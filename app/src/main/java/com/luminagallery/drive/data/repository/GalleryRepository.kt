package com.luminagallery.drive.data.repository

import androidx.room.withTransaction
import com.luminagallery.drive.data.local.GalleryDatabase
import com.luminagallery.drive.data.local.dao.LightFolderRow
import com.luminagallery.drive.data.local.dao.ParentCoverSummaryRow
import com.luminagallery.drive.data.local.entity.DriveItemEntity
import com.luminagallery.drive.data.local.entity.PinnedRootEntity
import com.luminagallery.drive.data.local.entity.SyncStateEntity
import com.luminagallery.drive.data.remote.DriveApiService
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.remote.dto.DriveFileDto
import com.luminagallery.drive.domain.model.AlbumCoverPreview
import com.luminagallery.drive.domain.model.BreadcrumbItem
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.domain.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Single source of truth implementing Stale-While-Revalidate:
 *  - Every UI screen reads reactively from Room (<50ms cold/warm render).
 *  - Network syncs run silently on Dispatchers.IO using full `nextPageToken` pagination
 *    and Google Drive's `changes.list` delta API (`startPageToken`) for minimal radio wakeup.
 */
class GalleryRepository(
    private val database: GalleryDatabase,
    private val driveApi: DriveApiService,
    private val networkMonitor: NetworkMonitor
) {
    private val itemDao = database.driveItemDao()
    private val pinnedRootDao = database.pinnedRootDao()
    private val syncStateDao = database.syncStateDao()

    private val syncMutex = Mutex()
    private val isSyncingFlow = MutableStateFlow(false)
    private val lastSyncErrorFlow = MutableStateFlow<String?>(null)

    val syncStatusFlow: Flow<SyncStatus> = combine(
        isSyncingFlow,
        syncStateDao.observeSyncState(),
        lastSyncErrorFlow
    ) { syncing, state, error ->
        SyncStatus(
            isSyncing = syncing,
            lastSyncEpochMs = state?.lastDeltaSyncEpochMs ?: state?.lastFullSyncEpochMs,
            initialScanComplete = state?.initialScanComplete == true,
            errorMessage = error
        )
    }

    private data class FolderRollup(
        val directSubfolderCount: Int,
        val totalMediaCount: Int,
        val coverItemId: String?,
        val coverThumbnailLink: String?,
        val coverIsVideo: Boolean,
        val coverSizeBytes: Long,
        val coverScore: Long,
        val previewCovers: List<AlbumCoverPreview>
    )

    private val folderRollupsFlow: Flow<Map<String, FolderRollup>> = combine(
        itemDao.observeAllFoldersLight(),
        itemDao.observeParentCoverSummaries()
    ) { allFolders, mediaSummaries ->
        computeRecursiveRollups(allFolders, mediaSummaries)
    }.flowOn(Dispatchers.Default)

    fun observePinnedRoots(): Flow<List<DriveFolder>> = combine(
        pinnedRootDao.observePinnedRootsWithStats(),
        folderRollupsFlow
    ) { rows, rollups ->
        rows.map { row ->
            val rollup = rollups[row.folderId]
            DriveFolder(
                id = row.folderId,
                parentId = null,
                name = row.displayName,
                subfolderCount = rollup?.directSubfolderCount ?: row.subfolderCount,
                mediaCount = rollup?.totalMediaCount ?: row.mediaCount,
                coverThumbnailLink = rollup?.coverThumbnailLink ?: row.coverThumbnailLink,
                coverItemId = rollup?.coverItemId ?: row.coverItemId,
                modifiedTimeEpochMs = row.modifiedTimeEpochMs,
                isPinned = true,
                isLocal = false,
                coverIsVideo = rollup?.coverIsVideo ?: false,
                coverSizeBytes = rollup?.coverSizeBytes ?: 0L,
                previewCovers = rollup?.previewCovers.orEmpty()
            )
        }
    }.flowOn(Dispatchers.Default)

    fun observePinnedRootIds(): Flow<Set<String>> =
        pinnedRootDao.observePinnedIds().map { it.toSet() }

    /**
     * Full parentId -> direct child DriveFolders map with recursive rollups & collage covers,
     * powering Samsung-style expandable Drive folders in the left album sidebar.
     */
    fun observeAllDriveSubfoldersByParent(): Flow<Map<String, List<DriveFolder>>> = combine(
        itemDao.observeAllFoldersLight(),
        folderRollupsFlow
    ) { allFolders, rollups ->
        val byParent = HashMap<String, MutableList<DriveFolder>>()
        for (row in allFolders) {
            val parent = row.parentId ?: continue
            val rollup = rollups[row.id]
            val folder = DriveFolder(
                id = row.id,
                parentId = parent,
                name = row.name,
                subfolderCount = rollup?.directSubfolderCount ?: 0,
                mediaCount = rollup?.totalMediaCount ?: 0,
                coverThumbnailLink = rollup?.coverThumbnailLink,
                coverItemId = rollup?.coverItemId,
                modifiedTimeEpochMs = row.modifiedTimeEpochMs,
                isPinned = false,
                isLocal = false,
                coverIsVideo = rollup?.coverIsVideo ?: false,
                coverSizeBytes = rollup?.coverSizeBytes ?: 0L,
                previewCovers = rollup?.previewCovers.orEmpty()
            )
            byParent.getOrPut(parent) { mutableListOf() }.add(folder)
        }
        byParent.values.forEach { list -> list.sortBy { it.name.lowercase() } }
        byParent
    }.flowOn(Dispatchers.Default)

    fun observeSubfolders(parentId: String): Flow<List<DriveFolder>> = combine(
        itemDao.observeSubfoldersWithStats(parentId),
        folderRollupsFlow
    ) { rows, rollups ->
        rows.map { row ->
            val rollup = rollups[row.id]
            DriveFolder(
                id = row.id,
                parentId = row.parentId,
                name = row.name,
                subfolderCount = rollup?.directSubfolderCount ?: row.subfolderCount,
                mediaCount = rollup?.totalMediaCount ?: row.mediaCount,
                coverThumbnailLink = rollup?.coverThumbnailLink ?: row.coverThumbnailLink,
                coverItemId = rollup?.coverItemId ?: row.coverItemId,
                modifiedTimeEpochMs = row.modifiedTimeEpochMs,
                isPinned = row.isPinned,
                isLocal = false,
                coverIsVideo = rollup?.coverIsVideo ?: false,
                coverSizeBytes = rollup?.coverSizeBytes ?: 0L,
                previewCovers = rollup?.previewCovers.orEmpty()
            )
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Computes deep recursive cover thumbnails, up-to-4 Samsung collage covers, & total media counts
     * across arbitrary nested Drive folder depths (folder -> folder -> folder -> ... -> photos) in O(V+E) time.
     * Prioritizes real image thumbnails over videos/huge files so covers always render crisply.
     */
    private fun computeRecursiveRollups(
        folders: List<LightFolderRow>,
        mediaSummaries: List<ParentCoverSummaryRow>
    ): Map<String, FolderRollup> {
        val childrenByParent = HashMap<String, MutableList<String>>(folders.size)
        val allFolderIds = HashSet<String>(folders.size + mediaSummaries.size)

        for (folder in folders) {
            allFolderIds.add(folder.id)
            val p = folder.parentId
            if (!p.isNullOrBlank()) {
                allFolderIds.add(p)
                childrenByParent.getOrPut(p) { mutableListOf() }.add(folder.id)
            }
        }

        val directMediaByParent = HashMap<String, ParentCoverSummaryRow>(mediaSummaries.size)
        for (summary in mediaSummaries) {
            allFolderIds.add(summary.parentId)
            directMediaByParent[summary.parentId] = summary
        }

        val memo = HashMap<String, FolderRollup>(allFolderIds.size)
        val visiting = HashSet<String>()

        fun dfs(folderId: String): FolderRollup {
            memo[folderId]?.let { return it }
            if (!visiting.add(folderId)) {
                return FolderRollup(0, 0, null, null, false, 0L, Long.MIN_VALUE, emptyList())
            }

            val direct = directMediaByParent[folderId]
            var totalMedia = direct?.mediaCount ?: 0
            var bestCoverId = direct?.coverItemId
            var bestCoverLink = direct?.coverThumbnailLink
            var bestIsVideo = direct?.coverMediaType == MediaType.VIDEO.name
            var bestSizeBytes = direct?.coverSizeBytes ?: 0L
            var bestScore = direct?.latestEpochMs ?: Long.MIN_VALUE

            val directPreview = direct?.coverItemId?.let { id ->
                AlbumCoverPreview(
                    itemId = id,
                    thumbnailLink = direct.coverThumbnailLink,
                    isLocal = false,
                    isVideo = direct.coverMediaType == MediaType.VIDEO.name,
                    sizeBytes = direct.coverSizeBytes
                )
            }

            val childFolders = childrenByParent[folderId].orEmpty()
            val childRollups = childFolders.map { childId -> dfs(childId) }
                .sortedByDescending { it.coverScore }

            for (childRollup in childRollups) {
                totalMedia += childRollup.totalMediaCount
                if (childRollup.coverItemId != null && childRollup.coverScore > bestScore) {
                    bestScore = childRollup.coverScore
                    bestCoverId = childRollup.coverItemId
                    bestCoverLink = childRollup.coverThumbnailLink
                    bestIsVideo = childRollup.coverIsVideo
                    bestSizeBytes = childRollup.coverSizeBytes
                }
            }

            // Build up to 4 distinct collage covers for folders containing subfolders (Samsung-style split!)
            val previews = LinkedHashMap<String, AlbumCoverPreview>(4)
            // First pass: 1 primary cover from each distinct child subfolder so the collage showcases different sub-albums
            for (childRollup in childRollups) {
                if (previews.size >= 4) break
                val primary = childRollup.previewCovers.firstOrNull() ?: childRollup.coverItemId?.let { id ->
                    AlbumCoverPreview(
                        itemId = id,
                        thumbnailLink = childRollup.coverThumbnailLink,
                        isLocal = false,
                        isVideo = childRollup.coverIsVideo,
                        sizeBytes = childRollup.coverSizeBytes
                    )
                }
                if (primary != null) {
                    previews.putIfAbsent(primary.itemId, primary)
                }
            }
            // Include direct folder media cover if present
            if (directPreview != null && previews.size < 4) {
                previews.putIfAbsent(directPreview.itemId, directPreview)
            }
            // Second pass: fill any remaining slots from nested sub-subfolders so 2x2 stays lush
            if (previews.size < 4) {
                for (childRollup in childRollups) {
                    for (nested in childRollup.previewCovers) {
                        if (previews.size >= 4) break
                        previews.putIfAbsent(nested.itemId, nested)
                    }
                    if (previews.size >= 4) break
                }
            }

            visiting.remove(folderId)
            return FolderRollup(
                directSubfolderCount = childFolders.size,
                totalMediaCount = totalMedia,
                coverItemId = bestCoverId,
                coverThumbnailLink = bestCoverLink,
                coverIsVideo = bestIsVideo,
                coverSizeBytes = bestSizeBytes,
                coverScore = bestScore,
                previewCovers = previews.values.toList()
            ).also { memo[folderId] = it }
        }

        for (id in allFolderIds) {
            dfs(id)
        }
        return memo
    }

    fun observeMediaInFolder(parentId: String): Flow<List<MediaItem>> =
        itemDao.observeMediaInFolder(parentId).map { entities ->
            entities.map(DriveMapper::entityToMediaItem)
        }

    fun observeFlattenedMedia(rootFolderId: String): Flow<List<MediaItem>> =
        itemDao.observeFlattenedMediaRecursive(rootFolderId).map { entities ->
            entities.map(DriveMapper::entityToMediaItem)
        }

    fun searchMedia(
        query: String,
        mediaType: MediaType?,
        fromEpochMs: Long?,
        toEpochMs: Long?
    ): Flow<List<MediaItem>> =
        itemDao.searchMedia(
            query = query.trim(),
            mediaType = mediaType?.name,
            fromEpochMs = fromEpochMs,
            toEpochMs = toEpochMs
        ).map { entities ->
            entities.map(DriveMapper::entityToMediaItem)
        }

    fun observeCachedMediaCount(): Flow<Int> = itemDao.observeCachedMediaCount()

    fun observeCachedFolderCount(): Flow<Int> = itemDao.observeCachedFolderCount()

    suspend fun getBreadcrumbChain(folderId: String): List<BreadcrumbItem> =
        withContext(Dispatchers.IO) {
            itemDao.getAncestorChain(folderId).map { row ->
                BreadcrumbItem(id = row.id, name = row.name)
            }
        }

    suspend fun getFolderPathString(folderId: String?): String =
        withContext(Dispatchers.IO) {
            if (folderId.isNullOrBlank()) return@withContext "Google Drive"
            val chain = itemDao.getAncestorChain(folderId)
            if (chain.isEmpty()) "Google Drive" else chain.joinToString(" / ") { it.name }
        }

    suspend fun getItemById(id: String): MediaItem? = withContext(Dispatchers.IO) {
        itemDao.getById(id)?.takeUnless { it.isFolder }?.let(DriveMapper::entityToMediaItem)
    }

    /**
     * Performs delta sync via `changes.list` once an initial scan has recorded a `startPageToken`,
     * or executes an initial tree scan of all pinned roots if none has completed yet.
     */
    suspend fun performSmartSync(forceFullRescan: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!networkMonitor.isOnline()) {
                return@withContext Result.success(Unit)
            }
            if (!syncMutex.tryLock()) {
                return@withContext Result.success(Unit)
            }
            isSyncingFlow.value = true
            lastSyncErrorFlow.value = null
            try {
                val state = syncStateDao.getSyncState()
                val shouldRunDelta = !forceFullRescan &&
                    state != null &&
                    state.initialScanComplete &&
                    !state.changesStartPageToken.isNullOrBlank()

                if (shouldRunDelta) {
                    runDeltaSyncLocked(state.changesStartPageToken)
                    // Ensure any pinned root with unindexed children gets its full tree scanned
                    val pinnedRoots = pinnedRootDao.getAll()
                    for (root in pinnedRoots) {
                        if (itemDao.getChildCount(root.folderId) == 0) {
                            syncFolderTree(root.folderId)
                        }
                    }
                } else {
                    runInitialScanLocked()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                lastSyncErrorFlow.value = e.message ?: "Sync failed"
                Result.failure(e)
            } finally {
                isSyncingFlow.value = false
                syncMutex.unlock()
            }
        }

    /**
     * Syncs a single folder's direct children with complete `nextPageToken` pagination,
     * reconciling additions, updates, and removals atomically inside a Room transaction.
     */
    suspend fun syncFolderChildren(folderId: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (!networkMonitor.isOnline()) {
                return@withContext Result.success(emptyList())
            }
            runCatching {
                val childSubfolderIds = fetchAndPersistFolderChildren(folderId)
                pinnedRootDao.updateLastSynced(folderId, System.currentTimeMillis())
                childSubfolderIds
            }.onFailure { e ->
                lastSyncErrorFlow.value = e.message
            }
        }

    /**
     * Recursively indexes an entire nested Drive folder hierarchy (folder-inside-folder)
     * with bounded HTTP/2 parallelism (4 concurrent folder queries) and full pagination.
     */
    suspend fun syncFolderTree(rootFolderId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!networkMonitor.isOnline()) {
                return@withContext Result.success(Unit)
            }
            runCatching {
                val semaphore = Semaphore(MAX_CONCURRENT_FOLDER_SCANS)
                val visited = mutableSetOf<String>()
                var frontier = listOf(rootFolderId)

                while (frontier.isNotEmpty()) {
                    val currentBatch = frontier.filter { visited.add(it) }
                    if (currentBatch.isEmpty()) break

                    frontier = coroutineScope {
                        currentBatch.map { folderId ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    fetchAndPersistFolderChildren(folderId)
                                }
                            }
                        }.awaitAll().flatten()
                    }
                }
                pinnedRootDao.updateLastSynced(rootFolderId, System.currentTimeMillis())
            }.onFailure { e ->
                lastSyncErrorFlow.value = e.message
            }
        }

    /**
     * Fetches the authenticated Google user's email & display name via `drive/v3/about`.
     */
    suspend fun fetchSignedInUserProfile(): Result<Pair<String, String?>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val about = driveApi.getAbout()
                val email = about.user?.emailAddress?.takeIf { it.isNotBlank() } ?: "Google Account"
                val name = about.user?.displayName?.takeIf { it.isNotBlank() }
                email to name
            }
        }

    /**
     * Queries Google Drive for top-level folders accessible to the active account:
     *  - For Google Sign-In: fast-queries My Drive root folders + folders shared with you.
     *  - For Service Account: discovers top-level shared folders.
     */
    suspend fun discoverSharedRootFolders(): Result<List<DriveFolder>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rootChildren = runCatching {
                    fetchAllPages(
                        query = "'root' in parents and mimeType = '${DriveConstants.FOLDER_MIME_TYPE}' and trashed = false",
                        orderBy = "name"
                    )
                }.getOrDefault(emptyList())

                val sharedWithMe = runCatching {
                    fetchAllPages(
                        query = "sharedWithMe = true and mimeType = '${DriveConstants.FOLDER_MIME_TYPE}' and trashed = false",
                        orderBy = "name"
                    )
                }.getOrDefault(emptyList())

                val combined = (rootChildren + sharedWithMe).distinctBy { it.id }

                val topLevel = if (combined.isNotEmpty()) {
                    combined
                } else {
                    val allFolders = fetchAllPages(
                        query = "mimeType = '${DriveConstants.FOLDER_MIME_TYPE}' and trashed = false",
                        orderBy = "name"
                    )
                    val folderIdSet = allFolders.mapTo(HashSet(allFolders.size)) { it.id }
                    allFolders.filter { dto ->
                        dto.parents.isEmpty() || dto.parents.none { it in folderIdSet }
                    }.ifEmpty { allFolders }
                }

                val pinnedIds = pinnedRootDao.getAll().mapTo(HashSet()) { it.folderId }

                // Persist discovered folders into Room so breadcrumbs & stats resolve immediately
                val entities = topLevel.map { DriveMapper.dtoToEntity(it) }
                if (entities.isNotEmpty()) {
                    itemDao.upsertAll(entities)
                }

                topLevel.map { dto ->
                    val entity = DriveMapper.dtoToEntity(dto)
                    DriveFolder(
                        id = entity.id,
                        parentId = entity.parentId,
                        name = entity.name,
                        subfolderCount = 0,
                        mediaCount = 0,
                        coverThumbnailLink = entity.thumbnailLink,
                        coverItemId = null,
                        modifiedTimeEpochMs = entity.modifiedTimeEpochMs,
                        isPinned = entity.id in pinnedIds
                    )
                }.sortedBy { it.name.lowercase() }
            }
        }

    /**
     * Validates a pasted Google Drive Folder URL or raw ID via `files.get`,
     * pins it as a main Album, persists its node, and kicks off its recursive metadata scan.
     */
    suspend fun pinRootFolderByUrlOrId(
        rawUrlOrId: String,
        customDisplayName: String? = null
    ): Result<DriveFolder> = withContext(Dispatchers.IO) {
        runCatching {
            val folderId = DriveConstants.extractFolderId(rawUrlOrId)
                ?: throw IllegalArgumentException(
                    "Invalid Google Drive folder URL or ID. Paste a link like https://drive.google.com/drive/folders/..."
                )

            val dto = driveApi.getFile(fileId = folderId)
            require(dto.mimeType == DriveConstants.FOLDER_MIME_TYPE) {
                "The provided Drive ID points to a file ('${dto.name}'), not a folder."
            }
            require(!dto.trashed) {
                "This Google Drive folder is in the Trash."
            }

            val displayName = customDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: dto.name
            val entity = DriveMapper.dtoToEntity(dto)

            database.withTransaction {
                itemDao.upsert(entity)
                pinnedRootDao.upsert(
                    PinnedRootEntity(
                        folderId = dto.id,
                        displayName = displayName,
                        pinnedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }

            // Ensure a delta startPageToken exists if this is the user's first pinned root
            if (syncStateDao.getSyncState()?.changesStartPageToken == null) {
                val token = driveApi.getStartPageToken().startPageToken
                syncStateDao.upsert(
                    SyncStateEntity(
                        id = SyncStateEntity.SINGLETON_ID,
                        changesStartPageToken = token,
                        lastFullSyncEpochMs = System.currentTimeMillis(),
                        lastDeltaSyncEpochMs = System.currentTimeMillis(),
                        initialScanComplete = true
                    )
                )
            }

            DriveFolder(
                id = dto.id,
                parentId = entity.parentId,
                name = displayName,
                subfolderCount = 0,
                mediaCount = 0,
                coverThumbnailLink = null,
                coverItemId = null,
                modifiedTimeEpochMs = entity.modifiedTimeEpochMs,
                isPinned = true
            )
        }
    }

    suspend fun togglePinFolder(folder: DriveFolder): Boolean = withContext(Dispatchers.IO) {
        val currentlyPinned = pinnedRootDao.getAll().any { it.folderId == folder.id }
        if (currentlyPinned) {
            pinnedRootDao.deleteById(folder.id)
            false
        } else {
            pinnedRootDao.upsert(
                PinnedRootEntity(
                    folderId = folder.id,
                    displayName = folder.name,
                    pinnedAtEpochMs = System.currentTimeMillis()
                )
            )
            true
        }
    }

    suspend fun unpinRootFolder(folderId: String) = withContext(Dispatchers.IO) {
        pinnedRootDao.deleteById(folderId)
    }

    suspend fun resetMetadataCache() = withContext(Dispatchers.IO) {
        database.withTransaction {
            itemDao.clearAll()
            syncStateDao.clear()
        }
    }

    private suspend fun runInitialScanLocked() {
        val startToken = driveApi.getStartPageToken().startPageToken
        val pinnedRoots = pinnedRootDao.getAll()

        if (pinnedRoots.isNotEmpty()) {
            // Ensure each pinned root itself exists in drive_items for recursive breadcrumbs
            val rootEntities = pinnedRoots.mapNotNull { root ->
                runCatching { driveApi.getFile(root.folderId) }.getOrNull()
                    ?.let { DriveMapper.dtoToEntity(it) }
            }
            if (rootEntities.isNotEmpty()) {
                itemDao.upsertAll(rootEntities)
            }

            for (root in pinnedRoots) {
                syncFolderTree(root.folderId)
            }
        }

        val now = System.currentTimeMillis()
        syncStateDao.upsert(
            SyncStateEntity(
                id = SyncStateEntity.SINGLETON_ID,
                changesStartPageToken = startToken,
                lastFullSyncEpochMs = now,
                lastDeltaSyncEpochMs = now,
                initialScanComplete = true
            )
        )
    }

    private suspend fun runDeltaSyncLocked(startPageToken: String) {
        var pageToken: String? = startPageToken
        var newStartPageToken: String? = null
        val upserts = mutableListOf<DriveItemEntity>()
        val removals = mutableListOf<String>()

        while (!pageToken.isNullOrBlank()) {
            val response = driveApi.listChanges(pageToken = pageToken)
            for (change in response.changes) {
                val fileDto = change.file
                if (change.removed || fileDto == null || fileDto.trashed) {
                    removals.add(change.fileId)
                } else if (DriveMapper.isSupportedGalleryDto(fileDto)) {
                    upserts.add(DriveMapper.dtoToEntity(fileDto))
                }
            }
            if (!response.newStartPageToken.isNullOrBlank()) {
                newStartPageToken = response.newStartPageToken
            }
            pageToken = response.nextPageToken
        }

        val now = System.currentTimeMillis()
        database.withTransaction {
            if (removals.isNotEmpty()) {
                removals.chunked(SQLITE_MAX_BIND_PARAMS).forEach { chunk ->
                    itemDao.deleteByIds(chunk)
                }
            }
            if (upserts.isNotEmpty()) {
                itemDao.upsertAll(upserts)
            }
            val previous = syncStateDao.getSyncState()
            syncStateDao.upsert(
                SyncStateEntity(
                    id = SyncStateEntity.SINGLETON_ID,
                    changesStartPageToken = newStartPageToken ?: startPageToken,
                    lastFullSyncEpochMs = previous?.lastFullSyncEpochMs ?: now,
                    lastDeltaSyncEpochMs = now,
                    initialScanComplete = true
                )
            )
        }
    }

    private suspend fun fetchAndPersistFolderChildren(folderId: String): List<String> {
        val escapedId = folderId.replace("'", "\\'")
        val query = "'$escapedId' in parents and trashed = false"
        val dtos = fetchAllPages(query = query, orderBy = "folder,createdTime desc")
        val supported = dtos.filter(DriveMapper::isSupportedGalleryDto)
        val entities = supported.map { DriveMapper.dtoToEntity(it, fallbackParentId = folderId) }
        val remoteIds = entities.mapTo(HashSet(entities.size)) { it.id }

        database.withTransaction {
            val existingChildIds = itemDao.getChildIdsInParent(folderId)
            val staleIds = existingChildIds.filterNot { it in remoteIds }
            if (staleIds.isNotEmpty()) {
                staleIds.chunked(SQLITE_MAX_BIND_PARAMS).forEach { chunk ->
                    itemDao.deleteByIds(chunk)
                }
            }
            if (entities.isNotEmpty()) {
                itemDao.upsertAll(entities)
            }
        }

        return entities.filter { it.isFolder }.map { it.id }
    }

    /**
     * Exhaustive pagination loop across `nextPageToken` so folders with 1,000+ or 50,000+
     * items are indexed completely without truncation.
     */
    private suspend fun fetchAllPages(
        query: String,
        orderBy: String?
    ): List<DriveFileDto> {
        val results = mutableListOf<DriveFileDto>()
        var nextPageToken: String? = null
        do {
            val response = driveApi.listFiles(
                query = query,
                pageSize = DriveConstants.DEFAULT_PAGE_SIZE,
                pageToken = nextPageToken,
                orderBy = orderBy
            )
            results.addAll(response.files)
            nextPageToken = response.nextPageToken
        } while (!nextPageToken.isNullOrBlank())
        return results
    }

    private companion object {
        const val MAX_CONCURRENT_FOLDER_SCANS = 4
        const val SQLITE_MAX_BIND_PARAMS = 450
    }
}

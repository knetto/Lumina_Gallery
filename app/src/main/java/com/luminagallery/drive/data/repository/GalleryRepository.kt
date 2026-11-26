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
import com.luminagallery.drive.domain.model.ExifMetadata
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.domain.model.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val folderMediaCache = ConcurrentHashMap<String, List<MediaItem>>()
    private val subfoldersCache = ConcurrentHashMap<String, List<DriveFolder>>()
    private val lastFolderSyncEpochMs = ConcurrentHashMap<String, Long>()
    private val driveRichExifCache = ConcurrentHashMap<String, ExifMetadata>()

    suspend fun fetchRichDriveExif(item: MediaItem): ExifMetadata? = withContext(Dispatchers.IO) {
        if (item.isLocal) return@withContext null
        driveRichExifCache[item.id]?.let { return@withContext it }
        if (!networkMonitor.networkState.value.isOnline) return@withContext null
        val dto = runCatching {
            driveApi.getFile(fileId = item.id, fields = DriveConstants.DETAIL_FILE_FIELDS)
        }.getOrNull() ?: return@withContext null
        val img = dto.imageMediaMetadata ?: return@withContext null
        val loc = img.location
        val lat = loc?.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
        val lon = loc?.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
        val hasCoords = lat != null && lon != null && (lat != 0.0 || lon != 0.0)
        val alt = if (hasCoords) loc?.altitude?.takeIf { it.isFinite() && it in -500.0..15_000.0 } else null
        val flashDesc = img.flashUsed?.let { if (it) "Fired" else "Off" }
        val wb = img.whiteBalance?.trim()?.takeIf { it.isNotEmpty() }
            ?.replaceFirstChar { it.uppercase() }
        val metering = img.meteringMode?.trim()?.takeIf { it.isNotEmpty() }
            ?.replaceFirstChar { it.uppercase() }
        val colorSpace = img.colorSpace?.trim()?.takeIf { it.isNotEmpty() }
        val ew = item.effectiveWidth
        val eh = item.effectiveHeight
        val orientation = if (ew != null && eh != null) {
            when {
                ew > eh -> "Landscape"
                eh > ew -> "Portrait"
                else -> "Square"
            }
        } else null
        val enriched = item.exif.copy(
            cameraMake = img.cameraMake?.trim()?.takeIf { it.isNotEmpty() } ?: item.exif.cameraMake,
            cameraModel = img.cameraModel?.trim()?.takeIf { it.isNotEmpty() } ?: item.exif.cameraModel,
            lens = img.lens?.trim()?.takeIf { it.isNotEmpty() } ?: item.exif.lens,
            aperture = img.aperture ?: item.exif.aperture,
            exposureTime = img.exposureTime ?: item.exif.exposureTime,
            isoSpeed = img.isoSpeed ?: item.exif.isoSpeed,
            focalLength = img.focalLength ?: item.exif.focalLength,
            exposureBiasEv = img.exposureBias ?: item.exif.exposureBiasEv,
            flashDescription = flashDesc ?: item.exif.flashDescription,
            whiteBalance = wb ?: item.exif.whiteBalance,
            meteringMode = metering ?: item.exif.meteringMode,
            colorSpace = colorSpace ?: item.exif.colorSpace,
            orientationLabel = orientation ?: item.exif.orientationLabel,
            exifDateOriginal = img.time?.trim()?.takeIf { it.isNotEmpty() } ?: item.exif.exifDateOriginal,
            latitude = if (hasCoords) lat else item.exif.latitude,
            longitude = if (hasCoords) lon else item.exif.longitude,
            altitudeMeters = alt ?: item.exif.altitudeMeters
        )
        if (driveRichExifCache.size > 400) driveRichExifCache.clear()
        driveRichExifCache[item.id] = enriched
        enriched
    }

    @Volatile
    private var latestFolderParentById: Map<String, String?> = emptyMap()

    fun getCachedFolderMedia(parentId: String): List<MediaItem>? = folderMediaCache[parentId]
    fun getCachedSubfolders(parentId: String): List<DriveFolder>? = subfoldersCache[parentId]

    /**
     * Expands a set of locked/hidden folder IDs to include all nested Drive descendant folders
     * so locking a parent Drive album also hides its subfolders across Search, Stories, Special
     * screens, and Flattened view.
     */
    fun expandBlockedFolderIds(blockedIds: Set<String>): Set<String> {
        if (blockedIds.isEmpty()) return emptySet()
        val parentMap = latestFolderParentById
        if (parentMap.isEmpty()) return blockedIds
        val expanded = HashSet<String>(blockedIds)
        for (folderId in parentMap.keys) {
            if (folderId in expanded) continue
            var cursor = parentMap[folderId]
            var depth = 0
            while (cursor != null && depth < 32) {
                if (cursor in blockedIds) {
                    expanded.add(folderId)
                    break
                }
                cursor = parentMap[cursor]
                depth++
            }
        }
        return expanded
    }

    private val folderRollupsFlow: Flow<Map<String, FolderRollup>> = combine(
        itemDao.observeAllFoldersLight(),
        itemDao.observeParentCoverSummaries()
    ) { allFolders, mediaSummaries ->
        val pMap = HashMap<String, String?>(allFolders.size)
        for (f in allFolders) pMap[f.id] = f.parentId
        latestFolderParentById = pMap
        computeRecursiveRollups(allFolders, mediaSummaries)
    }.flowOn(Dispatchers.Default).shareIn(
        scope = repoScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 10_000L, replayExpirationMillis = 30_000L),
        replay = 1
    )

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
    private val sharedDriveSubfoldersByParent: Flow<Map<String, List<DriveFolder>>> = combine(
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
        byParent.forEach { (parentId, list) -> subfoldersCache[parentId] = list }
        byParent
    }.flowOn(Dispatchers.Default).shareIn(
        scope = repoScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 10_000L, replayExpirationMillis = 30_000L),
        replay = 1
    )

    fun observeAllDriveSubfoldersByParent(): Flow<Map<String, List<DriveFolder>>> =
        sharedDriveSubfoldersByParent

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
        }.also { subfoldersCache[parentId] = it }
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
            entities.map(DriveMapper::entityToMediaItem).also {
                if (folderMediaCache.size > 120) folderMediaCache.clear()
                folderMediaCache[parentId] = it
            }
        }.flowOn(Dispatchers.Default)

    fun observeFlattenedMedia(rootFolderId: String): Flow<List<MediaItem>> =
        itemDao.observeFlattenedMediaRecursive(rootFolderId).map { entities ->
            entities.map(DriveMapper::entityToMediaItem)
        }.flowOn(Dispatchers.Default)

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
     * Performs fast delta sync via `changes.list` once an initial scan has recorded a `startPageToken`,
     * or executes a parallel initial tree scan of all pinned roots if none has completed yet.
     * Expired Google Drive `thumbnailLink` URLs are self-healed on demand by `DriveThumbnailFetcher`,
     * so we no longer force slow full-tree rescans every few hours.
     */
    suspend fun performSmartSync(
        forceFullRescan: Boolean = false,
        waitIfBusy: Boolean = false
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!networkMonitor.isOnline()) {
                return@withContext Result.success(Unit)
            }
            if (waitIfBusy) {
                syncMutex.lock()
            } else if (!syncMutex.tryLock()) {
                return@withContext Result.success(Unit)
            }
            isSyncingFlow.value = true
            lastSyncErrorFlow.value = null
            try {
                val state = syncStateDao.getSyncState()
                val startToken = state?.changesStartPageToken
                val shouldRunDelta = shouldUseDeltaSync(
                    forceFullRescan = forceFullRescan,
                    initialScanComplete = state?.initialScanComplete == true,
                    changesStartPageToken = startToken
                )

                if (shouldRunDelta && !startToken.isNullOrBlank()) {
                    var deltaSucceeded = true
                    try {
                        runDeltaSyncLocked(startToken)
                    } catch (http: HttpException) {
                        if (http.code() == 400 || http.code() == 410) {
                            // Google Drive occasionally invalidates very old startPageTokens; recover cleanly.
                            deltaSucceeded = false
                            runInitialScanLocked()
                        } else {
                            throw http
                        }
                    }

                    if (deltaSucceeded) {
                        // Ensure any newly restored pinned root has its folder node & initial subtree indexed
                        val pinnedRoots = pinnedRootDao.getAll()
                        val missingNodes = coroutineScope {
                            pinnedRoots.map { root ->
                                async(Dispatchers.IO) {
                                    if (itemDao.getById(root.folderId) == null) {
                                        runCatching { driveApi.getFile(root.folderId) }.getOrNull()
                                            ?.let { DriveMapper.dtoToEntity(it) }
                                    } else {
                                        null
                                    }
                                }
                            }.awaitAll().filterNotNull()
                        }
                        if (missingNodes.isNotEmpty()) {
                            itemDao.upsertAll(missingNodes)
                        }

                        val rootsNeedingTree = pinnedRoots.filter { root ->
                            root.lastSyncedEpochMs == null && itemDao.getChildCount(root.folderId) == 0
                        }.map { it.folderId }
                        if (rootsNeedingTree.isNotEmpty()) {
                            syncFolderTreesParallel(rootsNeedingTree)
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
     * When `force = false` (background silent refresh on folder open), skips redundant network
     * calls if this folder was already synced recently.
     */
    suspend fun syncFolderChildren(
        folderId: String,
        force: Boolean = true
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (!networkMonitor.isOnline()) {
                return@withContext Result.success(emptyList())
            }
            if (!force) {
                val lastSynced = lastFolderSyncEpochMs[folderId] ?: 0L
                if (lastSynced > 0L && (System.currentTimeMillis() - lastSynced) < SILENT_FOLDER_SYNC_COOLDOWN_MS) {
                    return@withContext Result.success(emptyList())
                }
            }
            runCatching {
                val remote = fetchFolderChildrenRemote(folderId)
                persistFolderBatches(listOf(remote.batch))
                pinnedRootDao.updateLastSynced(folderId, System.currentTimeMillis())
                remote.childSubfolderIds
            }.onFailure { e ->
                lastSyncErrorFlow.value = e.message
            }
        }

    /**
     * Recursively indexes an entire nested Drive folder hierarchy (folder-inside-folder)
     * with dynamic HTTP/2 work-stealing concurrency and batched Room commits.
     */
    suspend fun syncFolderTree(rootFolderId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!networkMonitor.isOnline()) {
                return@withContext Result.success(Unit)
            }
            runCatching {
                syncFolderTreesParallel(listOf(rootFolderId))
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

                persistPickerFolders(topLevel)
            }
        }

    suspend fun browseComputerFolders(parentId: String?): Result<List<DriveFolder>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val foldersOnly = "mimeType = '${DriveConstants.FOLDER_MIME_TYPE}' and trashed = false"
                val folders = if (parentId == null) {
                    val myDriveRootId = driveApi.getFile(fileId = "root", fields = "id").id
                    fetchAllPages(
                        query = "'me' in owners and $foldersOnly",
                        orderBy = "name",
                        fields = "nextPageToken,files(id,name,mimeType,parents,createdTime,modifiedTime," +
                            "thumbnailLink,trashed,capabilities(canShare))"
                    ).filter { DriveMapper.isComputerRoot(it, myDriveRootId) }
                } else {
                    val escapedId = parentId.replace("\\", "\\\\").replace("'", "\\'")
                    fetchAllPages(
                        query = "'$escapedId' in parents and $foldersOnly",
                        orderBy = "name"
                    )
                }
                persistPickerFolders(folders, fallbackParentId = parentId)
            }
        }

    private suspend fun persistPickerFolders(
        folders: List<DriveFileDto>,
        fallbackParentId: String? = null
    ): List<DriveFolder> {
        if (folders.isEmpty()) return emptyList()
        val pinnedIds = pinnedRootDao.getAll().mapTo(HashSet()) { it.folderId }
        val entities = folders.distinctBy { it.id }.map {
            DriveMapper.dtoToEntity(it, fallbackParentId)
        }
        itemDao.upsertAll(entities)
        return entities.map { entity ->
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
            unpinRootFolder(folder.id)
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
        database.withTransaction {
            pinnedRootDao.deleteById(folderId)
            pruneUnreferencedDriveItemsLocked()
        }
        folderMediaCache.clear()
        subfoldersCache.clear()
        lastFolderSyncEpochMs.clear()
    }

    suspend fun resetMetadataCache() = withContext(Dispatchers.IO) {
        database.withTransaction {
            itemDao.clearAll()
            syncStateDao.clear()
        }
        folderMediaCache.clear()
        subfoldersCache.clear()
        lastFolderSyncEpochMs.clear()
        latestFolderParentById = emptyMap()
    }

    /** Clears all Google Drive metadata, pinned roots, Drive favorites, and caches on sign-out. */
    suspend fun clearDriveAccountData() = withContext(Dispatchers.IO) {
        database.withTransaction {
            itemDao.clearAll()
            pinnedRootDao.clearAll()
            syncStateDao.clear()
            database.galleryExtrasDao().clearDriveFavoriteItems()
        }
        folderMediaCache.clear()
        subfoldersCache.clear()
        lastFolderSyncEpochMs.clear()
        latestFolderParentById = emptyMap()
        lastSyncErrorFlow.value = null
    }

    private suspend fun pruneUnreferencedDriveItemsLocked() {
        val remainingRoots = pinnedRootDao.getAll()
        if (remainingRoots.isEmpty()) {
            itemDao.clearAll()
            return
        }
        val reachable = HashSet<String>()
        for (root in remainingRoots) {
            reachable.addAll(itemDao.getSubtreeItemIds(root.folderId))
        }
        val allIds = itemDao.getAllItemIds()
        val orphanIds = allIds.filterNot { it in reachable }
        if (orphanIds.isNotEmpty()) {
            orphanIds.chunked(SQLITE_MAX_BIND_PARAMS).forEach { chunk ->
                itemDao.deleteByIds(chunk)
            }
        }
    }

    private suspend fun runInitialScanLocked() {
        val startToken = driveApi.getStartPageToken().startPageToken
        val pinnedRoots = pinnedRootDao.getAll()

        if (pinnedRoots.isNotEmpty()) {
            // Fetch all pinned root folder nodes in parallel for recursive breadcrumbs
            val rootEntities = coroutineScope {
                pinnedRoots.map { root ->
                    async(Dispatchers.IO) {
                        runCatching { driveApi.getFile(root.folderId) }.getOrNull()
                            ?.let { DriveMapper.dtoToEntity(it) }
                    }
                }.awaitAll().filterNotNull()
            }
            if (rootEntities.isNotEmpty()) {
                itemDao.upsertAll(rootEntities)
            }

            // Scan all pinned roots and nested subfolders concurrently with batched Room commits
            syncFolderTreesParallel(pinnedRoots.map { it.folderId })

            database.withTransaction {
                pruneUnreferencedDriveItemsLocked()
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
        val pinnedRootIds = pinnedRootDao.getAll().mapTo(HashSet()) { it.folderId }
        val knownFolderIds = itemDao.getAllFolderIds().toHashSet().apply { addAll(pinnedRootIds) }
        val knownItemIds = itemDao.getAllItemIds().toHashSet()

        var pageToken: String? = startPageToken
        var newStartPageToken: String? = null
        val pendingUpserts = mutableListOf<DriveItemEntity>()
        val removals = mutableListOf<String>()

        while (!pageToken.isNullOrBlank()) {
            val response = driveApi.listChanges(pageToken = pageToken)
            for (change in response.changes) {
                val fileDto = change.file
                if (change.removed || fileDto == null || fileDto.trashed) {
                    if (change.fileId in knownItemIds) {
                        removals.add(change.fileId)
                    }
                } else if (DriveMapper.isSupportedGalleryDto(fileDto)) {
                    val entity = DriveMapper.dtoToEntity(fileDto)
                    pendingUpserts.add(entity)
                }
            }
            if (!response.newStartPageToken.isNullOrBlank()) {
                newStartPageToken = response.newStartPageToken
            }
            pageToken = response.nextPageToken
        }

        // Only accept upserts whose parent is inside a pinned tree (resolving multi-level new folders)
        val acceptedUpserts = mutableListOf<DriveItemEntity>()
        val remaining = pendingUpserts.toMutableList()
        var progressed = true
        while (progressed && remaining.isNotEmpty()) {
            progressed = false
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val entity = iter.next()
                val inTrackedTree = entity.id in pinnedRootIds ||
                    (entity.parentId != null && entity.parentId in knownFolderIds)
                if (inTrackedTree) {
                    acceptedUpserts.add(entity)
                    if (entity.isFolder) knownFolderIds.add(entity.id)
                    iter.remove()
                    progressed = true
                }
            }
        }
        // If an item previously in our cache moved OUT of pinned roots, remove it cleanly
        for (movedOut in remaining) {
            if (movedOut.id in knownItemIds) {
                removals.add(movedOut.id)
            }
        }

        val now = System.currentTimeMillis()
        database.withTransaction {
            if (removals.isNotEmpty()) {
                val expandedRemovals = LinkedHashSet<String>(removals.size)
                for (id in removals) {
                    if (id in knownFolderIds) {
                        expandedRemovals.addAll(itemDao.getSubtreeItemIds(id))
                    } else {
                        expandedRemovals.add(id)
                    }
                }
                expandedRemovals.chunked(SQLITE_MAX_BIND_PARAMS).forEach { chunk ->
                    itemDao.deleteByIds(chunk)
                }
            }
            if (acceptedUpserts.isNotEmpty()) {
                itemDao.upsertAll(acceptedUpserts)
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

    private data class FolderSyncBatch(
        val folderId: String,
        val entities: List<DriveItemEntity>,
        val remoteIds: Set<String>
    )

    private data class RemoteFolderChildren(
        val batch: FolderSyncBatch,
        val childSubfolderIds: List<String>
    )

    /**
     * Walks one or more Drive folder trees concurrently using dynamic HTTP/2 work-stealing
     * (no level-by-level barrier stalls behind huge folders!) and commits Room transactions in
     * batches so album rollups don't thrash on every single subfolder.
     */
    private suspend fun syncFolderTreesParallel(rootFolderIds: List<String>) {
        if (rootFolderIds.isEmpty()) return
        val semaphore = Semaphore(MAX_CONCURRENT_FOLDER_SCANS)
        val visited = ConcurrentHashMap.newKeySet<String>()
        val pendingBatches = ConcurrentLinkedQueue<FolderSyncBatch>()
        val flushMutex = Mutex()

        suspend fun walkFolder(folderId: String, depth: Int) {
            if (depth >= MAX_FOLDER_DEPTH || !visited.add(folderId)) return
            val remote = semaphore.withPermit {
                fetchFolderChildrenRemote(folderId)
            }
            pendingBatches.add(remote.batch)
            // Flush top-level folders immediately so album covers appear fast; batch deeper levels
            if (depth == 0 || pendingBatches.size >= FOLDER_DB_FLUSH_BATCH_SIZE) {
                flushPendingBatches(pendingBatches, flushMutex)
            }
            if (remote.childSubfolderIds.isNotEmpty()) {
                coroutineScope {
                    remote.childSubfolderIds.forEach { childId ->
                        launch(Dispatchers.IO) {
                            walkFolder(childId, depth + 1)
                        }
                    }
                }
            }
        }

        coroutineScope {
            rootFolderIds.forEach { rootId ->
                launch(Dispatchers.IO) {
                    walkFolder(rootId, depth = 0)
                }
            }
        }

        flushPendingBatches(pendingBatches, flushMutex)
        val now = System.currentTimeMillis()
        for (rootId in rootFolderIds) {
            pinnedRootDao.updateLastSynced(rootId, now)
        }
    }

    private suspend fun flushPendingBatches(
        queue: ConcurrentLinkedQueue<FolderSyncBatch>,
        flushMutex: Mutex
    ) {
        flushMutex.withLock {
            if (queue.isEmpty()) return
            val drained = ArrayList<FolderSyncBatch>(queue.size.coerceAtLeast(4))
            while (true) {
                val next = queue.poll() ?: break
                drained.add(next)
            }
            if (drained.isNotEmpty()) {
                persistFolderBatches(drained)
            }
        }
    }

    private suspend fun fetchFolderChildrenRemote(folderId: String): RemoteFolderChildren {
        val escapedId = folderId.replace("'", "\\'")
        val query = "'$escapedId' in parents and trashed = false"
        // Omit server-side orderBy so Google Drive uses its fastest index path; Room sorts locally.
        val dtos = fetchAllPages(query = query, orderBy = null)
        val supported = dtos.filter(DriveMapper::isSupportedGalleryDto)
        val entities = supported.map { DriveMapper.dtoToEntity(it, fallbackParentId = folderId) }
        val remoteIds = entities.mapTo(HashSet(entities.size)) { it.id }
        val childSubfolderIds = entities.filter { it.isFolder }.map { it.id }
        return RemoteFolderChildren(
            batch = FolderSyncBatch(
                folderId = folderId,
                entities = entities,
                remoteIds = remoteIds
            ),
            childSubfolderIds = childSubfolderIds
        )
    }

    private suspend fun persistFolderBatches(batches: List<FolderSyncBatch>) {
        if (batches.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            val allStaleWithDescendants = LinkedHashSet<String>()
            val allEntitiesToUpsert = ArrayList<DriveItemEntity>()
            for (batch in batches) {
                val existingChildIds = itemDao.getChildIdsInParent(batch.folderId)
                val staleIds = existingChildIds.filterNot { it in batch.remoteIds }
                for (staleId in staleIds) {
                    allStaleWithDescendants.addAll(itemDao.getSubtreeItemIds(staleId))
                }
                if (batch.entities.isNotEmpty()) {
                    allEntitiesToUpsert.addAll(batch.entities)
                }
            }
            if (allStaleWithDescendants.isNotEmpty()) {
                allStaleWithDescendants.chunked(SQLITE_MAX_BIND_PARAMS).forEach { chunk ->
                    itemDao.deleteByIds(chunk)
                }
            }
            if (allEntitiesToUpsert.isNotEmpty()) {
                itemDao.upsertAll(allEntitiesToUpsert)
            }
        }
        for (batch in batches) {
            lastFolderSyncEpochMs[batch.folderId] = now
        }
    }

    /**
     * Exhaustive pagination loop across `nextPageToken` so folders with 1,000+ or 50,000+
     * items are indexed completely without truncation.
     */
    private suspend fun fetchAllPages(
        query: String,
        orderBy: String?,
        fields: String = DriveConstants.FILES_LIST_FIELDS
    ): List<DriveFileDto> {
        val results = mutableListOf<DriveFileDto>()
        var nextPageToken: String? = null
        do {
            val response = driveApi.listFiles(
                query = query,
                fields = fields,
                pageSize = DriveConstants.DEFAULT_PAGE_SIZE,
                pageToken = nextPageToken,
                orderBy = orderBy
            )
            results.addAll(response.files)
            nextPageToken = response.nextPageToken
        } while (!nextPageToken.isNullOrBlank())
        return results
    }

    internal companion object {
        const val MAX_CONCURRENT_FOLDER_SCANS = 8
        const val FOLDER_DB_FLUSH_BATCH_SIZE = 10
        const val MAX_FOLDER_DEPTH = 32
        const val SQLITE_MAX_BIND_PARAMS = 450
        const val SILENT_FOLDER_SYNC_COOLDOWN_MS = 25_000L

        internal fun shouldUseDeltaSync(
            forceFullRescan: Boolean,
            initialScanComplete: Boolean,
            changesStartPageToken: String?
        ): Boolean = !forceFullRescan && initialScanComplete && !changesStartPageToken.isNullOrBlank()
    }
}

package com.luminagallery.drive.data.repository

import androidx.room.withTransaction
import com.luminagallery.drive.data.local.GalleryDatabase
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.local.entity.AlbumCollectionEntity
import com.luminagallery.drive.data.local.entity.AlbumLayoutEntity
import com.luminagallery.drive.data.local.entity.CustomAlbumCoverEntity
import com.luminagallery.drive.domain.model.AlbumCoverPreview
import com.luminagallery.drive.domain.model.AlbumGridItem
import com.luminagallery.drive.domain.model.AlbumSortMode
import com.luminagallery.drive.domain.model.BreadcrumbItem
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.SidebarAlbumNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Samsung Gallery-style unified Album Organization:
 *  - Mixes on-device phone albums + pinned Google Drive folders seamlessly in ONE grid.
 *  - Persists exact drag-to-reorder sort positions.
 *  - Supports infinite nested Collections inside Collections (grouping any mix of
 *    local albums, Drive folders, and sub-collections with recursive 2x2 collage covers).
 */
class AlbumOrganizationRepository(
    private val database: GalleryDatabase,
    private val localMediaRepository: LocalMediaRepository,
    private val galleryRepository: GalleryRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val preferencesManager: PreferencesManager
) {
    private val collectionDao = database.albumCollectionDao()
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gridCacheByParent = ConcurrentHashMap<String, List<AlbumGridItem>>()

    fun getCachedGridItems(parentCollectionId: String?): List<AlbumGridItem> =
        gridCacheByParent[parentCollectionId ?: ROOT_CACHE_KEY].orEmpty()

    fun currentSidebarTree(): List<SidebarAlbumNode> = sharedSidebarTreeState.value

    fun observeAllCollections(): Flow<List<AlbumCollectionEntity>> =
        collectionDao.observeAllCollections()

    fun observeAllAvailableAlbums(): Flow<List<DriveFolder>> = combine(
        localMediaRepository.observeLocalAlbums(),
        galleryRepository.observePinnedRoots()
    ) { local, drive ->
        local + drive
    }.flowOn(Dispatchers.Default)

    private fun applyCustomCovers(
        albums: List<DriveFolder>,
        customCovers: Map<String, CustomAlbumCoverEntity>
    ): List<DriveFolder> {
        if (customCovers.isEmpty()) return albums
        return albums.map { folder ->
            val override = customCovers[folder.id] ?: return@map folder
            val preview = AlbumCoverPreview(
                itemId = override.coverItemId,
                thumbnailLink = override.coverThumbnailLink,
                isLocal = override.isLocal,
                isVideo = override.isVideo,
                sizeBytes = override.sizeBytes
            )
            folder.copy(
                coverItemId = override.coverItemId,
                coverThumbnailLink = override.coverThumbnailLink,
                coverIsVideo = override.isVideo,
                coverSizeBytes = override.sizeBytes,
                previewCovers = listOf(preview)
            )
        }
    }

    private val albumSortModeFlow = preferencesManager.preferencesFlow
        .map { it.albumSortMode }
        .distinctUntilChanged()

    fun observeGridItems(parentCollectionId: String?): Flow<List<AlbumGridItem>> = combine(
        localMediaRepository.observeLocalAlbums(),
        galleryRepository.observePinnedRoots(),
        collectionDao.observeAllCollections(),
        collectionDao.observeAllLayouts(),
        combine(
            extrasRepository.hiddenAlbumIdsFlow,
            extrasRepository.lockedAlbumIdsFlow,
            albumSortModeFlow,
            extrasRepository.customAlbumCoversFlow
        ) { hidden, locked, sortMode, covers ->
            GridFilterBundle(hidden, locked, sortMode, covers)
        }
    ) { localAlbums, driveRoots, collections, layouts, bundle ->
        val visibleLocal = applyCustomCovers(
            localAlbums.filterNot { it.id in bundle.hiddenIds },
            bundle.customCovers
        ).map { if (it.id in bundle.lockedIds) it.copy(isLocked = true) else it }
        val visibleDrive = applyCustomCovers(
            driveRoots.filterNot { it.id in bundle.hiddenIds },
            bundle.customCovers
        ).map { if (it.id in bundle.lockedIds) it.copy(isLocked = true) else it }
        buildGridForParent(
            targetParentId = parentCollectionId,
            localAlbums = visibleLocal,
            driveRoots = visibleDrive,
            collections = collections,
            layouts = layouts,
            sortMode = bundle.sortMode
        ).also { gridCacheByParent[parentCollectionId ?: ROOT_CACHE_KEY] = it }
    }.flowOn(Dispatchers.Default)

    /**
     * Shared hot StateFlow powering Samsung-style expandable Albums + Album Groups + Nested
     * Google Drive Folders in the left sidebar rail — stays warm in memory so opening any
     * album NEVER stalls waiting for SQLite or recursive tree rebuilding!
     */
    private val sharedSidebarTreeState: StateFlow<List<SidebarAlbumNode>> = combine(
        localMediaRepository.observeLocalAlbums(),
        combine(
            galleryRepository.observePinnedRoots(),
            galleryRepository.observeAllDriveSubfoldersByParent()
        ) { roots, driveByParent -> roots to driveByParent },
        collectionDao.observeAllCollections(),
        collectionDao.observeAllLayouts(),
        combine(
            extrasRepository.hiddenAlbumIdsFlow,
            extrasRepository.lockedAlbumIdsFlow,
            albumSortModeFlow,
            extrasRepository.customAlbumCoversFlow
        ) { hidden, locked, sortMode, covers ->
            GridFilterBundle(hidden, locked, sortMode, covers)
        }
    ) { localAlbums, (driveRoots, driveByParent), collections, layouts, bundle ->
        val visibleLocal = applyCustomCovers(
            localAlbums.filterNot { it.id in bundle.hiddenIds },
            bundle.customCovers
        ).map { if (it.id in bundle.lockedIds) it.copy(isLocked = true) else it }
        val visibleDrive = applyCustomCovers(
            driveRoots.filterNot { it.id in bundle.hiddenIds },
            bundle.customCovers
        ).map { if (it.id in bundle.lockedIds) it.copy(isLocked = true) else it }
        val visited = HashSet<String>()

        fun buildDriveChildren(folderId: String): List<SidebarAlbumNode> {
            val subs = driveByParent[folderId].orEmpty()
            if (subs.isEmpty()) return emptyList()
            return subs.mapIndexed { idx, rawSub ->
                val sub = if (rawSub.id in bundle.lockedIds) rawSub.copy(isLocked = true) else rawSub
                val nested = if (visited.add(sub.id)) {
                    buildDriveChildren(sub.id).also { visited.remove(sub.id) }
                } else {
                    emptyList()
                }
                SidebarAlbumNode(
                    gridItem = AlbumGridItem.AlbumEntry(sub, idx),
                    children = nested
                )
            }
        }

        fun buildNodes(parentId: String?): List<SidebarAlbumNode> {
            val gridItems = buildGridForParent(
                targetParentId = parentId,
                localAlbums = visibleLocal,
                driveRoots = visibleDrive,
                collections = collections,
                layouts = layouts,
                sortMode = bundle.sortMode
            )
            // Warm collection grid cache alongside sidebar tree so opening any collection is 0ms!
            gridCacheByParent[parentId ?: ROOT_CACHE_KEY] = gridItems
            return gridItems.map { item ->
                val children = when (item) {
                    is AlbumGridItem.CollectionEntry -> if (visited.add(item.id)) {
                        buildNodes(item.id).also { visited.remove(item.id) }
                    } else emptyList()

                    is AlbumGridItem.AlbumEntry -> if (!item.folder.isLocal && visited.add(item.folder.id)) {
                        buildDriveChildren(item.folder.id).also { visited.remove(item.folder.id) }
                    } else emptyList()
                }
                SidebarAlbumNode(gridItem = item, children = children)
            }
        }

        buildNodes(parentId = null)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = repoScope,
        started = SharingStarted.Lazily,
        initialValue = emptyList()
    )

    fun observeSidebarTree(): StateFlow<List<SidebarAlbumNode>> = sharedSidebarTreeState

    fun warmCaches() {
        repoScope.launch {
            sharedSidebarTreeState.collect {}
        }
    }

    suspend fun getCollectionBreadcrumbs(collectionId: String): List<BreadcrumbItem> =
        withContext(Dispatchers.IO) {
            val allMap = collectionDao.getAllCollections().associateBy { it.id }
            val chain = mutableListOf<BreadcrumbItem>()
            var currentId: String? = collectionId
            var guard = 0
            while (currentId != null && guard++ < 32) {
                val col = allMap[currentId] ?: break
                chain.add(BreadcrumbItem(col.id, col.name))
                currentId = col.parentCollectionId
            }
            chain.reversed()
        }

    suspend fun getCollectionParentId(collectionId: String): String? =
        withContext(Dispatchers.IO) {
            collectionDao.getCollectionById(collectionId)?.parentCollectionId
        }

    suspend fun saveReorder(
        parentCollectionId: String?,
        orderedItems: List<AlbumGridItem>
    ) = withContext(Dispatchers.IO) {
        val updatedLayouts = orderedItems.mapIndexed { index, item ->
            AlbumLayoutEntity(
                itemId = item.itemId,
                itemKind = item.itemKind,
                parentCollectionId = parentCollectionId,
                sortOrder = index
            )
        }
        database.withTransaction {
            collectionDao.upsertLayouts(updatedLayouts)
        }
    }

    suspend fun createCollectionFromPair(
        parentCollectionId: String?,
        draggedItem: AlbumGridItem,
        targetItem: AlbumGridItem,
        collectionName: String
    ): Result<String> = createCollectionFromItems(
        parentCollectionId = parentCollectionId,
        items = listOf(targetItem, draggedItem),
        anchorSortOrder = targetItem.sortOrder,
        collectionName = collectionName
    )

    suspend fun createCollectionFromItems(
        parentCollectionId: String?,
        items: List<AlbumGridItem>,
        anchorSortOrder: Int? = null,
        collectionName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(items.isNotEmpty()) { "No albums selected to group." }
            val cleanName = collectionName.trim().ifBlank { "New Collection" }
            val newId = "$COLLECTION_PREFIX${UUID.randomUUID()}"

            database.withTransaction {
                collectionDao.upsertCollection(
                    AlbumCollectionEntity(
                        id = newId,
                        name = cleanName,
                        parentCollectionId = parentCollectionId,
                        createdAtEpochMs = System.currentTimeMillis()
                    )
                )

                val firstItem = items.first()
                val targetOrder = anchorSortOrder
                    ?: collectionDao.getLayout(firstItem.itemId)?.sortOrder
                    ?: firstItem.sortOrder

                // Place new collection right where the target/first album sat
                collectionDao.upsertLayout(
                    AlbumLayoutEntity(
                        itemId = newId,
                        itemKind = AlbumGridItem.KIND_COLLECTION,
                        parentCollectionId = parentCollectionId,
                        sortOrder = targetOrder
                    )
                )

                val childLayouts = items.mapIndexed { idx, entry ->
                    AlbumLayoutEntity(
                        itemId = entry.itemId,
                        itemKind = entry.itemKind,
                        parentCollectionId = newId,
                        sortOrder = idx
                    )
                }
                collectionDao.upsertLayouts(childLayouts)

                for (entry in items) {
                    if (entry is AlbumGridItem.CollectionEntry) {
                        collectionDao.updateCollectionParent(entry.id, newId)
                    }
                }
            }
            newId
        }
    }

    suspend fun createEmptyCollection(
        parentCollectionId: String?,
        name: String
    ): String = withContext(Dispatchers.IO) {
        val cleanName = name.trim().ifBlank { "New Collection" }
        val newId = "$COLLECTION_PREFIX${UUID.randomUUID()}"
        database.withTransaction {
            val existingMax = collectionDao.getAllLayouts()
                .filter { it.parentCollectionId == parentCollectionId }
                .maxOfOrNull { it.sortOrder } ?: -1

            collectionDao.upsertCollection(
                AlbumCollectionEntity(
                    id = newId,
                    name = cleanName,
                    parentCollectionId = parentCollectionId,
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
            collectionDao.upsertLayout(
                AlbumLayoutEntity(
                    itemId = newId,
                    itemKind = AlbumGridItem.KIND_COLLECTION,
                    parentCollectionId = parentCollectionId,
                    sortOrder = existingMax + 1
                )
            )
        }
        newId
    }

    suspend fun moveItemIntoCollection(
        item: AlbumGridItem,
        targetCollectionId: String?
    ): Boolean = withContext(Dispatchers.IO) {
        if (item.itemId == targetCollectionId) return@withContext false

        // Prevent dropping a collection inside one of its own descendants (cycle guard)
        if (item is AlbumGridItem.CollectionEntry && targetCollectionId != null) {
            if (isDescendant(candidateId = targetCollectionId, ancestorId = item.id)) {
                return@withContext false
            }
        }

        database.withTransaction {
            val nextOrder = (collectionDao.getAllLayouts()
                .filter { it.parentCollectionId == targetCollectionId }
                .maxOfOrNull { it.sortOrder } ?: -1) + 1

            collectionDao.upsertLayout(
                AlbumLayoutEntity(
                    itemId = item.itemId,
                    itemKind = item.itemKind,
                    parentCollectionId = targetCollectionId,
                    sortOrder = nextOrder
                )
            )
            if (item is AlbumGridItem.CollectionEntry) {
                collectionDao.updateCollectionParent(item.id, targetCollectionId)
            }
        }
        true
    }

    suspend fun renameCollection(collectionId: String, newName: String) =
        withContext(Dispatchers.IO) {
            val trimmed = newName.trim()
            if (trimmed.isNotEmpty()) {
                collectionDao.renameCollection(collectionId, trimmed)
            }
        }

    /**
     * Safely dissolves a collection wrapper by promoting all its child albums &
     * sub-collections up to the collection's parent. Never deletes photos or Drive folders.
     */
    suspend fun ungroupCollection(collectionId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val col = collectionDao.getCollectionById(collectionId) ?: return@withTransaction
            val grandParentId = col.parentCollectionId
            collectionDao.reparentChildCollections(collectionId, grandParentId)
            collectionDao.reparentChildLayouts(collectionId, grandParentId)
            collectionDao.deleteLayout(collectionId)
            collectionDao.deleteCollectionById(collectionId)
        }
    }

    private suspend fun isDescendant(candidateId: String, ancestorId: String): Boolean {
        val allMap = collectionDao.getAllCollections().associateBy { it.id }
        var cur: String? = candidateId
        var guard = 0
        while (cur != null && guard++ < 32) {
            if (cur == ancestorId) return true
            cur = allMap[cur]?.parentCollectionId
        }
        return false
    }

    private fun buildGridForParent(
        targetParentId: String?,
        localAlbums: List<DriveFolder>,
        driveRoots: List<DriveFolder>,
        collections: List<AlbumCollectionEntity>,
        layouts: List<AlbumLayoutEntity>,
        sortMode: AlbumSortMode
    ): List<AlbumGridItem> {
        val layoutMap = layouts.associateBy { it.itemId }
        val collectionMap = collections.associateBy { it.id }

        // Effective parent lookup (falls back to root if referenced parent collection was deleted)
        fun effectiveParentOf(itemId: String, fallbackParent: String? = null): String? {
            val stored = layoutMap[itemId]?.parentCollectionId ?: fallbackParent
            return if (stored != null && stored in collectionMap) stored else null
        }

        val allAlbumsById = LinkedHashMap<String, DriveFolder>(localAlbums.size + driveRoots.size)
        localAlbums.forEach { allAlbumsById[it.id] = it }
        driveRoots.forEach { allAlbumsById[it.id] = it }

        // Build adjacency of collection -> direct child item IDs
        val childCollectionsByParent = HashMap<String?, MutableList<AlbumCollectionEntity>>()
        for (col in collections) {
            val parent = effectiveParentOf(col.id, col.parentCollectionId)
            childCollectionsByParent.getOrPut(parent) { mutableListOf() }.add(col)
        }

        val childAlbumsByParent = HashMap<String?, MutableList<DriveFolder>>()
        for (album in allAlbumsById.values) {
            val parent = effectiveParentOf(album.id)
            childAlbumsByParent.getOrPut(parent) { mutableListOf() }.add(album)
        }

        // Recursive stats & up to 4 collage cover previews for any collection
        data class CollectionSummary(
            val directChildCount: Int,
            val totalMediaCount: Int,
            val covers: List<AlbumCoverPreview>,
            val hasDriveChild: Boolean
        )

        val summaryMemo = HashMap<String, CollectionSummary>()
        val visiting = HashSet<String>()

        fun summarizeCollection(colId: String): CollectionSummary {
            summaryMemo[colId]?.let { return it }
            if (!visiting.add(colId)) return CollectionSummary(0, 0, emptyList(), false)

            val directAlbums = childAlbumsByParent[colId].orEmpty()
                .sortedBy { layoutMap[it.id]?.sortOrder ?: Int.MAX_VALUE }
            val directSubCols = childCollectionsByParent[colId].orEmpty()
                .sortedBy { layoutMap[it.id]?.sortOrder ?: Int.MAX_VALUE }

            var totalMedia = 0
            var hasDrive = false
            val covers = mutableListOf<AlbumCoverPreview>()

            for (album in directAlbums) {
                totalMedia += album.mediaCount
                if (!album.isLocal) hasDrive = true
                if (!album.isLocked && covers.size < 4) {
                    val primary = album.previewCovers.firstOrNull() ?: album.coverItemId?.let { id ->
                        AlbumCoverPreview(
                            itemId = id,
                            thumbnailLink = album.coverThumbnailLink,
                            isLocal = album.isLocal,
                            isVideo = album.coverIsVideo,
                            sizeBytes = album.coverSizeBytes
                        )
                    }
                    if (primary != null) covers.add(primary)
                }
            }

            for (subCol in directSubCols) {
                val subSummary = summarizeCollection(subCol.id)
                totalMedia += subSummary.totalMediaCount
                if (subSummary.hasDriveChild) hasDrive = true
                for (c in subSummary.covers) {
                    if (covers.size < 4) covers.add(c)
                }
            }

            visiting.remove(colId)
            return CollectionSummary(
                directChildCount = directAlbums.size + directSubCols.size,
                totalMediaCount = totalMedia,
                covers = covers,
                hasDriveChild = hasDrive
            ).also { summaryMemo[colId] = it }
        }

        // Assemble the items for `targetParentId`
        val items = mutableListOf<Pair<AlbumGridItem, Int>>()

        // Default order indices for unsorted items: Local albums first, then Drive albums, then Collections
        val defaultRankMap = HashMap<String, Int>()
        var rank = 0
        localAlbums.forEach { defaultRankMap[it.id] = rank++ }
        driveRoots.forEach { defaultRankMap[it.id] = rank++ }
        collections.forEach { defaultRankMap[it.id] = rank++ }

        childAlbumsByParent[targetParentId].orEmpty().forEach { album ->
            val explicitOrder = layoutMap[album.id]?.sortOrder
            val sortKey = explicitOrder ?: (100_000 + (defaultRankMap[album.id] ?: 0))
            items.add(AlbumGridItem.AlbumEntry(album, sortKey) to sortKey)
        }

        childCollectionsByParent[targetParentId].orEmpty().forEach { col ->
            val summary = summarizeCollection(col.id)
            val explicitOrder = layoutMap[col.id]?.sortOrder
            val sortKey = explicitOrder ?: (100_000 + (defaultRankMap[col.id] ?: 0))
            items.add(
                AlbumGridItem.CollectionEntry(
                    id = col.id,
                    name = col.name,
                    parentCollectionId = targetParentId,
                    childCount = summary.directChildCount,
                    totalMediaCount = summary.totalMediaCount,
                    previewCovers = summary.covers,
                    hasDriveChild = summary.hasDriveChild,
                    sortOrder = sortKey
                ) to sortKey
            )
        }

        val baseOrdered = items.sortedBy { it.second }.map { it.first }
        return when (sortMode) {
            AlbumSortMode.CUSTOM -> baseOrdered
            AlbumSortMode.NAME_ASC -> baseOrdered.sortedBy { it.displayName.lowercase() }
            AlbumSortMode.COUNT_DESC -> baseOrdered.sortedByDescending { entry ->
                when (entry) {
                    is AlbumGridItem.AlbumEntry -> entry.folder.mediaCount
                    is AlbumGridItem.CollectionEntry -> entry.totalMediaCount
                }
            }
            AlbumSortMode.DATE_DESC -> baseOrdered.sortedByDescending { entry ->
                when (entry) {
                    is AlbumGridItem.AlbumEntry -> entry.folder.modifiedTimeEpochMs
                    is AlbumGridItem.CollectionEntry -> Long.MAX_VALUE
                }
            }
        }
    }

    companion object {
        const val COLLECTION_PREFIX = "col_"
        private const val ROOT_CACHE_KEY = "__root__"
    }

    private data class GridFilterBundle(
        val hiddenIds: Set<String>,
        val lockedIds: Set<String>,
        val sortMode: AlbumSortMode,
        val customCovers: Map<String, com.luminagallery.drive.data.local.entity.CustomAlbumCoverEntity>
    )
}

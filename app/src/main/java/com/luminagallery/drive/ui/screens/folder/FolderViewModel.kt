package com.luminagallery.drive.ui.screens.folder

import android.content.IntentSender
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.luminagallery.drive.AppContainer
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.repository.AlbumOrganizationRepository
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.domain.model.AlbumMediaFilter
import com.luminagallery.drive.domain.model.AlbumMediaGroupBy
import com.luminagallery.drive.domain.model.AlbumMediaSort
import com.luminagallery.drive.domain.model.BreadcrumbItem
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.FolderViewMode
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.SidebarAlbumNode
import com.luminagallery.drive.domain.usecase.ExportMediaUseCase
import com.luminagallery.drive.domain.usecase.SyncGalleryUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
sealed interface FolderGridEntry {
    val stableKey: String
    val scrollDateLabel: String?

    @Immutable
    data class SubfoldersHeading(val count: Int) : FolderGridEntry {
        override val stableKey: String = "header_subfolders"
        override val scrollDateLabel: String = "Folders"
    }

    @Immutable
    data class SubfolderTripleRow(
        val first: DriveFolder,
        val second: DriveFolder?,
        val third: DriveFolder?
    ) : FolderGridEntry {
        override val stableKey: String = "folder_row_${first.id}_${second?.id ?: "x"}_${third?.id ?: "y"}"
        override val scrollDateLabel: String = "Folders"
    }

    @Immutable
    data class SectionHeader(
        val title: String,
        val subtitle: String? = null,
        val keySuffix: String
    ) : FolderGridEntry {
        override val stableKey: String = "section_$keySuffix"
        override val scrollDateLabel: String = title
    }

    @Immutable
    data class MediaCell(val item: MediaItem) : FolderGridEntry {
        override val stableKey: String = "cell_${item.id}"
        override val scrollDateLabel: String = item.monthYearLabel
    }
}

@Immutable
data class FolderUiState(
    val folderId: String = "",
    val folderName: String = "",
    val isLocalAlbum: Boolean = false,
    val viewMode: FolderViewMode = FolderViewMode.HIERARCHY,
    val mediaFilter: AlbumMediaFilter = AlbumMediaFilter.ALL,
    val mediaGroupBy: AlbumMediaGroupBy = AlbumMediaGroupBy.NONE,
    val mediaSort: AlbumMediaSort = AlbumMediaSort.DATE_DESC,
    val showSidebar: Boolean = true,
    val sidebarNodes: List<SidebarAlbumNode> = emptyList(),
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val subfolders: List<DriveFolder> = emptyList(),
    val mediaItems: List<MediaItem> = emptyList(),
    val unfilteredItemCount: Int = 0,
    val gridEntries: List<FolderGridEntry> = emptyList(),
    val gridColumns: Int = 4,
    val showCloudBadges: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasCompletedInitialLoad: Boolean = false,
    val isOnline: Boolean = true,
    val isUnmeteredWifi: Boolean = true,
    val isLockedAlbum: Boolean = false,
    val lockedAlbumIds: Set<String> = emptySet(),
    val isProtectedPrivateAlbum: Boolean = false,
    val favoriteItemIds: Set<String> = emptySet(),
    val selectedIds: Set<String> = emptySet(),
    val forceSelectionMode: Boolean = false,
    val isExporting: Boolean = false,
    val exportProgressText: String? = null,
    val infoItem: MediaItem? = null,
    val infoFolderPath: String = "Google Drive",
    val pendingShareUris: List<Uri>? = null,
    val pendingTrashIntentSender: IntentSender? = null,
    val snackbarMessage: String? = null
) {
    val isSelectionMode: Boolean get() = forceSelectionMode || selectedIds.isNotEmpty()
    val isEmpty: Boolean
        get() = hasCompletedInitialLoad && subfolders.isEmpty() && mediaItems.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
class FolderViewModel(
    initialFolderId: String,
    initialFolderName: String,
    private val repository: GalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val organizationRepository: AlbumOrganizationRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val preferencesManager: PreferencesManager,
    private val syncUseCase: SyncGalleryUseCase,
    private val exportMediaUseCase: ExportMediaUseCase,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    private val initialPrefs = preferencesManager.latestPreferences
    private val activeFolderFlow = MutableStateFlow(initialFolderId to initialFolderName)
    private val viewModeFlow = MutableStateFlow(FolderViewMode.HIERARCHY)
    private val mediaFilterFlow = MutableStateFlow(AlbumMediaFilter.ALL)
    private val mediaGroupByFlow = MutableStateFlow(AlbumMediaGroupBy.NONE)
    private val mediaSortFlow = MutableStateFlow(AlbumMediaSort.DATE_DESC)
    private val showSidebarFlow = MutableStateFlow(initialPrefs.showAlbumSidebarDefault)
    private val breadcrumbsFlow = MutableStateFlow(listOf(BreadcrumbItem(initialFolderId, initialFolderName)))
    private val isRefreshingFlow = MutableStateFlow(false)
    private val hasLoadedOnceFlow = MutableStateFlow(LocalMediaRepository.isLocalFolderId(initialFolderId))
    private val selectedIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val forceSelectionFlow = MutableStateFlow(false)
    private val isExportingFlow = MutableStateFlow(false)
    private val exportProgressFlow = MutableStateFlow<String?>(null)
    private val infoSheetFlow = MutableStateFlow<Pair<MediaItem, String>?>(null)
    private val pendingShareFlow = MutableStateFlow<List<Uri>?>(null)
    private val pendingTrashFlow = MutableStateFlow<IntentSender?>(null)
    private val snackbarFlow = MutableStateFlow<String?>(null)

    private val displayOptionsFlow = combine(
        mediaFilterFlow,
        mediaGroupByFlow,
        mediaSortFlow,
        showSidebarFlow,
        combine(
            extrasRepository.favoriteItemIdsFlow,
            extrasRepository.lockedAlbumIdsFlow
        ) { favIds, lockedIds -> favIds to lockedIds }
    ) { filter, groupBy, sort, sidebar, (favIds, lockedIds) ->
        DisplayOptions(filter, groupBy, sort, sidebar, favIds, lockedIds)
    }

    private val rawContentFlow = activeFolderFlow.flatMapLatest { (activeId, activeName) ->
        val local = LocalMediaRepository.isLocalFolderId(activeId)
        if (local) {
            combine(
                localMediaRepository.observeLocalAlbumMedia(activeId),
                displayOptionsFlow
            ) { items, opts ->
                val processed = applyFilterAndSort(items, opts.filter, opts.sort, opts.favoriteIds)
                val entries = buildGroupedEntries(
                    mode = FolderViewMode.HIERARCHY,
                    subfolders = emptyList(),
                    mediaItems = processed,
                    groupBy = opts.groupBy,
                    sort = opts.sort
                )
                FolderContent(
                    folderId = activeId,
                    folderName = activeName,
                    isLocal = true,
                    mode = FolderViewMode.HIERARCHY,
                    subfolders = emptyList(),
                    mediaItems = processed,
                    unfilteredCount = items.size,
                    gridEntries = entries,
                    filter = opts.filter,
                    groupBy = opts.groupBy,
                    sort = opts.sort,
                    showSidebar = opts.showSidebar,
                    favoriteIds = opts.favoriteIds
                )
            }.flowOn(Dispatchers.Default)
        } else {
            // Only execute the heavy 32-level recursive CTE when user explicitly switches to FLATTENED mode!
            viewModeFlow.flatMapLatest { mode ->
                if (mode == FolderViewMode.HIERARCHY) {
                    combine(
                        repository.observeSubfolders(activeId),
                        repository.observeMediaInFolder(activeId),
                        displayOptionsFlow
                    ) { subfolders, directMedia, opts ->
                        val markedSubfolders = if (opts.lockedIds.isEmpty()) {
                            subfolders
                        } else {
                            subfolders.map { if (it.id in opts.lockedIds) it.copy(isLocked = true) else it }
                        }
                        val processed = applyFilterAndSort(directMedia, opts.filter, opts.sort, opts.favoriteIds)
                        val entries = buildGroupedEntries(
                            mode = FolderViewMode.HIERARCHY,
                            subfolders = markedSubfolders,
                            mediaItems = processed,
                            groupBy = opts.groupBy,
                            sort = opts.sort
                        )
                        FolderContent(
                            folderId = activeId,
                            folderName = activeName,
                            isLocal = false,
                            mode = FolderViewMode.HIERARCHY,
                            subfolders = markedSubfolders,
                            mediaItems = processed,
                            unfilteredCount = directMedia.size,
                            gridEntries = entries,
                            filter = opts.filter,
                            groupBy = opts.groupBy,
                            sort = opts.sort,
                            showSidebar = opts.showSidebar,
                            favoriteIds = opts.favoriteIds
                        )
                    }.flowOn(Dispatchers.Default)
                } else {
                    combine(
                        repository.observeFlattenedMedia(activeId),
                        displayOptionsFlow
                    ) { flattenedMedia, opts ->
                        val processed = applyFilterAndSort(flattenedMedia, opts.filter, opts.sort, opts.favoriteIds)
                        val entries = buildGroupedEntries(
                            mode = FolderViewMode.FLATTENED,
                            subfolders = emptyList(),
                            mediaItems = processed,
                            groupBy = opts.groupBy,
                            sort = opts.sort
                        )
                        FolderContent(
                            folderId = activeId,
                            folderName = activeName,
                            isLocal = false,
                            mode = FolderViewMode.FLATTENED,
                            subfolders = emptyList(),
                            mediaItems = processed,
                            unfilteredCount = flattenedMedia.size,
                            gridEntries = entries,
                            filter = opts.filter,
                            groupBy = opts.groupBy,
                            sort = opts.sort,
                            showSidebar = opts.showSidebar,
                            favoriteIds = opts.favoriteIds
                        )
                    }.flowOn(Dispatchers.Default)
                }
            }
        }
    }

    private val syncAndDisplayFlow = combine(
        breadcrumbsFlow,
        preferencesManager.preferencesFlow,
        isRefreshingFlow,
        hasLoadedOnceFlow,
        combine(
            networkMonitor.networkState,
            extrasRepository.hiddenAlbumIdsFlow,
            extrasRepository.lockedAlbumIdsFlow
        ) { net, hidden, locked -> Triple(net, hidden, locked) }
    ) { crumbs, prefs, refreshing, loaded, (net, hiddenIds, lockedIds) ->
        FolderMeta(
            breadcrumbs = crumbs,
            gridColumns = prefs.gridColumns,
            showCloudBadges = prefs.showCloudBadges,
            isRefreshing = refreshing,
            hasLoadedOnce = loaded,
            isOnline = net.isOnline,
            isUnmeteredWifi = net.isUnmeteredWifi || !prefs.wifiOnlyHighRes,
            lockHiddenAlbums = prefs.lockHiddenAlbums,
            hiddenAlbumIds = hiddenIds,
            lockedAlbumIds = lockedIds
        )
    }

    private val exportAndDialogFlow = combine(
        pendingShareFlow,
        pendingTrashFlow,
        snackbarFlow
    ) { share, trash, snack ->
        Triple(share, trash, snack)
    }

    private val interactionFlow = combine(
        combine(selectedIdsFlow, forceSelectionFlow) { ids, forced -> ids to forced },
        isExportingFlow,
        exportProgressFlow,
        infoSheetFlow,
        exportAndDialogFlow
    ) { (selected, forced), exporting, progress, info, (share, trash, snack) ->
        FolderInteraction(selected, forced, exporting, progress, info, share, trash, snack)
    }

    val uiState: StateFlow<FolderUiState> = combine(
        rawContentFlow,
        organizationRepository.observeSidebarTree(),
        syncAndDisplayFlow,
        interactionFlow
    ) { content, sidebarNodes, meta, interaction ->
        FolderUiState(
            folderId = content.folderId,
            folderName = content.folderName,
            isLocalAlbum = content.isLocal,
            viewMode = content.mode,
            mediaFilter = content.filter,
            mediaGroupBy = content.groupBy,
            mediaSort = content.sort,
            showSidebar = content.showSidebar,
            sidebarNodes = sidebarNodes,
            breadcrumbs = meta.breadcrumbs,
            subfolders = content.subfolders,
            mediaItems = content.mediaItems,
            unfilteredItemCount = content.unfilteredCount,
            gridEntries = content.gridEntries,
            gridColumns = meta.gridColumns,
            showCloudBadges = meta.showCloudBadges,
            isRefreshing = meta.isRefreshing,
            hasCompletedInitialLoad = meta.hasLoadedOnce ||
                content.subfolders.isNotEmpty() ||
                content.mediaItems.isNotEmpty() ||
                content.unfilteredCount > 0,
            isOnline = meta.isOnline,
            isUnmeteredWifi = meta.isUnmeteredWifi,
            isLockedAlbum = content.folderId in meta.lockedAlbumIds,
            lockedAlbumIds = meta.lockedAlbumIds,
            isProtectedPrivateAlbum = (content.folderId in meta.lockedAlbumIds) ||
                (meta.lockHiddenAlbums && content.folderId in meta.hiddenAlbumIds),
            favoriteItemIds = content.favoriteIds,
            selectedIds = interaction.selectedIds,
            forceSelectionMode = interaction.forceSelectionMode,
            isExporting = interaction.isExporting,
            exportProgressText = interaction.exportProgressText,
            infoItem = interaction.info?.first,
            infoFolderPath = interaction.info?.second ?: content.folderName,
            pendingShareUris = interaction.pendingShareUris,
            pendingTrashIntentSender = interaction.pendingTrashSender,
            snackbarMessage = interaction.snackbarMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = buildInstantInitialState(
            initialFolderId = initialFolderId,
            initialFolderName = initialFolderName,
            initialNet = networkMonitor.networkState.value
        )
    )

    private fun buildInstantInitialState(
        initialFolderId: String,
        initialFolderName: String,
        initialNet: com.luminagallery.drive.domain.model.NetworkState
    ): FolderUiState {
        val isLocal = LocalMediaRepository.isLocalFolderId(initialFolderId)
        val cachedSubfolders = if (isLocal) emptyList() else repository.getCachedSubfolders(initialFolderId).orEmpty()
        val cachedMedia = if (isLocal) {
            localMediaRepository.getCachedAlbumMedia(initialFolderId)
        } else {
            repository.getCachedFolderMedia(initialFolderId).orEmpty()
        }
        val initialEntries = if (cachedSubfolders.isNotEmpty() || cachedMedia.isNotEmpty()) {
            buildGroupedEntries(
                mode = FolderViewMode.HIERARCHY,
                subfolders = cachedSubfolders,
                mediaItems = cachedMedia,
                groupBy = AlbumMediaGroupBy.NONE,
                sort = AlbumMediaSort.DATE_DESC
            )
        } else {
            emptyList()
        }
        return FolderUiState(
            folderId = initialFolderId,
            folderName = initialFolderName,
            isLocalAlbum = isLocal,
            showSidebar = initialPrefs.showAlbumSidebarDefault,
            sidebarNodes = organizationRepository.currentSidebarTree(),
            breadcrumbs = listOf(BreadcrumbItem(initialFolderId, initialFolderName)),
            subfolders = cachedSubfolders,
            mediaItems = cachedMedia,
            unfilteredItemCount = cachedMedia.size,
            gridEntries = initialEntries,
            gridColumns = initialPrefs.gridColumns,
            showCloudBadges = initialPrefs.showCloudBadges,
            hasCompletedInitialLoad = isLocal || cachedSubfolders.isNotEmpty() || cachedMedia.isNotEmpty(),
            isOnline = initialNet.isOnline,
            isUnmeteredWifi = initialNet.isUnmeteredWifi || !initialPrefs.wifiOnlyHighRes,
            favoriteItemIds = extrasRepository.latestFavoriteItemIds
        )
    }

    init {
        loadSavedAlbumViewPref(initialFolderId)
        if (!LocalMediaRepository.isLocalFolderId(initialFolderId)) {
            loadBreadcrumbs(initialFolderId)
            refreshFolder(silent = true)
        }
    }

    private fun loadSavedAlbumViewPref(albumId: String) {
        viewModelScope.launch {
            val saved = extrasRepository.getAlbumViewPref(albumId)
            if (activeFolderFlow.value.first == albumId) {
                mediaSortFlow.value = saved?.sortName?.let {
                    runCatching { AlbumMediaSort.valueOf(it) }.getOrNull()
                } ?: AlbumMediaSort.DATE_DESC
                mediaGroupByFlow.value = saved?.groupByName?.let {
                    runCatching { AlbumMediaGroupBy.valueOf(it) }.getOrNull()
                } ?: AlbumMediaGroupBy.NONE
            }
        }
    }

    /**
     * Switches the active album seamlessly in-place when tapping any album in
     * Samsung's left album sidebar rail (no back-stack spam!).
     */
    fun switchToAlbum(folderId: String, folderName: String) {
        if (activeFolderFlow.value.first == folderId) return
        val local = LocalMediaRepository.isLocalFolderId(folderId)
        activeFolderFlow.value = folderId to folderName
        selectedIdsFlow.value = emptySet()
        forceSelectionFlow.value = false
        viewModeFlow.value = FolderViewMode.HIERARCHY
        breadcrumbsFlow.value = listOf(BreadcrumbItem(folderId, folderName))
        hasLoadedOnceFlow.value = local
        loadSavedAlbumViewPref(folderId)
        if (!local) {
            loadBreadcrumbs(folderId)
            refreshFolder(silent = true)
        }
    }

    fun setMediaFilter(filter: AlbumMediaFilter) {
        mediaFilterFlow.value = filter
    }

    fun setMediaGroupBy(groupBy: AlbumMediaGroupBy) {
        mediaGroupByFlow.value = groupBy
        val activeId = activeFolderFlow.value.first
        viewModelScope.launch {
            extrasRepository.saveAlbumViewPref(activeId, mediaSortFlow.value, groupBy)
        }
    }

    fun setMediaSort(sort: AlbumMediaSort) {
        mediaSortFlow.value = sort
        val activeId = activeFolderFlow.value.first
        viewModelScope.launch {
            extrasRepository.saveAlbumViewPref(activeId, sort, mediaGroupByFlow.value)
        }
    }

    fun onLocalAlbumRenamed(newName: String) {
        val currentId = activeFolderFlow.value.first
        activeFolderFlow.value = currentId to newName
        breadcrumbsFlow.value = listOf(BreadcrumbItem(currentId, newName))
        snackbarFlow.value = "Renamed album to \"$newName\""
    }

    fun toggleSidebar() {
        showSidebarFlow.update { !it }
    }

    fun enterSelectionMode() {
        forceSelectionFlow.value = true
    }

    private fun loadBreadcrumbs(targetFolderId: String) {
        viewModelScope.launch {
            val chain = repository.getBreadcrumbChain(targetFolderId)
            if (chain.isNotEmpty() && activeFolderFlow.value.first == targetFolderId) {
                breadcrumbsFlow.value = chain
            }
        }
    }

    fun refreshFolder(silent: Boolean = false) {
        val (activeId, _) = activeFolderFlow.value
        if (LocalMediaRepository.isLocalFolderId(activeId)) {
            localMediaRepository.notifyPermissionChanged()
            return
        }
        viewModelScope.launch {
            if (!silent) isRefreshingFlow.value = true
            val result = if (viewModeFlow.value == FolderViewMode.FLATTENED) {
                syncUseCase.syncFolderTree(activeId)
            } else {
                syncUseCase.syncFolder(activeId).map { Unit }
            }
            result.onFailure { err ->
                if (!silent) {
                    snackbarFlow.value = err.message ?: "Unable to sync folder"
                }
            }
            loadBreadcrumbs(activeId)
            hasLoadedOnceFlow.value = true
            isRefreshingFlow.value = false
        }
    }

    fun setViewMode(mode: FolderViewMode) {
        val (activeId, _) = activeFolderFlow.value
        if (LocalMediaRepository.isLocalFolderId(activeId) || viewModeFlow.value == mode) return
        viewModeFlow.value = mode
        selectedIdsFlow.value = emptySet()
        forceSelectionFlow.value = false
        if (mode == FolderViewMode.FLATTENED) {
            viewModelScope.launch { syncUseCase.syncFolderTree(activeId) }
        }
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch { preferencesManager.setGridColumns(columns) }
    }

    fun toggleSelection(itemId: String) {
        selectedIdsFlow.update { current ->
            if (itemId in current) current - itemId else current + itemId
        }
    }

    fun setItemSelected(itemId: String, selected: Boolean) {
        selectedIdsFlow.update { current ->
            if (selected) current + itemId else current - itemId
        }
    }

    fun selectAllVisible() {
        val allIds = uiState.value.mediaItems.mapTo(LinkedHashSet()) { it.id }
        selectedIdsFlow.value = allIds
    }

    fun clearSelection() {
        selectedIdsFlow.value = emptySet()
        forceSelectionFlow.value = false
    }

    fun shareSelected() {
        val isLocal = uiState.value.isLocalAlbum
        val selected = uiState.value.mediaItems.filter { it.id in selectedIdsFlow.value }
        if (selected.isEmpty() || isExportingFlow.value) return
        viewModelScope.launch {
            isExportingFlow.value = true
            exportProgressFlow.value = if (isLocal) null else "Preparing ${selected.size} file(s)…"
            exportMediaUseCase.prepareShareUris(selected) { done, total ->
                if (!isLocal) exportProgressFlow.value = "Downloading $done / $total"
            }.onSuccess { uris ->
                pendingShareFlow.value = uris
                selectedIdsFlow.value = emptySet()
                forceSelectionFlow.value = false
            }.onFailure { err ->
                snackbarFlow.value = err.message ?: "Failed to prepare files for sharing"
            }
            isExportingFlow.value = false
            exportProgressFlow.value = null
        }
    }

    fun saveSelectedToDevice() {
        if (uiState.value.isLocalAlbum) return
        val selected = uiState.value.mediaItems.filter { it.id in selectedIdsFlow.value }
        if (selected.isEmpty() || isExportingFlow.value) return
        viewModelScope.launch {
            isExportingFlow.value = true
            exportProgressFlow.value = "Saving 0 / ${selected.size}"
            exportMediaUseCase.saveToDevice(selected) { done, total ->
                exportProgressFlow.value = "Saving $done / $total"
            }.onSuccess { count ->
                snackbarFlow.value = "Saved $count item(s) to Pictures / Movies"
                selectedIdsFlow.value = emptySet()
                forceSelectionFlow.value = false
            }.onFailure { err ->
                snackbarFlow.value = err.message ?: "Failed to save to device"
            }
            isExportingFlow.value = false
            exportProgressFlow.value = null
        }
    }

    fun requestTrashSelectedLocal() {
        if (!uiState.value.isLocalAlbum) return
        val selected = uiState.value.mediaItems.filter { it.id in selectedIdsFlow.value }
        if (selected.isEmpty()) return
        if (localMediaRepository.canManageMediaDirectly()) {
            viewModelScope.launch {
                localMediaRepository.trashLocalItemsDirectly(selected, toTrash = true)
                    .onSuccess { count ->
                        selectedIdsFlow.value = emptySet()
                        forceSelectionFlow.value = false
                        localMediaRepository.notifyPermissionChanged()
                        snackbarFlow.value = if (count == 1) "Moved to trash" else "Moved $count items to trash"
                    }
                    .onFailure {
                        val sender = localMediaRepository.buildTrashIntentSender(selected)
                        if (sender != null) {
                            pendingTrashFlow.value = sender
                        } else {
                            snackbarFlow.value = "Trash requires Android 11 or newer"
                        }
                    }
            }
            return
        }
        val sender = localMediaRepository.buildTrashIntentSender(selected)
        if (sender != null) {
            pendingTrashFlow.value = sender
        } else {
            snackbarFlow.value = "Trash requires Android 11 or newer"
        }
    }

    fun onTrashCompleted(approved: Boolean) {
        pendingTrashFlow.value = null
        if (approved) {
            selectedIdsFlow.value = emptySet()
            forceSelectionFlow.value = false
            localMediaRepository.notifyPermissionChanged()
            snackbarFlow.value = "Moved to trash"
        }
    }

    fun openSelectedInfo() {
        val singleId = selectedIdsFlow.value.singleOrNull() ?: return
        val item = uiState.value.mediaItems.firstOrNull { it.id == singleId } ?: return
        val activeId = activeFolderFlow.value.first
        viewModelScope.launch {
            if (item.isLocal) {
                val (exif, path) = localMediaRepository.readLocalExifAndPath(item)
                infoSheetFlow.value = item.copy(exif = exif) to path
            } else {
                val path = repository.getFolderPathString(item.parentId ?: activeId)
                infoSheetFlow.value = item to path
            }
        }
    }

    fun closeInfoSheet() {
        infoSheetFlow.value = null
    }

    fun consumePendingShare() {
        pendingShareFlow.value = null
    }

    fun clearSnackbar() {
        snackbarFlow.value = null
    }

    fun showMessage(message: String) {
        snackbarFlow.value = message
    }

    private fun applyFilterAndSort(
        items: List<MediaItem>,
        filter: AlbumMediaFilter,
        sort: AlbumMediaSort,
        favoriteIds: Set<String>
    ): List<MediaItem> {
        val filtered = when (filter) {
            AlbumMediaFilter.ALL -> items
            AlbumMediaFilter.IMAGES -> items.filter { !it.isVideo }
            AlbumMediaFilter.VIDEOS -> items.filter { it.isVideo }
            AlbumMediaFilter.RAW -> items.filter { it.isRaw }
            AlbumMediaFilter.FAVORITES -> items.filter { it.id in favoriteIds }
        }
        return when (sort) {
            AlbumMediaSort.DATE_DESC -> filtered.sortedByDescending { it.createdTimeEpochMs }
            AlbumMediaSort.DATE_ASC -> filtered.sortedBy { it.createdTimeEpochMs }
            AlbumMediaSort.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            AlbumMediaSort.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
        }
    }

    private fun buildGroupedEntries(
        mode: FolderViewMode,
        subfolders: List<DriveFolder>,
        mediaItems: List<MediaItem>,
        groupBy: AlbumMediaGroupBy,
        sort: AlbumMediaSort
    ): List<FolderGridEntry> {
        val entries = ArrayList<FolderGridEntry>(subfolders.size + mediaItems.size + 16)
        if (mode == FolderViewMode.HIERARCHY && subfolders.isNotEmpty()) {
            // Only show the "Folders" subheading when media sits directly underneath;
            // folder-only views match Samsung's clean full-width 3-column album grid.
            if (mediaItems.isNotEmpty()) {
                entries.add(FolderGridEntry.SubfoldersHeading(subfolders.size))
            }
            subfolders.chunked(3).forEach { triple ->
                entries.add(
                    FolderGridEntry.SubfolderTripleRow(
                        first = triple[0],
                        second = triple.getOrNull(1),
                        third = triple.getOrNull(2)
                    )
                )
            }
        }

        if (mediaItems.isEmpty()) return entries

        // When sorting by Name or Size, flat listing avoids broken duplicate date headers
        val effectiveGroup = if (sort == AlbumMediaSort.NAME_ASC || sort == AlbumMediaSort.SIZE_DESC) {
            AlbumMediaGroupBy.NONE
        } else {
            groupBy
        }

        when (effectiveGroup) {
            AlbumMediaGroupBy.NONE -> {
                if (subfolders.isNotEmpty()) {
                    entries.add(
                        FolderGridEntry.SectionHeader(
                            title = "Media",
                            subtitle = "${mediaItems.size}",
                            keySuffix = "flat_media"
                        )
                    )
                }
                mediaItems.forEach { entries.add(FolderGridEntry.MediaCell(it)) }
            }

            AlbumMediaGroupBy.DAY -> {
                var currentDay: String? = null
                var idx = 0
                for (item in mediaItems) {
                    if (item.daySectionLabel != currentDay) {
                        currentDay = item.daySectionLabel
                        entries.add(
                            FolderGridEntry.SectionHeader(
                                title = item.daySectionLabel,
                                keySuffix = "day_${idx++}_${item.daySectionLabel}"
                            )
                        )
                    }
                    entries.add(FolderGridEntry.MediaCell(item))
                }
            }

            AlbumMediaGroupBy.MONTH -> {
                var currentMonth: String? = null
                var idx = 0
                for (item in mediaItems) {
                    if (item.monthYearLabel != currentMonth) {
                        currentMonth = item.monthYearLabel
                        entries.add(
                            FolderGridEntry.SectionHeader(
                                title = item.monthYearLabel,
                                keySuffix = "month_${idx++}_${item.monthYearLabel}"
                            )
                        )
                    }
                    entries.add(FolderGridEntry.MediaCell(item))
                }
            }
        }

        return entries
    }

    fun setCurrentAlbumLocked(locked: Boolean) {
        val (folderId, folderName) = activeFolderFlow.value
        if (folderId.isBlank()) return
        viewModelScope.launch {
            extrasRepository.setAlbumLocked(folderId, locked)
            snackbarFlow.value = if (locked) "Locked $folderName" else "Unlocked $folderName"
        }
    }

    private data class DisplayOptions(
        val filter: AlbumMediaFilter,
        val groupBy: AlbumMediaGroupBy,
        val sort: AlbumMediaSort,
        val showSidebar: Boolean,
        val favoriteIds: Set<String>,
        val lockedIds: Set<String> = emptySet()
    )

    private data class FolderContent(
        val folderId: String,
        val folderName: String,
        val isLocal: Boolean,
        val mode: FolderViewMode,
        val subfolders: List<DriveFolder>,
        val mediaItems: List<MediaItem>,
        val unfilteredCount: Int,
        val gridEntries: List<FolderGridEntry>,
        val filter: AlbumMediaFilter,
        val groupBy: AlbumMediaGroupBy,
        val sort: AlbumMediaSort,
        val showSidebar: Boolean,
        val favoriteIds: Set<String>
    )

    private data class FolderMeta(
        val breadcrumbs: List<BreadcrumbItem>,
        val gridColumns: Int,
        val showCloudBadges: Boolean,
        val isRefreshing: Boolean,
        val hasLoadedOnce: Boolean,
        val isOnline: Boolean,
        val isUnmeteredWifi: Boolean,
        val lockHiddenAlbums: Boolean = false,
        val hiddenAlbumIds: Set<String> = emptySet(),
        val lockedAlbumIds: Set<String> = emptySet()
    )

    private data class FolderInteraction(
        val selectedIds: Set<String>,
        val forceSelectionMode: Boolean,
        val isExporting: Boolean,
        val exportProgressText: String?,
        val info: Pair<MediaItem, String>?,
        val pendingShareUris: List<Uri>?,
        val pendingTrashSender: IntentSender?,
        val snackbarMessage: String?
    )

    class Factory(
        private val folderId: String,
        private val folderName: String,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FolderViewModel(
            initialFolderId = folderId,
            initialFolderName = folderName,
            repository = container.galleryRepository,
            localMediaRepository = container.localMediaRepository,
            organizationRepository = container.albumOrganizationRepository,
            extrasRepository = container.galleryExtrasRepository,
            preferencesManager = container.preferencesManager,
            syncUseCase = container.syncGalleryUseCase,
            exportMediaUseCase = container.exportMediaUseCase,
            networkMonitor = container.networkMonitor
        ) as T
    }
}

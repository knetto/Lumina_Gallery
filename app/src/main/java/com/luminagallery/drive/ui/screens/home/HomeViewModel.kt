package com.luminagallery.drive.ui.screens.home

import android.content.IntentSender
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.luminagallery.drive.AppContainer
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.local.entity.AlbumCollectionEntity
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.remote.auth.CredentialStore
import com.luminagallery.drive.data.remote.auth.ServiceAccountTokenManager
import com.luminagallery.drive.data.repository.AlbumOrganizationRepository
import com.luminagallery.drive.data.repository.CloudProfileSyncManager
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.domain.model.AlbumGridItem
import com.luminagallery.drive.domain.model.AlbumSortMode
import com.luminagallery.drive.domain.model.AlbumsTabFilter
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.GalleryStory
import com.luminagallery.drive.domain.model.LocationPhotoGroup
import com.luminagallery.drive.domain.model.MainTab
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.NetworkState
import com.luminagallery.drive.domain.model.ServiceAccountState
import com.luminagallery.drive.domain.model.SyncStatus
import com.luminagallery.drive.domain.usecase.ExportMediaUseCase
import com.luminagallery.drive.domain.usecase.ManageRootsUseCase
import com.luminagallery.drive.domain.usecase.SyncGalleryUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
sealed interface PicturesTimelineEntry {
    val stableKey: String
    val dateLabel: String

    @Immutable
    data class DayHeader(
        val label: String,
        val locationLabel: String? = null,
        val keySuffix: String
    ) : PicturesTimelineEntry {
        override val stableKey: String = "day_$keySuffix"
        override val dateLabel: String = label
    }

    @Immutable
    data class PhotoTile(val item: MediaItem) : PicturesTimelineEntry {
        override val stableKey: String = "pic_${item.id}"
        override val dateLabel: String = item.monthYearLabel
    }
}

enum class RootPickerTab(val label: String) {
    MY_DRIVE("My Drive"),
    COMPUTERS("Computers"),
    PASTE_LINK("Paste link")
}

@Immutable
data class ComputerFolderBrowser(
    val path: List<DriveFolder>,
    val isLoading: Boolean = true,
    val folders: List<DriveFolder> = emptyList(),
    val errorMessage: String? = null
)

@Immutable
data class RootPickerUiState(
    val isOpen: Boolean = false,
    val selectedTab: RootPickerTab = RootPickerTab.MY_DRIVE,
    val urlOrIdInput: String = "",
    val customNameInput: String = "",
    val isPinningByUrl: Boolean = false,
    val isLoadingSharedFolders: Boolean = false,
    val sharedFolders: List<DriveFolder> = emptyList(),
    val computerBrowser: ComputerFolderBrowser? = null,
    val errorMessage: String? = null
)

@Immutable
data class HomeUiState(
    val selectedTab: MainTab = MainTab.ALBUMS,
    val hasLocalMediaPermission: Boolean = false,
    val hasPartialMediaPermission: Boolean = false,
    val localPictures: List<MediaItem> = emptyList(),
    val timelineEntries: List<PicturesTimelineEntry> = emptyList(),
    val localAlbums: List<DriveFolder> = emptyList(),
    val unifiedAlbumGridItems: List<AlbumGridItem> = emptyList(),
    val allCollections: List<AlbumCollectionEntity> = emptyList(),
    val allAvailableAlbums: List<DriveFolder> = emptyList(),
    val hiddenAlbumIds: Set<String> = emptySet(),
    val favoriteItemIds: Set<String> = emptySet(),
    val albumSortMode: AlbumSortMode = AlbumSortMode.CUSTOM,
    val albumsFilter: AlbumsTabFilter = AlbumsTabFilter.ALL,
    val stories: List<GalleryStory> = emptyList(),
    val gridColumns: Int = 4,
    val showCloudBadges: Boolean = true,
    val lockHiddenAlbums: Boolean = false,
    val highResThumbnailsAllowed: Boolean = true,
    val serviceAccountState: ServiceAccountState = ServiceAccountState.NotConfigured,
    val pinnedRoots: List<DriveFolder> = emptyList(),
    val syncStatus: SyncStatus = SyncStatus(),
    val networkState: NetworkState = NetworkState(),
    val selectedPictureIds: Set<String> = emptySet(),
    val forcePictureSelectionMode: Boolean = false,
    val selectedAlbumIds: Set<String> = emptySet(),
    val forceAlbumSelectionMode: Boolean = false,
    val isExporting: Boolean = false,
    val infoItem: MediaItem? = null,
    val infoPath: String = "On Device",
    val pendingShareUris: List<Uri>? = null,
    val pendingTrashIntentSender: IntentSender? = null,
    val setupError: String? = null,
    val snackbarMessage: String? = null,
    val rootPicker: RootPickerUiState = RootPickerUiState(),
    val isDriveSheetOpen: Boolean = false
) {
    val isPictureSelectionMode: Boolean
        get() = forcePictureSelectionMode || selectedPictureIds.isNotEmpty()
    val isAlbumSelectionMode: Boolean
        get() = forceAlbumSelectionMode || selectedAlbumIds.isNotEmpty()
}

class HomeViewModel(
    private val credentialStore: CredentialStore,
    private val tokenManager: ServiceAccountTokenManager,
    private val repository: GalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val organizationRepository: AlbumOrganizationRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val preferencesManager: PreferencesManager,
    private val cloudProfileSyncManager: CloudProfileSyncManager,
    private val ocrIndexerManager: com.luminagallery.drive.data.repository.OcrIndexerManager,
    private val syncUseCase: SyncGalleryUseCase,
    private val manageRootsUseCase: ManageRootsUseCase,
    private val exportMediaUseCase: ExportMediaUseCase,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    private val selectedTabFlow = MutableStateFlow(MainTab.ALBUMS)
    private var hasUserChangedTab = false
    private val storiesActivatedFlow = MutableStateFlow(false)
    private val albumsFilterFlow = MutableStateFlow(AlbumsTabFilter.ALL)
    private val permissionGrantedFlow = MutableStateFlow(localMediaRepository.hasMediaPermission())
    private val partialPermissionFlow = MutableStateFlow(localMediaRepository.hasPartialMediaPermission())
    private val locationPermVersionFlow = MutableStateFlow(0)
    private val selectedPictureIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val forcePictureSelectionFlow = MutableStateFlow(false)
    private val selectedAlbumIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val forceAlbumSelectionFlow = MutableStateFlow(false)
    private val isExportingFlow = MutableStateFlow(false)
    private val infoSheetFlow = MutableStateFlow<Pair<MediaItem, String>?>(null)
    private val pendingShareFlow = MutableStateFlow<List<Uri>?>(null)
    private val pendingTrashFlow = MutableStateFlow<IntentSender?>(null)
    private val setupErrorFlow = MutableStateFlow<String?>(null)
    private val snackbarFlow = MutableStateFlow<String?>(null)
    private val rootPickerFlow = MutableStateFlow(RootPickerUiState())
    private var computerFoldersJob: Job? = null
    private val isDriveSheetOpenFlow = MutableStateFlow(false)
    private val locationsFlow = MutableStateFlow<List<LocationPhotoGroup>>(emptyList())
    private val dayLocationsFlow = MutableStateFlow<Map<String, String>>(emptyMap())

    private val showDayLocationsPrefFlow = preferencesManager.preferencesFlow
        .map { it.showDayLocationHeaders }
        .distinctUntilChanged()

    private val localContentFlow = combine(
        combine(permissionGrantedFlow, partialPermissionFlow) { full, partial -> full to partial },
        combine(
            localMediaRepository.observeAllLocalMedia(),
            dayLocationsFlow,
            showDayLocationsPrefFlow,
            extrasRepository.excludedAlbumIdsFlow
        ) { pics, dayLocs, showDayHeaders, excludedIds ->
            val visiblePics = if (excludedIds.isEmpty()) pics else pics.filterNot { it.parentId in excludedIds }
            Triple(visiblePics, dayLocs, showDayHeaders)
        },
        localMediaRepository.observeLocalAlbums(),
        organizationRepository.observeGridItems(parentCollectionId = null),
        organizationRepository.observeAllCollections()
    ) { (hasPerm, hasPartial), (pictures, dayLocs, showDayLocations), albums, unifiedItems, allCols ->
        LocalContent(
            hasPermission = hasPerm,
            hasPartialPermission = hasPartial,
            pictures = pictures,
            timelineEntries = buildTimelineEntries(
                items = pictures,
                dayLocations = if (showDayLocations) dayLocs else emptyMap()
            ),
            albums = albums,
            unifiedGridItems = unifiedItems,
            allCollections = allCols
        )
    }.flowOn(Dispatchers.Default)

    private val lazyStoriesFlow = storiesActivatedFlow.flatMapLatest { active ->
        if (!active) {
            flowOf(emptyList())
        } else {
            combine(
                localMediaRepository.observeAllLocalMedia(),
                repository.searchMedia("", null, null, null),
                locationsFlow,
                extrasRepository.favoriteStoryIdsFlow,
                extrasRepository.excludedAlbumIdsFlow
            ) { localPics, drivePics, locations, favStoryIds, excludedIds ->
                val blockedIds = repository.expandBlockedFolderIds(excludedIds)
                val allVisible = if (blockedIds.isEmpty()) {
                    localPics + drivePics
                } else {
                    (localPics + drivePics).filterNot { it.parentId in blockedIds }
                }
                val visibleLocationGroups = if (blockedIds.isEmpty()) {
                    locations
                } else {
                    locations.mapNotNull { group ->
                        val items = group.items.filterNot { it.parentId in blockedIds }
                        if (items.isEmpty()) null else group.copy(items = items)
                    }
                }
                extrasRepository.generateStories(
                    allMedia = allVisible.sortedByDescending { it.createdTimeEpochMs },
                    locations = visibleLocationGroups,
                    favoriteStoryIds = favStoryIds
                )
            }.flowOn(Dispatchers.Default)
        }
    }

    private val storiesAndExtrasFlow = combine(
        lazyStoriesFlow,
        organizationRepository.observeAllAvailableAlbums(),
        extrasRepository.hiddenAlbumIdsFlow,
        extrasRepository.favoriteItemIdsFlow
    ) { stories, allAlbums, hiddenIds, favIds ->
        ExtrasBundle(
            stories = stories,
            allAvailableAlbums = allAlbums,
            hiddenAlbumIds = hiddenIds,
            favoriteItemIds = favIds
        )
    }.flowOn(Dispatchers.Default)

    private val driveStateFlow = combine(
        credentialStore.state,
        repository.observePinnedRoots(),
        repository.syncStatusFlow,
        networkMonitor.networkState,
        isDriveSheetOpenFlow
    ) { account, roots, sync, net, sheetOpen ->
        DriveBundle(account, roots, sync, net, sheetOpen)
    }

    private val selectionOverlayFlow = combine(
        combine(
            selectedPictureIdsFlow,
            forcePictureSelectionFlow,
            selectedAlbumIdsFlow,
            forceAlbumSelectionFlow
        ) { pics, forcePics, albums, forceAlbums ->
            SelectionState(pics, forcePics, albums, forceAlbums)
        },
        isExportingFlow,
        infoSheetFlow,
        pendingShareFlow,
        pendingTrashFlow
    ) { sel, exporting, info, share, trash ->
        SelectionBundle(
            selectedIds = sel.selectedPics,
            forcePicMode = sel.forcePics,
            selectedAlbumIds = sel.selectedAlbums,
            forceAlbumMode = sel.forceAlbums,
            isExporting = exporting,
            info = info,
            pendingShareUris = share,
            pendingTrashSender = trash
        )
    }

    private val homePrefsSliceFlow = preferencesManager.preferencesFlow
        .map { prefs ->
            HomePrefsSlice(
                gridColumns = prefs.gridColumns,
                albumSortMode = prefs.albumSortMode,
                showCloudBadges = prefs.showCloudBadges,
                lockHiddenAlbums = prefs.lockHiddenAlbums,
                wifiOnlyHighRes = prefs.wifiOnlyHighRes
            )
        }
        .distinctUntilChanged()

    private val miscOverlayFlow = combine(
        combine(selectedTabFlow, albumsFilterFlow) { tab, filter -> tab to filter },
        homePrefsSliceFlow,
        setupErrorFlow,
        snackbarFlow,
        rootPickerFlow
    ) { (tab, filter), prefs, setupErr, snack, picker ->
        MiscBundle(
            selectedTab = tab,
            albumsFilter = filter,
            gridColumns = prefs.gridColumns,
            albumSortMode = prefs.albumSortMode,
            showCloudBadges = prefs.showCloudBadges,
            lockHiddenAlbums = prefs.lockHiddenAlbums,
            wifiOnlyHighRes = prefs.wifiOnlyHighRes,
            setupError = setupErr,
            snackbarMessage = snack,
            rootPicker = picker
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        localContentFlow,
        storiesAndExtrasFlow,
        driveStateFlow,
        selectionOverlayFlow,
        miscOverlayFlow
    ) { local, extras, drive, selection, misc ->
        val pinnedIdSet = drive.pinnedRoots.mapTo(HashSet()) { it.id }
        val updatedShared = misc.rootPicker.sharedFolders.map { folder ->
            folder.copy(isPinned = folder.id in pinnedIdSet)
        }
        val updatedComputers = misc.rootPicker.computerBrowser?.let { browser ->
            browser.copy(folders = browser.folders.map { folder ->
                folder.copy(isPinned = folder.id in pinnedIdSet)
            })
        }
        val filteredAlbumsGrid = when (misc.albumsFilter) {
            AlbumsTabFilter.ALL -> local.unifiedGridItems
            AlbumsTabFilter.ON_DEVICE -> local.unifiedGridItems.filter {
                it is AlbumGridItem.AlbumEntry && it.folder.isLocal
            }
            AlbumsTabFilter.GOOGLE_DRIVE -> local.unifiedGridItems.filter {
                (it is AlbumGridItem.AlbumEntry && !it.folder.isLocal) ||
                    (it is AlbumGridItem.CollectionEntry && it.hasDriveChild)
            }
            AlbumsTabFilter.COLLECTIONS -> local.unifiedGridItems.filterIsInstance<AlbumGridItem.CollectionEntry>()
        }
        HomeUiState(
            selectedTab = misc.selectedTab,
            hasLocalMediaPermission = local.hasPermission,
            hasPartialMediaPermission = local.hasPartialPermission,
            localPictures = local.pictures,
            timelineEntries = local.timelineEntries,
            localAlbums = local.albums,
            unifiedAlbumGridItems = filteredAlbumsGrid,
            allCollections = local.allCollections,
            allAvailableAlbums = extras.allAvailableAlbums,
            hiddenAlbumIds = extras.hiddenAlbumIds,
            favoriteItemIds = extras.favoriteItemIds,
            albumSortMode = misc.albumSortMode,
            albumsFilter = misc.albumsFilter,
            stories = extras.stories,
            gridColumns = misc.gridColumns,
            showCloudBadges = misc.showCloudBadges,
            lockHiddenAlbums = misc.lockHiddenAlbums,
            highResThumbnailsAllowed = drive.networkState.isUnmeteredWifi || !misc.wifiOnlyHighRes,
            serviceAccountState = drive.account,
            pinnedRoots = drive.pinnedRoots,
            syncStatus = drive.syncStatus,
            networkState = drive.networkState,
            selectedPictureIds = selection.selectedIds,
            forcePictureSelectionMode = selection.forcePicMode,
            selectedAlbumIds = selection.selectedAlbumIds,
            forceAlbumSelectionMode = selection.forceAlbumMode,
            isExporting = selection.isExporting,
            infoItem = selection.info?.first,
            infoPath = selection.info?.second ?: "On Device",
            pendingShareUris = selection.pendingShareUris,
            pendingTrashIntentSender = selection.pendingTrashSender,
            setupError = misc.setupError,
            snackbarMessage = misc.snackbarMessage,
            rootPicker = misc.rootPicker.copy(
                sharedFolders = updatedShared,
                computerBrowser = updatedComputers
            ),
            isDriveSheetOpen = drive.isSheetOpen
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState(
            hasLocalMediaPermission = localMediaRepository.hasMediaPermission(),
            hasPartialMediaPermission = localMediaRepository.hasPartialMediaPermission(),
            serviceAccountState = credentialStore.state.value,
            networkState = networkMonitor.networkState.value
        )
    )

    init {
        viewModelScope.launch {
            val startTab = runCatching {
                preferencesManager.getCurrentPreferences().defaultStartTab
            }.getOrDefault(MainTab.ALBUMS)
            if (!hasUserChangedTab) {
                selectedTabFlow.value = startTab
                if (startTab == MainTab.STORIES) {
                    storiesActivatedFlow.value = true
                }
            }
        }
        // Pre-warm sidebar & collection caches shortly after initial render so opening any album is 0ms!
        viewModelScope.launch(Dispatchers.Default) {
            delay(350L)
            organizationRepository.observeSidebarTree().collect { /* keep warm */ }
        }
        if (credentialStore.state.value is ServiceAccountState.Configured) {
            viewModelScope.launch {
                // Let initial local UI & thumbnails render before starting Drive TLS sync
                delay(600L)
                triggerSmartSync()
            }
        }
        // Observe shared Room-cached location index for Pictures day headers + Stories Trips
        viewModelScope.launch {
            extrasRepository.locationProgressFlow.collect { progress ->
                dayLocationsFlow.value = progress.dayLocations
                locationsFlow.value = progress.groups
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Defer background EXIF GPS indexing briefly so cold launch has uncontested CPU & flash I/O
            delay(1_500L)
            combine(
                localMediaRepository.observeAllLocalMedia(),
                locationPermVersionFlow
            ) { localPics, tick -> localPics to tick }
                .distinctUntilChangedBy { (pics, tick) ->
                    Triple(pics.size, tick, localMediaRepository.hasMediaLocationPermission())
                }
                .collect { (localPics, _) ->
                    extrasRepository.syncLocationsIndex(localPics)
                    // Lightweight warm-up only; bulk indexing runs in charging-constrained MediaIndexWorker
                    ocrIndexerManager.syncOcrIndex(localPics, maxNewItemsPerRun = 20)
                }
        }
    }

    fun selectTab(tab: MainTab) {
        hasUserChangedTab = true
        selectedTabFlow.value = tab
        if (tab == MainTab.STORIES) {
            storiesActivatedFlow.value = true
        }
        if (tab != MainTab.PICTURES) {
            selectedPictureIdsFlow.value = emptySet()
            forcePictureSelectionFlow.value = false
        }
        if (tab != MainTab.ALBUMS) {
            selectedAlbumIdsFlow.value = emptySet()
            forceAlbumSelectionFlow.value = false
        }
    }

    fun enterAlbumSelectionMode() {
        forceAlbumSelectionFlow.value = true
    }

    fun enterPictureSelectionMode() {
        forcePictureSelectionFlow.value = true
    }

    fun setAlbumSortMode(mode: AlbumSortMode) {
        viewModelScope.launch {
            preferencesManager.setAlbumSortMode(mode)
        }
    }

    fun setAlbumsFilter(filter: AlbumsTabFilter) {
        albumsFilterFlow.value = filter
    }

    fun toggleHiddenAlbum(albumId: String) {
        val currentlyHidden = albumId in uiState.value.hiddenAlbumIds
        viewModelScope.launch {
            extrasRepository.toggleHiddenAlbum(albumId, currentlyHidden)
        }
    }

    fun toggleFavoriteStory(story: GalleryStory) {
        viewModelScope.launch {
            extrasRepository.toggleFavoriteStory(story.id, story.isFavorited)
        }
    }

    fun openDriveManagerSheet() {
        isDriveSheetOpenFlow.value = true
    }

    fun closeDriveManagerSheet() {
        isDriveSheetOpenFlow.value = false
    }

    fun toggleAlbumSelection(item: AlbumGridItem) {
        selectedAlbumIdsFlow.update { current ->
            val next = if (item.itemId in current) current - item.itemId else current + item.itemId
            if (next.isEmpty() && !forceAlbumSelectionFlow.value) emptySet() else next
        }
    }

    fun clearAlbumSelection() {
        selectedAlbumIdsFlow.value = emptySet()
        forceAlbumSelectionFlow.value = false
    }

    fun groupSelectedAlbumsIntoCollection(name: String) {
        val selected = uiState.value.unifiedAlbumGridItems.filter { it.itemId in selectedAlbumIdsFlow.value }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            organizationRepository.createCollectionFromItems(
                parentCollectionId = null,
                items = selected,
                collectionName = name
            ).onSuccess {
                selectedAlbumIdsFlow.value = emptySet()
                forceAlbumSelectionFlow.value = false
                snackbarFlow.value = "Created collection \"${name.trim().ifBlank { "New Collection" }}\""
            }
        }
    }

    fun moveSelectedAlbumsIntoCollection(targetCollectionId: String) {
        val selected = uiState.value.unifiedAlbumGridItems.filter { it.itemId in selectedAlbumIdsFlow.value }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            var moved = 0
            for (item in selected) {
                if (organizationRepository.moveItemIntoCollection(item, targetCollectionId)) moved++
            }
            selectedAlbumIdsFlow.value = emptySet()
            forceAlbumSelectionFlow.value = false
            if (moved > 0) snackbarFlow.value = "Moved $moved item(s) to collection"
        }
    }

    fun unpinSelectedDriveAlbums() {
        val selected = uiState.value.unifiedAlbumGridItems
            .filterIsInstance<AlbumGridItem.AlbumEntry>()
            .filter { !it.folder.isLocal && it.itemId in selectedAlbumIdsFlow.value }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            selected.forEach { manageRootsUseCase.unpin(it.folder.id) }
            selectedAlbumIdsFlow.value = emptySet()
            forceAlbumSelectionFlow.value = false
            snackbarFlow.value = "Removed ${selected.size} Drive album(s)"
        }
    }

    fun onMediaPermissionResult() {
        val granted = localMediaRepository.hasMediaPermission()
        permissionGrantedFlow.value = granted
        partialPermissionFlow.value = localMediaRepository.hasPartialMediaPermission()
        locationPermVersionFlow.update { it + 1 }
        localMediaRepository.notifyPermissionChanged()
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch { preferencesManager.setGridColumns(columns) }
    }

    fun togglePictureSelection(itemId: String) {
        selectedPictureIdsFlow.update { current ->
            if (itemId in current) current - itemId else current + itemId
        }
    }

    fun setPictureSelected(itemId: String, selected: Boolean) {
        selectedPictureIdsFlow.update { current ->
            if (selected) current + itemId else current - itemId
        }
    }

    fun setPicturesSelectedRange(ids: Set<String>, selected: Boolean) {
        if (ids.isEmpty()) return
        selectedPictureIdsFlow.update { current ->
            if (selected) current + ids else current - ids
        }
    }

    fun clearPictureSelection() {
        selectedPictureIdsFlow.value = emptySet()
        forcePictureSelectionFlow.value = false
    }

    fun shareSelectedPictures() {
        val selected = uiState.value.localPictures.filter { it.id in selectedPictureIdsFlow.value }
        if (selected.isEmpty() || isExportingFlow.value) return
        viewModelScope.launch {
            isExportingFlow.value = true
            exportMediaUseCase.prepareShareUris(selected)
                .onSuccess { uris ->
                    pendingShareFlow.value = uris
                    selectedPictureIdsFlow.value = emptySet()
                    forcePictureSelectionFlow.value = false
                }
                .onFailure { err ->
                    snackbarFlow.value = err.message ?: "Unable to share selected items"
                }
            isExportingFlow.value = false
        }
    }

    fun requestTrashSelectedPictures() {
        val selected = uiState.value.localPictures.filter { it.id in selectedPictureIdsFlow.value }
        if (selected.isEmpty()) return
        val supportsTrash = localMediaRepository.supportsMediaStoreTrash()
        if (localMediaRepository.canDeleteDirectlyWithoutDialog()) {
            viewModelScope.launch {
                localMediaRepository.trashLocalItemsDirectly(selected, toTrash = true)
                    .onSuccess { count ->
                        selectedPictureIdsFlow.value = emptySet()
                        forcePictureSelectionFlow.value = false
                        localMediaRepository.notifyPermissionChanged()
                        snackbarFlow.value = if (!supportsTrash) {
                            if (count == 1) "Deleted permanently" else "Deleted $count items permanently"
                        } else {
                            if (count == 1) "Moved to trash" else "Moved $count items to trash"
                        }
                    }
                    .onFailure { err ->
                        val sender = localMediaRepository.buildTrashIntentSender(selected)
                        if (sender != null) {
                            pendingTrashFlow.value = sender
                        } else {
                            snackbarFlow.value = err.localizedMessage ?: "Unable to delete selected items"
                        }
                    }
            }
            return
        }
        val sender = localMediaRepository.buildTrashIntentSender(selected)
        if (sender != null) {
            pendingTrashFlow.value = sender
        } else {
            snackbarFlow.value = "Unable to move items to trash"
        }
    }

    fun onTrashCompleted(approved: Boolean) {
        pendingTrashFlow.value = null
        if (approved) {
            selectedPictureIdsFlow.value = emptySet()
            forcePictureSelectionFlow.value = false
            localMediaRepository.notifyPermissionChanged()
            snackbarFlow.value = "Moved to trash"
        }
    }

    fun openSelectedPictureInfo() {
        val id = selectedPictureIdsFlow.value.singleOrNull() ?: return
        val item = uiState.value.localPictures.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val (exif, path) = localMediaRepository.readLocalExifAndPath(item)
            infoSheetFlow.value = item.copy(exif = exif) to path
        }
    }

    fun closeInfoSheet() {
        infoSheetFlow.value = null
    }

    fun consumePendingShare() {
        pendingShareFlow.value = null
    }

    fun onGoogleSignInSuccess(
        accessToken: String,
        fallbackEmail: String?,
        grantedAppDataScope: Boolean = true
    ) {
        viewModelScope.launch {
            setupErrorFlow.value = null
            tokenManager.cacheInteractiveOAuthToken(
                accessToken = accessToken,
                grantedAppDataScope = grantedAppDataScope
            )
            credentialStore.saveGoogleSignInSession(
                email = fallbackEmail ?: "Google Account",
                displayName = null
            )

            var resolvedEmail = fallbackEmail
            repository.fetchSignedInUserProfile()
                .onSuccess { (email, displayName) ->
                    resolvedEmail = email
                    credentialStore.saveGoogleSignInSession(email, displayName)
                }

            val restoreSummary = cloudProfileSyncManager.syncOnSignIn().getOrNull()
            val hasPinnedRootsNow = repository.observePinnedRoots().first().isNotEmpty() ||
                (restoreSummary?.restoredPinnedRootsCount ?: 0) > 0

            snackbarFlow.value = when {
                restoreSummary?.restoredFromCloud == true -> {
                    val parts = buildList {
                        if (restoreSummary.restoredPinnedRootsCount > 0) {
                            add("${restoreSummary.restoredPinnedRootsCount} Drive album(s)")
                        }
                        if (restoreSummary.restoredCollectionsCount > 0) {
                            add("${restoreSummary.restoredCollectionsCount} group(s)")
                        }
                    }
                    if (parts.isNotEmpty()) {
                        "Restored settings & ${parts.joinToString(", ")} from Google"
                    } else {
                        "Restored settings & layout from Google"
                    }
                }
                !grantedAppDataScope ->
                    "Connected Google Drive · enable backup permission in Settings"
                !resolvedEmail.isNullOrBlank() -> "Signed in as $resolvedEmail"
                else -> "Connected to Google Drive"
            }

            if (!hasPinnedRootsNow) {
                isDriveSheetOpenFlow.value = false
                openRootPicker()
            } else {
                isDriveSheetOpenFlow.value = false
                triggerSmartSync(waitIfBusy = true)
            }
        }
    }

    fun onGoogleSignInError(message: String) {
        setupErrorFlow.value = message
    }

    fun importCredentials(uri: Uri) {
        viewModelScope.launch {
            setupErrorFlow.value = null
            credentialStore.importFromUri(uri)
                .onSuccess { configured ->
                    tokenManager.invalidate()
                    snackbarFlow.value = "Connected as ${configured.clientEmail}"
                    triggerSmartSync()
                }
                .onFailure { error ->
                    setupErrorFlow.value = error.message ?: "Unable to read credentials.json"
                }
        }
    }

    fun triggerSmartSync(forceFullRescan: Boolean = false, waitIfBusy: Boolean = false) {
        if (credentialStore.state.value !is ServiceAccountState.Configured) return
        viewModelScope.launch {
            syncUseCase.syncDeltaOrInitial(
                forceFullRescan = forceFullRescan,
                waitIfBusy = waitIfBusy
            )
                .onFailure { err ->
                    snackbarFlow.value = err.message ?: "Sync failed"
                }
        }
    }

    fun openRootPicker(initialTab: RootPickerTab = RootPickerTab.MY_DRIVE) {
        isDriveSheetOpenFlow.value = false
        computerFoldersJob?.cancel()
        rootPickerFlow.update {
            it.copy(
                isOpen = true,
                selectedTab = initialTab,
                computerBrowser = null,
                errorMessage = null
            )
        }
        when (initialTab) {
            RootPickerTab.MY_DRIVE -> loadSharedFolders()
            RootPickerTab.COMPUTERS -> loadComputerFolders()
            RootPickerTab.PASTE_LINK -> Unit
        }
    }

    fun closeRootPicker() {
        computerFoldersJob?.cancel()
        rootPickerFlow.update { it.copy(isOpen = false, computerBrowser = null, errorMessage = null) }
    }

    fun selectRootPickerTab(tab: RootPickerTab) {
        val current = rootPickerFlow.value
        rootPickerFlow.update { it.copy(selectedTab = tab, errorMessage = null) }
        when (tab) {
            RootPickerTab.MY_DRIVE -> {
                if (current.sharedFolders.isEmpty() && !current.isLoadingSharedFolders) loadSharedFolders()
            }
            RootPickerTab.COMPUTERS -> {
                if (current.computerBrowser == null) loadComputerFolders()
            }
            RootPickerTab.PASTE_LINK -> Unit
        }
    }

    fun refreshComputerFolders() {
        loadComputerFolders(rootPickerFlow.value.computerBrowser?.path.orEmpty())
    }

    fun openComputerFolder(folder: DriveFolder) {
        val browser = rootPickerFlow.value.computerBrowser ?: return
        if (browser.folders.none { it.id == folder.id }) return
        loadComputerFolders(browser.path + folder)
    }

    fun goUpFromComputerFolder() {
        val path = rootPickerFlow.value.computerBrowser?.path.orEmpty()
        if (path.isNotEmpty()) loadComputerFolders(path.dropLast(1))
    }

    private fun loadComputerFolders(path: List<DriveFolder> = emptyList()) {
        computerFoldersJob?.cancel()
        rootPickerFlow.update { it.copy(computerBrowser = ComputerFolderBrowser(path)) }
        computerFoldersJob = viewModelScope.launch {
            val result = manageRootsUseCase.browseComputerFolders(path.lastOrNull()?.id)
            rootPickerFlow.update {
                it.copy(
                    computerBrowser = ComputerFolderBrowser(
                        path = path,
                        isLoading = false,
                        folders = result.getOrDefault(emptyList()),
                        errorMessage = result.exceptionOrNull()?.let { error ->
                            error.message ?: "Could not load computer folders."
                        }
                    )
                )
            }
        }
    }

    fun onUrlOrIdInputChanged(value: String) {
        rootPickerFlow.update { it.copy(urlOrIdInput = value, errorMessage = null) }
    }

    fun onCustomNameInputChanged(value: String) {
        rootPickerFlow.update { it.copy(customNameInput = value) }
    }

    fun pinFolderByUrlOrId() {
        val current = rootPickerFlow.value
        if (current.urlOrIdInput.isBlank() || current.isPinningByUrl) return

        viewModelScope.launch {
            rootPickerFlow.update { it.copy(isPinningByUrl = true, errorMessage = null) }
            manageRootsUseCase.pinByUrlOrId(
                urlOrId = current.urlOrIdInput,
                alias = current.customNameInput
            ).onSuccess { pinnedFolder ->
                closeRootPicker()
                rootPickerFlow.update {
                    it.copy(
                        urlOrIdInput = "",
                        customNameInput = "",
                        isPinningByUrl = false,
                        errorMessage = null
                    )
                }
                snackbarFlow.value = "Added \"${pinnedFolder.name}\" — syncing in background"
                launch { syncUseCase.syncFolderTree(pinnedFolder.id) }
            }.onFailure { err ->
                rootPickerFlow.update {
                    it.copy(
                        isPinningByUrl = false,
                        errorMessage = err.message ?: "Could not access that Drive folder."
                    )
                }
            }
        }
    }

    fun loadSharedFolders() {
        viewModelScope.launch {
            rootPickerFlow.update { it.copy(isLoadingSharedFolders = true, errorMessage = null) }
            manageRootsUseCase.discoverSharedFolders()
                .onSuccess { folders ->
                    rootPickerFlow.update {
                        it.copy(
                            isLoadingSharedFolders = false,
                            sharedFolders = folders,
                            errorMessage = if (folders.isEmpty()) {
                                "No folders found in Google Drive yet."
                            } else null
                        )
                    }
                }
                .onFailure { err ->
                    rootPickerFlow.update {
                        it.copy(
                            isLoadingSharedFolders = false,
                            errorMessage = err.message ?: "Failed to load Google Drive folders."
                        )
                    }
                }
        }
    }

    fun togglePinSharedFolder(folder: DriveFolder) {
        viewModelScope.launch {
            val nowPinned = manageRootsUseCase.togglePin(folder)
            if (nowPinned) {
                snackbarFlow.value = "Added \"${folder.name}\" to Albums"
                launch { syncUseCase.syncFolderTree(folder.id) }
            } else {
                snackbarFlow.value = "Removed \"${folder.name}\""
            }
        }
    }

    fun unpinRootFolder(folder: DriveFolder) {
        viewModelScope.launch {
            manageRootsUseCase.unpin(folder.id)
            snackbarFlow.value = "Removed \"${folder.name}\" from Albums"
        }
    }

    fun saveRootAlbumOrder(orderedItems: List<AlbumGridItem>) {
        viewModelScope.launch {
            if (uiState.value.albumSortMode != AlbumSortMode.CUSTOM) {
                preferencesManager.setAlbumSortMode(AlbumSortMode.CUSTOM)
            }
            organizationRepository.saveReorder(parentCollectionId = null, orderedItems = orderedItems)
        }
    }

    fun createCollectionFromPair(
        dragged: AlbumGridItem,
        target: AlbumGridItem,
        name: String
    ) {
        viewModelScope.launch {
            organizationRepository.createCollectionFromPair(
                parentCollectionId = null,
                draggedItem = dragged,
                targetItem = target,
                collectionName = name
            ).onSuccess {
                snackbarFlow.value = "Created collection \"${name.trim().ifBlank { "New Collection" }}\""
            }
        }
    }

    fun createEmptyCollection(name: String) {
        viewModelScope.launch {
            organizationRepository.createEmptyCollection(parentCollectionId = null, name = name)
            snackbarFlow.value = "Created collection \"${name.trim().ifBlank { "New Collection" }}\""
        }
    }

    fun moveItemIntoCollection(item: AlbumGridItem, targetCollectionId: String) {
        viewModelScope.launch {
            val ok = organizationRepository.moveItemIntoCollection(item, targetCollectionId)
            snackbarFlow.value = if (ok) {
                "Added \"${item.displayName}\" to collection"
            } else {
                "Cannot move a collection inside itself"
            }
        }
    }

    fun renameCollection(collectionId: String, newName: String) {
        viewModelScope.launch {
            organizationRepository.renameCollection(collectionId, newName)
            snackbarFlow.value = "Renamed collection"
        }
    }

    fun ungroupCollection(collectionId: String) {
        viewModelScope.launch {
            organizationRepository.ungroupCollection(collectionId)
            snackbarFlow.value = "Ungrouped collection"
        }
    }

    fun showMessage(message: String) {
        snackbarFlow.value = message
    }

    fun clearSnackbar() {
        snackbarFlow.value = null
    }

    private fun buildTimelineEntries(
        items: List<MediaItem>,
        dayLocations: Map<String, String>
    ): List<PicturesTimelineEntry> {
        if (items.isEmpty()) return emptyList()
        val entries = ArrayList<PicturesTimelineEntry>(items.size + 24)
        var currentDay: String? = null
        var sectionIdx = 0
        for (item in items) {
            if (item.daySectionLabel != currentDay) {
                currentDay = item.daySectionLabel
                entries.add(
                    PicturesTimelineEntry.DayHeader(
                        label = item.daySectionLabel,
                        locationLabel = dayLocations[item.daySectionLabel],
                        keySuffix = "${sectionIdx++}_${item.daySectionLabel}"
                    )
                )
            }
            entries.add(PicturesTimelineEntry.PhotoTile(item))
        }
        return entries
    }

    private data class LocalContent(
        val hasPermission: Boolean,
        val hasPartialPermission: Boolean,
        val pictures: List<MediaItem>,
        val timelineEntries: List<PicturesTimelineEntry>,
        val albums: List<DriveFolder>,
        val unifiedGridItems: List<AlbumGridItem>,
        val allCollections: List<AlbumCollectionEntity>
    )

    private data class HomePrefsSlice(
        val gridColumns: Int,
        val albumSortMode: AlbumSortMode,
        val showCloudBadges: Boolean,
        val lockHiddenAlbums: Boolean,
        val wifiOnlyHighRes: Boolean
    )

    private data class ExtrasBundle(
        val stories: List<GalleryStory>,
        val allAvailableAlbums: List<DriveFolder>,
        val hiddenAlbumIds: Set<String>,
        val favoriteItemIds: Set<String>
    )

    private data class DriveBundle(
        val account: ServiceAccountState,
        val pinnedRoots: List<DriveFolder>,
        val syncStatus: SyncStatus,
        val networkState: NetworkState,
        val isSheetOpen: Boolean
    )

    private data class SelectionState(
        val selectedPics: Set<String>,
        val forcePics: Boolean,
        val selectedAlbums: Set<String>,
        val forceAlbums: Boolean
    )

    private data class SelectionBundle(
        val selectedIds: Set<String>,
        val forcePicMode: Boolean,
        val selectedAlbumIds: Set<String>,
        val forceAlbumMode: Boolean,
        val isExporting: Boolean,
        val info: Pair<MediaItem, String>?,
        val pendingShareUris: List<Uri>?,
        val pendingTrashSender: IntentSender?
    )

    private data class MiscBundle(
        val selectedTab: MainTab,
        val albumsFilter: AlbumsTabFilter,
        val gridColumns: Int,
        val albumSortMode: AlbumSortMode,
        val showCloudBadges: Boolean,
        val lockHiddenAlbums: Boolean,
        val wifiOnlyHighRes: Boolean,
        val setupError: String?,
        val snackbarMessage: String?,
        val rootPicker: RootPickerUiState
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
            credentialStore = container.credentialStore,
            tokenManager = container.tokenManager,
            repository = container.galleryRepository,
            localMediaRepository = container.localMediaRepository,
            organizationRepository = container.albumOrganizationRepository,
            extrasRepository = container.galleryExtrasRepository,
            preferencesManager = container.preferencesManager,
            cloudProfileSyncManager = container.cloudProfileSyncManager,
            ocrIndexerManager = container.ocrIndexerManager,
            syncUseCase = container.syncGalleryUseCase,
            manageRootsUseCase = container.manageRootsUseCase,
            exportMediaUseCase = container.exportMediaUseCase,
            networkMonitor = container.networkMonitor
        ) as T
    }
}

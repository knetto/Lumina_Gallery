package com.luminagallery.drive.ui.screens.viewer

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
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.domain.model.SpecialGalleryFilter
import com.luminagallery.drive.domain.model.UserPreferences
import com.luminagallery.drive.domain.usecase.ExportMediaUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ViewerLaunchContext(
    val folderId: String,
    val initialItemId: String,
    val isFlattened: Boolean = false,
    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
    val mediaTypeFilter: MediaType? = null,
    val fromEpochMs: Long? = null,
    val toEpochMs: Long? = null,
    val specialFilter: SpecialGalleryFilter? = null
)

@Immutable
data class ViewerUiState(
    val items: List<MediaItem> = emptyList(),
    val initialPageIndex: Int = 0,
    val favoriteItemIds: Set<String> = emptySet(),
    val isUnmeteredWifi: Boolean = true,
    val isOnline: Boolean = true,
    val preferences: UserPreferences = UserPreferences(),
    val isExporting: Boolean = false,
    val activeDrivePath: String = "Google Drive",
    val activeExifOverride: MediaItem? = null,
    val showExifSheet: Boolean = false,
    val pendingShareUris: List<Uri>? = null,
    val pendingShareMimeType: String = "*/*",
    val pendingTrashIntentSender: IntentSender? = null,
    val snackbarMessage: String? = null
)

class ViewerViewModel(
    private val launchContext: ViewerLaunchContext,
    private val repository: GalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val preferencesManager: PreferencesManager,
    private val exportMediaUseCase: ExportMediaUseCase,
    networkMonitor: NetworkMonitor,
    private val initialTappedItem: MediaItem? = null
) : ViewModel() {

    private val isExportingFlow = MutableStateFlow(false)
    private val activePathFlow = MutableStateFlow("Google Drive")
    private val activeExifItemFlow = MutableStateFlow<MediaItem?>(null)
    private val showExifFlow = MutableStateFlow(false)
    private val pendingShareFlow = MutableStateFlow<Pair<List<Uri>, String>?>(null)
    private val pendingTrashFlow = MutableStateFlow<IntentSender?>(null)
    private val snackbarFlow = MutableStateFlow<String?>(null)

    private val mediaSourceFlow = when {
        launchContext.specialFilter != null -> when (launchContext.specialFilter) {
            SpecialGalleryFilter.VIDEOS -> combine(
                localMediaRepository.observeAllLocalMedia(),
                repository.searchMedia("", MediaType.VIDEO, null, null)
            ) { local, drive ->
                (local.filter { it.isVideo } + drive).sortedByDescending { it.createdTimeEpochMs }
            }

            SpecialGalleryFilter.FAVORITES -> combine(
                localMediaRepository.observeAllLocalMedia(),
                repository.searchMedia("", null, null, null),
                extrasRepository.favoriteItemIdsFlow
            ) { local, drive, favIds ->
                (local + drive)
                    .filter { it.id in favIds }
                    .sortedByDescending { it.createdTimeEpochMs }
            }

            SpecialGalleryFilter.RECENT -> combine(
                localMediaRepository.observeAllLocalMedia(),
                repository.searchMedia("", null, null, null)
            ) { local, drive ->
                (local + drive)
                    .sortedByDescending { it.createdTimeEpochMs }
                    .take(300)
            }

            SpecialGalleryFilter.TRASH -> localMediaRepository.observeTrashedLocalMedia()
        }

        launchContext.isSearchMode -> combine(
            localMediaRepository.searchLocalMedia(
                query = launchContext.searchQuery,
                mediaType = launchContext.mediaTypeFilter,
                fromEpochMs = launchContext.fromEpochMs,
                toEpochMs = launchContext.toEpochMs
            ),
            repository.searchMedia(
                query = launchContext.searchQuery,
                mediaType = launchContext.mediaTypeFilter,
                fromEpochMs = launchContext.fromEpochMs,
                toEpochMs = launchContext.toEpochMs
            )
        ) { localItems, driveItems ->
            (localItems + driveItems).sortedByDescending { it.createdTimeEpochMs }
        }

        LocalMediaRepository.isLocalFolderId(launchContext.folderId) ->
            localMediaRepository.observeLocalAlbumMedia(launchContext.folderId)

        launchContext.isFlattened -> repository.observeFlattenedMedia(launchContext.folderId)
        else -> repository.observeMediaInFolder(launchContext.folderId)
    }

    private val dialogAndActionsFlow = combine(
        pendingShareFlow,
        pendingTrashFlow,
        snackbarFlow
    ) { share, trash, snack ->
        Triple(share, trash, snack)
    }

    private val overlayFlow = combine(
        isExportingFlow,
        activePathFlow,
        activeExifItemFlow,
        showExifFlow,
        dialogAndActionsFlow
    ) { exporting, path, exifItem, showExif, (share, trash, snack) ->
        ViewerOverlay(exporting, path, exifItem, showExif, share, trash, snack)
    }

    val uiState: StateFlow<ViewerUiState> = combine(
        mediaSourceFlow,
        extrasRepository.favoriteItemIdsFlow,
        networkMonitor.networkState,
        preferencesManager.preferencesFlow,
        overlayFlow
    ) { rawItems, favIds, net, prefs, overlay ->
        val items = rawItems.distinctBy { it.id }
        val index = items.indexOfFirst { it.id == launchContext.initialItemId }.coerceAtLeast(0)
        ViewerUiState(
            items = items,
            initialPageIndex = index,
            favoriteItemIds = favIds,
            isUnmeteredWifi = net.isUnmeteredWifi || !prefs.wifiOnlyHighRes,
            isOnline = net.isOnline,
            preferences = prefs,
            isExporting = overlay.isExporting,
            activeDrivePath = overlay.activeDrivePath,
            activeExifOverride = overlay.activeExifItem,
            showExifSheet = overlay.showExifSheet,
            pendingShareUris = overlay.pendingShare?.first,
            pendingShareMimeType = overlay.pendingShare?.second ?: "*/*",
            pendingTrashIntentSender = overlay.pendingTrash,
            snackbarMessage = overlay.snackbarMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = buildInstantInitialState(networkMonitor)
    )

    /**
     * Populates frame 0 synchronously from in-memory caches (or the tapped grid item) so opening a
     * photo expands the real image immediately out of its thumbnail with zero black spinner flash.
     */
    private fun buildInstantInitialState(networkMonitor: NetworkMonitor): ViewerUiState {
        val prefs = preferencesManager.latestPreferences
        val net = networkMonitor.networkState.value
        val cached = when {
            launchContext.specialFilter == SpecialGalleryFilter.VIDEOS ->
                localMediaRepository.getCachedAlbumMedia(LocalMediaRepository.LOCAL_BUCKET_VIDEOS)
            launchContext.specialFilter == SpecialGalleryFilter.RECENT ->
                localMediaRepository.getCachedAlbumMedia(LocalMediaRepository.LOCAL_BUCKET_ALL).take(300)
            launchContext.specialFilter != null || launchContext.isSearchMode -> emptyList()
            LocalMediaRepository.isLocalFolderId(launchContext.folderId) ->
                localMediaRepository.getCachedAlbumMedia(launchContext.folderId)
            !launchContext.isFlattened && launchContext.folderId.isNotBlank() ->
                repository.getCachedFolderMedia(launchContext.folderId).orEmpty()
            else -> emptyList()
        }.distinctBy { it.id }

        val idxInCache = cached.indexOfFirst { it.id == launchContext.initialItemId }
        val initialItems: List<MediaItem>
        val initialIndex: Int
        if (idxInCache >= 0) {
            initialItems = cached
            initialIndex = idxInCache
        } else if (initialTappedItem != null && initialTappedItem.id == launchContext.initialItemId) {
            initialItems = listOf(initialTappedItem)
            initialIndex = 0
        } else {
            initialItems = emptyList()
            initialIndex = 0
        }

        return ViewerUiState(
            items = initialItems,
            initialPageIndex = initialIndex,
            isUnmeteredWifi = net.isUnmeteredWifi || !prefs.wifiOnlyHighRes,
            isOnline = net.isOnline,
            preferences = prefs
        )
    }

    fun toggleFavorite(item: MediaItem) {
        val currentlyFav = item.id in uiState.value.favoriteItemIds
        viewModelScope.launch {
            extrasRepository.toggleFavoriteItem(item.id, currentlyFav)
        }
    }

    fun openExifSheet(item: MediaItem) {
        viewModelScope.launch {
            if (item.isLocal) {
                val (exif, path) = localMediaRepository.readLocalExifAndPath(item)
                activeExifItemFlow.value = item.copy(exif = exif)
                activePathFlow.value = path
            } else {
                activeExifItemFlow.value = item
                activePathFlow.value = repository.getFolderPathString(item.parentId)
            }
            showExifFlow.value = true
        }
    }

    fun closeExifSheet() {
        showExifFlow.value = false
    }

    fun shareItem(item: MediaItem) {
        if (isExportingFlow.value) return
        viewModelScope.launch {
            isExportingFlow.value = true
            exportMediaUseCase.prepareShareUris(listOf(item))
                .onSuccess { uris ->
                    pendingShareFlow.value = uris to item.mimeType.ifBlank { "*/*" }
                }
                .onFailure { err ->
                    snackbarFlow.value = err.message ?: "Failed to prepare file for sharing"
                }
            isExportingFlow.value = false
        }
    }

    fun saveItemToDevice(item: MediaItem) {
        if (item.isLocal || isExportingFlow.value) return
        viewModelScope.launch {
            isExportingFlow.value = true
            exportMediaUseCase.saveToDevice(listOf(item))
                .onSuccess {
                    snackbarFlow.value = "Saved \"${item.name}\" to Pictures / Movies"
                }
                .onFailure { err ->
                    snackbarFlow.value = err.message ?: "Failed to save to device"
                }
            isExportingFlow.value = false
        }
    }

    fun requestTrashLocalItem(item: MediaItem) {
        if (!item.isLocal) return
        if (localMediaRepository.canManageMediaDirectly()) {
            viewModelScope.launch {
                localMediaRepository.trashLocalItemsDirectly(listOf(item), toTrash = true)
                    .onSuccess {
                        localMediaRepository.notifyPermissionChanged()
                        snackbarFlow.value = "Moved to trash"
                    }
                    .onFailure {
                        val sender = localMediaRepository.buildTrashIntentSender(listOf(item))
                        if (sender != null) {
                            pendingTrashFlow.value = sender
                        } else {
                            snackbarFlow.value = "Trash requires Android 11 or newer"
                        }
                    }
            }
            return
        }
        val sender = localMediaRepository.buildTrashIntentSender(listOf(item))
        if (sender != null) {
            pendingTrashFlow.value = sender
        } else {
            snackbarFlow.value = "Trash requires Android 11 or newer"
        }
    }

    fun onTrashCompleted(approved: Boolean) {
        pendingTrashFlow.value = null
        if (approved) {
            localMediaRepository.notifyPermissionChanged()
            snackbarFlow.value = "Moved to trash"
        }
    }

    fun consumePendingShare() {
        pendingShareFlow.value = null
    }

    fun showMessage(message: String) {
        snackbarFlow.value = message
    }

    fun clearSnackbar() {
        snackbarFlow.value = null
    }

    private data class ViewerOverlay(
        val isExporting: Boolean,
        val activeDrivePath: String,
        val activeExifItem: MediaItem?,
        val showExifSheet: Boolean,
        val pendingShare: Pair<List<Uri>, String>?,
        val pendingTrash: IntentSender?,
        val snackbarMessage: String?
    )

    class Factory(
        private val launchContext: ViewerLaunchContext,
        private val container: AppContainer,
        private val initialTappedItem: MediaItem? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ViewerViewModel(
            launchContext = launchContext,
            repository = container.galleryRepository,
            localMediaRepository = container.localMediaRepository,
            extrasRepository = container.galleryExtrasRepository,
            preferencesManager = container.preferencesManager,
            exportMediaUseCase = container.exportMediaUseCase,
            networkMonitor = container.networkMonitor,
            initialTappedItem = initialTappedItem
        ) as T
    }
}

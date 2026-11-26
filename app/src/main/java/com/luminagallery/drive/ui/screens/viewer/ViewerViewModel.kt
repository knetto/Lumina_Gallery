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
import com.luminagallery.drive.data.repository.ai.SmartRecognitionManager
import com.luminagallery.drive.domain.model.AlbumMediaFilter
import com.luminagallery.drive.domain.model.AlbumMediaSort
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.domain.model.SpecialGalleryFilter
import com.luminagallery.drive.domain.model.UserPreferences
import com.luminagallery.drive.domain.usecase.ExportMediaUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    val onlyWithText: Boolean = false,
    val specialFilter: SpecialGalleryFilter? = null,
    val albumFilter: AlbumMediaFilter = AlbumMediaFilter.ALL,
    val albumSort: AlbumMediaSort = AlbumMediaSort.DATE_DESC,
    val locationKey: String? = null,
    val personIds: List<String> = emptyList()
)

@Immutable
data class ViewerUiState(
    val items: List<MediaItem> = emptyList(),
    val initialPageIndex: Int = 0,
    val hasLoadedOnce: Boolean = false,
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
    val pendingFavoriteIntentSender: IntentSender? = null,
    val snackbarMessage: String? = null
)

class ViewerViewModel(
    private val launchContext: ViewerLaunchContext,
    private val repository: GalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val smartRecognitionManager: SmartRecognitionManager,
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
    private val pendingFavoriteFlow = MutableStateFlow<IntentSender?>(null)
    private val snackbarFlow = MutableStateFlow<String?>(null)

    private val excludedAlbumIdsFlow = extrasRepository.excludedAlbumIdsFlow
        .map { ids -> repository.expandBlockedFolderIds(ids) }

    private val mediaSourceFlow = when {
        launchContext.personIds.isNotEmpty() && !launchContext.isSearchMode -> combine(
            localMediaRepository.observeAllLocalMedia(),
            repository.searchMedia("", null, null, null),
            smartRecognitionManager.allActiveFacesFlow,
            excludedAlbumIdsFlow
        ) { local, drive, allFaces, excluded ->
            val targetSet = launchContext.personIds.toSet()
            val photosByPerson = HashMap<String, MutableSet<String>>(targetSet.size)
            for (face in allFaces) {
                val pid = face.personId
                if (pid != null && pid in targetSet) {
                    photosByPerson.getOrPut(pid) { HashSet() }.add(face.itemId)
                }
            }
            val intersected = if (photosByPerson.size < targetSet.size) {
                emptySet()
            } else {
                var acc: Set<String>? = null
                for (ids in photosByPerson.values) {
                    acc = if (acc == null) ids else acc.intersect(ids)
                }
                acc.orEmpty()
            }
            (local + drive)
                .filter { it.id in intersected && it.parentId !in excluded }
                .sortedByDescending { it.createdTimeEpochMs }
        }.flowOn(Dispatchers.Default)

        !launchContext.locationKey.isNullOrBlank() -> combine(
            extrasRepository.locationProgressFlow,
            excludedAlbumIdsFlow
        ) { progress, excluded ->
            val group = progress.groups.firstOrNull { it.locationKey == launchContext.locationKey }
            group?.items.orEmpty().filterNot { it.parentId in excluded }
        }.flowOn(Dispatchers.Default)

        launchContext.specialFilter != null -> when (launchContext.specialFilter) {
            SpecialGalleryFilter.VIDEOS -> combine(
                localMediaRepository.observeAllLocalMedia(),
                repository.searchMedia("", MediaType.VIDEO, null, null),
                excludedAlbumIdsFlow
            ) { local, drive, excluded ->
                (local.filter { it.isVideo } + drive)
                    .filterNot { it.parentId in excluded }
                    .sortedByDescending { it.createdTimeEpochMs }
            }.flowOn(Dispatchers.Default)

            SpecialGalleryFilter.FAVORITES -> combine(
                localMediaRepository.observeAllLocalMedia(),
                repository.searchMedia("", null, null, null),
                extrasRepository.favoriteItemIdsFlow,
                excludedAlbumIdsFlow
            ) { local, drive, favIds, excluded ->
                (local + drive)
                    .filter { it.id in favIds && it.parentId !in excluded }
                    .sortedByDescending { it.createdTimeEpochMs }
            }.flowOn(Dispatchers.Default)

            SpecialGalleryFilter.RECENT -> combine(
                localMediaRepository.observeAllLocalMedia(),
                repository.searchMedia("", null, null, null),
                excludedAlbumIdsFlow
            ) { local, drive, excluded ->
                (local + drive)
                    .filterNot { it.parentId in excluded }
                    .sortedByDescending { it.createdTimeEpochMs }
                    .take(300)
            }.flowOn(Dispatchers.Default)

            SpecialGalleryFilter.TRASH -> localMediaRepository.observeTrashedLocalMedia()
        }

        launchContext.isSearchMode -> {
            val smartFlow = combine(
                smartRecognitionManager.allPersonsFlow,
                smartRecognitionManager.allActiveFacesFlow,
                smartRecognitionManager.smartIndexMapFlow
            ) { persons, faces, smartMap -> Triple(persons, faces, smartMap) }
            val enrichmentFlow = combine(
                extrasRepository.cachedLocationsMapFlow,
                extrasRepository.mediaAnnotationsFlow,
                extrasRepository.ocrTextMapFlow,
                excludedAlbumIdsFlow,
                smartFlow
            ) { locs, anns, ocr, excluded, (persons, faces, smartMap) ->
                SearchViewerEnrichment(locs, anns, ocr, excluded, persons, faces, smartMap)
            }
            combine(
                localMediaRepository.observeAllLocalMedia(),
                repository.searchMedia("", null, null, null),
                enrichmentFlow
            ) { localItems, driveItems, enrich ->
                val tokens = launchContext.searchQuery.trim().lowercase()
                    .split(Regex("\\s+")).map { it.removePrefix("#").trim() }.filter { it.isNotBlank() }
                val personById = enrich.persons.associateBy { it.personId }
                val personNamesByPhoto = HashMap<String, MutableList<String>>()
                val targetPersonSet = launchContext.personIds.toSet()
                val photosBySelectedPerson = if (targetPersonSet.isNotEmpty()) {
                    HashMap<String, MutableSet<String>>(targetPersonSet.size)
                } else {
                    null
                }
                for (face in enrich.faces) {
                    val pid = face.personId ?: continue
                    val p = personById[pid] ?: continue
                    if (p.isHidden) continue
                    if (photosBySelectedPerson != null && pid in targetPersonSet) {
                        photosBySelectedPerson.getOrPut(pid) { HashSet() }.add(face.itemId)
                    }
                    if (p.name.isNotBlank()) {
                        personNamesByPhoto.getOrPut(face.itemId) { mutableListOf() }.add(p.name)
                    }
                }
                val selectedPersonPhotoIds: Set<String>? = if (photosBySelectedPerson != null) {
                    if (photosBySelectedPerson.size < targetPersonSet.size) {
                        emptySet()
                    } else {
                        var acc: Set<String>? = null
                        for (ids in photosBySelectedPerson.values) {
                            acc = if (acc == null) ids else acc.intersect(ids)
                        }
                        acc.orEmpty()
                    }
                } else {
                    null
                }
                (localItems + driveItems).filter { item ->
                    if (item.parentId in enrich.excludedIds) return@filter false
                    if (selectedPersonPhotoIds != null && item.id !in selectedPersonPhotoIds) return@filter false
                    if (launchContext.mediaTypeFilter != null && item.mediaType != launchContext.mediaTypeFilter) {
                        return@filter false
                    }
                    if (launchContext.fromEpochMs != null && item.createdTimeEpochMs < launchContext.fromEpochMs) {
                        return@filter false
                    }
                    if (launchContext.toEpochMs != null && item.createdTimeEpochMs > launchContext.toEpochMs) {
                        return@filter false
                    }
                    val ocr = enrich.ocrById[item.id]
                    if (launchContext.onlyWithText && (ocr == null || !ocr.hasText)) {
                        return@filter false
                    }
                    if (tokens.isEmpty()) return@filter true
                    val loc = enrich.locationsById[item.id]
                    val ann = enrich.annotationsById[item.id]
                    val smart = enrich.smartById[item.id]
                    val pNames = personNamesByPhoto[item.id]
                    tokens.all { token ->
                        (pNames != null && pNames.any { it.contains(token, ignoreCase = true) }) ||
                            smart?.labelsCsv?.contains(token, ignoreCase = true) == true ||
                            smart?.labelSearchTokens?.contains(token, ignoreCase = true) == true ||
                            ((smart?.faceCount ?: 0) > 0 && (token == "people" || token == "person" || token == "personen" || token == "mensen" || token == "face")) ||
                            item.name.contains(token, ignoreCase = true) ||
                            item.relativePath?.contains(token, ignoreCase = true) == true ||
                            item.storagePath?.contains(token, ignoreCase = true) == true ||
                            item.monthYearLabel.contains(token, ignoreCase = true) ||
                            item.daySectionLabel.contains(token, ignoreCase = true) ||
                            item.exif.cameraMake?.contains(token, ignoreCase = true) == true ||
                            item.exif.cameraModel?.contains(token, ignoreCase = true) == true ||
                            item.exif.lens?.contains(token, ignoreCase = true) == true ||
                            loc?.city?.contains(token, ignoreCase = true) == true ||
                            loc?.country?.contains(token, ignoreCase = true) == true ||
                            ann?.tagsCsv?.contains(token, ignoreCase = true) == true ||
                            ann?.note?.contains(token, ignoreCase = true) == true ||
                            ocr?.extractedText?.contains(token, ignoreCase = true) == true ||
                            (token == "video" && item.isVideo) ||
                            (token == "raw" && item.isRaw) ||
                            (token == "drive" && !item.isLocal)
                    }
                }.sortedByDescending { it.createdTimeEpochMs }
            }.flowOn(Dispatchers.Default)
        }

        launchContext.folderId == LocalMediaRepository.LOCAL_BUCKET_ALL -> combine(
            localMediaRepository.observeLocalAlbumMedia(LocalMediaRepository.LOCAL_BUCKET_ALL),
            excludedAlbumIdsFlow
        ) { items, excluded ->
            items.filterNot { it.parentId in excluded }
        }.flowOn(Dispatchers.Default)

        LocalMediaRepository.isLocalFolderId(launchContext.folderId) -> combine(
            localMediaRepository.observeLocalAlbumMedia(launchContext.folderId),
            extrasRepository.favoriteItemIdsFlow
        ) { items, favIds ->
            applyAlbumFilterAndSort(items, favIds, launchContext.albumFilter, launchContext.albumSort)
        }.flowOn(Dispatchers.Default)

        launchContext.isFlattened -> combine(
            repository.observeFlattenedMedia(launchContext.folderId),
            extrasRepository.favoriteItemIdsFlow,
            excludedAlbumIdsFlow
        ) { items, favIds, excluded ->
            // Allow items inside the explicitly opened folderId, but never leak locked/hidden subfolders
            val blocked = excluded - launchContext.folderId
            val visible = items.filterNot { it.parentId in blocked }
            applyAlbumFilterAndSort(visible, favIds, launchContext.albumFilter, launchContext.albumSort)
        }.flowOn(Dispatchers.Default)

        else -> combine(
            repository.observeMediaInFolder(launchContext.folderId),
            extrasRepository.favoriteItemIdsFlow
        ) { items, favIds ->
            applyAlbumFilterAndSort(items, favIds, launchContext.albumFilter, launchContext.albumSort)
        }.flowOn(Dispatchers.Default)
    }

    private val dialogAndActionsFlow = combine(
        pendingShareFlow,
        pendingTrashFlow,
        pendingFavoriteFlow,
        snackbarFlow
    ) { share, trash, favSender, snack ->
        ViewerDialogEvents(share, trash, favSender, snack)
    }

    private val overlayFlow = combine(
        isExportingFlow,
        activePathFlow,
        activeExifItemFlow,
        showExifFlow,
        dialogAndActionsFlow
    ) { exporting, path, exifItem, showExif, events ->
        ViewerOverlay(
            isExporting = exporting,
            activeDrivePath = path,
            activeExifItem = exifItem,
            showExifSheet = showExif,
            pendingShare = events.pendingShare,
            pendingTrash = events.pendingTrash,
            pendingFavorite = events.pendingFavorite,
            snackbarMessage = events.snackbarMessage
        )
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
            hasLoadedOnce = true,
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
            pendingFavoriteIntentSender = overlay.pendingFavorite,
            snackbarMessage = overlay.snackbarMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = buildInstantInitialState(networkMonitor)
    )

    private fun applyAlbumFilterAndSort(
        items: List<MediaItem>,
        favIds: Set<String>,
        filter: AlbumMediaFilter,
        sort: AlbumMediaSort
    ): List<MediaItem> {
        val filtered = when (filter) {
            AlbumMediaFilter.ALL -> items
            AlbumMediaFilter.IMAGES -> items.filter { !it.isVideo && !it.isRaw }
            AlbumMediaFilter.VIDEOS -> items.filter { it.isVideo }
            AlbumMediaFilter.RAW -> items.filter { it.isRaw }
            AlbumMediaFilter.FAVORITES -> items.filter { it.id in favIds }
        }
        return when (sort) {
            AlbumMediaSort.DATE_DESC -> filtered.sortedByDescending { it.createdTimeEpochMs }
            AlbumMediaSort.DATE_ASC -> filtered.sortedBy { it.createdTimeEpochMs }
            AlbumMediaSort.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            AlbumMediaSort.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
        }
    }

    /**
     * Populates frame 0 synchronously from in-memory caches (or the tapped grid item) so opening a
     * photo expands the real image immediately out of its thumbnail with zero black spinner flash.
     */
    private fun buildInstantInitialState(networkMonitor: NetworkMonitor): ViewerUiState {
        val prefs = preferencesManager.latestPreferences
        val net = networkMonitor.networkState.value
        val excluded = repository.expandBlockedFolderIds(
            extrasRepository.latestLockedAlbumIds + extrasRepository.latestHiddenAlbumIds
        )
        val cached = when {
            launchContext.personIds.isNotEmpty() || !launchContext.locationKey.isNullOrBlank() -> emptyList()
            launchContext.specialFilter == SpecialGalleryFilter.VIDEOS ->
                localMediaRepository.getCachedAlbumMedia(LocalMediaRepository.LOCAL_BUCKET_VIDEOS)
                    .filterNot { it.parentId in excluded }
            launchContext.specialFilter == SpecialGalleryFilter.RECENT ->
                localMediaRepository.getCachedAlbumMedia(LocalMediaRepository.LOCAL_BUCKET_ALL)
                    .filterNot { it.parentId in excluded }
                    .take(300)
            launchContext.specialFilter != null || launchContext.isSearchMode -> emptyList()
            launchContext.folderId == LocalMediaRepository.LOCAL_BUCKET_ALL ->
                localMediaRepository.getCachedAlbumMedia(LocalMediaRepository.LOCAL_BUCKET_ALL)
                    .filterNot { it.parentId in excluded }
            LocalMediaRepository.isLocalFolderId(launchContext.folderId) ->
                applyAlbumFilterAndSort(
                    localMediaRepository.getCachedAlbumMedia(launchContext.folderId),
                    extrasRepository.latestFavoriteItemIds,
                    launchContext.albumFilter,
                    launchContext.albumSort
                )
            !launchContext.isFlattened && launchContext.folderId.isNotBlank() ->
                applyAlbumFilterAndSort(
                    repository.getCachedFolderMedia(launchContext.folderId).orEmpty(),
                    extrasRepository.latestFavoriteItemIds,
                    launchContext.albumFilter,
                    launchContext.albumSort
                )
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
            hasLoadedOnce = false,
            favoriteItemIds = extrasRepository.latestFavoriteItemIds,
            isUnmeteredWifi = net.isUnmeteredWifi || !prefs.wifiOnlyHighRes,
            isOnline = net.isOnline,
            preferences = prefs
        )
    }

    fun toggleFavorite(item: MediaItem) {
        val currentlyFav = item.id in uiState.value.favoriteItemIds
        viewModelScope.launch {
            val sender = extrasRepository.toggleFavoriteItem(item.id, currentlyFav, item)
            if (sender != null) {
                pendingFavoriteFlow.value = sender
            }
        }
    }

    fun onFavoriteRequestCompleted(approved: Boolean) {
        pendingFavoriteFlow.value = null
        if (approved) {
            localMediaRepository.notifyPermissionChanged()
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
        val isHiddenFile = com.luminagallery.drive.data.local.HiddenLockedFolderManager
            .isHiddenFileUri(item.localContentUri)
        val supportsTrash = localMediaRepository.supportsMediaStoreTrash()
        if (isHiddenFile || localMediaRepository.canDeleteDirectlyWithoutDialog()) {
            viewModelScope.launch {
                localMediaRepository.trashLocalItemsDirectly(listOf(item), toTrash = true)
                    .onSuccess {
                        localMediaRepository.notifyPermissionChanged()
                        snackbarFlow.value = when {
                            isHiddenFile -> "Deleted permanently"
                            supportsTrash -> "Moved to trash"
                            else -> "Deleted from device"
                        }
                    }
                    .onFailure { err ->
                        if (isHiddenFile || !supportsTrash) {
                            snackbarFlow.value = err.localizedMessage ?: "Could not delete file"
                        } else {
                            val sender = localMediaRepository.buildTrashIntentSender(listOf(item))
                            if (sender != null) {
                                pendingTrashFlow.value = sender
                            } else {
                                snackbarFlow.value = "Could not move item to trash"
                            }
                        }
                    }
            }
            return
        }
        val sender = localMediaRepository.buildTrashIntentSender(listOf(item))
        if (sender != null) {
            pendingTrashFlow.value = sender
        } else {
            // Android 10 fallback: attempt direct delete via MediaStore
            viewModelScope.launch {
                localMediaRepository.deleteLocalItemsPermanentlyDirectly(listOf(item))
                    .onSuccess {
                        localMediaRepository.notifyPermissionChanged()
                        snackbarFlow.value = "Deleted from device"
                    }
                    .onFailure { err ->
                        snackbarFlow.value = err.localizedMessage ?: "Unable to delete file"
                    }
            }
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

    private data class SearchViewerEnrichment(
        val locationsById: Map<String, com.luminagallery.drive.data.local.entity.PhotoLocationEntity>,
        val annotationsById: Map<String, com.luminagallery.drive.data.local.entity.MediaAnnotationEntity>,
        val ocrById: Map<String, com.luminagallery.drive.data.local.entity.PhotoOcrEntity>,
        val excludedIds: Set<String>,
        val persons: List<com.luminagallery.drive.data.local.entity.PersonClusterEntity>,
        val faces: List<com.luminagallery.drive.data.local.entity.DetectedFaceEntity>,
        val smartById: Map<String, com.luminagallery.drive.data.local.entity.PhotoSmartIndexEntity>
    )

    private data class ViewerDialogEvents(
        val pendingShare: Pair<List<Uri>, String>?,
        val pendingTrash: IntentSender?,
        val pendingFavorite: IntentSender?,
        val snackbarMessage: String?
    )

    private data class ViewerOverlay(
        val isExporting: Boolean,
        val activeDrivePath: String,
        val activeExifItem: MediaItem?,
        val showExifSheet: Boolean,
        val pendingShare: Pair<List<Uri>, String>?,
        val pendingTrash: IntentSender?,
        val pendingFavorite: IntentSender?,
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
            smartRecognitionManager = container.smartRecognitionManager,
            preferencesManager = container.preferencesManager,
            exportMediaUseCase = container.exportMediaUseCase,
            networkMonitor = container.networkMonitor,
            initialTappedItem = initialTappedItem
        ) as T
    }
}

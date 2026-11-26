package com.luminagallery.drive.ui.screens.people

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.luminagallery.drive.AppContainer
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.local.entity.DetectedFaceEntity
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.data.repository.ai.FaceClusteringEngine
import com.luminagallery.drive.data.repository.ai.PersonMergeSuggestionUi
import com.luminagallery.drive.data.repository.ai.PersonSummary
import com.luminagallery.drive.data.repository.ai.SmartIndexProgress
import com.luminagallery.drive.data.repository.ai.SmartRecognitionManager
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.usecase.ExportMediaUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class PeopleUiState(
    val visiblePersons: List<PersonSummary> = emptyList(),
    val hiddenPersons: List<PersonSummary> = emptyList(),
    val mergeSuggestions: List<PersonMergeSuggestionUi> = emptyList(),
    val showHiddenSection: Boolean = false,
    val selectedPersonIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val progress: SmartIndexProgress = SmartIndexProgress(),
    val snackbarMessage: String? = null
)

class PeopleViewModel(
    private val repository: GalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val smartRecognitionManager: SmartRecognitionManager
) : ViewModel() {

    private val showHiddenFlow = MutableStateFlow(false)
    private val selectedIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val selectionModeActiveFlow = MutableStateFlow(false)
    private val snackbarFlow = MutableStateFlow<String?>(null)

    private val visibleMediaIdsFlow = combine(
        localMediaRepository.observeAllLocalMedia(),
        repository.searchMedia("", null, null, null),
        extrasRepository.excludedAlbumIdsFlow
    ) { local, drive, excluded ->
        val blocked = repository.expandBlockedFolderIds(excluded)
        (local + drive)
            .asSequence()
            .filterNot { it.parentId in blocked }
            .mapTo(HashSet()) { it.id }
    }.flowOn(Dispatchers.Default)

    private val allPersonsWithHiddenFlow = smartRecognitionManager.observeVisiblePersons(
        visibleItemIdsFlow = visibleMediaIdsFlow,
        includeHidden = true
    )

    private val mergeSuggestionsFlow = combine(
        allPersonsWithHiddenFlow,
        smartRecognitionManager.allPersonsFlow,
        smartRecognitionManager.rejectedMergePairsFlow
    ) { summaries, rawEntities, rejectedPairs ->
        val visible = summaries.filterNot { it.isHidden }
        if (visible.size < 2) return@combine emptyList()
        val summaryById = visible.associateBy { it.personId }
        val centroidById = rawEntities.associate {
            it.personId to FaceClusteringEngine.decodeEmbedding(it.centroidBlob)
        }
        val candidates = visible.mapNotNull { s ->
            val centroid = centroidById[s.personId] ?: return@mapNotNull null
            if (centroid.isEmpty()) return@mapNotNull null
            FaceClusteringEngine.ClusterCandidate(
                personId = s.personId,
                centroid = centroid,
                sampleCount = s.photoCount,
                isNamed = s.isNamed,
                photoIds = s.photoIds
            )
        }
        FaceClusteringEngine.findInteractiveMergeSuggestions(
            clusters = candidates,
            rejectedPairs = rejectedPairs,
            maxSuggestions = 8
        ).mapNotNull { m ->
            val target = summaryById[m.targetPersonId] ?: return@mapNotNull null
            val source = summaryById[m.sourcePersonId] ?: return@mapNotNull null
            PersonMergeSuggestionUi(target = target, source = source, similarity = m.similarity)
        }
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<PeopleUiState> = combine(
        combine(allPersonsWithHiddenFlow, mergeSuggestionsFlow) { persons, suggs -> persons to suggs },
        showHiddenFlow,
        combine(selectedIdsFlow, selectionModeActiveFlow) { ids, active -> ids to active },
        smartRecognitionManager.progress,
        snackbarFlow
    ) { (allPersons, suggestions), showHidden, (selectedIds, modeActive), progress, snackbar ->
        val visible = allPersons.filterNot { it.isHidden }
        val hidden = allPersons.filter { it.isHidden }
        PeopleUiState(
            visiblePersons = visible,
            hiddenPersons = hidden,
            mergeSuggestions = suggestions,
            showHiddenSection = showHidden,
            selectedPersonIds = selectedIds,
            isSelectionMode = modeActive || selectedIds.isNotEmpty(),
            progress = progress,
            snackbarMessage = snackbar
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PeopleUiState()
    )

    init {
        triggerScan(maxNewItems = 140)
    }

    fun triggerScan(maxNewItems: Int = 600) {
        viewModelScope.launch(Dispatchers.IO) {
            smartRecognitionManager.syncSmartIndex(
                maxNewItemsPerRun = maxNewItems,
                includeDrive = true,
                respectWifiOnlyForDrive = true
            )
        }
    }

    fun scanEntireLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            smartRecognitionManager.syncSmartIndex(
                maxNewItemsPerRun = Int.MAX_VALUE,
                includeDrive = true,
                respectWifiOnlyForDrive = true
            )
        }
    }

    fun pauseScan() {
        smartRecognitionManager.pauseActiveScan()
    }

    fun rebuildAllSmartIndex() {
        viewModelScope.launch(Dispatchers.IO) {
            clearSelection()
            snackbarFlow.value = "Rebuilding People & Objects (keeping saved names)…"
            smartRecognitionManager.resetAllIndexData(preserveNames = true)
            smartRecognitionManager.syncSmartIndex(
                maxNewItemsPerRun = Int.MAX_VALUE,
                includeDrive = true,
                respectWifiOnlyForDrive = true
            )
        }
    }

    fun acceptMergeSuggestion(suggestion: PersonMergeSuggestionUi) {
        viewModelScope.launch {
            smartRecognitionManager.mergePersons(
                targetPersonId = suggestion.target.personId,
                sourcePersonIds = listOf(suggestion.source.personId)
            )
            val label = suggestion.target.name.ifBlank { suggestion.source.name }.ifBlank { "1 person" }
            snackbarFlow.value = "Combined into $label"
        }
    }

    fun dismissMergeSuggestion(suggestion: PersonMergeSuggestionUi) {
        smartRecognitionManager.dismissMergeSuggestion(
            suggestion.target.personId,
            suggestion.source.personId
        )
    }

    fun toggleShowHidden() {
        showHiddenFlow.update { !it }
    }

    fun enterSelectionMode(initialPersonId: String? = null) {
        selectionModeActiveFlow.value = true
        if (initialPersonId != null) {
            selectedIdsFlow.update { it + initialPersonId }
        }
    }

    fun togglePersonSelected(personId: String) {
        selectedIdsFlow.update { cur ->
            val next = if (personId in cur) cur - personId else cur + personId
            if (next.isEmpty()) selectionModeActiveFlow.value = false
            next
        }
    }

    fun clearSelection() {
        selectedIdsFlow.value = emptySet()
        selectionModeActiveFlow.value = false
    }

    fun renamePerson(personId: String, newName: String, mergeIfDuplicate: Boolean = false) {
        viewModelScope.launch {
            val clean = newName.trim()
            if (mergeIfDuplicate && clean.isNotEmpty()) {
                val existing = smartRecognitionManager.findExistingPersonByName(clean, excludePersonId = personId)
                if (existing != null) {
                    smartRecognitionManager.mergePersons(
                        targetPersonId = existing.personId,
                        sourcePersonIds = listOf(personId),
                        overrideName = existing.name
                    )
                    clearSelection()
                    snackbarFlow.value = "Combined with ${existing.name}"
                    return@launch
                }
            }
            smartRecognitionManager.renamePerson(personId, clean)
            clearSelection()
            snackbarFlow.value = if (clean.isNotEmpty()) "Named \"$clean\"" else "Name cleared"
        }
    }

    suspend fun findDuplicateNameTarget(name: String, excludePersonId: String): String? =
        smartRecognitionManager.findExistingPersonByName(name, excludePersonId)?.name

    fun combineSelectedPersons(combinedName: String) {
        val selected = selectedIdsFlow.value.toList()
        if (selected.size < 2) return
        viewModelScope.launch {
            val all = uiState.value.visiblePersons + uiState.value.hiddenPersons
            val selectedSummaries = selected.mapNotNull { id -> all.firstOrNull { it.personId == id } }
            val primary = selectedSummaries
                .sortedWith(compareByDescending<PersonSummary> { it.isNamed }.thenByDescending { it.photoCount })
                .firstOrNull() ?: return@launch
            val others = selected.filter { it != primary.personId }
            smartRecognitionManager.mergePersons(
                targetPersonId = primary.personId,
                sourcePersonIds = others,
                overrideName = combinedName.trim().takeIf { it.isNotEmpty() }
            )
            clearSelection()
            val label = combinedName.trim().ifBlank { primary.name }.ifBlank { "person" }
            snackbarFlow.value = "Combined ${selected.size} groups into $label"
        }
    }

    fun setSelectedHidden(hidden: Boolean) {
        val ids = selectedIdsFlow.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { smartRecognitionManager.setPersonHidden(it, hidden) }
            clearSelection()
            snackbarFlow.value = if (hidden) {
                if (ids.size == 1) "Hidden 1 person" else "Hidden ${ids.size} people"
            } else {
                if (ids.size == 1) "Restored 1 person" else "Restored ${ids.size} people"
            }
        }
    }

    fun clearSnackbar() {
        snackbarFlow.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PeopleViewModel(
            repository = container.galleryRepository,
            localMediaRepository = container.localMediaRepository,
            extrasRepository = container.galleryExtrasRepository,
            smartRecognitionManager = container.smartRecognitionManager
        ) as T
    }
}

@Immutable
data class PersonDetailUiState(
    val person: PersonSummary? = null,
    val photos: List<MediaItem> = emptyList(),
    val personFaces: List<DetectedFaceEntity> = emptyList(),
    val otherPersons: List<PersonSummary> = emptyList(),
    val frequentCompanions: List<PersonSummary> = emptyList(),
    val selectedCompanion: PersonSummary? = null,
    val gridColumns: Int = 4,
    val isUnmeteredWifi: Boolean = true,
    val selectedIds: Set<String> = emptySet(),
    val isExporting: Boolean = false,
    val exportProgressText: String? = null,
    val infoItem: MediaItem? = null,
    val infoFolderPath: String = "Google Drive",
    val pendingShareUris: List<Uri>? = null,
    val snackbarMessage: String? = null,
    val personRemovedOrMerged: Boolean = false
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

class PersonDetailViewModel(
    private val personId: String,
    private val repository: GalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val smartRecognitionManager: SmartRecognitionManager,
    private val preferencesManager: PreferencesManager,
    private val exportMediaUseCase: ExportMediaUseCase,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    private val selectedIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val selectedCompanionIdFlow = MutableStateFlow<String?>(null)
    private val isExportingFlow = MutableStateFlow(false)
    private val exportProgressFlow = MutableStateFlow<String?>(null)
    private val infoSheetFlow = MutableStateFlow<Pair<MediaItem, String>?>(null)
    private val pendingShareFlow = MutableStateFlow<List<Uri>?>(null)
    private val snackbarFlow = MutableStateFlow<String?>(null)
    private val mergedAwayFlow = MutableStateFlow(false)

    private val visibleMediaFlow = combine(
        localMediaRepository.observeAllLocalMedia(),
        repository.searchMedia("", null, null, null),
        extrasRepository.excludedAlbumIdsFlow
    ) { local, drive, excluded ->
        val blocked = repository.expandBlockedFolderIds(excluded)
        (local + drive)
            .filterNot { it.parentId in blocked }
            .sortedByDescending { it.createdTimeEpochMs }
    }.flowOn(Dispatchers.Default)

    private val visibleMediaIdsFlow = visibleMediaFlow
        .map { list -> list.mapTo(HashSet(list.size)) { it.id } }
        .flowOn(Dispatchers.Default)

    private val allVisiblePersonsFlow = smartRecognitionManager.observeVisiblePersons(
        visibleItemIdsFlow = visibleMediaIdsFlow,
        includeHidden = true
    )

    private val personContentFlow = combine(
        allVisiblePersonsFlow,
        visibleMediaFlow,
        smartRecognitionManager.observeFacesForPerson(personId),
        selectedCompanionIdFlow
    ) { allPersons, allMedia, faces, companionId ->
        val current = allPersons.firstOrNull { it.personId == personId }
        val others = allPersons.filter { it.personId != personId && !it.isHidden }
        val visibleIds = allMedia.mapTo(HashSet(allMedia.size)) { it.id }
        val safeFaces = faces.filter { it.itemId in visibleIds && !it.cropPath.isNullOrBlank() }

        val companions = if (current != null) {
            others.mapNotNull { other ->
                val shared = current.photoIds.intersect(other.photoIds)
                if (shared.isEmpty()) null else other.copy(photoCount = shared.size, photoIds = shared)
            }.sortedByDescending { it.photoCount }.take(12)
        } else {
            emptyList()
        }
        val activeCompanion = companionId?.let { cid -> companions.firstOrNull { it.personId == cid } }

        val personPhotos = if (current != null) {
            val targetIds = if (activeCompanion != null) activeCompanion.photoIds else current.photoIds
            allMedia.filter { it.id in targetIds }
        } else {
            emptyList()
        }
        PersonDetailContent(
            person = current,
            photos = personPhotos,
            faces = safeFaces,
            otherPersons = others,
            frequentCompanions = companions,
            selectedCompanion = activeCompanion
        )
    }.flowOn(Dispatchers.Default)

    private val interactionFlow = combine(
        selectedIdsFlow,
        isExportingFlow,
        exportProgressFlow,
        infoSheetFlow,
        combine(pendingShareFlow, snackbarFlow, mergedAwayFlow) { share, snack, merged ->
            Triple(share, snack, merged)
        }
    ) { sel, exp, prog, info, (share, snack, merged) ->
        PersonDetailInteraction(sel, exp, prog, info, share, snack, merged)
    }

    val uiState: StateFlow<PersonDetailUiState> = combine(
        personContentFlow,
        preferencesManager.preferencesFlow,
        networkMonitor.networkState,
        interactionFlow
    ) { content, prefs, net, inter ->
        PersonDetailUiState(
            person = content.person,
            photos = content.photos,
            personFaces = content.faces,
            otherPersons = content.otherPersons,
            frequentCompanions = content.frequentCompanions,
            selectedCompanion = content.selectedCompanion,
            gridColumns = prefs.gridColumns,
            isUnmeteredWifi = net.isUnmeteredWifi || !prefs.wifiOnlyHighRes,
            selectedIds = inter.selectedIds,
            isExporting = inter.isExporting,
            exportProgressText = inter.exportProgressText,
            infoItem = inter.info?.first,
            infoFolderPath = inter.info?.second ?: "Google Drive",
            pendingShareUris = inter.pendingShareUris,
            snackbarMessage = inter.snackbarMessage,
            personRemovedOrMerged = inter.mergedAway
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PersonDetailUiState()
    )

    fun setGridColumns(columns: Int) {
        viewModelScope.launch { preferencesManager.setGridColumns(columns) }
    }

    fun toggleCompanionFilter(companionId: String) {
        selectedCompanionIdFlow.update { cur -> if (cur == companionId) null else companionId }
        selectedIdsFlow.value = emptySet()
    }

    fun clearCompanionFilter() {
        selectedCompanionIdFlow.value = null
    }

    suspend fun findDuplicateNameTarget(name: String): String? =
        smartRecognitionManager.findExistingPersonByName(name, excludePersonId = personId)?.name

    fun renamePerson(newName: String, mergeIfDuplicate: Boolean = false) {
        viewModelScope.launch {
            val clean = newName.trim()
            if (mergeIfDuplicate && clean.isNotEmpty()) {
                val existing = smartRecognitionManager.findExistingPersonByName(clean, excludePersonId = personId)
                if (existing != null) {
                    smartRecognitionManager.mergePersons(
                        targetPersonId = existing.personId,
                        sourcePersonIds = listOf(personId),
                        overrideName = existing.name
                    )
                    snackbarFlow.value = "Combined with ${existing.name}"
                    mergedAwayFlow.value = true
                    return@launch
                }
            }
            smartRecognitionManager.renamePerson(personId, clean)
            snackbarFlow.value = if (clean.isNotEmpty()) "Named \"$clean\"" else "Name cleared"
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch { smartRecognitionManager.togglePersonFavorite(personId) }
    }

    fun setHidden(hidden: Boolean) {
        viewModelScope.launch {
            smartRecognitionManager.setPersonHidden(personId, hidden)
            snackbarFlow.value = if (hidden) "Person hidden from People" else "Person restored"
        }
    }

    fun setCoverFace(faceId: String) {
        viewModelScope.launch {
            smartRecognitionManager.setPersonCoverFace(personId, faceId)
            snackbarFlow.value = "Updated profile photo"
        }
    }

    fun combineWithPersons(otherPersonIds: List<String>) {
        if (otherPersonIds.isEmpty()) return
        viewModelScope.launch {
            smartRecognitionManager.mergePersons(
                targetPersonId = personId,
                sourcePersonIds = otherPersonIds
            )
            snackbarFlow.value = if (otherPersonIds.size == 1) {
                "Combined 1 group into this person"
            } else {
                "Combined ${otherPersonIds.size} groups into this person"
            }
        }
    }

    fun removeSelectedFromPerson() {
        val ids = selectedIdsFlow.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            smartRecognitionManager.removePhotosFromPerson(personId, ids)
            selectedIdsFlow.value = emptySet()
            snackbarFlow.value = if (ids.size == 1) {
                "Removed 1 photo from this person"
            } else {
                "Removed ${ids.size} photos from this person"
            }
        }
    }

    fun toggleSelection(itemId: String) {
        selectedIdsFlow.update { cur -> if (itemId in cur) cur - itemId else cur + itemId }
    }

    fun setItemSelected(itemId: String, selected: Boolean) {
        selectedIdsFlow.update { cur -> if (selected) cur + itemId else cur - itemId }
    }

    fun setItemsSelectedRange(ids: Set<String>, selected: Boolean) {
        if (ids.isEmpty()) return
        selectedIdsFlow.update { cur -> if (selected) cur + ids else cur - ids }
    }

    fun clearSelection() {
        selectedIdsFlow.value = emptySet()
    }

    fun shareSelected() {
        val selected = uiState.value.photos.filter { it.id in selectedIdsFlow.value }
        if (selected.isEmpty() || isExportingFlow.value) return
        viewModelScope.launch {
            isExportingFlow.value = true
            exportProgressFlow.value = "Preparing ${selected.size} file(s)…"
            exportMediaUseCase.prepareShareUris(selected) { done, total ->
                exportProgressFlow.value = "Downloading $done / $total"
            }.onSuccess { uris ->
                pendingShareFlow.value = uris
                selectedIdsFlow.value = emptySet()
            }.onFailure { err ->
                snackbarFlow.value = err.message ?: "Failed to prepare share files"
            }
            isExportingFlow.value = false
            exportProgressFlow.value = null
        }
    }

    fun saveSelectedToDevice() {
        val selected = uiState.value.photos.filter { it.id in selectedIdsFlow.value }
        if (selected.isEmpty() || isExportingFlow.value) return
        viewModelScope.launch {
            isExportingFlow.value = true
            exportProgressFlow.value = "Saving ${selected.size} file(s)…"
            exportMediaUseCase.saveToDevice(selected) { done, total ->
                exportProgressFlow.value = "Saving $done / $total"
            }.onSuccess { count ->
                snackbarFlow.value = "Saved $count file(s) to Gallery"
                selectedIdsFlow.value = emptySet()
            }.onFailure { err ->
                snackbarFlow.value = err.message ?: "Save failed"
            }
            isExportingFlow.value = false
            exportProgressFlow.value = null
        }
    }

    fun openSelectedInfo() {
        val firstId = selectedIdsFlow.value.firstOrNull() ?: return
        val item = uiState.value.photos.firstOrNull { it.id == firstId } ?: return
        viewModelScope.launch {
            val path = if (item.isLocal) {
                item.storagePath ?: item.relativePath ?: "On device"
            } else {
                repository.getFolderPathString(item.parentId)
            }
            infoSheetFlow.value = item to path
        }
    }

    fun closeInfoSheet() {
        infoSheetFlow.value = null
    }

    fun consumePendingShare() {
        pendingShareFlow.value = null
    }

    fun showMessage(msg: String) {
        snackbarFlow.value = msg
    }

    fun clearSnackbar() {
        snackbarFlow.value = null
    }

    private data class PersonDetailContent(
        val person: PersonSummary?,
        val photos: List<MediaItem>,
        val faces: List<DetectedFaceEntity>,
        val otherPersons: List<PersonSummary>,
        val frequentCompanions: List<PersonSummary>,
        val selectedCompanion: PersonSummary?
    )

    private data class PersonDetailInteraction(
        val selectedIds: Set<String>,
        val isExporting: Boolean,
        val exportProgressText: String?,
        val info: Pair<MediaItem, String>?,
        val pendingShareUris: List<Uri>?,
        val snackbarMessage: String?,
        val mergedAway: Boolean
    )

    class Factory(
        private val personId: String,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PersonDetailViewModel(
            personId = personId,
            repository = container.galleryRepository,
            localMediaRepository = container.localMediaRepository,
            extrasRepository = container.galleryExtrasRepository,
            smartRecognitionManager = container.smartRecognitionManager,
            preferencesManager = container.preferencesManager,
            exportMediaUseCase = container.exportMediaUseCase,
            networkMonitor = container.networkMonitor
        ) as T
    }
}

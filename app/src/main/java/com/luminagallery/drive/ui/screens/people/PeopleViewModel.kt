package com.luminagallery.drive.ui.screens.people

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.luminagallery.drive.AppContainer
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.local.dao.LightFaceRow
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.data.repository.ai.FaceClusteringEngine
import com.luminagallery.drive.data.repository.ai.PersonMergeSuggestionUi
import com.luminagallery.drive.data.repository.ai.PersonSummary
import com.luminagallery.drive.data.repository.ai.SmartIndexProgress
import com.luminagallery.drive.data.repository.ai.SmartRecognitionManager
import com.luminagallery.drive.data.repository.sync.SmartScanScheduler
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.usecase.ExportMediaUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
    private val appContext: Context,
    private val repository: GalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val smartRecognitionManager: SmartRecognitionManager,
    private val networkMonitor: NetworkMonitor
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
        val entityById = rawEntities.associateBy { it.personId }
        val candidates = visible.mapNotNull { s ->
            val entity = entityById[s.personId] ?: return@mapNotNull null
            val centroid = FaceClusteringEngine.decodeEmbedding(entity.centroidBlob)
            if (centroid.isEmpty()) return@mapNotNull null
            FaceClusteringEngine.ClusterCandidate(
                personId = s.personId,
                centroid = centroid,
                sampleCount = s.photoCount,
                isNamed = s.isNamed,
                photoIds = s.photoIds,
                memoryId = entity.memoryId,
                entityKind = entity.entityKind
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
    }.flowOn(Dispatchers.Default).onStart { emit(emptyList()) }

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
        initialValue = buildInstantInitialState()
    )

    private fun buildInstantInitialState(): PeopleUiState {
        val blocked = repository.expandBlockedFolderIds(
            extrasRepository.latestLockedAlbumIds + extrasRepository.latestHiddenAlbumIds
        )
        val local = localMediaRepository.getCachedAllMedia()
        val drive = repository.getCachedAllMedia()
        val visibleIds = HashSet<String>(local.size + drive.size)
        for (item in local) if (item.parentId !in blocked) visibleIds.add(item.id)
        for (item in drive) if (item.parentId !in blocked) visibleIds.add(item.id)
        val allPersons = smartRecognitionManager.computeVisiblePersonsSync(
            visibleIds = visibleIds,
            includeHidden = true
        )
        return PeopleUiState(
            visiblePersons = allPersons.filterNot { it.isHidden },
            hiddenPersons = allPersons.filter { it.isHidden },
            progress = smartRecognitionManager.progress.value
        )
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Defer incremental AI scan briefly so the entrance transition & avatars paint at 120fps
            delay(800L)
            smartRecognitionManager.syncSmartIndex(
                maxNewItemsPerRun = 140,
                includeDrive = true,
                respectWifiOnlyForDrive = true
            )
        }
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
        val onWifi = networkMonitor.networkState.value.isUnmeteredWifi
        SmartScanScheduler.startBackgroundLibraryScan(appContext, wifiOnly = onWifi)
        snackbarFlow.value = if (onWifi) {
            "Scanning in background on Wi-Fi · safe to lock screen"
        } else {
            "Scanning local photos in background · connect to Wi-Fi for Drive"
        }
    }

    fun pauseScan() {
        smartRecognitionManager.pauseActiveScan()
        SmartScanScheduler.cancelActiveScan(appContext)
    }

    fun rebuildAllSmartIndex() {
        viewModelScope.launch(Dispatchers.IO) {
            clearSelection()
            smartRecognitionManager.pauseActiveScan()
            SmartScanScheduler.cancelActiveScan(appContext)
            snackbarFlow.value = "Rebuilding People & Objects in background (keeping saved names & combines)…"
            smartRecognitionManager.resetAllIndexData(preserveNames = true)
            val onWifi = networkMonitor.networkState.value.isUnmeteredWifi
            SmartScanScheduler.startBackgroundLibraryScan(appContext, wifiOnly = onWifi)
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

    fun combineTwoPersons(
        draggedPersonId: String,
        targetPersonId: String,
        combinedName: String
    ) {
        if (draggedPersonId == targetPersonId) return
        viewModelScope.launch {
            val all = uiState.value.visiblePersons + uiState.value.hiddenPersons
            val dragged = all.firstOrNull { it.personId == draggedPersonId } ?: return@launch
            val target = all.firstOrNull { it.personId == targetPersonId } ?: return@launch
            val (primary, secondary) = resolveCombineOrder(dragged = dragged, target = target)
            val cleanName = combinedName.trim()
            smartRecognitionManager.mergePersons(
                targetPersonId = primary.personId,
                sourcePersonIds = listOf(secondary.personId),
                overrideName = cleanName.takeIf { it.isNotEmpty() }
            )
            clearSelection()
            val label = cleanName.ifBlank { primary.name }.ifBlank { "1 person" }
            snackbarFlow.value = "Combined into $label"
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

    /**
     * Synchronously resolves visible (unlocked) full [MediaItem]s for [person] with the person's
     * cover photo placed first so tapping an avatar inside "Same person?" / Combine popups opens
     * the exact full-resolution photo immediately.
     */
    fun getPhotosForPerson(person: PersonSummary): List<MediaItem> {
        val blocked = repository.expandBlockedFolderIds(
            extrasRepository.latestLockedAlbumIds + extrasRepository.latestHiddenAlbumIds
        )
        val localById = localMediaRepository.getCachedMediaById()
        val driveById = repository.getCachedMediaById()
        val items = person.photoIds.mapNotNull { id ->
            val item = localById[id] ?: driveById[id]
            if (item != null && item.parentId !in blocked) item else null
        }.sortedByDescending { it.createdTimeEpochMs }
        val coverId = person.coverPhotoId ?: return items
        val coverIndex = items.indexOfFirst { it.id == coverId }
        if (coverIndex <= 0) return items
        return buildList(items.size) {
            add(items[coverIndex])
            for (i in items.indices) {
                if (i != coverIndex) add(items[i])
            }
        }
    }

    fun clearSnackbar() {
        snackbarFlow.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PeopleViewModel(
            appContext = container.appContext,
            repository = container.galleryRepository,
            localMediaRepository = container.localMediaRepository,
            extrasRepository = container.galleryExtrasRepository,
            smartRecognitionManager = container.smartRecognitionManager,
            networkMonitor = container.networkMonitor
        ) as T
    }

    internal companion object {
        /**
         * Resolves which person stays as the merge target when dragging [dragged] onto [target]:
         *  - Named person always takes priority over an unnamed face.
         *  - When both are named, the target circle dropped onto stays primary.
         *  - When both are unnamed, the cluster with more photos stays primary.
         */
        internal fun resolveCombineOrder(
            dragged: PersonSummary,
            target: PersonSummary
        ): Pair<PersonSummary, PersonSummary> = when {
            target.isNamed && !dragged.isNamed -> target to dragged
            dragged.isNamed && !target.isNamed -> dragged to target
            target.isNamed && dragged.isNamed -> target to dragged
            dragged.photoCount > target.photoCount -> dragged to target
            else -> target to dragged
        }
    }
}

@Immutable
data class PersonDetailUiState(
    val person: PersonSummary? = null,
    val photos: List<MediaItem> = emptyList(),
    val personFaces: List<LightFaceRow> = emptyList(),
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

    private val visibleMediaIndexFlow = combine(
        localMediaRepository.observeAllLocalMedia(),
        repository.searchMedia("", null, null, null),
        extrasRepository.excludedAlbumIdsFlow
    ) { local, drive, excluded ->
        val blocked = repository.expandBlockedFolderIds(excluded)
        val byId = HashMap<String, MediaItem>(local.size + drive.size)
        for (item in local) {
            if (item.parentId !in blocked) byId[item.id] = item
        }
        for (item in drive) {
            if (item.parentId !in blocked) byId[item.id] = item
        }
        byId
    }.flowOn(Dispatchers.Default)

    private val visibleMediaIdsFlow = visibleMediaIndexFlow
        .map { it.keys }

    private val allVisiblePersonsFlow = smartRecognitionManager.observeVisiblePersons(
        visibleItemIdsFlow = visibleMediaIdsFlow,
        includeHidden = true
    )

    private val personContentFlow = combine(
        allVisiblePersonsFlow,
        visibleMediaIndexFlow,
        smartRecognitionManager.observeLightFacesForPerson(personId),
        selectedCompanionIdFlow
    ) { allPersons, mediaById, faces, companionId ->
        val current = allPersons.firstOrNull { it.personId == personId }
        val others = allPersons.filter { it.personId != personId && !it.isHidden }
        val safeFaces = faces.filter { it.itemId in mediaById && !it.cropPath.isNullOrBlank() }

        val companions = if (current != null) {
            others.mapNotNull { other ->
                val shared = current.photoIds.intersect(other.photoIds)
                if (shared.isEmpty()) null else other.copy(photoCount = shared.size, photoIds = shared)
            }.sortedByDescending { it.photoCount }.take(12)
        } else {
            emptyList()
        }
        val activeCompanion = companionId?.let { cid -> companions.firstOrNull { it.personId == cid } }

        // O(K log K) on just this person's photos instead of sorting the entire 20k-item library!
        val personPhotos = if (current != null) {
            val targetIds = if (activeCompanion != null) activeCompanion.photoIds else current.photoIds
            targetIds.mapNotNull { mediaById[it] }
                .sortedByDescending { it.createdTimeEpochMs }
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
        initialValue = buildInstantInitialState(networkMonitor)
    )

    private fun buildInstantInitialState(networkMonitor: NetworkMonitor): PersonDetailUiState {
        val prefs = preferencesManager.latestPreferences
        val net = networkMonitor.networkState.value
        val blocked = repository.expandBlockedFolderIds(
            extrasRepository.latestLockedAlbumIds + extrasRepository.latestHiddenAlbumIds
        )
        val localById = localMediaRepository.getCachedMediaById()
        val driveById = repository.getCachedMediaById()
        val visibleIds = HashSet<String>(localById.size + driveById.size)
        for ((id, item) in localById) if (item.parentId !in blocked) visibleIds.add(id)
        for ((id, item) in driveById) if (item.parentId !in blocked) visibleIds.add(id)
        val allPersons = smartRecognitionManager.computeVisiblePersonsSync(visibleIds, includeHidden = true)
        val current = allPersons.firstOrNull { it.personId == personId }
        val others = allPersons.filter { it.personId != personId && !it.isHidden }
        val companions = if (current != null) {
            others.mapNotNull { other ->
                val shared = current.photoIds.intersect(other.photoIds)
                if (shared.isEmpty()) null else other.copy(photoCount = shared.size, photoIds = shared)
            }.sortedByDescending { it.photoCount }.take(12)
        } else emptyList()
        val photos = if (current != null) {
            current.photoIds.mapNotNull { id ->
                val item = localById[id] ?: driveById[id]
                if (item != null && item.parentId !in blocked) item else null
            }.sortedByDescending { it.createdTimeEpochMs }
        } else emptyList()
        val faces = smartRecognitionManager.getCachedLightFacesForPerson(personId, visibleIds)
        return PersonDetailUiState(
            person = current,
            photos = photos,
            personFaces = faces,
            otherPersons = others,
            frequentCompanions = companions,
            gridColumns = prefs.gridColumns,
            isUnmeteredWifi = net.isUnmeteredWifi || !prefs.wifiOnlyHighRes
        )
    }

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
        val totalBefore = uiState.value.person?.photoIds?.size ?: 0
        viewModelScope.launch {
            smartRecognitionManager.removePhotosFromPerson(personId, ids)
            selectedIdsFlow.value = emptySet()
            val label = if (uiState.value.person?.isPet == true) "pet" else "person"
            snackbarFlow.value = if (ids.size == 1) {
                "Removed 1 photo from this $label"
            } else {
                "Removed ${ids.size} photos from this $label"
            }
            if (totalBefore > 0 && ids.size >= totalBefore) {
                mergedAwayFlow.value = true
            }
        }
    }

    fun reassignSelectedToPerson(targetPersonId: String?, newPersonName: String = "") {
        val ids = selectedIdsFlow.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val result = smartRecognitionManager.reassignPhotosFromPerson(
                sourcePersonId = personId,
                photoIds = ids,
                targetPersonId = targetPersonId,
                newPersonName = newPersonName
            )
            selectedIdsFlow.value = emptySet()
            if (result.movedFaceCount > 0) {
                val countLabel = if (ids.size == 1) "1 photo" else "${ids.size} photos"
                snackbarFlow.value = "Moved $countLabel to ${result.destinationDisplayName}"
                if (result.sourceClusterDeleted) {
                    mergedAwayFlow.value = true
                }
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
        val faces: List<LightFaceRow>,
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

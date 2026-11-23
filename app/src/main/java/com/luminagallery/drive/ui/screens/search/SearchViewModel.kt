package com.luminagallery.drive.ui.screens.search

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.luminagallery.drive.AppContainer
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.local.entity.MediaAnnotationEntity
import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import com.luminagallery.drive.data.local.entity.PhotoOcrEntity
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.data.repository.OcrIndexProgress
import com.luminagallery.drive.data.repository.OcrIndexerManager
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.domain.usecase.ExportMediaUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DateFilterPreset(val label: String) {
    ANY("Any time"),
    LAST_7_DAYS("7 days"),
    LAST_30_DAYS("30 days"),
    THIS_YEAR("This year"),
    CUSTOM("Date range")
}

@Immutable
data class SearchUiState(
    val query: String = "",
    val selectedMediaType: MediaType? = null,
    val datePreset: DateFilterPreset = DateFilterPreset.ANY,
    val customFromEpochMs: Long? = null,
    val customToEpochMs: Long? = null,
    val resolvedFromEpochMs: Long? = null,
    val resolvedToEpochMs: Long? = null,
    val results: List<MediaItem> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val suggestedCities: List<String> = emptyList(),
    val suggestedTags: List<String> = emptyList(),
    val onlyWithText: Boolean = false,
    val ocrProgress: OcrIndexProgress = OcrIndexProgress(),
    val gridColumns: Int = 3,
    val isUnmeteredWifi: Boolean = true,
    val selectedIds: Set<String> = emptySet(),
    val isExporting: Boolean = false,
    val exportProgressText: String? = null,
    val infoItem: MediaItem? = null,
    val infoFolderPath: String = "Google Drive",
    val pendingShareUris: List<Uri>? = null,
    val snackbarMessage: String? = null
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

private data class SearchQueryParams(
    val query: String,
    val mediaType: MediaType?,
    val fromEpochMs: Long?,
    val toEpochMs: Long?,
    val onlyWithText: Boolean = false
)

class SearchViewModel(
    private val repository: GalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val ocrIndexerManager: OcrIndexerManager,
    private val preferencesManager: PreferencesManager,
    private val exportMediaUseCase: ExportMediaUseCase,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val mediaTypeFlow = MutableStateFlow<MediaType?>(null)
    private val onlyWithTextFlow = MutableStateFlow(false)
    private val datePresetFlow = MutableStateFlow(DateFilterPreset.ANY)
    private val customRangeFlow = MutableStateFlow<Pair<Long?, Long?>>(null to null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cachedLocal = localMediaRepository.getCachedAllMedia()
            if (cachedLocal.isNotEmpty()) {
                ocrIndexerManager.syncOcrIndex(cachedLocal, maxNewItemsPerRun = 260)
            }
        }
    }

    private val selectedIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val isExportingFlow = MutableStateFlow(false)
    private val exportProgressFlow = MutableStateFlow<String?>(null)
    private val infoSheetFlow = MutableStateFlow<Pair<MediaItem, String>?>(null)
    private val pendingShareFlow = MutableStateFlow<List<Uri>?>(null)
    private val snackbarFlow = MutableStateFlow<String?>(null)

    private val filterParamsFlow = combine(
        queryFlow.debounce(110L),
        mediaTypeFlow,
        datePresetFlow,
        customRangeFlow,
        onlyWithTextFlow
    ) { query, type, preset, (customFrom, customTo), onlyText ->
        val (from, to) = resolveDateRange(preset, customFrom, customTo)
        SearchQueryParams(query, type, from, to, onlyText)
    }

    // Combined offline index across phone media, cached Drive media, GPS cities, custom tags/notes, and ML Kit OCR text
    private val allSearchableMediaFlow = combine(
        localMediaRepository.observeAllLocalMedia(),
        repository.searchMedia("", null, null, null),
        extrasRepository.lockedAlbumIdsFlow
    ) { local, drive, lockedIds ->
        val visible = if (lockedIds.isEmpty()) {
            local + drive
        } else {
            (local + drive).filterNot { it.parentId in lockedIds }
        }
        visible.sortedByDescending { it.createdTimeEpochMs }
    }.flowOn(Dispatchers.Default)

    private val enrichmentFlow = combine(
        extrasRepository.cachedLocationsMapFlow,
        extrasRepository.mediaAnnotationsFlow,
        extrasRepository.ocrTextMapFlow,
        ocrIndexerManager.progress,
        preferencesManager.recentSearchesFlow
    ) { locs, annotations, ocrMap, ocrProg, recents ->
        SearchEnrichment(
            locationsByItem = locs,
            annotationsByItem = annotations,
            ocrByItem = ocrMap,
            ocrProgress = ocrProg,
            recentSearches = recents,
            topCities = locs.values
                .map { it.city }
                .filter { it.isNotBlank() && !it.contains("°") && !it.startsWith("Location") }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(8)
                .map { it.key },
            topTags = annotations.values
                .flatMap { it.tagsCsv.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(10)
                .map { it.key }
        )
    }.flowOn(Dispatchers.Default)

    private val searchResultsFlow = combine(
        filterParamsFlow,
        allSearchableMediaFlow,
        enrichmentFlow
    ) { params, allMedia, enrich ->
        val tokens = params.query.trim()
            .split(Regex("\\s+"))
            .map { it.removePrefix("#").trim() }
            .filter { it.isNotEmpty() }

        val filtered = allMedia.filter { item ->
            if (params.mediaType != null && item.mediaType != params.mediaType) return@filter false
            if (params.fromEpochMs != null && item.createdTimeEpochMs < params.fromEpochMs) return@filter false
            if (params.toEpochMs != null && item.createdTimeEpochMs > params.toEpochMs) return@filter false
            val ocr = enrich.ocrByItem[item.id]
            if (params.onlyWithText && (ocr == null || !ocr.hasText)) return@filter false
            if (tokens.isEmpty()) return@filter true

            val loc = enrich.locationsByItem[item.id]
            val ann = enrich.annotationsByItem[item.id]
            tokens.all { token -> matchesToken(item, loc, ann, ocr, token) }
        }
        filtered to enrich
    }.flowOn(Dispatchers.Default)

    private val queryControlsFlow = combine(
        queryFlow,
        mediaTypeFlow,
        datePresetFlow,
        customRangeFlow,
        onlyWithTextFlow
    ) { query, type, preset, range, onlyText ->
        SearchControls(query, type, preset, range.first, range.second, onlyText)
    }

    private val interactionFlow = combine(
        selectedIdsFlow,
        isExportingFlow,
        exportProgressFlow,
        infoSheetFlow,
        combine(pendingShareFlow, snackbarFlow) { share, snack -> share to snack }
    ) { selected, exporting, progress, info, (share, snack) ->
        SearchInteraction(selected, exporting, progress, info, share, snack)
    }

    val uiState: StateFlow<SearchUiState> = combine(
        queryControlsFlow,
        searchResultsFlow,
        preferencesManager.preferencesFlow,
        networkMonitor.networkState,
        interactionFlow
    ) { controls, (results, enrich), prefs, net, interaction ->
        val (resolvedFrom, resolvedTo) = resolveDateRange(
            controls.preset,
            controls.customFrom,
            controls.customTo
        )
        SearchUiState(
            query = controls.query,
            selectedMediaType = controls.mediaType,
            datePreset = controls.preset,
            customFromEpochMs = controls.customFrom,
            customToEpochMs = controls.customTo,
            resolvedFromEpochMs = resolvedFrom,
            resolvedToEpochMs = resolvedTo,
            results = results,
            recentSearches = enrich.recentSearches,
            suggestedCities = enrich.topCities,
            suggestedTags = enrich.topTags,
            onlyWithText = controls.onlyWithText,
            ocrProgress = enrich.ocrProgress,
            gridColumns = prefs.gridColumns,
            isUnmeteredWifi = net.isUnmeteredWifi || !prefs.wifiOnlyHighRes,
            selectedIds = interaction.selectedIds,
            isExporting = interaction.isExporting,
            exportProgressText = interaction.exportProgressText,
            infoItem = interaction.info?.first,
            infoFolderPath = interaction.info?.second ?: "Google Drive",
            pendingShareUris = interaction.pendingShareUris,
            snackbarMessage = interaction.snackbarMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SearchUiState()
    )

    private fun matchesToken(
        item: MediaItem,
        location: PhotoLocationEntity?,
        annotation: MediaAnnotationEntity?,
        ocr: PhotoOcrEntity?,
        token: String
    ): Boolean {
        if (item.name.contains(token, ignoreCase = true)) return true
        if (item.relativePath?.contains(token, ignoreCase = true) == true) return true
        if (item.storagePath?.contains(token, ignoreCase = true) == true) return true
        if (item.monthYearLabel.contains(token, ignoreCase = true)) return true
        if (item.fullDateLabel.contains(token, ignoreCase = true)) return true
        if (item.exif.cameraMake?.contains(token, ignoreCase = true) == true) return true
        if (item.exif.cameraModel?.contains(token, ignoreCase = true) == true) return true
        if (item.exif.lens?.contains(token, ignoreCase = true) == true) return true
        if (location != null && (
                location.city.contains(token, ignoreCase = true) ||
                    location.country.contains(token, ignoreCase = true)
                )
        ) return true
        if (annotation != null && (
                annotation.tagsCsv.contains(token, ignoreCase = true) ||
                    annotation.note.contains(token, ignoreCase = true)
                )
        ) return true
        if (ocr != null && ocr.hasText && ocr.extractedText.contains(token, ignoreCase = true)) return true
        if (token.equals("video", ignoreCase = true) && item.isVideo) return true
        if (token.equals("raw", ignoreCase = true) && item.isRaw) return true
        if (token.equals("drive", ignoreCase = true) && !item.isLocal) return true
        return false
    }

    fun toggleOnlyWithText() {
        onlyWithTextFlow.update { !it }
    }

    fun setItemSelected(itemId: String, selected: Boolean) {
        selectedIdsFlow.update { current ->
            if (selected) current + itemId else current - itemId
        }
    }

    fun onQueryChanged(value: String) {
        queryFlow.value = value
    }

    fun commitSearchHistory(query: String = queryFlow.value) {
        val clean = query.trim()
        if (clean.length >= 2) {
            viewModelScope.launch { preferencesManager.addRecentSearch(clean) }
        }
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { preferencesManager.removeRecentSearch(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { preferencesManager.clearRecentSearches() }
    }

    fun onMediaTypeSelected(type: MediaType?) {
        mediaTypeFlow.value = type
    }

    fun onDatePresetSelected(preset: DateFilterPreset) {
        datePresetFlow.value = preset
        if (preset != DateFilterPreset.CUSTOM) {
            customRangeFlow.value = null to null
        }
    }

    fun onCustomDateRangeSelected(fromEpochMs: Long?, toEpochMs: Long?) {
        if (fromEpochMs == null && toEpochMs == null) {
            datePresetFlow.value = DateFilterPreset.ANY
            customRangeFlow.value = null to null
        } else {
            val endOfDayTo = toEpochMs?.let { it + 86_399_999L }
            datePresetFlow.value = DateFilterPreset.CUSTOM
            customRangeFlow.value = fromEpochMs to endOfDayTo
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

    fun clearSelection() {
        selectedIdsFlow.value = emptySet()
    }

    fun shareSelected() {
        val selected = uiState.value.results.filter { it.id in selectedIdsFlow.value }
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
        val selected = uiState.value.results.filter { it.id in selectedIdsFlow.value }
        if (selected.isEmpty() || isExportingFlow.value) return
        viewModelScope.launch {
            isExportingFlow.value = true
            exportProgressFlow.value = "Saving 0 / ${selected.size}"
            exportMediaUseCase.saveToDevice(selected) { done, total ->
                exportProgressFlow.value = "Saving $done / $total"
            }.onSuccess { count ->
                snackbarFlow.value = "Saved $count item(s) to device gallery"
                selectedIdsFlow.value = emptySet()
            }.onFailure { err ->
                snackbarFlow.value = err.message ?: "Failed to save to device"
            }
            isExportingFlow.value = false
            exportProgressFlow.value = null
        }
    }

    fun openSelectedInfo() {
        val singleId = selectedIdsFlow.value.singleOrNull() ?: return
        val item = uiState.value.results.firstOrNull { it.id == singleId } ?: return
        viewModelScope.launch {
            if (item.isLocal) {
                val (exif, path) = localMediaRepository.readLocalExifAndPath(item)
                infoSheetFlow.value = item.copy(exif = exif) to path
            } else {
                val path = repository.getFolderPathString(item.parentId)
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

    private fun resolveDateRange(
        preset: DateFilterPreset,
        customFrom: Long?,
        customTo: Long?
    ): Pair<Long?, Long?> {
        val zone = ZoneId.systemDefault()
        val now = Instant.now().toEpochMilli()
        return when (preset) {
            DateFilterPreset.ANY -> null to null
            DateFilterPreset.LAST_7_DAYS -> (now - 7L * 86_400_000L) to now
            DateFilterPreset.LAST_30_DAYS -> (now - 30L * 86_400_000L) to now
            DateFilterPreset.THIS_YEAR -> {
                val startOfYear = LocalDate.now(zone).withDayOfYear(1)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                startOfYear to now
            }
            DateFilterPreset.CUSTOM -> customFrom to customTo
        }
    }

    private data class SearchEnrichment(
        val locationsByItem: Map<String, PhotoLocationEntity>,
        val annotationsByItem: Map<String, MediaAnnotationEntity>,
        val ocrByItem: Map<String, PhotoOcrEntity>,
        val ocrProgress: OcrIndexProgress,
        val recentSearches: List<String>,
        val topCities: List<String>,
        val topTags: List<String>
    )

    private data class SearchControls(
        val query: String,
        val mediaType: MediaType?,
        val preset: DateFilterPreset,
        val customFrom: Long?,
        val customTo: Long?,
        val onlyWithText: Boolean
    )

    private data class SearchInteraction(
        val selectedIds: Set<String>,
        val isExporting: Boolean,
        val exportProgressText: String?,
        val info: Pair<MediaItem, String>?,
        val pendingShareUris: List<Uri>?,
        val snackbarMessage: String?
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(
            repository = container.galleryRepository,
            localMediaRepository = container.localMediaRepository,
            extrasRepository = container.galleryExtrasRepository,
            ocrIndexerManager = container.ocrIndexerManager,
            preferencesManager = container.preferencesManager,
            exportMediaUseCase = container.exportMediaUseCase,
            networkMonitor = container.networkMonitor
        ) as T
    }
}

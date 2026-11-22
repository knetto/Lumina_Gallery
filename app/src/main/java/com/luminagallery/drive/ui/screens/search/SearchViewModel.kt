package com.luminagallery.drive.ui.screens.search

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.luminagallery.drive.AppContainer
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.domain.usecase.ExportMediaUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
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
    val toEpochMs: Long?
)

class SearchViewModel(
    private val repository: GalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val preferencesManager: PreferencesManager,
    private val exportMediaUseCase: ExportMediaUseCase,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val mediaTypeFlow = MutableStateFlow<MediaType?>(null)
    private val datePresetFlow = MutableStateFlow(DateFilterPreset.ANY)
    private val customRangeFlow = MutableStateFlow<Pair<Long?, Long?>>(null to null)

    private val selectedIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val isExportingFlow = MutableStateFlow(false)
    private val exportProgressFlow = MutableStateFlow<String?>(null)
    private val infoSheetFlow = MutableStateFlow<Pair<MediaItem, String>?>(null)
    private val pendingShareFlow = MutableStateFlow<List<Uri>?>(null)
    private val snackbarFlow = MutableStateFlow<String?>(null)

    private val filterParamsFlow = combine(
        queryFlow.debounce(120L),
        mediaTypeFlow,
        datePresetFlow,
        customRangeFlow
    ) { query, type, preset, (customFrom, customTo) ->
        val (from, to) = resolveDateRange(preset, customFrom, customTo)
        SearchQueryParams(query, type, from, to)
    }

    private val searchResultsFlow = filterParamsFlow.flatMapLatest { params ->
        combine(
            localMediaRepository.searchLocalMedia(
                query = params.query,
                mediaType = params.mediaType,
                fromEpochMs = params.fromEpochMs,
                toEpochMs = params.toEpochMs
            ),
            repository.searchMedia(
                query = params.query,
                mediaType = params.mediaType,
                fromEpochMs = params.fromEpochMs,
                toEpochMs = params.toEpochMs
            )
        ) { localItems, driveItems ->
            (localItems + driveItems).sortedByDescending { it.createdTimeEpochMs }
        }
    }

    private val queryControlsFlow = combine(
        queryFlow,
        mediaTypeFlow,
        datePresetFlow,
        customRangeFlow
    ) { query, type, preset, range ->
        SearchControls(query, type, preset, range.first, range.second)
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
    ) { controls, results, prefs, net, interaction ->
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
            gridColumns = prefs.gridColumns,
            isUnmeteredWifi = net.isUnmeteredWifi,
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

    fun onQueryChanged(value: String) {
        queryFlow.value = value
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

    private data class SearchControls(
        val query: String,
        val mediaType: MediaType?,
        val preset: DateFilterPreset,
        val customFrom: Long?,
        val customTo: Long?
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
            preferencesManager = container.preferencesManager,
            exportMediaUseCase = container.exportMediaUseCase,
            networkMonitor = container.networkMonitor
        ) as T
    }
}

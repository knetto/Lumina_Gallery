package com.luminagallery.drive.ui.screens.search

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.ui.components.CollagePdfBottomSheet
import com.luminagallery.drive.ui.components.ExifMetadataBottomSheet
import com.luminagallery.drive.ui.components.FastScrollThumb
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.MediaGridItem
import com.luminagallery.drive.ui.components.MultiSelectActionBar
import com.luminagallery.drive.ui.components.PinchZoomGridContainer
import com.luminagallery.drive.ui.components.photoGridDragSelect

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onNavigateBack: () -> Unit,
    onOpenViewer: (
        itemId: String,
        query: String,
        mediaType: MediaType?,
        fromEpochMs: Long?,
        toEpochMs: Long?
    ) -> Unit,
    onOpenCompare: (List<String>) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val favoriteIds by container.galleryExtrasRepository.favoriteItemIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDateRangeDialog by remember { mutableStateOf(false) }
    var showCollagePdfSheet by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.pendingShareUris) {
        val uris = uiState.pendingShareUris ?: return@LaunchedEffect
        FormatUtils.launchShareSheet(context, uris)
        viewModel.consumePendingShare()
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { LuminaSnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                        TextField(
                            value = uiState.query,
                            onValueChange = viewModel::onQueryChanged,
                            placeholder = { Text("Search text in photos, city, tag, or name…") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { viewModel.commitSearchHistory() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        if (uiState.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChanged("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear query")
                            }
                        }
                    }
                }

                // Media type & date filter chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "type_all") {
                        FilterChip(
                            selected = uiState.selectedMediaType == null,
                            onClick = { viewModel.onMediaTypeSelected(null) },
                            label = { Text("All") }
                        )
                    }
                    item(key = "type_photo") {
                        FilterChip(
                            selected = uiState.selectedMediaType == MediaType.PHOTO,
                            onClick = { viewModel.onMediaTypeSelected(MediaType.PHOTO) },
                            label = { Text("Photos") }
                        )
                    }
                    item(key = "type_video") {
                        FilterChip(
                            selected = uiState.selectedMediaType == MediaType.VIDEO,
                            onClick = { viewModel.onMediaTypeSelected(MediaType.VIDEO) },
                            label = { Text("Videos") }
                        )
                    }
                    item(key = "type_raw") {
                        FilterChip(
                            selected = uiState.selectedMediaType == MediaType.RAW,
                            onClick = { viewModel.onMediaTypeSelected(MediaType.RAW) },
                            label = { Text("RAW") }
                        )
                    }
                    item(key = "filter_has_text") {
                        FilterChip(
                            selected = uiState.onlyWithText,
                            onClick = viewModel::toggleOnlyWithText,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.DocumentScanner,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text("Text in photos") }
                        )
                    }
                    items(
                        items = DateFilterPreset.entries,
                        key = { "date_${it.name}" }
                    ) { preset ->
                        FilterChip(
                            selected = uiState.datePreset == preset,
                            onClick = {
                                if (preset == DateFilterPreset.CUSTOM) {
                                    showDateRangeDialog = true
                                } else {
                                    viewModel.onDatePresetSelected(preset)
                                }
                            },
                            leadingIcon = if (preset == DateFilterPreset.CUSTOM) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.CalendarMonth,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            label = { Text(preset.label) }
                        )
                    }
                }
                if (uiState.ocrProgress.isIndexing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.8.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Scanning text in photos offline (${uiState.ocrProgress.indexedCount}/${uiState.ocrProgress.totalCount})…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.results.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "No matching media in local cache",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Try a different filename, media type, or date filter",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            } else {
                PinchZoomGridContainer(
                    currentColumns = uiState.gridColumns,
                    onColumnsChange = viewModel::setGridColumns,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val orderedResultIds = remember(uiState.results) { uiState.results.map { it.id } }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(uiState.gridColumns),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 4.dp,
                            bottom = if (uiState.isSelectionMode) 110.dp else 24.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .photoGridDragSelect(
                                gridState = gridState,
                                orderedItemIds = orderedResultIds,
                                keyToItemId = { key ->
                                    (key as? String)?.takeUnless { it == "smart_discovery" }
                                },
                                selectedIds = uiState.selectedIds,
                                onSetItemSelected = viewModel::setItemSelected
                            )
                    ) {
                        // Recent searches & smart city/tag discovery chips when query is blank
                        if (uiState.query.isBlank() &&
                            (uiState.recentSearches.isNotEmpty() || uiState.suggestedCities.isNotEmpty() || uiState.suggestedTags.isNotEmpty())
                        ) {
                            item(key = "smart_discovery", span = { GridItemSpan(maxLineSpan) }) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (uiState.recentSearches.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Recent searches",
                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "Clear",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable(onClick = viewModel::clearRecentSearches)
                                                    .padding(4.dp)
                                            )
                                        }
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(uiState.recentSearches, key = { "rec_$it" }) { recent ->
                                                InputChip(
                                                    selected = false,
                                                    onClick = { viewModel.onQueryChanged(recent) },
                                                    label = { Text(recent) },
                                                    leadingIcon = {
                                                        Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    },
                                                    trailingIcon = {
                                                        Icon(
                                                            Icons.Rounded.Close,
                                                            contentDescription = "Remove",
                                                            modifier = Modifier
                                                                .size(15.dp)
                                                                .clickable { viewModel.removeRecentSearch(recent) }
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    if (uiState.suggestedCities.isNotEmpty() || uiState.suggestedTags.isNotEmpty()) {
                                        Text(
                                            text = "Places & tags",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(uiState.suggestedCities, key = { "city_$it" }) { city ->
                                                AssistChip(
                                                    onClick = {
                                                        viewModel.onQueryChanged(city)
                                                        viewModel.commitSearchHistory(city)
                                                    },
                                                    label = { Text(city) },
                                                    leadingIcon = {
                                                        Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    }
                                                )
                                            }
                                            items(uiState.suggestedTags, key = { "tag_$it" }) { tag ->
                                                AssistChip(
                                                    onClick = {
                                                        viewModel.onQueryChanged(tag)
                                                        viewModel.commitSearchHistory(tag)
                                                    },
                                                    label = { Text("#$tag") },
                                                    leadingIcon = {
                                                        Icon(Icons.Rounded.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        items(
                            items = uiState.results,
                            key = { it.id },
                            contentType = { "search_media" }
                        ) { item ->
                            val isSelected = item.id in uiState.selectedIds
                            MediaGridItem(
                                item = item,
                                isUnmeteredWifi = uiState.isUnmeteredWifi,
                                isSelectionMode = uiState.isSelectionMode,
                                isSelected = isSelected,
                                isFavorite = item.id in favoriteIds,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleSelection(item.id)
                                    } else {
                                        viewModel.commitSearchHistory()
                                        onOpenViewer(
                                            item.id,
                                            uiState.query,
                                            uiState.selectedMediaType,
                                            uiState.resolvedFromEpochMs,
                                            uiState.resolvedToEpochMs
                                        )
                                    }
                                },
                                onLongClick = null,
                                showCloudBadge = true,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

                    FastScrollThumb(
                        gridState = gridState,
                        totalItemCount = uiState.results.size,
                        labelForIndex = { idx -> uiState.results.getOrNull(idx)?.monthYearLabel },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            val selectedSearchPhotos = remember(uiState.selectedIds, uiState.results) {
                uiState.results.filter { it.id in uiState.selectedIds && !it.isVideo }
            }
            val comparableIds = remember(selectedSearchPhotos) {
                selectedSearchPhotos.map { it.id }
            }
            MultiSelectActionBar(
                visible = uiState.isSelectionMode,
                selectedCount = uiState.selectedIds.size,
                isExporting = uiState.isExporting,
                exportProgressText = uiState.exportProgressText,
                onCompare = if (comparableIds.size in 2..4) {
                    {
                        viewModel.clearSelection()
                        onOpenCompare(comparableIds)
                    }
                } else null,
                onCreateCollagePdf = if (selectedSearchPhotos.isNotEmpty()) {
                    { showCollagePdfSheet = true }
                } else null,
                onShare = viewModel::shareSelected,
                onSaveToDevice = viewModel::saveSelectedToDevice,
                onInfo = viewModel::openSelectedInfo,
                onClearSelection = viewModel::clearSelection,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showCollagePdfSheet) {
        val photosForCreation = remember(uiState.selectedIds, uiState.results) {
            uiState.results.filter { it.id in uiState.selectedIds && !it.isVideo }
        }
        if (photosForCreation.isNotEmpty()) {
            CollagePdfBottomSheet(
                items = photosForCreation,
                onDismiss = { showCollagePdfSheet = false },
                onCompleted = { msg ->
                    showCollagePdfSheet = false
                    viewModel.clearSelection()
                    viewModel.showMessage(msg)
                }
            )
        }
    }

    // EXIF Bottom Sheet
    uiState.infoItem?.let { item ->
        ExifMetadataBottomSheet(
            item = item,
            driveFolderPath = uiState.infoFolderPath,
            onDismiss = viewModel::closeInfoSheet,
            onCopiedMessage = viewModel::showMessage
        )
    }

    // Custom Date Range Picker Dialog
    if (showDateRangeDialog) {
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = uiState.customFromEpochMs,
            initialSelectedEndDateMillis = uiState.customToEpochMs
        )
        DatePickerDialog(
            onDismissRequest = { showDateRangeDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onCustomDateRangeSelected(
                            fromEpochMs = pickerState.selectedStartDateMillis,
                            toEpochMs = pickerState.selectedEndDateMillis
                        )
                        showDateRangeDialog = false
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDateRangeDialog = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DateRangePicker(
                state = pickerState,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

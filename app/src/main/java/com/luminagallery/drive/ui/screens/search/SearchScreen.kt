package com.luminagallery.drive.ui.screens.search

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.ui.components.ExifMetadataBottomSheet
import com.luminagallery.drive.ui.components.FastScrollThumb
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.MediaGridItem
import com.luminagallery.drive.ui.components.MultiSelectActionBar
import com.luminagallery.drive.ui.components.PinchZoomGridContainer

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
    ) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val favoriteIds by container.galleryExtrasRepository.favoriteItemIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDateRangeDialog by remember { mutableStateOf(false) }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            placeholder = { Text("Search phone & Google Drive…") },
                            singleLine = true,
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
                        modifier = Modifier.fillMaxSize()
                    ) {
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
                                        onOpenViewer(
                                            item.id,
                                            uiState.query,
                                            uiState.selectedMediaType,
                                            uiState.resolvedFromEpochMs,
                                            uiState.resolvedToEpochMs
                                        )
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(item.id) },
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

            MultiSelectActionBar(
                visible = uiState.isSelectionMode,
                selectedCount = uiState.selectedIds.size,
                isExporting = uiState.isExporting,
                exportProgressText = uiState.exportProgressText,
                onShare = viewModel::shareSelected,
                onSaveToDevice = viewModel::saveSelectedToDevice,
                onInfo = viewModel::openSelectedInfo,
                onClearSelection = viewModel::clearSelection,
                modifier = Modifier.align(Alignment.BottomCenter)
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

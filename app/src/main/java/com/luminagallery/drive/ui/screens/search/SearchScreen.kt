package com.luminagallery.drive.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.History
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.luminagallery.drive.ui.components.PersonAvatar
import com.luminagallery.drive.ui.components.PinchZoomGridContainer
import com.luminagallery.drive.ui.components.iconForObjectCategory
import com.luminagallery.drive.ui.components.photoGridDragSelect

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onNavigateBack: () -> Unit,
    onOpenPeople: () -> Unit = {},
    onOpenPersonDetail: (personId: String) -> Unit = {},
    onOpenViewer: (
        itemId: String,
        query: String,
        mediaType: MediaType?,
        fromEpochMs: Long?,
        toEpochMs: Long?,
        onlyWithText: Boolean,
        personId: String?
    ) -> Unit,
    onOpenCompare: (List<String>) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val favoriteIds by container.galleryExtrasRepository.favoriteItemIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDateRangeDialog by remember { mutableStateOf(false) }
    var showCollagePdfSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

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
                            placeholder = { Text("Search people, objects, places, text…") },
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

                // Media type, active Person filter & date filter chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val activePerson = uiState.selectedPerson
                    if (activePerson != null) {
                        item(key = "active_person_filter") {
                            InputChip(
                                selected = true,
                                onClick = { onOpenPersonDetail(activePerson.personId) },
                                avatar = {
                                    PersonAvatar(
                                        cropPath = activePerson.coverCropPath,
                                        contentDescription = activePerson.displayTitle,
                                        size = 24.dp
                                    )
                                },
                                label = { Text(activePerson.displayTitle, fontWeight = FontWeight.SemiBold) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Clear person filter",
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { viewModel.selectPersonFilter(null) }
                                    )
                                }
                            )
                        }
                    }
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
                            label = {
                                val text = if (preset == DateFilterPreset.CUSTOM && !uiState.customRangeLabel.isNullOrBlank()) {
                                    uiState.customRangeLabel!!
                                } else {
                                    preset.label
                                }
                                Text(text)
                            }
                        )
                    }
                }
                val smartProg = uiState.smartProgress
                if (smartProg.isIndexing || smartProg.isDownloadingModels) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (smartProg.isDownloadingModels) {
                                    "Downloading on-device People & Objects AI models…"
                                } else {
                                    "Recognizing people & objects (${smartProg.totalIndexedPhotos}/${smartProg.totalEligiblePhotos}) · Local & Drive"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (smartProg.isIndexing) {
                            Text(
                                text = "Pause",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(onClick = viewModel::pauseSmartScan)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                } else if (uiState.ocrProgress.isIndexing) {
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
            val showSmartDiscovery = uiState.query.isBlank() &&
                uiState.selectedPerson == null &&
                (uiState.suggestedPeople.isNotEmpty() ||
                    uiState.suggestedObjects.isNotEmpty() ||
                    uiState.recentSearches.isNotEmpty() ||
                    uiState.suggestedCities.isNotEmpty() ||
                    uiState.suggestedTags.isNotEmpty() ||
                    uiState.smartProgress.remainingPhotos > 0)
            val discoveryHeaderOffset = if (showSmartDiscovery) 1 else 0

            PinchZoomGridContainer(
                currentColumns = uiState.gridColumns,
                onColumnsChange = viewModel::setGridColumns,
                minColumns = 1,
                maxColumns = 6,
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
                                (key as? String)?.takeUnless { it == "smart_discovery" || it == "empty_search_state" }
                            },
                            selectedIds = uiState.selectedIds,
                            onSetItemSelected = viewModel::setItemSelected,
                            onSetRangeSelected = viewModel::setItemsSelectedRange
                        )
                ) {
                    // Recent searches & smart city/tag discovery chips stay accessible even when results are empty
                    if (showSmartDiscovery) {
                        item(key = "smart_discovery", span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // 1. Samsung One UI Circular People Strip (tap to filter, hold to manage/name)
                                if (uiState.suggestedPeople.isNotEmpty() || uiState.smartProgress.remainingPhotos > 0) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "People",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (uiState.smartProgress.remainingPhotos > 0 && !uiState.smartProgress.isIndexing) {
                                                Text(
                                                    text = "Scan more",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier
                                                        .clickable(onClick = viewModel::scanMoreSmartPhotos)
                                                        .padding(4.dp)
                                                )
                                            }
                                            Text(
                                                text = "Manage & combine",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable(onClick = onOpenPeople)
                                                    .padding(4.dp)
                                            )
                                        }
                                    }
                                    if (uiState.suggestedPeople.isNotEmpty()) {
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            contentPadding = PaddingValues(horizontal = 2.dp)
                                        ) {
                                            items(uiState.suggestedPeople, key = { "person_${it.personId}" }) { person ->
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier
                                                        .width(72.dp)
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .combinedClickable(
                                                            onClick = {
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                viewModel.selectPersonFilter(person.personId)
                                                            },
                                                            onLongClick = {
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                onOpenPersonDetail(person.personId)
                                                            }
                                                        )
                                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                                ) {
                                                    PersonAvatar(
                                                        cropPath = person.coverCropPath,
                                                        contentDescription = person.displayTitle,
                                                        size = 64.dp,
                                                        isFavorite = person.isFavorite
                                                    )
                                                    Spacer(Modifier.height(5.dp))
                                                    Text(
                                                        text = if (person.isNamed) person.name else "Add name",
                                                        fontSize = 11.5.sp,
                                                        fontWeight = if (person.isNamed) FontWeight.Medium else FontWeight.Normal,
                                                        color = if (person.isNamed) {
                                                            MaterialTheme.colorScheme.onSurface
                                                        } else {
                                                            MaterialTheme.colorScheme.primary
                                                        },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // 2. On-device Detected Objects & Scenes Categories
                                if (uiState.suggestedObjects.isNotEmpty()) {
                                    Text(
                                        text = "Objects & scenes",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(uiState.suggestedObjects, key = { "obj_${it.label}" }) { chip ->
                                            AssistChip(
                                                onClick = {
                                                    viewModel.onQueryChanged(chip.label)
                                                    viewModel.commitSearchHistory(chip.label)
                                                },
                                                label = { Text("${chip.label} · ${chip.count}") },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = iconForObjectCategory(chip.category),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }

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
                                                    Icon(Icons.AutoMirrored.Rounded.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.results.isEmpty()) {
                        item(key = "empty_search_state", span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 72.dp),
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
                                    text = "No matching photos or videos",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Try a different name, city, camera, tag, or date filter",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                        }
                    } else {
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
                                            uiState.resolvedToEpochMs,
                                            uiState.onlyWithText,
                                            uiState.selectedPerson?.personId
                                        )
                                    }
                                },
                                onLongClick = null,
                                showCloudBadge = true,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }

                if (uiState.results.isNotEmpty()) {
                    FastScrollThumb(
                        gridState = gridState,
                        totalItemCount = uiState.results.size + discoveryHeaderOffset,
                        labelForIndex = { idx ->
                            uiState.results.getOrNull(idx - discoveryHeaderOffset)?.monthYearLabel
                        },
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
            initialSelectedStartDateMillis = uiState.pickerStartUtcMs,
            initialSelectedEndDateMillis = uiState.pickerEndUtcMs
        )
        DatePickerDialog(
            onDismissRequest = { showDateRangeDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onCustomDateRangeSelected(
                            fromUtcMs = pickerState.selectedStartDateMillis,
                            toUtcMs = pickerState.selectedEndDateMillis
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

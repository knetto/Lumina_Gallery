package com.luminagallery.drive.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
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
        personIdsCsv: String?
    ) -> Unit,
    onOpenCompare: (List<String>) -> Unit = {},
    lastViewedItemId: String? = null,
    onConsumedLastViewedItemId: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val favoriteIds by container.galleryExtrasRepository.favoriteItemIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilterDrawer by remember { mutableStateOf(false) }
    var showDateRangeDialog by remember { mutableStateOf(false) }
    var showCollagePdfSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.isSelectionMode || showFilterDrawer) {
        if (uiState.isSelectionMode) {
            viewModel.clearSelection()
        } else {
            showFilterDrawer = false
        }
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
                    .padding(top = 10.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Clean, calm pill search bar with integrated Filter toggle (no chip wall!)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 6.dp)
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
                            placeholder = {
                                Text(
                                    text = "Search photos, people, pets, places…",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
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
                            IconButton(
                                onClick = { viewModel.onQueryChanged("") },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear query",
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                        // Single Filter toggle button with subtle active badge
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    if (showFilterDrawer || uiState.hasActiveFilters) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showFilterDrawer = !showFilterDrawer
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = "Filters",
                                tint = if (showFilterDrawer || uiState.hasActiveFilters) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            if (uiState.activeFilterCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 6.dp, end = 6.dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                }

                // Smooth collapsible Filter Drawer (only shown when toggled)
                AnimatedVisibility(
                    visible = showFilterDrawer,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .border(
                                width = 0.6.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(22.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Type",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (uiState.hasActiveFilters) {
                                    Text(
                                        text = "Reset",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                viewModel.onMediaTypeSelected(null)
                                                viewModel.onDatePresetSelected(DateFilterPreset.ANY)
                                                if (uiState.onlyWithText) viewModel.toggleOnlyWithText()
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item(key = "f_all") {
                                    SearchTonalPill(
                                        label = "All",
                                        selected = uiState.selectedMediaType == null && !uiState.onlyWithText,
                                        onClick = {
                                            viewModel.onMediaTypeSelected(null)
                                            if (uiState.onlyWithText) viewModel.toggleOnlyWithText()
                                        }
                                    )
                                }
                                item(key = "f_photo") {
                                    SearchTonalPill(
                                        label = "Photos",
                                        selected = uiState.selectedMediaType == MediaType.PHOTO,
                                        onClick = {
                                            viewModel.onMediaTypeSelected(
                                                if (uiState.selectedMediaType == MediaType.PHOTO) null else MediaType.PHOTO
                                            )
                                        }
                                    )
                                }
                                item(key = "f_video") {
                                    SearchTonalPill(
                                        label = "Videos",
                                        selected = uiState.selectedMediaType == MediaType.VIDEO,
                                        onClick = {
                                            viewModel.onMediaTypeSelected(
                                                if (uiState.selectedMediaType == MediaType.VIDEO) null else MediaType.VIDEO
                                            )
                                        }
                                    )
                                }
                                item(key = "f_raw") {
                                    SearchTonalPill(
                                        label = "RAW",
                                        selected = uiState.selectedMediaType == MediaType.RAW,
                                        onClick = {
                                            viewModel.onMediaTypeSelected(
                                                if (uiState.selectedMediaType == MediaType.RAW) null else MediaType.RAW
                                            )
                                        }
                                    )
                                }
                                item(key = "f_text") {
                                    SearchTonalPill(
                                        label = "With text",
                                        icon = Icons.Rounded.DocumentScanner,
                                        selected = uiState.onlyWithText,
                                        onClick = viewModel::toggleOnlyWithText
                                    )
                                }
                            }

                            Text(
                                text = "Date",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(DateFilterPreset.entries, key = { "date_${it.name}" }) { preset ->
                                    val label = if (preset == DateFilterPreset.CUSTOM && !uiState.customRangeLabel.isNullOrBlank()) {
                                        uiState.customRangeLabel!!
                                    } else {
                                        preset.label
                                    }
                                    SearchTonalPill(
                                        label = label,
                                        icon = if (preset == DateFilterPreset.CUSTOM) Icons.Rounded.CalendarMonth else null,
                                        selected = uiState.datePreset == preset,
                                        onClick = {
                                            if (preset == DateFilterPreset.CUSTOM) {
                                                showDateRangeDialog = true
                                            } else {
                                                viewModel.onDatePresetSelected(preset)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Active filter / selected person pills (ONLY shown when something is actively filtered)
                val showActivePills = !showFilterDrawer && (uiState.selectedPeople.isNotEmpty() || uiState.hasActiveFilters)
                if (showActivePills) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.selectedPeople, key = { "active_person_${it.personId}" }) { activePerson ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onOpenPersonDetail(activePerson.personId) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                                ) {
                                    PersonAvatar(
                                        cropPath = activePerson.coverCropPath,
                                        contentDescription = activePerson.displayTitle,
                                        size = 24.dp,
                                        isPet = activePerson.isPet
                                    )
                                    Text(
                                        text = activePerson.displayTitle,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clickable { viewModel.togglePersonFilter(activePerson.personId) }
                                    )
                                }
                            }
                        }
                        uiState.selectedMediaType?.let { type ->
                            item(key = "active_type") {
                                ActiveRemovablePill(
                                    label = when (type) {
                                        MediaType.PHOTO -> "Photos"
                                        MediaType.VIDEO -> "Videos"
                                        MediaType.RAW -> "RAW"
                                    },
                                    onRemove = { viewModel.onMediaTypeSelected(null) }
                                )
                            }
                        }
                        if (uiState.onlyWithText) {
                            item(key = "active_text") {
                                ActiveRemovablePill(
                                    label = "With text",
                                    onRemove = viewModel::toggleOnlyWithText
                                )
                            }
                        }
                        if (uiState.datePreset != DateFilterPreset.ANY) {
                            item(key = "active_date") {
                                ActiveRemovablePill(
                                    label = if (uiState.datePreset == DateFilterPreset.CUSTOM && !uiState.customRangeLabel.isNullOrBlank()) {
                                        uiState.customRangeLabel!!
                                    } else {
                                        uiState.datePreset.label
                                    },
                                    onRemove = { viewModel.onDatePresetSelected(DateFilterPreset.ANY) }
                                )
                            }
                        }
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
            if (!uiState.hasActiveSearch) {
                // MODE A: Calm, spacious Discovery Hub (no overwhelming chip walls or raw 10k photo dump)
                val hasDiscoveryContent = uiState.suggestedPeople.isNotEmpty() ||
                    uiState.recentSearches.isNotEmpty() ||
                    uiState.suggestedObjects.isNotEmpty() ||
                    uiState.suggestedCities.isNotEmpty() ||
                    uiState.suggestedTags.isNotEmpty()

                if (!hasDiscoveryContent) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Search your gallery",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Find photos by person, pet, place, camera, date, or text inside images.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. People & Pets card
                        if (uiState.suggestedPeople.isNotEmpty()) {
                            item(key = "disc_people") {
                                SearchDiscoveryCard(
                                    title = "People & pets",
                                    actionLabel = "See all",
                                    onActionClick = onOpenPeople
                                ) {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        contentPadding = PaddingValues(horizontal = 2.dp)
                                    ) {
                                        items(uiState.suggestedPeople, key = { "person_${it.personId}" }) { person ->
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier
                                                    .width(68.dp)
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .combinedClickable(
                                                        onClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            viewModel.togglePersonFilter(person.personId)
                                                        },
                                                        onLongClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            onOpenPersonDetail(person.personId)
                                                        }
                                                    )
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                PersonAvatar(
                                                    cropPath = person.coverCropPath,
                                                    contentDescription = person.displayTitle,
                                                    size = 60.dp,
                                                    isFavorite = person.isFavorite,
                                                    isPet = person.isPet
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    text = if (person.isNamed) person.name else person.displayTitle,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = if (person.isNamed) FontWeight.Medium else FontWeight.Normal,
                                                    color = if (person.isNamed) {
                                                        MaterialTheme.colorScheme.onSurface
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
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
                        }

                        // 2. Recent searches (compact, borderless pills)
                        if (uiState.recentSearches.isNotEmpty()) {
                            item(key = "disc_recent") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Recent",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Clear",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable(onClick = viewModel::clearRecentSearches)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(uiState.recentSearches.take(6), key = { "rec_$it" }) { recent ->
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .clickable { viewModel.onQueryChanged(recent) }
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    modifier = Modifier.padding(start = 11.dp, end = 9.dp, top = 7.dp, bottom = 7.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.History,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Text(
                                                        text = recent,
                                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1
                                                    )
                                                    Icon(
                                                        imageVector = Icons.Rounded.Close,
                                                        contentDescription = "Remove",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                        modifier = Modifier
                                                            .size(14.dp)
                                                            .clickable { viewModel.removeRecentSearch(recent) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Explore Categories (clean 2-column tonal grid — much calmer than noisy outlined chips)
                        if (uiState.suggestedObjects.isNotEmpty()) {
                            item(key = "disc_explore") {
                                val topEight = remember(uiState.suggestedObjects) {
                                    uiState.suggestedObjects.take(8)
                                }
                                SearchDiscoveryCard(title = "Explore") {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (rowItems in topEight.chunked(2)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                for (chip in rowItems) {
                                                    Surface(
                                                        shape = RoundedCornerShape(14.dp),
                                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clip(RoundedCornerShape(14.dp))
                                                            .clickable {
                                                                viewModel.onQueryChanged(chip.label)
                                                                viewModel.commitSearchHistory(chip.label)
                                                            }
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 12.dp, vertical = 11.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = iconForObjectCategory(chip.category),
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                            Text(
                                                                text = chip.label,
                                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            Text(
                                                                text = "${chip.count}",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                                                            )
                                                        }
                                                    }
                                                }
                                                if (rowItems.size == 1) {
                                                    Spacer(Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Places & tags card (clean borderless tonal pills)
                        if (uiState.suggestedCities.isNotEmpty() || uiState.suggestedTags.isNotEmpty()) {
                            item(key = "disc_places_tags") {
                                SearchDiscoveryCard(title = "Places & tags") {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(uiState.suggestedCities, key = { "city_$it" }) { city ->
                                            SearchTonalPill(
                                                label = city,
                                                icon = Icons.Rounded.LocationOn,
                                                selected = false,
                                                onClick = {
                                                    viewModel.onQueryChanged(city)
                                                    viewModel.commitSearchHistory(city)
                                                }
                                            )
                                        }
                                        items(uiState.suggestedTags, key = { "tag_$it" }) { tag ->
                                            SearchTonalPill(
                                                label = "#$tag",
                                                icon = Icons.AutoMirrored.Rounded.Label,
                                                selected = false,
                                                onClick = {
                                                    viewModel.onQueryChanged(tag)
                                                    viewModel.commitSearchHistory(tag)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // MODE B: Focused Search / Filter Results Grid
                val showCompanionBar = uiState.selectedPeople.isNotEmpty() && uiState.companionPeople.isNotEmpty()
                val headerOffset = 1 + (if (showCompanionBar) 1 else 0)

                PinchZoomGridContainer(
                    currentColumns = uiState.gridColumns,
                    onColumnsChange = viewModel::setGridColumns,
                    minColumns = 1,
                    maxColumns = 6,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val orderedResultIds = remember(uiState.results) { uiState.results.map { it.id } }
                    com.luminagallery.drive.ui.components.ReturnMorphScrollEffect(
                        gridState = gridState,
                        lastViewedItemId = lastViewedItemId,
                        itemIds = orderedResultIds,
                        onConsumed = onConsumedLastViewedItemId
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(uiState.gridColumns),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 2.dp,
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
                                    (key as? String)?.takeUnless {
                                        it == "results_summary" || it == "companion_people_bar" || it == "empty_search_state"
                                    }
                                },
                                selectedIds = uiState.selectedIds,
                                onSetItemSelected = viewModel::setItemSelected,
                                onSetRangeSelected = viewModel::setItemsSelectedRange
                            )
                    ) {
                        // Compact results summary row
                        item(key = "results_summary", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (uiState.results.size == 1) "1 result" else "${uiState.results.size} results",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (uiState.hasActiveFilters || uiState.selectedPeople.isNotEmpty()) {
                                    Text(
                                        text = "Clear filters",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(onClick = viewModel::clearAllFilters)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // Multi-person/pet "Together with…" companion bar
                        if (showCompanionBar) {
                            item(key = "companion_people_bar", span = { GridItemSpan(maxLineSpan) }) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Together with",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(uiState.companionPeople, key = { "with_${it.personId}" }) { comp ->
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .clickable {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        viewModel.togglePersonFilter(comp.personId)
                                                    }
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                                    modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                                                ) {
                                                    PersonAvatar(
                                                        cropPath = comp.coverCropPath,
                                                        contentDescription = comp.displayTitle,
                                                        size = 24.dp,
                                                        isPet = comp.isPet
                                                    )
                                                    Text(
                                                        text = "${comp.displayTitle} · ${comp.photoCount}",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
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
                                        text = "Try a different name, pet, city, camera, tag, or date filter",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                        textAlign = TextAlign.Center
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
                                                uiState.selectedPeople.joinToString(",") { it.personId }.takeIf { it.isNotBlank() }
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
                            totalItemCount = uiState.results.size + headerOffset,
                            labelForIndex = { idx ->
                                uiState.results.getOrNull(idx - headerOffset)?.monthYearLabel
                            },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
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

    uiState.infoItem?.let { infoItem ->
        ExifMetadataBottomSheet(
            item = infoItem,
            driveFolderPath = uiState.infoFolderPath,
            onDismiss = viewModel::closeInfoSheet,
            onCopiedMessage = viewModel::showMessage,
            onOpenPerson = { pid ->
                viewModel.closeInfoSheet()
                onOpenPersonDetail(pid)
            },
            onSearchTag = { tag ->
                viewModel.closeInfoSheet()
                viewModel.onQueryChanged(tag)
                viewModel.commitSearchHistory(tag)
            }
        )
    }

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

@Composable
private fun SearchDiscoveryCard(
    title: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!actionLabel.isNullOrBlank() && onActionClick != null) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onActionClick)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SearchTonalPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ActiveRemovablePill(
    label: String,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onRemove)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(start = 12.dp, end = 9.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove filter",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

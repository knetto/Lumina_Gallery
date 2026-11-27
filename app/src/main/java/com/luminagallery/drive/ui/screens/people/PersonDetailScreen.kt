package com.luminagallery.drive.ui.screens.people

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.ui.components.CollagePdfBottomSheet
import com.luminagallery.drive.ui.components.ExifMetadataBottomSheet
import com.luminagallery.drive.ui.components.FastScrollThumb
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.MediaGridItem
import com.luminagallery.drive.ui.components.MultiSelectActionBar
import com.luminagallery.drive.ui.components.PersonAvatar
import com.luminagallery.drive.ui.components.PinchZoomGridContainer
import com.luminagallery.drive.ui.components.photoGridDragSelect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    personId: String,
    viewModel: PersonDetailViewModel,
    onNavigateBack: () -> Unit,
    onOpenViewer: (personIds: List<String>, itemId: String) -> Unit,
    onOpenPerson: (personId: String) -> Unit = {},
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
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showCombineSheet by remember { mutableStateOf(false) }
    var showWrongPersonSheet by remember { mutableStateOf(false) }
    var showCoverPickerSheet by remember { mutableStateOf(false) }
    var showCollagePdfSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    LaunchedEffect(uiState.isSelectionMode) {
        if (!uiState.isSelectionMode) {
            showWrongPersonSheet = false
        }
    }

    LaunchedEffect(uiState.personRemovedOrMerged) {
        if (uiState.personRemovedOrMerged) {
            onNavigateBack()
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

    val person = uiState.person
    val sameKindOtherPersons = remember(uiState.otherPersons, person) {
        if (person == null) emptyList()
        else uiState.otherPersons.filter { it.entityKind == person.entityKind }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { LuminaSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.isSelectionMode) {
                        Text(
                            text = "${uiState.selectedIds.size} selected",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    } else {
                        Text(
                            text = person?.name?.takeIf { it.isNotBlank() } ?: "Person",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.isSelectionMode) viewModel.clearSelection() else onNavigateBack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isSelectionMode) {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showWrongPersonSheet = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PersonRemove,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (person?.isPet == true) "Not this pet" else "Not this person",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else if (person != null) {
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Edit name")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PinchZoomGridContainer(
                currentColumns = uiState.gridColumns,
                onColumnsChange = viewModel::setGridColumns,
                minColumns = 1,
                maxColumns = 6,
                modifier = Modifier.fillMaxSize()
            ) {
                val orderedPhotoIds = remember(uiState.photos) { uiState.photos.map { it.id } }
                com.luminagallery.drive.ui.components.ReturnMorphScrollEffect(
                    gridState = gridState,
                    lastViewedItemId = lastViewedItemId,
                    itemIds = orderedPhotoIds,
                    onConsumed = onConsumedLastViewedItemId
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(uiState.gridColumns),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = 6.dp,
                        end = 6.dp,
                        top = 4.dp,
                        bottom = if (uiState.isSelectionMode) 116.dp else 28.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .photoGridDragSelect(
                            gridState = gridState,
                            orderedItemIds = orderedPhotoIds,
                            keyToItemId = { key ->
                                (key as? String)?.takeUnless { it == "person_hero_header" }
                            },
                            selectedIds = uiState.selectedIds,
                            onSetItemSelected = viewModel::setItemSelected,
                            onSetRangeSelected = viewModel::setItemsSelectedRange
                        )
                ) {
                    if (person != null) {
                        item(key = "person_hero_header", span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Large circular portrait with 1-tap cover face picker badge
                                Box(
                                    modifier = Modifier
                                        .clickable(enabled = uiState.personFaces.size > 1) {
                                            showCoverPickerSheet = true
                                        }
                                ) {
                                    PersonAvatar(
                                        cropPath = person.coverCropPath,
                                        contentDescription = person.displayTitle,
                                        size = 96.dp,
                                        isPet = person.isPet,
                                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                    )
                                    if (uiState.personFaces.size > 1) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                                .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PhotoCamera,
                                                contentDescription = "Change cover photo",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { showRenameDialog = true }
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (person.isNamed) person.name else "Add a name",
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 22.sp
                                            ),
                                            color = if (person.isNamed) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            imageVector = Icons.Rounded.Edit,
                                            contentDescription = "Rename",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    val localCount = uiState.photos.count { it.isLocal }
                                    val driveCount = uiState.photos.size - localCount
                                    val originSummary = when {
                                        localCount > 0 && driveCount > 0 -> "${uiState.photos.size} photos ($localCount local · $driveCount Drive)"
                                        driveCount > 0 -> "${uiState.photos.size} photos on Google Drive"
                                        else -> if (uiState.photos.size == 1) "1 photo" else "${uiState.photos.size} photos"
                                    }
                                    Text(
                                        text = originSummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Clean One UI quick-action chip bar
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) {
                                    item(key = "act_pin") {
                                        FilterChip(
                                            selected = person.isFavorite,
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.toggleFavorite()
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (person.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = { Text(if (person.isFavorite) "Pinned" else "Pin") }
                                        )
                                    }
                                    if (sameKindOtherPersons.isNotEmpty()) {
                                        item(key = "act_combine") {
                                            AssistChip(
                                                onClick = { showCombineSheet = true },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Merge,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                },
                                                label = { Text("Combine with…") }
                                            )
                                        }
                                    }
                                    item(key = "act_hide") {
                                        AssistChip(
                                            onClick = { viewModel.setHidden(!person.isHidden) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (person.isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = { Text(if (person.isHidden) "Unhide" else "Hide") }
                                        )
                                    }
                                }

                                // "Frequently with…" companion strip -> 1-tap filter to photos of BOTH together!
                                if (uiState.frequentCompanions.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (uiState.selectedCompanion != null) {
                                                    "Together with ${uiState.selectedCompanion?.displayTitle}"
                                                } else {
                                                    "Frequently with"
                                                },
                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (uiState.selectedCompanion != null) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                            if (uiState.selectedCompanion != null) {
                                                Text(
                                                    text = "Show all",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .clickable(onClick = viewModel::clearCompanionFilter)
                                                        .padding(4.dp)
                                                )
                                            }
                                        }
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = PaddingValues(horizontal = 2.dp)
                                        ) {
                                            lazyRowItems(
                                                items = uiState.frequentCompanions,
                                                key = { "comp_${it.personId}" }
                                            ) { comp ->
                                                val isActiveComp = uiState.selectedCompanion?.personId == comp.personId
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier
                                                        .width(62.dp)
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .clickable {
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            viewModel.toggleCompanionFilter(comp.personId)
                                                        }
                                                        .padding(vertical = 3.dp, horizontal = 2.dp)
                                                ) {
                                                    PersonAvatar(
                                                        cropPath = comp.coverCropPath,
                                                        contentDescription = comp.displayTitle,
                                                        size = 50.dp,
                                                        isSelected = isActiveComp
                                                    )
                                                    Spacer(Modifier.height(3.dp))
                                                    Text(
                                                        text = comp.displayTitle,
                                                        fontSize = 10.5.sp,
                                                        fontWeight = if (isActiveComp) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = if (isActiveComp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        textAlign = TextAlign.Center
                                                    )
                                                    Text(
                                                        text = "${comp.photoCount}",
                                                        fontSize = 9.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(
                        items = uiState.photos,
                        key = { it.id },
                        contentType = { "person_photo" }
                    ) { item ->
                        MediaGridItem(
                            item = item,
                            isUnmeteredWifi = uiState.isUnmeteredWifi,
                            isSelectionMode = uiState.isSelectionMode,
                            isSelected = item.id in uiState.selectedIds,
                            isFavorite = item.id in favoriteIds,
                            showCloudBadge = true,
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    viewModel.toggleSelection(item.id)
                                } else {
                                    val activeIds = buildList {
                                        add(personId)
                                        uiState.selectedCompanion?.personId?.let { add(it) }
                                    }
                                    onOpenViewer(activeIds, item.id)
                                }
                            },
                            onLongClick = null,
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                if (uiState.photos.isNotEmpty()) {
                    FastScrollThumb(
                        gridState = gridState,
                        totalItemCount = uiState.photos.size + 1,
                        labelForIndex = { idx -> uiState.photos.getOrNull(idx - 1)?.monthYearLabel },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }

            val selectedPhotos = remember(uiState.selectedIds, uiState.photos) {
                uiState.photos.filter { it.id in uiState.selectedIds && !it.isVideo }
            }
            val comparableIds = remember(selectedPhotos) { selectedPhotos.map { it.id } }

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
                onCreateCollagePdf = if (selectedPhotos.isNotEmpty()) {
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

    // Rename Dialog (with duplicate-name auto-merge option!)
    if (showRenameDialog && person != null) {
        var draft by remember(person.name) { mutableStateOf(person.name) }
        var duplicateName by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text(if (person.isNamed) "Rename person" else "Name this person") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            duplicateName = null
                        },
                        label = { Text("Name") },
                        placeholder = { Text("e.g. Emma, Dad…") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (duplicateName != null) {
                        Text(
                            text = "\"$duplicateName\" already exists — combine both groups into one person?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                if (duplicateName != null) {
                    Button(
                        onClick = {
                            showRenameDialog = false
                            viewModel.renamePerson(draft, mergeIfDuplicate = true)
                        }
                    ) { Text("Combine") }
                } else {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val dup = viewModel.findDuplicateNameTarget(draft)
                                if (dup != null) {
                                    duplicateName = dup
                                } else {
                                    showRenameDialog = false
                                    viewModel.renamePerson(draft, mergeIfDuplicate = false)
                                }
                            }
                        }
                    ) { Text("Save", fontWeight = FontWeight.SemiBold) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Visual "Combine with…" Bottom Sheet
    if (showCombineSheet && person != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var pickedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

        ModalBottomSheet(
            onDismissRequest = { showCombineSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Combine with ${person.displayTitle}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Select one or more groups showing the same person",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        enabled = pickedIds.isNotEmpty(),
                        onClick = {
                            showCombineSheet = false
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.combineWithPersons(pickedIds.toList())
                        },
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            text = if (pickedIds.isEmpty()) "Combine" else "Combine (${pickedIds.size})",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    items(sameKindOtherPersons, key = { it.personId }) { candidate ->
                        val picked = candidate.personId in pickedIds
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    pickedIds = if (picked) pickedIds - candidate.personId else pickedIds + candidate.personId
                                }
                                .padding(4.dp)
                        ) {
                            PersonAvatar(
                                cropPath = candidate.coverCropPath,
                                contentDescription = candidate.displayTitle,
                                size = 66.dp,
                                isSelected = picked,
                                isPet = candidate.isPet
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = candidate.displayTitle,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "${candidate.photoCount}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    // "Not this person — move to right person / new person / just remove" Bottom Sheet
    if (showWrongPersonSheet && person != null && uiState.isSelectionMode) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val kindWord = if (person.isPet) "pet" else "person"
        val selectedCount = uiState.selectedIds.size
        var searchQuery by remember { mutableStateOf("") }
        var showCreateNewField by remember { mutableStateOf(false) }
        var newPersonName by remember { mutableStateOf("") }

        val filteredCandidates = remember(sameKindOtherPersons, searchQuery) {
            val q = searchQuery.trim()
            if (q.isEmpty()) sameKindOtherPersons
            else sameKindOtherPersons.filter { it.displayTitle.contains(q, ignoreCase = true) }
        }

        ModalBottomSheet(
            onDismissRequest = { showWrongPersonSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Not ${person.displayTitle}?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = if (selectedCount == 1) {
                            "Choose who is in this photo instead, or just remove it from ${person.displayTitle}."
                        } else {
                            "Choose who is in these $selectedCount photos instead, or just remove them from ${person.displayTitle}."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Quick "+ New person" inline creation card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (showCreateNewField) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        width = if (showCreateNewField) 1.2.dp else 0.75.dp,
                        color = if (showCreateNewField) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !showCreateNewField) { showCreateNewField = true }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PersonAdd,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Move to a new $kindWord",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Create a separate $kindWord group (name optional)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        AnimatedVisibility(visible = showCreateNewField) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = newPersonName,
                                    onValueChange = { newPersonName = it },
                                    placeholder = { Text("Name (optional)…") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        showWrongPersonSheet = false
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.reassignSelectedToPerson(
                                            targetPersonId = null,
                                            newPersonName = newPersonName
                                        )
                                    },
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Move", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // Existing people / pets picker grid ("It's this person instead")
                if (sameKindOtherPersons.isNotEmpty()) {
                    Text(
                        text = "It's this $kindWord instead",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (sameKindOtherPersons.size >= 6) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search ${if (person.isPet) "pets" else "people"}…") },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (filteredCandidates.isEmpty()) {
                        Text(
                            text = "No matching ${if (person.isPet) "pets" else "people"} found.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                        ) {
                            items(filteredCandidates, key = { "move_${it.personId}" }) { candidate ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            showWrongPersonSheet = false
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.reassignSelectedToPerson(targetPersonId = candidate.personId)
                                        }
                                        .padding(4.dp)
                                ) {
                                    PersonAvatar(
                                        cropPath = candidate.coverCropPath,
                                        contentDescription = candidate.displayTitle,
                                        size = 64.dp,
                                        isFavorite = candidate.isFavorite,
                                        isPet = candidate.isPet
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        text = candidate.displayTitle,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "${candidate.photoCount}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Destructive fallback: just remove without assigning to anyone else
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            showWrongPersonSheet = false
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.removeSelectedFromPerson()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PersonRemove,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Just remove from ${person.displayTitle}",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }

    // Choose Cover Portrait Bottom Sheet
    if (showCoverPickerSheet && person != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCoverPickerSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Choose profile photo",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(uiState.personFaces.take(40), key = { it.faceId }) { face ->
                        val isCurrent = face.cropPath == person.coverCropPath
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.setCoverFace(face.faceId)
                                    showCoverPickerSheet = false
                                }
                        ) {
                            PersonAvatar(
                                cropPath = face.cropPath,
                                contentDescription = null,
                                size = 72.dp,
                                isSelected = isCurrent
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showCollagePdfSheet) {
        val photosForCreation = remember(uiState.selectedIds, uiState.photos) {
            uiState.photos.filter { it.id in uiState.selectedIds && !it.isVideo }
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

    uiState.infoItem?.let { item ->
        ExifMetadataBottomSheet(
            item = item,
            driveFolderPath = uiState.infoFolderPath,
            onDismiss = viewModel::closeInfoSheet,
            onCopiedMessage = viewModel::showMessage,
            onOpenPerson = { targetPid ->
                viewModel.closeInfoSheet()
                if (targetPid != personId) onOpenPerson(targetPid)
            }
        )
    }
}

package com.luminagallery.drive.ui.screens.people

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.itemsIndexed as lazyRowItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.ZoomIn
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.repository.DriveThumbnailRequest
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
import java.io.File
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

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
        var selectedFaceDetails by remember { mutableStateOf<List<SelectedWrongPersonPhotoInfo>>(emptyList()) }
        var inspectorInitialIndex by remember { mutableStateOf<Int?>(null) }

        LaunchedEffect(uiState.selectedIds) {
            selectedFaceDetails = viewModel.loadSelectedPhotoFaceDetails(uiState.selectedIds)
        }

        val singlePhotoDetail = selectedFaceDetails.singleOrNull()
        val alreadyTaggedPersonIdsInSingle = remember(singlePhotoDetail) {
            singlePhotoDetail?.otherFacesInPhoto?.mapNotNullTo(HashSet()) { it.personId }.orEmpty()
        }

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
                    .verticalScroll(rememberScrollState())
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
                            "Check which face was detected as ${person.displayTitle}, then move it to the right $kindWord or remove it."
                        } else {
                            "Check the detected faces in these $selectedCount photos, then move them or remove them from ${person.displayTitle}."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Clear visual proof of WHICH face was detected as this person (especially for group photos!)
                if (singlePhotoDetail != null) {
                    val primaryFace = singlePhotoDetail.primaryDetectedFace
                    val otherNamed = singlePhotoDetail.otherFacesInPhoto
                        .map { it.personName }
                        .filter { it.isNotBlank() }
                        .distinct()
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (singlePhotoDetail.isGroupPhoto) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(196.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF101318))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            inspectorInitialIndex = 0
                                        }
                                ) {
                                    FaceHighlightedPhotoOverlay(
                                        info = singlePhotoDetail,
                                        currentPersonName = person.displayTitle,
                                        compactLabels = true,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color(0xAA000000),
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.ZoomIn,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = "Tap to inspect",
                                                color = Color.White,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        inspectorInitialIndex = 0
                                    }
                                    .padding(horizontal = 2.dp, vertical = 2.dp)
                            ) {
                                PersonAvatar(
                                    cropPath = primaryFace?.cropPath ?: person.coverCropPath,
                                    contentDescription = "Detected face",
                                    size = 48.dp,
                                    isPet = person.isPet,
                                    borderColor = MaterialTheme.colorScheme.primary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Detected as ${person.displayTitle}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val detailSubtitle = when {
                                        singlePhotoDetail.isGroupPhoto && otherNamed.isNotEmpty() ->
                                            "Group photo (${singlePhotoDetail.totalFacesCount} faces) · Also tagged: ${otherNamed.joinToString(", ")}"
                                        singlePhotoDetail.isGroupPhoto ->
                                            "Group photo (${singlePhotoDetail.totalFacesCount} faces) · Highlighted face will be changed"
                                        else ->
                                            "1 face detected · Tap to inspect full photo"
                                    }
                                    Text(
                                        text = detailSubtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                } else if (selectedFaceDetails.size >= 2) {
                    val groupCount = selectedFaceDetails.count { it.isGroupPhoto }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (groupCount > 0) {
                                "Detected faces (${selectedFaceDetails.size} · $groupCount group) — tap to inspect, × to keep"
                            } else {
                                "Detected faces (${selectedFaceDetails.size}) — tap to inspect, × to keep"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            lazyRowItemsIndexed(
                                items = selectedFaceDetails,
                                key = { _, info -> "face_prev_${info.photo.id}" }
                            ) { idx, info ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(
                                        width = if (info.isGroupPhoto) 1.1.dp else 0.75.dp,
                                        color = if (info.isGroupPhoto) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier
                                        .width(86.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            inspectorInitialIndex = idx
                                        }
                                ) {
                                    Box(modifier = Modifier.padding(6.dp)) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Spacer(Modifier.height(4.dp))
                                            PersonAvatar(
                                                cropPath = info.primaryDetectedFace?.cropPath ?: person.coverCropPath,
                                                contentDescription = "Detected face",
                                                size = 54.dp,
                                                isPet = person.isPet,
                                                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            if (info.isGroupPhoto) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Groups,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Text(
                                                        text = "${info.totalFacesCount} faces",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        maxLines = 1
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    text = "1 face",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                        // 1-tap unselect if this face actually IS the right person!
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    viewModel.toggleSelection(info.photo.id)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Keep in ${person.displayTitle}",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                                .heightIn(max = 260.dp)
                        ) {
                            items(filteredCandidates, key = { "move_${it.personId}" }) { candidate ->
                                val alreadyInPhoto = candidate.personId in alreadyTaggedPersonIdsInSingle
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
                                        text = if (alreadyInPhoto) "In photo" else "${candidate.photoCount}",
                                        fontSize = 10.sp,
                                        fontWeight = if (alreadyInPhoto) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (alreadyInPhoto) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
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

        // Full-Screen Face Box Inspector Dialog (back returns straight to "Not <Name>?" sheet!)
        inspectorInitialIndex?.let { startIdx ->
            if (selectedFaceDetails.isNotEmpty()) {
                FaceBoxPhotoInspectorDialog(
                    photos = selectedFaceDetails,
                    initialIndex = startIdx.coerceIn(0, selectedFaceDetails.lastIndex),
                    currentPersonName = person.displayTitle,
                    onKeepPhoto = { photoId ->
                        viewModel.toggleSelection(photoId)
                        if (selectedFaceDetails.size <= 1) {
                            inspectorInitialIndex = null
                        }
                    },
                    onDismiss = { inspectorInitialIndex = null }
                )
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

internal data class FittedFaceRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

/**
 * Projects a normalized `[0..1]` face bounding box onto the exact pixel rectangle of a
 * `ContentScale.Fit` image drawn inside a `(containerW x containerH)` box.
 */
internal fun computeFittedFaceRect(
    containerW: Float,
    containerH: Float,
    imageW: Float,
    imageH: Float,
    bboxLeft: Float,
    bboxTop: Float,
    bboxRight: Float,
    bboxBottom: Float
): FittedFaceRect? {
    if (containerW <= 0f || containerH <= 0f || imageW <= 0f || imageH <= 0f) return null
    val scale = min(containerW / imageW, containerH / imageH)
    val drawnW = imageW * scale
    val drawnH = imageH * scale
    val offsetX = (containerW - drawnW) * 0.5f
    val offsetY = (containerH - drawnH) * 0.5f
    val l = offsetX + bboxLeft.coerceIn(0f, 1f) * drawnW
    val t = offsetY + bboxTop.coerceIn(0f, 1f) * drawnH
    val r = offsetX + bboxRight.coerceIn(0f, 1f) * drawnW
    val b = offsetY + bboxBottom.coerceIn(0f, 1f) * drawnH
    val w = (r - l).coerceAtLeast(1f)
    val h = (b - t).coerceAtLeast(1f)
    return FittedFaceRect(left = l, top = t, width = w, height = h)
}

@Composable
private fun FaceHighlightedPhotoOverlay(
    info: SelectedWrongPersonPhotoInfo,
    currentPersonName: String,
    compactLabels: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    val item = info.photo
    val imageModel = remember(item.id, item.localContentUri, item.thumbnailLink) {
        val data: Any? = when {
            item.isLocal && !item.localContentUri.isNullOrBlank() -> Uri.parse(item.localContentUri)
            else -> {
                val offlinePath = container.offlineMediaManager.getOfflineFilePath(item.id)
                val offlineFile = offlinePath?.let(::File)?.takeIf { it.exists() }
                offlineFile ?: DriveThumbnailRequest(
                    fileId = item.id,
                    thumbnailLink = item.thumbnailLink,
                    isUnmeteredWifi = true,
                    isVideo = item.isVideo,
                    sizeBytes = item.sizeBytes,
                    requestedMaxPx = 1200
                )
            }
        }
        ImageRequest.Builder(context)
            .data(data)
            .crossfade(true)
            .build()
    }

    var intrinsicSize by remember(item.id) {
        val fallbackW = (item.effectiveWidth ?: 0).toFloat()
        val fallbackH = (item.effectiveHeight ?: 0).toFloat()
        mutableStateOf(
            if (fallbackW > 0f && fallbackH > 0f) Size(fallbackW, fallbackH) else Size.Unspecified
        )
    }

    BoxWithConstraints(modifier = modifier) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()

        AsyncImage(
            model = imageModel,
            imageLoader = imageLoader,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            onSuccess = { success ->
                val s = success.painter.intrinsicSize
                if (s.width.isFinite() && s.height.isFinite() && s.width > 0f && s.height > 0f) {
                    intrinsicSize = s
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (intrinsicSize != Size.Unspecified && intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
            val otherRects = remember(info.otherFacesInPhoto, containerW, containerH, intrinsicSize) {
                info.otherFacesInPhoto.mapNotNull { f ->
                    computeFittedFaceRect(
                        containerW = containerW,
                        containerH = containerH,
                        imageW = intrinsicSize.width,
                        imageH = intrinsicSize.height,
                        bboxLeft = f.bboxLeft,
                        bboxTop = f.bboxTop,
                        bboxRight = f.bboxRight,
                        bboxBottom = f.bboxBottom
                    )?.let { f to it }
                }
            }
            val currentRects = remember(info.detectedFacesForCurrentPerson, containerW, containerH, intrinsicSize) {
                info.detectedFacesForCurrentPerson.mapNotNull { f ->
                    computeFittedFaceRect(
                        containerW = containerW,
                        containerH = containerH,
                        imageW = intrinsicSize.width,
                        imageH = intrinsicSize.height,
                        bboxLeft = f.bboxLeft,
                        bboxTop = f.bboxTop,
                        bboxRight = f.bboxRight,
                        bboxBottom = f.bboxBottom
                    )?.let { f to it }
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val otherStroke = 1.6.dp.toPx()
                val primaryGlow = 6.dp.toPx()
                val primaryStroke = 2.6.dp.toPx()
                val cornerPx = 10.dp.toPx()

                for ((_, rect) in otherRects) {
                    val cr = CornerRadius(min(cornerPx, min(rect.width, rect.height) * 0.25f))
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.65f),
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width, rect.height),
                        cornerRadius = cr,
                        style = Stroke(width = otherStroke)
                    )
                }

                for ((_, rect) in currentRects) {
                    val cr = CornerRadius(min(cornerPx, min(rect.width, rect.height) * 0.25f))
                    // Soft outer highlight halo
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.34f),
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width, rect.height),
                        cornerRadius = cr,
                        style = Stroke(width = primaryGlow)
                    )
                    // Crisp inner primary frame
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width, rect.height),
                        cornerRadius = cr,
                        style = Stroke(width = primaryStroke)
                    )
                }
            }

            // Translucent name tags under other faces in group photos
            val pillMarginPx = with(density) { 4.dp.toPx() }
            val approxPillW = with(density) { if (compactLabels) 90.dp.toPx() else 120.dp.toPx() }
            val approxPillH = with(density) { 22.dp.toPx() }

            for ((face, rect) in otherRects) {
                val label = face.personName.takeIf { it.isNotBlank() } ?: continue
                val pillX = (rect.left + rect.width * 0.5f - approxPillW * 0.5f)
                    .coerceIn(pillMarginPx, (containerW - approxPillW - pillMarginPx).coerceAtLeast(pillMarginPx))
                val belowY = rect.top + rect.height + pillMarginPx
                val pillY = if (belowY + approxPillH <= containerH - pillMarginPx) {
                    belowY
                } else {
                    (rect.top - approxPillH - pillMarginPx).coerceAtLeast(pillMarginPx)
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xB8000000),
                    modifier = Modifier.offset { IntOffset(pillX.roundToInt(), pillY.roundToInt()) }
                ) {
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = if (compactLabels) 9.5.sp else 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            // Bold accent pill on the exact face detected as the current person
            val targetPillW = with(density) { if (compactLabels) 118.dp.toPx() else 168.dp.toPx() }
            for ((_, rect) in currentRects) {
                val pillX = (rect.left + rect.width * 0.5f - targetPillW * 0.5f)
                    .coerceIn(pillMarginPx, (containerW - targetPillW - pillMarginPx).coerceAtLeast(pillMarginPx))
                val belowY = rect.top + rect.height + pillMarginPx
                val pillY = if (belowY + approxPillH <= containerH - pillMarginPx) {
                    belowY
                } else {
                    (rect.top - approxPillH - pillMarginPx).coerceAtLeast(pillMarginPx)
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = primaryColor,
                    shadowElevation = 4.dp,
                    modifier = Modifier.offset { IntOffset(pillX.roundToInt(), pillY.roundToInt()) }
                ) {
                    Text(
                        text = if (compactLabels) currentPersonName else "Detected as $currentPersonName",
                        color = onPrimaryColor,
                        fontSize = if (compactLabels) 10.sp else 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FaceBoxPhotoInspectorDialog(
    photos: List<SelectedWrongPersonPhotoInfo>,
    initialIndex: Int,
    currentPersonName: String,
    onKeepPhoto: (photoId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)),
        pageCount = { photos.size }
    )
    val activeInfo = photos.getOrNull(pagerState.currentPage) ?: photos.firstOrNull() ?: return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val info = photos[page]
                FaceHighlightedPhotoOverlay(
                    info = info,
                    currentPersonName = currentPersonName,
                    compactLabels = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 84.dp, bottom = 112.dp, start = 8.dp, end = 8.dp)
                )
            }

            // Top bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (activeInfo.isGroupPhoto) {
                            "Group photo · ${activeInfo.totalFacesCount} faces"
                        } else {
                            "Detected face"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = if (photos.size > 1) {
                            "Photo ${pagerState.currentPage + 1} of ${photos.size} · Highlighted face is tagged as $currentPersonName"
                        } else {
                            "Highlighted face is tagged as $currentPersonName"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Bottom legend & quick "Actually this IS <Name> — keep it" action
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    PersonAvatar(
                        cropPath = activeInfo.primaryDetectedFace?.cropPath,
                        contentDescription = null,
                        size = 44.dp,
                        borderColor = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Boxed face = $currentPersonName",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        val others = activeInfo.otherFacesInPhoto.map { it.personName }.filter { it.isNotBlank() }.distinct()
                        if (others.isNotEmpty()) {
                            Text(
                                text = "Also in photo: ${others.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.76f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    TextButton(onClick = { onKeepPhoto(activeInfo.photo.id) }) {
                        Text(
                            text = "Keep in $currentPersonName",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

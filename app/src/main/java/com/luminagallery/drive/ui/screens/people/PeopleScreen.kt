package com.luminagallery.drive.ui.screens.people

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.data.repository.ai.PersonMergeSuggestionUi
import com.luminagallery.drive.data.repository.ai.PersonSummary
import com.luminagallery.drive.data.repository.ai.SmartIndexProgress
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.PersonAvatar
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PeopleScreen(
    viewModel: PeopleViewModel,
    onNavigateBack: () -> Unit,
    onOpenPerson: (personId: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Background scan runs either way; permission only enables lock-screen progress notification */ }

    val startBackgroundScan: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.scanEntireLibrary()
    }

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showCombineDialog by remember { mutableStateOf(false) }
    var showRebuildConfirmDialog by remember { mutableStateOf(false) }
    var renameTargetPerson by remember { mutableStateOf<PersonSummary?>(null) }
    var fullPhotoPreview by remember { mutableStateOf<PersonPhotoPreviewRequest?>(null) }

    // Drag-to-combine state (hold & drag any person circle onto another person circle)
    var draggedPerson by remember { mutableStateOf<PersonSummary?>(null) }
    var dragPointerOffset by remember { mutableStateOf(Offset.Zero) }
    var hoveredMergeTargetId by remember { mutableStateOf<String?>(null) }
    var pendingDragCombinePair by remember { mutableStateOf<Pair<PersonSummary, PersonSummary>?>(null) }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    BackHandler(enabled = uiState.isSelectionMode || draggedPerson != null) {
        if (draggedPerson != null) {
            draggedPerson = null
            hoveredMergeTargetId = null
            autoScrollSpeed = 0f
        } else {
            viewModel.clearSelection()
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    val activeList = if (uiState.showHiddenSection) uiState.hiddenPersons else uiState.visiblePersons
    val activeById = remember(activeList) { activeList.associateBy { it.personId } }

    val latestActiveById by rememberUpdatedState(activeById)
    val latestIsSelectionMode by rememberUpdatedState(uiState.isSelectionMode)
    val latestCanDragCombine by rememberUpdatedState(!uiState.isSelectionMode && activeList.size >= 2)
    val dragTravelThresholdPx = remember(density) { with(density) { 18.dp.toPx() } }
    val autoScrollZonePx = remember(density) { with(density) { 64.dp.toPx() } }
    val maxAutoScrollSpeedPx = remember(density) { with(density) { 16.dp.toPx() } }
    val floatingAvatarSizeDp = 96.dp
    val floatingAvatarHalfPx = remember(density) { with(density) { (floatingAvatarSizeDp / 2).toPx() } }
    val floatingLiftUpPx = remember(density) { with(density) { 18.dp.toPx() } }
    val gridHorizontalPaddingPx = remember(density) { with(density) { 16.dp.toPx() } }

    fun findPersonAt(offset: Offset, excludePersonId: String? = null): PersonSummary? {
        val layoutInfo = gridState.layoutInfo
        val adjustedX = offset.x - gridHorizontalPaddingPx
        val adjustedY = offset.y - layoutInfo.beforeContentPadding
        val hit = layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
            val personId = itemInfo.key as? String ?: return@firstOrNull false
            if (personId == excludePersonId || personId !in latestActiveById) return@firstOrNull false
            val left = itemInfo.offset.x.toFloat()
            val top = itemInfo.offset.y.toFloat()
            val right = left + itemInfo.size.width
            val bottom = top + itemInfo.size.height
            adjustedX in left..right && adjustedY in top..bottom
        } ?: return null
        return latestActiveById[hit.key as? String]
    }

    fun findDropTargetPerson(pointerPos: Offset, excludePersonId: String): PersonSummary? =
        findPersonAt(pointerPos, excludePersonId = excludePersonId)
            ?: findPersonAt(Offset(pointerPos.x, pointerPos.y - floatingLiftUpPx), excludePersonId = excludePersonId)

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed == 0f) return@LaunchedEffect
        while (isActive && autoScrollSpeed != 0f) {
            gridState.scrollBy(autoScrollSpeed)
            val activeDrag = draggedPerson
            if (activeDrag != null) {
                val targetId = findDropTargetPerson(dragPointerOffset, excludePersonId = activeDrag.personId)?.personId
                if (targetId != hoveredMergeTargetId) {
                    hoveredMergeTargetId = targetId
                    if (targetId != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
            delay(16L)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { LuminaSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when {
                                uiState.isSelectionMode -> "${uiState.selectedPersonIds.size} selected"
                                uiState.showHiddenSection -> "Hidden people & pets"
                                else -> "People & pets"
                            },
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        val subtitle = when {
                            uiState.isSelectionMode -> "Tap people to combine or manage"
                            uiState.showHiddenSection -> "${uiState.hiddenPersons.size} hidden"
                            uiState.visiblePersons.size >= 2 -> "${uiState.visiblePersons.size} detected · Drag onto another to combine"
                            else -> "${uiState.visiblePersons.size} detected · Local & Drive"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.isSelectionMode) {
                                viewModel.clearSelection()
                            } else if (uiState.showHiddenSection) {
                                viewModel.toggleShowHidden()
                            } else {
                                onNavigateBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (uiState.isSelectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (!uiState.isSelectionMode && activeList.size >= 2) {
                        TextButton(onClick = { viewModel.enterSelectionMode() }) {
                            Icon(
                                imageVector = Icons.Rounded.Merge,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("Combine", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (!uiState.isSelectionMode) {
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiState.showHiddenSection) "Show active people"
                                            else "Hidden people (${uiState.hiddenPersons.size})"
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.toggleShowHidden()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Scan entire library") },
                                    onClick = {
                                        showOverflowMenu = false
                                        startBackgroundScan()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rebuild People & Objects") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showRebuildConfirmDialog = true
                                    }
                                )
                            }
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = if (uiState.isSelectionMode) 116.dp else 32.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(gridState) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            val hitPerson = findPersonAt(longPress.position) ?: return@awaitEachGesture

                            // If already in selection mode or only 1 person exists, toggle selection cleanly
                            // and consume UP on Initial so releasing never fires a phantom click!
                            if (!latestCanDragCombine) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (latestIsSelectionMode) {
                                    viewModel.togglePersonSelected(hitPerson.personId)
                                } else {
                                    viewModel.enterSelectionMode(hitPerson.personId)
                                }
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) {
                                        break
                                    }
                                }
                                return@awaitEachGesture
                            }

                            var lastPos = longPress.position
                            var totalTravelPx = 0f
                            draggedPerson = hitPerson
                            dragPointerOffset = longPress.position
                            hoveredMergeTargetId = null
                            autoScrollSpeed = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val activeChange = event.changes.firstOrNull { it.id == longPress.id }
                                        ?: event.changes.firstOrNull()
                                        ?: break
                                    val moved = activeChange.positionChangedIgnoreConsumed()
                                    val pos = activeChange.position
                                    val isPressed = activeChange.pressed
                                    event.changes.forEach { it.consume() }

                                    if (!isPressed) {
                                        val activeDragged = draggedPerson
                                        val dropTargetId = hoveredMergeTargetId
                                        val travel = totalTravelPx
                                        draggedPerson = null
                                        hoveredMergeTargetId = null
                                        autoScrollSpeed = 0f

                                        val dropTarget = dropTargetId?.let { latestActiveById[it] }
                                        when {
                                            activeDragged != null && dropTarget != null && dropTarget.personId != activeDragged.personId -> {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                pendingDragCombinePair = activeDragged to dropTarget
                                            }
                                            activeDragged != null && travel < dragTravelThresholdPx -> {
                                                viewModel.enterSelectionMode(activeDragged.personId)
                                            }
                                        }
                                        break
                                    }
                                    if (moved) {
                                        totalTravelPx += hypot(pos.x - lastPos.x, pos.y - lastPos.y)
                                        lastPos = pos
                                        dragPointerOffset = pos

                                        val nextTargetId = findDropTargetPerson(pos, excludePersonId = hitPerson.personId)?.personId
                                        if (nextTargetId != hoveredMergeTargetId) {
                                            hoveredMergeTargetId = nextTargetId
                                            if (nextTargetId != null) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }

                                        val viewportH = gridState.layoutInfo.viewportSize.height.toFloat()
                                        autoScrollSpeed = when {
                                            viewportH > 0f && pos.y < autoScrollZonePx -> {
                                                val f = ((autoScrollZonePx - pos.y) / autoScrollZonePx).coerceIn(0f, 1f)
                                                -maxAutoScrollSpeedPx * f
                                            }
                                            viewportH > 0f && pos.y > viewportH - autoScrollZonePx -> {
                                                val f = ((pos.y - (viewportH - autoScrollZonePx)) / autoScrollZonePx).coerceIn(0f, 1f)
                                                maxAutoScrollSpeedPx * f
                                            }
                                            else -> 0f
                                        }
                                    }
                                }
                            } catch (ce: CancellationException) {
                                draggedPerson = null
                                hoveredMergeTargetId = null
                                autoScrollSpeed = 0f
                                throw ce
                            }
                        }
                    }
            ) {
                // Live scan status banner (shown while scanning or if remaining unindexed items exist)
                val prog = uiState.progress
                if (prog.isIndexing || prog.isDownloadingModels || prog.remainingPhotos > 0) {
                    item(key = "smart_scan_banner", span = { GridItemSpan(maxLineSpan) }) {
                        SmartScanProgressBanner(
                            progress = prog,
                            onScanAll = startBackgroundScan,
                            onPause = viewModel::pauseScan
                        )
                    }
                }

                // Interactive "Same person?" merge suggestion card (Google Photos / Apple Photos style)
                val topSuggestion = uiState.mergeSuggestions.firstOrNull()
                if (!uiState.showHiddenSection && !uiState.isSelectionMode && topSuggestion != null) {
                    item(key = "merge_suggestion_card", span = { GridItemSpan(maxLineSpan) }) {
                        MergeSuggestionCard(
                            suggestion = topSuggestion,
                            totalCount = uiState.mergeSuggestions.size,
                            onInspectPerson = { pickedIdx ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                fullPhotoPreview = PersonPhotoPreviewRequest(
                                    people = listOf(topSuggestion.target, topSuggestion.source),
                                    initialIndex = pickedIdx
                                )
                            },
                            onCombine = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.acceptMergeSuggestion(topSuggestion)
                            },
                            onDismiss = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.dismissMergeSuggestion(topSuggestion)
                            }
                        )
                    }
                }

                if (activeList.isEmpty()) {
                    item(key = "people_empty_state", span = { GridItemSpan(maxLineSpan) }) {
                        PeopleEmptyState(
                            isHiddenView = uiState.showHiddenSection,
                            isScanning = prog.isIndexing || prog.isDownloadingModels,
                            onScanNow = startBackgroundScan
                        )
                    }
                } else {
                    // Show named, pinned & recurring (2+ photos) people first, followed by
                    // single-photo faces separated below a clean divider (always visible).
                    val recurringPeople = if (uiState.showHiddenSection) {
                        activeList
                    } else {
                        val primary = activeList.filter { it.isNamed || it.isFavorite || it.photoCount >= 2 }
                        if (primary.isNotEmpty()) primary else activeList
                    }
                    val singlePhotoFaces = if (uiState.showHiddenSection || recurringPeople.size == activeList.size) {
                        emptyList()
                    } else {
                        activeList.filterNot { it.isNamed || it.isFavorite || it.photoCount >= 2 }
                    }

                    items(
                        items = recurringPeople,
                        key = { it.personId }
                    ) { person ->
                        val isSelected = person.personId in uiState.selectedPersonIds
                        PersonGridCard(
                            person = person,
                            isSelected = isSelected,
                            isBeingDragged = draggedPerson?.personId == person.personId,
                            isDropTarget = hoveredMergeTargetId == person.personId,
                            onClick = {
                                if (draggedPerson != null) return@PersonGridCard
                                if (uiState.isSelectionMode) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.togglePersonSelected(person.personId)
                                } else {
                                    onOpenPerson(person.personId)
                                }
                            },
                            modifier = Modifier.animateItem()
                        )
                    }

                    if (singlePhotoFaces.isNotEmpty()) {
                        item(key = "other_faces_header", span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                HorizontalDivider(
                                    thickness = 0.8.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Other faces",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${singlePhotoFaces.size}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                                    )
                                }
                            }
                        }

                        items(
                            items = singlePhotoFaces,
                            key = { it.personId }
                        ) { person ->
                            val isSelected = person.personId in uiState.selectedPersonIds
                            PersonGridCard(
                                person = person,
                                isSelected = isSelected,
                                isBeingDragged = draggedPerson?.personId == person.personId,
                                isDropTarget = hoveredMergeTargetId == person.personId,
                                onClick = {
                                    if (draggedPerson != null) return@PersonGridCard
                                    if (uiState.isSelectionMode) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.togglePersonSelected(person.personId)
                                    } else {
                                        onOpenPerson(person.personId)
                                    }
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }

            // Floating drag-to-combine status pill at top while dragging a person circle
            AnimatedVisibility(
                visible = draggedPerson != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp, start = 20.dp, end = 20.dp)
            ) {
                val targetPerson = hoveredMergeTargetId?.let { activeById[it] }
                val isHoveringTarget = targetPerson != null
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isHoveringTarget) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f)
                    },
                    shadowElevation = 8.dp,
                    modifier = Modifier.border(
                        width = if (isHoveringTarget) 1.2.dp else 0.7.dp,
                        color = if (isHoveringTarget) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        },
                        shape = RoundedCornerShape(50)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Merge,
                            contentDescription = null,
                            tint = if (isHoveringTarget) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = when {
                                targetPerson == null -> "Drag onto another person to combine"
                                targetPerson.isNamed -> "Release to combine with ${targetPerson.name}"
                                else -> "Release to combine into 1 person"
                            },
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isHoveringTarget) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Floating lifted circular person avatar following finger smoothly at 120Hz
            draggedPerson?.let { lifting ->
                val ghostScale by animateFloatAsState(
                    targetValue = if (hoveredMergeTargetId != null) 0.92f else 1.08f,
                    animationSpec = spring(dampingRatio = 0.68f, stiffness = 500f),
                    label = "dragPersonGhostScale"
                )
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (dragPointerOffset.x - floatingAvatarHalfPx).roundToInt(),
                                y = (dragPointerOffset.y - floatingAvatarHalfPx - floatingLiftUpPx).roundToInt()
                            )
                        }
                        .graphicsLayer {
                            scaleX = ghostScale
                            scaleY = ghostScale
                            alpha = 0.95f
                        }
                        .shadow(16.dp, CircleShape)
                ) {
                    PersonAvatar(
                        cropPath = lifting.coverCropPath,
                        contentDescription = lifting.displayTitle,
                        size = floatingAvatarSizeDp,
                        borderColor = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Floating Combine / Manage bottom pill bar
            AnimatedVisibility(
                visible = uiState.isSelectionMode && uiState.selectedPersonIds.isNotEmpty(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                val count = uiState.selectedPersonIds.size
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            0.8.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            RoundedCornerShape(28.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (count >= 2) {
                            Button(
                                onClick = { showCombineDialog = true },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.weight(1.3f)
                            ) {
                                Icon(Icons.Rounded.Merge, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Combine ($count)", fontWeight = FontWeight.SemiBold)
                            }
                        } else if (count == 1) {
                            val single = activeList.firstOrNull { it.personId in uiState.selectedPersonIds }
                            FilledTonalButton(
                                onClick = { if (single != null) renameTargetPerson = single },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.weight(1.1f)
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Name", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        FilledTonalButton(
                            onClick = { viewModel.setSelectedHidden(!uiState.showHiddenSection) },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (uiState.showHiddenSection) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (uiState.showHiddenSection) "Unhide" else "Hide")
                        }
                    }
                }
            }
        }
    }

    // Drag-to-Combine confirmation dialog (dropping one person circle onto another)
    pendingDragCombinePair?.let { (dragged, target) ->
        val (primary, secondary) = remember(dragged.personId, target.personId) {
            PeopleViewModel.resolveCombineOrder(dragged = dragged, target = target)
        }
        val defaultName = remember(primary.personId, secondary.personId) {
            primary.name.ifBlank { secondary.name }
        }
        var combinedName by remember(primary.personId, secondary.personId) {
            mutableStateOf(defaultName)
        }

        AlertDialog(
            onDismissRequest = { pendingDragCombinePair = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = if (defaultName.isNotBlank()) "Combine into $defaultName?" else "Combine into 1 person?",
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    fullPhotoPreview = PersonPhotoPreviewRequest(
                                        people = listOf(primary, secondary),
                                        initialIndex = 0
                                    )
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            PersonAvatar(
                                cropPath = primary.coverCropPath,
                                contentDescription = primary.displayTitle,
                                size = 66.dp,
                                isPet = primary.isPet,
                                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = primary.displayTitle,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    fullPhotoPreview = PersonPhotoPreviewRequest(
                                        people = listOf(primary, secondary),
                                        initialIndex = 1
                                    )
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            PersonAvatar(
                                cropPath = secondary.coverCropPath,
                                contentDescription = secondary.displayTitle,
                                size = 66.dp,
                                isPet = secondary.isPet,
                                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = secondary.displayTitle,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        text = "Tap either photo to inspect full images before combining.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "All photos from both circles will be merged into one person, improving future recognition automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    OutlinedTextField(
                        value = combinedName,
                        onValueChange = { combinedName = it },
                        label = { Text("Person's name (optional)") },
                        placeholder = { Text("e.g. Alex, Mom…") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDragCombinePair = null
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.combineTwoPersons(
                            draggedPersonId = dragged.personId,
                            targetPersonId = target.personId,
                            combinedName = combinedName
                        )
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Merge, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Combine", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDragCombinePair = null }) { Text("Cancel") }
            }
        )
    }

    // Combine Selected People Dialog
    if (showCombineDialog) {
        val selectedPeople = remember(activeList, uiState.selectedPersonIds) {
            activeList.filter { it.personId in uiState.selectedPersonIds }
        }
        val defaultName = remember(selectedPeople) {
            selectedPeople.firstOrNull { it.isNamed }?.name.orEmpty()
        }
        var combinedName by remember(defaultName) { mutableStateOf(defaultName) }

        AlertDialog(
            onDismissRequest = { showCombineDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text("Combine ${selectedPeople.size} people?", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Tappable avatar preview row so you can inspect full photos before combining
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(selectedPeople, key = { _, p -> "comb_${p.personId}" }) { idx, p ->
                            PersonAvatar(
                                cropPath = p.coverCropPath,
                                contentDescription = p.displayTitle,
                                size = 56.dp,
                                isPet = p.isPet,
                                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        fullPhotoPreview = PersonPhotoPreviewRequest(
                                            people = selectedPeople,
                                            initialIndex = idx
                                        )
                                    }
                            )
                        }
                    }
                    Text(
                        text = "Tap any photo to inspect full images before combining.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "All photos from these groups will be merged into one person, improving future face recognition automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    OutlinedTextField(
                        value = combinedName,
                        onValueChange = { combinedName = it },
                        label = { Text("Person's name (optional)") },
                        placeholder = { Text("e.g. Alex, Mom…") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCombineDialog = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.combineSelectedPersons(combinedName)
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Combine", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCombineDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename single selected person dialog (with automatic duplicate-name merge check)
    renameTargetPerson?.let { person ->
        var draft by remember(person.personId) { mutableStateOf(person.name) }
        var duplicatePromptName by remember(person.personId) { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { renameTargetPerson = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text(if (person.isNamed) "Rename person" else "Name this person") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            duplicatePromptName = null
                        },
                        label = { Text("Name") },
                        placeholder = { Text("Enter name…") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (duplicatePromptName != null) {
                        Text(
                            text = "\"$duplicatePromptName\" already exists — tap Combine to merge both into one person.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                if (duplicatePromptName != null) {
                    Button(
                        onClick = {
                            renameTargetPerson = null
                            viewModel.renamePerson(person.personId, draft, mergeIfDuplicate = true)
                        }
                    ) { Text("Combine") }
                } else {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val dup = viewModel.findDuplicateNameTarget(draft, person.personId)
                                if (dup != null) {
                                    duplicatePromptName = dup
                                } else {
                                    renameTargetPerson = null
                                    viewModel.renamePerson(person.personId, draft, mergeIfDuplicate = false)
                                }
                            }
                        }
                    ) { Text("Save", fontWeight = FontWeight.SemiBold) }
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetPerson = null }) { Text("Cancel") }
            }
        )
    }

    if (showRebuildConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Rebuild People & Objects?") },
            text = {
                Text(
                    text = "This re-analyzes your local & Google Drive photos and videos cleanly on-device while automatically keeping your saved names and pinned people (locked folders are always skipped).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRebuildConfirmDialog = false
                        viewModel.rebuildAllSmartIndex()
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Rebuild", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Full-screen photo preview overlay on top of "Same person?" / Combine popups:
    // Pressing Back or tapping the back arrow returns right back to the popup with both people!
    fullPhotoPreview?.let { request ->
        PersonFullPhotoPreviewDialog(
            people = request.people,
            initialPersonIndex = request.initialIndex,
            getPhotosForPerson = viewModel::getPhotosForPerson,
            onDismiss = { fullPhotoPreview = null }
        )
    }
}

private data class PersonPhotoPreviewRequest(
    val people: List<PersonSummary>,
    val initialIndex: Int
)

@Composable
private fun PersonGridCard(
    person: PersonSummary,
    isSelected: Boolean,
    isBeingDragged: Boolean,
    isDropTarget: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardScale by animateFloatAsState(
        targetValue = when {
            isDropTarget -> 1.11f
            isBeingDragged -> 0.90f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.66f, stiffness = 460f),
        label = "personCardScale"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (isBeingDragged) 0.24f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "personCardAlpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
                alpha = cardAlpha
            }
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isDropTarget) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                else MaterialTheme.colorScheme.background.copy(alpha = 0f)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        PersonAvatar(
            cropPath = person.coverCropPath,
            contentDescription = person.displayTitle,
            size = 90.dp,
            isSelected = isSelected,
            isFavorite = person.isFavorite,
            isPet = person.isPet,
            borderColor = if (isDropTarget) MaterialTheme.colorScheme.primary else null
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                isDropTarget -> "Drop to combine"
                person.isNamed -> person.name
                else -> "Add name"
            },
            fontSize = 13.5.sp,
            fontWeight = if (person.isNamed || isDropTarget) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                isDropTarget -> MaterialTheme.colorScheme.primary
                person.isNamed -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.primary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (person.photoCount == 1) "1 photo" else "${person.photoCount} photos",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun MergeSuggestionCard(
    suggestion: PersonMergeSuggestionUi,
    totalCount: Int,
    onInspectPerson: (index: Int) -> Unit,
    onCombine: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                RoundedCornerShape(22.dp)
            )
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
                Column {
                    Text(
                        text = if (suggestion.target.isPet) "Same pet?" else "Same person?",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tap either photo to inspect full image",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (totalCount > 1) {
                    Text(
                        text = "1 of $totalCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onInspectPerson(0) }
                        .padding(vertical = 4.dp)
                ) {
                    PersonAvatar(
                        cropPath = suggestion.target.coverCropPath,
                        contentDescription = suggestion.target.displayTitle,
                        size = 64.dp,
                        isFavorite = suggestion.target.isFavorite,
                        isPet = suggestion.target.isPet,
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = suggestion.target.displayTitle,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${suggestion.target.photoCount} items",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.CompareArrows,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onInspectPerson(1) }
                        .padding(vertical = 4.dp)
                ) {
                    PersonAvatar(
                        cropPath = suggestion.source.coverCropPath,
                        contentDescription = suggestion.source.displayTitle,
                        size = 64.dp,
                        isFavorite = suggestion.source.isFavorite,
                        isPet = suggestion.source.isPet,
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = suggestion.source.displayTitle,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${suggestion.source.photoCount} items",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Not the same", fontSize = 13.sp)
                }
                Button(
                    onClick = onCombine,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Merge, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Combine", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SmartScanProgressBanner(
    progress: SmartIndexProgress,
    onScanAll: () -> Unit,
    onPause: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                0.7.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = when {
                                progress.isDownloadingModels -> "Preparing on-device AI models…"
                                progress.isIndexing -> progress.statusText ?: "Analyzing photos & videos on-device…"
                                !progress.statusText.isNullOrBlank() -> progress.statusText
                                else -> "${progress.remainingPhotos} more items ready to scan"
                            },
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${progress.totalIndexedPhotos}/${progress.totalEligiblePhotos} indexed · Local & Drive · Locked skipped",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (progress.isIndexing) {
                    IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Pause, contentDescription = "Pause scan", modifier = Modifier.size(18.dp))
                    }
                } else if (!progress.isDownloadingModels) {
                    FilledTonalButton(
                        onClick = onScanAll,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Scan all", fontSize = 12.sp)
                    }
                }
            }

            if (progress.isIndexing || progress.isDownloadingModels) {
                val frac = if (progress.totalEligiblePhotos > 0) {
                    (progress.totalIndexedPhotos.toFloat() / progress.totalEligiblePhotos).coerceIn(0f, 1f)
                } else 0f
                if (progress.isDownloadingModels || frac <= 0.01f) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun PeopleEmptyState(
    isHiddenView: Boolean,
    isScanning: Boolean,
    onScanNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isHiddenView) Icons.Rounded.VisibilityOff else Icons.Rounded.Face,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
        }
        Text(
            text = when {
                isHiddenView -> "No hidden people"
                isScanning -> "Finding faces on your device…"
                else -> "No people grouped yet"
            },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (isHiddenView) {
                "People you hide from the main People list will appear here so you can restore them anytime."
            } else {
                "Lumina recognizes faces and objects 100% locally across your phone and connected Google Drive folders — your photos never leave your device."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (!isHiddenView) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "Locked & hidden folders are always skipped",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isScanning) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onScanNow, shape = RoundedCornerShape(20.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan photos now", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Full-screen high-resolution photo lightbox opened right on top of "Same person?" / Combine
 * popups so you can inspect full photos (swipe through all photos of each person, pinch or
 * double-tap to zoom, and switch between the two people to compare) — and pressing Back or
 * tapping the back arrow returns straight back to the popup!
 */
@Composable
private fun PersonFullPhotoPreviewDialog(
    people: List<PersonSummary>,
    initialPersonIndex: Int,
    getPhotosForPerson: (PersonSummary) -> List<MediaItem>,
    onDismiss: () -> Unit
) {
    if (people.isEmpty()) return
    val context = LocalContext.current
    val appContainer = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current

    var activePersonIndex by remember(people, initialPersonIndex) {
        mutableIntStateOf(initialPersonIndex.coerceIn(0, people.lastIndex))
    }
    val activePerson = people[activePersonIndex]
    val photos = remember(activePerson.personId, activePerson.photoIds) {
        getPhotosForPerson(activePerson)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (photos.isEmpty()) {
                // Fallback to larger avatar preview if full media isn't cached yet
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    PersonAvatar(
                        cropPath = activePerson.coverCropPath,
                        contentDescription = activePerson.displayTitle,
                        size = 200.dp,
                        isPet = activePerson.isPet
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Text(
                            text = activePerson.displayTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }
                }
            } else {
                val pagerState = rememberPagerState(
                    initialPage = 0,
                    pageCount = { photos.size }
                )
                LaunchedEffect(activePerson.personId) {
                    pagerState.scrollToPage(0)
                }

                HorizontalPager(
                    state = pagerState,
                    key = { idx -> "${activePerson.personId}_${photos[idx].id}" },
                    modifier = Modifier.fillMaxSize()
                ) { pageIdx ->
                    val item = photos[pageIdx]
                    var scale by remember(item.id) { mutableFloatStateOf(1f) }
                    var panOffset by remember(item.id) { mutableStateOf(Offset.Zero) }
                    var containerSize by remember { mutableStateOf(IntSize.Zero) }

                    val request = remember(item.id, item.localContentUri, item.thumbnailLink, item.isVideo) {
                        val data: Any? = when {
                            item.isLocal && !item.localContentUri.isNullOrBlank() && !item.isVideo ->
                                Uri.parse(item.localContentUri)
                            item.isLocal && !item.localContentUri.isNullOrBlank() ->
                                LocalThumbnailRequest(item.localContentUri, isVideo = true)
                            else -> {
                                val offlinePath = if (!item.isVideo) {
                                    appContainer.offlineMediaManager.getOfflineFilePath(item.id)
                                } else null
                                val offlineFile = offlinePath?.let(::File)?.takeIf { it.exists() }
                                offlineFile ?: DriveThumbnailRequest(
                                    fileId = item.id,
                                    thumbnailLink = item.thumbnailLink,
                                    isUnmeteredWifi = true,
                                    isVideo = item.isVideo,
                                    sizeBytes = item.sizeBytes,
                                    requestedMaxPx = 1600
                                )
                            }
                        }
                        ImageRequest.Builder(context)
                            .data(data)
                            .size(1600)
                            .crossfade(true)
                            .build()
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { containerSize = it }
                            .pointerInput(item.id) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            panOffset = Offset.Zero
                                        } else {
                                            scale = 2.4f
                                        }
                                    }
                                )
                            }
                            .pointerInput(item.id) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressedCount = event.changes.count { it.pressed }
                                        if (pressedCount == 0) break
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                        val isPinching = pressedCount >= 2 && (zoomChange != 1f || centroidSize > 0f)
                                        if (isPinching || scale > 1.02f) {
                                            val nextScale = (scale * zoomChange).coerceIn(1f, 4.5f)
                                            val maxX = (containerSize.width * (nextScale - 1f)) * 0.5f
                                            val maxY = (containerSize.height * (nextScale - 1f)) * 0.5f
                                            scale = nextScale
                                            panOffset = if (nextScale <= 1.02f) {
                                                Offset.Zero
                                            } else {
                                                Offset(
                                                    x = (panOffset.x + panChange.x).coerceIn(-maxX, maxX),
                                                    y = (panOffset.y + panChange.y).coerceIn(-maxY, maxY)
                                                )
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = request,
                            imageLoader = imageLoader,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = panOffset.x
                                    translationY = panOffset.y
                                }
                        )
                    }
                }

                // Top bar scrim with Back button + person & photo info
                val currentPhoto = photos.getOrNull(pagerState.currentPage)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
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
                                text = activePerson.displayTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val datePart = currentPhoto?.fullDateLabel?.takeIf { it.isNotBlank() }
                            val metaLine = buildString {
                                append("${pagerState.currentPage + 1} of ${photos.size}")
                                if (datePart != null) append(" · $datePart")
                            }
                            Text(
                                text = metaLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Bottom quick-switch strip when comparing 2+ people so you can flip between them in 1 tap!
            if (people.size >= 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                            )
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        itemsIndexed(people, key = { _, p -> "switch_${p.personId}" }) { idx, person ->
                            val isActive = idx == activePersonIndex
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.White.copy(alpha = 0.14f)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .border(
                                        width = if (isActive) 1.5.dp else 0.7.dp,
                                        color = if (isActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.White.copy(alpha = 0.25f)
                                        },
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable { activePersonIndex = idx }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
                                ) {
                                    PersonAvatar(
                                        cropPath = person.coverCropPath,
                                        contentDescription = person.displayTitle,
                                        size = 30.dp,
                                        isPet = person.isPet
                                    )
                                    Column {
                                        Text(
                                            text = person.displayTitle,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isActive) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                Color.White
                                            },
                                            maxLines = 1
                                        )
                                        Text(
                                            text = if (person.photoCount == 1) "1 photo" else "${person.photoCount} photos",
                                            fontSize = 10.sp,
                                            color = if (isActive) {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                                            } else {
                                                Color.White.copy(alpha = 0.72f)
                                            },
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

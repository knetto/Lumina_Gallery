package com.luminagallery.drive.ui.screens.folder

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MoveUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.LocalFileOperationsManager
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.AlbumGridItem
import com.luminagallery.drive.domain.model.AlbumMediaFilter
import com.luminagallery.drive.domain.model.AlbumMediaGroupBy
import com.luminagallery.drive.domain.model.AlbumMediaSort
import com.luminagallery.drive.domain.model.BreadcrumbItem
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.FolderViewMode
import com.luminagallery.drive.domain.model.GalleryStory
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.SidebarAlbumNode
import com.luminagallery.drive.domain.model.StoryCategory
import kotlin.math.abs
import kotlin.math.roundToInt
import com.luminagallery.drive.ui.components.BiometricLockHelper
import com.luminagallery.drive.ui.components.CollagePdfBottomSheet
import com.luminagallery.drive.ui.components.CollectionCoverArtwork
import com.luminagallery.drive.ui.components.ExifMetadataBottomSheet
import com.luminagallery.drive.ui.components.FastScrollThumb
import com.luminagallery.drive.ui.components.FolderCard
import com.luminagallery.drive.ui.components.FolderCoverArtwork
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.HyperOsTrashConfirmSheet
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.MediaGridItem
import com.luminagallery.drive.ui.components.MediaGridShimmer
import com.luminagallery.drive.ui.components.MoveCopyToAlbumSheet
import com.luminagallery.drive.ui.components.MultiSelectActionBar
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.local.HiddenLockedFolderManager
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.ui.components.OneUiMotion
import com.luminagallery.drive.ui.components.PinchZoomGridContainer
import com.luminagallery.drive.ui.components.SecureWindowEffect
import com.luminagallery.drive.ui.components.TrashConfirmMode
import com.luminagallery.drive.ui.components.oneUiClickable
import com.luminagallery.drive.ui.components.photoGridDragSelect
import com.luminagallery.drive.ui.components.prefetchAlbumThumbnails
import com.luminagallery.drive.ui.screens.stories.StorySlideshowDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SidebarEasing = OneUiMotion.FluidEasing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    viewModel: FolderViewModel,
    lastViewedItemId: String?,
    onConsumedLastViewedItemId: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenSubfolder: (folderId: String, folderName: String) -> Unit,
    onOpenBreadcrumb: (folderId: String, folderName: String) -> Unit,
    onOpenViewer: (
        folderId: String,
        itemId: String,
        isFlattened: Boolean,
        filter: AlbumMediaFilter,
        sort: AlbumMediaSort
    ) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCompare: (List<String>) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContainer = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val gridStatesByFolder = remember { mutableMapOf<String, LazyGridState>() }
    val snackbarHostState = remember { SnackbarHostState() }

    val offlineIds by appContainer.offlineMediaManager.offlineItemIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val customCovers by appContainer.galleryExtrasRepository.customAlbumCoversFlow
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showGroupByDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showSlideshow by remember { mutableStateOf(false) }
    var showMoveCopySheet by remember { mutableStateOf(false) }
    var moveSheetInitialMoveMode by remember { mutableStateOf(false) }
    var moveSheetOverrideItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var showCollagePdfSheet by remember { mutableStateOf(false) }
    var showTrashConfirmLocal by remember { mutableStateOf(false) }
    var showRenameAlbumDialog by remember { mutableStateOf(false) }
    var pendingAlbumRenameName by remember { mutableStateOf<String?>(null) }
    var showAllFilesAccessLockDialog by remember { mutableStateOf(false) }
    var pendingAllFilesAction by remember { mutableStateOf<String?>(null) }

    // State for dragging local photos/videos out to the parent folder ("moeder folder") or sidebar albums
    val density = LocalDensity.current
    var activePhotoDrag by remember { mutableStateOf<ActivePhotoDragState?>(null) }
    var pendingDragMove by remember { mutableStateOf<PendingPhotoMoveRequest?>(null) }
    var rootBoxOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var gridOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    val topDropZoneRects = remember { mutableStateMapOf<String, Rect>() }
    val sidebarAlbumDropTargets = remember { mutableStateMapOf<String, SidebarDropTarget>() }
    val sidebarCollapsedGroupRects = remember { mutableStateMapOf<String, Rect>() }
    var hoveredPhotoDropTargetKey by remember { mutableStateOf<String?>(null) }
    var hoveredCollapsedGroupId by remember { mutableStateOf<String?>(null) }
    var springLoadedExpandGroupId by remember { mutableStateOf<String?>(null) }

    fun executeLocalPhotoMoveNow(
        items: List<MediaItem>,
        targetName: String,
        useDcimParent: Boolean,
        targetRelPath: String?
    ) {
        scope.launch {
            appContainer.localFileOperationsManager.moveLocalItemsToAlbum(
                items = items,
                albumFolderName = targetName,
                useDcimParent = useDcimParent,
                targetRelativePath = targetRelPath
            ).onSuccess { count ->
                appContainer.localMediaRepository.notifyPermissionChanged()
                viewModel.clearSelection()
                viewModel.showMessage(
                    if (count == 1) "Moved 1 item to $targetName" else "Moved $count items to $targetName"
                )
            }.onFailure { err ->
                viewModel.showMessage(err.localizedMessage ?: "Failed to move item(s)")
            }
        }
    }

    val photoMovePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingDragMove
        pendingDragMove = null
        if (result.resultCode == Activity.RESULT_OK && pending != null) {
            executeLocalPhotoMoveNow(
                items = pending.items,
                targetName = pending.targetName,
                useDcimParent = pending.useDcimParent,
                targetRelPath = pending.targetRelativePath
            )
        } else {
            viewModel.showMessage("Move cancelled")
        }
    }

    fun startLocalPhotoMove(
        items: List<MediaItem>,
        targetName: String,
        useDcimParent: Boolean,
        targetRelPath: String?
    ) {
        val movable = items.filter { it.isLocal && !HiddenLockedFolderManager.isHiddenFileUri(it.localContentUri) }
        if (movable.isEmpty()) {
            viewModel.showMessage("Unlock private album first to move items")
            return
        }
        val sender = appContainer.localFileOperationsManager.buildWritePermissionSender(movable)
        if (sender != null) {
            pendingDragMove = PendingPhotoMoveRequest(
                items = movable,
                targetName = targetName,
                useDcimParent = useDcimParent,
                targetRelativePath = targetRelPath
            )
            photoMovePermissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            executeLocalPhotoMoveNow(movable, targetName, useDcimParent, targetRelPath)
        }
    }

    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val action = pendingAllFilesAction
        pendingAllFilesAction = null
        val granted = appContainer.hiddenLockedFolderManager.hasAllFilesAccess()
        when (action) {
            "lock" -> {
                if (granted) {
                    viewModel.lockCurrentAlbum(hideOnDisk = true, onLocked = onNavigateBack)
                } else {
                    viewModel.showMessage("All files access not granted — album was not hidden")
                }
            }
            "unlock" -> {
                if (granted) {
                    viewModel.unlockCurrentAlbum()
                } else {
                    viewModel.showMessage("Grant All files access to restore this hidden folder")
                }
            }
        }
    }

    SecureWindowEffect(enabled = uiState.isProtectedPrivateAlbum)

    fun executeAlbumRename(targetName: String) {
        val itemsToRename = uiState.unfilteredMediaItems.ifEmpty { uiState.mediaItems }
        scope.launch {
            appContainer.localFileOperationsManager.renameLocalAlbum(itemsToRename, targetName)
                .onSuccess { cleanName ->
                    appContainer.localMediaRepository.notifyPermissionChanged()
                    viewModel.onLocalAlbumRenamed(cleanName)
                }
                .onFailure { err ->
                    viewModel.showMessage(err.localizedMessage ?: "Unable to rename album")
                }
        }
    }

    val renameAlbumPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val target = pendingAlbumRenameName
        pendingAlbumRenameName = null
        if (result.resultCode == Activity.RESULT_OK && !target.isNullOrBlank()) {
            executeAlbumRename(target)
        } else {
            viewModel.showMessage("Rename cancelled")
        }
    }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onTrashCompleted(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(uiState.pendingTrashIntentSender) {
        val sender = uiState.pendingTrashIntentSender ?: return@LaunchedEffect
        trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    LaunchedEffect(lastViewedItemId, uiState.gridEntries, uiState.folderId) {
        val targetId = lastViewedItemId ?: return@LaunchedEffect
        val activeGridState = gridStatesByFolder[uiState.folderId] ?: return@LaunchedEffect
        val targetIndex = uiState.gridEntries.indexOfFirst { entry ->
            entry is FolderGridEntry.MediaCell && entry.item.id == targetId
        }
        if (targetIndex >= 0) {
            val visibleIndices = activeGridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (targetIndex !in visibleIndices) {
                activeGridState.scrollToItem(targetIndex)
            }
            onConsumedLastViewedItemId()
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

    val mediaBreakdownSubtitle = remember(uiState.mediaItems, uiState.subfolders.size, uiState.viewMode) {
        FormatUtils.formatMediaBreakdown(
            items = uiState.mediaItems,
            subfolderCount = if (uiState.viewMode == FolderViewMode.HIERARCHY) uiState.subfolders.size else 0
        )
    }

    val hasSidebarContent = uiState.sidebarNodes.isNotEmpty()
    val sidebarAlbumOrder = remember(uiState.sidebarNodes) {
        flattenSidebarAlbumOrder(uiState.sidebarNodes)
    }
    // Just like Samsung Gallery: while browsing a folder-of-folders (nested container albums)
    // with no direct photos, show a full-width album grid without the left sidebar!
    val isFolderContainerOnly = uiState.viewMode == FolderViewMode.HIERARCHY &&
        uiState.subfolders.isNotEmpty() &&
        uiState.mediaItems.isEmpty()
    val canHaveSidebar = hasSidebarContent && !isFolderContainerOnly
    val effectiveShowSidebar = uiState.showSidebar && canHaveSidebar
    val effectiveColumns = if (effectiveShowSidebar) {
        (uiState.gridColumns - 1).coerceIn(1, 5)
    } else {
        uiState.gridColumns.coerceIn(1, 6)
    }

    val sidebarMaxWidthDp = 94.dp
    val sidebarMaxWidthPx = with(density) { sidebarMaxWidthDp.toPx() }
    val sidebarFraction = remember {
        Animatable(if (effectiveShowSidebar) 1f else 0f)
    }
    var sidebarAutoOpenedByDrag by remember { mutableStateOf(false) }

    LaunchedEffect(effectiveShowSidebar) {
        sidebarFraction.animateTo(
            targetValue = if (effectiveShowSidebar) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 430f
            )
        )
    }

    // Spring-loaded folder auto-expand when hovering dragged photo(s) over a collapsed group in sidebar
    LaunchedEffect(hoveredCollapsedGroupId, activePhotoDrag != null) {
        val groupId = hoveredCollapsedGroupId
        if (groupId != null && activePhotoDrag != null) {
            delay(420L)
            springLoadedExpandGroupId = groupId
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Resolve parent ("moeder") storage folder for local albums (e.g. DCIM/Vakantie/Dag1 -> DCIM/Vakantie)
    val parentLocalFolder = remember(uiState.isLocalAlbum, uiState.unfilteredMediaItems, uiState.mediaItems) {
        if (!uiState.isLocalAlbum) null
        else {
            val sample = uiState.unfilteredMediaItems.firstOrNull() ?: uiState.mediaItems.firstOrNull()
            LocalFileOperationsManager.resolveParentLocalFolder(sample?.storagePath)
        }
    }
    val canDragMovePhotos = uiState.isLocalAlbum && !uiState.isLockedAlbum && uiState.mediaItems.isNotEmpty()
    val latestFolderId by rememberUpdatedState(uiState.folderId)
    val latestCanHaveSidebar by rememberUpdatedState(canHaveSidebar)
    val latestEffectiveShowSidebar by rememberUpdatedState(effectiveShowSidebar)
    val latestIsPhotoDragActive by rememberUpdatedState(activePhotoDrag != null)
    val latestParentLocalFolder by rememberUpdatedState(parentLocalFolder)
    val latestSidebarWidthPx by rememberUpdatedState(sidebarMaxWidthPx)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { LuminaSnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        AnimatedContent(
                            targetState = uiState.folderId to uiState.folderName,
                            transitionSpec = {
                                val fromIdx = sidebarAlbumOrder.indexOf(initialState.first)
                                val toIdx = sidebarAlbumOrder.indexOf(targetState.first)
                                val dir = if (fromIdx >= 0 && toIdx >= 0 && toIdx < fromIdx) -1 else 1
                                (slideInVertically(tween(260, easing = OneUiMotion.FluidEasing)) { dir * 18 } +
                                    fadeIn(tween(200, easing = OneUiMotion.FluidEasing)))
                                    .togetherWith(
                                        slideOutVertically(tween(170, easing = OneUiMotion.FluidEasing)) { -dir * 14 } +
                                            fadeOut(tween(130))
                                    )
                            },
                            label = "folderTitleTransition"
                        ) { (_, titleText) ->
                            Column {
                                Text(
                                    text = titleText,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = mediaBreakdownSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(38.dp)
                                .oneUiClickable(pressedScale = 0.88f, hoveredScale = 1.06f, onClick = onNavigateBack)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    actions = {
                        if (uiState.isSelectionMode) {
                            IconButton(onClick = viewModel::selectAllVisible) {
                                Icon(
                                    imageVector = Icons.Rounded.SelectAll,
                                    contentDescription = "Select all"
                                )
                            }
                        } else {
                            IconButton(onClick = onOpenSearch) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = "Search"
                                )
                            }
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = "More options"
                                    )
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false },
                                    shape = RoundedCornerShape(22.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Select") },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.enterSelectionMode()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            val suffix = if (uiState.mediaFilter != AlbumMediaFilter.ALL) {
                                                " (${uiState.mediaFilter.label})"
                                            } else ""
                                            Text("Filter$suffix")
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            showFilterDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Group by") },
                                        onClick = {
                                            showOverflowMenu = false
                                            showGroupByDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort") },
                                        onClick = {
                                            showOverflowMenu = false
                                            showSortDialog = true
                                        }
                                    )
                                    if (uiState.mediaItems.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("Start slideshow") },
                                            onClick = {
                                                showOverflowMenu = false
                                                showSlideshow = true
                                            }
                                        )
                                    }
                                    if (hasSidebarContent && !isFolderContainerOnly) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(if (uiState.showSidebar) "Hide album sidebar" else "Show album sidebar")
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                viewModel.toggleSidebar()
                                            }
                                        )
                                    }
                                    if (!uiState.isLocalAlbum) {
                                        val isFlattened = uiState.viewMode == FolderViewMode.FLATTENED
                                        DropdownMenuItem(
                                            text = {
                                                Text(if (isFlattened) "Show folder tree" else "Flatten all nested media")
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                viewModel.setViewMode(
                                                    if (isFlattened) FolderViewMode.HIERARCHY
                                                    else FolderViewMode.FLATTENED
                                                )
                                            }
                                        )
                                        if (uiState.mediaItems.isNotEmpty()) {
                                            val allOffline = uiState.mediaItems.all { it.id in offlineIds }
                                            DropdownMenuItem(
                                                text = {
                                                    Text(if (allOffline) "Remove offline copies" else "Keep album offline")
                                                },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    val targetFolderId = uiState.folderId
                                                    val targetItems = uiState.mediaItems
                                                    scope.launch {
                                                        if (allOffline) {
                                                            appContainer.offlineMediaManager.unpinFolderOffline(targetFolderId)
                                                            viewModel.showMessage("Removed offline copies for ${uiState.folderName}")
                                                        } else {
                                                            viewModel.showMessage("Downloading ${targetItems.size} items for offline viewing…")
                                                            val res = appContainer.offlineMediaManager.pinFolderOffline(
                                                                folderId = targetFolderId,
                                                                items = targetItems
                                                            )
                                                            res.onSuccess { count ->
                                                                viewModel.showMessage("Pinned $count item${if (count == 1) "" else "s"} offline")
                                                            }.onFailure { err ->
                                                                viewModel.showMessage(err.localizedMessage ?: "Offline download failed")
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    if (uiState.isLocalAlbum && !uiState.isLockedAlbum && uiState.mediaItems.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("Rename album") },
                                            onClick = {
                                                showOverflowMenu = false
                                                showRenameAlbumDialog = true
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (uiState.isLockedAlbum) "Unlock" else "Lock") },
                                        onClick = {
                                            showOverflowMenu = false
                                            val wantLock = !uiState.isLockedAlbum
                                            val albumTitle = uiState.folderName
                                            val isLocal = uiState.isLocalAlbum
                                            val folderId = uiState.folderId
                                            BiometricLockHelper.authenticate(
                                                context = context,
                                                title = if (wantLock) "Lock album" else "Unlock album",
                                                subtitle = if (wantLock) {
                                                    "Verify your identity to lock \"$albumTitle\""
                                                } else {
                                                    "Verify your identity to unlock \"$albumTitle\""
                                                },
                                                onSuccess = {
                                                    if (wantLock) {
                                                        if (!isLocal || appContainer.hiddenLockedFolderManager.hasAllFilesAccess()) {
                                                            viewModel.lockCurrentAlbum(
                                                                hideOnDisk = isLocal,
                                                                onLocked = onNavigateBack
                                                            )
                                                        } else {
                                                            showAllFilesAccessLockDialog = true
                                                        }
                                                    } else {
                                                        scope.launch {
                                                            val entity = appContainer.galleryExtrasRepository.getLockedAlbum(folderId)
                                                            if (entity?.isHiddenOnDisk == true &&
                                                                !appContainer.hiddenLockedFolderManager.hasAllFilesAccess()
                                                            ) {
                                                                pendingAllFilesAction = "unlock"
                                                                allFilesAccessLauncher.launch(
                                                                    appContainer.hiddenLockedFolderManager.buildAllFilesAccessIntent()
                                                                )
                                                            } else {
                                                                viewModel.unlockCurrentAlbum()
                                                            }
                                                        }
                                                    }
                                                },
                                                onFailure = { err -> viewModel.showMessage(err) }
                                            )
                                        }
                                    )
                                    if (uiState.folderId in customCovers) {
                                        DropdownMenuItem(
                                            text = { Text("Reset album cover") },
                                            onClick = {
                                                showOverflowMenu = false
                                                val targetFolderId = uiState.folderId
                                                scope.launch {
                                                    appContainer.galleryExtrasRepository.clearCustomAlbumCover(targetFolderId)
                                                    viewModel.showMessage("Album cover reset to default")
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )

                if (!uiState.isLocalAlbum && uiState.breadcrumbs.size > 1) {
                    BreadcrumbBar(
                        breadcrumbs = uiState.breadcrumbs,
                        onHomeClick = onNavigateHome,
                        onBreadcrumbClick = { crumb ->
                            if (crumb.id != uiState.folderId) {
                                onOpenBreadcrumb(crumb.id, crumb.name)
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        val edgeRevealThresholdPx = with(density) { 54.dp.toPx() }
        val topDropZoneOtherKey = "__photo_drop_other__"
        val topDropZoneParentKey = "__photo_drop_parent__"

        fun updatePhotoDragHover(localGridOffset: Offset) {
            val pointerInRoot = gridOriginInRoot + localGridOffset
            val pointerInBox = pointerInRoot - rootBoxOriginInRoot
            activePhotoDrag = activePhotoDrag?.copy(pointerInBox = pointerInBox)

            // Auto-reveal sidebar if hidden and dragging near the left edge
            if (canHaveSidebar && !effectiveShowSidebar && pointerInBox.x <= edgeRevealThresholdPx && !sidebarAutoOpenedByDrag) {
                sidebarAutoOpenedByDrag = true
                scope.launch {
                    sidebarFraction.animateTo(
                        1f,
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 430f)
                    )
                }
            }

            // 1. Check top parent/other drop zones first
            val hitTopKey = topDropZoneRects.entries.firstOrNull { (_, rect) ->
                rect.contains(pointerInRoot)
            }?.key
            if (hitTopKey != null) {
                hoveredCollapsedGroupId = null
                if (hoveredPhotoDropTargetKey != hitTopKey) {
                    hoveredPhotoDropTargetKey = hitTopKey
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                return
            }

            // 2. Check local album targets in the sidebar rail (only when sidebar is visible)
            val sidebarVisibleForDrop = sidebarFraction.value > 0.35f
            val hitSidebarKey = if (sidebarVisibleForDrop) {
                sidebarAlbumDropTargets.entries.firstOrNull { (id, target) ->
                    id != latestFolderId && target.boundsInRoot.contains(pointerInRoot)
                }?.key
            } else {
                null
            }
            if (hitSidebarKey != null) {
                hoveredCollapsedGroupId = null
                if (hoveredPhotoDropTargetKey != hitSidebarKey) {
                    hoveredPhotoDropTargetKey = hitSidebarKey
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                return
            }

            // 3. Check collapsed sidebar groups for spring-loaded auto-expand
            hoveredPhotoDropTargetKey = null
            val hitGroupId = if (sidebarVisibleForDrop) {
                sidebarCollapsedGroupRects.entries.firstOrNull { (_, rect) ->
                    rect.contains(pointerInRoot)
                }?.key
            } else {
                null
            }
            hoveredCollapsedGroupId = hitGroupId
        }

        fun finishPhotoDrag(cancelled: Boolean) {
            val drag = activePhotoDrag
            val targetKey = hoveredPhotoDropTargetKey
            val wasAutoOpened = sidebarAutoOpenedByDrag

            activePhotoDrag = null
            hoveredPhotoDropTargetKey = null
            hoveredCollapsedGroupId = null
            springLoadedExpandGroupId = null
            sidebarAutoOpenedByDrag = false

            if (wasAutoOpened && !effectiveShowSidebar) {
                scope.launch {
                    sidebarFraction.animateTo(
                        0f,
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 430f)
                    )
                }
            }

            if (cancelled || drag == null || targetKey == null) return
            val itemsToMove = uiState.mediaItems.filter { it.id in drag.draggedIds }
            if (itemsToMove.isEmpty()) return

            when (targetKey) {
                topDropZoneParentKey -> {
                    val parent = latestParentLocalFolder
                    if (parent != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        startLocalPhotoMove(
                            items = itemsToMove,
                            targetName = parent.displayName,
                            useDcimParent = parent.useDcimParent,
                            targetRelPath = parent.relativePath
                        )
                    }
                }
                topDropZoneOtherKey -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    moveSheetOverrideItems = itemsToMove
                    moveSheetInitialMoveMode = true
                    showMoveCopySheet = true
                }
                else -> {
                    val dest = sidebarAlbumDropTargets[targetKey]?.folder ?: return
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val inDcim = dest.relativePath?.startsWith("DCIM", ignoreCase = true) == true
                    val exactPath = dest.relativePath?.takeIf { it.isNotBlank() }
                    startLocalPhotoMove(
                        items = itemsToMove,
                        targetName = dest.name,
                        useDcimParent = inDcim,
                        targetRelPath = exactPath
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onGloballyPositioned { coords ->
                    rootBoxOriginInRoot = coords.positionInRoot()
                }
                // Samsung One UI horizontal swipe left/right anywhere in an album to hide/show the album sidebar!
                .pointerInput(Unit) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        if (!latestCanHaveSidebar || latestIsPhotoDragActive) return@awaitEachGesture
                        val startFraction = sidebarFraction.value
                        var totalDx = 0f
                        var totalDy = 0f
                        var horizontalLocked = false
                        var aborted = false
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)

                        do {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount >= 2 || latestIsPhotoDragActive) {
                                aborted = true
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.isConsumed) {
                                // A child gesture (e.g., drag-select, move-drag, or horizontal chip row) claimed this drag
                                aborted = true
                                break
                            }
                            val delta = change.position - change.previousPosition
                            totalDx += delta.x
                            totalDy += delta.y
                            velocityTracker.addPosition(change.uptimeMillis, change.position)

                            if (!horizontalLocked) {
                                val absX = abs(totalDx)
                                val absY = abs(totalDy)
                                if (absY > touchSlop * 1.15f && absY > absX) {
                                    // Vertical scroll won — step aside cleanly
                                    aborted = true
                                    break
                                }
                                if (absX > touchSlop * 1.3f && absX > absY * 1.45f) {
                                    // Lock only when swiping in a valid reveal/hide direction
                                    val swipingToHide = startFraction > 0.5f && totalDx < 0f
                                    val swipingToShow = startFraction <= 0.5f && totalDx > 0f
                                    if (swipingToHide || swipingToShow) {
                                        horizontalLocked = true
                                    } else {
                                        aborted = true
                                        break
                                    }
                                }
                            }
                            if (horizontalLocked) {
                                change.consume()
                                val targetFrac = (startFraction + totalDx / latestSidebarWidthPx.coerceAtLeast(1f))
                                    .coerceIn(0f, 1f)
                                scope.launch { sidebarFraction.snapTo(targetFrac) }
                            }
                        } while (true)

                        if (horizontalLocked) {
                            val vx = runCatching { velocityTracker.calculateVelocity().x }.getOrDefault(0f)
                            val curFrac = sidebarFraction.value
                            val shouldOpen = when {
                                vx > 340f -> true
                                vx < -340f -> false
                                else -> curFrac > 0.44f
                            }
                            if (shouldOpen != latestEffectiveShowSidebar) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.setSidebarVisible(shouldOpen)
                            }
                            scope.launch {
                                sidebarFraction.animateTo(
                                    targetValue = if (shouldOpen) 1f else 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = 430f
                                    )
                                )
                            }
                        } else if (aborted && abs(sidebarFraction.value - (if (latestEffectiveShowSidebar) 1f else 0f)) > 0.01f) {
                            scope.launch {
                                sidebarFraction.animateTo(
                                    targetValue = if (latestEffectiveShowSidebar) 1f else 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = 430f
                                    )
                                )
                            }
                        }
                    }
                }
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Redesigned, clean, swipeable Samsung One UI Left Album & Group Quick-Switch Rail
                val curSidebarFrac = sidebarFraction.value.coerceIn(0f, 1f)
                if (canHaveSidebar && curSidebarFrac > 0.005f) {
                    Box(
                        modifier = Modifier
                            .width(sidebarMaxWidthDp * curSidebarFrac)
                            .fillMaxHeight()
                            .clipToBounds()
                    ) {
                        Box(
                            modifier = Modifier
                                .width(sidebarMaxWidthDp)
                                .fillMaxHeight()
                                .graphicsLayer {
                                    alpha = curSidebarFrac
                                    translationX = -(1f - curSidebarFrac) * (sidebarMaxWidthPx * 0.25f)
                                }
                        ) {
                            SamsungAlbumSidebarRail(
                                nodes = uiState.sidebarNodes,
                                activeFolderId = uiState.folderId,
                                isUnmeteredWifi = uiState.isUnmeteredWifi,
                                isPhotoDragActive = activePhotoDrag != null,
                                hoveredDropFolderId = hoveredPhotoDropTargetKey,
                                externalExpandGroupId = springLoadedExpandGroupId,
                                onRegisterAlbumDropTarget = { folder, rect ->
                                    if (folder.isLocal && !folder.isLocked) {
                                        sidebarAlbumDropTargets[folder.id] = SidebarDropTarget(folder, rect)
                                    }
                                },
                                onClearAlbumDropTarget = { folderId ->
                                    sidebarAlbumDropTargets.remove(folderId)
                                },
                                onRegisterCollapsedGroup = { groupId, rect ->
                                    sidebarCollapsedGroupRects[groupId] = rect
                                },
                                onClearCollapsedGroup = { groupId ->
                                    sidebarCollapsedGroupRects.remove(groupId)
                                },
                                onCollapseSidebar = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.setSidebarVisible(false)
                                },
                                onSelectAlbum = { id, name ->
                                    val performSwitch: () -> Unit = {
                                        if (id != uiState.folderId) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        val cached = if (LocalMediaRepository.isLocalFolderId(id)) {
                                            appContainer.localMediaRepository.getCachedAlbumMedia(id)
                                        } else {
                                            appContainer.galleryRepository.getCachedFolderMedia(id).orEmpty()
                                        }
                                        if (cached.isNotEmpty()) {
                                            prefetchAlbumThumbnails(
                                                context = context.applicationContext,
                                                imageLoader = imageLoader,
                                                items = cached,
                                                isUnmeteredWifi = uiState.isUnmeteredWifi
                                            )
                                        }
                                        viewModel.switchToAlbum(id, name)
                                    }
                                    if (id != uiState.folderId && id in uiState.lockedAlbumIds) {
                                        BiometricLockHelper.authenticate(
                                            context = context,
                                            title = "Unlock \"$name\"",
                                            subtitle = "Verify your identity to open locked album",
                                            onSuccess = performSwitch,
                                            onFailure = { err -> viewModel.showMessage(err) }
                                        )
                                    } else {
                                        performSwitch()
                                    }
                                }
                            )
                        }
                    }
                }

                // Right Side: Active Album Filter Pill + Media Grid
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // Sleek Samsung One UI active Filter / Group / Sort capsule strip
                    val activeOptionCount =
                        (if (uiState.mediaFilter != AlbumMediaFilter.ALL) 1 else 0) +
                        (if (uiState.mediaGroupBy != AlbumMediaGroupBy.NONE) 1 else 0) +
                        (if (uiState.mediaSort != AlbumMediaSort.DATE_DESC) 1 else 0)

                    AnimatedVisibility(
                        visible = activeOptionCount > 0,
                        enter = expandVertically(tween(220, easing = SidebarEasing)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180, easing = SidebarEasing)) + fadeOut(tween(120))
                    ) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (uiState.mediaFilter != AlbumMediaFilter.ALL) {
                                item(key = "chip_filter") {
                                    ActiveOptionChip(
                                        icon = Icons.Outlined.FilterAlt,
                                        label = uiState.mediaFilter.label,
                                        onClick = { showFilterDialog = true },
                                        onClear = { viewModel.setMediaFilter(AlbumMediaFilter.ALL) }
                                    )
                                }
                            }
                            if (uiState.mediaGroupBy != AlbumMediaGroupBy.NONE) {
                                item(key = "chip_group") {
                                    ActiveOptionChip(
                                        icon = Icons.Rounded.CalendarMonth,
                                        label = uiState.mediaGroupBy.chipLabel,
                                        onClick = { showGroupByDialog = true },
                                        onClear = { viewModel.setMediaGroupBy(AlbumMediaGroupBy.NONE) }
                                    )
                                }
                            }
                            if (uiState.mediaSort != AlbumMediaSort.DATE_DESC) {
                                item(key = "chip_sort") {
                                    ActiveOptionChip(
                                        icon = Icons.AutoMirrored.Rounded.Sort,
                                        label = uiState.mediaSort.chipLabel,
                                        onClick = { showSortDialog = true },
                                        onClear = { viewModel.setMediaSort(AlbumMediaSort.DATE_DESC) }
                                    )
                                }
                            }
                            if (activeOptionCount >= 2) {
                                item(key = "chip_reset_all") {
                                    Text(
                                        text = "Reset",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                viewModel.setMediaFilter(AlbumMediaFilter.ALL)
                                                viewModel.setMediaGroupBy(AlbumMediaGroupBy.NONE)
                                                viewModel.setMediaSort(AlbumMediaSort.DATE_DESC)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refreshFolder(silent = false) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AnimatedContent(
                            targetState = uiState.folderId,
                            transitionSpec = {
                                val fromIdx = sidebarAlbumOrder.indexOf(initialState)
                                val toIdx = sidebarAlbumOrder.indexOf(targetState)
                                val dir = if (fromIdx >= 0 && toIdx >= 0 && toIdx < fromIdx) -1 else 1
                                // Apple iPadOS / Xiaomi HyperOS directional fluid glide + subtle depth bloom
                                (slideInVertically(
                                    animationSpec = tween(290, easing = OneUiMotion.FluidEasing)
                                ) { h -> dir * (h / 14).coerceIn(28, 72) } +
                                    scaleIn(
                                        animationSpec = tween(290, easing = OneUiMotion.FluidEasing),
                                        initialScale = 0.965f
                                    ) +
                                    fadeIn(tween(220, easing = OneUiMotion.FluidEasing)))
                                    .togetherWith(
                                        slideOutVertically(
                                            animationSpec = tween(210, easing = OneUiMotion.FluidEasing)
                                        ) { h -> -dir * (h / 22).coerceIn(16, 44) } +
                                            scaleOut(
                                                animationSpec = tween(210, easing = OneUiMotion.FluidEasing),
                                                targetScale = 0.975f
                                            ) +
                                            fadeOut(tween(150))
                                    )
                            },
                            label = "albumSwitchTransition"
                        ) { targetFolderId ->
                            // Each AnimatedContent slot gets its own LazyGridState so simultaneous
                            // enter/exit frames never attach two LazyVerticalGrids to one state!
                            val branchGridState = remember(targetFolderId) {
                                gridStatesByFolder.getOrPut(targetFolderId) { LazyGridState() }
                            }
                            when {
                                !uiState.hasCompletedInitialLoad && uiState.gridEntries.isEmpty() -> {
                                    MediaGridShimmer(columns = effectiveColumns)
                                }

                                uiState.isEmpty -> {
                                    EmptyFolderState(
                                        isLocal = uiState.isLocalAlbum,
                                        isFlattened = uiState.viewMode == FolderViewMode.FLATTENED,
                                        activeFilter = uiState.mediaFilter,
                                        onClearFilter = { viewModel.setMediaFilter(AlbumMediaFilter.ALL) }
                                    )
                                }

                                else -> {
                                    val pinchCurrentCols = if (isFolderContainerOnly) {
                                        uiState.albumGridColumns
                                    } else {
                                        effectiveColumns
                                    }
                                    val pinchMinCols = if (isFolderContainerOnly) 2 else 1
                                    val pinchMaxCols = when {
                                        isFolderContainerOnly -> 5
                                        effectiveShowSidebar -> 5
                                        else -> 6
                                    }
                                    PinchZoomGridContainer(
                                        currentColumns = pinchCurrentCols,
                                        onColumnsChange = { newCols ->
                                            if (isFolderContainerOnly) {
                                                viewModel.setAlbumGridColumns(newCols)
                                            } else {
                                                val persistedCols = if (effectiveShowSidebar) {
                                                    (newCols + 1).coerceIn(1, 6)
                                                } else {
                                                    newCols.coerceIn(1, 6)
                                                }
                                                viewModel.setGridColumns(persistedCols)
                                            }
                                        },
                                        minColumns = pinchMinCols,
                                        maxColumns = pinchMaxCols,
                                        columnLabel = { cols ->
                                            if (isFolderContainerOnly) "$cols albums / row"
                                            else if (cols == 1) "1 column" else "$cols columns"
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        val orderedFolderMediaIds = remember(uiState.mediaItems) {
                                            uiState.mediaItems.map { it.id }
                                        }
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(effectiveColumns),
                                            state = branchGridState,
                                            contentPadding = PaddingValues(
                                                start = if (effectiveShowSidebar) 4.dp else 8.dp,
                                                end = 8.dp,
                                                top = 2.dp,
                                                bottom = if (uiState.isSelectionMode) 112.dp else 28.dp
                                            ),
                                            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                                            verticalArrangement = Arrangement.spacedBy(1.5.dp),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .onGloballyPositioned { coords ->
                                                    gridOriginInRoot = coords.positionInRoot()
                                                }
                                                .photoGridDragSelect(
                                                    gridState = branchGridState,
                                                    orderedItemIds = orderedFolderMediaIds,
                                                    keyToItemId = { key ->
                                                        (key as? String)?.takeIf { it.startsWith("cell_") }?.removePrefix("cell_")
                                                    },
                                                    selectedIds = uiState.selectedIds,
                                                    onSetItemSelected = viewModel::setItemSelected,
                                                    onSetRangeSelected = viewModel::setItemsSelectedRange,
                                                    moveDragEnabled = canDragMovePhotos,
                                                    onMoveDragStart = { draggedIds, anchorItemId, localOffset ->
                                                        val anchor = uiState.mediaItems.firstOrNull { it.id == anchorItemId }
                                                            ?: uiState.mediaItems.firstOrNull { it.id in draggedIds }
                                                        if (anchor != null) {
                                                            val pointerInBox = (gridOriginInRoot + localOffset) - rootBoxOriginInRoot
                                                            activePhotoDrag = ActivePhotoDragState(
                                                                draggedIds = draggedIds,
                                                                leadItem = anchor,
                                                                pointerInBox = pointerInBox
                                                            )
                                                            updatePhotoDragHover(localOffset)
                                                        }
                                                    },
                                                    onMoveDragMove = { localOffset ->
                                                        updatePhotoDragHover(localOffset)
                                                    },
                                                    onMoveDragEnd = { cancelled ->
                                                        finishPhotoDrag(cancelled)
                                                    }
                                                )
                                        ) {
                                            items(
                                                items = uiState.gridEntries,
                                                key = { it.stableKey },
                                                span = { entry ->
                                                    when (entry) {
                                                        is FolderGridEntry.MediaCell -> GridItemSpan(1)
                                                        else -> GridItemSpan(maxLineSpan)
                                                    }
                                                },
                                                contentType = { entry ->
                                                    when (entry) {
                                                        is FolderGridEntry.SubfoldersHeading -> "subfolders_heading"
                                                        is FolderGridEntry.SubfolderRow -> "subfolder_row"
                                                        is FolderGridEntry.SectionHeader -> "section_header"
                                                        is FolderGridEntry.MediaCell -> "media_cell"
                                                    }
                                                }
                                            ) { entry ->
                                                when (entry) {
                                                    is FolderGridEntry.SubfoldersHeading -> {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 6.dp, vertical = 10.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(
                                                                text = "Folders",
                                                                style = MaterialTheme.typography.titleSmall.copy(
                                                                    fontWeight = FontWeight.SemiBold
                                                                ),
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            Text(
                                                                text = "${entry.count}",
                                                                style = MaterialTheme.typography.labelMedium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }

                                                    is FolderGridEntry.SubfolderRow -> {
                                                        val compactRow = entry.rowColumns >= 4
                                                        val rowGap = if (compactRow) 8.dp else 10.dp
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 4.dp, vertical = 6.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(rowGap)
                                                        ) {
                                                            for (col in 0 until entry.rowColumns) {
                                                                val folder = entry.folders.getOrNull(col)
                                                                if (folder != null) {
                                                                    FolderCard(
                                                                        folder = folder,
                                                                        isUnmeteredWifi = uiState.isUnmeteredWifi,
                                                                        showDriveBadge = uiState.showCloudBadges && !folder.isLocal,
                                                                        compact = compactRow,
                                                                        onClick = { onOpenSubfolder(folder.id, folder.name) },
                                                                        modifier = Modifier.weight(1f)
                                                                    )
                                                                } else {
                                                                    Spacer(Modifier.weight(1f))
                                                                }
                                                            }
                                                        }
                                                    }

                                                    is FolderGridEntry.SectionHeader -> {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(
                                                                    start = 10.dp,
                                                                    end = 10.dp,
                                                                    top = 14.dp,
                                                                    bottom = 6.dp
                                                                ),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(
                                                                text = entry.title,
                                                                style = MaterialTheme.typography.labelLarge.copy(
                                                                    fontWeight = FontWeight.SemiBold
                                                                ),
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                            if (!entry.subtitle.isNullOrBlank()) {
                                                                Text(
                                                                    text = entry.subtitle,
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                        }
                                                    }

                                                    is FolderGridEntry.MediaCell -> {
                                                        val isSelected = entry.item.id in uiState.selectedIds
                                                        MediaGridItem(
                                                            item = entry.item,
                                                            isUnmeteredWifi = uiState.isUnmeteredWifi,
                                                            isSelectionMode = uiState.isSelectionMode,
                                                            isSelected = isSelected,
                                                            isFavorite = entry.item.id in uiState.favoriteItemIds,
                                                            isOfflinePinned = entry.item.id in offlineIds,
                                                            showCloudBadge = uiState.showCloudBadges,
                                                            onClick = {
                                                                if (uiState.isSelectionMode) {
                                                                    viewModel.toggleSelection(entry.item.id)
                                                                } else {
                                                                    onOpenViewer(
                                                                        uiState.folderId,
                                                                        entry.item.id,
                                                                        uiState.viewMode == FolderViewMode.FLATTENED,
                                                                        uiState.mediaFilter,
                                                                        uiState.mediaSort
                                                                    )
                                                                }
                                                            },
                                                            onLongClick = null,
                                                            modifier = Modifier.animateItem()
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        FastScrollThumb(
                                            gridState = branchGridState,
                                            totalItemCount = uiState.gridEntries.size,
                                            labelForIndex = { idx ->
                                                uiState.gridEntries.getOrNull(idx)?.scrollDateLabel
                                            },
                                            modifier = Modifier.align(Alignment.CenterEnd)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Subtle left-edge handle pill when sidebar is hidden (swipe right OR tap to reveal)
            AnimatedVisibility(
                visible = canHaveSidebar && !effectiveShowSidebar && sidebarFraction.value < 0.05f && activePhotoDrag == null,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120)),
                modifier = Modifier
                    .align(Alignment.CenterStart)
            ) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(64.dp)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.setSidebarVisible(true)
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 3.dp)
                            .width(3.5.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f))
                    )
                }
            }

            // Floating Top Parent-Folder ("Moeder Folder") Drop Bar when dragging photo(s) out!
            AnimatedVisibility(
                visible = activePhotoDrag != null,
                enter = slideInVertically { -it } + fadeIn(tween(160)),
                exit = slideOutVertically { -it } + fadeOut(tween(120)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val parent = parentLocalFolder
                    if (parent != null) {
                        PhotoMoveDropPill(
                            icon = Icons.Rounded.MoveUp,
                            label = "Move to ${parent.displayName}",
                            isHovered = hoveredPhotoDropTargetKey == topDropZoneParentKey,
                            onBoundsChanged = { topDropZoneRects[topDropZoneParentKey] = it },
                            onDisposeBounds = { topDropZoneRects.remove(topDropZoneParentKey) },
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                    PhotoMoveDropPill(
                        icon = Icons.Rounded.DriveFileMove,
                        label = if (parent != null) "Other album…" else "Drop to choose album…",
                        isHovered = hoveredPhotoDropTargetKey == topDropZoneOtherKey,
                        onBoundsChanged = { topDropZoneRects[topDropZoneOtherKey] = it },
                        onDisposeBounds = { topDropZoneRects.remove(topDropZoneOtherKey) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Floating 120Hz Dragged Photo Stack Overlay under the user's finger
            val currentDrag = activePhotoDrag
            if (currentDrag != null) {
                val dragCount = currentDrag.draggedIds.size.coerceAtLeast(1)
                val isOverTarget = hoveredPhotoDropTargetKey != null
                val stackScale by animateFloatAsState(
                    targetValue = if (isOverTarget) 0.84f else 1.04f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "photoDragStackScale"
                )
                val halfSizePx = with(density) { 34.dp.toPx() }
                FloatingPhotoDragStack(
                    leadItem = currentDrag.leadItem,
                    count = dragCount,
                    isUnmeteredWifi = uiState.isUnmeteredWifi,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (currentDrag.pointerInBox.x - halfSizePx).roundToInt(),
                                y = (currentDrag.pointerInBox.y - halfSizePx * 1.45f).roundToInt()
                            )
                        }
                        .graphicsLayer {
                            scaleX = stackScale
                            scaleY = stackScale
                            alpha = 0.94f
                        }
                )
            }

            val selectedMediaItems = remember(uiState.mediaItems, uiState.selectedIds) {
                uiState.mediaItems.filter { it.id in uiState.selectedIds }
            }
            val comparableIds = remember(selectedMediaItems) {
                selectedMediaItems.filter { !it.isVideo }.map { it.id }
            }

            MultiSelectActionBar(
                visible = uiState.isSelectionMode && activePhotoDrag == null,
                selectedCount = uiState.selectedIds.size,
                isExporting = uiState.isExporting,
                exportProgressText = uiState.exportProgressText,
                onShare = viewModel::shareSelected,
                onSaveToDevice = if (uiState.isLocalAlbum) null else viewModel::saveSelectedToDevice,
                onDeleteLocal = if (uiState.isLocalAlbum && selectedMediaItems.isNotEmpty()) {
                    { showTrashConfirmLocal = true }
                } else null,
                onCompare = if (comparableIds.size in 2..4) {
                    {
                        viewModel.clearSelection()
                        onOpenCompare(comparableIds)
                    }
                } else null,
                onMoveCopyLocal = if (uiState.isLocalAlbum && selectedMediaItems.isNotEmpty()) {
                    {
                        moveSheetOverrideItems = null
                        moveSheetInitialMoveMode = false
                        showMoveCopySheet = true
                    }
                } else null,
                onCreateCollagePdf = if (comparableIds.isNotEmpty()) {
                    { showCollagePdfSheet = true }
                } else null,
                onInfo = viewModel::openSelectedInfo,
                onClearSelection = viewModel::clearSelection,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showMoveCopySheet && uiState.isLocalAlbum) {
        val selectedForMove = moveSheetOverrideItems ?: remember(uiState.mediaItems, uiState.selectedIds) {
            uiState.mediaItems.filter { it.id in uiState.selectedIds }
        }
        if (selectedForMove.isNotEmpty()) {
            MoveCopyToAlbumSheet(
                items = selectedForMove,
                initialMoveMode = moveSheetInitialMoveMode,
                onDismiss = {
                    showMoveCopySheet = false
                    moveSheetOverrideItems = null
                    moveSheetInitialMoveMode = false
                },
                onCompleted = { msg ->
                    showMoveCopySheet = false
                    moveSheetOverrideItems = null
                    moveSheetInitialMoveMode = false
                    viewModel.clearSelection()
                    viewModel.showMessage(msg)
                }
            )
        }
    }

    if (showTrashConfirmLocal && uiState.isLocalAlbum) {
        val itemsToTrash = remember(uiState.mediaItems, uiState.selectedIds) {
            uiState.mediaItems.filter { it.id in uiState.selectedIds }
        }
        if (itemsToTrash.isNotEmpty()) {
            val hasHiddenLockedFiles = itemsToTrash.any {
                HiddenLockedFolderManager.isHiddenFileUri(it.localContentUri)
            }
            HyperOsTrashConfirmSheet(
                items = itemsToTrash,
                mode = if (hasHiddenLockedFiles) TrashConfirmMode.DELETE_PERMANENTLY else TrashConfirmMode.MOVE_TO_TRASH,
                canSkipSystemPrompt = hasHiddenLockedFiles || appContainer.localMediaRepository.canManageMediaDirectly(),
                supportsSkipPromptSetting = !hasHiddenLockedFiles && appContainer.localMediaRepository.supportsManageMediaPermission(),
                onOpenManageMediaSettings = {
                    appContainer.localMediaRepository.buildManageMediaSettingsIntent()?.let { intent ->
                        runCatching { context.startActivity(intent) }
                    }
                },
                onConfirm = {
                    showTrashConfirmLocal = false
                    viewModel.requestTrashSelectedLocal()
                },
                onDismiss = { showTrashConfirmLocal = false }
            )
        }
    }

    if (showAllFilesAccessLockDialog) {
        AlertDialog(
            onDismissRequest = { showAllFilesAccessLockDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Hide folder from File Explorer?") },
            text = {
                Text(
                    text = "Grant All files access once so Lumina can hide \"${uiState.folderName}\" as a hidden .folder with .nomedia — making it invisible in File Explorer, other gallery apps, and photo pickers while keeping your files safe across reinstalls.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAllFilesAccessLockDialog = false
                        pendingAllFilesAction = "lock"
                        allFilesAccessLauncher.launch(
                            appContainer.hiddenLockedFolderManager.buildAllFilesAccessIntent()
                        )
                    }
                ) {
                    Text("Grant access")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAllFilesAccessLockDialog = false
                        viewModel.lockCurrentAlbum(hideOnDisk = false, onLocked = onNavigateBack)
                    }
                ) {
                    Text("In-app lock only")
                }
            }
        )
    }

    if (showCollagePdfSheet) {
        val photosForCreation = remember(uiState.mediaItems, uiState.selectedIds) {
            uiState.mediaItems.filter { it.id in uiState.selectedIds && !it.isVideo }
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

    if (showRenameAlbumDialog && uiState.isLocalAlbum) {
        var newAlbumName by remember(uiState.folderName) { mutableStateOf(uiState.folderName) }
        val allAlbumItems = uiState.unfilteredMediaItems.ifEmpty { uiState.mediaItems }
        AlertDialog(
            onDismissRequest = { showRenameAlbumDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Rename local album") },
            text = {
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = { newAlbumName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newAlbumName.isNotBlank() && newAlbumName.trim() != uiState.folderName,
                    onClick = {
                        val trimmed = newAlbumName.trim()
                        showRenameAlbumDialog = false
                        val sender = appContainer.localFileOperationsManager
                            .buildWritePermissionSender(allAlbumItems)
                        if (sender != null) {
                            pendingAlbumRenameName = trimmed
                            renameAlbumPermissionLauncher.launch(
                                IntentSenderRequest.Builder(sender).build()
                            )
                        } else {
                            executeAlbumRename(trimmed)
                        }
                    }
                ) { Text("Rename", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameAlbumDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Filter Dialog
    if (showFilterDialog) {
        SamsungRadioChoiceDialog(
            title = "Filter",
            options = AlbumMediaFilter.entries,
            selected = uiState.mediaFilter,
            labelOf = { it.label },
            onSelect = {
                viewModel.setMediaFilter(it)
                showFilterDialog = false
            },
            onDismiss = { showFilterDialog = false }
        )
    }

    // Group By Dialog
    if (showGroupByDialog) {
        SamsungRadioChoiceDialog(
            title = "Group by",
            options = AlbumMediaGroupBy.entries,
            selected = uiState.mediaGroupBy,
            labelOf = { it.label },
            onSelect = {
                viewModel.setMediaGroupBy(it)
                showGroupByDialog = false
            },
            onDismiss = { showGroupByDialog = false }
        )
    }

    // Sort Dialog
    if (showSortDialog) {
        SamsungRadioChoiceDialog(
            title = "Sort by",
            options = AlbumMediaSort.entries,
            selected = uiState.mediaSort,
            labelOf = { it.label },
            onSelect = {
                viewModel.setMediaSort(it)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
    }

    // Full-screen Ken Burns Slideshow
    if (showSlideshow && uiState.mediaItems.isNotEmpty()) {
        val photosForSlideshow = remember(uiState.mediaItems) {
            val photos = uiState.mediaItems.filter { !it.isVideo }
            if (photos.isNotEmpty()) photos else uiState.mediaItems
        }
        val albumStory = remember(uiState.folderId, photosForSlideshow) {
            GalleryStory(
                id = "slideshow_${uiState.folderId}",
                title = uiState.folderName,
                subtitle = mediaBreakdownSubtitle,
                dateRangeLabel = mediaBreakdownSubtitle,
                daySpanLabel = null,
                category = StoryCategory.OTHER,
                coverItem = photosForSlideshow.first(),
                items = photosForSlideshow
            )
        }
        StorySlideshowDialog(
            story = albumStory,
            isUnmeteredWifi = uiState.isUnmeteredWifi,
            onToggleFavorite = null,
            onDismiss = { showSlideshow = false }
        )
    }

    uiState.infoItem?.let { item ->
        ExifMetadataBottomSheet(
            item = item,
            driveFolderPath = uiState.infoFolderPath,
            onDismiss = viewModel::closeInfoSheet,
            onCopiedMessage = viewModel::showMessage
        )
    }
}

private data class ActivePhotoDragState(
    val draggedIds: Set<String>,
    val leadItem: MediaItem,
    val pointerInBox: Offset
)

private data class SidebarDropTarget(
    val folder: DriveFolder,
    val boundsInRoot: Rect
)

private data class PendingPhotoMoveRequest(
    val items: List<MediaItem>,
    val targetName: String,
    val useDcimParent: Boolean,
    val targetRelativePath: String?
)

@Composable
private fun PhotoMoveDropPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isHovered: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onDisposeBounds: () -> Unit,
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        onDispose { onDisposeBounds() }
    }
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f)
        },
        label = "photoDropBg"
    )
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.03f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "photoDropScale"
    )
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = bgColor,
        shadowElevation = if (isHovered) 10.dp else 4.dp,
        modifier = modifier
            .height(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onGloballyPositioned { coords ->
                onBoundsChanged(coords.boundsInRoot())
            }
            .border(
                width = if (isHovered) 2.dp else 1.dp,
                color = if (isHovered) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(18.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isHovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isHovered) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FloatingPhotoDragStack(
    leadItem: MediaItem,
    count: Int,
    isUnmeteredWifi: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val request = remember(leadItem, isUnmeteredWifi) {
        if (leadItem.isLocal && !leadItem.localContentUri.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(LocalThumbnailRequest(leadItem.localContentUri, leadItem.isVideo))
                .size(256)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(leadItem.id))
                .crossfade(false)
                .build()
        } else {
            val url = DriveConstants.buildThumbnailUrl(
                thumbnailLink = leadItem.thumbnailLink,
                fileId = leadItem.id,
                isUnmeteredWifi = isUnmeteredWifi,
                isVideo = leadItem.isVideo,
                sizeBytes = leadItem.sizeBytes
            ) ?: return@remember null
            ImageRequest.Builder(context)
                .data(url)
                .size(256)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(leadItem.id))
                .crossfade(false)
                .build()
        }
    }

    Box(modifier = modifier.size(72.dp)) {
        if (count > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(62.dp)
                    .graphicsLayer {
                        rotationZ = -7f
                        translationX = -4f
                        translationY = 4f
                    }
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(15.dp))
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(64.dp)
                .shadow(16.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    imageLoader = imageLoader,
                    contentDescription = leadItem.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (count > 1) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (count > 99) "99+" else count.toString(),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Clean, consistent Samsung One UI–inspired left Album & Group Quick-Switch Sidebar Rail:
 *  - Calm tonal rail surface with hairline edge divider & compact top header + 1-tap collapse.
 *  - Uniform centered 64.dp rounded-square covers across every top-level album and collapsed group.
 *  - Clean active indicator pill + crisp ring without overflow jitter.
 *  - Expanded groups display a clear tappable header (to collapse back) and evenly aligned child albums.
 *  - Acts as a live drag-and-drop destination (with spring-loaded group expansion) when dragging photos.
 */
@Composable
private fun SamsungAlbumSidebarRail(
    nodes: List<SidebarAlbumNode>,
    activeFolderId: String,
    isUnmeteredWifi: Boolean,
    isPhotoDragActive: Boolean,
    hoveredDropFolderId: String?,
    externalExpandGroupId: String?,
    onRegisterAlbumDropTarget: (DriveFolder, Rect) -> Unit,
    onClearAlbumDropTarget: (String) -> Unit,
    onRegisterCollapsedGroup: (String, Rect) -> Unit,
    onClearCollapsedGroup: (String) -> Unit,
    onCollapseSidebar: () -> Unit,
    onSelectAlbum: (folderId: String, folderName: String) -> Unit
) {
    val railListState = rememberLazyListState()
    var expandedNodeIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Automatically expand ONLY the ancestor group chain of the currently active album
    LaunchedEffect(activeFolderId, nodes) {
        val ancestorIds = findAncestorNodeIds(nodes, activeFolderId)
        expandedNodeIds = ancestorIds
        val topIdx = nodes.indexOfFirst { nodeContainsAlbum(it, activeFolderId) }
        if (topIdx >= 0) {
            val visible = railListState.layoutInfo.visibleItemsInfo.map { it.index }
            if (topIdx !in visible) {
                railListState.animateScrollToItem((topIdx - 1).coerceAtLeast(0))
            }
        }
    }

    // Spring-loaded expand when dragging photos over a collapsed group
    LaunchedEffect(externalExpandGroupId) {
        val targetId = externalExpandGroupId ?: return@LaunchedEffect
        val targetNode = findNodeById(nodes, targetId) ?: return@LaunchedEffect
        val path = findAncestorNodeIds(nodes, targetId)
        expandedNodeIds = expandedNodeIds + path + collectAutoExpandIds(targetNode)
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.52f))
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            // Minimal, clean rail header with subtle collapse button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Albums",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .clickable(onClick = onCollapseSidebar),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronLeft,
                        contentDescription = "Hide album sidebar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            LazyColumn(
                state = railListState,
                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = nodes,
                    key = { it.gridItem.itemId }
                ) { node ->
                    SidebarNodeView(
                        node = node,
                        depth = 0,
                        activeFolderId = activeFolderId,
                        isUnmeteredWifi = isUnmeteredWifi,
                        expandedIds = expandedNodeIds,
                        isPhotoDragActive = isPhotoDragActive,
                        hoveredDropFolderId = hoveredDropFolderId,
                        onRegisterAlbumDropTarget = onRegisterAlbumDropTarget,
                        onClearAlbumDropTarget = onClearAlbumDropTarget,
                        onRegisterCollapsedGroup = onRegisterCollapsedGroup,
                        onClearCollapsedGroup = onClearCollapsedGroup,
                        onToggleGroup = { tappedNode ->
                            val tappedId = tappedNode.gridItem.itemId
                            if (tappedId in expandedNodeIds) {
                                expandedNodeIds = expandedNodeIds - tappedId
                            } else {
                                val toExpand = collectAutoExpandIds(tappedNode)
                                val pathToTapped = findAncestorNodeIds(nodes, tappedId)
                                expandedNodeIds = pathToTapped + toExpand
                            }
                        },
                        onSelectAlbum = onSelectAlbum
                    )
                }
            }
        }

        // Crisp hairline divider cleanly separating the album rail from the photo grid
        Box(
            modifier = Modifier
                .width(0.5.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
        )
    }
}

@Composable
private fun SidebarNodeView(
    node: SidebarAlbumNode,
    depth: Int,
    activeFolderId: String,
    isUnmeteredWifi: Boolean,
    expandedIds: Set<String>,
    isPhotoDragActive: Boolean,
    hoveredDropFolderId: String?,
    onRegisterAlbumDropTarget: (DriveFolder, Rect) -> Unit,
    onClearAlbumDropTarget: (String) -> Unit,
    onRegisterCollapsedGroup: (String, Rect) -> Unit,
    onClearCollapsedGroup: (String) -> Unit,
    onToggleGroup: (SidebarAlbumNode) -> Unit,
    onSelectAlbum: (String, String) -> Unit
) {
    val isGroup = node.children.isNotEmpty()
    val nodeId = node.gridItem.itemId
    val coverSizeDp = if (depth == 0) 64.dp else 54.dp
    val coverCornerDp = if (depth == 0) 16.dp else 13.dp

    if (!isGroup) {
        val entry = node.gridItem as? AlbumGridItem.AlbumEntry ?: return
        val isActive = entry.folder.id == activeFolderId
        val isDropEligible = isPhotoDragActive && entry.folder.isLocal && !entry.folder.isLocked && !isActive
        val isDropHovered = isDropEligible && hoveredDropFolderId == entry.folder.id

        DisposableEffect(entry.folder.id) {
            onDispose { onClearAlbumDropTarget(entry.folder.id) }
        }

        val tileScale by animateFloatAsState(
            targetValue = if (isDropHovered) 1.07f else 1.0f,
            animationSpec = OneUiMotion.fluidSpring(),
            label = "sidebarTileScale"
        )
        val tileBgColor by animateColorAsState(
            targetValue = when {
                isDropHovered -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
                else -> Color.Transparent
            },
            label = "sidebarTileBg"
        )
        val indicatorHeight by animateDpAsState(
            targetValue = if (isActive) 22.dp else 0.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 440f
            ),
            label = "sidebarPillHeight"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    onRegisterAlbumDropTarget(entry.folder, coords.boundsInRoot())
                }
                .clip(RoundedCornerShape(14.dp))
                .background(tileBgColor)
                .graphicsLayer {
                    scaleX = tileScale
                    scaleY = tileScale
                }
                .oneUiClickable(
                    pressedScale = 0.94f,
                    hoveredScale = 1.02f,
                    onClick = { onSelectAlbum(entry.folder.id, entry.folder.name) }
                )
                .padding(vertical = 4.dp, horizontal = 3.dp)
        ) {
            // Crisp left-edge active indicator bar cleanly in the gutter
            val safePillHeight = indicatorHeight.coerceAtLeast(0.dp)
            if (safePillHeight > 0.5.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = (coverSizeDp - safePillHeight) / 2)
                        .width(3.dp)
                        .height(safePillHeight)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(coverSizeDp)
                        .clip(RoundedCornerShape(coverCornerDp))
                ) {
                    FolderCoverArtwork(
                        folder = entry.folder,
                        isUnmeteredWifi = isUnmeteredWifi,
                        showDriveBadge = !entry.folder.isLocal,
                        modifier = Modifier.fillMaxSize()
                    )
                    when {
                        isDropHovered -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                                    .border(
                                        width = 2.5.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(coverCornerDp)
                                    )
                            )
                        }
                        isActive -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(coverCornerDp)
                                    )
                            )
                        }
                        isDropEligible -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(coverCornerDp)
                                    )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = entry.folder.name,
                    fontSize = if (depth == 0) 11.sp else 10.5.sp,
                    lineHeight = 13.sp,
                    fontWeight = if (isActive || isDropHovered) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isActive || isDropHovered) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp)
                )
                Text(
                    text = entry.folder.mediaCount.toString(),
                    fontSize = if (depth == 0) 10.sp else 9.5.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        return
    }

    // Expandable Group: Local Collection OR Google Drive folder containing subfolders
    val isExpanded = nodeId in expandedIds
    val containsActive = remember(node, activeFolderId) { nodeContainsAlbum(node, activeFolderId) }
    val groupTitle = node.gridItem.displayName
    val groupCount = when (val g = node.gridItem) {
        is AlbumGridItem.CollectionEntry -> g.totalMediaCount
        is AlbumGridItem.AlbumEntry -> g.folder.mediaCount
    }

    AnimatedContent(
        targetState = isExpanded,
        transitionSpec = {
            (fadeIn(tween(220, easing = OneUiMotion.FluidEasing)) +
                expandVertically(tween(260, easing = OneUiMotion.FluidEasing)))
                .togetherWith(
                    fadeOut(tween(140)) +
                        shrinkVertically(tween(200, easing = OneUiMotion.FluidEasing))
                )
        },
        label = "sidebarGroupExpand_$nodeId",
        modifier = Modifier.fillMaxWidth()
    ) { expanded ->
        if (!expanded) {
            DisposableEffect(nodeId) {
                onDispose { onClearCollapsedGroup(nodeId) }
            }
            // Collapsed Group: EXACT same clean 64.dp centered geometry as single albums + subtle child count pill
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        onRegisterCollapsedGroup(nodeId, coords.boundsInRoot())
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .oneUiClickable(
                        pressedScale = 0.94f,
                        hoveredScale = 1.02f,
                        onClick = { onToggleGroup(node) }
                    )
                    .padding(vertical = 4.dp, horizontal = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(coverSizeDp)
                        .clip(RoundedCornerShape(coverCornerDp))
                ) {
                    when (val g = node.gridItem) {
                        is AlbumGridItem.CollectionEntry -> {
                            CollectionCoverArtwork(
                                previewCovers = g.previewCovers,
                                hasDriveChild = g.hasDriveChild,
                                isUnmeteredWifi = isUnmeteredWifi,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        is AlbumGridItem.AlbumEntry -> {
                            FolderCoverArtwork(
                                folder = g.folder,
                                isUnmeteredWifi = isUnmeteredWifi,
                                showDriveBadge = !g.folder.isLocal,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    // Subtle glass badge indicating this tile is a group containing N albums
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(horizontal = 4.5.dp, vertical = 1.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.size(9.dp)
                        )
                        Text(
                            text = node.children.size.toString(),
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = groupTitle,
                    fontSize = if (depth == 0) 11.sp else 10.5.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp)
                )
                Text(
                    text = groupCount.toString(),
                    fontSize = if (depth == 0) 10.sp else 9.5.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        } else {
            // Expanded Group: clean rounded tonal tray with tappable collapse header & aligned child albums
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f))
                    .border(
                        width = if (containsActive) 1.dp else 0.6.dp,
                        color = if (containsActive) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
                        },
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggleGroup(node) }
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = groupTitle,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (containsActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Collapse group",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }

                node.children.forEach { child ->
                    SidebarNodeView(
                        node = child,
                        depth = depth + 1,
                        activeFolderId = activeFolderId,
                        isUnmeteredWifi = isUnmeteredWifi,
                        expandedIds = expandedIds,
                        isPhotoDragActive = isPhotoDragActive,
                        hoveredDropFolderId = hoveredDropFolderId,
                        onRegisterAlbumDropTarget = onRegisterAlbumDropTarget,
                        onClearAlbumDropTarget = onClearAlbumDropTarget,
                        onRegisterCollapsedGroup = onRegisterCollapsedGroup,
                        onClearCollapsedGroup = onClearCollapsedGroup,
                        onToggleGroup = onToggleGroup,
                        onSelectAlbum = onSelectAlbum
                    )
                }
            }
        }
    }
}

private fun findNodeById(nodes: List<SidebarAlbumNode>, targetId: String): SidebarAlbumNode? {
    for (node in nodes) {
        if (node.gridItem.itemId == targetId) return node
        val found = findNodeById(node.children, targetId)
        if (found != null) return found
    }
    return null
}

private fun nodeContainsAlbum(node: SidebarAlbumNode, folderId: String): Boolean {
    if (node.gridItem.itemId == folderId) return true
    return node.children.any { nodeContainsAlbum(it, folderId) }
}

private fun findAncestorNodeIds(nodes: List<SidebarAlbumNode>, targetId: String): Set<String> {
    val result = HashSet<String>()
    fun dfs(node: SidebarAlbumNode): Boolean {
        if (node.gridItem.itemId == targetId) return true
        var found = false
        for (child in node.children) {
            if (dfs(child)) found = true
        }
        if (found && node.children.isNotEmpty()) {
            result.add(node.gridItem.itemId)
        }
        return found
    }
    nodes.forEach { dfs(it) }
    return result
}

private fun collectAutoExpandIds(startNode: SidebarAlbumNode): Set<String> {
    val ids = HashSet<String>()
    var cur: SidebarAlbumNode? = startNode
    var guard = 0
    while (cur != null && cur.children.isNotEmpty() && guard++ < 16) {
        ids.add(cur.gridItem.itemId)
        // Auto-unwrap single-child folder chains (e.g. XT5 -> fotos xt5 -> subfolders)
        cur = if (cur.children.size == 1 && cur.children[0].children.isNotEmpty()) {
            cur.children[0]
        } else {
            null
        }
    }
    return ids
}

private fun flattenSidebarAlbumOrder(nodes: List<SidebarAlbumNode>): List<String> {
    val result = ArrayList<String>(nodes.size * 2)
    fun visit(list: List<SidebarAlbumNode>) {
        for (node in list) {
            if (node.children.isEmpty()) {
                result.add(node.gridItem.itemId)
            } else {
                visit(node.children)
            }
        }
    }
    visit(nodes)
    return result
}

@Composable
private fun ActiveOptionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(50)
            )
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Reset",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

@Composable
private fun <T> SamsungRadioChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(26.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelect(option) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = labelOf(option),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun BreadcrumbBar(
    breadcrumbs: List<BreadcrumbItem>,
    onHomeClick: () -> Unit,
    onBreadcrumbClick: (BreadcrumbItem) -> Unit
) {
    val rowState = rememberLazyListState()
    LaunchedEffect(breadcrumbs.size) {
        if (breadcrumbs.isNotEmpty()) {
            rowState.animateScrollToItem(breadcrumbs.size)
        }
    }

    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        item(key = "crumb_home") {
            Text(
                text = "Drive",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onHomeClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        itemsIndexed(
            items = breadcrumbs,
            key = { _, item -> "crumb_${item.id}" }
        ) { index, crumb ->
            val isLast = index == breadcrumbs.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isLast) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !isLast) { onBreadcrumbClick(crumb) }
                ) {
                    Text(
                        text = crumb.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isLast) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFolderState(
    isLocal: Boolean,
    isFlattened: Boolean,
    activeFilter: AlbumMediaFilter,
    onClearFilter: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = when {
                    activeFilter != AlbumMediaFilter.ALL -> "No ${activeFilter.label.lowercase()} in this album"
                    isLocal -> "No media in this album"
                    isFlattened -> "No media in this folder tree"
                    else -> "This folder is empty"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (activeFilter != AlbumMediaFilter.ALL) {
                TextButton(onClick = onClearFilter) {
                    Text("Show all media")
                }
            }
        }
    }
}

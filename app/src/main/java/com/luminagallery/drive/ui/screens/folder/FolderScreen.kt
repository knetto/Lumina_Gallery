package com.luminagallery.drive.ui.screens.folder

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.domain.model.AlbumGridItem
import com.luminagallery.drive.domain.model.AlbumMediaFilter
import com.luminagallery.drive.domain.model.AlbumMediaGroupBy
import com.luminagallery.drive.domain.model.AlbumMediaSort
import com.luminagallery.drive.domain.model.BreadcrumbItem
import com.luminagallery.drive.domain.model.FolderViewMode
import com.luminagallery.drive.domain.model.GalleryStory
import com.luminagallery.drive.domain.model.SidebarAlbumNode
import com.luminagallery.drive.domain.model.StoryCategory
import com.luminagallery.drive.ui.components.CollectionCoverArtwork
import com.luminagallery.drive.ui.components.ExifMetadataBottomSheet
import com.luminagallery.drive.ui.components.FastScrollThumb
import com.luminagallery.drive.ui.components.FolderCard
import com.luminagallery.drive.ui.components.FolderCoverArtwork
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.MediaGridItem
import com.luminagallery.drive.ui.components.MediaGridShimmer
import com.luminagallery.drive.ui.components.MultiSelectActionBar
import com.luminagallery.drive.ui.components.OneUiMotion
import com.luminagallery.drive.ui.components.PinchZoomGridContainer
import com.luminagallery.drive.ui.components.oneUiClickable
import com.luminagallery.drive.ui.screens.stories.StorySlideshowDialog
import kotlinx.coroutines.delay

private val SidebarEasing = OneUiMotion.DecelerateEasing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun FolderScreen(
    viewModel: FolderViewModel,
    lastViewedItemId: String?,
    onConsumedLastViewedItemId: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenSubfolder: (folderId: String, folderName: String) -> Unit,
    onOpenBreadcrumb: (folderId: String, folderName: String) -> Unit,
    onOpenViewer: (folderId: String, itemId: String, isFlattened: Boolean) -> Unit,
    onOpenSearch: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showGroupByDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showSlideshow by remember { mutableStateOf(false) }

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

    LaunchedEffect(lastViewedItemId, uiState.gridEntries) {
        val targetId = lastViewedItemId ?: return@LaunchedEffect
        val targetIndex = uiState.gridEntries.indexOfFirst { entry ->
            entry is FolderGridEntry.MediaCell && entry.item.id == targetId
        }
        if (targetIndex >= 0) {
            val visibleIndices = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (targetIndex !in visibleIndices) {
                gridState.scrollToItem(targetIndex)
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

    // Reset grid scroll cleanly when switching album via left sidebar
    LaunchedEffect(uiState.folderId) {
        gridState.scrollToItem(0)
    }

    var autoScrollDelta by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(autoScrollDelta) {
        while (autoScrollDelta != 0f) {
            gridState.scrollBy(autoScrollDelta)
            delay(16L)
        }
    }

    val latestEntries by rememberUpdatedState(uiState.gridEntries)
    val latestSelectedIds by rememberUpdatedState(uiState.selectedIds)

    val mediaBreakdownSubtitle = remember(uiState.mediaItems, uiState.subfolders.size, uiState.viewMode) {
        FormatUtils.formatMediaBreakdown(
            items = uiState.mediaItems,
            subfolderCount = if (uiState.viewMode == FolderViewMode.HIERARCHY) uiState.subfolders.size else 0
        )
    }

    val hasSidebarContent = uiState.sidebarNodes.isNotEmpty()
    // Just like Samsung Gallery: while browsing a folder-of-folders (e.g. MakkumAll or MijnDingetjes)
    // with no direct photos, show a full-width 3-column album grid without the left sidebar!
    val isFolderContainerOnly = uiState.viewMode == FolderViewMode.HIERARCHY &&
        uiState.subfolders.isNotEmpty() &&
        uiState.mediaItems.isEmpty()
    val effectiveShowSidebar = uiState.showSidebar && hasSidebarContent && !isFolderContainerOnly
    val effectiveColumns = if (effectiveShowSidebar) {
        (uiState.gridColumns - 1).coerceIn(2, 5)
    } else {
        uiState.gridColumns
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = uiState.folderName,
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
                    },
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(38.dp)
                                .oneUiClickable(pressedScale = 0.88f, hoveredScale = 1.06f, onClick = onNavigateBack)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f)),
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
                                    containerColor = Color(0xFF28282B)
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Samsung One UI Left Album & Album Group Quick-Switch Sidebar Rail!
                AnimatedVisibility(
                    visible = effectiveShowSidebar,
                    enter = expandHorizontally(tween(260, easing = SidebarEasing)) + fadeIn(tween(200)),
                    exit = shrinkHorizontally(tween(220, easing = SidebarEasing)) + fadeOut(tween(150))
                ) {
                    SamsungAlbumSidebarRail(
                        nodes = uiState.sidebarNodes,
                        activeFolderId = uiState.folderId,
                        isUnmeteredWifi = uiState.isUnmeteredWifi,
                        onSelectAlbum = { id, name ->
                            viewModel.switchToAlbum(id, name)
                        }
                    )
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
                                        color = Color.White.copy(alpha = 0.62f),
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
                                (scaleIn(tween(280, easing = SidebarEasing), initialScale = 0.96f) +
                                    slideInVertically(tween(280, easing = SidebarEasing)) { it / 36 } +
                                    fadeIn(tween(220, easing = SidebarEasing)))
                                    .togetherWith(
                                        scaleOut(tween(170, easing = SidebarEasing), targetScale = 0.98f) +
                                            fadeOut(tween(140))
                                    )
                            },
                            label = "albumSwitchTransition"
                        ) { _ ->
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
                                    PinchZoomGridContainer(
                                        currentColumns = uiState.gridColumns,
                                        onColumnsChange = viewModel::setGridColumns,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(effectiveColumns),
                                            state = gridState,
                                            contentPadding = PaddingValues(
                                                start = if (effectiveShowSidebar) 2.dp else 8.dp,
                                                end = 8.dp,
                                                top = 2.dp,
                                                bottom = if (uiState.isSelectionMode) 112.dp else 28.dp
                                            ),
                                            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                                            verticalArrangement = Arrangement.spacedBy(1.5.dp),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .pointerInput(Unit) {
                                                    var targetSelectState = true
                                                    var lastDraggedId: String? = null
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = { startOffset ->
                                                            val hitId = findMediaIdAtOffset(
                                                                gridState = gridState,
                                                                entries = latestEntries,
                                                                pointerOffset = startOffset
                                                            )
                                                            if (hitId != null) {
                                                                targetSelectState = hitId !in latestSelectedIds
                                                                lastDraggedId = hitId
                                                                viewModel.setItemSelected(hitId, targetSelectState)
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            }
                                                        },
                                                        onDragEnd = {
                                                            autoScrollDelta = 0f
                                                            lastDraggedId = null
                                                        },
                                                        onDragCancel = {
                                                            autoScrollDelta = 0f
                                                            lastDraggedId = null
                                                        },
                                                        onDrag = { change, _ ->
                                                            change.consume()
                                                            val pos = change.position
                                                            val hitId = findMediaIdAtOffset(
                                                                gridState = gridState,
                                                                entries = latestEntries,
                                                                pointerOffset = pos
                                                            )
                                                            if (hitId != null && hitId != lastDraggedId) {
                                                                lastDraggedId = hitId
                                                                viewModel.setItemSelected(hitId, targetSelectState)
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            }
                                                            val viewportHeight = size.height.toFloat()
                                                            val hotZone = viewportHeight * 0.14f
                                                            autoScrollDelta = when {
                                                                pos.y > viewportHeight - hotZone -> 24f
                                                                pos.y < hotZone -> -24f
                                                                else -> 0f
                                                            }
                                                        }
                                                    )
                                                }
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
                                                        is FolderGridEntry.SubfolderTripleRow -> "subfolder_row"
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

                                                    is FolderGridEntry.SubfolderTripleRow -> {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 4.dp, vertical = 6.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                        ) {
                                                            FolderCard(
                                                                folder = entry.first,
                                                                isUnmeteredWifi = uiState.isUnmeteredWifi,
                                                                showDriveBadge = uiState.showCloudBadges && !entry.first.isLocal,
                                                                onClick = { onOpenSubfolder(entry.first.id, entry.first.name) },
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            if (entry.second != null) {
                                                                FolderCard(
                                                                    folder = entry.second,
                                                                    isUnmeteredWifi = uiState.isUnmeteredWifi,
                                                                    showDriveBadge = uiState.showCloudBadges && !entry.second.isLocal,
                                                                    onClick = { onOpenSubfolder(entry.second.id, entry.second.name) },
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                            } else {
                                                                Spacer(Modifier.weight(1f))
                                                            }
                                                            if (entry.third != null) {
                                                                FolderCard(
                                                                    folder = entry.third,
                                                                    isUnmeteredWifi = uiState.isUnmeteredWifi,
                                                                    showDriveBadge = uiState.showCloudBadges && !entry.third.isLocal,
                                                                    onClick = { onOpenSubfolder(entry.third.id, entry.third.name) },
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                            } else {
                                                                Spacer(Modifier.weight(1f))
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
                                                            showCloudBadge = uiState.showCloudBadges,
                                                            sharedTransitionScope = sharedTransitionScope,
                                                            animatedVisibilityScope = animatedVisibilityScope,
                                                            onClick = {
                                                                if (uiState.isSelectionMode) {
                                                                    viewModel.toggleSelection(entry.item.id)
                                                                } else {
                                                                    onOpenViewer(
                                                                        uiState.folderId,
                                                                        entry.item.id,
                                                                        uiState.viewMode == FolderViewMode.FLATTENED
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
                                            gridState = gridState,
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

            MultiSelectActionBar(
                visible = uiState.isSelectionMode,
                selectedCount = uiState.selectedIds.size,
                isExporting = uiState.isExporting,
                exportProgressText = uiState.exportProgressText,
                onShare = viewModel::shareSelected,
                onSaveToDevice = if (uiState.isLocalAlbum) null else viewModel::saveSelectedToDevice,
                onDeleteLocal = if (uiState.isLocalAlbum) viewModel::requestTrashSelectedLocal else null,
                onInfo = viewModel::openSelectedInfo,
                onClearSelection = viewModel::clearSelection,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
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

/**
 * Ultra-minimal Samsung One UI Gallery left Album & Album Group quick-switch rail:
 *  - Supports local albums, local Collections, AND nested Google Drive folders seamlessly.
 *  - Zero expand/collapse chevron clutter!
 *  - Collapsed group: sleek dark rounded outline wrapping its collage cover, name & count.
 *  - Expanded group: smoothly replaces its collapsed cover with a clean tall rounded outline
 *    containing ONLY its child albums directly inside (matching Samsung One UI Gallery!).
 */
@Composable
private fun SamsungAlbumSidebarRail(
    nodes: List<SidebarAlbumNode>,
    activeFolderId: String,
    isUnmeteredWifi: Boolean,
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

    LazyColumn(
        state = railListState,
        contentPadding = PaddingValues(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .width(84.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
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
                onExpandGroup = { tappedNode ->
                    // Expand tapped group (and auto-unwrap single-child folder chains),
                    // keeping active album's ancestors or replacing unrelated expanded top-level groups
                    val toExpand = collectAutoExpandIds(tappedNode)
                    val pathToTapped = findAncestorNodeIds(nodes, tappedNode.gridItem.itemId)
                    expandedNodeIds = pathToTapped + toExpand
                },
                onSelectAlbum = onSelectAlbum
            )
        }
    }
}

@Composable
private fun SidebarNodeView(
    node: SidebarAlbumNode,
    depth: Int,
    activeFolderId: String,
    isUnmeteredWifi: Boolean,
    expandedIds: Set<String>,
    onExpandGroup: (SidebarAlbumNode) -> Unit,
    onSelectAlbum: (String, String) -> Unit
) {
    val isGroup = node.children.isNotEmpty()
    val nodeId = node.gridItem.itemId

    if (!isGroup) {
        // Leaf Album (Local album or leaf Drive folder)
        val entry = node.gridItem as? AlbumGridItem.AlbumEntry ?: return
        val isActive = entry.folder.id == activeFolderId
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .oneUiClickable(
                    pressedScale = 0.92f,
                    hoveredScale = 1.035f,
                    onClick = { onSelectAlbum(entry.folder.id, entry.folder.name) }
                )
                .padding(horizontal = 2.dp, vertical = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(18.dp))
            ) {
                FolderCoverArtwork(
                    folder = entry.folder,
                    isUnmeteredWifi = isUnmeteredWifi,
                    showDriveBadge = !entry.folder.isLocal,
                    modifier = Modifier.fillMaxSize()
                )
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                width = 2.dp,
                                color = Color(0xFFE6C8C4),
                                shape = RoundedCornerShape(18.dp)
                            )
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.folder.name,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Text(
                text = entry.folder.mediaCount.toString(),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
            )
        }
        return
    }

    // Expandable Group: Local Collection OR Google Drive folder containing subfolders!
    val isExpanded = nodeId in expandedIds
    val containsActive = remember(node, activeFolderId) { nodeContainsAlbum(node, activeFolderId) }
    val groupTitle = node.gridItem.displayName
    val groupCount = when (val g = node.gridItem) {
        is AlbumGridItem.CollectionEntry -> g.totalMediaCount
        is AlbumGridItem.AlbumEntry -> g.folder.mediaCount
    }

    val containerModifier = if (depth == 0) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0A0A0C))
            .border(
                width = if (containsActive) 1.dp else 0.8.dp,
                color = if (containsActive) {
                    Color.White.copy(alpha = 0.22f)
                } else {
                    Color.White.copy(alpha = 0.13f)
                },
                shape = RoundedCornerShape(20.dp)
            )
            .padding(start = 5.dp, end = 5.dp, top = 7.dp, bottom = 10.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    Box(modifier = containerModifier) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                (fadeIn(tween(200, easing = SidebarEasing)) + expandVertically(tween(220, easing = SidebarEasing)))
                    .togetherWith(
                        fadeOut(tween(140)) + shrinkVertically(tween(180, easing = SidebarEasing))
                    )
            },
            label = "sidebarGroupExpand_$nodeId"
        ) { expanded ->
            if (!expanded) {
                // Collapsed Group Card: clean cover + title + count (NO chevron icon!)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .oneUiClickable(
                            pressedScale = 0.92f,
                            hoveredScale = 1.03f,
                            onClick = { onExpandGroup(node) }
                        )
                        .padding(horizontal = if (depth == 0) 1.dp else 2.dp)
                ) {
                    when (val g = node.gridItem) {
                        is AlbumGridItem.CollectionEntry -> {
                            CollectionCoverArtwork(
                                previewCovers = g.previewCovers,
                                hasDriveChild = g.hasDriveChild,
                                isUnmeteredWifi = isUnmeteredWifi,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                            )
                        }
                        is AlbumGridItem.AlbumEntry -> {
                            FolderCoverArtwork(
                                folder = g.folder,
                                isUnmeteredWifi = isUnmeteredWifi,
                                showDriveBadge = !g.folder.isLocal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = groupTitle,
                        fontSize = 10.5.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                    Text(
                        text = groupCount.toString(),
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                    )
                }
            } else {
                // Expanded Group (Samsung One UI style!): displays ONLY its child albums cleanly inside!
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    node.children.forEach { child ->
                        SidebarNodeView(
                            node = child,
                            depth = depth + 1,
                            activeFolderId = activeFolderId,
                            isUnmeteredWifi = isUnmeteredWifi,
                            expandedIds = expandedIds,
                            onExpandGroup = onExpandGroup,
                            onSelectAlbum = onSelectAlbum
                        )
                    }
                }
            }
        }
    }
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

@Composable
private fun ActiveOptionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFF1F1F24),
        modifier = Modifier
            .border(
                width = 0.8.dp,
                color = Color.White.copy(alpha = 0.14f),
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
                tint = Color.White.copy(alpha = 0.86f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = Color.White.copy(alpha = 0.94f),
                maxLines = 1
            )
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Reset",
                    tint = Color.White.copy(alpha = 0.9f),
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
        containerColor = Color(0xFF232326),
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

private fun findMediaIdAtOffset(
    gridState: LazyGridState,
    entries: List<FolderGridEntry>,
    pointerOffset: Offset
): String? {
    val adjustedY = pointerOffset.y - gridState.layoutInfo.beforeContentPadding
    val hitItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        val left = info.offset.x.toFloat()
        val top = info.offset.y.toFloat()
        val right = left + info.size.width.toFloat()
        val bottom = top + info.size.height.toFloat()
        pointerOffset.x in left..right && adjustedY in top..bottom
    } ?: return null

    val entry = entries.getOrNull(hitItem.index) as? FolderGridEntry.MediaCell
    return entry?.item?.id
}

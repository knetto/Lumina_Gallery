package com.luminagallery.drive.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.MoveUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luminagallery.drive.domain.model.AlbumGridItem
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Drop target shown at the top of [DraggableAlbumGrid] while dragging an album or nested
 * collection inside a Collection so users can drag items straight out into the parent/root folder.
 */
data class AlbumParentDropZone(
    val key: String,
    val label: String,
    val targetParentId: String?,
    val isRoot: Boolean = targetParentId == null
)

/**
 * Samsung One UI Gallery–faithful Album & Collection grid (supports 2..5 pinch-zoomed columns):
 *
 *  1. **Hold without dragging** -> selects the album cleanly (entering Samsung Album Selection Mode
 *     with top-left circular checkmarks and bottom action bar for Group / Move / Rename / Ungroup).
 *  2. **Hold & drag** -> lifts a pure photo-only rounded cover in a dedicated top-level floating
 *     overlay (never clipped, never fighting `animateItem()`, silky 120Hz under your finger!)
 *     while the source slot smoothly opens up.
 *  3. **Drag up into the top parent-folder drop zone (when inside a Collection)** -> lifts the album
 *     or nested collection cleanly out into its parent collection or root Albums folder!
 *  4. **Drag onto another album or collection's generous center zone (64%)** -> target album stays
 *     rock-solid (never runs away!) and highlights cleanly; releasing immediately merges the two
 *     albums into a Samsung-style Collection (or adds into an existing Collection).
 *  5. **Drag into the outer edge / gap strip (with a gentle 130ms dwell)** -> smoothly swaps and
 *     reorders albums with spring `animateItem()` placement and persists order to Room!
 */
@Composable
fun DraggableAlbumGrid(
    items: List<AlbumGridItem>,
    isUnmeteredWifi: Boolean,
    selectedIds: Set<String>,
    contentPadding: PaddingValues,
    onItemClick: (AlbumGridItem) -> Unit,
    onToggleSelect: (AlbumGridItem) -> Unit,
    onReorderCommitted: (List<AlbumGridItem>) -> Unit,
    onDropToGroup: (dragged: AlbumGridItem, target: AlbumGridItem) -> Unit,
    columns: Int = 3,
    topDropZones: List<AlbumParentDropZone> = emptyList(),
    onDropOnTopZone: ((dragged: AlbumGridItem, zone: AlbumParentDropZone) -> Unit)? = null,
    headerContent: (@Composable () -> Unit)? = null,
    forceSelectionMode: Boolean = false,
    dragReorderEnabled: Boolean = true,
    showCloudBadges: Boolean = true,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState()
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val isSelectionMode = forceSelectionMode || selectedIds.isNotEmpty()
    val safeColumns = columns.coerceIn(2, 5)
    val isCompactCards = safeColumns >= 4
    val hSpacing = when (safeColumns) {
        2 -> 14.dp
        3 -> 12.dp
        4 -> 9.dp
        else -> 7.dp
    }
    val vSpacing = when (safeColumns) {
        2 -> 20.dp
        3 -> 18.dp
        4 -> 14.dp
        else -> 12.dp
    }

    // Working order updated smoothly during drag
    var workingItems by remember { mutableStateOf(items) }
    var draggingItem by remember { mutableStateOf<AlbumGridItem?>(null) }
    var floatingTopLeft by remember { mutableStateOf(Offset.Zero) }
    var floatingCoverWidthPx by remember { mutableIntStateOf(0) }
    var mergeTargetId by remember { mutableStateOf<String?>(null) }
    var hoveredTopZoneKey by remember { mutableStateOf<String?>(null) }
    var pendingReorderTargetIdx by remember { mutableStateOf<Int?>(null) }
    var autoScrollDelta by remember { mutableFloatStateOf(0f) }

    val topDropZoneRects = remember { mutableStateMapOf<String, Rect>() }
    var topDropBarOffsetInBox by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(items, draggingItem) {
        if (draggingItem == null) {
            workingItems = items
        }
    }

    // Smooth auto-scroll while holding near top/bottom viewport edge
    LaunchedEffect(autoScrollDelta) {
        while (autoScrollDelta != 0f) {
            gridState.scrollBy(autoScrollDelta)
            delay(16L)
        }
    }

    // 130ms dwell timer before swapping on outer edges so crossing an edge to merge on center
    // NEVER makes the target album run away from under your finger!
    var didReorderDuringDrag by remember { mutableStateOf(false) }
    LaunchedEffect(pendingReorderTargetIdx, draggingItem?.itemId) {
        val targetIdx = pendingReorderTargetIdx ?: return@LaunchedEffect
        val activeId = draggingItem?.itemId ?: return@LaunchedEffect
        delay(130L)
        val currentIdx = workingItems.indexOfFirst { it.itemId == activeId }
        if (currentIdx >= 0 && targetIdx in workingItems.indices && currentIdx != targetIdx) {
            val mutable = workingItems.toMutableList()
            val moved = mutable.removeAt(currentIdx)
            mutable.add(targetIdx, moved)
            workingItems = mutable
            didReorderDuringDrag = true
            pendingReorderTargetIdx = null
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val headerOffset = if (headerContent != null) 1 else 0
    val latestItems by rememberUpdatedState(items)
    val latestIsSelectionMode by rememberUpdatedState(isSelectionMode)
    val latestDragReorderEnabled by rememberUpdatedState(dragReorderEnabled)
    val latestHeaderOffset by rememberUpdatedState(headerOffset)
    val latestOnToggleSelect by rememberUpdatedState(onToggleSelect)
    val latestOnReorder by rememberUpdatedState(onReorderCommitted)
    val latestOnGroup by rememberUpdatedState(onDropToGroup)
    val latestTopDropZones by rememberUpdatedState(topDropZones)
    val latestOnDropOnTopZone by rememberUpdatedState(onDropOnTopZone)

    val topZoneBandHeightPx = with(density) { 86.dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(safeColumns),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(hSpacing),
            verticalArrangement = Arrangement.spacedBy(vSpacing),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var grabOffsetInCover = Offset.Zero
                    var totalTravelPx = 0f
                    var startedItem: AlbumGridItem? = null

                    detectDragGesturesAfterLongPress(
                        onDragStart = { startPointer ->
                            val topPadPx = gridState.layoutInfo.beforeContentPadding
                            val adjustedY = startPointer.y - topPadPx
                            val hitInfo = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                info.index >= latestHeaderOffset &&
                                    startPointer.x >= info.offset.x &&
                                    startPointer.x <= info.offset.x + info.size.width &&
                                    adjustedY >= info.offset.y &&
                                    adjustedY <= info.offset.y + info.size.height
                            }
                            val listIdx = hitInfo?.index?.minus(latestHeaderOffset)
                            val item = listIdx?.let { workingItems.getOrNull(it) }
                            if (hitInfo != null && item != null) {
                                // Never drag-reorder while in selection mode or viewing a filtered subset
                                if (latestIsSelectionMode || !latestDragReorderEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latestOnToggleSelect(item)
                                    startedItem = null
                                    draggingItem = null
                                    return@detectDragGesturesAfterLongPress
                                }
                                startedItem = item
                                draggingItem = item
                                floatingCoverWidthPx = hitInfo.size.width
                                val tileTopLeft = Offset(
                                    x = hitInfo.offset.x.toFloat(),
                                    y = hitInfo.offset.y.toFloat() + topPadPx
                                )
                                grabOffsetInCover = startPointer - tileTopLeft
                                floatingTopLeft = tileTopLeft
                                mergeTargetId = null
                                hoveredTopZoneKey = null
                                pendingReorderTargetIdx = null
                                didReorderDuringDrag = false
                                totalTravelPx = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDragCancel = {
                            draggingItem = null
                            mergeTargetId = null
                            hoveredTopZoneKey = null
                            pendingReorderTargetIdx = null
                            autoScrollDelta = 0f
                            workingItems = latestItems
                        },
                        onDragEnd = {
                            val active = draggingItem
                            val mergeId = mergeTargetId
                            val topZoneKey = hoveredTopZoneKey
                            val snapshot = workingItems
                            val reordered = didReorderDuringDrag
                            val travel = totalTravelPx
                            val initial = startedItem

                            autoScrollDelta = 0f
                            pendingReorderTargetIdx = null
                            draggingItem = null
                            mergeTargetId = null
                            hoveredTopZoneKey = null

                            val hitTopZone = topZoneKey?.let { key ->
                                latestTopDropZones.firstOrNull { it.key == key }
                            }
                            val mergeTarget = snapshot.firstOrNull { it.itemId == mergeId }

                            when {
                                // 0. Dropped onto parent/root folder drop zone -> MOVE OUT!
                                active != null && hitTopZone != null && latestOnDropOnTopZone != null -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latestOnDropOnTopZone?.invoke(active, hitTopZone)
                                }
                                // 1. Dropped on top of another album / collection -> MERGE!
                                active != null && mergeTarget != null && active.itemId != mergeTarget.itemId -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    latestOnGroup(active, mergeTarget)
                                }
                                // 2. Reordered smoothly -> persist new order!
                                reordered -> {
                                    latestOnReorder(snapshot)
                                }
                                // 3. Long-pressed & released without dragging -> Samsung Select Mode!
                                initial != null && travel < 20f -> {
                                    latestOnToggleSelect(initial)
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            val active = draggingItem ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            totalTravelPx += hypot(dragAmount.x, dragAmount.y)

                            val pointer = change.position
                            floatingTopLeft = pointer - grabOffsetInCover

                            val topPadPx = gridState.layoutInfo.beforeContentPadding
                            val currentIdx = workingItems.indexOfFirst { it.itemId == active.itemId }

                            // Use the floating cover's visual center for ultra-natural Samsung hit detection
                            val coverCenterX = floatingTopLeft.x + floatingCoverWidthPx / 2f
                            val coverCenterYInViewport = floatingTopLeft.y + floatingCoverWidthPx / 2f
                            val coverCenterY = coverCenterYInViewport - topPadPx

                            // 1. Check top parent-folder drop zones FIRST (when inside a Collection)
                            if (latestTopDropZones.isNotEmpty() && latestOnDropOnTopZone != null) {
                                val probeY = minOf(pointer.y, coverCenterYInViewport)
                                val matchedZoneKey = if (probeY <= topZoneBandHeightPx) {
                                    val byRect = latestTopDropZones.firstOrNull { zone ->
                                        val r = topDropZoneRects[zone.key]
                                        r != null && pointer.x in r.left..r.right
                                    }?.key
                                    byRect ?: latestTopDropZones.firstOrNull()?.key
                                } else {
                                    null
                                }
                                if (matchedZoneKey != null) {
                                    if (hoveredTopZoneKey != matchedZoneKey) {
                                        hoveredTopZoneKey = matchedZoneKey
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    mergeTargetId = null
                                    pendingReorderTargetIdx = null
                                    autoScrollDelta = 0f
                                    return@detectDragGesturesAfterLongPress
                                } else {
                                    hoveredTopZoneKey = null
                                }
                            }

                            val hitTarget = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                val itemIdx = info.index - latestHeaderOffset
                                itemIdx >= 0 &&
                                    itemIdx != currentIdx &&
                                    coverCenterX >= info.offset.x &&
                                    coverCenterX <= info.offset.x + info.size.width &&
                                    coverCenterY >= info.offset.y &&
                                    coverCenterY <= info.offset.y + info.size.height
                            }

                            if (hitTarget != null) {
                                val targetIdx = hitTarget.index - latestHeaderOffset
                                val targetItem = workingItems.getOrNull(targetIdx)
                                if (targetItem != null) {
                                    // Measure distance to the square cover artwork's center (top width x width)
                                    val coverHeight = hitTarget.size.width.toFloat().coerceAtLeast(1f)
                                    val targetCenterX = hitTarget.offset.x + hitTarget.size.width / 2f
                                    val targetCenterY = hitTarget.offset.y + coverHeight / 2f
                                    val dxNorm = abs(coverCenterX - targetCenterX) / (hitTarget.size.width / 2f)
                                    val dyNorm = abs(coverCenterY - targetCenterY) / (coverHeight / 2f)

                                    if (dxNorm < 0.66f && dyNorm < 0.68f) {
                                        // Generous center MERGE zone — target stays rock solid!
                                        pendingReorderTargetIdx = null
                                        if (mergeTargetId != targetItem.itemId) {
                                            mergeTargetId = targetItem.itemId
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    } else {
                                        // Outer rim -> schedule smooth dwell reorder
                                        mergeTargetId = null
                                        if (pendingReorderTargetIdx != targetIdx) {
                                            pendingReorderTargetIdx = targetIdx
                                        }
                                    }
                                }
                            } else {
                                // Hovering in the gap between tiles -> reorder to nearest slot!
                                mergeTargetId = null
                                val nearest = gridState.layoutInfo.visibleItemsInfo
                                    .filter { it.index >= latestHeaderOffset }
                                    .minByOrNull { info ->
                                        val cx = info.offset.x + info.size.width / 2f
                                        val cy = info.offset.y + info.size.width / 2f
                                        hypot(coverCenterX - cx, coverCenterY - cy)
                                    }
                                val nearestIdx = nearest?.index?.minus(latestHeaderOffset)
                                if (nearestIdx != null && nearestIdx != currentIdx && totalTravelPx > 32f) {
                                    if (pendingReorderTargetIdx != nearestIdx) {
                                        pendingReorderTargetIdx = nearestIdx
                                    }
                                } else {
                                    pendingReorderTargetIdx = null
                                }
                            }

                            // Smooth edge auto-scroll (skip top auto-scroll when top drop zones exist)
                            val vh = size.height.toFloat()
                            val hotZone = vh * 0.14f
                            autoScrollDelta = when {
                                pointer.y > vh - hotZone -> 24f
                                pointer.y < hotZone && latestTopDropZones.isEmpty() -> -24f
                                else -> 0f
                            }
                        }
                    )
                }
        ) {
            if (headerContent != null) {
                item(
                    key = "draggable_grid_header",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    headerContent()
                }
            }

            itemsIndexed(
                items = workingItems,
                key = { _, entry -> "${entry.itemKind}_${entry.itemId}" },
                contentType = { _, entry -> entry.itemKind }
            ) { _, entry ->
                val isDraggedSource = entry.itemId == draggingItem?.itemId
                val isMergeTarget = entry.itemId == mergeTargetId
                val isSelected = entry.itemId in selectedIds

                val targetScale by animateFloatAsState(
                    targetValue = if (isMergeTarget) 1.07f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "mergeTargetScale"
                )

                Box(
                    modifier = Modifier
                        .animateItem(
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                        .alpha(if (isDraggedSource) 0.0f else 1f)
                        .graphicsLayer {
                            scaleX = targetScale
                            scaleY = targetScale
                        }
                ) {
                    when (entry) {
                        is AlbumGridItem.AlbumEntry -> {
                            FolderCard(
                                folder = entry.folder,
                                isUnmeteredWifi = isUnmeteredWifi,
                                showDriveBadge = showCloudBadges && !entry.folder.isLocal,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                compact = isCompactCards,
                                onClick = {
                                    if (isSelectionMode) onToggleSelect(entry) else onItemClick(entry)
                                }
                            )
                        }

                        is AlbumGridItem.CollectionEntry -> {
                            CollectionCard(
                                collection = if (showCloudBadges) entry else entry.copy(hasDriveChild = false),
                                isUnmeteredWifi = isUnmeteredWifi,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                compact = isCompactCards,
                                onClick = {
                                    if (isSelectionMode) onToggleSelect(entry) else onItemClick(entry)
                                }
                            )
                        }
                    }

                    // Crisp Samsung-style soft merge glow ring around the target album cover
                    if (isMergeTarget) {
                        val coverDp = with(density) {
                            floatingCoverWidthPx.coerceAtLeast(1).toDp()
                        }
                        Box(
                            modifier = Modifier
                                .size(coverDp)
                                .clip(RoundedCornerShape(18.dp))
                                .border(
                                    width = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(18.dp)
                                )
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        )
                    }
                }
            }
        }

        // Top Parent-Folder Drop Bar (slides in smoothly while dragging inside a Collection!)
        AnimatedVisibility(
            visible = draggingItem != null && topDropZones.isNotEmpty() && onDropOnTopZone != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .onGloballyPositioned { coords ->
                    topDropBarOffsetInBox = coords.positionInParent()
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                topDropZones.forEach { zone ->
                    val isHovered = hoveredTopZoneKey == zone.key
                    val bgColor by animateColorAsState(
                        targetValue = if (isHovered) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f)
                        },
                        label = "topDropBg_${zone.key}"
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (isHovered) 1.03f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "topDropScale_${zone.key}"
                    )
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = bgColor,
                        shadowElevation = if (isHovered) 10.dp else 4.dp,
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .onGloballyPositioned { coords ->
                                val b = coords.boundsInParent()
                                topDropZoneRects[zone.key] = Rect(
                                    left = topDropBarOffsetInBox.x + b.left,
                                    top = topDropBarOffsetInBox.y + b.top,
                                    right = topDropBarOffsetInBox.x + b.right,
                                    bottom = topDropBarOffsetInBox.y + b.bottom
                                )
                            }
                            .border(
                                width = if (isHovered) 2.dp else 1.dp,
                                color = if (isHovered) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                },
                                shape = RoundedCornerShape(20.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            Icon(
                                imageVector = if (zone.isRoot) Icons.Rounded.DriveFileMove else Icons.Rounded.MoveUp,
                                contentDescription = null,
                                tint = if (isHovered) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = zone.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isHovered) {
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
            }
        }

        // Dedicated Top-Level Floating Cover Overlay (Samsung Gallery drag aesthetic:
        // pure rounded photo cover gliding seamlessly above the grid at 120Hz with zero clipping!)
        val activeFloating = draggingItem
        if (activeFloating != null && floatingCoverWidthPx > 0) {
            val coverSizeDp = with(density) { floatingCoverWidthPx.toDp() }
            val isHoveringAnyTarget = mergeTargetId != null || hoveredTopZoneKey != null
            val liftScale by animateFloatAsState(
                targetValue = if (isHoveringAnyTarget) 0.84f else 1.05f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "floatingLiftScale"
            )

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = floatingTopLeft.x.roundToInt(),
                            y = floatingTopLeft.y.roundToInt()
                        )
                    }
                    .size(coverSizeDp)
                    .graphicsLayer {
                        scaleX = liftScale
                        scaleY = liftScale
                        alpha = 0.88f
                    }
                    .shadow(
                        elevation = 20.dp,
                        shape = RoundedCornerShape(18.dp),
                        ambientColor = Color.Black,
                        spotColor = Color.Black
                    )
            ) {
                when (activeFloating) {
                    is AlbumGridItem.AlbumEntry -> {
                        FolderCoverArtwork(
                            folder = activeFloating.folder,
                            isUnmeteredWifi = isUnmeteredWifi
                        )
                    }

                    is AlbumGridItem.CollectionEntry -> {
                        CollectionCoverArtwork(
                            previewCovers = activeFloating.previewCovers,
                            hasDriveChild = activeFloating.hasDriveChild,
                            isUnmeteredWifi = isUnmeteredWifi
                        )
                    }
                }
            }
        }
    }
}

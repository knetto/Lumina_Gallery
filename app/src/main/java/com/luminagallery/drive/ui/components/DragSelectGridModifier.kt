package com.luminagallery.drive.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Samsung One UI / Google Photos-style continuous drag-to-select AND drag-to-move modifier
 * across a `LazyVerticalGrid`:
 *  - Long-press an unselected photo & release (without dragging) -> cleanly selects that photo
 *    and consumes the UP event on `PointerEventPass.Initial` BEFORE child `MediaGridItem.clickable`
 *    can see it, so releasing never accidentally toggles the selection back off!
 *  - Long-press an unselected photo & slide across other photos -> smooth range selection with
 *    O(1) lookup, batched range updates, and auto-scroll.
 *  - Long-press an ALREADY-SELECTED photo (when [moveDragEnabled] is true) -> immediately lifts
 *    all selected photos so you can drag them into the parent folder or any sidebar album!
 *  - Long-press a SINGLE unselected photo and drag straight out of the grid (left into the
 *    album sidebar or up into the top parent-folder drop zone) -> seamlessly lifts that photo
 *    to move it in one fluid gesture!
 */
fun Modifier.photoGridDragSelect(
    gridState: LazyGridState,
    orderedItemIds: List<String>,
    keyToItemId: (Any) -> String?,
    selectedIds: Set<String>,
    onSetItemSelected: (String, Boolean) -> Unit,
    onSetRangeSelected: ((Set<String>, Boolean) -> Unit)? = null,
    moveDragEnabled: Boolean = false,
    onMoveDragStart: ((draggedIds: Set<String>, anchorItemId: String, localOffset: Offset) -> Unit)? = null,
    onMoveDragMove: ((localOffset: Offset) -> Unit)? = null,
    onMoveDragEnd: ((cancelled: Boolean) -> Unit)? = null
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val autoScrollZonePx = remember(density) { with(density) { 64.dp.toPx() } }
    val maxAutoScrollSpeedPx = remember(density) { with(density) { 18.dp.toPx() } }
    val escapeLeftThresholdPx = remember(density) { with(density) { 14.dp.toPx() } }
    val escapeTopThresholdPx = remember(density) { with(density) { 54.dp.toPx() } }

    val indexById = remember(orderedItemIds) {
        HashMap<String, Int>(orderedItemIds.size * 2).apply {
            orderedItemIds.forEachIndexed { idx, id -> put(id, idx) }
        }
    }

    val latestOrderedIds by rememberUpdatedState(orderedItemIds)
    val latestIndexById by rememberUpdatedState(indexById)
    val latestSelectedIds by rememberUpdatedState(selectedIds)
    val latestKeyMapper by rememberUpdatedState(keyToItemId)
    val latestOnSetSelected by rememberUpdatedState(onSetItemSelected)
    val latestOnSetRange by rememberUpdatedState(onSetRangeSelected)
    val latestMoveDragEnabled by rememberUpdatedState(moveDragEnabled)
    val latestOnMoveStart by rememberUpdatedState(onMoveDragStart)
    val latestOnMoveMove by rememberUpdatedState(onMoveDragMove)
    val latestOnMoveEnd by rememberUpdatedState(onMoveDragEnd)

    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var lastDragPosition by remember { mutableStateOf<Offset?>(null) }
    val dragSessionRef = remember { DragSelectSession() }

    fun itemIdAtOffset(offset: Offset): String? {
        val layoutInfo = gridState.layoutInfo
        val adjustedY = offset.y - layoutInfo.beforeContentPadding
        val hit = layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
            val left = itemInfo.offset.x.toFloat()
            val top = itemInfo.offset.y.toFloat()
            val right = left + itemInfo.size.width
            val bottom = top + itemInfo.size.height
            offset.x in left..right && adjustedY in top..bottom
        } ?: return null
        return latestKeyMapper(hit.key)
    }

    fun applyBatch(ids: Set<String>, selected: Boolean) {
        if (ids.isEmpty()) return
        val batchCb = latestOnSetRange
        if (batchCb != null) {
            batchCb(ids, selected)
        } else {
            ids.forEach { latestOnSetSelected(it, selected) }
        }
    }

    fun applyRange(currentIndex: Int) {
        val ids = latestOrderedIds
        val anchor = dragSessionRef.anchorIndex
        if (anchor !in ids.indices || currentIndex !in ids.indices) return
        if (currentIndex == dragSessionRef.lastTargetIndex) return

        val low = minOf(anchor, currentIndex)
        val high = maxOf(anchor, currentIndex)
        val prevTarget = dragSessionRef.lastTargetIndex
        val prevLow = if (prevTarget in ids.indices) minOf(anchor, prevTarget) else low
        val prevHigh = if (prevTarget in ids.indices) maxOf(anchor, prevTarget) else high

        val toSelect = LinkedHashSet<String>()
        val toDeselect = LinkedHashSet<String>()

        // Restore items that fell outside the shrunk range back to their pre-drag state
        for (idx in prevLow..prevHigh) {
            if (idx !in low..high && idx in ids.indices) {
                val id = ids[idx]
                if (id in dragSessionRef.baselineSelection) toSelect.add(id) else toDeselect.add(id)
            }
        }
        // Apply target selection state to newly added indices
        val targetBucket = if (dragSessionRef.selectTargetState) toSelect else toDeselect
        for (idx in low..high) {
            if (idx !in prevLow..prevHigh || prevTarget < 0) {
                targetBucket.add(ids[idx])
            }
        }
        applyBatch(toSelect, true)
        applyBatch(toDeselect, false)

        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        dragSessionRef.lastTargetIndex = currentIndex
    }

    fun startLongPressSession(startOffset: Offset): Boolean {
        val hitId = itemIdAtOffset(startOffset) ?: return false
        val idx = latestIndexById[hitId] ?: return false
        val alreadySelected = hitId in latestSelectedIds

        // Samsung Gallery behavior: long-pressing an ALREADY-SELECTED photo immediately
        // lifts all selected photos for drag-and-drop into parent folder or sidebar albums!
        if (alreadySelected && latestMoveDragEnabled && latestOnMoveStart != null) {
            dragSessionRef.reset()
            dragSessionRef.isMoveDragging = true
            dragSessionRef.anchorItemId = hitId
            autoScrollSpeed = 0f
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            latestOnMoveStart?.invoke(latestSelectedIds, hitId, startOffset)
            return true
        }

        dragSessionRef.baselineSelection = latestSelectedIds
        dragSessionRef.anchorIndex = idx
        dragSessionRef.anchorItemId = hitId
        dragSessionRef.lastTargetIndex = idx
        dragSessionRef.selectTargetState = !alreadySelected
        dragSessionRef.isMoveDragging = false
        dragSessionRef.startOffset = startOffset
        lastDragPosition = startOffset
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        applyBatch(setOf(hitId), dragSessionRef.selectTargetState)
        return true
    }

    fun handleDragMove(pos: Offset) {
        if (dragSessionRef.isMoveDragging) {
            autoScrollSpeed = 0f
            latestOnMoveMove?.invoke(pos)
            return
        }
        if (dragSessionRef.anchorIndex < 0) return
        lastDragPosition = pos

        // Single-gesture photo drag-out: if user grabbed 1 photo (no range extended yet)
        // and dragged directly left toward the sidebar or straight up to the top parent drop zone,
        // seamlessly lift that photo into move-drag mode!
        val anchorId = dragSessionRef.anchorItemId
        if (latestMoveDragEnabled &&
            latestOnMoveStart != null &&
            anchorId != null &&
            dragSessionRef.selectTargetState &&
            dragSessionRef.lastTargetIndex == dragSessionRef.anchorIndex &&
            (pos.x <= escapeLeftThresholdPx || (pos.y <= escapeTopThresholdPx && pos.y < dragSessionRef.startOffset.y - 24f))
        ) {
            dragSessionRef.isMoveDragging = true
            autoScrollSpeed = 0f
            val moveIds = latestSelectedIds.ifEmpty { setOf(anchorId) }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            latestOnMoveStart?.invoke(moveIds, anchorId, pos)
            return
        }

        val viewportHeight = gridState.layoutInfo.viewportSize.height.toFloat()
        autoScrollSpeed = when {
            viewportHeight > 0f && pos.y < autoScrollZonePx -> {
                val factor = ((autoScrollZonePx - pos.y) / autoScrollZonePx).coerceIn(0f, 1f)
                -maxAutoScrollSpeedPx * factor
            }
            viewportHeight > 0f && pos.y > viewportHeight - autoScrollZonePx -> {
                val factor = ((pos.y - (viewportHeight - autoScrollZonePx)) / autoScrollZonePx).coerceIn(0f, 1f)
                maxAutoScrollSpeedPx * factor
            }
            else -> 0f
        }
        val hitId = itemIdAtOffset(pos) ?: return
        val idx = latestIndexById[hitId] ?: return
        applyRange(idx)
    }

    fun finishLongPressSession(cancelled: Boolean) {
        val wasMove = dragSessionRef.isMoveDragging
        dragSessionRef.reset()
        lastDragPosition = null
        autoScrollSpeed = 0f
        if (wasMove) {
            latestOnMoveEnd?.invoke(cancelled)
        }
    }

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed == 0f) return@LaunchedEffect
        while (isActive && autoScrollSpeed != 0f) {
            gridState.scrollBy(autoScrollSpeed)
            lastDragPosition?.let { pos ->
                if (!dragSessionRef.isMoveDragging) {
                    val hitId = itemIdAtOffset(pos)
                    val idx = hitId?.let { latestIndexById[it] } ?: -1
                    if (idx >= 0) applyRange(idx)
                }
            }
            delay(16L)
        }
    }

    pointerInput(gridState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (!startLongPressSession(longPress.position)) return@awaitEachGesture

            // Consume all subsequent MOVE and UP events on PointerEventPass.Initial (parent -> child)
            // so lifting the finger after a stationary long-press NEVER leaks an unconsumed UP to
            // child MediaGridItem.clickable (which would immediately toggle the item back off!).
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
                        finishLongPressSession(cancelled = false)
                        break
                    }
                    if (moved) {
                        handleDragMove(pos)
                    }
                }
            } catch (ce: CancellationException) {
                finishLongPressSession(cancelled = true)
                throw ce
            }
        }
    }
}

private class DragSelectSession {
    var anchorIndex: Int = -1
    var anchorItemId: String? = null
    var lastTargetIndex: Int = -1
    var selectTargetState: Boolean = true
    var baselineSelection: Set<String> = emptySet()
    var isMoveDragging: Boolean = false
    var startOffset: Offset = Offset.Zero

    fun reset() {
        anchorIndex = -1
        anchorItemId = null
        lastTargetIndex = -1
        baselineSelection = emptySet()
        isMoveDragging = false
        startOffset = Offset.Zero
    }
}

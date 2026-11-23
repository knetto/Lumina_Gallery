package com.luminagallery.drive.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Samsung / Google Photos-style continuous drag-to-select across a `LazyVerticalGrid`:
 *  - O(1) index lookup via cached `indexById` map.
 *  - Batches range updates via [onSetRangeSelected] (or falls back to [onSetItemSelected])
 *    so dragging across 500 items never floods `StateFlow` per frame.
 *  - Continues updating range selection smoothly while auto-scrolling at top/bottom edges.
 */
fun Modifier.photoGridDragSelect(
    gridState: LazyGridState,
    orderedItemIds: List<String>,
    keyToItemId: (Any) -> String?,
    selectedIds: Set<String>,
    onSetItemSelected: (String, Boolean) -> Unit,
    onSetRangeSelected: ((Set<String>, Boolean) -> Unit)? = null
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val autoScrollZonePx = remember(density) { with(density) { 64.dp.toPx() } }
    val maxAutoScrollSpeedPx = remember(density) { with(density) { 18.dp.toPx() } }

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

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed == 0f) return@LaunchedEffect
        while (isActive && autoScrollSpeed != 0f) {
            gridState.scrollBy(autoScrollSpeed)
            lastDragPosition?.let { pos ->
                val hitId = itemIdAtOffset(pos)
                val idx = hitId?.let { latestIndexById[it] } ?: -1
                if (idx >= 0) applyRange(idx)
            }
            delay(16L)
        }
    }

    pointerInput(gridState) {
        detectDragGesturesAfterLongPress(
            onDragStart = { startOffset ->
                val hitId = itemIdAtOffset(startOffset) ?: return@detectDragGesturesAfterLongPress
                val idx = latestIndexById[hitId] ?: return@detectDragGesturesAfterLongPress
                dragSessionRef.baselineSelection = latestSelectedIds
                dragSessionRef.anchorIndex = idx
                dragSessionRef.lastTargetIndex = idx
                dragSessionRef.selectTargetState = hitId !in dragSessionRef.baselineSelection
                lastDragPosition = startOffset
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                applyBatch(setOf(hitId), dragSessionRef.selectTargetState)
            },
            onDragEnd = {
                dragSessionRef.reset()
                lastDragPosition = null
                autoScrollSpeed = 0f
            },
            onDragCancel = {
                dragSessionRef.reset()
                lastDragPosition = null
                autoScrollSpeed = 0f
            },
            onDrag = { change, _ ->
                if (dragSessionRef.anchorIndex < 0) return@detectDragGesturesAfterLongPress
                change.consume()
                val pos = change.position
                lastDragPosition = pos
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
                val hitId = itemIdAtOffset(pos) ?: return@detectDragGesturesAfterLongPress
                val idx = latestIndexById[hitId] ?: return@detectDragGesturesAfterLongPress
                applyRange(idx)
            }
        )
    }
}

private class DragSelectSession {
    var anchorIndex: Int = -1
    var lastTargetIndex: Int = -1
    var selectTargetState: Boolean = true
    var baselineSelection: Set<String> = emptySet()

    fun reset() {
        anchorIndex = -1
        lastTargetIndex = -1
        baselineSelection = emptySet()
    }
}

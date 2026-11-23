package com.luminagallery.drive.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
 *  - Long-press any photo tile to start selecting (or deselecting if the start tile was already selected).
 *  - Drag across rows and columns to sweep-select every photo in range with smooth edge auto-scroll
 *    and subtle haptic feedback.
 */
fun Modifier.photoGridDragSelect(
    gridState: LazyGridState,
    orderedItemIds: List<String>,
    keyToItemId: (Any) -> String?,
    selectedIds: Set<String>,
    onSetItemSelected: (String, Boolean) -> Unit
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val autoScrollZonePx = remember(density) { with(density) { 64.dp.toPx() } }
    val maxAutoScrollSpeedPx = remember(density) { with(density) { 18.dp.toPx() } }

    val latestOrderedIds by rememberUpdatedState(orderedItemIds)
    val latestSelectedIds by rememberUpdatedState(selectedIds)
    val latestKeyMapper by rememberUpdatedState(keyToItemId)
    val latestOnSetSelected by rememberUpdatedState(onSetItemSelected)

    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed == 0f) return@LaunchedEffect
        while (isActive && autoScrollSpeed != 0f) {
            gridState.scrollBy(autoScrollSpeed)
            delay(16L)
        }
    }

    fun itemIdAtOffset(offset: Offset): String? {
        val layoutInfo = gridState.layoutInfo
        val hit = layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
            val left = itemInfo.offset.x.toFloat()
            val top = itemInfo.offset.y.toFloat()
            val right = left + itemInfo.size.width
            val bottom = top + itemInfo.size.height
            offset.x in left..right && offset.y in top..bottom
        } ?: return null
        return latestKeyMapper(hit.key)
    }

    pointerInput(gridState) {
        var anchorIndex = -1
        var lastTargetIndex = -1
        var selectTargetState = true
        var baselineSelection: Set<String> = emptySet()

        fun applyRange(currentIndex: Int) {
            val ids = latestOrderedIds
            if (anchorIndex !in ids.indices || currentIndex !in ids.indices) return
            val low = minOf(anchorIndex, currentIndex)
            val high = maxOf(anchorIndex, currentIndex)
            val prevLow = if (lastTargetIndex in ids.indices) minOf(anchorIndex, lastTargetIndex) else low
            val prevHigh = if (lastTargetIndex in ids.indices) maxOf(anchorIndex, lastTargetIndex) else high

            // Restore items that fell outside the shrunk range back to their pre-drag state
            for (idx in prevLow..prevHigh) {
                if (idx !in low..high && idx in ids.indices) {
                    val id = ids[idx]
                    latestOnSetSelected(id, id in baselineSelection)
                }
            }
            // Apply target selection state to the active range
            for (idx in low..high) {
                val id = ids[idx]
                latestOnSetSelected(id, selectTargetState)
            }
            if (currentIndex != lastTargetIndex) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lastTargetIndex = currentIndex
            }
        }

        detectDragGesturesAfterLongPress(
            onDragStart = { startOffset ->
                val hitId = itemIdAtOffset(startOffset) ?: return@detectDragGesturesAfterLongPress
                val idx = latestOrderedIds.indexOf(hitId)
                if (idx < 0) return@detectDragGesturesAfterLongPress
                baselineSelection = latestSelectedIds
                anchorIndex = idx
                lastTargetIndex = idx
                selectTargetState = hitId !in baselineSelection
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                latestOnSetSelected(hitId, selectTargetState)
            },
            onDragEnd = {
                anchorIndex = -1
                lastTargetIndex = -1
                autoScrollSpeed = 0f
            },
            onDragCancel = {
                anchorIndex = -1
                lastTargetIndex = -1
                autoScrollSpeed = 0f
            },
            onDrag = { change, _ ->
                if (anchorIndex < 0) return@detectDragGesturesAfterLongPress
                change.consume()
                val pos = change.position
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
                val idx = latestOrderedIds.indexOf(hitId)
                if (idx >= 0) {
                    applyRange(idx)
                }
            }
        )
    }
}

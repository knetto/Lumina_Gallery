package com.luminagallery.drive.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Right-edge draggable fast-scroll thumb displaying a floating month/year date pill
 * (e.g. "Nov 2026") while scrubbing through large folders.
 * Defers offset reads to layout phase, excludes Android's edge-back gesture on the handle,
 * and conflates scroll jumps so scrolling stays smooth at 120Hz.
 */
@Composable
fun FastScrollThumb(
    gridState: LazyGridState,
    totalItemCount: Int,
    labelForIndex: (Int) -> String?,
    modifier: Modifier = Modifier
) {
    if (totalItemCount < 24) return

    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestLabelForIndex by rememberUpdatedState(labelForIndex)

    var trackHeightPx by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var isVisible by remember { mutableStateOf(false) }
    var activeScrollJob by remember { mutableStateOf<Job?>(null) }

    val thumbHeightDp = 48.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }

    val scrollFractionState = remember(totalItemCount) {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val visibleCount = layoutInfo.visibleItemsInfo.size
            val scrollableRange = (totalItemCount - visibleCount).coerceAtLeast(1)
            (gridState.firstVisibleItemIndex.toFloat() / scrollableRange.toFloat()).coerceIn(0f, 1f)
        }
    }

    val currentDateLabel by remember(totalItemCount) {
        derivedStateOf {
            if (!isDragging) return@derivedStateOf null
            val index = (dragFraction * (totalItemCount - 1)).roundToInt().coerceIn(0, totalItemCount - 1)
            latestLabelForIndex(index)
        }
    }

    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }

    LaunchedEffect(isScrolling, isDragging) {
        if (isScrolling || isDragging) {
            isVisible = true
        } else {
            delay(1300L)
            isVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .onSizeChanged { trackHeightPx = it.height }
    ) {
        AnimatedVisibility(
            visible = isVisible && trackHeightPx > 0,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            val usableTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .offset {
                        val frac = if (isDragging) dragFraction else scrollFractionState.value
                        IntOffset(x = 0, y = (frac * usableTrackPx).roundToInt())
                    }
                    .padding(end = 4.dp)
            ) {
                // Floating Date Pill while scrubbing
                AnimatedVisibility(
                    visible = isDragging && !currentDateLabel.isNullOrBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    GlassSurface(
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
                        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = currentDateLabel.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Draggable Thumb Handle with edge-back exclusion
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(thumbHeightDp)
                        .systemGestureExclusion()
                        .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 6.dp, bottomEnd = 6.dp))
                        .background(
                            if (isDragging) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f)
                        )
                        .pointerInput(totalItemCount, usableTrackPx) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    dragFraction = scrollFractionState.value
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                onDragEnd = { isDragging = false },
                                onDragCancel = { isDragging = false },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    val newFraction = (dragFraction + dragAmount / usableTrackPx)
                                        .coerceIn(0f, 1f)
                                    dragFraction = newFraction
                                    val targetIndex = (newFraction * (totalItemCount - 1))
                                        .roundToInt()
                                        .coerceIn(0, (totalItemCount - 1).coerceAtLeast(0))
                                    activeScrollJob?.cancel()
                                    activeScrollJob = coroutineScope.launch {
                                        gridState.scrollToItem(targetIndex)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.UnfoldMore,
                        contentDescription = "Fast scroll",
                        tint = if (isDragging) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

package com.luminagallery.drive.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Intercepts two-finger pinch gestures over a photo grid to smoothly switch between
 * 2, 3, 4, and 5 columns without disrupting single-finger scrolling, taps, or long-press selection.
 */
@Composable
fun PinchZoomGridContainer(
    currentColumns: Int,
    onColumnsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minColumns: Int = 2,
    maxColumns: Int = 5,
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val latestColumns by rememberUpdatedState(currentColumns)
    val latestOnChange by rememberUpdatedState(onColumnsChange)

    Box(
        modifier = modifier.pointerInput(minColumns, maxColumns) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var cumulativeZoom = 1f
                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val pressedCount = event.changes.count { it.pressed }
                    if (pressedCount >= 2) {
                        val zoomChange = event.calculateZoom()
                        if (zoomChange != 1f) {
                            cumulativeZoom *= zoomChange
                            event.changes.forEach { it.consume() }

                            when {
                                cumulativeZoom > 1.24f -> {
                                    val next = (latestColumns - 1).coerceAtLeast(minColumns)
                                    if (next != latestColumns) {
                                        latestOnChange(next)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    cumulativeZoom = 1f
                                }
                                cumulativeZoom < 0.80f -> {
                                    val next = (latestColumns + 1).coerceAtMost(maxColumns)
                                    if (next != latestColumns) {
                                        latestOnChange(next)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    cumulativeZoom = 1f
                                }
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        },
        content = content
    )
}

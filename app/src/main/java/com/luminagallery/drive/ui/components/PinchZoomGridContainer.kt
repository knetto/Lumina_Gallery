package com.luminagallery.drive.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Samsung One UI Gallery–style two-finger pinch-to-zoom grid container for BOTH photo grids
 * (1..6 columns) and album grids (2..5 columns):
 *  - Spreading fingers (zoom in) reduces column count for larger thumbnails/albums.
 *  - Pinching together (zoom out) increases column count for denser overview.
 *  - Consumes multi-touch frames cleanly so single-finger scrolling, taps, long-press drag,
 *    and horizontal sidebar swipes are never disrupted.
 *  - Displays subtle live rubber-band scale feedback and a brief column indicator pill.
 */
@Composable
fun PinchZoomGridContainer(
    currentColumns: Int,
    onColumnsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minColumns: Int = 1,
    maxColumns: Int = 6,
    columnLabel: (Int) -> String = { cols -> if (cols == 1) "1 column" else "$cols columns" },
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val latestColumns by rememberUpdatedState(currentColumns)
    val latestOnChange by rememberUpdatedState(onColumnsChange)

    var previewScaleRaw by remember { mutableFloatStateOf(1f) }
    var badgeVisible by remember { mutableStateOf(false) }
    var badgeTick by remember { mutableIntStateOf(0) }
    var displayedColumns by remember { mutableIntStateOf(currentColumns) }
    val scope = rememberCoroutineScope()
    // Apple Photos style zoom hand-off: when the column count steps, the new layout starts partly
    // at the old tile scale (anchored under your fingers) and springs into place while fading in,
    // so the change reads as one continuous zoom instead of a hard re-layout.
    val stepSettle = remember { Animatable(1f) }
    val stepAlpha = remember { Animatable(1f) }
    val pinchPivot = remember { mutableStateOf(TransformOrigin.Center) }

    LaunchedEffect(currentColumns) {
        displayedColumns = currentColumns
    }

    LaunchedEffect(badgeTick) {
        if (badgeTick > 0) {
            badgeVisible = true
            delay(750L)
            badgeVisible = false
        }
    }

    val animatedScale by animateFloatAsState(
        targetValue = previewScaleRaw,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 460f
        ),
        label = "pinchGridPreviewScale"
    )

    Box(
        modifier = modifier.pointerInput(minColumns, maxColumns) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var cumulativeZoom = 1f
                var wasPinching = false
                try {
                    do {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            wasPinching = true
                            val zoomChange = event.calculateZoom()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (centroid.isSpecified && size.width > 0 && size.height > 0) {
                                pinchPivot.value = TransformOrigin(
                                    (centroid.x / size.width).coerceIn(0f, 1f),
                                    (centroid.y / size.height).coerceIn(0f, 1f)
                                )
                            }
                            event.changes.forEach { it.consume() }
                            if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
                                cumulativeZoom = (cumulativeZoom * zoomChange).coerceIn(0.60f, 1.65f)
                                // Subtle live rubber-band feedback before stepping to next column count
                                val rubberBand = (1f + (cumulativeZoom - 1f) * 0.22f).coerceIn(0.94f, 1.06f)
                                previewScaleRaw = rubberBand

                                val stepped = computeSteppedPinchColumns(
                                    currentColumns = latestColumns,
                                    cumulativeZoom = cumulativeZoom,
                                    minColumns = minColumns,
                                    maxColumns = maxColumns
                                )
                                if (stepped != null) {
                                    if (stepped != latestColumns) {
                                        val ratio = stepped.toFloat() / latestColumns.coerceAtLeast(1)
                                        val startScale = (1f + (ratio - 1f) * 0.5f).coerceIn(0.8f, 1.25f)
                                        scope.launch {
                                            stepSettle.snapTo(startScale)
                                            stepSettle.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 360f))
                                        }
                                        scope.launch {
                                            stepAlpha.snapTo(0.55f)
                                            stepAlpha.animateTo(1f, tween(durationMillis = 220))
                                        }
                                        displayedColumns = stepped
                                        latestOnChange(stepped)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    badgeTick++
                                    cumulativeZoom = 1f
                                    previewScaleRaw = 1f
                                }
                            }
                        } else if (wasPinching) {
                            // Swallow trailing lift frame after multi-touch so releasing one finger
                            // slightly before the other never triggers an accidental tap or scroll jump!
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                } finally {
                    previewScaleRaw = 1f
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val s = animatedScale * stepSettle.value
                    transformOrigin = pinchPivot.value
                    scaleX = s
                    scaleY = s
                    alpha = stepAlpha.value
                },
            content = content
        )

        AnimatedVisibility(
            visible = badgeVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.88f),
            exit = fadeOut() + scaleOut(targetScale = 0.90f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                shadowElevation = 6.dp,
                modifier = Modifier.border(
                    width = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(50)
                )
            ) {
                Text(
                    text = columnLabel(displayedColumns),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

internal fun computeSteppedPinchColumns(
    currentColumns: Int,
    cumulativeZoom: Float,
    minColumns: Int,
    maxColumns: Int
): Int? = when {
    cumulativeZoom >= PINCH_ZOOM_IN_THRESHOLD -> (currentColumns - 1).coerceAtLeast(minColumns)
    cumulativeZoom <= PINCH_ZOOM_OUT_THRESHOLD -> (currentColumns + 1).coerceAtMost(maxColumns)
    else -> null
}

private const val PINCH_ZOOM_IN_THRESHOLD = 1.18f
private const val PINCH_ZOOM_OUT_THRESHOLD = 0.84f

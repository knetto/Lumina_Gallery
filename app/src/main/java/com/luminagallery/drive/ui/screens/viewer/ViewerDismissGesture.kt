package com.luminagallery.drive.ui.screens.viewer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@Stable
internal class ViewerDismissDrag {
    var offsetPx by mutableFloatStateOf(0f)
        private set

    private var returnAnimation: Job? = null

    private fun progress(viewportHeightPx: Float): Float =
        (offsetPx / (viewportHeightPx * 0.36f).coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun scale(viewportHeightPx: Float): Float = 1f - 0.24f * progress(viewportHeightPx)

    fun backdropAlpha(viewportHeightPx: Float): Float = 1f - 0.8f * progress(viewportHeightPx)

    fun transform(rect: Rect, viewportCenter: Offset): Rect {
        val scale = scale(viewportCenter.y * 2f)
        return Rect(
            topLeft = viewportCenter + (rect.topLeft - viewportCenter) * scale + Offset(0f, offsetPx),
            bottomRight = viewportCenter + (rect.bottomRight - viewportCenter) * scale + Offset(0f, offsetPx)
        )
    }

    fun start() {
        returnAnimation?.cancel()
    }

    fun dragBy(deltaPx: Float) {
        offsetPx = (offsetPx + deltaPx).coerceAtLeast(0f)
    }

    fun settle(scope: CoroutineScope, velocity: Float = 0f) {
        if (offsetPx == 0f) return
        returnAnimation = scope.launch {
            animate(
                initialValue = offsetPx,
                targetValue = 0f,
                initialVelocity = velocity.coerceIn(-2_000f, 0f),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { value, _ ->
                offsetPx = value.coerceAtLeast(0f)
            }
        }
    }
}

/**
 * The pager and media receive pointer events first, so horizontal paging and pinch zoom
 * can take ownership before this parent handles a single-finger vertical gesture.
 */
internal fun Modifier.viewerVerticalGestures(
    drag: ViewerDismissDrag,
    scope: CoroutineScope,
    canStart: () -> Boolean,
    canDismiss: () -> Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit
): Modifier = pointerInput(drag) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!canStart()) return@awaitEachGesture

        val velocity = VelocityTracker().apply { addPointerInputChange(down) }
        var direction = 0
        var detailsDistance = 0f
        var released = false
        var releaseVelocity = 0f
        var lastMoveTime = down.uptimeMillis

        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.any { it.id != down.id && it.pressed }) break
                if (!change.pressed) {
                    released = !change.isConsumed
                    if (released && direction > 0 && change.uptimeMillis - lastMoveTime <= 64L) {
                        releaseVelocity = velocity.calculateVelocity().y
                    }
                    break
                }
                if (change.isConsumed) break
                velocity.addPointerInputChange(change)
                val delta = change.position - change.previousPosition
                if (delta != Offset.Zero) lastMoveTime = change.uptimeMillis

                if (direction == 0) {
                    val travel = change.position - down.position
                    if (abs(travel.y) <= viewConfiguration.touchSlop) continue
                    if (abs(travel.y) < abs(travel.x) * 1.25f) break
                    if (travel.y > 0f && !canDismiss()) break
                    direction = if (travel.y > 0f) 1 else -1
                    if (direction > 0) drag.start()
                }

                change.consume()
                if (direction > 0) {
                    drag.dragBy(delta.y)
                } else {
                    detailsDistance -= delta.y
                    if (detailsDistance >= 64.dp.toPx()) {
                        onDetails()
                        break
                    }
                }
            }
        } finally {
            if (direction > 0) {
                if (released && canDismiss() &&
                    shouldDismissViewer(drag.offsetPx / density, releaseVelocity / density, size.height / density)
                ) {
                    onDismiss()
                } else {
                    drag.settle(scope, releaseVelocity)
                }
            } else if (released && detailsDistance >= 36.dp.toPx()) {
                onDetails()
            }
        }
    }
}

internal fun shouldDismissViewer(
    distanceDp: Float,
    velocityDpPerSecond: Float,
    viewportHeightDp: Float
): Boolean {
    val threshold = (viewportHeightDp * 0.16f).coerceAtLeast(84f)
    if (velocityDpPerSecond < -850f) return false
    return distanceDp >= threshold ||
        (distanceDp >= 24f && velocityDpPerSecond >= 850f && distanceDp + velocityDpPerSecond * 0.12f >= threshold)
}

package com.luminagallery.drive.ui.components

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Samsung One UI 7 / 8 Motion & Tactile Interaction System:
 *  - Signature fast-out, extra-slow-in decelerate curve (`OneUiDecelerateEasing`).
 *  - Physical spring press compression (`0.94f`) & subtle S-Pen / cursor hover lift (`1.025f`)
 *    without boxy Material ripple wash leaking behind album labels.
 *  - Critically damped 120Hz shared-element photo morphing bounds transform.
 */
object OneUiMotion {
    /** Samsung One UI signature smooth decelerate curve (SineInOut / Quintic-out feel). */
    val DecelerateEasing = CubicBezierEasing(0.16f, 1f, 0.30f, 1f)

    /** Snappy yet silky spring used for press bounce, filmstrip expansion & micro-interactions. */
    fun <T> snappySpring() = spring<T>(
        dampingRatio = 0.78f,
        stiffness = 480f
    )

    /** Smooth no-bounce spring for grid reflow and layout transitions. */
    fun <T> smoothPlacementSpring() = spring<T>(
        dampingRatio = 0.86f,
        stiffness = 380f
    )

    /** Silky 120Hz spring for zooming a photo straight out of its thumbnail into the Viewer. */
    @OptIn(ExperimentalSharedTransitionApi::class)
    val SharedPhotoBoundsTransform = BoundsTransform { _, _ ->
        spring(
            dampingRatio = 0.85f,
            stiffness = 370f
        )
    }
}

/**
 * Samsung One UI tactile press + hover modifier:
 * Replaces boxy Material ripples with smooth physical spring compression on touch
 * and a subtle lift on hover (S-Pen / DeX / Phone Link mouse).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.oneUiClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    hoveredScale: Float = 1.022f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            isPressed -> pressedScale
            isHovered -> hoveredScale
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 560f
        ),
        label = "oneUiScale"
    )

    val clickMod = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
    }

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .hoverable(interactionSource = interactionSource, enabled = enabled)
        .then(clickMod)
}

/**
 * Subtle Samsung One UI press dimming & hover rim sheen layered strictly inside a rounded cover.
 */
@Composable
fun BoxScope.OneUiCoverHighlightOverlay(
    isPressed: Boolean,
    isHovered: Boolean,
    shape: Shape = RoundedCornerShape(18.dp),
    hoverBorderWidth: Dp = 1.2.dp
) {
    val pressAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.16f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 90 else 180),
        label = "coverPressScrim"
    )
    val hoverAlpha by animateFloatAsState(
        targetValue = if (isHovered && !isPressed) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "coverHoverGlow"
    )

    if (pressAlpha > 0.005f) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = pressAlpha), shape)
        )
    }
    if (hoverAlpha > 0.005f) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.White.copy(alpha = 0.06f * hoverAlpha), shape)
                .border(
                    width = hoverBorderWidth,
                    color = Color.White.copy(alpha = 0.24f * hoverAlpha),
                    shape = shape
                )
        )
    }
}

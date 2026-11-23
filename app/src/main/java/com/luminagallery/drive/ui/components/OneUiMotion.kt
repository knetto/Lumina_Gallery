package com.luminagallery.drive.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.MediaItem

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

    /** Samsung One UI 7 / 8 album depth-zoom easing: instantaneous launch with long velvety settle. */
    val AlbumExpandEasing = CubicBezierEasing(0.20f, 0.96f, 0.28f, 1.0f)

    /** Apple iOS / Xiaomi HyperOS signature quintic-out fluid curve: instant touch response, silky settle. */
    val FluidEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /**
     * Gentle Xiaomi HyperOS / Apple iOS Predictive-Back curve:
     * Tracks finger distance smoothly without violently jumping at 10% edge drag.
     */
    val HyperOsBackEasing = CubicBezierEasing(0.32f, 0.08f, 0.24f, 1.0f)

    /**
     * Ease-in commit dissolve for returning cards:
     * Keeps the shrinking rounded card crisp & solid during back-swipe drag and only melts
     * seamlessly in the final commit phase (eliminates mid-swipe ghosting & black voids!).
     */
    val BackFadeCommitEasing = CubicBezierEasing(0.58f, 0f, 1f, 0.42f)

    /** Critically damped Apple/HyperOS fluid spring (zero jarring bounce, silky settle). */
    fun <T> fluidSpring() = spring<T>(
        dampingRatio = 0.90f,
        stiffness = 380f
    )

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

/**
 * Tracks the exact normalized screen center (`TransformOrigin`) of the album or collection card
 * the user tapped so the open/close depth-zoom animation expands directly OUT of the tapped card
 * and collapses right BACK into it — matching Samsung One UI 7/8 & Xiaomi HyperOS Gallery physics.
 */
@Stable
class AlbumTransitionOriginTracker {
    var lastOrigin: TransformOrigin = TransformOrigin.Center
        private set

    private val originByKey = HashMap<String, TransformOrigin>(16)

    fun record(key: String, normalizedX: Float, normalizedY: Float) {
        val origin = TransformOrigin(
            pivotFractionX = normalizedX.coerceIn(0.12f, 0.88f),
            pivotFractionY = normalizedY.coerceIn(0.12f, 0.88f)
        )
        lastOrigin = origin
        originByKey[key] = origin
    }

    fun originFor(key: String?): TransformOrigin =
        if (key != null) originByKey[key] ?: lastOrigin else lastOrigin

    fun originForOrCenter(key: String?): TransformOrigin =
        if (key != null) originByKey[key] ?: TransformOrigin.Center else TransformOrigin.Center
}

val LocalAlbumTransitionOrigin = staticCompositionLocalOf { AlbumTransitionOriginTracker() }

/**
 * Xiaomi HyperOS & Apple iOS transition frame synchronized 1:1 with Navigation Compose's
 * live Predictive Back `AnimatedVisibilityScope.transition`:
 *  - While swiping back (`Visible -> PostExit`): smoothly rounds all 4 screen corners
 *    (`0.dp -> 38.dp` matching physical OLED display curves), lifts a subtle drop shadow, and
 *    adds a razor-subtle glass rim highlight so the floating card separates cleanly in dark mode.
 *  - On the underlying screen (`PreEnter -> Visible`): smoothly clears a soft dark depth scrim
 *    (`0.34f -> 0f`) as the foreground screen glides away.
 *  - At rest (`Visible`): applies zero clip, zero shadow, zero border, zero overlay (0 overhead!).
 */
@Composable
fun AnimatedVisibilityScope.HyperOsTransitionFrame(
    enableExitRounding: Boolean = true,
    content: @Composable () -> Unit
) {
    val cornerRadius by transition.animateDp(
        transitionSpec = { tween(durationMillis = 260, easing = LinearEasing) },
        label = "hyperOsCorner"
    ) { state ->
        if (enableExitRounding && state == EnterExitState.PostExit) 38.dp else 0.dp
    }

    val rimAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 240, easing = LinearEasing) },
        label = "hyperOsRim"
    ) { state ->
        if (enableExitRounding && state == EnterExitState.PostExit) 0.16f else 0f
    }

    val shadowElevation by transition.animateDp(
        transitionSpec = { tween(durationMillis = 240, easing = LinearEasing) },
        label = "hyperOsShadow"
    ) { state ->
        if (enableExitRounding && state == EnterExitState.PostExit) 22.dp else 0.dp
    }

    val underlyingDimAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 260, easing = LinearEasing) },
        label = "hyperOsUnderDim"
    ) { state ->
        if (state == EnterExitState.PreEnter) 0.34f else 0f
    }

    val safeCorner = cornerRadius.coerceAtLeast(0.dp)
    val safeShadow = shadowElevation.coerceAtLeast(0.dp)

    val frameModifier = if (safeCorner > 0.5.dp) {
        val shape = RoundedCornerShape(safeCorner)
        Modifier
            .fillMaxSize()
            .shadow(elevation = safeShadow, shape = shape, clip = false)
            .clip(shape)
            .border(
                width = 0.8.dp,
                color = Color.White.copy(alpha = rimAlpha.coerceIn(0f, 1f)),
                shape = shape
            )
    } else {
        Modifier.fillMaxSize()
    }

    Box(modifier = frameModifier) {
        content()

        val safeDim = underlyingDimAlpha.coerceIn(0f, 0.45f)
        if (safeDim > 0.005f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = safeDim))
            )
        }
    }
}

/**
 * Fires immediate background Coil pre-decoding for the top visible tiles of an album right on
 * tap — before the navigation transition even reaches frame 2!
 */
fun prefetchAlbumThumbnails(
    context: Context,
    imageLoader: ImageLoader,
    items: List<MediaItem>,
    isUnmeteredWifi: Boolean,
    maxItems: Int = 16
) {
    if (items.isEmpty()) return
    val count = items.size.coerceAtMost(maxItems)
    for (i in 0 until count) {
        val item = items[i]
        val request = if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(LocalThumbnailRequest(item.localContentUri, item.isVideo))
                .size(360)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .crossfade(false)
                .build()
        } else {
            val url = DriveConstants.buildThumbnailUrl(
                thumbnailLink = item.thumbnailLink,
                fileId = item.id,
                isUnmeteredWifi = isUnmeteredWifi,
                isVideo = item.isVideo,
                sizeBytes = item.sizeBytes
            ) ?: continue
            ImageRequest.Builder(context)
                .data(url)
                .size(360)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .diskCacheKey(DriveConstants.thumbnailCacheKey(item.id, isUnmeteredWifi))
                .crossfade(false)
                .build()
        }
        imageLoader.enqueue(request)
    }
}

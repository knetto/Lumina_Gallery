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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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

    var lastBounds: Rect? = null
        private set

    private val originByKey = HashMap<String, TransformOrigin>(32)
    private val boundsByKey = HashMap<String, Rect>(128)
    private val morphEnabledByEntryId = HashMap<String, Boolean>(16)

    fun setMorphEnabled(entryId: String, enabled: Boolean) {
        if (morphEnabledByEntryId.size > 64) {
            morphEnabledByEntryId.clear()
        }
        morphEnabledByEntryId[entryId] = enabled
    }

    fun isMorphEnabled(entryId: String): Boolean = morphEnabledByEntryId[entryId] ?: false

    fun record(key: String, normalizedX: Float, normalizedY: Float) {
        val origin = TransformOrigin(
            pivotFractionX = normalizedX.coerceIn(0.12f, 0.88f),
            pivotFractionY = normalizedY.coerceIn(0.12f, 0.88f)
        )
        lastOrigin = origin
        originByKey[key] = origin
    }

    fun recordBounds(key: String, boundsInRoot: Rect, rootWidth: Float, rootHeight: Float, markAsLast: Boolean = false) {
        if (boundsInRoot.width < 4f || boundsInRoot.height < 4f) return
        boundsByKey[key] = boundsInRoot
        if (rootWidth > 0f && rootHeight > 0f) {
            val origin = TransformOrigin(
                pivotFractionX = (boundsInRoot.center.x / rootWidth).coerceIn(0.08f, 0.92f),
                pivotFractionY = (boundsInRoot.center.y / rootHeight).coerceIn(0.08f, 0.92f)
            )
            originByKey[key] = origin
            if (markAsLast) {
                lastOrigin = origin
                lastBounds = boundsInRoot
            }
        } else if (markAsLast) {
            lastBounds = boundsInRoot
        }
    }

    fun originFor(key: String?): TransformOrigin =
        if (key != null) originByKey[key] ?: lastOrigin else lastOrigin

    fun originForOrCenter(key: String?): TransformOrigin =
        if (key != null) originByKey[key] ?: TransformOrigin.Center else TransformOrigin.Center

    fun boundsFor(key: String?, fallbackToLast: Boolean = false): Rect? {
        if (key != null) {
            boundsByKey[key]?.let { return it }
        }
        return if (fallbackToLast) lastBounds else null
    }
}

val LocalAlbumTransitionOrigin = staticCompositionLocalOf { AlbumTransitionOriginTracker() }

/**
 * True Xiaomi HyperOS / Apple iOS Photos geometric Rect-to-Rect Morph Frame:
 *  - On Open (`PreEnter -> Visible`): physically expands directly OUT of the exact pixel
 *    bounding rectangle (`targetBounds`) of the tapped album card or photo tile up to full screen.
 *  - On Back Swipe / Close (`Visible -> PostExit`): tracks Android Predictive Back 1:1 and
 *    physically shrinks, crops, rounds, and flies ALL THE WAY DOWN into the exact pixel
 *    bounding rectangle of the tapped (or currently active) album card or photo tile!
 *  - Runs 100% inside the GPU `graphicsLayer` draw phase at 120Hz with zero tile-hiding
 *    black holes and zero runtime overhead when settled (`Visible`).
 */
@Composable
fun AnimatedVisibilityScope.HyperOsRectMorphFrame(
    entryId: String,
    targetKey: String?,
    targetCornerRadius: Dp = 18.dp,
    verticalCropBias: Float = 0.5f,
    backdropMaxAlpha: Float = 0.28f,
    fallbackToLastBounds: Boolean = true,
    content: @Composable (morphProgress: Float) -> Unit
) {
    val originTracker = LocalAlbumTransitionOrigin.current
    val density = LocalDensity.current
    val targetCornerPx = with(density) { targetCornerRadius.toPx() }
    val midFlightCornerPx = with(density) { 36.dp.toPx() }
    val maxShadowPx = with(density) { 20.dp.toPx() }

    // 0f = full screen (Visible), 1f = completely shrunk inside targetRect (PreEnter or PostExit)
    val rawMorphProgress by transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                // Opening: snappy Xiaomi HyperOS / Apple exponential bloom out of the tapped card
                tween(durationMillis = 340, easing = OneUiMotion.FluidEasing)
            } else {
                // Closing / Predictive Back: smooth finger-tracked shrink right back into the card!
                tween(durationMillis = 330, easing = OneUiMotion.HyperOsBackEasing)
            }
        },
        label = "hyperOsRectMorphProgress"
    ) { state ->
        when (state) {
            EnterExitState.Visible -> 0f
            EnterExitState.PreEnter, EnterExitState.PostExit -> 1f
        }
    }

    val isExiting = transition.targetState == EnterExitState.PostExit ||
        transition.currentState == EnterExitState.PostExit

    // Gate morph strictly to fresh-open & pop-close transitions so pushing a child screen
    // (e.g. Folder -> Viewer) NEVER shrinks the parent album into its card in the background!
    val morphEnabled = originTracker.isMorphEnabled(entryId)
    val effectiveMorphProgress = if (morphEnabled) rawMorphProgress.coerceIn(0f, 1f) else 0f
    val isMorphing = effectiveMorphProgress > 0.002f

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen depth / theater backdrop BEHIND the morphing window:
        // Clears smoothly across the entire screen while the crisp photo or album card flies home.
        if (isMorphing && backdropMaxAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = ((1f - effectiveMorphProgress) * backdropMaxAlpha).coerceIn(0f, 1f)
                    }
                    .background(Color.Black)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!originTracker.isMorphEnabled(entryId)) return@graphicsLayer
                    val t = rawMorphProgress.coerceIn(0f, 1f)
                    if (t <= 0.002f) return@graphicsLayer

                    val w = size.width
                    val h = size.height
                    if (w <= 1f || h <= 1f) return@graphicsLayer

                    // Live lookup so even if the underlying grid just scrolled to a newly swiped photo
                    // on frame 1 of Predictive Back, we immediately lock onto its exact pixel Rect!
                    val targetRect = originTracker.boundsFor(
                        key = targetKey,
                        fallbackToLast = fallbackToLastBounds
                    )

                    val dst = targetRect?.takeIf {
                        it.width >= 16f && it.height >= 16f &&
                            it.bottom > -h * 0.25f && it.top < h * 1.25f &&
                            it.right > -w * 0.25f && it.left < w * 1.25f
                    } ?: Rect(
                        left = w * 0.34f,
                        top = h * 0.36f,
                        right = w * 0.66f,
                        bottom = h * 0.36f + w * 0.32f
                    )

                    val curL = dst.left * t
                    val curT = dst.top * t
                    val curR = w + (dst.right - w) * t
                    val curB = h + (dst.bottom - h) * t

                    val curW = (curR - curL).coerceAtLeast(1f)
                    val curH = (curB - curT).coerceAtLeast(1f)
                    val curCx = (curL + curR) * 0.5f
                    val curCy = (curT + curB) * 0.5f

                    // Uniform aspect-preserving scale covering the morphing window (zero stretching!)
                    val uniformScale = maxOf(curW / w, curH / h).coerceIn(0.08f, 1f)

                    // Exact local clip window that projects pixel-for-pixel onto Rect(curL, curT, curR, curB)
                    val localClipW = (curW / uniformScale).coerceIn(1f, w)
                    val localClipH = (curH / uniformScale).coerceIn(1f, h)
                    val localLeft = (w - localClipW) * 0.5f
                    val localTop = (h - localClipH) * verticalCropBias.coerceIn(0f, 1f)
                    val localCy = localTop + localClipH * 0.5f

                    scaleX = uniformScale
                    scaleY = uniformScale
                    translationX = curCx - (w * 0.5f)
                    translationY = curCy - (h * 0.5f) - (localCy - (h * 0.5f)) * uniformScale

                    // Smoothly round screen corners mid-flight (OLED bezel 36.dp -> exact card corner radius)
                    val visualCornerPx = if (t < 0.45f) {
                        val f = (t / 0.45f).coerceIn(0f, 1f)
                        midFlightCornerPx * f
                    } else {
                        val f = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
                        midFlightCornerPx + (targetCornerPx - midFlightCornerPx) * f
                    }
                    val localRadius = (visualCornerPx / uniformScale).coerceAtLeast(0f)

                    // Soft 3D HyperOS elevation shadow peaking mid-flight & landing flush at 0.dp
                    val shadowBell = kotlin.math.sin(t * kotlin.math.PI.toFloat()).coerceIn(0f, 1f)
                    shadowElevation = maxShadowPx * shadowBell
                    ambientShadowColor = Color.Black.copy(alpha = 0.55f)
                    spotShadowColor = Color.Black.copy(alpha = 0.65f)

                    clip = true
                    shape = object : Shape {
                        override fun createOutline(
                            size: Size,
                            layoutDirection: LayoutDirection,
                            density: Density
                        ): Outline = Outline.Rounded(
                            RoundRect(
                                left = localLeft,
                                top = localTop,
                                right = localLeft + localClipW,
                                bottom = localTop + localClipH,
                                cornerRadius = CornerRadius(localRadius, localRadius)
                            )
                        )
                    }

                    // Keep screen 100% solid while shrinking into the target box, melting seamlessly
                    // into the real card/tile underneath only during the final 22% of the travel!
                    alpha = if (isExiting) {
                        ((1f - t) / 0.22f).coerceIn(0f, 1f)
                    } else {
                        ((1f - t) / 0.18f).coerceIn(0f, 1f)
                    }
                }
        ) {
            content(effectiveMorphProgress)
        }
    }
}

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

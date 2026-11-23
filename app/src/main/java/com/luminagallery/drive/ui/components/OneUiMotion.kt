package com.luminagallery.drive.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Transition
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
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
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

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

    /**
     * Xiaomi HyperOS page push / pop curve: confident launch with a long, soft settle.
     * Used for full-width horizontal page slides (Settings, Search, Editor...).
     */
    val PageSlideEasing = CubicBezierEasing(0.24f, 0.86f, 0.22f, 1f)
    const val PAGE_SLIDE_MS = 400

    /** Photo / album card bloom-out (thumbnail -> full screen). */
    val MorphOpenEasing = CubicBezierEasing(0.20f, 0.92f, 0.24f, 1f)
    const val MORPH_OPEN_MS = 380

    /**
     * Photo / album card fly-home (full screen -> thumbnail). Starts with real velocity right
     * on release (no sluggish ease-in) and decelerates gently into the exact grid tile.
     */
    val MorphCloseEasing = CubicBezierEasing(0.26f, 0.80f, 0.20f, 1f)
    const val MORPH_CLOSE_MS = 380

    /** Depth scrim drawn over the page sitting underneath a sliding page. */
    const val PAGE_DIM_ALPHA = 0.30f

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

    /** Direction of the navigation transition currently running (true = back / pop). */
    var lastNavWasPop: Boolean = false
        private set

    /** Whether the page underneath a sliding page should receive the depth scrim. */
    var dimBackgroundPage: Boolean = true
        private set

    /**
     * Exact on-screen rect (root coordinates, un-transformed) of the photo/video currently shown
     * in the Viewer. Lets the close morph start from the real picture instead of the full screen,
     * so landscape / panorama shots fly home without black bars or a size jump.
     */
    var viewerContentRect: Rect? = null

    /** Current Viewer background opacity (drops while dragging the photo down to dismiss). */
    var viewerBackdropAlpha: Float = 1f

    fun notePush(dimBackground: Boolean) {
        lastNavWasPop = false
        dimBackgroundPage = dimBackground
    }

    fun notePop(dimBackground: Boolean) {
        lastNavWasPop = true
        dimBackgroundPage = dimBackground
    }

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
 * Animates 0f (settled / Visible) -> 1f (off-stage) in lock-step with the page slide.
 */
@Composable
private fun AnimatedVisibilityScope.rememberPageDimFraction(): State<Float> =
    transition.animateFloat(
        transitionSpec = {
            tween(durationMillis = OneUiMotion.PAGE_SLIDE_MS, easing = OneUiMotion.PageSlideEasing)
        },
        label = "hyperOsPageDim"
    ) { state -> if (state == EnterExitState.Visible) 0f else 1f }

/**
 * Xiaomi HyperOS depth scrim: only the page sitting *underneath* a sliding page is dimmed
 * (outgoing page on push, returning page on pop). The page on top always stays crisp.
 */
private fun Modifier.hyperOsPageDim(
    transition: Transition<EnterExitState>,
    tracker: AlbumTransitionOriginTracker,
    fraction: State<Float>
): Modifier = drawWithContent {
    drawContent()
    val f = fraction.value
    if (f <= 0.003f || !tracker.dimBackgroundPage) return@drawWithContent
    val entering = transition.targetState == EnterExitState.Visible
    val isUnderneath = if (tracker.lastNavWasPop) entering else !entering
    if (isUnderneath) {
        drawRect(Color.Black.copy(alpha = (OneUiMotion.PAGE_DIM_ALPHA * f).coerceIn(0f, 1f)))
    }
}

/**
 * Xiaomi HyperOS Gallery style Rect-to-Rect morph frame.
 *  - Open (`PreEnter -> Visible`): blooms straight out of the tapped tile / album card.
 *  - Close (`Visible -> PostExit`): starts only once back is *released* (see LuminaNavGraph) and
 *    flies the real picture home into the exact tile it belongs to, landing on a center-crop that
 *    matches the grid thumbnail pixel-for-pixel before melting into it.
 *  - Everything is computed in the draw phase (`graphicsLayer`), so the screen content is never
 *    recomposed per frame; content only receives a boolean "is morphing" flag.
 */
@Composable
fun AnimatedVisibilityScope.HyperOsRectMorphFrame(
    entryId: String,
    targetKey: String?,
    targetCornerRadius: Dp = 18.dp,
    verticalCropBias: Float = 0.5f,
    backdropMaxAlpha: Float = 0.28f,
    fallbackToLastBounds: Boolean = true,
    maxShadowElevation: Dp = 12.dp,
    midFlightCornerBump: Dp = 14.dp,
    sourceRectProvider: (() -> Rect?)? = null,
    backdropAlphaProvider: () -> Float = { 1f },
    content: @Composable (isMorphing: Boolean) -> Unit
) {
    val originTracker = LocalAlbumTransitionOrigin.current
    val density = LocalDensity.current
    val targetCornerPx = with(density) { targetCornerRadius.toPx() }
    val bumpCornerPx = with(density) { midFlightCornerBump.toPx() }
    val maxShadowPx = with(density) { maxShadowElevation.toPx() }

    // 0f = full screen (Visible), 1f = fully collapsed into the target tile (PreEnter / PostExit)
    val morphProgressState = transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(durationMillis = OneUiMotion.MORPH_OPEN_MS, easing = OneUiMotion.MorphOpenEasing)
            } else {
                tween(durationMillis = OneUiMotion.MORPH_CLOSE_MS, easing = OneUiMotion.MorphCloseEasing)
            }
        },
        label = "hyperOsRectMorphProgress"
    ) { state ->
        when (state) {
            EnterExitState.Visible -> 0f
            EnterExitState.PreEnter, EnterExitState.PostExit -> 1f
        }
    }

    val isMorphing by remember(entryId) {
        derivedStateOf {
            morphProgressState.value > 0.002f && originTracker.isMorphEnabled(entryId)
        }
    }

    val pageDim = rememberPageDimFraction()
    val animTransition = transition

    Box(
        modifier = Modifier
            .fillMaxSize()
            .hyperOsPageDim(animTransition, originTracker, pageDim)
    ) {
        // Full-screen backdrop behind the flying picture: clears smoothly as it travels home.
        if (backdropMaxAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        if (!originTracker.isMorphEnabled(entryId)) return@drawBehind
                        val t = morphProgressState.value.coerceIn(0f, 1f)
                        if (t <= 0.002f) return@drawBehind
                        val a = ((1f - t) * backdropMaxAlpha * backdropAlphaProvider().coerceIn(0f, 1f))
                            .coerceIn(0f, 1f)
                        if (a > 0.003f) drawRect(Color.Black.copy(alpha = a))
                    }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!originTracker.isMorphEnabled(entryId)) return@graphicsLayer
                    val t = morphProgressState.value.coerceIn(0f, 1f)
                    if (t <= 0.002f) return@graphicsLayer

                    val w = size.width
                    val h = size.height
                    if (w <= 1f || h <= 1f) return@graphicsLayer

                    val providedSource = sourceRectProvider?.invoke()?.takeIf {
                        it.width > 8f && it.height > 8f &&
                            it.left >= -1f && it.top >= -1f && it.right <= w + 1f && it.bottom <= h + 1f
                    }
                    val src = providedSource ?: Rect(0f, 0f, w, h)
                    val cropBias = if (providedSource != null) 0.5f else verticalCropBias.coerceIn(0f, 1f)

                    // Live lookup: if the grid underneath just scrolled to the photo the user swiped to,
                    // we immediately lock onto its fresh on-screen rect.
                    val target = originTracker.boundsFor(
                        key = targetKey,
                        fallbackToLast = fallbackToLastBounds
                    )?.takeIf {
                        it.width >= 16f && it.height >= 16f &&
                            it.bottom > 0f && it.top < h && it.right > 0f && it.left < w
                    }
                    val hasTarget = target != null

                    // Tile scrolled off-screen: gently shrink in place & fade (HyperOS fallback).
                    val dst = target ?: Rect(
                        left = src.center.x - src.width * 0.30f,
                        top = src.center.y - src.height * 0.30f,
                        right = src.center.x + src.width * 0.30f,
                        bottom = src.center.y + src.height * 0.30f
                    )

                    val cur = lerp(src, dst, t)
                    val curW = cur.width.coerceAtLeast(1f)
                    val curH = cur.height.coerceAtLeast(1f)

                    // Uniform (aspect-preserving) scale that makes the picture COVER the current window,
                    // so it lands as the same center-crop the grid thumbnail shows.
                    val uniformScale = max(curW / src.width, curH / src.height).coerceIn(0.02f, 6f)

                    // Local (un-scaled) crop window inside the source picture
                    val localW = (curW / uniformScale).coerceIn(1f, src.width)
                    val localH = (curH / uniformScale).coerceIn(1f, src.height)
                    val localLeft = src.left + (src.width - localW) * 0.5f
                    val localTop = src.top + (src.height - localH) * cropBias
                    val localCx = localLeft + localW * 0.5f
                    val localCy = localTop + localH * 0.5f

                    scaleX = uniformScale
                    scaleY = uniformScale
                    translationX = cur.center.x - w * 0.5f - (localCx - w * 0.5f) * uniformScale
                    translationY = cur.center.y - h * 0.5f - (localCy - h * 0.5f) * uniformScale

                    val bell = sin(t * PI.toFloat()).coerceIn(0f, 1f)
                    val visualCornerPx = targetCornerPx * t + bumpCornerPx * bell
                    val localRadius = (visualCornerPx / uniformScale).coerceAtLeast(0f)

                    shadowElevation = if (hasTarget) maxShadowPx * bell else 0f
                    ambientShadowColor = Color.Black.copy(alpha = 0.40f)
                    spotShadowColor = Color.Black.copy(alpha = 0.50f)

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
                                right = localLeft + localW,
                                bottom = localTop + localH,
                                cornerRadius = CornerRadius(localRadius, localRadius)
                            )
                        )
                    }

                    // Stay 100% solid while travelling; melt into the identical grid tile only in the
                    // last few percent of the flight (no ghosting, no black voids).
                    alpha = if (hasTarget) {
                        ((1f - t) / 0.14f).coerceIn(0f, 1f)
                    } else {
                        (1f - t * 1.15f).coerceIn(0f, 1f)
                    }
                }
        ) {
            content(isMorphing)
        }
    }
}

/**
 * Xiaomi HyperOS page frame for regular (non-morph) screens, synchronised with the
 * Navigation Compose transition:
 *  - The page on top slides cleanly (no rounding, no rim, no ghosting).
 *  - The page underneath gets a soft depth scrim that clears as it comes back into focus.
 *  - At rest it draws nothing extra (zero overhead).
 */
@Composable
fun AnimatedVisibilityScope.HyperOsTransitionFrame(
    content: @Composable () -> Unit
) {
    val tracker = LocalAlbumTransitionOrigin.current
    val pageDim = rememberPageDimFraction()
    val animTransition = transition
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hyperOsPageDim(animTransition, tracker, pageDim)
    ) {
        content()
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

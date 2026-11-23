package com.luminagallery.drive.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.MediaItem
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
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
    val PageSlideEasing = CubicBezierEasing(0.22f, 0.90f, 0.24f, 1f)
    const val PAGE_SLIDE_MS = 340

    /** Photo / album card bloom-out (thumbnail -> full screen): instant tactile lift, silky settle. */
    val MorphOpenEasing = CubicBezierEasing(0.18f, 0.90f, 0.22f, 1f)
    const val MORPH_OPEN_MS = 330

    /**
     * Photo / album card fly-home (full screen -> thumbnail). Starts immediately on release with
     * spring-like velocity and lands cleanly right into the exact grid slot.
     */
    val MorphCloseEasing = CubicBezierEasing(0.22f, 0.88f, 0.24f, 1f)
    const val MORPH_CLOSE_MS = 310

    /** Depth scrim drawn over the page sitting underneath a sliding page. */
    const val PAGE_DIM_ALPHA = 0.28f

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

@Immutable
private data class MorphRoundRectShape(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val radius: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Rounded(
        RoundRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            cornerRadius = CornerRadius(radius, radius)
        )
    )
}

@Immutable
data class AlbumCoverMorphInfo(
    val isLocal: Boolean,
    val coverItemId: String,
    val coverThumbnailLink: String?,
    val coverIsVideo: Boolean,
    val coverSizeBytes: Long,
    val isUnmeteredWifi: Boolean
)

/**
 * Tracks the exact normalized screen center (`TransformOrigin`), pixel bounding rects, and
 * cover/item handoff metadata for every album card and photo tile so open/close animations
 * expand directly OUT of the exact tapped card/tile and collapse seamlessly BACK into it.
 */
@Stable
class AlbumTransitionOriginTracker {
    var lastOrigin: TransformOrigin = TransformOrigin.Center
        private set

    var lastBounds: Rect? = null
        private set

    /** Most recently tapped MediaItem for instant frame-0 Viewer rendering (zero black spinner flash). */
    var lastTappedMediaItem: MediaItem? = null
        private set

    private fun <V> lruMap(maxEntries: Int): LinkedHashMap<String, V> =
        object : LinkedHashMap<String, V>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean =
                size > maxEntries
        }

    private val originByKey = lruMap<TransformOrigin>(MAX_TRACKED_ENTRIES)
    private val boundsByKey = lruMap<Rect>(MAX_TRACKED_ENTRIES)
    private val albumCoverByKey = lruMap<AlbumCoverMorphInfo>(MAX_TRACKED_ENTRIES)
    private val morphEnabledByEntryId = mutableStateMapOf<String, Boolean>()

    private var activeMorphOwnerEntryId: String? = null

    /**
     * Key of the grid tile or album cover currently in-flight (`"media_<id>"` or `<folderId>`).
     * That single source tile vacates its slot (`alpha = 0f` in `graphicsLayer`) while the
     * real flying photo/album lifts out of or lands back into it — eliminating double-image ghosting.
     */
    var activeMorphKey by mutableStateOf<String?>(null)
        private set

    fun setActiveMorph(entryId: String, key: String?) {
        if (key != null) {
            activeMorphOwnerEntryId = entryId
            if (activeMorphKey != key) {
                activeMorphKey = key
            }
        } else if (activeMorphOwnerEntryId == entryId) {
            activeMorphOwnerEntryId = null
            if (activeMorphKey != null) {
                activeMorphKey = null
            }
        }
    }

    fun clearActiveMorph(entryId: String) {
        if (activeMorphOwnerEntryId == entryId) {
            activeMorphOwnerEntryId = null
            if (activeMorphKey != null) {
                activeMorphKey = null
            }
        }
    }

    fun recordTappedMediaItem(item: MediaItem) {
        lastTappedMediaItem = item
    }

    fun recordAlbumCover(key: String, info: AlbumCoverMorphInfo?) {
        if (info == null) {
            albumCoverByKey.remove(key)
        } else {
            albumCoverByKey[key] = info
        }
    }

    fun albumCoverFor(key: String?): AlbumCoverMorphInfo? =
        if (key != null) albumCoverByKey[key] else null

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
     * in the Viewer. Lets the open & close morphs use the real picture bounds instead of the full
     * screen, so landscape / portrait shots expand & fly home with zero black bars or size jumps.
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
        val existing = boundsByKey[key]
        val meaningfullyChanged = existing == null ||
            kotlin.math.abs(existing.left - boundsInRoot.left) >= 0.5f ||
            kotlin.math.abs(existing.top - boundsInRoot.top) >= 0.5f ||
            kotlin.math.abs(existing.right - boundsInRoot.right) >= 0.5f ||
            kotlin.math.abs(existing.bottom - boundsInRoot.bottom) >= 0.5f
        if (!meaningfullyChanged && !markAsLast) return

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

    internal fun trackedBoundsCount(): Int = boundsByKey.size

    companion object {
        internal const val MAX_TRACKED_ENTRIES = 256
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
 * Uses `snap()` when background dimming is off (e.g. morph open/close) so this helper NEVER
 * keeps NavHost's outer Transition alive past the morph animation's exact duration!
 */
@Composable
private fun AnimatedVisibilityScope.rememberPageDimFraction(
    tracker: AlbumTransitionOriginTracker
): State<Float> =
    transition.animateFloat(
        transitionSpec = {
            if (tracker.dimBackgroundPage) {
                tween(durationMillis = OneUiMotion.PAGE_SLIDE_MS, easing = OneUiMotion.PageSlideEasing)
            } else {
                snap()
            }
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
 * Xiaomi HyperOS / Apple Photos style Rect-to-Rect morph frame.
 *  - Open (`PreEnter -> Visible`): vacates the tapped grid tile / album cover and expands
 *    smoothly straight out of its exact pixel rectangle up to full screen.
 *  - Close (`Visible -> PostExit`): starts ONLY after the back gesture is released, vacates
 *    the target tile / cover slot, and flies the picture or album right back into that exact
 *    pixel rectangle at 100% solid opacity (with seamless album cover crossfade for albums!).
 *  - All geometry runs strictly in the GPU `graphicsLayer` draw phase at 120Hz with zero
 *    per-frame recomposition of the screen content.
 */
@Composable
fun AnimatedVisibilityScope.HyperOsRectMorphFrame(
    entryId: String,
    targetKey: String?,
    targetCornerRadius: Dp = 18.dp,
    verticalCropBias: Float = 0f,
    backdropMaxAlpha: Float = 0.34f,
    fallbackToLastBounds: Boolean = false,
    maxShadowElevation: Dp = 14.dp,
    midFlightCornerBump: Dp = 10.dp,
    hideSourceWhileMorphing: Boolean = true,
    useAlbumCoverCrossfade: Boolean = false,
    sourceRectProvider: (() -> Rect?)? = null,
    backdropAlphaProvider: () -> Float = { 1f },
    content: @Composable (isMorphing: Boolean) -> Unit
) {
    val originTracker = LocalAlbumTransitionOrigin.current
    val density = LocalDensity.current
    val targetCornerPx = with(density) { targetCornerRadius.toPx() }
    val bumpCornerPx = with(density) { midFlightCornerBump.toPx() }
    val maxShadowPx = with(density) { maxShadowElevation.toPx() }

    // 0f = full screen (Visible), 1f = fully collapsed inside the target tile/card (PreEnter / PostExit)
    // Uses `snap()` when morph is disabled for this entry (e.g. an album returning behind a closing
    // photo) so it never extends NavHost's transition duration or blocks back-to-back close animations!
    val morphProgressState = transition.animateFloat(
        transitionSpec = {
            if (!originTracker.isMorphEnabled(entryId)) {
                snap()
            } else if (targetState == EnterExitState.Visible) {
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

    val animTransition = transition
    val isTransitionActive by remember(entryId, animTransition) {
        derivedStateOf {
            (animTransition.currentState != EnterExitState.Visible ||
                animTransition.targetState != EnterExitState.Visible) &&
                originTracker.isMorphEnabled(entryId)
        }
    }
    val isMorphing by remember(entryId, animTransition) {
        derivedStateOf {
            originTracker.isMorphEnabled(entryId) &&
                (morphProgressState.value > 0.002f || isTransitionActive)
        }
    }

    val albumCoverInfo = if (useAlbumCoverCrossfade) originTracker.albumCoverFor(targetKey) else null
    val canSolidlyHandOff = !useAlbumCoverCrossfade || albumCoverInfo != null

    val shouldHideSource by remember(entryId, targetKey, hideSourceWhileMorphing, canSolidlyHandOff, animTransition) {
        derivedStateOf {
            if (!hideSourceWhileMorphing || !canSolidlyHandOff || targetKey == null) return@derivedStateOf false
            if (!originTracker.isMorphEnabled(entryId)) return@derivedStateOf false
            if (!isTransitionActive) return@derivedStateOf false
            val t = morphProgressState.value.coerceIn(0f, 1f)
            val isClosingNow = animTransition.targetState == EnterExitState.PostExit
            val inFlight = if (isClosingNow) t < 0.985f else t > 0.015f
            inFlight && originTracker.boundsFor(targetKey, fallbackToLast = false) != null
        }
    }

    SideEffect {
        if (shouldHideSource && targetKey != null) {
            originTracker.setActiveMorph(entryId, targetKey)
        } else {
            originTracker.clearActiveMorph(entryId)
        }
    }

    DisposableEffect(entryId) {
        onDispose {
            originTracker.clearActiveMorph(entryId)
        }
    }

    val pageDim = rememberPageDimFraction(originTracker)
    val frameSizeRef = remember { floatArrayOf(0f, 0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .hyperOsPageDim(animTransition, originTracker, pageDim)
    ) {
        // Full-screen depth / theater backdrop behind the flying photo or album card.
        if (backdropMaxAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        if (!originTracker.isMorphEnabled(entryId)) return@drawBehind
                        val t = morphProgressState.value.coerceIn(0f, 1f)
                        if (t <= 0.002f && !isTransitionActive) return@drawBehind
                        val isClosingNow = animTransition.targetState == EnterExitState.PostExit
                        val curve = if (isClosingNow) (1f - t).pow(1.25f) else (1f - t).pow(0.75f)
                        val a = (curve * backdropMaxAlpha * backdropAlphaProvider().coerceIn(0f, 1f))
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
                    frameSizeRef[0] = w
                    frameSizeRef[1] = h
                    if (w <= 1f || h <= 1f) return@graphicsLayer

                    val providedSource = sourceRectProvider?.invoke()?.takeIf {
                        it.width > 8f && it.height > 8f &&
                            it.left >= -1f && it.top >= -1f && it.right <= w + 1f && it.bottom <= h + 1f
                    }
                    val src = providedSource ?: Rect(0f, 0f, w, h)
                    val cropBias = if (providedSource != null) 0.5f else verticalCropBias.coerceIn(0f, 1f)

                    // Live lookup: even if the underlying grid just scrolled to a newly viewed photo,
                    // we lock straight onto its exact on-screen pixel rectangle!
                    val target = originTracker.boundsFor(
                        key = targetKey,
                        fallbackToLast = fallbackToLastBounds
                    )?.takeIf {
                        it.width >= 16f && it.height >= 16f &&
                            it.bottom > 0f && it.top < h && it.right > 0f && it.left < w
                    }
                    val hasTarget = target != null

                    // Fallback if target tile/card is off-screen: gentle centered scale & fade
                    val dst = target ?: Rect(
                        left = src.center.x - src.width * 0.36f,
                        top = src.center.y - src.height * 0.36f,
                        right = src.center.x + src.width * 0.36f,
                        bottom = src.center.y + src.height * 0.36f
                    )

                    val cur = lerp(src, dst, t)
                    val curW = cur.width.coerceAtLeast(1f)
                    val curH = cur.height.coerceAtLeast(1f)

                    // Uniform aspect-preserving scale so the photo / album fills cur with zero stretch
                    val uniformScale = max(curW / src.width, curH / src.height).coerceIn(0.02f, 6f)

                    // Exact local clip window that projects pixel-for-pixel onto `cur`
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
                    ambientShadowColor = Color.Black.copy(alpha = 0.36f)
                    spotShadowColor = Color.Black.copy(alpha = 0.45f)

                    clip = true
                    shape = MorphRoundRectShape(
                        left = localLeft,
                        top = localTop,
                        right = localLeft + localW,
                        bottom = localTop + localH,
                        radius = localRadius
                    )

                    // Keep the morphing photo or album-with-cover 100% solid throughout the flight
                    // (since the source slot is vacated and the visuals match pixel-for-pixel!).
                    // Only crossfade softly at the very end when morphing without a cover overlay
                    // (e.g. Collection collages) or when the target tile is scrolled off-screen.
                    alpha = when {
                        !hasTarget -> (1f - t).coerceIn(0f, 1f)
                        canSolidlyHandOff -> 1f
                        else -> ((1f - t) / 0.14f).coerceIn(0f, 1f)
                    }
                }
        ) {
            content(isMorphing)

            if (isMorphing && albumCoverInfo != null) {
                AlbumCoverMorphOverlay(
                    coverInfo = albumCoverInfo,
                    morphProgressState = morphProgressState,
                    targetKey = targetKey,
                    originTracker = originTracker,
                    fallbackToLastBounds = fallbackToLastBounds,
                    frameSizeRef = frameSizeRef,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}

/**
 * Seamless album cover crossfade drawn strictly inside [HyperOsRectMorphFrame] while an album is
 * expanding out of or collapsing back into its rounded cover card on Home/Collections.
 * Uses the exact Coil memory-cache key as `FolderCoverArtwork` so frame 0 is synchronous & crisp.
 */
@Composable
private fun AlbumCoverMorphOverlay(
    coverInfo: AlbumCoverMorphInfo,
    morphProgressState: State<Float>,
    targetKey: String?,
    originTracker: AlbumTransitionOriginTracker,
    fallbackToLastBounds: Boolean,
    frameSizeRef: FloatArray,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val coverRequest = remember(coverInfo) {
        if (coverInfo.isLocal) {
            val uriStr = coverInfo.coverThumbnailLink ?: return@remember null
            ImageRequest.Builder(context)
                .data(LocalThumbnailRequest(uriStr, coverInfo.coverIsVideo))
                .size(420)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(coverInfo.coverItemId))
                .placeholderMemoryCacheKey(DriveConstants.stableThumbMemoryKey(coverInfo.coverItemId))
                .crossfade(false)
                .build()
        } else {
            val url = DriveConstants.buildThumbnailUrl(
                thumbnailLink = coverInfo.coverThumbnailLink,
                fileId = coverInfo.coverItemId,
                isUnmeteredWifi = coverInfo.isUnmeteredWifi,
                isVideo = coverInfo.coverIsVideo,
                sizeBytes = coverInfo.coverSizeBytes
            ) ?: return@remember null
            ImageRequest.Builder(context)
                .data(url)
                .size(420)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(coverInfo.coverItemId))
                .placeholderMemoryCacheKey(DriveConstants.stableThumbMemoryKey(coverInfo.coverItemId))
                .diskCacheKey(DriveConstants.thumbnailCacheKey(coverInfo.coverItemId, coverInfo.isUnmeteredWifi))
                .crossfade(false)
                .build()
        }
    } ?: return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                val t = morphProgressState.value.coerceIn(0f, 1f)
                // 100% opaque right at the card (t >= 0.84), dissolving cleanly into the album grid
                // between t = 0.84 and t = 0.28 so the transition feels like a single living card.
                val coverAlpha = ((t - 0.28f) / 0.56f).coerceIn(0f, 1f)
                alpha = coverAlpha
                if (coverAlpha <= 0.002f) return@graphicsLayer

                // Smoothly keep the cover filling the morphing clip height as it stretches from
                // 1:1 square toward full screen so no bottom edge is ever exposed mid-crossfade.
                val target = originTracker.boundsFor(targetKey, fallbackToLast = fallbackToLastBounds)
                val coverSide = size.width
                if (target != null && coverSide > 1f) {
                    val parentW = frameSizeRef[0].takeIf { it > 1f } ?: coverSide
                    val parentH = frameSizeRef[1].takeIf { it > 1f } ?: (coverSide * 2.25f)
                    val curW = (parentW + (target.width - parentW) * t).coerceAtLeast(1f)
                    val curH = (parentH + (target.height - parentH) * t).coerceAtLeast(1f)
                    val uScale = max(curW / parentW, curH / parentH).coerceIn(0.02f, 6f)
                    val localH = (curH / uScale).coerceAtLeast(coverSide)
                    val extra = (localH / coverSide).coerceIn(1f, 3f)
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = extra
                    scaleY = extra
                }
            }
    ) {
        AsyncImage(
            model = coverRequest,
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
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
    val pageDim = rememberPageDimFraction(tracker)
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

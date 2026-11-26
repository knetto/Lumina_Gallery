package com.luminagallery.drive.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.AbsoluteAlignment
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.AlbumCoverPreview
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

    /**
     * Total close budget. The close transition itself runs linearly so Android predictive-back
     * seeking maps 1:1 onto the finger; [MorphCloseEasing] is applied in the draw phase. When a
     * gesture is released at progress p the remaining flight takes (1 - p) * MORPH_CLOSE_MS.
     */
    const val MORPH_CLOSE_MS = 360

    /** Rubber-band resistance applied to live predictive-back gesture progress (fast start, soft limit). */
    fun gestureResistance(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return 1f - (1f - p).pow(1.6f)
    }

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
sealed interface AlbumCoverMorphInfo {
    data class Single(
        val preview: AlbumCoverPreview,
        val isUnmeteredWifi: Boolean
    ) : AlbumCoverMorphInfo

    data class Collage(
        val previews: List<AlbumCoverPreview>,
        val showDriveBadge: Boolean,
        val isUnmeteredWifi: Boolean
    ) : AlbumCoverMorphInfo
}

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
    private val albumCoverByKey = lruMap<MutableState<AlbumCoverMorphInfo?>>(MAX_TRACKED_ENTRIES)
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
        albumCoverByKey.getOrPut(key) { mutableStateOf(null) }.value = info
    }

    fun albumCoverFor(key: String?): AlbumCoverMorphInfo? =
        if (key != null) albumCoverByKey[key]?.value else null

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

    /**
     * True while a system back gesture is in progress (finger down). The NavHost seeks its
     * transition with the gesture progress during this time; frames use it to switch from the
     * eased animation curves to direct finger tracking.
     */
    var backGestureActive by mutableStateOf(false)
        private set

    /** Edge the current / last back gesture started from (NavigationEvent.EDGE_*). */
    var backGestureEdge: Int = 0
        private set

    fun onBackGesture(active: Boolean, swipeEdge: Int) {
        if (active) backGestureEdge = swipeEdge
        if (backGestureActive != active) backGestureActive = active
    }

    /** Whether the page underneath a sliding page should receive the depth scrim. */
    var dimBackgroundPage: Boolean = true
        private set

    /**
     * Current fitted media bounds in root coordinates, including any swipe displacement.
     * Evaluated in the layer so the close morph can start at the dragged position.
     */
    var viewerContentRectProvider: (() -> Rect?)? = null

    /** Current Viewer background opacity (drops while dragging the photo down to dismiss). */
    var viewerBackdropAlphaProvider: (() -> Float)? = null

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
 * Linear 0f (settled / Visible) -> 1f (off-stage) page fraction that runs in lock-step with the
 * HyperOS page slide. It is linear on purpose: during Android predictive back the NavHost seeks the
 * transition with the raw gesture progress, so this value *is* the finger progress. Easing is
 * applied in the draw phase instead (see [PageSlideMotion]).
 * Uses `snap()` when the page is not sliding (e.g. morph open/close) so this helper NEVER keeps
 * NavHost's outer Transition alive past the morph animation's exact duration!
 */
@Composable
private fun AnimatedVisibilityScope.rememberLinearPageFraction(
    tracker: AlbumTransitionOriginTracker
): State<Float> =
    transition.animateFloat(
        transitionSpec = {
            if (tracker.dimBackgroundPage) {
                tween(durationMillis = OneUiMotion.PAGE_SLIDE_MS, easing = LinearEasing)
            } else {
                snap()
            }
        },
        label = "hyperOsPageFraction"
    ) { state -> if (state == EnterExitState.Visible) 0f else 1f }

/**
 * Remembers where a predictive-back gesture was released so the commit animation continues
 * seamlessly from the exact pose the finger left behind (instead of snapping onto the eased curve).
 * Pure draw-phase bookkeeping: idempotent, so several graphicsLayer blocks may call it per frame.
 */
internal class PredictiveSeekHandoff {
    private var wasSeeking = false
    private var lastSeekProgress = 0f

    /** >= 0 once a seek gesture was released into its commit animation, otherwise -1. */
    var releaseProgress = -1f
        private set

    fun update(progress: Float, seeking: Boolean, settled: Boolean) {
        when {
            settled -> {
                wasSeeking = false
                lastSeekProgress = 0f
                releaseProgress = -1f
            }
            seeking -> {
                wasSeeking = true
                lastSeekProgress = progress
                releaseProgress = -1f
            }
            wasSeeking -> {
                wasSeeking = false
                releaseProgress = lastSeekProgress.coerceIn(0f, 0.98f)
            }
        }
    }
}

/**
 * Converts the linear page fraction into the visual progress of a page slide:
 *  - Regular navigation: [OneUiMotion.PageSlideEasing].
 *  - Predictive back (seeking): exactly the finger progress, so the page tracks the thumb 1:1
 *    like iOS / HyperOS instead of racing ahead on the fast-out curve.
 *  - Gesture released: the eased curve plus a decaying correction, so the page continues from
 *    the exact released position with zero jump.
 */
private class PageSlideMotion(
    private val transition: Transition<EnterExitState>,
    private val tracker: AlbumTransitionOriginTracker,
    private val fraction: State<Float>
) {
    private val handoff = PredictiveSeekHandoff()

    val isEntering: Boolean get() = transition.targetState == EnterExitState.Visible

    /** Visual progress (what we want to show) vs eased progress (what the NavHost slide draws). */
    fun visualProgress(): Float? {
        val settled = transition.currentState == transition.targetState
        if (settled || !tracker.dimBackgroundPage) {
            handoff.update(0f, seeking = false, settled = true)
            return null
        }
        val q = fraction.value.coerceIn(0f, 1f)
        val p = if (isEntering) 1f - q else q
        val eased = OneUiMotion.PageSlideEasing.transform(p)
        val seeking = tracker.lastNavWasPop && tracker.backGestureActive
        handoff.update(p, seeking, settled = false)
        val release = handoff.releaseProgress
        return when {
            seeking -> p
            // Gesture cancelled: the NavHost seeks back to the start, keep tracking linearly.
            release >= 0f && p < release - 0.0005f -> p
            release >= 0f -> {
                val correction = release - OneUiMotion.PageSlideEasing.transform(release)
                val remaining = 1f - ((p - release) / (1f - release)).coerceIn(0f, 1f)
                eased + correction * remaining
            }
            else -> eased
        }
    }

    fun easedProgress(): Float {
        val q = fraction.value.coerceIn(0f, 1f)
        return OneUiMotion.PageSlideEasing.transform(if (isEntering) 1f - q else q)
    }
}

/**
 * Xiaomi HyperOS depth scrim + predictive-back finger tracking for a sliding page:
 *  - Only the page sitting *underneath* a sliding page is dimmed (outgoing page on push,
 *    returning page on pop). The page on top always stays crisp.
 *  - While the back gesture is seeking, a translation correction is layered on top of the NavHost
 *    slide so the top page follows the thumb 1:1 and the page below parallaxes at 1/4 speed.
 */
private fun Modifier.hyperOsPageMotion(
    motion: PageSlideMotion,
    tracker: AlbumTransitionOriginTracker,
    enabled: () -> Boolean = { true }
): Modifier = this
    .graphicsLayer {
        translationX = 0f
        if (!enabled()) return@graphicsLayer
        val visual = motion.visualProgress() ?: return@graphicsLayer
        val eased = motion.easedProgress()
        if (visual == eased) return@graphicsLayer
        // Exiting page slides a full width, the page underneath parallaxes a quarter width.
        val span = if (motion.isEntering) size.width / 4f else size.width
        translationX = span * (visual - eased)
    }
    .drawWithContent {
        drawContent()
        if (!enabled() || !tracker.dimBackgroundPage) return@drawWithContent
        val visual = motion.visualProgress() ?: return@drawWithContent
        val entering = motion.isEntering
        val isUnderneath = if (tracker.lastNavWasPop) entering else !entering
        if (!isUnderneath) return@drawWithContent
        // Underneath page: fully dimmed while covered, clearing as it comes back into focus.
        val f = if (entering) 1f - visual else visual
        if (f > 0.003f) {
            drawRect(Color.Black.copy(alpha = (OneUiMotion.PAGE_DIM_ALPHA * f).coerceIn(0f, 1f)))
        }
    }

/** Draw-phase pose of a rect morph. */
private class MorphPose(
    /** Predictive-back "card preview" amount the card is shrunk by (0 = none). */
    val previewStart: Float,
    /** 0 = at [previewStart] pose / full screen, 1 = landed inside the target tile. */
    val flight: Float,
    val closing: Boolean
) {
    val isRest: Boolean get() = flight <= 0.002f && previewStart <= 0.002f
}

/** Snapshot of where a rect-morph card currently is, shared by the frame and its cover overlays. */
internal class MorphGeometry(
    val current: Rect,
    val contentFrame: Rect,
    val flight: Float,
    val hasTarget: Boolean,
    val sourceProvided: Boolean
)

private fun scaleAboutCenter(rect: Rect, scale: Float): Rect {
    val c = rect.center
    val hw = rect.width * scale * 0.5f
    val hh = rect.height * scale * 0.5f
    return Rect(c.x - hw, c.y - hh, c.x + hw, c.y + hh)
}

/**
 * Xiaomi HyperOS / Apple Photos style Rect-to-Rect morph frame.
 *  - Open (`PreEnter -> Visible`): vacates the tapped grid tile / album cover and expands
 *    smoothly straight out of its exact pixel rectangle up to full screen.
 *  - Predictive back (gesture in progress): the page / photo lifts into a rounded "card preview"
 *    that shrinks with the thumb while the real page underneath shows through the fading backdrop.
 *    Cancelling springs it back; releasing flies it from that exact pose into its tile.
 *  - Close (`Visible -> PostExit`): vacates the target tile / cover slot and flies the picture
 *    or album right back into that exact pixel rectangle at 100% solid opacity (with seamless
 *    album cover crossfade for albums!).
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
    previewMinScale: Float = 0.86f,
    previewCornerRadius: Dp = 28.dp,
    previewBackdropDrop: Float = 0.55f,
    sourceRectProvider: (() -> Rect?)? = null,
    backdropAlphaProvider: () -> Float = { 1f },
    content: @Composable (isMorphing: Boolean) -> Unit
) {
    val originTracker = LocalAlbumTransitionOrigin.current
    val density = LocalDensity.current
    val targetCornerPx = with(density) { targetCornerRadius.toPx() }
    val bumpCornerPx = with(density) { midFlightCornerBump.toPx() }
    val maxShadowPx = with(density) { maxShadowElevation.toPx() }
    val previewCornerPx = with(density) { previewCornerRadius.toPx() }

    // 0f = full screen (Visible), 1f = fully collapsed inside the target tile/card (PreEnter / PostExit).
    // The close runs LINEAR so predictive-back seeking equals the finger progress; the close easing
    // is applied in the draw phase. Uses `snap()` when morph is disabled for this entry (e.g. an album
    // returning behind a closing photo) so it never extends NavHost's transition duration.
    val morphProgressState = transition.animateFloat(
        transitionSpec = {
            if (!originTracker.isMorphEnabled(entryId)) {
                snap()
            } else if (targetState == EnterExitState.Visible) {
                tween(durationMillis = OneUiMotion.MORPH_OPEN_MS, easing = OneUiMotion.MorphOpenEasing)
            } else {
                tween(durationMillis = OneUiMotion.MORPH_CLOSE_MS, easing = LinearEasing)
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
    val seekHandoff = remember(entryId) { PredictiveSeekHandoff() }
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

    val pageFraction = rememberLinearPageFraction(originTracker)
    val pageMotion = remember(animTransition, pageFraction) {
        PageSlideMotion(animTransition, originTracker, pageFraction)
    }
    val frameSize = remember { mutableStateOf(IntSize.Zero) }

    fun resolvePose(): MorphPose {
        val raw = morphProgressState.value.coerceIn(0f, 1f)
        val closing = animTransition.targetState == EnterExitState.PostExit
        if (!closing) {
            seekHandoff.update(raw, seeking = false, settled = true)
            return MorphPose(previewStart = 0f, flight = raw, closing = false)
        }
        val seeking = originTracker.backGestureActive
        seekHandoff.update(raw, seeking, settled = false)
        val release = seekHandoff.releaseProgress
        return when {
            seeking -> MorphPose(OneUiMotion.gestureResistance(raw), 0f, closing = true)
            // Gesture cancelled: the NavHost seeks back to 0, so the card preview grows back to full.
            release >= 0f && raw < release - 0.0005f ->
                MorphPose(OneUiMotion.gestureResistance(raw), 0f, closing = true)
            release >= 0f -> {
                val local = ((raw - release) / (1f - release)).coerceIn(0f, 1f)
                MorphPose(
                    previewStart = OneUiMotion.gestureResistance(release),
                    flight = OneUiMotion.MorphCloseEasing.transform(local),
                    closing = true
                )
            }
            else -> MorphPose(0f, OneUiMotion.MorphCloseEasing.transform(raw), closing = true)
        }
    }

    fun computeGeometry(w: Float, h: Float, pose: MorphPose): MorphGeometry {
        val providedSource = sourceRectProvider?.invoke()?.takeIf {
            // A dragged viewer can extend beyond the viewport at the start of its close.
            it.width > 8f && it.height > 8f &&
                it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite()
        }
        val contentFrame = providedSource ?: Rect(0f, 0f, w, h)
        val start = if (pose.previewStart > 0f) {
            // HyperOS card preview: shrinks with the thumb and drifts slightly away from the edge
            // the swipe started on, so it feels physically pushed by the finger.
            val edgeDirection = when (originTracker.backGestureEdge) {
                NavigationEvent.EDGE_LEFT -> 1f
                NavigationEvent.EDGE_RIGHT -> -1f
                else -> 0f
            }
            scaleAboutCenter(contentFrame, 1f - (1f - previewMinScale) * pose.previewStart)
                .translate(w * 0.045f * pose.previewStart * edgeDirection, 0f)
        } else {
            contentFrame
        }
        // Live lookup: even if the underlying grid just scrolled to a newly viewed photo,
        // we lock straight onto its exact on-screen pixel rectangle!
        val target = originTracker.boundsFor(
            key = targetKey,
            fallbackToLast = fallbackToLastBounds
        )?.takeIf {
            it.width >= 16f && it.height >= 16f &&
                it.bottom > 0f && it.top < h && it.right > 0f && it.left < w
        }
        // Fallback if target tile/card is off-screen: gentle centered scale & fade
        val dst = target ?: scaleAboutCenter(start, 0.72f)
        return MorphGeometry(
            current = lerp(start, dst, pose.flight),
            contentFrame = contentFrame,
            flight = pose.flight,
            hasTarget = target != null,
            sourceProvided = providedSource != null
        )
    }

    val overlayGeometry: () -> MorphGeometry? = {
        val s = frameSize.value
        if (s.width <= 1 || s.height <= 1) {
            null
        } else {
            computeGeometry(s.width.toFloat(), s.height.toFloat(), resolvePose())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .hyperOsPageMotion(pageMotion, originTracker, enabled = { !originTracker.isMorphEnabled(entryId) })
    ) {
        // Full-screen depth / theater backdrop behind the flying photo or album card.
        if (backdropMaxAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        if (!originTracker.isMorphEnabled(entryId)) return@drawBehind
                        val pose = resolvePose()
                        if (pose.isRest && !isTransitionActive) return@drawBehind
                        val e = pose.flight
                        val curve = if (pose.closing) (1f - e).pow(1.25f) else (1f - e).pow(0.75f)
                        val startFactor = 1f - previewBackdropDrop * pose.previewStart
                        val a = (curve * startFactor * backdropMaxAlpha * backdropAlphaProvider().coerceIn(0f, 1f))
                            .coerceIn(0f, 1f)
                        if (a > 0.003f) drawRect(Color.Black.copy(alpha = a))
                    }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { frameSize.value = it }
                .graphicsLayer {
                    if (!originTracker.isMorphEnabled(entryId)) return@graphicsLayer
                    val pose = resolvePose()
                    if (pose.isRest) return@graphicsLayer

                    val w = size.width
                    val h = size.height
                    if (w <= 1f || h <= 1f) return@graphicsLayer

                    val g = computeGeometry(w, h, pose)
                    val cropBias = if (g.sourceProvided) 0.5f else verticalCropBias.coerceIn(0f, 1f)
                    val cur = g.current
                    // Uniform aspect-preserving scale so the photo / album fills cur with zero stretch
                    val uniformScale = morphScale(g.contentFrame, cur)
                    // Exact local clip window that projects pixel-for-pixel onto `cur`
                    val localClip = morphClipRect(g.contentFrame, cur, uniformScale, cropBias)

                    scaleX = uniformScale
                    scaleY = uniformScale
                    translationX = cur.center.x - w * 0.5f - (localClip.center.x - w * 0.5f) * uniformScale
                    translationY = cur.center.y - h * 0.5f - (localClip.center.y - h * 0.5f) * uniformScale

                    val e = g.flight
                    val bell = sin(e * PI.toFloat()).coerceIn(0f, 1f)
                    val startCornerPx = previewCornerPx * pose.previewStart
                    val visualCornerPx = startCornerPx + (targetCornerPx - startCornerPx) * e + bumpCornerPx * bell
                    val localRadius = (visualCornerPx / uniformScale).coerceAtLeast(0f)

                    val previewShadowPx = maxShadowPx * 0.55f * pose.previewStart * (1f - e)
                    shadowElevation = max(previewShadowPx, if (g.hasTarget) maxShadowPx * bell else 0f)
                    ambientShadowColor = Color.Black.copy(alpha = 0.36f)
                    spotShadowColor = Color.Black.copy(alpha = 0.45f)

                    clip = true
                    shape = MorphRoundRectShape(
                        left = localClip.left,
                        top = localClip.top,
                        right = localClip.right,
                        bottom = localClip.bottom,
                        radius = localRadius
                    )

                    // Keep the morphing photo or album-with-cover 100% solid throughout the flight
                    // (since the source slot is vacated and the visuals match pixel-for-pixel!).
                    // Fade when there is no cover to hand off to or the target is scrolled off-screen.
                    alpha = when {
                        !g.hasTarget -> (1f - e).coerceIn(0f, 1f)
                        canSolidlyHandOff -> 1f
                        else -> ((1f - e) / 0.14f).coerceIn(0f, 1f)
                    }
                }
        ) {
            content(isMorphing)

            if (isMorphing) {
                when (albumCoverInfo) {
                    is AlbumCoverMorphInfo.Single -> AlbumCoverMorphOverlay(
                        coverInfo = albumCoverInfo,
                        geometry = overlayGeometry,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                    is AlbumCoverMorphInfo.Collage -> {
                        val target = originTracker.boundsFor(targetKey, fallbackToLastBounds)
                        if (target != null && target.width >= 16f && target.height >= 16f) {
                            CollectionCoverArtwork(
                                previewCovers = albumCoverInfo.previews,
                                hasDriveChild = albumCoverInfo.showDriveBadge,
                                isUnmeteredWifi = albumCoverInfo.isUnmeteredWifi,
                                modifier = Modifier
                                    .align(AbsoluteAlignment.TopLeft)
                                    .size(with(density) { target.width.toDp() })
                                    .graphicsLayer {
                                        val g = overlayGeometry()
                                        if (g == null || size.width <= 0f || size.height <= 0f) {
                                            alpha = 0f
                                            return@graphicsLayer
                                        }
                                        alpha = albumCoverAlpha(g.flight)
                                        if (alpha <= 0f) return@graphicsLayer

                                        val clip = morphClipRect(
                                            g.contentFrame,
                                            g.current,
                                            morphScale(g.contentFrame, g.current),
                                            verticalCropBias.coerceIn(0f, 1f)
                                        )
                                        // Draw the square at tile size, then scale it into the clip.
                                        // The gaps and badge land at their normal size as the morph ends.
                                        val scale = max(clip.width / size.width, clip.height / size.height)
                                        transformOrigin = TransformOrigin(0f, 0f)
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = clip.center.x - size.width * scale * 0.5f
                                        translationY = clip.center.y - size.height * scale * 0.5f
                                    }
                            )
                        }
                    }
                    null -> Unit
                }
            }
        }
    }
}

private fun morphScale(source: Rect, current: Rect): Float =
    max(
        current.width.coerceAtLeast(1f) / source.width,
        current.height.coerceAtLeast(1f) / source.height
    ).coerceIn(0.02f, 6f)

private fun morphClipRect(source: Rect, current: Rect, scale: Float, cropBias: Float): Rect {
    val width = (current.width.coerceAtLeast(1f) / scale).coerceIn(1f, source.width)
    val height = (current.height.coerceAtLeast(1f) / scale).coerceIn(1f, source.height)
    val left = source.left + (source.width - width) * 0.5f
    val top = source.top + (source.height - height) * cropBias
    return Rect(left, top, left + width, top + height)
}

private fun albumCoverAlpha(progress: Float): Float = ((progress - 0.28f) / 0.56f).coerceIn(0f, 1f)

/**
 * Seamless album cover crossfade drawn strictly inside [HyperOsRectMorphFrame] while an album is
 * expanding out of or collapsing back into its rounded cover card on Home/Collections.
 * Uses the exact Coil memory-cache key as `FolderCoverArtwork` so frame 0 is synchronous & crisp.
 */
@Composable
private fun AlbumCoverMorphOverlay(
    coverInfo: AlbumCoverMorphInfo.Single,
    geometry: () -> MorphGeometry?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val coverRequest = remember(coverInfo) {
        val preview = coverInfo.preview
        if (preview.isLocal) {
            val uriStr = preview.thumbnailLink ?: return@remember null
            ImageRequest.Builder(context)
                .data(LocalThumbnailRequest(uriStr, preview.isVideo))
                .size(420)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(preview.itemId))
                .placeholderMemoryCacheKey(DriveConstants.stableThumbMemoryKey(preview.itemId))
                .crossfade(false)
                .build()
        } else {
            ImageRequest.Builder(context)
                .data(
                    com.luminagallery.drive.data.repository.DriveThumbnailRequest(
                        fileId = preview.itemId,
                        thumbnailLink = preview.thumbnailLink,
                        isUnmeteredWifi = coverInfo.isUnmeteredWifi,
                        isVideo = preview.isVideo,
                        sizeBytes = preview.sizeBytes,
                        requestedMaxPx = 420
                    )
                )
                .size(420)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(preview.itemId))
                .placeholderMemoryCacheKey(DriveConstants.stableThumbMemoryKey(preview.itemId))
                .diskCacheKey(DriveConstants.stableThumbDiskKey(preview.itemId))
                .crossfade(false)
                .build()
        }
    } ?: return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                val g = geometry()
                if (g == null) {
                    alpha = 0f
                    return@graphicsLayer
                }
                // 100% opaque right at the card (flight >= 0.84), dissolving cleanly into the album grid
                // between 0.84 and 0.28 so the transition feels like a single living card.
                val coverAlpha = albumCoverAlpha(g.flight)
                alpha = coverAlpha
                if (coverAlpha <= 0.002f) return@graphicsLayer

                // Smoothly keep the cover filling the morphing clip height as it stretches from
                // 1:1 square toward full screen so no bottom edge is ever exposed mid-crossfade.
                val coverSide = size.width
                if (g.hasTarget && coverSide > 1f) {
                    val uScale = morphScale(g.contentFrame, g.current)
                    val localH = (g.current.height.coerceAtLeast(1f) / uScale).coerceAtLeast(coverSide)
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
 *  - During a predictive-back swipe the top page follows the thumb 1:1 (iOS / HyperOS feel)
 *    and the commit / cancel continues from the released position without any jump.
 *  - At rest it draws nothing extra (zero overhead).
 */
@Composable
fun AnimatedVisibilityScope.HyperOsTransitionFrame(
    content: @Composable () -> Unit
) {
    val tracker = LocalAlbumTransitionOrigin.current
    val animTransition = transition
    val pageFraction = rememberLinearPageFraction(tracker)
    val pageMotion = remember(animTransition, pageFraction) {
        PageSlideMotion(animTransition, tracker, pageFraction)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hyperOsPageMotion(pageMotion, tracker)
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
            ImageRequest.Builder(context)
                .data(
                    com.luminagallery.drive.data.repository.DriveThumbnailRequest(
                        fileId = item.id,
                        thumbnailLink = item.thumbnailLink,
                        isUnmeteredWifi = isUnmeteredWifi,
                        isVideo = item.isVideo,
                        sizeBytes = item.sizeBytes,
                        requestedMaxPx = 360
                    )
                )
                .size(360)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .diskCacheKey(DriveConstants.stableThumbDiskKey(item.id))
                .crossfade(false)
                .build()
        }
        imageLoader.enqueue(request)
    }
}

/**
 * Pre-warms circular person face avatars into Coil's memory cache right before navigating to
 * People so every person circle paints cleanly on Frame 0 without disk-decode pop-in.
 */
fun prefetchPersonAvatars(
    context: Context,
    imageLoader: ImageLoader,
    cropPaths: List<String>,
    maxAvatars: Int = 24
) {
    if (cropPaths.isEmpty()) return
    val count = cropPaths.size.coerceAtMost(maxAvatars)
    for (i in 0 until count) {
        val path = cropPaths[i]
        if (path.isBlank()) continue
        val file = java.io.File(path)
        if (!file.exists()) continue
        val stamp = file.lastModified()
        val req = ImageRequest.Builder(context)
            .data(file)
            .memoryCacheKey("person_avatar_${file.name}_$stamp")
            .size(256)
            .crossfade(false)
            .build()
        imageLoader.enqueue(req)
    }
}

/**
 * Pre-warms visible Google Drive album covers (including split collage tiles) right after
 * pinned roots load from Room so Drive album cards paint immediately without gray delays.
 */
fun prefetchDriveAlbumCovers(
    context: Context,
    imageLoader: ImageLoader,
    folders: List<com.luminagallery.drive.domain.model.DriveFolder>,
    isUnmeteredWifi: Boolean,
    maxCovers: Int = 16
) {
    if (folders.isEmpty()) return
    val enqueuedIds = HashSet<String>(maxCovers)
    for (folder in folders) {
        if (enqueuedIds.size >= maxCovers) break
        if (folder.isLocal || folder.isLocked) continue

        if (folder.subfolderCount > 0 && folder.previewCovers.size >= 2) {
            for (preview in folder.previewCovers.take(4)) {
                if (enqueuedIds.size >= maxCovers) break
                if (preview.isLocal || !enqueuedIds.add(preview.itemId)) continue
                val req = ImageRequest.Builder(context)
                    .data(
                        com.luminagallery.drive.data.repository.DriveThumbnailRequest(
                            fileId = preview.itemId,
                            thumbnailLink = preview.thumbnailLink,
                            isUnmeteredWifi = isUnmeteredWifi,
                            isVideo = preview.isVideo,
                            sizeBytes = preview.sizeBytes,
                            requestedMaxPx = 280
                        )
                    )
                    .size(256)
                    .precision(Precision.INEXACT)
                    .allowRgb565(true)
                    .memoryCacheKey(DriveConstants.stableThumbMemoryKey(preview.itemId))
                    .diskCacheKey(DriveConstants.stableThumbDiskKey(preview.itemId))
                    .crossfade(false)
                    .build()
                imageLoader.enqueue(req)
            }
        } else {
            val coverId = folder.coverItemId ?: continue
            if (!enqueuedIds.add(coverId)) continue
            val req = ImageRequest.Builder(context)
                .data(
                    com.luminagallery.drive.data.repository.DriveThumbnailRequest(
                        fileId = coverId,
                        thumbnailLink = folder.coverThumbnailLink,
                        isUnmeteredWifi = isUnmeteredWifi,
                        isVideo = folder.coverIsVideo,
                        sizeBytes = folder.coverSizeBytes,
                        requestedMaxPx = 420
                    )
                )
                .size(420)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(coverId))
                .diskCacheKey(DriveConstants.stableThumbDiskKey(coverId))
                .crossfade(false)
                .build()
            imageLoader.enqueue(req)
        }
    }
}

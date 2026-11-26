package com.luminagallery.drive.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tiny animated icon set in the spirit of iOS SF Symbol effects and HyperOS micro-motion:
 * every animation is short, physically sprung and only plays in response to a real state change
 * (never when a list item is recycled or when paging to a different photo).
 */
private val BurstEasing = CubicBezierEasing(0.16f, 0.84f, 0.30f, 1f)
private val FavoriteBurstAccent = Color(0xFFFFB36B)

/**
 * Remembers the previous value for a given [animationKey] so an animation only fires on a real
 * toggle of the same subject, not when the key (e.g. the photo being shown) changes.
 */
private class ToggleMemory<T>(var key: Any?, var value: T)

/**
 * Heart that "pops" with a soft ring + particle burst when favourited (Apple Photos / Instagram
 * feel, toned down) and gently deflates when un-favourited.
 *
 * @param animationKey identity of the subject (e.g. photo id); changing it never animates.
 */
@Composable
fun AnimatedFavoriteIcon(
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    inactiveTint: Color = Color.White,
    activeTint: Color = Color(0xFFFF4D67),
    animationKey: Any? = null,
    contentDescription: String? = if (isFavorite) "Remove from favorites" else "Add to favorites"
) {
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    val memory = remember { ToggleMemory(animationKey, isFavorite) }
    val fill by animateFloatAsState(
        targetValue = if (isFavorite) 1f else 0f,
        animationSpec = tween(durationMillis = if (isFavorite) 120 else 200),
        label = "favoriteFill"
    )

    LaunchedEffect(isFavorite, animationKey) {
        if (memory.key != animationKey) {
            memory.key = animationKey
            memory.value = isFavorite
            pop.snapTo(1f)
            burst.snapTo(1f)
            return@LaunchedEffect
        }
        if (memory.value == isFavorite) return@LaunchedEffect
        memory.value = isFavorite
        if (isFavorite) {
            pop.snapTo(0.62f)
            burst.snapTo(0f)
            launch { burst.animateTo(1f, tween(durationMillis = 560, easing = BurstEasing)) }
            pop.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 520f))
        } else {
            pop.snapTo(0.84f)
            pop.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 620f))
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .drawBehind {
                val b = burst.value
                if (b >= 0.999f) return@drawBehind
                val s = this.size.minDimension
                val c = center
                // Expanding ring that thins out as it grows.
                val ringRadius = s * (0.30f + 0.40f * b)
                val ringWidth = s * 0.14f * (1f - b)
                if (ringWidth > 0.3f) {
                    drawCircle(
                        color = activeTint.copy(alpha = 0.55f * (1f - b)),
                        radius = ringRadius,
                        center = c,
                        style = Stroke(width = ringWidth)
                    )
                }
                // Seven particles flung outward, alternating red / warm accent.
                val particleRadius = s * 0.065f * (1f - b)
                if (particleRadius > 0.25f) {
                    val distance = s * (0.36f + 0.38f * b)
                    for (i in 0 until 7) {
                        val angle = (i / 7f) * 2f * PI.toFloat() - PI.toFloat() / 2f
                        drawCircle(
                            color = if (i % 2 == 0) activeTint else FavoriteBurstAccent,
                            radius = particleRadius,
                            center = Offset(c.x + cos(angle) * distance, c.y + sin(angle) * distance),
                            alpha = (1f - b).coerceIn(0f, 1f)
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (fill < 0.999f) {
            Icon(
                imageVector = Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = inactiveTint,
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        alpha = 1f - fill
                        scaleX = pop.value
                        scaleY = pop.value
                    }
            )
        }
        if (fill > 0.001f) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = activeTint,
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        alpha = fill
                        scaleX = pop.value
                        scaleY = pop.value
                    }
            )
        }
    }
}

/**
 * Play <-> Pause icon that morphs its geometry (the two pause bars fold into the two halves of
 * the play triangle) with a soft spring, like the iOS / HyperOS media controls.
 */
@Composable
fun AnimatedPlayPauseIcon(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 24.dp
) {
    val progress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
        label = "playPauseMorph"
    )
    val path = remember { Path() }
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = if (isPlaying) "Pause" else "Play" }
    ) {
        val unit = this.size.minDimension / 24f
        val t = progress.coerceIn(0f, 1f)
        fun lerpPt(play: Offset, pause: Offset) = Offset(
            (play.x + (pause.x - play.x) * t) * unit,
            (play.y + (pause.y - play.y) * t) * unit
        )
        path.reset()
        // Left bar <-> left half of the (optically centred) triangle.
        val l0 = lerpPt(Offset(8.4f, 5f), Offset(6f, 5f))
        val l1 = lerpPt(Offset(13.9f, 8.5f), Offset(10f, 5f))
        val l2 = lerpPt(Offset(13.9f, 15.5f), Offset(10f, 19f))
        val l3 = lerpPt(Offset(8.4f, 19f), Offset(6f, 19f))
        path.moveTo(l0.x, l0.y); path.lineTo(l1.x, l1.y); path.lineTo(l2.x, l2.y); path.lineTo(l3.x, l3.y); path.close()
        // Right bar <-> right half (tip) of the triangle.
        val r0 = lerpPt(Offset(13.9f, 8.5f), Offset(14f, 5f))
        val r1 = lerpPt(Offset(19.4f, 12f), Offset(18f, 5f))
        val r2 = lerpPt(Offset(19.4f, 12f), Offset(18f, 19f))
        val r3 = lerpPt(Offset(13.9f, 15.5f), Offset(14f, 19f))
        path.moveTo(r0.x, r0.y); path.lineTo(r1.x, r1.y); path.lineTo(r2.x, r2.y); path.lineTo(r3.x, r3.y); path.close()
        drawPath(path = path, color = tint, style = Fill)
        // Soft rounded silhouette (SF Symbols style) via a thin round-joined outline.
        drawPath(
            path = path,
            color = tint,
            style = Stroke(
                width = 1.6f * unit,
                join = StrokeJoin.Round,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.cornerPathEffect(1.2f * unit)
            )
        )
    }
}

/**
 * Selection check-circle: springs in when selection mode starts, fills with the accent colour and
 * draws its check mark stroke-by-stroke when picked (iOS Photos / HyperOS Gallery style).
 */
@Composable
fun AnimatedSelectionCheck(
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    selectedColor: Color,
    unselectedFill: Color = Color.Black.copy(alpha = 0.28f),
    borderColor: Color = Color.White.copy(alpha = 0.9f),
    checkColor: Color = Color.White
) {
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
        label = "selectionFill"
    )
    val check by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (selected) tween(durationMillis = 240, delayMillis = 40, easing = BurstEasing) else tween(110),
        label = "selectionCheckDraw"
    )
    val bounce by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "selectionBounce"
    )
    val checkPath = remember { Path() }
    val segment = remember { Path() }
    val measure = remember { PathMeasure() }

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = bounce
                scaleY = bounce
            }
            .semantics { contentDescription = if (selected) "Selected" else "Not selected" }
    ) {
        val r = this.size.minDimension / 2f
        // Translucent base + white rim (fades away as the accent fill grows).
        drawCircle(color = unselectedFill, radius = r)
        if (fill < 0.999f) {
            drawCircle(
                color = borderColor.copy(alpha = borderColor.alpha * (1f - fill)),
                radius = r - 0.75.dp.toPx(),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
        if (fill > 0.001f) {
            drawCircle(color = selectedColor, radius = r * (0.55f + 0.45f * fill), alpha = fill.coerceIn(0f, 1f))
        }
        if (check > 0.001f) {
            val u = this.size.minDimension / 24f
            checkPath.reset()
            checkPath.moveTo(7f * u, 12.4f * u)
            checkPath.lineTo(10.6f * u, 16f * u)
            checkPath.lineTo(17.2f * u, 8.6f * u)
            measure.setPath(checkPath, false)
            segment.reset()
            measure.getSegment(0f, measure.length * check.coerceIn(0f, 1f), segment, true)
            drawPath(
                path = segment,
                color = checkColor,
                style = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

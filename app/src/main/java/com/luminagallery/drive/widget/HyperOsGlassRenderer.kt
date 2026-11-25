package com.luminagallery.drive.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometric tile layout chosen for a multi-photo widget based on user layout preference,
 * actual resized widget aspect ratio, distinct photo count of the day/group, and photo orientations.
 */
internal enum class ResolvedTilePattern(val maxSlots: Int) {
    SINGLE(1),
    STRIP_HORIZONTAL_2(2),
    STRIP_VERTICAL_2(2),
    HERO_LEFT_2_RIGHT(3),
    HERO_RIGHT_2_LEFT(3),
    HERO_TOP_2_BOTTOM(3),
    STRIP_HORIZONTAL_3(3),
    STRIP_VERTICAL_3(3),
    GRID_2X2(4),
    BENTO_4_WIDE(4),
    BENTO_4_TOP_HERO(4),
    STRIP_HORIZONTAL_4(4),
    MOSAIC_5_HERO_LEFT(5),
    MOSAIC_5_HERO_TOP(5),
    GRID_3X2(6),
    GRID_2X3(6)
}

internal data class TileBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class WidgetCaptionData(
    val title: String,
    val subtitle: String? = null,
    val badgeIconText: String? = null
)

/**
 * Renders Xiaomi HyperOS-authentic continuous-curvature squircle photo & collage widgets
 * with physical curved-glass edge refraction, dual-arc crystalline specular rims,
 * frosted glass collage mats, and real backdrop-blurred glass caption/date badges.
 */
internal object HyperOsGlassRenderer {

    private val WeekdayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    private val MonthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())

    /**
     * Deterministically selects the best responsive tile pattern for a widget's exact dimensions,
     * available distinct photo count, and individual photo aspect ratios (width/height).
     */
    fun resolveTilePattern(
        kind: PhotoWidgetKind,
        collageLayout: WidgetCollageLayout,
        widthDp: Float,
        heightDp: Float,
        photoCount: Int,
        photoAspectRatios: List<Float> = emptyList(),
        varietySeed: Int = 0
    ): ResolvedTilePattern {
        if (!kind.isMultiPhoto || photoCount <= 1) return ResolvedTilePattern.SINGLE

        val safeW = widthDp.coerceAtLeast(48f)
        val safeH = heightDp.coerceAtLeast(48f)
        val aspect = safeW / safeH

        // Explicit user-chosen layout overrides (gracefully adapting when a day has fewer photos)
        when (collageLayout) {
            WidgetCollageLayout.STRIP -> return if (aspect < 0.85f) {
                if (safeH >= 210f && photoCount >= 3) ResolvedTilePattern.STRIP_VERTICAL_3
                else ResolvedTilePattern.STRIP_VERTICAL_2
            } else {
                when {
                    safeW >= 290f && photoCount >= 4 -> ResolvedTilePattern.STRIP_HORIZONTAL_4
                    safeW >= 185f && photoCount >= 3 -> ResolvedTilePattern.STRIP_HORIZONTAL_3
                    else -> ResolvedTilePattern.STRIP_HORIZONTAL_2
                }
            }
            WidgetCollageLayout.HERO_SIDE -> return when {
                photoCount == 2 -> bestTwoPhotoPattern(safeW, safeH, photoAspectRatios)
                aspect < 0.82f -> ResolvedTilePattern.HERO_TOP_2_BOTTOM
                else -> ResolvedTilePattern.HERO_LEFT_2_RIGHT
            }
            WidgetCollageLayout.GRID_2X2 -> return when {
                photoCount >= 4 -> ResolvedTilePattern.GRID_2X2
                photoCount == 3 -> bestThreePhotoPattern(safeW, safeH, photoAspectRatios, varietySeed)
                else -> bestTwoPhotoPattern(safeW, safeH, photoAspectRatios)
            }
            WidgetCollageLayout.BENTO_4 -> return when {
                photoCount >= 4 && aspect >= 1.05f -> ResolvedTilePattern.BENTO_4_WIDE
                photoCount >= 4 -> ResolvedTilePattern.GRID_2X2
                photoCount == 3 -> bestThreePhotoPattern(safeW, safeH, photoAspectRatios, varietySeed)
                else -> bestTwoPhotoPattern(safeW, safeH, photoAspectRatios)
            }
            WidgetCollageLayout.BENTO_TOP -> return when {
                photoCount >= 4 -> ResolvedTilePattern.BENTO_4_TOP_HERO
                photoCount == 3 -> ResolvedTilePattern.HERO_TOP_2_BOTTOM
                else -> bestTwoPhotoPattern(safeW, safeH, photoAspectRatios)
            }
            WidgetCollageLayout.MOSAIC_5 -> return when {
                photoCount >= 5 && aspect >= 1.15f -> ResolvedTilePattern.MOSAIC_5_HERO_LEFT
                photoCount >= 5 -> ResolvedTilePattern.MOSAIC_5_HERO_TOP
                photoCount == 4 -> bestFourPhotoPattern(safeW, safeH, photoAspectRatios)
                photoCount == 3 -> bestThreePhotoPattern(safeW, safeH, photoAspectRatios, varietySeed)
                else -> bestTwoPhotoPattern(safeW, safeH, photoAspectRatios)
            }
            WidgetCollageLayout.GRID_6 -> return when {
                photoCount >= 6 && aspect < 0.85f -> ResolvedTilePattern.GRID_2X3
                photoCount >= 6 -> ResolvedTilePattern.GRID_3X2
                photoCount == 5 -> if (aspect >= 1.15f) ResolvedTilePattern.MOSAIC_5_HERO_LEFT else ResolvedTilePattern.MOSAIC_5_HERO_TOP
                photoCount == 4 -> bestFourPhotoPattern(safeW, safeH, photoAspectRatios)
                photoCount == 3 -> bestThreePhotoPattern(safeW, safeH, photoAspectRatios, varietySeed)
                else -> bestTwoPhotoPattern(safeW, safeH, photoAspectRatios)
            }
            WidgetCollageLayout.AUTO -> Unit
        }

        // SMART AUTO: combine widget physical dimensions, day's distinct photo count, AND photo orientations!
        val area = safeW * safeH
        val maxComfortSlots = when {
            aspect >= 2.25f -> if (safeW >= 290f) 4 else 3 // Wide short banner (4x1 / 5x1)
            safeW < 115f -> 3 // Narrow 1-column strip
            area >= 48_000f && min(safeW, safeH) >= 148f -> 6 // Large 4x2+, 4x3, 4x4, 2x4
            area >= 35_000f && max(safeW, safeH) >= 225f -> 5 // Medium-wide 4x2 / 3x3 / 2x3
            else -> 4 // Compact 2x2
        }
        val targetCount = min(photoCount, maxComfortSlots)

        return when (targetCount) {
            2 -> bestTwoPhotoPattern(safeW, safeH, photoAspectRatios)
            3 -> bestThreePhotoPattern(safeW, safeH, photoAspectRatios, varietySeed)
            4 -> bestFourPhotoPattern(safeW, safeH, photoAspectRatios)
            5 -> bestFivePhotoPattern(safeW, safeH, photoAspectRatios)
            else -> bestSixPhotoPattern(safeW, safeH, photoAspectRatios)
        }
    }

    /**
     * Resolves the optimal tile pattern AND reorders [photos] so portrait photos sit in portrait
     * tiles and landscape photos sit in landscape tiles with minimal crop distortion.
     */
    fun <T> resolveSmartPatternAndOrder(
        kind: PhotoWidgetKind,
        collageLayout: WidgetCollageLayout,
        widthDp: Float,
        heightDp: Float,
        photos: List<T>,
        aspectRatioOf: (T) -> Float,
        varietySeed: Int = 0
    ): Pair<ResolvedTilePattern, List<T>> {
        val aspects = photos.map(aspectRatioOf)
        val pattern = resolveTilePattern(
            kind = kind,
            collageLayout = collageLayout,
            widthDp = widthDp,
            heightDp = heightDp,
            photoCount = photos.size,
            photoAspectRatios = aspects,
            varietySeed = varietySeed
        )
        if (pattern == ResolvedTilePattern.SINGLE || photos.size <= 1) {
            return pattern to photos.take(pattern.maxSlots)
        }
        val boxes = computeTileBoxes(pattern, widthDp.coerceAtLeast(48f), heightDp.coerceAtLeast(48f), 0f)
        val slotAspects = boxes.map { it.width / it.height.coerceAtLeast(1f) }
        val (ordered, _) = WidgetPhotoDiversity.assignToBestSlots(
            items = photos.take(pattern.maxSlots),
            itemAspectRatio = aspectRatioOf,
            slotAspectRatios = slotAspects
        )
        return pattern to ordered
    }

    private fun bestTwoPhotoPattern(
        widthDp: Float,
        heightDp: Float,
        photoAspects: List<Float>
    ): ResolvedTilePattern {
        val aspect = widthDp / heightDp
        val candidates = when {
            aspect >= 1.9f -> listOf(ResolvedTilePattern.STRIP_HORIZONTAL_2)
            aspect <= 0.6f -> listOf(ResolvedTilePattern.STRIP_VERTICAL_2)
            else -> listOf(ResolvedTilePattern.STRIP_HORIZONTAL_2, ResolvedTilePattern.STRIP_VERTICAL_2)
        }
        if (photoAspects.size < 2) {
            return if (aspect < 0.85f) ResolvedTilePattern.STRIP_VERTICAL_2 else ResolvedTilePattern.STRIP_HORIZONTAL_2
        }
        return scorePatternsByPhotoFit(candidates, widthDp, heightDp, photoAspects)
    }

    private fun bestThreePhotoPattern(
        widthDp: Float,
        heightDp: Float,
        photoAspects: List<Float>,
        varietySeed: Int
    ): ResolvedTilePattern {
        val aspect = widthDp / heightDp
        val primaryHero = if (varietySeed % 3 == 1) {
            ResolvedTilePattern.HERO_RIGHT_2_LEFT
        } else {
            ResolvedTilePattern.HERO_LEFT_2_RIGHT
        }
        val candidates = when {
            aspect >= 2.25f -> listOf(ResolvedTilePattern.STRIP_HORIZONTAL_3)
            aspect >= 2.0f -> listOf(ResolvedTilePattern.STRIP_HORIZONTAL_3, primaryHero)
            aspect < 0.78f -> if (widthDp < 115f) {
                listOf(ResolvedTilePattern.STRIP_VERTICAL_3, ResolvedTilePattern.HERO_TOP_2_BOTTOM)
            } else {
                listOf(ResolvedTilePattern.HERO_TOP_2_BOTTOM, ResolvedTilePattern.STRIP_VERTICAL_3)
            }
            aspect >= 1.15f -> listOf(primaryHero, ResolvedTilePattern.STRIP_HORIZONTAL_3, ResolvedTilePattern.HERO_TOP_2_BOTTOM)
            else -> listOf(ResolvedTilePattern.HERO_TOP_2_BOTTOM, primaryHero, ResolvedTilePattern.STRIP_HORIZONTAL_3)
        }
        if (photoAspects.size < 3) return candidates.first()
        return scorePatternsByPhotoFit(candidates, widthDp, heightDp, photoAspects)
    }

    private fun bestFourPhotoPattern(
        widthDp: Float,
        heightDp: Float,
        photoAspects: List<Float>
    ): ResolvedTilePattern {
        val aspect = widthDp / heightDp
        val candidates = when {
            aspect >= 2.25f -> listOf(ResolvedTilePattern.STRIP_HORIZONTAL_4)
            aspect >= 2.15f -> listOf(ResolvedTilePattern.STRIP_HORIZONTAL_4, ResolvedTilePattern.BENTO_4_WIDE)
            aspect < 0.82f -> listOf(ResolvedTilePattern.BENTO_4_TOP_HERO, ResolvedTilePattern.GRID_2X2)
            aspect >= 1.22f -> buildList {
                if (widthDp >= 250f && heightDp >= 135f) add(ResolvedTilePattern.BENTO_4_WIDE)
                add(ResolvedTilePattern.BENTO_4_TOP_HERO)
                add(ResolvedTilePattern.GRID_2X2)
                if (widthDp >= 270f) add(ResolvedTilePattern.STRIP_HORIZONTAL_4)
            }
            else -> listOf(ResolvedTilePattern.GRID_2X2, ResolvedTilePattern.BENTO_4_TOP_HERO, ResolvedTilePattern.BENTO_4_WIDE)
        }
        if (photoAspects.size < 4) return candidates.first()
        return scorePatternsByPhotoFit(candidates, widthDp, heightDp, photoAspects)
    }

    private fun bestFivePhotoPattern(
        widthDp: Float,
        heightDp: Float,
        photoAspects: List<Float>
    ): ResolvedTilePattern {
        val aspect = widthDp / heightDp
        val candidates = if (aspect >= 1.15f) {
            listOf(ResolvedTilePattern.MOSAIC_5_HERO_LEFT, ResolvedTilePattern.MOSAIC_5_HERO_TOP)
        } else {
            listOf(ResolvedTilePattern.MOSAIC_5_HERO_TOP, ResolvedTilePattern.MOSAIC_5_HERO_LEFT)
        }
        if (photoAspects.size < 5) return candidates.first()
        return scorePatternsByPhotoFit(candidates, widthDp, heightDp, photoAspects)
    }

    private fun bestSixPhotoPattern(
        widthDp: Float,
        heightDp: Float,
        photoAspects: List<Float>
    ): ResolvedTilePattern {
        val aspect = widthDp / heightDp
        val candidates = when {
            aspect >= 1.15f -> listOf(
                ResolvedTilePattern.GRID_3X2,
                ResolvedTilePattern.MOSAIC_5_HERO_LEFT,
                ResolvedTilePattern.MOSAIC_5_HERO_TOP
            )
            aspect < 0.85f -> listOf(
                ResolvedTilePattern.GRID_2X3,
                ResolvedTilePattern.MOSAIC_5_HERO_TOP
            )
            else -> listOf(
                ResolvedTilePattern.GRID_3X2,
                ResolvedTilePattern.GRID_2X3,
                ResolvedTilePattern.MOSAIC_5_HERO_TOP
            )
        }
        if (photoAspects.size < 6) return candidates.first()
        return scorePatternsByPhotoFit(candidates, widthDp, heightDp, photoAspects)
    }

    private fun scorePatternsByPhotoFit(
        candidates: List<ResolvedTilePattern>,
        widthDp: Float,
        heightDp: Float,
        photoAspects: List<Float>
    ): ResolvedTilePattern {
        if (candidates.size == 1) return candidates.first()
        var bestPattern = candidates.first()
        var bestScore = Float.POSITIVE_INFINITY

        for (pattern in candidates) {
            val tiles = computeTileBoxes(pattern, widthDp, heightDp, 0f)
            val minTileEdge = tiles.minOf { min(it.width, it.height) }
            // Avoid patterns that squeeze tiles below a clean readable threshold
            val crampPenalty = if (minTileEdge < 42f) (42f - minTileEdge) * 0.04f else 0f
            val slotAspects = tiles.map { it.width / it.height.coerceAtLeast(1f) }
            val (_, avgErr) = WidgetPhotoDiversity.assignToBestSlots(
                items = photoAspects.take(pattern.maxSlots),
                itemAspectRatio = { it },
                slotAspectRatios = slotAspects
            )
            // Slight bonus when showcasing all 6 distinct photos cleanly on a large widget
            val richnessBonus = if (pattern.maxSlots == 6) -0.04f else 0f
            val total = avgErr + crampPenalty + richnessBonus
            if (total < bestScore) {
                bestScore = total
                bestPattern = pattern
            }
        }
        return bestPattern
    }

    /**
     * Renders a complete Xiaomi HyperOS glass-styled widget bitmap for 1 or more photos.
     */
    fun renderWidgetBitmap(
        photos: List<Bitmap>,
        pattern: ResolvedTilePattern,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        settings: PhotoWidgetSettings,
        caption: WidgetCaptionData? = null,
        date: LocalDate = LocalDate.now(),
        reserveBottomRightButton: Boolean = false,
        reserveTopLeftButton: Boolean = false,
        overrideCornerRadiusDp: Float? = null
    ): Bitmap {
        val w = widthPx.coerceAtLeast(64)
        val h = heightPx.coerceAtLeast(64)
        val minEdge = min(w, h).toFloat()
        val targetRadiusDp = overrideCornerRadiusDp ?: settings.cornerStyle.radiusDp
        val maxOuterRadius = (minEdge * 0.42f).coerceAtLeast(4f)
        val minOuterRadius = min(10f * density, maxOuterRadius)
        val outerRadius = (targetRadiusDp * density).coerceIn(minOuterRadius, maxOuterRadius)
        val bounds = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val outerPath = buildContinuousSquirclePath(bounds, outerRadius)

        // 1. Render raw photo / multi-photo composite + interior effects onto a single working bitmap
        val contentBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val contentCanvas = Canvas(contentBitmap)

            val multiOuterInset = if (pattern != ResolvedTilePattern.SINGLE && photos.size > 1 &&
                settings.frameStyle == WidgetFrameStyle.FROSTED_FRAME
            ) {
                (minEdge * 0.042f).coerceIn(5.5f * density, 9.5f * density)
            } else {
                0f
            }

            if (photos.isEmpty()) {
                drawEmptyHyperOsBackdrop(contentCanvas, bounds)
            } else if (pattern == ResolvedTilePattern.SINGLE || photos.size == 1) {
                if (settings.frameStyle == WidgetFrameStyle.FROSTED_FRAME) {
                    drawFrostedFramedSingle(contentCanvas, bounds, photos.first(), outerRadius, density)
                } else {
                    drawCenterCropBitmap(contentCanvas, photos.first(), bounds)
                }
            } else {
                drawMultiPhotoComposite(
                    canvas = contentCanvas,
                    bounds = bounds,
                    photos = photos,
                    pattern = pattern,
                    outerRadius = outerRadius,
                    density = density,
                    extraOuterInset = multiOuterInset
                )
            }

            // Draw interior collage dividers with the EXACT same razor-thin clear glass rim as the outside border
            if (pattern != ResolvedTilePattern.SINGLE && photos.size > 1) {
                val tileBounds = RectF(
                    bounds.left + multiOuterInset,
                    bounds.top + multiOuterInset,
                    bounds.right - multiOuterInset,
                    bounds.bottom - multiOuterInset
                )
                drawCollageGlassDividers(
                    canvas = contentCanvas,
                    bounds = bounds,
                    tileBounds = tileBounds,
                    pattern = pattern,
                    outerRadius = outerRadius,
                    density = density,
                    frameStyle = settings.frameStyle
                )
            }

            // Subtle inner lighting accent (zero murky grey halo)
            when (settings.frameStyle) {
                WidgetFrameStyle.HYPER_GLASS -> {
                    drawHyperOsSubtleInnerSheen(
                        canvas = contentCanvas,
                        bounds = bounds,
                        outerRadius = outerRadius,
                        density = density
                    )
                }
                WidgetFrameStyle.FROSTED_FRAME -> {
                    drawFrostedFrameHighlight(
                        canvas = contentCanvas,
                        bounds = bounds,
                        outerRadius = outerRadius,
                        density = density
                    )
                }
                WidgetFrameStyle.CRYSTAL_RIM,
                WidgetFrameStyle.BORDERLESS -> Unit
            }

            // 2. Draw optional HyperOS frosted glass Date Badge (top-left)
            if (settings.showDateBadge && w >= (100f * density) && h >= (86f * density)) {
                drawHyperOsDateBadge(
                    canvas = contentCanvas,
                    backdrop = contentBitmap,
                    bounds = bounds,
                    date = date,
                    density = density,
                    shiftRightForButton = reserveTopLeftButton
                )
            }

            // 3. Draw optional HyperOS Caption overlay (bottom)
            if (settings.showCaption && caption != null && caption.title.isNotBlank() &&
                w >= (96f * density) && h >= (84f * density)
            ) {
                when (settings.captionStyle) {
                    WidgetCaptionStyle.GLASS_PILL -> drawGlassPillCaption(
                        canvas = contentCanvas,
                        backdrop = contentBitmap,
                        bounds = bounds,
                        caption = caption,
                        density = density,
                        reserveRightSpace = reserveBottomRightButton
                    )
                    WidgetCaptionStyle.EDITORIAL_FADE -> drawEditorialCaption(
                        canvas = contentCanvas,
                        bounds = bounds,
                        caption = caption,
                        density = density,
                        reserveRightSpace = reserveBottomRightButton
                    )
                }
            }

            // 4. Composite smoothly through the continuous-curvature squircle using an anti-aliased BitmapShader
            // (eliminates 3rd full-size maskBitmap allocation & avoids jagged clipPath edges)
            val outCanvas = Canvas(output)
            val squirclePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(contentBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            outCanvas.drawPath(outerPath, squirclePaint)

            // Final crisp outer dual-arc rim drawn right after squircle pass so it never gets clipped unevenly
            if (settings.frameStyle != WidgetFrameStyle.BORDERLESS) {
                drawDualArcSpecularBorder(
                    canvas = outCanvas,
                    bounds = bounds,
                    outerRadius = outerRadius,
                    density = density,
                    strong = settings.frameStyle == WidgetFrameStyle.HYPER_GLASS ||
                        settings.frameStyle == WidgetFrameStyle.FROSTED_FRAME
                )
            }
        } finally {
            contentBitmap.recycle()
        }
        return output
    }

    /**
     * Builds a G2-continuous squircle (superellipse-like continuous rounded rectangle) matching
     * Xiaomi HyperOS and modern iOS widget/icon corner curvature with zero tangent kink.
     */
    fun buildContinuousSquirclePath(rect: RectF, radius: Float, smoothness: Float = 0.62f): Path {
        val w = rect.width()
        val h = rect.height()
        val shoulderScale = 1f + 0.45f * smoothness.coerceIn(0f, 1f) // ~1.28 for 0.62
        val maxEffRadius = (min(w, h) * 0.495f) / shoulderScale
        val r = radius.coerceIn(0f, maxEffRadius)
        val p = r * shoulderScale
        val h1 = r * (0.74f * (shoulderScale / 1.28f))
        val d = r * 0.292893f
        val t45 = r * 0.21f
        val a = d + t45 // ~0.5029 * r
        val bOff = d - t45 // ~0.0829 * r

        val l = rect.left
        val t = rect.top
        val ri = rect.right
        val b = rect.bottom

        return Path().apply {
            // Start top edge after top-left continuous shoulder
            moveTo(l + p, t)
            lineTo(ri - p, t)
            // Top-right continuous corner (exact 45-deg C1/G2 symmetric midpoint)
            cubicTo(ri - h1, t, ri - a, t + bOff, ri - d, t + d)
            cubicTo(ri - bOff, t + a, ri, t + h1, ri, t + p)
            lineTo(ri, b - p)
            // Bottom-right continuous corner
            cubicTo(ri, b - h1, ri - bOff, b - a, ri - d, b - d)
            cubicTo(ri - a, b - bOff, ri - h1, b, ri - p, b)
            lineTo(l + p, b)
            // Bottom-left continuous corner
            cubicTo(l + h1, b, l + a, b - bOff, l + d, b - d)
            cubicTo(l + bOff, b - a, l, b - h1, l, b - p)
            lineTo(l, t + p)
            // Top-left continuous corner
            cubicTo(l, t + h1, l + bOff, t + a, l + d, t + d)
            cubicTo(l + a, t + bOff, l + h1, t, l + p, t)
            close()
        }
    }

    /**
     * Pure-Kotlin tile box geometry (JVM-testable without android.graphics.RectF stubs).
     */
    fun computeTileBoxes(
        pattern: ResolvedTilePattern,
        width: Float,
        height: Float,
        gapPx: Float,
        leftOffset: Float = 0f,
        topOffset: Float = 0f
    ): List<TileBox> {
        val l = leftOffset
        val t = topOffset
        val r = l + width
        val b = t + height
        val w = width
        val h = height

        return when (pattern) {
            ResolvedTilePattern.SINGLE -> listOf(TileBox(l, t, r, b))
            ResolvedTilePattern.HERO_LEFT_2_RIGHT -> {
                val heroW = (w - gapPx) * 0.58f
                val rightL = l + heroW + gapPx
                val halfH = (h - gapPx) * 0.5f
                listOf(
                    TileBox(l, t, l + heroW, b),
                    TileBox(rightL, t, r, t + halfH),
                    TileBox(rightL, t + halfH + gapPx, r, b)
                )
            }
            ResolvedTilePattern.HERO_RIGHT_2_LEFT -> {
                val leftW = (w - gapPx) * 0.42f
                val heroL = l + leftW + gapPx
                val halfH = (h - gapPx) * 0.5f
                listOf(
                    TileBox(heroL, t, r, b),
                    TileBox(l, t, l + leftW, t + halfH),
                    TileBox(l, t + halfH + gapPx, l + leftW, b)
                )
            }
            ResolvedTilePattern.HERO_TOP_2_BOTTOM -> {
                val heroH = (h - gapPx) * 0.58f
                val botT = t + heroH + gapPx
                val halfW = (w - gapPx) * 0.5f
                listOf(
                    TileBox(l, t, r, t + heroH),
                    TileBox(l, botT, l + halfW, b),
                    TileBox(l + halfW + gapPx, botT, r, b)
                )
            }
            ResolvedTilePattern.GRID_2X2 -> {
                val halfW = (w - gapPx) * 0.5f
                val halfH = (h - gapPx) * 0.5f
                listOf(
                    TileBox(l, t, l + halfW, t + halfH),
                    TileBox(l + halfW + gapPx, t, r, t + halfH),
                    TileBox(l, t + halfH + gapPx, l + halfW, b),
                    TileBox(l + halfW + gapPx, t + halfH + gapPx, r, b)
                )
            }
            ResolvedTilePattern.BENTO_4_WIDE -> {
                val heroW = (w - gapPx) * 0.52f
                val rightL = l + heroW + gapPx
                val rightW = r - rightL
                val topH = (h - gapPx) * 0.52f
                val botT = t + topH + gapPx
                val miniW = (rightW - gapPx) * 0.5f
                listOf(
                    TileBox(l, t, l + heroW, b),
                    TileBox(rightL, t, r, t + topH),
                    TileBox(rightL, botT, rightL + miniW, b),
                    TileBox(rightL + miniW + gapPx, botT, r, b)
                )
            }
            ResolvedTilePattern.BENTO_4_TOP_HERO -> {
                val heroH = (h - gapPx) * 0.50f
                val botT = t + heroH + gapPx
                val cellW = (w - gapPx * 2f) / 3f
                listOf(
                    TileBox(l, t, r, t + heroH),
                    TileBox(l, botT, l + cellW, b),
                    TileBox(l + cellW + gapPx, botT, l + cellW * 2f + gapPx, b),
                    TileBox(l + cellW * 2f + gapPx * 2f, botT, r, b)
                )
            }
            ResolvedTilePattern.MOSAIC_5_HERO_LEFT -> {
                val heroW = (w - gapPx) * 0.45f
                val rightL = l + heroW + gapPx
                val rightW = r - rightL
                val halfRW = (rightW - gapPx) * 0.5f
                val halfH = (h - gapPx) * 0.5f
                listOf(
                    TileBox(l, t, l + heroW, b),
                    TileBox(rightL, t, rightL + halfRW, t + halfH),
                    TileBox(rightL + halfRW + gapPx, t, r, t + halfH),
                    TileBox(rightL, t + halfH + gapPx, rightL + halfRW, b),
                    TileBox(rightL + halfRW + gapPx, t + halfH + gapPx, r, b)
                )
            }
            ResolvedTilePattern.MOSAIC_5_HERO_TOP -> {
                val topH = (h - gapPx) * 0.54f
                val botT = t + topH + gapPx
                val halfW = (w - gapPx) * 0.5f
                val thirdW = (w - gapPx * 2f) / 3f
                listOf(
                    TileBox(l, t, l + halfW, t + topH),
                    TileBox(l + halfW + gapPx, t, r, t + topH),
                    TileBox(l, botT, l + thirdW, b),
                    TileBox(l + thirdW + gapPx, botT, l + thirdW * 2f + gapPx, b),
                    TileBox(l + thirdW * 2f + gapPx * 2f, botT, r, b)
                )
            }
            ResolvedTilePattern.GRID_3X2 -> {
                val colW = (w - gapPx * 2f) / 3f
                val rowH = (h - gapPx) * 0.5f
                val botT = t + rowH + gapPx
                listOf(
                    TileBox(l, t, l + colW, t + rowH),
                    TileBox(l + colW + gapPx, t, l + colW * 2f + gapPx, t + rowH),
                    TileBox(l + colW * 2f + gapPx * 2f, t, r, t + rowH),
                    TileBox(l, botT, l + colW, b),
                    TileBox(l + colW + gapPx, botT, l + colW * 2f + gapPx, b),
                    TileBox(l + colW * 2f + gapPx * 2f, botT, r, b)
                )
            }
            ResolvedTilePattern.GRID_2X3 -> {
                val colW = (w - gapPx) * 0.5f
                val rowH = (h - gapPx * 2f) / 3f
                val rightL = l + colW + gapPx
                val row2T = t + rowH + gapPx
                val row3T = t + rowH * 2f + gapPx * 2f
                listOf(
                    TileBox(l, t, l + colW, t + rowH),
                    TileBox(rightL, t, r, t + rowH),
                    TileBox(l, row2T, l + colW, row2T + rowH),
                    TileBox(rightL, row2T, r, row2T + rowH),
                    TileBox(l, row3T, l + colW, b),
                    TileBox(rightL, row3T, r, b)
                )
            }
            ResolvedTilePattern.STRIP_HORIZONTAL_2 -> splitHorizontalBoxes(l, t, r, b, 2, gapPx)
            ResolvedTilePattern.STRIP_HORIZONTAL_3 -> splitHorizontalBoxes(l, t, r, b, 3, gapPx)
            ResolvedTilePattern.STRIP_HORIZONTAL_4 -> splitHorizontalBoxes(l, t, r, b, 4, gapPx)
            ResolvedTilePattern.STRIP_VERTICAL_2 -> splitVerticalBoxes(l, t, r, b, 2, gapPx)
            ResolvedTilePattern.STRIP_VERTICAL_3 -> splitVerticalBoxes(l, t, r, b, 3, gapPx)
        }
    }

    /**
     * Computes exact normalized tile rectangles (within [insetRect]) for each multi-photo pattern.
     */
    fun computeTileRects(
        pattern: ResolvedTilePattern,
        insetRect: RectF,
        gapPx: Float
    ): List<RectF> = computeTileBoxes(
        pattern = pattern,
        width = insetRect.width(),
        height = insetRect.height(),
        gapPx = gapPx,
        leftOffset = insetRect.left,
        topOffset = insetRect.top
    ).map { RectF(it.left, it.top, it.right, it.bottom) }

    private fun splitHorizontalBoxes(l: Float, t: Float, r: Float, b: Float, count: Int, gapPx: Float): List<TileBox> {
        val cellW = ((r - l) - gapPx * (count - 1)) / count
        return (0 until count).map { idx ->
            val left = l + idx * (cellW + gapPx)
            TileBox(left, t, if (idx == count - 1) r else left + cellW, b)
        }
    }

    private fun splitVerticalBoxes(l: Float, t: Float, r: Float, b: Float, count: Int, gapPx: Float): List<TileBox> {
        val cellH = ((b - t) - gapPx * (count - 1)) / count
        return (0 until count).map { idx ->
            val top = t + idx * (cellH + gapPx)
            TileBox(l, top, r, if (idx == count - 1) b else top + cellH)
        }
    }

    private fun drawFrostedFramedSingle(
        canvas: Canvas,
        bounds: RectF,
        photo: Bitmap,
        outerRadius: Float,
        density: Float
    ) {
        val minEdge = min(bounds.width(), bounds.height())
        val frameInset = (minEdge * 0.056f).coerceIn(6.5f * density, 11.5f * density)

        // Blurred, vivid photo-tinted frosted glass backdrop
        val frosted = createBlurredGlassBitmap(
            source = photo,
            targetW = bounds.width().roundToInt(),
            targetH = bounds.height().roundToInt(),
            blurRadius = 11,
            saturation = 1.24f,
            brightnessLift = 20f
        )
        canvas.drawBitmap(frosted, 0f, 0f, null)
        frosted.recycle()

        // Frosted milk glass wash over frame
        val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, bounds.width(), bounds.height(),
                intArrayOf(
                    Color.argb(74, 255, 255, 255),
                    Color.argb(30, 232, 242, 255),
                    Color.argb(46, 18, 28, 44)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(bounds, washPaint)

        // Crisp inset photo with mathematically concentric inner squircle corners
        val innerRect = RectF(
            bounds.left + frameInset,
            bounds.top + frameInset,
            bounds.right - frameInset,
            bounds.bottom - frameInset
        )
        val innerRadius = (outerRadius - frameInset * 0.72f).coerceAtLeast(8f * density)
        val innerPath = buildContinuousSquirclePath(innerRect, innerRadius)

        // Soft recessed shadow behind inset photo
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(56, 0, 0, 0)
            maskFilter = BlurMaskFilter(3.5f * density, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(innerPath, shadowPaint)

        val save = canvas.save()
        canvas.clipPath(innerPath)
        drawCenterCropBitmap(canvas, photo, innerRect)
        canvas.restoreToCount(save)

        // Subtle inner groove specular lip
        val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * density
            shader = LinearGradient(
                innerRect.left, innerRect.top, innerRect.right, innerRect.bottom,
                intArrayOf(
                    Color.argb(120, 255, 255, 255),
                    Color.argb(36, 255, 255, 255),
                    Color.argb(95, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(innerPath, innerRimPaint)
    }

    private fun drawMultiPhotoComposite(
        canvas: Canvas,
        bounds: RectF,
        photos: List<Bitmap>,
        pattern: ResolvedTilePattern,
        outerRadius: Float,
        density: Float,
        extraOuterInset: Float
    ) {
        val isFullBleed = extraOuterInset <= 0.5f

        if (!isFullBleed) {
            // Frosted glass outer frame derived from the primary photo when Frosted Frame is chosen
            val mat = createBlurredGlassBitmap(
                source = photos.first(),
                targetW = bounds.width().roundToInt(),
                targetH = bounds.height().roundToInt(),
                blurRadius = 10,
                saturation = 1.15f,
                brightnessLift = 8f
            )
            canvas.drawBitmap(mat, 0f, 0f, null)
            mat.recycle()

            val matTint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, bounds.width(), bounds.height(),
                    intArrayOf(
                        Color.argb(56, 255, 255, 255),
                        Color.argb(28, 224, 236, 255),
                        Color.argb(46, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(bounds, matTint)
        }

        val insetRect = RectF(
            bounds.left + extraOuterInset,
            bounds.top + extraOuterInset,
            bounds.right - extraOuterInset,
            bounds.bottom - extraOuterInset
        )
        // Photos meet edge-to-edge with zero bulky gap; the unified 1.15dp clear glass divider is stroked cleanly on top
        val tileRects = computeTileRects(pattern, insetRect, gapPx = 0f)

        val compositeSave = canvas.save()
        if (!isFullBleed) {
            val innerFrameRadius = (outerRadius - extraOuterInset * 0.78f).coerceAtLeast(8f * density)
            canvas.clipPath(buildContinuousSquirclePath(insetRect, innerFrameRadius))
        }

        tileRects.forEachIndexed { index, rect ->
            val bitmap = photos.getOrNull(index) ?: photos.last()
            val save = canvas.save()
            canvas.clipRect(rect)
            drawCenterCropBitmap(canvas, bitmap, rect)
            canvas.restoreToCount(save)
        }
        canvas.restoreToCount(compositeSave)
    }

    /**
     * Draws interior collage dividers using the EXACT same thickness (1.15dp) and translucent
     * specular glass gradient as the outer widget rim, clipped neatly inside the outer rim
     * so T-junctions merge seamlessly with zero double-bright overlap.
     */
    private fun drawCollageGlassDividers(
        canvas: Canvas,
        bounds: RectF,
        tileBounds: RectF,
        pattern: ResolvedTilePattern,
        outerRadius: Float,
        density: Float,
        frameStyle: WidgetFrameStyle
    ) {
        val seams = computeInteriorSeamLines(pattern, tileBounds)
        if (seams.isEmpty()) return

        val strong = frameStyle == WidgetFrameStyle.HYPER_GLASS || frameStyle == WidgetFrameStyle.FROSTED_FRAME
        val rimWidth = when (frameStyle) {
            WidgetFrameStyle.HYPER_GLASS, WidgetFrameStyle.FROSTED_FRAME -> 1.15f * density
            WidgetFrameStyle.CRYSTAL_RIM, WidgetFrameStyle.BORDERLESS -> 0.85f * density
        }

        // Clip strictly inside the outer border stroke so dividers butt cleanly into the outer glass rim
        val innerClipInset = if (frameStyle == WidgetFrameStyle.BORDERLESS) 0f else rimWidth + 0.35f * density
        val innerClipRect = RectF(
            bounds.left + innerClipInset,
            bounds.top + innerClipInset,
            bounds.right - innerClipInset,
            bounds.bottom - innerClipInset
        )
        val innerClipPath = buildContinuousSquirclePath(
            innerClipRect,
            (outerRadius - innerClipInset * 0.86f).coerceAtLeast(4f * density)
        )

        val dividerPaint = createGlassRimPaint(bounds, rimWidth, strong = strong)
        val seamPath = Path().apply {
            for (line in seams) {
                moveTo(line[0], line[1])
                lineTo(line[2], line[3])
            }
        }
        val save = canvas.save()
        canvas.clipPath(innerClipPath)
        canvas.drawPath(seamPath, dividerPaint)
        canvas.restoreToCount(save)
    }

    private fun computeInteriorSeamLines(pattern: ResolvedTilePattern, rect: RectF): List<FloatArray> {
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        val w = rect.width()
        val h = rect.height()
        return when (pattern) {
            ResolvedTilePattern.SINGLE -> emptyList()
            ResolvedTilePattern.HERO_LEFT_2_RIGHT -> {
                val splitX = l + w * 0.58f
                val midY = t + h * 0.5f
                listOf(
                    floatArrayOf(splitX, t, splitX, b),
                    floatArrayOf(splitX, midY, r, midY)
                )
            }
            ResolvedTilePattern.HERO_RIGHT_2_LEFT -> {
                val splitX = l + w * 0.42f
                val midY = t + h * 0.5f
                listOf(
                    floatArrayOf(splitX, t, splitX, b),
                    floatArrayOf(l, midY, splitX, midY)
                )
            }
            ResolvedTilePattern.HERO_TOP_2_BOTTOM -> {
                val splitY = t + h * 0.58f
                val midX = l + w * 0.5f
                listOf(
                    floatArrayOf(l, splitY, r, splitY),
                    floatArrayOf(midX, splitY, midX, b)
                )
            }
            ResolvedTilePattern.GRID_2X2 -> {
                val midX = l + w * 0.5f
                val midY = t + h * 0.5f
                listOf(
                    floatArrayOf(midX, t, midX, b),
                    floatArrayOf(l, midY, r, midY)
                )
            }
            ResolvedTilePattern.BENTO_4_WIDE -> {
                val splitX = l + w * 0.52f
                val splitY = t + h * 0.52f
                val rightMidX = splitX + (r - splitX) * 0.5f
                listOf(
                    floatArrayOf(splitX, t, splitX, b),
                    floatArrayOf(splitX, splitY, r, splitY),
                    floatArrayOf(rightMidX, splitY, rightMidX, b)
                )
            }
            ResolvedTilePattern.BENTO_4_TOP_HERO -> {
                val splitY = t + h * 0.50f
                val x1 = l + w / 3f
                val x2 = l + w * 2f / 3f
                listOf(
                    floatArrayOf(l, splitY, r, splitY),
                    floatArrayOf(x1, splitY, x1, b),
                    floatArrayOf(x2, splitY, x2, b)
                )
            }
            ResolvedTilePattern.MOSAIC_5_HERO_LEFT -> {
                val splitX = l + w * 0.45f
                val rightMidX = splitX + (r - splitX) * 0.5f
                val midY = t + h * 0.5f
                listOf(
                    floatArrayOf(splitX, t, splitX, b),
                    floatArrayOf(splitX, midY, r, midY),
                    floatArrayOf(rightMidX, t, rightMidX, b)
                )
            }
            ResolvedTilePattern.MOSAIC_5_HERO_TOP -> {
                val splitY = t + h * 0.54f
                val topMidX = l + w * 0.5f
                val botX1 = l + w / 3f
                val botX2 = l + w * 2f / 3f
                listOf(
                    floatArrayOf(l, splitY, r, splitY),
                    floatArrayOf(topMidX, t, topMidX, splitY),
                    floatArrayOf(botX1, splitY, botX1, b),
                    floatArrayOf(botX2, splitY, botX2, b)
                )
            }
            ResolvedTilePattern.GRID_3X2 -> {
                val x1 = l + w / 3f
                val x2 = l + w * 2f / 3f
                val midY = t + h * 0.5f
                listOf(
                    floatArrayOf(x1, t, x1, b),
                    floatArrayOf(x2, t, x2, b),
                    floatArrayOf(l, midY, r, midY)
                )
            }
            ResolvedTilePattern.GRID_2X3 -> {
                val midX = l + w * 0.5f
                val y1 = t + h / 3f
                val y2 = t + h * 2f / 3f
                listOf(
                    floatArrayOf(midX, t, midX, b),
                    floatArrayOf(l, y1, r, y1),
                    floatArrayOf(l, y2, r, y2)
                )
            }
            ResolvedTilePattern.STRIP_HORIZONTAL_2 -> verticalSplits(rect, 2)
            ResolvedTilePattern.STRIP_HORIZONTAL_3 -> verticalSplits(rect, 3)
            ResolvedTilePattern.STRIP_HORIZONTAL_4 -> verticalSplits(rect, 4)
            ResolvedTilePattern.STRIP_VERTICAL_2 -> horizontalSplits(rect, 2)
            ResolvedTilePattern.STRIP_VERTICAL_3 -> horizontalSplits(rect, 3)
        }
    }

    private fun verticalSplits(rect: RectF, count: Int): List<FloatArray> =
        (1 until count).map { i ->
            val x = rect.left + rect.width() * (i.toFloat() / count)
            floatArrayOf(x, rect.top, x, rect.bottom)
        }

    private fun horizontalSplits(rect: RectF, count: Int): List<FloatArray> =
        (1 until count).map { i ->
            val y = rect.top + rect.height() * (i.toFloat() / count)
            floatArrayOf(rect.left, y, rect.right, y)
        }

    private fun createGlassRimPaint(bounds: RectF, strokeWidthPx: Float, strong: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            shader = LinearGradient(
                bounds.left, bounds.top, bounds.right, bounds.bottom,
                intArrayOf(
                    Color.argb(if (strong) 128 else 78, 255, 255, 255),
                    Color.argb(if (strong) 78 else 46, 240, 248, 255),
                    Color.argb(if (strong) 48 else 28, 255, 255, 255),
                    Color.argb(if (strong) 72 else 44, 236, 246, 255),
                    Color.argb(if (strong) 104 else 64, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.24f, 0.50f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
        }

    /**
     * Exact Xiaomi HyperOS ultra-clean glass edge micro-sheen:
     * Keeps the photo 100% razor-sharp edge-to-edge with only a ~1.5dp feather-soft specular glint
     * catching the outer curved glass lip (zero blurry grey halo or dark inner crease).
     */
    private fun drawHyperOsSubtleInnerSheen(
        canvas: Canvas,
        bounds: RectF,
        outerRadius: Float,
        density: Float
    ) {
        val sheenInset = 1.25f * density
        val sheenRect = RectF(
            bounds.left + sheenInset,
            bounds.top + sheenInset,
            bounds.right - sheenInset,
            bounds.bottom - sheenInset
        )
        val sheenPath = buildContinuousSquirclePath(
            sheenRect,
            (outerRadius - sheenInset * 0.85f).coerceAtLeast(6f * density)
        )
        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            maskFilter = BlurMaskFilter(1.1f * density, BlurMaskFilter.Blur.NORMAL)
            shader = LinearGradient(
                0f, 0f, bounds.width(), bounds.height(),
                intArrayOf(
                    Color.argb(46, 255, 255, 255),
                    Color.argb(12, 240, 248, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(14, 240, 248, 255),
                    Color.argb(30, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.24f, 0.50f, 0.76f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(sheenPath, sheenPaint)
    }

    private fun drawFrostedFrameHighlight(
        canvas: Canvas,
        bounds: RectF,
        outerRadius: Float,
        density: Float
    ) {
        val sheenInset = 2.0f * density
        val sheenRect = RectF(
            bounds.left + sheenInset,
            bounds.top + sheenInset,
            bounds.right - sheenInset,
            bounds.bottom - sheenInset
        )
        val sheenPath = buildContinuousSquirclePath(
            sheenRect,
            (outerRadius - sheenInset * 0.8f).coerceAtLeast(6f * density)
        )
        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            maskFilter = BlurMaskFilter(1.8f * density, BlurMaskFilter.Blur.NORMAL)
            shader = LinearGradient(
                0f, 0f, bounds.width(), bounds.height(),
                intArrayOf(
                    Color.argb(62, 255, 255, 255),
                    Color.argb(12, 255, 255, 255),
                    Color.argb(42, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(sheenPath, sheenPaint)
    }

    /**
     * Pixel-matched Xiaomi HyperOS crystalline translucent glass rim:
     * Razor-thin ~1.15dp specular edge catching light softly at top-left (~48% white),
     * whisper-translucent along neutral mid-edges (~14%), and delicate opposing bounce at bottom-right (~36%).
     */
    private fun drawDualArcSpecularBorder(
        canvas: Canvas,
        bounds: RectF,
        outerRadius: Float,
        density: Float,
        strong: Boolean
    ) {
        // Ultra-subtle outer contrast silhouette for white wallpapers (virtually invisible on dark)
        val contrastStroke = 0.45f * density
        val outerStrokeRect = RectF(
            bounds.left + contrastStroke * 0.5f,
            bounds.top + contrastStroke * 0.5f,
            bounds.right - contrastStroke * 0.5f,
            bounds.bottom - contrastStroke * 0.5f
        )
        val contrastPath = buildContinuousSquirclePath(
            outerStrokeRect,
            (outerRadius - contrastStroke * 0.4f).coerceAtLeast(4f * density)
        )
        val contrastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = contrastStroke
            color = Color.argb(26, 0, 0, 0)
        }
        canvas.drawPath(contrastPath, contrastPaint)

        // Exact Xiaomi HyperOS razor-thin translucent specular glass rim (shared with interior dividers!)
        val rimWidth = (if (strong) 1.15f else 0.85f) * density
        val rimInset = rimWidth * 0.5f + contrastStroke * 0.35f
        val rimRect = RectF(
            bounds.left + rimInset,
            bounds.top + rimInset,
            bounds.right - rimInset,
            bounds.bottom - rimInset
        )
        val rimPath = buildContinuousSquirclePath(
            rimRect,
            (outerRadius - rimInset * 0.86f).coerceAtLeast(4f * density)
        )
        val rimPaint = createGlassRimPaint(bounds, rimWidth, strong = strong)
        canvas.drawPath(rimPath, rimPaint)
    }

    private fun drawHyperOsDateBadge(
        canvas: Canvas,
        backdrop: Bitmap,
        bounds: RectF,
        date: LocalDate,
        density: Float,
        shiftRightForButton: Boolean
    ) {
        val pad = (11f * density).coerceAtLeast(8f)
        val leftStart = if (shiftRightForButton) pad + 38f * density else pad
        val weekday = WeekdayFormatter.format(date).uppercase(Locale.getDefault())
        val dayNum = date.dayOfMonth.toString()
        val month = MonthFormatter.format(date).uppercase(Locale.getDefault())

        val compact = bounds.width() < 155f * density || bounds.height() < 130f * density
        val daySize = (if (compact) 20f else 24f) * density
        val metaSize = (if (compact) 9.5f else 10.5f) * density

        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = daySize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(2f * density, 0f, 1f * density, Color.argb(80, 0, 0, 0))
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(226, 244, 248, 255)
            textSize = metaSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
        }

        val dayWidth = dayPaint.measureText(dayNum)
        val topMeta = "$weekday · $month"
        val metaWidth = metaPaint.measureText(topMeta)
        val hPad = (if (compact) 10f else 12f) * density
        val vPad = (if (compact) 7f else 8.5f) * density
        val cardW = max(dayWidth, metaWidth) + hPad * 2f
        val cardH = metaSize + daySize + vPad * 2f + 2f * density
        val cardRect = RectF(leftStart, pad, (leftStart + cardW).coerceAtMost(bounds.right - pad), pad + cardH)
        val cardRadius = (14f * density).coerceAtMost(cardRect.height() * 0.42f)

        drawBackdropFrostedCapsule(canvas, backdrop, cardRect, cardRadius, density, darkTintAlpha = 86)

        val metaY = cardRect.top + vPad + metaSize * 0.86f
        val dayY = metaY + daySize * 0.94f + 2f * density
        canvas.drawText(topMeta, cardRect.left + hPad, metaY, metaPaint)
        canvas.drawText(dayNum, cardRect.left + hPad, dayY, dayPaint)
    }

    private fun drawGlassPillCaption(
        canvas: Canvas,
        backdrop: Bitmap,
        bounds: RectF,
        caption: WidgetCaptionData,
        density: Float,
        reserveRightSpace: Boolean
    ) {
        val margin = 11f * density
        val rightMargin = if (reserveRightSpace) margin + 38f * density else margin
        val maxPillW = (bounds.width() - margin - rightMargin).coerceAtLeast(60f * density)
        val compact = bounds.height() < 125f * density || bounds.width() < 150f * density

        val titleSize = (if (compact) 11.5f else 13f) * density
        val subSize = (if (compact) 9.5f else 10.5f) * density

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = titleSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 232, 240, 250)
            textSize = subSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val hPad = (if (compact) 10f else 12.5f) * density
        val vPad = (if (compact) 6.5f else 8f) * density
        val maxTextW = (maxPillW - hPad * 2f).coerceAtLeast(24f * density)
        val titleText = ellipsizeText(caption.title, titlePaint, maxTextW)
        val rawSub = caption.subtitle?.takeIf { it.isNotBlank() }
        val subText = if (rawSub != null && !compact) ellipsizeText(rawSub, subPaint, maxTextW) else null

        val contentW = max(
            titlePaint.measureText(titleText),
            subText?.let { subPaint.measureText(it) } ?: 0f
        )
        val pillW = (contentW + hPad * 2f).coerceAtMost(maxPillW)
        val pillH = vPad * 2f + titleSize + if (subText != null) subSize + 3.5f * density else 1f * density

        val pillRect = RectF(
            bounds.left + margin,
            bounds.bottom - margin - pillH,
            bounds.left + margin + pillW,
            bounds.bottom - margin
        )
        val pillRadius = (if (subText != null) 15f else 18f) * density

        drawBackdropFrostedCapsule(
            canvas = canvas,
            backdrop = backdrop,
            rect = pillRect,
            radius = pillRadius.coerceAtMost(pillRect.height() * 0.5f),
            density = density,
            darkTintAlpha = 98
        )

        val titleY = pillRect.top + vPad + titleSize * 0.86f
        canvas.drawText(titleText, pillRect.left + hPad, titleY, titlePaint)
        if (subText != null) {
            val subY = titleY + subSize + 2.5f * density
            canvas.drawText(subText, pillRect.left + hPad, subY, subPaint)
        }
    }

    private fun drawEditorialCaption(
        canvas: Canvas,
        bounds: RectF,
        caption: WidgetCaptionData,
        density: Float,
        reserveRightSpace: Boolean
    ) {
        val compact = bounds.height() < 125f * density
        val scrimH = (bounds.height() * 0.38f).coerceIn(48f * density, 108f * density)
        val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, bounds.bottom - scrimH, 0f, bounds.bottom,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(54, 6, 10, 18),
                    Color.argb(148, 6, 10, 18)
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(bounds.left, bounds.bottom - scrimH, bounds.right, bounds.bottom, scrimPaint)

        val margin = 13f * density
        val rightMargin = if (reserveRightSpace) margin + 38f * density else margin
        val maxTextW = (bounds.width() - margin - rightMargin).coerceAtLeast(36f * density)

        val titleSize = (if (compact) 13f else 15f) * density
        val subSize = (if (compact) 10f else 11.5f) * density
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = titleSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(3f * density, 0f, 1f * density, Color.argb(115, 0, 0, 0))
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 236, 242, 250)
            textSize = subSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(2f * density, 0f, 1f * density, Color.argb(95, 0, 0, 0))
        }

        val titleText = ellipsizeText(caption.title, titlePaint, maxTextW)
        val rawSub = caption.subtitle?.takeIf { it.isNotBlank() }
        val subText = if (rawSub != null && bounds.height() >= 112f * density) ellipsizeText(rawSub, subPaint, maxTextW) else null

        val bottomBase = bounds.bottom - 12f * density
        if (subText != null) {
            canvas.drawText(subText, bounds.left + margin, bottomBase, subPaint)
            canvas.drawText(titleText, bounds.left + margin, bottomBase - subSize - 3.5f * density, titlePaint)
        } else {
            canvas.drawText(titleText, bounds.left + margin, bottomBase - 1f * density, titlePaint)
        }
    }

    /**
     * Draws a genuine backdrop-blurred frosted glass capsule sampling the ACTUAL photo pixels underneath!
     */
    private fun drawBackdropFrostedCapsule(
        canvas: Canvas,
        backdrop: Bitmap,
        rect: RectF,
        radius: Float,
        density: Float,
        darkTintAlpha: Int
    ) {
        val left = rect.left.toInt().coerceIn(0, backdrop.width - 1)
        val top = rect.top.toInt().coerceIn(0, backdrop.height - 1)
        val right = ceil(rect.right.toDouble()).toInt().coerceIn(left + 1, backdrop.width)
        val bottom = ceil(rect.bottom.toDouble()).toInt().coerceIn(top + 1, backdrop.height)
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)

        val capsulePath = buildContinuousSquirclePath(rect, radius, smoothness = 0.55f)
        val save = canvas.save()
        canvas.clipPath(capsulePath)

        // Crop & blur underlying photo pixels for real glassmorphism
        runCatching {
            val crop = Bitmap.createBitmap(backdrop, left, top, cropW, cropH)
            val blurred = createBlurredGlassBitmap(
                source = crop,
                targetW = cropW,
                targetH = cropH,
                blurRadius = 8,
                saturation = 1.2f,
                brightnessLift = 6f
            )
            canvas.drawBitmap(blurred, rect.left, rect.top, null)
            blurred.recycle()
            crop.recycle()
        }

        // Translucent frosted glass tint + specular sheen
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(
                    Color.argb(darkTintAlpha, 22, 30, 44),
                    Color.argb((darkTintAlpha * 1.15f).toInt().coerceAtMost(180), 12, 18, 28)
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, fillPaint)
        canvas.restoreToCount(save)

        // Delicate translucent specular border on the frosted capsule
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.75f * density
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(
                    Color.argb(76, 255, 255, 255),
                    Color.argb(22, 255, 255, 255),
                    Color.argb(52, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(capsulePath, strokePaint)
    }

    private fun drawEmptyHyperOsBackdrop(canvas: Canvas, bounds: RectF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, bounds.width(), bounds.height(),
                intArrayOf(
                    Color.rgb(34, 48, 72),
                    Color.rgb(20, 29, 46),
                    Color.rgb(14, 20, 34)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(bounds, paint)
    }

    private fun drawCenterCropBitmap(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || dst.width() <= 0f || dst.height() <= 0f) return
        val scale = max(dst.width() / bitmap.width, dst.height() / bitmap.height)
        val dx = dst.left + (dst.width() - bitmap.width * scale) * 0.5f
        val dy = dst.top + (dst.height() - bitmap.height * scale) * 0.5f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    private fun createBlurredGlassBitmap(
        source: Bitmap,
        targetW: Int,
        targetH: Int,
        blurRadius: Int,
        zoomFactor: Float = 1f,
        saturation: Float = 1f,
        brightnessLift: Float = 0f
    ): Bitmap {
        val safeW = targetW.coerceAtLeast(16)
        val safeH = targetH.coerceAtLeast(16)
        // Downscale to small fast buffer (~110px max edge) so StackBlur runs in ~1-2ms
        val smallScale = min(1f, 112f / max(safeW, safeH).toFloat())
        val smallW = (safeW * smallScale).roundToInt().coerceAtLeast(12)
        val smallH = (safeH * smallScale).roundToInt().coerceAtLeast(12)

        val small = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        val smallCanvas = Canvas(small)

        val scale = max(smallW.toFloat() / source.width, smallH.toFloat() / source.height) * zoomFactor
        val dx = (smallW - source.width * scale) * 0.5f
        val dy = (smallH - source.height * scale) * 0.5f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (saturation != 1f || brightnessLift != 0f) {
            val cm = ColorMatrix().apply { setSaturation(saturation) }
            if (brightnessLift != 0f) {
                cm.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1f, 0f, 0f, 0f, brightnessLift,
                            0f, 1f, 0f, 0f, brightnessLift,
                            0f, 0f, 1f, 0f, brightnessLift,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            paint.colorFilter = ColorMatrixColorFilter(cm)
        }
        smallCanvas.drawBitmap(source, matrix, paint)

        fastBoxBlurInPlace(small, blurRadius.coerceIn(2, 18))

        val out = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(out)
        val upscalePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        outCanvas.drawBitmap(small, null, Rect(0, 0, safeW, safeH), upscalePaint)
        small.recycle()
        return out
    }

    /**
     * Two-pass separable box blur (approximates smooth Gaussian blur in O(W*H) time).
     */
    private fun fastBoxBlurInPlace(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3 || radius < 1) return
        val r = min(radius, min(w, h) / 2).coerceAtLeast(1)
        val pixels = IntArray(w * h)
        val temp = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val div = r * 2 + 1

        repeat(2) {
            // Horizontal pass
            for (y in 0 until h) {
                val row = y * w
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (i in -r..r) {
                    val c = pixels[row + i.coerceIn(0, w - 1)]
                    rSum += (c ushr 16) and 0xFF
                    gSum += (c ushr 8) and 0xFF
                    bSum += c and 0xFF
                }
                for (x in 0 until w) {
                    temp[row + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                    val outIdx = row + (x - r).coerceIn(0, w - 1)
                    val inIdx = row + (x + r + 1).coerceIn(0, w - 1)
                    val cOut = pixels[outIdx]
                    val cIn = pixels[inIdx]
                    rSum += ((cIn ushr 16) and 0xFF) - ((cOut ushr 16) and 0xFF)
                    gSum += ((cIn ushr 8) and 0xFF) - ((cOut ushr 8) and 0xFF)
                    bSum += (cIn and 0xFF) - (cOut and 0xFF)
                }
            }
            // Vertical pass
            for (x in 0 until w) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (i in -r..r) {
                    val c = temp[i.coerceIn(0, h - 1) * w + x]
                    rSum += (c ushr 16) and 0xFF
                    gSum += (c ushr 8) and 0xFF
                    bSum += c and 0xFF
                }
                for (y in 0 until h) {
                    pixels[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                    val outIdx = (y - r).coerceIn(0, h - 1) * w + x
                    val inIdx = (y + r + 1).coerceIn(0, h - 1) * w + x
                    val cOut = temp[outIdx]
                    val cIn = temp[inIdx]
                    rSum += ((cIn ushr 16) and 0xFF) - ((cOut ushr 16) and 0xFF)
                    gSum += ((cIn ushr 8) and 0xFF) - ((cOut ushr 8) and 0xFF)
                    bSum += (cIn and 0xFF) - (cOut and 0xFF)
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun ellipsizeText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisW = paint.measureText(ellipsis)
        if (ellipsisW >= maxWidth) return ellipsis
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + ellipsisW > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }
}

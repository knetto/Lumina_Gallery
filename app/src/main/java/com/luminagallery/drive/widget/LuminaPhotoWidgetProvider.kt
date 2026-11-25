package com.luminagallery.drive.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.luminagallery.drive.MainActivity
import com.luminagallery.drive.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Base provider for all Lumina HyperOS-style glass photo widgets.
 * The original class name (`LuminaPhotoWidgetProvider`) is preserved so existing Photo Frame
 * widgets stay intact across app updates.
 */
internal open class LuminaPhotoWidgetProvider : AppWidgetProvider() {
    open val kind: PhotoWidgetKind = PhotoWidgetKind.FRAME

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetRefreshScheduler.syncSchedule(context)
        updateWidgets(context, appWidgetManager, appWidgetIds, advance = false, checkIntervalDue = true)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // Re-render at exact new resized aspect ratio without skipping the user's current photo/group
        updateWidgets(context, appWidgetManager, intArrayOf(appWidgetId), advance = false, checkIntervalDue = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NEXT_PHOTO) {
            super.onReceive(context, intent)
            return
        }
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetInfo(widgetId)?.provider != ComponentName(context, javaClass)) return
        updateWidgets(context, manager, intArrayOf(widgetId), advance = true, checkIntervalDue = false)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (id in appWidgetIds) {
            PhotoWidgetSettings.delete(context, id)
        }
        WidgetRefreshScheduler.syncSchedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshScheduler.syncSchedule(context)
    }

    private fun updateWidgets(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
        advance: Boolean = false,
        checkIntervalDue: Boolean = false
    ) {
        if (widgetIds.isEmpty()) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                renderWidgets(context, manager, widgetIds, advance, checkIntervalDue)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to refresh photo widgets", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal suspend fun renderWidgets(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
        advance: Boolean = false,
        checkIntervalDue: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val provider = ComponentName(appContext, this@LuminaPhotoWidgetProvider.javaClass)
        updateMutex.withLock {
            for (widgetId in widgetIds) {
                if (manager.getAppWidgetInfo(widgetId)?.provider != provider) continue
                runCatching {
                    updateSingleWidget(appContext, manager, widgetId, advance, checkIntervalDue)
                }.onFailure { err ->
                    Log.w(TAG, "Failed rendering widget $widgetId ($kind)", err)
                }
            }
        }
    }

    private suspend fun updateSingleWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        advance: Boolean,
        checkIntervalDue: Boolean
    ) {
        val settings = PhotoWidgetSettings.load(context, widgetId, kind)
        val prefs = context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastAdvance = prefs.getLong("last_advance_$widgetId", 0L)
        val effectiveAdvance = advance || (
            checkIntervalDue && isWidgetAdvanceDue(settings.shuffleInterval, lastAdvance, now)
        )
        val options = manager.getAppWidgetOptions(widgetId)
        val widgetSizes = resolveWidgetSizesDp(options)
        val primarySize = widgetSizes.first()

        val maxSlots = when (kind) {
            PhotoWidgetKind.COLLAGE, PhotoWidgetKind.MEMORIES -> 6
            PhotoWidgetKind.STRIP -> 4
            else -> 1
        }
        val useFastSlideshow = settings.shuffleInterval.isFastSlideshow
        val requestedFrames = if (useFastSlideshow) SLIDESHOW_FRAME_COUNT else 1

        val selections = WidgetMediaRepository.resolveWidgetFrames(
            context = context,
            widgetId = widgetId,
            kind = kind,
            settings = settings,
            slotsPerFrame = maxSlots,
            frameCount = requestedFrames,
            advance = effectiveAdvance
        )

        val today = LocalDate.now()
        val primarySelection = selections.firstOrNull()
        prefs.edit().apply {
            if (effectiveAdvance || lastAdvance == 0L) {
                putLong("last_advance_$widgetId", now)
            }
            putString("last_date_$widgetId", today.toString())
            if (primarySelection != null && primarySelection.photos.isNotEmpty()) {
                putString(
                    "shown_ids_$widgetId",
                    primarySelection.photos.joinToString(",") { it.luminaItemId }
                )
                val cap = primarySelection.caption
                if (cap != null) {
                    putString("shown_title_$widgetId", cap.title)
                    if (cap.subtitle != null) putString("shown_sub_$widgetId", cap.subtitle)
                    else remove("shown_sub_$widgetId")
                } else {
                    remove("shown_title_$widgetId")
                    remove("shown_sub_$widgetId")
                }
            }
        }.apply()

        val canUseResponsiveMap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !useFastSlideshow &&
            widgetSizes.size > 1

        if (canUseResponsiveMap) {
            updateWithResponsiveSizeMap(
                context = context,
                manager = manager,
                widgetId = widgetId,
                sizes = widgetSizes.take(2),
                settings = settings,
                selection = selections.firstOrNull(),
                today = today
            )
            return
        }

        val remoteViews = if (useFastSlideshow && selections.size > 1) {
            buildSlideshowRemoteViews(
                context = context,
                widgetId = widgetId,
                sizeDp = primarySize,
                settings = settings,
                selections = selections,
                today = today,
                scaleDown = 1f
            )
        } else {
            buildSingleFrameRemoteViews(
                context = context,
                widgetId = widgetId,
                sizeDp = primarySize,
                settings = settings,
                selection = selections.firstOrNull(),
                today = today,
                scaleDown = 1f
            )
        }

        try {
            manager.updateAppWidget(widgetId, remoteViews)
        } catch (err: IllegalArgumentException) {
            // Memory-budget fallback if OEM launcher enforces a smaller bitmap cap
            Log.w(TAG, "Widget $widgetId bitmap budget exceeded, retrying at 0.65x", err)
            val fallback = buildSingleFrameRemoteViews(
                context = context,
                widgetId = widgetId,
                sizeDp = primarySize,
                settings = settings,
                selection = selections.firstOrNull(),
                today = today,
                scaleDown = 0.65f
            )
            manager.updateAppWidget(widgetId, fallback)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun updateWithResponsiveSizeMap(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        sizes: List<SizeF>,
        settings: PhotoWidgetSettings,
        selection: WidgetResolvedSelection?,
        today: LocalDate
    ) {
        val map = LinkedHashMap<SizeF, RemoteViews>(sizes.size)
        val perSizeScale = if (sizes.size > 1) 0.85f else 1f
        for (size in sizes) {
            map[size] = buildSingleFrameRemoteViews(
                context = context,
                widgetId = widgetId,
                sizeDp = size,
                settings = settings,
                selection = selection,
                today = today,
                scaleDown = perSizeScale
            )
        }
        try {
            manager.updateAppWidget(widgetId, RemoteViews(map))
        } catch (err: IllegalArgumentException) {
            Log.w(TAG, "Responsive map exceeded bitmap cap for widget $widgetId, falling back", err)
            val primary = buildSingleFrameRemoteViews(
                context = context,
                widgetId = widgetId,
                sizeDp = sizes.first(),
                settings = settings,
                selection = selection,
                today = today,
                scaleDown = 0.7f
            )
            manager.updateAppWidget(widgetId, primary)
        }
    }

    private fun buildSingleFrameRemoteViews(
        context: Context,
        widgetId: Int,
        sizeDp: SizeF,
        settings: PhotoWidgetSettings,
        selection: WidgetResolvedSelection?,
        today: LocalDate,
        scaleDown: Float
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lumina_rendered)
        val rawPhotos = selection?.photos.orEmpty()
        val varietySeed = (selection?.groupKey?.hashCode() ?: widgetId) and 0x7FFFFFFF
        val prePattern = HyperOsGlassRenderer.resolveTilePattern(
            kind = kind,
            collageLayout = settings.collageLayout,
            widthDp = sizeDp.width,
            heightDp = sizeDp.height,
            photoCount = rawPhotos.size,
            photoAspectRatios = rawPhotos.map { it.aspectRatio },
            varietySeed = varietySeed
        )
        val (renderW, renderH, renderDensity) = computeRenderCanvasSize(
            context = context,
            sizeDp = sizeDp,
            frameCount = 1,
            scaleDown = scaleDown
        )
        val slotDecodeEdge = max(renderW, renderH).coerceIn(240, 1080)
        val decodedRaw = decodePhotos(context, rawPhotos, prePattern.maxSlots, slotDecodeEdge)
        // Use actual decoded bitmap aspect ratios so orientation-aware layout + slot matching is 100% accurate!
        val (actualPattern, decoded) = HyperOsGlassRenderer.resolveSmartPatternAndOrder(
            kind = kind,
            collageLayout = settings.collageLayout,
            widthDp = sizeDp.width,
            heightDp = sizeDp.height,
            photos = decodedRaw,
            aspectRatioOf = { (_, bmp) -> (bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceIn(0.35f, 3.0f) },
            varietySeed = varietySeed
        )

        val showShuffleBtn = settings.showShuffleButton &&
            (selection?.totalChoices ?: 0) > 1 &&
            sizeDp.width >= 96f && sizeDp.height >= 76f
        val showSettingsBtn = settings.showSettingsButton &&
            sizeDp.width >= 96f && sizeDp.height >= 76f

        val rendered = HyperOsGlassRenderer.renderWidgetBitmap(
            photos = decoded.map { it.second },
            pattern = actualPattern,
            widthPx = renderW,
            heightPx = renderH,
            density = renderDensity,
            settings = settings,
            caption = if (decoded.isNotEmpty()) selection?.caption else null,
            date = today,
            reserveBottomRightButton = showShuffleBtn,
            reserveTopLeftButton = showSettingsBtn,
            overrideCornerRadiusDp = resolveSystemHyperCornerDp(context, settings)
        )
        decodedRaw.forEach { it.second.recycle() }

        views.setImageViewBitmap(R.id.widget_rendered_image, rendered)
        views.setContentDescription(
            R.id.widget_rendered_image,
            buildContentDescription(context, decoded.firstOrNull()?.first, selection?.caption)
        )

        // Root tap fallback
        val primaryPhoto = decoded.firstOrNull()?.first
        views.setOnClickPendingIntent(
            android.R.id.background,
            buildTapIntent(context, widgetId, slot = 0, photo = primaryPhoto, settings = settings)
        )

        // Per-tile transparent click targets for multi-photo collages & strips (up to 6 tiles)
        views.removeAllViews(R.id.widget_hit_overlay)
        if (decoded.size > 1 && settings.tapAction == WidgetTapAction.OPEN_PHOTO) {
            val hitLayout = hitOverlayLayoutFor(actualPattern)
            if (hitLayout != null) {
                val overlay = RemoteViews(context.packageName, hitLayout)
                val maxViewsInLayout = hitViewCountFor(actualPattern)
                val hitIds = intArrayOf(
                    R.id.widget_hit_0,
                    R.id.widget_hit_1,
                    R.id.widget_hit_2,
                    R.id.widget_hit_3,
                    R.id.widget_hit_4,
                    R.id.widget_hit_5
                )
                for (idx in 0 until maxViewsInLayout) {
                    val hitId = hitIds[idx]
                    val photo = decoded.getOrNull(idx)?.first
                    if (photo != null && idx < actualPattern.maxSlots) {
                        overlay.setViewVisibility(hitId, View.VISIBLE)
                        overlay.setContentDescription(
                            hitId,
                            context.getString(
                                R.string.widget_photo_details,
                                photo.albumName,
                                widgetDateLabel(photo.takenAtMs)
                            )
                        )
                        overlay.setOnClickPendingIntent(
                            hitId,
                            openPhotoPendingIntent(context, widgetId, idx, photo)
                        )
                    } else if (actualPattern == ResolvedTilePattern.STRIP_HORIZONTAL_2 ||
                        actualPattern == ResolvedTilePattern.STRIP_HORIZONTAL_3 ||
                        actualPattern == ResolvedTilePattern.STRIP_VERTICAL_2
                    ) {
                        overlay.setViewVisibility(hitId, View.GONE)
                    }
                }
                views.addView(R.id.widget_hit_overlay, overlay)
            }
        }

        // Empty state message
        val isEmpty = decoded.isEmpty()
        setVisible(views, R.id.widget_empty_state, isEmpty)
        if (isEmpty) {
            views.setTextViewText(R.id.widget_empty_label, emptyMessageFor(context, settings))
        }

        // Optional sleek frosted-glass quick controls
        bindQuickButtons(
            context = context,
            views = views,
            widgetId = widgetId,
            showShuffle = showShuffleBtn,
            showSettings = showSettingsBtn || isEmpty
        )

        return views
    }

    private fun buildSlideshowRemoteViews(
        context: Context,
        widgetId: Int,
        sizeDp: SizeF,
        settings: PhotoWidgetSettings,
        selections: List<WidgetResolvedSelection>,
        today: LocalDate,
        scaleDown: Float
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lumina_slideshow)
        views.removeAllViews(R.id.widget_shuffle_container)

        val showShuffleBtn = settings.showShuffleButton && sizeDp.width >= 96f && sizeDp.height >= 76f
        val showSettingsBtn = settings.showSettingsButton && sizeDp.width >= 96f && sizeDp.height >= 76f

        val maxFrames = selections.size.coerceAtMost(SLIDESHOW_FRAME_COUNT)
        val (renderW, renderH, renderDensity) = computeRenderCanvasSize(
            context = context,
            sizeDp = sizeDp,
            frameCount = maxFrames,
            scaleDown = scaleDown
        )
        val decodeEdge = max(renderW, renderH).coerceIn(220, 760)

        val animation = RemoteViews(context.packageName, R.layout.widget_shuffle_animation)
        animation.setInt(
            R.id.widget_shuffled_photos,
            "setFlipInterval",
            settings.effectiveFlipSeconds * 1000
        )

        var addedFrames = 0
        selections.take(maxFrames).forEachIndexed { frameIndex, sel ->
            val varietySeed = ((sel.groupKey?.hashCode() ?: widgetId) + frameIndex) and 0x7FFFFFFF
            val prePattern = HyperOsGlassRenderer.resolveTilePattern(
                kind = kind,
                collageLayout = settings.collageLayout,
                widthDp = sizeDp.width,
                heightDp = sizeDp.height,
                photoCount = sel.photos.size,
                photoAspectRatios = sel.photos.map { it.aspectRatio },
                varietySeed = varietySeed
            )
            val decodedRaw = decodePhotos(context, sel.photos, prePattern.maxSlots, decodeEdge)
            if (decodedRaw.isEmpty()) return@forEachIndexed

            val (actualPattern, decoded) = HyperOsGlassRenderer.resolveSmartPatternAndOrder(
                kind = kind,
                collageLayout = settings.collageLayout,
                widthDp = sizeDp.width,
                heightDp = sizeDp.height,
                photos = decodedRaw,
                aspectRatioOf = { (_, bmp) -> (bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceIn(0.35f, 3.0f) },
                varietySeed = varietySeed
            )
            val rendered = HyperOsGlassRenderer.renderWidgetBitmap(
                photos = decoded.map { it.second },
                pattern = actualPattern,
                widthPx = renderW,
                heightPx = renderH,
                density = renderDensity,
                settings = settings,
                caption = sel.caption,
                date = today,
                reserveBottomRightButton = showShuffleBtn,
                reserveTopLeftButton = showSettingsBtn,
                overrideCornerRadiusDp = resolveSystemHyperCornerDp(context, settings)
            )
            val leadPhoto = decoded.first().first
            decodedRaw.forEach { it.second.recycle() }

            val frameView = RemoteViews(context.packageName, R.layout.widget_shuffle_frame)
            frameView.setImageViewBitmap(R.id.widget_image, rendered)
            frameView.setContentDescription(
                R.id.widget_image,
                buildContentDescription(context, leadPhoto, sel.caption)
            )
            frameView.setOnClickPendingIntent(
                R.id.widget_image,
                buildTapIntent(context, widgetId, slot = frameIndex, photo = leadPhoto, settings = settings)
            )
            animation.addView(R.id.widget_shuffled_photos, frameView)
            addedFrames++
        }

        if (addedFrames > 0) {
            views.addView(R.id.widget_shuffle_container, animation)
        }

        val isEmpty = addedFrames == 0
        setVisible(views, R.id.widget_empty_state, isEmpty)
        if (isEmpty) {
            views.setTextViewText(R.id.widget_empty_label, emptyMessageFor(context, settings))
        }
        views.setOnClickPendingIntent(
            android.R.id.background,
            buildTapIntent(context, widgetId, slot = -1, photo = null, settings = settings)
        )
        bindQuickButtons(
            context = context,
            views = views,
            widgetId = widgetId,
            showShuffle = showShuffleBtn && addedFrames > 0,
            showSettings = showSettingsBtn || isEmpty
        )
        return views
    }

    private fun resolveSystemHyperCornerDp(context: Context, settings: PhotoWidgetSettings): Float? {
        // Use exact HyperOS continuous squircle radius (26dp) rather than AOSP's sharper 20dp dimen
        return null
    }

    private fun hitOverlayLayoutFor(pattern: ResolvedTilePattern): Int? = when (pattern) {
        ResolvedTilePattern.HERO_LEFT_2_RIGHT -> R.layout.widget_hits_hero_left_2_right
        ResolvedTilePattern.HERO_RIGHT_2_LEFT -> R.layout.widget_hits_hero_right_2_left
        ResolvedTilePattern.HERO_TOP_2_BOTTOM -> R.layout.widget_hits_hero_top_2_bottom
        ResolvedTilePattern.GRID_2X2 -> R.layout.widget_hits_grid_2x2
        ResolvedTilePattern.BENTO_4_WIDE -> R.layout.widget_hits_bento_4_wide
        ResolvedTilePattern.BENTO_4_TOP_HERO -> R.layout.widget_hits_bento_4_top_hero
        ResolvedTilePattern.MOSAIC_5_HERO_LEFT -> R.layout.widget_hits_mosaic_5_hero_left
        ResolvedTilePattern.MOSAIC_5_HERO_TOP -> R.layout.widget_hits_mosaic_5_hero_top
        ResolvedTilePattern.GRID_3X2 -> R.layout.widget_hits_grid_3x2
        ResolvedTilePattern.GRID_2X3 -> R.layout.widget_hits_grid_2x3
        ResolvedTilePattern.STRIP_HORIZONTAL_2,
        ResolvedTilePattern.STRIP_HORIZONTAL_3,
        ResolvedTilePattern.STRIP_HORIZONTAL_4 -> R.layout.widget_hits_strip_h
        ResolvedTilePattern.STRIP_VERTICAL_2,
        ResolvedTilePattern.STRIP_VERTICAL_3 -> R.layout.widget_hits_strip_v
        ResolvedTilePattern.SINGLE -> null
    }

    private fun hitViewCountFor(pattern: ResolvedTilePattern): Int = when (pattern) {
        ResolvedTilePattern.HERO_LEFT_2_RIGHT,
        ResolvedTilePattern.HERO_RIGHT_2_LEFT,
        ResolvedTilePattern.HERO_TOP_2_BOTTOM,
        ResolvedTilePattern.STRIP_VERTICAL_2,
        ResolvedTilePattern.STRIP_VERTICAL_3 -> 3
        ResolvedTilePattern.GRID_2X2,
        ResolvedTilePattern.BENTO_4_WIDE,
        ResolvedTilePattern.BENTO_4_TOP_HERO,
        ResolvedTilePattern.STRIP_HORIZONTAL_2,
        ResolvedTilePattern.STRIP_HORIZONTAL_3,
        ResolvedTilePattern.STRIP_HORIZONTAL_4 -> 4
        ResolvedTilePattern.MOSAIC_5_HERO_LEFT,
        ResolvedTilePattern.MOSAIC_5_HERO_TOP -> 5
        ResolvedTilePattern.GRID_3X2,
        ResolvedTilePattern.GRID_2X3 -> 6
        ResolvedTilePattern.SINGLE -> 0
    }

    private fun resolveWidgetSizesDp(options: Bundle): List<SizeF> =
        resolveWidgetSizesDpForKind(options, kind)

    /**
     * Computes exact pixel canvas dimensions matching [sizeDp]'s aspect ratio while safely
     * respecting Android's RemoteViews total ARGB bitmap memory budget.
     */
    private fun computeRenderCanvasSize(
        context: Context,
        sizeDp: SizeF,
        frameCount: Int,
        scaleDown: Float
    ): Triple<Int, Int, Float> {
        val metrics = context.resources.displayMetrics
        val windowBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        } else null
        val displayPixels = metrics.widthPixels.toDouble() * metrics.heightPixels
        val windowPixels = windowBounds?.let { it.width().toDouble() * it.height() } ?: displayPixels
        // Stay comfortably below Android's 1.5x screen bitmap RemoteViews cap
        val totalPixelBudget = min(displayPixels, windowPixels) * 0.88
        val perFrameBudget = totalPixelBudget / frameCount.coerceAtLeast(1)

        val rawW = sizeDp.width * metrics.density * scaleDown
        val rawH = sizeDp.height * metrics.density * scaleDown
        val maxCapEdge = if (frameCount > 1) 740f else 1080f

        var factor = 1f
        val maxSide = max(rawW, rawH)
        if (maxSide > maxCapEdge) {
            factor = min(factor, maxCapEdge / maxSide)
        }
        val area = rawW.toDouble() * rawH.toDouble()
        if (area > perFrameBudget && area > 0.0) {
            factor = min(factor, sqrt(perFrameBudget / area).toFloat())
        }

        val finalW = (rawW * factor).roundToInt().coerceAtLeast(96)
        val finalH = (rawH * factor).roundToInt().coerceAtLeast(96)
        // Scale density proportionally so dp-sized glass rims & typography stay crisp at any downscale
        val effectiveDensity = (metrics.density * scaleDown * factor).coerceAtLeast(0.75f)
        return Triple(finalW, finalH, effectiveDensity)
    }

    private fun decodePhotos(
        context: Context,
        pool: List<WidgetPhoto>,
        limit: Int,
        targetMax: Int
    ): List<Pair<WidgetPhoto, Bitmap>> = buildList {
        for (photo in pool) {
            val bitmap = decodeWidgetBitmap(context, Uri.parse(photo.uri), targetMax) ?: continue
            add(photo to bitmap)
            if (size >= limit) break
        }
    }

    private fun decodeWidgetBitmap(context: Context, uri: Uri, targetMax: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxDim = maxOf(info.size.width, info.size.height)
                if (maxDim > targetMax) {
                    val scale = targetMax.toFloat() / maxDim
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > targetMax) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    }.getOrNull()

    private fun bindQuickButtons(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        showShuffle: Boolean,
        showSettings: Boolean
    ) {
        setVisible(views, R.id.widget_next_btn, showShuffle)
        if (showShuffle) {
            views.setOnClickPendingIntent(
                R.id.widget_next_btn,
                nextPhotoPendingIntent(context, widgetId)
            )
        }
        setVisible(views, R.id.widget_settings_btn, showSettings)
        if (showSettings) {
            views.setOnClickPendingIntent(
                R.id.widget_settings_btn,
                openSettingsPendingIntent(context, widgetId)
            )
        }
    }

    private fun buildTapIntent(
        context: Context,
        widgetId: Int,
        slot: Int,
        photo: WidgetPhoto?,
        settings: PhotoWidgetSettings
    ): PendingIntent = when (settings.tapAction) {
        WidgetTapAction.OPEN_PHOTO -> openPhotoPendingIntent(context, widgetId, slot, photo)
        WidgetTapAction.SHUFFLE_NEXT -> nextPhotoPendingIntent(context, widgetId)
        WidgetTapAction.OPEN_SETTINGS -> openSettingsPendingIntent(context, widgetId)
    }

    private fun nextPhotoPendingIntent(context: Context, widgetId: Int): PendingIntent {
        val nextIntent = Intent(context, javaClass).apply {
            action = ACTION_NEXT_PHOTO
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        return PendingIntent.getBroadcast(context, widgetId * 31 + 7, nextIntent, PENDING_FLAGS)
    }

    private fun openSettingsPendingIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, PhotoWidgetSettingsActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, widgetId * 31 + 13, intent, PENDING_FLAGS)
    }

    private fun openPhotoPendingIntent(
        context: Context,
        widgetId: Int,
        slot: Int,
        photo: WidgetPhoto?
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("lumina-widget://photo/$widgetId/$slot")
            photo?.let { putExtra(EXTRA_WIDGET_OPEN_ITEM_ID, it.luminaItemId) }
        }
        return PendingIntent.getActivity(context, widgetId * 31 + slot + 1, intent, PENDING_FLAGS)
    }

    private fun emptyMessageFor(context: Context, settings: PhotoWidgetSettings): String = when (settings.source) {
        WidgetPhotoSource.SELECTED_PHOTOS -> context.getString(R.string.widget_empty_selected_photos)
        WidgetPhotoSource.SELECTED_ALBUMS -> context.getString(R.string.widget_empty_selected_albums)
        WidgetPhotoSource.LOCATION_GROUPS -> context.getString(R.string.widget_empty_trips)
        WidgetPhotoSource.FAVORITES_ONLY -> context.getString(R.string.widget_empty_favorites)
        WidgetPhotoSource.DAY_GROUPS, WidgetPhotoSource.MEMORIES_MIX -> context.getString(R.string.widget_empty_memories)
        else -> context.getString(R.string.widget_open_gallery)
    }

    private fun buildContentDescription(
        context: Context,
        photo: WidgetPhoto?,
        caption: WidgetCaptionData?
    ): String = when {
        caption != null && caption.title.isNotBlank() -> caption.title
        photo != null -> context.getString(
            R.string.widget_photo_details,
            photo.albumName,
            widgetDateLabel(photo.takenAtMs)
        )
        else -> context.getString(R.string.widget_open_gallery)
    }

    private fun setVisible(views: RemoteViews, id: Int, visible: Boolean) {
        views.setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
    }

    companion object {
        const val EXTRA_WIDGET_OPEN_ITEM_ID = "com.luminagallery.drive.EXTRA_WIDGET_OPEN_ITEM_ID"
        private const val ACTION_NEXT_PHOTO = "com.luminagallery.drive.widget.ACTION_NEXT_PHOTO"
        private const val TAG = "LuminaPhotoWidget"
        private const val SLIDESHOW_FRAME_COUNT = 6
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private val updateMutex = Mutex()

        internal fun resolveWidgetSizesDpForKind(options: Bundle, kind: PhotoWidgetKind): List<SizeF> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                val reported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                } else {
                    options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                }
                val valid = reported?.filter { it.width >= 32f && it.height >= 32f }.orEmpty()
                if (valid.isNotEmpty()) {
                    return valid.sortedByDescending { it.height }
                }
            }
            val (defW, defH) = when (kind) {
                PhotoWidgetKind.COLLAGE, PhotoWidgetKind.MEMORIES -> 320f to 165f
                PhotoWidgetKind.STRIP -> 320f to 84f
                else -> 160f to 160f
            }
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val w = (if (minW > 0) minW.toFloat() else defW).coerceAtLeast(48f)
            val h = (when {
                maxH > 0 -> maxH.toFloat()
                minH > 0 -> minH.toFloat()
                else -> defH
            }).coerceAtLeast(48f)
            return listOf(SizeF(w, h))
        }

        internal fun isWidgetAdvanceDue(
            interval: WidgetShuffleInterval,
            lastAdvanceMs: Long,
            nowMs: Long
        ): Boolean {
            if (interval == WidgetShuffleInterval.MANUAL || lastAdvanceMs <= 0L) return false
            val targetMs = if (interval.isFastSlideshow) {
                30L * 60L * 1000L
            } else {
                interval.intervalMs
            }
            return nowMs - lastAdvanceMs >= (targetMs - 30_000L)
        }

        fun allProviders(): List<LuminaPhotoWidgetProvider> = listOf(
            LuminaPhotoWidgetProvider(),
            LuminaPhotoShuffleWidgetProvider(),
            LuminaPhotoCollageWidgetProvider(),
            LuminaMemoriesWidgetProvider(),
            LuminaPhotoStripWidgetProvider(),
            LuminaFavoritesWidgetProvider(),
            LuminaPhotoDateWidgetProvider()
        )

        fun providerForComponent(context: Context, component: ComponentName?): LuminaPhotoWidgetProvider? {
            if (component == null) return null
            return allProviders().firstOrNull { ComponentName(context, it.javaClass) == component }
        }

        suspend fun refreshAllWidgets(context: Context, advance: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            for (provider in allProviders()) {
                val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
                if (ids.isEmpty()) continue
                runCatching { provider.renderWidgets(context, manager, ids, advance = advance) }
                    .onFailure { Log.w(TAG, "Unable to refresh ${provider.kind} widgets", it) }
            }
        }

        fun refreshOnExclusionsChanged(context: Context, exclusions: Flow<Set<String>>) {
            CoroutineScope(Dispatchers.IO).launch {
                exclusions.distinctUntilChanged().drop(1).collect {
                    refreshAllWidgets(context, advance = false)
                }
            }
        }
    }
}

internal class LuminaPhotoStripWidgetProvider : LuminaPhotoWidgetProvider() {
    override val kind = PhotoWidgetKind.STRIP
}

internal class LuminaPhotoCollageWidgetProvider : LuminaPhotoWidgetProvider() {
    override val kind = PhotoWidgetKind.COLLAGE
}

internal class LuminaPhotoShuffleWidgetProvider : LuminaPhotoWidgetProvider() {
    override val kind = PhotoWidgetKind.RANDOM_SHUFFLE
}

internal class LuminaMemoriesWidgetProvider : LuminaPhotoWidgetProvider() {
    override val kind = PhotoWidgetKind.MEMORIES
}

internal class LuminaFavoritesWidgetProvider : LuminaPhotoWidgetProvider() {
    override val kind = PhotoWidgetKind.FAVORITES
}

internal class LuminaPhotoDateWidgetProvider : LuminaPhotoWidgetProvider() {
    override val kind = PhotoWidgetKind.PHOTO_DATE
}

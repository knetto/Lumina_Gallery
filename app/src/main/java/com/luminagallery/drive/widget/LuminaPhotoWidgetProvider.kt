package com.luminagallery.drive.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
import com.luminagallery.drive.MainActivity
import com.luminagallery.drive.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
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
            deleteCachedRender(context, id)
            widgetLocks.remove(id)
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
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var completed = false
            try {
                completed = withTimeoutOrNull(BROADCAST_RENDER_TIMEOUT_MS) {
                    renderWidgets(appContext, manager, widgetIds, advance, checkIntervalDue)
                    true
                } == true
                if (!completed) {
                    Log.w(TAG, "Broadcast widget render hit timeout; handing off to WorkManager recovery")
                    WidgetRefreshScheduler.enqueueImmediateRecovery(appContext)
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                Log.w(TAG, "Unable to refresh photo widgets", err)
                WidgetRefreshScheduler.enqueueImmediateRecovery(appContext)
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
        for (widgetId in widgetIds) {
            if (manager.getAppWidgetInfo(widgetId)?.provider != provider) continue
            val lock = widgetLocks.computeIfAbsent(widgetId) { Mutex() }
            lock.withLock {
                try {
                    updateSingleWidget(appContext, manager, widgetId, advance, checkIntervalDue)
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Throwable) {
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
        val primarySize = resolvePrimaryWidgetSizeDp(context, options, kind)

        val maxSlots = when (kind) {
            PhotoWidgetKind.COLLAGE -> if (settings.collageLayout == WidgetCollageLayout.STRIP) 4 else 6
            PhotoWidgetKind.FRAME -> 1
        }
        val useFastSlideshow = settings.shuffleInterval.isFastSlideshow
        val requestedFrames = if (useFastSlideshow) SLIDESHOW_FRAME_COUNT else 1
        val today = LocalDate.now()

        // Fast-path: on non-advancing updates (resize, launcher restart, app update, keep-alive heartbeat,
        // appearance-only edit), reuse the currently displayed verified photos directly via indexed SQL lookup.
        // This avoids scanning thousands of photos on boot/resize and keeps the displayed photo stable!
        if (!effectiveAdvance && !useFastSlideshow) {
            val fastSelection = tryResolveFastCachedSelection(
                context = context,
                prefs = prefs,
                widgetId = widgetId,
                settings = settings,
                primarySize = primarySize,
                maxSlots = maxSlots
            )
            when (fastSelection) {
                is FastSelectionResult.PrivacyUnavailable -> {
                    Log.w(TAG, "Privacy verification unavailable during widget $widgetId recovery; deferring")
                    WidgetRefreshScheduler.enqueueImmediateRecovery(context)
                    return
                }
                is FastSelectionResult.Hit -> {
                    if (renderAndPushSingleFrame(
                            context = context,
                            manager = manager,
                            prefs = prefs,
                            widgetId = widgetId,
                            sizeDp = primarySize,
                            settings = settings,
                            selection = fastSelection.selection,
                            today = today,
                            allowCachedBitmapFallback = true
                        )
                    ) {
                        prefs.edit()
                            .putString("last_date_$widgetId", today.toString())
                            .apply()
                        return
                    }
                }
                FastSelectionResult.Miss -> Unit
            }
        }

        val resolution = WidgetMediaRepository.resolveWidgetFramesChecked(
            context = context,
            widgetId = widgetId,
            kind = kind,
            settings = settings,
            slotsPerFrame = maxSlots,
            frameCount = requestedFrames,
            advance = effectiveAdvance
        )
        if (!resolution.privacyVerified) {
            // Transient DB/Direct-Boot failure: never overwrite an active widget with an empty state!
            Log.w(TAG, "Skipping widget $widgetId overwrite while privacy verification is unavailable")
            WidgetRefreshScheduler.enqueueImmediateRecovery(context)
            return
        }

        val selections = resolution.frames
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

        if (useFastSlideshow && selections.size > 1) {
            pushSlideshowSafely(
                context = context,
                manager = manager,
                prefs = prefs,
                widgetId = widgetId,
                sizeDp = primarySize,
                settings = settings,
                selections = selections,
                today = today
            )
            return
        }

        renderAndPushSingleFrame(
            context = context,
            manager = manager,
            prefs = prefs,
            widgetId = widgetId,
            sizeDp = primarySize,
            settings = settings,
            selection = primarySelection,
            today = today,
            allowCachedBitmapFallback = primarySelection != null && primarySelection.photos.isNotEmpty()
        )
    }

    private sealed interface FastSelectionResult {
        data class Hit(val selection: WidgetResolvedSelection) : FastSelectionResult
        data object Miss : FastSelectionResult
        data object PrivacyUnavailable : FastSelectionResult
    }

    private suspend fun tryResolveFastCachedSelection(
        context: Context,
        prefs: android.content.SharedPreferences,
        widgetId: Int,
        settings: PhotoWidgetSettings,
        primarySize: SizeF,
        maxSlots: Int
    ): FastSelectionResult {
        val savedIds = prefs.getString("shown_ids_$widgetId", null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (savedIds.isEmpty()) return FastSelectionResult.Miss

        val neededSlots = if (kind.isMultiPhoto && settings.collageLayout != WidgetCollageLayout.AUTO) {
            HyperOsGlassRenderer.resolveTilePattern(
                kind = kind,
                collageLayout = settings.collageLayout,
                widthDp = primarySize.width,
                heightDp = primarySize.height,
                photoCount = maxSlots
            ).maxSlots
        } else {
            1
        }
        val selectedCap = if (settings.source == WidgetPhotoSource.SELECTED_PHOTOS && settings.selectedPhotoIds.isNotEmpty()) {
            min(neededSlots, settings.selectedPhotoIds.size)
        } else {
            neededSlots
        }
        if (savedIds.size < selectedCap) return FastSelectionResult.Miss

        if (settings.source == WidgetPhotoSource.PEOPLE_AND_PETS &&
            !WidgetMediaRepository.areSelectedPeopleStillVisible(context, settings)
        ) {
            return FastSelectionResult.Miss
        }

        val verified = WidgetMediaRepository.loadVerifiedPhotosByIds(context, savedIds.take(maxSlots))
            ?: return FastSelectionResult.PrivacyUnavailable
        // If any previously displayed photo was deleted or moved to a hidden/locked album, resolve fresh!
        if (verified.size < savedIds.take(maxSlots).size || verified.isEmpty()) {
            return FastSelectionResult.Miss
        }

        val savedTitle = prefs.getString("shown_title_$widgetId", null)
        val savedSub = prefs.getString("shown_sub_$widgetId", null)
        val caption = if (!savedTitle.isNullOrBlank()) {
            WidgetCaptionData(savedTitle, savedSub)
        } else {
            val first = verified.first()
            WidgetCaptionData(
                title = first.albumName.ifBlank { "Photos" },
                subtitle = widgetDateLabel(first.takenAtMs).takeIf { it.isNotBlank() }
            )
        }
        return FastSelectionResult.Hit(
            WidgetResolvedSelection(
                photos = verified,
                caption = caption,
                groupKey = prefs.getString("group_key_$widgetId", null),
                totalChoices = if (settings.source == WidgetPhotoSource.SELECTED_PHOTOS && settings.selectedPhotoIds.size <= 1) 1 else 2
            )
        )
    }

    /**
     * Builds & pushes a single-frame RemoteViews with progressive scale-down retries on any IPC/memory
     * failure, plus disk-cached last-good bitmap protection against transient URI decode failures.
     */
    private fun renderAndPushSingleFrame(
        context: Context,
        manager: AppWidgetManager,
        prefs: android.content.SharedPreferences,
        widgetId: Int,
        sizeDp: SizeF,
        settings: PhotoWidgetSettings,
        selection: WidgetResolvedSelection?,
        today: LocalDate,
        allowCachedBitmapFallback: Boolean
    ): Boolean {
        val scales = floatArrayOf(1.0f, 0.68f, 0.45f)
        for ((attempt, scale) in scales.withIndex()) {
            try {
                val built = buildSingleFrameRemoteViews(
                    context = context,
                    widgetId = widgetId,
                    sizeDp = sizeDp,
                    settings = settings,
                    selection = selection,
                    today = today,
                    scaleDown = scale,
                    allowCachedBitmapFallback = allowCachedBitmapFallback
                )
                if (built.hadTransientDecodeFailure) {
                    if (built.usedDiskFallback && built.views != null) {
                        manager.updateAppWidget(widgetId, built.views)
                        prefs.edit().putLong("last_push_$widgetId", System.currentTimeMillis()).apply()
                    }
                    WidgetRefreshScheduler.enqueueImmediateRecovery(context)
                    return false
                }
                val views = built.views ?: return false
                manager.updateAppWidget(widgetId, views)
                prefs.edit().putLong("last_push_$widgetId", System.currentTimeMillis()).apply()
                built.renderedBitmapToCache?.let { bmp ->
                    saveCachedRender(context, widgetId, bmp)
                }
                return true
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                Log.w(
                    TAG,
                    "Widget $widgetId push failed at scale=${scale}x (attempt ${attempt + 1}/${scales.size})",
                    err
                )
            }
        }
        return false
    }

    private fun pushSlideshowSafely(
        context: Context,
        manager: AppWidgetManager,
        prefs: android.content.SharedPreferences,
        widgetId: Int,
        sizeDp: SizeF,
        settings: PhotoWidgetSettings,
        selections: List<WidgetResolvedSelection>,
        today: LocalDate
    ) {
        // Attempt 1 & 2: full live slideshow (normal, then compact scale)
        for (scale in floatArrayOf(1.0f, 0.68f)) {
            try {
                val views = buildSlideshowRemoteViews(
                    context = context,
                    widgetId = widgetId,
                    sizeDp = sizeDp,
                    settings = settings,
                    selections = selections,
                    today = today,
                    scaleDown = scale
                )
                manager.updateAppWidget(widgetId, views)
                prefs.edit().putLong("last_push_$widgetId", System.currentTimeMillis()).apply()
                return
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                Log.w(TAG, "Widget $widgetId slideshow push failed at ${scale}x, retrying", err)
            }
        }
        // Final guaranteed fallback: single frame so the widget NEVER goes blank on strict launchers!
        renderAndPushSingleFrame(
            context = context,
            manager = manager,
            prefs = prefs,
            widgetId = widgetId,
            sizeDp = sizeDp,
            settings = settings,
            selection = selections.firstOrNull(),
            today = today,
            allowCachedBitmapFallback = true
        )
    }

    private data class BuiltSingleFrame(
        val views: RemoteViews?,
        val renderedBitmapToCache: Bitmap? = null,
        val hadTransientDecodeFailure: Boolean = false,
        val usedDiskFallback: Boolean = false
    )

    private fun buildSingleFrameRemoteViews(
        context: Context,
        widgetId: Int,
        sizeDp: SizeF,
        settings: PhotoWidgetSettings,
        selection: WidgetResolvedSelection?,
        today: LocalDate,
        scaleDown: Float,
        allowCachedBitmapFallback: Boolean
    ): BuiltSingleFrame {
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
        val slotDecodeEdge = if (prePattern.maxSlots > 1) {
            (max(renderW, renderH) * 0.78f).roundToInt().coerceIn(200, 640)
        } else {
            max(renderW, renderH).coerceIn(240, 840)
        }
        val decodedRaw = decodePhotos(context, rawPhotos, prePattern.maxSlots, slotDecodeEdge)

        // If MediaStore reported photos, yet none could be decoded (transient storage/permission hiccup),
        // fall back to the last good rendered bitmap on disk instead of wiping out the widget!
        if (rawPhotos.isNotEmpty() && decodedRaw.isEmpty()) {
            if (allowCachedBitmapFallback) {
                val cached = readCachedRender(context, widgetId)
                if (cached != null) {
                    setVisible(views, R.id.widget_loading_placeholder, false)
                    views.setImageViewBitmap(R.id.widget_rendered_image, cached)
                    setVisible(views, R.id.widget_empty_state, false)
                    views.setOnClickPendingIntent(
                        android.R.id.background,
                        buildTapIntent(context, widgetId, slot = 0, photo = rawPhotos.firstOrNull(), settings = settings)
                    )
                    return BuiltSingleFrame(views = views, hadTransientDecodeFailure = true, usedDiskFallback = true)
                }
            }
            return BuiltSingleFrame(views = null, hadTransientDecodeFailure = true, usedDiskFallback = false)
        }

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

        val rendered = try {
            HyperOsGlassRenderer.renderWidgetBitmap(
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
        } finally {
            decodedRaw.forEach { it.second.recycle() }
        }

        setVisible(views, R.id.widget_loading_placeholder, false)
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

        return BuiltSingleFrame(
            views = views,
            renderedBitmapToCache = if (!isEmpty) rendered else null
        )
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
        val decodeEdge = max(renderW, renderH).coerceIn(200, 560)

        val animation = RemoteViews(context.packageName, R.layout.widget_shuffle_animation)
        animation.setInt(
            R.id.widget_shuffled_photos,
            "setFlipInterval",
            settings.effectiveFlipSeconds * 1000
        )

        var addedFrames = 0
        var firstFrameBitmap: Bitmap? = null
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
            val rendered = try {
                HyperOsGlassRenderer.renderWidgetBitmap(
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
            } finally {
                decodedRaw.forEach { it.second.recycle() }
            }
            val leadPhoto = decoded.first().first
            if (firstFrameBitmap == null) {
                firstFrameBitmap = rendered
            }

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
            firstFrameBitmap?.let { backdrop ->
                // Same Bitmap reference is deduplicated by RemoteViews.BitmapCache -> zero extra memory!
                views.setImageViewBitmap(R.id.widget_slideshow_backdrop, backdrop)
            }
            views.addView(R.id.widget_shuffle_container, animation)
        } else {
            val emptyBackdrop = HyperOsGlassRenderer.renderWidgetBitmap(
                photos = emptyList(),
                pattern = ResolvedTilePattern.SINGLE,
                widthPx = renderW,
                heightPx = renderH,
                density = renderDensity,
                settings = settings,
                caption = null,
                date = today,
                reserveBottomRightButton = false,
                reserveTopLeftButton = true,
                overrideCornerRadiusDp = resolveSystemHyperCornerDp(context, settings)
            )
            views.setImageViewBitmap(R.id.widget_slideshow_backdrop, emptyBackdrop)
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

    /**
     * Computes exact pixel canvas dimensions matching [sizeDp]'s aspect ratio while staying
     * well below Android & OEM launcher RemoteViews ARGB bitmap memory/IPC budgets so widgets
     * are never silently evicted or blanked out on Xiaomi HyperOS / MIUI launchers.
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
        val screenPixels = min(displayPixels, windowPixels)
        // Stay comfortably below OEM launcher bitmap caps even with multiple placed widgets
        val totalPixelBudget = if (frameCount > 1) {
            min(screenPixels * 0.36, 480_000.0)
        } else {
            min(screenPixels * 0.45, 580_000.0)
        }
        val perFrameBudget = totalPixelBudget / frameCount.coerceAtLeast(1)

        val rawW = sizeDp.width * metrics.density * scaleDown
        val rawH = sizeDp.height * metrics.density * scaleDown
        val maxCapEdge = if (frameCount > 1) 560f else 840f

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

    private fun decodeWidgetBitmap(context: Context, uri: Uri, targetMax: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    val maxDim = maxOf(info.size.width, info.size.height)
                    if (maxDim > targetMax) {
                        val scale = targetMax.toFloat() / maxDim
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()
            if (decoded != null) return decoded
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > targetMax) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.getOrNull()
    }

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
        WidgetPhotoSource.PEOPLE_AND_PETS -> if (settings.selectedPersonIds.size >= 2 &&
            settings.personMatchMode == WidgetPersonMatchMode.TOGETHER
        ) {
            context.getString(R.string.widget_empty_people_together)
        } else {
            context.getString(R.string.widget_empty_people_and_pets)
        }
        WidgetPhotoSource.TAGS_AND_ANIMALS -> context.getString(R.string.widget_empty_tags_and_animals)
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
        private const val SLIDESHOW_FRAME_COUNT = 4
        private const val BROADCAST_RENDER_TIMEOUT_MS = 7_500L
        private const val COLD_START_RECOVERY_MIN_AGE_MS = 5L * 60L * 1000L
        private const val RENDER_CACHE_DIR = "widget_renders"
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private val widgetLocks = ConcurrentHashMap<Int, Mutex>()

        internal fun resolvePrimaryWidgetSizeDp(
            context: Context,
            options: Bundle,
            kind: PhotoWidgetKind
        ): SizeF {
            val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            return resolveWidgetSizesDpForKind(options, kind, isLandscape = isLandscape).first()
        }

        internal fun resolveWidgetSizesDpForKind(
            options: Bundle,
            kind: PhotoWidgetKind,
            isLandscape: Boolean = false
        ): List<SizeF> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                val reported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                } else {
                    options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                }
                val valid = reported?.filter { it.width >= 32f && it.height >= 32f }.orEmpty()
                if (valid.isNotEmpty()) {
                    return if (isLandscape) {
                        valid.sortedByDescending { it.width }
                    } else {
                        valid.sortedByDescending { it.height }
                    }
                }
            }
            val (defW, defH) = when (kind) {
                PhotoWidgetKind.COLLAGE -> 320f to 165f
                PhotoWidgetKind.FRAME -> 160f to 160f
            }
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val w = (when {
                isLandscape && maxW > 0 -> maxW.toFloat()
                minW > 0 -> minW.toFloat()
                maxW > 0 -> maxW.toFloat()
                else -> defW
            }).coerceAtLeast(48f)
            val h = (when {
                isLandscape && minH > 0 -> minH.toFloat()
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
            LuminaPhotoCollageWidgetProvider()
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

        /**
         * Lightweight non-advancing recovery when the app process cold-starts, immediately
         * restoring any widget whose RemoteViews was evicted by the OEM launcher.
         */
        fun scheduleColdStartRecovery(context: Context) {
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val manager = AppWidgetManager.getInstance(appContext)
                val prefs = appContext.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                for (provider in allProviders()) {
                    val ids = manager.getAppWidgetIds(ComponentName(appContext, provider.javaClass))
                    val staleIds = ids.filter { id ->
                        val lastPush = prefs.getLong("last_push_$id", 0L)
                        lastPush <= 0L || (now - lastPush) >= COLD_START_RECOVERY_MIN_AGE_MS
                    }.toIntArray()
                    if (staleIds.isEmpty()) continue
                    runCatching {
                        provider.renderWidgets(appContext, manager, staleIds, advance = false, checkIntervalDue = true)
                    }.onFailure {
                        Log.w(TAG, "Cold-start recovery failed for ${provider.kind}", it)
                    }
                }
            }
        }

        fun refreshOnExclusionsChanged(context: Context, exclusions: Flow<Set<String>>) {
            CoroutineScope(Dispatchers.IO).launch {
                exclusions.distinctUntilChanged().drop(1).collect {
                    clearRenderCache(context)
                    refreshAllWidgets(context, advance = false)
                }
            }
        }

        internal fun deleteCachedRender(context: Context, widgetId: Int) {
            runCatching {
                cacheFileFor(context, widgetId).delete()
            }
        }

        internal fun clearRenderCache(context: Context) {
            runCatching {
                File(context.noBackupFilesDir, RENDER_CACHE_DIR).deleteRecursively()
            }
        }

        private fun cacheFileFor(context: Context, widgetId: Int): File {
            val dir = File(context.noBackupFilesDir, RENDER_CACHE_DIR)
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "widget_$widgetId.png")
        }

        private fun saveCachedRender(context: Context, widgetId: Int, bitmap: Bitmap) {
            runCatching {
                val target = cacheFileFor(context, widgetId)
                val tmp = File(target.parentFile, "widget_${widgetId}.tmp")
                FileOutputStream(tmp).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }
        }

        private fun readCachedRender(context: Context, widgetId: Int): Bitmap? = runCatching {
            val file = cacheFileFor(context, widgetId)
            if (!file.isFile || file.length() <= 0L) return@runCatching null
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }
}

internal class LuminaPhotoCollageWidgetProvider : LuminaPhotoWidgetProvider() {
    override val kind = PhotoWidgetKind.COLLAGE
}

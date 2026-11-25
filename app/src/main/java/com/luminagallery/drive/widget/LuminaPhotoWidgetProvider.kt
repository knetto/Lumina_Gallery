package com.luminagallery.drive.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
import com.luminagallery.drive.LuminaApplication
import com.luminagallery.drive.MainActivity
import com.luminagallery.drive.R
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.random.Random

internal enum class PhotoWidgetLayout(val layoutId: Int, val thumbnailEdge: Int) {
    FRAME(R.layout.widget_lumina_photo, 720),
    STRIP(R.layout.widget_lumina_photo_strip, 480),
    COLLAGE(R.layout.widget_lumina_photo_collage, 520),
    SHUFFLE(R.layout.widget_lumina_photo_shuffle, 720),
    MEMORIES(R.layout.widget_lumina_photo_collage, 520)
}

/** The original provider name keeps existing Photo Frames installed across upgrades. */
internal open class LuminaPhotoWidgetProvider : AppWidgetProvider() {
    protected open val layout = PhotoWidgetLayout.FRAME

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = updateWidgets(context, appWidgetManager, appWidgetIds, advance = layout == PhotoWidgetLayout.MEMORIES)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) = updateWidgets(context, appWidgetManager, intArrayOf(appWidgetId))

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NEXT_PHOTO) {
            super.onReceive(context, intent)
            return
        }
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetInfo(widgetId)?.provider != ComponentName(context, javaClass)) return
        updateWidgets(context, manager, intArrayOf(widgetId), advance = true)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (id in appWidgetIds) {
            editor.remove("idx_$id")
            editor.remove("seconds_$id")
            editor.remove("source_$id")
        }
        editor.apply()
    }

    private fun updateWidgets(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
        advance: Boolean = false
    ) {
        if (widgetIds.isEmpty()) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                renderWidgets(context, manager, widgetIds, advance)
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
        advance: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val provider = ComponentName(appContext, this@LuminaPhotoWidgetProvider.javaClass)
        updateMutex.withLock {
            val candidates = queryCandidatePhotos(appContext)
            for (widgetId in widgetIds) {
                if (manager.getAppWidgetInfo(widgetId)?.provider != provider) continue
                updateSingleWidget(appContext, manager, widgetId, advance, candidates)
            }
        }
    }

    private fun updateSingleWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        advance: Boolean,
        candidates: WidgetPhotos
    ) {
        val options = manager.getAppWidgetOptions(widgetId)
        val defaultWidth = if (layout == PhotoWidgetLayout.FRAME || layout == PhotoWidgetLayout.SHUFFLE) 140 else 250
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: defaultWidth
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
        val settings = PhotoWidgetSettings.load(context, widgetId)
        if (layout == PhotoWidgetLayout.SHUFFLE) {
            val bitmapEdge = widgetThumbnailEdge(context, options, widthDp, heightDp, SHUFFLE_FRAMES)
            renderShuffle(context, manager, widgetId, widthDp, settings, candidates.special, bitmapEdge)
            return
        }

        val slots = when (layout) {
            PhotoWidgetLayout.FRAME -> 1
            PhotoWidgetLayout.STRIP -> if (widthDp >= 195) 3 else 2
            else -> 3
        }
        val memories = if (layout == PhotoWidgetLayout.MEMORIES) {
            buildWidgetMemories(candidates.special, candidates.locations, settings.memorySource)
        } else emptyList()
        val regularPhotos = when (layout) {
            PhotoWidgetLayout.FRAME -> candidates.favorites.ifEmpty { candidates.recent }
            PhotoWidgetLayout.MEMORIES -> emptyList()
            else -> candidates.favorites + candidates.recent
        }
        val choiceCount = if (layout == PhotoWidgetLayout.MEMORIES) memories.size else regularPhotos.size
        val prefs = context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
        val indexKey = "idx_$widgetId"
        val previousIndex = prefs.getInt(indexKey, -1)
        val targetIndex = if (choiceCount > 0) {
            Math.floorMod(if (previousIndex < 0) 0 else previousIndex + if (advance) 1 else 0, choiceCount)
        } else 0
        if (choiceCount > 0) prefs.edit().putInt(indexKey, targetIndex).apply()
        val memory = memories.getOrNull(targetIndex)
        val pool = memory?.photos ?: regularPhotos
        val photoStart = if (memory != null) 0 else targetIndex
        val shown = if (pool.isEmpty()) emptyList() else {
            val bitmapEdge = widgetThumbnailEdge(context, options, widthDp, heightDp, slots)
            decodePhotos(context, pool, photoStart, slots, bitmapEdge, slots + 4)
        }
        val firstPhoto = shown.firstOrNull()?.first
        val views = RemoteViews(context.packageName, layout.layoutId)
        views.setOnClickPendingIntent(android.R.id.background, openPhoto(context, widgetId, 0, firstPhoto))

        val imageSlots = if (layout == PhotoWidgetLayout.FRAME) {
            listOf(R.id.widget_image)
        } else {
            listOf(R.id.widget_image, R.id.widget_image_two, R.id.widget_image_three)
        }
        imageSlots.forEachIndexed { slot, viewId ->
            val rendered = shown.getOrNull(slot)
            setVisible(views, viewId, rendered != null)
            views.setImageViewBitmap(viewId, rendered?.second)
            if (rendered != null) {
                val photo = rendered.first
                views.setContentDescription(
                    viewId,
                    context.getString(R.string.widget_photo_details, photo.albumName, widgetDateLabel(photo.takenAtMs))
                )
                views.setOnClickPendingIntent(viewId, openPhoto(context, widgetId, slot, photo))
            }
        }

        if (layout == PhotoWidgetLayout.FRAME) {
            setVisible(views, R.id.widget_empty_icon, firstPhoto == null)
            setVisible(views, R.id.widget_caption, widthDp >= 100 && heightDp >= 90)
            views.setTextViewText(R.id.widget_title, firstPhoto?.albumName ?: context.getString(R.string.app_name))
            views.setTextViewText(
                R.id.widget_subtitle,
                firstPhoto?.let { widgetDateLabel(it.takenAtMs) } ?: context.getString(R.string.widget_open_gallery)
            )
        } else {
            setVisible(views, R.id.widget_empty_label, shown.isEmpty())
            if (layout == PhotoWidgetLayout.COLLAGE || layout == PhotoWidgetLayout.MEMORIES) {
                setVisible(views, R.id.widget_side_photos, shown.size > 1)
            }
            if (layout == PhotoWidgetLayout.MEMORIES) {
                setVisible(views, R.id.widget_caption, memory != null && shown.isNotEmpty() && heightDp >= 90)
                views.setTextViewText(R.id.widget_title, memory?.title.orEmpty())
                views.setTextViewText(R.id.widget_subtitle, memory?.subtitle.orEmpty())
                setVisible(views, R.id.widget_subtitle, memory?.subtitle != null && heightDp >= 124)
                views.setTextViewText(
                    R.id.widget_empty_label,
                    context.getString(
                        if (settings.memorySource == WidgetMemorySource.TRIPS) R.string.widget_empty_trips
                        else R.string.widget_empty_memories
                    )
                )
                bindSettings(context, views, widgetId, visible = widthDp >= 96)
            }
        }

        val hasNext = if (layout == PhotoWidgetLayout.MEMORIES) {
            memories.size > 1
        } else {
            pool.size > shown.size && (layout != PhotoWidgetLayout.FRAME || widthDp >= 105)
        }
        setVisible(views, R.id.widget_next_btn, hasNext)
        if (hasNext) {
            val nextIntent = Intent(context, javaClass).apply {
                action = ACTION_NEXT_PHOTO
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            views.setOnClickPendingIntent(
                R.id.widget_next_btn,
                PendingIntent.getBroadcast(context, widgetId, nextIntent, PENDING_FLAGS)
            )
        }
        manager.updateAppWidget(widgetId, views)
    }

    private fun renderShuffle(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        widthDp: Int,
        settings: PhotoWidgetSettings,
        randomPhotos: List<WidgetPhoto>,
        bitmapEdge: Int
    ) {
        val frames = decodePhotos(context, randomPhotos.shuffled(), 0, SHUFFLE_FRAMES, bitmapEdge, randomPhotos.size)
        val views = RemoteViews(context.packageName, layout.layoutId)
        views.removeAllViews(R.id.widget_shuffle_container)
        if (frames.isNotEmpty()) {
            val animation = RemoteViews(context.packageName, R.layout.widget_shuffle_animation)
            val seconds = if (frames.size == 1) MAX_SHUFFLE_SECONDS else settings.shuffleSeconds
            animation.setInt(R.id.widget_shuffled_photos, "setFlipInterval", seconds * 1000)
            frames.forEachIndexed { slot, (photo, bitmap) ->
                val frame = RemoteViews(context.packageName, R.layout.widget_shuffle_frame)
                frame.setImageViewBitmap(R.id.widget_image, bitmap)
                frame.setContentDescription(
                    R.id.widget_image,
                    context.getString(R.string.widget_photo_details, photo.albumName, widgetDateLabel(photo.takenAtMs))
                )
                frame.setOnClickPendingIntent(R.id.widget_image, openPhoto(context, widgetId, slot, photo))
                animation.addView(R.id.widget_shuffled_photos, frame)
            }
            views.addView(R.id.widget_shuffle_container, animation)
        }
        setVisible(views, R.id.widget_empty_icon, frames.isEmpty())
        views.setOnClickPendingIntent(android.R.id.background, openPhoto(context, widgetId, -1, null))
        bindSettings(context, views, widgetId, visible = widthDp >= 96 || frames.isEmpty())
        manager.updateAppWidget(widgetId, views)
    }

    private fun decodePhotos(
        context: Context,
        pool: List<WidgetPhoto>,
        start: Int,
        slots: Int,
        bitmapEdge: Int,
        attempts: Int
    ): List<Pair<WidgetPhoto, Bitmap>> = buildList {
        for (offset in 0 until minOf(pool.size, attempts)) {
            val photo = pool[(start + offset) % pool.size]
            val bitmap = decodeWidgetBitmap(context, Uri.parse(photo.uri), bitmapEdge) ?: continue
            add(photo to bitmap)
            if (size == slots) break
        }
    }

    private fun bindSettings(context: Context, views: RemoteViews, widgetId: Int, visible: Boolean) {
        setVisible(views, R.id.widget_settings_btn, visible)
        val intent = Intent(context, PhotoWidgetSettingsActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        views.setOnClickPendingIntent(
            R.id.widget_settings_btn,
            PendingIntent.getActivity(context, widgetId, intent, PENDING_FLAGS)
        )
    }

    private fun widgetThumbnailEdge(context: Context, options: Bundle, widthDp: Int, heightDp: Int, slots: Int): Int {
        val metrics = context.resources.displayMetrics
        val widgetEdgePx = (maxOf(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).coerceAtLeast(widthDp),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).coerceAtLeast(heightDp)
        ) * metrics.density).toInt()
        val windowBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        } else null
        val displayPixels = metrics.widthPixels.toDouble() * metrics.heightPixels
        val windowPixels = windowBounds?.let { it.width().toDouble() * it.height() } ?: displayPixels
        // Keep total ARGB bitmap pixels below one screen's worth across all photo slots.
        val budgetEdge = sqrt(minOf(displayPixels, windowPixels) / slots).toInt()
        return minOf(widgetEdgePx, budgetEdge, layout.thumbnailEdge).coerceAtLeast(1)
    }

    private fun openPhoto(context: Context, widgetId: Int, slot: Int, photo: WidgetPhoto?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("lumina-widget://photo/$widgetId/$slot")
            photo?.let { putExtra(EXTRA_WIDGET_OPEN_ITEM_ID, it.luminaItemId) }
        }
        return PendingIntent.getActivity(context, widgetId, intent, PENDING_FLAGS)
    }

    private fun setVisible(views: RemoteViews, id: Int, visible: Boolean) {
        views.setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
    }

    private data class WidgetPhotos(
        val favorites: List<WidgetPhoto> = emptyList(),
        val recent: List<WidgetPhoto> = emptyList(),
        val special: List<WidgetPhoto> = emptyList(),
        val locations: Map<String, PhotoLocationEntity> = emptyMap()
    )

    private suspend fun queryCandidatePhotos(context: Context): WidgetPhotos {
        val extrasDao = (context.applicationContext as? LuminaApplication)
            ?.container?.database?.galleryExtrasDao() ?: return WidgetPhotos()
        val blockedBucketIds = runCatching {
            (extrasDao.getAllLockedAlbumIds() + extrasDao.getAllHiddenAlbumIds()).toHashSet()
        }.getOrElse {
            Log.w(TAG, "Unable to check widget photo exclusions", it)
            return WidgetPhotos()
        }
        val discoverFavorites = layout != PhotoWidgetLayout.SHUFFLE && layout != PhotoWidgetLayout.MEMORIES
        val roomFavoriteIds = if (discoverFavorites) {
            runCatching { extrasDao.getAllFavoriteItemIds().toHashSet() }.getOrDefault(emptySet())
        } else emptySet()
        val locations = if (layout == PhotoWidgetLayout.MEMORIES) {
            runCatching { extrasDao.observeCachedLocations().first().associateBy { it.itemId } }
                .getOrDefault(emptyMap())
        } else emptyMap()

        val favorites = ArrayList<WidgetPhoto>(40)
        val fallback = ArrayList<WidgetPhoto>(40)
        val special = ArrayList<WidgetPhoto>()
        var seenForShuffle = 0
        var olderLocatedPhotos = 0

        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.BUCKET_ID)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_FAVORITE)
            }
        }.toTypedArray()

        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.SIZE} > 0",
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketIdCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
                val bucketCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val takenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val favCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
                } else -1

                while (c.moveToNext()) {
                    if (discoverFavorites && favorites.size >= 40 && fallback.size >= 40) break
                    if (layout == PhotoWidgetLayout.MEMORIES && special.size >= RECENT_MEMORY_PHOTOS &&
                        (locations.isEmpty() || olderLocatedPhotos >= RECENT_MEMORY_PHOTOS)
                    ) break
                    val rawBucket = if (bucketIdCol >= 0) c.getString(bucketIdCol) else null
                    val luminaBucketId = "${LocalMediaRepository.LOCAL_BUCKET_PREFIX}${rawBucket ?: "default"}"
                    // Never leak locked or hidden albums onto the Home Screen widget!
                    if (luminaBucketId in blockedBucketIds) continue

                    val id = c.getLong(idCol)
                    val luminaItemId = "${LocalMediaRepository.LOCAL_ITEM_PREFIX}$id"
                    val isFav = luminaItemId in roomFavoriteIds || (favCol >= 0 && c.getInt(favCol) == 1)
                    if (discoverFavorites && ((!isFav && fallback.size >= 40) || (isFav && favorites.size >= 40))) continue
                    val isOlderLocatedPhoto = layout == PhotoWidgetLayout.MEMORIES &&
                        special.size >= RECENT_MEMORY_PHOTOS && locations.containsKey(luminaItemId)
                    if (layout == PhotoWidgetLayout.MEMORIES && special.size >= RECENT_MEMORY_PHOTOS &&
                        !isOlderLocatedPhoto
                    ) continue
                    val shuffleSlot = if (layout == PhotoWidgetLayout.SHUFFLE) {
                        val slot = Random.nextInt(++seenForShuffle)
                        if (slot >= SHUFFLE_SAMPLE) continue
                        slot
                    } else -1

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val album = if (bucketCol >= 0) c.getString(bucketCol) ?: "Photos" else "Photos"
                    val taken = if (takenCol >= 0) c.getLong(takenCol) else 0L
                    val ms = if (taken > 0L) taken else c.getLong(modCol) * 1000L
                    val photo = WidgetPhoto(
                        luminaItemId = luminaItemId,
                        uri = uri,
                        albumName = album,
                        takenAtMs = ms
                    )
                    when (layout) {
                        PhotoWidgetLayout.SHUFFLE -> {
                            if (special.size < SHUFFLE_SAMPLE) special += photo else special[shuffleSlot] = photo
                        }
                        PhotoWidgetLayout.MEMORIES -> {
                            special += photo
                            if (isOlderLocatedPhoto) olderLocatedPhotos++
                        }
                        else -> if (isFav) favorites.add(photo) else fallback.add(photo)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Unable to query widget photos", it) }
        return WidgetPhotos(favorites, fallback, special, locations)
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

    companion object {
        const val EXTRA_WIDGET_OPEN_ITEM_ID = "com.luminagallery.drive.EXTRA_WIDGET_OPEN_ITEM_ID"
        private const val ACTION_NEXT_PHOTO = "com.luminagallery.drive.widget.ACTION_NEXT_PHOTO"
        private const val TAG = "LuminaPhotoWidget"
        private const val DEFAULT_HEIGHT_DP = 140
        private const val SHUFFLE_FRAMES = 10
        private const val SHUFFLE_SAMPLE = 16
        private const val RECENT_MEMORY_PHOTOS = 1500
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private val updateMutex = Mutex()

        fun refreshOnExclusionsChanged(context: Context, exclusions: Flow<Set<String>>) {
            CoroutineScope(Dispatchers.IO).launch {
                exclusions.distinctUntilChanged().drop(1).collect {
                    val manager = AppWidgetManager.getInstance(context)
                    val providers = listOf(
                        LuminaPhotoWidgetProvider(),
                        LuminaPhotoStripWidgetProvider(),
                        LuminaPhotoCollageWidgetProvider(),
                        LuminaPhotoShuffleWidgetProvider(),
                        LuminaMemoriesWidgetProvider()
                    )
                    for (provider in providers) {
                        val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
                        if (ids.isEmpty()) continue
                        runCatching { provider.renderWidgets(context, manager, ids) }
                            .onFailure { Log.w(TAG, "Unable to refresh excluded widget photos", it) }
                    }
                }
            }
        }
    }
}

internal class LuminaPhotoStripWidgetProvider : LuminaPhotoWidgetProvider() {
    override val layout = PhotoWidgetLayout.STRIP
}

internal class LuminaPhotoCollageWidgetProvider : LuminaPhotoWidgetProvider() {
    override val layout = PhotoWidgetLayout.COLLAGE
}

internal class LuminaPhotoShuffleWidgetProvider : LuminaPhotoWidgetProvider() {
    override val layout = PhotoWidgetLayout.SHUFFLE
}

internal class LuminaMemoriesWidgetProvider : LuminaPhotoWidgetProvider() {
    override val layout = PhotoWidgetLayout.MEMORIES
}

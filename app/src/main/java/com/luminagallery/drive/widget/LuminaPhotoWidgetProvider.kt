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
import android.provider.MediaStore
import android.widget.RemoteViews
import com.luminagallery.drive.LuminaApplication
import com.luminagallery.drive.MainActivity
import com.luminagallery.drive.R
import com.luminagallery.drive.data.local.LocalMediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Samsung One UI-style rounded Home Screen Photo Widget for Lumina Gallery:
 *  - Cycles cleanly through Favorites (falling back to recent device photos automatically).
 *  - Tapping the photo opens Lumina Gallery directly.
 *  - Tapping the top-right cycle button loads the next memory immediately.
 */
class LuminaPhotoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (widgetId in appWidgetIds) {
                    updateSingleWidget(context, appWidgetManager, widgetId, advanceIndex = false)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_NEXT_PHOTO) {
            val widgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val manager = AppWidgetManager.getInstance(context)
                    if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        updateSingleWidget(context, manager, widgetId, advanceIndex = true)
                    } else {
                        val ids = manager.getAppWidgetIds(
                            ComponentName(context, LuminaPhotoWidgetProvider::class.java)
                        )
                        ids.forEach { updateSingleWidget(context, manager, it, advanceIndex = true) }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (id in appWidgetIds) {
            editor.remove("idx_$id")
        }
        editor.apply()
    }

    private suspend fun updateSingleWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        advanceIndex: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_lumina_photo)

        // Tap next button -> cycle to next favorite/memory photo
        val nextIntent = Intent(context, LuminaPhotoWidgetProvider::class.java).apply {
            action = ACTION_NEXT_PHOTO
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val nextPending = PendingIntent.getBroadcast(
            context,
            widgetId + 10_000,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_next_btn, nextPending)

        val candidates = queryCandidatePhotos(context)
        if (candidates.isEmpty()) {
            val openHomeIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                widgetId,
                openHomeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPending)
            views.setImageViewResource(R.id.widget_image, R.drawable.widget_bg_rounded)
            views.setTextViewText(R.id.widget_title, "Lumina Gallery")
            views.setTextViewText(R.id.widget_subtitle, "Tap to view your photos")
            manager.updateAppWidget(widgetId, views)
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentIdx = prefs.getInt("idx_$widgetId", 0)
        val targetIdx = if (advanceIndex) {
            (currentIdx + 1) % candidates.size
        } else {
            currentIdx % candidates.size
        }
        prefs.edit().putInt("idx_$widgetId", targetIdx).apply()

        val chosen = candidates[targetIdx]

        // Tap photo -> deep-link directly into the displayed photo in Lumina Viewer
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_WIDGET_OPEN_ITEM_ID, chosen.luminaItemId)
        }
        val openPending = PendingIntent.getActivity(
            context,
            widgetId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPending)

        val bitmap = decodeWidgetBitmap(context, chosen.uri)
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_image, bitmap)
        }
        views.setTextViewText(R.id.widget_title, chosen.albumName)
        views.setTextViewText(R.id.widget_subtitle, chosen.dateLabel)
        manager.updateAppWidget(widgetId, views)
    }

    private data class WidgetPhoto(
        val luminaItemId: String,
        val uri: Uri,
        val albumName: String,
        val dateLabel: String
    )

    private suspend fun queryCandidatePhotos(context: Context): List<WidgetPhoto> {
        val extrasDao = (context.applicationContext as? LuminaApplication)
            ?.container?.database?.galleryExtrasDao()
        val blockedBucketIds = HashSet<String>()
        val roomFavoriteIds = HashSet<String>()
        if (extrasDao != null) {
            runCatching {
                blockedBucketIds.addAll(extrasDao.getAllLockedAlbumIds())
                blockedBucketIds.addAll(extrasDao.getAllHiddenAlbumIds())
                roomFavoriteIds.addAll(extrasDao.getAllFavoriteItemIds())
            }
        }

        val favorites = ArrayList<WidgetPhoto>(40)
        val fallback = ArrayList<WidgetPhoto>(40)
        val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

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

                while (c.moveToNext() && (favorites.size < 40 || fallback.size < 40)) {
                    val rawBucket = if (bucketIdCol >= 0) c.getString(bucketIdCol) else null
                    val luminaBucketId = "${LocalMediaRepository.LOCAL_BUCKET_PREFIX}${rawBucket ?: "default"}"
                    // Never leak locked or hidden albums onto the Home Screen widget!
                    if (luminaBucketId in blockedBucketIds) continue

                    val id = c.getLong(idCol)
                    val luminaItemId = "${LocalMediaRepository.LOCAL_ITEM_PREFIX}$id"
                    val isFav = luminaItemId in roomFavoriteIds || (favCol >= 0 && c.getInt(favCol) == 1)
                    if (!isFav && fallback.size >= 40) continue
                    if (isFav && favorites.size >= 40) continue

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val album = if (bucketCol >= 0) c.getString(bucketCol) ?: "Photos" else "Photos"
                    val taken = if (takenCol >= 0) c.getLong(takenCol) else 0L
                    val ms = if (taken > 0L) taken else c.getLong(modCol) * 1000L
                    val photo = WidgetPhoto(
                        luminaItemId = luminaItemId,
                        uri = uri,
                        albumName = album,
                        dateLabel = dateFmt.format(Date(ms.coerceAtLeast(0L)))
                    )
                    if (isFav) favorites.add(photo) else fallback.add(photo)
                }
            }
        }
        return favorites.ifEmpty { fallback }
    }

    private fun decodeWidgetBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
        val targetMax = 768
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
        const val ACTION_NEXT_PHOTO = "com.luminagallery.drive.widget.ACTION_NEXT_PHOTO"
        const val EXTRA_WIDGET_OPEN_ITEM_ID = "com.luminagallery.drive.EXTRA_WIDGET_OPEN_ITEM_ID"
        private const val PREFS_NAME = "lumina_widget_prefs"
    }
}

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
import com.luminagallery.drive.MainActivity
import com.luminagallery.drive.R
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

    private fun updateSingleWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        advanceIndex: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_lumina_photo)

        // Tap photo -> open Lumina Gallery
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            widgetId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPending)

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
        val bitmap = decodeWidgetBitmap(context, chosen.uri)
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_image, bitmap)
        }
        views.setTextViewText(R.id.widget_title, chosen.albumName)
        views.setTextViewText(R.id.widget_subtitle, chosen.dateLabel)
        manager.updateAppWidget(widgetId, views)
    }

    private data class WidgetPhoto(
        val uri: Uri,
        val albumName: String,
        val dateLabel: String
    )

    private fun queryCandidatePhotos(context: Context): List<WidgetPhoto> {
        val list = ArrayList<WidgetPhoto>(40)
        val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        fun query(favoritesOnly: Boolean) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED
            )
            val selection = if (favoritesOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "${MediaStore.MediaColumns.IS_FAVORITE} = 1 AND ${MediaStore.MediaColumns.SIZE} > 0"
            } else {
                "${MediaStore.MediaColumns.SIZE} > 0"
            }
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val bucketCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val takenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    while (c.moveToNext() && list.size < 40) {
                        val id = c.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        val album = if (bucketCol >= 0) c.getString(bucketCol) ?: "Photos" else "Photos"
                        val taken = if (takenCol >= 0) c.getLong(takenCol) else 0L
                        val ms = if (taken > 0L) taken else c.getLong(modCol) * 1000L
                        list.add(WidgetPhoto(uri, album, dateFmt.format(Date(ms.coerceAtLeast(0L)))))
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            query(favoritesOnly = true)
        }
        if (list.isEmpty()) {
            query(favoritesOnly = false)
        }
        return list
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
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    companion object {
        const val ACTION_NEXT_PHOTO = "com.luminagallery.drive.widget.ACTION_NEXT_PHOTO"
        private const val PREFS_NAME = "lumina_widget_prefs"
    }
}

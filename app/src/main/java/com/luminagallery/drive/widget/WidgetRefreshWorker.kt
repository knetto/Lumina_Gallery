package com.luminagallery.drive.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Battery-friendly WorkManager worker that advances scheduled Lumina widgets when their
 * configured shuffle interval elapses, refreshes ViewFlipper slideshow batches periodically,
 * and keeps live Date badges accurate across midnight.
 */
class LuminaWidgetRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val manager = AppWidgetManager.getInstance(context)
        val prefs = context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val todayStr = LocalDate.now().toString()
        var anyNeedsSchedule = false

        for (provider in LuminaPhotoWidgetProvider.allProviders()) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
            for (widgetId in ids) {
                val settings = PhotoWidgetSettings.load(context, widgetId, provider.kind)
                val interval = settings.shuffleInterval
                val dateChanged = settings.showDateBadge && prefs.getString("last_date_$widgetId", null) != todayStr
                val autoEnabled = interval != WidgetShuffleInterval.MANUAL

                if (autoEnabled || settings.showDateBadge) {
                    anyNeedsSchedule = true
                }

                val lastAdvance = prefs.getLong("last_advance_$widgetId", 0L)
                val targetWaitMs = when {
                    interval.isFastSlideshow -> TimeUnit.MINUTES.toMillis(30) // Deal a fresh random batch every 30m
                    interval == WidgetShuffleInterval.MANUAL -> Long.MAX_VALUE
                    else -> interval.intervalMs
                }
                val isDue = autoEnabled && (now - lastAdvance >= targetWaitMs - 30_000L)

                if (isDue || dateChanged) {
                    runCatching {
                        provider.renderWidgets(
                            context = context,
                            manager = manager,
                            widgetIds = intArrayOf(widgetId),
                            advance = isDue
                        )
                    }.onFailure { err ->
                        Log.w(TAG, "Scheduled refresh failed for widget $widgetId", err)
                    }
                }
            }
        }

        if (!anyNeedsSchedule) {
            WidgetRefreshScheduler.cancel(context)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "LuminaWidgetWorker"
    }
}

internal object WidgetRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "lumina_widget_auto_shuffle"

    fun syncSchedule(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        var shouldSchedule = false

        for (provider in LuminaPhotoWidgetProvider.allProviders()) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
            for (id in ids) {
                val settings = PhotoWidgetSettings.load(context, id, provider.kind)
                if (settings.shuffleInterval != WidgetShuffleInterval.MANUAL || settings.showDateBadge) {
                    shouldSchedule = true
                    break
                }
            }
            if (shouldSchedule) break
        }

        if (!shouldSchedule) {
            cancel(context)
            return
        }

        val request = PeriodicWorkRequestBuilder<LuminaWidgetRefreshWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        ).build()

        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}

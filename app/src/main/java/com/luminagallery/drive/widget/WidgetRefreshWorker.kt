package com.luminagallery.drive.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Battery-friendly WorkManager worker that:
 *  - advances scheduled Lumina widgets when their configured shuffle interval elapses,
 *  - refreshes ViewFlipper slideshow batches periodically,
 *  - keeps live Date badges accurate across midnight, and
 *  - sends a lightweight non-advancing keep-alive push so OEM launchers (e.g. Xiaomi HyperOS)
 *    never leave a widget blank/invisible after purging RemoteViews bitmaps from memory.
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
        val forceRefresh = inputData.getBoolean(KEY_FORCE_REFRESH, false)
        var anyWidgetsPlaced = false

        for (provider in LuminaPhotoWidgetProvider.allProviders()) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
            for (widgetId in ids) {
                anyWidgetsPlaced = true
                val settings = PhotoWidgetSettings.load(context, widgetId, provider.kind)
                val interval = settings.shuffleInterval
                val dateChanged = settings.showDateBadge && prefs.getString("last_date_$widgetId", null) != todayStr
                val autoEnabled = interval != WidgetShuffleInterval.MANUAL

                val lastAdvance = prefs.getLong("last_advance_$widgetId", 0L)
                val isDue = autoEnabled && LuminaPhotoWidgetProvider.isWidgetAdvanceDue(interval, lastAdvance, now)

                val lastPush = prefs.getLong("last_push_$widgetId", 0L)
                val needsKeepAlive = shouldTriggerKeepAliveRefresh(lastPush, now)

                if (forceRefresh || isDue || dateChanged || needsKeepAlive) {
                    runCatching {
                        provider.renderWidgets(
                            context = context,
                            manager = manager,
                            widgetIds = intArrayOf(widgetId),
                            advance = isDue,
                            checkIntervalDue = false
                        )
                    }.onFailure { err ->
                        Log.w(TAG, "Scheduled refresh failed for widget $widgetId", err)
                    }
                }
            }
        }

        if (!anyWidgetsPlaced) {
            WidgetRefreshScheduler.cancel(context)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "LuminaWidgetWorker"
        internal const val KEY_FORCE_REFRESH = "force_refresh"
        internal const val KEEP_ALIVE_INTERVAL_MS = 45L * 60L * 1000L

        internal fun shouldTriggerKeepAliveRefresh(lastPushMs: Long, nowMs: Long): Boolean {
            if (lastPushMs <= 0L) return true
            return nowMs - lastPushMs >= KEEP_ALIVE_INTERVAL_MS
        }
    }
}

/**
 * Self-healing receiver that re-pushes all placed home screen widgets immediately after an
 * app update (APK auto-update) or phone reboot so widgets never appear blank.
 */
class LuminaWidgetRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED || action == Intent.ACTION_BOOT_COMPLETED) {
            val appContext = context.applicationContext
            WidgetRefreshScheduler.syncSchedule(appContext)
            WidgetRefreshScheduler.enqueueImmediateRecovery(appContext)
        }
    }
}

internal object WidgetRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "lumina_widget_auto_shuffle"
    private const val RECOVERY_WORK_NAME = "lumina_widget_immediate_recovery"

    fun syncSchedule(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        var anyPlaced = false

        for (provider in LuminaPhotoWidgetProvider.allProviders()) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
            if (ids.isNotEmpty()) {
                anyPlaced = true
                break
            }
        }

        if (!anyPlaced) {
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

    fun enqueueImmediateRecovery(context: Context) {
        val request = OneTimeWorkRequestBuilder<LuminaWidgetRefreshWorker>()
            .setInputData(workDataOf(LuminaWidgetRefreshWorker.KEY_FORCE_REFRESH to true))
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                RECOVERY_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
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

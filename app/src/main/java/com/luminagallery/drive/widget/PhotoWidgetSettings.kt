package com.luminagallery.drive.widget

import android.content.Context

internal const val PHOTO_WIDGET_PREFS = "lumina_widget_prefs"
internal const val DEFAULT_SHUFFLE_SECONDS = 15
internal const val MAX_SHUFFLE_SECONDS = 3600

internal data class PhotoWidgetSettings(
    val shuffleSeconds: Int = DEFAULT_SHUFFLE_SECONDS,
    val memorySource: WidgetMemorySource = WidgetMemorySource.BOTH
) {
    fun save(context: Context, widgetId: Int) {
        context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE).edit()
            .putInt("seconds_$widgetId", shuffleSeconds)
            .putString("source_$widgetId", memorySource.name)
            .remove("idx_$widgetId")
            .apply()
    }

    companion object {
        fun load(context: Context, widgetId: Int): PhotoWidgetSettings {
            val prefs = context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
            val source = prefs.getString("source_$widgetId", null)
            return PhotoWidgetSettings(
                shuffleSeconds = prefs.getInt("seconds_$widgetId", DEFAULT_SHUFFLE_SECONDS)
                    .coerceIn(1, MAX_SHUFFLE_SECONDS),
                memorySource = WidgetMemorySource.entries.firstOrNull { it.name == source }
                    ?: WidgetMemorySource.BOTH
            )
        }
    }
}

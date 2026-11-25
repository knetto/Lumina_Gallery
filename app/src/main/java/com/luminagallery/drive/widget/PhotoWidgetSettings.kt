package com.luminagallery.drive.widget

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val PHOTO_WIDGET_PREFS = "lumina_widget_prefs"
internal const val DEFAULT_SHUFFLE_SECONDS = 15
internal const val MAX_SHUFFLE_SECONDS = 3600

internal enum class PhotoWidgetKind(
    val defaultSource: WidgetPhotoSource,
    val defaultInterval: WidgetShuffleInterval,
    val defaultShowCaption: Boolean,
    val defaultShowDateBadge: Boolean,
    val defaultShowShuffleButton: Boolean,
    val defaultShowSettingsButton: Boolean
) {
    FRAME(
        defaultSource = WidgetPhotoSource.FAVORITES_AND_RECENT,
        defaultInterval = WidgetShuffleInterval.HOUR_1,
        defaultShowCaption = false,
        defaultShowDateBadge = false,
        defaultShowShuffleButton = false,
        defaultShowSettingsButton = false
    ),
    RANDOM_SHUFFLE(
        defaultSource = WidgetPhotoSource.ALL_PHONE_RANDOM,
        defaultInterval = WidgetShuffleInterval.MIN_30,
        defaultShowCaption = false,
        defaultShowDateBadge = false,
        defaultShowShuffleButton = true,
        defaultShowSettingsButton = false
    ),
    COLLAGE(
        defaultSource = WidgetPhotoSource.SMART_GROUPS,
        defaultInterval = WidgetShuffleInterval.HOUR_1,
        defaultShowCaption = true,
        defaultShowDateBadge = false,
        defaultShowShuffleButton = true,
        defaultShowSettingsButton = false
    ),
    MEMORIES(
        defaultSource = WidgetPhotoSource.MEMORIES_MIX,
        defaultInterval = WidgetShuffleInterval.HOUR_3,
        defaultShowCaption = true,
        defaultShowDateBadge = false,
        defaultShowShuffleButton = true,
        defaultShowSettingsButton = false
    ),
    STRIP(
        defaultSource = WidgetPhotoSource.FAVORITES_AND_RECENT,
        defaultInterval = WidgetShuffleInterval.HOUR_1,
        defaultShowCaption = false,
        defaultShowDateBadge = false,
        defaultShowShuffleButton = true,
        defaultShowSettingsButton = false
    ),
    FAVORITES(
        defaultSource = WidgetPhotoSource.FAVORITES_ONLY,
        defaultInterval = WidgetShuffleInterval.HOUR_1,
        defaultShowCaption = false,
        defaultShowDateBadge = false,
        defaultShowShuffleButton = false,
        defaultShowSettingsButton = false
    ),
    PHOTO_DATE(
        defaultSource = WidgetPhotoSource.ALL_PHONE_RANDOM,
        defaultInterval = WidgetShuffleInterval.HOUR_1,
        defaultShowCaption = false,
        defaultShowDateBadge = true,
        defaultShowShuffleButton = false,
        defaultShowSettingsButton = false
    );

    val isMultiPhoto: Boolean
        get() = this == COLLAGE || this == MEMORIES || this == STRIP
}

@Serializable
internal enum class WidgetPhotoSource {
    SELECTED_PHOTOS,
    SELECTED_ALBUMS,
    ALL_PHONE_RANDOM,
    SMART_GROUPS,
    LOCATION_GROUPS,
    DAY_GROUPS,
    MEMORIES_MIX,
    FAVORITES_ONLY,
    FAVORITES_AND_RECENT
}

@Serializable
internal enum class WidgetShuffleInterval(val seconds: Int, val isFastSlideshow: Boolean) {
    MANUAL(0, false),
    SEC_15(15, true),
    SEC_30(30, true),
    MIN_1(60, true),
    MIN_15(15 * 60, false),
    MIN_30(30 * 60, false),
    HOUR_1(60 * 60, false),
    HOUR_3(3 * 60 * 60, false),
    HOUR_6(6 * 60 * 60, false),
    DAILY(24 * 60 * 60, false);

    val intervalMs: Long get() = seconds * 1000L
}

@Serializable
internal enum class WidgetFrameStyle {
    HYPER_GLASS,
    FROSTED_FRAME,
    CRYSTAL_RIM,
    BORDERLESS
}

@Serializable
internal enum class WidgetCornerStyle(val radiusDp: Float) {
    HYPER_OS(24f),
    SOFT(18f),
    COMPACT(12f)
}

@Serializable
internal enum class WidgetCollageLayout {
    AUTO,
    HERO_SIDE,
    GRID_2X2,
    BENTO_4
}

@Serializable
internal enum class WidgetCaptionStyle {
    GLASS_PILL,
    EDITORIAL_FADE
}

@Serializable
internal enum class WidgetTapAction {
    OPEN_PHOTO,
    SHUFFLE_NEXT,
    OPEN_SETTINGS
}

@Serializable
internal data class PhotoWidgetSettings(
    val source: WidgetPhotoSource = WidgetPhotoSource.FAVORITES_AND_RECENT,
    val selectedAlbumIds: List<String> = emptyList(),
    val selectedPhotoIds: List<String> = emptyList(),
    val shuffleInterval: WidgetShuffleInterval = WidgetShuffleInterval.HOUR_1,
    val customSlideshowSeconds: Int = DEFAULT_SHUFFLE_SECONDS,
    val shuffleOrder: Boolean = true,
    val groupByAlbum: Boolean = true,
    val excludeScreenshots: Boolean = false,
    val frameStyle: WidgetFrameStyle = WidgetFrameStyle.HYPER_GLASS,
    val cornerStyle: WidgetCornerStyle = WidgetCornerStyle.HYPER_OS,
    val collageLayout: WidgetCollageLayout = WidgetCollageLayout.AUTO,
    val showCaption: Boolean = false,
    val captionStyle: WidgetCaptionStyle = WidgetCaptionStyle.GLASS_PILL,
    val showDateBadge: Boolean = false,
    val showShuffleButton: Boolean = false,
    val showSettingsButton: Boolean = false,
    val tapAction: WidgetTapAction = WidgetTapAction.OPEN_PHOTO,
    val memorySource: WidgetMemorySource = WidgetMemorySource.BOTH
) {
    val effectiveFlipSeconds: Int
        get() = when {
            shuffleInterval.isFastSlideshow -> shuffleInterval.seconds
            else -> customSlideshowSeconds.coerceIn(5, MAX_SHUFFLE_SECONDS)
        }

    fun save(context: Context, widgetId: Int, resetState: Boolean = true) {
        val prefs = context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
        val encoded = widgetSettingsJson.encodeToString(this)
        prefs.edit().apply {
            putString(configKey(widgetId), encoded)
            // Keep legacy keys cleanly removed when saving new schema
            remove("seconds_$widgetId")
            remove("source_$widgetId")
            if (resetState) {
                remove("idx_$widgetId")
                remove("seed_$widgetId")
                remove("last_advance_$widgetId")
                remove("group_key_$widgetId")
            }
        }.apply()
    }

    companion object {
        private val widgetSettingsJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

        private fun configKey(widgetId: Int) = "cfg_v2_$widgetId"

        fun defaultsFor(kind: PhotoWidgetKind): PhotoWidgetSettings = PhotoWidgetSettings(
            source = kind.defaultSource,
            shuffleInterval = kind.defaultInterval,
            showCaption = kind.defaultShowCaption,
            showDateBadge = kind.defaultShowDateBadge,
            showShuffleButton = kind.defaultShowShuffleButton,
            showSettingsButton = kind.defaultShowSettingsButton,
            memorySource = WidgetMemorySource.BOTH
        )

        fun load(context: Context, widgetId: Int, kind: PhotoWidgetKind = PhotoWidgetKind.FRAME): PhotoWidgetSettings {
            val prefs = context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(configKey(widgetId), null)
            if (!raw.isNullOrBlank()) {
                runCatching {
                    return widgetSettingsJson.decodeFromString<PhotoWidgetSettings>(raw)
                }
            }
            // Migrate legacy v1 widget settings seamlessly if present
            val hasLegacySeconds = prefs.contains("seconds_$widgetId")
            val legacySource = prefs.getString("source_$widgetId", null)
            val base = defaultsFor(kind)
            if (!hasLegacySeconds && legacySource == null) {
                return base
            }
            val legacySec = prefs.getInt("seconds_$widgetId", DEFAULT_SHUFFLE_SECONDS)
                .coerceIn(1, MAX_SHUFFLE_SECONDS)
            val migratedInterval = if (hasLegacySeconds) {
                when {
                    legacySec <= 20 -> WidgetShuffleInterval.SEC_15
                    legacySec <= 45 -> WidgetShuffleInterval.SEC_30
                    legacySec <= 120 -> WidgetShuffleInterval.MIN_1
                    legacySec <= 20 * 60 -> WidgetShuffleInterval.MIN_15
                    legacySec <= 45 * 60 -> WidgetShuffleInterval.MIN_30
                    else -> WidgetShuffleInterval.HOUR_1
                }
            } else base.shuffleInterval
            val migratedMemory = WidgetMemorySource.entries.firstOrNull { it.name == legacySource }
                ?: base.memorySource
            return base.copy(
                shuffleInterval = migratedInterval,
                customSlideshowSeconds = legacySec,
                memorySource = migratedMemory
            )
        }

        fun delete(context: Context, widgetId: Int) {
            context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE).edit()
                .remove(configKey(widgetId))
                .remove("idx_$widgetId")
                .remove("seed_$widgetId")
                .remove("last_advance_$widgetId")
                .remove("group_key_$widgetId")
                .remove("seconds_$widgetId")
                .remove("source_$widgetId")
                .apply()
        }
    }
}

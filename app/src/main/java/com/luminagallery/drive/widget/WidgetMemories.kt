package com.luminagallery.drive.widget

import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

internal data class WidgetPhoto(
    val luminaItemId: String,
    val uri: String,
    val albumName: String,
    val takenAtMs: Long,
    val bucketId: String = "",
    val isScreenshot: Boolean = false
)

@Serializable
internal enum class WidgetMemorySource { DAYS, TRIPS, BOTH }

internal enum class WidgetGroupKind {
    ON_THIS_DAY,
    LOCATION_TRIP,
    PHOTO_DAY,
    ALBUM_GROUP,
    SELECTED_BATCH
}

internal data class WidgetMemory(
    val title: String,
    val subtitle: String?,
    val photos: List<WidgetPhoto>,
    val latestDay: LocalDate,
    val groupKind: WidgetGroupKind = WidgetGroupKind.PHOTO_DAY,
    val groupKey: String = ""
)

private val WidgetDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

internal fun widgetDateLabel(takenAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (takenAtMs > 0L) WidgetDateFormat.format(Instant.ofEpochMilli(takenAtMs).atZone(zone)) else ""

/**
 * Builds coherent photo groups for Memories & Smart Collages:
 * - Same-day photo clusters (2+ photos taken on the same calendar day)
 * - Location / Trip clusters (2+ photos taken in the same city within a 2-day visit window)
 */
internal fun buildWidgetMemories(
    photos: List<WidgetPhoto>,
    locations: Map<String, PhotoLocationEntity>,
    source: WidgetMemorySource,
    zone: ZoneId = ZoneId.systemDefault()
): List<WidgetMemory> {
    val dated = photos.asSequence()
        .filter { it.takenAtMs > 0L }
        .sortedByDescending { it.takenAtMs }
        .map { it to Instant.ofEpochMilli(it.takenAtMs).atZone(zone).toLocalDate() }
        .toList()
    val memories = mutableListOf<WidgetMemory>()

    if (source != WidgetMemorySource.TRIPS) {
        dated.groupBy({ it.second }, { it.first }).entries.asSequence()
            .filter { it.value.size >= 2 }
            .take(40)
            .mapTo(memories) { (day, sameDay) ->
                WidgetMemory(
                    title = WidgetDateFormat.format(day),
                    subtitle = null,
                    photos = sameDay.take(12),
                    latestDay = day,
                    groupKind = WidgetGroupKind.PHOTO_DAY,
                    groupKey = "day_$day"
                )
            }
    }

    if (source != WidgetMemorySource.DAYS) {
        val visits = dated.groupBy { (photo, _) ->
            locations[photo.luminaItemId]?.takeIf { it.hasLocation && it.city.isNotBlank() }
                ?.let { it.city.trim() to it.country.trim() }
        }
        val trips = mutableListOf<WidgetMemory>()
        for ((place, cityPhotos) in visits) {
            if (place == null) continue
            val (city, country) = place
            var visitStart = 0
            for (end in 1..cityPhotos.size) {
                val lastDay = cityPhotos[end - 1].second
                val nextDay = cityPhotos.getOrNull(end)?.second
                if (nextDay != null && ChronoUnit.DAYS.between(nextDay, lastDay) <= 2) continue
                if (end - visitStart >= 2) {
                    val firstDay = cityPhotos[visitStart].second
                    val dateLabel = if (firstDay == lastDay) {
                        WidgetDateFormat.format(firstDay)
                    } else {
                        "${WidgetDateFormat.format(lastDay)} – ${WidgetDateFormat.format(firstDay)}"
                    }
                    trips += WidgetMemory(
                        title = if (country.isBlank()) city else "$city, $country",
                        subtitle = dateLabel,
                        photos = cityPhotos.subList(visitStart, minOf(end, visitStart + 12)).map { it.first },
                        latestDay = firstDay,
                        groupKind = WidgetGroupKind.LOCATION_TRIP,
                        groupKey = "trip_${city}_${firstDay}"
                    )
                }
                visitStart = end
            }
        }
        memories += trips.sortedByDescending { it.latestDay }.take(40)
    }

    return memories.sortedByDescending { it.latestDay }
}

/**
 * Builds "On This Day" flashbacks matching today's month & day (or ±1 day window) in prior years.
 */
internal fun buildOnThisDayMemories(
    photos: List<WidgetPhoto>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault()
): List<WidgetMemory> {
    val byYear = photos.asSequence()
        .filter { it.takenAtMs > 0L }
        .map { it to Instant.ofEpochMilli(it.takenAtMs).atZone(zone).toLocalDate() }
        .filter { (_, date) ->
            val yearsAgo = today.year - date.year
            if (yearsAgo < 1) return@filter false
            val adjusted = runCatching { date.withYear(today.year) }.getOrNull() ?: return@filter false
            kotlin.math.abs(ChronoUnit.DAYS.between(today, adjusted)) <= 1L
        }
        .groupBy({ it.second.year }, { it })

    return byYear.entries
        .sortedByDescending { it.key }
        .mapNotNull { (year, entries) ->
            if (entries.isEmpty()) return@mapNotNull null
            val yearsAgo = today.year - year
            val title = if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago"
            val latest = entries.maxOf { it.second }
            WidgetMemory(
                title = title,
                subtitle = WidgetDateFormat.format(latest),
                photos = entries.sortedByDescending { it.first.takenAtMs }.take(12).map { it.first },
                latestDay = latest,
                groupKind = WidgetGroupKind.ON_THIS_DAY,
                groupKey = "otd_$year"
            )
        }
}

/**
 * Groups photos by album so a Smart Collage with multiple selected albums can shuffle
 * coherent album groups (each collage showing photos from one cohesive album).
 */
internal fun buildAlbumCollageGroups(
    photos: List<WidgetPhoto>,
    minPhotosPerGroup: Int = 2
): List<WidgetMemory> {
    val zone = ZoneId.systemDefault()
    return photos
        .groupBy { it.bucketId.ifBlank { it.albumName } }
        .values
        .filter { it.size >= minPhotosPerGroup }
        .map { albumPhotos ->
            val sorted = albumPhotos.sortedByDescending { it.takenAtMs }
            val newestMs = sorted.first().takenAtMs
            val newestDay = if (newestMs > 0L) {
                Instant.ofEpochMilli(newestMs).atZone(zone).toLocalDate()
            } else LocalDate.now()
            WidgetMemory(
                title = sorted.first().albumName.ifBlank { "Album" },
                subtitle = "${sorted.size} photos",
                photos = sorted,
                latestDay = newestDay,
                groupKind = WidgetGroupKind.ALBUM_GROUP,
                groupKey = "alb_${sorted.first().bucketId.ifBlank { sorted.first().albumName }}"
            )
        }
        .sortedByDescending { it.latestDay }
}

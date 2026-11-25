package com.luminagallery.drive.widget

import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
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
    val takenAtMs: Long
)

internal enum class WidgetMemorySource { DAYS, TRIPS, BOTH }

internal data class WidgetMemory(
    val title: String,
    val subtitle: String?,
    val photos: List<WidgetPhoto>,
    val latestDay: LocalDate
)

private val WidgetDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

internal fun widgetDateLabel(takenAtMs: Long): String =
    if (takenAtMs > 0L) WidgetDateFormat.format(Instant.ofEpochMilli(takenAtMs).atZone(ZoneId.systemDefault())) else ""

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
            .take(24)
            .mapTo(memories) { (day, sameDay) ->
                WidgetMemory(WidgetDateFormat.format(day), null, sameDay.take(8), day)
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
                        photos = cityPhotos.subList(visitStart, minOf(end, visitStart + 8)).map { it.first },
                        latestDay = firstDay
                    )
                }
                visitStart = end
            }
        }
        memories += trips.sortedByDescending { it.latestDay }.take(24)
    }

    return memories.sortedByDescending { it.latestDay }
}

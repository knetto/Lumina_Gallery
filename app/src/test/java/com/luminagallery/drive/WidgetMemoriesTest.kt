package com.luminagallery.drive

import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import com.luminagallery.drive.widget.WidgetMemorySource
import com.luminagallery.drive.widget.WidgetPhoto
import com.luminagallery.drive.widget.buildWidgetMemories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class WidgetMemoriesTest {
    @Test
    fun tripCollagesKeepSeparateVisitsToTheSameCityApart() {
        val photos = listOf(
            photo("a", "2026-11-20T10:00:00Z"),
            photo("b", "2026-11-21T10:00:00Z"),
            photo("c", "2026-10-01T10:00:00Z"),
            photo("d", "2026-10-03T10:00:00Z"),
            photo("e", "2026-09-01T10:00:00Z")
        )
        val locations = photos.associate { photo ->
            photo.luminaItemId to PhotoLocationEntity(
                itemId = photo.luminaItemId,
                dateModifiedMs = photo.takenAtMs,
                hasLocation = true,
                latitude = 52.37,
                longitude = 4.90,
                city = "Amsterdam",
                country = "Netherlands"
            )
        }
        val trips = buildWidgetMemories(photos, locations, WidgetMemorySource.TRIPS, ZoneOffset.UTC)

        assertEquals(listOf(listOf("b", "a"), listOf("d", "c")), trips.map { it.photos.map(WidgetPhoto::luminaItemId) })
        assertTrue(trips.all { it.title == "Amsterdam, Netherlands" })
    }

    @Test
    fun mixedMemoriesStillShowDayCollagesWhenLocationDataIsUnavailable() {
        val photos = listOf(
            photo("a", "2026-11-20T10:00:00Z"),
            photo("b", "2026-11-20T22:00:00Z"),
            photo("c", "2026-11-21T10:00:00Z")
        )

        val mixed = buildWidgetMemories(photos, emptyMap(), WidgetMemorySource.BOTH, ZoneOffset.UTC)

        assertEquals(listOf("b", "a"), mixed.single().photos.map(WidgetPhoto::luminaItemId))
        assertTrue(buildWidgetMemories(photos, emptyMap(), WidgetMemorySource.TRIPS, ZoneOffset.UTC).isEmpty())
    }

    private fun photo(id: String, date: String) =
        WidgetPhoto(id, "content://test/$id", "Camera", Instant.parse(date).toEpochMilli())
}

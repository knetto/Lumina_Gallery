package com.luminagallery.drive

import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import com.luminagallery.drive.widget.HyperOsGlassRenderer
import com.luminagallery.drive.widget.LuminaPhotoWidgetProvider
import com.luminagallery.drive.widget.PhotoWidgetKind
import com.luminagallery.drive.widget.PhotoWidgetSettings
import com.luminagallery.drive.widget.ResolvedTilePattern
import com.luminagallery.drive.widget.WidgetCollageLayout
import com.luminagallery.drive.widget.WidgetMemorySource
import com.luminagallery.drive.widget.WidgetPhoto
import com.luminagallery.drive.widget.WidgetPhotoSource
import com.luminagallery.drive.widget.WidgetShuffleInterval
import com.luminagallery.drive.widget.buildAlbumCollageGroups
import com.luminagallery.drive.widget.buildOnThisDayMemories
import com.luminagallery.drive.widget.buildWidgetMemories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
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

    @Test
    fun onThisDayMemoriesGroupPriorYearsAndIgnoreCurrentYear() {
        val photos = listOf(
            photo("now", "2026-11-25T10:00:00Z"),
            photo("y1a", "2025-11-25T14:00:00Z"),
            photo("y1b", "2025-11-25T09:00:00Z"),
            photo("y3", "2023-11-24T18:00:00Z"),
            photo("other", "2025-07-04T12:00:00Z")
        )
        val today = LocalDate.of(2026, 11, 25)
        val flashbacks = buildOnThisDayMemories(photos, today, ZoneOffset.UTC)

        assertEquals(listOf("1 year ago", "3 years ago"), flashbacks.map { it.title })
        assertEquals(listOf("y1a", "y1b"), flashbacks[0].photos.map(WidgetPhoto::luminaItemId))
        assertEquals(listOf("y3"), flashbacks[1].photos.map(WidgetPhoto::luminaItemId))
    }

    @Test
    fun albumCollageGroupsKeepAlbumsCoherent() {
        val photos = listOf(
            photo("a1", "2026-11-20T10:00:00Z", album = "Japan", bucket = "local_bucket_1"),
            photo("a2", "2026-11-20T12:00:00Z", album = "Japan", bucket = "local_bucket_1"),
            photo("b1", "2026-11-18T10:00:00Z", album = "Pets", bucket = "local_bucket_2"),
            photo("b2", "2026-11-18T11:00:00Z", album = "Pets", bucket = "local_bucket_2"),
            photo("solo", "2026-11-19T10:00:00Z", album = "Single", bucket = "local_bucket_3")
        )
        val groups = buildAlbumCollageGroups(photos, minPhotosPerGroup = 2)

        assertEquals(listOf("Japan", "Pets"), groups.map { it.title })
        assertEquals(listOf("a2", "a1"), groups[0].photos.map(WidgetPhoto::luminaItemId))
    }

    @Test
    fun adaptiveCollageAndStripTilePatternsRespondToResizing() {
        // Wide 4x2 collage with 4 photos -> 4-tile editorial bento
        assertEquals(
            ResolvedTilePattern.BENTO_4_WIDE,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.AUTO, 320f, 160f, 4
            )
        )
        // Tall 2x4 collage -> vertical Hero Top + 2 bottom tiles
        assertEquals(
            ResolvedTilePattern.HERO_TOP_2_BOTTOM,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.AUTO, 150f, 310f, 4
            )
        )
        // Square 2x2 collage with 4 photos -> balanced 2x2 grid
        assertEquals(
            ResolvedTilePattern.GRID_2X2,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.AUTO, 170f, 170f, 4
            )
        )
        // Strip flips from horizontal 4-photo row to vertical 3-photo stack on portrait resize
        assertEquals(
            ResolvedTilePattern.STRIP_HORIZONTAL_4,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.STRIP, WidgetCollageLayout.AUTO, 320f, 80f, 4
            )
        )
        assertEquals(
            ResolvedTilePattern.STRIP_VERTICAL_3,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.STRIP, WidgetCollageLayout.AUTO, 90f, 280f, 4
            )
        )
    }

    @Test
    fun widgetDefaultsMatchEachWidgetKindPurpose() {
        assertEquals(WidgetPhotoSource.ALL_PHONE_RANDOM, PhotoWidgetSettings.defaultsFor(PhotoWidgetKind.RANDOM_SHUFFLE).source)
        assertEquals(WidgetPhotoSource.SMART_GROUPS, PhotoWidgetSettings.defaultsFor(PhotoWidgetKind.COLLAGE).source)
        assertEquals(WidgetPhotoSource.FAVORITES_ONLY, PhotoWidgetSettings.defaultsFor(PhotoWidgetKind.FAVORITES).source)
        for (kind in PhotoWidgetKind.entries) {
            assertFalse("Live date badge must be OFF by default for $kind", PhotoWidgetSettings.defaultsFor(kind).showDateBadge)
        }
    }

    @Test
    fun intervalDueRespectsManualDailyAndFirstPlacement() {
        val now = 10_000_000_000L
        // First placement (lastAdvance = 0) renders index 0 without skipping
        assertFalse(LuminaPhotoWidgetProvider.isWidgetAdvanceDue(WidgetShuffleInterval.HOUR_1, 0L, now))
        // Manual interval never auto-advances on periodic onUpdate
        assertFalse(LuminaPhotoWidgetProvider.isWidgetAdvanceDue(WidgetShuffleInterval.MANUAL, now - 86_400_000L, now))
        // Daily interval does NOT prematurely shuffle after 30 minutes
        assertFalse(LuminaPhotoWidgetProvider.isWidgetAdvanceDue(WidgetShuffleInterval.DAILY, now - 1_800_000L, now))
        // Daily interval DOES advance after 24 hours
        assertTrue(LuminaPhotoWidgetProvider.isWidgetAdvanceDue(WidgetShuffleInterval.DAILY, now - 86_400_000L, now))
        // Hourly interval advances after 1 hour
        assertTrue(LuminaPhotoWidgetProvider.isWidgetAdvanceDue(WidgetShuffleInterval.HOUR_1, now - 3_600_000L, now))
    }

    private fun photo(
        id: String,
        date: String,
        album: String = "Camera",
        bucket: String = "local_bucket_cam"
    ) = WidgetPhoto(
        luminaItemId = id,
        uri = "content://test/$id",
        albumName = album,
        takenAtMs = Instant.parse(date).toEpochMilli(),
        bucketId = bucket
    )
}

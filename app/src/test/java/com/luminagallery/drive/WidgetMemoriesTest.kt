package com.luminagallery.drive

import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import com.luminagallery.drive.widget.HyperOsGlassRenderer
import com.luminagallery.drive.widget.LuminaPhotoWidgetProvider
import com.luminagallery.drive.widget.PhotoWidgetKind
import com.luminagallery.drive.widget.PhotoWidgetSettings
import com.luminagallery.drive.widget.ResolvedTilePattern
import com.luminagallery.drive.widget.VisualFingerprint
import com.luminagallery.drive.widget.WidgetCollageLayout
import com.luminagallery.drive.widget.WidgetMemorySource
import com.luminagallery.drive.widget.WidgetPhoto
import com.luminagallery.drive.widget.WidgetPhotoDiversity
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
import kotlin.random.Random

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
        // Tall 2x4 collage with 3 photos -> vertical Hero Top + 2 bottom tiles
        assertEquals(
            ResolvedTilePattern.HERO_TOP_2_BOTTOM,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.AUTO, 150f, 310f, 3
            )
        )
        // Tall 2x4 collage with 4 photos -> Top Hero + 3 bottom tiles
        assertEquals(
            ResolvedTilePattern.BENTO_4_TOP_HERO,
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

    @Test
    fun smartCollageLayoutAdaptsToDayImageCountAndPhotoOrientations() {
        // 2 wide landscape photos on a square widget -> vertical stack (wide tiles matching landscape)
        assertEquals(
            ResolvedTilePattern.STRIP_VERTICAL_2,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.AUTO, 180f, 180f, 2,
                photoAspectRatios = listOf(1.78f, 1.50f)
            )
        )
        // 2 tall portrait photos on a square widget -> side-by-side split (tall tiles matching portrait)
        assertEquals(
            ResolvedTilePattern.STRIP_HORIZONTAL_2,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.AUTO, 180f, 180f, 2,
                photoAspectRatios = listOf(0.56f, 0.75f)
            )
        )
        // 3 tall portraits on a wide 4x2 widget -> 3 vertical panes instead of slicing portraits flat
        assertEquals(
            ResolvedTilePattern.STRIP_HORIZONTAL_3,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.AUTO, 320f, 165f, 3,
                photoAspectRatios = listOf(0.65f, 0.70f, 0.68f)
            )
        )
        // 1 wide panoramic/landscape + 3 portraits on 4x3 widget -> Top Hero + 3 bottom portrait panes
        assertEquals(
            ResolvedTilePattern.BENTO_4_TOP_HERO,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.AUTO, 320f, 240f, 4,
                photoAspectRatios = listOf(2.1f, 0.70f, 0.72f, 0.68f)
            )
        )
        // Rich day with 6 distinct photos on large 4x3 widget -> 6-photo 3x2 grid
        assertEquals(
            ResolvedTilePattern.GRID_3X2,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.AUTO, 330f, 240f, 6,
                photoAspectRatios = listOf(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f)
            )
        )
    }

    @Test
    fun slotAssignmentPlacesPortraitInPortraitSlotAndLandscapeInLandscapeSlot() {
        val landscape1 = photo("wide1", "2026-11-20T10:00:00Z", width = 4000, height = 2250)
        val landscape2 = photo("wide2", "2026-11-20T10:30:00Z", width = 4000, height = 2667)
        val portrait = photo("tall", "2026-11-20T11:00:00Z", width = 2250, height = 4000)

        // Pass landscape first; HERO_LEFT_2_RIGHT has portrait hero left (slot 0) and 2 landscape slots right (1, 2)
        val (pattern, ordered) = HyperOsGlassRenderer.resolveSmartPatternAndOrder(
            kind = PhotoWidgetKind.COLLAGE,
            collageLayout = WidgetCollageLayout.HERO_SIDE,
            widthDp = 320f,
            heightDp = 220f,
            photos = listOf(landscape1, landscape2, portrait),
            aspectRatioOf = { it.aspectRatio }
        )
        assertEquals(ResolvedTilePattern.HERO_LEFT_2_RIGHT, pattern)
        assertEquals("tall", ordered[0].luminaItemId)
        assertTrue(setOf(ordered[1].luminaItemId, ordered[2].luminaItemId) == setOf("wide1", "wide2"))
    }

    @Test
    fun temporalAndVisualDiversitySkipsBurstRetakesAndNearDuplicates() {
        // 4 rapid burst shots at 10:00:00..10:00:09 + 2 distinct scenes hours later
        val burst1 = photo("b1", "2026-11-20T10:00:00Z")
        val burst2 = photo("b2", "2026-11-20T10:00:03Z")
        val burst3 = photo("b3", "2026-11-20T10:00:06Z")
        val burst4 = photo("b4", "2026-11-20T10:00:09Z")
        val lunch = photo("lunch", "2026-11-20T13:15:00Z")
        val sunset = photo("sunset", "2026-11-20T18:30:00Z")

        val temporal = WidgetPhotoDiversity.selectTemporallyDiverseCandidates(
            photos = listOf(burst1, burst2, burst3, burst4, lunch, sunset),
            maxCount = 3,
            randomize = false
        )
        // Must pick 1 burst + lunch + sunset (NOT 3 copies from the burst!)
        assertEquals(listOf("b1", "lunch", "sunset"), temporal.map { it.luminaItemId })

        // Even if two shots are hours apart, visual fingerprinting rejects near-identical frames
        val identicalFpA = VisualFingerprint(0x00FF00FF00FF00FFL, IntArray(48) { 40 })
        val identicalFpB = VisualFingerprint(0x00FF00FF00FF00FEL, IntArray(48) { 42 })
        val distinctFpC = VisualFingerprint(0xFF00FF00FF00FF00UL.toLong(), IntArray(48) { 210 })
        val distinctFpD = VisualFingerprint(0x0F0F0F0FF0F0F0F0L, IntArray(48) { if (it % 3 == 0) 240 else 20 })

        val chosen = WidgetPhotoDiversity.selectFromFingerprints(
            candidates = listOf(burst1, lunch, burst2, sunset),
            fingerprints = mapOf(
                "b1" to identicalFpA,
                "b2" to identicalFpB,
                "lunch" to distinctFpC,
                "sunset" to distinctFpD
            ),
            desiredMax = 4,
            minSlots = 2,
            allowStepDownOnDuplicates = true,
            randomize = false,
            random = Random(1)
        )
        // Steps down from 4 to 3 genuinely distinct photos instead of including near-duplicate b2!
        assertEquals(3, chosen.size)
        assertTrue(chosen.map { it.luminaItemId }.containsAll(listOf("b1", "lunch", "sunset")))
        assertFalse(chosen.any { it.luminaItemId == "b2" })
    }

    private fun photo(
        id: String,
        date: String,
        album: String = "Camera",
        bucket: String = "local_bucket_cam",
        width: Int = 3000,
        height: Int = 4000
    ) = WidgetPhoto(
        luminaItemId = id,
        uri = "content://test/$id",
        albumName = album,
        takenAtMs = Instant.parse(date).toEpochMilli(),
        bucketId = bucket,
        width = width,
        height = height
    )
}

package com.luminagallery.drive

import com.luminagallery.drive.data.local.dao.LightFaceRow
import com.luminagallery.drive.data.local.entity.MediaAnnotationEntity
import com.luminagallery.drive.data.local.entity.PersonClusterEntity
import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import com.luminagallery.drive.data.local.entity.PhotoSmartIndexEntity
import com.luminagallery.drive.widget.HyperOsGlassRenderer
import com.luminagallery.drive.widget.LuminaPhotoWidgetProvider
import com.luminagallery.drive.widget.LuminaWidgetRefreshWorker
import com.luminagallery.drive.widget.PhotoWidgetKind
import com.luminagallery.drive.widget.PhotoWidgetSettings
import com.luminagallery.drive.widget.ResolvedTilePattern
import com.luminagallery.drive.widget.VisualFingerprint
import com.luminagallery.drive.widget.WidgetCollageLayout
import com.luminagallery.drive.widget.WidgetMemorySource
import com.luminagallery.drive.widget.WidgetPersonMatchMode
import com.luminagallery.drive.widget.WidgetPhoto
import com.luminagallery.drive.widget.WidgetPhotoDiversity
import com.luminagallery.drive.widget.WidgetPhotoSource
import com.luminagallery.drive.widget.WidgetShuffleInterval
import com.luminagallery.drive.widget.WidgetSmartFilter
import com.luminagallery.drive.widget.WidgetTagKind
import com.luminagallery.drive.widget.WidgetTagMatchMode
import com.luminagallery.drive.widget.buildAlbumCollageGroups
import com.luminagallery.drive.widget.buildOnThisDayMemories
import com.luminagallery.drive.widget.buildWidgetMemories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // Photo Strip layout inside Collage flips from horizontal 4-photo row to vertical 3-photo stack on portrait resize
        assertEquals(
            ResolvedTilePattern.STRIP_HORIZONTAL_4,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.STRIP, 320f, 80f, 4
            )
        )
        assertEquals(
            ResolvedTilePattern.STRIP_VERTICAL_3,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.STRIP, 90f, 280f, 4
            )
        )
        // Smart Auto Collage also becomes a clean 4-photo horizontal strip when resized down to a 4x1 banner
        assertEquals(
            ResolvedTilePattern.STRIP_HORIZONTAL_4,
            HyperOsGlassRenderer.resolveTilePattern(
                PhotoWidgetKind.COLLAGE, WidgetCollageLayout.AUTO, 320f, 80f, 4
            )
        )
    }

    @Test
    fun widgetDefaultsDisableOverlaysAndEnableAvoidSimilarPhotos() {
        assertEquals(listOf(PhotoWidgetKind.FRAME, PhotoWidgetKind.COLLAGE), LuminaPhotoWidgetProvider.allProviders().map { it.kind })
        assertEquals(WidgetPhotoSource.FAVORITES_AND_RECENT, PhotoWidgetSettings.defaultsFor(PhotoWidgetKind.FRAME).source)
        assertEquals(WidgetPhotoSource.SMART_GROUPS, PhotoWidgetSettings.defaultsFor(PhotoWidgetKind.COLLAGE).source)
        for (kind in PhotoWidgetKind.entries) {
            val defaults = PhotoWidgetSettings.defaultsFor(kind)
            assertFalse("Caption must be OFF by default for $kind", defaults.showCaption)
            assertFalse("Live date badge must be OFF by default for $kind", defaults.showDateBadge)
            assertFalse("Shuffle button must be OFF by default for $kind", defaults.showShuffleButton)
            assertFalse("Settings button must be OFF by default for $kind", defaults.showSettingsButton)
            assertTrue("Avoid similar photos must be ON by default for $kind", defaults.avoidSimilarPhotos)
        }
    }

    @Test
    fun keepAliveRefreshTriggersAfterStaleIntervalOrFirstPush() {
        val now = 10_000_000_000L
        assertTrue(LuminaWidgetRefreshWorker.shouldTriggerKeepAliveRefresh(0L, now))
        assertFalse(LuminaWidgetRefreshWorker.shouldTriggerKeepAliveRefresh(now - 10 * 60_000L, now))
        assertTrue(
            LuminaWidgetRefreshWorker.shouldTriggerKeepAliveRefresh(
                now - LuminaWidgetRefreshWorker.KEEP_ALIVE_INTERVAL_MS,
                now
            )
        )
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

    @Test
    fun widgetSettingsJsonRemainsBackwardCompatibleAndRoundTripsPeopleAndTags() {
        // Legacy v2 widget payload saved before PEOPLE_AND_PETS / TAGS_AND_ANIMALS existed
        val legacyJson = """{"source":"SELECTED_ALBUMS","selectedAlbumIds":["local_bucket_1"],"shuffleInterval":"HOUR_1"}"""
        val decodedLegacy = PhotoWidgetSettings.decodeFromJson(legacyJson)
        assertNotNull(decodedLegacy)
        assertEquals(WidgetPhotoSource.SELECTED_ALBUMS, decodedLegacy!!.source)
        assertEquals(listOf("local_bucket_1"), decodedLegacy.selectedAlbumIds)
        assertTrue(decodedLegacy.selectedPersonIds.isEmpty())
        assertEquals(WidgetPersonMatchMode.ANY, decodedLegacy.personMatchMode)
        assertTrue(decodedLegacy.selectedTags.isEmpty())
        assertEquals(WidgetTagMatchMode.ANY, decodedLegacy.tagMatchMode)

        val peopleWidget = PhotoWidgetSettings(
            source = WidgetPhotoSource.PEOPLE_AND_PETS,
            selectedPersonIds = listOf("p_alex", "p_sarah"),
            selectedPersonMemoryIds = mapOf("p_alex" to "mem_alex", "p_sarah" to "mem_sarah"),
            personMatchMode = WidgetPersonMatchMode.TOGETHER
        )
        val roundTrippedPeople = PhotoWidgetSettings.decodeFromJson(peopleWidget.encodeToJson())
        assertEquals(peopleWidget, roundTrippedPeople)

        val tagWidget = PhotoWidgetSettings(
            source = WidgetPhotoSource.TAGS_AND_ANIMALS,
            selectedTags = listOf(WidgetSmartFilter.TAG_ALL_PETS, "Beach", "#vacation"),
            tagMatchMode = WidgetTagMatchMode.ALL
        )
        val roundTrippedTags = PhotoWidgetSettings.decodeFromJson(tagWidget.encodeToJson())
        assertEquals(tagWidget, roundTrippedTags)
        assertFalse(peopleWidget.hasSamePhotoPoolAs(peopleWidget.copy(personMatchMode = WidgetPersonMatchMode.ANY)))
        assertFalse(tagWidget.hasSamePhotoPoolAs(tagWidget.copy(tagMatchMode = WidgetTagMatchMode.ANY)))
    }

    @Test
    fun widgetSmartFilterMatchesSinglePersonMultiplePeopleTogetherPetsAndDurableMemory() {
        val alex = person("p_alex", name = "Alex", memoryId = "mem_alex")
        val sarah = person("p_sarah", name = "Sarah", memoryId = "mem_sarah")
        val bellaDog = person("p_bella", name = "Bella", entityKind = PersonClusterEntity.KIND_PET_DOG, memoryId = "mem_bella")
        val strayCat = person("p_stray", name = "", entityKind = PersonClusterEntity.KIND_PET_CAT)
        val hiddenPerson = person("p_secret", name = "Hidden", isHidden = true)

        val persons = listOf(alex, sarah, bellaDog, strayCat, hiddenPerson)
        val faces = listOf(
            // Visible local photos
            face("f1", "local_1", "p_alex"),
            face("f2", "local_2", "p_alex"),
            face("f3", "local_2", "p_sarah"),
            face("f4", "local_3", "p_sarah"),
            face("f5", "local_4", "p_alex"),
            face("f6", "local_4", "p_bella"),
            face("f7", "local_5", "p_bella"),
            face("f8", "local_6", "p_stray"), // 1-photo unnamed stray cat
            face("f9", "local_7", "p_secret"), // hidden person
            // Locked/hidden album photo (NOT in visibleLocalPhotoIds!)
            face("f10", "local_locked", "p_alex"),
            face("f11", "local_locked", "p_sarah")
        )
        val visibleLocal = setOf("local_1", "local_2", "local_3", "local_4", "local_5", "local_6", "local_7")

        // Picker options exclude hidden people and 1-photo unnamed stray cats, include multi-photo pets
        val options = WidgetSmartFilter.buildPersonOptions(persons, faces, visibleLocal)
        assertEquals(setOf("p_alex", "p_sarah", "p_bella"), options.map { it.personId }.toSet())
        assertTrue(options.first { it.personId == "p_bella" }.isPet)

        // Single person
        assertEquals(
            setOf("local_1", "local_2", "local_4"),
            WidgetSmartFilter.resolveMatchingPhotoIdsForPeople(
                selectedPersonIds = listOf("p_alex"),
                selectedPersonMemoryIds = emptyMap(),
                matchMode = WidgetPersonMatchMode.ANY,
                persons = persons,
                faces = faces,
                visibleLocalPhotoIds = visibleLocal
            )
        )

        // Multiple people (ANY = union)
        assertEquals(
            setOf("local_1", "local_2", "local_3", "local_4"),
            WidgetSmartFilter.resolveMatchingPhotoIdsForPeople(
                selectedPersonIds = listOf("p_alex", "p_sarah"),
                selectedPersonMemoryIds = emptyMap(),
                matchMode = WidgetPersonMatchMode.ANY,
                persons = persons,
                faces = faces,
                visibleLocalPhotoIds = visibleLocal
            )
        )

        // Multiple people (TOGETHER = intersection; locked photo excluded!)
        assertEquals(
            setOf("local_2"),
            WidgetSmartFilter.resolveMatchingPhotoIdsForPeople(
                selectedPersonIds = listOf("p_alex", "p_sarah"),
                selectedPersonMemoryIds = emptyMap(),
                matchMode = WidgetPersonMatchMode.TOGETHER,
                persons = persons,
                faces = faces,
                visibleLocalPhotoIds = visibleLocal
            )
        )

        // Person + Pet together!
        assertEquals(
            setOf("local_4"),
            WidgetSmartFilter.resolveMatchingPhotoIdsForPeople(
                selectedPersonIds = listOf("p_alex", "p_bella"),
                selectedPersonMemoryIds = emptyMap(),
                matchMode = WidgetPersonMatchMode.TOGETHER,
                persons = persons,
                faces = faces,
                visibleLocalPhotoIds = visibleLocal
            )
        )

        // Hidden person never leaks
        assertTrue(
            WidgetSmartFilter.resolveMatchingPhotoIdsForPeople(
                selectedPersonIds = listOf("p_secret"),
                selectedPersonMemoryIds = emptyMap(),
                matchMode = WidgetPersonMatchMode.ANY,
                persons = persons,
                faces = faces,
                visibleLocalPhotoIds = visibleLocal
            ).isEmpty()
        )

        // Durable memoryId fallback survives AI rebuild where personId changed from p_alex -> p_alex_rebuilt
        val rebuiltPersons = listOf(alex.copy(personId = "p_alex_rebuilt"))
        val rebuiltFaces = listOf(face("f_rebuilt", "local_1", "p_alex_rebuilt"))
        assertEquals(
            setOf("local_1"),
            WidgetSmartFilter.resolveMatchingPhotoIdsForPeople(
                selectedPersonIds = listOf("p_alex"),
                selectedPersonMemoryIds = mapOf("p_alex" to "mem_alex"),
                matchMode = WidgetPersonMatchMode.ANY,
                persons = rebuiltPersons,
                faces = rebuiltFaces,
                visibleLocalPhotoIds = visibleLocal
            )
        )

        assertEquals(
            "Alex & Sarah",
            WidgetSmartFilter.formatPeopleCaption(
                selectedPersonIds = listOf("p_alex", "p_sarah"),
                selectedPersonMemoryIds = emptyMap(),
                matchMode = WidgetPersonMatchMode.TOGETHER,
                persons = persons
            )
        )
    }

    @Test
    fun widgetSmartFilterMatchesPetsAnimalsAiTagsAndCustomTagsWithAnyAndAllModes() {
        val bellaDog = person("p_bella", name = "Bella", entityKind = PersonClusterEntity.KIND_PET_DOG)
        val persons = listOf(bellaDog)
        val faces = listOf(face("fd1", "local_dog_cluster", "p_bella"))

        val smartIndex = listOf(
            smart("local_dog_beach", "Dog, Pet, Beach", "dog hond pet huisdier beach strand"),
            smart("local_cat", "Cat, Pet", "cat kat pet huisdier"),
            smart("local_lion", "Lion, Animal", "lion leeuw animal dier wildlife safari"),
            smart("local_monkey", "Monkey", "monkey aap primate animal dier"),
            smart("local_parrot", "Parrot, Bird", "parrot papegaai bird vogel animal dier"),
            smart("local_sunset", "Sunset, Beach", "sunset zonsondergang beach strand"),
            smart("local_locked_dog", "Dog, Pet", "dog hond pet")
        )
        val annotations = listOf(
            MediaAnnotationEntity("local_custom", tagsCsv = "vacation, summer", note = "", updatedAtEpochMs = 1L),
            MediaAnnotationEntity("local_dog_beach", tagsCsv = "dog, vacation", note = "", updatedAtEpochMs = 1L)
        )
        val visibleLocal = setOf(
            "local_dog_cluster",
            "local_dog_beach",
            "local_cat",
            "local_lion",
            "local_monkey",
            "local_parrot",
            "local_sunset",
            "local_custom"
        )

        // All pets matches dog cluster, dog/cat AI labels, excludes wildlife (lion, monkey, parrot) & locked photos
        assertEquals(
            setOf("local_dog_cluster", "local_dog_beach", "local_cat"),
            WidgetSmartFilter.resolveMatchingPhotoIdsForTags(
                selectedTags = listOf(WidgetSmartFilter.TAG_ALL_PETS),
                matchMode = WidgetTagMatchMode.ANY,
                visibleLocalPhotoIds = visibleLocal,
                smartEntries = smartIndex,
                annotations = annotations,
                persons = persons,
                faces = faces
            )
        )

        // All animals includes pets AND wildlife/birds, excludes pure scenery (sunset) & non-animal custom tags
        assertEquals(
            setOf("local_dog_cluster", "local_dog_beach", "local_cat", "local_lion", "local_monkey", "local_parrot"),
            WidgetSmartFilter.resolveMatchingPhotoIdsForTags(
                selectedTags = listOf(WidgetSmartFilter.TAG_ALL_ANIMALS),
                matchMode = WidgetTagMatchMode.ANY,
                visibleLocalPhotoIds = visibleLocal,
                smartEntries = smartIndex,
                annotations = annotations,
                persons = persons,
                faces = faces
            )
        )

        // Specific tags with ALL (AND): Dog + Beach -> only local_dog_beach
        assertEquals(
            setOf("local_dog_beach"),
            WidgetSmartFilter.resolveMatchingPhotoIdsForTags(
                selectedTags = listOf("Dog", "Beach"),
                matchMode = WidgetTagMatchMode.ALL,
                visibleLocalPhotoIds = visibleLocal,
                smartEntries = smartIndex,
                annotations = annotations,
                persons = persons,
                faces = faces
            )
        )

        // Specific tags with ANY (OR): #vacation + Parrot
        assertEquals(
            setOf("local_custom", "local_dog_beach", "local_parrot"),
            WidgetSmartFilter.resolveMatchingPhotoIdsForTags(
                selectedTags = listOf("#vacation", "Parrot"),
                matchMode = WidgetTagMatchMode.ANY,
                visibleLocalPhotoIds = visibleLocal,
                smartEntries = smartIndex,
                annotations = annotations,
                persons = persons,
                faces = faces
            )
        )

        // Catalog builds presets, merges duplicate manual "dog" into AI "Dog", and separates categories
        val catalog = WidgetSmartFilter.buildTagOptions(visibleLocal, smartIndex, annotations, persons, faces)
        val presets = catalog.filter { it.kind == WidgetTagKind.PRESET }
        assertEquals(3, presets.first { it.key == WidgetSmartFilter.TAG_ALL_PETS }.photoCount)
        assertEquals(6, presets.first { it.key == WidgetSmartFilter.TAG_ALL_ANIMALS }.photoCount)
        assertTrue(catalog.any { it.key == "Dog" && it.kind == WidgetTagKind.ANIMAL && it.photoCount == 2 })
        assertFalse(catalog.any { it.key.equals("#dog", ignoreCase = true) })
        assertTrue(catalog.any { it.key == "#vacation" && it.kind == WidgetTagKind.CUSTOM_TAG && it.photoCount == 2 })
        assertTrue(catalog.any { it.key == "Sunset" && it.kind == WidgetTagKind.AI_TAG && it.photoCount == 1 })

        assertEquals(
            "Pets + Beach",
            WidgetSmartFilter.formatTagsCaption(
                listOf(WidgetSmartFilter.TAG_ALL_PETS, "Beach"),
                WidgetTagMatchMode.ALL
            )
        )
    }

    private fun person(
        id: String,
        name: String = "",
        entityKind: String = PersonClusterEntity.KIND_HUMAN,
        isHidden: Boolean = false,
        memoryId: String? = null
    ) = PersonClusterEntity(
        personId = id,
        name = name,
        isHidden = isHidden,
        centroidBlob = ByteArray(4),
        updatedAtEpochMs = 1L,
        memoryId = memoryId,
        entityKind = entityKind
    )

    private fun face(faceId: String, itemId: String, personId: String) = LightFaceRow(
        faceId = faceId,
        itemId = itemId,
        personId = personId,
        qualityScore = 0.9f,
        cropPath = null
    )

    private fun smart(itemId: String, labelsCsv: String, tokens: String) = PhotoSmartIndexEntity(
        itemId = itemId,
        dateModifiedMs = 1L,
        faceCount = 0,
        labelsCsv = labelsCsv,
        labelSearchTokens = tokens,
        isDriveItem = false,
        indexedAtEpochMs = 1L
    )

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

package com.luminagallery.drive

import com.luminagallery.drive.data.local.entity.PersonClusterEntity
import com.luminagallery.drive.data.local.entity.SavedPersonAnchorEntity
import com.luminagallery.drive.data.local.entity.SavedPersonMemoryEntity
import com.luminagallery.drive.data.repository.ai.AnimalTaxonomy
import com.luminagallery.drive.data.repository.ai.FaceClusteringEngine
import com.luminagallery.drive.data.repository.ai.FaceEmbeddingEngine
import com.luminagallery.drive.data.repository.ai.PersonMemoryEngine
import com.luminagallery.drive.data.repository.ai.PetRecognitionEngine
import com.luminagallery.drive.data.repository.ai.SmartObjectVocabulary
import com.luminagallery.drive.data.repository.ai.SmartRecognitionManager
import com.luminagallery.drive.domain.model.ExifMetadata
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class SmartRecognitionTest {

    @Test
    fun eyeRollComputation_neverInvertsWhenMlKitLeftRightEyesArePassedInEitherOrder() {
        // ML Kit LEFT_EYE is subject's left eye (larger X in image), RIGHT_EYE is smaller X
        val mlKitLeftEye = FaceClusteringEngine.Point2D(320f, 200f)
        val mlKitRightEye = FaceClusteringEngine.Point2D(220f, 200f)
        assertEquals(0f, FaceClusteringEngine.computeUprightEyeRollDegrees(mlKitLeftEye, mlKitRightEye), 0.1f)
        assertEquals(0f, FaceClusteringEngine.computeUprightEyeRollDegrees(mlKitRightEye, mlKitLeftEye), 0.1f)

        // Tilted 15 degrees clockwise
        val rad = Math.toRadians(15.0)
        val dx = (100.0 * cos(rad)).toFloat()
        val dy = (100.0 * sin(rad)).toFloat()
        val tiltedRight = FaceClusteringEngine.Point2D(220f + dx, 200f + dy)
        assertEquals(15f, FaceClusteringEngine.computeUprightEyeRollDegrees(mlKitRightEye, tiltedRight), 0.2f)
        assertEquals(15f, FaceClusteringEngine.computeUprightEyeRollDegrees(tiltedRight, mlKitRightEye), 0.2f)
    }

    @Test
    fun fivePointSimilarityAlignment_mapsRotatedScaledFaceOntoCanonical112Template() {
        val canonical = listOf(
            FaceClusteringEngine.CANONICAL_112_LEFT_EYE,
            FaceClusteringEngine.CANONICAL_112_RIGHT_EYE,
            FaceClusteringEngine.CANONICAL_112_NOSE,
            FaceClusteringEngine.CANONICAL_112_MOUTH_LEFT,
            FaceClusteringEngine.CANONICAL_112_MOUTH_RIGHT
        )
        // Synthesize a 2.5x larger face rotated 18 degrees and shifted in a 768x768 photo
        val scale = 2.5
        val rad = Math.toRadians(18.0)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val src = canonical.map { p ->
            FaceClusteringEngine.Point2D(
                x = (scale * (p.x * cosA - p.y * sinA) + 210.0).toFloat(),
                y = (scale * (p.x * sinA + p.y * cosA) + 140.0).toFloat()
            )
        }

        val transform = FaceClusteringEngine.solveSimilarityTransform(src, canonical, outputSize = 112)
        assertNotNull(transform)
        for (i in canonical.indices) {
            val mapped = transform!!.map(src[i])
            assertEquals("X mismatch at landmark $i", canonical[i].x, mapped.x, 0.25f)
            assertEquals("Y mismatch at landmark $i", canonical[i].y, mapped.y, 0.25f)
        }
    }

    @Test
    fun humanFaceGeometryGate_acceptsRealHumansAndRejectsBrokenAnimalOrTinyFaces() {
        val valid = FaceClusteringEngine.isHumanFaceGeometryValid(
            bboxLeft = 200f,
            bboxTop = 100f,
            bboxRight = 380f,
            bboxBottom = 290f,
            imageWidthPx = 768f,
            eulerY = 8f,
            eulerX = -4f,
            eyeA = FaceClusteringEngine.Point2D(325f, 175f), // ML Kit LEFT_EYE (larger X)
            eyeB = FaceClusteringEngine.Point2D(255f, 175f), // ML Kit RIGHT_EYE (smaller X)
            nose = FaceClusteringEngine.Point2D(290f, 215f),
            mouth = FaceClusteringEngine.Point2D(290f, 255f),
            hasClassification = true
        )
        assertTrue(valid)

        // Reject missing mouth/classification (typical animal false positive)
        assertFalse(
            FaceClusteringEngine.isHumanFaceGeometryValid(
                bboxLeft = 200f,
                bboxTop = 100f,
                bboxRight = 380f,
                bboxBottom = 290f,
                imageWidthPx = 768f,
                eulerY = 5f,
                eulerX = 0f,
                eyeA = FaceClusteringEngine.Point2D(325f, 175f),
                eyeB = FaceClusteringEngine.Point2D(255f, 175f),
                nose = FaceClusteringEngine.Point2D(290f, 215f),
                mouth = null,
                hasClassification = true
            )
        )
        // Reject impossible anatomy (mouth above nose)
        assertFalse(
            FaceClusteringEngine.isHumanFaceGeometryValid(
                bboxLeft = 200f,
                bboxTop = 100f,
                bboxRight = 380f,
                bboxBottom = 290f,
                imageWidthPx = 768f,
                eulerY = 5f,
                eulerX = 0f,
                eyeA = FaceClusteringEngine.Point2D(325f, 175f),
                eyeB = FaceClusteringEngine.Point2D(255f, 175f),
                nose = FaceClusteringEngine.Point2D(290f, 250f),
                mouth = FaceClusteringEngine.Point2D(290f, 210f),
                hasClassification = true
            )
        )
        // Reject extreme side profile (>38 deg yaw)
        assertFalse(
            FaceClusteringEngine.isHumanFaceGeometryValid(
                bboxLeft = 200f,
                bboxTop = 100f,
                bboxRight = 380f,
                bboxBottom = 290f,
                imageWidthPx = 768f,
                eulerY = 46f,
                eulerX = 0f,
                eyeA = FaceClusteringEngine.Point2D(325f, 175f),
                eyeB = FaceClusteringEngine.Point2D(255f, 175f),
                nose = FaceClusteringEngine.Point2D(290f, 215f),
                mouth = FaceClusteringEngine.Point2D(290f, 255f),
                hasClassification = true
            )
        )
    }

    @Test
    fun nonHumanFaceCropVeto_blocksParrotsDogsToysAndPareidoliaButAllowsRealHumans() {
        // Parrot detected falsely as a face -> MUST be vetoed from People
        assertTrue(
            SmartObjectVocabulary.isNonHumanFaceCrop(
                cropMlKitLabels = listOf("Bird" to 0.88f, "Beak" to 0.72f, "Eye" to 0.51f),
                cropImageNetLabels = listOf("macaw" to 0.76f, "lorikeet" to 0.12f)
            )
        )
        // African grey parrot even if ML Kit only returned weak labels
        assertTrue(
            SmartObjectVocabulary.isNonHumanFaceCrop(
                cropMlKitLabels = listOf("Feather" to 0.61f),
                cropImageNetLabels = listOf("African grey" to 0.44f)
            )
        )
        // Dog pet face -> vetoed
        assertTrue(
            SmartObjectVocabulary.isNonHumanFaceCrop(
                cropMlKitLabels = listOf("Dog" to 0.91f, "Pet" to 0.84f),
                cropImageNetLabels = listOf("golden retriever" to 0.68f)
            )
        )
        // Wall / tree bark pareidolia with zero human cues -> vetoed
        assertTrue(
            SmartObjectVocabulary.isNonHumanFaceCrop(
                cropMlKitLabels = listOf("Wood" to 0.80f, "Pattern" to 0.72f),
                cropImageNetLabels = listOf("picket fence" to 0.52f)
            )
        )
        // Real human portrait -> allowed!
        assertFalse(
            SmartObjectVocabulary.isNonHumanFaceCrop(
                cropMlKitLabels = listOf("Face" to 0.92f, "Smile" to 0.84f, "Hair" to 0.78f, "Person" to 0.95f),
                cropImageNetLabels = listOf("sunglasses" to 0.34f, "jersey" to 0.18f)
            )
        )
    }

    @Test
    fun mergedObjectVocabulary_indexesParrotWithDutchAndEnglishSearchTokens() {
        val merged = SmartObjectVocabulary.mergeSceneAndObjectLabels(
            mlKitLabels = listOf("Bird" to 0.91f, "Fun" to 0.88f, "Feather" to 0.79f),
            imageNetLabels = listOf("macaw" to 0.81f, "lorikeet" to 0.10f)
        )
        assertTrue("Expected Parrot display label: ${merged.displayLabels}", "Parrot" in merged.displayLabels)
        assertTrue("Expected Bird display label: ${merged.displayLabels}", "Bird" in merged.displayLabels)
        assertFalse("Noisy generic label 'Fun' must be dropped", "Fun" in merged.displayLabels)
        assertTrue(merged.searchTokens.contains("parrot"))
        assertTrue(merged.searchTokens.contains("papegaai"))
        assertTrue(merged.searchTokens.contains("macaw"))
        assertTrue(merged.searchTokens.contains("vogel"))
        assertEquals(SmartObjectVocabulary.ObjectCategory.PETS, SmartObjectVocabulary.categoryForLabel("Parrot"))
    }

    @Test
    fun embeddingEncoding_roundTripsWithoutLoss() {
        val original = FaceClusteringEngine.l2Normalize(floatArrayOf(0.3f, -0.7f, 0.25f, 0.9f, -0.1f, 0.44f))
        val blob = FaceClusteringEngine.encodeEmbedding(original)
        val decoded = FaceClusteringEngine.decodeEmbedding(blob)
        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertEquals(original[i], decoded[i], 1e-6f)
        }
        assertEquals(1.0f, FaceClusteringEngine.cosineSimilarity(original, decoded), 1e-5f)
    }

    @Test
    fun clusterMatchingAndConsolidation_respectSamePhotoAndDifferentNameGuards() {
        val aliceA = FaceClusteringEngine.l2Normalize(floatArrayOf(1f, 0.10f, 0.05f, 0f))
        val aliceB = FaceClusteringEngine.l2Normalize(floatArrayOf(0.98f, 0.16f, 0.07f, 0.01f))
        val bob = FaceClusteringEngine.l2Normalize(floatArrayOf(0f, 0.05f, 0.95f, 0.3f))

        val candidates = listOf(
            FaceClusteringEngine.ClusterCandidate("p_alice", aliceA, sampleCount = 3, isNamed = true, photoIds = setOf("img1", "img2")),
            FaceClusteringEngine.ClusterCandidate("p_alice_split", aliceB, sampleCount = 2, isNamed = false, photoIds = setOf("img3", "img4")),
            FaceClusteringEngine.ClusterCandidate("p_bob", bob, sampleCount = 2, isNamed = false, photoIds = setOf("img1"))
        )

        val matched = FaceClusteringEngine.findBestMatchingCluster(
            embedding = aliceB,
            candidates = listOf(candidates[0], candidates[2])
        )
        assertEquals("p_alice", matched?.personId)

        // Two faces in the exact same photo must NEVER attach to the same person cluster
        assertNull(
            FaceClusteringEngine.findBestMatchingCluster(
                embedding = aliceB,
                candidates = candidates,
                forbiddenPersonIds = setOf("p_alice", "p_alice_split")
            )
        )

        // Consolidation auto-merges split unnamed cluster into named cluster when no photos overlap
        val merges = FaceClusteringEngine.findAutoConsolidationPairs(candidates)
        assertEquals(1, merges.size)
        assertEquals("p_alice", merges.first().targetPersonId)
        assertEquals("p_alice_split", merges.first().sourcePersonId)

        // If two similar clusters share a photo (e.g. twins standing side by side), NEVER auto-merge!
        val twins = listOf(
            FaceClusteringEngine.ClusterCandidate("twin_1", aliceA, sampleCount = 3, photoIds = setOf("shared_photo")),
            FaceClusteringEngine.ClusterCandidate("twin_2", aliceB, sampleCount = 3, photoIds = setOf("shared_photo"))
        )
        assertTrue(FaceClusteringEngine.findAutoConsolidationPairs(twins).isEmpty())
    }

    @Test
    fun interactiveMergeSuggestions_detectsGrayZonePairsAndRespectsRejections() {
        val base = FaceClusteringEngine.l2Normalize(floatArrayOf(1f, 0f, 0f, 0f))
        // Cosine similarity ~0.58 -> gray zone (sunglasses / different lighting)
        val sunglasses = FaceClusteringEngine.l2Normalize(floatArrayOf(0.58f, 0.81f, 0f, 0f))
        val other = FaceClusteringEngine.l2Normalize(floatArrayOf(0f, 0f, 1f, 0f))

        val clusters = listOf(
            FaceClusteringEngine.ClusterCandidate("p1", base, sampleCount = 5, isNamed = true, photoIds = setOf("a", "b")),
            FaceClusteringEngine.ClusterCandidate("p2", sunglasses, sampleCount = 2, isNamed = false, photoIds = setOf("c", "d")),
            FaceClusteringEngine.ClusterCandidate("p3", other, sampleCount = 4, isNamed = false, photoIds = setOf("e"))
        )

        val suggestions = FaceClusteringEngine.findInteractiveMergeSuggestions(clusters)
        assertEquals(1, suggestions.size)
        assertEquals("p1", suggestions.first().targetPersonId)
        assertEquals("p2", suggestions.first().sourcePersonId)

        // Once user marks "Not the same", it must NEVER be suggested or auto-merged again
        val rejected = setOf(FaceClusteringEngine.canonicalPairKey("p2", "p1"))
        assertTrue(FaceClusteringEngine.findInteractiveMergeSuggestions(clusters, rejectedPairs = rejected).isEmpty())
    }

    @Test
    fun preservedProfileMatching_restoresNamesAndPinsAfterIndexRebuild() {
        val preserved = listOf(
            FaceClusteringEngine.PreservedPersonProfile(
                name = "Mom",
                isFavorite = true,
                photoIds = setOf("p1", "p2", "p3", "p4")
            ),
            FaceClusteringEngine.PreservedPersonProfile(
                name = "Alex",
                isFavorite = false,
                photoIds = setOf("p10", "p11", "p12")
            )
        )
        val dummyVec = FaceClusteringEngine.l2Normalize(floatArrayOf(1f, 0f, 0f, 0f))
        val rebuiltClusters = listOf(
            FaceClusteringEngine.ClusterCandidate("new_1", dummyVec, sampleCount = 3, isNamed = false, photoIds = setOf("p1", "p2", "p5")),
            FaceClusteringEngine.ClusterCandidate("new_2", dummyVec, sampleCount = 2, isNamed = false, photoIds = setOf("p10", "p11")),
            FaceClusteringEngine.ClusterCandidate("new_3", dummyVec, sampleCount = 2, isNamed = false, photoIds = setOf("p99", "p100"))
        )

        val restored = FaceClusteringEngine.matchPreservedProfiles(preserved, rebuiltClusters)
        assertEquals(2, restored.size)
        assertEquals("Mom", restored.first { it.personId == "new_1" }.profile.name)
        assertTrue(restored.first { it.personId == "new_1" }.profile.isFavorite)
        assertEquals("Alex", restored.first { it.personId == "new_2" }.profile.name)
    }

    @Test
    fun centeredAvatarCropRect_neverExceedsPhotoBoundsAndKeepsFaceCentered() {
        // 1. Standard portrait face near center: exact 1.62x square centered on face
        val centered = FaceEmbeddingEngine.computeCenteredAvatarCropRect(
            imageWidth = 1000,
            imageHeight = 1000,
            faceLeft = 400f,
            faceTop = 400f,
            faceRight = 600f,
            faceBottom = 600f
        )!!
        assertEquals(324f, centered.right - centered.left, 0.5f)
        assertEquals(324f, centered.bottom - centered.top, 0.5f)
        assertEquals(500f, (centered.left + centered.right) * 0.5f, 0.5f)
        assertTrue(centered.left >= 0f && centered.top >= 0f && centered.right <= 1000f && centered.bottom <= 1000f)

        // 2. Face near top edge: shrinks symmetrically so face stays centered with ZERO black bar
        val nearTop = FaceEmbeddingEngine.computeCenteredAvatarCropRect(
            imageWidth = 1000,
            imageHeight = 1000,
            faceLeft = 400f,
            faceTop = 40f,
            faceRight = 600f,
            faceBottom = 240f
        )!!
        assertEquals(0f, nearTop.top, 0.01f)
        assertEquals(500f, (nearTop.left + nearTop.right) * 0.5f, 0.5f)
        assertEquals(nearTop.right - nearTop.left, nearTop.bottom - nearTop.top, 0.5f)
        assertTrue(nearTop.left >= 0f && nearTop.right <= 1000f && nearTop.bottom <= 1000f)

        // 3. Face touching left edge: clamps strictly inside [0..W, 0..H] without clipping the face
        val leftEdge = FaceEmbeddingEngine.computeCenteredAvatarCropRect(
            imageWidth = 1000,
            imageHeight = 1000,
            faceLeft = 0f,
            faceTop = 400f,
            faceRight = 200f,
            faceBottom = 600f
        )!!
        assertEquals(0f, leftEdge.left, 0.01f)
        assertTrue(leftEdge.right >= 200f && leftEdge.right <= 1000f)
        assertTrue(leftEdge.top >= 0f && leftEdge.bottom <= 1000f)

        // 4. Close-up face taller than landscape height: clamped cleanly to image height
        val closeUp = FaceEmbeddingEngine.computeCenteredAvatarCropRect(
            imageWidth = 1200,
            imageHeight = 600,
            faceLeft = 350f,
            faceTop = 10f,
            faceRight = 850f,
            faceBottom = 590f
        )!!
        assertEquals(600f, closeUp.right - closeUp.left, 0.5f)
        assertEquals(600f, closeUp.bottom - closeUp.top, 0.5f)
        assertEquals(0f, closeUp.top, 0.01f)
        assertEquals(600f, closeUp.bottom, 0.01f)
    }

    @Test
    fun incrementalSmartScan_skips30kAlreadyIndexedPhotosAndScansOnlyNewOrModified() {
        val emptyExif = ExifMetadata(null, null, null, null, null, null, null, "")
        fun item(id: String, epochMs: Long) = MediaItem(
            id = id,
            parentId = "local_bucket_cam",
            name = "$id.jpg",
            mimeType = "image/jpeg",
            mediaType = MediaType.PHOTO,
            sizeBytes = 1024L,
            createdTimeEpochMs = epochMs,
            monthYearLabel = "Nov 2026",
            fullDateLabel = "Nov 26, 2026",
            thumbnailLink = null,
            width = 1000,
            height = 1000,
            rotation = 0,
            durationMillis = null,
            exif = emptyExif,
            isLocal = true,
            localContentUri = "content://media/$id"
        )

        val totalExisting = 30_000
        val stamps = HashMap<String, Long>(totalExisting * 2)
        val eligible = ArrayList<MediaItem>(totalExisting + 5)
        for (i in 0 until totalExisting) {
            val id = "local_$i"
            val ts = 1_700_000_000_000L + i
            stamps[id] = ts
            // Modify 1 existing item's timestamp to verify edited files are re-scanned
            eligible.add(item(id, if (i == 42) ts + 9999L else ts))
        }
        // Add 5 brand-new photos
        for (j in 0 until 5) {
            eligible.add(item("local_new_$j", 1_750_000_000_000L + j))
        }

        val toScan = SmartRecognitionManager.filterUnindexedItems(eligible, stamps)
        assertEquals(6, toScan.size)
        assertTrue(toScan.any { it.id == "local_42" })
        for (j in 0 until 5) {
            assertTrue(toScan.any { it.id == "local_new_$j" })
        }

        // Wi-Fi continuation guard
        assertFalse(SmartRecognitionManager.shouldStopScanForWifi(requireWifiThroughout = true, isUnmeteredWifi = true))
        assertTrue(SmartRecognitionManager.shouldStopScanForWifi(requireWifiThroughout = true, isUnmeteredWifi = false))
        assertFalse(SmartRecognitionManager.shouldStopScanForWifi(requireWifiThroughout = false, isUnmeteredWifi = false))
    }

    @Test
    fun bundledAiModelAssets_existWithValidTfliteHeadersAnd1000Labels() {
        fun findAsset(name: String): File {
            return listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
                .firstOrNull { it.exists() }
                ?: error("Missing bundled asset: $name")
        }

        val faceModel = findAsset("mobilefacenet.tflite")
        val faceBytes = faceModel.readBytes()
        assertTrue("mobilefacenet.tflite should be > 1 MB", faceBytes.size > 1_000_000)
        assertEquals("TFL3", String(faceBytes, 4, 4, Charsets.US_ASCII))

        val objModel = findAsset("efficientnet_lite2.tflite")
        val objBytes = objModel.readBytes()
        assertTrue("efficientnet_lite2.tflite should be > 4 MB", objBytes.size > 4_000_000)
        assertEquals("TFL3", String(objBytes, 4, 4, Charsets.US_ASCII))

        val labelsFile = findAsset("imagenet_1000_labels.txt")
        val labels = labelsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(1000, labels.size)
        assertTrue(labels.contains("macaw"))
        assertTrue(labels.contains("African grey"))
        assertTrue(labels.contains("sulphur-crested cockatoo"))
    }

    @Test
    fun personMemoryEngine_matchesGroupPhotoFacesByLocationAndRespectsExclusions() {
        val validMemIds = setOf("mem_mom", "mem_dad")
        val anchors = listOf(
            SavedPersonAnchorEntity(
                anchorId = "a_mom",
                memoryId = "mem_mom",
                itemId = "group_photo_1",
                bboxLeft = 0.10f,
                bboxTop = 0.20f,
                bboxRight = 0.28f,
                bboxBottom = 0.45f,
                originGroupId = "orig_mom",
                updatedAtEpochMs = 100L
            ),
            SavedPersonAnchorEntity(
                anchorId = "a_dad",
                memoryId = "mem_dad",
                itemId = "group_photo_1",
                bboxLeft = 0.65f,
                bboxTop = 0.18f,
                bboxRight = 0.84f,
                bboxBottom = 0.44f,
                originGroupId = "orig_dad",
                updatedAtEpochMs = 100L
            ),
            SavedPersonAnchorEntity(
                anchorId = "a_excl_stranger",
                memoryId = "mem_mom",
                itemId = "group_photo_1",
                bboxLeft = 0.40f,
                bboxTop = 0.22f,
                bboxRight = 0.56f,
                bboxBottom = 0.44f,
                originGroupId = "orig_mom",
                isUserAssigned = true,
                isExcluded = true,
                updatedAtEpochMs = 200L
            )
        )

        // New detector produces slightly looser box around Mom and slightly tighter box around Dad
        val momNewBox = PersonMemoryEngine.FaceBox(0.08f, 0.17f, 0.30f, 0.48f)
        val dadNewBox = PersonMemoryEngine.FaceBox(0.67f, 0.21f, 0.82f, 0.41f)
        val strangerBox = PersonMemoryEngine.FaceBox(0.41f, 0.23f, 0.55f, 0.43f)

        assertEquals(
            "mem_mom",
            PersonMemoryEngine.findBestPositiveMemoryId(momNewBox, anchors, validMemIds, singleFaceInPhoto = false)
        )
        assertEquals(
            "mem_dad",
            PersonMemoryEngine.findBestPositiveMemoryId(dadNewBox, anchors, validMemIds, singleFaceInPhoto = false)
        )
        assertEquals(
            setOf("mem_mom"),
            PersonMemoryEngine.findExcludedMemoryIds(strangerBox, anchors, singleFaceInPhoto = false)
        )
        assertNull(
            PersonMemoryEngine.findBestPositiveMemoryId(
                detected = strangerBox,
                anchorsForItem = anchors,
                validMemoryIds = validMemIds,
                excludedMemoryIds = setOf("mem_mom"),
                singleFaceInPhoto = false
            )
        )
    }

    @Test
    fun personMemoryEngine_reconciliationRestoresNamesCoversAndAutoRecombinesMergedGroups() {
        val memories = listOf(
            SavedPersonMemoryEntity(
                memoryId = "mem_alex",
                name = "Alex",
                isFavorite = true,
                isHidden = false,
                isUserMerged = true,
                coverItemId = "p2",
                coverBboxLeft = 0.20f,
                coverBboxTop = 0.20f,
                coverBboxRight = 0.50f,
                coverBboxBottom = 0.55f,
                updatedAtEpochMs = 1_000L
            ),
            SavedPersonMemoryEntity(
                memoryId = "mem_unnamed_combo",
                name = "",
                isFavorite = false,
                isHidden = false,
                isUserMerged = true,
                updatedAtEpochMs = 1_000L
            )
        )
        val box = PersonMemoryEngine.FaceBox(0.20f, 0.20f, 0.50f, 0.55f)
        val anchors = listOf(
            SavedPersonAnchorEntity("a1", "mem_alex", "p1", box.left, box.top, box.right, box.bottom, "g_normal", updatedAtEpochMs = 100L),
            SavedPersonAnchorEntity("a2", "mem_alex", "p2", box.left, box.top, box.right, box.bottom, "g_normal", updatedAtEpochMs = 100L),
            SavedPersonAnchorEntity("a3", "mem_alex", "p3", box.left, box.top, box.right, box.bottom, "g_sunglasses", updatedAtEpochMs = 100L),
            SavedPersonAnchorEntity("a4", "mem_unnamed_combo", "p10", box.left, box.top, box.right, box.bottom, "g_u1", updatedAtEpochMs = 100L),
            SavedPersonAnchorEntity("a5", "mem_unnamed_combo", "p11", box.left, box.top, box.right, box.bottom, "g_u2", updatedAtEpochMs = 100L)
        )

        // After changing detection/embeddings, Alex split into new_alex_1 & new_alex_sunglasses,
        // and the unnamed user-merged pair split into new_u1 & new_u2
        val clusters = listOf(
            PersonMemoryEngine.ActiveClusterSnapshot("new_alex_1", null, "", false, false, "f1", "p1", 2, setOf("p1", "p2")),
            PersonMemoryEngine.ActiveClusterSnapshot("new_alex_sunglasses", null, "", false, false, "f3", "p3", 1, setOf("p3")),
            PersonMemoryEngine.ActiveClusterSnapshot("new_u1", null, "", false, false, "f10", "p10", 1, setOf("p10")),
            PersonMemoryEngine.ActiveClusterSnapshot("new_u2", null, "", false, false, "f11", "p11", 1, setOf("p11"))
        )
        val faces = listOf(
            PersonMemoryEngine.ActiveFaceSnapshot("f1", "p1", "new_alex_1", box, 0.8f, "/tmp/f1.jpg"),
            PersonMemoryEngine.ActiveFaceSnapshot("f2", "p2", "new_alex_1", box, 0.7f, "/tmp/f2.jpg"),
            PersonMemoryEngine.ActiveFaceSnapshot("f3", "p3", "new_alex_sunglasses", box, 0.6f, "/tmp/f3.jpg"),
            PersonMemoryEngine.ActiveFaceSnapshot("f10", "p10", "new_u1", box, 0.7f, "/tmp/f10.jpg"),
            PersonMemoryEngine.ActiveFaceSnapshot("f11", "p11", "new_u2", box, 0.7f, "/tmp/f11.jpg")
        )

        val plan = PersonMemoryEngine.planReconciliation(memories, anchors, clusters, faces)

        // Alex is linked & named, his sunglasses split is scheduled to auto-merge back, and custom cover p2 is restored
        val alexLink = plan.links.firstOrNull { it.personId == "new_alex_1" }
        assertNotNull(alexLink)
        assertEquals("Alex", alexLink?.memory?.name)
        assertTrue(alexLink?.memory?.isFavorite == true)
        assertTrue(plan.merges.any { it.targetPersonId == "new_alex_1" && it.sourcePersonId == "new_alex_sunglasses" })
        assertTrue(plan.coverRestores.any { it.personId == "new_alex_1" && it.faceId == "f2" && it.itemId == "p2" })

        // Unnamed combined pair is ALSO auto-recombined!
        assertTrue(
            plan.merges.any {
                (it.targetPersonId == "new_u1" && it.sourcePersonId == "new_u2") ||
                    (it.targetPersonId == "new_u2" && it.sourcePersonId == "new_u1")
            }
        )
    }

    @Test
    fun personMemoryEngine_balancedPruningPreservesSmallCombinedSubgroupsAndCover() {
        val bigGroup = (1..200).map { idx ->
            SavedPersonAnchorEntity(
                anchorId = "big_$idx",
                memoryId = "mem_1",
                itemId = "photo_big_$idx",
                bboxLeft = 0.2f,
                bboxTop = 0.2f,
                bboxRight = 0.4f,
                bboxBottom = 0.4f,
                originGroupId = "group_big",
                qualityScore = 0.9f,
                updatedAtEpochMs = idx.toLong()
            )
        }
        val sunglassesGroup = (1..3).map { idx ->
            SavedPersonAnchorEntity(
                anchorId = "sun_$idx",
                memoryId = "mem_1",
                itemId = "photo_sun_$idx",
                bboxLeft = 0.2f,
                bboxTop = 0.2f,
                bboxRight = 0.4f,
                bboxBottom = 0.4f,
                originGroupId = "group_sunglasses",
                qualityScore = 0.4f,
                updatedAtEpochMs = 500L + idx
            )
        }
        val childhoodGroup = (1..2).map { idx ->
            SavedPersonAnchorEntity(
                anchorId = "child_$idx",
                memoryId = "mem_1",
                itemId = "photo_child_$idx",
                bboxLeft = 0.2f,
                bboxTop = 0.2f,
                bboxRight = 0.4f,
                bboxBottom = 0.4f,
                originGroupId = "group_childhood",
                qualityScore = 0.3f,
                updatedAtEpochMs = 600L + idx
            )
        }
        val manualPin = SavedPersonAnchorEntity(
            anchorId = "manual_1",
            memoryId = "mem_1",
            itemId = "photo_manual",
            bboxLeft = 0.2f,
            bboxTop = 0.2f,
            bboxRight = 0.4f,
            bboxBottom = 0.4f,
            originGroupId = "reassigned",
            isUserAssigned = true,
            qualityScore = 0.2f,
            updatedAtEpochMs = 700L
        )

        val kept = PersonMemoryEngine.pruneAndDeduplicateAnchors(
            anchors = bigGroup + sunglassesGroup + childhoodGroup + manualPin,
            maxPositiveCount = 30,
            coverItemId = "photo_big_1",
            coverBox = PersonMemoryEngine.FaceBox(0.2f, 0.2f, 0.4f, 0.4f)
        )
        assertEquals(30, kept.size)
        val keptIds = kept.map { it.anchorId }.toSet()
        assertTrue("Saved cover must survive pruning", "big_1" in keptIds)
        assertTrue("User-reassigned face must survive pruning", "manual_1" in keptIds)
        assertTrue("All 3 sunglasses subgroup anchors must survive", sunglassesGroup.all { it.anchorId in keptIds })
        assertTrue("All 2 childhood subgroup anchors must survive", childhoodGroup.all { it.anchorId in keptIds })
    }

    @Test
    fun durableMemoryRejection_blocksSuggestionsAndConsolidationEvenWithNewPersonIds() {
        val vecA = FaceClusteringEngine.l2Normalize(floatArrayOf(1f, 0.08f, 0f, 0f))
        val vecB = FaceClusteringEngine.l2Normalize(floatArrayOf(0.98f, 0.19f, 0f, 0f))
        val rebuiltClusters = listOf(
            FaceClusteringEngine.ClusterCandidate("brand_new_p1", vecA, sampleCount = 4, isNamed = true, photoIds = setOf("a"), memoryId = "mem_twin_a"),
            FaceClusteringEngine.ClusterCandidate("brand_new_p2", vecB, sampleCount = 4, isNamed = false, photoIds = setOf("b"), memoryId = "mem_twin_b")
        )
        val rejected = setOf(FaceClusteringEngine.canonicalPairKey("mem_twin_a", "mem_twin_b"))
        assertTrue(FaceClusteringEngine.findAutoConsolidationPairs(rebuiltClusters, rejectedPairs = rejected).isEmpty())
        assertTrue(FaceClusteringEngine.findInteractiveMergeSuggestions(rebuiltClusters, rejectedPairs = rejected).isEmpty())
    }

    @Test
    fun petRecognition_acceptsObviousDomesticDogsAndCatsAndStrictlyRejectsWildlife() {
        fun probsOf(vararg pairs: Pair<Int, Float>): FloatArray = FloatArray(1000).apply {
            for ((idx, v) in pairs) this[idx] = v
        }

        // 1. Clear domestic Golden Retriever -> accepted as DOG
        val goldenProbs = probsOf(207 to 0.72f, 208 to 0.14f)
        val goldenDet = PetRecognitionEngine.detectDomesticPet(
            probs = goldenProbs,
            analysis = AnimalTaxonomy.analyze(goldenProbs),
            mlKitLabels = listOf("Dog" to 0.94f, "Pet" to 0.88f)
        )
        assertEquals(PetRecognitionEngine.PetSpecies.DOG, goldenDet?.species)

        // 2. Clear domestic Tabby Cat -> accepted as CAT
        val tabbyProbs = probsOf(281 to 0.64f, 282 to 0.16f)
        val tabbyDet = PetRecognitionEngine.detectDomesticPet(
            probs = tabbyProbs,
            analysis = AnimalTaxonomy.analyze(tabbyProbs),
            mlKitLabels = listOf("Cat" to 0.91f)
        )
        assertEquals(PetRecognitionEngine.PetSpecies.CAT, tabbyDet?.species)

        // 3. Lion (index 291, BIG_CAT) -> strictly REJECTED
        val lionProbs = probsOf(291 to 0.85f)
        assertNull(
            PetRecognitionEngine.detectDomesticPet(lionProbs, AnimalTaxonomy.analyze(lionProbs), listOf("Lion" to 0.95f))
        )

        // 4. Ambiguous big-cat cub leaking onto tabby -> strictly REJECTED
        val cheetahLeakProbs = probsOf(281 to 0.56f, 293 to 0.12f)
        assertNull(
            PetRecognitionEngine.detectDomesticPet(cheetahLeakProbs, AnimalTaxonomy.analyze(cheetahLeakProbs), listOf("Cat" to 0.70f))
        )

        // 5. Wolf / wild canine leaking onto Husky -> strictly REJECTED
        val wolfLeakProbs = probsOf(250 to 0.58f, 269 to 0.11f)
        assertNull(
            PetRecognitionEngine.detectDomesticPet(wolfLeakProbs, AnimalTaxonomy.analyze(wolfLeakProbs), listOf("Dog" to 0.70f))
        )

        // 6. Monkey / macaque (index 373) or monkey leaking onto affenpinscher (252) -> strictly REJECTED
        val monkeyProbs = probsOf(373 to 0.78f)
        assertNull(
            PetRecognitionEngine.detectDomesticPet(monkeyProbs, AnimalTaxonomy.analyze(monkeyProbs), listOf("Monkey" to 0.90f))
        )
        val monkeyDogLeakProbs = probsOf(252 to 0.58f, 373 to 0.08f)
        assertNull(
            PetRecognitionEngine.detectDomesticPet(monkeyDogLeakProbs, AnimalTaxonomy.analyze(monkeyDogLeakProbs))
        )

        // 7. Zoo / safari / wildlife photo -> strictly REJECTED even if ImageNet guesses a dog/cat
        assertNull(
            PetRecognitionEngine.detectDomesticPet(
                probs = goldenProbs,
                analysis = AnimalTaxonomy.analyze(goldenProbs),
                mlKitLabels = listOf("Wildlife" to 0.85f, "Safari" to 0.72f)
            )
        )
    }

    @Test
    fun petRecognition_clustersSamePetSeparatesDifferentPetsAndHidesSinglePhotoStrays() {
        fun solidPixels(r: Int, g: Int, b: Int, size: Int = 64): IntArray {
            val argb = (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
            return IntArray(size * size) { argb }
        }
        fun probsOf(vararg pairs: Pair<Int, Float>): FloatArray = FloatArray(1000).apply {
            for ((idx, v) in pairs) this[idx] = v
        }

        // Two photos of the same orange tabby cat under slightly different brightness -> cluster together!
        val tabbyProbsA = probsOf(281 to 0.65f, 282 to 0.20f)
        val tabbyProbsB = probsOf(281 to 0.58f, 282 to 0.24f)
        val orangeCoatA = PetRecognitionEngine.computeCoatColorDescriptor(solidPixels(215, 130, 55), 64, 64)
        val orangeCoatB = PetRecognitionEngine.computeCoatColorDescriptor(solidPixels(205, 122, 48), 64, 64)
        val blackCoat = PetRecognitionEngine.computeCoatColorDescriptor(solidPixels(24, 24, 28), 64, 64)

        val orangeCatA = PetRecognitionEngine.buildPetEmbedding(PetRecognitionEngine.PetSpecies.CAT, tabbyProbsA, orangeCoatA)
        val orangeCatB = PetRecognitionEngine.buildPetEmbedding(PetRecognitionEngine.PetSpecies.CAT, tabbyProbsB, orangeCoatB)
        val blackCat = PetRecognitionEngine.buildPetEmbedding(PetRecognitionEngine.PetSpecies.CAT, tabbyProbsA, blackCoat)

        val sameCatSim = FaceClusteringEngine.cosineSimilarity(orangeCatA, orangeCatB)
        val diffCatSim = FaceClusteringEngine.cosineSimilarity(orangeCatA, blackCat)
        assertTrue("Same orange tabby across lighting should match ($sameCatSim)", sameCatSim >= PetRecognitionEngine.PET_SINGLETON_MATCH_THRESHOLD)
        assertTrue("Orange tabby vs black cat should stay separate ($diffCatSim)", diffCatSim < PetRecognitionEngine.PET_CLUSTER_MATCH_THRESHOLD)

        // Golden Retriever vs Siberian Husky -> completely separate
        val goldenEmb = PetRecognitionEngine.buildPetEmbedding(
            PetRecognitionEngine.PetSpecies.DOG,
            probsOf(207 to 0.80f),
            PetRecognitionEngine.computeCoatColorDescriptor(solidPixels(210, 175, 110), 64, 64)
        )
        val huskyEmb = PetRecognitionEngine.buildPetEmbedding(
            PetRecognitionEngine.PetSpecies.DOG,
            probsOf(250 to 0.78f),
            PetRecognitionEngine.computeCoatColorDescriptor(solidPixels(185, 190, 195), 64, 64)
        )
        assertTrue(FaceClusteringEngine.cosineSimilarity(goldenEmb, huskyEmb) < 0.45f)

        // Single-photo random stray dog/cat must be HIDDEN; multi-photo (2+) or named pets are SHOWN
        assertFalse(PetRecognitionEngine.shouldDisplayCluster(PersonClusterEntity.KIND_PET_DOG, photoCount = 1, isNamed = false, isFavorite = false))
        assertFalse(PetRecognitionEngine.shouldDisplayCluster(PersonClusterEntity.KIND_PET_CAT, photoCount = 1, isNamed = false, isFavorite = false))
        assertTrue(PetRecognitionEngine.shouldDisplayCluster(PersonClusterEntity.KIND_PET_DOG, photoCount = 2, isNamed = false, isFavorite = false))
        assertTrue(PetRecognitionEngine.shouldDisplayCluster(PersonClusterEntity.KIND_PET_CAT, photoCount = 1, isNamed = true, isFavorite = false))
        assertTrue(PetRecognitionEngine.shouldDisplayCluster(PersonClusterEntity.KIND_HUMAN, photoCount = 1, isNamed = false, isFavorite = false))

        // Different entity kinds (e.g. Dog vs Cat or Human vs Dog) must NEVER auto-consolidate or cross-suggest
        val crossKindClusters = listOf(
            FaceClusteringEngine.ClusterCandidate("p_dog", goldenEmb, sampleCount = 3, entityKind = PersonClusterEntity.KIND_PET_DOG),
            FaceClusteringEngine.ClusterCandidate("p_cat", goldenEmb, sampleCount = 3, entityKind = PersonClusterEntity.KIND_PET_CAT)
        )
        assertTrue(FaceClusteringEngine.findAutoConsolidationPairs(crossKindClusters).isEmpty())
        assertTrue(FaceClusteringEngine.findInteractiveMergeSuggestions(crossKindClusters).isEmpty())
    }
}

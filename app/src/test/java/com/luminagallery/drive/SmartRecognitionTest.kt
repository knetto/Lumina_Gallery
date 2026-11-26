package com.luminagallery.drive

import com.luminagallery.drive.data.repository.ai.FaceClusteringEngine
import com.luminagallery.drive.data.repository.ai.SmartObjectVocabulary
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
}

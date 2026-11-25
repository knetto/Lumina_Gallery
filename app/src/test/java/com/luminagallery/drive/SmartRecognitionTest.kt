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

class SmartRecognitionTest {

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
    fun clusterMatching_matchesSamePersonRejectsStrangerAndForbidsSamePhotoDuplicates() {
        val alice = FaceClusteringEngine.l2Normalize(floatArrayOf(1f, 0.1f, 0.05f, 0f))
        val alice2 = FaceClusteringEngine.l2Normalize(floatArrayOf(0.97f, 0.18f, 0.08f, 0.02f))
        val bob = FaceClusteringEngine.l2Normalize(floatArrayOf(0f, 0.05f, 0.95f, 0.3f))

        val candidates = listOf(
            FaceClusteringEngine.ClusterCandidate("p_alice", alice, sampleCount = 3),
            FaceClusteringEngine.ClusterCandidate("p_bob", bob, sampleCount = 2)
        )

        val matched = FaceClusteringEngine.findBestMatchingCluster(alice2, candidates)
        assertNotNull(matched)
        assertEquals("p_alice", matched?.personId)

        // Two faces in the exact same photo must NEVER attach to the same person cluster
        val blockedSamePhoto = FaceClusteringEngine.findBestMatchingCluster(
            embedding = alice2,
            candidates = candidates,
            forbiddenPersonIds = setOf("p_alice")
        )
        assertNull(blockedSamePhoto)

        // Unrelated face should not match either cluster
        val stranger = FaceClusteringEngine.l2Normalize(floatArrayOf(0.1f, -0.95f, -0.1f, 0.25f))
        assertNull(FaceClusteringEngine.findBestMatchingCluster(stranger, candidates))
    }

    @Test
    fun mergeCentroidAveraging_producesUnitVectorCloserToBothSides() {
        val v1 = FaceClusteringEngine.l2Normalize(floatArrayOf(1f, 0f, 0f, 0f))
        val v2 = FaceClusteringEngine.l2Normalize(floatArrayOf(0.6f, 0.8f, 0f, 0f))
        val merged = FaceClusteringEngine.averageNormalizedEmbeddings(listOf(v1, v2))
        assertEquals(1f, FaceClusteringEngine.cosineSimilarity(merged, merged), 1e-5f)
        assertTrue(FaceClusteringEngine.cosineSimilarity(merged, v1) > 0.88f)
        assertTrue(FaceClusteringEngine.cosineSimilarity(merged, v2) > 0.88f)
    }

    @Test
    fun faceQualityFilter_rejectsTinyOrExtremeProfilesAndPrefersFrontalPortraits() {
        assertFalse(
            FaceClusteringEngine.isFaceUsableForRecognition(
                faceWidthPx = 28f,
                faceHeightPx = 28f,
                imageWidthPx = 768f,
                eulerY = 5f,
                eulerX = 2f
            )
        )
        assertFalse(
            FaceClusteringEngine.isFaceUsableForRecognition(
                faceWidthPx = 120f,
                faceHeightPx = 120f,
                imageWidthPx = 768f,
                eulerY = 68f,
                eulerX = 5f
            )
        )
        assertTrue(
            FaceClusteringEngine.isFaceUsableForRecognition(
                faceWidthPx = 140f,
                faceHeightPx = 140f,
                imageWidthPx = 768f,
                eulerY = 12f,
                eulerX = -4f
            )
        )

        val frontalPortrait = FaceClusteringEngine.computeFaceQualityScore(
            faceWidthPx = 240f,
            faceHeightPx = 240f,
            imageWidthPx = 768f,
            eulerY = 3f,
            eulerX = 2f,
            leftEyeOpen = 0.95f,
            rightEyeOpen = 0.96f,
            smilingProb = 0.85f
        )
        val distantTilted = FaceClusteringEngine.computeFaceQualityScore(
            faceWidthPx = 60f,
            faceHeightPx = 60f,
            imageWidthPx = 768f,
            eulerY = 44f,
            eulerX = 30f,
            leftEyeOpen = 0.1f,
            rightEyeOpen = 0.1f,
            smilingProb = 0.0f
        )
        assertTrue("Frontal crisp portrait should outscore small tilted face", frontalPortrait > distantTilted + 0.3f)
    }

    @Test
    fun objectVocabulary_filtersNoiseAndExpandsDutchAndEnglishSynonyms() {
        val filtered = SmartObjectVocabulary.filterUsefulLabels(
            listOf(
                "Fun" to 0.95f,
                "Skin" to 0.92f,
                "Dog" to 0.91f,
                "Beach" to 0.84f,
                "Car" to 0.78f,
                "LowConfidenceThing" to 0.41f
            )
        )
        assertEquals(listOf("Dog", "Beach", "Car"), filtered)

        val tokens = SmartObjectVocabulary.buildSearchTokens(filtered)
        assertTrue(tokens.contains("hond"))
        assertTrue(tokens.contains("pet"))
        assertTrue(tokens.contains("strand"))
        assertTrue(tokens.contains("auto"))
        assertEquals(SmartObjectVocabulary.ObjectCategory.PETS, SmartObjectVocabulary.categoryForLabel("Dog"))
        assertEquals(SmartObjectVocabulary.ObjectCategory.BEACH, SmartObjectVocabulary.categoryForLabel("Beach"))
        assertEquals(SmartObjectVocabulary.ObjectCategory.VEHICLES, SmartObjectVocabulary.categoryForLabel("Car"))
    }

    @Test
    fun bundledMobileFaceNetAsset_existsAndHasValidTfliteHeader() {
        val candidates = listOf(
            File("src/main/assets/mobilefacenet.tflite"),
            File("app/src/main/assets/mobilefacenet.tflite")
        )
        val model = candidates.firstOrNull { it.exists() }
        assertNotNull("mobilefacenet.tflite must be bundled in app/src/main/assets", model)
        val bytes = model!!.readBytes()
        assertTrue("mobilefacenet.tflite should be > 1 MB", bytes.size > 1_000_000)
        val magic = String(bytes, 4, 4, Charsets.US_ASCII)
        assertEquals("TFL3", magic)
    }
}

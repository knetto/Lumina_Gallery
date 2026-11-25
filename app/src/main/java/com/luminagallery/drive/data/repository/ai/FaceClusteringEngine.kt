package com.luminagallery.drive.data.repository.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Pure-Kotlin face embedding serialization, cosine similarity matching, cluster centroid math,
 * and portrait quality scoring. Completely Android-free so every heuristic is covered by JVM tests.
 */
object FaceClusteringEngine {

    /** Similarity threshold for attaching a face to an established multi-photo person cluster. */
    const val CLUSTER_MATCH_THRESHOLD: Float = 0.62f

    /** Slightly stricter threshold for single-sample tentative clusters to prevent early stranger merges. */
    const val SINGLETON_MATCH_THRESHOLD: Float = 0.66f

    /** Minimum normalized face bounding-box dimension relative to image width to keep quality high. */
    const val MIN_RELATIVE_FACE_SIZE: Float = 0.035f

    /** Maximum yaw/pitch head angle (degrees) accepted for reliable recognition embeddings. */
    const val MAX_ABS_EULER_Y: Float = 52f
    const val MAX_ABS_EULER_X: Float = 42f

    data class ClusterCandidate(
        val personId: String,
        val centroid: FloatArray,
        val sampleCount: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ClusterCandidate) return false
            return personId == other.personId &&
                centroid.contentEquals(other.centroid) &&
                sampleCount == other.sampleCount
        }

        override fun hashCode(): Int {
            var result = personId.hashCode()
            result = 31 * result + centroid.contentHashCode()
            result = 31 * result + sampleCount
            return result
        }
    }

    data class MatchResult(
        val personId: String,
        val similarity: Float
    )

    fun encodeEmbedding(vector: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) buf.putFloat(v)
        return buf.array()
    }

    fun decodeEmbedding(blob: ByteArray): FloatArray {
        if (blob.isEmpty() || blob.size % 4 != 0) return FloatArray(0)
        val count = blob.size / 4
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buf.getFloat() }
    }

    fun l2Normalize(vector: FloatArray): FloatArray {
        if (vector.isEmpty()) return vector
        var sumSq = 0.0
        for (v in vector) {
            if (v.isFinite()) sumSq += (v * v).toDouble()
        }
        val norm = sqrt(sumSq).toFloat()
        if (norm <= 1e-8f) return FloatArray(vector.size)
        return FloatArray(vector.size) { i ->
            val v = vector[i]
            if (v.isFinite()) v / norm else 0f
        }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return -1f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denom = sqrt(normA * normB)
        if (denom <= 1e-9) return -1f
        return (dot / denom).toFloat().coerceIn(-1f, 1f)
    }

    fun averageNormalizedEmbeddings(vectors: List<FloatArray>): FloatArray {
        val valid = vectors.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return FloatArray(0)
        val dim = valid.first().size
        val sum = DoubleArray(dim)
        var used = 0
        for (vec in valid) {
            if (vec.size != dim) continue
            for (i in 0 until dim) {
                sum[i] += vec[i].toDouble()
            }
            used++
        }
        if (used == 0) return FloatArray(0)
        val avg = FloatArray(dim) { i -> (sum[i] / used).toFloat() }
        return l2Normalize(avg)
    }

    fun updateRunningCentroid(
        oldCentroid: FloatArray,
        oldCount: Int,
        newEmbedding: FloatArray
    ): FloatArray {
        if (oldCentroid.isEmpty() || oldCount <= 0 || oldCentroid.size != newEmbedding.size) {
            return l2Normalize(newEmbedding)
        }
        val weight = oldCount.coerceIn(1, 200).toFloat()
        val combined = FloatArray(newEmbedding.size) { i ->
            (oldCentroid[i] * weight + newEmbedding[i]) / (weight + 1f)
        }
        return l2Normalize(combined)
    }

    /**
     * Finds the strongest matching existing person cluster for [embedding], strictly forbidding
     * clusters already assigned to another face in the exact same photo ([forbiddenPersonIds]).
     */
    fun findBestMatchingCluster(
        embedding: FloatArray,
        candidates: List<ClusterCandidate>,
        forbiddenPersonIds: Set<String> = emptySet()
    ): MatchResult? {
        if (embedding.isEmpty() || candidates.isEmpty()) return null
        var bestId: String? = null
        var bestSim = -1f
        for (candidate in candidates) {
            if (candidate.personId in forbiddenPersonIds) continue
            if (candidate.centroid.size != embedding.size) continue
            val sim = cosineSimilarity(embedding, candidate.centroid)
            val required = if (candidate.sampleCount <= 1) {
                SINGLETON_MATCH_THRESHOLD
            } else {
                CLUSTER_MATCH_THRESHOLD
            }
            if (sim >= required && sim > bestSim) {
                bestSim = sim
                bestId = candidate.personId
            }
        }
        return bestId?.let { MatchResult(personId = it, similarity = bestSim) }
    }

    fun isFaceUsableForRecognition(
        faceWidthPx: Float,
        faceHeightPx: Float,
        imageWidthPx: Float,
        eulerY: Float,
        eulerX: Float,
        minFacePx: Float = 42f
    ): Boolean {
        if (faceWidthPx < minFacePx || faceHeightPx < minFacePx) return false
        if (imageWidthPx > 0f && (faceWidthPx / imageWidthPx) < MIN_RELATIVE_FACE_SIZE) return false
        if (abs(eulerY) > MAX_ABS_EULER_Y || abs(eulerX) > MAX_ABS_EULER_X) return false
        return true
    }

    /**
     * Scores how flattering and clear a face crop is for picking a person's circular cover avatar:
     * rewards large crisp faces, straight-on pose, open eyes, and a subtle pleasant expression bonus.
     */
    fun computeFaceQualityScore(
        faceWidthPx: Float,
        faceHeightPx: Float,
        imageWidthPx: Float,
        eulerY: Float,
        eulerX: Float,
        leftEyeOpen: Float? = null,
        rightEyeOpen: Float? = null,
        smilingProb: Float? = null
    ): Float {
        val sizeRatio = if (imageWidthPx > 0f) {
            ((faceWidthPx + faceHeightPx) * 0.5f / imageWidthPx).coerceIn(0f, 0.7f)
        } else {
            0.15f
        }
        val sizeScore = (sizeRatio / 0.45f).coerceIn(0f, 1f) * 0.45f
        val yawRad = Math.toRadians(abs(eulerY).coerceIn(0f, 89f).toDouble())
        val pitchRad = Math.toRadians(abs(eulerX).coerceIn(0f, 89f).toDouble())
        val frontalScore = (cos(yawRad) * cos(pitchRad)).toFloat().coerceIn(0f, 1f) * 0.35f
        val eyeScore = when {
            leftEyeOpen != null && rightEyeOpen != null ->
                ((leftEyeOpen + rightEyeOpen) * 0.5f).coerceIn(0f, 1f) * 0.14f
            leftEyeOpen != null -> leftEyeOpen.coerceIn(0f, 1f) * 0.14f
            rightEyeOpen != null -> rightEyeOpen.coerceIn(0f, 1f) * 0.14f
            else -> 0.08f
        }
        val smileBonus = (smilingProb?.coerceIn(0f, 1f) ?: 0.3f) * 0.06f
        return (sizeScore + frontalScore + eyeScore + smileBonus).coerceIn(0f, 1f)
    }
}

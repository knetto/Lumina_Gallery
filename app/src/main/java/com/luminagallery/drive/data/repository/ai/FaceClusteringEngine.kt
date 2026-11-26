package com.luminagallery.drive.data.repository.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure-Kotlin 5-point InsightFace similarity alignment, strict human facial anatomy verification,
 * embedding serialization, cosine similarity matching, cluster consolidation, and portrait quality
 * scoring. Completely Android-free so every mathematical invariant is covered by JVM unit tests.
 */
object FaceClusteringEngine {

    /** Cosine similarity threshold for attaching a 5-point-aligned face to a multi-photo person cluster. */
    const val CLUSTER_MATCH_THRESHOLD: Float = 0.58f

    /** Slightly stricter threshold for 1-photo tentative clusters to prevent early stranger merges. */
    const val SINGLETON_MATCH_THRESHOLD: Float = 0.63f

    /** High-confidence threshold for automatic post-scan consolidation of split clusters. */
    const val AUTO_CONSOLIDATE_MULTI_THRESHOLD: Float = 0.67f
    const val AUTO_CONSOLIDATE_SINGLE_THRESHOLD: Float = 0.72f

    /** Minimum normalized face bounding-box width relative to image width. */
    const val MIN_RELATIVE_FACE_SIZE: Float = 0.045f

    /** Strict head pose limits (degrees): MobileFaceNet accuracy degrades rapidly beyond ~38 deg yaw. */
    const val MAX_ABS_EULER_Y: Float = 38f
    const val MAX_ABS_EULER_X: Float = 30f

    data class Point2D(val x: Float, val y: Float)

    /**
     * 4-DOF 2D similarity transform (uniform scale + rotation + translation):
     *   x' = a * x - b * y + tx
     *   y' = b * x + a * y + ty
     */
    data class SimilarityTransform2D(
        val a: Float,
        val b: Float,
        val tx: Float,
        val ty: Float
    ) {
        fun map(p: Point2D): Point2D = Point2D(
            x = a * p.x - b * p.y + tx,
            y = b * p.x + a * p.y + ty
        )

        fun toMatrixValues(): FloatArray = floatArrayOf(
            a, -b, tx,
            b, a, ty,
            0f, 0f, 1f
        )
    }

    /** Canonical InsightFace / ArcFace / MobileFaceNet 5-point template on 112x112 (viewer left -> right). */
    val CANONICAL_112_LEFT_EYE = Point2D(38.2946f, 51.6963f)
    val CANONICAL_112_RIGHT_EYE = Point2D(73.5318f, 51.5014f)
    val CANONICAL_112_NOSE = Point2D(56.0252f, 71.7366f)
    val CANONICAL_112_MOUTH_LEFT = Point2D(41.5493f, 92.3655f)
    val CANONICAL_112_MOUTH_RIGHT = Point2D(70.7299f, 92.2041f)
    val CANONICAL_112_MOUTH_CENTER = Point2D(56.1396f, 92.2848f)

    data class ClusterCandidate(
        val personId: String,
        val centroid: FloatArray,
        val sampleCount: Int,
        val isNamed: Boolean = false,
        val photoIds: Set<String> = emptySet()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ClusterCandidate) return false
            return personId == other.personId &&
                centroid.contentEquals(other.centroid) &&
                sampleCount == other.sampleCount &&
                isNamed == other.isNamed &&
                photoIds == other.photoIds
        }

        override fun hashCode(): Int {
            var result = personId.hashCode()
            result = 31 * result + centroid.contentHashCode()
            result = 31 * result + sampleCount
            result = 31 * result + isNamed.hashCode()
            result = 31 * result + photoIds.hashCode()
            return result
        }
    }

    data class MatchResult(
        val personId: String,
        val similarity: Float
    )

    data class MergeSuggestion(
        val targetPersonId: String,
        val sourcePersonId: String,
        val similarity: Float
    )

    /**
     * Closed-form Procrustes least-squares 2D similarity transform mapping [srcPoints] onto [dstPoints].
     * Scales canonical `112x112` destination landmarks proportionally if [outputSize] differs from 112.
     */
    fun solveSimilarityTransform(
        srcPoints: List<Point2D>,
        dstPoints: List<Point2D>,
        outputSize: Int = 112
    ): SimilarityTransform2D? {
        if (srcPoints.size < 2 || srcPoints.size != dstPoints.size) return null
        val scaleOut = outputSize.toFloat() / 112f
        val n = srcPoints.size
        var srcMeanX = 0.0
        var srcMeanY = 0.0
        var dstMeanX = 0.0
        var dstMeanY = 0.0
        for (i in 0 until n) {
            srcMeanX += srcPoints[i].x
            srcMeanY += srcPoints[i].y
            dstMeanX += dstPoints[i].x * scaleOut
            dstMeanY += dstPoints[i].y * scaleOut
        }
        srcMeanX /= n
        srcMeanY /= n
        dstMeanX /= n
        dstMeanY /= n

        var srcVar = 0.0
        var numA = 0.0
        var numB = 0.0
        for (i in 0 until n) {
            val sx = srcPoints[i].x - srcMeanX
            val sy = srcPoints[i].y - srcMeanY
            val dx = dstPoints[i].x * scaleOut - dstMeanX
            val dy = dstPoints[i].y * scaleOut - dstMeanY
            srcVar += sx * sx + sy * sy
            numA += sx * dx + sy * dy
            numB += sx * dy - sy * dx
        }
        if (srcVar < 1e-4) return null
        val a = (numA / srcVar).toFloat()
        val b = (numB / srcVar).toFloat()
        if (!a.isFinite() || !b.isFinite()) return null
        val scale = hypot(a.toDouble(), b.toDouble())
        if (scale < 0.02 || scale > 25.0) return null

        val tx = (dstMeanX - (a * srcMeanX - b * srcMeanY)).toFloat()
        val ty = (dstMeanY - (b * srcMeanX + a * srcMeanY)).toFloat()
        if (!tx.isFinite() || !ty.isFinite()) return null
        return SimilarityTransform2D(a = a, b = b, tx = tx, ty = ty)
    }

    /**
     * Computes eye-line roll angle in degrees from two eyes regardless of which one ML Kit labeled
     * LEFT_EYE vs RIGHT_EYE (always orienting from viewer-left smaller X to viewer-right larger X).
     */
    fun computeUprightEyeRollDegrees(eyeA: Point2D, eyeB: Point2D): Float {
        val (viewerLeft, viewerRight) = if (eyeA.x <= eyeB.x) eyeA to eyeB else eyeB to eyeA
        val dx = (viewerRight.x - viewerLeft.x).toDouble()
        val dy = (viewerRight.y - viewerLeft.y).toDouble()
        if (hypot(dx, dy) < 4.0) return 0f
        return Math.toDegrees(kotlin.math.atan2(dy, dx)).toFloat().coerceIn(-45f, 45f)
    }

    /**
     * Strict human facial anatomy & quality gate that eliminates parrots, pets, statues, plush toys,
     * distant crowd specks, and extreme side profiles BEFORE generating a face embedding.
     */
    fun isHumanFaceGeometryValid(
        bboxLeft: Float,
        bboxTop: Float,
        bboxRight: Float,
        bboxBottom: Float,
        imageWidthPx: Float,
        eulerY: Float,
        eulerX: Float,
        eyeA: Point2D?,
        eyeB: Point2D?,
        nose: Point2D?,
        mouth: Point2D?,
        hasClassification: Boolean,
        minFacePx: Float = 52f
    ): Boolean {
        val w = bboxRight - bboxLeft
        val h = bboxBottom - bboxTop
        if (w < minFacePx || h < minFacePx) return false
        if (imageWidthPx > 0f && (w / imageWidthPx) < MIN_RELATIVE_FACE_SIZE) return false
        val aspect = w / h.coerceAtLeast(1f)
        if (aspect !in 0.58f..1.48f) return false
        if (abs(eulerY) > MAX_ABS_EULER_Y || abs(eulerX) > MAX_ABS_EULER_X) return false
        if (!hasClassification) return false

        // Require all 4 primary human landmarks (both eyes, nose, and mouth)
        if (eyeA == null || eyeB == null || nose == null || mouth == null) return false

        // Verify every landmark sits cleanly inside the face bounding box (+12% margin)
        val marginX = w * 0.12f
        val marginY = h * 0.12f
        fun inBox(p: Point2D): Boolean =
            p.x in (bboxLeft - marginX)..(bboxRight + marginX) &&
                p.y in (bboxTop - marginY)..(bboxBottom + marginY)
        if (!inBox(eyeA) || !inBox(eyeB) || !inBox(nose) || !inBox(mouth)) return false

        // Orient viewer-left -> viewer-right eye vector
        val (leftEye, rightEye) = if (eyeA.x <= eyeB.x) eyeA to eyeB else eyeB to eyeA
        val ex = rightEye.x - leftEye.x
        val ey = rightEye.y - leftEye.y
        val eyeDist = hypot(ex.toDouble(), ey.toDouble()).toFloat()
        if (eyeDist < w * 0.20f || eyeDist > w * 0.66f) return false

        // Orthonormal basis aligned with the face: u = left->right across eyes, v = downward perpendicular
        val ux = ex / eyeDist
        val uy = ey / eyeDist
        val vx = -uy
        val vy = ux
        val midEyeX = (leftEye.x + rightEye.x) * 0.5f
        val midEyeY = (leftEye.y + rightEye.y) * 0.5f

        val noseRelX = (nose.x - midEyeX) * ux + (nose.y - midEyeY) * uy
        val noseRelY = (nose.x - midEyeX) * vx + (nose.y - midEyeY) * vy
        val mouthRelX = (mouth.x - midEyeX) * ux + (mouth.y - midEyeY) * uy
        val mouthRelY = (mouth.x - midEyeX) * vx + (mouth.y - midEyeY) * vy

        // Nose must lie below eye line & reasonably centered horizontally
        if (noseRelY < eyeDist * 0.12f || noseRelY > eyeDist * 1.40f) return false
        if (abs(noseRelX) > eyeDist * 0.68f) return false

        // Mouth must lie strictly below the nose & reasonably centered horizontally
        if (mouthRelY <= noseRelY + eyeDist * 0.08f || mouthRelY > eyeDist * 2.40f) return false
        if (abs(mouthRelX) > eyeDist * 0.72f) return false

        return true
    }

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

    /**
     * Finds high-confidence split cluster pairs that belong to the same person (never merging
     * two differently-named people and never merging clusters that co-occur in the same photo).
     */
    fun findAutoConsolidationPairs(clusters: List<ClusterCandidate>): List<MergeSuggestion> {
        if (clusters.size < 2) return emptyList()
        val sorted = clusters.sortedWith(
            compareByDescending<ClusterCandidate> { it.isNamed }
                .thenByDescending { it.sampleCount }
        )
        val absorbed = HashSet<String>()
        val result = mutableListOf<MergeSuggestion>()

        for (i in sorted.indices) {
            val target = sorted[i]
            if (target.personId in absorbed) continue
            for (j in (i + 1) until sorted.size) {
                val source = sorted[j]
                if (source.personId in absorbed) continue
                // Never auto-merge two differently named people
                if (target.isNamed && source.isNamed) continue
                if (target.centroid.size != source.centroid.size) continue
                // Never merge two people who appear together in the same photo
                if (target.photoIds.isNotEmpty() && source.photoIds.any { it in target.photoIds }) continue

                val sim = cosineSimilarity(target.centroid, source.centroid)
                val threshold = if (target.sampleCount >= 2 && source.sampleCount >= 2) {
                    AUTO_CONSOLIDATE_MULTI_THRESHOLD
                } else {
                    AUTO_CONSOLIDATE_SINGLE_THRESHOLD
                }
                if (sim >= threshold) {
                    absorbed.add(source.personId)
                    result.add(
                        MergeSuggestion(
                            targetPersonId = target.personId,
                            sourcePersonId = source.personId,
                            similarity = sim
                        )
                    )
                }
            }
        }
        return result
    }

    fun isFaceUsableForRecognition(
        faceWidthPx: Float,
        faceHeightPx: Float,
        imageWidthPx: Float,
        eulerY: Float,
        eulerX: Float,
        minFacePx: Float = 52f
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

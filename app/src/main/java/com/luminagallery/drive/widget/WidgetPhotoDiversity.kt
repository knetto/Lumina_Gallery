package com.luminagallery.drive.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/**
 * Compact perceptual visual fingerprint of a photo (64-bit structural dHash + 4x4 RGB color grid).
 * Used to avoid picking 4 near-identical burst shots or repeated angles in the same collage.
 */
internal data class VisualFingerprint(
    val dHash: Long,
    val colorCells: IntArray // 16 cells * 3 channels = 48 ints (0..255)
) {
    /**
     * Returns normalized perceptual distance in [0.0, 1.0]:
     *  - < 0.18 : almost identical shot / burst retake
     *  - 0.18..0.28 : very similar scene / framing
     *  - > 0.30 : visually distinct photo
     */
    fun distanceTo(other: VisualFingerprint): Float {
        val hamming = java.lang.Long.bitCount(dHash xor other.dHash) / 64f
        var colorDiffSum = 0
        val len = min(colorCells.size, other.colorCells.size)
        for (i in 0 until len) {
            colorDiffSum += abs(colorCells[i] - other.colorCells[i])
        }
        val colorDist = if (len > 0) (colorDiffSum / (len * 255f)).coerceIn(0f, 1f) else 0f
        // Combine structural composition (dHash) + color palette layout
        return (hamming * 0.56f + colorDist * 0.44f).coerceIn(0f, 1f)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualFingerprint) return false
        return dHash == other.dHash && colorCells.contentEquals(other.colorCells)
    }

    override fun hashCode(): Int = 31 * dHash.hashCode() + colorCells.contentHashCode()
}

/**
 * Smart two-stage photo diversity selector & orientation-aware slot optimizer for collages:
 *  1. Temporal burst clustering + day-span stratification (zero I/O): separates rapid retakes
 *     and spreads picks across morning/afternoon/evening moments.
 *  2. Visual perceptual fingerprinting (32x32 tiny thumbs): detects and skips near-identical shots
 *     even taken minutes apart, picking the most visually varied photos from the day/trip/album.
 */
internal object WidgetPhotoDiversity {

    private const val BURST_WINDOW_MS = 45_000L
    private const val NEAR_TIME_MS = 3L * 60L * 1000L
    private const val SIMILAR_VISUAL_THRESHOLD = 0.23f

    /**
     * Stage 1 (pure Kotlin, zero I/O): collapses rapid burst/retake clusters taken within 45s
     * of each other (with similar orientation) and spreads picks evenly across the group's full
     * time span so a day collage represents distinct moments across the day.
     */
    fun selectTemporallyDiverseCandidates(
        photos: List<WidgetPhoto>,
        maxCount: Int,
        randomize: Boolean = true,
        random: Random = Random.Default
    ): List<WidgetPhoto> {
        if (photos.size <= maxCount) return photos

        val sorted = photos.sortedBy { it.takenAtMs }
        // 1. Group rapid burst retakes (<= 45s apart with compatible orientation) into moment clusters
        val clusters = mutableListOf<MutableList<WidgetPhoto>>()
        for (photo in sorted) {
            val lastCluster = clusters.lastOrNull()
            val lastPhoto = lastCluster?.lastOrNull()
            val isBurstRetake = lastPhoto != null &&
                photo.takenAtMs > 0L && lastPhoto.takenAtMs > 0L &&
                abs(photo.takenAtMs - lastPhoto.takenAtMs) <= BURST_WINDOW_MS &&
                abs(photo.aspectRatio - lastPhoto.aspectRatio) <= 0.25f

            if (isBurstRetake) {
                lastCluster.add(photo)
            } else {
                clusters.add(mutableListOf(photo))
            }
        }

        // Pick one strong representative per moment cluster
        val representatives = clusters.map { cluster ->
            if (randomize) cluster[random.nextInt(cluster.size)] else cluster.first()
        }

        // If we have enough distinct moment clusters, stratify across the full timeline
        if (representatives.size >= maxCount) {
            return stratifyAcrossTimeline(representatives, maxCount, randomize, random)
        }

        // Otherwise take all distinct moment representatives first, then pad from largest clusters
        val chosen = ArrayList<WidgetPhoto>(maxCount)
        chosen.addAll(representatives)
        val usedIds = chosen.mapTo(HashSet()) { it.luminaItemId }
        val extras = clusters
            .sortedByDescending { it.size }
            .flatMap { cluster -> if (randomize) cluster.shuffled(random) else cluster }
            .filter { it.luminaItemId !in usedIds }
        for (extra in extras) {
            if (chosen.size >= maxCount) break
            chosen.add(extra)
        }
        return chosen
    }

    private fun stratifyAcrossTimeline(
        ordered: List<WidgetPhoto>,
        count: Int,
        randomize: Boolean,
        random: Random
    ): List<WidgetPhoto> {
        if (ordered.size <= count) return ordered
        val out = ArrayList<WidgetPhoto>(count)
        val step = ordered.size.toDouble() / count
        for (i in 0 until count) {
            val start = (i * step).toInt().coerceIn(0, ordered.lastIndex)
            val end = ((i + 1) * step).toInt().coerceIn(start + 1, ordered.size)
            val idx = if (randomize && end - start > 1) random.nextInt(start, end) else (start + end - 1) / 2
            out.add(ordered[idx])
        }
        return out
    }

    /**
     * Stage 2: Decodes tiny 32x32 thumbnails for candidate photos and selects up to [desiredMax]
     * visually distinct photos using max-min perceptual distance.
     *
     * When [allowStepDownOnDuplicates] is true (e.g. Smart Auto collage), if a day has e.g. 6
     * candidates where 2 are near-duplicates of already-picked photos, we return only the genuinely
     * distinct photos (down to [minSlots]) so the collage picks a cleaner smaller layout instead of
     * showing repeated shots!
     */
    fun selectVisuallyDistinctPhotos(
        context: Context,
        candidates: List<WidgetPhoto>,
        desiredMax: Int,
        minSlots: Int = 2,
        allowStepDownOnDuplicates: Boolean = true,
        randomize: Boolean = true,
        random: Random = Random.Default
    ): List<WidgetPhoto> {
        if (candidates.size <= 1) return candidates
        val prefiltered = selectTemporallyDiverseCandidates(
            photos = candidates,
            maxCount = (desiredMax * 3).coerceIn(desiredMax, 16),
            randomize = randomize,
            random = random
        )
        if (prefiltered.size <= 1) return prefiltered

        val fingerprints = HashMap<String, VisualFingerprint>(prefiltered.size)
        for (photo in prefiltered) {
            val thumb = decodeTinyFingerprintBitmap(context, Uri.parse(photo.uri))
            if (thumb != null) {
                fingerprints[photo.luminaItemId] = computeFingerprint(thumb)
                thumb.recycle()
            }
        }
        return selectFromFingerprints(
            candidates = prefiltered,
            fingerprints = fingerprints,
            desiredMax = desiredMax,
            minSlots = minSlots,
            allowStepDownOnDuplicates = allowStepDownOnDuplicates,
            randomize = randomize,
            random = random
        )
    }

    /**
     * Pure Kotlin max-min diversity selector (unit-testable without Android bitmaps).
     */
    internal fun selectFromFingerprints(
        candidates: List<WidgetPhoto>,
        fingerprints: Map<String, VisualFingerprint>,
        desiredMax: Int,
        minSlots: Int = 2,
        allowStepDownOnDuplicates: Boolean = true,
        randomize: Boolean = true,
        random: Random = Random.Default
    ): List<WidgetPhoto> {
        if (candidates.size <= 1 || desiredMax <= 1) return candidates.take(desiredMax.coerceAtLeast(1))

        val pool = candidates.toMutableList()
        val chosen = ArrayList<WidgetPhoto>(min(desiredMax, pool.size))

        // Start with a fresh anchor photo
        val firstIndex = if (randomize) random.nextInt(pool.size) else 0
        chosen.add(pool.removeAt(firstIndex))

        while (chosen.size < desiredMax && pool.isNotEmpty()) {
            var bestIdx = -1
            var bestScore = Float.NEGATIVE_INFINITY
            var bestRawVisualDist = 1f

            // Track top candidates so we can break near-ties with gentle variety
            val scored = ArrayList<Triple<Int, Float, Float>>(pool.size)

            for (i in pool.indices) {
                val cand = pool[i]
                val candFp = fingerprints[cand.luminaItemId]
                var minVisualDist = 1f
                var minTimeGapMs = Long.MAX_VALUE

                for (picked in chosen) {
                    val pickedFp = fingerprints[picked.luminaItemId]
                    if (candFp != null && pickedFp != null) {
                        val d = candFp.distanceTo(pickedFp)
                        if (d < minVisualDist) minVisualDist = d
                    }
                    if (cand.takenAtMs > 0L && picked.takenAtMs > 0L) {
                        val gap = abs(cand.takenAtMs - picked.takenAtMs)
                        if (gap < minTimeGapMs) minTimeGapMs = gap
                    }
                }

                // Prefer photos separated in time as well as visually distinct
                val timeBonus = when {
                    minTimeGapMs == Long.MAX_VALUE -> 0.08f
                    minTimeGapMs <= BURST_WINDOW_MS -> -0.25f
                    minTimeGapMs <= NEAR_TIME_MS -> -0.08f
                    else -> 0.08f
                }
                val score = minVisualDist + timeBonus
                scored.add(Triple(i, score, minVisualDist))
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                    bestRawVisualDist = minVisualDist
                }
            }

            if (bestIdx < 0) break

            // If we already have enough slots AND the best remaining photo is still a near-duplicate,
            // stop adding duplicates so the collage uses a cleaner layout with only genuinely different shots!
            if (allowStepDownOnDuplicates &&
                chosen.size >= minSlots &&
                bestRawVisualDist < SIMILAR_VISUAL_THRESHOLD
            ) {
                break
            }

            val pickedIndex = if (randomize && scored.size > 1) {
                val nearBest = scored.filter { it.second >= bestScore - 0.04f && it.third >= SIMILAR_VISUAL_THRESHOLD }
                if (nearBest.isNotEmpty()) nearBest[random.nextInt(nearBest.size)].first else bestIdx
            } else {
                bestIdx
            }
            chosen.add(pool.removeAt(pickedIndex))
        }

        return chosen
    }

    /**
     * Computes a compact 64-bit dHash + 4x4 RGB spatial color grid from a small bitmap.
     */
    internal fun computeFingerprint(bitmap: Bitmap): VisualFingerprint {
        // 1. 9x8 grayscale for 64-bit structural difference hash
        val graySmall = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val grayPixels = IntArray(9 * 8)
        graySmall.getPixels(grayPixels, 0, 9, 0, 0, 9, 8)
        if (graySmall !== bitmap) graySmall.recycle()

        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            val row = y * 9
            for (x in 0 until 8) {
                val l1 = luminance(grayPixels[row + x])
                val l2 = luminance(grayPixels[row + x + 1])
                if (l1 > l2) hash = hash or (1L shl bit)
                bit++
            }
        }

        // 2. 4x4 spatial RGB signature (captures subject/background palette & placement)
        val colorSmall = Bitmap.createScaledBitmap(bitmap, 4, 4, true)
        val colorPixels = IntArray(16)
        colorSmall.getPixels(colorPixels, 0, 4, 0, 0, 4, 4)
        if (colorSmall !== bitmap) colorSmall.recycle()

        val cells = IntArray(48)
        for (i in 0 until 16) {
            val c = colorPixels[i]
            cells[i * 3] = (c ushr 16) and 0xFF
            cells[i * 3 + 1] = (c ushr 8) and 0xFF
            cells[i * 3 + 2] = c and 0xFF
        }
        return VisualFingerprint(hash, cells)
    }

    private fun luminance(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun decodeTinyFingerprintBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(32, 32)
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > 64) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    }.getOrNull()

    /**
     * Optimal photo-to-slot matcher: assigns portrait photos to portrait tiles and landscape
     * photos to landscape tiles by minimizing total log-aspect-ratio crop distortion.
     * For N <= 6 (at most 720 permutations), finds the mathematically exact global optimum in < 0.1ms!
     */
    fun <T> assignToBestSlots(
        items: List<T>,
        itemAspectRatio: (T) -> Float,
        slotAspectRatios: List<Float>
    ): Pair<List<T>, Float> {
        val n = min(items.size, slotAspectRatios.size)
        if (n <= 1) {
            val err = if (n == 1) aspectMismatch(itemAspectRatio(items[0]), slotAspectRatios[0]) else 0f
            return items.take(n) to err
        }

        val subset = items.take(n)
        val itemAspects = FloatArray(n) { itemAspectRatio(subset[it]).coerceIn(0.35f, 3.0f) }
        val slotAspects = FloatArray(n) { slotAspectRatios[it].coerceIn(0.35f, 3.0f) }

        val cost = Array(n) { slot ->
            FloatArray(n) { item -> aspectMismatch(itemAspects[item], slotAspects[slot]) }
        }

        var bestCost = Float.POSITIVE_INFINITY
        val bestAssign = IntArray(n) { it }
        val current = IntArray(n)
        val used = BooleanArray(n)

        fun dfs(slot: Int, accCost: Float) {
            if (accCost >= bestCost) return
            if (slot == n) {
                bestCost = accCost
                current.copyInto(bestAssign)
                return
            }
            for (item in 0 until n) {
                if (used[item]) continue
                used[item] = true
                current[slot] = item
                dfs(slot + 1, accCost + cost[slot][item])
                used[item] = false
            }
        }

        dfs(0, 0f)
        val ordered = List(n) { slot -> subset[bestAssign[slot]] }
        return ordered to (bestCost / n)
    }

    private fun aspectMismatch(photoAspect: Float, tileAspect: Float): Float {
        val ratio = (photoAspect / tileAspect).coerceIn(0.1f, 10f)
        val logErr = abs(ln(ratio.toDouble()))
        // Squared log-ratio error strongly penalizes severe cropping on any single tile
        return (logErr * logErr).toFloat()
    }
}

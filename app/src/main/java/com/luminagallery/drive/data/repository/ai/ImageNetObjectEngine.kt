package com.luminagallery.drive.data.repository.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 100% offline 1,000-class ImageNet object & species classifier powered by Google's
 * EfficientNet-Lite2 (`int8`, 6.8 MB) on LiteRT:
 *  - Recognizes specific animals & objects that ML Kit's 400-class base model misses
 *    (macaw / African grey / cockatoo / lorikeet -> Parrot, 120 dog breeds, owls, flamingos,
 *    pizza, espresso, sports cars, guitars, laptops, sunglasses, etc.).
 *  - Doubles as an ultra-accurate non-human verifier on candidate face crops so parrots, dogs,
 *    cats, plush toys, and statues never leak into the People section!
 */
class ImageNetObjectEngine(private val context: Context) {

    private val inferenceLock = Any()
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputWidth: Int = DEFAULT_INPUT_SIZE
    private var inputHeight: Int = DEFAULT_INPUT_SIZE
    private var inputDataType: DataType = DataType.UINT8
    private var inputScale: Float = 1f / 128f
    private var inputZeroPoint: Int = 128
    private var outputDataType: DataType = DataType.UINT8
    private var outputCount: Int = 1000
    private var outputScale: Float = 1f / 256f
    private var outputZeroPoint: Int = 0
    private var initAttempted: Boolean = false

    private fun ensureInitialized(): Boolean {
        if (interpreter != null && labels.isNotEmpty()) return true
        synchronized(inferenceLock) {
            if (interpreter != null && labels.isNotEmpty()) return true
            if (initAttempted) return false
            initAttempted = true
            runCatching {
                labels = context.assets.open(LABELS_ASSET_NAME)
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { seq -> seq.map { it.trim() }.filter { it.isNotEmpty() }.toList() }
                if (labels.isEmpty()) return@runCatching

                val modelBuffer = loadModelBuffer(MODEL_ASSET_NAME) ?: return@runCatching
                val opts = Interpreter.Options().apply { setNumThreads(2) }
                val created = Interpreter(modelBuffer, opts)

                val inTensor = created.getInputTensor(0)
                val inShape = inTensor.shape() // [1, H, W, 3]
                if (inShape.size >= 4) {
                    inputHeight = inShape[1].coerceAtLeast(64)
                    inputWidth = inShape[2].coerceAtLeast(64)
                }
                inputDataType = inTensor.dataType()
                val inQ = inTensor.quantizationParams()
                if (inQ.scale > 0f) {
                    inputScale = inQ.scale
                    inputZeroPoint = inQ.zeroPoint
                }

                val outTensor = created.getOutputTensor(0)
                val outShape = outTensor.shape()
                if (outShape.isNotEmpty()) {
                    outputCount = outShape.last().coerceAtLeast(1)
                }
                outputDataType = outTensor.dataType()
                val outQ = outTensor.quantizationParams()
                if (outQ.scale > 0f) {
                    outputScale = outQ.scale
                    outputZeroPoint = outQ.zeroPoint
                }
                interpreter = created
            }
            return interpreter != null && labels.isNotEmpty()
        }
    }

    private fun loadModelBuffer(assetName: String): ByteBuffer? = runCatching {
        context.assets.openFd(assetName).use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length) as MappedByteBuffer
            }
        }
    }.getOrElse {
        runCatching {
            val bytes = context.assets.open(assetName).use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }.getOrNull()
    }

    /**
     * Full 1000-class softmax for [bitmap] (center-crop, zero aspect distortion), or null if the
     * model is unavailable. Feed it to [AnimalTaxonomy.analyze] for group-level reasoning.
     */
    fun classifyProbabilities(bitmap: Bitmap): FloatArray? {
        if (!ensureInitialized()) return null
        val interp = interpreter ?: return null
        return synchronized(inferenceLock) {
            runCatching {
                val w = inputWidth
                val h = inputHeight
                val prepared = centerCropScale(bitmap, w, h)
                try {
                    val pixels = IntArray(w * h)
                    prepared.getPixels(pixels, 0, w, 0, 0, w, h)
                    runAndDequantize(interp, buildInputBuffer(pixels, w, h))
                } finally {
                    if (prepared !== bitmap) prepared.recycle()
                }
            }.getOrNull()
        }
    }

    /**
     * Top [topK] `(label, probability)` pairs from a softmax produced by [classifyProbabilities].
     * With [excludeAnimals] every animal class is skipped (animals are labelled through
     * [AnimalTaxonomy] group masses instead of single, easily-confused breed names).
     */
    fun topLabels(
        probs: FloatArray,
        topK: Int = 5,
        minConfidence: Float = 0.22f,
        excludeAnimals: Boolean = false
    ): List<Pair<String, Float>> {
        if (labels.isEmpty()) return emptyList()
        val limit = minOf(probs.size, labels.size)
        val scored = ArrayList<Pair<String, Float>>(topK * 2)
        for (i in 0 until limit) {
            val p = probs[i]
            if (p < minConfidence) continue
            if (excludeAnimals && AnimalTaxonomy.groupOf(i) != null) continue
            scored.add(labels[i] to p)
        }
        scored.sortByDescending { it.second }
        return if (scored.size > topK) scored.subList(0, topK).toList() else scored
    }

    /** Raw ImageNet label for a class index (e.g. `"macaque"`), or null. */
    fun labelAt(index: Int): String? = labels.getOrNull(index)

    /**
     * Classifies [bitmap] using center-crop scaling (zero aspect distortion) and returns the top
     * [topK] `(rawImageNetLabel, probability)` pairs meeting [minConfidence].
     */
    fun classify(
        bitmap: Bitmap,
        topK: Int = 5,
        minConfidence: Float = 0.22f
    ): List<Pair<String, Float>> {
        if (!ensureInitialized()) return emptyList()
        val interp = interpreter ?: return emptyList()

        return synchronized(inferenceLock) {
            runCatching {
                val w = inputWidth
                val h = inputHeight
                val prepared = centerCropScale(bitmap, w, h)
                try {
                    val pixels = IntArray(w * h)
                    prepared.getPixels(pixels, 0, w, 0, 0, w, h)

                    val inputBuffer = buildInputBuffer(pixels, w, h)
                    val probs = runAndDequantize(interp, inputBuffer)

                    val limit = minOf(probs.size, labels.size)
                    val scored = ArrayList<Pair<String, Float>>(topK * 2)
                    for (i in 0 until limit) {
                        val p = probs[i]
                        if (p >= minConfidence) {
                            scored.add(labels[i] to p)
                        }
                    }
                    scored.sortByDescending { it.second }
                    if (scored.size > topK) scored.subList(0, topK).toList() else scored
                } finally {
                    if (prepared !== bitmap) prepared.recycle()
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun centerCropScale(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (src.width == targetW && src.height == targetH) return src
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = max(targetW.toFloat() / src.width.coerceAtLeast(1), targetH.toFloat() / src.height.coerceAtLeast(1))
        val dx = (targetW - src.width * scale) * 0.5f
        val dy = (targetH - src.height * scale) * 0.5f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(src, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun buildInputBuffer(pixels: IntArray, w: Int, h: Int): ByteBuffer = when (inputDataType) {
        DataType.UINT8 -> {
            ByteBuffer.allocateDirect(w * h * 3).order(ByteOrder.nativeOrder()).apply {
                for (px in pixels) {
                    put(((px shr 16) and 0xFF).toByte())
                    put(((px shr 8) and 0xFF).toByte())
                    put((px and 0xFF).toByte())
                }
                rewind()
            }
        }
        DataType.INT8 -> {
            val invScale = if (inputScale > 0f) 1f / inputScale else 128f
            ByteBuffer.allocateDirect(w * h * 3).order(ByteOrder.nativeOrder()).apply {
                for (px in pixels) {
                    val rNorm = (((px shr 16) and 0xFF) - 127.5f) / 128.0f
                    val gNorm = (((px shr 8) and 0xFF) - 127.5f) / 128.0f
                    val bNorm = ((px and 0xFF) - 127.5f) / 128.0f
                    put((rNorm * invScale + inputZeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                    put((gNorm * invScale + inputZeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                    put((bNorm * invScale + inputZeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                }
                rewind()
            }
        }
        else -> {
            ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder()).apply {
                for (px in pixels) {
                    putFloat((((px shr 16) and 0xFF) - 127.5f) / 128.0f)
                    putFloat((((px shr 8) and 0xFF) - 127.5f) / 128.0f)
                    putFloat(((px and 0xFF) - 127.5f) / 128.0f)
                }
                rewind()
            }
        }
    }

    private fun runAndDequantize(interp: Interpreter, inputBuffer: ByteBuffer): FloatArray {
        val rawFloat = when (outputDataType) {
            DataType.UINT8 -> {
                val outBytes = Array(1) { ByteArray(outputCount) }
                interp.run(inputBuffer, outBytes)
                val row = outBytes[0]
                FloatArray(outputCount) { i -> ((row[i].toInt() and 0xFF) - outputZeroPoint) * outputScale }
            }
            DataType.INT8 -> {
                val outBytes = Array(1) { ByteArray(outputCount) }
                interp.run(inputBuffer, outBytes)
                val row = outBytes[0]
                FloatArray(outputCount) { i -> (row[i].toInt() - outputZeroPoint) * outputScale }
            }
            else -> {
                val outFloats = Array(1) { FloatArray(outputCount) }
                interp.run(inputBuffer, outFloats)
                outFloats[0]
            }
        }

        return normalizeOutputToProbabilities(rawFloat)
    }

    fun close() {
        synchronized(inferenceLock) {
            runCatching { interpreter?.close() }
            interpreter = null
        }
    }

    companion object {
        private const val MODEL_ASSET_NAME = "efficientnet_lite2.tflite"
        private const val LABELS_ASSET_NAME = "imagenet_1000_labels.txt"
        private const val DEFAULT_INPUT_SIZE = 260

        /**
         * Converts raw dequantized model outputs into a probability vector.
         *
         * IMPORTANT: 8-bit quantized 1,000-class Softmax models (like EfficientNet-Lite2 int8/uint8)
         * truncate hundreds of small tail probabilities (< 0.002) down to 0, so the sum of valid
         * probabilities is routinely 0.15–0.88 (< 0.90). Applying a second softmax to an already
         * non-negative `[0, 1]` vector would flatten every class to ~0.001 (collapsing all object
         * detections AND inflating the 398-class ImageNet animal sum to ~0.398, vetoing all human
         * faces!). Therefore softmax is ONLY applied when outputs are actual unnormalized logits
         * (contain negative values, exceed 1.05, or sum well above 1.25).
         */
        internal fun normalizeOutputToProbabilities(rawFloat: FloatArray): FloatArray {
            if (rawFloat.isEmpty()) return rawFloat
            var hasNeg = false
            var maxVal = Float.NEGATIVE_INFINITY
            var sum = 0.0
            for (v in rawFloat) {
                if (!v.isFinite()) continue
                if (v < -0.01f) hasNeg = true
                if (v > maxVal) maxVal = v
                sum += v.toDouble()
            }
            if (!maxVal.isFinite()) return FloatArray(rawFloat.size)

            // Already a probability vector (including quantized softmax outputs with truncated tails or all-zeros)
            if (!hasNeg && maxVal <= 1.05f && sum <= 1.25) {
                return FloatArray(rawFloat.size) { i ->
                    val v = rawFloat[i]
                    if (v.isFinite() && v > 0f) v.coerceAtMost(1f) else 0f
                }
            }

            // Unnormalized logits -> numerically stable softmax
            var expSum = 0.0
            val exps = DoubleArray(rawFloat.size) { i ->
                val v = rawFloat[i]
                val e = if (v.isFinite()) exp((v - maxVal).toDouble()) else 0.0
                expSum += e
                e
            }
            if (expSum <= 1e-12) return FloatArray(rawFloat.size)
            return FloatArray(rawFloat.size) { i -> (exps[i] / expSum).toFloat() }
        }
    }
}

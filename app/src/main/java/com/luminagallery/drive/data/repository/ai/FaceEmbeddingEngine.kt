package com.luminagallery.drive.data.repository.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * 100% offline on-device face-embedding engine powered by MobileFaceNet (LiteRT / TFLite):
 *  - Memory-maps `assets/mobilefacenet.tflite` with zero heap duplication.
 *  - Warps each face via exact 5-point InsightFace Procrustes similarity alignment onto
 *    MobileFaceNet's canonical `112x112` landmark positions (fixing eye-order roll inversion!).
 *  - Runs horizontal-flip Test-Time Augmentation (TTA) averaging + L2 normalization for
 *    dramatically higher grouping accuracy across left/right poses.
 *  - Extracts upright `160x160` portrait crops both for AI non-human verification and crisp
 *    offline circular People avatars.
 */
class FaceEmbeddingEngine(private val context: Context) {

    private val inferenceLock = Any()
    private var interpreter: Interpreter? = null
    private var inputWidth: Int = DEFAULT_INPUT_SIZE
    private var inputHeight: Int = DEFAULT_INPUT_SIZE
    private var inputDataType: DataType = DataType.FLOAT32
    private var outputDim: Int = DEFAULT_EMBEDDING_DIM
    private var initAttempted: Boolean = false

    val faceCropsDir: File by lazy {
        File(context.noBackupFilesDir, "face_crops").apply { mkdirs() }
    }

    private fun ensureInterpreter(): Interpreter? {
        if (interpreter != null) return interpreter
        synchronized(inferenceLock) {
            if (interpreter != null) return interpreter
            if (initAttempted) return null
            initAttempted = true
            runCatching {
                val modelBuffer = loadModelBuffer(MODEL_ASSET_NAME) ?: return@runCatching
                val opts = Interpreter.Options().apply {
                    setNumThreads(2)
                }
                val created = Interpreter(modelBuffer, opts)
                val inTensor = created.getInputTensor(0)
                val inShape = inTensor.shape() // [1, H, W, 3]
                if (inShape.size >= 4) {
                    inputHeight = inShape[1].coerceAtLeast(32)
                    inputWidth = inShape[2].coerceAtLeast(32)
                }
                inputDataType = inTensor.dataType()

                val outTensor = created.getOutputTensor(0)
                val outShape = outTensor.shape() // [1, dim]
                if (outShape.isNotEmpty()) {
                    outputDim = outShape.last().coerceAtLeast(32)
                }
                interpreter = created
            }
            return interpreter
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
     * Extracts a canonical 5-point InsightFace aligned `outputSize x outputSize` bitmap for
     * MobileFaceNet recognition, falling back cleanly to eye-straightened tight box crop if
     * landmarks cannot form a valid similarity transform.
     */
    fun extractCanonicalRecognitionCrop(
        source: Bitmap,
        face: Face,
        outputSize: Int = inputWidth
    ): Bitmap? = runCatching {
        val eye1 = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val eye2 = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthL = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthR = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mouthB = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position

        if (eye1 != null && eye2 != null) {
            val p1 = FaceClusteringEngine.Point2D(eye1.x, eye1.y)
            val p2 = FaceClusteringEngine.Point2D(eye2.x, eye2.y)
            // Always sort viewer-left (smaller x) -> viewer-right (larger x)
            val (viewerLeftEye, viewerRightEye) = if (p1.x <= p2.x) p1 to p2 else p2 to p1

            val srcPts = mutableListOf(viewerLeftEye, viewerRightEye)
            val dstPts = mutableListOf(
                FaceClusteringEngine.CANONICAL_112_LEFT_EYE,
                FaceClusteringEngine.CANONICAL_112_RIGHT_EYE
            )
            if (nose != null) {
                srcPts.add(FaceClusteringEngine.Point2D(nose.x, nose.y))
                dstPts.add(FaceClusteringEngine.CANONICAL_112_NOSE)
            }
            if (mouthL != null && mouthR != null) {
                val m1 = FaceClusteringEngine.Point2D(mouthL.x, mouthL.y)
                val m2 = FaceClusteringEngine.Point2D(mouthR.x, mouthR.y)
                val (vMouthL, vMouthR) = if (m1.x <= m2.x) m1 to m2 else m2 to m1
                srcPts.add(vMouthL)
                srcPts.add(vMouthR)
                dstPts.add(FaceClusteringEngine.CANONICAL_112_MOUTH_LEFT)
                dstPts.add(FaceClusteringEngine.CANONICAL_112_MOUTH_RIGHT)
            } else if (mouthB != null) {
                srcPts.add(FaceClusteringEngine.Point2D(mouthB.x, mouthB.y))
                dstPts.add(FaceClusteringEngine.CANONICAL_112_MOUTH_CENTER)
            }

            val transform = FaceClusteringEngine.solveSimilarityTransform(srcPts, dstPts, outputSize)
            if (transform != null) {
                val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                val matrix = Matrix().apply { setValues(transform.toMatrixValues()) }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(source, matrix, paint)
                return@runCatching out
            }
        }

        extractUprightFaceCrop(source, face, paddingFactor = 1.08f, outputSize = outputSize)
    }.getOrNull()

    /**
     * Extracts a straight, upright square face crop around [face] using viewer-sorted eye roll
     * (never inverting 180 degrees!). Use [paddingFactor] ~1.62f for flattering circular avatars
     * and AI non-human verification crops.
     */
    fun extractUprightFaceCrop(
        source: Bitmap,
        face: Face,
        paddingFactor: Float = AVATAR_PADDING_FACTOR,
        outputSize: Int = AVATAR_SIZE_PX
    ): Bitmap? = runCatching {
        val box: Rect = face.boundingBox
        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        val baseSize = max(box.width(), box.height()).toFloat().coerceAtLeast(16f)
        val cropSpan = baseSize * paddingFactor

        val eye1 = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val eye2 = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val rollDegrees = if (eye1 != null && eye2 != null) {
            FaceClusteringEngine.computeUprightEyeRollDegrees(
                FaceClusteringEngine.Point2D(eye1.x, eye1.y),
                FaceClusteringEngine.Point2D(eye2.x, eye2.y)
            )
        } else {
            (-face.headEulerAngleZ).coerceIn(-45f, 45f)
        }

        val verticalBiasPx = if (paddingFactor > 1.4f) -baseSize * 0.08f else 0f

        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = outputSize.toFloat() / cropSpan
        val matrix = Matrix().apply {
            postTranslate(-cx, -(cy + verticalBiasPx))
            postRotate(-rollDegrees)
            postScale(scale, scale)
            postTranslate(outputSize * 0.5f, outputSize * 0.5f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        out
    }.getOrNull()

    /**
     * Computes an L2-normalized MobileFaceNet embedding for a canonical 5-point aligned [face],
     * averaging original and horizontally-flipped passes for pose invariance.
     */
    fun computeEmbedding(source: Bitmap, face: Face): FloatArray? {
        val interp = ensureInterpreter() ?: return null
        val crop = extractCanonicalRecognitionCrop(
            source = source,
            face = face,
            outputSize = inputWidth
        ) ?: return null
        try {
            val embNormal = runInferenceSingle(interp, crop, flipHorizontal = false) ?: return null
            val embFlipped = runInferenceSingle(interp, crop, flipHorizontal = true)
            return if (embFlipped != null) {
                FaceClusteringEngine.averageNormalizedEmbeddings(listOf(embNormal, embFlipped))
            } else {
                FaceClusteringEngine.l2Normalize(embNormal)
            }
        } finally {
            crop.recycle()
        }
    }

    private fun runInferenceSingle(
        interp: Interpreter,
        bitmap: Bitmap,
        flipHorizontal: Boolean
    ): FloatArray? = synchronized(inferenceLock) {
        runCatching {
            val w = inputWidth
            val h = inputHeight
            val resized = if (bitmap.width == w && bitmap.height == h) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            }
            try {
                val pixels = IntArray(w * h)
                resized.getPixels(pixels, 0, w, 0, 0, w, h)

                val inputBuffer = if (inputDataType == DataType.UINT8) {
                    ByteBuffer.allocateDirect(w * h * 3).order(ByteOrder.nativeOrder()).apply {
                        for (y in 0 until h) {
                            val rowStart = y * w
                            for (x in 0 until w) {
                                val srcX = if (flipHorizontal) (w - 1 - x) else x
                                val px = pixels[rowStart + srcX]
                                put(((px shr 16) and 0xFF).toByte())
                                put(((px shr 8) and 0xFF).toByte())
                                put((px and 0xFF).toByte())
                            }
                        }
                        rewind()
                    }
                } else {
                    ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder()).apply {
                        for (y in 0 until h) {
                            val rowStart = y * w
                            for (x in 0 until w) {
                                val srcX = if (flipHorizontal) (w - 1 - x) else x
                                val px = pixels[rowStart + srcX]
                                val r = ((px shr 16) and 0xFF)
                                val g = ((px shr 8) and 0xFF)
                                val b = (px and 0xFF)
                                putFloat((r - 127.5f) / 128.0f)
                                putFloat((g - 127.5f) / 128.0f)
                                putFloat((b - 127.5f) / 128.0f)
                            }
                        }
                        rewind()
                    }
                }

                val output = Array(1) { FloatArray(outputDim) }
                interp.run(inputBuffer, output)
                FaceClusteringEngine.l2Normalize(output[0])
            } finally {
                if (resized !== bitmap) resized.recycle()
            }
        }.getOrNull()
    }

    /**
     * Persists an already-extracted upright portrait bitmap to `noBackupFilesDir/face_crops/`.
     */
    fun saveAvatarBitmap(avatar: Bitmap, faceId: String): String? = try {
        val safeName = faceId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val file = File(faceCropsDir, "$safeName.jpg")
        FileOutputStream(file).use { out ->
            avatar.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        file.absolutePath
    } catch (_: Throwable) {
        null
    }

    fun deleteCropFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    fun clearAllCropFiles() {
        runCatching {
            faceCropsDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun close() {
        synchronized(inferenceLock) {
            runCatching { interpreter?.close() }
            interpreter = null
        }
    }

    companion object {
        private const val MODEL_ASSET_NAME = "mobilefacenet.tflite"
        private const val DEFAULT_INPUT_SIZE = 112
        private const val DEFAULT_EMBEDDING_DIM = 192
        const val AVATAR_PADDING_FACTOR = 1.62f
        const val AVATAR_SIZE_PX = 160
    }
}

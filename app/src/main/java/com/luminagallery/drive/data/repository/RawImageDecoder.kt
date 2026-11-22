package com.luminagallery.drive.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Coil 3 Decoder supporting camera RAW files (.DNG, .CR2, .CR3, .NEF, .ARW, .ORF, .RW2, .RAF, etc.)
 * on Android:
 *  1. Uses hardware `ImageDecoder` on API 28+ (natively decodes Samsung Expert RAW / Pro DNG and
 *     Android-supported RAW formats with clean downsampling so 108MP DNGs never OOM).
 *  2. Falls back transparently to extracting the embedded JPEG preview via `ExifInterface` for
 *     proprietary camera RAW formats (Canon CR3, Sony compressed ARW, Fuji RAF, Nikon NEF).
 */
class RawImageDecoder(
    private val source: ImageSource,
    private val options: Options
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val file: File = source.file().toFile()
        if (!file.exists() || file.length() == 0L) return null

        val targetMaxPx = resolveTargetMaxDimension(options)

        // Attempt 1: Native Android ImageDecoder (API 28+) with downsampled target dimensions
        val decodedBitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val imgSource = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(imgSource) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val srcW = info.size.width
                    val srcH = info.size.height
                    if (targetMaxPx > 0 && max(srcW, srcH) > targetMaxPx) {
                        val scale = targetMaxPx.toFloat() / max(srcW, srcH).toFloat()
                        decoder.setTargetSize(
                            (srcW * scale).roundToInt().coerceAtLeast(1),
                            (srcH * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()
        } else {
            null
        }

        if (decodedBitmap != null) {
            return DecodeResult(
                image = decodedBitmap.asImage(),
                isSampled = targetMaxPx > 0
            )
        }

        // Attempt 2: Embedded JPEG preview via ExifInterface (handles CR3, ARW, NEF, RAF, RW2)
        val embeddedBitmap = runCatching {
            val exif = ExifInterface(file)
            val thumbBytes = exif.thumbnailBytes ?: return@runCatching null
            val rawBmp = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                ?: return@runCatching null
            applyExifOrientation(rawBmp, exif.rotationDegrees)
        }.getOrNull()

        if (embeddedBitmap != null) {
            return DecodeResult(
                image = embeddedBitmap.asImage(),
                isSampled = true
            )
        }

        return null
    }

    private fun resolveTargetMaxDimension(options: Options): Int {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: 0
        val h = (options.size.height as? Dimension.Pixels)?.px ?: 0
        val requested = max(w, h)
        return if (requested > 0) requested.coerceAtMost(MAX_RAW_DECODE_PX) else MAX_RAW_DECODE_PX
    }

    private fun applyExifOrientation(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader
        ): Decoder? {
            if (!isRawMimeType(result.mimeType) && !hasRawMagicHeader(result.source)) return null
            return RawImageDecoder(result.source, options)
        }

        private fun hasRawMagicHeader(source: ImageSource): Boolean = runCatching {
            val peek = source.source().peek()
            val header = peek.readByteArray(16)
            if (header.size < 12) return@runCatching false
            // TIFF-based RAWs: DNG, CR2, NEF, ARW, PEF, SRW (II*\0 or MM\0*)
            val isTiffLE = header[0] == 0x49.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x2A.toByte() && header[3] == 0x00.toByte()
            val isTiffBE = header[0] == 0x4D.toByte() && header[1] == 0x4D.toByte() &&
                header[2] == 0x00.toByte() && header[3] == 0x2A.toByte()
            // Panasonic RW2 (IIU\0), Olympus ORF (IIRO / IIRS)
            val isRw2 = header[0] == 0x49.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x55.toByte() && header[3] == 0x00.toByte()
            val isOrf = header[0] == 0x49.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x52.toByte() && (header[3] == 0x4F.toByte() || header[3] == 0x53.toByte())
            // Canon CR3 ISO-BMFF ("ftypcrx ")
            val isCr3 = header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() &&
                header[8] == 'c'.code.toByte() && header[9] == 'r'.code.toByte() &&
                header[10] == 'x'.code.toByte()
            // Fujifilm RAF ("FUJIFILMCCD-RAW")
            val isRaf = String(header, 0, 8, Charsets.US_ASCII) == "FUJIFILM"

            isTiffLE || isTiffBE || isRw2 || isOrf || isCr3 || isRaf
        }.getOrDefault(false)

        private fun isRawMimeType(mimeType: String?): Boolean {
            if (mimeType.isNullOrBlank()) return false
            val lower = mimeType.lowercase(Locale.US)
            return lower.contains("dng") ||
                lower.contains("raw") ||
                lower.contains("cr2") ||
                lower.contains("cr3") ||
                lower.contains("nef") ||
                lower.contains("arw") ||
                lower.contains("orf") ||
                lower.contains("rw2") ||
                lower.contains("raf") ||
                lower.contains("pef") ||
                lower.contains("srw")
        }
    }

    private companion object {
        const val MAX_RAW_DECODE_PX = 2400
    }
}

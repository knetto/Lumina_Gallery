package com.luminagallery.drive.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.luminagallery.drive.data.remote.DriveApiService
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

enum class CollageLayoutMode(val label: String) {
    AUTO_GRID("Grid"),
    SIDE_BY_SIDE("Row"),
    VERTICAL_STACK("Column"),
    HERO_TOP("Hero top")
}

data class CollageOptions(
    val layout: CollageLayoutMode = CollageLayoutMode.AUTO_GRID,
    val spacingPx: Int = 20,
    val cornerRadiusPx: Float = 28f,
    val backgroundColor: Int = Color.BLACK,
    val outputWidthPx: Int = 2048
)

data class CollageCellSpec(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Pure layout math for responsive collages (also unit-tested!).
 */
object CollageLayoutMath {
    fun computeCells(
        count: Int,
        layout: CollageLayoutMode,
        canvasWidth: Float,
        canvasHeight: Float,
        spacing: Float
    ): List<CollageCellSpec> {
        if (count <= 0) return emptyList()
        if (count == 1) {
            return listOf(
                CollageCellSpec(spacing, spacing, canvasWidth - spacing, canvasHeight - spacing)
            )
        }
        return when (layout) {
            CollageLayoutMode.SIDE_BY_SIDE -> {
                val cellW = (canvasWidth - spacing * (count + 1)) / count
                (0 until count).map { i ->
                    val l = spacing + i * (cellW + spacing)
                    CollageCellSpec(l, spacing, l + cellW, canvasHeight - spacing)
                }
            }
            CollageLayoutMode.VERTICAL_STACK -> {
                val cellH = (canvasHeight - spacing * (count + 1)) / count
                (0 until count).map { i ->
                    val t = spacing + i * (cellH + spacing)
                    CollageCellSpec(spacing, t, canvasWidth - spacing, t + cellH)
                }
            }
            CollageLayoutMode.HERO_TOP -> {
                val topH = (canvasHeight - spacing * 3f) * 0.56f
                val bottomTop = spacing * 2f + topH
                val bottomH = canvasHeight - bottomTop - spacing
                val bottomCount = count - 1
                val bottomW = (canvasWidth - spacing * (bottomCount + 1)) / bottomCount
                buildList {
                    add(CollageCellSpec(spacing, spacing, canvasWidth - spacing, spacing + topH))
                    for (i in 0 until bottomCount) {
                        val l = spacing + i * (bottomW + spacing)
                        add(CollageCellSpec(l, bottomTop, l + bottomW, bottomTop + bottomH))
                    }
                }
            }
            CollageLayoutMode.AUTO_GRID -> {
                val cols = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
                val rows = ceil(count.toDouble() / cols).toInt().coerceAtLeast(1)
                val cellH = (canvasHeight - spacing * (rows + 1)) / rows
                buildList {
                    var idx = 0
                    for (r in 0 until rows) {
                        val itemsInRow = if (r == rows - 1) count - idx else cols
                        val cellW = (canvasWidth - spacing * (itemsInRow + 1)) / itemsInRow
                        val top = spacing + r * (cellH + spacing)
                        for (c in 0 until itemsInRow) {
                            val left = spacing + c * (cellW + spacing)
                            add(CollageCellSpec(left, top, left + cellW, top + cellH))
                            idx++
                        }
                    }
                }
            }
        }
    }
}

/**
 * Generates high-resolution photo Collages and multi-page A4 PDF documents directly on-device
 * using Android's native `Canvas` and `PdfDocument` APIs (zero extra dependencies).
 */
class CollagePdfCreator(
    private val context: Context,
    private val driveApi: DriveApiService,
    private val offlineMediaManager: OfflineMediaManager
) {
    private val shareDir: File
        get() = File(context.cacheDir, CoilCacheManager.SHARE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    suspend fun createCollage(
        items: List<MediaItem>,
        options: CollageOptions,
        saveToGallery: Boolean
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val photos = items.filter { !it.isVideo }.take(9)
            require(photos.size >= 2) { "Select at least 2 photos to create a collage" }

            val bitmaps = photos.mapNotNull { decodeScaledPhoto(it, maxDim = 1400) }
            require(bitmaps.size >= 2) { "Could not decode selected photos for collage" }

            val width = options.outputWidthPx
            val height = when (options.layout) {
                CollageLayoutMode.SIDE_BY_SIDE -> (width * 0.62f).toInt()
                CollageLayoutMode.VERTICAL_STACK -> (width * 1.45f).toInt()
                CollageLayoutMode.HERO_TOP -> (width * 1.15f).toInt()
                CollageLayoutMode.AUTO_GRID -> width
            }

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(output)
                canvas.drawColor(options.backgroundColor)
                val cells = CollageLayoutMath.computeCells(
                    count = bitmaps.size,
                    layout = options.layout,
                    canvasWidth = width.toFloat(),
                    canvasHeight = height.toFloat(),
                    spacing = options.spacingPx.toFloat()
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                cells.forEachIndexed { idx, cell ->
                    val bmp = bitmaps[idx]
                    val dst = RectF(cell.left, cell.top, cell.right, cell.bottom)
                    val src = computeCenterCropRect(bmp.width, bmp.height, dst.width(), dst.height())
                    canvas.save()
                    if (options.cornerRadiusPx > 0f) {
                        val path = Path().apply {
                            addRoundRect(dst, options.cornerRadiusPx, options.cornerRadiusPx, Path.Direction.CW)
                        }
                        canvas.clipPath(path)
                    }
                    canvas.drawBitmap(bmp, src, dst, paint)
                    canvas.restore()
                }

                val filename = "Lumina_Collage_${System.currentTimeMillis()}.jpg"
                if (saveToGallery) {
                    saveBitmapToPictures(output, filename)
                } else {
                    val file = File(shareDir, filename)
                    FileOutputStream(file).use { out ->
                        output.compress(Bitmap.CompressFormat.JPEG, 94, out)
                    }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
            } finally {
                output.recycle()
                bitmaps.forEach { runCatching { it.recycle() } }
            }
        }
    }

    suspend fun createPdf(
        items: List<MediaItem>,
        documentTitle: String,
        saveToDocuments: Boolean
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val photos = items.filter { !it.isVideo }.take(40)
            require(photos.isNotEmpty()) { "Select at least 1 photo to create a PDF" }

            val pdf = PdfDocument()
            // A4 at 150 DPI (1240 x 1754 pt) — crisp print & screen quality with compact file size
            val pageW = 1240
            val pageH = 1754
            val margin = 56f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            var pagesWritten = 0

            try {
                photos.forEachIndexed { idx, item ->
                    val bmp = decodeScaledPhoto(item, maxDim = 1800) ?: return@forEachIndexed
                    try {
                        val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create()
                        val page = pdf.startPage(pageInfo)
                        val canvas = page.canvas
                        canvas.drawColor(Color.WHITE)

                        val availW = pageW - margin * 2f
                        val availH = pageH - margin * 2f
                        val scale = min(availW / bmp.width.toFloat(), availH / bmp.height.toFloat())
                        val drawW = bmp.width * scale
                        val drawH = bmp.height * scale
                        val left = (pageW - drawW) / 2f
                        val top = (pageH - drawH) / 2f
                        canvas.drawBitmap(bmp, null, RectF(left, top, left + drawW, top + drawH), paint)
                        pdf.finishPage(page)
                        pagesWritten++
                    } finally {
                        bmp.recycle()
                    }
                }
                require(pagesWritten > 0) { "Unable to decode any selected photos for PDF" }

                val cleanTitle = documentTitle.trim()
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "Lumina_Photos_${System.currentTimeMillis()}" }
                val filename = if (cleanTitle.endsWith(".pdf", ignoreCase = true)) cleanTitle else "$cleanTitle.pdf"

                if (saveToDocuments && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/LuminaGallery")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                        ?: throw IOException("Could not create PDF in Documents/LuminaGallery")
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            pdf.writeTo(out)
                        } ?: throw IOException("Unable to write PDF output")
                        context.contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null,
                            null
                        )
                        uri
                    } catch (e: Exception) {
                        context.contentResolver.delete(uri, null, null)
                        throw e
                    }
                } else {
                    val file = File(shareDir, filename)
                    FileOutputStream(file).use { out -> pdf.writeTo(out) }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
            } finally {
                pdf.close()
            }
        }
    }

    private fun saveBitmapToPictures(bitmap: Bitmap, filename: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LuminaGallery")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not save collage to Pictures")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
            } ?: throw IOException("Unable to write collage image")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun computeCenterCropRect(srcW: Int, srcH: Int, dstW: Float, dstH: Float): Rect {
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val dstAspect = dstW / dstH
        return if (srcAspect > dstAspect) {
            val cropW = (srcH * dstAspect).toInt().coerceIn(1, srcW)
            val left = (srcW - cropW) / 2
            Rect(left, 0, left + cropW, srcH)
        } else {
            val cropH = (srcW / dstAspect).toInt().coerceIn(1, srcH)
            val top = (srcH - cropH) / 2
            Rect(0, top, srcW, top + cropH)
        }
    }

    private suspend fun decodeScaledPhoto(item: MediaItem, maxDim: Int): Bitmap? {
        if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            val uri = Uri.parse(item.localContentUri)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val m = maxOf(info.size.width, info.size.height)
                        if (m > maxDim) {
                            val s = maxDim.toFloat() / m
                            dec.setTargetSize(
                                (info.size.width * s).toInt().coerceAtLeast(1),
                                (info.size.height * s).toInt().coerceAtLeast(1)
                            )
                        }
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()
        }

        val offlinePath = offlineMediaManager.getOfflineFilePath(item.id)
        if (!offlinePath.isNullOrBlank()) {
            val f = File(offlinePath)
            if (f.exists()) return decodeFileScaled(f, maxDim)
        }

        val tmp = File(context.cacheDir, "collage_tmp_${item.id.take(10)}_${System.currentTimeMillis()}.jpg")
        return try {
            driveApi.downloadFile(item.id).use { body ->
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            decodeFileScaled(tmp, maxDim)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun decodeFileScaled(file: File, maxDim: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val m = maxOf(info.size.width, info.size.height)
                if (m > maxDim) {
                    val s = maxDim.toFloat() / m
                    dec.setTargetSize(
                        (info.size.width * s).toInt().coerceAtLeast(1),
                        (info.size.height * s).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            BitmapFactory.decodeFile(file.absolutePath)
        }
    }.getOrNull()
}

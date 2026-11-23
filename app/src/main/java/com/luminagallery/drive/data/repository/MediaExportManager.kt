package com.luminagallery.drive.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.luminagallery.drive.data.remote.DriveApiService
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Handles on-demand full-resolution downloads for:
 *  1. Sharing via Android Share Sheet (temporary FileProvider cache, auto-pruned).
 *  2. Saving to device Pictures/Movies via MediaStore without storing full-res files permanently otherwise.
 */
class MediaExportManager(
    private val context: Context,
    private val driveApi: DriveApiService
) {
    private val shareDir: File
        get() = File(context.cacheDir, CoilCacheManager.SHARE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    suspend fun prepareShareUris(
        items: List<MediaItem>,
        stripLocation: Boolean = true,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<List<Uri>> = withContext(Dispatchers.IO) {
        runCatching {
            pruneStaleShareFiles()
            val uris = ArrayList<Uri>(items.size)
            val authority = "${context.packageName}.fileprovider"

            items.forEachIndexed { index, item ->
                if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
                    val parsed = Uri.parse(item.localContentUri)
                    if (stripLocation && !item.isVideo && !item.isRaw) {
                        val cleanFile = createExifStrippedLocalCopy(item, parsed)
                        uris.add(FileProvider.getUriForFile(context, authority, cleanFile))
                    } else {
                        uris.add(parsed)
                    }
                } else {
                    val prefix = if (stripLocation && !item.isVideo) "clean_" else ""
                    val safeName = sanitizeFilename("${prefix}${item.id.take(8)}_${item.name}")
                    val targetFile = File(shareDir, safeName)

                    if (!targetFile.exists() || targetFile.length() == 0L) {
                        val responseBody = driveApi.downloadFile(item.id)
                        responseBody.use { body ->
                            FileOutputStream(targetFile).use { output ->
                                body.byteStream().copyTo(output, BUFFER_SIZE)
                            }
                        }
                        if (stripLocation && !item.isVideo && !item.isRaw) {
                            stripExifFromFile(targetFile, item.mimeType)
                        }
                    }
                    uris.add(FileProvider.getUriForFile(context, authority, targetFile))
                }
                onProgress(index + 1, items.size)
            }
            uris
        }
    }

    private fun createExifStrippedLocalCopy(item: MediaItem, srcUri: Uri): File {
        val ext = item.name.substringAfterLast('.', "jpg").lowercase()
        val safeName = sanitizeFilename("clean_${item.id.take(12)}_${System.currentTimeMillis()}.$ext")
        val targetFile = File(shareDir, safeName)
        context.contentResolver.openInputStream(srcUri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output, BUFFER_SIZE)
            }
        } ?: throw IOException("Unable to read ${item.name} for privacy share")
        stripExifFromFile(targetFile, item.mimeType)
        return targetFile
    }

    /**
     * Creates a temporary EXIF/GPS-stripped copy of an arbitrary external content URI for sharing.
     * Videos and RAW files are passed through untouched.
     */
    suspend fun prepareCleanShareUri(
        srcUri: Uri,
        displayName: String,
        mimeType: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val isImage = mimeType.startsWith("image/", ignoreCase = true)
            val isRaw = mimeType.contains("raw", ignoreCase = true) ||
                mimeType.contains("dng", ignoreCase = true)
            if (!isImage || isRaw) return@runCatching srcUri
            pruneStaleShareFiles()
            val ext = displayName.substringAfterLast('.', "jpg").lowercase()
            val safeName = sanitizeFilename("clean_ext_${System.currentTimeMillis()}.$ext")
            val targetFile = File(shareDir, safeName)
            context.contentResolver.openInputStream(srcUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            } ?: throw IOException("Unable to read $displayName for sharing")
            stripExifFromFile(targetFile, mimeType)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
        }
    }

    /**
     * Removes GPS coordinates and identifying device EXIF metadata from a temporary shared copy
     * while leaving the user's original file untouched.
     */
    private fun stripExifFromFile(file: File, mimeType: String) {
        val strippedInPlace = runCatching {
            val exif = ExifInterface(file.absolutePath)
            SENSITIVE_EXIF_TAGS.forEach { tag ->
                exif.setAttribute(tag, null)
            }
            exif.saveAttributes()
            true
        }.getOrDefault(false)

        if (!strippedInPlace && mimeType.startsWith("image/", ignoreCase = true)) {
            // Fallback for formats where ExifInterface cannot write in-place (e.g., HEIC/certain PNGs):
            // re-encode clean pixels without EXIF blocks.
            runCatching {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { dec, _, _ ->
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    BitmapFactory.decodeFile(file.absolutePath)
                }
                if (bitmap != null) {
                    try {
                        val format = if (mimeType.contains("png", ignoreCase = true)) {
                            Bitmap.CompressFormat.PNG
                        } else {
                            Bitmap.CompressFormat.JPEG
                        }
                        FileOutputStream(file).use { out ->
                            bitmap.compress(format, 95, out)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    suspend fun saveToDeviceGallery(
        items: List<MediaItem>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var savedCount = 0
            items.forEachIndexed { index, item ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStoreQPlus(item)
                } else {
                    saveViaLegacyPublicStorage(item)
                }
                savedCount++
                onProgress(index + 1, items.size)
            }
            savedCount
        }
    }

    private suspend fun saveViaMediaStoreQPlus(item: MediaItem) {
        val resolver = context.contentResolver
        val isVideo = item.isVideo
        val collectionUri = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (isVideo) {
            "${Environment.DIRECTORY_MOVIES}/$EXPORT_SUBFOLDER"
        } else {
            "${Environment.DIRECTORY_PICTURES}/$EXPORT_SUBFOLDER"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeFilename(item.name))
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType.ifBlank {
                if (isVideo) "video/mp4" else "image/jpeg"
            })
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val insertedUri = resolver.insert(collectionUri, values)
            ?: throw IOException("Failed to create MediaStore entry for ${item.name}")

        try {
            val responseBody = driveApi.downloadFile(item.id)
            responseBody.use { body ->
                resolver.openOutputStream(insertedUri)?.use { out ->
                    body.byteStream().copyTo(out, BUFFER_SIZE)
                } ?: throw IOException("Unable to open output stream for ${item.name}")
            }

            val completedValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(insertedUri, completedValues, null, null)
        } catch (e: Exception) {
            resolver.delete(insertedUri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun saveViaLegacyPublicStorage(item: MediaItem) {
        val publicDir = Environment.getExternalStoragePublicDirectory(
            if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        )
        val appDir = File(publicDir, EXPORT_SUBFOLDER).apply {
            if (!exists()) mkdirs()
        }
        val targetFile = File(appDir, sanitizeFilename(item.name))
        val responseBody = driveApi.downloadFile(item.id)
        responseBody.use { body ->
            FileOutputStream(targetFile).use { out ->
                body.byteStream().copyTo(out, BUFFER_SIZE)
            }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf(item.mimeType),
            null
        )
    }

    private fun pruneStaleShareFiles() {
        val cutoff = System.currentTimeMillis() - SHARE_TTL_MS
        shareDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private fun sanitizeFilename(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(140).ifBlank { "drive_media" }

    companion object {
        private const val EXPORT_SUBFOLDER = "LuminaGallery"
        private const val BUFFER_SIZE = 32 * 1024
        private const val SHARE_TTL_MS = 2L * 60L * 60L * 1000L

        val SENSITIVE_EXIF_TAGS: List<String> = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_SERIAL_NUMBER
        )
    }
}

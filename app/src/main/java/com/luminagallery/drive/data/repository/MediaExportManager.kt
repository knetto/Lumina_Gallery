package com.luminagallery.drive.data.repository

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
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
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<List<Uri>> = withContext(Dispatchers.IO) {
        runCatching {
            pruneStaleShareFiles()
            val uris = ArrayList<Uri>(items.size)
            val authority = "${context.packageName}.fileprovider"

            items.forEachIndexed { index, item ->
                if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
                    uris.add(Uri.parse(item.localContentUri))
                } else {
                    val safeName = sanitizeFilename("${item.id.take(8)}_${item.name}")
                    val targetFile = File(shareDir, safeName)

                    if (!targetFile.exists() || targetFile.length() == 0L) {
                        val responseBody = driveApi.downloadFile(item.id)
                        responseBody.use { body ->
                            FileOutputStream(targetFile).use { output ->
                                body.byteStream().copyTo(output, BUFFER_SIZE)
                            }
                        }
                    }
                    uris.add(FileProvider.getUriForFile(context, authority, targetFile))
                }
                onProgress(index + 1, items.size)
            }
            uris
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

    private companion object {
        const val EXPORT_SUBFOLDER = "LuminaGallery"
        const val BUFFER_SIZE = 32 * 1024
        const val SHARE_TTL_MS = 2L * 60L * 60L * 1000L
    }
}

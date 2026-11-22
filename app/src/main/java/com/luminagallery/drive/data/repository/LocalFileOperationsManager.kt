package com.luminagallery.drive.data.repository

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.luminagallery.drive.data.remote.DriveApiService
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class WallpaperTarget(val label: String, val flags: Int) {
    HOME("Home screen", WallpaperManager.FLAG_SYSTEM),
    LOCK("Lock screen", WallpaperManager.FLAG_LOCK),
    BOTH("Home and lock screens", WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
}

/**
 * Handles everyday local gallery file operations & Android system integrations:
 *  - Copy local photos/videos into existing or brand-new device albums (zero extra permissions).
 *  - Move & Rename local files via Android 11+ `MediaStore.createWriteRequest` scoped-storage flow.
 *  - Set any local or Google Drive photo as Home / Lock / Both wallpaper.
 *  - Open in external photo editors (`ACTION_EDIT` chooser).
 */
class LocalFileOperationsManager(
    private val context: Context,
    private val driveApi: DriveApiService,
    private val offlineMediaManager: OfflineMediaManager
) {
    fun buildWritePermissionSender(items: List<MediaItem>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = items.mapNotNull { item ->
            if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
                Uri.parse(item.localContentUri)
            } else null
        }
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createWriteRequest(context.contentResolver, uris).intentSender
        }.getOrNull()
    }

    suspend fun copyLocalItemsToAlbum(
        items: List<MediaItem>,
        albumFolderName: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanAlbum = sanitizeFolderName(albumFolderName)
            var copied = 0
            val resolver = context.contentResolver

            for (item in items) {
                if (!item.isLocal || item.localContentUri.isNullOrBlank()) continue
                val srcUri = Uri.parse(item.localContentUri)
                val isVideo = item.isVideo
                val targetCollection = if (isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val baseDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        item.mimeType.ifBlank { if (isVideo) "video/mp4" else "image/jpeg" }
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$cleanAlbum")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val dstUri = resolver.insert(targetCollection, values)
                    ?: throw IOException("Could not create copy for ${item.name}")
                try {
                    resolver.openInputStream(srcUri)?.use { input ->
                        resolver.openOutputStream(dstUri)?.use { output ->
                            input.copyTo(output, 32 * 1024)
                        } ?: throw IOException("Cannot write ${item.name}")
                    } ?: throw IOException("Cannot read ${item.name}")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.update(
                            dstUri,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null,
                            null
                        )
                    }
                    copied++
                } catch (e: Exception) {
                    resolver.delete(dstUri, null, null)
                    throw e
                }
            }
            copied
        }
    }

    suspend fun moveLocalItemsToAlbum(
        items: List<MediaItem>,
        albumFolderName: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@runCatching copyLocalItemsToAlbum(items, albumFolderName).getOrThrow()
            }
            val cleanAlbum = sanitizeFolderName(albumFolderName)
            val resolver = context.contentResolver
            var moved = 0

            for (item in items) {
                if (!item.isLocal || item.localContentUri.isNullOrBlank()) continue
                val uri = Uri.parse(item.localContentUri)
                val baseDir = if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$cleanAlbum/")
                }
                val updated = resolver.update(uri, values, null, null)
                if (updated > 0) moved++
            }
            if (moved == 0) {
                throw IOException("Unable to move file(s). Android denied write permission.")
            }
            moved
        }
    }

    suspend fun renameLocalItem(item: MediaItem, newBaseName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(item.isLocal && !item.localContentUri.isNullOrBlank()) {
                    "Only on-device files can be renamed"
                }
                val sanitized = newBaseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                require(sanitized.isNotBlank()) { "Filename cannot be empty" }

                val originalExt = item.name.substringAfterLast('.', "")
                val finalName = if (sanitized.contains('.') || originalExt.isBlank()) {
                    sanitized
                } else {
                    "$sanitized.$originalExt"
                }

                val uri = Uri.parse(item.localContentUri)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                }
                val updated = context.contentResolver.update(uri, values, null, null)
                if (updated <= 0) {
                    throw IOException("Could not rename ${item.name}")
                }
                finalName
            }
        }

    suspend fun setAsWallpaper(item: MediaItem, target: WallpaperTarget): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(!item.isVideo) { "Videos cannot be set as a static wallpaper" }
                val bitmap = decodeWallpaperBitmap(item)
                    ?: throw IOException("Could not decode image for wallpaper")
                try {
                    val wm = WallpaperManager.getInstance(context)
                    wm.setBitmap(bitmap, null, true, target.flags)
                } finally {
                    bitmap.recycle()
                }
                Unit
            }
        }

    fun launchExternalEditor(activityContext: Context, uri: Uri, mimeType: String) {
        val editIntent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mimeType.ifBlank { "image/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(editIntent, "Edit with")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activityContext.startActivity(chooser)
    }

    private suspend fun decodeWallpaperBitmap(item: MediaItem): Bitmap? {
        val offlinePath = offlineMediaManager.getOfflineFilePath(item.id)
        if (!offlinePath.isNullOrBlank()) {
            val file = File(offlinePath)
            if (file.exists()) {
                return decodeScaledFile(file)
            }
        }
        if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            val uri = Uri.parse(item.localContentUri)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val maxDim = maxOf(info.size.width, info.size.height)
                        if (maxDim > 3200) {
                            val scale = 3200f / maxDim
                            decoder.setTargetSize(
                                (info.size.width * scale).toInt().coerceAtLeast(1),
                                (info.size.height * scale).toInt().coerceAtLeast(1)
                            )
                        }
                    }
                }.getOrNull()
            } else {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        }
        // Google Drive photo -> download into temp file & decode
        val tmp = File(context.cacheDir, "wallpaper_tmp_${System.currentTimeMillis()}.jpg")
        return try {
            driveApi.downloadFile(item.id).use { body ->
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            decodeScaledFile(tmp)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun decodeScaledFile(file: File): Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching {
            val src = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxDim = maxOf(info.size.width, info.size.height)
                if (maxDim > 3200) {
                    val scale = 3200f / maxDim
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        }.getOrNull()
    } else {
        BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun sanitizeFolderName(raw: String): String =
        raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "LuminaGallery" }
}

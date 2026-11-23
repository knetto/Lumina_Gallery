package com.luminagallery.drive.data.repository

import android.app.WallpaperManager
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
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
import java.io.IOException

data class InstalledGalleryApp(
    val packageName: String,
    val label: String,
    val handlesImages: Boolean,
    val handlesVideos: Boolean
) {
    val capabilitySummary: String
        get() = when {
            handlesImages && handlesVideos -> "Photos & videos"
            handlesImages -> "Photos"
            else -> "Videos"
        }
}

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)) {
            return null
        }
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
        albumFolderName: String,
        useDcimParent: Boolean = false,
        targetRelativePath: String? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanAlbum = sanitizeFolderName(albumFolderName)
            val normalizedExplicitPath = normalizeExplicitRelativePath(targetRelativePath)
            var copied = 0
            val resolver = context.contentResolver

            for (item in items) {
                if (!item.isLocal || item.localContentUri.isNullOrBlank()) continue
                val srcUri = Uri.parse(item.localContentUri)
                val isVideo = item.isVideo
                val targetCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (isVideo) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                } else {
                    if (isVideo) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                }
                val resolvedRelPath = normalizedExplicitPath ?: run {
                    val baseDir = when {
                        useDcimParent -> Environment.DIRECTORY_DCIM
                        isVideo && items.all { it.isVideo } -> Environment.DIRECTORY_MOVIES
                        else -> Environment.DIRECTORY_PICTURES
                    }
                    "$baseDir/$cleanAlbum"
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            item.mimeType.ifBlank { if (isVideo) "video/mp4" else "image/jpeg" }
                        )
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$resolvedRelPath/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val dstUri = resolver.insert(targetCollection, values)
                        ?: throw IOException("Could not create copy for ${item.name}")
                    try {
                        resolver.openInputStream(srcUri)?.use { input ->
                            resolver.openOutputStream(dstUri)?.use { output ->
                                input.copyTo(output, 32 * 1024)
                            } ?: throw IOException("Cannot write ${item.name}")
                        } ?: throw IOException("Cannot read ${item.name}")

                        resolver.update(
                            dstUri,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null,
                            null
                        )
                        copied++
                    } catch (e: Exception) {
                        resolver.delete(dstUri, null, null)
                        throw e
                    }
                } else {
                    // Android 8-9 (API 26-28): write directly to target directory and scan into MediaStore
                    @Suppress("DEPRECATION")
                    val destDir = File(Environment.getExternalStorageDirectory(), resolvedRelPath).apply {
                        if (!exists()) mkdirs()
                    }
                    var destFile = File(destDir, item.name)
                    if (destFile.exists()) {
                        val base = destFile.nameWithoutExtension
                        val ext = destFile.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
                        destFile = File(destDir, "${base}_${System.currentTimeMillis()}$ext")
                    }
                    resolver.openInputStream(srcUri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output, 32 * 1024)
                        }
                    } ?: throw IOException("Cannot read ${item.name}")
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(destFile.absolutePath),
                        arrayOf(item.mimeType.ifBlank { if (isVideo) "video/mp4" else "image/jpeg" }),
                        null
                    )
                    copied++
                }
            }
            copied
        }
    }

    suspend fun moveLocalItemsToAlbum(
        items: List<MediaItem>,
        albumFolderName: String,
        useDcimParent: Boolean = false,
        targetRelativePath: String? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanAlbum = sanitizeFolderName(albumFolderName)
            val normalizedExplicitPath = normalizeExplicitRelativePath(targetRelativePath)
            val resolver = context.contentResolver

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android 8-9: copy to destination, then remove originals so Move truly moves
                var moved = 0
                for (item in items) {
                    if (!item.isLocal || item.localContentUri.isNullOrBlank()) continue
                    copyLocalItemsToAlbum(listOf(item), cleanAlbum, useDcimParent, normalizedExplicitPath).getOrThrow()
                    val srcUri = Uri.parse(item.localContentUri)
                    val deleted = runCatching { resolver.delete(srcUri, null, null) }.getOrDefault(0)
                    if (deleted <= 0 && srcUri.scheme == "file") {
                        srcUri.path?.let { File(it).delete() }
                    }
                    moved++
                }
                return@runCatching moved
            }

            var moved = 0
            for (item in items) {
                if (!item.isLocal || item.localContentUri.isNullOrBlank()) continue
                val uri = Uri.parse(item.localContentUri)
                val resolvedRelPath = normalizedExplicitPath ?: run {
                    val baseDir = when {
                        useDcimParent -> Environment.DIRECTORY_DCIM
                        item.isVideo && items.all { it.isVideo } -> Environment.DIRECTORY_MOVIES
                        else -> Environment.DIRECTORY_PICTURES
                    }
                    "$baseDir/$cleanAlbum"
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$resolvedRelPath/")
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

    /**
     * Renames a local album folder by moving all of its MediaStore items into the new sibling
     * folder under the exact same parent directory (preserving nested paths & keeping mixed
     * photo+video albums together). Returns Pair(cleanName, newRelativePath).
     */
    suspend fun renameLocalAlbum(
        items: List<MediaItem>,
        newAlbumName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = sanitizeFolderName(newAlbumName)
            val localItems = items.filter { it.isLocal && !it.localContentUri.isNullOrBlank() }
            require(localItems.isNotEmpty()) { "Album has no local files to rename" }
            val currentPath = localItems.first().storagePath?.trim()?.trim('/').orEmpty()
            val parentDir = currentPath.substringBeforeLast('/', missingDelimiterValue = "")
            val newRelativePath = if (parentDir.isNotBlank()) {
                "$parentDir/$cleanName"
            } else {
                val useDcim = currentPath.startsWith("DCIM", ignoreCase = true)
                val baseDir = if (useDcim) Environment.DIRECTORY_DCIM else Environment.DIRECTORY_PICTURES
                "$baseDir/$cleanName"
            }
            moveLocalItemsToAlbum(
                items = localItems,
                albumFolderName = cleanName,
                useDcimParent = newRelativePath.startsWith("DCIM", ignoreCase = true),
                targetRelativePath = newRelativePath
            ).getOrThrow()
            cleanName
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

    fun launchNativeWallpaperCrop(activityContext: Context, uri: Uri, mimeType: String): Boolean {
        val wm = WallpaperManager.getInstance(context)
        val cropIntent = runCatching { wm.getCropAndSetWallpaperIntent(uri) }.getOrNull()
        val intent = cropIntent ?: Intent(Intent.ACTION_ATTACH_DATA).apply {
            setDataAndType(uri, mimeType.ifBlank { "image/*" })
            putExtra("mimeType", mimeType.ifBlank { "image/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (activityContext !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            activityContext.startActivity(Intent.createChooser(intent, "Set wallpaper with"))
            true
        }.getOrDefault(false)
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

    /**
     * Lists installed apps on the device capable of viewing photos and/or videos
     * (Samsung Gallery, Google Photos, Xiaomi Gallery, third-party galleries, video players),
     * excluding Lumina Gallery itself.
     */
    fun queryInstalledGalleryApps(): List<InstalledGalleryApp> {
        val pm = context.packageManager
        val selfPkg = context.packageName

        fun query(intent: Intent): List<ResolveInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrDefault(emptyList())

        val imageCandidates = buildList {
            addAll(
                query(
                    Intent(Intent.ACTION_VIEW).setDataAndType(
                        Uri.parse("content://media/external/images/media/1"),
                        "image/jpeg"
                    )
                )
            )
            addAll(query(Intent(Intent.ACTION_VIEW).setType("image/*")))
        }
        val videoCandidates = buildList {
            addAll(
                query(
                    Intent(Intent.ACTION_VIEW).setDataAndType(
                        Uri.parse("content://media/external/video/media/1"),
                        "video/mp4"
                    )
                )
            )
            addAll(query(Intent(Intent.ACTION_VIEW).setType("video/*")))
        }

        val imagePackages = imageCandidates
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != selfPkg }
            .toSet()
        val videoPackages = videoCandidates
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != selfPkg }
            .toSet()

        val allPackages = (imagePackages + videoPackages)
        val preferredGalleryOrder = listOf(
            "com.sec.android.gallery3d",           // Samsung Gallery
            "com.google.android.apps.photos",      // Google Photos
            "com.google.android.apps.photosgo",    // Gallery by Google
            "com.miui.gallery",                    // Xiaomi Gallery
            "com.oneplus.gallery",                 // OnePlus Photos
            "com.coloros.gallery3d",               // OPPO/Realme Photos
            "com.vivo.gallery",                    // vivo Albums
            "com.android.gallery3d",               // AOSP Gallery
            "org.fossify.gallery",                 // Fossify Gallery
            "com.simplemobiletools.gallery.pro",   // Simple Gallery Pro
            "deckers.thibault.aves"                // Aves Gallery
        )

        return allPackages.mapNotNull { pkg ->
            val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(appInfo).toString().trim() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: pkg
            InstalledGalleryApp(
                packageName = pkg,
                label = label,
                handlesImages = pkg in imagePackages,
                handlesVideos = pkg in videoPackages
            )
        }.sortedWith(
            compareBy<InstalledGalleryApp>(
                {
                    val idx = preferredGalleryOrder.indexOf(it.packageName)
                    if (idx >= 0) idx else preferredGalleryOrder.size
                },
                { !(it.handlesImages && it.handlesVideos) },
                { !it.handlesImages },
                { it.label.lowercase() }
            )
        )
    }

    /**
     * Returns an immediately usable URI for local MediaStore files or offline-pinned Drive files
     * (0ms, zero network), or `null` if a cloud-only Drive item needs to be staged first.
     */
    fun getQuickLocalOrOfflineUri(item: MediaItem): Uri? {
        if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            val parsed = Uri.parse(item.localContentUri)
            if (parsed.scheme == "file") {
                // Never expose raw file:// URIs (Android 7+ throws FileUriExposedException);
                // return null so callers stage via FileProvider safely.
                return null
            }
            return parsed
        }
        val offlinePath = offlineMediaManager.getOfflineFilePath(item.id)
        if (!offlinePath.isNullOrBlank()) {
            val file = File(offlinePath)
            if (file.exists() && file.length() > 0L) {
                return runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()
            }
        }
        return null
    }

    /**
     * Opens a photo or video in the user's configured external gallery app (or opens Android's
     * app chooser excluding Lumina Gallery when set to "Ask every time" or if the chosen app
     * cannot handle that media type).
     */
    fun launchInExternalGallery(
        activityContext: Context,
        uri: Uri,
        mimeType: String,
        isVideo: Boolean,
        targetPackage: String?
    ): Result<Unit> = runCatching {
        val pm = context.packageManager
        val fallbackMime = if (isVideo) "video/*" else "image/*"
        val resolvedMime = mimeType.ifBlank { fallbackMime }

        val needsNewTask = activityContext !is android.app.Activity

        fun buildViewIntent(mime: String, pkg: String?): Intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (needsNewTask) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!pkg.isNullOrBlank()) {
                setPackage(pkg)
            }
        }

        if (!targetPackage.isNullOrBlank()) {
            val directExact = buildViewIntent(resolvedMime, targetPackage)
            val directIntent = when {
                directExact.resolveActivity(pm) != null -> directExact
                else -> {
                    val directWild = buildViewIntent(fallbackMime, targetPackage)
                    if (directWild.resolveActivity(pm) != null) directWild else null
                }
            }
            if (directIntent != null) {
                runCatching {
                    context.grantUriPermission(targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activityContext.startActivity(directIntent)
                return@runCatching
            }
        }

        val baseIntent = buildViewIntent(resolvedMime, null)
        val chooser = Intent.createChooser(baseIntent, "Open in another gallery").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (needsNewTask) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(context.packageName, "com.luminagallery.drive.MainActivity"))
                )
            }
        }
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

    private fun normalizeExplicitRelativePath(raw: String?): String? {
        val cleaned = raw?.trim()?.replace('\\', '/')?.trim('/') ?: return null
        if (cleaned.isBlank()) return null
        val segments = cleaned.split('/').mapNotNull { seg ->
            val trimmed = seg.trim().trimStart('.')
            if (trimmed.isBlank() || trimmed == "." || trimmed == "..") null else trimmed
        }
        return segments.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    companion object {
        internal fun sanitizeFolderName(raw: String): String {
            val cleaned = raw.trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trimStart('.')
                .trim()
            return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "LuminaGallery" else cleaned
        }
    }
}

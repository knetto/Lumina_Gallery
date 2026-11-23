package com.luminagallery.drive.data.local

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import com.luminagallery.drive.data.local.entity.LockedAlbumEntity
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.ExifMetadata
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class HiddenFolderMoveResult(
    val hiddenDirPath: String,
    val originalDirPath: String,
    val displayName: String,
    val originalRelativePath: String
)

/**
 * Hides and restores locked local albums on disk using a dot-prefixed folder + `.nomedia` marker:
 *  - Atomic `File.renameTo` on the same filesystem (0ms, zero multi-GB copying, survives uninstalls).
 *  - Creates `.nomedia` so Android MediaStore, Samsung/Xiaomi Gallery, Google Photos, and app photo
 *    pickers stop indexing & showing the files, and default File Explorer hides the dot-folder.
 *  - Writes a tiny `.lumina_locked` marker so Lumina can still recognize its hidden folders even
 *    after a reinstall or clear-data.
 */
class HiddenLockedFolderManager(private val context: Context) {

    private data class CachedFileMeta(
        val lastModified: Long,
        val length: Long,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?
    )

    private val fileMetaCache = ConcurrentHashMap<String, CachedFileMeta>()

    fun requiresAllFilesPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun buildAllFilesAccessIntent(): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSpecific = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            if (appSpecific.resolveActivity(context.packageManager) != null) {
                return appSpecific
            }
            return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }

    /**
     * Resolves the physical folder on disk for a local album from MediaStore `_DATA` (or `storagePath`).
     */
    fun resolveAlbumDirectory(items: List<MediaItem>): File? {
        for (item in items) {
            if (!item.isLocal || item.localContentUri.isNullOrBlank()) continue
            val uri = Uri.parse(item.localContentUri)
            if (uri.scheme == "file") {
                val parent = uri.path?.let { File(it).parentFile }
                if (parent != null && parent.isDirectory) return parent
            } else {
                val dataPath = queryMediaStoreDataPath(uri)
                if (!dataPath.isNullOrBlank()) {
                    val parent = File(dataPath).parentFile
                    if (parent != null && parent.isDirectory) return parent
                }
            }
        }
        val rel = items.firstOrNull { it.isLocal }?.storagePath?.trim()?.trim('/')
        if (!rel.isNullOrBlank()) {
            val candidate = File(Environment.getExternalStorageDirectory(), rel)
            if (candidate.isDirectory) return candidate
        }
        return null
    }

    /**
     * Hides a local album folder by renaming it to a dot-prefixed hidden sibling folder and
     * dropping `.nomedia` + `.lumina_locked` inside, then notifying MediaScanner to prune stale rows.
     */
    suspend fun hideFolderOnDisk(
        albumId: String,
        albumName: String,
        originalRelativePath: String?,
        items: List<MediaItem>
    ): Result<HiddenFolderMoveResult> = withContext(Dispatchers.IO) {
        runCatching {
            check(hasAllFilesAccess()) {
                "All files access is required to hide folders from File Explorer"
            }
            val originalDir = resolveAlbumDirectory(items)
                ?: throw IOException("Could not locate folder on device storage")

            check(isSafeFolderToHide(originalDir)) {
                "Top-level system folder \"${originalDir.name}\" cannot be renamed on disk"
            }

            val parentDir = originalDir.parentFile
                ?: throw IOException("Folder has no parent directory")
            val cleanName = originalDir.name.trimStart('.').ifBlank { albumName.ifBlank { "Album" } }

            // Record every file path BEFORE renaming so MediaScanner prunes stale MediaStore rows
            val oldFilePaths = collectMediaPathsRecursively(originalDir)

            val hiddenDir = if (originalDir.name.startsWith(".")) {
                originalDir
            } else {
                val target = pickUniqueSibling(parentDir, ".$cleanName")
                if (!originalDir.renameTo(target)) {
                    throw IOException("Unable to hide folder \"${originalDir.name}\" on storage")
                }
                target
            }

            // Create .nomedia + .lumina_locked markers inside hiddenDir
            val nomediaFile = File(hiddenDir, NOMEDIA_FILE)
            if (!nomediaFile.exists()) {
                runCatching { nomediaFile.createNewFile() }
            }
            val normalizedRelPath = computeRelativePath(originalDir, originalRelativePath, albumName)
            writeLockMarker(
                hiddenDir = hiddenDir,
                albumId = albumId,
                displayName = albumName.ifBlank { cleanName },
                originalDirPath = originalDir.absolutePath,
                originalRelativePath = normalizedRelPath
            )

            // Trigger MediaScanner on old paths (removed -> pruned from MediaStore!) & .nomedia
            val pathsToScan = buildList {
                addAll(oldFilePaths)
                add(originalDir.absolutePath)
                add(hiddenDir.absolutePath)
                add(nomediaFile.absolutePath)
            }.distinct().toTypedArray()
            if (pathsToScan.isNotEmpty()) {
                MediaScannerConnection.scanFile(context, pathsToScan, null, null)
            }

            HiddenFolderMoveResult(
                hiddenDirPath = hiddenDir.absolutePath,
                originalDirPath = originalDir.absolutePath,
                displayName = albumName.ifBlank { cleanName },
                originalRelativePath = normalizedRelPath
            )
        }
    }

    /**
     * Restores a disk-hidden locked folder back to its visible folder name, removes `.nomedia` &
     * `.lumina_locked`, and triggers MediaScanner so Android galleries & File Explorer see it again.
     */
    suspend fun unhideFolderOnDisk(entity: LockedAlbumEntity): Result<File?> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!entity.isHiddenOnDisk || entity.hiddenDirPath.isNullOrBlank()) {
                    return@runCatching null
                }
                check(hasAllFilesAccess()) {
                    "Grant All files access to restore this hidden folder to File Explorer"
                }

                val hiddenDir = File(entity.hiddenDirPath)
                if (!hiddenDir.exists() || !hiddenDir.isDirectory) {
                    return@runCatching null
                }

                // Remove hiding markers first
                runCatching { File(hiddenDir, NOMEDIA_FILE).delete() }
                runCatching { File(hiddenDir, LOCK_MARKER_FILE).delete() }

                val desiredTarget = entity.originalDirPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { File(it) }
                    ?: File(
                        hiddenDir.parentFile ?: Environment.getExternalStorageDirectory(),
                        hiddenDir.name.trimStart('.').ifBlank { entity.displayName ?: "Restored Album" }
                    )

                val restoredDir: File = if (hiddenDir.absolutePath == desiredTarget.absolutePath) {
                    hiddenDir
                } else if (!desiredTarget.exists()) {
                    if (!hiddenDir.renameTo(desiredTarget)) {
                        // Fallback if rename fails across volumes
                        val fallback = pickUniqueSibling(
                            hiddenDir.parentFile ?: Environment.getExternalStorageDirectory(),
                            desiredTarget.name
                        )
                        if (!hiddenDir.renameTo(fallback)) {
                            throw IOException("Unable to restore folder \"${desiredTarget.name}\"")
                        }
                        fallback
                    } else {
                        desiredTarget
                    }
                } else {
                    // Destination already recreated (e.g. new camera photos taken while locked):
                    // recursively merge all hidden files & subdirectories cleanly before removing hiddenDir.
                    mergeDirectoryContents(sourceDir = hiddenDir, destDir = desiredTarget)
                    if (isDirectoryEffectivelyEmpty(hiddenDir)) {
                        hiddenDir.deleteRecursively()
                    }
                    desiredTarget
                }

                val restoredPaths = buildList {
                    add(hiddenDir.absolutePath)
                    add(restoredDir.absolutePath)
                    addAll(collectMediaPathsRecursively(restoredDir))
                }.distinct().toTypedArray()
                if (restoredPaths.isNotEmpty()) {
                    MediaScannerConnection.scanFile(context, restoredPaths, null, null)
                }

                restoredDir
            }
        }

    /**
     * Reads all photos/videos directly from a hidden `.folder` so Lumina can browse, view, play,
     * share, and edit them after biometric unlock even though Android MediaStore ignores them.
     */
    fun scanHiddenFolderMedia(entity: LockedAlbumEntity): List<MediaItem> {
        val dirPath = entity.hiddenDirPath ?: return emptyList()
        if (!hasAllFilesAccess()) return emptyList()
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val files = collectMediaFilesRecursively(dir)
        if (files.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val currentYear = LocalDate.now(zone).year
        val displayFolderName = entity.displayName?.takeIf { it.isNotBlank() }
            ?: dir.name.trimStart('.').ifBlank { "Locked Album" }
        val storagePathDisplay = entity.originalRelativePath?.takeIf { it.isNotBlank() }
            ?: displayFolderName

        val results = ArrayList<MediaItem>(files.size)
        for (file in files) {
            if (!file.isFile || file.name.startsWith(".") || file.length() <= 0L) continue
            val ext = file.extension.lowercase(Locale.US)
            val isVideo = ext in VIDEO_EXTS
            val isRaw = ext in RAW_EXTS
            val isImage = ext in IMAGE_EXTS || isRaw
            if (!isVideo && !isImage) continue

            val mime = resolveMimeType(ext, isVideo, isRaw)
            val type = when {
                isVideo -> MediaType.VIDEO
                isRaw -> MediaType.RAW
                else -> MediaType.PHOTO
            }

            val lastMod = file.lastModified()
            val fileLen = file.length()
            val epochMs = lastMod.takeIf { it > 0L } ?: System.currentTimeMillis()
            val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
            val monthYear = MONTH_YEAR_FMT.format(zdt)
            val fullDate = FULL_DATE_FMT.format(zdt)
            val dayLabel = if (zdt.year == currentYear) {
                SAME_YEAR_DAY_FMT.format(zdt)
            } else {
                OTHER_YEAR_DAY_FMT.format(zdt)
            }

            val cachedMeta = fileMetaCache[file.absolutePath]?.takeIf {
                it.lastModified == lastMod && it.length == fileLen
            }
            var width: Int? = cachedMeta?.width
            var height: Int? = cachedMeta?.height
            var durationMs: Long? = cachedMeta?.durationMs
            if (cachedMeta == null) {
                if (!isVideo && !isRaw) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        width = opts.outWidth
                        height = opts.outHeight
                    }
                } else if (isVideo) {
                    runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            durationMs = retriever
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull()
                                ?.takeIf { it > 0L }
                            width = retriever
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                ?.toIntOrNull()
                                ?.takeIf { it > 0 }
                            height = retriever
                                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                ?.toIntOrNull()
                                ?.takeIf { it > 0 }
                        } finally {
                            runCatching { retriever.release() }
                        }
                    }
                }
                if (fileMetaCache.size > 4000) fileMetaCache.clear()
                fileMetaCache[file.absolutePath] = CachedFileMeta(lastMod, fileLen, width, height, durationMs)
            }

            val fileUri = Uri.fromFile(file).toString()
            results.add(
                MediaItem(
                    id = "${LocalMediaRepository.LOCAL_ITEM_PREFIX}hidden_${stableShortHash(file.absolutePath)}",
                    parentId = entity.albumId,
                    name = file.name,
                    mimeType = mime,
                    mediaType = type,
                    sizeBytes = file.length(),
                    createdTimeEpochMs = epochMs,
                    monthYearLabel = monthYear,
                    fullDateLabel = fullDate,
                    thumbnailLink = fileUri,
                    width = width,
                    height = height,
                    rotation = 0,
                    durationMillis = durationMs,
                    exif = ExifMetadata(
                        cameraMake = null,
                        cameraModel = null,
                        lens = null,
                        aperture = null,
                        exposureTime = null,
                        isoSpeed = null,
                        focalLength = null,
                        dateTakenDisplay = fullDate
                    ),
                    isLocal = true,
                    localContentUri = fileUri,
                    relativePath = displayFolderName,
                    storagePath = storagePathDisplay,
                    daySectionLabel = dayLabel
                )
            )
        }
        results.sortByDescending { it.createdTimeEpochMs }
        return results
    }

    fun buildHiddenAlbumFolder(entity: LockedAlbumEntity, items: List<MediaItem>): DriveFolder {
        val newest = items.firstOrNull()
        val fallbackName = entity.hiddenDirPath
            ?.let { File(it).name.trimStart('.') }
            ?.takeIf { it.isNotBlank() }
            ?: "Locked Album"
        val displayName = entity.displayName?.takeIf { it.isNotBlank() } ?: fallbackName
        return DriveFolder(
            id = entity.albumId,
            parentId = null,
            name = displayName,
            subfolderCount = 0,
            mediaCount = items.size,
            coverThumbnailLink = newest?.localContentUri,
            coverItemId = newest?.id,
            modifiedTimeEpochMs = newest?.createdTimeEpochMs ?: entity.lockedAtEpochMs,
            isPinned = false,
            isLocal = true,
            coverIsVideo = newest?.isVideo ?: false,
            coverSizeBytes = newest?.sizeBytes ?: 0L,
            relativePath = entity.originalRelativePath ?: displayName,
            isLocked = true
        )
    }

    /**
     * Scans standard media roots (`DCIM`, `Pictures`, `Movies`, `Download`) for `.lumina_locked`
     * dot-folders created by Lumina before a reinstall or data wipe so they automatically reappear.
     */
    suspend fun discoverHiddenFoldersOnDisk(): List<LockedAlbumEntity> = withContext(Dispatchers.IO) {
        if (!hasAllFilesAccess()) return@withContext emptyList()
        val extRoot = Environment.getExternalStorageDirectory()
        val scanRoots = listOf(
            extRoot,
            File(extRoot, "DCIM"),
            File(extRoot, "Pictures"),
            File(extRoot, "Movies"),
            File(extRoot, "Download"),
            File(extRoot, "Documents")
        ).distinctBy { it.absolutePath }
        val discovered = LinkedHashMap<String, LockedAlbumEntity>()
        val visited = HashSet<String>()
        val now = System.currentTimeMillis()

        fun scanDir(dir: File, depth: Int) {
            if (depth > 3 || !dir.isDirectory) return
            val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visited.add(canonical)) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (!child.isDirectory) continue
                val name = child.name
                if (name.startsWith(".")) {
                    val marker = File(child, LOCK_MARKER_FILE)
                    if (marker.isFile) {
                        val parsed = runCatching {
                            val obj = JSONObject(marker.readText(Charsets.UTF_8))
                            val albumId = obj.optString("albumId").takeIf { it.isNotBlank() }
                                ?: "${LocalMediaRepository.LOCAL_BUCKET_PREFIX}hidden_${stableShortHash(child.absolutePath)}"
                            val displayName = obj.optString("displayName").takeIf { it.isNotBlank() }
                                ?: name.trimStart('.')
                            val origPath = obj.optString("originalDirPath").takeIf { it.isNotBlank() }
                            val origRel = obj.optString("originalRelativePath").takeIf { it.isNotBlank() }
                            LockedAlbumEntity(
                                albumId = albumId,
                                lockedAtEpochMs = now,
                                isHiddenOnDisk = true,
                                hiddenDirPath = child.absolutePath,
                                originalDirPath = origPath,
                                displayName = displayName,
                                originalRelativePath = origRel
                            )
                        }.getOrNull()
                        if (parsed != null) {
                            discovered[parsed.albumId] = parsed
                        }
                    }
                    continue
                }
                if (depth < 3 && !name.equals("Android", ignoreCase = true)) {
                    scanDir(child, depth + 1)
                }
            }
        }

        for (root in scanRoots) {
            scanDir(root, depth = 0)
        }
        discovered.values.toList()
    }

    suspend fun deleteHiddenFilesDirectly(items: List<MediaItem>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                var deleted = 0
                for (item in items) {
                    val uriStr = item.localContentUri ?: continue
                    val uri = Uri.parse(uriStr)
                    if (uri.scheme == "file") {
                        val file = uri.path?.let { File(it) } ?: continue
                        if (!file.exists() || file.delete()) {
                            deleted++
                        }
                    }
                }
                if (deleted == 0) {
                    throw IOException("Could not delete locked album file(s)")
                }
                deleted
            }
        }

    @Suppress("DEPRECATION")
    private fun queryMediaStoreDataPath(contentUri: Uri): String? = runCatching {
        context.contentResolver.query(
            contentUri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null
        )?.use { cursor ->
            val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else null
        }
    }.getOrNull()

    private fun writeLockMarker(
        hiddenDir: File,
        albumId: String,
        displayName: String,
        originalDirPath: String,
        originalRelativePath: String
    ) {
        runCatching {
            val payload = JSONObject().apply {
                put("albumId", albumId)
                put("displayName", displayName)
                put("originalDirPath", originalDirPath)
                put("originalRelativePath", originalRelativePath)
            }.toString()
            File(hiddenDir, LOCK_MARKER_FILE).writeText(payload, Charsets.UTF_8)
        }
    }

    private fun collectMediaFilesRecursively(dir: File, maxDepth: Int = 8): List<File> {
        val out = mutableListOf<File>()
        fun walk(current: File, depth: Int) {
            if (depth > maxDepth) return
            val files = current.listFiles() ?: return
            for (f in files) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else if (f.isFile) {
                    out.add(f)
                }
            }
        }
        walk(dir, 0)
        return out
    }

    private fun collectMediaPathsRecursively(dir: File): List<String> =
        collectMediaFilesRecursively(dir).map { it.absolutePath }

    private fun computeRelativePath(
        originalDir: File,
        fallbackRelPath: String?,
        fallbackName: String
    ): String {
        val extRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        val abs = originalDir.absolutePath
        if (abs.startsWith("$extRoot/")) {
            return abs.removePrefix("$extRoot/").trim('/')
        }
        return fallbackRelPath?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
            ?: fallbackName.ifBlank { originalDir.name }
    }

    private fun resolveMimeType(ext: String, isVideo: Boolean, isRaw: Boolean): String {
        if (isVideo) {
            return com.luminagallery.drive.data.repository.VideoFormatSupport.normalizeVideoMimeType("file.$ext", null)
        }
        val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!guessed.isNullOrBlank()) return guessed
        return when {
            isRaw -> "image/x-adobe-dng"
            else -> "image/jpeg"
        }
    }

    companion object {
        private const val NOMEDIA_FILE = ".nomedia"
        private const val LOCK_MARKER_FILE = ".lumina_locked"

        private val RESERVED_TOP_LEVEL_DIRS = setOf(
            "dcim", "pictures", "movies", "download", "downloads", "documents",
            "music", "android", "alarms", "notifications", "ringtones",
            "podcasts", "audiobooks", "recordings"
        )

        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif")
        private val VIDEO_EXTS = com.luminagallery.drive.data.repository.VideoFormatSupport.ALL_VIDEO_EXTENSIONS
        private val RAW_EXTS = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "orf", "rw2", "raf", "pef", "srw", "raw")

        private val MONTH_YEAR_FMT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
        private val FULL_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.getDefault())
        private val SAME_YEAR_DAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
        private val OTHER_YEAR_DAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())

        fun isHiddenFileUri(uriStr: String?): Boolean =
            uriStr != null && uriStr.startsWith("file://", ignoreCase = true)

        internal fun mergeDirectoryContents(sourceDir: File, destDir: File) {
            if (!destDir.exists()) destDir.mkdirs()
            val children = sourceDir.listFiles() ?: return
            for (child in children) {
                if (child.name == NOMEDIA_FILE || child.name == LOCK_MARKER_FILE) {
                    runCatching { child.delete() }
                    continue
                }
                if (child.isDirectory) {
                    val targetDir = File(destDir, child.name)
                    if (!targetDir.exists()) {
                        if (!child.renameTo(targetDir)) {
                            targetDir.mkdirs()
                            mergeDirectoryContents(child, targetDir)
                            if (isDirectoryEffectivelyEmpty(child)) {
                                child.delete()
                            }
                        }
                    } else if (targetDir.isDirectory) {
                        mergeDirectoryContents(child, targetDir)
                        if (isDirectoryEffectivelyEmpty(child)) {
                            child.delete()
                        }
                    } else {
                        val uniqueDir = pickUniqueSibling(destDir, child.name)
                        if (!child.renameTo(uniqueDir)) {
                            uniqueDir.mkdirs()
                            mergeDirectoryContents(child, uniqueDir)
                            if (isDirectoryEffectivelyEmpty(child)) {
                                child.delete()
                            }
                        }
                    }
                } else {
                    var target = File(destDir, child.name)
                    if (target.exists()) {
                        val base = child.nameWithoutExtension
                        val ext = child.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
                        var idx = 1
                        while (target.exists() && idx < 500) {
                            target = File(destDir, "${base}_$idx$ext")
                            idx++
                        }
                    }
                    if (!child.renameTo(target)) {
                        child.copyTo(target, overwrite = false)
                        child.delete()
                    }
                }
            }
        }

        internal fun isDirectoryEffectivelyEmpty(dir: File): Boolean {
            val children = dir.listFiles() ?: return true
            return children.all { it.name == NOMEDIA_FILE || it.name == LOCK_MARKER_FILE }
        }

        private fun pickUniqueSibling(parentDir: File, desiredName: String): File {
            var candidate = File(parentDir, desiredName)
            var suffix = 1
            while (candidate.exists() && suffix < 100) {
                candidate = File(parentDir, "${desiredName}_$suffix")
                suffix++
            }
            return candidate
        }

        internal fun isSafeFolderPathToHide(dirPath: String, externalStorageRootPath: String): Boolean {
            val cleanRoot = externalStorageRootPath.replace('\\', '/').trimEnd('/').lowercase(Locale.US)
            val cleanDir = dirPath.replace('\\', '/').trimEnd('/')
            val lowerDir = cleanDir.lowercase(Locale.US)
            if (cleanDir.isBlank() || lowerDir == cleanRoot) return false
            val rel = if (lowerDir.startsWith("$cleanRoot/")) {
                lowerDir.removePrefix("$cleanRoot/").trim('/')
            } else {
                lowerDir.trim('/')
            }
            if (rel.isEmpty()) return false
            // Never rename a direct top-level root like DCIM, Pictures, Movies, Download, or Android
            if ('/' !in rel && rel in RESERVED_TOP_LEVEL_DIRS) return false
            if (rel == "android" || rel.startsWith("android/")) return false
            return true
        }

        private fun isSafeFolderToHide(dir: File): Boolean {
            val rootPath = runCatching { Environment.getExternalStorageDirectory().absolutePath }
                .getOrDefault("/storage/emulated/0")
            return isSafeFolderPathToHide(dir.absolutePath, rootPath)
        }

        private fun stableShortHash(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

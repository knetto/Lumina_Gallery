package com.luminagallery.drive.data.repository

import android.content.Context
import com.luminagallery.drive.data.local.GalleryDatabase
import com.luminagallery.drive.data.local.entity.OfflinePinnedEntity
import com.luminagallery.drive.data.remote.DriveApiService
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages full-resolution offline pinning for Google Drive albums & individual photos/videos.
 * Keeps an in-memory map synchronized with Room so ViewerScreen & VideoPlayerPage resolve
 * offline files in 0ms without touching the network (works seamlessly on airplanes!).
 */
class OfflineMediaManager(
    private val context: Context,
    database: GalleryDatabase,
    private val driveApi: DriveApiService
) {
    private val extrasDao = database.galleryExtrasDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val offlineDir: File
        get() = File(context.filesDir, "offline_media").apply { if (!exists()) mkdirs() }

    @Volatile
    private var pathByItemId: Map<String, String> = emptyMap()

    val offlineItemsFlow: StateFlow<List<OfflinePinnedEntity>> = extrasDao.observeAllOfflineItems()
        .map { list ->
            val validMap = HashMap<String, String>(list.size)
            val validList = ArrayList<OfflinePinnedEntity>(list.size)
            for (entry in list) {
                val file = File(entry.localFilePath)
                if (file.exists() && file.length() > 0L) {
                    validMap[entry.itemId] = entry.localFilePath
                    validList.add(entry)
                }
            }
            pathByItemId = validMap
            validList
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val offlineItemIdsFlow: Flow<Set<String>> = offlineItemsFlow.map { list ->
        list.mapTo(HashSet()) { it.itemId }
    }

    val offlineTotalBytesFlow: Flow<Long> = offlineItemsFlow.map { list ->
        list.sumOf { it.sizeBytes }
    }

    fun getOfflineFilePath(itemId: String): String? = pathByItemId[itemId]

    fun isItemOffline(itemId: String): Boolean = pathByItemId.containsKey(itemId)

    suspend fun pinItemOffline(item: MediaItem, folderId: String? = item.parentId): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(!item.isLocal) { "On-device files are already available offline" }
                val existingPath = pathByItemId[item.id]
                if (!existingPath.isNullOrBlank() && File(existingPath).exists()) {
                    return@runCatching File(existingPath)
                }

                val ext = item.name.substringAfterLast('.', "").take(8).filter { it.isLetterOrDigit() }
                val safeId = item.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val targetFile = File(offlineDir, if (ext.isNotEmpty()) "$safeId.$ext" else safeId)
                val partFile = File(offlineDir, "${targetFile.name}.part")
                if (partFile.exists()) partFile.delete()

                try {
                    driveApi.downloadFile(item.id).use { body ->
                        FileOutputStream(partFile).use { out ->
                            body.byteStream().copyTo(out, 32 * 1024)
                        }
                    }
                    if (partFile.length() <= 0L) {
                        throw java.io.IOException("Downloaded offline file was empty")
                    }
                    if (targetFile.exists()) targetFile.delete()
                    if (!partFile.renameTo(targetFile)) {
                        partFile.copyTo(targetFile, overwrite = true)
                        partFile.delete()
                    }
                } catch (e: Exception) {
                    runCatching { partFile.delete() }
                    throw e
                }

                extrasDao.upsertOfflineItem(
                    OfflinePinnedEntity(
                        itemId = item.id,
                        folderId = folderId,
                        localFilePath = targetFile.absolutePath,
                        sizeBytes = targetFile.length(),
                        pinnedAtEpochMs = System.currentTimeMillis()
                    )
                )
                targetFile
            }
        }

    suspend fun pinFolderOffline(
        folderId: String,
        items: List<MediaItem>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val driveItems = items.filter { !it.isLocal }
            var pinnedCount = 0
            driveItems.forEachIndexed { idx, item ->
                if (pinItemOffline(item, folderId).isSuccess) {
                    pinnedCount++
                }
                onProgress(idx + 1, driveItems.size)
            }
            pinnedCount
        }
    }

    suspend fun unpinItemOffline(itemId: String) = withContext(Dispatchers.IO) {
        val existing = extrasDao.getOfflineItem(itemId)
        existing?.let { runCatching { File(it.localFilePath).delete() } }
        extrasDao.deleteOfflineItem(itemId)
    }

    suspend fun unpinFolderOffline(folderId: String) = withContext(Dispatchers.IO) {
        val entries = extrasDao.getOfflineItemsByFolder(folderId)
        for (entry in entries) {
            runCatching { File(entry.localFilePath).delete() }
        }
        extrasDao.deleteOfflineItemsByFolder(folderId)
    }

    suspend fun clearAllOffline() = withContext(Dispatchers.IO) {
        runCatching { offlineDir.deleteRecursively() }
        extrasDao.clearAllOfflineItems()
    }
}

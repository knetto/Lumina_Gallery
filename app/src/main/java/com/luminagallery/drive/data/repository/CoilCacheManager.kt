package com.luminagallery.drive.data.repository

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Manages the Coil 3 ImageLoader backed by our HTTP/2 authenticated OkHttpClient,
 * aggressive LRU Disk Cache (configurable 250 MB – 2 GB), and 25% Memory Cache.
 */
class CoilCacheManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    initialMaxCacheMb: Int
) {
    private val diskCacheDir: File = File(context.cacheDir, CACHE_DIR_NAME)
    private val shareCacheDir: File = File(context.cacheDir, SHARE_DIR_NAME)
    private val cacheMutex = Mutex()

    private val _imageLoader = MutableStateFlow(buildImageLoader(initialMaxCacheMb))
    val imageLoaderFlow: StateFlow<ImageLoader> = _imageLoader.asStateFlow()

    val currentImageLoader: ImageLoader get() = _imageLoader.value

    private val _diskUsageBytes = MutableStateFlow(computeDirectoryBytes(diskCacheDir))
    val diskUsageBytesFlow: StateFlow<Long> = _diskUsageBytes.asStateFlow()

    suspend fun updateMaxCacheSizeMb(maxSizeMb: Int) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val clamped = maxSizeMb.coerceIn(250, 2048)
            val previous = _imageLoader.value
            // Shut down existing ImageLoader first to release DiskLruCache journal lock
            previous.shutdown()
            _imageLoader.value = buildImageLoader(clamped)
            refreshDiskUsage()
        }
    }

    suspend fun clearThumbnailCache() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val loader = _imageLoader.value
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            if (shareCacheDir.exists()) {
                shareCacheDir.deleteRecursively()
            }
            refreshDiskUsage()
        }
    }

    suspend fun refreshDiskUsage(): Long = withContext(Dispatchers.IO) {
        val bytes = computeDirectoryBytes(diskCacheDir)
        _diskUsageBytes.value = bytes
        bytes
    }

    private fun buildImageLoader(maxCacheMb: Int): ImageLoader {
        if (!diskCacheDir.exists()) diskCacheDir.mkdirs()
        val maxBytes = maxCacheMb.toLong() * 1024L * 1024L

        return ImageLoader.Builder(context)
            .components {
                add(RawImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { okHttpClient }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskCacheDir.toOkioPath())
                    .maxSizeBytes(maxBytes)
                    .build()
            }
            .crossfade(160)
            .build()
    }

    private fun computeDirectoryBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    companion object {
        const val CACHE_DIR_NAME = "coil_thumbnail_cache"
        const val SHARE_DIR_NAME = "shared_media"
    }
}

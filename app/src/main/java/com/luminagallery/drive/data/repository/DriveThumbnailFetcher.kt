package com.luminagallery.drive.data.repository

import androidx.compose.runtime.Immutable
import androidx.room.withTransaction
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import com.luminagallery.drive.data.local.GalleryDatabase
import com.luminagallery.drive.data.remote.DriveApiService
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.remote.auth.DriveAuthInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Self-healing, disk-cached Coil 3 request model for Google Drive thumbnails & album covers.
 *
 * Solves two root causes of slow/gray Drive covers:
 *  1. Keys Coil's DiskCache purely by stable `fileId` (independent of expiring `thumbnailLink`
 *     URLs and Wi-Fi/cellular switches), so cached covers paint in < 3 ms even offline.
 *  2. Automatically detects expired/403/404/HTML `thumbnailLink` responses, performs a coalesced
 *     lightweight `files.get(fields="id,thumbnailLink")` refresh, updates Room in batched writes,
 *     and retries seamlessly (with non-Bearer fallback for pre-signed `googleusercontent.com` URLs
 *     and `?alt=media` fallback for small images) so Drive covers never stay stuck gray.
 */
@Immutable
data class DriveThumbnailRequest(
    val fileId: String,
    val thumbnailLink: String?,
    val isUnmeteredWifi: Boolean,
    val isVideo: Boolean = false,
    val sizeBytes: Long = 0L,
    val requestedMaxPx: Int = 0,
    val retryAttempt: Int = 0
)

class DriveThumbnailKeyer : Keyer<DriveThumbnailRequest> {
    override fun key(data: DriveThumbnailRequest, options: Options): String =
        if (data.retryAttempt == 0) {
            DriveConstants.stableThumbDiskKey(data.fileId)
        } else {
            "${DriveConstants.stableThumbDiskKey(data.fileId)}_r${data.retryAttempt}"
        }
}

class DriveThumbnailFetcher(
    private val data: DriveThumbnailRequest,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val okHttpClient: OkHttpClient,
    private val driveApi: DriveApiService,
    private val database: GalleryDatabase,
    private val coordinator: RefreshCoordinator
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        coroutineContext.ensureActive()
        val diskKey = DriveConstants.stableThumbDiskKey(data.fileId)

        // Step 0: Fast-path DiskCache read keyed by stable fileId (< 3 ms, offline-safe, URL-expiry-proof)
        if (data.retryAttempt == 0 && options.diskCachePolicy.readEnabled) {
            readValidDiskSnapshot(diskKey)?.let { return it }
        }

        val targetPx = resolveTargetPx(options, data)
        val initialLink = coordinator.freshLinkCache[data.fileId]?.takeIf { it.isNotBlank() }
            ?: data.thumbnailLink?.takeIf { it.isNotBlank() }

        // Step 1: Try the current known thumbnailLink first (fast path on normal cache miss)
        if (data.retryAttempt == 0 && initialLink != null) {
            val primaryUrl = DriveConstants.buildThumbnailUrl(
                thumbnailLink = initialLink,
                fileId = data.fileId,
                isUnmeteredWifi = data.isUnmeteredWifi,
                isVideo = data.isVideo,
                sizeBytes = data.sizeBytes,
                requestedMaxPx = targetPx
            )
            if (primaryUrl != null) {
                val primaryOutcome = fetchImageBytes(primaryUrl, skipAuth = false)
                if (primaryOutcome is DownloadOutcome.Success) {
                    return persistAndReturn(primaryOutcome.bytes, primaryOutcome.mimeType, diskKey)
                }
                // Some pre-signed lh3.googleusercontent.com URLs reject Bearer tokens on shared files
                if (primaryOutcome is DownloadOutcome.AuthOrExpired && isGoogleUserContentUrl(primaryUrl)) {
                    val unauthOutcome = fetchImageBytes(primaryUrl, skipAuth = true)
                    if (unauthOutcome is DownloadOutcome.Success) {
                        return persistAndReturn(unauthOutcome.bytes, unauthOutcome.mimeType, diskKey)
                    }
                }
            }
        }

        coroutineContext.ensureActive()

        // Step 2: Self-heal expired/missing thumbnailLink (check Room first, then coalesced Drive files.get)
        var healedLink = runCatching { database.driveItemDao().getThumbnailLink(data.fileId) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != initialLink }

        if (healedLink == null) {
            healedLink = coordinator.refreshThumbnailLink(data.fileId, driveApi, database)
        }

        if (!healedLink.isNullOrBlank()) {
            val freshUrl = DriveConstants.buildThumbnailUrl(
                thumbnailLink = healedLink,
                fileId = data.fileId,
                isUnmeteredWifi = data.isUnmeteredWifi,
                isVideo = data.isVideo,
                sizeBytes = data.sizeBytes,
                requestedMaxPx = targetPx
            )
            if (freshUrl != null) {
                val freshOutcome = fetchImageBytes(freshUrl, skipAuth = false)
                if (freshOutcome is DownloadOutcome.Success) {
                    return persistAndReturn(freshOutcome.bytes, freshOutcome.mimeType, diskKey)
                }
                if (freshOutcome is DownloadOutcome.AuthOrExpired && isGoogleUserContentUrl(freshUrl)) {
                    val unauthOutcome = fetchImageBytes(freshUrl, skipAuth = true)
                    if (unauthOutcome is DownloadOutcome.Success) {
                        return persistAndReturn(unauthOutcome.bytes, unauthOutcome.mimeType, diskKey)
                    }
                }
            }
        }

        coroutineContext.ensureActive()

        // Step 3: Safe direct `?alt=media` fallback for small non-video photos when Drive has no thumbnail
        if (!data.isVideo && data.sizeBytes <= DriveConstants.MAX_DIRECT_MEDIA_THUMB_BYTES) {
            val directUrl = DriveConstants.buildFullMediaUrl(data.fileId)
            val directOutcome = fetchImageBytes(
                url = directUrl,
                skipAuth = false,
                maxBytes = DriveConstants.MAX_DIRECT_MEDIA_THUMB_BYTES
            )
            if (directOutcome is DownloadOutcome.Success) {
                return persistAndReturn(directOutcome.bytes, directOutcome.mimeType, diskKey)
            }
        }

        throw IOException("Failed to fetch Google Drive thumbnail for ${data.fileId}")
    }

    private fun readValidDiskSnapshot(diskKey: String): SourceFetchResult? {
        val snapshot = runCatching { imageLoader.diskCache?.openSnapshot(diskKey) }.getOrNull()
            ?: return null
        val sizeBytes = runCatching {
            options.fileSystem.metadataOrNull(snapshot.data)?.size ?: 0L
        }.getOrDefault(0L)
        if (sizeBytes < MIN_VALID_IMAGE_BYTES) {
            runCatching { snapshot.close() }
            return null
        }
        return SourceFetchResult(
            source = ImageSource(
                file = snapshot.data,
                fileSystem = options.fileSystem,
                diskCacheKey = diskKey,
                closeable = snapshot
            ),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    private suspend fun fetchImageBytes(
        url: String,
        skipAuth: Boolean,
        maxBytes: Long = MAX_THUMBNAIL_BYTES
    ): DownloadOutcome {
        coroutineContext.ensureActive()
        return coordinator.fetchSemaphore.withPermit {
            coroutineContext.ensureActive()
            val reqBuilder = Request.Builder().url(url)
            if (skipAuth) {
                reqBuilder.header(DriveAuthInterceptor.HEADER_SKIP_AUTH, "1")
            }
            val call = okHttpClient.newCall(reqBuilder.build())
            try {
                val response = call.awaitResponse()
                response.use { res ->
                    val code = res.code
                    if (code == 401 || code == 403 || code == 404 || code == 410) {
                        return@withPermit DownloadOutcome.AuthOrExpired(code)
                    }
                    if (!res.isSuccessful) {
                        return@withPermit DownloadOutcome.Failed(code)
                    }

                    val body = res.body ?: return@withPermit DownloadOutcome.Failed(code)
                    val contentType = body.contentType()?.toString()?.lowercase().orEmpty()
                    if (isNonImageContentType(contentType)) {
                        // Google occasionally serves HTTP 200 HTML login/error pages for expired links
                        return@withPermit DownloadOutcome.AuthOrExpired(code)
                    }

                    val contentLength = body.contentLength()
                    if (contentLength > maxBytes) {
                        return@withPermit DownloadOutcome.Failed(code)
                    }

                    val bytes = readBoundedBytes(body.source(), maxBytes)
                        ?: return@withPermit DownloadOutcome.Failed(code)
                    if (bytes.size < MIN_VALID_IMAGE_BYTES || looksLikeHtml(bytes)) {
                        return@withPermit DownloadOutcome.AuthOrExpired(code)
                    }

                    val cleanMime = contentType.substringBefore(';').takeIf { it.startsWith("image/") }
                    DownloadOutcome.Success(bytes = bytes, mimeType = cleanMime)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                coroutineContext.ensureActive()
                DownloadOutcome.Failed(-1)
            }
        }
    }

    private fun persistAndReturn(
        bytes: ByteArray,
        mimeType: String?,
        diskKey: String
    ): SourceFetchResult {
        val diskCache = imageLoader.diskCache
        if (diskCache != null && options.diskCachePolicy.writeEnabled) {
            val editor = runCatching { diskCache.openEditor(diskKey) }.getOrNull()
            if (editor != null) {
                try {
                    options.fileSystem.sink(editor.data).buffer().use { sink ->
                        sink.write(bytes)
                    }
                    val snapshot = editor.commitAndOpenSnapshot()
                    if (snapshot != null) {
                        return SourceFetchResult(
                            source = ImageSource(
                                file = snapshot.data,
                                fileSystem = options.fileSystem,
                                diskCacheKey = diskKey,
                                closeable = snapshot
                            ),
                            mimeType = mimeType,
                            dataSource = DataSource.NETWORK
                        )
                    }
                } catch (_: Throwable) {
                    runCatching { editor.abort() }
                }
            }
        }

        val buffer = Buffer().write(bytes)
        return SourceFetchResult(
            source = ImageSource(
                source = buffer,
                fileSystem = options.fileSystem
            ),
            mimeType = mimeType,
            dataSource = DataSource.NETWORK
        )
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) {
                    cont.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

    private fun resolveTargetPx(options: Options, data: DriveThumbnailRequest): Int {
        if (data.requestedMaxPx > 0) return data.requestedMaxPx
        val w = (options.size.width as? Dimension.Pixels)?.px ?: 0
        val h = (options.size.height as? Dimension.Pixels)?.px ?: 0
        return max(w, h)
    }

    private sealed interface DownloadOutcome {
        data class Success(val bytes: ByteArray, val mimeType: String?) : DownloadOutcome
        data class AuthOrExpired(val code: Int) : DownloadOutcome
        data class Failed(val code: Int) : DownloadOutcome
    }

    class RefreshCoordinator {
        val fetchSemaphore = Semaphore(MAX_CONCURRENT_THUMB_FETCHES)
        val freshLinkCache = ConcurrentHashMap<String, String>()
        private val inflightRefreshes = ConcurrentHashMap<String, Deferred<String?>>()
        private val pendingDbUpdates = ConcurrentHashMap<String, String>()
        private val dbFlushScheduled = AtomicBoolean(false)
        private val dbFlushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun refreshThumbnailLink(
            fileId: String,
            driveApi: DriveApiService,
            database: GalleryDatabase
        ): String? {
            val existing = inflightRefreshes[fileId]
            if (existing != null) {
                return runCatching { existing.await() }.getOrNull()
            }

            val created = CompletableDeferred<String?>()
            val winner = inflightRefreshes.putIfAbsent(fileId, created)
            if (winner != null) {
                return runCatching { winner.await() }.getOrNull()
            }

            return try {
                val fresh = runCatching {
                    driveApi.getFile(
                        fileId = fileId,
                        fields = REFRESH_THUMB_FIELDS
                    ).thumbnailLink?.takeIf { it.isNotBlank() }
                }.getOrNull()

                if (fresh != null) {
                    if (freshLinkCache.size > MAX_MEMORY_LINK_CACHE) freshLinkCache.clear()
                    freshLinkCache[fileId] = fresh
                    scheduleBatchedRoomUpdate(fileId, fresh, database)
                }
                created.complete(fresh)
                fresh
            } catch (ce: CancellationException) {
                created.cancel(ce)
                throw ce
            } catch (t: Throwable) {
                created.complete(null)
                null
            } finally {
                inflightRefreshes.remove(fileId, created)
            }
        }

        private fun scheduleBatchedRoomUpdate(
            fileId: String,
            freshLink: String,
            database: GalleryDatabase
        ) {
            pendingDbUpdates[fileId] = freshLink
            if (dbFlushScheduled.compareAndSet(false, true)) {
                dbFlushScope.launch {
                    try {
                        delay(DB_FLUSH_DEBOUNCE_MS)
                        val snapshot = HashMap<String, String>()
                        val keys = pendingDbUpdates.keys.toList()
                        for (k in keys) {
                            pendingDbUpdates.remove(k)?.let { v -> snapshot[k] = v }
                        }
                        if (snapshot.isNotEmpty()) {
                            runCatching {
                                val dao = database.driveItemDao()
                                database.withTransaction {
                                    for ((id, link) in snapshot) {
                                        dao.updateThumbnailLink(id, link)
                                    }
                                }
                            }
                        }
                    } finally {
                        dbFlushScheduled.set(false)
                        if (pendingDbUpdates.isNotEmpty()) {
                            // Re-trigger if new entries arrived while flushing
                            val nextEntry = pendingDbUpdates.entries.firstOrNull()
                            if (nextEntry != null) {
                                scheduleBatchedRoomUpdate(nextEntry.key, nextEntry.value, database)
                            }
                        }
                    }
                }
            }
        }
    }

    class Factory(
        private val okHttpClient: OkHttpClient,
        private val driveApi: DriveApiService,
        private val database: GalleryDatabase
    ) : Fetcher.Factory<DriveThumbnailRequest> {

        private val coordinator = RefreshCoordinator()

        override fun create(
            data: DriveThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = DriveThumbnailFetcher(
            data = data,
            options = options,
            imageLoader = imageLoader,
            okHttpClient = okHttpClient,
            driveApi = driveApi,
            database = database,
            coordinator = coordinator
        )
    }

    internal companion object {
        private const val REFRESH_THUMB_FIELDS = "id,thumbnailLink"
        private const val MAX_CONCURRENT_THUMB_FETCHES = 12
        private const val MAX_THUMBNAIL_BYTES = 4L * 1024L * 1024L
        private const val MIN_VALID_IMAGE_BYTES = 32
        private const val MAX_MEMORY_LINK_CACHE = 4_000
        private const val DB_FLUSH_DEBOUNCE_MS = 400L

        internal fun isGoogleUserContentUrl(url: String): Boolean =
            url.contains("googleusercontent.com", ignoreCase = true)

        internal fun isNonImageContentType(contentTypeLower: String): Boolean {
            if (contentTypeLower.isBlank()) return false
            return contentTypeLower.startsWith("text/") ||
                contentTypeLower.contains("html") ||
                contentTypeLower.contains("json") ||
                contentTypeLower.contains("xml")
        }

        internal fun looksLikeHtml(bytes: ByteArray): Boolean {
            var idx = 0
            while (idx < bytes.size && idx < 32 && bytes[idx].toInt().toChar().isWhitespace()) {
                idx++
            }
            if (idx >= bytes.size) return true
            if (bytes[idx] != '<'.code.toByte()) return false
            val prefix = String(bytes, idx, (bytes.size - idx).coerceAtMost(16), Charsets.US_ASCII).lowercase()
            return prefix.startsWith("<!doctype") || prefix.startsWith("<html") || prefix.startsWith("<head")
        }

        private fun readBoundedBytes(source: okio.BufferedSource, maxBytes: Long): ByteArray? {
            val out = Buffer()
            val chunk = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = source.read(chunk, 0, chunk.size)
                if (read == -1) break
                total += read
                if (total > maxBytes) return null
                out.write(chunk, 0, read)
            }
            return out.readByteArray()
        }
    }
}

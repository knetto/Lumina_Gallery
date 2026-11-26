package com.luminagallery.drive.data.repository

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.room.withTransaction
import com.luminagallery.drive.data.local.GalleryDatabase
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.local.entity.AlbumCollectionEntity
import com.luminagallery.drive.data.local.entity.AlbumLayoutEntity
import com.luminagallery.drive.data.local.entity.AlbumViewPrefEntity
import com.luminagallery.drive.data.local.entity.CustomAlbumCoverEntity
import com.luminagallery.drive.data.local.entity.FavoriteItemEntity
import com.luminagallery.drive.data.local.entity.FavoriteStoryEntity
import com.luminagallery.drive.data.local.entity.HiddenAlbumEntity
import com.luminagallery.drive.data.local.entity.LockedAlbumEntity
import com.luminagallery.drive.data.local.entity.MediaAnnotationEntity
import com.luminagallery.drive.data.local.entity.PinnedRootEntity
import com.luminagallery.drive.data.local.entity.SavedPersonAnchorEntity
import com.luminagallery.drive.data.local.entity.SavedPersonMemoryEntity
import com.luminagallery.drive.data.local.entity.SavedRejectedMergeEntity
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.remote.auth.CredentialStore
import com.luminagallery.drive.data.remote.auth.ServiceAccountTokenManager
import com.luminagallery.drive.data.repository.ai.FaceClusteringEngine
import com.luminagallery.drive.data.repository.ai.PersonMemoryEngine
import com.luminagallery.drive.data.repository.ai.PersonMemoryEngine.coverBoxOrNull
import com.luminagallery.drive.data.repository.ai.PersonMemoryEngine.toFaceBox
import com.luminagallery.drive.domain.model.AlbumGridItem
import com.luminagallery.drive.domain.model.AlbumSortMode
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.MainTab
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.domain.model.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class CloudProfileSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val updatedAtEpochMs: Long = 0L,
    val preferences: CloudPreferencesPayload = CloudPreferencesPayload(),
    val pinnedDriveRoots: List<CloudPinnedRoot> = emptyList(),
    val collections: List<CloudAlbumCollection> = emptyList(),
    val layouts: List<CloudAlbumLayout> = emptyList(),
    val hiddenAlbums: List<CloudHiddenAlbum> = emptyList(),
    val lockedAlbums: List<CloudHiddenAlbum> = emptyList(),
    val driveFavoriteItemIds: List<String> = emptyList(),
    val favoriteStoryIds: List<String> = emptyList(),
    val customAlbumCovers: List<CloudCustomAlbumCover> = emptyList(),
    val mediaAnnotations: List<CloudMediaAnnotation> = emptyList(),
    val albumViewPrefs: List<CloudAlbumViewPref> = emptyList(),
    val savedPeople: List<CloudSavedPerson> = emptyList(),
    val savedPersonRejections: List<CloudRejectedPersonPair> = emptyList()
) {
    /**
     * Canonical SHA-256 fingerprint of all meaningful profile data (excluding `updatedAtEpochMs`
     * and non-semantic list ordering) so auto-backup skips echo uploads without ever dropping
     * real edits made immediately after a restore.
     */
    fun contentFingerprint(json: Json): String {
        val canonical = copy(
            updatedAtEpochMs = 0L,
            pinnedDriveRoots = pinnedDriveRoots
                .map { it.copy(pinnedAtEpochMs = 0L) }
                .sortedWith(compareBy<CloudPinnedRoot> { it.sortOrder }.thenBy { it.folderId }),
            collections = collections
                .map { it.copy(createdAtEpochMs = 0L) }
                .sortedBy { it.id },
            layouts = layouts.sortedBy { it.itemId },
            hiddenAlbums = hiddenAlbums.sortedBy { it.albumId },
            lockedAlbums = lockedAlbums.sortedBy { it.albumId },
            driveFavoriteItemIds = driveFavoriteItemIds.distinct().sorted(),
            favoriteStoryIds = favoriteStoryIds.distinct().sorted(),
            customAlbumCovers = customAlbumCovers.sortedBy { it.albumId },
            mediaAnnotations = mediaAnnotations.sortedBy { it.itemId },
            albumViewPrefs = albumViewPrefs.sortedBy { it.albumId },
            savedPeople = savedPeople
                .map { person ->
                    person.copy(
                        anchors = person.anchors.sortedWith(
                            compareBy<CloudPersonFaceAnchor> { it.itemId }
                                .thenBy { it.isExcluded }
                                .thenBy { it.bboxLeft }
                                .thenBy { it.bboxTop }
                                .thenBy { it.originGroupId }
                        )
                    )
                }
                .sortedBy { it.memoryId },
            savedPersonRejections = savedPersonRejections
                .map { pair ->
                    CloudRejectedPersonPair(
                        memoryIdA = minOf(pair.memoryIdA, pair.memoryIdB),
                        memoryIdB = maxOf(pair.memoryIdA, pair.memoryIdB)
                    )
                }
                .distinct()
                .sortedWith(compareBy<CloudRejectedPersonPair> { it.memoryIdA }.thenBy { it.memoryIdB })
        )
        val encoded = json.encodeToString(canonical).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
    }
}

@Serializable
data class CloudPreferencesPayload(
    val maxCacheSizeMb: Int = PreferencesManager.DEFAULT_CACHE_MB,
    val themeMode: String = ThemeMode.AMOLED_BLACK.name,
    val dynamicColor: Boolean = true,
    val gridColumns: Int = PreferencesManager.DEFAULT_GRID_COLUMNS,
    val albumGridColumns: Int = PreferencesManager.DEFAULT_ALBUM_GRID_COLUMNS,
    val backgroundSyncEnabled: Boolean = false,
    val albumSortMode: String = AlbumSortMode.CUSTOM.name,
    val defaultStartTab: String = MainTab.ALBUMS.name,
    val showDayLocationHeaders: Boolean = true,
    val showAlbumSidebarDefault: Boolean = true,
    val showCloudBadges: Boolean = true,
    val autoPlayVideos: Boolean = true,
    val startVideosMuted: Boolean = false,
    val loopVideos: Boolean = false,
    val showViewerFilmstrip: Boolean = true,
    val maxViewerBrightness: Boolean = false,
    val keepScreenOnViewer: Boolean = true,
    val swipeDownToDismiss: Boolean = true,
    val prefetchAdjacentMedia: Boolean = true,
    val wifiOnlyHighRes: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    val stripLocationOnShare: Boolean = true,
    val lockHiddenAlbums: Boolean = false
)

@Serializable
data class CloudPinnedRoot(
    val folderId: String,
    val displayName: String,
    val pinnedAtEpochMs: Long = 0L,
    val sortOrder: Int = 0
)

@Serializable
data class CloudAlbumCollection(
    val id: String,
    val name: String,
    val parentCollectionId: String? = null,
    val createdAtEpochMs: Long = 0L
)

@Serializable
data class CloudAlbumLayout(
    val itemId: String,
    val itemKind: String,
    val parentCollectionId: String? = null,
    val sortOrder: Int = Int.MAX_VALUE,
    val localRelativePath: String? = null,
    val localFolderName: String? = null
)

@Serializable
data class CloudHiddenAlbum(
    val albumId: String,
    val isLocal: Boolean = false,
    val localRelativePath: String? = null,
    val localFolderName: String? = null
)

@Serializable
data class CloudCustomAlbumCover(
    val albumId: String,
    val isAlbumLocal: Boolean = false,
    val albumRelativePath: String? = null,
    val albumFolderName: String? = null,
    val coverItemId: String,
    val coverIsLocal: Boolean = false,
    val coverFileName: String? = null,
    val coverRelativePath: String? = null,
    val coverThumbnailLink: String? = null,
    val coverIsVideo: Boolean = false,
    val coverSizeBytes: Long = 0L
)

@Serializable
data class CloudMediaAnnotation(
    val itemId: String,
    val isLocal: Boolean = false,
    val localFileName: String? = null,
    val localRelativePath: String? = null,
    val localSizeBytes: Long = 0L,
    val tagsCsv: String = "",
    val note: String = ""
)

@Serializable
data class CloudAlbumViewPref(
    val albumId: String,
    val isLocal: Boolean = false,
    val localRelativePath: String? = null,
    val localFolderName: String? = null,
    val sortName: String,
    val groupByName: String
)

@Serializable
data class CloudPersonFaceAnchor(
    val itemId: String,
    val isLocal: Boolean = false,
    val localFileName: String? = null,
    val localRelativePath: String? = null,
    val localSizeBytes: Long = 0L,
    val bboxLeft: Float,
    val bboxTop: Float,
    val bboxRight: Float,
    val bboxBottom: Float,
    val originGroupId: String = "",
    val isUserAssigned: Boolean = false,
    val isExcluded: Boolean = false,
    val qualityScore: Float = 0.5f
)

@Serializable
data class CloudSavedPerson(
    val memoryId: String,
    val name: String = "",
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isUserMerged: Boolean = false,
    val coverAnchor: CloudPersonFaceAnchor? = null,
    val anchors: List<CloudPersonFaceAnchor> = emptyList()
)

@Serializable
data class CloudRejectedPersonPair(
    val memoryIdA: String,
    val memoryIdB: String
)

@Serializable
data class PendingLocalCloudRules(
    val layouts: List<CloudAlbumLayout> = emptyList(),
    val hiddenAlbums: List<CloudHiddenAlbum> = emptyList(),
    val lockedAlbums: List<CloudHiddenAlbum> = emptyList(),
    val customAlbumCovers: List<CloudCustomAlbumCover> = emptyList(),
    val mediaAnnotations: List<CloudMediaAnnotation> = emptyList(),
    val albumViewPrefs: List<CloudAlbumViewPref> = emptyList(),
    val savedPeople: List<CloudSavedPerson> = emptyList()
)

@Immutable
data class CloudProfileSyncStatus(
    val isSyncing: Boolean = false,
    val lastSyncedEpochMs: Long = 0L,
    val needsScopeReconsent: Boolean = false,
    val lastMessage: String? = null,
    val lastError: String? = null
)

data class CloudProfileRestoreSummary(
    val restoredFromCloud: Boolean,
    val restoredPinnedRootsCount: Int = 0,
    val restoredCollectionsCount: Int = 0,
    val matchedLocalAlbumsCount: Int = 0,
    val needsScopeReconsent: Boolean = false
)

/**
 * Pure cross-phone matcher mapping saved local album rules (e.g. `DCIM/Vacations`,
 * `DCIM/Screenshots`) to the current phone's MediaStore `local_bucket_*` IDs:
 *  1. Exact bucket ID match (same phone or identical `/storage/emulated/0/...` path hash).
 *  2. Case-insensitive normalized relative path match (e.g. `dcim/vacations`, works across
 *     different phones / storage volumes / SD cards).
 *  3. Unique case-insensitive folder name match (e.g. Samsung `DCIM/Screenshots` <->
 *     Xiaomi `Pictures/Screenshots`, only when unambiguous on the current device).
 */
object LocalAlbumCloudMatcher {

    fun normalizeRelativePath(raw: String?): String? =
        raw?.trim()
            ?.replace('\\', '/')
            ?.trim('/')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    fun resolveTargetBucketId(
        savedBucketId: String,
        savedRelativePath: String?,
        savedFolderName: String?,
        localAlbums: List<DriveFolder>
    ): String? {
        if (localAlbums.isEmpty()) return null

        // 1. Exact bucket ID match
        localAlbums.firstOrNull { it.id == savedBucketId }?.let { return it.id }

        // 2. Exact case-insensitive relative path match (e.g. "dcim/italy 2025")
        val targetPath = normalizeRelativePath(savedRelativePath)
        if (targetPath != null) {
            localAlbums.firstOrNull { album ->
                normalizeRelativePath(album.relativePath) == targetPath
            }?.let { return it.id }
        }

        // 3. Unique folder name fallback (e.g. "screenshots" moved between DCIM/ and Pictures/)
        val targetName = savedFolderName?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: targetPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        if (targetName != null) {
            val matches = localAlbums.filter { it.name.trim().lowercase() == targetName }
            if (matches.size == 1) {
                return matches.first().id
            }
        }
        return null
    }

    /**
     * Matches a saved local photo/video (for custom album covers or tags/notes) across phones:
     *  1. Exact MediaStore ID
     *  2. Normalized relativePath + case-insensitive filename (+ sizeBytes if available)
     *  3. Unique case-insensitive filename + sizeBytes match on the current device
     */
    fun resolveTargetLocalMediaItem(
        savedItemId: String,
        savedFileName: String?,
        savedRelativePath: String?,
        savedSizeBytes: Long,
        localMedia: List<MediaItem>
    ): MediaItem? {
        if (localMedia.isEmpty()) return null
        localMedia.firstOrNull { it.id == savedItemId }?.let { return it }

        val targetName = savedFileName?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val targetPath = normalizeRelativePath(savedRelativePath)

        if (targetPath != null) {
            val pathAndNameMatches = localMedia.filter { item ->
                item.name.trim().lowercase() == targetName &&
                    normalizeRelativePath(item.storagePath ?: item.relativePath) == targetPath
            }
            if (pathAndNameMatches.size == 1) return pathAndNameMatches.first()
            if (pathAndNameMatches.size > 1 && savedSizeBytes > 0L) {
                pathAndNameMatches.firstOrNull { it.sizeBytes == savedSizeBytes }?.let { return it }
            }
        }

        val nameMatches = localMedia.filter { item ->
            item.name.trim().lowercase() == targetName &&
                (savedSizeBytes <= 0L || item.sizeBytes == savedSizeBytes)
        }
        return if (nameMatches.size == 1) nameMatches.first() else null
    }
}

/**
 * Zero-server Google Account Sync using Google Drive's hidden private `appDataFolder` space:
 *  - Saves & restores Settings, pinned Google Drive album links, nested album-in-album groups
 *    (collections + sort order for both Drive AND local DCIM folders), hidden albums, and Drive
 *    favorites under one invisible JSON file (`lumina_profile_v1.json`) tied to the Google account.
 *  - Automatically restores on fresh install / new phone sign-in and keeps matching newly copied
 *    DCIM folders into their saved album groups as they appear on the device.
 */
@OptIn(FlowPreview::class)
class CloudProfileSyncManager(
    private val database: GalleryDatabase,
    private val localMediaRepository: LocalMediaRepository,
    private val preferencesManager: PreferencesManager,
    private val credentialStore: CredentialStore,
    private val tokenManager: ServiceAccountTokenManager,
    private val networkMonitor: NetworkMonitor,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val onPinnedRootsRestored: suspend () -> Unit = {},
    private val onPreferencesRestored: suspend (UserPreferences) -> Unit = {},
    private val onSavedPeopleRestored: suspend () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val sessionGeneration = AtomicLong(0L)

    private val pinnedRootDao = database.pinnedRootDao()
    private val collectionDao = database.albumCollectionDao()
    private val extrasDao = database.galleryExtrasDao()
    private val smartDao = database.smartRecognitionDao()

    private val _status = MutableStateFlow(CloudProfileSyncStatus())
    val status: StateFlow<CloudProfileSyncStatus> = _status.asStateFlow()

    @Volatile
    private var lastSyncedFingerprint: String? = null

    @Volatile
    private var cachedAppDataFileId: String? = null

    private var autoObserverJob: Job? = null
    private var networkFlushJob: Job? = null
    private var localReconcileJob: Job? = null

    init {
        scope.launch {
            val lastMs = preferencesManager.getLastCloudProfileSyncMs()
            if (lastMs > 0L) {
                _status.value = _status.value.copy(lastSyncedEpochMs = lastMs)
            }
        }
        startLocalReconcileObserver()
    }

    /**
     * Starts background auto-sync listeners if the user is signed into Google.
     * Safe to call multiple times (idempotent).
     */
    fun startAutoSyncIfSignedIn() {
        if (!credentialStore.isGoogleSignInActive()) return
        ensureAutoBackupObserverRunning()
        ensureNetworkFlushObserverRunning()
        scope.launch {
            // Pull latest if cloud is newer (or flush dirty offline edits) without clobbering local work
            syncIfOnlineSilently()
        }
    }

    fun onSignedOut() {
        sessionGeneration.incrementAndGet()
        autoObserverJob?.cancel()
        autoObserverJob = null
        networkFlushJob?.cancel()
        networkFlushJob = null
        cachedAppDataFileId = null
        lastSyncedFingerprint = null
        _status.value = CloudProfileSyncStatus()
        scope.launch {
            preferencesManager.setLastCloudProfileSyncMs(0L)
            preferencesManager.setPendingLocalCloudRulesJson("")
            preferencesManager.setCloudProfileDirty(false)
        }
    }

    /**
     * Called immediately after Google Sign-In succeeds (fresh install, new phone, or re-consent):
     *  - If a cloud profile exists in `appDataFolder`, restores settings, linked Drive albums,
     *    and nested album collections (matching local DCIM folders by ID/path/name).
     *  - If no cloud profile exists yet on this Google account, uploads the current device setup.
     */
    suspend fun syncOnSignIn(): Result<CloudProfileRestoreSummary> = withContext(Dispatchers.IO) {
        if (!credentialStore.isGoogleSignInActive()) {
            return@withContext Result.success(CloudProfileRestoreSummary(restoredFromCloud = false))
        }
        val gen = sessionGeneration.get()
        syncMutex.withLock {
            if (sessionGeneration.get() != gen) {
                return@withLock Result.success(CloudProfileRestoreSummary(restoredFromCloud = false))
            }
            _status.value = _status.value.copy(isSyncing = true, lastError = null)
            runCatching {
                val remote = fetchRemoteProfile()
                if (sessionGeneration.get() != gen) {
                    return@runCatching CloudProfileRestoreSummary(restoredFromCloud = false)
                }
                if (remote != null) {
                    val (fileId, snapshot) = remote
                    cachedAppDataFileId = fileId
                    val hasPreSignInLocalData = pinnedRootDao.getAll().isNotEmpty() ||
                        collectionDao.getAllCollections().isNotEmpty() ||
                        collectionDao.getAllLayouts().isNotEmpty() ||
                        extrasDao.getAllCustomAlbumCovers().isNotEmpty() ||
                        extrasDao.getAllAnnotations().isNotEmpty() ||
                        smartDao.getAllSavedPersonMemories().isNotEmpty()
                    val effectiveSnapshot = if (hasPreSignInLocalData) {
                        mergeSnapshots(local = buildLocalSnapshot(), remote = snapshot, preferLocalConflicts = false)
                    } else {
                        snapshot
                    }
                    val summary = applySnapshotLocally(effectiveSnapshot)
                    val now = if (hasPreSignInLocalData) {
                        uploadSnapshotLocked(effectiveSnapshot)
                    } else {
                        val ts = snapshot.updatedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                        preferencesManager.setLastCloudProfileSyncMs(ts)
                        preferencesManager.setCloudProfileDirty(false)
                        ts
                    }
                    _status.value = CloudProfileSyncStatus(
                        isSyncing = false,
                        lastSyncedEpochMs = now,
                        needsScopeReconsent = false,
                        lastMessage = buildRestoreMessage(summary)
                    )
                    ensureAutoBackupObserverRunning()
                    ensureNetworkFlushObserverRunning()
                    summary
                } else {
                    // First time using account sync on this Google account — back up current setup!
                    val uploadedAt = uploadSnapshotLocked()
                    _status.value = CloudProfileSyncStatus(
                        isSyncing = false,
                        lastSyncedEpochMs = uploadedAt,
                        needsScopeReconsent = false,
                        lastMessage = "Backed up settings & albums to your Google account"
                    )
                    ensureAutoBackupObserverRunning()
                    ensureNetworkFlushObserverRunning()
                    CloudProfileRestoreSummary(restoredFromCloud = false)
                }
            }.onFailure { err ->
                if (sessionGeneration.get() == gen) handleSyncFailure(err)
            }
        }
    }

    /** Manually pushes current Settings, Drive links, and nested albums to Google `appDataFolder`. */
    suspend fun backupToCloudNow(): Result<Long> = withContext(Dispatchers.IO) {
        if (!credentialStore.isGoogleSignInActive()) {
            return@withContext Result.failure(IllegalStateException("Sign in with Google to back up your profile"))
        }
        if (!networkMonitor.networkState.value.isOnline) {
            preferencesManager.setCloudProfileDirty(true)
            return@withContext Result.failure(IOException("No internet connection"))
        }
        val gen = sessionGeneration.get()
        syncMutex.withLock {
            if (sessionGeneration.get() != gen) {
                return@withLock Result.failure(IllegalStateException("Signed out"))
            }
            _status.value = _status.value.copy(isSyncing = true, lastError = null)
            runCatching {
                val uploadedAt = uploadSnapshotLocked()
                if (sessionGeneration.get() == gen) {
                    _status.value = CloudProfileSyncStatus(
                        isSyncing = false,
                        lastSyncedEpochMs = uploadedAt,
                        needsScopeReconsent = false,
                        lastMessage = "Backed up to Google account"
                    )
                    ensureAutoBackupObserverRunning()
                    ensureNetworkFlushObserverRunning()
                }
                uploadedAt
            }.onFailure { err ->
                if (sessionGeneration.get() == gen) handleSyncFailure(err)
            }
        }
    }

    /** Manually downloads and applies the saved profile from Google `appDataFolder`. */
    suspend fun restoreFromCloudNow(): Result<CloudProfileRestoreSummary> = withContext(Dispatchers.IO) {
        if (!credentialStore.isGoogleSignInActive()) {
            return@withContext Result.failure(IllegalStateException("Sign in with Google to restore your profile"))
        }
        if (!networkMonitor.networkState.value.isOnline) {
            return@withContext Result.failure(IOException("No internet connection"))
        }
        val gen = sessionGeneration.get()
        syncMutex.withLock {
            if (sessionGeneration.get() != gen) {
                return@withLock Result.failure(IllegalStateException("Signed out"))
            }
            _status.value = _status.value.copy(isSyncing = true, lastError = null)
            runCatching {
                val remote = fetchRemoteProfile()
                    ?: throw IOException("No Lumina backup found on this Google account yet.")
                if (sessionGeneration.get() != gen) {
                    return@runCatching CloudProfileRestoreSummary(restoredFromCloud = false)
                }
                val (fileId, snapshot) = remote
                cachedAppDataFileId = fileId
                val summary = applySnapshotLocally(snapshot)
                val now = snapshot.updatedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                preferencesManager.setLastCloudProfileSyncMs(now)
                preferencesManager.setCloudProfileDirty(false)
                _status.value = CloudProfileSyncStatus(
                    isSyncing = false,
                    lastSyncedEpochMs = now,
                    needsScopeReconsent = false,
                    lastMessage = buildRestoreMessage(summary)
                )
                ensureAutoBackupObserverRunning()
                ensureNetworkFlushObserverRunning()
                summary
            }.onFailure { err ->
                if (sessionGeneration.get() == gen) handleSyncFailure(err)
            }
        }
    }

    /** Exports the complete profile (settings, Drive links, nested albums, covers, tags) to a local JSON file. */
    suspend fun exportProfileToUri(context: Context, uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot = buildLocalSnapshot()
                val raw = json.encodeToString(snapshot)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(raw.toByteArray(Charsets.UTF_8))
                } ?: throw IOException("Unable to open destination file for export")
                Unit
            }
        }

    /** Imports a previously exported local JSON backup file and applies it across Room & DataStore. */
    suspend fun importProfileFromUri(context: Context, uri: Uri): Result<CloudProfileRestoreSummary> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw IOException("Unable to read selected backup file")
                    val snapshot = json.decodeFromString<CloudProfileSnapshot>(raw)
                    applySnapshotLocally(snapshot)
                }
            }
        }

    private suspend fun syncIfOnlineSilently() {
        if (!credentialStore.isGoogleSignInActive() || !networkMonitor.networkState.value.isOnline) return
        val gen = sessionGeneration.get()
        syncMutex.withLock {
            if (sessionGeneration.get() != gen) return@withLock
            runCatching {
                val localLastSync = preferencesManager.getLastCloudProfileSyncMs()
                val isDirty = preferencesManager.getCloudProfileDirty()
                val remote = fetchRemoteProfile()
                if (sessionGeneration.get() != gen) return@runCatching

                if (remote != null) {
                    val (fileId, snapshot) = remote
                    cachedAppDataFileId = fileId
                    val isFreshInstall = localLastSync == 0L &&
                        pinnedRootDao.getAll().isEmpty() &&
                        collectionDao.getAllCollections().isEmpty() &&
                        collectionDao.getAllLayouts().isEmpty()

                    val remoteAdvanced = snapshot.updatedAtEpochMs > localLastSync + 2_000L
                    if (isDirty && remoteAdvanced && !isFreshInstall) {
                        // Both this device (offline) and another device changed — merge instead of wiping local edits!
                        val merged = mergeSnapshots(
                            local = buildLocalSnapshot(),
                            remote = snapshot,
                            preferLocalConflicts = true
                        )
                        val summary = applySnapshotLocally(merged)
                        val uploadedAt = uploadSnapshotLocked(merged)
                        _status.value = _status.value.copy(
                            isSyncing = false,
                            lastSyncedEpochMs = uploadedAt,
                            needsScopeReconsent = false,
                            lastMessage = buildRestoreMessage(summary),
                            lastError = null
                        )
                        if (summary.restoredPinnedRootsCount > 0) {
                            scope.launch { runCatching { onPinnedRootsRestored() } }
                        }
                    } else if (isFreshInstall || remoteAdvanced) {
                        val summary = applySnapshotLocally(snapshot)
                        preferencesManager.setLastCloudProfileSyncMs(snapshot.updatedAtEpochMs)
                        preferencesManager.setCloudProfileDirty(false)
                        _status.value = _status.value.copy(
                            isSyncing = false,
                            lastSyncedEpochMs = snapshot.updatedAtEpochMs,
                            needsScopeReconsent = false,
                            lastMessage = buildRestoreMessage(summary),
                            lastError = null
                        )
                        if (summary.restoredPinnedRootsCount > 0) {
                            scope.launch { runCatching { onPinnedRootsRestored() } }
                        }
                    } else if (isDirty) {
                        // Offline edits were made on this phone while remote didn't advance — push them now!
                        val uploadedAt = uploadSnapshotLocked()
                        _status.value = _status.value.copy(
                            isSyncing = false,
                            lastSyncedEpochMs = uploadedAt,
                            needsScopeReconsent = false,
                            lastError = null
                        )
                    } else {
                        if (localLastSync == 0L) {
                            preferencesManager.setLastCloudProfileSyncMs(snapshot.updatedAtEpochMs)
                        }
                        lastSyncedFingerprint = buildLocalSnapshot().contentFingerprint(json)
                        _status.value = _status.value.copy(
                            lastSyncedEpochMs = snapshot.updatedAtEpochMs.coerceAtLeast(localLastSync),
                            needsScopeReconsent = false
                        )
                    }
                } else if (localLastSync == 0L || isDirty) {
                    // Signed in previously before this feature existed — seed initial backup automatically!
                    val uploadedAt = uploadSnapshotLocked()
                    _status.value = _status.value.copy(
                        isSyncing = false,
                        lastSyncedEpochMs = uploadedAt,
                        needsScopeReconsent = false,
                        lastError = null
                    )
                }
            }.onFailure { err ->
                if (sessionGeneration.get() == gen && isInsufficientScopeError(err)) {
                    tokenManager.markAppDataScopeMissing()
                    _status.value = _status.value.copy(
                        isSyncing = false,
                        needsScopeReconsent = true
                    )
                }
            }
        }
    }

    private fun ensureAutoBackupObserverRunning() {
        if (autoObserverJob?.isActive == true) return
        autoObserverJob = scope.launch {
            val changeTrigger = combine(
                preferencesManager.preferencesFlow
                    .map { it.toCloudPayload() }
                    .distinctUntilChanged(),
                pinnedRootDao.observeAll(),
                collectionDao.observeAllCollections(),
                collectionDao.observeAllLayouts(),
                combine(
                    extrasDao.observeHiddenAlbumIds(),
                    extrasDao.observeFavoriteItemIds(),
                    extrasDao.observeFavoriteStoryIds(),
                    extrasDao.observeCustomAlbumCovers(),
                    combine(
                        extrasDao.observeAllAnnotations(),
                        extrasDao.observeAllAlbumViewPrefs(),
                        extrasDao.observeLockedAlbumIds(),
                        smartDao.observeAllSavedPersonMemories(),
                        smartDao.observeAllRejectedMerges()
                    ) { _, _, _, _, _ -> Unit }
                ) { _, _, _, _, _ -> Unit }
            ) { _, _, _, _, _ -> Unit }

            changeTrigger
                .drop(1) // Skip initial emission on subscription
                .debounce(AUTO_BACKUP_DEBOUNCE_MS)
                .collect {
                    if (!credentialStore.isGoogleSignInActive()) return@collect
                    val gen = sessionGeneration.get()

                    val candidate = runCatching { buildLocalSnapshot() }.getOrNull() ?: return@collect
                    val candidateFp = candidate.contentFingerprint(json)
                    if (candidateFp == lastSyncedFingerprint) return@collect

                    if (!networkMonitor.networkState.value.isOnline || _status.value.needsScopeReconsent) {
                        preferencesManager.setCloudProfileDirty(true)
                        return@collect
                    }

                    syncMutex.withLock {
                        if (sessionGeneration.get() != gen) return@withLock
                        val freshSnapshot = buildLocalSnapshot()
                        if (freshSnapshot.contentFingerprint(json) == lastSyncedFingerprint) {
                            preferencesManager.setCloudProfileDirty(false)
                            return@withLock
                        }
                        runCatching {
                            val uploadedAt = uploadSnapshotLocked(freshSnapshot)
                            if (sessionGeneration.get() == gen) {
                                _status.value = _status.value.copy(
                                    isSyncing = false,
                                    lastSyncedEpochMs = uploadedAt,
                                    needsScopeReconsent = false,
                                    lastError = null
                                )
                            }
                        }.onFailure { err ->
                            preferencesManager.setCloudProfileDirty(true)
                            if (sessionGeneration.get() == gen && isInsufficientScopeError(err)) {
                                tokenManager.markAppDataScopeMissing()
                                _status.value = _status.value.copy(
                                    isSyncing = false,
                                    needsScopeReconsent = true
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * Automatically flushes edits made while offline the moment network connectivity returns.
     */
    private fun ensureNetworkFlushObserverRunning() {
        if (networkFlushJob?.isActive == true) return
        networkFlushJob = scope.launch {
            networkMonitor.networkState
                .map { it.isOnline }
                .distinctUntilChanged()
                .collect { isOnline ->
                    if (!isOnline) return@collect
                    if (!credentialStore.isGoogleSignInActive()) return@collect
                    if (_status.value.needsScopeReconsent) return@collect
                    if (!preferencesManager.getCloudProfileDirty()) return@collect

                    val gen = sessionGeneration.get()
                    syncMutex.withLock {
                        if (sessionGeneration.get() != gen) return@withLock
                        if (!preferencesManager.getCloudProfileDirty()) return@withLock
                        runCatching {
                            val uploadedAt = uploadSnapshotLocked()
                            if (sessionGeneration.get() == gen) {
                                _status.value = _status.value.copy(
                                    isSyncing = false,
                                    lastSyncedEpochMs = uploadedAt,
                                    needsScopeReconsent = false,
                                    lastError = null
                                )
                            }
                        }.onFailure { err ->
                            if (sessionGeneration.get() == gen && isInsufficientScopeError(err)) {
                                tokenManager.markAppDataScopeMissing()
                                _status.value = _status.value.copy(
                                    isSyncing = false,
                                    needsScopeReconsent = true
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * Watches local DCIM/Pictures albums so if the user signs into Google on a brand-new phone
     * BEFORE copying their DCIM folders (or before granting media permission), those folders
     * still slot straight into their saved album-in-album groups the moment they appear!
     */
    private fun startLocalReconcileObserver() {
        if (localReconcileJob?.isActive == true) return
        localReconcileJob = scope.launch {
            localMediaRepository.observeLocalAlbums()
                .debounce(600L)
                .collect { localAlbums ->
                    if (localAlbums.isEmpty()) return@collect
                    val rawPending = preferencesManager.getPendingLocalCloudRulesJson()
                    if (rawPending.isBlank()) return@collect
                    val pending = runCatching {
                        json.decodeFromString<PendingLocalCloudRules>(rawPending)
                    }.getOrNull() ?: return@collect
                    if (pending.layouts.isEmpty() &&
                        pending.hiddenAlbums.isEmpty() &&
                        pending.lockedAlbums.isEmpty() &&
                        pending.customAlbumCovers.isEmpty() &&
                        pending.mediaAnnotations.isEmpty() &&
                        pending.albumViewPrefs.isEmpty() &&
                        pending.savedPeople.isEmpty()
                    ) return@collect

                    reconcilePendingLocalRules(pending, localAlbums)
                }
        }
    }

    private suspend fun reconcilePendingLocalRules(
        pending: PendingLocalCloudRules,
        localAlbums: List<DriveFolder>
    ) {
        val existingCollectionIds = collectionDao.getAllCollections().mapTo(HashSet()) { it.id }
        val existingLayoutIds = collectionDao.getAllLayouts().mapTo(HashSet()) { it.itemId }
        val existingHiddenIds = extrasDao.getAllHiddenAlbumIds().toHashSet()
        val existingLockedIds = extrasDao.getAllLockedAlbumIds().toHashSet()
        val existingCoverAlbumIds = extrasDao.getAllCustomAlbumCovers().mapTo(HashSet()) { it.albumId }
        val existingAnnotationIds = extrasDao.getAllAnnotations().mapTo(HashSet()) { it.itemId }
        val existingViewPrefIds = extrasDao.getAllAlbumViewPrefs().mapTo(HashSet()) { it.albumId }
        val localMedia = localMediaRepository.getCachedAllMedia()

        val layoutsToInsert = mutableListOf<AlbumLayoutEntity>()
        for (rule in pending.layouts) {
            val parentId = rule.parentCollectionId
            if (parentId != null && parentId !in existingCollectionIds) continue
            val matchedBucketId = LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = rule.itemId,
                savedRelativePath = rule.localRelativePath,
                savedFolderName = rule.localFolderName,
                localAlbums = localAlbums
            ) ?: continue
            if (matchedBucketId !in existingLayoutIds) {
                layoutsToInsert.add(
                    AlbumLayoutEntity(
                        itemId = matchedBucketId,
                        itemKind = AlbumGridItem.KIND_LOCAL,
                        parentCollectionId = parentId,
                        sortOrder = rule.sortOrder
                    )
                )
                existingLayoutIds.add(matchedBucketId)
            }
        }

        val hiddenToInsert = mutableListOf<HiddenAlbumEntity>()
        val now = System.currentTimeMillis()
        for (rule in pending.hiddenAlbums) {
            val matchedBucketId = LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = rule.albumId,
                savedRelativePath = rule.localRelativePath,
                savedFolderName = rule.localFolderName,
                localAlbums = localAlbums
            ) ?: continue
            if (matchedBucketId !in existingHiddenIds) {
                hiddenToInsert.add(HiddenAlbumEntity(albumId = matchedBucketId, hiddenAtEpochMs = now))
                existingHiddenIds.add(matchedBucketId)
            }
        }

        val lockedToInsert = mutableListOf<LockedAlbumEntity>()
        for (rule in pending.lockedAlbums) {
            val matchedBucketId = LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = rule.albumId,
                savedRelativePath = rule.localRelativePath,
                savedFolderName = rule.localFolderName,
                localAlbums = localAlbums
            ) ?: continue
            if (matchedBucketId !in existingLockedIds) {
                lockedToInsert.add(LockedAlbumEntity(albumId = matchedBucketId, lockedAtEpochMs = now))
                existingLockedIds.add(matchedBucketId)
            }
        }

        val coversToInsert = mutableListOf<CustomAlbumCoverEntity>()
        for (rule in pending.customAlbumCovers) {
            val targetAlbumId = if (rule.isAlbumLocal) {
                LocalAlbumCloudMatcher.resolveTargetBucketId(
                    savedBucketId = rule.albumId,
                    savedRelativePath = rule.albumRelativePath,
                    savedFolderName = rule.albumFolderName,
                    localAlbums = localAlbums
                ) ?: continue
            } else {
                rule.albumId
            }
            if (targetAlbumId in existingCoverAlbumIds) continue
            if (rule.coverIsLocal) {
                val matchedMedia = LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
                    savedItemId = rule.coverItemId,
                    savedFileName = rule.coverFileName,
                    savedRelativePath = rule.coverRelativePath,
                    savedSizeBytes = rule.coverSizeBytes,
                    localMedia = localMedia
                ) ?: continue
                coversToInsert.add(
                    CustomAlbumCoverEntity(
                        albumId = targetAlbumId,
                        coverItemId = matchedMedia.id,
                        coverThumbnailLink = matchedMedia.localContentUri,
                        isLocal = true,
                        isVideo = matchedMedia.isVideo,
                        sizeBytes = matchedMedia.sizeBytes,
                        updatedAtEpochMs = now
                    )
                )
                existingCoverAlbumIds.add(targetAlbumId)
            } else {
                coversToInsert.add(
                    CustomAlbumCoverEntity(
                        albumId = targetAlbumId,
                        coverItemId = rule.coverItemId,
                        coverThumbnailLink = rule.coverThumbnailLink,
                        isLocal = false,
                        isVideo = rule.coverIsVideo,
                        sizeBytes = rule.coverSizeBytes,
                        updatedAtEpochMs = now
                    )
                )
                existingCoverAlbumIds.add(targetAlbumId)
            }
        }

        val annotationsToInsert = mutableListOf<MediaAnnotationEntity>()
        for (rule in pending.mediaAnnotations) {
            val matchedMedia = LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
                savedItemId = rule.itemId,
                savedFileName = rule.localFileName,
                savedRelativePath = rule.localRelativePath,
                savedSizeBytes = rule.localSizeBytes,
                localMedia = localMedia
            ) ?: continue
            if (matchedMedia.id !in existingAnnotationIds) {
                annotationsToInsert.add(
                    MediaAnnotationEntity(
                        itemId = matchedMedia.id,
                        tagsCsv = rule.tagsCsv,
                        note = rule.note,
                        updatedAtEpochMs = now
                    )
                )
                existingAnnotationIds.add(matchedMedia.id)
            }
        }

        val viewPrefsToInsert = mutableListOf<AlbumViewPrefEntity>()
        for (rule in pending.albumViewPrefs) {
            val matchedBucket = LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = rule.albumId,
                savedRelativePath = rule.localRelativePath,
                savedFolderName = rule.localFolderName,
                localAlbums = localAlbums
            ) ?: continue
            if (matchedBucket !in existingViewPrefIds) {
                viewPrefsToInsert.add(
                    AlbumViewPrefEntity(
                        albumId = matchedBucket,
                        sortName = rule.sortName,
                        groupByName = rule.groupByName,
                        updatedAtEpochMs = now
                    )
                )
                existingViewPrefIds.add(matchedBucket)
            }
        }

        val savedAnchorsToInsert = mutableListOf<SavedPersonAnchorEntity>()
        val savedCoversToUpdate = mutableListOf<SavedPersonMemoryEntity>()
        if (pending.savedPeople.isNotEmpty() && localMedia.isNotEmpty()) {
            val existingMemoriesById = smartDao.getAllSavedPersonMemories().associateBy { it.memoryId }
            val existingAnchorsByMemory = smartDao.getAllSavedPersonAnchors().groupBy { it.memoryId }
            for (savedPerson in pending.savedPeople) {
                val mem = existingMemoriesById[savedPerson.memoryId] ?: continue
                val existingForMem = existingAnchorsByMemory[savedPerson.memoryId].orEmpty()
                val existingByItem = existingForMem.groupBy { it.itemId }

                for (anchor in savedPerson.anchors) {
                    if (!anchor.isLocal && !anchor.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)) continue
                    val matchedMedia = LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
                        savedItemId = anchor.itemId,
                        savedFileName = anchor.localFileName,
                        savedRelativePath = anchor.localRelativePath,
                        savedSizeBytes = anchor.localSizeBytes,
                        localMedia = localMedia
                    ) ?: continue
                    val box = PersonMemoryEngine.FaceBox(anchor.bboxLeft, anchor.bboxTop, anchor.bboxRight, anchor.bboxBottom)
                    val alreadyHas = existingByItem[matchedMedia.id].orEmpty().any { ex ->
                        ex.isExcluded == anchor.isExcluded &&
                            (box.isLegacyWholePhotoPlaceholder ||
                                PersonMemoryEngine.isSameFaceLocation(box, ex.toFaceBox(), singleFaceInPhoto = false))
                    }
                    if (!alreadyHas) {
                        savedAnchorsToInsert.add(
                            SavedPersonAnchorEntity(
                                anchorId = "anc_${UUID.randomUUID().toString().take(16)}",
                                memoryId = savedPerson.memoryId,
                                itemId = matchedMedia.id,
                                bboxLeft = anchor.bboxLeft,
                                bboxTop = anchor.bboxTop,
                                bboxRight = anchor.bboxRight,
                                bboxBottom = anchor.bboxBottom,
                                originGroupId = anchor.originGroupId.ifBlank { "cloud_restored" },
                                isUserAssigned = anchor.isUserAssigned,
                                isExcluded = anchor.isExcluded,
                                qualityScore = anchor.qualityScore,
                                updatedAtEpochMs = now
                            )
                        )
                    }
                }
                val coverRule = savedPerson.coverAnchor
                if (mem.coverItemId.isNullOrBlank() && coverRule != null &&
                    (coverRule.isLocal || coverRule.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX))
                ) {
                    val matchedCover = LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
                        savedItemId = coverRule.itemId,
                        savedFileName = coverRule.localFileName,
                        savedRelativePath = coverRule.localRelativePath,
                        savedSizeBytes = coverRule.localSizeBytes,
                        localMedia = localMedia
                    )
                    if (matchedCover != null) {
                        savedCoversToUpdate.add(
                            mem.copy(
                                coverItemId = matchedCover.id,
                                coverBboxLeft = coverRule.bboxLeft,
                                coverBboxTop = coverRule.bboxTop,
                                coverBboxRight = coverRule.bboxRight,
                                coverBboxBottom = coverRule.bboxBottom,
                                updatedAtEpochMs = now
                            )
                        )
                    }
                }
            }
        }

        if (layoutsToInsert.isNotEmpty() ||
            hiddenToInsert.isNotEmpty() ||
            lockedToInsert.isNotEmpty() ||
            coversToInsert.isNotEmpty() ||
            annotationsToInsert.isNotEmpty() ||
            viewPrefsToInsert.isNotEmpty() ||
            savedAnchorsToInsert.isNotEmpty() ||
            savedCoversToUpdate.isNotEmpty()
        ) {
            val wasClean = !preferencesManager.getCloudProfileDirty()
            database.withTransaction {
                if (layoutsToInsert.isNotEmpty()) collectionDao.upsertLayouts(layoutsToInsert)
                if (hiddenToInsert.isNotEmpty()) extrasDao.upsertHiddenAlbums(hiddenToInsert)
                if (lockedToInsert.isNotEmpty()) extrasDao.upsertLockedAlbums(lockedToInsert)
                if (coversToInsert.isNotEmpty()) extrasDao.upsertCustomAlbumCovers(coversToInsert)
                if (annotationsToInsert.isNotEmpty()) extrasDao.upsertAnnotations(annotationsToInsert)
                if (viewPrefsToInsert.isNotEmpty()) extrasDao.upsertAlbumViewPrefs(viewPrefsToInsert)
                if (savedCoversToUpdate.isNotEmpty()) smartDao.upsertSavedPersonMemories(savedCoversToUpdate)
                if (savedAnchorsToInsert.isNotEmpty()) smartDao.upsertSavedPersonAnchors(savedAnchorsToInsert)
            }
            if (savedAnchorsToInsert.isNotEmpty() || savedCoversToUpdate.isNotEmpty()) {
                runCatching { onSavedPeopleRestored() }
            }
            if (wasClean) {
                lastSyncedFingerprint = runCatching {
                    buildLocalSnapshot().contentFingerprint(json)
                }.getOrNull()
            }
        }
    }

    private suspend fun getCurrentLocalAlbums(): List<DriveFolder> {
        val cached = localMediaRepository.getCachedLocalAlbums()
        if (cached.isNotEmpty() || !localMediaRepository.hasMediaPermission()) {
            return cached
        }
        return withTimeoutOrNull(2_500L) {
            localMediaRepository.observeLocalAlbums().first()
        }.orEmpty()
    }

    private suspend fun buildLocalSnapshot(): CloudProfileSnapshot {
        val prefs = preferencesManager.getCurrentPreferences()
        val pinnedRoots = pinnedRootDao.getAll()
        val collections = collectionDao.getAllCollections()
        val layouts = collectionDao.getAllLayouts()
        val hiddenIds = extrasDao.getAllHiddenAlbumIds()
        val lockedIds = extrasDao.getAllLockedAlbumIds()
        val favIds = extrasDao.getAllFavoriteItemIds()
        val localAlbums = getCurrentLocalAlbums()
        val localById = localAlbums.associateBy { it.id }

        val pendingRaw = preferencesManager.getPendingLocalCloudRulesJson()
        val previousPending = if (pendingRaw.isBlank()) {
            PendingLocalCloudRules()
        } else {
            runCatching { json.decodeFromString<PendingLocalCloudRules>(pendingRaw) }
                .getOrDefault(PendingLocalCloudRules())
        }
        val prevLayoutMetaById = previousPending.layouts.associateBy { it.itemId }
        val prevHiddenMetaById = previousPending.hiddenAlbums.associateBy { it.albumId }
        val prevLockedMetaById = previousPending.lockedAlbums.associateBy { it.albumId }

        val validCollectionIds = collections.mapTo(HashSet()) { it.id }

        val cloudLayouts = mutableListOf<CloudAlbumLayout>()
        val coveredLocalPaths = HashSet<String>()
        val coveredLayoutIds = HashSet<String>()

        for (layout in layouts) {
            val isLocal = layout.itemKind == AlbumGridItem.KIND_LOCAL ||
                LocalMediaRepository.isLocalFolderId(layout.itemId)
            val localAlbum = if (isLocal) localById[layout.itemId] else null
            val fallbackMeta = if (isLocal) prevLayoutMetaById[layout.itemId] else null
            val relPath = localAlbum?.relativePath ?: fallbackMeta?.localRelativePath
            val folderName = localAlbum?.name ?: fallbackMeta?.localFolderName

            cloudLayouts.add(
                CloudAlbumLayout(
                    itemId = layout.itemId,
                    itemKind = layout.itemKind,
                    parentCollectionId = layout.parentCollectionId,
                    sortOrder = layout.sortOrder,
                    localRelativePath = relPath,
                    localFolderName = folderName
                )
            )
            coveredLayoutIds.add(layout.itemId)
            LocalAlbumCloudMatcher.normalizeRelativePath(relPath)?.let { coveredLocalPaths.add(it) }
        }

        // Preserve local DCIM rules saved from another phone whose folders aren't on this device yet
        for (oldRule in previousPending.layouts) {
            if (oldRule.itemId in coveredLayoutIds) continue
            val normPath = LocalAlbumCloudMatcher.normalizeRelativePath(oldRule.localRelativePath)
            if (normPath != null && normPath in coveredLocalPaths) continue
            if (oldRule.parentCollectionId != null && oldRule.parentCollectionId !in validCollectionIds) continue
            // If this rule actually resolves to an existing local album on this phone that the user
            // explicitly un-grouped/removed, skip preserving the stale rule.
            val matchedHere = LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = oldRule.itemId,
                savedRelativePath = oldRule.localRelativePath,
                savedFolderName = oldRule.localFolderName,
                localAlbums = localAlbums
            )
            if (matchedHere != null) continue
            cloudLayouts.add(oldRule)
        }

        val cloudHidden = mutableListOf<CloudHiddenAlbum>()
        val coveredHiddenIds = HashSet<String>()
        val coveredHiddenPaths = HashSet<String>()
        for (id in hiddenIds) {
            val isLocal = LocalMediaRepository.isLocalFolderId(id)
            val localAlbum = if (isLocal) localById[id] else null
            val fallbackMeta = if (isLocal) prevHiddenMetaById[id] else null
            val relPath = localAlbum?.relativePath ?: fallbackMeta?.localRelativePath
            val folderName = localAlbum?.name ?: fallbackMeta?.localFolderName
            cloudHidden.add(
                CloudHiddenAlbum(
                    albumId = id,
                    isLocal = isLocal,
                    localRelativePath = relPath,
                    localFolderName = folderName
                )
            )
            coveredHiddenIds.add(id)
            LocalAlbumCloudMatcher.normalizeRelativePath(relPath)?.let { coveredHiddenPaths.add(it) }
        }
        for (oldHidden in previousPending.hiddenAlbums) {
            if (oldHidden.albumId in coveredHiddenIds) continue
            val normPath = LocalAlbumCloudMatcher.normalizeRelativePath(oldHidden.localRelativePath)
            if (normPath != null && normPath in coveredHiddenPaths) continue
            val matchedHere = LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = oldHidden.albumId,
                savedRelativePath = oldHidden.localRelativePath,
                savedFolderName = oldHidden.localFolderName,
                localAlbums = localAlbums
            )
            if (matchedHere != null) continue
            cloudHidden.add(oldHidden)
        }

        val cloudLocked = mutableListOf<CloudHiddenAlbum>()
        val coveredLockedIds = HashSet<String>()
        val coveredLockedPaths = HashSet<String>()
        for (id in lockedIds) {
            val isLocal = LocalMediaRepository.isLocalFolderId(id)
            val localAlbum = if (isLocal) localById[id] else null
            val fallbackMeta = if (isLocal) prevLockedMetaById[id] else null
            val relPath = localAlbum?.relativePath ?: fallbackMeta?.localRelativePath
            val folderName = localAlbum?.name ?: fallbackMeta?.localFolderName
            cloudLocked.add(
                CloudHiddenAlbum(
                    albumId = id,
                    isLocal = isLocal,
                    localRelativePath = relPath,
                    localFolderName = folderName
                )
            )
            coveredLockedIds.add(id)
            LocalAlbumCloudMatcher.normalizeRelativePath(relPath)?.let { coveredLockedPaths.add(it) }
        }
        for (oldLocked in previousPending.lockedAlbums) {
            if (oldLocked.albumId in coveredLockedIds) continue
            val normPath = LocalAlbumCloudMatcher.normalizeRelativePath(oldLocked.localRelativePath)
            if (normPath != null && normPath in coveredLockedPaths) continue
            val matchedHere = LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = oldLocked.albumId,
                savedRelativePath = oldLocked.localRelativePath,
                savedFolderName = oldLocked.localFolderName,
                localAlbums = localAlbums
            )
            if (matchedHere != null) continue
            cloudLocked.add(oldLocked)
        }

        val driveFavs = favIds.filterNot { it.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX) }
        val favStories = extrasDao.getAllFavoriteStoryIds()
        val localMediaList = localMediaRepository.getCachedAllMedia()
        val localMediaById = localMediaList.associateBy { it.id }

        val cloudCovers = mutableListOf<CloudCustomAlbumCover>()
        val coveredCoverAlbumIds = HashSet<String>()
        for (cover in extrasDao.getAllCustomAlbumCovers()) {
            val albumIsLocal = LocalMediaRepository.isLocalFolderId(cover.albumId)
            val albumMeta = if (albumIsLocal) localById[cover.albumId] else null
            val mediaMeta = if (cover.isLocal) localMediaById[cover.coverItemId] else null
            cloudCovers.add(
                CloudCustomAlbumCover(
                    albumId = cover.albumId,
                    isAlbumLocal = albumIsLocal,
                    albumRelativePath = albumMeta?.relativePath,
                    albumFolderName = albumMeta?.name,
                    coverItemId = cover.coverItemId,
                    coverIsLocal = cover.isLocal,
                    coverFileName = mediaMeta?.name,
                    coverRelativePath = mediaMeta?.storagePath ?: mediaMeta?.relativePath,
                    coverThumbnailLink = if (cover.isLocal) null else cover.coverThumbnailLink,
                    coverIsVideo = cover.isVideo,
                    coverSizeBytes = cover.sizeBytes
                )
            )
            coveredCoverAlbumIds.add(cover.albumId)
        }
        // Preserve unmatched custom album covers from other devices whose local album/photo isn't on this device
        for (oldCover in previousPending.customAlbumCovers) {
            if (oldCover.albumId in coveredCoverAlbumIds) continue
            val matchedAlbumHere = if (oldCover.isAlbumLocal) {
                LocalAlbumCloudMatcher.resolveTargetBucketId(
                    savedBucketId = oldCover.albumId,
                    savedRelativePath = oldCover.albumRelativePath,
                    savedFolderName = oldCover.albumFolderName,
                    localAlbums = localAlbums
                )
            } else null
            if (matchedAlbumHere != null && matchedAlbumHere in coveredCoverAlbumIds) continue
            if (matchedAlbumHere != null) continue
            cloudCovers.add(oldCover)
        }

        val cloudAnnotations = mutableListOf<CloudMediaAnnotation>()
        val coveredAnnotationIds = HashSet<String>()
        for (ann in extrasDao.getAllAnnotations()) {
            val isLocal = ann.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)
            val mediaMeta = if (isLocal) localMediaById[ann.itemId] else null
            cloudAnnotations.add(
                CloudMediaAnnotation(
                    itemId = ann.itemId,
                    isLocal = isLocal,
                    localFileName = mediaMeta?.name,
                    localRelativePath = mediaMeta?.storagePath ?: mediaMeta?.relativePath,
                    localSizeBytes = mediaMeta?.sizeBytes ?: 0L,
                    tagsCsv = ann.tagsCsv,
                    note = ann.note
                )
            )
            coveredAnnotationIds.add(ann.itemId)
        }
        // Preserve unmatched local photo notes/tags from other devices
        for (oldAnn in previousPending.mediaAnnotations) {
            if (oldAnn.itemId in coveredAnnotationIds) continue
            val matchedHere = LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
                savedItemId = oldAnn.itemId,
                savedFileName = oldAnn.localFileName,
                savedRelativePath = oldAnn.localRelativePath,
                savedSizeBytes = oldAnn.localSizeBytes,
                localMedia = localMediaList
            )
            if (matchedHere != null) continue
            cloudAnnotations.add(oldAnn)
        }

        val cloudViewPrefs = mutableListOf<CloudAlbumViewPref>()
        val coveredViewPrefIds = HashSet<String>()
        for (pref in extrasDao.getAllAlbumViewPrefs()) {
            val isLocal = LocalMediaRepository.isLocalFolderId(pref.albumId)
            val albumMeta = if (isLocal) localById[pref.albumId] else null
            cloudViewPrefs.add(
                CloudAlbumViewPref(
                    albumId = pref.albumId,
                    isLocal = isLocal,
                    localRelativePath = albumMeta?.relativePath,
                    localFolderName = albumMeta?.name,
                    sortName = pref.sortName,
                    groupByName = pref.groupByName
                )
            )
            coveredViewPrefIds.add(pref.albumId)
        }
        // Preserve unmatched local album view preferences from other devices
        for (oldPref in previousPending.albumViewPrefs) {
            if (oldPref.albumId in coveredViewPrefIds) continue
            val matchedHere = LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = oldPref.albumId,
                savedRelativePath = oldPref.localRelativePath,
                savedFolderName = oldPref.localFolderName,
                localAlbums = localAlbums
            )
            if (matchedHere != null) continue
            cloudViewPrefs.add(oldPref)
        }

        // 10. Durable saved People memories (names, user-combined groups, favorites, hidden, covers, exclusions)
        val blockedFolderIds = (hiddenIds + lockedIds).toHashSet()
        val prevPendingPeopleById = previousPending.savedPeople.associateBy { it.memoryId }
        val allSavedMemories = smartDao.getAllSavedPersonMemories()
        val anchorsByMemory = smartDao.getAllSavedPersonAnchors().groupBy { it.memoryId }
        val cloudSavedPeople = mutableListOf<CloudSavedPerson>()
        for (mem in allSavedMemories) {
            val rawAnchors = anchorsByMemory[mem.memoryId].orEmpty().filter { anc ->
                val localItem = localMediaById[anc.itemId]
                localItem == null || localItem.parentId !in blockedFolderIds
            }
            val positiveSelected = PersonMemoryEngine.selectBalancedPositiveAnchors(
                anchors = rawAnchors.filterNot { it.isExcluded },
                limit = PersonMemoryEngine.MAX_CLOUD_ANCHORS_PER_MEMORY,
                coverItemId = mem.coverItemId,
                coverBox = mem.coverBoxOrNull()
            )
            val excludedSelected = rawAnchors
                .filter { it.isExcluded }
                .sortedByDescending { it.updatedAtEpochMs }
                .take(PersonMemoryEngine.MAX_CLOUD_ANCHORS_PER_MEMORY)

            val localCloudAnchors = (positiveSelected + excludedSelected).map { anc ->
                val isLocal = anc.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)
                val mediaMeta = if (isLocal) localMediaById[anc.itemId] else null
                CloudPersonFaceAnchor(
                    itemId = anc.itemId,
                    isLocal = isLocal,
                    localFileName = mediaMeta?.name,
                    localRelativePath = mediaMeta?.storagePath ?: mediaMeta?.relativePath,
                    localSizeBytes = mediaMeta?.sizeBytes ?: 0L,
                    bboxLeft = anc.bboxLeft,
                    bboxTop = anc.bboxTop,
                    bboxRight = anc.bboxRight,
                    bboxBottom = anc.bboxBottom,
                    originGroupId = anc.originGroupId,
                    isUserAssigned = anc.isUserAssigned,
                    isExcluded = anc.isExcluded,
                    qualityScore = anc.qualityScore
                )
            }.toMutableList()

            // Preserve unmatched local anchors from another phone whose photos aren't on this device yet
            val pendingForMem = prevPendingPeopleById[mem.memoryId]
            if (pendingForMem != null) {
                val coveredIds = localCloudAnchors.mapTo(HashSet()) { it.itemId }
                for (oldAnc in pendingForMem.anchors) {
                    if (!oldAnc.isLocal && !oldAnc.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)) continue
                    if (oldAnc.itemId in coveredIds) continue
                    val matchedHere = LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
                        savedItemId = oldAnc.itemId,
                        savedFileName = oldAnc.localFileName,
                        savedRelativePath = oldAnc.localRelativePath,
                        savedSizeBytes = oldAnc.localSizeBytes,
                        localMedia = localMediaList
                    )
                    if (matchedHere != null) continue
                    if (localCloudAnchors.size < PersonMemoryEngine.MAX_CLOUD_ANCHORS_PER_MEMORY * 2) {
                        localCloudAnchors.add(oldAnc)
                    }
                }
            }

            val coverItemId = mem.coverItemId
            val coverAnchor = if (!coverItemId.isNullOrBlank()) {
                val isLocal = coverItemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)
                val mediaMeta = if (isLocal) localMediaById[coverItemId] else null
                if (mediaMeta != null && mediaMeta.parentId in blockedFolderIds) {
                    null
                } else {
                    CloudPersonFaceAnchor(
                        itemId = coverItemId,
                        isLocal = isLocal,
                        localFileName = mediaMeta?.name,
                        localRelativePath = mediaMeta?.storagePath ?: mediaMeta?.relativePath,
                        localSizeBytes = mediaMeta?.sizeBytes ?: 0L,
                        bboxLeft = mem.coverBboxLeft ?: 0f,
                        bboxTop = mem.coverBboxTop ?: 0f,
                        bboxRight = mem.coverBboxRight ?: 1f,
                        bboxBottom = mem.coverBboxBottom ?: 1f,
                        originGroupId = "cover",
                        isUserAssigned = true,
                        isExcluded = false,
                        qualityScore = 1f
                    )
                }
            } else {
                pendingForMem?.coverAnchor
            }

            cloudSavedPeople.add(
                CloudSavedPerson(
                    memoryId = mem.memoryId,
                    name = mem.name,
                    isFavorite = mem.isFavorite,
                    isHidden = mem.isHidden,
                    isUserMerged = mem.isUserMerged,
                    coverAnchor = coverAnchor,
                    anchors = localCloudAnchors
                )
            )
        }
        val validMemIds = cloudSavedPeople.mapTo(HashSet()) { it.memoryId }
        val cloudRejections = smartDao.getAllRejectedMerges()
            .filter { it.memoryIdA in validMemIds && it.memoryIdB in validMemIds }
            .map { CloudRejectedPersonPair(it.memoryIdA, it.memoryIdB) }

        return CloudProfileSnapshot(
            schemaVersion = CloudProfileSnapshot.CURRENT_SCHEMA_VERSION,
            updatedAtEpochMs = System.currentTimeMillis(),
            preferences = prefs.toCloudPayload(),
            pinnedDriveRoots = pinnedRoots.map {
                CloudPinnedRoot(
                    folderId = it.folderId,
                    displayName = it.displayName,
                    pinnedAtEpochMs = it.pinnedAtEpochMs,
                    sortOrder = it.sortOrder
                )
            },
            collections = collections.map {
                CloudAlbumCollection(
                    id = it.id,
                    name = it.name,
                    parentCollectionId = it.parentCollectionId,
                    createdAtEpochMs = it.createdAtEpochMs
                )
            },
            layouts = cloudLayouts,
            hiddenAlbums = cloudHidden,
            lockedAlbums = cloudLocked,
            driveFavoriteItemIds = driveFavs,
            favoriteStoryIds = favStories,
            customAlbumCovers = cloudCovers,
            mediaAnnotations = cloudAnnotations,
            albumViewPrefs = cloudViewPrefs,
            savedPeople = cloudSavedPeople,
            savedPersonRejections = cloudRejections
        )
    }

    private suspend fun applySnapshotLocally(snapshot: CloudProfileSnapshot): CloudProfileRestoreSummary {
        // 1. Restore settings & immediately apply runtime side effects (cache size, background sync schedule)
        val currentPrefs = preferencesManager.getCurrentPreferences()
        val restoredPrefs = snapshot.preferences.toUserPreferences(currentPrefs)
        preferencesManager.applyCloudSyncedPreferences(restoredPrefs)
        runCatching { onPreferencesRestored(restoredPrefs) }

        val localAlbums = getCurrentLocalAlbums()
        val localMedia = localMediaRepository.getCachedAllMedia()
        val now = System.currentTimeMillis()
        var restoredRootsCount = 0
        var matchedLocalCount = 0

        val localLayoutRules = mutableListOf<CloudAlbumLayout>()
        val localHiddenRules = mutableListOf<CloudHiddenAlbum>()
        val localLockedRules = mutableListOf<CloudHiddenAlbum>()
        val localCoverRules = mutableListOf<CloudCustomAlbumCover>()
        val localAnnotationRules = mutableListOf<CloudMediaAnnotation>()
        val localViewPrefRules = mutableListOf<CloudAlbumViewPref>()
        val localSavedPeopleRules = mutableListOf<CloudSavedPerson>()
        val hasSavedPeopleSection = snapshot.schemaVersion >= 3 ||
            snapshot.savedPeople.isNotEmpty() ||
            snapshot.savedPersonRejections.isNotEmpty()

        database.withTransaction {
            // 2. Authoritatively sync pinned Google Drive album links (propagates unpins too)
            pinnedRootDao.clearAll()
            for (root in snapshot.pinnedDriveRoots) {
                if (root.folderId.isBlank()) continue
                restoredRootsCount++
                pinnedRootDao.upsert(
                    PinnedRootEntity(
                        folderId = root.folderId,
                        displayName = root.displayName.ifBlank { "Google Drive Folder" },
                        pinnedAtEpochMs = root.pinnedAtEpochMs.takeIf { it > 0L } ?: now,
                        sortOrder = root.sortOrder
                    )
                )
            }

            // 3. Authoritatively sync album-in-album collections & custom ordering (propagates ungroups too)
            collectionDao.clearAllCollections()
            collectionDao.clearAllLayouts()

            if (snapshot.collections.isNotEmpty()) {
                collectionDao.upsertCollections(
                    snapshot.collections.map { col ->
                        AlbumCollectionEntity(
                            id = col.id,
                            name = col.name,
                            parentCollectionId = col.parentCollectionId,
                            createdAtEpochMs = col.createdAtEpochMs.takeIf { it > 0L } ?: now
                        )
                    }
                )
            }

            val resolvedLayouts = LinkedHashMap<String, AlbumLayoutEntity>()
            for (layout in snapshot.layouts) {
                val isLocal = layout.itemKind == AlbumGridItem.KIND_LOCAL ||
                    LocalMediaRepository.isLocalFolderId(layout.itemId)
                if (isLocal) {
                    localLayoutRules.add(layout)
                    val matchedBucket = LocalAlbumCloudMatcher.resolveTargetBucketId(
                        savedBucketId = layout.itemId,
                        savedRelativePath = layout.localRelativePath,
                        savedFolderName = layout.localFolderName,
                        localAlbums = localAlbums
                    )
                    if (matchedBucket != null) {
                        matchedLocalCount++
                        resolvedLayouts[matchedBucket] = AlbumLayoutEntity(
                            itemId = matchedBucket,
                            itemKind = AlbumGridItem.KIND_LOCAL,
                            parentCollectionId = layout.parentCollectionId,
                            sortOrder = layout.sortOrder
                        )
                    }
                } else {
                    resolvedLayouts[layout.itemId] = AlbumLayoutEntity(
                        itemId = layout.itemId,
                        itemKind = layout.itemKind,
                        parentCollectionId = layout.parentCollectionId,
                        sortOrder = layout.sortOrder
                    )
                }
            }
            if (resolvedLayouts.isNotEmpty()) {
                collectionDao.upsertLayouts(resolvedLayouts.values.toList())
            }

            // 4. Authoritatively sync hidden albums (Drive directly + local matched by path/name)
            extrasDao.clearAllHiddenAlbums()
            val hiddenToUpsert = mutableListOf<HiddenAlbumEntity>()
            for (hidden in snapshot.hiddenAlbums) {
                if (hidden.isLocal || LocalMediaRepository.isLocalFolderId(hidden.albumId)) {
                    localHiddenRules.add(hidden)
                    val matched = LocalAlbumCloudMatcher.resolveTargetBucketId(
                        savedBucketId = hidden.albumId,
                        savedRelativePath = hidden.localRelativePath,
                        savedFolderName = hidden.localFolderName,
                        localAlbums = localAlbums
                    )
                    if (matched != null) {
                        hiddenToUpsert.add(HiddenAlbumEntity(albumId = matched, hiddenAtEpochMs = now))
                    }
                } else {
                    hiddenToUpsert.add(HiddenAlbumEntity(albumId = hidden.albumId, hiddenAtEpochMs = now))
                }
            }
            if (hiddenToUpsert.isNotEmpty()) {
                extrasDao.upsertHiddenAlbums(hiddenToUpsert)
            }

            // 4B. Authoritatively sync locked albums while preserving any local disk-hidden `.folder` paths
            val existingLockedById = extrasDao.getAllLockedAlbums().associateBy { it.albumId }
            extrasDao.clearAllLockedAlbums()
            val lockedToUpsert = LinkedHashMap<String, LockedAlbumEntity>()
            for (locked in snapshot.lockedAlbums) {
                if (locked.isLocal || LocalMediaRepository.isLocalFolderId(locked.albumId)) {
                    localLockedRules.add(locked)
                    val matched = LocalAlbumCloudMatcher.resolveTargetBucketId(
                        savedBucketId = locked.albumId,
                        savedRelativePath = locked.localRelativePath,
                        savedFolderName = locked.localFolderName,
                        localAlbums = localAlbums
                    )
                    if (matched != null) {
                        val prev = existingLockedById[matched]
                        lockedToUpsert[matched] = prev ?: LockedAlbumEntity(albumId = matched, lockedAtEpochMs = now)
                    }
                } else {
                    val prev = existingLockedById[locked.albumId]
                    lockedToUpsert[locked.albumId] = prev ?: LockedAlbumEntity(albumId = locked.albumId, lockedAtEpochMs = now)
                }
            }
            // Never forget a folder that is physically hidden on this device's disk
            for ((id, entity) in existingLockedById) {
                if (entity.isHiddenOnDisk && !entity.hiddenDirPath.isNullOrBlank() && id !in lockedToUpsert) {
                    lockedToUpsert[id] = entity
                }
            }
            if (lockedToUpsert.isNotEmpty()) {
                extrasDao.upsertLockedAlbums(lockedToUpsert.values.toList())
            }

            // 5. Authoritatively sync Google Drive favorites (preserving local on-device favorites)
            extrasDao.clearDriveFavoriteItems()
            if (snapshot.driveFavoriteItemIds.isNotEmpty()) {
                extrasDao.upsertFavoriteItems(
                    snapshot.driveFavoriteItemIds.map { id ->
                        FavoriteItemEntity(itemId = id, favoritedAtEpochMs = now)
                    }
                )
            }

            // 6. Sync favorited Stories
            extrasDao.clearAllFavoriteStories()
            if (snapshot.favoriteStoryIds.isNotEmpty()) {
                extrasDao.upsertFavoriteStories(
                    snapshot.favoriteStoryIds.map { id -> FavoriteStoryEntity(id, now) }
                )
            }

            // 7. Sync custom album covers (Drive directly + local matched by path/filename)
            extrasDao.clearAllCustomAlbumCovers()
            val coversToUpsert = mutableListOf<CustomAlbumCoverEntity>()
            for (cover in snapshot.customAlbumCovers) {
                if (cover.isAlbumLocal || cover.coverIsLocal) {
                    localCoverRules.add(cover)
                }
                val targetAlbumId = if (cover.isAlbumLocal) {
                    LocalAlbumCloudMatcher.resolveTargetBucketId(
                        savedBucketId = cover.albumId,
                        savedRelativePath = cover.albumRelativePath,
                        savedFolderName = cover.albumFolderName,
                        localAlbums = localAlbums
                    ) ?: continue
                } else {
                    cover.albumId
                }
                if (cover.coverIsLocal) {
                    val matchedMedia = LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
                        savedItemId = cover.coverItemId,
                        savedFileName = cover.coverFileName,
                        savedRelativePath = cover.coverRelativePath,
                        savedSizeBytes = cover.coverSizeBytes,
                        localMedia = localMedia
                    ) ?: continue
                    coversToUpsert.add(
                        CustomAlbumCoverEntity(
                            albumId = targetAlbumId,
                            coverItemId = matchedMedia.id,
                            coverThumbnailLink = matchedMedia.localContentUri,
                            isLocal = true,
                            isVideo = matchedMedia.isVideo,
                            sizeBytes = matchedMedia.sizeBytes,
                            updatedAtEpochMs = now
                        )
                    )
                } else {
                    coversToUpsert.add(
                        CustomAlbumCoverEntity(
                            albumId = targetAlbumId,
                            coverItemId = cover.coverItemId,
                            coverThumbnailLink = cover.coverThumbnailLink,
                            isLocal = false,
                            isVideo = cover.coverIsVideo,
                            sizeBytes = cover.coverSizeBytes,
                            updatedAtEpochMs = now
                        )
                    )
                }
            }
            if (coversToUpsert.isNotEmpty()) {
                extrasDao.upsertCustomAlbumCovers(coversToUpsert)
            }

            // 8. Sync photo tags & notes (Drive directly + local matched by path/name/size)
            extrasDao.clearAllAnnotations()
            val annotationsToUpsert = mutableListOf<MediaAnnotationEntity>()
            for (ann in snapshot.mediaAnnotations) {
                if (ann.isLocal || ann.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)) {
                    localAnnotationRules.add(ann)
                    val matchedMedia = LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
                        savedItemId = ann.itemId,
                        savedFileName = ann.localFileName,
                        savedRelativePath = ann.localRelativePath,
                        savedSizeBytes = ann.localSizeBytes,
                        localMedia = localMedia
                    ) ?: continue
                    annotationsToUpsert.add(
                        MediaAnnotationEntity(
                            itemId = matchedMedia.id,
                            tagsCsv = ann.tagsCsv,
                            note = ann.note,
                            updatedAtEpochMs = now
                        )
                    )
                } else {
                    annotationsToUpsert.add(
                        MediaAnnotationEntity(
                            itemId = ann.itemId,
                            tagsCsv = ann.tagsCsv,
                            note = ann.note,
                            updatedAtEpochMs = now
                        )
                    )
                }
            }
            if (annotationsToUpsert.isNotEmpty()) {
                extrasDao.upsertAnnotations(annotationsToUpsert)
            }

            // 9. Sync per-album sort & grouping preferences
            extrasDao.clearAllAlbumViewPrefs()
            val prefsToUpsert = mutableListOf<AlbumViewPrefEntity>()
            for (pref in snapshot.albumViewPrefs) {
                if (pref.isLocal || LocalMediaRepository.isLocalFolderId(pref.albumId)) {
                    localViewPrefRules.add(pref)
                    val matchedBucket = LocalAlbumCloudMatcher.resolveTargetBucketId(
                        savedBucketId = pref.albumId,
                        savedRelativePath = pref.localRelativePath,
                        savedFolderName = pref.localFolderName,
                        localAlbums = localAlbums
                    ) ?: continue
                    prefsToUpsert.add(
                        AlbumViewPrefEntity(
                            albumId = matchedBucket,
                            sortName = pref.sortName,
                            groupByName = pref.groupByName,
                            updatedAtEpochMs = now
                        )
                    )
                } else {
                    prefsToUpsert.add(
                        AlbumViewPrefEntity(
                            albumId = pref.albumId,
                            sortName = pref.sortName,
                            groupByName = pref.groupByName,
                            updatedAtEpochMs = now
                        )
                    )
                }
            }
            if (prefsToUpsert.isNotEmpty()) {
                extrasDao.upsertAlbumViewPrefs(prefsToUpsert)
            }

            // 10. Sync durable saved People memories (names, combines, favorites, hidden, covers, exclusions)
            // Backward-compatible: never wipe local people memories when restoring a pre-v3 cloud backup.
            if (hasSavedPeopleSection) {
                smartDao.clearAllSavedPersonMemories()
                smartDao.clearAllSavedPersonAnchors()
                smartDao.clearAllRejectedMerges()

                val memoriesToUpsert = mutableListOf<SavedPersonMemoryEntity>()
                val anchorsToUpsert = mutableListOf<SavedPersonAnchorEntity>()
                for (person in snapshot.savedPeople) {
                    if (person.memoryId.isBlank()) continue
                    val localAnchorsForPending = person.anchors.filter {
                        it.isLocal || it.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)
                    }
                    val coverIsLocal = person.coverAnchor?.let {
                        it.isLocal || it.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)
                    } == true
                    if (localAnchorsForPending.isNotEmpty() || coverIsLocal) {
                        localSavedPeopleRules.add(
                            person.copy(
                                coverAnchor = if (coverIsLocal) person.coverAnchor else null,
                                anchors = localAnchorsForPending
                            )
                        )
                    }

                    val resolvedCoverItemId = person.coverAnchor?.let { cov ->
                        if (cov.isLocal || cov.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)) {
                            LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
                                savedItemId = cov.itemId,
                                savedFileName = cov.localFileName,
                                savedRelativePath = cov.localRelativePath,
                                savedSizeBytes = cov.localSizeBytes,
                                localMedia = localMedia
                            )?.id
                        } else {
                            cov.itemId
                        }
                    }

                    val coverRule = person.coverAnchor
                    memoriesToUpsert.add(
                        SavedPersonMemoryEntity(
                            memoryId = person.memoryId,
                            name = person.name,
                            isFavorite = person.isFavorite,
                            isHidden = person.isHidden,
                            isUserMerged = person.isUserMerged,
                            coverItemId = resolvedCoverItemId,
                            coverBboxLeft = if (resolvedCoverItemId != null && coverRule != null) coverRule.bboxLeft else null,
                            coverBboxTop = if (resolvedCoverItemId != null && coverRule != null) coverRule.bboxTop else null,
                            coverBboxRight = if (resolvedCoverItemId != null && coverRule != null) coverRule.bboxRight else null,
                            coverBboxBottom = if (resolvedCoverItemId != null && coverRule != null) coverRule.bboxBottom else null,
                            updatedAtEpochMs = now
                        )
                    )

                    for (anc in person.anchors) {
                        val resolvedItemId = if (anc.isLocal || anc.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)) {
                            LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
                                savedItemId = anc.itemId,
                                savedFileName = anc.localFileName,
                                savedRelativePath = anc.localRelativePath,
                                savedSizeBytes = anc.localSizeBytes,
                                localMedia = localMedia
                            )?.id ?: continue
                        } else {
                            anc.itemId
                        }
                        anchorsToUpsert.add(
                            SavedPersonAnchorEntity(
                                anchorId = "anc_${UUID.randomUUID().toString().take(16)}",
                                memoryId = person.memoryId,
                                itemId = resolvedItemId,
                                bboxLeft = anc.bboxLeft,
                                bboxTop = anc.bboxTop,
                                bboxRight = anc.bboxRight,
                                bboxBottom = anc.bboxBottom,
                                originGroupId = anc.originGroupId.ifBlank { "cloud_restored" },
                                isUserAssigned = anc.isUserAssigned,
                                isExcluded = anc.isExcluded,
                                qualityScore = anc.qualityScore,
                                updatedAtEpochMs = now
                            )
                        )
                    }
                }
                if (memoriesToUpsert.isNotEmpty()) {
                    smartDao.upsertSavedPersonMemories(memoriesToUpsert)
                }
                if (anchorsToUpsert.isNotEmpty()) {
                    smartDao.upsertSavedPersonAnchors(anchorsToUpsert)
                }
                val rejectionsToUpsert = snapshot.savedPersonRejections
                    .filter { it.memoryIdA.isNotBlank() && it.memoryIdB.isNotBlank() && it.memoryIdA != it.memoryIdB }
                    .map { pair ->
                        val a = minOf(pair.memoryIdA, pair.memoryIdB)
                        val b = maxOf(pair.memoryIdA, pair.memoryIdB)
                        SavedRejectedMergeEntity(
                            pairKey = FaceClusteringEngine.canonicalPairKey(a, b),
                            memoryIdA = a,
                            memoryIdB = b,
                            createdAtEpochMs = now
                        )
                    }
                if (rejectionsToUpsert.isNotEmpty()) {
                    smartDao.upsertRejectedMerges(rejectionsToUpsert)
                }
            }
        }

        if (hasSavedPeopleSection && snapshot.savedPeople.isNotEmpty()) {
            runCatching { onSavedPeopleRestored() }
        }

        // Persist local DCIM path rules so folders/photos copied later onto a new phone auto-slot into place!
        val pendingRules = PendingLocalCloudRules(
            layouts = localLayoutRules,
            hiddenAlbums = localHiddenRules,
            lockedAlbums = localLockedRules,
            customAlbumCovers = localCoverRules,
            mediaAnnotations = localAnnotationRules,
            albumViewPrefs = localViewPrefRules,
            savedPeople = localSavedPeopleRules
        )
        preferencesManager.setPendingLocalCloudRulesJson(json.encodeToString(pendingRules))

        // Record exact resulting local fingerprint so auto-backup skips echo uploads without dropping real edits
        lastSyncedFingerprint = runCatching {
            buildLocalSnapshot().contentFingerprint(json)
        }.getOrNull()

        return CloudProfileRestoreSummary(
            restoredFromCloud = true,
            restoredPinnedRootsCount = restoredRootsCount,
            restoredCollectionsCount = snapshot.collections.size,
            matchedLocalAlbumsCount = matchedLocalCount
        )
    }

    private suspend fun uploadSnapshotLocked(
        prebuiltSnapshot: CloudProfileSnapshot? = null
    ): Long {
        val snapshot = prebuiltSnapshot ?: buildLocalSnapshot()
        val payloadJson = json.encodeToString(snapshot)
        val existingId = cachedAppDataFileId ?: findRemoteProfileFileId()
        val fileId = if (!existingId.isNullOrBlank()) {
            patchRemoteProfile(existingId, payloadJson)
        } else {
            createRemoteProfile(payloadJson)
        }
        cachedAppDataFileId = fileId
        preferencesManager.setLastCloudProfileSyncMs(snapshot.updatedAtEpochMs)
        preferencesManager.setCloudProfileDirty(false)
        val refreshedPending = PendingLocalCloudRules(
            layouts = snapshot.layouts.filter {
                it.itemKind == AlbumGridItem.KIND_LOCAL || LocalMediaRepository.isLocalFolderId(it.itemId)
            },
            hiddenAlbums = snapshot.hiddenAlbums.filter {
                it.isLocal || LocalMediaRepository.isLocalFolderId(it.albumId)
            },
            lockedAlbums = snapshot.lockedAlbums.filter {
                it.isLocal || LocalMediaRepository.isLocalFolderId(it.albumId)
            },
            customAlbumCovers = snapshot.customAlbumCovers.filter { it.isAlbumLocal || it.coverIsLocal },
            mediaAnnotations = snapshot.mediaAnnotations.filter {
                it.isLocal || it.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)
            },
            albumViewPrefs = snapshot.albumViewPrefs.filter {
                it.isLocal || LocalMediaRepository.isLocalFolderId(it.albumId)
            },
            savedPeople = snapshot.savedPeople.mapNotNull { person ->
                val localAnchors = person.anchors.filter {
                    it.isLocal || it.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)
                }
                val localCover = person.coverAnchor?.takeIf {
                    it.isLocal || it.itemId.startsWith(LocalMediaRepository.LOCAL_ITEM_PREFIX)
                }
                if (localAnchors.isEmpty() && localCover == null) null
                else person.copy(coverAnchor = localCover, anchors = localAnchors)
            }
        )
        preferencesManager.setPendingLocalCloudRulesJson(json.encodeToString(refreshedPending))
        lastSyncedFingerprint = snapshot.contentFingerprint(json)
        return snapshot.updatedAtEpochMs
    }

    private fun throwForHttpFailure(code: Int, errorBody: String, fallbackMessage: String): Nothing {
        if (code == 403 && (isScopeDeniedErrorBody(errorBody) || (errorBody.isBlank() && !tokenManager.hasAppDataScope))) {
            throw InsufficientAppDataScopeException()
        }
        val apiMsg = runCatching {
            JSONObject(errorBody).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val detail = if (apiMsg != null) "$fallbackMessage (HTTP $code: $apiMsg)" else "$fallbackMessage (HTTP $code)"
        throw IOException(detail)
    }

    private fun findRemoteProfileFileId(): String? {
        val q = URLEncoder.encode("name='$PROFILE_FILE_NAME' and trashed=false", "UTF-8")
        val url = "${DriveConstants.BASE_URL}drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id,modifiedTime)&pageSize=5"
        val req = Request.Builder().url(url).get().build()
        okHttpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throwForHttpFailure(resp.code, body, "Drive appDataFolder list failed")
            val root = JSONObject(body)
            val files = root.optJSONArray("files") ?: return null
            if (files.length() == 0) return null
            return files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
        }
    }

    private fun fetchRemoteProfile(): Pair<String, CloudProfileSnapshot>? {
        val fileId = cachedAppDataFileId ?: findRemoteProfileFileId() ?: return null
        val url = "${DriveConstants.BASE_URL}drive/v3/files/$fileId?alt=media"
        val req = Request.Builder().url(url).get().build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (resp.code == 404) {
                cachedAppDataFileId = null
                return null
            }
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throwForHttpFailure(resp.code, body, "Failed to download cloud profile")
            if (body.isBlank()) return null
            val parsed = json.decodeFromString<CloudProfileSnapshot>(body)
            return fileId to parsed
        }
    }

    private fun createRemoteProfile(payloadJson: String): String {
        val boundary = "lumina_${UUID.randomUUID().toString().replace("-", "")}"
        val metadataJson = """{"name":"$PROFILE_FILE_NAME","parents":["appDataFolder"]}"""
        val multipartBody = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadataJson).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(payloadJson).append("\r\n")
            append("--").append(boundary).append("--")
        }
        val url = "${DriveConstants.BASE_URL}upload/drive/v3/files?uploadType=multipart&fields=id"
        val req = Request.Builder()
            .url(url)
            .post(multipartBody.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throwForHttpFailure(resp.code, body, "Failed to create cloud backup")
            val obj = JSONObject(body)
            return obj.optString("id").takeIf { it.isNotBlank() }
                ?: throw IOException("Drive returned empty file id for cloud backup")
        }
    }

    private fun patchRemoteProfile(fileId: String, payloadJson: String): String {
        val url = "${DriveConstants.BASE_URL}upload/drive/v3/files/$fileId?uploadType=media&fields=id"
        val req = Request.Builder()
            .url(url)
            .patch(payloadJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (resp.code == 404) {
                cachedAppDataFileId = null
                return createRemoteProfile(payloadJson)
            }
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throwForHttpFailure(resp.code, body, "Failed to update cloud backup")
            return fileId
        }
    }

    private fun handleSyncFailure(err: Throwable) {
        if (isInsufficientScopeError(err)) {
            tokenManager.markAppDataScopeMissing()
            _status.value = _status.value.copy(
                isSyncing = false,
                needsScopeReconsent = true,
                lastError = "Sign in with Google once more to enable account backup"
            )
        } else {
            _status.value = _status.value.copy(
                isSyncing = false,
                lastError = err.localizedMessage ?: "Account sync failed"
            )
        }
    }

    private fun isInsufficientScopeError(err: Throwable): Boolean =
        err is InsufficientAppDataScopeException

    private fun buildRestoreMessage(summary: CloudProfileRestoreSummary): String {
        val parts = mutableListOf<String>()
        if (summary.restoredPinnedRootsCount > 0) {
            parts.add("${summary.restoredPinnedRootsCount} Drive album${if (summary.restoredPinnedRootsCount == 1) "" else "s"}")
        }
        if (summary.restoredCollectionsCount > 0) {
            parts.add("${summary.restoredCollectionsCount} group${if (summary.restoredCollectionsCount == 1) "" else "s"}")
        }
        return if (parts.isEmpty()) {
            "Restored settings & layout from your Google account"
        } else {
            "Restored settings, ${parts.joinToString(" & ")} from Google"
        }
    }

    private class InsufficientAppDataScopeException :
        IOException("Account backup requires one-time Google Sign-In permission")

    companion object {
        private const val PROFILE_FILE_NAME = "lumina_profile_v1.json"
        private const val AUTO_BACKUP_DEBOUNCE_MS = 3_500L

        internal fun mergeSnapshots(
            local: CloudProfileSnapshot,
            remote: CloudProfileSnapshot,
            preferLocalConflicts: Boolean
        ): CloudProfileSnapshot {
            val primary = if (preferLocalConflicts) local else remote
            val secondary = if (preferLocalConflicts) remote else local

            fun <T, K> unionByKey(first: List<T>, second: List<T>, keyOf: (T) -> K): List<T> {
                val map = LinkedHashMap<K, T>(first.size + second.size)
                for (item in second) map[keyOf(item)] = item
                for (item in first) map[keyOf(item)] = item
                return map.values.toList()
            }

            val mergedPeople = run {
                val byId = LinkedHashMap<String, CloudSavedPerson>(primary.savedPeople.size + secondary.savedPeople.size)
                for (p in secondary.savedPeople) byId[p.memoryId] = p
                for (p in primary.savedPeople) {
                    val existing = byId[p.memoryId]
                    byId[p.memoryId] = if (existing == null) {
                        p
                    } else {
                        val mergedAnchors = unionByKey(p.anchors, existing.anchors) { anc ->
                            "${anc.itemId}|${anc.isExcluded}|${(anc.bboxLeft * 20).toInt()}_${(anc.bboxTop * 20).toInt()}"
                        }
                        p.copy(
                            name = p.name.ifBlank { existing.name },
                            isFavorite = p.isFavorite || existing.isFavorite,
                            isUserMerged = p.isUserMerged || existing.isUserMerged,
                            coverAnchor = p.coverAnchor ?: existing.coverAnchor,
                            anchors = mergedAnchors
                        )
                    }
                }
                byId.values.toList()
            }
            val mergedRejections = unionByKey(primary.savedPersonRejections, secondary.savedPersonRejections) { pair ->
                FaceClusteringEngine.canonicalPairKey(pair.memoryIdA, pair.memoryIdB)
            }

            return CloudProfileSnapshot(
                schemaVersion = CloudProfileSnapshot.CURRENT_SCHEMA_VERSION,
                updatedAtEpochMs = System.currentTimeMillis(),
                preferences = primary.preferences,
                pinnedDriveRoots = unionByKey(primary.pinnedDriveRoots, secondary.pinnedDriveRoots) { it.folderId },
                collections = unionByKey(primary.collections, secondary.collections) { it.id },
                layouts = unionByKey(primary.layouts, secondary.layouts) { it.itemId },
                hiddenAlbums = unionByKey(primary.hiddenAlbums, secondary.hiddenAlbums) { it.albumId },
                lockedAlbums = unionByKey(primary.lockedAlbums, secondary.lockedAlbums) { it.albumId },
                driveFavoriteItemIds = (primary.driveFavoriteItemIds + secondary.driveFavoriteItemIds).distinct(),
                favoriteStoryIds = (primary.favoriteStoryIds + secondary.favoriteStoryIds).distinct(),
                customAlbumCovers = unionByKey(primary.customAlbumCovers, secondary.customAlbumCovers) { it.albumId },
                mediaAnnotations = unionByKey(primary.mediaAnnotations, secondary.mediaAnnotations) { it.itemId },
                albumViewPrefs = unionByKey(primary.albumViewPrefs, secondary.albumViewPrefs) { it.albumId },
                savedPeople = mergedPeople,
                savedPersonRejections = mergedRejections
            )
        }

        internal fun isScopeDeniedErrorBody(errorBody: String): Boolean {
            if (errorBody.isBlank()) return false
            val lower = errorBody.lowercase()
            return "access_token_scope_insufficient" in lower ||
                "insufficientpermissions" in lower ||
                "insufficient_scope" in lower ||
                "insufficient authentication scopes" in lower ||
                "request had insufficient authentication scopes" in lower
        }
    }
}

private fun UserPreferences.toCloudPayload(): CloudPreferencesPayload =
    CloudPreferencesPayload(
        maxCacheSizeMb = maxCacheSizeMb,
        themeMode = themeMode.name,
        dynamicColor = dynamicColor,
        gridColumns = gridColumns,
        albumGridColumns = albumGridColumns,
        backgroundSyncEnabled = backgroundSyncEnabled,
        albumSortMode = albumSortMode.name,
        defaultStartTab = defaultStartTab.name,
        showDayLocationHeaders = showDayLocationHeaders,
        showAlbumSidebarDefault = showAlbumSidebarDefault,
        showCloudBadges = showCloudBadges,
        autoPlayVideos = autoPlayVideos,
        startVideosMuted = startVideosMuted,
        loopVideos = loopVideos,
        showViewerFilmstrip = showViewerFilmstrip,
        maxViewerBrightness = maxViewerBrightness,
        keepScreenOnViewer = keepScreenOnViewer,
        swipeDownToDismiss = swipeDownToDismiss,
        prefetchAdjacentMedia = prefetchAdjacentMedia,
        wifiOnlyHighRes = wifiOnlyHighRes,
        autoCheckUpdates = autoCheckUpdates,
        stripLocationOnShare = stripLocationOnShare,
        lockHiddenAlbums = lockHiddenAlbums
    )

private fun CloudPreferencesPayload.toUserPreferences(current: UserPreferences): UserPreferences =
    current.copy(
        maxCacheSizeMb = maxCacheSizeMb.coerceIn(250, 2048),
        themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(current.themeMode),
        dynamicColor = dynamicColor,
        gridColumns = gridColumns.coerceIn(
            PreferencesManager.MIN_PHOTO_GRID_COLUMNS,
            PreferencesManager.MAX_PHOTO_GRID_COLUMNS
        ),
        albumGridColumns = albumGridColumns.coerceIn(
            PreferencesManager.MIN_ALBUM_GRID_COLUMNS,
            PreferencesManager.MAX_ALBUM_GRID_COLUMNS
        ),
        backgroundSyncEnabled = backgroundSyncEnabled,
        albumSortMode = runCatching { AlbumSortMode.valueOf(albumSortMode) }.getOrDefault(current.albumSortMode),
        defaultStartTab = runCatching { MainTab.valueOf(defaultStartTab) }.getOrDefault(current.defaultStartTab),
        showDayLocationHeaders = showDayLocationHeaders,
        showAlbumSidebarDefault = showAlbumSidebarDefault,
        showCloudBadges = showCloudBadges,
        autoPlayVideos = autoPlayVideos,
        startVideosMuted = startVideosMuted,
        loopVideos = loopVideos,
        showViewerFilmstrip = showViewerFilmstrip,
        maxViewerBrightness = maxViewerBrightness,
        keepScreenOnViewer = keepScreenOnViewer,
        swipeDownToDismiss = swipeDownToDismiss,
        prefetchAdjacentMedia = prefetchAdjacentMedia,
        wifiOnlyHighRes = wifiOnlyHighRes,
        autoCheckUpdates = autoCheckUpdates,
        stripLocationOnShare = stripLocationOnShare,
        lockHiddenAlbums = lockHiddenAlbums
    )

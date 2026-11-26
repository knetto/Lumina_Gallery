package com.luminagallery.drive.data.repository.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.runtime.Immutable
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.luminagallery.drive.data.local.GalleryDatabase
import com.luminagallery.drive.data.local.HiddenLockedFolderManager
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.local.entity.DetectedFaceEntity
import com.luminagallery.drive.data.local.entity.PersonClusterEntity
import com.luminagallery.drive.data.local.entity.PhotoSmartIndexEntity
import com.luminagallery.drive.data.remote.DriveApiService
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.remote.auth.DriveAuthInterceptor
import com.luminagallery.drive.data.repository.DriveMapper
import com.luminagallery.drive.data.repository.DriveThumbnailFetcher
import com.luminagallery.drive.data.repository.OfflineMediaManager
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

@Immutable
data class SmartIndexProgress(
    val isIndexing: Boolean = false,
    val scannedInRun: Int = 0,
    val totalInRun: Int = 0,
    val totalIndexedPhotos: Int = 0,
    val totalEligiblePhotos: Int = 0,
    val remainingPhotos: Int = 0,
    val isDownloadingModels: Boolean = false,
    val isRunningInBackground: Boolean = false,
    val statusText: String? = null
)

@Immutable
data class PersonSummary(
    val personId: String,
    val name: String,
    val isHidden: Boolean,
    val isFavorite: Boolean,
    val coverCropPath: String?,
    val coverPhotoId: String?,
    val photoCount: Int,
    val photoIds: Set<String>
) {
    val isNamed: Boolean get() = name.isNotBlank()
    val displayTitle: String get() = name.ifBlank { "Unnamed" }
}

@Immutable
data class SmartObjectChip(
    val label: String,
    val count: Int,
    val category: SmartObjectVocabulary.ObjectCategory?
)

@Immutable
data class PersonMergeSuggestionUi(
    val target: PersonSummary,
    val source: PersonSummary,
    val similarity: Float
)

@Serializable
private data class SavedPreservedProfileDto(
    val name: String,
    val isFavorite: Boolean,
    val photoIds: List<String>
)

/**
 * 100% on-device People (Face Recognition + Clustering) & Objects/Scenes AI indexing engine:
 *  - Scans BOTH on-device local photos/videos AND connected Google Drive thumbnails (`=s640`).
 *  - STRICTLY skips all locked & hidden albums (and every nested Drive descendant folder) BEFORE
 *    decoding or downloading a single byte, and automatically purges cached faces/labels if an
 *    album is later locked.
 *  - Supports interactive "Same person?" merge suggestions, preserving user-given names across
 *    rebuilds, full-library scans with Wi-Fi awareness for Drive, and bilingual EN+NL object search.
 */
class SmartRecognitionManager(
    private val context: Context,
    private val database: GalleryDatabase,
    private val localMediaRepository: LocalMediaRepository,
    private val offlineMediaManager: OfflineMediaManager,
    private val preferencesManager: PreferencesManager,
    private val okHttpClient: OkHttpClient,
    private val driveApi: DriveApiService,
    private val networkMonitor: NetworkMonitor
) {
    enum class ScanRunOutcome {
        COMPLETED_ALL,
        PAUSED_BY_USER,
        WAITING_FOR_WIFI,
        MODEL_DOWNLOADING,
        PARTIAL_BATCH,
        ALREADY_BUSY
    }
    private val smartDao = database.smartRecognitionDao()
    private val extrasDao = database.galleryExtrasDao()
    private val driveItemDao = database.driveItemDao()
    private val faceEngine = FaceEmbeddingEngine(context)
    private val objectEngine = ImageNetObjectEngine(context)
    private val metaPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val profileJson = Json { ignoreUnknownKeys = true }

    private val scanMutex = Mutex()
    @Volatile
    private var pauseRequested: Boolean = false

    private val _progress = MutableStateFlow(SmartIndexProgress())
    val progress: StateFlow<SmartIndexProgress> = _progress.asStateFlow()

    private val _rejectedMergePairs = MutableStateFlow(
        metaPrefs.getStringSet(KEY_REJECTED_MERGE_PAIRS, emptySet())?.toSet().orEmpty()
    )
    val rejectedMergePairsFlow: StateFlow<Set<String>> = _rejectedMergePairs.asStateFlow()

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
        FaceDetection.getClient(options)
    }

    private val imageLabeler by lazy {
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.55f)
            .build()
        ImageLabeling.getClient(options)
    }

    private val cropVerifierLabeler by lazy {
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.42f)
            .build()
        ImageLabeling.getClient(options)
    }

    val allPersonsFlow: Flow<List<PersonClusterEntity>> = smartDao.observeAllPersons()
    val allActiveFacesFlow: Flow<List<DetectedFaceEntity>> = smartDao.observeAllActiveFaces()
    val smartIndexMapFlow: Flow<Map<String, PhotoSmartIndexEntity>> =
        smartDao.observeAllSmartIndex()
            .map { list -> list.associateBy { it.itemId } }
            .flowOn(Dispatchers.Default)

    fun observePersonById(personId: String): Flow<PersonClusterEntity?> =
        smartDao.observePersonById(personId)

    fun observeFacesForPerson(personId: String): Flow<List<DetectedFaceEntity>> =
        smartDao.observeFacesForPerson(personId)

    fun observeFacesForPhoto(itemId: String): Flow<List<DetectedFaceEntity>> =
        smartDao.observeFacesForPhoto(itemId)

    fun observeSmartIndexForPhoto(itemId: String): Flow<PhotoSmartIndexEntity?> =
        smartDao.observeSmartIndexForPhoto(itemId)

    fun pauseActiveScan() {
        pauseRequested = true
    }

    fun dismissMergeSuggestion(personIdA: String, personIdB: String) {
        val key = FaceClusteringEngine.canonicalPairKey(personIdA, personIdB)
        val updated = _rejectedMergePairs.value + key
        _rejectedMergePairs.value = updated
        metaPrefs.edit().putStringSet(KEY_REJECTED_MERGE_PAIRS, updated).apply()
    }

    /**
     * Clears all cached People clusters, face crops, and object tags so the library re-scans
     * cleanly from scratch — automatically preserving user-assigned names & pinned favorites so
     * they re-attach to the rebuilt clusters by photo overlap!
     */
    suspend fun resetAllIndexData(preserveNames: Boolean = true) = withContext(Dispatchers.IO) {
        pauseRequested = true
        scanMutex.lock()
        try {
            if (preserveNames) {
                snapshotNamedProfilesBeforeReset()
            } else {
                metaPrefs.edit().remove(KEY_PRESERVED_PROFILES).apply()
            }
            database.withTransaction {
                smartDao.clearAllFaces()
                smartDao.clearAllPersons()
                smartDao.clearAllSmartIndex()
            }
            faceEngine.clearAllCropFiles()
            _rejectedMergePairs.value = emptySet()
            metaPrefs.edit()
                .remove(KEY_REJECTED_MERGE_PAIRS)
                .putInt(KEY_AI_INDEX_VERSION, CURRENT_AI_INDEX_VERSION)
                .putInt(KEY_AVATAR_CROP_VERSION, CURRENT_AVATAR_CROP_VERSION)
                .apply()
            _progress.value = SmartIndexProgress()
        } finally {
            scanMutex.unlock()
        }
    }

    private suspend fun snapshotNamedProfilesBeforeReset() {
        val persons = smartDao.getAllPersons().filter { it.name.isNotBlank() || it.isFavorite }
        if (persons.isEmpty()) return
        val dtos = persons.mapNotNull { person ->
            val photoIds = smartDao.getFacesForPerson(person.personId)
                .map { it.itemId }
                .distinct()
                .take(80)
            if (photoIds.isEmpty()) null
            else SavedPreservedProfileDto(
                name = person.name,
                isFavorite = person.isFavorite,
                photoIds = photoIds
            )
        }
        if (dtos.isNotEmpty()) {
            val serialized = runCatching { profileJson.encodeToString(dtos) }.getOrNull()
            if (serialized != null) {
                metaPrefs.edit().putString(KEY_PRESERVED_PROFILES, serialized).apply()
            }
        }
    }

    private suspend fun applyPreservedPersonProfiles() {
        val raw = metaPrefs.getString(KEY_PRESERVED_PROFILES, null) ?: return
        val dtos = runCatching {
            profileJson.decodeFromString<List<SavedPreservedProfileDto>>(raw)
        }.getOrDefault(emptyList())
        if (dtos.isEmpty()) {
            metaPrefs.edit().remove(KEY_PRESERVED_PROFILES).apply()
            return
        }

        val allPersons = smartDao.getAllPersons().filterNot { it.isHidden }
        if (allPersons.isEmpty()) return
        val candidates = allPersons.map { p ->
            val faces = smartDao.getFacesForPerson(p.personId)
            FaceClusteringEngine.ClusterCandidate(
                personId = p.personId,
                centroid = FaceClusteringEngine.decodeEmbedding(p.centroidBlob),
                sampleCount = faces.size.coerceAtLeast(p.sampleCount),
                isNamed = p.name.isNotBlank(),
                photoIds = faces.mapTo(HashSet(faces.size)) { it.itemId }
            )
        }
        val profiles = dtos.map {
            FaceClusteringEngine.PreservedPersonProfile(
                name = it.name,
                isFavorite = it.isFavorite,
                photoIds = it.photoIds.toSet()
            )
        }
        val restored = FaceClusteringEngine.matchPreservedProfiles(profiles, candidates)
        if (restored.isEmpty()) return

        val matchedProfileNames = HashSet<String>()
        val now = System.currentTimeMillis()
        database.withTransaction {
            for (match in restored) {
                val target = smartDao.getPersonById(match.personId) ?: continue
                smartDao.upsertPerson(
                    target.copy(
                        name = match.profile.name.ifBlank { target.name },
                        isFavorite = target.isFavorite || match.profile.isFavorite,
                        updatedAtEpochMs = now
                    )
                )
                matchedProfileNames.add(match.profile.name)
            }
        }

        val remainingDtos = dtos.filterNot { it.name in matchedProfileNames }
        if (remainingDtos.isEmpty()) {
            metaPrefs.edit().remove(KEY_PRESERVED_PROFILES).apply()
        } else {
            runCatching { profileJson.encodeToString(remainingDtos) }.getOrNull()?.let { updated ->
                metaPrefs.edit().putString(KEY_PRESERVED_PROFILES, updated).apply()
            }
        }
    }

    /**
     * Observes privacy-filtered [PersonSummary] items computed exclusively from [visibleItemIdsFlow]
     * so faces inside locked or hidden albums NEVER leak into People, Search, or Photo Counts.
     */
    fun observeVisiblePersons(
        visibleItemIdsFlow: Flow<Set<String>>,
        includeHidden: Boolean = false
    ): Flow<List<PersonSummary>> = combine(
        allPersonsFlow,
        allActiveFacesFlow,
        visibleItemIdsFlow
    ) { persons, faces, visibleIds ->
        if (persons.isEmpty() || visibleIds.isEmpty()) return@combine emptyList()
        val facesByPerson = HashMap<String, MutableList<DetectedFaceEntity>>(persons.size)
        for (face in faces) {
            val pid = face.personId ?: continue
            if (face.itemId !in visibleIds) continue
            facesByPerson.getOrPut(pid) { mutableListOf() }.add(face)
        }

        persons.mapNotNull { person ->
            if (!includeHidden && person.isHidden) return@mapNotNull null
            val personFaces = facesByPerson[person.personId] ?: return@mapNotNull null
            if (personFaces.isEmpty()) return@mapNotNull null
            val photoIds = personFaces.mapTo(LinkedHashSet(personFaces.size)) { it.itemId }

            // Ensure the cover avatar itself belongs to a visible (unlocked) photo
            val safeCoverFace = personFaces.firstOrNull {
                it.faceId == person.coverFaceId && !it.cropPath.isNullOrBlank()
            } ?: personFaces.maxByOrNull { it.qualityScore }

            PersonSummary(
                personId = person.personId,
                name = person.name,
                isHidden = person.isHidden,
                isFavorite = person.isFavorite,
                coverCropPath = safeCoverFace?.cropPath ?: person.coverCropPath?.takeIf { person.coverPhotoId in visibleIds },
                coverPhotoId = safeCoverFace?.itemId ?: person.coverPhotoId?.takeIf { it in visibleIds },
                photoCount = photoIds.size,
                photoIds = photoIds
            )
        }.sortedWith(
            compareByDescending<PersonSummary> { it.isFavorite }
                .thenBy { if (it.isNamed) 0 else 1 }
                .thenByDescending { it.photoCount }
                .thenBy { it.name.lowercase(Locale.US) }
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Incrementally scans ONLY unindexed or modified local & connected Google Drive photos/videos
     * for faces + objects/scenes, strictly excluding all locked & hidden albums (and Drive subtrees).
     *
     * Optimized for 30,000+ photo libraries:
     *  - Reads only lightweight `(itemId, dateModifiedMs)` stamps from Room so 30,000 already-scanned
     *    photos are skipped in milliseconds and only brand-new photos are processed.
     *  - Commits progress checkpoints every 20 photos so pausing, locking the screen, losing Wi-Fi,
     *    or rebooting never loses progress or re-scans completed items.
     */
    suspend fun syncSmartIndex(
        maxNewItemsPerRun: Int = 120,
        includeDrive: Boolean = true,
        respectWifiOnlyForDrive: Boolean = true,
        requireWifiThroughout: Boolean = false,
        runningInBackgroundService: Boolean = false,
        shouldStop: () -> Boolean = { false },
        onProgressUpdate: (suspend (SmartIndexProgress) -> Unit)? = null
    ): ScanRunOutcome = withContext(Dispatchers.IO) {
        if (!scanMutex.tryLock()) return@withContext ScanRunOutcome.ALREADY_BUSY
        pauseRequested = false
        try {
            // One-time automatic clean rebuild when upgrading from v1 -> v2 (preserves any names set!)
            val savedVersion = metaPrefs.getInt(KEY_AI_INDEX_VERSION, 0)
            if (savedVersion < CURRENT_AI_INDEX_VERSION) {
                snapshotNamedProfilesBeforeReset()
                database.withTransaction {
                    smartDao.clearAllFaces()
                    smartDao.clearAllPersons()
                    smartDao.clearAllSmartIndex()
                }
                faceEngine.clearAllCropFiles()
                metaPrefs.edit()
                    .putInt(KEY_AI_INDEX_VERSION, CURRENT_AI_INDEX_VERSION)
                    .putInt(KEY_AVATAR_CROP_VERSION, CURRENT_AVATAR_CROP_VERSION)
                    .apply()
            }

            val blockedFolderIds = computeBlockedFolderIdsFromDb()

            // Gather eligible local photos AND videos (1 fast thumbnail frame per video),
            // strictly skipping locked, hidden, and hidden-on-disk vault files!
            val localCandidates = if (localMediaRepository.hasMediaPermission()) {
                localMediaRepository.getCachedAllMedia().ifEmpty {
                    runCatching { localMediaRepository.observeAllLocalMedia().first() }.getOrDefault(emptyList())
                }.filter { item ->
                    item.isLocal &&
                        !item.isRaw &&
                        !item.localContentUri.isNullOrBlank() &&
                        item.parentId !in blockedFolderIds &&
                        !HiddenLockedFolderManager.isHiddenFileUri(item.localContentUri)
                }
            } else {
                emptyList()
            }

            // Gather eligible connected Google Drive photos AND videos (via lightweight server thumbnails),
            // strictly skipping locked & hidden Drive subtrees!
            val driveCandidates = if (includeDrive) {
                driveItemDao.getAllNonTrashedMediaSync()
                    .asSequence()
                    .map(DriveMapper::entityToMediaItem)
                    .filter { item ->
                        item.parentId !in blockedFolderIds &&
                            (!item.thumbnailLink.isNullOrBlank() || (!item.isVideo && offlineMediaManager.getOfflineFilePath(item.id) != null))
                    }
                    .toList()
            } else {
                emptyList()
            }

            val allEligible = (localCandidates + driveCandidates)
                .distinctBy { it.id }
                .sortedWith(
                    compareBy<MediaItem> { scanPriority(it) }
                        .thenByDescending { it.createdTimeEpochMs }
                )

            // Purge any previously cached face/object data that now belongs to a locked/hidden album
            purgeBlockedItemsFromCache(blockedFolderIds)

            // One-time fast upgrade of any legacy rotated black-corner avatars to clean unrotated
            // face-centered crops (preserves all person names, favorites, clusters, and embeddings!)
            migrateLegacyRotatedAvatarsIfNeeded(allEligible)

            // Fast 2-column SQLite stamp lookup (scales cleanly to 30,000+ photos in ~20ms)
            val stampRows = smartDao.getIndexedItemStamps()
            val existingStamps = HashMap<String, Long>(stampRows.size * 2)
            for (row in stampRows) {
                existingStamps[row.itemId] = row.dateModifiedMs
            }

            val unindexedAll = filterUnindexedItems(allEligible, existingStamps)
            val totalEligible = allEligible.size
            val alreadyIndexed = (totalEligible - unindexedAll.size).coerceAtLeast(0)

            // Clean up deleted-photo orphans periodically so the index never grows unbounded
            if (includeDrive && localMediaRepository.hasMediaPermission() && existingStamps.size > totalEligible + ORPHAN_PRUNE_THRESHOLD) {
                val eligibleIds = allEligible.mapTo(HashSet(totalEligible)) { it.id }
                val deletedIds = existingStamps.keys.filter { it !in eligibleIds }
                if (deletedIds.isNotEmpty()) {
                    purgeItemIdsFromCache(deletedIds)
                }
            }

            if (unindexedAll.isEmpty()) {
                val doneProgress = SmartIndexProgress(
                    isIndexing = false,
                    scannedInRun = 0,
                    totalInRun = 0,
                    totalIndexedPhotos = totalEligible,
                    totalEligiblePhotos = totalEligible,
                    remainingPhotos = 0,
                    isRunningInBackground = false,
                    statusText = null
                )
                _progress.value = doneProgress
                onProgressUpdate?.invoke(doneProgress)
                return@withContext ScanRunOutcome.COMPLETED_ALL
            }

            val prefs = runCatching { preferencesManager.getCurrentPreferences() }.getOrNull()
            val net = networkMonitor.networkState.value
            if (shouldStopScanForWifi(requireWifiThroughout = requireWifiThroughout, isUnmeteredWifi = net.isUnmeteredWifi)) {
                val waitingProgress = SmartIndexProgress(
                    isIndexing = false,
                    scannedInRun = 0,
                    totalInRun = 0,
                    totalIndexedPhotos = alreadyIndexed,
                    totalEligiblePhotos = totalEligible,
                    remainingPhotos = unindexedAll.size,
                    isRunningInBackground = false,
                    statusText = "Paused · resumes automatically on Wi-Fi"
                )
                _progress.value = waitingProgress
                onProgressUpdate?.invoke(waitingProgress)
                return@withContext ScanRunOutcome.WAITING_FOR_WIFI
            }

            val canFetchOnlineDrive = net.isOnline && (!respectWifiOnlyForDrive || prefs?.wifiOnlyHighRes != true || net.isUnmeteredWifi)
            val filteredByNetwork = if (canFetchOnlineDrive) {
                unindexedAll
            } else {
                unindexedAll.filter { it.isLocal || (!it.isVideo && offlineMediaManager.getOfflineFilePath(it.id) != null) }
            }
            val runQueue = if (maxNewItemsPerRun == Int.MAX_VALUE) {
                filteredByNetwork
            } else {
                filteredByNetwork.take(maxNewItemsPerRun)
            }

            if (runQueue.isEmpty()) {
                val waitMsg = if (!net.isOnline) {
                    "Connect to the internet to scan Google Drive items"
                } else {
                    "Waiting for Wi-Fi to scan Google Drive thumbnails"
                }
                val waitingProgress = SmartIndexProgress(
                    isIndexing = false,
                    scannedInRun = 0,
                    totalInRun = 0,
                    totalIndexedPhotos = alreadyIndexed,
                    totalEligiblePhotos = totalEligible,
                    remainingPhotos = unindexedAll.size,
                    isRunningInBackground = false,
                    statusText = waitMsg
                )
                _progress.value = waitingProgress
                onProgressUpdate?.invoke(waitingProgress)
                return@withContext ScanRunOutcome.WAITING_FOR_WIFI
            }

            val inMemoryClusters = smartDao.getAllPersons()
                .associateByTo(LinkedHashMap()) { it.personId }
                .mapValuesTo(LinkedHashMap()) { (_, entity) ->
                    MutableClusterState(
                        entity = entity,
                        centroid = FaceClusteringEngine.decodeEmbedding(entity.centroidBlob)
                    )
                }

            var processedInQueue = 0
            var newlyIndexedCount = 0
            var stoppedForWifi = false
            var stoppedByUserOrSystem = false
            val totalRun = runQueue.size
            val initialProgress = SmartIndexProgress(
                isIndexing = true,
                scannedInRun = 0,
                totalInRun = totalRun,
                totalIndexedPhotos = alreadyIndexed,
                totalEligiblePhotos = totalEligible,
                remainingPhotos = unindexedAll.size,
                isRunningInBackground = runningInBackgroundService,
                statusText = if (runningInBackgroundService && requireWifiThroughout) {
                    "Scanning on Wi-Fi · safe to lock screen"
                } else if (runningInBackgroundService) {
                    "Scanning in background · safe to lock screen"
                } else {
                    "Scanning new photos & videos…"
                }
            )
            _progress.value = initialProgress
            onProgressUpdate?.invoke(initialProgress)

            val pendingIndexBatch = ArrayList<PhotoSmartIndexEntity>(BATCH_WRITE_SIZE)
            val pendingFacesBatch = ArrayList<DetectedFaceEntity>(BATCH_WRITE_SIZE * 2)
            val dirtyClusterIds = LinkedHashSet<String>()

            suspend fun flushPending() {
                if (pendingIndexBatch.isEmpty() && pendingFacesBatch.isEmpty() && dirtyClusterIds.isEmpty()) return
                val indicesToWrite = pendingIndexBatch.toList()
                val facesToWrite = pendingFacesBatch.toList()
                val clustersToWrite = dirtyClusterIds.mapNotNull { inMemoryClusters[it]?.entity }
                database.withTransaction {
                    if (clustersToWrite.isNotEmpty()) smartDao.upsertPersons(clustersToWrite)
                    if (facesToWrite.isNotEmpty()) smartDao.upsertFaces(facesToWrite)
                    if (indicesToWrite.isNotEmpty()) smartDao.upsertSmartIndex(indicesToWrite)
                }
                pendingIndexBatch.clear()
                pendingFacesBatch.clear()
                dirtyClusterIds.clear()
            }

            var activeBlockedIds = blockedFolderIds

            for (item in runQueue) {
                if (pauseRequested || shouldStop()) {
                    stoppedByUserOrSystem = true
                    break
                }
                if (shouldStopScanForWifi(
                        requireWifiThroughout = requireWifiThroughout,
                        isUnmeteredWifi = networkMonitor.networkState.value.isUnmeteredWifi
                    )
                ) {
                    stoppedForWifi = true
                    break
                }

                // Periodically refresh blocked folders (every 30 items instead of 30,000 DB queries!)
                if (processedInQueue > 0 && processedInQueue % BLOCKED_REFRESH_INTERVAL == 0) {
                    activeBlockedIds = computeBlockedFolderIdsFromDb()
                }
                if (item.parentId in activeBlockedIds) {
                    processedInQueue++
                    continue
                }

                val bitmap = decodeScanBitmap(item)
                if (bitmap == null) {
                    // For local files that genuinely fail to decode (corrupt/unsupported), persist an empty
                    // index stamp so future incremental scans never waste battery re-failing on them.
                    // For Drive items (which may fail transiently due to network hiccups), do NOT cache empty.
                    if (item.isLocal) {
                        pendingIndexBatch.add(
                            PhotoSmartIndexEntity(
                                itemId = item.id,
                                dateModifiedMs = item.createdTimeEpochMs,
                                faceCount = 0,
                                labelsCsv = "",
                                labelSearchTokens = "",
                                isDriveItem = false,
                                indexedAtEpochMs = System.currentTimeMillis()
                            )
                        )
                        newlyIndexedCount++
                        if (pendingIndexBatch.size >= BATCH_WRITE_SIZE) {
                            flushPending()
                        }
                    }
                    processedInQueue++
                    val stepProgress = SmartIndexProgress(
                        isIndexing = true,
                        scannedInRun = processedInQueue,
                        totalInRun = totalRun,
                        totalIndexedPhotos = (alreadyIndexed + newlyIndexedCount).coerceAtMost(totalEligible),
                        totalEligiblePhotos = totalEligible,
                        remainingPhotos = (unindexedAll.size - newlyIndexedCount).coerceAtLeast(0),
                        isRunningInBackground = runningInBackgroundService,
                        statusText = buildActiveScanStatusText(
                            isLocalItem = item.isLocal,
                            runningInBackground = runningInBackgroundService,
                            requireWifi = requireWifiThroughout
                        )
                    )
                    _progress.value = stepProgress
                    onProgressUpdate?.invoke(stepProgress)
                    continue
                }

                try {
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val facesResult = runCatching {
                        Tasks.await(faceDetector.process(inputImage), ML_TIMEOUT_SEC, TimeUnit.SECONDS)
                    }
                    val labelsResult = runCatching {
                        Tasks.await(imageLabeler.process(inputImage), ML_TIMEOUT_SEC, TimeUnit.SECONDS)
                    }

                    // If Play Services is still downloading unbundled ML Kit models on first launch, stop cleanly without caching empties
                    val faceErr = facesResult.exceptionOrNull()
                    val labelErr = labelsResult.exceptionOrNull()
                    if (isModelDownloadingException(faceErr) || isModelDownloadingException(labelErr)) {
                        flushPending()
                        val dlProgress = SmartIndexProgress(
                            isIndexing = false,
                            scannedInRun = processedInQueue,
                            totalInRun = totalRun,
                            totalIndexedPhotos = (alreadyIndexed + newlyIndexedCount).coerceAtMost(totalEligible),
                            totalEligiblePhotos = totalEligible,
                            remainingPhotos = (unindexedAll.size - newlyIndexedCount).coerceAtLeast(0),
                            isDownloadingModels = true,
                            isRunningInBackground = false,
                            statusText = "Downloading on-device AI models via Google Play Services…"
                        )
                        _progress.value = dlProgress
                        onProgressUpdate?.invoke(dlProgress)
                        return@withContext ScanRunOutcome.MODEL_DOWNLOADING
                    }

                    val detectedFaces = facesResult.getOrDefault(emptyList())
                    val rawMlLabels = labelsResult.getOrDefault(emptyList())
                        .map { it.text to it.confidence }
                    val rawImageNetLabels = objectEngine.classify(
                        bitmap = bitmap,
                        topK = 5,
                        minConfidence = 0.22f
                    )

                    val mergedObjects = SmartObjectVocabulary.mergeSceneAndObjectLabels(
                        mlKitLabels = rawMlLabels,
                        imageNetLabels = rawImageNetLabels
                    )
                    val now = System.currentTimeMillis()

                    val usedPersonIdsInThisPhoto = HashSet<String>()
                    var acceptedFaces = 0

                    for ((idx, face) in detectedFaces.withIndex()) {
                        val processed = processSingleFace(
                            bitmap = bitmap,
                            item = item,
                            face = face,
                            faceIndex = idx,
                            now = now,
                            clusters = inMemoryClusters,
                            forbiddenPersonIds = usedPersonIdsInThisPhoto
                        )
                        if (processed != null) {
                            acceptedFaces++
                            processed.personId?.let { pid ->
                                usedPersonIdsInThisPhoto.add(pid)
                                dirtyClusterIds.add(pid)
                            }
                            pendingFacesBatch.add(processed)
                        }
                    }

                    pendingIndexBatch.add(
                        PhotoSmartIndexEntity(
                            itemId = item.id,
                            dateModifiedMs = item.createdTimeEpochMs,
                            faceCount = acceptedFaces,
                            labelsCsv = mergedObjects.displayLabels.joinToString(","),
                            labelSearchTokens = mergedObjects.searchTokens,
                            isDriveItem = !item.isLocal,
                            indexedAtEpochMs = now
                        )
                    )

                    processedInQueue++
                    newlyIndexedCount++
                    if (pendingIndexBatch.size >= BATCH_WRITE_SIZE) {
                        flushPending()
                    }
                    // Periodically merge high-confidence split clusters during very large 30k runs
                    if (newlyIndexedCount % PERIODIC_CONSOLIDATE_INTERVAL == 0) {
                        flushPending()
                        runCatching { consolidateSplitClusters() }
                    }
                    val stepProgress = SmartIndexProgress(
                        isIndexing = true,
                        scannedInRun = processedInQueue,
                        totalInRun = totalRun,
                        totalIndexedPhotos = (alreadyIndexed + newlyIndexedCount).coerceAtMost(totalEligible),
                        totalEligiblePhotos = totalEligible,
                        remainingPhotos = (unindexedAll.size - newlyIndexedCount).coerceAtLeast(0),
                        isRunningInBackground = runningInBackgroundService,
                        statusText = buildActiveScanStatusText(
                            isLocalItem = item.isLocal,
                            runningInBackground = runningInBackgroundService,
                            requireWifi = requireWifiThroughout
                        )
                    )
                    _progress.value = stepProgress
                    onProgressUpdate?.invoke(stepProgress)
                } finally {
                    bitmap.recycle()
                }
            }

            flushPending()

            // Post-scan high-confidence cluster consolidation & preserved-name restoration
            if (newlyIndexedCount > 0) {
                runCatching { consolidateSplitClusters() }
                runCatching { applyPreservedPersonProfiles() }
            }

            val finalRemaining = (unindexedAll.size - newlyIndexedCount).coerceAtLeast(0)
            val finalStatus = when {
                stoppedForWifi -> "Paused · resumes automatically on Wi-Fi"
                stoppedByUserOrSystem && finalRemaining > 0 -> "Paused · $finalRemaining remaining"
                else -> null
            }
            val finalProgress = SmartIndexProgress(
                isIndexing = false,
                scannedInRun = processedInQueue,
                totalInRun = totalRun,
                totalIndexedPhotos = (alreadyIndexed + newlyIndexedCount).coerceAtMost(totalEligible),
                totalEligiblePhotos = totalEligible,
                remainingPhotos = finalRemaining,
                isRunningInBackground = false,
                statusText = finalStatus
            )
            _progress.value = finalProgress
            onProgressUpdate?.invoke(finalProgress)

            when {
                stoppedForWifi -> ScanRunOutcome.WAITING_FOR_WIFI
                stoppedByUserOrSystem -> ScanRunOutcome.PAUSED_BY_USER
                finalRemaining == 0 -> ScanRunOutcome.COMPLETED_ALL
                else -> ScanRunOutcome.PARTIAL_BATCH
            }
        } catch (ce: CancellationException) {
            _progress.value = _progress.value.copy(isIndexing = false, isRunningInBackground = false)
            throw ce
        } catch (_: Throwable) {
            _progress.value = _progress.value.copy(isIndexing = false, isRunningInBackground = false)
            ScanRunOutcome.PARTIAL_BATCH
        } finally {
            scanMutex.unlock()
        }
    }

    private fun processSingleFace(
        bitmap: Bitmap,
        item: MediaItem,
        face: Face,
        faceIndex: Int,
        now: Long,
        clusters: LinkedHashMap<String, MutableClusterState>,
        forbiddenPersonIds: Set<String>
    ): DetectedFaceEntity? {
        val box = face.boundingBox
        val w = box.width().toFloat()
        val h = box.height().toFloat()

        // Gate 1: Strict human facial anatomy & pose verification (eliminates tiny background faces,
        // extreme side profiles, and animals/parrots/patterns lacking valid human landmark geometry!)
        val eye1 = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            ?.let { FaceClusteringEngine.Point2D(it.x, it.y) }
        val eye2 = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            ?.let { FaceClusteringEngine.Point2D(it.x, it.y) }
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
            ?.let { FaceClusteringEngine.Point2D(it.x, it.y) }
        val mouthL = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthR = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mouthB = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val mouth = when {
            mouthB != null -> FaceClusteringEngine.Point2D(mouthB.x, mouthB.y)
            mouthL != null && mouthR != null -> FaceClusteringEngine.Point2D(
                (mouthL.x + mouthR.x) * 0.5f,
                (mouthL.y + mouthR.y) * 0.5f
            )
            mouthL != null -> FaceClusteringEngine.Point2D(mouthL.x, mouthL.y)
            mouthR != null -> FaceClusteringEngine.Point2D(mouthR.x, mouthR.y)
            else -> null
        }
        val hasClassification = face.leftEyeOpenProbability != null ||
            face.rightEyeOpenProbability != null ||
            face.smilingProbability != null

        if (!FaceClusteringEngine.isHumanFaceGeometryValid(
                bboxLeft = box.left.toFloat(),
                bboxTop = box.top.toFloat(),
                bboxRight = box.right.toFloat(),
                bboxBottom = box.bottom.toFloat(),
                imageWidthPx = bitmap.width.toFloat(),
                eulerY = face.headEulerAngleY,
                eulerX = face.headEulerAngleX,
                eyeA = eye1,
                eyeB = eye2,
                nose = nose,
                mouth = mouth,
                hasClassification = hasClassification
            )
        ) {
            return null
        }

        // Gate 2: AI Non-Human Face-Crop Verification!
        // Classify the upright cropped "face" directly with BOTH ML Kit & EfficientNet-Lite2 so a
        // parrot, dog, cat, plush toy, statue, or pareidolia pattern NEVER enters the People section!
        val verifyBitmap = faceEngine.extractUprightFaceCrop(bitmap, face) ?: return null
        val cropPath: String?
        val embedding: FloatArray?
        try {
            val cropMlLabels = runCatching {
                Tasks.await(
                    cropVerifierLabeler.process(InputImage.fromBitmap(verifyBitmap, 0)),
                    ML_TIMEOUT_SEC,
                    TimeUnit.SECONDS
                ).map { it.text to it.confidence }
            }.getOrDefault(emptyList())
            val cropImageNetLabels = objectEngine.classify(
                bitmap = verifyBitmap,
                topK = 5,
                minConfidence = 0.16f
            )
            if (SmartObjectVocabulary.isNonHumanFaceCrop(cropMlLabels, cropImageNetLabels)) {
                return null
            }

            // Gate 3: Canonical 5-point InsightFace aligned MobileFaceNet embedding (+ horizontal flip TTA)
            embedding = faceEngine.computeEmbedding(bitmap, face)
            if (embedding == null || embedding.isEmpty()) return null

            val faceId = "${item.id}_f$faceIndex"
            // Visible circular People avatar: unrotated, face-centered, strictly in-bounds (zero black corners!)
            val portraitAvatar = faceEngine.extractAvatarPortraitCrop(bitmap, face)
            cropPath = if (portraitAvatar != null) {
                try {
                    faceEngine.saveAvatarBitmap(portraitAvatar, "${faceId}_v2")
                } finally {
                    portraitAvatar.recycle()
                }
            } else {
                faceEngine.saveAvatarBitmap(verifyBitmap, "${faceId}_v2")
            }
        } finally {
            verifyBitmap.recycle()
        }

        val faceId = "${item.id}_f$faceIndex"
        val quality = FaceClusteringEngine.computeFaceQualityScore(
            faceWidthPx = w,
            faceHeightPx = h,
            imageWidthPx = bitmap.width.toFloat(),
            eulerY = face.headEulerAngleY,
            eulerX = face.headEulerAngleX,
            leftEyeOpen = face.leftEyeOpenProbability,
            rightEyeOpen = face.rightEyeOpenProbability,
            smilingProb = face.smilingProbability
        )

        val candidates = clusters.values.map {
            FaceClusteringEngine.ClusterCandidate(
                personId = it.entity.personId,
                centroid = it.centroid,
                sampleCount = it.entity.sampleCount,
                isNamed = it.entity.name.isNotBlank()
            )
        }
        val match = FaceClusteringEngine.findBestMatchingCluster(
            embedding = embedding,
            candidates = candidates,
            forbiddenPersonIds = forbiddenPersonIds
        )

        val assignedPersonId = if (match != null) {
            val state = clusters[match.personId]
            if (state != null) {
                val updatedCentroid = FaceClusteringEngine.updateRunningCentroid(
                    oldCentroid = state.centroid,
                    oldCount = state.entity.sampleCount,
                    newEmbedding = embedding
                )
                val shouldUpgradeCover = state.entity.coverCropPath.isNullOrBlank() ||
                    (cropPath != null && quality > state.bestCoverQuality + 0.04f)
                state.centroid = updatedCentroid
                if (shouldUpgradeCover && cropPath != null) {
                    state.bestCoverQuality = quality
                }
                state.entity = state.entity.copy(
                    centroidBlob = FaceClusteringEngine.encodeEmbedding(updatedCentroid),
                    sampleCount = state.entity.sampleCount + 1,
                    coverFaceId = if (shouldUpgradeCover) faceId else state.entity.coverFaceId,
                    coverPhotoId = if (shouldUpgradeCover) item.id else state.entity.coverPhotoId,
                    coverCropPath = if (shouldUpgradeCover && cropPath != null) cropPath else state.entity.coverCropPath,
                    updatedAtEpochMs = now
                )
                match.personId
            } else {
                createNewCluster(clusters, faceId, item.id, cropPath, embedding, quality, now)
            }
        } else {
            createNewCluster(clusters, faceId, item.id, cropPath, embedding, quality, now)
        }

        val imgW = bitmap.width.toFloat().coerceAtLeast(1f)
        val imgH = bitmap.height.toFloat().coerceAtLeast(1f)
        return DetectedFaceEntity(
            faceId = faceId,
            itemId = item.id,
            personId = assignedPersonId,
            embeddingBlob = FaceClusteringEngine.encodeEmbedding(embedding),
            bboxLeft = (box.left / imgW).coerceIn(0f, 1f),
            bboxTop = (box.top / imgH).coerceIn(0f, 1f),
            bboxRight = (box.right / imgW).coerceIn(0f, 1f),
            bboxBottom = (box.bottom / imgH).coerceIn(0f, 1f),
            qualityScore = quality,
            cropPath = cropPath,
            isExcludedByUser = false,
            detectedAtEpochMs = now
        )
    }

    private suspend fun consolidateSplitClusters() {
        val allPersons = smartDao.getAllPersons().filterNot { it.isHidden }
        if (allPersons.size < 2) return
        val candidates = allPersons.map { person ->
            val faces = smartDao.getFacesForPerson(person.personId)
            FaceClusteringEngine.ClusterCandidate(
                personId = person.personId,
                centroid = FaceClusteringEngine.decodeEmbedding(person.centroidBlob),
                sampleCount = faces.size.coerceAtLeast(person.sampleCount),
                isNamed = person.name.isNotBlank(),
                photoIds = faces.mapTo(HashSet(faces.size)) { it.itemId }
            )
        }
        val merges = FaceClusteringEngine.findAutoConsolidationPairs(
            clusters = candidates,
            rejectedPairs = _rejectedMergePairs.value
        )
        if (merges.isEmpty()) return
        for (merge in merges) {
            mergePersons(
                targetPersonId = merge.targetPersonId,
                sourcePersonIds = listOf(merge.sourcePersonId)
            )
        }
    }

    private fun createNewCluster(
        clusters: LinkedHashMap<String, MutableClusterState>,
        faceId: String,
        photoId: String,
        cropPath: String?,
        embedding: FloatArray,
        quality: Float,
        now: Long
    ): String {
        val newId = "person_${UUID.randomUUID().toString().take(12)}"
        val entity = PersonClusterEntity(
            personId = newId,
            name = "",
            isHidden = false,
            isFavorite = false,
            coverFaceId = faceId,
            coverPhotoId = photoId,
            coverCropPath = cropPath,
            centroidBlob = FaceClusteringEngine.encodeEmbedding(embedding),
            sampleCount = 1,
            updatedAtEpochMs = now
        )
        clusters[newId] = MutableClusterState(
            entity = entity,
            centroid = embedding,
            bestCoverQuality = quality
        )
        return newId
    }

    // ── People Management Actions (Name, Combine/Merge, Hide, Favorite, Cover, Split) ──

    suspend fun findExistingPersonByName(name: String, excludePersonId: String? = null): PersonClusterEntity? =
        withContext(Dispatchers.IO) {
            val clean = name.trim()
            if (clean.isEmpty()) return@withContext null
            smartDao.getAllPersons().firstOrNull {
                it.personId != excludePersonId && it.name.equals(clean, ignoreCase = true)
            }
        }

    suspend fun renamePerson(personId: String, newName: String) = withContext(Dispatchers.IO) {
        smartDao.renamePerson(
            personId = personId,
            name = newName.trim(),
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun setPersonHidden(personId: String, hidden: Boolean) = withContext(Dispatchers.IO) {
        smartDao.setPersonHidden(
            personId = personId,
            hidden = hidden,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun togglePersonFavorite(personId: String) = withContext(Dispatchers.IO) {
        val current = smartDao.getPersonById(personId) ?: return@withContext
        smartDao.setPersonFavorite(
            personId = personId,
            favorite = !current.isFavorite,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun setPersonCoverFace(personId: String, faceId: String) = withContext(Dispatchers.IO) {
        val person = smartDao.getPersonById(personId) ?: return@withContext
        val face = smartDao.getFaceById(faceId) ?: return@withContext
        if (face.personId != personId) return@withContext
        smartDao.upsertPerson(
            person.copy(
                coverFaceId = face.faceId,
                coverPhotoId = face.itemId,
                coverCropPath = face.cropPath ?: person.coverCropPath,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Combines multiple person clusters into [targetPersonId] inside one Room transaction:
     * preserves the preferred name, merges all faces, recomputes the L2-normalized centroid
     * across every face embedding, picks the sharpest cover portrait, and removes absorbed clusters.
     */
    suspend fun mergePersons(
        targetPersonId: String,
        sourcePersonIds: List<String>,
        overrideName: String? = null
    ): String = withContext(Dispatchers.IO) {
        val sourcesToMerge = sourcePersonIds.distinct().filter { it != targetPersonId }
        if (sourcesToMerge.isEmpty()) return@withContext targetPersonId

        database.withTransaction {
            val target = smartDao.getPersonById(targetPersonId) ?: return@withTransaction
            val sourceEntities = sourcesToMerge.mapNotNull { smartDao.getPersonById(it) }

            val resolvedName = overrideName?.trim()?.takeIf { it.isNotEmpty() }
                ?: target.name.takeIf { it.isNotBlank() }
                ?: sourceEntities.firstOrNull { it.name.isNotBlank() }?.name
                ?: ""

            smartDao.reassignPersonFaces(sourcesToMerge, targetPersonId)
            smartDao.deletePersons(sourcesToMerge)

            val allFaces = smartDao.getFacesForPerson(targetPersonId)
            val embeddings = allFaces.map { FaceClusteringEngine.decodeEmbedding(it.embeddingBlob) }
            val mergedCentroid = FaceClusteringEngine.averageNormalizedEmbeddings(embeddings)
            val bestCover = allFaces.firstOrNull { it.faceId == target.coverFaceId }
                ?: allFaces.maxByOrNull { it.qualityScore }

            smartDao.upsertPerson(
                target.copy(
                    name = resolvedName,
                    isFavorite = target.isFavorite || sourceEntities.any { it.isFavorite },
                    isHidden = false,
                    coverFaceId = bestCover?.faceId ?: target.coverFaceId,
                    coverPhotoId = bestCover?.itemId ?: target.coverPhotoId,
                    coverCropPath = bestCover?.cropPath ?: target.coverCropPath,
                    centroidBlob = if (mergedCentroid.isNotEmpty()) {
                        FaceClusteringEngine.encodeEmbedding(mergedCentroid)
                    } else {
                        target.centroidBlob
                    },
                    sampleCount = allFaces.size.coerceAtLeast(1),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
        targetPersonId
    }

    /**
     * Removes misclassified photo(s) from [personId] so they never re-attach automatically,
     * recalculating the person's centroid and cover portrait cleanly (or deleting the cluster if empty).
     */
    suspend fun removePhotosFromPerson(personId: String, photoIds: Collection<String>) =
        withContext(Dispatchers.IO) {
            if (photoIds.isEmpty()) return@withContext
            database.withTransaction {
                smartDao.excludePhotosFromPerson(photoIds.toList(), personId)
                recalculateClusterAfterRemoval(personId)
            }
        }

    /**
     * Moves a single detected face to another person cluster (or creates a brand-new person if
     * [targetPersonId] is null) and recalculates both clusters' centroids.
     */
    suspend fun reassignFaceToPerson(faceId: String, targetPersonId: String?) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val face = smartDao.getFaceById(faceId) ?: return@withTransaction
                val oldPersonId = face.personId
                val destId = targetPersonId ?: run {
                    val createdId = "person_${UUID.randomUUID().toString().take(12)}"
                    smartDao.upsertPerson(
                        PersonClusterEntity(
                            personId = createdId,
                            name = "",
                            isHidden = false,
                            isFavorite = false,
                            coverFaceId = face.faceId,
                            coverPhotoId = face.itemId,
                            coverCropPath = face.cropPath,
                            centroidBlob = face.embeddingBlob,
                            sampleCount = 1,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                    createdId
                }
                smartDao.reassignSingleFace(faceId, destId)
                if (oldPersonId != null && oldPersonId != destId) {
                    recalculateClusterAfterRemoval(oldPersonId)
                }
                recalculateClusterAfterRemoval(destId)
            }
        }

    private suspend fun recalculateClusterAfterRemoval(personId: String) {
        val person = smartDao.getPersonById(personId) ?: return
        val remaining = smartDao.getFacesForPerson(personId)
        if (remaining.isEmpty()) {
            smartDao.deletePersons(listOf(personId))
            return
        }
        val centroid = FaceClusteringEngine.averageNormalizedEmbeddings(
            remaining.map { FaceClusteringEngine.decodeEmbedding(it.embeddingBlob) }
        )
        val cover = remaining.firstOrNull { it.faceId == person.coverFaceId }
            ?: remaining.maxByOrNull { it.qualityScore }
        smartDao.upsertPerson(
            person.copy(
                coverFaceId = cover?.faceId,
                coverPhotoId = cover?.itemId,
                coverCropPath = cover?.cropPath,
                centroidBlob = if (centroid.isNotEmpty()) {
                    FaceClusteringEngine.encodeEmbedding(centroid)
                } else {
                    person.centroidBlob
                },
                sampleCount = remaining.size,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Immediately purges any cached faces, avatar crop JPEGs, and object labels belonging to
     * locked or hidden folders so private vault albums leave zero trace in the AI index.
     */
    suspend fun purgeBlockedItemsFromCache(precomputedBlockedIds: Set<String>? = null) =
        withContext(Dispatchers.IO) {
            val blocked = precomputedBlockedIds ?: computeBlockedFolderIdsFromDb()
            if (blocked.isEmpty()) return@withContext

            val blockedItemIds = HashSet<String>()
            // 1. Blocked Drive media items
            for (entity in driveItemDao.getAllNonTrashedMediaSync()) {
                if (entity.parentId in blocked) {
                    blockedItemIds.add(entity.id)
                }
            }
            // 2. Blocked local media items
            if (localMediaRepository.hasMediaPermission()) {
                for (local in localMediaRepository.getCachedAllMedia()) {
                    if (local.parentId in blocked || HiddenLockedFolderManager.isHiddenFileUri(local.localContentUri)) {
                        blockedItemIds.add(local.id)
                    }
                }
            }
            if (blockedItemIds.isEmpty()) return@withContext

            val affectedPersonIds = HashSet<String>()
            for (chunk in blockedItemIds.chunked(400)) {
                val faces = smartDao.getFacesForPhotos(chunk)
                for (f in faces) {
                    faceEngine.deleteCropFile(f.cropPath)
                    f.personId?.let { affectedPersonIds.add(it) }
                }
                database.withTransaction {
                    smartDao.deleteFacesForPhotos(chunk)
                    smartDao.deleteSmartIndexForPhotos(chunk)
                }
            }
            if (affectedPersonIds.isNotEmpty()) {
                database.withTransaction {
                    for (pid in affectedPersonIds) {
                        recalculateClusterAfterRemoval(pid)
                    }
                }
            }
        }

    /**
     * Computes locked + hidden folder IDs directly from Room AND expands all nested Google Drive
     * descendant folders, guaranteeing locked subtrees are skipped even in background WorkManager runs.
     */
    private suspend fun computeBlockedFolderIdsFromDb(): Set<String> {
        val baseBlocked = (extrasDao.getAllLockedAlbumIds() + extrasDao.getAllHiddenAlbumIds()).toSet()
        if (baseBlocked.isEmpty()) return emptySet()

        val allDriveFolders = driveItemDao.getAllFoldersLightSync()
        if (allDriveFolders.isEmpty()) return baseBlocked

        val parentById = HashMap<String, String?>(allDriveFolders.size)
        for (f in allDriveFolders) parentById[f.id] = f.parentId

        val expanded = HashSet<String>(baseBlocked)
        for (folderId in parentById.keys) {
            if (folderId in expanded) continue
            var cursor = parentById[folderId]
            var depth = 0
            while (cursor != null && depth < 32) {
                if (cursor in baseBlocked) {
                    expanded.add(folderId)
                    break
                }
                cursor = parentById[cursor]
                depth++
            }
        }
        return expanded
    }

    // ── Image Decoding (Local MediaStore + Google Drive Thumbnail) ──

    private suspend fun decodeScanBitmap(item: MediaItem): Bitmap? {
        if (item.isLocal) {
            val uriStr = item.localContentUri ?: return null
            val uri = Uri.parse(uriStr)
            return if (item.isVideo) {
                decodeLocalVideoKeyframe(uri)
            } else {
                decodeLocalBitmapScaled(uri)
            }
        }
        // Google Drive item: prefer offline pinned photo, otherwise fetch crisp =s600 server thumbnail (works for both photos & videos!)
        if (!item.isVideo) {
            val offlinePath = offlineMediaManager.getOfflineFilePath(item.id)
            if (!offlinePath.isNullOrBlank()) {
                val f = File(offlinePath)
                if (f.exists()) return decodeFileScaled(f)
            }
        }
        if (!networkMonitor.isOnline()) return null
        val initialThumbUrl = DriveConstants.buildThumbnailUrl(
            thumbnailLink = item.thumbnailLink,
            fileId = item.id,
            isUnmeteredWifi = true,
            isVideo = item.isVideo,
            sizeBytes = item.sizeBytes,
            requestedMaxPx = 512
        )

        if (initialThumbUrl != null) {
            fetchDriveScanBitmap(initialThumbUrl, skipAuth = false)?.let { return it }
            if (DriveThumbnailFetcher.isGoogleUserContentUrl(initialThumbUrl)) {
                fetchDriveScanBitmap(initialThumbUrl, skipAuth = true)?.let { return it }
            }
        }

        // Self-heal expired Drive thumbnailLink during multi-hour background scans
        val refreshedLink = runCatching {
            driveApi.getFile(fileId = item.id, fields = "id,thumbnailLink")
                .thumbnailLink?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!refreshedLink.isNullOrBlank()) {
            runCatching { driveItemDao.updateThumbnailLink(item.id, refreshedLink) }
            val freshUrl = DriveConstants.buildThumbnailUrl(
                thumbnailLink = refreshedLink,
                fileId = item.id,
                isUnmeteredWifi = true,
                isVideo = item.isVideo,
                sizeBytes = item.sizeBytes,
                requestedMaxPx = 512
            )
            if (freshUrl != null) {
                fetchDriveScanBitmap(freshUrl, skipAuth = false)?.let { return it }
                if (DriveThumbnailFetcher.isGoogleUserContentUrl(freshUrl)) {
                    fetchDriveScanBitmap(freshUrl, skipAuth = true)?.let { return it }
                }
            }
        }
        return null
    }

    private fun fetchDriveScanBitmap(url: String, skipAuth: Boolean): Bitmap? = runCatching {
        val reqBuilder = Request.Builder().url(url).get()
        if (skipAuth) {
            reqBuilder.header(DriveAuthInterceptor.HEADER_SKIP_AUTH, "1")
        }
        okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            val contentType = body.contentType()?.toString()?.lowercase(Locale.US).orEmpty()
            if (DriveThumbnailFetcher.isNonImageContentType(contentType)) return@use null
            val bytes = body.bytes()
            if (bytes.size < 32 || DriveThumbnailFetcher.looksLikeHtml(bytes)) return@use null
            decodeBytesScaled(bytes)
        }
    }.getOrNull()

    private suspend fun purgeItemIdsFromCache(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        val existingFaces = itemIds.chunked(400).flatMap { chunk -> smartDao.getFacesForPhotos(chunk) }
        val affectedPersonIds = existingFaces.mapNotNullTo(HashSet()) { it.personId }
        database.withTransaction {
            itemIds.chunked(400).forEach { chunk ->
                smartDao.deleteFacesForPhotos(chunk)
                smartDao.deleteSmartIndexForPhotos(chunk)
            }
        }
        existingFaces.forEach { faceEngine.deleteCropFile(it.cropPath) }
        val now = System.currentTimeMillis()
        for (pid in affectedPersonIds) {
            val remaining = smartDao.getFacesForPerson(pid)
            if (remaining.isEmpty()) {
                smartDao.deletePersons(listOf(pid))
            } else {
                val person = smartDao.getPersonById(pid) ?: continue
                val cover = remaining.firstOrNull { it.faceId == person.coverFaceId }
                    ?: remaining.maxByOrNull { it.qualityScore }
                smartDao.upsertPerson(
                    person.copy(
                        sampleCount = remaining.size,
                        coverFaceId = cover?.faceId,
                        coverPhotoId = cover?.itemId,
                        coverCropPath = cover?.cropPath,
                        updatedAtEpochMs = now
                    )
                )
            }
        }
    }

    private fun buildActiveScanStatusText(
        isLocalItem: Boolean,
        runningInBackground: Boolean,
        requireWifi: Boolean
    ): String = when {
        runningInBackground && requireWifi -> "Scanning on Wi-Fi · safe to lock screen"
        runningInBackground -> "Scanning in background · safe to lock screen"
        isLocalItem -> "Scanning local photos…"
        else -> "Scanning Google Drive photos…"
    }

    private fun decodeLocalVideoKeyframe(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(640, 640), null)
            }.getOrNull()?.let { return@runCatching it }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    500_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    640,
                    640
                ) ?: retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
            } else {
                retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun decodeLocalBitmapScaled(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val maxDim = maxOf(info.size.width, info.size.height)
                if (maxDim > MAX_SCAN_DIMENSION_PX) {
                    val scale = MAX_SCAN_DIMENSION_PX.toFloat() / maxDim
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_SCAN_DIMENSION_PX)
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val degrees = runCatching {
                context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            }.getOrDefault(0)
            if (degrees != 0) {
                val m = Matrix().apply { postRotate(degrees.toFloat()) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                if (rotated !== raw) raw.recycle()
                rotated
            } else {
                raw
            }
        }
    }.getOrNull()

    private fun decodeFileScaled(file: File): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val maxDim = maxOf(info.size.width, info.size.height)
                if (maxDim > MAX_SCAN_DIMENSION_PX) {
                    val scale = MAX_SCAN_DIMENSION_PX.toFloat() / maxDim
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_SCAN_DIMENSION_PX)
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private fun decodeBytesScaled(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_SCAN_DIMENSION_PX)
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }.getOrNull()

    private fun computeInSampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        var curMax = maxOf(w, h)
        while (curMax / 2 >= maxDim) {
            sample *= 2
            curMax /= 2
        }
        return sample
    }

    private fun scanPriority(item: MediaItem): Int {
        val path = (item.storagePath ?: item.relativePath).orEmpty()
        return when {
            !item.isVideo && path.contains("camera", ignoreCase = true) -> 0
            !item.isVideo && !item.isLocal -> 1 // Connected Google Drive photos right up front!
            !item.isVideo && path.contains("screenshot", ignoreCase = true) -> 3
            !item.isVideo -> 2
            else -> 4 // Scan video keyframes cleanly right after still photos
        }
    }

    private fun isModelDownloadingException(err: Throwable?): Boolean {
        var cur: Throwable? = err
        while (cur != null) {
            if (cur is MlKitException && cur.errorCode == MlKitException.UNAVAILABLE) return true
            val msg = cur.message.orEmpty()
            if (msg.contains("Waiting for the", ignoreCase = true) ||
                msg.contains("model to be downloaded", ignoreCase = true)
            ) return true
            cur = cur.cause
        }
        return false
    }

    /**
     * One-time lightweight upgrade replacing legacy rotated face avatars (which could show black
     * corner wedges) with clean unrotated, strictly in-bounds face-centered circular crops using
     * the normalized face boxes already saved in Room. Preserves all person names, favorites,
     * hidden states, merges, and MobileFaceNet embeddings.
     */
    private suspend fun migrateLegacyRotatedAvatarsIfNeeded(allEligible: List<MediaItem>) {
        if (metaPrefs.getInt(KEY_AVATAR_CROP_VERSION, 1) >= CURRENT_AVATAR_CROP_VERSION) return
        val persons = smartDao.getAllPersons()
        val allFaces = smartDao.getAllActiveFacesSync()
        if (persons.isEmpty() && allFaces.isEmpty()) {
            metaPrefs.edit().putInt(KEY_AVATAR_CROP_VERSION, CURRENT_AVATAR_CROP_VERSION).apply()
            return
        }

        val itemsById = allEligible.associateBy { it.id }
        val coverFaceIds = persons.mapNotNull { it.coverFaceId }.toSet()
        val personsByCoverFaceId = persons.groupBy { it.coverFaceId }
        val personsByCoverPath = persons.groupBy { it.coverCropPath }

        val legacyFaces = allFaces.filter { face ->
            val path = face.cropPath
            path.isNullOrBlank() || !path.endsWith("_v2.jpg")
        }
        if (legacyFaces.isEmpty()) {
            metaPrefs.edit().putInt(KEY_AVATAR_CROP_VERSION, CURRENT_AVATAR_CROP_VERSION).apply()
            return
        }

        // Group by photo so multi-person photos only decode once; process person covers first!
        val groupedByPhoto = legacyFaces.groupBy { it.itemId }
        val orderedPhotoIds = groupedByPhoto.keys.sortedBy { itemId ->
            val hasCover = groupedByPhoto[itemId].orEmpty().any { it.faceId in coverFaceIds }
            if (hasCover) 0 else 1
        }

        var anyCoverDeferred = false
        val now = System.currentTimeMillis()
        for (itemId in orderedPhotoIds) {
            val facesInPhoto = groupedByPhoto[itemId].orEmpty()
            val item = itemsById[itemId] ?: continue
            val bitmap = decodeScanBitmap(item)
            if (bitmap == null) {
                if (facesInPhoto.any { it.faceId in coverFaceIds }) {
                    anyCoverDeferred = true
                }
                continue
            }
            try {
                for (face in facesInPhoto) {
                    val crop = faceEngine.extractAvatarPortraitCropFromNormalizedBox(
                        source = bitmap,
                        bboxLeft = face.bboxLeft,
                        bboxTop = face.bboxTop,
                        bboxRight = face.bboxRight,
                        bboxBottom = face.bboxBottom
                    ) ?: continue
                    val newPath = try {
                        faceEngine.saveAvatarBitmap(crop, "${face.faceId}_v2")
                    } finally {
                        crop.recycle()
                    } ?: continue

                    smartDao.updateFaceCropPath(face.faceId, newPath)
                    val matchingPersons = buildSet {
                        personsByCoverFaceId[face.faceId]?.forEach { add(it.personId) }
                        if (!face.cropPath.isNullOrBlank()) {
                            personsByCoverPath[face.cropPath]?.forEach { add(it.personId) }
                        }
                    }
                    for (pid in matchingPersons) {
                        smartDao.updatePersonCoverCropPath(pid, newPath, now)
                    }
                    if (!face.cropPath.isNullOrBlank() && face.cropPath != newPath) {
                        faceEngine.deleteCropFile(face.cropPath)
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }

        if (!anyCoverDeferred) {
            metaPrefs.edit().putInt(KEY_AVATAR_CROP_VERSION, CURRENT_AVATAR_CROP_VERSION).apply()
        }
    }

    private data class MutableClusterState(
        var entity: PersonClusterEntity,
        var centroid: FloatArray,
        var bestCoverQuality: Float = 0.5f
    )

    companion object {
        private const val PREFS_NAME = "lumina_smart_ai_meta"
        private const val KEY_AI_INDEX_VERSION = "smart_ai_index_version"
        private const val KEY_AVATAR_CROP_VERSION = "smart_avatar_crop_version"
        private const val KEY_REJECTED_MERGE_PAIRS = "rejected_merge_pairs"
        private const val KEY_PRESERVED_PROFILES = "preserved_person_profiles_json"
        private const val CURRENT_AI_INDEX_VERSION = 2
        private const val CURRENT_AVATAR_CROP_VERSION = 2
        private const val MAX_SCAN_DIMENSION_PX = 768
        private const val ML_TIMEOUT_SEC = 10L
        private const val BATCH_WRITE_SIZE = 20
        private const val BLOCKED_REFRESH_INTERVAL = 30
        private const val PERIODIC_CONSOLIDATE_INTERVAL = 400
        private const val ORPHAN_PRUNE_THRESHOLD = 100

        /**
         * Returns ONLY newly added or modified items compared to the persisted `(itemId -> stamp)`
         * map in Room, so 30,000+ already-scanned photos are skipped in milliseconds.
         */
        internal fun filterUnindexedItems(
            eligible: List<MediaItem>,
            existingStamps: Map<String, Long>
        ): List<MediaItem> = eligible.filter { item ->
            val prev = existingStamps[item.id]
            prev == null || prev != item.createdTimeEpochMs
        }

        internal fun shouldStopScanForWifi(
            requireWifiThroughout: Boolean,
            isUnmeteredWifi: Boolean
        ): Boolean = requireWifiThroughout && !isUnmeteredWifi
    }
}

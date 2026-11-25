package com.luminagallery.drive.data.repository.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.luminagallery.drive.data.local.GalleryDatabase
import com.luminagallery.drive.data.local.HiddenLockedFolderManager
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.entity.DetectedFaceEntity
import com.luminagallery.drive.data.local.entity.PersonClusterEntity
import com.luminagallery.drive.data.local.entity.PhotoSmartIndexEntity
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.repository.DriveMapper
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

/**
 * 100% on-device People (Face Recognition + Clustering) & Objects/Scenes AI indexing engine:
 *  - Scans BOTH on-device local photos AND connected Google Drive photo thumbnails (`=s640`).
 *  - STRICTLY skips all locked & hidden albums (and every nested Drive descendant folder) BEFORE
 *    decoding or downloading a single byte, and automatically purges cached faces/labels if an
 *    album is later locked.
 *  - Powers Samsung-style People avatars, naming, combining/merging clusters, hiding strangers,
 *    removing misclassified faces, choosing cover portraits, and bilingual EN+NL object search.
 */
class SmartRecognitionManager(
    private val context: Context,
    private val database: GalleryDatabase,
    private val localMediaRepository: LocalMediaRepository,
    private val offlineMediaManager: OfflineMediaManager,
    private val okHttpClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor
) {
    private val smartDao = database.smartRecognitionDao()
    private val extrasDao = database.galleryExtrasDao()
    private val driveItemDao = database.driveItemDao()
    private val faceEngine = FaceEmbeddingEngine(context)

    private val scanMutex = Mutex()
    @Volatile
    private var pauseRequested: Boolean = false

    private val _progress = MutableStateFlow(SmartIndexProgress())
    val progress: StateFlow<SmartIndexProgress> = _progress.asStateFlow()

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
            .setConfidenceThreshold(0.62f)
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
     * Scans unindexed local & connected Google Drive photos for faces + objects/scenes,
     * strictly excluding all locked & hidden albums (and their Drive descendants).
     */
    suspend fun syncSmartIndex(
        maxNewItemsPerRun: Int = 120,
        includeDrive: Boolean = true
    ) = withContext(Dispatchers.IO) {
        if (!scanMutex.tryLock()) return@withContext
        pauseRequested = false
        try {
            val blockedFolderIds = computeBlockedFolderIdsFromDb()

            // Gather eligible local photos (skipping locked, hidden, and hidden-on-disk vault files)
            val localCandidates = if (localMediaRepository.hasMediaPermission()) {
                localMediaRepository.getCachedAllMedia().ifEmpty {
                    runCatching { localMediaRepository.observeAllLocalMedia().first() }.getOrDefault(emptyList())
                }.filter { item ->
                    item.isLocal &&
                        !item.isVideo &&
                        !item.isRaw &&
                        !item.localContentUri.isNullOrBlank() &&
                        item.parentId !in blockedFolderIds &&
                        !HiddenLockedFolderManager.isHiddenFileUri(item.localContentUri)
                }
            } else {
                emptyList()
            }

            // Gather eligible connected Google Drive photos (skipping locked & hidden Drive subtrees)
            val driveCandidates = if (includeDrive) {
                driveItemDao.getAllNonTrashedMediaSync()
                    .asSequence()
                    .map(DriveMapper::entityToMediaItem)
                    .filter { item ->
                        !item.isVideo &&
                            item.parentId !in blockedFolderIds &&
                            (!item.thumbnailLink.isNullOrBlank() || offlineMediaManager.getOfflineFilePath(item.id) != null)
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

            val existingIndex = smartDao.getAllSmartIndex().associateByTo(HashMap()) { it.itemId }
            val unindexedAll = allEligible.filter { item ->
                val prev = existingIndex[item.id]
                prev == null || prev.dateModifiedMs != item.createdTimeEpochMs
            }

            val totalEligible = allEligible.size
            val alreadyIndexed = (totalEligible - unindexedAll.size).coerceAtLeast(0)
            if (unindexedAll.isEmpty()) {
                _progress.value = SmartIndexProgress(
                    isIndexing = false,
                    scannedInRun = 0,
                    totalInRun = 0,
                    totalIndexedPhotos = totalEligible,
                    totalEligiblePhotos = totalEligible,
                    remainingPhotos = 0
                )
                return@withContext
            }

            val batchItems = unindexedAll.take(maxNewItemsPerRun)
            val isOnline = networkMonitor.isOnline()
            val runQueue = if (isOnline) {
                batchItems
            } else {
                batchItems.filter { it.isLocal || offlineMediaManager.getOfflineFilePath(it.id) != null }
            }

            if (runQueue.isEmpty()) {
                _progress.value = SmartIndexProgress(
                    isIndexing = false,
                    scannedInRun = 0,
                    totalInRun = 0,
                    totalIndexedPhotos = alreadyIndexed,
                    totalEligiblePhotos = totalEligible,
                    remainingPhotos = unindexedAll.size,
                    statusText = "Connect to Wi-Fi/internet to scan Google Drive photos"
                )
                return@withContext
            }

            val inMemoryClusters = smartDao.getAllPersons()
                .associateByTo(LinkedHashMap()) { it.personId }
                .mapValuesTo(LinkedHashMap()) { (_, entity) ->
                    MutableClusterState(
                        entity = entity,
                        centroid = FaceClusteringEngine.decodeEmbedding(entity.centroidBlob)
                    )
                }

            var scanned = 0
            val totalRun = runQueue.size
            _progress.value = SmartIndexProgress(
                isIndexing = true,
                scannedInRun = 0,
                totalInRun = totalRun,
                totalIndexedPhotos = alreadyIndexed,
                totalEligiblePhotos = totalEligible,
                remainingPhotos = unindexedAll.size,
                statusText = "Scanning people & objects…"
            )

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

            for (item in runQueue) {
                if (pauseRequested) break

                // Re-verify privacy lock before every item in case user locked an album mid-scan
                val curLocked = extrasDao.getAllLockedAlbumIds().toSet()
                if (item.parentId in curLocked) {
                    scanned++
                    continue
                }

                val bitmap = decodeScanBitmap(item)
                if (bitmap == null) {
                    // Do NOT cache permanent empty result on transient network/decode failure
                    scanned++
                    _progress.value = SmartIndexProgress(
                        isIndexing = true,
                        scannedInRun = scanned,
                        totalInRun = totalRun,
                        totalIndexedPhotos = alreadyIndexed + scanned,
                        totalEligiblePhotos = totalEligible,
                        remainingPhotos = (unindexedAll.size - scanned).coerceAtLeast(0),
                        statusText = if (item.isLocal) "Scanning local photos…" else "Scanning Google Drive photos…"
                    )
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
                        _progress.value = SmartIndexProgress(
                            isIndexing = false,
                            scannedInRun = scanned,
                            totalInRun = totalRun,
                            totalIndexedPhotos = alreadyIndexed + scanned,
                            totalEligiblePhotos = totalEligible,
                            remainingPhotos = (unindexedAll.size - scanned).coerceAtLeast(0),
                            isDownloadingModels = true,
                            statusText = "Downloading on-device AI models via Google Play Services…"
                        )
                        return@withContext
                    }

                    val detectedFaces = facesResult.getOrDefault(emptyList())
                    val rawLabels = labelsResult.getOrDefault(emptyList())
                        .map { it.text to it.confidence }

                    val usefulLabels = SmartObjectVocabulary.filterUsefulLabels(rawLabels)
                    val searchTokens = SmartObjectVocabulary.buildSearchTokens(usefulLabels)
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
                            labelsCsv = usefulLabels.joinToString(","),
                            labelSearchTokens = searchTokens,
                            isDriveItem = !item.isLocal,
                            indexedAtEpochMs = now
                        )
                    )

                    scanned++
                    if (pendingIndexBatch.size >= BATCH_WRITE_SIZE) {
                        flushPending()
                    }
                    _progress.value = SmartIndexProgress(
                        isIndexing = true,
                        scannedInRun = scanned,
                        totalInRun = totalRun,
                        totalIndexedPhotos = alreadyIndexed + scanned,
                        totalEligiblePhotos = totalEligible,
                        remainingPhotos = (unindexedAll.size - scanned).coerceAtLeast(0),
                        statusText = if (item.isLocal) "Scanning local photos…" else "Scanning Google Drive photos…"
                    )
                } finally {
                    bitmap.recycle()
                }
            }

            flushPending()

            val finalRemaining = (unindexedAll.size - scanned).coerceAtLeast(0)
            _progress.value = SmartIndexProgress(
                isIndexing = false,
                scannedInRun = scanned,
                totalInRun = totalRun,
                totalIndexedPhotos = (alreadyIndexed + scanned).coerceAtMost(totalEligible),
                totalEligiblePhotos = totalEligible,
                remainingPhotos = finalRemaining,
                statusText = null
            )
        } catch (ce: CancellationException) {
            _progress.value = _progress.value.copy(isIndexing = false)
            throw ce
        } catch (_: Throwable) {
            _progress.value = _progress.value.copy(isIndexing = false)
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
        if (!FaceClusteringEngine.isFaceUsableForRecognition(
                faceWidthPx = w,
                faceHeightPx = h,
                imageWidthPx = bitmap.width.toFloat(),
                eulerY = face.headEulerAngleY,
                eulerX = face.headEulerAngleX
            )
        ) {
            return null
        }

        val embedding = faceEngine.computeEmbedding(bitmap, face) ?: return null
        if (embedding.isEmpty()) return null

        val faceId = "${item.id}_f$faceIndex"
        val cropPath = faceEngine.saveAvatarCrop(bitmap, face, faceId)
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
                sampleCount = it.entity.sampleCount
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
            return decodeLocalBitmapScaled(Uri.parse(uriStr))
        }
        // Google Drive item: prefer offline pinned file, otherwise fetch crisp =s640 thumbnail
        val offlinePath = offlineMediaManager.getOfflineFilePath(item.id)
        if (!offlinePath.isNullOrBlank()) {
            val f = File(offlinePath)
            if (f.exists()) return decodeFileScaled(f)
        }
        if (!networkMonitor.isOnline()) return null
        val thumbUrl = DriveConstants.buildThumbnailUrl(
            thumbnailLink = item.thumbnailLink,
            fileId = item.id,
            isUnmeteredWifi = true,
            isVideo = item.isVideo,
            sizeBytes = item.sizeBytes
        ) ?: return null

        return runCatching {
            val req = Request.Builder().url(thumbUrl).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                if (bytes.isEmpty()) return@use null
                decodeBytesScaled(bytes)
            }
        }.getOrNull()
    }

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
            path.contains("camera", ignoreCase = true) -> 0
            !item.isLocal -> 1 // Connected Google Drive albums right up front!
            path.contains("screenshot", ignoreCase = true) -> 3
            else -> 2
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

    private data class MutableClusterState(
        var entity: PersonClusterEntity,
        var centroid: FloatArray,
        var bestCoverQuality: Float = 0.5f
    )

    companion object {
        private const val MAX_SCAN_DIMENSION_PX = 768
        private const val ML_TIMEOUT_SEC = 10L
        private const val BATCH_WRITE_SIZE = 10
    }
}

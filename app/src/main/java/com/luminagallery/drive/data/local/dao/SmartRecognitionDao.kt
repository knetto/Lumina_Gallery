package com.luminagallery.drive.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.luminagallery.drive.data.local.entity.DetectedFaceEntity
import com.luminagallery.drive.data.local.entity.PersonClusterEntity
import com.luminagallery.drive.data.local.entity.PhotoSmartIndexEntity
import com.luminagallery.drive.data.local.entity.SavedPersonAnchorEntity
import com.luminagallery.drive.data.local.entity.SavedPersonMemoryEntity
import com.luminagallery.drive.data.local.entity.SavedRejectedMergeEntity
import kotlinx.coroutines.flow.Flow

data class IndexedItemStampRow(
    val itemId: String,
    val dateModifiedMs: Long
)

/**
 * Lightweight face projection omitting the 768-byte `embeddingBlob` so People, PersonDetail,
 * Search, and Viewer load 10–20× faster with near-zero SQLite / GC overhead.
 */
data class LightFaceRow(
    val faceId: String,
    val itemId: String,
    val personId: String?,
    val qualityScore: Float,
    val cropPath: String?
)

@Dao
interface SmartRecognitionDao {

    // Person clusters
    @Query("SELECT * FROM person_clusters ORDER BY isFavorite DESC, CASE WHEN name != '' THEN 0 ELSE 1 END ASC, sampleCount DESC, name COLLATE NOCASE ASC")
    fun observeAllPersons(): Flow<List<PersonClusterEntity>>

    @Query("SELECT * FROM person_clusters")
    suspend fun getAllPersons(): List<PersonClusterEntity>

    @Query("SELECT * FROM person_clusters WHERE personId = :personId LIMIT 1")
    suspend fun getPersonById(personId: String): PersonClusterEntity?

    @Query("SELECT * FROM person_clusters WHERE personId = :personId LIMIT 1")
    fun observePersonById(personId: String): Flow<PersonClusterEntity?>

    @Upsert
    suspend fun upsertPerson(person: PersonClusterEntity)

    @Upsert
    suspend fun upsertPersons(persons: List<PersonClusterEntity>)

    @Query("UPDATE person_clusters SET name = :name, updatedAtEpochMs = :updatedAt WHERE personId = :personId")
    suspend fun renamePerson(personId: String, name: String, updatedAt: Long)

    @Query("UPDATE person_clusters SET isHidden = :hidden, updatedAtEpochMs = :updatedAt WHERE personId = :personId")
    suspend fun setPersonHidden(personId: String, hidden: Boolean, updatedAt: Long)

    @Query("UPDATE person_clusters SET isFavorite = :favorite, updatedAtEpochMs = :updatedAt WHERE personId = :personId")
    suspend fun setPersonFavorite(personId: String, favorite: Boolean, updatedAt: Long)

    @Query("UPDATE person_clusters SET memoryId = :memoryId, updatedAtEpochMs = :updatedAt WHERE personId = :personId")
    suspend fun updatePersonMemoryId(personId: String, memoryId: String?, updatedAt: Long)

    @Query("DELETE FROM person_clusters WHERE personId IN (:personIds)")
    suspend fun deletePersons(personIds: List<String>)

    @Query("DELETE FROM person_clusters")
    suspend fun clearAllPersons()

    // Detected faces
    @Query("SELECT * FROM detected_faces WHERE isExcludedByUser = 0")
    fun observeAllActiveFaces(): Flow<List<DetectedFaceEntity>>

    @Query("SELECT faceId, itemId, personId, qualityScore, cropPath FROM detected_faces WHERE isExcludedByUser = 0")
    fun observeAllActiveLightFaces(): Flow<List<LightFaceRow>>

    @Query("SELECT faceId, itemId, personId, qualityScore, cropPath FROM detected_faces WHERE isExcludedByUser = 0")
    suspend fun getAllActiveLightFacesSync(): List<LightFaceRow>

    @Query("SELECT * FROM detected_faces WHERE isExcludedByUser = 0")
    suspend fun getAllActiveFacesSync(): List<DetectedFaceEntity>

    @Query("UPDATE detected_faces SET cropPath = :cropPath WHERE faceId = :faceId")
    suspend fun updateFaceCropPath(faceId: String, cropPath: String)

    @Query("UPDATE person_clusters SET coverCropPath = :cropPath, updatedAtEpochMs = :updatedAt WHERE personId = :personId")
    suspend fun updatePersonCoverCropPath(personId: String, cropPath: String, updatedAt: Long)

    @Query("SELECT * FROM detected_faces WHERE personId = :personId AND isExcludedByUser = 0 ORDER BY qualityScore DESC")
    fun observeFacesForPerson(personId: String): Flow<List<DetectedFaceEntity>>

    @Query("SELECT faceId, itemId, personId, qualityScore, cropPath FROM detected_faces WHERE personId = :personId AND isExcludedByUser = 0 ORDER BY qualityScore DESC")
    fun observeLightFacesForPerson(personId: String): Flow<List<LightFaceRow>>

    @Query("SELECT * FROM detected_faces WHERE personId = :personId AND isExcludedByUser = 0")
    suspend fun getFacesForPerson(personId: String): List<DetectedFaceEntity>

    @Query("SELECT * FROM detected_faces WHERE itemId = :itemId AND isExcludedByUser = 0")
    fun observeFacesForPhoto(itemId: String): Flow<List<DetectedFaceEntity>>

    @Query("SELECT * FROM detected_faces WHERE itemId IN (:itemIds)")
    suspend fun getFacesForPhotos(itemIds: List<String>): List<DetectedFaceEntity>

    @Query("SELECT * FROM detected_faces WHERE faceId = :faceId LIMIT 1")
    suspend fun getFaceById(faceId: String): DetectedFaceEntity?

    @Upsert
    suspend fun upsertFaces(faces: List<DetectedFaceEntity>)

    @Query("UPDATE detected_faces SET personId = :targetPersonId WHERE personId IN (:sourcePersonIds) AND isExcludedByUser = 0")
    suspend fun reassignPersonFaces(sourcePersonIds: List<String>, targetPersonId: String)

    @Query("UPDATE detected_faces SET personId = :newPersonId, isExcludedByUser = 0 WHERE faceId = :faceId")
    suspend fun reassignSingleFace(faceId: String, newPersonId: String)

    @Query("UPDATE detected_faces SET personId = NULL, isExcludedByUser = 1 WHERE faceId = :faceId")
    suspend fun excludeFaceFromPerson(faceId: String)

    @Query("UPDATE detected_faces SET personId = NULL, isExcludedByUser = 1 WHERE itemId IN (:itemIds) AND personId = :personId")
    suspend fun excludePhotosFromPerson(itemIds: List<String>, personId: String)

    @Query("DELETE FROM detected_faces WHERE itemId IN (:itemIds)")
    suspend fun deleteFacesForPhotos(itemIds: List<String>)

    @Query("DELETE FROM detected_faces")
    suspend fun clearAllFaces()

    // Smart photo index (object/scene tags + scan markers)
    @Query("SELECT * FROM photo_smart_index")
    fun observeAllSmartIndex(): Flow<List<PhotoSmartIndexEntity>>

    @Query("SELECT * FROM photo_smart_index")
    suspend fun getAllSmartIndex(): List<PhotoSmartIndexEntity>

    @Query("SELECT itemId, dateModifiedMs FROM photo_smart_index")
    suspend fun getIndexedItemStamps(): List<IndexedItemStampRow>

    @Query("SELECT * FROM photo_smart_index WHERE itemId = :itemId LIMIT 1")
    fun observeSmartIndexForPhoto(itemId: String): Flow<PhotoSmartIndexEntity?>

    @Query("SELECT * FROM photo_smart_index WHERE itemId = :itemId LIMIT 1")
    suspend fun getSmartIndexForPhoto(itemId: String): PhotoSmartIndexEntity?

    @Upsert
    suspend fun upsertSmartIndex(entries: List<PhotoSmartIndexEntity>)

    @Query("DELETE FROM photo_smart_index WHERE itemId IN (:itemIds)")
    suspend fun deleteSmartIndexForPhotos(itemIds: List<String>)

    @Query("DELETE FROM photo_smart_index")
    suspend fun clearAllSmartIndex()

    // Durable Saved Person Memory (survives AI detector/embedding/threshold rebuilds)
    @Query("SELECT * FROM saved_person_memory ORDER BY updatedAtEpochMs DESC")
    fun observeAllSavedPersonMemories(): Flow<List<SavedPersonMemoryEntity>>

    @Query("SELECT * FROM saved_person_memory")
    suspend fun getAllSavedPersonMemories(): List<SavedPersonMemoryEntity>

    @Query("SELECT * FROM saved_person_memory WHERE memoryId = :memoryId LIMIT 1")
    suspend fun getSavedPersonMemoryById(memoryId: String): SavedPersonMemoryEntity?

    @Upsert
    suspend fun upsertSavedPersonMemory(memory: SavedPersonMemoryEntity)

    @Upsert
    suspend fun upsertSavedPersonMemories(memories: List<SavedPersonMemoryEntity>)

    @Query("DELETE FROM saved_person_memory WHERE memoryId IN (:memoryIds)")
    suspend fun deleteSavedPersonMemories(memoryIds: List<String>)

    @Query("DELETE FROM saved_person_memory")
    suspend fun clearAllSavedPersonMemories()

    @Query("UPDATE saved_person_memory SET coverItemId = NULL, coverBboxLeft = NULL, coverBboxTop = NULL, coverBboxRight = NULL, coverBboxBottom = NULL, updatedAtEpochMs = :updatedAt WHERE coverItemId IN (:itemIds)")
    suspend fun clearSavedCoverForItems(itemIds: List<String>, updatedAt: Long)

    // Durable face-location anchors
    @Query("SELECT * FROM saved_person_anchors")
    fun observeAllSavedPersonAnchors(): Flow<List<SavedPersonAnchorEntity>>

    @Query("SELECT * FROM saved_person_anchors")
    suspend fun getAllSavedPersonAnchors(): List<SavedPersonAnchorEntity>

    @Query("SELECT * FROM saved_person_anchors WHERE memoryId = :memoryId")
    suspend fun getAnchorsForMemory(memoryId: String): List<SavedPersonAnchorEntity>

    @Query("SELECT * FROM saved_person_anchors WHERE memoryId IN (:memoryIds)")
    suspend fun getAnchorsForMemories(memoryIds: List<String>): List<SavedPersonAnchorEntity>

    @Upsert
    suspend fun upsertSavedPersonAnchors(anchors: List<SavedPersonAnchorEntity>)

    @Query("DELETE FROM saved_person_anchors WHERE anchorId IN (:anchorIds)")
    suspend fun deleteAnchorsByIds(anchorIds: List<String>)

    @Query("DELETE FROM saved_person_anchors WHERE memoryId = :memoryId")
    suspend fun deleteAnchorsForMemory(memoryId: String)

    @Query("DELETE FROM saved_person_anchors WHERE memoryId IN (:memoryIds)")
    suspend fun deleteAnchorsForMemories(memoryIds: List<String>)

    @Query("DELETE FROM saved_person_anchors WHERE itemId IN (:itemIds)")
    suspend fun deleteAnchorsForItems(itemIds: List<String>)

    @Query("DELETE FROM saved_person_anchors")
    suspend fun clearAllSavedPersonAnchors()

    // Durable "Not the same person" rejections
    @Query("SELECT * FROM saved_rejected_merges")
    fun observeAllRejectedMerges(): Flow<List<SavedRejectedMergeEntity>>

    @Query("SELECT * FROM saved_rejected_merges")
    suspend fun getAllRejectedMerges(): List<SavedRejectedMergeEntity>

    @Upsert
    suspend fun upsertRejectedMerge(entry: SavedRejectedMergeEntity)

    @Upsert
    suspend fun upsertRejectedMerges(entries: List<SavedRejectedMergeEntity>)

    @Query("DELETE FROM saved_rejected_merges WHERE pairKey = :pairKey")
    suspend fun deleteRejectedMergeByPairKey(pairKey: String)

    @Query("DELETE FROM saved_rejected_merges WHERE memoryIdA = :memoryId OR memoryIdB = :memoryId")
    suspend fun deleteRejectedMergesForMemory(memoryId: String)

    @Query("DELETE FROM saved_rejected_merges")
    suspend fun clearAllRejectedMerges()
}

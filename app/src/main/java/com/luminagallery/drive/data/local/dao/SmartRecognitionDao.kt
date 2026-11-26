package com.luminagallery.drive.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.luminagallery.drive.data.local.entity.DetectedFaceEntity
import com.luminagallery.drive.data.local.entity.PersonClusterEntity
import com.luminagallery.drive.data.local.entity.PhotoSmartIndexEntity
import kotlinx.coroutines.flow.Flow

data class IndexedItemStampRow(
    val itemId: String,
    val dateModifiedMs: Long
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

    @Query("DELETE FROM person_clusters WHERE personId IN (:personIds)")
    suspend fun deletePersons(personIds: List<String>)

    @Query("DELETE FROM person_clusters")
    suspend fun clearAllPersons()

    // Detected faces
    @Query("SELECT * FROM detected_faces WHERE isExcludedByUser = 0")
    fun observeAllActiveFaces(): Flow<List<DetectedFaceEntity>>

    @Query("SELECT * FROM detected_faces WHERE isExcludedByUser = 0")
    suspend fun getAllActiveFacesSync(): List<DetectedFaceEntity>

    @Query("UPDATE detected_faces SET cropPath = :cropPath WHERE faceId = :faceId")
    suspend fun updateFaceCropPath(faceId: String, cropPath: String)

    @Query("UPDATE person_clusters SET coverCropPath = :cropPath, updatedAtEpochMs = :updatedAt WHERE personId = :personId")
    suspend fun updatePersonCoverCropPath(personId: String, cropPath: String, updatedAt: Long)

    @Query("SELECT * FROM detected_faces WHERE personId = :personId AND isExcludedByUser = 0 ORDER BY qualityScore DESC")
    fun observeFacesForPerson(personId: String): Flow<List<DetectedFaceEntity>>

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
}

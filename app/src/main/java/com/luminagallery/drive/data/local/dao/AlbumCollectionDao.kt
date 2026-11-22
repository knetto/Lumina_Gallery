package com.luminagallery.drive.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.luminagallery.drive.data.local.entity.AlbumCollectionEntity
import com.luminagallery.drive.data.local.entity.AlbumLayoutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumCollectionDao {

    @Query("SELECT * FROM album_collections ORDER BY createdAtEpochMs ASC")
    fun observeAllCollections(): Flow<List<AlbumCollectionEntity>>

    @Query("SELECT * FROM album_collections")
    suspend fun getAllCollections(): List<AlbumCollectionEntity>

    @Query("SELECT * FROM album_collections WHERE id = :id LIMIT 1")
    suspend fun getCollectionById(id: String): AlbumCollectionEntity?

    @Upsert
    suspend fun upsertCollection(collection: AlbumCollectionEntity)

    @Query("UPDATE album_collections SET name = :newName WHERE id = :collectionId")
    suspend fun renameCollection(collectionId: String, newName: String)

    @Query("UPDATE album_collections SET parentCollectionId = :newParentId WHERE id = :collectionId")
    suspend fun updateCollectionParent(collectionId: String, newParentId: String?)

    @Query("UPDATE album_collections SET parentCollectionId = :newParentId WHERE parentCollectionId = :oldParentId")
    suspend fun reparentChildCollections(oldParentId: String, newParentId: String?)

    @Query("DELETE FROM album_collections WHERE id = :collectionId")
    suspend fun deleteCollectionById(collectionId: String)

    @Query("SELECT * FROM album_layout ORDER BY sortOrder ASC")
    fun observeAllLayouts(): Flow<List<AlbumLayoutEntity>>

    @Query("SELECT * FROM album_layout")
    suspend fun getAllLayouts(): List<AlbumLayoutEntity>

    @Query("SELECT * FROM album_layout WHERE itemId = :itemId LIMIT 1")
    suspend fun getLayout(itemId: String): AlbumLayoutEntity?

    @Upsert
    suspend fun upsertLayout(layout: AlbumLayoutEntity)

    @Upsert
    suspend fun upsertLayouts(layouts: List<AlbumLayoutEntity>)

    @Query("UPDATE album_layout SET parentCollectionId = :newParentId WHERE parentCollectionId = :oldParentId")
    suspend fun reparentChildLayouts(oldParentId: String, newParentId: String?)

    @Query("DELETE FROM album_layout WHERE itemId = :itemId")
    suspend fun deleteLayout(itemId: String)
}

package com.luminagallery.drive.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.luminagallery.drive.data.local.entity.FavoriteItemEntity
import com.luminagallery.drive.data.local.entity.FavoriteStoryEntity
import com.luminagallery.drive.data.local.entity.HiddenAlbumEntity
import com.luminagallery.drive.data.local.entity.IgnoredCleanOutEntity
import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GalleryExtrasDao {

    @Query("SELECT itemId FROM favorite_items")
    fun observeFavoriteItemIds(): Flow<List<String>>

    @Upsert
    suspend fun upsertFavoriteItem(item: FavoriteItemEntity)

    @Query("DELETE FROM favorite_items WHERE itemId = :itemId")
    suspend fun removeFavoriteItem(itemId: String)

    @Query("SELECT albumId FROM hidden_albums")
    fun observeHiddenAlbumIds(): Flow<List<String>>

    @Upsert
    suspend fun upsertHiddenAlbum(album: HiddenAlbumEntity)

    @Query("DELETE FROM hidden_albums WHERE albumId = :albumId")
    suspend fun removeHiddenAlbum(albumId: String)

    @Query("SELECT storyId FROM favorite_stories")
    fun observeFavoriteStoryIds(): Flow<List<String>>

    @Upsert
    suspend fun upsertFavoriteStory(story: FavoriteStoryEntity)

    @Query("DELETE FROM favorite_stories WHERE storyId = :storyId")
    suspend fun removeFavoriteStory(storyId: String)

    @Query("SELECT itemId FROM ignored_cleanout_items")
    fun observeIgnoredCleanOutIds(): Flow<List<String>>

    @Upsert
    suspend fun upsertIgnoredCleanOutItems(items: List<IgnoredCleanOutEntity>)

    @Query("DELETE FROM ignored_cleanout_items")
    suspend fun clearAllIgnoredCleanOut()

    @Query("SELECT * FROM photo_location_cache")
    suspend fun getAllCachedLocations(): List<PhotoLocationEntity>

    @Upsert
    suspend fun upsertPhotoLocations(items: List<PhotoLocationEntity>)
}

package com.luminagallery.drive.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.luminagallery.drive.data.local.entity.AlbumViewPrefEntity
import com.luminagallery.drive.data.local.entity.CustomAlbumCoverEntity
import com.luminagallery.drive.data.local.entity.FavoriteItemEntity
import com.luminagallery.drive.data.local.entity.FavoriteStoryEntity
import com.luminagallery.drive.data.local.entity.HiddenAlbumEntity
import com.luminagallery.drive.data.local.entity.IgnoredCleanOutEntity
import com.luminagallery.drive.data.local.entity.MediaAnnotationEntity
import com.luminagallery.drive.data.local.entity.OfflinePinnedEntity
import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import com.luminagallery.drive.data.local.entity.PhotoOcrEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GalleryExtrasDao {

    @Query("SELECT itemId FROM favorite_items")
    fun observeFavoriteItemIds(): Flow<List<String>>

    @Query("SELECT itemId FROM favorite_items")
    suspend fun getAllFavoriteItemIds(): List<String>

    @Upsert
    suspend fun upsertFavoriteItem(item: FavoriteItemEntity)

    @Upsert
    suspend fun upsertFavoriteItems(items: List<FavoriteItemEntity>)

    @Query("DELETE FROM favorite_items WHERE itemId = :itemId")
    suspend fun removeFavoriteItem(itemId: String)

    @Query("DELETE FROM favorite_items WHERE itemId NOT LIKE 'local_%'")
    suspend fun clearDriveFavoriteItems()

    @Query("SELECT albumId FROM hidden_albums")
    fun observeHiddenAlbumIds(): Flow<List<String>>

    @Query("SELECT albumId FROM hidden_albums")
    suspend fun getAllHiddenAlbumIds(): List<String>

    @Upsert
    suspend fun upsertHiddenAlbum(album: HiddenAlbumEntity)

    @Upsert
    suspend fun upsertHiddenAlbums(albums: List<HiddenAlbumEntity>)

    @Query("DELETE FROM hidden_albums WHERE albumId = :albumId")
    suspend fun removeHiddenAlbum(albumId: String)

    @Query("DELETE FROM hidden_albums")
    suspend fun clearAllHiddenAlbums()

    @Query("SELECT storyId FROM favorite_stories")
    fun observeFavoriteStoryIds(): Flow<List<String>>

    @Query("SELECT storyId FROM favorite_stories")
    suspend fun getAllFavoriteStoryIds(): List<String>

    @Upsert
    suspend fun upsertFavoriteStory(story: FavoriteStoryEntity)

    @Upsert
    suspend fun upsertFavoriteStories(stories: List<FavoriteStoryEntity>)

    @Query("DELETE FROM favorite_stories WHERE storyId = :storyId")
    suspend fun removeFavoriteStory(storyId: String)

    @Query("DELETE FROM favorite_stories")
    suspend fun clearAllFavoriteStories()

    @Query("SELECT itemId FROM ignored_cleanout_items")
    fun observeIgnoredCleanOutIds(): Flow<List<String>>

    @Upsert
    suspend fun upsertIgnoredCleanOutItems(items: List<IgnoredCleanOutEntity>)

    @Query("DELETE FROM ignored_cleanout_items")
    suspend fun clearAllIgnoredCleanOut()

    @Query("SELECT * FROM photo_location_cache")
    suspend fun getAllCachedLocations(): List<PhotoLocationEntity>

    @Query("SELECT * FROM photo_location_cache WHERE hasLocation = 1")
    fun observeCachedLocations(): Flow<List<PhotoLocationEntity>>

    @Upsert
    suspend fun upsertPhotoLocations(items: List<PhotoLocationEntity>)

    // Custom album covers
    @Query("SELECT * FROM custom_album_covers")
    fun observeCustomAlbumCovers(): Flow<List<CustomAlbumCoverEntity>>

    @Query("SELECT * FROM custom_album_covers")
    suspend fun getAllCustomAlbumCovers(): List<CustomAlbumCoverEntity>

    @Upsert
    suspend fun upsertCustomAlbumCover(cover: CustomAlbumCoverEntity)

    @Upsert
    suspend fun upsertCustomAlbumCovers(covers: List<CustomAlbumCoverEntity>)

    @Query("DELETE FROM custom_album_covers WHERE albumId = :albumId")
    suspend fun deleteCustomAlbumCover(albumId: String)

    @Query("DELETE FROM custom_album_covers")
    suspend fun clearAllCustomAlbumCovers()

    // Photo tags & notes
    @Query("SELECT * FROM media_annotations")
    fun observeAllAnnotations(): Flow<List<MediaAnnotationEntity>>

    @Query("SELECT * FROM media_annotations")
    suspend fun getAllAnnotations(): List<MediaAnnotationEntity>

    @Query("SELECT * FROM media_annotations WHERE itemId = :itemId LIMIT 1")
    fun observeAnnotation(itemId: String): Flow<MediaAnnotationEntity?>

    @Upsert
    suspend fun upsertAnnotation(annotation: MediaAnnotationEntity)

    @Upsert
    suspend fun upsertAnnotations(annotations: List<MediaAnnotationEntity>)

    @Query("DELETE FROM media_annotations WHERE itemId = :itemId")
    suspend fun deleteAnnotation(itemId: String)

    @Query("DELETE FROM media_annotations")
    suspend fun clearAllAnnotations()

    // Offline pinned Drive media
    @Query("SELECT * FROM offline_pinned_items")
    fun observeAllOfflineItems(): Flow<List<OfflinePinnedEntity>>

    @Query("SELECT * FROM offline_pinned_items WHERE folderId = :folderId")
    suspend fun getOfflineItemsByFolder(folderId: String): List<OfflinePinnedEntity>

    @Query("SELECT * FROM offline_pinned_items WHERE itemId = :itemId LIMIT 1")
    suspend fun getOfflineItem(itemId: String): OfflinePinnedEntity?

    @Upsert
    suspend fun upsertOfflineItem(item: OfflinePinnedEntity)

    @Query("DELETE FROM offline_pinned_items WHERE itemId = :itemId")
    suspend fun deleteOfflineItem(itemId: String)

    @Query("DELETE FROM offline_pinned_items WHERE folderId = :folderId")
    suspend fun deleteOfflineItemsByFolder(folderId: String)

    @Query("DELETE FROM offline_pinned_items")
    suspend fun clearAllOfflineItems()

    // Offline on-device OCR text cache
    @Query("SELECT * FROM photo_ocr_cache")
    suspend fun getAllOcrEntries(): List<PhotoOcrEntity>

    @Query("SELECT * FROM photo_ocr_cache WHERE hasText = 1")
    fun observeOcrTextEntries(): Flow<List<PhotoOcrEntity>>

    @Query("SELECT * FROM photo_ocr_cache WHERE itemId = :itemId LIMIT 1")
    suspend fun getOcrEntry(itemId: String): PhotoOcrEntity?

    @Upsert
    suspend fun upsertOcrEntry(entry: PhotoOcrEntity)

    @Upsert
    suspend fun upsertOcrEntries(entries: List<PhotoOcrEntity>)

    // Per-album sort order & grouping preferences
    @Query("SELECT * FROM album_view_prefs WHERE albumId = :albumId LIMIT 1")
    fun observeAlbumViewPref(albumId: String): Flow<AlbumViewPrefEntity?>

    @Query("SELECT * FROM album_view_prefs WHERE albumId = :albumId LIMIT 1")
    suspend fun getAlbumViewPref(albumId: String): AlbumViewPrefEntity?

    @Query("SELECT * FROM album_view_prefs")
    fun observeAllAlbumViewPrefs(): Flow<List<AlbumViewPrefEntity>>

    @Query("SELECT * FROM album_view_prefs")
    suspend fun getAllAlbumViewPrefs(): List<AlbumViewPrefEntity>

    @Upsert
    suspend fun upsertAlbumViewPref(pref: AlbumViewPrefEntity)

    @Upsert
    suspend fun upsertAlbumViewPrefs(prefs: List<AlbumViewPrefEntity>)

    @Query("DELETE FROM album_view_prefs")
    suspend fun clearAllAlbumViewPrefs()
}

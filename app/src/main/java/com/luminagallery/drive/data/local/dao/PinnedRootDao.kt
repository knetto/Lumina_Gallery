package com.luminagallery.drive.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.luminagallery.drive.data.local.entity.PinnedRootEntity
import kotlinx.coroutines.flow.Flow

data class PinnedRootWithStatsRow(
    val folderId: String,
    val displayName: String,
    val modifiedTimeEpochMs: Long,
    val subfolderCount: Int,
    val mediaCount: Int,
    val coverThumbnailLink: String?,
    val coverItemId: String?
)

@Dao
interface PinnedRootDao {

    @Query(
        """
        SELECT
            p.folderId,
            p.displayName,
            COALESCE(f.modifiedTimeEpochMs, p.pinnedAtEpochMs) AS modifiedTimeEpochMs,
            (SELECT COUNT(*) FROM drive_items c
             WHERE c.parentId = p.folderId AND c.isFolder = 1 AND c.trashed = 0) AS subfolderCount,
            (SELECT COUNT(*) FROM drive_items m
             WHERE m.parentId = p.folderId AND m.isFolder = 0 AND m.trashed = 0) AS mediaCount,
            COALESCE(
                (SELECT m.thumbnailLink FROM drive_items m
                 WHERE m.parentId = p.folderId AND m.isFolder = 0 AND m.trashed = 0
                 ORDER BY m.createdTimeEpochMs DESC LIMIT 1),
                (SELECT m2.thumbnailLink FROM drive_items m2
                 INNER JOIN drive_items sf ON m2.parentId = sf.id
                 WHERE sf.parentId = p.folderId AND m2.isFolder = 0 AND m2.trashed = 0
                 ORDER BY m2.createdTimeEpochMs DESC LIMIT 1)
            ) AS coverThumbnailLink,
            COALESCE(
                (SELECT m.id FROM drive_items m
                 WHERE m.parentId = p.folderId AND m.isFolder = 0 AND m.trashed = 0
                 ORDER BY m.createdTimeEpochMs DESC LIMIT 1),
                (SELECT m2.id FROM drive_items m2
                 INNER JOIN drive_items sf ON m2.parentId = sf.id
                 WHERE sf.parentId = p.folderId AND m2.isFolder = 0 AND m2.trashed = 0
                 ORDER BY m2.createdTimeEpochMs DESC LIMIT 1)
            ) AS coverItemId
        FROM pinned_roots p
        LEFT JOIN drive_items f ON f.id = p.folderId
        ORDER BY p.sortOrder ASC, p.pinnedAtEpochMs ASC
        """
    )
    fun observePinnedRootsWithStats(): Flow<List<PinnedRootWithStatsRow>>

    @Query("SELECT * FROM pinned_roots ORDER BY sortOrder ASC, pinnedAtEpochMs ASC")
    suspend fun getAll(): List<PinnedRootEntity>

    @Query("SELECT folderId FROM pinned_roots")
    fun observePinnedIds(): Flow<List<String>>

    @Upsert
    suspend fun upsert(root: PinnedRootEntity)

    @Query("DELETE FROM pinned_roots WHERE folderId = :folderId")
    suspend fun deleteById(folderId: String)

    @Query("UPDATE pinned_roots SET lastSyncedEpochMs = :timestamp WHERE folderId = :folderId")
    suspend fun updateLastSynced(folderId: String, timestamp: Long)
}

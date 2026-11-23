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
            0 AS subfolderCount,
            0 AS mediaCount,
            NULL AS coverThumbnailLink,
            NULL AS coverItemId
        FROM pinned_roots p
        LEFT JOIN drive_items f ON f.id = p.folderId
        ORDER BY p.sortOrder ASC, p.pinnedAtEpochMs ASC
        """
    )
    fun observePinnedRootsWithStats(): Flow<List<PinnedRootWithStatsRow>>

    @Query("SELECT * FROM pinned_roots ORDER BY sortOrder ASC, pinnedAtEpochMs ASC")
    suspend fun getAll(): List<PinnedRootEntity>

    @Query("SELECT * FROM pinned_roots ORDER BY sortOrder ASC, pinnedAtEpochMs ASC")
    fun observeAll(): Flow<List<PinnedRootEntity>>

    @Query("SELECT folderId FROM pinned_roots")
    fun observePinnedIds(): Flow<List<String>>

    @Upsert
    suspend fun upsert(root: PinnedRootEntity)

    @Query("DELETE FROM pinned_roots WHERE folderId = :folderId")
    suspend fun deleteById(folderId: String)

    @Query("DELETE FROM pinned_roots")
    suspend fun clearAll()

    @Query("UPDATE pinned_roots SET lastSyncedEpochMs = :timestamp WHERE folderId = :folderId")
    suspend fun updateLastSynced(folderId: String, timestamp: Long)
}

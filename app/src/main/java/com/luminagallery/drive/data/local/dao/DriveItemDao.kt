package com.luminagallery.drive.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.luminagallery.drive.data.local.entity.DriveItemEntity
import kotlinx.coroutines.flow.Flow

data class FolderWithStatsRow(
    val id: String,
    val parentId: String?,
    val name: String,
    val modifiedTimeEpochMs: Long,
    val subfolderCount: Int,
    val mediaCount: Int,
    val coverThumbnailLink: String?,
    val coverItemId: String?,
    val isPinned: Boolean
)

data class AncestorRow(
    val id: String,
    val name: String
)

data class LightFolderRow(
    val id: String,
    val parentId: String?,
    val name: String,
    val modifiedTimeEpochMs: Long
)

data class ParentCoverSummaryRow(
    val parentId: String,
    val mediaCount: Int,
    val coverItemId: String,
    val coverThumbnailLink: String?,
    val coverMediaType: String,
    val coverSizeBytes: Long,
    val latestEpochMs: Long
)

@Dao
interface DriveItemDao {

    @Query(
        """
        SELECT
            f.id,
            f.parentId,
            f.name,
            f.modifiedTimeEpochMs,
            0 AS subfolderCount,
            0 AS mediaCount,
            NULL AS coverThumbnailLink,
            NULL AS coverItemId,
            CASE WHEN EXISTS(SELECT 1 FROM pinned_roots p WHERE p.folderId = f.id)
                 THEN 1 ELSE 0 END AS isPinned
        FROM drive_items f
        WHERE f.parentId = :parentId AND f.isFolder = 1 AND f.trashed = 0
        ORDER BY f.name COLLATE NOCASE ASC
        """
    )
    fun observeSubfoldersWithStats(parentId: String): Flow<List<FolderWithStatsRow>>

    @Query(
        """
        SELECT * FROM drive_items
        WHERE parentId = :parentId AND isFolder = 0 AND trashed = 0
        ORDER BY createdTimeEpochMs DESC, name COLLATE NOCASE ASC
        """
    )
    fun observeMediaInFolder(parentId: String): Flow<List<DriveItemEntity>>

    @Query(
        """
        WITH RECURSIVE descendants(id, depth) AS (
            SELECT :rootFolderId, 0
            UNION ALL
            SELECT d.id, descendants.depth + 1
            FROM drive_items d
            INNER JOIN descendants ON d.parentId = descendants.id
            WHERE d.isFolder = 1 AND d.trashed = 0 AND descendants.depth < 32
        )
        SELECT m.* FROM drive_items m
        WHERE m.parentId IN (SELECT id FROM descendants)
          AND m.isFolder = 0
          AND m.trashed = 0
        ORDER BY m.createdTimeEpochMs DESC, m.name COLLATE NOCASE ASC
        """
    )
    fun observeFlattenedMediaRecursive(rootFolderId: String): Flow<List<DriveItemEntity>>

    @Query(
        """
        SELECT * FROM drive_items
        WHERE isFolder = 0
          AND trashed = 0
          AND (:query = '' OR name LIKE '%' || :query || '%' COLLATE NOCASE)
          AND (:mediaType IS NULL OR mediaType = :mediaType)
          AND (:fromEpochMs IS NULL OR createdTimeEpochMs >= :fromEpochMs)
          AND (:toEpochMs IS NULL OR createdTimeEpochMs <= :toEpochMs)
        ORDER BY createdTimeEpochMs DESC, name COLLATE NOCASE ASC
        """
    )
    fun searchMedia(
        query: String,
        mediaType: String?,
        fromEpochMs: Long?,
        toEpochMs: Long?
    ): Flow<List<DriveItemEntity>>

    @Query(
        """
        WITH RECURSIVE descendants(id, depth) AS (
            SELECT :rootFolderId, 0
            UNION ALL
            SELECT d.id, descendants.depth + 1
            FROM drive_items d
            INNER JOIN descendants ON d.parentId = descendants.id
            WHERE descendants.depth < 32
        )
        SELECT id FROM descendants
        """
    )
    suspend fun getSubtreeItemIds(rootFolderId: String): List<String>

    @Query("SELECT id FROM drive_items WHERE isFolder = 1 AND trashed = 0")
    suspend fun getAllFolderIds(): List<String>

    @Query("SELECT id FROM drive_items")
    suspend fun getAllItemIds(): List<String>

    @Query(
        """
        WITH RECURSIVE ancestors(id, parentId, name, depth) AS (
            SELECT id, parentId, name, 0
            FROM drive_items
            WHERE id = :folderId
            UNION ALL
            SELECT d.id, d.parentId, d.name, ancestors.depth + 1
            FROM drive_items d
            INNER JOIN ancestors ON d.id = ancestors.parentId
            WHERE ancestors.depth < 32
        )
        SELECT id, name FROM ancestors ORDER BY depth DESC
        """
    )
    suspend fun getAncestorChain(folderId: String): List<AncestorRow>

    @Query("SELECT * FROM drive_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DriveItemEntity?

    @Query("SELECT thumbnailLink FROM drive_items WHERE id = :id LIMIT 1")
    suspend fun getThumbnailLink(id: String): String?

    @Query("UPDATE drive_items SET thumbnailLink = :thumbnailLink WHERE id = :id")
    suspend fun updateThumbnailLink(id: String, thumbnailLink: String?)

    @Query("SELECT id FROM drive_items WHERE parentId = :parentId")
    suspend fun getChildIdsInParent(parentId: String): List<String>

    @Query("SELECT COUNT(*) FROM drive_items WHERE isFolder = 0 AND trashed = 0")
    fun observeCachedMediaCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM drive_items WHERE isFolder = 1 AND trashed = 0")
    fun observeCachedFolderCount(): Flow<Int>

    @Query(
        """
        SELECT id, parentId, name, modifiedTimeEpochMs
        FROM drive_items
        WHERE isFolder = 1 AND trashed = 0
        ORDER BY name COLLATE NOCASE ASC
        """
    )
    fun observeAllFoldersLight(): Flow<List<LightFolderRow>>

    @Query(
        """
        SELECT id, parentId, name, modifiedTimeEpochMs
        FROM drive_items
        WHERE isFolder = 1 AND trashed = 0
        """
    )
    suspend fun getAllFoldersLightSync(): List<LightFolderRow>

    @Query(
        """
        SELECT * FROM drive_items
        WHERE isFolder = 0 AND trashed = 0
        ORDER BY createdTimeEpochMs DESC
        """
    )
    suspend fun getAllNonTrashedMediaSync(): List<DriveItemEntity>

    @Query(
        """
        SELECT
            parentId,
            COUNT(*) AS mediaCount,
            id AS coverItemId,
            thumbnailLink AS coverThumbnailLink,
            mediaType AS coverMediaType,
            sizeBytes AS coverSizeBytes,
            MAX(
                CASE
                    WHEN thumbnailLink IS NOT NULL AND thumbnailLink != '' AND mediaType != 'VIDEO'
                        THEN createdTimeEpochMs + 200000000000000
                    WHEN thumbnailLink IS NOT NULL AND thumbnailLink != ''
                        THEN createdTimeEpochMs + 100000000000000
                    ELSE createdTimeEpochMs
                END
            ) AS latestEpochMs
        FROM drive_items
        WHERE isFolder = 0 AND trashed = 0 AND parentId IS NOT NULL
        GROUP BY parentId
        """
    )
    fun observeParentCoverSummaries(): Flow<List<ParentCoverSummaryRow>>

    @Query("SELECT COUNT(*) FROM drive_items WHERE parentId = :parentId AND trashed = 0")
    suspend fun getChildCount(parentId: String): Int

    @Upsert
    suspend fun upsertAll(items: List<DriveItemEntity>)

    @Upsert
    suspend fun upsert(item: DriveItemEntity)

    @Query("DELETE FROM drive_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM drive_items")
    suspend fun clearAll()
}

package com.luminagallery.drive.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a recognized person cluster (named or unnamed) computed 100% locally on-device.
 */
@Entity(
    tableName = "person_clusters",
    indices = [
        Index(value = ["isHidden"]),
        Index(value = ["isFavorite"]),
        Index(value = ["memoryId"])
    ]
)
data class PersonClusterEntity(
    @PrimaryKey
    val personId: String,
    val name: String = "",
    val isHidden: Boolean = false,
    val isFavorite: Boolean = false,
    val coverFaceId: String? = null,
    val coverPhotoId: String? = null,
    val coverCropPath: String? = null,
    val centroidBlob: ByteArray,
    val sampleCount: Int = 1,
    val updatedAtEpochMs: Long,
    val memoryId: String? = null,
    val entityKind: String = KIND_HUMAN
) {
    val isPet: Boolean get() = entityKind != KIND_HUMAN

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersonClusterEntity) return false
        return personId == other.personId &&
            name == other.name &&
            isHidden == other.isHidden &&
            isFavorite == other.isFavorite &&
            coverFaceId == other.coverFaceId &&
            coverPhotoId == other.coverPhotoId &&
            coverCropPath == other.coverCropPath &&
            centroidBlob.contentEquals(other.centroidBlob) &&
            sampleCount == other.sampleCount &&
            updatedAtEpochMs == other.updatedAtEpochMs &&
            memoryId == other.memoryId &&
            entityKind == other.entityKind
    }

    override fun hashCode(): Int {
        var result = personId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + isHidden.hashCode()
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + (coverFaceId?.hashCode() ?: 0)
        result = 31 * result + (coverPhotoId?.hashCode() ?: 0)
        result = 31 * result + (coverCropPath?.hashCode() ?: 0)
        result = 31 * result + centroidBlob.contentHashCode()
        result = 31 * result + sampleCount
        result = 31 * result + updatedAtEpochMs.hashCode()
        result = 31 * result + (memoryId?.hashCode() ?: 0)
        result = 31 * result + entityKind.hashCode()
        return result
    }

    companion object {
        const val KIND_HUMAN = "HUMAN"
        const val KIND_PET_DOG = "PET_DOG"
        const val KIND_PET_CAT = "PET_CAT"
    }
}

/**
 * Individual face detection inside a local or Google Drive photo with its L2-normalized
 * MobileFaceNet embedding and cropped circular avatar file.
 */
@Entity(
    tableName = "detected_faces",
    indices = [
        Index(value = ["itemId"]),
        Index(value = ["personId"])
    ]
)
data class DetectedFaceEntity(
    @PrimaryKey
    val faceId: String,
    val itemId: String,
    val personId: String?,
    val embeddingBlob: ByteArray,
    val bboxLeft: Float,
    val bboxTop: Float,
    val bboxRight: Float,
    val bboxBottom: Float,
    val qualityScore: Float,
    val cropPath: String?,
    val isExcludedByUser: Boolean = false,
    val detectedAtEpochMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetectedFaceEntity) return false
        return faceId == other.faceId &&
            itemId == other.itemId &&
            personId == other.personId &&
            embeddingBlob.contentEquals(other.embeddingBlob) &&
            bboxLeft == other.bboxLeft &&
            bboxTop == other.bboxTop &&
            bboxRight == other.bboxRight &&
            bboxBottom == other.bboxBottom &&
            qualityScore == other.qualityScore &&
            cropPath == other.cropPath &&
            isExcludedByUser == other.isExcludedByUser &&
            detectedAtEpochMs == other.detectedAtEpochMs
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + itemId.hashCode()
        result = 31 * result + (personId?.hashCode() ?: 0)
        result = 31 * result + embeddingBlob.contentHashCode()
        result = 31 * result + bboxLeft.hashCode()
        result = 31 * result + bboxTop.hashCode()
        result = 31 * result + bboxRight.hashCode()
        result = 31 * result + bboxBottom.hashCode()
        result = 31 * result + qualityScore.hashCode()
        result = 31 * result + (cropPath?.hashCode() ?: 0)
        result = 31 * result + isExcludedByUser.hashCode()
        result = 31 * result + detectedAtEpochMs.hashCode()
        return result
    }
}

/**
 * Per-photo cache tracking scanned object/scene tags (with bilingual EN+NL search tokens)
 * and face scan completion so each photo is only analyzed once.
 */
@Entity(
    tableName = "photo_smart_index",
    indices = [
        Index(value = ["faceCount"]),
        Index(value = ["isDriveItem"])
    ]
)
data class PhotoSmartIndexEntity(
    @PrimaryKey
    val itemId: String,
    val dateModifiedMs: Long,
    val faceCount: Int,
    val labelsCsv: String,
    val labelSearchTokens: String,
    val isDriveItem: Boolean,
    val indexedAtEpochMs: Long
)

/**
 * Durable user-curated person identity (name, favorite, hidden state, custom cover, user-combined
 * flag). Stored separately from the rebuildable AI cluster cache so changing face detectors,
 * embeddings, or thresholds NEVER forgets names or combined people.
 */
@Entity(
    tableName = "saved_person_memory",
    indices = [
        Index(value = ["name"])
    ]
)
data class SavedPersonMemoryEntity(
    @PrimaryKey
    val memoryId: String,
    val name: String = "",
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isUserMerged: Boolean = false,
    val coverItemId: String? = null,
    val coverBboxLeft: Float? = null,
    val coverBboxTop: Float? = null,
    val coverBboxRight: Float? = null,
    val coverBboxBottom: Float? = null,
    val updatedAtEpochMs: Long,
    val entityKind: String = PersonClusterEntity.KIND_HUMAN
)

/**
 * Durable face-location anchor linking a specific face box inside a photo (`itemId` + normalized
 * bounding box) to a [SavedPersonMemoryEntity]. Supports both positive anchors (so named and
 * user-combined groups automatically re-attach & re-merge after any AI rebuild) and negative
 * exclusion anchors (so removed wrong faces stay excluded forever).
 */
@Entity(
    tableName = "saved_person_anchors",
    indices = [
        Index(value = ["memoryId"]),
        Index(value = ["itemId"])
    ]
)
data class SavedPersonAnchorEntity(
    @PrimaryKey
    val anchorId: String,
    val memoryId: String,
    val itemId: String,
    val bboxLeft: Float,
    val bboxTop: Float,
    val bboxRight: Float,
    val bboxBottom: Float,
    val originGroupId: String,
    val isUserAssigned: Boolean = false,
    val isExcluded: Boolean = false,
    val qualityScore: Float = 0.5f,
    val updatedAtEpochMs: Long
)

/**
 * Durable record of two remembered people the user explicitly marked as "Not the same person",
 * surviving AI rebuilds so they are never auto-merged or suggested together again.
 */
@Entity(tableName = "saved_rejected_merges")
data class SavedRejectedMergeEntity(
    @PrimaryKey
    val pairKey: String,
    val memoryIdA: String,
    val memoryIdB: String,
    val createdAtEpochMs: Long
)

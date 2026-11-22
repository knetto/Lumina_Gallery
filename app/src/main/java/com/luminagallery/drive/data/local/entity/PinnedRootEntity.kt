package com.luminagallery.drive.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pinned_roots")
data class PinnedRootEntity(
    @PrimaryKey
    val folderId: String,
    val displayName: String,
    val pinnedAtEpochMs: Long,
    val sortOrder: Int = 0,
    val lastSyncedEpochMs: Long? = null
)

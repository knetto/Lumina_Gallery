package com.luminagallery.drive.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val changesStartPageToken: String? = null,
    val lastFullSyncEpochMs: Long? = null,
    val lastDeltaSyncEpochMs: Long? = null,
    val initialScanComplete: Boolean = false
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

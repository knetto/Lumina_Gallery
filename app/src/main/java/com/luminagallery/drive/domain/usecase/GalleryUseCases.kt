package com.luminagallery.drive.domain.usecase

import android.net.Uri
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.data.repository.MediaExportManager
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.MediaItem

class SyncGalleryUseCase(
    private val repository: GalleryRepository
) {
    suspend fun syncDeltaOrInitial(forceFullRescan: Boolean = false): Result<Unit> =
        repository.performSmartSync(forceFullRescan = forceFullRescan)

    suspend fun syncFolder(folderId: String): Result<List<String>> =
        repository.syncFolderChildren(folderId)

    suspend fun syncFolderTree(rootFolderId: String): Result<Unit> =
        repository.syncFolderTree(rootFolderId)
}

class ManageRootsUseCase(
    private val repository: GalleryRepository
) {
    suspend fun pinByUrlOrId(urlOrId: String, alias: String? = null): Result<DriveFolder> =
        repository.pinRootFolderByUrlOrId(urlOrId, alias)

    suspend fun discoverSharedFolders(): Result<List<DriveFolder>> =
        repository.discoverSharedRootFolders()

    suspend fun togglePin(folder: DriveFolder): Boolean =
        repository.togglePinFolder(folder)

    suspend fun unpin(folderId: String) =
        repository.unpinRootFolder(folderId)
}

class ExportMediaUseCase(
    private val exportManager: MediaExportManager
) {
    suspend fun prepareShareUris(
        items: List<MediaItem>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<List<Uri>> = exportManager.prepareShareUris(items, onProgress)

    suspend fun saveToDevice(
        items: List<MediaItem>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<Int> = exportManager.saveToDeviceGallery(items, onProgress)
}

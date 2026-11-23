package com.luminagallery.drive.ui.screens.collection

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.luminagallery.drive.AppContainer
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.local.entity.AlbumCollectionEntity
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.repository.AlbumOrganizationRepository
import com.luminagallery.drive.domain.model.AlbumGridItem
import com.luminagallery.drive.domain.model.AlbumSortMode
import com.luminagallery.drive.domain.model.BreadcrumbItem
import com.luminagallery.drive.domain.usecase.ManageRootsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class CollectionUiState(
    val collectionId: String = "",
    val collectionName: String = "",
    val parentCollectionId: String? = null,
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val items: List<AlbumGridItem> = emptyList(),
    val allCollections: List<AlbumCollectionEntity> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isUnmeteredWifi: Boolean = true,
    val snackbarMessage: String? = null,
    val wasUngrouped: Boolean = false
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

class CollectionViewModel(
    private val collectionId: String,
    initialCollectionName: String,
    private val organizationRepository: AlbumOrganizationRepository,
    private val preferencesManager: PreferencesManager,
    private val manageRootsUseCase: ManageRootsUseCase,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    private val collectionNameFlow = MutableStateFlow(initialCollectionName)
    private val parentIdFlow = MutableStateFlow<String?>(null)
    private val breadcrumbsFlow = MutableStateFlow(listOf(BreadcrumbItem(collectionId, initialCollectionName)))
    private val selectedIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val snackbarFlow = MutableStateFlow<String?>(null)
    private val ungroupedFlow = MutableStateFlow(false)

    private val metaFlow = combine(
        collectionNameFlow,
        parentIdFlow,
        breadcrumbsFlow,
        selectedIdsFlow,
        combine(snackbarFlow, ungroupedFlow) { snack, ungrouped -> snack to ungrouped }
    ) { name, parentId, crumbs, selected, (snack, ungrouped) ->
        CollectionMeta(name, parentId, crumbs, selected, snack, ungrouped)
    }

    val uiState: StateFlow<CollectionUiState> = combine(
        organizationRepository.observeGridItems(collectionId),
        organizationRepository.observeAllCollections(),
        networkMonitor.networkState,
        metaFlow
    ) { items, allCols, net, meta ->
        val latestName = allCols.firstOrNull { it.id == collectionId }?.name ?: meta.name
        CollectionUiState(
            collectionId = collectionId,
            collectionName = latestName,
            parentCollectionId = meta.parentId,
            breadcrumbs = meta.breadcrumbs,
            items = items,
            allCollections = allCols,
            selectedIds = meta.selectedIds,
            isUnmeteredWifi = net.isUnmeteredWifi,
            snackbarMessage = meta.snackbar,
            wasUngrouped = meta.ungrouped
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CollectionUiState(
            collectionId = collectionId,
            collectionName = initialCollectionName,
            breadcrumbs = listOf(BreadcrumbItem(collectionId, initialCollectionName)),
            items = organizationRepository.getCachedGridItems(collectionId),
            isUnmeteredWifi = networkMonitor.networkState.value.isUnmeteredWifi
        )
    )

    init {
        refreshMetadata()
    }

    private fun refreshMetadata() {
        viewModelScope.launch {
            parentIdFlow.value = organizationRepository.getCollectionParentId(collectionId)
            val chain = organizationRepository.getCollectionBreadcrumbs(collectionId)
            if (chain.isNotEmpty()) {
                breadcrumbsFlow.value = chain
            }
        }
    }

    fun saveReorder(ordered: List<AlbumGridItem>) {
        viewModelScope.launch {
            if (preferencesManager.latestPreferences.albumSortMode != AlbumSortMode.CUSTOM) {
                preferencesManager.setAlbumSortMode(AlbumSortMode.CUSTOM)
            }
            organizationRepository.saveReorder(collectionId, ordered)
        }
    }

    fun groupItemsIntoNewSubCollection(
        dragged: AlbumGridItem,
        target: AlbumGridItem,
        name: String
    ) {
        viewModelScope.launch {
            organizationRepository.createCollectionFromPair(
                parentCollectionId = collectionId,
                draggedItem = dragged,
                targetItem = target,
                collectionName = name
            ).onSuccess {
                snackbarFlow.value = "Created collection \"${name.trim().ifBlank { "New Collection" }}\""
            }
        }
    }

    fun moveItemIntoCollection(item: AlbumGridItem, targetCollectionId: String?) {
        viewModelScope.launch {
            val moved = organizationRepository.moveItemIntoCollection(item, targetCollectionId)
            if (moved) {
                snackbarFlow.value = if (targetCollectionId == null) {
                    "Moved \"${item.displayName}\" to Albums"
                } else {
                    "Moved \"${item.displayName}\""
                }
            } else {
                snackbarFlow.value = "Cannot move a collection inside itself"
            }
        }
    }

    fun moveItemOutToParent(item: AlbumGridItem) {
        moveItemIntoCollection(item, uiState.value.parentCollectionId)
    }

    fun createSubCollection(name: String) {
        viewModelScope.launch {
            organizationRepository.createEmptyCollection(collectionId, name)
            snackbarFlow.value = "Created collection \"${name.trim().ifBlank { "New Collection" }}\""
        }
    }

    fun renameCollection(targetCollectionId: String, newName: String) {
        viewModelScope.launch {
            organizationRepository.renameCollection(targetCollectionId, newName)
            if (targetCollectionId == collectionId) {
                collectionNameFlow.value = newName.trim()
                refreshMetadata()
            }
            snackbarFlow.value = "Renamed to \"${newName.trim()}\""
        }
    }

    fun ungroupCollection(targetCollectionId: String) {
        viewModelScope.launch {
            organizationRepository.ungroupCollection(targetCollectionId)
            if (targetCollectionId == collectionId) {
                ungroupedFlow.value = true
            } else {
                snackbarFlow.value = "Ungrouped collection"
            }
        }
    }

    fun unpinDriveAlbum(folderId: String, folderName: String) {
        viewModelScope.launch {
            manageRootsUseCase.unpin(folderId)
            snackbarFlow.value = "Removed \"$folderName\" from gallery"
        }
    }

    fun toggleSelection(item: AlbumGridItem) {
        selectedIdsFlow.value = if (item.itemId in selectedIdsFlow.value) {
            selectedIdsFlow.value - item.itemId
        } else {
            selectedIdsFlow.value + item.itemId
        }
    }

    fun clearSelection() {
        selectedIdsFlow.value = emptySet()
    }

    fun groupSelectedIntoSubCollection(name: String) {
        val selected = uiState.value.items.filter { it.itemId in selectedIdsFlow.value }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            organizationRepository.createCollectionFromItems(
                parentCollectionId = collectionId,
                items = selected,
                collectionName = name
            ).onSuccess {
                selectedIdsFlow.value = emptySet()
                snackbarFlow.value = "Created collection \"${name.trim().ifBlank { "New Collection" }}\""
            }
        }
    }

    fun moveSelectedOutToParent() {
        val selected = uiState.value.items.filter { it.itemId in selectedIdsFlow.value }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val targetParent = uiState.value.parentCollectionId
            selected.forEach { organizationRepository.moveItemIntoCollection(it, targetParent) }
            selectedIdsFlow.value = emptySet()
            snackbarFlow.value = if (targetParent == null) "Moved to Albums" else "Moved up one level"
        }
    }

    fun moveSelectedIntoCollection(targetCollectionId: String) {
        val selected = uiState.value.items.filter { it.itemId in selectedIdsFlow.value }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            var count = 0
            selected.forEach {
                if (organizationRepository.moveItemIntoCollection(it, targetCollectionId)) count++
            }
            selectedIdsFlow.value = emptySet()
            if (count > 0) snackbarFlow.value = "Moved $count item(s)"
        }
    }

    fun unpinSelectedDriveAlbums() {
        val selected = uiState.value.items
            .filterIsInstance<AlbumGridItem.AlbumEntry>()
            .filter { !it.folder.isLocal && it.itemId in selectedIdsFlow.value }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            selected.forEach { manageRootsUseCase.unpin(it.folder.id) }
            selectedIdsFlow.value = emptySet()
            snackbarFlow.value = "Removed ${selected.size} Drive album(s)"
        }
    }

    fun clearSnackbar() {
        snackbarFlow.value = null
    }

    private data class CollectionMeta(
        val name: String,
        val parentId: String?,
        val breadcrumbs: List<BreadcrumbItem>,
        val selectedIds: Set<String>,
        val snackbar: String?,
        val ungrouped: Boolean
    )

    class Factory(
        private val collectionId: String,
        private val collectionName: String,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CollectionViewModel(
            collectionId = collectionId,
            initialCollectionName = collectionName,
            organizationRepository = container.albumOrganizationRepository,
            preferencesManager = container.preferencesManager,
            manageRootsUseCase = container.manageRootsUseCase,
            networkMonitor = container.networkMonitor
        ) as T
    }
}

package com.luminagallery.drive.ui.screens.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MoveUp
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.domain.model.AlbumGridItem
import com.luminagallery.drive.ui.components.AlbumSelectionActionBar
import com.luminagallery.drive.ui.components.DraggableAlbumGrid
import com.luminagallery.drive.ui.components.LuminaSnackbarHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenAlbum: (folderId: String, folderName: String) -> Unit,
    onOpenSubCollection: (collectionId: String, collectionName: String) -> Unit,
    onOpenBreadcrumbCollection: (collectionId: String, collectionName: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showTopMenu by remember { mutableStateOf(false) }
    var showCreateSubColDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingGroupPair by remember { mutableStateOf<Pair<AlbumGridItem, AlbumGridItem>?>(null) }
    var showGroupSelectedDialog by remember { mutableStateOf(false) }
    var showMoveSelectedSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    LaunchedEffect(uiState.wasUngrouped) {
        if (uiState.wasUngrouped) onNavigateBack()
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { LuminaSnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = uiState.collectionName,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${uiState.items.size} ${if (uiState.items.size == 1) "item" else "items"} · Drag to reorder or group",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showCreateSubColDialog = true }) {
                            Icon(Icons.Rounded.CreateNewFolder, contentDescription = "New nested collection")
                        }
                        Box {
                            IconButton(onClick = { showTopMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Collection options")
                            }
                            DropdownMenu(
                                expanded = showTopMenu,
                                onDismissRequest = { showTopMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename collection") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        renameTarget = uiState.collectionId to uiState.collectionName
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Ungroup collection") },
                                    leadingIcon = { Icon(Icons.Rounded.Unarchive, contentDescription = null) },
                                    onClick = {
                                        showTopMenu = false
                                        viewModel.ungroupCollection(uiState.collectionId)
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )

                // Collection-in-Collection Breadcrumb Trail
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item(key = "col_crumb_root") {
                        Text(
                            text = "Albums",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onNavigateHome)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    itemsIndexed(
                        items = uiState.breadcrumbs,
                        key = { _, c -> "col_crumb_${c.id}" }
                    ) { idx, crumb ->
                        val isLast = idx == uiState.breadcrumbs.lastIndex
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isLast) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isLast) {
                                        onOpenBreadcrumbCollection(crumb.id, crumb.name)
                                    }
                            ) {
                                Text(
                                    text = crumb.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isLast) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Collection is empty",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Drag albums onto this collection from Albums, or tap + above to create a nested collection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                DraggableAlbumGrid(
                    items = uiState.items,
                    isUnmeteredWifi = uiState.isUnmeteredWifi,
                    selectedIds = uiState.selectedIds,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = if (uiState.isSelectionMode) 112.dp else 32.dp
                    ),
                    onItemClick = { clicked ->
                        when (clicked) {
                            is AlbumGridItem.AlbumEntry ->
                                onOpenAlbum(clicked.folder.id, clicked.folder.name)
                            is AlbumGridItem.CollectionEntry ->
                                onOpenSubCollection(clicked.id, clicked.name)
                        }
                    },
                    onToggleSelect = viewModel::toggleSelection,
                    onReorderCommitted = viewModel::saveReorder,
                    onDropToGroup = { dragged, target ->
                        if (target is AlbumGridItem.CollectionEntry) {
                            viewModel.moveItemIntoCollection(dragged, target.id)
                        } else {
                            pendingGroupPair = dragged to target
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            val selectedItems = remember(uiState.items, uiState.selectedIds) {
                uiState.items.filter { it.itemId in uiState.selectedIds }
            }
            val singleSelectedCol = selectedItems.singleOrNull() as? AlbumGridItem.CollectionEntry
            val hasDrive = selectedItems.any { it is AlbumGridItem.AlbumEntry && !it.folder.isLocal }
            val moveTargets = remember(uiState.allCollections, uiState.selectedIds, uiState.collectionId) {
                uiState.allCollections.filter { it.id != uiState.collectionId && it.id !in uiState.selectedIds }
            }

            AlbumSelectionActionBar(
                visible = uiState.isSelectionMode,
                selectedCount = uiState.selectedIds.size,
                canGroup = uiState.selectedIds.size >= 2,
                canMoveOut = true,
                canMoveIntoExisting = moveTargets.isNotEmpty(),
                canRename = singleSelectedCol != null,
                canUngroup = singleSelectedCol != null,
                canRemoveDrive = hasDrive,
                onGroup = { showGroupSelectedDialog = true },
                onMoveOut = viewModel::moveSelectedOutToParent,
                onMoveIntoExisting = { showMoveSelectedSheet = true },
                onRename = {
                    singleSelectedCol?.let { renameTarget = it.id to it.name }
                },
                onUngroup = {
                    singleSelectedCol?.let {
                        viewModel.ungroupCollection(it.id)
                        viewModel.clearSelection()
                    }
                },
                onRemoveDrive = viewModel::unpinSelectedDriveAlbums,
                onClear = viewModel::clearSelection,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // Create sub-collection from drag-on-top pair
    pendingGroupPair?.let { (dragged, target) ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingGroupPair = null },
            title = { Text("Create nested collection") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Collection name") },
                    placeholder = { Text("e.g. Trips, 2026") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.groupItemsIntoNewSubCollection(dragged, target, name)
                        pendingGroupPair = null
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { pendingGroupPair = null }) { Text("Cancel") }
            }
        )
    }

    // Create empty nested sub-collection dialog
    if (showCreateSubColDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateSubColDialog = false },
            title = { Text("New nested collection") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Collection name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createSubCollection(name)
                        showCreateSubColDialog = false
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateSubColDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename dialog
    renameTarget?.let { (targetId, currentName) ->
        var edited by remember(targetId) { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename collection") },
            text = {
                OutlinedTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = edited.isNotBlank(),
                    onClick = {
                        viewModel.renameCollection(targetId, edited)
                        renameTarget = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showGroupSelectedDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGroupSelectedDialog = false },
            title = { Text("Create nested collection") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Collection name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.groupSelectedIntoSubCollection(name)
                        showGroupSelectedDialog = false
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showGroupSelectedDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showMoveSelectedSheet) {
        val destinations = uiState.allCollections.filter {
            it.id != uiState.collectionId && it.id !in uiState.selectedIds
        }
        ModalBottomSheet(
            onDismissRequest = { showMoveSelectedSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
            ) {
                Text(
                    text = "Move to collection",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
                destinations.forEach { dest ->
                    ListItem(
                        headlineContent = { Text(dest.name) },
                        leadingContent = {
                            Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            viewModel.moveSelectedIntoCollection(dest.id)
                            showMoveSelectedSheet = false
                        }
                    )
                }
            }
        }
    }
}

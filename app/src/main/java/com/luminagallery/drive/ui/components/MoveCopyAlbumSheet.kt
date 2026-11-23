package com.luminagallery.drive.ui.components

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.launch

/**
 * Samsung One UI bottom sheet for Moving or Copying on-device photos & videos to any
 * existing local album or creating a brand-new local album folder.
 * Handles Android 11+ `MediaStore.createWriteRequest` transparently for Move operations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveCopyToAlbumSheet(
    items: List<MediaItem>,
    initialMoveMode: Boolean = false,
    onDismiss: () -> Unit,
    onCompleted: (String) -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val localAlbums by container.localMediaRepository.observeLocalAlbums()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val localItems = remember(items) { items.filter { it.isLocal } }
    var isMoveMode by remember { mutableStateOf(initialMoveMode) }
    var useDcimParent by remember { mutableStateOf(true) }
    var newAlbumName by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var pendingMoveTarget by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun executeMoveNow(targetFolderName: String, inDcim: Boolean) {
        isBusy = true
        scope.launch {
            container.localFileOperationsManager.moveLocalItemsToAlbum(
                items = localItems,
                albumFolderName = targetFolderName,
                useDcimParent = inDcim
            )
                .onSuccess { count ->
                    container.localMediaRepository.notifyPermissionChanged()
                    isBusy = false
                    onCompleted("Moved $count item(s) to $targetFolderName")
                }
                .onFailure { err ->
                    isBusy = false
                    onCompleted(err.localizedMessage ?: "Failed to move item(s)")
                }
        }
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val target = pendingMoveTarget
        pendingMoveTarget = null
        if (result.resultCode == Activity.RESULT_OK && target != null && target.first.isNotBlank()) {
            executeMoveNow(target.first, target.second)
        } else {
            onCompleted("Move cancelled")
        }
    }

    fun selectAlbum(targetFolderName: String, inDcim: Boolean = useDcimParent) {
        if (isBusy || localItems.isEmpty()) return
        val cleanName = targetFolderName.trim()
        if (cleanName.isEmpty()) return

        if (isMoveMode) {
            val sender = container.localFileOperationsManager.buildWritePermissionSender(localItems)
            if (sender != null) {
                pendingMoveTarget = cleanName to inDcim
                writePermissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                executeMoveNow(cleanName, inDcim)
            }
        } else {
            isBusy = true
            scope.launch {
                container.localFileOperationsManager.copyLocalItemsToAlbum(
                    items = localItems,
                    albumFolderName = cleanName,
                    useDcimParent = inDcim
                )
                    .onSuccess { count ->
                        container.localMediaRepository.notifyPermissionChanged()
                        isBusy = false
                        onCompleted("Copied $count item(s) to $cleanName")
                    }
                    .onFailure { err ->
                        isBusy = false
                        onCompleted(err.localizedMessage ?: "Failed to copy item(s)")
                    }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isBusy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isMoveMode) "Move ${localItems.size} item(s)" else "Copy ${localItems.size} item(s)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            // Copy / Move pill toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(false to "Copy to album", true to "Move to album").forEach { (move, label) ->
                    val selected = isMoveMode == move
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !isBusy) { isMoveMode = move }
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Create new local album row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = { newAlbumName = it },
                    placeholder = { Text("New album name…") },
                    leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = !isBusy && newAlbumName.isNotBlank(),
                    onClick = { selectAlbum(newAlbumName, inDcim = useDcimParent) }
                ) {
                    Text(if (isMoveMode) "Move" else "Copy", fontWeight = FontWeight.SemiBold)
                }
            }

            // DCIM vs Pictures parent selector for newly created folders
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "New folder location:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(true to "DCIM/", false to "Pictures/").forEach { (dcim, label) ->
                    val chosen = useDcimParent == dcim
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !isBusy) { useDcimParent = dcim }
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
            ) {
                items(localAlbums, key = { it.id }) { album ->
                    val albumInDcim = album.relativePath?.startsWith("DCIM", ignoreCase = true) == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = !isBusy) { selectAlbum(album.name, inDcim = albumInDcim) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = album.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${album.mediaCount} items",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

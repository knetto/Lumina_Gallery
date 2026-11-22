package com.luminagallery.drive.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoveUp
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Samsung One UI floating bottom action bar shown when one or more Albums / Collections
 * are selected by long-pressing.
 */
@Composable
fun AlbumSelectionActionBar(
    visible: Boolean,
    selectedCount: Int,
    canGroup: Boolean,
    canMoveOut: Boolean,
    canMoveIntoExisting: Boolean,
    canRename: Boolean,
    canUngroup: Boolean,
    canRemoveDrive: Boolean,
    onGroup: () -> Unit,
    onMoveOut: () -> Unit,
    onMoveIntoExisting: () -> Unit,
    onRename: () -> Unit,
    onUngroup: () -> Unit,
    onRemoveDrive: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            initialOffsetY = { it }
        ) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumActionButton(
                    icon = Icons.Rounded.Close,
                    label = "$selectedCount",
                    onClick = onClear
                )
                if (canGroup) {
                    AlbumActionButton(
                        icon = Icons.Rounded.CreateNewFolder,
                        label = "Group",
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = onGroup
                    )
                }
                if (canMoveOut) {
                    AlbumActionButton(
                        icon = Icons.Rounded.MoveUp,
                        label = "Move out",
                        onClick = onMoveOut
                    )
                }
                if (canMoveIntoExisting) {
                    AlbumActionButton(
                        icon = Icons.AutoMirrored.Rounded.DriveFileMove,
                        label = "Move",
                        onClick = onMoveIntoExisting
                    )
                }
                if (canRename) {
                    AlbumActionButton(
                        icon = Icons.Rounded.Edit,
                        label = "Rename",
                        onClick = onRename
                    )
                }
                if (canUngroup) {
                    AlbumActionButton(
                        icon = Icons.Rounded.Unarchive,
                        label = "Ungroup",
                        onClick = onUngroup
                    )
                }
                if (canRemoveDrive) {
                    AlbumActionButton(
                        icon = Icons.Rounded.DeleteOutline,
                        label = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onRemoveDrive
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumActionButton(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(21.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

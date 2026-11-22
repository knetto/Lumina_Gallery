package com.luminagallery.drive.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExifMetadataBottomSheet(
    item: MediaItem,
    driveFolderPath: String,
    onDismiss: () -> Unit,
    onCopiedMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val annotation by remember(item.id) {
        container.galleryExtrasRepository.observeAnnotation(item.id)
    }.collectAsStateWithLifecycle(initialValue = null)

    var tags by remember(item.id) { mutableStateOf<List<String>>(emptyList()) }
    var noteDraft by remember(item.id) { mutableStateOf("") }
    var tagInput by remember(item.id) { mutableStateOf("") }

    LaunchedEffect(annotation) {
        val loadedTags = annotation?.tagsCsv.orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        tags = loadedTags
        noteDraft = annotation?.note.orEmpty()
    }

    fun persistAnnotation(updatedTags: List<String>, updatedNote: String) {
        scope.launch {
            container.galleryExtrasRepository.saveAnnotation(item.id, updatedTags, updatedNote)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Filename header
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Date taken
            MetadataInfoRow(
                icon = Icons.Rounded.CalendarToday,
                title = item.fullDateLabel,
                subtitle = "Date captured"
            )

            // Resolution & File size
            val resolutionSummary = buildString {
                val w = item.effectiveWidth
                val h = item.effectiveHeight
                if (w != null && h != null && w > 0 && h > 0) {
                    append("$w × $h")
                    FormatUtils.formatMegapixels(w, h)?.let { append(" · $it") }
                    append(" · ")
                }
                append(FormatUtils.formatFileSize(item.sizeBytes))
                if (item.isVideo && item.durationMillis != null) {
                    append(" · ${FormatUtils.formatDuration(item.durationMillis)}")
                }
            }
            MetadataInfoRow(
                icon = if (item.isVideo) Icons.Rounded.Movie else Icons.Rounded.Image,
                title = resolutionSummary,
                subtitle = when {
                    item.isLocal && item.isVideo -> "On-device video"
                    item.isLocal && item.isRaw -> "On-device RAW photograph"
                    item.isLocal -> "On-device photo"
                    item.isVideo -> "Video stream (on-demand via Drive API)"
                    item.isRaw -> "RAW photograph (Google Drive)"
                    else -> "Image file (Google Drive)"
                }
            )

            // Camera & EXIF section
            if (item.exif.hasCameraDetails) {
                MetadataInfoRow(
                    icon = Icons.Rounded.CameraAlt,
                    title = item.exif.cameraDisplayName ?: "Camera Details",
                    subtitle = item.exif.lens ?: if (item.isLocal) "EXIF metadata" else "EXIF metadata from Google Drive"
                )

                val pills = buildList {
                    FormatUtils.formatAperture(item.exif.aperture)?.let { add(it) }
                    FormatUtils.formatShutterSpeed(item.exif.exposureTime)?.let { add(it) }
                    item.exif.isoSpeed?.let { add("ISO $it") }
                    FormatUtils.formatFocalLength(item.exif.focalLength)?.let { add(it) }
                }

                if (pills.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 40.dp)
                    ) {
                        pills.forEach { pill ->
                            Text(
                                text = pill,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Folder Path (On-Device or Google Drive)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetadataInfoRow(
                    icon = Icons.Rounded.Folder,
                    title = driveFolderPath,
                    subtitle = if (item.isLocal) "Internal storage" else "Drive ID: ${item.id}",
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Drive Path", "$driveFolderPath/${item.name}")
                        )
                        onCopiedMessage("Copied Drive path")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy path",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Searchable Tags & Notes section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Label,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Tags & notes",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }

                if (tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tags.forEach { tag ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .clickable {
                                        val next = tags - tag
                                        tags = next
                                        persistAnnotation(next, noteDraft)
                                    }
                                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                            ) {
                                Text(
                                    text = "#$tag",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Remove tag",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        placeholder = { Text("Add tag (e.g. receipt, trip, car)") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val clean = tagInput.trim().removePrefix("#")
                            if (clean.isNotEmpty() && clean !in tags) {
                                val next = tags + clean
                                tags = next
                                tagInput = ""
                                persistAnnotation(next, noteDraft)
                                onCopiedMessage("Added tag #$clean")
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Add tag",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    placeholder = { Text("Add a searchable caption or note…") },
                    maxLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            persistAnnotation(tags, noteDraft)
                            onCopiedMessage("Saved tags & note")
                        }
                    ) {
                        Text("Save note", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun MetadataInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

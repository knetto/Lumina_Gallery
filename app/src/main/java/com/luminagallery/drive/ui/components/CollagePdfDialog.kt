package com.luminagallery.drive.ui.components

import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesomeMosaic
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.data.repository.CollageLayoutMode
import com.luminagallery.drive.data.repository.CollageOptions
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.launch

/**
 * One UI bottom sheet for creating a Photo Collage (2–9 photos) or multi-page A4 PDF (1–40 photos)
 * from any multi-selection, with 1-tap Save to Gallery/Documents or Android Share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollagePdfBottomSheet(
    items: List<MediaItem>,
    onDismiss: () -> Unit,
    onCompleted: (String) -> Unit
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val photos = remember(items) { items.filter { !it.isVideo } }
    var isPdfTab by remember(photos.size) { mutableStateOf(photos.size < 2) }
    var selectedLayout by remember { mutableStateOf(CollageLayoutMode.AUTO_GRID) }
    var spacingDp by remember { mutableFloatStateOf(20f) }
    var cornerRadius by remember { mutableFloatStateOf(28f) }
    var bgColorInt by remember { mutableIntStateOf(AndroidColor.BLACK) }
    var pdfTitle by remember { mutableStateOf("Lumina_Photos") }
    var isWorking by remember { mutableStateOf(false) }

    fun shareUri(uri: Uri, mimeType: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(intent, "Share")
        context.startActivity(chooser)
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isWorking) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Create from ${photos.size} photo${if (photos.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            // Collage / PDF toggle pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    false to ("Collage" to Icons.Rounded.AutoAwesomeMosaic),
                    true to ("PDF Document" to Icons.Rounded.PictureAsPdf)
                ).forEach { (pdfMode, meta) ->
                    val enabled = if (!pdfMode) photos.size >= 2 else photos.isNotEmpty()
                    val selected = isPdfTab == pdfMode
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !isWorking && enabled) { isPdfTab = pdfMode }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = meta.second,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = meta.first,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f)
                            )
                        }
                    }
                }
            }

            if (photos.isEmpty()) {
                Text(
                    text = "Select photos (non-video items) to create a collage or PDF.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!isPdfTab) {
                Text(
                    text = "Layout (up to 9 photos)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CollageLayoutMode.entries.forEach { mode ->
                        FilterChip(
                            selected = selectedLayout == mode,
                            onClick = { selectedLayout = mode },
                            label = { Text(mode.label, fontSize = 12.sp) }
                        )
                    }
                }

                Column {
                    Text(
                        text = "Border spacing (${spacingDp.toInt()} px)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = spacingDp,
                        onValueChange = { spacingDp = it },
                        valueRange = 0f..56f
                    )
                }

                Column {
                    Text(
                        text = "Corner roundness (${cornerRadius.toInt()} px)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = cornerRadius,
                        onValueChange = { cornerRadius = it },
                        valueRange = 0f..72f
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Background",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(
                        AndroidColor.BLACK to Color.Black,
                        AndroidColor.WHITE to Color.White,
                        AndroidColor.rgb(24, 24, 28) to Color(0xFF18181C),
                        AndroidColor.rgb(244, 238, 229) to Color(0xFFF4EEE5)
                    ).forEach { (intVal, composeColor) ->
                        val active = bgColorInt == intVal
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(composeColor)
                                .border(
                                    width = if (active) 2.5.dp else 1.dp,
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable { bgColorInt = intVal }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        enabled = !isWorking && photos.size >= 2,
                        onClick = {
                            isWorking = true
                            scope.launch {
                                container.collagePdfCreator.createCollage(
                                    items = photos,
                                    options = CollageOptions(
                                        layout = selectedLayout,
                                        spacingPx = spacingDp.toInt(),
                                        cornerRadiusPx = cornerRadius,
                                        backgroundColor = bgColorInt
                                    ),
                                    saveToGallery = false
                                ).onSuccess { uri ->
                                    isWorking = false
                                    shareUri(uri, "image/jpeg")
                                    onCompleted("Collage ready to share")
                                }.onFailure { err ->
                                    isWorking = false
                                    onCompleted(err.localizedMessage ?: "Failed to create collage")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Share")
                    }
                    Button(
                        enabled = !isWorking && photos.size >= 2,
                        onClick = {
                            isWorking = true
                            scope.launch {
                                container.collagePdfCreator.createCollage(
                                    items = photos,
                                    options = CollageOptions(
                                        layout = selectedLayout,
                                        spacingPx = spacingDp.toInt(),
                                        cornerRadiusPx = cornerRadius,
                                        backgroundColor = bgColorInt
                                    ),
                                    saveToGallery = true
                                ).onSuccess {
                                    container.localMediaRepository.notifyPermissionChanged()
                                    isWorking = false
                                    onCompleted("Saved collage to Pictures/LuminaGallery")
                                }.onFailure { err ->
                                    isWorking = false
                                    onCompleted(err.localizedMessage ?: "Failed to save collage")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save collage")
                    }
                }
            } else {
                Text(
                    text = "Creates a crisp multi-page A4 PDF (${photos.take(40).size} page${if (photos.size == 1) "" else "s"}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pdfTitle,
                    onValueChange = { pdfTitle = it },
                    label = { Text("PDF filename") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        enabled = !isWorking && photos.isNotEmpty(),
                        onClick = {
                            isWorking = true
                            scope.launch {
                                container.collagePdfCreator.createPdf(
                                    items = photos,
                                    documentTitle = pdfTitle,
                                    saveToDocuments = false
                                ).onSuccess { uri ->
                                    isWorking = false
                                    shareUri(uri, "application/pdf")
                                    onCompleted("PDF ready to share")
                                }.onFailure { err ->
                                    isWorking = false
                                    onCompleted(err.localizedMessage ?: "Failed to build PDF")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Share PDF")
                    }
                    Button(
                        enabled = !isWorking && photos.isNotEmpty(),
                        onClick = {
                            isWorking = true
                            scope.launch {
                                container.collagePdfCreator.createPdf(
                                    items = photos,
                                    documentTitle = pdfTitle,
                                    saveToDocuments = true
                                ).onSuccess {
                                    isWorking = false
                                    onCompleted("Saved PDF to Documents/LuminaGallery")
                                }.onFailure { err ->
                                    isWorking = false
                                    onCompleted(err.localizedMessage ?: "Failed to save PDF")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save PDF")
                    }
                }
            }
        }
    }
}

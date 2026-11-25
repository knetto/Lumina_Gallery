package com.luminagallery.drive.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.luminagallery.drive.data.repository.ai.SmartObjectVocabulary
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Flagship Details Sheet inspired by Apple Photos, Xiaomi HyperOS Gallery, and Samsung One UI 8:
 *  - Large crisp date header with filename, format pill, and device/cloud badge
 *  - Pro Camera & EXIF telemetry card (tabular f-stop / shutter / ISO / focal length strip)
 *  - Unified media resolution, megapixels, file size & storage card with one-tap copy
 *  - Clean inline caption/note editor & pill tag manager (zero cluttered box borders)
 */
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
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val annotation by remember(item.id) {
        container.galleryExtrasRepository.observeAnnotation(item.id)
    }.collectAsStateWithLifecycle(initialValue = null)

    val detectedFaces by remember(item.id) {
        container.smartRecognitionManager.observeFacesForPhoto(item.id)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val allPersons by container.smartRecognitionManager.allPersonsFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val smartIndex by remember(item.id) {
        container.smartRecognitionManager.observeSmartIndexForPhoto(item.id)
    }.collectAsStateWithLifecycle(initialValue = null)
    var quickRenamePersonId by remember { mutableStateOf<String?>(null) }
    var quickRenameInitial by remember { mutableStateOf("") }

    var tags by remember(item.id) { mutableStateOf<List<String>>(emptyList()) }
    var noteDraft by remember(item.id) { mutableStateOf("") }
    var savedNote by remember(item.id) { mutableStateOf("") }
    var tagInput by remember(item.id) { mutableStateOf("") }

    LaunchedEffect(annotation) {
        val loadedTags = annotation?.tagsCsv.orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        tags = loadedTags
        val loadedNote = annotation?.note.orEmpty()
        noteDraft = loadedNote
        savedNote = loadedNote
    }

    fun persistAnnotation(updatedTags: List<String>, updatedNote: String) {
        savedNote = updatedNote
        // Persist on repository IO scope so dismissing the sheet immediately never cancels the DB write
        container.galleryExtrasRepository.saveAnnotationAsync(item.id, updatedTags, updatedNote)
    }

    fun submitTag() {
        val parts = tagInput.split(",")
            .map { it.trim().removePrefix("#").trim() }
            .filter { it.isNotEmpty() && it !in tags }
            .distinct()
        if (parts.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val next = tags + parts
            tags = next
            tagInput = ""
            persistAnnotation(next, noteDraft)
            onCopiedMessage("Added ${parts.joinToString(", ") { "#$it" }}")
        }
    }

    val handleDismiss: () -> Unit = {
        if (noteDraft != savedNote) {
            persistAnnotation(tags, noteDraft)
        }
        onDismiss()
    }

    val extBadge = remember(item.name, item.mimeType, item.isRaw, item.isVideo) {
        val ext = item.name.substringAfterLast('.', "").uppercase(Locale.US)
        when {
            ext.length in 2..5 -> ext
            item.isRaw -> "RAW"
            item.isVideo -> "VIDEO"
            else -> "PHOTO"
        }
    }

    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val hairlineBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    ModalBottomSheet(
        onDismissRequest = handleDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .navigationBarsPadding()
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Apple Photos-style Hero Date + Filename Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.fullDateLabel,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.3).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    // Origin badge (On device / Google Drive)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (item.isLocal) {
                                Icon(
                                    imageVector = Icons.Rounded.Smartphone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                GoogleDriveGlyph(
                                    size = 13.dp,
                                    primaryColor = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = if (item.isLocal) "On device" else "Drive",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = extBadge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                letterSpacing = 0.4.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                        )
                    }
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 2. Apple / Xiaomi / Samsung Pro Camera & Media Telemetry Card
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.7.dp, hairlineBorder, RoundedCornerShape(22.dp))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Top header row inside card: Camera Model or Media Kind + MP badge
                    val w = item.effectiveWidth
                    val h = item.effectiveHeight
                    val mpLabel = FormatUtils.formatMegapixels(w, h)
                    val resLabel = if (w != null && h != null && w > 0 && h > 0) "$w × $h" else null

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    item.exif.hasCameraDetails -> Icons.Rounded.CameraAlt
                                    item.isVideo -> Icons.Rounded.Movie
                                    else -> Icons.Rounded.Image
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.exif.cameraDisplayName
                                    ?: if (item.isVideo) "Video recording" else if (item.isRaw) "RAW Photograph" else "Photograph",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subline = buildList {
                                item.exif.lens?.takeIf { it.isNotBlank() }?.let { add(it) }
                                resLabel?.let { add(it) }
                                if (item.isVideo && item.durationMillis != null) {
                                    add(FormatUtils.formatDuration(item.durationMillis))
                                }
                            }.joinToString(" · ").ifBlank { item.mimeType }
                            Text(
                                text = subline,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = FormatUtils.formatFileSize(item.sizeBytes),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFeatureSettings = "tnum"
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (mpLabel != null) {
                                Text(
                                    text = mpLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Pro EXIF 4-column telemetry strip (f-stop | shutter | ISO | focal length)
                    val exifCells = buildList {
                        FormatUtils.formatAperture(item.exif.aperture)?.let { add("Aperture" to it) }
                        FormatUtils.formatShutterSpeed(item.exif.exposureTime)?.let { add("Shutter" to it) }
                        item.exif.isoSpeed?.let { add("ISO" to "$it") }
                        FormatUtils.formatFocalLength(item.exif.focalLength)?.let { add("Lens" to it) }
                    }

                    if (exifCells.isNotEmpty()) {
                        HorizontalDivider(
                            thickness = 0.6.dp,
                            color = hairlineBorder
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            exifCells.forEachIndexed { idx, (label, value) ->
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = value,
                                        style = TextStyle(
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFeatureSettings = "tnum",
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                if (idx < exifCells.lastIndex) {
                                    VerticalDivider(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(vertical = 4.dp),
                                        thickness = 0.6.dp,
                                        color = hairlineBorder
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Storage Location Card with One-Tap Copy
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.7.dp, hairlineBorder, RoundedCornerShape(22.dp))
                    .clip(RoundedCornerShape(22.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val copyPath = if (item.isLocal) {
                            val rel = item.storagePath?.trim('/')?.takeIf { it.isNotBlank() }
                            if (rel != null) "/storage/emulated/0/$rel/${item.name}" else "$driveFolderPath/${item.name}"
                        } else {
                            "$driveFolderPath/${item.name}"
                        }
                        clipboard.setPrimaryClip(ClipData.newPlainText("Path", copyPath))
                        onCopiedMessage("Copied path to clipboard")
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = driveFolderPath,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (item.isLocal) "Internal storage · tap to copy path" else "Google Drive · tap to copy path",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy path",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 3.5. On-device Detected People & Objects Card (Samsung One UI style)
            val photoPersons = remember(detectedFaces, allPersons) {
                val byId = allPersons.associateBy { it.personId }
                detectedFaces.mapNotNull { face ->
                    val pid = face.personId ?: return@mapNotNull null
                    val person = byId[pid] ?: return@mapNotNull null
                    if (person.isHidden) null else (person to (face.cropPath ?: person.coverCropPath))
                }.distinctBy { it.first.personId }
            }
            val detectedLabels = remember(smartIndex) {
                smartIndex?.labelsCsv.orEmpty()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            if (photoPersons.isNotEmpty() || detectedLabels.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = cardColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.7.dp, hairlineBorder, RoundedCornerShape(22.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (photoPersons.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Face,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "People in this photo",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(photoPersons, key = { it.first.personId }) { (person, crop) ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                quickRenamePersonId = person.personId
                                                quickRenameInitial = person.name
                                            }
                                            .padding(4.dp)
                                    ) {
                                        PersonAvatar(
                                            cropPath = crop,
                                            contentDescription = person.name.ifBlank { "Unnamed" },
                                            size = 54.dp,
                                            isFavorite = person.isFavorite
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = person.name.ifBlank { "Add name" },
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = if (person.name.isNotBlank()) FontWeight.SemiBold else FontWeight.Medium
                                            ),
                                            color = if (person.name.isNotBlank()) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        if (detectedLabels.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(17.dp)
                                )
                                Text(
                                    text = "Detected objects & scenes",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                detectedLabels.forEach { label ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Icon(
                                            imageVector = iconForObjectCategory(SmartObjectVocabulary.categoryForLabel(label)),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Clean iOS-style Caption & Tags Card
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.7.dp, hairlineBorder, RoundedCornerShape(22.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header + contextual Save pill when caption changed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Label,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Caption & tags",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        AnimatedVisibility(
                            visible = noteDraft != savedNote,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        persistAnnotation(tags, noteDraft)
                                        onCopiedMessage("Caption saved")
                                    }
                            ) {
                                Text(
                                    text = "Save",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    // Borderless soft caption input (iOS Photos "Add a caption")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        if (noteDraft.isEmpty()) {
                            Text(
                                text = "Add a searchable caption or note…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                            )
                        }
                        BasicTextField(
                            value = noteDraft,
                            onValueChange = { noteDraft = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Existing tag pills
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
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val next = tags - tag
                                            tags = next
                                            persistAnnotation(next, noteDraft)
                                        }
                                        .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                                ) {
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove tag",
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Compact inline "+ Add tag" pill row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f))
                            .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (tagInput.isEmpty()) {
                                Text(
                                    text = "Add tag (e.g. receipt, travel, car)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = tagInput,
                                onValueChange = { tagInput = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submitTag() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        val canAdd = tagInput.trim().removePrefix("#").isNotBlank()
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canAdd) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .clickable(enabled = canAdd, onClick = ::submitTag),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Add tag",
                                tint = if (canAdd) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    quickRenamePersonId?.let { targetPid ->
        var draftName by remember(targetPid) { mutableStateOf(quickRenameInitial) }
        AlertDialog(
            onDismissRequest = { quickRenamePersonId = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
            title = { Text(if (quickRenameInitial.isNotBlank()) "Rename person" else "Name this person") },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text("Name") },
                    placeholder = { Text("Enter name…") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = draftName.trim()
                        quickRenamePersonId = null
                        scope.launch {
                            val existing = container.smartRecognitionManager.findExistingPersonByName(clean, targetPid)
                            if (existing != null && clean.isNotEmpty()) {
                                container.smartRecognitionManager.mergePersons(existing.personId, listOf(targetPid), existing.name)
                                onCopiedMessage("Combined with ${existing.name}")
                            } else {
                                container.smartRecognitionManager.renamePerson(targetPid, clean)
                                onCopiedMessage(if (clean.isNotEmpty()) "Named \"$clean\"" else "Name cleared")
                            }
                        }
                    }
                ) { Text("Save", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { quickRenamePersonId = null }) { Text("Cancel") }
            }
        )
    }
}

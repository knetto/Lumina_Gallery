package com.luminagallery.drive.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.ai.SmartObjectVocabulary
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

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
    onCopiedMessage: (String) -> Unit = {},
    onOpenPerson: ((personId: String) -> Unit)? = null,
    onSearchTag: ((tag: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val annotation by remember(item.id) {
        container.galleryExtrasRepository.observeAnnotation(item.id)
    }.collectAsStateWithLifecycle(initialValue = null)

    val cachedLocationsMap by container.galleryExtrasRepository.cachedLocationsMapFlow
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val cachedLocation = cachedLocationsMap[item.id]

    val detectedFaces by remember(item.id) {
        container.smartRecognitionManager.observeFacesForPhoto(item.id)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val allPersons by container.smartRecognitionManager.allPersonsFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val smartIndex by remember(item.id) {
        container.smartRecognitionManager.observeSmartIndexForPhoto(item.id)
    }.collectAsStateWithLifecycle(initialValue = null)
    var actionTargetFace by remember { mutableStateOf<Triple<String, String, String>?>(null) } // (faceId, personId, name)
    var quickRenamePersonId by remember { mutableStateOf<String?>(null) }
    var quickRenameInitial by remember { mutableStateOf("") }
    var moveFaceIdAndCurrentPerson by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Async rich metadata hydration (EXIF specs, video telemetry, GPS + detailed address, Drive details)
    var liveExif by remember(item.id) { mutableStateOf(item.exif) }
    LaunchedEffect(item.id, item.exif) {
        liveExif = item.exif
        if (item.isLocal) {
            val (enriched, _) = container.localMediaRepository.readLocalExifAndPath(item, resolveAddress = true)
            liveExif = enriched
        } else {
            val driveEnriched = container.galleryRepository.fetchRichDriveExif(item)
            if (driveEnriched != null) {
                liveExif = driveEnriched
                val lat = driveEnriched.latitude
                val lon = driveEnriched.longitude
                if (lat != null && lon != null && driveEnriched.hasLocation && driveEnriched.locationAddress.isNullOrBlank()) {
                    val addr = container.localMediaRepository.resolveDetailedAddress(lat, lon)
                    if (!addr.isNullOrBlank()) {
                        liveExif = driveEnriched.copy(locationAddress = addr)
                    }
                }
            }
        }
    }

    // Resolve detailed address for cached Room location if liveExif didn't yield GPS yet
    var cachedDetailedAddress by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id, cachedLocation?.latitude, cachedLocation?.longitude, liveExif.hasLocation) {
        if (!liveExif.hasLocation && cachedLocation != null && cachedLocation.hasLocation) {
            cachedDetailedAddress = container.localMediaRepository.resolveDetailedAddress(
                cachedLocation.latitude,
                cachedLocation.longitude
            )
        }
    }

    // Instant single-photo AI recognition if this photo hasn't been indexed yet
    var onDemandLabels by remember(item.id) { mutableStateOf<List<String>>(emptyList()) }
    var isAnalyzingSmart by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, smartIndex?.indexedAtEpochMs) {
        if (smartIndex == null && !item.isRaw) {
            isAnalyzingSmart = true
            val labels = runCatching {
                container.smartRecognitionManager.analyzePhotoOnDemand(item)
            }.getOrDefault(emptyList())
            onDemandLabels = labels
            isAnalyzingSmart = false
        } else {
            isAnalyzingSmart = false
        }
    }

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

                if (item.dateRecoveredFromFilename) {
                    val copiedText = if (item.fileModifiedEpochMs > 0L) {
                        " · transferred ${FormatUtils.formatCalendarDate(item.fileModifiedEpochMs)}"
                    } else ""
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.11f))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Date recovered from filename$copiedText",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 2. Recognized People & Animal / Object / Scene Tags Card (prominently near top!)
            val photoPersons = remember(detectedFaces, allPersons) {
                val byId = allPersons.associateBy { it.personId }
                detectedFaces.mapNotNull { face ->
                    val pid = face.personId ?: return@mapNotNull null
                    val person = byId[pid] ?: return@mapNotNull null
                    if (person.isHidden) null else Triple(person, face.faceId, face.cropPath ?: person.coverCropPath)
                }.distinctBy { it.first.personId }
            }
            val detectedLabels = remember(smartIndex, onDemandLabels) {
                val indexed = smartIndex?.labelsCsv.orEmpty()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (indexed.isNotEmpty()) indexed else onDemandLabels
            }
            if (photoPersons.isNotEmpty() || detectedLabels.isNotEmpty() || isAnalyzingSmart) {
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
                                    text = if (photoPersons.size == 1) "Person in this photo" else "People in this photo",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(photoPersons, key = { it.first.personId }) { (person, faceId, crop) ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                actionTargetFace = Triple(faceId, person.personId, person.name)
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
                            if (photoPersons.isNotEmpty()) {
                                HorizontalDivider(thickness = 0.6.dp, color = hairlineBorder)
                            }
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
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Text(
                                        text = "Detected tags",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (onSearchTag != null) {
                                    Text(
                                        text = "Tap to find similar",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                detectedLabels.forEach { label ->
                                    val category = SmartObjectVocabulary.categoryForLabel(label)
                                    val isAnimalTag = category == SmartObjectVocabulary.ObjectCategory.PETS
                                    val chipBg = if (isAnimalTag) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    }
                                    val chipTint = if (isAnimalTag) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(chipBg)
                                            .then(
                                                if (onSearchTag != null) {
                                                    Modifier.clickable {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        onSearchTag(label)
                                                    }
                                                } else Modifier
                                            )
                                            .padding(horizontal = 11.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = iconForObjectCategory(category),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (isAnimalTag) FontWeight.SemiBold else FontWeight.Medium
                                            ),
                                            color = chipTint
                                        )
                                        if (onSearchTag != null) {
                                            Icon(
                                                imageVector = Icons.Rounded.Search,
                                                contentDescription = null,
                                                tint = chipTint.copy(alpha = 0.55f),
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (isAnalyzingSmart && photoPersons.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Detecting people, animals & scenes…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 3. Location & Photo Mini-Map Card (when GPS coordinates exist in EXIF, video, Drive, or Room cache)
            val resolvedLat = if (liveExif.hasLocation) {
                liveExif.latitude
            } else if (cachedLocation != null && cachedLocation.hasLocation) {
                cachedLocation.latitude
            } else null
            val resolvedLon = if (liveExif.hasLocation) {
                liveExif.longitude
            } else if (cachedLocation != null && cachedLocation.hasLocation) {
                cachedLocation.longitude
            } else null

            if (resolvedLat != null && resolvedLon != null) {
                val cachedCityCountry = cachedLocation?.let { loc ->
                    listOf(loc.city, loc.country)
                        .filter { it.isNotBlank() && it != "Other" && !it.contains("°") }
                        .distinct()
                        .joinToString(", ")
                        .takeIf { it.isNotBlank() }
                }
                val locationTitle = liveExif.locationAddress?.takeIf { it.isNotBlank() }
                    ?: cachedDetailedAddress?.takeIf { it.isNotBlank() }
                    ?: cachedCityCountry
                    ?: "Photo location"
                val coordText = buildString {
                    append(FormatUtils.formatCoordinates(resolvedLat, resolvedLon))
                    FormatUtils.formatAltitude(liveExif.altitudeMeters)?.let { alt ->
                        append(" · ")
                        append(alt)
                    }
                }
                val thumbData: Any? = if (item.isLocal) {
                    item.localContentUri
                } else {
                    DriveConstants.buildThumbnailUrl(
                        thumbnailLink = item.thumbnailLink,
                        fileId = item.id,
                        isUnmeteredWifi = true,
                        isVideo = item.isVideo,
                        sizeBytes = item.sizeBytes,
                        requestedMaxPx = 256
                    )
                }

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = cardColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.7.dp, hairlineBorder, RoundedCornerShape(22.dp))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        PhotoLocationMiniMap(
                            latitude = resolvedLat,
                            longitude = resolvedLon,
                            thumbnailData = thumbData,
                            thumbnailMemoryKey = DriveConstants.stableThumbMemoryKey(item.id),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(172.dp)
                                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = locationTitle,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = coordText,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val labelEncoded = Uri.encode(locationTitle)
                                            val geoUri = Uri.parse(
                                                String.format(
                                                    Locale.US,
                                                    "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                                                    resolvedLat,
                                                    resolvedLon,
                                                    resolvedLat,
                                                    resolvedLon,
                                                    labelEncoded
                                                )
                                            )
                                            val launched = runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, geoUri).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                )
                                            }.isSuccess
                                            if (!launched) {
                                                onCopiedMessage("No maps app installed")
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Map,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Open in Maps",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val clipboard =
                                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val rawCoord = String.format(Locale.US, "%.6f, %.6f", resolvedLat, resolvedLon)
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Location", rawCoord))
                                            onCopiedMessage("Copied coordinates")
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Copy coords",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Apple / Xiaomi / Samsung Pro Camera & Media Telemetry Card
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
                    val aspectLabel = FormatUtils.formatAspectRatio(w, h)

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
                                    liveExif.hasCameraDetails -> Icons.Rounded.CameraAlt
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
                                text = liveExif.cameraDisplayName
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
                                liveExif.lens?.takeIf { it.isNotBlank() }?.let { add(it) }
                                resLabel?.let { add(it) }
                                aspectLabel?.let { add(it) }
                                if (item.isVideo && item.durationMillis != null) {
                                    add(FormatUtils.formatDuration(item.durationMillis))
                                }
                            }.joinToString(" · ").ifBlank { item.mimeType }
                            Text(
                                text = subline,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
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
                    val focalLabel = FormatUtils.formatFocalLength(liveExif.focalLength)
                        ?: liveExif.focalLength35mm?.let { "$it mm" }
                    val exifCells = buildList {
                        FormatUtils.formatAperture(liveExif.aperture)?.let { add("Aperture" to it) }
                        FormatUtils.formatShutterSpeed(liveExif.exposureTime)?.let { add("Shutter" to it) }
                        liveExif.isoSpeed?.let { add("ISO" to "$it") }
                        focalLabel?.let { add("Focal" to it) }
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

                    // Additional camera, video & file technical specs (2-column compact grid)
                    val extraSpecs = buildList<Pair<String, String>> {
                        liveExif.focalLength35mm?.let { eq ->
                            if (liveExif.focalLength != null) add("35mm equiv." to "$eq mm")
                        }
                        FormatUtils.formatExposureBias(liveExif.exposureBiasEv)?.let { add("Exposure" to it) }
                        liveExif.flashDescription?.let { add("Flash" to it) }
                        liveExif.whiteBalance?.let { add("White balance" to it) }
                        liveExif.meteringMode?.let { add("Metering" to it) }
                        liveExif.digitalZoomRatio?.let { zoom ->
                            add("Digital zoom" to String.format(Locale.US, "%.1f×", zoom))
                        }
                        liveExif.colorSpace?.let { add("Color space" to it) }
                        FormatUtils.formatFrameRate(liveExif.videoFrameRate)?.let { add("Frame rate" to it) }
                        FormatUtils.formatBitrate(liveExif.videoBitrateBps)?.let { add("Bitrate" to it) }
                        liveExif.videoCodec?.let { add("Codec" to it) }
                        liveExif.audioSummary?.let { add("Audio" to it) }
                        liveExif.orientationLabel?.let { add("Orientation" to it) }
                        liveExif.software?.takeIf { it.isNotBlank() }?.let { add("Software" to it) }
                        item.mimeType.takeIf { it.isNotBlank() }?.let { add("Format" to it) }
                        if (item.fileModifiedEpochMs > 0L &&
                            abs(item.fileModifiedEpochMs - item.createdTimeEpochMs) > 86_400_000L
                        ) {
                            add("File modified" to FormatUtils.formatCalendarDate(item.fileModifiedEpochMs))
                        }
                    }

                    if (extraSpecs.isNotEmpty()) {
                        HorizontalDivider(thickness = 0.6.dp, color = hairlineBorder)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            extraSpecs.chunked(2).forEach { pairRow ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    pairRow.forEach { (specLabel, specValue) ->
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(1.dp)
                                        ) {
                                            Text(
                                                text = specLabel,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = specValue,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.Medium,
                                                    fontFeatureSettings = "tnum"
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (pairRow.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Storage Location Card with One-Tap Copy
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

    // Rich Person Quick-Action Dialog (View all photos, Rename, Move to another person, Remove)
    actionTargetFace?.let { (faceId, personId, currentName) ->
        val displayTitle = currentName.ifBlank { "Unnamed person" }
        val otherPeople = remember(allPersons, personId) {
            allPersons.filter { it.personId != personId && !it.isHidden }
        }
        AlertDialog(
            onDismissRequest = { actionTargetFace = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text(displayTitle, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onOpenPerson != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    actionTargetFace = null
                                    onOpenPerson(personId)
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Rounded.People, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("View all photos", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                actionTargetFace = null
                                quickRenamePersonId = personId
                                quickRenameInitial = currentName
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        Text(if (currentName.isNotBlank()) "Rename" else "Add name", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (otherPeople.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    actionTargetFace = null
                                    moveFaceIdAndCurrentPerson = faceId to personId
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                            Text("Move to another person…", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                actionTargetFace = null
                                scope.launch {
                                    container.smartRecognitionManager.removePhotosFromPerson(personId, listOf(item.id))
                                    onCopiedMessage("Removed from $displayTitle")
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Rounded.PersonRemove, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text("Not this person", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTargetFace = null }) { Text("Close") }
            }
        )
    }

    // Move Face to Another Person Picker
    moveFaceIdAndCurrentPerson?.let { (faceId, currentPersonId) ->
        val candidates = remember(allPersons, currentPersonId) {
            allPersons.filter { it.personId != currentPersonId && !it.isHidden }
        }
        AlertDialog(
            onDismissRequest = { moveFaceIdAndCurrentPerson = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Move face to person", fontWeight = FontWeight.SemiBold) },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    gridItems(candidates, key = { it.personId }) { dest ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    moveFaceIdAndCurrentPerson = null
                                    scope.launch {
                                        container.smartRecognitionManager.reassignFaceToPerson(faceId, dest.personId)
                                        onCopiedMessage("Moved to ${dest.name.ifBlank { "person" }}")
                                    }
                                }
                                .padding(4.dp)
                        ) {
                            PersonAvatar(
                                cropPath = dest.coverCropPath,
                                contentDescription = dest.name.ifBlank { "Unnamed" },
                                size = 56.dp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = dest.name.ifBlank { "Unnamed" },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moveFaceIdAndCurrentPerson = null }) { Text("Cancel") }
            }
        )
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

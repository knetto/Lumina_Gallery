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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FilterCenterFocus
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShutterSpeed
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.ZoomIn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.luminagallery.drive.domain.model.ExifMetadata
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Photographer-first Details Sheet:
 *  - Crisp date & filename header with an icon-only storage origin badge (device / Google Drive)
 *  - Hero Camera & Shooting Settings card: clean camera/lens header, 2×2 exposure & optics bento
 *    tiles (aperture, shutter, ISO, focal + 35mm equiv.), focused shooting chips (EV, mode,
 *    metering, WB, flash when fired), and a quiet resolution/size footer
 *  - Location mini-map, People & Detected tags, clean storage path, and inline caption & tags
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
            // 1. Clean Hero Date + Filename Header with Icon-Only Origin Badge
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
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
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = extBadge,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Icon-only origin indicator (phone for local, Google Drive glyph for cloud)
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .size(38.dp)
                            .border(0.7.dp, hairlineBorder, CircleShape)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onCopiedMessage(
                                    if (item.isLocal) "Saved on this device" else "Stored in Google Drive"
                                )
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (item.isLocal) {
                                Icon(
                                    imageVector = Icons.Rounded.Smartphone,
                                    contentDescription = "On device",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                GoogleDriveGlyph(
                                    size = 17.dp,
                                    primaryColor = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.semantics { contentDescription = "Google Drive" }
                                )
                            }
                        }
                    }
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

            // 2. Hero Camera & Shooting Settings Card (immediately visible on swipe-up)
            CameraSettingsCard(
                item = item,
                liveExif = liveExif,
                cardColor = cardColor,
                hairlineBorder = hairlineBorder
            )

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
                    shape = RoundedCornerShape(24.dp),
                    color = cardColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.7.dp, hairlineBorder, RoundedCornerShape(24.dp))
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
                                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
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

            // 4. Recognized People & Animal / Object / Scene Tags Card
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
            if (photoPersons.isNotEmpty() || detectedLabels.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = cardColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.7.dp, hairlineBorder, RoundedCornerShape(24.dp))
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
                        }
                    }
                }
            } else if (isAnalyzingSmart) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardColor.copy(alpha = 0.7f))
                        .border(0.7.dp, hairlineBorder, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.8.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Detecting people, animals & scenes…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 5. Storage Location Card with One-Tap Copy
            val cleanFolderPath = remember(driveFolderPath) {
                FormatUtils.formatDisplayFolderPath(driveFolderPath)
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = cardColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.7.dp, hairlineBorder, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val copyPath = if (item.isLocal) {
                            val rel = item.storagePath?.trim('/')?.takeIf { it.isNotBlank() }
                            if (rel != null) "/storage/emulated/0/$rel/${item.name}" else "$cleanFolderPath/${item.name}"
                        } else {
                            "$cleanFolderPath/${item.name}"
                        }
                        clipboard.setPrimaryClip(ClipData.newPlainText("Path", copyPath))
                        onCopiedMessage("Copied path to clipboard")
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp),
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
                            text = cleanFolderPath,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Tap to copy path",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy path",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // 6. Clean iOS-style Caption & Tags Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = cardColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.7.dp, hairlineBorder, RoundedCornerShape(24.dp))
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

private data class ShootingMetricTile(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val secondary: String? = null
)

private data class ShootingChip(
    val icon: ImageVector,
    val label: String,
    val highlighted: Boolean = false
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraSettingsCard(
    item: MediaItem,
    liveExif: ExifMetadata,
    cardColor: androidx.compose.ui.graphics.Color,
    hairlineBorder: androidx.compose.ui.graphics.Color
) {
    val w = item.effectiveWidth
    val h = item.effectiveHeight
    val mpLabel = FormatUtils.formatMegapixels(w, h)
    val resLabel = if (w != null && h != null && w > 0 && h > 0) "$w × $h" else null
    val aspectLabel = FormatUtils.formatAspectRatio(w, h)
    val sizeLabel = FormatUtils.formatFileSize(item.sizeBytes)

    val formattedLens = remember(liveExif.lens, liveExif.cameraMake, liveExif.cameraModel) {
        FormatUtils.formatLensName(liveExif.lens, liveExif.cameraMake, liveExif.cameraModel)
    }

    val primaryTiles = remember(item.isVideo, item.durationMillis, liveExif) {
        if (item.isVideo) {
            buildList {
                item.durationMillis?.takeIf { it > 0L }?.let {
                    add(ShootingMetricTile(Icons.Rounded.Timer, "Duration", FormatUtils.formatDuration(it)))
                }
                FormatUtils.formatFrameRate(liveExif.videoFrameRate)?.let {
                    add(ShootingMetricTile(Icons.Rounded.SlowMotionVideo, "Frame rate", it))
                }
                FormatUtils.formatBitrate(liveExif.videoBitrateBps)?.let {
                    add(ShootingMetricTile(Icons.Rounded.Speed, "Bitrate", it))
                }
                liveExif.videoCodec?.takeIf { it.isNotBlank() }?.let {
                    add(ShootingMetricTile(Icons.Rounded.Videocam, "Codec", it))
                }
            }
        } else {
            buildList {
                FormatUtils.formatAperture(liveExif.aperture)?.let {
                    add(ShootingMetricTile(Icons.Rounded.Camera, "Aperture", it))
                }
                FormatUtils.formatShutterSpeed(liveExif.exposureTime)?.let {
                    add(ShootingMetricTile(Icons.Rounded.ShutterSpeed, "Shutter", it))
                }
                liveExif.isoSpeed?.let {
                    add(ShootingMetricTile(Icons.Rounded.Grain, "ISO", "$it"))
                }
                val focalActual = FormatUtils.formatFocalLength(liveExif.focalLength)
                val focal35 = liveExif.focalLength35mm
                if (focalActual != null) {
                    val eqSuffix = if (
                        focal35 != null &&
                        liveExif.focalLength != null &&
                        abs(focal35 - liveExif.focalLength.roundToInt()) >= 2
                    ) {
                        "${focal35}mm eq"
                    } else null
                    add(ShootingMetricTile(Icons.Rounded.FilterCenterFocus, "Focal", focalActual, secondary = eqSuffix))
                } else if (focal35 != null) {
                    add(ShootingMetricTile(Icons.Rounded.FilterCenterFocus, "Focal", "$focal35 mm", secondary = "35mm eq"))
                }
            }
        }
    }

    val shootingChips = remember(item.isVideo, liveExif) {
        if (item.isVideo) {
            buildList {
                liveExif.audioSummary?.takeIf { it.isNotBlank() }?.let {
                    add(ShootingChip(Icons.AutoMirrored.Rounded.VolumeUp, it))
                }
            }
        } else {
            buildList {
                liveExif.exposureBiasEv?.takeIf { it.isFinite() && abs(it) >= 0.05f }?.let { ev ->
                    FormatUtils.formatExposureBias(ev)?.let { formatted ->
                        add(ShootingChip(Icons.Rounded.Exposure, formatted, highlighted = true))
                    }
                }
                liveExif.exposureMode?.takeIf { it.isNotBlank() }?.let { mode ->
                    add(ShootingChip(Icons.Rounded.Tune, mode))
                }
                liveExif.meteringMode?.takeIf { it.isNotBlank() }?.let { metering ->
                    add(ShootingChip(Icons.Rounded.Adjust, metering))
                }
                liveExif.whiteBalance?.takeIf { it.isNotBlank() }?.let { wb ->
                    add(
                        ShootingChip(
                            icon = Icons.Rounded.WbSunny,
                            label = "WB · $wb",
                            highlighted = wb.equals("Manual", ignoreCase = true)
                        )
                    )
                }
                liveExif.flashDescription?.takeIf { it.contains("Fired", ignoreCase = true) }?.let { flash ->
                    val label = if (flash.equals("Fired", ignoreCase = true)) "Flash fired" else "Flash · $flash"
                    add(ShootingChip(Icons.Rounded.FlashOn, label, highlighted = true))
                }
                liveExif.digitalZoomRatio?.let { zoom ->
                    add(ShootingChip(Icons.Rounded.ZoomIn, String.format(Locale.US, "%.1f× zoom", zoom)))
                }
            }
        }
    }

    val hasShootingTelemetry = primaryTiles.isNotEmpty() || shootingChips.isNotEmpty() || liveExif.hasCameraDetails
    val fileSpecsLeft = remember(resLabel, mpLabel, aspectLabel, item.isVideo) {
        buildList {
            resLabel?.let { add(it) }
            if (!item.isVideo) mpLabel?.let { add(it) }
            aspectLabel?.let { add(it) }
        }.joinToString(" · ")
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardColor,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.7.dp, hairlineBorder, RoundedCornerShape(24.dp))
    ) {
        if (!hasShootingTelemetry) {
            // Compact summary for screenshots / graphics without camera EXIF
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
                        imageVector = if (item.isVideo) Icons.Rounded.Movie else Icons.Rounded.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = when {
                            item.isVideo -> "Video"
                            item.isRaw -> "RAW Photograph"
                            else -> "Image"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (fileSpecsLeft.isNotEmpty()) {
                        Text(
                            text = fileSpecsLeft,
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = sizeLabel,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Gear Identity Header (Camera Body + Clean Lens Name)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (item.isVideo && !liveExif.hasCameraDetails) {
                                Icons.Rounded.Movie
                            } else {
                                Icons.Rounded.CameraAlt
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = liveExif.cameraDisplayName
                                ?: if (item.isVideo) "Video capture" else if (item.isRaw) "RAW Photograph" else "Camera settings",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.5.sp,
                                letterSpacing = (-0.2).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!formattedLens.isNullOrBlank()) {
                            Text(
                                text = formattedLens,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Core 2×2 Exposure & Optics Bento Grid
                if (primaryTiles.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        primaryTiles.chunked(2).forEach { rowTiles ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowTiles.forEach { tile ->
                                    ShootingMetricTileCard(
                                        tile = tile,
                                        hairlineBorder = hairlineBorder,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (rowTiles.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // Focused Secondary Shooting Chips (EV bias, Mode, Metering, WB, Flash when fired)
                if (shootingChips.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 14.dp,
                                end = 14.dp,
                                top = if (primaryTiles.isNotEmpty()) 10.dp else 0.dp
                            ),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        shootingChips.forEach { chip ->
                            ShootingChipBadge(chip = chip)
                        }
                    }
                }

                if (primaryTiles.isNotEmpty() || shootingChips.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                }

                // Quiet File Geometry & Size Footer
                HorizontalDivider(thickness = 0.6.dp, color = hairlineBorder)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.28f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = fileSpecsLeft.ifEmpty { if (item.isVideo) "Video file" else "Image file" },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.5.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = sizeLabel,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ShootingMetricTileCard(
    tile: ShootingMetricTile,
    hairlineBorder: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.56f))
            .border(0.6.dp, hairlineBorder.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = tile.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = tile.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!tile.secondary.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = tile.secondary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum"
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = tile.value,
            style = TextStyle(
                fontSize = 17.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
                fontFeatureSettings = "tnum",
                color = MaterialTheme.colorScheme.onSurface
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ShootingChipBadge(chip: ShootingChip) {
    val bgColor = if (chip.highlighted) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
    }
    val contentColor = if (chip.highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    }
    val iconTint = if (chip.highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = chip.icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(13.5.dp)
        )
        Text(
            text = chip.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.5.sp,
                fontWeight = if (chip.highlighted) FontWeight.SemiBold else FontWeight.Medium,
                fontFeatureSettings = "tnum"
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

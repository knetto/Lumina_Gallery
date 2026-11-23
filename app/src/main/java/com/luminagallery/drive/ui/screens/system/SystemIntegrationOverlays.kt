package com.luminagallery.drive.ui.screens.system

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.size.Precision
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.repository.LocalFileOperationsManager
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.ui.components.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Full-screen Android system media picker supporting `ACTION_PICK` and `ACTION_GET_CONTENT`
 * (single or `EXTRA_ALLOW_MULTIPLE`), filtering by requested MIME type and automatically
 * hiding locked & hidden albums so private photos are never exposed to third-party apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemMediaPickerScreen(
    mimeFilter: String,
    allowMultiple: Boolean,
    onPicked: (List<Uri>) -> Unit,
    onCancel: () -> Unit
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current

    val allLocal by container.localMediaRepository.observeAllLocalMedia()
        .collectAsStateWithLifecycle(initialValue = container.localMediaRepository.getCachedAllMedia())
    val localAlbums by container.localMediaRepository.observeLocalAlbums()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val excludedIds by container.galleryExtrasRepository.excludedAlbumIdsFlow
        .collectAsStateWithLifecycle(
            initialValue = container.galleryExtrasRepository.latestLockedAlbumIds +
                container.galleryExtrasRepository.latestHiddenAlbumIds
        )

    var selectedBucketId by remember { mutableStateOf<String?>(null) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val wantImagesOnly = mimeFilter.startsWith("image/", ignoreCase = true) ||
        mimeFilter.equals("vnd.android.cursor.dir/image", ignoreCase = true)
    val wantVideosOnly = mimeFilter.startsWith("video/", ignoreCase = true) ||
        mimeFilter.equals("vnd.android.cursor.dir/video", ignoreCase = true)

    val visibleAlbums = remember(localAlbums, excludedIds) {
        localAlbums.filter {
            it.id !in excludedIds &&
                it.id != LocalMediaRepository.LOCAL_BUCKET_ALL &&
                it.id != LocalMediaRepository.LOCAL_BUCKET_VIDEOS
        }
    }

    val filteredMedia = remember(allLocal, excludedIds, selectedBucketId, wantImagesOnly, wantVideosOnly) {
        allLocal.filter { item ->
            if (item.parentId in excludedIds) return@filter false
            if (item.localContentUri.isNullOrBlank()) return@filter false
            if (item.localContentUri.startsWith("file://")) return@filter false
            if (wantImagesOnly && item.isVideo) return@filter false
            if (wantVideosOnly && !item.isVideo) return@filter false
            if (selectedBucketId != null && item.parentId != selectedBucketId) return@filter false
            true
        }
    }

    BackHandler(onBack = onCancel)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    val label = when {
                        allowMultiple && selectedUris.isNotEmpty() -> "${selectedUris.size} selected"
                        wantVideosOnly -> "Choose video"
                        wantImagesOnly -> "Choose photo"
                        else -> "Choose media"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    if (allowMultiple) {
                        TextButton(
                            enabled = selectedUris.isNotEmpty(),
                            onClick = { onPicked(selectedUris) }
                        ) {
                            Text("Done", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (visibleAlbums.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "pick_all") {
                        FilterChip(
                            selected = selectedBucketId == null,
                            onClick = { selectedBucketId = null },
                            label = { Text("All") }
                        )
                    }
                    items(visibleAlbums, key = { it.id }) { album ->
                        FilterChip(
                            selected = selectedBucketId == album.id,
                            onClick = { selectedBucketId = album.id },
                            label = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }

            if (filteredMedia.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No matching media found on device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredMedia, key = { it.id }) { item ->
                        val uri = remember(item.localContentUri) { Uri.parse(item.localContentUri) }
                        val selIdx = selectedUris.indexOf(uri)
                        val isSelected = selIdx >= 0

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable {
                                    if (!allowMultiple) {
                                        onPicked(listOf(uri))
                                    } else {
                                        selectedUris = if (isSelected) {
                                            selectedUris - uri
                                        } else {
                                            selectedUris + uri
                                        }
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(LocalThumbnailRequest(item.localContentUri!!, item.isVideo))
                                    .size(320)
                                    .precision(Precision.INEXACT)
                                    .allowRgb565(true)
                                    .build(),
                                imageLoader = imageLoader,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (item.isVideo) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Black.copy(alpha = 0.62f))
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = FormatUtils.formatDuration(item.durationMillis),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                            if (allowMultiple) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else Color.Black.copy(alpha = 0.38f)
                                        )
                                        .border(
                                            width = if (isSelected) 0.dp else 1.5.dp,
                                            color = Color.White,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean action screen for incoming `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intents:
 * lets the user save shared photo(s)/video(s) into a chosen device album folder or open
 * a single photo straight into Lumina's Photo Editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingShareSheetScreen(
    uris: List<Uri>,
    mimeType: String?,
    onOpenEditor: (uri: String, displayName: String) -> Unit,
    onCompleted: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val scope = rememberCoroutineScope()

    var albumName by remember { mutableStateOf("LuminaGallery") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val singleImageUri = remember(uris, mimeType) {
        if (uris.size == 1) {
            val u = uris.first()
            val resolvedMime = mimeType ?: runCatching { context.contentResolver.getType(u) }.getOrNull()
            if (resolvedMime == null || resolvedMime.startsWith("image/", ignoreCase = true)) u else null
        } else null
    }

    val localAlbums by container.localMediaRepository.observeLocalAlbums()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val excludedIds by container.galleryExtrasRepository.excludedAlbumIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val quickAlbums = remember(localAlbums, excludedIds) {
        localAlbums.filter {
            it.id !in excludedIds &&
                it.id != LocalMediaRepository.LOCAL_BUCKET_ALL &&
                it.id != LocalMediaRepository.LOCAL_BUCKET_VIDEOS
        }.take(10)
    }

    BackHandler(enabled = !isSaving, onBack = onDismiss)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uris.size == 1) "Save to Lumina Gallery" else "Save ${uris.size} items",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uris.take(10), key = { it.toString() }) { u ->
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(u).size(240).build(),
                            imageLoader = imageLoader,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            if (singleImageUri != null) {
                OutlinedButton(
                    onClick = {
                        val name = resolveSharedDisplayName(context, singleImageUri, 0)
                        onOpenEditor(singleImageUri.toString(), name)
                    },
                    enabled = !isSaving,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit in Darkroom Studio")
                }
            }

            Text(
                text = "Destination album",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = albumName,
                onValueChange = { albumName = it },
                label = { Text("Album folder name") },
                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (quickAlbums.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickAlbums, key = { it.id }) { album ->
                        FilterChip(
                            selected = albumName.equals(album.name, ignoreCase = true),
                            onClick = { albumName = album.name },
                            leadingIcon = {
                                Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }

            errorMsg?.let { err ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    val cleanAlbum = LocalFileOperationsManager.sanitizeFolderName(albumName)
                    if (cleanAlbum.isBlank()) {
                        errorMsg = "Enter a valid album name"
                        return@Button
                    }
                    isSaving = true
                    errorMsg = null
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            runCatching {
                                saveIncomingUrisToAlbum(context, uris, mimeType, cleanAlbum)
                            }
                        }
                        isSaving = false
                        res.onSuccess { count ->
                            container.localMediaRepository.notifyPermissionChanged()
                            onCompleted("Saved $count item(s) to $cleanAlbum")
                        }.onFailure { err ->
                            errorMsg = err.localizedMessage ?: "Unable to save shared media"
                        }
                    }
                },
                enabled = !isSaving && albumName.isNotBlank() && uris.isNotEmpty(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Save to \"$albumName\"", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun resolveSharedDisplayName(context: Context, uri: Uri, index: Int): String {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "shared_${System.currentTimeMillis()}_$index.jpg"
}

private fun saveIncomingUrisToAlbum(
    context: Context,
    uris: List<Uri>,
    fallbackMime: String?,
    cleanAlbum: String
): Int {
    val resolver = context.contentResolver
    var saved = 0
    uris.forEachIndexed { idx, srcUri ->
        val rawMime = runCatching { resolver.getType(srcUri) }.getOrNull()
            ?: fallbackMime
        val rawName = resolveSharedDisplayName(context, srcUri, idx)
        val isVideo = com.luminagallery.drive.data.repository.VideoFormatSupport.isVideoFile(rawName, rawMime)
        val mime = if (isVideo) {
            com.luminagallery.drive.data.repository.VideoFormatSupport.normalizeVideoMimeType(rawName, rawMime)
        } else {
            rawMime ?: "image/jpeg"
        }
        val displayName = if (rawName.contains('.')) {
            rawName
        } else {
            rawName + if (isVideo) ".mp4" else ".jpg"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val rootDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$rootDir/$cleanAlbum")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dstUri = resolver.insert(collection, values)
                ?: throw IOException("Could not create MediaStore entry")
            try {
                resolver.openInputStream(srcUri)?.use { input ->
                    resolver.openOutputStream(dstUri)?.use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    } ?: throw IOException("Could not open destination stream")
                } ?: throw IOException("Could not read shared file")
                resolver.update(dstUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                saved++
            } catch (e: Exception) {
                resolver.delete(dstUri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val baseDir = Environment.getExternalStoragePublicDirectory(
                if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            )
            val targetDir = File(baseDir, cleanAlbum).apply { if (!exists()) mkdirs() }
            val outFile = File(targetDir, displayName)
            resolver.openInputStream(srcUri)?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: throw IOException("Could not read shared file")
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf(mime), null)
            saved++
        }
    }
    return saved
}

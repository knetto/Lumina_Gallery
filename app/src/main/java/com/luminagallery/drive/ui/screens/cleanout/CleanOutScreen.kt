package com.luminagallery.drive.ui.screens.cleanout

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.luminagallery.drive.AppContainer
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.repository.CoilCacheManager
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.CleanOutCategory
import com.luminagallery.drive.domain.model.CleanOutGroup
import com.luminagallery.drive.domain.model.DuplicateCluster
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.HyperOsTrashConfirmSheet
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.TrashConfirmMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class CleanOutUiState(
    val isScanning: Boolean = true,
    val groups: List<CleanOutGroup> = emptyList(),
    val driveCacheBytes: Long = 0L,
    val ignoredCount: Int = 0,
    val pendingTrashSender: IntentSender? = null,
    val snackbarMessage: String? = null
) {
    val totalReclaimableBytes: Long
        get() {
            // Deduplicate overlapping suggestions across categories (e.g. a large video that is also a duplicate)
            val seenItemSizes = LinkedHashMap<String, Long>()
            for (group in groups) {
                val reclaimItems = if (group.category == CleanOutCategory.DUPLICATES ||
                    group.category == CleanOutCategory.BURST_SIMILAR
                ) {
                    group.items.filter { it.id in group.preselectedIds }
                } else {
                    group.items
                }
                for (item in reclaimItems) {
                    seenItemSizes.putIfAbsent(item.id, item.sizeBytes)
                }
            }
            val visibleDriveCache = if (driveCacheBytes > 1024L * 1024L) driveCacheBytes else 0L
            return seenItemSizes.values.sum() + visibleDriveCache
        }
}

class CleanOutViewModel(
    private val localMediaRepository: LocalMediaRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val coilCacheManager: CoilCacheManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CleanOutUiState())
    val uiState: StateFlow<CleanOutUiState> = _uiState.asStateFlow()

    init {
        runSmartScan()
    }

    fun runSmartScan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }
            val excludedAlbumIds = extrasRepository.excludedAlbumIdsFlow.first()
            val allLocal = localMediaRepository.observeAllLocalMedia().first()
                .let { list ->
                    if (excludedAlbumIds.isEmpty()) list
                    else list.filterNot { it.parentId in excludedAlbumIds }
                }
            val ignoredIds = extrasRepository.ignoredCleanOutIdsFlow.first()
            val groups = localMediaRepository.scanCleanOutSuggestions(allLocal, ignoredIds)
            val cacheBytes = coilCacheManager.refreshDiskUsage()
            _uiState.update {
                it.copy(
                    isScanning = false,
                    groups = groups,
                    driveCacheBytes = cacheBytes,
                    ignoredCount = ignoredIds.size
                )
            }
        }
    }

    fun ignoreItems(itemIds: Collection<String>, label: String = "Ignored") {
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            extrasRepository.ignoreCleanOutItems(itemIds)
            _uiState.update { it.copy(snackbarMessage = label) }
            runSmartScan()
        }
    }

    fun restoreAllIgnored() {
        viewModelScope.launch {
            extrasRepository.clearIgnoredCleanOut()
            _uiState.update { it.copy(snackbarMessage = "Restored ignored suggestions") }
            runSmartScan()
        }
    }

    fun clearDriveCache() {
        viewModelScope.launch {
            coilCacheManager.clearThumbnailCache()
            val updated = coilCacheManager.refreshDiskUsage()
            _uiState.update {
                it.copy(
                    driveCacheBytes = updated,
                    snackbarMessage = "Cleared Google Drive thumbnail cache"
                )
            }
        }
    }

    fun requestTrashItems(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val supportsTrash = localMediaRepository.supportsMediaStoreTrash()
        if (localMediaRepository.canDeleteDirectlyWithoutDialog()) {
            viewModelScope.launch {
                localMediaRepository.trashLocalItemsDirectly(items, toTrash = true)
                    .onSuccess { count ->
                        localMediaRepository.notifyPermissionChanged()
                        _uiState.update {
                            it.copy(
                                snackbarMessage = if (!supportsTrash) {
                                    if (count == 1) "Deleted permanently" else "Deleted $count items permanently"
                                } else {
                                    if (count == 1) "Moved to trash" else "Moved $count items to trash"
                                }
                            )
                        }
                        runSmartScan()
                    }
                    .onFailure { err ->
                        val sender = localMediaRepository.buildTrashIntentSender(items, toTrash = true)
                        if (sender != null) {
                            _uiState.update { it.copy(pendingTrashSender = sender) }
                        } else {
                            _uiState.update {
                                it.copy(snackbarMessage = err.localizedMessage ?: "Unable to delete items")
                            }
                        }
                    }
            }
            return
        }
        val sender = localMediaRepository.buildTrashIntentSender(items, toTrash = true)
        if (sender != null) {
            _uiState.update { it.copy(pendingTrashSender = sender) }
        } else {
            _uiState.update { it.copy(snackbarMessage = "Unable to move items to trash") }
        }
    }

    fun onTrashCompleted(approved: Boolean) {
        _uiState.update { it.copy(pendingTrashSender = null) }
        if (approved) {
            localMediaRepository.notifyPermissionChanged()
            _uiState.update { it.copy(snackbarMessage = "Moved selected items to trash") }
            runSmartScan()
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CleanOutViewModel(
            localMediaRepository = container.localMediaRepository,
            extrasRepository = container.galleryExtrasRepository,
            coilCacheManager = container.coilCacheManager
        ) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanOutScreen(
    viewModel: CleanOutViewModel,
    onNavigateBack: () -> Unit,
    onOpenPreview: (MediaItem) -> Unit = {},
    onOpenCompare: (List<String>) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var reviewingCategory by remember { mutableStateOf<CleanOutCategory?>(null) }
    var pendingCleanOutTrash by remember { mutableStateOf<List<MediaItem>?>(null) }
    var showRestoreIgnoredDialog by remember { mutableStateOf(false) }

    val activeReviewGroup = remember(uiState.groups, reviewingCategory) {
        reviewingCategory?.let { cat -> uiState.groups.firstOrNull { it.category == cat } }
    }

    // Automatically close review sheet if all items in that category were cleaned or ignored
    LaunchedEffect(activeReviewGroup, reviewingCategory, uiState.isScanning) {
        if (!uiState.isScanning && reviewingCategory != null && activeReviewGroup == null) {
            reviewingCategory = null
        }
    }

    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val approved = result.resultCode == Activity.RESULT_OK
        if (approved) reviewingCategory = null
        viewModel.onTrashCompleted(approved)
    }

    LaunchedEffect(uiState.pendingTrashSender) {
        val sender = uiState.pendingTrashSender ?: return@LaunchedEffect
        trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
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
            TopAppBar(
                title = {
                    Text(
                        text = "Clean out",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.ignoredCount > 0) {
                        TextButton(onClick = { showRestoreIgnoredDialog = true }) {
                            Text("Ignored (${uiState.ignoredCount})")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (uiState.isScanning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Analyzing duplicates, bursts & large files…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Hero Smart Summary Card
                item(key = "cleanout_hero") {
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Smart Storage Suggestions",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = FormatUtils.formatFileSize(uiState.totalReclaimableBytes),
                                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (uiState.groups.isEmpty() && uiState.driveCacheBytes == 0L) {
                                    "Your gallery is already super clean!"
                                } else {
                                    "Potential space you can safely reclaim — trashed items stay recoverable for 30 days."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Drive Thumbnail Cache Card
                if (uiState.driveCacheBytes > 1024L * 1024L) {
                    item(key = "drive_cache_card") {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Storage,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Google Drive thumbnail cache",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = FormatUtils.formatFileSize(uiState.driveCacheBytes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FilledTonalButton(
                                    onClick = viewModel::clearDriveCache,
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Clear")
                                }
                            }
                        }
                    }
                }

                // Smart Suggestion Categories
                items(
                    items = uiState.groups,
                    key = { it.category.name }
                ) { group ->
                    CleanOutCategoryCard(
                        group = group,
                        onReviewClick = { reviewingCategory = group.category }
                    )
                }

                if (uiState.groups.isEmpty()) {
                    item(key = "all_clean") {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CleaningServices,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text("No clutter detected", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "No exact duplicates, huge files, burst clusters, or old screenshots found.",
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

    // Interactive Review & Clean Sheet
    activeReviewGroup?.let { group ->
        CleanOutReviewSheet(
            group = group,
            onDismiss = { reviewingCategory = null },
            onPreviewItem = { item ->
                onOpenPreview(item)
            },
            onCompareItems = { ids ->
                reviewingCategory = null
                onOpenCompare(ids)
            },
            onIgnoreCluster = { cluster ->
                viewModel.ignoreItems(
                    itemIds = cluster.items.map { it.id },
                    label = "Ignored duplicate set"
                )
            },
            onIgnoreSelected = { chosen ->
                viewModel.ignoreItems(
                    itemIds = chosen.map { it.id },
                    label = "Ignored ${chosen.size} item(s)"
                )
            },
            onMoveToTrash = { chosen -> pendingCleanOutTrash = chosen }
        )
    }

    if (showRestoreIgnoredDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreIgnoredDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Restore ignored suggestions?") },
            text = {
                Text(
                    text = "All ${uiState.ignoredCount} previously ignored Clean Out item(s) will be shown again in future scans.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreIgnoredDialog = false
                        viewModel.restoreAllIgnored()
                    }
                ) { Text("Restore all", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreIgnoredDialog = false }) { Text("Cancel") }
            }
        )
    }

    pendingCleanOutTrash?.let { chosen ->
        HyperOsTrashConfirmSheet(
            items = chosen,
            mode = TrashConfirmMode.MOVE_TO_TRASH,
            canSkipSystemPrompt = container.localMediaRepository.canDeleteDirectlyWithoutDialog(),
            supportsSkipPromptSetting = container.localMediaRepository.supportsManageMediaPermission(),
            onOpenManageMediaSettings = {
                container.localMediaRepository.buildManageMediaSettingsIntent()?.let { intent ->
                    runCatching { context.startActivity(intent) }
                }
            },
            onConfirm = {
                pendingCleanOutTrash = null
                if (container.localMediaRepository.canDeleteDirectlyWithoutDialog()) {
                    reviewingCategory = null
                }
                viewModel.requestTrashItems(chosen)
            },
            onDismiss = { pendingCleanOutTrash = null }
        )
    }
}

@Composable
private fun CleanOutCategoryCard(
    group: CleanOutGroup,
    onReviewClick: () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val icon = when (group.category) {
        CleanOutCategory.LARGE_FILES -> Icons.Rounded.VideoFile
        CleanOutCategory.DUPLICATES -> Icons.Rounded.ContentCopy
        CleanOutCategory.BURST_SIMILAR -> Icons.Rounded.BurstMode
        CleanOutCategory.OLD_SCREENSHOTS -> Icons.Rounded.Screenshot
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onReviewClick)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = group.category.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        val countDesc = if (group.category == CleanOutCategory.DUPLICATES && group.duplicateClusters.isNotEmpty()) {
                            "${group.duplicateClusters.size} sets (${group.items.size} files) · ${FormatUtils.formatFileSize(group.reclaimableBytes)}"
                        } else {
                            "${group.items.size} items · ${FormatUtils.formatFileSize(group.reclaimableBytes)}"
                        }
                        Text(
                            text = countDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "Review",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(group.items.take(8), key = { it.id }) { item ->
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(item.localContentUri).build(),
                            imageLoader = imageLoader,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanOutReviewSheet(
    group: CleanOutGroup,
    onDismiss: () -> Unit,
    onPreviewItem: (MediaItem) -> Unit,
    onCompareItems: (List<String>) -> Unit,
    onIgnoreCluster: (DuplicateCluster) -> Unit,
    onIgnoreSelected: (List<MediaItem>) -> Unit,
    onMoveToTrash: (List<MediaItem>) -> Unit
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    var selectedIds by remember(group.category, group.items.size) { mutableStateOf(group.preselectedIds) }

    val selectedItems = remember(group.items, selectedIds) {
        group.items.filter { it.id in selectedIds }
    }
    val selectedBytes = remember(selectedItems) { selectedItems.sumOf { it.sizeBytes } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(
                    text = group.category.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = group.category.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (group.category == CleanOutCategory.DUPLICATES && group.duplicateClusters.isNotEmpty()) {
                // Rich Duplicate Cluster List showing exact Source Folder + Preview + Compare + Ignore!
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                ) {
                    items(
                        items = group.duplicateClusters,
                        key = { it.clusterKey }
                    ) { cluster ->
                        val comparableIds = remember(cluster.items) {
                            cluster.items.filter { !it.isVideo }.map { it.id }.take(4)
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${cluster.items.size} copies · ${FormatUtils.formatFileSize(cluster.sizeBytes)} each",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (comparableIds.size >= 2) {
                                            TextButton(
                                                onClick = { onCompareItems(comparableIds) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Compare,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text("Compare", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                        TextButton(
                                            onClick = { onIgnoreCluster(cluster) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.VisibilityOff,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Ignore", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }

                                cluster.items.forEachIndexed { idx, item ->
                                    if (idx > 0) {
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                        )
                                    }
                                    val checked = item.id in selectedIds
                                    val sourcePath = remember(item.relativePath, item.storagePath) {
                                        FormatUtils.formatReadableSourcePath(item.relativePath, item.storagePath)
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                selectedIds = if (checked) selectedIds - item.id else selectedIds + item.id
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                                .clickable { onPreviewItem(item) }
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(context)
                                                    .data(
                                                        if (item.isVideo && !item.localContentUri.isNullOrBlank()) {
                                                            LocalThumbnailRequest(item.localContentUri, isVideo = true)
                                                        } else item.localContentUri
                                                    )
                                                    .build(),
                                                imageLoader = imageLoader,
                                                contentDescription = "Preview ${item.name}",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = item.name,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (idx == 0) {
                                                    Text(
                                                        text = "Original",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }

                                            // Prominent Source Folder Path Row!
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Folder,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = sourcePath,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Text(
                                                text = item.fullDateLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }

                                        CleanOutCheckCircle(checked = checked)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (group.category == CleanOutCategory.BURST_SIMILAR) {
                // Visual burst-shot cluster grouping so user can compare & pick the sharpest shot!
                val burstClusters = remember(group.items) {
                    val sorted = group.items.sortedBy { it.createdTimeEpochMs }
                    val clusters = mutableListOf<List<MediaItem>>()
                    val current = mutableListOf<MediaItem>()
                    for (item in sorted) {
                        val prev = current.lastOrNull()
                        if (prev != null && kotlin.math.abs(item.createdTimeEpochMs - prev.createdTimeEpochMs) > 6_000L) {
                            if (current.isNotEmpty()) clusters.add(current.toList())
                            current.clear()
                        }
                        current.add(item)
                    }
                    if (current.isNotEmpty()) clusters.add(current.toList())
                    clusters.reversed()
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                ) {
                    items(burstClusters, key = { it.first().id }) { burst ->
                        val comparableIds = remember(burst) {
                            burst.filter { !it.isVideo }.map { it.id }.take(4)
                        }
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${burst.size} burst shots · ${burst.first().fullDateLabel}",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (comparableIds.size >= 2) {
                                        TextButton(
                                            onClick = { onCompareItems(comparableIds) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Rounded.Compare, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Compare", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(burst, key = { it.id }) { item ->
                                        val checked = item.id in selectedIds
                                        Box(
                                            modifier = Modifier
                                                .size(96.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    selectedIds = if (checked) selectedIds - item.id else selectedIds + item.id
                                                }
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(context).data(item.localContentUri).build(),
                                                imageLoader = imageLoader,
                                                contentDescription = item.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(5.dp)
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.Black.copy(alpha = 0.55f))
                                                    .clickable { onPreviewItem(item) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Visibility,
                                                    contentDescription = "Preview",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                            if (item.id == burst.first().id) {
                                                Text(
                                                    text = "Keep",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    modifier = Modifier
                                                        .align(Alignment.BottomStart)
                                                        .padding(5.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                            CleanOutCheckCircle(
                                                checked = checked,
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(5.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
                    items(group.items, key = { it.id }) { item ->
                        val checked = item.id in selectedIds
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selectedIds = if (checked) selectedIds - item.id else selectedIds + item.id
                                }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(
                                        if (item.isVideo && !item.localContentUri.isNullOrBlank()) {
                                            LocalThumbnailRequest(item.localContentUri, isVideo = true)
                                        } else item.localContentUri
                                    )
                                    .build(),
                                imageLoader = imageLoader,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(5.dp)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickable { onPreviewItem(item) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = "Preview",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(5.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.68f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = FormatUtils.formatFileSize(item.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    maxLines = 1
                                )
                                item.relativePath?.let { album ->
                                    Text(
                                        text = album,
                                        fontSize = 9.sp,
                                        color = Color.White.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            CleanOutCheckCircle(
                                checked = checked,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onIgnoreSelected(selectedItems) },
                    enabled = selectedItems.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(50.dp)
                ) {
                    Icon(Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ignore")
                }

                Button(
                    onClick = { onMoveToTrash(selectedItems) },
                    enabled = selectedItems.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (selectedItems.isEmpty()) "Select items"
                        else "Trash ${selectedItems.size} (${FormatUtils.formatFileSize(selectedBytes)})",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanOutCheckCircle(
    checked: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else Color.Black.copy(alpha = 0.35f)
            )
            .border(
                width = if (checked) 0.dp else 1.5.dp,
                color = Color.White,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

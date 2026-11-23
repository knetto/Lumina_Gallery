package com.luminagallery.drive.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.Identity
import com.luminagallery.drive.BuildConfig
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.R
import com.luminagallery.drive.data.repository.AppUpdateState
import com.luminagallery.drive.data.repository.InstalledGalleryApp
import com.luminagallery.drive.domain.model.AlbumSortMode
import com.luminagallery.drive.domain.model.MainTab
import com.luminagallery.drive.domain.model.ServiceAccountState
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.ui.components.FormatUtils
import java.util.Locale

private enum class SettingsPickerSheet {
    THEME,
    START_TAB,
    GRID_DENSITY,
    ALBUM_SORT,
    CACHE_LIMIT,
    EXTERNAL_GALLERY
}

private val OneUiGroupShape = RoundedCornerShape(26.dp)
private val OneUiSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContainer = LocalAppContainer.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var activePicker by remember { mutableStateOf<SettingsPickerSheet?>(null) }
    var showCloudProfileDialog by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showResetDefaultsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    val googleAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        runCatching {
            val authResult = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(activityResult.data)
            val token = authResult.accessToken
            if (!token.isNullOrBlank()) {
                val granted = authResult.grantedScopes
                val hasAppData = granted.isEmpty() ||
                    com.luminagallery.drive.data.remote.DriveScopes.DRIVE_APPDATA in granted
                viewModel.onGoogleSignInSuccess(
                    accessToken = token,
                    fallbackEmail = authResult.toGoogleSignInAccount()?.email,
                    grantedAppDataScope = hasAppData
                )
            } else {
                viewModel.showMessage("Google sign-in cancelled")
            }
        }.onFailure { err ->
            viewModel.showMessage(err.localizedMessage ?: "Google Sign-In failed")
        }
    }

    fun startGoogleSignIn() {
        val request = appContainer.tokenManager.buildGoogleAuthorizationRequest()
        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    result.pendingIntent?.let { pi ->
                        googleAuthLauncher.launch(
                            IntentSenderRequest.Builder(pi.intentSender).build()
                        )
                    }
                } else {
                    val token = result.accessToken
                    if (!token.isNullOrBlank()) {
                        val granted = result.grantedScopes
                        val hasAppData = granted.isEmpty() ||
                            com.luminagallery.drive.data.remote.DriveScopes.DRIVE_APPDATA in granted
                        viewModel.onGoogleSignInSuccess(
                            accessToken = token,
                            fallbackEmail = result.toGoogleSignInAccount()?.email,
                            grantedAppDataScope = hasAppData
                        )
                    }
                }
            }
            .addOnFailureListener { err ->
                viewModel.showMessage(err.localizedMessage ?: "Google Sign-In failed")
            }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    val prefs = uiState.preferences
    val configuredAccount = uiState.serviceAccountState as? ServiceAccountState.Configured
    val isSignedIn = configuredAccount != null

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "Gallery settings",
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. CLOUD & SYNC (Samsung "Cloud sync" style top card — NO JSON Import)
            item(key = "section_cloud") {
                OneUiSettingsSection(title = "Cloud sync") {
                    OneUiAccountRow(
                        account = configuredAccount,
                        onSignInClick = ::startGoogleSignIn,
                        onCopyEmail = { email ->
                            val clipboard = context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Google Account", email))
                            viewModel.showMessage("Copied email to clipboard")
                        },
                        onSignOutClick = { showSignOutDialog = true }
                    )

                    OneUiDivider()

                    OneUiActionRow(
                        title = "Sync now",
                        subtitle = if (uiState.syncStatus.isSyncing) {
                            "Syncing Google Drive changes…"
                        } else if (!isSignedIn) {
                            "Sign in to sync your Drive albums"
                        } else {
                            "Last synced · ${FormatUtils.formatLastSync(uiState.syncStatus.lastSyncEpochMs)}"
                        },
                        subtitleAccent = uiState.syncStatus.isSyncing,
                        enabled = isSignedIn && !uiState.syncStatus.isSyncing,
                        trailingContent = if (uiState.syncStatus.isSyncing) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        onClick = viewModel::triggerDeltaSync
                    )

                    OneUiDivider()

                    val cloudSync = uiState.cloudProfileSyncStatus
                    val cloudProfileSubtitle = when {
                        cloudSync.isSyncing -> "Syncing settings & album layout…"
                        !isSignedIn -> "Sign in with Google to sync settings, Drive links & nested albums"
                        cloudSync.needsScopeReconsent -> "Tap to grant backup permission (one-time Google prompt)"
                        cloudSync.lastSyncedEpochMs > 0L ->
                            "Backed up · ${FormatUtils.formatLastSync(cloudSync.lastSyncedEpochMs)}"
                        else -> "Syncs settings, Drive links & nested DCIM album groups"
                    }
                    OneUiActionRow(
                        title = "Account backup & restore",
                        subtitle = cloudProfileSubtitle,
                        subtitleAccent = cloudSync.isSyncing || cloudSync.needsScopeReconsent,
                        enabled = !cloudSync.isSyncing,
                        showChevron = isSignedIn && !cloudSync.needsScopeReconsent && !cloudSync.isSyncing,
                        trailingContent = if (cloudSync.isSyncing) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        onClick = {
                            if (!isSignedIn || cloudSync.needsScopeReconsent) {
                                startGoogleSignIn()
                            } else {
                                showCloudProfileDialog = true
                            }
                        }
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Background delta sync",
                        subtitle = "Sync automatically only while charging on unmetered Wi-Fi",
                        checked = prefs.backgroundSyncEnabled,
                        enabled = isSignedIn,
                        onCheckedChange = viewModel::setBackgroundSyncEnabled
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "High-res on Wi-Fi only",
                        subtitle = if (uiState.networkState.isUnmeteredWifi) {
                            "Wi-Fi active · HD thumbnails & pre-fetch enabled"
                        } else if (uiState.networkState.isOnline) {
                            "Mobile data active · eco thumbnails active"
                        } else {
                            "Offline · browsing cached albums & local media"
                        },
                        checked = prefs.wifiOnlyHighRes,
                        onCheckedChange = viewModel::setWifiOnlyHighRes
                    )
                }
            }

            // 2. DISPLAY & LAYOUT
            item(key = "section_display") {
                OneUiSettingsSection(title = "View & layout") {
                    OneUiValueRow(
                        title = "Theme",
                        value = themeModeLabel(prefs.themeMode),
                        onClick = { activePicker = SettingsPickerSheet.THEME }
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        OneUiDivider()
                        OneUiToggleRow(
                            title = "Color palette (Material You)",
                            subtitle = "Match accent color to wallpaper while keeping AMOLED black",
                            checked = prefs.dynamicColor,
                            onCheckedChange = viewModel::setDynamicColor
                        )
                    }

                    OneUiDivider()

                    OneUiValueRow(
                        title = "Default start tab",
                        value = startTabLabel(prefs.defaultStartTab),
                        onClick = { activePicker = SettingsPickerSheet.START_TAB }
                    )

                    OneUiDivider()

                    OneUiValueRow(
                        title = "Grid density",
                        value = gridColumnsLabel(prefs.gridColumns),
                        onClick = { activePicker = SettingsPickerSheet.GRID_DENSITY }
                    )

                    OneUiDivider()

                    OneUiValueRow(
                        title = "Album sort order",
                        value = prefs.albumSortMode.label,
                        onClick = { activePicker = SettingsPickerSheet.ALBUM_SORT }
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Show location in Pictures",
                        subtitle = "Display city and region beside date headers",
                        checked = prefs.showDayLocationHeaders,
                        onCheckedChange = viewModel::setShowDayLocationHeaders
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Show album sidebar rail",
                        subtitle = "Show quick-switch album rail inside folders",
                        checked = prefs.showAlbumSidebarDefault,
                        onCheckedChange = viewModel::setShowAlbumSidebarDefault
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Show cloud badges",
                        subtitle = "Display Google Drive badge on synced albums and photos",
                        checked = prefs.showCloudBadges,
                        onCheckedChange = viewModel::setShowCloudBadges
                    )
                }
            }

            // 3. VIEWER & VIDEO PLAYBACK (Signature Samsung Gallery viewer controls)
            item(key = "section_viewer") {
                OneUiSettingsSection(title = "Viewer & video playback") {
                    OneUiToggleRow(
                        title = "Auto-play videos",
                        subtitle = "Start playing videos automatically when opened",
                        checked = prefs.autoPlayVideos,
                        onCheckedChange = viewModel::setAutoPlayVideos
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Start videos muted",
                        subtitle = "Mute audio until you tap the sound icon",
                        checked = prefs.startVideosMuted,
                        onCheckedChange = viewModel::setStartVideosMuted
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Loop videos",
                        subtitle = "Automatically repeat videos when playback ends",
                        checked = prefs.loopVideos,
                        onCheckedChange = viewModel::setLoopVideos
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Show filmstrip in viewer",
                        subtitle = "Display scrollable thumbnail strip at the bottom of photos",
                        checked = prefs.showViewerFilmstrip,
                        onCheckedChange = viewModel::setShowViewerFilmstrip
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Maximum screen brightness",
                        subtitle = "Boost brightness when viewing full-screen photos and videos",
                        checked = prefs.maxViewerBrightness,
                        onCheckedChange = viewModel::setMaxViewerBrightness
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Keep screen on in viewer",
                        subtitle = "Prevent display timeout while browsing full-screen media",
                        checked = prefs.keepScreenOnViewer,
                        onCheckedChange = viewModel::setKeepScreenOnViewer
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Swipe down to close",
                        subtitle = "Drag downward on any photo or video to return to the grid",
                        checked = prefs.swipeDownToDismiss,
                        onCheckedChange = viewModel::setSwipeDownToDismiss
                    )

                    OneUiDivider()

                    OneUiToggleRow(
                        title = "Pre-fetch adjacent photos",
                        subtitle = "Preload next and previous Drive photos into RAM for instant swipes",
                        checked = prefs.prefetchAdjacentMedia,
                        onCheckedChange = viewModel::setPrefetchAdjacentMedia
                    )

                    OneUiDivider()

                    OneUiValueRow(
                        title = "External gallery app",
                        value = prefs.externalGalleryLabel.ifBlank { "Ask every time" },
                        onClick = { activePicker = SettingsPickerSheet.EXTERNAL_GALLERY }
                    )
                }
            }

            // 4. STORAGE & DATA
            item(key = "section_storage") {
                OneUiSettingsSection(title = "Storage & cache") {
                    OneUiStorageMeterBlock(
                        usedBytes = uiState.storageStats.diskCacheUsedBytes,
                        maxCacheMb = uiState.storageStats.maxCacheLimitMb,
                        cachedMediaCount = uiState.storageStats.cachedMediaCount,
                        cachedFolderCount = uiState.storageStats.cachedFolderCount
                    )

                    OneUiDivider()

                    OneUiValueRow(
                        title = "Thumbnail cache limit",
                        value = formatMbLabel(prefs.maxCacheSizeMb),
                        onClick = { activePicker = SettingsPickerSheet.CACHE_LIMIT }
                    )

                    OneUiDivider()

                    OneUiActionRow(
                        title = "Clear thumbnail cache",
                        subtitle = "Frees ${FormatUtils.formatFileSize(uiState.storageStats.diskCacheUsedBytes)} of cached thumbnails · original photos stay safe",
                        enabled = !uiState.isClearingCache,
                        trailingContent = if (uiState.isClearingCache) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        onClick = viewModel::clearThumbnailCache
                    )

                    OneUiDivider()

                    OneUiActionRow(
                        title = "Rebuild Drive folder index",
                        subtitle = "Refresh all cached Google Drive folders and metadata",
                        enabled = isSignedIn && !uiState.syncStatus.isSyncing,
                        onClick = viewModel::rebuildMetadataIndex
                    )

                    OneUiDivider()

                    OneUiActionRow(
                        title = "Clear offline Drive downloads",
                        subtitle = if (uiState.offlinePinnedCount > 0) {
                            "${uiState.offlinePinnedCount} pinned file(s) · ${FormatUtils.formatFileSize(uiState.offlinePinnedBytes)}"
                        } else {
                            "No Drive files pinned offline"
                        },
                        subtitleAccent = uiState.offlinePinnedCount > 0,
                        enabled = uiState.offlinePinnedCount > 0,
                        onClick = viewModel::clearAllOfflineMedia
                    )

                    OneUiDivider()

                    OneUiActionRow(
                        title = "Reset dismissed Clean Out items",
                        subtitle = if (uiState.ignoredCleanOutCount > 0) {
                            "${uiState.ignoredCleanOutCount} dismissed suggestion(s) will reappear"
                        } else {
                            "No dismissed Clean Out items"
                        },
                        enabled = uiState.ignoredCleanOutCount > 0,
                        onClick = viewModel::resetIgnoredCleanOut
                    )
                }
            }

            // 5. GENERAL & ABOUT
            item(key = "section_about") {
                OneUiSettingsSection(title = "General") {
                    OneUiToggleRow(
                        title = "Auto-check for updates",
                        subtitle = "Show a popup when a newer build is pushed to GitHub",
                        checked = prefs.autoCheckUpdates,
                        onCheckedChange = viewModel::setAutoCheckUpdates
                    )

                    OneUiDivider()

                    val isCheckingUpdate = updateState is AppUpdateState.Checking
                    val updateSubtitle = when (val u = updateState) {
                        is AppUpdateState.Checking -> "Checking GitHub for newer commits…"
                        is AppUpdateState.Available -> "Update #${u.update.shortSha} available · tap to install"
                        is AppUpdateState.Downloading -> "Downloading update… ${(u.progress * 100f).toInt()}%"
                        is AppUpdateState.ReadyToInstall -> "Update #${u.update.shortSha} ready · tap to install"
                        is AppUpdateState.UpToDate -> "Up to date · version ${BuildConfig.VERSION_NAME}"
                        is AppUpdateState.Error -> u.message
                        AppUpdateState.Idle -> "Version ${BuildConfig.VERSION_NAME} · tap to check GitHub"
                    }
                    val highlightUpdate = updateState is AppUpdateState.Available ||
                        updateState is AppUpdateState.Downloading ||
                        updateState is AppUpdateState.ReadyToInstall

                    OneUiActionRow(
                        title = "Check for updates",
                        subtitle = updateSubtitle,
                        subtitleAccent = highlightUpdate,
                        enabled = !isCheckingUpdate && updateState !is AppUpdateState.Downloading,
                        trailingContent = if (isCheckingUpdate) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        onClick = viewModel::checkForUpdatesManually
                    )

                    OneUiDivider()

                    OneUiActionRow(
                        title = "Reset settings to defaults",
                        subtitle = "Restore display, viewer, and sync preferences",
                        onClick = { showResetDefaultsDialog = true }
                    )

                    OneUiDivider()

                    OneUiActionRow(
                        title = "About Lumina Gallery",
                        subtitle = "Version ${BuildConfig.VERSION_NAME}",
                        subtitleAccent = true,
                        showChevron = true,
                        onClick = { showAboutDialog = true }
                    )
                }
            }
        }
    }

    // --- One UI Option Picker Bottom Sheets ---
    activePicker?.let { sheet ->
        ModalBottomSheet(
            onDismissRequest = { activePicker = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = OneUiSheetShape,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 6.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f))
                )
            }
        ) {
            when (sheet) {
                SettingsPickerSheet.THEME -> OneUiPickerSheetContent(
                    title = "Theme",
                    options = listOf(
                        PickerOption(ThemeMode.AMOLED_BLACK, "AMOLED black", "Pure #000000 black for OLED battery savings"),
                        PickerOption(ThemeMode.DARK, "Dark", "Deep charcoal dark mode"),
                        PickerOption(ThemeMode.LIGHT, "Light", "Clean bright One UI light palette"),
                        PickerOption(ThemeMode.SYSTEM, "Match system", "Follow Android system theme")
                    ),
                    selected = prefs.themeMode,
                    onSelect = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setThemeMode(it)
                        activePicker = null
                    }
                )

                SettingsPickerSheet.START_TAB -> OneUiPickerSheetContent(
                    title = "Default start tab",
                    options = listOf(
                        PickerOption(MainTab.PICTURES, "Pictures", "Chronological timeline of all photos & videos"),
                        PickerOption(MainTab.ALBUMS, "Albums", "Unified device & Google Drive album grid"),
                        PickerOption(MainTab.STORIES, "Stories", "Auto-curated trips and photo journals")
                    ),
                    selected = prefs.defaultStartTab,
                    onSelect = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setDefaultStartTab(it)
                        activePicker = null
                    }
                )

                SettingsPickerSheet.GRID_DENSITY -> OneUiPickerSheetContent(
                    title = "Grid density",
                    options = listOf(
                        PickerOption(2, "2 columns", "Large detailed thumbnails"),
                        PickerOption(3, "3 columns", "Balanced medium tiles"),
                        PickerOption(4, "4 columns", "Samsung Gallery default"),
                        PickerOption(5, "5 columns", "Compact overview for large libraries")
                    ),
                    selected = prefs.gridColumns,
                    onSelect = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setGridColumns(it)
                        activePicker = null
                    }
                )

                SettingsPickerSheet.ALBUM_SORT -> OneUiPickerSheetContent(
                    title = "Album sort order",
                    options = AlbumSortMode.entries.map { mode ->
                        PickerOption(
                            value = mode,
                            label = mode.label,
                            description = when (mode) {
                                AlbumSortMode.CUSTOM -> "Keep your manual drag-and-drop arrangement"
                                AlbumSortMode.NAME_ASC -> "Alphabetical order from A to Z"
                                AlbumSortMode.COUNT_DESC -> "Largest albums shown first"
                                AlbumSortMode.DATE_DESC -> "Most recently updated albums first"
                            }
                        )
                    },
                    selected = prefs.albumSortMode,
                    onSelect = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setAlbumSortMode(it)
                        activePicker = null
                    }
                )

                SettingsPickerSheet.CACHE_LIMIT -> OneUiPickerSheetContent(
                    title = "Thumbnail cache limit",
                    options = listOf(
                        PickerOption(250, "250 MB", "Minimal disk footprint"),
                        PickerOption(500, "500 MB", "Recommended default"),
                        PickerOption(750, "750 MB", "Balanced for medium libraries"),
                        PickerOption(1024, "1.0 GB", "Fast recall for large Drive collections"),
                        PickerOption(1536, "1.5 GB", "Extra headroom for heavy photo libraries"),
                        PickerOption(2048, "2.0 GB", "Maximum offline thumbnail cache")
                    ),
                    selected = nearestCachePreset(prefs.maxCacheSizeMb),
                    onSelect = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.updateMaxCacheSizeMb(it)
                        activePicker = null
                    }
                )

                SettingsPickerSheet.EXTERNAL_GALLERY -> {
                    val installedApps = remember {
                        appContainer.localFileOperationsManager.queryInstalledGalleryApps()
                    }
                    ExternalGalleryPickerSheetContent(
                        installedApps = installedApps,
                        selectedPackage = prefs.externalGalleryPackage,
                        onSelect = { pkg, label ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.setExternalGallery(pkg, label)
                            activePicker = null
                        }
                    )
                }
            }
        }
    }

    // --- Confirmation & About Dialogs ---
    if (showCloudProfileDialog) {
        val cloudSync = uiState.cloudProfileSyncStatus
        AlertDialog(
            onDismissRequest = { showCloudProfileDialog = false },
            shape = OneUiGroupShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Google account backup", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Your Gallery settings, linked Google Drive albums, custom order, and nested album groups (including DCIM folders matched across phones) are saved automatically in your Google Drive's private hidden app data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (cloudSync.lastSyncedEpochMs > 0L) {
                        Text(
                            text = "Last backup: ${FormatUtils.formatLastSync(cloudSync.lastSyncedEpochMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    cloudSync.lastError?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                showCloudProfileDialog = false
                                viewModel.backupCloudProfileNow()
                            }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = "Back up now",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Upload this phone's settings, Drive links & album groups",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                showCloudProfileDialog = false
                                viewModel.restoreCloudProfileNow()
                            }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = "Restore from Google",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Apply your saved cloud profile & match local DCIM albums",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCloudProfileDialog = false }) {
                    Text("Close", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            shape = OneUiGroupShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Sign out of Google Drive?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "Your on-device photos stay untouched. You can sign back in anytime to view your Google Drive folders.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        viewModel.signOutGoogleAccount()
                    }
                ) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showResetDefaultsDialog) {
        AlertDialog(
            onDismissRequest = { showResetDefaultsDialog = false },
            shape = OneUiGroupShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Reset settings?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "All display, viewer, playback, and cache limit settings will return to their defaults. Your albums, favorites, and Google account stay untouched.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDefaultsDialog = false
                        viewModel.resetSettingsToDefaults()
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDefaultsDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            shape = OneUiGroupShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = null,
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "Lumina Gallery",
                            modifier = Modifier.size(96.dp)
                        )
                    }
                    Text(
                        text = "Lumina Gallery",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Fast 120 Hz unified photo & video gallery for your phone and Google Drive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.hiddenAlbumCount > 0) {
                        Text(
                            text = "${uiState.hiddenAlbumCount} hidden album(s) · ${uiState.storageStats.cachedMediaCount} indexed Drive items",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

// ============================================================================================
// Samsung One UI Settings Building Blocks
// ============================================================================================

@Composable
private fun OneUiSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
        )
        Surface(
            shape = OneUiGroupShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }
}

@Composable
private fun OneUiDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    )
}

@Composable
private fun OneUiAccountRow(
    account: ServiceAccountState.Configured?,
    onSignInClick: () -> Unit,
    onCopyEmail: (String) -> Unit,
    onSignOutClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (account != null) onCopyEmail(account.clientEmail) else onSignInClick()
            }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (account != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (account != null) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = if (account != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Sync with Google Drive",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = account?.clientEmail ?: "Tap to sign in with Google",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = if (account != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (account != null) {
            Text(
                text = "Sign out",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSignOutClick)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        } else {
            Text(
                text = "Sign in",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .clickable(onClick = onSignInClick)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun OneUiToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun OneUiValueRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun OneUiActionRow(
    title: String,
    subtitle: String,
    subtitleAccent: Boolean = false,
    enabled: Boolean = true,
    showChevron: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = if (subtitleAccent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (trailingContent != null) {
            trailingContent()
        } else if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun OneUiStorageMeterBlock(
    usedBytes: Long,
    maxCacheMb: Int,
    cachedMediaCount: Int,
    cachedFolderCount: Int
) {
    val maxBytes = (maxCacheMb.toLong() * 1024L * 1024L).coerceAtLeast(1L)
    val usedRatio = (usedBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f)
    val animatedRatio by animateFloatAsState(
        targetValue = usedRatio,
        label = "oneUiCacheBar"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = FormatUtils.formatFileSize(usedBytes),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "used of ${formatMbLabel(maxCacheMb)} thumbnail cache",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "$cachedMediaCount items · $cachedFolderCount folders",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LinearProgressIndicator(
            progress = { animatedRatio },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round
        )
    }
}

private data class PickerOption<T>(
    val value: T,
    val label: String,
    val description: String
)

@Composable
private fun <T> OneUiPickerSheetContent(
    title: String,
    options: List<PickerOption<T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 22.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )

        options.forEachIndexed { idx, opt ->
            val isSelected = opt.value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(opt.value) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = opt.label,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = opt.description,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Spacer(Modifier.width(22.dp))
                }
            }
            if (idx < options.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
            }
        }
    }
}

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.AMOLED_BLACK -> "AMOLED black"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.SYSTEM -> "Match system"
}

private fun startTabLabel(tab: MainTab): String = when (tab) {
    MainTab.PICTURES -> "Pictures"
    MainTab.ALBUMS -> "Albums"
    MainTab.STORIES -> "Stories"
}

private fun gridColumnsLabel(columns: Int): String = "$columns columns"

private fun formatMbLabel(mb: Int): String =
    if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024f) else "$mb MB"

private fun nearestCachePreset(mb: Int): Int {
    val presets = listOf(250, 500, 750, 1024, 1536, 2048)
    return presets.minByOrNull { kotlin.math.abs(it - mb) } ?: 500
}

@Composable
private fun ExternalGalleryPickerSheetContent(
    installedApps: List<InstalledGalleryApp>,
    selectedPackage: String,
    onSelect: (packageName: String, label: String) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 20.dp)
    ) {
        Text(
            text = "External gallery app",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
        )
        Text(
            text = "Choose which app opens when tapping \"Open in…\" inside the viewer's ⋮ menu",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        ) {
            item(key = "ask_every_time") {
                val isSelected = selectedPackage.isBlank()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect("", "") }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ask every time",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Show the Android app chooser each time",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Spacer(Modifier.width(22.dp))
                    }
                }
                if (installedApps.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                }
            }

            itemsIndexed(
                items = installedApps,
                key = { _, app -> app.packageName }
            ) { idx, app ->
                val isSelected = app.packageName == selectedPackage
                val appIconBitmap = remember(app.packageName) {
                    runCatching {
                        context.packageManager.getApplicationIcon(app.packageName)
                            .toBitmap(width = 96, height = 96)
                            .asImageBitmap()
                    }.getOrNull()
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(app.packageName, app.label) }
                        .padding(horizontal = 24.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (appIconBitmap != null) {
                        Image(
                            bitmap = appIconBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.capabilitySummary,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Spacer(Modifier.width(22.dp))
                    }
                }
                if (idx < installedApps.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

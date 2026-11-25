package com.luminagallery.drive.ui.screens.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.data.remote.auth.GoogleAuthHelper
import com.luminagallery.drive.domain.model.AlbumGridItem
import com.luminagallery.drive.domain.model.AlbumSortMode
import com.luminagallery.drive.domain.model.AlbumsTabFilter
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.MainTab
import com.luminagallery.drive.domain.model.ServiceAccountState
import com.luminagallery.drive.domain.model.SpecialGalleryFilter
import com.luminagallery.drive.ui.components.AlbumSelectionActionBar
import com.luminagallery.drive.ui.components.BiometricLockHelper
import com.luminagallery.drive.ui.components.CollagePdfBottomSheet
import com.luminagallery.drive.ui.components.DraggableAlbumGrid
import com.luminagallery.drive.ui.components.ExifMetadataBottomSheet
import com.luminagallery.drive.ui.components.FastScrollThumb
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.HyperOsTrashConfirmSheet
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.MediaGridItem
import com.luminagallery.drive.ui.components.MoveCopyToAlbumSheet
import com.luminagallery.drive.ui.components.MultiSelectActionBar
import com.luminagallery.drive.ui.components.OneUiBottomNavBar
import com.luminagallery.drive.ui.components.OneUiMotion
import com.luminagallery.drive.ui.components.PinchZoomGridContainer
import com.luminagallery.drive.ui.components.SamsungMenuBottomSheet
import com.luminagallery.drive.ui.components.SecureWindowEffect
import com.luminagallery.drive.ui.components.SetupCredentialsCard
import com.luminagallery.drive.ui.components.TrashConfirmMode
import com.luminagallery.drive.ui.components.photoGridDragSelect
import com.luminagallery.drive.ui.screens.stories.StoriesTabContent

private val OneUiTabEasing = OneUiMotion.FluidEasing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    lastViewedItemId: String?,
    onConsumedLastViewedItemId: () -> Unit,
    onOpenFolder: (folderId: String, folderName: String) -> Unit,
    onOpenCollection: (collectionId: String, collectionName: String) -> Unit,
    onOpenPictureViewer: (itemId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSpecialFilter: (SpecialGalleryFilter) -> Unit,
    onOpenLocations: () -> Unit,
    onOpenPeople: () -> Unit = {},
    onOpenCleanOut: () -> Unit,
    onOpenCompare: (List<String>) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContainer = LocalAppContainer.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val picturesGridState = rememberLazyGridState()
    val albumsGridState = rememberLazyGridState()
    val permPrefs = remember(context) {
        context.applicationContext.getSharedPreferences("lumina_perm_prefs", Context.MODE_PRIVATE)
    }
    var hasRequestedPermsBefore by remember {
        mutableStateOf(permPrefs.getBoolean("asked_initial_media_perms", false))
    }

    fun launchSystemCamera() {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        runCatching { context.startActivity(intent) }
            .onFailure { viewModel.showMessage("No camera app found on this device") }
    }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        runCatching { context.startActivity(intent) }
    }

    var folderToUnpin by remember { mutableStateOf<DriveFolder?>(null) }
    var isSigningInWithGoogle by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSamsungMenuSheet by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showAlbumsFilterDialog by remember { mutableStateOf(false) }
    var showHideAlbumsSheet by remember { mutableStateOf(false) }
    var showCollagePdfSheet by remember { mutableStateOf(false) }
    var showTrashConfirmPictures by remember { mutableStateOf(false) }
    var showCreateCollectionDialog by remember { mutableStateOf(false) }
    var pendingGroupPair by remember { mutableStateOf<Pair<AlbumGridItem, AlbumGridItem>?>(null) }
    var showGroupSelectedDialog by remember { mutableStateOf(false) }
    var showMoveSelectedSheet by remember { mutableStateOf(false) }
    var showMoveCopyPicturesSheet by remember { mutableStateOf(false) }
    var renameCollectionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    BackHandler(enabled = uiState.isPictureSelectionMode || uiState.isAlbumSelectionMode) {
        if (uiState.isPictureSelectionMode) viewModel.clearPictureSelection()
        if (uiState.isAlbumSelectionMode) viewModel.clearAlbumSelection()
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permPrefs.edit().putBoolean("asked_initial_media_perms", true).apply()
        hasRequestedPermsBefore = true
        viewModel.onMediaPermissionResult()
    }

    LaunchedEffect(Unit) {
        // Only auto-prompt once on fresh install; never nag about ACCESS_MEDIA_LOCATION on every launch
        val alreadyAsked = permPrefs.getBoolean("asked_initial_media_perms", false)
        if (!alreadyAsked && !appContainer.localMediaRepository.hasMediaPermission()) {
            permPrefs.edit().putBoolean("asked_initial_media_perms", true).apply()
            hasRequestedPermsBefore = true
            mediaPermissionLauncher.launch(
                appContainer.localMediaRepository.requiredMediaPermissions()
            )
        }
    }

    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onTrashCompleted(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(uiState.pendingTrashIntentSender) {
        val sender = uiState.pendingTrashIntentSender ?: return@LaunchedEffect
        trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    LaunchedEffect(uiState.pendingShareUris) {
        val uris = uiState.pendingShareUris ?: return@LaunchedEffect
        FormatUtils.launchShareSheet(context, uris)
        viewModel.consumePendingShare()
    }

    LaunchedEffect(lastViewedItemId, uiState.timelineEntries) {
        val targetId = lastViewedItemId ?: return@LaunchedEffect
        val idx = uiState.timelineEntries.indexOfFirst {
            it is PicturesTimelineEntry.PhotoTile && it.item.id == targetId
        }
        if (idx >= 0) {
            val visible = picturesGridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (idx !in visible) {
                picturesGridState.scrollToItem(idx)
            }
            onConsumedLastViewedItemId()
        }
    }

    var activeGoogleAuthIsReadOnlyFallback by remember { mutableStateOf(false) }

    lateinit var launchGoogleAuthRequest: (readOnlyFallback: Boolean) -> Unit

    val googleAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        val wasReadOnlyFallback = activeGoogleAuthIsReadOnlyFallback
        if (GoogleAuthHelper.isExplicitUserCancel(
                error = null,
                resultCode = activityResult.resultCode,
                hasIntentData = activityResult.data != null
            )
        ) {
            isSigningInWithGoogle = false
            viewModel.onGoogleSignInError("Google sign-in was cancelled.")
            return@rememberLauncherForActivityResult
        }
        runCatching {
            val authResult = GoogleAuthHelper.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(activityResult.data)
            val token = authResult.accessToken
            if (!token.isNullOrBlank()) {
                isSigningInWithGoogle = false
                val granted = authResult.grantedScopes
                val hasAppData = !wasReadOnlyFallback && (
                    granted.isEmpty() || GoogleAuthHelper.hasGrantedAppDataScope(granted)
                )
                viewModel.onGoogleSignInSuccess(
                    accessToken = token,
                    fallbackEmail = authResult.toGoogleSignInAccount()?.email,
                    grantedAppDataScope = hasAppData
                )
            } else if (!wasReadOnlyFallback) {
                launchGoogleAuthRequest(true)
            } else {
                isSigningInWithGoogle = false
                viewModel.onGoogleSignInError("Google sign-in was cancelled.")
            }
        }.onFailure { err ->
            if (!wasReadOnlyFallback && GoogleAuthHelper.shouldFallbackToReadOnly(err, afterConsentScreen = true)) {
                launchGoogleAuthRequest(true)
            } else {
                isSigningInWithGoogle = false
                viewModel.onGoogleSignInError(GoogleAuthHelper.formatAuthError(err))
            }
        }
    }

    launchGoogleAuthRequest = { readOnlyFallback ->
        activeGoogleAuthIsReadOnlyFallback = readOnlyFallback
        isSigningInWithGoogle = true
        val request = if (readOnlyFallback) {
            appContainer.tokenManager.buildReadOnlyAuthorizationRequest()
        } else {
            appContainer.tokenManager.buildGoogleAuthorizationRequest()
        }
        GoogleAuthHelper.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null) {
                        googleAuthLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } else if (!readOnlyFallback) {
                        launchGoogleAuthRequest(true)
                    } else {
                        isSigningInWithGoogle = false
                        viewModel.onGoogleSignInError("Missing Google consent prompt intent.")
                    }
                } else {
                    isSigningInWithGoogle = false
                    val token = result.accessToken
                    if (!token.isNullOrBlank()) {
                        val granted = result.grantedScopes
                        val hasAppData = !readOnlyFallback && (
                            granted.isEmpty() || GoogleAuthHelper.hasGrantedAppDataScope(granted)
                        )
                        viewModel.onGoogleSignInSuccess(
                            accessToken = token,
                            fallbackEmail = result.toGoogleSignInAccount()?.email,
                            grantedAppDataScope = hasAppData
                        )
                    } else if (!readOnlyFallback) {
                        launchGoogleAuthRequest(true)
                    } else {
                        viewModel.onGoogleSignInError("Empty access token returned.")
                    }
                }
            }
            .addOnFailureListener { err ->
                if (!readOnlyFallback && GoogleAuthHelper.shouldFallbackToReadOnly(err, afterConsentScreen = false)) {
                    launchGoogleAuthRequest(true)
                } else {
                    isSigningInWithGoogle = false
                    viewModel.onGoogleSignInError(GoogleAuthHelper.formatAuthError(err))
                }
            }
    }

    fun startGoogleSignIn() {
        launchGoogleAuthRequest(false)
    }

    val credentialsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importCredentials(uri)
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearSnackbar()
    }

    val isDriveConnected = uiState.serviceAccountState is ServiceAccountState.Configured
    val isInAnySelectionMode = uiState.isPictureSelectionMode || uiState.isAlbumSelectionMode

    // Subtle Samsung Gallery ambient teal-to-black top wash when on Stories tab
    val storiesAmbientAlpha by animateFloatAsState(
        targetValue = if (uiState.selectedTab == MainTab.STORIES) 1f else 0f,
        animationSpec = tween(durationMillis = 360, easing = OneUiTabEasing),
        label = "storiesGlow"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            LuminaSnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !isInAnySelectionMode,
                enter = slideInVertically(tween(240, easing = OneUiTabEasing)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(200, easing = OneUiTabEasing)) { it } + fadeOut(tween(140))
            ) {
                OneUiBottomNavBar(
                    selectedTab = uiState.selectedTab,
                    menuOpen = showSamsungMenuSheet,
                    onSelectTab = viewModel::selectTab,
                    onMenuClick = { showSamsungMenuSheet = true }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            if (storiesAmbientAlpha > 0.01f) {
                val isDarkBg = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val washColor = if (isDarkBg) {
                    Color(0xFF0E2B2A).copy(alpha = 0.72f * storiesAmbientAlpha)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f * storiesAmbientAlpha)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(washColor, Color.Transparent)
                            )
                        )
                )
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // Clean Samsung One UI Minimalist Top Header (zero clutter subtitles!)
                SamsungTopHeaderBar(
                    selectedTab = uiState.selectedTab,
                    addMenuExpanded = showAddMenu,
                    overflowMenuExpanded = showOverflowMenu,
                    onDismissAddMenu = { showAddMenu = false },
                    onDismissOverflowMenu = { showOverflowMenu = false },
                    onAddClick = { showAddMenu = true },
                    onCreateCollectionClick = {
                        showAddMenu = false
                        showCreateCollectionDialog = true
                    },
                    onAddDriveFolderClick = {
                        showAddMenu = false
                        if (isDriveConnected) {
                            viewModel.openRootPicker()
                        } else {
                            viewModel.openDriveManagerSheet()
                        }
                    },
                    onFavoritesClick = {
                        onOpenSpecialFilter(SpecialGalleryFilter.FAVORITES)
                    },
                    onCameraClick = ::launchSystemCamera,
                    onSearchClick = onOpenSearch,
                    onOverflowClick = { showOverflowMenu = true },
                    onSelectActionClick = {
                        showOverflowMenu = false
                        when (uiState.selectedTab) {
                            MainTab.ALBUMS -> viewModel.enterAlbumSelectionMode()
                            MainTab.PICTURES -> viewModel.enterPictureSelectionMode()
                            MainTab.STORIES -> onOpenLocations()
                        }
                    },
                    onSortAlbumsClick = {
                        showOverflowMenu = false
                        showSortDialog = true
                    },
                    onFilterAlbumsClick = {
                        showOverflowMenu = false
                        showAlbumsFilterDialog = true
                    },
                    onGroupAlbumsClick = {
                        showOverflowMenu = false
                        viewModel.enterAlbumSelectionMode()
                    },
                    onHideAlbumsClick = {
                        showOverflowMenu = false
                        if (uiState.lockHiddenAlbums) {
                            BiometricLockHelper.authenticate(
                                context = context,
                                title = "Unlock hidden albums",
                                subtitle = "Verify your identity to view or manage hidden albums",
                                onSuccess = { showHideAlbumsSheet = true },
                                onFailure = { err -> viewModel.showMessage(err) }
                            )
                        } else {
                            showHideAlbumsSheet = true
                        }
                    },
                    onCleanOutQuickClick = {
                        showOverflowMenu = false
                        onOpenCleanOut()
                    }
                )

                // Smooth One UI Tab Transitions
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = uiState.selectedTab,
                        transitionSpec = {
                            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            (slideInHorizontally(
                                animationSpec = tween(300, easing = OneUiTabEasing)
                            ) { width -> direction * (width / 12) } +
                                scaleIn(
                                    animationSpec = tween(300, easing = OneUiTabEasing),
                                    initialScale = 0.972f
                                ) +
                                fadeIn(animationSpec = tween(220, easing = OneUiTabEasing)))
                                .togetherWith(
                                    slideOutHorizontally(
                                        animationSpec = tween(210, easing = OneUiTabEasing)
                                    ) { width -> -direction * (width / 18) } +
                                        scaleOut(
                                            animationSpec = tween(210, easing = OneUiTabEasing),
                                            targetScale = 0.98f
                                        ) +
                                        fadeOut(animationSpec = tween(150))
                                )
                        },
                        label = "homeTabTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { activeTab ->
                        when (activeTab) {
                            MainTab.PICTURES -> {
                                if (!uiState.hasLocalMediaPermission) {
                                    LocalPermissionCard(
                                        showSettingsFallback = hasRequestedPermsBefore,
                                        onGrantClick = {
                                            mediaPermissionLauncher.launch(
                                                appContainer.localMediaRepository.requiredMediaPermissions()
                                            )
                                        },
                                        onOpenSettingsClick = ::openAppSettings
                                    )
                                } else if (uiState.timelineEntries.isEmpty()) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        if (uiState.hasPartialMediaPermission) {
                                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                                CompactOneUiBanner(
                                                    icon = Icons.Rounded.PhotoLibrary,
                                                    title = "Limited photo access",
                                                    subtitle = "Tap to choose more photos or grant full access",
                                                    onClick = {
                                                        mediaPermissionLauncher.launch(
                                                            appContainer.localMediaRepository.requiredMediaPermissions()
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                        EmptyPicturesState()
                                    }
                                } else {
                                    PinchZoomGridContainer(
                                        currentColumns = uiState.gridColumns,
                                        onColumnsChange = viewModel::setGridColumns,
                                        minColumns = 1,
                                        maxColumns = 6,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        val hasPartialBanner = uiState.hasPartialMediaPermission
                                        val partialHeaderOffset = if (hasPartialBanner) 1 else 0
                                        val orderedPictureIds = remember(uiState.timelineEntries) {
                                            uiState.timelineEntries
                                                .filterIsInstance<PicturesTimelineEntry.PhotoTile>()
                                                .map { it.item.id }
                                        }
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(uiState.gridColumns),
                                            state = picturesGridState,
                                            contentPadding = PaddingValues(
                                                start = 8.dp,
                                                end = 8.dp,
                                                top = 2.dp,
                                                bottom = if (uiState.isPictureSelectionMode) 96.dp else 12.dp
                                            ),
                                            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                                            verticalArrangement = Arrangement.spacedBy(1.5.dp),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .photoGridDragSelect(
                                                    gridState = picturesGridState,
                                                    orderedItemIds = orderedPictureIds,
                                                    keyToItemId = { key ->
                                                        (key as? String)?.takeIf { it.startsWith("pic_") }?.removePrefix("pic_")
                                                    },
                                                    selectedIds = uiState.selectedPictureIds,
                                                    onSetItemSelected = viewModel::setPictureSelected,
                                                    onSetRangeSelected = viewModel::setPicturesSelectedRange
                                                )
                                        ) {
                                            if (hasPartialBanner) {
                                                item(
                                                    key = "partial_access_banner_pics",
                                                    span = { GridItemSpan(maxLineSpan) }
                                                ) {
                                                    Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                                                        CompactOneUiBanner(
                                                            icon = Icons.Rounded.PhotoLibrary,
                                                            title = "Limited photo access",
                                                            subtitle = "Tap to select more photos or allow all",
                                                            onClick = {
                                                                mediaPermissionLauncher.launch(
                                                                    appContainer.localMediaRepository.requiredMediaPermissions()
                                                                )
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            items(
                                                items = uiState.timelineEntries,
                                                key = { it.stableKey },
                                                span = { entry ->
                                                    when (entry) {
                                                        is PicturesTimelineEntry.DayHeader -> GridItemSpan(maxLineSpan)
                                                        is PicturesTimelineEntry.PhotoTile -> GridItemSpan(1)
                                                    }
                                                },
                                                contentType = { entry ->
                                                    when (entry) {
                                                        is PicturesTimelineEntry.DayHeader -> "day_header"
                                                        is PicturesTimelineEntry.PhotoTile -> "photo_tile"
                                                    }
                                                }
                                            ) { entry ->
                                                when (entry) {
                                                    is PicturesTimelineEntry.DayHeader -> {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(
                                                                    start = 6.dp,
                                                                    end = 6.dp,
                                                                    top = 18.dp,
                                                                    bottom = 8.dp
                                                                ),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(
                                                                text = entry.label,
                                                                style = MaterialTheme.typography.titleSmall.copy(
                                                                    fontWeight = FontWeight.SemiBold
                                                                ),
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                maxLines = 1
                                                            )
                                                            if (!entry.locationLabel.isNullOrBlank()) {
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                                    modifier = Modifier.padding(start = 12.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Outlined.LocationOn,
                                                                        contentDescription = null,
                                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                                                        modifier = Modifier.size(13.dp)
                                                                    )
                                                                    Text(
                                                                        text = entry.locationLabel,
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }

                                                    is PicturesTimelineEntry.PhotoTile -> {
                                                        val isSelected = entry.item.id in uiState.selectedPictureIds
                                                        MediaGridItem(
                                                            item = entry.item,
                                                            isUnmeteredWifi = uiState.highResThumbnailsAllowed,
                                                            isSelectionMode = uiState.isPictureSelectionMode,
                                                            isSelected = isSelected,
                                                            isFavorite = entry.item.id in uiState.favoriteItemIds,
                                                            showCloudBadge = uiState.showCloudBadges,
                                                            onClick = {
                                                                if (uiState.isPictureSelectionMode) {
                                                                    viewModel.togglePictureSelection(entry.item.id)
                                                                } else {
                                                                    onOpenPictureViewer(entry.item.id)
                                                                }
                                                            },
                                                            onLongClick = null,
                                                            modifier = Modifier.animateItem()
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        FastScrollThumb(
                                            gridState = picturesGridState,
                                            totalItemCount = uiState.timelineEntries.size + partialHeaderOffset,
                                            labelForIndex = { idx ->
                                                uiState.timelineEntries.getOrNull(idx - partialHeaderOffset)?.dateLabel
                                            },
                                            modifier = Modifier.align(Alignment.CenterEnd)
                                        )
                                    }
                                }
                            }

                            MainTab.ALBUMS -> {
                                PullToRefreshBox(
                                    isRefreshing = uiState.syncStatus.isSyncing,
                                    onRefresh = {
                                        viewModel.onMediaPermissionResult()
                                        if (isDriveConnected) viewModel.triggerSmartSync(false)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    if (!uiState.hasLocalMediaPermission && uiState.unifiedAlbumGridItems.isEmpty()) {
                                        LocalPermissionCard(
                                            showSettingsFallback = hasRequestedPermsBefore,
                                            onGrantClick = {
                                                mediaPermissionLauncher.launch(
                                                    appContainer.localMediaRepository.requiredMediaPermissions()
                                                )
                                            },
                                            onOpenSettingsClick = ::openAppSettings
                                        )
                                    } else {
                                        PinchZoomGridContainer(
                                            currentColumns = uiState.albumGridColumns,
                                            onColumnsChange = viewModel::setAlbumGridColumns,
                                            minColumns = 2,
                                            maxColumns = 5,
                                            columnLabel = { cols -> "$cols albums / row" },
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            DraggableAlbumGrid(
                                                items = uiState.unifiedAlbumGridItems,
                                                isUnmeteredWifi = uiState.highResThumbnailsAllowed,
                                                selectedIds = uiState.selectedAlbumIds,
                                                columns = uiState.albumGridColumns,
                                                forceSelectionMode = uiState.forceAlbumSelectionMode,
                                                dragReorderEnabled = uiState.albumsFilter == AlbumsTabFilter.ALL,
                                                showCloudBadges = uiState.showCloudBadges,
                                                gridState = albumsGridState,
                                                contentPadding = PaddingValues(
                                                    start = 16.dp,
                                                    end = 16.dp,
                                                    top = 6.dp,
                                                    bottom = if (uiState.isAlbumSelectionMode) 96.dp else 18.dp
                                                ),
                                                headerContent = when {
                                                    !uiState.hasLocalMediaPermission -> {
                                                        {
                                                            CompactOneUiBanner(
                                                                icon = Icons.Rounded.PhotoLibrary,
                                                                title = "Show your phone's photos & albums",
                                                                subtitle = "Tap to allow Camera, Screenshots, Downloads & RAW",
                                                                onClick = {
                                                                    mediaPermissionLauncher.launch(
                                                                        appContainer.localMediaRepository.requiredMediaPermissions()
                                                                    )
                                                                }
                                                            )
                                                        }
                                                    }
                                                    uiState.hasPartialMediaPermission -> {
                                                        {
                                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                                CompactOneUiBanner(
                                                                    icon = Icons.Rounded.PhotoLibrary,
                                                                    title = "Limited photo access",
                                                                    subtitle = "Tap to select more photos or allow all",
                                                                    onClick = {
                                                                        mediaPermissionLauncher.launch(
                                                                            appContainer.localMediaRepository.requiredMediaPermissions()
                                                                        )
                                                                    }
                                                                )
                                                                if (uiState.albumsFilter != AlbumsTabFilter.ALL || uiState.albumSortMode != AlbumSortMode.CUSTOM) {
                                                                    Row(
                                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                        verticalAlignment = Alignment.CenterVertically
                                                                    ) {
                                                                        if (uiState.albumsFilter != AlbumsTabFilter.ALL) {
                                                                            AlbumsActiveCapsule(
                                                                                icon = Icons.Outlined.FilterAlt,
                                                                                label = uiState.albumsFilter.label,
                                                                                onClick = { showAlbumsFilterDialog = true },
                                                                                onClear = { viewModel.setAlbumsFilter(AlbumsTabFilter.ALL) }
                                                                            )
                                                                        }
                                                                        if (uiState.albumSortMode != AlbumSortMode.CUSTOM) {
                                                                            AlbumsActiveCapsule(
                                                                                icon = Icons.AutoMirrored.Rounded.Sort,
                                                                                label = uiState.albumSortMode.label,
                                                                                onClick = { showSortDialog = true },
                                                                                onClear = { viewModel.setAlbumSortMode(AlbumSortMode.CUSTOM) }
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    uiState.albumsFilter != AlbumsTabFilter.ALL || uiState.albumSortMode != AlbumSortMode.CUSTOM -> {
                                                        {
                                                            Row(
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.padding(bottom = 4.dp)
                                                            ) {
                                                                if (uiState.albumsFilter != AlbumsTabFilter.ALL) {
                                                                    AlbumsActiveCapsule(
                                                                        icon = Icons.Outlined.FilterAlt,
                                                                        label = uiState.albumsFilter.label,
                                                                        onClick = { showAlbumsFilterDialog = true },
                                                                        onClear = { viewModel.setAlbumsFilter(AlbumsTabFilter.ALL) }
                                                                    )
                                                                }
                                                                if (uiState.albumSortMode != AlbumSortMode.CUSTOM) {
                                                                    AlbumsActiveCapsule(
                                                                        icon = Icons.AutoMirrored.Rounded.Sort,
                                                                        label = uiState.albumSortMode.label,
                                                                        onClick = { showSortDialog = true },
                                                                        onClear = { viewModel.setAlbumSortMode(AlbumSortMode.CUSTOM) }
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                    else -> null
                                                },
                                                onItemClick = { clicked ->
                                                    when (clicked) {
                                                        is AlbumGridItem.AlbumEntry ->
                                                            onOpenFolder(clicked.folder.id, clicked.folder.name)
                                                        is AlbumGridItem.CollectionEntry ->
                                                            onOpenCollection(clicked.id, clicked.name)
                                                    }
                                                },
                                                onToggleSelect = viewModel::toggleAlbumSelection,
                                                onReorderCommitted = viewModel::saveRootAlbumOrder,
                                                onDropToGroup = { dragged, target ->
                                                    if (target is AlbumGridItem.CollectionEntry) {
                                                        viewModel.moveItemIntoCollection(dragged, target.id)
                                                    } else {
                                                        pendingGroupPair = dragged to target
                                                    }
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            }

                            MainTab.STORIES -> {
                                StoriesTabContent(
                                    stories = uiState.stories,
                                    isUnmeteredWifi = uiState.networkState.isUnmeteredWifi,
                                    onToggleFavoriteStory = viewModel::toggleFavoriteStory,
                                    onOpenLocations = onOpenLocations,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            // Samsung Album Selection Action Bar (Group / Move / Rename / Ungroup / Remove)
            val selectedAlbumItems = remember(uiState.unifiedAlbumGridItems, uiState.selectedAlbumIds) {
                uiState.unifiedAlbumGridItems.filter { it.itemId in uiState.selectedAlbumIds }
            }
            val singleSelectedCollection = selectedAlbumItems.singleOrNull() as? AlbumGridItem.CollectionEntry
            val hasSelectedDrive = selectedAlbumItems.any {
                it is AlbumGridItem.AlbumEntry && !it.folder.isLocal
            }
            val availableMoveTargets = remember(uiState.allCollections, uiState.selectedAlbumIds) {
                val blocked = buildSet {
                    addAll(uiState.selectedAlbumIds)
                    val childrenByParent = uiState.allCollections.groupBy { it.parentCollectionId }
                    val queue = ArrayDeque(uiState.selectedAlbumIds)
                    while (queue.isNotEmpty()) {
                        val curr = queue.removeFirst()
                        for (child in childrenByParent[curr].orEmpty()) {
                            if (add(child.id)) queue.addLast(child.id)
                        }
                    }
                }
                uiState.allCollections.filter { it.id !in blocked }
            }

            AlbumSelectionActionBar(
                visible = uiState.isAlbumSelectionMode,
                selectedCount = uiState.selectedAlbumIds.size,
                canGroup = uiState.selectedAlbumIds.size >= 2,
                canMoveOut = false,
                canMoveIntoExisting = uiState.selectedAlbumIds.isNotEmpty() && availableMoveTargets.isNotEmpty(),
                canRename = singleSelectedCollection != null,
                canUngroup = singleSelectedCollection != null,
                canRemoveDrive = hasSelectedDrive,
                onGroup = { showGroupSelectedDialog = true },
                onMoveOut = {},
                onMoveIntoExisting = { showMoveSelectedSheet = true },
                onRename = {
                    singleSelectedCollection?.let {
                        renameCollectionTarget = it.id to it.name
                    }
                },
                onUngroup = {
                    singleSelectedCollection?.let {
                        viewModel.ungroupCollection(it.id)
                        viewModel.clearAlbumSelection()
                    }
                },
                onRemoveDrive = viewModel::unpinSelectedDriveAlbums,
                onClear = viewModel::clearAlbumSelection,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            val selectedPictures = remember(uiState.timelineEntries, uiState.selectedPictureIds) {
                uiState.timelineEntries
                    .filterIsInstance<PicturesTimelineEntry.PhotoTile>()
                    .map { it.item }
                    .filter { it.id in uiState.selectedPictureIds }
            }
            val comparablePictureIds = remember(selectedPictures) {
                selectedPictures.filter { !it.isVideo }.map { it.id }
            }

            // Multi-select bar for Pictures tab
            MultiSelectActionBar(
                visible = uiState.isPictureSelectionMode,
                selectedCount = uiState.selectedPictureIds.size,
                isExporting = uiState.isExporting,
                exportProgressText = null,
                onShare = viewModel::shareSelectedPictures,
                onSaveToDevice = null,
                onDeleteLocal = { if (selectedPictures.isNotEmpty()) showTrashConfirmPictures = true },
                onCompare = if (comparablePictureIds.size in 2..4) {
                    {
                        viewModel.clearPictureSelection()
                        onOpenCompare(comparablePictureIds)
                    }
                } else null,
                onMoveCopyLocal = if (selectedPictures.isNotEmpty()) {
                    { showMoveCopyPicturesSheet = true }
                } else null,
                onCreateCollagePdf = if (comparablePictureIds.isNotEmpty()) {
                    { showCollagePdfSheet = true }
                } else null,
                onInfo = viewModel::openSelectedPictureInfo,
                onClearSelection = viewModel::clearPictureSelection,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showMoveCopyPicturesSheet) {
        val itemsToMove = remember(uiState.timelineEntries, uiState.selectedPictureIds) {
            uiState.timelineEntries
                .filterIsInstance<PicturesTimelineEntry.PhotoTile>()
                .map { it.item }
                .filter { it.id in uiState.selectedPictureIds }
        }
        if (itemsToMove.isNotEmpty()) {
            MoveCopyToAlbumSheet(
                items = itemsToMove,
                onDismiss = { showMoveCopyPicturesSheet = false },
                onCompleted = { msg ->
                    showMoveCopyPicturesSheet = false
                    viewModel.clearPictureSelection()
                    viewModel.showMessage(msg)
                }
            )
        }
    }

    // Samsung One UI ≡ Menu Bottom Sheet
    if (showSamsungMenuSheet) {
        SamsungMenuBottomSheet(
            onDismiss = { showSamsungMenuSheet = false },
            onVideosClick = { onOpenSpecialFilter(SpecialGalleryFilter.VIDEOS) },
            onFavoritesClick = { onOpenSpecialFilter(SpecialGalleryFilter.FAVORITES) },
            onRecentClick = { onOpenSpecialFilter(SpecialGalleryFilter.RECENT) },
            onLocationsClick = onOpenLocations,
            onPeopleClick = onOpenPeople,
            onGoogleDriveClick = viewModel::openDriveManagerSheet,
            onCleanOutClick = onOpenCleanOut,
            onTrashClick = { onOpenSpecialFilter(SpecialGalleryFilter.TRASH) },
            onSettingsClick = onOpenSettings,
            onCameraClick = ::launchSystemCamera
        )
    }

    // Google Drive Manager ModalBottomSheet (launched from ≡ Menu -> Google Drive)
    if (uiState.isDriveSheetOpen) {
        GoogleDriveManagerSheet(
            accountState = uiState.serviceAccountState,
            pinnedRoots = uiState.pinnedRoots,
            syncStatus = uiState.syncStatus,
            setupError = uiState.setupError,
            isSigningIn = isSigningInWithGoogle,
            onDismiss = viewModel::closeDriveManagerSheet,
            onGoogleSignInClick = ::startGoogleSignIn,
            onImportJsonClick = {
                credentialsPickerLauncher.launch(arrayOf("application/json", "*/*"))
            },
            onSyncNow = { viewModel.triggerSmartSync(false) },
            onBrowseDrive = { viewModel.openRootPicker() },
            onPasteUrl = { viewModel.openRootPicker(RootPickerTab.PASTE_LINK) },
            onOpenFolder = { folder ->
                viewModel.closeDriveManagerSheet()
                onOpenFolder(folder.id, folder.name)
            },
            onUnpinFolder = { folder -> folderToUnpin = folder }
        )
    }

    // Samsung Sort Albums Dialog
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Sort albums") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AlbumSortMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAlbumSortMode(mode)
                                    showSortDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.albumSortMode == mode,
                                onClick = {
                                    viewModel.setAlbumSortMode(mode)
                                    showSortDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortDialog = false }) { Text("Done") }
            }
        )
    }

    // Samsung Filter Albums Dialog
    if (showAlbumsFilterDialog) {
        AlertDialog(
            onDismissRequest = { showAlbumsFilterDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Filter albums") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AlbumsTabFilter.entries.forEach { filter ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAlbumsFilter(filter)
                                    showAlbumsFilterDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.albumsFilter == filter,
                                onClick = {
                                    viewModel.setAlbumsFilter(filter)
                                    showAlbumsFilterDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = filter.label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAlbumsFilterDialog = false }) { Text("Done") }
            }
        )
    }

    if (showTrashConfirmPictures) {
        val itemsToTrash = remember(uiState.localPictures, uiState.selectedPictureIds) {
            uiState.localPictures.filter { it.id in uiState.selectedPictureIds }
        }
        if (itemsToTrash.isNotEmpty()) {
            HyperOsTrashConfirmSheet(
                items = itemsToTrash,
                mode = TrashConfirmMode.MOVE_TO_TRASH,
                canSkipSystemPrompt = appContainer.localMediaRepository.canManageMediaDirectly(),
                supportsSkipPromptSetting = appContainer.localMediaRepository.supportsManageMediaPermission(),
                onOpenManageMediaSettings = {
                    appContainer.localMediaRepository.buildManageMediaSettingsIntent()?.let { intent ->
                        runCatching { context.startActivity(intent) }
                    }
                },
                onConfirm = {
                    showTrashConfirmPictures = false
                    viewModel.requestTrashSelectedPictures()
                },
                onDismiss = { showTrashConfirmPictures = false }
            )
        }
    }

    if (showCollagePdfSheet) {
        val photosForCreation = remember(uiState.timelineEntries, uiState.selectedPictureIds) {
            uiState.timelineEntries
                .filterIsInstance<PicturesTimelineEntry.PhotoTile>()
                .map { it.item }
                .filter { it.id in uiState.selectedPictureIds && !it.isVideo }
        }
        if (photosForCreation.isNotEmpty()) {
            CollagePdfBottomSheet(
                items = photosForCreation,
                onDismiss = { showCollagePdfSheet = false },
                onCompleted = { msg ->
                    showCollagePdfSheet = false
                    viewModel.clearPictureSelection()
                    viewModel.showMessage(msg)
                }
            )
        }
    }

    // Samsung Hide Albums Bottom Sheet (Biometric Private Vault when lockHiddenAlbums is enabled)
    if (showHideAlbumsSheet) {
        SecureWindowEffect(enabled = uiState.lockHiddenAlbums)
        HideAlbumsBottomSheet(
            allAlbums = uiState.allAvailableAlbums,
            hiddenAlbumIds = uiState.hiddenAlbumIds,
            isBiometricProtected = uiState.lockHiddenAlbums,
            onToggleHidden = viewModel::toggleHiddenAlbum,
            onOpenPrivateAlbum = { folder ->
                showHideAlbumsSheet = false
                onOpenFolder(folder.id, folder.name)
            },
            onDismiss = { showHideAlbumsSheet = false }
        )
    }

    // EXIF Bottom Sheet for Pictures multi-select Info
    uiState.infoItem?.let { item ->
        ExifMetadataBottomSheet(
            item = item,
            driveFolderPath = uiState.infoPath,
            onDismiss = viewModel::closeInfoSheet,
            onCopiedMessage = viewModel::showMessage
        )
    }

    // Drive Folder Picker Bottom Sheet
    if (uiState.rootPicker.isOpen) {
        RootFolderPickerBottomSheet(
            state = uiState.rootPicker,
            onDismiss = viewModel::closeRootPicker,
            onTabSelected = viewModel::selectRootPickerTab,
            onUrlChanged = viewModel::onUrlOrIdInputChanged,
            onCustomNameChanged = viewModel::onCustomNameInputChanged,
            onPinByUrl = viewModel::pinFolderByUrlOrId,
            onRefreshShared = viewModel::loadSharedFolders,
            onTogglePinFolder = viewModel::togglePinSharedFolder,
            onRefreshComputers = viewModel::refreshComputerFolders,
            onOpenComputerFolder = viewModel::openComputerFolder,
            onGoUpComputerFolder = viewModel::goUpFromComputerFolder
        )
    }

    // Confirm unpin dialog
    folderToUnpin?.let { target ->
        AlertDialog(
            onDismissRequest = { folderToUnpin = null },
            title = { Text("Remove Drive album?") },
            text = {
                Text(
                    "Unpin \"${target.name}\" from your gallery? " +
                        "Your files in Google Drive stay completely untouched."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unpinRootFolder(target)
                        folderToUnpin = null
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { folderToUnpin = null }) { Text("Cancel") }
            }
        )
    }

    // Create Collection from drag-on-top pair
    pendingGroupPair?.let { (dragged, target) ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingGroupPair = null },
            title = { Text("Create collection") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Collection name") },
                    placeholder = { Text("e.g. Travel, Work, Family") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createCollectionFromPair(dragged, target, name)
                        pendingGroupPair = null
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { pendingGroupPair = null }) { Text("Cancel") }
            }
        )
    }

    // Create empty collection dialog from '+' menu
    if (showCreateCollectionDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateCollectionDialog = false },
            title = { Text("New collection") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Collection name") },
                    placeholder = { Text("e.g. Trips, Photography") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createEmptyCollection(name)
                        showCreateCollectionDialog = false
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateCollectionDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename Collection dialog
    renameCollectionTarget?.let { (colId, currentName) ->
        var edited by remember(colId) { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { renameCollectionTarget = null },
            title = { Text("Rename collection") },
            text = {
                OutlinedTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = edited.isNotBlank(),
                    onClick = {
                        viewModel.renameCollection(colId, edited)
                        renameCollectionTarget = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameCollectionTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Group multiple selected albums into a new Collection
    if (showGroupSelectedDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGroupSelectedDialog = false },
            title = { Text("Create collection") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Collection name") },
                    placeholder = { Text("e.g. WhatsApp, Wintersport") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.groupSelectedAlbumsIntoCollection(name)
                        showGroupSelectedDialog = false
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showGroupSelectedDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Move selected albums into an existing collection
    if (showMoveSelectedSheet) {
        val destinations = remember(uiState.allCollections, uiState.selectedAlbumIds) {
            val blocked = buildSet {
                addAll(uiState.selectedAlbumIds)
                val childrenByParent = uiState.allCollections.groupBy { it.parentCollectionId }
                val queue = ArrayDeque(uiState.selectedAlbumIds)
                while (queue.isNotEmpty()) {
                    val curr = queue.removeFirst()
                    for (child in childrenByParent[curr].orEmpty()) {
                        if (add(child.id)) queue.addLast(child.id)
                    }
                }
            }
            uiState.allCollections.filter { it.id !in blocked }
        }
        ModalBottomSheet(
            onDismissRequest = { showMoveSelectedSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
            ) {
                Text(
                    text = "Move to collection",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(destinations, key = { it.id }) { dest ->
                        ListItem(
                            headlineContent = { Text(dest.name) },
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                viewModel.moveSelectedAlbumsIntoCollection(dest.id)
                                showMoveSelectedSheet = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SamsungTopHeaderBar(
    selectedTab: MainTab,
    addMenuExpanded: Boolean,
    overflowMenuExpanded: Boolean,
    onDismissAddMenu: () -> Unit,
    onDismissOverflowMenu: () -> Unit,
    onAddClick: () -> Unit,
    onCreateCollectionClick: () -> Unit,
    onAddDriveFolderClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onCameraClick: () -> Unit,
    onSearchClick: () -> Unit,
    onOverflowClick: () -> Unit,
    onSelectActionClick: () -> Unit,
    onSortAlbumsClick: () -> Unit,
    onFilterAlbumsClick: () -> Unit,
    onGroupAlbumsClick: () -> Unit,
    onHideAlbumsClick: () -> Unit,
    onCleanOutQuickClick: () -> Unit
) {
    val title = when (selectedTab) {
        MainTab.PICTURES -> "Pictures"
        MainTab.ALBUMS -> "Albums"
        MainTab.STORIES -> "Stories"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 6.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectedTab == MainTab.ALBUMS) {
                Box {
                    IconButton(onClick = onAddClick) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Add",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    DropdownMenu(
                        expanded = addMenuExpanded,
                        onDismissRequest = onDismissAddMenu,
                        shape = RoundedCornerShape(20.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        DropdownMenuItem(
                            text = { Text("Create collection") },
                            leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                            onClick = onCreateCollectionClick
                        )
                        DropdownMenuItem(
                            text = { Text("Add Google Drive folder") },
                            leadingIcon = {
                                com.luminagallery.drive.ui.components.GoogleDriveGlyph(
                                    size = 20.dp,
                                    primaryColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = onAddDriveFolderClick
                        )
                    }
                }
            }

            if (selectedTab == MainTab.STORIES) {
                IconButton(onClick = onFavoritesClick) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favorites",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            } else {
                IconButton(onClick = onCameraClick) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = "Open camera",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }

            Box {
                IconButton(onClick = onOverflowClick) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded = overflowMenuExpanded,
                    onDismissRequest = onDismissOverflowMenu,
                    shape = RoundedCornerShape(22.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    when (selectedTab) {
                        MainTab.ALBUMS -> {
                            DropdownMenuItem(
                                text = { Text("Select") },
                                onClick = onSelectActionClick
                            )
                            DropdownMenuItem(
                                text = { Text("Filter") },
                                onClick = onFilterAlbumsClick
                            )
                            DropdownMenuItem(
                                text = { Text("Group albums") },
                                onClick = onGroupAlbumsClick
                            )
                            DropdownMenuItem(
                                text = { Text("Sort") },
                                onClick = onSortAlbumsClick
                            )
                            DropdownMenuItem(
                                text = { Text("Hide albums") },
                                onClick = onHideAlbumsClick
                            )
                        }

                        MainTab.PICTURES -> {
                            DropdownMenuItem(
                                text = { Text("Select") },
                                onClick = onSelectActionClick
                            )
                            DropdownMenuItem(
                                text = { Text("Clean out") },
                                onClick = onCleanOutQuickClick
                            )
                        }

                        MainTab.STORIES -> {
                            DropdownMenuItem(
                                text = { Text("Locations") },
                                onClick = onSelectActionClick
                            )
                            DropdownMenuItem(
                                text = { Text("Clean out") },
                                onClick = onCleanOutQuickClick
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HideAlbumsBottomSheet(
    allAlbums: List<DriveFolder>,
    hiddenAlbumIds: Set<String>,
    isBiometricProtected: Boolean,
    onToggleHidden: (String) -> Unit,
    onOpenPrivateAlbum: (DriveFolder) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
                Text(
                    text = if (isBiometricProtected) "Hidden albums (Locked)" else "Hide albums",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = if (isBiometricProtected) {
                        "Tap a hidden album to browse privately, or toggle switches to hide/unhide"
                    } else {
                        "Turn on to hide albums from the Albums grid · lock in Privacy settings"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                items(allAlbums, key = { it.id }) { album ->
                    val isHidden = album.id in hiddenAlbumIds
                    ListItem(
                        headlineContent = {
                            Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                text = buildString {
                                    append("${album.mediaCount} items · ")
                                    append(if (album.isLocal) "On device" else "Google Drive")
                                    if (isHidden) append(" · Tap to browse privately")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = isHidden,
                                onCheckedChange = { onToggleHidden(album.id) }
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            if (isHidden) {
                                onOpenPrivateAlbum(album)
                            } else {
                                onToggleHidden(album.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoogleDriveManagerSheet(
    accountState: ServiceAccountState,
    pinnedRoots: List<DriveFolder>,
    syncStatus: com.luminagallery.drive.domain.model.SyncStatus,
    setupError: String?,
    isSigningIn: Boolean,
    onDismiss: () -> Unit,
    onGoogleSignInClick: () -> Unit,
    onImportJsonClick: () -> Unit,
    onSyncNow: () -> Unit,
    onBrowseDrive: () -> Unit,
    onPasteUrl: () -> Unit,
    onOpenFolder: (DriveFolder) -> Unit,
    onUnpinFolder: (DriveFolder) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
            when (accountState) {
                ServiceAccountState.NotConfigured -> {
                    SetupCredentialsCard(
                        errorMessage = setupError,
                        isSigningIn = isSigningIn,
                        onGoogleSignInClick = onGoogleSignInClick,
                        onSelectCredentialsClick = onImportJsonClick
                    )
                }

                is ServiceAccountState.Configured -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                            Column {
                                Text(
                                    text = "Google Drive",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "${accountState.clientEmail} · Synced ${FormatUtils.formatLastSync(syncStatus.lastSyncEpochMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = onSyncNow, enabled = !syncStatus.isSyncing) {
                            if (syncStatus.isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Sync now")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onBrowseDrive,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Browse Drive")
                        }
                        FilledTonalButton(
                            onClick = onPasteUrl,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Paste URL")
                        }
                    }

                    if (pinnedRoots.isNotEmpty()) {
                        Text(
                            text = "Linked albums (${pinnedRoots.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(pinnedRoots, key = { it.id }) { folder ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenFolder(folder) }
                                ) {
                                    ListItem(
                                        headlineContent = {
                                            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        },
                                        supportingContent = {
                                            Text("${folder.mediaCount} items")
                                        },
                                        leadingContent = {
                                            Icon(
                                                imageVector = Icons.Rounded.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        trailingContent = {
                                            IconButton(onClick = { onUnpinFolder(folder) }) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DeleteOutline,
                                                    contentDescription = "Unpin",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
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

@Composable
private fun AlbumsActiveCapsule(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(50)
            )
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Box(
                modifier = Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

@Composable
private fun CompactOneUiBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LocalPermissionCard(
    showSettingsFallback: Boolean = false,
    onGrantClick: () -> Unit,
    onOpenSettingsClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp)
                )
                Text(
                    text = "Access your phone gallery",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Allow access to Photos & Videos so your Camera, Screenshots, Downloads, and on-device albums appear right alongside your Google Drive folders.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onGrantClick,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Allow Photos & Videos")
                }
                if (showSettingsFallback) {
                    OutlinedButton(
                        onClick = onOpenSettingsClick,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Text("Open App Settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPicturesState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = "No photos or videos on device",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootFolderPickerBottomSheet(
    state: RootPickerUiState,
    onDismiss: () -> Unit,
    onTabSelected: (RootPickerTab) -> Unit,
    onUrlChanged: (String) -> Unit,
    onCustomNameChanged: (String) -> Unit,
    onPinByUrl: () -> Unit,
    onRefreshShared: () -> Unit,
    onTogglePinFolder: (DriveFolder) -> Unit,
    onRefreshComputers: () -> Unit,
    onOpenComputerFolder: (DriveFolder) -> Unit,
    onGoUpComputerFolder: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val showComputers = state.selectedTab == RootPickerTab.COMPUTERS
    val computerBrowser = state.computerBrowser
    val currentComputerFolder = computerBrowser?.path?.lastOrNull()
    val errorMessage = if (showComputers) computerBrowser?.errorMessage else state.errorMessage

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Add Google Drive Album",
                style = MaterialTheme.typography.titleLarge
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RootPickerTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = state.selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        shape = SegmentedButtonDefaults.itemShape(index, RootPickerTab.entries.size),
                        icon = {}
                    ) {
                        Text(tab.label, textAlign = TextAlign.Center)
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (state.selectedTab == RootPickerTab.PASTE_LINK) {
                OutlinedTextField(
                    value = state.urlOrIdInput,
                    onValueChange = onUrlChanged,
                    label = { Text("Google Drive Folder URL or ID") },
                    placeholder = { Text("https://drive.google.com/drive/folders/...") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.customNameInput,
                    onValueChange = onCustomNameChanged,
                    label = { Text("Album name (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = onPinByUrl,
                    enabled = state.urlOrIdInput.isNotBlank() && !state.isPinningByUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (state.isPinningByUrl) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Album")
                    }
                }
            } else {
                val showComputerRoots = showComputers && currentComputerFolder == null
                val folders = if (showComputers) computerBrowser?.folders.orEmpty() else state.sharedFolders
                val isLoading = if (showComputers) computerBrowser?.isLoading != false else state.isLoadingSharedFolders
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showComputers && currentComputerFolder != null) {
                        IconButton(onClick = onGoUpComputerFolder) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Parent folder")
                        }
                    }
                    Text(
                        text = when {
                            showComputerRoots -> "Open a computer to browse its synced folders"
                            showComputers -> currentComputerFolder?.name.orEmpty()
                            else -> "Tap any folder to pin it as a gallery album"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = if (showComputers) onRefreshComputers else onRefreshShared,
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (showComputers && folders.isEmpty() && errorMessage == null) {
                    Text(
                        text = if (showComputerRoots) {
                            "No synced computers found. You can paste a folder link from Google Drive’s Computers section."
                        } else {
                            "No subfolders"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    key(state.selectedTab, currentComputerFolder?.id) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(folders, key = { it.id }) { folder ->
                                RootPickerFolderRow(
                                    folder = folder,
                                    isComputerRoot = showComputerRoots,
                                    onOpen = if (showComputers) onOpenComputerFolder else null,
                                    onTogglePin = onTogglePinFolder
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootPickerFolderRow(
    folder: DriveFolder,
    isComputerRoot: Boolean,
    onOpen: ((DriveFolder) -> Unit)?,
    onTogglePin: (DriveFolder) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable { (onOpen ?: onTogglePin)(folder) }
    ) {
        ListItem(
            headlineContent = {
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = {
                Icon(
                    imageVector = if (isComputerRoot) Icons.Rounded.Computer else Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingContent = {
                if (isComputerRoot) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                } else {
                    val pinIcon = if (folder.isPinned) Icons.Rounded.CheckCircle else Icons.Rounded.Add
                    val pinTint = if (folder.isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    if (onOpen != null) {
                        val pinAction = if (folder.isPinned) "Unpin ${folder.name}" else "Pin ${folder.name}"
                        IconButton(onClick = { onTogglePin(folder) }) {
                            Icon(pinIcon, contentDescription = pinAction, tint = pinTint)
                        }
                    } else {
                        Icon(pinIcon, contentDescription = if (folder.isPinned) "Pinned" else "Pin", tint = pinTint)
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

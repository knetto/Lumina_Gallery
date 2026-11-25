package com.luminagallery.drive.ui.navigation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.domain.model.AlbumMediaFilter
import com.luminagallery.drive.domain.model.AlbumMediaSort
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.domain.model.SpecialGalleryFilter
import com.luminagallery.drive.ui.components.AlbumTransitionOriginTracker
import com.luminagallery.drive.ui.components.BiometricLockHelper
import com.luminagallery.drive.ui.components.HyperOsRectMorphFrame
import com.luminagallery.drive.ui.components.HyperOsTransitionFrame
import com.luminagallery.drive.ui.components.LocalAlbumTransitionOrigin
import com.luminagallery.drive.ui.components.OneUiMotion
import com.luminagallery.drive.ui.components.findActivity
import com.luminagallery.drive.ui.components.prefetchAlbumThumbnails
import com.luminagallery.drive.ui.screens.cleanout.CleanOutScreen
import com.luminagallery.drive.ui.screens.cleanout.CleanOutViewModel
import com.luminagallery.drive.ui.screens.collection.CollectionScreen
import com.luminagallery.drive.ui.screens.collection.CollectionViewModel
import com.luminagallery.drive.ui.screens.compare.CompareScreen
import com.luminagallery.drive.ui.screens.editor.PhotoEditorScreen
import com.luminagallery.drive.ui.screens.folder.FolderScreen
import com.luminagallery.drive.ui.screens.folder.FolderViewModel
import com.luminagallery.drive.ui.screens.home.HomeScreen
import com.luminagallery.drive.ui.screens.home.HomeViewModel
import com.luminagallery.drive.ui.screens.people.PeopleScreen
import com.luminagallery.drive.ui.screens.people.PeopleViewModel
import com.luminagallery.drive.ui.screens.people.PersonDetailScreen
import com.luminagallery.drive.ui.screens.people.PersonDetailViewModel
import com.luminagallery.drive.ui.screens.search.SearchScreen
import com.luminagallery.drive.ui.screens.search.SearchViewModel
import com.luminagallery.drive.ui.screens.settings.SettingsScreen
import com.luminagallery.drive.ui.screens.settings.SettingsViewModel
import com.luminagallery.drive.ui.screens.special.LocationsScreen
import com.luminagallery.drive.ui.screens.special.SpecialCollectionScreen
import com.luminagallery.drive.ui.screens.viewer.ExternalMediaViewerOverlay
import com.luminagallery.drive.ui.screens.viewer.ViewerLaunchContext
import com.luminagallery.drive.ui.screens.viewer.ViewerScreen
import com.luminagallery.drive.ui.screens.viewer.ViewerViewModel
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data class FolderRoute(
    val folderId: String,
    val folderName: String
)

@Serializable
data class CollectionRoute(
    val collectionId: String,
    val collectionName: String
)

@Serializable
data class SpecialCollectionRoute(
    val filterName: String
)

@Serializable
data object LocationsRoute

@Serializable
data object PeopleRoute

@Serializable
data class PersonDetailRoute(
    val personId: String
)

@Serializable
data object CleanOutRoute

@Serializable
data class ViewerRoute(
    val folderId: String,
    val initialItemId: String,
    val isFlattened: Boolean = false,
    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
    val mediaTypeName: String? = null,
    val fromEpochMs: Long? = null,
    val toEpochMs: Long? = null,
    val onlyWithText: Boolean = false,
    val specialFilterName: String? = null,
    val albumFilterName: String? = null,
    val albumSortName: String? = null,
    val locationKey: String? = null,
    val personId: String? = null
)

@Serializable
data object SearchRoute

@Serializable
data object SettingsRoute

@Serializable
data class EditorRoute(
    val itemId: String,
    val sourceUri: String,
    val displayName: String,
    val isLocal: Boolean,
    val isLocked: Boolean = false
)

@Serializable
data class CompareRoute(
    val itemIdsCsv: String
)

private const val KEY_LAST_VIEWED_ITEM_ID = "last_viewed_item_id"

private fun pageSlideSpec() = tween<IntOffset>(
    durationMillis = OneUiMotion.PAGE_SLIDE_MS,
    easing = OneUiMotion.PageSlideEasing
)

/** Keeps the outgoing screen fully composed & visible for [durationMillis] without moving it. */
private fun holdStillExit(durationMillis: Int): ExitTransition =
    fadeOut(tween(durationMillis, easing = LinearEasing), targetAlpha = 0.99f)

/** Keeps the incoming morph screen alive while HyperOsRectMorphFrame drives the real visuals. */
private fun holdStillEnter(durationMillis: Int): EnterTransition =
    fadeIn(tween(durationMillis, easing = LinearEasing), initialAlpha = 0.99f)

private fun NavDestination.isMorphRoute(): Boolean =
    hasRoute<ViewerRoute>() || hasRoute<FolderRoute>() || hasRoute<CollectionRoute>()

/**
 * Keeps rect-morph routes (`FolderRoute`, `CollectionRoute`, `ViewerRoute`) closing cleanly
 * on gesture release so `HyperOsRectMorphFrame` animates smoothly back into the source tile,
 * while standard slide routes use Android's native predictive-back transitions.
 */
@Composable
private fun PopOnReleaseBackHandler(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
}

private fun NavBackStackEntry.isLockedDestination(lockedIds: Set<String>): Boolean {
    if (destination.hasRoute<EditorRoute>()) {
        val route = runCatching { toRoute<EditorRoute>() }.getOrNull()
        if (route?.isLocked == true || route?.sourceUri?.startsWith("file://") == true) {
            return true
        }
    }
    if (lockedIds.isEmpty()) return false
    if (destination.hasRoute<FolderRoute>()) {
        val route = runCatching { toRoute<FolderRoute>() }.getOrNull()
        if (route != null && route.folderId in lockedIds) return true
    }
    if (destination.hasRoute<ViewerRoute>()) {
        val route = runCatching { toRoute<ViewerRoute>() }.getOrNull()
        if (route != null && route.folderId in lockedIds) return true
    }
    return false
}

@Composable
fun LuminaNavGraph(
    externalViewUri: String? = null,
    externalViewMime: String? = null,
    onConsumedExternalView: () -> Unit = {},
    shortcutTarget: String? = null,
    onConsumedShortcut: () -> Unit = {},
    widgetOpenItemId: String? = null,
    onConsumedWidgetItem: () -> Unit = {}
) {
    val navController = rememberNavController()
    val container = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val activityContext = LocalContext.current
    val appContext = activityContext.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val lockedAlbumIds by container.galleryExtrasRepository.lockedAlbumIdsFlow
        .collectAsStateWithLifecycle(initialValue = container.galleryExtrasRepository.latestLockedAlbumIds)
    val originTracker = remember { AlbumTransitionOriginTracker() }
    var activeViewerItemId by remember { mutableStateOf<String?>(null) }

    // Track active NavHost transitions so back-to-back closes (e.g. close photo then immediately
    // close album) play BOTH animations cleanly one after the other instead of NavHost cancelling
    // the second route's exit transition mid-flight!
    val visibleEntries by navController.visibleEntries.collectAsStateWithLifecycle()
    var pendingBackPops by remember { mutableIntStateOf(0) }

    fun safePopBackStack() {
        if (navController.previousBackStackEntry == null) {
            pendingBackPops = 0
            return
        }
        val inFlight = navController.visibleEntries.value.size > 1
        if (inFlight) {
            if (pendingBackPops < 2) {
                pendingBackPops += 1
            }
        } else {
            navController.popBackStack()
        }
    }

    LaunchedEffect(visibleEntries.size, pendingBackPops) {
        if (visibleEntries.size <= 1 && pendingBackPops > 0) {
            withFrameNanos { }
            if (navController.visibleEntries.value.size <= 1 && navController.previousBackStackEntry != null) {
                pendingBackPops -= 1
                navController.popBackStack()
            } else if (navController.previousBackStackEntry == null) {
                pendingBackPops = 0
            }
        }
    }

    // Auto-relock private albums whenever the app goes to background (home / recents / screen lock),
    // while ignoring transient configuration changes like device rotation.
    DisposableEffect(lifecycleOwner, navController, activityContext) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val activity = activityContext.findActivity()
                if (activity?.isChangingConfigurations == true) return@LifecycleEventObserver
                val currentLocked = container.galleryExtrasRepository.latestLockedAlbumIds
                val entriesToCheck = buildList {
                    navController.currentBackStackEntry?.let(::add)
                    navController.previousBackStackEntry?.let(::add)
                    addAll(navController.visibleEntries.value)
                }
                if (entriesToCheck.any { it.isLockedDestination(currentLocked) }) {
                    pendingBackPops = 0
                    navController.popBackStack(route = HomeRoute, inclusive = false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Handle static launcher shortcuts (Search, Favorites, Trash, Clean Out)
    LaunchedEffect(shortcutTarget) {
        val target = shortcutTarget ?: return@LaunchedEffect
        pendingBackPops = 0
        when (target) {
            "search" -> navController.navigate(SearchRoute) { launchSingleTop = true }
            "favorites" -> navController.navigate(SpecialCollectionRoute(SpecialGalleryFilter.FAVORITES.name))
            "trash" -> navController.navigate(SpecialCollectionRoute(SpecialGalleryFilter.TRASH.name))
            "cleanout" -> navController.navigate(CleanOutRoute) { launchSingleTop = true }
        }
        onConsumedShortcut()
    }

    // Handle Home Screen Photo Widget tap deep-link
    LaunchedEffect(widgetOpenItemId) {
        val itemId = widgetOpenItemId ?: return@LaunchedEffect
        pendingBackPops = 0
        activeViewerItemId = itemId
        navController.navigate(
            ViewerRoute(
                folderId = LocalMediaRepository.LOCAL_BUCKET_ALL,
                initialItemId = itemId
            )
        )
        onConsumedWidgetItem()
    }

    fun isAlbumLocked(folderId: String): Boolean =
        folderId in lockedAlbumIds || folderId in container.galleryExtrasRepository.latestLockedAlbumIds

    fun prewarmFolderAndNavigate(folderId: String, folderName: String) {
        val navigateNow: () -> Unit = {
            pendingBackPops = 0
            val prefs = container.preferencesManager.latestPreferences
            val highRes = container.networkMonitor.networkState.value.isUnmeteredWifi || !prefs.wifiOnlyHighRes
            val cachedItems = if (LocalMediaRepository.isLocalFolderId(folderId)) {
                container.localMediaRepository.getCachedAlbumMedia(folderId)
            } else {
                container.galleryRepository.getCachedFolderMedia(folderId).orEmpty()
            }
            if (cachedItems.isNotEmpty()) {
                prefetchAlbumThumbnails(
                    context = appContext,
                    imageLoader = imageLoader,
                    items = cachedItems,
                    isUnmeteredWifi = highRes
                )
            }
            navController.navigate(FolderRoute(folderId, folderName))
        }

        if (isAlbumLocked(folderId)) {
            BiometricLockHelper.authenticate(
                context = activityContext,
                title = "Unlock \"$folderName\"",
                subtitle = "Verify your identity to open locked album",
                onSuccess = navigateNow,
                onFailure = { msg ->
                    Toast.makeText(activityContext, msg, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            navigateNow()
        }
    }

    CompositionLocalProvider(LocalAlbumTransitionOrigin provides originTracker) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                enterTransition = {
                    // Xiaomi HyperOS crisp full-width page push
                    slideInHorizontally(pageSlideSpec()) { it }
                },
                exitTransition = {
                    // Never run card-morph on a screen moving to background behind a newly pushed child route
                    originTracker.setMorphEnabled(initialState.id, false)
                    val toMorphRoute = targetState.destination.isMorphRoute()
                    originTracker.notePush(dimBackground = !toMorphRoute)
                    if (toMorphRoute) {
                        // Grid / album list stays perfectly still while the tapped card blooms open
                        // (exact HyperOS behaviour, keeps the recorded tile rects valid for the return trip).
                        holdStillExit(OneUiMotion.MORPH_OPEN_MS)
                    } else {
                        // Underlying page drifts left a quarter width under a soft depth scrim
                        slideOutHorizontally(pageSlideSpec()) { -it / 4 }
                    }
                },
                popEnterTransition = {
                    // Never run card-morph on a screen returning from background as a child route pops above it
                    originTracker.setMorphEnabled(targetState.id, false)
                    val fromMorphRoute = initialState.destination.isMorphRoute()
                    originTracker.notePop(dimBackground = !fromMorphRoute)
                    if (fromMorphRoute) {
                        // Grid stays rock-solid so the photo / album lands exactly on its tile
                        EnterTransition.None
                    } else {
                        slideInHorizontally(pageSlideSpec()) { -it / 4 }
                    }
                },
                popExitTransition = {
                    slideOutHorizontally(pageSlideSpec()) { it }
                }
            ) {
                composable<HomeRoute> { backStackEntry ->
                    val homeViewModel: HomeViewModel = viewModel(
                        factory = HomeViewModel.Factory(container)
                    )
                    val lastViewedItemId by backStackEntry.savedStateHandle
                        .getStateFlow<String?>(KEY_LAST_VIEWED_ITEM_ID, null)
                        .collectAsStateWithLifecycle()

                    HyperOsTransitionFrame {
                        HomeScreen(
                            viewModel = homeViewModel,
                            lastViewedItemId = lastViewedItemId,
                            onConsumedLastViewedItemId = {
                                backStackEntry.savedStateHandle[KEY_LAST_VIEWED_ITEM_ID] = null
                            },
                            onOpenFolder = { folderId, folderName ->
                                prewarmFolderAndNavigate(folderId, folderName)
                            },
                            onOpenCollection = { collectionId, collectionName ->
                                navController.navigate(CollectionRoute(collectionId, collectionName))
                            },
                            onOpenPictureViewer = { itemId ->
                                activeViewerItemId = itemId
                                navController.navigate(
                                    ViewerRoute(
                                        folderId = LocalMediaRepository.LOCAL_BUCKET_ALL,
                                        initialItemId = itemId
                                    )
                                )
                            },
                            onOpenSearch = {
                                navController.navigate(SearchRoute)
                            },
                            onOpenSettings = {
                                navController.navigate(SettingsRoute)
                            },
                            onOpenSpecialFilter = { filter ->
                                navController.navigate(SpecialCollectionRoute(filter.name))
                            },
                            onOpenLocations = {
                                navController.navigate(LocationsRoute)
                            },
                            onOpenPeople = {
                                navController.navigate(PeopleRoute)
                            },
                            onOpenCleanOut = {
                                navController.navigate(CleanOutRoute)
                            },
                            onOpenCompare = { ids ->
                                navController.navigate(CompareRoute(ids.joinToString(",")))
                            }
                        )
                    }
                }

                composable<SpecialCollectionRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<SpecialCollectionRoute>()
                    val filter = runCatching { SpecialGalleryFilter.valueOf(route.filterName) }
                        .getOrDefault(SpecialGalleryFilter.VIDEOS)

                    HyperOsTransitionFrame {
                        SpecialCollectionScreen(
                            filter = filter,
                            onNavigateBack = { safePopBackStack() },
                            onOpenViewer = { activeFilter, itemId ->
                                activeViewerItemId = itemId
                                navController.navigate(
                                    ViewerRoute(
                                        folderId = "",
                                        initialItemId = itemId,
                                        specialFilterName = activeFilter.name
                                    )
                                )
                            }
                        )
                    }
                }

                composable<LocationsRoute> {
                    HyperOsTransitionFrame {
                        LocationsScreen(
                            onNavigateBack = { safePopBackStack() },
                            onOpenPhoto = { item, locKey ->
                                activeViewerItemId = item.id
                                navController.navigate(
                                    ViewerRoute(
                                        folderId = LocalMediaRepository.LOCAL_BUCKET_ALL,
                                        initialItemId = item.id,
                                        locationKey = locKey
                                    )
                                )
                            }
                        )
                    }
                }

                composable<PeopleRoute> {
                    val peopleViewModel: PeopleViewModel = viewModel(
                        factory = PeopleViewModel.Factory(container)
                    )
                    HyperOsTransitionFrame {
                        PeopleScreen(
                            viewModel = peopleViewModel,
                            onNavigateBack = { safePopBackStack() },
                            onOpenPerson = { personId ->
                                navController.navigate(PersonDetailRoute(personId))
                            }
                        )
                    }
                }

                composable<PersonDetailRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<PersonDetailRoute>()
                    val detailViewModel: PersonDetailViewModel = viewModel(
                        key = "person_${route.personId}",
                        factory = PersonDetailViewModel.Factory(route.personId, container)
                    )
                    HyperOsTransitionFrame {
                        PersonDetailScreen(
                            personId = route.personId,
                            viewModel = detailViewModel,
                            onNavigateBack = { safePopBackStack() },
                            onOpenViewer = { pid, itemId ->
                                activeViewerItemId = itemId
                                navController.navigate(
                                    ViewerRoute(
                                        folderId = "",
                                        initialItemId = itemId,
                                        personId = pid
                                    )
                                )
                            },
                            onOpenCompare = { ids ->
                                navController.navigate(CompareRoute(ids.joinToString(",")))
                            }
                        )
                    }
                }

                composable<CleanOutRoute> {
                    val cleanOutViewModel: CleanOutViewModel = viewModel(
                        factory = CleanOutViewModel.Factory(container)
                    )
                    HyperOsTransitionFrame {
                        CleanOutScreen(
                            viewModel = cleanOutViewModel,
                            onNavigateBack = { safePopBackStack() },
                            onOpenPreview = { item ->
                                activeViewerItemId = item.id
                                navController.navigate(
                                    ViewerRoute(
                                        folderId = LocalMediaRepository.LOCAL_BUCKET_ALL,
                                        initialItemId = item.id
                                    )
                                )
                            },
                            onOpenCompare = { ids ->
                                navController.navigate(CompareRoute(ids.joinToString(",")))
                            }
                        )
                    }
                }

                // True Xiaomi HyperOS Rect-to-Rect Morph for Collections (shrinks right into the exact tapped cover!)
                composable<CollectionRoute>(
                    enterTransition = {
                        originTracker.setMorphEnabled(targetState.id, true)
                        holdStillEnter(OneUiMotion.MORPH_OPEN_MS)
                    },
                    exitTransition = null,
                    popEnterTransition = null,
                    popExitTransition = {
                        originTracker.setMorphEnabled(initialState.id, true)
                        holdStillExit(OneUiMotion.MORPH_CLOSE_MS)
                    }
                ) { backStackEntry ->
                    val route = backStackEntry.toRoute<CollectionRoute>()
                    val collectionViewModel: CollectionViewModel = viewModel(
                        key = "collection_${route.collectionId}",
                        factory = CollectionViewModel.Factory(
                            collectionId = route.collectionId,
                            collectionName = route.collectionName,
                            container = container
                        )
                    )
                    PopOnReleaseBackHandler(::safePopBackStack)
                    HyperOsRectMorphFrame(
                        entryId = backStackEntry.id,
                        targetKey = route.collectionId,
                        targetCornerRadius = 18.dp,
                        verticalCropBias = 0f,
                        backdropMaxAlpha = 0.34f,
                        fallbackToLastBounds = false,
                        midFlightCornerBump = 8.dp,
                        hideSourceWhileMorphing = true,
                        useAlbumCoverCrossfade = true
                    ) {
                        CollectionScreen(
                            viewModel = collectionViewModel,
                            onNavigateBack = { safePopBackStack() },
                            onNavigateHome = {
                                navController.popBackStack(route = HomeRoute, inclusive = false)
                            },
                            onOpenAlbum = { folderId, folderName ->
                                prewarmFolderAndNavigate(folderId, folderName)
                            },
                            onOpenSubCollection = { subId, subName ->
                                navController.navigate(CollectionRoute(subId, subName))
                            },
                            onOpenBreadcrumbCollection = { ancestorId, ancestorName ->
                                val popped = navController.popBackStack(
                                    route = CollectionRoute(ancestorId, ancestorName),
                                    inclusive = false
                                )
                                if (!popped) {
                                    navController.navigate(CollectionRoute(ancestorId, ancestorName))
                                }
                            }
                        )
                    }
                }

                // True Xiaomi HyperOS Rect-to-Rect Morph for Albums (expands out of & shrinks directly into the exact tapped album card!)
                composable<FolderRoute>(
                    enterTransition = {
                        originTracker.setMorphEnabled(targetState.id, true)
                        holdStillEnter(OneUiMotion.MORPH_OPEN_MS)
                    },
                    exitTransition = null,
                    popEnterTransition = null,
                    popExitTransition = {
                        originTracker.setMorphEnabled(initialState.id, true)
                        holdStillExit(OneUiMotion.MORPH_CLOSE_MS)
                    }
                ) { backStackEntry ->
                    val route = backStackEntry.toRoute<FolderRoute>()
                    val folderViewModel: FolderViewModel = viewModel(
                        key = "folder_${route.folderId}",
                        factory = FolderViewModel.Factory(
                            folderId = route.folderId,
                            folderName = route.folderName,
                            container = container
                        )
                    )

                    val activeFolderId by folderViewModel.uiState
                        .collectAsStateWithLifecycle()

                    val lastViewedItemId by backStackEntry.savedStateHandle
                        .getStateFlow<String?>(KEY_LAST_VIEWED_ITEM_ID, null)
                        .collectAsStateWithLifecycle()

                    PopOnReleaseBackHandler(::safePopBackStack)
                    HyperOsRectMorphFrame(
                        entryId = backStackEntry.id,
                        targetKey = activeFolderId.folderId.ifBlank { route.folderId },
                        targetCornerRadius = 18.dp,
                        verticalCropBias = 0f,
                        backdropMaxAlpha = 0.34f,
                        fallbackToLastBounds = false,
                        midFlightCornerBump = 8.dp,
                        hideSourceWhileMorphing = true,
                        useAlbumCoverCrossfade = true
                    ) {
                        FolderScreen(
                            viewModel = folderViewModel,
                            lastViewedItemId = lastViewedItemId,
                            onConsumedLastViewedItemId = {
                                backStackEntry.savedStateHandle[KEY_LAST_VIEWED_ITEM_ID] = null
                            },
                            onNavigateBack = { safePopBackStack() },
                            onNavigateHome = {
                                navController.popBackStack(route = HomeRoute, inclusive = false)
                            },
                            onOpenSubfolder = { subId, subName ->
                                prewarmFolderAndNavigate(subId, subName)
                            },
                            onOpenBreadcrumb = { ancestorId, ancestorName ->
                                val openAncestor = {
                                    val popped = navController.popBackStack(
                                        route = FolderRoute(ancestorId, ancestorName),
                                        inclusive = false
                                    )
                                    if (!popped) {
                                        navController.navigate(FolderRoute(ancestorId, ancestorName))
                                    }
                                }
                                if (isAlbumLocked(ancestorId)) {
                                    BiometricLockHelper.authenticate(
                                        context = activityContext,
                                        title = "Unlock \"$ancestorName\"",
                                        subtitle = "Verify your identity to open locked album",
                                        onSuccess = openAncestor,
                                        onFailure = { msg ->
                                            Toast.makeText(activityContext, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } else {
                                    openAncestor()
                                }
                            },
                            onOpenViewer = { folderId, itemId, isFlattened, filter, sort ->
                                activeViewerItemId = itemId
                                navController.navigate(
                                    ViewerRoute(
                                        folderId = folderId,
                                        initialItemId = itemId,
                                        isFlattened = isFlattened,
                                        albumFilterName = filter.name,
                                        albumSortName = sort.name
                                    )
                                )
                            },
                            onOpenSearch = {
                                navController.navigate(SearchRoute)
                            },
                            onOpenCompare = { ids ->
                                navController.navigate(CompareRoute(ids.joinToString(",")))
                            }
                        )
                    }
                }

                composable<SearchRoute> {
                    val searchViewModel: SearchViewModel = viewModel(
                        factory = SearchViewModel.Factory(container)
                    )
                    HyperOsTransitionFrame {
                        SearchScreen(
                            viewModel = searchViewModel,
                            onNavigateBack = { safePopBackStack() },
                            onOpenPeople = {
                                navController.navigate(PeopleRoute)
                            },
                            onOpenPersonDetail = { personId ->
                                navController.navigate(PersonDetailRoute(personId))
                            },
                            onOpenViewer = { itemId, query, mediaType, fromMs, toMs, onlyWithText, personId ->
                                activeViewerItemId = itemId
                                navController.navigate(
                                    ViewerRoute(
                                        folderId = "",
                                        initialItemId = itemId,
                                        isSearchMode = true,
                                        searchQuery = query,
                                        mediaTypeName = mediaType?.name,
                                        fromEpochMs = fromMs,
                                        toEpochMs = toMs,
                                        onlyWithText = onlyWithText,
                                        personId = personId
                                    )
                                )
                            },
                            onOpenCompare = { ids ->
                                navController.navigate(CompareRoute(ids.joinToString(",")))
                            }
                        )
                    }
                }

                // True Xiaomi HyperOS / Apple Photos Rect-to-Rect Morph for full-screen Viewer
                // (expands directly out of the tapped thumbnail & shrinks straight back into the active photo's tile!)
                composable<ViewerRoute>(
                    enterTransition = {
                        originTracker.setMorphEnabled(targetState.id, true)
                        holdStillEnter(OneUiMotion.MORPH_OPEN_MS)
                    },
                    exitTransition = {
                        // Viewer -> Editor: regular HyperOS page push
                        originTracker.setMorphEnabled(initialState.id, false)
                        originTracker.notePush(dimBackground = true)
                        slideOutHorizontally(pageSlideSpec()) { -it / 4 }
                    },
                    popEnterTransition = {
                        originTracker.setMorphEnabled(targetState.id, false)
                        originTracker.notePop(dimBackground = true)
                        slideInHorizontally(pageSlideSpec()) { -it / 4 }
                    },
                    popExitTransition = {
                        originTracker.setMorphEnabled(initialState.id, true)
                        holdStillExit(OneUiMotion.MORPH_CLOSE_MS)
                    }
                ) { backStackEntry ->
                    val route = backStackEntry.toRoute<ViewerRoute>()
                    val mediaType = route.mediaTypeName?.let {
                        runCatching { MediaType.valueOf(it) }.getOrNull()
                    }
                    val specialFilter = route.specialFilterName?.let {
                        runCatching { SpecialGalleryFilter.valueOf(it) }.getOrNull()
                    }
                    val albumFilter = route.albumFilterName?.let {
                        runCatching { AlbumMediaFilter.valueOf(it) }.getOrNull()
                    } ?: AlbumMediaFilter.ALL
                    val albumSort = route.albumSortName?.let {
                        runCatching { AlbumMediaSort.valueOf(it) }.getOrNull()
                    } ?: AlbumMediaSort.DATE_DESC
                    val initialTappedItem = remember(route.initialItemId) {
                        originTracker.lastTappedMediaItem?.takeIf { it.id == route.initialItemId }
                    }
                    val viewerViewModel: ViewerViewModel = viewModel(
                        key = "viewer_${route.folderId}_${route.initialItemId}_${route.isFlattened}_${route.isSearchMode}_${route.onlyWithText}_${route.specialFilterName}_${route.albumFilterName}_${route.albumSortName}_${route.locationKey}_${route.personId}",
                        factory = ViewerViewModel.Factory(
                            launchContext = ViewerLaunchContext(
                                folderId = route.folderId,
                                initialItemId = route.initialItemId,
                                isFlattened = route.isFlattened,
                                isSearchMode = route.isSearchMode,
                                searchQuery = route.searchQuery,
                                mediaTypeFilter = mediaType,
                                fromEpochMs = route.fromEpochMs,
                                toEpochMs = route.toEpochMs,
                                onlyWithText = route.onlyWithText,
                                specialFilter = specialFilter,
                                albumFilter = albumFilter,
                                albumSort = albumSort,
                                locationKey = route.locationKey,
                                personId = route.personId
                            ),
                            container = container,
                            initialTappedItem = initialTappedItem
                        )
                    )
                    val targetPhotoKey = "media_${activeViewerItemId ?: route.initialItemId}"
                    PopOnReleaseBackHandler(::safePopBackStack)
                    HyperOsRectMorphFrame(
                        entryId = backStackEntry.id,
                        targetKey = targetPhotoKey,
                        targetCornerRadius = 0.dp,
                        verticalCropBias = 0.5f,
                        backdropMaxAlpha = 1f,
                        fallbackToLastBounds = false,
                        maxShadowElevation = 0.dp,
                        midFlightCornerBump = 8.dp,
                        hideSourceWhileMorphing = true,
                        useAlbumCoverCrossfade = false,
                        sourceRectProvider = { originTracker.viewerContentRectProvider?.invoke() },
                        backdropAlphaProvider = { originTracker.viewerBackdropAlphaProvider?.invoke() ?: 1f }
                    ) { isRectMorphing ->
                        ViewerScreen(
                            viewModel = viewerViewModel,
                            animatedVisibilityScope = this@composable,
                            initialItemId = route.initialItemId,
                            isRectMorphing = isRectMorphing,
                            onActiveItemChanged = { activeId ->
                                activeViewerItemId = activeId
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set(KEY_LAST_VIEWED_ITEM_ID, activeId)
                            },
                            onOpenEditor = { itemId, sourceUri, displayName, isLocal ->
                                navController.navigate(
                                    EditorRoute(
                                        itemId = itemId,
                                        sourceUri = sourceUri,
                                        displayName = displayName,
                                        isLocal = isLocal,
                                        isLocked = isAlbumLocked(route.folderId) || sourceUri.startsWith("file://")
                                    )
                                )
                            },
                            onDismiss = { safePopBackStack() }
                        )
                    }
                }

                composable<EditorRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<EditorRoute>()
                    HyperOsTransitionFrame {
                        PhotoEditorScreen(
                            itemId = route.itemId,
                            sourceUri = route.sourceUri,
                            displayName = route.displayName,
                            isLocal = route.isLocal,
                            onSavedAndClose = { msg ->
                                Toast.makeText(activityContext, msg, Toast.LENGTH_SHORT).show()
                                safePopBackStack()
                            },
                            onDismiss = { safePopBackStack() },
                            isLocked = route.isLocked
                        )
                    }
                }

                composable<CompareRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<CompareRoute>()
                    val ids = remember(route.itemIdsCsv) {
                        route.itemIdsCsv.split(",").filter { it.isNotBlank() }
                    }
                    HyperOsTransitionFrame {
                        CompareScreen(
                            itemIds = ids,
                            onNavigateBack = { safePopBackStack() }
                        )
                    }
                }

                composable<SettingsRoute> {
                    val settingsViewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.Factory(
                            appContext = appContext,
                            container = container
                        )
                    )
                    HyperOsTransitionFrame {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onNavigateBack = { safePopBackStack() }
                        )
                    }
                }
            }

            if (!externalViewUri.isNullOrBlank()) {
                ExternalMediaViewerOverlay(
                    uriString = externalViewUri,
                    mimeType = externalViewMime ?: "image/*",
                    onOpenEditor = { uri, name ->
                        onConsumedExternalView()
                        navController.navigate(
                            EditorRoute(
                                itemId = "ext_${uri.hashCode()}",
                                sourceUri = uri,
                                displayName = name,
                                isLocal = true
                            )
                        )
                    },
                    onDismiss = onConsumedExternalView
                )
            }
        }
    }
}

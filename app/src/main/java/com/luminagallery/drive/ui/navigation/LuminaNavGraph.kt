package com.luminagallery.drive.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.domain.model.SpecialGalleryFilter
import com.luminagallery.drive.ui.components.AlbumTransitionOriginTracker
import com.luminagallery.drive.ui.components.HyperOsRectMorphFrame
import com.luminagallery.drive.ui.components.HyperOsTransitionFrame
import com.luminagallery.drive.ui.components.LocalAlbumTransitionOrigin
import com.luminagallery.drive.ui.components.OneUiMotion
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
    val specialFilterName: String? = null
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
    val isLocal: Boolean
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
 * Xiaomi HyperOS / Samsung One UI Gallery back behaviour: the close animation must start when the
 * back gesture is RELEASED, not scrub along while the finger is still dragging from the edge.
 *
 * Navigation Compose would otherwise seek every transition with the predictive-back progress.
 * This plain (non-predictive) handler is registered inside each destination, so it sits above
 * NavHost's predictive handler but below any screen-level handlers (selection mode, map sheets…)
 * that compose after it. On release it pops, and the full, smooth close animation plays.
 */
@Composable
private fun PopOnReleaseBackHandler(navController: NavHostController) {
    BackHandler {
        navController.popBackStack()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LuminaNavGraph(
    externalViewUri: String? = null,
    externalViewMime: String? = null,
    onConsumedExternalView: () -> Unit = {}
) {
    val navController = rememberNavController()
    val container = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val appContext = LocalContext.current.applicationContext
    val originTracker = remember { AlbumTransitionOriginTracker() }
    var activeViewerItemId by remember { mutableStateOf<String?>(null) }

    fun prewarmFolderAndNavigate(folderId: String, folderName: String) {
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

    CompositionLocalProvider(LocalAlbumTransitionOrigin provides originTracker) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
        SharedTransitionLayout {
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
                    // Plays only AFTER the back gesture is released (see PopOnReleaseBackHandler)
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
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@composable,
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

                    PopOnReleaseBackHandler(navController)
                    HyperOsTransitionFrame {
                        SpecialCollectionScreen(
                            filter = filter,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@composable,
                            onNavigateBack = { navController.popBackStack() },
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
                    PopOnReleaseBackHandler(navController)
                    HyperOsTransitionFrame {
                        LocationsScreen(
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@composable,
                            onNavigateBack = { navController.popBackStack() },
                            onOpenPhoto = { item ->
                                activeViewerItemId = item.id
                                navController.navigate(
                                    ViewerRoute(
                                        folderId = LocalMediaRepository.LOCAL_BUCKET_ALL,
                                        initialItemId = item.id
                                    )
                                )
                            }
                        )
                    }
                }

                composable<CleanOutRoute> {
                    val cleanOutViewModel: CleanOutViewModel = viewModel(
                        factory = CleanOutViewModel.Factory(container)
                    )
                    PopOnReleaseBackHandler(navController)
                    HyperOsTransitionFrame {
                        CleanOutScreen(
                            viewModel = cleanOutViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }

                // True Xiaomi HyperOS Rect-to-Rect Morph for Collections (shrinks right into the exact tapped cover!)
                composable<CollectionRoute>(
                    enterTransition = {
                        originTracker.setMorphEnabled(targetState.id, true)
                        holdStillEnter(OneUiMotion.MORPH_OPEN_MS)
                    },
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
                    PopOnReleaseBackHandler(navController)
                    HyperOsRectMorphFrame(
                        entryId = backStackEntry.id,
                        targetKey = route.collectionId,
                        targetCornerRadius = 18.dp,
                        verticalCropBias = 0.16f,
                        backdropMaxAlpha = 0.28f
                    ) {
                        CollectionScreen(
                            viewModel = collectionViewModel,
                            onNavigateBack = { navController.popBackStack() },
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

                    PopOnReleaseBackHandler(navController)
                    HyperOsRectMorphFrame(
                        entryId = backStackEntry.id,
                        targetKey = activeFolderId.folderId.ifBlank { route.folderId },
                        targetCornerRadius = 18.dp,
                        verticalCropBias = 0.16f,
                        backdropMaxAlpha = 0.28f
                    ) {
                        FolderScreen(
                            viewModel = folderViewModel,
                            lastViewedItemId = lastViewedItemId,
                            onConsumedLastViewedItemId = {
                                backStackEntry.savedStateHandle[KEY_LAST_VIEWED_ITEM_ID] = null
                            },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@composable,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateHome = {
                                navController.popBackStack(route = HomeRoute, inclusive = false)
                            },
                            onOpenSubfolder = { subId, subName ->
                                prewarmFolderAndNavigate(subId, subName)
                            },
                            onOpenBreadcrumb = { ancestorId, ancestorName ->
                                val popped = navController.popBackStack(
                                    route = FolderRoute(ancestorId, ancestorName),
                                    inclusive = false
                                )
                                if (!popped) {
                                    navController.navigate(FolderRoute(ancestorId, ancestorName))
                                }
                            },
                            onOpenViewer = { folderId, itemId, isFlattened ->
                                activeViewerItemId = itemId
                                navController.navigate(
                                    ViewerRoute(
                                        folderId = folderId,
                                        initialItemId = itemId,
                                        isFlattened = isFlattened
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
                    PopOnReleaseBackHandler(navController)
                    HyperOsTransitionFrame {
                        SearchScreen(
                            viewModel = searchViewModel,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@composable,
                            onNavigateBack = { navController.popBackStack() },
                            onOpenViewer = { itemId, query, mediaType, fromMs, toMs ->
                                activeViewerItemId = itemId
                                navController.navigate(
                                    ViewerRoute(
                                        folderId = "",
                                        initialItemId = itemId,
                                        isSearchMode = true,
                                        searchQuery = query,
                                        mediaTypeName = mediaType?.name,
                                        fromEpochMs = fromMs,
                                        toEpochMs = toMs
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
                    val viewerViewModel: ViewerViewModel = viewModel(
                        key = "viewer_${route.folderId}_${route.initialItemId}_${route.isFlattened}_${route.isSearchMode}_${route.specialFilterName}",
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
                                specialFilter = specialFilter
                            ),
                            container = container
                        )
                    )
                    val targetPhotoKey = "media_${activeViewerItemId ?: route.initialItemId}"
                    PopOnReleaseBackHandler(navController)
                    HyperOsRectMorphFrame(
                        entryId = backStackEntry.id,
                        targetKey = targetPhotoKey,
                        targetCornerRadius = 0.dp,
                        verticalCropBias = 0.5f,
                        backdropMaxAlpha = 1f,
                        fallbackToLastBounds = false,
                        maxShadowElevation = 0.dp,
                        midFlightCornerBump = 12.dp,
                        sourceRectProvider = { originTracker.viewerContentRect },
                        backdropAlphaProvider = { originTracker.viewerBackdropAlpha }
                    ) { isRectMorphing ->
                        ViewerScreen(
                            viewModel = viewerViewModel,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@composable,
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
                                        isLocal = isLocal
                                    )
                                )
                            },
                            onDismiss = { navController.popBackStack() }
                        )
                    }
                }

                composable<EditorRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<EditorRoute>()
                    PopOnReleaseBackHandler(navController)
                    HyperOsTransitionFrame {
                        PhotoEditorScreen(
                            itemId = route.itemId,
                            sourceUri = route.sourceUri,
                            displayName = route.displayName,
                            isLocal = route.isLocal,
                            onSavedAndClose = { navController.popBackStack() },
                            onDismiss = { navController.popBackStack() }
                        )
                    }
                }

                composable<CompareRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<CompareRoute>()
                    val ids = remember(route.itemIdsCsv) {
                        route.itemIdsCsv.split(",").filter { it.isNotBlank() }
                    }
                    PopOnReleaseBackHandler(navController)
                    HyperOsTransitionFrame {
                        CompareScreen(
                            itemIds = ids,
                            onNavigateBack = { navController.popBackStack() }
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
                    PopOnReleaseBackHandler(navController)
                    HyperOsTransitionFrame {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
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

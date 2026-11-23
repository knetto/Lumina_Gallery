package com.luminagallery.drive.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
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
private val FluidOpenEasing = OneUiMotion.FluidEasing
private val HyperOsBackEasing = OneUiMotion.HyperOsBackEasing
private val BackFadeCommitEasing = OneUiMotion.BackFadeCommitEasing

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
                    // Apple iOS / Xiaomi HyperOS crisp full-width slide-in
                    slideInHorizontally(tween(300, easing = FluidOpenEasing)) { it }
                },
                exitTransition = {
                    when {
                        targetState.destination.hasRoute<ViewerRoute>() -> {
                            scaleOut(tween(260, easing = FluidOpenEasing), targetScale = 0.96f)
                        }
                        targetState.destination.hasRoute<FolderRoute>() ||
                            targetState.destination.hasRoute<CollectionRoute>() -> {
                            val key = runCatching {
                                if (targetState.destination.hasRoute<FolderRoute>()) {
                                    targetState.toRoute<FolderRoute>().folderId
                                } else {
                                    targetState.toRoute<CollectionRoute>().collectionId
                                }
                            }.getOrNull()
                            scaleOut(
                                animationSpec = tween(280, easing = FluidOpenEasing),
                                targetScale = 1.04f,
                                transformOrigin = originTracker.originFor(key)
                            )
                        }
                        else -> {
                            // Subtle iOS/HyperOS underlying parallax push-back (stays 100% solid!)
                            slideOutHorizontally(tween(300, easing = FluidOpenEasing)) { -it / 4 } +
                                scaleOut(tween(300, easing = FluidOpenEasing), targetScale = 0.96f)
                        }
                    }
                },
                popEnterTransition = {
                    when {
                        initialState.destination.hasRoute<ViewerRoute>() -> {
                            // Underlying grid stays 100% solid while smoothly settling from 0.95 -> 1.0
                            scaleIn(tween(270, easing = HyperOsBackEasing), initialScale = 0.95f)
                        }
                        initialState.destination.hasRoute<FolderRoute>() ||
                            initialState.destination.hasRoute<CollectionRoute>() -> {
                            // Underlying Home/Albums stays 100% solid with smooth HyperOS depth settle
                            scaleIn(tween(280, easing = HyperOsBackEasing), initialScale = 0.95f)
                        }
                        else -> {
                            // Linear 1:1 finger-tracked iOS/HyperOS parallax reveal underneath!
                            slideInHorizontally(tween(300, easing = LinearEasing)) { -it / 4 } +
                                scaleIn(tween(300, easing = LinearEasing), initialScale = 0.96f)
                        }
                    }
                },
                popExitTransition = {
                    // 1:1 linear finger-tracked iOS / Xiaomi HyperOS floating rounded card slide-out!
                    // (No aggressive ease-out double-easing jump, no premature ghosting!)
                    slideOutHorizontally(tween(300, easing = LinearEasing)) { it } +
                        scaleOut(tween(300, easing = LinearEasing), targetScale = 0.94f)
                }
            ) {
                composable<HomeRoute> { backStackEntry ->
                    val homeViewModel: HomeViewModel = viewModel(
                        factory = HomeViewModel.Factory(container)
                    )
                    val lastViewedItemId by backStackEntry.savedStateHandle
                        .getStateFlow<String?>(KEY_LAST_VIEWED_ITEM_ID, null)
                        .collectAsStateWithLifecycle()

                    HyperOsTransitionFrame(enableExitRounding = false) {
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
                    HyperOsTransitionFrame {
                        CleanOutScreen(
                            viewModel = cleanOutViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }

                // Xiaomi HyperOS / Apple Photos fluid zoom-bloom & rounded-card return for Collections
                composable<CollectionRoute>(
                    enterTransition = {
                        val key = runCatching { targetState.toRoute<CollectionRoute>().collectionId }.getOrNull()
                        scaleIn(
                            animationSpec = tween(280, easing = FluidOpenEasing),
                            initialScale = 0.84f,
                            transformOrigin = originTracker.originFor(key)
                        ) + fadeIn(tween(140, easing = LinearOutSlowInEasing))
                    },
                    popExitTransition = {
                        val key = runCatching { initialState.toRoute<CollectionRoute>().collectionId }.getOrNull()
                        scaleOut(
                            animationSpec = tween(280, easing = HyperOsBackEasing),
                            targetScale = 0.78f,
                            transformOrigin = originTracker.originFor(key)
                        ) + slideOutHorizontally(tween(280, easing = HyperOsBackEasing)) { it / 12 } +
                            fadeOut(tween(280, easing = BackFadeCommitEasing))
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
                    HyperOsTransitionFrame {
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

                // Xiaomi HyperOS / Apple Photos fluid zoom-bloom & rounded-card return for Albums
                composable<FolderRoute>(
                    enterTransition = {
                        val key = runCatching { targetState.toRoute<FolderRoute>().folderId }.getOrNull()
                        scaleIn(
                            animationSpec = tween(280, easing = FluidOpenEasing),
                            initialScale = 0.84f,
                            transformOrigin = originTracker.originFor(key)
                        ) + fadeIn(tween(140, easing = LinearOutSlowInEasing))
                    },
                    popExitTransition = {
                        val key = runCatching { initialState.toRoute<FolderRoute>().folderId }.getOrNull()
                        scaleOut(
                            animationSpec = tween(280, easing = HyperOsBackEasing),
                            targetScale = 0.78f,
                            transformOrigin = originTracker.originFor(key)
                        ) + slideOutHorizontally(tween(280, easing = HyperOsBackEasing)) { it / 12 } +
                            fadeOut(tween(280, easing = BackFadeCommitEasing))
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

                    val lastViewedItemId by backStackEntry.savedStateHandle
                        .getStateFlow<String?>(KEY_LAST_VIEWED_ITEM_ID, null)
                        .collectAsStateWithLifecycle()

                    HyperOsTransitionFrame {
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

                // Xiaomi HyperOS / Apple Photos rounded-card zoom-back for full-screen Viewer
                composable<ViewerRoute>(
                    enterTransition = {
                        val key = runCatching { "media_${targetState.toRoute<ViewerRoute>().initialItemId}" }.getOrNull()
                        scaleIn(
                            animationSpec = tween(250, easing = FluidOpenEasing),
                            initialScale = 0.84f,
                            transformOrigin = originTracker.originFor(key)
                        ) + fadeIn(tween(140, easing = LinearOutSlowInEasing))
                    },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = { scaleIn(tween(240, easing = HyperOsBackEasing), initialScale = 0.96f) },
                    popExitTransition = {
                        val activeKey = activeViewerItemId?.let { "media_$it" }
                            ?: runCatching { "media_${initialState.toRoute<ViewerRoute>().initialItemId}" }.getOrNull()
                        scaleOut(
                            animationSpec = tween(270, easing = HyperOsBackEasing),
                            targetScale = 0.76f,
                            transformOrigin = originTracker.originForOrCenter(activeKey)
                        ) + fadeOut(tween(270, easing = BackFadeCommitEasing))
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
                    HyperOsTransitionFrame {
                        ViewerScreen(
                            viewModel = viewerViewModel,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@composable,
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

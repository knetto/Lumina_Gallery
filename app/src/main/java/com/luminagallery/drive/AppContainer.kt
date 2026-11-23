package com.luminagallery.drive

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.ImageLoader
import com.luminagallery.drive.data.local.GalleryDatabase
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.remote.DriveApiService
import com.luminagallery.drive.data.remote.NetworkModule
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.remote.auth.CredentialStore
import com.luminagallery.drive.data.remote.auth.DriveAuthInterceptor
import com.luminagallery.drive.data.remote.auth.ServiceAccountTokenManager
import com.luminagallery.drive.data.repository.AlbumOrganizationRepository
import com.luminagallery.drive.data.repository.AppUpdateManager
import com.luminagallery.drive.data.repository.CloudProfileSyncManager
import com.luminagallery.drive.data.repository.CoilCacheManager
import com.luminagallery.drive.data.repository.CollagePdfCreator
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.data.repository.LocalFileOperationsManager
import com.luminagallery.drive.data.repository.MediaExportManager
import com.luminagallery.drive.data.repository.MotionPhotoDetector
import com.luminagallery.drive.data.repository.OcrIndexerManager
import com.luminagallery.drive.data.repository.OfflineMediaManager
import com.luminagallery.drive.data.repository.VideoTrimmer
import com.luminagallery.drive.data.repository.sync.DriveSyncScheduler
import com.luminagallery.drive.domain.usecase.ExportMediaUseCase
import com.luminagallery.drive.domain.usecase.ManageRootsUseCase
import com.luminagallery.drive.domain.usecase.SyncGalleryUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val json: Json = NetworkModule.createJson()
    val credentialStore: CredentialStore = CredentialStore(appContext, json)
    val tokenManager: ServiceAccountTokenManager = ServiceAccountTokenManager(appContext, credentialStore)
    val authInterceptor: DriveAuthInterceptor = DriveAuthInterceptor(tokenManager)
    val okHttpClient: OkHttpClient = NetworkModule.createOkHttpClient(authInterceptor)
    val driveApiService: DriveApiService = NetworkModule.createDriveApiService(okHttpClient, json)
    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext)

    val database: GalleryDatabase = GalleryDatabase.build(appContext)
    val localMediaRepository: LocalMediaRepository = LocalMediaRepository(appContext)
    val preferencesManager: PreferencesManager = PreferencesManager(appContext)

    val coilCacheManager: CoilCacheManager = CoilCacheManager(
        context = appContext,
        okHttpClient = okHttpClient,
        initialMaxCacheMb = PreferencesManager.DEFAULT_CACHE_MB
    )

    init {
        // Apply custom cache size asynchronously without blocking cold startup on the main thread
        appScope.launch {
            val savedCacheMb = runCatching {
                preferencesManager.getCurrentPreferences().maxCacheSizeMb
            }.getOrDefault(PreferencesManager.DEFAULT_CACHE_MB)
            if (savedCacheMb != PreferencesManager.DEFAULT_CACHE_MB) {
                coilCacheManager.updateMaxCacheSizeMb(savedCacheMb)
            }
        }
    }

    val galleryRepository: GalleryRepository = GalleryRepository(
        database = database,
        driveApi = driveApiService,
        networkMonitor = networkMonitor
    )

    val galleryExtrasRepository: GalleryExtrasRepository = GalleryExtrasRepository(
        database = database,
        localMediaRepository = localMediaRepository
    )

    val albumOrganizationRepository: AlbumOrganizationRepository = AlbumOrganizationRepository(
        database = database,
        localMediaRepository = localMediaRepository,
        galleryRepository = galleryRepository,
        extrasRepository = galleryExtrasRepository,
        preferencesManager = preferencesManager
    )

    val mediaExportManager: MediaExportManager = MediaExportManager(
        context = appContext,
        driveApi = driveApiService
    )

    val offlineMediaManager: OfflineMediaManager = OfflineMediaManager(
        context = appContext,
        database = database,
        driveApi = driveApiService
    )

    val motionPhotoDetector: MotionPhotoDetector = MotionPhotoDetector(appContext)

    val localFileOperationsManager: LocalFileOperationsManager = LocalFileOperationsManager(
        context = appContext,
        driveApi = driveApiService,
        offlineMediaManager = offlineMediaManager
    )

    val videoTrimmer: VideoTrimmer = VideoTrimmer(
        context = appContext,
        driveApi = driveApiService,
        offlineMediaManager = offlineMediaManager
    )

    val appUpdateManager: AppUpdateManager = AppUpdateManager(
        context = appContext,
        preferencesManager = preferencesManager,
        networkMonitor = networkMonitor
    )

    val ocrIndexerManager: OcrIndexerManager = OcrIndexerManager(
        context = appContext,
        database = database,
        driveApi = driveApiService,
        offlineMediaManager = offlineMediaManager
    )

    val collagePdfCreator: CollagePdfCreator = CollagePdfCreator(
        context = appContext,
        driveApi = driveApiService,
        offlineMediaManager = offlineMediaManager
    )

    val syncGalleryUseCase: SyncGalleryUseCase = SyncGalleryUseCase(galleryRepository)
    val manageRootsUseCase: ManageRootsUseCase = ManageRootsUseCase(galleryRepository)
    val exportMediaUseCase: ExportMediaUseCase = ExportMediaUseCase(mediaExportManager)

    val cloudProfileSyncManager: CloudProfileSyncManager = CloudProfileSyncManager(
        database = database,
        localMediaRepository = localMediaRepository,
        preferencesManager = preferencesManager,
        credentialStore = credentialStore,
        tokenManager = tokenManager,
        networkMonitor = networkMonitor,
        okHttpClient = okHttpClient,
        json = json,
        onPinnedRootsRestored = {
            syncGalleryUseCase.syncDeltaOrInitial(forceFullRescan = false, waitIfBusy = true)
        },
        onPreferencesRestored = { restored ->
            coilCacheManager.updateMaxCacheSizeMb(restored.maxCacheSizeMb)
            DriveSyncScheduler.setBackgroundSyncEnabled(appContext, restored.backgroundSyncEnabled)
        }
    )
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer not provided")
}

val LocalGalleryImageLoader = staticCompositionLocalOf<ImageLoader> {
    error("LocalGalleryImageLoader not provided")
}

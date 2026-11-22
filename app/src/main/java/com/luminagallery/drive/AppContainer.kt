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
import com.luminagallery.drive.data.repository.CoilCacheManager
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.data.repository.MediaExportManager
import com.luminagallery.drive.domain.usecase.ExportMediaUseCase
import com.luminagallery.drive.domain.usecase.ManageRootsUseCase
import com.luminagallery.drive.domain.usecase.SyncGalleryUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

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

    private val initialCacheLimitMb: Int = runBlocking {
        preferencesManager.getCurrentPreferences().maxCacheSizeMb
    }

    val coilCacheManager: CoilCacheManager = CoilCacheManager(
        context = appContext,
        okHttpClient = okHttpClient,
        initialMaxCacheMb = initialCacheLimitMb
    )

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

    val syncGalleryUseCase: SyncGalleryUseCase = SyncGalleryUseCase(galleryRepository)
    val manageRootsUseCase: ManageRootsUseCase = ManageRootsUseCase(galleryRepository)
    val exportMediaUseCase: ExportMediaUseCase = ExportMediaUseCase(mediaExportManager)
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer not provided")
}

val LocalGalleryImageLoader = staticCompositionLocalOf<ImageLoader> {
    error("LocalGalleryImageLoader not provided")
}

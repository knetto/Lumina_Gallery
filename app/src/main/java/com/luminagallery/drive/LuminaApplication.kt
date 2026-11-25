package com.luminagallery.drive

import android.app.Application
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.luminagallery.drive.data.repository.sync.LuminaWorkerFactory
import com.luminagallery.drive.data.repository.sync.MediaIndexScheduler
import com.luminagallery.drive.widget.LuminaPhotoWidgetProvider

class LuminaApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runCatching { MediaIndexScheduler.ensureScheduled(this) }
        LuminaPhotoWidgetProvider.refreshOnExclusionsChanged(this, container.galleryExtrasRepository.excludedAlbumIdsFlow)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                LuminaWorkerFactory(
                    repository = container.galleryRepository,
                    localMediaRepository = container.localMediaRepository,
                    extrasRepository = container.galleryExtrasRepository,
                    ocrIndexerManager = container.ocrIndexerManager
                )
            )
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        container.coilCacheManager.currentImageLoader
}

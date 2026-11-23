package com.luminagallery.drive

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.domain.model.UserPreferences
import com.luminagallery.drive.ui.components.AppUpdateDialogHost
import com.luminagallery.drive.ui.navigation.LuminaNavGraph
import com.luminagallery.drive.ui.theme.LuminaTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val externalViewRequestState = mutableStateOf<Pair<Uri, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractViewIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        val appContainer = (application as LuminaApplication).container

        setContent {
            val preferences by appContainer.preferencesManager.preferencesFlow
                .collectAsStateWithLifecycle(initialValue = UserPreferences())
            val activeImageLoader by appContainer.coilCacheManager.imageLoaderFlow
                .collectAsStateWithLifecycle()
            val externalRequest = externalViewRequestState.value

            val systemDark = isSystemInDarkTheme()
            val useDarkBars = when (preferences.themeMode) {
                ThemeMode.AMOLED_BLACK, ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }

            LaunchedEffect(useDarkBars) {
                val style = if (useDarkBars) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            CompositionLocalProvider(
                LocalAppContainer provides appContainer,
                LocalGalleryImageLoader provides activeImageLoader
            ) {
                LuminaTheme(
                    themeMode = preferences.themeMode,
                    dynamicColor = preferences.dynamicColor
                ) {
                    LaunchedEffect(Unit) {
                        // Start Google Account profile sync (settings, Drive links, nested albums)
                        appContainer.cloudProfileSyncManager.startAutoSyncIfSignedIn()
                        // Wait 2 seconds after launch so cold start stays instant, then silently check GitHub
                        delay(2_000L)
                        appContainer.appUpdateManager.checkForUpdate(manual = false)
                    }

                    LuminaNavGraph(
                        externalViewUri = externalRequest?.first?.toString(),
                        externalViewMime = externalRequest?.second,
                        onConsumedExternalView = { externalViewRequestState.value = null }
                    )

                    AppUpdateDialogHost(appUpdateManager = appContainer.appUpdateManager)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractViewIntent(intent)
    }

    private fun extractViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            val mime = intent.type
                ?: runCatching { contentResolver.getType(uri) }.getOrNull()
                ?: "image/*"
            externalViewRequestState.value = uri to mime
        }
    }
}

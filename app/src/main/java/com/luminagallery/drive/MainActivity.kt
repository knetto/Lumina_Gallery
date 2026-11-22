package com.luminagallery.drive

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.domain.model.UserPreferences
import com.luminagallery.drive.ui.navigation.LuminaNavGraph
import com.luminagallery.drive.ui.theme.LuminaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    LuminaNavGraph()
                }
            }
        }
    }
}

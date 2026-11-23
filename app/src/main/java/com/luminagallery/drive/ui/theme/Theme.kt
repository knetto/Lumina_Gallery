package com.luminagallery.drive.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.luminagallery.drive.domain.model.ThemeMode

private val AmoledColorScheme = darkColorScheme(
    primary = LuminaBlue,
    onPrimary = Color(0xFF06213F),
    primaryContainer = LuminaBlueContainer,
    onPrimaryContainer = Color(0xFFD6E7FF),
    secondary = LuminaIndigo,
    onSecondary = Color(0xFF1E1B4B),
    tertiary = LuminaCyan,
    error = LuminaRose,
    background = PitchBlack,
    onBackground = DarkOnSurface,
    surface = PitchBlack,
    onSurface = DarkOnSurface,
    surfaceVariant = OledSurfaceHigh,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = PitchBlack,
    surfaceContainerLow = OledSurfaceLow,
    surfaceContainer = OledSurface,
    surfaceContainerHigh = OledSurfaceHigh,
    surfaceContainerHighest = OledSurfaceHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant
)

private val StandardDarkColorScheme = AmoledColorScheme.copy(
    background = Color(0xFF0E0E12),
    surface = Color(0xFF0E0E12),
    surfaceContainerLowest = Color(0xFF0A0A0D),
    surfaceContainerLow = Color(0xFF141419),
    surfaceContainer = Color(0xFF1B1B22),
    surfaceContainerHigh = Color(0xFF23232C),
    surfaceContainerHighest = Color(0xFF2D2D38)
)

private val LuminaLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = Color(0xFF00325E),
    secondary = Color(0xFF475C7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3F7),
    onSecondaryContainer = Color(0xFF0C1D36),
    tertiary = Color(0xFF006B77),
    onTertiary = Color.White,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFFDBE0E8),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0)
)

@Composable
fun LuminaTheme(
    themeMode: ThemeMode = ThemeMode.AMOLED_BLACK,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.AMOLED_BLACK, ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val useAmoledPitchBlack = themeMode == ThemeMode.AMOLED_BLACK

    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDark) {
                val base = dynamicDarkColorScheme(context)
                if (useAmoledPitchBlack) {
                    base.copy(
                        background = PitchBlack,
                        surface = PitchBlack,
                        surfaceContainerLowest = PitchBlack
                    )
                } else {
                    base
                }
            } else {
                dynamicLightColorScheme(context)
            }
        }
        useAmoledPitchBlack -> AmoledColorScheme
        useDark -> StandardDarkColorScheme
        else -> LuminaLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LuminaTypography,
        content = content
    )
}

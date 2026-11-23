package com.luminagallery.drive.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Translucent glassmorphic pill/bar container tuned for 120Hz OLED rendering.
 * Uses calibrated luminance alpha + hairline border to create frosted depth
 * without causing frame drops during scrolling or gesture physics.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    tonalElevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        tonalElevation = tonalElevation,
        border = BorderStroke(0.75.dp, borderColor),
        content = content
    )
}

fun Modifier.topViewerScrim(): Modifier = background(
    Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Black.copy(alpha = 0.78f),
            0.65f to Color.Black.copy(alpha = 0.40f),
            1.0f to Color.Transparent
        )
    )
)

fun Modifier.bottomViewerScrim(): Modifier = background(
    Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            0.35f to Color.Black.copy(alpha = 0.44f),
            1.0f to Color.Black.copy(alpha = 0.86f)
        )
    )
)

package com.luminagallery.drive.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Minimalist monochrome white Google Drive triangle badge designed to match
 * Samsung One UI's subtle white album cover badges.
 */
@Composable
fun GoogleDriveBadge(
    modifier: Modifier = Modifier,
    badgeSize: Dp = 22.dp
) {
    Box(
        modifier = modifier
            .size(badgeSize)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(4.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(badgeSize - 9.dp)) {
            val w = size.width
            val h = size.height
            val white = Color.White.copy(alpha = 0.94f)
            val softWhite = Color.White.copy(alpha = 0.78f)

            // Left ribbon (with crisp hairline gap at joints for clean monochrome readability)
            val leftRibbon = Path().apply {
                moveTo(w * 0.34f, h * 0.11f)
                lineTo(w * 0.48f, h * 0.36f)
                lineTo(w * 0.18f, h * 0.88f)
                lineTo(w * 0.04f, h * 0.63f)
                close()
            }
            drawPath(leftRibbon, color = white)

            // Top-right ribbon
            val rightRibbon = Path().apply {
                moveTo(w * 0.39f, h * 0.08f)
                lineTo(w * 0.66f, h * 0.08f)
                lineTo(w * 0.96f, h * 0.59f)
                lineTo(w * 0.68f, h * 0.59f)
                close()
            }
            drawPath(rightRibbon, color = white)

            // Bottom ribbon (subtle tonal separation for crisp monochrome depth)
            val bottomRibbon = Path().apply {
                moveTo(w * 0.35f, h * 0.65f)
                lineTo(w * 0.96f, h * 0.65f)
                lineTo(w * 0.82f, h * 0.90f)
                lineTo(w * 0.22f, h * 0.90f)
                close()
            }
            drawPath(bottomRibbon, color = softWhite)
        }
    }
}

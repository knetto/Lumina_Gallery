package com.luminagallery.drive.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val BadgeBackdrop = Color.Black.copy(alpha = 0.50f)
private val BadgeWhite = Color.White.copy(alpha = 0.95f)
private val BadgeSoftWhite = Color.White.copy(alpha = 0.78f)

/**
 * Reusable cached vector glyph for the Google Drive triangle logo.
 * Caches its 3 triangle ribbon [Path]s in `drawWithCache` so scrolling incurs zero Path allocations.
 */
@Composable
fun GoogleDriveGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    primaryColor: Color = BadgeWhite,
    secondaryColor: Color = primaryColor.copy(alpha = 0.78f)
) {
    Box(
        modifier = modifier
            .size(size)
            .drawWithCache {
                val w = this.size.width
                val h = this.size.height
                val leftRibbon = Path().apply {
                    moveTo(w * 0.34f, h * 0.11f)
                    lineTo(w * 0.48f, h * 0.36f)
                    lineTo(w * 0.18f, h * 0.88f)
                    lineTo(w * 0.04f, h * 0.63f)
                    close()
                }
                val rightRibbon = Path().apply {
                    moveTo(w * 0.39f, h * 0.08f)
                    lineTo(w * 0.66f, h * 0.08f)
                    lineTo(w * 0.96f, h * 0.59f)
                    lineTo(w * 0.68f, h * 0.59f)
                    close()
                }
                val bottomRibbon = Path().apply {
                    moveTo(w * 0.35f, h * 0.65f)
                    lineTo(w * 0.96f, h * 0.65f)
                    lineTo(w * 0.82f, h * 0.90f)
                    lineTo(w * 0.22f, h * 0.90f)
                    close()
                }
                onDrawBehind {
                    drawPath(leftRibbon, color = primaryColor)
                    drawPath(rightRibbon, color = primaryColor)
                    drawPath(bottomRibbon, color = secondaryColor)
                }
            }
    )
}

/**
 * Minimalist monochrome white Google Drive triangle badge designed to match
 * Samsung One UI's subtle white album cover & media tile badges.
 */
@Composable
fun GoogleDriveBadge(
    modifier: Modifier = Modifier,
    badgeSize: Dp = 22.dp,
    contentDescriptionText: String? = "Google Drive"
) {
    val inset = (badgeSize * 0.21f).coerceAtLeast(3.dp)
    Box(
        modifier = modifier
            .size(badgeSize)
            .then(
                if (contentDescriptionText != null) {
                    Modifier.semantics { contentDescription = contentDescriptionText }
                } else {
                    Modifier
                }
            )
            .clip(CircleShape)
            .background(BadgeBackdrop)
            .padding(inset),
        contentAlignment = Alignment.Center
    ) {
        GoogleDriveGlyph(
            size = (badgeSize - inset * 2).coerceAtLeast(8.dp),
            primaryColor = BadgeWhite,
            secondaryColor = BadgeSoftWhite
        )
    }
}

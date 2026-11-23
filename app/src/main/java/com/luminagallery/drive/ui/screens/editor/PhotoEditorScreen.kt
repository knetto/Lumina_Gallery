package com.luminagallery.drive.ui.screens.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rotate90DegreesCcw
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.ui.components.OneUiMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.ColorMatrix as AndroidColorMatrix

private enum class EditorTab(val label: String, val icon: ImageVector) {
    ADJUST("Adjust", Icons.Rounded.Tune),
    FILTERS("Filters", Icons.Rounded.AutoAwesome),
    CROP("Crop", Icons.Rounded.Crop)
}

private enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    THREE_FOUR("3:4", 3f / 4f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    NINE_SIXTEEN("9:16", 9f / 16f)
}

private enum class AdjustTool(val label: String, val icon: ImageVector) {
    EXPOSURE("Exposure", Icons.Rounded.Exposure),
    BRIGHTNESS("Brightness", Icons.Rounded.Brightness6),
    CONTRAST("Contrast", Icons.Rounded.Contrast),
    SATURATION("Saturation", Icons.Rounded.Palette),
    WARMTH("Warmth", Icons.Rounded.WbSunny),
    FADE("Fade", Icons.Rounded.Gradient)
}

private enum class PhotoFilterPreset(
    val label: String,
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val fade: Float = 0f
) {
    ORIGINAL("Original"),
    VIVID("Vivid", contrast = 0.22f, saturation = 0.34f, brightness = 0.04f),
    WARM("Warm", warmth = 0.45f, saturation = 0.10f, contrast = 0.08f),
    GOLDEN("Golden", exposure = 0.08f, warmth = 0.62f, saturation = 0.16f, contrast = 0.12f),
    COOL("Cool", warmth = -0.45f, contrast = 0.12f, saturation = 0.06f),
    CINEMA("Cinema", contrast = 0.28f, saturation = -0.18f, warmth = -0.14f, fade = 0.18f),
    MATTE("Matte", contrast = -0.12f, saturation = -0.14f, fade = 0.42f, warmth = 0.12f),
    BW("Mono", saturation = -1.0f, contrast = 0.18f, brightness = 0.04f),
    NOIR("Noir", saturation = -1.0f, contrast = 0.48f, exposure = -0.10f, fade = 0.10f)
}

private enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

/**
 * Flagship Darkroom Photo Editor inspired by Apple Photos, Xiaomi HyperOS Gallery, and Samsung One UI 8:
 *  - Minimalist darkroom header with press-and-hold "Original" before/after compare + Reset + Save copy
 *  - Adjust Studio: Apple Photos circular progress dials (double-tap to reset tool) + tactile tick-mark ruler scrubber
 *  - Filters Studio: Live-preview photographic preset thumbnails + Filter intensity ruler
 *  - Crop & Transform Studio: Dynamic rule-of-thirds grid, rounded L-bracket handles, 90° Rotate, Flip H/V & aspect presets
 */
@Composable
fun PhotoEditorScreen(
    itemId: String,
    sourceUri: String,
    displayName: String,
    isLocal: Boolean,
    onSavedAndClose: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isComparingOriginal by remember { mutableStateOf(false) }

    var activeTab by remember { mutableStateOf(EditorTab.ADJUST) }
    var activeAdjustTool by remember { mutableStateOf(AdjustTool.EXPOSURE) }
    var activeAspect by remember { mutableStateOf(CropAspect.FREE) }
    var activeFilter by remember { mutableStateOf(PhotoFilterPreset.ORIGINAL) }
    var filterStrength by remember { mutableFloatStateOf(1f) }

    // Transform state
    var rotationDeg by remember { mutableIntStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }

    // Normalized crop rect inside the displayed transformed image [0f..1f]
    var cropLeft by remember { mutableFloatStateOf(0f) }
    var cropTop by remember { mutableFloatStateOf(0f) }
    var cropRight by remember { mutableFloatStateOf(1f) }
    var cropBottom by remember { mutableFloatStateOf(1f) }

    // Tone sliders (-1f..1f where 0f is neutral)
    var exposure by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(0f) }
    var warmth by remember { mutableFloatStateOf(0f) }
    var fade by remember { mutableFloatStateOf(0f) }

    val hasAnyEdits = rotationDeg != 0 || flipH || flipV ||
        cropLeft > 0.005f || cropTop > 0.005f || cropRight < 0.995f || cropBottom < 0.995f ||
        activeFilter != PhotoFilterPreset.ORIGINAL ||
        abs(exposure) > 0.01f || abs(brightness) > 0.01f || abs(contrast) > 0.01f ||
        abs(saturation) > 0.01f || abs(warmth) > 0.01f || abs(fade) > 0.01f

    // Load source bitmap once
    LaunchedEffect(itemId, sourceUri, isLocal) {
        withContext(Dispatchers.IO) {
            runCatching {
                val offlinePath = container.offlineMediaManager.getOfflineFilePath(itemId)
                when {
                    !offlinePath.isNullOrBlank() && File(offlinePath).exists() -> {
                        decodeEditorBitmap(File(offlinePath))
                    }
                    isLocal && sourceUri.isNotBlank() -> {
                        decodeEditorBitmap(context, Uri.parse(sourceUri))
                    }
                    else -> {
                        val tmp = File(context.cacheDir, "edit_src_${System.currentTimeMillis()}.jpg")
                        try {
                            container.driveApiService.downloadFile(itemId).use { body ->
                                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                            }
                            decodeEditorBitmap(tmp)
                        } finally {
                            runCatching { tmp.delete() }
                        }
                    }
                }
            }.onSuccess { bmp ->
                if (bmp != null) sourceBitmap = bmp else loadError = "Unable to decode photo"
            }.onFailure { err ->
                loadError = err.localizedMessage ?: "Failed to load photo"
            }
        }
    }

    // Recompute transformed preview bitmap cleanly whenever rotate/flip changes
    val transformedBitmap = remember(sourceBitmap, rotationDeg, flipH, flipV) {
        val base = sourceBitmap ?: return@remember null
        if (rotationDeg == 0 && !flipH && !flipV) return@remember base
        val m = Matrix().apply {
            if (rotationDeg != 0) postRotate(rotationDeg.toFloat())
            postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        }
        Bitmap.createBitmap(base, 0, 0, base.width, base.height, m, true)
    }

    // Small square preview bitmap for instant live Filter preset thumbnails
    val filterPreviewThumb = remember(sourceBitmap) {
        val src = sourceBitmap ?: return@remember null
        val target = 160
        val scale = target.toFloat() / min(src.width, src.height).coerceAtLeast(1).toFloat()
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(src, w, h, true)
    }

    // Combine active Filter preset (scaled by filterStrength) + manual Adjust sliders
    fun buildCombinedColorMatrix(): AndroidColorMatrix {
        val f = activeFilter
        val s = if (f == PhotoFilterPreset.ORIGINAL) 0f else filterStrength.coerceIn(0f, 1f)
        return buildToneColorMatrix(
            exposure = (exposure + f.exposure * s).coerceIn(-1f, 1f),
            brightness = (brightness + f.brightness * s).coerceIn(-1f, 1f),
            contrast = (contrast + f.contrast * s).coerceIn(-1f, 1f),
            saturation = (saturation + f.saturation * s).coerceIn(-1f, 1f),
            warmth = (warmth + f.warmth * s).coerceIn(-1f, 1f),
            fade = (fade + f.fade * s).coerceIn(0f, 1f)
        )
    }

    val composeColorFilter = remember(
        exposure, brightness, contrast, saturation, warmth, fade,
        activeFilter, filterStrength, isComparingOriginal
    ) {
        if (isComparingOriginal) {
            null
        } else {
            val androidCm = buildCombinedColorMatrix()
            ColorFilter.colorMatrix(ColorMatrix(androidCm.array))
        }
    }

    fun applyAspectRatio(aspect: CropAspect) {
        activeAspect = aspect
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val bmp = transformedBitmap ?: return
        val targetRatio = aspect.ratio ?: return
        val imgRatio = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
        val normRatio = targetRatio / imgRatio
        if (normRatio >= 1f) {
            val h = (1f / normRatio).coerceIn(0.15f, 1f)
            cropLeft = 0f
            cropRight = 1f
            cropTop = (1f - h) / 2f
            cropBottom = 1f - cropTop
        } else {
            val w = normRatio.coerceIn(0.15f, 1f)
            cropTop = 0f
            cropBottom = 1f
            cropLeft = (1f - w) / 2f
            cropRight = 1f - cropLeft
        }
    }

    fun resetCropOnly() {
        cropLeft = 0f
        cropTop = 0f
        cropRight = 1f
        cropBottom = 1f
        activeAspect = CropAspect.FREE
    }

    fun resetAll() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        rotationDeg = 0
        flipH = false
        flipV = false
        resetCropOnly()
        activeFilter = PhotoFilterPreset.ORIGINAL
        filterStrength = 1f
        exposure = 0f
        brightness = 0f
        contrast = 0f
        saturation = 0f
        warmth = 0f
        fade = 0f
    }

    fun valueForTool(tool: AdjustTool): Float = when (tool) {
        AdjustTool.EXPOSURE -> exposure
        AdjustTool.BRIGHTNESS -> brightness
        AdjustTool.CONTRAST -> contrast
        AdjustTool.SATURATION -> saturation
        AdjustTool.WARMTH -> warmth
        AdjustTool.FADE -> fade
    }

    fun setValueForTool(tool: AdjustTool, value: Float) {
        val clamped = if (tool == AdjustTool.FADE) value.coerceIn(0f, 1f) else value.coerceIn(-1f, 1f)
        when (tool) {
            AdjustTool.EXPOSURE -> exposure = clamped
            AdjustTool.BRIGHTNESS -> brightness = clamped
            AdjustTool.CONTRAST -> contrast = clamped
            AdjustTool.SATURATION -> saturation = clamped
            AdjustTool.WARMTH -> warmth = clamped
            AdjustTool.FADE -> fade = clamped
        }
    }

    fun saveCopy() {
        val bmp = transformedBitmap ?: return
        if (isSaving) return
        isSaving = true
        val finalMatrix = buildCombinedColorMatrix()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val leftPx = (cropLeft * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
                    val topPx = (cropTop * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)
                    val wPx = ((cropRight - cropLeft) * bmp.width).roundToInt().coerceIn(1, bmp.width - leftPx)
                    val hPx = ((cropBottom - cropTop) * bmp.height).roundToInt().coerceIn(1, bmp.height - topPx)

                    val outBmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(outBmp)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(finalMatrix)
                    }
                    canvas.drawBitmap(
                        bmp,
                        android.graphics.Rect(leftPx, topPx, leftPx + wPx, topPx + hPx),
                        android.graphics.Rect(0, 0, wPx, hPx),
                        paint
                    )
                    try {
                        val stem = displayName.substringBeforeLast('.').take(70).ifBlank { "photo" }
                        val outName = "${stem}_edited_${System.currentTimeMillis() % 100000}.jpg"
                        saveBitmapToMediaStore(context, outBmp, outName)
                        outName
                    } finally {
                        outBmp.recycle()
                    }
                }
            }
            isSaving = false
            result.onSuccess { savedName ->
                container.localMediaRepository.notifyPermissionChanged()
                onSavedAndClose("Saved edited copy: $savedName")
            }.onFailure { err ->
                snackbarHostState.showSnackbar(err.localizedMessage ?: "Failed to save edited photo")
            }
        }
    }

    val accentColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Minimalist Apple / Xiaomi Studio Darkroom Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Close glass button
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(enabled = !isSaving, onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cancel",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Center: Hold-to-Compare "Original" + Reset pills (shown cleanly only when edited!)
                AnimatedVisibility(
                    visible = hasAnyEdits,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(150))
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isComparingOriginal) accentColor else Color.White.copy(alpha = 0.11f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            isComparingOriginal = true
                                            tryAwaitRelease()
                                            isComparingOriginal = false
                                        }
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Compare,
                                    contentDescription = "Compare original",
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Original",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.11f))
                                .clickable(enabled = !isSaving, onClick = ::resetAll),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Reset all",
                                tint = Color.White.copy(alpha = 0.88f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Right: Sleek Save Copy pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (hasAnyEdits) accentColor else Color.White.copy(alpha = 0.14f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = !isSaving && transformedBitmap != null, onClick = ::saveCopy)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text(
                                text = "Save copy",
                                color = if (hasAnyEdits) Color.White else Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 2. Full-Bleed Darkroom Canvas
            var viewportSize by remember { mutableStateOf(IntSize.Zero) }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .onSizeChanged { viewportSize = it },
                contentAlignment = Alignment.Center
            ) {
                when {
                    loadError != null -> {
                        Text(loadError.orEmpty(), color = Color.White.copy(alpha = 0.75f))
                    }
                    transformedBitmap == null -> {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp)
                    }
                    else -> {
                        val bmp = transformedBitmap
                        val imgRect = remember(viewportSize, bmp.width, bmp.height) {
                            computeFittedRect(viewportSize, bmp.width, bmp.height)
                        }

                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = displayName,
                            contentScale = ContentScale.Fit,
                            colorFilter = composeColorFilter,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Floating "ORIGINAL" pill badge when user holds Compare
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isComparingOriginal,
                            enter = fadeIn(tween(100)),
                            exit = fadeOut(tween(150)),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = Color.Black.copy(alpha = 0.68f),
                                modifier = Modifier.border(0.8.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(50))
                            ) {
                                Text(
                                    text = "ORIGINAL",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }

                        // Apple / Samsung Crop Overlay with Dynamic Rule-of-Thirds
                        if (activeTab == EditorTab.CROP && imgRect.width > 10f && imgRect.height > 10f) {
                            var activeHandle by remember { mutableStateOf<CropHandle?>(null) }
                            val gridAlpha by animateFloatAsState(
                                targetValue = if (activeHandle != null) 0.52f else 0.18f,
                                animationSpec = tween(180),
                                label = "cropGridAlpha"
                            )

                            ComposeCanvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(imgRect, activeAspect) {
                                        detectDragGestures(
                                            onDragStart = { pos ->
                                                val l = imgRect.left + cropLeft * imgRect.width
                                                val t = imgRect.top + cropTop * imgRect.height
                                                val r = imgRect.left + cropRight * imgRect.width
                                                val b = imgRect.top + cropBottom * imgRect.height
                                                val hit = 64f
                                                activeHandle = when {
                                                    (pos - Offset(l, t)).getDistance() < hit -> CropHandle.TOP_LEFT
                                                    (pos - Offset(r, t)).getDistance() < hit -> CropHandle.TOP_RIGHT
                                                    (pos - Offset(l, b)).getDistance() < hit -> CropHandle.BOTTOM_LEFT
                                                    (pos - Offset(r, b)).getDistance() < hit -> CropHandle.BOTTOM_RIGHT
                                                    pos.x in l..r && pos.y in t..b -> CropHandle.MOVE
                                                    else -> null
                                                }
                                            },
                                            onDragEnd = { activeHandle = null },
                                            onDragCancel = { activeHandle = null },
                                            onDrag = { change, drag ->
                                                change.consume()
                                                val handle = activeHandle ?: return@detectDragGestures
                                                val dx = drag.x / imgRect.width
                                                val dy = drag.y / imgRect.height
                                                val minSize = 0.14f
                                                when (handle) {
                                                    CropHandle.TOP_LEFT -> {
                                                        cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - minSize)
                                                        cropTop = (cropTop + dy).coerceIn(0f, cropBottom - minSize)
                                                    }
                                                    CropHandle.TOP_RIGHT -> {
                                                        cropRight = (cropRight + dx).coerceIn(cropLeft + minSize, 1f)
                                                        cropTop = (cropTop + dy).coerceIn(0f, cropBottom - minSize)
                                                    }
                                                    CropHandle.BOTTOM_LEFT -> {
                                                        cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - minSize)
                                                        cropBottom = (cropBottom + dy).coerceIn(cropTop + minSize, 1f)
                                                    }
                                                    CropHandle.BOTTOM_RIGHT -> {
                                                        cropRight = (cropRight + dx).coerceIn(cropLeft + minSize, 1f)
                                                        cropBottom = (cropBottom + dy).coerceIn(cropTop + minSize, 1f)
                                                    }
                                                    CropHandle.MOVE -> {
                                                        val w = cropRight - cropLeft
                                                        val h = cropBottom - cropTop
                                                        val nl = (cropLeft + dx).coerceIn(0f, 1f - w)
                                                        val nt = (cropTop + dy).coerceIn(0f, 1f - h)
                                                        cropLeft = nl
                                                        cropRight = nl + w
                                                        cropTop = nt
                                                        cropBottom = nt + h
                                                    }
                                                }
                                            }
                                        )
                                    }
                            ) {
                                val l = imgRect.left + cropLeft * imgRect.width
                                val t = imgRect.top + cropTop * imgRect.height
                                val r = imgRect.left + cropRight * imgRect.width
                                val b = imgRect.top + cropBottom * imgRect.height
                                val scrim = Color.Black.copy(alpha = 0.62f)

                                drawRect(scrim, Offset(imgRect.left, imgRect.top), Size(imgRect.width, (t - imgRect.top).coerceAtLeast(0f)))
                                drawRect(scrim, Offset(imgRect.left, b), Size(imgRect.width, (imgRect.bottom - b).coerceAtLeast(0f)))
                                drawRect(scrim, Offset(imgRect.left, t), Size((l - imgRect.left).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
                                drawRect(scrim, Offset(r, t), Size((imgRect.right - r).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))

                                val w = r - l
                                val h = b - t
                                drawRect(Color.White.copy(alpha = 0.9f), Offset(l, t), Size(w, h), style = Stroke(width = 1.4.dp.toPx()))
                                val thirdColor = Color.White.copy(alpha = gridAlpha)
                                val thirdStroke = 0.75.dp.toPx()
                                drawLine(thirdColor, Offset(l + w / 3f, t), Offset(l + w / 3f, b), thirdStroke)
                                drawLine(thirdColor, Offset(l + 2f * w / 3f, t), Offset(l + 2f * w / 3f, b), thirdStroke)
                                drawLine(thirdColor, Offset(l, t + h / 3f), Offset(r, t + h / 3f), thirdStroke)
                                drawLine(thirdColor, Offset(l, t + 2f * h / 3f), Offset(r, t + 2f * h / 3f), thirdStroke)

                                // Sleek rounded L-bracket handles
                                val cornerLen = 22.dp.toPx()
                                val cornerStroke = 3.5.dp.toPx()
                                drawLine(Color.White, Offset(l, t), Offset(l + cornerLen, t), cornerStroke, StrokeCap.Round)
                                drawLine(Color.White, Offset(l, t), Offset(l, t + cornerLen), cornerStroke, StrokeCap.Round)
                                drawLine(Color.White, Offset(r, t), Offset(r - cornerLen, t), cornerStroke, StrokeCap.Round)
                                drawLine(Color.White, Offset(r, t), Offset(r, t + cornerLen), cornerStroke, StrokeCap.Round)
                                drawLine(Color.White, Offset(l, b), Offset(l + cornerLen, b), cornerStroke, StrokeCap.Round)
                                drawLine(Color.White, Offset(l, b), Offset(l, b - cornerLen), cornerStroke, StrokeCap.Round)
                                drawLine(Color.White, Offset(r, b), Offset(r - cornerLen, b), cornerStroke, StrokeCap.Round)
                                drawLine(Color.White, Offset(r, b), Offset(r, b - cornerLen), cornerStroke, StrokeCap.Round)
                            }
                        }
                    }
                }
            }

            // 3. Flagship Studio Control Deck (Adjust Dials + Tick Ruler | Live Filters | Crop & Transform)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0D))
                    .navigationBarsPadding()
                    .padding(top = 10.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AnimatedContent(
                    targetState = activeTab,
                    transitionSpec = {
                        (slideInVertically(tween(240, easing = OneUiMotion.FluidEasing)) { 24 } +
                            fadeIn(tween(180, easing = OneUiMotion.FluidEasing)))
                            .togetherWith(
                                slideOutVertically(tween(160, easing = OneUiMotion.FluidEasing)) { 16 } +
                                    fadeOut(tween(120))
                            )
                    },
                    label = "editorStudioPanel"
                ) { tab ->
                    when (tab) {
                        EditorTab.ADJUST -> {
                            val currentVal = valueForTool(activeAdjustTool)
                            val isUnipolar = activeAdjustTool == AdjustTool.FADE
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Apple / Xiaomi Tactile Tick-Mark Ruler Scrubber
                                ProRulerSlider(
                                    label = activeAdjustTool.label,
                                    value = currentVal,
                                    minValue = if (isUnipolar) 0f else -1f,
                                    maxValue = 1f,
                                    accentColor = accentColor,
                                    onValueChange = { v -> setValueForTool(activeAdjustTool, v) },
                                    onDoubleTapReset = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        setValueForTool(activeAdjustTool, 0f)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                )

                                // Apple Photos Circular Dials with Progress Rings
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(AdjustTool.entries) { tool ->
                                        val toolVal = valueForTool(tool)
                                        val selected = activeAdjustTool == tool
                                        AppleStudioDial(
                                            tool = tool,
                                            value = toolVal,
                                            isSelected = selected,
                                            accentColor = accentColor,
                                            onSelect = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                activeAdjustTool = tool
                                            },
                                            onDoubleTapReset = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                setValueForTool(tool, 0f)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        EditorTab.FILTERS -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (activeFilter != PhotoFilterPreset.ORIGINAL) {
                                    ProRulerSlider(
                                        label = "${activeFilter.label} strength",
                                        value = filterStrength,
                                        minValue = 0f,
                                        maxValue = 1f,
                                        accentColor = accentColor,
                                        onValueChange = { filterStrength = it },
                                        onDoubleTapReset = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            filterStrength = 1f
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Choose a studio preset",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White.copy(alpha = 0.56f)
                                        )
                                    }
                                }

                                // Live-preview Filter Strip (Apple + Xiaomi + Samsung style)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(PhotoFilterPreset.entries) { preset ->
                                        val selected = activeFilter == preset
                                        val previewFilter = remember(preset) {
                                            if (preset == PhotoFilterPreset.ORIGINAL) null
                                            else {
                                                val cm = buildToneColorMatrix(
                                                    exposure = preset.exposure,
                                                    brightness = preset.brightness,
                                                    contrast = preset.contrast,
                                                    saturation = preset.saturation,
                                                    warmth = preset.warmth,
                                                    fade = preset.fade
                                                )
                                                ColorFilter.colorMatrix(ColorMatrix(cm.array))
                                            }
                                        }
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier
                                                .width(66.dp)
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    activeFilter = preset
                                                    if (preset != PhotoFilterPreset.ORIGINAL && filterStrength < 0.05f) {
                                                        filterStrength = 1f
                                                    }
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(62.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(Color(0xFF1A1A20))
                                                    .then(
                                                        if (selected) {
                                                            Modifier.border(
                                                                2.dp,
                                                                accentColor,
                                                                RoundedCornerShape(14.dp)
                                                            )
                                                        } else {
                                                            Modifier.border(
                                                                0.6.dp,
                                                                Color.White.copy(alpha = 0.14f),
                                                                RoundedCornerShape(14.dp)
                                                            )
                                                        }
                                                    )
                                            ) {
                                                filterPreviewThumb?.let { thumb ->
                                                    Image(
                                                        bitmap = thumb.asImageBitmap(),
                                                        contentDescription = preset.label,
                                                        contentScale = ContentScale.Crop,
                                                        colorFilter = previewFilter,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                            }
                                            Text(
                                                text = preset.label,
                                                fontSize = 11.sp,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (selected) accentColor else Color.White.copy(alpha = 0.72f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        EditorTab.CROP -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Clean Circular Transform Buttons
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    StudioTransformButton(
                                        icon = Icons.Rounded.Rotate90DegreesCcw,
                                        label = "Rotate L",
                                        active = false
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        rotationDeg = (rotationDeg + 270) % 360
                                    }
                                    StudioTransformButton(
                                        icon = Icons.Rounded.Rotate90DegreesCw,
                                        label = "Rotate R",
                                        active = false
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        rotationDeg = (rotationDeg + 90) % 360
                                    }
                                    StudioTransformButton(
                                        icon = Icons.Rounded.Flip,
                                        label = "Flip H",
                                        active = flipH
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        flipH = !flipH
                                    }
                                    StudioTransformButton(
                                        icon = Icons.Rounded.Flip,
                                        label = "Flip V",
                                        active = flipV
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        flipV = !flipV
                                    }
                                }

                                // Sleek Aspect Ratio Pills
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 18.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    items(CropAspect.entries) { aspect ->
                                        val selected = activeAspect == aspect
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (selected) accentColor else Color(0xFF1C1C22),
                                            modifier = Modifier
                                                .border(
                                                    width = 0.6.dp,
                                                    color = if (selected) Color.Transparent else Color.White.copy(alpha = 0.10f),
                                                    shape = RoundedCornerShape(50)
                                                )
                                                .clip(RoundedCornerShape(50))
                                                .clickable { applyAspectRatio(aspect) }
                                        ) {
                                            Text(
                                                text = aspect.label,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                modifier = Modifier.padding(horizontal = 15.dp, vertical = 7.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Clean Floating Studio Mode Switcher Pill (Adjust | Filters | Crop)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF16161C),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .border(0.7.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        EditorTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (selected) Color(0xFF282832) else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        if (activeTab != tab) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            activeTab = tab
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                        tint = if (selected) accentColor else Color.White.copy(alpha = 0.62f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = tab.label,
                                        color = if (selected) Color.White else Color.White.copy(alpha = 0.62f),
                                        fontSize = 12.5.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 140.dp)
        )
    }
}

/**
 * Apple Photos / Xiaomi HyperOS Studio Circular Dial:
 *  - Shows the tool icon when neutral or unselected, with a live circular progress arc around the rim
 *    whenever the adjustment is non-zero.
 *  - Double-tap to reset that specific adjustment to 0 cleanly.
 */
@Composable
private fun AppleStudioDial(
    tool: AdjustTool,
    value: Float,
    isSelected: Boolean,
    accentColor: Color,
    onSelect: () -> Unit,
    onDoubleTapReset: () -> Unit
) {
    val isModified = abs(value) > 0.01f
    val displayInt = (value * 100f).roundToInt()
    val ringColor = if (value >= 0f) accentColor else Color(0xFFFFB74D)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .width(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(tool) {
                detectTapGestures(
                    onTap = { onSelect() },
                    onDoubleTap = { onDoubleTapReset() }
                )
            }
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 2.4.dp.toPx()
                val inset = strokeWidth / 2f
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)

                // Dark studio circle fill
                drawCircle(
                    color = if (isSelected) Color(0xFF24242E) else Color(0xFF16161C),
                    radius = (size.minDimension / 2f) - strokeWidth
                )

                // Track ring
                drawCircle(
                    color = if (isSelected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f),
                    radius = (size.minDimension / 2f) - inset,
                    style = Stroke(width = strokeWidth)
                )

                // Live value arc starting from 12 o'clock (-90°)
                if (isModified) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = value.coerceIn(-1f, 1f) * 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            if (isSelected && isModified) {
                Text(
                    text = if (displayInt > 0) "+$displayInt" else "$displayInt",
                    style = TextStyle(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        color = ringColor
                    )
                )
            } else {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.label,
                    tint = when {
                        isSelected -> Color.White
                        isModified -> ringColor
                        else -> Color.White.copy(alpha = 0.72f)
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Text(
            text = tool.label,
            fontSize = 10.5.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                isSelected -> Color.White
                isModified -> ringColor
                else -> Color.White.copy(alpha = 0.62f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Apple Photos / Xiaomi HyperOS Tactile Tick-Mark Ruler Scrubber:
 *  - Precision tick bar with bold zero notch, live accent fill, numeric badge readout,
 *    smooth horizontal scrubbing, tap-to-seek, double-tap to reset, and zero-crossing haptic tick!
 */
@Composable
private fun ProRulerSlider(
    label: String,
    value: Float,
    minValue: Float,
    maxValue: Float,
    accentColor: Color,
    onValueChange: (Float) -> Unit,
    onDoubleTapReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestReset by rememberUpdatedState(onDoubleTapReset)

    val pct = (value * 100f).roundToInt()
    val valueText = when {
        minValue >= 0f -> "$pct%"
        pct > 0 -> "+$pct"
        else -> "$pct"
    }
    val activeTint = if (value >= 0f) accentColor else Color(0xFFFFB74D)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = Color.White.copy(alpha = 0.85f)
            )
            Text(
                text = valueText,
                style = TextStyle(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                    color = if (abs(value) > 0.01f) activeTint else Color.White.copy(alpha = 0.55f)
                )
            )
        }

        ComposeCanvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(minValue, maxValue) {
                    detectTapGestures(
                        onTap = { tap ->
                            val frac = (tap.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val mapped = minValue + frac * (maxValue - minValue)
                            val snapped = if (minValue < 0f && abs(mapped) < 0.03f) 0f else mapped
                            latestOnChange(snapped)
                        },
                        onDoubleTap = { latestReset() }
                    )
                }
                .pointerInput(minValue, maxValue) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        val span = maxValue - minValue
                        val delta = (dragAmount / size.width.coerceAtLeast(1)) * span
                        val prev = latestValue
                        val nextRaw = (prev + delta).coerceIn(minValue, maxValue)
                        val crossedZero = minValue < 0f && ((prev < 0f && nextRaw >= 0f) || (prev > 0f && nextRaw <= 0f))
                        if (crossedZero) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        val snapped = if (minValue < 0f && abs(nextRaw) < 0.018f && abs(dragAmount) < 4f) 0f else nextRaw
                        latestOnChange(snapped)
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val midY = h / 2f
            val span = (maxValue - minValue).coerceAtLeast(0.001f)
            val normVal = ((value - minValue) / span).coerceIn(0f, 1f)
            val zeroNorm = ((0f - minValue) / span).coerceIn(0f, 1f)
            val valX = normVal * w
            val zeroX = zeroNorm * w

            // Subtle ticks across full width (41 ticks)
            val tickCount = 40
            for (i in 0..tickCount) {
                val frac = i.toFloat() / tickCount.toFloat()
                val x = frac * w
                val isMajor = i % 5 == 0
                val isCenter = minValue < 0f && i == tickCount / 2
                val tickH = when {
                    isCenter -> 16.dp.toPx()
                    isMajor -> 11.dp.toPx()
                    else -> 6.dp.toPx()
                }
                val inActiveSpan = (x >= min(zeroX, valX) && x <= maxOf(zeroX, valX))
                drawLine(
                    color = when {
                        isCenter -> Color.White.copy(alpha = 0.72f)
                        inActiveSpan -> activeTint.copy(alpha = 0.92f)
                        isMajor -> Color.White.copy(alpha = 0.34f)
                        else -> Color.White.copy(alpha = 0.16f)
                    },
                    start = Offset(x, midY - tickH / 2f),
                    end = Offset(x, midY + tickH / 2f),
                    strokeWidth = if (isCenter) 2.dp.toPx() else 1.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Crisp glowing vertical indicator needle at current position
            val needleW = 3.2.dp.toPx()
            val needleH = 24.dp.toPx()
            drawRoundRect(
                color = Color.White,
                topLeft = Offset((valX - needleW / 2f).coerceIn(0f, w - needleW), midY - needleH / 2f),
                size = Size(needleW, needleH),
                cornerRadius = CornerRadius(needleW / 2f, needleW / 2f)
            )
        }
    }
}

@Composable
private fun StudioTransformButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    else Color(0xFF1A1A22)
                )
                .border(
                    width = 0.8.dp,
                    color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = label,
            color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.72f),
            fontSize = 11.sp
        )
    }
}

private fun computeFittedRect(container: IntSize, imgW: Int, imgH: Int): Rect {
    if (container.width <= 0 || container.height <= 0 || imgW <= 0 || imgH <= 0) return Rect.Zero
    val scale = min(container.width.toFloat() / imgW, container.height.toFloat() / imgH)
    val w = imgW * scale
    val h = imgH * scale
    val left = (container.width - w) / 2f
    val top = (container.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

private fun buildToneColorMatrix(
    exposure: Float,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    warmth: Float,
    fade: Float
): AndroidColorMatrix {
    val result = AndroidColorMatrix()

    // 1. Saturation (0..2)
    val sat = AndroidColorMatrix().apply { setSaturation((1f + saturation).coerceIn(0f, 2f)) }
    result.postConcat(sat)

    // 2. Exposure multiplier (multiplicative gain, photographic feel)
    if (abs(exposure) > 0.005f) {
        val gain = (1f + exposure * 0.55f).coerceIn(0.45f, 1.75f)
        result.postConcat(
            AndroidColorMatrix(
                floatArrayOf(
                    gain, 0f, 0f, 0f, 0f,
                    0f, gain, 0f, 0f, 0f,
                    0f, 0f, gain, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    // 3. Contrast & Brightness
    val c = (1f + contrast * 0.7f).coerceIn(0.3f, 2.0f)
    val translate = (-0.5f * c + 0.5f) * 255f + brightness * 85f
    result.postConcat(
        AndroidColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    // 4. Warmth (white-balance shift)
    if (abs(warmth) > 0.005f) {
        result.postConcat(
            AndroidColorMatrix(
                floatArrayOf(
                    1f + warmth * 0.16f, 0f, 0f, 0f, warmth * 12f,
                    0f, 1f + warmth * 0.04f, 0f, 0f, 0f,
                    0f, 0f, 1f - warmth * 0.16f, 0f, -warmth * 12f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    // 5. Film Fade (lifts black point softly like Apple / VSCO Matte)
    if (fade > 0.005f) {
        val f = fade.coerceIn(0f, 1f)
        val scale = 1f - f * 0.22f
        val lift = f * 42f
        result.postConcat(
            AndroidColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, lift,
                    0f, scale, 0f, 0f, lift,
                    0f, 0f, scale, 0f, lift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    return result
}

private fun decodeEditorBitmap(context: Context, uri: Uri): Bitmap? {
    val maxPx = 2800
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val maxDim = maxOf(info.size.width, info.size.height)
            if (maxDim > maxPx) {
                val s = maxPx.toFloat() / maxDim
                decoder.setTargetSize(
                    (info.size.width * s).toInt().coerceAtLeast(1),
                    (info.size.height * s).toInt().coerceAtLeast(1)
                )
            }
        }
    } else {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }
}

private fun decodeEditorBitmap(file: File): Bitmap? {
    val maxPx = 2800
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val src = ImageDecoder.createSource(file)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val maxDim = maxOf(info.size.width, info.size.height)
            if (maxDim > maxPx) {
                val s = maxPx.toFloat() / maxDim
                decoder.setTargetSize(
                    (info.size.width * s).toInt().coerceAtLeast(1),
                    (info.size.height * s).toInt().coerceAtLeast(1)
                )
            }
        }
    } else {
        BitmapFactory.decodeFile(file.absolutePath)
    }
}

private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap, displayName: String) {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LuminaGallery")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: throw IOException("Failed to create MediaStore entry")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
            } ?: throw IOException("Failed to open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LuminaGallery")
        if (!dir.exists()) dir.mkdirs()
        File(dir, displayName).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
        }
    }
}

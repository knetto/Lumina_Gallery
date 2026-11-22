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
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rotate90DegreesCcw
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luminagallery.drive.LocalAppContainer
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
    CROP("Crop & Rotate", Icons.Rounded.Crop),
    ADJUST("Adjust", Icons.Rounded.Tune)
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
    BRIGHTNESS("Brightness", Icons.Rounded.Brightness6),
    CONTRAST("Contrast", Icons.Rounded.Contrast),
    SATURATION("Saturation", Icons.Rounded.Palette),
    WARMTH("Warmth", Icons.Rounded.WbSunny)
}

private enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

/**
 * Samsung One UI-style non-destructive Photo Editor:
 *  - Interactive Crop with aspect-ratio presets + corner drag handles
 *  - Rotate 90° CCW/CW & Flip Horizontal/Vertical
 *  - Real-time GPU ColorMatrix sliders: Brightness, Contrast, Saturation, Warmth
 *  - Non-destructive "Save copy" into Pictures/LuminaGallery for both phone & Google Drive photos
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
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    var activeTab by remember { mutableStateOf(EditorTab.CROP) }
    var activeAdjustTool by remember { mutableStateOf(AdjustTool.BRIGHTNESS) }
    var activeAspect by remember { mutableStateOf(CropAspect.FREE) }

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
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(0f) }
    var warmth by remember { mutableFloatStateOf(0f) }

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

    val composeColorFilter = remember(brightness, contrast, saturation, warmth) {
        val androidCm = buildToneColorMatrix(brightness, contrast, saturation, warmth)
        ColorFilter.colorMatrix(ColorMatrix(androidCm.array))
    }

    fun applyAspectRatio(aspect: CropAspect) {
        activeAspect = aspect
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

    fun resetAll() {
        rotationDeg = 0
        flipH = false
        flipV = false
        cropLeft = 0f
        cropTop = 0f
        cropRight = 1f
        cropBottom = 1f
        activeAspect = CropAspect.FREE
        brightness = 0f
        contrast = 0f
        saturation = 0f
        warmth = 0f
    }

    fun saveCopy() {
        val bmp = transformedBitmap ?: return
        if (isSaving) return
        isSaving = true
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
                        colorFilter = ColorMatrixColorFilter(
                            buildToneColorMatrix(brightness, contrast, saturation, warmth)
                        )
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onDismiss, enabled = !isSaving) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }

                Text(
                    text = "Edit photo",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::resetAll, enabled = !isSaving) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Reset",
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !isSaving && transformedBitmap != null, onClick = ::saveCopy)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }

            // Interactive Photo + Crop Viewport
            var viewportSize by remember { mutableStateOf(IntSize.Zero) }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onSizeChanged { viewportSize = it },
                contentAlignment = Alignment.Center
            ) {
                when {
                    loadError != null -> {
                        Text(loadError!!, color = Color.White.copy(alpha = 0.75f))
                    }
                    transformedBitmap == null -> {
                        CircularProgressIndicator(color = Color.White)
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

                        if (activeTab == EditorTab.CROP && imgRect.width > 10f && imgRect.height > 10f) {
                            var activeHandle by remember { mutableStateOf<CropHandle?>(null) }

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
                                                val hit = 56f
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
                                val scrim = Color.Black.copy(alpha = 0.56f)

                                // Dim outer cropped-off bands cleanly
                                drawRect(scrim, Offset(imgRect.left, imgRect.top), Size(imgRect.width, (t - imgRect.top).coerceAtLeast(0f)))
                                drawRect(scrim, Offset(imgRect.left, b), Size(imgRect.width, (imgRect.bottom - b).coerceAtLeast(0f)))
                                drawRect(scrim, Offset(imgRect.left, t), Size((l - imgRect.left).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
                                drawRect(scrim, Offset(r, t), Size((imgRect.right - r).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))

                                // Rule-of-thirds grid + crisp white frame
                                val w = r - l
                                val h = b - t
                                drawRect(Color.White, Offset(l, t), Size(w, h), style = Stroke(width = 2.dp.toPx()))
                                val thirdColor = Color.White.copy(alpha = 0.32f)
                                val thirdStroke = 0.8.dp.toPx()
                                drawLine(thirdColor, Offset(l + w / 3f, t), Offset(l + w / 3f, b), thirdStroke)
                                drawLine(thirdColor, Offset(l + 2f * w / 3f, t), Offset(l + 2f * w / 3f, b), thirdStroke)
                                drawLine(thirdColor, Offset(l, t + h / 3f), Offset(r, t + h / 3f), thirdStroke)
                                drawLine(thirdColor, Offset(l, t + 2f * h / 3f), Offset(r, t + 2f * h / 3f), thirdStroke)

                                // Bold L-shaped Samsung corner handles
                                val cornerLen = 20.dp.toPx()
                                val cornerStroke = 4.dp.toPx()
                                drawLine(Color.White, Offset(l, t), Offset(l + cornerLen, t), cornerStroke)
                                drawLine(Color.White, Offset(l, t), Offset(l, t + cornerLen), cornerStroke)
                                drawLine(Color.White, Offset(r, t), Offset(r - cornerLen, t), cornerStroke)
                                drawLine(Color.White, Offset(r, t), Offset(r, t + cornerLen), cornerStroke)
                                drawLine(Color.White, Offset(l, b), Offset(l + cornerLen, b), cornerStroke)
                                drawLine(Color.White, Offset(l, b), Offset(l, b - cornerLen), cornerStroke)
                                drawLine(Color.White, Offset(r, b), Offset(r - cornerLen, b), cornerStroke)
                                drawLine(Color.White, Offset(r, b), Offset(r, b - cornerLen), cornerStroke)
                            }
                        }
                    }
                }
            }

            // Bottom One UI Editing Controls Dock
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101013))
                    .navigationBarsPadding()
                    .padding(top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (activeTab == EditorTab.CROP) {
                    // Rotate & Flip quick row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        EditorIconButton(Icons.Rounded.Rotate90DegreesCcw, "Rotate L") {
                            rotationDeg = (rotationDeg + 270) % 360
                        }
                        EditorIconButton(Icons.Rounded.Rotate90DegreesCw, "Rotate R") {
                            rotationDeg = (rotationDeg + 90) % 360
                        }
                        EditorIconButton(Icons.Rounded.Flip, "Flip H") { flipH = !flipH }
                        EditorIconButton(Icons.Rounded.Flip, "Flip V") { flipV = !flipV }
                    }

                    // Aspect ratio chips
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(CropAspect.entries) { aspect ->
                            val selected = activeAspect == aspect
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF23232A),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { applyAspectRatio(aspect) }
                            ) {
                                Text(
                                    text = aspect.label,
                                    color = Color.White,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                } else {
                    // Active tone slider
                    val currentValue = when (activeAdjustTool) {
                        AdjustTool.BRIGHTNESS -> brightness
                        AdjustTool.CONTRAST -> contrast
                        AdjustTool.SATURATION -> saturation
                        AdjustTool.WARMTH -> warmth
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = activeAdjustTool.label,
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.width(76.dp)
                        )
                        Slider(
                            value = currentValue,
                            onValueChange = { v ->
                                when (activeAdjustTool) {
                                    AdjustTool.BRIGHTNESS -> brightness = v
                                    AdjustTool.CONTRAST -> contrast = v
                                    AdjustTool.SATURATION -> saturation = v
                                    AdjustTool.WARMTH -> warmth = v
                                }
                            },
                            valueRange = -1f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color(0xFF34343D)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(currentValue * 100).roundToInt()}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(34.dp)
                        )
                    }

                    // Adjust tool selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AdjustTool.entries.forEach { tool ->
                            val selected = activeAdjustTool == tool
                            val isModified = when (tool) {
                                AdjustTool.BRIGHTNESS -> abs(brightness) > 0.01f
                                AdjustTool.CONTRAST -> abs(contrast) > 0.01f
                                AdjustTool.SATURATION -> abs(saturation) > 0.01f
                                AdjustTool.WARMTH -> abs(warmth) > 0.01f
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { activeAdjustTool = tool }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                                            else Color(0xFF1E1E24)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = tool.icon,
                                        contentDescription = tool.label,
                                        tint = if (selected || isModified) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = tool.label,
                                    fontSize = 11.sp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.72f)
                                )
                            }
                        }
                    }
                }

                // Bottom Mode Tabs (Crop & Rotate | Adjust)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EditorTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (selected) Color(0xFF282832) else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .clickable { activeTab = tab }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.65f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = tab.label,
                                    color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
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
                .padding(bottom = 120.dp)
        )
    }
}

@Composable
private fun EditorIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp)
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
    brightness: Float,
    contrast: Float,
    saturation: Float,
    warmth: Float
): AndroidColorMatrix {
    val result = AndroidColorMatrix()

    // Saturation (0..2)
    val sat = AndroidColorMatrix().apply { setSaturation((1f + saturation).coerceIn(0f, 2f)) }
    result.postConcat(sat)

    // Contrast & Brightness
    val c = (1f + contrast * 0.7f).coerceIn(0.3f, 2.0f)
    val translate = (-0.5f * c + 0.5f) * 255f + brightness * 90f
    val bc = AndroidColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, translate,
            0f, c, 0f, 0f, translate,
            0f, 0f, c, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )
    result.postConcat(bc)

    // Warmth (shifts red/green vs blue subtly)
    if (abs(warmth) > 0.005f) {
        val w = AndroidColorMatrix(
            floatArrayOf(
                1f + warmth * 0.16f, 0f, 0f, 0f, warmth * 12f,
                0f, 1f + warmth * 0.04f, 0f, 0f, 0f,
                0f, 0f, 1f - warmth * 0.16f, 0f, -warmth * 12f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(w)
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
                decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
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
                decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
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

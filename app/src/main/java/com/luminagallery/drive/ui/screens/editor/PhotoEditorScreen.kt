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
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rotate90DegreesCcw
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.OneUiMotion
import com.luminagallery.drive.ui.components.SecureWindowEffect
import com.luminagallery.drive.ui.components.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.ColorMatrix as AndroidColorMatrix

private enum class EditorTab(val label: String, val icon: ImageVector) {
    ADJUST("Adjust", Icons.Rounded.Tune),
    FILTERS("Filters", Icons.Rounded.AutoAwesome),
    CROP("Crop", Icons.Rounded.Crop),
    MARKUP("Markup", Icons.Rounded.Draw)
}

private enum class MarkupTool(val label: String, val icon: ImageVector) {
    PEN("Pen", Icons.Rounded.Draw),
    HIGHLIGHTER("Marker", Icons.Rounded.Brush),
    MOSAIC("Mosaic", Icons.Rounded.BlurOn),
    TEXT("Text", Icons.Rounded.TextFields)
}

private data class MarkupStroke(
    val tool: MarkupTool,
    val colorArgb: Int,
    val widthNorm: Float,
    val points: List<Offset>
)

private data class MarkupTextOverlay(
    val id: Long,
    val text: String,
    val colorArgb: Int,
    val normX: Float,
    val normY: Float
)

private data class EditorSnapshot(
    val rotationDeg: Int = 0,
    val straightenDeg: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val activeAspect: CropAspect = CropAspect.FREE,
    val activeFilter: PhotoFilterPreset = PhotoFilterPreset.ORIGINAL,
    val filterStrength: Float = 1f,
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val fade: Float = 0f,
    val strokes: List<MarkupStroke> = emptyList(),
    val texts: List<MarkupTextOverlay> = emptyList()
)

/**
 * Computes minimum uniform zoom scale needed to keep a rotated rectangle fully inside the image
 * frame so free-angle straightening never introduces black corner triangles.
 */
internal fun computeStraightenFillScale(angleDeg: Float, width: Float, height: Float): Float {
    if (abs(angleDeg) < 0.05f || width <= 0f || height <= 0f) return 1f
    val rad = Math.toRadians(abs(angleDeg).toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val scaleW = c + s * (height / width)
    val scaleH = c + s * (width / height)
    return maxOf(scaleW, scaleH).coerceAtLeast(1f)
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
    onDismiss: () -> Unit,
    isLocked: Boolean = false
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val container = LocalAppContainer.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val lockedAlbumIds by container.galleryExtrasRepository.lockedAlbumIdsFlow
        .collectAsStateWithLifecycle(initialValue = container.galleryExtrasRepository.latestLockedAlbumIds)
    var itemInLockedAlbum by remember(itemId) { mutableStateOf(false) }
    LaunchedEffect(itemId, lockedAlbumIds, isLocal) {
        if (lockedAlbumIds.isEmpty()) {
            itemInLockedAlbum = false
            return@LaunchedEffect
        }
        itemInLockedAlbum = withContext(Dispatchers.IO) {
            val expandedLocked = container.galleryRepository.expandBlockedFolderIds(lockedAlbumIds)
            if (isLocal) {
                val media = container.localMediaRepository.getCachedAllMedia().firstOrNull { it.id == itemId }
                media?.parentId != null && media.parentId in expandedLocked
            } else {
                val item = container.galleryRepository.getItemById(itemId)
                item?.parentId != null && item.parentId in expandedLocked
            }
        }
    }
    val shouldSecureWindow = isLocked || itemInLockedAlbum || sourceUri.startsWith("file://")
    SecureWindowEffect(enabled = shouldSecureWindow)

    // Always keep status & navigation bar icons light on the pitch-black Darkroom canvas
    DisposableEffect(view) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevLightStatus = controller?.isAppearanceLightStatusBars
        val prevLightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (prevLightStatus != null) controller.isAppearanceLightStatusBars = prevLightStatus
            if (prevLightNav != null) controller.isAppearanceLightNavigationBars = prevLightNav
        }
    }

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cachedDriveSourceFile by remember { mutableStateOf<File?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isComparingOriginal by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    var activeTab by remember { mutableStateOf(EditorTab.ADJUST) }
    var activeAdjustTool by remember { mutableStateOf(AdjustTool.EXPOSURE) }
    var activeAspect by remember { mutableStateOf(CropAspect.FREE) }
    var activeFilter by remember { mutableStateOf(PhotoFilterPreset.ORIGINAL) }
    var filterStrength by remember { mutableFloatStateOf(1f) }

    // Transform state
    var rotationDeg by remember { mutableIntStateOf(0) }
    var straightenDeg by remember { mutableFloatStateOf(0f) }
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

    // Markup studio state (normalized [0..1] coordinates on the transformed image)
    var activeMarkupTool by remember { mutableStateOf(MarkupTool.PEN) }
    var markupColor by remember { mutableStateOf(Color.White) }
    var markupBrushNorm by remember { mutableFloatStateOf(0.014f) }
    var markupStrokes by remember { mutableStateOf<List<MarkupStroke>>(emptyList()) }
    var activeStrokePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var markupTexts by remember { mutableStateOf<List<MarkupTextOverlay>>(emptyList()) }
    var showAddTextDialog by remember { mutableStateOf(false) }

    // Undo / Redo history stack (up to 30 snapshots)
    var undoStack by remember { mutableStateOf<List<EditorSnapshot>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<EditorSnapshot>>(emptyList()) }

    fun captureSnapshot(): EditorSnapshot = EditorSnapshot(
        rotationDeg = rotationDeg,
        straightenDeg = straightenDeg,
        flipH = flipH,
        flipV = flipV,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRight = cropRight,
        cropBottom = cropBottom,
        activeAspect = activeAspect,
        activeFilter = activeFilter,
        filterStrength = filterStrength,
        exposure = exposure,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        warmth = warmth,
        fade = fade,
        strokes = markupStrokes,
        texts = markupTexts
    )

    fun restoreSnapshot(snap: EditorSnapshot) {
        rotationDeg = snap.rotationDeg
        straightenDeg = snap.straightenDeg
        flipH = snap.flipH
        flipV = snap.flipV
        cropLeft = snap.cropLeft
        cropTop = snap.cropTop
        cropRight = snap.cropRight
        cropBottom = snap.cropBottom
        activeAspect = snap.activeAspect
        activeFilter = snap.activeFilter
        filterStrength = snap.filterStrength
        exposure = snap.exposure
        brightness = snap.brightness
        contrast = snap.contrast
        saturation = snap.saturation
        warmth = snap.warmth
        fade = snap.fade
        markupStrokes = snap.strokes
        markupTexts = snap.texts
    }

    fun pushUndoCheckpoint() {
        val snap = captureSnapshot()
        if (undoStack.lastOrNull() != snap) {
            undoStack = (undoStack + snap).takeLast(30)
            redoStack = emptyList()
        }
    }

    fun performUndo() {
        val prev = undoStack.lastOrNull() ?: return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        redoStack = (redoStack + captureSnapshot()).takeLast(30)
        undoStack = undoStack.dropLast(1)
        restoreSnapshot(prev)
    }

    fun performRedo() {
        val next = redoStack.lastOrNull() ?: return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        undoStack = (undoStack + captureSnapshot()).takeLast(30)
        redoStack = redoStack.dropLast(1)
        restoreSnapshot(next)
    }

    val isCropped = cropLeft > 0.005f || cropTop > 0.005f || cropRight < 0.995f || cropBottom < 0.995f
    val hasAnyEdits = rotationDeg != 0 || abs(straightenDeg) > 0.1f || flipH || flipV ||
        isCropped ||
        activeFilter != PhotoFilterPreset.ORIGINAL ||
        abs(exposure) > 0.01f || abs(brightness) > 0.01f || abs(contrast) > 0.01f ||
        abs(saturation) > 0.01f || abs(warmth) > 0.01f || abs(fade) > 0.01f ||
        markupStrokes.isNotEmpty() || markupTexts.isNotEmpty()

    fun requestClose() {
        if (isSaving) return
        if (hasAnyEdits) {
            showDiscardDialog = true
        } else {
            onDismiss()
        }
    }

    BackHandler(enabled = !isSaving) {
        requestClose()
    }

    // Load preview bitmap once (2200px preview; full-res re-decoded on Save)
    LaunchedEffect(itemId, sourceUri, isLocal) {
        withContext(Dispatchers.IO) {
            runCatching {
                val offlinePath = container.offlineMediaManager.getOfflineFilePath(itemId)
                when {
                    !offlinePath.isNullOrBlank() && File(offlinePath).exists() -> {
                        decodeEditorBitmap(File(offlinePath), maxPx = 2200)
                    }
                    isLocal && sourceUri.isNotBlank() -> {
                        decodeEditorBitmap(context, Uri.parse(sourceUri), maxPx = 2200)
                    }
                    else -> {
                        val tmp = File(context.cacheDir, "edit_src_${itemId.hashCode()}_${System.currentTimeMillis()}.jpg")
                        container.driveApiService.downloadFile(itemId).use { body ->
                            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                        }
                        cachedDriveSourceFile = tmp
                        decodeEditorBitmap(tmp, maxPx = 2200)
                    }
                }
            }.onSuccess { bmp ->
                if (bmp != null) sourceBitmap = bmp else loadError = "Unable to decode photo"
            }.onFailure { err ->
                loadError = err.localizedMessage ?: "Failed to load photo"
            }
        }
    }

    // Clean up temporary cached Drive file on exit
    DisposableEffect(Unit) {
        onDispose {
            cachedDriveSourceFile?.let { runCatching { it.delete() } }
            cachedDriveSourceFile = null
        }
    }

    // Recompute transformed preview bitmap cleanly whenever rotate/flip changes
    val transformedBitmap = remember(sourceBitmap, rotationDeg, flipH, flipV) {
        val base = sourceBitmap ?: return@remember null
        if (rotationDeg == 0 && !flipH && !flipV) return@remember base
        applyRotateFlip(base, rotationDeg, flipH, flipV)
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

    // Precomputed real pixelated mosaic preview bitmap whenever mosaic tool or strokes are present
    val hasMosaicStrokes = activeMarkupTool == MarkupTool.MOSAIC ||
        markupStrokes.any { it.tool == MarkupTool.MOSAIC }
    val mosaicPreviewBitmap = remember(transformedBitmap, hasMosaicStrokes) {
        val bmp = transformedBitmap ?: return@remember null
        if (!hasMosaicStrokes) return@remember null
        val blockScale = (min(bmp.width, bmp.height) / 36).coerceIn(10, 48)
        val tinyW = (bmp.width / blockScale).coerceAtLeast(4)
        val tinyH = (bmp.height / blockScale).coerceAtLeast(4)
        val tiny = Bitmap.createScaledBitmap(bmp, tinyW, tinyH, true)
        Bitmap.createScaledBitmap(tiny, bmp.width, bmp.height, false).also { tiny.recycle() }
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

    fun rotateViewCw90() {
        pushUndoCheckpoint()
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val oldH = flipH
        flipH = flipV
        flipV = oldH
        rotationDeg = (rotationDeg + 90) % 360
        // Transform crop rect & markup coordinates by 90° CW: (x, y) -> (1 - y, x)
        val nl = 1f - cropBottom
        val nt = cropLeft
        val nr = 1f - cropTop
        val nb = cropRight
        cropLeft = nl.coerceIn(0f, 1f)
        cropTop = nt.coerceIn(0f, 1f)
        cropRight = nr.coerceIn(0f, 1f)
        cropBottom = nb.coerceIn(0f, 1f)
        markupStrokes = markupStrokes.map { s ->
            s.copy(points = s.points.map { p -> Offset(1f - p.y, p.x) })
        }
        markupTexts = markupTexts.map { t ->
            t.copy(normX = 1f - t.normY, normY = t.normX)
        }
    }

    fun rotateViewCcw90() {
        pushUndoCheckpoint()
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val oldH = flipH
        flipH = flipV
        flipV = oldH
        rotationDeg = (rotationDeg + 270) % 360
        // Transform crop rect & markup coordinates by 90° CCW: (x, y) -> (y, 1 - x)
        val nl = cropTop
        val nt = 1f - cropRight
        val nr = cropBottom
        val nb = 1f - cropLeft
        cropLeft = nl.coerceIn(0f, 1f)
        cropTop = nt.coerceIn(0f, 1f)
        cropRight = nr.coerceIn(0f, 1f)
        cropBottom = nb.coerceIn(0f, 1f)
        markupStrokes = markupStrokes.map { s ->
            s.copy(points = s.points.map { p -> Offset(p.y, 1f - p.x) })
        }
        markupTexts = markupTexts.map { t ->
            t.copy(normX = t.normY, normY = 1f - t.normX)
        }
    }

    fun toggleFlipH() {
        pushUndoCheckpoint()
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        flipH = !flipH
        val nl = 1f - cropRight
        val nr = 1f - cropLeft
        cropLeft = nl.coerceIn(0f, 1f)
        cropRight = nr.coerceIn(0f, 1f)
        markupStrokes = markupStrokes.map { s ->
            s.copy(points = s.points.map { p -> Offset(1f - p.x, p.y) })
        }
        markupTexts = markupTexts.map { t ->
            t.copy(normX = 1f - t.normX)
        }
    }

    fun toggleFlipV() {
        pushUndoCheckpoint()
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        flipV = !flipV
        val nt = 1f - cropBottom
        val nb = 1f - cropTop
        cropTop = nt.coerceIn(0f, 1f)
        cropBottom = nb.coerceIn(0f, 1f)
        markupStrokes = markupStrokes.map { s ->
            s.copy(points = s.points.map { p -> Offset(p.x, 1f - p.y) })
        }
        markupTexts = markupTexts.map { t ->
            t.copy(normY = 1f - t.normY)
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
        pushUndoCheckpoint()
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        rotationDeg = 0
        straightenDeg = 0f
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
        markupStrokes = emptyList()
        markupTexts = emptyList()
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
        if (transformedBitmap == null || isSaving) return
        isSaving = true
        val finalMatrix = buildCombinedColorMatrix()
        val finalRotation = rotationDeg
        val finalFlipH = flipH
        val finalFlipV = flipV
        val finalStraighten = straightenDeg
        val finalStrokes = markupStrokes
        val finalTexts = markupTexts
        val finalCropLeft = cropLeft
        val finalCropTop = cropTop
        val finalCropRight = cropRight
        val finalCropBottom = cropBottom
        val fallbackPreviewBmp = transformedBitmap

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Preserve original EXIF metadata (camera, lens, date, GPS)
                    val offlinePath = container.offlineMediaManager.getOfflineFilePath(itemId)
                    val offlineFile = offlinePath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
                    val parsedLocalUri = if (isLocal && sourceUri.isNotBlank()) Uri.parse(sourceUri) else null
                    val originalExif = readOriginalExifSnapshot(
                        context = context,
                        localUri = parsedLocalUri,
                        file = offlineFile ?: cachedDriveSourceFile
                    )

                    // Re-decode at high resolution (up to 6000px) so 12MP-24MP photos keep full detail
                    val hiResRaw = when {
                        offlineFile != null -> decodeEditorBitmap(offlineFile, maxPx = 6000)
                        parsedLocalUri != null -> decodeEditorBitmap(context, parsedLocalUri, maxPx = 6000)
                        cachedDriveSourceFile?.exists() == true -> decodeEditorBitmap(cachedDriveSourceFile!!, maxPx = 6000)
                        else -> null
                    }
                    val hiResTransformed = if (hiResRaw != null) {
                        if (finalRotation == 0 && !finalFlipH && !finalFlipV) {
                            hiResRaw
                        } else {
                            applyRotateFlip(hiResRaw, finalRotation, finalFlipH, finalFlipV).also {
                                hiResRaw.recycle()
                            }
                        }
                    } else null

                    val bmp = hiResTransformed ?: fallbackPreviewBmp
                        ?: throw IOException("Unable to decode image for export")
                    val shouldRecycleSource = hiResTransformed != null

                    try {
                        // 1. Render full transformed image with tone filter + free-angle straighten + markup
                        val fullCanvasBmp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                        val fullCanvas = Canvas(fullCanvasBmp)
                        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            colorFilter = ColorMatrixColorFilter(finalMatrix)
                        }
                        if (abs(finalStraighten) > 0.05f) {
                            val fillScale = computeStraightenFillScale(
                                finalStraighten,
                                bmp.width.toFloat(),
                                bmp.height.toFloat()
                            )
                            fullCanvas.save()
                            fullCanvas.translate(bmp.width / 2f, bmp.height / 2f)
                            fullCanvas.rotate(finalStraighten)
                            fullCanvas.scale(fillScale, fillScale)
                            fullCanvas.translate(-bmp.width / 2f, -bmp.height / 2f)
                            fullCanvas.drawBitmap(bmp, 0f, 0f, tonePaint)
                            fullCanvas.restore()
                        } else {
                            fullCanvas.drawBitmap(bmp, 0f, 0f, tonePaint)
                        }

                        // 2. Bake Markup strokes (Pen, Highlighter, Mosaic pixelation censor) & Text overlays
                        if (finalStrokes.isNotEmpty() || finalTexts.isNotEmpty()) {
                            val hasMosaic = finalStrokes.any { it.tool == MarkupTool.MOSAIC }
                            val mosaicBmp = if (hasMosaic) {
                                val blockScale = (min(bmp.width, bmp.height) / 36).coerceIn(12, 80)
                                val tinyW = (bmp.width / blockScale).coerceAtLeast(4)
                                val tinyH = (bmp.height / blockScale).coerceAtLeast(4)
                                val tiny = Bitmap.createScaledBitmap(fullCanvasBmp, tinyW, tinyH, true)
                                Bitmap.createScaledBitmap(tiny, bmp.width, bmp.height, false).also { tiny.recycle() }
                            } else null

                            try {
                                val baseMin = min(bmp.width, bmp.height).toFloat()
                                for (stroke in finalStrokes) {
                                    if (stroke.points.isEmpty()) continue
                                    val path = AndroidPath()
                                    val first = stroke.points.first()
                                    path.moveTo(first.x * bmp.width, first.y * bmp.height)
                                    for (pt in stroke.points.drop(1)) {
                                        path.lineTo(pt.x * bmp.width, pt.y * bmp.height)
                                    }
                                    val strokePx = (stroke.widthNorm * baseMin).coerceAtLeast(4f)
                                    if (stroke.tool == MarkupTool.MOSAIC && mosaicBmp != null) {
                                        val mosaicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.STROKE
                                            strokeWidth = strokePx * 2.2f
                                            strokeCap = Paint.Cap.ROUND
                                            strokeJoin = Paint.Join.ROUND
                                            shader = android.graphics.BitmapShader(
                                                mosaicBmp,
                                                android.graphics.Shader.TileMode.CLAMP,
                                                android.graphics.Shader.TileMode.CLAMP
                                            )
                                        }
                                        fullCanvas.drawPath(path, mosaicPaint)
                                    } else {
                                        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.STROKE
                                            color = stroke.colorArgb
                                            alpha = if (stroke.tool == MarkupTool.HIGHLIGHTER) 115 else 255
                                            strokeWidth = if (stroke.tool == MarkupTool.HIGHLIGHTER) strokePx * 2.1f else strokePx
                                            strokeCap = Paint.Cap.ROUND
                                            strokeJoin = Paint.Join.ROUND
                                        }
                                        fullCanvas.drawPath(path, drawPaint)
                                    }
                                }

                                val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.argb(110, 0, 0, 0)
                                    style = Paint.Style.FILL
                                }
                                for (item in finalTexts) {
                                    val textSizePx = (baseMin * 0.055f).coerceIn(24f, 260f)
                                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color = item.colorArgb
                                        textSize = textSizePx
                                        textAlign = Paint.Align.CENTER
                                        isFakeBoldText = true
                                    }
                                    val centerX = item.normX * bmp.width
                                    val centerY = item.normY * bmp.height
                                    val fm = textPaint.fontMetrics
                                    val textW = textPaint.measureText(item.text)
                                    val textH = fm.descent - fm.ascent
                                    val padH = textSizePx * 0.42f
                                    val padV = textSizePx * 0.20f
                                    val cornerR = textSizePx * 0.36f
                                    val pillRect = RectF(
                                        centerX - textW / 2f - padH,
                                        centerY - textH / 2f - padV,
                                        centerX + textW / 2f + padH,
                                        centerY + textH / 2f + padV
                                    )
                                    fullCanvas.drawRoundRect(pillRect, cornerR, cornerR, pillPaint)
                                    val baselineY = centerY - (fm.ascent + fm.descent) / 2f
                                    fullCanvas.drawText(item.text, centerX, baselineY, textPaint)
                                }
                            } finally {
                                mosaicBmp?.recycle()
                            }
                        }

                        // 3. Apply crop rect onto the final baked canvas
                        val leftPx = (finalCropLeft * fullCanvasBmp.width).roundToInt().coerceIn(0, fullCanvasBmp.width - 1)
                        val topPx = (finalCropTop * fullCanvasBmp.height).roundToInt().coerceIn(0, fullCanvasBmp.height - 1)
                        val wPx = ((finalCropRight - finalCropLeft) * fullCanvasBmp.width).roundToInt().coerceIn(1, fullCanvasBmp.width - leftPx)
                        val hPx = ((finalCropBottom - finalCropTop) * fullCanvasBmp.height).roundToInt().coerceIn(1, fullCanvasBmp.height - topPx)

                        val outBmp = Bitmap.createBitmap(fullCanvasBmp, leftPx, topPx, wPx, hPx)
                        fullCanvasBmp.recycle()
                        try {
                            val stem = displayName.substringBeforeLast('.').take(70).ifBlank { "photo" }
                            val outName = "${stem}_edited_${System.currentTimeMillis() % 100000}.jpg"
                            saveBitmapToMediaStore(context, outBmp, outName, originalExif)
                            outName
                        } finally {
                            outBmp.recycle()
                        }
                    } finally {
                        if (shouldRecycleSource) bmp.recycle()
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
                        .clickable(enabled = !isSaving, onClick = ::requestClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cancel",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Center: Undo / Redo + Hold-to-Compare "Original" + Reset pills
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (undoStack.isNotEmpty() || redoStack.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (undoStack.isNotEmpty()) 0.12f else 0.05f))
                                .clickable(enabled = !isSaving && undoStack.isNotEmpty(), onClick = ::performUndo),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Undo,
                                contentDescription = "Undo",
                                tint = Color.White.copy(alpha = if (undoStack.isNotEmpty()) 0.92f else 0.30f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (redoStack.isNotEmpty()) 0.12f else 0.05f))
                                .clickable(enabled = !isSaving && redoStack.isNotEmpty(), onClick = ::performRedo),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Redo,
                                contentDescription = "Redo",
                                tint = Color.White.copy(alpha = if (redoStack.isNotEmpty()) 0.92f else 0.30f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = hasAnyEdits,
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(150))
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
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
                        val activeBmp = if (isComparingOriginal) (sourceBitmap ?: transformedBitmap) else transformedBitmap
                        val imgRect = remember(viewportSize, activeBmp.width, activeBmp.height) {
                            computeFittedRect(viewportSize, activeBmp.width, activeBmp.height)
                        }
                        val liveStraighten = if (isComparingOriginal) 0f else straightenDeg
                        val liveFillScale = remember(liveStraighten, activeBmp.width, activeBmp.height) {
                            computeStraightenFillScale(liveStraighten, activeBmp.width.toFloat(), activeBmp.height.toFloat())
                        }
                        val textMeasurer = rememberTextMeasurer()

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { clip = true }
                        ) {
                            // Clip straightened preview strictly to the image rect so corners never bleed
                            if (imgRect.width > 1f && imgRect.height > 1f) {
                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(imgRect.left.roundToInt(), imgRect.top.roundToInt()) }
                                        .size(
                                            width = with(density) { imgRect.width.toDp() },
                                            height = with(density) { imgRect.height.toDp() }
                                        )
                                        .clipToBounds()
                                ) {
                                    Image(
                                        bitmap = activeBmp.asImageBitmap(),
                                        contentDescription = displayName,
                                        contentScale = ContentScale.FillBounds,
                                        colorFilter = composeColorFilter,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                rotationZ = liveStraighten
                                                scaleX = liveFillScale
                                                scaleY = liveFillScale
                                            }
                                    )
                                }
                            }

                            // Live Markup Layer (Pen / Marker / True Pixelated Mosaic preview / Draggable Text overlays)
                            if (!isComparingOriginal &&
                                (markupStrokes.isNotEmpty() || activeStrokePoints.isNotEmpty() || markupTexts.isNotEmpty() || activeTab == EditorTab.MARKUP) &&
                                imgRect.width > 10f && imgRect.height > 10f
                            ) {
                                val latestMarkupTool by rememberUpdatedState(activeMarkupTool)
                                val latestMarkupColor by rememberUpdatedState(markupColor)
                                val latestBrushNorm by rememberUpdatedState(markupBrushNorm)
                                val latestTexts by rememberUpdatedState(markupTexts)
                                val latestStrokes by rememberUpdatedState(markupStrokes)

                                ComposeCanvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (activeTab == EditorTab.MARKUP) {
                                                Modifier.pointerInput(imgRect) {
                                                    var activeDragTextId: Long? = null
                                                    detectDragGestures(
                                                        onDragStart = { pos ->
                                                            if (pos.x !in imgRect.left..imgRect.right || pos.y !in imgRect.top..imgRect.bottom) return@detectDragGestures
                                                            val nx = ((pos.x - imgRect.left) / imgRect.width).coerceIn(0f, 1f)
                                                            val ny = ((pos.y - imgRect.top) / imgRect.height).coerceIn(0f, 1f)
                                                            val hitText = latestTexts.lastOrNull { t ->
                                                                abs(t.normX - nx) < 0.16f && abs(t.normY - ny) < 0.09f
                                                            }
                                                            if (hitText != null) {
                                                                pushUndoCheckpoint()
                                                                activeDragTextId = hitText.id
                                                            } else if (latestMarkupTool != MarkupTool.TEXT) {
                                                                activeStrokePoints = listOf(Offset(nx, ny))
                                                            }
                                                        },
                                                        onDragEnd = {
                                                            if (activeDragTextId != null) {
                                                                activeDragTextId = null
                                                            } else if (activeStrokePoints.isNotEmpty()) {
                                                                pushUndoCheckpoint()
                                                                markupStrokes = latestStrokes + MarkupStroke(
                                                                    tool = latestMarkupTool,
                                                                    colorArgb = latestMarkupColor.toArgb(),
                                                                    widthNorm = latestBrushNorm,
                                                                    points = activeStrokePoints
                                                                )
                                                                activeStrokePoints = emptyList()
                                                            }
                                                        },
                                                        onDragCancel = {
                                                            activeDragTextId = null
                                                            activeStrokePoints = emptyList()
                                                        },
                                                        onDrag = { change, drag ->
                                                            change.consume()
                                                            val textId = activeDragTextId
                                                            if (textId != null) {
                                                                val dx = drag.x / imgRect.width
                                                                val dy = drag.y / imgRect.height
                                                                markupTexts = latestTexts.map { t ->
                                                                    if (t.id == textId) {
                                                                        t.copy(
                                                                            normX = (t.normX + dx).coerceIn(0.05f, 0.95f),
                                                                            normY = (t.normY + dy).coerceIn(0.05f, 0.95f)
                                                                        )
                                                                    } else t
                                                                }
                                                            } else if (latestMarkupTool != MarkupTool.TEXT) {
                                                                val pos = change.position
                                                                val nx = ((pos.x - imgRect.left) / imgRect.width).coerceIn(0f, 1f)
                                                                val ny = ((pos.y - imgRect.top) / imgRect.height).coerceIn(0f, 1f)
                                                                activeStrokePoints = activeStrokePoints + Offset(nx, ny)
                                                            }
                                                        }
                                                    )
                                                }
                                            } else Modifier
                                        )
                                ) {
                                    val baseMin = min(imgRect.width, imgRect.height)
                                    val strokesToDraw = if (activeStrokePoints.isNotEmpty()) {
                                        markupStrokes + MarkupStroke(
                                            tool = activeMarkupTool,
                                            colorArgb = markupColor.toArgb(),
                                            widthNorm = markupBrushNorm,
                                            points = activeStrokePoints
                                        )
                                    } else markupStrokes

                                    for (stroke in strokesToDraw) {
                                        if (stroke.points.isEmpty()) continue
                                        val widthPx = (stroke.widthNorm * baseMin).coerceAtLeast(3f)
                                        if (stroke.tool == MarkupTool.MOSAIC && mosaicPreviewBitmap != null) {
                                            drawIntoCanvas { canvas ->
                                                val androidPath = AndroidPath().apply {
                                                    val p0 = stroke.points.first()
                                                    moveTo(imgRect.left + p0.x * imgRect.width, imgRect.top + p0.y * imgRect.height)
                                                    for (pt in stroke.points.drop(1)) {
                                                        lineTo(imgRect.left + pt.x * imgRect.width, imgRect.top + pt.y * imgRect.height)
                                                    }
                                                }
                                                val shaderMatrix = Matrix().apply {
                                                    setScale(
                                                        imgRect.width / mosaicPreviewBitmap.width.toFloat().coerceAtLeast(1f),
                                                        imgRect.height / mosaicPreviewBitmap.height.toFloat().coerceAtLeast(1f)
                                                    )
                                                    postTranslate(imgRect.left, imgRect.top)
                                                }
                                                val shader = android.graphics.BitmapShader(
                                                    mosaicPreviewBitmap,
                                                    android.graphics.Shader.TileMode.CLAMP,
                                                    android.graphics.Shader.TileMode.CLAMP
                                                ).apply { setLocalMatrix(shaderMatrix) }
                                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                                    style = Paint.Style.STROKE
                                                    strokeWidth = widthPx * 2.2f
                                                    strokeCap = Paint.Cap.ROUND
                                                    strokeJoin = Paint.Join.ROUND
                                                    this.shader = shader
                                                }
                                                canvas.nativeCanvas.drawPath(androidPath, paint)
                                            }
                                        } else {
                                            val path = Path().apply {
                                                val p0 = stroke.points.first()
                                                moveTo(imgRect.left + p0.x * imgRect.width, imgRect.top + p0.y * imgRect.height)
                                                for (pt in stroke.points.drop(1)) {
                                                    lineTo(imgRect.left + pt.x * imgRect.width, imgRect.top + pt.y * imgRect.height)
                                                }
                                            }
                                            when (stroke.tool) {
                                                MarkupTool.HIGHLIGHTER -> {
                                                    drawPath(
                                                        path = path,
                                                        color = Color(stroke.colorArgb).copy(alpha = 0.45f),
                                                        style = Stroke(
                                                            width = widthPx * 2.1f,
                                                            cap = StrokeCap.Round,
                                                            join = StrokeJoin.Round
                                                        )
                                                    )
                                                }
                                                else -> {
                                                    drawPath(
                                                        path = path,
                                                        color = Color(stroke.colorArgb),
                                                        style = Stroke(
                                                            width = widthPx,
                                                            cap = StrokeCap.Round,
                                                            join = StrokeJoin.Round
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    val textPx = (baseMin * 0.055f).coerceIn(14f, 64f)
                                    val textSp = with(density) { textPx.toSp() }
                                    val padH = textPx * 0.42f
                                    val padV = textPx * 0.20f
                                    val cornerR = textPx * 0.36f
                                    for (t in markupTexts) {
                                        val measured = textMeasurer.measure(
                                            text = t.text,
                                            style = TextStyle(
                                                color = Color(t.colorArgb),
                                                fontSize = textSp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        val cx = imgRect.left + t.normX * imgRect.width - measured.size.width / 2f
                                        val cy = imgRect.top + t.normY * imgRect.height - measured.size.height / 2f
                                        drawRoundRect(
                                            color = Color.Black.copy(alpha = 0.43f),
                                            topLeft = Offset(cx - padH, cy - padV),
                                            size = Size(measured.size.width + padH * 2f, measured.size.height + padV * 2f),
                                            cornerRadius = CornerRadius(cornerR, cornerR)
                                        )
                                        drawText(
                                            textLayoutResult = measured,
                                            topLeft = Offset(cx, cy)
                                        )
                                    }
                                }
                            }

                            // Non-interactive darkened crop preview mask on Adjust/Filters/Markup tabs so preview matches export
                            if (!isComparingOriginal && activeTab != EditorTab.CROP && isCropped &&
                                imgRect.width > 10f && imgRect.height > 10f
                            ) {
                                ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                                    val l = imgRect.left + cropLeft * imgRect.width
                                    val t = imgRect.top + cropTop * imgRect.height
                                    val r = imgRect.left + cropRight * imgRect.width
                                    val b = imgRect.top + cropBottom * imgRect.height
                                    val scrim = Color.Black.copy(alpha = 0.72f)
                                    drawRect(scrim, Offset(imgRect.left, imgRect.top), Size(imgRect.width, (t - imgRect.top).coerceAtLeast(0f)))
                                    drawRect(scrim, Offset(imgRect.left, b), Size(imgRect.width, (imgRect.bottom - b).coerceAtLeast(0f)))
                                    drawRect(scrim, Offset(imgRect.left, t), Size((l - imgRect.left).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
                                    drawRect(scrim, Offset(r, t), Size((imgRect.right - r).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
                                    drawRect(
                                        color = Color.White.copy(alpha = 0.25f),
                                        topLeft = Offset(l, t),
                                        size = Size((r - l).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)),
                                        style = Stroke(width = 0.8.dp.toPx())
                                    )
                                }
                            }
                        }

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

                        // Apple / Samsung Crop Overlay with Dynamic Rule-of-Thirds & Aspect Ratio Lock
                        if (!isComparingOriginal && activeTab == EditorTab.CROP && imgRect.width > 10f && imgRect.height > 10f) {
                            var activeHandle by remember { mutableStateOf<CropHandle?>(null) }
                            val gridAlpha by animateFloatAsState(
                                targetValue = if (activeHandle != null) 0.52f else 0.18f,
                                animationSpec = tween(180),
                                label = "cropGridAlpha"
                            )
                            val hitRadiusPx = with(density) { 28.dp.toPx() }
                            val imgRatio = activeBmp.width.toFloat() / activeBmp.height.toFloat().coerceAtLeast(1f)

                            ComposeCanvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(imgRect, activeAspect, imgRatio, hitRadiusPx) {
                                        val normRatio = activeAspect.ratio?.let { it / imgRatio }
                                        detectDragGestures(
                                            onDragStart = { pos ->
                                                val l = imgRect.left + cropLeft * imgRect.width
                                                val t = imgRect.top + cropTop * imgRect.height
                                                val r = imgRect.left + cropRight * imgRect.width
                                                val b = imgRect.top + cropBottom * imgRect.height
                                                activeHandle = when {
                                                    (pos - Offset(l, t)).getDistance() < hitRadiusPx -> CropHandle.TOP_LEFT
                                                    (pos - Offset(r, t)).getDistance() < hitRadiusPx -> CropHandle.TOP_RIGHT
                                                    (pos - Offset(l, b)).getDistance() < hitRadiusPx -> CropHandle.BOTTOM_LEFT
                                                    (pos - Offset(r, b)).getDistance() < hitRadiusPx -> CropHandle.BOTTOM_RIGHT
                                                    pos.x in l..r && pos.y in t..b -> CropHandle.MOVE
                                                    else -> null
                                                }
                                                if (activeHandle != null) pushUndoCheckpoint()
                                            },
                                            onDragEnd = { activeHandle = null },
                                            onDragCancel = { activeHandle = null },
                                            onDrag = { change, drag ->
                                                change.consume()
                                                val handle = activeHandle ?: return@detectDragGestures
                                                val dx = drag.x / imgRect.width
                                                val dy = drag.y / imgRect.height
                                                val minSize = 0.14f
                                                if (handle == CropHandle.MOVE) {
                                                    val w = cropRight - cropLeft
                                                    val h = cropBottom - cropTop
                                                    val nl = (cropLeft + dx).coerceIn(0f, 1f - w)
                                                    val nt = (cropTop + dy).coerceIn(0f, 1f - h)
                                                    cropLeft = nl
                                                    cropRight = nl + w
                                                    cropTop = nt
                                                    cropBottom = nt + h
                                                } else if (normRatio == null) {
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
                                                        else -> Unit
                                                    }
                                                } else {
                                                    // Enforce locked aspect ratio relative to the opposite anchored corner
                                                    fun fitConstrained(desiredW: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
                                                        val capW = min(maxW, maxH * normRatio)
                                                        val minW = maxOf(minSize, minSize * normRatio).coerceAtMost(capW)
                                                        val w = desiredW.coerceIn(minW, capW)
                                                        val h = (w / normRatio).coerceIn(minSize.coerceAtMost(maxH), maxH)
                                                        return w to h
                                                    }
                                                    when (handle) {
                                                        CropHandle.TOP_LEFT -> {
                                                            val desiredW = if (abs(dx) >= abs(dy)) {
                                                                cropRight - (cropLeft + dx)
                                                            } else {
                                                                (cropBottom - (cropTop + dy)) * normRatio
                                                            }
                                                            val (w, h) = fitConstrained(desiredW, cropRight, cropBottom)
                                                            cropLeft = (cropRight - w).coerceIn(0f, cropRight)
                                                            cropTop = (cropBottom - h).coerceIn(0f, cropBottom)
                                                        }
                                                        CropHandle.TOP_RIGHT -> {
                                                            val desiredW = if (abs(dx) >= abs(dy)) {
                                                                (cropRight + dx) - cropLeft
                                                            } else {
                                                                (cropBottom - (cropTop + dy)) * normRatio
                                                            }
                                                            val (w, h) = fitConstrained(desiredW, 1f - cropLeft, cropBottom)
                                                            cropRight = (cropLeft + w).coerceIn(cropLeft, 1f)
                                                            cropTop = (cropBottom - h).coerceIn(0f, cropBottom)
                                                        }
                                                        CropHandle.BOTTOM_LEFT -> {
                                                            val desiredW = if (abs(dx) >= abs(dy)) {
                                                                cropRight - (cropLeft + dx)
                                                            } else {
                                                                ((cropBottom + dy) - cropTop) * normRatio
                                                            }
                                                            val (w, h) = fitConstrained(desiredW, cropRight, 1f - cropTop)
                                                            cropLeft = (cropRight - w).coerceIn(0f, cropRight)
                                                            cropBottom = (cropTop + h).coerceIn(cropTop, 1f)
                                                        }
                                                        CropHandle.BOTTOM_RIGHT -> {
                                                            val desiredW = if (abs(dx) >= abs(dy)) {
                                                                (cropRight + dx) - cropLeft
                                                            } else {
                                                                ((cropBottom + dy) - cropTop) * normRatio
                                                            }
                                                            val (w, h) = fitConstrained(desiredW, 1f - cropLeft, 1f - cropTop)
                                                            cropRight = (cropLeft + w).coerceIn(cropLeft, 1f)
                                                            cropBottom = (cropTop + h).coerceIn(cropTop, 1f)
                                                        }
                                                        else -> Unit
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
                                    onDragStart = ::pushUndoCheckpoint,
                                    onValueChange = { v -> setValueForTool(activeAdjustTool, v) },
                                    onDoubleTapReset = {
                                        pushUndoCheckpoint()
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
                                                pushUndoCheckpoint()
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
                                        onDragStart = ::pushUndoCheckpoint,
                                        onValueChange = { filterStrength = it },
                                        onDoubleTapReset = {
                                            pushUndoCheckpoint()
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
                                                    pushUndoCheckpoint()
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
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Free-angle Straighten Slider (-45° to +45°, auto zoom-to-fill!)
                                ProRulerSlider(
                                    label = "Straighten (${String.format(java.util.Locale.US, "%+.1f°", straightenDeg)})",
                                    value = (straightenDeg / 45f).coerceIn(-1f, 1f),
                                    minValue = -1f,
                                    maxValue = 1f,
                                    accentColor = accentColor,
                                    onDragStart = ::pushUndoCheckpoint,
                                    onValueChange = { norm ->
                                        val deg = (norm * 45f).coerceIn(-45f, 45f)
                                        straightenDeg = if (abs(deg) < 0.6f) 0f else deg
                                    },
                                    onDoubleTapReset = {
                                        pushUndoCheckpoint()
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        straightenDeg = 0f
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                )

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
                                        active = false,
                                        onClick = ::rotateViewCcw90
                                    )
                                    StudioTransformButton(
                                        icon = Icons.Rounded.Rotate90DegreesCw,
                                        label = "Rotate R",
                                        active = false,
                                        onClick = ::rotateViewCw90
                                    )
                                    StudioTransformButton(
                                        icon = Icons.Rounded.Flip,
                                        label = "Flip H",
                                        active = flipH,
                                        onClick = ::toggleFlipH
                                    )
                                    StudioTransformButton(
                                        icon = Icons.Rounded.Flip,
                                        label = "Flip V",
                                        active = flipV,
                                        onClick = ::toggleFlipV
                                    )
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
                                                .clickable {
                                                    pushUndoCheckpoint()
                                                    applyAspectRatio(aspect)
                                                }
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

                        EditorTab.MARKUP -> {
                            val swatches = remember {
                                listOf(
                                    Color.White,
                                    Color.Black,
                                    Color(0xFFFF4D5E),
                                    Color(0xFFFFD54F),
                                    Color(0xFF40C4FF),
                                    Color(0xFF69F0AE)
                                )
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Tool selector row: Pen | Marker | Mosaic Censor | Add Text
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    MarkupTool.entries.forEach { tool ->
                                        val isToolActive = activeMarkupTool == tool
                                        StudioTransformButton(
                                            icon = tool.icon,
                                            label = tool.label,
                                            active = isToolActive
                                        ) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            activeMarkupTool = tool
                                            if (tool == MarkupTool.TEXT) {
                                                showAddTextDialog = true
                                            }
                                        }
                                    }
                                }

                                // Color swatches + Brush size + Clear markup
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                        swatches.forEach { swatch ->
                                            val chosen = markupColor == swatch
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .background(swatch)
                                                    .border(
                                                        width = if (chosen) 2.5.dp else 1.dp,
                                                        color = if (chosen) accentColor else Color.White.copy(alpha = 0.35f),
                                                        shape = CircleShape
                                                    )
                                                    .clickable { markupColor = swatch }
                                            )
                                        }
                                    }

                                    // Brush size toggle pill (S / M / L)
                                    val sizeLabel = when {
                                        markupBrushNorm < 0.011f -> "Thin"
                                        markupBrushNorm < 0.022f -> "Medium"
                                        else -> "Thick"
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color(0xFF22222A),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                markupBrushNorm = when {
                                                    markupBrushNorm < 0.011f -> 0.016f
                                                    markupBrushNorm < 0.022f -> 0.030f
                                                    else -> 0.008f
                                                }
                                            }
                                    ) {
                                        Text(
                                            text = "Size: $sizeLabel",
                                            color = Color.White,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
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

        LuminaSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 140.dp)
        )
    }

    if (showAddTextDialog) {
        var textInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            containerColor = Color(0xFF232328),
            shape = RoundedCornerShape(24.dp),
            title = { Text("Add text label", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = {
                        Text(
                            text = "Type caption (drag to move on photo)",
                            color = Color.White.copy(alpha = 0.55f)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = accentColor,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = textInput.isNotBlank(),
                    onClick = {
                        pushUndoCheckpoint()
                        markupTexts = markupTexts + MarkupTextOverlay(
                            id = System.currentTimeMillis(),
                            text = textInput.trim(),
                            colorArgb = markupColor.toArgb(),
                            normX = 0.5f,
                            normY = 0.5f
                        )
                        showAddTextDialog = false
                    }
                ) { Text("Add", color = accentColor, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddTextDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.8f))
                }
            }
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            containerColor = Color(0xFF232328),
            shape = RoundedCornerShape(24.dp),
            title = { Text("Discard changes?", color = Color.White) },
            text = {
                Text(
                    text = "Your edits haven't been saved yet.",
                    color = Color.White.copy(alpha = 0.78f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onDismiss()
                    }
                ) {
                    Text("Discard", color = Color(0xFFFF6B6B), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep editing", color = Color.White.copy(alpha = 0.85f))
                }
            }
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
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestReset by rememberUpdatedState(onDoubleTapReset)
    val latestOnDragStart by rememberUpdatedState(onDragStart)

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
                            latestOnDragStart()
                            val frac = (tap.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val mapped = minValue + frac * (maxValue - minValue)
                            val snapped = if (minValue < 0f && abs(mapped) < 0.03f) 0f else mapped
                            latestOnChange(snapped)
                        },
                        onDoubleTap = { latestReset() }
                    )
                }
                .pointerInput(minValue, maxValue) {
                    detectHorizontalDragGestures(
                        onDragStart = { latestOnDragStart() }
                    ) { change, dragAmount ->
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

private val EXIF_TAGS_TO_PRESERVE = listOf(
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_LENS_MAKE,
    ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP
)

private data class OriginalExifSnapshot(
    val attributes: Map<String, String> = emptyMap(),
    val dateTakenMs: Long? = null
)

private fun readOriginalExifSnapshot(
    context: Context,
    localUri: Uri?,
    file: File?
): OriginalExifSnapshot {
    val attrs = LinkedHashMap<String, String>()
    var dateTakenMs: Long? = null
    runCatching {
        val exif = when {
            file != null && file.exists() -> ExifInterface(file.absolutePath)
            localUri != null -> context.contentResolver.openInputStream(localUri)?.use { ExifInterface(it) }
            else -> null
        } ?: return@runCatching
        for (tag in EXIF_TAGS_TO_PRESERVE) {
            val v = exif.getAttribute(tag)
            if (!v.isNullOrBlank()) attrs[tag] = v
        }
        dateTakenMs = exif.dateTimeOriginal ?: exif.dateTime
    }
    if (dateTakenMs == null && localUri != null && localUri.scheme == "content") {
        runCatching {
            context.contentResolver.query(
                localUri,
                arrayOf(MediaStore.Images.Media.DATE_TAKEN),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val v = cursor.getLong(0)
                    if (v > 0L) dateTakenMs = v
                }
            }
        }
    }
    return OriginalExifSnapshot(attributes = attrs, dateTakenMs = dateTakenMs)
}

private fun applyRotateFlip(base: Bitmap, rotationDeg: Int, flipH: Boolean, flipV: Boolean): Bitmap {
    val m = Matrix().apply {
        if (rotationDeg != 0) postRotate(rotationDeg.toFloat())
        postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
    }
    return Bitmap.createBitmap(base, 0, 0, base.width, base.height, m, true)
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            m.postRotate(90f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            m.postRotate(270f)
            m.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

private fun computeInSampleSize(width: Int, height: Int, maxPx: Int): Int {
    var inSampleSize = 1
    var maxDim = maxOf(width, height)
    while (maxDim / 2 >= maxPx) {
        inSampleSize *= 2
        maxDim /= 2
    }
    return inSampleSize
}

private fun decodeEditorBitmap(context: Context, uri: Uri, maxPx: Int = 2200): Bitmap? {
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        applyExifOrientation(decoded, orientation)
    }
}

private fun decodeEditorBitmap(file: File, maxPx: Int = 2200): Bitmap? {
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        applyExifOrientation(decoded, orientation)
    }
}

private fun saveBitmapToMediaStore(
    context: Context,
    bitmap: Bitmap,
    displayName: String,
    originalExif: OriginalExifSnapshot
) {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LuminaGallery")
            originalExif.dateTakenMs?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: throw IOException("Failed to create MediaStore entry")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)) {
                    throw IOException("JPEG compression failed")
                }
            } ?: throw IOException("Failed to open output stream")

            // Restore original EXIF tags (date, camera, GPS) with normalized orientation
            if (originalExif.attributes.isNotEmpty()) {
                runCatching {
                    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        originalExif.attributes.forEach { (k, v) -> exif.setAttribute(k, v) }
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL.toString()
                        )
                        exif.saveAttributes()
                    }
                }
            }

            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LuminaGallery")
        if (!dir.exists()) dir.mkdirs()
        val outFile = File(dir, displayName)
        outFile.outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)) {
                throw IOException("JPEG compression failed")
            }
        }
        if (originalExif.attributes.isNotEmpty()) {
            runCatching {
                val exif = ExifInterface(outFile.absolutePath)
                originalExif.attributes.forEach { (k, v) -> exif.setAttribute(k, v) }
                exif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString()
                )
                exif.saveAttributes()
            }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(outFile.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
    }
}

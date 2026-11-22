package com.luminagallery.drive.ui.components

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.domain.model.GeoTaggedMediaItem
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

@Immutable
data class MapPhotoCluster(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val items: List<GeoTaggedMediaItem>
) {
    val coverItem get() = items.first().item
    val count get() = items.size
}

private const val STYLE_OPENFREEMAP_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_OPENFREEMAP_DARK = "https://tiles.openfreemap.org/styles/dark"

/**
 * Flagship 120Hz GPU-accelerated Vector Photo Map powered by MapLibre Native + OpenFreeMap
 * (100% API-key-free, zero watermarks, silky native C++ fling/pinch/scroll-wheel/rotate/tilt controls!):
 *  - Defaults to sleek AMOLED dark vector streets (`dark`) matching Samsung One UI Gallery,
 *    with 1-tap toggle to crisp pastel daytime vector streets (`liberty`).
 *  - `initialFocusItems` lets the Locations hero map frame your #1 country (e.g. Netherlands)
 *    nicely while keeping ALL worldwide geotagged pins seamlessly available when panning/zooming!
 *  - Hooks `MapView`'s direct native camera & render-frame listeners (`OnCameraIsChanging`,
 *    `OnCameraDidChange`, `OnDidFinishRenderingFrame`) + live projection scale so mouse-wheel
 *    scrolling, pinch-zooming, panning, flinging, rotating, and tilting ALL update photo clusters
 *    & screen positions smoothly on EVERY frame (even when MapLibre's CameraChangeDispatcher is idle!).
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun SamsungPhotoMap(
    geoItems: List<GeoTaggedMediaItem>,
    selectedClusterId: String?,
    onSelectCluster: (MapPhotoCluster?) -> Unit,
    modifier: Modifier = Modifier,
    initialFocusItems: List<GeoTaggedMediaItem> = geoItems,
    cameraPaddingBottomPx: Int = 0,
    isMapExpanded: Boolean = false,
    onUserInteractMap: (() -> Unit)? = null,
    onToggleExpandMap: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    // Initialize MapLibre native library once cleanly
    remember(context) {
        MapLibre.getInstance(context.applicationContext)
        true
    }

    var isDarkStyle by remember { mutableStateOf(true) }
    var mapLibreRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraTick by remember { mutableIntStateOf(0) }
    var currentZoom by remember { mutableDoubleStateOf(7.0) }
    var hasFittedInitial by remember(initialFocusItems) { mutableStateOf(false) }

    // Camera fingerprint to avoid idle recompositions while guaranteeing zero missed frames
    var lastProjCenterLat by remember { mutableDoubleStateOf(Double.NaN) }
    var lastProjCenterLon by remember { mutableDoubleStateOf(Double.NaN) }
    var lastProjZoom by remember { mutableDoubleStateOf(Double.NaN) }
    var lastProjBearing by remember { mutableDoubleStateOf(Double.NaN) }
    var lastProjTilt by remember { mutableDoubleStateOf(Double.NaN) }

    val latestOnSelectCluster by rememberUpdatedState(onSelectCluster)
    val latestOnUserInteract by rememberUpdatedState(onUserInteractMap)
    val latestFocusItems by rememberUpdatedState(initialFocusItems.ifEmpty { geoItems })
    val latestCameraPaddingBottomPx by rememberUpdatedState(cameraPaddingBottomPx)

    val mapView = remember {
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true) // TextureView respects Compose rounded corners & overlapping sheets cleanly!
            .attributionEnabled(false)
            .logoEnabled(false)
            .compassEnabled(false)
        MapView(context, options).apply {
            onCreate(null)
            // Ensure map touch gestures (pan/pinch/zoom) never get hijacked by parent containers
            // AND notify parent so the Locations folder sheet hides cleanly when focusing on the map!
            setOnTouchListener { v, event ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    latestOnUserInteract?.invoke()
                }
                false
            }
            // Mouse-wheel / trackpad scroll zoom (e.g. Phone Link / DeX) -> also focus map & hide sheet!
            setOnGenericMotionListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                    latestOnUserInteract?.invoke()
                }
                false
            }
        }
    }

    /**
     * Reads LIVE center & pixel-exact zoom directly from MapLibre's C++ NativeMapView projection
     * (never stale `Transform.cameraPosition` cache!) and triggers pin re-projection/re-clustering
     * whenever the camera moved, zoomed, rotated, or tilted.
     */
    fun syncCameraFromMap(map: MapLibreMap, force: Boolean = false) {
        val applySync = {
            val w = mapView.width
            val h = mapView.height
            if (w > 0 && h > 0) {
                val proj = map.projection
                val center = proj.fromScreenLocation(PointF(w * 0.5f, h * 0.5f))
                val cLat = center.latitude.coerceIn(-85.0, 85.0)
                val cLon = center.longitude

                // Measure live world pixel width directly via native `pixelForLatLng` (rotation-invariant!)
                val pCenter = proj.toScreenLocation(LatLng(cLat, cLon))
                val dLon = if (cLon > 179.0) -0.05 else 0.05
                val pEast = proj.toScreenLocation(LatLng(cLat, cLon + dLon))
                val pxPerDeg = hypot((pEast.x - pCenter.x).toDouble(), (pEast.y - pCenter.y).toDouble()) / abs(dLon)
                val worldPx = pxPerDeg * 360.0
                val baseWorldPx = 512.0 * density.density.toDouble().coerceAtLeast(1.0)
                val liveZoom = if (worldPx > 1.0) {
                    (ln(worldPx / baseWorldPx) / ln(2.0)).coerceIn(0.0, 22.0)
                } else {
                    map.cameraPosition.zoom
                }

                val cam = map.cameraPosition
                val changed = force ||
                    !lastProjCenterLat.isFinite() ||
                    abs(cLat - lastProjCenterLat) > 1e-6 ||
                    abs(cLon - lastProjCenterLon) > 1e-6 ||
                    abs(liveZoom - lastProjZoom) > 1e-3 ||
                    abs(cam.bearing - lastProjBearing) > 1e-2 ||
                    abs(cam.tilt - lastProjTilt) > 1e-2

                if (changed) {
                    lastProjCenterLat = cLat
                    lastProjCenterLon = cLon
                    lastProjZoom = liveZoom
                    lastProjBearing = cam.bearing
                    lastProjTilt = cam.tilt
                    currentZoom = liveZoom
                    cameraTick++
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applySync()
        } else {
            mapView.post(applySync)
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val cameraChangingListener = MapView.OnCameraIsChangingListener {
            mapLibreRef?.let { syncCameraFromMap(it) }
        }
        val cameraDidChangeListener = MapView.OnCameraDidChangeListener { _ ->
            mapLibreRef?.let { syncCameraFromMap(it) }
        }
        val renderFrameListener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
            mapLibreRef?.let { syncCameraFromMap(it) }
        }
        mapView.addOnCameraIsChangingListener(cameraChangingListener)
        mapView.addOnCameraDidChangeListener(cameraDidChangeListener)
        mapView.addOnDidFinishRenderingFrameListener(renderFrameListener)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mapView.onStart()
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }
        onDispose {
            mapView.removeOnCameraIsChangingListener(cameraChangingListener)
            mapView.removeOnCameraDidChangeListener(cameraDidChangeListener)
            mapView.removeOnDidFinishRenderingFrameListener(renderFrameListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Bind MapLibreMap once ready
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false
                isCompassEnabled = false
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = true
                isZoomGesturesEnabled = true
                isScrollGesturesEnabled = true
                isDoubleTapGesturesEnabled = true
                isQuickZoomGesturesEnabled = true
                isFlingVelocityAnimationEnabled = true
                isScaleVelocityAnimationEnabled = true
            }
            map.setStyle(Style.Builder().fromUri(if (isDarkStyle) STYLE_OPENFREEMAP_DARK else STYLE_OPENFREEMAP_LIBERTY)) {
                mapLibreRef = map
                syncCameraFromMap(map, force = true)
            }
            map.addOnCameraMoveListener {
                syncCameraFromMap(map)
            }
            map.addOnCameraIdleListener {
                syncCameraFromMap(map)
            }
            map.addOnMapClickListener {
                latestOnSelectCluster(null)
                latestOnUserInteract?.invoke()
                false
            }
        }
    }

    // Apply Light/Dark OpenFreeMap vector style switch smoothly
    LaunchedEffect(isDarkStyle, mapLibreRef) {
        val map = mapLibreRef ?: return@LaunchedEffect
        val targetUri = if (isDarkStyle) STYLE_OPENFREEMAP_DARK else STYLE_OPENFREEMAP_LIBERTY
        if (map.style?.uri != targetUri) {
            map.setStyle(Style.Builder().fromUri(targetUri)) {
                syncCameraFromMap(map, force = true)
            }
        }
    }

    fun fitCameraTo(map: MapLibreMap, focus: List<GeoTaggedMediaItem>, animated: Boolean) {
        if (focus.isEmpty()) return
        val viewW = mapView.width
        val viewH = mapView.height
        if (viewW <= 0 || viewH <= 0) return

        val padSide = with(density) { 40.dp.roundToPx() }.coerceAtMost((viewW * 0.25f).roundToInt())
        val rawPadBottom = with(density) { 28.dp.roundToPx() } + latestCameraPaddingBottomPx
        val padBottom = rawPadBottom.coerceAtMost((viewH * 0.62f).roundToInt())
        val padTop = with(density) { 54.dp.roundToPx() }.coerceAtMost((viewH - padBottom - 80).coerceAtLeast(16))

        val minLat = focus.minOf { it.latitude }
        val maxLat = focus.maxOf { it.latitude }
        val minLon = focus.minOf { it.longitude }
        val maxLon = focus.maxOf { it.longitude }

        val update = if ((maxLat - minLat) < 0.004 && (maxLon - minLon) < 0.004) {
            CameraUpdateFactory.newLatLngZoom(LatLng((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0), 13.5)
        } else {
            val bounds = LatLngBounds.Builder()
                .include(LatLng(minLat, minLon))
                .include(LatLng(maxLat, maxLon))
                .build()
            CameraUpdateFactory.newLatLngBounds(bounds, padSide, padTop, padSide, padBottom)
        }
        if (animated) {
            map.animateCamera(update, 480)
        } else {
            map.moveCamera(update)
            syncCameraFromMap(map, force = true)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(if (isDarkStyle) Color(0xFF14161A) else Color(0xFFE6EFF4))
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // Auto-fit initial camera onto `initialFocusItems` (e.g. Netherlands or selected city!)
        // Wait until the MapView has real measured bounds so `newLatLngBounds` centers dead-on!
        LaunchedEffect(mapLibreRef, latestFocusItems, hasFittedInitial, widthPx, heightPx) {
            val map = mapLibreRef ?: return@LaunchedEffect
            if (!hasFittedInitial && latestFocusItems.isNotEmpty() && widthPx > 0f && heightPx > 0f) {
                mapView.post {
                    if (!hasFittedInitial && mapView.width > 0 && mapView.height > 0) {
                        fitCameraTo(map, latestFocusItems, animated = false)
                        hasFittedInitial = true
                    }
                }
            }
        }

        // 1. GPU-Accelerated Native MapLibre Vector Map
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Dynamic Screen-Space Clustered Samsung Speech-Bubble Photo Pins
        val map = mapLibreRef
        val zoomBucket = (currentZoom * 2.0).roundToInt() / 2.0
        val clusterCellPx = with(density) { 58.dp.toPx() }.toDouble()
        val clusters = remember(geoItems, zoomBucket) {
            buildVectorClusters(
                items = geoItems,
                zoom = zoomBucket,
                density = density.density.toDouble(),
                cellSizePx = clusterCellPx
            )
        }

        // Read `cameraTick` so pins re-project smoothly on EVERY camera & render frame!
        val activeTick = cameraTick
        if (map != null && activeTick >= 0) {
            val pinWidthPx = with(density) { 54.dp.toPx() }
            val pinTotalHeightPx = with(density) { 62.dp.toPx() }
            val projection = map.projection

            for (cluster in clusters) {
                val pt = projection.toScreenLocation(LatLng(cluster.latitude, cluster.longitude))
                val sx = pt.x
                val sy = pt.y
                if (sx < -pinWidthPx || sx > widthPx + pinWidthPx || sy < -pinTotalHeightPx || sy > heightPx + pinTotalHeightPx) {
                    continue
                }
                val isSelected = cluster.id == selectedClusterId
                key(cluster.id) {
                    SamsungPhotoPinMarker(
                        cluster = cluster,
                        isSelected = isSelected,
                        onClick = { onSelectCluster(cluster) },
                        modifier = Modifier
                            .zIndex(if (isSelected) 10f else 1f)
                            .offset {
                                IntOffset(
                                    x = (sx - pinWidthPx / 2f).roundToInt(),
                                    y = (sy - pinTotalHeightPx).roundToInt()
                                )
                            }
                    )
                }
            }
        }

        // 3. Sleek Floating Map Controls (Expand/Collapse + Light/Dark + Recenter)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onToggleExpandMap != null) {
                MapControlCircle(
                    icon = if (isMapExpanded) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                    contentDescription = if (isMapExpanded) "Show folders" else "Expand map",
                    onClick = onToggleExpandMap
                )
            }
            MapControlCircle(
                icon = if (isDarkStyle) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                contentDescription = if (isDarkStyle) "Light map" else "Dark map",
                onClick = { isDarkStyle = !isDarkStyle }
            )
            MapControlCircle(
                icon = Icons.Rounded.MyLocation,
                contentDescription = "Recenter",
                onClick = {
                    mapLibreRef?.let { fitCameraTo(it, latestFocusItems, animated = true) }
                }
            )
        }

        // Subtle OpenFreeMap attribution badge
        Text(
            text = "© OpenFreeMap · OSM",
            fontSize = 9.sp,
            color = if (isDarkStyle) Color.White.copy(alpha = 0.65f) else Color(0xFF303036).copy(alpha = 0.72f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (isDarkStyle) Color.Black.copy(alpha = 0.52f)
                    else Color.White.copy(alpha = 0.76f)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Pixel-faithful Samsung Gallery map photo pin:
 *  - Crisp `52.dp` rounded photo square with `2.5.dp` white frame (or Samsung Blue `#2B7CFF` when active)
 *  - Downward speech-bubble pointer triangle anchored right onto the GPS coordinate
 *  - Centered bold white item count (`810`, `166`, `7`)
 */
@Composable
private fun SamsungPhotoPinMarker(
    cluster: MapPhotoCluster,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val frameColor = if (isSelected) Color(0xFF2B7CFF) else Color.White

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.14f else 1.0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 460f),
        label = "pinScale"
    )

    val thumbReq = remember(cluster.coverItem.id, cluster.coverItem.localContentUri) {
        ImageRequest.Builder(context)
            .data(cluster.coverItem.localContentUri)
            .memoryCacheKey(DriveConstants.stableThumbMemoryKey(cluster.coverItem.id))
            .size(160)
            .build()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .oneUiClickable(pressedScale = 0.90f, hoveredScale = 1.06f, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .shadow(elevation = if (isSelected) 8.dp else 4.dp, shape = RoundedCornerShape(9.dp))
                .clip(RoundedCornerShape(9.dp))
                .background(frameColor)
                .padding(2.5.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF222226)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = thumbReq,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.24f))
                )
                Text(
                    text = cluster.count.toString(),
                    color = Color.White,
                    fontSize = if (cluster.count >= 100) 13.5.sp else 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Canvas(modifier = Modifier.size(width = 12.dp, height = 7.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
                close()
            }
            drawPath(path = path, color = frameColor)
        }
    }
}

@Composable
private fun MapControlCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xE61B1B1F))
            .border(0.8.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .oneUiClickable(pressedScale = 0.88f, hoveredScale = 1.06f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(19.dp)
        )
    }
}

private fun buildVectorClusters(
    items: List<GeoTaggedMediaItem>,
    zoom: Double,
    density: Double,
    cellSizePx: Double
): List<MapPhotoCluster> {
    if (items.isEmpty()) return emptyList()
    // MapLibre uses 512dp logical world at zoom 0 -> scale to screen px cleanly
    val worldPx = 512.0 * density * 2.0.pow(zoom.coerceIn(1.0, 19.0))
    val buckets = LinkedHashMap<Long, MutableList<GeoTaggedMediaItem>>()

    for (g in items) {
        val wx = ((g.longitude + 180.0) / 360.0) * worldPx
        val clampedLat = g.latitude.coerceIn(-85.0511, 85.0511)
        val rad = Math.toRadians(clampedLat)
        val mercN = ln(tan(rad) + 1.0 / cos(rad))
        val wy = (1.0 - mercN / PI) / 2.0 * worldPx

        val bx = floor(wx / cellSizePx).toLong()
        val by = floor(wy / cellSizePx).toLong()
        val key = (bx shl 32) xor (by and 0xffffffffL)
        buckets.getOrPut(key) { mutableListOf() }.add(g)
    }

    return buckets.entries.map { (key, list) ->
        val avgLat = list.sumOf { it.latitude } / list.size
        val avgLon = list.sumOf { it.longitude } / list.size
        MapPhotoCluster(
            id = "pin_${key}_${list.first().item.id}",
            latitude = avgLat,
            longitude = avgLon,
            items = list
        )
    }.sortedBy { it.count }
}

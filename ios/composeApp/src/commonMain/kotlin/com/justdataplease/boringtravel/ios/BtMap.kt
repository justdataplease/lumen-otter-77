package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.map.MapData
import com.justdataplease.boringtravel.ui.map.MapTap
import com.justdataplease.boringtravel.ui.theme.Bt

/** How far the map is: its style still coming, there, or the plain stand-in with the app's own lines and pins. */
enum class MapLoadState { Loading, Ready, RouteOnly }

/**
 * Where the camera goes, as the Android app's BtMap.MapHolder decides it: the first fit once the style is in and there
 * is something to show, again when the room around it changes (the sheet grew) unless the traveller moved the map,
 * on the fit button, and on a box asked for ([focus]). Plain state, so the rules are tested without a screen.
 */
class MapFitter {
    var move by mutableStateOf<MapMove?>(null)
        private set

    /** Where the map is now, as the view last said. */
    var camera by mutableStateOf(MapCamera.World)
        private set
    var width = 0f
        private set
    var height = 0f
        private set

    private var insets = MapInsets()
    private var fitted = false
    private var userMoved = false
    private var focusBox: BBox? = null
    private var bounds: BBox? = null
    private var empty = true
    private var ready = false
    private var pending: Boolean? = null
    private var seq = 0

    /** True once the traveller panned or zoomed since the last fit. */
    val moved: Boolean get() = userMoved

    fun size(w: Float, h: Float) {
        if (w == width && h == height) return
        width = w
        height = h
        if (w > 0f && h > 0f) pending?.let { pending = null; fit(it) }
    }

    fun styleReady() {
        ready = true
        if (!fitted && (!empty || focusBox != null)) fit(animate = false)
    }

    fun data(data: MapData) {
        bounds = data.bounds
        empty = data.isEmpty
        if (!fitted && (!empty || focusBox != null) && ready) fit(animate = false)
    }

    /** The screen's own room ([MapFit.BASE] comes on top); a change refits unless the map was moved by hand. */
    fun padding(p: MapInsets) {
        if (p == insets) return
        insets = p
        if (fitted && !userMoved) fit(animate = true)
    }

    /** Back to all of the data (the fit button). */
    fun fitAll() {
        focusBox = null
        fit(animate = true)
    }

    /** Frames [box] (null keeps the view as it is). */
    fun focus(box: BBox?) {
        if (box == null || box == focusBox) return
        focusBox = box
        if (ready) fit(animate = true) else fitted = false
    }

    fun cameraMoved(c: MapCamera, byGesture: Boolean) {
        camera = c
        if (byGesture) userMoved = true
    }

    private fun fit(animate: Boolean) {
        val box = focusBox ?: bounds ?: return
        fitted = true
        userMoved = false
        if (width <= 0f || height <= 0f) {
            pending = animate
            return
        }
        // A fit keeps the way the map is turned (Android: getCameraForLatLngBounds at the map's bearing).
        move = MapMove(MapFit.camera(box, width, height, MapFit.BASE + insets, camera.bearing), animate, ++seq)
    }

    /** What a tap at [x], [y] (points) reached. */
    fun hit(data: MapData, x: Float, y: Float): MapTap? {
        if (width <= 0f || height <= 0f) return null
        val p = MapProjection(camera, width, height)
        return MapHits.hit(data, MapPins.placed(data, p), p, x, y)
    }
}

/**
 * A map in Compose, as the Android app's ui/map/BtMap.kt: OpenFreeMap's tiles (no key, no per-view cost) in the look's
 * colours, positron on light looks and dark on Trail, Terrain's shading or Earth's pictures ([mapStyle]); the app's
 * routes, pins and "now" on top. [contentPadding] keeps fitted content clear of what lies over the map (the sheet);
 * [fitKey] changes = fit everything again; [focus] frames that box instead (the fit button goes back to everything).
 * A tap on a pin or a route comes back as [onTap].
 *
 * The view is the phone's ([LocalMapKit]: MapLibre on iPhone, a drawn stand-in on the desktop); none: "Map not
 * available". Until the style is in, "Loading map"; without OpenFreeMap's style the first time offline, the plain land
 * with the routes and pins and "Map unavailable · showing saved places" with Try again.
 */
@Composable
fun BtMap(
    data: MapData,
    modifier: Modifier = Modifier,
    showStopNames: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    fitKey: Any? = null,
    focus: BBox? = null,
    interactive: Boolean = true,
    mapStyle: MapStyle = MapStyle.Street,
    /** Android's compactRoute: the opening card's small map (a community post's plan): thin dashed lines and dots. */
    compactRoute: Boolean = false,
    onTap: (MapTap) -> Unit = {},
) {
    val kit = LocalMapKit.current
    if (kit == null) {
        EmptyState("Map not available", "This phone can't draw the map.", modifier)
        return
    }
    val web = LocalWeb.current
    val dark = Bt.tokens.look.dark
    val colors = MapColors.from(Bt.colors)
    var attempt by remember { mutableIntStateOf(0) }
    var style by remember { mutableStateOf<MapStyleBuild?>(null) }
    var loadedJson by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dark, colors, mapStyle, showStopNames, attempt, compactRoute) {
        style = null
        val base = MapBaseStyles.base(web, dark)
        style = MapStyles.build(mapStyle, base, dark, colors, showStopNames, compactRoute)
    }
    val fitter = remember { MapFitter() }
    val latestData by rememberUpdatedState(data)
    val latestTap by rememberUpdatedState(onTap)
    val latestColors by rememberUpdatedState(colors)
    val latestInteractive by rememberUpdatedState(interactive)
    val latestCompact by rememberUpdatedState(compactRoute)
    val events = remember(fitter) {
        MapEvents(
            styleLoaded = { json ->
                loadedJson = json
                fitter.styleReady()
            },
            // MapLibre couldn't load it (no network on the first open): the plain land, so the routes still show.
            styleFailed = { json -> if (style?.json == json && style?.offline == false) style = MapStyles.offline(latestColors, latestCompact) },
            cameraMoved = fitter::cameraMoved,
            tapped = { x, y -> if (latestInteractive) fitter.hit(latestData, x, y)?.let { latestTap(it) } },
            picked = { if (latestInteractive) latestTap(it) },
        )
    }
    val direction = LocalLayoutDirection.current
    val insets = MapInsets(
        contentPadding.calculateLeftPadding(direction).value, contentPadding.calculateTopPadding().value,
        contentPadding.calculateRightPadding(direction).value, contentPadding.calculateBottomPadding().value,
    )
    LaunchedEffect(data) { fitter.data(data) }
    LaunchedEffect(insets) { fitter.padding(insets) }
    LaunchedEffect(fitKey) { if (fitKey != null) fitter.fitAll() }
    LaunchedEffect(focus) { fitter.focus(focus) }
    val sources = remember(data) { MapLayers.sources(data) }
    val scene = MapScene(style, data, sources, colors, MapStyles.maxZoom(mapStyle), interactive, showStopNames, compactRoute)
    val state = when {
        style == null || loadedJson != style?.json -> MapLoadState.Loading
        style?.offline == true -> MapLoadState.RouteOnly
        else -> MapLoadState.Ready
    }
    val density = LocalDensity.current.density
    Box(modifier.onSizeChanged { fitter.size(it.width / density, it.height / density) }) {
        kit.MapView(scene, fitter.move, events, Modifier.fillMaxSize())
        when (state) {
            MapLoadState.Loading -> Column(
                Modifier.align(Alignment.Center).background(Bt.colors.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(color = Bt.colors.primary, strokeWidth = 2.dp)
                Text("Loading map", style = Bt.text.fine)
            }
            // Under what lies over the top of the map (the trip map's chips), not behind it.
            MapLoadState.RouteOnly -> Column(
                Modifier.align(Alignment.TopCenter).padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 12.dp + insets.top.dp)
                    .background(Bt.colors.surface, RoundedCornerShape(16.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Map unavailable · showing saved places", style = Bt.text.sub)
                if (interactive) BtGhostButton("Try again", onClick = { attempt++ })
            }
            MapLoadState.Ready -> Unit
        }
    }
}

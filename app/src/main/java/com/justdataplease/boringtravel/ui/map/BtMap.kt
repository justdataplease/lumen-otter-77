package com.justdataplease.boringtravel.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.justdataplease.boringtravel.clip.ImageryTiles
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.MapStyleLoader
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.ui.components.EmptyState
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtColors
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The map's colours, taken from the current look. [plan] draws the planned route, dashed. */
data class MapColors(
    val land: Int, val sea: Int, val route: Int, val surface: Int, val ink: Int, val muted: Int, val park: Int, val plan: Int,
) {
    companion object {
        fun from(c: BtColors) = MapColors(
            land = c.land.toArgb(), sea = c.sea.toArgb(), route = c.route.toArgb(), surface = c.surface.toArgb(),
            ink = c.ink.toArgb(), muted = c.muted.toArgb(), park = mix(c.land, c.ink, 0.05f).toArgb(), plan = planColor(c).toArgb(),
        )

        /** The planned route's colour: the theme's plan colour, dashed. */
        fun planColor(c: BtColors): Color = c.plan

        private fun mix(a: Color, b: Color, t: Float) = Color(
            a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
        )
    }
}

/**
 * MapLibre with OpenFreeMap tiles (no key, no per-view cost) in Compose: the MapView follows the screen's lifecycle
 * and is destroyed with the composable. Light looks use the quiet "positron" style, Night Route the "dark" one; land
 * and water are recoloured from the look. [mapStyle] adds hill shading (Terrain) or shows satellite pictures (Earth),
 * the same styles as the trip clip's. [contentPadding] keeps fitted content clear of overlays (the sheet).
 * [fitKey] changes = fit the camera to the data again. [focus] frames the camera on that box instead (the World
 * view's "Show on the map"); the fit button goes back to everything. [compactRoute] is the opening card's
 * restrained dot-and-line treatment; detailed maps retain distinct stop, plan and journal pins.
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
    compactRoute: Boolean = false,
    mapStyle: MapStyle = MapStyle.Street,
    onTap: (MapTap) -> Unit = {},
) {
    val context = LocalContext.current
    val holder = remember(compactRoute) { runCatching { MapHolder(context, compactRoute) }.getOrNull() }
    if (holder == null) {
        EmptyState("Map not available", "This phone can't draw the map.", modifier)
        return
    }
    DisposableEffect(holder) { onDispose { holder.destroy() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, holder) {
        val observer = LifecycleEventObserver { _, event -> holder.onLifecycle(event) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val dark = Bt.tokens.look.dark
    val colors = MapColors.from(Bt.colors)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val currentTap by rememberUpdatedState(onTap)
    val padding = with(density) {
        listOf(
            contentPadding.calculateLeftPadding(direction).roundToPx(), contentPadding.calculateTopPadding().roundToPx(),
            contentPadding.calculateRightPadding(direction).roundToPx(), contentPadding.calculateBottomPadding().roundToPx(),
        )
    }
    SideEffect { holder.onTap = { currentTap(it) } }
    LaunchedEffect(holder, interactive) { holder.setInteractive(interactive) }
    LaunchedEffect(holder, dark, colors, mapStyle) { holder.setLook(dark, colors, mapStyle) }
    LaunchedEffect(holder, data, showStopNames) { holder.setData(data, showStopNames) }
    // The sheet opened or closed: refit, unless the traveller has moved the map themselves.
    LaunchedEffect(holder, padding) { holder.setPadding(padding.toIntArray()) }
    LaunchedEffect(holder, fitKey) { if (fitKey != null) holder.fitAll() }
    LaunchedEffect(holder, focus) { holder.focus(focus) }
    Box(modifier) {
        AndroidView(factory = { holder.view }, modifier = Modifier.fillMaxSize())
        when (holder.loadState) {
            MapLoadState.Loading -> Column(
                Modifier.align(Alignment.Center).background(Bt.colors.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(color = Bt.colors.primary, strokeWidth = 2.dp)
                Text("Loading map", style = Bt.text.fine)
            }
            MapLoadState.RouteOnly -> Column(
                Modifier.align(Alignment.TopCenter).padding(12.dp)
                    .background(Bt.colors.surface, RoundedCornerShape(16.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Map unavailable · showing saved places", style = Bt.text.sub)
                if (interactive) BtGhostButton("Try again", onClick = holder::retry)
            }
            MapLoadState.Ready -> Unit
        }
    }
}

private enum class MapLoadState { Loading, Ready, RouteOnly }

/** What the map is drawn with: the look's colours and the map style ([json] = its style JSON, null = by address). */
private data class MapLook(val dark: Boolean, val colors: MapColors, val style: MapStyle, val json: String?)
private data class MapSources(
    val routes: String, val plans: String, val stops: String, val entries: String, val me: String, val planned: String, val bounds: BBox?,
)
private fun sourceData(data: MapData) = MapSources(
    MapGeoJson.routes(data.routes), MapGeoJson.marks(data.plans), MapGeoJson.marks(data.stops),
    MapGeoJson.marks(data.entries), MapGeoJson.point(data.me), MapGeoJson.line(data.planned), data.bounds,
)

/** Owns one MapView: style per look, our sources and layers on top, taps, and fitting the camera. */
private class MapHolder(private val context: Context, private val compactRoute: Boolean) {
    val view: MapView
    var onTap: (MapTap) -> Unit = {}
    var loadState by mutableStateOf(MapLoadState.Loading)
        private set
    private var interactive = true
    private var padding = IntArray(4)
    /** True once the traveller pans or zooms; automatic refits stop then (the fit button still works). */
    private var userMoved = false

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleKey: MapLook? = null
    private var wantedLook: MapLook? = null
    private var defaultMaxZoom: Double? = null
    private var data = MapData()
    private var sources = sourceData(data)
    private var focusBox: BBox? = null
    private var stopNames = false
    private var fitted = false
    private var offline = false
    private var started = false
    private var resumed = false
    private var destroyed = false

    init {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .logoEnabled(false)
            .attributionEnabled(true)
            // Top right under the fit button: the bottom is the sheet's, the top left the chips'.
            .attributionGravity(Gravity.TOP or Gravity.END)
            .attributionMargins(intArrayOf(0, dp(60), dp(20), 0))
            .compassEnabled(false)
            .tiltGesturesEnabled(false)
            .camera(CameraPosition.Builder().target(LatLng(30.0, 10.0)).zoom(1.0).build())
        view = MapView(context, options)
        view.onCreate(null)
        // No network on the first open (no cached style): a plain background, so the route and stops still show.
        view.addOnDidFailLoadingMapListener {
            val ready = map
            val look = styleKey
            if (ready != null && look != null && style == null && !offline) {
                offline = true
                loadState = MapLoadState.RouteOnly
                ready.setStyle(Style.Builder().fromJson(offlineStyle(look.colors))) { loaded -> onStyle(loaded, look) }
            }
        }
        view.getMapAsync { ready ->
            if (destroyed) return@getMapAsync
            map = ready
            defaultMaxZoom = ready.maxZoomLevel
            ready.uiSettings.setAllGesturesEnabled(interactive)
            ready.addOnMapClickListener { point -> tap(ready, point) }
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMoved = true
            }
            wantedLook?.let { loadStyle(ready, it) }
        }
    }

    fun onLifecycle(event: Lifecycle.Event) {
        if (destroyed) return
        when (event) {
            Lifecycle.Event.ON_START -> if (!started) { view.onStart(); started = true }
            Lifecycle.Event.ON_RESUME -> if (!resumed) { if (!started) { view.onStart(); started = true }; view.onResume(); resumed = true }
            Lifecycle.Event.ON_PAUSE -> if (resumed) { view.onPause(); resumed = false }
            Lifecycle.Event.ON_STOP -> { if (resumed) { view.onPause(); resumed = false }; if (started) { view.onStop(); started = false } }
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }

    fun destroy() {
        if (destroyed) return
        if (resumed) view.onPause()
        if (started) view.onStop()
        view.onDestroy()
        destroyed = true
        resumed = false
        started = false
        map = null
        style = null
    }

    /** Terrain and Earth first fetch OpenFreeMap's style to build on (cached); Street loads it by its address. */
    suspend fun setLook(dark: Boolean, colors: MapColors, mapStyle: MapStyle) {
        val json = if (mapStyle == MapStyle.Street) null else MapStyleLoader.json(context, mapStyle, dark)
        if (destroyed) return
        val look = MapLook(dark, colors, mapStyle, json)
        wantedLook = look
        val ready = map ?: return
        if (styleKey != look) loadStyle(ready, look)
    }

    fun setInteractive(value: Boolean) {
        interactive = value
        map?.uiSettings?.setAllGesturesEnabled(value)
    }

    fun retry() {
        val ready = map ?: return
        wantedLook?.let { loadStyle(ready, it) }
    }

    private fun loadStyle(ready: MapLibreMap, look: MapLook) {
        styleKey = look
        style = null
        offline = false
        loadState = MapLoadState.Loading
        // Satellite pictures have no detail past their last zoom: allow a little more, then stop.
        val most = defaultMaxZoom
        if (look.style == MapStyle.Earth) ready.setMaxZoomPreference(ImageryTiles.MAX_ZOOM + EARTH_EXTRA_ZOOM)
        else if (most != null) ready.setMaxZoomPreference(most)
        ready.setStyle(MapStyleLoader.builder(look.json, look.dark)) { loaded -> onStyle(loaded, look) }
    }

    private fun onStyle(loaded: Style, look: MapLook) {
        if (destroyed || styleKey != look) return
        val colors = look.colors
        recolour(loaded, colors)
        addLayers(loaded, colors)
        style = loaded
        loadState = if (offline) MapLoadState.RouteOnly else MapLoadState.Ready
        showData()
        if (!fitted && (!data.isEmpty || focusBox != null)) fit(animate = false)
    }

    /** Frames the camera on [box] (null keeps the current view). */
    fun focus(box: BBox?) {
        if (box == null || box == focusBox) return
        focusBox = box
        if (map != null) fit(animate = style != null) else fitted = false
    }

    /** Back to all of the data (the fit button). */
    fun fitAll() {
        focusBox = null
        fit(animate = true)
    }

    suspend fun setData(data: MapData, stopNames: Boolean) {
        val encoded = withContext(Dispatchers.Default) { sourceData(data) }
        val namesChanged = stopNames != this.stopNames
        this.data = data
        this.sources = encoded
        this.stopNames = stopNames
        if (namesChanged) style?.getLayer(L_STOP_NAMES)?.setProperties(PropertyFactory.visibility(namesVisibility()))
        showData()
        if (!fitted && (!data.isEmpty || focusBox != null) && style != null) fit(animate = false)
    }

    fun setPadding(p: IntArray) {
        if (p.contentEquals(padding)) return
        padding = p
        if (fitted && !userMoved) fit(animate = true)
    }

    /** Moves the camera to show all of the data (zoom capped, so one stop isn't shown at street level). */
    fun fit(animate: Boolean) {
        val ready = map ?: return
        val box = (focusBox ?: sources.bounds)?.let { if (it.isPoint) it.expand(POINT_SPAN_M) else it } ?: return
        fitted = true
        userMoved = false
        view.doOnLayout {
            if (destroyed) return@doOnLayout
            val p = intArrayOf(dp(48) + padding[0], dp(56) + padding[1], dp(48) + padding[2], dp(40) + padding[3])
            val bounds = LatLngBounds.from(box.maxLat, box.maxLng, box.minLat, box.minLng)
            val camera = ready.getCameraForLatLngBounds(bounds, p)?.let { c ->
                if (c.zoom > MAX_FIT_ZOOM) CameraPosition.Builder(c).zoom(MAX_FIT_ZOOM).build() else c
            } ?: return@doOnLayout
            val update = CameraUpdateFactory.newCameraPosition(camera)
            if (animate) ready.animateCamera(update, 600) else ready.moveCamera(update)
        }
    }

    private fun recolour(style: Style, colors: MapColors) {
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            // Provider labels must follow the recoloured land, especially in Night Route.
            // Our route, stop and journal layers keep their own independently defined styling.
            if (id.startsWith("bt-")) continue
            when {
                layer is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(colors.land))
                layer is FillLayer && (id.contains("water") || id.contains("ocean")) -> layer.setProperties(PropertyFactory.fillColor(colors.sea))
                layer is LineLayer && id.startsWith("waterway") -> layer.setProperties(PropertyFactory.lineColor(colors.sea))
                layer is FillLayer && (id.startsWith("landcover") || id.startsWith("landuse") || id.startsWith("park")) ->
                    layer.setProperties(PropertyFactory.fillColor(colors.park))
                layer is SymbolLayer -> layer.setProperties(
                    PropertyFactory.textColor(colors.ink),
                    PropertyFactory.textHaloColor(colors.land),
                    PropertyFactory.textHaloWidth(1.25f),
                    PropertyFactory.textHaloBlur(0.5f),
                )
            }
        }
    }

    private fun addLayers(style: Style, c: MapColors) {
        style.addSource(GeoJsonSource(S_PLANNED))
        style.addSource(GeoJsonSource(S_ROUTES))
        style.addSource(GeoJsonSource(S_PLANS))
        style.addSource(GeoJsonSource(S_STOPS))
        style.addSource(GeoJsonSource(S_ENTRIES))
        style.addSource(GeoJsonSource(S_ME))
        if (!compactRoute) {
            style.addImage(IMG_ENTRY, entryPin(c))
            style.addImage(IMG_STOP, placePin(c, planned = false))
            style.addImage(IMG_PLAN, placePin(c, planned = true))
        }
        // The planned route, under the travelled one: dashed in the plan colour.
        style.addLayer(
            LineLayer(L_PLANNED, S_PLANNED).withProperties(
                PropertyFactory.lineColor(c.plan), PropertyFactory.lineWidth(2.6f), PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineDasharray(arrayOf(2.2f, 2.2f)), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        val jump = Expression.eq(Expression.get(MapGeoJson.P_JUMP), Expression.literal(true))
        style.addLayer(
            LineLayer(L_ROUTE_JUMP, S_ROUTES).withFilter(jump).withProperties(
                PropertyFactory.lineColor(c.route), PropertyFactory.lineWidth(if (compactRoute) 2f else 2.5f),
                PropertyFactory.lineOpacity(if (compactRoute) 0.72f else 0.8f),
                PropertyFactory.lineDasharray(if (compactRoute) arrayOf(3f, 2.5f) else arrayOf(2f, 2f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(L_ROUTE, S_ROUTES).withFilter(Expression.not(jump)).withProperties(
                PropertyFactory.lineColor(c.route), PropertyFactory.lineWidth(if (compactRoute) 2f else 4f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).also { layer ->
                if (compactRoute) layer.setProperties(
                    PropertyFactory.lineDasharray(arrayOf(3f, 2.5f)), PropertyFactory.lineOpacity(0.72f),
                )
            },
        )
        if (compactRoute) {
            // The opening card uses one normalized dot treatment over actual saved coordinates.
            // Source geometry stays unchanged; no connector is invented when no route was recorded.
            listOf(L_PLANS to S_PLANS, L_STOPS to S_STOPS, L_ENTRIES to S_ENTRIES).forEach { (id, source) ->
                style.addLayer(CircleLayer(id, source).withProperties(
                    PropertyFactory.circleRadius(3.2f), PropertyFactory.circleColor(c.route),
                    PropertyFactory.circleStrokeColor(c.surface), PropertyFactory.circleStrokeWidth(1.2f),
                    PropertyFactory.circleOpacity(0.9f),
                ))
            }
        } else {
            style.addLayer(
                SymbolLayer(L_PLANS, S_PLANS).withProperties(
                    PropertyFactory.iconImage(IMG_PLAN), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(false), PropertyFactory.iconPadding(5f),
                ),
            )
            style.addLayer(
                SymbolLayer(L_STOPS, S_STOPS).withProperties(
                    PropertyFactory.iconImage(IMG_STOP), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(false), PropertyFactory.iconPadding(6f),
                ),
            )
        }
        style.addLayer(
            SymbolLayer(L_STOP_NAMES, S_STOPS).withProperties(
                PropertyFactory.textField(Expression.get(MapGeoJson.P_NAME)), PropertyFactory.textFont(arrayOf(FONT)),
                PropertyFactory.textSize(12f), PropertyFactory.textColor(c.ink), PropertyFactory.textHaloColor(c.surface),
                PropertyFactory.textHaloWidth(1.5f), PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                PropertyFactory.textOffset(arrayOf(0.9f, 0f)), PropertyFactory.textOptional(true),
                PropertyFactory.visibility(namesVisibility()),
            ),
        )
        if (!compactRoute) style.addLayer(
            SymbolLayer(L_ENTRIES, S_ENTRIES).withProperties(
                PropertyFactory.iconImage(IMG_ENTRY), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        // "Now": a ring in the route colour around the latest point.
        style.addLayer(
            CircleLayer(L_ME_HALO, S_ME).withProperties(
                PropertyFactory.circleRadius(15f), PropertyFactory.circleColor(c.route), PropertyFactory.circleOpacity(0.12f),
                PropertyFactory.circleStrokeColor(c.route), PropertyFactory.circleStrokeWidth(2.5f), PropertyFactory.circleStrokeOpacity(0.6f),
            ),
        )
        style.addLayer(
            CircleLayer(L_ME, S_ME).withProperties(
                PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(c.route),
                PropertyFactory.circleStrokeColor(c.surface), PropertyFactory.circleStrokeWidth(2.5f),
            ),
        )
    }

    private fun namesVisibility() = if (stopNames && !offline) Property.VISIBLE else Property.NONE

    private fun showData() {
        val s = style ?: return
        s.getSourceAs<GeoJsonSource>(S_PLANNED)?.setGeoJson(sources.planned)
        s.getSourceAs<GeoJsonSource>(S_ROUTES)?.setGeoJson(sources.routes)
        s.getSourceAs<GeoJsonSource>(S_PLANS)?.setGeoJson(sources.plans)
        s.getSourceAs<GeoJsonSource>(S_STOPS)?.setGeoJson(sources.stops)
        s.getSourceAs<GeoJsonSource>(S_ENTRIES)?.setGeoJson(sources.entries)
        s.getSourceAs<GeoJsonSource>(S_ME)?.setGeoJson(sources.me)
    }

    /** Entries first (pins sit on top), then stops, plan items, then the route line. */
    private fun tap(map: MapLibreMap, point: LatLng): Boolean {
        if (style == null || !interactive) return false
        val screen = map.projection.toScreenLocation(point)
        val reach = dp(16).toFloat()
        val area = RectF(screen.x - reach, screen.y - reach, screen.x + reach, screen.y + reach)
        fun first(layer: String) = map.queryRenderedFeatures(area, layer).firstOrNull()
        val hit: MapTap? = first(L_ENTRIES)?.getStringProperty(MapGeoJson.P_ID)?.let { MapTap.Entry(it) }
            ?: first(L_STOPS)?.let { f -> f.getStringProperty(MapGeoJson.P_ID)?.let { MapTap.StopMark(it, f.getStringProperty(MapGeoJson.P_TRIP)) } }
            ?: first(L_PLANS)?.getStringProperty(MapGeoJson.P_ID)?.let { MapTap.Plan(it) }
            ?: (first(L_ROUTE) ?: first(L_ROUTE_JUMP))?.getStringProperty(MapGeoJson.P_TRIP)?.let { MapTap.Trip(it) }
        if (hit != null) onTap(hit)
        return hit != null
    }

    /** A pin in the look's ink with a surface centre, tip at the bottom. */
    private fun entryPin(c: MapColors): Bitmap {
        val w = dp(22)
        val h = dp(29)
        val stroke = dp(2).toFloat()
        val r = w / 2f - stroke
        val cx = w / 2f
        val cy = w / 2f
        val shape = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            op(Path().apply { moveTo(cx - r * 0.7f, cy + r * 0.7f); lineTo(cx, h - stroke); lineTo(cx + r * 0.7f, cy + r * 0.7f); close() }, Path.Op.UNION)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = c.ink
            canvas.drawPath(shape, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = c.surface
            canvas.drawPath(shape, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r * 0.36f, paint)
        }
    }

    /** Readable place markers: a location pin for a recorded stop, a flag for a planned place. */
    private fun placePin(c: MapColors, planned: Boolean): Bitmap {
        val unit = context.resources.displayMetrics.density
        return Bitmap.createBitmap(dp(30), dp(38), Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            canvas.scale(unit, unit)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val pin = Path().apply {
                moveTo(15f, 36f)
                cubicTo(10f, 29f, 2f, 24f, 2f, 15f)
                cubicTo(2f, -1f, 28f, -1f, 28f, 15f)
                cubicTo(28f, 24f, 20f, 29f, 15f, 36f)
                close()
            }
            paint.color = c.surface
            canvas.drawPath(pin, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f
            paint.color = if (planned) c.muted else c.route
            canvas.drawPath(pin, paint)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            if (planned) {
                canvas.drawLine(11f, 10f, 11f, 23f, paint)
                canvas.drawPath(Path().apply {
                    moveTo(11f, 10f); lineTo(21f, 10f); lineTo(19f, 14f); lineTo(21f, 18f); lineTo(11f, 18f)
                }, paint)
            } else {
                canvas.drawPath(Path().apply {
                    moveTo(9f, 16f); lineTo(13f, 20f); lineTo(21f, 11f)
                }, paint)
            }
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        /** How far past the satellite pictures' last zoom the traveller may still zoom in on Earth. */
        private const val EARTH_EXTRA_ZOOM = 2.0

        /** Just the look's land colour: no tiles, no glyphs (so no stop names). */
        private fun offlineStyle(c: MapColors): String {
            val land = "#%06X".format(c.land and 0xFFFFFF)
            return """{"version":8,"name":"offline","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$land"}}]}"""
        }
        private const val FONT = "Noto Sans Regular"
        /** One point alone is shown with this much around it. */
        private const val POINT_SPAN_M = 1_500.0
        private const val MAX_FIT_ZOOM = 15.0

        private const val S_ROUTES = "bt-routes"
        private const val S_PLANNED = "bt-planned"
        private const val L_PLANNED = "bt-planned-line"
        private const val S_PLANS = "bt-plans"
        private const val S_STOPS = "bt-stops"
        private const val S_ENTRIES = "bt-entries"
        private const val S_ME = "bt-me"
        private const val L_ROUTE = "bt-route"
        private const val L_ROUTE_JUMP = "bt-route-jump"
        private const val L_PLANS = "bt-plans"
        private const val L_STOPS = "bt-stops"
        private const val L_STOP_NAMES = "bt-stop-names"
        private const val L_ENTRIES = "bt-entries"
        private const val L_ME_HALO = "bt-me-halo"
        private const val L_ME = "bt-me"
        private const val IMG_ENTRY = "bt-entry-pin"
        private const val IMG_STOP = "bt-stop-pin"
        private const val IMG_PLAN = "bt-plan-pin"
    }
}

package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.ios.MapPictures.drawPinAt
import com.justdataplease.boringtravel.ios.resources.Res
import com.justdataplease.boringtravel.ui.map.MapData
import com.justdataplease.boringtravel.ui.map.MapTap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The map view itself, behind one small interface: MapLibre on iPhone (written in Swift, iosApp/BoringTravel/MapLibreMap.swift,
 * reached through iosMain's NativeMap), a drawn stand-in on the desktop and in the tests. Everything the screens decide
 * (the style, the data, where the camera goes, what a tap hit) is common code in BtMap; the view only draws it and
 * says what happened.
 */

/** Everything a map view draws. [style] is null while it is being made. */
@Immutable
class MapScene(
    val style: MapStyleBuild?,
    val data: MapData,
    /** What each of the app's sources holds ([MapLayers.sources]). */
    val sources: Map<String, String>,
    val colors: MapColors,
    val maxZoom: Double,
    val interactive: Boolean,
    val showStopNames: Boolean,
    /** A community post's small map (Android's compactRoute): thin dashed lines, a dot per place (round 24). */
    val compact: Boolean = false,
)

/** What the view tells the map: its style is in (or couldn't load), the camera moved, a tap (in points). */
class MapEvents(
    val styleLoaded: (json: String) -> Unit,
    val styleFailed: (json: String) -> Unit,
    val cameraMoved: (camera: MapCamera, byGesture: Boolean) -> Unit,
    val tapped: (x: Float, y: Float) -> Unit,
    /** One pin chosen as itself (the stand-in's pin nodes, for a screen reader and the tests). */
    val picked: (MapTap) -> Unit = {},
)

/** A phone's map view. */
interface MapKit {
    /** Draws [scene], goes where [move] says when its number changes, and reports through [events]. */
    @Composable
    fun MapView(scene: MapScene, move: MapMove?, events: MapEvents, modifier: Modifier)
}

/** The map view the screens use: the drawn stand-in unless the phone gives its own; null when the phone has none. */
val LocalMapKit = staticCompositionLocalOf<MapKit?> { DrawnMapKit }

/**
 * The desktop's map: no tiles, the same pins and lines on a plain projection of the countries' outlines (the Android
 * app's Natural Earth file), with the same camera, pans and pinches. Each pin is also a node with its name, so a test
 * taps it as a finger would.
 */
object DrawnMapKit : MapKit {
    @Composable
    override fun MapView(scene: MapScene, move: MapMove?, events: MapEvents, modifier: Modifier) {
        val density = LocalDensity.current.density
        var camera by remember { mutableStateOf(MapCamera.World) }
        var width by remember { mutableStateOf(0f) }
        var height by remember { mutableStateOf(0f) }
        val latestEvents by rememberUpdatedState(events)
        val style = scene.style
        // A drawn map has nothing to load: the style is in as soon as it is there.
        LaunchedEffect(style?.json) { if (style != null) latestEvents.styleLoaded(style.json) }
        LaunchedEffect(move?.seq) {
            val m = move ?: return@LaunchedEffect
            camera = m.camera
            latestEvents.cameraMoved(m.camera, false)
        }
        val land by produceState<List<DoubleArray>>(emptyList()) { value = DrawnLand.load() }
        val text = rememberTextMeasurer()
        val c = scene.colors
        val projection = MapProjection(camera, width, height)
        val pins = if (width > 0f) MapPins.placed(scene.data, projection) else emptyList()
        Box(
            modifier
                .clipToBounds()
                .onSizeChanged { width = it.width / density; height = it.height / density }
                .then(
                    if (!scene.interactive) Modifier else Modifier
                        .pointerInput(scene.maxZoom) {
                            detectTransformGestures { centroid, pan, zoom, rotation ->
                                var next = MapProjection(camera, width, height).panned(pan.x / density, pan.y / density)
                                if (zoom != 1f) next = MapProjection(next, width, height).zoomed(zoom, centroid.x / density, centroid.y / density, scene.maxZoom)
                                // Two fingers turn it, as Android's map and MapLibre's on iPhone (no compass, no tilt).
                                if (rotation != 0f) next = MapProjection(next, width, height).turned(rotation, centroid.x / density, centroid.y / density)
                                camera = next
                                latestEvents.cameraMoved(next, true)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { latestEvents.tapped(it.x / density, it.y / density) })
                        },
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val p = MapProjection(camera, size.width / density, size.height / density)
                fun at(lat: Double, lng: Double) = p.at(lat, lng).let { Offset(it.x * density, it.y * density) }
                drawRect(Color(if (style?.offline == true) c.land else c.sea))
                if (style?.offline != true) {
                    for (ring in land) {
                        val path = Path().apply {
                            fillType = PathFillType.EvenOdd
                            var i = 0
                            while (i + 1 < ring.size) {
                                val o = at(ring[i + 1], ring[i])
                                if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                                i += 2
                            }
                            close()
                        }
                        drawPath(path, Color(c.land))
                        drawPath(path, Color(c.ink).copy(alpha = 0.12f), style = Stroke(0.6f * density))
                    }
                }
                fun line(points: List<Pair<Double, Double>>, color: Int, width: Float, opacity: Float, dash: Float?) {
                    if (points.size < 2) return
                    val path = Path()
                    points.forEachIndexed { i, (lat, lng) -> at(lat, lng).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
                    val w = width * density
                    drawPath(
                        path, Color(color).copy(alpha = opacity),
                        style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash?.let { PathEffect.dashPathEffect(floatArrayOf(it * w, it * w)) }),
                    )
                }
                val d = scene.data
                line(d.planned.map { it.lat to it.lng }, c.plan, 2.6f, 0.85f, 2.2f)
                for (r in d.routes) for (piece in r.pieces) {
                    val pts = piece.points.map { it.lat to it.lng }
                    when {
                        scene.compact -> line(pts, c.route, 2f, 0.72f, 3f)
                        piece.jump -> line(pts, c.route, 2.5f, 0.8f, 2f)
                        else -> line(pts, c.route, 4f, 1f, null)
                    }
                }
                if (scene.compact) {
                    // A dot per place instead of pins, as the style's compact layers draw them.
                    for (m in d.plans + d.stops + d.entries) {
                        val o = at(m.lat, m.lng)
                        drawCircle(Color(c.route).copy(alpha = 0.9f), 3.2f * density, o)
                        drawCircle(Color(c.surface), 3.2f * density, o, style = Stroke(1.2f * density))
                    }
                    return@Canvas
                }
                for (pin in pins.filter { it.kind != PinKind.Entry }) drawPinAt(pin.kind, c, density, pin.left * density, pin.top * density)
                if (scene.showStopNames && style?.offline != true) {
                    for (pin in pins.filter { it.kind == PinKind.Stop && it.mark.title.isNotBlank() }) {
                        val label = text.measure(
                            pin.mark.title,
                            TextStyle(fontSize = 12.sp, color = Color(c.ink), shadow = Shadow(Color(c.surface), Offset.Zero, 3f * density)),
                        )
                        drawText(label, topLeft = Offset((pin.centerX + 12 * 0.9f) * density, pin.bottom * density - label.size.height / 2f))
                    }
                }
                for (pin in pins.filter { it.kind == PinKind.Entry }) drawPinAt(pin.kind, c, density, pin.left * density, pin.top * density)
                d.me?.let { me ->
                    val o = at(me.lat, me.lng)
                    drawCircle(Color(c.route).copy(alpha = 0.12f), 15f * density, o)
                    drawCircle(Color(c.route).copy(alpha = 0.6f), 15f * density, o, style = Stroke(2.5f * density))
                    drawCircle(Color(c.route), 6f * density, o)
                    drawCircle(Color(c.surface), 6f * density, o, style = Stroke(2.5f * density))
                }
            }
            // Each pin that shows is a node with its name, which opens that pin.
            for (pin in pins) {
                val name = pin.mark.title.ifBlank { pin.kind.name }
                val tap = when (pin.kind) {
                    PinKind.Stop -> MapTap.StopMark(pin.mark.id, pin.mark.tripId)
                    PinKind.Plan -> MapTap.Plan(pin.mark.id)
                    PinKind.Entry -> MapTap.Entry(pin.mark.id)
                }
                Box(
                    Modifier.offset(pin.left.dp, pin.top.dp).size(pin.kind.width.dp, pin.kind.height.dp)
                        .semantics { contentDescription = name }
                        .clickable(enabled = scene.interactive, role = Role.Button) { latestEvents.picked(tap) },
                )
            }
        }
    }
}

/** The countries' outer rings and holes (lng, lat pairs) from the bundled Natural Earth outlines, read once. */
object DrawnLand {
    private var rings: List<DoubleArray>? = null

    suspend fun load(): List<DoubleArray> = rings ?: runCatching {
        val root = Json.parseToJsonElement(Res.readBytes("files/geo/countries.json").decodeToString()).jsonObject
        root["c"]!!.jsonArray.flatMap { country ->
            (country as JsonObject)["p"]!!.jsonArray.flatMap { poly ->
                (poly as JsonArray).map { ring -> ring.jsonArray.let { a -> DoubleArray(a.size) { a[it].jsonPrimitive.double } } }
            }
        }
    }.getOrDefault(emptyList()).also { rings = it }
}

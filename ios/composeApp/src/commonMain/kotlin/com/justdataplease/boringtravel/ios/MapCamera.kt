package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.Mercator
import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.ui.map.MapData
import com.justdataplease.boringtravel.ui.map.MapMark
import com.justdataplease.boringtravel.ui.map.MapTap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The camera's rules, the same on iPhone (MapLibre) and on the desktop's drawn stand-in: where a fit puts the camera
 * (the Android app's BtMap.fit: zoom 15 at most, a point shown with 1.5 km around it, room kept for the chips and the
 * sheet), where a point lands on screen, which pins show where they would cover each other (MapLibre's icon-padding
 * collisions), and what a tap reaches (16 points around the finger, notes first, then stops, the plan's places and the
 * routes), all on the map as it is turned (pins stand upright on screen, as MapLibre draws them). Every length is in
 * points (dp).
 */

/**
 * Where the map looks: its centre, MapLibre's zoom (the world is 512 × 2^zoom points wide) and its [bearing], the
 * compass direction at the top of the screen in degrees clockwise from north (MapLibre's direction; Android's map turns
 * with two fingers, as this one does since the second parity round: 0 is north up).
 */
data class MapCamera(val lat: Double, val lng: Double, val zoom: Double, val bearing: Double = 0.0) {
    companion object {
        /** Before anything is fitted: the world, as BtMap opens. */
        val World = MapCamera(30.0, 10.0, 1.0)
    }
}

/** Room around fitted content, in points: left, top, right, bottom. */
data class MapInsets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    operator fun plus(o: MapInsets) = MapInsets(left + o.left, top + o.top, right + o.right, bottom + o.bottom)
}

/** A move asked of the map: [seq] counts them, so the same camera asked twice still moves. */
data class MapMove(val camera: MapCamera, val animate: Boolean, val seq: Int)

/** A point on the map's screen, in points from its top left. */
data class ScreenPoint(val x: Float, val y: Float)

/**
 * Web Mercator on screen for a [camera] on a map [width] × [height] points, turned by the camera's bearing (MapLibre
 * turns the map about its centre, the bearing's direction at the top); no tilt (the Android app's map has none).
 */
class MapProjection(val camera: MapCamera, val width: Float, val height: Float) {
    /** Points per world unit. */
    val scale: Double = TILE * 2.0.pow(camera.zoom)
    private val cx = Mercator.x(camera.lng)
    private val cy = Mercator.y(camera.lat)
    private val cos = cos(camera.bearing * PI / 180.0)
    private val sin = sin(camera.bearing * PI / 180.0)

    /** Where [lng] would be across the screen with north up (the camera's bearing left out). */
    fun x(lng: Double): Float = (width / 2 + (Mercator.x(lng) - cx) * scale).toFloat()

    /** Where [lat] would be down the screen with north up (the camera's bearing left out). */
    fun y(lat: Double): Float = (height / 2 + (Mercator.y(lat) - cy) * scale).toFloat()

    /** Where [lat], [lng] is on screen, the map turned by the camera's bearing. */
    fun at(lat: Double, lng: Double): ScreenPoint {
        val dx = (Mercator.x(lng) - cx) * scale
        val dy = (Mercator.y(lat) - cy) * scale
        return ScreenPoint((width / 2 + dx * cos + dy * sin).toFloat(), (height / 2 - dx * sin + dy * cos).toFloat())
    }

    /** A move of [sx], [sy] points on the turned screen as world units (north up). */
    private fun toWorld(sx: Double, sy: Double): Pair<Double, Double> = (sx * cos - sy * sin) / scale to (sx * sin + sy * cos) / scale

    /** The camera after the content moved by [dx], [dy] points (a pan). */
    fun panned(dx: Float, dy: Float): MapCamera {
        val (wx, wy) = toWorld(dx.toDouble(), dy.toDouble())
        return camera.copy(lat = Mercator.lat((cy - wy).coerceIn(0.0, 1.0)), lng = wrap(Mercator.lng(cx - wx)))
    }

    /** The camera zoomed by [factor] around the point [fx], [fy] (a pinch), within [maxZoom]. */
    fun zoomed(factor: Float, fx: Float, fy: Float, maxZoom: Double): MapCamera {
        val zoom = (camera.zoom + ln(factor.toDouble()) / ln(2.0)).coerceIn(0.0, maxZoom)
        val (ox, oy) = toWorld((fx - width / 2).toDouble(), (fy - height / 2).toDouble())
        val wx = cx + ox
        val wy = cy + oy
        val k = scale / (TILE * 2.0.pow(zoom))
        val nx = wx - ox * k
        val ny = (wy - oy * k).coerceIn(0.0, 1.0)
        return MapCamera(Mercator.lat(ny), wrap(Mercator.lng(nx)), zoom, camera.bearing)
    }

    /**
     * The camera turned by [degrees] (clockwise: the map's content turns that way on screen) around the point [fx], [fy]
     * (a two-finger twist), the point under the fingers staying where it is.
     */
    fun turned(degrees: Float, fx: Float, fy: Float): MapCamera {
        val (ox, oy) = toWorld((fx - width / 2).toDouble(), (fy - height / 2).toDouble())
        val bearing = (camera.bearing - degrees).mod(360.0)
        val next = MapProjection(camera.copy(bearing = bearing), width, height)
        val (nx, ny) = next.toWorld((fx - width / 2).toDouble(), (fy - height / 2).toDouble())
        val x = cx + ox - nx
        val y = (cy + oy - ny).coerceIn(0.0, 1.0)
        return MapCamera(Mercator.lat(y), wrap(Mercator.lng(x)), camera.zoom, bearing)
    }

    private fun wrap(lng: Double): Double = ((lng + 180.0).mod(360.0)) - 180.0

    companion object {
        /** MapLibre's tile size in points. */
        const val TILE = 512.0
    }
}

object MapFit {
    /** The closest a fit goes (BtMap's MAX_FIT_ZOOM). */
    const val MAX_FIT_ZOOM = 15.0

    /** One point alone is shown with this much around it (BtMap's POINT_SPAN_M). */
    const val POINT_SPAN_M = 1_500.0

    /** Room BtMap always keeps around fitted content (points), before the screen's own (the chips, the sheet). */
    val BASE = MapInsets(left = 48f, top = 56f, right = 48f, bottom = 40f)

    /** The box to fit: [box] itself, or 1.5 km around it when it is one point. */
    fun frame(box: BBox): BBox = if (box.isPoint) box.expand(POINT_SPAN_M) else box

    /**
     * The camera that shows [box] inside a [width] × [height] map with [insets] kept clear, the map turned to [bearing]
     * (a fit keeps the way the map was turned, as MapLibre's getCameraForLatLngBounds on Android does): the largest zoom
     * at which the box, turned, fits the free part (15 at most), the box in the middle of that part.
     */
    fun camera(box: BBox, width: Float, height: Float, insets: MapInsets, bearing: Double = 0.0): MapCamera {
        val b = frame(box)
        val x0 = Mercator.x(b.minLng)
        val x1 = Mercator.x(b.maxLng)
        val y0 = Mercator.y(b.maxLat)
        val y1 = Mercator.y(b.minLat)
        val freeW = max(1f, width - insets.left - insets.right).toDouble()
        val freeH = max(1f, height - insets.top - insets.bottom).toDouble()
        // The box as it lies on the turned screen: its corners turned about its middle, then their extent.
        val c = cos(bearing * PI / 180.0)
        val s = sin(bearing * PI / 180.0)
        val hw = (x1 - x0) / 2
        val hh = (y1 - y0) / 2
        val dx = max(2 * (abs(hw * c) + abs(hh * s)), 1e-12)
        val dy = max(2 * (abs(hw * s) + abs(hh * c)), 1e-12)
        val fit = min(freeW / (dx * MapProjection.TILE), freeH / (dy * MapProjection.TILE))
        val zoom = (ln(fit) / ln(2.0)).coerceIn(0.0, MAX_FIT_ZOOM)
        val scale = MapProjection.TILE * 2.0.pow(zoom)
        // The box's middle goes to the middle of the free part, which is off the map's middle by half the difference
        // of the opposite insets (on the turned screen, so turned back into the world's directions).
        val ox = ((insets.left - insets.right) / 2.0) / scale
        val oy = ((insets.top - insets.bottom) / 2.0) / scale
        val mx = (x0 + x1) / 2 - (ox * c - oy * s)
        val my = (y0 + y1) / 2 - (ox * s + oy * c)
        return MapCamera(Mercator.lat(my.coerceIn(0.0, 1.0)), Mercator.lng(mx), zoom, bearing)
    }
}

/** A pin as it sits on screen: its mark, which layer it is in, and the picture's box (left, top, right, bottom). */
data class PlacedPin(val mark: MapMark, val kind: PinKind, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
}

/** The three pictures: a stop's location pin with a tick, a plan place's flag pin, a note's ink pin. */
enum class PinKind(val image: String, val width: Float, val height: Float, val padding: Float?) {
    Stop(MapLayers.IMG_STOP, 30f, 38f, 6f),
    Plan(MapLayers.IMG_PLAN, 30f, 38f, 5f),
    Entry(MapLayers.IMG_ENTRY, 22f, 29f, null),
}

object MapPins {
    /**
     * The pins that show, as MapLibre places them: stops first (the higher layer), then the plan's places, each hidden
     * when its picture (grown by its icon-padding) would cover one already placed; notes always show, over the rest.
     * A pin's tip is on its point (icon-anchor bottom).
     */
    fun placed(data: MapData, p: MapProjection): List<PlacedPin> {
        val boxes = ArrayList<FloatArray>()
        val out = ArrayList<PlacedPin>()
        fun place(mark: MapMark, kind: PinKind) {
            val (x, y) = p.at(mark.lat, mark.lng)
            // Off screen by more than a pin: not placed (and not hit).
            if (x < -kind.width || x > p.width + kind.width || y < 0 || y > p.height + kind.height) return
            val pin = PlacedPin(mark, kind, x - kind.width / 2, y - kind.height, x + kind.width / 2, y)
            val pad = kind.padding
            if (pad != null) {
                val box = floatArrayOf(pin.left - pad, pin.top - pad, pin.right + pad, pin.bottom + pad)
                if (boxes.any { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) return
                boxes += box
            }
            out += pin
        }
        data.stops.forEach { place(it, PinKind.Stop) }
        data.plans.forEach { place(it, PinKind.Plan) }
        data.entries.forEach { place(it, PinKind.Entry) }
        return out
    }
}

object MapHits {
    /** How far a tap reaches, in points (BtMap's 16 dp). */
    const val REACH = 16f

    /**
     * What a tap at [x], [y] hits, in BtMap's order: a note's pin, else a stop, else a plan place, else a route (solid
     * or a gap); the nearest when several of one kind are in reach. Null: nothing.
     */
    fun hit(data: MapData, pins: List<PlacedPin>, p: MapProjection, x: Float, y: Float): MapTap? {
        fun inReach(pin: PlacedPin) = pin.left <= x + REACH && pin.right >= x - REACH && pin.top <= y + REACH && pin.bottom >= y - REACH
        fun nearest(kind: PinKind) = pins.filter { it.kind == kind && inReach(it) }
            .minByOrNull { (it.centerX - x) * (it.centerX - x) + (it.centerY - y) * (it.centerY - y) }
        nearest(PinKind.Entry)?.let { return MapTap.Entry(it.mark.id) }
        nearest(PinKind.Stop)?.let { return MapTap.StopMark(it.mark.id, it.mark.tripId) }
        nearest(PinKind.Plan)?.let { return MapTap.Plan(it.mark.id) }
        var best: String? = null
        var bestD = Float.MAX_VALUE
        for (route in data.routes) for (piece in route.pieces) {
            val pts = piece.points.map { p.at(it.lat, it.lng) }
            for (i in 1 until pts.size) {
                val d = segmentDistance(x, y, pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y)
                if (d < bestD) { bestD = d; best = route.tripId }
            }
        }
        // The line's half width counts too (4 points wide).
        return if (best != null && bestD <= REACH + 2f) MapTap.Trip(best) else null
    }

    /** Distance in points from ([px], [py]) to the segment from ([ax], [ay]) to ([bx], [by]). */
    fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }

    /** True when the two cameras are the same view (within a hair). */
    fun same(a: MapCamera, b: MapCamera): Boolean =
        abs(a.lat - b.lat) < 1e-9 && abs(a.lng - b.lng) < 1e-9 && abs(a.zoom - b.zoom) < 1e-9 && abs(a.bearing - b.bearing) < 1e-9
}

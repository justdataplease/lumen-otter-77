package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.globe.GlobeMath
import com.justdataplease.boringtravel.model.Vehicle
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Globe map of the clip (owner, 2026-11-08: "also in video make it possible to also use the globe map"): the trip on
 * the app's own globe (the Map tab's look, [com.justdataplease.boringtravel.globe.GlobeRenderer]), seen from space at
 * the start and the end, with the street map draped over the sphere where the camera comes close. Plain maths, tested.
 *
 * The plan keeps its camera in Web Mercator world units like every other map ([Camera]); on the globe the same camera
 * is the sphere seen from straight above its centre, at the Mercator view's own scale there ([radiusPx]), so the
 * follow camera's zoom limits and speeds hold unchanged and the street map, draped onto the sphere, lines up exactly.
 */
object ClipGlobe {
    /** The globe's radius in the space view, as a share of the frame's short side (the Map tab's globe: 0.46). */
    const val SPACE_RADIUS = 0.42

    /**
     * Map zooms (by the frame's short side, as [ZoomLimits] counts them) over which the street map comes in over the
     * globe's own country shapes: from about 2,000 km across to about 700 km across.
     */
    const val DRAPE_FROM_ZOOM = 4.5
    const val DRAPE_TO_ZOOM = 6.0

    /** Map zooms over which the flags of the trip's countries fade out on the way down. */
    const val FLAGS_FROM_ZOOM = 2.5
    const val FLAGS_TO_ZOOM = 3.5

    /** Long legs follow the great circle; from far away they are drawn in steps of at most this many degrees. */
    const val STEP_DEG = 1.0

    /** Latitudes past this are treated as this (the Mercator scale grows without bound towards the poles). */
    private const val MAX_LAT = 85.0

    /** The map zoom of a view [spanX] world units wide on a frame of [aspect] (width / height), by its short side. */
    fun mapZoom(spanX: Double, aspect: Double): Double {
        val short = if (aspect >= 1) spanX / aspect else spanX
        return log2(ClipPlan.DESIGN_SHORT_DP / (ClipPlan.MAP_TILE_PX * short))
    }

    /** How much of the street map shows over the globe's own shapes for [camera] (0 from space, 1 close in). */
    fun drape(camera: Camera, aspect: Double): Double = Ease.window(mapZoom(camera.spanX, aspect), DRAPE_FROM_ZOOM, DRAPE_TO_ZOOM)

    /** How strongly the flags of the trip's countries show for [camera] (1 from space, 0 once close). */
    fun flags(camera: Camera, aspect: Double): Double = 1 - Ease.window(mapZoom(camera.spanX, aspect), FLAGS_FROM_ZOOM, FLAGS_TO_ZOOM)

    /**
     * The globe's radius in pixels for [camera] on a frame [widthPx] wide: the Mercator view's scale at its centre (at
     * latitude φ a world unit is 2π·cos φ Earth radii), so the sphere and a flat map agree around the middle.
     */
    fun radiusPx(camera: Camera, widthPx: Double): Double = widthPx / camera.spanX / (2 * PI * cosLat(Mercator.lat(camera.cy)))

    /** The width (world units) of the space view over latitude [lat] on a frame of [aspect]: the globe [SPACE_RADIUS] of the short side. */
    fun spaceSpan(lat: Double, aspect: Double): Double = max(1.0, aspect) / (2 * PI * cosLat(lat) * SPACE_RADIUS)

    private fun cosLat(lat: Double) = cos(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))

    /** The copy of world column [x] (whole turns of the world apart) nearest to [ref]: keeps a route continuous across 180°. */
    fun near(x: Double, ref: Double): Double = x + Math.rint(ref - x)

    /** Angle between two lat/lng points in radians (great circle). */
    fun angle(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val a = Unit3.of(lat1, lng1)
        val b = Unit3.of(lat2, lng2)
        return a.angleTo(b)
    }

    /**
     * The point a share [t] of the way from ([x1], [y1]) to ([x2], [y2]) (Mercator world units) along the great circle,
     * in world units again, its column kept next to [x1]'s (see [near]). Very short legs (under about 60 m) go
     * straight, where the two are the same.
     */
    fun along(x1: Double, y1: Double, x2: Double, y2: Double, t: Double): Pair<Double, Double> {
        val a = Unit3.of(Mercator.lat(y1), Mercator.lng(x1))
        val b = Unit3.of(Mercator.lat(y2), Mercator.lng(x2))
        val theta = a.angleTo(b)
        if (theta < 1e-5 || t <= 0.0 || t >= 1.0) {
            return when {
                t <= 0.0 -> x1 to y1
                t >= 1.0 -> x2 to y2
                else -> (x1 + (x2 - x1) * t) to (y1 + (y2 - y1) * t)
            }
        }
        val p = Unit3.slerp(a, b, theta, t)
        return near(Mercator.x(p.lng), x1) to Mercator.y(p.lat)
    }

    /** At most this many flags on the globe (the first countries the route reaches). */
    const val MAX_FLAGS = 12

    /**
     * The trip's countries for the globe, as counted codes ([CountryInfo.key]) in the order the route reaches them:
     * looked up with [codeAt] (the bundled outlines) at the stops and along the route (at most about [samples]
     * points), leaving out what a flight only passes over.
     */
    fun countries(plan: ClipPlan, samples: Int = 400, codeAt: (lat: Double, lng: Double) -> String?): List<String> {
        val n = plan.xs.size
        val legs = plan.legVehicle
        val found = ArrayList<Pair<Double, String>>()
        fun look(x: Double, y: Double, dist: Double) {
            val code = CountryInfo.key(codeAt(Mercator.lat(y), GlobeMath.normLon(Mercator.lng(x)))) ?: return
            found += dist to code
        }
        val step = max(1, (n + samples - 1) / samples)
        for (i in 0 until n) {
            // Points inside a flight (both legs by plane) are only flown over; a run's ends and the last point count.
            val before = if (i > 0) legs.getOrNull(i - 1) else null
            val after = legs.getOrNull(i)
            if (before == Vehicle.Plane && after == Vehicle.Plane) continue
            if (i % step != 0 && i != n - 1 && before == after) continue
            look(plan.xs[i], plan.ys[i], plan.realCum[i])
        }
        for (s in plan.stops) look(s.x, s.y, s.dist)
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * The point at the middle of the sphere's surface under points ([lats], [lngs]) (the normalised sum of their unit
     * vectors), or null when they cancel out (a trip around the world).
     */
    fun mean(lats: DoubleArray, lngs: DoubleArray): Pair<Double, Double>? {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (i in lats.indices) {
            val u = Unit3.of(lats[i], lngs[i])
            x += u.x; y += u.y; z += u.z
        }
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1e-9 * max(1, lats.size)) return null
        return Math.toDegrees(asin((z / n).coerceIn(-1.0, 1.0))) to Math.toDegrees(atan2(y, x))
    }
}

/** A unit vector on the sphere (x towards 0°/0°, y towards 0°/90°E, z to the North Pole), in doubles. */
class Unit3(val x: Double, val y: Double, val z: Double) {
    val lat: Double get() = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))
    val lng: Double get() = Math.toDegrees(atan2(y, x))

    fun dot(o: Unit3) = x * o.x + y * o.y + z * o.z

    /** Angle to [o] in radians, accurate for tiny angles too. */
    fun angleTo(o: Unit3): Double {
        val cx = y * o.z - z * o.y
        val cy = z * o.x - x * o.z
        val cz = x * o.y - y * o.x
        return atan2(sqrt(cx * cx + cy * cy + cz * cz), dot(o))
    }

    companion object {
        fun of(lat: Double, lng: Double): Unit3 {
            val p = Math.toRadians(lat)
            val l = Math.toRadians(lng)
            val c = cos(p)
            return Unit3(c * cos(l), c * sin(l), sin(p))
        }

        /** Point [t] of the way from [a] to [b] along the great circle; [theta] is their angle. */
        fun slerp(a: Unit3, b: Unit3, theta: Double, t: Double): Unit3 {
            if (theta < 1e-9) return a
            val s = sin(theta)
            val wa = sin((1 - t) * theta) / s
            val wb = sin(t * theta) / s
            return normalised(a.x * wa + b.x * wb, a.y * wa + b.y * wb, a.z * wa + b.z * wb) ?: a
        }

        /** (x, y, z) scaled to length 1, or null for (almost) zero. */
        fun normalised(x: Double, y: Double, z: Double): Unit3? {
            val n = sqrt(x * x + y * y + z * z)
            return if (n < 1e-15) null else Unit3(x / n, y / n, z / n)
        }
    }
}

/**
 * The sphere seen from far above ([lat0], [lng0]) — an orthographic view, as the Map tab's globe — with radius [r]
 * pixels around ([cx], [cy]), in doubles (a close view's radius runs into tens of millions of pixels, where floats
 * would shake the route against the map). [depth] is ≥ 0 on the side facing the viewer.
 */
class GlobeView(val lat0: Double, val lng0: Double, val cx: Double, val cy: Double, val r: Double) {
    private val ex: Double
    private val ey: Double
    private val nx: Double
    private val ny: Double
    private val nz: Double
    private val fx: Double
    private val fy: Double
    private val fz: Double

    init {
        val l = Math.toRadians(lng0)
        val p = Math.toRadians(lat0)
        val cl = cos(l)
        val sl = sin(l)
        val cp = cos(p)
        val sp = sin(p)
        fx = cp * cl; fy = cp * sl; fz = sp
        ex = -sl; ey = cl
        nx = -sp * cl; ny = -sp * sl; nz = cp
    }

    fun x(x: Double, y: Double, z: Double): Double = cx + r * (x * ex + y * ey)
    fun y(x: Double, y: Double, z: Double): Double = cy - r * (x * nx + y * ny + z * nz)
    fun depth(x: Double, y: Double, z: Double): Double = x * fx + y * fy + z * fz

    fun x(u: Unit3) = x(u.x, u.y, u.z)
    fun y(u: Unit3) = y(u.x, u.y, u.z)
    fun depth(u: Unit3) = depth(u.x, u.y, u.z)

    /**
     * Which way a step along compass [bearing] (degrees; 0 north, 90 east) from the point at [lat], [lng] goes in this
     * view, as a unit direction in its pixels; null when it points straight at or away from the viewer. Worked out on
     * the sphere's own east and north at the point, never through a second point that could be round the back, so the
     * way doesn't flip near the rim.
     */
    fun tangent(lat: Double, lng: Double, bearing: Double): Pair<Double, Double>? {
        val p = Math.toRadians(lat)
        val l = Math.toRadians(lng)
        val b = Math.toRadians(bearing)
        val east = sin(b)
        val north = cos(b)
        // East (−sin λ, cos λ, 0) and north (−sin φ cos λ, −sin φ sin λ, cos φ) at the point.
        val tx = -sin(l) * east - sin(p) * cos(l) * north
        val ty = cos(l) * east - sin(p) * sin(l) * north
        val tz = cos(p) * north
        val dx = tx * ex + ty * ey
        val dy = -(tx * nx + ty * ny + tz * nz)
        val len = sqrt(dx * dx + dy * dy)
        return if (len < 1e-9) null else dx / len to dy / len
    }

    companion object {
        /** The view of [camera] on a [width] × [height] frame (before any tilt): centred, at the Mercator view's scale. */
        fun of(camera: Camera, width: Double, height: Double): GlobeView =
            GlobeView(Mercator.lat(camera.cy), Mercator.lng(camera.cx), width / 2, height / 2, ClipGlobe.radiusPx(camera, width))
    }
}

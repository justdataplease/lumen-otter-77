package com.justdataplease.boringtravel.globe

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orthographic view of the unit sphere, the way a globe looks from far away. Earth-fixed unit vectors (x towards
 * 0°/0°, y towards 0°/90°E, z to the North Pole) are read along three view axes: east (screen right), north (screen
 * up) and forward (towards the viewer). A point faces the viewer when its forward part is ≥ 0.
 *
 * Mutable on purpose: one instance is set per frame and read for every vertex, with no allocation.
 */
class Ortho {
    var ex = 0f; private set
    var ey = 1f; private set
    var ez = 0f; private set
    var nx = 0f; private set
    var ny = 0f; private set
    var nz = 1f; private set
    var fx = 1f; private set
    var fy = 0f; private set
    var fz = 0f; private set

    /** Screen centre and radius in pixels. */
    var cx = 0f; private set
    var cy = 0f; private set
    var r = 1f; private set

    var lonDeg = 0.0; private set
    var latDeg = 0.0; private set

    fun set(lonDeg: Double, latDeg: Double, cx: Float, cy: Float, r: Float): Ortho {
        this.lonDeg = lonDeg
        this.latDeg = latDeg
        this.cx = cx
        this.cy = cy
        this.r = r
        val l = Math.toRadians(lonDeg)
        val p = Math.toRadians(latDeg)
        val cl = cos(l)
        val sl = sin(l)
        val cp = cos(p)
        val sp = sin(p)
        fx = (cp * cl).toFloat(); fy = (cp * sl).toFloat(); fz = sp.toFloat()
        ex = (-sl).toFloat(); ey = cl.toFloat(); ez = 0f
        nx = (-sp * cl).toFloat(); ny = (-sp * sl).toFloat(); nz = cp.toFloat()
        return this
    }

    /** Right of centre, in globe radii. */
    fun x(vx: Float, vy: Float, vz: Float) = vx * ex + vy * ey + vz * ez

    /** Above centre, in globe radii. */
    fun y(vx: Float, vy: Float, vz: Float) = vx * nx + vy * ny + vz * nz

    /** Towards the viewer: > 0 on the near side, < 0 behind the globe. */
    fun depth(vx: Float, vy: Float, vz: Float) = vx * fx + vy * fy + vz * fz

    fun screenX(x: Float) = cx + r * x
    fun screenY(y: Float) = cy - r * y

    /**
     * The latitude and longitude under a screen point, or null off the globe. [out] receives lat, lng (degrees);
     * returns false when the point is outside the disc.
     */
    fun unproject(sx: Float, sy: Float, out: DoubleArray): Boolean {
        val x = ((sx - cx) / r).toDouble()
        val y = ((cy - sy) / r).toDouble()
        val q = x * x + y * y
        if (q > 1.0) return false
        val d = sqrt(1.0 - q)
        val px = x * ex + y * nx + d * fx
        val py = x * ey + y * ny + d * fy
        val pz = x * ez + y * nz + d * fz
        out[0] = Math.toDegrees(asin(pz.coerceIn(-1.0, 1.0)))
        out[1] = Math.toDegrees(atan2(py, px))
        return true
    }
}

/** Rotation, zoom and motion rules for the globe. Plain maths, so every rule has a test. */
object GlobeMath {
    /** The view never tips further than this towards a pole (beyond it, dragging sideways gets unreadable). */
    const val MAX_LAT = 80.0
    const val MIN_ZOOM = 0.7f
    const val MAX_ZOOM = 6f

    /** Idle spin, degrees of longitude per second (a turn in 72 s). */
    const val SPIN_DEG_PER_S = 5.0

    /** Fling slows by e every this many seconds. */
    const val FLING_TIME_CONSTANT_S = 0.42

    /** Below this (degrees per second) a fling has stopped. */
    const val FLING_STOP_DEG_PER_S = 2.0

    fun unitX(latDeg: Double, lngDeg: Double): Float = (cos(Math.toRadians(latDeg)) * cos(Math.toRadians(lngDeg))).toFloat()
    fun unitY(latDeg: Double, lngDeg: Double): Float = (cos(Math.toRadians(latDeg)) * sin(Math.toRadians(lngDeg))).toFloat()
    fun unitZ(latDeg: Double): Float = sin(Math.toRadians(latDeg)).toFloat()

    /** Longitude in (-180, 180]. */
    fun normLon(lon: Double): Double {
        var l = lon % 360.0
        if (l <= -180.0) l += 360.0
        if (l > 180.0) l -= 360.0
        return l
    }

    /** The shorter way from [from] to [to] in degrees of longitude, in (-180, 180]. */
    fun lonDelta(from: Double, to: Double): Double = normLon(to - from)

    /** Angle in radians folded into (-π, π]. */
    fun wrapRad(a: Float): Float {
        var x = a
        while (x <= -PI.toFloat()) x += (2 * PI).toFloat()
        while (x > PI.toFloat()) x -= (2 * PI).toFloat()
        return x
    }

    /**
     * Degrees of longitude a horizontal drag of [dxPx] turns the globe, so the point under the finger follows it
     * (sideways motion near the poles is capped so it doesn't race).
     */
    fun dragLon(dxPx: Float, radiusPx: Float, latDeg: Double): Double =
        -Math.toDegrees(dxPx / radiusPx.toDouble()) / max(cos(Math.toRadians(latDeg)), 0.35)

    /** Degrees of latitude a vertical drag turns the globe: dragging down brings the north into view. */
    fun dragLat(dyPx: Float, radiusPx: Float): Double = Math.toDegrees(dyPx / radiusPx.toDouble())

    fun clampLat(lat: Double): Double = lat.coerceIn(-MAX_LAT, MAX_LAT)

    fun clampZoom(z: Float, min: Float = MIN_ZOOM, max: Float = MAX_ZOOM): Float = z.coerceIn(min, max)

    /** A fling's speed after [dtS] seconds of friction. */
    fun decay(velocity: Double, dtS: Double, timeConstantS: Double = FLING_TIME_CONSTANT_S): Double = velocity * exp(-dtS / timeConstantS)

    /** How far a fling still travels from [velocity] until it stops (the integral of [decay]). */
    fun flingDistance(velocity: Double, timeConstantS: Double = FLING_TIME_CONSTANT_S): Double = velocity * timeConstantS

    /** Smooth start and end for animated moves, t in 0..1. */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
    }

    /** Idle spin speed ramping up from 0 over [rampS] seconds after it resumes. */
    fun spinSpeed(secondsSinceResume: Double, rampS: Double = 1.5): Double =
        SPIN_DEG_PER_S * (secondsSinceResume / rampS).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    /** The globe's radius at zoom 1 for a view of [w]×[h] px: the whole globe with a little margin. */
    fun baseRadius(w: Float, h: Float): Float = 0.46f * minOf(w, h)

    /** Angle between two lat/lng points in degrees (great-circle, on the unit sphere). */
    fun angleDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val a = unitX(lat1, lng1) * unitX(lat2, lng2) + unitY(lat1, lng1) * unitY(lat2, lng2) + unitZ(lat1) * unitZ(lat2)
        return Math.toDegrees(kotlin.math.acos(a.toDouble().coerceIn(-1.0, 1.0)))
    }

    /** Spherical mean of points (lat, lng pairs), for centring the globe on a traveller's countries. */
    fun meanPoint(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (points.isEmpty()) return null
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for ((lat, lng) in points) {
            x += unitX(lat, lng); y += unitY(lat, lng); z += unitZ(lat)
        }
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1e-6) return points.first()
        return Math.toDegrees(asin(z / n)) to Math.toDegrees(atan2(y, x))
    }
}

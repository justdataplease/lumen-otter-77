package com.justdataplease.boringtravel.clip

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** One route point as the clip sees it: when, where, and how sure the phone was. */
data class GeoPoint(val time: Long, val lat: Double, val lng: Double, val accuracyM: Float? = null)

/**
 * Light shaping of raw route points before they are drawn: drop vague fixes, fold the jitter of a long stay into
 * one spot, smooth a little and thin out straight stretches. Small and local on purpose; it never invents points.
 */
object TrackShaping {
    /** Fixes vaguer than this are dropped (a cell-tower fix can be kilometres off). */
    const val MAX_ACCURACY_M = 300f

    /** Sorts by time, drops vague and same-time fixes, and folds each still spell into its first and last point. */
    fun clean(points: List<GeoPoint>): List<GeoPoint> {
        val sorted = points
            .filter { (it.accuracyM ?: 0f) <= MAX_ACCURACY_M && it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }
            .sortedBy { it.time }
        val out = ArrayList<GeoPoint>(sorted.size)
        for (p in sorted) {
            if (out.isNotEmpty() && out.last().time == p.time) continue
            out += p
        }
        return collapseStays(out)
    }

    /**
     * A run of 3+ points that all stay within [radiusFor] of the run's first point is a stay (a café, a night in a
     * hotel): it becomes two points at the run's centre, keeping the arrival and leaving times so speeds stay right.
     */
    fun collapseStays(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 3) return points
        val out = ArrayList<GeoPoint>(points.size)
        var i = 0
        while (i < points.size) {
            val a = points[i]
            val r = radiusFor(a)
            var j = i + 1
            while (j < points.size && Geo.distanceM(a.lat, a.lng, points[j].lat, points[j].lng) <= r) j++
            val n = j - i
            if (n >= 3) {
                var lat = 0.0
                var lng = 0.0
                for (k in i until j) { lat += points[k].lat; lng += points[k].lng }
                lat /= n; lng /= n
                out += a.copy(lat = lat, lng = lng)
                out += points[j - 1].copy(lat = lat, lng = lng)
                i = j
            } else {
                out += a
                i++
            }
        }
        return out
    }

    private fun radiusFor(p: GeoPoint): Double = max(25.0, 2.0 * (p.accuracyM ?: 0f))

    /**
     * A 1-2-1 moving average on positions. A point is only smoothed when both neighbours are within [reachM], so a
     * flight or a ferry's ends never get pulled. The first and last points stay where they are.
     */
    fun smooth(points: List<GeoPoint>, reachM: Double = 3_000.0): List<GeoPoint> {
        if (points.size < 3) return points
        val out = ArrayList<GeoPoint>(points.size)
        out += points.first()
        for (i in 1 until points.size - 1) {
            val a = points[i - 1]
            val b = points[i]
            val c = points[i + 1]
            val near = Geo.distanceM(a.lat, a.lng, b.lat, b.lng) <= reachM && Geo.distanceM(b.lat, b.lng, c.lat, c.lng) <= reachM
            out += if (near) b.copy(lat = (a.lat + 2 * b.lat + c.lat) / 4, lng = (a.lng + 2 * b.lng + c.lng) / 4) else b
        }
        out += points.last()
        return out
    }

    /**
     * Douglas–Peucker in local metres: keeps the first and last point and every point that sits more than
     * [toleranceM] off the line between the points kept around it. Returns the indices kept, in order.
     */
    fun simplify(points: List<GeoPoint>, toleranceM: Double): List<Int> {
        val n = points.size
        if (n <= 2) return points.indices.toList()
        val lat0 = points.sumOf { it.lat } / n
        val kx = 111_320.0 * cos(lat0 * PI / 180.0)
        val ky = 110_540.0
        val xs = DoubleArray(n) { points[it].lng * kx }
        val ys = DoubleArray(n) { points[it].lat * ky }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            var worst = -1.0
            var at = -1
            for (k in a + 1 until b) {
                val d = pointToSegment(xs[k], ys[k], xs[a], ys[a], xs[b], ys[b])
                if (d > worst) { worst = d; at = k }
            }
            if (worst > toleranceM) {
                keep[at] = true
                stack.addLast(a to at)
                stack.addLast(at to b)
            }
        }
        return (0 until n).filter { keep[it] }
    }

    internal fun pointToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Total length of a point list in metres. */
    fun lengthM(points: List<GeoPoint>): Double {
        var d = 0.0
        for (i in 1 until points.size) d += Geo.distanceM(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
        return d
    }
}

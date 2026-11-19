package com.justdataplease.boringtravel.export

import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Double, val y: Double)

/**
 * A route without map tiles: an equirectangular projection (longitude scaled by the cosine of the middle latitude)
 * fitted into a box and the line thinned with Douglas–Peucker, for the book's route
 * ([com.justdataplease.boringtravel.book.BookSvg]). The trip PDF no longer draws a route.
 */
object RouteSvg {
    /** Fits [points] into the box, keeping the shape's proportions and centring it. Longitudes are unwrapped across ±180°. */
    fun project(points: List<GeoPoint>, width: Double, height: Double, pad: Double): List<Pt> {
        if (points.isEmpty()) return emptyList()
        val lngs = unwrap(points.map { it.lng })
        val midLat = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
        val k = cos(Math.toRadians(midLat)).coerceAtLeast(0.05)
        val xs = lngs.map { it * k }
        val ys = points.map { -it.lat }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val spanX = maxX - minX
        val spanY = maxY - minY
        val innerW = max(width - 2 * pad, 1.0)
        val innerH = max(height - 2 * pad, 1.0)
        val scale = when {
            spanX <= 1e-12 && spanY <= 1e-12 -> 0.0
            spanX <= 1e-12 -> innerH / spanY
            spanY <= 1e-12 -> innerW / spanX
            else -> min(innerW / spanX, innerH / spanY)
        }
        val offX = (width - spanX * scale) / 2
        val offY = (height - spanY * scale) / 2
        return xs.indices.map { i -> Pt(offX + (xs[i] - minX) * scale, offY + (ys[i] - minY) * scale) }
    }

    /** Keeps longitudes continuous when a route crosses the antimeridian (179° → −179° becomes 179° → 181°). */
    fun unwrap(lngs: List<Double>): List<Double> {
        if (lngs.isEmpty()) return lngs
        val out = ArrayList<Double>(lngs.size)
        var shift = 0.0
        out += lngs[0]
        for (i in 1 until lngs.size) {
            val d = lngs[i] - lngs[i - 1]
            if (d > 180) shift -= 360 else if (d < -180) shift += 360
            out += lngs[i] + shift
        }
        return out
    }

    /** Douglas–Peucker: drops points closer than [tolerance] to the simplified line. Ends are always kept. */
    fun simplify(points: List<Pt>, tolerance: Double): List<Pt> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = distanceToSegment(points[i], points[a], points[b])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > tolerance) {
                keep[idx] = true
                stack.addLast(a to idx)
                stack.addLast(idx to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Numbers with at most one decimal, always with a dot. */
    fun n(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else String.format(Locale.ROOT, "%.1f", r)
    }
}

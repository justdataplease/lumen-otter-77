package com.justdataplease.boringtravel.geo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A position in degrees (WGS84). */
data class GeoPoint(val lat: Double, val lng: Double)

/** A lat/lng box. Boxes don't cross the antimeridian (fine for trips; a Pacific crossing just gets a wide box). */
data class BBox(val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double) {
    val center: GeoPoint get() = GeoPoint((minLat + maxLat) / 2, (minLng + maxLng) / 2)

    /** True when the box is a single point (one fix, or many at the same spot). */
    val isPoint: Boolean get() = maxLat - minLat < 1e-6 && maxLng - minLng < 1e-6

    fun contains(lat: Double, lng: Double): Boolean = lat in minLat..maxLat && lng in minLng..maxLng

    fun union(other: BBox) = BBox(min(minLat, other.minLat), min(minLng, other.minLng), max(maxLat, other.maxLat), max(maxLng, other.maxLng))

    /** The box grown by [meters] on every side. */
    fun expand(meters: Double): BBox {
        val dLat = meters / Geo.METERS_PER_DEGREE
        val c = cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)
        val dLng = meters / (Geo.METERS_PER_DEGREE * c)
        return BBox(minLat - dLat, minLng - dLng, maxLat + dLat, maxLng + dLng)
    }

    companion object {
        fun of(points: Iterable<GeoPoint>): BBox? {
            var b: BBox? = null
            for (p in points) b = b?.let { BBox(min(it.minLat, p.lat), min(it.minLng, p.lng), max(it.maxLat, p.lat), max(it.maxLng, p.lng)) }
                ?: BBox(p.lat, p.lng, p.lat, p.lng)
            return b
        }
    }
}

/** Distances, bearings and line helpers on the sphere. Plain Kotlin, so every rule is unit-tested. */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8
    const val METERS_PER_DEGREE = 111_195.0

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    fun distanceM(a: GeoPoint, b: GeoPoint): Double = distanceM(a.lat, a.lng, b.lat, b.lng)

    /** Initial bearing from the first point to the second, degrees clockwise from north, 0 until 360. */
    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** The smaller angle between two bearings, 0 until 180. */
    fun turnDeg(b1: Double, b2: Double): Double {
        val d = abs(b1 - b2) % 360
        return if (d > 180) 360 - d else d
    }

    /** Length of a line in metres. */
    fun lengthM(points: List<GeoPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += distanceM(points[i - 1], points[i])
        return sum
    }

    /**
     * Distance in metres from [p] to the segment [a]–[b], on a flat projection around [p] (good to well under 1 % for
     * segments up to a few hundred km, which is all the country and line helpers need).
     */
    fun distanceToSegmentM(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lng - p.lng) * k
        val ay = a.lat - p.lat
        val bx = (b.lng - p.lng) * k
        val by = b.lat - p.lat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy) * METERS_PER_DEGREE
    }

    /**
     * Douglas–Peucker: drops points that lie within [toleranceM] of the line through their neighbours. The map draws
     * long trips with far fewer vertices this way; the first and last points always stay.
     */
    fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        if (points.size < 3 || toleranceM <= 0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast().let { it[0] to it[1] }
            var worst = -1.0
            var index = -1
            for (i in from + 1 until to) {
                val d = distanceToSegmentM(points[i], points[from], points[to])
                if (d > worst) { worst = d; index = i }
            }
            if (index >= 0 && worst > toleranceM) {
                keep[index] = true
                stack.addLast(intArrayOf(from, index))
                stack.addLast(intArrayOf(index, to))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}

/**
 * Google's encoded polyline format (precision 5 by default): a day of route as one short string, which is how sync
 * stores tracks (one line per day, never point by point).
 */
object Polyline {
    fun encode(points: List<GeoPoint>, precision: Int = 5): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = StringBuilder()
        var lastLat = 0L
        var lastLng = 0L
        for (p in points) {
            val lat = Math.round(p.lat * factor)
            val lng = Math.round(p.lng * factor)
            encodeValue(lat - lastLat, out)
            encodeValue(lng - lastLng, out)
            lastLat = lat
            lastLng = lng
        }
        return out.toString()
    }

    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0L
        var lng = 0L
        while (index < encoded.length) {
            val dLat = decodeValue(encoded, index).also { index = it.second }.first
            if (index >= encoded.length) break
            val dLng = decodeValue(encoded, index).also { index = it.second }.first
            lat += dLat
            lng += dLng
            points += GeoPoint(lat / factor, lng / factor)
        }
        return points
    }

    private fun encodeValue(value: Long, out: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }

    /** One zig-zag value starting at [start]; returns it and the index after it. */
    private fun decodeValue(s: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < s.length) {
            val b = s[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to i
    }
}

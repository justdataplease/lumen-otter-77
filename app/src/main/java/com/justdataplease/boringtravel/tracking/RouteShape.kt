package com.justdataplease.boringtravel.tracking

import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.geo.GeoPoint

/** Turns cleaned points into what the map and the stats show. Plain Kotlin, unit-tested. */
object RouteShape {
    /** A drawn part of the route; [jump] = a gap with no points (a flight, a ferry out of signal), drawn dashed. */
    data class Piece(val points: List<GeoPoint>, val jump: Boolean)

    /**
     * Splits the route at jumps: two points more than [jumpM] apart, or more than [gapM] apart after more than
     * [gapMs] without points. Each piece is simplified to [toleranceM] for drawing.
     */
    fun pieces(
        points: List<TrackPoint>,
        toleranceM: Double = 0.0,
        jumpM: Double = 25_000.0,
        gapMs: Long = 3 * 3_600_000L,
        gapM: Double = 2_000.0,
    ): List<Piece> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<Piece>()
        var run = arrayListOf(GeoPoint(points[0].lat, points[0].lng))
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = Geo.distanceM(a.lat, a.lng, b.lat, b.lng)
            val p = GeoPoint(b.lat, b.lng)
            if (d > jumpM || (b.time - a.time > gapMs && d > gapM)) {
                if (run.size > 1) out += Piece(Geo.simplify(run, toleranceM), jump = false)
                out += Piece(listOf(GeoPoint(a.lat, a.lng), p), jump = true)
                run = arrayListOf(p)
            } else run += p
        }
        if (run.size > 1) out += Piece(Geo.simplify(run, toleranceM), jump = false)
        return out
    }

    /** Metres travelled between [from] and [to] (epoch ms, end exclusive), counting legs that start and end inside. */
    fun distanceM(points: List<TrackPoint>, from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): Double {
        var sum = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (a.time >= from && b.time < to) sum += Geo.distanceM(a.lat, a.lng, b.lat, b.lng)
        }
        return sum
    }

    /** Stops that overlap [from]–[to] (an open stop lasts until [now]). */
    fun stopsBetween(stops: List<Stop>, from: Long, to: Long, now: Long): List<Stop> =
        stops.filter { it.deletedAt == null && it.arrivedAt < to && (it.leftAt ?: now) >= from }

    /** "18.4 km", "212 km", "650 m". */
    fun formatDistance(meters: Double): String = when {
        meters < 10 -> "0 km"
        meters < 1_000 -> "${(meters / 10).toInt() * 10} m"
        meters < 100_000 -> String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1_000)
        else -> String.format(java.util.Locale.getDefault(), "%,d km", (meters / 1_000).toLong())
    }
}

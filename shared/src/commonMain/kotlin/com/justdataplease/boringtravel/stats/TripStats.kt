package com.justdataplease.boringtravel.stats

import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.places.Geo
import com.justdataplease.boringtravel.time.LocalDate

/** Days away and route lengths for Your world (world/WorldModel). Pure, computed on the phone. */
object TripStats {
    /** A leg longer than this between two points is a gap in the record (or a flight), not travel we saw. */
    const val MAX_LEG_M = 50_000.0

    /** Faster than this is a flight or a bad fix: 300 km/h in metres per second. */
    const val MAX_SPEED_MPS = 300_000.0 / 3_600.0

    /** Days of past trips plus the days so far of a trip happening now. Someday and future trips add nothing. */
    fun daysAway(trips: List<Trip>, today: LocalDate): Int = trips.filter { it.deletedAt == null }.sumOf { t ->
        when {
            t.isPast(today) -> t.days ?: 0
            t.isCurrent(today) -> (t.dayNumber(today) ?: 0).coerceIn(0, t.days ?: 0)
            else -> 0
        }
    }

    /**
     * Distance along one trip's points (sorted by time here), skipping legs that are jumps: longer than
     * [MAX_LEG_M] or faster than [MAX_SPEED_MPS].
     */
    fun meters(points: List<TrackPoint>): Double {
        if (points.size < 2) return 0.0
        val sorted = points.sortedBy { it.time }
        var total = 0.0
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val d = Geo.distanceM(a.lat, a.lng, b.lat, b.lng)
            if (d > MAX_LEG_M) continue
            val seconds = (b.time - a.time) / 1000.0
            if (d > 0 && (seconds <= 0 || d / seconds > MAX_SPEED_MPS)) continue
            total += d
        }
        return total
    }
}

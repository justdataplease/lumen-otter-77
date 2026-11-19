package com.justdataplease.boringtravel.tracking

import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.geo.Geo
import kotlin.math.max

/**
 * Cleans raw fixes before they become the route line, stops and distances. Looks at each point B with the last kept
 * point A and the next point C and drops B when the detour A→B→C can't be real:
 *
 * 1. poor accuracy (worse than [Rules.maxAccuracyM]);
 * 2. both legs faster than any airliner ([Rules.maxSpeedMps]), or the same instant somewhere else;
 * 3. both legs faster than ground travel ([Rules.groundSpeedMps]) while B is off the A→C line. A long, straight
 *    A→B→C at that speed is a flight or a fast train and stays (the flight allowance);
 * 4. a sharp out-and-back (angle at B under [Rules.sharpTurnDeg], detour over [Rules.sharpDetour]) on short legs,
 *    when B is much less accurate than its neighbours or both legs are too quick for a walk.
 *
 * The raw points stay in the database; this runs on a copy each time, so better rules later clean old trips too.
 */
object SpikeFilter {
    data class Rules(
        val maxAccuracyM: Float = 1_000f,
        /** About 1,220 km/h. */
        val maxSpeedMps: Double = 340.0,
        /** About 250 km/h. */
        val groundSpeedMps: Double = 70.0,
        /** (AB + BC) / AC at most this counts as "on the way". */
        val straightDetour: Double = 1.5,
        val sharpTurnDeg: Double = 30.0,
        val sharpDetour: Double = 3.0,
        val shortLegM: Double = 5_000.0,
        /** A walk or ride that turns back is slower than this on both legs (about 43 km/h). */
        val jumpSpeedMps: Double = 12.0,
        /** Accuracy assumed when a fix has none. */
        val defaultAccuracyM: Float = 20f,
    )

    fun filter(points: List<TrackPoint>, rules: Rules = Rules()): List<TrackPoint> {
        val pts = points
            .filter { it.accuracyM == null || it.accuracyM!! <= rules.maxAccuracyM }
            .filter { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 && !(it.lat == 0.0 && it.lng == 0.0) }
            .sortedBy { it.time }
        if (pts.size < 2) return pts
        val out = ArrayList<TrackPoint>(pts.size)
        for (i in pts.indices) {
            val b = pts[i]
            val c = pts.getOrNull(i + 1)
            val a = out.lastOrNull()
            if (a == null) {
                // A bad first fix would make every good point after it look impossible, so check it against the next two.
                val d = pts.getOrNull(i + 2)
                if (c != null && d != null && speed(b, c) > rules.maxSpeedMps && speed(c, d) <= rules.maxSpeedMps) continue
                out += b
                continue
            }
            if (b.time == a.time && dist(a, b) < 50) continue // the same fix twice
            if (dropMiddle(a, b, c, rules)) continue
            out += b
        }
        return out
    }

    /** True when [b] between [a] and [c] is a spike. [c] null = [b] is the newest point. */
    internal fun dropMiddle(a: TrackPoint, b: TrackPoint, c: TrackPoint?, rules: Rules = Rules()): Boolean {
        val vAB = speed(a, b)
        if (c == null) return vAB > rules.maxSpeedMps
        val vBC = speed(b, c)
        if (vAB > rules.maxSpeedMps && vBC > rules.maxSpeedMps) return true

        val dAB = dist(a, b)
        val dBC = dist(b, c)
        val dAC = dist(a, c)
        val detour = (dAB + dBC) / max(dAC, 1.0)
        if (vAB > rules.groundSpeedMps && vBC > rules.groundSpeedMps && detour > rules.straightDetour) return true

        if (dAB < rules.shortLegM && dBC < rules.shortLegM && detour > rules.sharpDetour && dAB > 1 && dBC > 1) {
            val angle = Geo.turnDeg(Geo.bearingDeg(b.lat, b.lng, a.lat, a.lng), Geo.bearingDeg(b.lat, b.lng, c.lat, c.lng))
            if (angle < rules.sharpTurnDeg) {
                val accB = b.accuracyM ?: rules.defaultAccuracyM
                val accNear = maxOf(a.accuracyM ?: rules.defaultAccuracyM, c.accuracyM ?: rules.defaultAccuracyM, rules.defaultAccuracyM)
                if (accB > 2 * accNear) return true
                if (vAB > rules.jumpSpeedMps && vBC > rules.jumpSpeedMps) return true
            }
        }
        return false
    }

    private fun dist(a: TrackPoint, b: TrackPoint) = Geo.distanceM(a.lat, a.lng, b.lat, b.lng)

    /** Metres per second from [a] to [b]; the same instant counts as infinitely fast unless it's the same spot. */
    private fun speed(a: TrackPoint, b: TrackPoint): Double {
        val d = dist(a, b)
        val s = (b.time - a.time) / 1000.0
        return when {
            s > 0 -> d / s
            d < 50 -> 0.0
            else -> Double.POSITIVE_INFINITY
        }
    }
}

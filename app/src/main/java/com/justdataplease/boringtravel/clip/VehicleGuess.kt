package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.model.Vehicle
import kotlin.math.max

/**
 * Guesses how each stretch of a route was travelled, from speed alone (no map data, works offline):
 *
 * - **Plane**: faster than 250 km/h, or a jump of 600 km or more at 80 km/h or more.
 * - **Ferry**: a long silence (30 min or more and at least 3× the trip's usual gap between points) covering 8 km or
 *   more at 8–70 km/h. Phones often keep quiet at sea, and nothing on land is that slow over such a gap.
 * - Otherwise by the speed averaged over the neighbouring legs: **Walk** under 7 km/h, **Bike** under 25,
 *   **Car** under 130, **Train** up to 250.
 *
 * Short runs then take the vehicle of the longer neighbour, so a red light doesn't turn a drive into a walk.
 * A vehicle chosen by the traveller overrides all of it.
 */
object VehicleGuess {
    const val WALK_MAX_KMH = 7.0
    const val BIKE_MAX_KMH = 25.0
    const val CAR_MAX_KMH = 130.0
    const val PLANE_MIN_KMH = 250.0

    /** Below this a leg is "still" (GPS drift at a stop) and doesn't vote. */
    private const val STILL_M = 30.0

    fun bySpeed(kmh: Double): Vehicle = when {
        kmh < WALK_MAX_KMH -> Vehicle.Walk
        kmh < BIKE_MAX_KMH -> Vehicle.Bike
        kmh < CAR_MAX_KMH -> Vehicle.Car
        kmh < PLANE_MIN_KMH -> Vehicle.Train
        else -> Vehicle.Plane
    }

    fun isFlight(km: Double, kmh: Double): Boolean = kmh >= PLANE_MIN_KMH || (km >= 600.0 && kmh >= 80.0)

    fun isCrossing(km: Double, gapSec: Double, kmh: Double, usualGapSec: Double): Boolean =
        gapSec >= max(1_800.0, 3 * usualGapSec) && km >= 8.0 && kmh in 8.0..70.0

    /** One vehicle per leg (points.size - 1 of them). [chosen] other than Auto is used for every leg. */
    fun legs(points: List<GeoPoint>, chosen: Vehicle = Vehicle.Auto): List<Vehicle> {
        val n = points.size - 1
        if (n <= 0) return emptyList()
        if (chosen != Vehicle.Auto) return List(n) { chosen }

        val dist = DoubleArray(n) { Geo.distanceM(points[it].lat, points[it].lng, points[it + 1].lat, points[it + 1].lng) }
        val gap = DoubleArray(n) { (points[it + 1].time - points[it].time) / 1000.0 }
        val usualGap = gap.filter { it > 0 }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

        // 1. Legs that are clearly a flight or a crossing on their own.
        val fixed = arrayOfNulls<Vehicle>(n)
        for (i in 0 until n) {
            val km = dist[i] / 1000.0
            val kmh = if (gap[i] > 0) dist[i] / gap[i] * 3.6 else if (km >= 100.0) Double.MAX_VALUE else 0.0
            fixed[i] = when {
                isFlight(km, kmh) -> Vehicle.Plane
                isCrossing(km, gap[i], kmh, usualGap) -> Vehicle.Ferry
                else -> null
            }
        }

        // 2. Ground legs by the speed over a small window of ground legs around them.
        val out = arrayOfNulls<Vehicle>(n)
        for (i in 0 until n) {
            fixed[i]?.let { out[i] = it; continue }
            if (dist[i] < STILL_M) continue
            var d = 0.0
            var t = 0.0
            for (k in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(n - 1)) {
                if (fixed[k] != null || gap[k] <= 0) continue
                d += dist[k]; t += gap[k]
            }
            out[i] = if (t > 0) bySpeed(d / t * 3.6) else Vehicle.Walk
        }

        // 3. Still legs take the vehicle around them; a route that never moves is a walk.
        fillGaps(out)

        // 4. Short ground runs join their longer ground neighbour.
        val legs = out.map { it ?: Vehicle.Walk }
        var ground = 0.0
        for (i in 0 until n) if (isGround(legs[i])) ground += dist[i]
        return mergeShortRuns(legs, dist, max(400.0, ground * 0.03))
    }

    private fun isGround(v: Vehicle) = v != Vehicle.Plane && v != Vehicle.Ferry

    private fun fillGaps(out: Array<Vehicle?>) {
        var last: Vehicle? = null
        for (i in out.indices) { if (out[i] == null) out[i] = last else last = out[i] }
        var next: Vehicle? = null
        for (i in out.indices.reversed()) { if (out[i] == null) out[i] = next else next = out[i] }
    }

    /**
     * Repeatedly folds the shortest too-short ground run into its longer ground neighbour. Planes and ferries stay,
     * and a ground run between two of them stays too (the walk to the gate is still a walk).
     */
    internal fun mergeShortRuns(legs: List<Vehicle>, dist: DoubleArray, minRunM: Double): List<Vehicle> {
        val v = legs.toMutableList()
        while (true) {
            val runs = runsOf(v, dist)
            if (runs.size < 2) return v
            var best: Pair<Run, Run>? = null
            for ((i, r) in runs.withIndex()) {
                if (r.length >= minRunM || !isGround(r.vehicle)) continue
                val into = listOfNotNull(runs.getOrNull(i - 1), runs.getOrNull(i + 1))
                    .filter { isGround(it.vehicle) }
                    .maxByOrNull { it.length } ?: continue
                if (best == null || r.length < best.first.length) best = r to into
            }
            val (short, into) = best ?: return v
            for (k in short.from until short.to) v[k] = into.vehicle
        }
    }

    internal data class Run(val from: Int, val to: Int, val vehicle: Vehicle, val length: Double)

    internal fun runsOf(legs: List<Vehicle>, dist: DoubleArray): List<Run> {
        val runs = ArrayList<Run>()
        var start = 0
        for (i in 1..legs.size) {
            if (i == legs.size || legs[i] != legs[start]) {
                var len = 0.0
                for (k in start until i) len += dist[k]
                runs += Run(start, i, legs[start], len)
                start = i
            }
        }
        return runs
    }
}

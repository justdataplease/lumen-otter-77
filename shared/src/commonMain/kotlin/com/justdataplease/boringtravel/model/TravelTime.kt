package com.justdataplease.boringtravel.model

/**
 * A rough door-to-door time for a leg from its straight-line distance and vehicle: a detour factor for the way roads
 * and paths wind, a typical speed, and a fixed overhead for flights. One definition for the plan's "≈ 3 h" (LegEstimate)
 * and the clip's screen time per leg. Pure, unit tested.
 */
object TravelTime {
    private class Pace(val kmh: Double, val detour: Double, val overheadMin: Int = 0)

    private fun pace(v: Vehicle): Pace? = when (v) {
        Vehicle.Walk -> Pace(4.5, 1.3)
        Vehicle.Bike -> Pace(15.0, 1.3)
        Vehicle.Car -> Pace(65.0, 1.35)
        Vehicle.Train -> Pace(80.0, 1.25)
        Vehicle.Ferry -> Pace(30.0, 1.1)
        Vehicle.Plane -> Pace(700.0, 1.05, overheadMin = 30)
        Vehicle.Auto -> null
    }

    /** Minutes (not rounded) for [distanceM] by [vehicle]; null for no vehicle or no distance. */
    fun minutes(vehicle: Vehicle, distanceM: Double): Double? {
        val p = pace(vehicle) ?: return null
        if (!distanceM.isFinite() || distanceM <= 0.0) return null
        return distanceM / 1000.0 * p.detour / p.kmh * 60.0 + p.overheadMin
    }
}

package com.justdataplease.boringtravel.places

/**
 * Search results in order of nearness to the trip (owner, 15 Nov 2026: the trip was in Madrid, a restaurant search
 * found four and the planner took the one in Barcelona). The search's own order is kept inside rings around the
 * nearest of the trip's places: the same town, a day trip away, the same part of the world, anywhere. A street of the
 * same name 300 m closer doesn't beat the restaurant the search ranked first; another city always loses. Pure and unit
 * tested.
 */
object NearTrip {
    const val TOWN_M = 25_000.0
    const val DAY_TRIP_M = 150_000.0
    const val REGION_M = 1_500_000.0

    /** Metres from [p] to the nearest of [anchors]; null without anchors. */
    fun distanceM(p: PlaceResult, anchors: List<LatLng>): Double? =
        anchors.minOfOrNull { Geo.distanceM(it.lat, it.lng, p.lat, p.lng) }

    private fun ring(m: Double?): Int = when {
        m == null || m <= TOWN_M -> 0
        m <= DAY_TRIP_M -> 1
        m <= REGION_M -> 2
        else -> 3
    }

    /** [found] nearest the trip first (by ring, the search's order inside one); as found without [anchors]. */
    fun ranked(found: List<PlaceResult>, anchors: List<LatLng>): List<PlaceResult> {
        if (anchors.isEmpty() || found.size < 2) return found
        return found.withIndex().sortedWith(compareBy({ ring(distanceM(it.value, anchors)) }, { it.index })).map { it.value }
    }
}

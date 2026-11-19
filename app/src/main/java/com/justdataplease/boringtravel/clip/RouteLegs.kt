package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.TravelDefault
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The planner and replay share exactly the same valid, ordered destination pairs. */
object RouteLegs {
    fun planned(trip: Trip, items: List<PlanItem>): List<RouteLeg> = fromPlanned(trip, PlannedReplay.build(trip, items))

    fun fromPlanned(trip: Trip, replay: PlannedReplay): List<RouteLeg> = withChoices(trip,
        replay.waypoints.zipWithNext { from, to -> RouteLeg(
            RouteLegKey(RouteLegSource.Planned, from.itemId, to.itemId), from.title.ifBlank { "Place" }, to.title.ifBlank { "Place" }, from.day, to.day, null,
            samePlace = samePlace(from, to), distanceM = Geo.distanceM(from.lat, from.lng, to.lat, to.lng), toTravel = to.travel,
        ) },
    )

    /** Whether planned places [a] and [b] are items of one place on one day, near each other (walked unless chosen otherwise). */
    fun samePlace(a: PlannedWaypoint, b: PlannedWaypoint): Boolean = TravelDefault.samePlace(
        a.place ?: a.title, b.place ?: b.title, a.day, b.day, Geo.distanceM(a.lat, a.lng, b.lat, b.lng),
    )

    fun savedStops(trip: Trip, stops: List<Stop>, zone: ZoneId = ZoneId.systemDefault()): List<RouteLeg> = withChoices(trip,
        orderedStops(trip, stops).zipWithNext { from, to -> RouteLeg(
            RouteLegKey(RouteLegSource.Stops, from.id, to.id), from.name.ifBlank { "Stop" }, to.name.ifBlank { "Stop" },
            Instant.ofEpochMilli(from.arrivedAt).atZone(zone).toLocalDate(), Instant.ofEpochMilli(to.arrivedAt).atZone(zone).toLocalDate(), null,
        ) },
    )

    fun withChoices(trip: Trip, legs: List<RouteLeg>): List<RouteLeg> {
        val saved = TravelModes.choices(trip.travelModes)
        val km = TravelModes.kmChoices(trip.travelModes)
        return legs.map { it.copy(vehicle = saved[it.key], countKm = km[it.key]) }
    }

    internal fun orderedStops(trip: Trip, stops: List<Stop>): List<Stop> {
        val result = ArrayList<Stop>()
        for (stop in stops.filter { it.tripId == trip.id && it.deletedAt == null && validLocation(it.lat, it.lng) }
            .sortedWith(compareBy<Stop>({ it.arrivedAt }, { it.id }))) {
            val prior = result.lastOrNull()
            if (prior == null || Geo.distanceM(prior.lat, prior.lng, stop.lat, stop.lng) >= 5.0) result += stop
        }
        return result
    }

    private fun validLocation(lat: Double, lng: Double) = lat.isFinite() && lng.isFinite() && lat in -Mercator.MAX_LAT..Mercator.MAX_LAT && lng in -180.0..180.0
}

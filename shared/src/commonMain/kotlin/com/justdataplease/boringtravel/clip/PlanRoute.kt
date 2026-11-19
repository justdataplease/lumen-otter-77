package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.PlanStep
import com.justdataplease.boringtravel.tags.ItemTags

/**
 * The plan as a route: its located items in plan order, day by day; an item that ends on another day
 * ([PlanSpan.endRow]) is also a stop on that day, where its end row is ("Hostal Alicante" again on the check-out
 * morning; owner, 2026-11-12: "these will be available in clip"). Ideas come last. Adjacent rows at one spot are one
 * stop, but a return to a place later in the plan stays.
 *
 * The one place the order comes from: the Android app's map, clip and travel rows read it through PlannedReplay and
 * RouteLegs, a trip made from a trip idea reads [legKeys], and the iPhone app reads the same.
 */
object PlanRoute {
    /** A stop of the route: a plan row ([step]) at a position, with the tags of every row merged into it. */
    data class Stop(val step: PlanStep, val lat: Double, val lng: Double, val tags: List<String>) {
        /** The row's id: the item's, or its end's ([PlanSpan.endId]). */
        val id: String get() = step.id
        val item: PlanItem get() = step.item
    }

    /** [live]: the trip's items that aren't deleted; [missingLocations]: how many of them have no position. */
    data class Route(val stops: List<Stop>, val live: List<PlanItem>, val missingLocations: Int)

    fun of(trip: Trip, items: List<PlanItem>): Route {
        val active = items.filter { it.tripId == trip.id && it.deletedAt == null }
        val located = active.filter { item ->
            val lat = item.lat
            val lng = item.lng
            lat != null && lng != null && lat.isFinite() && lng.isFinite() &&
                lat in -Mercator.MAX_LAT..Mercator.MAX_LAT && lng in -180.0..180.0
        }
        // The order comes from the whole day, so an untimed place keeps its place among timed items without a pin.
        val at = located.mapTo(HashSet()) { it.id }
        val steps = (PlanSpan.byDay(active).values.flatten() + PlanSpan.dayOrder(active.filter { it.day == null }).map { PlanStep(it) })
            .filter { it.item.id in at }
        val out = ArrayList<Stop>()
        for (step in steps) {
            val item = step.item
            val stop = Stop(step, requireNotNull(item.lat), requireNotNull(item.lng), ItemTags.of(item))
            val previous = out.lastOrNull()
            // Collapse adjacent duplicate places, but preserve a return to a place later in the itinerary.
            if (previous != null && Geo.distanceM(previous.lat, previous.lng, stop.lat, stop.lng) < 5.0) {
                // The same spot is one stop; its tags are the place's too.
                if (stop.tags.any { it !in previous.tags }) out[out.lastIndex] = previous.copy(tags = (previous.tags + stop.tags).distinct())
                continue
            }
            out += stop
        }
        return Route(out, active, active.size - located.size)
    }

    /** The legs between the route's stops, as the keys the trip's travel choices are saved under ([TravelModes]). */
    fun legKeys(trip: Trip, items: List<PlanItem>): List<RouteLegKey> =
        of(trip, items).stops.zipWithNext { from, to -> RouteLegKey(RouteLegSource.Planned, from.id, to.id) }
}

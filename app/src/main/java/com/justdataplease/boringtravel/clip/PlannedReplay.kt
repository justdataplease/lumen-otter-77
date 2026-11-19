package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.ui.place.PlaceGrouping
import java.time.LocalDate
import java.time.ZoneId

/** The provenance remains attached to the replay and its exported video. */
enum class ClipSource(val label: String) {
    Recorded("Recorded journey"), Planned("Trip plan"), Stops("Saved stops"),
}

/**
 * Planned places are their own model; they are never stored as recorded TrackPoints. [place] is the town or area the
 * item sits in ("El Chaltén" for "Laguna Capri and Mirador…", from [PlaceGrouping.placeOf]); the clip names it, and
 * the title only when there is none. [travel]: a travel item ("Fly to Mendoza"): the video shows its place, never its
 * title, as a stop of that place.
 */
data class PlannedWaypoint(
    val itemId: String, val title: String, val lat: Double, val lng: Double, val day: LocalDate?, val place: String? = null,
    val travel: Boolean = false,
    /**
     * How long the item lasts in hours ([PlanSpan.hours]; null: no end, or this is the end's stop): a longer item gets
     * more time at its stop in the clip ([StopStays]).
     */
    val hours: Double? = null,
    /** The item's tags ([ItemTags]); the clip shows them under its place's name ([ClipTags.byPlace]). */
    val tags: List<String> = emptyList(),
)

data class PlannedReplay(val waypoints: List<PlannedWaypoint>, val missingLocations: Int, val unscheduledPlaces: Int) {
    /** Internal animation ordering only. These seconds are never presented as visit times or used for speed guesses. */
    fun timeline(zone: ZoneId): List<GeoPoint> {
        val start = LocalDate.of(2000, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return waypoints.mapIndexed { index, p -> GeoPoint(start + index * 1_000L, p.lat, p.lng) }
    }

    companion object {
        /**
         * The located items in plan order, day by day; an item that ends on another day ([PlanSpan.endRow]) is also a
         * stop on that day, where its end row is ("Hostal Alicante" again on the check-out morning; owner, 2026-11-12:
         * "these will be available in clip"). Ideas come last.
         */
        fun build(trip: Trip, items: List<PlanItem>): PlannedReplay {
            // The order, the located items and the merged spots are the engine's ([PlanRoute], the same on iPhone);
            // the names are added here.
            val route = PlanRoute.of(trip, items)
            val out = route.stops.map { stop ->
                val item = stop.item
                val end = stop.step.end
                PlannedWaypoint(
                    stop.id, (if (end) PlanSpan.endName(item) else item.title).ifBlank { item.placeName },
                    stop.lat, stop.lng, if (end) PlanSpan.endDate(item) else item.day,
                    place = placeOf(route.live, item), travel = item.kind == PlanKind.Transport.id,
                    hours = if (end) null else PlanSpan.hours(item), tags = stop.tags,
                )
            }
            return PlannedReplay(out, route.missingLocations, out.count { it.day == null })
        }

        /**
         * The town or area a plan item sits in, for the clip's place name: the plan's arrival near it ("Transfer to
         * El Chaltén"), else its place name's area ("Laguna Capri, El Chaltén, Argentina" → "El Chaltén"). Null when
         * neither says more than the item's own title.
         */
        fun placeOf(items: List<PlanItem>, item: PlanItem): String? =
            PlaceGrouping.placeOf(items, item)?.trim()?.takeIf { it.isNotEmpty() && (it != item.title.trim() || item.placeName.isNotBlank()) }
    }
}

/** Only sources with enough actual coordinates and distance are offered. No location is guessed from a title. */
object ClipSources {
    fun available(
        trip: Trip, points: List<TrackPoint>, stops: List<Stop>, items: List<PlanItem>, media: List<Media>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<ClipSource, ClipInput> {
        val common = ClipInput.from(trip, points, stops, media, zone)
        val result = linkedMapOf<ClipSource, ClipInput>()
        fun usable(route: List<GeoPoint>) = route.size >= 2 && TrackShaping.lengthM(route) >= ClipPlan.MIN_ROUTE_M
        val recorded = common.takeIf { usable(TrackShaping.clean(it.points)) }
        // The trip plan comes first, so it is the clip's route by default (owner, 2026-11-11: "in clip have by default
        // the planned"); the recorded journey and the saved stops follow.
        val planned = PlannedReplay.build(trip, items)
        val plannedPoints = planned.timeline(zone)
        if (usable(plannedPoints)) {
            result[ClipSource.Planned] = common.copy(
                source = ClipSource.Planned, planned = planned, points = emptyList(),
                stops = planned.waypoints.zip(plannedPoints) { p, point -> ClipStop(point.time, p.lat, p.lng, p.title, null, p.itemId) },
                routeLegs = RouteLegs.fromPlanned(trip, planned),
            )
        }
        if (recorded != null) result[ClipSource.Recorded] = recorded
        val saved = common.stops.map { GeoPoint(it.time, it.lat, it.lng) }
        if (usable(saved)) result[ClipSource.Stops] = common.copy(source = ClipSource.Stops, points = emptyList(),
            routeLegs = RouteLegs.savedStops(trip, stops, zone))
        return result
    }
}

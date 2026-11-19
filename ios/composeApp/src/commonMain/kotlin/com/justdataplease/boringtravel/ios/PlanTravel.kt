package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.Geo
import com.justdataplease.boringtravel.clip.Mercator
import com.justdataplease.boringtravel.clip.PlanRoute
import com.justdataplease.boringtravel.clip.RouteLeg
import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.TravelTime
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Travel between the plan's places, as the Android app works it out (clip/RouteLegs.kt and PlannedReplay.kt,
 * ui/trip/PlanTravelRows.kt, LegRow.kt, TravelDefault.kt, TravelPicker.kt there), written again here because those
 * files read the clip's and the place page's Java code. The order of the places is the engine's (PlanRoute), the
 * defaults its TravelDefault, the rough times its TravelTime and the saved choices its TravelModes: the same legs, the
 * same keys and the same numbers as on Android.
 */

/** A planned place on the route, with the town or area it is in ([place]) and whether it is a travel item. */
data class PlannedWaypoint(
    val itemId: String,
    val title: String,
    val lat: Double,
    val lng: Double,
    val day: LocalDate?,
    val place: String? = null,
    val travel: Boolean = false,
)

/** The planner and the plan's travel rows share exactly the same ordered pairs of places. */
object RouteLegs {
    /** The located items in plan order (an end on a later day is a stop there too), as places with their towns. */
    fun waypoints(trip: Trip, items: List<PlanItem>): List<PlannedWaypoint> {
        val route = PlanRoute.of(trip, items)
        return route.stops.map { stop ->
            val item = stop.item
            val end = stop.step.end
            PlannedWaypoint(
                stop.id, (if (end) PlanSpan.endName(item) else item.title).ifBlank { item.placeName },
                stop.lat, stop.lng, if (end) PlanSpan.endDate(item) else item.day,
                place = placeOf(route.live, item), travel = item.kind == PlanKind.Transport.id,
            )
        }
    }

    /** Every leg between two planned places, with the trip's saved choices. */
    fun planned(trip: Trip, items: List<PlanItem>): List<RouteLeg> = withChoices(
        trip,
        waypoints(trip, items).zipWithNext { from, to ->
            RouteLeg(
                RouteLegKey(RouteLegSource.Planned, from.itemId, to.itemId), from.title.ifBlank { "Place" }, to.title.ifBlank { "Place" },
                from.day, to.day, null,
                samePlace = samePlace(from, to), distanceM = Geo.distanceM(from.lat, from.lng, to.lat, to.lng), toTravel = to.travel,
            )
        },
    )

    /** Whether planned places [a] and [b] are items of one place on one day, near each other (walked unless chosen otherwise). */
    fun samePlace(a: PlannedWaypoint, b: PlannedWaypoint): Boolean = TravelDefault.samePlace(
        a.place ?: a.title, b.place ?: b.title, a.day, b.day, Geo.distanceM(a.lat, a.lng, b.lat, b.lng),
    )

    fun withChoices(trip: Trip, legs: List<RouteLeg>): List<RouteLeg> {
        val saved = TravelModes.choices(trip.travelModes)
        val km = TravelModes.kmChoices(trip.travelModes)
        return legs.map { it.copy(vehicle = saved[it.key], countKm = km[it.key]) }
    }

    /**
     * The trip's saved stops in time order, the second of two at one spot (within 5 m) left out: what the map joins
     * when there is no recorded route (the Android app's RouteLegs.orderedStops).
     */
    fun orderedStops(trip: Trip, stops: List<Stop>): List<Stop> {
        val result = ArrayList<Stop>()
        for (stop in stops.filter { it.tripId == trip.id && it.deletedAt == null && validLocation(it.lat, it.lng) }
            .sortedWith(compareBy<Stop>({ it.arrivedAt }, { it.id }))) {
            val prior = result.lastOrNull()
            if (prior == null || Geo.distanceM(prior.lat, prior.lng, stop.lat, stop.lng) >= 5.0) result += stop
        }
        return result
    }

    private fun validLocation(lat: Double, lng: Double) =
        lat.isFinite() && lng.isFinite() && lat in -Mercator.MAX_LAT..Mercator.MAX_LAT && lng in -180.0..180.0

    /**
     * The town or area a plan item sits in: the plan's arrival near it, else its place name's area. Null when neither
     * says more than the item's own title.
     */
    fun placeOf(items: List<PlanItem>, item: PlanItem): String? =
        PlaceGrouping.placeOf(items, item)?.trim()?.takeIf { it.isNotEmpty() && (it != item.title.trim() || item.placeName.isNotBlank()) }
}

/** The leg's chosen vehicle, else [TravelDefault] (on foot between the items of one place); the trip video goes by the same. */
val RouteLeg.travel: Vehicle get() = vehicle?.takeUnless { it == Vehicle.Auto } ?: TravelDefault.between(toTitle, samePlace, distanceM, toTravel)

/** Whether the leg adds to the trip's km: its own "Count in km", else every leg but a flight ([TravelDefault.countsKm]). */
val RouteLeg.countsKm: Boolean get() = TravelDefault.countsKm(travel, countKm)

/** One incoming connection, attached to its destination's plan-item id. */
data class PlanTravelConnection(val leg: RouteLeg) {
    val fromLabel: String get() = leg.fromTitle.ifBlank { "Previous place" }
    val toLabel: String get() = leg.toTitle.ifBlank { "Next place" }

    /** The chosen vehicle, else the default (Car unless the destination says otherwise, see [TravelDefault]). */
    val mode: Vehicle get() = leg.travel

    /** Where the leg starts when it is the first place of the day shown: the day before. */
    val originDay: LocalDate? get() = leg.fromDay?.takeIf { it != leg.toDay }
}

object PlanTravelRows {
    /** Built across the whole trip, then looked up by the row each leg arrives at. */
    fun incoming(trip: Trip, items: List<PlanItem>): Map<String, PlanTravelConnection> =
        RouteLegs.planned(trip, items).associate { it.key.toId to PlanTravelConnection(it) }

    /** An open chooser never drifts onto a new origin after a move or a deletion. */
    fun resolve(incoming: Map<String, PlanTravelConnection>, key: RouteLegKey?): PlanTravelConnection? =
        key?.let { incoming[it.toId]?.takeIf { row -> row.leg.key == it } }
}

/**
 * A rough door-to-door time for a leg, from the straight-line distance and the vehicle ([TravelTime]: a detour factor,
 * a typical speed, and a fixed overhead for flights). Always shown with "≈"; never stored.
 */
object LegEstimate {
    /** Minutes for [distanceM] by [vehicle], or null for no vehicle or no distance. */
    fun minutes(vehicle: Vehicle, distanceM: Double): Int? = TravelTime.minutes(vehicle, distanceM)?.roundToInt()

    /** Rounded for reading: 5 min steps under an hour, quarters under three hours, whole hours after. */
    fun rounded(minutes: Int): Int = when {
        minutes < 60 -> ((minutes + 2) / 5 * 5).coerceAtLeast(5)
        minutes < 180 -> ((minutes + 7) / 15) * 15
        else -> ((minutes + 30) / 60) * 60
    }

    /** "≈ 15 min", "≈ 1 h 30", "≈ 3 h". */
    fun short(minutes: Int): String {
        val m = rounded(minutes)
        return when {
            m < 60 -> "≈ $m min"
            m % 60 == 0 -> "≈ ${m / 60} h"
            else -> "≈ ${m / 60} h ${(m % 60).toString().padStart(2, '0')}"
        }
    }

    /** "About 3 hours by road", "About 40 minutes on foot", "About 2 h 30 by air" (the map's leg sheet). */
    fun sentence(minutes: Int, vehicle: Vehicle): String {
        val m = rounded(minutes)
        val time = when {
            m < 60 -> "$m minutes"
            m == 60 -> "1 hour"
            m % 60 == 0 -> "${m / 60} hours"
            else -> "${m / 60} h ${(m % 60).toString().padStart(2, '0')}"
        }
        val how = when (vehicle) {
            Vehicle.Walk -> "on foot"
            Vehicle.Bike -> "by bike"
            Vehicle.Car -> "by road"
            Vehicle.Train -> "by train"
            Vehicle.Ferry -> "by boat"
            Vehicle.Plane -> "by air"
            Vehicle.Auto -> ""
        }
        return "About $time $how".trim()
    }
}

/**
 * What a leg row between two plan items shows: the vehicle chosen for the leg (or none yet) and a rough time, e.g.
 * "Car · ≈ 3 h", "Walk · ≈ 20 min", "Choose travel". [fromLabel] is set only when the leg starts on another day.
 */
data class LegRowModel(val vehicle: Vehicle?, val estimate: String?, val fromLabel: String?, val countsKm: Boolean = true) {
    /** The small words after the chip: where it starts from (another day) and "not in km" for a leg left out. */
    val aside: String? get() = listOfNotNull(fromLabel?.let { "from $it" }, "not in km".takeUnless { countsKm }).joinToString(" · ").ifEmpty { null }

    val text: String get() = when {
        vehicle == null -> "Choose travel"
        estimate == null -> vehicle.label
        else -> "${vehicle.label} · $estimate"
    }

    companion object {
        fun of(connection: PlanTravelConnection, from: PlanItem?, to: PlanItem?): LegRowModel {
            val mode = connection.mode
            val distance = if (from != null && to != null) distance(from, to) else null
            val estimate = if (distance != null) LegEstimate.minutes(mode, distance)?.let(LegEstimate::short) else null
            return LegRowModel(mode, estimate, connection.fromLabel.takeIf { connection.originDay != null }, connection.leg.countsKm)
        }

        private fun distance(a: PlanItem, b: PlanItem): Double? {
            val aLat = a.lat ?: return null
            val aLng = a.lng ?: return null
            val bLat = b.lat ?: return null
            val bLng = b.lng ?: return null
            return Geo.distanceM(aLat, aLng, bLat, bLng)
        }
    }
}

/** The ways to travel a leg, in the order the item sheet's Getting there shows them. */
val TRAVEL_CHOICES = listOf(Vehicle.Car, Vehicle.Train, Vehicle.Plane, Vehicle.Ferry, Vehicle.Bike, Vehicle.Walk)

/** "2,370 km in the trip's total" or "… left out of the trip's total" (no number when the leg's length is unknown). */
fun legKmLine(distanceM: Double, counted: Boolean): String {
    val km = distanceM.takeIf { it.isFinite() && it > 0 }?.let { kmText(it / 1000.0) }
    return when {
        km == null -> if (counted) "In the trip's total" else "Left out of the trip's total"
        counted -> "$km in the trip's total"
        else -> "$km, left out of the trip's total"
    }
}

/**
 * "4.2 km" under 10 km, else "2,370 km" (the Android app's ClipText.km with Locale.getDefault(), as Java writes it in the
 * phone's language: "4,2 km", "2.370 km" on a Greek phone).
 */
fun kmText(km: Double, locale: com.justdataplease.boringtravel.compat.Locale = com.justdataplease.boringtravel.compat.Locale.getDefault()): String =
    if (km < 10) com.justdataplease.boringtravel.tracking.RouteShapeText.oneDecimalKm(km, locale)
    else com.justdataplease.boringtravel.tracking.RouteShapeText.groupedKm(km.roundToLong(), locale)

/** What to save when the switch is set to [on] for a leg by [vehicle]: null when that is the default anyway. */
fun legKmChoice(vehicle: Vehicle, on: Boolean): Boolean? = on.takeIf { it != TravelDefault.countsKm(vehicle, null) }

/** "From Bariloche · ≈ 2 h" for a leg into a place (the time only when both ends have a location). */
fun gettingThereLine(fromLabel: String, vehicle: Vehicle, distanceM: Double?): String =
    listOfNotNull("From $fromLabel", distanceM?.let { LegEstimate.minutes(vehicle, it) }?.let(LegEstimate::short)).joinToString(" · ")

/**
 * Where the leg into [item] comes from, as the item sheet says it ("From Mendoza" between places, "From Boutique
 * hotel" within one), and how far that is in metres (null: one end has no pin).
 */
fun gettingThereFrom(connection: PlanTravelConnection, items: List<PlanItem>, item: PlanItem): Pair<String, Double?> {
    val from = items.firstOrNull { it.id == connection.leg.key.fromItemId }
    val distance = from?.let { f ->
        if (PlaceGrouping.located(f) && PlaceGrouping.located(item)) Geo.distanceM(f.lat!!, f.lng!!, item.lat!!, item.lng!!) else null
    }
    val label = from?.takeIf { distance == null || distance > PlaceGrouping.RADIUS_M }?.let { PlaceGrouping.placeOf(items, it) }
        ?: connection.fromLabel
    return label to distance
}

package com.justdataplease.boringtravel.ui.map

import com.justdataplease.boringtravel.clip.Geo
import com.justdataplease.boringtravel.clip.RouteLeg
import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.place.PlaceGrouping
import com.justdataplease.boringtravel.ui.trip.LegEstimate
import com.justdataplease.boringtravel.ui.trip.travel
import java.time.LocalDate

/** One leg as the map's sheet shows it: "Day 13 · leg 9 of 21", "El Calafate → El Chaltén", "About 3 hours by road". */
data class LegCard(
    val leg: RouteLeg,
    val index: Int,
    val count: Int,
    val dayNumber: Int?,
    val from: String,
    val to: String,
    val distanceM: Double?,
) {
    /** The chosen vehicle, else the default ([com.justdataplease.boringtravel.model.TravelDefault]). */
    val vehicle: Vehicle get() = leg.travel

    val eyebrow: String get() = listOfNotNull(dayNumber?.let { "Day $it" }, "Leg ${index + 1} of $count").joinToString(" · ")

    val title: String get() = "$from → $to"

    /** "About 3 hours by road", or a nudge to choose how this leg is travelled. */
    val note: String get() {
        val v = vehicle
        val minutes = distanceM?.let { LegEstimate.minutes(v, it) } ?: return "By ${v.label.lowercase()}"
        return LegEstimate.sentence(minutes, v)
    }
}

/** The planned legs of a trip for the map's sheet, and which one is "now" (pure, unit tested). */
object LegSheetModel {
    /**
     * The legs between places ("El Calafate → El Chaltén"): planned legs longer than a place ([PlaceGrouping.RADIUS_M]),
     * each end named after its place. A trip that stays in one place shows all its legs, named after the items.
     */
    fun cards(trip: Trip, items: List<PlanItem>): List<LegCard> {
        val legs = RouteLegs.planned(trip, items)
        val byId = items.associateBy { it.id }
        fun distance(leg: RouteLeg): Double? {
            val from = byId[leg.key.fromItemId]
            val to = byId[leg.key.toItemId]
            return if (from != null && to != null && PlaceGrouping.located(from) && PlaceGrouping.located(to)) {
                Geo.distanceM(from.lat!!, from.lng!!, to.lat!!, to.lng!!)
            } else null
        }
        val between = legs.filter { (distance(it) ?: Double.MAX_VALUE) > PlaceGrouping.RADIUS_M }
        val shown = between.ifEmpty { legs }
        val byPlace = between.isNotEmpty()
        return shown.mapIndexed { i, leg ->
            val from = byId[leg.key.fromItemId]
            val to = byId[leg.key.toItemId]
            fun name(item: PlanItem?, fallback: String) =
                (if (byPlace) PlaceGrouping.placeOf(items, item) else item?.let(PlaceGrouping::itemPlaceLabel)) ?: fallback
            val day = leg.toDay?.let { dayNumber(trip, it) }
            LegCard(leg, i, shown.size, day, name(from, leg.fromTitle), name(to, leg.toTitle), distance(leg))
        }
    }

    /** The leg the trip has reached by [today]: the last one arriving on or before today; the first before the trip. */
    fun currentIndex(cards: List<LegCard>, today: LocalDate): Int {
        if (cards.isEmpty()) return -1
        val i = cards.indexOfLast { c -> c.leg.toDay?.let { !it.isAfter(today) } == true }
        return if (i >= 0) i else 0
    }

    private fun dayNumber(trip: Trip, day: LocalDate): Int? {
        val start = trip.startDate ?: return null
        val end = trip.endDate ?: return null
        if (day.isBefore(start) || day.isAfter(end)) return null
        return (day.toEpochDay() - start.toEpochDay() + 1).toInt()
    }
}

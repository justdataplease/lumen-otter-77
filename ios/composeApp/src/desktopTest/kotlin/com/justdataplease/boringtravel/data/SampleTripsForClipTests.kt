package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.compat.ZoneId
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.ios.LinkPlaces
import com.justdataplease.boringtravel.ios.SampleRows
import com.justdataplease.boringtravel.ios.SampleTrips
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.ProgramTrips
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.programs.data.LisbonPorto
import java.time.LocalDate

/*
 * The Android app's example plans as its clip tests build them (data/SampleArgentina.kt, SampleKyoto.kt,
 * SampleLisbon.kt and SampleBuilder.plan there), made from this app's copies of the same tables (ios/DemoData.kt) and
 * the engine's trip ideas, so the Android app's own clip tests run here unchanged. The plan is all the tests read.
 */

/** SampleBuilder.plan: the trip from [start], [days] long, and its rows as plan items, the rows' travel set on the legs. */
private fun plan(tripId: String, title: String, subtitle: String, start: LocalDate, days: Int, rows: List<SampleRows.Row>, t: Long, doneBefore: Int): Pair<Trip, List<PlanItem>> {
    val trip = Trip(tripId, title, subtitle, start, start.plusDays((days - 1).toLong()), trackingLevel = "off", createdAt = t, updatedAt = t)
    val positions = HashMap<Int?, Double>()
    val items = rows.map { r ->
        val pos = (positions[r.day] ?: 0.0) + 1.0
        positions[r.day] = pos
        LinkPlaces.sortLinks(
            PlanItem(
                id = newId(), tripId = tripId, day = r.day?.let { start.plusDays((it - 1).toLong()) }, position = pos,
                kind = r.kind, title = r.title, note = r.note, lat = r.at?.first, lng = r.at?.second,
                done = r.day != null && r.day < doneBefore && r.kind != "note" && !r.optional,
                createdAt = t, updatedAt = t, url = r.url,
            ),
        )
    }
    val viaById = rows.zip(items).mapNotNull { (r, item) -> r.via?.let { item.id to it } }.toMap()
    var modes: String? = null
    for (leg in RouteLegs.planned(trip, items)) {
        val via = viaById[leg.key.toId] ?: continue
        modes = TravelModes.set(modes, leg.key, via)
    }
    return trip.copy(travelModes = modes) to items
}

object SampleArgentina {
    const val TRIP_ID = com.justdataplease.boringtravel.ios.SampleArgentina.TRIP_ID
    val ZONE: ZoneId = ZoneId.of(com.justdataplease.boringtravel.ios.SampleArgentina.ZONE)

    data class Plan(val trip: Trip, val items: List<PlanItem>)

    fun plan(start: LocalDate, t: Long, tripId: String = TRIP_ID, doneBefore: Int = 1): Plan {
        val a = com.justdataplease.boringtravel.ios.SampleArgentina
        val (trip, items) = plan(tripId, a.TITLE, a.SUBTITLE, start, a.DAYS, a.rows, t, doneBefore)
        return Plan(trip, items)
    }
}

object SampleKyoto {
    fun plan(start: LocalDate, t: Long): ProgramTrips.Built = ProgramTrips.build(
        KyotoOsaka.destination, KyotoOsaka.destination.default, Pace.Balanced, start, t,
        links = LinkPlaces::sortLinks, tripId = SampleTrips.KYOTO, trackingLevel = "off",
    )
}

object SampleLisbon {
    val ZONE: ZoneId = ZoneId.of("Europe/Lisbon")

    fun plan(start: LocalDate, t: Long): ProgramTrips.Built {
        val program = LisbonPorto.destination.default
        return ProgramTrips.build(
            LisbonPorto.destination, program, Pace.Balanced, start, t,
            links = LinkPlaces::sortLinks, tripId = SampleTrips.LISBON, doneBefore = program.days.size + 1, trackingLevel = "off",
        )
    }
}

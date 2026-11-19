package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.links.LinkPlaces
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.Vehicle
import java.time.LocalDate

/**
 * "Copy this trip": someone else's posted plan becomes a trip of the viewer's own, the way a trip idea does
 * (programs/ProgramTrips): new ids, day N on [start] + N − 1 (ideas stay ideas), nothing ticked, the poster's travel
 * choices on the same legs, and every item marked as coming from the community (source "community", externalId
 * "community:<postId>:<index>"). A plan of ideas only (no days) makes a trip without dates. Pure (TripCopyTest).
 */
object TripCopy {
    data class Built(val trip: Trip, val items: List<PlanItem>)

    /** Whether copying asks for a first day: only a plan with days needs one. */
    fun needsStart(shared: SharedTrip): Boolean = shared.days > 0 && shared.items.any { it.day > 0 }

    fun build(
        shared: SharedTrip,
        title: String,
        postId: String,
        start: LocalDate?,
        now: Long,
        ids: () -> String = ::newId,
    ): Built {
        val tripId = ids()
        val dated = start != null && needsStart(shared)
        val end = if (dated) start!!.plusDays((shared.days - 1).coerceAtLeast(0).toLong()) else null
        val trip = Trip(
            id = tripId, title = title.trim().ifEmpty { "Trip" }.take(CommunityLimits.TITLE),
            startDate = if (dated) start else null, endDate = end, createdAt = now, updatedAt = now,
        )
        val items = ArrayList<PlanItem>(shared.items.size)
        val via = HashMap<String, Vehicle>()
        val positions = HashMap<Int, Int>()
        shared.items.forEachIndexed { index, s ->
            // Without a first day every item is an idea, kept in the plan's order.
            val dayNumber = if (dated) s.day else 0
            val position = (positions[dayNumber] ?: 0) + 1
            positions[dayNumber] = position
            val located = s.lat != null && s.lng != null
            // A post's Google Maps link is the copy's Maps link, any other its website.
            val row = LinkPlaces.sortLinks(PlanItem(
                id = ids(), tripId = tripId,
                day = if (dayNumber > 0) start!!.plusDays((dayNumber - 1).toLong()) else null,
                position = position.toDouble(),
                timeMinutes = s.time?.takeIf { it in 0 until 24 * 60 },
                kind = PlanKind.of(s.kind).id,
                title = s.title.trim().ifEmpty { "Place" },
                placeName = s.place.trim(),
                lat = if (located) s.lat else null, lng = if (located) s.lng else null,
                done = false,
                source = ItemSource.Community.id, externalId = externalId(postId, index),
                createdAt = now, updatedAt = now,
                url = s.link?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            ))
            items += row
            Vehicle.entries.firstOrNull { it.id == s.via && it != Vehicle.Auto }?.takeIf { located }?.let { via[row.id] = it }
        }
        var modes: String? = null
        if (via.isNotEmpty()) {
            for (leg in RouteLegs.planned(trip, items)) {
                val v = via[leg.key.toId] ?: continue
                modes = TravelModes.set(modes, leg.key, v)
            }
        }
        return Built(trip.copy(travelModes = modes), items)
    }

    /** "community:<postId>:<index>": item [index] of the shared plan of post [postId]. */
    fun externalId(postId: String, index: Int): String = "community:$postId:$index"
}

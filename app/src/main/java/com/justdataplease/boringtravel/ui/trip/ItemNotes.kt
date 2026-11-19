package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.checkin.DailyCheckIn
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.places.Geo
import com.justdataplease.boringtravel.ui.place.PlaceGrouping
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * The journal notes written for one plan item (owner, 2026-11-11: "when I select a plan item, see the notes written
 * for this item … click them and open them from there"): the trip's notes whose place is the item (written from it,
 * picked from "From this trip", or typed with its name) or that were written within [NEAR_M] of it. Oldest first.
 * Pure, unit tested.
 */
object ItemNotes {
    /** A note written this close to the item is about it (the museum, not the next street). */
    const val NEAR_M = 100.0

    fun of(item: PlanItem, entries: List<Entry>): List<Entry> {
        val lat = item.lat?.takeIf { it.isFinite() }
        val lng = item.lng?.takeIf { it.isFinite() }
        return entries.filter { e ->
            if (e.deletedAt != null || e.tripId != item.tripId) return@filter false
            val near = lat != null && lng != null && e.lat != null && e.lng != null &&
                Geo.distanceM(lat, lng, e.lat!!, e.lng!!) <= NEAR_M
            named(item, e.placeName) || near
        }.sortedWith(compareBy<Entry>({ it.occurredAt }, { it.id }))
    }

    /** A note's place names [item]: its name alone, or its name with a place in brackets ([location]). */
    fun named(item: PlanItem, place: String): Boolean {
        val name = item.title.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return false
        val p = place.trim().lowercase(Locale.ROOT)
        return p == name || (p.startsWith("$name (") && p.endsWith(")"))
    }

    /**
     * The place of a note about [item] (owner, 2026-11-12: "on journal as location keep the plan item name and
     * parenthesis the place"): "Alhambra (Granada)"; the name alone when the place is the name. [items]: the trip's plan,
     * for the town it arrived in.
     */
    fun location(item: PlanItem, items: List<PlanItem>): String {
        val name = item.title.trim()
        val place = PlaceGrouping.placeOf(items, item)?.trim().orEmpty()
        return when {
            name.isEmpty() -> place
            place.isEmpty() || place.equals(name, ignoreCase = true) -> name
            else -> "$name ($place)"
        }
    }

    /**
     * A new note [id] about [item], written at [now] (owner, 2026-11-12: "from inside plan item add a journal entry"):
     * in its trip, at its [location] and point, dated on its day when that day has passed (at its time, else midday).
     */
    fun newEntry(id: String, item: PlanItem, items: List<PlanItem>, now: Long, zone: ZoneId): Entry {
        val day = item.day
        val at = if (day != null && day < Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) {
            val minutes = (item.timeMinutes ?: (12 * 60)).coerceIn(0, 24 * 60 - 1)
            day.atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(zone).toInstant().toEpochMilli()
        } else now
        val located = DailyCheckIn.validCoordinates(item.lat, item.lng)
        return Entry(
            id = id, tripId = item.tripId, occurredAt = at, zoneId = zone.id, placeName = location(item, items),
            lat = item.lat.takeIf { located }, lng = item.lng.takeIf { located }, createdAt = now, updatedAt = now,
        )
    }
}

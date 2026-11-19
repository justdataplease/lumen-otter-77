package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.time.LocalDate
import kotlin.math.abs

/*
 * The places a trip already names, for picking one when writing (the Android app's places/TripPlaces.kt and
 * ui/trip/ItemNotes.kt's location), written again here because those files use java.time and Java's Locale.
 */

/** A place the trip already has, with its point when the trip knows one. [onDay]: a plan item of the note's own day. */
data class KnownPlace(val name: String, val lat: Double? = null, val lng: Double? = null, val onDay: Boolean = false)

object TripPlaces {
    /**
     * [items], [stops] and [entries] of one trip; [day] is the note's date. Order: the plan's items on [day] by name, as a
     * note about them is placed ("Alhambra (Granada)"; a ride by where it goes), then those items' towns, earlier notes'
     * places (newest first), the saved stops (newest first), then the rest of the plan, the days nearest [day] first.
     * Plain notes in the plan are not places. A name seen again keeps its first place and takes a point when it had none.
     */
    fun of(items: List<PlanItem>, stops: List<Stop>, entries: List<Entry>, day: LocalDate?, exceptEntry: String? = null): List<KnownPlace> {
        val out = LinkedHashMap<String, KnownPlace>()
        fun add(name: String?, lat: Double?, lng: Double?, onDay: Boolean = false) {
            val clean = name?.trim().orEmpty()
            if (clean.isEmpty()) return
            val key = clean.lowercase()
            val had = out[key]
            if (had == null) out[key] = KnownPlace(clean, lat, lng, onDay)
            else if (had.lat == null && lat != null && lng != null) out[key] = had.copy(lat = lat, lng = lng)
        }
        val live = items.filter { it.deletedAt == null }
        fun town(i: PlanItem): String? {
            if (PlaceGrouping.located(i)) PlaceGrouping.arrivalName(live, i.day, i.lat!!, i.lng!!)?.let { return it }
            return PlaceGrouping.itemPlaceLabel(i).takeIf { it != i.title.trim() || i.placeName.isNotBlank() }
        }
        fun addItself(i: PlanItem, onDay: Boolean) = when (PlanKind.of(i.kind)) {
            PlanKind.Note -> Unit
            PlanKind.Transport -> add(town(i), null, null, onDay)
            else -> {
                val pinned = PlaceGrouping.located(i)
                add(ItemNotes.location(i, live), i.lat.takeIf { pinned }, i.lng.takeIf { pinned }, onDay)
            }
        }
        if (day != null) {
            val today = live.filter { it.day == day }.sortedBy { it.position }
            today.forEach { addItself(it, onDay = true) }
            today.forEach { add(town(it), null, null) }
        }
        entries.filter { it.deletedAt == null && it.id != exceptEntry }.sortedByDescending { it.occurredAt }
            .forEach { add(it.placeName, it.lat, it.lng) }
        stops.filter { it.deletedAt == null }.sortedByDescending { it.arrivedAt }.forEach { add(it.name, it.lat, it.lng) }
        live.sortedWith(compareBy<PlanItem>({ it.day == null }, { gap(it.day, day) }, { it.day?.toEpochDay() }, { it.position })).forEach {
            addItself(it, onDay = false)
            add(town(it), null, null)
        }
        return out.values.toList()
    }

    private fun gap(a: LocalDate?, b: LocalDate?): Long = if (a == null || b == null) 0 else abs(a.toEpochDay() - b.toEpochDay())

    /** [places] while [typed] is in the field: the names starting with it, then the ones with it, then the rest. */
    fun suggest(places: List<KnownPlace>, typed: String): List<KnownPlace> {
        val q = typed.trim().lowercase()
        if (q.isEmpty()) return places
        fun rank(p: KnownPlace): Int {
            val n = p.name.lowercase()
            return when {
                n.startsWith(q) -> 0
                n.contains(q) -> 1
                else -> 2
            }
        }
        return places.sortedBy(::rank)
    }

    /** The chips on show for [typed]: the first [limit] of [suggest], plus the note's day's plan items wherever they fall. */
    fun shown(places: List<KnownPlace>, typed: String, limit: Int): List<KnownPlace> =
        suggest(places, typed).filterIndexed { i, p -> i < limit || p.onDay }

    /** The note's point after [picked] is chosen while the note is at [lat], [lng]. */
    fun pointAfterPick(places: List<KnownPlace>, picked: KnownPlace, lat: Double?, lng: Double?): LatLng? {
        if (picked.lat != null && picked.lng != null) return LatLng(picked.lat, picked.lng)
        if (lat == null || lng == null) return null
        return if (places.any { it.lat == lat && it.lng == lng }) null else LatLng(lat, lng)
    }
}

/** The journal notes about one plan item, and how a note about it is placed (the Android app's ui/trip/ItemNotes.kt). */
object ItemNotes {
    /** A note written this close to the item is about it (the museum, not the next street). */
    const val NEAR_M = 100.0

    /** The trip's notes whose place is [item] or that were written within [NEAR_M] of it, oldest first. */
    fun of(item: PlanItem, entries: List<Entry>): List<Entry> {
        val lat = item.lat?.takeIf { it.isFinite() }
        val lng = item.lng?.takeIf { it.isFinite() }
        return entries.filter { e ->
            if (e.deletedAt != null || e.tripId != item.tripId) return@filter false
            val near = lat != null && lng != null && e.lat != null && e.lng != null &&
                com.justdataplease.boringtravel.places.Geo.distanceM(lat, lng, e.lat!!, e.lng!!) <= NEAR_M
            named(item, e.placeName) || near
        }.sortedWith(compareBy<Entry>({ it.occurredAt }, { it.id }))
    }

    /** A note's place names [item]: its name alone, or its name with a place in brackets ([location]). */
    fun named(item: PlanItem, place: String): Boolean {
        val name = item.title.trim().lowercase()
        if (name.isEmpty()) return false
        val p = place.trim().lowercase()
        return p == name || (p.startsWith("$name (") && p.endsWith(")"))
    }

    /** The place of a note about [item]: "Alhambra (Granada)"; the name alone when the place is the name. */
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
     * A new note [id] about [item], written at [now] in [zone] (the item sheet's Write; Android's ItemNotes.newEntry):
     * in its trip, at its [location] and point, dated on its day when that day has passed (at its time, else midday, on
     * [zone]'s clocks).
     */
    fun newEntry(id: String, item: PlanItem, items: List<PlanItem>, now: Long, zone: String): Entry {
        val day = item.day
        val at = if (day != null && day.toEpochDay() < com.justdataplease.boringtravel.time.localDateAt(now, zone).toEpochDay()) {
            val minutes = (item.timeMinutes ?: (12 * 60)).coerceIn(0, 24 * 60 - 1)
            // The wall time on that day in [zone] (java.time's day.atTime(time).atZone(zone)).
            val wall = day.toEpochDay() * 86_400_000L + minutes * 60_000L
            wall - zoneOffsetMs(wall - zoneOffsetMs(wall, zone), zone)
        } else now
        val located = com.justdataplease.boringtravel.checkin.DailyCheckIn.validCoordinates(item.lat, item.lng)
        return Entry(
            id = id, tripId = item.tripId, occurredAt = at, zoneId = zone, placeName = location(item, items),
            lat = item.lat.takeIf { located }, lng = item.lng.takeIf { located }, createdAt = now, updatedAt = now,
        )
    }
}

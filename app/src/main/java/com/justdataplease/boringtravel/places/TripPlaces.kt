package com.justdataplease.boringtravel.places

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.ui.place.PlaceGrouping
import com.justdataplease.boringtravel.ui.trip.ItemNotes
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * A place the trip already has, with its point when the trip knows one. [onDay]: a plan item of the note's own day,
 * always offered (never cut off with the rest).
 */
data class KnownPlace(val name: String, val lat: Double? = null, val lng: Double? = null, val onDay: Boolean = false)

/**
 * The places a trip already names, for picking one when writing (owner, 2026-11-10: "when writing a note should be
 * able to select a place from existing"; 2026-11-12: "I still do not see trip item names"). Each name once, the
 * likeliest first.
 */
object TripPlaces {
    /**
     * [items], [stops] and [entries] of one trip; [day] is the entry's date. Order: the plan's items on [day] by name, as
     * a note about them is placed ("Alhambra (Granada)", [ItemNotes.location]; a ride by where it goes), then those
     * items' towns, earlier entries' places (newest first), the saved stops (newest first), then the rest of the plan,
     * the days nearest [day] first. Plain notes in the plan are not places. A name seen again (whatever its case)
     * keeps its first place and takes a point when it had none.
     */
    fun of(items: List<PlanItem>, stops: List<Stop>, entries: List<Entry>, day: LocalDate?, exceptEntry: String? = null): List<KnownPlace> {
        val out = LinkedHashMap<String, KnownPlace>()
        fun add(name: String?, lat: Double?, lng: Double?, onDay: Boolean = false) {
            val clean = name?.trim().orEmpty()
            if (clean.isEmpty()) return
            val key = clean.lowercase(Locale.ROOT)
            val had = out[key]
            if (had == null) out[key] = KnownPlace(clean, lat, lng, onDay)
            else if (had.lat == null && lat != null && lng != null) out[key] = had.copy(lat = lat, lng = lng)
        }
        val live = items.filter { it.deletedAt == null }
        // The town an item is in: the plan's arrival near it ("Transfer to El Chaltén"), its own place name, or a
        // ride's destination; never a bare title ("Tango show" is not a place).
        fun town(i: PlanItem): String? {
            if (PlaceGrouping.located(i)) PlaceGrouping.arrivalName(live, i.day, i.lat!!, i.lng!!)?.let { return it }
            return PlaceGrouping.itemPlaceLabel(i).takeIf { it != i.title.trim() || i.placeName.isNotBlank() }
        }
        // The item itself: a ride by where it goes (no point: the station isn't the town), a thing to be at by name with
        // its town and its pin (a name alone without one), a plain note not at all.
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
        live.sortedWith(compareBy<PlanItem>({ it.day == null }, { gap(it.day, day) }, { it.day }, { it.position })).forEach {
            addItself(it, onDay = false)
            add(town(it), null, null)
        }
        return out.values.toList()
    }

    /** Days between [a] and [b] (0 without both). */
    private fun gap(a: LocalDate?, b: LocalDate?): Long = if (a == null || b == null) 0 else abs(a.toEpochDay() - b.toEpochDay())

    /**
     * [places] in the order to offer them while [typed] is in the field: the names starting with it, then the ones
     * with it somewhere, then the rest as they were (a place filled in by itself must not hide the others).
     */
    fun suggest(places: List<KnownPlace>, typed: String): List<KnownPlace> {
        val q = typed.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return places
        fun rank(p: KnownPlace): Int {
            val n = p.name.lowercase(Locale.ROOT)
            return when {
                n.startsWith(q) -> 0
                n.contains(q) -> 1
                else -> 2
            }
        }
        return places.sortedBy(::rank)
    }

    /**
     * The chips on show for [typed]: the first [limit] of [suggest] over all [places] (what is typed looks through every
     * one), plus the note's day's plan items wherever they fall, so the cut never hides them.
     */
    fun shown(places: List<KnownPlace>, typed: String, limit: Int): List<KnownPlace> =
        suggest(places, typed).filterIndexed { i, p -> i < limit || p.onDay }

    /**
     * The note's point after [picked] is chosen while the note is at [lat], [lng]: the picked place's own point; else
     * none when the note's point is one of [places]' (picked before, or the plan item the note was written from: it
     * would keep the note under that item too, [ItemNotes.of] goes by 100 m); else the point the note has (the route's,
     * the phone's or the map search's).
     */
    fun pointAfterPick(places: List<KnownPlace>, picked: KnownPlace, lat: Double?, lng: Double?): LatLng? {
        if (picked.lat != null && picked.lng != null) return LatLng(picked.lat, picked.lng)
        if (lat == null || lng == null) return null
        return if (places.any { it.lat == lat && it.lng == lng }) null else LatLng(lat, lng)
    }
}

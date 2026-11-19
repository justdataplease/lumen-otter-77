package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.clip.RouteLegs
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.google.PublicDrive
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.Vehicle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.justdataplease.boringtravel.model.PlanSpan

/**
 * A trip as a post carries it: the plan day by day (day numbers, not dates, so a copy can start any day) and the
 * journal notes the poster chose. Only what the plan and those notes say: never the recorded route, stops, done ticks,
 * plan notes (they can hold booking codes) or where a note was written. Stored as JSON beside the post
 * (`posts/{id}/content/trip`), at most [CommunityLimits.TRIP_BYTES].
 */
@Serializable
data class SharedTrip(
    val v: Int = VERSION,
    /** How many days the plan spans (0: a trip of ideas only). */
    val days: Int = 0,
    val items: List<SharedItem> = emptyList(),
    val notes: List<SharedNote> = emptyList(),
) {
    /** The plan's items on day [n] (1-based), in order; day 0 = the ideas. */
    fun itemsOn(n: Int): List<SharedItem> = items.filter { it.day == n }

    val ideas: List<SharedItem> get() = itemsOn(0)

    /** The day numbers that have items, in order (ideas left out). */
    val plannedDays: List<Int> get() = items.map { it.day }.filter { it > 0 }.distinct().sorted()

    companion object {
        const val VERSION = 1
    }
}

/** One plan item. [day] 1-based, 0 = an idea; [time] minutes after midnight; [via] how the traveller gets there. */
@Serializable
data class SharedItem(
    val day: Int = 0,
    val title: String,
    val kind: String = PlanKind.Place.id,
    val place: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val time: Int? = null,
    val link: String? = null,
    val via: String? = null,
)

/** One journal note the poster chose: its day number (0 outside the plan's days), date, title, text and photos. */
@Serializable
data class SharedNote(
    val day: Int = 0,
    /** "2027-03-14", the day it was written for, in its own time zone. */
    val date: String? = null,
    val title: String = "",
    val text: String = "",
    /** Drive file ids of its photos in the post's folder. */
    val photos: List<String> = emptyList(),
)

object SharedTrips {
    // Defaults are written (the version above all, so a newer app's trips are never misread); nulls are left out.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    /** A note to share: the entry and the Drive ids its photos got. */
    data class NoteInput(val entry: Entry, val photoFileIds: List<String> = emptyList())

    /**
     * The shared form of [trip]'s plan ([items], deleted ones left out) and the chosen [notes] (in time order). Day
     * numbers count from the trip's first day, or from the plan's first day when the trip has no dates.
     */
    fun build(trip: Trip, items: List<PlanItem>, notes: List<NoteInput> = emptyList()): SharedTrip {
        val live = items.filter { it.deletedAt == null && it.tripId == trip.id && it.title.isNotBlank() }
        val itemDays = live.mapNotNull { it.day }
        val first = listOfNotNull(trip.startDate, itemDays.minOrNull()).minOrNull()
        val last = listOfNotNull(trip.endDate, itemDays.maxOrNull()).maxOrNull()
        val span = if (first != null && last != null && !last.isBefore(first)) (last.toEpochDay() - first.toEpochDay() + 1).toInt() else 0
        fun dayOf(d: LocalDate?): Int = if (d == null || first == null) 0 else (d.toEpochDay() - first.toEpochDay() + 1).toInt()
        // How each planned place is reached, from the traveller's own choices (train, plane …) on the plan's legs.
        val via = runCatching {
            RouteLegs.planned(trip, live).mapNotNull { leg -> leg.vehicle?.takeIf { it != Vehicle.Auto }?.let { leg.key.toId to it.id } }.toMap()
        }.getOrDefault(emptyMap())
        val ordered = PlanSpan.ordered(live)
        val shared = ordered.map { i ->
            SharedItem(
                day = dayOf(i.day).coerceAtLeast(0),
                title = i.title.trim().take(TITLE_MAX),
                kind = PlanKind.of(i.kind).id,
                place = i.placeName.trim().take(PLACE_MAX),
                lat = i.lat?.takeIf { it.isFinite() && it in -90.0..90.0 },
                lng = i.lng?.takeIf { it.isFinite() && it in -180.0..180.0 },
                time = i.timeMinutes?.takeIf { it in 0 until 24 * 60 },
                // One link per place in a post: the website, else the Google Maps link.
                link = (i.url ?: i.mapsUrl)?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(LINK_MAX),
                via = via[i.id],
            )
        }.map { if (it.lat == null || it.lng == null) it.copy(lat = null, lng = null) else it }
        val sharedNotes = notes.filter { it.entry.deletedAt == null }.sortedBy { it.entry.occurredAt }.map { n ->
            val date = localDate(n.entry)
            SharedNote(
                day = dayOf(date).takeIf { it in 1..span } ?: 0,
                date = date.toString(),
                title = n.entry.title.trim().take(TITLE_MAX),
                text = cut(n.entry.plainText.trim(), CommunityLimits.NOTE_TEXT),
                photos = n.photoFileIds.distinct(),
            )
        }
        return SharedTrip(days = span, items = shared, notes = sharedNotes)
    }

    fun encode(t: SharedTrip): String = json.encodeToString(SharedTrip.serializer(), t)

    /**
     * The trip in [text], or null when it is missing, damaged or from a newer app it can't read. Anyone can write a
     * post's trip, so what comes back is made safe the way [build] makes it: see [sanitize].
     */
    fun decode(text: String?): SharedTrip? {
        if (text.isNullOrBlank()) return null
        val t = runCatching { json.decodeFromString(SharedTrip.serializer(), text) }.getOrNull() ?: return null
        return t.takeIf { it.v <= SharedTrip.VERSION }?.let(::sanitize)
    }

    /**
     * [t] as the app would have written it: days 0–[CommunityLimits.MAX_DAYS], places on the globe (else no place),
     * times within the day, http(s) links only, note photos that are Drive ids, each once.
     */
    fun sanitize(t: SharedTrip): SharedTrip {
        fun day(d: Int) = d.coerceIn(0, CommunityLimits.MAX_DAYS)
        return t.copy(
            days = day(t.days),
            items = t.items.map { i ->
                val lat = i.lat?.takeIf { it.isFinite() && it in -90.0..90.0 }
                val lng = i.lng?.takeIf { it.isFinite() && it in -180.0..180.0 }
                val located = lat != null && lng != null
                i.copy(
                    day = day(i.day),
                    lat = if (located) lat else null,
                    lng = if (located) lng else null,
                    time = i.time?.takeIf { it in 0 until 24 * 60 },
                    link = i.link?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(LINK_MAX),
                )
            },
            notes = t.notes.map { n -> n.copy(day = day(n.day), photos = n.photos.filter(PublicDrive::isId).distinct()) },
        )
    }

    /** UTF-8 size of [t] as stored. */
    fun bytes(t: SharedTrip): Int = encode(t).toByteArray(Charsets.UTF_8).size

    /**
     * [t] made to fit [maxBytes]: long notes are cut first, then notes go from the last one back, then the ideas;
     * null when the plan's days alone are too big to post.
     */
    fun fit(t: SharedTrip, maxBytes: Int = CommunityLimits.TRIP_BYTES): SharedTrip? {
        if (bytes(t) <= maxBytes) return t
        var cur = t.copy(notes = t.notes.map { it.copy(text = cut(it.text, SHORT_NOTE)) })
        if (bytes(cur) <= maxBytes) return cur
        while (cur.notes.isNotEmpty()) {
            cur = cur.copy(notes = cur.notes.dropLast(1))
            if (bytes(cur) <= maxBytes) return cur
        }
        cur = cur.copy(items = cur.items.filter { it.day > 0 })
        return cur.takeIf { bytes(it) <= maxBytes }
    }

    private fun localDate(e: Entry): LocalDate =
        Instant.ofEpochMilli(e.occurredAt).atZone(runCatching { ZoneId.of(e.zoneId) }.getOrDefault(ZoneId.of("UTC"))).toLocalDate()

    private fun cut(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    private const val TITLE_MAX = 200
    private const val PLACE_MAX = 200
    private const val LINK_MAX = 1_000
    private const val SHORT_NOTE = 1_500
}

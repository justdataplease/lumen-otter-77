package com.justdataplease.boringtravel.calendar

import kotlinx.serialization.Serializable

/**
 * Our ids inside a phone calendar event. Android's calendar provider lets any app set an event's
 * `CUSTOM_APP_PACKAGE` and `CUSTOM_APP_URI` (meant for "open this event in the app that made it"); we write our
 * package and `boringtravel://trip/<tripId>/<key>`, so an update finds its own events among everything else in the
 * calendar and never touches anyone else's.
 */
object CalendarMarker {
    private const val PREFIX = "boringtravel://trip/"
    private const val SOURCE = "BoringTravel link: boringtravel://calendar/"

    fun uri(tripId: String, key: String): String = "$PREFIX$tripId/$key"

    /** The prefix every marker of [tripId] starts with (for a LIKE query). */
    fun tripPrefix(tripId: String): String = "$PREFIX$tripId/"

    /** (tripId, key) from a marker, or null when [uri] isn't one. */
    fun parse(uri: String?): Pair<String, String>? {
        if (uri == null || !uri.startsWith(PREFIX)) return null
        val rest = uri.substring(PREFIX.length)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.length - 1) return null
        return rest.substring(0, slash) to rest.substring(slash + 1)
    }

    /** A visible source link survives providers/account sync that discard the optional custom-app columns. */
    fun description(text: String, ourPackage: String, tripId: String, key: String): String =
        cleanDescription(text, ourPackage) + "\n\n$SOURCE$ourPackage/$tripId/$key"

    fun descriptionPrefix(ourPackage: String, tripId: String? = null): String =
        "$SOURCE$ourPackage/" + (tripId?.let { "$it/" } ?: "")

    fun fromDescription(text: String, ourPackage: String): Pair<String, String>? {
        val prefix = descriptionPrefix(ourPackage)
        val owners = text.lineSequence().filter { it.startsWith(prefix) }.mapNotNull { line ->
            parse(PREFIX + line.removePrefix(prefix).trimEnd())
        }.distinct().toList()
        return owners.singleOrNull()
    }

    fun cleanDescription(text: String, ourPackage: String): String {
        if (fromDescription(text, ourPackage) == null) return text
        val prefix = descriptionPrefix(ourPackage)
        return text.lineSequence().filterNot { it.startsWith(prefix) }.joinToString("\n").trimEnd()
    }
}

/** An event read from the phone's calendar provider, as far as the diff needs it. */
data class PhoneEvent(
    val id: Long,
    val calendarId: Long,
    val fields: EventFields,
    val appPackage: String? = null,
    val appUri: String? = null,
) {
    val unmarked: Boolean get() = appPackage.isNullOrEmpty() && appUri.isNullOrEmpty()
}

/** What the app remembers about an event it wrote, in case a calendar's sync drops the marker. */
@Serializable
data class Remembered(val id: Long, val title: String, val signature: String? = null)

/** What an update does: events to insert, events to rewrite (id → wanted), events to delete. */
data class CalendarPlan(
    val inserts: List<TripEvent>,
    val updates: List<Pair<Long, TripEvent>>,
    val deletes: List<Long>,
    /** Key → id of every event that stays (updated or untouched); inserted ones get their ids when written. */
    val kept: Map<String, Long>,
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && deletes.isEmpty()
}

/**
 * Event ↔ item mapping. Pure: the phone's events come in as [PhoneEvent]s, the trip's as [TripEvent]s.
 *
 * An event belongs to the trip when it carries the trip's marker (any key), or — only in the linked calendar,
 * without any marker and still matching every field we wrote — when its id is one we remember with a signature.
 * An identical unmarked event is not proof of ownership and is never adopted or removed.
 * Nothing else is ever updated or deleted.
 */
object CalendarDiff {

    fun plan(
        tripId: String,
        ourPackage: String,
        calendarId: Long,
        desired: List<Pair<TripEvent, EventFields>>,
        existing: List<PhoneEvent>,
        remembered: Map<String, Remembered>,
    ): CalendarPlan {
        val events = existing.distinctBy { it.id }.sortedBy { it.id }
        val owned = owned(tripId, ourPackage, calendarId, events, remembered)

        val inserts = ArrayList<TripEvent>()
        val updates = ArrayList<Pair<Long, TripEvent>>()
        val deletes = LinkedHashSet<Long>()
        val kept = LinkedHashMap<String, Long>()
        val wanted = HashSet<String>()

        for ((event, fields) in desired) {
            if (!wanted.add(event.key)) continue
            val all = owned[event.key].orEmpty()
            // An event of ours that someone moved to another calendar goes; the linked calendar gets the trip.
            all.filter { it.calendarId != calendarId }.forEach { deletes += it.id }
            val mine = all.filter { it.calendarId == calendarId }
            val keep = mine.firstOrNull { it.fields.sameAs(fields) && isMarked(it, tripId, ourPackage) } ?: mine.firstOrNull()
            if (keep != null) {
                mine.filter { it.id != keep.id }.forEach { deletes += it.id }
                if (!keep.fields.sameAs(fields) || !isMarked(keep, tripId, ourPackage)) updates += keep.id to event
                kept[event.key] = keep.id
                continue
            }
            inserts += event
        }
        for ((key, list) in owned) if (key !in wanted) list.forEach { deletes += it.id }
        deletes.removeAll(kept.values.toSet())
        return CalendarPlan(inserts, updates, deletes.toList(), kept)
    }

    /** Only events with verified ownership, never another person's identical unmarked appointment. */
    fun removal(
        tripId: String,
        ourPackage: String,
        calendarId: Long,
        desired: List<Pair<TripEvent, EventFields>>,
        existing: List<PhoneEvent>,
        remembered: Map<String, Remembered>,
    ): List<Long> {
        val events = existing.distinctBy { it.id }.sortedBy { it.id }
        val out = LinkedHashSet<Long>()
        owned(tripId, ourPackage, calendarId, events, remembered).values.flatten().forEach { out += it.id }
        return out.toList()
    }

    /** The trip's events by key: marked ones anywhere, remembered unmarked ones in the linked calendar. */
    fun owned(
        tripId: String,
        ourPackage: String,
        calendarId: Long,
        events: List<PhoneEvent>,
        remembered: Map<String, Remembered>,
    ): Map<String, List<PhoneEvent>> {
        val out = LinkedHashMap<String, MutableList<PhoneEvent>>()
        val used = HashSet<Long>()
        for (e in events) {
            if (e.appPackage != ourPackage) continue
            val (trip, key) = CalendarMarker.parse(e.appUri) ?: continue
            if (trip != tripId) continue
            out.getOrPut(key) { ArrayList() } += e
            used += e.id
        }
        val byId = events.associateBy { it.id }
        for ((key, r) in remembered) {
            val e = byId[r.id] ?: continue
            if (e.id in used || e.calendarId != calendarId || !e.unmarked || r.signature == null || e.fields.signature() != r.signature) continue
            out.getOrPut(key) { ArrayList() } += e
            used += e.id
        }
        return out
    }

    private fun isMarked(e: PhoneEvent, tripId: String, ourPackage: String): Boolean =
        e.appPackage == ourPackage && CalendarMarker.parse(e.appUri)?.first == tripId
}

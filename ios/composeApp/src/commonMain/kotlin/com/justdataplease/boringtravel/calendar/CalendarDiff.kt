package com.justdataplease.boringtravel.calendar

import kotlinx.serialization.Serializable

/*
 * Which events in a phone's calendar are a trip's, and what an update does to them: the Android app's
 * calendar/CalendarDiff.kt, with the phone's own ids (EventKit's identifiers are text). On iPhone the marker is the
 * event's URL (Android: its CUSTOM_APP_URI) and the same "BoringTravel link:" line at the end of its notes.
 */

/**
 * The app's ids inside a phone calendar event: `boringtravel://trip/<tripId>/<key>` as the event's link, and a visible
 * "BoringTravel link:" line in its notes, which survives a calendar that drops links. An update finds its own events
 * by them among everything else in the calendar and never touches anyone else's.
 */
object CalendarMarker {
    private const val PREFIX = "boringtravel://trip/"
    private const val SOURCE = "BoringTravel link: boringtravel://calendar/"

    fun uri(tripId: String, key: String): String = "$PREFIX$tripId/$key"

    /** The prefix every marker of [tripId] starts with. */
    fun tripPrefix(tripId: String): String = "$PREFIX$tripId/"

    /** (tripId, key) from a marker, or null when [uri] isn't one. */
    fun parse(uri: String?): Pair<String, String>? {
        if (uri == null || !uri.startsWith(PREFIX)) return null
        val rest = uri.substring(PREFIX.length)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.length - 1) return null
        return rest.substring(0, slash) to rest.substring(slash + 1)
    }

    /** The notes with the visible source line at the end. */
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

    /**
     * Whose an event read from the phone is ([PhoneEvent.appPackage], [PhoneEvent.appUri]): its link when that is a
     * marker, else the source line in its notes when the link was dropped (as the Android app's PhoneCalendar.events).
     */
    fun owner(url: String?, notes: String, ourPackage: String): Pair<String?, String?> {
        if (parse(url) != null) return ourPackage to url
        val source = fromDescription(notes, ourPackage) ?: return null to url
        return if (url.isNullOrEmpty()) ourPackage to uri(source.first, source.second) else null to url
    }
}

/** An event read from the phone's calendar, as far as the diff needs it. */
data class PhoneEvent(
    val id: String,
    val calendarId: String,
    val fields: EventFields,
    val appPackage: String? = null,
    val appUri: String? = null,
) {
    val unmarked: Boolean get() = appPackage.isNullOrEmpty() && appUri.isNullOrEmpty()
}

/** What the app remembers about an event it wrote, in case a calendar's sync drops the marker. */
@Serializable
data class Remembered(val id: String, val title: String, val signature: String? = null)

/** What an update does: events to insert, events to rewrite (id → wanted), events to delete. */
data class CalendarPlan(
    val inserts: List<TripEvent>,
    val updates: List<Pair<String, TripEvent>>,
    val deletes: List<String>,
    /** Key → id of every event that stays (updated or untouched); inserted ones get their ids when written. */
    val kept: Map<String, String>,
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && deletes.isEmpty()
}

/**
 * Event ↔ item mapping. Pure: the phone's events come in as [PhoneEvent]s, the trip's as [TripEvent]s.
 *
 * An event belongs to the trip when it carries the trip's marker (any key), or (only in the linked calendar, without
 * any marker and still matching every field the app wrote) when its id is one remembered with a signature. An
 * identical unmarked event is not proof of ownership and is never adopted or removed. Nothing else is ever updated or
 * deleted.
 */
object CalendarDiff {
    fun plan(
        tripId: String,
        ourPackage: String,
        calendarId: String,
        desired: List<Pair<TripEvent, EventFields>>,
        existing: List<PhoneEvent>,
        remembered: Map<String, Remembered>,
    ): CalendarPlan {
        val events = existing.distinctBy { it.id }.sortedBy { it.id }
        val owned = owned(tripId, ourPackage, calendarId, events, remembered)

        val inserts = ArrayList<TripEvent>()
        val updates = ArrayList<Pair<String, TripEvent>>()
        val deletes = LinkedHashSet<String>()
        val kept = LinkedHashMap<String, String>()
        val wanted = HashSet<String>()

        for ((event, fields) in desired) {
            if (!wanted.add(event.key)) continue
            val all = owned[event.key].orEmpty()
            // An event of the app's that someone moved to another calendar goes; the linked calendar gets the trip.
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
        calendarId: String,
        existing: List<PhoneEvent>,
        remembered: Map<String, Remembered>,
    ): List<String> {
        val events = existing.distinctBy { it.id }.sortedBy { it.id }
        val out = LinkedHashSet<String>()
        owned(tripId, ourPackage, calendarId, events, remembered).values.flatten().forEach { out += it.id }
        return out.toList()
    }

    /** The trip's events by key: marked ones anywhere, remembered unmarked ones in the linked calendar. */
    fun owned(
        tripId: String,
        ourPackage: String,
        calendarId: String,
        events: List<PhoneEvent>,
        remembered: Map<String, Remembered>,
    ): Map<String, List<PhoneEvent>> {
        val out = LinkedHashMap<String, MutableList<PhoneEvent>>()
        val used = HashSet<String>()
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

/** One calendar on the phone (EventKit's EKCalendar; Android's CalendarContract.Calendars row). */
data class CalendarInfo(
    val id: String,
    val name: String,
    val accountName: String,
    val accountType: String,
    val ownerAccount: String = "",
    /** ARGB. */
    val color: Int = 0,
    val primary: Boolean = false,
    /** Android's access levels: 500 (contributor) and up may add events; an iPhone calendar that takes changes is 700. */
    val accessLevel: Int = 0,
    val visible: Boolean = true,
) {
    val writable: Boolean get() = accessLevel >= CONTRIBUTOR

    companion object {
        const val CONTRIBUTOR = 500
        const val OWNER = 700
    }
}

/** Who keeps a calendar in sync, from its account type (and name). */
enum class AccountKind(val label: String) {
    Google("Google"),
    ICloud("iCloud"),
    Outlook("Outlook"),
    Exchange("Exchange"),
    Samsung("Samsung"),
    CalDav("CalDAV"),
    Phone("On this phone"),
    Other("Other"),
}

/** Calendars under one account: "Google · ana@gmail.com". */
data class CalendarGroup(val kind: AccountKind, val accountName: String, val calendars: List<CalendarInfo>) {
    val title: String
        get() = if (accountName.isBlank() || kind == AccountKind.Phone || accountName.equals(kind.label, ignoreCase = true)) kind.label
        else "${kind.label} · $accountName"
}

/** Pure helpers for the calendar picker (the Android app's calendar/CalendarAccounts.kt). */
object CalendarAccounts {
    fun kind(accountType: String, accountName: String): AccountKind {
        val type = accountType.lowercase()
        val name = accountName.lowercase()
        return when {
            name.contains("icloud") || name.endsWith("@me.com") || name.endsWith("@mac.com") -> AccountKind.ICloud
            type == "com.google" -> AccountKind.Google
            type.startsWith("com.microsoft") || type.contains("outlook") -> AccountKind.Outlook
            type.contains("exchange") -> AccountKind.Exchange
            type == "com.osp.app.signin" || type.startsWith("com.samsung") -> AccountKind.Samsung
            type.contains("davdroid") || type.contains("davx") || type.contains("caldav") || type.contains("smoothsync") ||
                type.contains("dmfs") -> AccountKind.CalDav
            type == "local" || type.isBlank() -> AccountKind.Phone
            else -> AccountKind.Other
        }
    }

    /**
     * Calendars that only mirror other data (holidays, contacts' birthdays, week numbers): never a place to add a
     * trip, and noise when importing.
     */
    fun isNoise(c: CalendarInfo): Boolean {
        val owner = c.ownerAccount.lowercase()
        val type = c.accountType.lowercase()
        return owner.endsWith("#holiday@group.v.calendar.google.com") ||
            owner.endsWith("#contacts@group.v.calendar.google.com") ||
            owner.endsWith("#weeknum@group.v.calendar.google.com") ||
            type.contains("birthday") || type.contains("holiday")
    }

    /** Writable, visible calendars to add a trip to, grouped by account: Google first, then iCloud, Outlook… */
    fun groups(calendars: List<CalendarInfo>): List<CalendarGroup> =
        calendars
            .filter { it.writable && it.visible && !isNoise(it) }
            .groupBy { kind(it.accountType, it.accountName) to it.accountName }
            .map { (k, list) -> CalendarGroup(k.first, k.second, list.sortedWith(compareBy({ !it.primary }, { it.name.lowercase() }))) }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.accountName.lowercase() }))

    /** The calendar to preselect: the linked one, else the first account's main calendar. */
    fun defaultChoice(groups: List<CalendarGroup>, linkedId: String?, preferredAccountEmail: String? = null): CalendarInfo? {
        val all = groups.flatMap { it.calendars }
        val google = all.filter { it.accountType == "com.google" }
        val preferred = google.filter { preferredAccountEmail != null && it.accountName.equals(preferredAccountEmail, ignoreCase = true) }
        return all.firstOrNull { it.id == linkedId } ?: preferred.firstOrNull { it.primary } ?: preferred.firstOrNull()
            ?: google.firstOrNull { it.primary } ?: google.firstOrNull() ?: all.firstOrNull { it.primary } ?: all.firstOrNull()
    }
}

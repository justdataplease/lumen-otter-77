package com.justdataplease.boringtravel.calendar

/**
 * A phone's calendars in memory, for tests and previews: calendars and events kept as EventKit keeps them (an all-day
 * event at UTC midnights, a timed one in its zone, the app's marker as the event's link and the source line in its
 * notes), access that can be given, refused or never asked, and every call recorded in [calls].
 */
class MemoryPhoneCalendar(
    calendars: List<CalendarInfo> = listOf(
        CalendarInfo("cal-icloud-home", "Home", "iCloud", "caldav", color = 0xFF34AADC.toInt(), primary = true, accessLevel = CalendarInfo.OWNER),
        CalendarInfo("cal-icloud-work", "Work", "iCloud", "caldav", color = 0xFFFF9500.toInt(), accessLevel = CalendarInfo.OWNER),
        CalendarInfo("cal-google", "ana@gmail.com", "ana@gmail.com", "com.google", color = 0xFF0B8043.toInt(), primary = true, accessLevel = CalendarInfo.OWNER),
        CalendarInfo("cal-holidays", "Holidays in Greece", "Subscribed", "holiday", accessLevel = 200),
    ),
    /** "granted", "refused" or "notAsked". */
    var access: String = "granted",
    /** What the system's question answers when asked. */
    var answer: Boolean = true,
    override val ourPackage: String = "com.justdataplease.boringtravel",
) : PhoneCalendar {
    val calendarsHere = calendars.toMutableList()

    /** A stored event: its calendar, fields, link and the notes as written (with the source line). */
    data class Stored(val id: String, val calendarId: String, val fields: EventFields, val url: String?, val notes: String)

    val events = LinkedHashMap<String, Stored>()
    val calls = ArrayList<String>()
    private var next = 1

    /** A plain event of someone else's (an appointment, a flight from an airline's mail). */
    fun addOther(calendarId: String, title: String, begin: Long, end: Long, allDay: Boolean = false, location: String = "", notes: String = ""): String {
        val id = "ev-${next++}"
        events[id] = Stored(id, calendarId, EventFields(title, location, notes, allDay, begin, end, "UTC", false), null, notes)
        return id
    }

    override fun canRead(): Boolean = access == "granted"

    override fun canWrite(): Boolean = access == "granted"

    override fun refused(): Boolean = access == "refused"

    override suspend fun requestAccess(): Boolean {
        calls += "requestAccess"
        if (access == "notAsked") access = if (answer) "granted" else "refused"
        return access == "granted"
    }

    private fun need() {
        if (!canRead()) throw CalendarAccessException()
    }

    override fun calendars(): List<CalendarInfo> {
        need()
        return calendarsHere.toList()
    }

    private fun read(s: Stored): PhoneEvent {
        val (pkg, uri) = CalendarMarker.owner(s.url, s.notes, ourPackage)
        return PhoneEvent(s.id, s.calendarId, s.fields.copy(description = CalendarMarker.cleanDescription(s.notes, ourPackage)), pkg, uri)
    }

    override fun markedEvents(tripId: String, window: LongRange?): List<PhoneEvent> {
        need()
        calls += "markedEvents $tripId"
        return events.values.map(::read).filter { it.appPackage == ourPackage && CalendarMarker.parse(it.appUri)?.first == tripId }
    }

    override fun markedEventIds(window: LongRange): Set<String> {
        need()
        return events.values.map(::read).filter { it.appPackage == ourPackage && CalendarMarker.parse(it.appUri) != null }.mapTo(HashSet()) { it.id }
    }

    override fun eventsByIds(ids: Collection<String>): List<PhoneEvent> {
        need()
        return ids.distinct().mapNotNull { events[it] }.map(::read)
    }

    override fun apply(calendarId: String, tripId: String, zone: String, plan: CalendarPlan): Map<String, String> {
        if (!canWrite()) throw CalendarAccessException()
        calls += "apply $calendarId +${plan.inserts.size} ~${plan.updates.size} -${plan.deletes.size}"
        if (plan.deletes.isNotEmpty()) delete(plan.deletes)
        fun stored(id: String, cal: String, e: TripEvent): Stored {
            val f = TripEvents.fields(e, zone)
            return Stored(id, cal, f, CalendarMarker.uri(tripId, e.key), CalendarMarker.description(f.description, ourPackage, tripId, e.key))
        }
        plan.updates.forEach { (id, e) -> events[id]?.let { old -> events[id] = stored(id, old.calendarId, e) } }
        return plan.inserts.associate { e ->
            val id = "ev-${next++}"
            events[id] = stored(id, calendarId, e)
            e.key to id
        }
    }

    override fun delete(ids: Collection<String>): Int {
        if (!canWrite()) throw CalendarAccessException()
        calls += "delete ${ids.size}"
        return ids.distinct().count { events.remove(it) != null }
    }

    override fun instances(from: Long, to: Long): List<PhoneInstance> {
        need()
        return events.values.filter { it.fields.endMs > from && it.fields.startMs < to }.map {
            PhoneInstance(it.id, it.calendarId, it.fields.title, it.fields.location, it.notes, it.fields.allDay, it.fields.startMs, it.fields.endMs)
        }
    }

    override fun openSettings() {
        calls += "openSettings"
    }

    /** What [newEvent] opened last: the title, place, notes and time. */
    var opened: List<Any?>? = null

    override fun newEvent(title: String, location: String, notes: String, url: String?, time: ItemWhen, zone: String): Boolean {
        calls += "newEvent $title"
        opened = listOf(title, location, notes, url, time, zone)
        return true
    }
}

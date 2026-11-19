package com.justdataplease.boringtravel.calendar

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.ios.KindWords
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.time.LocalDate

/*
 * Calendar events into the plan: the Android app's calendar/CalendarImport.kt, written again with days compared by
 * their epoch day, zones by their ids and the phone's event ids as text.
 */

/** One occurrence of an event in the phone's calendars (EventKit's EKEvent in a date range). */
data class PhoneInstance(
    val eventId: String,
    val calendarId: String,
    val title: String,
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
    /** Epoch ms; all-day occurrences start and end at UTC midnights. */
    val begin: Long,
    val end: Long,
)

/** A calendar event offered for the plan: the day and time it lands on, a kind, and whether the plan has it already. */
data class ImportCandidate(
    val instance: PhoneInstance,
    val calendar: CalendarInfo?,
    val day: LocalDate,
    val timeMinutes: Int?,
    val kind: PlanKind,
    /** The kind came from an obvious word (flight, hotel, tour…); such events start ticked. */
    val guessed: Boolean,
    val externalId: String,
    val inPlan: Boolean,
    /** Occurs once in the trip's dates (not a repeating event), so its id alone identifies it. */
    val oneOff: Boolean = true,
    /** When it ends: days after [day] and the time; none for an event already under way. */
    val endDays: Int? = null,
    val endMinutes: Int? = null,
) {
    val title: String get() = CalendarImport.title(instance.title)
}

/** Calendar events → plan items. Pure. */
object CalendarImport {
    private const val PREFIX = "cal:"
    private const val NOTE_MAX = 500

    /** "cal:<eventId>/<day>": the event and the day it was taken for (repeating events have one per day). */
    fun externalId(eventId: String, day: LocalDate): String = "$PREFIX$eventId/$day"

    fun title(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(200).ifBlank { "Event" }

    /**
     * The events of [instances] that touch the trip's days [first]..[last] (times read in [zone]), skipping
     * [skipEventIds] (events this app wrote). An event that started before the trip lands on its first day without a
     * time. Marked [ImportCandidate.inPlan] (and never added twice) when a live item came from the same event (same id
     * and day; any day for a one-off event, in case it moved), or has the same title, day and time. The same event
     * listed twice (an invitation in two calendars) shows once.
     */
    fun candidates(
        instances: List<PhoneInstance>,
        calendars: Map<String, CalendarInfo>,
        first: LocalDate,
        last: LocalDate,
        zone: String,
        items: List<PlanItem>,
        skipEventIds: Set<String> = emptySet(),
    ): List<ImportCandidate> {
        val live = items.filter { it.deletedAt == null }
        val liveContent = contentKeys(live)
        val repeats = instances.groupingBy { it.eventId }.eachCount()
        val listed = HashSet<String>()
        val out = ArrayList<ImportCandidate>()
        for (i in instances.sortedWith(compareBy({ it.begin }, { it.eventId }))) {
            if (i.eventId in skipEventIds) continue
            val (from, to) = days(i, zone)
            if (to.toEpochDay() < first.toEpochDay() || from.toEpochDay() > last.toEpochDay()) continue
            val started = from.toEpochDay() >= first.toEpochDay()
            val day = if (started) from else first
            val time = if (!i.allDay && started) wallOf(i.begin, zone).second else null
            val title = title(i.title)
            if (!listed.add(contentKey(title, day, time))) continue
            val guess = guessKind(title, i.location)
            // An all-day stay covers its nights: it is left on the morning after its last day.
            val leave = if (i.allDay && guess == PlanKind.Stay) 1 else 0
            val endDays = if (started) (to.toEpochDay() - day.toEpochDay() + leave).toInt().takeIf { it in 1..PlanSpan.MAX_DAYS } else null
            val endMinutes = if (started && !i.allDay && i.end > i.begin) wallOf(i.end, zone).second else null
            val c = ImportCandidate(
                i, calendars[i.calendarId], day, time, guess ?: PlanKind.Place, guess != null,
                externalId(i.eventId, day), inPlan = false, oneOff = (repeats[i.eventId] ?: 0) <= 1,
                endDays = endDays, endMinutes = endMinutes,
            )
            out += c.copy(inPlan = alreadyIn(c, live, liveContent))
        }
        return out.sortedWith(compareBy({ it.day.toEpochDay() }, { it.timeMinutes ?: -1 }, { it.title.lowercase() }))
    }

    /** The picked candidates still worth adding, checked again against [items] right before writing, in day and time order. */
    fun fresh(picked: List<ImportCandidate>, items: List<PlanItem>): List<ImportCandidate> {
        val live = items.filter { it.deletedAt == null }
        val content = contentKeys(live)
        val ids = HashSet<String>()
        return picked
            .sortedWith(compareBy({ it.day.toEpochDay() }, { it.timeMinutes ?: -1 }, { it.title.lowercase() }))
            .filter { !alreadyIn(it, live, content) && ids.add(it.externalId) && content.add(contentKey(it.title, it.day, it.timeMinutes)) }
    }

    private fun alreadyIn(c: ImportCandidate, live: List<PlanItem>, liveContent: Set<String>): Boolean {
        if (contentKey(c.title, c.day, c.timeMinutes) in liveContent) return true
        val sameEvent = "$PREFIX${c.instance.eventId}/"
        return live.any {
            val id = it.externalId ?: return@any false
            id == c.externalId || (c.oneOff && id.startsWith(sameEvent))
        }
    }

    private fun contentKeys(items: List<PlanItem>): HashSet<String> = items.mapTo(HashSet()) { contentKey(it.title, it.day, it.timeMinutes) }

    /** First and last day of an occurrence: UTC dates for all-day ones (end exclusive), [zone] dates for timed ones. */
    fun days(i: PhoneInstance, zone: String): Pair<LocalDate, LocalDate> = if (i.allDay) {
        val from = TripEvents.utcDay(i.begin)
        val endExcl = TripEvents.utcDay(i.end)
        from to (if (endExcl.toEpochDay() > from.toEpochDay()) endExcl.minusDays(1) else from)
    } else {
        wallOf(i.begin, zone).first to wallOf(maxOf(i.begin, i.end - 1), zone).first
    }

    private fun contentKey(title: String, day: LocalDate?, time: Int?): String =
        "${title.replace(Regex("\\s+"), " ").trim().lowercase()}|$day|$time"

    /** The plan item for [c], at [position] on its day. */
    fun toItem(c: ImportCandidate, tripId: String, id: String, position: Double, now: Long): PlanItem = PlanSpan.normalized(PlanItem(
        id = id,
        tripId = tripId,
        day = c.day,
        position = position,
        timeMinutes = c.timeMinutes,
        kind = c.kind.id,
        title = c.title,
        note = note(c.instance.description),
        placeName = c.instance.location.replace(Regex("\\s+"), " ").trim().take(300),
        source = ItemSource.Calendar.id,
        externalId = c.externalId,
        createdAt = now,
        updatedAt = now,
        endDays = c.endDays,
        endMinutes = c.endMinutes,
    ))

    /** An event's description as a plain note: HTML flattened, blank runs squeezed, capped. */
    fun note(description: String): String {
        val plain = description
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .replace("\r\n", "\n").replace('\r', '\n')
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return if (plain.length <= NOTE_MAX) plain else plain.take(NOTE_MAX - 1).trimEnd() + "…"
    }

    /** A kind from obvious words in [title], else in [location] (in English and Greek); null when nothing is obvious. */
    fun guessKind(title: String, location: String = ""): PlanKind? = KindWords.guessKind(title, location)
}

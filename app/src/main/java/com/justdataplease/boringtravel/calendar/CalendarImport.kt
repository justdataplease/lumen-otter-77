package com.justdataplease.boringtravel.calendar

import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** One occurrence of an event in the phone's calendars (a row of CalendarContract.Instances). */
data class PhoneInstance(
    val eventId: Long,
    val calendarId: Long,
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
    /** When it ends: days after [day] and the time ([PlanItem.endDays], [PlanItem.endMinutes]); none for an event already under way. */
    val endDays: Int? = null,
    val endMinutes: Int? = null,
) {
    val title: String get() = CalendarImport.title(instance.title)
}

/** Calendar events → plan items. Pure. */
object CalendarImport {
    private const val PREFIX = "cal:"
    private const val NOTE_MAX = 500

    /** "cal:<eventId>/<day>": the event and the day it was taken for (recurring events have one per day). */
    fun externalId(eventId: Long, day: LocalDate): String = "$PREFIX$eventId/$day"

    fun title(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(200).ifBlank { "Event" }

    /**
     * The events of [instances] that touch the trip's days [first]..[last] (times read in [zone]), skipping
     * [skipEventIds] (events this app wrote). An event that started before the trip lands on its first day without a
     * time. Marked [ImportCandidate.inPlan] — and never added twice — when a live item came from the same event (same
     * id and day; any day for a one-off event, in case it moved), or has the same title, day and time (an item typed
     * by hand, or imported on another phone). The same event listed twice (an invitation in two calendars) shows once.
     */
    fun candidates(
        instances: List<PhoneInstance>,
        calendars: Map<Long, CalendarInfo>,
        first: LocalDate,
        last: LocalDate,
        zone: ZoneId,
        items: List<PlanItem>,
        skipEventIds: Set<Long> = emptySet(),
    ): List<ImportCandidate> {
        val live = items.filter { it.deletedAt == null }
        val liveContent = contentKeys(live)
        val repeats = instances.groupingBy { it.eventId }.eachCount()
        val listed = HashSet<String>()
        val out = ArrayList<ImportCandidate>()
        for (i in instances.sortedWith(compareBy({ it.begin }, { it.eventId }))) {
            if (i.eventId in skipEventIds) continue
            val (from, to) = days(i, zone)
            if (to.isBefore(first) || from.isAfter(last)) continue
            val day = if (from.isBefore(first)) first else from
            val time = if (!i.allDay && !from.isBefore(first)) minutesOfDay(i.begin, zone) else null
            val title = title(i.title)
            if (!listed.add(contentKey(title, day, time))) continue
            val guess = guessKind(title, i.location)
            // The event's end, as the plan keeps it: a concert 19:00-22:00, a hotel over its nights.
            val started = !from.isBefore(first)
            // An all-day stay covers its nights: it is left on the morning after its last day.
            val leave = if (i.allDay && guess == PlanKind.Stay) 1 else 0
            val endDays = if (started) (to.toEpochDay() - day.toEpochDay() + leave).toInt().takeIf { it in 1..PlanSpan.MAX_DAYS } else null
            val endMinutes = if (started && !i.allDay && i.end > i.begin) minutesOfDay(i.end, zone) else null
            val c = ImportCandidate(
                i, calendars[i.calendarId], day, time, guess ?: PlanKind.Place, guess != null,
                externalId(i.eventId, day), inPlan = false, oneOff = (repeats[i.eventId] ?: 0) <= 1,
                endDays = endDays, endMinutes = endMinutes,
            )
            out += c.copy(inPlan = alreadyIn(c, live, liveContent))
        }
        return out.sortedWith(compareBy({ it.day }, { it.timeMinutes ?: -1 }, { it.title.lowercase() }))
    }

    /**
     * The picked candidates still worth adding, checked again against [items] right before writing (a second tap, or
     * the same event picked twice), in day and time order.
     */
    fun fresh(picked: List<ImportCandidate>, items: List<PlanItem>): List<ImportCandidate> {
        val live = items.filter { it.deletedAt == null }
        val content = contentKeys(live)
        val ids = HashSet<String>()
        return picked
            .sortedWith(compareBy({ it.day }, { it.timeMinutes ?: -1 }, { it.title.lowercase() }))
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
    fun days(i: PhoneInstance, zone: ZoneId): Pair<LocalDate, LocalDate> = if (i.allDay) {
        val from = TripEvents.utcDay(i.begin)
        val endExcl = TripEvents.utcDay(i.end)
        from to (if (endExcl.isAfter(from)) endExcl.minusDays(1) else from)
    } else {
        val from = Instant.ofEpochMilli(i.begin).atZone(zone).toLocalDate()
        from to Instant.ofEpochMilli(maxOf(i.begin, i.end - 1)).atZone(zone).toLocalDate()
    }

    private fun minutesOfDay(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    private fun contentKey(title: String, day: LocalDate?, time: Int?): String =
        "${title.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)}|$day|$time"

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

    /** An event's description as a plain note: HTML (Google Calendar keeps some) flattened, blank runs squeezed, capped. */
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

    // Kind guessing: only obvious words, in English and Greek (accents and case don't matter). Transport is checked
    // first so "check-in for your flight" is a flight; the title is read before the place.
    private val rules: List<Pair<PlanKind, Regex>> = listOf(
        PlanKind.Transport to listOf(
            "flights?", "airport", "boarding", "layover", "trains?", "rail", "railway", "ferry", "ferries", "bus", "shuttle",
            "transfer", "taxi", "car rental", "rental car", "car hire",
            "πτηση", "πτησεις", "αεροδρομιο", "αεροπλανο", "πλοιο", "φεριμποτ", "τρενο", "λεωφορειο", "ταξι", "μεταφορα",
        ),
        PlanKind.Stay to listOf(
            "hotel", "hostel", "motel", "inn", "airbnb", "apartments?", "guesthouse", "guest house", "b&b", "bed and breakfast",
            "villa", "resort", "lodge", "camping", "campsite", "check[- ]?in", "check[- ]?out", "stay", "accommodation",
            "ξενοδοχειο", "διαμονη", "καταλυμα", "δωματια", "βιλα", "ξενωνας",
        ),
        PlanKind.Food to listOf(
            "restaurant", "dinner", "lunch", "breakfast", "brunch", "cafe", "coffee", "bar", "taverna", "tavern", "bistro",
            "pizzeria", "trattoria", "food", "meal",
            // Names of places in shared Google Maps lists (owner's "Seville" list, 17 Nov 2026: "Mesón", "Casa de Comidas").
            "bodega", "taberna", "tasca", "meson", "casa de comidas", "cerveceria", "marisqueria", "tapas", "churreria",
            "heladeria", "gelateria", "pasteleria", "bakery", "ristorante", "osteria", "brasserie", "cafeteria",
            "εστιατοριο", "ταβερνα", "δειπνο", "γευμα", "μεσημεριανο", "πρωινο", "καφε", "μεζεδοπωλειο", "ουζερι",
        ),
        PlanKind.Activity to listOf(
            "tours?", "museum", "gallery", "excursion", "tickets?", "concert", "show", "hike", "hiking", "trek", "trekking",
            "class", "lesson", "workshop", "tasting", "cruise", "boat trip", "snorkell?ing", "diving", "kayak(ing)?", "surf(ing)?",
            "spa", "massage", "festival", "theatre", "theater", "cinema", "opera", "zoo", "aquarium", "guided", "sightseeing",
            "ξεναγηση", "εκδρομη", "μουσειο", "εισιτηρια", "εισιτηριο", "συναυλια", "παρασταση", "πεζοπορια", "κρουαζιερα",
        ),
    ).map { (kind, words) ->
        kind to Regex("(?<![\\p{L}\\p{N}])(" + words.joinToString("|") { fold(it) } + ")(?![\\p{L}\\p{N}])")
    }

    /** A kind from obvious words in [title], else in [location]; null when nothing is obvious. */
    fun guessKind(title: String, location: String = ""): PlanKind? {
        for (text in listOf(title, location)) {
            if (text.isBlank()) continue
            if (text.contains('✈')) return PlanKind.Transport
            val t = fold(text)
            rules.firstOrNull { it.second.containsMatchIn(t) }?.let { return it.first }
        }
        return null
    }

    /** Lower case without accents, final sigma as sigma: "Πτήσεις" → "πτησεισ", "Café" → "cafe". */
    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace('ς', 'σ')
}

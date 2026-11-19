package com.justdataplease.boringtravel.calendar

import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** When an event happens: whole days, or a start time local to the trip and a length. */
sealed interface EventTime {
    /** [first] up to [endExclusive] (the day after the last), the way RFC 5545 and Android store all-day events. */
    data class Days(val first: LocalDate, val endExclusive: LocalDate) : EventTime

    /** Starts at [start] (wall time in the trip's zone) and lasts [minutes]. */
    data class At(val start: LocalDateTime, val minutes: Int) : EventTime
}

/**
 * One event a trip puts in a calendar: the trip itself (all-day, over its dates) or one plan item on a day.
 * [key] names it among the trip's events ("trip" or "item/<id>") and is written into the phone's event, so an update
 * finds it again; [uid] is the RFC 5545 UID of the .ics copy. Both stay the same for as long as the trip or item lives.
 */
data class TripEvent(
    val key: String,
    val uid: String,
    val title: String,
    val time: EventTime,
    val location: String = "",
    val description: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    /** Shown as free (the trip's own all-day band, all-day items), so a week away doesn't block the calendar. */
    val free: Boolean = time is EventTime.Days,
    val updatedAt: Long = 0,
)

/**
 * An event as Android's calendar provider stores it (epoch ms, UTC midnights for all-day events). Also what an update
 * compares: an event whose fields are the same is left alone.
 */
data class EventFields(
    val title: String,
    val location: String,
    val description: String,
    val allDay: Boolean,
    val startMs: Long,
    val endMs: Long,
    val timeZone: String,
    val free: Boolean,
) {
    /** Same event for the calendar (the zone only matters for timed events; providers spell UTC differently). */
    fun sameAs(o: EventFields): Boolean =
        title == o.title && location == o.location && description == o.description && allDay == o.allDay &&
            startMs == o.startMs && endMs == o.endMs && free == o.free && (allDay || timeZone == o.timeZone)

    /** Persisted proof for an event ID if a provider strips both of our source markers. */
    fun signature(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(title, location, description, allDay.toString(), startMs.toString(), endMs.toString(), if (allDay) "UTC" else timeZone, free.toString())
            .forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
                digest.update(bytes)
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** A trip and its plan as calendar events. Pure; the phone's calendar and the .ics writer both start here. */
object TripEvents {
    const val TRIP_KEY = "trip"
    const val UID_DOMAIN = "boringtravel.justdataplease.com"

    /** Plan items have a start time but no end; in a calendar they last an hour. */
    const val DEFAULT_MINUTES = 60

    private const val DAY_MS = 86_400_000L

    fun itemKey(itemId: String) = "item/$itemId"

    /** An item's event text: its note, then its website and its Google Maps link (calendar apps make links tappable). */
    fun itemDescription(item: PlanItem): String =
        listOf(item.note.trim(), item.url?.trim().orEmpty(), item.mapsUrl?.trim().orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")

    /**
     * The trip as an all-day event over its dates (when it has them), then every plan item that sits on a day: timed
     * items at their time for [DEFAULT_MINUTES], the others as all-day events. Ideas (no day) and deleted items stay out.
     * [forPhone] also leaves out items imported from the phone's calendars: the original event is there already, and
     * writing a copy next to it (or adopting the original as ours) would be wrong. The .ics file keeps them.
     */
    fun build(trip: Trip, items: List<PlanItem>, forPhone: Boolean = false): List<TripEvent> {
        if (trip.deletedAt != null) return emptyList()
        val out = ArrayList<TripEvent>()
        val start = trip.startDate
        if (start != null) {
            val end = trip.endDate?.takeIf { !it.isBefore(start) } ?: start
            out += TripEvent(
                key = TRIP_KEY,
                uid = "trip-${trip.id}@$UID_DOMAIN",
                title = trip.title.trim().ifBlank { "Trip" },
                time = EventTime.Days(start, end.plusDays(1)),
                description = trip.subtitle.trim(),
                updatedAt = trip.updatedAt,
            )
        }
        items.asSequence()
            .filter { it.deletedAt == null && it.day != null && !(forPhone && it.source == ItemSource.Calendar.id) }
            .sortedWith(compareBy<PlanItem>({ it.day }, { it.timeMinutes ?: -1 }, { it.position }))
            .forEach { item ->
                out += TripEvent(
                    key = itemKey(item.id),
                    uid = "${item.id}@$UID_DOMAIN",
                    title = item.title.trim()
                        .ifBlank { item.placeName.substringBefore(',').trim() }
                        .ifBlank { PlanKind.of(item.kind).label },
                    time = itemTime(item)!!,
                    location = item.placeName.trim(),
                    description = itemDescription(item),
                    lat = item.lat,
                    lng = item.lng,
                    updatedAt = item.updatedAt,
                )
            }
        return out
    }

    /**
     * When a plan item is in a calendar (null for an idea): at its time for as long as it lasts (its own end, else
     * [DEFAULT_MINUTES]); otherwise all day over its days, a stay up to its check-out morning (a hotel Thu to Sun
     * covers Thu, Fri and Sat, as calendars show stays; [CalendarImport] reads it back the same way).
     */
    fun itemTime(item: PlanItem): EventTime? {
        val day = item.day ?: return null
        val minutes = item.timeMinutes?.coerceIn(0, 24 * 60 - 1)
        val days = PlanSpan.days(item)
        val length = PlanSpan.minutes(item)
        if (minutes != null && (days == 0 || length != null)) return EventTime.At(day.atTime(minutes / 60, minutes % 60), length ?: DEFAULT_MINUTES)
        val nights = days > 0 && item.kind == PlanKind.Stay.id
        return EventTime.Days(day, day.plusDays(days + if (nights) 0L else 1L))
    }

    /** How Android's calendar provider stores [event]: all-day at UTC midnights in "UTC", timed in [zone]. */
    fun fields(event: TripEvent, zone: ZoneId): EventFields = when (val t = event.time) {
        is EventTime.Days -> EventFields(
            event.title, event.location, event.description, allDay = true,
            startMs = t.first.toEpochDay() * DAY_MS, endMs = t.endExclusive.toEpochDay() * DAY_MS,
            timeZone = "UTC", free = event.free,
        )
        is EventTime.At -> {
            val start = t.start.atZone(zone).toInstant()
            EventFields(
                event.title, event.location, event.description, allDay = false,
                startMs = start.toEpochMilli(), endMs = start.plusSeconds(t.minutes * 60L).toEpochMilli(),
                timeZone = zone.id, free = event.free,
            )
        }
    }

    /** The UTC date an all-day timestamp stands for. */
    fun utcDay(ms: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(ms, DAY_MS))

    /** Instant bounds of the events (for queries around them), or null when there are none. */
    fun window(events: List<TripEvent>, zone: ZoneId): LongRange? {
        if (events.isEmpty()) return null
        val f = events.map { fields(it, zone) }
        return f.minOf { it.startMs } - DAY_MS..f.maxOf { it.endMs } + DAY_MS
    }

    /**
     * A short digest of what the calendar should hold. The Plan tab compares it with the one saved at the last
     * update to offer "Update calendar"; moving an item within a day or ticking it off changes nothing here.
     */
    fun fingerprint(events: List<TripEvent>, zone: ZoneId): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (e in events.sortedBy { it.key }) {
            val f = fields(e, zone)
            val line = listOf(e.key, f.title, f.location, f.description, f.allDay, f.startMs, f.endMs, f.timeZone, f.free).joinToString("\u001F")
            md.update(line.toByteArray(Charsets.UTF_8))
            md.update(0x1E.toByte())
        }
        return md.digest().take(10).joinToString("") { "%02x".format(it) }
    }
}

package com.justdataplease.boringtravel.calendar

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.ios.JavaText
import com.justdataplease.boringtravel.ios.zoneDst
import com.justdataplease.boringtravel.ios.zoneKnown
import com.justdataplease.boringtravel.ios.zoneNextTransition
import com.justdataplease.boringtravel.ios.zoneOffsetMs
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.time.CivilDay
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import kotlin.math.abs

/*
 * The trip's time zone, its .ics file and one item for the phone's own new-event screen: the Android app's
 * calendar/TripZone.kt, IcsWriter.kt and CalendarIntents.kt, written again with zones by their ids (Foundation's
 * NSTimeZone on iPhone, ios/Zones.kt) in place of java.time's.
 */

private const val DAY_MS = 86_400_000L

/** The zone a trip's times are read in, and the others worth offering (zone ids). */
data class ZoneChoice(val current: String, val options: List<String>)

/**
 * Plan times are wall-clock times at the destination ("ferry 07:30"), but a trip stores no zone. The calendar needs one,
 * so it is guessed and can be changed: the zone chosen before, else the zone most journal entries were written in,
 * else the one zone of the countries the planned places are in, else the phone's zone. Pure.
 */
object TripZone {
    /** [placeZones]: for each planned place, the zones of its country (empty when unknown). */
    fun guess(chosen: String?, entryZones: List<String>, placeZones: List<Set<String>>, phone: String): String {
        parse(chosen)?.let { return it }
        entryZones.mapNotNull(::parse).groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { return it.key }
        val single = placeZones.filter { it.size == 1 }.map { it.first() }
        single.mapNotNull(::parse).groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { return it.key }
        return phone
    }

    /** The guess first, then the phone's zone, the entries' and the places' countries' zones, east to west. */
    fun options(current: String, phone: String, entryZones: List<String>, placeZones: List<Set<String>>, on: LocalDate): List<String> {
        val rest = (listOf(phone) + entryZones.mapNotNull(::parse) + placeZones.flatten().mapNotNull(::parse))
            .distinct()
            .filter { it != current }
            .sortedWith(compareByDescending<String> { offsetSeconds(it, on) }.thenBy { city(it) })
        return (listOf(current) + rest).take(60)
    }

    /** The zone [id] names, or null for one this phone doesn't know. */
    fun parse(id: String?): String? = id?.takeIf { it.isNotBlank() && zoneKnown(it) }

    /** "New York" from America/New_York, "UTC" for UTC. */
    fun city(zone: String): String {
        if (zone == "Z" || zone.equals("UTC", true) || zone.equals("Etc/UTC", true) || zone.equals("GMT", true)) return "UTC"
        return zone.substringAfterLast('/').replace('_', ' ')
    }

    /** "GMT+2", "GMT-5", "GMT+5:30" on [on] (summer time included). */
    fun offsetLabel(zone: String, on: LocalDate): String {
        val total = offsetSeconds(zone, on)
        if (total == 0) return "GMT"
        val a = abs(total)
        val h = a / 3600
        val m = a / 60 % 60
        return "GMT" + (if (total < 0) "-" else "+") + h + (if (m != 0) ":" + m.toString().padStart(2, '0') else "")
    }

    /** "Athens · GMT+3". */
    fun label(zone: String, on: LocalDate): String {
        val city = city(zone)
        return if (city == "UTC") "UTC" else "$city · ${offsetLabel(zone, on)}"
    }

    fun offsetSeconds(zone: String, on: LocalDate): Int = (zoneOffsetMs(wallMs(on, 12 * 60, zone), zone) / 1000).toInt()
}

/**
 * A trip and its plan as an iCalendar file (RFC 5545) for Apple Calendar, Outlook, Google Calendar's import or anything
 * else. Pure but for the zone rules: the caller gives the clock ([stamp]) and the app version.
 *
 * - Lines end in CRLF and are folded at 75 octets without splitting a UTF-8 character.
 * - TEXT values escape `\`, `;`, `,` and newlines; other control characters are dropped.
 * - UIDs come from the app's ids, so importing a newer file updates the events instead of adding copies; SEQUENCE and
 *   LAST-MODIFIED follow the item's own edits.
 * - All-day events are `VALUE=DATE` with the day after the last as an exclusive end.
 * - Timed events carry `TZID=<the trip's zone>` and the file includes a VTIMEZONE with the zone's real offsets over the
 *   trip (each transition listed once). Zones that are a fixed offset (UTC, Etc/GMT+3, +05:30) are written as UTC
 *   times with `Z` instead, since a TZID can't name them portably.
 */
object IcsWriter {
    const val MIME = "text/calendar"
    private const val CRLF = "\r\n"
    private const val MAX_OCTETS = 75

    fun write(name: String, events: List<TripEvent>, zone: String, stamp: Long, appVersion: String): String {
        val lines = ArrayList<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//justdataplease//BoringTravel ${appVersion.filter { it.isLetterOrDigit() || it in ".-" }}//EN"
        lines += "CALSCALE:GREGORIAN"
        lines += "METHOD:PUBLISH"
        if (name.isNotBlank()) lines += "X-WR-CALNAME:" + text(name.trim())

        val useTzid = usesTzid(zone)
        val timed = events.mapNotNull { it.time as? EventTime.At }
        if (useTzid && timed.isNotEmpty()) {
            val from = timed.minOf { wallMs(it.day, it.startMinutes, zone) }
            val to = timed.maxOf { wallMs(it.day, it.startMinutes, zone) + it.minutes * 60_000L }
            lines += vtimezone(zone, from, to)
        }
        val dtstamp = utc(stamp)
        for (e in events) lines += vevent(e, zone, useTzid, dtstamp)
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + CRLF }
    }

    /** Region zones with real rules get a TZID; fixed offsets are written in UTC. */
    fun usesTzid(zone: String): Boolean {
        val id = zone.trim()
        if (id.isEmpty() || id == "Z" || id.startsWith("+") || id.startsWith("-") || id.startsWith("Etc/", ignoreCase = true)) return false
        val upper = id.uppercase()
        return !(upper == "UTC" || upper == "GMT" || upper == "UT" || upper.startsWith("UTC+") || upper.startsWith("UTC-") ||
            upper.startsWith("GMT+") || upper.startsWith("GMT-"))
    }

    private fun vevent(e: TripEvent, zone: String, useTzid: Boolean, dtstamp: String): List<String> {
        val out = ArrayList<String>()
        out += "BEGIN:VEVENT"
        out += "UID:" + text(e.uid)
        out += "DTSTAMP:$dtstamp"
        if (e.updatedAt > 0) {
            out += "LAST-MODIFIED:" + utc(e.updatedAt)
            out += "SEQUENCE:" + sequence(e.updatedAt)
        }
        when (val t = e.time) {
            is EventTime.Days -> {
                val end = if (t.endExclusive.toEpochDay() > t.first.toEpochDay()) t.endExclusive else t.first.plusDays(1)
                out += "DTSTART;VALUE=DATE:" + date(t.first.civil)
                out += "DTEND;VALUE=DATE:" + date(end.civil)
            }
            is EventTime.At -> {
                val start = wallMs(t.day, t.startMinutes, zone)
                val end = start + t.minutes * 60_000L
                if (useTzid) {
                    out += "DTSTART;TZID=$zone:" + local(start, zone)
                    out += "DTEND;TZID=$zone:" + local(end, zone)
                } else {
                    out += "DTSTART:" + utc(start)
                    out += "DTEND:" + utc(end)
                }
            }
        }
        out += "SUMMARY:" + text(e.title)
        if (e.location.isNotBlank()) out += "LOCATION:" + text(e.location)
        if (e.description.isNotBlank()) out += "DESCRIPTION:" + text(e.description)
        val lat = e.lat
        val lng = e.lng
        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
            out += "GEO:" + JavaText.fixed(lat, 6) + ";" + JavaText.fixed(lng, 6)
        }
        out += "TRANSP:" + if (e.free) "TRANSPARENT" else "OPAQUE"
        out += "END:VEVENT"
        return out
    }

    /** Minutes since 1970: grows with every edit and fits a 32-bit INTEGER for thousands of years. */
    fun sequence(updatedAt: Long): Int = (updatedAt / 60_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /**
     * The zone's offsets from before [from] up to [to] (epoch ms): the transition in force at [from] (or a fixed starting
     * offset when the zone never changed before), then each transition up to [to], one observance each.
     */
    fun vtimezone(zone: String, from: Long, to: Long): List<String> {
        val out = ArrayList<String>()
        out += "BEGIN:VTIMEZONE"
        out += "TZID:$zone"
        // The last change at or before [from]: walked to from a year and a half earlier (Foundation lists only the next).
        var current: Long? = null
        var probe = zoneNextTransition(from - 550L * DAY_MS, zone)
        while (probe != null && probe <= from + 1000) {
            current = probe
            probe = zoneNextTransition(probe, zone)
        }
        if (current == null) {
            val offset = zoneOffsetMs(from, zone)
            out += observance("STANDARD", "19700101T000000", offset, offset)
        } else {
            out += transition(current, zone)
        }
        var next = zoneNextTransition(current ?: from, zone)
        while (next != null && next <= to) {
            out += transition(next, zone)
            next = zoneNextTransition(next, zone)
        }
        out += "END:VTIMEZONE"
        return out
    }

    private fun transition(at: Long, zone: String): List<String> {
        val before = zoneOffsetMs(at - 1, zone)
        val after = zoneOffsetMs(at, zone)
        // The wall time just before the change, as java.time's dateTimeBefore.
        return observance(if (zoneDst(at, zone)) "DAYLIGHT" else "STANDARD", localAt(at + before), before, after)
    }

    private fun observance(kind: String, start: String, from: Long, to: Long) = listOf(
        "BEGIN:$kind",
        "DTSTART:$start",
        "TZOFFSETFROM:" + offset((from / 1000).toInt()),
        "TZOFFSETTO:" + offset((to / 1000).toInt()),
        "END:$kind",
    )

    /** "+0200", "-0500", "+0530", "+0000"; seconds only when the offset has them. */
    fun offset(totalSeconds: Int): String {
        val a = abs(totalSeconds)
        val s = (if (totalSeconds < 0) "-" else "+") + two(a / 3600) + two(a / 60 % 60)
        return if (a % 60 != 0) s + two(a % 60) else s
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun date(c: CivilDay): String = c.year.toString().padStart(4, '0') + two(c.month) + two(c.day)

    /** "yyyyMMdd'T'HHmmss" of the wall time [localMs] (ms since 1970 as if the zone were UTC). */
    private fun localAt(localMs: Long): String {
        val day = CivilDay.ofEpochDay(localMs.floorDiv(DAY_MS))
        val s = (localMs.mod(DAY_MS) / 1000).toInt()
        return date(day) + "T" + two(s / 3600) + two(s / 60 % 60) + two(s % 60)
    }

    private fun local(ms: Long, zone: String): String = localAt(ms + zoneOffsetMs(ms, zone))

    private fun utc(ms: Long): String = localAt(ms) + "Z"

    /** RFC 5545 TEXT: backslash, semicolon, comma and newlines escaped; other control characters dropped. */
    fun text(s: String): String {
        val sb = StringBuilder(s.length + 8)
        val t = s.replace("\r\n", "\n").replace('\r', '\n')
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == ';' -> sb.append("\\;")
                ch == ',' -> sb.append("\\,")
                ch == '\n' -> sb.append("\\n")
                ch == '\t' -> sb.append('\t')
                ch.code < 0x20 || ch.code == 0x7F -> Unit
                ch.isHighSurrogate() && i + 1 < t.length && t[i + 1].isLowSurrogate() -> { sb.append(ch).append(t[i + 1]); i++ }
                ch.isSurrogate() -> Unit // a lone surrogate has no UTF-8 form
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * Splits a content line into physical lines of at most 75 octets (the leading space of a continuation line counts),
     * only between whole UTF-8 characters. Lines are joined with CRLF + one space, as unfolding expects.
     */
    fun fold(line: String): String {
        if (line.encodeToByteArray().size <= MAX_OCTETS) return line
        val sb = StringBuilder(line.length + line.length / 40 * 3)
        var used = 0
        var i = 0
        while (i < line.length) {
            val pair = line[i].isHighSurrogate() && i + 1 < line.length && line[i + 1].isLowSurrogate()
            val octets = when {
                pair -> 4
                line[i].code < 0x80 -> 1
                line[i].code < 0x800 -> 2
                else -> 3
            }
            if (used + octets > MAX_OCTETS) {
                sb.append(CRLF).append(' ')
                used = 1
            }
            sb.append(line[i])
            if (pair) sb.append(line[i + 1])
            used += octets
            i += if (pair) 2 else 1
        }
        return sb.toString()
    }

    /** "Kyoto Osaka.ics": the trip's name made safe for a file, as the Android app's ExportFiles.fileName makes it. */
    fun fileName(title: String): String {
        val clean = title.replace(Regex("[^\\p{L}\\p{N} .,_-]+"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(60).trim()
        return "${clean.ifEmpty { "Trip" }}.ics"
    }
}

/** When one plan item happens, as a calendar app wants it: start and end in epoch ms, and whether it is all day. */
data class ItemWhen(val beginMs: Long, val endMs: Long, val allDay: Boolean)

/**
 * One plan item handed to the phone's own new-event screen (iPhone: EventKitUI's, the Android app's ACTION_INSERT):
 * it opens with the event filled in and the traveller saves it there. No calendar permission. A copy: later plan
 * changes don't reach it (adding the whole trip from the trip's ⋮ › Calendar keeps every item in step).
 */
object CalendarIntents {
    /** A timed item at its time in [zone] for [TripEvents.DEFAULT_MINUTES]; an untimed one all day (UTC midnights). */
    fun whenOf(day: LocalDate, minutes: Int?, zone: String): ItemWhen = whenOf(
        if (minutes == null) EventTime.Days(day, day.plusDays(1))
        else EventTime.At(day, minutes.coerceIn(0, 24 * 60 - 1), TripEvents.DEFAULT_MINUTES),
        zone,
    )

    /** [time] as the calendar app takes it: timed in [zone], or all day at UTC midnights. */
    fun whenOf(time: EventTime, zone: String): ItemWhen = when (time) {
        is EventTime.Days -> ItemWhen(time.first.toEpochDay() * DAY_MS, time.endExclusive.toEpochDay() * DAY_MS, allDay = true)
        is EventTime.At -> wallMs(time.day, time.startMinutes, zone).let { ItemWhen(it, it + time.minutes * 60_000L, allDay = false) }
    }

    /** The event's title: the item's, else its place's, else its kind (as the trip's calendar events are named). */
    fun title(item: PlanItem): String = item.title.trim()
        .ifBlank { item.placeName.substringBefore(',').trim() }
        .ifBlank { PlanKind.of(item.kind).label }

    /** The event text: the item's note and link (as in the trip's calendar events), then [tripTitle]. */
    fun description(item: PlanItem, tripTitle: String): String =
        listOf(TripEvents.itemDescription(item), tripTitle.trim()).filter { it.isNotBlank() }.joinToString("\n\n")
}

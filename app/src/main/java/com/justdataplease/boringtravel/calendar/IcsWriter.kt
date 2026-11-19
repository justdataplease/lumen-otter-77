package com.justdataplease.boringtravel.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A trip and its plan as an iCalendar file (RFC 5545) for Apple Calendar, Outlook, Google Calendar's import or
 * anything else. Pure: the caller gives the clock ([stamp]) and the app version.
 *
 * - Lines end in CRLF and are folded at 75 octets without splitting a UTF-8 character.
 * - TEXT values escape `\`, `;`, `,` and newlines; other control characters are dropped.
 * - UIDs come from our ids, so importing a newer file updates the events instead of adding copies; SEQUENCE and
 *   LAST-MODIFIED follow the item's own edits.
 * - All-day events are `VALUE=DATE` with the day after the last as an exclusive end.
 * - Timed events carry `TZID=<the trip's zone>` and the file includes a VTIMEZONE with the zone's real offsets over the
 *   trip (each transition listed once, no recurrence rules to get wrong). Zones that are a fixed offset (UTC,
 *   Etc/GMT+3, +05:30) are written as UTC times with `Z` instead, since a TZID can't name them portably.
 */
object IcsWriter {
    const val MIME = "text/calendar"
    private const val CRLF = "\r\n"
    private const val MAX_OCTETS = 75

    private val dateF = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
    private val localF = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)
    private val utcF = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun write(name: String, events: List<TripEvent>, zone: ZoneId, stamp: Instant, appVersion: String): String {
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
            val from = timed.minOf { it.start.atZone(zone).toInstant() }
            val to = timed.maxOf { it.start.atZone(zone).toInstant().plusSeconds(it.minutes * 60L) }
            lines += vtimezone(zone, from, to)
        }
        val dtstamp = utcF.format(stamp)
        for (e in events) lines += vevent(e, zone, useTzid, dtstamp)
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + CRLF }
    }

    /** Region zones with real rules get a TZID; fixed offsets are written in UTC. */
    fun usesTzid(zone: ZoneId): Boolean = zone.normalized() !is ZoneOffset

    private fun vevent(e: TripEvent, zone: ZoneId, useTzid: Boolean, dtstamp: String): List<String> {
        val out = ArrayList<String>()
        out += "BEGIN:VEVENT"
        out += "UID:" + text(e.uid)
        out += "DTSTAMP:$dtstamp"
        if (e.updatedAt > 0) {
            out += "LAST-MODIFIED:" + utcF.format(Instant.ofEpochMilli(e.updatedAt))
            out += "SEQUENCE:" + sequence(e.updatedAt)
        }
        when (val t = e.time) {
            is EventTime.Days -> {
                val end = if (t.endExclusive.isAfter(t.first)) t.endExclusive else t.first.plusDays(1)
                out += "DTSTART;VALUE=DATE:" + date(t.first)
                out += "DTEND;VALUE=DATE:" + date(end)
            }
            is EventTime.At -> {
                val start = t.start.atZone(zone)
                val end = start.plusMinutes(t.minutes.toLong())
                if (useTzid) {
                    out += "DTSTART;TZID=${zone.id}:" + localF.format(start.toLocalDateTime())
                    out += "DTEND;TZID=${zone.id}:" + localF.format(end.toLocalDateTime())
                } else {
                    out += "DTSTART:" + utcF.format(start.toInstant())
                    out += "DTEND:" + utcF.format(end.toInstant())
                }
            }
        }
        out += "SUMMARY:" + text(e.title)
        if (e.location.isNotBlank()) out += "LOCATION:" + text(e.location)
        if (e.description.isNotBlank()) out += "DESCRIPTION:" + text(e.description)
        if (e.lat != null && e.lng != null && e.lat in -90.0..90.0 && e.lng in -180.0..180.0) {
            out += String.format(Locale.ROOT, "GEO:%.6f;%.6f", e.lat, e.lng)
        }
        out += "TRANSP:" + if (e.free) "TRANSPARENT" else "OPAQUE"
        out += "END:VEVENT"
        return out
    }

    /** Minutes since 1970: grows with every edit and fits a 32-bit INTEGER for thousands of years. */
    fun sequence(updatedAt: Long): Int = (updatedAt / 60_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /**
     * The zone's offsets from before [from] up to [to]: the transition in force at [from] (or a fixed starting
     * offset when the zone never changed before), then each transition up to [to], one observance each.
     */
    fun vtimezone(zone: ZoneId, from: Instant, to: Instant): List<String> {
        val rules = zone.rules
        val out = ArrayList<String>()
        out += "BEGIN:VTIMEZONE"
        out += "TZID:" + zone.id
        val current = rules.previousTransition(from.plusSeconds(1))
        if (current == null) {
            val offset = rules.getOffset(from)
            out += observance("STANDARD", LocalDateTime.of(1970, 1, 1, 0, 0), offset, offset)
        } else {
            out += observance(if (rules.isDaylightSavings(current.instant)) "DAYLIGHT" else "STANDARD", current.dateTimeBefore, current.offsetBefore, current.offsetAfter)
        }
        var next = rules.nextTransition(current?.instant ?: from)
        while (next != null && !next.instant.isAfter(to)) {
            out += observance(if (rules.isDaylightSavings(next.instant)) "DAYLIGHT" else "STANDARD", next.dateTimeBefore, next.offsetBefore, next.offsetAfter)
            next = rules.nextTransition(next.instant)
        }
        out += "END:VTIMEZONE"
        return out
    }

    private fun observance(kind: String, start: LocalDateTime, from: ZoneOffset, to: ZoneOffset) = listOf(
        "BEGIN:$kind",
        "DTSTART:" + localF.format(start),
        "TZOFFSETFROM:" + offset(from),
        "TZOFFSETTO:" + offset(to),
        "END:$kind",
    )

    /** "+0200", "-0500", "+0530", "+0000"; seconds only when the offset has them. */
    fun offset(o: ZoneOffset): String {
        val total = o.totalSeconds
        val abs = Math.abs(total)
        val s = String.format(Locale.ROOT, "%s%02d%02d", if (total < 0) "-" else "+", abs / 3600, abs / 60 % 60)
        return if (abs % 60 != 0) s + String.format(Locale.ROOT, "%02d", abs % 60) else s
    }

    private fun date(d: LocalDate): String = dateF.format(d)

    /** RFC 5545 TEXT: backslash, semicolon, comma and newlines escaped; other control characters dropped. */
    fun text(s: String): String {
        val sb = StringBuilder(s.length + 8)
        s.replace("\r\n", "\n").replace('\r', '\n').codePoints().forEach { cp ->
            when {
                cp == '\\'.code -> sb.append("\\\\")
                cp == ';'.code -> sb.append("\\;")
                cp == ','.code -> sb.append("\\,")
                cp == '\n'.code -> sb.append("\\n")
                cp == '\t'.code -> sb.append('\t')
                cp < 0x20 || cp == 0x7F -> Unit
                cp in 0xD800..0xDFFF -> Unit // a lone surrogate has no UTF-8 form
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    /**
     * Splits a content line into physical lines of at most 75 octets (the leading space of a continuation line counts),
     * only between whole UTF-8 characters. Lines are joined with CRLF + one space, as unfolding expects.
     */
    fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= MAX_OCTETS) return line
        val sb = StringBuilder(line.length + line.length / 40 * 3)
        var used = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val octets = utf8Length(cp)
            if (used + octets > MAX_OCTETS) {
                sb.append(CRLF).append(' ')
                used = 1
            }
            sb.appendCodePoint(cp)
            used += octets
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}

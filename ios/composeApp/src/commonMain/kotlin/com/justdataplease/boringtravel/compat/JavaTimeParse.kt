package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.time.CivilDay

/*
 * Reading dates the way java.time reads them, for the Android app's Copy from a link (site/Feeds.kt's SiteDates,
 * site/TumblrApi.kt): ISO moments with an offset ("2016-11-01T06:00:00.000-07:00", "…Z"), ISO local date-times, RFC 822
 * and RFC 1123 dates in feeds ("Wed, 11 Nov 2026 14:00:00 +0000", "Tue, 10 Nov 2026 18:42:18 GMT", "… 08:00 PDT") and
 * Tumblr's "2016-04-08 10:00:00 GMT". Strict where Java is (a day that doesn't exist, a weekday that doesn't match its
 * date), so a date Java refuses is refused here too and the next form is tried. Times are in milliseconds.
 */
internal object JavaDates {
    private const val DAY_MS = 86_400_000L

    /** ISO date, 'T', HH:mm, optional :ss and fraction, then Z or ±HH:MM[:ss] (ISO_OFFSET_DATE_TIME). */
    private val OFFSET_ISO = Regex(
        """^([+-]?\d{4,9})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|z|[+-]\d{2}:\d{2}(?::\d{2})?)$""",
    )

    /** ISO_LOCAL_DATE_TIME: the same without the offset. */
    private val LOCAL_ISO = Regex("""^([+-]?\d{4,9})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$""")

    /** The moment of an ISO date-time with its offset; null when Java's OffsetDateTime.parse would refuse it. */
    fun offsetDateTime(text: String): Long? {
        val m = OFFSET_ISO.matchEntire(text) ?: return null
        val g = m.groupValues
        val wall = wallMs(g[1], g[2], g[3], g[4], g[5], g[6], g[7]) ?: return null
        val offset = isoOffsetMs(g[8]) ?: return null
        return wall - offset
    }

    /** An ISO local date-time as if on UTC's clock; null when Java's LocalDateTime.parse would refuse it. */
    fun localDateTime(text: String): Long? {
        val g = LOCAL_ISO.matchEntire(text)?.groupValues ?: return null
        return wallMs(g[1], g[2], g[3], g[4], g[5], g[6], g[7])
    }

    private fun wallMs(y: String, mo: String, d: String, h: String, mi: String, s: String, fraction: String): Long? {
        // Java writes a year past 9999 with a sign; four digits otherwise.
        if (y.length > 4 && !y.startsWith("+")) return null
        val year = y.removePrefix("+").toIntOrNull() ?: return null
        val month = mo.toInt()
        val day = d.toInt()
        if (!CivilDay.isValid(year, month, day)) return null
        val hour = h.toInt()
        val minute = mi.toInt()
        val second = s.ifEmpty { "0" }.toInt()
        if (hour > 23 || minute > 59 || second > 59) return null
        val millis = fraction.padEnd(3, '0').take(3).ifEmpty { "0" }.toInt()
        return CivilDay(year, month, day).epochDay * DAY_MS + hour * 3_600_000L + minute * 60_000L + second * 1000L + millis
    }

    private fun isoOffsetMs(text: String): Long? {
        if (text == "Z" || text == "z") return 0
        val sign = if (text[0] == '-') -1 else 1
        val parts = text.substring(1).split(':').map { it.toInt() }
        val (h, m) = parts[0] to parts[1]
        val s = parts.getOrElse(2) { 0 }
        if (h > 18 || m > 59 || s > 59) return null
        return sign * (h * 3_600_000L + m * 60_000L + s * 1000L)
    }

    /** Java's Instant.toString: "2016-04-03T10:00:00Z", with ".SSS" when there are milliseconds. */
    fun isoInstant(epochMs: Long): String {
        val day = epochMs.floorDiv(DAY_MS)
        val ms = epochMs - day * DAY_MS
        val c = CivilDay.ofEpochDay(day)
        fun two(n: Long) = n.toString().padStart(2, '0')
        val time = two(ms / 3_600_000) + ":" + two(ms / 60_000 % 60) + ":" + two(ms / 1000 % 60)
        val fraction = (ms % 1000).takeIf { it != 0L }?.let { "." + it.toString().padStart(3, '0') }.orEmpty()
        return c.iso() + "T" + time + fraction + "Z"
    }

    // ---- Patterns (DateTimeFormatter.ofPattern) and RFC 1123

    val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /**
     * Zones' short names as feeds write them (Java reads every name its zone database knows; these are the ones blogs
     * use), as their offsets in hours.
     */
    private val ZONE_NAMES: Map<String, Double> = mapOf(
        "GMT" to 0.0, "UTC" to 0.0, "UT" to 0.0, "Z" to 0.0, "WET" to 0.0,
        "BST" to 1.0, "CET" to 1.0, "WEST" to 1.0, "MET" to 1.0,
        "CEST" to 2.0, "EET" to 2.0, "MEST" to 2.0, "SAST" to 2.0,
        "EEST" to 3.0, "MSK" to 3.0,
        "IST" to 5.5,
        "SGT" to 8.0, "HKT" to 8.0, "AWST" to 8.0,
        "JST" to 9.0, "KST" to 9.0,
        "ACST" to 9.5, "ACDT" to 10.5,
        "AEST" to 10.0, "AEDT" to 11.0,
        "NZST" to 12.0, "NZDT" to 13.0,
        "AST" to -4.0, "ADT" to -3.0,
        "EST" to -5.0, "EDT" to -4.0,
        "CST" to -6.0, "CDT" to -5.0,
        "MST" to -7.0, "MDT" to -6.0,
        "PST" to -8.0, "PDT" to -7.0,
        "AKST" to -9.0, "AKDT" to -8.0,
        "HST" to -10.0,
    )

    /** What a pattern read: the date, the time, and the zone or offset when it had one. */
    class Fields {
        var year: Int? = null
        var month: Int? = null
        var day: Int? = null
        var weekday: Int? = null
        var hour: Int = 0
        var minute: Int = 0
        var second: Int = 0
        var offsetMs: Long? = null
    }

    /** RFC 1123 as Java's RFC_1123_DATE_TIME reads it (case-insensitive; weekday and seconds optional; GMT or ±HHMM). */
    private val RFC_1123 = Regex(
        """^(?:([A-Za-z]{3}), )?(\d{1,2}) ([A-Za-z]{3}) (\d{4}) (\d{2}):(\d{2})(?::(\d{2}))? (GMT|[+-]\d{4})$""",
        RegexOption.IGNORE_CASE,
    )

    fun rfc1123(text: String): Fields? {
        val g = RFC_1123.matchEntire(text)?.groupValues ?: return null
        val f = Fields()
        if (g[1].isNotEmpty()) f.weekday = WEEKDAYS.indexOfFirst { it.take(3).equals(g[1], ignoreCase = true) }.takeIf { it >= 0 }?.plus(1) ?: return null
        f.day = g[2].toInt()
        f.month = MONTHS.indexOfFirst { it.take(3).equals(g[3], ignoreCase = true) }.takeIf { it >= 0 }?.plus(1) ?: return null
        f.year = g[4].toInt()
        f.hour = g[5].toInt()
        f.minute = g[6].toInt()
        f.second = g[7].ifEmpty { "0" }.toInt()
        f.offsetMs = if (g[8].equals("GMT", ignoreCase = true)) 0 else hhmm(g[8]) ?: return null
        return f
    }

    private fun hhmm(text: String): Long? {
        val sign = if (text[0] == '-') -1 else 1
        val h = text.substring(1, 3).toInt()
        val m = text.substring(3, 5).toInt()
        if (h > 18 || m > 59) return null
        return sign * (h * 3_600_000L + m * 60_000L)
    }

    /** [text] read by [parts] (case-sensitive, as ofPattern's formatters are); null when it doesn't fit. */
    fun pattern(parts: List<DateTimeFormatter.Part>, text: String): Fields? {
        val f = Fields()
        val end = read(parts, 0, parts.size, text, 0, f) ?: return null
        return f.takeIf { end == text.length }
    }

    /** Reads parts [from] until [to] at [at]; where it ended, or null. An optional section that doesn't fit is skipped. */
    private fun read(parts: List<DateTimeFormatter.Part>, from: Int, to: Int, text: String, at: Int, f: Fields): Int? {
        var i = from
        var pos = at
        while (i < to) {
            when (val p = parts[i]) {
                is DateTimeFormatter.Part.Optional -> {
                    if (!p.start) {
                        i++
                        continue
                    }
                    val close = closing(parts, i)
                    val trial = f.copy()
                    val end = read(parts, i + 1, close, text, pos, trial)
                    if (end != null) {
                        trial.into(f)
                        pos = end
                    }
                    i = close + 1
                    continue
                }
                is DateTimeFormatter.Part.Text -> {
                    if (!text.startsWith(p.text, pos)) return null
                    pos += p.text.length
                }
                is DateTimeFormatter.Part.Field -> pos = field(p, text, pos, f) ?: return null
            }
            i++
        }
        return pos
    }

    private fun closing(parts: List<DateTimeFormatter.Part>, open: Int): Int {
        var depth = 0
        for (i in open until parts.size) {
            val p = parts[i] as? DateTimeFormatter.Part.Optional ?: continue
            if (p.start) depth++ else if (--depth == 0) return i
        }
        return parts.size
    }

    private fun Fields.copy(): Fields = Fields().also { into(it) }

    private fun Fields.into(other: Fields) {
        other.year = year
        other.month = month
        other.day = day
        other.weekday = weekday
        other.hour = hour
        other.minute = minute
        other.second = second
        other.offsetMs = offsetMs
    }

    private fun digits(text: String, at: Int, min: Int, max: Int): Pair<Int, Int>? {
        var end = at
        while (end < text.length && end - at < max && text[end].isDigit()) end++
        if (end - at < min) return null
        return text.substring(at, end).toInt() to end
    }

    private fun word(text: String, at: Int, names: List<String>): Pair<Int, Int>? {
        // The longest name first ("Sept" never; "Monday" before "Mon").
        val found = names.withIndex().filter { text.startsWith(it.value, at) }.maxByOrNull { it.value.length } ?: return null
        return found.index to at + found.value.length
    }

    private fun field(p: DateTimeFormatter.Part.Field, text: String, at: Int, f: Fields): Int? {
        val n = p.count
        return when (p.letter) {
            'E' -> word(text, at, if (n >= 4) WEEKDAYS else WEEKDAYS.map { it.take(3) })?.let { (i, end) -> f.weekday = i + 1; end }
            'M' -> when {
                n >= 4 -> word(text, at, MONTHS)
                n == 3 -> word(text, at, MONTHS.map { it.take(3) })
                else -> digits(text, at, n, 2)?.let { (v, end) -> (v - 1) to end }
            }?.let { (i, end) -> f.month = i + 1; end }
            'd' -> digits(text, at, n, 2)?.let { (v, end) -> f.day = v; end }
            // "yy" is 2000–2099, as Java's reduced year; "yyyy" four digits or more.
            'y' -> digits(text, at, if (n == 2) 2 else n, if (n == 2) 2 else 9)?.let { (v, end) -> f.year = if (n == 2) 2000 + v else v; end }
            'H' -> digits(text, at, n, 2)?.let { (v, end) -> f.hour = v; end }
            'm' -> digits(text, at, n, 2)?.let { (v, end) -> f.minute = v; end }
            's' -> digits(text, at, n, 2)?.let { (v, end) -> f.second = v; end }
            'z' -> ZONE_NAMES.keys.filter { text.startsWith(it, at) }.maxByOrNull { it.length }?.let { name ->
                f.offsetMs = (ZONE_NAMES.getValue(name) * 3_600_000).toLong()
                at + name.length
            }
            'Z' -> if (at + 5 <= text.length && text[at] in "+-" && text.substring(at + 1, at + 5).all(Char::isDigit)) {
                f.offsetMs = hhmm(text.substring(at, at + 5)) ?: return null
                at + 5
            } else null
            else -> null
        }
    }

    /**
     * The fields as a wall time on UTC's clock, as Java's SMART resolver takes them: the day kept within its month, the
     * weekday checked against the date. Null when they make no date.
     */
    fun wallMs(f: Fields): Long? {
        val year = f.year ?: return null
        val month = f.month?.takeIf { it in 1..12 } ?: return null
        val day = f.day?.takeIf { it in 1..31 }?.let { minOf(it, CivilDay.lengthOfMonth(year, month)) } ?: return null
        if (f.hour > 23 || f.minute > 59 || f.second > 59) return null
        val civil = CivilDay(year, month, day)
        if (f.weekday != null && f.weekday != civil.dayOfWeek) return null
        return civil.epochDay * DAY_MS + f.hour * 3_600_000L + f.minute * 60_000L + f.second * 1000L
    }
}

/** The moment [text] names, read by this formatter's pattern with its zone or offset (ZonedDateTime.parse). */
internal fun DateTimeFormatter.parseMoment(text: String): Long {
    val f = (if (rfc1123) JavaDates.rfc1123(text) else JavaDates.pattern(pattern, text)) ?: throw DateTimeParseException(text)
    val offset = f.offsetMs ?: throw DateTimeParseException(text)
    return (JavaDates.wallMs(f) ?: throw DateTimeParseException(text)) - offset
}

/** [text] read by this formatter's pattern as a wall time on UTC's clock (LocalDateTime.parse). */
internal fun DateTimeFormatter.parseLocal(text: String): Long {
    val f = JavaDates.pattern(pattern, text) ?: throw DateTimeParseException(text)
    return JavaDates.wallMs(f) ?: throw DateTimeParseException(text)
}

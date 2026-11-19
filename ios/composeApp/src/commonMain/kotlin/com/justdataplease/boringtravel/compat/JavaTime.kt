package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.ios.zoneKnown
import com.justdataplease.boringtravel.ios.zoneOffsetMs
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.systemZoneId

/*
 * The java.time and java.util calls the Android app's export and book files make, in plain Kotlin under the same
 * names, so those files compile for iPhone with only their imports changed (they are read in at build time:
 * composeApp/build.gradle.kts). Dates and numbers are written in the language the Android code asks for: the phone's for
 * Locale.getDefault() (PhoneLocale.kt, since the parity round of 19 Nov 2026), Java's English for the root locale and
 * English (US). Only what those files call is here: a pattern letter or a call they don't use fails loudly, so a change
 * in the Android files shows in the tests.
 */

/** java.util.Locale as the export files use it: a language, a country and (rarely) a script. */
class Locale(val language: String, val country: String = "", val script: String = "") {
    /** "en-GB", "el", "sr-Latn-RS", "und" for no language (Java's toLanguageTag). */
    fun toLanguageTag(): String = when {
        language.isEmpty() -> "und"
        else -> listOf(language, script, country).filter { it.isNotEmpty() }.joinToString("-")
    }

    override fun equals(other: Any?): Boolean = other is Locale && other.language == language && other.country == country && other.script == script

    override fun hashCode(): Int = (language.hashCode() * 31 + country.hashCode()) * 31 + script.hashCode()

    override fun toString(): String = when {
        script.isNotEmpty() -> "${language}_${country}_#$script"
        country.isEmpty() -> language
        else -> "${language}_$country"
    }

    companion object {
        val ROOT = Locale("")
        val ENGLISH = Locale("en")
        val US = Locale("en", "US")
        val UK = Locale("en", "GB")

        /** "el-GR" → el, GR; "sr-Latn-RS" → sr, RS, Latn: the language, the region and a script are read. */
        fun forLanguageTag(tag: String): Locale {
            val parts = tag.substringBefore('@').split('-', '_').filter { it.isNotEmpty() }
            val language = parts.firstOrNull()?.takeIf { it.length in 2..3 && it.all(Char::isLetter) }?.lowercase() ?: return ROOT
            val rest = parts.drop(1)
            val script = rest.firstOrNull()?.takeIf { it.length == 4 && it.all(Char::isLetter) }
                ?.let { it.lowercase().replaceFirstChar(Char::uppercaseChar) }.orEmpty()
            val country = rest.firstOrNull { (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit)) }?.uppercase().orEmpty()
            return Locale(language, country, script)
        }

        /**
         * The phone's language with its region ([phoneLanguageTag]: "el-GR"), as Android's Locale.getDefault(); the
         * phone's region ([phoneRegion]) when its language names none, so Letter paper still follows the region.
         */
        fun getDefault(): Locale {
            val phone = forLanguageTag(phoneLanguageTag())
            if (phone == ROOT) return Locale("en", phoneRegion())
            return if (phone.country.isEmpty()) Locale(phone.language, phoneRegion(), phone.script) else phone
        }
    }
}

/** The phone's region ("GR", "US"); empty when it has none. Foundation's NSLocale on iPhone, Java's on the desktop. */
expect fun phoneRegion(): String

/**
 * java.time.ZoneId: a zone by its id. [of] refuses a zone the phone doesn't know, as Java's does. [ZoneOffset.UTC] is
 * one too (its id "Z", as Java's).
 */
open class ZoneId internal constructor(val id: String) {
    /** The id the phone's own zones know it by ("Z" is UTC). */
    internal val platformId: String get() = if (id == "Z") "UTC" else id

    override fun equals(other: Any?): Boolean = other is ZoneId && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id

    companion object {
        fun of(zoneId: String): ZoneId {
            if (zoneId == "Z") return ZoneOffset.UTC
            require(zoneId.isNotBlank() && zoneKnown(zoneId)) { "Unknown time zone: $zoneId" }
            return ZoneId(zoneId)
        }

        fun systemDefault(): ZoneId = ZoneId(systemZoneId())
    }
}

/** java.time.ZoneOffset: only UTC, which Copy from a link's dates without a zone are read in. */
class ZoneOffset private constructor(id: String) : ZoneId(id) {
    companion object {
        val UTC: ZoneOffset = ZoneOffset("Z")
    }
}

/**
 * java.time.Instant, for a moment in epoch milliseconds (Java keeps nanoseconds; the posts' dates need no more than
 * milliseconds). Ordered, equal by the moment, written as Java writes it ("2016-04-03T10:00:00Z").
 */
class Instant private constructor(private val epochMs: Long) : Comparable<Instant> {
    fun toEpochMilli(): Long = epochMs

    fun atZone(zone: ZoneId): ZonedDateTime = ZonedDateTime(epochMs, zone)

    fun plusMillis(millis: Long): Instant = Instant(epochMs + millis)

    fun plusSeconds(seconds: Long): Instant = Instant(epochMs + seconds * 1000)

    fun minusSeconds(seconds: Long): Instant = Instant(epochMs - seconds * 1000)

    /** The whole seconds since 1970 (Java's getEpochSecond). */
    val epochSecond: Long get() = epochMs.floorDiv(1000L)

    override fun compareTo(other: Instant): Int = epochMs.compareTo(other.epochMs)

    override fun equals(other: Any?): Boolean = other is Instant && other.epochMs == epochMs

    override fun hashCode(): Int = epochMs.hashCode()

    override fun toString(): String = JavaDates.isoInstant(epochMs)

    companion object {
        fun ofEpochMilli(epochMilli: Long): Instant = Instant(epochMilli)

        fun ofEpochSecond(epochSecond: Long): Instant = Instant(epochSecond * 1000)

        /** "2016-04-03T10:00:00Z", or with an offset ("…+02:00"); throws [DateTimeParseException] for anything else. */
        fun parse(text: CharSequence): Instant = Instant(JavaDates.offsetDateTime(text.toString()) ?: throw DateTimeParseException(text))
    }
}

/** java.time.ZonedDateTime: a moment read on the clocks of a zone. */
class ZonedDateTime internal constructor(private val epochMs: Long, private val zone: ZoneId) {
    private val wallMs: Long = epochMs + zoneOffsetMs(epochMs, zone.platformId)

    fun toLocalDate(): LocalDate = localDateAt(epochMs, zone.platformId)

    fun toInstant(): Instant = Instant.ofEpochMilli(epochMs)

    val hour: Int get() = (wallMs.mod(DAY_MS) / HOUR_MS).toInt()
    val minute: Int get() = (wallMs.mod(HOUR_MS) / MINUTE_MS).toInt()

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L

        /** The moment [text] names in [formatter]'s pattern (RFC 822 dates, with a zone or an offset). */
        fun parse(text: CharSequence, formatter: DateTimeFormatter): ZonedDateTime =
            ZonedDateTime(formatter.parseMoment(text.toString()), ZoneOffset.UTC)
    }
}

/** java.time.OffsetDateTime, only to read an ISO moment with its offset ("2016-11-01T06:00:00.000-07:00"). */
class OffsetDateTime private constructor(private val epochMs: Long) {
    fun toInstant(): Instant = Instant.ofEpochMilli(epochMs)

    companion object {
        fun parse(text: CharSequence): OffsetDateTime = OffsetDateTime(JavaDates.offsetDateTime(text.toString()) ?: throw DateTimeParseException(text))
    }
}

/** java.time.LocalDateTime: a day and a time on no zone's clock, as the posts' dates without a zone are read. */
class LocalDateTime private constructor(private val wallMs: Long) {
    /** The moment this is at [offset] (UTC only). */
    fun toInstant(@Suppress("UNUSED_PARAMETER") offset: ZoneOffset): Instant = Instant.ofEpochMilli(wallMs)

    companion object {
        /** "2019-05-04T08:30:00" (seconds and their fraction optional). */
        fun parse(text: CharSequence): LocalDateTime = LocalDateTime(JavaDates.localDateTime(text.toString()) ?: throw DateTimeParseException(text))

        fun parse(text: CharSequence, formatter: DateTimeFormatter): LocalDateTime = LocalDateTime(formatter.parseLocal(text.toString()))

        /** [day] at [hour]:[minute]. */
        fun of(day: LocalDate, hour: Int, minute: Int): LocalDateTime =
            LocalDateTime(day.toEpochDay() * 86_400_000L + hour * 3_600_000L + minute * 60_000L)
    }
}

/** java.time.format.DateTimeParseException. */
class DateTimeParseException(text: CharSequence) : IllegalArgumentException("Text '$text' could not be parsed")

/**
 * The moment [this] day is at [hour]:[minute] on [zone]'s clocks (java.time's `day.atTime(h, m).atZone(zone).toInstant()`,
 * which on a day the clocks change keeps the wall time). Named apart from java.time's own atTime, which the engine's
 * day has on the desktop.
 */
fun LocalDate.atTimeIn(hour: Int, minute: Int, zone: ZoneId): Instant {
    val wall = toEpochDay() * 86_400_000L + hour * 3_600_000L + minute * 60_000L
    val first = wall - zoneOffsetMs(wall, zone.platformId)
    return Instant.ofEpochMilli(wall - zoneOffsetMs(first, zone.platformId))
}

/** java.time's `day < other` and the like, where the engine's day isn't Comparable (iPhone). */
operator fun LocalDate.compareTo(other: LocalDate): Int = toEpochDay().compareTo(other.toEpochDay())

/**
 * java.time.format.DateTimeFormatter for the patterns the export files use: E (weekday), d, M (month), y, H, m and
 * quoted text, in English whatever the locale (see the file's note). An unknown letter is refused at once. Copy from a
 * link also reads dates with it (RFC 822 dates in feeds, Tumblr's): s, z (a zone's short name), Z (+HHMM) and optional
 * sections in brackets, and [RFC_1123_DATE_TIME] ([parseMoment], [parseLocal], in JavaTimeParse.kt).
 */
class DateTimeFormatter private constructor(
    private val parts: List<Part>,
    internal val caseInsensitive: Boolean = false,
    internal val rfc1123: Boolean = false,
    private val text: String = "",
    private val locale: Locale = Locale.ROOT,
) {
    internal sealed interface Part {
        data class Text(val text: String) : Part
        data class Field(val letter: Char, val count: Int) : Part

        /** "[" (an optional section starts) or "]" (it ends). */
        data class Optional(val start: Boolean) : Part
    }

    internal val pattern: List<Part> get() = parts

    fun format(date: LocalDate): String = write(date, null)

    fun format(time: ZonedDateTime): String = write(time.toLocalDate(), time)

    private fun write(date: LocalDate, time: ZonedDateTime?): String {
        // Another language than Java's English: the phone's own names, as java.time writes them there.
        if (!locale.plainEnglish()) {
            if (time == null) parts.firstOrNull { it is Part.Field && (it.letter == 'H' || it.letter == 'm') }?.let { unsupported(it as Part.Field) }
            if (parts.any { it is Part.Optional }) throw IllegalArgumentException("Optional sections are only read")
            return platformDate(text, date.toEpochDay(), time?.let { it.hour * 60 + it.minute } ?: 0, locale.toLanguageTag())
        }
        return english(date, time)
    }

    private fun english(date: LocalDate, time: ZonedDateTime?): String = buildString {
        val c = date.civil
        for (p in parts) when (p) {
            is Part.Text -> append(p.text)
            is Part.Optional -> throw IllegalArgumentException("Optional sections are only read")
            is Part.Field -> when (p.letter) {
                'E' -> append(if (p.count >= 4) WEEKDAYS[c.dayOfWeek - 1] else WEEKDAYS[c.dayOfWeek - 1].take(3))
                'd' -> append(c.day.toString().padStart(p.count, '0'))
                // L is the month standing alone ("June 2027"); in English it reads as M.
                'M', 'L' -> append(
                    when {
                        p.count >= 4 -> MONTHS[c.month - 1]
                        p.count == 3 -> MONTHS[c.month - 1].take(3)
                        else -> c.month.toString().padStart(p.count, '0')
                    },
                )
                'y' -> append(if (p.count == 2) (c.year.mod(100)).toString().padStart(2, '0') else c.year.toString().padStart(p.count, '0'))
                'H' -> append((time ?: unsupported(p)).hour.toString().padStart(p.count, '0'))
                'm' -> append((time ?: unsupported(p)).minute.toString().padStart(p.count, '0'))
                else -> unsupported(p)
            }
        }
    }

    private fun unsupported(p: Part.Field): Nothing = throw IllegalArgumentException("Unsupported field: ${p.letter}")

    companion object {
        private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        private const val LETTERS = "EdMLyHmszZ"

        /** RFC 1123 ("Tue, 3 Jun 2008 11:05:30 GMT"), as Java's: case-insensitive, the weekday and the seconds optional. */
        val RFC_1123_DATE_TIME: DateTimeFormatter = DateTimeFormatter(emptyList(), caseInsensitive = true, rfc1123 = true)

        fun ofPattern(pattern: String): DateTimeFormatter = ofPattern(pattern, Locale.getDefault())

        fun ofPattern(pattern: String, locale: Locale): DateTimeFormatter {
            val parts = ArrayList<Part>()
            var i = 0
            while (i < pattern.length) {
                val ch = pattern[i]
                when {
                    ch == '[' || ch == ']' -> {
                        parts += Part.Optional(start = ch == '[')
                        i++
                    }
                    ch == '\'' -> {
                        val end = pattern.indexOf('\'', i + 1)
                        require(end > i) { "Unclosed quote in $pattern" }
                        parts += Part.Text(if (end == i + 1) "'" else pattern.substring(i + 1, end))
                        i = end + 1
                    }
                    ch.isLetter() -> {
                        require(ch in LETTERS) { "Unsupported pattern letter '$ch' in $pattern" }
                        var n = 1
                        while (i + n < pattern.length && pattern[i + n] == ch) n++
                        parts += Part.Field(ch, n)
                        i += n
                    }
                    else -> {
                        parts += Part.Text(ch.toString())
                        i++
                    }
                }
            }
            return DateTimeFormatter(parts, text = pattern, locale = locale)
        }
    }
}

/* java.time.LocalDate's members the export files read, for the engine's day (on iPhone its own small class). */

fun LocalDate.isAfter(other: LocalDate): Boolean = toEpochDay() > other.toEpochDay()

fun LocalDate.isBefore(other: LocalDate): Boolean = toEpochDay() < other.toEpochDay()

val LocalDate.year: Int get() = civil.year

/** The month as a number (Java's is an enum): only compared. */
val LocalDate.month: Int get() = civil.month

val LocalDate.dayOfMonth: Int get() = civil.day

/** java.time's plusYears: the same day [years] on; 29 February becomes the 28th in a year without one. */
fun LocalDate.plusYears(years: Long): LocalDate {
    val c = civil
    val year = (c.year + years).toInt()
    return com.justdataplease.boringtravel.time.localDate(year, c.month, minOf(c.day, com.justdataplease.boringtravel.time.CivilDay.lengthOfMonth(year, c.month)))
}

/**
 * java.util's sorted map by day (sortedMapOf<LocalDate, V>()), as the AI planner's review gathers its days: each day
 * once, in day order.
 */
class DayMap<V> : Iterable<Map.Entry<LocalDate, V>> {
    private val byDay = HashMap<Long, Pair<LocalDate, V>>()

    fun getOrPut(day: LocalDate, make: () -> V): V = byDay.getOrPut(day.toEpochDay()) { day to make() }.second

    override fun iterator(): Iterator<Map.Entry<LocalDate, V>> =
        byDay.keys.sorted().map { k -> byDay.getValue(k).let { (day, value) -> Entry(day, value) } }.iterator()

    private class Entry<V>(override val key: LocalDate, override val value: V) : Map.Entry<LocalDate, V>
}

/** java.util's sorted set of days (sortedSetOf), as the export gathers the days to print: each once, in order. */
class DaySet : Iterable<LocalDate> {
    private val days = HashMap<Long, LocalDate>()

    operator fun plusAssign(day: LocalDate) {
        days.getOrPut(day.toEpochDay()) { day }
    }

    operator fun plusAssign(more: Iterable<LocalDate>) {
        more.forEach { plusAssign(it) }
    }

    override fun iterator(): Iterator<LocalDate> = days.keys.sorted().map { days.getValue(it) }.iterator()
}

/** What the export files write with String.format, as Java writes it in English (the iPhone's texts are English). */
object JavaFormat {
    /** "%.1f". */
    fun oneDecimal(x: Double): String = com.justdataplease.boringtravel.ios.JavaText.fixed(x, 1)

    /** "%.2f". */
    fun twoDecimals(x: Double): String = com.justdataplease.boringtravel.ios.JavaText.fixed(x, 2)

    /** "%.3f". */
    fun threeDecimals(x: Double): String = com.justdataplease.boringtravel.ios.JavaText.fixed(x, 3)

    /** "%.1f" for a Float, as Java widens it. */
    fun oneDecimal(x: Float): String = oneDecimal(x.toDouble())

    /** "%.3f" for a Float. */
    fun threeDecimals(x: Float): String = threeDecimals(x.toDouble())

    /** "%.1f" in [locale]: "18,4" in Greek ([javaNumber]). */
    fun oneDecimal(x: Double, locale: Locale): String = javaNumber(x, 1, grouped = false, locale = locale)

    /** "%.1f" of a Float in [locale], as Java widens it. */
    fun oneDecimal(x: Float, locale: Locale): String = oneDecimal(x.toDouble(), locale)

    /** "%.Nf" in [locale]. */
    fun fixed(x: Double, decimals: Int, locale: Locale): String = javaNumber(x, decimals, grouped = false, locale = locale)

    /** "%,.0f" in [locale]: "2.370" in Greek. */
    fun grouped(x: Double, locale: Locale): String = javaNumber(x, 0, grouped = true, locale = locale)

    /** "%,d" in [locale]. */
    fun groupedInt(n: Long, locale: Locale): String = javaGroupedInt(n, locale)

    /** "%,.0f": rounded half up, thousands with a comma. */
    fun grouped(x: Double): String {
        val whole = com.justdataplease.boringtravel.ios.JavaText.fixed(x, 0)
        val negative = whole.startsWith("-")
        val digits = whole.removePrefix("-")
        val out = digits.reversed().chunked(3).joinToString(",").reversed()
        return if (negative) "-$out" else out
    }

    /** "%02d". */
    fun twoDigits(n: Int): String = if (n < 0) "-" + (-n).toString().padStart(1, '0') else n.toString().padStart(2, '0')

    /** Java's String(Character.toChars(code)): a code point as one or two chars. */
    fun codePoint(cp: Int): String =
        if (cp < 0x10000) cp.toChar().toString() else charArrayOf((0xD800 + ((cp - 0x10000) shr 10)).toChar(), (0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar()).concatToString()

    /** Java's text.codePointAt(index): a surrogate pair as its one code point, a lone surrogate as itself. */
    fun codePointAt(text: String, index: Int): Int {
        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < text.length) {
            val low = text[index + 1]
            if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
        }
        return high.code
    }

    /** Java's Character.charCount(cp): 2 for a code point beyond the 16-bit plane, else 1. */
    fun charCount(cp: Int): Int = if (cp >= 0x10000) 2 else 1

    /** java.time.Month's name for month [month] (1–12): "JANUARY" … "DECEMBER". */
    fun monthName(month: Int): String = MONTHS[(month - 1).coerceIn(0, 11)]

    private val MONTHS = listOf(
        "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER",
    )
}

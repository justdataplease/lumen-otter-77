package com.justdataplease.boringtravel.time

/**
 * A calendar day, as the engine needs it. On Android and the desktop this *is* `java.time.LocalDate` (a typealias), so
 * the Android app's own code, its database and its tests are untouched by the engine. On iPhone it is the engine's own
 * small class (iosMain) on the same arithmetic ([CivilDay]).
 *
 * Only what both have is declared: the rest is written from these three here (see [civil]), never as a member call
 * that only java.time has. `toString()` is the ISO day ("2027-06-18") on both.
 */
expect class LocalDate {
    fun toEpochDay(): Long
    fun plusDays(daysToAdd: Long): LocalDate
    fun minusDays(daysToSubtract: Long): LocalDate
}

/** The day [year]-[month]-[day]; throws when there is no such day (30 February). */
expect fun localDate(year: Int, month: Int, day: Int): LocalDate

expect fun localDateOfEpochDay(epochDay: Long): LocalDate

/** "2027-06-18" as a day; null for anything that is not a real ISO day. */
expect fun parseLocalDateOrNull(iso: String): LocalDate?

/** The name of the phone's own time zone ("Europe/Athens"), as it is stored beside a journal entry. */
expect fun systemZoneId(): String

/** The calendar day it is at [epochMs] in the zone named [zoneId]; null, blank or unknown = the phone's own zone. */
expect fun localDateAt(epochMs: Long, zoneId: String?): LocalDate

/** The first moment of [day] in the zone named [zoneId] (as in [localDateAt]), in epoch milliseconds. */
expect fun startOfDayMs(day: LocalDate, zoneId: String?): Long

/** Now, in epoch milliseconds. */
expect fun nowMs(): Long

/** Today in the phone's own zone. */
fun today(): LocalDate = localDateAt(nowMs(), null)

/** Year, month, day and weekday of this day. */
val LocalDate.civil: CivilDay get() = CivilDay.ofEpochDay(toEpochDay())

/**
 * A day as year, month and day in the Gregorian calendar, counted the way java.time counts (day 0 is 1 January 1970,
 * the calendar runs backwards before 1582). The iPhone's [LocalDate] is built on this, and the date texts read it.
 */
data class CivilDay(val year: Int, val month: Int, val day: Int) {
    /** Days since 1 January 1970. */
    val epochDay: Long
        get() {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = y.floorDiv(400L)
            val yearOfEra = y - era * 400
            val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era * 146097 + dayOfEra - 719468
        }

    /** 1 = Monday … 7 = Sunday. */
    val dayOfWeek: Int get() = (epochDay + 3).mod(7L).toInt() + 1

    /** "2027-06-18", as java.time prints a day (a sign only outside the years 0–9999). */
    fun iso(): String {
        val y = when {
            year in 0..9999 -> year.toString().padStart(4, '0')
            year > 9999 -> "+$year"
            else -> "-" + (-year).toString().padStart(4, '0')
        }
        return y + "-" + month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
    }

    companion object {
        fun ofEpochDay(epochDay: Long): CivilDay {
            val z = epochDay + 719468
            val era = z.floorDiv(146097L)
            val dayOfEra = z - era * 146097
            val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val mp = (5 * dayOfYear + 2) / 153
            val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
            val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val year = (yearOfEra + era * 400 + (if (month <= 2) 1 else 0)).toInt()
            return CivilDay(year, month, day)
        }

        fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

        fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (isLeap(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        /** Whether there is such a day. */
        fun isValid(year: Int, month: Int, day: Int): Boolean = month in 1..12 && day in 1..lengthOfMonth(year, month)

        private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

        /** "2027-06-18" → that day; null for another shape or a day that doesn't exist (the years 0000–9999). */
        fun parse(iso: String): CivilDay? {
            val m = ISO.matchEntire(iso) ?: return null
            val (y, mo, d) = m.destructured
            return if (isValid(y.toInt(), mo.toInt(), d.toInt())) CivilDay(y.toInt(), mo.toInt(), d.toInt()) else null
        }
    }
}

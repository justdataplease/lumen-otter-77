package com.justdataplease.boringtravel.util

import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.time.CivilDay
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil

/**
 * Date texts used across the app: "Fri 18 Jun 2027", "17–26 Jun 2027", "May 2026". On Android (and the desktop) they
 * are in the phone's language, through java.time; on iPhone they are [PlainDates]' English names for now.
 */
expect object Dates {
    fun dayLong(d: LocalDate): String
    fun dayShort(d: LocalDate): String
    fun dayMonth(d: LocalDate): String
    fun dayMonthYear(d: LocalDate): String
    fun monthYear(d: LocalDate): String
    fun weekday(d: LocalDate): String

    /** Minutes after midnight as "07:30". */
    fun time(minutes: Int): String

    /** "12 days", "1 day". */
    fun days(n: Int): String

    /** "17–26 Jun 2027", "28 Jun – 3 Jul 2027", "30 Dec 2026 – 4 Jan 2027". */
    fun range(start: LocalDate, end: LocalDate): String

    /** One-line summary for trip rows: "Oct 2027 · 12 days". */
    fun tripSummary(trip: Trip): String
}

/**
 * The same texts with English names and no library: what the iPhone shows until it reads the phone's language, and
 * what the engine's tests compare (the Android texts in English are these, letter for letter).
 */
object PlainDates {
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun month(c: CivilDay) = MONTHS[c.month - 1]

    fun weekday(d: LocalDate): String = WEEKDAYS[d.civil.dayOfWeek - 1]
    fun dayLong(d: LocalDate): String = d.civil.let { "${weekday(d)} ${it.day} ${month(it)} ${it.year}" }
    fun dayShort(d: LocalDate): String = "${weekday(d)} ${d.civil.day}"
    fun dayMonth(d: LocalDate): String = d.civil.let { "${it.day} ${month(it)}" }
    fun dayMonthYear(d: LocalDate): String = d.civil.let { "${it.day} ${month(it)} ${it.year}" }
    fun monthYear(d: LocalDate): String = d.civil.let { "${month(it)} ${it.year}" }
    fun time(minutes: Int): String = (minutes / 60).toString().padStart(2, '0') + ":" + (minutes % 60).toString().padStart(2, '0')
    fun days(n: Int): String = if (n == 1) "1 day" else "$n days"

    fun range(start: LocalDate, end: LocalDate): String {
        val s = start.civil
        val e = end.civil
        return when {
            s.year != e.year -> "${dayMonthYear(start)} – ${dayMonthYear(end)}"
            s.month != e.month -> "${dayMonth(start)} – ${dayMonthYear(end)}"
            s == e -> dayMonthYear(start)
            else -> "${s.day}–${dayMonthYear(end)}"
        }
    }

    fun tripSummary(trip: Trip): String {
        val s = trip.startDate ?: return "Someday"
        val days = trip.days
        return if (days != null) "${monthYear(s)} · ${days(days)}" else monthYear(s)
    }
}

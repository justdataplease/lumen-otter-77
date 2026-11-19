package com.justdataplease.boringtravel.util

import com.justdataplease.boringtravel.data.db.Trip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date texts used across the app, in the phone's language: "Fri 18 Jun 2027", "17–26 Jun 2027", "May 2026". */
actual object Dates {
    private val locale: Locale get() = Locale.getDefault()
    private fun f(pattern: String) = DateTimeFormatter.ofPattern(pattern, locale)

    actual fun dayLong(d: LocalDate): String = f("EEE d MMM yyyy").format(d)
    actual fun dayShort(d: LocalDate): String = f("EEE d").format(d)
    actual fun dayMonth(d: LocalDate): String = f("d MMM").format(d)
    actual fun dayMonthYear(d: LocalDate): String = f("d MMM yyyy").format(d)
    actual fun monthYear(d: LocalDate): String = f("MMM yyyy").format(d)
    actual fun weekday(d: LocalDate): String = f("EEE").format(d)
    actual fun time(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** "12 days", "1 day". */
    actual fun days(n: Int): String = if (n == 1) "1 day" else "$n days"

    fun time(epochMs: Long, zone: String): String =
        f("HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())))

    fun localDate(epochMs: Long, zone: String): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())).toLocalDate()

    /** "17–26 Jun 2027", "28 Jun – 3 Jul 2027", "30 Dec 2026 – 4 Jan 2027". */
    actual fun range(start: LocalDate, end: LocalDate): String = when {
        start.year != end.year -> "${f("d MMM yyyy").format(start)} – ${f("d MMM yyyy").format(end)}"
        start.month != end.month -> "${f("d MMM").format(start)} – ${f("d MMM yyyy").format(end)}"
        start == end -> f("d MMM yyyy").format(start)
        else -> "${start.dayOfMonth}–${f("d MMM yyyy").format(end)}"
    }

    /** One-line summary for trip rows: "Oct 2027 · 12 days". */
    actual fun tripSummary(trip: Trip): String {
        val s = trip.startDate ?: return "Someday"
        val days = trip.days
        return if (days != null) "${monthYear(s)} · ${days(days)}" else monthYear(s)
    }
}

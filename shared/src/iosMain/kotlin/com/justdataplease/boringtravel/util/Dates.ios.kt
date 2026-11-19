package com.justdataplease.boringtravel.util

import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil

/**
 * iPhone: the date texts in the phone's language, as Android's Dates.java.kt writes them with java.time and
 * Locale.getDefault(): the same patterns through [PhoneLocale] (NSDateFormatter with the phone's first language),
 * "Παρ 18 Ιουν 2027" on a Greek phone, "Fri 18 Jun 2027" on an English (US) one. [time] and [days] are Android's own
 * (digits and English words on both phones).
 */
actual object Dates {
    private fun f(pattern: String, d: LocalDate): String = PhoneLocale.date(pattern, d.toEpochDay())

    actual fun dayLong(d: LocalDate): String = f("EEE d MMM yyyy", d)
    actual fun dayShort(d: LocalDate): String = f("EEE d", d)
    actual fun dayMonth(d: LocalDate): String = f("d MMM", d)
    actual fun dayMonthYear(d: LocalDate): String = f("d MMM yyyy", d)
    actual fun monthYear(d: LocalDate): String = f("MMM yyyy", d)
    actual fun weekday(d: LocalDate): String = f("EEE", d)
    actual fun time(minutes: Int): String = PlainDates.time(minutes)
    actual fun days(n: Int): String = PlainDates.days(n)

    /** "17–26 Jun 2027", "28 Jun – 3 Jul 2027", "30 Dec 2026 – 4 Jan 2027" (Android's rules, the phone's names). */
    actual fun range(start: LocalDate, end: LocalDate): String {
        val s = start.civil
        val e = end.civil
        return when {
            s.year != e.year -> "${f("d MMM yyyy", start)} – ${f("d MMM yyyy", end)}"
            s.month != e.month -> "${f("d MMM", start)} – ${f("d MMM yyyy", end)}"
            start == end -> f("d MMM yyyy", start)
            else -> "${s.day}–${f("d MMM yyyy", end)}"
        }
    }

    /** One-line summary for trip rows: "Oct 2027 · 12 days". */
    actual fun tripSummary(trip: Trip): String {
        val s = trip.startDate ?: return "Someday"
        val days = trip.days
        return if (days != null) "${monthYear(s)} · ${days(days)}" else monthYear(s)
    }
}

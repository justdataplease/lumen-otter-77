package com.justdataplease.boringtravel.time

import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.util.Dates
import com.justdataplease.boringtravel.util.PlainDates
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iPhone's calendar arithmetic and date texts against java.time, which is what the Android app runs on: every day
 * of eight centuries, and every text for three years. Runs on the desktop only (it needs java.time to compare with).
 */
class AgainstJavaTimeTest {
    @Test
    fun civilDayAgreesWithJavaTimeEveryDayFrom1600To2400() {
        var day = java.time.LocalDate.of(1600, 1, 1)
        val end = java.time.LocalDate.of(2400, 12, 31)
        var count = 0
        while (!day.isAfter(end)) {
            val civil = CivilDay.ofEpochDay(day.toEpochDay())
            if (civil.year != day.year || civil.month != day.monthValue || civil.day != day.dayOfMonth ||
                civil.dayOfWeek != day.dayOfWeek.value || civil.epochDay != day.toEpochDay() || civil.iso() != day.toString() ||
                CivilDay.lengthOfMonth(civil.year, civil.month) != day.lengthOfMonth()
            ) throw AssertionError("$day is $civil (${civil.iso()}, weekday ${civil.dayOfWeek})")
            day = day.plusDays(1)
            count++
        }
        assertEquals(292_560, count)
    }

    @Test
    fun plainDateTextsAreTheAndroidTextsInEnglish() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            var day = java.time.LocalDate.of(2026, 1, 1)
            val end = java.time.LocalDate.of(2028, 12, 31)
            while (!day.isAfter(end)) {
                assertEquals(Dates.dayLong(day), PlainDates.dayLong(day))
                assertEquals(Dates.dayShort(day), PlainDates.dayShort(day))
                assertEquals(Dates.dayMonth(day), PlainDates.dayMonth(day))
                assertEquals(Dates.dayMonthYear(day), PlainDates.dayMonthYear(day))
                assertEquals(Dates.monthYear(day), PlainDates.monthYear(day))
                assertEquals(Dates.weekday(day), PlainDates.weekday(day))
                for (length in listOf(0L, 1L, 9L, 40L, 400L)) assertEquals(Dates.range(day, day.plusDays(length)), PlainDates.range(day, day.plusDays(length)))
                val trip = Trip("t", "T", startDate = day, endDate = day.plusDays(11), createdAt = 1, updatedAt = 1)
                assertEquals(Dates.tripSummary(trip), PlainDates.tripSummary(trip))
                day = day.plusDays(1)
            }
            for (minutes in 0 until 24 * 60) assertEquals(Dates.time(minutes), PlainDates.time(minutes))
            for (n in 0..40) assertEquals(Dates.days(n), PlainDates.days(n))
            assertEquals(Dates.tripSummary(Trip("t", "T", createdAt = 1, updatedAt = 1)), PlainDates.tripSummary(Trip("t", "T", createdAt = 1, updatedAt = 1)))
        } finally {
            Locale.setDefault(before)
        }
    }
}

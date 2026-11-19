package com.justdataplease.boringtravel.tripcal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale

class TripGridTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun span(id: String, start: LocalDate, end: LocalDate) = TripSpan(id, id, start, end)
    private val nov = YearMonth.of(2026, 11)

    @Test fun firstWeekdayFollowsTheLocale() {
        assertEquals(DayOfWeek.MONDAY, WeekFields.of(Locale.UK).firstDayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, WeekFields.of(Locale.US).firstDayOfWeek)
    }

    @Test fun mondayWeeksFillTheEndsFromTheNeighbouringMonths() {
        // 1 Nov 2026 is a Sunday and the 30th a Monday: six rows, 26 Oct to 6 Dec.
        val weeks = TripGrid.weeks(nov, DayOfWeek.MONDAY)
        assertEquals(6, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(d(2026, 10, 26), weeks.first().first())
        assertEquals(d(2026, 11, 1), weeks.first().last())
        assertEquals(d(2026, 12, 6), weeks.last().last())
        assertTrue(weeks.all { it.first().dayOfWeek == DayOfWeek.MONDAY })
    }

    @Test fun sundayWeeksStartOnTheFirst() {
        val weeks = TripGrid.weeks(nov, DayOfWeek.SUNDAY)
        assertEquals(5, weeks.size)
        assertEquals(d(2026, 11, 1), weeks.first().first())
        assertEquals(d(2026, 12, 5), weeks.last().last())
    }

    @Test fun aMonthThatFitsHasFourRows() {
        // February 2027 starts on a Monday and has 28 days.
        val weeks = TripGrid.weeks(YearMonth.of(2027, 2), DayOfWeek.MONDAY)
        assertEquals(4, weeks.size)
        assertEquals(d(2027, 2, 1), weeks.first().first())
        assertEquals(d(2027, 2, 28), weeks.last().last())
    }

    @Test fun aTripAcrossTwoMonthsIsClippedToEach() {
        val trip = span("a", d(2026, 10, 28), d(2026, 11, 3))
        val novBars = TripGrid.month(nov, DayOfWeek.MONDAY, listOf(trip)).bars
        assertEquals(
            listOf(
                TripGrid.Bar("a", week = 0, from = 6, to = 6, lane = 0, opensBefore = true, runsOn = true),
                TripGrid.Bar("a", week = 1, from = 0, to = 1, lane = 0, opensBefore = true, runsOn = false),
            ),
            novBars,
        )
        val octBars = TripGrid.month(YearMonth.of(2026, 10), DayOfWeek.MONDAY, listOf(trip)).bars
        // 28 Oct is a Wednesday in the row of 26 Oct; the bar stops at the 31st (Saturday) and goes on.
        assertEquals(listOf(TripGrid.Bar("a", week = 4, from = 2, to = 5, lane = 0, opensBefore = false, runsOn = true)), octBars)
    }

    @Test fun aTripAcrossNewYear() {
        val trip = span("ny", d(2026, 12, 30), d(2027, 1, 2))
        val dec = TripGrid.month(YearMonth.of(2026, 12), DayOfWeek.MONDAY, listOf(trip))
        assertEquals(listOf(TripGrid.Bar("ny", week = 4, from = 2, to = 3, lane = 0, opensBefore = false, runsOn = true)), dec.bars)
        val jan = TripGrid.month(YearMonth.of(2027, 1), DayOfWeek.MONDAY, listOf(trip))
        assertEquals(listOf(TripGrid.Bar("ny", week = 0, from = 4, to = 5, lane = 0, opensBefore = true, runsOn = false)), jan.bars)
        assertEquals(d(2026, 12, 28), jan.weeks.first().first())
    }

    @Test fun overlappingTripsStackAndKeepTheirLane() {
        val a = span("a", d(2026, 11, 3), d(2026, 11, 12))
        val b = span("b", d(2026, 11, 10), d(2026, 11, 20))
        val c = span("c", d(2026, 11, 14), d(2026, 11, 16))
        val far = span("far", d(2027, 5, 1), d(2027, 5, 3))
        val layout = TripGrid.month(nov, DayOfWeek.MONDAY, listOf(c, b, far, a))
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 0), layout.lanes)
        assertEquals(2, layout.laneCount)
        assertTrue(layout.bars.filter { it.tripId == "b" }.all { it.lane == 1 })
        assertTrue(layout.bars.none { it.tripId == "far" })
        // The rows with b use two lanes; the last week has none.
        assertEquals(2, layout.lanesIn(2))
        assertEquals(0, layout.lanesIn(5))
        assertEquals(listOf("a", "b"), TripGrid.tripsOn(d(2026, 11, 11), listOf(c, b, a), layout.lanes).map { it.id })
        assertEquals(emptyList<String>(), TripGrid.tripsOn(d(2026, 11, 25), listOf(c, b, a), layout.lanes).map { it.id })
    }

    @Test fun longerTripsTakeTheTopLaneOnTheSameStart() {
        val short = span("s", d(2026, 11, 5), d(2026, 11, 6))
        val long = span("l", d(2026, 11, 5), d(2026, 11, 15))
        assertEquals(mapOf("l" to 0, "s" to 1), TripGrid.lanes(listOf(short, long), d(2026, 11, 1), d(2026, 11, 30)))
    }

    @Test fun monthIndexRoundTrips() {
        assertEquals(2026 * 12 + 10, TripGrid.index(nov))
        assertEquals(nov, TripGrid.monthOf(TripGrid.index(nov)))
        assertEquals(YearMonth.of(2026, 12), TripGrid.monthOf(TripGrid.index(YearMonth.of(2027, 1)) - 1))
        assertEquals(YearMonth.of(2027, 1), TripGrid.monthOf(TripGrid.index(YearMonth.of(2026, 12)) + 1))
    }
}

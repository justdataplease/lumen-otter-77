package com.justdataplease.boringtravel.widget

import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class WidgetTextTest {
    private val today = LocalDate.of(2026, 6, 19)

    private fun trip(title: String, start: LocalDate?, end: LocalDate?, id: String = title, deleted: Boolean = false) =
        Trip(id = id, title = title, startDate = start, endDate = end, createdAt = 0, updatedAt = 0, deletedAt = if (deleted) 1L else null)

    private val cyclades = trip("Cyclades", today.minusDays(3), today.plusDays(5))
    private fun info(trip: Trip? = null, place: String? = null, next: Trip? = null, wrote: Int = 0) =
        TodayInfo(today, Look.StickerBook, trip, place, next, wrote)

    private val date get() = "${Dates.weekday(today)} ${Dates.dayMonth(today)}"

    @Test
    fun tripDayWithPlace() {
        assertEquals(ContextLine("Day 4 · Naxos", "Cyclades"), WidgetText.context(info(cyclades, "Naxos")))
    }

    @Test
    fun tripDayWithoutPlaceCountsTheDays() {
        assertEquals(ContextLine("Day 4 of 9", "Cyclades"), WidgetText.context(info(cyclades)))
        assertEquals(ContextLine("Day 4 of 9", "Cyclades"), WidgetText.context(info(cyclades, "  ")))
    }

    @Test
    fun placeNamedLikeTheTripIsntRepeated() {
        val naxos = trip("Naxos", today.minusDays(1), today.plusDays(2))
        assertEquals(ContextLine("Day 2 of 4", "Naxos"), WidgetText.context(info(naxos, "naxos")))
    }

    @Test
    fun oneDayTripIsToday() {
        val hydra = trip("Hydra", today, today)
        assertEquals(ContextLine("Today · Hydra port", "Hydra"), WidgetText.context(info(hydra, "Hydra port")))
        assertEquals(ContextLine("Today", "Hydra"), WidgetText.context(info(hydra)))
    }

    @Test
    fun noTripShowsTheDate() {
        assertEquals(ContextLine("No trip today", date), WidgetText.context(info()))
    }

    @Test
    fun noTripNamesTheNextOneWhenSoon() {
        val japan = trip("Kyoto & Osaka", today.plusDays(12), today.plusDays(20))
        assertEquals(ContextLine("No trip today", "Kyoto & Osaka in 12 days"), WidgetText.context(info(next = japan)))
        val tomorrow = trip("Lisbon", today.plusDays(1), today.plusDays(4))
        assertEquals(ContextLine("No trip today", "Lisbon tomorrow"), WidgetText.context(info(next = tomorrow)))
    }

    @Test
    fun aNextTripFarOffIsLeftOut() {
        val far = trip("Patagonia", today.plusDays(WidgetText.NEXT_TRIP_DAYS + 1L), today.plusDays(90))
        assertEquals(ContextLine("No trip today", date), WidgetText.context(info(next = far)))
        val edge = trip("Patagonia", today.plusDays(WidgetText.NEXT_TRIP_DAYS.toLong()), today.plusDays(90))
        assertEquals("Patagonia in ${WidgetText.NEXT_TRIP_DAYS} days", WidgetText.context(info(next = edge)).secondary)
    }

    @Test
    fun nextTripIsTheSoonestDatedOne() {
        val soon = trip("Soon", today.plusDays(3), today.plusDays(5))
        val later = trip("Later", today.plusDays(30), today.plusDays(35))
        val past = trip("Past", today.minusDays(30), today.minusDays(25))
        val someday = trip("Someday", null, null)
        val gone = trip("Gone", today.plusDays(1), today.plusDays(2), deleted = true)
        val now = trip("Now", today, today.plusDays(1))
        assertEquals(soon, WidgetText.nextTrip(listOf(later, past, someday, gone, soon, now), today))
        assertNull(WidgetText.nextTrip(listOf(past, someday, now), today))
    }

    @Test
    fun todayWidgetLine() {
        assertEquals("Day 4 of 9 · 2 written today", WidgetText.todayLine(info(cyclades, wrote = 2)))
        assertEquals("No trip today · ${Dates.dayLong(today)}", WidgetText.todayLine(info()))
    }
}

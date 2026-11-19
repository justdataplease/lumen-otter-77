package com.justdataplease.boringtravel.ui.trips

import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ui.trips.TripCountdown.Count
import com.justdataplease.boringtravel.ui.trips.TripCountdown.Phase
import com.justdataplease.boringtravel.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TripCountdownTest {
    private val today = LocalDate.of(2026, 11, 7)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test fun theDemoReadsAsOnTheMockups() {
        // Argentina on now, day 14 of 31.
        val argentina = Trip("arg", "Argentina & Patagonia", startDate = d(2026, 10, 25), endDate = d(2026, 11, 24), createdAt = 1, updatedAt = 1)
        assertEquals(14, argentina.dayNumber(today))
        assertEquals(31, argentina.days)
        assertEquals(Count(Phase.Now, 17, "days left"), TripCountdown.of(argentina, today))
        assertEquals(14f / 31f, TripCountdown.progress(argentina.startDate, argentina.endDate, today), 1e-6f)
        // Kyoto next year: 330 days to go.
        assertEquals(Count(Phase.Next, 330, "days to go"), TripCountdown.of(d(2027, 10, 3), d(2027, 10, 14), today))
        // Lisbon in May: 6 months ago.
        assertEquals(Count(Phase.Past, 6, "months ago"), TripCountdown.of(d(2026, 5, 9), d(2026, 5, 17), today))
    }

    @Test fun aTripOnNowCountsTheDaysAfterToday() {
        assertEquals(Count(Phase.Now, 2, "days left"), TripCountdown.of(d(2026, 11, 1), d(2026, 11, 9), today))
        assertEquals(Count(Phase.Now, 1, "day left"), TripCountdown.of(d(2026, 11, 1), d(2026, 11, 8), today))
        assertEquals(Count(Phase.Now, null, "Last day"), TripCountdown.of(d(2026, 11, 1), today, today))
        // First day of a one-day trip, and a start with no end (one day, as on the Calendar).
        assertEquals(Count(Phase.Now, null, "Last day"), TripCountdown.of(today, today, today))
        assertEquals(Count(Phase.Now, null, "Last day"), TripCountdown.of(today, null, today))
        // An end before the start is read as one day too.
        assertEquals(Count(Phase.Now, null, "Last day"), TripCountdown.of(today, d(2026, 11, 1), today))
    }

    @Test fun aTripAheadCountsTheDaysToGo() {
        assertEquals(Count(Phase.Next, null, "Tomorrow"), TripCountdown.of(d(2026, 11, 8), d(2026, 11, 20), today))
        assertEquals(Count(Phase.Next, 2, "days to go"), TripCountdown.of(d(2026, 11, 9), null, today))
        assertEquals(Count(Phase.Next, 1000, "days to go"), TripCountdown.of(today.plusDays(1000), today.plusDays(1010), today))
    }

    @Test fun aTripWithoutDatesInvitesThem() {
        assertEquals(Count(Phase.Someday, null, "Add dates"), TripCountdown.of(null, null, today))
        assertEquals(Count(Phase.Someday, null, "Add dates"), TripCountdown.of(Trip("x", "Someday", createdAt = 1, updatedAt = 1), today))
        assertEquals(0f, TripCountdown.progress(null, null, today), 0f)
    }

    @Test fun pastTripsReadInTheUnitThatFits() {
        fun ago(days: Long) = TripCountdown.ago(days).let { it.number to it.unit }
        assertEquals(null to "Yesterday", ago(1))
        assertEquals(2L to "days ago", ago(2))
        assertEquals(13L to "days ago", ago(13))
        assertEquals(2L to "weeks ago", ago(14))
        assertEquals(3L to "weeks ago", ago(27))
        assertEquals(8L to "weeks ago", ago(59))
        assertEquals(2L to "months ago", ago(60))
        assertEquals(11L to "months ago", ago(340))
        assertEquals(1L to "year ago", ago(365))
        assertEquals(1L to "year ago", ago(500))
        assertEquals(2L to "years ago", ago(600))
        assertEquals(10L to "years ago", ago(3653))
        // Never "0 days".
        assertEquals(null to "Yesterday", ago(0))
        assertEquals(Phase.Past, TripCountdown.ago(40).phase)
        // From the trip's last day.
        assertEquals(Count(Phase.Past, null, "Yesterday"), TripCountdown.of(d(2026, 11, 1), d(2026, 11, 6), today))
        assertEquals(Count(Phase.Past, 3, "weeks ago"), TripCountdown.of(d(2026, 10, 10), d(2026, 10, 17), today))
    }

    @Test fun progressRunsFromTheFirstDayToTheLast() {
        val start = d(2026, 11, 1)
        val end = d(2026, 11, 10)
        assertEquals(0f, TripCountdown.progress(start, end, d(2026, 10, 30)), 0f)
        assertEquals(0.1f, TripCountdown.progress(start, end, start), 1e-6f)
        assertEquals(0.7f, TripCountdown.progress(start, end, today), 1e-6f)
        assertEquals(1f, TripCountdown.progress(start, end, end), 0f)
        assertEquals(1f, TripCountdown.progress(start, end, d(2026, 12, 1)), 0f)
        assertEquals(1f, TripCountdown.progress(today, null, today), 0f)
    }

    @Test fun countLinesSaySingularForOneAndNothingForNone() {
        assertEquals("68 places planned", TripCountdown.countLine(68, past = false))
        assertEquals("1 place planned", TripCountdown.countLine(1, past = false))
        assertEquals("68 places", TripCountdown.countLine(68, past = false, short = true))
        assertEquals("1 place", TripCountdown.countLine(1, past = false, short = true))
        assertEquals("8 entries", TripCountdown.countLine(8, past = true))
        assertEquals("1 entry", TripCountdown.countLine(1, past = true, short = true))
        assertNull(TripCountdown.countLine(0, past = false))
        assertNull(TripCountdown.countLine(null, past = true))
        assertEquals("12 days", Dates.days(12))
        assertEquals("1 day", Dates.days(1))
    }
}

package com.justdataplease.boringtravel.ui.trips

import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.stats.TripStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HomeStatsTest {
    private val today = LocalDate.of(2027, 3, 14)
    private fun trip(id: String, start: LocalDate?, days: Long = 0, deleted: Boolean = false) = Trip(
        id, id, startDate = start, endDate = start?.plusDays(days - 1), createdAt = 1, updatedAt = 1, deletedAt = if (deleted) 2 else null,
    )

    private val argentina = trip("argentina", LocalDate.of(2027, 3, 1), 31)
    private val kyoto = trip("kyoto", LocalDate.of(2027, 10, 4), 12)
    private val lisbon = trip("lisbon", LocalDate.of(2026, 5, 2), 9)
    private val someday = trip("someday", null)
    private val gone = trip("gone", LocalDate.of(2025, 1, 1), 5, deleted = true)
    private val all = listOf(kyoto, someday, argentina, gone, lisbon)

    @Test fun daysAwayCountOnlyDatedLiveTrips() {
        val days = TripStats.daysAway(all.filter { it.deletedAt == null }, today)
        assertEquals(9 + 14, days)
    }

    @Test fun sectionsSplitNowNextSomedayAndPast() {
        val s = HomeStats.sections(all, today)
        assertEquals("argentina", s.now?.id)
        assertEquals(listOf("kyoto"), s.next.map { it.id })
        assertEquals(listOf("someday"), s.someday.map { it.id })
        assertEquals(listOf("lisbon"), s.past.map { it.id })
        val quiet = HomeStats.sections(listOf(kyoto, lisbon), today)
        assertNull(quiet.now)
    }
}

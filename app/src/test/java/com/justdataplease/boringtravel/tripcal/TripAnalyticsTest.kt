package com.justdataplease.boringtravel.tripcal

import com.justdataplease.boringtravel.data.db.Trip
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TripAnalyticsTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun span(
        id: String,
        start: LocalDate,
        end: LocalDate,
        countries: List<String> = emptyList(),
        days: Map<LocalDate, List<String>> = emptyMap(),
    ) = TripSpan(id, id, start, end, countries, days)

    // ---- spans from trips

    @Test fun tripsWithoutDatesAreListedApart() {
        fun trip(id: String, s: LocalDate?, e: LocalDate?, deleted: Boolean = false) =
            Trip(id, id, "", s, e, createdAt = 1, updatedAt = 1, deletedAt = if (deleted) 2 else null)
        val split = TripSpan.split(
            listOf(
                trip("later", d(2027, 3, 1), d(2027, 3, 4)),
                trip("someday", null, null),
                trip("one-day", d(2026, 5, 2), null),
                trip("backwards", d(2026, 6, 10), d(2026, 6, 1)),
                trip("gone", d(2026, 1, 1), d(2026, 1, 2), deleted = true),
            ),
            countries = mapOf("later" to listOf("JP")),
            dayCountries = mapOf("later" to mapOf(d(2027, 3, 2) to listOf("JP"))),
        )
        assertEquals(listOf("one-day", "backwards", "later"), split.spans.map { it.id })
        assertEquals(listOf("someday"), split.undated.map { it.id })
        assertEquals(1, split.spans[0].days)
        assertEquals(1, split.spans[1].days)
        assertEquals(4, split.spans[2].days)
        assertEquals(listOf("JP"), split.spans[2].countries)
        assertEquals(listOf("JP"), split.spans[2].dayCountries[d(2027, 3, 2)])
    }

    // ---- each day's countries

    @Test fun withoutDayPlacesTheTripsCountriesShareItsDays() {
        val trip = span("iberia", d(2026, 3, 1), d(2026, 3, 5), countries = listOf("PT", "ES"))
        assertEquals(listOf(listOf("PT"), listOf("PT"), listOf("PT"), listOf("ES"), listOf("ES")), TripDays.countries(trip))
        // Two countries over four days: half each; three over one day: all on it.
        assertEquals(listOf(listOf("A"), listOf("A"), listOf("B"), listOf("B")), TripDays.spread(listOf("A", "B"), 4))
        assertEquals(listOf(listOf("A", "B", "C")), TripDays.spread(listOf("A", "B", "C"), 1))
    }

    @Test fun dayPlacesWinAndCarryOver() {
        val trip = span(
            "iberia", d(2026, 3, 1), d(2026, 3, 5), countries = listOf("PT", "ES"),
            days = mapOf(
                d(2026, 3, 1) to listOf("PT"),
                d(2026, 3, 3) to listOf("PT", "ES"), // crossed the border that day: the night is in Spain
                d(2026, 4, 1) to listOf("FR"), // outside the trip: ignored
            ),
        )
        assertEquals(listOf(listOf("PT"), listOf("PT"), listOf("PT", "ES"), listOf("ES"), listOf("ES")), TripDays.countries(trip))
    }

    @Test fun daysBeforeTheFirstKnownDayTakeItsFirstCountry() {
        val trip = span("jp", d(2026, 3, 1), d(2026, 3, 4), days = mapOf(d(2026, 3, 3) to listOf("JP", "KR")))
        assertEquals(listOf(listOf("JP"), listOf("JP"), listOf("JP", "KR"), listOf("KR")), TripDays.countries(trip))
    }

    // ---- overlaps

    @Test fun overlappingTripsAreFlaggedButHandOversAreNot() {
        val a = span("a", d(2026, 5, 1), d(2026, 5, 5))
        val b = span("b", d(2026, 5, 4), d(2026, 5, 8))
        val c = span("c", d(2026, 5, 8), d(2026, 5, 9)) // starts the day b ends: a hand-over
        val inside = span("inside", d(2026, 6, 3), d(2026, 6, 3))
        val around = span("around", d(2026, 6, 1), d(2026, 6, 10))
        val overlaps = TripAnalytics.overlaps(listOf(c, inside, b, around, a))
        assertEquals(listOf("a+b", "around+inside"), overlaps.map { "${it.first.id}+${it.second.id}" })
        assertEquals(d(2026, 5, 4), overlaps[0].from)
        assertEquals(d(2026, 5, 5), overlaps[0].to)
        assertEquals(d(2026, 6, 3), overlaps[1].from)
        assertEquals(d(2026, 6, 3), overlaps[1].to)
    }
}

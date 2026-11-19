package com.justdataplease.boringtravel.stats

import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.places.Geo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TripStatsTest {
    private val today = LocalDate.of(2027, 6, 20)

    private fun trip(id: String, start: LocalDate?, end: LocalDate?, deleted: Boolean = false) =
        Trip(id, id, startDate = start, endDate = end, createdAt = 0, updatedAt = 0, deletedAt = if (deleted) 1L else null)

    private fun point(minute: Long, lat: Double, lng: Double, trip: String = "t") = TrackPoint(tripId = trip, time = minute * 60_000L, lat = lat, lng = lng)

    @Test fun daysAwayCountsPastTripsAndDaysSoFarOfTheCurrentOne() {
        val trips = listOf(
            trip("past", LocalDate.of(2027, 5, 9), LocalDate.of(2027, 5, 17)), // 9 days
            trip("now", LocalDate.of(2027, 6, 17), LocalDate.of(2027, 6, 26)), // day 4 of 10
            trip("next", LocalDate.of(2027, 10, 3), LocalDate.of(2027, 10, 14)),
            trip("someday", null, null),
            trip("gone", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 30), deleted = true),
        )
        assertEquals(13, TripStats.daysAway(trips, today))
    }

    @Test fun metersFollowThePointsInTimeOrder() {
        // Three points about 1.1 km apart along a meridian, ten minutes apart, given out of order.
        val pts = listOf(point(20, 37.02, 25.0), point(0, 37.0, 25.0), point(10, 37.01, 25.0))
        val expected = Geo.distanceM(37.0, 25.0, 37.01, 25.0) * 2
        assertEquals(expected, TripStats.meters(pts), 0.5)
    }

    @Test fun jumpsLongerThan50KmAreSkipped() {
        // Walk 1.1 km, then a 60 km gap over two days (slow but too long), then walk 1.1 km more.
        val pts = listOf(
            point(0, 37.0, 25.0), point(10, 37.01, 25.0),
            point(3_000, 37.55, 25.0), point(3_010, 37.56, 25.0),
        )
        val walk = Geo.distanceM(37.0, 25.0, 37.01, 25.0) + Geo.distanceM(37.55, 25.0, 37.56, 25.0)
        assertEquals(walk, TripStats.meters(pts), 0.5)
    }

    @Test fun jumpsFasterThan300KmhAreSkipped() {
        // 40 km in 5 minutes is 480 km/h: a flight or a bad fix.
        val pts = listOf(point(0, 37.0, 25.0), point(5, 37.36, 25.0), point(15, 37.37, 25.0))
        assertEquals(Geo.distanceM(37.36, 25.0, 37.37, 25.0), TripStats.meters(pts), 0.5)
        // The same 40 km in an hour (40 km/h, a ferry) counts.
        val ferry = listOf(point(0, 37.0, 25.0), point(60, 37.36, 25.0))
        assertEquals(Geo.distanceM(37.0, 25.0, 37.36, 25.0), TripStats.meters(ferry), 0.5)
    }

    @Test fun samePointTwiceAddsNothingAndSameTimeJumpIsSkipped() {
        val pts = listOf(point(0, 37.0, 25.0), point(0, 37.0, 25.0), point(0, 37.01, 25.0))
        assertEquals(0.0, TripStats.meters(pts), 0.0)
        assertEquals(0.0, TripStats.meters(listOf(point(0, 37.0, 25.0))), 0.0)
    }

    @Test fun haversineMatchesAKnownDistance() {
        // Parikia (Paros) to Naxos port: about 20.4 km.
        val d = Geo.distanceM(37.0853, 25.1488, 37.1056, 25.3747)
        assertEquals(20_200.0, d, 400.0)
    }
}

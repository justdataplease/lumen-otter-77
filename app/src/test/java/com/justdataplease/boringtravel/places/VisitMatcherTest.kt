package com.justdataplease.boringtravel.places

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class VisitMatcherTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val day4 = LocalDate.of(2027, 6, 20)
    private val halki = 37.0587 to 25.4807
    private val portara = 37.1111 to 25.3721

    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun point(time: Long, lat: Double, lng: Double) = TrackPoint(tripId = "t", time = time, lat = lat, lng = lng)
    private fun item(id: String, day: LocalDate?, place: Pair<Double, Double>?, done: Boolean = false) = PlanItem(
        id, "t", day, 1.0, title = id, lat = place?.first, lng = place?.second, done = done, createdAt = 0, updatedAt = 0,
    )

    // The bus up to Halki on day 4, ending about 50 m from the square.
    private val route = listOf(
        point(at(day4, 11, 40), 37.0990, 25.3730),
        point(at(day4, 12, 0), 37.0800, 25.4300),
        point(at(day4, 12, 20), 37.0590, 25.4802),
    )

    @Test fun itemOnTheRouteThatDayIsVisited() {
        val items = listOf(item("lunch", day4, halki), item("portara", day4, portara))
        assertEquals(setOf("lunch"), VisitMatcher.visited(items, route, zone))
    }

    @Test fun samePlaceOnAnotherDayDoesNotCount() {
        val items = listOf(item("lunch", day4.plusDays(1), halki))
        assertTrue(VisitMatcher.visited(items, route, zone).isEmpty())
    }

    @Test fun moreThan200MetresAwayDoesNotCount() {
        // About 330 m north of the last point.
        val items = listOf(item("near", day4, 37.0620 to 25.4802))
        assertTrue(VisitMatcher.visited(items, route, zone).isEmpty())
        assertEquals(setOf("near"), VisitMatcher.visited(items, route, zone, radiusM = 400.0))
    }

    @Test fun doneIdeasAndUnplacedItemsAreLeftAlone() {
        val items = listOf(
            item("done", day4, halki, done = true),
            item("idea", null, halki),
            item("noPlace", day4, null),
        )
        assertTrue(VisitMatcher.visited(items, route, zone).isEmpty())
    }

    @Test fun theDayIsTheDeviceZoneDay() {
        // 23:30 in Athens on day 4 is 20:30 UTC: the same calendar day only in Athens.
        val late = listOf(point(at(day4, 23, 30), halki.first, halki.second))
        val items = listOf(item("lunch", day4, halki))
        assertEquals(setOf("lunch"), VisitMatcher.visited(items, late, zone))
        assertTrue(VisitMatcher.visited(items, late, ZoneId.of("Asia/Tokyo")).isEmpty())
    }
}

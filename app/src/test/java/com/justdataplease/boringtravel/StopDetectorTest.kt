package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.tracking.StopDetector
import com.justdataplease.boringtravel.tracking.StopSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopDetectorTest {
    private val t0 = 1_780_000_000_000L
    private val min = 60_000L
    private val halki = 37.0587 to 25.4807
    private val port = 37.1056 to 25.3747

    private fun pt(minute: Long, p: Pair<Double, Double>, northM: Double = 0.0) =
        TrackPoint(tripId = "t", time = t0 + minute * min, lat = p.first + northM / Geo.METERS_PER_DEGREE, lng = p.second)

    private fun stop(id: String, from: Long, to: Long?, p: Pair<Double, Double>, source: String = StopSource.AUTO, deleted: Boolean = false, name: String = "") =
        Stop(id, "t", t0 + from * min, to?.let { t0 + it * min }, p.first, p.second, name = name, source = source,
            createdAt = t0, updatedAt = t0, deletedAt = if (deleted) t0 else null)

    @Test fun denseStayIsFound() {
        // Every 2 minutes at Halki for an hour (jitter within 40 m), then the bus away.
        val stay = (0..30).map { pt(it * 2L, halki, northM = if (it % 2 == 0) 20.0 else -20.0) }
        val away = (1..5).map { pt(60L + it * 2, halki, northM = it * 800.0) }
        val d = StopDetector.detect(stay + away)
        assertEquals(1, d.size)
        assertEquals(t0, d[0].arrivedAt)
        assertTrue(d[0].leftAt!! >= t0 + 60 * min)
        assertEquals(halki.first, d[0].lat, 0.0005)
    }

    @Test fun shortPauseIsNotAStop() {
        val pts = (0..5).map { pt(it * 2L, halki) } + pt(12, halki, northM = 2_000.0)
        assertTrue(StopDetector.detect(pts).isEmpty())
    }

    @Test fun sparseOvernightStayWithLowPoints() {
        // Low sends nothing while the phone stays put: a point at 20:00, the next at 08:30 three km away.
        val pts = listOf(pt(0, port), pt(12 * 60 + 30, port, northM = 3_000.0))
        val d = StopDetector.detect(pts)
        assertEquals(1, d.size)
        assertEquals(t0, d[0].arrivedAt)
        val travel = (3_000 / 8.3 * 1000).toLong()
        assertEquals((t0 + (12 * 60 + 30) * min - travel).toDouble(), d[0].leftAt!!.toDouble(), 2_000.0)
    }

    @Test fun ongoingStayStaysOpen() {
        val pts = listOf(pt(0, port, northM = 3_000.0), pt(30, halki), pt(32, halki))
        val d = StopDetector.detect(pts, ongoingUntil = t0 + 80 * min)
        assertEquals(1, d.size)
        assertNull(d[0].leftAt)
        assertEquals(t0 + 30 * min, d[0].arrivedAt)
        // Not recording any more: the stay closes at its last point and is too short.
        assertTrue(StopDetector.detect(pts).isEmpty())
    }

    @Test fun strayFixAndShortWalkDoNotSplitAStay() {
        val pts = (0..10).map { pt(it * 3L, halki) }.toMutableList()
        pts[5] = pt(15, halki, northM = 500.0) // one stray fix
        val d = StopDetector.detect(pts + pt(40, halki, northM = 5_000.0))
        assertEquals(1, d.size)
        // Two stays at the same café split by a ten-minute walk become one.
        val a = (0..10).map { pt(it * 3L, halki) }
        val walk = listOf(pt(33, halki, northM = 400.0), pt(36, halki, northM = 300.0))
        val b = (0..10).map { pt(40 + it * 3L, halki) }
        val joined = StopDetector.detect(a + walk + b + pt(80, halki, northM = 5_000.0))
        assertEquals(1, joined.size)
        assertEquals(t0, joined[0].arrivedAt)
    }

    @Test fun mergeAddsNewSuggestions() {
        val dwell = StopDetector.Dwell(halki.first, halki.second, t0, t0 + 60 * min, 30)
        val out = StopDetector.merge(emptyList(), listOf(dwell), "t", t0, { "new" })
        assertEquals(1, out.size)
        assertEquals("new", out[0].id)
        assertEquals(StopSource.AUTO, out[0].source)
        assertEquals("t", out[0].tripId)
    }

    @Test fun mergeNeverTouchesTheTravellersStops() {
        val dwell = StopDetector.Dwell(halki.first, halki.second, t0, t0 + 60 * min, 30)
        // Their own stop for lunch there (no end time) covers it.
        val own = stop("own", 30, null, halki, source = StopSource.MANUAL, name = "Lunch")
        assertTrue(StopDetector.merge(listOf(own), listOf(dwell), "t", t0, { "new" }).isEmpty())
        // A suggestion they deleted is not suggested again.
        val gone = stop("gone", 0, 50, halki, deleted = true)
        assertTrue(StopDetector.merge(listOf(gone), listOf(dwell), "t", t0, { "new" }).isEmpty())
        // A manual stop far away (another island) doesn't cover it.
        val far = stop("far", 30, null, port, source = StopSource.MANUAL)
        assertEquals(1, StopDetector.merge(listOf(far), listOf(dwell), "t", t0, { "new" }).size)
    }

    @Test fun mergeUpdatesAnEarlierSuggestionInPlace() {
        val earlier = stop("s1", 0, null, halki, name = "Halki")
        val dwell = StopDetector.Dwell(halki.first, halki.second, t0, t0 + 90 * min, 40)
        val out = StopDetector.merge(listOf(earlier), listOf(dwell), "t", t0 + 100 * min, { "new" })
        assertEquals(1, out.size)
        assertEquals("s1", out[0].id)
        assertEquals("Halki", out[0].name)
        assertEquals(t0 + 90 * min, out[0].leftAt)
        // Nothing changed: nothing to save.
        assertTrue(StopDetector.merge(out, listOf(dwell), "t", t0 + 200 * min, { "new" }).isEmpty())
    }
}

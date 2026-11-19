package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.tracking.SpikeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeFilterTest {
    private val t0 = 1_780_000_000_000L
    private fun pt(seconds: Long, lat: Double, lng: Double, acc: Float? = 10f) =
        TrackPoint(tripId = "t", time = t0 + seconds * 1000, lat = lat, lng = lng, accuracyM = acc)

    /** A walk east along 37.1 N, 20 m every 15 s. */
    private fun walk(n: Int, from: Long = 0) = (0 until n).map { i ->
        pt(from + i * 15L, 37.1, 25.37 + i * 20 / (Geo.METERS_PER_DEGREE * Math.cos(Math.toRadians(37.1))))
    }

    @Test fun gpsJumpInAWalkIsDropped() {
        val w = walk(10).toMutableList()
        val spike = w[5].copy(lat = w[5].lat + 400 / Geo.METERS_PER_DEGREE)
        w[5] = spike
        val out = SpikeFilter.filter(w)
        assertFalse(spike in out)
        assertEquals(9, out.size)
    }

    @Test fun inaccurateJumpIsDroppedEvenWhenSlow() {
        // Balanced: a point every 10 min, one network fix 1.2 km off with 600 m accuracy.
        val pts = listOf(
            pt(0, 37.1, 25.37, 15f), pt(600, 37.1005, 25.371, 15f),
            pt(1200, 37.1005 + 1_200 / Geo.METERS_PER_DEGREE, 25.371, 600f),
            pt(1800, 37.101, 25.372, 15f), pt(2400, 37.1015, 25.373, 15f),
        )
        val out = SpikeFilter.filter(pts)
        assertEquals(4, out.size)
        assertFalse(pts[2] in out)
    }

    @Test fun veryPoorAccuracyIsDropped() {
        val w = walk(5).toMutableList()
        w[2] = w[2].copy(accuracyM = 1_500f)
        assertEquals(4, SpikeFilter.filter(w).size)
    }

    @Test fun slowOutAndBackWalkIsKept() {
        // To the end of a pier and back: 200 m out in 3 minutes, back in 3 minutes.
        val pts = listOf(
            pt(0, 37.1, 25.37), pt(180, 37.1 + 200 / Geo.METERS_PER_DEGREE, 25.37), pt(360, 37.1 + 5 / Geo.METERS_PER_DEGREE, 25.37),
        )
        assertEquals(pts, SpikeFilter.filter(pts))
    }

    @Test fun flightIsKept() {
        // Athens airport, a fix over the Adriatic, Paris CDG, then the train into Paris.
        val pts = listOf(
            pt(0, 37.936, 23.944), pt(300, 37.94, 23.95),
            pt(5_400, 43.5, 15.5),
            pt(11_100, 49.0, 2.55), pt(11_700, 48.95, 2.45), pt(13_500, 48.87, 2.36),
        )
        assertEquals(pts, SpikeFilter.filter(pts))
    }

    @Test fun fastDetourIsDroppedButStraightFastTrainKept() {
        // Two minutes "in Istanbul" between two fixes in Athens: faster than a plane both ways.
        val athens = listOf(pt(0, 37.98, 23.72), pt(120, 41.01, 28.97), pt(240, 37.981, 23.721))
        assertEquals(listOf(athens[0], athens[2]), SpikeFilter.filter(athens))
        // A 300 km/h train: fast but on the line from A to C.
        val train = (0..5).map { i -> pt(i * 15L, 40.0, 3.0 + i * 1_250 / (Geo.METERS_PER_DEGREE * Math.cos(Math.toRadians(40.0)))) }
        assertEquals(train, SpikeFilter.filter(train))
    }

    @Test fun badFirstFixIsDropped() {
        val madrid = pt(0, 40.42, -3.70)
        val naxos = (1..5).map { i -> pt(60L * i, 37.1 + i * 0.0002, 25.37) }
        val out = SpikeFilter.filter(listOf(madrid) + naxos)
        assertEquals(naxos, out)
    }

    @Test fun duplicatesAndEmptyInput() {
        val a = pt(0, 37.1, 25.37)
        assertEquals(listOf(a), SpikeFilter.filter(listOf(a, a.copy(id = 2))))
        assertTrue(SpikeFilter.filter(emptyList()).isEmpty())
        // Unsorted input comes back in time order.
        val w = walk(4)
        assertEquals(w, SpikeFilter.filter(w.reversed()))
    }
}

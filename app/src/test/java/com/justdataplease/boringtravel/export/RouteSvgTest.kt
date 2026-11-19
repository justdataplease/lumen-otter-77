package com.justdataplease.boringtravel.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class RouteSvgTest {
    private fun close(a: Double, b: Double, eps: Double = 1e-6) = abs(a - b) < eps

    @Test fun projectionFitsTheBoxAndKeepsProportions() {
        // 1° of longitude at 60°N is half as wide as 1° of latitude.
        val pts = listOf(GeoPoint(60.0, 10.0), GeoPoint(61.0, 12.0))
        val p = RouteSvg.project(pts, 200.0, 200.0, 10.0)
        val w = abs(p[1].x - p[0].x)
        val h = abs(p[1].y - p[0].y)
        val k = cos(Math.toRadians(60.5))
        assertTrue(close(w / h, 2 * k, 1e-9))
        p.forEach { assertTrue(it.x in 10.0..190.0 && it.y in 10.0..190.0) }
        // The longer side (north–south here) touches the padding; the shape is centred on the other axis.
        assertTrue(close(minOf(p[0].y, p[1].y), 10.0) && close(maxOf(p[0].y, p[1].y), 190.0))
        assertTrue(close((p[0].x + p[1].x) / 2, 100.0))
    }

    @Test fun northIsUp() {
        val p = RouteSvg.project(listOf(GeoPoint(37.0, 25.0), GeoPoint(38.0, 25.0)), 100.0, 100.0, 0.0)
        assertTrue(p[1].y < p[0].y)
        assertTrue(close(p[0].x, p[1].x))
    }

    @Test fun singlePointSitsInTheMiddle() {
        val p = RouteSvg.project(listOf(GeoPoint(37.0, 25.0)), 300.0, 100.0, 12.0).single()
        assertTrue(close(p.x, 150.0) && close(p.y, 50.0))
    }

    @Test fun antimeridianIsUnwrapped() {
        assertEquals(listOf(179.0, 181.0, 182.0), RouteSvg.unwrap(listOf(179.0, -179.0, -178.0)))
        assertEquals(listOf(-179.0, -181.0), RouteSvg.unwrap(listOf(-179.0, 179.0)))
        val p = RouteSvg.project(listOf(GeoPoint(0.0, 179.0), GeoPoint(0.0, -179.0)), 100.0, 100.0, 0.0)
        // Two degrees apart, not 358: the line goes across the date line, left to right.
        assertTrue(p[1].x > p[0].x)
    }

    @Test fun simplifyDropsStraightRunsAndKeepsEnds() {
        val straight = (0..50).map { Pt(it.toDouble(), 0.0) }
        assertEquals(listOf(Pt(0.0, 0.0), Pt(50.0, 0.0)), RouteSvg.simplify(straight, 0.5))
        val corner = (0..10).map { Pt(it.toDouble(), 0.0) } + (1..10).map { Pt(10.0, it.toDouble()) }
        assertEquals(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0)), RouteSvg.simplify(corner, 0.5))
    }

    @Test fun numbersUseADotAndOneDecimal() {
        assertEquals("12", RouteSvg.n(12.0))
        assertEquals("12.3", RouteSvg.n(12.34))
        assertEquals("-0.5", RouteSvg.n(-0.5))
    }

    @Test fun sampleTripRouteUsesTheTrack() {
        val book = ExportFixtures.book()
        val r = book.route(ExportOptions())
        assertFalse(r.planned)
        assertEquals(book.track.size, r.line.size)
        assertEquals(listOf("Parikia, Paros", "Agios Georgios, Naxos", "Halki, Naxos"), r.places.map { it.name })
    }

    @Test fun plannedTripRouteUsesPlanPlaces() {
        val book = ExportFixtures.book().copy(track = emptyList(), stops = emptyList())
        val r = book.route(ExportOptions())
        assertTrue(r.planned)
        assertEquals(listOf("Ferry Piraeus → Paros", "Ferry Paros → Naxos", "Lunch in Halki"), r.places.map { it.name })
    }
}

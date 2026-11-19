package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.geo.BBox
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.geo.GeoPoint
import com.justdataplease.boringtravel.geo.Polyline
import com.justdataplease.boringtravel.geo.PrivacyZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test fun haversineMatchesKnownDistances() {
        assertEquals(343_500.0, Geo.distanceM(48.8566, 2.3522, 51.5074, -0.1278), 1_000.0) // Paris – London
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 10.0)
        assertEquals(0.0, Geo.distanceM(37.1, 25.4, 37.1, 25.4), 1e-9)
    }

    @Test fun bearingAndTurn() {
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(0.0, Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(180.0, Geo.bearingDeg(0.0, 0.0, -1.0, 0.0), 1e-6)
        assertEquals(270.0, Geo.bearingDeg(0.0, 0.0, 0.0, -1.0), 1e-6)
        assertEquals(20.0, Geo.turnDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.turnDeg(0.0, 180.0), 1e-9)
    }

    @Test fun distanceToSegment() {
        val a = GeoPoint(37.0, 25.0)
        val b = GeoPoint(37.0, 25.2)
        val p = GeoPoint(37.0 + 1_000 / Geo.METERS_PER_DEGREE, 25.1)
        assertEquals(1_000.0, Geo.distanceToSegmentM(p, a, b), 5.0)
        // Beyond the end the nearest point is the end itself.
        assertEquals(Geo.distanceM(GeoPoint(37.0, 25.3), b), Geo.distanceToSegmentM(GeoPoint(37.0, 25.3), a, b), 50.0)
    }

    @Test fun polylineMatchesGooglesExample() {
        val points = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
        val encoded = Polyline.encode(points)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encoded)
        val back = Polyline.decode(encoded)
        assertEquals(points.size, back.size)
        points.zip(back).forEach { (p, q) ->
            assertEquals(p.lat, q.lat, 1e-5)
            assertEquals(p.lng, q.lng, 1e-5)
        }
    }

    @Test fun polylineRoundTripsARealRoute() {
        val route = (0..200).map { i -> GeoPoint(37.0853 + i * 0.0001, 25.1488 + i * 0.00113) } + GeoPoint(-33.86, 151.2)
        val back = Polyline.decode(Polyline.encode(route))
        assertEquals(route.size, back.size)
        route.zip(back).forEach { (p, q) ->
            assertEquals(p.lat, q.lat, 1e-5)
            assertEquals(p.lng, q.lng, 1e-5)
        }
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test fun simplifyKeepsCornersAndDropsStraightRuns() {
        val straight = (0..100).map { GeoPoint(37.0, 25.0 + it * 0.001) }
        assertEquals(listOf(straight.first(), straight.last()), Geo.simplify(straight, 5.0))
        val corner = (0..50).map { GeoPoint(37.0, 25.0 + it * 0.001) } + (1..50).map { GeoPoint(37.0 + it * 0.001, 25.05) }
        val s = Geo.simplify(corner, 5.0)
        assertEquals(3, s.size)
        assertEquals(GeoPoint(37.0, 25.05), s[1])
        assertEquals(corner, Geo.simplify(corner, 0.0))
    }

    @Test fun boxes() {
        val b = BBox.of(listOf(GeoPoint(37.0, 25.0), GeoPoint(37.2, 25.5), GeoPoint(36.9, 25.1)))!!
        assertEquals(BBox(36.9, 25.0, 37.2, 25.5), b)
        assertTrue(b.contains(37.1, 25.2))
        assertFalse(b.contains(38.0, 25.2))
        assertNull(BBox.of(emptyList()))
        val one = BBox.of(listOf(GeoPoint(37.0, 25.0)))!!
        assertTrue(one.isPoint)
        val grown = one.expand(1_000.0)
        assertTrue(grown.contains(37.0 + 900 / Geo.METERS_PER_DEGREE, 25.0))
        assertFalse(grown.contains(37.0 + 1_100 / Geo.METERS_PER_DEGREE, 25.0))
    }

    @Test fun privacyZoneDropsPointsInside() {
        val home = PrivacyZone(37.9838, 23.7275, 500.0)
        val pts = listOf(
            GeoPoint(37.9838, 23.7275), // home
            GeoPoint(37.9838 + 400 / Geo.METERS_PER_DEGREE, 23.7275), // still inside
            GeoPoint(37.9838 + 600 / Geo.METERS_PER_DEGREE, 23.7275), // outside
            GeoPoint(37.1, 25.37),
        )
        val out = PrivacyZone.outside(pts, listOf(home), { it.lat }, { it.lng })
        assertEquals(pts.drop(2), out)
        assertEquals(pts, PrivacyZone.outside(pts, emptyList(), { it.lat }, { it.lng }))
    }
}

package com.justdataplease.boringtravel.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GlobeMathTest {
    private fun ortho(lon: Double, lat: Double) = Ortho().set(lon, lat, 500f, 400f, 200f)

    private fun project(o: Ortho, lat: Double, lng: Double): Triple<Float, Float, Float> {
        val x = GlobeMath.unitX(lat, lng)
        val y = GlobeMath.unitY(lat, lng)
        val z = GlobeMath.unitZ(lat)
        return Triple(o.screenX(o.x(x, y, z)), o.screenY(o.y(x, y, z)), o.depth(x, y, z))
    }

    @Test fun theViewCentreSitsInTheMiddleFacingUs() {
        val o = ortho(23.7, 37.9)
        val (sx, sy, d) = project(o, 37.9, 23.7)
        assertEquals(500f, sx, 1e-3f)
        assertEquals(400f, sy, 1e-3f)
        assertEquals(1f, d, 1e-5f)
    }

    @Test fun eastIsRightNorthIsUpAndTheFarSideIsBehind() {
        val o = ortho(0.0, 0.0)
        val (ex, ey, _) = project(o, 0.0, 30.0)
        assertTrue(ex > 500f)
        assertEquals(400f, ey, 1e-3f)
        val (_, ny, _) = project(o, 30.0, 0.0)
        assertTrue(ny < 400f) // screen y grows downwards
        assertEquals(-1f, project(o, 0.0, 180.0).third, 1e-5f)
        // A point 90° away lies on the rim.
        val (rx, ry, rd) = project(o, 0.0, 90.0)
        assertEquals(0f, rd, 1e-5f)
        assertEquals(700f, rx, 1e-2f)
        assertEquals(400f, ry, 1e-2f)
    }

    @Test fun unprojectGivesBackThePointUnderTheFinger() {
        val o = ortho(-40.0, 25.0)
        val out = DoubleArray(2)
        for ((lat, lng) in listOf(25.0 to -40.0, 40.0 to -20.0, -10.0 to -70.0, 60.0 to 0.0)) {
            val (sx, sy, d) = project(o, lat, lng)
            assertTrue(d > 0)
            assertTrue(o.unproject(sx, sy, out))
            assertEquals(lat, out[0], 1e-3)
            assertEquals(lng, out[1], 1e-3)
        }
        assertFalse(o.unproject(500f + 201f, 400f, out)) // off the disc
    }

    @Test fun aSidewaysDragKeepsThePointUnderTheFinger() {
        val r = 200f
        for (lat in listOf(0.0, 36.0, 60.0)) {
            val o = ortho(10.0, lat)
            val before = project(o, lat, 10.0)
            val dx = 6f
            val lon2 = 10.0 + GlobeMath.dragLon(dx, r, lat)
            val after = project(ortho(lon2, lat), lat, 10.0)
            assertEquals("lat $lat", before.first + dx, after.first, 0.05f)
        }
        // Dragging down brings the north into view.
        assertTrue(GlobeMath.dragLat(10f, r) > 0)
    }

    @Test fun latitudeStopsShortOfThePoles() {
        assertEquals(GlobeMath.MAX_LAT, GlobeMath.clampLat(95.0), 0.0)
        assertEquals(-GlobeMath.MAX_LAT, GlobeMath.clampLat(-120.0), 0.0)
        assertEquals(12.5, GlobeMath.clampLat(12.5), 0.0)
    }

    @Test fun longitudesWrapAndTakeTheShortWay() {
        assertEquals(-170.0, GlobeMath.normLon(190.0), 1e-9)
        assertEquals(180.0, GlobeMath.normLon(-180.0), 1e-9)
        assertEquals(10.0, GlobeMath.normLon(370.0), 1e-9)
        assertEquals(20.0, GlobeMath.lonDelta(170.0, -170.0), 1e-9)
        assertEquals(-20.0, GlobeMath.lonDelta(-170.0, 170.0), 1e-9)
        assertEquals(PI_F, abs(GlobeMath.wrapRad(3 * PI_F)), 1e-4f)
        assertEquals(-0.5f, GlobeMath.wrapRad(-0.5f + 2 * PI_F), 1e-4f)
    }

    @Test fun flingsSlowDownAndStop() {
        val v0 = 300.0
        val v1 = GlobeMath.decay(v0, GlobeMath.FLING_TIME_CONSTANT_S)
        assertEquals(v0 / Math.E, v1, 1e-6)
        // Step it like frames do: the distance matches the closed form and it comes to rest.
        var v = v0
        var travelled = 0.0
        var t = 0.0
        while (abs(v) > GlobeMath.FLING_STOP_DEG_PER_S) {
            travelled += v / 120.0
            v = GlobeMath.decay(v, 1 / 120.0)
            t += 1 / 120.0
        }
        assertTrue(t < 3.0)
        assertEquals(GlobeMath.flingDistance(v0), travelled, GlobeMath.flingDistance(v0) * 0.03)
    }

    @Test fun zoomStaysWithinItsLimits() {
        assertEquals(GlobeMath.MIN_ZOOM, GlobeMath.clampZoom(0.1f), 0f)
        assertEquals(GlobeMath.MAX_ZOOM, GlobeMath.clampZoom(40f), 0f)
        assertEquals(2f, GlobeMath.clampZoom(2f), 0f)
        assertEquals(0.46f * 300f, GlobeMath.baseRadius(300f, 500f), 1e-4f)
    }

    @Test fun easingAndSpinRampStartSoftAndSettle() {
        assertEquals(0f, GlobeMath.ease(0f), 0f)
        assertEquals(1f, GlobeMath.ease(1f), 1e-6f)
        assertEquals(0.5f, GlobeMath.ease(0.5f), 1e-6f)
        assertTrue(GlobeMath.ease(0.1f) < 0.1f)
        assertEquals(0.0, GlobeMath.spinSpeed(0.0), 0.0)
        assertEquals(GlobeMath.SPIN_DEG_PER_S, GlobeMath.spinSpeed(10.0), 1e-9)
        assertTrue(GlobeMath.spinSpeed(0.5) in 0.0..GlobeMath.SPIN_DEG_PER_S)
    }

    @Test fun theMiddleOfSomeCountriesIsAmongThem() {
        val (lat, lng) = GlobeMath.meanPoint(listOf(39.0 to 22.0, 42.0 to 12.0, 46.0 to 2.0))!!
        assertTrue(lat in 39.0..46.0)
        assertTrue(lng in 2.0..22.0)
        // Across the date line the middle stays in the Pacific, not in Africa.
        val (_, pacific) = GlobeMath.meanPoint(listOf(-17.0 to 178.0, -14.0 to -172.0))!!
        assertTrue(abs(pacific) > 170.0)
        assertEquals(90.0, GlobeMath.angleDeg(0.0, 0.0, 0.0, 90.0), 1e-3)
    }

    private companion object {
        const val PI_F = Math.PI.toFloat()
    }
}

package com.justdataplease.boringtravel.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor's geometry: screen ↔ page, turned boxes, handles, resizing, turning and snapping. */
class FreeMathTest {
    private val eps = 1e-6

    private fun assertBox(expected: Box, actual: Box, tol: Double = 1e-6) {
        assertEquals("x", expected.x, actual.x, tol)
        assertEquals("y", expected.y, actual.y, tol)
        assertEquals("w", expected.w, actual.w, tol)
        assertEquals("h", expected.h, actual.h, tol)
    }

    @Test fun screenToPageAndBack() {
        // A page shown 3.2 px per mm, its trim 40 px in and 24 px down (the bleed margin around it).
        val t = PageTransform(3.2, 40.0, 24.0)
        val p = t.toPage(40.0 + 3.2 * 74, 24.0 + 3.2 * 105)
        assertEquals(74.0, p.x, eps)
        assertEquals(105.0, p.y, eps)
        val s = t.toScreen(p)
        assertEquals(40.0 + 3.2 * 74, s.x, eps)
        assertEquals(24.0 + 3.2 * 105, s.y, eps)
        assertEquals(10.0, t.mm(32.0), eps)
        // Points off the trim (in the bleed) are negative page mm.
        assertTrue(t.toPage(0.0, 0.0).x < 0)
    }

    @Test fun hitTestFollowsTheTurn() {
        val b = Box(10.0, 10.0, 40.0, 10.0) // a long thin box, centre (30, 15)
        assertTrue(FreeMath.hit(b, 0.0, 12.0, 12.0))
        assertFalse(FreeMath.hit(b, 0.0, 30.0, 30.0))
        // Turned a quarter, it stands upright through its centre: (30, 30) is on it, (12, 12) is not.
        assertTrue(FreeMath.hit(b, 90.0, 30.0, 33.0))
        assertFalse(FreeMath.hit(b, 90.0, 12.0, 12.0))
        // Turned 45°: a point along the diagonal is on it, the upright corner is not.
        assertTrue(FreeMath.hit(b, 45.0, 30.0 + 12.0, 15.0 + 12.0))
        assertFalse(FreeMath.hit(b, 45.0, 10.5, 10.5))
        // Slop widens every side.
        assertFalse(FreeMath.hit(b, 0.0, 30.0, 21.5))
        assertTrue(FreeMath.hit(b, 0.0, 30.0, 21.5, slop = 2.0))
    }

    @Test fun cornersBoundsAndTheTurnHandle() {
        val b = Box(0.0, 0.0, 20.0, 10.0)
        val cs = FreeMath.corners(b, 90.0)
        // Turned clockwise a quarter around (10, 5): top-left goes to the top-right of the upright shape.
        assertEquals(15.0, cs[0].x, eps)
        assertEquals(-5.0, cs[0].y, eps)
        assertBox(Box(5.0, -5.0, 10.0, 20.0), FreeMath.bounds(b, 90.0))
        assertBox(b, FreeMath.bounds(b, 0.0))
        val h = FreeMath.rotateHandle(b, 0.0, 6.0)
        assertEquals(10.0, h.x, eps)
        assertEquals(-6.0, h.y, eps)
        val h90 = FreeMath.rotateHandle(b, 90.0, 6.0)
        assertEquals(10.0 + 11.0, h90.x, eps) // the top now faces right
        assertEquals(5.0, h90.y, eps)
    }

    @Test fun resizeKeepsTheOppositeCorner() {
        val b = Box(10.0, 10.0, 40.0, 20.0)
        // Bottom-right dragged out by (10, 5): top-left stays.
        assertBox(Box(10.0, 10.0, 50.0, 25.0), FreeMath.resize(b, 0.0, 2, P2(60.0, 35.0), keepAspect = false))
        // Top-left dragged in: bottom-right stays.
        assertBox(Box(20.0, 15.0, 30.0, 15.0), FreeMath.resize(b, 0.0, 0, P2(20.0, 15.0), keepAspect = false))
        // Photos keep their shape: the larger scale wins.
        val kept = FreeMath.resize(b, 0.0, 2, P2(90.0, 32.0), keepAspect = true)
        assertEquals(2.0, kept.w / kept.h, eps)
        assertEquals(80.0, kept.w, eps)
        // Never smaller than the minimum.
        val tiny = FreeMath.resize(b, 0.0, 2, P2(0.0, 0.0), keepAspect = false)
        assertEquals(FreeMath.MIN_SIZE, tiny.w, eps)
        assertEquals(FreeMath.MIN_SIZE, tiny.h, eps)
    }

    @Test fun resizeATurnedBoxKeepsItsFixedCornerInPlace() {
        val b = Box(10.0, 10.0, 40.0, 20.0)
        listOf(30.0, -60.0, 135.0).forEach { rot ->
            val fixed = FreeMath.corners(b, rot)[0]
            val dragged = FreeMath.corners(b, rot)[2]
            // Drag the bottom-right corner further along its own diagonal.
            val p = dragged + (dragged - fixed) * 0.5
            val r = FreeMath.resize(b, rot, 2, p, keepAspect = true)
            assertEquals("$rot", 60.0, r.w, 1e-6)
            assertEquals("$rot", 30.0, r.h, 1e-6)
            val after = FreeMath.corners(r, rot)
            assertEquals("$rot", fixed.x, after[0].x, 1e-6)
            assertEquals("$rot", fixed.y, after[0].y, 1e-6)
            assertEquals("$rot", p.x, after[2].x, 1e-6)
            assertEquals("$rot", p.y, after[2].y, 1e-6)
        }
    }

    @Test fun turningPointsTheTopAtTheFingerAndSnapsToEighths() {
        val b = Box(0.0, 0.0, 20.0, 20.0) // centre (10, 10)
        assertEquals(0.0, FreeMath.angleTo(b, P2(10.0, -30.0)), eps)
        assertEquals(90.0, FreeMath.angleTo(b, P2(40.0, 10.0)), eps)
        assertEquals(180.0, FreeMath.angleTo(b, P2(10.0, 40.0)), eps)
        assertEquals(-90.0, FreeMath.angleTo(b, P2(-20.0, 10.0)), eps)
        assertEquals(0.0, FreeMath.snapAngle(3.0), eps)
        assertEquals(45.0, FreeMath.snapAngle(42.5), eps)
        assertEquals(-90.0, FreeMath.snapAngle(-87.0), eps)
        assertEquals(20.0, FreeMath.snapAngle(20.0), eps)
        assertEquals(180.0, FreeMath.snapAngle(-178.0), eps)
    }

    @Test fun snapsToThePageCentreMarginsAndOtherElements() {
        val margins = Box(15.0, 15.0, 118.0, 175.0)
        val other = Box(20.0, 120.0, 50.0, 30.0)
        val lines = FreeMath.snapLines(148.0, 210.0, margins, listOf(other))
        // A box whose centre is 0.8 mm off the page centre snaps onto it.
        val moving = Box(74.8 - 20.0, 40.3, 40.0, 20.0)
        val s = FreeMath.snap(moving, lines, tol = 1.5)
        assertEquals(-0.8, s.dx, 1e-9)
        assertTrue(s.guides.contains(Guide(true, 74.0)))
        // Its top lands on nothing within reach, so it stays.
        assertEquals(0.0, s.dy, 1e-9)
        // The left edge snaps to the left margin; the bottom onto the other element's top.
        val s2 = FreeMath.snap(Box(16.0, 99.0, 30.0, 20.4), lines, tol = 1.5)
        assertEquals(-1.0, s2.dx, 1e-9)
        assertEquals(0.6, s2.dy, 1e-9)
        assertTrue(s2.guides.contains(Guide(true, 15.0)))
        assertTrue(s2.guides.contains(Guide(false, 120.0)))
        // Too far from everything: no snap, no guides.
        val none = FreeMath.snap(Box(50.0, 60.0, 11.0, 11.0), lines, tol = 1.5)
        assertEquals(0.0, none.dx, 0.0)
        assertTrue(none.guides.isEmpty())
    }

    @Test fun keepsSomethingOnThePage() {
        val b = FreeMath.keepOnPage(Box(-100.0, 300.0, 40.0, 20.0), 148.0, 210.0)
        assertEquals(-34.0, b.x, eps)
        assertEquals(204.0, b.y, eps)
    }
}

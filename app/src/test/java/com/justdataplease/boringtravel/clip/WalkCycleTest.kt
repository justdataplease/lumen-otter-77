package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** The traveller's steps: in pace with the ground under them, within a natural cadence, still when the head waits. */
class WalkCycleTest {
    private val fps = 30

    /** A head moving [unitsPerSecond] design units a second for [frames] frames (camera 1000 units wide, upright frame). */
    private fun cycle(unitsPerSecond: (Int) -> Double, frames: Int, span: Double = 1000.0, aspect: Double = 9.0 / 16): WalkCycle {
        val xs = DoubleArray(frames)
        val ys = DoubleArray(frames)
        val across = 360.0 * maxOf(1.0, aspect)
        for (f in 1 until frames) xs[f] = xs[f - 1] + unitsPerSecond(f) / fps / across * span
        return WalkCycle.of(xs, ys, DoubleArray(frames) { span }, aspect, fps)
    }

    /** Cycles a second over the last second of [c]. */
    private fun hz(c: WalkCycle, at: Int = c.frames - 1) = (c.phase(at) - c.phase(at - fps)).toDouble()

    @Test fun cadenceFollowsTheGroundWithinAWalkersRange() {
        assertEquals(0.0, WalkCycle.cadence(0.0), 0.0)
        assertEquals(0.0, WalkCycle.cadence(Double.NaN), 0.0)
        // Slower than the still threshold: standing.
        assertEquals(0.0, WalkCycle.cadence((WalkCycle.STILL_U_PER_S - .1) / WalkCycle.FIGURE_U), 0.0)
        // In range: two steps per stride of ground.
        assertEquals(1.2 / WalkCycle.STRIDE_FIGURES, WalkCycle.cadence(1.2), 1e-9)
        // Barely moving still walks at the slowest cadence; racing never blurs past the fastest.
        assertEquals(WalkCycle.MIN_HZ, WalkCycle.cadence(.1), 0.0)
        assertEquals(WalkCycle.MAX_HZ, WalkCycle.cadence(40.0), 0.0)
    }

    @Test fun aSteadyWalkStepsInPaceWithTheGround() {
        val figures = .65
        val c = cycle({ figures * WalkCycle.FIGURE_U }, 4 * fps)
        assertEquals(figures / WalkCycle.STRIDE_FIGURES, hz(c), .01)
        assertEquals(1f, c.swing(c.frames - 1), 0f)
        // The pose swings both ways, within −1..1.
        val strides = (0 until c.frames).map { c.stride(it) }
        assertTrue(strides.all { it in -1f..1f })
        assertTrue(strides.max() > .95f && strides.min() < -.95f)
        // Twice the ground speed, twice the cadence (still in range).
        assertEquals(2 * figures / WalkCycle.STRIDE_FIGURES, hz(cycle({ 2 * figures * WalkCycle.FIGURE_U }, 4 * fps)), .02)
        // Racing over the map: the fastest cadence, not a blur.
        assertEquals(WalkCycle.MAX_HZ, hz(cycle({ 30 * WalkCycle.FIGURE_U }, 4 * fps)), .01)
    }

    @Test fun theGroundIsMeasuredOnScreenNotInTheWorld() {
        // Slow enough that twice the pace stays under the fastest cadence.
        val u = .65 * WalkCycle.FIGURE_U
        val base = hz(cycle({ u }, 4 * fps))
        // The same world movement with the camera twice as close passes twice the screen: twice the steps.
        val xs = DoubleArray(4 * fps) { it * u / fps / 360.0 * 1000.0 }
        val closer = WalkCycle.of(xs, DoubleArray(xs.size), DoubleArray(xs.size) { 500.0 }, 9.0 / 16, fps)
        assertEquals(2 * base, hz(closer), .02)
        // A wide frame shows the camera's width across more design units.
        val wide = WalkCycle.of(xs, DoubleArray(xs.size), DoubleArray(xs.size) { 1000.0 }, 16.0 / 9, fps)
        assertEquals(base * 16 / 9, hz(wide), .02)
    }

    @Test fun theTravellerStandsWhileTheHeadWaitsAndWalksOnAfter() {
        val walk = 1.0 * WalkCycle.FIGURE_U
        // Walk 2 s, wait 2 s (a photo pause), walk 2 s.
        val c = cycle({ f -> if (f in 2 * fps until 4 * fps) 0.0 else walk }, 6 * fps)
        val settle = (WalkCycle.EASE_S * fps).toInt() + 2
        for (f in 2 * fps + settle until 4 * fps) {
            assertEquals("frame $f", 0f, c.swing(f), 0f)
            assertEquals(0f, c.stride(f), 0f)
        }
        // The phase barely moves while standing.
        assertTrue(c.phase(4 * fps - 1) - c.phase(2 * fps + settle) < .05f)
        assertEquals(1f, c.swing(4 * fps + settle), 0f)
        assertTrue(hz(c) > .9 * walk / WalkCycle.FIGURE_U / WalkCycle.STRIDE_FIGURES)
        // Starting and stopping ease in and out (no snap to a stride).
        for (f in 1 until c.frames) assertTrue(abs(c.swing(f) - c.swing(f - 1)) <= 1f / (WalkCycle.EASE_S * fps).toFloat() + 1e-4f)
        // Never steps backwards.
        for (f in 1 until c.frames) assertTrue(c.phase(f) >= c.phase(f - 1))
    }

    @Test fun stillAndEmptyHeadsStand() {
        val still = WalkCycle.of(DoubleArray(90) { 5.0 }, DoubleArray(90) { 7.0 }, DoubleArray(90) { 100.0 }, 1.0, fps)
        for (f in 0 until 90) {
            assertEquals(0f, still.phase(f), 0f)
            assertEquals(0f, still.stride(f), 0f)
        }
        val none = WalkCycle.of(DoubleArray(0), DoubleArray(0), DoubleArray(0), 1.0, fps)
        assertEquals(0, none.frames)
        assertEquals(0f, none.stride(10), 0f)
        // Bad numbers don't make the figure jump.
        val odd = WalkCycle.of(doubleArrayOf(0.0, Double.NaN, 1.0), DoubleArray(3), doubleArrayOf(0.0, -1.0, Double.POSITIVE_INFINITY), 1.0, fps)
        for (f in 0 until 3) assertTrue(odd.stride(f).isFinite())
        // Out-of-range frames read the nearest one.
        assertEquals(still.phase(89), still.phase(500), 0f)
    }

    @Test fun everyFrameIsWorkedOutOnceForPreviewAndExportAlike() {
        val a = cycle({ f -> 40.0 + 20 * kotlin.math.sin(f / 9.0) }, 200)
        val b = cycle({ f -> 40.0 + 20 * kotlin.math.sin(f / 9.0) }, 200)
        val pa = FloatArray(a.frames) { a.phase(it) }
        val pb = FloatArray(b.frames) { b.phase(it) }
        assertArrayEquals(pa, pb, 0f)
        // Read in any order (the preview's workers draw frames out of order): the same values.
        val shuffled = (0 until a.frames).shuffled(java.util.Random(4))
        for (f in shuffled) assertEquals(pa[f], a.phase(f), 0f)
    }

    // --- In a real clip -------------------------------------------------------------------------------------------

    @Test fun aWalkingClipStepsOnTheJourneyAndStandsAtTheStartAndEnd() {
        val zone = ZoneId.of("Europe/Athens")
        val start = LocalDate.of(2026, 6, 16)
        val t0 = start.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        // An hour's walk around Parikia, about 3 km/h.
        val points = (0..60).map { m -> GeoPoint(t0 + m * 60_000L, 37.0853 + m * 0.0004, 25.1488 + m * 0.0003, 8f) }
        val input = ClipInput("Paros", start, start, zone, points, listOf(ClipStop(t0, 37.0853, 25.1488, "Parikia", "GR")), emptyList())
        val plan = requireNotNull(ClipPlan.build(input, ClipOptions(), Locale.ENGLISH))
        assertEquals(listOf(Vehicle.Walk), plan.vehiclesUsed)
        val walk = plan.walk
        assertEquals(plan.frameCount, walk.frames)
        // The opening and the end card: standing.
        assertEquals(0f, walk.swing(0), 0f)
        assertEquals(0f, walk.swing(plan.frameCount - 1), 0f)
        // The journey: walking at a walker's cadence.
        val middle = (plan.drawStart + plan.drawEnd) / 2
        assertEquals(1f, walk.swing(middle), 0f)
        val stepHz = (walk.phase(middle + fps / 2) - walk.phase(middle - fps / 2)).toDouble()
        assertTrue("$stepHz", stepHz in WalkCycle.MIN_HZ - .05..WalkCycle.MAX_HZ + .05)
        assertTrue(walk.phase(plan.drawEnd) > walk.phase(plan.drawStart))
    }
}

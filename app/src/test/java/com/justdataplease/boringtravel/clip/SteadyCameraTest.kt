package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.SampleKyoto
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * The steady camera (owner, 2026-11-08: "the camera zooms out and in … i want strava like effects"): one width per run
 * changed only between runs, a flight's one move out and one move in, the opening that moves from frame 0 and lands by
 * about 2 s, from the region or from space, and the close that frames like the opening.
 */
class SteadyCameraTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val start = LocalDate.of(2026, 6, 16)
    private fun at(day: Int, h: Int, m: Int) = start.plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun leg(from: Pair<Double, Double>, to: Pair<Double, Double>, startMs: Long, minutes: Int): List<GeoPoint> =
        (0..minutes).map { i ->
            val f = i.toDouble() / minutes
            GeoPoint(startMs + i * 60_000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, 10f)
        }

    /** Three days on Paros and Naxos: a walk, the ferry, the bus up to Halki (one country, about 35 km). */
    private val cyclades = ClipInput(
        title = "Cyclades", startDate = start, endDate = start.plusDays(2), zone = zone,
        points = leg(37.0853 to 25.1488, 37.0950 to 25.1700, at(0, 9, 0), 40) +
            leg(37.0950 to 25.1700, 37.1056 to 25.3747, at(1, 8, 0), 60) +
            leg(37.1056 to 25.3747, 37.0587 to 25.4807, at(2, 11, 0), 30),
        stops = listOf(
            ClipStop(at(0, 9, 40), 37.0950, 25.1700, "Parikia, Paros", "GR", "s0"),
            ClipStop(at(1, 9, 0), 37.1056, 25.3747, "Chora, Naxos", "GR", "s1"),
            ClipStop(at(2, 11, 30), 37.0587, 25.4807, "Halki, Naxos", "GR", "s2"),
        ),
        photos = emptyList(),
    )

    /** A walk in Athens, the flight to Lisbon (recorded: two points), a walk in Lisbon the next day. */
    private val flight: ClipInput by lazy {
        val athens = 37.9715 to 23.7257
        val athensB = 37.9838 to 23.7275
        val lisbon = 38.7110 to -9.1365
        val lisbonB = 38.7223 to -9.1393
        ClipInput(
            title = "Two cities", startDate = start, endDate = start.plusDays(2), zone = zone,
            points = leg(athens, athensB, at(0, 10, 0), 20) +
                listOf(GeoPoint(at(1, 9, 0), athensB.first, athensB.second), GeoPoint(at(1, 12, 30), lisbon.first, lisbon.second)) +
                leg(lisbon, lisbonB, at(2, 10, 0), 20),
            stops = listOf(ClipStop(at(0, 9, 0), athens.first, athens.second, "Plaka, Athens", "GR"), ClipStop(at(1, 13, 0), lisbon.first, lisbon.second, "Alfama, Lisbon", "PT")),
            photos = emptyList(),
        )
    }

    private val kyoto: ClipInput by lazy {
        val plan = SampleKyoto.plan(LocalDate.of(2027, 10, 3), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), zone).getValue(ClipSource.Planned)
    }

    private fun build(input: ClipInput, options: ClipOptions = ClipOptions()) = requireNotNull(ClipPlan.build(input, options, Locale.ENGLISH))

    private fun lnSpan(p: ClipPlan, f: Int) = ln(p.camera(f).spanX)

    /** Frames the eased change between two runs of widths [a] and [b] may take (see SteadyCamera), with the smoothing. */
    private fun change(p: ClipPlan, a: Double, b: Double): Int {
        val rate = ClipPlan.MAX_ZOOM_RATE * 30.0 / p.fps
        return max((SteadyCamera.EASE_S * p.fps).toInt(), ceil(1.5 * abs(ln(b / a)) / rate).toInt()) + 8
    }

    // --- Runs ---------------------------------------------------------------------------------------------------------

    @Test fun theWidthNeverChangesInsideARun() {
        for (input in listOf(cyclades, flight, kyoto)) for (shape in listOf(ClipShape.Story, ClipShape.Wide)) {
            val p = build(input, ClipOptions(shape = shape))
            val runs = p.cameraRuns
            // A calm clip may hold one height throughout (owner, 2026-11-08: "many zoom in zoom out … tiring").
            assertTrue(input.title, runs.isNotEmpty())
            // The runs cover the journey, in order.
            assertEquals(p.drawStart, runs.first().from)
            assertEquals(p.drawEnd, runs.last().to)
            for (i in 1 until runs.size) assertEquals(runs[i - 1].to, runs[i].from)
            var checked = 0
            for ((i, run) in runs.withIndex()) {
                // Away from the eased changes at its two ends, each frame shows the run's own width exactly.
                val head = if (i > 0) change(p, runs[i - 1].span, run.span) else 0
                val tail = if (i < runs.lastIndex) change(p, run.span, runs[i + 1].span) else 0
                for (f in max(run.from + head, p.openingEnd) until run.to - tail) {
                    assertEquals("${input.title} $shape run ${run.from}..${run.to} frame $f", 0.0, lnSpan(p, f) - ln(run.span), 1e-9)
                    checked++
                }
            }
            assertTrue("${input.title}: $checked of ${p.drawEnd - p.drawStart} frames held", checked > (p.drawEnd - p.drawStart) / 3)
            // No pumping: the zoom turns (in, then out, or back) at most once per run.
            var turns = 0
            var dir = 0
            for (f in p.openingEnd + 1 until p.drawEnd) {
                val d = lnSpan(p, f) - lnSpan(p, f - 1)
                if (abs(d) < 1e-7) continue
                val now = if (d > 0) 1 else -1
                if (dir != 0 && now != dir) turns++
                dir = now
            }
            assertTrue("${input.title} $shape: $turns turns over ${runs.size} runs", turns <= runs.size)
        }
    }

    @Test fun theSteadyCameraIsCalmerThanTheFollowCamera() {
        // The same trips: the follow camera re-zooms with the speed on screen, the steady one only between runs.
        for (input in listOf(cyclades, kyoto)) {
            fun moving(p: ClipPlan) = (p.openingEnd + 1 until p.drawEnd).count { abs(lnSpan(p, it) - lnSpan(p, it - 1)) > 1e-4 }
            val steady = build(input)
            val follow = build(input, ClipOptions(camera = CameraMode.Follow))
            assertTrue("${input.title}: steady ${moving(steady)} vs follow ${moving(follow)} zooming frames", moving(steady) < moving(follow))
            assertTrue(follow.cameraRuns.isEmpty())
        }
    }

    @Test fun aFlightIsOneMoveOutAndOneMoveIn() {
        for (seconds in listOf<Int?>(null, 15, 60)) {
            val p = build(flight, ClipOptions(seconds = seconds))
            val plane = (p.drawStart until p.drawEnd).filter { p.frame(it).vehicle == Vehicle.Plane }
            assertTrue(plane.size > p.fps)
            // The zoom's moves over the journey: stretches of frames changing the same way, more than 1% in all.
            class Move(val from: Int, val to: Int, val sign: Int, var size: Double)
            val moves = ArrayList<Move>()
            for (f in p.openingEnd + 1 until p.drawEnd) {
                val d = lnSpan(p, f) - lnSpan(p, f - 1)
                if (abs(d) < 1e-7) continue
                val sign = if (d > 0) 1 else -1
                val last = moves.lastOrNull()
                if (last != null && last.sign == sign && f - last.to <= 2) moves[moves.lastIndex] = Move(last.from, f, sign, last.size + abs(d))
                else moves += Move(f, f, sign, abs(d))
            }
            val big = moves.filter { it.size > 0.01 }
            assertEquals("$seconds s: ${big.map { "${it.from}..${it.to} ${it.sign}" }}", 2, big.size)
            // Out as the plane takes off, in as it lands (each around its moment); the flight's own width between.
            val mid = plane[plane.size / 2]
            assertTrue("$seconds s out ${big[0].from}..${big[0].to}, plane ${plane.first()}..${plane.last()}",
                big[0].sign > 0 && big[0].from < plane.first() && big[0].to < mid)
            assertTrue("$seconds s in ${big[1].from}..${big[1].to}", big[1].sign < 0 && big[1].from > mid && big[1].to > plane.last())
            val flightWidth = lnSpan(p, mid)
            for (f in big[0].to + 1 until big[1].from) assertEquals(flightWidth, lnSpan(p, f), 1e-9)
            val walk = p.days.first().let { d -> (d.fromFrame until d.toFrame).minOf { lnSpan(p, it) } }
            assertTrue(flightWidth - walk > ln(20.0))
            // The head stays in view throughout, by the centre's moves.
            for (f in 0 until p.frameCount) {
                val st = p.frame(f)
                val r = st.camera.rect(p.options.shape.aspect)
                assertTrue("$seconds s frame $f", (st.headX - r.x0) / r.w in 0.05..0.95 && (st.headY - r.y0) / r.h in 0.05..0.95)
            }
        }
    }

    @Test fun theCentreLeadsTheWayAhead() {
        // Across to Naxos (heading east), the head sits behind the middle of the frame: more of the way ahead shows.
        val p = build(cyclades)
        val crossing = p.days[1].let { d -> (d.fromFrame until d.toFrame).toList() }
        val mid = crossing.subList(crossing.size / 4, crossing.size * 3 / 4)
        val lead = mid.map { f -> (p.camera(f).cx - p.frame(f).headX) / p.camera(f).spanX }.average()
        assertTrue("lead $lead", lead in 0.08..0.25)
    }

    @Test fun aPlacesOwnZoomHoldsOverItsStretch() {
        val base = build(kyoto)
        val label = base.labels[base.labels.size / 2]
        val p = build(kyoto, ClipOptions(cameraEdits = CameraEdits(zoom = mapOf(label.key to ClipZoom.VeryClose))))
        val own = p.cameraRuns.single { it.own }
        assertTrue(own.from <= label.from + 1 && own.to >= label.to - 1)
        // Its height throughout (the eases happen around it; only the last smoothing touches its first and last frames).
        for (f in own.from + 8 until own.to - 8) assertEquals(ln(own.span), lnSpan(p, f), 1e-9)
        val mid = label.from + (label.to - label.from) / 2
        assertTrue(own.span < base.cameraRuns.first { mid in it.from until it.to }.span)
    }

    // --- The opening and the close ------------------------------------------------------------------------------------

    @Test fun theOpeningMovesFromFrameZeroAndLandsByAboutTwoSeconds() {
        for (input in listOf(cyclades, flight, kyoto)) for (shape in listOf(ClipShape.Story, ClipShape.Wide)) {
            val p = build(input, ClipOptions(shape = shape))
            assertEquals(p.overview, p.camera(0))
            // Moving from the first frame: wider than the next, the dive under way.
            assertTrue(p.camera(1).spanX < p.camera(0).spanX * 0.99)
            // The title over the moving map for at most 1.5 s; the dive done by 2 s.
            assertTrue(p.drawStart <= (ClipTiming.INTRO_S * p.fps).toInt())
            assertEquals(0.0, p.frame(p.drawStart).titleAlpha, 1e-9)
            assertTrue("${input.title} ${p.openingEnd}", p.openingEnd <= (ClipPlan.DIVE_S * p.fps).toInt())
            // The dive narrows all the way (eased out: fastest at first, landing softly).
            for (f in 1 until p.openingEnd) assertTrue(p.camera(f).spanX <= p.camera(f - 1).spanX * (1 + 1e-9))
            val first = lnSpan(p, 0) - lnSpan(p, 1)
            val last = lnSpan(p, p.openingEnd - 2) - lnSpan(p, p.openingEnd - 1)
            assertTrue(first > 5 * abs(last))
            // The close: pull-back and end card within 2.5 s, back on the opening's view (the clip loops).
            assertTrue(p.frameCount - p.drawEnd <= (ClipTiming.OUTRO_S * p.fps).toInt() + 1)
            assertTrue(p.endCardStart in p.drawEnd until p.frameCount)
            assertEquals(p.overview.spanX, p.camera(p.frameCount - 1).spanX, p.overview.spanX * 1e-9)
            assertEquals(p.overview.cx, p.camera(p.frameCount - 1).cx, p.overview.spanX * 1e-9)
            assertEquals(1.0, p.frame(p.frameCount - 1).endAlpha, 1e-9)
        }
    }

    @Test fun aNearbyTripOpensOverItsRegionAFarOrForeignOneFromSpace() {
        // About 35 km in one country: the region, a few times the route around it.
        val near = build(cyclades)
        assertFalse(near.fromSpace)
        assertEquals(ClipPlan.REGION_SPAN, near.overview.spanX / near.routeView.spanX, 1e-9)
        assertEquals(near.routeView.cx, near.overview.cx, 1e-12)
        // Two countries: from space (on a flat map, a continent).
        val far = build(flight)
        assertTrue(far.fromSpace)
        assertTrue(far.overview.spanX > 3 * far.routeView.spanX)
        // One country but more than 500 km across: from space too; on the globe from the globe's own space view.
        val long = cyclades.copy(points = leg(37.0 to 22.0, 41.0 to 26.0, at(0, 9, 0), 300), stops = emptyList())
        assertTrue(build(long).fromSpace)
        val globe = build(long, ClipOptions(map = MapStyle.Globe))
        assertEquals(ClipGlobe.SPACE_RADIUS * 1080, ClipGlobe.radiusPx(globe.camera(globe.frameCount - 1), 1080.0), 1e-6)
        // On the globe a nearby trip also opens over its region, not from space, and doesn't spin.
        val local = build(cyclades, ClipOptions(map = MapStyle.Globe))
        assertFalse(local.fromSpace)
        assertEquals(local.overview, local.camera(0))
        assertTrue(local.overview.spanX < globe.overview.spanX / 10)
        // The follow camera keeps the views it always had.
        val follow = build(cyclades, ClipOptions(camera = CameraMode.Follow))
        assertEquals(follow.routeView, follow.overview)
    }

    @Test fun theDiveIsCappedNowhereElse() {
        // Outside the opening and the close the zoom keeps the speed limit and the centre glides.
        val limit = ClipPlan.MAX_ZOOM_RATE + 1e-9
        for (input in listOf(cyclades, flight, kyoto)) {
            val p = build(input, ClipOptions(closeUp = CloseUp.Close))
            for (f in p.openingEnd + 1..p.drawEnd) {
                val a = p.camera(f - 1)
                val b = p.camera(f)
                assertTrue("${input.title} frame $f", abs(ln(b.spanX / a.spanX)) <= limit)
                val span = minOf(a.spanX, b.spanX)
                assertTrue(max(abs(b.cx - a.cx) / span, abs(b.cy - a.cy) / (span / p.options.shape.aspect)) <= 0.08)
            }
            // The head never jumps when the close begins.
            val end = p.frame(p.drawEnd)
            val before = p.frame(p.drawEnd - 1)
            assertTrue(hypot(end.headX - before.headX, end.headY - before.headY) < p.camera(p.drawEnd).spanX * 0.01)
        }
    }

}

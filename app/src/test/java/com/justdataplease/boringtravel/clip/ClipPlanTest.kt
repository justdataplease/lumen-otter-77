package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

class ClipPlanTest {
    /** Set lengths for the camera checks (the app's length comes from the trip and the speed: ClipTimingTest). */
    private val LENGTHS = listOf(15, 30, 60)
    private val zone = ZoneId.of("Europe/Athens")
    private val start = LocalDate.of(2026, 6, 16)

    private val parikia = 37.0853 to 25.1488
    private val parikiaNorth = 37.0950 to 25.1700
    private val naxosPort = 37.1056 to 25.3747
    private val halki = 37.0587 to 25.4807

    private fun at(day: Int, h: Int, m: Int) = start.plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun leg(from: Pair<Double, Double>, to: Pair<Double, Double>, startMs: Long, minutes: Int, everyMin: Int): List<GeoPoint> {
        val n = minutes / everyMin
        return (0..n).map { i ->
            val f = i.toDouble() / n
            GeoPoint(startMs + i * everyMin * 60_000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, 10f)
        }
    }

    /**
     * Three days in the Cyclades: a walk in Parikia, the ferry to Naxos (no points while at sea), the bus up to
     * Halki. Stops and photos along the way.
     */
    private fun cyclades(photos: Int = 3) = ClipInput(
        title = "Cyclades, slowly",
        startDate = start,
        endDate = start.plusDays(2),
        zone = zone,
        points = leg(parikia, parikiaNorth, at(0, 9, 0), 30, 1) +
            listOf(GeoPoint(at(1, 7, 30), parikia.first, parikia.second, 10f), GeoPoint(at(1, 8, 35), naxosPort.first, naxosPort.second, 10f)) +
            leg(naxosPort, halki, at(2, 11, 0), 16, 1),
        stops = listOf(
            ClipStop(at(0, 8, 50), parikia.first, parikia.second, "Parikia, Paros", "GR"),
            ClipStop(at(1, 8, 40), naxosPort.first, naxosPort.second, "Chora, Naxos", "GR"),
            ClipStop(at(2, 11, 20), halki.first, halki.second, "Halki, Naxos", "GR"),
        ),
        photos = (0 until photos).map { i ->
            val t = listOf(at(0, 9, 20), at(1, 8, 0), at(2, 11, 10))[i % 3] + (i / 3) * 60_000L
            ClipPhoto("p$i", t, null, null)
        },
    )

    private fun plan(
        input: ClipInput = cyclades(), seconds: Int = 15, shape: ClipShape = ClipShape.Story, vehicle: Vehicle = Vehicle.Auto,
        photos: Boolean = true, view: ClipView = ClipView.Flat, sky: Boolean = false,
    ) = requireNotNull(ClipPlan.build(input, ClipOptions(seconds = seconds, shape = shape, vehicle = vehicle, photos = photos, view = view, sky = sky), Locale.ENGLISH))

    @Test fun perspectiveCameraKeepsTheHeadVisibleAndFetchesTheRevealedGround() {
        for (sky in listOf(false, true)) for (shape in ClipShape.entries) {
            val p = plan(shape = shape, view = ClipView.Perspective, sky = sky)
            val map = p.layerSpecs().first().rect
            var greatestTilt = 0.0
            for (f in 0 until p.frameCount) {
                val state = p.frame(f)
                val projection = p.projection(f)
                greatestTilt = max(greatestTilt, projection.tiltDegrees)
                // Without the sky the ground reaches the top of every frame; with it the band follows the tilt.
                if (sky) assertEquals(shape.height * ReplayProjection.SKY * projection.tiltDegrees / ReplayProjection.MAX_TILT, projection.horizonY, 1e-9)
                else assertEquals("$shape frame $f", 0.0, projection.horizonY, 0.0)
                val ground = projection.groundRect(state.camera)
                assertTrue("$shape sky $sky frame $f upper ground missing", map.contains(ground.x0, ground.y0, 1e-9))
                assertTrue("$shape sky $sky frame $f lower ground missing", map.contains(ground.x1, ground.y1, 1e-9))
                val x = (state.headX - state.camera.cx) / state.camera.spanX * shape.width + shape.width / 2.0
                val y = (state.headY - state.camera.cy) / state.camera.spanX * shape.width + shape.height / 2.0
                val screen = projection.project(x, y)
                assertTrue("$shape frame $f head ${screen.x},${screen.y}",
                    screen.x in 0.0..shape.width.toDouble() && screen.y in 0.0..shape.height.toDouble())
            }
            // Sky on: the full tilt as before; off: never past its own cap.
            assertEquals(if (sky) ReplayProjection.MAX_TILT else ReplayProjection.MAX_TILT_NO_SKY, greatestTilt, 1e-9)
            assertEquals(24.0, p.projection(0).tiltDegrees, 1e-9)
            assertEquals(24.0, p.projection(p.frameCount - 1).tiltDegrees, 1e-9)
        }
    }

    @Test fun switchingTheSkyRekeysTheBasemapAndKeepsTheRoute() {
        val plain = plan(view = ClipView.Perspective)
        val banded = plan(view = ClipView.Perspective, sky = true)
        assertTrue(plain.geometryKey != banded.geometryKey)
        assertTrue(plain.xs.contentEquals(banded.xs))
        assertEquals(plain.frameCount, banded.frameCount)
        // Flat has no sky either way.
        val flat = plan(sky = true)
        for (f in 0 until flat.frameCount step 10) assertEquals(0.0, flat.projection(f).horizonY, 0.0)
    }

    @Test fun changingViewRekeysTheBasemapWithoutChangingTheRoute() {
        val flat = plan()
        val perspective = plan(view = ClipView.Perspective)
        assertTrue(flat.geometryKey != perspective.geometryKey)
        assertTrue(flat.xs.contentEquals(perspective.xs))
        assertTrue(flat.ys.contentEquals(perspective.ys))
        assertEquals(flat.totalKm, perspective.totalKm, 0.0)
        assertEquals(flat.frameCount, perspective.frameCount)
    }

    // --- Timing -------------------------------------------------------------------------------------------------

    @Test fun frameCountsFollowTheLength() {
        for (s in LENGTHS) {
            val p = plan(seconds = s)
            assertEquals(s * 30, p.frameCount)
            assertEquals(ClipPlan.introFrames(s.toDouble(), 30), p.drawStart)
            assertEquals(s * 30 - ClipPlan.outroFrames(s.toDouble(), 30), p.drawEnd)
            assertTrue(p.endCardStart in p.drawEnd until p.frameCount)
        }
    }

    @Test fun daysShareTheDrawingTimeWithoutGapsAndInOrder() {
        val p = plan()
        assertEquals(3, p.days.size)
        assertEquals(p.drawStart, p.days.first().fromFrame)
        assertEquals(p.drawEnd, p.days.last().toFrame)
        for (i in 1 until p.days.size) assertEquals(p.days[i - 1].toFrame, p.days[i].fromFrame)
        assertTrue(p.days.all { it.toFrame > it.fromFrame })
        // The ferry day is the longest and gets the most time; the short walk still gets its floor.
        val frames = p.days.map { it.toFrame - it.fromFrame }
        assertEquals(1, frames.indexOf(frames.max()))
        val draw = p.drawEnd - p.drawStart
        assertTrue("walk day got ${frames[0]} of $draw", frames[0] >= draw * 0.5 / 3 * 0.6)
    }

    @Test fun shareFramesAddsUpAndKeepsAFloor() {
        val f = ClipPlan.shareFrames(listOf(100.0, 1.0, 1.0), 300)
        assertEquals(300, f.sum())
        assertTrue(f[0] > f[1])
        assertTrue("floor: ${f.toList()}", f[1] >= 30 && f[2] >= 30)
        val even = ClipPlan.shareFrames(listOf(0.0, 0.0), 101)
        assertEquals(101, even.sum())
        assertTrue(abs(even[0] - even[1]) <= 1)
        val proportional = ClipPlan.shareFrames(listOf(1.0, 3.0), 400)
        assertEquals(100, proportional[0])
        assertEquals(300, proportional[1])
        val crowded = ClipPlan.shareFrames(List(10) { 1.0 }, 5)
        assertEquals(5, crowded.sum())
    }

    @Test fun longLegsCountLessPastFourHours() {
        assertEquals(100.0, ClipTiming.effectiveMinutes(100.0), 1e-9)
        assertEquals(240.0, ClipTiming.effectiveMinutes(240.0), 1e-9)
        val e12h = ClipTiming.effectiveMinutes(720.0)
        assertTrue(e12h in 400.0..600.0)
        assertTrue(ClipTiming.effectiveMinutes(1_000.0) > e12h)
    }

    @Test fun theHeadOnlyMovesForwardAndEndsEachDayAtItsEnd() {
        val p = plan()
        for (f in 1 until p.frameCount) assertTrue("frame $f", p.headDistance(f) >= p.headDistance(f - 1) - 1e-6)
        for (f in 0 until p.drawStart) assertEquals(0.0, p.headDistance(f), 1e-9)
        val total = p.realCum.last()
        for (f in p.drawEnd until p.frameCount) assertEquals(total, p.headDistance(f), 1e-6)
        for (d in p.days) assertEquals(p.realCum[d.toIdx], p.headDistance(d.toFrame - 1), 1.0)
        assertEquals(35.0, p.totalKm, 4.0)
    }

    // --- Camera -------------------------------------------------------------------------------------------------

    @Test fun theOverviewFitsTheWholeRouteWithRoomToSpare() {
        for (shape in ClipShape.entries) {
            val p = plan(shape = shape)
            val r = p.overview.rect(shape.aspect)
            for (i in p.xs.indices) {
                val fx = (p.xs[i] - r.x0) / r.w
                val fy = (p.ys[i] - r.y0) / r.h
                assertTrue("$shape x $fx", fx in ClipPlan.PAD_X - 1e-9..1 - ClipPlan.PAD_X + 1e-9)
                assertTrue("$shape y $fy", fy in ClipPlan.PAD_Y - 1e-9..1 - ClipPlan.PAD_Y + 1e-9)
            }
            // It starts and ends on the overview.
            assertEquals(p.overview.spanX, p.camera(0).spanX, p.overview.spanX * 1e-9)
            assertEquals(p.overview.cx, p.camera(p.frameCount - 1).cx, p.overview.spanX * 0.01)
            assertEquals(p.overview.spanX, p.camera(p.frameCount - 1).spanX, p.overview.spanX * 0.02)
        }
    }

    @Test fun theCameraFollowsCloserWhileDrawing() {
        val p = plan()
        val mid = p.days[0].let { (it.fromFrame + it.toFrame) / 2 }
        assertTrue("zoom on day 1 was ${p.frame(mid).zoom}", p.frame(mid).zoom > 2.0)
    }

    @Test fun theCameraNeverJumpsAndTheHeadStaysInView() {
        val inputs = listOf(cyclades(), flightTrip())
        for (input in inputs) for (shape in ClipShape.entries) for (s in LENGTHS) {
            val p = plan(input, seconds = s, shape = shape)
            var worstMove = 0.0
            var worstZoom = 0.0
            // The journey (the opening's dive and the closing pull-back have their own easing, see the opening test).
            for (f in p.openingEnd + 1..p.drawEnd) {
                val a = p.camera(f - 1)
                val b = p.camera(f)
                worstMove = max(worstMove, max(abs(b.cx - a.cx), abs(b.cy - a.cy)) / a.spanX)
                worstZoom = max(worstZoom, abs(ln(b.spanX / a.spanX)))
            }
            assertTrue("${input.title} $shape $s s: centre moved ${"%.3f".format(worstMove)} of the view in one frame", worstMove <= 0.08)
            assertTrue("${input.title} $shape $s s: zoom changed ${"%.3f".format(worstZoom)} in one frame", worstZoom <= 0.15)
            for (f in 0 until p.frameCount) {
                val st = p.frame(f)
                val r = st.camera.rect(shape.aspect)
                val fx = (st.headX - r.x0) / r.w
                val fy = (st.headY - r.y0) / r.h
                assertTrue("${input.title} $shape frame $f head at $fx,$fy", fx in 0.03..0.97 && fy in 0.03..0.97)
            }
        }
    }

    // --- Vehicles -----------------------------------------------------------------------------------------------

    @Test fun autoGuessesEachStretch() {
        val p = plan()
        assertEquals(listOf(Vehicle.Walk, Vehicle.Ferry, Vehicle.Car), p.vehiclesUsed)
        // The marker shows the stretch the head is on.
        val ferryDay = p.days[1]
        val vehicles = (ferryDay.fromFrame until ferryDay.toFrame).map { p.frame(it).vehicle }.toSet()
        assertTrue(Vehicle.Ferry in vehicles)
        assertEquals(Vehicle.Car, p.frame(p.days[2].toFrame - 2).vehicle)
    }

    @Test fun aChosenVehicleIsUsedThroughout() {
        val p = plan(vehicle = Vehicle.Bike)
        assertEquals(listOf(Vehicle.Bike), p.vehiclesUsed)
        assertTrue((0 until p.frameCount step 7).all { p.frame(it).vehicle == Vehicle.Bike })
    }

    /** Athens walk, the flight to Lisbon, a Lisbon walk. */
    private fun flightTrip(): ClipInput {
        val athens = 37.9715 to 23.7257
        val athensB = 37.9838 to 23.7275
        val lisbon = 38.7110 to -9.1365
        val lisbonB = 38.7223 to -9.1393
        return ClipInput(
            title = "Two cities",
            startDate = start,
            endDate = start.plusDays(2),
            zone = zone,
            points = leg(athens, athensB, at(0, 10, 0), 20, 1) +
                listOf(GeoPoint(at(1, 9, 0), athensB.first, athensB.second), GeoPoint(at(1, 12, 30), lisbon.first, lisbon.second)) +
                leg(lisbon, lisbonB, at(2, 10, 0), 20, 1),
            stops = listOf(ClipStop(at(0, 9, 0), athens.first, athens.second, "Plaka, Athens", "GR"), ClipStop(at(1, 13, 0), lisbon.first, lisbon.second, "Alfama, Lisbon", "PT")),
            photos = emptyList(),
        )
    }

    @Test fun flightsAreDrawnAsArcsBowingNorthAndDontEatTheClip() {
        val p = plan(flightTrip())
        assertTrue(Vehicle.Plane in p.vehiclesUsed)
        val planeLegs = p.legVehicle.withIndex().filter { it.value == Vehicle.Plane }.map { it.index }
        assertTrue("an arc has many points, got ${planeLegs.size}", planeLegs.size >= ClipPlan.ARC_STEPS)
        val a = planeLegs.first()
        val b = planeLegs.last() + 1
        val chordTop = minOf(p.ys[a], p.ys[b])
        assertTrue("bows north", (a..b).any { p.ys[it] < chordTop })
        // The Lisbon walk still gets its floor of the drawing time.
        val draw = p.drawEnd - p.drawStart
        val lisbonDay = p.days.last()
        assertTrue(lisbonDay.toFrame - lisbonDay.fromFrame >= draw * 0.5 / 3 * 0.6)
        assertEquals("Greece, Portugal", p.endCard.countries)
    }

    @Test fun thePlaneTurnsToItsBearing() {
        val p = plan(flightTrip())
        val flight = p.days[1]
        val mid = (flight.fromFrame + flight.toFrame) / 2
        val st = p.frame(mid)
        assertEquals(Vehicle.Plane, st.vehicle)
        assertTrue("heading west-ish, was ${st.bearing}", st.bearing in 225.0..315.0)
        assertTrue(st.facingLeft)
    }

    // --- Stops, photos, labels, end card ------------------------------------------------------------------------

    @Test fun dayLabelsUseTheDayNumberAndWhereTheDayEnds() {
        val p = plan()
        assertEquals(listOf("Day 1 · Paros", "Day 2 · Naxos", "Day 3 · Naxos"), p.days.map { it.label })
        val f = (p.days[1].fromFrame + p.days[1].toFrame) / 2
        assertEquals("Day 2 · Naxos", p.frame(f).label)
        assertTrue(p.frame(f).labelAlpha > 0.99)
        assertNull("no label during the opening", p.frame(0).label)
        assertTrue(p.frame(0).titleAlpha > 0.99)
        assertEquals(0.0, p.frame(p.drawStart + 2).titleAlpha, 1e-9)
    }

    @Test fun stopsAppearWhenTheHeadReachesThem() {
        val p = plan()
        assertEquals(3, p.stops.size)
        for (s in p.stops) assertTrue(p.headDistance(s.frame) >= s.dist - 1e-6)
        assertTrue(p.stops.zipWithNext().all { (a, b) -> a.frame <= b.frame })
        assertEquals(0, p.frame(p.stops[1].frame - 1).stops.count { it.first === p.stops[1] })
    }

    @Test fun photosPopInOrderSpacedAndBeforeTheEndCard() {
        val p = plan(cyclades(photos = 20))
        assertTrue("capped to ${p.photos.size}", p.photos.size in 2..6)
        for (pop in p.photos) {
            assertTrue(pop.fromFrame >= p.drawStart)
            assertTrue(pop.toFrame <= p.endCardStart)
        }
        assertTrue(p.photos.zipWithNext().all { (a, b) -> b.fromFrame >= a.fromFrame + (a.toFrame - a.fromFrame) * 0.75 })
        assertEquals(p.photos.indices.toList(), p.photos.map { it.slot })
        val shown = p.frame(p.photos[0].fromFrame + 15).photos
        assertEquals(1, shown.size)
        assertEquals(1.0, shown[0].alpha, 1e-9)
        assertTrue(plan(photos = false).photos.isEmpty())
    }

    @Test fun theEndCardHasTheTotals() {
        val p = plan()
        assertEquals("Cyclades, slowly", p.endCard.title)
        assertTrue(p.endCard.stats, p.endCard.stats.startsWith("3 days · ") && p.endCard.stats.endsWith(" km"))
        assertEquals("Greece", p.endCard.countries)
        assertNotNull(p.endCard.dates)
        assertEquals(1.0, p.frame(p.frameCount - 1).endAlpha, 1e-9)
        assertEquals(0.0, p.frame(p.drawEnd).endAlpha, 1e-9)
    }

    /** The debug sample's day 4 (data/SampleData.kt): ferry Paros → Naxos, walk to the studios, bus to Halki. */
    @Test fun theDebugSampleMakesADayFourClip() {
        val tripStart = start
        val day4 = 3
        fun wiggleLeg(from: Pair<Double, Double>, to: Pair<Double, Double>, startMs: Long, minutes: Int, everyMin: Int, wiggle: Double) =
            (0..minutes / everyMin).map { i ->
                val f = i.toDouble() / (minutes / everyMin)
                GeoPoint(startMs + i * everyMin * 60_000L, from.first + (to.first - from.first) * f + kotlin.math.sin(f * Math.PI) * wiggle, from.second + (to.second - from.second) * f, 12f)
            }
        val agiosGeorgios = 37.0990 to 25.3730
        val input = ClipInput(
            title = "Cyclades, slowly", startDate = tripStart, endDate = tripStart.plusDays(9), zone = zone,
            points = wiggleLeg(parikia, naxosPort, at(day4, 7, 30), 65, 3, 0.02) +
                wiggleLeg(naxosPort, agiosGeorgios, at(day4, 9, 45), 12, 2, 0.0) +
                wiggleLeg(agiosGeorgios, halki, at(day4, 11, 40), 40, 2, -0.01),
            stops = listOf(
                ClipStop(at(0, 20, 30), parikia.first, parikia.second, "Parikia, Paros", "GR"),
                ClipStop(at(day4, 9, 45), agiosGeorgios.first, agiosGeorgios.second, "Agios Georgios, Naxos", "GR"),
                ClipStop(at(day4, 12, 20), halki.first, halki.second, "Halki, Naxos", "GR"),
            ),
            photos = emptyList(),
        )
        val p = plan(input)
        assertEquals(1, p.days.size)
        assertEquals("Day 4 · Naxos", p.days[0].label)
        assertTrue(p.endCard.stats, p.endCard.stats.startsWith("10 days · "))
        assertEquals("Greece", p.endCard.countries)
        assertTrue(p.totalKm in 28.0..40.0)
        assertEquals(listOf(Vehicle.Ferry), plan(input, vehicle = Vehicle.Ferry).vehiclesUsed)
    }

    // --- Inputs -------------------------------------------------------------------------------------------------

    @Test fun noRouteMeansNoClip() {
        assertNull(ClipPlan.build(cyclades().copy(points = emptyList(), stops = emptyList()), ClipOptions()))
        assertNull(ClipPlan.build(cyclades().copy(points = cyclades().points.take(1), stops = cyclades().stops.take(1)), ClipOptions()))
    }

    @Test fun stopsMakeARouteWhenThereIsNoTrack() {
        val p = ClipPlan.build(cyclades().copy(points = emptyList()), ClipOptions(), Locale.ENGLISH)
        assertNotNull(p)
        assertTrue(p!!.totalKm > 20)
    }

    @Test fun bigTracksAreThinnedButKeepTheirShape() {
        val wiggly = (0..20_000).map { i ->
            GeoPoint(at(0, 8, 0) + i * 5_000L, 37.0 + kotlin.math.sin(i / 300.0) * 0.05, 25.0 + i * 0.00002, 5f)
        }
        val p = plan(cyclades().copy(points = wiggly, stops = emptyList(), photos = emptyList()))
        assertTrue("kept ${p.xs.size}", p.xs.size in 20..ClipPlan.MAX_POINTS + 1)
        assertEquals(TrackShaping.lengthM(TrackShaping.clean(wiggly)) / 1000, p.totalKm, p.totalKm * 0.05)
    }

    // --- Base map pictures --------------------------------------------------------------------------------------

    @Test fun mapLayersCoverTheClipAndStayWithinSize() {
        for (shape in ClipShape.entries) {
            val p = plan(shape = shape)
            val specs = p.layerSpecs()
            val all = specs.first().rect
            for (f in 0 until p.frameCount step 5) assertTrue("$shape frame $f", all.contains(p.camera(f).rect(shape.aspect), 1e-9))
            assertTrue(specs.all { it.widthPx in 1..2048 && it.heightPx in 1..2048 && it.pixelRatio in 1f..4f })
            assertTrue("$shape: closer pictures for the zoomed-in days, got ${specs.size}", specs.size in 2..ClipPlan.MAX_LAYERS)
            assertTrue(specs.sumOf { it.pixels } <= ClipPlan.LAYER_BUDGET_PX)
            // Pictures keep the shape of the area they cover.
            for (s in specs) assertEquals(s.rect.w / s.rect.h, s.widthPx.toDouble() / s.heightPx, 0.02 * s.rect.w / s.rect.h + 2.0 / s.heightPx)
        }
    }
}

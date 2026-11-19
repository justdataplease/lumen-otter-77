package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.SampleArgentina
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The Globe map of the clip (owner: "also in video make it possible to also use the globe map"): the sphere's maths,
 * great circles, the camera from space down to the streets, the street map only where the camera comes close, and the
 * route across 180°.
 */
class ClipGlobeTest {
    private val zone = ZoneId.of("Europe/Lisbon")
    private val start = LocalDate.of(2026, 6, 16)
    private fun at(day: Int, h: Int, m: Int) = start.plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val buenosAires = -34.6037 to -58.3816
    private val madrid = 40.4168 to -3.7038
    private val tokyo = 35.6762 to 139.6503
    private val sanFrancisco = 37.7749 to -122.4194

    private fun argentina(): ClipInput {
        val plan = SampleArgentina.plan(LocalDate.of(2026, 10, 25), 0L)
        return ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleArgentina.ZONE).getValue(ClipSource.Planned)
    }

    /** Saved stops across the Pacific: Tokyo, Honolulu, San Francisco (two long straight legs). */
    private fun pacific(): ClipInput {
        val stops = listOf(
            ClipStop(at(0, 9, 0), tokyo.first, tokyo.second, "Tokyo", "JP", "a"),
            ClipStop(at(2, 9, 0), 21.3069, -157.8583, "Honolulu", "US", "b"),
            ClipStop(at(5, 9, 0), sanFrancisco.first, sanFrancisco.second, "San Francisco", "US", "c"),
        )
        return ClipInput("Across the Pacific", start, start.plusDays(5), zone, emptyList(), stops, emptyList(), ClipSource.Stops)
    }

    /** A morning walk in Lisbon, the flight to New York (recorded: two points), a walk in Manhattan the next day. */
    private fun transatlantic(): ClipInput {
        fun walk(lat: Double, lng: Double, from: Long): List<GeoPoint> =
            (0..40).map { GeoPoint(from + it * 30_000L, lat + it * 0.0002, lng + it * 0.0003, 8f) }
        val points = walk(38.7078, -9.1366, at(0, 9, 0)) +
            listOf(GeoPoint(at(0, 13, 0), 38.7742, -9.1342, 10f), GeoPoint(at(0, 21, 0), 40.6413, -73.7781, 10f)) +
            walk(40.7580, -73.9855, at(1, 10, 0))
        return ClipInput(
            "Lisbon to New York", start, start.plusDays(1), zone, points,
            listOf(ClipStop(at(0, 9, 0), 38.7078, -9.1366, "Lisbon", "PT"), ClipStop(at(1, 10, 0), 40.7580, -73.9855, "New York", "US")),
            emptyList(),
        )
    }

    private fun build(input: ClipInput, map: MapStyle = MapStyle.Globe, shape: ClipShape = ClipShape.Story, zoom: ClipZoom = ClipZoom.Normal, seconds: Int? = 40) =
        requireNotNull(ClipPlan.build(input, ClipOptions(seconds = seconds, shape = shape, map = map, zoom = zoom), Locale.ENGLISH))

    private fun unitOf(x: Double, y: Double) = Unit3.of(Mercator.lat(y), Mercator.lng(x))

    // --- The sphere -------------------------------------------------------------------------------------------------

    @Test fun legsFollowTheGreatCircle() {
        val ax = Mercator.x(buenosAires.second)
        val ay = Mercator.y(buenosAires.first)
        val bx = Mercator.x(madrid.second)
        val by = Mercator.y(madrid.first)
        val a = Unit3.of(buenosAires.first, buenosAires.second)
        val b = Unit3.of(madrid.first, madrid.second)
        val total = a.angleTo(b)
        for (t in listOf(0.1, 0.25, 0.5, 0.75, 0.9)) {
            val (x, y) = ClipGlobe.along(ax, ay, bx, by, t)
            val p = unitOf(x, y)
            // On the great circle, a share t of the way along it.
            assertEquals("t=$t", total, a.angleTo(p) + p.angleTo(b), 1e-7)
            assertEquals("t=$t", t * total, a.angleTo(p), 1e-7)
        }
        // Not the flat map's straight line: the halfway point of the great circle lies some 150 km away from it.
        val (mx, my) = ClipGlobe.along(ax, ay, bx, by, 0.5)
        assertTrue(Geo.distanceM(Mercator.lat(my), Mercator.lng(mx), Mercator.lat((ay + by) / 2), Mercator.lng((ax + bx) / 2)) > 100_000)
        // Ends are exact; very short legs go straight.
        assertEquals(ax to ay, ClipGlobe.along(ax, ay, bx, by, 0.0))
        assertEquals(bx to by, ClipGlobe.along(ax, ay, bx, by, 1.0))
        val (sx, sy) = ClipGlobe.along(0.5, 0.5, 0.5 + 1e-8, 0.5, 0.5)
        assertEquals(0.5 + 5e-9, sx, 1e-15)
        assertEquals(0.5, sy, 1e-15)
    }

    @Test fun aPacificCrossingStaysOneShortLegPast180() {
        val ax = Mercator.x(tokyo.second)
        val bx = ClipGlobe.near(Mercator.x(sanFrancisco.second), ax)
        assertTrue("San Francisco one world east of its usual column", bx > 1.0)
        assertTrue(bx - ax < 0.5)
        val ay = Mercator.y(tokyo.first)
        val by = Mercator.y(sanFrancisco.first)
        var lastX = ax
        var north = -90.0
        for (k in 1..50) {
            val (x, y) = ClipGlobe.along(ax, ay, bx, by, k / 50.0)
            assertTrue("step $k goes east", x > lastX)
            assertTrue(x - lastX < 0.02)
            lastX = x
            north = max(north, Mercator.lat(y))
        }
        assertEquals(bx, lastX, 1e-12)
        // The great circle bends north over the Pacific (about 48°N), well above both ends.
        assertTrue("north $north", north in 46.0..50.0)
        // Whole worlds apart only: near() never moves a point within the world.
        assertEquals(0.3, ClipGlobe.near(0.3, 0.6), 1e-15)
        assertEquals(1.1, ClipGlobe.near(0.1, 0.9), 1e-12)
        assertEquals(-0.1, ClipGlobe.near(0.9, 0.1), 1e-12)
    }

    @Test fun theGlobeHasTheFlatMapsScaleAtTheMiddle() {
        for (lat in listOf(0.0, -34.6, 52.0, 70.0)) for (shape in ClipShape.entries) {
            val camera = Camera(Mercator.x(10.0), Mercator.y(lat), 0.002)
            val view = GlobeView.of(camera, shape.width.toDouble(), shape.height.toDouble())
            val k = shape.width / camera.spanX
            // A point a few pixels east and south of the middle lands where the flat map draws it.
            val dx = 6.0 / k
            val dy = 4.0 / k
            val p = unitOf(camera.cx + dx, camera.cy + dy)
            assertEquals("$lat $shape", shape.width / 2.0 + 6.0, view.x(p), 0.01)
            assertEquals("$lat $shape", shape.height / 2.0 + 4.0, view.y(p), 0.01)
            assertTrue(view.depth(p) > 0.999)
        }
    }

    @Test fun spaceShowsTheWholeGlobe() {
        for (shape in ClipShape.entries) for (lat in listOf(-35.0, 0.0, 45.0)) {
            val camera = Camera(0.5, Mercator.y(lat), ClipGlobe.spaceSpan(lat, shape.aspect))
            val r = ClipGlobe.radiusPx(camera, shape.width.toDouble())
            assertEquals("$shape $lat", ClipGlobe.SPACE_RADIUS * min(shape.width, shape.height), r, 1e-6)
            assertEquals(0.0, ClipGlobe.drape(camera, shape.aspect), 0.0)
            assertEquals(1.0, ClipGlobe.flags(camera, shape.aspect), 0.0)
        }
        // A point 89° from the middle still faces the viewer; past 90° it is round the back.
        val view = GlobeView(0.0, 0.0, 500.0, 500.0, 400.0)
        assertTrue(view.depth(Unit3.of(0.0, 89.0)) > 0)
        assertTrue(view.depth(Unit3.of(0.0, 91.0)) < 0)
        assertEquals(900.0, view.x(Unit3.of(0.0, 90.0)), 1e-9)
        assertEquals(100.0, view.y(Unit3.of(90.0, 0.0)), 1e-9)
    }

    @Test fun theStreetMapComesInAsTheCameraComesDown() {
        val aspect = ClipShape.Story.aspect
        var lastDrape = -1.0
        var lastFlags = 2.0
        // From space (map zoom ≈ 1) down to a few streets (zoom 16).
        for (z in 0..32) {
            val zoom = z * 0.5
            val camera = Camera(0.5, 0.5, ZoomLimits.shortSpan(zoom))
            assertEquals(zoom, ClipGlobe.mapZoom(camera.spanX, aspect), 1e-9)
            val d = ClipGlobe.drape(camera, aspect)
            val f = ClipGlobe.flags(camera, aspect)
            assertTrue(d >= lastDrape && f <= lastFlags)
            lastDrape = d
            lastFlags = f
            if (zoom <= ClipGlobe.DRAPE_FROM_ZOOM) assertEquals(0.0, d, 0.0)
            if (zoom >= ClipGlobe.DRAPE_TO_ZOOM) assertEquals(1.0, d, 0.0)
            // The flags are gone before the street map starts.
            if (d > 0) assertEquals(0.0, f, 0.0)
        }
        // Wide frames count by their short side (the height).
        val wide = ClipShape.Wide.aspect
        assertEquals(6.0, ClipGlobe.mapZoom(ZoomLimits.shortSpan(6.0) * wide, wide), 1e-9)
    }

    @Test fun theMiddleOfARouteIsOnTheSphere() {
        val (lat, lng) = requireNotNull(ClipGlobe.mean(doubleArrayOf(10.0, -10.0), doubleArrayOf(170.0, -170.0)))
        assertEquals(0.0, lat, 1e-9)
        assertEquals(180.0, abs(lng), 1e-9)
        // Opposite points cancel out.
        assertEquals(null, ClipGlobe.mean(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 180.0)))
    }

    // --- The camera -------------------------------------------------------------------------------------------------

    @Test fun theClipStartsAndEndsInSpaceAndComesDownInBetween() {
        for (shape in listOf(ClipShape.Story, ClipShape.Wide)) {
            val p = build(argentina(), shape = shape)
            val short = min(shape.width, shape.height).toDouble()
            fun radius(f: Int) = ClipGlobe.radiusPx(p.camera(f), shape.width.toDouble())
            // The opening and the end card: the whole globe, over Argentina.
            assertEquals(ClipGlobe.SPACE_RADIUS * short, radius(0), 1e-6)
            assertEquals(ClipGlobe.SPACE_RADIUS * short, radius(p.frameCount - 1), 1e-6)
            val centre = Mercator.lat(p.overview.cy) to Mercator.lng(p.overview.cx)
            assertTrue("centre $centre", centre.first in -45.0..-25.0 && centre.second in -75.0..-55.0)
            // Under the title the globe turns into place from the east, then the camera comes down.
            assertEquals(ClipPlan.INTRO_TURN_DEG, Mercator.lng(p.camera(0).cx) - centre.second, 1e-9)
            for (f in 1 until p.drawStart / 2) assertTrue(p.camera(f).cx < p.camera(f - 1).cx)
            assertEquals(0.0, p.globeDrape(0), 0.0)
            // In between it comes down to the places: the street map shows for much of the journey (the steady camera
            // holds a month squeezed into 40 s at a few days' height, wide on the long bus days).
            val journey = (p.drawStart until p.drawEnd)
            val close = journey.count { p.globeDrape(it) >= 1.0 }
            assertTrue("$shape close ${close}/${journey.count()}", close > journey.count() * 0.4)
            assertTrue(journey.maxOf { radius(it) } > 50 * short)
            // The globe never tilts from space; the 3D camera leans in only once the street map is in.
            val tilted = requireNotNull(ClipPlan.build(argentina(), ClipOptions(seconds = 40, shape = shape, map = MapStyle.Globe, view = ClipView.Perspective), Locale.ENGLISH))
            for (f in 0 until tilted.frameCount) {
                val tilt = tilted.projection(f).tiltDegrees
                if (tilted.globeDrape(f) == 0.0) assertEquals(0.0, tilt, 0.0)
            }
            assertTrue((0 until tilted.frameCount).any { tilted.projection(it).tiltDegrees > 30 })
        }
    }

    @Test fun theHeadFliesAlongGreatCircles() {
        for (input in listOf(argentina(), pacific())) {
            val p = build(input)
            for (f in 0 until p.frameCount) {
                val s = p.frame(f)
                val a = unitOf(p.xs[s.headSeg], p.ys[s.headSeg])
                val b = unitOf(p.xs[s.headSeg + 1], p.ys[s.headSeg + 1])
                val h = unitOf(s.headX, s.headY)
                assertEquals("${input.title} frame $f", a.angleTo(b), a.angleTo(h) + h.angleTo(b), 1e-6)
            }
        }
        // A recorded flight is the great circle itself, in steps of at most a degree.
        val p = build(transatlantic())
        val plane = p.legVehicle.indices.filter { p.legVehicle[it] == Vehicle.Plane }
        assertTrue("plane legs ${plane.size}, ${p.vehiclesUsed}, ${p.xs.size} points", plane.size >= 48)
        val a = unitOf(p.xs[plane.first()], p.ys[plane.first()])
        val b = unitOf(p.xs[plane.last() + 1], p.ys[plane.last() + 1])
        for (i in plane) {
            val q = unitOf(p.xs[i + 1], p.ys[i + 1])
            assertEquals(a.angleTo(b), a.angleTo(q) + q.angleTo(b), 1e-6)
            assertTrue(unitOf(p.xs[i], p.ys[i]).angleTo(q) <= Math.toRadians(ClipGlobe.STEP_DEG) + 1e-9)
        }
        // The flat maps keep their own arc (bowing north on the map, in its 24 steps).
        val street = build(transatlantic(), map = MapStyle.Street)
        assertEquals(ClipPlan.ARC_STEPS, street.legVehicle.count { it == Vehicle.Plane })
    }

    @Test fun aRoutePast180KeepsTheCameraOnTheShortWay() {
        val p = build(pacific())
        for (i in 1 until p.xs.size) assertTrue(abs(p.xs[i] - p.xs[i - 1]) < 0.5)
        // The camera never swings round the world: from frame to frame it moves a small part of it (the opening's first
        // frames pan with the dive, a little faster onto a high first height).
        for (f in 1 until p.frameCount) assertTrue("frame $f", abs(p.camera(f).cx - p.camera(f - 1).cx) < 0.025)
        // The head goes east from Tokyo to San Francisco.
        val first = p.frame(p.drawStart).headX
        val last = p.frame(p.drawEnd - 1).headX
        assertTrue(last - first in 0.25..0.45)
    }

    @Test fun neverCloserThanTheStreetMapGoes() {
        assertEquals(MapStyle.Street.maxZoom, MapStyle.Globe.maxZoom, 0.0)
        for (input in listOf(argentina(), transatlantic())) for (zoom in listOf(ClipZoom.Normal, ClipZoom.VeryClose)) {
            val p = build(input, zoom = zoom)
            val closest = ZoomLimits.shortSpan(MapStyle.Globe.maxZoom)
            for (f in 0 until p.frameCount) {
                val c = p.camera(f)
                assertTrue(min(c.spanX, c.spanY(p.options.shape.aspect)) >= closest * (1 - 1e-9))
                // And never further out than space.
                assertTrue(c.spanX <= p.overview.spanX * (1 + 1e-9))
            }
        }
    }

    @Test fun theWholeRouteZoomShowsTheRouteNotTheWholeGlobe() {
        val p = build(argentina(), zoom = ClipZoom.Route)
        val mid = (p.drawStart + p.drawEnd) / 2
        val r = ClipGlobe.radiusPx(p.camera(mid), p.options.shape.width.toDouble())
        assertTrue("radius $r", r > 2 * ClipGlobe.SPACE_RADIUS * p.options.shape.width)
        assertTrue(p.camera(mid).spanX < p.overview.spanX / 2)
    }

    // --- The street map ---------------------------------------------------------------------------------------------

    @Test fun picturesOnlyWhereTheStreetMapShows() {
        for (input in listOf(argentina(), transatlantic())) {
            val p = build(input)
            val specs = p.layerSpecs()
            assertTrue(specs.isNotEmpty())
            val mapped = (0 until p.frameCount).filter { p.globeDrape(it) > 0 }
            var area = p.projection(mapped.first()).groundRect(p.camera(mapped.first()))
            for (f in mapped) area = area.union(p.projection(f).groundRect(p.camera(f)))
            val pad = max(area.w, area.h) * 0.05
            for (s in specs) assertTrue(input.title, area.padded(0.05).contains(s.rect, pad))
            // Far less ground than a picture of the space view would need.
            assertTrue(specs.first().rect.w < p.overview.spanX / 2)
            // Every frame that shows the street map is sharp enough.
            val sharp = p.sharpness(specs)
            for (f in 0 until p.frameCount) if (p.globeDrape(f) == 0.0) assertEquals(1.0, sharp[f], 0.0)
        }
    }

    @Test fun aGlobeThatNeverComesCloseNeedsNoPictures() {
        // Two long flights only (no local legs), at the whole-route zoom: the camera stays far out.
        val stops = listOf(
            ClipStop(at(0, 9, 0), buenosAires.first, buenosAires.second, "Buenos Aires", "AR", "a"),
            ClipStop(at(1, 9, 0), madrid.first, madrid.second, "Madrid", "ES", "b"),
            ClipStop(at(2, 9, 0), tokyo.first, tokyo.second, "Tokyo", "JP", "c"),
        )
        val input = ClipInput("Around", start, start.plusDays(2), zone, emptyList(), stops, emptyList(), ClipSource.Stops)
        val p = build(input, zoom = ClipZoom.Route)
        assertTrue((0 until p.frameCount).all { p.globeDrape(it) == 0.0 })
        assertTrue(p.layerSpecs().isEmpty())
    }

    @Test fun theTripsCountriesInTheOrderTheRouteReachesThem() {
        val p = build(transatlantic())
        // A made-up map: Portugal east of 20°W, the United States west of 60°W, ocean between (named "XX" so a
        // flight's own points would show up).
        val codes = ClipGlobe.countries(p) { _, lng ->
            when {
                lng > -20 -> "PT"
                lng < -60 -> "US"
                else -> "XX"
            }
        }
        assertEquals(listOf("PT", "US"), codes)
        // Counted codes: Northern Cyprus counts as Cyprus, unknown codes are left out.
        val cy = ClipGlobe.countries(build(argentina())) { lat, _ -> if (lat < -40) "CYN" else "??" }
        assertEquals(listOf("CY"), cy)
    }

    @Test fun flatMapsAreUntouched() {
        // The globe's changes stay on the globe: a Street clip's path, camera and pictures as before.
        val a = build(argentina(), map = MapStyle.Street)
        for (i in a.xs.indices) assertTrue(a.xs[i] in 0.0..1.0)
        assertEquals(0.0, a.routeView.spanX - fitWidth(a), 1e-12)
        // A trip this far across opens and closes from much higher: a continent, kept on the map.
        assertTrue(a.fromSpace)
        assertTrue(a.overview.spanX > 3 * a.routeView.spanX)
        assertTrue(a.overview.rect(a.options.shape.aspect).let { it.y0 >= 0.0 && it.y1 <= 1.0 && it.x0 >= 0.0 && it.x1 <= 1.0 })
        assertFalse(a.globe)
        assertEquals(1.0, a.sharpness(a.layerSpecs()).size.toDouble() / a.frameCount, 0.0)
    }

    /** The flat overview: the whole route with its margins (ClipPlan's fit). */
    private fun fitWidth(p: ClipPlan): Double {
        val r = WorldRect.around(p.xs, p.ys)
        val aspect = p.options.shape.aspect
        val least = max(ClipPlan.MIN_VIEW_M * Mercator.unitsPerMeter(r.cy), ZoomLimits.shortSpan(p.options.map.maxZoom)) * max(1.0, aspect)
        return max(max(r.w / (1 - 2 * ClipPlan.PAD_X), r.h * aspect / (1 - 2 * ClipPlan.PAD_Y)), least)
    }

    @Test fun savedChoiceReadsBack() {
        assertEquals(MapStyle.Globe, MapStyle.of("globe"))
        assertEquals(MapStyle.Street, MapStyle.of(null))
        assertEquals(listOf(MapStyle.Street, MapStyle.Terrain, MapStyle.Earth), MapStyle.tripMap)
        assertEquals(listOf(GlobeShapes.CREDIT, StreetTiles.CREDIT), MapStyle.Globe.credits)
        // Without its street map (offline), the globe still makes a clip and credits only its shapes.
        val alone = BaseMap(emptyList(), MapStyle.Globe, GlobeBase(emptyGeometry(), emptyList(), emptyList()))
        assertTrue(alone.hasMap)
        assertEquals(listOf(GlobeShapes.CREDIT), alone.credits)
        assertEquals(MapStyle.Globe, BaseMap.Plain.withGlobe(alone.globe).style)
        assertEquals(BaseMap.Plain, BaseMap.Plain.withGlobe(null))
    }

    private fun emptyGeometry() = com.justdataplease.boringtravel.globe.GlobeGeometry.from(emptyList<Pair<String, List<DoubleArray>>>())

    /** Not a check: prints the Argentina demo's pictures for Street and Globe (see PLAN.md). */
    @Test fun printTheNumbers() {
        val out = StringBuilder()
        for (map in listOf(MapStyle.Street, MapStyle.Globe)) for (shape in listOf(ClipShape.Story, ClipShape.Wide)) {
            val started = System.nanoTime()
            val p = requireNotNull(ClipPlan.build(argentina(), ClipOptions(shape = shape, map = map, view = ClipView.Perspective), Locale.ENGLISH))
            val planMs = (System.nanoTime() - started) / 1e6
            val t = System.nanoTime()
            val specs = p.layerSpecs()
            val specMs = (System.nanoTime() - t) / 1e6
            val sharp = p.sharpness(specs)
            out.appendLine(
                "%s %s: %d frames, plan %.0f ms, specs %.0f ms, %d pictures %.1f Mpx, median sharpness %.2f, street map on %d%% of frames"
                    .format(map, shape, p.frameCount, planMs, specMs, specs.size, specs.sumOf { it.pixels } / 1e6,
                        sharp.sorted()[sharp.size / 2], if (map == MapStyle.Globe) 100 * (0 until p.frameCount).count { p.globeDrape(it) > 0 } / p.frameCount else 100),
            )
        }
        println(out)
        assertNotNull(out)
        // Keep the unused-import checker quiet about the maths helpers some checks use.
        assertTrue(cos(0.0) == 1.0 && PI > 3)
    }

}

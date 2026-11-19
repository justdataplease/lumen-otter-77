package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.SampleArgentina
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The follow camera ([CameraMode.Follow]) over real-shaped trips: a city walk, a 2,100 km flight, and the 31-day Argentina demo. */
class FollowCameraTest {
    /** Set lengths for the camera checks (the app's length comes from the trip and the speed). */
    private val LENGTHS = listOf(15, 30, 60)
    private val zone = ZoneId.of("Europe/Athens")
    private val start = LocalDate.of(2026, 6, 16)

    private fun at(day: Int, h: Int, m: Int) = start.plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    /** Points every [everyS] seconds along [path] at [kmh]. */
    private fun walk(path: List<Pair<Double, Double>>, startMs: Long, kmh: Double, everyS: Int = 30): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var t = startMs.toDouble()
        val msPerM = 3_600_000.0 / (kmh * 1000)
        for (i in 0 until path.size - 1) {
            val (a, b) = path[i] to path[i + 1]
            val d = Geo.distanceM(a.first, a.second, b.first, b.second)
            val steps = max(1, (d * msPerM / 1000 / everyS).toInt())
            for (s in 0 until steps) {
                val f = s.toDouble() / steps
                out += GeoPoint(t.toLong(), a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, 8f)
                t += d * msPerM / steps
            }
        }
        out += GeoPoint(t.toLong(), path.last().first, path.last().second, 8f)
        return out
    }

    private val lisbonWalk = listOf(
        38.7078 to -9.1366, 38.7105 to -9.1333, 38.7118 to -9.1302, 38.7131 to -9.1290, 38.7152 to -9.1290,
        38.7139 to -9.1334, 38.7139 to -9.1394, 38.7149 to -9.1406, 38.7110 to -9.1427, 38.7122 to -9.1458,
        38.7140 to -9.1460, 38.7107 to -9.1480, 38.7085 to -9.1440, 38.7078 to -9.1366,
    )

    /** One morning walking around Lisbon (≈ 7 km). */
    private fun cityWalk() = ClipInput(
        title = "Lisbon on foot", startDate = start, endDate = start, zone = zone,
        points = walk(lisbonWalk, at(0, 9, 0), 4.5), stops = emptyList(), photos = emptyList(),
    )

    /** A walk in Athens, the flight to Paris (≈ 2,100 km) and into town, a walk in Paris the next day. */
    private fun flight(): ClipInput {
        val athens = listOf(37.9715 to 23.7257, 37.9760 to 23.7300, 37.9838 to 23.7275, 37.9790 to 23.7210)
        val paris = listOf(48.8530 to 2.3499, 48.8566 to 2.3412, 48.8606 to 2.3376, 48.8584 to 2.3200)
        return ClipInput(
            title = "Athens to Paris", startDate = start, endDate = start.plusDays(2), zone = zone,
            points = walk(athens, at(0, 10, 0), 4.5) +
                listOf(GeoPoint(at(1, 9, 0), 37.9364, 23.9445), GeoPoint(at(1, 12, 15), 49.0097, 2.5479), GeoPoint(at(1, 13, 30), paris[0].first, paris[0].second)) +
                walk(paris, at(2, 10, 0), 4.5),
            stops = listOf(
                ClipStop(at(0, 9, 0), athens[0].first, athens[0].second, "Plaka, Athens", "GR"),
                ClipStop(at(1, 13, 0), paris[0].first, paris[0].second, "Le Marais, Paris", "FR"),
            ),
            photos = emptyList(),
        )
    }

    /** The demo's planned route: 31 days from Buenos Aires to Santiago with four flights. */
    private fun argentina(): ClipInput {
        val plan = SampleArgentina.plan(LocalDate.of(2026, 10, 25), 0L)
        return ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleArgentina.ZONE)
            .getValue(ClipSource.Planned)
    }

    private fun build(input: ClipInput, seconds: Int = 30, shape: ClipShape = ClipShape.Story, view: ClipView = ClipView.Flat, map: MapStyle = MapStyle.Street) =
        requireNotNull(ClipPlan.build(input, ClipOptions(seconds = seconds, shape = shape, view = view, map = map, camera = CameraMode.Follow), Locale.ENGLISH))

    /** Ground metres on the frame's short side at frame [f]. */
    private fun shortSideM(p: ClipPlan, f: Int): Double {
        val c = p.camera(f)
        val short = min(c.spanX, c.spanY(p.options.shape.aspect))
        return short / Mercator.unitsPerMeter(c.cy)
    }

    private val inputs by lazy { listOf(cityWalk(), flight(), argentina()) }

    @Test fun theHeadAlwaysStaysInsideTheFrameWithAMargin() {
        for (input in inputs) for (shape in ClipShape.entries) for (s in LENGTHS) for (view in ClipView.entries) {
            val p = build(input, s, shape, view)
            for (f in 0 until p.frameCount) {
                val st = p.frame(f)
                val r = st.camera.rect(shape.aspect)
                val fx = (st.headX - r.x0) / r.w
                val fy = (st.headY - r.y0) / r.h
                assertTrue("${input.title} $shape $s s $view frame $f head at $fx,$fy", fx in 0.05..0.95 && fy in 0.05..0.95)
                if (view == ClipView.Perspective) {
                    val x = (st.headX - st.camera.cx) / st.camera.spanX * shape.width + shape.width / 2.0
                    val y = (st.headY - st.camera.cy) / st.camera.spanX * shape.width + shape.height / 2.0
                    val screen = p.projection(f).project(x, y)
                    assertTrue("${input.title} $shape $s s frame $f on screen ${screen.x},${screen.y}",
                        screen.x in 0.03 * shape.width..0.97 * shape.width && screen.y in 0.03 * shape.height..0.97 * shape.height)
                }
            }
        }
    }

    @Test fun theZoomNeverChangesFasterThanTheLimitAndTheCentreGlides() {
        val limit = ClipPlan.MAX_ZOOM_RATE + 1e-9
        for (input in inputs) for (shape in ClipShape.entries) for (s in LENGTHS) {
            val p = build(input, s, shape)
            for (f in 1 until p.frameCount) {
                val a = p.camera(f - 1)
                val b = p.camera(f)
                val zoom = abs(ln(b.spanX / a.spanX))
                assertTrue("${input.title} $shape $s s frame $f zoom changed by $zoom", zoom <= limit)
                // As a share of the view on each axis (a tall frame pans further up and down than across).
                val span = min(a.spanX, b.spanX)
                val move = max(abs(b.cx - a.cx) / span, abs(b.cy - a.cy) / (span / shape.aspect))
                assertTrue("${input.title} $shape $s s frame $f centre moved $move of the view", move <= 0.08)
            }
        }
    }

    @Test fun aWalkStaysCloseToTheWalker() {
        for (s in LENGTHS) for (shape in ClipShape.entries) {
            val p = build(cityWalk(), s, shape)
            val widths = (p.drawStart + p.fps until p.drawEnd).map { shortSideM(p, it) }.sorted()
            val median = widths[widths.size / 2]
            assertTrue("$s s $shape: median short side $median m", median <= 3_000)
            assertTrue("$s s $shape: widest ${widths.last()} m", widths[(widths.size * 0.9).toInt()] <= 4_000)
            // Never closer than the floor.
            assertTrue(widths.first() >= ClipPlan.MIN_VIEW_M * 0.99)
        }
    }

    @Test fun aWalkDoesNotPump() {
        for (s in LENGTHS) {
            val p = build(cityWalk(), s)
            // Count the zoom's turns (in, then out, or back) larger than 5% after the dive.
            var turns = 0
            var dir = 0
            var anchor = ln(p.camera(p.drawStart + p.fps * 2).spanX)
            for (f in p.drawStart + p.fps * 2 until p.drawEnd) {
                val l = ln(p.camera(f).spanX)
                val d = l - anchor
                if (abs(d) < 0.05) continue
                val now = if (d > 0) 1 else -1
                if (dir != 0 && now != dir) turns++
                dir = now
                anchor = l
            }
            assertTrue("$s s: the zoom turned $turns times", turns <= 2)
        }
    }

    @Test fun aLongFlightZoomsOutToShowBothEndsThenBackIn() {
        for (s in LENGTHS) {
            val p = build(flight(), s)
            val planeFrames = (p.drawStart until p.drawEnd).filter { p.frame(it).vehicle == Vehicle.Plane }
            val mid = planeFrames[planeFrames.size / 2]
            val a = p.legVehicle.indexOfFirst { it == Vehicle.Plane }
            val b = p.legVehicle.indexOfLast { it == Vehicle.Plane } + 1
            // Both ends in view: through a short flight, and for a while after take-off on a long one.
            val long = planeFrames.size > FollowCamera.JUMP_HOLD_S * p.fps
            val both = if (long) planeFrames.first() + (FollowCamera.ESTABLISH_S * p.fps / 2).toInt() else mid
            val r = p.camera(both).rect(p.options.shape.aspect)
            assertTrue("$s s: take-off in view", r.contains(p.xs[a], p.ys[a]))
            assertTrue("$s s: landing in view", r.contains(p.xs[b], p.ys[b]))
            // A long flight is then followed: the plane's surroundings, not the whole flight.
            if (long) assertTrue("$s s: following at ${shortSideM(p, mid)} m", shortSideM(p, mid) < shortSideM(p, both))
            assertTrue("$s s: mid-flight ${shortSideM(p, mid)} m", shortSideM(p, mid) >= if (long) 500_000.0 else 1_500_000.0)
            // Back in after landing: the Paris walk comes far closer than the flight (the short close starts the
            // capped pull-back before the walk ends).
            val end = p.days.last().let { d -> (d.fromFrame until d.toFrame).minOf { shortSideM(p, it) } }
            assertTrue("$s s: Paris at $end m", end * 20 <= shortSideM(p, mid))
            if (s == 60) {
                // With time to spare, down to street level on the walks either side (the dive and the climb take ~3 s).
                for (d in listOf(p.days.first(), p.days.last())) {
                    val closest = (d.fromFrame until d.toFrame).minOf { shortSideM(p, it) }
                    assertTrue("${d.label} at $closest m", closest <= 2_000)
                }
            }
        }
    }

    @Test fun earthNeverZoomsPastItsPictures() {
        val closest = ClipPlan.DESIGN_SHORT_DP / (ClipPlan.MAP_TILE_PX * 2.0.pow(ImageryTiles.MAX_ZOOM))
        for (input in inputs) for (shape in ClipShape.entries) {
            val p = build(input, 30, shape, map = MapStyle.Earth)
            for (f in 0 until p.frameCount) {
                val c = p.camera(f)
                assertTrue("${input.title} $shape frame $f", min(c.spanX, c.spanY(shape.aspect)) >= closest * (1 - 1e-9))
            }
        }
        // On a walk the street map goes closer than the pictures allow.
        val street = build(cityWalk(), 60)
        val earth = build(cityWalk(), 60, map = MapStyle.Earth)
        val mid = (street.drawStart + street.drawEnd) / 2
        assertTrue(street.camera(mid).spanX < earth.camera(mid).spanX)
    }

    @Test fun theSameTripAlwaysMakesTheSameCamera() {
        for (input in inputs) {
            val a = build(input, 60, ClipShape.Wide, ClipView.Perspective)
            val b = build(input, 60, ClipShape.Wide, ClipView.Perspective)
            for (f in 0 until a.frameCount) assertEquals(a.camera(f), b.camera(f))
            assertEquals(a.layerSpecs(), b.layerSpecs())
            assertEquals(a.geometryKey, b.geometryKey)
        }
    }

    @Test fun mapPicturesStayWithinTheBudgetAndKeepTheFollowSharp() {
        for (input in inputs) for (view in ClipView.entries) for (s in LENGTHS) {
            val p = build(input, s, ClipShape.Story, view)
            val specs = p.layerSpecs()
            assertTrue(specs.size in 1..ClipPlan.MAX_LAYERS)
            assertTrue(specs.sumOf { it.pixels } <= ClipPlan.LAYER_BUDGET_PX)
            assertTrue(specs.all { it.widthPx in 1..2048 && it.heightPx in 1..2048 && it.pixelRatio in 1f..4f })
            // Coarse to sharp after the overview, so the sharpest covering picture is drawn last.
            assertTrue(specs.drop(1).zipWithNext().all { (x, y) -> x.density <= y.density })
            if (input.title == "Lisbon on foot") {
                // A walk fits the budget: most frames as sharp as planned, none soft.
                val q = p.sharpness(specs)
                val sharp = (p.drawStart until p.drawEnd).count { q[it] >= 0.9 }.toDouble() / (p.drawEnd - p.drawStart)
                assertTrue("$s s $view walk: $sharp of the frames sharp", sharp >= 0.7)
                assertTrue("$s s $view walk: softest ${q.min()}", q.min() >= 0.6)
            }
        }
    }

    /** A recorded drive, Lisbon to Porto (≈ 300 km in 3 h 30), then a walk in Porto. */
    private fun roadTrip(): ClipInput {
        val road = listOf(38.7223 to -9.1393, 39.2369 to -8.6868, 39.7436 to -8.8071, 40.2033 to -8.4103, 40.6405 to -8.6538, 41.1496 to -8.6109)
        val porto = listOf(41.1405 to -8.6113, 41.1452 to -8.6105, 41.1473 to -8.6143, 41.1437 to -8.6200, 41.1405 to -8.6113)
        return ClipInput(
            title = "Up the coast", startDate = start, endDate = start.plusDays(1), zone = zone,
            points = walk(road, at(0, 9, 0), 85.0, everyS = 60) + walk(porto, at(1, 10, 0), 4.5),
            stops = emptyList(), photos = emptyList(),
        )
    }

    /** Not a check: prints the numbers behind the camera's tuning (see PLAN.md, F. Trip clip). */
    @Test fun printTheNumbers() {
        val out = StringBuilder()
        for (input in inputs + roadTrip()) for (s in LENGTHS) for (view in ClipView.entries) {
            val p = build(input, s, ClipShape.Story, view)
            val draw = p.drawStart until p.drawEnd
            val byVehicle = draw.groupBy { p.frame(it).vehicle }.mapValues { (_, fs) ->
                val w = fs.map { shortSideM(p, it) / 1000 }.sorted()
                "%.1f km median (%.1f–%.1f)".format(w[w.size / 2], w.first(), w.last())
            }
            val specs = p.layerSpecs()
            val q = p.sharpness(specs)
            val qd = draw.map { q[it] }
            var outFrames = 0
            for (f in 1 until p.frameCount) if (p.camera(f).spanX > p.camera(f - 1).spanX * 1.0005) outFrames++
            out.appendLine(
                "${input.title} ${s}s $view: overview %.0f km; %s; layers %d (%.1f Mpx); sharp≥0.9 %.0f%%, ≥0.5 %.0f%%, min %.2f; zooming-out frames %d".format(
                    min(p.overview.spanX, p.overview.spanY(p.options.shape.aspect)) / Mercator.unitsPerMeter(p.overview.cy) / 1000,
                    byVehicle.entries.joinToString("; ") { "${it.key}: ${it.value}" },
                    specs.size, specs.sumOf { it.pixels } / 1e6,
                    100.0 * qd.count { it >= 0.9 } / qd.size, 100.0 * qd.count { it >= 0.5 } / qd.size, qd.min(), outFrames,
                ),
            )
        }
        // The flight: how long the camera climbs before take-off and comes down after landing.
        for (s in LENGTHS) {
            val p = build(flight(), s)
            val plane = (p.drawStart until p.drawEnd).filter { p.frame(it).vehicle == Vehicle.Plane }
            val grows = { f: Int -> p.camera(f).spanX > p.camera(f - 1).spanX * 1.0005 }
            val shrinks = { f: Int -> p.camera(f).spanX < p.camera(f - 1).spanX / 1.0005 }
            var climbFrom = plane.first()
            while (climbFrom > 1 && !grows(climbFrom)) climbFrom--
            val climbEnd = climbFrom
            while (climbFrom > 1 && grows(climbFrom)) climbFrom--
            var down = plane.last() + 1
            while (down < p.frameCount - 1 && !shrinks(down)) down++
            val downFrom = down
            while (down < p.frameCount - 1 && shrinks(down)) down++
            out.appendLine(
                "Flight ${s}s: ${plane.size} plane frames; climb ${climbEnd - climbFrom} frames ending ${plane.first() - climbEnd} before take-off; " +
                    "down ${down - downFrom} frames starting ${downFrom - plane.last() - 1} after landing; mid-flight %.0f km, before %.1f km, after %.1f km".format(
                        shortSideM(p, plane[plane.size / 2]) / 1000, shortSideM(p, climbFrom) / 1000, shortSideM(p, down) / 1000),
            )
        }
        println(out)
    }
}

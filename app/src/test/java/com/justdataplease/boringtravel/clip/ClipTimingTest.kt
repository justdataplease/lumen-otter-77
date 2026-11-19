package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.SampleArgentina
import com.justdataplease.boringtravel.data.SampleKyoto
import com.justdataplease.boringtravel.data.SampleLisbon
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.TravelTime
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.trip.LegEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The clip's length from the trip's travel time, the speeds, and each leg's screen time by its vehicle. */
class ClipTimingTest {
    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2031, 5, 1)

    /** A planned trip through [places] (lat to lng), each leg by the vehicle chosen in [modes]. */
    private fun planned(places: List<Pair<Double, Double>>, modes: List<Vehicle>): ClipInput {
        val trip0 = Trip("t", "Legs", createdAt = 1, updatedAt = 1)
        val items = places.mapIndexed { i, (lat, lng) ->
            PlanItem("p$i", trip0.id, day, i.toDouble(), title = "Place $i", lat = lat, lng = lng, createdAt = 1, updatedAt = 1)
        }
        val raw = modes.foldIndexed(null as String?) { i, json, v -> TravelModes.set(json, RouteLegKey(RouteLegSource.Planned, "p$i", "p${i + 1}"), v) }
        return ClipSources.available(trip0.copy(travelModes = raw), emptyList(), emptyList(), items, emptyList(), zone).getValue(ClipSource.Planned)
    }

    private fun framesPerLeg(p: ClipPlan): Map<Int, Int> = (p.drawStart until p.drawEnd).groupingBy { p.frame(it).headSeg }.eachCount()

    @Test fun theClipTimesLegsWithThePlansOwnEstimate() {
        // One pace table: the plan's "≈ 3 h" and the clip's screen time.
        for (v in Vehicle.entries) for (d in listOf(800.0, 12_000.0, 300_000.0, 2_000_000.0)) {
            assertEquals(LegEstimate.minutes(v, d), TravelTime.minutes(v, d)?.roundToInt())
        }
        assertEquals(null, TravelTime.minutes(Vehicle.Auto, 1_000.0))
        assertEquals(30.0 + 2_000.0 * 1.05 / 700 * 60, TravelTime.minutes(Vehicle.Plane, 2_000_000.0)!!, 1e-9)
    }

    @Test fun aTrainCrossesTheSameDistanceFasterThanACarAndAFlightIsQuickForItsLength() {
        // Three legs along the equator: 300 km by car, 300 km by train, then 2,000 km by plane.
        val deg = 300.0 / 111.32
        val places = listOf(0.0 to 0.0, 0.0 to deg, 0.0 to 2 * deg, 0.0 to 2 * deg + 2_000.0 / 111.32)
        val p = requireNotNull(ClipPlan.build(planned(places, listOf(Vehicle.Car, Vehicle.Train, Vehicle.Plane)), ClipOptions(seconds = 30), Locale.ENGLISH))
        val legs = framesPerLeg(p)
        val car = legs.getValue(0)
        val train = legs.getValue(1)
        val plane = legs.getValue(2)
        assertTrue("train $train vs car $car", train < car * 0.85)
        assertTrue("plane $plane vs car $car", plane < car)
        // In proportion to the estimates (beyond the per-leg floor): car 6 h 14, train 4 h 41, plane 3 h 30, each
        // counting a little less past four hours.
        val floor = p.fps / 2
        fun eff(v: Vehicle) = ClipTiming.effectiveMinutes(TravelTime.minutes(v, 300_000.0)!!)
        assertEquals(eff(Vehicle.Train) / eff(Vehicle.Car), (train - floor).toDouble() / (car - floor), 0.03)
        // The same trip all by car: the second leg takes as long as the first.
        val byCar = framesPerLeg(requireNotNull(ClipPlan.build(planned(places, List(3) { Vehicle.Car }), ClipOptions(seconds = 30), Locale.ENGLISH)))
        assertTrue(abs(byCar.getValue(0) - byCar.getValue(1)) <= 2)
    }

    @Test fun shortHopsKeepTheirFloor() {
        // A 300 m walk between two long drives still gets half a second.
        val places = listOf(0.0 to 0.0, 0.0 to 2.0, 0.0 to 2.0027, 0.0 to 4.0)
        val p = requireNotNull(ClipPlan.build(planned(places, listOf(Vehicle.Car, Vehicle.Walk, Vehicle.Car)), ClipOptions(seconds = 15), Locale.ENGLISH))
        assertTrue(framesPerLeg(p).getValue(1) >= p.fps / 2)
    }

    @Test fun aRecordingIsTimedByItsMovingTime() {
        // An hour's walk (4.5 km), three hours sitting in a café, then an hour's drive (65 km), the same day.
        val t0 = day.atStartOfDay(zone).toInstant().toEpochMilli() + 9 * 3_600_000L
        val walk = (0..60).map { m -> GeoPoint(t0 + m * 60_000L, 38.70, -9.15 + m * 0.00086, 8f) }
        val cafe = GeoPoint(t0 + 4 * 3_600_000L, 38.70, -9.15 + 60 * 0.00086 + 0.0003, 8f)
        val drive = (1..60).map { m -> GeoPoint(cafe.time + m * 60_000L, 38.70, cafe.lng + m * 0.0124, 8f) }
        val input = ClipInput("A day out", day, day, zone, walk + cafe + drive, emptyList(), emptyList())
        val p = requireNotNull(ClipPlan.build(input, ClipOptions(seconds = 30), Locale.ENGLISH))
        val byVehicle = (p.drawStart until p.drawEnd).groupingBy { p.frame(it).vehicle }.eachCount()
        val walking = byVehicle.getValue(Vehicle.Walk)
        val driving = byVehicle.getValue(Vehicle.Car)
        // Equal moving time, equal screen time (by distance the drive would get 14 times as long); the café gets none.
        assertTrue("walk $walking, drive $driving", walking.toDouble() / driving in 0.75..1.33)
        assertEquals(2.0, p.travelHours, 0.1)
    }

    @Test fun lengthsAtEachSpeedAndTheClamps() {
        for (hours in listOf(0.5, 5.0, 20.0, 60.0, 200.0)) {
            val lengths = ClipTiming.SPEEDS.map { ClipTiming.seconds(hours, it) }
            assertTrue("$hours h: $lengths", lengths.zipWithNext().all { (a, b) -> b <= a })
            assertTrue(lengths.all { it in ClipTiming.MIN_SECONDS..ClipTiming.MAX_SECONDS })
            // The drawing itself is divided by the speed; the opening and the end card keep their own time.
            for (k in ClipTiming.SPEEDS) assertEquals(ClipTiming.drawSeconds(hours, 1) / k, ClipTiming.drawSeconds(hours, k), 1e-9)
        }
        assertEquals(ClipTiming.MIN_SECONDS, ClipTiming.seconds(0.0, 1), 1e-9)
        assertEquals(ClipTiming.MIN_SECONDS, ClipTiming.seconds(1.0, 10), 1e-9)
        assertEquals(ClipTiming.MAX_SECONDS, ClipTiming.seconds(10_000.0, 1), 0.01)
        // A very long trip still gets four different lengths.
        assertEquals(4, ClipTiming.SPEEDS.map { ClipTiming.seconds(10_000.0, it).toInt() }.distinct().size)
        // Opening ≥ 1.5 s and close ≥ 3 s around even the shortest drawing.
        val shortest = ClipTiming.seconds(1.0, 10)
        assertTrue(shortest - ClipTiming.drawSeconds(1.0, 10) >= 4.5 - 1e-9)
        assertEquals("1:12", ClipTiming.clock(72.4))
        assertEquals("0:06", ClipTiming.clock(6.0))
    }

    @Test fun freeClipsRunUpToAMinute() {
        assertTrue(ClipTiming.needsPro(75.0))
        assertTrue(!ClipTiming.needsPro(60.0))
        // A free clip of a long trip moves to the slowest speed within a minute; Pro keeps its choice.
        val hours = 60.0
        val free = ClipTiming.freeSpeed(hours, 1, isPro = false)
        assertTrue(free > 1 && !ClipTiming.needsPro(ClipTiming.seconds(hours, free)))
        assertEquals(1, ClipTiming.freeSpeed(hours, 1, isPro = true))
        assertEquals(5, ClipTiming.freeSpeed(hours, 5, isPro = false))
        assertEquals(1, ClipTiming.freeSpeed(2.0, 1, isPro = false))
    }

    private fun argentina(): ClipInput {
        val plan = SampleArgentina.plan(LocalDate.of(2026, 10, 25), 0L)
        return ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleArgentina.ZONE).getValue(ClipSource.Planned)
    }

    @Test fun theArgentinaDemoRunsAboutAMinuteAndAHalfAtX1() {
        val p = requireNotNull(ClipPlan.build(argentina(), ClipOptions(length = null), Locale.ENGLISH))
        assertTrue("x1 runs ${p.seconds} s from ${p.travelHours} h", p.seconds in 60.0..90.0)
        for (k in ClipTiming.SPEEDS) {
            val q = requireNotNull(ClipPlan.build(argentina(), ClipOptions(length = null, speed = k), Locale.ENGLISH))
            assertEquals(ClipTiming.seconds(p.travelHours, k), q.seconds, 1.0 / q.fps)
        }
    }

    @Test fun photosAndTheEndCardStillWorkAtX10() {
        val base = argentina()
        val withPhotos = base.copy(photos = base.stops.take(6).mapIndexed { i, s -> ClipPhoto("p$i", null, s.lat, s.lng) })
        val p = requireNotNull(ClipPlan.build(withPhotos, ClipOptions(length = null, speed = 10), Locale.ENGLISH))
        assertTrue(p.seconds >= ClipTiming.MIN_SECONDS)
        assertTrue("photos ${p.photos.size}", p.photos.isNotEmpty())
        for (pop in p.photos) {
            assertTrue(pop.toFrame <= p.endCardStart)
            assertTrue("held ${pop.toFrame - pop.fromFrame} frames", pop.toFrame - pop.fromFrame >= (1.6 * p.fps).toInt())
        }
        assertTrue(p.endCardStart <= p.frameCount - (0.8 * p.fps).toInt())
        assertEquals(1.0, p.frame(p.frameCount - 1).endAlpha, 1e-9)
        assertEquals(0.0, p.frame(p.drawEnd).endAlpha, 1e-9)
    }

    /** Not a check: the lengths of the demo trips at each speed (see PLAN.md, F. Trip clip). */
    @Test fun printTheLengths() {
        val out = StringBuilder()
        fun line(name: String, input: ClipInput) {
            val p = requireNotNull(ClipPlan.build(input, ClipOptions(), Locale.ENGLISH))
            out.appendLine("$name: %.1f h of travel → ".format(p.travelHours) +
                ClipTiming.SPEEDS.joinToString("  ") { "×$it ${ClipTiming.clock(ClipTiming.seconds(p.travelHours, it))}" })
        }
        line("Argentina (31 days, planned)", argentina())
        val kyoto = SampleKyoto.plan(LocalDate.of(2027, 10, 3), 0L)
        line("Kyoto & Osaka (12 days, planned)", ClipSources.available(kyoto.trip, emptyList(), emptyList(), kyoto.items, emptyList(), zone).getValue(ClipSource.Planned))
        val lisbon = SampleLisbon.plan(LocalDate.of(2026, 6, 1), 0L)
        line("Lisbon & Porto (9 days, planned)", ClipSources.available(lisbon.trip, emptyList(), emptyList(), lisbon.items, emptyList(), SampleLisbon.ZONE).getValue(ClipSource.Planned))
        for (h in listOf(1.5, 5.0, 12.0)) out.appendLine("$h h: " + ClipTiming.SPEEDS.joinToString("  ") { "×$it ${ClipTiming.clock(ClipTiming.seconds(h, it))}" })
        println(out)
    }
}

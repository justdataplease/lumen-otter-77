package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The marker's heading (owner, 2026-11-08: "when moving should have correct direction"): along the route by distance,
 * small wobbles left alone, corners turned at the vehicle's pace, U-turns pivoted, a wait facing as it arrived and
 * turning to the way on at its end; on the globe the way at the head itself, never flipped at the rim.
 */
class HeadingTest {
    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2031, 5, 1)

    /** A planned trip through [places] (lat to lng), each leg by the vehicle in [modes]. */
    private fun planned(places: List<Pair<Double, Double>>, modes: List<Vehicle>): ClipInput {
        val trip0 = Trip("t", "Legs", createdAt = 1, updatedAt = 1)
        val items = places.mapIndexed { i, (lat, lng) ->
            PlanItem("p$i", trip0.id, day, i.toDouble(), title = "Place $i", lat = lat, lng = lng, createdAt = 1, updatedAt = 1)
        }
        val raw = modes.foldIndexed(null as String?) { i, json, v -> TravelModes.set(json, RouteLegKey(RouteLegSource.Planned, "p$i", "p${i + 1}"), v) }
        return ClipSources.available(trip0.copy(travelModes = raw), emptyList(), emptyList(), items, emptyList(), zone).getValue(ClipSource.Planned)
    }

    private fun build(input: ClipInput, options: ClipOptions = ClipOptions()) = requireNotNull(ClipPlan.build(input, options, Locale.ENGLISH))

    /** The smallest angle between two bearings. */
    private fun gap(a: Double, b: Double) = abs(ClipPlan.turnBy(a, b))

    private fun turns(p: ClipPlan, frames: IntRange) = frames.drop(1).map { gap(p.frame(it - 1).bearing, p.frame(it).bearing) }

    @Test fun cornersTurnAtTheVehiclesPace() {
        // East, then a corner north.
        val l = listOf(0.0 to 0.0, 0.0 to 0.3, 0.3 to 0.3)
        val train = build(planned(l, listOf(Vehicle.Train, Vehicle.Train)))
        val walk = build(planned(l, listOf(Vehicle.Walk, Vehicle.Walk)))
        for (p in listOf(train, walk)) {
            val journey = p.drawStart until p.drawEnd
            val first = journey.filter { p.frame(it).headSeg == 0 }
            val second = journey.filter { p.frame(it).headSeg == 1 }
            assertEquals(90.0, p.frame(first[first.size / 2]).bearing, 3.0)
            assertEquals(0.0, gap(p.frame(second[second.size * 3 / 4]).bearing, 0.0), 3.0)
        }
        // A train turns at most 120° a second; a walker turns faster.
        val trainMax = turns(train, train.drawStart until train.drawEnd).max()
        assertTrue("train $trainMax° a frame", trainMax <= ClipPlan.turnCap(Vehicle.Train) / train.fps + 1e-6)
        assertTrue(turns(walk, walk.drawStart until walk.drawEnd).max() > trainMax + 1)
        assertEquals(90.0, ClipPlan.turnCap(Vehicle.Plane), 0.0)
        assertEquals(300.0, ClipPlan.turnCap(Vehicle.Walk), 0.0)
    }

    @Test fun aUTurnPivotsQuickly() {
        // East and straight back (the walker turns round at the end of the pier).
        val p = build(planned(listOf(0.0 to 0.0, 0.0 to 0.05, 0.0 to 0.0001), listOf(Vehicle.Walk, Vehicle.Walk)))
        val journey = p.drawStart until p.drawEnd
        val turning = journey.count { gap(p.frame(it).bearing, 90.0) > 20 && gap(p.frame(it).bearing, 270.0) > 20 }
        assertTrue("$turning frames mid-turn", turning <= (ClipPlan.HEADING_PIVOT_S * p.fps).toInt() + 4)
        // Without the pivot a walker would take 0.6 s for half a turn.
        assertTrue(turning < (180.0 / ClipPlan.turnCap(Vehicle.Walk) * p.fps).toInt())
        assertEquals(270.0, p.frame(p.drawEnd - p.fps / 2).bearing, 5.0)
    }

    @Test fun gpsWobblesDontTurnTheTraveller() {
        // A walk due east, every point up to about 3 m off the line, this way or that (as GPS does).
        val t0 = day.atStartOfDay(zone).toInstant().toEpochMilli() + 9 * 3_600_000L
        val points = (0..90).map { i ->
            GeoPoint(t0 + i * 30_000L, 38.70 + ((i * 7919) % 13 - 6) / 6.0 * 0.000025, -9.15 + i * 0.00043, 6f)
        }
        val p = build(ClipInput("Wobbly", day, day, zone, points, emptyList(), emptyList()))
        val journey = p.drawStart until p.drawEnd
        val middle = journey.first + journey.count() / 4 until journey.last - journey.count() / 4
        for (f in middle) assertEquals("frame $f", 90.0, p.frame(f).bearing, 8.0)
        // Raw, a step between points swings up to some 8° either way; the marker barely moves.
        assertTrue("turned ${turns(p, middle).sum()}°", turns(p, middle).sum() < 15.0)
    }

    @Test fun aWaitFacesAsItArrivedThenTurnsToTheWayOn() {
        // East for an hour, a photo at the corner (the journey waits), then north.
        val t0 = day.atStartOfDay(zone).toInstant().toEpochMilli() + 9 * 3_600_000L
        val east = (0..60).map { GeoPoint(t0 + it * 60_000L, 38.70, -9.15 + it * 0.0008, 6f) }
        val north = (1..60).map { GeoPoint(east.last().time + it * 60_000L, 38.70 + it * 0.0006, east.last().lng, 6f) }
        val corner = east.last()
        val input = ClipInput("Corner", day, day, zone, east + north, emptyList(), listOf(ClipPhoto("c", corner.time, corner.lat, corner.lng)))
        val p = build(input, ClipOptions(photoHoldSeconds = 2.0, photoGapSeconds = 0.0))
        val hold = p.photos.single { it.held }
        val pre = (ClipPlan.HEADING_PRETURN_S * p.fps).toInt()
        // Still, facing as it arrived, until the last moments of the wait...
        val arrived = p.frame(hold.fromFrame).bearing
        for (f in hold.fromFrame until hold.toFrame - pre - 1) assertEquals("frame $f", arrived, p.frame(f).bearing, 1e-9)
        // ...then turned to the way on by the time it moves on (round the corner: north, a little east of it), so it
        // leaves without a jerk, and soon faces north.
        val leaving = p.frame(hold.toFrame).bearing
        assertTrue("left at $leaving", gap(leaving, 0.0) < 40.0)
        assertTrue(gap(p.frame(hold.toFrame - 1).bearing, leaving) <= ClipPlan.turnCap(com.justdataplease.boringtravel.model.Vehicle.Walk) / p.fps + 1e-9)
        assertEquals(0.0, gap(p.frame(hold.toFrame + p.fps).bearing, 0.0), 5.0)
        assertTrue("arrived at $arrived", gap(arrived, leaving) > 10.0)
    }

    // --- The globe ----------------------------------------------------------------------------------------------------

    /** Where a tiny step of [d] radians along compass [bearing] from [lat], [lng] lands (great circle). */
    private fun step(lat: Double, lng: Double, bearing: Double, d: Double): Unit3 {
        val p = Math.toRadians(lat)
        val l = Math.toRadians(lng)
        val b = Math.toRadians(bearing)
        val p2 = asin(sin(p) * cos(d) + cos(p) * sin(d) * cos(b))
        val l2 = l + atan2(sin(b) * sin(d) * cos(p), cos(d) - sin(p) * sin(p2))
        return Unit3.of(Math.toDegrees(p2), Math.toDegrees(l2))
    }

    @Test fun onTheGlobeTheWayIsTheSpheresOwnAtTheHead() {
        val view = GlobeView(10.0, 20.0, 500.0, 500.0, 400.0)
        for ((lat, lng) in listOf(10.0 to 20.0, 40.0 to 30.0, -20.0 to 70.0, 10.0 to 105.0, 75.0 to 20.0)) {
            for (b in listOf(0.0, 45.0, 90.0, 200.0, 300.0)) {
                val t = view.tangent(lat, lng, b) ?: continue
                assertEquals(1.0, hypot(t.first, t.second), 1e-9)
                // The same way as a tiny step along the bearing, seen in the view.
                val a = Unit3.of(lat, lng)
                val q = step(lat, lng, b, 1e-6)
                val dx = view.x(q) - view.x(a)
                val dy = view.y(q) - view.y(a)
                val len = hypot(dx, dy)
                if (len < 1e-9) continue
                assertEquals("$lat,$lng $b", t.first, dx / len, 1e-4)
                assertEquals("$lat,$lng $b", t.second, dy / len, 1e-4)
            }
        }
        // Near the rim, heading east (round towards the back): a long step went past the rim and came out pointing
        // the wrong way (left); the way at the head points right, out to the rim, as the route does.
        val rim = GlobeView(0.0, 0.0, 500.0, 500.0, 400.0)
        assertTrue(rim.x(Unit3.of(0.0, 93.0)) < rim.x(Unit3.of(0.0, 88.0)))
        val east = requireNotNull(rim.tangent(0.0, 88.0, 90.0))
        assertTrue(east.first > 0.99)
        // Heading straight away from the viewer (east, right on the rim) there is no way on screen.
        assertEquals(null, rim.tangent(0.0, 90.0, 90.0))
        assertNotNull(rim.tangent(0.0, 0.0, 0.0))
        // Every frame of a Globe clip has a heading that the marker can use.
        val trip = planned(listOf(38.7 to -9.1, 40.4 to -3.7, 41.4 to 2.2), listOf(Vehicle.Car, Vehicle.Train))
        val p = build(trip, ClipOptions(map = MapStyle.Globe))
        for (f in 0 until p.frameCount) assertTrue(p.frame(f).bearing.let { it >= 0 && it < 360 })
    }
}

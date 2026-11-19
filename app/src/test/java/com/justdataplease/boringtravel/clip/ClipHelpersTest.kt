package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class EaseTest {
    private val curves = mapOf<String, (Double) -> Double>(
        "inOutCubic" to Ease::inOutCubic,
        "outCubic" to Ease::outCubic,
        "smoothstep" to Ease::smoothstep,
        "travel" to Ease::travel,
    )

    @Test fun curvesRunFromZeroToOneWithoutGoingBack() {
        for ((name, f) in curves) {
            assertEquals(name, 0.0, f(0.0), 1e-12)
            assertEquals(name, 1.0, f(1.0), 1e-12)
            var prev = f(0.0)
            for (i in 1..200) {
                val v = f(i / 200.0)
                assertTrue("$name goes back at ${i / 200.0}", v >= prev - 1e-12)
                prev = v
            }
            assertEquals("$name clamps below", 0.0, f(-1.0), 1e-12)
            assertEquals("$name clamps above", 1.0, f(2.0), 1e-12)
        }
    }

    @Test fun inOutCubicIsSymmetric() {
        assertEquals(0.5, Ease.inOutCubic(0.5), 1e-12)
        for (i in 0..20) {
            val t = i / 20.0
            assertEquals(1 - Ease.inOutCubic(t), Ease.inOutCubic(1 - t), 1e-12)
        }
    }

    @Test fun travelSlowsAtTheEndsButNeverStops() {
        val start = Ease.travel(0.001) / 0.001
        val end = (1 - Ease.travel(0.999)) / 0.001
        assertTrue("starts at half speed, was $start", start in 0.45..0.55)
        assertTrue("ends at half speed, was $end", end in 0.45..0.55)
    }

    @Test fun outBackOvershootsThenSettles() {
        assertEquals(0.0, Ease.outBack(0.0), 1e-9)
        assertEquals(1.0, Ease.outBack(1.0), 1e-9)
        assertTrue((1..99).any { Ease.outBack(it / 100.0) > 1.0 })
    }

    @Test fun windowIsSmoothBetweenItsEdges() {
        assertEquals(0.0, Ease.window(5.0, 10.0, 20.0), 1e-12)
        assertEquals(0.5, Ease.window(15.0, 10.0, 20.0), 1e-12)
        assertEquals(1.0, Ease.window(25.0, 10.0, 20.0), 1e-12)
    }
}

class GeoTest {
    @Test fun haversineMatchesAKnownDistance() {
        // Parikia (Paros) to Naxos port: about 20 km across the strait.
        val d = Geo.distanceM(37.0853, 25.1488, 37.1056, 25.3747)
        assertTrue("was $d", d in 19_800.0..20_400.0)
    }

    @Test fun mercatorRoundTrips() {
        for (lat in listOf(-60.0, -10.0, 0.0, 37.1, 64.1)) assertEquals(lat, Mercator.lat(Mercator.y(lat)), 1e-9)
        for (lng in listOf(-170.0, -9.1, 0.0, 25.4, 179.0)) assertEquals(lng, Mercator.lng(Mercator.x(lng)), 1e-9)
        assertTrue("north is up (smaller y)", Mercator.y(50.0) < Mercator.y(10.0))
    }

    @Test fun bearingsPointTheRightWay() {
        assertEquals(0.0, bearingOf(0.0, -1.0), 1e-9)
        assertEquals(90.0, bearingOf(1.0, 0.0), 1e-9)
        assertEquals(180.0, bearingOf(0.0, 1.0), 1e-9)
        assertEquals(270.0, bearingOf(-1.0, 0.0), 1e-9)
    }

    @Test fun rectToAspectKeepsTheCentreAndGrowsOnlyTheShortSide() {
        val r = WorldRect(0.0, 0.0, 2.0, 1.0).toAspect(9.0 / 16.0)
        assertEquals(1.0, r.cx, 1e-12)
        assertEquals(0.5, r.cy, 1e-12)
        assertEquals(2.0, r.w, 1e-12)
        assertEquals(2.0 * 16 / 9, r.h, 1e-9)
    }

    @Test fun layerBlendShowsCloserPicturesOnlyWhenZoomedInAndWellInside() {
        val layer = WorldRect(0.0, 0.0, 10.0, 10.0)
        val deep = WorldRect(4.0, 4.0, 6.0, 6.0)
        val edge = WorldRect(8.5, 4.0, 10.5, 6.0)
        assertEquals(1.0, LayerBlend.weight(layer, deep, zoom = 3.0), 1e-9)
        assertEquals(0.0, LayerBlend.weight(layer, deep, zoom = 1.0), 1e-9)
        assertEquals(0.0, LayerBlend.weight(layer, edge, zoom = 3.0), 1e-9)
        val near = WorldRect(7.95, 4.0, 9.95, 6.0)
        val w = LayerBlend.weight(layer, near, zoom = 3.0)
        assertTrue("fades near the edge, was $w", w > 0.0 && w < 1.0)
    }
}

class TrackShapingTest {
    private fun p(min: Int, lat: Double, lng: Double, acc: Float? = 10f) = GeoPoint(min * 60_000L, lat, lng, acc)

    @Test fun cleanSortsAndDropsVagueOrRepeatedFixes() {
        val out = TrackShaping.clean(
            listOf(p(2, 37.002, 25.0), p(0, 37.0, 25.0), p(1, 37.001, 25.0, acc = 900f), p(0, 37.0, 25.0), p(3, 37.003, 25.0)),
        )
        assertEquals(listOf(0L, 2L, 3L).map { it * 60_000 }, out.map { it.time })
    }

    @Test fun aLongStayFoldsIntoArrivalAndDeparture() {
        val jitter = (0..20).map { p(it, 37.0 + (it % 3) * 0.00005, 25.0 + (it % 2) * 0.00005) }
        val out = TrackShaping.collapseStays(listOf(p(-5, 36.99, 25.0)) + jitter + p(30, 37.01, 25.0))
        assertEquals(4, out.size)
        assertEquals(0L, out[1].time)
        assertEquals(20 * 60_000L, out[2].time)
        assertEquals(out[1].lat, out[2].lat, 1e-12)
    }

    @Test fun smoothingKeepsTheEndsAndNeverPullsALongJump() {
        val pts = listOf(p(0, 37.0, 25.0), p(1, 37.001, 25.001), p(2, 37.0, 25.002), p(60, 40.0, 25.0))
        val out = TrackShaping.smooth(pts)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
        assertTrue("the middle point is smoothed", out[1].lat < pts[1].lat)
        assertEquals("the point before the jump stays", pts[2], out[2])
    }

    @Test fun simplifyDropsStraightStretchesAndKeepsCorners() {
        val straight = (0..100).map { p(it, 37.0, 25.0 + it * 0.001) }
        assertEquals(listOf(0, 100), TrackShaping.simplify(straight, 5.0))
        val corner = (0..50).map { p(it, 37.0, 25.0 + it * 0.001) } + (1..50).map { p(50 + it, 37.0 + it * 0.001, 25.05) }
        assertEquals(listOf(0, 50, 100), TrackShaping.simplify(corner, 5.0))
    }

    @Test fun simplifyStaysWithinTheTolerance() {
        val wiggle = (0..400).map { p(it, 37.0 + kotlin.math.sin(it / 9.0) * 0.002, 25.0 + it * 0.0005) }
        val tol = 20.0
        val kept = TrackShaping.simplify(wiggle, tol)
        assertTrue(kept.size in 3 until wiggle.size)
        val lat0 = 37.0
        val kx = 111_320.0 * kotlin.math.cos(lat0 * Math.PI / 180)
        for (j in 0 until kept.size - 1) {
            val a = wiggle[kept[j]]
            val b = wiggle[kept[j + 1]]
            for (i in kept[j] + 1 until kept[j + 1]) {
                val q = wiggle[i]
                val d = TrackShaping.pointToSegment(q.lng * kx, q.lat * 110_540.0, a.lng * kx, a.lat * 110_540.0, b.lng * kx, b.lat * 110_540.0)
                assertTrue("point $i is $d m off", d <= tol * 1.05)
            }
        }
    }
}

class VehicleGuessTest {
    /** Points every [everyS] seconds moving east at [kmh], starting at [startS]. */
    private fun run(kmh: Double, legs: Int, everyS: Int = 60, startS: Long = 0, lat: Double = 37.0, lng0: Double = 25.0): List<GeoPoint> {
        val metresPerLeg = kmh / 3.6 * everyS
        val degPerLeg = metresPerLeg / (111_320.0 * kotlin.math.cos(lat * Math.PI / 180))
        return (0..legs).map { GeoPoint((startS + it.toLong() * everyS) * 1000, lat, lng0 + it * degPerLeg) }
    }

    @Test fun speedBands() {
        assertEquals(Vehicle.Walk, VehicleGuess.bySpeed(5.0))
        assertEquals(Vehicle.Bike, VehicleGuess.bySpeed(7.0))
        assertEquals(Vehicle.Bike, VehicleGuess.bySpeed(20.0))
        assertEquals(Vehicle.Car, VehicleGuess.bySpeed(25.0))
        assertEquals(Vehicle.Car, VehicleGuess.bySpeed(110.0))
        assertEquals(Vehicle.Train, VehicleGuess.bySpeed(130.0))
        assertEquals(Vehicle.Train, VehicleGuess.bySpeed(240.0))
        assertEquals(Vehicle.Plane, VehicleGuess.bySpeed(250.0))
    }

    @Test fun steadyMovesAreGuessedBySpeed() {
        assertTrue(VehicleGuess.legs(run(4.5, 30)).all { it == Vehicle.Walk })
        assertTrue(VehicleGuess.legs(run(18.0, 30)).all { it == Vehicle.Bike })
        assertTrue(VehicleGuess.legs(run(80.0, 30)).all { it == Vehicle.Car })
        assertTrue(VehicleGuess.legs(run(180.0, 30)).all { it == Vehicle.Train })
    }

    @Test fun flightsAreFastOrVeryLongJumps() {
        assertTrue(VehicleGuess.isFlight(km = 300.0, kmh = 600.0))
        assertTrue(VehicleGuess.isFlight(km = 700.0, kmh = 90.0))
        assertFalse(VehicleGuess.isFlight(km = 500.0, kmh = 90.0))
        val hop = listOf(GeoPoint(0, 37.94, 23.94), GeoPoint(3_600_000L * 4, 38.77, -9.13))
        assertEquals(listOf(Vehicle.Plane), VehicleGuess.legs(hop))
    }

    @Test fun aLongQuietCrossingIsAFerry() {
        val before = run(4.5, 20)
        val last = before.last()
        // 65 minutes with no points, 20 km further on: a ferry.
        val across = GeoPoint(last.time + 65 * 60_000L, last.lat, last.lng + 20_000 / (111_320.0 * kotlin.math.cos(37.0 * Math.PI / 180)))
        val after = run(4.5, 20, startS = across.time / 1000, lng0 = across.lng).drop(1)
        val legs = VehicleGuess.legs(before + across + after)
        assertEquals(Vehicle.Ferry, legs[20])
        assertTrue(legs.take(20).all { it == Vehicle.Walk })
        assertTrue(legs.drop(21).all { it == Vehicle.Walk })
    }

    @Test fun theSameGapOnAnHourlyTrackIsNotAFerry() {
        // Points every 30 min at 25 km/h: a gap of 30 min is normal here, so it stays on land.
        val sparse = run(25.0, 6, everyS = 1_800)
        assertTrue(VehicleGuess.legs(sparse).none { it == Vehicle.Ferry })
    }

    @Test fun aChosenVehicleOverridesEverything() {
        val pts = run(4.5, 10) + run(800.0, 3, startS = 3_600, lng0 = 26.0).drop(1)
        assertTrue(VehicleGuess.legs(pts, Vehicle.Train).all { it == Vehicle.Train })
    }

    @Test fun shortRunsJoinTheirLongerGroundNeighbour() {
        val v = listOf(Vehicle.Car, Vehicle.Car, Vehicle.Walk, Vehicle.Car, Vehicle.Car)
        val d = doubleArrayOf(5_000.0, 5_000.0, 100.0, 5_000.0, 5_000.0)
        assertTrue(VehicleGuess.mergeShortRuns(v, d, 400.0).all { it == Vehicle.Car })
    }

    @Test fun theWalkToTheGateStaysAWalk() {
        val v = listOf(Vehicle.Plane, Vehicle.Walk, Vehicle.Ferry)
        val d = doubleArrayOf(900_000.0, 100.0, 30_000.0)
        assertEquals(v, VehicleGuess.mergeShortRuns(v, d, 400.0))
    }

    @Test fun aRedLightDoesNotTurnADriveIntoAWalk() {
        val drive = run(60.0, 20, everyS = 30)
        val slow = run(6.0, 2, everyS = 30, startS = drive.last().time / 1000, lng0 = drive.last().lng).drop(1)
        val more = run(60.0, 20, everyS = 30, startS = slow.last().time / 1000, lng0 = slow.last().lng).drop(1)
        assertTrue(VehicleGuess.legs(drive + slow + more).all { it == Vehicle.Car })
    }
}

class ClipTextTest {
    @Test fun fileNamesKeepLettersOfAnyScript() {
        assertEquals("Cyclades-slowly-clip.mp4", ClipText.fileName("Cyclades, slowly"))
        assertEquals("Νάξος-2026-clip.mp4", ClipText.fileName("Νάξος 2026!"))
        assertEquals("trip-clip.mp4", ClipText.fileName("  ...  "))
    }

    @Test fun placeIsThePartAfterTheComma() {
        assertEquals("Naxos", ClipText.place("Halki, Naxos"))
        assertEquals("Lisbon", ClipText.place("Lisbon"))
        assertNull(ClipText.place(" , "))
        assertEquals("Day 4 · Naxos", ClipText.dayLabel(ClipText.dayName(4), "Naxos"))
        assertEquals("Day 4", ClipText.dayLabel(ClipText.dayName(4), null))
    }

    @Test fun clockAndStats() {
        assertEquals("0:09", ClipText.clock(270, 30))
        assertEquals("1:00", ClipText.clock(1800, 30))
        assertEquals("10 days · 46 km", ClipText.stats(10, 45.6, Locale.ENGLISH))
        assertEquals("1 day · 2.5 km", ClipText.stats(1, 2.46, Locale.ENGLISH))
        assertEquals("3 days · 1,234 km", ClipText.stats(3, 1234.4, Locale.ENGLISH))
        assertEquals("2 days", ClipText.stats(2, 0.0, Locale.ENGLISH))
    }

    @Test fun countriesInOrderUpToThree() {
        assertEquals("Greece", ClipText.countries(listOf("GR", "gr"), Locale.ENGLISH))
        assertEquals("Greece, Italy, France +1", ClipText.countries(listOf("GR", "IT", "FR", "ES"), Locale.ENGLISH))
        assertNull(ClipText.countries(emptyList(), Locale.ENGLISH))
    }
}

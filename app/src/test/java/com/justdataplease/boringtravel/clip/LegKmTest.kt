package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * "Count in km" on every leg (owner, 2026-11-11: "for stats can we exclude sections … airplane first I don't want to
 * include it"; "a count in km in every item"): a flight is left out of the km unless switched on, any leg can be.
 */
class LegKmTest {
    private val trip = Trip("t", "Mixed journey", createdAt = 1, updatedAt = 1)
    private val zone = ZoneId.of("UTC")
    private val points = (0..3).map { i ->
        PlanItem("p$i", trip.id, LocalDate.of(2031, 1, 1), i.toDouble(), title = "Place $i", lat = 37.0 + i * .2, lng = 25.0 + i * .1, createdAt = 1, updatedAt = 1)
    }
    private val modes = listOf(Vehicle.Walk, Vehicle.Plane, Vehicle.Car)

    private fun key(i: Int) = RouteLegKey(RouteLegSource.Planned, "p$i", "p${i + 1}")

    private fun tripWith(km: Map<Int, Boolean?> = emptyMap()): Trip {
        var raw: String? = null
        modes.forEachIndexed { i, v -> raw = TravelModes.set(raw, key(i), v) }
        km.forEach { (i, count) -> raw = TravelModes.setKm(raw, key(i), count) }
        return trip.copy(travelModes = raw)
    }

    private fun plan(t: Trip): ClipPlan = requireNotNull(
        ClipPlan.build(ClipSources.available(t, emptyList(), emptyList(), points, emptyList(), zone).getValue(ClipSource.Planned), ClipOptions(), Locale.ENGLISH),
    )

    private fun legKm(p: ClipPlan, i: Int) = (p.realCum[i + 1] - p.realCum[i]) / 1000.0

    @Test fun byDefaultEveryLegButAFlightCounts() {
        for (v in Vehicle.entries) assertEquals(v != Vehicle.Plane, TravelDefault.countsKm(v, null))
        assertTrue(TravelDefault.countsKm(Vehicle.Plane, true))
        assertFalse(TravelDefault.countsKm(Vehicle.Car, false))
    }

    @Test fun aFlightIsLeftOutOfTheStatsLineAndTheEndCardUnlessSwitchedOn() {
        val p = plan(tripWith())
        assertEquals(modes, p.legVehicle)
        assertEquals(p.totalKm - legKm(p, 1), p.countedKm, 1e-6)
        assertEquals(p.countedKm, p.statsAtEnd.km, 1e-9)
        assertEquals(p.countedKm, p.statsAt(p.frameCount - 1).km, 0.05)
        assertTrue(p.endCard.stats, p.endCard.stats.contains(ClipText.km(p.countedKm, Locale.ENGLISH)))

        val on = plan(tripWith(mapOf(1 to true)))
        assertEquals(on.totalKm, on.countedKm, 1e-6)
        assertEquals(on.totalKm, on.statsAtEnd.km, 1e-6)
        // Only the numbers change, so the frames that show them must be drawn again.
        assertFalse(p.sameTimeline(on))
    }

    @Test fun anyLegCanBeLeftOutAndTheCountNeverGoesBack() {
        val p = plan(tripWith(mapOf(2 to false)))
        assertEquals(legKm(p, 0), p.countedKm, 1e-6)
        var last = 0.0
        for (f in 0 until p.frameCount) {
            val km = p.statsAt(f).km
            assertTrue("frame $f", km >= last - 1e-9 && km <= p.countedKm + 1e-9)
            last = km
        }
        // A leg that would count anyway saves nothing: the plain default again.
        assertEquals(plan(tripWith()).countedKm, plan(tripWith(mapOf(0 to true, 2 to true))).countedKm, 1e-9)
    }

    @Test fun theKmSwitchKeepsTheVehicleAndCanGoBackToTheDefault() {
        val k = key(1)
        val both = TravelModes.setKm(TravelModes.set(null, k, Vehicle.Plane), k, true)
        assertEquals(Vehicle.Plane, TravelModes.choices(both)[k])
        assertEquals(true, TravelModes.kmChoices(both)[k])

        val back = TravelModes.setKm(both, k, null)
        assertEquals(Vehicle.Plane, TravelModes.choices(back)[k])
        assertNull(TravelModes.kmChoices(back)[k])

        val noVehicle = TravelModes.set(both, k, null)
        assertNull(TravelModes.choices(noVehicle)[k])
        assertEquals(true, TravelModes.kmChoices(noVehicle)[k])

        assertNull(TravelModes.setKm(null, k, null))
        val legs = RouteLegs.withChoices(trip.copy(travelModes = both), listOf(RouteLeg(k, "a", "b", null, null, null)))
        assertEquals(Vehicle.Plane, legs.single().vehicle)
        assertEquals(true, legs.single().countKm)
    }

    @Test fun anOlderAppsRowWithoutKmReadsAsTheDefault() {
        val raw = """{"v":1,"legs":[{"source":"planned","from":"p1","to":"p2","vehicle":"plane","later":"kept"}]}"""
        assertNull(TravelModes.kmChoices(raw)[key(1)])
        val saved = TravelModes.setKm(raw, key(1), true)!!
        assertTrue(saved, saved.contains("\"later\":\"kept\""))
        assertEquals(Vehicle.Plane, TravelModes.choices(saved)[key(1)])
    }
}

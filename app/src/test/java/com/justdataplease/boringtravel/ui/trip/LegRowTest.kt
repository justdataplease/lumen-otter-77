package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LegRowTest {
    private val day = LocalDate.of(2027, 3, 13)
    private val trip = Trip("t", "Patagonia", startDate = day, endDate = day.plusDays(3), createdAt = 1, updatedAt = 1)
    private fun item(id: String, d: LocalDate, pos: Double, lat: Double, lng: Double) =
        PlanItem(id, trip.id, d, pos, title = id, lat = lat, lng = lng, createdAt = 1, updatedAt = 1)

    private val airport = item("El Calafate", day, 1.0, -50.2803, -72.0531)
    private val town = item("El Chaltén", day, 2.0, -49.3315, -72.8863)
    private val mirador = item("Mirador", day, 3.0, -49.3373, -72.8778)
    private val capri = item("Laguna Capri", day.plusDays(1), 1.0, -49.296, -72.927)

    private fun key(a: PlanItem, b: PlanItem) = RouteLegKey(RouteLegSource.Planned, a.id, b.id)

    @Test fun legRowsShowTheChosenVehicleAndARoughTime() {
        var modes = TravelModes.set(null, key(airport, town), Vehicle.Car)
        modes = TravelModes.set(modes, key(town, mirador), Vehicle.Walk)
        val items = listOf(airport, town, mirador, capri)
        val rows = PlanTravelRows.incoming(trip.copy(travelModes = modes), items)
        val byId = items.associateBy { it.id }
        fun model(to: PlanItem): LegRowModel {
            val c = rows.getValue(to.id)
            return LegRowModel.of(c, byId[c.leg.key.fromId], to)
        }
        assertEquals("Car · ≈ 2 h 30", model(town).text)
        assertNull(model(town).fromLabel)
        assertEquals("Walk · ≈ 15 min", model(mirador).text)
        // No vehicle chosen yet: by car (owner, 2026-11-07), and the leg from the day before names where it starts.
        val next = model(capri)
        assertEquals(Vehicle.Car, next.vehicle)
        assertTrue(next.text, next.text.startsWith("Car · ≈ "))
        assertEquals("Mirador", next.fromLabel)
    }

    @Test fun dayLabelsCarryTheLatestArrival() {
        fun t(id: String, d: Int, pos: Double, title: String, kind: String = "transport") =
            PlanItem(id, trip.id, day.plusDays(d.toLong()), pos, kind = kind, title = title, createdAt = 1, updatedAt = 1)
        val items = listOf(
            t("a", 0, 1.0, "Fly to El Calafate"), t("b", 0, 2.0, "Transfer to El Chaltén"),
            t("c", 1, 1.0, "Laguna Capri", kind = "activity"), t("d", 3, 1.0, "Fly home"),
            t("e", 2, 1.0, "Old bus to Nowhere").copy(deletedAt = 3),
        )
        assertNull(PlanDayLabel.label(day.minusDays(1), items))
        assertEquals("El Chaltén", PlanDayLabel.label(day, items))
        assertEquals("El Chaltén", PlanDayLabel.label(day.plusDays(1), items))
        assertEquals("El Chaltén", PlanDayLabel.label(day.plusDays(2), items))
        // "Fly home" names no place: the last arrival stays.
        assertEquals("El Chaltén", PlanDayLabel.label(day.plusDays(3), items))
    }

    @Test fun estimatesRoundForReading() {
        assertEquals(5, LegEstimate.rounded(1))
        assertEquals(15, LegEstimate.rounded(13))
        assertEquals(45, LegEstimate.rounded(47))
        assertEquals(60, LegEstimate.rounded(61))
        assertEquals(90, LegEstimate.rounded(95))
        assertEquals(180, LegEstimate.rounded(179))
        assertEquals(180, LegEstimate.rounded(205))
        assertEquals(240, LegEstimate.rounded(215))
        assertEquals("≈ 20 min", LegEstimate.short(19))
        assertEquals("≈ 1 h", LegEstimate.short(58 + 4))
        assertEquals("≈ 1 h 30", LegEstimate.short(88))
        assertEquals("≈ 4 h", LegEstimate.short(230))
    }

    @Test fun estimatesByVehicle() {
        // 100 km: by car 100 × 1.35 / 65 h ≈ 125 min; by plane 105 / 700 h + 30 min ≈ 39 min; on foot ≈ 29 h.
        assertEquals(125, LegEstimate.minutes(Vehicle.Car, 100_000.0))
        assertEquals(39, LegEstimate.minutes(Vehicle.Plane, 100_000.0))
        assertEquals(1733, LegEstimate.minutes(Vehicle.Walk, 100_000.0))
        assertNull(LegEstimate.minutes(Vehicle.Auto, 100_000.0))
        assertNull(LegEstimate.minutes(Vehicle.Car, 0.0))
        assertNull(LegEstimate.minutes(Vehicle.Car, Double.NaN))
    }

    @Test fun sentencesForTheMapSheet() {
        assertEquals("About 3 hours by road", LegEstimate.sentence(185, Vehicle.Car))
        assertEquals("About 1 hour by air", LegEstimate.sentence(58, Vehicle.Plane))
        assertEquals("About 40 minutes on foot", LegEstimate.sentence(41, Vehicle.Walk))
        assertEquals("About 2 h 30 by boat", LegEstimate.sentence(150, Vehicle.Ferry))
    }
}

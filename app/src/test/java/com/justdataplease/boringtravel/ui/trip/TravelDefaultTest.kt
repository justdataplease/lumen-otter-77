package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TravelDefaultTest {
    @Test fun carUnlessTheNameSaysOtherwise() {
        assertEquals(Vehicle.Car, TravelDefault.guess("Recoleta Cemetery"))
        assertEquals(Vehicle.Car, TravelDefault.guess("Back to Kyoto"))
        assertEquals(Vehicle.Car, TravelDefault.guess("Gion evening walk"))
        assertEquals(Vehicle.Plane, TravelDefault.guess("Fly to Mendoza"))
        assertEquals(Vehicle.Plane, TravelDefault.guess("  flight home"))
        assertEquals(Vehicle.Train, TravelDefault.guess("Train to Nara"))
        assertEquals(Vehicle.Train, TravelDefault.guess("Shinkansen to Shin-Osaka"))
        assertEquals(Vehicle.Ferry, TravelDefault.guess("Ferry to Naxos"))
        assertEquals(Vehicle.Walk, TravelDefault.guess("Walk to the viewpoint"))
        assertEquals(Vehicle.Bike, TravelDefault.guess("Bike along the coast"))
        assertEquals(Vehicle.Car, TravelDefault.guess("Flyover viewpoint"))
    }

    private val day = LocalDate.of(2027, 3, 1)
    private fun item(id: String, d: Int?, pos: Double, kind: String, title: String, at: Pair<Double, Double>? = null) =
        PlanItem(id, "t", d?.let { day.plusDays(it.toLong()) }, pos, kind = kind, title = title, lat = at?.first, lng = at?.second, createdAt = 1, updatedAt = 1)

    private val fly = item("fly", 0, 1.0, "transport", "Fly to Buenos Aires", -34.8222 to -58.5358)
    private val hotel = item("hotel", 0, 2.0, "stay", "Boutique hotel in Palermo Soho", -34.5886 to -58.4302)
    private val dinner = item("dinner", 0, 3.0, "food", "Parrilla dinner")
    private val recoleta = item("recoleta", 1, 1.0, "place", "Recoleta Cemetery", -34.5875 to -58.3934)
    private val rest = item("rest", 2, 1.0, "note", "Slow day")
    private val toMendoza = item("mendoza", 3, 1.0, "transport", "Fly to Mendoza", -32.8317 to -68.7929)
    private val all = listOf(fly, hotel, dinner, recoleta, rest, toMendoza)

    @Test fun theDayTitleOpensThePlaceNotTheFlight() {
        // "Day 1 · Buenos Aires" opens Buenos Aires on the hotel, never on "Fly to Buenos Aires".
        assertEquals("hotel", PlanDayAnchor.item(day, all, "Buenos Aires")?.id)
        assertEquals("hotel", PlanDayAnchor.item(day, all, null)?.id)
        assertEquals("recoleta", PlanDayAnchor.item(day.plusDays(1), all, "Buenos Aires")?.id)
    }

    @Test fun aQuietDayOpensWhereYouAlreadyAre() {
        assertEquals("recoleta", PlanDayAnchor.item(day.plusDays(2), all, null)?.id)
    }

    @Test fun aTravelOnlyDayOpensTheArrival() {
        assertEquals("mendoza", PlanDayAnchor.item(day.plusDays(3), all, "Mendoza")?.id)
    }

    @Test fun theArrivalBeatsALodgeOutOfTown() {
        // "Day 4 · Mendoza": the lodge in Valle de Uco (~85 km out) is not Mendoza; the arrival is.
        val lodge = item("lodge", 3, 2.0, "stay", "Wine lodge in Valle de Uco", -33.62 to -69.20)
        val list = all + lodge
        assertEquals("mendoza", PlanDayAnchor.item(day.plusDays(3), list, "Mendoza")?.id)
        assertEquals("lodge", PlanDayAnchor.item(day.plusDays(3), list, null)?.id)
    }

    @Test fun nothingLocatedNothingToOpen() {
        assertNull(PlanDayAnchor.item(day, listOf(dinner, rest), null))
        assertNull(PlanDayAnchor.item(day, listOf(hotel.copy(deletedAt = 5)), null))
    }
}

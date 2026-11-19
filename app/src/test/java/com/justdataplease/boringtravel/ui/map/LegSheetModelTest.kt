package com.justdataplease.boringtravel.ui.map

import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LegSheetModelTest {
    private val start = LocalDate.of(2027, 3, 1)
    private val trip = Trip("t", "Patagonia", startDate = start, endDate = start.plusDays(30), createdAt = 1, updatedAt = 1)
    private fun item(id: String, day: Int, kind: String, title: String, lat: Double, lng: Double) =
        PlanItem(id, trip.id, start.plusDays(day - 1L), 1.0, kind = kind, title = title, lat = lat, lng = lng, createdAt = 1, updatedAt = 1)

    private val bariloche = item("bariloche", 8, "transport", "Fly to Bariloche", -41.1512, -71.1578)
    private val calafate = item("calafate", 13, "transport", "Fly to El Calafate", -50.2803, -72.0531)
    private val chalten = item("chalten", 13, "transport", "Transfer to El Chaltén", -49.3315, -72.8863)
    private val mirador = item("mirador", 13, "place", "Mirador de los Cóndores", -49.3373, -72.8778)
    private val back = item("back", 17, "transport", "Back to El Calafate", -50.3379, -72.2648)
    private val items = listOf(bariloche, calafate, chalten, mirador.copy(position = 2.0), back)

    @Test fun aTripInOnePlaceShowsEveryLeg() {
        val town = listOf(chalten, mirador.copy(position = 2.0))
        val cards = LegSheetModel.cards(trip, town)
        assertEquals(1, cards.size)
        assertEquals("El Chaltén → Mirador de los Cóndores", cards[0].title)
    }

    @Test fun cardsNameBothEndsAndCountTheLegs() {
        val modes = TravelModes.set(null, RouteLegKey(RouteLegSource.Planned, "calafate", "chalten"), Vehicle.Car)
        val cards = LegSheetModel.cards(trip.copy(travelModes = modes), items)
        assertEquals(3, cards.size)
        val c = cards[1]
        assertEquals("Day 13 · Leg 2 of 3", c.eyebrow)
        assertEquals("El Calafate → El Chaltén", c.title)
        assertEquals("About 2 h 30 by road", c.note)
        // Nothing chosen for "Fly to El Calafate": the name says plane (other legs default to car).
        assertEquals(Vehicle.Plane, cards[0].vehicle)
        assertTrue(cards[0].note, cards[0].note.startsWith("About ") && cards[0].note.endsWith(" by air"))
    }

    @Test fun theCurrentLegIsTheLastOneReachedByToday() {
        val cards = LegSheetModel.cards(trip, items)
        assertEquals(0, LegSheetModel.currentIndex(cards, start))
        assertEquals(0, LegSheetModel.currentIndex(cards, start.plusDays(11)))
        assertEquals(1, LegSheetModel.currentIndex(cards, start.plusDays(13)))
        assertEquals(2, LegSheetModel.currentIndex(cards, start.plusDays(40)))
        assertEquals(-1, LegSheetModel.currentIndex(emptyList(), start))
    }
}

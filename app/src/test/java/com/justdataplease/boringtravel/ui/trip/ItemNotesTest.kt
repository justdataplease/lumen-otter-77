package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The journal notes a plan item shows (owner, 2026-11-11), and the plan's "Count in km" words. */
class ItemNotesTest {
    private val prado = PlanItem(
        "i", "t", LocalDate.of(2026, 11, 14), 1.0, title = "Museo del Prado", placeName = "Madrid",
        lat = 40.4138, lng = -3.6921, createdAt = 1, updatedAt = 1,
    )

    private fun entry(id: String, place: String = "", lat: Double? = null, lng: Double? = null, trip: String = "t", at: Long = 0, deleted: Long? = null) =
        Entry(id, trip, occurredAt = at, zoneId = "Europe/Madrid", placeName = place, lat = lat, lng = lng, createdAt = 1, updatedAt = 1, deletedAt = deleted)

    @Test fun anItemsNotesAreTheOnesNamedAfterItOrWrittenThere() {
        val notes = ItemNotes.of(prado, listOf(
            entry("named", place = " museo del prado ", at = 3),
            entry("near", lat = 40.4140, lng = -3.6925, at = 1), // about 40 m away
            entry("far", lat = 40.4200, lng = -3.7000, at = 2),
            entry("town", place = "Madrid", at = 4), // the town is not this item
            entry("other trip", place = "Museo del Prado", trip = "x"),
            entry("deleted", place = "Museo del Prado", deleted = 5),
        ))
        assertEquals(listOf("near", "named"), notes.map { it.id })
    }

    @Test fun anItemWithoutAPointGoesByItsName() {
        val idea = prado.copy(lat = null, lng = null)
        val notes = ItemNotes.of(idea, listOf(entry("named", place = "Museo del Prado"), entry("near", lat = 40.4138, lng = -3.6921)))
        assertEquals(listOf("named"), notes.map { it.id })
    }

    @Test fun aNoteWrittenFromAnItemTakesItsPlaceAndShowsUnderIt() {
        // Owner, 2026-11-12: "keep the plan item name and parenthesis the place".
        assertEquals("Museo del Prado (Madrid)", ItemNotes.location(prado, listOf(prado)))
        assertEquals("Lunch", ItemNotes.location(prado.copy(title = "Lunch", placeName = "", lat = null, lng = null), emptyList()))
        assertEquals("the place is the name", "Madrid", ItemNotes.location(prado.copy(title = "Madrid", lat = null, lng = null), emptyList()))
        val zone = ZoneId.of("Europe/Madrid")
        val now = LocalDate.of(2026, 11, 14).atTime(18, 30).atZone(zone).toInstant().toEpochMilli()
        val note = ItemNotes.newEntry("n", prado, listOf(prado), now, zone)
        assertEquals("t", note.tripId)
        assertEquals("Museo del Prado (Madrid)", note.placeName)
        assertEquals(40.4138, note.lat!!, 1e-9)
        assertEquals("written on its day: now", now, note.occurredAt)
        // Written later about a day that has passed: on that day, at the item's time.
        val past = ItemNotes.newEntry("p", prado.copy(timeMinutes = 10 * 60), listOf(prado), now + 3 * 86_400_000L, zone)
        assertEquals(LocalDate.of(2026, 11, 14).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), past.occurredAt)
        // Listed under the item by its place alone, without a point.
        val idea = prado.copy(lat = null, lng = null)
        assertEquals(listOf("n"), ItemNotes.of(idea, listOf(note.copy(lat = null, lng = null))).map { it.id })
        assertTrue(ItemNotes.named(prado, " museo del prado (madrid) "))
        assertFalse(ItemNotes.named(prado, "Museo del Prado shop"))
        assertFalse(ItemNotes.named(prado.copy(title = "Museo"), "Museo del Prado (Madrid)"))
        assertFalse(ItemNotes.named(prado.copy(title = " "), " ()"))
    }

    @Test fun theKmSwitchSavesOnlyWhatDiffersFromTheDefault() {
        assertEquals(true, legKmChoice(Vehicle.Plane, true))
        assertNull(legKmChoice(Vehicle.Plane, false))
        assertEquals(false, legKmChoice(Vehicle.Car, false))
        assertNull(legKmChoice(Vehicle.Walk, true))
    }

    @Test fun theKmWordsSayWhetherTheLegCounts() {
        assertEquals("In the trip's total", legKmLine(Double.NaN, counted = true))
        assertEquals("Left out of the trip's total", legKmLine(0.0, counted = false))
        assertTrue(legKmLine(2_370_000.0, counted = false).endsWith(" km, left out of the trip's total"))
        assertTrue(legKmLine(12_000.0, counted = true).endsWith(" km in the trip's total"))
        assertEquals("from Athens · not in km", LegRowModel(Vehicle.Plane, "≈ 4 h", "Athens", countsKm = false).aside)
        assertEquals("not in km", LegRowModel(Vehicle.Plane, null, null, countsKm = false).aside)
        assertNull(LegRowModel(Vehicle.Car, null, null).aside)
    }
}

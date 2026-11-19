package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PlanTravelRowsTest {
    private val day = LocalDate.of(2028, 5, 10)
    private val trip = Trip("trip", "Example", startDate = day, endDate = day.plusDays(2), createdAt = 1, updatedAt = 1)
    private fun item(id: String, position: Double, date: LocalDate? = day, lat: Double? = 37.0, lng: Double? = 23.0) =
        PlanItem(id, trip.id, date, position, title = id, lat = lat, lng = lng, createdAt = 1, updatedAt = 1)
    private fun key(from: String, to: String) = RouteLegKey(RouteLegSource.Planned, from, to)

    @Test fun firstDestinationOfADayRetainsItsIncomingConnectionFromThePriorDay() {
        val a = item("Harbour", 2.0)
        val note = item("Packing note", 1.0, day.plusDays(1), null, null)
        val b = item("Museum", 2.0, day.plusDays(1), 37.1, 23.1)
        val rows = PlanTravelRows.incoming(trip, listOf(b, note, a))
        assertEquals(setOf(b.id), rows.keys)
        val incoming = requireNotNull(rows[b.id])
        assertEquals(key(a.id,b.id), incoming.leg.key)
        assertEquals(day, incoming.originDay)
        assertEquals("Harbour", incoming.fromLabel)
        assertEquals("Museum", incoming.toLabel)
        assertNull(incoming.leg.vehicle)
        // Nothing chosen: by car (owner, 2026-11-07).
        assertEquals(Vehicle.Car, incoming.mode)
        assertNull(rows[a.id])
        assertNull(rows[note.id])
    }

    @Test fun collapsedUnlocatedInvalidDeletedAndForeignItemsDoNotGetTravelRows() {
        val a = item("a", 1.0)
        val samePlace = item("same-place", 2.0, lat = 37.000001, lng = 23.000001)
        val missing = item("missing", 3.0, lat = null, lng = null)
        val invalid = item("invalid", 4.0, lat = Double.NaN)
        val deleted = item("deleted", 5.0, lat = 37.3).copy(deletedAt = 4)
        val foreign = item("foreign", 6.0, lat = 37.4).copy(tripId = "other-trip")
        val b = item("b", 7.0, lat = 37.5)
        val rows = PlanTravelRows.incoming(trip, listOf(a,samePlace,missing,invalid,deleted,foreign,b))
        assertEquals(setOf(b.id), rows.keys)
        assertEquals(key(a.id,b.id), rows.getValue(b.id).leg.key)
        assertNull(rows.getValue(b.id).originDay)
    }

    @Test fun reorderingCannotMoveAnOldChoiceOntoTheNewIncomingLegOrKeepItsChooserOpen() {
        val a = item("a", 1.0)
        val b = item("b", 2.0, lat = 37.1)
        val c = item("c", 3.0, lat = 37.2)
        val saved = trip.copy(travelModes = TravelModes.set(null,key(a.id,b.id),Vehicle.Ferry))
        val before = PlanTravelRows.incoming(saved,listOf(a,b,c))
        assertEquals(Vehicle.Ferry,before.getValue(b.id).mode)
        assertNotNull(PlanTravelRows.resolve(before,key(a.id,b.id)))
        val after = PlanTravelRows.incoming(saved,listOf(a,b.copy(position = 4.0),c))
        assertEquals(key(c.id,b.id),after.getValue(b.id).leg.key)
        assertNull(after.getValue(b.id).leg.vehicle)
        assertNull(after.getValue(c.id).leg.vehicle)
        assertNull(PlanTravelRows.resolve(after,key(a.id,b.id)))
    }

    @Test fun renamingPlacesUpdatesTheChooserContextWithoutLosingTheSavedPair() {
        val a = item("a",1.0)
        val b = item("b",2.0,lat = 37.1)
        val saved = trip.copy(travelModes = TravelModes.set(null,key(a.id,b.id),Vehicle.Train))
        val renamed = PlanTravelRows.incoming(saved,listOf(a.copy(title = "Central station"),b.copy(title = "Old town")))
        val current = requireNotNull(PlanTravelRows.resolve(renamed,key(a.id,b.id)))
        assertEquals("Central station",current.fromLabel)
        assertEquals("Old town",current.toLabel)
        assertEquals(Vehicle.Train,current.mode)
    }

    @Test fun aReplacementItemAtTheSamePlaceDoesNotInheritAnotherItemsTravelChoice() {
        val a = item("a",1.0)
        val replacement = item("new-a",1.0)
        val b = item("b",2.0,lat = 37.1)
        val saved = trip.copy(travelModes = TravelModes.set(null,key(a.id,b.id),Vehicle.Plane))
        val current = PlanTravelRows.incoming(saved,listOf(a.copy(deletedAt = 2),replacement,b))
        assertEquals(key(replacement.id,b.id),current.getValue(b.id).leg.key)
        assertNull(current.getValue(b.id).leg.vehicle)
        assertNull(PlanTravelRows.resolve(current,key(a.id,b.id)))
    }

    @Test fun undatedIdeasFollowDatedPlacesAndCanKeepTheirOwnExplicitChoice() {
        val a = item("scheduled",1.0)
        val b = item("idea",0.0,date = null,lat = 37.1)
        val saved = trip.copy(travelModes = TravelModes.set(null,key(a.id,b.id),Vehicle.Walk))
        val rows = PlanTravelRows.incoming(saved,listOf(b,a))
        val current = rows.getValue(b.id)
        assertEquals(day,current.originDay)
        assertNull(current.leg.toDay)
        assertEquals(Vehicle.Walk,current.mode)
        assertNull(rows[a.id])
    }
}

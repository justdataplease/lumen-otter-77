package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.clip.PlanRoute
import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.time.localDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trip store's travel choices (a leg's vehicle and "Count in km", checked against the plan as the Android app's
 * TripLegUpdates checks them) and rows written as given (the iPhone's demo). Each one stays the backup's data.json.
 */
class TripBookTravelTest {
    private var clock = 1_000L
    private var next = 0
    private val file = MemoryTripFile()
    private fun book() = TripBook(file, now = { clock }, ids = { "id-${next++}" })
    private val start = localDate(2027, 4, 1)

    private fun restored() = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read())).toSnapshot()

    @Test
    fun aLegsVehicleAndKmAreSavedOnlyWhileTheLegIsThePlans() {
        val book = book()
        val trip = book.startFromIdea(KyotoOsaka.destination, KyotoOsaka.destination.default, Pace.Balanced, start)
        val key = PlanRoute.legKeys(trip, book.items(trip.id)).first()
        clock = 2_000L
        val saved = assertNotNull(book.setLegVehicle(trip.id, key, Vehicle.Ferry))
        assertEquals(Vehicle.Ferry, TravelModes.choices(saved.travelModes)[key])
        assertEquals(2_000L, saved.updatedAt)
        // The other legs' choices stay as the idea set them.
        val others = TravelModes.choices(trip.travelModes) - key
        assertEquals(others, TravelModes.choices(saved.travelModes) - key)
        assertNotNull(book.setLegKm(trip.id, key, false))
        assertEquals(false, TravelModes.kmChoices(book.trip(trip.id)?.travelModes)[key])
        // Back to the default: the vehicle goes, the km choice stays.
        book.setLegVehicle(trip.id, key, null)
        assertNull(TravelModes.choices(book.trip(trip.id)?.travelModes)[key])
        assertEquals(false, TravelModes.kmChoices(book.trip(trip.id)?.travelModes)[key])
        assertEquals(book.snapshot, restored())
        // A leg the plan no longer has, and a trip that isn't there.
        val stale = RouteLegKey(RouteLegSource.Planned, "nowhere", key.toId)
        val e = assertFailsWith<IllegalStateException> { book.setLegVehicle(trip.id, stale, Vehicle.Car) }
        assertEquals("This route leg changed. Reopen it and choose again.", e.message)
        assertNull(book.setLegVehicle("gone", key, Vehicle.Car))
    }

    @Test
    fun rowsAreWrittenAsGivenReplacingTheirIds() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(3))
        val item = assertNotNull(book.addItem(trip.id, "Portara", start))
        val example = trip.copy(id = "demo-x", title = "Example", createdAt = 7, updatedAt = 7)
        val stop = Stop("s", example.id, arrivedAt = 10, lat = 37.1, lng = 25.4, name = "Chora", createdAt = 7, updatedAt = 7)
        book.putRows(trips = listOf(example), items = listOf(item.copy(title = "Portara at sunset")), stops = listOf(stop))
        assertEquals("Portara at sunset", book.item(item.id)?.title)
        assertEquals(1, book.snapshot.planItems.size)
        assertEquals(7L, book.trip("demo-x")?.updatedAt)
        assertEquals(listOf(stop), book.snapshot.stops)
        // Again with a change: replaced, not doubled.
        book.putRows(trips = listOf(example.copy(title = "Example 2", updatedAt = 8)))
        assertEquals(2, book.snapshot.trips.size)
        assertEquals("Example 2", book.trip("demo-x")?.title)
        assertTrue(book.snapshot == restored())
    }
}

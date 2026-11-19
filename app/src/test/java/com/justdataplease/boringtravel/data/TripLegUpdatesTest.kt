package com.justdataplease.boringtravel.data.repo

import com.justdataplease.boringtravel.clip.RouteLegKey
import com.justdataplease.boringtravel.clip.RouteLegSource
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.Vehicle
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TripLegUpdatesTest {
    private val first = RouteLegKey(RouteLegSource.Planned, "a", "b")
    private val second = RouteLegKey(RouteLegSource.Planned, "b", "c")
    private val trip = Trip("t", "Journey", createdAt = 1, updatedAt = 10)

    @Test fun concurrentQueuedChoicesReadFreshInsideTransactionAndKeepBoth() = runTest {
        val lock = Mutex(locked = true)
        var row = trip
        var reads = 0
        suspend fun save(key: RouteLegKey, vehicle: Vehicle) = TripLegUpdates.set(key, vehicle,
            transaction = { work -> lock.withLock { work() } },
            read = { assertTrue(lock.isLocked); reads++; row }, activeKeys = { setOf(first, second) },
            write = { row = it }, clock = { 5 })
        val one = async { save(first, Vehicle.Walk) }
        val two = async { save(second, Vehicle.Plane) }
        runCurrent()
        assertEquals(0, reads)
        row = row.copy(title = "Title edited while waiting")
        lock.unlock()
        one.await(); two.await()
        assertEquals(mapOf(first to Vehicle.Walk, second to Vehicle.Plane), TravelModes.choices(row.travelModes))
        assertEquals("Title edited while waiting", row.title)
        assertEquals(12L, row.updatedAt)
    }

    @Test fun ordinaryStaleTripEditPreservesLatestChoicesAndNewTripKeepsItsPayload() {
        val latest = trip.copy(travelModes = TravelModes.set(null, first, Vehicle.Train))
        val edit = trip.copy(title = "New title")
        assertEquals(latest.travelModes, TripLegUpdates.preserveChoices(edit, latest).travelModes)
        assertEquals("New title", TripLegUpdates.preserveChoices(edit, latest).title)
        assertEquals(latest, TripLegUpdates.preserveChoices(latest, null))
    }

    @Test fun removedPairAndDeletedTripCannotReceiveAChoice() = runTest {
        var wrote = false
        val error = runCatching { TripLegUpdates.set(first, Vehicle.Car, { it() }, { trip }, { setOf(second) }, { wrote = true }, { 11 }) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(wrote)
        assertNull(TripLegUpdates.set(first, Vehicle.Car, { it() }, { trip.copy(deletedAt = 11) }, { setOf(first) }, { wrote = true }, { 12 }))
        assertFalse(wrote)
    }

    @Test fun cancellationWhileWaitingNeverReadsOrWrites() = runTest {
        val lock = Mutex(locked = true)
        var touched = false
        val saving = launch { TripLegUpdates.set(first, Vehicle.Car, { work -> lock.withLock { work() } },
            { touched = true; trip }, { setOf(first) }, { touched = true }, { 11 }) }
        runCurrent()
        saving.cancelAndJoin()
        lock.unlock()
        assertFalse(touched)
    }
}

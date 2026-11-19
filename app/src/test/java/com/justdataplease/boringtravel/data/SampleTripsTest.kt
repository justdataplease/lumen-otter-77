package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The one-time move from the first examples never touches what the traveller made or changed. */
class SampleTripsTest {
    private val today = LocalDate.of(2026, 11, 6)
    private val seeded = 100L
    private val argentinaAdded = 500L
    private val now = 9_000L

    private fun cyclades(updated: Long = seeded, deleted: Long? = null) =
        Trip(SampleTrips.CYCLADES, "Cyclades, slowly", "Example trip", today.minusDays(10), today.minusDays(1), createdAt = seeded, updatedAt = updated, deletedAt = deleted)

    private fun item(trip: String, created: Long = seeded, updated: Long = created, done: Boolean = false, deleted: Long? = null) =
        PlanItem("i-$trip-$created-$updated-$done", trip, title = "Ferry", done = done, createdAt = created, updatedAt = updated, deletedAt = deleted)

    private fun entry(trip: String, created: Long = seeded, updated: Long = created) =
        Entry("e-$trip-$created-$updated", trip, occurredAt = 0, zoneId = "Europe/Athens", title = "Ferry day", createdAt = created, updatedAt = updated)

    private fun stop(trip: String, created: Long = seeded) =
        Stop("s-$trip-$created", trip, arrivedAt = 0, lat = 37.0, lng = 25.1, createdAt = created, updatedAt = created)

    private fun legacy(updated: Long = argentinaAdded) =
        Trip(SampleArgentina.LEGACY_TRIP_ID, "Argentina & Patagonia", "", SampleArgentina.LEGACY_START, SampleArgentina.LEGACY_START.plusDays(30), createdAt = argentinaAdded, updatedAt = updated)

    private fun kyotoRemoved() = SampleTrips.kyoto(today, seeded).copy(updatedAt = argentinaAdded, deletedAt = argentinaAdded)

    private fun lisbon() = SampleTrips.lisbon(today, seeded)

    /** An install with the first examples, all untouched (the Kyoto example removed by the Argentina plan). */
    private fun firstExamples() = SampleTrips.Rows(
        trips = listOf(cyclades(), lisbon(), legacy(), kyotoRemoved()),
        items = listOf(item(SampleTrips.CYCLADES), item(SampleTrips.CYCLADES, done = true), item(SampleArgentina.LEGACY_TRIP_ID, created = argentinaAdded)),
        entries = listOf(entry(SampleTrips.CYCLADES)),
        stops = listOf(stop(SampleTrips.CYCLADES)),
    )

    @Test fun untouchedExamplesAreSoftDeletedAndKyotoComesBack() {
        val c = SampleTrips.upgrade(firstExamples(), today, now)
        val trips = c.trips.associateBy { it.id }
        assertEquals(now, trips.getValue(SampleTrips.CYCLADES).deletedAt)
        assertEquals(now, trips.getValue(SampleArgentina.LEGACY_TRIP_ID).deletedAt)
        assertEquals(null, trips.getValue(SampleTrips.KYOTO).deletedAt)
        assertEquals(LocalDate.of(2027, 10, 3), trips.getValue(SampleTrips.KYOTO).startDate)
        assertEquals(trips.getValue(SampleTrips.KYOTO).createdAt, trips.getValue(SampleTrips.KYOTO).updatedAt)
        // Lisbon stays as it is.
        assertTrue(SampleTrips.LISBON !in trips)
        assertEquals(3, c.items.size)
        assertTrue((c.items.map { it.deletedAt } + c.entries.map { it.deletedAt } + c.stops.map { it.deletedAt }).all { it == now })
        assertEquals(1, c.entries.size)
        assertEquals(1, c.stops.size)
    }

    @Test fun anEditedTripIsLeftAlone() {
        val rows = firstExamples().copy(trips = listOf(cyclades(updated = 700), lisbon(), legacy(updated = 800), kyotoRemoved()))
        val c = SampleTrips.upgrade(rows, today, now)
        assertTrue(c.trips.none { it.id == SampleTrips.CYCLADES || it.id == SampleArgentina.LEGACY_TRIP_ID })
        assertTrue(c.items.isEmpty() && c.entries.isEmpty() && c.stops.isEmpty())
    }

    @Test fun aTickedEditedOrAddedRowMakesTheTripTheTravellers() {
        val base = firstExamples()
        val cases = listOf(
            base.copy(items = base.items + item(SampleTrips.CYCLADES, created = seeded, updated = 650, done = true)),
            base.copy(items = base.items + item(SampleTrips.CYCLADES, created = 650)),
            base.copy(items = base.items + item(SampleTrips.CYCLADES, created = seeded, updated = 650, deleted = 650)),
            base.copy(entries = base.entries + entry(SampleTrips.CYCLADES, created = 650)),
            base.copy(entries = listOf(entry(SampleTrips.CYCLADES, updated = 650))),
            base.copy(stops = base.stops + stop(SampleTrips.CYCLADES, created = 650)),
            base.copy(media = listOf(Media("m", SampleTrips.CYCLADES, null, "media/m.jpg", createdAt = 650, updatedAt = 650))),
            base.copy(media = listOf(Media("m", null, base.entries.first().id, "media/m.jpg", createdAt = 650, updatedAt = 650))),
            base.copy(trackedTrips = setOf(SampleTrips.CYCLADES)),
        )
        for ((i, rows) in cases.withIndex()) {
            val c = SampleTrips.upgrade(rows, today, now)
            assertTrue("case $i", c.trips.none { it.id == SampleTrips.CYCLADES })
            assertTrue("case $i", c.items.none { it.tripId == SampleTrips.CYCLADES })
            assertTrue("case $i", c.entries.isEmpty() && c.stops.isEmpty())
            // The untouched legacy plan still goes.
            assertTrue("case $i", c.trips.any { it.id == SampleArgentina.LEGACY_TRIP_ID && it.deletedAt == now })
        }
    }

    @Test fun theTravellersOwnTripsAndDeletionsAreNeverTouched() {
        val mine = Trip("mine", "Cyclades, slowly", "", today, today.plusDays(3), createdAt = seeded, updatedAt = seeded)
        val userDeletedKyoto = SampleTrips.kyoto(today, seeded).copy(updatedAt = 300, deletedAt = 300)
        val rows = SampleTrips.Rows(
            trips = listOf(mine, cyclades(updated = 200, deleted = 200), userDeletedKyoto, lisbon(), legacy()),
            items = listOf(item("mine"), item(SampleArgentina.LEGACY_TRIP_ID, created = argentinaAdded)),
            entries = listOf(entry("mine")),
        )
        val c = SampleTrips.upgrade(rows, today, now)
        assertEquals(listOf(SampleArgentina.LEGACY_TRIP_ID), c.trips.map { it.id })
        assertTrue(c.items.all { it.tripId == SampleArgentina.LEGACY_TRIP_ID })
        assertTrue(c.entries.isEmpty())
    }

    @Test fun anArgentinaPlanWithOtherDatesIsTheTravellers() {
        val moved = legacy().copy(startDate = LocalDate.of(2027, 4, 1), endDate = LocalDate.of(2027, 5, 1))
        val c = SampleTrips.upgrade(firstExamples().copy(trips = listOf(cyclades(), lisbon(), moved, kyotoRemoved())), today, now)
        assertTrue(c.trips.none { it.id == SampleArgentina.LEGACY_TRIP_ID })
    }

    @Test fun kyotoIsAddedWhenItWasNeverThereAndLeftWhenItIs() {
        val noKyoto = firstExamples().copy(trips = listOf(cyclades(), lisbon(), legacy()))
        assertTrue(SampleTrips.upgrade(noKyoto, today, now).trips.any { it.id == SampleTrips.KYOTO && it.deletedAt == null })
        val kept = firstExamples().copy(trips = listOf(cyclades(), lisbon(), legacy(), SampleTrips.kyoto(today, seeded)))
        assertTrue(SampleTrips.upgrade(kept, today, now).trips.none { it.id == SampleTrips.KYOTO })
        // Removed by the Argentina step but with plans made since: not restored over them.
        val planned = firstExamples().let { it.copy(items = it.items + item(SampleTrips.KYOTO, created = 900)) }
        assertTrue(SampleTrips.upgrade(planned, today, now).trips.none { it.id == SampleTrips.KYOTO })
    }

    @Test fun lisbonIsAlwaysInThePast() {
        assertEquals(LocalDate.of(2026, 5, 9), SampleTrips.lisbon(today, 0).startDate)
        assertEquals(LocalDate.of(2025, 5, 9), SampleTrips.lisbon(LocalDate.of(2026, 3, 1), 0).startDate)
        assertTrue(SampleTrips.lisbon(LocalDate.of(2026, 5, 17), 0).isPast(LocalDate.of(2026, 5, 17)))
        assertTrue(SampleTrips.kyoto(today, 0).startDate!!.isAfter(today))
        // The Demo tag says they are examples; the subtitle is the route.
        assertEquals("Kyoto → Osaka", SampleTrips.kyoto(today, 0).subtitle)
        assertEquals("Lisbon → Porto", SampleTrips.lisbon(today, 0).subtitle)
        assertTrue(SampleTrips.kyoto(today, 0).isDemo && SampleTrips.lisbon(today, 0).isDemo)
    }
}

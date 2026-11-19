package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.daysAt
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.programs.data.LisbonPorto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Sample version 5 (Kyoto & Osaka and Lisbon & Porto get their plans from the trip ideas) and Me › Demo mode bringing
 * missing examples back: only the example trips change, never the traveller's own.
 */
class DemoUpgradeTest {
    private val today = LocalDate.of(2026, 11, 7)
    private val seeded = 100L
    private val now = 9_000L

    /** An install from before version 5: the old Kyoto and Lisbon plans (hand-written rows), Lisbon's journal and stays. */
    private val oldKyoto = SampleTrips.kyoto(today, seeded).copy(travelModes = """{"v":1,"legs":[]}""")
    private val oldLisbon = SampleTrips.lisbon(today, seeded)
    private val lisbonFull = SampleLisbon.demo(oldLisbon.startDate!!, seeded)
    private fun old(trip: String, title: String, day: LocalDate?, created: Long = seeded, updated: Long = created, done: Boolean = false) =
        PlanItem("old-$trip-$title", trip, day = day, title = title, done = done, createdAt = created, updatedAt = updated)
    private val oldItems = listOf(
        old(SampleTrips.KYOTO, "Fushimi Inari early", oldKyoto.startDate!!.plusDays(1)),
        old(SampleTrips.KYOTO, "Kiyomizu-dera", oldKyoto.startDate!!.plusDays(1), updated = 400, done = true),
        old(SampleTrips.KYOTO, "Fushimi sake district", null),
        old(SampleTrips.LISBON, "Tram 28", oldLisbon.startDate!!.plusDays(1), done = true),
    )

    /** The traveller's own trip with its plan, an entry and a photo. */
    private val mine = Trip("5a1c7d2e-own", "Crete", "", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), createdAt = 50, updatedAt = 60)
    private val myItem = PlanItem("my-item", mine.id, title = "Balos", createdAt = 50, updatedAt = 50)
    private val myEntry = Entry("my-entry", mine.id, occurredAt = 0, zoneId = "Europe/Athens", title = "Chania", createdAt = 50, updatedAt = 50)
    private val myPhoto = Media("my-photo", mine.id, myEntry.id, "media/my-photo.jpg", createdAt = 50, updatedAt = 50)
    private val myStop = Stop("my-stop", mine.id, arrivedAt = 0, lat = 35.5, lng = 24.0, createdAt = 50, updatedAt = 50)

    private fun rows(kyoto: Trip = oldKyoto, lisbon: Trip = oldLisbon) = SampleTrips.Rows(
        trips = listOf(kyoto, lisbon, mine),
        items = oldItems + myItem,
        entries = lisbonFull.entries + myEntry,
        stops = lisbonFull.stops + myStop,
        media = lisbonFull.media + myPhoto,
    )

    private val own = setOf(mine.id)
    private fun SampleTrips.Changes.touchesOwn() =
        trips.any { it.id in own } || items.any { it.tripId in own } || entries.any { it.tripId in own } ||
            stops.any { it.tripId in own } || media.any { it.tripId in own || it.entryId == myEntry.id }

    @Test fun kyotoAndLisbonGetTheIdeasPlansOnTheirOwnDates() {
        val c = SampleTrips.newPrograms(rows(), now)
        assertTrue(!c.touchesOwn())
        val trips = c.trips.associateBy { it.id }
        assertEquals(setOf(SampleTrips.KYOTO, SampleTrips.LISBON), trips.keys)
        for ((trip, program, done) in listOf(
            Triple(oldKyoto, KyotoOsaka.destination.default, false),
            Triple(oldLisbon, LisbonPorto.destination.default, true),
        )) {
            val after = trips.getValue(trip.id)
            // Same trip, same start; the end follows the program; an untouched trip takes the idea's name and route.
            assertEquals(trip.startDate, after.startDate)
            assertEquals(trip.startDate!!.plusDays(program.days.size - 1L), after.endDate)
            assertEquals(trip.coverMediaId to trip.trackingLevel, after.coverMediaId to after.trackingLevel)
            assertEquals(now to now, after.createdAt to after.updatedAt)
            assertTrue(after.isDemo)
            val added = c.items.filter { it.tripId == trip.id && it.deletedAt == null }
            val balanced = program.daysAt(Pace.Balanced)
            assertEquals(balanced.sumOf { it.items.size } + program.ideas.size, added.size)
            assertTrue(added.all { it.source == ItemSource.Program.id && it.createdAt == now && it.updatedAt == now })
            assertTrue(added.all { it.day == null || !it.day!!.isBefore(after.startDate) && !it.day!!.isAfter(after.endDate) })
            // Lisbon is over: everything planned done. Kyoto is ahead: nothing.
            assertEquals(done, added.filter { it.day != null && it.kind != "note" }.all { it.done })
            if (!done) assertTrue(added.none { it.done })
        }
        // The old plans go as a whole, ticked or edited ones too.
        val removed = c.items.filter { it.deletedAt != null }
        assertEquals(oldItems.map { it.id }.toSet(), removed.map { it.id }.toSet())
        assertTrue(removed.all { it.deletedAt == now && it.updatedAt == now })
    }

    @Test fun lisbonsJournalPicturesAndStaysStayAndAreRestampedWithTheTrip() {
        val c = SampleTrips.newPrograms(rows(), now)
        // Nothing of the journal is deleted; the rows seeded with the trip are stamped with it again.
        assertTrue(c.entries.none { it.deletedAt != null } && c.stops.none { it.deletedAt != null } && c.media.none { it.deletedAt != null })
        assertEquals(lisbonFull.entries.map { it.id to it.occurredAt }, c.entries.map { it.id to it.occurredAt })
        assertEquals(lisbonFull.stops.map { it.id }, c.stops.map { it.id })
        assertEquals(lisbonFull.media.map { it.id to it.localPath }, c.media.map { it.id to it.localPath })
        assertTrue((c.entries.map { it.createdAt to it.updatedAt } + c.stops.map { it.createdAt to it.updatedAt } + c.media.map { it.createdAt to it.updatedAt }).all { it == now to now })
        // So the daily move still sees an untouched demo: everything moves and is restamped together.
        val lisbon = c.trips.single { it.id == SampleTrips.LISBON }
        val after = DemoTrips.Rows(lisbon, c.items.filter { it.tripId == lisbon.id && it.deletedAt == null }, c.entries, c.media, c.stops)
        val argentina = SampleArgentina.demo(today.minusDays(3), java.time.Instant.ofEpochMilli(0), now)
        val moved = DemoTrips.reanchor(listOf(DemoTrips.Rows(argentina.trip), after), today, now + 1).single { it.trip.id == lisbon.id }
        assertEquals(lisbon.startDate!!.plusDays(3), moved.trip.startDate)
        assertTrue((listOf(moved.trip.createdAt) + moved.items.map { it.createdAt } + moved.entries.map { it.createdAt } + moved.stops.map { it.createdAt }).all { it == now + 1 })
    }

    @Test fun aRenamedOrMovedExampleKeepsItsNameAndStamps() {
        val renamed = oldKyoto.copy(title = "Japan in autumn", subtitle = "Mine", updatedAt = 700)
        val c = SampleTrips.newPrograms(rows(kyoto = renamed), now)
        val kyoto = c.trips.single { it.id == SampleTrips.KYOTO }
        assertEquals("Japan in autumn" to "Mine", kyoto.title to kyoto.subtitle)
        assertEquals(seeded to now, kyoto.createdAt to kyoto.updatedAt)
        // Its plan still becomes the idea's: the demo's plan is an example, edited or not.
        assertTrue(c.items.any { it.tripId == SampleTrips.KYOTO && it.deletedAt == null && it.source == ItemSource.Program.id })
        // A touched trip's journal rows are not restamped.
        val touchedLisbon = SampleTrips.newPrograms(rows(lisbon = oldLisbon.copy(updatedAt = 700)), now)
        assertTrue(touchedLisbon.entries.isEmpty() && touchedLisbon.stops.isEmpty() && touchedLisbon.media.isEmpty())
        assertEquals(seeded, touchedLisbon.trips.single { it.id == SampleTrips.LISBON }.createdAt)
    }

    @Test fun aDeletedMissingOrJustFilledExampleIsLeftAlone() {
        val deleted = SampleTrips.newPrograms(rows(kyoto = oldKyoto.copy(deletedAt = 500, updatedAt = 500)), now)
        assertEquals(listOf(SampleTrips.LISBON), deleted.trips.map { it.id })
        assertTrue(deleted.items.none { it.tripId == SampleTrips.KYOTO })
        val without = rows().let { it.copy(trips = it.trips.filterNot { t -> t.id == SampleTrips.LISBON }) }
        assertEquals(listOf(SampleTrips.KYOTO), SampleTrips.newPrograms(without, now).trips.map { it.id })
        val skipped = SampleTrips.newPrograms(rows(), now, skip = setOf(SampleTrips.KYOTO))
        assertEquals(listOf(SampleTrips.LISBON), skipped.trips.map { it.id })
        assertTrue(skipped.items.none { it.tripId == SampleTrips.KYOTO })
        // Nothing but the traveller's own trips: nothing to do.
        val c = SampleTrips.newPrograms(SampleTrips.Rows(trips = listOf(mine), items = listOf(myItem), entries = listOf(myEntry)), now)
        assertEquals(SampleTrips.Changes(), c)
    }

    // ---- Me › Demo mode on: bringing the examples back ----

    @Test fun missingOrDeletedExamplesAreSeededAgain() {
        val argentina = Trip(SampleArgentina.TRIP_ID, "Argentina & Patagonia", createdAt = 1, updatedAt = 1)
        assertEquals(SampleTrips.CURRENT, SampleTrips.missing(emptyList()))
        assertEquals(SampleTrips.CURRENT, SampleTrips.missing(listOf(mine)))
        assertEquals(emptyList<String>(), SampleTrips.missing(listOf(argentina, oldKyoto, oldLisbon, mine)))
        assertEquals(listOf(SampleTrips.KYOTO), SampleTrips.missing(listOf(argentina, oldKyoto.copy(deletedAt = 5), oldLisbon)))
        // The first examples (Cyclades, the fixed-date Argentina) never come back.
        val first = listOf(Trip(SampleTrips.CYCLADES, "Cyclades", createdAt = 1, updatedAt = 1), Trip(SampleArgentina.LEGACY_TRIP_ID, "Argentina", createdAt = 1, updatedAt = 1))
        assertEquals(SampleTrips.CURRENT, SampleTrips.missing(first))
        assertEquals(listOf(SampleArgentina.TRIP_ID, SampleTrips.KYOTO, SampleTrips.LISBON), SampleTrips.CURRENT)
    }

    @Test fun whatWasLeftOfADeletedExampleGoesSoNothingShowsTwice() {
        val r = rows(lisbon = oldLisbon.copy(deletedAt = 500, updatedAt = 500))
        val c = SampleTrips.leftovers(r, setOf(SampleTrips.LISBON), now)
        assertTrue(c.trips.isEmpty())
        assertTrue(!c.touchesOwn())
        assertEquals(oldItems.filter { it.tripId == SampleTrips.LISBON }.map { it.id }, c.items.map { it.id })
        assertEquals(lisbonFull.entries.map { it.id }, c.entries.map { it.id })
        assertEquals(lisbonFull.stops.map { it.id }, c.stops.map { it.id })
        assertEquals(lisbonFull.media.map { it.id }.toSet(), c.media.map { it.id }.toSet())
        assertTrue((c.items.map { it.deletedAt } + c.entries.map { it.deletedAt } + c.stops.map { it.deletedAt } + c.media.map { it.deletedAt }).all { it == now })
        // Rows already deleted stay as they were.
        val again = SampleTrips.leftovers(SampleTrips.Rows(trips = r.trips, items = c.items, entries = c.entries, stops = c.stops, media = c.media), setOf(SampleTrips.LISBON), now + 1)
        assertEquals(SampleTrips.Changes(), again)
    }
}

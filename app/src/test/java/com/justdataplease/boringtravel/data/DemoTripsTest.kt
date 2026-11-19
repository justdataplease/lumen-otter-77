package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.data.DemoTrips.visibleIn
import com.justdataplease.boringtravel.data.db.BookDraft
import com.justdataplease.boringtravel.data.db.CountryMark
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.stats.TripStats
import com.justdataplease.boringtravel.ui.trips.HomeStats
import com.justdataplease.boringtravel.world.WorldInput
import com.justdataplease.boringtravel.world.WorldModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Demo mode: the app shows the example trips or the traveller's own, never both; the numbers come from what shows;
 * the demo is kept around today; a new trip of the traveller's own ends demo mode; backups never carry the demo.
 */
class DemoTripsTest {
    private val zone: ZoneId = SampleArgentina.ZONE
    private val today = LocalDate.of(2026, 11, 7)
    private val seeded = 100L
    private val now = 9_000L

    /** The examples as an install from 6 November seeded them (Argentina's day 14 that day). */
    private val seededOn = LocalDate.of(2026, 11, 6)
    private val demo = SampleArgentina.demo(seededOn, seededOn.atTime(15, 0).atZone(ZoneId.of("Europe/Athens")).toInstant(), seeded)
    private val kyoto = SampleTrips.kyoto(seededOn, seeded)
    private val lisbon = SampleTrips.lisbon(seededOn, seeded)

    /** The traveller's own: a past trip to Crete with a stay, an entry and a photo, and a note outside any trip. */
    private val crete = Trip("8c1f0e9a-crete", "Crete", "", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), createdAt = 50, updatedAt = 50)
    private val creteStop = Stop("my-stop", crete.id, ms(LocalDateTime.of(2026, 9, 1, 15, 0)), ms(LocalDateTime.of(2026, 9, 8, 9, 0)), 35.51, 24.02, "Chania", "GR", createdAt = 60, updatedAt = 60)
    private val creteEntry = Entry("my-entry", crete.id, ms(LocalDateTime.of(2026, 9, 2, 20, 0)), "Europe/Athens", "Chania", lat = 35.51, lng = 24.02, createdAt = 60, updatedAt = 60)
    private val cretePhoto = Media("my-photo", crete.id, creteEntry.id, "media/my-photo.jpg", takenAt = creteEntry.occurredAt, lat = 35.51, lng = 24.02, createdAt = 60, updatedAt = 60)
    private val looseNote = Entry("loose", null, ms(LocalDateTime.of(2026, 10, 1, 9, 0)), "Europe/Athens", "Note", createdAt = 70, updatedAt = 70)
    private val franceByHand = CountryMark(CountryMark.id(CountryMark.SOURCE_MANUAL, "FR"), "FR", CountryMark.SOURCE_MANUAL, 1, 1)

    private fun ms(at: LocalDateTime, z: ZoneId = zone) = at.atZone(z).toInstant().toEpochMilli()
    private fun local(ms: Long, z: ZoneId = zone): LocalDateTime = Instant.ofEpochMilli(ms).atZone(z).toLocalDateTime()

    private val everything = WorldInput(
        trips = listOf(demo.trip, kyoto, lisbon, crete),
        stops = demo.stops + creteStop,
        entries = demo.entries + creteEntry + looseNote,
        media = demo.media + cretePhoto,
        planItems = demo.items,
        marks = listOf(franceByHand),
    )

    /** A pretend country lookup: Greece by a box, everything south of the equator and west of 50° W is "AR". */
    private val codeAt: (Double, Double) -> String? = { lat, lng ->
        when {
            lat in 34.0..42.0 && lng in 19.0..28.0 -> "GR"
            lat < 0 && lng < -50.0 -> "AR"
            else -> null
        }
    }

    private fun world(input: WorldInput) = WorldModel.build(input, today, zone, ms(today.atTime(12, 0)), codeAt)

    // ---- What shows ----

    @Test fun theExamplesAreDemoAndTheTravellersTripsAreNot() {
        for (id in listOf(SampleArgentina.TRIP_ID, SampleTrips.KYOTO, SampleTrips.LISBON, SampleTrips.CYCLADES, SampleArgentina.LEGACY_TRIP_ID)) {
            assertTrue(id, Trip.isDemoId(id))
            assertTrue(id, Trip(id, "x", createdAt = 0, updatedAt = 0).isDemo)
        }
        assertFalse(crete.isDemo)
        assertFalse(Trip.isDemoId("0f8e3c3e-5b3e-4a39-9a1c-6c2b0c1d2e3f"))
        assertFalse(Trip.isDemoId(null))
        assertFalse(Trip.isDemoId("my-sample-trip"))
    }

    @Test fun eachModeShowsOneSideOnly() = runTest {
        val mode = MutableStateFlow(true)
        val trips = flowOf(listOf(demo.trip, kyoto, lisbon, crete))
        val entries = flowOf(demo.entries + creteEntry + looseNote)
        // Demo mode: only the examples and what is in them.
        assertEquals(setOf(SampleArgentina.TRIP_ID, SampleTrips.KYOTO, SampleTrips.LISBON), trips.visibleIn(mode) { it.id }.first().map { it.id }.toSet())
        assertEquals(demo.entries.map { it.id }, entries.visibleIn(mode) { it.tripId }.first().map { it.id })
        // Off: only the traveller's own, notes outside any trip included.
        mode.value = false
        assertEquals(listOf(crete.id), trips.visibleIn(mode) { it.id }.first().map { it.id })
        assertEquals(listOf(creteEntry.id, looseNote.id), entries.visibleIn(mode) { it.tripId }.first().map { it.id })
        // Never both.
        for (on in listOf(true, false)) {
            for (id in listOf(SampleArgentina.TRIP_ID, crete.id, null)) {
                assertTrue(DemoTrips.visible(id, on) != DemoTrips.visible(id, !on))
            }
        }
    }

    @Test fun theNumbersComeFromWhatShows() {
        val own = world(DemoTrips.visibleWorld(everything, demoMode = false))
        val examples = world(DemoTrips.visibleWorld(everything, demoMode = true))
        // The traveller's own: Crete (Greece) and France marked by hand, 8 days away, 1 trip.
        assertEquals(setOf("GR", "FR"), own.visitedCodes)
        assertEquals(8, own.daysAway)
        assertEquals(1, own.trips)
        // The same numbers as their trips alone.
        val alone = world(WorldInput(trips = listOf(crete), stops = listOf(creteStop), entries = listOf(creteEntry, looseNote), media = listOf(cretePhoto), marks = listOf(franceByHand)))
        assertEquals(alone.visitedCodes, own.visitedCodes)
        assertEquals(alone.daysAway, own.daysAway)
        assertEquals(alone.trips, own.trips)

        // Demo mode: the examples only, the hand-marked France left out too.
        assertEquals(setOf("AR"), examples.visitedCodes)
        val shown = listOf(demo.trip, kyoto, lisbon)
        // Argentina's 15 days so far (day 15 today) and Lisbon's 9.
        assertEquals(TripStats.daysAway(shown, today), examples.daysAway)
        assertEquals(15 + 9, examples.daysAway)
        assertEquals(2, examples.trips)
        assertEquals(SampleArgentina.TRIP_ID, HomeStats.sections(shown, today).now?.id)
        assertTrue(examples.tripsById.keys.none { it == crete.id })
    }

    @Test fun demoModeStartsOnWhereverThereAreExamples() {
        // Fresh and older installs with the examples (trips of their own or not): on, with a reminder at each start.
        assertTrue(DemoTrips.initialMode(hasDemoTrips = true))
        // Release builds have no examples.
        assertFalse(DemoTrips.initialMode(hasDemoTrips = false))
    }

    @Test fun aNewTripOfTheTravellersOwnEndsDemoMode() {
        assertTrue(DemoTrips.leavesDemo(demoMode = true, saved = crete, existed = false))
        // Saving a trip that was already there, or an example, keeps demo mode.
        assertFalse(DemoTrips.leavesDemo(demoMode = true, saved = crete, existed = true))
        assertFalse(DemoTrips.leavesDemo(demoMode = true, saved = demo.trip, existed = false))
        assertFalse(DemoTrips.leavesDemo(demoMode = false, saved = crete, existed = false))
    }

    // ---- Backups ----

    @Test fun backupsAndRestoresNeverCarryTheDemo() {
        val strayDemoPhoto = Media("stray", null, demo.entries.first().id, "media/stray.jpg", createdAt = 60, updatedAt = 60)
        val snapshot = Snapshot(
            trips = listOf(demo.trip, kyoto, lisbon, crete),
            planItems = demo.items,
            entries = demo.entries + creteEntry + looseNote,
            media = demo.media + cretePhoto + strayDemoPhoto,
            trackPoints = listOf(TrackPoint(1, crete.id, 1, 35.5, 24.0), TrackPoint(2, SampleArgentina.TRIP_ID, 1, -34.6, -58.4)),
            stops = demo.stops + creteStop,
            countryMarks = listOf(franceByHand),
            books = listOf(
                BookDraft("b1", SampleArgentina.TRIP_ID, "a5", "notebook", "theme", false, "{}", createdAt = 1, updatedAt = 1),
                BookDraft("b2", crete.id, "a5", "notebook", "theme", false, "{}", createdAt = 1, updatedAt = 1),
            ),
        )
        val out = DemoTrips.withoutDemo(snapshot)
        assertEquals(listOf(crete.id), out.trips.map { it.id })
        assertTrue(out.planItems.isEmpty())
        assertEquals(listOf(creteEntry.id, looseNote.id), out.entries.map { it.id })
        // A photo filed under no trip but placed in a demo entry goes with the demo.
        assertEquals(listOf(cretePhoto.id), out.media.map { it.id })
        assertEquals(listOf(crete.id), out.trackPoints.map { it.tripId })
        assertEquals(listOf(creteStop.id), out.stops.map { it.id })
        assertEquals(listOf("b2"), out.books.map { it.id })
        assertEquals(listOf(franceByHand), out.countryMarks)
        assertEquals(listOf(cretePhoto.id), DemoTrips.withoutDemoMedia(snapshot.entries, snapshot.media).map { it.id })
    }

    // ---- Kept around today ----

    private fun rows() = listOf(
        DemoTrips.Rows(demo.trip.copy(subtitle = SampleArgentina.OLD_SUBTITLE), demo.items, demo.entries, demo.media.map { it.copy(updatedAt = 300) }, demo.stops),
        DemoTrips.Rows(kyoto.copy(subtitle = SampleTrips.OLD_SUBTITLE)),
        DemoTrips.Rows(lisbon),
    )

    @Test fun theDemoMovesSoTodayIsDay14Again() {
        val later = LocalDate.of(2027, 2, 20)
        val moved = DemoTrips.reanchor(rows(), later, now).associateBy { it.trip.id }
        val days = later.toEpochDay() - seededOn.toEpochDay()
        val argentina = moved.getValue(SampleArgentina.TRIP_ID)
        assertTrue(argentina.trip.isCurrent(later))
        assertEquals(14, argentina.trip.dayNumber(later))
        assertEquals(31, argentina.trip.days)
        // Kyoto stays as far ahead and Lisbon as far back.
        assertEquals(kyoto.startDate!!.plusDays(days), moved.getValue(SampleTrips.KYOTO).trip.startDate)
        assertEquals(lisbon.startDate!!.plusDays(days), moved.getValue(SampleTrips.LISBON).trip.startDate)
        assertTrue(moved.getValue(SampleTrips.KYOTO).trip.startDate!!.isAfter(later))
        assertTrue(moved.getValue(SampleTrips.LISBON).trip.isPast(later))
        // The whole demo moves: plan days, entry and photo times (same time of day), stays, the cover; ids and files stay.
        assertEquals(demo.items.map { it.id to it.day?.plusDays(days) }, argentina.items.map { it.id to it.day })
        assertEquals(demo.entries.map { it.id to local(it.occurredAt).plusDays(days) }, argentina.entries.map { it.id to local(it.occurredAt) })
        assertEquals(demo.media.map { Triple(it.id, it.localPath, local(it.takenAt!!).plusDays(days)) }, argentina.media.map { Triple(it.id, it.localPath, local(it.takenAt!!)) })
        assertEquals(demo.stops.map { local(it.arrivedAt).plusDays(days) }, argentina.stops.map { local(it.arrivedAt) })
        assertEquals(demo.trip.coverMediaId, argentina.trip.coverMediaId)
        assertEquals(demo.trip.travelModes, argentina.trip.travelModes)
        // The subtitles from before the Demo tag become the routes.
        assertEquals("Buenos Aires → Patagonia → Santiago", argentina.trip.subtitle)
        assertEquals("Kyoto → Osaka", moved.getValue(SampleTrips.KYOTO).trip.subtitle)
        // Seeded rows are restamped together, so they still read as untouched.
        val stamps = listOf(argentina.trip.createdAt to argentina.trip.updatedAt) + argentina.items.map { it.createdAt to it.updatedAt } +
            argentina.entries.map { it.createdAt to it.updatedAt } + argentina.media.map { it.createdAt to it.updatedAt } + argentina.stops.map { it.createdAt to it.updatedAt }
        assertTrue(stamps.all { it == now to now })
    }

    @Test fun movingAgainTheSameDayChangesNothing() {
        val first = DemoTrips.reanchor(rows(), today, now)
        assertTrue(first.isNotEmpty())
        val again = DemoTrips.reanchor(first, today, now + 1)
        assertTrue(again.isEmpty())
        // Seeded today with the new subtitles: nothing to do either.
        val fresh = SampleArgentina.demo(today, today.atTime(23, 0).atZone(zone).toInstant(), seeded)
        assertTrue(DemoTrips.reanchor(listOf(DemoTrips.Rows(fresh.trip, fresh.items, fresh.entries, fresh.media, fresh.stops)), today, now).isEmpty())
        // Without Argentina (gone or deleted) the rest stays where it is.
        assertTrue(DemoTrips.reanchor(rows().drop(1), today.plusDays(40), now).isEmpty())
        val deleted = rows()[0].let { it.copy(trip = it.trip.copy(deletedAt = 5)) }
        assertTrue(DemoTrips.reanchor(listOf(deleted) + rows().drop(1), today.plusDays(40), now).isEmpty())
    }

    @Test fun whatIsWrittenInTheDemoMovesWithItAsDemoData() {
        // An entry written on the demo trip in demo mode is the demo's: it moves with it and keeps its own creation stamp.
        val mine = Entry("written-in-demo", SampleArgentina.TRIP_ID, ms(seededOn.atTime(18, 0)), zone.id, "My note", createdAt = 500, updatedAt = 500)
        val r = rows()
        val moved = DemoTrips.reanchor(listOf(r[0].copy(entries = r[0].entries + mine)) + r.drop(1), today, now).first { it.trip.id == SampleArgentina.TRIP_ID }
        val note = moved.entries.single { it.id == mine.id }
        assertEquals(local(mine.occurredAt).plusDays(1), local(note.occurredAt))
        assertEquals(500L to now, note.createdAt to note.updatedAt)
    }

    // ---- Moving a demo by hand (Edit trip) ----

    @Test fun theWholeDemoMovesWithItsStart() {
        val rows = DemoTrips.Rows(demo.trip, demo.items, demo.entries, demo.media, demo.stops)
        val start = LocalDate.of(2025, 2, 10)
        val days = DemoTrips.delta(demo.trip, start)
        val moved = DemoTrips.shift(rows, days, ZoneId.of("Europe/Athens"))
        // The end follows the length.
        assertEquals(start, moved.trip.startDate)
        assertEquals(start.plusDays(30), moved.trip.endDate)
        assertEquals(demo.entries.map { local(it.occurredAt).plusDays(days) }, moved.entries.map { local(it.occurredAt) })
        assertEquals(demo.stops.map { s -> s.leftAt?.let { local(it).plusDays(days) } }, moved.stops.map { s -> s.leftAt?.let(::local) })
        // Stamps are the caller's; no move, no change.
        assertTrue(moved.entries.all { it.updatedAt == seeded })
        assertEquals(rows, DemoTrips.shift(rows, 0, zone))
        // A day the traveller named keeps its name on its new date.
        val day = demo.trip.startDate!!.plusDays(4)
        val named = rows.copy(trip = demo.trip.copy(travelModes = com.justdataplease.boringtravel.model.DayNames.set(demo.trip.travelModes, day, "Ice and wind")))
        assertEquals(mapOf(day.plusDays(days) to "Ice and wind"), com.justdataplease.boringtravel.model.DayNames.all(DemoTrips.shift(named, days, zone).trip.travelModes))
    }
}

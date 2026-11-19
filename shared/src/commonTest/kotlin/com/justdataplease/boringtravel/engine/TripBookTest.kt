package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupFormatException
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.clip.PlanRoute
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.ProgramLibrary
import com.justdataplease.boringtravel.programs.daysAt
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.time.localDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine end to end, as the iPhone app uses it: a trip idea becomes a trip, the plan is added to and ticked, a day
 * is named, a note is written, everything is kept and read back, a trip is deleted, and the kept text is the backup's
 * data.json. The Android app's own tests cover the same rules from its side (ProgramTripsTest, PlanSpanTest,
 * BackupCodecTest, TripRemovalTest, EngineOnAndroidTest).
 */
class TripBookTest {
    private var clock = 1_000L
    private var next = 0
    private val file = MemoryTripFile()
    private fun book() = TripBook(file, now = { clock }, ids = { "id-${next++}" })

    private val start = localDate(2027, 4, 1)
    private val kyoto = KyotoOsaka.destination

    @Test
    fun aTripIdeaBecomesATripWithItsPlan() {
        val book = book()
        val trip = book.startFromIdea(kyoto, kyoto.default, Pace.Balanced, start)
        val programDays = kyoto.default.daysAt(Pace.Balanced)

        assertEquals(kyoto.title, trip.title)
        assertEquals(start, trip.startDate)
        assertEquals(programDays.size, trip.days)
        assertEquals(listOf(trip), book.trips(today = start))

        val days = book.days(trip.id)
        assertEquals((1..programDays.size).toList(), days.map { it.number })
        assertEquals(programDays.map { it.items.size }, days.map { day -> day.steps.count { !it.end } })
        // Program order within each day, and every item says which program it came from.
        assertEquals(programDays.first().items.map { it.title.trim() }, days.first().steps.filter { !it.end }.map { it.item.title })
        assertTrue(book.items(trip.id).all { it.source == ItemSource.Program.id && it.externalId != null && !it.done })
        assertEquals(kyoto.default.ideas.size, book.ideas(trip.id).size)
    }

    @Test
    fun theLegsOfAnIdeaKeepTheirVehicles() {
        val book = book()
        val trip = book.startFromIdea(kyoto, kyoto.default, Pace.Balanced, start)
        val items = book.items(trip.id)
        val choices = TravelModes.choices(book.trip(trip.id)!!.travelModes)
        // Every saved choice is a leg of the plan's route, never Auto.
        val keys = PlanRoute.legKeys(trip, items).toSet()
        assertTrue(choices.isNotEmpty())
        assertTrue(choices.keys.all { it in keys })
        assertTrue(choices.values.none { it == Vehicle.Auto })
    }

    @Test
    fun everyIdeaBuildsAtEveryPace() {
        for (destination in ProgramLibrary.destinations) for (program in destination.programs) for (pace in Pace.entries) {
            val file = MemoryTripFile()
            val book = TripBook(file, now = { 5L }, ids = { "x-${next++}" })
            val trip = book.startFromIdea(destination, program, pace, start)
            assertEquals(program.daysAt(pace).size, book.days(trip.id).size, "${program.id} at ${pace.id}")
            // What was kept reads back as the same rows.
            assertEquals(book.snapshot, TripBook(file).snapshot, "${program.id} at ${pace.id}")
        }
    }

    @Test
    fun whatIsWrittenIsReadBackByANewBook() {
        val first = book()
        val trip = first.startFromIdea(kyoto, kyoto.default, Pace.Chill, start)
        clock = 2_000L
        val added = assertNotNull(first.addItem(trip.id, "  Evening walk by the river ", start, PlanKind.Activity, timeMinutes = 19 * 60 + 30))
        val idea = assertNotNull(first.addItem(trip.id, "Tea ceremony"))
        first.setDone(added.id, true)
        first.nameDay(trip.id, start, "Temples and tea")
        val note = assertNotNull(first.writeNote(trip.id, "Arrived late.\n\nThe lanterns were already lit.", title = "First night", at = 3_000L, zoneId = "Asia/Tokyo"))

        val second = TripBook(file)
        assertEquals(first.snapshot, second.snapshot)
        assertEquals("Evening walk by the river", second.items(trip.id).first { it.id == added.id }.title)
        assertTrue(second.items(trip.id).first { it.id == added.id }.done)
        assertEquals(2_000L, second.items(trip.id).first { it.id == added.id }.createdAt)
        assertNull(second.items(trip.id).first { it.id == idea.id }.day)
        assertEquals("Temples and tea", second.days(trip.id).first().name)
        // The added item is the last row of its day, after the idea's own.
        assertEquals(added.id, second.days(trip.id).first().steps.last().item.id)

        val kept = second.entries(trip.id).single()
        assertEquals(note, kept)
        assertEquals("Asia/Tokyo", kept.zoneId)
        assertEquals("Arrived late.\n\nThe lanterns were already lit.", kept.plainText)
        val doc = EntryDoc.parse(kept.body)
        assertEquals(2, doc.blocks.size)
        assertTrue(doc.blocks.all { it is Block.Paragraph })
    }

    @Test
    fun aChangeIsStampedAfterTheRowsLast() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(4))
        val item = assertNotNull(book.addItem(trip.id, "Halki", start))
        // The clock went back (a phone whose time was corrected): the stamp still moves forward.
        clock = 10L
        val ticked = assertNotNull(book.setDone(item.id, true))
        assertEquals(item.updatedAt + 1, ticked.updatedAt)
        // Ticking again changes nothing.
        assertEquals(ticked, book.setDone(item.id, true))
        assertNull(book.setDone("no-such-item", true))
        assertNull(book.addItem("no-such-trip", "Nowhere"))
        assertNull(book.writeNote(trip.id, "   "))
    }

    @Test
    fun tripsAreListedNowNextSomedayPast() {
        val book = book()
        val today = localDate(2027, 6, 10)
        val past = book.newTrip("Past", localDate(2027, 5, 1), localDate(2027, 5, 3))
        val someday = book.newTrip("Someday")
        val later = book.newTrip("Later", localDate(2027, 9, 1), localDate(2027, 9, 9))
        val now = book.newTrip("Now", localDate(2027, 6, 8), localDate(2027, 6, 12))
        val soon = book.newTrip("Soon", localDate(2027, 7, 1), localDate(2027, 7, 2))

        val sections = book.sections(today)
        assertEquals(now, sections.now)
        assertEquals(listOf(soon, later), sections.next)
        assertEquals(listOf(someday), sections.someday)
        assertEquals(listOf(past), sections.past)
        assertEquals(listOf(now, soon, later, someday, past), book.trips(today))
        assertEquals(5, now.days)
        assertEquals(3, now.dayNumber(today))
        assertNull(someday.days)
    }

    @Test
    fun deletingATripMarksEverythingInItAndKeepsTheRest() {
        val book = book()
        val gone = book.startFromIdea(kyoto, kyoto.default, Pace.Chill, start)
        val stays = book.newTrip("Naxos", start, start.plusDays(2))
        val other = assertNotNull(book.addItem(stays.id, "Halki", start))
        assertNotNull(book.writeNote(gone.id, "A note."))
        clock = 9_000L

        assertTrue(book.removeTrip(gone.id))
        assertFalse(book.removeTrip(gone.id))
        assertEquals(listOf(stays), book.trips(start))
        assertTrue(book.items(gone.id).isEmpty())
        assertTrue(book.entries(gone.id).isEmpty())
        assertEquals(listOf(other), book.items(stays.id))

        // The rows are still there, marked: a backup or another phone keeps them deleted.
        val reopened = TripBook(file).snapshot
        assertEquals(9_000L, reopened.trips.first { it.id == gone.id }.deletedAt)
        assertTrue(reopened.planItems.filter { it.tripId == gone.id }.all { it.deletedAt == 9_000L && it.updatedAt == 9_000L })
        assertTrue(reopened.entries.filter { it.tripId == gone.id }.all { it.deletedAt == 9_000L })
        assertEquals(1, reopened.items().trips)
        assertEquals(0, reopened.items().entries)
    }

    @Test
    fun theKeptTextIsTheBackupsDataJson() {
        val book = book()
        val trip = book.newTrip("Naxos", localDate(2027, 6, 17), localDate(2027, 6, 26), subtitle = "Cyclades")
        book.addItem(trip.id, "Halki", localDate(2027, 6, 18), PlanKind.Place, timeMinutes = 630, note = "Kitron distillery")

        // Field for field what the Android app writes for these rows (docs/BACKUP-FORMAT.md): defaults written, nulls left out.
        assertEquals(
            """{"trips":[{"id":"id-0","title":"Naxos","subtitle":"Cyclades","startDate":"2027-06-17","endDate":"2027-06-26","createdAt":1000,"updatedAt":1000}],""" +
                """"planItems":[{"id":"id-1","tripId":"id-0","day":"2027-06-18","position":1.0,"timeMinutes":630,"kind":"place","title":"Halki",""" +
                """"note":"Kitron distillery","placeName":"","done":false,"source":"manual","createdAt":1000,"updatedAt":1000}],""" +
                """"entries":[],"media":[],"trackPoints":[],"stops":[],"countryMarks":[],"books":[]}""",
            file.read(),
        )
        assertEquals(file.read(), book.export())
    }

    @Test
    fun aBackupFromTheOtherPhoneOpensAndItsOtherPartsTravelOn() {
        // Written by hand as the Android app writes it, with a field this version doesn't know and parts this class
        // never touches (a stop, shared trips).
        val android = """{"trips":[{"id":"t1","title":"Lisbon","subtitle":"","startDate":"2027-03-05","endDate":"2027-03-08","createdAt":5,"updatedAt":6,"fromTheFuture":true}],""" +
            """"planItems":[{"id":"p1","tripId":"t1","day":"2027-03-06","position":2.0,"kind":"food","title":"Pastéis de Belém","note":"","placeName":"Belém, Lisbon, Portugal",""" +
            """"lat":38.6975,"lng":-9.2032,"done":true,"source":"ai","createdAt":5,"updatedAt":7,"endDays":0,"endMinutes":720,"tags":"pastry","mapsUrl":"https://maps.app.goo.gl/x"}],""" +
            """"entries":[],"media":[],"trackPoints":[],"stops":[{"id":"s1","tripId":"t1","arrivedAt":10,"lat":38.7,"lng":-9.1,"name":"Alfama","source":"manual","createdAt":5,"updatedAt":5}],""" +
            """"countryMarks":[{"id":"manual:PT","code":"PT","source":"manual","addedAt":5,"updatedAt":5}],"books":[],"sharedTrips":"{\"v\":1}"}"""
        val book = book()
        book.restore(android)

        val trip = book.trips(localDate(2027, 3, 6)).single()
        assertEquals("Lisbon", trip.title)
        assertEquals(4, trip.days)
        val item = book.items(trip.id).single()
        assertEquals(PlanKind.Food.id, item.kind)
        assertEquals(localDate(2027, 3, 6), item.day)
        assertEquals(720, item.endMinutes)
        assertEquals("https://maps.app.goo.gl/x", item.mapsUrl)
        assertEquals("Alfama", book.snapshot.stops.single().name)
        assertEquals("PT", book.snapshot.countryMarks.single().code)

        // A change here keeps the stop, the country and the shared trips in the file.
        book.setDone(item.id, false)
        val after = BackupJson.decodeFromString(BackupData.serializer(), file.read()!!)
        assertEquals("{\"v\":1}", after.sharedTrips)
        assertEquals("Alfama", after.stops.single().name)
        assertEquals(false, after.planItems.single().done)
        assertEquals(TripBook(file).snapshot, book.snapshot)
    }

    @Test
    fun aTextThatIsNotABackupChangesNothing() {
        val book = book()
        val trip = book.newTrip("Naxos")
        val before = file.read()
        assertFailsWith<BackupFormatException> { book.restore("not json") }
        assertFailsWith<BackupFormatException> { book.restore("""{"trips":[{"id":"t","title":"T","startDate":"2027-02-30","createdAt":1,"updatedAt":1}]}""") }
        assertEquals(before, file.read())
        assertEquals(listOf(trip), book.trips(start))
    }
}

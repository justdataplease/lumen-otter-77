package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.backup.toData
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.util.Dates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trip store's edits, as the iPhone app's forms and sheets make them: a trip's name and dates (its plan and day
 * names moving with the start), plan items added, changed and deleted through the item sheet, and notes changed and
 * deleted after saving. Each one stays the backup's data.json.
 */
class TripBookEditTest {
    private var clock = 1_000L
    private var next = 0
    private val file = MemoryTripFile()
    private fun book() = TripBook(file, now = { clock }, ids = { "id-${next++}" })

    private val start = localDate(2027, 4, 1)
    private val kyoto = KyotoOsaka.destination

    /** The kept text read the way a restore on Android reads it. */
    private fun restored() = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read())).toSnapshot()

    @Test
    fun anEditedTripMovesItsPlanAndDayNamesWithItsStart() {
        val book = book()
        val trip = book.startFromIdea(kyoto, kyoto.default, Pace.Balanced, start)
        book.nameDay(trip.id, start.plusDays(2), "Temples and tea")
        val before = book.items(trip.id)
        val ideas = before.filter { it.day == null }
        clock = 2_000L

        val moved = assertNotNull(book.editTrip(trip.id, " Kyoto in May ", " Kyoto → Osaka ", start.plusDays(30), start.plusDays(30 + trip.days!! - 1L)))
        assertEquals("Kyoto in May", moved.title)
        assertEquals("Kyoto → Osaka", moved.subtitle)
        assertEquals(start.plusDays(30), moved.startDate)
        assertEquals(trip.days, moved.days)
        // Every dated item 30 days on, in the same order; the ideas where they were.
        val after = book.items(trip.id)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.day?.plusDays(30) }, after.map { it.day })
        assertEquals(ideas, after.filter { it.day == null })
        assertTrue(after.filter { it.day != null }.all { it.updatedAt == 2_000L })
        // The day's name went with its day.
        assertEquals(mapOf(start.plusDays(32) to "Temples and tea"), DayNames.all(book.trip(trip.id)!!.travelModes))
        assertEquals("Temples and tea", book.days(trip.id)[2].name)
        // Kept as a restore reads it.
        assertEquals(book.snapshot, restored())
    }

    @Test
    fun aTripsDatesCanBeTakenAwayAndPutBack() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(6))
        val item = assertNotNull(book.addItem(trip.id, "Ferry to Naxos", start, PlanKind.Transport))

        // No dates: a someday trip; the item keeps its day (it shows outside the dates until it is moved).
        val someday = assertNotNull(book.editTrip(trip.id, "Naxos", "", null, start.plusDays(3)))
        assertNull(someday.startDate)
        assertNull(someday.endDate)
        assertEquals(start, book.item(item.id)?.day)
        assertTrue(book.sections(start).someday.any { it.id == trip.id })

        // Dates again: from a trip without a start nothing moves. An end before the start is the start.
        val dated = assertNotNull(book.editTrip(trip.id, "Naxos", "", start.plusDays(10), start.plusDays(2)))
        assertEquals(start.plusDays(10), dated.endDate)
        assertEquals(start, book.item(item.id)?.day)

        // A blank name or a trip that isn't there changes nothing.
        assertNull(book.editTrip(trip.id, "  ", "", start, start))
        assertNull(book.editTrip("nope", "Paros", "", start, start))
        assertEquals("Naxos", book.trip(trip.id)?.title)
    }

    @Test
    fun anUnchangedTripIsNotWrittenAgain() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(6), subtitle = "Cyclades")
        val text = file.read()
        clock = 9_000L
        assertEquals(trip, book.editTrip(trip.id, "Naxos", "Cyclades", start, start.plusDays(6)))
        assertEquals(text, file.read())
    }

    @Test
    fun theItemSheetAddsChangesAndDeletesAnItem() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(6))
        clock = 2_000L
        val draft = book.draftItem(trip.id, start.plusDays(1), 1.0)
        assertNull(book.item(draft.id), "a draft isn't kept until it is saved")
        assertEquals(ItemSource.Manual.id, draft.source)

        // A concert from 22:00 until 01:30 the next morning: kept as ending the next day.
        val concert = draft.copy(title = "Concert at the castle", kind = PlanKind.Activity.id, timeMinutes = 22 * 60, endMinutes = 90, note = "Tickets at the door")
        val saved = assertNotNull(book.saveItem(concert))
        assertEquals(1, saved.endDays)
        assertEquals(2_000L, saved.createdAt)
        assertEquals(2_000L, saved.updatedAt)
        assertEquals("22:00 → ${Dates.dayShort(start.plusDays(2))} 01:30", PlanSpan.times(saved))
        assertEquals("3 h 30", PlanSpan.length(saved))
        // Its end is its own row on the next day.
        assertTrue(book.days(trip.id).single { it.date == start.plusDays(2) }.steps.single().end)

        // Changed: moved to Ideas, which have no end. Stamped later, never earlier, and made when it was.
        clock = 1_500L
        val idea = assertNotNull(book.saveItem(saved.copy(day = null, title = "Concert, maybe")))
        assertNull(idea.endDays)
        assertNull(idea.endMinutes)
        assertEquals(2_001L, idea.updatedAt)
        assertEquals(2_000L, idea.createdAt)
        assertEquals(listOf(idea), book.ideas(trip.id))

        // A blank title, a trip that isn't there: left out.
        assertNull(book.saveItem(idea.copy(title = " ")))
        assertNull(book.saveItem(book.draftItem("nope", null, 1.0).copy(title = "Lost")))

        // Deleted: gone from the plan, kept as deleted, and not brought back by a late save.
        clock = 3_000L
        assertTrue(book.removeItem(idea.id))
        assertFalse(book.removeItem(idea.id))
        assertTrue(book.items(trip.id).isEmpty())
        assertEquals(3_000L, book.snapshot.planItems.single().deletedAt)
        assertNull(book.saveItem(idea.copy(title = "Back again")))
        assertEquals(book.snapshot, restored())
    }

    @Test
    fun severalItemsAreSavedInOneGo() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(6))
        val a = assertNotNull(book.addItem(trip.id, "Breakfast", start))
        val b = book.draftItem(trip.id, start, 0.5).copy(title = "Sunrise walk")
        val saved = book.saveItems(listOf(a.copy(position = 2.0), b.copy(position = 1.0)))
        assertEquals(listOf(a.id, b.id), saved.map { it.id })
        assertEquals(listOf("Sunrise walk", "Breakfast"), book.items(trip.id).map { it.title })
    }

    @Test
    fun aPlainNoteIsChangedAndDeleted() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(6))
        val text = "Fish & chips at \"Yialos\" <the best>.\nIt's 30°.\n\nThe ferry was late."
        val note = assertNotNull(book.writeNote(trip.id, text, title = "Day one", at = 5_000L, zoneId = "Europe/Athens"))
        // What it says is what the editor gets back, letter for letter.
        assertEquals(text, book.noteText(note.id))

        clock = 6_000L
        val edited = assertNotNull(book.editNote(note.id, "The ferry was on time.\n\nSwam at Agia Anna.", "First day"))
        assertEquals("First day", edited.title)
        assertEquals("The ferry was on time.\n\nSwam at Agia Anna.", edited.plainText)
        assertEquals(listOf("The ferry was on time.", "Swam at Agia Anna."), EntryDoc.parse(edited.body).blocks.map { (it as Block.Paragraph).html })
        assertEquals(5_000L, edited.occurredAt)
        assertEquals("Europe/Athens", edited.zoneId)
        assertEquals(6_000L, edited.updatedAt)
        assertEquals("The ferry was on time.\n\nSwam at Agia Anna.", book.noteText(note.id))

        // Nothing left: not saved (the note stays as it was).
        assertNull(book.editNote(note.id, "  ", ""))
        assertEquals("First day", book.entry(note.id)?.title)

        clock = 7_000L
        assertTrue(book.removeNote(note.id))
        assertFalse(book.removeNote(note.id))
        assertTrue(book.entries(trip.id).isEmpty())
        assertNull(book.noteText(note.id))
        assertEquals(7_000L, book.snapshot.entries.single().deletedAt)
        assertEquals(book.snapshot, restored())
    }

    @Test
    fun aNoteWithMoreThanPlainTextIsNotChangedAsText() {
        // Written on Android: formatting, a photo, a checklist. Changing those as plain text would lose them.
        val doc = EntryDoc(font = "caveat", blocks = listOf(Block.Paragraph("A <b>great</b> day"), Block.Photo("photo-1"), Block.Checklist(emptyList())))
        val photo = Media("photo-1", tripId = "t", entryId = "e", localPath = "media/photo-1.jpg", createdAt = 1L, updatedAt = 1L)
        val other = Media("photo-2", tripId = "t", localPath = "media/photo-2.jpg", createdAt = 1L, updatedAt = 1L)
        val book = book()
        book.restore(
            BackupJson.encodeToString(
                BackupData.serializer(),
                Snapshot(
                    trips = listOf(Trip("t", "Naxos", createdAt = 1L, updatedAt = 1L)),
                    entries = listOf(Entry("e", "t", 1L, "UTC", body = doc.toJson(), plainText = "A great day", createdAt = 1L, updatedAt = 1L)),
                    media = listOf(photo, other),
                ).toData(),
            ),
        )
        clock = 10L
        assertNull(book.noteText("e"))
        assertNull(book.editNote("e", "A great day"))
        assertEquals("caveat", EntryDoc.parse(book.entry("e")?.body).font)
        // Deleting it takes its photo, not another one.
        assertTrue(book.removeNote("e"))
        assertEquals(10L, book.snapshot.media.single { it.id == "photo-1" }.deletedAt)
        assertNull(book.snapshot.media.single { it.id == "photo-2" }.deletedAt)
        assertEquals(book.snapshot, restored())
    }

    @Test
    fun aFriendsNoteInASharedTripStaysTheirs() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(6))
        val note = assertNotNull(book.writeNote(trip.id, "Mine"))
        val theirs = note.copy(id = "theirs", authorId = "friend")
        book.restore(BackupJson.encodeToString(BackupData.serializer(), book.snapshot.copy(entries = book.snapshot.entries + theirs).toData()))
        assertEquals("Mine", book.noteText("theirs"))
        assertNull(book.editNote("theirs", "Changed"))
        assertFalse(book.removeNote("theirs"))
        assertNotNull(book.editNote(note.id, "Changed"))
    }
}

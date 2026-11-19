package com.justdataplease.boringtravel.engine

import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.backup.toData
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.time.localDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Rich notes and their photos on the trip store, as the iPhone's editor saves them: the whole document (lists,
 * checklists, formatting, photos and their layouts), its plain text as the Android app makes it, notes outside trips,
 * and the media rows a backup carries the files by. Each change stays the backup's data.json.
 */
class TripBookNotesTest {
    private var clock = 1_000L
    private var next = 0
    private val file = MemoryTripFile()
    private fun book() = TripBook(file, now = { clock }, ids = { "id-${next++}" })

    private val start = localDate(2027, 6, 17)

    private fun restored() = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read())).toSnapshot()

    /** [rows] put into [book] the way a restore brings them. */
    private fun restore(book: TripBook, rows: Snapshot) = book.restore(BackupJson.encodeToString(BackupData.serializer(), rows.toData()))

    private val doc = EntryDoc(
        font = "caveat",
        blocks = listOf(
            Block.Heading("Halki", 3),
            Block.Paragraph("Forty minutes <b>late</b>, then <span style=\"color: rgba(196, 98, 45, 1.0)\">orange</span> light"),
            Block.Paragraph("Coffee", ListStyle.Bullet),
            Block.Paragraph("Kitron", ListStyle.Bullet),
            Block.Paragraph("Ferry", ListStyle.Number),
            Block.Paragraph("Bus", ListStyle.Number),
            Block.Checklist(listOf(CheckItem("Tickets", done = true), CheckItem("Sun cream"))),
            Block.Quote("Slowly, <i>slowly</i>"),
            Block.Album("3-top", listOf("p1", "p2", "p3"), mapOf("p2" to PhotoFrame.Polaroid)),
            Block.Photo("p4", rotation = -4f, width = 0.65f, frame = PhotoFrame.Tape, caption = "The square"),
            Block.Place("Halki, Naxos", 37.06, 25.5),
            Block.Divider,
        ),
    )

    @Test
    fun aRichNoteIsSavedWithItsWholeDocumentAndTheAndroidPlainText() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(6))
        clock = 2_000L
        val entry = Entry("note-1", trip.id, occurredAt = 1_800_000_000_000L, zoneId = "Europe/Athens", title = "Halki", placeName = "Halki", createdAt = 0L, updatedAt = 0L)
        val saved = assertNotNull(book.saveNote(entry, doc))
        assertEquals(doc, EntryDoc.parse(saved.body))
        assertEquals(2_000L, saved.createdAt)
        assertEquals(2_000L, saved.updatedAt)
        assertEquals(
            "Halki\n\nForty minutes late, then orange light\n\n• Coffee\n\n• Kitron\n\n1. Ferry\n\n2. Bus\n\n[x] Tickets\n[ ] Sun cream\n\nSlowly, slowly\n\nThe square\n\nHalki, Naxos",
            saved.plainText,
        )
        val preview = NoteText.preview(doc, max = 60)
        assertEquals("Halki Forty minutes late, then orange light • Coffee •…", preview)
        assertEquals(listOf(saved), book.entries(trip.id))
        assertEquals(book.snapshot, restored())

        // Saving what is kept changes nothing, not even the stamp.
        clock = 3_000L
        assertSame(book.entry("note-1"), book.saveNote(saved, doc))
        assertEquals(2_000L, book.entry("note-1")?.updatedAt)

        // A change: stamped now, created when it was.
        val edited = assertNotNull(book.saveNote(saved.copy(title = "Halki at noon"), doc.copy(blocks = doc.blocks.dropLast(1))))
        assertEquals(3_000L, edited.updatedAt)
        assertEquals(2_000L, edited.createdAt)
        assertEquals("Halki at noon", book.entry("note-1")?.title)
        assertEquals(book.snapshot, restored())
    }

    @Test
    fun aNoteOutsideTripsIsWrittenAndANoteInATripNeedsItsTrip() {
        val book = book()
        val loose = assertNotNull(book.saveNote(Entry("loose", null, 5_000L, "UTC", createdAt = 0L, updatedAt = 0L), EntryDoc.ofText("Bought a rucksack.")))
        assertNull(loose.tripId)
        assertEquals("Bought a rucksack.", book.entry("loose")?.plainText)
        assertNull(book.saveNote(Entry("lost", "no-such-trip", 5_000L, "UTC", createdAt = 0L, updatedAt = 0L), EntryDoc.ofText("Lost")))
        assertNull(book.entry("lost"))
        assertEquals(book.snapshot, restored())
    }

    @Test
    fun aFriendsNoteIsNotSavedOver() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(6))
        val mine = assertNotNull(book.writeNote(trip.id, "Mine"))
        restore(book, book.snapshot.copy(entries = book.snapshot.entries + mine.copy(id = "theirs", authorId = "friend")))
        assertNull(book.saveNote(book.entry("theirs")!!, EntryDoc.ofText("Changed")))
        assertNull(book.saveNote(mine.copy(authorId = "friend"), EntryDoc.ofText("Changed")))
        assertEquals("Mine", book.entry("theirs")?.plainText)
    }

    @Test
    fun aPhotosRowIsKeptAsABackupCarriesItAndRemovedLikeOnAndroid() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(6))
        clock = 4_000L
        val photo = book.addMedia(
            Media(
                "p1", trip.id, "note-1", localPath = "media/p1.jpg", mime = "image/jpeg", width = 4032, height = 3024,
                takenAt = 1_800_000_000_000L, lat = 37.06, lng = 25.5, sha256 = "ab".repeat(32), createdAt = 0L, updatedAt = 0L,
            ),
        )
        assertEquals(4_000L, photo.createdAt)
        assertEquals(photo, book.media("p1"))
        assertEquals(photo, restored().media.single())
        // The trip's cover goes with it.
        restore(book, book.snapshot.copy(trips = book.snapshot.trips.map { it.copy(coverMediaId = "p1") }))
        clock = 5_000L
        assertTrue(book.removeMedia("p1"))
        assertFalse(book.removeMedia("p1"))
        assertNull(book.media("p1"))
        assertEquals(5_000L, book.snapshot.media.single().deletedAt)
        assertNull(book.trip(trip.id)?.coverMediaId)
        assertEquals(book.snapshot, restored())
    }

    @Test
    fun deletingARichNoteTakesItsPhotosAndVideos() {
        val book = book()
        val trip = book.newTrip("Naxos", start, start.plusDays(6))
        for (id in listOf("p1", "p2", "p3", "p4", "other")) {
            book.addMedia(Media(id, trip.id, if (id == "other") null else "note-1", localPath = "media/$id.jpg", createdAt = 0L, updatedAt = 0L))
        }
        book.saveNote(Entry("note-1", trip.id, 1L, "UTC", createdAt = 0L, updatedAt = 0L), doc)
        clock = 9_000L
        assertTrue(book.removeNote("note-1"))
        assertEquals(listOf("other"), book.snapshot.media.filter { it.deletedAt == null }.map { it.id })
        assertEquals(book.snapshot, restored())
    }
}

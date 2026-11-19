package com.justdataplease.boringtravel.ios

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The journal editor driven as a traveller would on the desktop target: a rich note written on Android opened, edited
 * and kept whole; lists, checklists and formatting from the toolbar; photos and videos through the picker (a fake that
 * hands over drawn photos) into layouts, swapped, moved and removed; the camera; the viewer; a note outside trips.
 * After each, what was kept still reads as a backup's data.json, rows and files as the Android app keeps them.
 */
@OptIn(ExperimentalTestApi::class)
class NoteEditorOnDesktopTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking { BtFontFiles.load() }
    }

    /** The phone's pickers in a test: what [next] holds is "picked", its files put where a picker puts them. */
    private class FakePicker(private val files: MediaFiles) : MediaPicker {
        var next: List<PickedMedia> = emptyList()
        var shot: PickedMedia? = null
        private val bytes = HashMap<String, ByteArray>()

        /** A photo or video to hand over, its bytes kept until picked. */
        fun file(name: String, data: ByteArray, mime: String = MediaTypes.JPEG, durationMs: Long? = null): PickedMedia {
            bytes[name] = data
            return PickedMedia("picked/$name", mime, width = if (MediaTypes.isVideo(mime)) 1920 else 0, height = if (MediaTypes.isVideo(mime)) 1080 else 0, durationMs = durationMs)
        }

        private fun handOver(p: PickedMedia): PickedMedia {
            files.write(p.path, bytes.getValue(p.path.removePrefix("picked/")))
            return p
        }

        override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) = done(next.take(max).map(::handOver))
        override val hasCamera: Boolean = true
        override fun takePhoto(done: (PickedMedia?) -> Unit) = done(shot?.let(::handOver))
        override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
    }

    private fun ui(book: TripBook, kit: MediaKit, vararg at: Place, block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = 440, height = 2400) {
        setContent { BoringTravelApp(book, AppSettings.ready(), today, startAt = at.toList(), media = kit) }
        block()
        waitForIdle()
    }

    private fun ComposeUiTest.fields(): SemanticsNodeInteractionCollection = onAllNodes(hasSetTextAction())

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** What [file] holds, read the way a restore on Android reads a backup's data.json. */
    private fun assertKeptAsABackup(file: MemoryTripFile, book: TripBook) {
        val data = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read()))
        assertEquals(book.snapshot, data.toSnapshot())
        assertEquals(book.snapshot, TripBook(file).snapshot)
    }

    @Test
    fun aRichNoteWrittenOnAndroidOpensIsEditedAndKeepsEveryBlock() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val kit = MediaKit.memory()
        var n = 0
        val trip = book.newTrip("Kyoto", today.minusDays(2), today.plusDays(3))
        val note = richNote(book, kit, trip.id, "Asia/Tokyo", startOfDayMs(today, "Asia/Tokyo") + 8 * 3_600_000L) { "p-${n++}" }
        val before = EntryDoc.parse(note.body)
        ui(book, kit, Place.Trip(trip.id, TripTab.Journal), Place.Note(trip.id, note.id)) {
            // Read: the heading, the lists, the quote, the checklist, the photo's caption, the place.
            onNodeWithText("A thousand gates").assertExists()
            onNodeWithText("Fushimi Inari").assertExists()
            onNodeWithText("Matcha at the top").assertExists()
            onNodeWithText("Deer biscuits").assertExists()
            onNodeWithText("Go slowly, the mountain isn't going anywhere.").assertExists()
            onNodeWithText("Ryokan booked").assertExists()
            onNodeWithText("Last light on the Kamo").assertExists()
            onNodeWithText("Fushimi Inari Taisha").assertExists()
            onNodeWithText("Edit").performClick()
            // Rich notes open in the editor: the layout and the checklist among the text.
            onNodeWithText("Layout").assertExists()
            onNodeWithText("Postcards").assertExists()
            fields()[0].performTextReplacement("A thousand gates at dawn")
            // A new paragraph after the last one.
            val last = fields().onLast()
            last.performTextInputSelection(TextRange("More on the shrine's site.".length))
            last.performTextInput("\nBack by six.")
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            onNodeWithText("Back by six.").assertExists()
        }
        val after = assertNotNull(book.entry(note.id))
        val doc = EntryDoc.parse(after.body)
        assertEquals("A thousand gates at dawn", after.title)
        assertEquals(before.blocks + Block.Paragraph("Back by six."), doc.blocks)
        // Every block it had is written as Android wrote it, letter for letter.
        assertEquals(before.toJson(), EntryDoc(doc.font, doc.blocks.dropLast(1)).toJson())
        assertEquals(4, book.snapshot.media.count { it.deletedAt == null })
        assertKeptAsABackup(file, book)
    }

    @Test
    fun listsChecklistsAndFormattingComeFromTheToolbar() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val trip = book.newTrip("Naxos", today, today.plusDays(3))
        ui(book, MediaKit.memory(), Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id)) {
            fields()[0].performTextInput("Market day")
            fields()[1].performTextInput("Figs and cheese")
            fields()[1].performTextInputSelection(TextRange(0, 4))
            onNodeWithContentDescription("Bold").performClick()
            fields()[1].performTextInputSelection(TextRange(15))
            onNodeWithContentDescription("Lists").performClick()
            onNodeWithText("Bullets").performClick()
            // Enter goes on with the list.
            fields()[1].performTextInput("\nOlives")
            fields()[1].performTextInputSelection(TextRange(16, 22))
            onNodeWithContentDescription("Text style").performClick()
            onNodeWithText("Larger").performClick()
            fields()[1].performTextInputSelection(TextRange(22))
            onNodeWithContentDescription("Lists").performClick()
            onNodeWithText("Checklist").performClick()
            waitForIdle()
            // The checklist's first row is the next field.
            fields()[2].performTextInput("Bread")
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            onNodeWithText("Market day").assertExists()
        }
        val note = book.entries(trip.id).single()
        assertEquals("Market day", note.title)
        assertEquals(
            listOf(
                Block.Paragraph("<b>Figs</b> and cheese", ListStyle.Bullet),
                Block.Paragraph("<span style=\"font-size: 1.3em\">Olives</span>", ListStyle.Bullet),
                Block.Checklist(listOf(CheckItem("Bread"))),
            ),
            EntryDoc.parse(note.body).blocks,
        )
        assertEquals("• Figs and cheese\n\n• Olives\n\n[ ] Bread", note.plainText)
        assertKeptAsABackup(file, book)

        // The note shows them; a tick in the checklist is kept.
        ui(book, MediaKit.memory(), Place.Trip(trip.id, TripTab.Journal), Place.Note(trip.id, note.id)) {
            onNodeWithText("Figs and cheese").assertExists()
            onNodeWithContentDescription("Not done").performClick()
            waitForIdle()
            onNodeWithContentDescription("Done").assertExists()
        }
        assertEquals(Block.Checklist(listOf(CheckItem("Bread", done = true))), EntryDoc.parse(book.entry(note.id)?.body).blocks.last())
        assertKeptAsABackup(file, book)
    }

    @Test
    fun photosFromThePickerGoIntoALayoutThatIsSwappedMovedAndShortened() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val kit0 = MediaKit.memory()
        val picker = FakePicker(kit0.files)
        val kit = MediaKit(kit0.files, picker)
        val trip = book.newTrip("Naxos", today, today.plusDays(3))
        val photos = listOf(samplePhoto(Scene.Sea, 600, 450), samplePhoto(Scene.Town, 600, 450), samplePhoto(Scene.Olive, 450, 600))
        picker.next = photos.mapIndexed { i, p -> picker.file("p$i.jpg", p) }
        ui(book, kit, Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id)) {
            fields()[0].performTextInput("Halki")
            onNodeWithContentDescription("Choose photos and videos").performClick()
            waitUntil(timeoutMillis = 20_000) { book.snapshot.media.size == 3 && onAllNodesWithText("Layout").fetchSemanticsNodes().isNotEmpty() }
            // Three picked together: the usual layout for three, in the order picked.
            onNodeWithText("Layout").performClick()
            waitForIdle()
            onNodeWithText("Tap one, then another to swap them.").assertExists()
            onAllNodesWithContentDescription("Photo 1 of 3").onLast().performClick()
            onAllNodesWithContentDescription("Photo 3 of 3").onLast().performClick()
            onAllNodesWithContentDescription("Photo 1 of 3").onLast().performClick()
            onNodeWithText("Later").performClick()
            onNodeWithText("Remove").performClick()
            waitForIdle()
            onNodeWithText("2 photos").assertExists()
            onNodeWithText("Done").performClick()
            waitForIdle()
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            onNodeWithText("Halki").assertExists()
            onNodeWithText("2 photos", substring = true).assertExists()
        }
        val note = book.entries(trip.id).single()
        // The rows in the order picked (their ids are random): each by its file's checksum.
        val rows = photos.map { p -> book.snapshot.media.single { it.sha256 == sha(p) } }
        val (a, b, c) = rows
        // Picked a, b, c; swapped to c, b, a; c moved later: b, c, a; c removed: b, a in the usual layout for two.
        val album = EntryDoc.parse(note.body).blocks.single() as Block.Album
        assertEquals("2-side", album.layoutId)
        assertEquals(listOf(b.id, a.id), album.mediaIds)
        assertEquals(PhotoFrame.Rounded, album.frame)
        assertNotNull(book.snapshot.media.single { it.id == c.id }.deletedAt)
        // The rows and files a backup carries: media/<id>.jpg, its checksum and size, the note and trip it is in.
        rows.forEachIndexed { i, m ->
            assertEquals("media/${m.id}.jpg", m.localPath)
            assertEquals("image/jpeg", m.mime)
            assertEquals(note.id, m.entryId)
            assertEquals(trip.id, m.tripId)
            assertTrue(kit.files.read(m.localPath)!!.contentEquals(photos[i]))
            assertEquals(sha(photos[i]), m.sha256)
        }
        assertEquals(600 to 450, a.width to a.height)
        assertEquals(450 to 600, c.width to c.height)
        assertTrue((kit.files as MemoryMediaFiles).paths.none { it.startsWith("picked/") })
        assertKeptAsABackup(file, book)
    }

    @Test
    fun aVideoGoesInWholeAndPlaysAndOneTooLongIsTurnedAway() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val files = MemoryMediaFiles()
        val picker = FakePicker(files)
        val kit = MediaKit(files, picker)
        val trip = book.newTrip("Naxos", today, today.plusDays(3))
        val video = Random(5).nextBytes(64_000)
        picker.next = listOf(
            picker.file("v.mov", video, "video/quicktime", durationMs = 95_000),
            picker.file("long.mov", video, "video/quicktime", durationMs = 45 * 60_000L),
        )
        ui(book, kit, Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id)) {
            onNodeWithContentDescription("Choose photos and videos").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("This video is too long. Videos can be up to 30 minutes.").fetchSemanticsNodes().isNotEmpty() }
            // The one that went in: a tap on it, then Play; the desktop shows its first frame where it would play.
            onNodeWithContentDescription("Video").performClick()
            onNodeWithText("Play").performClick()
            waitForIdle()
            onNodeWithContentDescription("Close").performClick()
            waitForIdle()
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            onNodeWithText("1 video", substring = true).assertExists()
        }
        val note = book.entries(trip.id).single()
        val m = book.snapshot.media.single()
        assertEquals(listOf(Block.Photo(m.id, frame = PhotoFrame.Rounded)), EntryDoc.parse(note.body).blocks)
        assertEquals("video/quicktime", m.mime)
        assertEquals("media/${m.id}.mov", m.localPath)
        assertEquals(1920 to 1080, m.width to m.height)
        assertEquals(sha(video), m.sha256)
        assertTrue(files.read(m.localPath)!!.contentEquals(video))
        assertKeptAsABackup(file, book)
    }

    @Test
    fun aPhotoFromTheCameraOffersItsTimeAndTheDetailsSheetSetsThePlace() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val files = MemoryMediaFiles()
        val picker = FakePicker(files)
        val trip = book.newTrip("Naxos", today, today.plusDays(3))
        val shotAt = startOfDayMs(today, "Europe/Athens") + 9 * 3_600_000L
        picker.shot = picker.file("shot.jpg", samplePhoto(Scene.Sunset, 400, 300)).copy(takenAt = shotAt)
        ui(book, MediaKit(files, picker), Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id)) {
            onNodeWithContentDescription("Take a photo").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Use photo time", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Use photo time", substring = true).performClick()
            // When and where: the day's line on top opens them.
            onNodeWithText("${Dates.weekday(today)} ${Dates.dayMonth(today)}".uppercase()).performClick()
            onNodeWithText("Entry details").assertExists()
            fields().onLast().performTextInput("Halki")
            onNodeWithText("Done").performClick()
            waitForIdle()
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
        }
        val note = book.entries(trip.id).single()
        assertEquals(shotAt, note.occurredAt)
        assertEquals("Halki", note.placeName)
        val m = book.snapshot.media.single()
        assertEquals(listOf(Block.Photo(m.id, frame = PhotoFrame.Rounded)), EntryDoc.parse(note.body).blocks)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun aNotesPhotosAreOnlyLookedAtAsOnAndroid() {
        // Android's DocView makes only a note's videos tappable (they play); a photo opens nothing. The iPhone's own
        // full-screen viewer went in the parity round of 19 Nov 2026.
        val book = TripBook(MemoryTripFile())
        val kit = MediaKit.memory()
        var n = 0
        val trip = book.newTrip("Naxos", today, today.plusDays(3))
        val ids = listOf(Scene.Sea, Scene.Town, Scene.Olive).map { addSamplePhoto(book, kit, samplePhoto(it, 400, 300), trip.id, "note", { "v-${n++}" }).id }
        book.saveNote(Entry("note", trip.id, startOfDayMs(today, null) + 3_600_000L, "UTC", title = "Three", createdAt = 0L, updatedAt = 0L), EntryDoc(blocks = ids.map { Block.Photo(it) }))
        ui(book, kit, Place.Trip(trip.id, TripTab.Journal), Place.Note(trip.id, "note")) {
            val photos = onAllNodesWithContentDescription("Photo")
            photos.assertCountEquals(3)
            for (i in 0 until 3) photos[i].assert(hasNoClickAction())
            photos[0].performClick()
            waitForIdle()
            onNodeWithText("1 / 3").assertDoesNotExist()
            onNodeWithContentDescription("Close").assertDoesNotExist()
            onNodeWithText("Three").assertExists()
        }
    }

    @Test
    fun notesOutsideTripsHaveNoWriteAsOnAndroidAndANoteWrittenOutsideTripsListsThere() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        // Android's LooseNotesScreen has no Write (notes outside trips come from the widget's or a shortcut's Write
        // with no trip on): neither has the iPhone's since the parity round.
        ui(book, MediaKit.memory(), Place.LooseNotes) {
            onNodeWithText("No notes outside trips").assertExists()
            onNodeWithText("Notes written when no trip is on show up here.").assertExists()
            onNodeWithText("Write").assertDoesNotExist()
        }
        // Write with no trip (the widget's Write when none is on) over the list: the note goes outside trips.
        ui(book, MediaKit.memory(), Place.LooseNotes, Place.Write(null)) {
            fields()[0].performTextInput("Rucksack")
            fields()[1].performTextInput("Thirty litres, fits under a seat.")
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            onNodeWithText("Rucksack").assertExists()
        }
        val note = book.snapshot.entries.single()
        assertNull(note.tripId)
        assertEquals("Thirty litres, fits under a seat.", note.plainText)
        assertKeptAsABackup(file, book)
    }
}

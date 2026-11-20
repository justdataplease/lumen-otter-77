package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ios.AppCore
import com.justdataplease.boringtravel.ios.FileTripFile
import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.MemoryHomeWidget
import com.justdataplease.boringtravel.ios.MemoryMediaFiles
import com.justdataplease.boringtravel.ios.MemorySettingsFile
import com.justdataplease.boringtravel.ios.PhotoDefaults
import com.justdataplease.boringtravel.ios.PhotoEntries
import com.justdataplease.boringtravel.ios.PickedMedia
import com.justdataplease.boringtravel.ios.ShotTime
import com.justdataplease.boringtravel.ios.ToastLine
import com.justdataplease.boringtravel.ios.ToastTimes
import com.justdataplease.boringtravel.ios.Toasts
import com.justdataplease.boringtravel.ios.TripStoreLockedException
import com.justdataplease.boringtravel.ios.TripsHomeData
import com.justdataplease.boringtravel.ios.WidgetWriter
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 28's rules, on both the desktop's JVM and the iPhone Simulator: Android's toasts in turn for their times, the
 * trip a note without a trip goes into (Android's currentOn), a camera shot's time and its note made at once, the trips'
 * store as a file of its own with the old text moved over, the widgets following every write a moment later, and a
 * page break's printed pages.
 */
class LeftoversSmokeTest {
    private val today = localDate(2026, 11, 20)
    private var clock = 1_795_000_000_000L

    private fun book(file: MemoryTripFile = MemoryTripFile()) = TripBook(file, now = { clock++ })

    @Test
    fun toastsShowInTurnForAndroidsTimes() = runTest {
        val toasts = Toasts()
        backgroundScope.launch { toasts.run() }
        toasts.show("Copied")
        toasts.show("Restored 2 trips", long = true)
        toasts.show("   ")
        runCurrent()
        assertEquals(ToastLine("Copied"), toasts.showing)
        advanceTimeBy(ToastTimes.SHORT_MS - 1)
        runCurrent()
        assertEquals("Copied", toasts.showing?.text)
        advanceTimeBy(2)
        runCurrent()
        // Gone, and the next one after the fade.
        assertNull(toasts.showing)
        advanceTimeBy(ToastTimes.FADE_MS.toLong())
        runCurrent()
        assertEquals(ToastLine("Restored 2 trips", long = true), toasts.showing)
        advanceTimeBy(ToastTimes.LONG_MS - 100)
        runCurrent()
        assertTrue(toasts.showing?.long == true)
        advanceTimeBy(101 + ToastTimes.FADE_MS.toLong())
        runCurrent()
        // The blank one was never shown.
        assertNull(toasts.showing)
        // One shows and five wait their turn (Android keeps five per app): more are dropped.
        repeat(8) { toasts.show("Line $it") }
        val seen = ArrayList<String>()
        repeat(10) {
            runCurrent()
            toasts.showing?.let { seen += it.text }
            advanceTimeBy(ToastTimes.SHORT_MS + ToastTimes.FADE_MS)
        }
        assertEquals((0..ToastTimes.MAX_WAITING).map { "Line $it" }, seen)
    }

    @Test
    fun aNoteWithoutATripGoesIntoTheTripThatStartedLast() {
        val book = book()
        assertNull(TripsHomeData.currentOn(book, demoMode = false, day = today))
        val long = book.newTrip("Greece", today.minusDays(10), today.plusDays(10))
        assertEquals(long.id, TripsHomeData.currentOn(book, false, today)?.id)
        // A short trip inside the long one: Android's TripDao.allOn puts the one that started last first.
        val naxos = book.newTrip("Naxos", today.minusDays(1), today.plusDays(2))
        assertEquals(naxos.id, TripsHomeData.currentOn(book, false, today)?.id)
        // Two starting the same day: the one made last.
        val crete = book.newTrip("Crete", today.minusDays(1), today.plusDays(1))
        assertEquals(crete.id, TripsHomeData.currentOn(book, false, today)?.id)
        // Trips without dates, past and coming ones are never "on".
        book.newTrip("Someday")
        book.newTrip("Later", today.plusDays(3), today.plusDays(5))
        assertEquals(long.id, TripsHomeData.currentOn(book, false, today.minusDays(5))?.id)
        assertNull(TripsHomeData.currentOn(book, false, today.plusDays(30)))
        // In demo mode only the examples count.
        assertNull(TripsHomeData.currentOn(book, demoMode = true, day = today))
        // Deleted trips don't.
        book.removeTrip(crete.id)
        assertEquals(naxos.id, TripsHomeData.onDay(book.snapshot.trips, today)?.id)
    }

    @Test
    fun aShotTakesItsOwnTimeOnlyWhileTheCameraWasOpen() {
        val opened = 1_000_000L
        val back = opened + 60_000L
        assertEquals(opened + 10_000L, ShotTime.forShot(opened + 10_000L, opened, back))
        // A slow save or a clock a little off: up to two minutes either side.
        assertEquals(opened - ShotTime.SESSION_SLACK_MS, ShotTime.forShot(opened - ShotTime.SESSION_SLACK_MS, opened, back))
        assertEquals(back + ShotTime.SESSION_SLACK_MS, ShotTime.forShot(back + ShotTime.SESSION_SLACK_MS, opened, back))
        // A camera with a wrong clock, or no time at all: when it came back.
        assertEquals(back, ShotTime.forShot(opened - ShotTime.SESSION_SLACK_MS - 1, opened, back))
        assertEquals(back, ShotTime.forShot(null, opened, back))
    }

    @Test
    fun aShotBecomesANoteAtOnceWithThePhotoAsItsOnlyBlock() = runTest {
        val file = MemoryTripFile()
        val book = book(file)
        val files = MemoryMediaFiles()
        val trip = book.newTrip("Naxos", today, today.plusDays(3))
        files.write("picked/shot.jpg", ByteArray(64) { it.toByte() })
        var n = 0
        val id = PhotoEntries.create(
            book, files, PickedMedia("picked/shot.jpg", "image/jpeg"), trip.id, PhotoDefaults.Page,
            launchedAt = 5_000L, returnedAt = 9_000L, zoneId = "Europe/Athens", ids = { "shot-${n++}" },
        )
        val note = assertNotNull(book.entry(id))
        assertEquals(trip.id, note.tripId)
        assertEquals(9_000L, note.occurredAt)
        assertEquals("Europe/Athens", note.zoneId)
        val photo = EntryDoc.parse(note.body).blocks.single() as Block.Photo
        assertEquals(1f, photo.width)
        val media = assertNotNull(book.media(photo.mediaId))
        assertEquals(id, media.entryId)
        assertNotNull(files.read(media.localPath))
        assertNull(files.read("picked/shot.jpg"))
        assertEquals(book.snapshot, TripBook(file).snapshot)
        // A shot whose file never came: nothing is made, and Android's words say so.
        val before = book.snapshot
        assertFailsWith<Exception> {
            PhotoEntries.create(book, files, PickedMedia("picked/gone.jpg", "image/jpeg"), trip.id, PhotoDefaults.Page, 0L, 1L)
        }
        assertEquals(before, book.snapshot)
        assertEquals("The photo couldn't be added.", PhotoEntries.FAILED)
    }

    @Test
    fun aCameraNoteIsNamedFromItsPhotosPointElseStaysWithoutAPlaceAsOnAndroid() = runTest {
        val book = book()
        val trip = book.newTrip("Naxos", today, today.plusDays(3))
        val doc = EntryDoc(blocks = listOf(Block.Photo("m1")))
        fun note(id: String, lat: Double?) = assertNotNull(
            book.saveNote(
                com.justdataplease.boringtravel.data.db.Entry(id, tripId = trip.id, occurredAt = 1_000L, zoneId = "UTC", lat = lat, lng = lat?.let { 25.37 }, createdAt = 0, updatedAt = 0),
                doc,
            ),
        )
        val kit = com.justdataplease.boringtravel.ios.MediaKit.memory()
        // The photo's own point: named by the geocoder.
        val withPoint = com.justdataplease.boringtravel.ios.WriteModel(book, kit, trip.id, note("n1", 37.1).id, 1_000L)
        withPoint.placeShot(here = { 1.0 to 2.0 }, point = null, now = 2_000L) { _, _ -> "Chora" }
        assertEquals("Chora", withPoint.placeName)
        assertEquals(37.1, withPoint.lat)
        // Without one, Android's WriteViewModel has already taken the note for "No place": nothing more is looked for.
        val without = com.justdataplease.boringtravel.ios.WriteModel(book, kit, trip.id, note("n2", null).id, 1_000L)
        assertTrue(without.noPlace)
        without.placeShot(here = { 1.0 to 2.0 }, point = null, now = 2_000L) { _, _ -> "Somewhere" }
        assertEquals("", without.placeName)
        assertNull(without.lat)
    }

    @Test
    fun theTripsAreAFileOfTheirOwnAndTheOldTextMovesThere() {
        val files = MemoryMediaFiles()
        // A phone that kept its trips in UserDefaults before round 28.
        val old = MemoryTripFile()
        val seed = book(old)
        seed.newTrip("Kyoto", today, today.plusDays(4))
        val text = assertNotNull(old.read())
        var forgotten = 0
        val store = FileTripFile(files, legacy = old, forgetLegacy = { forgotten++ })
        assertEquals(text, store.read())
        assertEquals(text, files.read(FileTripFile.PATH)?.decodeToString())
        assertEquals(1, forgotten)
        // From now on the file is read; the old text is never looked at again.
        old.write("{}")
        assertEquals(text, store.read())
        assertEquals(1, forgotten)
        val book = TripBook(store, now = { clock++ })
        assertEquals("Kyoto", book.trips(today).single().title)
        book.newTrip("Naxos")
        assertEquals(book.snapshot, TripBook(FileTripFile(files)).snapshot)
        // A new phone: no file, nothing to move.
        assertNull(FileTripFile(MemoryMediaFiles()).read())
        // A file that is there but can't be read (iPhone: locked until the phone's first unlock) is never "no trips".
        val locked = object : MediaFiles by files {
            override fun read(path: String): ByteArray? = null
        }
        assertFailsWith<TripStoreLockedException> { FileTripFile(locked).read() }
        // A disk that takes nothing: the write says so, and the file stays as it was.
        val full = object : MediaFiles by files {
            override fun write(path: String, bytes: ByteArray): Boolean = false
        }
        val before = files.read(FileTripFile.PATH)!!.decodeToString()
        assertFailsWith<Exception> { FileTripFile(full).write("{}") }
        assertEquals(before, files.read(FileTripFile.PATH)!!.decodeToString())
        // A move that couldn't be written keeps the old text for the next start.
        val empty = MemoryMediaFiles()
        val emptyAndFull = object : MediaFiles by empty {
            override fun write(path: String, bytes: ByteArray): Boolean = false
        }
        var gone = 0
        assertEquals(text, FileTripFile(emptyAndFull, legacy = MemoryTripFile(text), forgetLegacy = { gone++ }).read())
        assertEquals(0, gone)
    }

    @Test
    fun theWidgetsFollowEveryWriteAMomentLater() = runTest {
        val core = AppCore(MemoryTripFile(), MemorySettingsFile())
        val widget = MemoryHomeWidget()
        val writer = WidgetWriter(widget)
        backgroundScope.launch { writer.follow(core.writes, core.book, { Look.Trail to false }) { today } }
        runCurrent()
        assertEquals(0, widget.saves)
        core.book.newTrip("Naxos", today, today.plusDays(2))
        // Typing doesn't write each letter: a change waits for the next one.
        advanceTimeBy(WidgetWriter.DEBOUNCE_MS - 100)
        core.book.newTrip("Crete", today.plusDays(10), today.plusDays(12))
        advanceTimeBy(WidgetWriter.DEBOUNCE_MS - 100)
        runCurrent()
        assertEquals(0, widget.saves)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, widget.saves)
        val days = assertNotNull(com.justdataplease.boringtravel.ios.WidgetSnapshot.parse(widget.snapshot!!)).days
        assertEquals("Naxos", days.first().title)
        assertEquals("trail", com.justdataplease.boringtravel.ios.WidgetSnapshot.parse(widget.snapshot!!)?.look)
    }

    @Test
    fun aPageBreakStartsANewPrintedPage() {
        val doc = EntryDoc(blocks = listOf(Block.Paragraph("Before"), Block.PageBreak, Block.Paragraph("After"), Block.PageBreak))
        val pages = DocRender.printPages(doc)
        assertEquals(listOf(listOf<Block>(Block.Paragraph("Before")), listOf<Block>(Block.Paragraph("After"))), pages.map { it.blocks })
        assertEquals(1, DocRender.printPages(EntryDoc(blocks = listOf(Block.Paragraph("One")))).size)
        assertTrue(DocRender.html(doc) { null }.contains("break-after:page"))
    }
}

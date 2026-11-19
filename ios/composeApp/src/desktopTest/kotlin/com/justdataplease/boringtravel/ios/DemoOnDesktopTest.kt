package com.justdataplease.boringtravel.ios

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.daysAt
import com.justdataplease.boringtravel.programs.data.KyotoOsaka
import com.justdataplease.boringtravel.programs.data.LisbonPorto
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Demo mode as the Android app has it: off on a store install, Me › Demo mode adds the example trips (Argentina on
 * now, Kyoto next, Lisbon past) with their photos and shows only them, Exit demo shows the traveller's own again, and
 * the traveller's own rows are never touched. The demo is kept around today, and data.json still reads as a backup.
 */
@OptIn(ExperimentalTestApi::class)
class DemoOnDesktopTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking { BtFontFiles.load() }
    }

    private fun ui(book: TripBook, settings: AppSettings, kit: MediaKit, vararg at: Place, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 440, height = 2400) {
            setContent { BoringTravelApp(book, settings, today, startAt = at.toList(), media = kit) }
            block()
            waitForIdle()
        }

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** The traveller's own rows, which demo mode must never change. */
    private fun own(s: Snapshot): List<Any> = listOf(
        s.trips.filterNot { it.isDemo }, s.planItems.filterNot { Trip.isDemoId(it.tripId) },
        s.entries.filterNot { Trip.isDemoId(it.tripId) }, s.media.filterNot { Trip.isDemoId(it.tripId) },
    )

    private fun assertKeptAsABackup(file: MemoryTripFile, book: TripBook) {
        val data = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read()))
        assertEquals(book.snapshot, data.toSnapshot())
        assertEquals(book.snapshot, TripBook(file).snapshot)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun demoModeShowsTheExamplesAndExitDemoBringsYoursBack() {
        val file = MemoryTripFile()
        file.write(sampleBook().export())
        val book = TripBook(file)
        val before = own(book.snapshot)
        val settings = AppSettings.ready()
        val files = MemoryMediaFiles()
        val kit = MediaKit(files)
        ui(book, settings, kit) {
            // A store install starts with demo mode off.
            onNodeWithText("Kyoto & Osaka").assertExists()
            assertFalse(settings.demoMode)
            onNode(isTab and hasText("Me")).performClick()
            onNodeWithText("Demo mode").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { book.snapshot.trips.count { it.isDemo && it.deletedAt == null } == 3 }
            onNode(isTab and hasText("Trips")).performClick()
            waitForIdle()
            onNodeWithText("Example trips. Yours are hidden.").assertExists()
            onNodeWithText("Argentina & Patagonia").assertExists()
            onNodeWithText("Now · day 14 of 31", ignoreCase = true).assertExists()
            onNodeWithText("Exit demo").assertExists()
            // The traveller's own trips are hidden, never changed.
            onNodeWithText("Naxos").assertDoesNotExist()
            onNodeWithText("Exit demo").performClick()
            waitUntil(timeoutMillis = 5_000) { !settings.demoMode }
            waitForIdle()
            onNodeWithText("Naxos").assertExists()
            onNodeWithText("Argentina & Patagonia").assertDoesNotExist()
        }
        assertEquals(before, own(book.snapshot))
        // The examples: Argentina on day 14, Kyoto next October, Lisbon in the past, each from Android's tables.
        val argentina = assertNotNull(book.trip(SampleArgentina.TRIP_ID))
        assertEquals(14, argentina.dayNumber(today))
        assertEquals(31, argentina.days)
        val items = book.items(argentina.id)
        assertEquals(SampleArgentina.rows.size, items.size)
        assertTrue(items.filter { it.day != null && it.day!!.toEpochDay() < today.toEpochDay() && it.kind != "note" }.all { it.done || it.title.startsWith("Vendimia: Acto") })
        assertTrue(items.none { it.day != null && it.day!!.toEpochDay() >= today.toEpochDay() && it.done })
        assertEquals(listOf("Still to book", "Aconcagua viewpoints", "Laguna Torre", "Photo credits"), items.filter { it.day == null }.map { it.title })
        assertEquals(SampleArgentina.days.size, book.entries(argentina.id).size)
        assertEquals(4, book.snapshot.stops.count { it.tripId == argentina.id })
        // 17 real photos, kept as an import keeps them: a file each, its checksum in its row.
        val photos = book.snapshot.media.filter { it.tripId == argentina.id && it.deletedAt == null }
        assertEquals(17, photos.size)
        for (m in photos) assertEquals(sha(assertNotNull(files.read(m.localPath), m.localPath)), m.sha256)
        assertNotNull(photos.firstOrNull { it.id == argentina.coverMediaId })
        val kyoto = assertNotNull(book.trip(SampleTrips.KYOTO))
        assertEquals(localDate(2027, 10, 3), kyoto.startDate)
        assertEquals(KyotoOsaka.destination.default.daysAt(Pace.Balanced).sumOf { it.items.size } + KyotoOsaka.destination.default.ideas.size, book.items(kyoto.id).size)
        assertTrue(book.items(kyoto.id).none { it.done })
        val lisbon = assertNotNull(book.trip(SampleTrips.LISBON))
        assertEquals(localDate(2026, 5, 9), lisbon.startDate)
        assertEquals(LisbonPorto.destination.default.days.size, lisbon.days)
        assertTrue(book.items(lisbon.id).filter { it.day != null && it.kind != "note" }.all { it.done })
        assertEquals(8, book.entries(lisbon.id).size)
        // Lisbon's pictures are drawn: written as JPEGs in Postcard Pop, drawn live in the theme on screen.
        val drawn = book.snapshot.media.filter { it.tripId == lisbon.id }
        assertEquals(8, drawn.size)
        assertTrue(drawn.all { DemoArt.live(it.localPath) != null && files.read(it.localPath) != null })
        assertKeptAsABackup(file, book)
    }

    @Test
    fun theDemoIsKeptAroundTodayAndMovesAsOne() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val settings = AppSettings.ready()
        val kit = MediaKit(MemoryMediaFiles())
        var clock = 1_800_000_000_000L
        var n = 0
        val demo = DemoMode(book, settings, kit.files, now = { clock++ }, ids = { "d-${n++}" })
        runBlocking { demo.set(true, today) }
        val note = book.entries(SampleArgentina.TRIP_ID).first()
        val noteDay = localDateAt(note.occurredAt, note.zoneId)
        // Five days on: the whole demo moves so today is day 14 again, the notes keep their time of day.
        val later = today.plusDays(5)
        demo.anchor(force = false, today = later)
        assertEquals(14, book.trip(SampleArgentina.TRIP_ID)?.dayNumber(later))
        val moved = assertNotNull(book.entry(note.id))
        assertEquals(noteDay.plusDays(5), localDateAt(moved.occurredAt, moved.zoneId))
        assertEquals((note.occurredAt - moved.occurredAt).mod(86_400_000L), 0L)
        assertEquals(book.trip(SampleTrips.KYOTO)?.startDate, localDate(2027, 10, 8))
        assertEquals(later.toEpochDay(), settings.demoAnchoredDay)
        // Once a day at most.
        val stamp = book.trip(SampleArgentina.TRIP_ID)?.updatedAt
        demo.anchor(force = false, today = later)
        assertEquals(stamp, book.trip(SampleArgentina.TRIP_ID)?.updatedAt)

        // Edit trip on an example: one start date moves it all.
        assertTrue(DemoMode.moveDemo(book, SampleTrips.LISBON, localDate(2026, 6, 1), "Lisbon & Porto", "Lisbon → Porto"))
        val lisbon = assertNotNull(book.trip(SampleTrips.LISBON))
        assertEquals(localDate(2026, 6, 1), lisbon.startDate)
        assertEquals(localDate(2026, 6, 1).plusDays(lisbon.days!! - 1L), lisbon.endDate)
        val first = book.entries(lisbon.id).first()
        assertEquals(localDate(2026, 6, 1), localDateAt(first.occurredAt, first.zoneId))

        // Off: the examples stay as rows, hidden; on again, a deleted example comes back once, nothing doubled.
        runBlocking { demo.set(false, later) }
        book.removeTrip(SampleTrips.KYOTO)
        runBlocking { demo.set(true, later) }
        assertEquals(1, book.snapshot.trips.count { it.id == SampleTrips.KYOTO })
        val live = book.items(SampleTrips.KYOTO)
        assertEquals(live.size, book.snapshot.planItems.count { it.tripId == SampleTrips.KYOTO && it.deletedAt == null })
        assertTrue(live.isNotEmpty())
        assertKeptAsABackup(file, book)
    }

    @Test
    fun theCalendarAndTripsSayDemoAndOfferTheWayOut() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val settings = AppSettings.ready()
        val kit = MediaKit(MemoryMediaFiles())
        runBlocking { DemoMode(book, settings, kit.files).set(true, today) }
        val example = assertNotNull(book.trip(SampleArgentina.TRIP_ID))
        ui(book, settings, kit, Place.Home(HomeTab.Calendar)) {
            onNodeWithText("DEMO").assertExists()
            assertTrue(onAllNodes(hasText(example.title)).fetchSemanticsNodes().isNotEmpty())
        }
        // Every example deleted: the way out, on Trips and on the Calendar.
        SampleTrips.CURRENT.forEach { book.removeTrip(it) }
        ui(book, settings, kit) {
            onNodeWithText("No example trips").assertExists()
            onNodeWithText("Exit demo to see your own trips and plan a new one.").assertExists()
            onNode(isTab and hasText("Calendar")).performClick()
            onNodeWithText("No example trips on the calendar.").assertExists()
            onNodeWithText("Exit demo").performClick()
            waitUntil(timeoutMillis = 5_000) { !settings.demoMode }
        }
        assertKeptAsABackup(file, book)
    }

    @Test
    fun eachStartInDemoModeSaysSoAndNewTripEndsIt() {
        val book = TripBook(MemoryTripFile())
        val settings = AppSettings.ready()
        val kit = MediaKit(MemoryMediaFiles())
        runBlocking { DemoMode(book, settings, kit.files).set(true, today) }
        ui(book, settings, kit) {
            onNodeWithText(DemoNotice.TEXT).assertExists()
            onNodeWithText(DemoNotice.ACTION).performClick()
            waitUntil(timeoutMillis = 5_000) { !settings.demoMode }
        }
        settings.demoMode = true
        ui(book, settings, kit, Place.NewTrip) {
            waitForIdle()
            assertFalse(settings.demoMode)
            onNodeWithText(DemoNotice.LEFT).assertExists()
        }
        // An example's notes are kept as notes: the journal reads them.
        val note = book.entries(SampleArgentina.TRIP_ID).last()
        assertTrue(EntryDoc.parse(note.body).mediaIds().isNotEmpty())
        assertNull(book.trip("nothing"))
    }
}

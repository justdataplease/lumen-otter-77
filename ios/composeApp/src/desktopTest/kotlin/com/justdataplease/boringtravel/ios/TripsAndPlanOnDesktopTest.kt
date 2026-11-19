package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.clip.Geo
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.settings.TripsLook
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trips home and a trip's plan driven as a traveller would on the desktop target: the sections and their tickets and
 * pictures, the Now card's Write today and camera, the plan's collapsed, expanded and Calendar views, dragging, a row's
 * menu, the travel between two places and Getting there. After each, what was kept still reads as a backup's data.json.
 */
@OptIn(ExperimentalTestApi::class)
class TripsAndPlanOnDesktopTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking { BtFontFiles.load() }
    }

    /** What the phone was asked to copy and share. */
    private class Asked {
        var copied: String? = null
        var shared: String? = null
        val phone = Phone(share = { _, text -> shared = text; true }, copy = { copied = it })
    }

    private fun ui(
        book: TripBook,
        vararg at: Place,
        settings: AppSettings = AppSettings.ready(),
        kit: MediaKit = MediaKit.memory(),
        asked: Asked = Asked(),
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 440, height = 2400) {
        setContent {
            CompositionLocalProvider(LocalPhone provides asked.phone) {
                BoringTravelApp(book, settings, today, startAt = at.toList(), media = kit)
            }
        }
        block()
        waitForIdle()
    }

    /** Whatever a screen reader would tap to do [label] ("Choose travel", "Open Hotel Grotta", a day's date). */
    private fun ComposeUiTest.byAction(label: String): SemanticsNodeInteraction =
        onAllNodes(SemanticsMatcher("does $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label })[0]

    private fun assertKeptAsABackup(file: MemoryTripFile, book: TripBook) {
        val data = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read()))
        assertEquals(book.snapshot, data.toSnapshot())
        assertEquals(book.snapshot, TripBook(file).snapshot)
    }

    /** The sample traveller (Kyoto on now, Naxos next, Iceland someday) and a past trip with two notes. */
    private fun traveller(file: MemoryTripFile = MemoryTripFile()): TripBook {
        val book = sampleBook()
        val past = book.newTrip("Lisbon in spring", localDate(2026, 5, 9), localDate(2026, 5, 17), subtitle = "Lisbon → Porto")
        book.writeNote(past.id, "Tram 28.", title = "Alfama")
        book.writeNote(past.id, "Port in Gaia.", title = "Gaia")
        file.write(book.export())
        return TripBook(file)
    }

    @Test
    fun theTripsAreTicketsNowNextSomedayAndPast() {
        val file = MemoryTripFile()
        val book = traveller(file)
        val kyoto = assertNotNull(book.sections(today).now)
        ui(book) {
            // The trip on now: its day, its days as a bar, Write today and the camera.
            onNodeWithText("Now · day 3 of ${kyoto.days}", ignoreCase = true).assertExists()
            onNodeWithText("Write today").assertExists()
            onNodeWithContentDescription("Take a photo").assertExists()
            onNodeWithText("days left").assertExists()
            onNodeWithText("Next", ignoreCase = true).assertExists()
            onNodeWithText("Someday", ignoreCase = true).assertExists()
            onNodeWithText("Past", ignoreCase = true).performScrollTo()
            // Naxos: its start and length, then its route (nothing planned yet); 211 days to go.
            onNodeWithText("17 Jun 2027 · 10 days", ignoreCase = true).assertExists()
            onNodeWithText("Cyclades by ferry").assertExists()
            onNodeWithText("days to go").assertExists()
            // The past trip counts its notes and how long ago it was.
            onNodeWithText("2 entries").assertExists()
            onNodeWithText("months ago").assertExists()
            // Without dates: the stub asks for them and opens Edit trip.
            onNodeWithText("Add dates").performClick()
            onNodeWithText("Edit trip").assertExists()
        }
        assertKeptAsABackup(file, book)
    }

    @Test
    fun picturesShowCoversAndTheirCountdowns() {
        val book = traveller()
        val kyoto = assertNotNull(book.sections(today).now)
        val settings = AppSettings.ready().apply { tripsLook = TripsLook.Pictures }
        ui(book, settings = settings) {
            onNodeWithText("Now · day 3 of ${kyoto.days}", ignoreCase = true).assertExists()
            onNodeWithText("Jun 2027 · 10 days", ignoreCase = true).assertExists()
            onNodeWithText("No dates yet").assertExists()
            onNodeWithText("Past", ignoreCase = true).performScrollTo()
            onNodeWithText("May 2026 · 9 days · 2 entries", ignoreCase = true).assertExists()
            // Tickets is Me's other choice; the same trips either way.
            onNodeWithText("Write today").assertExists()
        }
    }

    @Test
    fun writeTodayOpensWriteOverTheTripsJournal() {
        val file = MemoryTripFile()
        val book = traveller(file)
        val kyoto = assertNotNull(book.sections(today).now)
        val notes = book.entries(kyoto.id).size
        ui(book) {
            onNodeWithText("Write today").performClick()
            onAllNodes(hasSetTextAction())[0].performTextInput("Day three")
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            // Back from Write: the trip's Journal with the new note; Back again leaves the trip.
            onNodeWithText("Day three").assertExists()
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            onNodeWithText("Write today").assertExists()
        }
        assertEquals(notes + 1, book.entries(kyoto.id).size)
        assertKeptAsABackup(file, book)
    }

    /** A camera that hands over one drawn photo. */
    private class Camera(private val files: MediaFiles) : MediaPicker {
        override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) = done(emptyList())
        override val hasCamera: Boolean = true
        override fun takePhoto(done: (PickedMedia?) -> Unit) {
            files.write("picked/shot.jpg", samplePhoto(Scene.Sea, 800, 600))
            done(PickedMedia("picked/shot.jpg", MediaTypes.JPEG))
        }
        override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
    }

    @Test
    fun theCameraOnTheNowCardStartsANoteWithThePhoto() {
        val file = MemoryTripFile()
        val book = traveller(file)
        val kyoto = assertNotNull(book.sections(today).now)
        val files = MemoryMediaFiles()
        val kit = MediaKit(files, Camera(files))
        ui(book, kit = kit) {
            onNodeWithContentDescription("Take a photo").performClick()
            waitUntil(timeoutMillis = 10_000) { book.entries(kyoto.id).any { EntryDoc.parse(it.body).mediaIds().isNotEmpty() } }
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
        }
        val note = book.entries(kyoto.id).single { EntryDoc.parse(it.body).mediaIds().isNotEmpty() }
        val photo = assertNotNull(book.media(EntryDoc.parse(note.body).mediaIds().single()))
        assertNotNull(files.read(photo.localPath))
        assertKeptAsABackup(file, book)
    }

    @Test
    fun thePlanOpensCollapsedExpandsAndShowsTheCalendar() {
        val book = smallTripBook()
        val trip = book.trips(today).single()
        val start = assertNotNull(trip.startDate)
        val settings = AppSettings.ready()
        val ferry = book.items(trip.id).single { it.title == "Ferry from Paros" }
        ui(book, Place.Trip(trip.id), settings = settings) {
            // Collapsed by default, as on Android: one line per item, the time in its own column.
            onNodeWithText("Expand").assertExists()
            onNodeWithText("09:15").assertExists()
            onNodeWithText("Ferry from Paros").assertExists()
            onNodeWithText("Expand").performClick()
            // Every day in full: the times and length under the title.
            onNodeWithText("Collapse").assertExists()
            onNodeWithText(listOfNotNull(PlanSpan.times(ferry), PlanSpan.length(ferry)).joinToString(" · ")).assertExists()
        }
        assertTrue(settings.planExpanded)
        ui(book, Place.Trip(trip.id), settings = settings) {
            onNodeWithContentDescription("Calendar").performClick()
            waitForIdle()
            onNodeWithText("November 2026").assertExists()
            // The second day: its dinner under the month.
            byAction(Dates.dayLong(start.plusDays(1))).performClick()
            onNodeWithText("Dinner in Chora").assertExists()
            onNodeWithText("Ideas · 1").performClick()
            onNodeWithText("Portara at sunset").assertExists()
        }
        assertEquals(PlanView.Calendar, settings.planView)
    }

    @Test
    fun aDraggedItemIsSavedWithinItsDayAndOnAnotherDay() {
        val file = MemoryTripFile()
        val seed = smallTripBook()
        file.write(seed.export())
        val book = TripBook(file)
        val trip = book.trips(today).single()
        val start = assertNotNull(trip.startDate)
        val items = book.items(trip.id)
        // The List's rows as the Plan tab lays them out, then as a drag leaves them, and the drop as the tab saves it.
        fun rows() = PlanListLogic.rows(PlanListLogic.sections(trip.startDate, trip.endDate, book.items(trip.id), emptyMap(), setOf(today)), ideasHeader = true)
        fun drag(id: String, to: Int): List<PlanItem> {
            val list = rows().toMutableList()
            val from = list.indexOfFirst { it.key == "i-$id" }
            list.add(to, list.removeAt(from))
            val target = assertNotNull(PlanDrop.target(list, "i-$id"))
            val item = assertNotNull(book.item(id))
            return PlanDrop.drop(item, target.day, target.before, target.after).also { book.saveItems(it) }
        }
        val ferry = items.single { it.title == "Ferry from Paros" }
        val hotel = items.single { it.title == "Hotel Grotta" }
        // Within the first day: the hotel above the ferry; its time no longer fits, so it takes an hour before.
        val firstDay = rows().indexOfFirst { it is PlanListLogic.Row.DayHead && it.day == start }
        drag(hotel.id, firstDay + 1)
        val reread = TripBook(file)
        assertEquals(listOf("Hotel Grotta", "Ferry from Paros"), reread.items(trip.id).filter { it.day == start }.map { it.title })
        assertEquals(8 * 60 + 15, reread.item(hotel.id)?.timeMinutes)
        // Onto the second day, under the dinner (20:00): the day changes and the 09:15 ferry takes an hour after it.
        val dinnerRow = rows().indexOfFirst { it.key == "i-${items.single { i -> i.title == "Dinner in Chora" }.id}" }
        val saves = drag(ferry.id, dinnerRow)
        val again = TripBook(file)
        val moved = assertNotNull(again.item(ferry.id))
        assertEquals(start.plusDays(1), moved.day)
        assertEquals(21 * 60, moved.timeMinutes)
        assertEquals("Moved to ${Dates.dayShort(start.plusDays(1))} · 21:00", PlanDrop.notice(ferry, saves.first()))
        assertEquals(listOf("Dinner in Chora", "Ferry from Paros"), again.items(trip.id).filter { it.day == start.plusDays(1) }.map { it.title })
        assertKeptAsABackup(file, again)
    }

    @Test
    fun aRowHeldAndDraggedOnTheListIsSavedWithUndo() {
        val file = MemoryTripFile()
        file.write(smallTripBook().export())
        val book = TripBook(file)
        val trip = book.trips(today).single()
        val start = assertNotNull(trip.startDate)
        val hotel = book.items(trip.id).single { it.title == "Hotel Grotta" }
        ui(book, Place.Trip(trip.id)) {
            // Held, the hotel lifts; moved up past the ferry and let go, it is saved there with a time that fits.
            byAction("Open Hotel Grotta").performTouchInput {
                down(center)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
                repeat(16) {
                    moveBy(Offset(0f, -6f))
                    advanceEventTime(16)
                }
                up()
            }
            waitForIdle()
            onNodeWithText("Moved to ${Dates.dayShort(start)} · 08:15").assertExists()
        }
        val reread = TripBook(file)
        assertEquals(listOf("Hotel Grotta", "Ferry from Paros"), reread.items(trip.id).filter { it.day == start }.map { it.title })
        assertEquals(8 * 60 + 15, reread.item(hotel.id)?.timeMinutes)
    }

    @Test
    fun holdingARowOpensItsMenuToCopyAndShare() {
        val book = smallTripBook()
        val trip = book.trips(today).single()
        val asked = Asked()
        val hotel = book.items(trip.id).single { it.title == "Hotel Grotta" }
        ui(book, Place.Trip(trip.id), asked = asked) {
            byAction("Open Hotel Grotta").performSemanticsAction(SemanticsActions.OnLongClick)
            onNodeWithText("Copy name").performClick()
            byAction("Open Hotel Grotta").performSemanticsAction(SemanticsActions.OnLongClick)
            onNodeWithText("Share").performClick()
        }
        assertEquals("Hotel Grotta", asked.copied)
        assertEquals(PlanItemText.of(hotel), asked.shared)
    }

    /** Buenos Aires on the first day, a winery in Mendoza on the second: about 985 km apart. */
    private fun argentinaBook(file: MemoryTripFile): Pair<TripBook, String> {
        val book = TripBook(file)
        val trip = book.newTrip("Argentina", today, today.plusDays(3))
        book.saveItem(book.draftItem(trip.id, today, 1.0).copy(title = "Recoleta Cemetery", lat = -34.5875, lng = -58.3934))
        book.saveItem(book.draftItem(trip.id, today.plusDays(1), 1.0).copy(title = "Winery", lat = -32.8894, lng = -68.8445))
        return book to trip.id
    }

    /** The Android app's rough time: its speeds and detours (model/TravelTime.kt), then LegEstimate's rounding. */
    private fun androidEstimate(distanceM: Double, kmh: Double, detour: Double, overhead: Int): String {
        val m = (distanceM / 1000.0 * detour / kmh * 60.0 + overhead).roundToInt()
        val r = when {
            m < 60 -> ((m + 2) / 5 * 5).coerceAtLeast(5)
            m < 180 -> ((m + 7) / 15) * 15
            else -> ((m + 30) / 60) * 60
        }
        return if (r < 60) "≈ $r min" else if (r % 60 == 0) "≈ ${r / 60} h" else "≈ ${r / 60} h ${(r % 60).toString().padStart(2, '0')}"
    }

    @Test
    fun theTravelBetweenTwoPlacesIsShownChosenAndCounted() {
        val file = MemoryTripFile()
        val (book, tripId) = argentinaBook(file)
        val items = book.items(tripId)
        val distance = Geo.distanceM(-34.5875, -58.3934, -32.8894, -68.8445)
        val settings = AppSettings.ready().apply { planExpanded = true }
        ui(book, Place.Trip(tripId), settings = settings) {
            // Unchosen over 700 km: a flight, left out of the km; it starts on the day before.
            onNodeWithText("Plane · ${androidEstimate(distance, 700.0, 1.05, 30)}").assertExists()
            onNodeWithText("from Recoleta Cemetery · not in km").assertExists()
            byAction("Choose travel").performClick()
            onNodeWithText("Travel for this leg").assertExists()
            onNodeWithText("Recoleta Cemetery → Winery").assertExists()
            byAction("Car").performClick()
            waitForIdle()
            onNodeWithText("Car · ${androidEstimate(distance, 65.0, 1.35, 0)}").assertExists()
        }
        val key = PlanTravelRows.incoming(assertNotNull(book.trip(tripId)), items).values.single().leg.key
        assertEquals(Vehicle.Car, TravelModes.choices(book.trip(tripId)?.travelModes)[key])
        assertKeptAsABackup(file, book)

        ui(book, Place.Trip(tripId), settings = settings) {
            byAction("Choose travel").performClick()
            // A car counts by default: switched off, the choice is kept; then the leg goes back to its default.
            onNodeWithText("Count in km").performClick()
            waitForIdle()
            assertEquals(false, TravelModes.kmChoices(book.trip(tripId)?.travelModes)[key])
            onNodeWithText("Leave unselected").performClick()
            waitForIdle()
        }
        assertNull(TravelModes.choices(book.trip(tripId)?.travelModes)[key])
        assertKeptAsABackup(file, book)
    }

    @Test
    fun gettingThereInTheItemSheetSavesTheLegsVehicle() {
        val file = MemoryTripFile()
        val (book, tripId) = argentinaBook(file)
        val distance = Geo.distanceM(-34.5875, -58.3934, -32.8894, -68.8445)
        ui(book, Place.Trip(tripId)) {
            // The row, not its day's title (which opens the place page around the winery and says so too).
            onAllNodes(SemanticsMatcher("does Open Winery") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open Winery" }).onLast().performClick()
            onNodeWithText("GETTING THERE").performScrollTo()
            onNodeWithText("From Recoleta Cemetery · ${androidEstimate(distance, 700.0, 1.05, 30)}").assertExists()
            byAction("Train").performClick()
            waitForIdle()
            onNodeWithText("From Recoleta Cemetery · ${androidEstimate(distance, 80.0, 1.25, 0)}").assertExists()
        }
        val trip = assertNotNull(book.trip(tripId))
        assertEquals(Vehicle.Train, TravelModes.choices(trip.travelModes).values.single())
        assertKeptAsABackup(file, book)
    }

    @Test
    fun deletingATripDeletesItsPhotosFilesButNotAnotherTrips() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val files = MemoryMediaFiles()
        val kit = MediaKit(files)
        var n = 0
        val ids = { "id-${n++}" }
        val gone = book.newTrip("Naxos", today, today.plusDays(2))
        val kept = book.newTrip("Paros", today.plusDays(10), today.plusDays(12))
        val photo = addSamplePhoto(book, kit, samplePhoto(Scene.Sea, 400, 300), gone.id, null, ids)
        val other = addSamplePhoto(book, kit, samplePhoto(Scene.Town, 400, 300), kept.id, null, ids)
        val note = book.writeNote(gone.id, "Swim.", title = "Agia Anna", at = startOfDayMs(today, "Europe/Athens") + 3_600_000L, zoneId = "Europe/Athens")
        assertNotNull(note)
        ui(book, Place.Trip(gone.id), Place.EditTrip(gone.id), kit = kit) {
            onNodeWithText("Delete trip").performClick()
            onNodeWithText(DeleteTripText.BODY).assertExists()
            onAllNodesWithText("Delete").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            waitForIdle()
        }
        assertNull(book.trip(gone.id))
        assertNull(files.read(photo.localPath))
        assertNotNull(files.read(other.localPath))
        assertNotNull(book.snapshot.media.single { it.id == photo.id }.deletedAt)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun aTripIdeasTrainShowsOnTheExpandedList() {
        val book = sampleBook()
        val trip = assertNotNull(book.sections(today).now)
        val settings = AppSettings.ready().apply { planExpanded = true }
        ui(book, Place.Trip(trip.id, TripTab.Plan), settings = settings) {
            // The day's own name stays in its title; the plan's legs are on the rows.
            onAllNodesWithText("Day 3 · Temples and tea").fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
            assertTrue(onAllNodes(SemanticsMatcher("a leg") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Choose travel" }).fetchSemanticsNodes().isNotEmpty())
        }
        assertEquals(PlanKind.Activity.id, book.items(trip.id).single { it.title.startsWith("Evening walk") }.kind)
        assertTrue(book.items(trip.id).any { it.kind == PlanKind.Transport.id })
        assertTrue(EntryDoc.parse(book.entries(trip.id).single().body).blocks.first() is Block.Paragraph)
    }
}

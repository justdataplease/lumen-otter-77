package com.justdataplease.boringtravel.ios

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.DayNames
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.programs.Focus
import com.justdataplease.boringtravel.programs.Pace
import com.justdataplease.boringtravel.programs.ProgramLibrary
import com.justdataplease.boringtravel.programs.daysAt
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.today
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The new screens driven as a traveller would on the desktop target: taps and typing on New trip, Edit trip, a trip
 * idea's questions and start date, the plan item sheet and the notes. After each, what was kept is still the backup's
 * data.json, as the Android app's restore reads it.
 */
@OptIn(ExperimentalTestApi::class)
class EditingOnDesktopTest {
    private val today = SAMPLE_TODAY

    /** The pickers open on the month it is now: the days picked there are in it. */
    private val month = today().civil
    private fun inMonth(day: Int): LocalDate = localDate(month.year, month.month, day)

    init {
        runBlocking { BtFontFiles.load() }
    }

    private fun ui(book: TripBook, vararg at: Place, block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = 440, height = 1800) {
        // The List expanded: the rows say their times and lengths under the title.
        setContent { BoringTravelApp(book, AppSettings.ready().apply { planExpanded = true }, today, startAt = at.toList()) }
        block()
        waitForIdle()
    }

    /** The text fields on screen, in order. */
    private fun ComposeUiTest.fields(): SemanticsNodeInteractionCollection = onAllNodes(hasSetTextAction())

    /**
     * The day cells of the open date picker that are on screen, in order: the first month shown first (a single-day
     * picker also lays out the months beside the one shown, out of sight). The page's chips are selectable too: they
     * are tabs.
     */
    private fun ComposeUiTest.days(): List<SemanticsNodeInteraction> {
        val cells = onAllNodes(isSelectable() and hasClickAction() and !SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        return cells.fetchSemanticsNodes().indices.map { cells[it] }.filter { it.isDisplayed() }
    }

    /** What [file] holds, read the way a restore on Android reads a backup's data.json. */
    private fun assertKeptAsABackup(file: MemoryTripFile, book: TripBook) {
        val data = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read()))
        assertEquals(book.snapshot, data.toSnapshot())
        assertEquals(book.snapshot, TripBook(file).snapshot)
    }

    @Test
    fun aTripIsMadeByHand() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        ui(book) {
            onNodeWithText("New trip").performClick()
            // Make it myself first, then the AI ways, then the ready-made trips.
            onNodeWithText("Start from a ready-made trip").assertExists()
            onNodeWithText("From a questionnaire").assertExists()
            onNodeWithText("Make it myself").performClick()
            fields()[0].performTextInput("Naxos")
            fields()[1].performTextInput("Paros → Naxos")
            onNodeWithText("Someday trips keep ideas until you pick dates.").assertExists()
            onNodeWithText("No dates yet").performClick()
            days()[19].performClick()
            days()[23].performClick()
            onNodeWithText("Done").performClick()
            onNodeWithText(Dates.range(inMonth(20), inMonth(24))).assertExists()
            onNodeWithText("Save").performClick()
            waitForIdle()
            // The new trip opens.
            onNodeWithText("Naxos", ignoreCase = true).assertExists()
            onNodeWithText("Add a place or a note").assertExists()
        }
        val trip = book.trips(today).single()
        assertEquals("Naxos", trip.title)
        assertEquals("Paros → Naxos", trip.subtitle)
        assertEquals(inMonth(20), trip.startDate)
        assertEquals(inMonth(24), trip.endDate)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun aTripsNameAndDatesAreChangedAndItsPlanMovesWithThem() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val trip = book.newTrip("Naxos", inMonth(10), inMonth(14), subtitle = "Cyclades")
        val ferry = assertNotNull(book.addItem(trip.id, "Ferry to Naxos", inMonth(10), PlanKind.Transport))
        val idea = assertNotNull(book.addItem(trip.id, "Portara at sunset", null))
        book.nameDay(trip.id, inMonth(11), "Chora")
        ui(book, Place.Trip(trip.id)) {
            onNodeWithContentDescription("Trip options").performClick()
            onNodeWithText("Edit trip").performClick()
            fields()[0].performTextReplacement("Naxos and Paros")
            onNodeWithText(Dates.range(inMonth(10), inMonth(14))).performClick()
            days()[19].performClick()
            days()[23].performClick()
            onNodeWithText("Done").performClick()
            onNodeWithText("Planned days move with the new dates.").assertExists()
            onNodeWithText("Save").performClick()
        }
        val edited = assertNotNull(book.trip(trip.id))
        assertEquals("Naxos and Paros", edited.title)
        assertEquals("Cyclades", edited.subtitle)
        assertEquals(inMonth(20), edited.startDate)
        assertEquals(inMonth(24), edited.endDate)
        assertEquals(inMonth(20), book.item(ferry.id)?.day)
        assertNull(book.item(idea.id)?.day)
        assertEquals(mapOf(inMonth(21) to "Chora"), DayNames.all(edited.travelModes))
        assertKeptAsABackup(file, book)

        // No dates yet: a someday trip, and the plan keeps its days.
        ui(book, Place.Trip(trip.id), Place.EditTrip(trip.id)) {
            onNodeWithText(Dates.range(inMonth(20), inMonth(24))).performClick()
            onAllNodesWithText("No dates yet").onLast().performClick()
            onNodeWithText("Someday trips keep ideas until you pick dates.").assertExists()
            onNodeWithText("Save").performClick()
        }
        assertNull(book.trip(trip.id)?.startDate)
        assertEquals(inMonth(20), book.item(ferry.id)?.day)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun aTripIdeaAsksItsTwoQuestionsAndTheFirstDay() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val kyoto = ProgramLibrary.destinations.first()
        val program = kyoto.program(Focus.Food)
        ui(book, Place.Ideas) {
            onNodeWithText(kyoto.title).performClick()
            onNodeWithText("How do you like to travel?").assertExists()
            onNodeWithText(Pace.Chill.label).performClick()
            onNodeWithText(Focus.Food.label).performClick()
            onNodeWithText(Pace.Chill.blurb).assertExists()
            onNodeWithText(Focus.Food.blurb).assertExists()
            onNodeWithText("Use this program").performClick()
            onNodeWithText("Pick the first day").assertExists()
            days()[2].performClick()
            onNodeWithText(Dates.range(inMonth(3), inMonth(3).plusDays(program.days.size - 1L))).assertExists()
            onNodeWithText("Make the trip").performClick()
            waitForIdle()
            // The trip opens on its plan.
            onNodeWithText("Day 1", substring = true).assertExists()
        }
        val trip = book.trips(today).single()
        assertEquals(inMonth(3), trip.startDate)
        assertEquals(program.days.size, trip.days)
        val chill = program.daysAt(Pace.Chill)
        assertEquals(chill.flatMap { day -> day.items.map { it.title.trim() } }, book.items(trip.id).filter { it.day != null }.map { it.title })
        assertTrue(book.items(trip.id).all { it.externalId?.startsWith(program.id) == true })
        assertKeptAsABackup(file, book)
    }

    @Test
    fun aPlanItemIsAddedTickedChangedAndDeleted() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val trip = book.newTrip("Naxos", today, today.plusDays(3))
        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        ui(book, Place.Trip(trip.id)) {
            onNodeWithText("Add a place or a note").performClick()
            onNodeWithText("Add to your plan").assertExists()
            onNodeWithText("Food").performClick()
            fields()[0].performTextInput("Lunch at the harbour")
            onNodeWithText("Add a time").performClick()
            // The clock opens at 09:00; its Done is the last one on screen.
            onAllNodesWithText("Done").onLast().performClick()
            onNodeWithText("09:00").assertExists()
            onNodeWithText("+2 h").performClick()
            onNodeWithText("2 h").assertExists()
            onNodeWithText("Done").performClick()
            waitForIdle()
            onNodeWithText("09:00–11:00 · 2 h").assertExists()
        }
        val added = book.items(trip.id).single()
        assertEquals("Lunch at the harbour", added.title)
        assertEquals(PlanKind.Food.id, added.kind)
        assertEquals(today, added.day)
        assertEquals(9 * 60, added.timeMinutes)
        assertEquals(11 * 60, added.endMinutes)
        assertKeptAsABackup(file, book)

        ui(book, Place.Trip(trip.id)) {
            // The circle ticks it off.
            onNode(checkbox).performClick()
            waitForIdle()
        }
        assertTrue(book.item(added.id)!!.done)

        ui(book, Place.Trip(trip.id)) {
            onNodeWithText("Lunch at the harbour").performClick()
            onNodeWithText("Edit plan item").assertExists()
            fields()[0].performTextReplacement("Lunch in Halki")
            // The note: after the title, the place's search and the website.
            fields()[3].performTextInput("Grilled octopus")
            // Another day (the Day row's chip; the Ends row has one too), and no end.
            onAllNodesWithText(Dates.dayShort(today.plusDays(2)))[0].performClick()
            onNodeWithText("No end").performClick()
            onNodeWithText("Done").performClick()
        }
        val changed = assertNotNull(book.item(added.id))
        assertEquals("Lunch in Halki", changed.title)
        assertEquals("Grilled octopus", changed.note)
        assertEquals(today.plusDays(2), changed.day)
        assertEquals(9 * 60, changed.timeMinutes)
        assertNull(changed.endMinutes)
        assertTrue(changed.done)
        assertKeptAsABackup(file, book)

        ui(book, Place.Trip(trip.id)) {
            onNodeWithText("Lunch in Halki").performClick()
            onNodeWithText("Delete").performClick()
            onNodeWithText("Delete this item?").assertExists()
            onNodeWithText("Are you sure you want to delete it? You can't undo this.").assertExists()
            onAllNodesWithText("Delete").onLast().performClick()
            waitForIdle()
            onNodeWithText("Lunch in Halki").assertDoesNotExist()
        }
        assertTrue(book.items(trip.id).isEmpty())
        assertNotNull(book.snapshot.planItems.single().deletedAt)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun aNoteIsWrittenChangedAndDeleted() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val trip = book.newTrip("Naxos", today, today.plusDays(3))
        ui(book, Place.Trip(trip.id)) {
            // The trip's journal is its Journal tab, on the bar.
            onNodeWithText("Journal").performClick()
            onNodeWithText("Nothing written for this trip").assertExists()
            onNodeWithText("Write").performClick()
            fields()[0].performTextInput("Day one")
            fields()[1].performTextInput("Swam at Agia Anna.")
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            onNodeWithText("Day one").performClick()
            onNodeWithText("Swam at Agia Anna.").assertExists()
            onNodeWithText("Edit").performClick()
            fields()[1].performTextReplacement("Swam at Agia Anna.\n\nDinner in Chora.")
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            // Back on the note, read again.
            onNodeWithText("Dinner in Chora.").assertExists()
        }
        val note = book.entries(trip.id).single()
        assertEquals("Day one", note.title)
        assertEquals("Swam at Agia Anna.\n\nDinner in Chora.", note.plainText)
        assertKeptAsABackup(file, book)

        ui(book, Place.Trip(trip.id, TripTab.Journal), Place.Note(trip.id, note.id)) {
            onNodeWithText("Delete").performClick()
            onNodeWithText("Delete this note?").assertExists()
            onAllNodesWithText("Delete").onLast().performClick()
            waitForIdle()
            onNodeWithText("Nothing written for this trip").assertExists()
        }
        assertTrue(book.entries(trip.id).isEmpty())
        assertNotNull(book.snapshot.entries.single().deletedAt)
        assertKeptAsABackup(file, book)
    }

    @Test
    fun deletingATripAsksFirst() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val trip = book.newTrip("Naxos", today, today.plusDays(3))
        ui(book, Place.Trip(trip.id), Place.EditTrip(trip.id)) {
            onNodeWithText("Delete trip").performClick()
            onNodeWithText("Delete this trip?").assertExists()
            onAllNodesWithText("Delete").onLast().performClick()
            waitForIdle()
            // Back on Trips, with none left.
            onNodeWithText("No trips yet").assertExists()
        }
        assertNull(book.trip(trip.id))
        assertKeptAsABackup(file, book)
    }
}

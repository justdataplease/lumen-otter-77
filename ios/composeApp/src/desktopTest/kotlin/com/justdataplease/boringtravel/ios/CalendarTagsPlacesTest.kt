package com.justdataplease.boringtravel.ios

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.tags.ItemTags
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Calendar tab, plan items' tags and the plan days' place names: driven on the desktop target, and their rules
 * against the Android app's cases.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarTagsPlacesTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking { BtFontFiles.load() }
    }

    private fun ui(book: TripBook, vararg at: Place, block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = 440, height = 1800) {
        setContent { BoringTravelApp(book, AppSettings.ready(), today, startAt = at.toList()) }
        block()
        waitForIdle()
    }

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private fun ComposeUiTest.tab(label: String): SemanticsNodeInteraction = onNode(isTab and hasText(label))

    /** A day of the open month, by what it says to a screen reader ("Fri 27 Nov 2026"). */
    private fun ComposeUiTest.day(d: LocalDate): SemanticsNodeInteraction =
        onNode(SemanticsMatcher("the day ${Dates.dayLong(d)}") { it.config.getOrNull(SemanticsActions.OnClick)?.label == Dates.dayLong(d) })

    @Test
    fun theCalendarShowsEveryTripAndOpensOneOnTheDayTapped() {
        val book = sampleBook()
        val kyoto = assertNotNull(book.sections(today).now)
        val tokyo = book.newTrip("Tokyo", today, today.plusDays(3))
        ui(book) {
            tab("Calendar").performClick()
            tab("Calendar").assertIsSelected()
            onNodeWithText("November 2026").assertExists()
            // The trip on now: a bar in each of the weeks it runs through.
            assertEquals(2, onAllNodes(hasText(kyoto.title)).fetchSemanticsNodes().size)
            // Two trips on the same days are flagged; a trip without dates is listed.
            onNodeWithText("${kyoto.title} and Tokyo overlap · ${Dates.range(today, today.plusDays(3))}").assertExists()
            onNodeWithText("No dates yet", ignoreCase = true).assertExists()
            onNodeWithText("Iceland ring road").assertExists()

            // A day of one trip opens it there: the trip's last day, far down its plan.
            day(kyoto.endDate!!).performClick()
            waitForIdle()
            tab("Plan").assertIsSelected()
            onNodeWithText("Day 12", substring = true).assertIsDisplayed()
            // Today's day is far up the list, out of sight (or not laid out at all).
            val todays = onAllNodes(hasText("Day 3 · Temples and tea"))
            assertTrue(todays.fetchSemanticsNodes().indices.none { todays[it].isDisplayed() })
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            tab("Calendar").assertIsSelected()

            // A day two trips share asks which.
            day(today).performClick()
            onNode(hasText("Tokyo") and hasClickAction()).performClick()
            waitForIdle()
            onNodeWithText("Tokyo").assertExists()
            onNodeWithText("Add a place or a note").assertExists()
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()

            // Other months, and back to this one.
            repeat(7) { onNodeWithContentDescription("Next month").performClick() }
            onNodeWithText("June 2027").assertExists()
            assertTrue(onAllNodes(hasText("Naxos")).fetchSemanticsNodes().isNotEmpty())
            onNodeWithText("Today").performClick()
            onNodeWithText("November 2026").assertExists()

            // The year, and a month of it.
            onNodeWithText("Year").performClick()
            onNodeWithText("2026").assertExists()
            onNodeWithText("JUN").performClick()
            onNodeWithText("June 2026").assertExists()
        }
        assertNotNull(book.trip(tokyo.id))
    }

    @Test
    fun aFreeDayStartsANewTripThere() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val naxos = book.newTrip("Naxos", today.plusDays(3), today.plusDays(6))
        val free = localDate(2026, 11, 2)
        ui(book, Place.Home(HomeTab.Calendar)) {
            day(free).performClick()
            onNodeWithText("New trip from here").performClick()
            waitForIdle()
            onNodeWithText("Make it myself").performClick()
            onNodeWithText(Dates.range(free, free)).assertExists()
            onAllNodes(hasSetTextAction())[0].performTextInput("Paros")
            onNodeWithText("Save").performClick()
            waitForIdle()
            onNodeWithText("Add a place or a note").assertExists()
        }
        val made = book.trips(today).single { it.id != naxos.id }
        assertEquals("Paros", made.title)
        assertEquals(free, made.startDate)
        assertEquals(free, made.endDate)
    }

    @Test
    fun theCalendarWithNoTripsOffersANewTrip() {
        ui(TripBook(MemoryTripFile()), Place.Home(HomeTab.Calendar)) {
            onNodeWithText("No trips on the calendar yet.").assertExists()
            onNodeWithText("New trip").performClick()
            waitForIdle()
            onNodeWithText("Make it myself").assertExists()
        }
    }

    @Test
    fun theCalendarsRulesAreTheAndroidApps() {
        fun span(id: String, from: LocalDate, to: LocalDate) = TripSpan(id, id, from, to)
        // November 2026 from Monday: 26 Oct to 6 Dec, six rows.
        val weeks = TripGrid.weeks(CalMonth(2026, 11), 1)
        assertEquals(6, weeks.size)
        assertEquals(localDate(2026, 10, 26), weeks.first().first())
        assertEquals(localDate(2026, 12, 6), weeks.last().last())
        // Overlapping trips take lanes; a trip crossing a week is cut in two bars.
        val a = span("a", localDate(2026, 11, 3), localDate(2026, 11, 10))
        val b = span("b", localDate(2026, 11, 5), localDate(2026, 11, 6))
        val c = span("c", localDate(2026, 11, 12), localDate(2026, 11, 12))
        val layout = TripGrid.month(CalMonth(2026, 11), 1, listOf(a, b, c))
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 0), layout.lanes)
        val barsOfA = layout.bars.filter { it.tripId == "a" }
        assertEquals(2, barsOfA.size)
        assertTrue(barsOfA[0].runsOn && barsOfA[1].opensBefore)
        assertEquals(listOf(b, a), TripGrid.tripsOn(localDate(2026, 11, 5), listOf(a, b, c), mapOf("a" to 1, "b" to 0)))
        // A hand-over is not an overlap; sharing days is.
        val lisbon = span("lisbon", localDate(2027, 5, 1), localDate(2027, 5, 5))
        val porto = span("porto", localDate(2027, 5, 5), localDate(2027, 5, 9))
        assertTrue(TripAnalytics.overlaps(listOf(lisbon, porto)).isEmpty())
        assertEquals(listOf(TripAnalytics.Overlap(a, b, b.start, b.end)), TripAnalytics.overlaps(listOf(a, b)))
        // A trip keeps its colour; two that touch never share one.
        assertEquals(TripColors.preferred("trip-1"), TripColors.preferred("trip-1"))
        val colours = TripColors.assign(listOf(lisbon, porto))
        assertTrue(colours["lisbon"] != colours["porto"])
        // Without dates: not on the grid; an end before the start is one day.
        val t = Trip("x", "X", startDate = localDate(2027, 1, 2), endDate = localDate(2027, 1, 1), createdAt = 1L, updatedAt = 1L)
        val split = TripSpan.split(listOf(t, t.copy(id = "y", startDate = null, endDate = null)))
        assertEquals(1, split.spans.single().days)
        assertEquals("y", split.undated.single().id)
        assertEquals(CalMonth(2027, 1), CalMonth.ofIndex(CalMonth(2027, 1).index))
        assertEquals(CalMonth(-1, 12), CalMonth.ofIndex(-1))
    }

    @Test
    fun aPlanItemTakesTagsFromTheTreeOrItsOwn() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val trip = book.newTrip("Kyoto", today, today.plusDays(3))
        ui(book, Place.Trip(trip.id)) {
            onNodeWithText("Add a place or a note").performClick()
            onAllNodes(hasSetTextAction())[0].performTextInput("Lunch in Gion")
            // Tags: a suggestion from the tree as you type, then a tag of one's own.
            onNodeWithText("Tags", ignoreCase = true).assertExists()
            // The tags' field: after the title, the place's search, the website and the note.
            onAllNodes(hasSetTextAction())[4].performTextInput("sush")
            onNodeWithText("#sushi").performClick()
            onAllNodes(hasSetTextAction())[4].performTextInput("kaiseki dinner")
            onNodeWithText("Your own tag").assertExists()
            onNodeWithText("#kaisekidinner").performClick()
            // Browse: category, subcategory, a tag. (Taps low in the sheet are sent as its click: the test window's
            // pointer misses chips the sheet has just scrolled to.)
            onNodeWithText("Browse tags").performScrollTo().performClick()
            onNodeWithText("Seasons & Events").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("Spring").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("#cherryblossom").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("Done").performClick()
            waitForIdle()
            // The plan shows them under the item.
            onNodeWithText("#sushi #kaisekidinner #cherryblossom").assertExists()
        }
        val item = book.items(trip.id).single()
        assertEquals(listOf("sushi", "kaisekidinner", "cherryblossom"), ItemTags.of(item))
        assertEquals("sushi kaisekidinner cherryblossom", item.tags)
        assertEquals(book.snapshot, TripBook(file).snapshot)
    }

    @Test
    fun aPlanDayIsNamedByWhereItIsSpent() {
        val book = TripBook(MemoryTripFile())
        val start = today.plusDays(1)
        val trip = book.newTrip("Cyclades", start, start.plusDays(3))
        book.saveItem(book.draftItem(trip.id, start, 1.0).copy(title = "Ferry to Naxos", kind = PlanKind.Transport.id))
        book.saveItem(book.draftItem(trip.id, start.plusDays(1), 1.0).copy(title = "Dinner in Chora", kind = PlanKind.Food.id))
        book.saveItem(book.draftItem(trip.id, start.plusDays(2), 1.0).copy(title = "Sunset", placeName = "Oia, Santorini, Greece"))
        book.saveItem(book.draftItem(trip.id, start.plusDays(3), 1.0).copy(title = "Breakfast"))
        book.nameDay(trip.id, start.plusDays(3), "Slow morning")
        ui(book, Place.Trip(trip.id)) {
            // The latest arrival names the days after it; a day's own places name it; a day's own name wins.
            onNodeWithText("Day 1 · Naxos").assertExists()
            onNodeWithText("Day 2 · Naxos").assertExists()
            onNodeWithText("Day 3 · Santorini").assertExists()
            onNodeWithText("Day 4 · Slow morning").assertExists()
        }
    }

    @Test
    fun placeNamesFollowTheAndroidAppsRules() {
        assertEquals("Naxos", PlaceNames.area("Halki, Naxos, Greece"))
        assertEquals("Naxos", PlaceNames.area("Agios Georgios, Naxos"))
        assertEquals("Naxos", PlaceNames.area("Naxos, Greece"))
        assertNull(PlaceNames.area(" , "))
        fun item(title: String, kind: PlanKind, day: LocalDate?, place: String = "") =
            PlanItem(id = title, tripId = "t", day = day, position = 1.0, kind = kind.id, title = title, placeName = place, createdAt = 1L, updatedAt = 1L)
        val d1 = localDate(2027, 6, 17)
        val items = listOf(item("Fly to Lisbon", PlanKind.Transport, d1), item("Fly home", PlanKind.Transport, d1.plusDays(5)))
        assertEquals("Lisbon", PlanDayLabel.label(d1.plusDays(2), items))
        // "Fly home" has no destination: the days after it keep Lisbon.
        assertEquals("Lisbon", PlanDayLabel.label(d1.plusDays(6), items))
        assertNull(PlanDayLabel.label(d1.minusDays(1), items))
        // Where the traveller was (stops, as a backup from Android brings them) wins over the plan.
        val zone = "Europe/Lisbon"
        val stop = Stop("s", "t", arrivedAt = startOfDayMs(d1.plusDays(2), zone) + 3_600_000L, leftAt = startOfDayMs(d1.plusDays(3), zone), lat = 41.1, lng = -8.6, name = "Ribeira, Porto, Portugal", createdAt = 1L, updatedAt = 1L)
        assertEquals("Porto", DayPlace.label(d1.plusDays(2), listOf(stop), items, zone))
        assertNull(DayPlace.label(d1.plusDays(4), listOf(stop), items, zone))
    }
}

package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.TrackingLevel
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 10 on the desktop: Me › Route tracking and its steps, a trip map's Route tracking sheet, the widgets' and quick
 * actions' links opening their screens, and a new note taking its place from the last route point. The phone is in
 * memory ([MemoryRouteSensor], [MemoryPhoneNotes]): nothing real is asked.
 */
@OptIn(ExperimentalTestApi::class)
class TrackingOnDesktopTest {
    init {
        runBlocking {
            BtFontFiles.load()
            CountryOutlines.load()
        }
    }

    private val noon = startOfDayMs(SAMPLE_TODAY, null) + 12 * 3_600_000L

    private class Phone(val grant: LocationGrant, val notes: NoteGrant) {
        val sensor = MemoryRouteSensor(grantNow = grant)
        val phoneNotes = MemoryPhoneNotes(notes)
    }

    private fun ui(
        book: TripBook, phone: Phone, settings: AppSettings = AppSettings.ready(), links: AppLinks? = null, web: Web = Web.offline(),
        vararg at: Place, block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 440, height = 1100) {
        setContent {
            val scope: CoroutineScope = rememberCoroutineScope()
            val tracking = remember {
                RouteTracking(
                    book, settings, phone.sensor, phone.phoneNotes, MemoryNightlyRuns(), TrackingStore(MemorySettingsFile()), scope,
                    clock = { noon },
                )
            }
            BoringTravelApp(book, settings, SAMPLE_TODAY, startAt = at.toList(), version = "1.0", web = web, tracking = tracking, links = links)
        }
        waitForIdle()
        block()
        waitForIdle()
    }

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun meSaysOffAndItsScreenAsksOneStepAtATime() {
        val phone = Phone(LocationGrant.NotAsked, NoteGrant.NotAsked)
        val book = sampleBook()
        val trip = book.trips(SAMPLE_TODAY).first()
        ui(book, phone, at = arrayOf(Place.Home(HomeTab.Me))) {
            // The row: its name over its level.
            onNode(hasText("Route tracking") and hasText("Off")).assertExists()
            onNodeWithText("Route tracking").performClick()
            waitForText("For every trip".uppercase())
            // A trip is on today: off for it (Android's words).
            onNodeWithText("Off for ${trip.title}.").assertExists()
            // Off: no step, nothing asked.
            assertTrue(onAllNodesWithText("Allow").fetchSemanticsNodes().isEmpty())
            assertTrue(phone.sensor.calls.isEmpty())

            onNodeWithText("Balanced").performClick()
            waitForText("Precise location")
            onNodeWithText("Waiting for location permission for ${trip.title}.").assertExists()
            assertTrue(phone.sensor.calls.isEmpty(), "nothing asked before Allow")
            onNodeWithText("Allow").performClick()
            waitForText("Recording ${trip.title} · Balanced")
            assertEquals(listOf("askWhileUsing"), phone.sensor.calls)

            waitForText("Notifications")
            onNodeWithText("Allow").performClick()
            // Android's steps end there: no Always (off as Android's background recording).
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Allow").fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf("notes"), phone.phoneNotes.asked)
            assertTrue(onAllNodesWithText("Allow all the time").fetchSemanticsNodes().isEmpty())
            assertEquals(listOf("askWhileUsing"), phone.sensor.calls)

            onNodeWithText("Stop for today").performClick()
            waitForText("Stopped for today · ${trip.title}")
            onNodeWithText("Start recording").performClick()
            waitForText("Recording ${trip.title} · Balanced")
        }
    }

    @Test
    fun aTripMapHasItsOwnLevel() {
        val phone = Phone(LocationGrant.WhileUsing, NoteGrant.Allowed)
        val book = sampleBook()
        val trip = book.trips(SAMPLE_TODAY).first()
        val settings = AppSettings.ready().apply { trackingLevel = TrackingLevel.Balanced }
        ui(book, phone, settings, web = Recorded.web(), at = arrayOf(Place.Trip(trip.id, TripTab.Map))) {
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Route tracking").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Route tracking").performClick()
            waitForText("For this trip.", substring = true)
            onNodeWithText("Recording ${trip.title} · Balanced").assertExists()
            onNodeWithText("Detailed").performClick()
            waitUntil(timeoutMillis = 10_000) { book.trip(trip.id)?.trackingLevel == "detailed" }
            assertEquals(TrackingLevel.Detailed, phone.sensor.requests.last().level)
        }
    }

    @Test
    fun aTripNotStartedSaysWhenItStarts() {
        val phone = Phone(LocationGrant.NotAsked, NoteGrant.NotAsked)
        val book = sampleBook()
        val naxos = book.trips(SAMPLE_TODAY).first { it.title == "Naxos" }
        ui(book, phone, web = Recorded.web(), at = arrayOf(Place.Trip(naxos.id, TripTab.Map))) {
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Route tracking").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Route tracking").performClick()
            waitForText("Starts on Thu 17 Jun 2027.")
            // Not a trip day: no status and no steps.
            assertTrue(onAllNodesWithText("Allow").fetchSemanticsNodes().isEmpty())
            assertTrue(phone.sensor.calls.isEmpty())
        }
    }

    @Test
    fun widgetAndQuickActionLinksOpenTheirScreens() {
        val phone = Phone(LocationGrant.NotAsked, NoteGrant.NotAsked)
        val book = sampleBook()
        val trip = book.trips(SAMPLE_TODAY).first()
        val links = AppLinks()
        ui(book, phone, links = links) {
            links.open("boringtravel://today")
            waitForText("First night")
            assertNull(links.pending)
            links.open("boringtravel://new_trip")
            waitForText("Make it myself")
            links.open("boringtravel://write")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Done").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Done").performClick()
            // The camera, where there is none: said in passing.
            links.open("boringtravel://camera")
            waitForText("No camera app on this phone.")
        }
        assertTrue(book.trip(trip.id) != null)
    }

    @Test
    fun linksBeforeTheFirstStartIsDoneAreDropped() {
        val phone = Phone(LocationGrant.NotAsked, NoteGrant.NotAsked)
        val links = AppLinks()
        ui(sampleBook(), phone, settings = AppSettings(MemorySettingsFile()), links = links) {
            links.open("boringtravel://write")
            waitForIdle()
            assertNull(links.pending)
            onNodeWithText("Continue").assertExists()
            assertTrue(onAllNodesWithContentDescription("Done").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun aNewNoteTakesTheLastRoutePointAsItsPlace() {
        val phone = Phone(LocationGrant.NotAsked, NoteGrant.NotAsked)
        val book = sampleBook()
        val trip = book.trips(SAMPLE_TODAY).first()
        // A point an hour ago (the note's place takes points under 12 hours old), named by the phone's geocoder.
        withPoints(book, trip.id, listOf(Triple(nowMs() - 3_600_000L, 34.9671, 135.7727)))
        val web = Web(OfflineNet, FixedLocation(LatLng(34.9671, 135.7727), named = GeoAddress(locality = "Fushimi")))
        ui(book, phone, web = web, at = arrayOf(Place.Write(trip.id))) {
            waitForText("FUSHIMI", substring = true)
        }
    }

    @Test
    fun theWidgetsSnapshotIsWrittenAtTheStartAndAfterAChange() {
        val book = sampleBook()
        val widget = MemoryHomeWidget()
        runDesktopComposeUiTest(width = 440, height = 1100) {
            setContent { BoringTravelApp(book, AppSettings.ready(), SAMPLE_TODAY, version = "1.0", widget = widget) }
            waitUntil(timeoutMillis = 10_000) { widget.saves > 0 }
            val first = WidgetSnapshot.parse(widget.snapshot!!)!!
            assertEquals("Day 3 · Temples and tea", first.days.first().primary)
        }
    }
}

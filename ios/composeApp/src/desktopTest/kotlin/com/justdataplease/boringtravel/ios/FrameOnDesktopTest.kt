package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
import com.justdataplease.boringtravel.backup.BackupPreferences
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.TextFont
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's frame driven as a traveller would on the desktop target: the bottom bar outside and inside a trip, the
 * journal of several trips and the notes outside trips, Me's theme and fonts kept over a restart, and the first start.
 */
@OptIn(ExperimentalTestApi::class)
class FrameOnDesktopTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking { BtFontFiles.load() }
    }

    private fun ui(book: TripBook, settings: AppSettings = AppSettings.ready(), vararg at: Place, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent { BoringTravelApp(book, settings, today, startAt = at.toList(), version = "1.0") }
            block()
            waitForIdle()
        }

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** The bar's item labelled [label] (a theme may write it upper-case). */
    private fun ComposeUiTest.tab(label: String): SemanticsNodeInteraction = onNode(isTab and hasText(label, ignoreCase = true))

    /** The labels on the bar, in order. */
    private fun ComposeUiTest.barLabels(): List<String> {
        val nodes = onAllNodes(isTab and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and SemanticsMatcher("on the bar") { node ->
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { t -> t.text.lowercase() in BAR_WORDS } == true
        }).fetchSemanticsNodes()
        return nodes.sortedBy { it.boundsInRoot.left }.map { n -> n.config[SemanticsProperties.Text].joinToString { it.text } }
    }

    private val BAR_WORDS = (HomeTab.entries.map { it.label } + TripTab.entries.map { it.label }).map { it.lowercase() }.toSet()

    /** Where [text] is drawn, from the top. */
    private fun ComposeUiTest.top(text: String): Float = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun theBarIsAndroidsOutsideAndInsideATrip() {
        val book = sampleBook()
        val current = assertNotNull(book.sections(today).now)
        ui(book) {
            // Trips · Calendar · Map · Community · Me, on Trips.
            assertEquals(listOf("Trips", "Calendar", "Map", "Community", "Me"), barLabels())
            tab("Trips").assertIsSelected()
            tab("Me").assertIsNotSelected()

            // The tab without a screen on this phone says so; the Calendar, the Map and Me are there.
            tab("Community").performClick()
            tab("Community").assertIsSelected()
            onNodeWithText("Not in this build").assertExists()
            // (The Map tab's own Globe · Map switch also has a "Map".)
            tab("Map").performClick()
            // The globe first, as on Android.
            assertTrue(onAllNodesWithText("Places I've been").fetchSemanticsNodes().isNotEmpty())
            tab("Calendar").performClick()
            tab("Calendar").assertIsSelected()
            onNodeWithText("November 2026").assertExists()
            tab("Me").performClick()
            tab("Me").assertIsSelected()
            onNodeWithText("Your space").assertExists()
            tab("Trips").performClick()
            tab("Trips").assertIsSelected()
            onNodeWithText(current.title).assertExists()

            // A trip has its own bar: Plan · Journal · Map · Media, on Plan.
            onNodeWithText(current.title).performClick()
            waitForIdle()
            assertEquals(listOf("Plan", "Journal", "Map", "Media"), barLabels())
            tab("Plan").assertIsSelected()
            onNodeWithText("Day 3 · Temples and tea").assertExists()
            tab("Journal").performClick()
            tab("Journal").assertIsSelected()
            onNodeWithText("First night").assertExists()
            tab("Map").performClick()
            onNode(isTab and hasText("Planned")).assertExists()
            // The trip's Media (round 20): its photos and videos by day (the section's name in the mono capitals).
            tab("Media").performClick()
            assertTrue(onAllNodesWithText("PHOTOS", substring = true).fetchSemanticsNodes().isNotEmpty())

            // Back leaves the trip in one step, whatever tab was on show: Trips and its bar again.
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            assertEquals(listOf("Trips", "Calendar", "Map", "Community", "Me"), barLabels())
            tab("Trips").assertIsSelected()

            // A trip opens on Plan again.
            onNodeWithText(current.title).performClick()
            waitForIdle()
            tab("Plan").assertIsSelected()
        }
    }

    @Test
    fun screensPushedOverTheTabsHaveNoBar() {
        val book = sampleBook()
        val current = assertNotNull(book.sections(today).now)
        val note = book.entries(current.id).single()
        ui(book, AppSettings.ready(), Place.Trip(current.id, TripTab.Journal), Place.Note(current.id, note.id)) {
            onNodeWithText("Journal entry").assertExists()
            assertTrue(barLabels().isEmpty())
            onNodeWithText("Edit").performClick()
            assertTrue(barLabels().isEmpty())
            onNodeWithContentDescription("Done").performClick()
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            // Back on the trip, on the tab it was on.
            tab("Journal").assertIsSelected()
        }
    }

    @Test
    fun eachTripsJournalListsItsOwnNotesNewestFirst() {
        val file = MemoryTripFile()
        var clock = 1_795_000_000_000L
        val book = TripBook(file, now = { clock++ })
        val naxos = book.newTrip("Naxos", today.minusDays(10), today.minusDays(5))
        val kyoto = book.newTrip("Kyoto", today.plusDays(20), today.plusDays(30))
        fun at(daysAgo: Long) = startOfDayMs(today.minusDays(daysAgo), "Europe/Athens") + 12 * 3_600_000L
        book.writeNote(naxos.id, "Ferry from Paros at nine.", "The ferry", at = at(10), zoneId = "Europe/Athens")
        book.writeNote(naxos.id, "Swam at Agia Anna.", "The beach", at = at(8), zoneId = "Europe/Athens")
        book.writeNote(kyoto.id, "Booked the ryokan.", "Booking", at = at(3), zoneId = "Europe/Athens")
        ui(book, AppSettings.ready(), Place.Trip(naxos.id, TripTab.Journal)) {
            onNodeWithText("The ferry").assertExists()
            onNodeWithText("The beach").assertExists()
            onNodeWithText("Booking").assertDoesNotExist()
            // Newest first, as the Android tab lists them.
            assertTrue(top("The beach") < top("The ferry"))
            // The first words under the title.
            onNodeWithText("Swam at Agia Anna.").assertExists()
        }
        ui(book, AppSettings.ready(), Place.Trip(kyoto.id, TripTab.Journal)) {
            onNodeWithText("Booking").assertExists()
            onNodeWithText("The ferry").assertDoesNotExist()
        }
    }

    @Test
    fun aNoteIsWrittenFromTheJournalTab() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val trip = book.newTrip("Naxos", today.minusDays(2), today.plusDays(3))
        book.writeNote(trip.id, "Landed.", "Day one", at = startOfDayMs(today.minusDays(2), null) + 3_600_000L)
        ui(book, AppSettings.ready(), Place.Trip(trip.id)) {
            onNodeWithText("Journal").performClick()
            onNodeWithText("Day one").assertExists()
            // Write floats over the list once there are notes.
            onNodeWithText("Write").performClick()
            assertTrue(barLabels().isEmpty())
            onAllNodes(hasSetTextAction())[0].performTextInput("Day three")
            onAllNodes(hasSetTextAction())[1].performTextInput("Walked to the Portara.")
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            // Back on the trip's Journal, the new note first.
            tab("Journal").assertIsSelected()
            onNodeWithText("Day three").assertExists()
            assertTrue(top("Day three") < top("Day one"))
        }
        val notes = book.entries(trip.id)
        assertEquals(listOf("Day one", "Day three"), notes.map { it.title })
        assertEquals("Walked to the Portara.", notes.last().plainText)
        val data = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read()))
        assertEquals(book.snapshot, data.toSnapshot())
    }

    @Test
    fun notesOutsideTripsAreReadChangedAndDeleted() {
        val file = MemoryTripFile()
        val book = withLooseNotes(TripBook(file).also { it.newTrip("Naxos", today.plusDays(3), today.plusDays(6)) })
        ui(book) {
            // The card on Trips, with the count.
            onNodeWithText("Notes outside trips").assertExists()
            onNodeWithText("2 notes").assertExists()
            onNodeWithText("Notes outside trips").performClick()
            waitForIdle()
            onNodeWithText("Written when no trip was on.").assertExists()
            // Under Trips: the bar stays, on Trips.
            tab("Trips").assertIsSelected()
            // Newest first; a note without a title shows its day.
            assertTrue(top("Bought a new rucksack, 30 litres. Fits under a plane seat.") < top("Ideas for spring"))
            onNodeWithText("Ideas for spring").performClick()
            onNodeWithText("Ferries to the small islands start in April. Look at Folegandros.").assertExists()
            onNodeWithText("Edit").performClick()
            onAllNodes(hasSetTextAction())[1].performTextReplacement("Ferries to the small islands start in April.")
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            onNodeWithText("Ferries to the small islands start in April.").assertExists()
            onNodeWithText("Delete").performClick()
            onAllNodesWithText("Delete").onLast().performClick()
            waitForIdle()
            // Back on the list, one note left.
            onNodeWithText("Ideas for spring").assertDoesNotExist()
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()
            onNodeWithText("1 note").assertExists()
        }
        val loose = book.snapshot.entries.filter { it.tripId == null }
        assertEquals(2, loose.size)
        val changed = loose.single { it.title == "Ideas for spring" }
        assertEquals("Ferries to the small islands start in April.", changed.plainText)
        assertNotNull(changed.deletedAt)
        assertEquals(book.snapshot, TripBook(file).snapshot)
    }

    @Test
    fun theThemeAndTheFontsAreKeptAfterARestart() {
        val stored = MemorySettingsFile()
        val book = sampleBook()
        ui(book, AppSettings(stored).apply { onboardingDone = true }) {
            tab("Me").performClick()
            onNodeWithText(Look.PostcardPop.label).assertIsSelected()
            onNodeWithText(Look.Trail.label).performClick()
            onNodeWithText(Look.Trail.label).assertIsSelected()
            // The app is in Trail at once: its bar writes its labels upper-case.
            onNode(isTab and hasText("ME")).assertExists()
            onNodeWithText(FontSet.Notebook.label).performClick()
            onNodeWithText(FontSet.Notebook.label).assertIsSelected()
            onNodeWithText(TextFont.Literata.label).performClick()
            onNodeWithText(TextFont.Literata.label).assertIsSelected()
            // The phone's font is the bundled Roboto, named as on Android.
            onNodeWithText("Phone (Roboto)").assertIsNotSelected()
        }
        // Kept under the backup's names, with the Android app's ids.
        assertEquals("trail", stored.read("look"))
        assertEquals("notebook", stored.read("fontSet"))
        assertEquals("literata", stored.read("textFont"))
        val again = AppSettings(stored)
        assertEquals(Look.Trail, again.look)
        assertEquals(FontSet.Notebook, again.fontSet)
        assertEquals(TextFont.Literata, again.textFont)
        assertEquals(BackupPreferences(look = "trail", fontSet = "notebook", textFont = "literata"), again.backupPreferences())
        // The app started again on what was kept.
        ui(book, again, Place.Home(HomeTab.Me)) {
            onNodeWithText(Look.Trail.label).assertIsSelected()
            onNodeWithText(FontSet.Notebook.label).assertIsSelected()
            onNodeWithText(TextFont.Literata.label).assertIsSelected()
            onNode(isTab and hasText("ME")).assertIsSelected()
        }
    }

    @Test
    fun aBackupsChoicesComeBackByTheAndroidAppsRule() {
        // Never chosen here: the backup's fill in. Chosen here: kept, unless the restore was asked for.
        val stored = MemorySettingsFile(mapOf("look" to "market"))
        val settings = AppSettings(stored)
        settings.restorePreferences(BackupPreferences(look = "trail", fontSet = "field", textFont = "lora", placePhotos = false))
        assertEquals(Look.Market, settings.look)
        assertEquals(FontSet.FieldBook, settings.fontSet)
        assertEquals(TextFont.Lora, settings.textFont)
        settings.restorePreferences(BackupPreferences(look = "notebook"), replace = true)
        // An id from before the five themes reads as its theme, and is kept as the backup wrote it.
        assertEquals(Look.StickerBook, settings.look)
        assertEquals("notebook", stored.read("look"))
        assertEquals(FontSet.FieldBook, settings.fontSet)
        // Nothing chosen: nothing in a backup, and the defaults.
        val fresh = AppSettings(MemorySettingsFile())
        assertEquals(BackupPreferences(), fresh.backupPreferences())
        assertEquals(Look.Default, fresh.look)
        assertEquals(TextFont.Phone, fresh.textFont)
        assertFalse(fresh.onboardingDone)
    }

    @Test
    fun meHasTheRowsThisPhoneCanKeep() {
        var opened: String? = null
        val links = object : UriHandler {
            override fun openUri(uri: String) {
                opened = uri
            }
        }
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                CompositionLocalProvider(LocalUriHandler provides links) {
                    BoringTravelApp(sampleBook(), AppSettings.ready(), today, startAt = listOf(Place.Home(HomeTab.Me)), version = "1.0")
                }
            }
            onNodeWithText("Your space").assertExists()
            // Section labels are written upper-case.
            onNodeWithText("Theme", ignoreCase = true).assertExists()
            onNodeWithText("Titles and dates", ignoreCase = true).assertExists()
            onNodeWithText("Text", ignoreCase = true).assertExists()
            onNodeWithText("Version").assertExists()
            onNodeWithText("1.0").assertExists()
            // Since round 23 the Account card first (no Google in this build: Android's words for one without sign-in).
            onNodeWithText("Account", ignoreCase = true).assertExists()
            onNodeWithText("Not signed in").assertExists()
            onNodeWithText("Google sign-in isn't set up in this build.").assertExists()
            // Backup & sync: "Off" until Google Drive is connected.
            onNodeWithText("Backup & sync").assertExists()
            onNode(hasText("Backup & sync") and hasText("Off")).assertExists()
            // Route tracking (since round 10), with its level: Off until one is picked.
            onNode(hasText("Route tracking") and hasText("Off")).assertExists()
            // AI: where the planner's answers come from (the built-in planner until a key of one's own works).
            onNodeWithText("AI").assertExists()
            onNodeWithText("Built-in planner").assertExists()
            // Voice notes: the language dictation listens for, the phone's until one is picked.
            onNodeWithText("Voice notes").assertExists()
            onNodeWithText("Phone language").assertExists()
            // Import trips from a link (a Google Maps list), Trips look, Plan view, Place photos and Demo mode are here.
            for (row in listOf("Import trips from a link", "Trips look", "Plan view", "Tickets", "Pictures", "List")) onNodeWithText(row).assertExists()
            onNodeWithText("Place photos", substring = true).assertExists()
            onNodeWithText("Demo mode", substring = true).assertExists()
            onNodeWithText("Privacy policy").performClick()
            assertEquals(AboutApp.PRIVACY_URL, opened)
        }
    }

    @Test
    fun theFirstStartShowsWelcomeOnce() {
        val stored = MemorySettingsFile()
        val trips = MemoryTripFile()
        runDesktopComposeUiTest(width = 440, height = 1800) {
            // From a cold start, as the phone opens it: fonts, trips and settings read first.
            setContent { App(trips, stored, "1.0") }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Boring travel, when everything can be interesting.").assertExists()
            // No bar on the first start, and no Google button in a build without the Google iPhone client.
            assertTrue(barLabels().isEmpty())
            onNodeWithText("Continue with Google").assertDoesNotExist()
            onNodeWithText("Continue").performClick()
            waitForIdle()
            onNodeWithText("No trips yet").assertExists()
            tab("Trips").assertIsSelected()
        }
        assertEquals("true", stored.read("onboardingDone"))
        // Opened again: straight to Trips.
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent { App(trips, stored, "1.0") }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("No trips yet").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Continue").assertDoesNotExist()
        }
        // Not a backup setting: it never travels.
        assertNull(AppSettings(stored).backupPreferences().look)
    }
}

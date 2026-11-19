package com.justdataplease.boringtravel.ios

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipPrefs
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.MapStylePrefs
import com.justdataplease.boringtravel.backup.BackupPreferences
import com.justdataplease.boringtravel.compat.PrefsText
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The trip video's screen driven on the desktop (round 18), offline (the map's pictures are the countries drawn): the
 * ways in (the trip's ⋮ › Make a clip, the trip map's Play the route clip), the studio's states and texts (Android's,
 * word for word), the size menu and the shape kept for the next clip as Android keeps it (and in a backup), the preview
 * playing and full screen with its bar, and Export saying what isn't here yet.
 */
@OptIn(ExperimentalTestApi::class)
class ClipScreenOnDesktopTest {
    init {
        runBlocking {
            BtFontFiles.load()
            CountryOutlines.load()
        }
    }

    private fun app(
        book: TripBook, settings: AppSettings = AppSettings.ready(), media: MediaKit = MediaKit.memory(), vararg at: Place,
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 440, height = 956) {
        setContent { BoringTravelApp(book, settings, SAMPLE_TODAY, startAt = at.toList(), version = "1.0", media = media) }
        waitForIdle()
        block()
    }

    private fun ComposeUiTest.waitForText(text: String, timeout: Long = 60_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.waitForDescription(text: String, timeout: Long = 60_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty() }

    /** A tap by the node's own click action: the demo's notice can cover the foot of the screen for its first seconds. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    @Test fun theTripMenuOpensTheStudioOnTheDemo() {
        val (book, kit) = demoBook()
        val settings = AppSettings.ready().apply { demoMode = true }
        app(book, settings, kit, Place.Trip(SampleArgentina.TRIP_ID)) {
            onNodeWithContentDescription("Trip options").performClick()
            onNodeWithText("Make a clip").performClick()
            // The trip's name in the studio's bar, the size and Export; the preview once the globe is in.
            waitForText(SampleArgentina.TITLE)
            waitForDescription("Clip preview")
            onNodeWithContentDescription("Video size 9:16").assertExists()
            onNodeWithText("Export").assertExists()
            onNodeWithContentDescription("Full screen").assertExists()
            // The play row: where the preview is in the clip, and Play.
            waitForText("0:00 / ", timeout = 10_000)
            onNodeWithContentDescription("Play").assertExists()
            // Export opens its sheet (round 20): the clip's shape, length and music over Android's states.
            onNodeWithText("Export").tap()
            waitForText("9:16 · 0:", timeout = 10_000)
            // Close (by its own action: the sheet's scrim lies over the bar): back to the trip.
            onNodeWithContentDescription("Close").tap()
            waitForText(SampleArgentina.TITLE)
            onNodeWithContentDescription("Trip options").assertExists()
        }
    }

    @Test fun theSizeMenuOffersAndKeepsTheFourShapes() {
        val (book, kit) = demoBook()
        val settings = AppSettings.ready().apply { demoMode = true }
        app(book, settings, kit, Place.Trip(SampleArgentina.TRIP_ID), Place.Clip(SampleArgentina.TRIP_ID)) {
            waitForDescription("Clip preview")
            onNodeWithContentDescription("Video size 9:16").performClick()
            for (use in listOf("TikTok, Reels and Stories", "Feed post", "Square post", "Wide, for a screen")) waitForText(use, timeout = 10_000)
            onNodeWithText("Square post").performClick()
            waitForDescription("Video size 1:1")
            waitForDescription("Clip preview")
        }
        // Kept for the next clip as Android keeps it ("clip" settings file: shape=square), and in a backup.
        assertEquals(ClipShape.Square, ClipPrefs.shape(settings.prefs))
        val backup = assertNotNull(settings.backupPreferences().clipStyle)
        assertEquals("square", PrefsText.decode(backup)["shape"])
    }

    @Test fun aTripWithoutARouteSaysSoAndOpensItsPlan() {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Weekend", SAMPLE_TODAY, SAMPLE_TODAY.plusDays(1))
        book.addItem(trip.id, "Somewhere", SAMPLE_TODAY, PlanKind.Place)
        app(book, AppSettings.ready(), MediaKit.memory(), Place.Trip(trip.id), Place.Clip(trip.id)) {
            waitForText("No route yet")
            onNodeWithText("Add locations to at least two planned places, record a journey, or save two stops to make a trip film.").assertExists()
            onNodeWithText("Trip video").assertExists()
            onNodeWithText("Open trip plan").performClick()
            waitForText("Weekend")
            onAllNodesWithText("No route yet").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        }
    }

    @Test fun thePreviewPlaysAndGoesFullScreen() {
        val (book, kit) = demoBook()
        val settings = AppSettings.ready().apply { demoMode = true }
        app(book, settings, kit, Place.Trip(SampleArgentina.TRIP_ID), Place.Clip(SampleArgentina.TRIP_ID)) {
            waitForDescription("Clip preview")
            onNodeWithContentDescription("Play").tap()
            waitForDescription("Pause", timeout = 10_000)
            onNodeWithContentDescription("Pause").tap()
            waitForDescription("Play", timeout = 10_000)
            onNodeWithContentDescription("Full screen").performClick()
            // Black, the picture, Close, and the bar to scrub with the time.
            waitForDescription("Close full screen", timeout = 10_000)
            onNodeWithContentDescription("Position").assertExists()
            onAllNodesWithContentDescription("Play preview").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
            onNodeWithContentDescription("Close full screen").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Close full screen").fetchSemanticsNodes().isEmpty() }
            onNodeWithContentDescription("Full screen").assertExists()
        }
    }

    @Test fun theTripMapsPlayTheRouteClipOpensTheStudio() {
        val (book, kit) = demoBook()
        val settings = AppSettings.ready().apply { demoMode = true }
        app(book, settings, kit, Place.Trip(SampleArgentina.TRIP_ID, TripTab.Map)) {
            waitForText("Planned")
            onNodeWithText("Planned").performClick()
            waitForText("Play the route clip")
            onNodeWithText("Play the route clip").tap()
            waitForDescription("Clip preview")
            onNodeWithText("Export").assertExists()
        }
    }

    /** The phone's files, where reading fails: the journey can't be opened. */
    private class BrokenFiles(private val files: MediaFiles) : MediaFiles by files {
        var broken = true
        override fun size(path: String): Long? = if (broken) throw IllegalStateException("Couldn't read the phone's files") else files.size(path)
    }

    @Test fun aJourneyThatCantOpenSaysSoAndTriesAgain() {
        val (book, kit) = demoBook()
        val broken = BrokenFiles(kit.files)
        app(book, AppSettings.ready().apply { demoMode = true }, MediaKit(broken), Place.Trip(SampleArgentina.TRIP_ID), Place.Clip(SampleArgentina.TRIP_ID)) {
            waitForText("Couldn't open this journey")
            onNodeWithText("Your trip is saved. Try opening the route again.").assertExists()
            broken.broken = false
            onNodeWithText("Try again").tap()
            waitForDescription("Clip preview")
        }
    }

    @Test fun aBackupsVideoChoicesAreThisPhones() {
        // An Android backup's "clip" and "map_style" files: a 4:5 clip on Terrain, its style in Film's look.
        val film = ClipDesign().copy(bars = true, grain = true)
        val clip = PrefsText.encode(mapOf("shape" to "portrait", "design" to ClipPrefs.encode(film), "defaults" to 4))
        val maps = PrefsText.encode(mapOf("clip" to "terrain", "trip_map" to "earth"))
        val settings = AppSettings.ready()
        settings.restorePreferences(BackupPreferences(clipStyle = clip, mapStyle = maps), replace = true)
        assertEquals(ClipShape.Portrait, ClipPrefs.shape(settings.prefs))
        assertTrue(ClipPrefs.design(settings.prefs).bars)
        assertEquals(MapStyle.Terrain, MapStylePrefs.load(settings.prefs, MapStylePrefs.CLIP))
        // The trip map's own look in the same file is untouched by the clip's.
        assertEquals(MapStyle.Earth, settings.tripMapStyle)
        // And they travel on in this phone's next backup, as they came.
        assertEquals(clip, settings.backupPreferences().clipStyle)
    }
}

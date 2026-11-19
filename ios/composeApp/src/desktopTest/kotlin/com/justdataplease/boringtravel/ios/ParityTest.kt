package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.justdataplease.boringtravel.compat.DateTimeFormatter
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.compat.SystemBars
import com.justdataplease.boringtravel.compat.setPhoneLanguage
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.theme.BtRoboto
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.TextFont
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The parity round of 19 Nov 2026 (the owner: "we need to be ios and android exactly the same"): the system's Back as
 * the iPhone's edge swipe sends it (the frame's, New trip's, Write's, a dialog's), the screens' fades, the tabs keeping
 * their pages, a note saved when the app leaves and the screens brought back after the phone closed it, the status bar
 * per theme and studio, dates and numbers in the phone's language, Roboto as the phone's font, Android's version, the
 * launch screen and the orientations, and the app sideways.
 */
@OptIn(ExperimentalTestApi::class)
class ParityTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking { BtFontFiles.load() }
    }

    /** Android's system Back as the iPhone's swipe from the left edge sends it: a navigation event. */
    private class SystemBack {
        private var input: DirectNavigationEventInput? = null

        @Composable
        fun Attach() {
            val owner = LocalNavigationEventDispatcherOwner.current
            DisposableEffect(owner) {
                val dispatcher = assertNotNull(owner).navigationEventDispatcher
                val i = DirectNavigationEventInput()
                dispatcher.addInput(i)
                input = i
                onDispose { dispatcher.removeInput(i) }
            }
        }

        fun ComposeUiTest.press() {
            runOnIdle { assertNotNull(input).backCompleted() }
            waitForIdle()
        }
    }

    /** A lifecycle the test moves (the app leaving the screen: ON_STOP). */
    private class TestLifecycle : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private fun ComposeUiTest.tab(label: String) = onNode(isTab and hasText(label, ignoreCase = true))

    private fun ComposeUiTest.exists(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // --- Back ---------------------------------------------------------------------------------------------------------

    @Test
    fun theEdgeSwipeIsAndroidsSystemBackForTheFrame() {
        val book = sampleBook()
        val current = assertNotNull(book.sections(today).now)
        val back = SystemBack()
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                back.Attach()
                BoringTravelApp(book, AppSettings.ready(), today, version = "1.0")
            }
            onNodeWithText(current.title).performClick()
            waitForIdle()
            onNodeWithText("Day 3 · Temples and tea").assertExists()
            // Back leaves the trip in one step (Android's NavHost pops it).
            with(back) { press() }
            assertFalse(exists("Day 3 · Temples and tea"))
            tab("Trips").assertIsSelected()
            // At the bottom there is nothing to go back to (Android would leave the app; an iPhone app stays).
            with(back) { press() }
            onNodeWithText(current.title).assertExists()
        }
    }

    @Test
    fun newTripsFormWriteAndADialogStepBackAsAndroid() {
        val book = sampleBook()
        val current = assertNotNull(book.sections(today).now)
        val note = book.entries(current.id).single()
        val back = SystemBack()
        // New trip: the form goes back to the start choices (Android's EditTripScreen BackHandler), then New trip closes.
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                back.Attach()
                BoringTravelApp(book, AppSettings.ready(), today, startAt = listOf(Place.NewTrip), version = "1.0")
            }
            onNodeWithText("Make it myself").performClick()
            waitForIdle()
            assertFalse(exists("Make it myself"))
            with(back) { press() }
            onNodeWithText("Make it myself").assertExists()
            with(back) { press() }
            assertFalse(exists("Make it myself"))
            tab("Trips").assertIsSelected()
        }
        // Write: Back leaves as its Back button does, the note saved (Android's BackHandler { leave() }).
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                back.Attach()
                BoringTravelApp(book, AppSettings.ready(), today, startAt = listOf(Place.Trip(current.id, TripTab.Journal), Place.Write(current.id)), version = "1.0")
            }
            onAllNodes(hasSetTextAction())[0].performTextInput("Harbour at dusk")
            with(back) { press() }
            assertTrue(book.entries(current.id).any { it.title == "Harbour at dusk" })
            onNodeWithText("Harbour at dusk").assertExists()
            tab("Journal").assertIsSelected()
        }
        // A dialog closes first (Compose's own, as Android's), then the note.
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                back.Attach()
                BoringTravelApp(book, AppSettings.ready(), today, startAt = listOf(Place.Trip(current.id, TripTab.Journal), Place.Note(current.id, note.id)), version = "1.0")
            }
            onNodeWithText("Delete").performClick()
            waitForIdle()
            onNodeWithText("Delete this note?").assertExists()
            with(back) { press() }
            assertFalse(exists("Delete this note?"))
            onNodeWithText("Journal entry").assertExists()
            with(back) { press() }
            assertFalse(exists("Journal entry"))
            tab("Journal").assertIsSelected()
        }
    }

    // --- Fades, tabs ----------------------------------------------------------------------------------------------------

    @Test
    fun screensFadeIntoEachOtherAsAndroidsNavHost() {
        val book = sampleBook()
        val current = assertNotNull(book.sections(today).now)
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent { BoringTravelApp(book, AppSettings.ready(), today, version = "1.0") }
            waitForIdle()
            mainClock.autoAdvance = false
            onNodeWithText(current.title).performClick()
            mainClock.advanceTimeBy(64)
            // Mid-fade both are there: Trips going (its "New trip"), the trip coming (its plan's day).
            assertTrue(exists("New trip"))
            assertTrue(exists("Day 3 · Temples and tea"))
            mainClock.advanceTimeBy(400)
            assertFalse(exists("New trip"))
            assertTrue(exists("Day 3 · Temples and tea"))
            mainClock.autoAdvance = true
        }
    }

    @Test
    fun aTabKeepsItsPagesAndPlaceWhileAnotherShowsAsAndroidsTabs() {
        val book = withLooseNotes(sampleBook())
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent { BoringTravelApp(book, AppSettings.ready(), today, version = "1.0") }
            tab("Calendar").performClick()
            onNodeWithText("November 2026").assertExists()
            onNodeWithContentDescription("Next month").performClick()
            onNodeWithText("December 2026").assertExists()
            // (Me has a "Calendar" of its own, Plan view's: away through Community.)
            tab("Community").performClick()
            onNodeWithText("Not on iPhone yet.").assertExists()
            // Back on the Calendar: still December (Android's restoreState).
            tab("Calendar").performClick()
            onNodeWithText("December 2026").assertExists()
            // A page straight over Trips isn't brought back by a tab (Android keeps it under no tab).
            tab("Trips").performClick()
            onNodeWithText("Notes outside trips").performClick()
            waitForIdle()
            onNodeWithText("Written when no trip was on.").assertExists()
            tab("Calendar").performClick()
            onNodeWithText("December 2026").assertExists()
            tab("Trips").performClick()
            assertFalse(exists("Written when no trip was on."))
            onNodeWithText("2 notes").assertExists()
        }
    }

    // --- Leaving and coming back --------------------------------------------------------------------------------------

    @Test
    fun aNoteIsSavedWhenTheAppLeavesAndTheScreensComeBackAfterThePhoneClosedIt() {
        val book = sampleBook()
        val current = assertNotNull(book.sections(today).now)
        val life = TestLifecycle()
        var kept: String? = null
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides life) {
                    BoringTravelApp(
                        book, AppSettings.ready(), today, startAt = listOf(Place.Trip(current.id, TripTab.Journal), Place.Write(current.id)),
                        version = "1.0", keep = { kept = it },
                    )
                }
            }
            waitForIdle()
            mainClock.autoAdvance = false
            onAllNodes(hasSetTextAction())[0].performTextInput("Harbour at dusk")
            mainClock.advanceTimeByFrame()
            // Typing hasn't paused long enough to save (0.8 s)...
            assertFalse(book.entries(current.id).any { it.title == "Harbour at dusk" })
            // ...the app leaving the screen saves it at once (Android's WriteScreen: ON_STOP, flush).
            runOnIdle { life.registry.currentState = Lifecycle.State.CREATED }
            mainClock.advanceTimeByFrame()
            assertTrue(book.entries(current.id).any { it.title == "Harbour at dusk" })
            mainClock.autoAdvance = true
            waitForIdle()
        }
        val noteId = book.entries(current.id).single { it.title == "Harbour at dusk" }.id
        val saved = assertNotNull(AppRestore.decode(kept, book))
        assertEquals(listOf(Place.Trips, Place.Trip(current.id, TripTab.Journal), Place.Write(current.id, noteId)), saved.stack)
        assertEquals(TripTab.Journal, saved.tripTab)
        // The phone opens the app again where it was: the note in Write, then Back to the trip's Journal.
        val back = SystemBack()
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                back.Attach()
                BoringTravelApp(book, AppSettings.ready(), today, version = "1.0", restored = kept)
            }
            waitForIdle()
            assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty())
            onNodeWithText("Harbour at dusk").assertExists()
            with(back) { press() }
            tab("Journal").assertIsSelected()
            onNodeWithText("Harbour at dusk").assertExists()
        }
        // Still exactly one note by that name: the restored Write went on with it rather than starting another.
        assertEquals(1, book.entries(current.id).count { it.title == "Harbour at dusk" })
    }

    // --- Status bar -------------------------------------------------------------------------------------------------

    @Test
    fun theStatusBarIsAndroidsOnEachThemeAndInTheStudios() {
        val book = sampleBook()
        val current = assertNotNull(book.sections(today).now)
        val settings = AppSettings.ready(Look.PostcardPop)
        val back = SystemBack()
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent {
                back.Attach()
                BoringTravelApp(book, settings, today, version = "1.0")
            }
            waitForIdle()
            // Dark icons on the light themes, light ones on Trail (Android's SystemBars).
            assertTrue(SystemBars.lightStatusBars)
            runOnIdle { settings.look = Look.Trail }
            waitForIdle()
            assertFalse(SystemBars.lightStatusBars)
            runOnIdle { settings.look = Look.Sketchbook }
            waitForIdle()
            assertTrue(SystemBars.lightStatusBars)
            // A studio is dark whatever the theme: light icons while it shows (Android's StudioSystemBars)...
            onNodeWithText(current.title).performClick()
            waitForIdle()
            onNodeWithContentDescription("Trip options").performClick()
            onNodeWithText("Make a clip").performClick()
            waitForIdle()
            assertFalse(SystemBars.lightStatusBars)
            // ...and the theme's again when it goes.
            with(back) { press() }
            assertTrue(SystemBars.lightStatusBars)
        }
    }

    // --- The phone's language ---------------------------------------------------------------------------------------

    @Test
    fun datesAndNumbersOnTheScreensFollowThePhonesLanguage() {
        val book = sampleBook()
        setPhoneLanguage("el-GR")
        try {
            runDesktopComposeUiTest(width = 440, height = 1800) {
                setContent { BoringTravelApp(book, AppSettings.ready(), today, version = "1.0") }
                // Trips: today's eyebrow and a trip's month (the engine's Dates).
                onNodeWithText("Τετ 18 Νοε", ignoreCase = true).assertExists()
                onNodeWithText("17 Ιουν 2027 · 10 days", ignoreCase = true).assertExists()
                // The Calendar's month (Android: "LLLL yyyy" titled).
                tab("Calendar").performClick()
                onNodeWithText("Νοέμβριος 2026").assertExists()
            }
        } finally {
            setPhoneLanguage(null)
        }
        // British English is the phone's too, as Java and Android write it ("Sept"); US English Java's own ("Sep").
        assertEquals("Fri 10 Sept 2027", DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK).format(localDate(2027, 9, 10)))
        assertEquals("Fri 10 Sep 2027", DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US).format(localDate(2027, 9, 10)))
    }

    // --- Roboto, version, launch screen ---------------------------------------------------------------------------

    @Test
    fun robotoIsGooglesRoboto2AndThePhoneFontOnBothPhones() {
        // Google's roboto-2 v2.138 release for Android (roboto-android.zip), as Street Gym bundles it: the files byte for byte.
        val expected = mapOf(
            "Roboto-Regular.ttf" to "797e35f7f5d6020a5c6ea13b42ecd668bcfb3bbc4baa0e74773527e5b6cb3174",
            "Roboto-Italic.ttf" to "5cd9337020d0b1593bae42dcca5cfc0fb86f5806ef469d66d09eae1e56ce346b",
            "Roboto-Medium.ttf" to "7984aafeaf437899ce89b251d2875fc5048cb7d290152eac070239cca7ff726c",
            "Roboto-Bold.ttf" to "36f3709dea3e3ce3c6aedc058079e55980825f898f1e901d091c73c40de8bab1",
            "Roboto-BoldItalic.ttf" to "63d368b094a9bde61447b410b4261b30569eef1bf2094a52de9828121ba7baf7",
        )
        val folder = File("src/commonMain/composeResources/files/roboto")
        assertEquals(expected.keys, folder.list()!!.toSet())
        assertEquals(BtRoboto.FILES.toSet(), expected.keys)
        for ((name, sha) in expected) {
            val digest = MessageDigest.getInstance("SHA-256").digest(File(folder, name).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(sha, digest, name)
        }
        val licence = File("src/commonMain/composeResources/files/licenses/roboto-LICENSE.txt").readText()
        assertTrue("Apache License" in licence && "Version 2.0, January 2004" in licence)
        // "Phone (Roboto)", the default text font, is Roboto here as on Android, and so is the drawings' system face.
        var body: Any? = null
        runDesktopComposeUiTest(width = 200, height = 200) {
            setContent { BoringTravelTheme(Look.PostcardPop, FontSet.LooksOwn, TextFont.Phone) { body = Bt.text.body.fontFamily } }
            waitForIdle()
        }
        assertSame(BtRoboto.family, body)
        assertSame(BtRoboto.family, com.justdataplease.boringtravel.compat.graphics.Typeface.DEFAULT.family)
        assertEquals("Phone (Roboto)", textFontLabel(TextFont.Phone))
    }

    @Test
    fun theVersionIsAndroidsVersionName() {
        val android = File("../../android-boringtravel/app/build.gradle.kts").readText()
        val name = assertNotNull(Regex("""\bversionName\s*=\s*"([^"]+)"""").find(android)).groupValues[1]
        assertEquals(name, AppVersion.NAME)
        // The App Store's version is the same number.
        val project = File("../iosApp/project.yml").readText()
        assertEquals(name, assertNotNull(Regex("""MARKETING_VERSION:\s*"([^"]+)"""").find(project)).groupValues[1])
        runDesktopComposeUiTest(width = 440, height = 1800) {
            setContent { BoringTravelApp(TripBook(MemoryTripFile()), AppSettings.ready(), today, startAt = listOf(Place.Home(HomeTab.Me)), version = PREVIEW_VERSION) }
            onNodeWithText(name).assertExists()
        }
    }

    @Test
    fun theLaunchScreenAndTheOrientationsAreAndroids() {
        val project = File("../iosApp/project.yml").readText()
        assertTrue("UIImageName: LaunchIcon" in project)
        for (o in listOf("UIInterfaceOrientationPortrait", "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight")) assertTrue(o in project, o)
        assertFalse("UIInterfaceOrientationPortraitUpsideDown" in project)
        // The app's icon in Android 12's splash circle: 160 points, clear outside the circle, the icon's yellow inside.
        for ((file, px) in listOf("launch-icon@2x.png" to 320, "launch-icon@3x.png" to 480)) {
            val image = ImageIO.read(File("../iosApp/BoringTravel/Assets.xcassets/LaunchIcon.imageset/$file"))
            assertEquals(px, image.width)
            assertEquals(px, image.height)
            assertEquals(0, image.getRGB(2, 2) ushr 24)
            assertEquals(0xFF, image.getRGB(px / 2, px / 8) ushr 24)
        }
    }

    @Test
    fun theAppTurnsSidewaysAndStretchesAsAndroids() {
        val book = sampleBook()
        val current = assertNotNull(book.sections(today).now)
        runDesktopComposeUiTest(width = 956, height = 440) {
            setContent { BoringTravelApp(book, AppSettings.ready(), today, version = "1.0") }
            onNodeWithText(current.title).assertIsDisplayed()
            tab("Me").assertIsDisplayed()
            onNodeWithText(current.title).performClick()
            waitForIdle()
            tab("Plan").assertIsDisplayed()
            tab("Map").performClick()
            tab("Map").assertIsSelected()
        }
    }
}

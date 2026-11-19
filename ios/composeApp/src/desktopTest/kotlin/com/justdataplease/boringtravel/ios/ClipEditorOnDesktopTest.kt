package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.geometry.Offset
import com.justdataplease.boringtravel.clip.ClipEdits
import com.justdataplease.boringtravel.clip.ClipMedia
import com.justdataplease.boringtravel.clip.ClipPrefs
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.ClipView
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.MapStylePrefs
import com.justdataplease.boringtravel.clip.NamePlace
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.clip.music.MusicPrefs
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trip video's editor driven on the desktop (round 19), offline, on the demo's Argentina: the tracks and the tools
 * (Android's own, read in), choosing a scene on the tracks, Let go and Skip stop, Undo and Redo, a tool's change kept as
 * Android keeps it (the "clip" settings file for the clip's choices, clip-edits/<trip>.json for the trip's), a name
 * typed as one undo step, the music listened to and played under the preview in step, a voice line with the
 * microphone asked once at the moment it starts, a phone video copied in after its place, the Camera's map and view, a
 * name dragged on the stage, and the tools round 20 opens saying so. The phone is fakes that record what they are asked.
 */
@OptIn(ExperimentalTestApi::class)
class ClipEditorOnDesktopTest {
    init {
        runBlocking {
            BtFontFiles.load()
            CountryOutlines.load()
        }
    }

    private val trip = SampleArgentina.TRIP_ID

    private class Phone(val book: TripBook, val files: MemoryMediaFiles, val settings: AppSettings, val kit: MediaKit, val device: ClipDevice)

    private fun phone(
        sounds: ClipSounds = FakeSounds(), mic: ((MemoryMediaFiles) -> Microphone)? = null, picker: ((MediaFiles) -> MediaPicker)? = null,
    ): Phone {
        val (book, demo) = demoBook()
        val files = demo.files as MemoryMediaFiles
        val kit = MediaKit(files, picker?.invoke(files) ?: NoPicker)
        val device = ClipDevice(sounds, mic?.invoke(files) ?: NoMicrophone, DrawnVideoFiles(files))
        return Phone(book, files, AppSettings.ready().apply { demoMode = true }, kit, device)
    }

    private fun editor(p: Phone, block: ComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = 440, height = 956) {
        setContent {
            CompositionLocalProvider(LocalClipDevice provides p.device) {
                BoringTravelApp(p.book, p.settings, SAMPLE_TODAY, startAt = listOf(Place.Trip(trip), Place.Clip(trip)), version = "1.0", media = p.kit)
            }
        }
        waitForIdle()
        waitForDescription("Clip preview")
        // The tracks once the plan's timeline is in.
        waitForDescription("Map scenes")
        block()
    }

    private fun ComposeUiTest.waitForText(text: String, timeout: Long = 30_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.waitForDescription(text: String, timeout: Long = 60_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.gone(text: String, timeout: Long = 10_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }

    /** A tap by the node's own click action (the studio's panels and bars sit close together). */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun ComposeUiTest.tapText(text: String) = onAllNodesWithText(text).onFirst().tap()

    /** Chooses the scenes track's block of the place whose name starts with [day] ("Day 2"). */
    private fun ComposeUiTest.choosePlace(day: String = "Day 2") {
        onAllNodes(hasContentDescription(day, substring = true)).onFirst().tap()
        waitForText("Let go")
    }

    /** This trip's edits as the editor keeps them (clip-edits/<trip>.json), once every write asked for is done. */
    private fun edits(p: Phone): ClipEdits {
        runBlocking { ClipEditStore.flush() }
        return ClipEditStore.load(p.files, trip)[ClipSource.Planned] ?: ClipEdits()
    }

    @Test fun theTracksTheToolsAndUndoShow() {
        editor(phone()) {
            // Five tracks under the playhead, the music's name on its track.
            for (track in listOf("Map scenes", "Photos", "Texts", "Music", "Voice")) onNodeWithContentDescription(track).assertExists()
            onNodeWithContentDescription("Music: Latin Groove").assertExists()
            // Nothing to undo or redo yet.
            onNodeWithContentDescription("Undo").assertIsNotEnabled()
            onNodeWithContentDescription("Redo").assertIsNotEnabled()
            // The tools for the whole clip, in Android's order; since round 20 every one of them opens.
            for (tool in listOf("Text", "Audio", "Media", "Camera", "Length", "Route", "Look", "Stats")) onAllNodesWithText(tool).onFirst().assertExists()
            tapText("Look")
            waitForText("A look sets the type")
            onAllNodesWithText(NOT_ON_IPHONE).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        }
    }

    /**
     * Round 20's tools, Android's own panels: Length (a set length, then Pace), Route (the route's line, Traveller &
     * rides with Make default into Me's), Look (a named look, then Custom… after a change) and Stats; each change one
     * undo step, kept as Android keeps it.
     */
    @Test fun lengthRouteLookAndStatsWorkAsOnAndroid() {
        val p = phone()
        editor(p) {
            // Length: 30 s, kept in the "clip" settings file; Pace ×2 instead.
            tapText("Length")
            waitForText("Pace")
            tapText("30 s")
            waitUntil(timeoutMillis = 30_000) { ClipPrefs.length(p.settings.prefs) == com.justdataplease.boringtravel.clip.ClipLength.S30 }
            onNodeWithContentDescription("Undo").assertIsEnabled()
            onNodeWithContentDescription("Done").tap()
            // Look: Film sets the finish (bars, grain); a change after it reads Custom.
            tapText("Look")
            waitForText("A look sets the type")
            tapText("Film")
            waitUntil(timeoutMillis = 30_000) { ClipPrefs.design(p.settings.prefs).look == com.justdataplease.boringtravel.clip.ClipLook.Film }
            assertTrue(ClipPrefs.design(p.settings.prefs).bars)
            // Film's tone is Warm: Natural is a change after the look.
            tapText("Natural")
            waitForText("Custom: an option changed")
            onNodeWithContentDescription("Done").tap()
            // Stats: none, then back to Day · km.
            tapText("Stats")
            waitForText("Counts up as the route goes")
            tapText("None")
            waitForText("No stats on the video")
            onNodeWithContentDescription("Done").tap()
            // Route: the line dashed, and the traveller as a woman for this video, then made Me's default.
            tapText("Route")
            // The sections' names are drawn in capitals (ROUTE FROM): its first choice instead.
            waitForText("Trip plan")
            tapText("Dashed")
            waitUntil(timeoutMillis = 30_000) { ClipPrefs.design(p.settings.prefs).line == com.justdataplease.boringtravel.clip.LineStyle.Dashed }
            onAllNodesWithText("FROM ME").onFirst().assertExists()
            tapText("Woman")
            waitForText("THIS VIDEO")
            waitForText("Make default")
            tapText("Make default")
            waitUntil(timeoutMillis = 30_000) { p.settings.traveller.who == com.justdataplease.boringtravel.clip.Who.Woman }
            waitForText("FROM ME")
        }
    }

    @Test fun choosingAPlacePutsItsToolsFirstAndLetGoFreesIt() {
        val p = phone()
        editor(p) {
            choosePlace()
            // Its own tools under its name, Skip stop for a planned place, Let go.
            waitForText("SCENE · DAY 2")
            onNodeWithText("Skip stop").assertExists()
            onNodeWithText("WHOLE CLIP").assertExists()
            tapText("Let go")
            gone("Skip stop")
            onNodeWithText("EDIT").assertExists()
        }
    }

    @Test fun skipStopLeavesThePlaceOutAndUndoBringsItBack() {
        val p = phone()
        editor(p) {
            choosePlace()
            tapText("Skip stop")
            gone("Let go")
            waitUntil(timeoutMillis = 10_000) { edits(p).skipped.isNotEmpty() }
            onNodeWithContentDescription("Undo").assertIsEnabled()
            onNodeWithContentDescription("Undo").tap()
            waitUntil(timeoutMillis = 10_000) { edits(p).skipped.isEmpty() }
            onNodeWithContentDescription("Redo").assertIsEnabled()
        }
    }

    @Test fun theTextToolsChangeIsTheClipsChoiceKeptForTheNextClipAndUndone() {
        val p = phone()
        editor(p) {
            tapText("Text")
            waitForText("Place names")
            // Android's panel: Place names, Title at the start, the size, the pad, Font, Behind the text, Colour, Edit stops.
            for (line in listOf("Title at the start", "TEXT SIZE", "PLACE NAMES GO", "FONT", "BEHIND THE TEXT", "Edit stops")) waitForText(line)
            tapText("Place names")
            waitUntil(timeoutMillis = 10_000) { ClipPrefs.design(p.settings.prefs).name == NamePlace.Off }
            onNodeWithContentDescription("Undo").tap()
            waitUntil(timeoutMillis = 10_000) { ClipPrefs.design(p.settings.prefs).name != NamePlace.Off }
            onNodeWithContentDescription("Redo").tap()
            waitUntil(timeoutMillis = 10_000) { ClipPrefs.design(p.settings.prefs).name == NamePlace.Off }
            // Done closes the panel: the tracks again.
            onNodeWithContentDescription("Done").tap()
            waitForDescription("Map scenes")
        }
    }

    @Test fun aPlacesNameTypedIsOneUndoStepKeptInAndroidsForm() {
        val p = phone()
        editor(p) {
            choosePlace()
            // The scene's own Text tool opens on "This scene": the place's name, note and spot.
            tapText("Text")
            waitForText("THIS SCENE · DAY 2")
            waitForText("Show the name")
            // The fields: the small line, the name, the note, the exact spot.
            val name = onAllNodes(hasSetTextAction())[1]
            name.performTextInput("Glacier")
            name.performTextInput(" walk")
            val key = waitForEdit(p) { it.scenes.values.any { o -> o.text == "Glacier walk" } }.scenes.entries.first { it.value.text == "Glacier walk" }.key
            // Android's own form, a part per route source.
            runBlocking { ClipEditStore.flush() }
            val json = Json.parseToJsonElement(assertNotNull(p.files.read(ClipEditStore.path(trip))).decodeToString()).jsonObject
            assertEquals("1", json["v"].toString())
            val scene = json["sources"]!!.jsonObject["Planned"]!!.jsonObject["scenes"]!!.jsonObject[key]!!.jsonObject
            assertEquals("\"Glacier walk\"", scene["text"].toString())
            // Both pieces of typing are one step: one Undo takes the name away.
            onNodeWithContentDescription("Undo").tap()
            waitUntil(timeoutMillis = 10_000) { edits(p).scenes[key]?.text == null }
        }
    }

    private fun ComposeUiTest.waitForEdit(p: Phone, test: (ClipEdits) -> Boolean): ClipEdits {
        var found: ClipEdits? = null
        waitUntil(timeoutMillis = 10_000) { edits(p).also { found = it }.let(test) }
        return found!!
    }

    @Test fun audioListensPicksATrackAndStopsWhenThePanelCloses() {
        val sounds = FakeSounds()
        val p = phone(sounds)
        editor(p) {
            tapText("Audio")
            waitForText("STARTS AT")
            waitForText("On the beat")
            // Listen to a track: its own player, from its start; the button says Stop.
            onNodeWithContentDescription("Play Afro Groove").tap()
            waitForDescription("Stop Afro Groove")
            val listened = sounds.players.last { it.uri.endsWith("files/music/afro_groove.m4a") }
            assertTrue(listened.isPlaying)
            // Pick it: the clip's music now, remembered for the next clip as Android remembers it.
            tapText("Afro Groove")
            waitUntil(timeoutMillis = 10_000) { MusicPrefs.load(p.settings.prefs) == "afro_groove" }
            // Done: listening stops with the panel.
            onNodeWithContentDescription("Done").tap()
            waitUntil(timeoutMillis = 10_000) { listened.released }
            onNodeWithContentDescription("Music: Afro Groove").assertExists()
        }
    }

    @Test fun theMusicPlaysUnderThePreviewFromWhereTheClipsMusicStarts() {
        val sounds = FakeSounds()
        val p = phone(sounds)
        editor(p) {
            onNodeWithContentDescription("Play").tap()
            waitUntil(timeoutMillis = 20_000) { sounds.events.any { it.startsWith("play ") } }
            val startMs = (MusicCatalog.startFor("latin_groove", null) * 1000).toInt()
            val first = sounds.events.first { it.startsWith("play ") }.removePrefix("play ").toInt()
            assertTrue(first in startMs..startMs + 5_000, "the music starts at $first ms, the clip's music at $startMs ms")
            assertTrue(sounds.players.first().uri.endsWith("files/music/latin_groove.m4a"))
            onNodeWithContentDescription("Pause").tap()
            waitUntil(timeoutMillis = 10_000) { sounds.events.last() == "pause" }
        }
    }

    @Test fun aVoiceLineAsksForTheMicrophoneOnceAtTheStartAndIsKept() {
        var mic: FakeMicrophone? = null
        val p = phone(mic = { files -> FakeMicrophone(files, MicAccess.NotAsked).also { mic = it } })
        editor(p) {
            choosePlace()
            assertEquals(0, mic!!.asked)
            // "+ Record" at the chosen place: the microphone asked, then recording at once.
            onAllNodesWithText("+ Record").onFirst().tap()
            waitForText("Speak now. It stops by itself after 20 s.")
            assertEquals(1, mic!!.asked)
            Thread.sleep(700)
            tapText("Stop")
            waitForText("Record again")
            val line = waitForEdit(p) { it.voices.isNotEmpty() }.voices.single()
            assertTrue(line.durationMs in 400..20_000, "the line is ${line.durationMs} ms")
            assertNotNull(p.files.read(ClipMedia.voiceDir(trip) + "/" + line.file))
            // Again: no second question.
            tapText("Record again")
            waitForText("Speak now")
            tapText("Cancel")
            waitForText("Record again")
            assertEquals(1, mic!!.asked)
        }
    }

    @Test fun aRefusedMicrophoneAndATooShortLineSaySo() {
        editor(phone(mic = { files -> FakeMicrophone(files, MicAccess.Refused) })) {
            choosePlace()
            onAllNodesWithText("+ Record").onFirst().tap()
            waitForText("Allow the microphone to record a voice line.")
        }
        editor(phone(mic = { files -> FakeMicrophone(files, MicAccess.Allowed) })) {
            choosePlace()
            onAllNodesWithText("+ Record").onFirst().tap()
            waitForText("Speak now")
            tapText("Stop")
            waitForText("Too short. Hold on a little longer.")
        }
    }

    @Test fun aPhoneVideoIsCopiedInAfterItsPlaceAndShowsOnTheTracks() {
        var picker: FakeVideoPicker? = null
        val p = phone(picker = { files -> FakeVideoPicker(files).also { picker = it } })
        editor(p) {
            choosePlace()
            tapText("Media")
            waitForText("MEDIA HERE")
            tapText("Add media")
            waitForText("From your phone")
            waitForText("Videos play after this place, 1–10 s each, up to 8.")
            tapText("From your phone")
            // Copied in: its first 5 s after the place, sound off, the picker's copy gone.
            val video = waitForEdit(p) { it.videos.isNotEmpty() }.videos.single()
            assertEquals(5_000, video.lengthMs)
            assertEquals(8_000, video.durationMs)
            assertEquals(false, video.sound)
            assertNull(video.source)
            assertNotNull(p.files.read(ClipMedia.videoDir(trip) + "/" + video.file))
            assertNull(p.files.read("picked/phone-video-1.mov"))
            assertEquals(1, picker!!.opened)
            // Its row in the place's media, and its block on the tracks once the panel is done.
            waitForText("Video 1 · 5 s")
            onNodeWithContentDescription("Done").tap()
            waitForDescription(", Video")
        }
    }

    @Test fun theCameraToolChangesTheMapAndTheViewAndUndoTakesThemBack() {
        val p = phone()
        editor(p) {
            tapText("Camera")
            waitForText("Starts from space over your trip")
            tapText("Street")
            waitUntil(timeoutMillis = 10_000) { MapStylePrefs.load(p.settings.prefs, MapStylePrefs.CLIP) == MapStyle.Street }
            tapText("Flat")
            waitUntil(timeoutMillis = 10_000) { ClipPrefs.view(p.settings.prefs) == ClipView.Flat }
            waitForText("The map seen from straight above, no sky.")
            onNodeWithContentDescription("Undo").tap()
            waitUntil(timeoutMillis = 10_000) { ClipPrefs.view(p.settings.prefs) != ClipView.Flat }
            onNodeWithContentDescription("Undo").tap()
            waitUntil(timeoutMillis = 10_000) { MapStylePrefs.load(p.settings.prefs, MapStylePrefs.CLIP) == MapStyle.Globe }
        }
    }

    @Test fun aChosenNameIsDraggedOnTheStageAsOneEdit() {
        val p = phone()
        editor(p) {
            choosePlace()
            // Once the still frame has the name's place drawn (the dashed ring: a drag before it moves nothing), a drag
            // down the picture. A busy machine draws it later: the drag is tried again until it lands.
            var y: Float? = null
            repeat(30) {
                if (y != null) return@repeat
                Thread.sleep(400)
                waitForIdle()
                onNodeWithContentDescription("Clip preview").performTouchInput {
                    swipe(Offset(width * 0.5f, height * 0.2f), Offset(width * 0.5f, height * 0.6f), durationMillis = 400)
                }
                waitForIdle()
                y = edits(p).scenes.values.firstNotNullOfOrNull { it.y }
            }
            val moved = assertNotNull(y)
            assertTrue(moved > 0.2f, "the name moved down to $moved")
            onNodeWithContentDescription("Undo").tap()
            waitUntil(timeoutMillis = 10_000) { edits(p).scenes.values.none { it.y != null } }
        }
    }
}

package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.checkin.DailyCheckIn
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.speech.DictationRecovery
import com.justdataplease.boringtravel.speech.SpeechRecognizer
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dictation and the daily check-in driven on the desktop: dictation's states through a scripted recognizer with its
 * words landing in the note (and the Android app's own rules for pauses, errors and the long pause), and a check-in
 * shown, written, kept with the trips and not asked again.
 */
@OptIn(ExperimentalTestApi::class)
class DictationAndCheckInsOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    private fun ui(book: TripBook, speech: SpeechEngine, settings: AppSettings = AppSettings.ready(), web: Web = Web.offline(), vararg at: Place, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 440, height = 1600) {
            setContent {
                CompositionLocalProvider(LocalSpeech provides speech) {
                    BoringTravelApp(book, settings, SAMPLE_TODAY, startAt = at.toList(), version = "1.0", web = web)
                }
            }
            waitForIdle()
            block()
            waitForIdle()
        }

    private fun ComposeUiTest.has(text: String, substring: Boolean = true) =
        onAllNodes(hasText(text, substring = substring, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun wordsSpokenLandInTheNoteAndAreKept() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val trip = book.newTrip("Naxos", SAMPLE_TODAY.minusDays(1), SAMPLE_TODAY.plusDays(3))
        val speech = ScriptedSpeech()
        ui(book, speech, AppSettings.ready().apply { dictationLanguage = "el-GR" }, at = arrayOf(Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id))) {
            onNodeWithContentDescription("Dictate").performClick()
            // Listening, in the language of Me › Voice notes.
            onNodeWithText("Listening · Greek").assertExists()
            onNodeWithContentDescription("Stop dictation").assertExists()
            assertEquals(listOf("el-GR" to true), speech.sessions)
            // Words appear as they are heard, a capital at the start, and are fixed at the pause.
            speech.say("we took the ferry")
            waitForIdle()
            assertTrue(has("We took the ferry"))
            speech.pause("we took the ferry to naxos")
            waitForIdle()
            assertTrue(has("We took the ferry to naxos"))
            // After the pause it listens again.
            mainClock.advanceTimeBy(500)
            waitUntil(timeoutMillis = 5_000) { speech.sessions.size == 2 }
            speech.say("then lunch")
            waitForIdle()
            assertTrue(has("We took the ferry to naxos then lunch"))
            // Stop: the words heard so far stay.
            onNodeWithContentDescription("Stop dictation").performClick()
            waitForIdle()
            onNodeWithText("Listening · Greek").assertDoesNotExist()
            onNodeWithContentDescription("Dictate").assertExists()
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
        }
        val note = book.entries(trip.id).single()
        assertEquals("We took the ferry to naxos then lunch", note.plainText)
        val data = BackupJson.decodeFromString(BackupData.serializer(), file.read()!!)
        assertEquals(book.snapshot, data.toSnapshot())
    }

    @Test
    fun theMicrophoneIsAskedForOnceAndARefusalLeadsToSettings() {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", SAMPLE_TODAY, SAMPLE_TODAY.plusDays(3))
        // First use: a word first, then the phone's question.
        val first = ScriptedSpeech(access = MicAccess.NotAsked)
        ui(book, first, at = arrayOf(Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id))) {
            onNodeWithContentDescription("Dictate").performClick()
            onNodeWithText("Dictation uses the microphone only while the mic button is on.").assertExists()
            onNodeWithText("Not now").performClick()
            assertEquals(0, first.asked)
            onNodeWithContentDescription("Dictate").performClick()
            onNodeWithText("Continue").performClick()
            waitForIdle()
            assertEquals(1, first.asked)
            // The phone's language when Me › Voice notes says nothing.
            onNodeWithText("Listening · English").assertExists()
        }
        // Refused for good: the way to Settings.
        val refused = ScriptedSpeech(access = MicAccess.Refused)
        ui(book, refused, at = arrayOf(Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id))) {
            onNodeWithContentDescription("Dictate").performClick()
            onNodeWithText("The microphone is off for this app. You can turn it on in Settings.").assertExists()
            onNodeWithText("Open Settings").performClick()
            assertEquals(1, refused.settingsOpened)
            assertTrue(refused.sessions.isEmpty())
        }
    }

    @Test
    fun errorsShowInRedAndADismissClearsThem() {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", SAMPLE_TODAY, SAMPLE_TODAY.plusDays(3))
        val speech = ScriptedSpeech()
        ui(book, speech, at = arrayOf(Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id))) {
            onNodeWithContentDescription("Dictate").performClick()
            speech.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            waitForIdle()
            onNodeWithText("Allow the microphone to dictate.").assertExists()
            onNodeWithContentDescription("Dismiss").performClick()
            onNodeWithText("Allow the microphone to dictate.").assertDoesNotExist()
        }
    }

    @Test
    fun theNowCardsMicOpensWriteDictating() {
        val book = sampleBook()
        val speech = ScriptedSpeech()
        ui(book, speech) {
            onNodeWithContentDescription("Dictate").performClick()
            waitForIdle()
            onNodeWithText("Listening · English").assertExists()
            assertEquals(1, speech.sessions.size)
        }
    }

    @Test
    fun dictationRecoversAsAndroidsDoesAndStopsAfterALongPause() {
        var clock = 0L
        val speech = ScriptedSpeech(offline = false)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val heard = mutableListOf<Pair<String, Boolean>>()
        val d = Dictation(speech, scope, clock = { clock }, wait = {})
        d.onText = { text, final -> heard += text to final }
        d.start("fr-FR")
        assertEquals(Dictation.State.Listening("French"), d.state)
        // Online when the phone has no offline pack for the language.
        assertEquals("fr-FR" to false, speech.sessions.single())
        // Silence only listens again.
        repeat(4) { speech.error(SpeechRecognizer.ERROR_NO_MATCH) }
        assertEquals(5, speech.sessions.size)
        assertIs<Dictation.State.Listening>(d.state)
        // Hiccups in a row stop it (DictationRecovery.MAX_HICCUPS), with Android's words.
        repeat(DictationRecovery.MAX_HICCUPS + 1) { speech.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
        assertEquals(Dictation.State.Failed(DictationRecovery.STOPPED), d.state)
        // No internet and no offline pack.
        d.start("fr-FR")
        speech.error(SpeechRecognizer.ERROR_NETWORK)
        assertEquals(Dictation.State.Failed("No internet, and offline dictation isn't ready for French."), d.state)
        // Three minutes without words.
        d.start("fr-FR")
        speech.say("bonjour")
        speech.pause()
        clock += Dictation.LONG_PAUSE_MS + 1
        speech.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals(Dictation.State.Failed("Stopped after a long pause."), d.state)
        assertTrue("bonjour" to true in heard)
        // Released: nothing late is written.
        d.start("fr-FR")
        val late = speech.listener
        d.release()
        late?.onPartial("trop tard")
        assertFalse(heard.any { it.first == "trop tard" })
        // Apple's errors read as Android's codes.
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, AppleSpeechErrors.code("kAFAssistantErrorDomain", 1110))
        assertNull(AppleSpeechErrors.code("kLSRErrorDomain", 301))
        assertEquals(SpeechRecognizer.ERROR_NETWORK, AppleSpeechErrors.code("NSURLErrorDomain", -1009))
    }

    @Test
    fun aCheckInIsShownWrittenKeptAndNotAskedAgain() {
        val file = MemoryTripFile()
        var clock = 1_795_000_000_000L
        val book = TripBook(file, now = { clock++ })
        val trip = book.newTrip("Naxos", SAMPLE_TODAY.minusDays(1), SAMPLE_TODAY.plusDays(3))
        val halki = book.saveItem(book.draftItem(trip.id, SAMPLE_TODAY, 1.0).copy(title = "Kitron distillery", placeName = "Halki, Naxos, Greece", lat = 37.0587, lng = 25.4807))!!
        book.saveItem(book.draftItem(trip.id, SAMPLE_TODAY, 2.0).copy(title = "Portara", lat = 37.1111, lng = 25.3721))
        val stored = MemorySettingsFile()
        val settings = AppSettings(stored).apply { onboardingDone = true }
        ui(book, ScriptedSpeech(), settings, at = arrayOf(Place.Trip(trip.id, TripTab.Journal))) {
            onNodeWithText("Today's check-in", ignoreCase = true).assertExists()
            // The place as a note written from the item names it.
            onNodeWithText("At Kitron distillery (Naxos)?").assertExists()
            onNodeWithText("From today's plan · Naxos").assertExists()
            onNodeWithText("Yes, write").performClick()
            waitForIdle()
            // Write, on today's check-in note with its place chip.
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
            // Today is written: no card.
            onNodeWithText("Today's check-in", ignoreCase = true).assertDoesNotExist()
        }
        val note = book.entries(trip.id).single()
        assertEquals(DailyCheckIn.entryId(trip.id, SAMPLE_TODAY), note.id)
        assertEquals("Kitron distillery (Naxos)", note.placeName)
        assertEquals(Block.Place("Kitron distillery (Naxos)", halki.lat, halki.lng), EntryDoc.parse(note.body).blocks.first())
        val data = BackupJson.decodeFromString(BackupData.serializer(), file.read()!!)
        assertEquals(book.snapshot, data.toSnapshot())
        // Asked once, kept on this phone only (the backup's preferences have no place for it).
        assertTrue(stored.read("checkInsAsked")!!.endsWith(":${trip.id}"))
        assertFalse("checkIns" in settings.backupPreferences().toString())
    }

    @Test
    fun anotherPlaceAndTheCurrentLocationPickTodaysPlace() {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", SAMPLE_TODAY.minusDays(1), SAMPLE_TODAY.plusDays(3))
        book.saveItem(book.draftItem(trip.id, SAMPLE_TODAY, 1.0).copy(title = "Kitron distillery", placeName = "Halki, Naxos, Greece", lat = 37.0587, lng = 25.4807))
        book.saveItem(book.draftItem(trip.id, SAMPLE_TODAY, 2.0).copy(title = "Portara", lat = 37.1111, lng = 25.3721))
        // The phone is at the Portara, 10 m accurate.
        val there = Recorded.web(location = FixedLocation(LatLng(37.1112, 25.3722), accuracyM = 10f))
        ui(book, ScriptedSpeech(), web = there, at = arrayOf(Place.Trip(trip.id, TripTab.Journal))) {
            onNodeWithText("Another place").performClick()
            onNodeWithText("Where are you today?").assertExists()
            onNodeWithText("Use current location").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("At Portara?").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("A recent location suggests you're nearby.").assertExists()
            // X: not today.
            onNodeWithContentDescription("Skip today's check-in").performClick()
            onNodeWithText("At Portara?").assertDoesNotExist()
        }
        // Far from both: no match, the places to pick from.
        val book2 = TripBook(MemoryTripFile())
        val trip2 = book2.newTrip("Naxos", SAMPLE_TODAY.minusDays(1), SAMPLE_TODAY.plusDays(3))
        book2.saveItem(book2.draftItem(trip2.id, SAMPLE_TODAY, 1.0).copy(title = "Portara", lat = 37.1111, lng = 25.3721))
        book2.saveItem(book2.draftItem(trip2.id, SAMPLE_TODAY, 2.0).copy(title = "Apollonas", lat = 37.1789, lng = 25.5446))
        val athens = Recorded.web(location = FixedLocation(LatLng(37.9755, 23.7348), accuracyM = 10f))
        ui(book2, ScriptedSpeech(), web = athens, at = arrayOf(Place.Trip(trip2.id, TripTab.Journal))) {
            onNodeWithText("Another place").performClick()
            onNodeWithText("Use current location").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("No reliable match nearby. You can choose a place yourself.").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Apollonas").performClick()
            waitForIdle()
            onNodeWithText("At Apollonas?").assertExists()
        }
        // Shown once counts: the same day isn't asked again on this phone.
        val store = CheckInStore(MemorySettingsFile())
        assertTrue(store.claim(trip2.id, SAMPLE_TODAY))
        assertFalse(store.claim(trip2.id, SAMPLE_TODAY))
        assertTrue(store.claim(trip2.id, SAMPLE_TODAY.plusDays(1)))
        assertNotNull(JournalCheckIns(book2, CheckInStore(MemorySettingsFile())).claim(trip2.id, nowOn(SAMPLE_TODAY)))
    }
}

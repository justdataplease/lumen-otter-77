package com.justdataplease.boringtravel.speech

import com.justdataplease.boringtravel.speech.DictationRecovery.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What dictation does after each recognizer error (owner, 18 Nov 2026: "I am getting dictation stopped, try again"). */
class DictationRecoveryTest {
    // SpeechRecognizer's codes.
    private val networkTimeout = 1
    private val network = 2
    private val audio = 3
    private val server = 4
    private val client = 5
    private val speechTimeout = 6
    private val noMatch = 7
    private val busy = 8
    private val permissions = 9

    private fun after(error: Int, onDevice: Boolean = false, preferOffline: Boolean = true, silences: Int = 0, hiccups: Int = 0) =
        DictationRecovery.after(error, onDevice, preferOffline, silences, hiccups, "Greek")

    @Test fun silenceOnlyListensAgainHoweverLong() {
        assertEquals(Step.Again(150L), after(noMatch))
        assertEquals(Step.Again(1000L), after(speechTimeout, silences = 40))
        assertTrue(DictationRecovery.isSilence(noMatch) && DictationRecovery.isSilence(speechTimeout))
    }

    @Test fun pausesNoLongerUseUpTheHiccups() {
        // Before 18 Nov one counter took both: five pauses and a busy recognizer ended dictation.
        assertTrue(after(busy, silences = 5, hiccups = 0) is Step.Again)
        assertTrue(after(client, silences = 12, hiccups = 1) is Step.Again)
    }

    @Test fun hiccupsInARowStopIt() {
        for (error in listOf(busy, client, server, DictationRecovery.ERROR_SERVER_DISCONNECTED, 10, 14, 99)) {
            assertTrue("$error", after(error, hiccups = 4) is Step.Again)
            assertEquals("$error", Step.Stop(DictationRecovery.STOPPED), after(error, hiccups = DictationRecovery.MAX_HICCUPS))
        }
    }

    @Test fun aHiccupStartsAFreshRecognizerAndWaitsLongerEachTime() {
        assertEquals(Step.Again(500L, fresh = true), after(client))
        assertEquals(Step.Again(1500L, fresh = true), after(client, hiccups = 2))
        assertEquals(Step.Again(2000L, fresh = true), after(server, hiccups = 4))
    }

    @Test fun theOnDeviceRecognizerIsLeftAfterItsSecondHiccup() {
        assertEquals(Step.Again(500L, fresh = true), after(client, onDevice = true))
        assertEquals(Step.Again(300L, fresh = true, leaveOnDevice = true), after(server, onDevice = true, hiccups = 1))
    }

    @Test fun languagesAndNetwork() {
        assertEquals(
            Step.Again(150L, fresh = true, leaveOnDevice = true, fetchLanguage = true),
            after(DictationRecovery.ERROR_LANGUAGE_UNAVAILABLE, onDevice = true),
        )
        assertEquals(Step.Again(150L, fresh = true, online = true), after(DictationRecovery.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(Step.Stop("Greek isn't available for dictation on this phone."), after(DictationRecovery.ERROR_LANGUAGE_NOT_SUPPORTED, preferOffline = false))
        assertEquals(Step.Again(150L, fresh = true, leaveOnDevice = true), after(network, onDevice = true))
        assertEquals(Step.Stop("No internet, and offline dictation isn't ready for Greek."), after(networkTimeout))
    }

    @Test fun microphone() {
        assertEquals(Step.Stop("Allow the microphone to dictate."), after(permissions))
        assertEquals(Step.Again(500L, fresh = true), after(audio))
        assertEquals(Step.Stop("The microphone is busy or not working."), after(audio, hiccups = 2))
    }
}

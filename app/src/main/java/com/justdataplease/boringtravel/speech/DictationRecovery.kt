package com.justdataplease.boringtravel.speech

import android.speech.SpeechRecognizer

/**
 * What [Dictation] does after a recognizer error. Pure (DictationRecoveryTest).
 *
 * Silence ([SpeechRecognizer.ERROR_NO_MATCH], [SpeechRecognizer.ERROR_SPEECH_TIMEOUT]) only listens again. A hiccup
 * (the recognizer busy, a client or server error, a lost service, anything unknown) counts toward [MAX_HICCUPS] in a
 * row, and only words heard reset that count. Before 18 Nov 2026 one counter took both, so a few pauses and one hiccup
 * ended dictation with "Dictation stopped. Try again." (owner, 18 Nov 2026). The phone's on-device recognizer that
 * hiccups twice in a row is left for the regular one.
 */
internal object DictationRecovery {
    sealed interface Step {
        /** Listen again after [delayMs]; [leaveOnDevice]: with the regular recognizer; [online]: no longer offline only. */
        data class Again(
            val delayMs: Long,
            val fresh: Boolean = false,
            val leaveOnDevice: Boolean = false,
            val online: Boolean = false,
            val fetchLanguage: Boolean = false,
        ) : Step

        data class Stop(val message: String) : Step
    }

    const val RESTART_MS = 150L
    const val MAX_HICCUPS = 5
    const val STOPPED = "Dictation stopped. Try again."

    /** API 31+ codes, as literals so older phones never look them up. */
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    /**
     * [silences] and [hiccups]: in a row before this error, this one not counted ([Dictation] counts it after);
     * [onDevice]: the phone's on-device recognizer is listening; [preferOffline]: the regular one was asked to stay
     * offline.
     */
    fun after(error: Int, onDevice: Boolean, preferOffline: Boolean, silences: Int, hiccups: Int, language: String): Step = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            // Silence: listen again, a little slower if the recognizer keeps returning at once.
            Step.Again(if (silences >= 3) 1000L else RESTART_MS)
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> when {
            // The phone's offline recognizer lacks this language: fetch it for next time, use the regular one now.
            onDevice -> Step.Again(RESTART_MS, fresh = true, leaveOnDevice = true, fetchLanguage = error == ERROR_LANGUAGE_UNAVAILABLE)
            // Asked to stay offline, the regular recognizer refuses a language it has no pack for: ask again, online.
            preferOffline -> Step.Again(RESTART_MS, fresh = true, online = true)
            else -> Step.Stop("$language isn't available for dictation on this phone.")
        }
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            if (onDevice) Step.Again(RESTART_MS, fresh = true, leaveOnDevice = true)
            else Step.Stop("No internet, and offline dictation isn't ready for $language.")
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Step.Stop("Allow the microphone to dictate.")
        // The microphone can still be held by the session before: once or twice is a hiccup.
        SpeechRecognizer.ERROR_AUDIO ->
            if (hiccups >= 2) Step.Stop("The microphone is busy or not working.") else Step.Again(500L, fresh = true)
        else -> when {
            hiccups >= MAX_HICCUPS -> Step.Stop(STOPPED)
            onDevice && hiccups >= 1 -> Step.Again(300L, fresh = true, leaveOnDevice = true)
            else -> Step.Again((500L * (hiccups + 1)).coerceAtMost(2000L), fresh = true)
        }
    }

    /** Silence is not a hiccup; everything else that listens again is. */
    fun isSilence(error: Int): Boolean = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
}

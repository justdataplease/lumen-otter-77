package com.justdataplease.boringtravel.speech

/**
 * Android's SpeechRecognizer error codes, which the Android app's DictationRecovery (read in as it is) decides on. This
 * phone's recognizer (ios/Speech.kt) reports its own errors as these codes, so both phones recover the same way.
 */
internal object SpeechRecognizer {
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
}

/** A language's name in the language [display] ("el" in "en" → "Greek"); blank when the phone doesn't know it. */
internal expect fun displayLanguage(tag: String, display: String): String

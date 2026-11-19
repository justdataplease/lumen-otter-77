package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.speech.DictationRecovery
import com.justdataplease.boringtravel.speech.DictationText
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Dictation, as the Android app's speech/Dictation.kt runs it, over the phone's own recognizer ([SpeechEngine]: the
 * Speech framework and AVAudioEngine on iPhone, ios/IosSpeech.kt; none on the desktop; a fake in the tests). The rules
 * are Android's: one utterance at a time, listening again after each pause, the same recovery after each error
 * (DictationRecovery, read in from the Android app), a stop after three minutes of silence, and the same words on
 * screen. Errors come back as Android's recognizer codes, so both phones decide alike.
 */

/** Whether the app may use the microphone and speech recognition. */
enum class MicAccess { Allowed, NotAsked, Refused }

/** What the recognizer tells about one listening session (Android's RecognitionListener), on the main thread. */
interface SpeechListener {
    fun onReady()
    fun onBeginningOfSpeech()

    /** How loud it is now, 0 (quiet) to 1 (speaking). */
    fun onLevel(level: Float)
    fun onEndOfSpeech()
    fun onPartial(text: String)

    /** The utterance's final words (it ended at a pause, or was stopped). */
    fun onResult(text: String)

    /** An error, as one of Android's SpeechRecognizer codes (speech/SpeechErrors.kt). */
    fun onError(code: Int)
}

/** The phone's speech recognizer. */
interface SpeechEngine {
    fun access(): MicAccess

    /** Asks for the microphone and speech recognition now (the system's questions); [done] says whether both were given. */
    fun askAccess(done: (Boolean) -> Unit)

    /** Whether any recognizer can be used on this phone. */
    fun available(): Boolean

    /** Whether the language [tag] can be recognised on the phone itself, without the network. */
    fun onDeviceAvailable(tag: String): Boolean

    /** Starts one session in [tag]; [onDevice]: the phone's own recognizer only. False when it couldn't start. */
    fun start(tag: String, onDevice: Boolean, listener: SpeechListener): Boolean

    /** Ends the session; the words heard so far still arrive as its result. */
    fun stop()

    /** Drops the session at once: nothing more arrives. */
    fun cancel()

    /** Opens the app's page in the phone's Settings (the microphone was refused). */
    fun openSettings()

    /** The phone's language as a tag ("en-GB"), what dictation listens for unless Me › Voice notes says otherwise. */
    fun phoneLanguage(): String
}

/** A phone without a recognizer (the desktop): dictation says it isn't available. */
object NoSpeech : SpeechEngine {
    override fun access() = MicAccess.Allowed
    override fun askAccess(done: (Boolean) -> Unit) = done(true)
    override fun available() = false
    override fun onDeviceAvailable(tag: String) = false
    override fun start(tag: String, onDevice: Boolean, listener: SpeechListener) = false
    override fun stop() = Unit
    override fun cancel() = Unit
    override fun openSettings() = Unit
    override fun phoneLanguage() = "en-US"
}

/** The phone's recognizer, for the editor. */
val LocalSpeech = staticCompositionLocalOf<SpeechEngine> { NoSpeech }

/**
 * Apple's speech errors as Android's recognizer codes (speech/SpeechErrors.kt), so the Android app's recovery decides
 * (iosMain's IosSpeech reads them off the NSError). Null: the session was cancelled on purpose; nothing to tell.
 */
object AppleSpeechErrors {
    fun code(domain: String, code: Long): Int? = when (domain) {
        // The server recognizer: 1110 "No speech detected"; 216 and 301 a task cancelled by the app.
        "kAFAssistantErrorDomain" -> when (code) {
            1110L -> com.justdataplease.boringtravel.speech.SpeechRecognizer.ERROR_NO_MATCH
            216L, 301L -> null
            1101L, 1107L -> com.justdataplease.boringtravel.speech.SpeechRecognizer.ERROR_NETWORK
            else -> com.justdataplease.boringtravel.speech.SpeechRecognizer.ERROR_CLIENT
        }
        // The phone's own recognizer: 301 cancelled; 102 the language's offline model is not on the phone.
        "kLSRErrorDomain" -> when (code) {
            301L -> null
            102L -> DictationRecovery.ERROR_LANGUAGE_UNAVAILABLE
            else -> com.justdataplease.boringtravel.speech.SpeechRecognizer.ERROR_CLIENT
        }
        "NSURLErrorDomain" -> com.justdataplease.boringtravel.speech.SpeechRecognizer.ERROR_NETWORK
        else -> com.justdataplease.boringtravel.speech.SpeechRecognizer.ERROR_CLIENT
    }
}

/**
 * Dictation into a note (the Android app's Dictation): [start] listens in a language until [stop]; partial results and
 * each utterance's final words go to [onText]. [scope] runs the waits between sessions ([wait]); [clock] measures the
 * long pause.
 */
class Dictation(
    private val engine: SpeechEngine,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::nowMs,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    sealed interface State {
        data object Off : State
        data class Listening(val language: String, val hearing: Boolean = false) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Off)
        private set

    /** Voice level 0..1 for the listening bars. */
    var level by mutableStateOf(0f)
        private set

    /** Receives each partial ([final] = false) and final result of an utterance. */
    var onText: (text: String, final: Boolean) -> Unit = { _, _ -> }

    private var onDevice = false
    private var onDeviceRefused = false

    /** False once the recognizer said it has no offline pack for this language: it is asked online then. */
    private var preferOffline = true
    private var active = false
    private var languageTag = ""
    private var languageName = ""
    private var lastSpeechAt = 0L
    private var silences = 0
    private var hiccups = 0
    private var lastPartial = ""
    private var inSession = false
    private var pending: Job? = null

    /** Each session's own listener: a late call from an old session finds it retired and is dropped. */
    private var session: Session? = null

    val isActive: Boolean get() = active

    fun access(): MicAccess = engine.access()

    fun start(tag: String) {
        if (active) return
        if (engine.access() != MicAccess.Allowed) {
            state = State.Failed("Allow the microphone to dictate.")
            return
        }
        if (!engine.available()) {
            state = State.Failed("Dictation isn't available on this phone.")
            return
        }
        pending?.cancel()
        retire()
        engine.cancel()
        inSession = false
        if (tag != languageTag) {
            onDeviceRefused = false
            preferOffline = true
        }
        languageTag = tag
        languageName = DictationText.languageName(tag, engine.phoneLanguage())
        active = true
        silences = 0
        hiccups = 0
        lastPartial = ""
        lastSpeechAt = clock()
        state = State.Listening(languageName)
        listen()
    }

    /** Stops listening; the words of the current utterance still arrive as a final result when the recognizer has them. */
    fun stop() {
        if (!active) return
        active = false
        state = State.Off
        level = 0f
        pending?.cancel()
        engine.stop()
    }

    fun dismissError() {
        if (state is State.Failed) state = State.Off
    }

    /** The screen is gone: nothing more is heard or written. */
    fun release() {
        active = false
        inSession = false
        pending?.cancel()
        retire()
        engine.cancel()
        state = State.Off
        level = 0f
    }

    private fun retire() {
        session?.retired = true
        session = null
    }

    private fun listen() {
        if (!active) return
        val useOnDevice = !onDeviceRefused && engine.onDeviceAvailable(languageTag)
        onDevice = useOnDevice
        retire()
        val s = Session()
        session = s
        inSession = true
        if (!engine.start(languageTag, useOnDevice, s)) fail("Dictation couldn't start. Try again.")
    }

    private fun restart(delayMs: Long) {
        if (!active) return
        if (clock() - lastSpeechAt > LONG_PAUSE_MS) {
            fail("Stopped after a long pause.")
            return
        }
        pending?.cancel()
        pending = scope.launch {
            wait(delayMs)
            listen()
        }
    }

    private fun fail(message: String) {
        active = false
        inSession = false
        pending?.cancel()
        retire()
        engine.cancel()
        level = 0f
        state = State.Failed(message)
    }

    private inner class Session : SpeechListener {
        var retired = false

        override fun onReady() {
            if (!retired && active) state = State.Listening(languageName)
        }

        override fun onBeginningOfSpeech() {
            if (retired) return
            lastSpeechAt = clock()
            if (active) state = State.Listening(languageName, hearing = true)
        }

        override fun onLevel(level: Float) {
            if (!retired) this@Dictation.level = level.coerceIn(0f, 1f)
        }

        override fun onEndOfSpeech() {
            if (!retired && active) state = State.Listening(languageName)
        }

        override fun onPartial(text: String) {
            if (retired || !inSession) return
            if (text.isNotBlank()) {
                lastSpeechAt = clock()
                lastPartial = text
                silences = 0
                hiccups = 0
            }
            onText(text, false)
        }

        override fun onResult(text: String) {
            if (retired || !inSession) return
            inSession = false
            val words = text.ifBlank { lastPartial }
            lastPartial = ""
            if (words.isNotBlank()) {
                lastSpeechAt = clock()
                silences = 0
                hiccups = 0
            }
            onText(words, true)
            if (active) restart(DictationRecovery.RESTART_MS)
        }

        override fun onError(code: Int) {
            if (retired) return
            level = 0f
            if (!inSession) return
            inSession = false
            onText(lastPartial, true)
            lastPartial = ""
            if (!active) return
            val step = DictationRecovery.after(code, onDevice, preferOffline, silences, hiccups, languageName)
            if (DictationRecovery.isSilence(code)) silences++ else hiccups++
            when (step) {
                is DictationRecovery.Step.Stop -> fail(step.message)
                is DictationRecovery.Step.Again -> {
                    if (step.leaveOnDevice) onDeviceRefused = true
                    if (step.online) preferOffline = false
                    if (step.fresh) engine.cancel()
                    restart(step.delayMs)
                }
            }
        }
    }

    companion object {
        /** Three minutes without words stop it ("Stopped after a long pause."). */
        const val LONG_PAUSE_MS = 3 * 60_000L
    }
}

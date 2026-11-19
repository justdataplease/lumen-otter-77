package com.justdataplease.boringtravel.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.justdataplease.boringtravel.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Dictation with Android's own speech recognizer. Prefers the on-device recognizer (free, works offline on most
 * phones), else the default one with "prefer offline", and online when the phone has no offline pack for the language
 * (Greek has none: owner, 2026-11-14, "greek, french, spanish, german should work"). Partial results stream through
 * [onText]; after each pause the recognizer starts again until [stop]. All calls on the main thread.
 */
class Dictation(context: Context) {
    sealed interface State {
        data object Off : State
        data class Listening(val language: String, val hearing: Boolean = false) : State
        data class Failed(val message: String) : State
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Voice level 0..1 for the listening animation. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Receives each partial ([final] = false) and final result of an utterance. */
    var onText: (text: String, final: Boolean) -> Unit = { _, _ -> }

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var onDeviceRefused = false
    /** False once the default recognizer said it has no offline pack for this language: it is asked online then. */
    private var preferOffline = true
    private var active = false
    private var languageTag = ""
    private var languageName = ""
    private var lastSpeechAt = 0L
    /** Silences and recognizer hiccups in a row ([DictationRecovery]); words heard start both again. */
    private var silences = 0
    private var hiccups = 0
    /** Words of the current utterance so far; kept when the recognizer ends it without a final result. */
    private var lastPartial = ""

    /** True from startListening until its first result or error; later callbacks (some recognizers repeat them) are ignored. */
    private var inSession = false

    val isActive: Boolean get() = active

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(tag: String) {
        if (active) return
        if (!hasPermission()) {
            _state.value = State.Failed("Allow the microphone to dictate.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(app) && !onDeviceAvailable()) {
            _state.value = State.Failed("Dictation isn't available on this phone.")
            return
        }
        // A recognizer still finishing a stopped session would deliver its words twice; drop it.
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        inSession = false
        if (tag != languageTag) {
            // Another language: the phone's offline recognizers may have this one.
            onDeviceRefused = false
            preferOffline = true
        }
        languageTag = tag
        languageName = DictationText.languageName(tag, Locale.getDefault())
        active = true
        silences = 0
        hiccups = 0
        lastPartial = ""
        lastSpeechAt = SystemClock.elapsedRealtime()
        _state.value = State.Listening(languageName)
        listen()
    }

    /** Stops listening; the words of the current utterance still arrive as a final result when the recognizer has them. */
    fun stop() {
        if (!active) return
        active = false
        _state.value = State.Off
        _level.value = 0f
        main.removeCallbacksAndMessages(null)
        recognizer?.stopListening()
        // Give the recognizer a moment to deliver the last words, then let it go.
        main.postDelayed({ if (!active) destroyRecognizer() }, 1500)
    }

    fun dismissError() {
        if (_state.value is State.Failed) _state.value = State.Off
    }

    fun release() {
        active = false
        inSession = false
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        _state.value = State.Off
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        val useOnDevice = !onDeviceRefused && onDeviceAvailable()
        val r = when {
            useOnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            SpeechRecognizer.isRecognitionAvailable(app) -> SpeechRecognizer.createSpeechRecognizer(app)
            else -> null
        } ?: return null
        onDevice = useOnDevice
        r.setRecognitionListener(listener)
        recognizer = r
        return r
    }

    private fun destroyRecognizer() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        // Hints only; recognizers may ignore them. Longer pauses before an utterance ends.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        // Punctuation and capitals from the on-device recognizer where it supports them.
        if (onDevice && Build.VERSION.SDK_INT >= 33) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
    }

    private fun listen() {
        if (!active) return
        val r = ensureRecognizer()
        if (r == null) {
            fail("Dictation isn't available on this phone.")
            return
        }
        inSession = true
        runCatching { r.startListening(intent()) }.onFailure { fail("Dictation couldn't start. Try again.") }
    }

    private fun restart(delayMs: Long) {
        if (!active) return
        if (SystemClock.elapsedRealtime() - lastSpeechAt > LONG_PAUSE_MS) {
            fail("Stopped after a long pause.")
            return
        }
        main.postDelayed({ listen() }, delayMs)
    }

    private fun fail(message: String) {
        active = false
        inSession = false
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        _level.value = 0f
        _state.value = State.Failed(message)
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (active) _state.value = State.Listening(languageName)
        }

        override fun onBeginningOfSpeech() {
            lastSpeechAt = SystemClock.elapsedRealtime()
            if (active) _state.value = State.Listening(languageName, hearing = true)
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Roughly −2 dB (quiet) … 10 dB (speaking) on most recognizers.
            _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (active) _state.value = State.Listening(languageName)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!inSession) return
            val text = firstResult(partialResults) ?: return
            if (text.isNotBlank()) {
                lastSpeechAt = SystemClock.elapsedRealtime()
                lastPartial = text
                silences = 0
                hiccups = 0
            }
            onText(text, false)
        }

        override fun onResults(results: Bundle?) {
            if (!inSession) return
            inSession = false
            val text = firstResult(results).orEmpty().ifBlank { lastPartial }
            lastPartial = ""
            if (text.isNotBlank()) {
                lastSpeechAt = SystemClock.elapsedRealtime()
                silences = 0
                hiccups = 0
            }
            onText(text, true)
            if (active) restart(RESTART_MS) else destroyRecognizer()
        }

        /** Ends the utterance with the words heard so far (or none). */
        private fun commitPartial() {
            onText(lastPartial, true)
            lastPartial = ""
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            _level.value = 0f
            if (!inSession) {
                if (!active) destroyRecognizer()
                return
            }
            inSession = false
            commitPartial()
            if (!active) {
                destroyRecognizer()
                return
            }
            // Codes only, for a report of "Dictation stopped": no words.
            if (BuildConfig.DEBUG) Log.w("BoringTravelDictation", "error $error onDevice=$onDevice silences=$silences hiccups=$hiccups")
            val step = DictationRecovery.after(error, onDevice, preferOffline, silences, hiccups, languageName)
            if (DictationRecovery.isSilence(error)) silences++ else hiccups++
            when (step) {
                is DictationRecovery.Step.Stop -> fail(step.message)
                is DictationRecovery.Step.Again -> {
                    if (step.fetchLanguage && Build.VERSION.SDK_INT >= 33) runCatching { recognizer?.triggerModelDownload(intent()) }
                    if (step.leaveOnDevice) onDeviceRefused = true
                    if (step.online) preferOffline = false
                    if (step.fresh) destroyRecognizer()
                    restart(step.delayMs)
                }
            }
        }
    }

    private companion object {
        const val RESTART_MS = DictationRecovery.RESTART_MS
        const val LONG_PAUSE_MS = 3 * 60_000L
    }
}

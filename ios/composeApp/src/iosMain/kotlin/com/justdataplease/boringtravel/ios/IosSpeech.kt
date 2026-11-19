@file:OptIn(ExperimentalForeignApi::class)

package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.speech.DictationRecovery
import com.justdataplease.boringtravel.speech.SpeechRecognizer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.log10
import kotlin.math.sqrt

/*
 * Dictation's recognizer on iPhone: the Speech framework (SFSpeechRecognizer, one SFSpeechAudioBufferRecognitionRequest
 * per utterance, punctuation added) fed by AVAudioEngine's microphone input, with AVAudioSession set to record. Like
 * Android's recognizer, an utterance ends at a pause (2.5 s without new words) and dictation (ios/Dictation.kt) starts
 * the next; no words for 8 s is Android's "speech timeout". Apple's errors are reported as Android's codes
 * (AppleSpeechErrors). Everything is handed to the main thread.
 */

/** This iPhone's speech recognizer. */
class IosSpeech : SpeechEngine {
    private val engine = AVAudioEngine()
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var listener: SpeechListener? = null
    private var timer: Job? = null
    private var heard = false
    private var lastText = ""
    private var tapped = false

    override fun access(): MicAccess {
        val speech = SFSpeechRecognizer.authorizationStatus()
        val mic = AVAudioSession.sharedInstance().recordPermission
        return when {
            speech == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusDenied ||
                speech == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusRestricted ||
                mic == AVAudioSessionRecordPermissionDenied -> MicAccess.Refused
            speech == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized &&
                mic == AVAudioSessionRecordPermissionGranted -> MicAccess.Allowed
            else -> MicAccess.NotAsked
        }
    }

    override fun askAccess(done: (Boolean) -> Unit) {
        SFSpeechRecognizer.requestAuthorization { status ->
            if (status != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                onMain { done(false) }
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission { granted -> onMain { done(granted) } }
            }
        }
    }

    override fun available(): Boolean = SFSpeechRecognizer.supportedLocales().isNotEmpty()

    override fun onDeviceAvailable(tag: String): Boolean = recognizer(tag)?.supportsOnDeviceRecognition ?: false

    override fun phoneLanguage(): String = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en-US"

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    override fun start(tag: String, onDevice: Boolean, listener: SpeechListener): Boolean {
        cancel()
        this.listener = listener
        val recognizer = recognizer(tag)
        if (recognizer == null) {
            post { it.onError(DictationRecovery.ERROR_LANGUAGE_NOT_SUPPORTED) }
            return true
        }
        if (!recognizer.available) {
            post { it.onError(SpeechRecognizer.ERROR_NETWORK) }
            return true
        }
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryRecord, mode = AVAudioSessionModeMeasurement, options = AVAudioSessionCategoryOptionDuckOthers, error = null)
        session.setActive(true, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        val req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.requiresOnDeviceRecognition = onDevice
        req.addsPunctuation = true
        request = req
        val input = engine.inputNode
        val format = input.outputFormatForBus(0u)
        input.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
            if (buffer == null) return@installTapOnBus
            req.appendAudioPCMBuffer(buffer)
            val samples = buffer.floatChannelData?.get(0) ?: return@installTapOnBus
            val n = buffer.frameLength.toInt()
            if (n <= 0) return@installTapOnBus
            var sum = 0.0
            for (i in 0 until n) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            val db = 20 * log10(sqrt(sum / n).coerceAtLeast(1e-6))
            val level = ((db + 50) / 40).toFloat()
            onMain { this.listener?.onLevel(level) }
        }
        tapped = true
        engine.prepare()
        if (!engine.startAndReturnError(null)) {
            stopAudio()
            post { it.onError(SpeechRecognizer.ERROR_AUDIO) }
            return true
        }
        heard = false
        lastText = ""
        task = recognizer.recognitionTaskWithRequest(req) { result, error ->
            val text = result?.bestTranscription?.formattedString
            val final = result?.final == true
            val domain = error?.domain
            val code = error?.code
            onMain {
                val l = this.listener ?: return@onMain
                when {
                    text != null && final -> {
                        finish()
                        l.onResult(text)
                    }
                    text != null -> {
                        if (!heard) {
                            heard = true
                            l.onBeginningOfSpeech()
                        }
                        lastText = text
                        l.onPartial(text)
                        // A pause ends the utterance, as Android's recognizer does.
                        waitThen(PAUSE_MS) { request?.endAudio() }
                    }
                    domain != null && code != null -> {
                        val mapped = AppleSpeechErrors.code(domain, code) ?: return@onMain
                        finish()
                        l.onError(mapped)
                    }
                }
            }
        }
        listener.onReady()
        // Nothing heard at all: Android's speech timeout.
        waitThen(NO_SPEECH_MS) {
            if (!heard) {
                val l = this.listener
                cancel()
                l?.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            }
        }
        return true
    }

    override fun stop() {
        timer?.cancel()
        request?.endAudio()
        stopAudio()
        // The final words arrive through the task; a recognizer that never answers is let go.
        waitThen(STOP_GRACE_MS) {
            val l = this.listener
            if (task != null && l != null) {
                val words = lastText
                cancel()
                l.onResult(words)
            }
        }
    }

    override fun cancel() {
        timer?.cancel()
        listener = null
        task?.cancel()
        task = null
        request?.endAudio()
        request = null
        stopAudio()
    }

    private fun finish() {
        timer?.cancel()
        task = null
        request = null
        stopAudio()
        listener = null
    }

    private fun stopAudio() {
        if (engine.running) engine.stop()
        if (tapped) {
            engine.inputNode.removeTapOnBus(0u)
            tapped = false
        }
        AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
    }

    private fun waitThen(ms: Long, block: () -> Unit) {
        timer?.cancel()
        timer = main.launch {
            delay(ms)
            block()
        }
    }

    /** [block] with the current listener, on the main thread, after this call returns (as a recognizer calls back). */
    private fun post(block: (SpeechListener) -> Unit) {
        val l = listener ?: return
        onMain { if (listener === l) block(l) }
    }

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { block() }

    /** The recognizer for [tag] when the phone has one for it ("el-GR", "el_GR" or just "el"). */
    private fun recognizer(tag: String): SFSpeechRecognizer? {
        val wanted = tag.replace('_', '-').lowercase()
        val locales = SFSpeechRecognizer.supportedLocales().filterIsInstance<NSLocale>()
        val match = locales.firstOrNull { it.localeIdentifier.replace('_', '-').lowercase() == wanted }
            ?: locales.firstOrNull { it.localeIdentifier.replace('_', '-').lowercase().substringBefore('-') == wanted.substringBefore('-') }
            ?: return null
        return SFSpeechRecognizer(locale = match)
    }

    private companion object {
        /** No new words this long ends an utterance (Android's complete-silence hint). */
        const val PAUSE_MS = 2_500L

        /** No words at all this long: listen again (Android's speech timeout). */
        const val NO_SPEECH_MS = 8_000L

        /** After Stop, the last words are waited for this long (as Android lets its recognizer finish). */
        const val STOP_GRACE_MS = 1_500L
    }
}

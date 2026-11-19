package com.justdataplease.boringtravel.ios

/**
 * A recognizer driven by hand, for the tests and the screenshots (the desktop has no speech service): [say] streams
 * words as the phone's recognizer would, [pause] ends an utterance, [error] reports one of Android's codes. It records
 * what was asked of it. [grant]: the answer to the microphone question.
 */
class ScriptedSpeech(
    var access: MicAccess = MicAccess.Allowed,
    private val offline: Boolean = true,
    private val language: String = "en-US",
    var grant: Boolean = true,
    /** Words heard as soon as a session starts, and an error ending it (the screenshots' states). */
    private val hearAtStart: String? = null,
    private val failAtStart: Int? = null,
) : SpeechEngine {
    var listener: SpeechListener? = null
        private set

    /** Each session started: its language and whether it was on the phone only. */
    val sessions = mutableListOf<Pair<String, Boolean>>()
    var asked = 0
        private set
    var settingsOpened = 0
        private set
    private var lastPartial = ""

    override fun access(): MicAccess = access

    override fun askAccess(done: (Boolean) -> Unit) {
        asked++
        access = if (grant) MicAccess.Allowed else MicAccess.Refused
        done(grant)
    }

    override fun available(): Boolean = true
    override fun onDeviceAvailable(tag: String): Boolean = offline
    override fun phoneLanguage(): String = language

    override fun start(tag: String, onDevice: Boolean, listener: SpeechListener): Boolean {
        sessions += tag to onDevice
        this.listener = listener
        lastPartial = ""
        listener.onReady()
        hearAtStart?.let { say(it) }
        failAtStart?.let { error(it) }
        return true
    }

    override fun stop() {
        val l = listener
        listener = null
        l?.onResult(lastPartial)
    }

    override fun cancel() {
        listener = null
    }

    override fun openSettings() {
        settingsOpened++
    }

    /** Words heard so far in this utterance (a partial result), with the voice's level. */
    fun say(words: String, level: Float = 0.7f) {
        val l = listener ?: return
        if (lastPartial.isEmpty()) l.onBeginningOfSpeech()
        lastPartial = words
        l.onLevel(level)
        l.onPartial(words)
    }

    /** A pause: the utterance ends with [words]. */
    fun pause(words: String = lastPartial) {
        val l = listener ?: return
        listener = null
        lastPartial = ""
        l.onResult(words)
    }

    /** An error, as one of Android's recognizer codes. */
    fun error(code: Int) {
        val l = listener ?: return
        listener = null
        l.onError(code)
    }
}

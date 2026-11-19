package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.compat.SystemClock
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.MicRecording
import com.justdataplease.boringtravel.ios.Microphone

/**
 * Records one voice line for the trip clip (the Android app's clip/ClipVoice.kt, written again over the phone's
 * [Microphone]: AVAudioRecorder on iPhone, MediaRecorder on Android): AAC in an MPEG-4 file, 44.1 kHz mono at 96 kbps,
 * into clip-voice/<trip>/ ([ClipMedia.voiceDir]) as "<uuid>.m4a". The caller has the microphone (the screen asks for it
 * before [start]); a line stops by itself at [VoiceLine.MAX_MS] (the studio's timer calls [stop]).
 */
class VoiceRecorder(private val files: MediaFiles, private val tripId: String, private val mic: Microphone) {
    private var recorder: MicRecording? = null
    private var file: String? = null
    private var startedAt = 0L

    val recording: Boolean get() = recorder != null

    /** Starts a new line; false when the microphone couldn't be opened. */
    fun start(): Boolean {
        if (recorder != null) return true
        val name = "${newId()}.m4a"
        val target = ClipMedia.voiceDir(tripId) + "/" + name
        // Its folder made (the recorder writes into a file whose folder is there), the recording over it.
        if (!files.write(target, ByteArray(0))) return false
        val r = runCatching { mic.record(files.absolute(target), VoiceLine.MAX_MS + 500) }.getOrNull()
        if (r == null) {
            files.delete(target)
            return false
        }
        recorder = r
        file = target
        startedAt = SystemClock.elapsedRealtime()
        return true
    }

    /** Stops and keeps the line: its file name and length, or null when it was too short or failed. */
    fun stop(): Pair<String, Long>? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        val ms = SystemClock.elapsedRealtime() - startedAt
        val ok = runCatching { r.stop() }.getOrDefault(false)
        if (f == null) return null
        if (!ok || ms < VoiceLine.MIN_MS || (files.size(f) ?: 0L) == 0L) {
            files.delete(f)
            return null
        }
        return f.substringAfterLast('/') to ms.coerceAtMost(VoiceLine.MAX_MS)
    }

    /** Stops and throws the line away. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.cancel() }
        file?.let { files.delete(it) }
        file = null
    }
}

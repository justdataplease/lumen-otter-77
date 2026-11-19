package com.justdataplease.boringtravel.clip

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Records one voice line for the trip clip (owner: "Voice-over"): AAC in an MPEG-4 file, 44.1 kHz mono at 96 kbps,
 * into files/clip-voice/<trip>/ ([ClipMedia.voiceDir]). The caller holds RECORD_AUDIO (the screen asks for it before
 * [start]); a line stops by itself at [VoiceLine.MAX_MS] (the view-model's timer calls [stop]).
 */
class VoiceRecorder(private val context: Context, private val tripId: String) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val recording: Boolean get() = recorder != null

    /** Starts a new line; false when the microphone couldn't be opened. */
    fun start(): Boolean {
        if (recorder != null) return true
        val dir = ClipMedia.voiceDir(context, tripId)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val target = File(dir, "${UUID.randomUUID()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(96_000)
            r.setMaxDuration(VoiceLine.MAX_MS.toInt() + 500)
            r.setOutputFile(target.path)
            r.prepare()
            r.start()
            recorder = r
            file = target
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            Log.w("TripClip", "Couldn't start the microphone", e)
            runCatching { r.release() }
            target.delete()
            false
        }
    }

    /** Stops and keeps the line: its file name and length, or null when it was too short or failed. */
    fun stop(): Pair<String, Long>? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        val ms = SystemClock.elapsedRealtime() - startedAt
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (f == null) return null
        if (!ok || ms < VoiceLine.MIN_MS || !f.isFile || f.length() == 0L) {
            f.delete()
            return null
        }
        return f.name to ms.coerceAtMost(VoiceLine.MAX_MS)
    }

    /** Stops and throws the line away. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }
}

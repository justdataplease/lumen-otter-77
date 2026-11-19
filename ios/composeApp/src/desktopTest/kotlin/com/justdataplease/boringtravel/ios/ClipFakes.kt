package com.justdataplease.boringtravel.ios

/*
 * The phone as the trip video's editor sees it, for the tests (round 19): music that records what it was asked, a
 * microphone that asks and records into the app's files in memory, and a picker that hands over a phone video. No
 * sound device, no microphone, no library: everything is a record of calls.
 */

/** The bundled tracks, recorded: each opened track and what was done to it. */
class FakeSounds : ClipSounds {
    val players = mutableListOf<FakeTrack>()

    /** Every call on every track, in order ("open afro_groove.m4a", "play 32000", "seek 33000", "pause", "release"). */
    val events = mutableListOf<String>()

    override fun open(uri: String, onEnd: () -> Unit): TrackPlayer {
        events += "open " + uri.substringAfterLast('/')
        return FakeTrack(uri, events).also { players += it }
    }
}

class FakeTrack(val uri: String, private val events: MutableList<String>) : TrackPlayer {
    var released = false
        private set
    override var positionMs: Int = 0
    override var isPlaying: Boolean = false
        private set

    override fun play(fromMs: Int) {
        events += "play $fromMs"
        positionMs = fromMs
        isPlaying = true
    }

    override fun seekTo(ms: Int) {
        events += "seek $ms"
        positionMs = ms
    }

    override fun pause() {
        events += "pause"
        isPlaying = false
    }

    override fun release() {
        events += "release"
        isPlaying = false
        released = true
    }
}

/**
 * A microphone that has [access] until asked, answers [answer] once asked (counting [asked]), and records a few bytes
 * into [files] (the app's files in memory) when stopped.
 */
class FakeMicrophone(private val files: MemoryMediaFiles, override var access: MicAccess, private val answer: Boolean = true) : Microphone {
    var asked = 0
        private set
    val recorded = mutableListOf<String>()

    override fun ask(done: (Boolean) -> Unit) {
        asked++
        access = if (answer) MicAccess.Allowed else MicAccess.Refused
        done(answer)
    }

    override fun record(absolutePath: String, maxMs: Long): MicRecording {
        val path = absolutePath.removePrefix("memory:/")
        recorded += path
        return object : MicRecording {
            override fun stop(): Boolean = files.write(path, "AAC voice line".encodeToByteArray())
            override fun cancel() = files.delete(path)
        }
    }
}

/** The library's picker handing over one phone video (a QuickTime file of [bytes] bytes) each time it is opened. */
class FakeVideoPicker(private val files: MediaFiles, private val bytes: Int = 32 * 1024) : MediaPicker {
    var opened = 0
        private set

    override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) = pickVideos(max, done)

    override fun pickVideos(max: Int, done: (List<PickedMedia>) -> Unit) {
        opened++
        val path = "picked/phone-video-$opened.mov"
        files.write(path, ByteArray(bytes) { (it % 199).toByte() })
        done(listOf(PickedMedia(path, "video/quicktime", 1080, 1920, durationMs = 8_000)))
    }

    override val hasCamera: Boolean = false
    override fun takePhoto(done: (PickedMedia?) -> Unit) = done(null)
    override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
}

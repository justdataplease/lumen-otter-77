package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.music.Pcm
import com.justdataplease.boringtravel.clip.music.SoundDecoder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The export's phone side for the tests (round 20): a sound decoder that makes tones instead of reading files, a share
 * that records what it was asked, and the phone's time in the background counted. No sound device, no files read.
 */

/**
 * Decodes every file as a tone: the bundled music (an address with "music/") as a 440 Hz stereo tone at 44.1 kHz,
 * anything else (a voice line, a video's sound) as an 880 Hz mono tone at 22.05 kHz, [amplitude] loud, as long as
 * asked (the music [musicSeconds] long at most, so a short track loops). Records each call.
 */
class ToneDecoder(
    private val amplitude: Int = 8_000, private val musicSeconds: Double = 600.0, private val failMusic: Boolean = false,
    private val voiceAmplitude: Int = amplitude,
) : SoundDecoder {
    val calls = mutableListOf<String>()

    override fun decode(location: String, fromUs: Long, lengthUs: Long, stillWanted: () -> Unit): Pcm? {
        stillWanted()
        synchronized(calls) { calls += "${location.substringAfterLast('/')} from $fromUs for $lengthUs" }
        val music = "music/" in location
        if (music && failMusic) return null
        val rate = if (music) 44_100 else 22_050
        val channels = if (music) 2 else 1
        val available = if (music) (musicSeconds * 1_000_000).toLong() - fromUs else lengthUs
        val frames = (minOf(lengthUs, available).coerceAtLeast(0) * rate / 1_000_000).toInt()
        if (frames <= 0) return null
        val hz = if (music) 440.0 else 880.0
        val samples = ShortArray(frames * channels)
        for (f in 0 until frames) {
            val v = ((if (music) amplitude else voiceAmplitude) * sin(2 * PI * hz * f / rate)).roundToInt().toShort()
            for (c in 0 until channels) samples[f * channels + c] = v
        }
        return Pcm(samples, frames, rate, channels)
    }
}

/** A share sheet, Photos and apps that record what they were asked; [installed] says which apps are on the phone. */
class RecordingShare(private val installed: Set<com.justdataplease.boringtravel.clip.ClipTarget> = emptySet(), private val photosAllowed: Boolean = true) : VideoShare {
    val shared = mutableListOf<String>()
    val savedToPhotos = mutableListOf<String>()
    val sent = mutableListOf<Pair<com.justdataplease.boringtravel.clip.ClipTarget, String>>()

    override fun share(path: String): Boolean {
        shared += path
        return true
    }

    override fun saveToPhotos(path: String, done: (Boolean) -> Unit) {
        if (photosAllowed) savedToPhotos += path
        done(photosAllowed)
    }

    override fun installed(target: com.justdataplease.boringtravel.clip.ClipTarget): Boolean = target in installed

    override fun sendTo(target: com.justdataplease.boringtravel.clip.ClipTarget, path: String): Boolean {
        if (target !in installed) return false
        sent += target to path
        return true
    }
}

/**
 * The phone's time as iOS 26's continued processing gives it (the second parity round): the work may go on away from
 * the screen ([BackgroundTicket.keepsGoingAway]); every progress handed over is kept in [progress].
 */
class ContinuedBackgroundWork : BackgroundWork {
    val progress = mutableListOf<Float>()
    var names = mutableListOf<String>()

    override fun beginBackgroundWork(name: String): BackgroundTicket {
        synchronized(this) { names += name }
        return object : BackgroundTicket {
            override fun endBackgroundWork() {}

            override fun progressBackgroundWork(done: Float) {
                synchronized(progress) { progress += done }
            }

            override val keepsGoingAway: Boolean get() = true
        }
    }
}

/** The phone's time in the background, counted: each ask and each end. */
class CountingBackgroundWork : BackgroundWork {
    var begun = 0
        private set
    var ended = 0
        private set

    override fun beginBackgroundWork(name: String): BackgroundTicket {
        synchronized(this) { begun++ }
        var done = false
        return BackgroundTicket {
            synchronized(this) {
                if (!done) {
                    done = true
                    ended++
                }
            }
        }
    }
}

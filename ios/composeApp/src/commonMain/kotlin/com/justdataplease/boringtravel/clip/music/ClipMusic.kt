package com.justdataplease.boringtravel.clip.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/*
 * The Android app's clip/music/ClipMusic.kt written again (round 20): the same sounds, gains, fades and steps, with
 * the sums of the Android app's own MusicMix (read in at build time). Android decodes with MediaExtractor and
 * MediaCodec and encodes AAC with MediaCodec; here the phone decodes ([SoundDecoder]: AVAssetReader on iPhone) and the
 * phone's writer encodes the mixed samples to AAC at [ClipMusic.BIT_RATE] as it writes the video (AVAssetWriter's
 * audio input: ios/VideoWriter.kt). Everything between, the mix itself, is this file: plain Kotlin, tested on Windows.
 */

/** Decoded sound: [frames] frames of 16-bit samples, [channels] interleaved, at [sampleRate]. */
class Pcm(val samples: ShortArray, val frames: Int, val sampleRate: Int, val channels: Int)

/** The phone's sound decoder (iPhone: AVAssetReader to 16-bit PCM; the desktop: a silent stand-in; tests: fakes). */
fun interface SoundDecoder {
    /**
     * The sound at [location] (an absolute file path, or an address such as the bundled music's) decoded to 16-bit PCM
     * from [fromUs] for at most [lengthUs], or null when it has no sound. [stillWanted] throws to stop (cancelling).
     */
    fun decode(location: String, fromUs: Long, lengthUs: Long, stillWanted: () -> Unit): Pcm?

    companion object {
        /** No sound on this phone: every file reads as silent (the chosen music then fails, as on Android). */
        val NONE = SoundDecoder { _, _, _, _ -> null }
    }
}

/**
 * A clip's whole sound, ready to be encoded: [totalFrames] frames at [sampleRate] with [channels] interleaved, made a
 * piece at a time by [render] (the music over its plan, ducked under the voices, the other sounds laid over it: Android's
 * MusicMix.mix). The music's samples are kept decoded (about 10 MB a minute in stereo); the rest is worked out as asked.
 */
class MixedSound internal constructor(
    val sampleRate: Int,
    val channels: Int,
    val totalFrames: Long,
    private val pcm: ShortArray?,
    private val plan: MusicMix.Plan?,
    private val overlays: List<MusicMix.Overlay>,
    private val duck: MusicMix.Duck?,
) {
    /** Output frames `[from, from + count)` into [out] (interleaved, at least `count × channels` long). */
    fun render(from: Long, count: Int, out: ShortArray) = MusicMix.mix(pcm, channels, plan, overlays, from, count, out, duck)

    /** How long it plays, in microseconds. */
    val durationUs: Long get() = totalFrames * 1_000_000L / sampleRate
}

/** The clip's sound: the chosen music from its start, faded, looped when short, with the voice lines and videos' sounds. */
object ClipMusic {
    /** The AAC the writer makes of it (Android's BIT_RATE). */
    const val BIT_RATE = 128_000

    /** Without music, the sounds are mixed at this rate in stereo. */
    const val PLAIN_RATE = 44_100

    /**
     * A sound laid over the clip: [location]'s sound from [fromUs] for [lengthUs], placed [atUs] into the clip,
     * × [gain], faded in and out over [fadeUs]; [duck]: the music dips under it (a voice line).
     */
    data class Sound(
        val location: String, val atUs: Long, val fromUs: Long, val lengthUs: Long, val gain: Float, val fadeUs: Long = 150_000L,
        val duck: Boolean = false,
    )

    /** A voice line on top: full level, short ramps, the music ducked under it. */
    fun voice(location: String, atUs: Long, lengthUs: Long) = Sound(location, atUs, 0L, lengthUs, VOICE_GAIN, fadeUs = 20_000L, duck = true)

    const val VOICE_GAIN = 1.0f

    /** A phone video's sound kept under the music: quietly; a little louder with no music. */
    const val VIDEO_GAIN_UNDER_MUSIC = 0.45f
    const val VIDEO_GAIN_ALONE = 0.9f

    /** Where in [track] a clip's music starts, [startS] seconds in as the editor chose it (kept within the track); 0 without one. */
    fun startUs(track: MusicTrack?, startS: Double): Long = track?.let { (MusicCatalog.startOf(it, startS) * 1_000_000).toLong() } ?: 0L

    /**
     * The sound for a clip of [durationUs], ready for the writer: [track] (decoded from [trackLocation]) from [startUs]
     * (it loops back there), cut or looped to the length and faded, with [sounds] laid over it. Null when there is
     * nothing to add (no track and no sounds). Throws when the music can't be read (the video is then silent, as on
     * Android); a sound that can't be read is left out. [progress] gets 0.15 once the song is decoded and 0.25 with the
     * sounds (Android's steps; its encoding's share is the writer's here). Cancelling the caller stops it.
     */
    suspend fun prepare(
        decoder: SoundDecoder, durationUs: Long, track: MusicTrack?, trackLocation: String?, startUs: Long, sounds: List<Sound>,
        progress: (Float) -> Unit = {},
    ): MixedSound? {
        if (track == null && sounds.isEmpty()) return null
        check(durationUs > 0) { "The clip has no length" }
        return withContext(Dispatchers.Default) {
            val wanted = { ensureActive() }
            mix(decoder, durationUs, track, trackLocation, startUs, sounds, progress, wanted)
        }
    }

    /** [prepare]'s work, on the caller's thread ([stillWanted] throws to stop). */
    fun mix(
        decoder: SoundDecoder, durationUs: Long, track: MusicTrack?, trackLocation: String?, startUs: Long, sounds: List<Sound>,
        progress: (Float) -> Unit, stillWanted: () -> Unit,
    ): MixedSound {
        val pcm = track?.let { t ->
            decoder.decode(requireNotNull(trackLocation) { "${t.asset} has no file" }, startUs, durationUs, stillWanted)
                ?: error("${t.asset} has no audio")
        }
        progress(0.15f)
        val rate = pcm?.sampleRate ?: PLAIN_RATE
        val channels = pcm?.channels ?: 2
        val plan = pcm?.let { MusicMix.plan(durationUs, it.frames.toLong(), it.sampleRate, midSong = startUs > 0) }
        val total = plan?.totalFrames ?: MusicMix.frames(durationUs, rate)
        val ducked = ArrayList<LongRange>()
        val overlays = sounds.mapNotNull { s ->
            val decoded = try {
                decoder.decode(s.location, s.fromUs, s.lengthUs, stillWanted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A sound that can't be read is left out (Android logs it and goes on).
                null
            } ?: return@mapNotNull null
            val converted = MusicMix.convert(decoded.samples, decoded.frames, decoded.sampleRate, decoded.channels, rate, channels)
            val o = MusicMix.Overlay(converted, (converted.size / channels).toLong(), MusicMix.frames(s.atUs, rate), s.gain, MusicMix.frames(s.fadeUs, rate))
            if (s.duck) ducked += o.at until o.at + o.frames
            o
        }
        progress(0.25f)
        return MixedSound(rate, channels, total, pcm?.samples, plan, overlays, MusicMix.Duck.of(ducked, rate))
    }
}

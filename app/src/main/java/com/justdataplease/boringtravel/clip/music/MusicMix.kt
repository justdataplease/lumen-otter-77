package com.justdataplease.boringtravel.clip.music

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The sums behind a clip's music, apart from Android so they can be tested. A "frame" is one sample per channel.
 *
 * The track plays from its chosen start for the whole video (the caller decodes it from there). A video longer than
 * the rest of the track loops it: each further pass starts [CROSSFADE_SECONDS] before the previous one ends, the two
 * crossfading (equal power). Over all of it the video's envelope fades in for [FADE_IN_SECONDS] ([FADE_IN_MID_SECONDS]
 * when it comes in mid-song) and out over the last [FADE_OUT_SECONDS].
 */
object MusicMix {
    const val FADE_IN_SECONDS = 0.3
    /** A song started mid-way comes in a little softer, not with a click. */
    const val FADE_IN_MID_SECONDS = 0.8
    const val FADE_OUT_SECONDS = 1.8
    const val CROSSFADE_SECONDS = 1.5

    /** Frames covering [durationUs] at [sampleRate], to the nearest frame. */
    fun frames(durationUs: Long, sampleRate: Int): Long = (durationUs * sampleRate + 500_000) / 1_000_000

    fun frames(seconds: Double, sampleRate: Int): Long = (seconds * sampleRate).roundToLong()

    /** When output frame [frame] plays, in microseconds from the start. */
    fun presentationTimeUs(frame: Long, sampleRate: Int): Long = frame * 1_000_000 / sampleRate

    /**
     * How many of the encoded sound's samples, starting at [timesUs] (in order), go under a video of [videoDurationUs]:
     * those that start before it ends. The sound is made for the planned length while the frames render; a video a
     * frame shorter than planned must not end with a tail of sound after its last picture.
     */
    fun samplesWithin(timesUs: List<Long>, videoDurationUs: Long): Int {
        val after = timesUs.indexOfFirst { it >= videoDurationUs }
        return if (after < 0) timesUs.size else after
    }

    /** The loop's overlap for a track of [trackFrames]: the crossfade, but at most a quarter of the track. */
    fun overlap(trackFrames: Long, crossfadeFrames: Long): Long = min(crossfadeFrames, trackFrames / 4).coerceAtLeast(0)

    /**
     * Where each pass of the track starts in the output. One pass (at 0) when the track covers the video;
     * otherwise every `trackFrames − overlapFrames`, as many as start before the end.
     */
    fun passStarts(totalFrames: Long, trackFrames: Long, overlapFrames: Long): List<Long> {
        require(trackFrames > 0) { "The track is empty" }
        require(overlapFrames in 0 until trackFrames) { "The overlap must be shorter than the track" }
        if (totalFrames <= 0) return emptyList()
        val step = trackFrames - overlapFrames
        return generateSequence(0L) { it + step }.takeWhile { it < totalFrames }.toList()
    }

    /**
     * The video's fade at [frame] of [totalFrames]: 0 on the first frame, up to 1 after [fadeInFrames], 1 in the
     * middle, down to 0 on the last frame over [fadeOutFrames]. Short videos get the product of both ramps.
     */
    fun envelope(frame: Long, totalFrames: Long, fadeInFrames: Long, fadeOutFrames: Long): Float {
        if (frame < 0 || frame >= totalFrames) return 0f
        val fadeIn = if (fadeInFrames <= 0) 1.0 else min(1.0, frame.toDouble() / fadeInFrames)
        val left = totalFrames - 1 - frame
        val fadeOut = if (fadeOutFrames <= 0) 1.0 else min(1.0, left.toDouble() / fadeOutFrames)
        return (fadeIn * fadeOut).toFloat()
    }

    /**
     * A pass's own gain at its frame [i]: a pass after the first rises over the overlap ([fadesIn]); a pass another
     * follows falls over its last [overlapFrames] ([fadesOut]). Sine and cosine, so the power stays even.
     */
    fun passGain(i: Long, trackFrames: Long, overlapFrames: Long, fadesIn: Boolean, fadesOut: Boolean): Float {
        if (i < 0 || i >= trackFrames) return 0f
        var g = 1.0
        if (overlapFrames > 0) {
            if (fadesIn && i < overlapFrames) g *= sin(PI / 2 * i / overlapFrames)
            val fromEnd = trackFrames - i
            if (fadesOut && fromEnd <= overlapFrames) g *= cos(PI / 2 * (overlapFrames - fromEnd) / overlapFrames)
        }
        return g.toFloat()
    }

    /** Everything one render needs, in frames at the track's sample rate. */
    data class Plan(
        val totalFrames: Long,
        val trackFrames: Long,
        val overlapFrames: Long,
        val fadeInFrames: Long,
        val fadeOutFrames: Long,
    ) {
        val starts: List<Long> = passStarts(totalFrames, trackFrames, overlapFrames)
        val step: Long get() = trackFrames - overlapFrames
    }

    /**
     * The plan for a video of [videoDurationUs] over a decoded track of [trackFrames] at [sampleRate] ([midSong]: decoded
     * from a start after the song's own beginning).
     */
    fun plan(videoDurationUs: Long, trackFrames: Long, sampleRate: Int, midSong: Boolean = false): Plan {
        val total = frames(videoDurationUs, sampleRate)
        val overlap = if (trackFrames >= total) 0 else overlap(trackFrames, frames(CROSSFADE_SECONDS, sampleRate))
        val fadeIn = if (midSong) FADE_IN_MID_SECONDS else FADE_IN_SECONDS
        return Plan(total, trackFrames, overlap, frames(fadeIn, sampleRate), frames(FADE_OUT_SECONDS, sampleRate))
    }

    /**
     * A sound laid over the music (a phone video's own sound): [pcm] interleaved at the mix's rate and channels,
     * [frames] long, from output frame [at], × [gain], ramped in and out over [fadeFrames].
     */
    class Overlay(val pcm: ShortArray, val frames: Long, val at: Long, val gain: Float, val fadeFrames: Long)

    /** An overlay's gain at its own frame [i] of [frames]: [gain], ramped over [fadeFrames] at both ends. */
    fun overlayGain(i: Long, frames: Long, fadeFrames: Long, gain: Float): Float {
        if (i < 0 || i >= frames) return 0f
        if (fadeFrames <= 0) return gain
        val up = min(1.0, (i + 1).toDouble() / fadeFrames)
        val down = min(1.0, (frames - i).toDouble() / fadeFrames)
        return (gain * min(up, down)).toFloat()
    }

    /** How far the music dips under a voice (about −10.5 dB): it stays there but the voice is clearly on top. */
    const val DUCK_GAIN = 0.3f
    /** The dip starts this long before the voice, takes [DUCK_DOWN_SECONDS] to go down and [DUCK_UP_SECONDS] to come back. */
    const val DUCK_LEAD_SECONDS = 0.1
    const val DUCK_DOWN_SECONDS = 0.25
    const val DUCK_UP_SECONDS = 0.6

    /**
     * When the music ducks: under each voice in [voices] (output frames `[from, to)`), from [lead] frames before it,
     * ramping down over [down] frames and back up over [up] after it ends.
     */
    class Duck(val voices: List<LongRange>, val lead: Long, val down: Long, val up: Long, val gain: Float = DUCK_GAIN) {
        companion object {
            /** For voices at [spans] (frames at [sampleRate]), with the usual timings. */
            fun of(spans: List<LongRange>, sampleRate: Int): Duck? = if (spans.isEmpty()) null else
                Duck(spans, frames(DUCK_LEAD_SECONDS, sampleRate), frames(DUCK_DOWN_SECONDS, sampleRate), frames(DUCK_UP_SECONDS, sampleRate))
        }
    }

    /** The music's gain at output frame [f] under [duck]: 1, [Duck.gain] under a voice, straight ramps between. */
    fun duckGain(f: Long, duck: Duck?): Float {
        if (duck == null) return 1f
        var g = 1.0
        for (v in duck.voices) {
            val full = v.first - duck.lead
            val end = v.last + 1
            val one = when {
                f >= full && f < end -> 0.0
                f < full && f >= full - duck.down -> (full - f).toDouble() / duck.down.coerceAtLeast(1)
                f >= end && f < end + duck.up -> (f - end + 1).toDouble() / duck.up.coerceAtLeast(1)
                else -> 1.0
            }
            g = min(g, duck.gain + (1 - duck.gain) * one)
        }
        return g.toFloat()
    }

    /**
     * Output frames `[from, from + count)` of the whole sound into [out]: the music ([pcm] over its [plan], see
     * [render]; null: silence), ducked under the voices ([duck]), with the [overlays] (voices, videos' sounds) added,
     * clipped to 16 bits.
     */
    fun mix(pcm: ShortArray?, channels: Int, plan: Plan?, overlays: List<Overlay>, from: Long, count: Int, out: ShortArray, duck: Duck? = null) {
        if (pcm != null && plan != null) render(pcm, channels, plan, from, count, out) else out.fill(0, 0, count * channels)
        if (duck != null && pcm != null) {
            for (n in 0 until count) {
                val g = duckGain(from + n, duck)
                if (g >= 1f) continue
                for (c in 0 until channels) out[n * channels + c] = (out[n * channels + c] * g).toInt().toShort()
            }
        }
        for (o in overlays) {
            val lo = max(from, o.at)
            val hi = min(from + count, o.at + o.frames)
            for (f in lo until hi) {
                val i = f - o.at
                val g = overlayGain(i, o.frames, o.fadeFrames, o.gain)
                if (g == 0f) continue
                val n = (f - from).toInt()
                for (c in 0 until channels) {
                    val v = out[n * channels + c] + o.pcm[(i * channels + c).toInt()] * g
                    out[n * channels + c] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
        }
    }

    /**
     * [frames] frames of [pcm] (interleaved, [rate] Hz, [channels]) at [outRate] with [outChannels]: linear between
     * samples, a mono sound on both sides, more channels than wanted averaged down.
     */
    fun convert(pcm: ShortArray, frames: Int, rate: Int, channels: Int, outRate: Int, outChannels: Int): ShortArray {
        if (frames <= 0 || channels <= 0) return ShortArray(0)
        val outFrames = ((frames.toLong() * outRate + rate / 2) / rate).toInt().coerceAtLeast(1)
        val out = ShortArray(outFrames * outChannels)
        fun sample(f: Int, c: Int): Float {
            val ff = f.coerceIn(0, frames - 1)
            return if (channels == outChannels) pcm[ff * channels + c].toFloat()
            else if (channels == 1) pcm[ff].toFloat()
            else if (outChannels == 1) (0 until channels).sumOf { pcm[ff * channels + it].toInt() }.toFloat() / channels
            else pcm[ff * channels + min(c, channels - 1)].toFloat()
        }
        for (n in 0 until outFrames) {
            val x = n.toDouble() * rate / outRate
            val i = x.toInt()
            val t = (x - i).toFloat()
            for (c in 0 until outChannels) {
                val v = sample(i, c) * (1 - t) + sample(i + 1, c) * t
                out[n * outChannels + c] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return out
    }

    /**
     * Writes output frames `[from, from + count)` into [out] (interleaved, [channels] a frame) from the track [pcm]
     * (interleaved the same way): the passes at their own gains, times the envelope, clipped to 16 bits.
     */
    fun render(pcm: ShortArray, channels: Int, plan: Plan, from: Long, count: Int, out: ShortArray) {
        require(out.size >= count * channels) { "The output buffer is too small" }
        val passes = plan.starts.size
        val step = plan.step
        for (n in 0 until count) {
            val f = from + n
            val env = envelope(f, plan.totalFrames, plan.fadeInFrames, plan.fadeOutFrames)
            // Frame f is in pass k (and, inside the overlap, in the end of pass k − 1 too).
            val k = (f / step).toInt().coerceAtMost(passes - 1)
            val i = f - k * step
            val gain = if (k < 0 || i >= plan.trackFrames) 0f
            else passGain(i, plan.trackFrames, plan.overlapFrames, fadesIn = k > 0, fadesOut = k < passes - 1)
            val prevI = i + step
            val prevGain = if (k >= 1 && prevI < plan.trackFrames) {
                passGain(prevI, plan.trackFrames, plan.overlapFrames, fadesIn = k - 1 > 0, fadesOut = true)
            } else 0f
            for (c in 0 until channels) {
                var v = 0f
                if (gain != 0f) v += pcm[(i * channels + c).toInt()] * gain
                if (prevGain != 0f) v += pcm[(prevI * channels + c).toInt()] * prevGain
                out[n * channels + c] = (v * env).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }
}

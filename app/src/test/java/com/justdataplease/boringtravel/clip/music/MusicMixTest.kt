package com.justdataplease.boringtravel.clip.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicMixTest {
    private val rate = 44_100

    @Test fun frameCountsAndTimesFollowTheSampleRate() {
        assertEquals(44_100L, MusicMix.frames(1_000_000L, rate))
        assertEquals(2_646_000L, MusicMix.frames(60_000_000L, rate))
        // A 30 fps clip of 451 frames lasts 15.033… s.
        assertEquals(662_970L, MusicMix.frames(451 * 1_000_000L / 30, rate))
        assertEquals(13_230L, MusicMix.frames(MusicMix.FADE_IN_SECONDS, rate))
        assertEquals(0L, MusicMix.presentationTimeUs(0, rate))
        assertEquals(1_000_000L, MusicMix.presentationTimeUs(44_100, rate))
        assertEquals(23_219L, MusicMix.presentationTimeUs(1_024, rate))
        // Times never run backwards and stay within a sample of the exact value.
        var last = -1L
        for (f in 0L..100_000L step 997) {
            val t = MusicMix.presentationTimeUs(f, rate)
            assertTrue(t > last)
            assertTrue(Math.abs(t - f * 1e6 / rate) < 1.0)
            last = t
        }
    }

    @Test fun envelopeFadesInAtTheStartHoldsAndFadesOutToSilence() {
        val total = MusicMix.frames(20_000_000L, rate)
        val fadeIn = MusicMix.frames(MusicMix.FADE_IN_SECONDS, rate)
        val fadeOut = MusicMix.frames(MusicMix.FADE_OUT_SECONDS, rate)
        fun env(f: Long) = MusicMix.envelope(f, total, fadeIn, fadeOut)
        // Start: silent, half way through the fade, full after it.
        assertEquals(0f, env(0), 0f)
        assertEquals(0.5f, env(fadeIn / 2), 0.001f)
        assertEquals(1f, env(fadeIn), 0f)
        // Middle.
        assertEquals(1f, env(total / 2), 0f)
        // End: full until the fade-out, half way, then silent on the last frame.
        assertEquals(1f, env(total - 1 - fadeOut), 0f)
        assertEquals(0.5f, env(total - 1 - fadeOut / 2), 0.001f)
        assertEquals(0f, env(total - 1), 0f)
        assertEquals(0f, env(total), 0f)
        // Never rises during the fade-out.
        var previous = 1f
        for (f in total - 1 - fadeOut until total) {
            val g = env(f)
            assertTrue(g <= previous)
            previous = g
        }
    }

    @Test fun envelopeOfAVeryShortVideoStaysWithinRange() {
        val total = MusicMix.frames(1_000_000L, rate)
        val fadeIn = MusicMix.frames(MusicMix.FADE_IN_SECONDS, rate)
        val fadeOut = MusicMix.frames(MusicMix.FADE_OUT_SECONDS, rate)
        for (f in 0 until total step 101) {
            val g = MusicMix.envelope(f, total, fadeIn, fadeOut)
            assertTrue(g in 0f..1f)
        }
        assertEquals(0f, MusicMix.envelope(0, total, fadeIn, fadeOut), 0f)
        assertEquals(0f, MusicMix.envelope(total - 1, total, fadeIn, fadeOut), 0f)
    }

    @Test fun aTrackLongerThanTheVideoPlaysOnceAndIsCut() {
        val track = MusicMix.frames(120_000_000L, rate)
        val plan = MusicMix.plan(45_000_000L, track, rate)
        assertEquals(MusicMix.frames(45_000_000L, rate), plan.totalFrames)
        assertEquals(0L, plan.overlapFrames)
        assertEquals(listOf(0L), plan.starts)
        // Exactly as long: still one pass.
        assertEquals(listOf(0L), MusicMix.plan(120_000_000L, track, rate).starts)
    }

    @Test fun aVideoLongerThanTheTrackLoopsItWithOverlaps() {
        val track = MusicMix.frames(100_000_000L, rate)
        val video = 250_000_000L
        val plan = MusicMix.plan(video, track, rate)
        val overlap = MusicMix.frames(MusicMix.CROSSFADE_SECONDS, rate)
        assertEquals(overlap, plan.overlapFrames)
        val step = track - overlap
        assertEquals(listOf(0L, step, 2 * step), plan.starts)
        // The passes cover the whole video and none starts after its end.
        assertTrue(plan.starts.last() + track >= plan.totalFrames)
        assertTrue(plan.starts.all { it < plan.totalFrames })
        // Just over one track: a second pass starts one crossfade before the first ends.
        assertEquals(listOf(0L, step), MusicMix.plan(101_000_000L, track, rate).starts)
    }

    @Test fun overlapIsAtMostAQuarterOfAShortTrack() {
        assertEquals(100L, MusicMix.overlap(400, 66_150))
        assertEquals(66_150L, MusicMix.overlap(4_410_000, 66_150))
        assertEquals(listOf(0L, 300L, 600L, 900L), MusicMix.passStarts(1_000, 400, 100))
        assertEquals(emptyList<Long>(), MusicMix.passStarts(0, 400, 100))
    }

    @Test fun loopCrossfadeKeepsThePowerEven() {
        val track = 10_000L
        val overlap = 1_000L
        val step = track - overlap
        // The first pass starts at full level; the last one ends at full level.
        assertEquals(1f, MusicMix.passGain(0, track, overlap, fadesIn = false, fadesOut = true), 0f)
        assertEquals(1f, MusicMix.passGain(track - 1, track, overlap, fadesIn = true, fadesOut = false), 0f)
        assertEquals(0f, MusicMix.passGain(0, track, overlap, fadesIn = true, fadesOut = false), 0f)
        assertEquals(1f, MusicMix.passGain(track / 2, track, overlap, fadesIn = true, fadesOut = true), 0f)
        for (i in step until track) {
            val out = MusicMix.passGain(i, track, overlap, fadesIn = false, fadesOut = true)
            val incoming = MusicMix.passGain(i - step, track, overlap, fadesIn = true, fadesOut = true)
            assertEquals(1.0, (out * out + incoming * incoming).toDouble(), 1e-5)
        }
    }

    @Test fun renderLaysThePassesOutInPlace() {
        // A ramp track (stereo: left = i, right = −i) makes every sample's source frame visible.
        val trackFrames = 4_000
        val channels = 2
        val pcm = ShortArray(trackFrames * channels) { if (it % 2 == 0) (it / 2).toShort() else (-(it / 2)).toShort() }
        val plan = MusicMix.Plan(totalFrames = 10_000, trackFrames = 4_000, overlapFrames = 400, fadeInFrames = 0, fadeOutFrames = 0)
        assertEquals(listOf(0L, 3_600L, 7_200L), plan.starts)
        val out = ShortArray(plan.totalFrames.toInt() * channels)
        MusicMix.render(pcm, channels, plan, 0, plan.totalFrames.toInt(), out)
        // The first pass as it is, before the overlap.
        assertEquals(1_234, out[1_234 * 2].toInt())
        assertEquals(-1_234, out[1_234 * 2 + 1].toInt())
        // The second pass alone after the overlap: output frame 5 000 is its frame 1 400.
        assertEquals(1_400, out[5_000 * 2].toInt())
        // Inside the overlap both play: frame 3 800 mixes the first pass's 3 800 and the second's 200, equal power.
        val x = 200.0 / 400
        val expected = 3_800 * Math.cos(Math.PI / 2 * x) + 200 * Math.sin(Math.PI / 2 * x)
        assertEquals(expected, out[3_800 * 2].toDouble(), 1.5)
        // The last pass runs to the end without fading out: output 9 999 is its frame 2 799.
        assertEquals(2_799, out[9_999 * 2].toInt())
    }

    @Test fun renderInChunksMatchesOneGo() {
        val channels = 2
        val pcm = ShortArray(3_000 * channels) { ((it * 37) % 20_000 - 10_000).toShort() }
        val plan = MusicMix.plan(200_000L, 3_000, rate) // 8 820 frames: loops
        val whole = ShortArray(plan.totalFrames.toInt() * channels)
        MusicMix.render(pcm, channels, plan, 0, plan.totalFrames.toInt(), whole)
        val chunked = ShortArray(whole.size)
        val chunk = ShortArray(1_024 * channels)
        var f = 0L
        while (f < plan.totalFrames) {
            val n = minOf(1_024L, plan.totalFrames - f).toInt()
            MusicMix.render(pcm, channels, plan, f, n, chunk)
            System.arraycopy(chunk, 0, chunked, (f * channels).toInt(), n * channels)
            f += n
        }
        assertTrue(whole.contentEquals(chunked))
        // Fades: silent first and last frames.
        assertEquals(0, whole[0].toInt())
        assertEquals(0, whole[whole.size - 1].toInt())
    }

    @Test fun soundMadeForThePlannedLengthLeavesNoTailAfterTheVideo() {
        // AAC frames of 1 024 samples at 44.1 kHz for a clip planned at 451 frames (15.033 s).
        val planned = 451 * 1_000_000L / 30
        val times = (0 until 700).map { MusicMix.presentationTimeUs(it * 1_024L, rate) }.filter { it < planned + 30_000 }
        // The video came out as planned: everything that starts before its end goes in.
        val all = MusicMix.samplesWithin(times, planned)
        assertEquals(times.count { it < planned }, all)
        assertTrue(times[all - 1] < planned)
        // One frame shorter than planned: the sound stops with the picture, nothing starts after it.
        val shorter = planned - 1_000_000L / 30
        val kept = MusicMix.samplesWithin(times, shorter)
        assertTrue(kept < all)
        assertTrue(times.take(kept).all { it < shorter })
        assertTrue(times.drop(kept).all { it >= shorter })
        // Longer than the sound: all of it.
        assertEquals(times.size, MusicMix.samplesWithin(times, planned * 2))
        assertEquals(0, MusicMix.samplesWithin(emptyList(), planned))
    }

    @Test fun renderClipsInsteadOfWrapping() {
        val pcm = ShortArray(2_000) { Short.MAX_VALUE }
        val plan = MusicMix.Plan(totalFrames = 3_000, trackFrames = 2_000, overlapFrames = 500, fadeInFrames = 0, fadeOutFrames = 0)
        val out = ShortArray(3_000)
        MusicMix.render(pcm, 1, plan, 0, 3_000, out)
        assertTrue(out.all { it > 0 })
        assertEquals(Short.MAX_VALUE, out[1_750])
    }
}

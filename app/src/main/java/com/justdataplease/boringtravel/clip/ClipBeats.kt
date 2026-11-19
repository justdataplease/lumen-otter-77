package com.justdataplease.boringtravel.clip

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A track's beat grid, measured offline from the bundled file (`tools/music_beats.py`): its tempo and where its first
 * beat falls. The grid holds through the track (checked at its start, middle and end).
 */
data class BeatGrid(val bpm: Double, val firstBeatS: Double) {
    val periodS: Double get() = 60.0 / bpm
}

/**
 * The beats a clip hears (owner: "Cuts on the beat"): the track's [grid] from the clip's first frame, the track
 * playing from [startS] into it and looping back there like the music does
 * ([com.justdataplease.boringtravel.clip.music.MusicMix]: a clip longer than the rest of the [trackS]-long track
 * starts it again [CROSSFADE_S] before it ends; each pass's beats count until the middle of the crossfade).
 */
data class ClipBeats(val grid: BeatGrid, val trackS: Double, val startS: Double = 0.0) {
    /** Beat times in seconds from 0 to [untilS]. */
    fun times(untilS: Double): DoubleArray {
        val period = grid.periodS
        val length = trackS - startS
        if (period <= 0 || length <= 0) return DoubleArray(0)
        val overlap = min(CROSSFADE_S, length / 4)
        val step = length - overlap
        // The first beat after the start: the grid's phase moved by the start. A start on a beat (MusicCatalog.onBar)
        // can come out a hair under a whole period after rounding: that beat is the one at 0, or the bars shift by one.
        val phase = ((grid.firstBeatS - startS) % period + period) % period
        val first = if (period - phase < PHASE_EPS_S) 0.0 else phase
        val out = ArrayList<Double>()
        var pass = 0
        while (pass * step < untilS) {
            val start = pass * step
            val from = if (pass == 0) 0.0 else start + overlap / 2
            val to = start + step + overlap / 2
            var t = start + first
            while (t < to && t <= untilS) {
                if (t >= from) out += t
                t += period
            }
            pass++
        }
        return out.toDoubleArray()
    }

    /** Beat times in frames at [fps] up to [untilFrame]. */
    fun frames(fps: Int, untilFrame: Int): DoubleArray = times(untilFrame.toDouble() / fps).map { it * fps }.toDoubleArray()

    companion object {
        /** As [com.justdataplease.boringtravel.clip.music.MusicMix.CROSSFADE_SECONDS]. */
        const val CROSSFADE_S = 1.5
        /** A phase this close under a whole period is a beat at 0 (floating-point rounding of a start on a beat). */
        private const val PHASE_EPS_S = 1e-6
    }
}

/**
 * Cuts on the beat: moving a clip's scene changes onto the music's beats without changing its length by more than a
 * beat. The clip is an opening, then stretches of the moving journey ("runs") between stops ([Cut]: close-up waits,
 * photo pauses, phone videos, day cards, or just a day change); this moves each stop's start to a beat near where it
 * was and makes each of its parts whole beats long, so every part starts and ends on one, and the runs take up the
 * difference (the journey a touch faster or slower between stops). The last run takes what is left, so the journey
 * ends where it did.
 */
object BeatFit {
    /** How a stop's part may change: to the nearest beat count, only longer (close-ups: never faster zooms), only shorter (videos). */
    enum class Round { Nearest, Up, Down }

    /** A part of a stop: [frames] long, rounded as [round]. */
    class Part(val frames: Int, val round: Round)

    /** A stop after moving frame [afterK] (−1: before the first) made of [parts] (none: just a day change). */
    class Cut(val afterK: Int, val parts: List<Part>)

    /** The new layout: the opening's length, each cut's parts in frames ([parts], as given), one run length per cut and one after the last ([runs]). */
    class Fit(val drawStart: Int, val parts: List<IntArray>, val runs: IntArray)

    /**
     * Fits [cuts] (in order of [Cut.afterK]) over [moving] moving frames after a [drawStart]-frame opening to the
     * [beats] (output frames, ascending). The opening ends on the beat nearest its end (at least [minIntro]).
     */
    fun fit(drawStart: Int, moving: Int, cuts: List<Cut>, beats: DoubleArray, minIntro: Int): Fit? {
        if (beats.size < 2 || moving <= 0) return null
        val period = (beats.last() - beats.first()) / (beats.size - 1)
        if (period <= 1.0) return null
        fun frame(n: Int) = beats[n.coerceIn(0, beats.lastIndex)].roundToInt()
        fun nearest(t: Double, from: Int = 0): Int {
            var lo = from
            var hi = beats.lastIndex
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (beats[mid] < t) lo = mid + 1 else hi = mid
            }
            return if (lo > from && abs(beats[lo - 1] - t) <= abs(beats[lo] - t)) lo - 1 else lo
        }
        fun beatsFor(p: Part): Int {
            val b = p.frames / period
            return when (p.round) {
                Round.Nearest -> b.roundToInt()
                Round.Up -> ceil(b - 1e-6).toInt()
                Round.Down -> floor(b + 1e-6).toInt()
            }.coerceAtLeast(1)
        }

        // The opening ends on a beat.
        var n0 = nearest(drawStart.toDouble())
        while (n0 < beats.lastIndex && frame(n0) < minIntro) n0++
        val newStart = frame(n0)
        val counts = cuts.map { c -> IntArray(c.parts.size) { beatsFor(c.parts[it]) } }
        val oldStops = cuts.sumOf { c -> c.parts.sumOf { it.frames } }
        val newStops = counts.sumOf { it.sum() } * period
        val drawEnd = drawStart + moving + oldStops
        // The moving frames that keep the end where it was, spread over the journey as before.
        val movingNew = (drawEnd - newStart - newStops).coerceAtLeast(moving * 0.5)
        val scale = movingNew / moving

        val parts = ArrayList<IntArray>(cuts.size)
        val runs = IntArray(cuts.size + 1)
        var end = newStart
        var prevK = -1
        var stopsBefore = 0.0
        for ((b, cut) in cuts.withIndex()) {
            val run = cut.afterK - prevK
            val minRun = if (run <= 0) 0 else max(1, ceil(run * 0.5).toInt())
            val maxRun = if (run <= 0) 0 else (run * 2.0 + period).toInt()
            val target = newStart + (cut.afterK + 1) * scale + stopsBefore
            // The beat nearest where the stop would be, keeping the run before it between half and twice its length
            // (the journey neither stops nor races); a stop only a few frames after the last waits for the next beat
            // (the journey slows for that moment rather than jumping).
            var n = nearest(target)
            while (n < beats.lastIndex && frame(n) - end < minRun) n++
            while (n > 0 && frame(n) - end > maxRun && frame(n - 1) - end >= minRun) n--
            if (run == 0) n = nearest(end.toDouble())
            val start = if (run == 0) end else frame(n)
            runs[b] = start - end
            val out = IntArray(cut.parts.size)
            var at = n
            var f = start
            for (i in cut.parts.indices) {
                at += counts[b][i]
                out[i] = max(1, frame(at) - f)
                f += out[i]
            }
            end = f
            parts += out
            stopsBefore += out.sum()
            prevK = cut.afterK
        }
        val lastRun = moving - 1 - prevK
        runs[cuts.size] = if (lastRun <= 0) 0 else max(max(1, ceil(lastRun * 0.5).toInt()), drawEnd - end)
        return Fit(newStart, parts, runs)
    }

    /** The beat nearest [f] within [lo]..[hi] (frames), or [f] when none is. */
    fun nearestWithin(f: Int, beats: DoubleArray, lo: Int, hi: Int): Int {
        var best = f
        var bestD = Double.MAX_VALUE
        for (b in beats) {
            val r = b.roundToInt()
            if (r < lo || r > hi) continue
            val d = abs(b - f)
            if (d < bestD) { bestD = d; best = r }
        }
        return best
    }
}

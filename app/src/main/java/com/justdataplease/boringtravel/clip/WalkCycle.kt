package com.justdataplease.boringtravel.clip

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The traveller's steps through a clip, worked out once from the head's path so every frame (preview workers, export)
 * agrees: the legs keep pace with the ground passing under the feet (two steps per [STRIDE_FIGURES] figure heights of
 * ground, as a person walks), within a cadence that never blurs or crawls ([MIN_HZ]–[MAX_HZ] cycles a second), and
 * come to rest when the head stands still (photo pauses, close-ups, videos, the opening and the end). Pure.
 */
class WalkCycle private constructor(private val phases: FloatArray, private val swings: FloatArray) {
    val frames: Int get() = phases.size

    /** Whole cycles walked by frame [f] (a cycle is two steps). */
    fun phase(f: Int): Float = if (phases.isEmpty()) 0f else phases[f.coerceIn(0, phases.size - 1)]

    /** How much the limbs swing at frame [f]: 0 standing, 1 walking. */
    fun swing(f: Int): Float = if (swings.isEmpty()) 0f else swings[f.coerceIn(0, swings.size - 1)]

    /** The limbs' pose at frame [f], −1..1: what the paper figure's bones are scaled by. */
    fun stride(f: Int): Float = (sin(phase(f) * 2.0 * PI) * swing(f)).toFloat()

    companion object {
        /** The figure's height in design units (the clip draws the traveller 62 u tall with its margins). */
        const val FIGURE_U = 51.0

        /** Ground covered by one cycle (two steps), in figure heights. */
        const val STRIDE_FIGURES = 0.85

        /** The slowest and fastest walking cadence, in cycles a second. */
        const val MIN_HZ = 0.75
        const val MAX_HZ = 1.6

        /** Ground moving slower than this (design units a second) counts as standing still. */
        const val STILL_U_PER_S = 1.5

        /** How long the legs take to start or stop (seconds), and how quickly the pace follows the ground. */
        const val EASE_S = 0.45
        const val PACE_S = 0.2

        /** Cycles a second for ground passing at [figuresPerSecond] figure heights a second (0 when standing). */
        fun cadence(figuresPerSecond: Double): Double {
            if (!figuresPerSecond.isFinite() || figuresPerSecond * FIGURE_U < STILL_U_PER_S) return 0.0
            return (figuresPerSecond / STRIDE_FIGURES).coerceIn(MIN_HZ, MAX_HZ)
        }

        /** No walking: the figure stands. */
        val STILL = WalkCycle(FloatArray(0), FloatArray(0))

        /**
         * The steps for a head at ([xs], [ys]) world units in each frame, with the camera [spans] world units wide,
         * in a frame [aspect] wide to high at [fps]. Ground speed is measured in design units (the short side is 360),
         * the units the figure is drawn in.
         */
        fun of(xs: DoubleArray, ys: DoubleArray, spans: DoubleArray, aspect: Double, fps: Int): WalkCycle {
            val n = minOf(xs.size, ys.size, spans.size)
            if (n == 0 || fps <= 0) return STILL
            // A frame [aspect] wide shows spanX across 360 × max(1, aspect) design units.
            val across = 360.0 * max(1.0, if (aspect.isFinite() && aspect > 0) aspect else 1.0)
            val dt = 1.0 / fps
            val pace = 1 - exp(-dt / PACE_S)
            val ease = dt / EASE_S
            val phases = FloatArray(n)
            val swings = FloatArray(n)
            var phase = 0.0
            var hz = 0.0
            var swing = 0.0
            for (f in 0 until n) {
                // The ground's speed under the head: this frame's move, or the next one's at the first frame.
                val a = if (f == 0) 0 else f - 1
                val b = if (f == 0) minOf(1, n - 1) else f
                val span = spans[f].takeIf { it.isFinite() && it > 0 } ?: Double.MAX_VALUE
                val moved = hypot(xs[b] - xs[a], ys[b] - ys[a]).takeIf { it.isFinite() } ?: 0.0
                val unitsPerSecond = moved / span * across * fps
                val target = cadence(unitsPerSecond / FIGURE_U)
                hz += (target - hz) * pace
                swing = if (target > 0) minOf(1.0, swing + ease) else max(0.0, swing - ease)
                if (f > 0) phase += hz * dt
                phases[f] = phase.toFloat()
                swings[f] = swing.toFloat()
            }
            return WalkCycle(phases, swings)
        }
    }
}

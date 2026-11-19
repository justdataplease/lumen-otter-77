package com.justdataplease.boringtravel.clip

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How long a clip runs (owner, 2026-11-08: "make sure speeds are well defined, x1 seems huge", "keep the defaults
 * strava like"). A [ClipLength] sets the whole clip (opening, journey, photo pauses, inserts and the end) and the
 * journey's speed follows from it; Auto is 25 s, or 45 s for a trip over 10 days or a wide clip. Without one (null:
 * "Pace") the trip's travel time sets the length at a chosen speed, as before:
 *
 * Every leg gets screen time for its travel time (the plan's estimate for its vehicle, or a recording's moving time),
 * long legs counting less past [COMPRESS_FROM_MIN]. At ×1 the drawing lasts [SECONDS_PER_SQRT_HOUR] × √(travel
 * hours), at most [MAX_DRAW_S]: a weekend with about 5 h of travel runs ≈ 22 s, a month in Argentina (≈ 58 h) ≈ 65 s.
 * Faster speeds divide it. The opening and the end card keep their own short lengths ([introSeconds], [outroSeconds]:
 * at most [INTRO_S] and [OUTRO_S]), so the title and the totals stay readable at ×10; the whole clip is never under
 * [MIN_SECONDS] nor over [MAX_SECONDS]. Photo pauses (the journey stands still while a photo shows) add their seconds
 * on top ([heldPhotos]).
 */
object ClipTiming {
    val SPEEDS = listOf(1, 2, 5, 10)
    const val SECONDS_PER_SQRT_HOUR = 8.0
    const val COMPRESS_FROM_MIN = 240.0
    const val MIN_SECONDS = 6.0
    const val MAX_SECONDS = 120.0
    /** The title over the moving map lasts at most this long (the camera's dive ends by [ClipPlan.DIVE_S]). */
    const val INTRO_S = 1.5
    /** The pull-back and the end card together last at most this long. */
    const val OUTRO_S = 2.5
    /** The longest ×1 drawing: with the longest opening and close, the clip ends at [MAX_SECONDS]. */
    const val MAX_DRAW_S = MAX_SECONDS - INTRO_S - OUTRO_S
    /** Free clips run up to a minute (as the 60 s length did); longer ones are Pro. */
    const val FREE_MAX_SECONDS = 60.0

    /** Screen-time weight of [minutes] of travel: the time itself, growing only slowly past four hours. */
    fun effectiveMinutes(minutes: Double): Double =
        if (minutes <= COMPRESS_FROM_MIN) minutes else COMPRESS_FROM_MIN * (1 + ln(minutes / COMPRESS_FROM_MIN))

    /** Seconds of drawing at [speed] for [travelHours] (effective hours, see [effectiveMinutes]). */
    fun drawSeconds(travelHours: Double, speed: Int): Double =
        min(SECONDS_PER_SQRT_HOUR * sqrt(travelHours.coerceAtLeast(0.0)), MAX_DRAW_S) / speed.coerceAtLeast(1)

    /** The opening (the title over the moving map, the camera already diving), for a clip of [seconds]: 1–1.5 s. */
    fun introSeconds(seconds: Double): Double = (seconds * 0.1).coerceIn(1.0, INTRO_S)

    /** The pull-back and the end card, for a clip of [seconds]: 2–2.5 s. */
    fun outroSeconds(seconds: Double): Double = (seconds * 0.1).coerceIn(2.0, OUTRO_S)

    /**
     * Where a clip of [frames] frames ends with music: on the bar line (every [BAR_BEATS]th beat of [beats], output
     * frames from the clip's start) nearest it, so the clip ends on a downbeat; [frames] when there are too few beats.
     * None past [most] (a length within the free minute must not snap past it): then the nearest one before it.
     */
    fun onBar(frames: Int, beats: DoubleArray, most: Int = Int.MAX_VALUE): Int {
        var best = frames
        var bestD = Double.MAX_VALUE
        var i = 0
        while (i < beats.size) {
            val f = beats[i].roundToInt()
            val d = abs(beats[i] - frames)
            if (f <= most && d < bestD) { bestD = d; best = f }
            i += BAR_BEATS
        }
        return if (beats.size > BAR_BEATS) best else frames
    }

    /** The first bar line (see [onBar]) at or after [frames]; [frames] when there is none. */
    fun barFrom(frames: Int, beats: DoubleArray): Int {
        var i = 0
        while (i < beats.size) {
            val f = ceil(beats[i] - 1e-9).toInt()
            if (f >= frames) return f
            i += BAR_BEATS
        }
        return frames
    }

    /** Beats in a bar ([onBar]). */
    const val BAR_BEATS = 4

    /** Photo cards over the moving route: at most one per 2.5 s of a clip of [seconds]. */
    fun photoCap(seconds: Double): Int = (seconds / 2.5).toInt().coerceAtLeast(1)

    /** Photos the journey pauses for: at most one per 4 s of a clip of [seconds] (pauses add up quickly). */
    fun holdCap(seconds: Double): Int = (seconds / 4.0).toInt().coerceAtLeast(1)

    /**
     * How many of [photos] (those that sit on the route) the journey pauses for, [holdSeconds] each, in a clip whose
     * travel, opening and close take [baseSeconds]: within [holdCap] and so that the whole stays within [MAX_SECONDS].
     */
    fun heldPhotos(photos: Int, baseSeconds: Double, holdSeconds: Double): Int {
        if (photos <= 0 || holdSeconds <= 0.0) return 0
        val room = ((MAX_SECONDS - baseSeconds) / holdSeconds).toInt().coerceAtLeast(0)
        return minOf(photos, holdCap(baseSeconds), room)
    }

    /**
     * The whole clip in seconds for [travelHours] at [speed]: drawing, opening and close, within the clamps, plus the
     * pauses for [photos] held [holdSeconds] each (see [heldPhotos]). The speed chips and the plan agree on it.
     */
    fun seconds(travelHours: Double, speed: Int, photos: Int = 0, holdSeconds: Double = 0.0): Double {
        val base = baseSeconds(travelHours, speed)
        return base + heldPhotos(photos, base, holdSeconds) * holdSeconds
    }

    /**
     * Which of [holds] (the photos on the route, in route order, each with its own pause) the journey pauses for in
     * a clip whose travel, opening and close take [baseSeconds]: the ones asked for first, then others spread evenly,
     * one per 4 s of clip at most ([holdCap], more only when more are asked for), and only as many as fit in [room]
     * seconds (by default what keeps the whole within [MAX_SECONDS]; a chosen length gives them a share of itself).
     * Photos without a pause are not counted. Indices into [holds], in order.
     */
    fun chooseHeld(holds: List<PhotoHold>, baseSeconds: Double, room: Double = MAX_SECONDS - baseSeconds): List<Int> {
        val held = holds.indices.filter { holds[it].seconds > 0.0 }
        if (held.isEmpty()) return emptyList()
        var asked = held.filter { holds[it].forced }
        while (asked.isNotEmpty() && asked.sumOf { holds[it].seconds } > room) asked = asked.dropLast(1)
        val others = held.filterNot { holds[it].forced }
        var count = minOf(others.size, (holdCap(baseSeconds) - asked.size).coerceAtLeast(0))
        while (count > 0) {
            val picked = pickEvenly(others, count)
            if ((asked + picked).sumOf { holds[it].seconds } <= room) return (asked + picked).sorted()
            count--
        }
        return asked.sorted()
    }

    /**
     * The whole clip in seconds with each photo's own pause ([chooseHeld]); the plan and the speed chips agree.
     * [extraSeconds]: what full-frame inserts add whatever the speed (phone videos, day cards: [ClipPlan.insertSeconds]).
     */
    fun seconds(travelHours: Double, speed: Int, holds: List<PhotoHold>, extraSeconds: Double = 0.0): Double {
        val base = baseSeconds(travelHours, speed)
        return base + chooseHeld(holds, base).sumOf { holds[it].seconds } + extraSeconds
    }

    /** [freeSpeed] with each photo's own pause and the inserts' [extraSeconds]. */
    fun freeSpeed(travelHours: Double, speed: Int, isPro: Boolean, holds: List<PhotoHold>, extraSeconds: Double = 0.0): Int {
        fun long(k: Int) = needsPro(seconds(travelHours, k, holds, extraSeconds))
        if (isPro || !long(speed)) return speed
        return SPEEDS.filter { it > speed }.firstOrNull { !long(it) } ?: SPEEDS.last()
    }

    /** The clip without photo pauses: drawing, opening and close, within the clamps. */
    fun baseSeconds(travelHours: Double, speed: Int): Double {
        val draw = drawSeconds(travelHours, speed)
        // T = draw + intro(T) + outro(T); both grow with T, so search it.
        var lo = draw
        var hi = draw + 11.0
        repeat(50) {
            val mid = (lo + hi) / 2
            if (mid - introSeconds(mid) - outroSeconds(mid) < draw) lo = mid else hi = mid
        }
        return hi.coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    /** Frames for [seconds] at [fps]. */
    fun frames(seconds: Double, fps: Int): Int = (seconds * fps).roundToInt().coerceAtLeast(fps)

    /** "0:34", "1:12". */
    fun clock(seconds: Double): String {
        val s = seconds.roundToInt()
        return "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }

    /** Whether a clip of [seconds] needs Pro. */
    fun needsPro(seconds: Double): Boolean = seconds > FREE_MAX_SECONDS + 0.5

    /** [speed], or on the free version, when that clip would run past a minute, the next faster speed that doesn't. */
    fun freeSpeed(travelHours: Double, speed: Int, isPro: Boolean, photos: Int = 0, holdSeconds: Double = 0.0): Int {
        fun long(k: Int) = needsPro(seconds(travelHours, k, photos, holdSeconds))
        if (isPro || !long(speed)) return speed
        return SPEEDS.filter { it > speed }.firstOrNull { !long(it) } ?: SPEEDS.last()
    }
}

/**
 * How long the whole clip runs (owner, 2026-11-08: "make sure speeds are well defined, x1 seems huge"): the clip lands
 * on [seconds] (opening, journey, photo pauses, phone videos, day cards and the end all inside it) and the journey's
 * speed follows. [Auto], the default, grows with the trip from [seconds] ([target]); at least [LONG_S] for a
 * wide (16:9) clip; the others are set lengths. A free clip stays within [ClipTiming.FREE_MAX_SECONDS]: 60 s at most.
 * No length ([ClipOptions.length] null) is the older "Pace": the trip's travel time at ×1 to ×10.
 */
enum class ClipLength(val id: String, val label: String, val seconds: Double) {
    Auto("auto", "Auto", 25.0),
    S15("15", "15 s", 15.0),
    S30("30", "30 s", 30.0),
    S45("45", "45 s", 45.0),
    S60("60", "60 s", 60.0);

    /**
     * The clip's length in seconds for a trip of [tripDays] days in [shape]. Auto grows with the trip, about
     * [AUTO_PER_DAY_S] a day from [seconds] up to the free minute (owner, 2026-11-08, on a 31-day trip at 45 s: "it
     * goes too fast"): a weekend 25 s, nine days about 32 s, a month 60 s; a wide clip at least [LONG_S].
     */
    fun target(tripDays: Int, shape: ClipShape): Double {
        if (this != Auto) return seconds
        val grown = (AUTO_BASE_S + AUTO_PER_DAY_S * tripDays.coerceAtLeast(1)).coerceIn(seconds, AUTO_MAX_S)
        return if (shape == ClipShape.Wide) max(grown, LONG_S) else grown
    }

    companion object {
        /** Auto's shortest length for a wide clip. */
        const val LONG_S = 45.0
        /** Auto: [AUTO_BASE_S] plus [AUTO_PER_DAY_S] a day, at most [AUTO_MAX_S] (a free clip's minute). */
        const val AUTO_BASE_S = 20.0
        const val AUTO_PER_DAY_S = 1.3
        const val AUTO_MAX_S = 60.0

        fun of(id: String?): ClipLength? = entries.firstOrNull { it.id == id }
    }
}

/** A photo on the route the journey may pause for: [seconds] of pause (0 = none), [forced] when asked for by hand. */
data class PhotoHold(val seconds: Double, val forced: Boolean = false)

/** At most [count] of [items], spread evenly (the middle one when there is room for one). */
internal fun <T> pickEvenly(items: List<T>, count: Int): List<T> = when {
    count <= 0 || items.isEmpty() -> emptyList()
    items.size <= count -> items
    count == 1 -> listOf(items[items.size / 2])
    else -> (0 until count).map { items[(it * (items.size - 1).toDouble() / (count - 1)).roundToInt()] }.distinct()
}

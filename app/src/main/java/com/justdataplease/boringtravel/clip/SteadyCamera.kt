package com.justdataplease.boringtravel.clip

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the plan reads from either camera on the journey's own timeline: its width per frame and its zoom speed limit. */
internal interface ClipCamera {
    /** The fastest zoom, in ln(scale) per frame. */
    val rate: Double

    /** The journey's view on its own (no opening or close): ln(width) per frame. */
    val followLn: DoubleArray
}

/**
 * A stretch of the journey the steady camera ([SteadyCamera]) holds at one width: output frames [from, to), [span]
 * world units wide. [own]: a place's own zoom (the scene editor), which keeps its height over its whole stretch. A
 * "whole route" stretch looks at the route's middle ([cx], [cy]; NaN otherwise).
 */
class CameraRun(
    val from: Int,
    val to: Int,
    val span: Double,
    val own: Boolean = false,
    val cx: Double = Double.NaN,
    val cy: Double = Double.NaN,
) {
    val fixed: Boolean get() = !cx.isNaN()
    val frames: Int get() = to - from
}

/**
 * The clip's steady camera ([CameraMode.Steady], the default; owner, 2026-11-08: "the camera zooms out and in … i want
 * strava like effects"), worked out for every frame in advance (plain Kotlin):
 *
 * 1. One width per run ([CameraRun]: a day, or a stretch with one vehicle, or a place's own zoom; the plan chooses
 *    them from each run's route and the clip's zoom, never from the speed on screen frame by frame).
 * 2. Between runs the width changes once: eased ([Ease.smoothstep]) over at least [EASE_S], longer when the change is
 *    big enough to break the zoom speed limit ([rate]). The change sits around the boundary (the camera lifts as a
 *    plane takes off, while it is still slow, and comes down as it lands), next to a place's own zoom (its height holds
 *    over its whole stretch), or under a full-frame insert at the change (a day card, a phone video), which hides it.
 *    However the changes fall, the view is never so close that the head moves more than [STEP_LIMIT] of it in a frame.
 * 3. Close-ups ([FollowCamera.Still]): in to the close view at the zoom speed limit, held, and out; centred on the place.
 * 4. The centre: a chase camera. Up to [LEAD] of the view ahead of the head along its way (where the head is, on
 *    average, over the next [LEAD_LOOK_S]), the lead kept as a share of the view and smoothed both ways like a soft
 *    spring that doesn't lag ([SMOOTH_S]): the traveller holds a steady place on screen and the map moves under it.
 *    Held while the journey waits (photo pauses, inserts); the head never nearer an edge than [KEEP] of the view from
 *    the middle: the centre moves, the width does not.
 * 5. The opening and the close ([cameras]): from the opening view, already moving at frame 0, one eased dive onto the
 *    journey; at the end one eased pull-back to the same view, so the last frame frames like the first.
 */
internal class SteadyCamera(
    private val hx: DoubleArray,
    private val hy: DoubleArray,
    private val fps: Int,
    private val aspect: Double,
    private val overview: Camera,
    val runs: List<CameraRun>,
    /**
     * The journey's frames: before them the camera dives onto the first width, after them it pulls back from the last
     * (a close-up at either end holds its close view into the dive or the pull-back).
     */
    journey: IntRange = 0 until hx.size,
    stills: List<FollowCamera.Still> = emptyList(),
    /** Frames where the journey waits (photo pauses, inserts): the camera's aim holds there. */
    waits: BooleanArray? = null,
    /** Full-frame inserts (day cards, phone videos): a width change next to one happens under it. */
    hidden: List<IntRange> = emptyList(),
    /**
     * The Follow camera ([CameraMode.Chase]): the runs' widths hold however fast the head goes (no step floor), and the
     * centre follows it.
     */
    chase: Boolean = false,
) : ClipCamera {
    private val n = hx.size
    private fun r(seconds: Double) = max(1, (seconds * fps).roundToInt())

    override val rate = ClipPlan.MAX_ZOOM_RATE * 30.0 / fps

    /** Each frame's run (the first before the journey, the last after it); declared before the init block that uses it. */
    private val runIndex = IntArray(n) { f ->
        var lo = 0
        var hi = runs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (runs[mid].from <= f) lo = mid else hi = mid - 1
        }
        lo
    }
    override val followLn: DoubleArray
    val followX: DoubleArray
    val followY: DoubleArray

    /** Frames of a place's own zoom and of the changes next to it: the traveller's choice, no step floor there. */
    private val chosen = BooleanArray(n) { runs.isNotEmpty() && runs[runIndex[it]].own }

    init {
        require(hy.size == n && n > 0)
        // 1–2. Each run's width, and one eased change at each boundary.
        var l = widths(hidden)
        // 3. Close-ups after, at the zoom speed limit in and out (their stretch is long enough to hold the close view).
        if (stills.isNotEmpty()) {
            for (s in stills) {
                val c = ln(min(s.span, overview.spanX))
                for (f in max(0, s.fromFrame) until min(n, s.toFrame)) l[f] = min(l[f], c)
            }
        }
        // Never so close that the head moves more than STEP_LIMIT of the short side in a frame (a run's own width
        // already allows for its fastest frame; this keeps a change that comes late, or a run too short for its
        // change, from letting the head race across the view). A place's own zoom is the traveller's choice.
        val wide = max(1.0, aspect)
        for (f in 0 until n) {
            if (chosen[f] || chase) continue
            var step = 0.0
            for (g in max(0, f - 2) until min(n - 1, f + 2)) step = max(step, hypot(hx[g + 1] - hx[g], hy[g + 1] - hy[g]))
            if (step > 0) l[f] = max(l[f], ln(step / STEP_LIMIT * wide))
        }
        // Before and after the journey the view is the dive's target and the pull-back's start: as at its ends.
        if (!journey.isEmpty()) {
            val a = journey.first.coerceIn(0, n - 1)
            val b = journey.last.coerceIn(0, n - 1)
            for (f in 0 until a) l[f] = l[a]
            for (f in b + 1 until n) l[f] = l[b]
        }
        // A run too short for its change gets it as fast as allowed: the wider view wins (never faster than the limit).
        l = FollowCamera.rateEnvelope(l, rate)
        l = ClipPlan.boxSmooth(ClipPlan.boxSmooth(l, r(0.1)), r(0.1))
        followLn = l

        // 4. The centre: like a chase camera, the head + a lead, kept as a share of the view (so the traveller holds
        // a steady place on screen and the map moves under it) and smoothed as a share, never beyond LEAD.
        val sumX = DoubleArray(n + 1)
        val sumY = DoubleArray(n + 1)
        for (i in 0 until n) { sumX[i + 1] = sumX[i] + hx[i]; sumY[i + 1] = sumY[i] + hy[i] }
        val look = r(LEAD_LOOK_S)
        val leadX = DoubleArray(n)
        val leadY = DoubleArray(n)
        val fixed = BooleanArray(n) { runAt(it)?.fixed == true }
        for (f in 0 until n) {
            val hi = min(n - 1, f + look)
            val w = exp(l[f])
            val h = w / aspect
            leadX[f] = ((sumX[hi + 1] - sumX[f]) / (hi - f + 1) - hx[f]) / w
            leadY[f] = ((sumY[hi + 1] - sumY[f]) / (hi - f + 1) - hy[f]) / h
            leadX[f] = leadX[f].coerceIn(-LEAD, LEAD)
            // North (up the frame, into a tilted picture, which shows more ground there anyway) leads a little less.
            leadY[f] = leadY[f].coerceIn(-LEAD_NORTH, LEAD)
        }
        // While the journey waits, the lead stays as the wait began: the camera settles and holds.
        if (waits != null) for (f in 1 until n) if (waits[f] && waits[f - 1]) { leadX[f] = leadX[f - 1]; leadY[f] = leadY[f - 1] }
        val smooth = r(SMOOTH_S)
        val sx = ClipPlan.boxSmooth(ClipPlan.boxSmooth(leadX, smooth), smooth)
        val sy = ClipPlan.boxSmooth(ClipPlan.boxSmooth(leadY, smooth), smooth)
        val cx = DoubleArray(n)
        val cy = DoubleArray(n)
        for (f in 0 until n) {
            val run = runAt(f)
            if (fixed[f] && run != null) {
                // A "whole route" stretch keeps its middle exactly.
                cx[f] = run.cx
                cy[f] = run.cy
                continue
            }
            val w = exp(l[f])
            cx[f] = hx[f] + sx[f] * w
            cy[f] = hy[f] + sy[f] * w / aspect
        }
        // Close-ups look at the place itself: the centre eases onto the waiting head and back off it.
        val towards = DoubleArray(n)
        val ramp = r(FollowCamera.STILL_AIM_S)
        for (s in stills) for (f in max(0, s.fromFrame - ramp) until min(n, s.toFrame + ramp)) {
            val w = when {
                f < s.fromFrame -> 1.0 - (s.fromFrame - f).toDouble() / (ramp + 1)
                f >= s.toFrame -> 1.0 - (f - s.toFrame + 1).toDouble() / (ramp + 1)
                else -> 1.0
            }
            towards[f] = max(towards[f], Ease.smoothstep(w))
        }
        fun onPlaces(x: DoubleArray, y: DoubleArray) {
            for (f in 0 until n) if (towards[f] > 0) {
                x[f] = Ease.lerp(x[f], hx[f], towards[f])
                y[f] = Ease.lerp(y[f], hy[f], towards[f])
            }
        }
        onPlaces(cx, cy)
        // The head stays in frame by moving the centre (gently, then a hard stop at the edge margin).
        keep(cx, cy, l, fixed, KEEP)
        val soft = r(0.1)
        followX = ClipPlan.boxSmooth(ClipPlan.boxSmooth(cx, soft), soft)
        followY = ClipPlan.boxSmooth(ClipPlan.boxSmooth(cy, soft), soft)
        for (f in 0 until n) if (fixed[f]) { followX[f] = cx[f]; followY[f] = cy[f] }
        // A close-up stays exactly on its place through the last smoothing too.
        onPlaces(followX, followY)
        keep(followX, followY, l, fixed, HARD)
    }

    /** The run frame [f] belongs to (the first before the journey, the last after it). */
    private fun runAt(f: Int): CameraRun? = if (runs.isEmpty()) null else runs[runIndex[f]]

    /** ln(width) per frame: each run's own, with one eased change at each boundary. */
    private fun widths(hidden: List<IntRange>): DoubleArray {
        val l = DoubleArray(n)
        if (runs.isEmpty()) { l.fill(ln(overview.spanX)); return l }
        class Change(var s: Int, var e: Int, val to: Double)
        val changes = ArrayList<Change>()
        var prevEnd = 0
        for (i in 0 until runs.size - 1) {
            val a = runs[i]
            val b = runs[i + 1]
            val la = ln(a.span)
            val lb = ln(b.span)
            val d = lb - la
            if (abs(d) < 1e-9) continue
            val at = b.from
            val need = max(r(EASE_S), ceil(1.5 * abs(d) / rate).toInt())
            val reach = r(HIDE_REACH_S)
            val under = hidden.firstOrNull { at >= it.first - reach && at <= it.last + 1 + reach }
            var s: Int
            var e: Int
            when {
                under != null -> {
                    val len = max(need, under.last + 1 - under.first)
                    s = (under.first + under.last + 1) / 2 - len / 2
                    e = s + len
                }
                // A place's own zoom keeps its height over its whole stretch: the change happens next to it.
                b.own -> { s = at - need; e = at }
                a.own -> { s = at; e = at + need }
                // Otherwise around the boundary: the camera lifts as a plane takes off and comes down as it lands.
                else -> { s = at - need / 2; e = s + need }
            }
            s = max(s, prevEnd).coerceIn(0, n)
            e = e.coerceIn(s + 1, max(s + 1, n))
            if (a.own || b.own) for (f in s until min(e, n)) chosen[f] = true
            changes += Change(s, e, lb)
            prevEnd = e
        }
        var level = ln(runs.first().span)
        var c = 0
        for (f in 0 until n) {
            while (c < changes.size && f >= changes[c].e) { level = changes[c].to; c++ }
            val ch = changes.getOrNull(c)
            l[f] = if (ch != null && f >= ch.s) level + (ch.to - level) * Ease.smoothstep((f - ch.s + 0.5) / (ch.e - ch.s)) else level
        }
        return l
    }

    /** Moves the centre so the head is never further than [frac] of the view from it (frames of a fixed view stay). */
    private fun keep(cx: DoubleArray, cy: DoubleArray, l: DoubleArray, fixed: BooleanArray, frac: Double) {
        for (f in 0 until n) {
            if (fixed[f]) continue
            val w = exp(l[f])
            val h = w / aspect
            val dx = hx[f] - cx[f]
            val dy = hy[f] - cy[f]
            if (abs(dx) > frac * w) cx[f] = hx[f] - Math.copySign(frac * w, dx)
            if (abs(dy) > frac * h) cy[f] = hy[f] - Math.copySign(frac * h, dy)
        }
    }

    /**
     * Every frame's camera: from the opening view at frame 0, diving onto the journey by [openingEnd] (eased out: it
     * moves from the first frame and lands softly); the journey; from [closeFrom] one eased pull-back to the opening
     * view, reached at [closeTo]. On the globe the view from space also turns [turn] world columns into place as it
     * dives. The pan keeps pace with the zoom: the centre moves in step with the width (in linear terms), so the head,
     * in both views, stays in view throughout.
     */
    fun cameras(openingEnd: Int, closeFrom: Int, closeTo: Int, turn: Double = 0.0): Array<Camera> {
        val o = overview.spanX
        val lnO = ln(o)
        // The pull-back leaves from where the journey's camera was (a close-up at the last stop, or a fixed whole-route
        // view, may aim elsewhere once the journey is over): no sideways jerk on its first frame.
        val last = (closeFrom - 1).coerceIn(0, n - 1)
        return Array(n) { f ->
            val s1 = exp(followLn[f])
            val fx = if (f >= closeFrom) followX[last] else followX[f]
            val fy = if (f >= closeFrom) followY[last] else followY[f]
            val e = when {
                f < openingEnd -> dive(f.toDouble() / openingEnd)
                f >= closeTo -> 0.0
                f >= closeFrom -> 1 - Ease.smoothstep((f - closeFrom + 1).toDouble() / (closeTo - closeFrom))
                else -> 1.0
            }
            if (e >= 1.0) return@Array Camera(fx, fy, s1)
            if (e <= 0.0) return@Array Camera(overview.cx + if (f < openingEnd) turn else 0.0, overview.cy, o)
            val s = exp(lnO + (followLn[f] - lnO) * e)
            val m = if (o - s1 > o * 1e-9) ((o - s) / (o - s1)).coerceIn(0.0, 1.0) else e
            val spin = if (f < openingEnd) turn * (1 - m) else 0.0
            Camera(Ease.lerp(overview.cx, fx, m) + spin, Ease.lerp(overview.cy, fy, m), s)
        }
    }

    companion object {
        /** A change of width between runs takes at least this long (longer when the zoom speed limit asks): a glide. */
        const val EASE_S = 1.8
        /**
         * The head never moves more than this share of the frame's short side in a frame: at least what a run's own
         * width allows its fastest frame (ClipPlan's RUN_STEP_MAX at the closest zoom), so it never acts inside a run,
         * and low enough that the centre, following, glides (under 8% of the view a frame with the lead's growth).
         */
        const val STEP_LIMIT = 0.075
        /** A full-frame insert this close to a change (seconds either side) hides it. */
        const val HIDE_REACH_S = 0.25
        /** The centre leads the head by up to this share of the view (sideways and southwards; [LEAD_NORTH] north). */
        const val LEAD = 0.2
        const val LEAD_NORTH = 0.12
        /** The lead points where the head is, on average, over this much of the coming journey. */
        const val LEAD_LOOK_S = 1.5
        /** The lead's smoothing (each of two passes, either way): soft, and centred so it never lags the head. */
        const val SMOOTH_S = 0.3
        /** The head stays within this share of the view from the middle (12% from the edges: the route's own margin)... */
        const val KEEP = 0.5 - ClipPlan.PAD_X
        /** ...and never nearer than 8% to an edge, whatever the smoothing did. */
        const val HARD = FollowCamera.HARD_FRAC

        /** The dive's easing: moving from the first frame, landing softly (an ease-out). */
        fun dive(t: Double): Double = Ease.clamp01(t).let { it * (2 - it) }
    }
}

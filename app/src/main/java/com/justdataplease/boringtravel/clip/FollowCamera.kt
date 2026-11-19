package com.justdataplease.boringtravel.clip

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The clip's adaptive camera ([CameraMode.Follow]; the default is [SteadyCamera]), worked out for every frame in
 * advance (plain Kotlin): close to the moving head, pulling out for long fast stretches and holding a whole flight in
 * view, then back in.
 *
 * 1. Look ahead: each frame aims at where the head is over the next [LOOK_AHEAD_S] (and the last [LOOK_BACK_S]) of
 *    screen time, so there is more ground ahead of the marker than behind it. The head itself stays near the middle
 *    (a little below it when heading north, which in 3D is "into" the picture).
 * 2. Width: just wide enough to show those positions with room around them ([COMFORT]), never narrower than
 *    [minSpan] (a few km of ground, wider on Earth where the pictures run out of detail). During a [Jump] (a flight,
 *    or a long straight connection) the view holds both ends.
 * 3. Smooth: dips shorter than [HOLD_S] are filled (no zooming in only to zoom out again), the zoom speed is capped
 *    at [rate] and the widening starts early, so the view is already wide when the head takes off.
 * 4. The opening holds the overview, then dives in at the same capped speed; the close pulls back to it.
 * 5. The head always stays inside the frame ([HARD_FRAC] of the half-view from the centre).
 *
 * The clip's zoom ([ClipZoom]) sets, per frame, how much of the route around the head is kept in view ([look] scales
 * the look window) and how close the camera may come ([minSpan]); a "whole route" stretch is a [Jump] held in full.
 * A close-up ([Still]) is a stretch where the head waits at a place: the camera centres on it, moves in to the
 * close-up's width at the capped speed, holds, and pulls back out by the end of the stretch.
 */
internal class FollowCamera(
    private val hx: DoubleArray,
    private val hy: DoubleArray,
    private val fps: Int,
    private val aspect: Double,
    private val overview: Camera,
    private val drawStart: Int,
    jumps: List<Jump>,
    /** The narrowest view width allowed (world units) in frame f around world row y. */
    minSpan: (f: Int, y: Double) -> Double,
    /** Per frame, the look window's length as a share of [LOOK_AHEAD_S] and [LOOK_BACK_S] (null: 1 everywhere). */
    look: DoubleArray? = null,
    stills: List<Still> = emptyList(),
    /**
     * The width of a "whole route" stretch: the overview's, unless the overview shows more than the route (Globe's
     * view from space).
     */
    private val routeSpan: Double = overview.spanX,
    /**
     * The widest the camera may go to keep the head in frame (Globe: the view from space, past which there is nothing
     * more to see; the head round the back is simply hidden).
     */
    private val maxSpan: Double = Double.POSITIVE_INFINITY,
) : ClipCamera {
    /**
     * Frames [fromFrame, toFrame) travel a long jump whose route fits in [rect]; [whole]: the view holds all of it
     * however long it lasts (the "whole route" zoom).
     */
    class Jump(val fromFrame: Int, val toFrame: Int, val rect: WorldRect, val whole: Boolean = false)

    /** Frames [fromFrame, toFrame): the head stands still while the camera closes in to a view [span] wide. */
    class Still(val fromFrame: Int, val toFrame: Int, val span: Double)

    private val n = hx.size
    private fun r(seconds: Double) = max(1, (seconds * fps).roundToInt())

    /** The fastest zoom, in ln(scale) per frame. */
    override val rate = ClipPlan.MAX_ZOOM_RATE * 30.0 / fps
    private val lnO = ln(overview.spanX)

    /** The follow view on its own (no opening or close): centre and ln(width) per frame. */
    val followX: DoubleArray
    val followY: DoubleArray
    override val followLn: DoubleArray

    init {
        require(hy.size == n && n > 0)
        val sumX = DoubleArray(n + 1)
        val sumY = DoubleArray(n + 1)
        for (i in 0 until n) { sumX[i + 1] = sumX[i] + hx[i]; sumY[i + 1] = sumY[i] + hy[i] }
        // A short jump holds both ends in view. A long one (a flight given many seconds) shows both ends at take-off,
        // then follows the plane with a longer look, so the ground keeps moving under it instead of a still picture.
        // A "whole route" stretch holds its rectangle however long it lasts.
        val jumpAt = arrayOfNulls<Jump>(n)
        val reachAt = IntArray(n)
        for (j in jumps) {
            val long = j.toFrame - j.fromFrame > r(JUMP_HOLD_S) && !j.whole
            for (f in max(0, j.fromFrame) until min(n, j.toFrame)) {
                if (!long || f < j.fromFrame + r(ESTABLISH_S)) jumpAt[f] = j else reachAt[f] = (j.toFrame - j.fromFrame) / 6
            }
        }

        // The local view: where to look and how wide, from the look window alone.
        val aimX = DoubleArray(n)
        val aimY = DoubleArray(n)
        val local = DoubleArray(n)
        for (f in 0 until n) {
            val share = look?.get(f)?.takeIf { it.isFinite() && it > 0 } ?: 1.0
            val lo = max(0, f - max(r(LOOK_BACK_S * share), reachAt[f]))
            val hi = min(n - 1, f + max(r(LOOK_AHEAD_S * share), reachAt[f]))
            val cx = (sumX[hi + 1] - sumX[lo]) / (hi - lo + 1)
            val cy = (sumY[hi + 1] - sumY[lo]) / (hi - lo + 1)
            var span = 0.0
            for (g in lo..hi) span = max(span, needSpan(hx[g] - cx, hy[g] - cy, COMFORT))
            aimX[f] = cx
            aimY[f] = cy
            local[f] = span
        }
        // A close-up looks at the place itself: the aim slides onto the waiting head and back off it.
        if (stills.isNotEmpty()) {
            val towards = DoubleArray(n)
            val ramp = r(STILL_AIM_S)
            for (s in stills) for (f in max(0, s.fromFrame - ramp) until min(n, s.toFrame + ramp)) {
                val w = when {
                    f < s.fromFrame -> 1.0 - (s.fromFrame - f).toDouble() / (ramp + 1)
                    f >= s.toFrame -> 1.0 - (f - s.toFrame + 1).toDouble() / (ramp + 1)
                    else -> 1.0
                }
                towards[f] = max(towards[f], w)
            }
            for (f in 0 until n) if (towards[f] > 0) {
                aimX[f] = Ease.lerp(aimX[f], hx[f], towards[f])
                aimY[f] = Ease.lerp(aimY[f], hy[f], towards[f])
            }
        }
        fun jumpSpan(j: Jump) = if (j.whole) min(routeSpan, overview.spanX) else
            min(max(needSpan(j.rect.w / 2, j.rect.h / 2, COMFORT), minSpan(max(0, j.fromFrame), j.rect.cy)), overview.spanX)
        val need = DoubleArray(n) { f -> jumpAt[f]?.let(::jumpSpan) ?: local[f] }
        for (f in 0 until n) {
            val floor = minSpan(f, aimY[f])
            need[f] = ln(min(max(need[f], floor), overview.spanX))
            local[f] = ln(min(max(local[f], floor), overview.spanX))
        }

        // No pumping: fill short dips, cap the zoom speed (widening early), round the corners.
        val hold = r(HOLD_S)
        fun steady(a: DoubleArray): DoubleArray {
            val l = rateEnvelope(minFilter(maxFilter(a, hold), hold), rate)
            return ClipPlan.boxSmooth(ClipPlan.boxSmooth(l, r(0.3)), r(0.3))
        }
        var l = steady(need)
        var localLn = steady(local)
        // Close-ups after the smoothing, so a long enough stretch holds its full depth: in and out at the capped speed.
        if (stills.isNotEmpty()) {
            for (s in stills) {
                val c = ln(min(s.span, overview.spanX))
                for (f in max(0, s.fromFrame) until min(n, s.toFrame)) {
                    l[f] = min(l[f], c)
                    localLn[f] = min(localLn[f], c)
                }
            }
            l = rateEnvelope(l, rate)
            localLn = rateEnvelope(localLn, rate)
        }
        followLn = l

        // The centre: the aim, but keeping the head near the middle (away from photo cards and labels)...
        val cx = DoubleArray(n)
        val cy = DoubleArray(n)
        for (f in 0 until n) {
            val w = exp(localLn[f])
            val h = w / aspect
            cx[f] = hx[f] + (aimX[f] - hx[f]).coerceIn(-LEAD_SIDE * w, LEAD_SIDE * w)
            cy[f] = hy[f] + (aimY[f] - hy[f]).coerceIn(-LEAD_DOWN * h, LEAD_UP * h)
        }
        // ...and around a held jump, moving to the jump's centre in step with the widening (and back with the
        // narrowing), so the head stays in both views and the pan keeps pace with the zoom.
        val prev = arrayOfNulls<Jump>(n)
        val next = arrayOfNulls<Jump>(n)
        var last: Jump? = null
        for (f in 0 until n) { jumpAt[f]?.let { last = it }; prev[f] = last }
        last = null
        for (f in n - 1 downTo 0) { jumpAt[f]?.let { last = it }; next[f] = last }
        for (f in 0 until n) {
            val s = exp(l[f])
            val sl = exp(localLn[f])
            var best = 0.0
            var to: Jump? = null
            for (j in listOf(prev[f], next[f])) {
                if (j == null) continue
                val sj = jumpSpan(j)
                val w = if (sj - sl > sj * 0.02) ((s - sl) / (sj - sl)).coerceIn(0.0, 1.0) else 0.0
                if (w > best) { best = w; to = j }
            }
            if (to != null) {
                cx[f] = Ease.lerp(cx[f], to.rect.cx, best)
                cy[f] = Ease.lerp(cy[f], to.rect.cy, best)
            }
            // A "whole route" stretch looks at the middle of the route throughout.
            jumpAt[f]?.takeIf { it.whole }?.let { cx[f] = it.rect.cx; cy[f] = it.rect.cy }
        }
        val smooth = r(0.15)
        followX = ClipPlan.boxSmooth(ClipPlan.boxSmooth(cx, smooth), smooth)
        followY = ClipPlan.boxSmooth(ClipPlan.boxSmooth(cy, smooth), smooth)
    }

    /**
     * Every frame's camera: the overview until [drawStart], diving into the follow view, and back on the overview
     * from [overviewFrom] (the end card).
     */
    fun cameras(overviewFrom: Int): Array<Camera> {
        val o = overview.spanX
        var l = DoubleArray(n) { f -> if (f < drawStart || f >= overviewFrom) max(lnO, followLn[f]) else followLn[f] }
        l = rateEnvelope(l, rate)
        l = ClipPlan.boxSmooth(ClipPlan.boxSmooth(l, r(0.2)), r(0.2))
        // How far from the overview towards the follow view the centre is. In step with the width (so the head, in
        // both views, stays in view and the pan keeps pace with the zoom), but never faster than a pan over [PAN_S]
        // (when the follow view is nearly as wide as the overview, the width alone would pan in a few frames).
        val step = DoubleArray(n) { k ->
            l[k] = max(l[k], followLn[k])
            if (k < drawStart || k >= overviewFrom) 0.0 else {
                val s = exp(l[k])
                ((o - s + PAN_SLACK * o) / (o - exp(followLn[k]) + PAN_SLACK * o)).coerceIn(0.0, 1.0)
            }
        }
        val capped = rateEnvelope(DoubleArray(n) { -step[it] }, 1.0 / r(PAN_S))
        val mix = ClipPlan.boxSmooth(ClipPlan.boxSmooth(DoubleArray(n) { -capped[it] }, r(0.15)), r(0.15))
        val cx = DoubleArray(n)
        val cy = DoubleArray(n)
        val ls = DoubleArray(n)
        for (k in 0 until n) {
            val w = mix[k]
            cx[k] = Ease.lerp(overview.cx, followX[k], w)
            cy[k] = Ease.lerp(overview.cy, followY[k], w)
            ls[k] = min(max(l[k], ln(needSpan(hx[k] - cx[k], hy[k] - cy[k], HARD_FRAC))), ln(maxSpan))
        }
        val lf = rateEnvelope(ls, rate)
        return Array(n) { Camera(cx[it], cy[it], exp(lf[it])) }
    }

    /** Width of a view centred off a point by (dx, dy) that keeps it within [frac] of the half-view from the centre. */
    private fun needSpan(dx: Double, dy: Double, frac: Double): Double =
        max(max(abs(dx) / frac, abs(dy) * aspect / frac), 1e-12)

    companion object {
        const val LOOK_AHEAD_S = 1.5
        const val LOOK_BACK_S = 0.6
        /** Positions in the look window stay this close to the centre (as a share of the half-view): 14% margins. */
        const val COMFORT = 0.72 / 2
        /** The head never gets closer to an edge than 8% of the view. */
        const val HARD_FRAC = 0.42
        /** Dips in the wanted width shorter than twice this are filled. */
        const val HOLD_S = 0.8
        /**
         * A jump up to this long holds both ends in view; a longer one shows both ends for [ESTABLISH_S] at take-off,
         * then is followed with a sixth of it ahead and behind in view.
         */
        const val JUMP_HOLD_S = 8.0
        const val ESTABLISH_S = 2.0
        /** The pan between the overview's centre and the follow view's takes at least this long. */
        const val PAN_S = 1.0
        /** Softens the pan-with-the-zoom mix where the follow view is about as wide as the overview. */
        const val PAN_SLACK = 0.05
        /** How far the centre may lead the head, as a share of the view: sideways, northwards, southwards. */
        const val LEAD_SIDE = 0.14
        const val LEAD_DOWN = 0.08
        const val LEAD_UP = 0.14
        /** The aim slides onto a close-up's place over this long before it and back off it after. */
        const val STILL_AIM_S = 0.5

        /** Raises [a] as little as possible so that it never changes faster than [rate] per step. */
        fun rateEnvelope(a: DoubleArray, rate: Double): DoubleArray {
            val out = a.copyOf()
            for (i in 1 until out.size) out[i] = max(out[i], out[i - 1] - rate)
            for (i in out.size - 2 downTo 0) out[i] = max(out[i], out[i + 1] - rate)
            return out
        }

        fun maxFilter(a: DoubleArray, radius: Int): DoubleArray = DoubleArray(a.size) { i ->
            var m = Double.NEGATIVE_INFINITY
            for (j in max(0, i - radius)..min(a.size - 1, i + radius)) m = max(m, a[j])
            m
        }

        fun minFilter(a: DoubleArray, radius: Int): DoubleArray = DoubleArray(a.size) { i ->
            var m = Double.POSITIVE_INFINITY
            for (j in max(0, i - radius)..min(a.size - 1, i + radius)) m = min(m, a[j])
            m
        }
    }
}

package com.justdataplease.boringtravel.book

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/** A point in mm on the page (trim coordinates) or in pixels on screen. */
data class P2(val x: Double, val y: Double) {
    operator fun plus(o: P2) = P2(x + o.x, y + o.y)
    operator fun minus(o: P2) = P2(x - o.x, y - o.y)
    operator fun times(k: Double) = P2(x * k, y * k)
}

/**
 * Screen ↔ page for the editor: the page's trim top-left sits at ([ox], [oy]) screen pixels and one mm is [scale]
 * pixels (the preview is never turned).
 */
data class PageTransform(val scale: Double, val ox: Double, val oy: Double) {
    fun toPage(sx: Double, sy: Double): P2 = P2((sx - ox) / scale, (sy - oy) / scale)
    fun toScreen(x: Double, y: Double): P2 = P2(ox + x * scale, oy + y * scale)
    fun toScreen(p: P2): P2 = toScreen(p.x, p.y)

    /** A distance in screen pixels as mm. */
    fun mm(px: Double): Double = px / scale
}

/** A guide line shown while snapping: vertical at x = [at] when [vertical], else horizontal at y = [at] (mm). */
data class Guide(val vertical: Boolean, val at: Double)

/** Where things snap to: lines across (x) and down (y) the page, in mm. */
data class SnapLines(val xs: List<Double>, val ys: List<Double>)

/** A snapped move: the change to apply and the guides it lined up with. */
data class Snap(val dx: Double, val dy: Double, val guides: List<Guide>)

/**
 * The editor's geometry, all in page mm: elements are boxes turned by an angle around their centre. Corners are
 * numbered 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left (before turning).
 */
object FreeMath {
    /** Smallest side of an element, mm. */
    const val MIN_SIZE = 4.0

    /** Angles snap to whole quarter and eighth turns within this many degrees. */
    const val ANGLE_SNAP = 4.0

    fun rotate(p: P2, deg: Double, about: P2): P2 {
        if (deg == 0.0) return p
        val a = Math.toRadians(deg)
        val c = cos(a)
        val s = sin(a)
        val dx = p.x - about.x
        val dy = p.y - about.y
        return P2(about.x + dx * c - dy * s, about.y + dx * s + dy * c)
    }

    fun centre(b: Box) = P2(b.cx, b.cy)

    /** The four corners of [b] turned by [rot], in order 0..3. */
    fun corners(b: Box, rot: Double): List<P2> {
        val c = centre(b)
        return listOf(P2(b.x, b.y), P2(b.right, b.y), P2(b.right, b.bottom), P2(b.x, b.bottom)).map { rotate(it, rot, c) }
    }

    /** The upright box around [b] turned by [rot]. */
    fun bounds(b: Box, rot: Double): Box {
        if (rot == 0.0) return b
        val cs = corners(b, rot)
        val x0 = cs.minOf { it.x }
        val y0 = cs.minOf { it.y }
        return Box(x0, y0, cs.maxOf { it.x } - x0, cs.maxOf { it.y } - y0)
    }

    /** Whether page point ([x], [y]) falls on [b] turned by [rot], with [slop] mm to spare on every side. */
    fun hit(b: Box, rot: Double, x: Double, y: Double, slop: Double = 0.0): Boolean {
        val p = rotate(P2(x, y), -rot, centre(b))
        return p.x >= b.x - slop && p.x <= b.right + slop && p.y >= b.y - slop && p.y <= b.bottom + slop
    }

    /** Where the turn handle sits: [gap] mm above the middle of the top edge, turning with the box. */
    fun rotateHandle(b: Box, rot: Double, gap: Double): P2 = rotate(P2(b.cx, b.y - gap), rot, centre(b))

    fun move(b: Box, dx: Double, dy: Double): Box = b.offset(dx, dy)

    /**
     * [b] (turned by [rot]) resized by dragging corner [corner] to page point [p]; the opposite corner stays where it
     * is. [keepAspect] keeps the shape (photos). Never smaller than [min] mm.
     */
    fun resize(b: Box, rot: Double, corner: Int, p: P2, keepAspect: Boolean, min: Double = MIN_SIZE): Box {
        val cs = corners(b, rot)
        val anchor = cs[(corner + 2) % 4]
        // The pointer in the box's own (unturned) axes, from the fixed corner.
        val local = rotate(p, -rot, anchor) - anchor
        val sx = if (corner == 1 || corner == 2) 1.0 else -1.0
        val sy = if (corner == 2 || corner == 3) 1.0 else -1.0
        var w = max(local.x * sx, min)
        var h = max(local.y * sy, min)
        if (keepAspect && b.w > 0 && b.h > 0) {
            // Follow the pointer along the diagonal: the larger of the two scales wins.
            val k = max(w / b.w, h / b.h).coerceAtLeast(max(min / b.w, min / b.h))
            w = b.w * k
            h = b.h * k
        }
        val half = rotate(P2(anchor.x + sx * w / 2, anchor.y + sy * h / 2), rot, anchor)
        return Box(half.x - w / 2, half.y - h / 2, w, h)
    }

    /** The turn that points the box's top towards page point [p] (degrees, clockwise from upright). */
    fun angleTo(b: Box, p: P2): Double {
        val a = Math.toDegrees(atan2(p.y - b.cy, p.x - b.cx)) + 90.0
        return BookJson.normalAngle(a)
    }

    /** [deg] moved onto the nearest multiple of 45° when within [tol] of it. */
    fun snapAngle(deg: Double, tol: Double = ANGLE_SNAP): Double {
        val k = round(deg / 45.0) * 45.0
        return BookJson.normalAngle(if (abs(deg - k) <= tol) k else deg)
    }

    /**
     * Lines an element lines up with on a page of [w] × [h] mm: the edges and centre of the page, the type area's
     * edges ([margins]) and the edges and centres of the [others] (their upright boxes).
     */
    fun snapLines(w: Double, h: Double, margins: Box?, others: List<Box>): SnapLines {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        xs += listOf(0.0, w / 2, w)
        ys += listOf(0.0, h / 2, h)
        margins?.let { xs += listOf(it.x, it.right); ys += listOf(it.y, it.bottom) }
        others.forEach { o -> xs += listOf(o.x, o.cx, o.right); ys += listOf(o.y, o.cy, o.bottom) }
        return SnapLines(xs.distinct(), ys.distinct())
    }

    /**
     * Snaps a box being moved: its left, centre or right (and top, middle or bottom) onto the nearest [lines] within
     * [tol] mm. Returns the extra move and the guides to show.
     */
    fun snap(moving: Box, lines: SnapLines, tol: Double): Snap {
        val bx = best(listOf(moving.x, moving.cx, moving.right), lines.xs, tol)
        val by = best(listOf(moving.y, moving.cy, moving.bottom), lines.ys, tol)
        val guides = buildList {
            if (bx != null) add(Guide(true, bx.second))
            if (by != null) add(Guide(false, by.second))
        }
        return Snap(bx?.first ?: 0.0, by?.first ?: 0.0, guides)
    }

    /** The smallest shift (and the line it reaches) that puts one of [edges] on one of [lines], within [tol]. */
    private fun best(edges: List<Double>, lines: List<Double>, tol: Double): Pair<Double, Double>? {
        var bestD = Double.MAX_VALUE
        var bestLine = 0.0
        for (e in edges) for (l in lines) {
            val d = l - e
            if (abs(d) <= tol && abs(d) < abs(bestD)) { bestD = d; bestLine = l }
        }
        return if (bestD == Double.MAX_VALUE) null else bestD to bestLine
    }

    /** Distance between two screen points. */
    fun dist(a: P2, b: P2): Double = hypot(a.x - b.x, a.y - b.y)

    /** [b] kept from leaving the page entirely: at least [keep] mm of it stays on a [w] × [h] page. */
    fun keepOnPage(b: Box, w: Double, h: Double, keep: Double = 6.0): Box {
        val k = min(keep, min(b.w, b.h))
        val x = b.x.coerceIn(-b.w + k, w - k)
        val y = b.y.coerceIn(-b.h + k, h - k)
        return Box(x, y, b.w, b.h)
    }
}

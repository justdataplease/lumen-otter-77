package com.justdataplease.boringtravel.globe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Where a clipped ring's fill goes (an android Path in the app, a recorder in tests). Coordinates in pixels. */
interface FillSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)

    /** Along the globe's rim from [startRad] (screen angle: 0 = right, clockwise, as Path.arcTo) by [sweepRad]. */
    fun rimArc(startRad: Float, sweepRad: Float)
    fun close()
}

/** Where outline segments go (drawLines in the app). */
fun interface LineSink {
    fun segment(x1: Float, y1: Float, x2: Float, y2: Float)
}

/**
 * Clips rings and lines on the sphere to the half facing the viewer, so nothing behind the globe shows and nothing
 * smears across the disc. Where a ring goes behind the horizon, the fill follows the rim from the exit point to the
 * entry point in the direction the hidden part travels (so the land between stays filled, and only it); outlines stop
 * at the horizon, and the 180° meridian / South Pole cuts of the source data are never outlined.
 *
 * Reuses its scratch arrays: no allocation per frame once they have grown to the largest ring.
 */
class RingClipper(capacity: Int = 64) {
    private var px = FloatArray(capacity)
    private var py = FloatArray(capacity)
    private var pd = FloatArray(capacity)

    private fun ensure(n: Int) {
        if (px.size >= n) return
        val size = maxOf(n, px.size * 2)
        px = FloatArray(size); py = FloatArray(size); pd = FloatArray(size)
    }

    /**
     * One ring of [n] unit vectors starting at vertex [start] of [xyz]. [cap] = centre x, y, z and sin(radius) at
     * [capAt] (or null to always test each vertex). Returns [HIDDEN], [VISIBLE] (no clipping needed) or [CLIPPED].
     */
    fun ring(
        xyz: FloatArray,
        start: Int,
        n: Int,
        o: Ortho,
        fill: FillSink?,
        line: LineSink?,
        seam: BooleanArray? = null,
        cap: FloatArray? = null,
        capAt: Int = 0,
    ): Int {
        if (n < 3) return HIDDEN
        var all = false
        if (cap != null) {
            val d = o.depth(cap[capAt], cap[capAt + 1], cap[capAt + 2])
            val s = cap[capAt + 3]
            if (d < -s) return HIDDEN
            all = d > s
        }
        ensure(n)
        var first = -1
        for (i in 0 until n) {
            val b = 3 * (start + i)
            val x = xyz[b]
            val y = xyz[b + 1]
            val z = xyz[b + 2]
            px[i] = o.x(x, y, z)
            py[i] = o.y(x, y, z)
            pd[i] = if (all) 1f else o.depth(x, y, z)
            if (first < 0 && pd[i] >= 0f) first = i
        }
        if (first < 0) return HIDDEN
        if (!all) {
            all = true
            for (i in 0 until n) if (pd[i] < 0f) { all = false; break }
        }

        val cx = o.cx
        val cy = o.cy
        val r = o.r
        fill?.moveTo(cx + r * px[first], cy - r * py[first])
        var exitAngle = 0f
        var last = 0f
        var sweep = 0f
        for (k in 0 until n) {
            val i = (first + k) % n
            val j = if (i + 1 == n) 0 else i + 1
            val vi = pd[i] >= 0f
            val vj = pd[j] >= 0f
            val outlined = line != null && (seam == null || !seam[start + i])
            when {
                vi && vj -> {
                    val x2 = cx + r * px[j]
                    val y2 = cy - r * py[j]
                    fill?.lineTo(x2, y2)
                    if (outlined) line.segment(cx + r * px[i], cy - r * py[i], x2, y2)
                }
                vi -> {
                    horizon(i, j)
                    val hx = cx + r * hX
                    val hy = cy - r * hY
                    fill?.lineTo(hx, hy)
                    if (outlined) line.segment(cx + r * px[i], cy - r * py[i], hx, hy)
                    exitAngle = hAngle
                    last = angleOf(j)
                    sweep = GlobeMath.wrapRad(last - exitAngle)
                }
                !vj -> {
                    val a = angleOf(j)
                    sweep += GlobeMath.wrapRad(a - last)
                    last = a
                }
                else -> {
                    horizon(i, j)
                    sweep += GlobeMath.wrapRad(hAngle - last)
                    if (abs(sweep) > 1e-4f) fill?.rimArc(exitAngle, sweep)
                    val x2 = cx + r * px[j]
                    val y2 = cy - r * py[j]
                    fill?.lineTo(x2, y2)
                    if (outlined) line.segment(cx + r * hX, cy - r * hY, x2, y2)
                }
            }
        }
        fill?.close()
        return if (all) VISIBLE else CLIPPED
    }

    /** An open line (graticule): the parts facing the viewer, cut exactly at the horizon. */
    fun polyline(xyz: FloatArray, start: Int, n: Int, o: Ortho, line: LineSink) {
        if (n < 2) return
        ensure(n)
        for (i in 0 until n) {
            val b = 3 * (start + i)
            px[i] = o.x(xyz[b], xyz[b + 1], xyz[b + 2])
            py[i] = o.y(xyz[b], xyz[b + 1], xyz[b + 2])
            pd[i] = o.depth(xyz[b], xyz[b + 1], xyz[b + 2])
        }
        val cx = o.cx
        val cy = o.cy
        val r = o.r
        for (i in 0 until n - 1) {
            val j = i + 1
            val vi = pd[i] >= 0f
            val vj = pd[j] >= 0f
            when {
                vi && vj -> line.segment(cx + r * px[i], cy - r * py[i], cx + r * px[j], cy - r * py[j])
                vi -> { horizon(i, j); line.segment(cx + r * px[i], cy - r * py[i], cx + r * hX, cy - r * hY) }
                vj -> { horizon(i, j); line.segment(cx + r * hX, cy - r * hY, cx + r * px[j], cy - r * py[j]) }
            }
        }
    }

    // The last horizon crossing, on the rim in globe radii, and its screen angle.
    private var hX = 0f
    private var hY = 0f
    private var hAngle = 0f

    /** Where the edge i→j crosses the horizon (depth 0), pushed onto the rim. */
    private fun horizon(i: Int, j: Int) {
        val di = pd[i]
        val dj = pd[j]
        val t = if (di == dj) 0.5f else (di / (di - dj)).coerceIn(0f, 1f)
        var x = px[i] + t * (px[j] - px[i])
        var y = py[i] + t * (py[j] - py[i])
        val len = sqrt(x * x + y * y)
        if (len < 1e-6f) {
            x = px[i]; y = py[i]
            val l2 = sqrt(x * x + y * y).coerceAtLeast(1e-6f)
            x /= l2; y /= l2
        } else {
            x /= len; y /= len
        }
        hX = x
        hY = y
        hAngle = atan2(-y, x)
    }

    /** Screen angle of a vertex seen from the centre (for vertices behind the globe: where it sits under the rim). */
    private fun angleOf(i: Int): Float = atan2(-py[i], px[i])

    companion object {
        const val HIDDEN = 0
        const val VISIBLE = 1
        const val CLIPPED = 2
    }
}

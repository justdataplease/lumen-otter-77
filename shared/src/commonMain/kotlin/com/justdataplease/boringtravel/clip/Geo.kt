package com.justdataplease.boringtravel.clip

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/** Distances on the globe (haversine); good to a few metres, which is all a clip needs. */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8
    const val EARTH_CIRCUMFERENCE_M = 2 * PI * EARTH_RADIUS_M

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = lat1.rad()
        val p2 = lat2.rad()
        val dp = (lat2 - lat1).rad()
        val dl = (lng2 - lng1).rad()
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun Double.rad() = this * PI / 180.0
}

/**
 * Web Mercator in "world units": the whole world is 1 × 1, x grows east, y grows south (like screen pixels), the
 * projection MapLibre's snapshots use. Straight lines here are straight lines on the base map.
 */
object Mercator {
    const val MAX_LAT = 85.05112878

    fun x(lng: Double): Double = (lng + 180.0) / 360.0

    fun y(lat: Double): Double {
        val s = sin(lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0)
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    fun lng(x: Double): Double = x * 360.0 - 180.0
    fun lat(y: Double): Double = atan(sinh(PI * (1 - 2 * y))) * 180.0 / PI

    /** World units per metre on the ground at world row [y]. */
    fun unitsPerMeter(y: Double): Double = 1.0 / (Geo.EARTH_CIRCUMFERENCE_M * cos(lat(y) * PI / 180.0))
}

/** An axis-aligned rectangle in world units. */
data class WorldRect(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val w: Double get() = x1 - x0
    val h: Double get() = y1 - y0
    val cx: Double get() = (x0 + x1) / 2
    val cy: Double get() = (y0 + y1) / 2

    fun contains(x: Double, y: Double, slack: Double = 1e-12) = x >= x0 - slack && x <= x1 + slack && y >= y0 - slack && y <= y1 + slack
    fun contains(r: WorldRect, slack: Double = 1e-12) = r.x0 >= x0 - slack && r.x1 <= x1 + slack && r.y0 >= y0 - slack && r.y1 <= y1 + slack
    fun intersects(r: WorldRect) = r.x0 < x1 && r.x1 > x0 && r.y0 < y1 && r.y1 > y0
    fun union(r: WorldRect) = WorldRect(min(x0, r.x0), min(y0, r.y0), max(x1, r.x1), max(y1, r.y1))
    fun union(x: Double, y: Double) = WorldRect(min(x0, x), min(y0, y), max(x1, x), max(y1, y))

    /** The overlap with [r], or null when they don't overlap. */
    fun intersect(r: WorldRect): WorldRect? {
        val a = max(x0, r.x0)
        val b = max(y0, r.y0)
        val c = min(x1, r.x1)
        val d = min(y1, r.y1)
        return if (c > a && d > b) WorldRect(a, b, c, d) else null
    }

    /** Moved [dx] world units east (a Globe route past 180° sits outside 0–1, see [ClipGlobe.near]). */
    fun shiftedX(dx: Double) = if (dx == 0.0) this else WorldRect(x0 + dx, y0, x1 + dx, y1)

    /** Grows each side by [fraction] of the larger side. */
    fun padded(fraction: Double): WorldRect {
        val p = max(w, h) * fraction
        return WorldRect(x0 - p, y0 - p, x1 + p, y1 + p)
    }

    /** Grows the short side so width / height == [aspect], keeping the centre. */
    fun toAspect(aspect: Double): WorldRect {
        val hw: Double
        val hh: Double
        if (w / h.coerceAtLeast(1e-18) > aspect) {
            hw = w / 2; hh = w / aspect / 2
        } else {
            hh = h / 2; hw = h * aspect / 2
        }
        return WorldRect(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    companion object {
        fun around(xs: DoubleArray, ys: DoubleArray, from: Int = 0, to: Int = xs.size): WorldRect {
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (i in from until to) {
                x0 = min(x0, xs[i]); x1 = max(x1, xs[i]); y0 = min(y0, ys[i]); y1 = max(y1, ys[i])
            }
            return WorldRect(x0, y0, x1, y1)
        }
    }
}

/**
 * How strongly a closer base-map picture covering [layer] shows over the overview picture for a [view] at [zoom]
 * (1 = overview). It fades in as the camera zooms in and fades out as the view nears the picture's edge, so the
 * switch between pictures is never a jump. Depends only on geometry, so any frame can be drawn on its own.
 */
object LayerBlend {
    fun weight(layer: WorldRect, view: WorldRect, zoom: Double): Double = inside(layer, view) * Ease.window(zoom, 1.2, 1.5)

    /** 1 when [view] sits well inside [layer] (3% of the view to spare), fading to 0 as it reaches the edge. */
    fun inside(layer: WorldRect, view: WorldRect): Double {
        val margin = min(min(view.x0 - layer.x0, layer.x1 - view.x1), min(view.y0 - layer.y0, layer.y1 - view.y1))
        return Ease.smoothstep(margin / (0.03 * max(view.w, view.h)))
    }
}

/** Compass bearing (0 = north, 90 = east) of a move by (dx, dy) in world units. */
fun bearingOf(dx: Double, dy: Double): Double = (atan2(dx, -dy) * 180.0 / PI + 360.0) % 360.0

/** Easing curves, all mapping 0 → 0 and 1 → 1. */
object Ease {
    fun clamp01(t: Double) = t.coerceIn(0.0, 1.0)

    fun inOutCubic(t: Double): Double {
        val x = clamp01(t)
        return if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2
    }

    fun outCubic(t: Double): Double = 1 - (1 - clamp01(t)).let { it * it * it }

    /** Overshoots a little past 1 before settling: for things that pop in. */
    fun outBack(t: Double): Double {
        val x = clamp01(t)
        val c1 = 1.70158
        val c3 = c1 + 1
        return 1 + c3 * (x - 1).let { it * it * it } + c1 * (x - 1).let { it * it }
    }

    fun smoothstep(t: Double): Double = clamp01(t).let { it * it * (3 - 2 * it) }

    /** Half linear, half smoothstep: slows near the ends without ever stopping (head speed over a day). */
    fun travel(t: Double): Double = 0.5 * clamp01(t) + 0.5 * smoothstep(t)

    /** 0 below [from], 1 above [to], smooth between. */
    fun window(x: Double, from: Double, to: Double): Double = smoothstep((x - from) / (to - from).coerceAtLeast(1e-9))

    fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
}

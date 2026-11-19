package com.justdataplease.boringtravel.clip

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.justdataplease.boringtravel.globe.GlobePalette
import com.justdataplease.boringtravel.globe.GlobeRenderer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The ground of a Globe clip ([ClipGlobe]), for [ClipRenderer]: the Map tab's globe ([GlobeRenderer]: its sea, land,
 * graticule, light and rim, the trip's countries in the look's highlighter and their flags while far out), the street
 * map draped onto the sphere close in (each picture through a mesh projected onto the sphere, so it lines up with the
 * route at every zoom), and the route, stops and head projected the same way, long legs along their great circles.
 * Everything is drawn in the ground plane (under the tilt, which only comes in once the street map has).
 *
 * One per renderer (not thread-safe); [begin] sets the frame's view.
 */
internal class GlobeGround(
    private val plan: ClipPlan,
    private val base: BaseMap,
    globe: GlobeBase,
    palette: GlobePalette,
    typeface: Typeface?,
    private val u: Float,
    private val width: Int,
    private val height: Int,
    tone: ColorFilter?,
) {
    private val aspect = plan.options.shape.aspect
    private val globeRenderer = GlobeRenderer(globe.geometry, u, PIN_DP).apply {
        // A video frame is drawn once, so the fills get their smooth edges.
        antiAliasFills = true
        setPalette(palette, typeface)
        setCountries(globe.countries.toSet(), emptySet())
        setPins(globe.pins)
    }

    /** The route's points on the sphere and each leg's angle. */
    private val route = Array(plan.xs.size) { unit(plan.xs[it], plan.ys[it]) }
    private val legAngle = DoubleArray(max(0, route.size - 1)) { route[it].angleTo(route[it + 1]) }

    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { colorFilter = tone }
    private val shaders = arrayOfNulls<BitmapShader>(base.layers.size)
    private var verts = FloatArray(0)
    private var texs = FloatArray(0)
    private var indices = ShortArray(0)
    private val bounds = RectF()

    /** The frame's view of the sphere (ground pixels, before the tilt). */
    var view: GlobeView = GlobeView(0.0, 0.0, width / 2.0, height / 2.0, 1.0)
        private set
    private var drape = 0.0

    fun begin(camera: Camera) {
        view = GlobeView.of(camera, width.toDouble(), height.toDouble())
        drape = ClipGlobe.drape(camera, aspect)
    }

    /** The ground pixels a tilted [projection] shows (the whole frame when flat), for clipping the ground. */
    fun groundClip(projection: ReplayProjection): RectF {
        val corners = listOf(
            projection.unproject(0.0, projection.horizonY), projection.unproject(width.toDouble(), projection.horizonY),
            projection.unproject(0.0, height.toDouble()), projection.unproject(width.toDouble(), height.toDouble()),
        )
        bounds.set(
            corners.minOf { it.x }.toFloat(), corners.minOf { it.y }.toFloat(),
            corners.maxOf { it.x }.toFloat(), corners.maxOf { it.y }.toFloat(),
        )
        return bounds
    }

    /**
     * The globe and, as the camera comes close, the street map over it ([ClipGlobe.drape]); [ground] is the world
     * rectangle in view (the tilted camera's whole ground), [s] the frame.
     */
    fun drawGround(canvas: Canvas, s: FrameState, ground: WorldRect) {
        val layers = base.layers
        val covered = drape >= 0.999 && layers.isNotEmpty() && LayerBlend.inside(layers[0].rect, ground) >= 0.999
        if (!covered) {
            globeRenderer.pinAlpha = ClipGlobe.flags(s.camera, aspect).toFloat()
            globeRenderer.draw(canvas, view.lng0, view.lat0, view.cx.toFloat(), view.cy.toFloat(), view.r.toFloat(), width.toFloat(), height.toFloat())
        }
        if (drape <= 0.0 || layers.isEmpty()) return
        val alpha = (drape * 255).roundToInt()
        if (alpha < 255) canvas.saveLayerAlpha(null, alpha)
        val weights = DoubleArray(layers.size) { i -> if (i == 0) 1.0 else LayerBlend.weight(layers[i].rect, ground, s.zoom) }
        // Coarse to sharp, from the sharpest one that covers the view (nothing under it shows), as on the flat maps.
        val from = weights.indices.lastOrNull { weights[it] >= 0.999 && LayerBlend.inside(layers[it].rect, ground) >= 0.999 } ?: 0
        val area = ground.padded(0.05)
        for (i in from until layers.size) {
            val w = weights[i]
            if (w < 0.004) continue
            val region = layers[i].rect.intersect(area) ?: continue
            drapeLayer(canvas, i, region, w)
        }
        if (alpha < 255) canvas.restore()
    }

    /**
     * One street map picture over [region] (world units) of it, onto the sphere: a grid of points on the picture,
     * each placed where the sphere shows it; the grid is fine enough for the sphere's curve and the tilt's perspective
     * (a triangle maps its picture flat).
     */
    private fun drapeLayer(canvas: Canvas, index: Int, region: WorldRect, weight: Double) {
        val layer = base.layers[index]
        val bmp = layer.bitmap
        val rect = layer.rect
        val latSpan = abs(Mercator.lat(region.y0) - Mercator.lat(region.y1))
        val lngSpan = region.w * 360.0 * cos(Math.toRadians(Mercator.lat(region.cy)))
        val n = ceil(max(latSpan, lngSpan) / MESH_DEG).toInt().coerceIn(MESH_MIN, MESH_MAX)
        val count = (n + 1) * (n + 1)
        if (verts.size < 2 * count) {
            verts = FloatArray(2 * count)
            texs = FloatArray(2 * count)
        }
        val v = view
        var k = 0
        for (j in 0..n) {
            val y = region.y0 + region.h * j / n
            val lat = Mercator.lat(y)
            for (i in 0..n) {
                val x = region.x0 + region.w * i / n
                val p = Unit3.of(lat, Mercator.lng(x))
                var gx = v.x(p) - v.cx
                var gy = v.y(p) - v.cy
                if (v.depth(p) < 0) {
                    // Past the horizon (only a huge picture far from the middle): pinned to the rim.
                    val d = hypot(gx, gy).coerceAtLeast(1e-9)
                    gx *= v.r / d; gy *= v.r / d
                }
                verts[2 * k] = (v.cx + gx).toFloat()
                verts[2 * k + 1] = (v.cy + gy).toFloat()
                texs[2 * k] = ((x - rect.x0) / rect.w * bmp.width).toFloat()
                texs[2 * k + 1] = ((y - rect.y0) / rect.h * bmp.height).toFloat()
                k++
            }
        }
        val needed = 6 * n * n
        if (indices.size < needed) indices = ShortArray(needed)
        var t = 0
        for (j in 0 until n) for (i in 0 until n) {
            val a = (j * (n + 1) + i).toShort()
            val b = (j * (n + 1) + i + 1).toShort()
            val c = ((j + 1) * (n + 1) + i).toShort()
            val d = ((j + 1) * (n + 1) + i + 1).toShort()
            indices[t++] = a; indices[t++] = b; indices[t++] = c
            indices[t++] = b; indices[t++] = d; indices[t++] = c
        }
        meshPaint.shader = shaders[index] ?: BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shaders[index] = it }
        meshPaint.alpha = (weight * 255).roundToInt().coerceIn(0, 255)
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, 2 * count, verts, 0, texs, 0, null, 0, indices, 0, needed, meshPaint)
        meshPaint.shader = null
    }

    /**
     * The route so far into [drawn] and the rest into [ahead] (the flat maps' two paths), each leg along its great
     * circle, in ground pixels; what is behind the globe is left out.
     */
    fun routePaths(s: FrameState, drawn: Path, ahead: Path, clip: RectF) {
        drawn.rewind()
        ahead.rewind()
        if (route.isEmpty()) return
        val head = unit(s.headX, s.headY)
        val seg = s.headSeg.coerceIn(0, max(0, route.size - 2))
        val pen = Pen(clip)
        pen.start(drawn, route[0])
        for (i in 0 until seg) pen.arc(route[i], route[i + 1], legAngle[i])
        if (route.size > 1) pen.arc(route[seg], head, route[seg].angleTo(head))
        pen.start(ahead, head)
        if (route.size > 1) {
            pen.arc(head, route[seg + 1], head.angleTo(route[seg + 1]))
            for (i in seg + 1 until route.size - 1) pen.arc(route[i], route[i + 1], legAngle[i])
        }
    }

    /**
     * Ground pixels of world point ([x], [y]) on the sphere, with how squarely it faces the viewer (1 in the middle,
     * 0 at the rim); null behind the globe or right at its rim (below [minDepth]).
     */
    fun ground(x: Double, y: Double, minDepth: Double = 0.02): Triple<Double, Double, Double>? {
        val p = unit(x, y)
        val d = view.depth(p)
        if (d < minDepth) return null
        return Triple(view.x(p), view.y(p), d)
    }

    /** A pen that follows great circles into a path: lifts at the globe's rim, finer where the curve shows. */
    private inner class Pen(private val clip: RectF) {
        private var path: Path? = null
        private var down = false
        private var last = Unit3(1.0, 0.0, 0.0)
        private val v = view
        private val reach = max(width, height) * 0.5f

        fun start(p: Path, at: Unit3) {
            path = p
            last = at
            down = false
            if (v.depth(at) >= 0) {
                p.moveTo(v.x(at).toFloat(), v.y(at).toFloat())
                down = true
            }
        }

        /** Along the great circle from [a] (the pen's place) to [b], [theta] apart. */
        fun arc(a: Unit3, b: Unit3, theta: Double, depth: Int = 0) {
            if (depth < MAX_DEPTH && theta > 1e-9 && (theta > STEP_RAD || curves(a, b, theta))) {
                val m = Unit3.normalised(a.x + b.x, a.y + b.y, a.z + b.z)
                if (m != null) {
                    arc(a, m, theta / 2, depth + 1)
                    arc(m, b, theta / 2, depth + 1)
                    return
                }
            }
            to(b)
        }

        /** Whether the straight line from [a] to [b] would leave the circle by more than a fraction of a pixel on screen. */
        private fun curves(a: Unit3, b: Unit3, theta: Double): Boolean {
            if (v.r * theta * theta / 8 < MAX_SAG_PX) return false
            val m = Unit3.normalised(a.x + b.x, a.y + b.y, a.z + b.z) ?: return false
            val ax = v.x(a); val ay = v.y(a)
            val bx = v.x(b); val by = v.y(b)
            val mx = v.x(m); val my = v.y(m)
            if (hypot(mx - (ax + bx) / 2, my - (ay + by) / 2) < MAX_SAG_PX) return false
            // Off screen it doesn't matter how the line bends.
            val l = min(ax, min(bx, mx)); val r = max(ax, max(bx, mx))
            val t = min(ay, min(by, my)); val bt = max(ay, max(by, my))
            return r >= clip.left - reach && l <= clip.right + reach && bt >= clip.top - reach && t <= clip.bottom + reach
        }

        private fun to(b: Unit3) {
            val p = path ?: return
            val da = v.depth(last)
            val db = v.depth(b)
            when {
                da >= 0 && db >= 0 -> {
                    if (!down) p.moveTo(v.x(last).toFloat(), v.y(last).toFloat())
                    p.lineTo(v.x(b).toFloat(), v.y(b).toFloat())
                    down = true
                }
                da >= 0 -> {
                    val c = rim(last, b, da, db)
                    if (!down) p.moveTo(v.x(last).toFloat(), v.y(last).toFloat())
                    if (c != null) p.lineTo(v.x(c).toFloat(), v.y(c).toFloat())
                    down = false
                }
                db >= 0 -> {
                    val c = rim(last, b, da, db)
                    if (c != null) p.moveTo(v.x(c).toFloat(), v.y(c).toFloat()) else p.moveTo(v.x(b).toFloat(), v.y(b).toFloat())
                    p.lineTo(v.x(b).toFloat(), v.y(b).toFloat())
                    down = true
                }
                else -> down = false
            }
            last = b
        }

        /** Where the great circle from [a] to [b] crosses the rim (depth 0). */
        private fun rim(a: Unit3, b: Unit3, da: Double, db: Double): Unit3? {
            val t = da / (da - db)
            return Unit3.normalised(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
        }
    }

    private fun unit(x: Double, y: Double) = Unit3.of(Mercator.lat(y), Mercator.lng(x))

    /**
     * The marker's heading on screen through the tilt: the way the head faces ([FrameState.bearing], degrees) on the
     * sphere at the head ([GlobeView.tangent]: never through a point past the rim, which used to flip it), as screen
     * degrees; null when the head is round the back.
     */
    fun screenHeading(s: FrameState, projection: ReplayProjection, anchorX: Double, anchorY: Double): Double? {
        val head = unit(s.headX, s.headY)
        if (view.depth(head) < 0.0) return null
        val (dx, dy) = view.tangent(head.lat, head.lng, s.bearing) ?: return null
        val gx = view.x(head)
        val gy = view.y(head)
        val p = projection.project(gx + dx * 20 * u, gy + dy * 20 * u)
        return Math.toDegrees(kotlin.math.atan2(p.x - anchorX, anchorY - p.y))
    }

    companion object {
        /** Flag pins' size in design dp (the Map tab's are 30 dp). */
        const val PIN_DP = 30f
        /** Mesh cells of at most this many degrees, between [MESH_MIN] and [MESH_MAX] a side. */
        const val MESH_DEG = 1.0
        const val MESH_MIN = 8
        const val MESH_MAX = 24
        /** Far out, great circles are drawn in steps of at most [ClipGlobe.STEP_DEG]; close in, finer until a line strays under this from its curve. */
        val STEP_RAD = Math.toRadians(ClipGlobe.STEP_DEG)
        const val MAX_SAG_PX = 0.3
        const val MAX_DEPTH = 26
    }
}

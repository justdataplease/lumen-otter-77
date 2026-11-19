package com.justdataplease.boringtravel.globe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.random.Random

/** A flag pin: where it sits on the sphere and the name under it (in the phone's language). */
class GlobePin(val code: String, lat: Double, lng: Double, val label: String) {
    val x = GlobeMath.unitX(lat, lng)
    val y = GlobeMath.unitY(lat, lng)
    val z = GlobeMath.unitZ(lat)
}

/**
 * Draws the globe into an android.graphics.Canvas: the screen's Compose canvas each frame, or a bitmap for the share
 * card. Order: stars (Night Route), paper shadow (notebooks), air glow, sea, graticule, land, visited land, outlines,
 * planned/wished dashes, day-side light and edge shade, rim, then flag pins with their names.
 *
 * Everything per frame reuses the same paths, arrays and shaders (shaders move by their local matrix), so turning the
 * globe allocates nothing. Fills skip anti-aliasing on screen: the anti-aliased outlines and rim cover their edges,
 * and aliased fills are far cheaper to redraw every frame.
 */
class GlobeRenderer(private val geometry: GlobeGeometry, private val density: Float, pinSizeDp: Float = 30f) {
    private val ortho = Ortho()
    private val clipper = RingClipper(geometry.maxRing + 4)

    private val landPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val visitedPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val dashPath = Path()
    private val lines = LineBuffer()
    private val visitedLines = LineBuffer()
    private val gridLines = LineBuffer()
    private val oval = RectF()

    private val landFill = PathFill(landPath, oval)
    private val visitedFill = PathFill(visitedPath, oval)
    private val dashSink = PathLines(dashPath)

    /** 0 = land, 1 = visited, 2 = planned or wished (dashed). */
    private var states = ByteArray(geometry.countryCount)

    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val pinPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    private val matrix = Matrix()
    private var glowShader: Shader? = null
    private var limbShader: Shader? = null
    private var lightShader: Shader? = null

    private val pinSize = pinSizeDp * density
    private val pinR = pinSize / 2f
    private var pins: List<GlobePin> = emptyList()
    private var badges: Array<Bitmap?> = emptyArray()
    private var labelW = FloatArray(0)

    // Per-frame pin layout (visible pins only, in priority order).
    private var visIndex = IntArray(0)
    private var visX = FloatArray(0)
    private var visY = FloatArray(0)
    private var anchorX = FloatArray(0)
    private var anchorY = FloatArray(0)
    private var visAlpha = FloatArray(0)
    private var visScale = FloatArray(0)
    private var visLabelW = FloatArray(0)
    private var visLabel = BooleanArray(0)
    private var visCount = 0

    private val starX: FloatArray
    private val starY: FloatArray
    private val starBig: BooleanArray
    private var starPts = FloatArray(0)

    var antiAliasFills = false
        set(value) { field = value; fillPaint.isAntiAlias = value }
    var showLabels = true
    /** The flag pins and their names fade by this (the trip video lets them go as its camera comes down); 1 = as always. */
    var pinAlpha = 1f
    var palette: GlobePalette? = null
        private set

    init {
        val rnd = Random(7)
        val n = 150
        starX = FloatArray(n) { rnd.nextFloat() }
        starY = FloatArray(n) { rnd.nextFloat() }
        starBig = BooleanArray(n) { rnd.nextFloat() < 0.12f }
    }

    fun setPalette(p: GlobePalette, typeface: Typeface?) {
        if (p == palette && labelPaint.typeface == typeface) return
        palette = p
        labelPaint.typeface = typeface
        haloPaint.typeface = typeface
        glowShader = RadialGradient(0f, 0f, 1f, intArrayOf(p.glow, p.glow, Color.TRANSPARENT), floatArrayOf(0f, 1f / GLOW, 1f), Shader.TileMode.CLAMP)
        limbShader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, p.limb), floatArrayOf(0f, 0.68f, 1f), Shader.TileMode.CLAMP)
        lightShader = RadialGradient(0f, 0f, 1f, intArrayOf(p.light, Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        measureLabels()
        rebuildBadges()
    }

    private fun measureLabels() {
        labelPaint.textSize = LABEL_SP * density
        labelW = FloatArray(pins.size) { labelPaint.measureText(pins[it].label) + 4 * density }
    }

    /** Which countries are filled and which dashed, by counted code ([com.justdataplease.boringtravel.geo.CountryInfo.key]). */
    fun setCountries(visited: Set<String>, dashed: Set<String>) {
        val s = ByteArray(geometry.countryCount)
        for (i in 0 until geometry.countryCount) {
            val k = geometry.keys[i] ?: continue
            s[i] = when (k) {
                in visited -> 1
                in dashed -> 2
                else -> 0
            }
        }
        states = s
    }

    /** Flag pins, most important first (they win space and sit on top). */
    fun setPins(list: List<GlobePin>) {
        pins = list
        val n = list.size
        visIndex = IntArray(n); visX = FloatArray(n); visY = FloatArray(n); anchorX = FloatArray(n); anchorY = FloatArray(n)
        visAlpha = FloatArray(n); visScale = FloatArray(n); visLabelW = FloatArray(n); visLabel = BooleanArray(n)
        measureLabels()
        rebuildBadges()
    }

    private fun rebuildBadges() {
        val p = palette ?: return
        val spec = FlagBadges.Spec(
            sizePx = pinSize.toInt(), ringPx = 2.5f * density, shadowPx = 3f * density, tiltDeg = p.pinTilt,
            fallbackBg = p.chip, fallbackInk = p.ink,
        )
        badges = Array(pins.size) { FlagBadges.badge(pins[it].code, spec) }
    }

    /**
     * One frame: the globe centred on [lonDeg]/[latDeg], drawn at ([cx], [cy]) with radius [r] in a view of
     * [viewW] × [viewH] px.
     */
    fun draw(canvas: Canvas, lonDeg: Double, latDeg: Double, cx: Float, cy: Float, r: Float, viewW: Float, viewH: Float) {
        val p = palette ?: return
        val o = ortho.set(lonDeg, latDeg, cx, cy, r)
        oval.set(cx - r, cy - r, cx + r, cy + r)

        if (p.stars) drawStars(canvas, p, lonDeg, latDeg, viewW, viewH)
        p.paperShadow?.let {
            discPaint.shader = null
            discPaint.color = it
            canvas.drawCircle(cx + 5 * density, cy + 7 * density, r, discPaint)
        }
        glowShader?.let { s ->
            val gr = r * GLOW
            matrix.setScale(gr, gr); matrix.postTranslate(cx, cy); s.setLocalMatrix(matrix)
            discPaint.shader = s
            canvas.drawCircle(cx, cy, gr, discPaint)
            discPaint.shader = null
        }
        discPaint.color = p.sea
        canvas.drawCircle(cx, cy, r, discPaint)

        // Graticule.
        gridLines.clear()
        for (g in geometry.gridStart.indices) clipper.polyline(geometry.gridXyz, geometry.gridStart[g], geometry.gridSize[g], o, gridLines)
        linePaint.color = p.grid
        linePaint.strokeWidth = 0.8f * density
        gridLines.draw(canvas, linePaint)

        // Countries.
        landPath.rewind(); visitedPath.rewind(); dashPath.rewind(); dashSink.reset()
        lines.clear(); visitedLines.clear()
        val xyz = geometry.xyz
        for (ring in 0 until geometry.ringCount) {
            val state = states[geometry.ringCountry[ring]].toInt()
            val fill = if (state == 1) visitedFill else landFill
            val line: LineSink = if (state == 1) visitedLines else lines
            val start = geometry.ringStart[ring]
            val n = geometry.ringSize[ring]
            val drawn = clipper.ring(xyz, start, n, o, fill, line, geometry.seam, geometry.ringCap, ring * 4)
            if (state == 2 && drawn != RingClipper.HIDDEN) clipper.ring(xyz, start, n, o, null, dashSink, geometry.seam, geometry.ringCap, ring * 4)
        }
        fillPaint.color = p.land
        canvas.drawPath(landPath, fillPaint)
        fillPaint.color = p.visited
        canvas.drawPath(visitedPath, fillPaint)
        linePaint.color = p.line
        linePaint.strokeWidth = 0.7f * density
        lines.draw(canvas, linePaint)
        linePaint.color = p.visitedLine
        linePaint.strokeWidth = 1.1f * density
        visitedLines.draw(canvas, linePaint)
        if (!dashPath.isEmpty) {
            dashPaint.color = p.dashed
            dashPaint.strokeWidth = 1.5f * density
            if (dashPaint.pathEffect == null || dashDensity != density) {
                dashDensity = density
                dashPaint.pathEffect = DashPathEffect(floatArrayOf(4.5f * density, 3f * density), 0f)
            }
            canvas.drawPath(dashPath, dashPaint)
        }

        // Light from the upper left, shade towards the edge.
        lightShader?.let { s ->
            val lr = r * 1.25f
            matrix.setScale(lr, lr); matrix.postTranslate(cx - 0.4f * r, cy - 0.45f * r); s.setLocalMatrix(matrix)
            discPaint.shader = s
            canvas.drawCircle(cx, cy, r, discPaint)
        }
        limbShader?.let { s ->
            matrix.setScale(r, r); matrix.postTranslate(cx, cy); s.setLocalMatrix(matrix)
            discPaint.shader = s
            canvas.drawCircle(cx, cy, r, discPaint)
        }
        discPaint.shader = null
        rimPaint.color = p.rim
        rimPaint.strokeWidth = 1.6f * density
        canvas.drawCircle(cx, cy, r, rimPaint)

        drawPins(canvas, p, o, viewW, viewH)
    }

    private var dashDensity = 0f

    private fun drawStars(canvas: Canvas, p: GlobePalette, lonDeg: Double, latDeg: Double, w: Float, h: Float) {
        // A slow drift with the globe's turn, so the sky feels far away.
        val shiftX = (((-lonDeg / 360.0) * w * 0.35) % w).toFloat()
        val shiftY = ((latDeg / 180.0) * h * 0.15).toFloat()
        if (starPts.size < starX.size * 2) starPts = FloatArray(starX.size * 2)
        for (pass in 0..1) {
            val big = pass == 1
            var k = 0
            for (i in starX.indices) {
                if (starBig[i] != big) continue
                var x = starX[i] * w + shiftX
                if (x < 0) x += w
                if (x >= w) x -= w
                var y = starY[i] * h + shiftY
                if (y < 0) y += h
                if (y >= h) y -= h
                starPts[k++] = x
                starPts[k++] = y
            }
            starPaint.color = p.star
            starPaint.alpha = if (big) 200 else 110
            starPaint.strokeWidth = (if (big) 2.2f else 1.3f) * density
            canvas.drawPoints(starPts, 0, k, starPaint)
        }
    }

    private fun drawPins(canvas: Canvas, p: GlobePalette, o: Ortho, w: Float, h: Float) {
        visCount = 0
        if (pinAlpha <= 0.004f) return
        for (i in pins.indices) {
            val pin = pins[i]
            val d = o.depth(pin.x, pin.y, pin.z)
            if (d < 0.03f) continue
            val k = visCount++
            visIndex[k] = i
            anchorX[k] = o.screenX(o.x(pin.x, pin.y, pin.z))
            anchorY[k] = o.screenY(o.y(pin.x, pin.y, pin.z))
            visX[k] = anchorX[k]
            visY[k] = anchorY[k]
            visAlpha[k] = smooth(0.03f, 0.22f, d) * pinAlpha
            visScale[k] = 0.72f + 0.28f * smooth(0.03f, 0.4f, d)
            visLabelW[k] = if (showLabels && d > 0.3f) labelW[i] else 0f
        }
        if (visCount == 0) return
        PinLayout.separate(visX, visY, visCount, pinSize * 0.9f)
        labelPaint.textSize = LABEL_SP * density
        val lh = labelPaint.textSize * 1.25f
        PinLayout.labels(visX, visY, visCount, pinR, visLabelW, lh, 2 * density, w, h, visLabel)

        // Least important first, so the most important sit on top.
        leaderPaint.color = p.ink
        leaderPaint.strokeWidth = 1f * density
        for (k in visCount - 1 downTo 0) {
            val a = visAlpha[k]
            val dx = visX[k] - anchorX[k]
            val dy = visY[k] - anchorY[k]
            if (abs(dx) + abs(dy) > pinR * 0.7f) {
                leaderPaint.alpha = (110 * a).toInt()
                canvas.drawLine(anchorX[k], anchorY[k], visX[k], visY[k], leaderPaint)
                canvas.drawCircle(anchorX[k], anchorY[k], 1.6f * density, leaderPaint)
            }
            val bmp = badges.getOrNull(visIndex[k]) ?: continue
            val s = visScale[k]
            val half = bmp.width / 2f * s
            pinRect.set(visX[k] - half, visY[k] - half, visX[k] + half, visY[k] + half)
            pinPaint.alpha = (255 * a).toInt()
            canvas.drawBitmap(bmp, null, pinRect, pinPaint)
        }
        haloPaint.textSize = labelPaint.textSize
        haloPaint.strokeWidth = 3f * density
        haloPaint.color = p.labelHalo
        labelPaint.color = p.labelInk
        if (pinAlpha < 1f) {
            haloPaint.alpha = (haloPaint.alpha * pinAlpha).toInt()
            labelPaint.alpha = (labelPaint.alpha * pinAlpha).toInt()
        }
        for (k in 0 until visCount) {
            if (!visLabel[k]) continue
            val label = pins[visIndex[k]].label
            val baseline = visY[k] + pinR + 2 * density + labelPaint.textSize
            canvas.drawText(label, visX[k], baseline, haloPaint)
            canvas.drawText(label, visX[k], baseline, labelPaint)
        }
    }

    private val pinRect = RectF()

    /** The pin under a touch at ([x], [y]) in the last frame, top-most first, with [slop] px of grace. */
    fun pinAt(x: Float, y: Float, slop: Float): String? {
        val reach = pinR + slop
        for (k in 0 until visCount) {
            if (visAlpha[k] < 0.5f) continue
            val dx = x - visX[k]
            val dy = y - visY[k]
            if (dx * dx + dy * dy <= reach * reach) return pins[visIndex[k]].code
        }
        return null
    }

    /** Lat/lng under a screen point in the last frame, or false off the globe. */
    fun unproject(x: Float, y: Float, out: DoubleArray): Boolean = ortho.unproject(x, y, out)

    private fun smooth(a: Float, b: Float, v: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Line segments for drawLines, grown as needed and kept between frames. */
    private class LineBuffer : LineSink {
        var pts = FloatArray(4096)
        var n = 0
        fun clear() { n = 0 }
        override fun segment(x1: Float, y1: Float, x2: Float, y2: Float) {
            if (n + 4 > pts.size) pts = pts.copyOf(pts.size * 2)
            pts[n] = x1; pts[n + 1] = y1; pts[n + 2] = x2; pts[n + 3] = y2
            n += 4
        }
        fun draw(canvas: Canvas, paint: Paint) { if (n > 0) canvas.drawLines(pts, 0, n, paint) }
    }

    /** A clipped ring into a Path; rim stretches become arcs of the globe's circle. */
    private class PathFill(private val path: Path, private val oval: RectF) : FillSink {
        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
        override fun rimArc(startRad: Float, sweepRad: Float) {
            val sweep = Math.toDegrees(sweepRad.toDouble()).toFloat().coerceIn(-359.9f, 359.9f)
            path.arcTo(oval, Math.toDegrees(startRad.toDouble()).toFloat(), sweep, false)
        }
        override fun close() = path.close()
    }

    /** Outline segments joined into one path (so a dash pattern runs on along a border). */
    private class PathLines(private val path: Path) : LineSink {
        private var lx = Float.NaN
        private var ly = Float.NaN
        fun reset() { lx = Float.NaN; ly = Float.NaN }
        override fun segment(x1: Float, y1: Float, x2: Float, y2: Float) {
            if (x1 != lx || y1 != ly) path.moveTo(x1, y1)
            path.lineTo(x2, y2)
            lx = x2
            ly = y2
        }
    }

    companion object {
        private const val GLOW = 1.09f
        private const val LABEL_SP = 11f
    }
}

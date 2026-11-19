package com.justdataplease.boringtravel.globe

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** A flag pin: where it sits on the sphere and the name under it (in the phone's language). */
class GlobePin(val code: String, lat: Double, lng: Double, val label: String) {
    val x = GlobeMath.unitX(lat, lng)
    val y = GlobeMath.unitY(lat, lng)
    val z = GlobeMath.unitZ(lat)
}

/**
 * Draws the globe with Compose's canvas (Skia on iPhone and the desktop): the screen each frame, or a picture for the
 * share card. The Android app's globe/GlobeRenderer.kt, which draws into an android.graphics.Canvas, with the same
 * order, colours, sizes and rules: stars (Night Route), paper shadow (notebooks), air glow, sea, graticule, land,
 * visited land, outlines, planned/wished dashes, day-side light and edge shade, rim, then flag pins with their names.
 * The shapes, the clipping at the horizon and the pins' layout are Android's own (GlobeGeometry, RingClipper and
 * PinLayout, read in).
 *
 * The paths are kept and filled again each frame, so turning the globe allocates little. [text] measures the names
 * (in [labelStyle], the theme's body face) and draws the flag badges ([FlagBadges]).
 */
class GlobeRenderer(private val geometry: GlobeGeometry, private val density: Float, pinSizeDp: Float = 30f) {
    private val ortho = Ortho()
    private val clipper = RingClipper(geometry.maxRing + 4)

    private val landPath = Path().apply { fillType = PathFillType.EvenOdd }
    private val visitedPath = Path().apply { fillType = PathFillType.EvenOdd }
    private val dashPath = Path()
    private val linePath = Path()
    private val visitedLinePath = Path()
    private val gridPath = Path()
    private var oval = Rect.Zero

    private val landFill = PathFill(landPath) { oval }
    private val visitedFill = PathFill(visitedPath) { oval }
    private val dashSink = PathLines(dashPath)
    private val lines = PathLines(linePath)
    private val visitedLines = PathLines(visitedLinePath)
    private val gridLines = PathLines(gridPath)

    /** 0 = land, 1 = visited, 2 = planned or wished (dashed). */
    private var states = ByteArray(geometry.countryCount)

    private val pinSize = pinSizeDp * density
    private val pinR = pinSize / 2f
    private var pins: List<GlobePin> = emptyList()
    private var badges: Array<ImageBitmap?> = emptyArray()
    private var labels: Array<TextLayoutResult?> = emptyArray()
    private var halos: Array<TextLayoutResult?> = emptyArray()
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

    var showLabels = true
    /** The flag pins and their names fade by this; 1 = as always. */
    var pinAlpha = 1f
    var palette: GlobePalette? = null
        private set
    private var text: TextMeasurer? = null
    private var labelStyle: TextStyle = TextStyle.Default

    init {
        val rnd = Random(7)
        val n = 150
        starX = FloatArray(n) { rnd.nextFloat() }
        starY = FloatArray(n) { rnd.nextFloat() }
        starBig = BooleanArray(n) { rnd.nextFloat() < 0.12f }
    }

    /** The colours and the names' face; [measurer] lays out the names and the badges' emoji. */
    fun setPalette(p: GlobePalette, measurer: TextMeasurer, style: TextStyle) {
        if (p == palette && measurer === text && style == labelStyle) return
        palette = p
        text = measurer
        labelStyle = style
        measureLabels()
        rebuildBadges()
    }

    private fun measureLabels() {
        val m = text ?: return
        val p = palette ?: return
        val ink = labelStyle.copy(color = Color(p.labelInk))
        val halo = labelStyle.copy(color = Color(p.labelHalo), drawStyle = Stroke(3f * density, join = StrokeJoin.Round))
        labels = Array(pins.size) { if (pins[it].label.isEmpty()) null else m.measure(AnnotatedString(pins[it].label), ink, softWrap = false) }
        halos = Array(pins.size) { if (pins[it].label.isEmpty()) null else m.measure(AnnotatedString(pins[it].label), halo, softWrap = false) }
        labelW = FloatArray(pins.size) { (labels[it]?.size?.width ?: 0) + 4 * density }
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
        val m = text ?: return
        val spec = FlagBadges.Spec(
            sizePx = pinSize.toInt(), ringPx = 2.5f * density, shadowPx = 3f * density, tiltDeg = p.pinTilt,
            fallbackBg = p.chip, fallbackInk = p.ink,
        )
        badges = Array(pins.size) { FlagBadges.badge(pins[it].code, spec, m) }
    }

    /**
     * One frame: the globe centred on [lonDeg]/[latDeg], drawn at ([cx], [cy]) with radius [r] in a view of
     * [viewW] × [viewH] px.
     */
    fun draw(scope: DrawScope, lonDeg: Double, latDeg: Double, cx: Float, cy: Float, r: Float, viewW: Float, viewH: Float) = with(scope) {
        val p = palette ?: return@with
        val o = ortho.set(lonDeg, latDeg, cx, cy, r)
        oval = Rect(cx - r, cy - r, cx + r, cy + r)
        val center = Offset(cx, cy)

        if (p.stars) drawStars(p, lonDeg, latDeg, viewW, viewH)
        p.paperShadow?.let { drawCircle(Color(it), r, Offset(cx + 5 * density, cy + 7 * density)) }
        val gr = r * GLOW
        drawCircle(Brush.radialGradient(0f to Color(p.glow), 1f / GLOW to Color(p.glow), 1f to Color.Transparent, center = center, radius = gr), gr, center)
        drawCircle(Color(p.sea), r, center)

        // Graticule.
        gridPath.reset(); gridLines.reset()
        for (g in geometry.gridStart.indices) clipper.polyline(geometry.gridXyz, geometry.gridStart[g], geometry.gridSize[g], o, gridLines)
        drawPath(gridPath, Color(p.grid), style = Stroke(0.8f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Countries.
        landPath.reset(); visitedPath.reset(); dashPath.reset(); linePath.reset(); visitedLinePath.reset()
        dashSink.reset(); lines.reset(); visitedLines.reset()
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
        landPath.fillType = PathFillType.EvenOdd
        visitedPath.fillType = PathFillType.EvenOdd
        drawPath(landPath, Color(p.land))
        drawPath(visitedPath, Color(p.visited))
        drawPath(linePath, Color(p.line), style = Stroke(0.7f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(visitedLinePath, Color(p.visitedLine), style = Stroke(1.1f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (!dashPath.isEmpty) {
            drawPath(
                dashPath, Color(p.dashed),
                style = Stroke(1.5f * density, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.5f * density, 3f * density), 0f)),
            )
        }

        // Light from the upper left, shade towards the edge.
        drawCircle(Brush.radialGradient(0f to Color(p.light), 1f to Color.Transparent, center = Offset(cx - 0.4f * r, cy - 0.45f * r), radius = r * 1.25f), r, center)
        drawCircle(Brush.radialGradient(0f to Color.Transparent, 0.68f to Color.Transparent, 1f to Color(p.limb), center = center, radius = r), r, center)
        drawCircle(Color(p.rim), r, center, style = Stroke(1.6f * density))

        drawPins(p, o, viewW, viewH)
    }

    private fun DrawScope.drawStars(p: GlobePalette, lonDeg: Double, latDeg: Double, w: Float, h: Float) {
        // A slow drift with the globe's turn, so the sky feels far away.
        val shiftX = (((-lonDeg / 360.0) * w * 0.35) % w).toFloat()
        val shiftY = ((latDeg / 180.0) * h * 0.15).toFloat()
        for (pass in 0..1) {
            val big = pass == 1
            val pts = ArrayList<Offset>()
            for (i in starX.indices) {
                if (starBig[i] != big) continue
                var x = starX[i] * w + shiftX
                if (x < 0) x += w
                if (x >= w) x -= w
                var y = starY[i] * h + shiftY
                if (y < 0) y += h
                if (y >= h) y -= h
                pts += Offset(x, y)
            }
            drawPoints(pts, PointMode.Points, Color(p.star).copy(alpha = (if (big) 200 else 110) / 255f), strokeWidth = (if (big) 2.2f else 1.3f) * density, cap = StrokeCap.Round)
        }
    }

    private fun DrawScope.drawPins(p: GlobePalette, o: Ortho, w: Float, h: Float) {
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
            visLabelW[k] = if (showLabels && d > 0.3f) labelW.getOrElse(i) { 0f } else 0f
        }
        if (visCount == 0) return
        PinLayout.separate(visX, visY, visCount, pinSize * 0.9f)
        val textPx = labelStyle.fontSize.value * density * fontScale
        val lh = textPx * 1.25f
        PinLayout.labels(visX, visY, visCount, pinR, visLabelW, lh, 2 * density, w, h, visLabel)

        // Least important first, so the most important sit on top.
        val ink = Color(p.ink)
        for (k in visCount - 1 downTo 0) {
            val a = visAlpha[k]
            val dx = visX[k] - anchorX[k]
            val dy = visY[k] - anchorY[k]
            if (abs(dx) + abs(dy) > pinR * 0.7f) {
                val leader = ink.copy(alpha = 110 / 255f * a)
                drawLine(leader, Offset(anchorX[k], anchorY[k]), Offset(visX[k], visY[k]), strokeWidth = 1f * density, cap = StrokeCap.Round)
                drawCircle(leader, 1.6f * density, Offset(anchorX[k], anchorY[k]), style = Stroke(1f * density))
            }
            val bmp = badges.getOrNull(visIndex[k]) ?: continue
            val s = visScale[k]
            val half = bmp.width / 2f * s
            drawImage(
                bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height),
                dstOffset = IntOffset((visX[k] - half).roundToInt(), (visY[k] - half).roundToInt()),
                dstSize = IntSize((2 * half).roundToInt(), (2 * half).roundToInt()),
                alpha = a,
            )
        }
        for (k in 0 until visCount) {
            if (!visLabel[k]) continue
            val label = labels.getOrNull(visIndex[k]) ?: continue
            val halo = halos.getOrNull(visIndex[k])
            // The baseline sits a type size under the pin's foot, as Android draws its centred text.
            val top = visY[k] + pinR + 2 * density + textPx - label.firstBaseline
            val left = visX[k] - label.size.width / 2f
            if (halo != null) drawText(halo, topLeft = Offset(left, top), alpha = pinAlpha)
            drawText(label, topLeft = Offset(left, top), alpha = pinAlpha)
        }
    }

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

    /** A clipped ring into a Path; rim stretches become arcs of the globe's circle. */
    private class PathFill(private val path: Path, private val oval: () -> Rect) : FillSink {
        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
        override fun rimArc(startRad: Float, sweepRad: Float) {
            val sweep = (sweepRad * DEG).coerceIn(-359.9f, 359.9f)
            path.arcTo(oval(), startRad * DEG, sweep, false)
        }
        override fun close() = path.close()
    }

    /** Outline segments joined into one path (so a dash pattern runs on along a border, and a stroke draws them all). */
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
        /** Radians to degrees, as Java's Math.toDegrees multiplies. */
        private const val DEG = 57.29577951308232f
        /** The names' size, as Android's 11 sp. */
        const val LABEL_SP = 11f
    }
}

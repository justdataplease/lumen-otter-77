package com.justdataplease.boringtravel.ios

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.math.ceil

/*
 * The map's pins as the Android app's BtMap draws them into bitmaps (placePin, entryPin): a stop is a location pin in
 * the route colour with a tick, a plan place the same pin in the muted colour with a flag, a note an ink pin with a
 * light centre. Drawn here once for both: on the desktop's stand-in directly, and as PNGs MapLibre gets on iPhone.
 */

object MapPictures {
    /** [kind]'s picture with its top-left corner at the origin, [unit] pixels per point. */
    fun DrawScope.drawPin(kind: PinKind, c: MapColors, unit: Float) {
        if (kind == PinKind.Entry) drawEntryPin(c, unit) else drawPlacePin(c, planned = kind == PinKind.Plan, unit = unit)
    }

    private fun DrawScope.drawPlacePin(c: MapColors, planned: Boolean, unit: Float) {
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        val pin = Path().apply {
            moveTo(15f * unit, 36f * unit)
            cubicTo(10f * unit, 29f * unit, 2f * unit, 24f * unit, 2f * unit, 15f * unit)
            cubicTo(2f * unit, -1f * unit, 28f * unit, -1f * unit, 28f * unit, 15f * unit)
            cubicTo(28f * unit, 24f * unit, 20f * unit, 29f * unit, 15f * unit, 36f * unit)
            close()
        }
        drawPath(pin, Color(c.surface))
        val ink = Color(if (planned) c.muted else c.route)
        val stroke = Stroke(1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(pin, ink, style = stroke)
        if (planned) {
            drawLine(ink, p(11f, 10f), p(11f, 23f), strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
            drawPath(Path().apply {
                p(11f, 10f).let { moveTo(it.x, it.y) }
                listOf(p(21f, 10f), p(19f, 14f), p(21f, 18f), p(11f, 18f)).forEach { lineTo(it.x, it.y) }
            }, ink, style = stroke)
        } else {
            drawPath(Path().apply {
                p(9f, 16f).let { moveTo(it.x, it.y) }
                p(13f, 20f).let { lineTo(it.x, it.y) }
                p(21f, 11f).let { lineTo(it.x, it.y) }
            }, ink, style = stroke)
        }
    }

    private fun DrawScope.drawEntryPin(c: MapColors, unit: Float) {
        val w = 22f * unit
        val h = 29f * unit
        val stroke = 2f * unit
        val r = w / 2f - stroke
        val cx = w / 2f
        val cy = w / 2f
        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r)) }
        val tip = Path().apply {
            moveTo(cx - r * 0.7f, cy + r * 0.7f)
            lineTo(cx, h - stroke)
            lineTo(cx + r * 0.7f, cy + r * 0.7f)
            close()
        }
        val shape = Path().apply { op(circle, tip, PathOperation.Union) }
        drawPath(shape, Color(c.ink))
        drawPath(shape, Color(c.surface), style = Stroke(stroke))
        drawCircle(Color(c.surface), r * 0.36f, Offset(cx, cy))
    }

    /** [kind]'s picture at [scale] pixels per point, as a PNG (what MapLibre takes on iPhone). */
    fun png(kind: PinKind, c: MapColors, scale: Float): ByteArray {
        val w = ceil(kind.width * scale).toInt()
        val h = ceil(kind.height * scale).toInt()
        val image = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
            drawPin(kind, c, scale)
        }
        return Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)
    }

    /** [kind]'s picture with its top-left at [x], [y] (pixels). */
    fun DrawScope.drawPinAt(kind: PinKind, c: MapColors, unit: Float, x: Float, y: Float) {
        translate(x, y) { drawPin(kind, c, unit) }
    }
}

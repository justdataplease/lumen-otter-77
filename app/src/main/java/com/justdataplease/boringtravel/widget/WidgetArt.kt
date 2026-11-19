package com.justdataplease.boringtravel.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.justdataplease.boringtravel.ui.theme.Paper
import kotlin.math.floor
import kotlin.math.roundToInt

/** Where the dots or rules of a strip of paper go, centred so the pattern sits evenly on a short bar. */
object PaperGrid {
    /** Paper spacing on the widget, in dp: a little tighter than the screens' so a 60 dp bar still reads as paper. */
    const val DOT_STEP = 13f
    const val DOT_RADIUS = 0.95f
    const val LINE_STEP = 15f
    const val GRID_STEP = 12f
    const val RULE = 0.75f

    /** Dot centres along [length]: [step] apart, the outer ones the same distance from both ends. */
    fun dots(length: Float, step: Float): List<Float> {
        if (step <= 0f || length <= 0f) return emptyList()
        val n = floor(length / step).toInt().coerceAtLeast(1)
        val first = (length - (n - 1) * step) / 2f
        return List(n) { first + it * step }
    }

    /** Rules across [length]: [step] apart, centred, none closer than half a step to either end. */
    fun rules(length: Float, step: Float): List<Float> {
        if (step <= 0f || length <= step) return emptyList()
        val n = floor(length / step).toInt()
        val first = (length - (n - 1) * step) / 2f
        return List(n) { first + it * step }.filter { it >= step / 2f - 0.01f && it <= length - step / 2f + 0.01f }
    }
}

/**
 * The widgets' bitmaps (Glance can't draw on a Canvas): the bar on the look's paper, the Note pill and the round
 * buttons. Each is drawn at exactly the size it is shown, in pixels for [density].
 */
object WidgetArt {
    private fun px(dp: Float, density: Float) = dp * density

    /**
     * The bar: a flat shadow, the paper with its dots / rules / squares clipped to the corners, and a hairline edge.
     * The shadow sits inside the bitmap, so the paper is [QuickBarLayout.SHADOW_X] × [QuickBarLayout.SHADOW_Y] dp smaller than the bitmap.
     */
    fun bar(look: WidgetLook, widthDp: Float, heightDp: Float, density: Float): Bitmap {
        val w = px(widthDp, density).roundToInt().coerceAtLeast(1)
        val h = px(heightDp, density).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val dx = px(QuickBarLayout.SHADOW_X, density)
        val dy = px(QuickBarLayout.SHADOW_Y, density)
        val paper = RectF(0f, 0f, w - dx, h - dy)
        val radius = if (look.pill) paper.height() / 2f else px(CORNER, density)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // A flat shadow like the notebooks' cards (offset, no blur), softer and closer in the round looks.
        fill.color = look.shadow.toArgb()
        canvas.drawRoundRect(RectF(paper.left + dx, paper.top + dy, paper.right + dx, paper.bottom + dy), radius, radius, fill)

        fill.color = look.paper.toArgb()
        canvas.drawRoundRect(paper, radius, radius, fill)

        look.textureColor?.let { ink ->
            val save = canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(paper, radius, radius, Path.Direction.CW) })
            texture(canvas, look.texture, ink, paper, density, look.dotStep)
            canvas.restoreToCount(save)
        }

        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(look.edgeWidth, density)
            color = look.edge.toArgb()
        }
        val half = edge.strokeWidth / 2f
        canvas.drawRoundRect(RectF(paper.left + half, paper.top + half, paper.right - half, paper.bottom - half), radius - half, radius - half, edge)
        return bmp
    }

    private fun texture(canvas: Canvas, paper: Paper, ink: Color, r: RectF, density: Float, dotStep: Float = PaperGrid.DOT_STEP) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb() }
        when (paper) {
            Paper.Dots -> {
                val step = px(dotStep, density)
                val dot = px(if (dotStep < PaperGrid.DOT_STEP) PaperGrid.DOT_RADIUS * 0.9f else PaperGrid.DOT_RADIUS, density)
                for (y in PaperGrid.dots(r.height(), step)) for (x in PaperGrid.dots(r.width(), step)) {
                    canvas.drawCircle(r.left + x, r.top + y, dot, p)
                }
            }
            Paper.Lines -> {
                // Whole pixels, so every rule is equally crisp (as on the screens).
                val stroke = px(PaperGrid.RULE, density).roundToInt().coerceAtLeast(1).toFloat()
                for (y in PaperGrid.rules(r.height(), px(PaperGrid.LINE_STEP, density))) {
                    val top = (r.top + y).roundToInt().toFloat()
                    canvas.drawRect(r.left, top, r.right, top + stroke, p)
                }
            }
            Paper.Grid -> {
                val stroke = px(PaperGrid.RULE, density).roundToInt().coerceAtLeast(1).toFloat()
                val step = px(PaperGrid.GRID_STEP, density)
                // One path, so the crossings aren't drawn twice (darker dots).
                val path = Path()
                for (y in PaperGrid.rules(r.height(), step)) {
                    val top = (r.top + y).roundToInt().toFloat()
                    path.addRect(r.left, top, r.right, top + stroke, Path.Direction.CW)
                }
                for (x in PaperGrid.rules(r.width(), step)) {
                    val left = (r.left + x).roundToInt().toFloat()
                    path.addRect(left, r.top, left + stroke, r.bottom, Path.Direction.CW)
                }
                path.fillType = Path.FillType.WINDING
                canvas.drawPath(path, p)
            }
            Paper.Plain -> Unit
        }
    }

    /**
     * The Note pill: the theme's active-tab colour leaning [WidgetLook.noteTilt] degrees with small corners, or a
     * plain round pill in Trail.
     */
    fun note(look: WidgetLook, widthDp: Float, heightDp: Float, density: Float): Bitmap {
        val w = px(widthDp, density).roundToInt().coerceAtLeast(1)
        val h = px(heightDp, density).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.note.toArgb() }
        if (look.pill) {
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, p)
        } else {
            // Leave room for the lean inside the bitmap.
            val inset = px(2.5f, density)
            val r = RectF(inset * 0.6f, inset, w - inset * 0.6f, h - inset)
            canvas.save()
            canvas.rotate(look.noteTilt, w / 2f, h / 2f)
            canvas.drawRoundRect(r, px(8f, density), px(8f, density), p)
            canvas.restore()
        }
        return bmp
    }

    /** A round button: the theme's button colour with its edge (Postcard Pop's ink, a hairline on paper). */
    fun round(look: WidgetLook, sizeDp: Float, density: Float): Bitmap {
        val s = px(sizeDp, density).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = s / 2f
        canvas.drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.button.toArgb() })
        look.buttonEdge?.let { edge ->
            val stroke = px(look.edgeWidth, density)
            canvas.drawCircle(c, c, c - stroke / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = edge.toArgb()
            })
        }
        return bmp
    }

    /** Paper-cut corner of the notebook bars, in dp. */
    const val CORNER = 12f
}

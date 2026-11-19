package com.justdataplease.boringtravel.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * [PlaneMark] on an Android canvas: BrandMark on screens (through the Compose canvas), the widgets (Glance shows
 * bitmaps), the share card and the clip's watermark and end card.
 */
object PlaneMarkBitmap {
    /** The tile at [left], [top], [size] px square: the rounded yellow, the trail and the navy-edged plane. */
    fun draw(canvas: Canvas, left: Float, top: Float, size: Float, colors: PlaneMark.Colors) {
        val k = size / PlaneMark.VIEW_SIZE
        fun x(v: Float) = left + PlaneMark.local(v) * k
        fun y(v: Float) = top + PlaneMark.local(v) * k
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = colors.sky
        val r = size * PlaneMark.CORNER
        canvas.drawRoundRect(RectF(left, top, left + size, top + size), r, r, paint)
        paint.color = colors.ink
        val d = PlaneMark.DOTS
        for (i in d.indices step 2) canvas.drawCircle(x(d[i]), y(d[i + 1]), PlaneMark.DOT_R * PlaneMark.SCALE * k, paint)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = PlaneMark.STROKE * PlaneMark.SCALE * k
            color = colors.ink
        }
        for ((tri, fill) in colors.faces) {
            val path = Path()
            path.moveTo(x(tri[0]), y(tri[1]))
            path.lineTo(x(tri[2]), y(tri[3]))
            path.lineTo(x(tri[4]), y(tri[5]))
            path.close()
            paint.color = fill
            canvas.drawPath(path, paint)
            canvas.drawPath(path, edge)
        }
        edge.strokeCap = Paint.Cap.ROUND
        edge.strokeWidth = PlaneMark.RULE_W * PlaneMark.SCALE * k
        edge.color = colors.rule
        val rl = PlaneMark.RULES
        for (i in rl.indices step 4) canvas.drawLine(x(rl[i]), y(rl[i + 1]), x(rl[i + 2]), y(rl[i + 3]), edge)
    }

    /** The tile as a bitmap [px] square. */
    fun bitmap(px: Int, colors: PlaneMark.Colors): Bitmap {
        val s = px.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), 0f, 0f, s.toFloat(), colors)
        return bmp
    }
}

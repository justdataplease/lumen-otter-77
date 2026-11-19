package com.justdataplease.boringtravel.art

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Paints a drawn picture ([SceneShapes]) with plain android.graphics: the 240×160 canvas scaled to fill any box,
 * centred and cropped, in one theme's [ScenePalette]. No frame: the theme draws frames around pictures.
 * [mirror] flips it left to right (the demo uses it so two pictures of one kind differ).
 */
object SceneArt {
    fun draw(canvas: Canvas, w: Float, h: Float, kind: SceneKind, palette: ScenePalette, mirror: Boolean = false) {
        if (w <= 0f || h <= 0f) return
        val fit = SceneMath.slice(w, h)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.translate(fit.dx, fit.dy)
        canvas.scale(fit.scale, fit.scale)
        if (mirror) canvas.scale(-1f, 1f, SceneMath.VIEW_W / 2f, SceneMath.VIEW_H / 2f)
        SceneShapes.background(palette)?.let { fill.color = colour(it); canvas.drawRect(0f, 0f, SceneMath.VIEW_W, SceneMath.VIEW_H, fill) }
        for (shape in SceneShapes.painted(kind, palette)) {
            val outline = SceneShapes.strokeOf(shape, palette)
            shape.fill?.let { fill.color = colour(it) }
            outline?.let { (c, width) -> pen.color = colour(c); pen.strokeWidth = width }
            when (shape) {
                is Shape.Rect -> {
                    canvas.drawRect(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h, fill)
                    if (outline != null) canvas.drawRect(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h, pen)
                }
                is Shape.Circle -> {
                    canvas.drawCircle(shape.cx, shape.cy, shape.r, fill)
                    if (outline != null) canvas.drawCircle(shape.cx, shape.cy, shape.r, pen)
                }
                is Shape.Poly -> {
                    val path = Path().apply {
                        shape.pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
                        close()
                    }
                    canvas.drawPath(path, fill)
                    if (outline != null) canvas.drawPath(path, pen)
                }
                is Shape.PathD -> {
                    val path = path(shape.d)
                    canvas.drawPath(path, fill)
                    if (outline != null) canvas.drawPath(path, pen)
                }
                is Shape.Line -> if (outline != null) canvas.drawLine(shape.x1, shape.y1, shape.x2, shape.y2, pen)
            }
        }
        canvas.restore()
    }

    private fun colour(hex: String): Int = (0xFF000000 or hex.removePrefix("#").toLong(16)).toInt()

    private val paths = HashMap<String, List<SvgPath.Op>>()

    private fun path(d: String): Path {
        val ops = synchronized(paths) { paths.getOrPut(d) { SvgPath.parse(d) } }
        return Path().apply {
            for (op in ops) when (op) {
                is SvgPath.Op.Move -> moveTo(op.x, op.y)
                is SvgPath.Op.LineTo -> lineTo(op.x, op.y)
                is SvgPath.Op.Cubic -> cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
                SvgPath.Op.Close -> close()
            }
        }
    }
}

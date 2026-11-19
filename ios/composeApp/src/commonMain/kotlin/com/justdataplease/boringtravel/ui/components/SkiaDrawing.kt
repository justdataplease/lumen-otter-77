package com.justdataplease.boringtravel.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaPaint
import com.justdataplease.boringtravel.art.PlaneMark
import com.justdataplease.boringtravel.ui.theme.BtShadow
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

/*
 * The iPhone app's version of the Android app's ui/components/AndroidDrawing.kt: the two pieces of Components.kt that
 * draw on the platform's own canvas. Same names and the same result, drawn with Skia, which Compose uses on iPhone.
 */

/**
 * A CSS box shadow behind the element: offset, blur, spread. Like CSS, it is never painted under the element itself,
 * so a see-through button keeps a see-through middle. Blur 0 is a flat, hard shadow (Postcard Pop).
 */
fun Modifier.btShadow(shadow: BtShadow?, shape: Shape): Modifier = if (shadow == null) this else drawWithCache {
    val spread = shadow.spread.toPx()
    val w = size.width + 2 * spread
    val h = size.height + 2 * spread
    val own = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val cast = if (w > 0f && h > 0f) Path().apply { addOutline(shape.createOutline(Size(w, h), layoutDirection, this@drawWithCache)) } else null
    val paint = Paint().apply {
        isAntiAlias = true
        color = shadow.color
        val blur = shadow.blur.toPx()
        // The blur Android's BlurMaskFilter draws at skiaBlurRadius(blur), as the sigma Skia takes directly.
        if (blur > 0f) skiaPaint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 0.57735f * skiaBlurRadius(blur) + 0.5f)
    }
    val dx = shadow.x.toPx() - spread
    val dy = shadow.y.toPx() - spread
    onDrawBehind {
        if (cast == null) return@onDrawBehind
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.clipPath(own, ClipOp.Difference)
            canvas.translate(dx, dy)
            canvas.drawPath(cast, paint)
            canvas.restore()
        }
    }
}

/** [PlaneMark] filling this square: the rounded yellow tile, the trail and the plane (Android: PlaneMarkBitmap.draw). */
fun DrawScope.drawPlaneMark(colors: PlaneMark.Colors) {
    val side = size.minDimension
    val k = side / PlaneMark.VIEW_SIZE
    fun at(x: Float, y: Float) = Offset(PlaneMark.local(x) * k, PlaneMark.local(y) * k)
    val corner = side * PlaneMark.CORNER
    drawRoundRect(Color(colors.sky), size = Size(side, side), cornerRadius = CornerRadius(corner, corner))
    val ink = Color(colors.ink)
    val dots = PlaneMark.DOTS
    for (i in dots.indices step 2) drawCircle(ink, PlaneMark.DOT_R * PlaneMark.SCALE * k, at(dots[i], dots[i + 1]))
    val edge = Stroke(PlaneMark.STROKE * PlaneMark.SCALE * k, join = StrokeJoin.Round)
    for ((face, fill) in colors.faces) {
        val path = Path().apply {
            at(face[0], face[1]).let { moveTo(it.x, it.y) }
            at(face[2], face[3]).let { lineTo(it.x, it.y) }
            at(face[4], face[5]).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, Color(fill))
        drawPath(path, ink, style = edge)
    }
    val rules = PlaneMark.RULES
    for (i in rules.indices step 4) {
        drawLine(Color(colors.rule), at(rules[i], rules[i + 1]), at(rules[i + 2], rules[i + 3]),
            strokeWidth = PlaneMark.RULE_W * PlaneMark.SCALE * k, cap = StrokeCap.Round)
    }
}

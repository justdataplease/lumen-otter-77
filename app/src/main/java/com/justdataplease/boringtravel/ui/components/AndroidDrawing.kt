package com.justdataplease.boringtravel.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.justdataplease.boringtravel.art.PlaneMark
import com.justdataplease.boringtravel.art.PlaneMarkBitmap
import com.justdataplease.boringtravel.ui.theme.BtShadow

/*
 * The two pieces of Components.kt that draw on Android's own canvas. They sit here so that Components.kt itself is
 * plain Compose, which the iPhone app compiles as it is (ios-boringtravel; it has its own version of this file).
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
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = shadow.color.toArgb()
        val blur = shadow.blur.toPx()
        if (blur > 0f) maskFilter = BlurMaskFilter(skiaBlurRadius(blur), BlurMaskFilter.Blur.NORMAL)
    }
    val dx = shadow.x.toPx() - spread
    val dy = shadow.y.toPx() - spread
    onDrawBehind {
        if (cast == null) return@onDrawBehind
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            val save = c.save()
            c.clipOutPath(own.asAndroidPath())
            c.translate(dx, dy)
            c.drawPath(cast.asAndroidPath(), paint)
            c.restoreToCount(save)
        }
    }
}

/** [PlaneMark] filling this square: the rounded yellow tile, the trail and the plane (drawn by [PlaneMarkBitmap]). */
fun DrawScope.drawPlaneMark(colors: PlaneMark.Colors) {
    drawIntoCanvas { PlaneMarkBitmap.draw(it.nativeCanvas, 0f, 0f, size.minDimension, colors) }
}

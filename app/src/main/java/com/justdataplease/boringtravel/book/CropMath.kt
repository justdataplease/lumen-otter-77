package com.justdataplease.boringtravel.book

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A rectangle; in mm on a page, or in fractions of a photo ([CropMath.visible]). */
data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right: Double get() = x + w
    val bottom: Double get() = y + h
    val cx: Double get() = x + w / 2
    val cy: Double get() = y + h / 2
    val aspect: Double get() = if (h <= 0.0) 1.0 else w / h

    fun inset(d: Double): Box = Box(x + d, y + d, (w - 2 * d).coerceAtLeast(0.0), (h - 2 * d).coerceAtLeast(0.0))
    fun inset(left: Double, top: Double, right: Double, bottom: Double): Box =
        Box(x + left, y + top, (w - left - right).coerceAtLeast(0.0), (h - top - bottom).coerceAtLeast(0.0))
    fun offset(dx: Double, dy: Double): Box = Box(x + dx, y + dy, w, h)

    /** The largest box of [aspect] inside this one, centred (then moved by [alignX]/[alignY] in 0..1). */
    fun fit(aspect: Double, alignX: Double = 0.5, alignY: Double = 0.5): Box {
        if (aspect <= 0.0 || w <= 0.0 || h <= 0.0) return this
        val fw: Double
        val fh: Double
        if (w / h > aspect) { fh = h; fw = h * aspect } else { fw = w; fh = w / aspect }
        return Box(x + (w - fw) * alignX, y + (h - fh) * alignY, fw, fh)
    }

    fun contains(px: Double, py: Double): Boolean = px >= x && px <= right && py >= y && py <= bottom
}

/**
 * Pan and zoom of a photo in a frame. The frame shows the biggest part of the photo with the frame's shape (zoom 1),
 * or a smaller part around (cx, cy) as the zoom grows; the part shown always stays inside the photo.
 */
object CropMath {
    const val MAX_ZOOM = 5.0

    /** Print resolution aimed for, in dots per inch at the size the photo is placed. */
    const val PRINT_DPI = 300.0

    /** Never decode more than this on the longest side (memory on small phones). */
    const val MAX_PRINT_PX = 4800

    fun clamp(c: Crop): Crop = Crop(
        zoom = c.zoom.takeIf { it.isFinite() }?.coerceIn(1.0, MAX_ZOOM) ?: 1.0,
        cx = c.cx.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5,
        cy = c.cy.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5,
    )

    /**
     * The part of an [imgW] × [imgH] photo a frame of [frameAspect] shows, as fractions of the photo (x, y, w, h in
     * 0..1). Unknown photo sizes count as 4:3.
     */
    fun visible(imgW: Int, imgH: Int, frameAspect: Double, crop: Crop): Box {
        val c = clamp(crop)
        val imgAspect = if (imgW > 0 && imgH > 0) imgW.toDouble() / imgH else 4.0 / 3.0
        val a = if (frameAspect > 0 && frameAspect.isFinite()) frameAspect else imgAspect
        var w: Double
        var h: Double
        if (imgAspect > a) { h = 1.0; w = a / imgAspect } else { w = 1.0; h = imgAspect / a }
        w /= c.zoom
        h /= c.zoom
        val x = (c.cx - w / 2).coerceIn(0.0, 1.0 - w)
        val y = (c.cy - h / 2).coerceIn(0.0, 1.0 - h)
        return Box(x, y, w, h)
    }

    /** The crop with its centre moved so the shown part stays inside the photo (what [visible] would show). */
    fun settle(imgW: Int, imgH: Int, frameAspect: Double, crop: Crop): Crop {
        val v = visible(imgW, imgH, frameAspect, crop)
        return Crop(clamp(crop).zoom, v.cx, v.cy)
    }

    /**
     * Moves the shown part by a drag of ([dxFrame], [dyFrame]) in fractions of the frame (finger right = photo
     * right = the view moves left) and zooms by [scale] around the centre.
     */
    fun pan(imgW: Int, imgH: Int, frameAspect: Double, crop: Crop, dxFrame: Double, dyFrame: Double, scale: Double = 1.0): Crop {
        val v = visible(imgW, imgH, frameAspect, crop)
        val zoom = (crop.zoom * scale).coerceIn(1.0, MAX_ZOOM)
        return settle(imgW, imgH, frameAspect, Crop(zoom, v.cx - dxFrame * v.w, v.cy - dyFrame * v.h))
    }

    /**
     * Where the whole photo goes so that [v] fills the frame: left, top, width and height in % of the frame (CSS on an
     * absolutely placed `<img>` inside a frame that clips).
     */
    fun cssPercent(v: Box): DoubleArray {
        val w = 100.0 / v.w
        val h = 100.0 / v.h
        return doubleArrayOf(-v.x * w, -v.y * h, w, h)
    }

    /**
     * Pixels the photo needs on its longest side so the shown part prints at [dpi] in a frame of [frameWmm] ×
     * [frameHmm]. Never more than the photo has, nor than [MAX_PRINT_PX].
     */
    fun printPx(imgW: Int, imgH: Int, frameWmm: Double, frameHmm: Double, crop: Crop, dpi: Double = PRINT_DPI): Int {
        val v = visible(imgW, imgH, if (frameHmm > 0) frameWmm / frameHmm else 1.0, crop)
        val needAcross = frameWmm / 25.4 * dpi / v.w // pixels across the whole photo's width
        if (imgW <= 0 || imgH <= 0) return min(ceil(needAcross).toInt(), MAX_PRINT_PX).coerceAtLeast(1)
        val wantLongest = needAcross / imgW * max(imgW, imgH)
        return min(min(ceil(wantLongest).toInt(), max(imgW, imgH)), MAX_PRINT_PX).coerceAtLeast(1)
    }

    /** The resolution the shown part prints at, in dpi, from a photo of [imgW] × [imgH] pixels (for "may look soft"). */
    fun effectiveDpi(imgW: Int, imgH: Int, frameWmm: Double, frameHmm: Double, crop: Crop): Double {
        if (imgW <= 0 || imgH <= 0 || frameWmm <= 0) return PRINT_DPI
        val v = visible(imgW, imgH, frameWmm / frameHmm, crop)
        return v.w * imgW / (frameWmm / 25.4)
    }
}

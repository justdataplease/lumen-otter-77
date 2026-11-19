package com.justdataplease.boringtravel.globe

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.util.LruCache
import com.justdataplease.boringtravel.geo.CountryInfo

/**
 * Round sticker badges with the phone's own flag emoji: a white ring, a soft shadow and, in the notebook looks, a
 * small tilt. Drawn once per flag and size into a bitmap, so the globe only blits them per frame. A phone without the
 * flag glyph (or a code with no flag) gets the two letters instead.
 */
object FlagBadges {
    data class Spec(val sizePx: Int, val ringPx: Float, val shadowPx: Float, val tiltDeg: Float, val fallbackBg: Int, val fallbackInk: Int)

    /** The bitmap is [Spec.sizePx] plus a margin for the shadow; the flag's centre is the bitmap's centre. */
    fun margin(spec: Spec): Int = (spec.shadowPx * 2.2f).toInt() + 2

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun badge(code: String, spec: Spec): Bitmap {
        val key = "$code|$spec"
        cache.get(key)?.let { return it }
        val bmp = draw(code, spec)
        cache.put(key, bmp)
        return bmp
    }

    /** Whether this phone can draw [code]'s flag (else the badge shows the letters). */
    fun hasFlag(code: String): Boolean {
        val emoji = CountryInfo.flagEmoji(code) ?: return false
        return runCatching { Paint().hasGlyph(emoji) }.getOrDefault(false)
    }

    private fun draw(code: String, spec: Spec): Bitmap {
        val m = margin(spec)
        val side = spec.sizePx + 2 * m
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = side / 2f
        val outer = spec.sizePx / 2f
        val inner = outer - spec.ringPx
        canvas.rotate(spec.tiltDeg, c, c)

        if (spec.shadowPx > 0f) {
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(90, 0, 0, 0)
                maskFilter = BlurMaskFilter(spec.shadowPx, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(c, c + spec.shadowPx * 0.45f, outer, shadow)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(c, c, outer, paint)

        canvas.save()
        val clip = Path().apply { addCircle(c, c, inner, Path.Direction.CW) }
        canvas.clipPath(clip)
        val emoji = CountryInfo.flagEmoji(code)
        if (emoji != null && hasFlag(code)) {
            paint.color = Color.WHITE
            canvas.drawCircle(c, c, inner, paint)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = inner * 4f }
            val bounds = Rect()
            text.getTextBounds(emoji, 0, emoji.length, bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                // Cover the circle with the flag's middle (emoji flags are wider than tall, some wave a little).
                val scale = (2f * inner * 1.18f) / minOf(bounds.width(), bounds.height())
                canvas.save()
                canvas.translate(c, c)
                canvas.scale(scale, scale)
                canvas.drawText(emoji, -bounds.exactCenterX(), -bounds.exactCenterY(), text)
                canvas.restore()
            }
        } else {
            paint.color = spec.fallbackBg
            canvas.drawCircle(c, c, inner, paint)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = spec.fallbackInk
                textSize = inner * 0.8f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val letters = code.take(2).uppercase()
            val b = Rect()
            text.getTextBounds(letters, 0, letters.length, b)
            canvas.drawText(letters, c, c - b.exactCenterY(), text)
        }
        canvas.restore()

        // A hairline so a white flag (Japan) still reads on white paper.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (spec.ringPx * 0.18f).coerceAtLeast(1f)
        paint.color = Color.argb(40, 0, 0, 0)
        canvas.drawCircle(c, c, outer - paint.strokeWidth / 2, paint)
        bmp.prepareToDraw()
        return bmp
    }
}

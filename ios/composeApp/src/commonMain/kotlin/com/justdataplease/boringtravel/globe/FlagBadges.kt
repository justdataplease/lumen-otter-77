package com.justdataplease.boringtravel.globe

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.geo.CountryInfo
import com.justdataplease.boringtravel.ios.flagEmojiShown
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

/**
 * Round sticker badges with the phone's own flag emoji: a white ring, a soft shadow and, in the notebook looks, a
 * small tilt (the Android app's globe/FlagBadges.kt, which paints them with android.graphics; here with Compose's
 * canvas, Skia underneath). Drawn once per flag and size into a picture, so the globe only places them per frame. A
 * phone that doesn't draw flag emoji (the desktop) gets the two letters instead, as Android does without the glyph.
 */
object FlagBadges {
    data class Spec(val sizePx: Int, val ringPx: Float, val shadowPx: Float, val tiltDeg: Float, val fallbackBg: Int, val fallbackInk: Int)

    /** The picture is [Spec.sizePx] plus a margin for the shadow; the flag's centre is the picture's centre. */
    fun margin(spec: Spec): Int = (spec.shadowPx * 2.2f).toInt() + 2

    /** Twelve megabytes of badges, as Android's cache. */
    private const val CACHE_BYTES = 12 * 1024 * 1024
    private val cache = LinkedHashMap<String, ImageBitmap>()
    private var cachedBytes = 0

    /**
     * Apple Color Emoji draws a flag about three quarters of the type size tall: the size that makes the flag's middle
     * cover the circle as Android's text bounds do. Only seen on the desktop (which has no flag glyphs): unverified.
     */
    private const val FLAG_HEIGHT_EM = 0.75f

    fun badge(code: String, spec: Spec, text: TextMeasurer): ImageBitmap {
        val key = "$code|$spec"
        cache.remove(key)?.let { cache[key] = it; return it }
        val bmp = draw(code, spec, text)
        cache[key] = bmp
        cachedBytes += bmp.width * bmp.height * 4
        while (cachedBytes > CACHE_BYTES && cache.size > 1) {
            val oldest = cache.keys.first()
            cache.remove(oldest)?.let { cachedBytes -= it.width * it.height * 4 }
        }
        return bmp
    }

    /** Whether this phone draws [code]'s flag (else the badge shows the letters). */
    fun hasFlag(code: String): Boolean = flagEmojiShown && CountryInfo.flagEmoji(code) != null

    private fun draw(code: String, spec: Spec, text: TextMeasurer): ImageBitmap {
        val m = margin(spec)
        val side = spec.sizePx + 2 * m
        val bmp = ImageBitmap(side, side)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(side.toFloat(), side.toFloat())) {
            val c = side / 2f
            val center = Offset(c, c)
            val outer = spec.sizePx / 2f
            val inner = outer - spec.ringPx
            rotate(spec.tiltDeg, center) {
                if (spec.shadowPx > 0f) drawIntoCanvas { canvas ->
                    val shadow = Paint().apply {
                        isAntiAlias = true
                        color = Color(0, 0, 0, 90)
                        // Android's BlurMaskFilter radius as the sigma Skia takes.
                        skiaPaint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 0.57735f * spec.shadowPx + 0.5f)
                    }
                    canvas.drawCircle(Offset(c, c + spec.shadowPx * 0.45f), outer, shadow)
                }
                drawCircle(Color.White, outer, center)
                val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, inner)) }
                clipPath(clip) {
                    val emoji = CountryInfo.flagEmoji(code)
                    if (emoji != null && hasFlag(code)) {
                        drawCircle(Color.White, inner, center)
                        // Cover the circle with the flag's middle (emoji flags are wider than tall, some wave a little).
                        val size = 2f * inner * 1.18f / FLAG_HEIGHT_EM
                        val layout = text.measure(AnnotatedString(emoji), TextStyle(fontSize = size.sp), density = Density(1f), softWrap = false)
                        drawText(layout, topLeft = Offset(c - layout.size.width / 2f, c - layout.size.height / 2f))
                    } else {
                        drawCircle(Color(spec.fallbackBg), inner, center)
                        val letters = code.take(2).uppercase()
                        val layout = text.measure(
                            AnnotatedString(letters),
                            TextStyle(fontSize = (inner * 0.8f).sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(spec.fallbackInk)),
                            density = Density(1f), softWrap = false,
                        )
                        drawText(layout, topLeft = Offset(c - layout.size.width / 2f, c - layout.size.height / 2f))
                    }
                }
                // A hairline so a white flag (Japan) still reads on white paper.
                val w = (spec.ringPx * 0.18f).coerceAtLeast(1f)
                drawCircle(Color(0, 0, 0, 40), outer - w / 2, center, style = Stroke(w))
            }
        }
        return bmp
    }

}

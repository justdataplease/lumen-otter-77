package com.justdataplease.boringtravel.compat.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * android.text and the text half of android.graphics, as the trip video sets its words: a face, its size in pixels,
 * letter spacing in ems, OpenType features ("tnum"), a shadow layer as a halo, StaticLayout's centred lines with an
 * ellipsis, TextUtils.ellipsize. Laid out and drawn by Compose's own text engine (Skia's paragraphs), so the faces are
 * the app's bundled fonts with the phone's fonts filling in letters a face lacks (Greek in Caveat), as on Android.
 */

/** A face: one of the app's font families at a weight (what Compose's resolver gives the Android app). */
class Typeface(val family: FontFamily, val weight: FontWeight) {
    override fun equals(other: Any?): Boolean = other is Typeface && other.family == family && other.weight == weight
    override fun hashCode(): Int = family.hashCode() * 31 + weight.hashCode()

    companion object {
        /** Android's system face, Roboto: the bundled one (BtRoboto), as on the screens. */
        val DEFAULT = Typeface(com.justdataplease.boringtravel.ui.theme.BtRoboto.family, FontWeight.Normal)
        val DEFAULT_BOLD = Typeface(com.justdataplease.boringtravel.ui.theme.BtRoboto.family, FontWeight.Bold)
    }
}

open class TextPaint(flags: Int = 0) : Paint(flags)

/** One text engine per paint (a renderer draws on one thread; Compose's measurer keeps a small cache of its own). */
internal class TextMeasure {
    private val measurer = TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr, cacheSize = 24)

    fun style(paint: Paint, align: TextAlign = TextAlign.Start): TextStyle {
        val face = paint.typeface ?: Typeface.DEFAULT
        return TextStyle(
            fontFamily = face.family,
            fontWeight = face.weight,
            fontSize = paint.textSize.sp,
            letterSpacing = paint.letterSpacing.em,
            fontFeatureSettings = paint.fontFeatureSettings,
            textAlign = align,
        )
    }

    /** [text] on one line, as wide as it is. */
    fun line(paint: Paint, text: String): TextLayoutResult =
        measurer.measure(AnnotatedString(text), style(paint), overflow = TextOverflow.Clip, softWrap = false, maxLines = 1)

    fun width(paint: Paint, text: String): Float {
        if (text.isEmpty()) return 0f
        val l = line(paint, text)
        return l.getLineRight(0) - l.getLineLeft(0)
    }

    fun metrics(paint: Paint): Paint.FontMetrics {
        val l = line(paint, "Hg")
        val baseline = l.firstBaseline
        val height = l.multiParagraph.height
        return Paint.FontMetrics().apply {
            ascent = -baseline
            descent = height - baseline
            top = ascent
            bottom = descent
        }
    }

    /** Wrapped lines [width] wide, centred or not, up to [maxLines] with an ellipsis. */
    fun block(paint: Paint, text: String, width: Int, maxLines: Int, align: TextAlign, ellipsize: Boolean): TextLayoutResult =
        measurer.measure(
            AnnotatedString(text), style(paint, align),
            overflow = if (ellipsize) TextOverflow.Ellipsis else TextOverflow.Clip,
            softWrap = true, maxLines = maxLines.coerceAtLeast(1),
            constraints = Constraints.fixedWidth(width.coerceAtLeast(1)),
        )

    fun draw(canvas: Canvas, paint: Paint, text: String, x: Float, y: Float) {
        if (text.isEmpty()) return
        val l = line(paint, text)
        val w = l.getLineRight(0) - l.getLineLeft(0)
        val left = when (paint.textAlign) {
            Paint.Align.LEFT -> x
            Paint.Align.CENTER -> x - w / 2f
            Paint.Align.RIGHT -> x - w
        } - l.getLineLeft(0)
        paintAt(canvas, paint, l, left, y - l.firstBaseline)
    }

    companion object {
        /** The font loader for every paint: the app's fonts are read once at start (BtFontFiles), so it only matches faces. */
        val resolver by lazy { createFontFamilyResolver() }

        /** [layout] with its top left at ([left], [top]), in the paint's colour now and with its shadow layer as a halo. */
        fun paintAt(canvas: Canvas, paint: Paint, layout: TextLayoutResult, left: Float, top: Float) {
            val shadow = if (paint.hasShadow) {
                Shadow(Color(paint.shadowArgb), Offset(paint.shadowDx, paint.shadowDy), paint.shadowRadius)
            } else null
            canvas.skia.save()
            canvas.skia.translate(left, top)
            layout.multiParagraph.paint(canvas.skia.asComposeCanvas(), color = Color(paint.color), shadow = shadow)
            canvas.skia.restore()
            canvas.touched()
        }
    }
}

abstract class Layout {
    enum class Alignment { ALIGN_NORMAL, ALIGN_OPPOSITE, ALIGN_CENTER }
}

/**
 * Android's StaticLayout as the video builds it: lines [width] px wide, aligned, at most [Builder.setMaxLines] with an
 * ellipsis at the end; drawn in its paint's colour and shadow at the time of drawing, as Android's is.
 */
class StaticLayout private constructor(private val result: TextLayoutResult, private val paint: TextPaint, val width: Int) {
    val lineCount: Int get() = result.lineCount

    val height: Int get() = result.multiParagraph.height.roundToInt()

    fun getLineWidth(line: Int): Float = result.getLineRight(line) - result.getLineLeft(line)

    fun draw(canvas: Canvas) = TextMeasure.paintAt(canvas, paint, result, 0f, 0f)

    class Builder private constructor(private val text: String, private val paint: TextPaint, private val width: Int) {
        private var alignment = Layout.Alignment.ALIGN_NORMAL
        private var maxLines = Int.MAX_VALUE
        private var ellipsize: TextUtils.TruncateAt? = null

        fun setAlignment(alignment: Layout.Alignment): Builder = apply { this.alignment = alignment }
        fun setMaxLines(maxLines: Int): Builder = apply { this.maxLines = maxLines }
        fun setEllipsize(where: TextUtils.TruncateAt?): Builder = apply { ellipsize = where }

        /** Android's extra spacing; the video asks for none (0, 1.0). */
        @Suppress("UNUSED_PARAMETER")
        fun setLineSpacing(add: Float, mult: Float): Builder = this

        /** The video asks for no extra padding, which is how Compose lays out text anyway. */
        @Suppress("UNUSED_PARAMETER")
        fun setIncludePad(include: Boolean): Builder = this

        fun build(): StaticLayout {
            val align = when (alignment) {
                Layout.Alignment.ALIGN_NORMAL -> TextAlign.Start
                Layout.Alignment.ALIGN_OPPOSITE -> TextAlign.End
                Layout.Alignment.ALIGN_CENTER -> TextAlign.Center
            }
            val result = paint.text.block(paint, text, width, maxLines, align, ellipsize == TextUtils.TruncateAt.END)
            return StaticLayout(result, paint, width)
        }

        companion object {
            fun obtain(source: CharSequence, start: Int, end: Int, paint: TextPaint, width: Int): Builder =
                Builder(source.subSequence(start, end).toString(), paint, width)
        }
    }
}

object TextUtils {
    enum class TruncateAt { START, MIDDLE, END, MARQUEE }

    private const val ELLIPSIS = "…"

    /**
     * [text] cut to fit [avail] px in [paint] with "…" at its end (Android's END), whole letters only (a pair of
     * surrogates stays together); as it is when it fits.
     */
    fun ellipsize(text: CharSequence, paint: TextPaint, avail: Float, where: TruncateAt): CharSequence {
        val s = text.toString()
        if (paint.measureText(s) <= avail) return s
        require(where == TruncateAt.END) { "Only the end is cut here" }
        var lo = 0
        var hi = s.length
        // The longest start that fits with the ellipsis after it.
        while (lo < hi) {
            val mid = cut(s, (lo + hi + 1) / 2)
            if (mid <= lo) break
            if (paint.measureText(s.substring(0, mid) + ELLIPSIS) <= avail) lo = mid else hi = mid - 1
        }
        val keep = cut(s, lo)
        return if (keep == 0 && paint.measureText(ELLIPSIS) > avail) "" else s.substring(0, keep) + ELLIPSIS
    }

    /** [n] moved back off the middle of a surrogate pair. */
    private fun cut(s: String, n: Int): Int = if (n in 1 until s.length && s[n].isLowSurrogate() && s[n - 1].isHighSurrogate()) n - 1 else n
}

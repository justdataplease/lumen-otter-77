package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The book's text measured on the desktop (its tests and pictures; iPhone: Core Text): each face from the bundled font
 * file the page prints it in ([fontFile]), at the weight it prints at ([measuredWeight], Android's rule), the words
 * shaped as one line by Skia's shaper (HarfBuzz: kerning and ligatures, as WebKit and Android shape them). [fonts] reads
 * a font file by its name. Words are measured once at 100 pt and kept (as Android's PaintTextMeasure keeps them).
 */
@OptIn(ExperimentalAtomicApi::class)
class SkiaTextMeasure(private val fonts: (String) -> ByteArray?) : TextMeasure {
    private val typefaces = HashMap<Pair<Face, Float?>, Typeface?>()
    private val cache = HashMap<String, Double>()
    private val busy = AtomicInt(0)

    override fun widthMm(text: String, face: Face, pt: Double, bold: Boolean): Double {
        val weight = measuredWeight(face, bold)
        val key = "${face.ordinal}|${weight ?: 0f}|$text"
        val em = locked {
            cache[key] ?: run {
                val tf = typefaces.getOrPut(face to weight) { typeface(face, weight) }
                val w = if (tf == null) TextFit.estimateEm(text) * face.widthFactor else {
                    val font = Font(tf, SIZE)
                    val line = TextLine.make(text, font)
                    (line.width / SIZE).toDouble().also {
                        line.close()
                        font.close()
                    }
                }
                w.also { if (cache.size < MAX_CACHED) cache[key] = it }
            }
        }
        return em * pt * TextFit.PT_MM
    }

    private fun typeface(face: Face, weight: Float?): Typeface? {
        val bytes = fonts(face.fontFile) ?: return null
        val base = FontMgr.default.makeFromData(Data.makeFromBytes(bytes)) ?: return null
        return if (weight == null) base else base.makeClone(FontVariation("wght", weight))
    }

    private inline fun <R> locked(block: () -> R): R {
        while (!busy.compareAndSet(0, 1)) Unit
        try {
            return block()
        } finally {
            busy.store(0)
        }
    }

    private companion object {
        const val SIZE = 100f
        const val MAX_CACHED = 20_000
    }
}

private val shared: TextMeasure by lazy { SkiaTextMeasure(BtFontFiles::file) }

actual fun phoneTextMeasure(): TextMeasure = shared

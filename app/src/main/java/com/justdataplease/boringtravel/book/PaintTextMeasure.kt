package com.justdataplease.boringtravel.book

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.justdataplease.boringtravel.R

/**
 * Measures text with the bundled fonts the page is printed in (the phone's own text shaping, kerning included), so
 * the book puts on a page what the page's browser will put there.
 */
class PaintTextMeasure(context: Context) : TextMeasure {
    private val app = context.applicationContext
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = SIZE }
    private val faces = HashMap<Face, Typeface>()
    private val cache = HashMap<String, Float>()

    override fun widthMm(text: String, face: Face, pt: Double, bold: Boolean): Double {
        val key = "${face.ordinal}|${if (bold) 1 else 0}|$text"
        val em = synchronized(this) {
            cache[key] ?: run {
                paint.typeface = typeface(face)
                paint.fontVariationSettings = when {
                    !variable(face) -> null
                    bold && face == Face.Literata -> "'wght' 600"
                    bold -> "'wght' 700"
                    else -> "'wght' 400"
                }
                (paint.measureText(text) / SIZE).also { if (cache.size < 20_000) cache[key] = it }
            }
        }
        return em * pt * TextFit.PT_MM
    }

    private fun variable(face: Face) = face in setOf(Face.Literata, Face.LiterataItalic, Face.Albert, Face.Caveat, Face.SourceSerif, Face.WorkSans, Face.JetBrains, Face.Lora)

    private fun typeface(face: Face): Typeface = faces.getOrPut(face) {
        runCatching { ResourcesCompat.getFont(app, face.fontRes()) }.getOrNull() ?: Typeface.DEFAULT
    }

    private companion object {
        const val SIZE = 100f
    }
}

/** The bundled font file a face is drawn with. */
fun Face.fontRes(): Int = when (this) {
    Face.Literata -> R.font.literata
    Face.LiterataItalic -> R.font.literata_italic
    Face.Albert -> R.font.albert_sans
    Face.Caveat -> R.font.caveat
    Face.PlexMono -> R.font.plex_mono_medium
    Face.Kalam -> R.font.kalam_bold
    Face.SourceSerif -> R.font.source_serif_4
    Face.CourierPrime -> R.font.courier_prime_regular
    Face.Architects -> R.font.architects_daughter_regular
    Face.WorkSans -> R.font.work_sans
    Face.JetBrains -> R.font.jetbrains_mono
    Face.Gochi -> R.font.gochi_hand_regular
    Face.Lora -> R.font.lora
    Face.Barlow -> R.font.barlow_regular
    Face.BarlowCondensed -> R.font.barlow_condensed_bold
}

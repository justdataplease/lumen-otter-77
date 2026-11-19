package com.justdataplease.boringtravel.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.justdataplease.boringtravel.R

/**
 * Bundled OFL fonts (licences in assets/licenses). Variable files get one Font per weight with the matching
 * variation; Greek and other scripts a face lacks fall back to the system font per glyph.
 */
@OptIn(ExperimentalTextApi::class)
object BtFonts {
    private fun variable(res: Int, weight: Int, style: FontStyle = FontStyle.Normal) = Font(
        resId = res,
        weight = FontWeight(weight),
        style = style,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    val albert = FontFamily(
        variable(R.font.albert_sans, 400),
        variable(R.font.albert_sans, 500),
        variable(R.font.albert_sans, 600),
        variable(R.font.albert_sans, 700),
    )

    val literata = FontFamily(
        variable(R.font.literata, 400),
        variable(R.font.literata, 600),
        variable(R.font.literata, 700),
        variable(R.font.literata_italic, 400, FontStyle.Italic),
        variable(R.font.literata_italic, 600, FontStyle.Italic),
    )

    val caveat = FontFamily(
        variable(R.font.caveat, 500),
        variable(R.font.caveat, 700),
    )

    val plexMono = FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
        Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
    )

    val barlow = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
    )

    val barlowCondensed = FontFamily(
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    )

    // ---- Field book, Graph paper and Kraft (google/fonts, 2026-11-06). No italic files: italic text is slanted. ----

    /** Field book titles: Kalam. */
    val kalam = FontFamily(
        Font(R.font.kalam_regular, FontWeight.Normal),
        Font(R.font.kalam_bold, FontWeight.Bold),
    )

    /** Field book text: Source Serif 4 (variable weight and optical size; the default text size is kept). */
    val sourceSerif = FontFamily(
        variable(R.font.source_serif_4, 400),
        variable(R.font.source_serif_4, 500),
        variable(R.font.source_serif_4, 600),
        variable(R.font.source_serif_4, 700),
    )

    /** Field book dates and numbers: Courier Prime. */
    val courierPrime = FontFamily(
        Font(R.font.courier_prime_regular, FontWeight.Normal),
        Font(R.font.courier_prime_bold, FontWeight.Bold),
    )

    /** Graph paper titles: Architects Daughter (one weight). */
    val architects = FontFamily(Font(R.font.architects_daughter_regular, FontWeight.Normal))

    /** Graph paper text: Work Sans. */
    val workSans = FontFamily(
        variable(R.font.work_sans, 400),
        variable(R.font.work_sans, 500),
        variable(R.font.work_sans, 600),
        variable(R.font.work_sans, 700),
    )

    /** Graph paper dates and numbers: JetBrains Mono. */
    val jetbrainsMono = FontFamily(
        variable(R.font.jetbrains_mono, 400),
        variable(R.font.jetbrains_mono, 500),
        variable(R.font.jetbrains_mono, 600),
    )

    /** Kraft titles: Gochi Hand (one weight). Kraft's numbers use Plex Mono. */
    val gochi = FontFamily(Font(R.font.gochi_hand_regular, FontWeight.Normal))

    /** Kraft text: Lora. */
    val lora = FontFamily(
        variable(R.font.lora, 400),
        variable(R.font.lora, 500),
        variable(R.font.lora, 600),
        variable(R.font.lora, 700),
    )

    // ---- Postcard Pop and Trail titles: Fraunces 900 and Archivo Black. ----
    // Neither file is bundled yet: adding fonts needs the owner's OK. Each stands in with the closest bundled face
    // behind one constant; when `fraunces.ttf` / `archivo_black.ttf` go into res/font, change that constant only.

    /** Postcard Pop titles. Placeholder: Literata at weight 900 and its display optical size (72). */
    val FRAUNCES = Face(R.font.literata, 900, opsz = 72f)

    /** Trail titles (upper-case). Placeholder: Work Sans at weight 900, a wide heavy grotesque like Archivo Black. */
    val ARCHIVO_BLACK = Face(R.font.work_sans, 900)

    val fraunces = FRAUNCES.family()
    val archivoBlack = ARCHIVO_BLACK.family()

    /** Fonts a journal entry can be written in (editor font chips), by stable id stored in the entry document. */
    val journalFonts: List<Pair<String, FontFamily>> = listOf(
        "albert" to albert,
        "literata" to literata,
        "caveat" to caveat,
        "mono" to plexMono,
        "barlow" to barlow,
    )

    fun journalFont(id: String?): FontFamily? = journalFonts.firstOrNull { it.first == id }?.second

    val journalFontLabels: Map<String, String> = mapOf(
        "albert" to "Albert Sans",
        "literata" to "Literata",
        "caveat" to "Caveat",
        "mono" to "Plex Mono",
        "barlow" to "Barlow",
    )
}

/**
 * One bundled font file at one weight, for drawing outside Compose (share card, globe labels, clip): the resource,
 * the weight, and the optical size for variable files that have one. [family] gives the Compose family of the face.
 */
data class Face(val res: Int, val weight: Int, val opsz: Float? = null) {
    companion object {
        /** The phone's own sans (no bundled file): loading [res] fails and callers fall back to the default typeface. */
        val System = Face(0, 400)
    }

    /** Whether this is the phone's font rather than a bundled file. */
    val isSystem: Boolean get() = res == 0

    /** The `font-variation-settings` string for a native Paint (empty for a static file at its own weight). */
    val variation: String
        get() = buildString {
            append("'wght' ").append(weight)
            if (opsz != null) append(", 'opsz' ").append(opsz)
        }

    @OptIn(ExperimentalTextApi::class)
    fun family(): FontFamily = FontFamily(
        Font(
            resId = res,
            weight = FontWeight(weight),
            variationSettings = if (opsz != null) {
                FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opsz))
            } else {
                FontVariation.Settings(FontVariation.weight(weight))
            },
        ),
    )
}

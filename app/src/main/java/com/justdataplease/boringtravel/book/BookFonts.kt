package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.model.PrintTheme

/**
 * A bundled face as the book uses it: its CSS family, the font file it is measured with, its vertical metrics
 * (ascent and descent per em, as FreeType reads them; used to sit text on the paper's lines) and how wide its text
 * runs compared with [TextFit.estimateEm]'s model.
 */
enum class Face(val css: String, val file: String, val ascent: Double, val descent: Double, val widthFactor: Double) {
    Literata("'Literata',Georgia,serif", "literata", 1.177, 0.308, 1.109),
    LiterataItalic("'Literata',Georgia,serif", "literata_italic", 1.177, 0.308, 1.044),
    Albert("'Albert Sans','Literata',sans-serif", "albert_sans", 0.950, 0.250, 1.069),
    Caveat("'Caveat','Literata',cursive", "caveat", 0.960, 0.300, 0.790),
    PlexMono("'IBM Plex Mono',monospace", "plex_mono_medium", 1.025, 0.275, 1.386),
    Kalam("'Kalam','Literata',cursive", "kalam_bold", 1.063, 0.531, 1.064),
    SourceSerif("'Source Serif 4',Georgia,serif", "source_serif_4", 1.036, 0.335, 1.039),
    CourierPrime("'Courier Prime','IBM Plex Mono',monospace", "courier_prime_regular", 0.782, 0.342, 1.386),
    Architects("'Architects Daughter','Literata',cursive", "architects_daughter_regular", 0.987, 0.404, 1.081),
    WorkSans("'Work Sans',sans-serif", "work_sans", 0.930, 0.243, 1.161),
    JetBrains("'JetBrains Mono',monospace", "jetbrains_mono", 1.020, 0.300, 1.386),
    Gochi("'Gochi Hand','Literata',cursive", "gochi_hand_regular", 0.771, 0.408, 0.987),
    Lora("'Lora','Literata',serif", "lora", 1.006, 0.274, 1.082),
    Barlow("'Barlow','Literata',sans-serif", "barlow_regular", 1.000, 0.200, 0.997),
    BarlowCondensed("'Barlow Condensed','Literata',sans-serif", "barlow_condensed_bold", 1.000, 0.200, 0.844),
}

/**
 * The book's font sets: the app's sets (Me › Look › Fonts) plus Postcard's serif and Minimal's sans sets. "Theme's own" follows the
 * print theme. [displayScale] keeps titles the same presence as Caveat at the same nominal size (like the app's
 * `BtType.h1`); [hand] = the title face is handwriting, so captions are written in it too.
 */
enum class BookFonts(
    val id: String,
    val label: String,
    val display: Face,
    val displayWeight: Int,
    val body: Face,
    val italic: Face,
    val mono: Face,
    val displayScale: Double,
    val hand: Boolean,
    val upper: Boolean = false,
) {
    Theme("theme", "Theme's own", Face.Caveat, 700, Face.Literata, Face.LiterataItalic, Face.PlexMono, 1.0, true),
    Notebook("notebook", "Notebook", Face.Caveat, 700, Face.Literata, Face.LiterataItalic, Face.PlexMono, 1.0, true),
    FieldBook("field", "Field book", Face.Kalam, 700, Face.SourceSerif, Face.SourceSerif, Face.CourierPrime, 0.82, true),
    Graph("graph", "Graph paper", Face.Architects, 400, Face.WorkSans, Face.WorkSans, Face.JetBrains, 0.76, true),
    Kraft("kraft", "Kraft", Face.Gochi, 400, Face.Lora, Face.Lora, Face.PlexMono, 0.92, true),
    /** Minimal's own clean sans set (Albert Sans and Plex Mono). */
    Minimal("minimal", "Minimal", Face.Albert, 700, Face.Albert, Face.Albert, Face.PlexMono, 0.74, false),
    NightRoute("night", "Night Route", Face.BarlowCondensed, 700, Face.Barlow, Face.Barlow, Face.PlexMono, 0.8, false, upper = true),
    Classic("classic", "Postcard", Face.Literata, 600, Face.Literata, Face.LiterataItalic, Face.PlexMono, 0.78, false);

    /** The set used with [theme]: Notebook and Scrapbook write by hand, Postcard is serif, Minimal is a clean sans. */
    fun resolve(theme: PrintTheme): BookFonts = if (this != Theme) this else when (theme) {
        PrintTheme.Notebook, PrintTheme.Scrapbook -> Notebook
        PrintTheme.Postcard -> Classic
        PrintTheme.Minimal -> Minimal
    }

    companion object {
        /** Unknown ids (and the retired "logbook") follow the theme. */
        fun of(id: String?): BookFonts = entries.firstOrNull { it.id == id } ?: Theme

        /** What the editor offers, in the app's order. */
        val choosable: List<BookFonts> = listOf(Theme, Notebook, Classic, Minimal, FieldBook, Graph, Kraft, NightRoute)
    }
}

package com.justdataplease.boringtravel.book

import kotlinx.serialization.json.JsonObject

/** Kinds of [FreeEl]. Kept as strings, so a kind from a newer version is kept (and not drawn). */
object FreeKind {
    const val PHOTO = "photo"
    const val TEXT = "text"
    /** The page's flowing text (its paragraphs); one per page, never copied or deleted. */
    const val FLOW = "flow"
    const val STAMP = "stamp"
    const val FLAGS = "flags"
    const val ROUTE = "route"
    const val RULE = "rule"
    const val LINES = "lines"
    const val TAPE = "tape"
    const val CORNERS = "corners"
    const val SHAPE = "shape"
    const val LINE = "line"
    /** A soft dark fade under words on a photo. */
    const val SHADE = "shade"

    /** Drawn by [DecoEl]. */
    val DECO = setOf(TAPE, CORNERS, SHAPE, LINE, SHADE)
}

/** Insets of a photo inside its print (the white border), in mm. */
data class Pad(val l: Double, val t: Double, val r: Double, val b: Double) {
    companion object {
        val ZERO = Pad(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Something placed by hand on a page: [box] is mm from the trim's top-left before turning, [rot] degrees clockwise
 * around the box's centre. One flat record for every kind; each kind reads the fields it needs.
 *
 * [derived] marks elements made from the page's template the first time it was changed by hand (ids `t0`, `t1`, … in
 * the template's order). Applying a template again replaces them; photos go into the template's spots and what was
 * added by hand stays on top.
 */
data class FreeEl(
    val id: String,
    val kind: String,
    val box: Box,
    val rot: Double = 0.0,
    val derived: Boolean = false,
    // ---- photo
    val photo: Slot? = null,
    /** Photo: print | flat | bleed. Stamp: brand | day | postage. Flags: sticker | flat. Shape: rect | oval. Lines: dots | lines. */
    val style: String? = null,
    val pad: Pad? = null,
    val tapes: List<Tape> = emptyList(),
    /** The caption is written on the print's wide bottom border (Scrapbook). */
    val band: Boolean = false,
    // ---- text
    val text: String = "",
    /** The words come from the page ("title", "subtitle") or a photo's caption ("cap:<element id>"). */
    val bind: String? = null,
    /** "display", "body", "italic", "mono" (the book's font set) or a [Face] name. */
    val font: String? = null,
    val pt: Double = 0.0,
    /** Line height as a multiple of the size. */
    val lh: Double = 1.25,
    val align: String = "left",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val upper: Boolean = false,
    /** Highlighter behind the words. */
    val mark: Boolean = false,
    /** The template's CSS class for the words (colour and type of titles, dates, captions). */
    val role: String? = null,
    // ---- colours (#RRGGBB): text and lines, shape fill, outline
    val color: String? = null,
    val fill: String? = null,
    /** Outline or line width, mm. */
    val stroke: Double = 0.0,
    // ---- stamp: top, middle, bottom, small
    val words: List<String> = emptyList(),
    // ---- flags
    val codes: List<String> = emptyList(),
    val flagH: Double = 0.0,
    // ---- route: cover | trip | day
    val src: String? = null,
    val labels: Boolean = false,
    val markers: Boolean = false,
    val scaleBar: Boolean = false,
    // ---- flowing text
    val cols: Int = 1,
    // ---- writing lines
    val pitch: Double = 0.0,
    /** Left off the page and out of the PDF; the editor still lists it, so it can be shown again. */
    val hidden: Boolean = false,
    val extra: JsonObject = EMPTY,
) {
    val isPhoto: Boolean get() = kind == FreeKind.PHOTO
    val isText: Boolean get() = kind == FreeKind.TEXT
    val isFlow: Boolean get() = kind == FreeKind.FLOW

    /** The photo element this caption belongs to, for a caption. */
    val captionOf: String? get() = bind?.takeIf { it.startsWith(CAP) }?.removePrefix(CAP)

    companion object {
        const val CAP = "cap:"
        const val TITLE = "title"
        const val SUBTITLE = "subtitle"
    }
}

/**
 * What is under everything on a page: a plain [color], or a [photo] to the bleed edge with [dim] (0 – 0.7) black over
 * it. No background is the theme's paper; a [hidden] one is kept but the paper shows.
 */
data class PageBg(
    val kind: String,
    val color: String? = null,
    val photo: Slot? = null,
    val dim: Double = 0.0,
    val hidden: Boolean = false,
    val extra: JsonObject = EMPTY,
) {
    companion object {
        const val COLOR = "color"
        const val PHOTO = "photo"
        const val MAX_DIM = 0.7
    }
}

/** Checks on values that end up in CSS and HTML attributes. */
object FreeValues {
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    private val ROLE = Regex("^[a-z][a-z0-9 -]{0,40}$")
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    val ALIGNS = setOf("left", "center", "right")
    val FONT_ROLES = setOf("display", "body", "italic", "mono")

    fun color(v: String?): String? = v?.takeIf { HEX.matches(it) }?.uppercase()
    fun role(v: String?): String? = v?.takeIf { ROLE.matches(it) }
    fun id(v: String?): String? = v?.takeIf { ID.matches(it) }
    fun align(v: String?): String = v?.takeIf { it in ALIGNS } ?: "left"
    fun font(v: String?): String? = v?.takeIf { it in FONT_ROLES || Face.entries.any { f -> f.name == it } }
}

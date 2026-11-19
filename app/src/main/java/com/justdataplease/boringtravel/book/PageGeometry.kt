package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.model.PrintTheme
import kotlin.math.floor
import kotlin.math.roundToInt

/** A straight line on the page, in mm (crop marks). */
data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/**
 * The physical sheet: the trim size of [format] and, with [bleed], 3 mm of bleed and a white margin with crop marks
 * around it (for print shops). Page coordinates are mm from the sheet's top-left; [trim] is where the page is cut.
 */
data class Sheet(val format: BookFormat, val bleed: Boolean) {
    val trimW: Double get() = format.widthMm
    val trimH: Double get() = format.heightMm

    /** Space between the sheet's edge and the trim on each side. */
    val slug: Double get() = if (bleed) SLUG_MM else 0.0

    val pageW: Double get() = trimW + 2 * slug
    val pageH: Double get() = trimH + 2 * slug

    val trim: Box get() = Box(slug, slug, trimW, trimH)

    /** What paper colour and full-page photos cover, in trim coordinates: the trim plus the bleed. */
    val art: Box get() = if (bleed) Box(-BLEED_MM, -BLEED_MM, trimW + 2 * BLEED_MM, trimH + 2 * BLEED_MM) else Box(0.0, 0.0, trimW, trimH)

    /** The sheet in thousandths of an inch, for Android's print media size. */
    val widthMils: Int get() = (pageW / 25.4 * 1000).roundToInt()
    val heightMils: Int get() = (pageH / 25.4 * 1000).roundToInt()

    /** Eight short lines at the corners, outside the bleed, in page coordinates. None without bleed. */
    fun cropMarks(): List<Line> {
        if (!bleed) return emptyList()
        val t = trim
        val a = MARK_OFFSET_MM
        val b = MARK_OFFSET_MM + MARK_LENGTH_MM
        val out = ArrayList<Line>(8)
        for (x in listOf(t.x, t.right)) for (y in listOf(t.y, t.bottom)) {
            val sx = if (x == t.x) -1 else 1
            val sy = if (y == t.y) -1 else 1
            out += Line(x + sx * a, y, x + sx * b, y) // horizontal, at the trim line
            out += Line(x, y + sy * a, x, y + sy * b) // vertical
        }
        return out
    }

    companion object {
        const val BLEED_MM = 3.0
        const val SLUG_MM = 10.0
        const val MARK_OFFSET_MM = 3.5
        const val MARK_LENGTH_MM = 5.0
    }
}

/**
 * The page grid every text sits on: one line pitch [line] (the body's line height, and the spacing of the paper's
 * dots or rules), whole columns and rows centred in the trim, margins of three lines (four at the foot for the page
 * number). Coordinates are mm from the trim's top-left.
 */
class Grid(val format: BookFormat) {
    val line: Double = when (format) {
        BookFormat.A5 -> 5.0
        BookFormat.Square -> 5.4
        BookFormat.A4, BookFormat.Letter -> 5.6
    }

    /** Type and spacing scale against A5. */
    val scale: Double = line / 5.0

    val cols: Int = floor(format.widthMm / line + 1e-9).toInt()
    val rows: Int = floor(format.heightMm / line + 1e-9).toInt()
    val ox: Double = (format.widthMm - cols * line) / 2
    val oy: Double = (format.heightMm - rows * line) / 2

    /** The type area. */
    val content: Box = Box(ox + 3 * line, oy + 3 * line, (cols - 6) * line, (rows - 7) * line)
    val contentRows: Int = rows - 7

    /** Text columns on full text pages: one on A5, two on the larger formats. */
    val columns: Int = if (format == BookFormat.A5) 1 else 2
    val gutter: Double = line

    fun y(row: Int): Double = oy + row * line
    fun x(col: Int): Double = ox + col * line

    /** Whole lines that fit in [mm]. */
    fun linesIn(mm: Double): Int = floor(mm / line + 1e-6).toInt()

    /** [y] moved down (or kept) to the next grid line. */
    fun snapDown(y: Double): Double = oy + kotlin.math.ceil((y - oy) / line - 1e-6) * line

    // ---- type sizes, pt ----
    val bodyPt: Double = 9.6 * scale
    val monoPt: Double = 6.6 * scale
}

/**
 * How a print theme places and dresses photos and paper (the geometry side; [BookCss] paints the rest). Prints are
 * fitted to the photo's own shape with a white border, tilted and taped; flat photos fill their frames.
 */
data class BookLook(
    val theme: PrintTheme,
    val prints: Boolean,
    val border: Double,
    val bottomBorder: Double,
    val tilt: Boolean,
    val tape: Boolean,
    /** Dots or rules on every page (otherwise only on notes pages). */
    val paperEverywhere: Boolean,
    val paperColor: String,
    val ink: String,
    val muted: String,
    val red: String,
    val route: String,
    val highlighter: String,
    val dot: String,
    val rule: String,
) {
    companion object {
        fun of(theme: PrintTheme): BookLook = when (theme) {
            PrintTheme.Notebook -> BookLook(
                theme, prints = true, border = 2.3, bottomBorder = 2.3, tilt = true, tape = true, paperEverywhere = true,
                paperColor = "#FCFCF9", ink = "#1E2A5A", muted = "#525C86", red = "#C4423A", route = "#C4423A",
                highlighter = "#F7E36A", dot = "#AEB5C9", rule = "#CDD2DC",
            )
            PrintTheme.Scrapbook -> BookLook(
                theme, prints = true, border = 2.6, bottomBorder = 8.0, tilt = true, tape = true, paperEverywhere = true,
                paperColor = "#FCFCF9", ink = "#1E2A5A", muted = "#525C86", red = "#C4423A", route = "#C4423A",
                highlighter = "#F7E36A", dot = "#B5BBCD", rule = "#CDD2DC",
            )
            PrintTheme.Postcard -> BookLook(
                theme, prints = false, border = 0.0, bottomBorder = 0.0, tilt = false, tape = false, paperEverywhere = false,
                paperColor = "#FFFFFF", ink = "#22262A", muted = "#6A7076", red = "#C4423A", route = "#1F6E9C",
                highlighter = "#F7E36A", dot = "#C3C7CC", rule = "#D9DCDF",
            )
            PrintTheme.Minimal -> BookLook(
                theme, prints = false, border = 0.0, bottomBorder = 0.0, tilt = false, tape = false, paperEverywhere = false,
                paperColor = "#FFFFFF", ink = "#1C1F22", muted = "#6A7076", red = "#C4423A", route = "#0E8A5C",
                highlighter = "#D6F2E4", dot = "#C3C7CC", rule = "#DFE3DE",
            )
        }
    }
}

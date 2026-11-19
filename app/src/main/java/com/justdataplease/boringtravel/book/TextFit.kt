package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.doc.RichHtml
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Measures text: the width in mm of [text] set in [face] at [pt] points ([bold] = the bold weight). */
fun interface TextMeasure {
    fun widthMm(text: String, face: Face, pt: Double, bold: Boolean): Double
}

/**
 * Line breaking the way the page's browser does it (greedy, at spaces, long words broken anywhere), so the book can
 * put exactly as much text on a page as fits. The phone measures with the real fonts (`PaintTextMeasure`); JVM tests
 * use [Estimate], a per-character model scaled per face.
 */
object TextFit {
    const val PT_MM = 25.4 / 72.0

    /** Lines are filled to this share of the column, so small differences in measuring never push a word over. */
    const val SAFETY = 0.975

    /** Width of [text] in em, by a simple model of a text face (narrow letters, capitals, digits, spaces). */
    fun estimateEm(text: String): Double {
        var s = 0.0
        for (c in text) s += when {
            c == ' ' -> 0.25
            c in "ijlt.,;:'!|()-" -> 0.30
            c in "mwMW" -> 0.82
            c.isUpperCase() -> 0.66
            c.isDigit() -> 0.55
            else -> 0.50
        }
        return s
    }

    val Estimate = TextMeasure { text, face, pt, bold -> estimateEm(text) * face.widthFactor * (if (bold) 1.05 else 1.0) * pt * PT_MM }

    /**
     * Where each line of [text] starts (indexes into [text]) when it is wrapped into [widthMm], the first line
     * [firstIndentMm] narrower. Line breaks in the text ('\n') always start a new line.
     */
    fun lineStarts(text: String, widthMm: Double, face: Face, pt: Double, bold: Boolean, measure: TextMeasure, firstIndentMm: Double = 0.0): List<Int> {
        val starts = ArrayList<Int>()
        val width = widthMm * SAFETY
        val space = measure.widthMm(" ", face, pt, bold)
        var lineStart = 0
        var first = true
        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val hard = text.substring(lineStart, nl)
            var lineW = if (first) firstIndentMm else 0.0
            var lineEmpty = true
            starts += lineStart
            var i = 0
            while (i < hard.length) {
                while (i < hard.length && hard[i] == ' ') i++
                if (i >= hard.length) break
                var j = i
                while (j < hard.length && hard[j] != ' ') j++
                val word = hard.substring(i, j)
                val ww = measure.widthMm(word, face, pt, bold)
                val add = if (lineEmpty) ww else space + ww
                if (lineW + add <= width || lineEmpty && ww <= width - lineW) {
                    lineW += add
                    lineEmpty = false
                } else if (!lineEmpty && ww <= width) {
                    starts += lineStart + i
                    lineW = ww
                    lineEmpty = false
                } else {
                    // A word wider than the line: broken anywhere, like overflow-wrap: anywhere.
                    if (!lineEmpty) { starts += lineStart + i; lineW = 0.0 }
                    var k = i
                    var w = lineW
                    while (k < j) {
                        val cw = measure.widthMm(hard[k].toString(), face, pt, bold)
                        if (w + cw > width && k > i) { starts += lineStart + k; w = 0.0 }
                        w += cw
                        k++
                    }
                    lineW = w
                    lineEmpty = false
                }
                i = j
            }
            first = false
            if (nl >= text.length) break
            lineStart = nl + 1
        }
        return starts
    }

    fun lines(text: String, widthMm: Double, face: Face, pt: Double, bold: Boolean, measure: TextMeasure, firstIndentMm: Double = 0.0): Int =
        if (text.isBlank()) 1 else lineStarts(text, widthMm, face, pt, bold, measure, firstIndentMm).size

    /** The largest size from [sizes] (big first) at which [text] takes at most [maxLines] lines in [widthMm]. */
    fun fitSize(text: String, widthMm: Double, maxLines: Int, face: Face, bold: Boolean, sizes: List<Double>, measure: TextMeasure): Double =
        sizes.firstOrNull { lines(text, widthMm, face, it, bold, measure) <= maxLines } ?: sizes.last()
}

/** Splitting the journal's inline HTML subset at a position in its plain text, keeping bold, italics, links open. */
object HtmlText {
    private val selfClosing = setOf("br")

    /** The entities [RichHtml.toPlainText] turns into one character; any other '&' counts as itself. */
    private val decoded = listOf("&lt;", "&gt;", "&quot;", "&#39;", "&nbsp;", "&amp;")

    /**
     * Cuts [html] where its plain text ([RichHtml.toPlainText]) reaches [at]. Tags open at the cut are closed at the
     * end of the first part and opened again at the start of the second. Spaces at the cut are dropped.
     */
    fun split(html: String, at: Int): Pair<String, String> {
        val open = ArrayList<Pair<String, String>>() // name, full opening tag
        var plain = 0
        var i = 0
        var cut = -1
        var openAtCut: List<Pair<String, String>> = emptyList()
        while (i < html.length) {
            val c = html[i]
            if (c == '<') {
                val end = html.indexOf('>', i).let { if (it < 0) html.length - 1 else it }
                val tag = html.substring(i, end + 1)
                val closing = tag.startsWith("</")
                val name = tag.removePrefix("</").removePrefix("<").takeWhile { it.isLetterOrDigit() }.lowercase()
                if (name in selfClosing) {
                    if (plain == at && cut < 0) { cut = i; openAtCut = open.toList() }
                    plain++
                } else if (closing) {
                    val idx = open.indexOfLast { it.first == name }
                    if (idx >= 0) open.removeAt(idx)
                } else {
                    open += name to tag
                }
                i = end + 1
                continue
            }
            if (plain == at && cut < 0) { cut = i; openAtCut = open.toList() }
            val entity = if (c == '&') decoded.firstOrNull { html.startsWith(it, i) } else null
            i += entity?.length ?: 1
            plain++
        }
        if (cut < 0) return html to ""
        val first = html.substring(0, cut).trimEnd() + openAtCut.asReversed().joinToString("") { "</${it.first}>" }
        val restRaw = html.substring(cut)
        val rest = openAtCut.joinToString("") { it.second } + restRaw.trimStart()
        return first to rest
    }

    /** Joins the two halves of a split paragraph again. */
    fun join(a: String, b: String): String = if (a.isEmpty()) b else if (b.isEmpty()) a else "$a $b"
}

/** A text area on a page: [rows] lines high, [cols] columns of [colWidth] mm. */
data class FlowBox(val rows: Int, val cols: Int, val colWidth: Double) {
    val capacity: Int get() = rows * cols
}

/** How one kind of paragraph is set: face and size, grid lines per text line, space before, left indent. */
data class ParaStyle(
    val face: Face,
    val pt: Double,
    val bold: Boolean,
    val linesPer: Double,
    val gapBefore: Int,
    val leftMm: Double,
    val splittable: Boolean,
    val keepWithNext: Boolean,
)

/** The flowing text's type for a book: sizes from the [grid], faces from [fonts]. */
class FlowType(val grid: Grid, val fonts: BookFonts) {
    val line: Double get() = grid.line
    val bodyPt: Double = grid.bodyPt
    val titlePt: Double = 19.0 * grid.scale * fonts.displayScale
    val headPt: Double = 13.5 * grid.scale * fonts.displayScale
    val monoPt: Double = grid.monoPt

    /** First-line indent of a paragraph that follows another (book style: no space between paragraphs). */
    val indentMm: Double = bodyPt * TextFit.PT_MM * 1.3

    fun style(kind: String): ParaStyle = when (kind) {
        ParaKind.TITLE -> ParaStyle(fonts.display, titlePt, fonts.displayWeight >= 600, 2.0, 1, 0.0, splittable = false, keepWithNext = true)
        ParaKind.META -> ParaStyle(fonts.mono, monoPt, false, 1.0, 0, 0.0, splittable = false, keepWithNext = true)
        ParaKind.HEADING -> ParaStyle(fonts.display, headPt, fonts.displayWeight >= 600, 2.0, 0, 0.0, splittable = false, keepWithNext = true)
        ParaKind.QUOTE -> ParaStyle(fonts.italic, bodyPt, false, 1.0, 0, 4.0 * grid.scale, splittable = true, keepWithNext = false)
        ParaKind.BULLET, ParaKind.NUMBER -> ParaStyle(fonts.body, bodyPt, false, 1.0, 0, 4.5 * grid.scale, splittable = true, keepWithNext = false)
        ParaKind.CHECK, ParaKind.DONE -> ParaStyle(fonts.body, bodyPt, false, 1.0, 0, 5.0 * grid.scale, splittable = true, keepWithNext = false)
        ParaKind.PLACE -> ParaStyle(fonts.mono, monoPt, false, 1.0, 0, 0.0, splittable = false, keepWithNext = false)
        else -> ParaStyle(fonts.body, bodyPt, false, 1.0, 0, 0.0, splittable = true, keepWithNext = false)
    }

    /** Grid lines a block of [textLines] lines takes: titles 2 lines for one line, 1.5 each when they wrap. */
    fun blockLines(kind: String, textLines: Int): Int = when (kind) {
        ParaKind.TITLE -> if (textLines <= 1) 2 else ceil(textLines * 1.5 - 1e-9).toInt()
        else -> ceil(textLines * style(kind).linesPer - 1e-9).toInt()
    }

    /** CSS line height of [kind] in grid lines, for a block of [textLines] lines. */
    fun lineHeight(kind: String, textLines: Int): Double = when (kind) {
        ParaKind.TITLE -> if (textLines <= 1) 2.0 else 1.5
        else -> style(kind).linesPer
    }

    /** Text sizes in display faces that cannot be upper-case get [BookFonts.upper] applied by CSS; width grows ~12%. */
    fun upperFactor(kind: String): Double = if (fonts.upper && (kind == ParaKind.TITLE || kind == ParaKind.HEADING)) 1.12 else 1.0
}

/** The result of filling a box: [count] paragraphs fit whole; the next one fits its first [splitLines] lines. */
data class Fill(val count: Int, val splitLines: Int?, val rowsUsed: Int)

/**
 * Pours paragraphs into a [FlowBox] like the page does: column by column, line by line; titles and their line stay
 * with the text after them; a paragraph split across pages keeps two lines on each side when it can. Paragraphs of
 * [hidden] entries take no room.
 */
class Flow(val type: FlowType, val measure: TextMeasure, val hidden: Set<String> = emptySet()) {
    private val cache = HashMap<String, Int>()

    fun visible(p: Para): Boolean = p.entry == null || p.entry !in hidden

    /** Whether [p] gets the first-line indent after [prev] (the paragraph before it in the same box). */
    fun indented(p: Para, prev: Para?): Boolean =
        p.kind == ParaKind.P && !p.cont && prev != null && prev.kind == ParaKind.P

    /** The paragraph's text as the page shows it (runs of spaces as one, line breaks kept). */
    fun plain(p: Para): String = RichHtml.toPlainText(normalised(p.html))

    /** Text lines of [p] in a column [colWidth] wide. */
    fun textLines(p: Para, colWidth: Double, indent: Boolean): Int {
        val key = "${p.kind}|${p.html.hashCode()}|${p.html.length}|$colWidth|$indent"
        return cache.getOrPut(key) {
            val s = type.style(p.kind)
            val w = (colWidth - s.leftMm) / type.upperFactor(p.kind)
            TextFit.lines(plain(p), w, s.face, s.pt, s.bold, measure, if (indent) type.indentMm else 0.0)
        }
    }

    /** Where the text of [p] is cut to keep its first [lines] lines (index into [plain]). */
    fun cutIndex(p: Para, colWidth: Double, indent: Boolean, lines: Int): Int {
        val s = type.style(p.kind)
        val starts = TextFit.lineStarts(plain(p), colWidth - s.leftMm, s.face, s.pt, s.bold, measure, if (indent) type.indentMm else 0.0)
        return starts.getOrElse(lines) { plain(p).length }
    }

    /** Fills [box] with [paras] from [from]; see [Fill]. */
    fun fill(paras: List<Para>, box: FlowBox, from: Int = 0): Fill {
        var col = 0
        var row = 0
        var count = 0
        var maxRow = 0
        var prev: Para? = null
        var i = from
        fun nextVisible(after: Int): Para? = (after + 1 until paras.size).map { paras[it] }.firstOrNull(::visible)
        while (i < paras.size) {
            val p = paras[i]
            if (!visible(p)) { count++; i++; continue }
            val s = type.style(p.kind)
            val n = textLines(p, box.colWidth, indented(p, prev))
            val h = type.blockLines(p.kind, n)
            val gap = if (row == 0) 0 else s.gapBefore
            // Titles and their time line need the next block's first line in the same column.
            val keep = if (s.keepWithNext) {
                val next = nextVisible(i)
                if (next == null) 0 else if (next.kind == ParaKind.META || next.kind == ParaKind.TITLE) type.blockLines(next.kind, 1) + 1 else 1
            } else 0
            if (row + gap + h + keep <= box.rows || (row == 0 && col == 0 && !s.splittable && h + keep > box.rows)) {
                row += gap + h
                maxRow = max(maxRow, min(row, box.rows))
                count++; prev = p; i++
                if (row >= box.rows && i < paras.size) { col++; row = 0; if (col >= box.cols) return Fill(count, null, box.rows) }
                continue
            }
            if (s.splittable) {
                // Lines run on into the next columns of the same page; only the last column splits the paragraph.
                var left = n
                var r = row + gap
                var c = col
                var fits = true
                while (left > 0) {
                    val free = box.rows - r
                    if (left <= free) { r += left; left = 0; break }
                    if (c + 1 < box.cols) { left -= max(free, 0); c++; r = 0; continue }
                    fits = false
                    break
                }
                if (!fits) {
                    // The page ends inside this paragraph: keep two lines on each side, or move it whole.
                    val onPage = n - left + max(box.rows - r, 0)
                    val firstOnPage = count == 0 && row == 0 && col == 0
                    var k = onPage
                    if (n >= 4) {
                        if (n - k < 2) k = n - 2
                        if (k >= 2) return Fill(count, k, box.rows)
                    }
                    // Nothing else on the page: split anyway rather than leave it empty.
                    if (firstOnPage && onPage >= 1) return Fill(count, onPage, box.rows)
                    return Fill(count, null, box.rows)
                }
                row = r; col = c
                maxRow = max(maxRow, row)
                count++; prev = p; i++
                if (row >= box.rows && i < paras.size) { col++; row = 0; if (col >= box.cols) return Fill(count, null, box.rows) }
                continue
            }
            // A title or line that doesn't fit here goes to the top of the next column, or the next page.
            if (col + 1 < box.cols && row > 0) { col++; row = 0; continue }
            return Fill(count, null, maxOf(maxRow, row))
        }
        return Fill(count, null, if (col > 0) box.rows else maxRow)
    }

    /** The fewest rows (≤ [box].rows) that still take every paragraph, for balanced columns on a run's last page. */
    fun balancedRows(paras: List<Para>, box: FlowBox): Int {
        if (box.cols <= 1) return box.rows
        val visible = paras.count(::visible)
        if (visible == 0) return box.rows
        var lo = 1
        var hi = box.rows
        if (fill(paras, box).count < paras.size) return box.rows
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val f = fill(paras, box.copy(rows = mid))
            if (f.count >= paras.size && f.splitLines == null) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Splits [p] after its first [lines] lines at [colWidth]: the first part, and the rest marked [Para.cont]. */
    fun split(p: Para, colWidth: Double, indent: Boolean, lines: Int): Pair<Para, Para> {
        val at = cutIndex(p, colWidth, indent, lines)
        val (a, b) = HtmlText.split(normalised(p.html), at)
        return p.copy(html = a) to p.copy(html = b, cont = true)
    }

    /** The html with runs of white space as one space, as the page shows it; [plain] and [split] both read this. */
    fun normalised(html: String): String = html.replace(WS, " ").trim()

    private companion object {
        val WS = Regex("\\s+")
    }
}

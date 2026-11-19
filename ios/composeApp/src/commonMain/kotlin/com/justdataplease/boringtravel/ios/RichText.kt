package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.ui.journal.HtmlRuns
import com.justdataplease.boringtravel.ui.theme.BtColors
import kotlin.math.roundToInt

/*
 * The journal editor's rich text: what the Android app gets from its rich-text library (com.mohamedrejeb.richeditor),
 * written here in plain Kotlin and Compose because that library is built with a newer Kotlin than this app.
 *
 * A text area holds paragraphs, list items (bullets, numbers) and headings, and inside them bold, italic, underline,
 * strikethrough, larger and smaller text, a highlight, colours and links: exactly what the entry document keeps (a
 * paragraph's small HTML, RichHtml; a heading's plain text). It reads a note's blocks in and writes them out again; a
 * paragraph nobody changed is written back exactly as it was stored, byte for byte, so a note written on Android keeps
 * everything when it is edited here.
 */

/** How a run of characters looks: the same fields the read view draws (bold, italic, …, colour, size, link). */
typealias Ink = HtmlRuns.Style

/** "Larger" and "Smaller" in the text style menu (the Android editor's 1.3em and 0.85em). */
val LARGER: HtmlRuns.Size = HtmlRuns.Size.Em(1.3f)
val SMALLER: HtmlRuns.Size = HtmlRuns.Size.Em(0.85f)

/**
 * One line of a text area ('\n' ends every line but the last). A paragraph is a line and the [soft] lines after it
 * (line breaks inside it, a <br>). [list] and [heading] (0, or a level 2–4) belong to the whole paragraph. [kept]: the
 * stored block the paragraph was read from (on its first line).
 */
data class RichLine(
    val list: ListStyle? = null,
    val heading: Int = 0,
    val soft: Boolean = false,
    val kept: Kept? = null,
)

/** A stored text block as it was read: written back unchanged while its text, looks and kind are the same. */
class Kept(val block: Block, val text: String, val inks: List<Ink>, val list: ListStyle?, val heading: Int)

/** One paragraph of a [RichValue]: its lines [first]..[last] and its characters [start] until [end]. */
data class RichParagraph(val first: Int, val last: Int, val start: Int, val end: Int)

/**
 * A text area's content: the [text], how each character looks ([inks], one per character; a '\n' has a plain one), and
 * each line's kind ([lines], one per line).
 */
data class RichValue(val text: String, val inks: List<Ink>, val lines: List<RichLine>) {
    init {
        require(inks.size == text.length) { "One look per character" }
        require(lines.size == text.count { it == '\n' } + 1) { "One kind per line" }
    }

    /** The first character of each line. */
    val lineStarts: IntArray by lazy {
        val out = IntArray(lines.size)
        var k = 1
        text.forEachIndexed { i, ch -> if (ch == '\n') out[k++] = i + 1 }
        out
    }

    fun lineStart(line: Int): Int = lineStarts[line]

    /** Where line [line] ends (its '\n', or the end of the text). */
    fun lineEnd(line: Int): Int = if (line + 1 < lines.size) lineStarts[line + 1] - 1 else text.length

    /** The line character [offset] is on (an offset at a '\n' is the end of the line before it). */
    fun lineAt(offset: Int): Int {
        var lo = 0
        var hi = lines.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun lineText(line: Int): String = text.substring(lineStart(line), lineEnd(line))

    /** The paragraph line [line] belongs to: back to its first line, on through its soft lines. */
    fun paragraphOf(line: Int): RichParagraph {
        var first = line
        while (first > 0 && lines[first].soft) first--
        var last = line
        while (last + 1 < lines.size && lines[last + 1].soft) last++
        return RichParagraph(first, last, lineStart(first), lineEnd(last))
    }

    /** Every paragraph, in order. */
    fun paragraphs(): List<RichParagraph> {
        val out = ArrayList<RichParagraph>()
        var line = 0
        while (line < lines.size) {
            val p = paragraphOf(line)
            out += p
            line = p.last + 1
        }
        return out
    }

    /** The look new text takes at [start] (replacing up to [end]): the character before it, else the one after; no link. */
    fun inkAt(start: Int, end: Int = start): Ink {
        val before = (start - 1).takeIf { it >= 0 && text[it] != '\n' }?.let { inks[it] }
        val after = end.takeIf { it < text.length && text[it] != '\n' }?.let { inks[it] }
        return (before ?: after ?: Ink()).copy(href = null)
    }

    /** The characters [start, end) replaced by [inserted], each new one in [ink]; lines split and joined as an editor does. */
    fun replace(start: Int, end: Int, inserted: String, ink: Ink): RichValue {
        val firstLine = lineAt(start)
        val lastLine = lineAt(end)
        val attrs = lines[firstLine]
        val newText = text.substring(0, start) + inserted + text.substring(end)
        val newInks = ArrayList<Ink>(newText.length).apply {
            addAll(inks.subList(0, start))
            inserted.forEach { add(if (it == '\n') Ink() else ink) }
            addAll(inks.subList(end, inks.size))
        }
        val breaks = inserted.count { it == '\n' }
        val middle = when {
            breaks == 0 -> listOf(attrs)
            // Enter at the very start of a line: the line's kind goes down with its text, an empty line stays above.
            start == lineStart(firstLine) && inserted.substringBefore('\n').isEmpty() ->
                listOf(RichLine(list = attrs.list.takeIf { attrs.heading == 0 }, soft = attrs.soft)) +
                    List(breaks - 1) { RichLine(list = attrs.list.takeIf { attrs.heading == 0 }) } + attrs.copy(soft = false)
            // A new line after a list item is the next item; after a heading, plain text.
            else -> listOf(attrs) + List(breaks) { RichLine(list = attrs.list.takeIf { attrs.heading == 0 }) }
        }
        val newLines = (lines.subList(0, firstLine) + middle + lines.subList(lastLine + 1, lines.size)).toMutableList()
        if (newLines[0].soft) newLines[0] = newLines[0].copy(soft = false)
        return RichValue(newText, newInks, newLines)
    }

    /** [change] applied to every look in [start, end). */
    fun mapInks(start: Int, end: Int, change: (Ink) -> Ink): RichValue =
        copy(inks = inks.mapIndexed { i, ink -> if (i in start until end && text[i] != '\n') change(ink) else ink })

    /** [change] applied to every line of the paragraphs that [start]..[end] touch. */
    fun mapParagraphs(start: Int, end: Int, change: (RichLine) -> RichLine): RichValue {
        val from = paragraphOf(lineAt(start)).first
        val to = paragraphOf(lineAt(end)).last
        return copy(lines = lines.mapIndexed { i, l -> if (i in from..to) change(l) else l })
    }

    /** The lines the paragraphs touched by [start]..[end] have, first lines only. */
    fun paragraphLines(start: Int, end: Int): List<RichLine> {
        val from = paragraphOf(lineAt(start)).first
        val to = paragraphOf(lineAt(end)).last
        return (from..to).map { lines[it] }.filter { !it.soft }
    }

    /** [this] then [other], a new paragraph between them. */
    operator fun plus(other: RichValue): RichValue = when {
        text.isEmpty() && lines.size == 1 -> other
        other.text.isEmpty() && other.lines.size == 1 -> this
        else -> RichValue(text + "\n" + other.text, inks + Ink() + other.inks, lines + other.lines.mapIndexed { i, l -> if (i == 0) l.copy(soft = false) else l })
    }

    /** Characters [start, end), with their lines. */
    fun slice(start: Int, end: Int): RichValue {
        val first = lineAt(start)
        val last = lineAt(end)
        val ls = lines.subList(first, last + 1).toMutableList()
        if (ls[0].soft) ls[0] = ls[0].copy(soft = false)
        return RichValue(text.substring(start, end), inks.subList(start, end).toList(), ls)
    }

    /** The paragraphs as values of their own. */
    fun split(): List<RichValue> = paragraphs().map { slice(it.start, it.end) }

    /** The text blocks this text area holds: a paragraph or list item each, a heading each; empty ones left out. */
    fun toBlocks(): List<Block> = paragraphs().mapNotNull(::block)

    private fun block(p: RichParagraph): Block? {
        val first = lines[p.first]
        val kept = first.kept
        val text = text.substring(p.start, p.end)
        val inks = inks.subList(p.start, p.end)
        if (kept != null && kept.text == text && kept.inks == inks && kept.list == first.list && kept.heading == first.heading) return kept.block
        if (first.heading > 0) {
            val t = text.replace(Regex("\\s+"), " ").trim()
            return if (t.isEmpty()) null else Block.Heading(t, first.heading.coerceIn(2, 4))
        }
        val html = RichHtml.sanitize(html(p.start, p.end))
        return if (RichHtml.toPlainText(html).isBlank()) null else Block.Paragraph(html, first.list)
    }

    /**
     * Characters [start, end) as the entry document's HTML: each run in b, i, u, s, a span with its size and colours,
     * and a link; line breaks as <br>. Spaces at the paragraph's two ends are left out, and a space after another is
     * kept as &nbsp; (HTML would join them).
     */
    fun html(start: Int, end: Int): String {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        val out = StringBuilder()
        var i = s
        while (i < e) {
            if (text[i] == '\n') {
                out.append("<br>")
                i++
                continue
            }
            var j = i + 1
            while (j < e && text[j] != '\n' && inks[j] == inks[i]) j++
            out.append(runHtml(text.substring(i, j), inks[i], i > s && text[i - 1] == ' '))
            i = j
        }
        return out.toString()
    }

    companion object {
        val Empty = RichValue("", emptyList(), listOf(RichLine()))

        /** Plain text, its paragraphs a line each. */
        fun plain(text: String): RichValue = RichValue(text, List(text.length) { Ink() }, List(text.count { it == '\n' } + 1) { RichLine() })

        /** A text area from the note's text blocks that follow each other (paragraphs, list items, headings). */
        fun of(blocks: List<Block>): RichValue {
            val text = StringBuilder()
            val inks = ArrayList<Ink>()
            val lines = ArrayList<RichLine>()
            for (b in blocks) {
                if (b !is Block.Paragraph && b !is Block.Heading) continue
                if (lines.isNotEmpty()) {
                    text.append('\n')
                    inks.add(Ink())
                }
                val start = text.length
                when (b) {
                    is Block.Paragraph -> {
                        for (r in HtmlRuns.parse(b.html)) {
                            text.append(r.text)
                            repeat(r.text.length) { inks.add(r.style) }
                        }
                        val own = text.substring(start)
                        lines += RichLine(list = b.list, kept = Kept(b, own, inks.subList(start, inks.size).toList(), b.list, 0))
                        repeat(own.count { it == '\n' }) { lines += RichLine(list = b.list, soft = true) }
                    }
                    is Block.Heading -> {
                        val t = b.text.replace('\n', ' ')
                        text.append(t)
                        repeat(t.length) { inks.add(Ink()) }
                        lines += RichLine(heading = b.level.coerceAtLeast(1), kept = Kept(b, t, List(t.length) { Ink() }, null, b.level.coerceAtLeast(1)))
                    }
                }
            }
            return if (lines.isEmpty()) Empty else RichValue(text.toString(), inks, lines)
        }

        /** A quote's text: its one fragment, line breaks as soft lines. */
        fun ofQuote(html: String): RichValue {
            val v = of(listOf(Block.Paragraph(html)))
            return v.copy(lines = v.lines.map { it.copy(kept = null) })
        }

        /** One run's HTML: its text escaped inside its tags; a space right after another as &nbsp;. */
        private fun runHtml(text: String, ink: Ink, afterSpace: Boolean): String {
            val escaped = StringBuilder()
            var lastSpace = afterSpace
            for (ch in text) {
                if (ch == ' ' && lastSpace) escaped.append("&nbsp;") else escaped.append(RichHtml.escape(ch.toString()))
                lastSpace = ch == ' '
            }
            var inner = escaped.toString()
            if (ink.strike) inner = "<s>$inner</s>"
            if (ink.underline) inner = "<u>$inner</u>"
            if (ink.italic) inner = "<i>$inner</i>"
            if (ink.bold) inner = "<b>$inner</b>"
            val css = listOfNotNull(
                ink.size?.let { "font-size: " + cssSize(it) },
                ink.color?.let { "color: " + cssColor(it) },
                ink.background?.let { "background-color: " + cssColor(it) },
            )
            if (css.isNotEmpty()) inner = "<span style=\"" + RichHtml.escape(css.joinToString("; ")) + "\">" + inner + "</span>"
            // The address goes in as it is: RichHtml.sanitize escapes it, once, as it does every stored link.
            val href = ink.href
            if (href != null) inner = "<a href=\"" + href.replace("\"", "%22") + "\">" + inner + "</a>"
            return inner
        }

        private fun cssSize(size: HtmlRuns.Size): String = when (size) {
            is HtmlRuns.Size.Em -> "${size.value}em"
            is HtmlRuns.Size.Px -> "${size.value}px"
        }

        /** "rgba(196, 98, 45, 1.0)", as the Android editor writes a colour. */
        fun cssColor(argb: Long): String {
            val a = (argb ushr 24) and 0xFF
            val alpha = if (a == 255L) "1.0" else ((a * 100.0 / 255).roundToInt() / 100.0).toString()
            return "rgba(${(argb shr 16) and 0xFF}, ${(argb shr 8) and 0xFF}, ${argb and 0xFF}, $alpha)"
        }
    }
}

/** A colour as the entry keeps it (ARGB). */
fun Color.argb(): Long =
    ((alpha * 255f + 0.5f).toLong() shl 24) or ((red * 255f + 0.5f).toLong() shl 16) or ((green * 255f + 0.5f).toLong() shl 8) or (blue * 255f + 0.5f).toLong()

/**
 * A text area being edited: its content, where the caret and selection are, and the look the next typed text takes
 * when one was picked with nothing selected ([typing]). [onFieldChange] takes what the text field reports and works out
 * what was typed or deleted; the formatting functions are the toolbar's.
 */
class RichTextState(initial: RichValue = RichValue.Empty) {
    var value by mutableStateOf(initial)

    /** At the end to start with: focusing a text area (a tap under the note) goes on writing after its last word. */
    var selection by mutableStateOf(TextRange(initial.text.length))
    private var composition by mutableStateOf<TextRange?>(null)
    var typing by mutableStateOf<Ink?>(null)
        private set

    /** What the text field shows. */
    val field: TextFieldValue get() = TextFieldValue(value.text, selection, composition)

    val text: String get() = value.text

    fun setValue(v: RichValue, caret: Int = v.text.length) {
        value = v
        selection = TextRange(caret.coerceIn(0, v.text.length))
        composition = null
        typing = null
    }

    fun onFieldChange(f: TextFieldValue) {
        val old = value.text
        val new = f.text
        if (old == new) {
            if (f.selection != selection) typing = null
            selection = f.selection
            composition = f.composition
            return
        }
        // What changed: [start, end) of the old text became [start, start + inserted.length) of the new, ending at the
        // caret when there is one (so typing "a" after an "a" adds the second, with the look of where it was typed).
        val shortest = minOf(old.length, new.length)
        var suffix = 0
        while (suffix < shortest && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        if (f.selection.collapsed) suffix = minOf(suffix, new.length - f.selection.start).coerceAtLeast(0)
        var prefix = 0
        val maxPrefix = shortest - suffix
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
        val start = prefix
        val end = old.length - suffix
        val inserted = new.substring(prefix, new.length - suffix)
        val v = value
        // Enter on an empty list item ends the list there (the Android editor's exitListOnEmptyItem).
        if (inserted == "\n" && start == end) {
            val line = v.lineAt(start)
            val l = v.lines[line]
            if (l.list != null && !l.soft && v.lineText(line).isEmpty()) {
                value = v.copy(lines = v.lines.mapIndexed { i, x -> if (i == line) x.copy(list = null) else x })
                selection = TextRange(start)
                composition = null
                return
            }
        }
        // Backspace at the start of a list item: first it stops being one.
        if (inserted.isEmpty() && end == start + 1 && old[start] == '\n') {
            val line = v.lineAt(end)
            val l = v.lines[line]
            if (l.list != null && !l.soft) {
                value = v.copy(lines = v.lines.mapIndexed { i, x -> if (i == line) x.copy(list = null) else x })
                selection = TextRange(end)
                composition = null
                return
            }
        }
        value = v.replace(start, end, inserted, typing ?: v.inkAt(start, end))
        selection = f.selection
        composition = f.composition
        if (inserted.isEmpty()) typing = null
    }

    /** The look where the caret is (or of the selection's first character): what the toolbar shows as on. */
    val currentInk: Ink
        get() {
            typing?.let { return it }
            val s = selection
            if (s.collapsed) return value.inkAt(s.start)
            val i = (s.min until s.max).firstOrNull { value.text[it] != '\n' } ?: return value.inkAt(s.min)
            return value.inks[i]
        }

    /** The kind of the paragraph the caret is in. */
    val currentLine: RichLine get() = value.lines[value.paragraphOf(value.lineAt(selection.min)).first]

    private val selected: IntRange get() = selection.min until selection.max

    /**
     * Turns a look on or off (the Android editor's toggleSpanStyle): over the selection, off when every selected
     * character has it, else on; with nothing selected, for what is typed next.
     */
    fun toggle(has: (Ink) -> Boolean, on: (Ink) -> Ink, off: (Ink) -> Ink) {
        if (selection.collapsed) {
            val now = currentInk
            typing = if (has(now)) off(now) else on(now)
            return
        }
        val all = selected.filter { value.text[it] != '\n' }.all { has(value.inks[it]) }
        value = value.mapInks(selection.min, selection.max) { if (all) off(it) else on(it) }
    }

    /** Sets a look over the selection, or for what is typed next. */
    fun restyle(change: (Ink) -> Ink) {
        if (selection.collapsed) typing = change(currentInk) else value = value.mapInks(selection.min, selection.max, change)
    }

    fun toggleBold() = toggle({ it.bold }, { it.copy(bold = true) }, { it.copy(bold = false) })
    fun toggleItalic() = toggle({ it.italic }, { it.copy(italic = true) }, { it.copy(italic = false) })
    fun toggleUnderline() = toggle({ it.underline }, { it.copy(underline = true) }, { it.copy(underline = false) })
    fun toggleStrike() = toggle({ it.strike }, { it.copy(strike = true) }, { it.copy(strike = false) })
    fun toggleSize(size: HtmlRuns.Size) = toggle({ it.size == size }, { it.copy(size = size) }, { it.copy(size = null) })
    fun toggleHighlight(argb: Long) = toggle({ it.background == argb }, { it.copy(background = argb) }, { it.copy(background = null) })

    /** The paragraphs the selection touches become a heading of [level] (2: Heading, 3: Subheading), or plain text (0). */
    fun setHeading(level: Int) {
        value = value.mapParagraphs(selection.min, selection.max) { it.copy(heading = level, list = if (level > 0) null else it.list) }
    }

    /** Bullets or numbers for the paragraphs the selection touches; off again when they all are such items already. */
    fun toggleList(style: ListStyle) {
        val all = value.paragraphLines(selection.min, selection.max).all { it.list == style }
        value = value.mapParagraphs(selection.min, selection.max) { it.copy(list = if (all) null else style, heading = 0) }
    }
}

/** How much a list item is indented: the read view's 26 dp column for "•" and "2.". */
val LIST_INDENT: TextUnit = 26.sp

/** Every line of a text area at its full line height, the first of a paragraph too. */
private val EVEN_LINES = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** A heading's size against the text (the read view's headingStyle). */
fun headingScale(level: Int): Float = when (level) {
    2 -> 1.32f
    3 -> 1.15f
    else -> 1.04f
}

/**
 * A text area drawn as it will read: every line its own paragraph (list items indented, headings larger), every run in
 * its look. The text field's own '\n' are drawn as spaces, each ending its line's paragraph, so lines with their own
 * indent and height lay out on Skia; a last empty line gets a zero-width space so the caret can stand on it.
 */
class RichTransformation(
    private val value: RichValue,
    private val base: TextStyle,
    private val colors: BtColors,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.text != value.text) return TransformedText(text, OffsetMapping.Identity)
        val t = value.text
        val lastEmpty = value.lineStart(value.lines.lastIndex) == t.length
        val shown = t.replace('\n', ' ') + if (lastEmpty) "​" else ""
        val b = AnnotatedString.Builder(shown)
        var i = 0
        while (i < t.length) {
            if (t[i] == '\n') {
                i++
                continue
            }
            var j = i + 1
            while (j < t.length && t[j] != '\n' && value.inks[j] == value.inks[i]) j++
            val span = spanOf(value.inks[i], colors)
            if (span != SpanStyle()) b.addStyle(span, i, j)
            i = j
        }
        value.lines.forEachIndexed { k, line ->
            val start = value.lineStart(k)
            val end = value.lineEnd(k)
            val paragraphEnd = if (k < value.lines.lastIndex) end + 1 else shown.length
            val heading = line.heading > 0
            b.addStyle(
                ParagraphStyle(
                    textIndent = if (line.list != null) TextIndent(LIST_INDENT, LIST_INDENT) else null,
                    lineHeight = if (heading) base.fontSize * headingScale(line.heading) * 1.3f else TextUnit.Unspecified,
                    // Each line is a paragraph here: its first line keeps its whole height, so the text runs evenly.
                    lineHeightStyle = EVEN_LINES,
                ),
                start, paragraphEnd,
            )
            if (heading && end > start) b.addStyle(SpanStyle(fontSize = base.fontSize * headingScale(line.heading), fontWeight = FontWeight.SemiBold), start, end)
        }
        val mapping = if (!lastEmpty) OffsetMapping.Identity else object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset
            override fun transformedToOriginal(offset: Int): Int = minOf(offset, t.length)
        }
        return TransformedText(b.toAnnotatedString(), mapping)
    }

    override fun equals(other: Any?): Boolean = other is RichTransformation && other.value == value && other.base == base && other.colors == colors

    override fun hashCode(): Int = value.hashCode()
}

/** A run's look as Compose draws it; a colour of a look's role shows as that role of the current look ([lookColor]). */
fun spanOf(ink: Ink, c: BtColors): SpanStyle {
    // A link is underlined in the look's route colour, as the read view shows it.
    val underline = ink.underline || ink.href != null
    val decoration = when {
        underline && ink.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        underline -> TextDecoration.Underline
        ink.strike -> TextDecoration.LineThrough
        else -> null
    }
    return SpanStyle(
        color = if (ink.href != null) c.route else ink.color?.let { lookColor(it, c) } ?: Color.Unspecified,
        background = ink.background?.let { Color(it) } ?: Color.Unspecified,
        fontWeight = if (ink.bold) FontWeight.Bold else null,
        fontStyle = if (ink.italic) FontStyle.Italic else null,
        textDecoration = decoration,
        fontSize = when (val size = ink.size) {
            is HtmlRuns.Size.Px -> size.value.sp
            is HtmlRuns.Size.Em -> size.value.em
            null -> TextUnit.Unspecified
        },
    )
}

/** The markers of a text area's list items: where each starts and its "•" or "2.". */
fun listMarkers(value: RichValue): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    var number = 0
    value.lines.forEachIndexed { k, line ->
        if (line.soft) return@forEachIndexed
        number = if (line.list == ListStyle.Number) number + 1 else 0
        when (line.list) {
            ListStyle.Bullet -> out += value.lineStart(k) to "•"
            ListStyle.Number -> out += value.lineStart(k) to "$number."
            null -> Unit
        }
    }
    return out
}

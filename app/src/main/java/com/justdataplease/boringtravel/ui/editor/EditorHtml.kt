package com.justdataplease.boringtravel.ui.editor

import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.RichHtml

/**
 * Converts between the rich-text editor's HTML (one `<p>` per paragraph, `<ul>/<ol><li>` lists, `<h1>`–`<h6>`
 * headings, inline b/i/u/s/a/span) and the entry document's text blocks: one [Block.Paragraph] per paragraph or list
 * item and one [Block.Heading] per heading. Inline markup outside the document's subset is dropped by
 * [RichHtml.sanitize], keeping its text. Pure Kotlin, so it is unit tested.
 */
object EditorHtml {
    private val tagRegex = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>")
    private val blockTags = setOf("p", "li", "div", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6")
    private val leadingBreaks = Regex("^(\\s|<br\\s*/?>)+", RegexOption.IGNORE_CASE)
    private val trailingBreaks = Regex("(\\s|<br\\s*/?>)+$", RegexOption.IGNORE_CASE)

    /** A stretch of the document: consecutive text blocks (one editor area) or a single other block. */
    sealed interface Segment {
        data class Text(val blocks: List<Block>) : Segment
        data class Single(val block: Block) : Segment
    }

    fun isTextBlock(b: Block): Boolean = b is Block.Paragraph || b is Block.Heading

    /** Groups paragraphs, list items and headings that follow each other into one text segment. */
    fun segments(blocks: List<Block>): List<Segment> {
        val out = ArrayList<Segment>()
        val run = ArrayList<Block>()
        fun flush() {
            if (run.isNotEmpty()) out += Segment.Text(run.toList())
            run.clear()
        }
        for (b in blocks) {
            if (isTextBlock(b)) run += b else {
                flush()
                out += Segment.Single(b)
            }
        }
        flush()
        return out
    }

    /** Text blocks → HTML for the editor's `setHtml`. */
    fun fromBlocks(blocks: List<Block>): String = buildString {
        var open: ListStyle? = null
        fun close() {
            when (open) {
                ListStyle.Bullet -> append("</ul>")
                ListStyle.Number -> append("</ol>")
                null -> Unit
            }
            open = null
        }
        for (b in blocks) {
            val list = (b as? Block.Paragraph)?.list
            if (list != open) {
                close()
                if (list != null) append(if (list == ListStyle.Bullet) "<ul>" else "<ol>")
                open = list
            }
            when (b) {
                is Block.Paragraph -> if (b.list != null) append("<li>").append(b.html).append("</li>") else append("<p>").append(b.html).append("</p>")
                is Block.Heading -> {
                    val level = b.level.coerceIn(1, 6)
                    append("<h").append(level).append('>').append(RichHtml.escape(b.text)).append("</h").append(level).append('>')
                }
                else -> Unit
            }
        }
        close()
    }

    /** The editor's HTML → paragraphs, list items and headings. Empty paragraphs are dropped. */
    fun toBlocks(html: String): List<Block> {
        val out = ArrayList<Block>()
        val lists = ArrayList<ListStyle>()
        var buf: StringBuilder? = null
        var kind: String? = null // "p", "li" or "hN" for the block being filled

        fun flush() {
            val b = buf ?: return
            val k = kind
            buf = null
            kind = null
            val clean = RichHtml.sanitize(
                b.toString().replace("&#32;", " ").replace(leadingBreaks, "").replace(trailingBreaks, ""),
            )
            val text = RichHtml.toPlainText(clean)
            if (text.isBlank()) return
            out += when {
                k != null && k.length == 2 && k[0] == 'h' && k[1].isDigit() ->
                    Block.Heading(text.replace(Regex("\\s+"), " ").trim(), k[1].digitToInt().coerceIn(2, 4))
                k == "li" -> Block.Paragraph(clean, lists.lastOrNull() ?: ListStyle.Bullet)
                else -> Block.Paragraph(clean)
            }
        }

        fun open(k: String) {
            flush()
            buf = StringBuilder()
            kind = k
        }

        var last = 0
        for (m in tagRegex.findAll(html)) {
            val text = html.substring(last, m.range.first)
            if (text.isNotEmpty()) {
                if (buf == null && text.isNotBlank()) open("p")
                buf?.append(text)
            }
            last = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            when {
                tag == "ul" || tag == "ol" -> {
                    flush()
                    if (closing) lists.removeLastOrNull() else lists += if (tag == "ul") ListStyle.Bullet else ListStyle.Number
                }
                tag in blockTags -> if (closing) flush() else open(if (tag == "div" || tag == "blockquote") "p" else tag)
                tag == "br" -> buf?.append("<br>")
                else -> {
                    if (buf == null && !closing) open("p")
                    buf?.append(m.value)
                }
            }
        }
        val tail = html.substring(last)
        if (tail.isNotBlank()) {
            if (buf == null) open("p")
            buf?.append(tail)
        }
        flush()
        return out
    }

    /** For quotes: the editor's paragraphs as one fragment, joined by line breaks. */
    fun toInline(html: String): String = toBlocks(html).joinToString("<br>") { b ->
        when (b) {
            is Block.Paragraph -> b.html
            is Block.Heading -> RichHtml.escape(b.text)
            else -> ""
        }
    }

    fun fromInline(html: String): String = if (html.isBlank()) "" else "<p>$html</p>"
}

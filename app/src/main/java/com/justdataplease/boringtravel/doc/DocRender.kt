package com.justdataplease.boringtravel.doc

/**
 * Turns an [EntryDoc] into plain text (search, previews, AI input) and into semantic HTML. The HTML has no
 * styling of its own: print themes (Wave 2E) wrap it with their CSS, and Google Docs keeps headings, text and photos.
 */
object DocRender {
    /** Content-bearing page sections make forced breaks reliable in Android WebView printing. */
    fun printPages(doc: EntryDoc): List<EntryDoc> {
        val pages = ArrayList<EntryDoc>()
        val run = ArrayList<Block>()
        fun flush() {
            if (run.isNotEmpty()) pages += EntryDoc(doc.font, run.toList())
            run.clear()
        }
        doc.blocks.forEach { if (it == Block.PageBreak) flush() else run += it }
        flush()
        return pages.ifEmpty { listOf(EntryDoc(doc.font)) }
    }

    fun plainText(doc: EntryDoc): String {
        var number = 0 // position in the current numbered list
        return doc.blocks.mapNotNull { b ->
            number = if (b is Block.Paragraph && b.list == ListStyle.Number) number + 1 else 0
            when (b) {
                is Block.Paragraph -> RichHtml.toPlainText(b.html).let { t ->
                    when {
                        t.isBlank() || b.list == null -> t
                        b.list == ListStyle.Bullet -> "• $t"
                        else -> "$number. $t"
                    }
                }
                is Block.Heading -> b.text
                is Block.Quote -> RichHtml.toPlainText(b.html)
                is Block.Photo -> b.caption.ifBlank { null }
                is Block.Album -> null
                is Block.Checklist -> b.items.joinToString("\n") { (if (it.done) "[x] " else "[ ] ") + it.text }
                is Block.Place -> b.name
                Block.Divider, Block.PageBreak, is Block.Unknown -> null
            }
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** First words for list previews. */
    fun preview(doc: EntryDoc, max: Int = 160): String {
        val t = plainText(doc).replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max).substringBeforeLast(' ') + "…"
    }

    /**
     * Semantic HTML for one entry body. [mediaSrc] maps a media id to an image URL (file://, content:// or a data URI)
     * or null to leave the photo out.
     */
    fun html(doc: EntryDoc, mediaSrc: (String) -> String?): String = html(doc, { null }, mediaSrc)

    fun html(doc: EntryDoc, mediaAspect: (String) -> Float?, mediaSrc: (String) -> String?): String = buildString {
        var openList: ListStyle? = null // consecutive list paragraphs share one <ul>/<ol>
        for (b in doc.blocks) {
            val list = (b as? Block.Paragraph)?.list
            if (list != openList) {
                if (openList != null) append(if (openList == ListStyle.Bullet) "</ul>\n" else "</ol>\n")
                if (list != null) append(if (list == ListStyle.Bullet) "<ul>" else "<ol>")
                openList = list
            }
            htmlBlock(b, mediaAspect, mediaSrc)
        }
        if (openList != null) append(if (openList == ListStyle.Bullet) "</ul>\n" else "</ol>\n")
    }

    private fun StringBuilder.htmlBlock(b: Block, mediaAspect: (String) -> Float?, mediaSrc: (String) -> String?) {
        when (b) {
            is Block.Paragraph ->
                if (b.list != null) append("<li>").append(RichHtml.sanitize(b.html)).append("</li>")
                else append("<p>").append(RichHtml.sanitize(b.html)).append("</p>\n")
            is Block.Heading -> {
                val level = b.level.coerceIn(2, 4)
                append("<h").append(level).append('>').append(RichHtml.escape(b.text)).append("</h").append(level).append(">\n")
            }
            is Block.Quote -> append("<blockquote>").append(RichHtml.sanitize(b.html)).append("</blockquote>\n")
            is Block.Photo -> {
                val src = mediaSrc(b.mediaId) ?: return
                append("<figure class=\"photo frame-").append(b.frame.id).append(" align-").append(b.align.id).append('"')
                append(" style=\"--rot: ").append(fmt(b.rotation)).append("deg; --w: ").append(fmt(b.width * 100)).append('%')
                if (b.position != null || b.rotation != 0f || b.align != Align.Center || b.width != 1f) {
                    val placement = PhotoPlacement.of(b, mediaAspect(b.mediaId) ?: 1f)
                    append(";float:none;clear:both;max-width:100%;width:").append(percent(placement.width)).append('%')
                    append(";margin-left:").append(percent(placement.left)).append("%;margin-right:0")
                    append(";margin-top:calc(3mm + ").append(percent(placement.verticalRoom)).append("%)")
                    append(";margin-bottom:calc(4mm + ").append(percent(placement.verticalRoom)).append("%)")
                    append(";transform:rotate(").append(fmt(b.rotation)).append("deg)")
                }
                append("\">")
                append("<img src=\"").append(RichHtml.escape(src)).append("\" alt=\"").append(RichHtml.escape(b.caption)).append("\">")
                if (b.caption.isNotBlank()) append("<figcaption>").append(RichHtml.escape(b.caption)).append("</figcaption>")
                append("</figure>\n")
            }
            is Block.Album -> album(b, mediaSrc)
            is Block.Checklist -> {
                append("<ul class=\"check\">")
                b.items.forEach { append(if (it.done) "<li class=\"done\">" else "<li>").append(RichHtml.escape(it.text)).append("</li>") }
                append("</ul>\n")
            }
            is Block.Place -> append("<p class=\"place\">").append(RichHtml.escape(b.name)).append("</p>\n")
            Block.Divider -> append("<hr>\n")
            Block.PageBreak -> append("<div class=\"page-break\" style=\"break-after:page;page-break-after:always\" aria-hidden=\"true\"></div>\n")
            is Block.Unknown -> Unit
        }
    }

    /**
     * A photo layout: each arrangement a box as tall as its shape needs (`padding-top`), the photos placed in it by
     * percentages and cropped to their spots. The positions are inline so the layout holds without the print CSS
     * (`figure.photo-layout` there sizes it); empty spots and photos without a source stay blank. `--ar` is the whole
     * layout's width / height, so print can keep a tall one within a page.
     */
    private fun StringBuilder.album(b: Block.Album, mediaSrc: (String) -> String?) {
        val srcs = b.mediaIds.map(mediaSrc)
        if (srcs.all { it == null }) return
        val grids = PhotoAlbums.grids(b)
        val w = 100.0
        val g = w * PhotoAlbums.GUTTER
        val heights = grids.map { it.layout.heightFor(w, g) }
        val aspect = w / (heights.sum() + g * (grids.size - 1))
        append("<figure class=\"photo-layout")
        // The frame they all have; photos framed each their own way say so themselves (data-frame).
        val common = b.frame
        if (common != null && common != PhotoFrame.None) append(" frame-").append(common.id)
        append("\" data-layout=\"").append(RichHtml.escape(b.layoutId)).append("\" style=\"--ar:").append(num(aspect)).append("\">")
        grids.forEachIndexed { k, grid ->
            val h = heights[k]
            // A vertical percentage margin or padding is a share of the width, so the gaps match the gutters.
            append("<div class=\"photo-layout-grid\" style=\"position:relative;height:0;padding-top:").append(num(h / w * 100)).append('%')
            if (k > 0) append(";margin-top:").append(num(g)).append('%')
            append("\">")
            grid.layout.place(w, h, g).forEachIndexed { i, r ->
                val src = if (grid.spots[i] != null) srcs[grid.first + i] else null
                if (src != null) {
                    append("<img src=\"").append(RichHtml.escape(src)).append("\" alt=\"\" style=\"position:absolute;left:").append(num(r.x / w * 100))
                        .append("%;top:").append(num(r.y / h * 100)).append("%;width:").append(num(r.w / w * 100))
                        .append("%;height:").append(num(r.h / h * 100)).append("%;object-fit:cover\"")
                    val own = grid.spots[i]?.let(b::frameOf)
                    if (common == null && own != null && own != PhotoFrame.None) append(" data-frame=\"").append(own.id).append('"')
                    append('>')
                }
            }
            append("</div>")
        }
        append("</figure>\n")
    }

    private fun num(v: Double): String = "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

    private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)
    private fun percent(fraction: Float): String = "%.3f".format(java.util.Locale.ROOT, fraction * 100).trimEnd('0').trimEnd('.')
}

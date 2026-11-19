package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.links.MetaTags
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * A post's HTML as note blocks, in the page's order and with the words exactly as written. Paragraphs keep what the
 * journal's text keeps ([RichHtml]: bold, italic, underline, strike-through, links, line breaks) and lose the rest
 * (fonts, colours, classes); headings, quotes, list items and pictures become blocks of their own. A picture comes at
 * the largest size the page offers, with its figure's caption. Videos, audio and embeds become a line with their link;
 * a table becomes one line per row, its cells apart with " · ". Formatting written as styles (Google Docs' classes, a
 * `style` attribute) counts like tags ([CssFormats]). Scripts, styles, forms, navigation and tracking pixels are left
 * out. Pure (jsoup only) and unit tested.
 */
object HtmlBlocks {
    /** [html] (a fragment or a whole page) with the class rules of its own `<style>` elements. */
    fun parse(html: String, baseUrl: String): List<SiteBlock> {
        val doc = Jsoup.parse(html, baseUrl)
        return of(doc.body(), baseUrl, styles(doc))
    }

    /** The blocks of [root]'s contents (a page's article, a feed item's body). */
    fun of(root: Element, baseUrl: String, formats: CssFormats = CssFormats.NONE): List<SiteBlock> = Walker(baseUrl, formats).run(root)

    /** The class rules of every `<style>` element under [root]. */
    fun styles(root: Element): CssFormats = CssFormats.of(root.select("style").joinToString("\n") { it.data() })

    /**
     * A link's address made absolute against [base], as written otherwise (http stays http); Tumblr's link wrappers
     * (href.li/?…, t.umblr.com/redirect?z=…) and Google's (google.com/url?q=…, as Google Docs writes links) give their
     * destination. Null for in-page anchors and anything that isn't a web or mail address.
     */
    fun link(ref: String, base: String): String? {
        val r = ref.trim()
        if (r.isEmpty() || r.startsWith("#")) return null
        if (r.startsWith("mailto:", ignoreCase = true)) return r
        val url = base.toHttpUrlOrNull()?.resolve(r) ?: r.toHttpUrlOrNull() ?: return null
        return unwrap(url).toString()
    }

    private fun unwrap(url: HttpUrl): HttpUrl {
        if (url.host == "href.li") url.encodedQuery?.toHttpUrlOrNull()?.let { return it }
        if (url.host == "t.umblr.com") url.queryParameter("z")?.toHttpUrlOrNull()?.let { return it }
        if ((url.host == "www.google.com" || url.host == "google.com") && url.encodedPath == "/url") {
            (url.queryParameter("q") ?: url.queryParameter("url"))?.toHttpUrlOrNull()?.let { return it }
        }
        return url
    }

    /**
     * The largest copy of the picture [img] shows, as an https address: the original upload (WordPress's
     * data-orig-file), a full-size picture it links to (Blogger links each picture to its large copy), the widest
     * srcset candidate (also of an enclosing picture element), then its own or lazy-loaded address.
     */
    fun pictureUrl(img: Element, base: String): String? = sequence {
        yield(img.attr("data-orig-file"))
        linkedPicture(img)?.let { yield(it) }
        widest(srcsets(img))?.let { yield(it) }
        yield(img.attr("data-large-file"))
        yield(img.attr("src"))
        yield(img.attr("data-src"))
        yield(img.attr("data-lazy-src"))
        yield(img.attr("data-original"))
    }.filter { it.isNotBlank() }.firstNotNullOfOrNull { MetaTags.resolve(it, base) }

    private val pictureFile = Regex("""\.(jpe?g|png|gif|webp)$""", RegexOption.IGNORE_CASE)
    private val candidateRe = Regex("""(\S+)\s+(\d+(?:\.\d+)?)([wx])""")

    private fun linkedPicture(img: Element): String? {
        val a = img.parents().take(3).firstOrNull { it.normalName() == "a" } ?: return null
        val href = a.attr("href").trim()
        val path = href.substringBefore('?').substringBefore('#')
        return href.takeIf { pictureFile.containsMatchIn(path) }
    }

    private fun srcsets(img: Element): List<String> {
        val own = listOf("srcset", "data-srcset", "data-lazy-srcset").map { img.attr(it) }
        val parent = img.parent()
        val sources = if (parent?.normalName() == "picture") {
            parent.children().filter { it.normalName() == "source" && "avif" !in it.attr("type").lowercase() }.map { it.attr("srcset") }
        } else emptyList()
        return (own + sources).filter { it.isNotBlank() }
    }

    /** The candidate with the largest width ("640w"), else the largest density ("2x"). */
    private fun widest(srcsets: List<String>): String? {
        val candidates = srcsets.flatMap { set ->
            candidateRe.findAll(set).map { m -> Triple(m.groupValues[1].trimStart(','), m.groupValues[2].toDouble(), m.groupValues[3]) }.toList()
        }.filter { it.first.isNotEmpty() }
        return (candidates.filter { it.third == "w" }.maxByOrNull { it.second } ?: candidates.maxByOrNull { it.second })?.first
    }

    /** Counters and share buttons (1×1 pictures, stats hosts): never part of the post. */
    internal fun isPixel(img: Element): Boolean {
        val w = img.attr("width").trim()
        val h = img.attr("height").trim()
        if (w == "0" || w == "1" || h == "0" || h == "1") return true
        val src = img.attr("src").lowercase()
        return PIXEL_HOSTS.any { it in src }
    }

    /** WordPress's and others' emoji pictures: their character (the alt text) stays in the line. */
    internal fun isEmoji(img: Element): Boolean {
        val cls = img.className().lowercase()
        return "wp-smiley" in cls || "emoji" in cls || "/images/core/emoji/" in img.attr("src")
    }

    /** A video, audio or embed's address as a link; YouTube and Vimeo players give the page people open. */
    internal fun mediaUrl(e: Element, base: String): String? {
        val raw = when (e.normalName()) {
            "video", "audio" -> e.attr("src").ifBlank { e.children().firstOrNull { it.normalName() == "source" }?.attr("src").orEmpty() }
            "object" -> e.attr("data")
            else -> e.attr("src")
        }
        val url = link(raw, base)?.toHttpUrlOrNull() ?: return null
        if (BLOCKED_EMBEDS.any { it in url.toString() }) return null
        val host = url.host.removePrefix("www.")
        val segments = url.pathSegments
        return when {
            (host == "youtube.com" || host == "youtube-nocookie.com") && segments.firstOrNull() == "embed" && segments.size > 1 ->
                "https://www.youtube.com/watch?v=" + segments[1]
            host == "player.vimeo.com" && segments.firstOrNull() == "video" && segments.size > 1 -> "https://vimeo.com/" + segments[1]
            else -> url.toString()
        }
    }

    private val PIXEL_HOSTS = listOf(
        "pixel.wp.com", "stats.wordpress.com", "feeds.feedburner.com/~r/", "feeds.feedburner.com/~ff/", "pixel.quantserve.com", "facebook.com/tr",
    )
    private val BLOCKED_EMBEDS = listOf("facebook.com/plugins", "googletagmanager.com", "doubleclick.net", "googlesyndication.com", "/like_iframe")

    internal val SKIP = setOf(
        "script", "style", "noscript", "template", "head", "title", "meta", "link", "form", "button", "input", "select",
        "textarea", "option", "svg", "canvas", "map", "nav", "dialog", "math",
    )
    internal val MEDIA = setOf("video", "audio", "iframe", "embed", "object")
    internal val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    internal val INLINE = mapOf("b" to "b", "strong" to "b", "i" to "i", "em" to "i", "u" to "u", "ins" to "u", "s" to "s", "strike" to "s", "del" to "s")
    internal val BLOCKS = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "aside", "address", "center", "dl", "dt", "dd",
        "table", "thead", "tbody", "tfoot", "tr", "caption", "details", "summary", "fieldset", "legend", "figcaption", "hgroup",
    )
}

/**
 * One pass over the tree in document order. Inline text gathers in [line] (the paragraph being written); a block
 * element ends it. Formatting tags still open when a paragraph ends are closed there and opened again in the next one,
 * so every paragraph is well formed. Text inside a quote gathers into one quote until something else (a picture, a
 * heading) comes between.
 */
private class Walker(private val base: String, private val formats: CssFormats) {
    private val out = ArrayList<SiteBlock>()
    private val line = StringBuilder()
    private val open = ArrayList<Pair<String, String>>() // opening tag as written, tag name
    private var hasText = false
    private var pendingSpace = false
    private var afterBreak = false
    private var quoteDepth = 0
    private val quote = ArrayList<String>()
    private val lists = ArrayList<ListStyle>()
    private var item: ListStyle? = null
    private var preDepth = 0

    /** Inside a table row: blocks only space the words apart, the row stays one line. */
    private var flat = 0

    /** The caption for the next picture: a figure with one picture in it. */
    private var caption: String? = null

    fun run(root: Element): List<SiteBlock> {
        children(root)
        endLine()
        endQuote()
        return out
    }

    private fun children(e: Element) {
        for (n in e.childNodes()) node(n)
    }

    private fun node(n: Node) {
        when (n) {
            is TextNode -> text(n.wholeText)
            is Element -> element(n)
        }
    }

    private fun element(e: Element) {
        val tag = e.normalName()
        val styled: List<String> = if (tag in HtmlBlocks.SKIP || tag == "a") emptyList() else styleTags(e)
        if (styled.isEmpty()) structure(e, tag) else styledElement(e, tag, styled)
    }

    /** The formats [e]'s classes or style ask for that aren't already open; underline dropped around a link. */
    private fun styleTags(e: Element): List<String> {
        val asked = formats.of(e)
        if (asked.isEmpty()) return emptyList()
        val linkOnly = "u" in asked && e.ownText().isBlank() && e.selectFirst("a[href]") != null
        return FORMAT_ORDER.filter { it in asked && !(it == "u" && linkOnly) && open.none { o -> o.second == it } }
    }

    /** A styled block keeps its place in the page; its words carry the formats. */
    private fun styledElement(e: Element, tag: String, tags: List<String>) {
        val block = flat == 0 && tag in HtmlBlocks.BLOCKS
        if (block) endLine()
        fun nest(i: Int) {
            if (i == tags.size) {
                if (block) children(e) else structure(e, tag)
                return
            }
            wrapWith(tags[i], "<${tags[i]}>") { nest(i + 1) }
        }
        nest(0)
        if (block) endLine()
    }

    private fun structure(e: Element, tag: String) {
        if (flat > 0 && tag in FLAT_BLOCKS) {
            if (hasText) pendingSpace = true
            children(e)
            if (hasText) pendingSpace = true
            return
        }
        when {
            tag in HtmlBlocks.SKIP -> Unit
            tag == "table" -> table(e)
            tag == "br" -> lineBreak()
            tag == "img" -> image(e)
            tag == "picture" -> e.children().firstOrNull { it.normalName() == "img" }?.let(::image)
            tag == "figure" -> figure(e)
            tag in HtmlBlocks.MEDIA -> media(e)
            tag == "hr" -> {
                endLine()
                endQuote()
                out += SiteBlock.Divider
            }
            tag in HtmlBlocks.HEADINGS -> heading(e, tag)
            tag == "blockquote" -> {
                endLine()
                quoteDepth++
                children(e)
                endLine()
                quoteDepth--
                if (quoteDepth == 0) endQuote()
            }
            tag == "ul" || tag == "ol" -> {
                endLine()
                lists += if (tag == "ol") ListStyle.Number else ListStyle.Bullet
                children(e)
                endLine()
                lists.removeAt(lists.lastIndex)
            }
            tag == "li" -> {
                endLine()
                val outer = item
                item = lists.lastOrNull() ?: ListStyle.Bullet
                children(e)
                endLine()
                item = outer
            }
            tag == "pre" -> {
                endLine()
                preDepth++
                children(e)
                endLine()
                preDepth--
            }
            tag == "a" -> {
                val href = HtmlBlocks.link(e.attr("href"), base)
                if (href == null) children(e) else wrap(e, "a", "<a href=\"" + RichHtml.escape(href) + "\">")
            }
            tag in HtmlBlocks.INLINE -> HtmlBlocks.INLINE.getValue(tag).let { t ->
                if (open.any { it.second == t }) children(e) else wrap(e, t, "<$t>")
            }
            tag == "td" || tag == "th" -> {
                if (hasText) pendingSpace = true
                children(e)
                if (hasText) pendingSpace = true
            }
            tag in HtmlBlocks.BLOCKS -> {
                endLine()
                children(e)
                endLine()
            }
            else -> children(e)
        }
    }

    private fun text(raw: String) {
        if (preDepth > 0) {
            raw.split('\n').forEachIndexed { i, part ->
                if (i > 0) lineBreak()
                if (part.isNotEmpty()) {
                    line.append(RichHtml.escape(part))
                    hasText = true
                    afterBreak = false
                }
            }
            return
        }
        var t = SPACES.replace(raw, " ")
        if (t.isEmpty()) return
        if (t.startsWith(' ')) {
            if (hasText && !afterBreak) pendingSpace = true
            t = t.trimStart(' ')
        }
        if (t.isEmpty()) return
        val trailing = t.endsWith(' ')
        t = t.trimEnd(' ')
        if (pendingSpace && hasText && !afterBreak) line.append(' ')
        line.append(RichHtml.escape(t))
        hasText = true
        afterBreak = false
        pendingSpace = trailing
    }

    private fun lineBreak() {
        line.append("<br>")
        pendingSpace = false
        afterBreak = true
    }

    private fun wrap(e: Element, name: String, openTag: String) = wrapWith(name, openTag) { children(e) }

    private fun wrapWith(name: String, openTag: String, inside: () -> Unit) {
        if (pendingSpace && hasText && !afterBreak) {
            line.append(' ')
            pendingSpace = false
        }
        line.append(openTag)
        open += openTag to name
        inside()
        open.removeAt(open.lastIndex)
        line.append("</").append(name).append('>')
    }

    /** One line per row, cells apart with " · " (empty cells left out); a table inside a cell joins its row. */
    private fun table(e: Element) {
        if (flat > 0) {
            children(e)
            return
        }
        endLine()
        val rows = e.select("tr").filter { r -> r.parents().firstOrNull { it.normalName() == "table" } === e }
        for (row in rows) {
            flat++
            var separatorEnd = -1
            for (cell in row.children().filter { it.normalName() == "td" || it.normalName() == "th" }) {
                // Between cells with words; an empty cell adds nothing.
                if (hasText && line.length != separatorEnd) {
                    line.append(" ·")
                    pendingSpace = true
                    afterBreak = false
                    separatorEnd = line.length
                }
                children(cell)
            }
            flat--
            endLine()
        }
    }

    /** Ends the paragraph being written: a text block, or one more paragraph of the quote. */
    private fun endLine() {
        for (i in open.indices.reversed()) line.append("</").append(open[i].second).append('>')
        val html = tidy(line.toString())
        line.setLength(0)
        open.forEach { line.append(it.first) }
        hasText = false
        pendingSpace = false
        afterBreak = false
        if (RichHtml.toPlainText(html).isBlank()) return
        if (quoteDepth > 0) quote += html else out += SiteBlock.Text(html, item)
    }

    private fun endQuote() {
        if (quote.isEmpty()) return
        out += SiteBlock.Quote(quote.joinToString("<br><br>"))
        quote.clear()
    }

    private fun heading(e: Element, tag: String) {
        endLine()
        endQuote()
        val text = e.text().trim()
        if (text.isNotEmpty()) out += SiteBlock.Heading(text, tag.substring(1).toInt())
        e.select("img").forEach(::image) // a picture inside a heading (a banner) still counts
    }

    private fun image(img: Element) {
        if (HtmlBlocks.isPixel(img)) return
        if (HtmlBlocks.isEmoji(img)) {
            text(img.attr("alt"))
            return
        }
        val url = HtmlBlocks.pictureUrl(img, base) ?: return
        endLine()
        endQuote()
        out += SiteBlock.Picture(url, caption.orEmpty())
        caption = null
    }

    /**
     * A figure: one picture takes the figure's caption; several (a gallery) are followed by the caption as a paragraph
     * of its own.
     */
    private fun figure(e: Element) {
        endLine()
        val captionEl = e.children().firstOrNull { it.normalName() == "figcaption" }
        val captionText = captionEl?.text()?.trim().orEmpty()
        val own = e.select("img").count { img ->
            img.parents().firstOrNull { it.normalName() == "figure" } == e && !HtmlBlocks.isPixel(img) && !HtmlBlocks.isEmoji(img)
        }
        val outer = caption
        caption = if (own == 1 && captionText.isNotEmpty()) captionText else null
        for (n in e.childNodes()) if (n !== captionEl) node(n)
        endLine()
        caption = outer
        if (own != 1 && captionEl != null) {
            children(captionEl)
            endLine()
        }
    }

    private fun media(e: Element) {
        val url = HtmlBlocks.mediaUrl(e, base) ?: return
        endLine()
        val safe = RichHtml.escape(url)
        line.append("<a href=\"").append(safe).append("\">").append(safe).append("</a>")
        hasText = true
        endLine()
    }

    private companion object {
        val FORMAT_ORDER = listOf("b", "i", "u", "s")

        /** Inside a table row these only space words apart. */
        val FLAT_BLOCKS = HtmlBlocks.BLOCKS + HtmlBlocks.HEADINGS + setOf("ul", "ol", "li", "blockquote", "pre", "hr", "figure")
        val SPACES = Regex("[ \\t\\n\\r\\u000C]+")
        val EMPTY_TAG = Regex("<(b|i|u|s)></\\1>|<a href=\"[^\"]*\"></a>")
        val EDGE_START = Regex("^(?:\\s|<br>)+")
        val EDGE_END = Regex("(?:\\s|<br>)+$")

        /** Empty formatting left by a paragraph break, and line breaks at the paragraph's edges. */
        fun tidy(html: String): String {
            var h = html
            while (true) {
                val next = EMPTY_TAG.replace(h, "")
                if (next == h) break
                h = next
            }
            return h.replace(EDGE_START, "").replace(EDGE_END, "")
        }
    }
}

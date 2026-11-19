package com.justdataplease.boringtravel.ios

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import com.justdataplease.boringtravel.book.BookCss
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Image
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathEffect
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.svg.SVGDOM
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * The desktop's stand-in for WebKit drawing the book's pages (iPhone: WKWebView, on screen and into the PDF). Windows has
 * no WebKit, so the book's own HTML and CSS (book/BookHtml.kt, book/BookCss.kt, read in from the Android app) are drawn
 * here by a small renderer that knows that HTML: one fixed-size section per page, every element placed in mm by the
 * page's layout. It reads the stylesheet's rules (the theme's colours, each class's colour, weight, letter spacing,
 * capitals, bands and shadows), sets text with the bundled fonts the page names (each line broken at spaces as the
 * browser does, measured shaped by Skia), lays the flowing text into its columns line by line, draws the photos with
 * their crops, prints' borders, shadows and tape, the stamps, routes, flags and paper (their SVG, through Skia's SVG; its
 * text falls back to the system's fonts) and the highlighter's bands and the scrim in either form (Android's gradients
 * or their WebKit forms: WebKitPrint.forBook). Near WebKit's pages, not the same: WebKit's own drawing is the Mac's.
 */
class BookPageDrawer(private val read: (String) -> ByteArray?) {
    /** One page of the HTML: its size in mm and the section to draw. */
    class Page(val widthMm: Double, val heightMm: Double, val number: Int, internal val section: Element)

    private val typefaces = HashMap<String, Typeface?>()
    private val images = HashMap<String, Image?>()
    private lateinit var css: Css
    private lateinit var vars: Map<String, String>
    private lateinit var svgs: IdentityHashMap<Element, String>

    /** The pages of [html] (a whole book or one page) and what is needed to draw them. */
    fun pages(html: String): List<Page> {
        css = Css(Regex("<style>([\\s\\S]*?)</style>").findAll(html).joinToString("\n") { it.groupValues[1] })
        val doc = Ksoup.parse(html)
        val body = doc.body()
        vars = css.vars + inlineStyle(body.attr("style")).filterKeys { it.startsWith("--") }
        // SVG text exactly as the page has it (an HTML parser lowers its names: viewBox would be lost).
        val sources = svgSources(html)
        svgs = IdentityHashMap()
        doc.select("svg").forEachIndexed { i, e -> sources.getOrNull(i)?.let { svgs[e] = it } }
        return doc.select("section.pg").mapIndexed { i, s ->
            val st = inlineStyle(s.attr("style"))
            Page(len(st["width"]) ?: 148.0, len(st["height"]) ?: 210.0, s.attr("data-n").toIntOrNull() ?: (i + 1), s)
        }
    }

    /** [page] drawn [pxPerMm] pixels to the mm, on white (the page's own ground). The caller closes the picture. */
    fun draw(page: Page, pxPerMm: Float): Image {
        val w = ceil(page.widthMm * pxPerMm).toInt().coerceAtLeast(1)
        val h = ceil(page.heightMm * pxPerMm).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        try {
            val c = surface.canvas
            c.clear(WHITE)
            c.scale(pxPerMm, pxPerMm)
            c.save()
            c.clipRect(Rect.makeWH(page.widthMm.toFloat(), page.heightMm.toFloat()))
            for (child in page.section.children()) element(c, child, vars)
            c.restore()
            return surface.makeImageSnapshot()
        } finally {
            surface.close()
        }
    }

    /** Every page of [html], each drawn [pxPerMm] to the mm. */
    fun drawAll(html: String, pxPerMm: Float): List<Image> = pages(html).map { draw(it, pxPerMm) }

    /** Frees the pictures and fonts read. */
    fun close() {
        images.values.forEach { it?.close() }
        images.clear()
        typefaces.values.forEach { it?.close() }
        typefaces.clear()
    }

    // ---------------------------------------------------------------------------------------------- elements

    private fun element(c: Canvas, e: Element, v: Map<String, String>) {
        val cls = e.classNames()
        val st = inlineStyle(e.attr("style"))
        when {
            e.tagName() == "svg" -> svg(c, e, boxOf(st) ?: Box(0.0, 0.0, 0.0, 0.0), v, fill = "paper" !in cls)
            "trim" in cls -> {
                c.save()
                c.translate(f(len(st["left"]) ?: 0.0), f(len(st["top"]) ?: 0.0))
                for (child in e.children()) element(c, child, v)
                c.restore()
            }
            "art" in cls -> boxOf(st)?.let { b -> fill(c, b, color(st["background"], v) ?: color("var(--paper)", v) ?: WHITE) }
            "dim" in cls -> boxOf(st)?.let { b -> fill(c, b, color(st["background"], v) ?: 0x59000000) }
            "ph" in cls -> photo(c, e, st, cls, v)
            "flow" in cls -> flow(c, e, st, v)
            "t" in cls -> text(c, e, st, cls, v)
            "e" in cls -> deco(c, e, st, cls, v)
        }
    }

    private fun deco(c: Canvas, e: Element, st: Map<String, String>, cls: Set<String>, v: Map<String, String>) {
        val box = boxOf(st) ?: return
        transformed(c, box, st, cls) {
            val opacity = css.value(cls, "opacity")?.toFloatOrNull()
            if (opacity != null && opacity < 1f) c.saveLayer(rect(Box(box.x - box.w, box.y - box.h, box.w * 3, box.h * 3)), Paint().apply { alpha = (opacity * 255).toInt() })
            when {
                "stamp" in cls || "route-box" in cls || "lines" in cls -> e.selectFirst("svg")?.let { svg(c, it, box, v, origin = true) }
                "postage" in cls -> postage(c, e, box, v)
                "flags" in cls -> flags(c, e, box, st, cls, v)
                "rule" in cls -> c.drawLine(f(box.x), f(box.y), f(box.x + box.w), f(box.y), stroke(color("var(--line)", v) ?: 0xFFDADDE0.toInt(), 0.16))
                "washi" in cls -> clipped(c, box, polygon(css.value(setOf("washi"), "clip-path"))) { fill(c, box, color(st["background"], v) ?: 0xFFE6D7A8.toInt()) }
                "corners" in cls -> corners(c, box, st, v)
                "shape" in cls -> shape(c, box, st, v)
                "line" in cls -> e.selectFirst("i")?.let { i ->
                    val s = inlineStyle(i.attr("style"))
                    val sw = len(s["height"]) ?: 0.5
                    c.drawLine(f(box.x), f(box.y + box.h / 2), f(box.x + box.w), f(box.y + box.h / 2), stroke(color(s["background"], v) ?: INK, sw).apply { strokeCap = org.jetbrains.skia.PaintStrokeCap.ROUND })
                }
                "shade" in cls -> background(c, box, st["background"] ?: css.value(setOf("shade"), "background"), v)
            }
            if (opacity != null && opacity < 1f) c.restore()
        }
    }

    /** A photo: its print, flat frame or the page's own background, its crop, tape and band caption; or an empty spot. */
    private fun photo(c: Canvas, e: Element, st: Map<String, String>, cls: Set<String>, v: Map<String, String>) {
        val frame = boxOf(st) ?: return
        transformed(c, frame, st, cls) {
            if ("empty" in cls) {
                fill(c, frame, 0x8CFFFFFF.toInt())
                c.drawRect(rect(frame), stroke(0xFF9AA3AE.toInt(), 0.35).apply { pathEffect = PathEffect.makeDash(floatArrayOf(1.2f, 0.9f), 0f) })
                val r = vars["--L"]?.let(::len)?.times(0.95) ?: 4.75
                c.drawCircle(f(frame.cx), f(frame.cy - r * 0.4), f(r), Paint().apply { color = 0xFFFF5A3C.toInt() })
                val plus = stroke(WHITE, r * 0.18)
                c.drawLine(f(frame.cx - r * 0.46), f(frame.cy - r * 0.4), f(frame.cx + r * 0.46), f(frame.cy - r * 0.4), plus)
                c.drawLine(f(frame.cx), f(frame.cy - r * 0.86), f(frame.cx), f(frame.cy + r * 0.06), plus)
                if (e.text().isNotBlank()) {
                    val font = font("'IBM Plex Mono',monospace", 500, false, pt(7.0))
                    val t = e.text().uppercase()
                    val w = width(t, font, 0.08 * pt(7.0))
                    drawRun(c, t, font, frame.cx - w / 2, frame.cy + r * 1.35, 0xFF6A7076.toInt(), 0.08 * pt(7.0))
                }
                return@transformed
            }
            if ("print" in cls) {
                shadow(c, frame, css.value(cls, "box-shadow"), v)
                fill(c, frame, WHITE)
            }
            for (child in e.children()) {
                val cs = inlineStyle(child.attr("style"))
                val ccls = child.classNames()
                when {
                    "im" in ccls -> {
                        val inner = (boxOf(cs) ?: Box(0.0, 0.0, frame.w, frame.h)).let { Box(frame.x + it.x, frame.y + it.y, it.w, it.h) }
                        val ground = color(css.value(setOf("ph", "im") + cls, "background", inside = true), v) ?: 0xFFE9ECEF.toInt()
                        fill(c, inner, ground)
                        child.selectFirst("img")?.let { img -> image(c, img, inner) }
                    }
                    "tape" in ccls -> {
                        val tb = boxOf(cs) ?: continue
                        val abs = Box(frame.x + tb.x, frame.y + tb.y, tb.w, tb.h)
                        transformed(c, abs, cs, ccls) {
                            clipped(c, abs, polygon(css.value(setOf("tape"), "clip-path"))) { fill(c, abs, color("var(--tape)", v) ?: 0xDBE8E0C4.toInt()) }
                        }
                    }
                    "band" in ccls -> {
                        val top = len(cs["top"]) ?: 0.0
                        val h = len(cs["height"]) ?: 0.0
                        val size = pt(cs["font-size"]) ?: pt(7.6)
                        val bold = (css.value(setOf("band"), "font-weight")?.toIntOrNull() ?: 400) >= 600
                        val font = font(cs["font-family"] ?: "'Literata',serif", if (bold) 600 else 400, cs["font-style"] == "italic", size)
                        val ink = color(css.value(ccls, "color"), v) ?: INK
                        val words = child.text()
                        val lines = breakLines(words, frame.w - 3.0, font, 0.0)
                        val lh = size * 1.1
                        var y = frame.y + top + (h - lines.size * lh) / 2
                        for (line in lines) {
                            val w = width(line, font, 0.0)
                            drawRun(c, line, font, frame.x + (frame.w - w) / 2, baseline(y, lh, font), ink, 0.0)
                            y += lh
                        }
                    }
                }
            }
        }
    }

    /** A photo's picture, placed as its style says (percentages of its frame) and cut to the frame. */
    private fun image(c: Canvas, img: Element, inner: Box) {
        val src = RichHtml.decodeEntities(img.attr("src"))
        val picture = picture(src) ?: return
        val s = inlineStyle(img.attr("style"))
        fun pct(key: String, of: Double) = s[key]?.removeSuffix("%")?.toDoubleOrNull()?.let { it / 100.0 * of }
        val x = inner.x + (pct("left", inner.w) ?: 0.0)
        val y = inner.y + (pct("top", inner.h) ?: 0.0)
        val w = pct("width", inner.w) ?: inner.w
        val h = pct("height", inner.h) ?: inner.h
        c.save()
        c.clipRect(rect(inner))
        c.drawImageRect(picture, Rect.makeWH(picture.width.toFloat(), picture.height.toFloat()), Rect.makeXYWH(f(x), f(y), f(w), f(h)), FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true)
        c.restore()
    }

    // ---------------------------------------------------------------------------------------------- text

    /** One short text (`.t`): its face, size, line height, colour, capitals, letter spacing; its highlighter band. */
    private fun text(c: Canvas, e: Element, st: Map<String, String>, cls: Set<String>, v: Map<String, String>) {
        val x = len(st["left"]) ?: return
        val y = len(st["top"]) ?: return
        val w = len(st["width"]) ?: return
        val h = len(st["height"]) ?: len(st["min-height"]) ?: 0.0
        val box = Box(x, y, w, h)
        val size = pt(st["font-size"]) ?: pt(9.6)
        val weight = st["font-weight"]?.toIntOrNull() ?: 400
        val italic = st["font-style"] == "italic"
        val family = st["font-family"]?.let { resolve(it, v) } ?: "'Literata',serif"
        val font = font(family, weight, italic, size)
        val lh = len(st["line-height"]) ?: (size * 1.2)
        val ink = color(st["color"], v) ?: color(css.value(cls, "color"), v) ?: INK
        val upper = css.value(cls, "text-transform") == "uppercase" || st["text-transform"] == "uppercase"
        val spacing = em(css.value(cls, "letter-spacing"), size)
        val align = st["text-align"] ?: "left"
        val mark = e.selectFirst("span.mk") != null
        val raw = buildString {
            fun walk(el: Element) {
                for (n in el.childNodes()) when (n) {
                    is TextNode -> append(n.text())
                    is Element -> if (n.tagName() == "br") append('\n') else walk(n)
                }
            }
            walk(e)
        }.replace(Regex("[ \\t\\r]+"), " ").trim()
        val words = if (upper) raw.uppercase() else raw
        if (words.isBlank()) return
        val isTag = "tag" in cls
        transformed(c, box, st, cls) {
            if (isTag) {
                shadow(c, box, css.value(setOf("tag"), "box-shadow"), v)
                fill(c, box, WHITE)
                c.save()
                c.clipRect(rect(box))
                val tw = width(words, font, spacing)
                drawRun(c, words, font, box.x + (box.w - tw) / 2, baseline(box.y + (box.h - lh) / 2, lh, font), ink, spacing)
                c.restore()
                return@transformed
            }
            val padTop = len(st["padding-top"]) ?: 0.0
            val lines = breakLines(words, w, font, spacing)
            val shadow = css.value(cls, "text-shadow")
            val band = if (mark) band(if ("mono" in cls) css.descendant(".t.mono .mk", "background") ?: css.value(setOf("mk"), "background") else css.value(setOf("mk"), "background"), v) else null
            val padX = if (mark) len(if ("mono" in cls) css.descendant(".t.mono .mk", "padding")?.split(' ')?.getOrNull(1) else css.value(setOf("mk"), "padding")?.split(' ')?.getOrNull(1)) ?: 0.7 else 0.0
            lines.forEachIndexed { i, line ->
                val lw = width(line, font, spacing)
                val lx = when (align) {
                    "center" -> x + (w - lw) / 2
                    "right" -> x + w - lw - padX
                    else -> x + padX
                }
                val top = y + padTop + i * lh
                val base = baseline(top, lh, font)
                if (band != null) {
                    val m = font.metrics
                    val contentTop = base + m.ascent
                    val contentH = m.descent - m.ascent
                    c.drawRect(Rect.makeLTRB(f(lx - padX), f(contentTop + contentH * band.first), f(lx + lw + padX), f(contentTop + contentH * band.second)), Paint().apply { color = band.third })
                }
                if (shadow != null) {
                    val p = Paint().apply { color = 0x59000000; maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 0.45f) }
                    drawRun(c, line, font, lx, base + 0.2, p.color, spacing, p)
                }
                drawRun(c, line, font, lx, base, ink, spacing)
            }
        }
    }

    /** The flowing text: its blocks poured into the columns line by line, as `column-fill:auto` pours them. */
    private fun flow(c: Canvas, e: Element, st: Map<String, String>, v0: Map<String, String>) {
        val v = v0 + st.filterKeys { it.startsWith("--") }
        val x = len(st["left"]) ?: return
        val y = len(st["top"]) ?: return
        val w = len(st["width"]) ?: return
        val h = len(st["height"]) ?: return
        val cols = st["column-count"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val gap = len(st["column-gap"]) ?: 0.0
        val colW = (w - (cols - 1) * gap) / cols
        val L = len(st["line-height"]) ?: 5.0
        val bodyPt = pt(st["font-size"]) ?: pt(9.6)
        val bodyFamily = resolve(st["font-family"] ?: "var(--body)", v)
        val sb = len(v["--sb"]) ?: 0.0
        val sm = len(v["--sm"]) ?: 0.0
        val text = color(css.descendant(".flow", "color"), v) ?: color("var(--text)", v) ?: INK
        val angle = rotation(st["transform"])
        c.save()
        if (angle != 0f) c.rotate(angle, f(x + w / 2), f(y + h / 2))
        c.clipRect(Rect.makeXYWH(f(x), f(y), f(w), f(h + (len(st["padding-bottom"]) ?: 0.0))))
        var col = 0
        var row = 0.0
        var first = true
        val boldBand = band(css.descendant(".flow b", "background"), v)
        val boldWeight = css.descendant(".flow b", "font-weight")?.toIntOrNull()
        fun nextColumn(): Boolean {
            col++
            row = 0.0
            return col < cols
        }
        for (block in e.children()) {
            val cls = block.classNames()
            val bs = inlineStyle(block.attr("style"))
            val tag = block.tagName()
            val isTitle = tag == "h3"
            val isHead = tag == "h4"
            val isMono = "meta" in cls || "place" in cls
            val family = when {
                isTitle || isHead || "q" in cls -> resolve(bs["font-family"] ?: bodyFamily, v)
                isMono -> resolve("var(--mono)", v)
                else -> bodyFamily
            }
            val size = when {
                isTitle || isHead -> pt(bs["font-size"]) ?: bodyPt
                isMono -> pt(bs["font-size"]) ?: len(v["--mono-pt"])?.let { pt("${it * 72 / 25.4}pt") } ?: bodyPt
                else -> bodyPt
            }
            val weight = bs["font-weight"]?.toIntOrNull() ?: if (isMono) 500 else 400
            val italic = "q" in cls
            val lh = if (isTitle || isHead) len(bs["line-height"]) ?: (2 * L) else L
            val ink = when {
                isTitle -> color(css.descendant(".flow .title", "color"), v) ?: color("var(--ink)", v) ?: text
                isHead -> color(css.descendant(".flow .head", "color"), v) ?: color("var(--ink)", v) ?: text
                isMono -> color(css.descendant(".flow .meta", "color"), v) ?: color("var(--muted)", v) ?: text
                "q" in cls -> color(css.descendant(".flow .q", "color"), v) ?: text
                else -> text
            }
            val upper = isMono || "up" in cls || bs["text-transform"] == "uppercase"
            val spacing = if (isMono) 0.09 * size else 0.0
            val padLeft = when {
                "q" in cls -> len(v["--qi"]) ?: 0.0
                "li" in cls -> len(v["--li"]) ?: 0.0
                "ck" in cls -> len(v["--ci"]) ?: 0.0
                else -> 0.0
            }
            val indent = if ("ind" in cls) len(v["--ind"]) ?: 0.0 else 0.0
            val shift = when {
                isTitle || isHead -> len(bs["top"]) ?: 0.0
                isMono -> sm
                else -> sb
            }
            val font = font(family, weight, italic, size)
            val runs = runs(block, font, family, size, weight, italic, upper, boldWeight)
            val lines = layoutRuns(runs, colW - padLeft, indent, spacing)
            if (isTitle || isHead) {
                val blockH = len(bs["height"]) ?: (lines.size * lh)
                val margin = if (isTitle && !first && row > 0.0) L else 0.0
                if (row + margin + blockH > h + 0.01 && row > 0.0) { if (!nextColumn()) break }
                if (row > 0.0) row += margin
                val left = x + col * (colW + gap)
                lines.forEachIndexed { i, line -> drawLine(c, line, left + padLeft, y + row + shift + i * lh, lh, ink, spacing, boldBand) }
                row += blockH
            } else {
                var stop = false
                lines.forEachIndexed { i, line ->
                    if (stop) return@forEachIndexed
                    if (row + lh > h + 0.01) { if (!nextColumn()) { stop = true; return@forEachIndexed } }
                    val left = x + col * (colW + gap)
                    val top = y + row
                    if (i == 0) marker(c, block, cls, left, top, L, sb, ink, len(v["--li"]) ?: 4.5, font)
                    drawLine(c, line, left + padLeft + if (i == 0) indent else 0.0, top + shift, lh, ink, spacing, boldBand)
                    row += lh
                }
                if (stop) break
            }
            first = false
            if (row >= h - 0.01 && block != e.children().last()) { if (!nextColumn()) break }
        }
        c.restore()
    }

    /** A list item's bullet or number, a checklist item's box (and tick), at the start of its first line. */
    private fun marker(c: Canvas, block: Element, cls: Set<String>, left: Double, top: Double, L: Double, sb: Double, ink: Int, li: Double, font: Font) {
        if ("cont" in cls) return
        when {
            "li" in cls && "num" in cls -> {
                drawRun(c, "${block.attr("data-n")}.", font, left, baseline(top + sb, L, font), (ink and 0x00FFFFFF) or (0xCC shl 24), 0.0)
            }
            "li" in cls -> {
                c.drawCircle(f(left + li * 0.3 + 0.55), f(top + L * 0.48 + 0.55), 0.55f, Paint().apply { color = (ink and 0x00FFFFFF) or (0xBF shl 24) })
            }
            "ck" in cls -> {
                val b = Box(left, top + L * 0.3, 2.5, 2.5)
                c.drawRRect(RRect.makeXYWH(f(b.x), f(b.y), 2.5f, 2.5f, 0.5f), stroke(ink, 0.25))
                if ("done" in cls) {
                    val p = PathBuilder().moveTo(f(b.x + 0.55), f(b.y + 1.3)).lineTo(f(b.x + 1.1), f(b.y + 1.9)).lineTo(f(b.x + 2.0), f(b.y + 0.5)).detach()
                    c.drawPath(p, stroke(ink, 0.35))
                    p.close()
                }
            }
        }
    }

    /** A run of words with one look (a paragraph's bold, italic, colour, highlight). */
    private class Run(val text: String, val font: Font, val bold: Boolean, val color: Int?, val back: Int?, val underline: Boolean, val strike: Boolean)

    private class Piece(val text: String, val run: Run, val width: Double, val space: Boolean)

    private class Line(val pieces: List<Piece>)

    /** A block's inline HTML (the journal's sanitized subset) as runs of words in their looks. */
    private fun runs(block: Element, base: Font, family: String, size: Double, weight: Int, italic: Boolean, upper: Boolean, boldWeight: Int?): List<Run> {
        val out = ArrayList<Run>()
        fun walk(el: Element, bold: Boolean, it: Boolean, color: Int?, back: Int?, under: Boolean, strike: Boolean) {
            for (n in el.childNodes()) when (n) {
                is TextNode -> {
                    val t = n.text().let { if (upper) it.uppercase() else it }
                    if (t.isNotEmpty()) {
                        val f = if (!bold && !it) base else font(family, if (bold) (boldWeight ?: weight) else weight, it || italic, size)
                        out += Run(t, f, bold, color, back, under, strike)
                    }
                }
                is Element -> when (n.tagName()) {
                    "br" -> out += Run("\n", base, false, null, null, false, false)
                    "b", "strong" -> walk(n, true, it, color, back, under, strike)
                    "i", "em" -> walk(n, bold, true, color, back, under, strike)
                    "u" -> walk(n, bold, it, color, back, true, strike)
                    "s", "del" -> walk(n, bold, it, color, back, under, true)
                    "span" -> {
                        val s = inlineStyle(n.attr("style"))
                        walk(
                            n, bold || (s["font-weight"]?.let { w -> w == "bold" || (w.toIntOrNull() ?: 400) >= 600 } ?: false),
                            it || s["font-style"] == "italic", color(s["color"], vars) ?: color, color(s["background-color"], vars) ?: back,
                            under || s["text-decoration"]?.contains("underline") == true, strike || s["text-decoration"]?.contains("line-through") == true,
                        )
                    }
                    else -> walk(n, bold, it, color, back, under, strike)
                }
            }
        }
        walk(block, false, false, null, null, false, false)
        return out
    }

    /** Plain [text] in one [font] broken into lines [width] mm wide, at spaces and at its line breaks. */
    private fun breakLines(text: String, width: Double, font: Font, spacing: Double): List<String> =
        layoutRuns(listOf(Run(text.replace("\n", " \n "), font, false, null, null, false, false)).flatMap { run ->
            run.text.split('\n').flatMapIndexed { i, part -> listOfNotNull(Run("\n", font, false, null, null, false, false).takeIf { i > 0 }, Run(part, font, false, null, null, false, false)) }
        }, width, 0.0, spacing).map { line -> line.pieces.joinToString("") { it.text }.trim() }

    /** Runs broken into lines [width] mm wide (the first [indent] narrower), at spaces; a word wider than a line anywhere. */
    private fun layoutRuns(runs: List<Run>, width: Double, indent: Double, spacing: Double): List<Line> {
        val lines = ArrayList<Line>()
        var current = ArrayList<Piece>()
        var used = indent
        fun breakLine() {
            while (current.lastOrNull()?.space == true) current.removeAt(current.lastIndex)
            lines += Line(current)
            current = ArrayList()
            used = 0.0
        }
        for (run in runs) {
            if (run.text == "\n") { breakLine(); continue }
            val tokens = Regex("\\s+|[^\\s]+").findAll(run.text.replace(Regex("\\s+"), " ")).map { it.value }
            for (t in tokens) {
                if (t.isBlank()) {
                    if (current.isEmpty()) continue
                    val sw = width(" ", run.font, spacing)
                    current += Piece(" ", run, sw, true)
                    used += sw
                    continue
                }
                var word = t
                var ww = width(word, run.font, spacing)
                if (used + ww > width + 0.01 && current.any { !it.space }) breakLine()
                while (ww > width && word.length > 1) {
                    // overflow-wrap:anywhere: as much as fits on this line.
                    var k = word.length - 1
                    while (k > 1 && width(word.substring(0, k), run.font, spacing) > width - used) k--
                    current += Piece(word.substring(0, k), run, width(word.substring(0, k), run.font, spacing), false)
                    breakLine()
                    word = word.substring(k)
                    ww = width(word, run.font, spacing)
                }
                current += Piece(word, run, ww, false)
                used += ww
            }
        }
        if (current.isNotEmpty() || lines.isEmpty()) breakLine()
        return lines
    }

    private fun drawLine(c: Canvas, line: Line, left: Double, top: Double, lh: Double, ink: Int, spacing: Double, boldBand: Triple<Double, Double, Int>?) {
        var x = left
        val baseFont = line.pieces.firstOrNull()?.run?.font ?: return
        val base = baseline(top, lh, baseFont)
        for (p in line.pieces) {
            val m = p.run.font.metrics
            val contentTop = base + m.ascent
            val contentH = m.descent - m.ascent
            if (p.run.back != null) c.drawRect(Rect.makeXYWH(f(x), f(contentTop), f(p.width), f(contentH)), Paint().apply { color = p.run.back })
            if (p.run.bold && boldBand != null) {
                c.drawRect(Rect.makeLTRB(f(x), f(contentTop + contentH * boldBand.first), f(x + p.width), f(contentTop + contentH * boldBand.second)), Paint().apply { color = boldBand.third })
            }
            if (!p.space) drawRun(c, p.text, p.run.font, x, base, p.run.color ?: ink, spacing)
            if (p.run.underline) c.drawLine(f(x), f(base + 0.4), f(x + p.width), f(base + 0.4), stroke(p.run.color ?: ink, 0.18))
            if (p.run.strike) c.drawLine(f(x), f(base - contentH * 0.28), f(x + p.width), f(base - contentH * 0.28), stroke(p.run.color ?: ink, 0.18))
            x += p.width
        }
    }

    // ---------------------------------------------------------------------------------------------- the rest

    /** Postcard's dashed postage stamp: the top word, the big number, the place. */
    private fun postage(c: Canvas, e: Element, box: Box, v: Map<String, String>) {
        val red = color("var(--red)", v) ?: 0xFFC4423A.toInt()
        fill(c, box, WHITE)
        c.drawRect(rect(box.inset(0.25)), stroke(red, 0.5).apply { pathEffect = PathEffect.makeDash(floatArrayOf(1.4f, 1.0f), 0f) })
        val small = font("'IBM Plex Mono',monospace", 600, false, pt(6.2))
        val big = font("'Literata',serif", 700, false, pt(19.0))
        val parts = e.children().map { it.tagName() to it.text() }
        val heights = parts.map { (t, _) -> if (t == "b") pt(19.0) * 1.05 else pt(6.2) * 1.25 }
        var y = box.y + (box.h - heights.sum()) / 2
        parts.forEachIndexed { i, (tag, words) ->
            val font = if (tag == "b") big else small
            val text = if (tag == "b") words else words.uppercase()
            val spacing = if (tag == "b") 0.0 else 0.06 * pt(6.2)
            val w = width(text, font, spacing)
            drawRun(c, text, font, box.x + (box.w - w) / 2, baseline(y, heights[i], font), red, spacing)
            y += heights[i]
        }
    }

    /** Flags in a row: stickers with a white edge, turned a little, or flat with a thin outline. */
    private fun flags(c: Canvas, e: Element, box: Box, st: Map<String, String>, cls: Set<String>, v: Map<String, String>) {
        val sticker = "sticker" in cls
        val items = e.select("span.fl").mapNotNull { span ->
            val s = span.selectFirst("svg") ?: return@mapNotNull null
            val w = len(s.attr("width")) ?: return@mapNotNull null
            val h = len(s.attr("height")) ?: return@mapNotNull null
            Triple(s, w, h)
        }
        if (items.isEmpty()) return
        val pad = if (sticker) 0.45 else 0.0
        val gap = 1.3
        val total = items.sumOf { it.second + 2 * pad } + gap * (items.size - 1)
        var x = when (st["justify-content"]) {
            "center" -> box.x + (box.w - total) / 2
            "flex-end" -> box.x + box.w - total
            else -> box.x
        }
        items.forEachIndexed { i, (svgEl, w, h) ->
            val outer = Box(x, box.y + (box.h - h - 2 * pad) / 2, w + 2 * pad, h + 2 * pad)
            c.save()
            if (sticker) {
                c.rotate(if (i % 2 == 1) 3.5f else -4f, f(outer.cx), f(outer.cy))
                shadow(c, outer, "0 .25mm .6mm rgba(20,24,40,.28)", v)
                c.drawRRect(RRect.makeXYWH(f(outer.x), f(outer.y), f(outer.w), f(outer.h), 0.5f), Paint().apply { color = WHITE })
            }
            svg(c, svgEl, Box(outer.x + pad, outer.y + pad, w, h), v, origin = false)
            if (!sticker) c.drawRect(rect(Box(outer.x, outer.y, w, h)), stroke(0x47000000, 0.12))
            c.restore()
            x += outer.w + gap
        }
    }

    private fun corners(c: Canvas, box: Box, st: Map<String, String>, v: Map<String, String>) {
        val cc = color(st["--cc"], v) ?: 0xFF2B2B2B.toInt()
        val cs = len(st["--cs"]) ?: 4.0
        val p = Paint().apply { color = cc }
        fun tri(a: Pair<Double, Double>, b: Pair<Double, Double>, d: Pair<Double, Double>) {
            val path = PathBuilder().moveTo(f(a.first), f(a.second)).lineTo(f(b.first), f(b.second)).lineTo(f(d.first), f(d.second)).closePath().detach()
            c.drawPath(path, p)
            path.close()
        }
        tri(box.x to box.y, box.x + cs to box.y, box.x to box.y + cs)
        tri(box.x + box.w - cs to box.y, box.x + box.w to box.y, box.x + box.w to box.y + cs)
        tri(box.x + box.w to box.y + box.h - cs, box.x + box.w to box.y + box.h, box.x + box.w - cs to box.y + box.h)
        tri(box.x to box.y + box.h - cs, box.x + cs to box.y + box.h, box.x to box.y + box.h)
    }

    private fun shape(c: Canvas, box: Box, st: Map<String, String>, v: Map<String, String>) {
        val oval = st["border-radius"] == "50%"
        color(st["background"], v)?.takeIf { (it ushr 24) != 0 }?.let { fillColor ->
            val p = Paint().apply { color = fillColor }
            if (oval) c.drawOval(rect(box), p) else c.drawRect(rect(box), p)
        }
        st["border"]?.split(' ')?.let { parts ->
            val sw = len(parts.getOrNull(0)) ?: return@let
            val ink = color(parts.getOrNull(2), v) ?: return@let
            val inner = box.inset(sw / 2)
            if (oval) c.drawOval(rect(inner), stroke(ink, sw)) else c.drawRect(rect(inner), stroke(ink, sw))
        }
    }

    /** A box's background: a colour, a picture (a data: PNG or a file, stretched as `0 0/100% 100%` says), or a gradient. */
    private fun background(c: Canvas, box: Box, value: String?, v: Map<String, String>) {
        val bg = value?.let { resolve(it, v) } ?: return
        val url = Regex("url\\(([^)]+)\\)").find(bg)?.groupValues?.get(1)?.trim('\'', '"')
        if (url != null) {
            val picture = picture(url) ?: return
            c.drawImageRect(picture, Rect.makeWH(picture.width.toFloat(), picture.height.toFloat()), rect(box), FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), null, true)
            return
        }
        val g = Regex("linear-gradient\\((.*)\\)").find(bg)?.groupValues?.get(1) ?: run { color(bg, v)?.let { fill(c, box, it) }; return }
        val colors = splitTop(g).mapNotNull { color(it.trim().substringBefore(' ').let { s -> if (s.startsWith("rgba")) it.trim() else s }, v) }
        if (colors.size < 2) return
        // Top to bottom, between the first and the last colour, in thin rows.
        val (a, b) = colors.first() to colors.last()
        val rows = 96
        for (i in 0 until rows) {
            val t = (i + 0.5) / rows
            fun mix(shift: Int) = ((((a ushr shift) and 0xFF) * (1 - t) + ((b ushr shift) and 0xFF) * t).toInt() and 0xFF) shl shift
            val argb = mix(24) or mix(16) or mix(8) or mix(0)
            c.drawRect(Rect.makeXYWH(f(box.x), f(box.y + box.h * i / rows), f(box.w), f(box.h / rows + 0.05)), Paint().apply { color = argb })
        }
    }

    /** An SVG of the page, drawn into [box]: [origin] = the SVG fills the box ([box] relative to nothing else). */
    private fun svg(c: Canvas, e: Element, box: Box, v: Map<String, String>, fill: Boolean = true, origin: Boolean = false) {
        val source = svgs[e] ?: return
        // The root's own size (mm), its CSS box and its scaling give way to the box's (Skia's SVG reads a CSS width in
        // a style attribute as the SVG's own, at 90 dpi).
        val root = Regex("^<svg[^>]*>").find(source) ?: return
        val none = "preserveAspectRatio=\"none\"" in root.value
        val open = root.value.replace(Regex("\\s(width|height|style|class|preserveAspectRatio)=\"[^\"]*\""), "")
            .replaceFirst("<svg", "<svg width=\"100%\" height=\"100%\" preserveAspectRatio=\"${if (none) "none" else "xMidYMid meet"}\"")
        val b = if (box.w > 0 && box.h > 0) box else return
        // Its words are drawn here with the page's own fonts (Skia's SVG would draw them in the system's).
        val (shapes, words) = SvgWords.split(open + source.substring(root.value.length))
        runCatching {
            val data = Data.makeFromBytes(shapes.encodeToByteArray())
            val dom = SVGDOM(data)
            try {
                dom.setContainerSize(f(b.w), f(b.h))
                c.save()
                c.translate(f(b.x), f(b.y))
                if (!origin && fill) c.clipRect(Rect.makeWH(f(b.w), f(b.h)))
                dom.render(c)
                c.restore()
            } finally {
                dom.close()
                data.close()
            }
        }
        if (words.isEmpty()) return
        val vb = Regex("viewBox=\"([^\"]+)\"").find(root.value)?.groupValues?.get(1)?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 } ?: listOf(0.0, 0.0, b.w, b.h)
        val sx = b.w / vb[2]
        val sy = b.h / vb[3]
        val s = min(sx, sy)
        c.save()
        if (none) {
            c.translate(f(b.x - vb[0] * sx), f(b.y - vb[1] * sy))
            c.scale(f(sx), f(sy))
        } else {
            c.translate(f(b.x + (b.w - vb[2] * s) / 2 - vb[0] * s), f(b.y + (b.h - vb[3] * s) / 2 - vb[1] * s))
            c.scale(f(s), f(s))
        }
        for (w in words) svgWord(c, w, v)
        c.restore()
    }

    /** One word of an SVG in its own units: along its arc when it has one (a day stamp's ring), else at its x and y. */
    private fun svgWord(c: Canvas, w: SvgWords.Word, v: Map<String, String>) {
        val font = font(w.family, w.weight, false, w.size)
        val ink = color(w.fill, v) ?: INK
        val arc = w.arc
        if (arc == null) {
            val width = width(w.text, font, w.spacing)
            val x = when (w.anchor) { "middle" -> w.x - width / 2; "end" -> w.x - width; else -> w.x }
            drawRun(c, w.text, font, x, w.y, ink, w.spacing)
            return
        }
        val advances = w.text.map { width(it.toString(), font, 0.0) }
        val total = advances.sum() + w.spacing * w.text.length
        var along = arc.length * w.offset - when (w.anchor) { "middle" -> total / 2; "end" -> total; else -> 0.0 }
        val p = Paint().apply { color = ink; isAntiAlias = true }
        w.text.forEachIndexed { i, ch ->
            val mid = along + advances[i] / 2
            val a = arc.start + arc.dir * mid / arc.r
            val px = arc.cx + arc.r * kotlin.math.cos(a)
            val py = arc.cy + arc.r * kotlin.math.sin(a)
            val tangent = Math.toDegrees(a) + arc.dir * 90.0
            c.save()
            c.translate(f(px), f(py))
            c.rotate(f(tangent))
            val line = TextLine.make(ch.toString(), font)
            c.drawTextLine(line, f(-advances[i] / 2), 0f, p)
            line.close()
            c.restore()
            along += advances[i] + w.spacing
        }
    }

    /** The CSS box-shadow's first shadow: offsets, blur and colour. */
    private fun shadow(c: Canvas, box: Box, value: String?, v: Map<String, String>) {
        val first = value?.let { splitTop(resolve(it, v)).firstOrNull() }?.trim() ?: return
        val parts = Regex("rgba?\\([^)]*\\)|#[0-9A-Fa-f]+|\\S+").findAll(first).map { it.value }.toList()
        val nums = parts.mapNotNull { len(it) }
        val ink = parts.firstNotNullOfOrNull { color(it, v) } ?: return
        val dx = nums.getOrElse(0) { 0.0 }
        val dy = nums.getOrElse(1) { 0.0 }
        val blur = nums.getOrElse(2) { 0.0 }
        val p = Paint().apply {
            color = ink
            if (blur > 0) maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, f(blur / 2).coerceAtLeast(0.01f))
        }
        c.drawRect(Rect.makeXYWH(f(box.x + dx), f(box.y + dy), f(box.w), f(box.h)), p)
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** Draws [block] with the element's rotation (its box's centre, or the origin its class names). */
    private inline fun transformed(c: Canvas, box: Box, st: Map<String, String>, cls: Set<String>, block: () -> Unit) {
        val angle = rotation(st["transform"])
        if (angle == 0f) { block(); return }
        val origin = st["transform-origin"] ?: css.value(cls, "transform-origin")
        val (ox, oy) = when (origin?.trim()) {
            "left center" -> box.x to box.cy
            else -> box.cx to box.cy
        }
        c.save()
        c.rotate(angle, f(ox), f(oy))
        block()
        c.restore()
    }

    private inline fun clipped(c: Canvas, box: Box, points: List<Pair<Double, Double>>?, block: () -> Unit) {
        c.save()
        if (points != null && points.size >= 3) {
            val b = PathBuilder()
            points.forEachIndexed { i, (px, py) ->
                val x = f(box.x + px * box.w)
                val y = f(box.y + py * box.h)
                if (i == 0) b.moveTo(x, y) else b.lineTo(x, y)
            }
            b.closePath()
            val p = b.detach()
            c.clipPath(p, ClipMode.INTERSECT, true)
            b.close()
        }
        block()
        c.restore()
    }

    /** A clip-path polygon's points as fractions of the box. */
    private fun polygon(value: String?): List<Pair<Double, Double>>? {
        val inner = value?.let { Regex("polygon\\((.*)\\)").find(it)?.groupValues?.get(1) } ?: return null
        return inner.split(',').mapNotNull { pt ->
            val (a, b) = pt.trim().split(Regex("\\s+")).takeIf { it.size == 2 } ?: return@mapNotNull null
            fun frac(s: String) = if (s.endsWith("%")) s.removeSuffix("%").toDoubleOrNull()?.div(100) else s.toDoubleOrNull()
            frac(a)?.let { x -> frac(b)?.let { y -> x to y } }
        }
    }

    /** The highlighter's band of a background: its top and bottom as fractions of the text's height and its colour. */
    private fun band(value: String?, v: Map<String, String>): Triple<Double, Double, Int>? {
        val bg = value?.let { resolve(it, v) } ?: return null
        // WebKit's form: linear-gradient(c,c) no-repeat 0 P%/100% S%.
        Regex("""linear-gradient\(([^,]+),[^)]+\) no-repeat 0 ([\d.]+)%/100% ([\d.]+)%""").find(bg)?.let { m ->
            val s = m.groupValues[3].toDouble() / 100
            val top = m.groupValues[2].toDouble() / 100 * (1 - s)
            return color(m.groupValues[1], v)?.let { Triple(top, top + s, it) }
        }
        // Android's: transparent A%, c A%, c B%, transparent B% (or to the bottom).
        Regex("""linear-gradient\(transparent ([\d.]+)%,(#[0-9A-Fa-f]+) [\d.]+%(?:,#[0-9A-Fa-f]+ ([\d.]+)%)?""").find(bg)?.let { m ->
            val to = m.groupValues[3].takeIf { it.isNotEmpty() }?.toDouble() ?: 100.0
            return color(m.groupValues[2], v)?.let { Triple(m.groupValues[1].toDouble() / 100, to / 100, it) }
        }
        return null
    }

    private fun rotation(transform: String?): Float =
        transform?.let { Regex("rotate\\((-?[\\d.]+)deg\\)").find(it)?.groupValues?.get(1)?.toFloatOrNull() } ?: 0f

    private fun boxOf(st: Map<String, String>): Box? {
        val x = len(st["left"]) ?: return null
        val y = len(st["top"]) ?: return null
        val w = len(st["width"]) ?: return null
        val h = len(st["height"]) ?: len(st["min-height"]) ?: return null
        return Box(x, y, w, h)
    }

    /** A picture by its address beside the page, or a data: URI. */
    private fun picture(src: String): Image? = images.getOrPut(src) {
        runCatching {
            val bytes = if (src.startsWith("data:")) java.util.Base64.getDecoder().decode(src.substringAfter("base64,")) else read(src)
            bytes?.let { Image.makeFromEncoded(it) }
        }.getOrNull()
    }

    /** The face a CSS font list names first, at [weight] (a variable font at that weight), italic when asked, [sizeMm] tall. */
    private fun font(family: String, weight: Int, italic: Boolean, sizeMm: Double): Font {
        val name = Regex("'([^']+)'").find(family)?.groupValues?.get(1) ?: family.substringBefore(',').trim()
        val faces = FACES.filter { it.family == name }.ifEmpty { FACES.filter { it.family == "Literata" } }
        val pick = faces.filter { it.italic == italic }.ifEmpty { faces }
            .minByOrNull { if (weight in it.min..it.max) 0 else min(kotlin.math.abs(weight - it.min), kotlin.math.abs(weight - it.max)) }!!
        val w = weight.coerceIn(pick.min, pick.max)
        val key = "${pick.file}|${if (pick.min != pick.max) w else 0}"
        val tf = typefaces.getOrPut(key) {
            BtFontFiles.file(pick.file)?.let { bytes ->
                val made = FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
                if (made != null && pick.min != pick.max) made.makeClone(FontVariation("wght", w.toFloat())) else made
            }
        }
        // Sizes are in mm (or an SVG's units): glyphs placed to a fraction of a unit, not snapped to whole ones.
        return Font(tf, f(sizeMm)).apply {
            isSubpixel = true
            isLinearMetrics = true
            hinting = org.jetbrains.skia.FontHinting.NONE
        }
    }

    private fun width(text: String, font: Font, spacing: Double): Double {
        if (text.isEmpty()) return 0.0
        val line = TextLine.make(text, font)
        val w = line.width.toDouble()
        line.close()
        return w + spacing * text.length
    }

    /** Where text in [font] sits in a line box [lh] tall from [top]: the half-leading model CSS uses. */
    private fun baseline(top: Double, lh: Double, font: Font): Double {
        val m = font.metrics
        return top + (lh - (m.descent - m.ascent)) / 2 - m.ascent
    }

    private fun drawRun(c: Canvas, text: String, font: Font, x: Double, base: Double, ink: Int, spacing: Double, paint: Paint? = null) {
        val p = paint ?: Paint().apply { color = ink; isAntiAlias = true }
        if (spacing == 0.0) {
            val line = TextLine.make(text, font)
            c.drawTextLine(line, f(x), f(base), p)
            line.close()
            return
        }
        var cx = x
        for (ch in text) {
            val s = ch.toString()
            val line = TextLine.make(s, font)
            c.drawTextLine(line, f(cx), f(base), p)
            cx += line.width.toDouble() + spacing
            line.close()
        }
    }

    private fun fill(c: Canvas, box: Box, argb: Int) = c.drawRect(rect(box), Paint().apply { color = argb })

    private fun stroke(argb: Int, width: Double) = Paint().apply {
        color = argb
        mode = PaintMode.STROKE
        strokeWidth = f(width)
        isAntiAlias = true
    }

    private fun rect(b: Box) = Rect.makeXYWH(f(b.x), f(b.y), f(b.w), f(b.h))

    private fun f(d: Double) = d.toFloat()

    private fun f(d: Float) = d

    private fun resolve(value: String, v: Map<String, String>): String {
        var s = value
        repeat(4) { s = Regex("var\\((--[a-z0-9-]+)\\)").replace(s) { m -> v[m.groupValues[1]] ?: m.value } }
        return s
    }

    private fun color(value: String?, v: Map<String, String>): Int? {
        val s = value?.let { resolve(it, v).trim() } ?: return null
        if (s == "transparent") return 0
        Regex("^#([0-9A-Fa-f]{6})$").find(s)?.let { return (0xFF000000 or it.groupValues[1].toLong(16)).toInt() }
        Regex("^#([0-9A-Fa-f]{3})$").find(s)?.let { m -> return (0xFF000000 or m.groupValues[1].map { "$it$it" }.joinToString("").toLong(16)).toInt() }
        Regex("^rgba?\\(([^)]*)\\)$").find(s)?.let { m ->
            val p = m.groupValues[1].split(',').map { it.trim() }
            val r = p.getOrNull(0)?.toIntOrNull() ?: return null
            val g = p.getOrNull(1)?.toIntOrNull() ?: return null
            val b = p.getOrNull(2)?.toIntOrNull() ?: return null
            val a = p.getOrNull(3)?.toDoubleOrNull() ?: 1.0
            return ((a * 255).toInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
        }
        return null
    }

    /** A length in mm: "12.5mm", "8pt", "4px", "0"; a calc of --L (the grid line) times a number. */
    private fun len(value: String?): Double? {
        val s = value?.trim() ?: return null
        Regex("^calc\\(var\\(--L\\)\\s*\\*\\s*([\\d.]+)\\)$").find(s)?.let { m -> return len(vars["--L"])?.times(m.groupValues[1].toDouble()) }
        val m = Regex("^(-?[\\d.]+)(mm|pt|px)?$").find(s) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "pt" -> n * 25.4 / 72
            "px" -> n * 25.4 / 96
            else -> n
        }
    }

    /** A font size in mm from "9.6pt". */
    private fun pt(value: String?): Double? = len(value)

    private fun pt(points: Double): Double = points * 25.4 / 72

    private fun em(value: String?, size: Double): Double {
        val s = value?.trim() ?: return 0.0
        return if (s.endsWith("em")) (s.removeSuffix("em").toDoubleOrNull() ?: 0.0) * size else len(s) ?: 0.0
    }

    private data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
        val cx: Double get() = x + w / 2
        val cy: Double get() = y + h / 2
        fun inset(d: Double) = Box(x + d, y + d, w - 2 * d, h - 2 * d)
    }

    /** A face of the book's stylesheet (book/BookCss.kt's @font-face): its family, file, weight range and style. */
    private data class FaceFile(val family: String, val file: String, val min: Int, val max: Int, val italic: Boolean)

    /** The page's stylesheet as rules: each selector's declarations, in order. */
    private class Css(text: String) {
        class Rule(val selector: String, val decls: Map<String, String>)

        val rules = ArrayList<Rule>()
        val vars = LinkedHashMap<String, String>()

        init {
            val clean = text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            var i = 0
            while (i < clean.length) {
                val open = clean.indexOf('{', i)
                if (open < 0) break
                val selector = clean.substring(i, open).trim()
                // The block's end, counting nested blocks (@media).
                var depth = 1
                var j = open + 1
                while (j < clean.length && depth > 0) {
                    if (clean[j] == '{') depth++ else if (clean[j] == '}') depth--
                    j++
                }
                val body = clean.substring(open + 1, j - 1)
                if (!selector.startsWith("@")) {
                    val decls = inlineStyle(body)
                    selector.split(',').map { it.trim() }.forEach { sel ->
                        rules += Rule(sel, decls)
                        if (sel == ".bk") decls.filterKeys { it.startsWith("--") }.forEach { (k, v) -> vars[k] = v }
                    }
                }
                i = j
            }
        }

        /** [prop] for an element with [classes]: the matching compound class selectors, the most classes and then the last winning. */
        fun value(classes: Set<String>, prop: String, inside: Boolean = false): String? {
            var best: String? = null
            var bestScore = -1
            rules.forEachIndexed { order, r ->
                val v = r.decls[prop] ?: return@forEachIndexed
                val parts = r.selector.split(Regex("\\s+"))
                val last = parts.last()
                if (!last.startsWith(".") || last.contains(':')) return@forEachIndexed
                val need = last.split('.').filter { it.isNotEmpty() }.toSet()
                if (!classes.containsAll(need)) return@forEachIndexed
                if (parts.size > 1 && !inside) return@forEachIndexed
                if (parts.size > 1) {
                    val outer = parts.dropLast(1).joinToString(" ").split('.').filter { it.isNotEmpty() }.toSet()
                    if (!classes.containsAll(outer)) return@forEachIndexed
                }
                val score = need.size * 1000 + order
                if (score >= bestScore) {
                    bestScore = score
                    best = v
                }
            }
            return best
        }

        /** [prop] of the last rule whose selector is exactly [selector] (a descendant rule such as ".flow .q"). */
        fun descendant(selector: String, prop: String): String? = rules.lastOrNull { it.selector == selector && prop in it.decls }?.decls?.get(prop)
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val INK = 0xFF1E2A5A.toInt()

        /** The faces the book's stylesheet names (book/BookCss.kt fonts), read from its @font-face rules. */
        private val FACES: List<FaceFile> = Regex("""@font-face\{font-family:'([^']+)';src:url\('([^']+)'\);font-weight:([\d ]+);font-style:(\w+)\}""")
            .findAll(BookCss.fonts("")).map { m ->
                val w = m.groupValues[3].trim().split(' ').map { it.toInt() }
                FaceFile(m.groupValues[1], m.groupValues[2], w.first(), w.last(), m.groupValues[4] == "italic")
            }.toList()

        /** A style attribute (or a rule's body) as its declarations; semicolons inside brackets (a data: URI) kept. */
        fun inlineStyle(style: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            splitTop(style, ';').forEach { d ->
                val k = d.substringBefore(':', "").trim().lowercase()
                val v = d.substringAfter(':', "").trim().removeSuffix("!important").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
            return out
        }

        /** [s] split at [sep] where it isn't inside brackets. */
        fun splitTop(s: String, sep: Char = ','): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            for ((i, ch) in s.withIndex()) {
                when (ch) {
                    '(' -> depth++
                    ')' -> depth--
                    sep -> if (depth == 0) {
                        out += s.substring(start, i)
                        start = i + 1
                    }
                }
            }
            out += s.substring(start)
            return out.filter { it.isNotBlank() }
        }

        /** Every `<svg …>…</svg>` of [html] as its text, in order (an SVG inside another stays in its parent's). */
        fun svgSources(html: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (true) {
                val start = html.indexOf("<svg", i)
                if (start < 0) break
                var depth = 0
                var j = start
                while (j < html.length) {
                    val open = html.indexOf("<svg", j)
                    val close = html.indexOf("</svg>", j)
                    if (close < 0) { j = html.length; break }
                    if (open in 0 until close) {
                        depth++
                        j = open + 4
                    } else {
                        depth--
                        j = close + 6
                        if (depth == 0) break
                    }
                }
                out += html.substring(start, j)
                // Inner SVGs are their own elements too, in the same order as the parser lists them.
                val inner = html.substring(start + 4, max(start + 4, j - 6))
                out += svgSources(inner)
                i = j
            }
            return out
        }
    }
}

/**
 * An SVG's words taken out of it, for [BookPageDrawer] to set in the page's own fonts: each `<text>` with what it
 * inherits from the groups around it (fill, face, weight, anchor), its place or the arc of its `<textPath>` (a circle's
 * arc, as the day stamp's rings are), and the SVG without them.
 */
internal object SvgWords {
    class Arc(val cx: Double, val cy: Double, val r: Double, val start: Double, val dir: Double, val length: Double)

    class Word(
        val text: String, val x: Double, val y: Double, val size: Double, val family: String, val weight: Int, val fill: String?,
        val anchor: String, val spacing: Double, val arc: Arc?, val offset: Double,
    )

    private val TAG = Regex("""<(/?)([A-Za-z][\w:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(/?)>""")
    private val ATTR = Regex("""([\w:-]+)="([^"]*)"""")

    private fun attrs(s: String): Map<String, String> = ATTR.findAll(s).associate { it.groupValues[1] to RichHtml.decodeEntities(it.groupValues[2]) }

    fun split(svg: String): Pair<String, List<Word>> {
        val paths = HashMap<String, String>()
        TAG.findAll(svg).filter { it.groupValues[2] == "path" }.forEach { m ->
            val a = attrs(m.groupValues[3])
            val id = a["id"]
            val d = a["d"]
            if (id != null && d != null) paths[id] = d
        }
        val words = ArrayList<Word>()
        val out = StringBuilder()
        val stack = ArrayList<Map<String, String>>()
        var at = 0
        var skipUntil = -1
        for (m in TAG.findAll(svg)) {
            if (m.range.first < skipUntil) continue
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2]
            val self = m.groupValues[4] == "/"
            if (tag == "text" && !closing) {
                val end = svg.indexOf("</text>", m.range.last + 1).takeIf { it >= 0 } ?: continue
                val inner = svg.substring(m.range.last + 1, end)
                val own = attrs(m.groupValues[3])
                val all = HashMap<String, String>()
                stack.forEach { all.putAll(it) }
                all.putAll(own)
                val pathTag = Regex("""<textPath([^>]*)>([\s\S]*?)</textPath>""").find(inner)
                val words0 = RichHtml.decodeEntities((pathTag?.groupValues?.get(2) ?: inner).replace(Regex("<[^>]*>"), ""))
                val pathAttrs = pathTag?.let { attrs(it.groupValues[1]) }.orEmpty()
                val href = (pathAttrs["href"] ?: pathAttrs["xlink:href"])?.removePrefix("#")
                words += Word(
                    text = words0,
                    x = all["x"]?.toDoubleOrNull() ?: 0.0,
                    y = all["y"]?.toDoubleOrNull() ?: 0.0,
                    size = all["font-size"]?.toDoubleOrNull() ?: 16.0,
                    family = all["font-family"] ?: "'Literata',serif",
                    weight = all["font-weight"]?.toIntOrNull() ?: 400,
                    fill = all["fill"],
                    anchor = all["text-anchor"] ?: "start",
                    spacing = all["letter-spacing"]?.toDoubleOrNull() ?: 0.0,
                    arc = href?.let { paths[it] }?.let(::arc),
                    offset = pathAttrs["startOffset"]?.let { if (it.endsWith("%")) it.removeSuffix("%").toDoubleOrNull()?.div(100) else null } ?: 0.0,
                )
                out.append(svg, at, m.range.first)
                at = end + "</text>".length
                skipUntil = at
                continue
            }
            if (!self && !closing && tag != "svg") stack += attrs(m.groupValues[3]).filterKeys { it in INHERITED }
            if (closing && tag != "svg" && stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        }
        out.append(svg, at, svg.length)
        return out.toString() to words
    }

    private val INHERITED = setOf("fill", "font-family", "font-weight", "font-size", "text-anchor", "letter-spacing")

    /** A path of one circular arc ("M x,y A r,r 0 large sweep x,y") as its circle, start angle and direction. */
    fun arc(d: String): Arc? {
        val m = Regex("""M\s*([-\d.]+)[ ,]([-\d.]+)\s*A\s*([-\d.]+)[ ,]([-\d.]+)\s+[-\d.]+\s+([01])[ ,]?\s*([01])\s+([-\d.]+)[ ,]([-\d.]+)""").find(d) ?: return null
        val g = m.groupValues.drop(1).map { it.toDouble() }
        val (x0, y0, r0) = Triple(g[0], g[1], g[2])
        val large = g[4] == 1.0
        val sweep = g[5] == 1.0
        val (x1, y1) = g[6] to g[7]
        val dx = (x0 - x1) / 2
        val dy = (y0 - y1) / 2
        val d2 = dx * dx + dy * dy
        if (d2 == 0.0) return null
        val r = max(r0, kotlin.math.sqrt(d2))
        val k = kotlin.math.sqrt(max(0.0, (r * r - d2) / d2)) * if (large == sweep) -1 else 1
        val cx = k * dy + (x0 + x1) / 2
        val cy = -k * dx + (y0 + y1) / 2
        val a0 = kotlin.math.atan2(y0 - cy, x0 - cx)
        val a1 = kotlin.math.atan2(y1 - cy, x1 - cx)
        val dir = if (sweep) 1.0 else -1.0
        var span = (a1 - a0) * dir
        while (span <= 0) span += 2 * Math.PI
        return Arc(cx, cy, r, a0, dir, r * span)
    }
}

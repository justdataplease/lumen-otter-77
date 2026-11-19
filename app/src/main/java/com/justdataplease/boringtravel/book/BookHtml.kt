package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.export.TripHtml
import kotlin.math.max
import com.justdataplease.boringtravel.art.PlaneMark

/**
 * The book as HTML: one fixed-size section per printed page, everything placed in mm by [PageLayout]. The same HTML
 * makes the PDF (WebView → print, [Mode.Print]), the editor's page preview ([Mode.Editor], one page, empty photo spots
 * shown) and pages to check in a browser ([Mode.Desk]).
 *
 * Every text from the trip is escaped; paragraphs are the journal's sanitized subset. Photos come in as URLs from
 * `photoSrc(mediaId, pixelsNeeded)`; null leaves a photo out.
 */
object BookHtml {
    const val FONT_BASE = TripHtml.FONT_BASE

    /** Washi tape colour when a tape strip has none. */
    private const val TAPE = "#E6D7A8"

    enum class Mode { Print, Desk, Editor }

    fun document(
        layout: BookLayout,
        doc: BookDoc,
        photoSrc: (String, Int) -> String?,
        mode: Mode = Mode.Print,
        fontBase: String = FONT_BASE,
        pages: List<BookPage> = layout.printed(doc),
        firstNumber: Int = 1,
        zoom: Double = 1.0,
    ): String = Writer(layout, doc, photoSrc, mode, fontBase, zoom).document(pages, firstNumber)

    /** One page for the editor; its page number is its place among the printed pages (or in the book, if hidden). */
    fun page(layout: BookLayout, doc: BookDoc, pageId: String, photoSrc: (String, Int) -> String?, zoom: Double, fontBase: String = FONT_BASE): String {
        val printed = layout.printed(doc)
        val page = doc.page(pageId) ?: printed.firstOrNull { it.id == pageId } ?: return document(layout, doc, photoSrc, Mode.Editor, fontBase, emptyList(), 1, zoom)
        val n = printed.indexOfFirst { it.id == pageId }.let { if (it >= 0) it + 1 else doc.indexOf(pageId) + 1 }
        return document(layout, doc, photoSrc, Mode.Editor, fontBase, listOf(page), n, zoom)
    }

    /** The pixels each photo needs on its longest side to print at about 300 dpi where it is placed (largest use). */
    fun photoNeeds(layout: BookLayout, doc: BookDoc): Map<String, Int> {
        val out = HashMap<String, Int>()
        val art = layout.pages.sheet.art
        layout.printed(doc).forEachIndexed { i, p ->
            val plan = layout.pages.plan(p, i + 1)
            plan.photos.forEach { el ->
                val m = layout.facts.media(el.mediaId) ?: return@forEach
                val px = CropMath.printPx(m.width, m.height, el.image.w, el.image.h, el.crop)
                out[m.id] = max(out[m.id] ?: 0, px)
            }
            plan.bg?.photo?.let { s ->
                val m = layout.facts.media(s.mediaId) ?: return@let
                out[m.id] = max(out[m.id] ?: 0, CropMath.printPx(m.width, m.height, art.w, art.h, s.crop))
            }
        }
        return out
    }

    private fun esc(s: String) = RichHtml.escape(s)
    private fun mm(v: Double) = BookSvg.n2(v) + "mm"
    private fun rotate(a: Double) = if (a != 0.0) "transform:rotate(${BookSvg.n2(a)}deg)" else ""

    private class Writer(
        val layout: BookLayout,
        val doc: BookDoc,
        val photoSrc: (String, Int) -> String?,
        val mode: Mode,
        val fontBase: String,
        val zoom: Double,
    ) {
        val pl = layout.pages
        val spec = layout.spec
        val sheet = pl.sheet
        val grid = pl.grid
        val look = pl.look
        val fonts = pl.fonts
        val type = pl.type
        val flow = Flow(type, layout.measure, doc.hidden)
        val L = grid.line

        fun document(pages: List<BookPage>, firstNumber: Int): String = buildString(64 * 1024) {
            val title = layout.facts.trip.title
            append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>").append(esc(title)).append("</title>\n<style>\n")
            append(BookCss.fonts(fontBase))
            append("@page{size:").append(mm(sheet.pageW)).append(' ').append(mm(sheet.pageH)).append(";margin:0}\n")
            append(BookCss.BASE)
            append(BookCss.theme(spec.theme))
            if (mode == Mode.Desk) append(BookCss.PREVIEW)
            if (mode == Mode.Editor) append("html{background:").append(look.paperColor).append("}\n")
            append("</style>\n</head>\n")
            append("<body class=\"bk th-").append(spec.theme.id).append(" fmt-").append(spec.format.id)
            append(when (mode) { Mode.Print -> ""; Mode.Desk -> " desk"; Mode.Editor -> " editor" }).append('"')
            append(" style=\"--mono:").append(esc(fonts.mono.css)).append(";--display:").append(esc(fonts.display.css))
                .append(";--body:").append(esc(fonts.body.css)).append(";--L:").append(mm(L))
            if (mode == Mode.Editor && zoom != 1.0) append(";zoom:").append(BookSvg.n2(zoom))
            append("\">\n")
            pages.forEachIndexed { i, p -> page(p, firstNumber + i) }
            append("</body>\n</html>\n")
        }

        private fun StringBuilder.page(p: BookPage, number: Int) {
            val plan = pl.plan(p, number)
            append("<section class=\"pg\" data-n=\"").append(number).append("\" data-t=\"").append(esc(p.templateId)).append("\" style=\"width:")
                .append(mm(sheet.pageW)).append(";height:").append(mm(sheet.pageH)).append("\">\n")
            val t = sheet.trim
            append("<div class=\"trim\" style=\"left:").append(mm(t.x)).append(";top:").append(mm(t.y)).append(";width:").append(mm(t.w)).append(";height:").append(mm(t.h)).append("\">\n")
            val art = sheet.art
            val bgColor = plan.bg?.takeIf { it.kind == PageBg.COLOR }?.color?.let(FreeValues::color)
            append("<div class=\"art\" style=\"left:").append(mm(art.x)).append(";top:").append(mm(art.y)).append(";width:").append(mm(art.w)).append(";height:").append(mm(art.h))
            if (bgColor != null) append(";background:").append(bgColor)
            append("\"></div>\n")
            plan.bg?.takeIf { it.kind == PageBg.PHOTO }?.let { bgPhoto(it) }
            plan.paper?.let { append(BookSvg.paper(it, art, grid.ox, grid.oy, L, look.dot, look.rule, grid.scale)).append('\n') }
            plan.els.forEachIndexed { i, e -> el(e, p, number, if (mode == Mode.Editor) plan.id(i) else null) }
            append("</div>\n")
            append(BookSvg.cropMarks(sheet))
            append("</section>\n")
        }

        private fun StringBuilder.box(b: com.justdataplease.boringtravel.book.Box, extra: String = "") {
            append(" style=\"left:").append(mm(b.x)).append(";top:").append(mm(b.y)).append(";width:").append(mm(b.w)).append(";height:").append(mm(b.h))
            if (extra.isNotEmpty()) append(';').append(extra)
            append('"')
        }

        /**
         * Writes [e]; on editor pages its outer `<div` gets the editor's handle `data-fe="<id>"`, for moving it live
         * (every element starts with a `<div`).
         */
        private fun StringBuilder.el(e: El, p: BookPage, number: Int, id: String?) {
            val start = length
            when (e) {
                is PhotoEl -> photo(e)
                is TextEl -> text(e)
                is FlowEl -> flowEl(e, p)
                is StampEl -> stamp(e, number)
                is RouteEl -> route(e)
                is FlagsEl -> flags(e)
                is RuleEl -> { append("<div class=\"e rule\""); box(e.box.copy(h = 0.0), rotate(e.angle)); append("></div>\n") }
                is LinesEl -> {
                    append("<div class=\"e lines\""); box(e.box, rotate(e.angle)); append(">")
                    append(BookSvg.lines(e.box, e.paper, e.pitch, look.dot, look.rule, grid.scale)).append("</div>\n")
                }
                is DecoEl -> deco(e)
            }
            if (id != null && length > start + 4 && startsWith("<div", start)) insert(start + 4, " data-fe=\"${esc(id)}\"")
        }

        /** A full-bleed photo under everything, with black over it to [PageBg.dim] it. */
        private fun StringBuilder.bgPhoto(bg: PageBg) {
            val s = bg.photo ?: return
            val media = layout.facts.media(s.mediaId) ?: return
            val art = sheet.art
            val src = photoSrc(media.id, CropMath.printPx(media.width, media.height, art.w, art.h, s.crop)) ?: return
            append("<div class=\"ph bleed bgph\""); box(art); append("><div class=\"im\" style=\"left:0;top:0;width:100%;height:100%\">")
            val c = CropMath.cssPercent(CropMath.visible(media.width, media.height, art.aspect, s.crop))
            append("<img src=\"").append(esc(src)).append("\" alt=\"\" style=\"left:").append(BookSvg.n2(c[0])).append("%;top:").append(BookSvg.n2(c[1]))
                .append("%;width:").append(BookSvg.n2(c[2])).append("%;height:").append(BookSvg.n2(c[3])).append("%\"></div></div>\n")
            val dim = bg.dim.coerceIn(0.0, PageBg.MAX_DIM)
            if (dim > 0.0) { append("<div class=\"dim\""); box(art, "background:rgba(0,0,0,${BookSvg.n2(dim)})"); append("></div>\n") }
        }

        private fun StringBuilder.deco(e: DecoEl) {
            val color = FreeValues.color(e.color)
            val fill = FreeValues.color(e.fill)
            val rot = rotate(e.angle)
            when (e.kind) {
                FreeKind.TAPE -> {
                    append("<div class=\"e washi\""); box(e.box, listOf("background:${color ?: TAPE}", rot).filter { it.isNotEmpty() }.joinToString(";")); append("></div>\n")
                }
                FreeKind.CORNERS -> {
                    val c = color ?: "#2B2B2B"
                    val size = (minOf(e.box.w, e.box.h) * 0.2).coerceIn(3.0, 12.0)
                    append("<div class=\"e corners\""); box(e.box, listOf("--cc:$c;--cs:${mm(size)}", rot).filter { it.isNotEmpty() }.joinToString(";")); append(">")
                    append("<i class=\"tl\"></i><i class=\"tr\"></i><i class=\"br\"></i><i class=\"bl\"></i></div>\n")
                }
                FreeKind.SHAPE -> {
                    val sw = e.stroke.coerceIn(0.0, 20.0)
                    val style = buildList {
                        add("background:${fill ?: "transparent"}")
                        if (sw > 0 && color != null) add("border:${mm(sw)} solid $color")
                        if (e.style == "oval") add("border-radius:50%")
                        if (rot.isNotEmpty()) add(rot)
                    }.joinToString(";")
                    append("<div class=\"e shape\""); box(e.box, style); append("></div>\n")
                }
                FreeKind.LINE -> {
                    val sw = e.stroke.takeIf { it > 0 } ?: 0.5
                    append("<div class=\"e line\""); box(e.box, rot); append("><i style=\"height:").append(mm(sw)).append(";margin-top:").append(mm(-sw / 2))
                        .append(";background:").append(color ?: look.ink).append("\"></i></div>\n")
                }
                FreeKind.SHADE -> { append("<div class=\"e shade\""); box(e.box, rot); append("></div>\n") }
            }
        }

        private fun StringBuilder.photo(e: PhotoEl) {
            val media = layout.facts.media(e.mediaId)
            val src = media?.let { photoSrc(it.id, CropMath.printPx(it.width, it.height, e.image.w, e.image.h, e.crop)) }
            val rot = rotate(e.tilt)
            if (media == null || src == null) {
                if (mode != Mode.Editor) return
                // The editor shows an empty spot as a round coral "+" to tap; the PDF leaves it out.
                append("<div class=\"ph empty\" data-slot=\"").append(e.slot).append('"'); box(e.frame, rot)
                append("><i class=\"add\"></i>")
                if (e.frame.w >= 4 * L && e.frame.h >= 3.4 * L) append("<span>Add photo</span>")
                append("</div>\n")
                return
            }
            val cls = when {
                e.style != null -> "ph ${if (e.style in FreeLayout.PHOTO_STYLES) e.style else FreeLayout.FLAT}"
                e.bleed -> "ph bleed"
                look.prints -> "ph print"
                else -> "ph flat"
            }
            append("<div class=\"").append(cls).append("\" data-slot=\"").append(e.slot).append('"'); box(e.frame, rot); append(">")
            val inner = com.justdataplease.boringtravel.book.Box(e.image.x - e.frame.x, e.image.y - e.frame.y, e.image.w, e.image.h)
            append("<div class=\"im\""); box(inner); append(">")
            val v = CropMath.visible(media.width, media.height, e.image.aspect, e.crop)
            val c = CropMath.cssPercent(v)
            append("<img src=\"").append(esc(src)).append("\" alt=\"").append(esc(e.captionText)).append("\" style=\"left:").append(BookSvg.n2(c[0])).append("%;top:")
                .append(BookSvg.n2(c[1])).append("%;width:").append(BookSvg.n2(c[2])).append("%;height:").append(BookSvg.n2(c[3])).append("%\"></div>")
            e.tapes.forEach { tp ->
                append("<i class=\"tape\" style=\"left:").append(mm(tp.cx - tp.w / 2)).append(";top:").append(mm(tp.cy - tp.h / 2)).append(";width:").append(mm(tp.w))
                    .append(";height:").append(mm(tp.h)).append(";transform:rotate(").append(BookSvg.n2(tp.angle)).append("deg)\"></i>")
            }
            val cap = e.caption
            if (e.captionInFrame && cap != null && e.captionText.isNotBlank()) {
                val size = if (fonts.hand) 12.5 * grid.scale * fonts.displayScale else 7.6 * grid.scale
                val face = if (fonts.hand) fonts.display else fonts.italic
                append("<div class=\"band t cap\" style=\"left:0;top:").append(mm(cap.y - e.frame.y)).append(";width:100%;height:").append(mm(cap.h))
                    .append(";font-family:").append(esc(face.css)).append(";font-size:").append(BookSvg.n2(size)).append("pt;line-height:1.1")
                if (!fonts.hand) append(";font-style:italic")
                append("\">").append(esc(e.captionText)).append("</div>")
            }
            append("</div>\n")
        }

        /** How far text moves down so its baseline sits on the line at the bottom of its line box. */
        private fun shift(face: Face, pt: Double, lineMm: Double): Double = (lineMm - (face.ascent - face.descent) * pt * TextFit.PT_MM) / 2

        private fun StringBuilder.text(e: TextEl) {
            if (e.text.isBlank() && mode != Mode.Editor) return
            val sh = shift(e.face, e.pt, e.lineMm)
            val mono = e.face == fonts.mono
            append("<div class=\"t ").append(FreeValues.role(e.role) ?: "free")
            if (mono) append(" mono")
            if (e.upper) append(" up")
            append('"')
            val weight = when {
                e.exact && e.bold && e.face != fonts.display -> 700
                mono -> 500
                e.face == fonts.display && e.bold -> fonts.displayWeight
                else -> 400
            }
            val style = buildString {
                append("font-family:").append(esc(e.face.css)).append(";font-size:").append(BookSvg.n2(e.pt)).append("pt;font-weight:").append(weight)
                append(";line-height:").append(mm(e.lineMm)).append(";text-align:").append(FreeValues.align(e.align))
                if (e.italic) append(";font-style:italic")
                FreeValues.color(e.color)?.let { append(";color:").append(it) }
                if (e.angle != 0.0) append(";transform:rotate(").append(BookSvg.n2(e.angle)).append("deg)")
                if (e.exact && e.angle != 0.0) append(";transform-origin:50% 50%")
            }
            // Line breaks typed in a text box start new lines.
            val words = esc(e.text).let { if (e.exact) it.replace("\n", "<br>") else it }
            if (e.role == "tag") {
                append(" style=\"left:").append(mm(e.box.x)).append(";top:").append(mm(e.box.y)).append(";width:").append(mm(e.box.w)).append(";height:").append(mm(e.box.h)).append(';').append(style).append("\">")
                append(words).append("</div>\n")
                return
            }
            if (e.exact) {
                // Exactly the box (so it turns around the box's centre); the words sit on the same line as below.
                append(" style=\"left:").append(mm(e.box.x)).append(";top:").append(mm(e.box.y)).append(";width:").append(mm(e.box.w)).append(";height:").append(mm(e.box.h))
                    .append(";padding-top:").append(mm(sh)).append(';').append(style).append("\">")
            } else {
                append(" style=\"left:").append(mm(e.box.x)).append(";top:").append(mm(e.box.y + sh)).append(";width:").append(mm(e.box.w)).append(";min-height:").append(mm(e.box.h)).append(';').append(style).append("\">")
            }
            if (e.mark && e.text.isNotBlank()) append("<span class=\"mk\">").append(words).append("</span>") else append(words)
            append("</div>\n")
        }

        private fun StringBuilder.flowEl(e: FlowEl, p: BookPage) {
            val paras = p.paras.filter(flow::visible)
            if (paras.isEmpty() && mode != Mode.Editor) return
            val sb = shift(fonts.body, type.bodyPt, L)
            val sm = shift(fonts.mono, type.monoPt, L)
            append("<div class=\"flow\"")
            if (e.angle != 0.0) append(" style=\"transform:rotate(").append(BookSvg.n2(e.angle)).append("deg);left:") else append(" style=\"left:")
            append(mm(e.box.x)).append(";top:").append(mm(e.box.y)).append(";width:").append(mm(e.box.w))
                .append(";height:").append(mm(e.rows * L)).append(";padding-bottom:").append(mm(L * 0.45))
                .append(";column-count:").append(e.cols).append(";column-gap:").append(mm(e.gutter))
                .append(";font-family:").append(esc(fonts.body.css)).append(";font-size:").append(BookSvg.n2(type.bodyPt)).append("pt;line-height:").append(mm(L))
                .append(";--sb:").append(mm(sb)).append(";--sm:").append(mm(sm)).append(";--ind:").append(mm(type.indentMm))
                .append(";--qi:").append(mm(type.style(ParaKind.QUOTE).leftMm)).append(";--li:").append(mm(type.style(ParaKind.BULLET).leftMm))
                .append(";--ci:").append(mm(type.style(ParaKind.CHECK).leftMm)).append(";--mono-pt:").append(BookSvg.n2(type.monoPt)).append("pt\">")
            var prev: Para? = null
            var number = 0
            for (para in paras) {
                number = if (para.kind == ParaKind.NUMBER && !para.cont) number + 1 else if (para.kind == ParaKind.NUMBER) number else 0
                when (para.kind) {
                    ParaKind.TITLE -> {
                        val s = type.style(para.kind)
                        val n = flow.textLines(para, e.colWidth, false)
                        val lh = type.lineHeight(para.kind, n) * L
                        val h = type.blockLines(para.kind, n) * L
                        append("<h3 class=\"title").append(if (fonts.upper) " up" else "").append("\" style=\"font-family:").append(esc(s.face.css)).append(";font-size:")
                            .append(BookSvg.n2(s.pt)).append("pt;font-weight:").append(if (s.bold) fonts.displayWeight else 400).append(";line-height:").append(mm(lh))
                            .append(";height:").append(mm(h)).append(";top:").append(mm(shift(s.face, s.pt, lh) + (h - n * lh)))
                            .append(if (fonts.upper) ";text-transform:uppercase" else "").append("\">").append(para.html).append("</h3>")
                    }
                    ParaKind.META -> append("<p class=\"meta\" style=\"font-size:").append(BookSvg.n2(type.monoPt)).append("pt\">").append(para.html).append("</p>")
                    ParaKind.HEADING -> {
                        val s = type.style(para.kind)
                        val lh = 2 * L
                        append("<h4 class=\"head\" style=\"font-family:").append(esc(s.face.css)).append(";font-size:").append(BookSvg.n2(s.pt)).append("pt;font-weight:")
                            .append(if (s.bold) fonts.displayWeight else 400).append(";line-height:").append(mm(lh)).append(";top:").append(mm(shift(s.face, s.pt, lh)))
                            .append(if (fonts.upper) ";text-transform:uppercase" else "").append("\">").append(para.html).append("</h4>")
                    }
                    ParaKind.QUOTE -> append("<p class=\"q").append(if (para.cont) " cont" else "").append("\" style=\"font-family:").append(esc(fonts.italic.css)).append("\">").append(para.html).append("</p>")
                    ParaKind.BULLET -> append("<p class=\"li").append(if (para.cont) " cont" else "").append("\">").append(para.html).append("</p>")
                    ParaKind.NUMBER -> append("<p class=\"li num").append(if (para.cont) " cont" else "").append("\" data-n=\"").append(number).append("\">").append(para.html).append("</p>")
                    ParaKind.CHECK, ParaKind.DONE -> append("<p class=\"ck").append(if (para.kind == ParaKind.DONE) " done" else "").append(if (para.cont) " cont" else "").append("\">").append(para.html).append("</p>")
                    ParaKind.PLACE -> append("<p class=\"place\">").append(para.html).append("</p>")
                    else -> {
                        append("<p")
                        if (flow.indented(para, prev)) append(" class=\"ind\"")
                        append('>').append(para.html).append("</p>")
                    }
                }
                prev = para
            }
            append("</div>\n")
        }

        private fun StringBuilder.stamp(e: StampEl, number: Int) {
            val rot = "transform:rotate(${BookSvg.n2(e.angle)}deg)"
            when (e.kind) {
                StampKind.Brand -> { append("<div class=\"e stamp brand\""); box(e.box, rot); append(">").append(PlaneMark.svg(pad = 0.08f)).append("</div>\n") }
                StampKind.Day -> {
                    append("<div class=\"e stamp day\""); box(e.box, rot); append(">")
                    append(BookSvg.dayStamp("ds$number", look.red, e.top, e.small, e.middle, e.bottom, "'Barlow Condensed',sans-serif"))
                    append("</div>\n")
                }
                StampKind.Postage -> {
                    append("<div class=\"e postage\""); box(e.box, rot); append(">")
                    append("<span>").append(esc(e.top)).append("</span><b>").append(esc(e.middle)).append("</b><span>").append(esc(e.bottom)).append("</span></div>\n")
                }
            }
        }

        private fun StringBuilder.route(e: RouteEl) {
            val hand = fonts.hand
            val pen = BookSvg.Pen(
                color = look.route,
                width = (if (look.prints) 0.62 else 0.55) * grid.scale,
                ink = look.ink,
                paper = look.paperColor,
                labelFont = if (hand) fonts.display.css else fonts.mono.css,
                labelSize = if (hand) 4.6 * grid.scale * fonts.displayScale else 2.3 * grid.scale,
                labelUpper = !hand,
            )
            val title = e.route.places.map { it.name }.filter { it.isNotBlank() }.distinct().joinToString(" → ").ifBlank { layout.facts.trip.title }
            val svg = BookSvg.route(e.route, e.box.w, e.box.h, pen, e.labels, e.markers, e.scaleBar, title) ?: return
            append("<div class=\"e route-box\""); box(e.box, rotate(e.angle)); append(">").append(svg).append("</div>\n")
        }

        private fun StringBuilder.flags(e: FlagsEl) {
            if (e.codes.isEmpty()) return
            val justify = when (e.align) { "center" -> "center"; "right" -> "flex-end"; else -> "flex-start" }
            append("<div class=\"e flags ").append(if (e.sticker) "sticker" else "flat").append('"')
            box(e.box, listOf("justify-content:$justify", rotate(e.angle)).filter { it.isNotEmpty() }.joinToString(";")); append(">")
            e.codes.forEach { append("<span class=\"fl\">").append(Flags.svg(it, e.height)).append("</span>") }
            append("</div>\n")
        }
    }
}

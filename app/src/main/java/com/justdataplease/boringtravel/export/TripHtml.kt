package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.util.Dates
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.book.BookFacts
import com.justdataplease.boringtravel.book.BookSvg
import com.justdataplease.boringtravel.book.Flags
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoPlacement
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.PrintTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import com.justdataplease.boringtravel.art.PlaneMark

/**
 * One HTML document for a trip (or some of its days) in a print style: a cover with the trip's photo, name, dates,
 * flags and numbers, then one section per day with its notes, photos and, optionally, the day's plan.
 *
 * Photos are always whole (owner, 2026-11-12: "sometimes images are cropped"): a photo alone is as wide as it may be
 * up to a height, several side by side in justified rows ([PhotoRows]) sized from each photo's own shape. A note's
 * words keep their place between its photos. The route sketches are gone from the cover and the days (owner: "the
 * plan trip sketch does not make sense"): a line without a map behind it said nothing.
 *
 * [ExportTarget.Print] is for the WebView → PDF path: `@page` size and margins, page breaks, a footer with the trip
 * title and page numbers, the bundled fonts. [ExportTarget.Docs] is a plainer document Google Docs converts well:
 * no positioning, images with explicit sizes, page breaks as Docs writes them.
 *
 * Every text from the database is escaped; note bodies go through [DocRender] (sanitized). Photos come in as URLs
 * from [photoSrc] (null leaves a photo out), their shapes from [photoAspect] (null: the size saved with the photo).
 */
object TripHtml {
    const val FONT_BASE = "file:///android_res/font/"

    fun build(
        book: TripBook,
        options: ExportOptions,
        photoSrc: (Media) -> String?,
        target: ExportTarget = ExportTarget.Print,
        locale: Locale = Locale.getDefault(),
        labels: PrintLabels = PrintLabels(),
        fontBase: String = FONT_BASE,
        photoAspect: (Media) -> Double? = { null },
    ): String = Writer(book, options, photoSrc, photoAspect, target, locale, labels, fontBase).document()

    /** A CSS string literal: quotes, backslashes, line breaks and angle brackets escaped (so `</style>` can't end the block). */
    fun cssString(s: String): String = buildString(s.length + 2) {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '<' -> append("\\3C ")
            ch == '>' -> append("\\3E ")
            ch == '\n' -> append("\\A ")
            ch.code < 0x20 || ch.code == 0x7F -> append('\\').append(Integer.toHexString(ch.code)).append(' ')
            else -> append(ch)
        }
        append('"')
    }

    /** "16–25 Jun 2027", "28 Jun – 3 Jul 2027", "30 Dec 2026 – 4 Jan 2027". */
    fun range(start: LocalDate, end: LocalDate, locale: Locale): String {
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
        val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        return when {
            start.year != end.year -> "${dayMonthYear.format(start)} – ${dayMonthYear.format(end)}"
            start.month != end.month -> "${dayMonth.format(start)} – ${dayMonthYear.format(end)}"
            start == end -> dayMonthYear.format(start)
            else -> "${start.dayOfMonth}–${dayMonthYear.format(end)}"
        }
    }

    /** A photo's shape (width / height) from the size saved with it; null when it wasn't saved. */
    fun savedAspect(m: Media): Double? = if (m.width > 0 && m.height > 0) m.width.toDouble() / m.height else null

    /** A photo with what is written under it. */
    private data class Pic(val media: Media, val caption: String = "")

    private class Writer(
        val book: TripBook,
        val o: ExportOptions,
        val photoSrc: (Media) -> String?,
        val photoAspect: (Media) -> Double?,
        val target: ExportTarget,
        val locale: Locale,
        val l: PrintLabels,
        val fontBase: String,
    ) {
        val theme = o.theme
        val docs = target == ExportTarget.Docs
        val trip = book.trip
        val days = book.days(o)
        private val srcCache = HashMap<String, String?>()
        private val aspectCache = HashMap<String, Double>()
        private val longDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)
        private val handDate = DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)
        private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
        private val hhmm = DateTimeFormatter.ofPattern("HH:mm", locale)

        /** The notebook's pages: Notebook, and Minimal (the same pages on plain white, without tape, highlighter or the yellow mark). */
        private val notebook = theme == PrintTheme.Notebook || theme == PrintTheme.Minimal
        private val taped = theme == PrintTheme.Notebook

        /** Photos in this style: framed prints (Notebook, Minimal) and polaroids (Scrapbook) need a wider gap and room for the frame. */
        private val framed = notebook || theme == PrintTheme.Scrapbook
        private val scale = PhotoRows.scale(o.photoSize)

        /** The text column and the page's height inside the margins, in mm (print) or px (Docs). */
        private val textW = if (docs) DOCS_TEXT_WIDTH_PX.toDouble() else o.paper.widthMm - 2 * PrintCss.MARGIN_SIDE_MM
        private val pageH = o.paper.heightMm - PrintCss.MARGIN_TOP_MM - PrintCss.MARGIN_BOTTOM_MM
        private val unit = if (docs) PX_PER_MM else 1.0
        private val gap = (if (docs) 2.0 else if (framed) 4.0 else 3.0) * unit
        private val frame = if (framed && !docs) PrintCss.FRAME_MM * 2 else 0.0

        fun src(m: Media): String? = srcCache.getOrPut(m.id) { photoSrc(m) }
        fun src(id: String): String? = book.media(id)?.let(::src)

        /** Width / height of [m] as it prints; ask only after [src] (the prepared copy is measured then). */
        fun aspect(m: Media): Double = aspectCache.getOrPut(m.id) { PhotoRows.clean(photoAspect(m) ?: savedAspect(m)) }

        fun document(): String = buildString(64 * 1024) {
            append("<!doctype html>\n<html lang=\"").append(esc(locale.language.ifBlank { "en" })).append("\">\n<head>\n<meta charset=\"utf-8\">\n")
            append("<title>").append(esc(trip.title)).append("</title>\n<style>\n")
            if (docs) {
                append(PrintCss.docs(theme))
            } else {
                append(PrintCss.fonts(fontBase))
                append(PrintCss.page(o.paper, theme, footer()))
                append(PrintCss.BASE)
                append(PrintCss.theme(theme))
                append(PrintCss.ENTRY_FONTS)
            }
            append("</style>\n</head>\n")
            append("<body class=\"theme-").append(theme.id).append(" photos-").append(o.photoSize.id).append(" paper-").append(o.paper.id)
                .append(if (theme == PrintTheme.Notebook && o.ruled) " paper-ruled" else "").append("\">\n")
            if (docs) docsBody() else printBody()
            append("</body>\n</html>\n")
        }

        private fun footer(): String = trip.title

        // ---------------------------------------------------------------- print

        private fun StringBuilder.printBody() {
            cover()
            if (days.isEmpty()) append("<p class=\"empty\">").append(esc(l.nothingYet)).append("</p>\n")
            days.forEach { day(it) }
            val ideas = if (o.includePlan && o.wholeTrip) book.ideas() else emptyList()
            if (ideas.isNotEmpty()) {
                append("<section class=\"day ideas\">\n<h2>").append(esc(l.ideas)).append("</h2>\n")
                planList(ideas, withHeading = false)
                append("</section>\n")
            }
        }

        private fun coverPhoto(): Media? {
            val chosen = trip.coverMediaId?.let { book.media(it) }?.takeIf { src(it) != null }
            return chosen ?: days.asSequence().flatMap { it.photos }.firstOrNull { src(it) != null }
        }

        /** The share of the width the cover photo takes: as big as the page leaves room for, never cropped. */
        private fun coverWidth(m: Media, room: Double, share: Double): Double = PhotoRows.single(aspect(m), textW, pageH - room, share)

        private fun StringBuilder.cover() {
            val photo = coverPhoto()
            append("<section class=\"cover").append(if (photo == null) " no-photo" else "").append("\">\n")
            when (theme) {
                PrintTheme.Notebook, PrintTheme.Minimal -> {
                    append("<h1>").append(esc(trip.title)).append("</h1>\n")
                    if (trip.subtitle.isNotBlank()) append("<p class=\"sub\">").append(esc(trip.subtitle)).append("</p>\n")
                    val dates = dateRange()
                    if (dates.isNotBlank()) append("<p class=\"dates\"><span class=\"mk\">").append(esc(dates)).append("</span></p>\n")
                    if (photo != null) {
                        append("<figure class=\"nb-print cover-print\" style=\"width:").append(pct(coverWidth(photo, 118.0, 0.9))).append("%\">")
                        if (taped) append("<i class=\"tape l\"></i><i class=\"tape r\"></i>")
                        append("<img src=\"").append(esc(src(photo)!!)).append("\" alt=\"\">")
                        if (taped) append("<span class=\"brand-mark\">").append(PlaneMark.svg()).append("</span>")
                        append("</figure>\n")
                    }
                    coverFoot()
                }
                PrintTheme.Postcard -> {
                    if (photo != null) {
                        append("<img class=\"cover-photo\" src=\"").append(esc(src(photo)!!)).append("\" alt=\"\" style=\"width:")
                            .append(pct(coverWidth(photo, 100.0, 1.0))).append("%\">\n")
                    }
                    trip.days?.let { n ->
                        append("<div class=\"stamp\"><span>").append(esc(l.days)).append("</span><b>").append(n).append("</b><span>")
                            .append(esc(shortPlace(trip.subtitle) ?: "")).append("</span></div>\n")
                    }
                    append("<p class=\"meta\"><span>").append(esc(dateRange())).append("</span><span>").append(esc(l.brand)).append("</span></p>\n")
                    append("<h1>").append(esc(trip.title)).append("</h1>\n")
                    if (trip.subtitle.isNotBlank()) append("<p class=\"sub\">").append(esc(trip.subtitle)).append("</p>\n")
                    coverFoot()
                }
                PrintTheme.Scrapbook -> {
                    append("<h1>").append(esc(trip.title)).append("</h1>\n")
                    append("<p class=\"sub\">").append(esc(listOf(dateRange(), trip.subtitle).filter { it.isNotBlank() }.joinToString(" · "))).append("</p>\n")
                    if (photo != null) {
                        append("<figure class=\"photo frame-polaroid cover-pol\" style=\"width:").append(pct(coverWidth(photo, 112.0, 0.8))).append("%\"><img src=\"")
                            .append(esc(src(photo)!!)).append("\" alt=\"\"></figure>\n")
                    }
                    coverFoot()
                }
            }
            append("</section>\n")
        }

        /** The numbers and the flags at the foot of the cover. */
        private fun StringBuilder.coverFoot() {
            append("<div class=\"foot\"><p class=\"stats\">").append(esc(statsLine())).append("</p>")
            flags(countries())
            append("</div>\n")
        }

        private fun StringBuilder.day(d: BookDay) {
            when (theme) {
                PrintTheme.Notebook, PrintTheme.Minimal -> notebookDay(d)
                PrintTheme.Postcard -> postcardDay(d)
                PrintTheme.Scrapbook -> scrapbookDay(d)
            }
        }

        /** The day's photos that aren't in a note, whole: one alone as big as a photo may be, more in rows. */
        private fun loose(d: BookDay): List<Media> = d.loosePhotos.filter { src(it) != null }

        private fun StringBuilder.postcardDay(d: BookDay) {
            // Photos placed by the author stay between their text and page breaks.
            // Only unattached album photos may become theme decorations.
            val loose = loose(d)
            val hero = loose.firstOrNull()
            append("<section class=\"day").append(if (hero == null) " no-hero" else "").append("\" id=\"day-").append(d.date).append("\">\n")
            if (hero != null) {
                append("<figure class=\"hero\" style=\"width:").append(pct(PhotoRows.single(aspect(hero), textW, scale.singleMm, scale.share)))
                    .append("%\"><img src=\"").append(esc(src(hero)!!)).append("\" alt=\"\"></figure>\n")
            }
            append("<div class=\"stamp\">")
            if (d.number != null) {
                append("<span>").append(esc(l.day)).append("</span><b>").append(d.number).append("</b>")
            } else {
                append("<span>").append(esc(DateTimeFormatter.ofPattern("MMM", locale).format(d.date))).append("</span><b>").append(d.date.dayOfMonth).append("</b>")
            }
            shortPlace(d.place)?.let { append("<span>").append(esc(it)).append("</span>") }
            append("</div>\n")
            append("<p class=\"meta\"><span>").append(esc(longDate.format(d.date))).append("</span><span>")
                .append(esc(listOfNotNull(d.place, weatherText(d)).joinToString(" · "))).append("</span></p>\n")
            entries(d)
            // A print style changes presentation, never which saved memories are included.
            photos(loose.drop(1).map { Pic(it) })
            if (o.includePlan && d.plan.isNotEmpty()) planList(d.plan, withHeading = true)
            append("</section>\n")
        }

        /** Notebook: the day as a round red stamp, the date in handwriting with highlighter, photos as taped prints. */
        private fun StringBuilder.notebookDay(d: BookDay) {
            append("<section class=\"day\" id=\"day-").append(d.date).append("\">\n<header class=\"nb-head\">")
            val region = BookFacts.region(d.place).uppercase().take(14)
            val stamp = if (d.number != null) {
                BookSvg.dayStamp("ds-${d.date}", RED, region, l.day.uppercase(), d.number.toString(), dayMonth.format(d.date).uppercase(), STAMP_FONT)
            } else {
                BookSvg.dayStamp("ds-${d.date}", RED, region, DateTimeFormatter.ofPattern("MMM", locale).format(d.date).uppercase(), d.date.dayOfMonth.toString(), d.date.year.toString(), STAMP_FONT)
            }
            append("<span class=\"daystamp\">").append(stamp).append("</span>")
            dayNumberText(d)?.let { append("<p class=\"dayno\">").append(esc(it)).append("</p>") }
            append("<h2 class=\"date\"><span class=\"mk\">").append(esc(handDate.format(d.date))).append("</span></h2>")
            val codes = d.stops.mapNotNull { it.countryCode?.uppercase()?.takeIf { c -> c.length == 2 } }.distinct()
            val place = listOfNotNull(d.place, weatherText(d)).joinToString(" · ")
            if (place.isNotBlank() || codes.isNotEmpty()) {
                append("<p class=\"place\">")
                flags(codes)
                append("<span>").append(esc(place)).append("</span></p>")
            }
            append("</header>\n")
            entries(d)
            photos(loose(d).map { Pic(it) })
            if (o.includePlan && d.plan.isNotEmpty()) planList(d.plan, withHeading = true)
            append("</section>\n")
        }

        private fun StringBuilder.flags(codes: List<String>) {
            if (codes.isEmpty()) return
            append("<span class=\"flags\">")
            codes.take(8).forEach { append("<span class=\"fl\">").append(Flags.svg(it, 4.2)).append("</span>") }
            append("</span>")
        }

        /** The trip's countries (within the chosen days), in the order they were reached. */
        private fun countries(): List<String> = book.stops.filter { it.deletedAt == null && o.includes(book.dateOf(it.arrivedAt)) }
            .sortedBy { it.arrivedAt }.mapNotNull { it.countryCode?.uppercase()?.takeIf { c -> c.length == 2 } }.distinct()

        private fun StringBuilder.scrapbookDay(d: BookDay) {
            append("<section class=\"day\" id=\"day-").append(d.date).append("\">\n<header>")
            dayNumberText(d)?.let { append("<p class=\"dayno\">").append(esc(it)).append("</p>") }
            append("<p class=\"date\">").append(esc(listOfNotNull(handDate.format(d.date), d.place).joinToString(" · "))).append("</p></header>\n")
            val tickets = d.plan.filter { PlanKind.of(it.kind) == PlanKind.Transport && it.done }
            if (tickets.isNotEmpty()) {
                append("<div class=\"tickets\">")
                tickets.forEach { t ->
                    append("<span class=\"ticket\">").append(esc(listOfNotNull(t.title, t.timeMinutes?.let(::clock)).joinToString(" · "))).append("</span>")
                }
                append("</div>\n")
            }
            entries(d)
            photos(loose(d).map { Pic(it) })
            if (o.includePlan && d.plan.isNotEmpty()) planList(d.plan, withHeading = true)
            append("</section>\n")
        }

        private fun StringBuilder.entries(d: BookDay) {
            d.entries.forEach { be ->
                val e = be.entry
                append("<article class=\"entry").append(be.doc.font?.let { " font-" + cssIdent(it) } ?: "").append("\">\n")
                if (e.title.isNotBlank()) append("<h3>").append(esc(e.title)).append("</h3>\n")
                append("<p class=\"when\">").append(esc(whenLine(e.occurredAt, e.zoneId, e.placeName))).append("</p>\n")
                val authoredPages = be.doc.blocks.any { it == Block.PageBreak }
                val pages = if (authoredPages) DocRender.printPages(be.doc) else listOf(be.doc)
                pages.forEachIndexed { index, page ->
                    append("<div class=\"body")
                    if (authoredPages) append(" authored-pages")
                    if (index > 0) append(" page-continuation")
                    append('"')
                    if (index > 0) append(" style=\"break-before:page;page-break-before:always\"")
                    append(">\n")
                    noteParts(page).forEach { part ->
                        if (part.photos.isEmpty()) {
                            append("<div class=\"text\">\n").append(DocRender.html(EntryDoc(page.font, part.text)) { null }).append("</div>\n")
                        } else {
                            notePhotos(part.photos)
                        }
                    }
                    append("</div>\n")
                }
                append("</article>\n")
            }
        }

        /**
         * A note's photos that stand together (nothing written between them): one placed photo keeps the author's
         * size, turn, frame and caption; more go side by side in rows.
         */
        private fun StringBuilder.notePhotos(blocks: List<Block>) {
            val single = blocks.singleOrNull() as? Block.Photo
            if (single != null) {
                book.media(single.mediaId)?.takeIf { src(it) != null }?.let { placed(single, it) }
                return
            }
            photos(pics(blocks))
        }

        /** The photos of [blocks] that can be shown, with their captions. */
        private fun pics(blocks: List<Block>): List<Pic> = blocks.flatMap { b ->
            when (b) {
                is Block.Photo -> listOf(Pic(book.media(b.mediaId) ?: return@flatMap emptyList(), b.caption))
                is Block.Album -> b.mediaIds.mapNotNull { id -> book.media(id)?.let { Pic(it) } }
                else -> emptyList()
            }
        }.filter { src(it.media) != null }

        /**
         * One photo placed in a note: the author's width (scaled by the photo size), turn, frame, caption and place
         * across the column, but never taller than a photo may be: a tall photo gets narrower instead of cropped.
         */
        private fun StringBuilder.placed(b: Block.Photo, m: Media) {
            val a = aspect(m)
            val fit = PhotoRows.single(a, textW, scale.singleMm, b.width.coerceIn(0.3f, 1f) * scale.share)
            val p = PhotoPlacement.of(b.copy(width = fit.toFloat()), a.toFloat())
            // The room a turned photo's corners need is padding round it: at the top of a page a margin is dropped
            // and the corner would print at the foot of the page before.
            append("<div class=\"pic\"")
            if (b.rotation != 0f) append(" style=\"--room:").append(pct(p.verticalRoom.toDouble())).append("%\"")
            append("><figure class=\"photo placed frame-").append(b.frame.id).append("\" style=\"width:").append(pct(p.width.toDouble()))
                .append("%;margin-left:").append(pct(p.left.toDouble())).append('%')
            if (b.rotation != 0f) append(";transform:rotate(").append(num(b.rotation.toDouble())).append("deg)")
            append("\"><img src=\"").append(esc(src(m)!!)).append("\" alt=\"").append(esc(b.caption)).append("\">")
            if (b.caption.isNotBlank()) append("<figcaption>").append(esc(b.caption)).append("</figcaption>")
            append("</figure></div>\n")
        }

        /** Photos in this style: one alone as big as a photo may be, more in justified rows. */
        private fun StringBuilder.photos(pics: List<Pic>) {
            when (pics.size) {
                0 -> Unit
                1 -> alone(pics[0])
                else -> {
                    append("<div class=\"rows\">")
                    PhotoRows.rows(pics.map { aspect(it.media) }, textW, scale.rowMm, gap, extra = frame).forEach { row(pics, it) }
                    append("</div>\n")
                }
            }
        }

        /** A photo by itself, centred, in the style's frame: a taped print, a polaroid, or plain. */
        private fun StringBuilder.alone(p: Pic) {
            val w = PhotoRows.single(aspect(p.media), textW, scale.singleMm, scale.share)
            val cls = when (theme) {
                PrintTheme.Notebook, PrintTheme.Minimal -> "nb-print alone"
                PrintTheme.Scrapbook -> "photo frame-polaroid alone"
                else -> "photo alone"
            }
            append("<div class=\"pic\"><figure class=\"").append(cls).append("\" style=\"width:").append(pct(w)).append("%\">")
            if (taped) append("<i class=\"tape c\"></i>")
            append("<img src=\"").append(esc(src(p.media)!!)).append("\" alt=\"").append(esc(p.caption)).append("\">")
            if (p.caption.isNotBlank()) append("<figcaption>").append(esc(p.caption)).append("</figcaption>")
            append("</figure></div>\n")
        }

        /**
         * One justified row: each photo grows by its own width / height (flex-grow), so all come out the same height
         * and fill the row; a row narrower than the column is centred at its own width.
         */
        private fun StringBuilder.row(pics: List<Pic>, r: PhotoRows.Row) {
            append("<div class=\"row\"")
            if (r.width < textW - 0.5) append(" style=\"width:").append(pct(r.width / textW)).append("%\"")
            append('>')
            for (i in r.from until r.to) {
                val p = pics[i]
                val a = num(aspect(p.media))
                append("<figure style=\"flex-grow:").append(a).append("\">")
                if (taped) append("<i class=\"tape c\"></i>")
                append("<img src=\"").append(esc(src(p.media)!!)).append("\" alt=\"").append(esc(p.caption)).append("\" style=\"aspect-ratio:").append(a).append("\">")
                if (p.caption.isNotBlank()) append("<figcaption>").append(esc(p.caption)).append("</figcaption>")
                append("</figure>")
            }
            append("</div>")
        }

        private fun StringBuilder.planList(items: List<PlanItem>, withHeading: Boolean) {
            append("<section class=\"plan\">")
            if (withHeading) append("<h4>").append(esc(l.plan)).append("</h4>")
            append("<ul class=\"plan-list\">")
            items.forEach { p ->
                append(if (p.done) "<li class=\"done\">" else "<li>")
                append("<span class=\"t\">").append(esc(spanTime(p))).append("</span>")
                append("<span class=\"box\"></span>")
                append("<span class=\"what\"><b>").append(esc(p.title)).append("</b>")
                PlanSpan.until(p)?.let { append(" · ").append(esc(it)) }
                val note = listOf(p.note, p.placeName).filter { it.isNotBlank() }.joinToString(" · ")
                if (note.isNotBlank()) append("<span class=\"note\">").append(esc(note)).append("</span>")
                append("</span><span class=\"kind\">").append(esc(PlanKind.of(p.kind).label)).append("</span></li>")
            }
            append("</ul></section>\n")
        }

        // ---------------------------------------------------------------- docs

        private fun StringBuilder.docsBody() {
            append("<h1>").append(esc(trip.title)).append("</h1>\n")
            append("<p class=\"meta\">").append(esc(listOf(dateRange(), trip.subtitle).filter { it.isNotBlank() }.joinToString(" · "))).append("</p>\n")
            coverPhoto()?.let { docsImage(it, PhotoRows.single(aspect(it), textW, DOCS_COVER_MM * unit), "") }
            append("<p class=\"facts\">").append(esc(statsLine())).append("</p>\n")
            if (days.isEmpty()) append("<p class=\"sub\">").append(esc(l.nothingYet)).append("</p>\n")
            days.forEach { d ->
                pageBreak()
                append("<h2>").append(esc(listOfNotNull(dayNumberText(d), longDate.format(d.date), d.place).joinToString(" · "))).append("</h2>\n")
                val factLine = dayFacts(d).joinToString(" · ") { (k, v) -> "$k $v" }
                if (factLine.isNotBlank()) append("<p class=\"meta\">").append(esc(factLine)).append("</p>\n")
                var loose = loose(d).map { Pic(it) }
                when (theme) {
                    PrintTheme.Postcard -> loose.firstOrNull()?.let { hero ->
                        docsImage(hero.media, PhotoRows.single(aspect(hero.media), textW, scale.singleMm * unit, scale.share), "")
                        loose = loose.drop(1)
                    }
                    PrintTheme.Scrapbook, PrintTheme.Notebook, PrintTheme.Minimal -> Unit
                }
                d.entries.forEach { be ->
                    val e = be.entry
                    if (e.title.isNotBlank()) append("<h3>").append(esc(e.title)).append("</h3>\n")
                    append("<p class=\"when\">").append(esc(whenLine(e.occurredAt, e.zoneId, e.placeName))).append("</p>\n")
                    append("<div class=\"entry\">\n")
                    docsBlocks(be.doc)
                    append("</div>\n")
                }
                docsPhotos(loose)
                if (o.includePlan && d.plan.isNotEmpty()) docsPlan(d.plan, l.plan)
            }
            val ideas = if (o.includePlan && o.wholeTrip) book.ideas() else emptyList()
            if (ideas.isNotEmpty()) {
                pageBreak()
                docsPlan(ideas, l.ideas, heading = true)
            }
        }

        /** Same markup Docs writes for its own page breaks, so a round trip keeps them. */
        private fun StringBuilder.pageBreak() = append("<hr style=\"page-break-before:always;display:none;\">\n")

        private fun StringBuilder.docsBlocks(doc: EntryDoc) {
            DocRender.printPages(doc).forEachIndexed { index, page ->
                if (index > 0) pageBreak()
                noteParts(page).forEach { part ->
                    if (part.photos.isEmpty()) {
                        append(DocRender.html(EntryDoc(doc.font, part.text)) { null })
                    } else {
                        val single = part.photos.singleOrNull() as? Block.Photo
                        val m = single?.let { book.media(it.mediaId) }?.takeIf { src(it) != null }
                        if (single != null && m != null) {
                            val w = PhotoRows.single(aspect(m), textW, scale.singleMm * unit, single.width.coerceIn(0.3f, 1f) * scale.share)
                            docsImage(m, w, single.caption, single.position)
                        } else if (single == null) {
                            docsPhotos(pics(part.photos))
                        }
                    }
                }
            }
        }

        /** Photos in Docs: one alone, more as rows, each row a table of photos at their own shape. */
        private fun StringBuilder.docsPhotos(pics: List<Pic>) {
            if (pics.isEmpty()) return
            if (pics.size == 1) {
                docsImage(pics[0].media, PhotoRows.single(aspect(pics[0].media), textW, scale.singleMm * unit, scale.share), pics[0].caption)
                return
            }
            PhotoRows.rows(pics.map { aspect(it.media) }, textW, scale.rowMm * unit, gap).forEach { r ->
                append("<table style=\"border-collapse:collapse;margin:0 auto\"><tr>")
                for (i in r.from until r.to) {
                    val m = pics[i].media
                    val h = r.height.roundToInt().coerceAtLeast(1)
                    val w = (r.height * aspect(m)).roundToInt().coerceAtLeast(1)
                    append("<td style=\"padding:0 ").append(if (i < r.to - 1) gap.roundToInt() else 0).append("px ").append(gap.roundToInt()).append("px 0;vertical-align:top\">")
                    append("<img src=\"").append(esc(src(m)!!)).append("\" width=\"").append(w).append("\" height=\"").append(h)
                        .append("\" style=\"width:").append(w).append("px;height:").append(h).append("px\" alt=\"").append(esc(pics[i].caption)).append("\"></td>")
                }
                append("</tr></table>\n")
                val caps = (r.from until r.to).map { pics[it].caption }.filter { it.isNotBlank() }
                if (caps.isNotEmpty()) append("<p class=\"cap\">").append(esc(caps.joinToString(" · "))).append("</p>\n")
            }
        }

        /** One photo at [fraction] of the column, its height from its shape; [position] places it across the column. */
        private fun StringBuilder.docsImage(m: Media, fraction: Double, caption: String, position: Float? = null) {
            val w = (textW * fraction).roundToInt().coerceAtLeast(1)
            val h = (w / aspect(m)).roundToInt().coerceAtLeast(1)
            if (position != null) {
                val left = ((DOCS_TEXT_WIDTH_PX - w).coerceAtLeast(0) * position.coerceIn(0f, 1f)).roundToInt()
                append("<p style=\"text-align:left;margin-left:").append(left).append("px\">")
            } else append("<p class=\"center\">")
            append("<img src=\"").append(esc(src(m)!!)).append("\" width=\"").append(w).append("\" height=\"").append(h)
                .append("\" style=\"width:").append(w).append("px;height:").append(h).append("px\" alt=\"").append(esc(caption)).append("\"></p>\n")
            if (caption.isNotBlank()) append("<p class=\"cap\">").append(esc(caption)).append("</p>\n")
        }

        private fun StringBuilder.docsPlan(items: List<PlanItem>, title: String, heading: Boolean = false) {
            if (heading) append("<h2>").append(esc(title)).append("</h2>\n") else append("<h4>").append(esc(title)).append("</h4>\n")
            append("<table style=\"border-collapse:collapse;width:100%\">")
            items.forEach { p ->
                val note = listOf(p.note, p.placeName).filter { it.isNotBlank() }.joinToString(" · ")
                append("<tr><td class=\"t\">").append(esc(spanTime(p))).append(if (p.done) " ✓" else "").append("</td><td><b>").append(esc(p.title)).append("</b>")
                PlanSpan.until(p)?.let { append(" · ").append(esc(it)) }
                if (note.isNotBlank()) append("<br>").append(esc(note))
                append("</td><td class=\"kind\">").append(esc(PlanKind.of(p.kind).label)).append("</td></tr>")
            }
            append("</table>\n")
        }

        // ---------------------------------------------------------------- texts

        private fun dayNumberText(d: BookDay): String? = d.number?.let { n ->
            if (d.total != null && d.total > 0) "${l.day} $n ${l.of} ${d.total}" else "${l.day} $n"
        }

        private fun dateRange(): String {
            val s = trip.startDate ?: days.firstOrNull()?.date ?: return ""
            val e = trip.endDate ?: days.lastOrNull()?.date ?: s
            val from = o.from?.takeIf { it.isAfter(s) } ?: s
            val to = o.to?.takeIf { it.isBefore(e) } ?: e
            return range(from, to)
        }

        fun range(start: LocalDate, end: LocalDate): String = TripHtml.range(start, end, locale)

        private fun whenLine(at: Long, zone: String, place: String): String {
            val z = runCatching { ZoneId.of(zone) }.getOrDefault(book.zone)
            val time = hhmm.format(Instant.ofEpochMilli(at).atZone(z))
            return listOf(time, place).filter { it.isNotBlank() }.joinToString(" · ")
        }

        private fun weatherText(d: BookDay): String? {
            val t = d.tempC?.let { "${it.roundToInt()}°" }
            val w = d.weather?.replace('_', ' ')
            return listOfNotNull(t, w).joinToString(" ").ifBlank { null }
        }

        private fun km(v: Double): String = if (v < 100) String.format(locale, "%.1f km", v) else String.format(locale, "%,.0f km", v)

        private fun dayFacts(d: BookDay): List<Pair<String, String>> = buildList {
            if (d.km >= 0.05) add(l.moved to km(d.km))
            if (d.stops.isNotEmpty()) add(l.stops to d.stops.size.toString())
            weatherText(d)?.let { add(l.weather to it) }
        }

        private fun tripFacts(): List<Pair<String, String>> = buildList {
            val n = if (o.wholeTrip) trip.days ?: days.size else days.size
            if (n > 0) add(l.days to n.toString())
            val k = days.sumOf { it.km }
            if (k >= 0.05) add(l.moved to km(k))
            val s = days.sumOf { it.stops.size }
            if (s > 0) add(l.stops to s.toString())
            // Videos print as their first frame but aren't counted as photos.
            val p = days.sumOf { d -> d.photos.count { !it.isVideo && src(it) != null } }
            if (p > 0) add(l.photos to p.toString())
        }

        private fun statsLine(): String = tripFacts().joinToString(" · ") { (k, v) -> "$v ${k.lowercase(locale)}" }

        private fun clock(minutes: Int): String = "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)

        /** The last part of a place name, short enough for the stamp ("Halki, Naxos" → "Naxos"). */
        private fun shortPlace(place: String?): String? {
            val p = place?.split(',', '→')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() } ?: return null
            return if (p.length <= 14) p else p.take(13).trimEnd() + "…"
        }
    }

    /** A stretch of a note: its words, or photos standing together (nothing written between them). */
    internal data class NotePart(val text: List<Block> = emptyList(), val photos: List<Block> = emptyList())

    /**
     * [doc]'s blocks (one printed page of them) cut into words and photo runs, in order. Photos with only empty lines
     * between them count as standing together; the empty lines go.
     */
    internal fun noteParts(doc: EntryDoc): List<NotePart> {
        val out = ArrayList<NotePart>()
        val text = ArrayList<Block>()
        val photos = ArrayList<Block>()
        fun flushText() { if (text.isNotEmpty()) out += NotePart(text = text.toList()); text.clear() }
        fun flushPhotos() { if (photos.isNotEmpty()) out += NotePart(photos = photos.toList()); photos.clear() }
        doc.blocks.forEach { b ->
            when {
                b is Block.Photo || b is Block.Album -> { flushText(); photos += b }
                photos.isNotEmpty() && b is Block.Paragraph && b.list == null && RichHtml.toPlainText(b.html).isBlank() -> Unit
                else -> { flushPhotos(); text += b }
            }
        }
        flushText()
        flushPhotos()
        return out
    }

    private fun esc(s: String) = RichHtml.escape(s)

    /** Percent of a fraction, at most three decimals, always with a dot. */
    private fun pct(fraction: Double): String = num(fraction * 100)

    private fun num(v: Double): String = "%.3f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')

    private const val RED = "#C4423A"
    private const val STAMP_FONT = "'Barlow Condensed',sans-serif"

    /** Only letters, digits and dashes, for class names built from stored ids. */
    private fun cssIdent(s: String) = s.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "x" }

    /** Google Docs' text column at its default margins, in CSS px. */
    const val DOCS_TEXT_WIDTH_PX = 624

    /** CSS px per mm (96 per inch), for photo sizes in Docs. */
    private const val PX_PER_MM = 96 / 25.4

    /** The tallest the cover photo is in Docs. */
    private const val DOCS_COVER_MM = 150.0

    /** The widest a photo is shown in Docs at [size], for downscaling before upload. */
    fun docsPhotoMaxPx(size: PhotoSize): Int = when (size) {
        PhotoSize.Small -> 800
        PhotoSize.Medium -> 1200
        PhotoSize.Large -> 1600
    }

    /** Pixels a print photo needs at [size] (about 250 dpi across the text column). */
    fun printPhotoMaxPx(size: PhotoSize): Int = when (size) {
        PhotoSize.Small -> 1200
        PhotoSize.Medium -> 1600
        PhotoSize.Large -> 2000
    }

}

/** An item's time for print: "19:00–22:00" when it ends the same day, else its start (the end follows the title). */
internal fun spanTime(p: PlanItem): String =
    (if (PlanSpan.days(p) == 0) PlanSpan.times(p) else p.timeMinutes?.let(Dates::time)).orEmpty()

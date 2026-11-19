package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.album.AlbumLayout
import com.justdataplease.boringtravel.export.GeoPoint
import com.justdataplease.boringtravel.export.RouteGeo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Something placed on a page; boxes are mm from the trim's top-left. */
sealed interface El

/** A strip of tape on a print, centred at ([cx], [cy]) relative to the print's frame. */
data class Tape(val cx: Double, val cy: Double, val w: Double, val h: Double, val angle: Double)

/**
 * A photo: [frame] is the print (white border included) or the photo itself; [image] the part showing the photo;
 * [tilt] in degrees; [caption] where its words go ([captionInFrame]: on the print's band, turning with it); [bleed] =
 * fills the page to the bleed edge. [empty] slots show as placeholders in the editor and are left out of the PDF.
 */
data class PhotoEl(
    val slot: Int,
    val mediaId: String?,
    val frame: Box,
    val image: Box,
    val tilt: Double,
    val crop: Crop,
    val caption: Box?,
    val captionText: String,
    val captionInFrame: Boolean,
    val tapes: List<Tape>,
    val bleed: Boolean,
    val empty: Boolean,
    /** print | flat | bleed for a photo placed by hand (its own look, whatever the theme); null = the theme's. */
    val style: String? = null,
) : El

/** The page's flowing text (paragraphs), in [cols] columns. */
data class FlowEl(val box: Box, val rows: Int, val cols: Int, val colWidth: Double, val gutter: Double, val angle: Double = 0.0) : El {
    val flowBox: FlowBox get() = FlowBox(rows, cols, colWidth)
}

/**
 * One short text: [role] is its CSS class; [lineMm] its line height (a whole number of grid lines, so it sits on the
 * paper's lines); [editable] names the page field it edits ("title", "subtitle"), if any; [mark] = highlighter behind.
 * [capOf] is the photo spot whose caption it shows. [exact] = drawn exactly in [box] (placed by hand: it turns around
 * the box's centre, and its words can run over several lines), [color] its own colour.
 */
data class TextEl(
    val role: String,
    val box: Box,
    val text: String,
    val face: Face,
    val pt: Double,
    val bold: Boolean,
    val lineMm: Double,
    val align: String = "left",
    val editable: String? = null,
    val mark: Boolean = false,
    val angle: Double = 0.0,
    val upper: Boolean = false,
    val italic: Boolean = false,
    val capOf: Int? = null,
    val exact: Boolean = false,
    val color: String? = null,
) : El

enum class StampKind { Brand, Day, Postage }

/** A stamp: the logo (the paper plane tile), a round day stamp, or Postcard's dashed postage stamp. */
data class StampEl(val kind: StampKind, val box: Box, val angle: Double, val top: String = "", val middle: String = "", val bottom: String = "", val small: String = "") : El

/** A route drawn in pen: [markers] are numbered day ends (map page), [scaleBar] adds a scale. */
data class RouteEl(
    val box: Box, val route: RouteGeo, val labels: Boolean, val markers: List<Pair<Int, GeoPoint>> = emptyList(), val scaleBar: Boolean = false,
    val angle: Double = 0.0,
) : El

/** Country flags in a row, [height] mm tall; [sticker] = small tilted stickers with a white edge. */
data class FlagsEl(val box: Box, val codes: List<String>, val height: Double, val sticker: Boolean, val align: String = "left", val angle: Double = 0.0) : El

/** A hairline. */
data class RuleEl(val box: Box, val angle: Double = 0.0) : El

/** Writing lines or dots inside [box] (notes pages in themes without paper). */
data class LinesEl(val box: Box, val paper: BookPaper, val pitch: Double, val angle: Double = 0.0) : El

/**
 * A decoration ([FreeKind.DECO]): washi tape, photo corners, a box or circle ([style] rect | oval), a line, or the soft
 * shade under words on a photo. [color] is the tape, corners or line colour and the outline; [fill] a shape's inside.
 */
data class DecoEl(val kind: String, val box: Box, val angle: Double, val color: String?, val fill: String?, val stroke: Double, val style: String?) : El

/**
 * Everything on one printed page. [number] counts from 1 at the cover; odd numbers are right-hand pages. [ids] runs
 * beside [els]: the element each comes from (`t0`, `t1`, … for a template's own, the [FreeEl] id for one placed by
 * hand, null for the page number and running head). [bg] is the page's background.
 */
data class PagePlan(val page: BookPage, val number: Int, val els: List<El>, val paper: BookPaper?, val ids: List<String?> = emptyList(), val bg: PageBg? = null) {
    val recto: Boolean get() = number % 2 == 1
    val photos: List<PhotoEl> get() = els.filterIsInstance<PhotoEl>()
    val flow: FlowEl? get() = els.filterIsInstance<FlowEl>().firstOrNull()

    fun id(i: Int): String? = ids.getOrNull(i)
}

/**
 * Where everything goes on each template, for one book's format, theme and fonts. All sizes come from the [Grid], so
 * an A4 book is an A5 book with more room, and every text sits on the grid's lines.
 */
class PageLayout(val spec: BookSpec, val facts: BookFacts, val measure: TextMeasure = TextFit.Estimate) {
    val sheet = Sheet(spec.format, spec.bleed)
    val grid = Grid(spec.format)
    val look = BookLook.of(spec.theme)
    val fonts = spec.faces
    val type = FlowType(grid, fonts)
    private val L = grid.line
    private val s = grid.scale
    private val C = grid.content
    private val W = spec.format.widthMm
    private val H = spec.format.heightMm
    private val labels = facts.labels

    /** Paper on this page: the book's paper on paper themes; notes pages draw their own lines elsewhere. */
    private fun paperFor(t: Template?): BookPaper? = when {
        t == Template.PhotoFull -> null
        look.paperEverywhere -> spec.paper
        else -> null
    }

    /** Elements placed by hand: turning them into what is drawn, and a template's own into them. */
    val free: FreeLayout by lazy { FreeLayout(this) }

    /**
     * Everything drawn on [page]: a template page's own elements with the ones placed by hand on top; a free page's
     * elements alone. The page number and running head follow the template in both. Hidden elements and a hidden
     * background are left out.
     */
    fun plan(page: BookPage, number: Int): PagePlan {
        val t = page.template
        val els = ArrayList<El>()
        val ids = ArrayList<String?>()
        if (!page.free) templateEls(page, number).forEachIndexed { i, e -> els += e; ids += FreeLayout.derivedId(i) }
        // Hidden elements stay in the page's record (the editor lists them) but are not drawn or printed.
        page.els.forEach { fe -> if (!fe.hidden) free.toEl(free.resolve(fe, page), page)?.let { els += it; ids += fe.id } }
        if (t != null && t.folio) { els += folio(number); ids += null }
        if (t != null && t.head) runningHead(page, number)?.let { els += it; ids += null }
        val bg = page.bg?.takeIf { !it.hidden }
        return PagePlan(page, number, els, if (bg != null) null else paperFor(t), ids, bg)
    }

    /** What [page]'s template draws, without the page number and running head. */
    fun templateEls(page: BookPage, number: Int): List<El> {
        val els = ArrayList<El>()
        when (page.template) {
            Template.Cover -> cover(page, els)
            Template.TitlePage -> titlePage(page, els)
            Template.DayOpener -> dayOpener(page, els)
            Template.PhotoFull -> photoFull(page, els)
            Template.PhotoFullCaption -> photoFullCaption(page, els)
            Template.Photo -> photoOne(page, els, number)
            Template.PhotoTwo -> photoTwo(page, els, number)
            Template.PhotoTwoSide -> photoTwoSide(page, els, number)
            Template.PhotoThree -> photoThree(page, els, number)
            Template.PhotoThreeUp -> photoThreeUp(page, els, number)
            Template.PhotoFour -> photoFour(page, els, number)
            Template.Album -> album(page, els)
            Template.PhotoText -> photoText(page, els, number)
            Template.PhotoTextSide -> photoTextSide(page, els, number)
            Template.Text -> els += textFlow()
            Template.Map -> map(page, els)
            Template.Stats -> stats(page, els)
            Template.Notes -> notes(page, els)
            Template.BackCover -> back(page, els)
            null -> Unit
        }
        return els
    }

    /** The text area of a text page (same as [plan]'s [FlowEl]), for laying out text without drawing. */
    fun flowBox(page: BookPage): FlowBox? {
        if (page.free) {
            if (page.template?.text != true) return null
            // A free text page without its text box takes no text; the text moves on to the next page.
            return page.els.firstOrNull { it.isFlow && !it.hidden }?.let { free.flowEl(it).flowBox } ?: FlowBox(0, 1, C.w)
        }
        return when (page.template) {
            Template.PhotoText -> photoTextFlow().flowBox
            Template.PhotoTextSide -> photoTextSideFlow().flowBox
            Template.Text -> textFlow().flowBox
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private val Template.folio: Boolean
        get() = this in setOf(
            Template.DayOpener, Template.Photo, Template.PhotoTwo, Template.PhotoTwoSide, Template.PhotoThree, Template.PhotoThreeUp, Template.PhotoFour,
            Template.Album, Template.PhotoText, Template.PhotoTextSide, Template.Text, Template.Map, Template.Stats, Template.Notes,
        )

    private val Template.head: Boolean
        get() = this in setOf(
            Template.Photo, Template.PhotoTwo, Template.PhotoTwoSide, Template.PhotoThree, Template.PhotoThreeUp, Template.PhotoFour,
            Template.Album, Template.PhotoText, Template.PhotoTextSide, Template.Text,
        )

    private fun pt(v: Double) = v * s
    private fun dpt(v: Double) = v * s * fonts.displayScale
    private fun rows(n: Int) = n * L

    private fun mono(role: String, box: Box, text: String, align: String = "left", mark: Boolean = false, size: Double = grid.monoPt) =
        TextEl(role, box, text, fonts.mono, size, false, L, align, mark = mark, upper = true)

    /** A display-face text sized to fit [maxLines] lines of [width]; its box grows by whole lines. */
    private fun display(
        role: String, x: Double, y: Double, width: Double, text: String, sizes: List<Double>, maxLines: Int,
        align: String = "left", editable: String? = null, mark: Boolean = false, angle: Double = 0.0,
    ): TextEl {
        val bold = fonts.displayWeight >= 600
        val w = width / (if (fonts.upper) 1.12 else 1.0)
        val ptSize = TextFit.fitSize(text.ifBlank { "M" }, w, maxLines, fonts.display, bold, sizes.map(::dpt), measure)
        val lines = TextFit.lines(text.ifBlank { "M" }, w, fonts.display, ptSize, bold, measure).coerceIn(1, maxLines)
        val lineMm = gridLine(ptSize * TextFit.PT_MM * 1.0)
        return TextEl(role, Box(x, y, width, lines * lineMm), text, fonts.display, ptSize, bold, lineMm, align, editable, mark, angle, fonts.upper)
    }

    /** A line height in whole grid lines at least [mm] tall. */
    private fun gridLine(mm: Double): Double = max(1, kotlin.math.ceil(mm / L - 0.08).toInt()) * L

    /** A secondary line: handwriting in hand sets, italic text otherwise. */
    private fun secondary(role: String, x: Double, y: Double, width: Double, text: String, align: String = "left", editable: String? = null, maxLines: Int = 2): TextEl {
        return if (fonts.hand) {
            val size = TextFit.fitSize(text.ifBlank { "M" }, width, maxLines, fonts.display, false, listOf(dpt(17.0), dpt(15.0), dpt(13.0)), measure)
            val lines = TextFit.lines(text.ifBlank { "M" }, width, fonts.display, size, false, measure).coerceIn(1, maxLines)
            val lineMm = gridLine(size * TextFit.PT_MM)
            TextEl(role, Box(x, y, width, lines * lineMm), text, fonts.display, size, false, lineMm, align, editable)
        } else {
            val size = pt(11.0)
            val lines = TextFit.lines(text.ifBlank { "M" }, width, fonts.italic, size, false, measure).coerceIn(1, maxLines)
            TextEl(role, Box(x, y, width, lines * L), text, fonts.italic, size, false, L, align, editable, italic = true)
        }
    }

    internal fun captionEl(box: Box, text: String, align: String = "center"): TextEl = if (fonts.hand) {
        TextEl("cap", box, text, fonts.display, dpt(12.5), false, L, align)
    } else {
        TextEl("cap", box, text, fonts.italic, pt(7.8), false, L, if (align == "center") "left" else align, italic = true)
    }

    /**
     * A photo in [region]. Prints (paper themes) keep the photo's own shape inside a white border, tilt and get
     * tape; flat themes fit the photo ([fill] = crop it to the region instead). [captionLines] are kept free under it.
     */
    private fun photo(
        page: BookPage, i: Int, region: Box, tilt: Double, alignX: Double = 0.5, alignY: Double = 0.5,
        fill: Boolean = false, captionLines: Int = 1, bleed: Boolean = false, tapes: Int = 1,
    ): PhotoEl {
        val slot = page.slot(i)
        val media = facts.media(slot.mediaId)
        val aspect = facts.aspect(slot.mediaId)
        val withCap = captionLines > 0 && slot.caption.isNotBlank() || captionLines > 0 && !bleed
        val capH = if (withCap && !(look.prints && look.bottomBorder > 4)) captionLines * L + 0.6 * L else 0.0
        val avail = region.inset(0.0, 0.0, 0.0, capH)
        val frame: Box
        val image: Box
        if (bleed || fill && !look.prints) {
            frame = avail
            image = avail
        } else if (look.prints) {
            val b = look.border * s
            val bb = look.bottomBorder * s
            // Room for the tilt: a tilted print reaches a little further out.
            val room = avail.inset(avail.w * 0.025, avail.h * 0.02, avail.w * 0.025, avail.h * 0.02)
            val iw = min(room.w - 2 * b, (room.h - b - bb) * aspect).coerceAtLeast(1.0)
            val ih = iw / aspect
            val fw = iw + 2 * b
            val fh = ih + b + bb
            frame = Box(room.x + (room.w - fw) * alignX, room.y + (room.h - fh) * alignY, fw, fh)
            image = Box(frame.x + b, frame.y + b, iw, ih)
        } else {
            frame = avail.fit(aspect, alignX, alignY)
            image = frame
        }
        val angle = if (look.tilt && !bleed) (slot.tilt ?: tilt) else 0.0
        val inBand = look.prints && look.bottomBorder > 4 && !bleed
        val caption = when {
            !withCap -> null
            inBand -> Box(frame.x, image.bottom, frame.w, frame.bottom - image.bottom)
            else -> {
                val top = grid.snapDown(frame.bottom + if (look.prints) 0.45 * L else 0.15 * L)
                Box(if (look.prints) min(frame.x, region.x) else frame.x, top, if (look.prints) max(frame.w, region.w * 0.6).coerceAtMost(region.w) else frame.w, captionLines * L)
                    .let { if (look.prints) it.copy(x = (frame.cx - it.w / 2).coerceIn(region.x, region.right - it.w)) else it }
            }
        }
        val tapeList = if (!look.tape || bleed) emptyList() else {
            val tw = 15.0 * s
            val th = 5.2 * s
            if (tapes == 2) listOf(Tape(frame.w * 0.1, 0.6 * th, tw, th, -38.0), Tape(frame.w * 0.9, 0.6 * th, tw, th, 36.0))
            else listOf(Tape(frame.w / 2 + (if (i % 2 == 0) -1 else 1) * frame.w * 0.06, 0.25 * th, tw * 1.1, th, if (i % 2 == 0) -4.0 else 5.0))
        }
        return PhotoEl(
            slot = i, mediaId = slot.mediaId, frame = frame, image = image, tilt = angle, crop = slot.crop,
            caption = caption, captionText = slot.caption, captionInFrame = inBand, tapes = tapeList, bleed = bleed,
            empty = media == null,
        )
    }

    private fun folio(number: Int): TextEl {
        val y = grid.y(grid.rows - 2)
        val recto = number % 2 == 1
        val box = Box(if (recto) C.right - 20 * s else C.x, y, 20 * s, L)
        return mono("folio", box, number.toString(), if (recto) "right" else "left")
    }

    private fun runningHead(page: BookPage, number: Int): TextEl? {
        val recto = number % 2 == 1
        val d = facts.day(page.day)
        val text = if (recto && d != null) listOfNotNull(facts.dayLabel(d).substringBefore(" ${labels.of} "), d.place?.let(BookFacts::short)).joinToString(" · ")
        else facts.trip.title
        if (text.isBlank()) return null
        return mono("head", Box(C.x, grid.y(1), C.w, L), text, if (recto) "right" else "left", size = grid.monoPt * 0.94)
    }

    // ---------------------------------------------------------------------------------------------- templates

    private fun cover(page: BookPage, els: MutableList<El>) {
        val title = page.title ?: facts.trip.title
        val sub = page.subtitle ?: facts.trip.subtitle
        if (look.prints) {
            val region = Box(C.x - L, grid.y(2), C.w + 2 * L, rows((grid.rows * 0.56).roundToInt()))
            val p = photo(page, 0, region, -2.0, alignY = 0.35, captionLines = 0, tapes = 2)
            els += p
            val d = 27.0 * s
            val cx = (p.frame.right - 7 * s).coerceAtMost(W - d / 2 - 5)
            val cy = (p.frame.bottom - 3 * s).coerceAtMost(H - d / 2 - 5)
            var y = grid.snapDown(max(p.frame.bottom, region.y + 40) + 1.4 * L)
            val t = display("cover-title", C.x, y, C.w, title, listOf(46.0, 40.0, 34.0, 28.0), 2, editable = "title")
            els += t
            y = t.box.bottom
            if (sub.isNotBlank()) {
                val st = secondary("cover-sub", C.x, y, C.w * 0.9, sub, editable = "subtitle")
                els += st
                y = st.box.bottom
            }
            if (facts.dateRange.isNotBlank()) {
                val text = facts.dateRange.uppercase()
                val dw = measure.widthMm(text, fonts.mono, grid.monoPt, false) + text.length * 0.09 * grid.monoPt * TextFit.PT_MM + 4.0
                els += mono("dates", Box(C.x, y, dw, L), facts.dateRange, mark = true)
                if (facts.countries.isNotEmpty()) {
                    val fh = 4.2 * s
                    els += FlagsEl(Box(C.x + dw + 2.5 * s, y + L - fh * 1.05, C.w * 0.4, fh * 1.2), facts.countries.take(6), fh, sticker = true)
                }
            }
            val bottom = grid.y(grid.rows - 3)
            val routeTop = grid.snapDown(y + 2 * L)
            if (bottom - routeTop > 6 * L) els += RouteEl(Box(C.x + C.w * 0.25, routeTop, C.w * 0.75, bottom - routeTop), facts.coverRoute, labels = true)
            els += StampEl(StampKind.Brand, Box(cx - d / 2, cy - d / 2, d, d), -12.0)
        } else {
            val photoH = H * 0.52
            val region = Box(sheet.art.x, sheet.art.y, sheet.art.w, photoH - sheet.art.y)
            els += photo(page, 0, region, 0.0, captionLines = 0, bleed = true)
            var y = grid.snapDown(photoH + 1.5 * L)
            if (spec.theme == com.justdataplease.boringtravel.model.PrintTheme.Minimal) {
                els += mono("kicker", Box(C.x, y, C.w, L), listOf(labels.brand, facts.dateRange).filter { it.isNotBlank() }.joinToString("  ·  "))
                y += 2 * L
            }
            val t = display("cover-title", C.x, y, C.w, title, listOf(40.0, 34.0, 28.0, 24.0), 2, editable = "title")
            els += t
            y = t.box.bottom
            if (sub.isNotBlank()) {
                val st = secondary("cover-sub", C.x, y, C.w, sub, editable = "subtitle")
                els += st
                y = st.box.bottom
            }
            if (spec.theme != com.justdataplease.boringtravel.model.PrintTheme.Minimal && facts.dateRange.isNotBlank()) {
                els += mono("dates", Box(C.x, y + L, C.w, L), facts.dateRange)
                y += 2 * L
            }
            val bottom = grid.y(grid.rows - 3)
            val routeTop = grid.snapDown(y + 2 * L)
            if (bottom - routeTop > 4 * L) els += RouteEl(Box(C.x, routeTop, C.w, bottom - routeTop), facts.coverRoute, labels = true)
            if (spec.theme == com.justdataplease.boringtravel.model.PrintTheme.Postcard) {
                val sw = 21.0 * s
                els += StampEl(
                    StampKind.Postage, Box(W - 3 * L - sw, grid.y(3), sw, sw * 1.18), 3.0,
                    top = labels.days.uppercase(), middle = facts.totalDays.takeIf { it > 0 }?.toString().orEmpty(),
                    bottom = BookFacts.region(facts.places.lastOrNull() ?: facts.trip.subtitle).uppercase().take(12),
                )
            }
        }
    }

    private fun titlePage(page: BookPage, els: MutableList<El>) {
        val title = page.title ?: facts.trip.title
        val sub = page.subtitle ?: facts.trip.subtitle
        val d = 17.0 * s
        els += StampEl(StampKind.Brand, Box(W / 2 - d / 2, grid.y(7), d, d), -8.0)
        var y = grid.y(13)
        val t = display("page-title center", C.x, y, C.w, title, listOf(34.0, 30.0, 26.0, 22.0), 2, align = "center", editable = "title")
        els += t
        y = t.box.bottom
        if (sub.isNotBlank()) {
            val st = secondary("sub", C.x, y, C.w, sub, align = "center", editable = "subtitle")
            els += st
            y = st.box.bottom
        }
        if (facts.dateRange.isNotBlank()) { els += mono("dates", Box(C.x, y + L, C.w, L), facts.dateRange, align = "center"); y += 2 * L }
        if (facts.countries.isNotEmpty()) {
            val fh = 5.0 * s
            els += FlagsEl(Box(C.x, y + 1.6 * L, C.w, fh * 1.4), facts.countries.take(8), fh, sticker = look.prints, align = "center")
        }
        val places = facts.places
        if (places.size >= 2) {
            val text = places.joinToString(" · ")
            els += secondary("places", C.x + C.w * 0.1, grid.y(grid.rows - 12), C.w * 0.8, text, align = "center", maxLines = 3)
        }
    }

    private fun dayOpener(page: BookPage, els: MutableList<El>) {
        val d = facts.day(page.day)
        val sd = 33.0 * s
        val stampBox = Box(C.right - sd + 1.5 * s, C.y - 1.5 * s, sd, sd)
        if (d != null && spec.theme == com.justdataplease.boringtravel.model.PrintTheme.Postcard) {
            val sw = 24.0 * s
            els += StampEl(
                StampKind.Postage, Box(C.right - sw, C.y - 1.0 * s, sw, sw * 1.18), 2.5,
                top = if (d.number != null) labels.day.uppercase() else facts.dayMonth(d.date).uppercase(),
                middle = (d.number ?: d.date.dayOfMonth).toString(),
                bottom = BookFacts.region(d.place ?: facts.trip.subtitle).uppercase().take(12),
            )
        } else if (d != null) {
            val region = BookFacts.region(d.place ?: facts.trip.subtitle).uppercase().take(14)
            els += if (d.number != null) StampEl(StampKind.Day, stampBox, -9.0, top = region, middle = d.number.toString(), small = labels.day.uppercase(), bottom = facts.dayMonth(d.date).uppercase())
            else StampEl(StampKind.Day, stampBox, -9.0, top = region, middle = d.date.dayOfMonth.toString(), small = d.date.month.name.take(3), bottom = d.date.year.toString())
        }
        val textW = C.w - sd - L
        var y = C.y
        if (d != null) els += mono("kicker", Box(C.x, y, textW, L), facts.dayLabel(d))
        y += 2 * L
        val title = page.title ?: d?.let(facts::dayTitle) ?: ""
        val t = display("day-title", C.x, y, textW, title, listOf(30.0, 26.0, 22.0), 2, editable = "title", mark = look.prints)
        els += t
        y = t.box.bottom + L
        val place = page.subtitle ?: d?.place
        if (!place.isNullOrBlank() || d?.countries?.isNotEmpty() == true) {
            val fh = 4.4 * s
            var x = C.x
            val codes = d?.countries.orEmpty()
            val fw = if (codes.isNotEmpty()) codes.size * (fh * 1.5 + 1.3 * s) + 1.0 else 0.0
            if (codes.isNotEmpty()) x += fw + 1.5 * s
            val st = if (!place.isNullOrBlank()) secondary("place", x, y, C.right - x, place, editable = "subtitle", maxLines = 1) else null
            val lineBottom = st?.box?.bottom ?: (y + L)
            // Flags sit on the same line as the place, just above its baseline.
            if (codes.isNotEmpty()) els += FlagsEl(Box(C.x, lineBottom - fh * 1.25 - 0.15 * L, fw, fh * 1.2), codes, fh, sticker = look.prints)
            st?.let { els += it }
            y = lineBottom
        }
        val factsLine = d?.let(facts::dayLine).orEmpty()
        val listed = d?.let { facts.dayEntries(it.date) }.orEmpty().take(6)
        var bottom = C.bottom - if (factsLine.isNotBlank()) 2 * L else 0.0
        if (listed.isNotEmpty()) {
            val top = bottom - listed.size * L - L
            listed.forEachIndexed { i, (time, title) ->
                val ly = top + i * L
                els += mono("entry-time", Box(C.x, ly, 14 * s, L), time)
                els += TextEl("entry-line", Box(C.x + 14 * s, ly, C.w - 14 * s, L), title, fonts.body, grid.bodyPt, false, L)
            }
            bottom = top - L
        }
        val top = grid.snapDown(y + 1.5 * L)
        val route = d?.route
        if (route != null && bottom - top > 6 * L) {
            els += RouteEl(Box(C.x, top, C.w, bottom - top), route, labels = true)
        } else if (d != null && d.places.isNotEmpty()) {
            val text = d.places.joinToString(" → ")
            els += secondary("places", C.x, top, C.w, text, maxLines = 4)
        }
        if (factsLine.isNotBlank()) {
            els += RuleEl(Box(C.x, C.bottom - L - 0.2, C.w, 0.0))
            els += mono("facts", Box(C.x, C.bottom - L, C.w, L), factsLine)
        }
    }

    private fun photoFull(page: BookPage, els: MutableList<El>) {
        val p = photo(page, 0, sheet.art, 0.0, captionLines = 0, bleed = true)
        els += p
        val cap = page.slot(0).caption
        if (cap.isNotBlank()) {
            val size = if (fonts.hand) dpt(13.0) else pt(8.0)
            val face = if (fonts.hand) fonts.display else fonts.italic
            val w = (measure.widthMm(cap, face, size, false) + 8 * s).coerceAtMost(C.w * 0.8)
            els += TextEl("tag", Box(C.x, H - 4 * L - 0.6 * L, w, 1.6 * L), cap, face, size, false, 1.6 * L, "center", angle = if (look.tilt) -2.0 else 0.0, italic = !fonts.hand, capOf = 0)
        }
    }

    /** A photo to the edges with its caption in big words over a soft shade at the foot. */
    private fun photoFullCaption(page: BookPage, els: MutableList<El>) {
        els += photo(page, 0, sheet.art, 0.0, captionLines = 0, bleed = true)
        val cap = page.slot(0).caption
        if (cap.isBlank()) return
        val shadeTop = H * 0.62
        els += DecoEl(FreeKind.SHADE, Box(sheet.art.x, shadeTop, sheet.art.w, sheet.art.bottom - shadeTop), 0.0, null, null, 0.0, null)
        val t = display("over", C.x, 0.0, C.w, cap, listOf(26.0, 22.0, 19.0, 16.0), 3, align = if (fonts.hand) "center" else "left")
        els += t.copy(box = t.box.copy(y = grid.y(grid.rows - 4) - t.box.h), capOf = 0)
    }

    private fun photoOne(page: BookPage, els: MutableList<El>, number: Int) {
        val region = Box(C.x, C.y + L, C.w, C.h - 2 * L)
        els += photo(page, 0, region, if (number % 2 == 0) -2.2 else 1.8, alignY = 0.42, captionLines = 2)
        captions(els)
    }

    private fun photoTwo(page: BookPage, els: MutableList<El>, number: Int) {
        val a1 = facts.aspect(page.slot(0).mediaId)
        val a2 = facts.aspect(page.slot(1).mediaId)
        val flip = number % 2 == 0
        if (a1 < 0.95 && a2 < 0.95) {
            val w2 = (C.w - L) / 2
            val drop = if (look.prints) 5 * L else 0.0
            els += photo(page, 0, Box(C.x, C.y + L, w2, C.h - 2 * L - drop), if (flip) 2.0 else -2.2, alignY = 0.2)
            els += photo(page, 1, Box(C.x + w2 + L, C.y + L + drop, w2, C.h - 2 * L - drop), if (flip) -1.8 else 2.4, alignY = 0.2)
        } else {
            val half = rows(((C.h / L).toInt() - 1) / 2)
            val zig = if (look.prints) 0.12 else 0.5
            els += photo(page, 0, Box(C.x, C.y, C.w, half), if (flip) 1.8 else -2.0, alignX = if (flip) 1 - zig else zig, fill = !look.prints)
            els += photo(page, 1, Box(C.x, C.y + half + L, C.w, half), if (flip) -2.4 else 2.2, alignX = if (flip) zig else 1 - zig, fill = !look.prints)
        }
        captions(els)
    }

    private fun photoThree(page: BookPage, els: MutableList<El>, number: Int) {
        val flip = number % 2 == 0
        val topRows = ((C.h / L) * 0.53).roundToInt()
        val top = Box(C.x, C.y, C.w, rows(topRows))
        els += photo(page, 0, top, if (flip) 1.6 else -1.8, fill = !look.prints)
        val y = top.bottom + L
        val w2 = (C.w - L) / 2
        val h = C.bottom - y
        els += photo(page, 1, Box(C.x, y, w2, h), if (flip) -2.6 else 2.4, alignY = 0.15, fill = !look.prints)
        els += photo(page, 2, Box(C.x + w2 + L, y, w2, h), if (flip) 2.2 else -2.8, alignY = 0.35, fill = !look.prints)
        captions(els)
    }

    /** Two photos in two columns, the same height. */
    private fun photoTwoSide(page: BookPage, els: MutableList<El>, number: Int) {
        val flip = number % 2 == 0
        val w2 = (C.w - L) / 2
        val h = C.h - 2 * L
        els += photo(page, 0, Box(C.x, C.y + L, w2, h), if (flip) 1.6 else -1.8, alignY = 0.4, fill = !look.prints)
        els += photo(page, 1, Box(C.x + w2 + L, C.y + L, w2, h), if (flip) -2.0 else 1.8, alignY = 0.4, fill = !look.prints)
        captions(els)
    }

    /** Three photos the same size: in a row on square pages, one above the other on taller ones. */
    private fun photoThreeUp(page: BookPage, els: MutableList<El>, number: Int) {
        val flip = number % 2 == 0
        val tilts = if (flip) listOf(1.4, -1.8, 1.2) else listOf(-1.6, 1.5, -1.2)
        if (spec.format.aspect >= 0.95) {
            val w3 = (C.w - 2 * L) / 3
            val h = C.h - 2 * L
            for (i in 0 until 3) els += photo(page, i, Box(C.x + i * (w3 + L), C.y + L, w3, h), tilts[i], alignY = 0.4, fill = !look.prints)
        } else {
            val rowsEach = ((C.h / L).toInt() - 2) / 3
            val h = rows(rowsEach)
            val zig = if (look.prints) 0.15 else 0.5
            for (i in 0 until 3) {
                val ax = if ((i % 2 == 0) != flip) zig else 1 - zig
                els += photo(page, i, Box(C.x, C.y + i * (h + L), C.w, h), tilts[i], alignX = ax, fill = !look.prints)
            }
        }
        captions(els)
    }

    /** Four photos in a 2 × 2 grid. */
    private fun photoFour(page: BookPage, els: MutableList<El>, number: Int) {
        val flip = number % 2 == 0
        val w2 = (C.w - L) / 2
        val h2 = rows(((C.h / L).toInt() - 1) / 2)
        val tilts = if (flip) listOf(1.5, -1.6, -1.2, 1.8) else listOf(-1.7, 1.4, 1.6, -1.3)
        for (i in 0 until 4) {
            val x = C.x + (i % 2) * (w2 + L)
            val y = C.y + (i / 2) * (h2 + L)
            els += photo(page, i, Box(x, y, w2, h2), tilts[i], fill = !look.prints)
        }
        captions(els)
    }

    /**
     * Where [a]'s photos go on a page: its cells in the type area, a line apart (the same gap as between the photos of
     * the other layouts), in mm from the trim's top-left.
     */
    fun albumCells(a: AlbumLayout): List<Box> = a.place(C.w, C.h, L).map { Box(C.x + it.x, C.y + it.y, it.w, it.h) }

    /**
     * An album page: every photo fills its cell, cropped to the cell's shape (prints keep their white border inside
     * it, upright, with tape on pages of three or fewer). Captions take a line at the foot of the cells (every cell
     * keeps it when one photo has a caption, so rows stay level), or the print's wide bottom border in themes that
     * write on it.
     */
    private fun album(page: BookPage, els: MutableList<El>) {
        val a = page.albumLayout ?: return
        val cells = albumCells(a)
        val inBand = look.prints && look.bottomBorder > 4
        val capLine = !inBand && (0 until a.count).any { page.slot(it).caption.isNotBlank() }
        cells.forEachIndexed { i, cell -> els += albumPhoto(page, i, cell, tape = look.tape && a.count <= 3, capLine = capLine) }
        captions(els)
    }

    private fun albumPhoto(page: BookPage, i: Int, cell: Box, tape: Boolean, capLine: Boolean): PhotoEl {
        val slot = page.slot(i)
        val inBand = look.prints && look.bottomBorder > 4
        val below = capLine && slot.caption.isNotBlank()
        val frame = if (capLine) cell.inset(0.0, 0.0, 0.0, 1.3 * L) else cell
        val b = if (look.prints) min(look.border * s, frame.w * 0.06) else 0.0
        val bb = if (look.prints) min(look.bottomBorder * s, frame.h * 0.18).coerceAtLeast(b) else 0.0
        val image = Box(frame.x + b, frame.y + b, (frame.w - 2 * b).coerceAtLeast(1.0), (frame.h - b - bb).coerceAtLeast(1.0))
        val caption = when {
            inBand -> Box(frame.x, image.bottom, frame.w, frame.bottom - image.bottom)
            below -> Box(cell.x, cell.bottom - L, cell.w, L)
            else -> null
        }
        val tapes = if (!tape || !look.prints) emptyList() else {
            val th = 5.2 * s
            listOf(Tape(frame.w / 2, 0.25 * th, min(15.0 * s * 1.1, frame.w * 0.45), th, if (i % 2 == 0) -4.0 else 5.0))
        }
        return PhotoEl(
            slot = i, mediaId = slot.mediaId, frame = frame, image = image, tilt = 0.0, crop = slot.crop,
            caption = caption, captionText = slot.caption, captionInFrame = inBand, tapes = tapes, bleed = false,
            empty = facts.media(slot.mediaId) == null,
        )
    }

    private fun photoColumnW(): Double = ((C.w - L) * 0.5).let { if (spec.format == BookFormat.A5) (C.w - L) * 0.52 else it }

    private fun photoTextSideFlow(): FlowEl {
        val pw = photoColumnW()
        val x = C.x + pw + L
        return FlowEl(Box(x, C.y, C.right - x, rows(grid.contentRows)), grid.contentRows, 1, C.right - x, grid.gutter)
    }

    /** A tall photo on the left and the text in a column beside it. */
    private fun photoTextSide(page: BookPage, els: MutableList<El>, number: Int) {
        val region = Box(C.x, C.y, photoColumnW(), C.h - 2 * L)
        els += photo(page, 0, region, if (number % 2 == 0) -1.4 else 1.2, alignY = 0.1, fill = !look.prints, captionLines = 2)
        captions(els)
        els += photoTextSideFlow()
    }

    private fun photoTextRows(): Int = ((C.h / L) * 0.46).roundToInt()

    /**
     * The photo's share of a photo-and-text page: the usual 46 %, or more (up to 70 %) when the page's text is short,
     * so a few lines don't sit under a small photo. Text is always poured into the usual box first, so it fits.
     */
    private fun photoTextRows(page: BookPage): Int {
        val base = photoTextRows()
        if (page.paras.isEmpty()) return ((C.h / L) * 0.62).roundToInt()
        val box = photoTextFlow().flowBox
        val flow = Flow(type, measure)
        val f = flow.fill(page.paras, box)
        if (f.count < page.paras.size || f.splitLines != null) return base
        val need = if (box.cols > 1) flow.balancedRows(page.paras, box) else f.rowsUsed
        return (grid.contentRows - need - 2).coerceIn(base, ((C.h / L) * 0.7).roundToInt())
    }

    private fun photoTextFlow(pr: Int = photoTextRows()): FlowEl {
        val top = C.y + rows(pr + 1)
        val rowsLeft = grid.contentRows - pr - 1
        return flowEl(Box(C.x, top, C.w, rows(rowsLeft)), rowsLeft)
    }

    private fun photoText(page: BookPage, els: MutableList<El>, number: Int) {
        val pr = photoTextRows(page)
        val region = Box(C.x, C.y, C.w, rows(pr))
        val p = photo(page, 0, region, if (number % 2 == 0) -1.6 else 1.4, alignY = 0.1, fill = !look.prints && spec.format != BookFormat.A5)
        els += p
        captions(els)
        // The text starts a line under the photo (or its caption), never lower than the usual place, so it always fits.
        val cap = p.caption?.takeIf { p.captionText.isNotBlank() && !p.captionInFrame }
        val below = maxOf(p.frame.bottom + if (p.tilt != 0.0) 0.04 * p.frame.w else 0.0, cap?.bottom ?: 0.0)
        val usual = C.y + rows(pr + 1)
        val top = minOf(grid.snapDown(below + 0.6 * L), usual)
        val rowsLeft = grid.linesIn(C.bottom - top)
        els += flowEl(Box(C.x, top, C.w, rows(rowsLeft)), rowsLeft)
    }

    private fun textFlow(): FlowEl = flowEl(C, grid.contentRows)

    private fun flowEl(box: Box, rows: Int): FlowEl {
        val cols = grid.columns
        val colW = (box.w - (cols - 1) * grid.gutter) / cols
        return FlowEl(box, rows, cols, colW, grid.gutter)
    }

    /** Turns the photos' caption boxes into texts (band captions are drawn by the photo itself). */
    private fun captions(els: MutableList<El>) {
        val photos = els.filterIsInstance<PhotoEl>()
        photos.forEach { p ->
            val box = p.caption ?: return@forEach
            if (!p.captionInFrame && p.captionText.isNotBlank()) els += captionEl(box, p.captionText, if (look.prints) "center" else "left").copy(capOf = p.slot)
        }
    }

    private fun map(page: BookPage, els: MutableList<El>) {
        var y = C.y
        val t = display("page-title", C.x, y, C.w * 0.7, page.title ?: labels.route, listOf(28.0, 24.0), 1, editable = "title")
        els += t
        if (facts.countries.isNotEmpty()) {
            val fh = 5.0 * s
            els += FlagsEl(Box(C.x + C.w * 0.7, y + (t.box.h - fh * 1.2) / 2, C.w * 0.3, fh * 1.2), facts.countries.take(6), fh, sticker = look.prints, align = "right")
        }
        y = t.box.bottom
        val regions = facts.regions
        if (regions.size >= 2 || page.subtitle != null) {
            val st = secondary("places", C.x, y, C.w, page.subtitle ?: regions.joinToString(" → "), editable = "subtitle", maxLines = 2)
            els += st
            y = st.box.bottom
        }
        val total = listOfNotNull(
            facts.km(facts.km).takeIf { facts.km >= 0.05 },
            facts.totalDays.takeIf { it > 0 }?.let { "$it ${labels.days.lowercase()}" },
        ).joinToString(" · ")
        val bottom = C.bottom - if (total.isNotBlank()) 2 * L else 0.0
        val top = grid.snapDown(y + L)
        els += RouteEl(Box(C.x, top, C.w, bottom - top), facts.regionRoute, labels = true, markers = facts.dayEnds, scaleBar = true)
        if (total.isNotBlank()) {
            els += RuleEl(Box(C.x, C.bottom - L - 0.2, C.w, 0.0))
            els += mono("facts", Box(C.x, C.bottom - L, C.w, L), total)
        }
    }

    private fun stats(page: BookPage, els: MutableList<El>) {
        val t = display("page-title", C.x, C.y, C.w, page.title ?: labels.numbers, listOf(28.0, 24.0), 1, editable = "title")
        els += t
        val items = buildList {
            if (facts.totalDays > 0) add(facts.totalDays.toString() to labels.days)
            if (facts.km >= 0.05) add(facts.kmNumber(facts.km) to labels.km)
            if (facts.countries.isNotEmpty()) add(facts.countries.size.toString() to labels.countries)
            add(facts.entries.toString() to labels.entries)
            add(facts.photos.toString() to labels.photos)
            if (facts.stops > 0) add(facts.stops.toString() to labels.stops)
        }
        val cellRows = if (spec.format == BookFormat.Square) 6 else 7
        val colW = (C.w - L) / 2
        val top = t.box.bottom + 2 * L
        items.forEachIndexed { i, (num, label) ->
            val x = C.x + (i % 2) * (colW + L)
            val y = top + (i / 2) * rows(cellRows)
            val n = display("stat", x, y, colW, num, listOf(48.0, 40.0, 34.0), 1, mark = look.prints)
            els += n
            els += mono("stat-label", Box(x, n.box.bottom, colW, L), label)
            if (label == labels.countries) {
                val fh = 4.4 * s
                els += FlagsEl(Box(x, n.box.bottom + L + 0.3 * L, colW, fh * 1.3), facts.countries.take(8), fh, sticker = look.prints)
            }
        }
        var y = top + ((items.size + 1) / 2) * rows(cellRows) + L
        listOfNotNull(
            facts.longestDay?.let { "${labels.longest}: ${labels.day} ${it.number ?: ""} · ${facts.km(it.km)}".replace("  ", " ") },
            facts.photoDay?.takeIf { facts.photos > 1 }?.let { "${labels.mostPhotos}: ${labels.day} ${it.number ?: ""} · ${it.photos}".replace("  ", " ") },
        ).forEach { line ->
            if (y + L <= C.bottom) {
                val e = secondary("note", C.x, y, C.w, line, maxLines = 1)
                els += e
                y = e.box.bottom
            }
        }
    }

    private fun notes(page: BookPage, els: MutableList<El>) {
        els += TextEl("notes-title", Box(C.x, C.y, C.w, 2 * L), page.title ?: labels.notes, fonts.display, dpt(18.0), false, 2 * L, editable = "title", upper = fonts.upper)
        if (!look.paperEverywhere) {
            val pitch = if (spec.paper == BookPaper.Dots) L else L * 1.4
            els += LinesEl(Box(C.x, C.y + 3 * L, C.w, C.h - 3 * L), if (spec.paper == BookPaper.Plain) BookPaper.Lines else spec.paper, pitch)
        }
    }

    private fun back(page: BookPage, els: MutableList<El>) {
        val rb = Box(C.x + C.w * 0.12, grid.y((grid.rows * 0.24).roundToInt()), C.w * 0.76, rows((grid.rows * 0.3).roundToInt()))
        if (!facts.route.isEmpty) els += RouteEl(rb, facts.regionRoute, labels = false)
        var y = grid.snapDown(rb.bottom + 2 * L)
        val t = display("back-title", C.x, y, C.w, page.title ?: facts.trip.title, listOf(24.0, 20.0), 2, align = "center", editable = "title")
        els += t
        y = t.box.bottom
        if (facts.dateRange.isNotBlank()) els += mono("dates", Box(C.x, y, C.w, L), facts.dateRange, align = "center")
        val d = 13.0 * s
        els += StampEl(StampKind.Brand, Box(W / 2 - d / 2, grid.y(grid.rows - 9), d, d), -10.0)
        els += mono("brand", Box(C.x, grid.y(grid.rows - 9) + d + 0.6 * L, C.w, L), labels.brand, align = "center", size = grid.monoPt * 0.9)
    }
}

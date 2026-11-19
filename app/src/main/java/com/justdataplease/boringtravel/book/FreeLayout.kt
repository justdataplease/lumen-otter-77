package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.model.PrintTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Elements placed by hand, for one book's layout: a template's own elements turned into [FreeEl]s one for one (so a
 * page looks exactly the same the moment it is first changed by hand), and [FreeEl]s turned into the [El]s the page's
 * HTML draws. Both directions keep every position and size in mm, so the PDF prints what the editor shows.
 */
class FreeLayout(private val pl: PageLayout) {
    private val fonts get() = pl.fonts
    private val look get() = pl.look
    private val grid get() = pl.grid
    private val facts get() = pl.facts

    // ------------------------------------------------------------------------------------------- template → free

    /** [page]'s template elements as free elements (ids `t0`, `t1`, … in drawing order), as they are drawn now. */
    fun derive(page: BookPage, number: Int): List<FreeEl> {
        val els = pl.templateEls(page, number)
        val photoIds = HashMap<Int, String>()
        els.forEachIndexed { i, e -> if (e is PhotoEl) photoIds.putIfAbsent(e.slot, derivedId(i)) }
        return els.mapIndexed { i, e -> fromEl(derivedId(i), e, page, photoIds) }
    }

    /**
     * Every element the editor shows on [page], bottom first: a free page's own, or the template's (not placed by
     * hand yet) with the hand-placed ones on top. Words have their text from the page and boxes tall enough for it.
     */
    fun elements(page: BookPage, number: Int): List<FreeEl> =
        if (page.free) resolved(page) else derive(page, number) + page.els.map { resolve(it, page) }

    fun resolved(page: BookPage): List<FreeEl> = page.els.map { resolve(it, page) }

    private fun fromEl(id: String, e: El, page: BookPage, photoIds: Map<Int, String>): FreeEl = when (e) {
        is PhotoEl -> FreeEl(
            id, FreeKind.PHOTO, e.frame, e.tilt, derived = true,
            photo = page.slot(e.slot).copy(tilt = null),
            style = e.style ?: when { e.bleed -> BLEED; look.prints -> PRINT; else -> FLAT },
            pad = Pad(e.image.x - e.frame.x, e.image.y - e.frame.y, e.frame.right - e.image.right, e.frame.bottom - e.image.bottom),
            tapes = e.tapes, band = e.captionInFrame,
        )
        is TextEl -> FreeEl(
            id, FreeKind.TEXT, tagBox(e), e.angle, derived = true,
            text = e.text,
            bind = e.editable ?: e.capOf?.let { photoIds[it] }?.let { FreeEl.CAP + it },
            font = token(e.face), pt = e.pt, lh = if (e.pt > 0) e.lineMm / (e.pt * TextFit.PT_MM) else 1.25,
            align = e.align, bold = e.bold, italic = e.italic, upper = e.upper, mark = e.mark, role = e.role, color = e.color,
        )
        is FlowEl -> FreeEl(id, FreeKind.FLOW, e.box.copy(h = e.rows * grid.line), e.angle, derived = true, cols = e.cols)
        is StampEl -> FreeEl(
            id, FreeKind.STAMP, e.box, e.angle, derived = true,
            style = e.kind.name.lowercase(), words = listOf(e.top, e.middle, e.bottom, e.small),
        )
        is RouteEl -> FreeEl(
            id, FreeKind.ROUTE, e.box, e.angle, derived = true,
            src = when (e.route) { facts.coverRoute -> COVER; facts.regionRoute -> TRIP; else -> DAY },
            labels = e.labels, markers = e.markers.isNotEmpty(), scaleBar = e.scaleBar,
        )
        is FlagsEl -> FreeEl(
            id, FreeKind.FLAGS, e.box, e.angle, derived = true,
            codes = e.codes, flagH = e.height, style = if (e.sticker) STICKER else FLAT, align = e.align,
        )
        is RuleEl -> FreeEl(id, FreeKind.RULE, e.box, e.angle, derived = true)
        is LinesEl -> FreeEl(id, FreeKind.LINES, e.box, e.angle, derived = true, style = e.paper.id, pitch = e.pitch)
        is DecoEl -> FreeEl(id, e.kind, e.box, e.angle, derived = true, color = e.color, fill = e.fill, stroke = e.stroke, style = e.style)
    }

    /**
     * Notebook's caption tag turns around its left edge; elements turn around their centre. The same tag, moved so
     * that turning around its centre puts it in the same place.
     */
    private fun tagBox(e: TextEl): Box {
        if (e.role != "tag" || e.angle == 0.0 || !(pl.spec.theme == PrintTheme.Notebook || pl.spec.theme == PrintTheme.Scrapbook)) return e.box
        val a = Math.toRadians(e.angle)
        val vx = -e.box.w / 2 // left edge's middle, from the centre
        val rx = vx * cos(a)
        val ry = vx * sin(a)
        return e.box.offset(vx - rx, -ry)
    }

    // ------------------------------------------------------------------------------------------- free → drawn

    /** [fe] with its words from the page and, for words, a box at least as tall as they need. */
    fun resolve(fe: FreeEl, page: BookPage): FreeEl {
        if (!fe.isText) return fe
        val text = text(fe, page)
        val withText = if (text == fe.text) fe else fe.copy(text = text)
        // A template's own fixed words keep their box; words that can change grow with their text.
        if (fe.derived && fe.bind == null) return withText
        val need = contentHeight(withText)
        return if (need > fe.box.h + 1e-6) withText.copy(box = fe.box.copy(h = need)) else withText
    }

    /** The words [fe] shows: its own, or the page's title or subtitle, or its photo's caption. */
    fun text(fe: FreeEl, page: BookPage): String = when (fe.bind) {
        null -> fe.text
        FreeEl.TITLE -> page.title ?: fe.text
        FreeEl.SUBTITLE -> page.subtitle ?: fe.text
        else -> fe.captionOf?.let { id -> page.el(id)?.photo?.caption } ?: fe.text
    }

    /** Height of [fe]'s words wrapped in its width, whole lines. */
    fun contentHeight(fe: FreeEl): Double {
        val face = face(fe.font)
        val pt = fe.pt.takeIf { it > 0 } ?: DEFAULT_PT
        val text = (if (fe.upper) fe.text.uppercase() else fe.text).ifBlank { "M" }
        val lines = TextFit.lines(text, fe.box.w.coerceAtLeast(1.0), face, pt, fe.bold, pl.measure)
        return lines * lineMm(fe)
    }

    fun lineMm(fe: FreeEl): Double = (fe.pt.takeIf { it > 0 } ?: DEFAULT_PT) * TextFit.PT_MM * fe.lh

    /** The face a font token names: the book's display, body, italic or mono face, or a bundled face by name. */
    fun face(token: String?): Face = when (token) {
        "display" -> fonts.display
        "italic" -> fonts.italic
        "mono" -> fonts.mono
        "body", null -> fonts.body
        else -> Face.entries.firstOrNull { it.name == token } ?: fonts.body
    }

    /** The token for [face]: one of the book's roles when it is one, so it follows the font set. */
    fun token(face: Face): String = when (face) {
        fonts.display -> "display"
        fonts.body -> "body"
        fonts.italic -> "italic"
        fonts.mono -> "mono"
        else -> face.name
    }

    /** Words for [photo]'s caption, under it, in the theme's caption style. */
    fun captionFor(photo: FreeEl, id: String): FreeEl {
        val L = grid.line
        val top = (photo.box.bottom + if (look.prints) 0.45 * L else 0.15 * L).coerceAtMost(pl.spec.format.heightMm - 3 * L)
        val t = pl.captionEl(Box(photo.box.x, top, photo.box.w, L), "", if (look.prints) "center" else "left")
        return FreeEl(
            id, FreeKind.TEXT, t.box, bind = FreeEl.CAP + photo.id, font = token(t.face), pt = t.pt,
            lh = t.lineMm / (t.pt * TextFit.PT_MM), align = t.align, italic = t.italic, role = t.role,
        )
    }

    /** The default border of a print placed by hand: the theme's, or Notebook's in themes without prints. */
    fun printPad(): Pad {
        val s = grid.scale
        val b = (if (look.prints) look.border else 2.3) * s
        val bb = (if (look.prints) look.bottomBorder else 2.3) * s
        return Pad(b, b, b, bb)
    }

    fun flowEl(fe: FreeEl): FlowEl {
        val L = grid.line
        val rows = grid.linesIn(fe.box.h).coerceAtLeast(0)
        var cols = fe.cols.coerceAtLeast(1)
        while (cols > 1 && (fe.box.w - (cols - 1) * grid.gutter) / cols < MIN_COLUMN_MM) cols--
        val colW = ((fe.box.w - (cols - 1) * grid.gutter) / cols).coerceAtLeast(1.0)
        return FlowEl(fe.box.copy(h = rows * L), rows, cols, colW, grid.gutter, fe.rot)
    }

    /** What [fe] draws (null: nothing, like a day's route on a page without a day). */
    fun toEl(fe: FreeEl, page: BookPage): El? = when (fe.kind) {
        FreeKind.PHOTO -> {
            val slot = fe.photo ?: Slot()
            val style = fe.style?.takeIf { it in PHOTO_STYLES } ?: FLAT
            val pad = fe.pad ?: if (style == PRINT) printPad() else Pad.ZERO
            val b = fe.box
            val image = Box(b.x + pad.l, b.y + pad.t, (b.w - pad.l - pad.r).coerceAtLeast(1.0), (b.h - pad.t - pad.b).coerceAtLeast(1.0))
            PhotoEl(
                slot = -1, mediaId = slot.mediaId, frame = b, image = image, tilt = fe.rot, crop = slot.crop,
                caption = if (fe.band) Box(b.x, image.bottom, b.w, (b.bottom - image.bottom).coerceAtLeast(0.0)) else null,
                captionText = slot.caption, captionInFrame = fe.band, tapes = fe.tapes, bleed = style == BLEED,
                empty = facts.media(slot.mediaId) == null, style = style,
            )
        }
        FreeKind.TEXT -> {
            val face = face(fe.font)
            val pt = fe.pt.takeIf { it > 0 } ?: DEFAULT_PT
            TextEl(
                role = fe.role ?: "free", box = fe.box, text = text(fe, page), face = face, pt = pt, bold = fe.bold, lineMm = lineMm(fe),
                align = fe.align, mark = fe.mark, angle = fe.rot, upper = fe.upper, italic = fe.italic, exact = true, color = fe.color,
            )
        }
        FreeKind.FLOW -> flowEl(fe)
        FreeKind.STAMP -> StampEl(
            kind = when (fe.style) { "day" -> StampKind.Day; "postage" -> StampKind.Postage; else -> StampKind.Brand },
            box = fe.box, angle = fe.rot,
            top = fe.words.getOrElse(0) { "" }, middle = fe.words.getOrElse(1) { "" }, bottom = fe.words.getOrElse(2) { "" }, small = fe.words.getOrElse(3) { "" },
        )
        FreeKind.ROUTE -> route(fe.src, page)?.let { r ->
            RouteEl(fe.box, r, fe.labels, if (fe.markers) facts.dayEnds else emptyList(), fe.scaleBar, fe.rot)
        }
        FreeKind.FLAGS -> FlagsEl(fe.box, fe.codes, fe.flagH.takeIf { it > 0 } ?: (fe.box.h / 1.2), fe.style == STICKER, fe.align, fe.rot)
        FreeKind.RULE -> RuleEl(fe.box, fe.rot)
        FreeKind.LINES -> LinesEl(fe.box, BookPaper.of(fe.style), fe.pitch.takeIf { it > 0 } ?: grid.line, fe.rot)
        in FreeKind.DECO -> DecoEl(fe.kind, fe.box, fe.rot, fe.color, fe.fill, fe.stroke, fe.style)
        else -> null
    }

    /** The route a route element draws: the cover's, the whole trip's, or the page's day. */
    fun route(src: String?, page: BookPage) = when (src) {
        COVER -> facts.coverRoute
        TRIP -> facts.regionRoute
        DAY -> facts.day(page.day)?.route
        else -> null
    }

    companion object {
        const val PRINT = "print"
        const val FLAT = "flat"
        const val BLEED = "bleed"
        const val STICKER = "sticker"
        const val COVER = "cover"
        const val TRIP = "trip"
        const val DAY = "day"
        val PHOTO_STYLES = setOf(PRINT, FLAT, BLEED)

        /** Text size of a new text box, pt. */
        const val DEFAULT_PT = 14.0
        private const val MIN_COLUMN_MM = 28.0

        fun derivedId(i: Int): String = "t$i"

        /** A template element's id (not placed by hand yet). */
        fun isDerivedId(id: String): Boolean = id.length > 1 && id[0] == 't' && id.drop(1).all { it.isDigit() }
    }
}

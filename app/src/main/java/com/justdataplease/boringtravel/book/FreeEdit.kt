package com.justdataplease.boringtravel.book

import kotlin.math.min

/**
 * Editing steps for elements placed by hand; each returns a new page or document. A template's own elements (ids
 * `t0`, `t1`, …) are turned into elements the first time one is moved, turned, resized, restyled, reordered or
 * deleted ([freeze]); until then the page stays a template page and the ones placed by hand sit on top of it.
 * [number] is the page's printed number (templates mirror on left-hand pages), from [BookLayout.number].
 */
object FreeEdit {
    /** Nudge step, mm. */
    const val NUDGE = 0.5

    /** How far a copy lands from its original, mm. */
    const val COPY_OFFSET = 5.0

    /** [page] turned free: its template's own elements as drawn now, under the ones placed by hand. Looks the same. */
    fun freeze(page: BookPage, pl: PageLayout, number: Int): BookPage {
        if (page.free) return page
        val derived = pl.free.derive(page, number)
        // Photos the template had no spot for are kept for a template with more spots.
        return page.copy(free = true, els = derived + page.els, slots = page.slots.drop(page.spots))
    }

    /** Whether changing [id] first needs the page turned free (it is one of the template's own elements). */
    fun needsFreeze(page: BookPage, id: String): Boolean = !page.free && page.el(id) == null

    /** [change] applied to element [id] (the page turned free first when it is a template's own). */
    fun change(page: BookPage, pl: PageLayout, number: Int, id: String, change: (FreeEl) -> FreeEl): BookPage {
        val p = if (needsFreeze(page, id)) freeze(page, pl, number) else page
        if (p.el(id) == null) return page
        return p.copy(els = p.els.map { if (it.id == id) change(it) else it })
    }

    /** [e] in box [b]: tape on a print and flag sizes follow the new size. */
    fun withBox(e: FreeEl, b: Box): FreeEl {
        val kx = if (e.box.w > 0) b.w / e.box.w else 1.0
        val ky = if (e.box.h > 0) b.h / e.box.h else 1.0
        return e.copy(
            box = b,
            tapes = if (kx == 1.0) e.tapes else e.tapes.map { it.copy(cx = it.cx * kx) },
            flagH = if (e.kind == FreeKind.FLAGS && e.flagH > 0) e.flagH * ky else e.flagH,
        )
    }

    fun move(page: BookPage, pl: PageLayout, number: Int, id: String, box: Box, rot: Double): BookPage =
        change(page, pl, number, id) { withBox(it, box).copy(rot = BookJson.normalAngle(rot)) }

    fun nudge(page: BookPage, pl: PageLayout, number: Int, id: String, dx: Double, dy: Double): BookPage =
        change(page, pl, number, id) { it.copy(box = it.box.offset(dx, dy)) }

    /** Adds [e] on top of everything. */
    fun add(page: BookPage, e: FreeEl): BookPage = page.copy(els = page.els + e)

    /** Whether [id] can be deleted or copied (the page's flowing text cannot: it is the page's text). */
    fun canRemove(e: FreeEl): Boolean = !e.isFlow

    /** Deletes [id] and the captions that belong to it. The page's flowing text stays. */
    fun delete(page: BookPage, pl: PageLayout, number: Int, id: String): BookPage {
        val el = pl.free.elements(page, number).firstOrNull { it.id == id } ?: return page
        if (!canRemove(el)) return page
        val p = if (needsFreeze(page, id)) freeze(page, pl, number) else page
        return p.copy(els = p.els.filter { it.id != id && it.captionOf != id })
    }

    /**
     * A copy of [id] a little down and to the right, just above it. Words that came from the page (title, caption)
     * are copied as plain words. The page stays as it is otherwise (a template page stays one).
     */
    fun duplicate(page: BookPage, pl: PageLayout, number: Int, id: String, newId: String): BookPage {
        val src = pl.free.elements(page, number).firstOrNull { it.id == id } ?: return page
        if (!canRemove(src)) return page
        val copy = src.copy(id = newId, derived = false, bind = null, hidden = false, box = src.box.offset(COPY_OFFSET, COPY_OFFSET))
        val i = page.els.indexOfFirst { it.id == id }
        val list = page.els.toMutableList()
        if (i >= 0) list.add(i + 1, copy) else list += copy
        return page.copy(els = list)
    }

    /** One step up ([up]) or down in the drawing order. */
    fun order(page: BookPage, pl: PageLayout, number: Int, id: String, up: Boolean): BookPage {
        val p = freeze(page, pl, number)
        val i = p.els.indexOfFirst { it.id == id }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in p.els.indices) return page
        val list = p.els.toMutableList()
        list[i] = p.els[j]
        list[j] = p.els[i]
        return p.copy(els = list)
    }

    /**
     * [id] moved to place [z] in the drawing order (0 = the bottom, the last = on top of everything); the page turns
     * free first when [id] is not where it should be yet. The editor's list shows the same order top first ([Layers]).
     */
    fun moveLayer(page: BookPage, pl: PageLayout, number: Int, id: String, z: Int): BookPage {
        val all = pl.free.elements(page, number)
        val from = all.indexOfFirst { it.id == id }
        if (from < 0) return page
        val to = z.coerceIn(0, all.lastIndex)
        if (to == from) return page
        // Freezing keeps the order and ids of what the editor lists, so the indices stay the same.
        val p = freeze(page, pl, number)
        val i = p.els.indexOfFirst { it.id == id }
        if (i < 0) return page
        val list = p.els.toMutableList()
        val e = list.removeAt(i)
        list.add(to.coerceAtMost(list.size), e)
        return p.copy(els = list)
    }

    /** Whether [e] can be hidden (the page's flowing text cannot: the text poured into it would drop out of the book). */
    fun canHide(e: FreeEl): Boolean = !e.isFlow

    /**
     * Hides or shows [id]. A hidden element keeps its place, size and settings and is listed in the editor, but the
     * page and the PDF leave it out. Hiding one of a template's own elements turns the page free first.
     */
    fun setHidden(page: BookPage, pl: PageLayout, number: Int, id: String, hidden: Boolean): BookPage {
        val el = pl.free.elements(page, number).firstOrNull { it.id == id } ?: return page
        if (!canHide(el) || el.hidden == hidden) return page
        return change(page, pl, number, id) { it.copy(hidden = hidden) }
    }

    /** Hides or shows the page's background (the theme's paper shows while it is hidden). */
    fun setBgHidden(page: BookPage, hidden: Boolean): BookPage {
        val bg = page.bg ?: return page
        return if (bg.hidden == hidden) page else page.copy(bg = bg.copy(hidden = hidden))
    }

    /** Whether [id] can go up or down a step (on a template page everything else counts as one step below). */
    fun canOrder(page: BookPage, pl: PageLayout, number: Int, id: String, up: Boolean): Boolean {
        val list = pl.free.elements(page, number)
        val i = list.indexOfFirst { it.id == id }
        return i >= 0 && if (up) i < list.lastIndex else i > 0
    }

    /**
     * New photo, crop or caption for photo element [id]. A caption for a photo placed by hand gets its own words
     * under the photo (which can then be moved on their own); a print with a wide border writes it on the border.
     */
    fun setPhoto(page: BookPage, pl: PageLayout, number: Int, id: String, slot: Slot): BookPage {
        var p = change(page, pl, number, id) { it.copy(photo = slot.copy(tilt = null)) }
        val ph = p.el(id) ?: return p
        if (slot.caption.isNotBlank() && !ph.band && p.els.none { it.captionOf == id }) {
            val cap = pl.free.captionFor(ph, "$id-cap")
            val i = p.els.indexOfFirst { it.id == id }
            val list = p.els.toMutableList()
            list.add(i + 1, cap)
            p = p.copy(els = list)
        }
        return p
    }

    fun setBg(page: BookPage, bg: PageBg?): BookPage = page.copy(bg = bg)

    /**
     * Another template for [page] ([album]: the arrangement of an [Template.Album] page). Its photos go into the new
     * spots in their order, photos first and empty spots after them; those beyond the spots are kept for a layout with
     * more ([BookPage.spots]). Its text is kept too (shown again if switched back). A free page goes back to a template:
     * every photo on it, in drawing order, fills the template's spots, its text flows in the template's text area, and
     * what was added by hand (words, decorations) stays where it is, on top.
     */
    fun applyTemplate(page: BookPage, t: Template, album: String? = page.album): BookPage {
        val start = if (t == Template.Text) page.start else true
        val albumId = if (t == Template.Album) album else null
        if (!page.free) return page.copy(templateId = t.id, start = start, album = albumId, slots = filledFirst(page.slots))
        val photos = page.els.filter { it.isPhoto }
        val moved = photos.map { it.id }.toSet()
        val slots = filledFirst(photos.map { it.photo ?: Slot() } + page.slots)
        val keep = page.els.filter { !it.derived && !it.isPhoto && !it.isFlow && it.captionOf !in moved }
        return page.copy(templateId = t.id, start = start, free = false, slots = slots, els = keep, album = albumId)
    }

    /** [slots] with the photos first, in their order, and the empty spots after them. */
    fun filledFirst(slots: List<Slot>): List<Slot> = slots.filter { it.mediaId != null } + slots.filter { it.mediaId == null }

    /**
     * [from]'s layout on the pages [to]: its template and background; if it was arranged by hand, the template's own
     * elements go where they are on [from] (photos and words stay each page's own). Covers are left alone.
     */
    fun copyLayout(doc: BookDoc, from: String, to: Collection<String>, pl: PageLayout, number: (String) -> Int): BookDoc {
        val src = doc.page(from) ?: return doc
        val t = src.template ?: return doc
        var d = doc
        for (id in to) {
            if (id == from) continue
            val target = d.page(id) ?: continue
            if (target.template == Template.Cover || target.template == Template.BackCover) continue
            var p = applyTemplate(target, t, src.album).copy(bg = src.bg)
            if (src.free) {
                p = freeze(p, pl, number(id))
                val byId = src.els.filter { it.derived }.associateBy { it.id }
                p = p.copy(els = p.els.map { e -> byId[e.id]?.takeIf { it.kind == e.kind }?.let { s -> withBox(e, s.box).copy(rot = s.rot) } ?: e })
            }
            val done = p
            d = BookEdit.update(d, id) { done }
        }
        return d
    }

    /** A copy of page [pageId] right after it, with new ids. */
    fun duplicatePage(doc: BookDoc, pageId: String, newId: () -> String): BookDoc {
        val i = doc.indexOf(pageId)
        if (i < 0) return doc
        val p = doc.pages[i]
        val ids = p.els.associate { it.id to if (FreeLayout.isDerivedId(it.id)) it.id else newId() }
        val els = p.els.map { e ->
            e.copy(id = ids.getValue(e.id), bind = e.captionOf?.let { c -> FreeEl.CAP + (ids[c] ?: c) } ?: e.bind)
        }
        return BookEdit.insert(doc, i + 1, p.copy(id = newId(), start = true, els = els))
    }

    /**
     * Elements placed by hand moved to a new page size: centres follow the page, sizes and type grow or shrink with
     * its shorter side; a full-bleed photo still covers the page.
     */
    fun rescale(doc: BookDoc, from: BookFormat, to: BookFormat): BookDoc {
        if (from == to) return doc
        val sx = to.widthMm / from.widthMm
        val sy = to.heightMm / from.heightMm
        val k = min(sx, sy)
        fun scale(e: FreeEl): FreeEl {
            if (e.isPhoto && e.style == FreeLayout.BLEED) return e.copy(box = Box(e.box.x * sx, e.box.y * sy, e.box.w * sx, e.box.h * sy))
            val w = e.box.w * k
            val h = e.box.h * k
            return e.copy(
                box = Box(e.box.cx * sx - w / 2, e.box.cy * sy - h / 2, w, h),
                pt = e.pt * k, stroke = e.stroke * k, flagH = e.flagH * k, pitch = e.pitch * k,
                pad = e.pad?.let { Pad(it.l * k, it.t * k, it.r * k, it.b * k) },
                tapes = e.tapes.map { Tape(it.cx * k, it.cy * k, it.w * k, it.h * k, it.angle) },
            )
        }
        return doc.copy(pages = doc.pages.map { p -> if (p.els.isEmpty()) p else p.copy(els = p.els.map(::scale)) })
    }

    // -------------------------------------------------------------------------------------------- new elements

    /** Washi colours offered for tape (the first suits the theme). */
    fun tapeColors(pl: PageLayout): List<String> = listOf(
        if (pl.look.tape && pl.spec.theme == com.justdataplease.boringtravel.model.PrintTheme.Scrapbook) "#F2DE6B" else "#E6D7A8",
        "#F2DE6B", "#F3B7A6", "#A9CFC0", "#B7C6E6", "#D9C2E6",
    ).distinct()

    /** Colours for words and shapes: the theme's own, then a few that go with every theme. */
    fun palette(pl: PageLayout): List<String> = (
        listOf(pl.look.ink, pl.look.muted, pl.look.red, pl.look.route, pl.look.highlighter, pl.look.paperColor) +
            listOf("#1A1A1A", "#FFFFFF", "#2F6E9C", "#3E7D4F", "#D9A520", "#E2725B")
        ).mapNotNull(FreeValues::color).distinct()

    private fun centred(pl: PageLayout, w: Double, h: Double): Box {
        val c = pl.grid.content
        return Box(c.cx - w / 2, c.cy - h / 2, w, h)
    }

    fun newText(pl: PageLayout, id: String): FreeEl {
        val s = pl.grid.scale
        val pt = 20.0 * s * pl.fonts.displayScale
        val w = pl.grid.content.w * 0.7
        val e = FreeEl(id, FreeKind.TEXT, centred(pl, w, pt * TextFit.PT_MM * 1.25), font = "display", pt = pt, lh = 1.25, align = "center", bold = pl.fonts.displayWeight >= 600)
        return e.copy(box = e.box.copy(h = pl.free.contentHeight(e)))
    }

    fun newPhoto(pl: PageLayout, id: String, mediaId: String): FreeEl {
        val c = pl.grid.content
        val aspect = pl.facts.aspect(mediaId)
        var w = c.w * 0.62
        var h = w / aspect
        if (h > c.h * 0.55) { h = c.h * 0.55; w = h * aspect }
        val print = pl.look.prints
        val pad = if (print) pl.free.printPad() else Pad.ZERO
        val fw = w + pad.l + pad.r
        val fh = h + pad.t + pad.b
        val s = pl.grid.scale
        val tapes = if (pl.look.tape) listOf(Tape(fw / 2, 0.25 * 5.2 * s, 15.0 * s * 1.1, 5.2 * s, -4.0)) else emptyList()
        return FreeEl(
            id, FreeKind.PHOTO, centred(pl, fw, fh), if (pl.look.tilt) -1.5 else 0.0,
            photo = Slot(mediaId), style = if (print) FreeLayout.PRINT else FreeLayout.FLAT, pad = pad, tapes = tapes,
        )
    }

    fun newTape(pl: PageLayout, id: String): FreeEl {
        val s = pl.grid.scale
        return FreeEl(id, FreeKind.TAPE, centred(pl, 34.0 * s, 9.0 * s), -6.0, color = tapeColors(pl).first())
    }

    fun newCorners(pl: PageLayout, id: String): FreeEl {
        val w = pl.grid.content.w * 0.55
        return FreeEl(id, FreeKind.CORNERS, centred(pl, w, w * 0.72), color = "#2B2B2B")
    }

    fun newStamp(pl: PageLayout, id: String): FreeEl {
        val d = 24.0 * pl.grid.scale
        return FreeEl(id, FreeKind.STAMP, centred(pl, d, d), -10.0, style = "brand")
    }

    fun newFlag(pl: PageLayout, id: String, code: String): FreeEl {
        val fh = 7.0 * pl.grid.scale
        return FreeEl(
            id, FreeKind.FLAGS, centred(pl, fh * 1.5 + 3.0, fh * 1.25), codes = listOf(code.uppercase().take(3)), flagH = fh,
            style = if (pl.look.prints) FreeLayout.STICKER else FreeLayout.FLAT, align = "center",
        )
    }

    fun newRoute(pl: PageLayout, id: String, src: String): FreeEl {
        val w = pl.grid.content.w * 0.8
        return FreeEl(id, FreeKind.ROUTE, centred(pl, w, w * 0.62), src = src, labels = true)
    }

    fun newShape(pl: PageLayout, id: String, oval: Boolean): FreeEl {
        val s = pl.grid.scale
        val w = 40.0 * s
        return FreeEl(
            id, FreeKind.SHAPE, centred(pl, w, if (oval) w else w * 0.7), style = if (oval) "oval" else "rect",
            fill = FreeValues.color(pl.look.highlighter), color = FreeValues.color(pl.look.ink), stroke = 0.0,
        )
    }

    fun newLine(pl: PageLayout, id: String): FreeEl =
        FreeEl(id, FreeKind.LINE, centred(pl, pl.grid.content.w * 0.6, 4.0), color = FreeValues.color(pl.look.ink), stroke = 0.6)
}

/** Undo and redo of whole states, [max] steps back. */
class EditHistory<T>(private val max: Int = 30) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Remembers [before], the state a change starts from; a new change forgets what could be redone. */
    fun push(before: T) {
        undo.addLast(before)
        while (undo.size > max) undo.removeFirst()
        redo.clear()
    }

    /** Forgets the last [push] (the change turned out to change nothing). */
    fun dropLast() {
        undo.removeLastOrNull()
    }

    /** The state before the last change, remembering [current] for redo; null when there is none. */
    fun undo(current: T): T? {
        val prev = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        return prev
    }

    fun redo(current: T): T? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        return next
    }
}

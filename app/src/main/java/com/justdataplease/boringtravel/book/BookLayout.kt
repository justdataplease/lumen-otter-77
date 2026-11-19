package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.export.BookDay
import com.justdataplease.boringtravel.export.BookEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ln

/**
 * Lays a trip out as a book and keeps the text fitted as it is edited.
 *
 * [auto] makes the first layout: cover, title page, the route, then per day an opener followed by each entry (its
 * first photo with its text, the text running on to text pages, its other photos in groups of one to three) and the
 * day's loose photos, then the numbers page and the back cover. [reflow] pours each run of text pages again after an
 * edit or a change of format or fonts; [printed] is what goes to paper (hidden entries left out, padded to a multiple
 * of four with notes pages before the back cover).
 */
class BookLayout(
    val spec: BookSpec,
    val facts: BookFacts,
    val measure: TextMeasure = TextFit.Estimate,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val pages = PageLayout(spec, facts, measure)
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun auto(): BookDoc {
        val out = ArrayList<BookPage>()
        out += BookPage(newId(), Template.Cover.id, slots = listOf(Slot(coverPhoto())))
        out += BookPage(newId(), Template.TitlePage.id)
        val route = facts.route
        if (!route.planned && (route.line.size >= 2 || route.places.size >= 2)) out += BookPage(newId(), Template.Map.id)
        val used = HashSet<String>()
        facts.bookDays.forEach { day -> out += dayPages(day, used) }
        out += BookPage(newId(), Template.Stats.id)
        out += BookPage(newId(), Template.BackCover.id)
        return reflow(BookDoc(pages = out, paper = spec.paper))
    }

    /** The trip's cover photo, else its first landscape photo, else any photo. */
    private fun coverPhoto(): String? {
        facts.trip.coverMediaId?.let { id -> if (facts.media(id) != null) return id }
        val all = facts.bookDays.flatMap { it.photos }
        return (all.firstOrNull { it.width > it.height } ?: all.firstOrNull())?.id
    }

    /** An entry's photos as it shows them: a single photo, or a photo layout's photos kept together. */
    private sealed interface Shot
    private data class Pic(val id: String, val caption: String) : Shot
    private data class LayoutShot(val layoutId: String, val pics: List<Pic>) : Shot

    /** [doc]'s photos in their order that the trip still has and the book hasn't placed yet ([used]). */
    private fun shots(doc: EntryDoc, used: MutableSet<String>): List<Shot> = doc.blocks.mapNotNull { b ->
        when (b) {
            is Block.Photo -> b.mediaId.takeIf { facts.media(it) != null && used.add(it) }?.let { Pic(it, b.caption) }
            is Block.Album -> b.mediaIds.filter { facts.media(it) != null && used.add(it) }.map { Pic(it, "") }
                .takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it.single() else LayoutShot(b.layoutId, it) }
            else -> null
        }
    }

    private fun dayPages(day: BookDay, used: MutableSet<String>): List<BookPage> {
        val out = ArrayList<BookPage>()
        out += BookPage(newId(), Template.DayOpener.id, day = day.date)
        var textChain = -1 // index in [out] of a text page that text-only entries of this day can join
        // Photos of the day that belong to no entry: a text-only entry takes the nearest in time, so its words have a
        // picture beside them; the rest close the day.
        val looseLeft = day.loosePhotos.filter { it.id !in used }.toMutableList()
        for (be in day.entries) {
            val id = be.entry.id
            val paras = entryParas(be)
            val shots = shots(be.doc, used)
            val hasBody = paras.any { it.kind != ParaKind.TITLE && it.kind != ParaKind.META }
            if (shots.isEmpty() && hasBody && looseLeft.isNotEmpty()) {
                val near = looseLeft.minBy { abs((it.takenAt ?: it.createdAt) - be.entry.occurredAt) }
                looseLeft.remove(near)
                used += near.id
                textChain = -1
                out += BookPage(newId(), Template.PhotoText.id, slots = listOf(Slot(near.id)), paras = paras, entryId = id, day = day.date)
                continue
            }
            if (shots.isEmpty()) {
                if (paras.isEmpty()) continue
                if (textChain >= 0 && textChain == out.lastIndex) {
                    out[textChain] = out[textChain].copy(paras = out[textChain].paras + paras)
                } else {
                    out += BookPage(newId(), Template.Text.id, paras = paras, entryId = id, day = day.date)
                    textChain = out.lastIndex
                }
                continue
            }
            // The entry's first single photo sits with its text; a photo layout stays whole on pages of its own, so
            // with layouts only the text runs as a text-only entry's does (on the day's text page when one is open).
            val first = shots.firstOrNull { it is Pic } as Pic?
            when {
                paras.isEmpty() -> Unit
                first != null -> out += BookPage(newId(), Template.PhotoText.id, slots = listOf(Slot(first.id, caption = first.caption)), paras = paras, entryId = id, day = day.date)
                textChain >= 0 && textChain == out.lastIndex -> out[textChain] = out[textChain].copy(paras = out[textChain].paras + paras)
                else -> out += BookPage(newId(), Template.Text.id, paras = paras, entryId = id, day = day.date)
            }
            textChain = -1
            out += shotPages(if (paras.isEmpty()) shots else shots - setOfNotNull(first), id, day)
        }
        val loose = looseLeft.filter { used.add(it.id) }.map { Pic(it.id, "") }
        out += photoPages(loose, null, day)
        return out
    }

    /** [shots] in order: single photos in groups ([photoPages]), each photo layout on album pages in its arrangement. */
    private fun shotPages(shots: List<Shot>, entryId: String?, day: BookDay): List<BookPage> {
        val out = ArrayList<BookPage>()
        val run = ArrayList<Pic>()
        fun flush() {
            out += photoPages(run.toList(), entryId, day)
            run.clear()
        }
        for (s in shots) when (s) {
            is Pic -> run += s
            is LayoutShot -> {
                flush()
                out += AlbumEdit.pages(s.pics.map { Slot(it.id) }, AlbumLayouts.of(s.layoutId), day.date, entryId, newId)
            }
        }
        flush()
        return out
    }

    /** Photos in groups: 1 → a full page or photo and caption, 2, 3; never a lone photo after a group when it can pair. */
    private fun photoPages(pics: List<Pic>, entryId: String?, day: BookDay): List<BookPage> {
        val groups = ArrayList<List<Pic>>()
        var rest = pics
        while (rest.isNotEmpty()) {
            val n = when (rest.size) { 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 2; else -> 3 }
            groups += rest.take(n)
            rest = rest.drop(n)
        }
        return groups.map { g ->
            val t = when (g.size) {
                1 -> if (fitsFullPage(g[0].id)) Template.PhotoFull else Template.Photo
                2 -> Template.PhotoTwo
                else -> Template.PhotoThree
            }
            BookPage(newId(), t.id, slots = g.map { Slot(it.id, caption = it.caption) }, entryId = entryId, day = day.date)
        }
    }

    /** A photo close to the page's own shape fills the page well. */
    private fun fitsFullPage(id: String): Boolean = abs(ln(facts.aspect(id) / spec.format.aspect)) < 0.22

    /** An entry as the book's paragraphs: title, its time and place line, then its text (photos are placed apart). */
    fun entryParas(be: BookEntry): List<Para> {
        val e = be.entry
        val id = e.id
        val out = ArrayList<Para>()
        if (e.title.isNotBlank()) out += Para(ParaKind.TITLE, RichHtml.escape(e.title.trim()), id)
        val zone = runCatching { ZoneId.of(e.zoneId) }.getOrDefault(facts.book.zone)
        val meta = listOf(hhmm.format(Instant.ofEpochMilli(e.occurredAt).atZone(zone)), e.placeName.trim()).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) out += Para(ParaKind.META, RichHtml.escape(meta), id)
        for (b in be.doc.blocks) when (b) {
            is Block.Paragraph -> {
                if (RichHtml.toPlainText(b.html).isBlank()) continue
                val kind = when (b.list) { ListStyle.Bullet -> ParaKind.BULLET; ListStyle.Number -> ParaKind.NUMBER; null -> ParaKind.P }
                out += Para(kind, RichHtml.sanitize(b.html), id)
            }
            is Block.Heading -> if (b.text.isNotBlank()) out += Para(ParaKind.HEADING, RichHtml.escape(b.text), id)
            is Block.Quote -> if (RichHtml.toPlainText(b.html).isNotBlank()) out += Para(ParaKind.QUOTE, RichHtml.sanitize(b.html), id)
            is Block.Checklist -> b.items.filter { it.text.isNotBlank() }.forEach { out += Para(if (it.done) ParaKind.DONE else ParaKind.CHECK, RichHtml.escape(it.text), id) }
            is Block.Place -> if (b.name.isNotBlank()) out += Para(ParaKind.PLACE, RichHtml.escape(b.name), id)
            is Block.Photo, is Block.Album, Block.Divider, Block.PageBreak, is Block.Unknown -> Unit
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------- text

    /**
     * Pours every run of text pages again (or only the run holding [onlyPage]): a run is a page with text that starts
     * one ([BookPage.start]) and the text pages that continue it. Pages are added or dropped at the end of a run as
     * the text needs; the run's first page always stays.
     */
    fun reflow(doc: BookDoc, onlyPage: String? = null): BookDoc {
        val flow = Flow(pages.type, measure, doc.hidden)
        val list = normalise(doc.pages)
        val out = ArrayList<BookPage>(list.size + 4)
        var i = 0
        while (i < list.size) {
            val p = list[i]
            if (p.template?.text != true) { out += p; i++; continue }
            var j = i + 1
            while (j < list.size && list[j].templateId == Template.Text.id && !list[j].start) j++
            val run = list.subList(i, j)
            if (onlyPage != null && run.none { it.id == onlyPage }) out += run else out += pour(run, flow)
            i = j
        }
        return doc.copy(pages = out)
    }

    /** A text page that continues nothing (its page before isn't a text page) starts a run of its own. */
    private fun normalise(pages: List<BookPage>): List<BookPage> = pages.mapIndexed { i, p ->
        if (p.templateId == Template.Text.id && !p.start && (i == 0 || pages[i - 1].template?.text != true)) p.copy(start = true) else p
    }

    private fun pour(run: List<BookPage>, flow: Flow): List<BookPage> {
        var rest = joinSplits(run.flatMap { it.paras })
        val result = ArrayList<BookPage>()
        var k = 0
        val head = run.first()
        while (true) {
            val page = run.getOrNull(k)?.let { if (k > 0) it.copy(start = false) else it }
                ?: BookPage(newId(), Template.Text.id, entryId = head.entryId, day = head.day, start = false)
            val box = pages.flowBox(page) ?: break
            val fill = flow.fill(rest, box)
            var onPage = rest.take(fill.count)
            var remaining = rest.drop(fill.count)
            val split = fill.splitLines
            if (split != null && remaining.isNotEmpty()) {
                val p = remaining.first()
                val (a, b) = flow.split(p, box.colWidth, flow.indented(p, onPage.lastOrNull(flow::visible)), split)
                onPage = onPage + a
                remaining = listOf(b) + remaining.drop(1)
            } else if (onPage.none(flow::visible) && remaining.any(flow::visible) && k > 0) {
                // Nothing fits on an empty page (a single huge block): place it anyway rather than loop.
                onPage = onPage + remaining.first()
                remaining = remaining.drop(1)
            }
            result += page.copy(paras = onPage)
            rest = remaining
            k++
            if (rest.none(flow::visible) || k > MAX_RUN) {
                if (rest.isNotEmpty()) result[result.lastIndex] = result.last().let { it.copy(paras = it.paras + rest) }
                break
            }
        }
        return result
    }

    /** Joins the halves of paragraphs split across pages ([Para.cont]) back into one. */
    fun joinSplits(paras: List<Para>): List<Para> {
        val out = ArrayList<Para>(paras.size)
        for (p in paras) {
            val last = out.lastOrNull()
            if (p.cont && last != null && last.kind == p.kind && last.entry == p.entry) {
                out[out.lastIndex] = last.copy(html = HtmlText.join(last.html, p.html))
            } else {
                out += p
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------- printing

    /** Whether [p] prints: pages of hidden entries don't, nor text pages whose text is all hidden. */
    fun isVisible(p: BookPage, doc: BookDoc): Boolean {
        val hidden = doc.hidden
        if (hidden.isEmpty()) return true
        if (p.template == Template.Text) return p.paras.isEmpty() || p.paras.any { it.entry == null || it.entry !in hidden }
        return p.entryId == null || p.entryId !in hidden
    }

    /** The pages as printed: visible ones, plus notes pages before the back cover up to a multiple of four. */
    fun printed(doc: BookDoc): List<BookPage> = padded(doc.pages.filter { isVisible(it, doc) }, doc.padTo4)

    /** Page [pageId]'s printed number (its place in the book when it is hidden). */
    fun number(doc: BookDoc, pageId: String): Int {
        val i = printed(doc).indexOfFirst { it.id == pageId }
        return if (i >= 0) i + 1 else doc.indexOf(pageId) + 1
    }

    companion object {
        private const val MAX_RUN = 400

        /** Adds notes pages before a final back cover (or at the end) so the count is a multiple of four. */
        fun padded(visible: List<BookPage>, padTo4: Boolean): List<BookPage> {
            if (!padTo4) return visible
            val pad = padding(visible.size)
            if (pad == 0) return visible
            val notes = List(pad) { BookPage("pad-${it + 1}", Template.Notes.id) }
            return if (visible.lastOrNull()?.template == Template.BackCover) visible.dropLast(1) + notes + visible.last() else visible + notes
        }

        /** Notes pages needed to reach a multiple of four. */
        fun padding(pages: Int): Int = (4 - pages % 4) % 4
    }
}

/** Editing steps on a [BookDoc]; each returns a new document. Text runs are poured again by [BookLayout.reflow]. */
object BookEdit {
    fun move(doc: BookDoc, from: Int, to: Int): BookDoc {
        if (from !in doc.pages.indices || to !in doc.pages.indices || from == to) return doc
        val before = doc.pages
        val list = before.toMutableList()
        val p = list.removeAt(from)
        list.add(to, p)
        return doc.copy(pages = restart(before, list))
    }

    fun delete(doc: BookDoc, pageId: String): BookDoc {
        val before = doc.pages
        val list = before.filter { it.id != pageId }
        return doc.copy(pages = restart(before, list))
    }

    fun insert(doc: BookDoc, at: Int, page: BookPage): BookDoc {
        val list = doc.pages.toMutableList()
        list.add(at.coerceIn(0, list.size), page)
        return doc.copy(pages = list)
    }

    fun update(doc: BookDoc, pageId: String, change: (BookPage) -> BookPage): BookDoc =
        doc.copy(pages = doc.pages.map { if (it.id == pageId) change(it) else it })

    /**
     * Another template for a page; its photos and text are kept (shown again if switched back). A page arranged by
     * hand is laid out by the template again ([FreeEdit.applyTemplate]).
     */
    fun template(doc: BookDoc, pageId: String, t: Template, album: String? = null): BookDoc =
        update(doc, pageId) { p -> FreeEdit.applyTemplate(p, t, album ?: p.album) }

    fun hideEntry(doc: BookDoc, entryId: String, hide: Boolean): BookDoc =
        doc.copy(hidden = if (hide) doc.hidden + entryId else doc.hidden - entryId)

    /**
     * New text for the paragraphs of [entry] on one page (blank lines between paragraphs). Unchanged paragraphs keep
     * their formatting; new or edited ones become plain paragraphs. [title] null leaves the title as it is.
     */
    fun setText(doc: BookDoc, pageId: String, entry: String?, title: String?, text: String): BookDoc = update(doc, pageId) { p ->
        val mine = p.paras.filter { it.entry == entry }
        val others = p.paras.filter { it.entry != entry }
        val firstIndex = p.paras.indexOfFirst { it.entry == entry }.let { if (it < 0) p.paras.size else it }
        val oldTitle = mine.firstOrNull { it.kind == ParaKind.TITLE }
        val meta = mine.filter { it.kind == ParaKind.META }
        val body = mine.filter { it.kind != ParaKind.TITLE && it.kind != ParaKind.META }
        val byText = body.associateBy { RichHtml.toPlainText(it.html).trim() }
        val pieces = text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val newBody = pieces.mapIndexed { i, piece ->
            byText[piece]?.let { if (i == 0) it else it.copy(cont = false) }
                ?: Para(ParaKind.P, RichHtml.escape(piece).replace("\n", "<br>"), entry, cont = i == 0 && body.firstOrNull()?.cont == true)
        }
        val t = when {
            title == null -> listOfNotNull(oldTitle)
            title.isBlank() -> emptyList()
            else -> listOf(Para(ParaKind.TITLE, RichHtml.escape(title.trim()), entry))
        }
        val replaced = t + meta + newBody
        val list = others.toMutableList()
        list.addAll(firstIndex.coerceAtMost(list.size), replaced)
        p.copy(paras = list)
    }

    /** The plain text of [entry]'s body on a page, paragraphs apart by blank lines (what the text editor shows). */
    fun textOf(p: BookPage, entry: String?): String = p.paras
        .filter { it.entry == entry && it.kind != ParaKind.TITLE && it.kind != ParaKind.META }
        .joinToString("\n\n") { RichHtml.toPlainText(it.html).trim() }

    fun titleOf(p: BookPage, entry: String?): String? =
        p.paras.firstOrNull { it.entry == entry && it.kind == ParaKind.TITLE }?.let { RichHtml.toPlainText(it.html) }

    /** Entries with text on a page, in order (null = text typed into the book itself). */
    fun entriesOn(p: BookPage): List<String?> = p.paras.map { it.entry }.distinct()

    /** A text page that lost the page it continued (moved or deleted around it) starts its own run. */
    private fun restart(before: List<BookPage>, after: List<BookPage>): List<BookPage> {
        val prevBefore = HashMap<String, String?>()
        before.forEachIndexed { i, p -> prevBefore[p.id] = before.getOrNull(i - 1)?.id }
        return after.mapIndexed { i, p ->
            if (p.templateId == Template.Text.id && !p.start && after.getOrNull(i - 1)?.id != prevBefore[p.id]) p.copy(start = true) else p
        }
    }

    /** A fresh page of [t] (photos chosen later); [album] is an album page's arrangement. */
    fun blank(t: Template, id: String, day: java.time.LocalDate? = null, album: String? = null): BookPage =
        BookPage(id, t.id, day = day, album = album.takeIf { t == Template.Album }).let { it.copy(slots = List(it.spots) { Slot() }) }
}

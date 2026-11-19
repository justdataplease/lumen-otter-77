package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.album.AlbumLayout
import com.justdataplease.boringtravel.album.AlbumLayouts
import java.time.LocalDate
import kotlin.math.min

/**
 * Album pages in the book: a page given an album arrangement ([AlbumLayouts]), photos added to a page's empty spots,
 * the photos a layout has no spot for moved to pages of their own, and runs of album pages made from a set of photos.
 * Pure; each step returns a new page or document (one undo step in the editor).
 */
object AlbumEdit {
    /**
     * [page] in album arrangement [layoutId]: its photos fill the spots in their order. Photos beyond the spots stay
     * with the page, unseen ([extras]), until it gets more spots or they go to a page of their own ([extrasToNewPages]).
     */
    fun apply(page: BookPage, layoutId: String): BookPage = FreeEdit.applyTemplate(page, Template.Album, layoutId)

    fun apply(doc: BookDoc, pageId: String, layoutId: String): BookDoc = BookEdit.update(doc, pageId) { apply(it, layoutId) }

    /** Photos kept with [page] that its layout has no spot for (they are not on the page). */
    fun extras(page: BookPage): List<Slot> =
        (if (page.free) page.slots else page.slots.drop(page.spots)).filter { it.mediaId != null }

    /**
     * [page]'s [extras] moved to new pages right after it (after the text that runs on from it): its own album
     * arrangement as long as it fills up, then the arrangement for what is left ([pages]). Nothing else changes.
     */
    fun extrasToNewPages(doc: BookDoc, pageId: String, newId: () -> String): BookDoc {
        val i = doc.indexOf(pageId)
        if (i < 0) return doc
        val page = doc.pages[i]
        val rest = extras(page)
        if (rest.isEmpty()) return doc
        val kept = if (page.free) emptyList() else page.slots.take(page.spots)
        val added = pages(rest, page.albumLayout, page.day, page.entryId, newId)
        val list = doc.pages.toMutableList()
        list[i] = page.copy(slots = kept)
        return insert(doc.copy(pages = list), pageId, added)
    }

    /**
     * Album pages for [slots] in their order: [first]'s arrangement as long as it fills up, then the arrangement for
     * exactly what is left (or the biggest smaller one, and so on: [AlbumLayouts.split]), so no page has a gap.
     */
    fun pages(slots: List<Slot>, first: AlbumLayout?, day: LocalDate?, entryId: String?, newId: () -> String): List<BookPage> {
        var rest = slots
        return AlbumLayouts.split(slots.size, first).map { l ->
            val take = rest.take(l.count)
            rest = rest.drop(l.count)
            BookPage(newId(), Template.Album.id, slots = take, day = day, entryId = entryId, album = l.id)
        }
    }

    /** [added] put after page [afterId] and the text pages that continue it (at the end when it isn't there). */
    fun insert(doc: BookDoc, afterId: String?, added: List<BookPage>): BookDoc {
        if (added.isEmpty()) return doc
        val list = doc.pages.toMutableList()
        list.addAll(after(doc, afterId), added)
        return doc.copy(pages = list)
    }

    /** Where pages go after page [pageId]: past the text pages that continue its text. */
    fun after(doc: BookDoc, pageId: String?): Int {
        val i = pageId?.let(doc::indexOf) ?: -1
        if (i < 0) return doc.pages.size
        var j = i + 1
        while (j < doc.pages.size && doc.pages[j].templateId == Template.Text.id && !doc.pages[j].start) j++
        return j
    }

    /** Where photo pages added from the overview go: before the back cover. */
    fun beforeBack(doc: BookDoc): Int = doc.pages.indexOfLast { it.template == Template.BackCover }.takeIf { it >= 0 } ?: doc.pages.size

    /** [added] put at [index]. */
    fun insertAt(doc: BookDoc, index: Int, added: List<BookPage>): BookDoc {
        val list = doc.pages.toMutableList()
        list.addAll(index.coerceIn(0, list.size), added)
        return doc.copy(pages = list)
    }

    // ---------------------------------------------------------------------------------------------- adding photos

    /** What [fill] did: the page with photos in its empty spots, and the photos that found none. */
    data class Filled(val page: BookPage, val rest: List<String>)

    /**
     * The element ids (as the editor lists them: `t0`, `t1`, … for a template's own) of [page]'s empty photo spots, in
     * drawing order. [has] tells whether the trip still has a photo; a spot whose photo is gone counts as empty.
     */
    fun emptySpots(page: BookPage, pl: PageLayout, number: Int, has: (String?) -> Boolean = { it != null }): List<String> =
        pl.free.elements(page, number).filter { it.isPhoto && !it.hidden && !has(it.photo?.mediaId) }.map { it.id }

    /**
     * [mediaIds] put into [page]'s empty photo spots: [target] first (an empty spot the traveller tapped), then the
     * others in their order on the page. A template page stays a template page. The photos left over are returned.
     */
    fun fill(
        page: BookPage, pl: PageLayout, number: Int, mediaIds: List<String>, target: String? = null,
        has: (String?) -> Boolean = { it != null },
    ): Filled {
        val spots = emptySpots(page, pl, number, has).let { s -> if (target != null && target in s) listOf(target) + (s - target) else s }
        // A template's own spots are its slots; the plan says which slot each one shows.
        val slotOf = HashMap<String, Int>()
        if (!page.free) pl.plan(page, number).let { plan -> plan.els.forEachIndexed { i, e -> if (e is PhotoEl && e.slot >= 0) plan.id(i)?.let { slotOf.putIfAbsent(it, e.slot) } } }
        var p = page
        val n = min(spots.size, mediaIds.size)
        for (k in 0 until n) {
            val id = spots[k]
            val m = mediaIds[k]
            val slot = slotOf[id]
            p = if (slot != null) p.withSlot(slot, p.slot(slot).copy(mediaId = m, crop = Crop()))
            else p.copy(els = p.els.map { e -> if (e.id == id) e.copy(photo = (e.photo ?: Slot()).copy(mediaId = m, crop = Crop())) else e })
        }
        return Filled(p, mediaIds.drop(n))
    }

    /** Whether [page] can take a bigger album arrangement: a page of photos only (not the cover, nor a text page). */
    fun canGrow(page: BookPage): Boolean {
        val t = page.template ?: return false
        return t == Template.Album || t.photos > 0 && !t.text && t != Template.Cover
    }

    /** How many photos [page] holds (on it and kept with it). */
    fun photoCount(page: BookPage): Int =
        (if (page.free) page.els.count { it.isPhoto && it.photo?.mediaId != null } else 0) + page.slots.count { it.mediaId != null }

    /** The arrangement [grow] would give [page] with [more] photos added; null when no arrangement holds them all. */
    fun grown(page: BookPage, more: Int): AlbumLayout? {
        if (!canGrow(page)) return null
        val total = photoCount(page) + more
        val now = page.albumLayout
        return if (now != null && now.count >= total) now else AlbumLayouts.fitting(total)
    }

    /**
     * [page] with [mediaIds] after its photos, in the smallest album arrangement that holds them all (that size's usual
     * one); null when no arrangement holds them.
     */
    fun grow(page: BookPage, mediaIds: List<String>): BookPage? {
        val l = grown(page, mediaIds.size) ?: return null
        return apply(page.copy(slots = page.slots + mediaIds.map { Slot(it) }), l.id)
    }

    /** [mediaIds] placed on [page] by hand, each a little lower and to the right of the one before. */
    fun byHand(page: BookPage, pl: PageLayout, mediaIds: List<String>, newId: () -> String): BookPage {
        var p = page
        mediaIds.forEachIndexed { k, m ->
            val e = FreeEdit.newPhoto(pl, newId(), m)
            val d = (k % 6) * FreeEdit.COPY_OFFSET * 1.6
            p = FreeEdit.add(p, e.copy(box = e.box.offset(d, d)))
        }
        return p
    }
}

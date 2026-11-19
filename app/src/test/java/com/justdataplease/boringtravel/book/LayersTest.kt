package com.justdataplease.boringtravel.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor's "On this page" list: rows top first ↔ drawing order, moving rows, hiding and showing, row words. */
class LayersTest {
    private val facts = BookFixtures.facts()

    private fun layout(spec: BookSpec = BookSpec()) = BookLayout(spec, facts, TextFit.Estimate, BookFixtures.ids())

    private fun photoPage() = BookPage(
        "x", Template.PhotoTwo.id, slots = listOf(Slot("chora", caption = "Chora at dawn"), Slot("beach")), day = BookFixtures.start.plusDays(3),
    )

    private val box = Box(10.0, 10.0, 40.0, 12.0)

    @Test fun rowsAreTopFirstAndMapBothWaysToTheDrawingOrder() {
        val draw = listOf("paper", "photo", "title", "sticker")
        val rows = Layers.topFirst(draw)
        assertEquals(listOf("sticker", "title", "photo", "paper"), rows)
        draw.indices.forEach { z ->
            val row = Layers.rowOf(z, draw.size)
            assertEquals(draw[z], rows[row])
            assertEquals(z, Layers.zOf(row, draw.size))
        }
        assertEquals(listOf("b", "a", "c"), Layers.move(listOf("a", "b", "c"), 1, 0))
        assertEquals(listOf("b", "c", "a"), Layers.move(listOf("a", "b", "c"), 0, 2))
        // Out of range or onto itself: unchanged.
        assertEquals(listOf("a", "b", "c"), Layers.move(listOf("a", "b", "c"), 3, 0))
        assertEquals(listOf("a", "b", "c"), Layers.move(listOf("a", "b", "c"), 1, 1))
    }

    @Test fun movingTheBottomRowToTheTopBringsItToTheFront() {
        val l = layout()
        val page = photoPage()
        val rows = Layers.topFirst(l.pages.free.elements(page, 3))
        val bottom = rows.last()
        val moved = FreeEdit.moveLayer(page, l.pages, 3, bottom.id, Layers.zOf(0, rows.size))
        // A template page turns free so its own elements can be reordered; the rest keep their order.
        assertTrue(moved.free)
        val after = Layers.topFirst(l.pages.free.elements(moved, 3))
        assertEquals(listOf(bottom.id) + rows.dropLast(1).map { it.id }, after.map { it.id })
        // The page draws it last, so it prints on top.
        assertEquals(bottom.id, l.pages.plan(moved, 3).ids.filterNotNull().last())
        // And back to the bottom.
        val back = FreeEdit.moveLayer(moved, l.pages, 3, bottom.id, Layers.zOf(rows.lastIndex, rows.size))
        assertEquals(rows.map { it.id }, Layers.topFirst(l.pages.free.elements(back, 3)).map { it.id })
    }

    @Test fun movingToWhereItIsOrAnUnknownIdChangesNothing() {
        val l = layout()
        val page = photoPage()
        val rows = Layers.topFirst(l.pages.free.elements(page, 3))
        assertSame(page, FreeEdit.moveLayer(page, l.pages, 3, rows.first().id, Layers.zOf(0, rows.size)))
        assertSame(page, FreeEdit.moveLayer(page, l.pages, 3, "nope", 0))
        assertFalse(FreeEdit.moveLayer(page, l.pages, 3, rows.first().id, Layers.zOf(0, rows.size)).free)
    }

    @Test fun hiddenElementsStayListedButLeaveThePageAndComeBackAsTheyWere() {
        val l = layout()
        val page = photoPage()
        val before = l.pages.plan(page, 3)
        val photo = l.pages.free.elements(page, 3).first { it.isPhoto }
        val hidden = FreeEdit.setHidden(page, l.pages, 3, photo.id, true)
        assertTrue(hidden.free)
        assertTrue(l.pages.free.elements(hidden, 3).first { it.id == photo.id }.hidden)
        assertEquals(l.pages.free.elements(page, 3).size, l.pages.free.elements(hidden, 3).size)
        val plan = l.pages.plan(hidden, 3)
        assertFalse(photo.id in plan.ids)
        assertEquals(before.ids.size - 1, plan.ids.size)
        assertEquals(before.photos.size - 1, plan.photos.size)
        // Shown again: the same page as before it was hidden.
        val shown = FreeEdit.setHidden(hidden, l.pages, 3, photo.id, false)
        val again = l.pages.plan(shown, 3)
        assertEquals(before.ids, again.ids)
        assertEquals(before.photos.map { it.mediaId to it.frame }, again.photos.map { it.mediaId to it.frame })
        // Asking for what it already is changes nothing.
        assertSame(shown, FreeEdit.setHidden(shown, l.pages, 3, photo.id, false))
    }

    @Test fun thePageTextCannotBeHidden() {
        val l = layout()
        val page = BookPage("t", Template.Text.id, entryId = "e5", paras = listOf(Para(ParaKind.P, "Forty late", "e5")))
        val flow = l.pages.free.elements(page, 3).first { it.isFlow }
        assertFalse(FreeEdit.canHide(flow))
        assertSame(page, FreeEdit.setHidden(page, l.pages, 3, flow.id, true))
    }

    @Test fun aHiddenBackgroundShowsThePaperAndIsKept() {
        val l = layout()
        val page = BookPage("n", Template.Notes.id, bg = PageBg(PageBg.COLOR, color = "#1E2A5A"))
        assertNotNull(l.pages.plan(page, 3).bg)
        val hid = FreeEdit.setBgHidden(page, true)
        val plan = l.pages.plan(hid, 3)
        assertNull(plan.bg)
        assertEquals(l.pages.plan(page.copy(bg = null), 3).paper, plan.paper)
        assertEquals("#1E2A5A", hid.bg?.color)
        assertEquals(page, FreeEdit.setBgHidden(hid, false))
        // No background: nothing to hide.
        val plain = BookPage("p", Template.Notes.id)
        assertSame(plain, FreeEdit.setBgHidden(plain, true))
    }

    @Test fun aCopyOfAHiddenElementShows() {
        val l = layout()
        val page = FreeEdit.add(BookPage("n", Template.Notes.id), FreeEl("w", FreeKind.TEXT, box, text = "Hi", hidden = true))
        val copied = FreeEdit.duplicate(page, l.pages, 3, "w", "w2")
        assertTrue(copied.el("w")!!.hidden)
        assertFalse(copied.el("w2")!!.hidden)
    }

    @Test fun rowsNameWhatTheyAre() {
        val photo = FreeEl("p", FreeKind.PHOTO, box, rot = -3.0, photo = Slot("chora", caption = "Fitz Roy"), style = FreeLayout.PRINT)
        assertEquals("Fitz Roy", Layers.name(photo, facts))
        assertEquals("Photo · frame, −3°", Layers.detail(photo) { Face.Caveat })
        // No caption: the place of the entry it belongs to.
        assertEquals(facts.book.entries.first { it.id == "e1" }.placeName, Layers.name(photo.copy(photo = Slot("ferry")), facts))
        assertEquals("No photo yet", Layers.name(photo.copy(photo = null), facts))
        assertEquals("Photo · empty", Layers.detail(photo.copy(photo = Slot(), style = FreeLayout.FLAT, rot = 0.0)) { Face.Caveat })

        val caption = FreeEl("c", FreeKind.TEXT, box, text = "It finally showed itself\nat sunrise", bind = "cap:p", font = "display")
        assertEquals("It finally showed itself", Layers.name(caption, facts))
        assertEquals("Caption · Hand", Layers.detail(caption) { Face.Caveat })
        val title = FreeEl("t", FreeKind.TEXT, box, text = "Fitz Roy", bind = FreeEl.TITLE, rot = 4.0, hidden = true)
        assertEquals("Title · Serif, +4°, hidden", Layers.detail(title) { Face.Literata })
        assertEquals("Empty", Layers.name(caption.copy(text = " "), facts))

        val stamp = FreeEl("s", FreeKind.STAMP, box, style = "brand")
        assertEquals("Paper plane", Layers.name(stamp, facts))
        assertEquals("Sticker", Layers.detail(stamp) { Face.Caveat })
        assertEquals("Flag GR", Layers.name(FreeEl("f", FreeKind.FLAGS, box, codes = listOf("GR")), facts))
        assertEquals("Day's route", Layers.name(FreeEl("r", FreeKind.ROUTE, box, src = FreeLayout.DAY), facts))
        assertEquals("Map", Layers.detail(FreeEl("r", FreeKind.ROUTE, box)) { Face.Caveat })
        assertEquals("Page text", Layers.name(FreeEl("t0", FreeKind.FLOW, box), facts))

        assertEquals("Dotted paper" to "Background · the theme's", Layers.background(null, BookPaper.Dots, facts))
        assertEquals("Colour" to "Background, hidden", Layers.background(PageBg(PageBg.COLOR, color = "#FFFFFF", hidden = true), null, facts))
    }
}

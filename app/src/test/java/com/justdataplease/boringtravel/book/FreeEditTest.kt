package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.model.PrintTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Free placement: a template page turned into elements, editing steps, templates applied again, undo. */
class FreeEditTest {
    private val facts = BookFixtures.facts()

    private fun layout(spec: BookSpec = BookSpec()) = BookLayout(spec, facts, TextFit.Estimate, BookFixtures.ids())

    private fun r(v: Double) = Math.round(v * 1e5) / 1e5
    private fun r(b: Box) = listOf(r(b.x), r(b.y), r(b.w), r(b.h))

    private fun pts(ps: List<P2>) = ps.map { listOf(r(it.x), r(it.y)) }

    /** What an element looks like on paper, whatever its kind of record. */
    private fun look(e: El, tagAroundLeft: Boolean): Any = when (e) {
        is PhotoEl -> listOf("photo", e.mediaId, r(e.frame), r(e.image), r(e.tilt), e.crop, e.captionText, e.captionInFrame, e.tapes, e.bleed, e.empty)
        is TextEl -> {
            // Where the turned words end up (Notebook's tag turns around its left edge, everything else around its centre).
            val about = if (tagAroundLeft && e.role == "tag" && !e.exact) P2(e.box.x, e.box.cy) else P2(e.box.cx, e.box.cy)
            val c = listOf(P2(e.box.x, e.box.y), P2(e.box.right, e.box.y)).map { FreeMath.rotate(it, e.angle, about) }
            listOf("text", e.role, e.text, e.face, r(e.pt), e.bold, r(e.lineMm), e.align, e.mark, e.upper, e.italic, pts(c))
        }
        is FlowEl -> listOf("flow", r(e.box), e.rows, e.cols, r(e.colWidth), e.angle)
        else -> e
    }

    private fun samplePage(t: Template, i: Int) = BookPage(
        "x$i", t.id,
        slots = List(t.photos) { j -> Slot(BookFixtures.photoSizes.keys.elementAt(j + i % 5), caption = "cap $j") },
        day = BookFixtures.start.plusDays(3), entryId = if (t.text) "e5" else null,
        paras = if (t.text) listOf(Para(ParaKind.TITLE, "The bus", "e5"), Para(ParaKind.P, "Forty <b>late</b>", "e5")) else emptyList(),
    )

    @Test fun turningATemplatePageFreeKeepsHowItLooksInEveryThemeAndTemplate() {
        PrintTheme.entries.forEach { theme ->
            listOf(BookFormat.A5, BookFormat.Square).forEach { format ->
                val l = layout(BookSpec(format = format, theme = theme))
                val tagLeft = theme == PrintTheme.Notebook || theme == PrintTheme.Scrapbook
                Template.entries.forEachIndexed { i, t ->
                    val page = samplePage(t, i)
                    listOf(3, 4).forEach { number ->
                        val before = l.pages.plan(page, number)
                        val frozen = FreeEdit.freeze(page, l.pages, number)
                        assertTrue(frozen.free)
                        val after = l.pages.plan(frozen, number)
                        assertEquals("$theme $format ${t.id}", before.els.size, after.els.size)
                        before.els.zip(after.els).forEachIndexed { k, (a, b) ->
                            assertEquals("$theme $format ${t.id} #$k", look(a, tagLeft), look(b, tagLeft))
                        }
                        // The same ids before and after, so the editor keeps what is selected.
                        assertEquals(before.ids, after.ids)
                    }
                }
            }
        }
    }

    @Test fun freezingKeepsPhotosTheTemplateHasNoSpotFor() {
        val page = BookPage("a", Template.Photo.id, slots = listOf(Slot("chora"), Slot("beach"), Slot("hozo")))
        val free = FreeEdit.freeze(page, layout().pages, 3)
        assertEquals(listOf("chora"), free.els.filter { it.isPhoto }.map { it.photo?.mediaId })
        assertEquals(listOf("beach", "hozo"), free.slots.map { it.mediaId })
        assertEquals(setOf("chora", "beach", "hozo"), free.mediaIds.toSet())
        // Freezing twice changes nothing.
        assertEquals(free, FreeEdit.freeze(free, layout().pages, 3))
    }

    @Test fun movingATemplateElementTurnsThePageFreeAndMovesOnlyThatOne() {
        val l = layout()
        val page = samplePage(Template.PhotoTwo, 0)
        val before = l.pages.free.elements(page, 3)
        val photo = before.first { it.isPhoto }
        val moved = FreeEdit.move(page, l.pages, 3, photo.id, photo.box.offset(10.0, -5.0), 7.5)
        assertTrue(moved.free)
        val after = l.pages.free.elements(moved, 3)
        assertEquals(before.map { it.id }, after.map { it.id })
        val m = after.first { it.id == photo.id }
        assertEquals(photo.box.x + 10.0, m.box.x, 1e-9)
        assertEquals(7.5, m.rot, 1e-9)
        before.filter { it.id != photo.id }.forEach { e -> assertEquals(e.box, after.first { it.id == e.id }.box) }
        // Its HTML puts it there, in mm, turned.
        val html = BookHtml.document(l, BookDoc(pages = listOf(moved), padTo4 = false), { id, _ -> "file:///img/$id.jpg" })
        assertTrue(html.contains("left:${BookSvg.n2(m.box.x)}mm;top:${BookSvg.n2(m.box.y)}mm;width:${BookSvg.n2(m.box.w)}mm;height:${BookSvg.n2(m.box.h)}mm;transform:rotate(7.5deg)"))
    }

    @Test fun addedElementsSitOnATemplatePageWithoutTurningItFree() {
        val l = layout()
        val page = samplePage(Template.Photo, 0)
        val text = FreeEdit.newText(l.pages, "n1").copy(text = "Hello")
        val p = FreeEdit.add(page, text)
        assertFalse(p.free)
        val plan = l.pages.plan(p, 3)
        assertEquals("n1", plan.ids[plan.els.indexOfFirst { it is TextEl && it.text == "Hello" }])
        // Above the template's own elements, below the page number.
        val i = plan.ids.indexOf("n1")
        assertTrue(plan.ids.subList(0, i).all { it != null && FreeLayout.isDerivedId(it) })
        // Editing it keeps the page a template page; editing a template element turns it free.
        assertFalse(FreeEdit.change(p, l.pages, 3, "n1") { it.copy(text = "Hi") }.free)
        assertTrue(FreeEdit.change(p, l.pages, 3, "t0") { it.copy(rot = 3.0) }.free)
    }

    @Test fun deleteRemovesCaptionsTooButNeverTheText() {
        val l = layout()
        val page = FreeEdit.freeze(samplePage(Template.Photo, 0), l.pages, 3)
        val photo = page.els.first { it.isPhoto }
        assertTrue(page.els.any { it.captionOf == photo.id })
        val gone = FreeEdit.delete(page, l.pages, 3, photo.id)
        assertTrue(gone.els.none { it.id == photo.id || it.captionOf == photo.id })
        val text = FreeEdit.freeze(samplePage(Template.Text, 0), l.pages, 3)
        val flow = text.els.first { it.isFlow }
        assertEquals(text, FreeEdit.delete(text, l.pages, 3, flow.id))
        assertEquals(text, FreeEdit.duplicate(text, l.pages, 3, flow.id, "copy"))
    }

    @Test fun duplicateCopiesAboveTheOriginalWithPlainWords() {
        val l = layout()
        val cover = BookPage("c", Template.Cover.id, slots = listOf(Slot("ferry")))
        val els = l.pages.free.elements(cover, 1)
        val title = els.first { it.bind == FreeEl.TITLE }
        val p = FreeEdit.duplicate(cover, l.pages, 1, title.id, "dup")
        assertFalse(p.free)
        val copy = p.el("dup")!!
        assertNull(copy.bind)
        assertEquals("Cyclades, slowly", copy.text)
        assertEquals(title.box.x + FreeEdit.COPY_OFFSET, copy.box.x, 1e-9)
        // On a free page the copy goes right above its original.
        val free = FreeEdit.freeze(cover, l.pages, 1)
        val p2 = FreeEdit.duplicate(free, l.pages, 1, title.id, "dup")
        assertEquals(p2.els.indexOfFirst { it.id == title.id } + 1, p2.els.indexOfFirst { it.id == "dup" })
    }

    @Test fun forwardAndBackSwapWithTheNeighbour() {
        val l = layout()
        val page = samplePage(Template.PhotoThree, 0)
        val ids = l.pages.free.elements(page, 3).map { it.id }
        val up = FreeEdit.order(page, l.pages, 3, ids[0], up = true)
        assertEquals(listOf(ids[1], ids[0]) + ids.drop(2), up.els.map { it.id })
        val down = FreeEdit.order(up, l.pages, 3, ids[0], up = false)
        assertEquals(ids, down.els.map { it.id })
        // Nothing below the first.
        assertFalse(FreeEdit.canOrder(page, l.pages, 3, ids[0], up = false))
        assertTrue(FreeEdit.canOrder(page, l.pages, 3, ids[0], up = true))
        assertEquals(page, FreeEdit.order(page, l.pages, 3, ids[0], up = false))
    }

    @Test fun applyingATemplateRearrangesTheElements() {
        val l = layout()
        // A free page with its own photo moved, a photo and words added by hand.
        var p = FreeEdit.freeze(samplePage(Template.Photo, 0), l.pages, 3)
        val t0 = p.els.first { it.isPhoto }
        p = FreeEdit.move(p, l.pages, 3, t0.id, t0.box.offset(5.0, 5.0), 10.0)
        p = FreeEdit.add(p, FreeEdit.newPhoto(l.pages, "added", "beach"))
        p = FreeEdit.add(p, FreeEdit.newText(l.pages, "words").copy(text = "Naxos"))
        p = FreeEdit.add(p, FreeEdit.newTape(l.pages, "tape"))
        val two = FreeEdit.applyTemplate(p, Template.PhotoTwoSide)
        assertFalse(two.free)
        assertEquals(Template.PhotoTwoSide.id, two.templateId)
        // Both photos fill the template's two spots, in drawing order; the template's own elements are gone.
        assertEquals(listOf(t0.photo?.mediaId, "beach"), two.slots.map { it.mediaId })
        assertEquals(listOf("words", "tape"), two.els.map { it.id })
        val plan = l.pages.plan(two, 3)
        assertEquals(listOf(t0.photo?.mediaId, "beach"), plan.photos.map { it.mediaId })
        // The same template again resets a hand-made page to it.
        val reset = FreeEdit.applyTemplate(FreeEdit.freeze(samplePage(Template.Photo, 0), l.pages, 3), Template.Photo)
        assertEquals(samplePage(Template.Photo, 0).slots, reset.slots)
        assertTrue(reset.els.isEmpty())
        // Through BookEdit (the layout picker).
        val doc = BookDoc(pages = listOf(p), padTo4 = false)
        assertEquals(Template.PhotoFour.id, BookEdit.template(doc, p.id, Template.PhotoFour).pages[0].templateId)
    }

    @Test fun newTemplatesLayOutTheirPhotosAndText() {
        PrintTheme.entries.forEach { theme ->
            BookFormat.entries.forEach { f ->
                val l = layout(BookSpec(format = f, theme = theme))
                val four = l.pages.plan(samplePage(Template.PhotoFour, 1), 3).photos
                assertEquals(4, four.size)
                // Two columns and two rows, none overlapping.
                for (a in four) for (b in four) if (a !== b) {
                    val overlap = a.image.x < b.image.right && b.image.x < a.image.right && a.image.y < b.image.bottom && b.image.y < a.image.bottom
                    assertFalse("$theme $f", overlap)
                }
                assertEquals(3, l.pages.plan(samplePage(Template.PhotoThreeUp, 1), 3).photos.size)
                val side = l.pages.plan(samplePage(Template.PhotoTwoSide, 1), 3).photos
                assertTrue("$theme $f side by side", side[0].frame.right <= side[1].frame.x + 1.0)
                val col = samplePage(Template.PhotoTextSide, 1)
                val plan = l.pages.plan(col, 3)
                val flow = plan.flow!!
                assertTrue("$theme $f text beside the photo", flow.box.x >= plan.photos.single().frame.right - 3.0)
                assertEquals(flow.flowBox, l.pages.flowBox(col))
                val cap = l.pages.plan(samplePage(Template.PhotoFullCaption, 1), 3)
                assertTrue(cap.photos.single().bleed)
                assertTrue(cap.els.any { it is TextEl && it.role == "over" && it.text == "cap 0" })
                assertTrue(cap.els.any { it is DecoEl && it.kind == FreeKind.SHADE })
            }
        }
    }

    @Test fun textOnAFreePageFlowsInItsTextBox() {
        val l = layout()
        val doc = l.auto()
        val page = doc.pages.first { it.entryId == "e2" && it.templateId == Template.PhotoText.id }
        val n = l.number(doc, page.id)
        val free = FreeEdit.freeze(page, l.pages, n)
        val flow = free.els.first { it.isFlow }
        // A smaller text box holds less: the run gets longer, and nothing is lost.
        val smaller = FreeEdit.move(free, l.pages, n, flow.id, flow.box.copy(h = flow.box.h / 2), 0.0)
        val before = doc.pages.count { it.entryId == "e2" && it.template?.text == true }
        val re = l.reflow(BookEdit.update(doc, page.id) { smaller }, page.id)
        val after = re.pages.count { it.entryId == "e2" && it.template?.text == true }
        assertTrue(after >= before)
        assertEquals(
            l.joinSplits(doc.pages.flatMap { it.paras }).map { it.html },
            l.joinSplits(re.pages.flatMap { it.paras }).map { it.html },
        )
    }

    @Test fun captionsFollowTheirPhoto() {
        val l = layout()
        var p = FreeEdit.add(BookPage("a", Template.Notes.id), FreeEdit.newPhoto(l.pages, "ph", "chora"))
        p = FreeEdit.setPhoto(p, l.pages, 3, "ph", Slot("chora", caption = "Harbour"))
        val cap = p.els.single { it.captionOf == "ph" }
        assertTrue(cap.box.y >= p.el("ph")!!.box.bottom)
        val html = BookHtml.document(l, BookDoc(pages = listOf(p), padTo4 = false), { id, _ -> "file:///img/$id.jpg" })
        assertTrue(html.contains(">Harbour</div>"))
        // A new caption replaces the words; the caption box stays one.
        p = FreeEdit.setPhoto(p, l.pages, 3, "ph", Slot("chora", caption = "Port"))
        assertEquals(1, p.els.count { it.captionOf == "ph" })
        assertEquals("Port", l.pages.free.text(p.els.single { it.captionOf == "ph" }, p))
    }

    @Test fun backgroundsDrawUnderEverything() {
        val l = layout()
        val page = samplePage(Template.Photo, 0)
        val color = FreeEdit.setBg(page, PageBg(PageBg.COLOR, color = "#123456"))
        val html = BookHtml.document(l, BookDoc(pages = listOf(color), padTo4 = false), { id, _ -> "file:///img/$id.jpg" })
        assertTrue(html.contains("height:210mm;background:#123456\"></div>"))
        assertFalse(html.contains("class=\"paper\"")) // no dots over a plain colour
        val photo = FreeEdit.setBg(page, PageBg(PageBg.PHOTO, photo = Slot("hozo"), dim = 0.3))
        val h2 = BookHtml.document(l, BookDoc(pages = listOf(photo), padTo4 = false), { id, _ -> "file:///img/$id.jpg" })
        assertTrue(h2.contains("class=\"ph bleed bgph\""))
        assertTrue(h2.contains("background:rgba(0,0,0,0.3)"))
        assertTrue(h2.indexOf("bgph") < h2.indexOf("class=\"ph print\""))
        assertTrue(BookHtml.photoNeeds(l, BookDoc(pages = listOf(photo), padTo4 = false)).containsKey("hozo"))
    }

    @Test fun copyLayoutAndDuplicatePage() {
        val l = layout()
        val doc = l.auto()
        val src = doc.pages.first { it.templateId == Template.PhotoTwo.id }
        val others = doc.pages.filter { it.templateId == Template.PhotoTwo.id && it.id != src.id }.map { it.id }
        val n = l.number(doc, src.id)
        val t0 = l.pages.free.elements(src, n).first { it.isPhoto }
        val arranged = FreeEdit.move(FreeEdit.setBg(src, PageBg(PageBg.COLOR, color = "#EEEEEE")), l.pages, n, t0.id, t0.box.offset(0.0, 12.0), 4.0)
        val d1 = BookEdit.update(doc, src.id) { arranged }
        val target = doc.pages.first { it.templateId == Template.PhotoText.id }
        val copied = FreeEdit.copyLayout(d1, src.id, others + target.id + doc.pages.first().id, l.pages) { l.number(d1, it) }
        val t = copied.page(target.id)!!
        assertEquals(Template.PhotoTwo.id, t.templateId)
        assertEquals("#EEEEEE", t.bg?.color)
        assertTrue(t.free)
        val moved = t.els.first { it.id == t0.id }
        assertEquals(4.0, moved.rot, 1e-9)
        assertEquals(arranged.el(t0.id)!!.box, moved.box)
        // Each page keeps its own photos.
        assertEquals(target.slots.first().mediaId, moved.photo?.mediaId)
        // The cover is left alone.
        assertEquals(doc.pages.first(), copied.pages.first())

        val dup = FreeEdit.duplicatePage(d1, src.id, BookFixtures.ids())
        val i = dup.indexOf(src.id)
        val copy = dup.pages[i + 1]
        assertNotEquals(src.id, copy.id)
        assertEquals(arranged.els.map { it.box }, copy.els.map { it.box })
        assertEquals(doc.pages.size + 1, dup.pages.size)
    }

    @Test fun changingThePageSizeMovesElementsWithThePage() {
        val doc = BookDoc(pages = listOf(BookPage("a", Template.Notes.id, els = listOf(
            FreeEl("t", FreeKind.TEXT, Box(64.0, 95.0, 20.0, 20.0), text = "Hi", pt = 10.0),
            FreeEl("p", FreeKind.PHOTO, Box(-3.0, -3.0, 154.0, 216.0), style = FreeLayout.BLEED),
        ))))
        val a4 = FreeEdit.rescale(doc, BookFormat.A5, BookFormat.A4).pages[0]
        val t = a4.el("t")!!
        // The centre (74, 105) of A5 goes to the centre of A4.
        assertEquals(105.0, t.box.cx, 1e-6)
        assertEquals(148.5, t.box.cy, 1e-6)
        assertTrue(t.pt > 10.0)
        val bleed = a4.el("p")!!
        assertTrue(bleed.box.right >= 210.0 && bleed.box.bottom >= 297.0)
        assertEquals(doc, FreeEdit.rescale(doc, BookFormat.A5, BookFormat.A5))
    }

    /** Every editing step undoes to exactly the page before it, and redoes to the page after. */
    @Test fun undoAndRedoOfEveryStep() {
        val l = layout()
        val pl = l.pages
        val base = l.auto()
        val page = base.pages.first { it.templateId == Template.PhotoTwo.id }
        val n = l.number(base, page.id)
        val photo = pl.free.elements(page, n).first { it.isPhoto }
        val steps: List<Pair<String, (BookDoc) -> BookDoc>> = listOf(
            "move" to { d -> BookEdit.update(d, page.id) { FreeEdit.move(it, pl, n, photo.id, photo.box.offset(3.0, 3.0), 0.0) } },
            "resize" to { d -> BookEdit.update(d, page.id) { FreeEdit.move(it, pl, n, photo.id, FreeMath.resize(photo.box, photo.rot, 2, P2(photo.box.right + 9, photo.box.bottom + 9), true), photo.rot) } },
            "turn" to { d -> BookEdit.update(d, page.id) { FreeEdit.move(it, pl, n, photo.id, photo.box, 30.0) } },
            "nudge" to { d -> BookEdit.update(d, page.id) { FreeEdit.nudge(it, pl, n, photo.id, FreeEdit.NUDGE, 0.0) } },
            "add text" to { d -> BookEdit.update(d, page.id) { FreeEdit.add(it, FreeEdit.newText(pl, "nt").copy(text = "Hi")) } },
            "add photo" to { d -> BookEdit.update(d, page.id) { FreeEdit.add(it, FreeEdit.newPhoto(pl, "np", "beach")) } },
            "add tape" to { d -> BookEdit.update(d, page.id) { FreeEdit.add(it, FreeEdit.newTape(pl, "tp")) } },
            "duplicate" to { d -> BookEdit.update(d, page.id) { FreeEdit.duplicate(it, pl, n, photo.id, "dp") } },
            "forward" to { d -> BookEdit.update(d, page.id) { FreeEdit.order(it, pl, n, photo.id, up = true) } },
            "delete" to { d -> BookEdit.update(d, page.id) { FreeEdit.delete(it, pl, n, photo.id) } },
            "background" to { d -> BookEdit.update(d, page.id) { FreeEdit.setBg(it, PageBg(PageBg.COLOR, color = "#101010")) } },
            "template" to { d -> BookEdit.template(d, page.id, Template.PhotoFour) },
            "duplicate page" to { d -> FreeEdit.duplicatePage(d, page.id, BookFixtures.ids()) },
            "copy layout" to { d -> FreeEdit.copyLayout(d, page.id, d.pages.map { it.id }, pl) { l.number(d, it) } },
        )
        val history = EditHistory<BookDoc>(30)
        var doc = base
        steps.forEach { (name, step) ->
            val before = doc
            history.push(before)
            doc = step(doc)
            assertNotEquals(name, before, doc)
            val undone = history.undo(doc)
            assertEquals(name, before, undone)
            val redone = history.redo(undone!!)
            assertEquals(name, doc, redone)
        }
        // Thirty steps back at most; a new change forgets the redo.
        val h = EditHistory<Int>(3)
        (1..5).forEach { h.push(it) }
        assertEquals(5, h.undo(6))
        assertEquals(4, h.undo(5))
        assertEquals(3, h.undo(4))
        assertNull(h.undo(3))
        assertTrue(h.canRedo)
        h.push(9)
        assertFalse(h.canRedo)
    }
}

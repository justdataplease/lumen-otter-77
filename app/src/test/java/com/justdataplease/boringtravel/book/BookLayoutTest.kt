package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.model.PrintTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookLayoutTest {
    private val facts = BookFixtures.facts()

    private fun layout(spec: BookSpec = BookSpec()) = BookLayout(spec, facts, TextFit.Estimate, BookFixtures.ids())

    private fun templates(doc: BookDoc) = doc.pages.map { it.templateId }

    @Test fun firstLayoutHasCoverTitleRouteDaysNumbersAndBack() {
        val doc = layout().auto()
        val t = templates(doc)
        assertEquals(Template.Cover.id, t.first())
        assertEquals(Template.TitlePage.id, t[1])
        assertEquals(Template.Map.id, t[2])
        assertEquals(Template.BackCover.id, t.last())
        assertEquals(Template.Stats.id, t[t.size - 2])
        // One opener per day with something on it: days 1, 2, 3, 4, 7 and 8.
        val openers = doc.pages.filter { it.templateId == Template.DayOpener.id }
        assertEquals(listOf(0L, 1L, 2L, 3L, 6L, 7L), openers.map { it.day!!.toEpochDay() - BookFixtures.start.toEpochDay() })
        // The cover takes the trip's first landscape photo.
        assertEquals("ferry", doc.pages.first().slots.single().mediaId)
    }

    @Test fun everyPhotoIsPlacedOnceAndEntriesKeepTheirOwnPhotos() {
        val doc = layout().auto()
        val placed = doc.pages.drop(1).flatMap { p -> p.slots.mapNotNull { it.mediaId } }
        assertEquals(BookFixtures.photoSizes.keys, placed.toSet())
        assertEquals(placed.size, placed.toSet().size)
        // Day 3: the entry's first photo sits with its text, the other two share a page after it.
        val e3 = doc.pages.filter { it.entryId == "e3" }
        assertEquals(Template.PhotoText.id, e3[0].templateId)
        assertEquals("kolymb", e3[0].slots.single().mediaId)
        assertEquals(Template.PhotoTwo.id, e3[1].templateId)
        assertEquals(listOf("kolymb2", "naousa"), e3[1].slots.map { it.mediaId })
        // Captions come from the journal.
        assertEquals("Six o'clock", e3[1].slots[0].caption)
        // A text-only entry takes the day's loose photo nearest in time; other loose photos close their day.
        val beach = doc.pages.single { p -> p.slots.any { it.mediaId == "beach" } }
        assertEquals("e5", beach.entryId)
        assertEquals(Template.PhotoText.id, beach.templateId)
        val parikia2 = doc.pages.single { p -> p.slots.any { it.mediaId == "parikia2" } }
        assertEquals(null, parikia2.entryId)
    }

    @Test fun longTextRunsOnToTextPagesAndNothingIsLost() {
        val l = layout()
        val doc = l.auto()
        val run = doc.pages.filter { it.entryId == "e2" && it.template?.text == true }
        assertEquals(Template.PhotoText.id, run[0].templateId)
        assertTrue("the walk needs more than the photo page", run.size >= 2)
        assertFalse(run[1].start)
        val text = l.joinSplits(run.flatMap { it.paras }).filter { it.kind == ParaKind.P }.map { com.justdataplease.boringtravel.doc.RichHtml.toPlainText(it.html) }
        val expected = BookFixtures.longWalk.map { com.justdataplease.boringtravel.doc.RichHtml.toPlainText(it) }
        assertEquals(expected, text)
        // Every page's text fits its box.
        val flow = Flow(l.pages.type, TextFit.Estimate)
        run.forEach { p ->
            val box = l.pages.flowBox(p)!!
            val f = flow.fill(p.paras, box)
            assertEquals("page ${p.id}", p.paras.size, f.count)
        }
    }

    @Test fun titleAndTimeLineStartTheEntry() {
        val doc = layout().auto()
        val first = doc.pages.first { it.entryId == "e5" }
        assertEquals(ParaKind.TITLE, first.paras[0].kind)
        assertEquals("The bus to Halki", first.paras[0].html)
        assertEquals(ParaKind.META, first.paras[1].kind)
        assertEquals("13:10 · Halki, Naxos", first.paras[1].html)
        assertTrue(first.paras.any { it.kind == ParaKind.DONE && it.html == "Postcards for Mum" })
        assertTrue(first.paras.any { it.html.contains("<b>Bought kitron</b>") })
    }

    @Test fun paddingAddsNotesPagesBeforeTheBackCover() {
        val doc = layout().auto()
        val printed = layout().printed(doc)
        assertEquals(0, printed.size % 4)
        val pad = BookLayout.padding(doc.pages.size)
        assertEquals(doc.pages.size + pad, printed.size)
        assertEquals(Template.BackCover.id, printed.last().templateId)
        if (pad > 0) assertEquals(Template.Notes.id, printed[printed.size - 2].templateId)
        assertEquals(doc.pages.size, layout().printed(doc.copy(padTo4 = false)).size)
        assertEquals(listOf(0, 3, 2, 1, 0), (4..8).map(BookLayout::padding))
    }

    @Test fun hidingAnEntryLeavesItsPagesOutAndShowingBringsThemBack() {
        val l = layout()
        val doc = l.auto()
        val hidden = l.reflow(BookEdit.hideEntry(doc, "e3", true))
        val printed = l.printed(hidden.copy(padTo4 = false))
        assertTrue(printed.none { it.entryId == "e3" })
        assertEquals(doc.pages.size, hidden.pages.size) // kept, only not printed
        val shown = l.reflow(BookEdit.hideEntry(hidden, "e3", false))
        assertEquals(doc.pages.size, l.printed(shown.copy(padTo4 = false)).size)
    }

    @Test fun editingTextKeepsUnchangedParagraphsAndPoursTheRunAgain() {
        val l = layout()
        val doc = l.auto()
        val page = doc.pages.first { it.entryId == "e5" }
        val old = BookEdit.textOf(page, "e5")
        assertTrue(old.startsWith("Forty minutes late"))
        val longer = old + "\n\n" + List(40) { "Another line about the bus and the square and the plane tree." }.joinToString(" ")
        val edited = l.reflow(BookEdit.setText(doc, page.id, "e5", "The slow bus", longer), page.id)
        val run = edited.pages.dropWhile { it.id != page.id }.takeWhile { it.id == page.id || (it.templateId == Template.Text.id && !it.start) }
        assertTrue(run.size >= 2)
        val first = edited.page(page.id)!!
        assertEquals("The slow bus", first.paras.first().html)
        // The bold highlight of the untouched paragraph survives the edit.
        assertTrue(run.flatMap { it.paras }.any { it.html.contains("<b>Bought kitron</b>") })
        // The journal entry itself is untouched.
        assertTrue(facts.book.entries.single { it.id == "e5" }.body.contains("Forty minutes late"))
    }

    @Test fun changingFormatPoursTextAgain() {
        val a5 = layout()
        val doc = a5.auto()
        val a4 = layout(BookSpec(format = BookFormat.A4))
        val re = a4.reflow(doc)
        val run5 = doc.pages.count { it.entryId == "e2" && it.template?.text == true }
        val run4 = re.pages.count { it.entryId == "e2" && it.template?.text == true }
        assertTrue("A4 holds more text per page", run4 <= run5)
        assertEquals(
            a5.joinSplits(doc.pages.flatMap { it.paras }).map { it.html },
            a4.joinSplits(re.pages.flatMap { it.paras }).map { it.html },
        )
    }

    @Test fun movingAContinuationPageMakesItStartItsOwnRun() {
        val l = layout()
        val doc = l.auto()
        val i = doc.pages.indexOfFirst { it.templateId == Template.Text.id && !it.start }
        assertTrue(i > 0)
        val moved = BookEdit.move(doc, i, 3)
        assertTrue(moved.pages[3].start)
        val deleted = BookEdit.delete(doc, doc.pages[i - 1].id)
        assertTrue(deleted.page(doc.pages[i].id)!!.start)
    }

    @Test fun templateChangeKeepsPhotosAndText() {
        val doc = layout().auto()
        val page = doc.pages.first { it.templateId == Template.PhotoTwo.id }
        val one = BookEdit.template(doc, page.id, Template.Photo).page(page.id)!!
        assertEquals(2, one.slots.size)
        val back = BookEdit.template(BookEdit.template(doc, page.id, Template.Photo), page.id, Template.PhotoTwo).page(page.id)!!
        assertEquals(page.slots, back.slots)
    }

    @Test fun everyThemeAndFormatLaysOutEveryPage() {
        PrintTheme.entries.forEach { theme ->
            BookFormat.entries.forEach { f ->
                val l = BookLayout(BookSpec(format = f, theme = theme), facts, TextFit.Estimate, BookFixtures.ids())
                val doc = l.auto()
                l.printed(doc).forEachIndexed { i, p ->
                    val plan = l.pages.plan(p, i + 1)
                    plan.photos.forEach { ph ->
                        assertTrue("$theme $f ${p.templateId} photo inside the bleed", ph.frame.x >= l.pages.sheet.art.x - 0.01 && ph.frame.right <= l.pages.sheet.art.right + 0.01)
                        assertTrue("$theme $f ${p.templateId}", ph.image.w > 5 && ph.image.h > 5)
                    }
                }
                assertNotNull(doc.pages.firstOrNull { it.templateId == Template.Stats.id })
            }
        }
    }
}

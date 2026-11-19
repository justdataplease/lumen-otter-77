package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.model.PrintTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smoke checks of the book's HTML: every template in every theme, escaping, crops, paper, bleed. */
class BookHtmlTest {
    private val facts = BookFixtures.facts()
    private val photo: (String, Int) -> String? = { id, _ -> "file:///img/$id.jpg" }

    private fun layout(spec: BookSpec = BookSpec()) = BookLayout(spec, facts, TextFit.Estimate, BookFixtures.ids())

    private fun count(s: String, part: String) = Regex(Regex.escape(part)).findAll(s).count()

    @Test fun onePageSectionPerPrintedPageAtTheTrimSize() {
        val l = layout()
        val doc = l.auto()
        val html = BookHtml.document(l, doc, photo)
        assertEquals(l.printed(doc).size, count(html, "<section class=\"pg\""))
        assertTrue(html.contains("@page{size:148mm 210mm;margin:0}"))
        assertTrue(html.contains("width:148mm;height:210mm"))
        assertTrue(html.contains("src:url('file:///android_res/font/caveat.ttf')"))
        assertTrue(html.trimEnd().endsWith("</html>"))
    }

    @Test fun everyTemplateRendersInEveryTheme() {
        PrintTheme.entries.forEach { theme ->
            val l = layout(BookSpec(theme = theme))
            val pages = Template.entries.mapIndexed { i, t ->
                BookPage("x$i", t.id, slots = List(t.photos) { j -> Slot(BookFixtures.photoSizes.keys.elementAt(j + i % 5), caption = "cap $j") },
                    day = BookFixtures.start.plusDays(3), entryId = if (t.text) "e5" else null,
                    paras = if (t.text) listOf(Para(ParaKind.TITLE, "The bus", "e5"), Para(ParaKind.P, "Forty <b>late</b>", "e5")) else emptyList())
            }
            val doc = BookDoc(pages = pages, padTo4 = false)
            val html = BookHtml.document(l, doc, photo)
            assertEquals(theme.id, Template.entries.size, count(html, "<section class=\"pg\""))
            Template.entries.forEach { t -> assertTrue("$theme ${t.id}", html.contains("data-t=\"${t.id}\"")) }
            // The cover has the trip's title; the day opener its stamp; the map and back cover the route.
            assertTrue(html.contains(">Cyclades, slowly<"))
            assertTrue(html.contains("class=\"e stamp day\"") || theme == PrintTheme.Postcard && html.contains("class=\"e postage\""))
            assertTrue(html.contains("<svg class=\"route\""))
            assertTrue(html.contains("<h3 class=\"title"))
            assertTrue(html.contains("Forty <b>late</b>"))
            assertTrue(html.contains("class=\"flag\""))
            assertTrue(html.contains(">Trip in numbers<"))
            assertTrue(html.contains("class=\"ph "))
        }
    }

    @Test fun notebookHasPrintsTapeStampAndPaper() {
        val l = layout(BookSpec(theme = PrintTheme.Notebook))
        val html = BookHtml.document(l, l.auto(), photo)
        assertTrue(html.contains("class=\"ph print\""))
        assertTrue(html.contains("class=\"tape\""))
        assertTrue(html.contains("transform:rotate("))
        assertTrue(html.contains("class=\"e stamp brand\""))
        // The logo is the paper plane on Postcard Pop's solid yellow, no gradient.
        assertTrue(html.contains("aria-label=\"BoringTravel\""))
        assertTrue(html.contains("fill=\"#FFC93C\""))
        assertFalse(html.contains("linearGradient"))
        assertTrue(html.contains("stroke-dasharray=\"0 5\"")) // dots every grid line
        assertTrue(html.contains("class=\"mk\""))
        assertTrue(html.contains("#C4423A"))
        val ruled = BookHtml.document(layout(BookSpec(paper = BookPaper.Lines)), l.auto(), photo)
        assertFalse(ruled.contains("stroke-dasharray=\"0 5\""))
        assertTrue(ruled.contains("class=\"paper\""))
    }

    @Test fun postcardIsFlatWithAFullBleedCover() {
        val l = layout(BookSpec(theme = PrintTheme.Postcard))
        val html = BookHtml.document(l, l.auto(), photo)
        assertTrue(html.contains("class=\"ph bleed\""))
        assertFalse(html.contains("class=\"ph print\""))
        assertFalse(html.contains("class=\"tape\""))
        assertTrue(html.contains("class=\"e postage\""))
    }

    @Test fun cropSetsTheImagePosition() {
        val l = layout()
        val page = BookPage("a", Template.PhotoFull.id, slots = listOf(Slot("chora", Crop(zoom = 2.0))))
        val html = BookHtml.document(l, BookDoc(pages = listOf(page), padTo4 = false), photo)
        // A full page photo zoomed 2x: the whole photo is drawn at well over 200% of the frame.
        val w = Regex("chora.jpg\" alt=\"\" style=\"left:(-?[\\d.]+)%;top:(-?[\\d.]+)%;width:([\\d.]+)%").find(html)!!.groupValues
        assertTrue(w[3].toDouble() > 200)
        assertTrue(w[1].toDouble() < 0)
    }

    @Test fun bleedAddsCropMarksAndALargerSheet() {
        val l = layout(BookSpec(bleed = true))
        val html = BookHtml.document(l, l.auto(), photo)
        assertTrue(html.contains("@page{size:168mm 230mm;margin:0}"))
        assertTrue(html.contains("class=\"marks\""))
        assertTrue(html.contains("left:-3mm;top:-3mm;width:154mm;height:216mm")) // paper to the bleed
        assertFalse(BookHtml.document(layout(), layout().auto(), photo).contains("class=\"marks\""))
    }

    @Test fun textFromTheTripIsEscaped() {
        val evil = "<script>alert(\"x\")</script>"
        val base = BookFixtures.book()
        val book = base.copy(trip = base.trip.copy(title = "Trip $evil"), entries = base.entries.map { it.copy(title = it.title + evil, placeName = evil) }, stops = base.stops.map { it.copy(name = it.name + evil) })
        val l = BookLayout(BookSpec(), BookFacts(book, java.util.Locale.UK), TextFit.Estimate, BookFixtures.ids())
        val doc = l.auto()
        val withCaption = BookEdit.update(doc, doc.pages[0].id) { it.withSlot(0, it.slot(0).copy(caption = evil)).copy(subtitle = evil) }
        val html = BookHtml.document(l, withCaption, photo)
        assertFalse(html.contains("<script"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test fun missingPhotosAreLeftOutOfThePdfButShownInTheEditor() {
        val l = layout()
        val doc = BookDoc(pages = listOf(BookPage("a", Template.PhotoTwo.id, slots = listOf(Slot("chora"), Slot(null)))), padTo4 = false)
        val pdf = BookHtml.document(l, doc, photo)
        assertEquals(1, count(pdf, "<img "))
        assertFalse(pdf.contains("ph empty"))
        val editor = BookHtml.page(l, doc, "a", photo, zoom = 0.5)
        assertTrue(editor.contains("class=\"ph empty\""))
        assertTrue(editor.contains("zoom:0.5"))
    }

    @Test fun photoNeedsFollowThePlacedSize() {
        val l = layout(BookSpec(format = BookFormat.A4))
        val needs = BookHtml.photoNeeds(l, l.auto())
        assertEquals(BookFixtures.photoSizes.keys, needs.keys)
        // Small photos are used at what they have; big ones get enough for 300 dpi.
        assertEquals(640, needs["portara"])
        assertTrue(needs.getValue("chora") in 1200..3456)
    }

    @Test fun freeElementsArePlacedInMmAndTurned() {
        val l = layout()
        val page = BookPage(
            "f", Template.Photo.id, free = true,
            els = listOf(
                FreeEl("p", FreeKind.PHOTO, Box(20.0, 30.0, 80.0, 60.0), 5.0, photo = Slot("chora"), style = "flat"),
                FreeEl("w", FreeKind.TEXT, Box(12.5, 120.0, 80.0, 30.0), -10.0, text = "Two <b>\nlines", font = "Lora", pt = 18.0, lh = 1.5,
                    align = "center", bold = true, color = "#C4423A"),
                FreeEl("tp", FreeKind.TAPE, Box(40.0, 25.0, 34.0, 9.0), -6.0, color = "#F2DE6B"),
                FreeEl("co", FreeKind.CORNERS, Box(20.0, 30.0, 80.0, 60.0), color = "#2B2B2B"),
                FreeEl("sh", FreeKind.SHAPE, Box(100.0, 150.0, 30.0, 30.0), style = "oval", fill = "#F7E36A"),
                FreeEl("ln", FreeKind.LINE, Box(20.0, 180.0, 80.0, 4.0), 90.0, color = "#1E2A5A", stroke = 0.6),
                FreeEl("st", FreeKind.STAMP, Box(110.0, 10.0, 24.0, 24.0), -10.0, style = "brand"),
                FreeEl("fg", FreeKind.FLAGS, Box(15.0, 10.0, 15.0, 9.0), 8.0, codes = listOf("GR"), flagH = 7.0, style = "sticker"),
            ),
        )
        val doc = BookDoc(pages = listOf(page), padTo4 = false)
        val html = BookHtml.document(l, doc, photo)
        // The photo: its frame where it was put, turned; the PDF's units.
        assertTrue(html.contains("class=\"ph flat\" data-slot=\"-1\" style=\"left:20mm;top:30mm;width:80mm;height:60mm;transform:rotate(5deg)\""))
        // Words: exactly in their box, turned around its centre, own face, size, colour and weight; line breaks kept, text escaped.
        assertTrue(html.contains("style=\"left:12.5mm;top:120mm;width:80mm;height:"))
        assertTrue(html.contains("Lora") && html.contains("font-size:18pt;font-weight:700"))
        assertTrue(html.contains("text-align:center;color:#C4423A;transform:rotate(-10deg);transform-origin:50% 50%"))
        assertTrue(html.contains("Two &lt;b&gt;<br>lines"))
        assertTrue(html.contains("class=\"e washi\""))
        assertTrue(html.contains("background:#F2DE6B;transform:rotate(-6deg)"))
        assertTrue(html.contains("class=\"e corners\""))
        assertTrue(html.contains("border-radius:50%"))
        assertTrue(html.contains("class=\"e line\""))
        assertTrue(html.contains("class=\"e stamp brand\""))
        assertTrue(html.contains("class=\"e flags sticker\""))
        // Drawing order is the elements' order.
        assertTrue(html.indexOf("class=\"ph flat\"") < html.indexOf("class=\"e washi\""))
        // The PDF has no editor handles; the editor's page has one per element, the page number none.
        assertFalse(html.contains("data-fe="))
        val editor = BookHtml.page(l, doc, "f", photo, zoom = 1.0)
        listOf("p", "w", "tp", "co", "sh", "ln", "st", "fg").forEach { assertTrue(it, editor.contains("data-fe=\"$it\"")) }
        // A template page's own elements carry t0, t1, … in the editor.
        val tpl = BookDoc(pages = listOf(BookPage("t", Template.PhotoTwo.id, slots = listOf(Slot("chora"), Slot("beach")))), padTo4 = false)
        val e2 = BookHtml.page(l, tpl, "t", photo, zoom = 1.0)
        assertTrue(e2.contains("data-fe=\"t0\""))
        assertTrue(e2.contains("data-fe=\"t1\""))
    }

    @Test fun newTemplatesRenderInEveryTheme() {
        val added = listOf(Template.PhotoFullCaption, Template.PhotoTwoSide, Template.PhotoThreeUp, Template.PhotoFour, Template.PhotoTextSide)
        PrintTheme.entries.forEach { theme ->
            val l = layout(BookSpec(theme = theme))
            val pages = added.mapIndexed { i, t ->
                BookPage("n$i", t.id, slots = List(t.photos) { j -> Slot(BookFixtures.photoSizes.keys.elementAt(j + i), caption = "cap $j") },
                    entryId = if (t.text) "e5" else null, paras = if (t.text) listOf(Para(ParaKind.P, "Forty <b>late</b>", "e5")) else emptyList())
            }
            val html = BookHtml.document(l, BookDoc(pages = pages, padTo4 = false), photo)
            added.forEachIndexed { i, t -> assertTrue("$theme ${t.id}", html.contains("data-t=\"${t.id}\"")) }
            assertEquals(theme.id, 1 + 2 + 3 + 4 + 1, count(html, "<img "))
            assertTrue(html.contains("class=\"e shade\""))
            assertTrue(html.contains("class=\"t over"))
            assertTrue(html.contains("Forty <b>late</b>"))
        }
    }

    @Test fun flagsDrawnForKnownCountriesAndLettersForOthers() {
        assertTrue(Flags.has("GR"))
        assertTrue(Flags.svg("gr", 5.0).contains("#0D5EAF"))
        val other = Flags.svg("ZZ", 5.0)
        assertTrue(other.contains(">ZZ</text>"))
        assertTrue(Flags.svg("<x>", 5.0).contains("aria-label=\"X\""))
    }

    @Test fun hiddenElementsAndBackgroundsAreLeftOutOfThePdfAndThePreview() {
        val l = layout()
        val pl = l.pages
        var page = BookPage("x", Template.Notes.id)
        page = FreeEdit.add(page, FreeEdit.newPhoto(pl, "p1", "chora"))
        page = FreeEdit.add(page, FreeEdit.newText(pl, "w1").copy(text = "Secret words"))
        page = FreeEdit.add(page, FreeEdit.newText(pl, "w2").copy(text = "Shown words"))
        val shown = BookDoc(pages = listOf(page), padTo4 = false)
        val before = BookHtml.document(l, shown, photo)
        assertTrue(before.contains("Secret words"))
        assertTrue(before.contains("/img/chora.jpg"))
        assertTrue(BookHtml.photoNeeds(l, shown).containsKey("chora"))

        page = FreeEdit.setHidden(page, pl, 1, "w1", true)
        page = FreeEdit.setHidden(page, pl, 1, "p1", true)
        page = page.copy(bg = PageBg(PageBg.PHOTO, photo = Slot("beach"), hidden = true))
        val doc = BookDoc(pages = listOf(page), padTo4 = false)
        val pdf = BookHtml.document(l, doc, photo)
        assertFalse(pdf.contains("Secret words"))
        assertTrue(pdf.contains("Shown words"))
        assertFalse(pdf.contains("/img/chora.jpg"))
        assertFalse(pdf.contains("/img/beach.jpg"))
        val needs = BookHtml.photoNeeds(l, doc)
        assertFalse(needs.containsKey("chora"))
        assertFalse(needs.containsKey("beach"))
        // The editor's preview leaves them out too (the list still has them).
        val editor = BookHtml.page(l, doc, "x", photo, zoom = 1.0)
        assertFalse(editor.contains("data-fe=\"w1\""))
        assertFalse(editor.contains("data-fe=\"p1\""))
        assertTrue(editor.contains("data-fe=\"w2\""))
        assertEquals(listOf("p1", "w1", "w2"), pl.free.elements(page, 1).filter { !FreeLayout.isDerivedId(it.id) }.map { it.id })
    }
}

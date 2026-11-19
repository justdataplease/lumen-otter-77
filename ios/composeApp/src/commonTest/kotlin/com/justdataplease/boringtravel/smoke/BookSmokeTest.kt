package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.book.BookCss
import com.justdataplease.boringtravel.book.BookEdit
import com.justdataplease.boringtravel.book.BookFacts
import com.justdataplease.boringtravel.book.BookFonts
import com.justdataplease.boringtravel.book.BookFormat
import com.justdataplease.boringtravel.book.BookHtml
import com.justdataplease.boringtravel.book.BookJson
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.BookPaper
import com.justdataplease.boringtravel.book.BookSpec
import com.justdataplease.boringtravel.book.Face
import com.justdataplease.boringtravel.book.Flow
import com.justdataplease.boringtravel.book.HtmlText
import com.justdataplease.boringtravel.book.Para
import com.justdataplease.boringtravel.book.ParaKind
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.book.TextFit
import com.justdataplease.boringtravel.book.measuredWeight
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.ios.BookPrintScale
import com.justdataplease.boringtravel.ios.WebKitPrint
import com.justdataplease.boringtravel.model.PrintTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The book (round 21), read in from the Android app (book/), on a made-up trip: the first layout and its text poured
 * page to page (TextFit's line breaking, the HTML subset cut at a page's end), the stored JSON both ways, the page HTML
 * in every look and its WebKit form, the day stamp's month as Java names it, and the print scale's numbers. Run on the
 * desktop and on the iPhone Simulator, where Kotlin/Native's regular expressions and text functions are the ones used.
 */
class BookSmokeTest {
    private val facts = BookFacts(Samples.book(), Locale.UK)
    private fun ids(): () -> String {
        var n = 0
        return { "p${++n}" }
    }

    @Test fun theFirstLayoutHasEveryPartAndPoursItsText() {
        val layout = BookLayout(BookSpec(BookFormat.A5, PrintTheme.Notebook), facts, TextFit.Estimate, ids())
        val doc = layout.auto()
        val kinds = doc.pages.map { it.template }
        assertEquals(Template.Cover, kinds.first())
        assertEquals(Template.TitlePage, kinds[1])
        assertEquals(Template.BackCover, kinds.last())
        assertTrue(Template.DayOpener in kinds && Template.Stats in kinds, "$kinds")
        // The note's photo sits with its text; its heading, words and checklist are the book's paragraphs.
        val text = doc.pages.first { it.template == Template.PhotoText }
        assertEquals("m1", text.slots.single().mediaId)
        assertEquals(listOf(ParaKind.TITLE, ParaKind.META, ParaKind.HEADING, ParaKind.P, ParaKind.DONE, ParaKind.CHECK), text.paras.map { it.kind })
        assertEquals("09:15 · Naxos port", text.paras[1].html)
        // Padded to a multiple of four with notes pages before the back cover.
        val printed = layout.printed(doc)
        assertEquals(0, printed.size % 4)
        assertEquals(Template.BackCover, printed.last().template)
    }

    @Test fun aLongParagraphIsCutAtAPagesEndAndJoinedAgain() {
        val words = (1..400).joinToString(" ") { if (it % 9 == 0) "<b>bold$it</b>" else "word$it" }
        val spec = BookSpec(BookFormat.A5, PrintTheme.Minimal)
        val layout = BookLayout(spec, facts, TextFit.Estimate, ids())
        var doc = layout.auto()
        val text = doc.pages.first { it.template == Template.PhotoText }
        doc = BookEdit.update(doc, text.id) { it.copy(paras = it.paras + Para(ParaKind.P, words, "e1")) }
        doc = layout.reflow(doc)
        val run = doc.pages.dropWhile { it.id != text.id }.takeWhile { it.id == text.id || (it.template == Template.Text && !it.start) }
        assertTrue(run.size >= 2, "the run grew to ${run.size} pages")
        assertTrue(run.flatMap { it.paras }.any { it.cont }, "a paragraph continues on the next page")
        // Every tag opened before a cut is closed and opened again after it.
        run.flatMap { it.paras }.forEach { p -> assertEquals(Regex("<b>").findAll(p.html).count(), Regex("</b>").findAll(p.html).count(), p.html) }
        // The halves join back into the paragraph's words.
        fun plainWords(html: String) = com.justdataplease.boringtravel.doc.RichHtml.toPlainText(html).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val joined = layout.joinSplits(run.flatMap { it.paras })
        assertTrue(joined.any { plainWords(it.html) == plainWords(words) }, "the halves join back into the paragraph")
        // A page's own share fits it.
        val flow = Flow(layout.pages.type, TextFit.Estimate)
        run.forEach { p -> layout.pages.flowBox(p)?.let { box -> assertEquals(p.paras.size, flow.fill(p.paras, box).count, p.id) } }
    }

    @Test fun theHtmlSubsetIsCutWithItsTagsKept() {
        assertEquals("<b>Paros</b>" to "<b>got smaller</b>", HtmlText.split("<b>Paros got smaller</b>", 6))
        assertEquals("a &amp; b" to "c", HtmlText.split("a &amp; b c", 6))
        assertEquals(listOf(0, 6, 12), TextFit.lineStarts("aaaaa bbbbb ccccc", 10.0, Face.Literata, 10.0, false, { t, _, _, _ -> t.length * 1.0 }))
    }

    @Test fun theStoredJsonReadsBackTheSame() {
        val layout = BookLayout(BookSpec(BookFormat.Square, PrintTheme.Postcard, BookFonts.Kraft, BookPaper.Lines, bleed = true), facts, TextFit.Estimate, ids())
        val doc = layout.auto()
        val json = BookJson.encode(doc)
        assertTrue(json.startsWith("{\"v\":2,\"paper\":\"lines\",\"pad4\":true,\"pages\":["), json.take(80))
        assertEquals(doc, BookJson.decode(json))
        assertEquals(json, BookJson.encode(BookJson.decode(json)))
        // Fields of a newer app are kept; a damaged text reads as an empty book.
        val newer = json.replaceFirst("{\"v\":2,", "{\"v\":2,\"future\":[1,2],")
        assertTrue(BookJson.encode(BookJson.decode(newer)).contains("\"future\":[1,2]"))
        assertTrue(BookJson.decode("{not json").pages.isEmpty())
        assertEquals("148 × 210 mm", BookFormat.A5.sizeText)
        assertEquals("215.9 × 279.4 mm", BookFormat.Letter.sizeText)
    }

    @Test fun everyLookPrintsItsPagesAndWebKitGetsNoClearGradient() {
        for (theme in PrintTheme.entries) for (mode in BookHtml.Mode.entries) {
            val layout = BookLayout(BookSpec(BookFormat.A5, theme), facts, TextFit.Estimate, ids())
            val doc = layout.auto()
            val html = BookHtml.document(layout, doc, { id, px -> "img/$id-$px.jpg" }, mode, fontBase = "fonts/")
            assertEquals(layout.printed(doc).size, Regex("<section class=\"pg\"").findAll(html).count(), "$theme $mode")
            assertTrue(html.contains("url('fonts/caveat.ttf')"))
            val webkit = WebKitPrint.forBookPrint(html, layout.pages.sheet.pageW, layout.pages.sheet.pageH)
            assertFalse(Regex("""gradient\([^;{}]*(transparent|rgba)""").containsMatchIn(webkit), "$theme $mode")
            assertTrue(webkit.contains("url(data:image/png;base64,"), "the scrim is a picture")
        }
        // A note before the trip's dates: its day stamp names the month as Java's Month does, its day from the engine's days.
        val before = Samples.book().let { it.copy(trip = it.trip.copy(startDate = Samples.start.plusDays(3), endDate = Samples.start.plusDays(4))) }
        val l = BookLayout(BookSpec(theme = PrintTheme.Notebook), BookFacts(before, Locale.UK), TextFit.Estimate, ids())
        val html = BookHtml.document(l, l.auto(), { _, _ -> null })
        assertTrue(html.contains(">SEP</text>") && html.contains(">11</text>"), html.substringAfter("class=\"e stamp day\"").take(1200))
        assertNotNull(BookCss.fonts("fonts/").takeIf { "@font-face{font-family:'Gochi Hand';src:url('fonts/gochi_hand_regular.ttf')" in it })
    }

    @Test fun thePrintScaleFitsEachSectionOnItsSheet() {
        val a5 = BookPrintScale(148.0, 210.0)
        assertEquals(524.0, a5.layoutWidth)
        assertEquals(743.0, a5.layoutHeight)
        assertTrue(a5.sectionMm < 210.0 && a5.sectionMm > 209.4, "${a5.sectionMm}")
        assertEquals(600f, measuredWeight(Face.Literata, true))
        assertEquals(700f, measuredWeight(Face.Caveat, true))
        assertEquals(null, measuredWeight(Face.PlexMono, true))
    }
}

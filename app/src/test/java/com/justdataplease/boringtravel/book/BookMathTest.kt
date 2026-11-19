package com.justdataplease.boringtravel.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crop, page size, bleed, grid and line-breaking maths. */
class BookMathTest {
    private val eps = 1e-9

    // ---------------------------------------------------------------- crop

    @Test fun wholePhotoThatFitsTheFrameShape() {
        // A 4:3 photo in a square frame shows its middle square.
        val v = CropMath.visible(4000, 3000, 1.0, Crop())
        assertEquals(0.75, v.w, eps)
        assertEquals(1.0, v.h, eps)
        assertEquals(0.125, v.x, eps)
        assertEquals(0.0, v.y, eps)
        // The same shape shows all of it.
        assertEquals(Box(0.0, 0.0, 1.0, 1.0), CropMath.visible(4000, 3000, 4.0 / 3.0, Crop()))
    }

    @Test fun zoomAndPanStayInsideThePhoto() {
        val v = CropMath.visible(4000, 3000, 4.0 / 3.0, Crop(zoom = 2.0, cx = 0.95, cy = 0.05))
        assertEquals(0.5, v.w, eps)
        assertEquals(0.5, v.x, eps) // pushed back inside
        assertEquals(0.0, v.y, eps)
        val c = CropMath.clamp(Crop(zoom = 0.2, cx = Double.NaN, cy = 7.0))
        assertEquals(Crop(1.0, 0.5, 1.0), c)
    }

    @Test fun panMovesTheOtherWayFromTheFinger() {
        val start = Crop(zoom = 2.0)
        val moved = CropMath.pan(4000, 3000, 4.0 / 3.0, start, dxFrame = -0.2, dyFrame = 0.0)
        // Finger to the left shows more of the right side.
        assertTrue(moved.cx > start.cx)
        val zoomed = CropMath.pan(4000, 3000, 4.0 / 3.0, Crop(), 0.0, 0.0, scale = 1.5)
        assertEquals(1.5, zoomed.zoom, eps)
        assertEquals(1.0, CropMath.pan(4000, 3000, 1.0, Crop(), 0.0, 0.0, scale = 0.2).zoom, eps)
    }

    @Test fun cssPlacesTheWholePhotoSoTheCropFillsTheFrame() {
        val c = CropMath.cssPercent(Box(0.25, 0.1, 0.5, 0.8))
        assertEquals(-50.0, c[0], eps)
        assertEquals(-12.5, c[1], eps)
        assertEquals(200.0, c[2], eps)
        assertEquals(125.0, c[3], eps)
    }

    @Test fun printPixelsAreAbout300DpiWherePlaced() {
        // 100 mm wide at 300 dpi = 1181 px across; a 4000 px photo shown whole needs 1181 on its long side.
        assertEquals(1182, CropMath.printPx(4000, 3000, 100.0, 75.0, Crop()))
        // Zoomed in 2x, the whole photo must be twice as big, capped by what the photo has.
        assertEquals(2363, CropMath.printPx(4000, 3000, 100.0, 75.0, Crop(zoom = 2.0)))
        assertEquals(640, CropMath.printPx(640, 480, 100.0, 75.0, Crop()))
        assertEquals(CropMath.MAX_PRINT_PX, CropMath.printPx(12000, 9000, 400.0, 300.0, Crop(zoom = 3.0)))
        assertEquals(162.56, CropMath.effectiveDpi(640, 480, 100.0, 75.0, Crop()), 0.01)
    }

    // ---------------------------------------------------------------- sheet

    @Test fun pageSizeWithAndWithoutBleed() {
        val plain = Sheet(BookFormat.A5, bleed = false)
        assertEquals(148.0, plain.pageW, eps)
        assertEquals(210.0, plain.pageH, eps)
        assertEquals(Box(0.0, 0.0, 148.0, 210.0), plain.art)
        assertTrue(plain.cropMarks().isEmpty())
        assertEquals(5827, plain.widthMils)

        val shop = Sheet(BookFormat.A5, bleed = true)
        assertEquals(168.0, shop.pageW, eps)
        assertEquals(230.0, shop.pageH, eps)
        assertEquals(Box(10.0, 10.0, 148.0, 210.0), shop.trim)
        assertEquals(Box(-3.0, -3.0, 154.0, 216.0), shop.art)
        val marks = shop.cropMarks()
        assertEquals(8, marks.size)
        // Every mark is outside the bleed and on a trim line.
        marks.forEach { m ->
            val horizontal = m.y1 == m.y2
            if (horizontal) {
                assertTrue(m.y1 == 10.0 || m.y1 == 220.0)
                assertTrue(minOf(m.x1, m.x2) >= 158.0 + 3.0 || maxOf(m.x1, m.x2) <= 10.0 - 3.0)
            } else {
                assertTrue(m.x1 == 10.0 || m.x1 == 158.0)
                assertTrue(minOf(m.y1, m.y2) >= 220.0 + 3.0 || maxOf(m.y1, m.y2) <= 10.0 - 3.0)
            }
        }
        assertEquals(Sheet(BookFormat.Letter, false).widthMils, 8500)
        assertEquals(Sheet(BookFormat.Square, false).pageW, Sheet(BookFormat.Square, false).pageH, eps)
    }

    @Test fun gridIsWholeLinesCentredInTheTrim() {
        BookFormat.entries.forEach { f ->
            val g = Grid(f)
            assertEquals(f.widthMm, g.cols * g.line + 2 * g.ox, 1e-6)
            assertEquals(f.heightMm, g.rows * g.line + 2 * g.oy, 1e-6)
            assertTrue(g.ox >= 0 && g.ox < g.line && g.oy >= 0 && g.oy < g.line)
            // The type area is at least 3 lines (≥ 15 mm) from every edge.
            assertTrue(g.content.x >= 15.0 && f.widthMm - g.content.right >= 15.0 && g.content.y >= 15.0 && f.heightMm - g.content.bottom >= 20.0)
            assertEquals(g.contentRows * g.line, g.content.h, 1e-6)
        }
        assertEquals(1, Grid(BookFormat.A5).columns)
        assertEquals(2, Grid(BookFormat.A4).columns)
        assertEquals(20.0, Grid(BookFormat.A5).snapDown(16.2), 1e-6)
        assertEquals(15.0, Grid(BookFormat.A5).snapDown(15.0), 1e-6)
    }

    @Test fun boxFitKeepsTheShape() {
        val b = Box(0.0, 0.0, 100.0, 50.0).fit(1.0)
        assertEquals(Box(25.0, 0.0, 50.0, 50.0), b)
        assertEquals(Box(0.0, 0.0, 50.0, 50.0), Box(0.0, 0.0, 100.0, 50.0).fit(1.0, alignX = 0.0))
    }

    // ---------------------------------------------------------------- text

    private val mono = TextMeasure { text, _, _, _ -> text.length.toDouble() } // 1 mm per character

    @Test fun greedyLineBreaks() {
        val starts = TextFit.lineStarts("aaa bbb ccc dddd", 7.5, Face.Literata, 10.0, false, mono)
        // 7.5 × 0.975 = 7.3 mm per line: "aaa bbb" (7) fits, then "ccc" (3), "dddd" doesn't join it.
        assertEquals(listOf(0, 8, 12), starts)
        assertEquals(3, TextFit.lines("aaa bbb ccc dddd", 7.5, Face.Literata, 10.0, false, mono))
        // A forced line break starts a new line; a word too long is broken anywhere.
        assertEquals(listOf(0, 4), TextFit.lineStarts("one\ntwo", 50.0, Face.Literata, 10.0, false, mono))
        assertEquals(3, TextFit.lines("abcdefghijkl", 5.0, Face.Literata, 10.0, false, mono))
        // A first-line indent pushes a word down.
        assertEquals(2, TextFit.lines("aaa bbb", 8.0, Face.Literata, 10.0, false, mono, firstIndentMm = 2.0))
    }

    @Test fun htmlSplitKeepsTagsOpenAcrossTheCut() {
        val html = "Forty <b>minutes late and</b> nobody <i>minded</i> &amp; so on"
        val plain = com.justdataplease.boringtravel.doc.RichHtml.toPlainText(html)
        val at = plain.indexOf("late")
        val (a, b) = HtmlText.split(html, at)
        assertEquals("Forty <b>minutes</b>", a)
        assertEquals("<b>late and</b> nobody <i>minded</i> &amp; so on", b)
        assertEquals(plain.substring(at), com.justdataplease.boringtravel.doc.RichHtml.toPlainText(b))
        // Entities count as one character.
        val (c, d) = HtmlText.split("a &amp; b c", 6)
        assertEquals("a &amp; b", c)
        assertEquals("c", d)
        assertEquals("x y", HtmlText.join("x", "y"))
    }

    @Test fun flowKeepsTitlesWithTextAndSplitsLongParagraphs() {
        val type = FlowType(Grid(BookFormat.A5), BookFonts.Notebook)
        val flow = Flow(type, mono)
        val box = FlowBox(rows = 6, cols = 1, colWidth = 20.0)
        val long = Para(ParaKind.P, List(10) { "word" }.joinToString(" ")) // 49 chars → 3 lines of 4 words (19 mm)
        val title = Para(ParaKind.TITLE, "T")
        // Title (2 lines) + meta (1) + 3 lines = 6: everything fits.
        assertEquals(Fill(3, null, 6), flow.fill(listOf(title, Para(ParaKind.META, "m"), long), box))
        // Two long paragraphs: the second is split after 2 of its 3 lines? It has fewer than 4 lines, so it moves whole.
        val f = flow.fill(listOf(long, long, long), box.copy(rows = 7))
        assertEquals(2, f.count)
        assertEquals(null, f.splitLines)
        // A paragraph of 6 lines starting with 4 free lines is split 4 / 2.
        val six = Para(ParaKind.P, List(24) { "word" }.joinToString(" "))
        val g = flow.fill(listOf(six), box.copy(rows = 4))
        assertEquals(0, g.count)
        assertEquals(4, g.splitLines)
        val (a, b) = flow.split(six, 20.0, false, 4)
        assertEquals(16, flow.plain(a).split(" ").size)
        assertTrue(b.cont)
        // A title at the foot of a page goes to the next page with its text.
        val h = flow.fill(listOf(long, title, long), box)
        assertEquals(1, h.count)
        // Hidden entries take no room.
        val hidden = Flow(type, mono, hidden = setOf("e"))
        assertEquals(3, hidden.fill(listOf(long.copy(entry = "e"), long.copy(entry = "e"), long), box.copy(rows = 3)).count)
    }
}

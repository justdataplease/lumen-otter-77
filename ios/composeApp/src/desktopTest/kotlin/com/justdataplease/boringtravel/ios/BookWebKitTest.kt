package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.book.BookCss
import com.justdataplease.boringtravel.book.BookFixtures
import com.justdataplease.boringtravel.book.BookFonts
import com.justdataplease.boringtravel.book.BookFormat
import com.justdataplease.boringtravel.book.BookHtml
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.BookSpec
import com.justdataplease.boringtravel.book.Face
import com.justdataplease.boringtravel.book.Flow
import com.justdataplease.boringtravel.book.SkiaTextMeasure
import com.justdataplease.boringtravel.book.TextFit
import com.justdataplease.boringtravel.book.phoneTextMeasure
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the book's pages need before WebKit draws them (WebKitPrint's book forms), against the Android app's own
 * stylesheet (book/BookCss.kt, read in) and every page it writes: no line gradient with a clear or see-through stop is
 * left (WebKit's print to PDF draws a gradient's colours solid: a clear stop prints black), the highlighter's bands are
 * one flat colour where they were, the scrim is a picture of itself, each page's section fits WebKit's printed page;
 * and the two measures of the book's text: Skia's on the desktop (the bundled fonts, shaped) and how the book pours with
 * it, and the stand-in drawing the forms WebKit gets.
 */
class BookWebKitTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    /** Every `…gradient(…)` of [css] with its brackets. */
    private fun gradients(css: String): List<String> = Regex("""[a-z-]*gradient\(""").findAll(css).map { m ->
        var depth = 0
        var end = m.range.last
        while (end < css.length) {
            if (css[end] == '(') depth++ else if (css[end] == ')') { depth--; if (depth == 0) break }
            end++
        }
        css.substring(m.range.first, end + 1)
    }.toList()

    /** A gradient WebKit prints right: no clear or see-through colour, and a line gradient of one colour only. */
    private fun printsRight(g: String): Boolean {
        if ("transparent" in g || "rgba(" in g || "hsla(" in g) return false
        if (!g.startsWith("linear-gradient(")) return false
        val colours = g.removePrefix("linear-gradient(").removeSuffix(")").split(',').map { it.trim() }
        return colours.size == 2 && colours[0] == colours[1]
    }

    private val androidSheets = listOf(BookCss.BASE, BookCss.PREVIEW) + PrintTheme.entries.map { BookCss.theme(it) }

    @Test
    fun androidsStylesheetHasTheGradientsThatPrintBlack() {
        val clear = androidSheets.flatMap { gradients(it) }.filterNot(::printsRight)
        // The highlighter's three bands and the scrim (and Scrapbook repeats Notebook's band).
        assertTrue(clear.size >= 4, "$clear")
        assertTrue(clear.any { "rgba(0,0,0,0)" in it }, "the scrim")
        assertTrue(clear.any { "transparent 52%,var(--hl) 52%" in it }, "the marker")
        // Every highlighter is an opaque colour in every look, so a flat band of it is the same band.
        PrintTheme.entries.forEach { t -> assertTrue(Regex("""--hl:#[0-9A-Fa-f]{6}[;}]""").containsMatchIn(BookCss.theme(t)), "$t") }
    }

    @Test
    fun noGradientWithAClearStopIsLeftInWhatWebKitIsGiven() {
        for (theme in PrintTheme.entries) for (mode in BookHtml.Mode.entries) for (format in listOf(BookFormat.A5, BookFormat.Letter)) {
            val layout = BookLayout(BookSpec(format, theme, BookFonts.Theme, bleed = format == BookFormat.Letter), BookFixtures.facts(), TextFit.Estimate, BookFixtures.ids())
            val html = BookHtml.document(layout, layout.auto(), { id, px -> "img/$id-$px.jpg" }, mode, fontBase = "fonts/")
            assertTrue(gradients(html).any { !printsRight(it) }, "Android's page has them ($theme $mode)")
            val sheet = layout.pages.sheet
            for (webkit in listOf(WebKitPrint.forBookPrint(html, sheet.pageW, sheet.pageH), WebKitPrint.forBookScreen(html))) {
                val left = gradients(webkit).filterNot(::printsRight)
                assertTrue(left.isEmpty(), "$theme $mode $format: $left")
                assertTrue("rgba(0,0,0,0)" !in webkit)
            }
        }
    }

    @Test
    fun theBandsAreOneFlatColourWhereTheyWere() {
        val css = WebKitPrint.forBook(BookCss.BASE + BookCss.theme(PrintTheme.Notebook))
        // 52% to 92% of the line: 40% tall, its top 52% down, the position a percentage of the 60% of room left.
        assertTrue(".mk{background:linear-gradient(var(--hl),var(--hl)) no-repeat 0 86.7%/100% 40%;" in css, css)
        assertTrue(".t.mono .mk{background:linear-gradient(var(--hl),var(--hl)) no-repeat 0 77.8%/100% 82%;" in css, css)
        assertTrue(".flow b,.flow strong{font-weight:inherit;background:linear-gradient(var(--hl),var(--hl)) no-repeat 0 87.3%/100% 37%}" in css, css)
        // Nothing else moves.
        val band = Regex("""background:linear-gradient\((transparent [^;}]*|var\(--hl\),var\(--hl\)\) no-repeat [^;}]*)""")
        val scrim = Regex("""background:(linear-gradient\(rgba\(0,0,0,0\),rgba\(0,0,0,\.62\)\)|url\(data:image/png;base64,[A-Za-z0-9+/=]+\) 0 0/100% 100% no-repeat)""")
        val original = BookCss.BASE + BookCss.theme(PrintTheme.Notebook)
        assertEquals(scrim.replace(band.replace(original, "BAND"), "SCRIM"), scrim.replace(band.replace(css, "BAND"), "SCRIM"))
    }

    @Test
    fun theScrimIsAPictureOfItself() {
        val css = WebKitPrint.forBook(BookCss.BASE)
        val data = Regex("""\.shade\{background:url\(data:image/png;base64,([A-Za-z0-9+/=]+)\) 0 0/100% 100% no-repeat}""").find(css)?.groupValues?.get(1)
        assertNotNull(data, css.substringAfter(".shade").take(120))
        val image = Image.makeFromEncoded(java.util.Base64.getDecoder().decode(data))
        assertEquals(2, image.width)
        assertEquals(ScrimPicture.STEPS, image.height)
        val bitmap = Bitmap.makeFromImage(image)
        val alphas = (0 until image.height).map { y -> (bitmap.getColor(0, y) ushr 24) and 0xFF }
        val colours = (0 until image.height).map { y -> bitmap.getColor(1, y) and 0xFFFFFF }
        assertEquals(0, alphas.first())
        assertTrue(abs(alphas.last() - (0.62 * 255).toInt()) <= 1, "${alphas.last()}")
        assertEquals(alphas.sorted(), alphas)
        assertTrue(colours.all { it == 0 }, "black all the way")
        bitmap.close()
        image.close()
    }

    @Test
    fun eachPagesSectionFitsWebKitsPrintedPage() {
        for (format in BookFormat.entries) for (bleed in listOf(false, true)) {
            val sheet = com.justdataplease.boringtravel.book.Sheet(format, bleed)
            val s = BookPrintScale(sheet.pageW, sheet.pageH)
            val pxPerMm = 96.0 / 25.4 * s.zoom
            // As wide as WebKit's page, a pixel shorter, and a printed mm a mm (to WebKit's rounding of a pixel).
            assertEquals(s.layoutWidth, sheet.pageW * pxPerMm, 1e-6, "$format $bleed")
            assertEquals(s.layoutHeight - 1, s.sectionMm * pxPerMm, 1e-6, "$format $bleed")
            assertTrue(sheet.pageH - s.sectionMm in 0.2..0.8, "$format $bleed: ${sheet.pageH - s.sectionMm} mm at the foot")
            assertTrue(s.zoom in 0.93..0.9375, "${s.zoom}")
            val html = WebKitPrint.forBookPrint("<html><head><meta charset=\"utf-8\"><title>t</title></head><body></body></html>", sheet.pageW, sheet.pageH)
            assertTrue(html.contains(".pg{width:${JavaText.fixed(sheet.pageW, 3)}mm!important;height:${JavaText.fixed(s.sectionMm, 3)}mm!important}</style>\n</head>"), html)
        }
    }

    @Test
    fun skiaMeasuresTheBundledFontsShaped() {
        val m = SkiaTextMeasure(BtFontFiles::file)
        val plain = m.widthMm("Lefkes and the Byzantine path", Face.Literata, 10.0, false)
        val bold = m.widthMm("Lefkes and the Byzantine path", Face.Literata, 10.0, true)
        assertTrue(plain > 30 && bold > plain, "$plain $bold")
        // Android's own per-face factors put each face near its real width.
        for (face in Face.entries) {
            val real = m.widthMm("The ferry left Piraeus at seven", face, 10.0, false)
            val estimate = TextFit.Estimate.widthMm("The ferry left Piraeus at seven", face, 10.0, false)
            assertTrue(real / estimate in 0.7..1.3, "$face ${real / estimate}")
        }
        // Kerning applied ("AV" closer than its letters apart), sizes in proportion, the same answer twice.
        assertTrue(m.widthMm("AV", Face.Literata, 20.0, false) < m.widthMm("A", Face.Literata, 20.0, false) + m.widthMm("V", Face.Literata, 20.0, false))
        assertEquals(m.widthMm("Naxos", Face.Caveat, 10.0, false) * 2, m.widthMm("Naxos", Face.Caveat, 20.0, false), 1e-9)
        assertEquals(m.widthMm("Naxos", Face.Caveat, 10.0, true), m.widthMm("Naxos", Face.Caveat, 10.0, true))
    }

    @Test
    fun theBookPoursWithTheDesktopsMeasureAndEveryPageHoldsItsShare() {
        for (format in BookFormat.entries) for (theme in listOf(PrintTheme.Notebook, PrintTheme.Minimal)) {
            val layout = BookLayout(BookSpec(format, theme), BookFixtures.facts(), phoneTextMeasure(), BookFixtures.ids())
            val doc = layout.auto()
            val flow = Flow(layout.pages.type, phoneTextMeasure())
            var text = 0
            doc.pages.forEach { p ->
                val box = layout.pages.flowBox(p) ?: return@forEach
                text++
                val fill = flow.fill(p.paras, box)
                assertEquals(p.paras.size, fill.count, "$format $theme page ${layout.number(doc, p.id)}")
            }
            assertTrue(text > 3)
            // Laid out again with the same measure, nothing moves.
            assertEquals(doc, layout.reflow(doc))
        }
    }

    @Test
    fun theStandInDrawsTheBandsAndTheScrimWebKitIsGiven() {
        val html = """<!doctype html><html><head><meta charset="utf-8"><style>${BookCss.BASE}${BookCss.theme(PrintTheme.Minimal)}</style></head>
            |<body class="bk"><section class="pg" style="width:100mm;height:100mm"><div class="trim" style="left:0mm;top:0mm;width:100mm;height:100mm">
            |<div class="art" style="left:0mm;top:0mm;width:100mm;height:100mm;background:#FFFFFF"></div>
            |<div class="e shade" style="left:0mm;top:0mm;width:100mm;height:100mm"></div>
            |</div></section></body></html>""".trimMargin()
        val drawer = BookPageDrawer { null }
        try {
            val page = drawer.pages(WebKitPrint.forBook(html)).single()
            val image = drawer.draw(page, 4f)
            val bitmap = Bitmap.makeFromImage(image)
            fun grey(y: Int) = bitmap.getColor(200, y) and 0xFF
            assertTrue(grey(2) > 245, "clear at the top: ${grey(2)}")
            assertTrue(grey(398) in 90..105, "62% black at the foot: ${grey(398)}")
            assertTrue(grey(200) in grey(398)..grey(2), "in between")
            bitmap.close()
            image.close()
        } finally {
            drawer.close()
        }
    }
}

package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.export.PrintCss
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The changes WebKit's print needs, against the stylesheet Android prints with (read in at build time). */
class WebKitPrintTest {
    @Test
    fun aMarkersBandBecomesOneFlatColourInTheSamePlace() {
        // 52% to 92%: 40% tall, its top 52% down; the position's percentage is of the 60% of room left.
        assertEquals(
            ".mk{background:linear-gradient(#F7E36A,#F7E36A) no-repeat 0 86.7%/100% 40%;padding:0 .8mm}",
            WebKitPrint.solidBands(".mk{background:linear-gradient(transparent 52%,#F7E36A 52%,#F7E36A 92%,transparent 92%);padding:0 .8mm}"),
        )
        // The short form runs to the bottom.
        assertEquals(
            "b{background:linear-gradient(#F7E36A,#F7E36A) no-repeat 0 100.0%/100% 45%}",
            WebKitPrint.solidBands("b{background:linear-gradient(transparent 55%,#F7E36A 55%)}"),
        )
    }

    @Test
    fun everyBandOfAndroidsStylesheetIsMadeFlat() {
        val sheets = listOf(PrintCss.BASE, PrintCss.POSTCARD, PrintCss.SCRAPBOOK, PrintCss.NOTEBOOK_PAGES, PrintCss.NOTEBOOK_PAPER, PrintCss.MINIMAL, PrintCss.ENTRY_FONTS)
        val bands = sheets.sumOf { Regex("""background:linear-gradient\(transparent""").findAll(it).count() }
        assertTrue(bands >= 4, "Android's stylesheet has the markers' bands ($bands found)")
        sheets.forEach { css ->
            val fixed = WebKitPrint.solidBands(css)
            assertFalse("background:linear-gradient(transparent" in fixed, "a band was left with a clear stop")
            // Nothing else moves: the same text once every band, old or new, is taken out.
            val flat = Regex("""background:linear-gradient\(#[0-9A-Fa-f]+,#[0-9A-Fa-f]+\) no-repeat 0 [\d.]+%/100% \d+%""")
            val band = Regex("""background:linear-gradient\(transparent [^)]*\)""")
            assertEquals(band.replace(css, "BAND"), flat.replace(fixed, "BAND"))
        }
    }

    @Test
    fun otherGradientsAreLeftAlone() {
        val dots = "body{background-image:radial-gradient(circle,rgba(30,42,90,.24) .19mm,transparent .3mm);background-size:5mm 5mm}"
        val ruled = "body.paper-ruled{background-image:repeating-linear-gradient(180deg,transparent 0,transparent 6.55mm,rgba(90,96,110,.24) 6.55mm,rgba(90,96,110,.24) 6.75mm)}"
        val twoColours = "i{background:linear-gradient(transparent 50%,#FFFFFF 50%,#000000 90%,transparent 90%)}"
        assertEquals(dots, WebKitPrint.solidBands(dots))
        assertEquals(ruled, WebKitPrint.solidBands(ruled))
        assertEquals(twoColours, WebKitPrint.solidBands(twoColours))
    }

    @Test
    fun ruledPaperHasNoClearGapsOnAndroidsOwnStylesheet() {
        val fixed = WebKitPrint.solidRuled(PrintCss.NOTEBOOK_PAPER)
        // The gaps are the paper (#FCFCF9); the line is rgba(90,96,110,.24) on that paper: 213, 215, 216.
        assertTrue(
            "body.paper-ruled{background-image:linear-gradient(180deg,#FCFCF9 0,#FCFCF9 6.55mm,#D5D7D8 6.55mm,#D5D7D8 6.75mm);background-size:100% 6.75mm}" in fixed,
            fixed,
        )
        // One line's tile, repeated: no repeating gradient is left.
        assertFalse("repeating-linear-gradient" in fixed, fixed)
        // The dots and everything else are as they were.
        assertEquals(
            Regex("""repeating-linear-gradient\([^)]*\)[^)]*\)[^)]*\);background-size:auto""").replace(PrintCss.NOTEBOOK_PAPER, "RULED"),
            fixed.replace("linear-gradient(180deg,#FCFCF9 0,#FCFCF9 6.55mm,#D5D7D8 6.55mm,#D5D7D8 6.75mm);background-size:100% 6.75mm", "RULED"),
        )
        // And after both changes no line gradient of the notebook has a clear stop left.
        val both = WebKitPrint.forWebKit("<html><head><style>${PrintCss.NOTEBOOK_PAPER}</style></head></html>")
        assertFalse(Regex("""linear-gradient\([^;]*transparent""").containsMatchIn(both), both)
    }

    @Test
    fun thePageGetsItsScaleAndNoSecondMargins() {
        val html = "<html><head><style>.mk{background:linear-gradient(transparent 18%,#F7E36A 18%,#F7E36A 96%,transparent 96%)}</style></head><body></body></html>"
        val out = WebKitPrint.forWebKit(html)
        assertTrue("<style>html{zoom:0.9375}@page{margin:0}</style>\n</head>" in out)
        // 18% to 96%: 78% tall, its top 18% down the 22% of room left.
        assertTrue("background:linear-gradient(#F7E36A,#F7E36A) no-repeat 0 81.8%/100% 78%" in out)
    }
}

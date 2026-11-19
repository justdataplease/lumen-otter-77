package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.backup.ZipReader
import com.justdataplease.boringtravel.compat.ByteArrayOutputStream
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.export.ExportFiles
import com.justdataplease.boringtravel.export.ExportOptions
import com.justdataplease.boringtravel.export.ExportTarget
import com.justdataplease.boringtravel.export.GeoPoint
import com.justdataplease.boringtravel.export.Paper
import com.justdataplease.boringtravel.export.PhotoSize
import com.justdataplease.boringtravel.export.PrintCss
import com.justdataplease.boringtravel.export.Pt
import com.justdataplease.boringtravel.export.RouteSvg
import com.justdataplease.boringtravel.export.TripDocx
import com.justdataplease.boringtravel.export.TripHtml
import com.justdataplease.boringtravel.export.TripText
import com.justdataplease.boringtravel.export.spanTime
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.time.localDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Share's files for a small made-up trip, read in from the Android app (export/) over compat's java.time, java.io and
 * String.format: the plain text, the print and Google Docs HTML, the Word file (read back with this app's own zip
 * reader), file names in any script (\p{L}), the print CSS and the route's numbers.
 */
class ExportSmokeTest {
    private val book = Samples.book()

    @Test fun theTripAsPlainText() {
        val out = TripText.build(book, ExportOptions(includePlan = true), Locale.US)
        val lines = out.lines()
        assertEquals("Naxos, slowly", lines[0])
        assertEquals("Paros → Naxos", lines[1])
        assertEquals("10–14 Sep 2027", lines[2])
        assertTrue(out.contains("\nDay 1 of 5 · Fri 10 Sep 2027\n"), out)
        assertTrue(out.contains("\n07:25  Ferry to Naxos · Piraeus port\n"), out)
        assertTrue(out.contains("\nDay 2 of 5 · Sat 11 Sep 2027 · Naxos port\n"), out)
        assertTrue(out.contains("\n19:30  Dinner by the harbour\n"), out)
        assertTrue(out.contains("\nFerry day\n09:15 · Naxos port\nOn deck\n\nSlept on the deck chairs. Paros got smaller & nothing else happened.\n"), out)
        assertTrue(out.contains("The harbour at dawn"))
        assertTrue(out.contains("[x] Tickets\n[ ] Sunscreen"))
        assertTrue(out.trimEnd().endsWith("Ideas\nKitron tasting in Halki"), out)
        assertFalse(out.contains("Removed earlier"))
        assertFalse(out.contains("<"))
        assertTrue(out.endsWith("\n") && !out.endsWith("\n\n"))
        // Without the plan: the note only, the days without notes left out.
        val notes = TripText.build(book, ExportOptions(), Locale.US)
        assertFalse(notes.contains("Ferry to Naxos"))
        assertFalse(notes.contains("Day 1 of 5"))
        assertTrue(notes.contains("Day 2 of 5"))
    }

    @Test fun datesRangesAndTimes() {
        assertEquals("10–14 Sep 2027", TripHtml.range(localDate(2027, 9, 10), localDate(2027, 9, 14), Locale.US))
        assertEquals("28 Sep – 3 Oct 2027", TripHtml.range(localDate(2027, 9, 28), localDate(2027, 10, 3), Locale.US))
        assertEquals("30 Dec 2027 – 2 Jan 2028", TripHtml.range(localDate(2027, 12, 30), localDate(2028, 1, 2), Locale.US))
        assertEquals("10 Sep 2027", TripHtml.range(localDate(2027, 9, 10), localDate(2027, 9, 10), Locale.US))
        assertEquals("07:25", spanTime(Samples.plan()[0]))
        assertEquals("", spanTime(Samples.plan()[2]))
        assertEquals("\"Naxos \\\"slow\\\" \\3C trip\\3E \"", TripHtml.cssString("Naxos \"slow\" <trip>"))
        assertEquals("\"a\\1 b\\A c\"", TripHtml.cssString("a\u0001b\nc"))
    }

    @Test fun thePrintHtmlAndTheGoogleDocsHtml() {
        val print = TripHtml.build(book, ExportOptions(includePlan = true), { null }, ExportTarget.Print, Locale.US)
        assertTrue(print.startsWith("<!doctype html>\n<html lang=\"en\">"), print.take(200))
        assertTrue(print.contains("<title>Naxos, slowly</title>"))
        assertTrue(print.contains("@page{size:A4;"))
        assertTrue(print.contains("Ferry to Naxos"))
        assertTrue(print.contains("Slept on the deck chairs."))
        assertTrue(print.trimEnd().endsWith("</html>"))
        val docs = TripHtml.build(book, ExportOptions(theme = PrintTheme.Postcard), { null }, ExportTarget.Docs, Locale.US)
        assertTrue(docs.contains("<html"))
        assertFalse(docs.contains("@page"))
        assertTrue(docs.contains("Ferry day"))
    }

    @Test fun theWordFileIsAZipOfWellNamedParts() {
        val out = ByteArrayOutputStream()
        TripDocx.write(book, ExportOptions(includePlan = true), out, { null }, Locale.US)
        val bytes = out.toByteArray()
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
        val zip = ZipReader(bytes.size.toLong()) { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }
        val names = zip.entries.map { it.name }
        assertEquals("[Content_Types].xml", names.first())
        for (part in listOf("_rels/.rels", "docProps/core.xml", "word/styles.xml", "word/document.xml", "word/_rels/document.xml.rels")) {
            assertTrue(part in names, "$part in $names")
        }
        fun text(name: String) = zip.bytesOf(zip.entries.first { it.name == name }, 10_000_000).decodeToString()
        assertTrue(text("docProps/core.xml").contains("Naxos, slowly"))
        val document = text("word/document.xml")
        assertTrue(document.startsWith("<?xml"), document.take(80))
        assertTrue(document.contains("Ferry day"))
        assertTrue(document.contains("Slept on the deck chairs"))
        assertTrue(document.contains("Ferry to Naxos"))
    }

    @Test fun fileNamesInAnyScript() {
        assertEquals("Naxos slow trip 2027.pdf", ExportFiles.fileName("Naxos: «slow» trip / 2027?", "pdf"))
        assertEquals("Νάξος, αργά.docx", ExportFiles.fileName("Νάξος, αργά", "docx"))
        assertEquals("Trip.txt", ExportFiles.fileName("???", "txt"))
        assertEquals(1600 to 1200, ExportFiles.fit(4000, 3000, 1600))
        assertEquals(800 to 600, ExportFiles.fit(800, 600, 1600))
        assertTrue(ExportFiles.isAllowedUrl("data:image/jpeg;base64,AAAA", "/files/export"))
        assertTrue(ExportFiles.isAllowedUrl("file:///files/export/m1.jpg", "/files/export"))
        assertFalse(ExportFiles.isAllowedUrl("file:///files/export/../secret.txt", "/files/export"))
        assertFalse(ExportFiles.isAllowedUrl("https://example.org/m1.jpg", "/files/export"))
        assertEquals(640, ExportFiles.docsMaxPx(PhotoSize.Large, 200))
    }

    @Test fun printCssAndTheRoutesNumbers() {
        val page = PrintCss.page(Paper.A4, PrintTheme.Notebook, "Naxos, slowly")
        assertTrue(page.contains("@page{size:A4;margin:16.0mm 16.0mm 18.0mm;"), page)
        assertTrue(page.contains("content:\"Naxos, slowly\""))
        assertTrue(PrintCss.fonts("file:///fonts/").contains("src:url('file:///fonts/literata.ttf')"))
        assertTrue(PrintCss.docs(PrintTheme.Notebook).contains("font-size:37.8pt"))
        assertEquals(Paper.Letter, Paper.default(Locale.US))
        assertEquals(Paper.A4, Paper.default(Locale.UK))
        assertEquals("2.3", RouteSvg.n(2.25))
        assertEquals("3", RouteSvg.n(3.0))
        assertEquals("-1", RouteSvg.n(-1.04))
        assertEquals(listOf(179.0, 181.0, 182.0), RouteSvg.unwrap(listOf(179.0, -179.0, -178.0)))
        assertEquals(listOf(Pt(0.0, 25.0), Pt(100.0, 25.0)), RouteSvg.project(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0)), 100.0, 50.0, 0.0))
    }
}

package com.justdataplease.boringtravel.export

import com.justdataplease.boringtravel.data.db.Media
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

class TripDocxTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Every fixture photo as a small file of its own bytes, landscape 4:3 except p-ferry (portrait). */
    private fun photos(): Map<String, TripDocx.Photo> = ExportFixtures.book().media.associate { m ->
        val f = tmp.newFile("${m.id}.jpg").apply { writeBytes("jpeg of ${m.id}".toByteArray()) }
        m.id to if (m.id == "p-ferry") TripDocx.Photo(f, 900, 1200) else TripDocx.Photo(f, 1200, 900)
    }

    private fun docx(options: ExportOptions = ExportOptions(), book: TripBook = ExportFixtures.book(), photos: Map<String, TripDocx.Photo> = photos()): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        TripDocx.write(book, options, out, { m: Media -> photos[m.id] }, Locale.UK)
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e -> parts[e.name] = zip.readBytes() }
        }
        return parts
    }

    private fun wellFormed(name: String, bytes: ByteArray) {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)) }.onFailure { throw AssertionError("$name is not well-formed XML: ${it.message}") }
    }

    @Test fun thePartsWordNeedsAreThereAndWellFormed() {
        val parts = docx()
        listOf("[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels")
            .forEach { assertTrue(it, it in parts) }
        assertEquals("[Content_Types].xml", parts.keys.first())
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, bytes) -> wellFormed(name, bytes) }
        val types = String(parts.getValue("[Content_Types].xml"))
        assertTrue(types.contains("Extension=\"jpeg\" ContentType=\"image/jpeg\""))
        assertTrue(types.contains("wordprocessingml.document.main+xml"))
    }

    @Test fun everyPhotoIsReferencedAndInTheZipOnce() {
        val photos = photos()
        val parts = docx(photos = photos)
        val doc = String(parts.getValue("word/document.xml"))
        val rels = String(parts.getValue("word/_rels/document.xml.rels"))
        val used = Regex("r:embed=\"(rIdImg\\d+)\"").findAll(doc).map { it.groupValues[1] }.toSet()
        val targets = Regex("Id=\"(rIdImg\\d+)\" Type=\"[^\"]+/image\" Target=\"(media/image\\d+\\.jpeg)\"").findAll(rels).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(used, targets.keys)
        targets.values.forEach { assertTrue(it, "word/$it" in parts) }
        assertEquals(targets.size, parts.keys.count { it.startsWith("word/media/") })
        // The day's photos and the cover (p-beach): each written once, as given.
        assertEquals(5, targets.size)
        val all = parts.filterKeys { it.startsWith("word/media/") }.values.map { String(it) }.toSet()
        photos.values.forEach { assertTrue(all.contains(it.file.readText())) }
        // docPr ids are unique.
        val ids = Regex("<wp:docPr id=\"(\\d+)\"").findAll(doc).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun photosKeepTheirShapeAndFitThePage() {
        val doc = String(docx().getValue("word/document.xml"))
        val textEmu = (Paper.A4.widthMm - 2 * TripDocx.MARGIN_MM) * 36_000
        val extents = Regex("<wp:extent cx=\"(\\d+)\" cy=\"(\\d+)\"/>").findAll(doc).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        assertTrue(extents.isNotEmpty())
        extents.forEach { (cx, cy) ->
            val a = cx / cy
            assertTrue("$cx×$cy", abs(a - 4 / 3.0) < 0.01 || abs(a - 0.75) < 0.01)
            assertTrue(cx <= textEmu)
        }
        // Nothing is cut: no crop rectangle anywhere.
        assertFalse(doc.contains("srcRect"))
    }

    @Test fun titleDaysNotesAndMarks() {
        val doc = String(docx().getValue("word/document.xml"))
        assertTrue(doc.contains("<w:pStyle w:val=\"Title\"/></w:pPr><w:r><w:t xml:space=\"preserve\">Cyclades, slowly</w:t>"))
        // One heading per day, each on a new page.
        assertEquals(2, Regex("<w:pStyle w:val=\"Heading1\"/><w:pageBreakBefore/>").findAll(doc).count())
        assertTrue(doc.contains("Day 4 of 10 · Sat 19 Jun 2027 · Halki, Naxos"))
        assertTrue(doc.contains("<w:t xml:space=\"preserve\">The bus to Halki</w:t>"))
        assertTrue(doc.contains("<w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">Bought kitron</w:t>"))
        assertTrue(doc.contains("<w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\">Better the second time.</w:t>"))
        assertTrue(doc.contains("☑"))
        assertTrue(doc.contains("Halki square, 11:40"))
        assertFalse(doc.contains("Ferry Paros → Naxos"))
        assertTrue(doc.contains("<w:pgSz w:w=\"11906\" w:h=\"16838\"/>"))
    }

    @Test fun thePlanAsLinesAndLetterPaper() {
        val doc = String(docx(ExportOptions(includePlan = true, paper = Paper.Letter)).getValue("word/document.xml"))
        assertTrue(doc.contains("<w:pStyle w:val=\"Plan\"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">07:30</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t xml:space=\"preserve\">Ferry Paros → Naxos</w:t>"))
        assertTrue(doc.contains("Kitron tasting in Halki"))
        assertTrue(doc.contains("<w:pgSz w:w=\"12240\" w:h=\"15840\"/>"))
    }

    @Test fun textFromTheDatabaseCannotBreakTheXml() {
        val base = ExportFixtures.book(title = "A <b>&</b> \"trip\"\u0001", caption = "cap </w:t> & co")
        val book = base.copy(entries = base.entries.map { it.copy(title = it.title + " <x>&\u0007") })
        val parts = docx(book = book)
        parts.filterKeys { it.endsWith(".xml") }.forEach { (name, bytes) -> wellFormed(name, bytes) }
        val doc = String(parts.getValue("word/document.xml"))
        assertTrue(doc.contains("A &lt;b&gt;&amp;&lt;/b&gt; &quot;trip&quot;</w:t>"))
        assertTrue(String(parts.getValue("docProps/core.xml")).contains("<dc:title>A &lt;b&gt;&amp;&lt;/b&gt; &quot;trip&quot;</dc:title>"))
    }

    @Test fun missingPhotosAreLeftOut() {
        val photos = photos().filterKeys { it != "p-halki" }
        val parts = docx(photos = photos)
        assertEquals(4, parts.keys.count { it.startsWith("word/media/") })
    }

    @Test fun richTextBecomesRuns() {
        val runs = TripDocx.richRuns("a <b>bold <i>both</i></b> <u>u</u><s>s</s><br>e &amp; f &#233;")
        assertEquals(
            listOf(
                TripDocx.Run("a "), TripDocx.Run("bold ", bold = true), TripDocx.Run("both", bold = true, italic = true), TripDocx.Run(" "),
                TripDocx.Run("u", underline = true), TripDocx.Run("s", strike = true), TripDocx.Run("", br = true), TripDocx.Run("e & f é"),
            ),
            runs,
        )
        assertEquals("a b", TripDocx.xml("a\u0000\u001F b"))
        assertEquals("&lt;&amp;&gt;", TripDocx.xml("<&>"))
    }

    @Test fun writesAFileWordCanOpen() {
        // Written to a real file the way the app does, then read back.
        val f = File(tmp.root, "trip.docx")
        val photos = photos()
        f.outputStream().use { TripDocx.write(ExportFixtures.book(), ExportOptions(), it, { m -> photos[m.id] }, Locale.UK) }
        assertTrue(f.length() > 0)
        val names = ZipInputStream(f.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertTrue("word/document.xml" in names)
        assertArrayEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte()), f.readBytes().copyOfRange(0, 2))
    }
}

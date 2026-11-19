package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.art.decomposed
import com.justdataplease.boringtravel.art.oneDecimal
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.geo.Geo
import com.justdataplease.boringtravel.geo.GeoPoint
import com.justdataplease.boringtravel.geo.Polyline
import com.justdataplease.boringtravel.ios.Md5
import com.justdataplease.boringtravel.speech.DictationMerger
import com.justdataplease.boringtravel.speech.DictationTarget
import com.justdataplease.boringtravel.speech.DictationText
import com.justdataplease.boringtravel.tracking.RouteShape
import com.justdataplease.boringtravel.ui.editor.EditorHtml
import com.justdataplease.boringtravel.ui.journal.HtmlRuns
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Text and number helpers the read-in code leans on: accents stripped for Greek and Spanish words, the journal's HTML
 * read into styled runs (an entity beyond the BMP as a surrogate pair), the editor's HTML and the note's blocks, the
 * HTML subset kept safe ((?is) with a backreference, a lookahead), plain text of a note, distances written as Java
 * writes them, the sphere's maths and Google's polylines, dictation's capitals (Greek title case) and MD5.
 */
class TextHelpersSmokeTest {
    @Test fun accentsAreStrippedForGreekAndLatinWords() {
        assertEquals("Λιμνη Βουλιαγμενης", decomposed("Λίμνη Βουλιαγμένης"))
        assertEquals("Peniscola, Castellon", decomposed("Peñíscola, Castellón"))
        assertEquals("μαιου προυπολογισμος", decomposed("μαΐου προϋπολογισμός"))
        assertEquals("Naxos", decomposed("Naxos"))
        assertEquals("Ωρα Αγιος", decomposed("Ώρα Άγιος"))
        assertEquals("37.1", oneDecimal(37.14))
        assertEquals("-0.0", oneDecimal(-0.04))
    }

    @Test fun aParagraphsHtmlIntoStyledRuns() {
        val runs = HtmlRuns.parse(
            "Beach <b>day</b> &#127965; <span style=\"color:#f00;font-size:1.25em\">red</span> <a href=\"https://lefteris.example.com\">link</a><br>next",
        )
        assertEquals("Beach day 🏝 red link\nnext", HtmlRuns.plain(runs))
        assertEquals(HtmlRuns.Run("day", HtmlRuns.Style(bold = true)), runs[1])
        val red = runs.first { it.text == "red" }.style
        assertEquals(0xFFFF0000, red.color)
        assertEquals(HtmlRuns.Size.Em(1.25f), red.size)
        assertEquals("https://lefteris.example.com", runs.first { it.text == "link" }.style.href)
        assertEquals("&lt; 🏝 &bogus;", HtmlRuns.decode("&amp;lt; &#x1F3DD; &bogus;"))
        assertEquals(0x80FF8000, HtmlRuns.parseColor("rgba(255, 128, 0, 0.5)"))
        assertEquals(0xFF112233, HtmlRuns.parseColor("#123"))
        assertEquals(HtmlRuns.Size.Px(16f), HtmlRuns.parseSize("12pt"))
        assertEquals(null, HtmlRuns.parse("<a href=\"javascript:alert(1)\">x</a>").single().style.href)
    }

    @Test fun theEditorsHtmlAndTheNotesBlocks() {
        val blocks = EditorHtml.toBlocks("<h1>Day one</h1><p>Ferry at <b>dawn</b>.</p><ul><li>Hat</li><li>Boots</li></ul><p><br></p><div>Last &#32;line</div>")
        assertEquals(
            listOf(
                Block.Heading("Day one", 2), Block.Paragraph("Ferry at <b>dawn</b>."),
                Block.Paragraph("Hat", ListStyle.Bullet), Block.Paragraph("Boots", ListStyle.Bullet), Block.Paragraph("Last  line"),
            ),
            blocks,
        )
        assertEquals("<h2>Day one</h2><p>Ferry at <b>dawn</b>.</p><ul><li>Hat</li><li>Boots</li></ul><p>Last  line</p>", EditorHtml.fromBlocks(blocks))
        val segments = EditorHtml.segments(listOf(Block.Paragraph("a"), Block.Heading("b"), Block.Divider, Block.Paragraph("c")))
        assertEquals(
            listOf(
                EditorHtml.Segment.Text(listOf(Block.Paragraph("a"), Block.Heading("b"))),
                EditorHtml.Segment.Single(Block.Divider),
                EditorHtml.Segment.Text(listOf(Block.Paragraph("c"))),
            ),
            segments,
        )
        assertEquals("one<br>two", EditorHtml.toInline("<p>one</p><p>two</p>"))
    }

    @Test fun theHtmlSubsetStaysSafe() {
        assertEquals(
            "Hi <b>there</b> &amp; <a>no</a> <a href=\"https://slowroads.example.com\">yes</a>",
            RichHtml.sanitize(
                "<p>Hi <SCRIPT type=\"x\">alert(1)\n</script><b onclick=\"x()\">there</b> & <a href=\"javascript:alert(1)\">no</a> <a href=\"https://slowroads.example.com\">yes</a></p>",
            ),
        )
        assertEquals("<span style=\"color: #f00\">red</span>", RichHtml.sanitize("<span style=\"color:#f00;position:fixed\">red</span>"))
        // Entities stay as written, a stray "<" is escaped, a bare "&" becomes "&amp;" (a lookahead).
        assertEquals("&amp;amp; &lt;b&gt; &#127965; &amp; x", RichHtml.sanitize("&amp;amp; <b&gt; &#127965; & x"))
        assertEquals("A\nB & C", RichHtml.toPlainText("A<br/>B &amp; C"))
        assertEquals("&lt;b&gt; &amp; &quot;x&quot; &#39;y&#39;", RichHtml.escape("<b> & \"x\" 'y'"))
    }

    @Test fun aNotesPlainTextPreviewAndHtml() {
        val doc = EntryDoc(
            blocks = listOf(
                Block.Heading("Naxos"), Block.Paragraph("Ferry at <b>dawn</b> &amp; coffee"),
                Block.Paragraph("Hat", ListStyle.Bullet), Block.Paragraph("One", ListStyle.Number), Block.Paragraph("Two", ListStyle.Number),
                Block.Checklist(listOf(com.justdataplease.boringtravel.doc.CheckItem("Tickets", true), com.justdataplease.boringtravel.doc.CheckItem("Sunscreen"))),
                Block.Photo("m1", caption = "Portara"), Block.Place("Halki"), Block.Divider,
                Block.Photo("m2", rotation = -4.5f, width = 0.75f),
            ),
        )
        assertEquals(
            "Naxos\n\nFerry at dawn & coffee\n\n• Hat\n\n1. One\n\n2. Two\n\n[x] Tickets\n[ ] Sunscreen\n\nPortara\n\nHalki",
            DocRender.plainText(doc),
        )
        assertEquals("Naxos Ferry at dawn &…", DocRender.preview(doc, 24))
        val html = DocRender.html(doc) { id -> "media/$id.jpg" }
        assertTrue(html.contains("<h2>Naxos</h2>"))
        assertTrue(html.contains("<ul><li>Hat</li></ul>\n<ol><li>One</li><li>Two</li></ol>\n"), html)
        assertTrue(html.contains("<ul class=\"check\"><li class=\"done\">Tickets</li><li>Sunscreen</li></ul>"))
        assertTrue(html.contains("<figure class=\"photo frame-none align-center\" style=\"--rot: 0deg; --w: 100%\"><img src=\"media/m1.jpg\" alt=\"Portara\"><figcaption>Portara</figcaption></figure>"), html)
        assertTrue(html.contains("--rot: -4.5deg; --w: 75%") && html.contains("transform:rotate(-4.5deg)"), html)
        assertEquals(EntryDoc(blocks = doc.blocks), EntryDoc.parse(doc.toJson()))
    }

    @Test fun distancesAsJavaWritesThem() {
        assertEquals("0 km", RouteShape.formatDistance(5.0))
        assertEquals("650 m", RouteShape.formatDistance(654.0))
        assertEquals("990 m", RouteShape.formatDistance(999.0))
        assertEquals("18.4 km", RouteShape.formatDistance(18_420.0))
        assertEquals("18.5 km", RouteShape.formatDistance(18_450.0))
        assertEquals("212 km", RouteShape.formatDistance(212_300.0))
        assertEquals("1,234 km", RouteShape.formatDistance(1_234_567.0))
    }

    @Test fun theSpheresMathsAndPolylines() {
        assertTrue(abs(Geo.distanceM(0.0, 0.0, 0.0, 1.0) - 111_195.08) < 1.0, Geo.distanceM(0.0, 0.0, 0.0, 1.0).toString())
        assertEquals(0.0, Geo.distanceM(37.1, 25.37, 37.1, 25.37))
        assertTrue(abs(Geo.bearingDeg(0.0, 0.0, 0.0, 1.0) - 90.0) < 1e-9)
        assertTrue(abs(Geo.bearingDeg(0.0, 0.0, 1.0, 0.0)) < 1e-9)
        assertEquals(20.0, Geo.turnDeg(350.0, 10.0))
        val line = listOf(GeoPoint(37.0, 25.0), GeoPoint(37.00001, 25.05), GeoPoint(37.0, 25.1))
        assertEquals(listOf(line.first(), line.last()), Geo.simplify(line, 10.0))
        val points = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
        val encoded = Polyline.encode(points)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encoded)
        val back = Polyline.decode(encoded)
        assertEquals(points.size, back.size)
        points.zip(back).forEach { (a, b) -> assertTrue(abs(a.lat - b.lat) < 1e-9 && abs(a.lng - b.lng) < 1e-9, "$a $b") }
    }

    private class Box(var value: String) : DictationTarget {
        override val text: String get() = value
        override var caret: Int = value.length

        override fun replace(start: Int, end: Int, with: String) {
            value = value.substring(0, start) + with + value.substring(end)
            caret = start + with.length
        }
    }

    @Test fun dictationJoinsWordsWithGreekCapitals() {
        assertEquals("Καλημέρα", DictationText.format("καλημέρα", "", "", paragraphStart = true))
        assertEquals(" Ναι", DictationText.format("ναι", "Ήρθαμε.", "", paragraphStart = false))
        assertEquals(" world", DictationText.format("world  ", "Hello", "", paragraphStart = false))
        assertEquals(", then", DictationText.format(", then", "Naxos", " port", paragraphStart = false))
        assertEquals("Ώρα", DictationText.capitalizeFirst("ώρα"))
        assertEquals("«Άγιος»", DictationText.capitalizeFirst("«άγιος»"))
        assertEquals("123", DictationText.capitalizeFirst("123"))
        val box = Box("Ferry at dawn")
        val merger = DictationMerger()
        merger.partial(box, "and coff")
        assertEquals("Ferry at dawn and coff", box.value)
        merger.final(box, "and coffee.")
        assertEquals("Ferry at dawn and coffee.", box.value)
        assertTrue(merger.idle)
    }

    @Test fun md5AgainstKnownValues() {
        fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hex(Md5.digest(ByteArray(0))))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex(Md5.digest("abc".encodeToByteArray())))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", hex(Md5.digest("The quick brown fox jumps over the lazy dog".encodeToByteArray())))
    }
}

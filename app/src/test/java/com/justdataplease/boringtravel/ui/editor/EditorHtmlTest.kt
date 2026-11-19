package com.justdataplease.boringtravel.ui.editor

import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.PhotoFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHtmlTest {
    private fun p(html: String, list: ListStyle? = null) = Block.Paragraph(html, list)

    @Test fun paragraphsBecomeBlocks() {
        assertEquals(listOf(p("Hello <b>world</b>"), p("Second")), EditorHtml.toBlocks("<p>Hello <b>world</b></p><p>Second</p>"))
    }

    @Test fun emptyParagraphsAndBreaksAreDropped() {
        assertEquals(listOf(p("a"), p("b")), EditorHtml.toBlocks("<p>a</p><br><p></p><p> </p><p>b</p>"))
        assertEquals(emptyList<Block>(), EditorHtml.toBlocks("<p></p>"))
        assertEquals(emptyList<Block>(), EditorHtml.toBlocks(""))
    }

    @Test fun lineBreaksInsideAParagraphStay() {
        assertEquals(listOf(p("line<br>two")), EditorHtml.toBlocks("<p>line<br>two<br></p>"))
    }

    @Test fun listsKeepTheirStyle() {
        assertEquals(
            listOf(p("one", ListStyle.Bullet), p("two", ListStyle.Bullet), p("x", ListStyle.Number)),
            EditorHtml.toBlocks("<ul><li>one</li><li>two</li></ul><ol><li>x</li></ol>"),
        )
    }

    @Test fun nestedListsAreFlattened() {
        assertEquals(
            listOf(p("a", ListStyle.Bullet), p("b", ListStyle.Bullet), p("c", ListStyle.Bullet)),
            EditorHtml.toBlocks("<ul><li>a<ul><li>b</li></ul></li><li>c</li></ul>"),
        )
    }

    @Test fun headingsBecomeHeadingBlocks() {
        assertEquals(
            listOf(Block.Heading("Big", 2), Block.Heading("Day 4", 2), Block.Heading("Small & bold", 3), p("t")),
            EditorHtml.toBlocks("<h1>Big</h1><h2>Day 4</h2><h3>Small &amp; <b>bold</b></h3><p>t</p>"),
        )
    }

    @Test fun spacesFromTheEditorAreKept() {
        assertEquals(listOf(p("a  b")), EditorHtml.toBlocks("<p>a &#32;b</p>"))
    }

    @Test fun linksAndStylesAreCleaned() {
        val html = """<p><a href="https://example.com" target="_blank">x</a> <span style="color: rgba(196, 66, 58, 1.0); font-size: 1.3em">red</span> <mark>hi</mark></p>"""
        assertEquals(
            listOf(p("""<a href="https://example.com">x</a> <span style="color: rgba(196, 66, 58, 1.0); font-size: 1.3em">red</span> hi""")),
            EditorHtml.toBlocks(html),
        )
    }

    @Test fun textOutsideBlocksIsAParagraph() {
        assertEquals(listOf(p("plain <b>x</b>")), EditorHtml.toBlocks("plain <b>x</b>"))
    }

    @Test fun roundTripIsStable() {
        val blocks = listOf(
            p("a <i>b</i>"),
            p("one", ListStyle.Bullet),
            p("two", ListStyle.Bullet),
            Block.Heading("Day <4>", 3),
            p("1st", ListStyle.Number),
        )
        val html = EditorHtml.fromBlocks(blocks)
        assertEquals("<p>a <i>b</i></p><ul><li>one</li><li>two</li></ul><h3>Day &lt;4&gt;</h3><ol><li>1st</li></ol>", html)
        assertEquals(blocks, EditorHtml.toBlocks(html))
    }

    @Test fun quotesJoinParagraphsWithBreaks() {
        assertEquals("a<br>b <b>c</b>", EditorHtml.toInline("<p>a</p><p>b <b>c</b></p>"))
        assertEquals("<p>a<br>b</p>", EditorHtml.fromInline("a<br>b"))
        assertEquals("", EditorHtml.fromInline(" "))
    }

    @Test fun segmentsGroupTextBlocks() {
        val photo = Block.Photo("m")
        val segments = EditorHtml.segments(listOf(p("a"), Block.Heading("h"), photo, p("b"), Block.Divider))
        assertEquals(
            listOf(
                EditorHtml.Segment.Text(listOf(p("a"), Block.Heading("h"))),
                EditorHtml.Segment.Single(photo),
                EditorHtml.Segment.Text(listOf(p("b"))),
                EditorHtml.Segment.Single(Block.Divider),
            ),
            segments,
        )
    }

    @Test fun photoTiltAlternates() {
        val notebook = PhotoDefaults(0.65f, PhotoFrame.Polaroid, -3f)
        assertEquals(listOf(-3f, 2f, -3f, 2f), (0..3).map(notebook::tiltFor))
        assertEquals(0f, PhotoDefaults(1f, PhotoFrame.None, 0f).tiltFor(1))
    }

    @Test fun draftContent() {
        fun draft(title: String, doc: EntryDoc) = Draft(title, 0L, "UTC", "", null, null, doc)
        assertFalse(draft(" ", EntryDoc()).hasContent)
        assertFalse(draft("", EntryDoc(blocks = listOf(p(" ")))).hasContent)
        assertTrue(draft("Ferry day", EntryDoc()).hasContent)
        assertTrue(draft("", EntryDoc(blocks = listOf(Block.Place("Halki")))).hasContent)
    }

    @Test fun placeLabelPrefersTheTown() {
        assertEquals("Chalki", Places.label("Chalki", "Naxos", "South Aegean", "12"))
        assertEquals("Attica", Places.label(null, " ", "Attica", null))
        assertNull(Places.label(null, null, null, "12"))
    }
}

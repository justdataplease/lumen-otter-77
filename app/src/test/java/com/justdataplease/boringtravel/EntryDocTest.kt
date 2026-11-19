package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.doc.Align
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoFrame
import com.justdataplease.boringtravel.doc.RichHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryDocTest {
    private val sample = EntryDoc(
        font = "literata",
        blocks = listOf(
            Block.Heading("Day 4"),
            Block.Paragraph("Forty minutes <b>late</b> &amp; nobody minded."),
            Block.Photo("m1", rotation = -4f, width = 0.7f, frame = PhotoFrame.Polaroid, caption = "Halki square", align = Align.Start),
            Block.Checklist(listOf(CheckItem("Postcards", true), CheckItem("Bus back"))),
            Block.Place("Halki, Naxos", 37.0587, 25.4807),
            Block.Quote("Slowly."),
            Block.Divider,
        ),
    )

    @Test fun roundTripKeepsEveryBlock() {
        val back = EntryDoc.parse(sample.toJson())
        assertEquals(sample, back)
    }

    @Test fun photoEditsSurviveRepeatedSaveAndReopenBetweenNotes() {
        val edited = EntryDoc("literata", listOf(
            Block.Paragraph("Before the <b>photo</b>"),
            Block.Photo("beach", rotation = -12f, width = 0.65f, frame = PhotoFrame.Rounded,
                caption = "Naxos & the sea — Νάξος", align = Align.End),
            Block.Paragraph("The story continues after the photo."),
            Block.Photo("town", rotation = 8f, width = 0.85f, frame = PhotoFrame.Tape,
                caption = "Town", align = Align.Start),
            Block.Paragraph("Last note."),
        ))
        var reopened = edited
        repeat(3) { reopened = EntryDoc.parse(reopened.toJson()) }
        assertEquals(edited, reopened)
        assertEquals(listOf("beach", "town"), reopened.mediaIds())
        assertTrue(DocRender.plainText(reopened).contains("The story continues after the photo."))
    }

    @Test fun unknownBlocksSurvive() {
        val json = """{"v":1,"blocks":[{"t":"p","html":"a"},{"t":"sketch","strokes":[1,2,3]}]}"""
        val doc = EntryDoc.parse(json)
        assertTrue(doc.blocks[1] is Block.Unknown)
        val again = EntryDoc.parse(doc.toJson())
        assertEquals(doc, again)
        assertTrue(doc.toJson().contains("\"strokes\":[1,2,3]"))
    }

    @Test fun photoValuesAreClamped() {
        val doc = EntryDoc.parse("""{"v":1,"blocks":[{"t":"img","media":"x","rot":40,"w":0.05}]}""")
        val photo = doc.blocks.single() as Block.Photo
        assertEquals(15f, photo.rotation)
        assertEquals(0.3f, photo.width)
    }

    @Test fun plainTextIsReadable() {
        val text = DocRender.plainText(sample)
        assertTrue(text.contains("Forty minutes late & nobody minded."))
        assertTrue(text.contains("[x] Postcards"))
        assertTrue(text.contains("Halki square"))
        assertFalse(text.contains("<b>"))
    }

    @Test fun nonJsonBodyBecomesParagraphs() {
        val doc = EntryDoc.parse("First line\n\nSecond <line>")
        assertEquals(2, doc.blocks.size)
        assertEquals("Second &lt;line&gt;", (doc.blocks[1] as Block.Paragraph).html)
    }

    @Test fun sanitizerKeepsAllowedMarkupOnly() {
        val dirty = """<b onclick="x()">bold</b> <script>alert(1)</script><a href="javascript:x">link</a> <span style="color: #c4423a; position: fixed">red</span> <img src=x onerror=y>"""
        val clean = RichHtml.sanitize(dirty)
        assertEquals("""<b>bold</b> <a>link</a> <span style="color: #c4423a">red</span> """, clean)
    }

    @Test fun htmlEscapesCaptionsAndSkipsMissingPhotos() {
        val doc = EntryDoc(blocks = listOf(Block.Photo("a", caption = "<x>"), Block.Photo("missing")))
        val html = DocRender.html(doc) { id -> if (id == "a") "file:///a.jpg" else null }
        assertTrue(html.contains("<figcaption>&lt;x&gt;</figcaption>"))
        assertEquals(1, Regex("<figure").findAll(html).count())
    }

    @Test fun previewCutsAtAWord() {
        val doc = EntryDoc.ofText("one two three four five six seven")
        assertEquals("one two…", DocRender.preview(doc, max = 10))
    }
}

package com.justdataplease.boringtravel.doc

import com.justdataplease.boringtravel.ui.editor.EditorHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutTest {
    @Test fun printedPageSectionsKeepFontsOpaqueBlocksAndOrderWithoutAccidentalBlankSheets() {
        val opaque = EntryDoc.parse("""{"blocks":[{"t":"future-block","data":"retained"}]}""").blocks.single()
        val first = Block.Paragraph("First page")
        val last = Block.Paragraph("Next page")
        val doc = EntryDoc("caveat", listOf(Block.PageBreak, first, opaque, Block.PageBreak, Block.PageBreak, last, Block.PageBreak))
        val pages = DocRender.printPages(doc)
        assertEquals(listOf(EntryDoc("caveat", listOf(first, opaque)), EntryDoc("caveat", listOf(last))), pages)
        assertEquals(listOf(first, opaque, last), pages.flatMap { it.blocks })
    }

    @Test fun pageBreakPhotoPositionAndUnknownContentSurviveRepeatedRoundTrips() {
        val unknown = EntryDoc.parse("""{"v":2,"blocks":[{"t":"future-collage","payload":{"layers":[1,2]}}]}""").blocks.single()
        val original = EntryDoc("literata", listOf(
            Block.Paragraph("Before <b>the photo</b>"),
            Block.Photo("p", rotation = -8f, width = 0.6f, frame = PhotoFrame.Polaroid, caption = "The harbour", position = 0.73f),
            Block.PageBreak, unknown, Block.Paragraph("A fresh page."),
        ))
        var reopened = original
        repeat(4) { reopened = EntryDoc.parse(reopened.toJson()) }
        assertEquals(original, reopened)
        assertTrue(reopened.toJson().contains("\"layers\":[1,2]"))
        assertEquals(listOf("p"), reopened.mediaIds())
        assertFalse(DocRender.plainText(reopened).contains("page break", ignoreCase = true))
    }

    @Test fun printHtmlKeepsOrderAndUsesRealPageBreaksWithoutPrintingADivider() {
        val doc = EntryDoc(blocks = listOf(Block.Paragraph("Before"), Block.PageBreak, Block.Paragraph("After")))
        val html = DocRender.html(doc) { null }
        assertTrue(html.indexOf("Before") < html.indexOf("page-break"))
        assertTrue(html.indexOf("page-break") < html.indexOf("After"))
        assertTrue(html.contains("break-after:page;page-break-after:always"))
        assertFalse(html.contains("<hr"))
        val segments = EditorHtml.segments(doc.blocks)
        assertEquals(3, segments.size)
        assertEquals(EditorHtml.Segment.Single(Block.PageBreak), segments[1])
    }

    @Test fun positionedPhotoEmitsStablePercentMarginsAndActualAspect() {
        val photo = Block.Photo("p", width = 0.6f, position = 0.75f)
        val html = DocRender.html(EntryDoc(blocks = listOf(photo)), mediaAspect = { 1.5f }) { "file:///p.jpg" }
        assertTrue(html.contains("width:60%"))
        assertTrue(html.contains("margin-left:30%"))
        assertTrue(html.contains("float:none;clear:both"))
        val rotated = DocRender.html(EntryDoc(blocks = listOf(photo.copy(width = 1f, rotation = 15f))), mediaAspect = { 0.5f }) { "file:///p.jpg" }
        assertTrue(rotated.contains("transform:rotate(15deg)"))
        assertFalse(rotated.contains(";width:100%"))
    }

    @Test fun importedPositionsClampAndNonFiniteValuesFallBack() {
        val bounded = EntryDoc.parse("""{"blocks":[{"t":"img","media":"p","x":8,"w":5,"rot":-100}]}""").blocks.single() as Block.Photo
        assertEquals(1f, bounded.position)
        assertEquals(1f, bounded.width)
        assertEquals(-15f, bounded.rotation)
        val safe = EntryDoc.parse("""{"blocks":[{"t":"img","media":"p","x":"NaN","w":"NaN","rot":"Infinity"}]}""").blocks.single() as Block.Photo
        assertEquals(null, safe.position)
        assertEquals(1f, safe.width)
        assertEquals(0f, safe.rotation)
    }

    @Test fun legacyAuthoredAngleAndAlignmentAreNotLostOutsideScrapbookTheme() {
        val legacy = Block.Photo("p", rotation = -7f, width = 0.5f, align = Align.End)
        val html = DocRender.html(EntryDoc(blocks = listOf(legacy))) { "file:///p.jpg" }
        assertTrue(html.contains("transform:rotate(-7deg)"))
        assertTrue(html.contains("margin-left:"))
        assertTrue(html.contains("float:none;clear:both"))
    }
}

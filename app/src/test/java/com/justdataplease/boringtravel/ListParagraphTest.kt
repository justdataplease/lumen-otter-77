package com.justdataplease.boringtravel

import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.DocRender
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paragraphs marked as list items (Wave 2B): stored as an optional "list" key, older entries unchanged. */
class ListParagraphTest {
    private val doc = EntryDoc(
        blocks = listOf(
            Block.Paragraph("Bread", ListStyle.Bullet),
            Block.Paragraph("Milk", ListStyle.Bullet),
            Block.Paragraph("Go", ListStyle.Number),
            Block.Paragraph("Swim", ListStyle.Number),
            Block.Paragraph("after"),
            Block.Paragraph("Again", ListStyle.Number),
        ),
    )

    @Test fun listItemsRoundTrip() {
        val json = doc.toJson()
        assertEquals(doc, EntryDoc.parse(json))
        assertTrue(json.contains("\"list\":\"ul\""))
        assertTrue(json.contains("\"list\":\"ol\""))
        assertTrue(EntryDoc(blocks = listOf(Block.Paragraph("x"))).toJson().let { !it.contains("list") })
    }

    @Test fun olderAndUnknownValuesReadAsPlainParagraphs() {
        assertNull((EntryDoc.parse("""{"v":1,"blocks":[{"t":"p","html":"x"}]}""").blocks.single() as Block.Paragraph).list)
        assertNull((EntryDoc.parse("""{"v":1,"blocks":[{"t":"p","html":"x","list":"zz"}]}""").blocks.single() as Block.Paragraph).list)
    }

    @Test fun plainTextShowsBulletsAndNumbers() {
        assertEquals("• Bread\n\n• Milk\n\n1. Go\n\n2. Swim\n\nafter\n\n1. Again", DocRender.plainText(doc))
    }

    @Test fun htmlGroupsListItems() {
        assertEquals(
            "<ul><li>Bread</li><li>Milk</li></ul>\n<ol><li>Go</li><li>Swim</li></ol>\n<p>after</p>\n<ol><li>Again</li></ol>\n",
            DocRender.html(doc) { null },
        )
    }
}

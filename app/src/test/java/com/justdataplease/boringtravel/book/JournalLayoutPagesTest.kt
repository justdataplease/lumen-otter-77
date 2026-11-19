package com.justdataplease.boringtravel.book

import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.export.TripBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An entry's photo layout in the book's first layout: its photos together on album pages in its arrangement. */
class JournalLayoutPagesTest {
    private fun withBody(book: TripBook, entryId: String, doc: EntryDoc) =
        book.copy(entries = book.entries.map { if (it.id == entryId) it.copy(body = doc.toJson()) else it })

    private fun auto(book: TripBook) = BookLayout(BookSpec(), BookFixtures.facts(book), TextFit.Estimate, BookFixtures.ids()).auto()

    @Test fun aLayoutBecomesAnAlbumPageAfterItsText() {
        val book = withBody(BookFixtures.book(), "e3", EntryDoc(blocks = listOf(
            Block.Paragraph("Same rocks in the morning and again at six."),
            Block.Album("3-side", listOf("kolymb", "kolymb2", "naousa")),
        )))
        val doc = auto(book)
        val e3 = doc.pages.filter { it.entryId == "e3" }
        assertEquals(listOf(Template.Text.id, Template.Album.id), e3.map { it.templateId })
        assertEquals("3-side", e3[1].album)
        assertEquals(listOf("kolymb", "kolymb2", "naousa"), e3[1].slots.map { it.mediaId })
        // Every photo of the trip is still placed, once.
        val placed = doc.pages.drop(1).flatMap { p -> p.slots.mapNotNull { it.mediaId } }
        assertEquals(BookFixtures.photoSizes.keys, placed.toSet())
        assertEquals(placed.size, placed.toSet().size)
    }

    @Test fun aSinglePhotoKeepsItsTextAndTheLayoutFollowsInOrder() {
        val book = withBody(BookFixtures.book(), "e3", EntryDoc(blocks = listOf(
            Block.Paragraph("Same rocks twice."),
            Block.Album("2-stack", listOf("kolymb2", "naousa")),
            Block.Photo("kolymb", caption = "Morning"),
        )))
        val e3 = auto(book).pages.filter { it.entryId == "e3" }
        assertEquals(listOf(Template.PhotoText.id, Template.Album.id), e3.map { it.templateId })
        assertEquals("kolymb", e3[0].slots.single().mediaId)
        assertEquals("Morning", e3[0].slots.single().caption)
        assertEquals("2-stack", e3[1].album)
        assertEquals(listOf("kolymb2", "naousa"), e3[1].slots.map { it.mediaId })
    }

    @Test fun photosTheTripNoLongerHasLeaveTheLayoutSmaller() {
        val book = withBody(BookFixtures.book(), "e3", EntryDoc(blocks = listOf(
            Block.Album("4-grid", listOf("kolymb", "gone", "kolymb2", "naousa")),
        )))
        val e3 = auto(book).pages.filter { it.entryId == "e3" }
        // The title and time line, then the arrangement for the three photos that are left (no empty spot in print).
        assertEquals(listOf(Template.Text.id, Template.Album.id), e3.map { it.templateId })
        assertEquals(listOf("kolymb", "kolymb2", "naousa"), e3[1].slots.map { it.mediaId })
        assertEquals(3, e3[1].albumLayout!!.count)
    }

    @Test fun textWithOnlyALayoutGoesOnATextPageRightBeforeIt() {
        val book = withBody(BookFixtures.book(), "e6", EntryDoc(blocks = listOf(
            Block.Paragraph("Half of Naxos walks out to the marble door at sunset."),
            Block.Album("2-side", listOf("portara2", "portara")),
        )))
        val pages = auto(book).pages
        val album = pages.single { it.album == "2-side" }
        val before = pages[pages.indexOf(album) - 1]
        assertEquals(Template.Text.id, before.templateId)
        assertEquals(listOf("portara2", "portara"), album.slots.map { it.mediaId })
        assertTrue(before.paras.any { "marble door" in it.html })
    }
}

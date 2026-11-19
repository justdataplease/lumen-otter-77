package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SiteNotesTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val post = SitePost(
        id = "1001", url = "https://wanderingfeet.tumblr.com/post/1001", title = "Kyoto at last", at = Instant.parse("2016-04-07T03:33:20Z"),
        blocks = listOf(
            SiteBlock.Heading("Day one", 2),
            SiteBlock.Text("We landed <b>at dawn</b>."),
            SiteBlock.Picture("https://cdn.example.com/a.jpg", "The gate"),
            SiteBlock.Quote("Arigato"),
            SiteBlock.Picture("https://cdn.example.com/b.jpg"),
            SiteBlock.Text("Rail pass", ListStyle.Bullet),
            SiteBlock.Divider,
            SiteBlock.Picture("https://cdn.example.com/a.jpg"),
        ),
        tags = listOf("japan", "#kyoto", "spring trip", "japan"),
    )

    @Test fun idsComeFromAddresses() {
        val id = SiteNotes.entryId(post)
        assertEquals(id, SiteNotes.entryId(post.copy(url = "http://www.wanderingfeet.tumblr.com/post/1001/#notes")))
        assertEquals(id, SiteNotes.entryId(post.copy(url = "https://WanderingFeet.tumblr.com/post/1001/", title = "Changed")))
        assertNotEquals(id, SiteNotes.entryId(post.copy(url = "https://wanderingfeet.tumblr.com/post/1002")))
        assertNotEquals(SiteNotes.entryId(post.copy(url = "https://blog.example.com/?p=1")), SiteNotes.entryId(post.copy(url = "https://blog.example.com/?p=2")))
        assertEquals("3a1c5f2b-0d4e-3a4c-9d1a-000000000000".length, id.length)

        val pictures = SiteNotes.pictureIds(post, id)
        assertEquals(3, pictures.size)
        assertEquals(3, pictures.toSet().size) // the same picture twice in one post: two photos
        assertEquals(pictures, SiteNotes.pictureIds(post, id))
        assertNotEquals(pictures, SiteNotes.pictureIds(post, SiteNotes.entryId(post.copy(url = "https://other.example.com/x"))))
    }

    @Test fun theNoteIsThePostInOrderWithNothingAdded() {
        val id = SiteNotes.entryId(post)
        val pictures = SiteNotes.pictureIds(post, id)
        val note = SiteNotes.note(post, id, "trip-1", zone, undatedAt = 0L, kept = pictures.toSet(), now = 5L)
        assertEquals(
            listOf(
                Block.Heading("Day one", 2),
                Block.Paragraph("We landed <b>at dawn</b>."),
                Block.Photo(pictures[0], caption = "The gate"),
                Block.Quote("Arigato"),
                Block.Photo(pictures[1]),
                Block.Paragraph("Rail pass", ListStyle.Bullet),
                Block.Divider,
                Block.Photo(pictures[2]),
                Block.Paragraph("#japan #kyoto #spring trip"),
            ),
            note.doc.blocks,
        )
        val e = note.entry
        assertEquals(id, e.id)
        assertEquals("trip-1", e.tripId)
        assertEquals("Kyoto at last", e.title)
        assertEquals(Instant.parse("2016-04-07T03:33:20Z").toEpochMilli(), e.occurredAt)
        assertEquals("Europe/Athens", e.zoneId)
        assertEquals(5L, e.createdAt)
        assertEquals("", e.placeName)
        assertNull(e.weather)
        assertEquals(note.doc, EntryDoc.parse(e.body))
        assertEquals("Day one\n\nWe landed at dawn.\n\nThe gate\n\nArigato\n\n• Rail pass\n\n#japan #kyoto #spring trip", e.plainText)
    }

    @Test fun picturesThatFailedAreLeftOut() {
        val id = SiteNotes.entryId(post)
        val pictures = SiteNotes.pictureIds(post, id)
        val note = SiteNotes.note(post, id, "trip-1", zone, 0L, kept = setOf(pictures[1]), now = 5L)
        assertEquals(listOf(pictures[1]), note.doc.mediaIds())
        assertEquals(7, note.doc.blocks.size)
    }

    @Test fun undatedPostsAndPostsWithoutTags() {
        val bare = post.copy(at = null, tags = emptyList(), blocks = listOf(SiteBlock.Text("Hi")))
        val note = SiteNotes.note(bare, "e", "t", zone, undatedAt = 1234L, kept = emptySet(), now = 5L)
        assertEquals(1234L, note.entry.occurredAt)
        assertEquals(listOf<Block>(Block.Paragraph("Hi")), note.doc.blocks)
        assertNull(SiteNotes.tagLine(listOf(" ", "#")))
    }

    @Test fun textIsSanitizedOnTheWayIn() {
        val hostile = post.copy(blocks = listOf(SiteBlock.Text("<script>x()</script>Hi <a href=\"javascript:x()\">there</a>")), tags = emptyList())
        val note = SiteNotes.note(hostile, "e", "t", zone, 0L, emptySet(), 5L)
        assertEquals(listOf<Block>(Block.Paragraph("Hi <a>there</a>")), note.doc.blocks)
    }
}

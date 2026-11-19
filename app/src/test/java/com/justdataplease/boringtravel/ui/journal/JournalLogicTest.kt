package com.justdataplease.boringtravel.ui.journal

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.ListStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class JournalLogicTest {
    private fun d(day: Int, month: Int = 6) = LocalDate.of(2027, month, day)

    @Test fun metaLine() {
        assertEquals("Naxos · 27° · 3 photos", JournalLogic.meta("Naxos", 26.6, 3))
        assertEquals("1 photo", JournalLogic.meta(" ", null, 1))
        assertEquals("", JournalLogic.meta("", null, 0))
        assertEquals("Naxos · 2 photos, 1 video", JournalLogic.meta("Naxos", null, 2, 1))
        assertEquals("3 videos", JournalLogic.meta("", null, 0, 3))
    }

    @Test fun photosAndVideosAreCountedApart() {
        assertNull(JournalLogic.counts(0, 0))
        assertEquals("1 photo", JournalLogic.counts(1, 0))
        assertEquals("1 video", JournalLogic.counts(0, 1))
        assertEquals("2 photos, 1 video", JournalLogic.counts(2, 1))
        assertEquals("1 photo, 2 videos", JournalLogic.counts(1, 2))
        // An entry's list line: the ids that are videos count as videos.
        val doc = EntryDoc(blocks = listOf(Block.Photo("p1"), Block.Photo("v1"), Block.Album("2-row", listOf("p2", "p3"))))
        val entry = Entry("e", "t", 0, "UTC", body = doc.toJson(), placeName = "Naxos", createdAt = 1, updatedAt = 1)
        assertEquals("Naxos · 3 photos, 1 video", EntryInfo(entry, videoIds = setOf("v1", "elsewhere")).meta)
        assertEquals("Naxos · 4 photos", EntryInfo(entry).meta)
    }

    @Test fun aVideoAddedSaysItTakesAWhileToUpload() {
        // Nothing to say when the note stays on the phone.
        assertNull(JournalLogic.videoAdded(4 * 60_000L, uploads = false))
        assertEquals("Videos take a while to upload.", JournalLogic.videoAdded(12_000, uploads = true))
        assertEquals("Videos take a while to upload.", JournalLogic.videoAdded(60_000, uploads = true))
        assertEquals("Videos take a while to upload.", JournalLogic.videoAdded(null, uploads = true))
        assertEquals("This video is 4 min long. It will take a while to upload.", JournalLogic.videoAdded(4 * 60_000L + 5_000, uploads = true))
        assertEquals("This video is 1 min long. It will take a while to upload.", JournalLogic.videoAdded(75_000, uploads = true))
    }

    @Test fun piecesGroupListItems() {
        val a = Block.Paragraph("a")
        val b = Block.Paragraph("b", ListStyle.Bullet)
        val c = Block.Paragraph("c", ListStyle.Bullet)
        val n = Block.Paragraph("d", ListStyle.Number)
        assertEquals(
            listOf(
                JournalLogic.Piece.One(0, a),
                JournalLogic.Piece.Items(ListStyle.Bullet, listOf(1 to b, 2 to c)),
                JournalLogic.Piece.Items(ListStyle.Number, listOf(3 to n)),
                JournalLogic.Piece.One(4, Block.Divider),
            ),
            JournalLogic.pieces(listOf(a, b, c, n, Block.Divider)),
        )
    }

    private val zone = ZoneId.of("Europe/Athens")
    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2027, 6, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun entry(id: String, time: Long, vararg photos: String) = Entry(
        id = id, occurredAt = time, zoneId = zone.id,
        body = EntryDoc(blocks = photos.map { Block.Photo(it) }).toJson(), createdAt = 0, updatedAt = 0,
    )

    private fun media(id: String, takenAt: Long?) = Media(id = id, localPath = "media/$id.jpg", takenAt = takenAt, createdAt = 0, updatedAt = 0)

    @Test fun photoCellsNewestFirstWithoutMissingFiles() {
        val infos = listOf(EntryInfo(entry("e1", at(19, 13, 10), "m1", "m2")), EntryInfo(entry("e2", at(18, 20, 0), "m3", "m4")))
        val all = listOf(media("m1", at(19, 11, 40)), media("m2", null), media("m3", at(18, 9, 0)))
        val cells = JournalLogic.photoCells(infos, all)
        assertEquals(listOf("m2", "m1", "m3"), cells.map { it.mediaId })
        assertEquals(listOf("e1", "e1", "e2"), cells.map { it.entryId })

        val days = JournalLogic.photoDays(cells)
        assertEquals(listOf(d(18), d(19)), days.map { it.first })
        assertEquals(listOf("m1", "m2"), days[1].second.map { it.mediaId })
        assertEquals("1 photo", EntryInfo(entry("e3", at(17, 8, 0), "m9")).meta)
    }

    @Test fun photosOfTheTripThatAreInNoEntryShowWhenAskedFor() {
        val infos = listOf(EntryInfo(entry("e1", at(19, 13, 10), "m1")))
        val fromPhone = media("p1", at(20, 9, 0))
        val abandoned = media("a1", at(20, 10, 0)).copy(entryId = "e9")
        val video = media("v1", at(20, 11, 0)).copy(mime = "video/mp4")
        val gone = media("g1", at(20, 12, 0)).copy(deletedAt = 5)
        val all = listOf(media("m1", at(19, 11, 40)), fromPhone, abandoned, video, gone)

        // As before without a zone: only what the entries hold.
        assertEquals(listOf("m1"), JournalLogic.photoCells(infos, all).map { it.mediaId })

        val cells = JournalLogic.photoCells(infos, all, looseZone = zone.id)
        assertEquals(listOf("p1", "m1"), cells.map { it.mediaId })
        assertNull(cells[0].entryId)
        assertEquals(d(20), cells[0].date)
    }

    @Test fun tripDayNumbers() {
        assertEquals(4, JournalLogic.tripDay(d(16), d(25), d(19)))
        assertNull(JournalLogic.tripDay(d(16), d(25), d(26)))
        assertNull(JournalLogic.tripDay(null, null, d(19)))
    }
}

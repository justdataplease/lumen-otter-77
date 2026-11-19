package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.backup.Snapshot
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoAlbums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Deleting a trip takes everything in it (marked deleted, stamped forward, so backups and other phones keep it
 * deleted); a photo goes from the entries that show it; the app's own files go, nothing else.
 */
class TripRemovalTest {
    @get:Rule val tmp = TemporaryFolder()

    private val trip = Trip("t1", "Argentina", createdAt = 10, updatedAt = 50)
    private fun item(id: String, trip: String = "t1", updated: Long = 20, deleted: Long? = null) =
        PlanItem(id, trip, title = id, createdAt = 10, updatedAt = updated, deletedAt = deleted)
    private fun entry(id: String, trip: String? = "t1", updated: Long = 20, deleted: Long? = null) =
        Entry(id, trip, occurredAt = 1, zoneId = "UTC", createdAt = 10, updatedAt = updated, deletedAt = deleted)
    private fun photo(id: String, trip: String? = "t1", entry: String? = null, path: String = "media/$id.jpg", updated: Long = 20, deleted: Long? = null) =
        Media(id, trip, entry, path, createdAt = 10, updatedAt = updated, deletedAt = deleted)
    private fun stop(id: String, trip: String? = "t1", updated: Long = 20, deleted: Long? = null) =
        Stop(id, trip, arrivedAt = 1, lat = 1.0, lng = 2.0, createdAt = 10, updatedAt = updated, deletedAt = deleted)

    /** A clock that says 100, and never earlier than a row's last change + 1 (as Stamps.next). */
    private val stamp: (Long) -> Long = { maxOf(100, it + 1) }

    @Test
    fun aTripGoesWithEverythingInIt() {
        val m = TripRemoval.mark(
            trip,
            items = listOf(item("i1"), item("i2"), item("other", trip = "t2")),
            entries = listOf(entry("e1"), entry("loose", trip = null), entry("e-other", trip = "t2")),
            media = listOf(
                photo("p1"),
                // A photo of one of the trip's entries, filed under no trip: it goes too.
                photo("p2", trip = null, entry = "e1"),
                photo("p-other", trip = "t2"),
                photo("p-loose", trip = null),
            ),
            stops = listOf(stop("s1"), stop("s-other", trip = "t2")),
            stamp = stamp,
        )
        assertEquals(100L, m.trip.deletedAt)
        assertEquals(100L, m.trip.updatedAt)
        assertEquals(listOf("i1", "i2"), m.items.map { it.id })
        assertEquals(listOf("e1"), m.entries.map { it.id })
        assertEquals(listOf("p1", "p2"), m.media.map { it.id })
        assertEquals(listOf("s1"), m.stops.map { it.id })
        (m.items.map { it.deletedAt to it.updatedAt } + m.entries.map { it.deletedAt to it.updatedAt } +
            m.media.map { it.deletedAt to it.updatedAt } + m.stops.map { it.deletedAt to it.updatedAt })
            .forEach { (deleted, updated) -> assertEquals(100L, deleted); assertEquals(100L, updated) }
    }

    @Test
    fun stampsNeverGoBackAndOldDeletionsKeepTheirs() {
        val m = TripRemoval.mark(
            trip.copy(updatedAt = 500),
            items = listOf(item("later", updated = 300), item("gone", deleted = 40, updated = 40)),
            entries = listOf(entry("e1", updated = 700)),
            // The same photo found by trip and by entry, and one deleted before.
            media = listOf(photo("p1", entry = "e1"), photo("p1", entry = "e1"), photo("old", deleted = 30, updated = 30)),
            stops = emptyList(),
            stamp = stamp,
        )
        // A row changed after "now" (another phone's clock) is stamped after that change, so the deletion wins a merge.
        assertEquals(501L, m.trip.updatedAt)
        assertEquals(listOf("later"), m.items.map { it.id })
        assertEquals(301L, m.items.single().updatedAt)
        assertEquals(701L, m.entries.single().deletedAt)
        // Already deleted rows are not written again; one photo is written once.
        assertEquals(listOf("p1"), m.media.map { it.id })
    }

    @Test
    fun onlyTheAppsOwnUnusedFilesAreFreed() {
        val deleted = listOf(
            photo("a", deleted = 1),
            photo("b", path = "media/shared.jpg", deleted = 1),
            photo("c", path = "media/c.jpg", deleted = 1),
            photo("c2", path = "media/c.jpg", deleted = 1),
            photo("d", path = "../databases/boringtravel.db", deleted = 1),
            photo("e", path = "/sdcard/DCIM/e.jpg", deleted = 1),
            photo("f", path = "clips/f.mp4", deleted = 1),
            photo("g", path = "media/../g.jpg", deleted = 1),
            photo("live"),
        )
        assertEquals(
            listOf("media/a.jpg", "media/c.jpg"),
            TripRemoval.filesToFree(deleted, livePaths = setOf("media/shared.jpg")),
        )
    }

    @Test
    fun theTripVideosFilesGoAndNoOtherTrips() {
        val files = tmp.newFolder("files")
        fun write(path: String) = File(files, path).apply { parentFile?.mkdirs(); writeText("x") }
        write("clip-edits/t1.json")
        write("clip-voice/t1/a.m4a")
        write("clip-media/t1/v.mp4")
        write("clip-media/t1/w.mp4")
        val keep = listOf(write("clip-edits/t2.json"), write("clip-voice/t2/a.m4a"), write("media/p.jpg"))

        assertEquals(4, TripRemoval.deleteClipFiles(files, "t1"))
        assertFalse(File(files, "clip-edits/t1.json").exists())
        assertFalse(File(files, "clip-voice/t1").exists())
        assertFalse(File(files, "clip-media/t1").exists())
        keep.forEach { assertTrue(it.path, it.isFile) }
        // Nothing there: nothing to do.
        assertEquals(0, TripRemoval.deleteClipFiles(files, "t3"))
    }

    @Test
    fun aTripsSavedClipsGoAndOthersStay() {
        val dir = tmp.newFolder("clips")
        fun clip(id: String, trip: String) {
            SavedClipIndex.video(dir, id).writeBytes(byteArrayOf(1))
            SavedClipIndex.thumb(dir, id).writeBytes(byteArrayOf(1))
            SavedClipIndex.meta(dir, id).writeText(SavedClipIndex.encode(SavedClip(id, trip, "Trip", createdAt = 5)))
        }
        clip("a", "t1")
        clip("b", "t1")
        clip("c", "t2")
        assertEquals(setOf("a", "b"), SavedClipIndex.deleteTrip(dir, "t1").toSet())
        assertEquals(listOf("c"), SavedClipIndex.list(dir).map { it.id })
        assertFalse(SavedClipIndex.video(dir, "a").exists())
        assertFalse(SavedClipIndex.thumb(dir, "b").exists())
    }

    @Test
    fun aDeletedPhotoLeavesTheEntry() {
        val three = PhotoAlbums.usual(3)
        val doc = EntryDoc(
            blocks = listOf(
                Block.Paragraph("Morning"),
                Block.Photo("p1", rotation = 3f),
                Block.Album(three.id, listOf("p2", "p3", "p4")),
                Block.Album(PhotoAlbums.usual(2).id, listOf("p5", "p6")),
                Block.Photo("p7"),
            ),
        )
        // A single photo goes; the text stays.
        assertEquals(listOf("p2", "p3", "p4", "p5", "p6", "p7"), TripRemoval.withoutPhoto(doc, "p1").mediaIds())
        // A layout keeps its other photos in order, in an arrangement that holds them.
        val album = TripRemoval.withoutPhoto(doc, "p3").blocks.filterIsInstance<Block.Album>().first()
        assertEquals(listOf("p2", "p4"), album.mediaIds)
        assertTrue(AlbumLayouts.of(album.layoutId)!!.count >= 2)
        // A layout left with one photo becomes that photo.
        val single = TripRemoval.withoutPhoto(doc, "p5")
        assertEquals(Block.Photo("p6"), single.blocks[3])
        assertEquals(5, single.blocks.size)
        // … in the frame it had there; a deleted photo's frame goes with it.
        val polaroid = com.justdataplease.boringtravel.doc.PhotoFrame.Polaroid
        val framed = EntryDoc(blocks = listOf(Block.Album(PhotoAlbums.usual(2).id, listOf("a", "b")).withFrame("b", polaroid)))
        assertEquals(Block.Photo("b", frame = polaroid), TripRemoval.withoutPhoto(framed, "a").blocks.single())
        val four = EntryDoc(blocks = listOf(Block.Album(PhotoAlbums.usual(4).id, listOf("a", "b", "c", "d")).withFrame(polaroid)))
        assertEquals(setOf("a", "c", "d"), (TripRemoval.withoutPhoto(four, "b").blocks.single() as Block.Album).frames.keys)
        // Not in it: the same document.
        assertSame(doc, TripRemoval.withoutPhoto(doc, "nope"))
        // A layout with nothing left goes.
        val lone = EntryDoc(blocks = listOf(Block.Album(three.id, listOf("x"))))
        assertTrue(TripRemoval.withoutPhoto(lone, "x").blocks.isEmpty())
    }

    @Test
    fun backupCountsLeaveOutWhatIsInADeletedTrip() {
        val counts = Snapshot(
            trips = listOf(trip, Trip("gone", "Left", createdAt = 1, updatedAt = 2, deletedAt = 2)),
            entries = listOf(entry("e1"), entry("e2", trip = "gone"), entry("loose", trip = null), entry("x", deleted = 3)),
            media = listOf(photo("p1"), photo("p2", trip = "gone"), photo("p3", trip = null)),
        ).items()
        assertEquals(1, counts.trips)
        assertEquals(2, counts.entries)
        assertEquals(2, counts.photos)
    }
}

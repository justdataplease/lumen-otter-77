package com.justdataplease.boringtravel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavedClipsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun clip(id: String, trip: String = "t1", at: Long = 1_000L) =
        SavedClip(id = id, tripId = trip, title = "Argentina", createdAt = at, durationMs = 42_000, width = 1080, height = 1920)

    private fun write(dir: File, c: SavedClip, video: Boolean = true) {
        if (video) SavedClipIndex.video(dir, c.id).writeBytes(byteArrayOf(1, 2, 3))
        SavedClipIndex.meta(dir, c.id).writeText(SavedClipIndex.encode(c))
    }

    @Test
    fun theSidecarRoundTrips() {
        val c = clip("1730000000000-ab12cd34")
        assertEquals(c, SavedClipIndex.decode(SavedClipIndex.encode(c)))
    }

    @Test
    fun damagedOrIncompleteSidecarsAreNotClips() {
        assertNull(SavedClipIndex.decode(null))
        assertNull(SavedClipIndex.decode(""))
        assertNull(SavedClipIndex.decode("not json"))
        assertNull(SavedClipIndex.decode("[1,2]"))
        assertNull(SavedClipIndex.decode("""{"trip":"t1","at":5}"""))
        assertNull(SavedClipIndex.decode("""{"id":"a","at":5}"""))
        assertNull(SavedClipIndex.decode("""{"id":"a","trip":"t1"}"""))
        assertNull(SavedClipIndex.decode("""{"id":"a","trip":"t1","at":"yesterday"}"""))
        assertNull(SavedClipIndex.decode("""{"id":{"x":1},"trip":"t1","at":5}"""))
    }

    @Test
    fun unknownKeysAndMissingDetailsAreFine() {
        val c = SavedClipIndex.decode("""{"v":9,"id":"a","trip":"t1","at":5,"later":true,"ms":-3}""")
        assertEquals(SavedClip("a", "t1", "", 5, 0, 0, 0), c)
    }

    @Test
    fun listingKeepsWholeClipsOnlyNewestFirst() {
        val dir = tmp.newFolder("clips")
        write(dir, clip("old", at = 1_000))
        write(dir, clip("new", at = 3_000))
        write(dir, clip("mid", trip = "t2", at = 2_000))
        write(dir, clip("no-video", at = 9_000), video = false)
        SavedClipIndex.video(dir, "no-meta").writeBytes(byteArrayOf(1))
        // An empty video (a copy that failed) and a sidecar under another clip's name.
        write(dir, clip("empty", at = 8_000))
        SavedClipIndex.video(dir, "empty").writeBytes(ByteArray(0))
        SavedClipIndex.meta(dir, "renamed").writeText(SavedClipIndex.encode(clip("other", at = 7_000)))
        SavedClipIndex.video(dir, "renamed").writeBytes(byteArrayOf(1))
        File(dir, "broken.json").writeText("{")
        File(dir, "x.part").writeBytes(byteArrayOf(1))

        assertEquals(listOf("new", "mid", "old"), SavedClipIndex.list(dir).map { it.id })
        assertEquals(emptyList<SavedClip>(), SavedClipIndex.list(File(dir, "missing")))
    }

    @Test
    fun aTripsClipsNewestFirst() {
        val all = listOf(clip("a", "t1", 1), clip("b", "t2", 5), clip("c", "t1", 3), clip("d", "t1", 3))
        assertEquals(listOf("d", "c", "a"), SavedClipIndex.forTrip(all, "t1").map { it.id })
        assertEquals(emptyList<SavedClip>(), SavedClipIndex.forTrip(all, "t9"))
    }

    @Test
    fun deletingRemovesEveryFile() {
        val dir = tmp.newFolder("clips")
        val c = clip("gone")
        write(dir, c)
        SavedClipIndex.thumb(dir, c.id).writeBytes(byteArrayOf(1))
        SavedClipIndex.delete(dir, c.id)
        assertFalse(SavedClipIndex.video(dir, c.id).exists())
        assertFalse(SavedClipIndex.meta(dir, c.id).exists())
        assertFalse(SavedClipIndex.thumb(dir, c.id).exists())
        assertTrue(SavedClipIndex.list(dir).isEmpty())
    }

    @Test
    fun lengthLabels() {
        assertEquals("", SavedClipIndex.length(0))
        assertEquals("0:01", SavedClipIndex.length(400))
        assertEquals("0:42", SavedClipIndex.length(42_000))
        assertEquals("1:05", SavedClipIndex.length(65_400))
        assertEquals("12:30", SavedClipIndex.length(750_000))
    }

    @Test
    fun idsSortByTimeAndAreSafeFileNames() {
        assertEquals("1730000000000-ab12cd34", SavedClipIndex.newId(1_730_000_000_000, "ab12-cd34-ef56"))
        assertEquals("5-x", SavedClipIndex.newId(5, "--"))
    }
}

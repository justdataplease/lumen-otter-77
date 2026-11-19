package com.justdataplease.boringtravel.clip.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class MusicCatalogTest {
    private val assets = File("src/main/assets")

    @Test fun eightTracksWithUniqueIdsNamesAndStyles() {
        val tracks = MusicCatalog.tracks
        assertEquals(8, tracks.size)
        assertEquals(8, tracks.map { it.id }.toSet().size)
        assertEquals(8, tracks.map { it.name }.toSet().size)
        // Each track its own style (Latin, Afrobeat, reggae…), not eight of one kind.
        assertEquals(8, tracks.map { it.mood }.toSet().size)
        tracks.forEach {
            assertTrue(it.id, it.id.matches(Regex("[a-z_]+")))
            assertTrue(it.id, it.name.isNotBlank() && it.name.length <= 14)
            assertTrue(it.id, it.mood.isNotBlank() && it.mood.length <= 14)
            assertEquals("music/${it.id}.m4a", it.asset)
        }
    }

    @Test fun everyAssetExistsAndNothingElseIsBundled() {
        val folder = File(assets, MusicCatalog.FOLDER)
        MusicCatalog.tracks.forEach { assertTrue(it.asset, File(assets, it.asset).isFile) }
        val bundled = folder.listFiles().orEmpty().map { "${MusicCatalog.FOLDER}/${it.name}" }.toSet()
        assertEquals(MusicCatalog.tracks.map { it.asset }.toSet(), bundled)
        // Size budget: no more than the ten tracks this set replaced (14.4 MB).
        assertTrue(folder.listFiles().orEmpty().sumOf { it.length() } <= 14_407_566)
    }

    @Test fun durationsAreInRangeAndMatchTheFiles() {
        MusicCatalog.tracks.forEach {
            assertTrue(it.id, it.durationMs in 90_000..121_000)
            val fileMs = mp4DurationMs(File(assets, it.asset))
            assertEquals(it.id, fileMs.toDouble(), it.durationMs.toDouble(), 50.0)
        }
    }

    @Test fun lookupByIdAndNone() {
        assertEquals("Que Chilero", MusicCatalog.byId("que_chilero")?.name)
        assertEquals("Latin Groove", MusicCatalog.byId("latin_groove")?.name)
        assertNull(MusicCatalog.byId(null))
        assertNull(MusicCatalog.byId("not_a_track"))
    }

    @Test fun theDroppedTracksAreGone() {
        DROPPED.forEach { assertNull(it, MusicCatalog.byId(it)) }
    }

    companion object {
        /** The ten tracks before the Latin and African set, minus Latin Groove, which stayed. */
        val DROPPED = listOf("sweet_sun", "picnic", "horizon", "world_map", "blue_sky", "sunset", "drift", "dreamscape", "old_road")
    }

    /** The movie header's duration (mvhd), in milliseconds. */
    private fun mp4DurationMs(file: File): Long {
        val bytes = file.readBytes()
        val at = (0..bytes.size - 4).first { bytes[it] == 'm'.code.toByte() && String(bytes, it, 4, Charsets.US_ASCII) == "mvhd" }
        val box = ByteBuffer.wrap(bytes, at + 4, bytes.size - at - 4)
        val version = box.get().toInt()
        box.position(box.position() + 3) // flags
        val (timescale, duration) = if (version == 1) {
            box.position(box.position() + 16)
            box.int.toLong() to box.long
        } else {
            box.position(box.position() + 8)
            box.int.toLong() to (box.int.toLong() and 0xFFFFFFFFL)
        }
        return duration * 1000 / timescale
    }
}

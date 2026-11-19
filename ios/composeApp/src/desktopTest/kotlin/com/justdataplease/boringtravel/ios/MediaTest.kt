package com.justdataplease.boringtravel.ios

import androidx.compose.ui.graphics.toPixelMap
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.CivilDay
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Photos and videos as this phone keeps them, without the screens: the checksum, what EXIF says, pictures turned and
 * made small, and the rows and files the importer leaves, which are the ones the Android app keeps and a backup carries.
 */
class MediaTest {
    private fun javaSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun theChecksumIsJavasSha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        val r = Random(7)
        for (n in listOf(1, 55, 56, 63, 64, 65, 1000, 70_001)) {
            val bytes = r.nextBytes(n)
            assertEquals(javaSha(bytes), Sha256.hex(bytes), "$n bytes")
            // Fed in pieces, as a video is read.
            val pieces = Sha256()
            bytes.toList().chunked(997).forEach { pieces.update(it.toByteArray()) }
            assertEquals(javaSha(bytes), pieces.hex())
        }
    }

    @Test
    fun exifSaysWhenAndWhereAndPicturesAreTurnedUpright() {
        val photo = withExif(samplePhoto(Scene.Sea, 400, 300), orientation = 6)
        val tags = Exif.read(photo)
        assertEquals("2027:06:19 13:10:05", tags.dateTimeOriginal)
        assertEquals("+03:00", tags.offsetTimeOriginal)
        // 13:10:05 at +03:00 is 10:10:05 UTC.
        val day = CivilDay(2027, 6, 19).epochDay
        assertEquals((day * 86_400 + 10 * 3600 + 10 * 60 + 5) * 1000, tags.takenAt())
        assertEquals(37.0 + 6.0 / 60 + 30.0 / 3600, tags.lat!!, 1e-9)
        assertEquals(-(25.0 + 22.5 / 60), tags.lng!!, 1e-9)
        // Turned a quarter by the camera: shown 300 wide and 400 tall, as Android reads its size.
        assertEquals(300 to 400, Pictures.size(photo))
        val picture = assertNotNull(Pictures.decode(photo, 200))
        assertEquals(150, picture.width)
        assertEquals(200, picture.height)
        // No EXIF, or a broken one: nothing, never a made-up date.
        assertNull(Exif.read(samplePhoto(Scene.Sea, 40, 30)).takenAt())
        assertNull(Exif.read(photo.copyOf(40)).lat)
        assertNull(Exif.parseTime("2027:02:30 10:00:00", null))
        assertNull(Exif.parseTime("2027:06:19 13:10:05", "+3"))
    }

    @Test
    fun aLargePhotoIsReadSmallForWhereItIsDrawn() {
        val big = samplePhoto(Scene.Town, 4000, 3000)
        val small = assertNotNull(Pictures.decode(big, 640))
        assertEquals(640, small.width)
        assertEquals(480, small.height)
        // Still the picture: the sky's blue at the top, the white houses' row lower down.
        val pixels = small.toPixelMap()
        val sky = pixels[320, 10]
        assertTrue(sky.blue > sky.red && sky.alpha > 0.99f, "sky: $sky")
        assertEquals(1280, Pictures.bucket(1000))
        assertEquals(2048, Pictures.bucket(5000))
    }

    @Test
    fun aPickedPhotoBecomesTheRowAndFileTheBackupCarries() {
        val files = MemoryMediaFiles()
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos")
        val bytes = withExif(samplePhoto(Scene.Olive, 800, 600), orientation = 1)
        files.write("picked/a.jpg", bytes)
        var n = 0
        val media = book.addMedia(MediaImporter(files) { "photo-${n++}" }.prepare(PickedMedia("picked/a.jpg", MediaTypes.JPEG), trip.id, "note-1"))
        assertEquals("photo-0", media.id)
        assertEquals("media/photo-0.jpg", media.localPath)
        assertEquals("image/jpeg", media.mime)
        assertEquals(800, media.width)
        assertEquals(600, media.height)
        assertEquals(javaSha(bytes), media.sha256)
        assertEquals(trip.id, media.tripId)
        assertEquals("note-1", media.entryId)
        assertNotNull(media.takenAt)
        assertNotNull(media.lat)
        // The file moved from the picker's place to where Android keeps it (the backup's zip entry is the same path).
        assertEquals(listOf("media/photo-0.jpg"), files.paths)
        assertTrue(bytes.contentEquals(files.read("media/photo-0.jpg")))
        val data = BackupJson.decodeFromString(BackupData.serializer(), book.export())
        assertEquals(book.snapshot, data.toSnapshot())
        assertEquals("media/photo-0.jpg", data.media.single().localPath)
    }

    @Test
    fun aVideoIsKeptWholeWithinAndroidsLimits() {
        val files = MemoryMediaFiles()
        val importer = MediaImporter(files) { "video-1" }
        val bytes = Random(3).nextBytes(300_000)
        files.write("picked/v.mov", bytes)
        val v = importer.prepare(PickedMedia("picked/v.mov", "video/quicktime", 1920, 1080, durationMs = 61_000, takenAt = 5L), "t", "e")
        assertEquals("media/video-1.mov", v.localPath)
        assertEquals("video/quicktime", v.mime)
        assertEquals(1920 to 1080, v.width to v.height)
        assertEquals(5L, v.takenAt)
        assertEquals(javaSha(bytes), v.sha256)
        assertTrue(v.isVideo)
        // Over 30 minutes: Android's words, and nothing stays behind.
        files.write("picked/long.mov", bytes)
        val e = assertFailsWith<MediaImportException> {
            importer.prepare(PickedMedia("picked/long.mov", "video/quicktime", durationMs = 31 * 60_000L), "t", "e")
        }
        assertEquals("This video is too long. Videos can be up to 30 minutes.", e.message)
        assertEquals(listOf("media/video-1.mov"), files.paths)
        // A file that isn't a picture is one photo that couldn't be added.
        files.write("picked/x.jpg", "not a photo".encodeToByteArray())
        val bad = importer.prepare(PickedMedia("picked/x.jpg", MediaTypes.JPEG), "t", "e")
        assertEquals(0, bad.width)
    }

    /**
     * [jpeg] with an EXIF block after its start: IFD0 (the orientation and a pointer each to the EXIF and GPS IFDs), the
     * capture time with its offset, and a place at 37°6'30" N, 25°22.5' W.
     */
    private fun withExif(jpeg: ByteArray, orientation: Int): ByteArray {
        val t = Tiff()
        // IFD0 at 8: 3 entries.
        val ifd0 = 8
        val exifIfd = ifd0 + 2 + 3 * 12 + 4
        val gpsIfd = exifIfd + 2 + 2 * 12 + 4
        val strings = gpsIfd + 2 + 4 * 12 + 4
        val date = "2027:06:19 13:10:05\u0000"
        val offset = "+03:00\u0000"
        val rationals = strings + date.length + offset.length
        t.u16(0x4949); t.u16(42); t.u32(ifd0)
        t.u16(3)
        t.entry(0x0112, 3, 1, orientation)
        t.entry(0x8769, 4, 1, exifIfd)
        t.entry(0x8825, 4, 1, gpsIfd)
        t.u32(0)
        t.u16(2)
        t.entry(0x9003, 2, date.length, strings)
        t.entry(0x9011, 2, offset.length, strings + date.length)
        t.u32(0)
        t.u16(4)
        t.entry(1, 2, 2, 'N'.code)
        t.entry(2, 5, 3, rationals)
        t.entry(3, 2, 2, 'W'.code)
        t.entry(4, 5, 3, rationals + 24)
        t.u32(0)
        t.ascii(date)
        t.ascii(offset)
        listOf(37L to 1L, 6L to 1L, 30L to 1L, 25L to 1L, 225L to 10L, 0L to 1L).forEach { (num, den) -> t.u32(num.toInt()); t.u32(den.toInt()) }
        val tiff = t.bytes()
        val app1 = ByteArrayOutputStream().apply {
            val length = 2 + 6 + tiff.size
            write(0xFF); write(0xE1); write(length shr 8); write(length and 0xFF)
            write("Exif\u0000\u0000".encodeToByteArray())
            write(tiff)
        }.toByteArray()
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    /** A little-endian TIFF block, written in order. */
    private class Tiff {
        private val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }

        fun u32(v: Int) {
            u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF)
        }

        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            u16(tag); u16(type); u32(count)
            if (type == 3) {
                u16(value); u16(0)
            } else u32(value)
        }

        fun ascii(s: String) = out.write(s.encodeToByteArray())
        fun bytes(): ByteArray = out.toByteArray()
    }
}

package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backup files (format 2): rows, shared trips, photos and extras in one zip, read in two passes. */
class BackupArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    /** The sample with schema 6's shared-trip columns set on a member's entry and photo. */
    private fun v6Snapshot(): Snapshot {
        val base = sampleSnapshot()
        return base.copy(
            entries = base.entries + Entry("e3", "t1", 1_812_400_000_000, "Europe/Athens", "From Anna", createdAt = 421, updatedAt = 422, authorId = "member-anna"),
            media = base.media + Media(
                "m3", "t1", "e3", "media/m3.jpg", createdAt = 521, updatedAt = 522, authorId = "member-anna", sharedFileId = "drive-shared-m3",
            ),
        )
    }

    private val shared = """{"trips":[{"tripId":"t1","me":"member-me","fileId":"f1","future":{"x":1}}]}"""

    private fun file(name: String, bytes: ByteArray) = File(folder.root, name).apply { writeBytes(bytes) }

    private fun archive(snapshot: Snapshot, files: List<ArchiveFile>, data: BackupData = snapshot.toData().copy(sharedTrips = shared)): ByteArray {
        val out = ByteArrayOutputStream()
        val manifest = BackupCodec.manifest(snapshot, "1.6.0", 1_794_000_000_000, BackupCodec.FILE_FORMAT)
        assertTrue(BackupArchive.write(manifest, data, files, out).isEmpty())
        return out.toByteArray()
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val manifestOnly = BackupCodec.MANIFEST to """{"format":2}""".toByteArray()
    private val emptyData = BackupCodec.DATA to "{}".toByteArray()

    private fun entries(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) names += (z.nextEntry ?: break).name }
        return names
    }

    private fun files(bytes: ByteArray): Map<String, ByteArray> = runBlocking {
        val seen = LinkedHashMap<String, ByteArray>()
        BackupArchive.eachFile(bytes.inputStream()) { name, content -> seen[name] = content.readBytes() }
        seen
    }

    private fun expectFormatError(contains: String, block: () -> Unit) {
        try {
            block()
            fail("expected BackupFormatException")
        } catch (e: BackupFormatException) {
            assertTrue(e.message, e.message!!.contains(contains))
        }
    }

    @Test fun formatTwoCarriesEveryRowSharedTripsPhotosAndExtras() {
        val snapshot = v6Snapshot()
        val photo = byteArrayOf(-1, -40, -1, 1, 2, 3, 4, 5)
        val edits = """{"v":1,"sources":{"Planned":{"scenes":{"label:item:p1":{"text":"Halki"}}}}}""".toByteArray()
        val voice = ByteArray(3000) { (it % 7).toByte() }
        val video = ByteArray(70_000) { (it % 251).toByte() }
        val bytes = archive(snapshot, listOf(
            ArchiveFile("extras/clip-edits/t1.json", file("t1.json", edits)),
            ArchiveFile("media/m1.jpg", file("m1.jpg", photo)),
            ArchiveFile("extras/clip-voice/t1/v1.m4a", file("v1.m4a", voice)),
            ArchiveFile("extras/clip-media/t1/c1.mp4", file("c1.mp4", video)),
        ))
        assertEquals(
            listOf("manifest.json", "data.json", "extras/clip-edits/t1.json", "media/m1.jpg", "extras/clip-voice/t1/v1.m4a", "extras/clip-media/t1/c1.mp4"),
            entries(bytes),
        )

        val read = BackupArchive.read(bytes.inputStream())
        assertEquals(2, read.manifest.format)
        assertEquals("1.6.0", read.manifest.appVersion)
        assertEquals(64, read.manifest.dataSha256?.length)
        assertEquals(snapshot, read.data.toSnapshot())
        assertEquals(shared, read.data.sharedTrips)
        val restored = read.data.toSnapshot()
        assertEquals("member-anna", restored.entries.single { it.id == "e3" }.authorId)
        assertNull(restored.entries.single { it.id == "e1" }.authorId)
        val m3 = restored.media.single { it.id == "m3" }
        assertEquals("member-anna", m3.authorId)
        assertEquals("drive-shared-m3", m3.sharedFileId)
        // The scene edits come with the rows; the big files only in the second pass, the photos' sizes in the first.
        assertEquals(listOf("extras/clip-edits/t1.json"), read.texts.keys.toList())
        assertEquals(mapOf("media/m1.jpg" to photo.size.toLong()), read.mediaSizes)
        assertArrayEquals(edits, read.texts.getValue("extras/clip-edits/t1.json"))

        val second = files(bytes)
        assertEquals(listOf("extras/clip-edits/t1.json", "media/m1.jpg", "extras/clip-voice/t1/v1.m4a", "extras/clip-media/t1/c1.mp4"), second.keys.toList())
        assertArrayEquals(photo, second.getValue("media/m1.jpg"))
        assertArrayEquals(voice, second.getValue("extras/clip-voice/t1/v1.m4a"))
        assertArrayEquals(video, second.getValue("extras/clip-media/t1/c1.mp4"))
    }

    @Test fun driveBackupsStayFormatOneAndTheFileReaderTakesThem() {
        val snapshot = v6Snapshot()
        val out = ByteArrayOutputStream()
        BackupCodec.write(BackupCodec.manifest(snapshot, "1.5.0", 7), snapshot.toData().copy(sharedTrips = shared), out)
        val drive = out.toByteArray()
        // What 1.5.0 writes and reads: two entries, format 1, no field it doesn't know.
        assertEquals(listOf("manifest.json", "data.json"), entries(drive))
        val (manifest, data) = BackupCodec.read(drive.inputStream())
        assertEquals(1, manifest.format)
        val keys = ZipInputStream(drive.inputStream()).use { z ->
            z.nextEntry
            BackupCodec.json.parseToJsonElement(z.readBytes().toString(Charsets.UTF_8)).let { it as kotlinx.serialization.json.JsonObject }.keys
        }
        assertEquals(setOf("format", "app", "appVersion", "createdAt", "counts", "items", "dataSha256"), keys)

        // A Drive backup downloaded and picked as a file restores the same rows (its photos aren't in it).
        val file = BackupArchive.read(drive.inputStream())
        assertEquals(1, file.manifest.format)
        assertEquals(data, file.data)
        assertTrue(files(drive).isEmpty())
        // And the Drive reader stays format 1: a backup file never goes to Drive.
        expectFormatError("newer BoringTravel") { BackupCodec.read(archive(snapshot, emptyList()).inputStream()) }
    }

    @Test fun aRestoreMergesBothFormatsTheSameWay() {
        val snapshot = v6Snapshot()
        val out = ByteArrayOutputStream()
        BackupCodec.write(BackupCodec.manifest(snapshot, "1.5.0", 7), snapshot.toData(), out)
        val one = BackupCodec.read(out.toByteArray().inputStream()).second.toSnapshot()
        val two = BackupArchive.read(archive(snapshot, emptyList()).inputStream()).data.toSnapshot()
        val local = sampleSnapshot().let { s -> s.copy(trips = s.trips.map { it.copy(updatedAt = 999) }, media = emptyList()) }
        val at: (Long) -> Long = { 9_000 }
        assertEquals(BackupMerge.replace(local, one, stamp = at), BackupMerge.replace(local, two, stamp = at))
        assertEquals(BackupMerge.replace(Snapshot(), one, stamp = at), BackupMerge.replace(Snapshot(), two, stamp = at))
    }

    @Test fun unsafeNamesAreRefusedBeforeAnythingIsRead() {
        val unsafe = listOf(
            "../evil.jpg", "media/../../evil.jpg", "/data/data/x/files/media/a.jpg", "C:/Windows/a.jpg", "media\\..\\a.jpg",
            "media//a.jpg", "media/./a.jpg", "extras/clip-media/t1/..", "media/a\u0000.jpg",
        )
        for (name in unsafe) {
            val bytes = zipOf(manifestOnly, emptyData, name to byteArrayOf(1))
            expectFormatError("unsafe") { BackupArchive.read(bytes.inputStream()) }
            expectFormatError("unsafe") { runBlocking { BackupArchive.eachFile(bytes.inputStream()) { _, _ -> fail("visited $name") } } }
            expectFormatError("unsafe") { BackupArchive.peek(zipOf(name to byteArrayOf(1), manifestOnly).inputStream()) }
            assertTrue(name, !BackupArchive.safeName(name))
        }
        // Plain names are fine, and a photo path that isn't a plain media file never becomes an entry.
        assertTrue(BackupArchive.safeName("media/m1.jpg"))
        assertTrue(BackupArchive.safeName("extras/clip-media/t1/"))
        assertEquals("media/m-1_a.jpg", BackupArchive.mediaEntry("media/m-1_a.jpg"))
        listOf("../files/a.jpg", "media/../a.jpg", "media/sub/a.jpg", "/media/a.jpg", "media/.hidden", "a.jpg").forEach {
            assertNull(it, BackupArchive.mediaEntry(it))
        }
    }

    @Test fun newerFormatsDamageAndSizeAreRefused() {
        expectFormatError("newer BoringTravel") { BackupArchive.read(zipOf(BackupCodec.MANIFEST to """{"format":3}""".toByteArray(), emptyData).inputStream()) }
        expectFormatError("newer BoringTravel") { BackupArchive.peek(zipOf(BackupCodec.MANIFEST to """{"format":3}""".toByteArray()).inputStream()) }
        expectFormatError("isn't a BoringTravel") { BackupArchive.read("not a zip".toByteArray().inputStream()) }
        expectFormatError("isn't a BoringTravel") { BackupArchive.peek(zipOf(emptyData).inputStream()) }
        expectFormatError("duplicate") {
            val bytes = zipOf(manifestOnly, emptyData, "media/a.jpg" to byteArrayOf(1), "media/b.jpg" to byteArrayOf(2))
            BackupArchive.read(bytes.toString(Charsets.ISO_8859_1).replace("media/b.jpg", "media/a.jpg").toByteArray(Charsets.ISO_8859_1).inputStream())
        }
        // Unknown files outside media/ and extras/ are bounded like format 1's.
        expectFormatError("too big") { BackupArchive.read(zipOf(manifestOnly, emptyData, "notes.bin" to ByteArray(1024 * 1024 + 1)).inputStream()) }
        // One changed byte in a photo: the zip's own check stops the first pass, before any row is merged.
        val photo = "PHOTO-BYTES-0123456789".toByteArray()
        val good = archive(v6Snapshot(), listOf(ArchiveFile("media/m1.jpg", file("m1.jpg", photo))))
        val at = good.toString(Charsets.ISO_8859_1).indexOf("PHOTO-BYTES")
        assertTrue(at > 0)
        val bad = good.copyOf().also { it[at] = 'X'.code.toByte() }
        expectFormatError("damaged") { BackupArchive.read(bad.inputStream()) }
        expectFormatError("can't be opened") { BackupArchive.read(good.copyOf(at + 5).inputStream()) }
    }

    @Test fun unknownFilesAndKindsAreReadThroughAndLeftForTheCaller() {
        val bytes = zipOf(
            BackupCodec.MANIFEST to """{"format":2,"later":true}""".toByteArray(),
            BackupCodec.DATA to """{"trips":[{"id":"t1","title":"Later","mood":"calm"}],"albums":[1]}""".toByteArray(),
            "README.txt" to "hello".toByteArray(),
            "extras/clip-music/t1/a.mp3" to byteArrayOf(1, 2),
            "media/" to ByteArray(0),
        )
        val read = BackupArchive.read(bytes.inputStream())
        assertEquals("Later", read.data.toSnapshot().trips.single().title)
        assertTrue(read.texts.isEmpty())
        val seen = files(bytes)
        assertEquals(setOf("extras/clip-music/t1/a.mp3"), seen.keys)
        assertNull(BackupExtras.target("extras/clip-music/t1/a.mp3"))
    }

    @Test fun aFileGoneByItsTurnIsLeftOutAndNamed() {
        val gone = File(folder.root, "never-there.jpg")
        val kept = file("m1.jpg", byteArrayOf(9, 9, 9))
        val out = ByteArrayOutputStream()
        val snapshot = sampleSnapshot()
        val leftOut = BackupArchive.write(
            BackupCodec.manifest(snapshot, "x", 1, BackupCodec.FILE_FORMAT), snapshot.toData(),
            listOf(ArchiveFile("media/m1.jpg", kept), ArchiveFile("media/m2.png", gone), ArchiveFile("media/m1.jpg", kept)), out,
        )
        assertEquals(listOf("media/m2.png"), leftOut)
        assertEquals(listOf("manifest.json", "data.json", "media/m1.jpg"), entries(out.toByteArray()))
        try {
            BackupArchive.write(BackupCodec.manifest(snapshot, "x", 1), snapshot.toData(), listOf(ArchiveFile("../x.jpg", kept)), ByteArrayOutputStream())
            fail("an unsafe name must never be written")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun peekReadsTheManifestForTheConfirmation() {
        val bytes = archive(v6Snapshot(), listOf(ArchiveFile("media/m1.jpg", file("m1.jpg", byteArrayOf(1)))))
        val manifest = BackupArchive.peek(bytes.inputStream())
        assertEquals(1_794_000_000_000, manifest.createdAt)
        assertEquals(BackupCounts(trips = 1, entries = 2, photos = 2), manifest.items)
    }

    @Test fun theDefaultNameIsTheDayOnThePhone() {
        val late = Instant.parse("2026-11-11T22:30:00Z").toEpochMilli()
        assertEquals("BoringTravel-2026-11-11.zip", BackupArchive.fileName(late, ZoneId.of("UTC")))
        assertEquals("BoringTravel-2026-11-12.zip", BackupArchive.fileName(late, ZoneId.of("Europe/Athens")))
    }
}

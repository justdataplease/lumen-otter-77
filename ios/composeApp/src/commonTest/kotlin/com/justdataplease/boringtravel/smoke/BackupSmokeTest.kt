package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.backup.ArchiveFile
import com.justdataplease.boringtravel.backup.BackupArchive
import com.justdataplease.boringtravel.backup.BackupCodec
import com.justdataplease.boringtravel.backup.BackupCounts
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupFormatException
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.BackupText
import com.justdataplease.boringtravel.backup.Crc32
import com.justdataplease.boringtravel.backup.EntryRow
import com.justdataplease.boringtravel.backup.FileBackupReport
import com.justdataplease.boringtravel.backup.PlanItemRow
import com.justdataplease.boringtravel.backup.TrackPointRow
import com.justdataplease.boringtravel.backup.TripRow
import com.justdataplease.boringtravel.backup.ZipFormatException
import com.justdataplease.boringtravel.backup.ZipReader
import com.justdataplease.boringtravel.backup.ZipWriter
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.compat.ByteArrayOutputStream
import com.justdataplease.boringtravel.ios.MemoryMediaFiles
import com.justdataplease.boringtravel.ios.Sha256
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.startOfDayMs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Backups on both phones: the engine's rows as JSON (BackupJson, kotlinx.serialization) round trip with Greek text,
 * an emoji, floats and dates; this app's zip (stored entries written, stored and deflated entries read, every CRC-32
 * checked) and its backup files (manifest, checksum, rows, photos), with SHA-256 and CRC-32 against known values.
 */
class BackupSmokeTest {
    private val data = BackupData(
        trips = listOf(TripRow("t1", "Naxos, slowly", "Paros → Naxos", "2027-09-10", "2027-09-14", createdAt = 1, updatedAt = 2)),
        planItems = listOf(
            PlanItemRow(
                "i1", "t1", day = "2027-09-10", timeMinutes = 445, kind = "transport", title = "Ferry to Naxos", lat = 37.942, lng = 23.646,
                url = "https://ferries.example.com/naxos", endDays = 0, endMinutes = 600, tags = "ferry islands", createdAt = 1, updatedAt = 2,
            ),
        ),
        entries = listOf(
            EntryRow(
                "e1", "t1", occurredAt = 1_820_000_000_000, zoneId = "Europe/Athens", title = "Ferry day", body = "{\"v\":1}",
                plainText = "Slept on deck. Νάξος \uD83C\uDFDD", placeName = "Naxos port", tempC = 27.5, createdAt = 1, updatedAt = 2,
            ),
        ),
        trackPoints = listOf(TrackPointRow("t1", time = 1_820_000_000_000, lat = 37.1, lng = 25.37, accuracyM = 8.5f, speedMps = 4.25f, altitudeM = 12.0)),
    )

    private fun bytes(write: ((ByteArray, Int, Int) -> Unit) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        write { b, off, len -> out.write(b, off, len) }
        return out.toByteArray()
    }

    private fun reader(zip: ByteArray) = ZipReader(zip.size.toLong()) { offset, length -> zip.copyOfRange(offset.toInt(), offset.toInt() + length) }

    // ---- Rows as JSON

    @Test fun rowsRoundTripAsJson() {
        val text = BackupJson.encodeToString(BackupData.serializer(), data)
        assertEquals(data, BackupJson.decodeFromString(BackupData.serializer(), text))
        assertTrue(text.contains("\"kind\":\"transport\""))
        assertTrue(text.contains("Νάξος \uD83C\uDFDD"))
        assertFalse(text.contains("deletedAt"), "nulls are left out")
        val older = BackupJson.decodeFromString(BackupData.serializer(), """{"trips":[{"id":"t9","title":"Old","futureField":1}],"somethingNew":true}""")
        assertEquals("t9", older.trips.single().id)
        assertEquals(emptyList<PlanItemRow>(), older.planItems)
    }

    @Test fun rowsBecomeTheEnginesEntities() {
        val snapshot = data.toSnapshot()
        assertEquals("2027-09-10", snapshot.trips.single().startDate.toString())
        assertEquals(5, snapshot.trips.single().days)
        assertEquals("2027-09-10", snapshot.planItems.single().day.toString())
        assertEquals(0L, snapshot.trackPoints.single().id)
        assertEquals(BackupCounts(trips = 1, entries = 1, photos = 0), snapshot.items())
        assertEquals(1, snapshot.tableCounts()["plan_items"])
        val broken = data.copy(trips = listOf(TripRow("t2", startDate = "2027-02-30")))
        assertFailsWith<BackupFormatException> { broken.toSnapshot() }
    }

    // ---- Hashes

    @Test fun checksumsAgainstKnownValues() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0x414FA339L, Crc32.of("The quick brown fox jumps over the lazy dog".encodeToByteArray()))
        assertEquals(0L, Crc32.of(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        val long = ByteArray(1_000) { (it % 251).toByte() }
        val split = Sha256().apply { update(long, 0, 300); update(long, 300, 700) }.hex()
        assertEquals(Sha256.hex(long), split)
    }

    // ---- The zip

    @Test fun aZipWrittenAndReadBack() {
        val manifest = "{\"format\":2}".encodeToByteArray()
        val photo = ByteArray(70_000) { (it * 31).toByte() }
        val zip = bytes { out ->
            ZipWriter(out).apply {
                add("manifest.json", manifest)
                add("media/νάξος.jpg", photo)
                finish()
            }
        }
        assertEquals('P'.code.toByte(), zip[0])
        val r = reader(zip)
        assertEquals(listOf("manifest.json", "media/νάξος.jpg"), r.entries.map { it.name })
        assertContentEquals(manifest, r.bytesOf(r.entries[0], 1_000))
        assertContentEquals(photo, r.bytesOf(r.entries[1], 1_000_000))
        assertEquals(Crc32.of(photo), r.entries[1].crc)
        // A byte changed inside the photo: its CRC-32 no longer matches.
        val damaged = zip.copyOf().also { it[zip.size / 2] = (it[zip.size / 2] + 1).toByte() }
        val bad = reader(damaged)
        assertFailsWith<ZipFormatException> { bad.bytesOf(bad.entries[1], 1_000_000) }
        assertFailsWith<ZipFormatException> { r.bytesOf(r.entries[1], 1_000) }
        assertFailsWith<ZipFormatException> { reader("not a zip at all, just words".encodeToByteArray()) }
    }

    @Test fun deflatedAndStoredBlocksAreInflated() {
        // "a" as raw DEFLATE (one fixed-Huffman block) and "Naxos" as one stored block, each in a zip of method 8.
        val fixed = byteArrayOf(0x4B, 0x04, 0x00)
        val stored = byteArrayOf(0x01, 0x05, 0x00, 0xFA.toByte(), 0xFF.toByte()) + "Naxos".encodeToByteArray()
        val zip = deflatedZip(listOf("a.txt" to (fixed to "a".encodeToByteArray()), "n.txt" to (stored to "Naxos".encodeToByteArray())))
        val r = reader(zip)
        assertEquals(listOf(8, 8), r.entries.map { it.method })
        assertEquals("a", r.bytesOf(r.entries[0], 100).decodeToString())
        assertEquals("Naxos", r.bytesOf(r.entries[1], 100).decodeToString())
    }

    /** A zip of [parts] (name, raw DEFLATE data and what it inflates to) with method 8, as Android's ZipOutputStream writes. */
    private fun deflatedZip(parts: List<Pair<String, Pair<ByteArray, ByteArray>>>): ByteArray {
        val out = ArrayList<Byte>()
        fun short(v: Int) { out += v.toByte(); out += (v shr 8).toByte() }
        fun int(v: Long) { short((v and 0xFFFF).toInt()); short(((v ushr 16) and 0xFFFF).toInt()) }
        val offsets = ArrayList<Int>()
        for ((name, content) in parts) {
            val (deflated, plain) = content
            offsets += out.size
            int(0x04034b50); short(20); short(0); short(8); short(0); short(33)
            int(Crc32.of(plain)); int(deflated.size.toLong()); int(plain.size.toLong()); short(name.length); short(0)
            out += name.encodeToByteArray().toList()
            out += deflated.toList()
        }
        val dirStart = out.size
        parts.forEachIndexed { i, (name, content) ->
            val (deflated, plain) = content
            int(0x02014b50); short(20); short(20); short(0); short(8); short(0); short(33)
            int(Crc32.of(plain)); int(deflated.size.toLong()); int(plain.size.toLong()); short(name.length); short(0); short(0)
            short(0); short(0); int(0); int(offsets[i].toLong())
            out += name.encodeToByteArray().toList()
        }
        val dirSize = out.size - dirStart
        int(0x06054b50); short(0); short(0); short(parts.size); short(parts.size); int(dirSize.toLong()); int(dirStart.toLong()); short(0)
        return out.toByteArray()
    }

    // ---- Backup files

    @Test fun aBackupFileRoundTrip() {
        val manifest = BackupCodec.manifest(data.toSnapshot(), "1.21.1", createdAt = 1_800_000_000_000, format = BackupCodec.FILE_FORMAT)
        val files = MemoryMediaFiles()
        val photo = ByteArray(5_000) { (it % 97).toByte() }
        files.write("media/m1.jpg", photo)
        var left: List<String> = emptyList()
        val zip = bytes { out ->
            left = BackupArchive.write(
                manifest, data, listOf(ArchiveFile("media/m1.jpg", "media/m1.jpg"), ArchiveFile("media/gone.jpg", "media/gone.jpg")),
                files, out, at = 1_800_000_000_000,
            )
        }
        assertEquals(listOf("media/gone.jpg"), left)
        val r = BackupArchive.reader(zip.size.toLong()) { offset, length -> zip.copyOfRange(offset.toInt(), offset.toInt() + length) }
        assertEquals(listOf("manifest.json", "data.json", "media/m1.jpg"), r.entries.map { it.name })
        assertEquals("1.21.1", BackupArchive.peek(r).appVersion)
        val contents = BackupArchive.read(r)
        assertEquals(data, contents.data)
        assertEquals(BackupCounts(trips = 1, entries = 1, photos = 0), contents.manifest.items)
        assertEquals(Sha256.hex(BackupJson.encodeToString(BackupData.serializer(), data).encodeToByteArray()), contents.manifest.dataSha256)
        assertEquals(5_000L, contents.mediaSizes["media/m1.jpg"])
    }

    @Test fun backupChecksRefuseWhatIsWrong() {
        val manifest = BackupCodec.manifest(data.toSnapshot(), "1.21.1", createdAt = 1)
        val (manifestBytes, dataBytes) = BackupCodec.rows(manifest, data)
        val (read, rows) = BackupCodec.parse(manifestBytes.decodeToString(), dataBytes, BackupCodec.FILE_FORMAT)
        assertEquals(data, rows)
        assertEquals(BackupCodec.sum(data), read.dataSha256)
        val tampered = dataBytes.copyOf().also { it[it.size - 3] = '0'.code.toByte() }
        val checksum = assertFailsWith<BackupFormatException> { BackupCodec.parse(manifestBytes.decodeToString(), tampered, 2) }
        assertEquals("This backup's checksum doesn't match. It may be damaged.", checksum.message)
        assertEquals(
            "This backup is from a newer BoringTravel. Update the app, then restore it.",
            assertFailsWith<BackupFormatException> { BackupCodec.parseManifest("{\"format\":3,\"app\":\"BoringTravel\"}", 2) }.message,
        )
        assertEquals("This isn't a BoringTravel backup.", assertFailsWith<BackupFormatException> { BackupCodec.parseManifest("{\"app\":\"Other\"}", 2) }.message)
        assertEquals("This backup is damaged and can't be opened.", assertFailsWith<BackupFormatException> { BackupCodec.parseManifest("not json", 2) }.message)
        assertTrue(BackupArchive.safeName("media/m1.jpg"))
        assertFalse(BackupArchive.safeName("media/../secret"))
        assertFalse(BackupArchive.safeName("/etc/passwd"))
        assertFalse(BackupArchive.safeName("C:/x"))
        assertEquals("media/m1.jpg", BackupArchive.mediaEntry("media/m1.jpg"))
        assertEquals(null, BackupArchive.mediaEntry("photos/m1.jpg"))
    }

    @Test fun backupTexts() {
        assertEquals("1 trip · 2 entries · 0 photos", BackupText.counts(BackupCounts(1, 2, 0)))
        val at = startOfDayMs(localDate(2026, 11, 5), "UTC") + 22 * 3_600_000L
        assertEquals("5 Nov 2026, 22:00", BackupText.date(at, "UTC"))
        assertEquals("BoringTravel-2026-11-05.zip", BackupArchive.fileName(at, "UTC"))
        assertEquals("Saved: 2 trips · 31 entries · 148 photos. 1 photo wasn't on this phone, so it isn't in the file.", BackupText.saved(FileBackupReport(BackupCounts(2, 31, 148), 148, 1)))
    }
}

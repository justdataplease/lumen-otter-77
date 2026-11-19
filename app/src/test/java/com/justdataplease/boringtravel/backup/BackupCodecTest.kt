package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.data.db.BookDraft
import com.justdataplease.boringtravel.data.db.CountryMark
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.Align
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.doc.PhotoFrame
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

/** Every field of every table gets a distinct value, so a swapped field in the mapping shows up. */
internal fun sampleSnapshot() = Snapshot(
    trips = listOf(
        Trip("t1", "Cyclades", "Paros, Naxos", LocalDate.of(2027, 6, 17), LocalDate.of(2027, 6, 26), "m1", "balanced", 101, 102,
            travelModes = """{"v":1,"legs":[{"source":"planned","from":"p1","to":"p2","vehicle":"ferry","future":"keep"}],"unknown":[1,2]}"""),
        Trip("t2", "Someday: Kyoto", "", null, null, null, null, 201, 202, deletedAt = 203),
    ),
    planItems = listOf(
        PlanItem(
            "p1", "t1", LocalDate.of(2027, 6, 18), 1.5, 450, "transport", "Ferry Paros → Naxos", "Gate B", "Parikia port", 37.08, 25.15,
            true, "ai", "draft-7", 301, 302, null, "https://www.blue-star.gr/routes?from=paros&to=naxos",
        ),
        PlanItem("p2", "t1", null, 0.0, null, "food", "Idea: «Lefteris»", createdAt = 311, updatedAt = 312, deletedAt = 313),
    ),
    entries = listOf(
        Entry(
            "e1", "t1", 1_812_345_678_000, "Europe/Athens", "Halki", """{"v":1,"blocks":[{"t":"p","html":"Forty \"minutes\" <b>late</b>"}]}""",
            "Forty minutes late", "Halki, Naxos", 37.0587, 25.4807, "sunny", 29.5, 401, 402, null,
        ),
        Entry("e2", null, 1_812_000_000_000, "UTC", createdAt = 411, updatedAt = 412, deletedAt = 413),
    ),
    media = listOf(
        Media("m1", "t1", "e1", "media/m1.jpg", "image/jpeg", 4032, 3024, 1_812_345_000_000, 37.05, 25.48, "drive-m1", "abc123", 501, 502, null),
        Media("m2", "t1", null, "media/m2.png", "image/png", createdAt = 511, updatedAt = 512, deletedAt = 513),
    ),
    trackPoints = listOf(
        TrackPoint(0, "t1", 1_812_345_000_000, 37.0, 25.1, 12.5f, 3.2f, 104.0, "detailed"),
        TrackPoint(0, null, 1_812_345_060_000, 37.001, 25.101),
    ),
    stops = listOf(
        Stop("s1", "t1", 1_812_300_000_000, 1_812_310_000_000, 37.05, 25.48, "Halki", "GR", "manual", 601, 602, null),
        Stop("s2", null, 1_812_400_000_000, null, 36.0, 24.0, createdAt = 611, updatedAt = 612, deletedAt = 613),
    ),
    countryMarks = listOf(CountryMark("manual:PT", "PT", CountryMark.SOURCE_MANUAL, 701, 702, null)),
    books = listOf(BookDraft("b1", "t1", "sq21", "postcard", "kraft", true, """{"v":1,"paper":"lines","pages":[{"id":"c","t":"cover","future":1}]}""", 801, 802, null)),
)

class BackupCodecTest {
    private fun zip(snapshot: Snapshot, createdAt: Long = 1_793_908_800_000): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCodec.write(BackupCodec.manifest(snapshot, "0.1.0", createdAt), snapshot.toData(), out)
        return out.toByteArray()
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun expectFormatError(bytes: ByteArray, contains: String) {
        try {
            BackupCodec.read(ByteArrayInputStream(bytes))
            fail("expected BackupFormatException")
        } catch (e: BackupFormatException) {
            assertTrue(e.message, e.message!!.contains(contains))
        }
    }

    @Test fun everyRowOfEveryTableSurvivesTheZip() {
        val snapshot = sampleSnapshot()
        val (manifest, data) = BackupCodec.read(ByteArrayInputStream(zip(snapshot)))
        assertEquals(snapshot, data.toSnapshot())
        assertEquals(1, manifest.format)
        assertEquals("0.1.0", manifest.appVersion)
        assertEquals(1_793_908_800_000, manifest.createdAt)
    }

    @Test fun manifestCountsRowsAndWhatPeopleSee() {
        val manifest = BackupCodec.manifest(sampleSnapshot(), "0.1.0", 5)
        assertEquals(
            mapOf("trips" to 2, "plan_items" to 2, "entries" to 2, "media" to 2, "track_points" to 2, "stops" to 2, "country_marks" to 1, "books" to 1),
            manifest.counts,
        )
        // Soft-deleted trips, entries and photos are in the file but not in the count people read.
        assertEquals(BackupCounts(trips = 1, entries = 1, photos = 1), manifest.items)
    }

    @Test fun datesAreReadableAndNullsLeftOut() {
        val json = BackupCodec.json.encodeToString(BackupData.serializer(), sampleSnapshot().toData())
        assertTrue(json.contains("\"startDate\":\"2027-06-17\""))
        assertTrue(!json.contains("\"coverMediaId\":null"))
    }

    @Test fun newerFormatsAreRefusedPlainly() {
        expectFormatError(zipOf("manifest.json" to """{"format":2,"createdAt":1}""", "data.json" to "{}"), "newer BoringTravel")
    }

    @Test fun foreignOrBrokenFilesAreRefused() {
        expectFormatError("not a zip at all".toByteArray(), "isn't a BoringTravel backup")
        expectFormatError(zipOf("data.json" to "{}"), "isn't a BoringTravel backup")
        expectFormatError(zipOf("manifest.json" to """{"format":1}"""), "missing")
        expectFormatError(zipOf("manifest.json" to "{oops", "data.json" to "{}"), "damaged")
        expectFormatError(zipOf("manifest.json" to """{"format":1}""", "data.json" to """{"trips":[{"title":"no id"}]}"""), "damaged")
    }

    @Test fun aPlanItemsLinkSurvivesTheZipAndOldBackupsHaveNone() {
        val (_, data) = BackupCodec.read(ByteArrayInputStream(zip(sampleSnapshot())))
        val items = data.toSnapshot().planItems.associateBy { it.id }
        assertEquals("https://www.blue-star.gr/routes?from=paros&to=naxos", items.getValue("p1").url)
        assertNull(items.getValue("p2").url)
        val json = BackupCodec.json.encodeToString(BackupData.serializer(), sampleSnapshot().toData())
        assertTrue(json.contains("\"url\":\"https://www.blue-star.gr/routes?from=paros&to=naxos\""))

        // A backup written before links (schema 4) restores its items without one.
        val old = zipOf(
            "manifest.json" to """{"format":1,"createdAt":7}""",
            "data.json" to """{"planItems":[{"id":"p9","tripId":"t1","day":"2027-06-18","title":"Halki","updatedAt":3}]}""",
        )
        val restored = BackupCodec.read(ByteArrayInputStream(old)).second.toSnapshot().planItems.single()
        assertEquals("Halki", restored.title)
        assertNull(restored.url)
    }

    @Test fun unknownFieldsFromALaterVersionAreIgnored() {
        val bytes = zipOf(
            "manifest.json" to """{"format":1,"createdAt":7,"futureThing":{"a":1}}""",
            "data.json" to """{"trips":[{"id":"t9","title":"Later","mood":"calm","updatedAt":3}],"albums":[{"id":"x"}]}""",
            "extra/readme.txt" to "hello",
        )
        val (manifest, data) = BackupCodec.read(ByteArrayInputStream(bytes))
        assertEquals(7L, manifest.createdAt)
        val trip = data.toSnapshot().trips.single()
        assertEquals("t9", trip.id)
        assertEquals("Later", trip.title)
        assertNull(trip.startDate)
    }

    @Test fun theSameRowsGiveTheSameSumAndAnyChangeAnother() {
        // Owner, 15 Nov 2026: "if a trip does not update do not run backup again". The sum is what the next backup
        // compares: the SHA-256 of data.json, whenever the backup is made.
        val data = sampleSnapshot().toData(BackupPreferences(look = "trail"))
        val sum = BackupCodec.sum(data)
        assertEquals(sum, BackupCodec.sum(sampleSnapshot().toData(BackupPreferences(look = "trail"))))
        val bytes = ByteArrayOutputStream().also { BackupCodec.write(BackupCodec.manifest(sampleSnapshot(), "1.0", 5L), data, it) }.toByteArray()
        assertEquals(sum, BackupCodec.read(ByteArrayInputStream(bytes)).first.dataSha256)
        // A later backup of the same rows has another date and the same sum.
        val later = ByteArrayOutputStream().also { BackupCodec.write(BackupCodec.manifest(sampleSnapshot(), "1.1", 99L), data, it) }.toByteArray()
        assertEquals(sum, BackupCodec.read(ByteArrayInputStream(later)).first.dataSha256)
        // A renamed trip, a changed setting, a new saved video: each is something new.
        val snapshot = sampleSnapshot()
        val renamed = snapshot.copy(trips = snapshot.trips.map { if (it.id == "t1") it.copy(title = "Cyclades, slowly") else it })
        assertTrue(BackupCodec.sum(renamed.toData(BackupPreferences(look = "trail"))) != sum)
        assertTrue(BackupCodec.sum(sampleSnapshot().toData(BackupPreferences(look = "market"))) != sum)
        assertTrue(BackupCodec.sum(data.copy(savedClips = listOf(ClipRow("c1", "t1")))) != sum)
    }

    @Test fun theStampOfTheLastBackupRoundTripsAndBadOnesAreIgnored() {
        val stamp = BackupStamp("abc123", BackupMarker(id = "file-1", name = "BoringTravel backup.zip", createdAt = 7L, items = BackupCounts(2, 31, 148), appVersion = "1.17.0"))
        assertEquals(stamp, BackupCodec.decodeStamp(BackupCodec.encodeStamp(stamp)))
        assertNull(BackupCodec.decodeStamp(null))
        assertNull(BackupCodec.decodeStamp(""))
        assertNull(BackupCodec.decodeStamp("not json"))
        assertNull(BackupCodec.decodeStamp(BackupCodec.encodeStamp(stamp.copy(sum = ""))))
        assertNull(BackupCodec.decodeStamp(BackupCodec.encodeStamp(stamp.copy(marker = stamp.marker.copy(id = "")))))
    }

    @Test fun markerRoundTripsAndBadOnesAreIgnored() {
        val marker = BackupMarker(id = "bk1", name = "BoringTravel backup 2026-11-05 22.00.zip", createdAt = 9, items = BackupCounts(2, 31, 148), appVersion = "0.1.0")
        assertEquals(marker, BackupCodec.decodeMarker(BackupCodec.encodeMarker(marker)))
        assertNull(BackupCodec.decodeMarker(null))
        assertNull(BackupCodec.decodeMarker("{not json"))
        assertNull(BackupCodec.decodeMarker("""{"format":9,"id":"x","name":"n","createdAt":1}"""))
        assertNull(BackupCodec.decodeMarker("""{"format":1,"id":"","name":"n","createdAt":1}"""))
        assertNull(BackupCodec.decodeMarker("""{"format":0,"id":"x","name":"n","createdAt":1}"""))
    }

    @Test fun richJournalAndPortablePreferencesSurviveTogether() {
        val doc = EntryDoc(font = "literata", blocks = listOf(
            Block.Paragraph("Greek ταξίδι <b>and bold</b>"),
            Block.Photo("m1", -7.5f, 0.6f, PhotoFrame.Polaroid, "A caption", Align.End, position = 0.37f),
            Block.PageBreak,
            Block.Checklist(listOf(CheckItem("Tickets", true), CheckItem("Pack"))),
            Block.Place("A saved place", 37.0, 25.0),
            Block.Unknown(Json.parseToJsonElement("""{"t":"future-block","nested":{"keep":[1,"original"]}}""").jsonObject),
        ))
        val base = sampleSnapshot()
        val snapshot = base.copy(entries = listOf(base.entries.first().copy(body = doc.toJson())))
        val preferences = BackupPreferences("notebook", "serif", "postcard", "el-GR", false, true)
        val output = ByteArrayOutputStream()
        BackupCodec.write(BackupCodec.manifest(snapshot, "preview", 123), snapshot.toData(preferences), output)
        val (manifest, restored) = BackupCodec.read(output.toByteArray().inputStream())
        assertEquals(snapshot, restored.toSnapshot())
        assertEquals(doc, EntryDoc.parse(restored.entries.single().body))
        assertEquals(preferences, restored.preferences)
        assertEquals(64, manifest.dataSha256?.length)
    }

    @Test fun oldFormatOneWithoutHashPreferencesOrCountryMarksStillRestores() {
        val (manifest, data) = BackupCodec.read(zipOf(
            "manifest.json" to """{"format":1,"counts":{"trips":1}}""",
            "data.json" to """{"trips":[{"id":"legacy","title":"Older trip","updatedAt":4}]}""",
        ).inputStream())
        assertEquals("Older trip", data.toSnapshot().trips.single().title)
        assertNull(data.toSnapshot().trips.single().travelModes)
        assertNull(manifest.dataSha256)
        assertNull(data.preferences)
        assertTrue(data.countryMarks.isEmpty())
        assertTrue(data.books.isEmpty())
    }

    @Test fun bookLayoutsTravelAsTheirOwnJson() {
        val (_, data) = BackupCodec.read(ByteArrayInputStream(zip(sampleSnapshot())))
        val b = data.toSnapshot().books.single()
        assertEquals("sq21", b.format)
        assertTrue(b.bleed)
        assertTrue(b.pages.contains("\"future\":1"))
    }

    @Test fun opaqueFutureTravelModesSurviveZipBackupWithoutReinterpretation() {
        val raw = """{"v":9, "legs":[{"source":"future","from":"a","to":"b","vehicle":"future","unknown":[1,2]}]}"""
        val base = sampleSnapshot()
        val snapshot = base.copy(trips = base.trips.map { it.copy(travelModes = raw) })
        val restored = BackupCodec.read(zip(snapshot).inputStream()).second.toSnapshot()
        assertEquals(snapshot, restored)
        assertTrue(restored.trips.all { it.travelModes == raw })
    }

    @Test fun changedDataWithValidZipCrcIsRejectedByTheBackupChecksum() {
        val files = linkedMapOf<String, String>()
        ZipInputStream(zip(sampleSnapshot()).inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                files[entry.name] = input.readBytes().toString(Charsets.UTF_8)
            }
        }
        files["data.json"] = requireNotNull(files["data.json"]).replace("Cyclades", "Altered!")
        expectFormatError(zipOf(*files.toList().toTypedArray()), "checksum")
    }

    @Test fun mismatchedCountsAndInvalidDatesCannotSilentlyRemoveData() {
        expectFormatError(zipOf("manifest.json" to """{"counts":{"entries":1}}""", "data.json" to "{}"), "row counts")
        expectFormatError(zipOf("manifest.json" to "{}", "data.json" to """{"trips":[{"id":"t","startDate":"2026-99-99"}]}"""), "invalid date")
        expectFormatError(zipOf("manifest.json" to "{}", "data.json" to """{"trips":[{"id":"t"},{"id":"t"}]}"""), "duplicate row IDs")
    }

    @Test fun foreignAndNonPositiveFormatsAreRejected() {
        expectFormatError(zipOf("manifest.json" to """{"app":"Other App","format":1}""", "data.json" to "{}"), "isn't a BoringTravel")
        expectFormatError(zipOf("manifest.json" to """{"format":0}""", "data.json" to "{}"), "isn't a BoringTravel")
    }

    @Test fun duplicateZipNamesAreRejectedInsteadOfSilentlyTakingTheLastPayload() {
        val bytes = zipOf("manifest.json" to "{}", "data.json" to "{}", "evil.json" to "{}")
            .toString(Charsets.ISO_8859_1).replace("evil.json", "data.json").toByteArray(Charsets.ISO_8859_1)
        expectFormatError(bytes, "duplicate")
    }

    @Test fun unknownZipEntriesAreBoundedToo() {
        expectFormatError(zipOf("manifest.json" to "{}", "data.json" to "{}", "extra.bin" to "x".repeat(1024 * 1024 + 1)), "too big")
    }

    @Test fun truncatedCompressedContentIsRejected() {
        val bytes = zip(sampleSnapshot())
        expectFormatError(bytes.copyOf(bytes.size / 2), "can't be opened")
    }

    @Test fun cancellationEscapesTheDecoderAsCancellation() {
        var checks = 0
        try {
            BackupCodec.read(zip(sampleSnapshot()).inputStream()) {
                if (++checks == 2) throw CancellationException("cancel")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(2, checks)
    }

    @Test fun aPlaceOutsideTheEarthIsMendedNotAReasonToRefuseTheBackup() {
        val bad = sampleSnapshot().let { it.copy(trackPoints = it.trackPoints + TrackPoint(0, "t1", 1, 91.0, 0.0)) }
        val read = BackupCodec.read(ByteArrayInputStream(zip(bad))).second.toSnapshot()
        assertEquals(sampleSnapshot(), BackupSafety.clean(read))
    }
}

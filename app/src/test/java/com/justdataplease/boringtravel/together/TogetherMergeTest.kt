package com.justdataplease.boringtravel.together

import com.justdataplease.boringtravel.backup.EntryRow
import com.justdataplease.boringtravel.backup.PlanItemRow
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class TogetherMergeTest {
    private val trip = Trip("t", "Patagonia", startDate = LocalDate.of(2027, 3, 1), endDate = LocalDate.of(2027, 3, 31), createdAt = 1, updatedAt = 10)

    private fun item(id: String, title: String, at: Long, deleted: Long? = null) =
        PlanItem(id, "t", LocalDate.of(2027, 3, 2), 1.0, null, "place", title, createdAt = 1, updatedAt = at, deletedAt = deleted)

    private fun entry(id: String, text: String, at: Long, author: String? = null) =
        Entry(id, "t", 1000, "UTC", title = text, createdAt = 1, updatedAt = at, authorId = author)

    private fun photo(id: String, at: Long, author: String? = null, file: String? = null) =
        Media(id, "t", localPath = "media/$id.jpg", createdAt = 1, updatedAt = at, authorId = author, sharedFileId = file, sha256 = "abc", driveFileId = "backup-$id")

    private fun video(id: String, at: Long, author: String? = null, file: String? = null, mime: String = "video/mp4") =
        Media(id, "t", localPath = "media/$id.mp4", mime = mime, width = 720, height = 1280, createdAt = 1, updatedAt = at, authorId = author, sharedFileId = file, sha256 = "abc")

    /** What phone "b" (member id "B") writes for its rows. */
    private fun fileOfB(rows: TripRows) = TogetherMerge.file(rows, "B", listOf(Member("B", "Bo", "file-of-b")), 50)

    @Test fun aNewPhoneTakesTheWholeTrip() {
        val b = TripRows(trip, listOf(item("i1", "Glacier", 20)), listOf(entry("e1", "Day one", 20)), listOf(photo("p1", 20, file = "drive-p1")))
        val plan = TogetherMerge.plan(TripRows(null), fileOfB(b), me = "A")
        assertEquals("Patagonia", plan.trip?.title)
        assertNull(plan.trip?.trackingLevel)
        assertEquals(listOf("Glacier"), plan.planItems.map { it.title })
        // B's own entry and photo arrive as B's.
        assertEquals("B", plan.entries.single().authorId)
        val p = plan.media.single()
        assertEquals("B", p.authorId)
        assertEquals("drive-p1", p.sharedFileId)
        assertEquals(TogetherMerge.sharedPath("p1"), p.localPath)
        // Nothing of B's phone: its checksum and backup copy stay there.
        assertNull(p.sha256)
        assertNull(p.driveFileId)
    }

    @Test fun laterChangesWinAndTiesKeepThePhonesRow() {
        val local = TripRows(trip, listOf(item("i1", "Mine", 30), item("i2", "Mine too", 30)))
        val incoming = fileOfB(TripRows(trip, listOf(item("i1", "Theirs", 31), item("i2", "Theirs too", 30))))
        assertEquals(listOf("Theirs"), TogetherMerge.plan(local, incoming, "A").planItems.map { it.title })
    }

    @Test fun deletionsTravel() {
        val local = TripRows(trip, listOf(item("i1", "Glacier", 30)))
        val incoming = fileOfB(TripRows(trip, listOf(item("i1", "Glacier", 40, deleted = 40))))
        assertEquals(40L, TogetherMerge.plan(local, incoming, "A").planItems.single().deletedAt)
    }

    @Test fun theTripIsNeverDeletedOrRetrackedFromOutside() {
        val mine = trip.copy(trackingLevel = "detailed", deletedAt = 99, updatedAt = 99)
        val theirs = fileOfB(TripRows(trip.copy(title = "Patagonia 2027", updatedAt = 120)))
        val t = TogetherMerge.plan(TripRows(mine), theirs, "A").trip!!
        assertEquals("Patagonia 2027", t.title)
        assertEquals("detailed", t.trackingLevel)
        assertEquals(99L, t.deletedAt)
    }

    @Test fun onlyTheAuthorChangesAnEntry() {
        // A's own entry (no author on A's phone) and B's entry.
        val local = TripRows(trip, entries = listOf(entry("mine", "A wrote", 30), entry("theirs", "B wrote", 30, author = "B")))
        // A file from C that claims to have changed both, as C's own.
        val c = TogetherMerge.file(
            TripRows(trip, entries = listOf(entry("mine", "C changed", 40, author = "C"), entry("theirs", "C changed", 40, author = "C"))),
            "C", listOf(Member("C")), 50,
        )
        assertTrue(TogetherMerge.plan(local, c, "A").entries.isEmpty())
        // B's own newer version is taken.
        val b = fileOfB(TripRows(trip, entries = listOf(entry("theirs", "B again", 41))))
        assertEquals("B again", TogetherMerge.plan(local, b, "A").entries.single().title)
    }

    @Test fun myOwnRowsComeBackAsMine() {
        // After a restore, B's file has A's entry: it stays A's (no author on A's phone).
        val b = TogetherMerge.file(TripRows(trip, entries = listOf(entry("e", "A wrote", 30, author = "A"))), "B", listOf(Member("B")), 50)
        assertNull(TogetherMerge.plan(TripRows(trip), b, "A").entries.single().authorId)
    }

    @Test fun rowsOfOtherTripsAreNotTakenOver() {
        val b = fileOfB(TripRows(trip, listOf(item("x", "Elsewhere", 40))))
        assertTrue(TogetherMerge.plan(TripRows(trip), b, "A", elsewhere = setOf("x")).planItems.isEmpty())
    }

    @Test fun aPhotoKeepsItsFileOnThisPhone() {
        val local = TripRows(trip, media = listOf(photo("p", 30, author = "B", file = "d1").copy(localPath = "media/shared-p.jpg", sha256 = null, driveFileId = null)))
        val b = fileOfB(TripRows(trip, media = listOf(photo("p", 40, file = "d2"))))
        val p = TogetherMerge.plan(local, b, "A").media.single()
        assertEquals("media/shared-p.jpg", p.localPath)
        assertEquals("d2", p.sharedFileId)
    }

    @Test fun aVideoKeepsItsTypeAndGetsAVideosPath() {
        val b = TripRows(trip, media = listOf(video("v1", 20, file = "drive-v1"), photo("p1", 20, file = "drive-p1").copy(mime = "image/png")))
        val file = fileOfB(b)
        // A video says what it is; a photo's copy is always a small JPEG, whatever the original was.
        assertEquals(mapOf("v1" to "video/mp4", "p1" to "image/jpeg"), file.photos.associate { it.id to it.mime })
        val read = ShareCodec.decode(ShareCodec.encode(file))
        assertEquals(file, read)
        // A video's size travels when the phone knows it (big ones can wait for Wi-Fi on the other phones).
        val sized = TogetherMerge.file(b, "B", listOf(Member("B", "Bo", "file-of-b")), 50) { 1234L }
        assertEquals(mapOf("v1" to 1234L, "p1" to 0L), sized.photos.associate { it.id to it.bytes })
        assertEquals(0L, read.photos.first { it.id == "v1" }.bytes)
        val plan = TogetherMerge.plan(TripRows(null), read, me = "A").media.associateBy { it.id }
        val v = plan.getValue("v1")
        assertEquals("video/mp4", v.mime)
        assertTrue(v.isVideo)
        assertEquals("media/shared-v1.mp4", v.localPath)
        assertEquals(TogetherMerge.sharedPath("v1", "video/mp4"), v.localPath)
        assertEquals(720 to 1280, v.width to v.height)
        assertEquals("drive-v1", v.sharedFileId)
        assertNull(v.sha256)
        val p = plan.getValue("p1")
        assertEquals("image/jpeg" to "media/shared-p1.jpg", p.mime to p.localPath)
    }

    @Test fun anOldFileWithoutTypesStillReadsAsPhotos() {
        val bytes = ShareCodec.encode(fileOfB(TripRows(trip, media = listOf(photo("p1", 20, file = "drive-p1")))))
        // A photo's type is left out of the file, exactly as apps before videos wrote it (format 1).
        val json = java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
        assertTrue("\"photos\"" in json)
        assertTrue("mime" !in json)
        assertEquals(1, ShareCodec.FORMAT)
        val read = ShareCodec.decode(bytes)
        assertEquals("image/jpeg", read.photos.single().mime)
        val p = TogetherMerge.plan(TripRows(null), read, me = "A").media.single()
        assertEquals("image/jpeg" to "media/shared-p1.jpg", p.mime to p.localPath)
        assertTrue(!p.isVideo)
    }

    @Test fun aStrangeTypeNeverBecomesAStrangePath() {
        val odd = fileOfB(TripRows(trip, media = listOf(video("v1", 20, file = "drive-v1"), video("v2", 20, file = "drive-v2"))))
            .let { f -> f.copy(photos = f.photos.map { if (it.id == "v1") it.copy(mime = "text/html") else it.copy(mime = "video/../../x") }) }
        val plan = TogetherMerge.plan(TripRows(null), ShareCodec.decode(ShareCodec.encode(odd)), me = "A").media.associateBy { it.id }
        assertEquals("image/jpeg" to "media/shared-v1.jpg", plan.getValue("v1").let { it.mime to it.localPath })
        assertEquals("video/mp4" to "media/shared-v2.mp4", plan.getValue("v2").let { it.mime to it.localPath })
    }

    @Test fun aVideoAnOlderAppTookForAPhotoBecomesAVideo() {
        // An older app here kept B's video as a photo (no type in its world): the next read makes it a video again.
        val local = TripRows(trip, media = listOf(video("v1", 20, author = "B", file = "drive-v1").copy(mime = "image/jpeg", localPath = "media/shared-v1.jpg", sha256 = null)))
        val incoming = fileOfB(TripRows(trip, media = listOf(video("v1", 20, file = "drive-v1"))))
        val v = TogetherMerge.plan(local, incoming, me = "A").media.single()
        assertEquals("video/mp4" to "media/shared-v1.mp4", v.mime to v.localPath)
        assertEquals("drive-v1", v.sharedFileId)
        // Once it is a video nothing more changes.
        assertTrue(TogetherMerge.plan(TripRows(trip, media = listOf(v)), incoming, me = "A").media.isEmpty())
    }

    @Test fun membersLearnFromEachOther() {
        val known = listOf(Member("B", "Bo", "file-b"))
        val file = ShareFile(
            writer = "B", writtenAt = 77, trip = TogetherMerge.file(TripRows(trip), "B", emptyList(), 0).trip,
            members = listOf(Member("B", "Bo J", "file-b2"), Member("C", "Cy", "file-c"), Member("A", "Hacked", "file-x")),
        )
        val out = TogetherMerge.members(known, file, me = "A").associateBy { it.id }
        assertEquals("Bo J", out["B"]?.name)
        assertEquals("file-b2", out["B"]?.fileId)
        assertEquals(77L, out["B"]?.seenAt)
        assertEquals("file-c", out["C"]?.fileId)
        assertNull(out["A"])
    }

    @Test fun fileRoundTripsThroughTheCodec() {
        val rows = TripRows(trip, listOf(item("i1", "Glacier", 20)), listOf(entry("e1", "Día uno ✈", 20)), listOf(photo("p1", 20, file = "drive-p1")))
        val file = fileOfB(rows)
        assertEquals(file, ShareCodec.decode(ShareCodec.encode(file)))
    }

    @Test fun deletedRowsLeaveNothingReadableInTheFile() {
        val rows = TripRows(
            trip,
            listOf(item("i1", "Secret place", 30, deleted = 30).copy(note = "door code 1234", lat = 1.0, lng = 2.0)),
            listOf(entry("e1", "Private thoughts", 30).copy(body = "{}", plainText = "private", lat = 3.0, lng = 4.0, deletedAt = 30)),
            listOf(photo("p1", 30, file = "drive-p1").copy(lat = 5.0, lng = 6.0, deletedAt = 30)),
        )
        val file = fileOfB(rows)
        assertEquals(PlanItemRow(id = "i1", tripId = "t", updatedAt = 30, deletedAt = 30), file.planItems.single())
        assertEquals(EntryRow(id = "e1", tripId = "t", updatedAt = 30, deletedAt = 30, authorId = "B"), file.entries.single())
        assertEquals(SharedPhoto(id = "p1", authorId = "B", updatedAt = 30, deletedAt = 30), file.photos.single())
        // Still a valid file for the others.
        assertEquals(file, ShareCodec.decode(ShareCodec.encode(file)))
    }

    @Test fun aTombstoneOnlyMarksTheRowDeletedHere() {
        val mine = TripRows(
            trip, listOf(item("i1", "Glacier", 30).copy(note = "bring boots")),
            listOf(entry("e1", "Day one", 30, author = "B")),
            listOf(photo("p1", 30, author = "B", file = "d1").copy(width = 800, height = 600)),
        )
        val deleted = TripRows(trip, listOf(item("i1", "Glacier", 40, deleted = 40)), listOf(entry("e1", "", 40).copy(deletedAt = 40)), listOf(photo("p1", 40).copy(deletedAt = 40)))
        val plan = TogetherMerge.plan(mine, fileOfB(deleted), "A")
        assertEquals(mine.planItems.single().copy(updatedAt = 40, deletedAt = 40), plan.planItems.single())
        assertEquals(mine.entries.single().copy(updatedAt = 40, deletedAt = 40), plan.entries.single())
        assertEquals(mine.media.single().copy(updatedAt = 40, deletedAt = 40), plan.media.single())
    }

    @Test fun everyColumnOfAPlanItemAndEntryTravels() {
        // Every field set, so a column added to Room but not to the shared rows shows up here (the rows are BackupFormat's).
        val i = PlanItem(
            "i1", "t", LocalDate.of(2027, 3, 2), 2.5, 600, "food", "Asado", "Book ahead", "El Calafate", -50.3, -72.3,
            true, "ai", "x-1", 11, 22, null, "https://example.com",
        )
        val e = Entry("e1", "t", 1000, "America/Argentina/Rio_Gallegos", "Day one", "{}", "plain", "Calafate", -50.3, -72.3, "Windy", 9.5, 11, 22, null, "B")
        val plan = TogetherMerge.plan(TripRows(trip), fileOfB(TripRows(trip, listOf(i), listOf(e.copy(authorId = null)))), "A")
        assertEquals(i, plan.planItems.single())
        assertEquals(e, plan.entries.single())
    }

    @Test fun aFarewellFileMarksTheMemberLeftAndChangesNoRows() {
        val bye = TogetherMerge.farewell("t", Member("B", "Bo", "file-of-b"), writtenAt = 99)
        val read = ShareCodec.decode(ShareCodec.encode(bye))
        assertTrue(read.planItems.isEmpty() && read.entries.isEmpty() && read.photos.isEmpty())
        assertTrue(TogetherMerge.plan(TripRows(trip, listOf(item("i1", "Glacier", 30))), read, "A").isEmpty)
        assertTrue(TogetherMerge.members(listOf(Member("B", "Bo", "file-of-b")), read, "A").single().left)
    }

    @Test fun theFingerprintMovesWithEveryChangeThatReachesTheFile() {
        val members = listOf(Member("B", "Bo", "file-of-b"))
        val rows = TripRows(trip, listOf(item("i1", "Glacier", 30)), listOf(entry("e1", "Day one", 30)), listOf(photo("p1", 30)))
        val base = TogetherMerge.fingerprint(rows, members)
        assertEquals(base, TogetherMerge.fingerprint(rows.copy(planItems = rows.planItems.reversed()), members))
        assertNotEquals(base, TogetherMerge.fingerprint(rows.copy(planItems = listOf(item("i1", "Glacier!", 31))), members))
        assertNotEquals(base, TogetherMerge.fingerprint(rows.copy(media = listOf(photo("p1", 30, file = "d1"))), members))
        assertNotEquals(base, TogetherMerge.fingerprint(rows, members + Member("C", "Cy")))
        assertNotEquals(base, TogetherMerge.fingerprint(rows.copy(trip = trip.copy(updatedAt = 11)), members))
    }

    @Test fun bothLinksTravelAndAnOlderAppsMapsLinkArrivesAsTheMapsLink() {
        val site = "https://www.perito-moreno.com/"
        val maps = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
        val both = ShareCodec.decode(ShareCodec.encode(fileOfB(TripRows(trip, listOf(item("i1", "Glacier", 20).copy(url = site, mapsUrl = maps))))))
        assertEquals(site to maps, both.planItems.single().let { it.url to it.mapsUrl })
        // An app before schema 9 sends its Maps link as the website: it becomes the Maps link here.
        val older = fileOfB(TripRows(trip, listOf(item("i1", "Glacier", 20)))).let { f -> f.copy(planItems = f.planItems.map { it.copy(url = maps) }) }
        val moved = ShareCodec.decode(ShareCodec.encode(older)).planItems.single()
        assertEquals(null to maps, moved.url to moved.mapsUrl)
        val plan = TogetherMerge.plan(TripRows(null), ShareCodec.decode(ShareCodec.encode(older)), me = "A")
        assertEquals(maps, plan.planItems.single().mapsUrl)
    }

    @Test fun damagedOrHostileFilesAreRefused() {
        val good = fileOfB(TripRows(trip, listOf(item("i1", "Glacier", 20))))
        fun refused(f: ShareFile) = try {
            ShareCodec.decode(ShareCodec.encode(f)); fail("accepted")
        } catch (_: ShareFormatException) {
        }
        refused(good.copy(writer = "Z"))
        refused(good.copy(planItems = good.planItems.map { it.copy(tripId = "other") }))
        // A place off the Earth or a link that isn't a web address costs the row only that, not the member's file.
        val mended = ShareCodec.decode(ShareCodec.encode(good.copy(planItems = good.planItems.map { it.copy(lat = 200.0, lng = 10.0, url = "javascript:alert(1)") })))
        assertEquals(listOf("Glacier" to null), mended.planItems.map { it.title to (it.lat ?: it.lng ?: it.url) })
        refused(good.copy(format = ShareCodec.FORMAT + 1))
        // A Maps link that isn't a web address goes too.
        val noMaps = ShareCodec.decode(ShareCodec.encode(good.copy(planItems = good.planItems.map { it.copy(mapsUrl = "intent://maps#end") })))
        assertNull(noMaps.planItems.single().mapsUrl)
        try {
            ShareCodec.decode("not gzip".toByteArray()); fail("accepted")
        } catch (_: ShareFormatException) {
        }
    }
}

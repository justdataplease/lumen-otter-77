package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.data.SavedClip
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.google.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The rules that keep a backup from failing or doing harm, and the saved videos' place in Drive. */
class BackupSafetyTest {
    private fun photo(id: String, driveFileId: String? = null, deletedAt: Long? = null, authorId: String? = null) =
        Media(id, localPath = "media/$id.jpg", driveFileId = driveFileId, createdAt = 1, updatedAt = 1, deletedAt = deletedAt, authorId = authorId)

    @Test fun oneBadRowNeverSpoilsABackup() {
        val bad = Snapshot(
            planItems = listOf(PlanItem("p", "t", position = Double.NaN, title = "Museum", lat = 91.0, lng = 10.0, createdAt = 1, updatedAt = 1)),
            entries = listOf(Entry("e", "t", 1, "UTC", title = "Note", lat = Double.NaN, lng = 2.0, tempC = Double.POSITIVE_INFINITY, createdAt = 1, updatedAt = 1)),
            media = listOf(photo("m").copy(lat = 10.0, lng = 181.0)),
            trackPoints = listOf(TrackPoint(1, "t", 1, 37.0, 25.0, accuracyM = Float.NaN, speedMps = 2f, altitudeM = Double.NaN), TrackPoint(2, "t", 2, Double.NaN, 25.0)),
            stops = listOf(Stop("s", "t", 1, null, 37.0, 25.0, createdAt = 1, updatedAt = 1), Stop("s2", "t", 1, null, -91.0, 25.0, createdAt = 1, updatedAt = 1)),
        )
        val clean = BackupSafety.clean(bad)
        // Each row keeps its name and loses only what can't be: a place off the Earth, a number that isn't one.
        assertEquals(Triple("Museum", null, 0.0), clean.planItems.single().let { Triple(it.title, it.lat, it.position) })
        assertEquals(Triple("Note", null, null), clean.entries.single().let { Triple(it.title, it.lng, it.tempC) })
        assertNull(clean.media.single().lat)
        assertEquals(TrackPoint(1, "t", 1, 37.0, 25.0, speedMps = 2f), clean.trackPoints.single())
        assertEquals(listOf("s"), clean.stops.map { it.id })
        // And it writes and reads back as a backup (a number that isn't one would stop the writer).
        val out = ByteArrayOutputStream()
        BackupCodec.write(BackupCodec.manifest(clean, "test", 5), clean.toData(), out)
        assertEquals(clean.planItems, BackupCodec.read(out.toByteArray().inputStream()).second.toSnapshot().planItems)
        assertEquals(sampleSnapshot(), BackupSafety.clean(sampleSnapshot()))
    }

    @Test fun bothLinksSurviveABackupAndAnOlderBackupsMapsLinkIsTheMapsLink() {
        val maps = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
        val item = PlanItem("p", "t", title = "Da Michele", url = "https://www.pizzeriadamichele.net/", mapsUrl = maps, createdAt = 1, updatedAt = 1)
        val snapshot = Snapshot(planItems = listOf(item))
        val out = ByteArrayOutputStream()
        BackupCodec.write(BackupCodec.manifest(snapshot, "test", 5), snapshot.toData(), out)
        assertEquals(listOf(item), BackupSafety.clean(BackupCodec.read(out.toByteArray().inputStream()).second.toSnapshot()).planItems)
        // Written before schema 9: no mapsUrl, the Maps link kept as the website. It restores as the Maps link.
        val old = BackupCodec.json.decodeFromString(
            BackupData.serializer(),
            """{"planItems":[{"id":"p9","tripId":"t1","title":"Halki","updatedAt":3,"url":"$maps"},{"id":"p8","tripId":"t1","title":"Site","updatedAt":3,"url":"https://example.com/"}]}""",
        )
        val restored = BackupSafety.clean(old.toSnapshot()).planItems.associateBy { it.id }
        assertEquals(null to maps, restored.getValue("p9").let { it.url to it.mapsUrl })
        assertEquals("https://example.com/" to null, restored.getValue("p8").let { it.url to it.mapsUrl })
        // Links are web addresses or nothing.
        val odd = BackupSafety.clean(Snapshot(planItems = listOf(item.copy(url = "javascript:alert(1)", mapsUrl = "intent://maps#end")))).planItems.single()
        assertEquals(null to null, odd.url to odd.mapsUrl)
    }

    @Test fun anEmptyPhoneWritesNoBackupAndANewPhoneAsksFirst() {
        assertFalse(BackupSafety.worthBackingUp(Snapshot()))
        assertFalse(BackupSafety.worthBackingUp(Snapshot(trips = listOf(Trip("t", "Gone", createdAt = 1, updatedAt = 2, deletedAt = 2)))))
        assertTrue(BackupSafety.worthBackingUp(Snapshot(trips = listOf(Trip("t", "Mine", createdAt = 1, updatedAt = 1)))))
        assertTrue(BackupSafety.worthBackingUp(Snapshot(trackPoints = listOf(TrackPoint(1, null, 1, 1.0, 1.0)))))

        // Never backed up, not asked yet, and Drive holds a backup: wait for the answer.
        assertTrue(BackupSafety.waitForRestoreAnswer(null, restoreAsked = false, driveHasData = true))
        assertFalse(BackupSafety.waitForRestoreAnswer(null, restoreAsked = true, driveHasData = true))
        assertFalse(BackupSafety.waitForRestoreAnswer(null, restoreAsked = false, driveHasData = false))
        assertFalse(BackupSafety.waitForRestoreAnswer(5L, restoreAsked = false, driveHasData = true))
    }

    @Test fun photosWithoutACheckedCopyInDriveAreCountedForTheNextBackup() {
        val media = listOf(photo("a", "d1"), photo("b", "gone"), photo("c"), photo("d", deletedAt = 5), photo("e", authorId = "member-2"), photo("f"))
        assertEquals(listOf("b", "c"), BackupSafety.photosLeft(media, verified = setOf("d1"), waiting = setOf("f")).map { it.id })
    }

    @Test fun theNewestBackupThatHoldsSomethingIsTheOneOffered() {
        val full = BackupEntry("full", "a", 10, BackupCounts(2, 3, 4))
        val empty = BackupEntry("empty", "b", 20, BackupCounts())
        val unknown = BackupEntry("old", "c", 5, null)
        assertEquals(full, BackupNames.best(listOf(empty, unknown, full)))
        assertEquals(empty, BackupNames.best(listOf(empty)))
        assertNull(BackupNames.best(emptyList()))
        assertTrue(BackupNames.hasData(unknown))
        assertEquals(10, BackupNames.KEEP)
    }

    private fun clip(id: String, trip: String = "t1", title: String = "Cyclades", at: Long = 100) = SavedClip(id, trip, title, at, 42_000, 1080, 1920)

    private fun inDrive(c: SavedClip, size: Long, fileId: String = "f-${c.id}", trashed: Boolean = false) =
        RemoteFile(fileId, BackupVideos.fileName(c), "video/mp4", size = size, appProperties = BackupVideos.properties(c), trashed = trashed)

    @Test fun eachSavedVideoGoesToDriveOnceAndReadsBack() {
        val a = clip("clip-a")
        val b = clip("clip-b", title = "A/B: \"best\" of?")
        assertEquals("A B best of.mp4", BackupVideos.fileName(b))
        assertEquals(a, BackupVideos.clipOf(inDrive(a, 10)))
        // A title too long for one Drive property is cut, never refused, and never inside a character.
        val long = clip("clip-c", title = "Ω".repeat(200))
        val props = BackupVideos.properties(long)
        assertTrue(props.all { (k, v) -> BackupVideos.fits(k, v) })
        assertTrue(props.getValue(BackupVideos.TITLE).all { it == 'Ω' })
        // Files the traveller put in the folder aren't the app's.
        assertNull(BackupVideos.clipOf(RemoteFile("x", "Holiday.mp4", size = 5)))

        val remote = listOf(inDrive(a, 10), inDrive(b, 7, trashed = true))
        // a is there whole; b's copy is in the bin; c was never sent; an empty file on the phone waits.
        assertEquals(listOf("clip-b", "clip-c"), BackupVideos.toUpload(listOf(a, b, long, clip("clip-d")), mapOf("clip-a" to 10L, "clip-b" to 7L, "clip-c" to 9L, "clip-d" to 0L), remote).map { it.id })
        // A video saved again under the same id with another size goes up again.
        assertEquals(listOf("clip-a"), BackupVideos.toUpload(listOf(a), mapOf("clip-a" to 11L), remote).map { it.id })
    }

    @Test fun aRestoreFetchesTheVideosOfItsBackup() {
        val a = clip("clip-a", at = 100)
        val b = clip("clip-b", at = 200)
        val other = clip("clip-x", trip = "deleted-trip", at = 50)
        val remote = listOf(inDrive(a, 10), inDrive(a, 12, fileId = "bigger"), inDrive(b, 7), inDrive(other, 5))
        val trips = setOf("t1")
        // The backup names its videos: exactly those the phone lacks, of trips it has, one copy each (the biggest).
        assertEquals(listOf("bigger"), BackupVideos.toFetch(remote, have = setOf("clip-b"), liveTrips = trips, inBackup = setOf("clip-a", "clip-b"), backupAt = 0).map { it.second.id })
        assertTrue(BackupVideos.toFetch(remote, have = emptySet(), liveTrips = trips, inBackup = emptySet(), backupAt = 500).isEmpty())
        // A backup older than the Videos folder names none: the videos made up to its time.
        assertEquals(listOf("clip-a"), BackupVideos.toFetch(remote, have = emptySet(), liveTrips = trips, inBackup = null, backupAt = 150).map { it.first.id })
    }

    @Test fun theVideoEditorsFilesGoByTheirPath() {
        val remote = listOf(
            RemoteFile("v1", "a.m4a", size = 5, appProperties = mapOf(BackupVideos.PATH to "clip-voice/t1/a.m4a")),
            RemoteFile("v2", "a.m4a", size = 9, appProperties = mapOf(BackupVideos.PATH to "clip-voice/t1/a.m4a")),
        )
        assertEquals(listOf("clip-voice/t1/b.m4a"), BackupVideos.filesToUpload(mapOf("clip-voice/t1/a.m4a" to 9L, "clip-voice/t1/b.m4a" to 3L, "clip-voice/t1/empty.m4a" to 0L), remote))
        assertEquals(mapOf("clip-voice/t1/a.m4a" to "v2"), BackupVideos.filesToFetch(setOf("clip-voice/t1/a.m4a", "clip-voice/t1/c.m4a"), remote).mapValues { it.value.id })
    }

    @Test fun theVideosRememberedChoicesTravelAsText() {
        val values = mapOf("speed" to 1.5f, "style" to "postcard", "beat" to true, "seconds" to 30, "at" to 99L, "tags" to setOf("b", "a"), "odd" to listOf(1))
        val text = PrefsBackup.encode(values)
        assertEquals(values - "odd", PrefsBackup.decode(text))
        assertNull(PrefsBackup.encode(emptyMap()))
        assertTrue(PrefsBackup.decode("{broken").isEmpty() && PrefsBackup.decode(null).isEmpty())
        // A value that changed type in the file is skipped, not misread.
        assertEquals(mapOf("ok" to 1), PrefsBackup.decode("""{"ok":{"t":"i","v":1},"bad":{"t":"i","v":"one"},"new":{"t":"future","v":1}}"""))
    }

    @Test fun aBackupCarriesItsVideosAndTheEditorsWork() {
        val data = sampleSnapshot().toData(BackupPreferences(clipStyle = """{"style":{"t":"s","v":"postcard"}}""")).copy(
            savedClips = listOf(ClipRow("clip-a", "t1", "Cyclades", 100, 42_000, 1080, 1920)),
            clipEdits = mapOf("t1" to """{"v":1}"""),
        )
        val out = ByteArrayOutputStream()
        BackupCodec.write(BackupCodec.manifest(sampleSnapshot(), "test", 5), data, out)
        val back = BackupCodec.read(out.toByteArray().inputStream()).second
        assertEquals(data.savedClips, back.savedClips)
        assertEquals(data.clipEdits, back.clipEdits)
        assertEquals(data.preferences?.clipStyle, back.preferences?.clipStyle)
        // A backup made before these existed reads with none.
        val old = BackupCodec.json.decodeFromString(BackupData.serializer(), """{"trips":[]}""")
        assertNull(old.savedClips)
        assertNull(old.clipEdits)
    }
}

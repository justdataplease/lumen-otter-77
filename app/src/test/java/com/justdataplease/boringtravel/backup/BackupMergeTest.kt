package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.TrackPoint
import com.justdataplease.boringtravel.data.db.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergeTest {
    /** The restore's clock: always later than anything in these tests. */
    private val now: (Long) -> Long = { maxOf(9_000L, it + 1) }

    private fun trip(id: String, title: String, updatedAt: Long, deletedAt: Long? = null) =
        Trip(id, title, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt)

    private fun item(id: String, tripId: String, title: String, updatedAt: Long, deletedAt: Long? = null) =
        PlanItem(id, tripId, title = title, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt)

    private fun photo(id: String, updatedAt: Long, driveFileId: String? = null, path: String = "media/$id.jpg", caption: String? = null) =
        Media(id, tripId = caption, localPath = path, driveFileId = driveFileId, createdAt = 1, updatedAt = updatedAt)

    private fun point(time: Long, lat: Double, lng: Double, id: Long = 0, trip: String? = "t1") = TrackPoint(id, trip, time, lat, lng)

    private fun <T> upsert(old: List<T>, new: List<T>, id: (T) -> String) = (old.associateBy(id) + new.associateBy(id)).values.toList()

    /** The phone after [plan] is written to it, as BackupManager writes it. */
    private fun write(phone: Snapshot, plan: MergePlan) = Snapshot(
        trips = upsert(phone.trips, plan.trips) { it.id },
        planItems = upsert(phone.planItems, plan.planItems) { it.id },
        entries = upsert(phone.entries, plan.entries) { it.id },
        media = upsert(phone.media, plan.media) { it.id },
        trackPoints = (phone.trackPoints.filterNot { it.tripId in plan.clearRoutes } + plan.trackPoints).mapIndexed { i, p -> p.copy(id = i + 1L) },
        stops = upsert(phone.stops, plan.stops) { it.id },
        countryMarks = upsert(phone.countryMarks, plan.countryMarks) { it.id },
        books = upsert(phone.books, plan.books) { it.id },
    )

    /** The rows as they read, whenever they were last stamped. */
    private fun unstamped(s: Snapshot) = s.copy(
        trips = s.trips.map { it.copy(updatedAt = 0) }, planItems = s.planItems.map { it.copy(updatedAt = 0) },
        entries = s.entries.map { it.copy(updatedAt = 0) }, media = s.media.map { it.copy(updatedAt = 0) },
        trackPoints = s.trackPoints.map { it.copy(id = 0) }.sortedBy { it.time },
        stops = s.stops.map { it.copy(updatedAt = 0) }, countryMarks = s.countryMarks.map { it.copy(updatedAt = 0) },
        books = s.books.map { it.copy(updatedAt = 0) },
    )

    @Test fun thePhoneBecomesTheBackup() {
        val backup = sampleSnapshot()
        // Since the backup: everything changed, a trip with a note and a route added, a country taken off.
        val phone = backup.copy(
            trips = backup.trips.map { it.copy(title = "Edited", updatedAt = 5_000) } + trip("new", "Added later", 6_000),
            planItems = backup.planItems.map { it.copy(note = "Changed", updatedAt = 5_000) } + item("p9", "t1", "Added later", 6_000),
            entries = backup.entries.map { it.copy(body = "Rewritten", updatedAt = 5_000) } + Entry("e9", "new", 1, "UTC", createdAt = 1, updatedAt = 6_000),
            media = backup.media.map { it.copy(entryId = "elsewhere", updatedAt = 5_000) } + photo("m9", 6_000, caption = "new"),
            trackPoints = backup.trackPoints.mapIndexed { i, p -> p.copy(id = i + 1L) } + point(5, 1.0, 2.0, id = 8) + point(6, 1.0, 2.0, id = 9, trip = "new"),
            stops = backup.stops.map { it.copy(name = "Renamed", updatedAt = 5_000) },
            countryMarks = backup.countryMarks.map { it.copy(deletedAt = 5_000, updatedAt = 5_000) },
            books = backup.books.map { it.copy(pages = "{}", updatedAt = 5_000) },
        )
        val plan = BackupMerge.replace(phone, backup, stamp = now)
        val after = write(phone, plan)

        // What was added since is deleted (the way the app deletes), with the restore's stamp.
        val added = setOf("new", "p9", "e9", "m9")
        assertEquals(9_000L, after.trips.single { it.id == "new" }.deletedAt)
        assertNotNull(after.planItems.single { it.id == "p9" }.deletedAt)
        assertNotNull(after.entries.single { it.id == "e9" }.deletedAt)
        assertNotNull(after.media.single { it.id == "m9" }.deletedAt)
        // Everything else reads as the backup does, and the restore is the latest change to each row it touched.
        val rest = after.copy(
            trips = after.trips.filterNot { it.id in added }, planItems = after.planItems.filterNot { it.id in added },
            entries = after.entries.filterNot { it.id in added }, media = after.media.filterNot { it.id in added },
        )
        assertEquals(unstamped(backup), unstamped(rest))
        assertTrue((plan.trips + plan.planItems).isNotEmpty() && plan.trips.all { it.updatedAt >= 9_000 } && plan.planItems.all { it.updatedAt >= 9_000 })
        // Again: nothing left to do.
        assertTrue(BackupMerge.replace(after, backup, stamp = now).isEmpty)
    }

    @Test fun whatWasDeletedSinceComesBackAndWhatTheBackupDeletedGoesAgain() {
        val back = BackupMerge.replace(Snapshot(trips = listOf(trip("a", "Cyclades", 50, deletedAt = 50))), Snapshot(trips = listOf(trip("a", "Cyclades", 10))), stamp = now)
        assertEquals(trip("a", "Cyclades", 9_000), back.trips.single())

        // Deleted in the backup, brought back on the phone since: deleted again, as the backup has it.
        val gone = BackupMerge.replace(Snapshot(trips = listOf(trip("a", "Cyclades", 15))), Snapshot(trips = listOf(trip("a", "Cyclades", 12, deletedAt = 12))), stamp = now)
        assertEquals(trip("a", "Cyclades", 9_000, deletedAt = 12), gone.trips.single())
    }

    @Test fun aNewerBackupFromAnotherPhoneComesInAsItIs() {
        val plan = BackupMerge.replace(Snapshot(trips = listOf(trip("a", "Old", 10))), Snapshot(trips = listOf(trip("a", "New", 20))), stamp = now)
        assertEquals(trip("a", "New", 20), plan.trips.single())
    }

    @Test fun anEmptyPhoneTakesEverything() {
        val backup = sampleSnapshot()
        val plan = BackupMerge.replace(Snapshot(), backup, stamp = now)
        assertEquals(backup.trips, plan.trips)
        assertEquals(backup.planItems, plan.planItems)
        assertEquals(backup.entries, plan.entries)
        assertEquals(backup.media, plan.media)
        assertEquals(backup.stops, plan.stops)
        assertEquals(backup.trackPoints, plan.trackPoints)
        assertEquals(backup.countryMarks, plan.countryMarks)
        assertEquals(backup.books, plan.books)
        assertTrue(plan.clearRoutes.isEmpty())
    }

    @Test fun theExamplesAreNeverRemoved() {
        val phone = Snapshot(
            trips = listOf(trip("demo-1", "Example", 10)),
            planItems = listOf(item("d1", "demo-1", "Example stop", 10)),
            trackPoints = listOf(point(1, 1.0, 2.0, id = 3, trip = "demo-1")),
        )
        assertTrue(BackupMerge.replace(phone, Snapshot(), stamp = now).isEmpty)
    }

    @Test fun aSharedTripOnlyGainsWhatIsMissing() {
        val theirs = Entry("e1", "t1", 1, "UTC", title = "Theirs", createdAt = 1, updatedAt = 10, authorId = "member-2")
        val backup = Snapshot(
            trips = listOf(trip("t1", "Ours", 10)),
            planItems = listOf(item("a", "t1", "Museum", 10), item("b", "t1", "Lunch", 10), item("d", "t1", "Only in the backup", 10)),
            entries = listOf(theirs),
            trackPoints = listOf(point(1, 1.0, 2.0), point(2, 1.0, 2.0)),
        )
        val phone = Snapshot(
            trips = listOf(trip("t1", "Ours now", 50)),
            planItems = listOf(item("a", "t1", "Museum at ten", 60), item("b", "t1", "Lunch", 70, deletedAt = 70), item("c", "t1", "Added by a friend", 80)),
            entries = listOf(theirs.copy(updatedAt = 90, deletedAt = 90)),
            trackPoints = listOf(point(1, 1.0, 2.0, id = 4), point(3, 1.0, 2.0, id = 5)),
        )
        val plan = BackupMerge.replace(phone, backup, shared = setOf("t1"), stamp = now)
        // The newer title and plan change stay, the friend's item stays, their own deleted note stays deleted.
        assertTrue(plan.trips.isEmpty() && plan.entries.isEmpty() && plan.clearRoutes.isEmpty())
        // What this phone deleted comes back, and what it never had comes in.
        assertEquals(listOf(item("b", "t1", "Lunch", 9_000), item("d", "t1", "Only in the backup", 10)), plan.planItems.sortedBy { it.id })
        assertEquals(listOf(2L), plan.trackPoints.map { it.time })
    }

    @Test fun laterChangesWinAndTiesKeepThePhonesRow() {
        val local = listOf(trip("a", "Phone A", 10), trip("b", "Phone B", 20), trip("c", "Phone C", 30))
        val incoming = listOf(trip("a", "Backup A", 11), trip("b", "Backup B", 19), trip("c", "Backup C", 30), trip("d", "Backup D", 5))
        val out = BackupMerge.newer(local, incoming, { it.id }, { it.updatedAt })
        assertEquals(listOf("Backup A", "Backup D"), out.map { it.title })
    }

    @Test fun whereAPhotoIsInDriveIsKeptWhicheverRowWins() {
        // The backup was written before the upload: the phone's Drive id stays on the backup's row.
        val a = BackupMerge.media(listOf(photo("p", 30, driveFileId = "d1", caption = "mine")), listOf(photo("p", 20, caption = "old")), stamp = now).single()
        assertEquals("d1", a.driveFileId)
        assertEquals("old", a.tripId)
        assertEquals(9_000L, a.updatedAt)

        // The backup knows it and the phone doesn't: it comes with the row.
        assertEquals("d2", BackupMerge.media(listOf(photo("p", 30, caption = "mine")), listOf(photo("p", 20, driveFileId = "d2")), stamp = now).single().driveFileId)

        // The same photo, only stamped later here: nothing to write.
        assertTrue(BackupMerge.media(listOf(photo("p", 30, "d1")), listOf(photo("p", 20, "d1")), stamp = now).isEmpty())
    }

    @Test fun photoPathsCantLeaveTheMediaFolder() {
        val bad = listOf(
            photo("p1", 1, path = "../shared_prefs/settings.xml"),
            photo("p2", 1, path = "media/../../databases/boringtravel.db"),
            photo("p3", 1, path = "/data/data/x.jpg"),
            photo("p4", 1, path = ""),
        )
        val out = BackupMerge.media(emptyList(), bad)
        assertEquals(listOf("media/p1.jpg", "media/p2.jpg", "media/p3.jpg", "media/p4.jpg"), out.map { it.localPath })
        assertEquals("media/x.png", BackupMerge.safePath(Media("x", localPath = "media/x.png", mime = "image/png", createdAt = 1, updatedAt = 1)).localPath)
        assertEquals("media/y.png", BackupMerge.safePath(Media("y", localPath = "a/b", mime = "image/png", createdAt = 1, updatedAt = 1)).localPath)
    }

    @Test fun aRestoredVideoKeepsAVideosPath() {
        fun video(id: String, path: String, sum: String) =
            Media(id, localPath = path, mime = "video/mp4", sha256 = sum, createdAt = 1, updatedAt = 1)
        // As backed up: its own path, untouched.
        assertEquals("media/v1.mp4", BackupMerge.safePath(video("v1", "media/v1.mp4", "a")).localPath)
        // A path that can't be used gets a video's name, so it still plays.
        assertEquals("media/v2.mp4", BackupMerge.safePath(video("v2", "../v2.mp4", "b")).localPath)
        assertEquals("media/v3.3gp", BackupMerge.safePath(video("v3", "", "c").copy(mime = "video/3gpp")).localPath)
        // Another file already at its path: a new name, still an mp4.
        val here = video("other", "media/v4.mp4", "d")
        val restored = BackupMerge.media(listOf(here), listOf(video("v4", "media/v4.mp4", "e"))).single()
        assertTrue(restored.localPath.startsWith("media/restored-"))
        assertTrue(restored.localPath.endsWith(".mp4"))
        assertEquals("video/mp4", restored.mime)
    }

    @Test fun checksumChangesNeverBorrowTheOtherPhotosDriveId() {
        val current = photo("p", 10, "original-drive").copy(sha256 = "first-hash")
        val replacement = photo("p", 20).copy(sha256 = "second-hash")
        assertNull(BackupMerge.media(listOf(current), listOf(replacement), stamp = now).single().driveFileId)
        // And back: the backup's photo with its own copy in Drive, not the newer file's.
        val back = BackupMerge.media(listOf(replacement.copy(driveFileId = "newer-drive", updatedAt = 30)), listOf(current), stamp = now).single()
        assertEquals("first-hash" to "original-drive", back.sha256 to back.driveFileId)
    }

    @Test fun collidingIncomingPathsCannotOverwriteAnotherOriginal() {
        val original = photo("first", 10, path = "media/shared.jpg").copy(sha256 = "first-hash")
        val other = photo("second", 20, path = "media/shared.jpg").copy(sha256 = "second-hash")
        val restored = BackupMerge.media(listOf(original), listOf(other)).single()
        assertTrue(restored.localPath != original.localPath)
        assertTrue(restored.localPath.startsWith("media/"))
        assertTrue(BackupMerge.media(listOf(original, restored), listOf(other)).isEmpty())
    }

    @Test fun unsafeIdsDoNotCollapseOntoOnePhotoFilename() {
        val one = BackupMerge.safePath(photo("a/b", 1, path = "../bad"))
        val two = BackupMerge.safePath(photo("ab", 1, path = "../bad"))
        assertTrue(one.localPath != two.localPath)
        assertTrue(!one.localPath.contains(".."))
        assertEquals(one, BackupMerge.safePath(one))
    }

    @Test fun aTripsRouteBecomesTheBackups() {
        val phone = listOf(point(1, 37.0, 25.0, id = 7), point(2, 37.1, 25.1, id = 8), point(1, 1.0, 1.0, id = 9, trip = "same"), point(4, 2.0, 2.0, id = 10, trip = "gone"))
        val backup = listOf(point(1, 37.0, 25.0), point(3, 37.2, 25.3), point(3, 37.2, 25.3), point(1, 1.0, 1.0, trip = "same"), point(5, 3.0, 3.0, trip = null))
        val (clear, add) = BackupMerge.routes(phone, backup)
        // t1 differs and "gone" isn't in the backup: both cleared. "same" is left alone. The points outside trips are new.
        assertEquals(setOf("t1", "gone"), clear.toSet())
        assertEquals(listOf("t1" to 1L, "t1" to 3L, null to 5L), add.map { it.tripId to it.time })
        assertTrue(add.all { it.id == 0L })
    }

    @Test fun routePointsMatchByTimeAndPlace() {
        val local = listOf(point(1, 37.0, 25.0, id = 7), point(2, 37.1, 25.1, id = 8))
        val incoming = listOf(
            point(1, 37.0, 25.0, id = 99), // already here
            point(2, 37.1, 25.2), // same time, other place
            point(3, 37.2, 25.3),
            point(3, 37.2, 25.3), // twice in the backup
        )
        val out = BackupMerge.trackPoints(local, incoming)
        assertEquals(listOf(2L to 25.2, 3L to 25.3), out.map { it.time to it.lng })
        assertTrue(out.all { it.id == 0L })
    }

    @Test fun samePointInDifferentTripsRetainsBothAssociations() {
        val point = point(100, 37.0, 25.0)
        val added = BackupMerge.trackPoints(listOf(point), listOf(point.copy(tripId = "another-trip"), point.copy(tripId = null)))
        assertEquals(listOf("another-trip", null), added.map { it.tripId })
        assertTrue(BackupMerge.trackPoints(listOf(point) + added, listOf(point) + added).isEmpty())
    }

    @Test fun zipToEmptyPhoneThenRepeatedRestoreIsIdempotentForAllEntities() {
        val original = sampleSnapshot()
        val output = java.io.ByteArrayOutputStream()
        BackupCodec.write(BackupCodec.manifest(original, "preview", 5), original.toData(), output)
        val restored = BackupCodec.read(output.toByteArray().inputStream()).second.toSnapshot()
        val after = write(Snapshot(), BackupMerge.replace(Snapshot(), restored, stamp = now))
        assertEquals(original.copy(trackPoints = after.trackPoints), after)
        assertTrue(BackupMerge.replace(after, restored, stamp = now).isEmpty)
    }
}

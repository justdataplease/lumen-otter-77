package com.justdataplease.boringtravel.together

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.google.NotSharedException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** Whole conversations between phones, each with its own Drive, over one fake Google Drive. */
class TogetherEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * Google Drive: files and folders by id. A file is readable by link when it, or the folder it is in, is shared
     * ("anyone with the link"); deleting a folder deletes what is in it.
     */
    private class Cloud {
        val files = HashMap<String, ByteArray>()
        val folders = HashSet<String>()
        val parent = HashMap<String, String>()
        val shared = HashSet<String>()
        var next = 0
        var clock = 1_000L

        /** Content reads by link (checksums don't count). */
        var reads = 0
        var downloads = 0
        var replaces = 0

        /** Every upload's name and type, and the size limit each download was asked to keep to (by file id). */
        val uploads = ArrayList<Pair<String, String>>()
        val limits = HashMap<String, Long>()

        /** Firestore's trip mailboxes: box → poster's uid → entry; [mailDown]: every call fails. */
        val mailboxes = HashMap<String, LinkedHashMap<String, MailEntry>>()
        var mailDown = false
        var mailPosts = 0
        var mailReads = 0

        fun id(owner: String) = "${owner}file${next++}xx"

        fun readable(id: String): ByteArray? = files[id]?.takeIf { id in shared || parent[id] in shared }

        fun deleteFolder(id: String) {
            folders -= id
            parent.filterValues { it == id }.keys.toList().forEach { files -= it; parent -= it }
        }
    }

    /** One phone: its rows, its Drive, its photo folder, its engine. [metered]: on mobile data with "Only on Wi-Fi" on. */
    private inner class Phone(val name: String, val cloud: Cloud, var driveOn: Boolean = true, var metered: Boolean = false) {
        var trips = HashMap<String, Trip>()
        val items = HashMap<String, PlanItem>()
        val entries = HashMap<String, Entry>()
        val media = HashMap<String, Media>()
        val dir: File = tmp.newFolder(name)
        var stored = TogetherState()

        /** The next own-Drive call that should fail, as a dropped connection. */
        var failNext: String? = null

        private fun maybeFail(call: String) {
            if (failNext == call) {
                failNext = null
                throw java.net.UnknownHostException("www.googleapis.com")
            }
        }

        private val rows = object : TogetherRows {
            override suspend fun trip(tripId: String) = trips[tripId]

            override suspend fun rows(tripId: String) = TripRows(
                trips[tripId], items.values.filter { it.tripId == tripId }, entries.values.filter { it.tripId == tripId },
                media.values.filter { it.tripId == tripId },
            )

            override suspend fun merge(tripId: String, ids: Set<String>, compute: (TripRows, Set<String>) -> TogetherPlan): TogetherPlan {
                val elsewhere = ids.filter { id ->
                    items[id]?.let { it.tripId != tripId } == true || entries[id]?.let { it.tripId != tripId } == true ||
                        media[id]?.let { it.tripId != tripId } == true
                }.toSet()
                val plan = compute(rows(tripId), elsewhere)
                plan.trip?.let { trips[it.id] = it }
                plan.planItems.forEach { items[it.id] = it }
                plan.entries.forEach { entries[it.id] = it }
                plan.media.forEach { media[it.id] = it }
                return plan
            }

            override suspend fun setSharedFile(expected: Media, fileId: String): Boolean {
                val m = media[expected.id] ?: return false
                media[m.id] = m.copy(sharedFileId = fileId, updatedAt = maxOf(cloud.clock++, m.updatedAt + 1))
                return true
            }

            override suspend fun clearSharedFile(expected: Media, fileId: String): Boolean {
                val m = media[expected.id]?.takeIf { it.sharedFileId == fileId } ?: return false
                media[m.id] = m.copy(sharedFileId = null)
                return true
            }
        }

        private val own = object : OwnDrive {
            override suspend fun connected() = driveOn
            override suspend fun sharedFolder(name: String): String {
                maybeFail("folder")
                return cloud.id(this@Phone.name).also { cloud.folders += it; cloud.shared += it }
            }
            override suspend fun create(folderId: String, name: String, content: ByteArray): String? {
                maybeFail("create")
                if (folderId !in cloud.folders) return null
                return cloud.id(this@Phone.name).also { cloud.files[it] = content; cloud.parent[it] = folderId; cloud.shared += it }
            }
            override suspend fun replace(fileId: String, content: ByteArray): Boolean {
                if (fileId !in cloud.files) return false
                cloud.files[fileId] = content
                cloud.replaces++
                return true
            }
            override suspend fun upload(folderId: String, file: File, name: String, mime: String): String? {
                maybeFail("upload")
                if (folderId !in cloud.folders) return null
                cloud.uploads += name to mime
                return cloud.id(this@Phone.name).also { cloud.files[it] = file.readBytes(); cloud.parent[it] = folderId }
            }
            override suspend fun delete(fileId: String) {
                cloud.files -= fileId
                cloud.parent -= fileId
            }
            override suspend fun unshare(id: String) {
                cloud.shared -= id
            }
        }

        private val reader = object : LinkReader {
            override suspend fun checksum(fileId: String): String? {
                val bytes = cloud.readable(fileId) ?: throw NotSharedException()
                return java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
            }
            override suspend fun read(fileId: String, maxBytes: Long): ByteArray {
                cloud.reads++
                return cloud.readable(fileId) ?: throw NotSharedException()
            }
            override suspend fun download(fileId: String, target: File, maxBytes: Long) {
                cloud.downloads++
                cloud.limits[fileId] = maxBytes
                val bytes = cloud.readable(fileId) ?: throw NotSharedException()
                if (bytes.size > maxBytes) throw java.io.IOException("Too big")
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }

        /** The photos a small copy was made of (never a video: it goes as it is). */
        val smallCopies = ArrayList<String>()

        private val photos = object : PhotoFiles {
            override fun file(media: Media) = File(dir, media.localPath)
            override fun smallCopy(media: Media) = File(dir, media.localPath).takeIf { it.isFile }.also { smallCopies += media.id }
        }

        private var ids = 0
        val engine = TogetherEngine(
            rows, own, reader, photos,
            store = object : TogetherEngine.StateStore {
                override suspend fun read() = stored
                override suspend fun write(state: TogetherState) { stored = state }
            },
            clock = { cloud.clock++ },
            newId = { "$name-${ids++}" },
            bigFilesWait = { metered },
        )

        fun now() = cloud.clock++

        /** The Firebase user signed in on this phone (null: signed out). */
        val account = MutableStateFlow<String?>("uid-$name")

        private val mailService = object : MailService {
            override val uid: StateFlow<String?> = account

            override suspend fun post(box: String, entry: MailEntry) {
                if (cloud.mailDown) throw java.io.IOException("Firestore is down")
                check(entry.uid == account.value) { "posted as someone else" }
                cloud.mailboxes.getOrPut(box) { LinkedHashMap() }[entry.uid] = entry
                cloud.mailPosts++
            }

            override suspend fun remove(box: String) {
                if (cloud.mailDown) throw java.io.IOException("Firestore is down")
                cloud.mailboxes[box]?.remove(account.value)
            }

            override suspend fun read(box: String): List<MailEntry> {
                if (cloud.mailDown) throw java.io.IOException("Firestore is down")
                cloud.mailReads++
                return cloud.mailboxes[box]?.values?.toList().orEmpty()
            }

            override fun watch(box: String): Flow<List<MailEntry>> = flow { emit(read(box)) }
        }

        /** This phone's side of the trips' mailboxes; its clock only moves when a test moves it. */
        val mail by lazy { TogetherMail(engine, mailService, clock = { cloud.clock }) }

        fun addItem(id: String, title: String, tripId: String = "t") {
            val t = now()
            items[id] = PlanItem(id, tripId, LocalDate.of(2027, 3, 2), 1.0, null, "place", title, createdAt = t, updatedAt = t)
        }

        fun edit(id: String, title: String) {
            val i = items.getValue(id)
            items[id] = i.copy(title = title, updatedAt = maxOf(now(), i.updatedAt + 1))
        }

        fun write(id: String, text: String) {
            val t = now()
            entries[id] = Entry(id, "t", t, "UTC", title = text, createdAt = t, updatedAt = t)
        }

        fun photo(id: String, entryId: String? = null) {
            val t = now()
            val m = Media(id, "t", entryId, "media/$id.jpg", createdAt = t, updatedAt = t)
            File(dir, m.localPath).apply { parentFile?.mkdirs(); writeText("jpeg of $id by ${this@Phone.name}") }
            media[id] = m
        }

        /** A journal video, as the editor stores one (compressed, files/media/<id>.mp4). */
        fun video(id: String, entryId: String? = null) {
            val t = now()
            val m = Media(id, "t", entryId, "media/$id.mp4", mime = "video/mp4", width = 720, height = 1280, sha256 = "sum-$id", createdAt = t, updatedAt = t)
            File(dir, m.localPath).apply { parentFile?.mkdirs(); writeText("mp4 of $id by ${this@Phone.name}") }
            media[id] = m
        }

        fun titles() = items.values.filter { it.deletedAt == null && it.tripId == "t" }.map { it.title }.sorted()

        suspend fun shared() = engine.load().of("t")!!
    }

    /** A joins B: A invites, B joins and sends the link back, A opens it. */
    private suspend fun together(a: Phone, b: Phone): ShareLink {
        val invite = a.engine.share("t", "Ana", "Patagonia")
        b.engine.open(invite, "Bo")
        a.engine.open(b.engine.share("t", "Bo", "Patagonia"), "Ana")
        return invite
    }

    private fun trip(cloud: Cloud) = Trip("t", "Patagonia", startDate = LocalDate.of(2027, 3, 1), endDate = LocalDate.of(2027, 3, 31), createdAt = 1, updatedAt = cloud.clock++)

    @Test fun twoPhonesPlanTogether() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        a.addItem("i1", "Glacier")

        val invite = a.engine.share("t", "Ana", "Patagonia")
        assertEquals("Ana", invite.name)

        // B joins: the trip arrives, as it is on A's phone.
        assertTrue(b.engine.open(invite, "Bo") is TogetherEngine.Opened.Joined)
        assertEquals("Patagonia", b.trips["t"]?.title)
        assertEquals(listOf("Glacier"), b.titles())

        // B makes their copy and sends the link back; A adds them.
        val back = b.engine.share("t", "Bo", "Patagonia")
        assertTrue(a.engine.open(back, "Ana") is TogetherEngine.Opened.Added)

        // Both change things; after one sync each, both phones agree.
        b.addItem("i2", "Fitz Roy")
        b.edit("i1", "Perito Moreno glacier")
        a.addItem("i3", "Asado")
        b.engine.sync("t")
        a.engine.sync("t")
        b.engine.sync("t")
        assertEquals(listOf("Asado", "Fitz Roy", "Perito Moreno glacier"), a.titles())
        assertEquals(a.titles(), b.titles())

        // A deletion travels too.
        val g = a.items.getValue("i3")
        a.items["i3"] = g.copy(deletedAt = a.now(), updatedAt = a.now())
        a.engine.sync("t")
        b.engine.sync("t")
        assertEquals(listOf("Fitz Roy", "Perito Moreno glacier"), b.titles())
    }

    @Test fun aThirdMemberIsLearnedFromTheOthers() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        val c = Phone("c", cloud)
        a.trips["t"] = trip(cloud)
        val invite = a.engine.share("t", "Ana", "Patagonia")
        b.engine.open(invite, "Bo")
        a.engine.open(b.engine.share("t", "Bo", "Patagonia"), "Ana")
        // C joins from A's invite and sends their link only to A.
        c.engine.open(invite, "Cy")
        a.engine.open(c.engine.share("t", "Cy", "Patagonia"), "Ana")
        c.addItem("c1", "Ushuaia")
        c.engine.sync("t")
        a.engine.sync("t") // A reads C and now lists C in its file.
        b.engine.sync("t") // B learns C from A's file…
        b.engine.sync("t") // …and reads C directly.
        assertTrue(b.engine.load().of("t")!!.members.any { it.name == "Cy" && it.fileId != null })
        assertEquals(listOf("Ushuaia"), b.titles())
    }

    @Test fun photosAndEntriesCrossWithTheirAuthor() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        a.write("e1", "First day")
        a.photo("p1", entryId = "e1")
        val invite = a.engine.share("t", "Ana", "Patagonia")
        // Sharing sends A's photo's small copy.
        a.engine.sync("t")
        b.engine.open(invite, "Bo")
        b.engine.sync("t")
        val e = b.entries.getValue("e1")
        assertEquals(a.engine.load().of("t")!!.me, e.authorId)
        val p = b.media.getValue("p1")
        assertNotNull(p.sharedFileId)
        assertEquals("jpeg of p1 by a", File(b.dir, p.localPath).readText())
        // B's phone never sends A's photo on as its own.
        b.engine.share("t", "Bo", "Patagonia")
        assertNull(b.media.values.firstOrNull { it.authorId == null })
    }

    @Test fun aVideoWrittenOnOnePhoneArrivesPlayableOnTheOther() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        a.write("e1", "On the glacier")
        a.photo("p1", entryId = "e1")
        a.video("v1", entryId = "e1")
        together(a, b)
        a.engine.sync("t")
        // The video goes up as it is, an mp4 of its own type; the photo as its small JPEG copy.
        assertTrue(("v1.mp4" to "video/mp4") in cloud.uploads)
        assertTrue(("p1.jpg" to "image/jpeg") in cloud.uploads)
        assertFalse("v1" in a.smallCopies)
        b.engine.sync("t")
        val v = b.media.getValue("v1")
        assertEquals("video/mp4", v.mime)
        assertEquals("media/shared-v1.mp4", v.localPath)
        assertEquals("e1", v.entryId)
        assertEquals(720 to 1280, v.width to v.height)
        assertEquals("mp4 of v1 by a", File(b.dir, v.localPath).readText())
        assertEquals(TogetherEngine.MAX_VIDEO_BYTES, cloud.limits[a.media.getValue("v1").sharedFileId])
        assertEquals(com.justdataplease.boringtravel.backup.BackupArchive.MAX_PHOTO_BYTES, TogetherEngine.MAX_VIDEO_BYTES)
        // The photo is still a photo, read with the photo limit.
        val p = b.media.getValue("p1")
        assertEquals("image/jpeg" to "media/shared-p1.jpg", p.mime to p.localPath)
        assertEquals(TogetherEngine.MAX_PHOTO_BYTES, cloud.limits[a.media.getValue("p1").sharedFileId])
        assertEquals(1, b.engine.photosVersion.value)
        // B's phone never sends A's video on as its own, and A's original stays A's.
        b.engine.sync("t")
        assertNull(b.media.values.firstOrNull { it.authorId == null })
        assertEquals("sum-v1", a.media.getValue("v1").sha256)
    }

    @Test fun bigVideosWaitForWifiWhenAskedAndStillGoLater() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        a.video("big")
        java.io.RandomAccessFile(File(a.dir, "media/big.mp4"), "rw").use { it.setLength(TogetherEngine.WAIT_FOR_WIFI_BYTES + 1) }
        a.video("small")
        together(a, b)
        // On mobile data with Only on Wi-Fi on: the small video goes up, the big one waits (no failure, nothing lost).
        a.metered = true
        a.engine.sync("t")
        assertNull(a.media.getValue("big").sharedFileId)
        assertNotNull(a.media.getValue("small").sharedFileId)
        assertTrue(a.engine.status.value["t"] is SyncStatus.Done)
        a.metered = false
        a.engine.sync("t")
        assertNotNull(a.media.getValue("big").sharedFileId)
        // The other phone learns both sizes from the file; on mobile data only the small one comes down.
        b.metered = true
        b.engine.sync("t")
        assertTrue(File(b.dir, b.media.getValue("small").localPath).isFile)
        assertFalse(File(b.dir, b.media.getValue("big").localPath).exists())
        b.engine.sync("t")
        assertFalse(File(b.dir, b.media.getValue("big").localPath).exists())
        // On Wi-Fi it comes at once (waiting was no failure, so no growing wait).
        b.metered = false
        b.engine.sync("t")
        assertEquals(TogetherEngine.WAIT_FOR_WIFI_BYTES + 1, File(b.dir, b.media.getValue("big").localPath).length())
    }

    @Test fun aDeletedVideoLeavesTheSharedCopy() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        a.video("v1")
        together(a, b)
        a.engine.sync("t")
        val copy = a.media.getValue("v1").sharedFileId!!
        assertTrue(copy in cloud.files)
        val m = a.media.getValue("v1")
        a.media["v1"] = m.copy(deletedAt = a.now(), updatedAt = a.now())
        a.engine.sync("t")
        assertFalse(copy in cloud.files)
        b.engine.sync("t")
        assertNotNull(b.media.getValue("v1").deletedAt)
    }

    @Test fun aViewerWithoutDriveStillSeesEverything() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud, driveOn = false)
        a.trips["t"] = trip(cloud)
        a.addItem("i1", "Glacier")
        b.engine.open(a.engine.share("t", "Ana", "Patagonia"), "Bo")
        a.addItem("i2", "Lake")
        a.engine.sync("t")
        b.engine.sync("t")
        assertEquals(listOf("Glacier", "Lake"), b.titles())
        assertNull(b.engine.load().of("t")!!.fileId)
        try {
            b.engine.share("t", "Bo", "Patagonia"); fail("shared without Drive")
        } catch (_: TogetherException) {
        }
    }

    @Test fun aDeadLinkChangesNothing() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        val invite = a.engine.share("t", "Ana", "Patagonia")
        cloud.files.remove(invite.fileId)
        try {
            b.engine.open(invite, "Bo"); fail("joined a dead link")
        } catch (_: TogetherException) {
        }
        assertTrue(b.trips.isEmpty())
        assertTrue(b.engine.load().trips.isEmpty())
    }

    @Test fun leavingKeepsTheTripForTheOthersAndCanBeUndoneByJoiningAgain() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        a.addItem("i1", "Glacier")
        val invite = a.engine.share("t", "Ana", "Patagonia")
        b.engine.open(invite, "Bo")
        a.engine.open(b.engine.share("t", "Bo", "Patagonia"), "Ana")
        b.engine.leave("t")
        b.trips["t"] = b.trips.getValue("t").let { it.copy(deletedAt = b.now(), updatedAt = b.now()) }
        a.engine.sync("t")
        assertTrue(a.engine.load().of("t")!!.members.single().left)
        assertNull(a.trips.getValue("t").deletedAt)
        // B joins again from the old invite: the trip comes back.
        assertTrue(b.engine.open(invite, "Bo") is TogetherEngine.Opened.Joined)
        assertNull(b.trips.getValue("t").deletedAt)
    }

    @Test fun anUnchangedTripIsNotWrittenAgain() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        a.trips["t"] = trip(cloud)
        val link = a.engine.share("t", "Ana", "Patagonia")
        val before = cloud.files.getValue(link.fileId)
        a.engine.sync("t")
        assertTrue(before === cloud.files.getValue(link.fileId))
        a.addItem("i1", "Glacier")
        a.engine.sync("t")
        assertFalse(before === cloud.files.getValue(link.fileId))
    }

    @Test fun aRestoredPhoneContinuesAsTheSameMember() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        a.trips["t"] = trip(cloud)
        a.engine.share("t", "Ana", "Patagonia")
        val saved = a.engine.exportState()!!
        val restored = Phone("a2", cloud)
        restored.trips["t"] = a.trips.getValue("t")
        restored.engine.importState(saved)
        assertEquals(a.engine.load().of("t")!!.me, restored.engine.load().of("t")!!.me)
        assertEquals(a.engine.load().of("t")!!.fileId, restored.engine.load().of("t")!!.fileId)
    }

    @Test fun anUnchangedFileIsNotDownloadedAgain() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        together(a, b)
        b.engine.sync("t")
        val reads = cloud.reads
        val writes = cloud.replaces
        // Nothing changed anywhere: Drive's checksum says so, and nothing is downloaded or written.
        b.engine.sync("t")
        b.engine.sync("t")
        assertEquals(reads, cloud.reads)
        assertEquals(writes, cloud.replaces)
        a.addItem("i1", "Glacier")
        a.engine.sync("t")
        val before = cloud.reads
        b.engine.sync("t")
        assertEquals(before + 1, cloud.reads)
        assertEquals(listOf("Glacier"), b.titles())
    }

    @Test fun aPhotoThatKeepsFailingWaitsLongerAndAnArrivalIsAnnounced() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        a.photo("p1")
        together(a, b)
        a.engine.sync("t")
        val copy = a.media.getValue("p1").sharedFileId!!
        val bytes = cloud.files.remove(copy)!!
        b.engine.sync("t")
        assertEquals(1, cloud.downloads)
        // Tried again only after the wait, not on every sync.
        b.engine.sync("t")
        b.engine.sync("t")
        assertEquals(1, cloud.downloads)
        assertEquals(0, b.engine.photosVersion.value)
        cloud.files[copy] = bytes
        cloud.clock += TogetherEngine.MISS_FIRST_MS
        b.engine.sync("t")
        assertEquals(2, cloud.downloads)
        assertEquals(1, b.engine.photosVersion.value)
        assertEquals(TogetherEngine.MISS_MAX_MS, TogetherEngine.retryWait(30))
    }

    @Test fun ownPhotosAddedAfterTheBackupComeBackToARecoveredPhone() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        together(a, b)
        val backup = a.engine.exportState()!!
        // After the backup: a photo, sent to the others.
        a.photo("p1")
        a.engine.sync("t")
        b.engine.sync("t")
        b.engine.sync("t")
        // A new phone restored from that backup: the trip and the state, not the photo.
        val a2 = Phone("a2", cloud)
        a2.trips["t"] = a.trips.getValue("t")
        a2.engine.importState(backup)
        a2.engine.sync("t")
        val p = a2.media.getValue("p1")
        assertNull(p.authorId)
        assertNull(p.sha256)
        assertEquals("jpeg of p1 by a", File(a2.dir, p.localPath).readText())
    }

    @Test fun deletedRowsAndPhotosLeaveTheSharedCopy() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        a.write("e1", "Private thoughts")
        a.photo("p1", entryId = "e1")
        together(a, b)
        a.engine.sync("t")
        b.engine.sync("t")
        val copy = a.media.getValue("p1").sharedFileId!!
        assertTrue(copy in cloud.files)
        // A deletes the entry and its photo.
        val t = a.now()
        a.entries["e1"] = a.entries.getValue("e1").copy(deletedAt = t, updatedAt = t)
        a.media["p1"] = a.media.getValue("p1").copy(deletedAt = t, updatedAt = t)
        a.engine.sync("t")
        assertFalse(copy in cloud.files)
        assertNull(a.media.getValue("p1").sharedFileId)
        val file = ShareCodec.decode(cloud.files.getValue(a.shared().fileId!!))
        assertEquals("", file.entries.single().title)
        assertNull(file.photos.single().fileId)
        // B's phone marks them deleted.
        b.engine.sync("t")
        assertNotNull(b.entries.getValue("e1").deletedAt)
        assertNotNull(b.media.getValue("p1").deletedAt)
    }

    @Test fun leavingEmptiesTheCopyAndTakesThePhotosOffTheLink() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        together(a, b)
        b.photo("pb")
        b.engine.sync("t")
        val photoCopy = b.media.getValue("pb").sharedFileId!!
        val bFile = b.shared().fileId!!
        assertNotNull(cloud.readable(photoCopy))
        b.engine.leave("t")
        assertNull(cloud.readable(photoCopy))
        val last = ShareCodec.decode(cloud.readable(bFile)!!)
        assertTrue(last.planItems.isEmpty() && last.entries.isEmpty() && last.photos.isEmpty())
        a.engine.sync("t")
        assertTrue(a.shared().members.single().left)
        // A link of someone who left doesn't join anyone.
        val c = Phone("c", cloud)
        try {
            c.engine.open(ShareLink("t", bFile, b.stored.former.getValue("t"), "Bo"), "Cy"); fail("joined through someone who left")
        } catch (e: TogetherException) {
            assertTrue(e.message!!.contains("left"))
        }
    }

    @Test fun aJoinerIsAskedForTheirLinkUntilSomeoneReadsIt() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        val invite = a.engine.share("t", "Ana", "Patagonia")
        b.engine.open(invite, "Bo")
        // B's first sync makes B's copy, but A doesn't know it yet.
        b.engine.sync("t")
        assertNotNull(b.shared().fileId)
        assertTrue(b.shared().needsLinkBack)
        a.engine.sync("t")
        b.engine.sync("t")
        assertTrue(b.shared().needsLinkBack)
        // A opens B's link: A's copy lists B's, so B can stop asking.
        assertTrue(a.engine.open(b.engine.share("t", "Bo", "Patagonia"), "Ana") is TogetherEngine.Opened.Added)
        assertFalse(a.shared().needsLinkBack)
        a.engine.sync("t")
        b.engine.sync("t")
        assertFalse(b.shared().needsLinkBack)
        // The inviter alone isn't asked for anything.
        val solo = Phone("solo", cloud)
        solo.trips["t"] = trip(cloud)
        solo.engine.share("t", "Sol", "Patagonia")
        assertFalse(solo.shared().needsLinkBack)
    }

    @Test fun aRestoreDoesNotBringBackATripLeftSince() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        together(a, b)
        val backup = b.engine.exportState()!!
        val me = b.shared().me
        b.engine.leave("t")
        b.trips["t"] = b.trips.getValue("t").let { it.copy(deletedAt = b.now(), updatedAt = b.now()) }
        b.engine.importState(backup)
        assertNull(b.engine.load().of("t"))
        assertEquals(me, b.engine.load().former["t"])
        // A shared trip deleted here without leaving is left at its next sync.
        val c = Phone("c", cloud)
        c.engine.open(a.engine.share("t", "Ana", "Patagonia"), "Cy")
        c.trips["t"] = c.trips.getValue("t").copy(deletedAt = c.now())
        assertFalse(c.engine.sync("t"))
        assertNull(c.engine.load().of("t"))
    }

    @Test fun rejoiningCarriesOnAsTheSameMember() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        val invite = together(a, b)
        b.write("e1", "Day one")
        b.engine.sync("t")
        a.engine.sync("t")
        val me = b.shared().me
        b.engine.leave("t")
        b.trips["t"] = b.trips.getValue("t").let { it.copy(deletedAt = b.now(), updatedAt = b.now()) }
        a.engine.sync("t")
        b.engine.open(invite, "Bo")
        assertEquals(me, b.shared().me)
        // B's later edit of their old entry still reaches A, and B is one member there, not two.
        a.engine.open(b.engine.share("t", "Bo", "Patagonia"), "Ana")
        b.entries["e1"] = b.entries.getValue("e1").let { it.copy(title = "Day one, again", updatedAt = b.now()) }
        b.engine.sync("t")
        a.engine.sync("t")
        assertEquals("Day one, again", a.entries.getValue("e1").title)
        assertEquals(1, a.shared().members.size)
        assertFalse(a.shared().members.single().left)
    }

    @Test fun joiningAgainTakesTheTripAsTheOthersHaveIt() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        val invite = together(a, b)
        // A renames the trip; B leaves without having seen it, which stamps B's old row later than the rename.
        a.trips["t"] = a.trips.getValue("t").copy(title = "Patagonia 2027", updatedAt = a.now())
        a.engine.sync("t")
        b.engine.leave("t")
        b.trips["t"] = b.trips.getValue("t").let { it.copy(deletedAt = b.now(), updatedAt = b.now()) }
        b.engine.open(invite, "Bo")
        assertEquals("Patagonia 2027", b.trips.getValue("t").title)
        // And B's copy doesn't take the old name back to A.
        a.engine.open(b.engine.share("t", "Bo", "Patagonia"), "Ana")
        b.engine.sync("t")
        a.engine.sync("t")
        assertEquals("Patagonia 2027", a.trips.getValue("t").title)
    }

    @Test fun photosGoUpAgainAfterLeavingAndJoiningAgain() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        val invite = together(a, b)
        b.photo("p1")
        b.engine.sync("t")
        val first = b.media.getValue("p1").sharedFileId!!
        b.engine.leave("t")
        b.trips["t"] = b.trips.getValue("t").let { it.copy(deletedAt = b.now(), updatedAt = b.now()) }
        assertNull(cloud.readable(first))
        // Back in the trip: a new folder, and the photo has a copy the others can read again.
        b.engine.open(invite, "Bo")
        b.engine.sync("t")
        val again = b.media.getValue("p1").sharedFileId!!
        assertNotEquals(first, again)
        assertNotNull(cloud.readable(again))
    }

    @Test fun leavingWithoutDriveIsFinishedLater() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        together(a, b)
        b.addItem("i1", "Glacier")
        b.engine.sync("t")
        val file = b.shared().fileId!!
        // Drive can't be told now: the leaving is remembered, and the copy is still as it was.
        b.driveOn = false
        b.engine.leave("t")
        assertNull(b.engine.load().of("t"))
        assertEquals(1, b.engine.load().unfinished.size)
        assertTrue(ShareCodec.decode(cloud.readable(file)!!).planItems.isNotEmpty())
        // Drive is back: the copy says "left" and holds nothing, and the others see it.
        b.driveOn = true
        b.engine.finishLeaving()
        assertTrue(b.engine.load().unfinished.isEmpty())
        assertTrue(ShareCodec.decode(cloud.readable(file)!!).planItems.isEmpty())
        a.engine.sync("t")
        assertTrue(a.shared().members.single().left)
    }

    @Test fun oneBadPlaceNeverBlocksAMembersCopy() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        together(a, b)
        // A photo's damaged GPS tag, and a place off the Earth: the rows go without their place, the rest crosses.
        a.photo("p1")
        a.media["p1"] = a.media.getValue("p1").copy(lat = Double.NaN, lng = 25.0)
        a.addItem("i1", "Glacier")
        a.items["i1"] = a.items.getValue("i1").copy(lat = 95.0, lng = 10.0)
        a.engine.sync("t")
        b.engine.sync("t")
        assertEquals(listOf("Glacier"), b.titles())
        assertNull(b.items.getValue("i1").lat)
        assertNull(b.media.getValue("p1").lat)
    }

    @Test fun anOwnDriveProblemStillLetsTheOthersPhotosIn() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        together(a, b)
        a.photo("p1")
        a.engine.sync("t")
        // B's own upload fails (its Drive, the connection): A's photo arrives all the same, and the sync says why.
        b.photo("p2")
        b.failNext = "upload"
        try {
            b.engine.sync("t")
            fail("Expected the sync to say what went wrong")
        } catch (_: java.io.IOException) {
        }
        assertTrue(java.io.File(b.dir, b.media.getValue("p1").localPath).isFile)
    }

    @Test fun aFolderMadeBeforeAFailureIsKeptAndADeletedOneIsMadeAgain() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = trip(cloud)
        a.failNext = "create"
        try {
            a.engine.share("t", "Ana", "Patagonia"); fail("shared offline")
        } catch (_: java.io.IOException) {
        }
        val folder = a.shared().folderId!!
        a.engine.share("t", "Ana", "Patagonia")
        assertEquals(folder, a.shared().folderId)
        assertEquals(setOf(folder), cloud.folders)

        // The owner deletes the folder in Drive: the next write makes a new one with the trip and its photos.
        b.engine.open(a.engine.linkFor("t", "Patagonia")!!, "Bo")
        a.engine.open(b.engine.share("t", "Bo", "Patagonia"), "Ana")
        a.photo("p1")
        a.engine.sync("t")
        val oldFile = a.shared().fileId
        cloud.deleteFolder(folder)
        a.addItem("i1", "Glacier")
        a.engine.sync("t")
        assertNotEquals(folder, a.shared().folderId)
        assertNotEquals(oldFile, a.shared().fileId)
        assertTrue(a.shared().needsLinkBack)
        a.engine.sync("t")
        assertEquals(a.shared().folderId, cloud.parent[a.media.getValue("p1").sharedFileId])
    }

    @Test fun aFailedSyncSaysWhyInPlainWords() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        a.trips["t"] = trip(cloud)
        a.engine.share("t", "Ana", "Patagonia")
        a.photo("p1")
        cloud.deleteFolder(a.shared().folderId!!)
        a.failNext = "folder"
        try {
            a.engine.sync("t"); fail("synced offline")
        } catch (_: java.io.IOException) {
        }
        assertEquals("No internet connection. Check it and try again.", (a.engine.status.value["t"] as SyncStatus.Failed).message)
    }

    // ---------------------------------------------------------------- the trips' mailboxes (TogetherMail)

    /** A trip id like the app's own (a UUID): "t" is too short to get a mailbox. */
    private val long = "3f1c2a9e-5b7d-4e21-9a0c-2d8f6b1e7a44"
    private val box = TripMail.box(long)!!

    private fun longTrip(cloud: Cloud) =
        Trip(long, "Patagonia", startDate = LocalDate.of(2027, 3, 1), endDate = LocalDate.of(2027, 3, 31), createdAt = 1, updatedAt = cloud.clock++)

    private suspend fun Phone.trip() = engine.load().of(long)!!

    private fun Phone.titlesOf() = items.values.filter { it.deletedAt == null && it.tripId == long }.map { it.title }.sorted()

    /** A shares and posts; B joins, makes its copy and posts: nobody sends a link back. */
    private suspend fun joinByMail(a: Phone, b: Phone): ShareLink {
        val invite = a.engine.share(long, "Ana", "Patagonia")
        assertTrue(a.mail.post(long))
        assertTrue(b.engine.open(invite, "Bo") is TogetherEngine.Opened.Joined)
        b.engine.sync(long)
        assertTrue(b.mail.post(long))
        return invite
    }

    @Test fun aJoinerReachesTheOthersThroughTheMailbox() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips[long] = longTrip(cloud)
        a.addItem("i1", "Glacier", long)
        joinByMail(a, b)
        assertTrue(b.trip().needsLinkBack)
        assertEquals(setOf("uid-a", "uid-b"), cloud.mailboxes.getValue(box).keys)

        // A reads the mailbox and adds B as if B's link had been opened.
        val entries = a.mail.look(long)!!
        assertEquals(2, entries.size)
        assertEquals(listOf("Bo"), a.trip().members.map { it.name })

        // Both change things; after a sync each, both phones agree, and B's copy is read: no "send your link".
        b.addItem("i2", "Fitz Roy", long)
        a.addItem("i3", "Asado", long)
        b.engine.sync(long)
        a.engine.sync(long)
        b.engine.sync(long)
        assertEquals(listOf("Asado", "Fitz Roy", "Glacier"), a.titlesOf())
        assertEquals(a.titlesOf(), b.titlesOf())
        assertFalse(b.trip().needsLinkBack)
        assertFalse(a.trip().needsLinkBack)
        b.mail.look(long)
        assertFalse(TripMail.offerLink(b.trip(), b.mail.seen.value[long], cloud.clock))
    }

    @Test fun aThirdMemberReachesEveryoneThroughTheMailbox() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        val c = Phone("c", cloud)
        a.trips[long] = longTrip(cloud)
        val invite = joinByMail(a, b)
        a.mail.look(long)
        c.engine.open(invite, "Cy")
        c.addItem("c1", "Ushuaia", long)
        c.engine.sync(long)
        assertTrue(c.mail.post(long))
        // B learns C straight from the mailbox (not only later through A's file), and C learns B.
        b.mail.look(long)
        c.mail.look(long)
        assertEquals(setOf("Ana", "Cy"), b.trip().members.map { it.name }.toSet())
        assertEquals(setOf("Ana", "Bo"), c.trip().members.map { it.name }.toSet())
        b.engine.sync(long)
        assertEquals(listOf("Ushuaia"), b.titlesOf())
    }

    @Test fun anEntryWhoseFileIsNotTheirsIsRefusedAndNotReadAgain() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        a.trips[long] = longTrip(cloud)
        val invite = a.engine.share(long, "Ana", "Patagonia")
        // Someone who knows the box names A's own file as theirs, and another posts a file that isn't there.
        cloud.mailboxes.getOrPut(box) { LinkedHashMap() }["uid-x"] = MailEntry("uid-x", "intruder", invite.fileId)
        cloud.mailboxes.getValue(box)["uid-y"] = MailEntry("uid-y", "ghost", "nofileatall")
        // An entry the fake would write that names this phone itself.
        cloud.mailboxes.getValue(box)["uid-z"] = MailEntry("uid-z", a.trip().me, "otherfile01")
        val reads = cloud.reads
        a.mail.look(long)
        assertTrue(a.trip().members.isEmpty())
        assertEquals(reads + 2, cloud.reads)
        a.mail.look(long)
        assertEquals(reads + 2, cloud.reads)
    }

    @Test fun aMemberWhoLeftAndCameBackIsTakenFromTheMailbox() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips[long] = longTrip(cloud)
        val invite = joinByMail(a, b)
        a.mail.look(long)
        val bId = b.trip().me
        // B leaves: their entry goes, and A sees them leave.
        b.engine.leave(long)
        b.mail.drop(long)
        assertEquals(setOf("uid-a"), cloud.mailboxes.getValue(box).keys)
        a.engine.sync(long)
        assertTrue(a.trip().members.single().left)
        // B joins again, as the same member with a new copy: A takes them back from the mailbox.
        b.trips[long] = b.trips.getValue(long).copy(deletedAt = b.now(), updatedAt = b.now())
        b.engine.open(invite, "Bo")
        assertEquals(bId, b.trip().me)
        b.engine.sync(long)
        assertTrue(b.mail.post(long))
        a.mail.look(long)
        val back = a.trip().members.single()
        assertFalse(back.left)
        assertEquals(b.trip().fileId, back.fileId)
    }

    @Test fun aPostIsMadeOnceAndAgainForANewCopyOrAccount() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        a.trips[long] = longTrip(cloud)
        a.engine.share(long, "Ana", "Patagonia")
        assertTrue(a.mail.post(long))
        a.engine.sync(long)
        assertTrue(a.mail.post(long))
        assertEquals(1, cloud.mailPosts)
        // The copy's folder is deleted in Drive: the new copy's address replaces the old one.
        cloud.deleteFolder(a.trip().folderId!!)
        a.addItem("i1", "Glacier", long)
        a.engine.sync(long)
        assertFalse(TripMail.isPosted(a.trip()))
        assertTrue(a.mail.post(long))
        assertEquals(2, cloud.mailPosts)
        assertEquals(a.trip().fileId, cloud.mailboxes.getValue(box).getValue("uid-a").fileId)
        // Another Google account on the phone posts under its own name; signed out, nothing is posted.
        a.account.value = "uid-a2"
        assertTrue(a.mail.post(long))
        assertEquals(3, cloud.mailPosts)
        a.account.value = null
        assertFalse(a.mail.post(long))
        assertEquals(3, cloud.mailPosts)
    }

    @Test fun aFailedPostWaitsUnlessASheetAsks() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        a.trips[long] = longTrip(cloud)
        a.engine.share(long, "Ana", "Patagonia")
        cloud.mailDown = true
        try {
            a.mail.post(long); fail("posted while Firestore was down")
        } catch (_: java.io.IOException) {
        }
        cloud.mailDown = false
        // The syncs leave it for a while…
        assertFalse(a.mail.post(long))
        assertEquals(0, cloud.mailPosts)
        cloud.clock += TogetherMail.RETRY_MS
        assertTrue(a.mail.post(long))
        assertEquals(1, cloud.mailPosts)
        // …a sheet doesn't wait.
        cloud.deleteFolder(a.trip().folderId!!)
        a.addItem("i1", "Glacier", long)
        a.engine.sync(long)
        cloud.mailDown = true
        try {
            a.mail.post(long); fail("posted while Firestore was down")
        } catch (_: java.io.IOException) {
        }
        cloud.mailDown = false
        assertTrue(a.mail.post(long, force = true))
        assertEquals(2, cloud.mailPosts)
    }

    @Test fun theWatchFollowsTheSignInAndOutlivesAFailingMailbox() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips[long] = longTrip(cloud)
        joinByMail(a, b)
        // A failing mailbox ends quietly; the trip goes on.
        cloud.mailDown = true
        val failing = launch { a.mail.watch(long) }
        withTimeout(5_000) { while (!a.mail.watching(long)) delay(5) }
        delay(20)
        assertTrue(a.trip().members.isEmpty())
        failing.cancelAndJoin()
        assertFalse(a.mail.watching(long))
        cloud.mailDown = false
        // Signed out nothing is read; once signed in, B shows up.
        a.account.value = null
        val reads = cloud.mailReads
        val job = launch { a.mail.watch(long) }
        delay(20)
        assertEquals(reads, cloud.mailReads)
        a.account.value = "uid-a"
        withTimeout(5_000) { while (a.trip().members.isEmpty()) delay(5) }
        assertEquals(listOf("Bo"), a.trip().members.map { it.name })
        job.cancelAndJoin()
    }

    @Test fun aRestoredPhonePostsAgain() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        a.trips[long] = longTrip(cloud)
        a.engine.share(long, "Ana", "Patagonia")
        assertTrue(a.mail.post(long))
        assertNotNull(a.trip().mailed)
        val again = Phone("again", cloud)
        again.trips[long] = a.trips.getValue(long)
        again.engine.importState(a.engine.exportState()!!)
        assertNull(again.trip().mailed)
        assertTrue(again.mail.post(long))
    }

    @Test fun theMailboxNeverJoinsATripAndWaitsWithoutSignIn() = runBlocking {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips[long] = longTrip(cloud)
        val invite = a.engine.share(long, "Ana", "Patagonia")
        assertTrue(a.mail.post(long))
        // B never joined: the mailbox adds nobody there and makes no trip.
        assertNull(b.engine.add(invite))
        assertTrue(b.mail.take(long, listOf(MailEntry("uid-a", invite.memberId, invite.fileId))).isEmpty())
        assertNull(b.trips[long])
        // B joins signed out: nothing posted or read, and the link is offered at once.
        b.account.value = null
        b.engine.open(invite, "Bo")
        b.engine.sync(long)
        assertFalse(b.mail.post(long))
        assertNull(b.mail.look(long))
        assertEquals(setOf("uid-a"), cloud.mailboxes.getValue(box).keys)
        assertTrue(TripMail.offerLink(b.trip(), null, cloud.clock))
        // The link still does it, as before the mailbox.
        assertTrue(a.engine.open(b.engine.linkFor(long, "Patagonia")!!, "Ana") is TogetherEngine.Opened.Added)
    }
}

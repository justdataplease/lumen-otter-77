package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.backup.EntryRow
import com.justdataplease.boringtravel.backup.TripRow
import com.justdataplease.boringtravel.compat.ConcurrentHashMap
import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.compat.Gzip
import com.justdataplease.boringtravel.compat.RelativeUnit
import com.justdataplease.boringtravel.compat.relativeAgo
import com.justdataplease.boringtravel.compat.relativeDay
import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.google.NotSharedException
import com.justdataplease.boringtravel.ios.AppFileStore
import com.justdataplease.boringtravel.ios.Deeplink
import com.justdataplease.boringtravel.ios.MemoryMediaFiles
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.together.LinkReader
import com.justdataplease.boringtravel.together.Member
import com.justdataplease.boringtravel.together.OwnDrive
import com.justdataplease.boringtravel.together.PhotoFiles
import com.justdataplease.boringtravel.together.SendQueue
import com.justdataplease.boringtravel.together.ShareCodec
import com.justdataplease.boringtravel.together.ShareFile
import com.justdataplease.boringtravel.together.ShareFormatException
import com.justdataplease.boringtravel.together.ShareLink
import com.justdataplease.boringtravel.together.Together
import com.justdataplease.boringtravel.together.TogetherEngine
import com.justdataplease.boringtravel.together.TogetherMerge
import com.justdataplease.boringtravel.together.TogetherPlan
import com.justdataplease.boringtravel.together.TogetherRows
import com.justdataplease.boringtravel.together.TogetherState
import com.justdataplease.boringtravel.together.TripMail
import com.justdataplease.boringtravel.together.TripRows
import com.justdataplease.boringtravel.ui.community.relative
import com.justdataplease.boringtravel.ui.together.TogetherText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Travel together (round 26) on both platforms: the code read in from Android (ShareCodec's gzip JSON with its checks,
 * ShareLink's form encoding and its pattern, TripMail's box, TogetherMerge's fingerprint, the engine's whole conversation
 * between two phones, SendQueue, TogetherText) and what this app wrote for it (compat's gzip and ConcurrentHashMap, the
 * relative times of the Community post sheet in the phone's language), where Kotlin/Native's text, regex, hashing and
 * maps could differ from the JVM's.
 */
class TogetherSmokeTest {
    @Test fun gzipGoesBothWaysAndIsChecked() {
        val text = ("{\"trip\":\"Spain 2027\",\"items\":[" + (1..400).joinToString(",") { "{\"id\":\"i$it\",\"title\":\"Tapas $it\"}" } + "]}").encodeToByteArray()
        val packed = Gzip.compress(text)
        assertEquals(0x1f, packed[0].toInt() and 0xFF)
        assertEquals(0x8b, packed[1].toInt() and 0xFF)
        // A trip's JSON shrinks: repeated keys are what LZ77 is for.
        assertTrue(packed.size < text.size / 3, "packed ${packed.size} of ${text.size}")
        assertContentEquals(text, Gzip.decompress(packed))
        assertContentEquals(ByteArray(0), Gzip.decompress(Gzip.compress(ByteArray(0))))
        // A flipped byte in the middle, a cut tail, something else entirely: not read.
        val broken = packed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        assertFailsWith<com.justdataplease.boringtravel.io.IOException> { Gzip.decompress(broken) }
        assertFailsWith<com.justdataplease.boringtravel.io.IOException> { Gzip.decompress(packed.copyOf(packed.size - 3)) }
        assertFailsWith<com.justdataplease.boringtravel.io.IOException> { Gzip.decompress("not gzip at all, really".encodeToByteArray()) }
    }

    @Test fun aMembersFileIsWrittenReadAndChecked() {
        val file = ShareFile(
            writer = "m-1", writtenAt = 5, members = listOf(Member("m-1", "Μαρία", "fileMaria01")),
            trip = TripRow(id = "trip-1", title = "Ισπανία 2027", startDate = "2027-04-01", endDate = "2027-04-04", updatedAt = 3),
            entries = listOf(EntryRow(id = "e1", tripId = "trip-1", occurredAt = 1, zoneId = "Europe/Madrid", title = "Πορτοκαλιές", authorId = "m-1", createdAt = 1, updatedAt = 2)),
        )
        val back = ShareCodec.decode(ShareCodec.encode(file))
        assertEquals(file.trip, back.trip)
        assertEquals(file.members, back.members)
        assertEquals("Πορτοκαλιές", back.entries.single().title)
        assertEquals("This shared trip is damaged.", assertFailsWith<ShareFormatException> { ShareCodec.decode(ByteArray(40) { 7 }) }.message)
        val foreign = ShareCodec.encode(file).let { Gzip.decompress(it).decodeToString().replace("\"writer\"", "\"app\":\"Other\",\"writer\"") }
        assertEquals("This isn't a BoringTravel trip.", assertFailsWith<ShareFormatException> { ShareCodec.decode(Gzip.compress(foreign.encodeToByteArray())) }.message)
        val newer = ShareCodec.encode(file).let { Gzip.decompress(it).decodeToString().replace("\"writer\"", "\"format\":2,\"writer\"") }
        assertEquals(
            "A member has a newer BoringTravel. Update the app to see their changes.",
            assertFailsWith<ShareFormatException> { ShareCodec.decode(Gzip.compress(newer.encodeToByteArray())) }.message,
        )
        val badDay = ShareCodec.encode(file.copy(trip = file.trip.copy(startDate = "2027-4-1")))
        assertEquals("This shared trip is damaged.", assertFailsWith<ShareFormatException> { ShareCodec.decode(badDay) }.message)
        // A member's name: one line, trimmed, 40 characters at most.
        assertEquals("Maria José", ShareCodec.cleanName("  Maria\n\tJosé "))
        assertEquals(ShareCodec.MAX_NAME, ShareCodec.cleanName("x".repeat(80)).length)
    }

    @Test fun theJoinLinkIsFoundAndReadBack() {
        val link = ShareLink("3f1c2a9e-5b7d-4e21-9a0c-2d8f6b1e7a44", "1AbCdEfGhIjKlMnOp", "m-1", "Μαρία José", "Ισπανία & Πορτογαλία 2027")
        assertEquals(link, ShareLink.find(link.url))
        // In a chat message, between quotes and angle brackets (they end the link, as the Android pattern says).
        assertEquals(link, ShareLink.find("Join my trip:\n<${link.url}>\nsee you"))
        assertEquals(link, ShareLink.find("\"${link.url}\" said Maria"))
        assertEquals(link, ShareLink.find(link.url.replace(ShareLink.WEB, ShareLink.APP)))
        // Java's form encoding: a space as %20 (the link's own rule), "&" and the Greek letters escaped.
        assertTrue("&n=%CE%9C%CE%B1%CF%81%CE%AF%CE%B1%20Jos%C3%A9&" in link.url, link.url)
        assertNull(ShareLink.find("https://example.com/apps/boringtravel/join#v=1&t=a&f=12345&m=b"))
        // The app's link from outside: a join link becomes the Join sheet's action, everything after "#" kept.
        assertEquals(link.url, Deeplink.of(link.url)?.let(Deeplink::joinOf))
        assertEquals(link, Deeplink.of(link.url.replace(ShareLink.WEB, ShareLink.APP))?.let(Deeplink::joinOf)?.let(ShareLink::find))
    }

    @Test fun theMailboxIsTheSameHashOnEveryPhone() {
        // Android's TripMailTest and the rules tests (firebase/test/mailbox.js) agree on it.
        assertEquals("d63e872a66a40fe4c17488921b326a22653c620a1a0a09fdfa852da883cc30b7", TripMail.box("3f1c2a9e-5b7d-4e21-9a0c-2d8f6b1e7a44"))
        assertNull(TripMail.box("demo-trip-1"))
        assertEquals("Maria joined Spain 2027", TogetherText.joined(listOf("Maria"), "Spain 2027"))
        assertEquals("Maria and 2 more joined Spain 2027", TogetherText.joined(listOf("Maria", "Nikos", "Eleni"), "Spain 2027"))
    }

    @Test fun theFingerprintMovesWithAChange() {
        val trip = Trip("t", "Spain 2027", createdAt = 1, updatedAt = 1)
        val item = PlanItem("i1", "t", localDate(2027, 4, 1), 1.0, null, "place", "Alhambra", createdAt = 1, updatedAt = 1)
        val one = TogetherMerge.fingerprint(TripRows(trip, listOf(item)), listOf(Member("m", "Maria")))
        assertEquals(64, one.length)
        assertEquals(one, TogetherMerge.fingerprint(TripRows(trip, listOf(item)), listOf(Member("m", "Maria"))))
        assertTrue(one != TogetherMerge.fingerprint(TripRows(trip, listOf(item.copy(updatedAt = 2))), listOf(Member("m", "Maria"))))
    }

    @Test fun aSendQueueTakesABurstAsOneAndAChangeAfterItsStartAsAnother() {
        val queued = ArrayList<String>()
        val sends = SendQueue { queued += it }
        repeat(4) { sends.changed("t") }
        sends.started("t")
        sends.changed("t")
        assertEquals(listOf("t", "t"), queued)
        val map = ConcurrentHashMap<String, Long>()
        map["a"] = 1
        map["b"] = 2
        map.remove("a")
        assertEquals(mapOf("b" to 2L), map.toMap())
        val set = ConcurrentHashMap.newKeySet<String>()
        assertTrue(set.add("x"))
        assertTrue(!set.add("x"))
        // The background turn's rule: a trip that ended over 30 days ago stops syncing.
        val today = localDate(2027, 5, 1)
        val ended = { days: Long -> Trip("t", "x", startDate = today.minusDays(days + 3), endDate = today.minusDays(days), createdAt = 1, updatedAt = 1) }
        assertTrue(Together.inBackground(ended(30), today))
        assertTrue(!Together.inBackground(ended(31), today))
        assertEquals(30_000L, Together.retryWait(1))
        assertEquals(Together.RETRY_MAX_MS, Together.retryWait(30))
    }

    @Test fun twoPhonesPlanTogether() = runTest {
        val cloud = Cloud()
        val a = Phone("a", cloud)
        val b = Phone("b", cloud)
        a.trips["t"] = Trip("t", "Spain 2027", startDate = localDate(2027, 4, 1), endDate = localDate(2027, 4, 4), createdAt = 1, updatedAt = cloud.clock++)
        a.item("i1", "Alhambra")
        a.photo("p1")
        val invite = a.engine.share("t", "Maria", "Spain 2027")
        assertTrue(b.engine.open(invite, "Nikos") is TogetherEngine.Opened.Joined)
        assertEquals(listOf("Alhambra"), b.titles())
        assertTrue(a.engine.open(b.engine.share("t", "Nikos", "Spain 2027"), "Maria") is TogetherEngine.Opened.Added)
        // Both change the same item: the later edit wins on both phones; a deletion travels.
        b.edit("i1", "Alhambra at night")
        a.item("i2", "Tapas")
        b.engine.sync("t")
        a.engine.sync("t")
        b.engine.sync("t")
        assertEquals(listOf("Alhambra at night", "Tapas"), a.titles())
        assertEquals(a.titles(), b.titles())
        a.items["i2"] = a.items.getValue("i2").let { it.copy(deletedAt = cloud.clock, updatedAt = cloud.clock++) }
        a.engine.sync("t")
        b.engine.sync("t")
        assertEquals(listOf("Alhambra at night"), b.titles())
        // A's photo arrives on B as its small copy, by its author.
        val p = b.media.getValue("p1")
        assertEquals(a.engine.load().of("t")!!.me, p.authorId)
        assertEquals("jpeg of p1", b.files.read(p.localPath)?.decodeToString())
        // The state is kept as JSON and read back.
        assertNotNull(a.engine.exportState())
    }

    /** Drive and the mailbox of the conversation above, in memory. */
    private class Cloud {
        val files = HashMap<String, ByteArray>()
        val folders = HashSet<String>()
        val parent = HashMap<String, String>()
        val shared = HashSet<String>()
        var next = 0
        var clock = 1_000L

        fun id(owner: String) = "${owner}file${next++}xx"

        fun readable(id: String): ByteArray? = files[id]?.takeIf { id in shared || parent[id] in shared }
    }

    private class Phone(val name: String, val cloud: Cloud) {
        val trips = HashMap<String, Trip>()
        val items = HashMap<String, PlanItem>()
        val entries = HashMap<String, Entry>()
        val media = HashMap<String, Media>()
        val files = MemoryMediaFiles()
        var stored = TogetherState()
        private var ids = 0

        fun item(id: String, title: String) {
            val t = cloud.clock++
            items[id] = PlanItem(id, "t", localDate(2027, 4, 2), 1.0, null, "place", title, createdAt = t, updatedAt = t)
        }

        fun edit(id: String, title: String) {
            val i = items.getValue(id)
            items[id] = i.copy(title = title, updatedAt = maxOf(cloud.clock++, i.updatedAt + 1))
        }

        fun photo(id: String) {
            val t = cloud.clock++
            val m = Media(id, "t", null, "media/$id.jpg", createdAt = t, updatedAt = t)
            files.write(m.localPath, "jpeg of $id".encodeToByteArray())
            media[id] = m
        }

        fun titles() = items.values.filter { it.deletedAt == null }.map { it.title }.sorted()

        private fun rows(tripId: String) = TripRows(trips[tripId], items.values.filter { it.tripId == tripId }, entries.values.filter { it.tripId == tripId }, media.values.filter { it.tripId == tripId })

        val engine = TogetherEngine(
            rows = object : TogetherRows {
                override suspend fun trip(tripId: String) = trips[tripId]
                override suspend fun rows(tripId: String) = this@Phone.rows(tripId)
                override suspend fun merge(tripId: String, ids: Set<String>, compute: (TripRows, Set<String>) -> TogetherPlan): TogetherPlan {
                    val plan = compute(rows(tripId), emptySet())
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
            },
            own = object : OwnDrive {
                override suspend fun connected() = true
                override suspend fun sharedFolder(name: String) = cloud.id(this@Phone.name).also { cloud.folders += it; cloud.shared += it }
                override suspend fun create(folderId: String, name: String, content: ByteArray): String? {
                    if (folderId !in cloud.folders) return null
                    return cloud.id(this@Phone.name).also { cloud.files[it] = content; cloud.parent[it] = folderId; cloud.shared += it }
                }
                override suspend fun replace(fileId: String, content: ByteArray): Boolean {
                    if (fileId !in cloud.files) return false
                    cloud.files[fileId] = content
                    return true
                }
                override suspend fun upload(folderId: String, file: File, name: String, mime: String): String? {
                    if (folderId !in cloud.folders) return null
                    return cloud.id(this@Phone.name).also { cloud.files[it] = file.readBytes(); cloud.parent[it] = folderId }
                }
                override suspend fun delete(fileId: String) {
                    cloud.files -= fileId
                }
                override suspend fun unshare(id: String) {
                    cloud.shared -= id
                }
            },
            links = object : LinkReader {
                override suspend fun checksum(fileId: String): String? {
                    val bytes = cloud.readable(fileId) ?: throw NotSharedException()
                    return com.justdataplease.boringtravel.ios.Sha256.hex(bytes)
                }
                override suspend fun read(fileId: String, maxBytes: Long): ByteArray = cloud.readable(fileId) ?: throw NotSharedException()
                override suspend fun download(fileId: String, target: File, maxBytes: Long) {
                    files.write(target.path, cloud.readable(fileId) ?: throw NotSharedException())
                }
            },
            photos = object : PhotoFiles {
                override fun file(media: Media) = File(media.localPath, AppFileStore(files))
                override fun smallCopy(media: Media) = File(media.localPath, AppFileStore(files)).takeIf { it.isFile }
            },
            store = object : TogetherEngine.StateStore {
                override suspend fun read() = stored
                override suspend fun write(state: TogetherState) {
                    stored = state
                }
            },
            clock = { cloud.clock++ },
            newId = { "$name-${ids++}" },
        )
    }

    @Test fun lastUpdatedIsWrittenInThePhonesLanguage() {
        val now = 1_795_000_000_000L
        assertEquals("5 minutes ago", relativeAgo(5, RelativeUnit.MINUTES, "en-US"))
        assertEquals("1 hour ago", relativeAgo(1, RelativeUnit.HOURS, "en-US"))
        assertEquals("3 days ago", relativeAgo(3, RelativeUnit.DAYS, "en-US"))
        assertEquals("yesterday", relativeDay(1, "en-US"))
        // CLDR's Greek, as Android's DateUtils (ICU) writes it on a Greek phone.
        assertEquals("πριν από 5 λεπτά", relativeAgo(5, RelativeUnit.MINUTES, "el-GR"))
        assertEquals("πριν από 1 ώρα", relativeAgo(1, RelativeUnit.HOURS, "el-GR"))
        assertEquals("πριν από 3 ημέρες", relativeAgo(3, RelativeUnit.DAYS, "el-GR"))
        assertEquals("χθες", relativeDay(1, "el-GR"))
        assertEquals("today", relativeDay(0, "en-US"))
        assertEquals("πριν από 5 λεπτά", relative(now - 5 * 60_000L, now, "el-GR"))
        assertEquals("πριν από 2 ώρες", relative(now - 2 * 3_600_000L - 60_000L, now, "el-GR"))
    }
}

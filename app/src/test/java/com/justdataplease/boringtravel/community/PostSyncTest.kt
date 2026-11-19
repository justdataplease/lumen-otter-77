package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A trip post kept in step with its trip by hand (owner, 17 Nov 2026: "a button in trip to … resync", "manually only"):
 * what an update sends, what it remembers, and what goes up again.
 */
class PostSyncTest {
    private val d = LocalDate.of(2027, 3, 1)
    private val trip = Trip("t", "Patagonia", startDate = d, endDate = d.plusDays(4), createdAt = 1, updatedAt = 1)
    private val items = listOf(
        PlanItem("i1", "t", day = d, kind = "place", title = "Buenos Aires", lat = -34.6, lng = -58.4, createdAt = 1, updatedAt = 1),
        PlanItem("i2", "t", day = d.plusDays(2), kind = "place", title = "El Chaltén", lat = -49.3, lng = -72.9, createdAt = 1, updatedAt = 1),
    )
    private fun note(id: String, at: Long) = Entry(id, "t", occurredAt = at, zoneId = "UTC", title = "Note $id", plainText = "Words", createdAt = at, updatedAt = at)
    private fun photo(id: String, entry: String?, at: Long) = Media(id, "t", entry, "media/$id.jpg", takenAt = at, createdAt = at, updatedAt = at)

    private val notes = listOf(note("n1", 10), note("n2", 20))
    private val photos = listOf(photo("p1", "n1", 11), photo("p2", "n2", 21))
    private val source = PostSource(trip, items, notes, photos)

    @Test fun aPostedTripRemembersWhatWasPickedAndWhatWentUp() {
        val draft = PostDrafts.withNotes(source, PostDrafts.start(source, "trip"), true)
        val plan = PostDrafts.plan("post1", source, draft) { null }
        val state = UploadState().apply { folderId = "folder"; files["p1"] = "drive1"; files["p2"] = "drive2" }
        val post = Post("post1", "me", "alexa", PostKind.Trip, "Patagonia")
        val link = PostSyncs.link("t", post, draft, source, state, plan, at = 5)
        assertEquals("post1", link.postId)
        assertTrue(link.includeNotes)
        assertTrue(link.allNotes)
        assertEquals("p1", link.cover)
        assertFalse(link.drawnCover)
        assertEquals(mapOf("p1" to "drive1", "p2" to "drive2"), link.files)
        assertEquals(PostSyncs.fingerprint(plan), link.sent)
        // Nothing changed: the same fingerprint, so an update asks nothing.
        val again = PostDrafts.plan("post1", source, PostSyncs.draft(source, link, "Patagonia")) { null }
        assertEquals(link.sent, PostSyncs.fingerprint(again))
    }

    @Test fun anUpdateTakesNewNotesWhenAllWerePickedAndOnlyThePickedOnesOtherwise() {
        val n3 = note("n3", 30)
        val p3 = photo("p3", "n3", 31)
        val later = source.copy(entries = notes + n3, photos = photos + p3)
        val all = PostLink("t", "post1", includeNotes = true, noteIds = listOf("n1", "n2"), allNotes = true, cover = "p1")
        assertEquals(setOf("n1", "n2", "n3"), PostSyncs.draft(later, all, "Patagonia").noteIds)
        val some = all.copy(noteIds = listOf("n2"), allNotes = false)
        assertEquals(setOf("n2"), PostSyncs.draft(later, some, "Patagonia").noteIds)
        // A picked note deleted since goes from the post.
        val gone = later.copy(entries = listOf(notes[0], n3))
        assertEquals(emptySet<String>(), PostSyncs.draft(gone, some, "Patagonia").noteIds)
        // New notes change what the post holds.
        val before = PostSyncs.fingerprint(PostDrafts.plan("post1", source, PostSyncs.draft(source, all, "Patagonia")) { null })
        val after = PostSyncs.fingerprint(PostDrafts.plan("post1", later, PostSyncs.draft(later, all, "Patagonia")) { null })
        assertNotEquals(before, after)
    }

    @Test fun theCoverFollowsItsPhotoOrTheDrawnPictureChosen() {
        val link = PostLink("t", "post1", cover = "p2")
        assertEquals("p2", PostSyncs.draft(source, link, "Patagonia").cover)
        // Its photo deleted: the trip's first photo.
        val withoutP2 = source.copy(photos = photos.take(1))
        assertEquals("p1", PostSyncs.draft(withoutP2, link, "Patagonia").cover)
        // The drawn picture chosen over photos stays drawn.
        assertNull(PostSyncs.draft(source, PostLink("t", "post1", cover = null, drawnCover = true), "Patagonia").cover)
        // Posted before there were photos: the first photo once there is one.
        assertEquals("p1", PostSyncs.draft(source, PostLink("t", "post1", cover = null, drawnCover = false), "Patagonia").cover)
    }

    @Test fun thePlanChangesWhatThePostHoldsButTheWordsDoNot() {
        val link = PostLink("t", "post1", cover = "p1")
        val plan = PostDrafts.plan("post1", source, PostSyncs.draft(source, link, "Patagonia")) { null }
        val renamed = plan.copy(title = "Another title", caption = "Other words")
        assertEquals(PostSyncs.fingerprint(plan), PostSyncs.fingerprint(renamed))
        val moved = source.copy(items = items.map { if (it.id == "i2") it.copy(day = d.plusDays(3)) else it })
        assertNotEquals(PostSyncs.fingerprint(plan), PostSyncs.fingerprint(PostDrafts.plan("post1", moved, PostSyncs.draft(moved, link, "Patagonia")) { null }))
    }

    @Test fun linksAreKeptOnThePhoneAndADamagedFileReadsAsNone() = runBlocking {
        val dir = Files.createTempDirectory("links").toFile()
        val file = File(dir, "community/post-links.json")
        val links = PostLinks(file)
        links.put(PostLink("t", "post1", title = "Patagonia", files = mapOf("p1" to "drive1")))
        links.put(PostLink("u", "post2"))
        assertTrue(file.isFile)
        val again = PostLinks(file)
        assertEquals(mapOf("p1" to "drive1"), again.of("t")?.files)
        again.removePost("post2")
        assertNull(PostLinks(file).of("u"))
        assertEquals(emptyMap<String, PostLink>(), PostLinks.decode("{not json"))
        dir.deleteRecursively()
        Unit
    }

    // ---- the upload of an update ----

    private class Store : PostStore {
        val folders = ArrayList<String>()
        val photos = ArrayList<String>()
        private var n = 0
        override suspend fun folder(name: String): String = "folder${folders.size + 1}".also { folders += name }
        override suspend fun photo(folderId: String, name: String, jpeg: ByteArray): String = "new${++n}".also { photos += "$folderId/$name" }
        override suspend fun video(folderId: String, name: String, file: File): String = throw IOException("no clips here")
        override suspend fun delete(folderId: String) {}
    }

    @Test fun anUpdateSendsOnlyNewPicturesIntoTheSameFolderAndKeepsTheLikes() = runTest {
        val repo = FakeCommunityRepository(clock = { 1_000L }).apply {
            profiles["me"] = Profile("me", "alexa")
            usernames["alexa"] = "me"
        }
        val store = Store()
        val uploader = PostUploader(store, PostPictures { it.toByteArray() }, repo, now = { 1_000L }, zone = ZoneOffset.UTC)
        val draft = PostDrafts.withNotes(source, PostDrafts.start(source, "trip"), true)
        val state = UploadState()
        val first = uploader.run(PostDrafts.plan("post1", source, draft) { null }, state)
        repo.posts[first.id] = repo.posts.getValue(first.id).copy(likes = 7)
        val link = PostSyncs.link("t", first, draft, source, state, PostDrafts.plan("post1", source, draft) { null }, 5)
        assertEquals(2, store.photos.size)

        // A new note with a photo: one picture goes up, into the post's folder; the likes and the posting month stay.
        val later = source.copy(entries = notes + note("n3", 30), photos = photos + photo("p3", "n3", 31))
        val plan = PostDrafts.plan("post1", later, PostSyncs.draft(later, link, first.title)) { null }
        val next = UploadState().apply { folderId = link.folderId; files.putAll(link.files.filterKeys { it in plan.pictures }) }
        val updated = uploader.run(plan, next, update = true)
        assertEquals(listOf("folder1/photo-3.jpg"), store.photos.drop(2))
        assertEquals(1, store.folders.size)
        assertEquals(7, updated.likes)
        assertEquals(first.month, updated.month)
        assertEquals(3, updated.photoFileIds.size)
        assertTrue(repo.trips.getValue("post1").contains("Note n3"))
    }

    @Test fun aLinkFrom119WithItsAutoSwitchStillReads() {
        val old = """[{"tripId":"t","postId":"post1","title":"Patagonia","auto":true,"sent":"abc"}]"""
        val link = PostLinks.decode(old).getValue("t")
        assertEquals("post1", link.postId)
        assertEquals("abc", link.sent)
        assertFalse(PostLinks.encode(mapOf("t" to link)).contains("auto"))
    }
}

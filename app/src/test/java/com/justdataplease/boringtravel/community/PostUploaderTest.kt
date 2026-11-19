package com.justdataplease.boringtravel.community

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.ZoneOffset

class PostUploaderTest {
    private class FakeStore : PostStore {
        val folders = ArrayList<String>()
        val photos = ArrayList<String>()
        val videos = ArrayList<String>()
        val deleted = ArrayList<String>()
        var failPhotoAt: Int = -1
        private var n = 0

        override suspend fun folder(name: String): String = "folder${folders.size + 1}".also { folders += name }

        override suspend fun photo(folderId: String, name: String, jpeg: ByteArray): String {
            if (photos.size == failPhotoAt) {
                failPhotoAt = -1
                throw IOException("Network dropped")
            }
            return "file${++n}".also { photos += name }
        }

        override suspend fun video(folderId: String, name: String, file: File): String = "video${++n}".also { videos += name }
        override suspend fun delete(folderId: String) { deleted += folderId }
    }

    private val now = 1_794_355_200_000L // 2026-11-11
    private val repo = FakeCommunityRepository(clock = { now }).apply {
        profiles["me"] = Profile("me", "alexa")
        usernames["alexa"] = "me"
    }
    private val store = FakeStore()
    private val readable = PostPictures { key -> if (key == "broken") null else key.toByteArray() }
    private val uploader = PostUploader(store, readable, repo, now = { now }, zone = ZoneOffset.UTC)

    private fun tripPlan(pictures: List<String>, cover: String?) = PostPlan(
        id = "p1", kind = PostKind.Trip, title = "Patagonia", caption = "Wind", countries = listOf("ar", "CL", "xx1"),
        days = 12, km = 1240, startMonth = "2027-03", pictures = pictures, cover = cover,
        trip = SharedTrip(days = 12, items = listOf(SharedItem(day = 1, title = "BA")), notes = listOf(
            SharedNote(day = 1, title = "First day", photos = listOf("m1", "broken")),
            SharedNote(day = 2, title = "Second", photos = listOf("m2")),
        )),
    )

    @Test fun aTripPostGoesUpInOrder() = runTest {
        val progress = ArrayList<UploadProgress>()
        val post = uploader.run(tripPlan(listOf("cover", "m1", "broken", "m2"), "cover"), UploadState()) { progress += it }
        assertEquals(listOf("Patagonia 2026-11-11"), store.folders)
        assertEquals(3, store.photos.size) // "broken" couldn't be read: left out
        assertEquals("p1", post.id)
        assertEquals("me", post.uid)
        assertEquals("alexa", post.username)
        assertEquals("file1", post.coverFileId)
        assertEquals(listOf("file1", "file2", "file3"), post.photoFileIds)
        assertEquals(listOf("AR", "CL"), post.countries)
        assertEquals("2026-11", post.month)
        assertEquals("folder1", post.folderId)
        assertTrue(post.hasTrip)
        assertEquals(0, post.likes)
        val trip = SharedTrips.decode(repo.trips["p1"])!!
        assertEquals(listOf(listOf("file2"), listOf("file3")), trip.notes.map { it.photos })
        assertEquals("Posted", progress.last().label)
        assertEquals(1f, progress.last().fraction)
    }

    @Test fun aRetryCarriesOnWhereItStopped() = runTest {
        val state = UploadState()
        store.failPhotoAt = 1
        try {
            uploader.run(tripPlan(listOf("a", "b", "c"), "a"), state)
            fail("expected the upload to stop")
        } catch (e: IOException) {
            assertEquals("Network dropped", e.message)
        }
        assertEquals(1, store.photos.size)
        assertEquals(1, store.folders.size)
        val post = uploader.run(tripPlan(listOf("a", "b", "c"), "a"), state)
        assertEquals(1, store.folders.size) // the same folder
        assertEquals(3, store.photos.size) // only the rest
        assertEquals(listOf("file1", "file2", "file3"), post.photoFileIds)
        // Once posted, running again changes nothing.
        assertSame(post, uploader.run(tripPlan(listOf("a", "b", "c"), "a"), state))
        assertEquals(1, repo.posts.size)
    }

    @Test fun aFailedPostCanBeRetried() = runTest {
        val state = UploadState()
        repo.failNext = CommunityOfflineException()
        try {
            uploader.run(tripPlan(listOf("a"), null), state)
            fail("expected the post to fail")
        } catch (_: CommunityOfflineException) {
        }
        assertEquals(1, store.photos.size)
        val post = uploader.run(tripPlan(listOf("a"), null), state)
        assertEquals(1, store.photos.size)
        assertNull(post.coverFileId) // a drawn cover
        assertEquals(listOf("file1"), post.photoFileIds)
    }

    @Test fun aClipPostUploadsTheVideoAndItsFrame() = runTest {
        val plan = PostPlan(
            id = "c1", kind = PostKind.Clip, title = "Route", pictures = listOf(PostPlan.CLIP_FRAME),
            cover = PostPlan.CLIP_FRAME, clip = File("clip.mp4"),
        )
        val post = uploader.run(plan, UploadState())
        assertEquals(listOf("clip.mp4"), store.videos)
        assertEquals(PostKind.Clip, post.kind)
        assertEquals("file1", post.coverFileId)
        assertTrue(post.clipFileId!!.startsWith("video"))
        assertTrue(!post.hasTrip)
        assertNull(repo.trips["c1"])
    }

    @Test fun aPlanWithADrawnCoverMakesNoFolder() = runTest {
        val progress = ArrayList<UploadProgress>()
        val post = uploader.run(tripPlan(emptyList(), null), UploadState()) { progress += it }
        assertTrue(store.folders.isEmpty())
        assertNull(post.folderId)
        assertNull(post.coverFileId)
        assertTrue(post.photoFileIds.isEmpty())
        assertTrue(post.hasTrip)
        assertEquals(listOf("Posting", "Posted"), progress.map { it.label })
        assertEquals(1f, progress.last().fraction)
    }

    @Test fun discardDeletesTheFolderOfAnUnfinishedPost() = runTest {
        val state = UploadState()
        store.failPhotoAt = 0
        runCatching { uploader.run(tripPlan(listOf("a"), null), state) }
        uploader.discard(state)
        assertEquals(listOf("folder1"), store.deleted)
        assertNull(state.folderId)
    }

    @Test fun rulesSayWhatIsMissing() {
        val base = PostPlan(id = "x", kind = PostKind.Album, title = "Album", pictures = listOf("a"))
        assertNull(PostRules.problem(base))
        assertEquals("Give it a title.", PostRules.problem(base.copy(title = " ")))
        assertEquals("Pick at least one photo.", PostRules.problem(base.copy(pictures = emptyList())))
        assertEquals("Up to 30 photos.", PostRules.problem(base.copy(pictures = (1..31).map { "p$it" })))
        assertEquals("Pick a clip.", PostRules.problem(base.copy(kind = PostKind.Clip)))
        assertEquals("This trip has nothing to share yet.", PostRules.problem(base.copy(kind = PostKind.Trip)))
        assertEquals("Pick the cover again.", PostRules.problem(base.copy(cover = "zzz")))
        assertEquals("Lisbon 2026-11-11", PostRules.folderName("Lisbon", now, ZoneOffset.UTC))
        assertEquals("a b 2026-11-11", PostRules.folderName("a/\\b", now, ZoneOffset.UTC))
        assertEquals("Post 2026-11-11", PostRules.folderName("  ", now, ZoneOffset.UTC))
    }
}

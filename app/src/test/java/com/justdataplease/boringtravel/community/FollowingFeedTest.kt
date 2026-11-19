package com.justdataplease.boringtravel.community

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FollowingFeedTest {
    /** 70 people in three groups; some post a lot, some never; times spread so groups interleave. */
    private val repo = FakeCommunityRepository().apply {
        var n = 0
        for (person in 1..70) {
            val count = when {
                person % 7 == 0 -> 0
                person % 5 == 0 -> 9
                else -> 2
            }
            repeat(count) { i ->
                n++
                val id = "p$person-$i"
                posts[id] = Post(id, "u$person", "user$person", PostKind.Trip, id, createdAt = ((n * 7_919L) % 10_007L) * 1000)
            }
        }
    }
    private val people = (1..70).map { FollowLink("u$it", "user$it") }

    private suspend fun readAll(limit: Int, fetch: suspend (List<String>, Any?, Int) -> Page<Post>): List<List<Post>> {
        val pages = ArrayList<List<Post>>()
        var page = FollowingFeed.page(FollowingFeed.start(Follows.feedGroups(people)), limit, fetch)
        pages += page.items
        while (page.next != null) {
            page = FollowingFeed.page(page.next as FollowingFeed.Cursor, limit, fetch)
            pages += page.items
        }
        return pages
    }

    @Test fun pagesMergeTheGroupsNewestFirstWithoutGapsOrRepeats() = runTest {
        val pages = readAll(20) { uids, after, limit -> repo.postsOf(uids, after, limit) }
        val expected = repo.posts.values.sortedWith(FollowingFeed.order).map { it.id }
        assertEquals(expected, pages.flatten().map { it.id })
        assertTrue(pages.dropLast(1).all { it.size == 20 })
    }

    @Test fun aGroupIsReadAgainOnlyWhenItHoldsLessThanAPage() = runTest {
        var read = 0
        readAll(20) { uids, after, limit -> repo.postsOf(uids, after, limit).also { read += it.items.size } }
        assertEquals(listOf(30, 30, 10), repo.postsOfCalls.take(3).map { it.size })
        // Every post was read once: no group was asked for the same posts twice.
        assertEquals(repo.posts.size, read)
    }

    @Test fun aFailedPageIsAskedAgainFromTheSameCursor() = runTest {
        val start = FollowingFeed.start(Follows.feedGroups(people))
        val first = FollowingFeed.page(start, 20) { uids, after, limit -> repo.postsOf(uids, after, limit) }
        val cursor = first.next as FollowingFeed.Cursor
        var calls = 0
        try {
            FollowingFeed.page(cursor, 20) { uids, after, limit ->
                if (++calls == 1) throw IOException("dropped")
                repo.postsOf(uids, after, limit)
            }
            fail("should fail")
        } catch (_: IOException) {
        }
        val again = FollowingFeed.page(cursor, 20) { uids, after, limit -> repo.postsOf(uids, after, limit) }
        val expected = repo.posts.values.sortedWith(FollowingFeed.order).map { it.id }
        assertEquals(expected.subList(20, 40), again.items.map { it.id })
    }

    @Test fun nobodyFollowedIsAnEmptyLastPage() = runTest {
        val page = FollowingFeed.page(FollowingFeed.start(emptyList()), 20) { _, _, _ -> error("no query") }
        assertTrue(page.items.isEmpty())
        assertNull(page.next)
    }

    @Test fun aPageFromThePhonesCopySaysSo() = runTest {
        val page = FollowingFeed.page(FollowingFeed.start(listOf(listOf("u1"), listOf("u2"))), 20) { uids, after, limit ->
            val p = repo.postsOf(uids, after, limit)
            if (uids == listOf("u2")) p.copy(fromCache = true) else p
        }
        assertTrue(page.fromCache)
        assertEquals(4, page.items.size)
    }
}

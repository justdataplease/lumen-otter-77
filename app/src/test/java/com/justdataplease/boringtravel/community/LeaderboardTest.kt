package com.justdataplease.boringtravel.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LeaderboardTest {
    private fun post(id: String, likes: Int, at: Long, uid: String = "u$id") =
        Post(id = id, uid = uid, username = "user$id", kind = PostKind.Trip, title = id, likes = likes, createdAt = at)

    @Test fun mostLikedFirstAndTiesShareARank() {
        val rows = Leaderboard.rank(listOf(post("a", 3, 10), post("b", 9, 50), post("c", 3, 5), post("d", 1, 1), post("e", 9, 20)))
        assertEquals(listOf("e", "b", "c", "a", "d"), rows.map { it.post.id })
        assertEquals(listOf(1, 1, 3, 3, 5), rows.map { it.rank })
        assertEquals(listOf(Leaderboard.Medal.Gold, Leaderboard.Medal.Gold, Leaderboard.Medal.Bronze, Leaderboard.Medal.Bronze, null), rows.map { it.medal })
    }

    @Test fun pagesPutTogetherDoNotRepeatAndBlockedPeopleAreLeftOut() {
        val page1 = listOf(post("a", 5, 1), post("b", 4, 1, uid = "blocked"))
        val page2 = listOf(post("b", 4, 1, uid = "blocked"), post("c", 2, 1))
        val rows = Leaderboard.rank(page1 + page2, hidden = setOf("blocked"))
        assertEquals(listOf("a", "c"), rows.map { it.post.id })
        assertEquals(listOf(1, 2), rows.map { it.rank })
        assertEquals(Leaderboard.Medal.Silver, rows[1].medal)
    }

    @Test fun likeTapsAreOptimisticAndRollBack() {
        val book = LikeBook()
        book.seed(post("p", 4, 0), liked = false)
        val on = book.toggle("p", LikeState(false, 0))!!
        assertTrue(on.liked)
        assertEquals(5, on.count)
        assertTrue(on.pending)
        // A second tap waits for the first.
        assertNull(book.toggle("p", LikeState(false, 0)))
        // Server data does not undo a change on its way.
        book.seed(post("p", 4, 0), liked = false)
        assertEquals(5, book.snapshot()["p"]!!.count)
        book.fail("p")
        assertEquals(LikeState(false, 4), book.snapshot()["p"])
        book.toggle("p", LikeState(false, 0))
        book.confirm("p")
        assertEquals(LikeState(true, 5), book.snapshot()["p"])
        val off = book.toggle("p", LikeState(false, 0))!!
        assertFalse(off.liked)
        assertEquals(4, off.count)
    }

    @Test fun unlikingNeverGoesBelowZero() {
        assertEquals(0, LikeState(true, 0).toggled().count)
    }

    @Test fun seedKeepsAKnownLikeWhenTheServerDoesNotSay() {
        val book = LikeBook()
        book.seed(post("p", 1, 0), liked = true)
        book.seed(post("p", 2, 0), liked = null)
        assertEquals(LikeState(true, 2), book.snapshot()["p"])
        assertTrue(book.known("p"))
        book.forget("p")
        assertFalse(book.known("p"))
    }

    @Test fun aFailedLookUpStaysUnknownAndIsAskedAgain() {
        val book = LikeBook()
        book.seed(post("p", 3, 0), liked = null)
        assertEquals(LikeState(false, 3), book.snapshot()["p"]) // shown, but not known
        assertFalse(book.known("p"))
        book.seed(post("p", 3, 0), liked = true)
        assertTrue(book.known("p"))
        assertEquals(LikeState(true, 3), book.snapshot()["p"])
    }

    @Test fun aRefusedTapTakesTheServersState() {
        val book = LikeBook()
        // Liked on another phone, look-up failed: the heart shows empty and a tap "likes" it again.
        book.seed(post("p", 3, 0), liked = null)
        val tap = book.toggle("p", LikeState(false, 3))!!
        assertEquals(LikeState(true, 4, pending = true), tap)
        book.adopt("p", LikeState(true, 3))
        assertEquals(LikeState(true, 3), book.snapshot()["p"])
        assertTrue(book.known("p"))
        // The next tap unlikes, from the server's state.
        assertEquals(LikeState(false, 2, pending = true), book.toggle("p", LikeState(false, 0)))
    }

    @Test fun aRefusalThatCantBeCheckedGoesBackAndIsAskedAgain() {
        val book = LikeBook()
        book.seed(post("p", 1, 0), liked = false)
        book.toggle("p", LikeState(false, 1))
        book.fail("p")
        assertEquals(LikeState(false, 1), book.snapshot()["p"])
        assertFalse(book.known("p"))
    }

    @Test fun aQueuedChangeIsShownButNotKnown() {
        val book = LikeBook()
        book.seed(post("p", 1, 0), liked = null)
        book.toggle("p", LikeState(false, 1))
        book.confirm("p", onServer = false)
        assertEquals(LikeState(true, 2), book.snapshot()["p"])
        assertFalse(book.known("p"))
        book.clear()
        assertTrue(book.snapshot().isEmpty())
    }

    @Test fun texts() {
        assertEquals("12 days · 1,240 km", PostText.facts(12, 1240, Locale.US))
        assertEquals("1 day", PostText.facts(1, 0, Locale.US))
        assertEquals("", PostText.facts(0, 0, Locale.US))
        assertEquals("1 like", PostText.likes(1))
        assertEquals("1.2k likes", PostText.likes(1234))
        assertEquals("12k", PostText.count(12_345))
        assertEquals("999", PostText.count(999))
        assertEquals("1k", PostText.count(1_000))
        assertEquals("2.5m", PostText.count(2_500_000))
        assertEquals("2026-11", PostText.monthKey(1_794_355_200_000L)) // 2026-11-11 UTC
        assertEquals("Mar 2027", PostText.monthLabel("2027-03", Locale.US))
        assertNull(PostText.monthLabel(null))
        assertEquals("@alexa", PostText.handle("alexa"))
        val now = 1_794_355_200_000L
        assertEquals("now", PostText.ago(now - 5_000, now))
        assertEquals("5m", PostText.ago(now - 5 * 60_000, now))
        assertEquals("3h", PostText.ago(now - 3 * 3_600_000L, now))
        assertEquals("2d", PostText.ago(now - 2 * 86_400_000L, now))
        assertEquals("4 Oct 2026", PostText.ago(now - 38 * 86_400_000L, now, Locale.US))
    }

    @Test fun picturesStartWithTheCover() {
        val p = post("p", 0, 0).copy(coverFileId = "c", photoFileIds = listOf("a", "c", "b"))
        assertEquals(listOf("c", "a", "b"), p.pictures)
        assertEquals(listOf("a"), post("q", 0, 0).copy(photoFileIds = listOf("a")).pictures)
    }
}

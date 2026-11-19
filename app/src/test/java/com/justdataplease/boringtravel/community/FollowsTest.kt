package com.justdataplease.boringtravel.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowsTest {
    private fun links(n: Int) = (1..n).map { FollowLink("u$it", "user$it", at = (1_000 - it).toLong()) }

    private fun post(id: String, uid: String, likes: Int, at: Long = 0) =
        Post(id = id, uid = uid, username = "name.$uid", kind = PostKind.Trip, title = id, likes = likes, createdAt = at, hasTrip = true)

    @Test fun nobodyFollowsThemselvesOrSomeoneTheyBlocked() {
        assertTrue(Follows.canFollow("me", "ann", emptySet()))
        assertFalse(Follows.canFollow("me", "me", emptySet()))
        assertFalse(Follows.canFollow("me", "ann", setOf("ann")))
        assertFalse(Follows.canFollow(null, "ann", emptySet()))
    }

    @Test fun theFeedAsksThirtyPeopleAtATimeForTheNewest150() {
        assertEquals(listOf(30, 30, 10), Follows.feedGroups(links(70)).map { it.size })
        val groups = Follows.feedGroups(links(200))
        assertEquals(5, groups.size)
        assertEquals(150, groups.flatten().size)
        assertEquals("u1", groups.first().first())
        assertEquals("u150", groups.last().last())
        assertTrue(Follows.capped(links(151)))
        assertFalse(Follows.capped(links(150)))
    }

    @Test fun blockedPeopleAndRepeatsLeaveTheFeed() {
        val list = links(3) + FollowLink("u2", "again")
        assertEquals(listOf(listOf("u1", "u3")), Follows.feedGroups(list, hidden = setOf("u2")))
        assertEquals(emptyList<List<String>>(), Follows.feedGroups(emptyList()))
    }

    @Test fun aTapMovesTheFollowersByOneUntilTheyAreCountedAgain() {
        val counted = CountedFollows(FollowCounts(followers = 12, following = 5), at = 0, followingThen = false)
        assertEquals(12, Follows.shownFollowers(counted, followingNow = false))
        assertEquals(13, Follows.shownFollowers(counted, followingNow = true))
        val wasFollowing = counted.copy(followingThen = true)
        assertEquals(11, Follows.shownFollowers(wasFollowing, followingNow = false))
        assertEquals(0, Follows.shownFollowers(CountedFollows(FollowCounts(0, 0), 0, true), followingNow = false))
        // Not known when counted, or not known now: the count as it is.
        assertEquals(12, Follows.shownFollowers(counted.copy(followingThen = null), followingNow = true))
        assertEquals(12, Follows.shownFollowers(counted, followingNow = null))
    }

    @Test fun labels() {
        assertEquals("follower", Follows.followersLabel(1))
        assertEquals("followers", Follows.followersLabel(0))
        assertEquals("followers", Follows.followersLabel(12))
    }

    @Test fun suggestionsArePostersOfTheMostLikedTripsEachOnce() {
        val top = listOf(
            post("a1", "ann", likes = 40), post("b1", "ben", likes = 90), post("a2", "ann", likes = 70),
            post("m1", "me", likes = 99), post("x1", "blocked", likes = 80), post("c1", "cy", likes = 10),
            post("d1", "dee", likes = 60), post("e1", "eve", likes = 50), post("f1", "fay", likes = 45),
        )
        val picks = Follows.suggestions(top, me = "me", hidden = setOf("blocked"), following = setOf("dee"), max = 4)
        assertEquals(listOf("b1", "a2", "e1", "f1"), picks.map { it.id })
    }

    @Test fun aFollowTapIsOptimisticAndWaitsForTheServer() {
        val book = FollowBook()
        book.seed("ann", following = false)
        val on = book.toggle("ann")!!
        assertTrue(on.following)
        assertTrue(on.pending)
        // A second tap waits for the first; the server's older answer doesn't undo it.
        assertNull(book.toggle("ann"))
        book.seed("ann", following = false)
        assertEquals(on, book.snapshot()["ann"])
        book.confirm("ann")
        assertEquals(FollowState(true), book.snapshot()["ann"])
        // Unfollow is the same tap back.
        assertFalse(book.toggle("ann")!!.following)
    }

    @Test fun aRefusedTapGoesBackWithALine() {
        val book = FollowBook()
        book.seed("ann", following = false)
        book.toggle("ann")
        book.refused("ann", server = null, problem = "Couldn't follow. Try again.")
        assertEquals(FollowState(false, problem = "Couldn't follow. Try again."), book.snapshot()["ann"])
        // The next tap starts clean.
        assertEquals(FollowState(true, pending = true), book.toggle("ann"))
    }

    @Test fun aRefusedTapTakesWhatTheServerSays() {
        val book = FollowBook()
        book.seed("ann", following = false)
        book.toggle("ann")
        // Another phone followed them first: nothing is wrong.
        book.refused("ann", server = true, problem = "x")
        assertEquals(FollowState(true), book.snapshot()["ann"])
        book.toggle("ann")
        book.refused("ann", server = true, problem = "Couldn't unfollow. Try again.")
        assertEquals(FollowState(true, problem = "Couldn't unfollow. Try again."), book.snapshot()["ann"])
    }

    @Test fun notKnownYetStartsFromNotFollowing() {
        val book = FollowBook()
        assertFalse(book.known("ann"))
        assertEquals(FollowState(true, pending = true), book.toggle("ann"))
        assertTrue(book.known("ann"))
    }

    @Test fun aBlockEndsTheFollowWhateverWasOnItsWay() {
        val book = FollowBook()
        book.seed("ann", true)
        book.toggle("ann")
        book.ended("ann")
        assertEquals(FollowState(false), book.snapshot()["ann"])
        book.forget("ann")
        assertFalse(book.known("ann"))
    }
}

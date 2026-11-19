package com.justdataplease.boringtravel.community

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowKeeperTest {
    private var now = 1_000_000L
    private val repo = FakeCommunityRepository(clock = { now }).apply {
        for ((uid, name) in listOf("me" to "alexa", "ann" to "ann.walks", "ben" to "ben.rides", "cy" to "cy.sails")) {
            profiles[uid] = Profile(uid, name)
            usernames[name] = uid
        }
    }

    private fun TestScope.keeper() = FollowKeeper(repo, this, clock = { now })

    private fun FollowKeeper.follow(uid: String) = toggle(uid, repo.profiles.getValue(uid).username, "alexa")

    @Test fun followShowsAtOnceAndTheServerFollows() = runTest {
        val k = keeper()
        k.seed("ann")
        assertEquals(FollowState(false), k.states.value["ann"])
        val tapped = k.follow("ann")!!
        assertTrue(tapped.following && tapped.pending)
        assertEquals(tapped, k.states.value["ann"])
        advanceUntilIdle()
        assertEquals(FollowState(true), k.states.value["ann"])
        assertEquals(listOf(Triple("me", "ann", "alexa")), repo.follows.map { Triple(it.from, it.to, it.fromName) })
        assertEquals("ann.walks", repo.follows.single().toName)
        assertEquals(1, k.changes.value)
        // Unfollow is one tap back, nothing asked.
        k.follow("ann")
        advanceUntilIdle()
        assertEquals(FollowState(false), k.states.value["ann"])
        assertTrue(repo.follows.isEmpty())
    }

    @Test fun nobodyFollowsThemselves() = runTest {
        assertNull(keeper().toggle("me", "alexa", "alexa"))
    }

    @Test fun aRefusedFollowGoesBackWithALine() = runTest {
        repo.blockedBy += "ben"
        val k = keeper()
        k.seed("ben")
        k.follow("ben")
        advanceUntilIdle()
        assertEquals(FollowState(false, problem = "Couldn't follow. Try again."), k.states.value["ben"])
        assertTrue(repo.follows.isEmpty())
        assertEquals(0, k.changes.value)
    }

    @Test fun offlineTheFollowWaitsOnThePhoneAndCountsAsDone() = runTest {
        val k = keeper()
        repo.failNext = CommunityOfflineException()
        k.follow("ann")
        advanceUntilIdle()
        assertEquals(FollowState(true), k.states.value["ann"])
    }

    @Test fun aSlowServerCountsAsKeptAfterTheWait() = runTest {
        repo.writeDelayMs = FollowKeeper.WAIT_MS * 3
        val k = keeper()
        k.follow("ann")
        advanceUntilIdle()
        assertEquals(FollowState(true), k.states.value["ann"])
    }

    @Test fun whetherTheyAreFollowedIsAskedOnce() = runTest {
        val k = keeper()
        k.seed("ann")
        k.seed("ann")
        k.seed("me")
        assertEquals(listOf("isFollowing"), repo.calls)
    }

    @Test fun aFailedLookUpIsAskedAgain() = runTest {
        val k = keeper()
        repo.failNext = CommunityOfflineException()
        k.seed("ann")
        assertNull(k.states.value["ann"])
        k.seed("ann")
        assertEquals(FollowState(false), k.states.value["ann"])
    }

    @Test fun countsAreKeptAMinuteAndATapMovesThemAtOnce() = runTest {
        repo.follows += FakeCommunityRepository.Follow("ben", "ann", "ben.rides", "ann.walks", 1)
        val k = keeper()
        k.seed("ann")
        val first = k.counts("ann")!!
        assertEquals(FollowCounts(followers = 1, following = 0), first.counts)
        assertEquals(false, first.followingThen)
        k.follow("ann")
        advanceUntilIdle()
        // Kept: no new count, and the shown number already has the viewer in it.
        val kept = k.counts("ann")!!
        assertEquals(first, kept)
        assertEquals(2, Follows.shownFollowers(kept, k.states.value["ann"]?.following))
        assertEquals(1, repo.calls.count { it == "followCounts" })
        now += Follows.COUNT_TTL_MS
        val again = k.counts("ann")!!
        assertEquals(FollowCounts(2, 0), again.counts)
        assertEquals(2, Follows.shownFollowers(again, k.states.value["ann"]?.following))
    }

    @Test fun theViewersOwnCountsAreAskedAgainAfterAFollow() = runTest {
        val k = keeper()
        assertEquals(FollowCounts(0, 0), k.counts("me")!!.counts)
        k.follow("ann")
        advanceUntilIdle()
        assertEquals(FollowCounts(0, 1), k.counts("me")!!.counts)
    }

    @Test fun countsOfflineAreNotKnown() = runTest {
        repo.failNext = CommunityOfflineException()
        assertNull(keeper().counts("ann"))
    }

    @Test fun theFeedsPeopleAreReadOnceAndFollowTheTaps() = runTest {
        repo.follows += FakeCommunityRepository.Follow("me", "ben", "alexa", "ben.rides", 5)
        val k = keeper()
        assertEquals(listOf("ben"), k.mine().map { it.uid })
        assertEquals(FollowState(true), k.states.value["ben"])
        k.follow("ann")
        advanceUntilIdle()
        assertEquals(listOf("ann", "ben"), k.mine().map { it.uid })
        k.toggle("ben", "ben.rides", "alexa")
        advanceUntilIdle()
        assertEquals(listOf("ann"), k.mine().map { it.uid })
        assertEquals(1, repo.calls.count { it == "following" })
        assertEquals(listOf("ann"), k.mine(fresh = true).map { it.uid })
        assertEquals(2, repo.calls.count { it == "following" })
    }

    @Test fun aBlockEndsTheFollowEverywhere() = runTest {
        repo.follows += FakeCommunityRepository.Follow("me", "ben", "alexa", "ben.rides", 5)
        val k = keeper()
        k.mine()
        k.ended("ben")
        assertEquals(FollowState(false), k.states.value["ben"])
        assertTrue(k.mine().isEmpty())
        assertEquals(1, k.changes.value)
        k.forget("ben")
        assertFalse("ben" in k.states.value)
    }

    @Test fun listProfilesAreReadOnceAndGoneOnesSaySo() = runTest {
        val k = keeper()
        val first = k.profiles(listOf("ann", "ghost"))
        assertEquals("ann.walks", first["ann"]?.username)
        assertTrue("ghost" in first && first["ghost"] == null)
        repo.profiles["ann"] = Profile("ann", "ann.renamed")
        assertEquals("ann.walks", k.profiles(listOf("ann"))["ann"]?.username)
        now += FollowKeeper.PROFILE_TTL_MS
        assertEquals("ann.renamed", k.profiles(listOf("ann"))["ann"]?.username)
    }

    @Test fun aProfileSeenOnItsPageIsNotReadAgain() = runTest {
        val k = keeper()
        k.saw(Profile("cy", "cy.new"))
        assertEquals("cy.new", k.profiles(listOf("cy"))["cy"]?.username)
    }

    @Test fun anotherSignInStartsClean() = runTest {
        val k = keeper()
        k.seed("ann")
        k.clear()
        assertTrue(k.states.value.isEmpty())
    }
}

package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.community.BlockedUser
import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.community.CommunityAuth
import com.justdataplease.boringtravel.community.CommunityOfflineException
import com.justdataplease.boringtravel.community.CommunityRefusedException
import com.justdataplease.boringtravel.community.FirestoreCommunity
import com.justdataplease.boringtravel.community.FollowLink
import com.justdataplease.boringtravel.community.ReportReason
import com.justdataplease.boringtravel.community.TopRange
import com.justdataplease.boringtravel.community.UsernameTakenException
import com.justdataplease.boringtravel.firebase.FsTime
import com.justdataplease.boringtravel.google.PublicDrive
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The community's transport (round 24): Firestore's REST API and Firebase Auth's, in place of Android's Firebase SDK,
 * against the in-memory Firestore that holds the owner's rules as far as the app meets them (CommunityFakes.kt). The same
 * documents and writes as Android's FirestoreCommunity: a username claimed once and a rename that the posts follow, a
 * like once per person and never on one's own post, pages that follow their cursor across equal likes, follows with
 * both halves and never across a block, reports and blocks, everything of theirs deleted; what the SDK keeps on the
 * phone kept by hand (the writes made offline, sent in order; the lists read, shown offline); the ID token renewed and an
 * account Firebase no longer has signed out; the session kept between starts; other people's pictures and clips read by
 * link with the build's key; and the community's screens brought back after the phone closed the app.
 */
class FirestoreCommunityOnDesktopTest {
    private val me = CommunityPeople.ME
    private val other = CommunityPeople.OTHER
    private val third = CommunityPeople.THIRD
    private val patagonia = CommunityWorld.PATAGONIA

    private fun signedIn(mine: Boolean = true): Triple<CommunityWorld, Community, FirestoreCommunity> {
        val world = CommunityWorld()
        world.signInWithGoogle()
        world.seed(mine = mine)
        world.pictures()
        val c = world.community()
        assertNull(runBlocking { c.signIn(quiet = true) })
        return Triple(world, c, c.repo as FirestoreCommunity)
    }

    @Test fun aUsernameIsClaimedOnceAndARenameReachesThePosts() = runBlocking<Unit> {
        val (world, _, repo) = signedIn()
        assertFailsWith<UsernameTakenException> { repo.saveProfile(CommunityPeople.OTHER_NAME, "Me", "", null) }
        assertEquals(me, world.fs.docs["usernames/${CommunityPeople.ME_NAME}"]?.get("uid"))
        val saved = repo.saveProfile("coast.walker", "  Slow ferries  ", "Islands.", null)
        assertEquals("coast.walker", saved.username)
        assertEquals("Slow ferries", saved.name)
        assertFalse(world.fs.docs.containsKey("usernames/${CommunityPeople.ME_NAME}"))
        assertEquals(me, world.fs.docs["usernames/coast.walker"]?.get("uid"))
        assertEquals("coast.walker", world.fs.docs["posts/${CommunityWorld.OWN}"]?.get("username"))
        // The first createdAt stays; updatedAt is the server's time.
        val profile = world.fs.docs["profiles/$me"]!!
        assertTrue((profile["updatedAt"] as FsTime).ms > (profile["createdAt"] as FsTime).ms)
        assertEquals("coast.walker", repo.usernameOwner("coast.walker")?.let { world.fs.docs["profiles/$it"]?.get("username") })
        assertNull(repo.usernameOwner("Not Valid"))
    }

    @Test fun aLikeOncePerPersonAndNeverOnOnesOwnPost() = runBlocking<Unit> {
        val (world, _, repo) = signedIn()
        repo.setLiked(patagonia, true)
        assertEquals(129L, world.fs.docs["posts/$patagonia"]?.get("likes"))
        assertEquals(mapOf(patagonia to true, CommunityWorld.CLIP to false), repo.liked(listOf(patagonia, CommunityWorld.CLIP)))
        // The rules refuse a second like and a like on one's own post; the app says so in people's words.
        assertFailsWith<CommunityRefusedException> { repo.setLiked(patagonia, true) }
        assertFailsWith<CommunityRefusedException> { repo.setLiked(CommunityWorld.OWN, true) }
        repo.setLiked(patagonia, false)
        assertEquals(128L, world.fs.docs["posts/$patagonia"]?.get("likes"))
        // A like and its count go in one commit.
        val last = world.fs.commits.last()
        assertEquals(2, last.size)
    }

    @Test fun pagesFollowTheirCursorAcrossEqualLikes() = runBlocking<Unit> {
        val (world, _, repo) = signedIn()
        world.fs.docs.keys.filter { it.startsWith("posts/") }.toList().forEach { world.fs.docs.remove(it) }
        val start = 1_790_000_000_000L
        repeat(25) { i -> world.fs.post("pTop${i.toString().padStart(2, '0')}", other, CommunityPeople.OTHER_NAME, "Trip $i", start + (i % 5) * 1_000L, "likes" to 5) }
        val seen = mutableListOf<String>()
        var after: Any? = null
        var pages = 0
        do {
            val page = repo.top(TopRange.AllTime, "2026-11", after, 10)
            seen += page.items.map { it.id }
            after = page.next
            pages++
        } while (after != null && pages < 10)
        assertEquals(3, pages)
        assertEquals(25, seen.size)
        assertEquals(25, seen.toSet().size)
        // Equal likes: the oldest first, then by id (the name Firestore orders by last).
        val expected = (0 until 25).map { "pTop${it.toString().padStart(2, '0')}" }.sortedWith(compareBy({ it.takeLast(2).toInt() % 5 }, { it }))
        assertEquals(expected, seen)
    }

    @Test fun writesMadeOfflineWaitAndGoInOrder() = runBlocking<Unit> {
        val (world, _, repo) = signedIn()
        world.cloud.connected = false
        assertFailsWith<CommunityOfflineException> { repo.setLiked(CommunityWorld.CLIP, true) }
        assertFailsWith<CommunityOfflineException> { repo.block(BlockedUser(third, CommunityPeople.THIRD_NAME)) }
        assertFailsWith<CommunityOfflineException> { repo.report(CommunityWorld.ALBUM, other, ReportReason.Spam) }
        assertTrue(repo.waiting())
        // What waits is what the phone shows.
        assertEquals(mapOf(CommunityWorld.CLIP to true), repo.liked(listOf(CommunityWorld.CLIP)))
        assertTrue(world.fs.reports.isEmpty())
        world.cloud.connected = true
        assertTrue(repo.flush())
        assertFalse(repo.waiting())
        assertEquals(43L, world.fs.docs["posts/${CommunityWorld.CLIP}"]?.get("likes"))
        assertTrue(world.fs.docs.containsKey("users/$me/blocked/$third"))
        assertEquals("spam", world.fs.reports.single()["reason"])
        assertEquals(listOf(third), repo.blocked().map { it.uid })
        // A write the rules refuse is dropped, not kept for ever (the SDK gives up on it too).
        world.cloud.connected = false
        assertFailsWith<CommunityOfflineException> { repo.setLiked(CommunityWorld.OWN, true) }
        world.cloud.connected = true
        assertTrue(repo.flush())
        assertFalse(repo.waiting())
    }

    @Test fun listsReadOnceShowOffline() = runBlocking<Unit> {
        val (world, _, repo) = signedIn()
        val online = repo.latest(null, 20)
        assertFalse(online.fromCache)
        assertEquals(listOf(patagonia, CommunityWorld.CLIP, CommunityWorld.ALBUM, CommunityWorld.IDEAS, CommunityWorld.OWN), online.items.map { it.id })
        assertNotNull(repo.trip(patagonia))
        world.cloud.connected = false
        val kept = repo.latest(null, 20)
        assertTrue(kept.fromCache)
        assertEquals(online.items.map { it.id }, kept.items.map { it.id })
        assertEquals("Patagonia on buses", repo.post(patagonia)?.title)
        assertNotNull(repo.trip(patagonia))
        // A list never read: nothing, said to be the phone's.
        val never = repo.top(TopRange.AllTime, "2026-11", null, 20)
        assertTrue(never.fromCache)
        assertTrue(never.items.isEmpty())
        // Deleting never uses the phone's copy: it fails at once.
        assertFailsWith<CommunityOfflineException> { repo.deleteAll() }
        assertTrue(world.fs.docs.containsKey("profiles/$me"))
    }

    @Test fun followsWithBothHalvesAndNeverAcrossABlock() = runBlocking<Unit> {
        val (world, _, repo) = signedIn()
        repo.follow(other, CommunityPeople.OTHER_NAME, CommunityPeople.ME_NAME)
        assertEquals(CommunityPeople.OTHER_NAME, world.fs.docs["users/$me/following/$other"]?.get("username"))
        assertEquals(CommunityPeople.ME_NAME, world.fs.docs["users/$other/followers/$me"]?.get("username"))
        assertTrue(repo.isFollowing(other))
        assertEquals(1, repo.followCounts(other).followers)
        assertEquals(0, repo.followCounts(other).following)
        assertEquals(listOf(FollowLink(me, CommunityPeople.ME_NAME)), repo.followers(other, null, 20).items.map { it.copy(at = 0) })
        repo.unfollow(other)
        assertFalse(repo.isFollowing(other))
        assertEquals(0, repo.followCounts(other).followers)
        // Blocked by them: the rules refuse the follow.
        world.fs.block(third, me, CommunityPeople.ME_NAME)
        assertFailsWith<CommunityRefusedException> { repo.follow(third, CommunityPeople.THIRD_NAME, CommunityPeople.ME_NAME) }
        // Nobody follows themselves.
        assertFailsWith<CommunityRefusedException> { repo.follow(me, CommunityPeople.ME_NAME, CommunityPeople.ME_NAME) }
    }

    @Test fun reportsAndBlocksAsTheRulesWantThem() = runBlocking<Unit> {
        val (world, _, repo) = signedIn()
        repo.report(patagonia, other, ReportReason.Inappropriate)
        repo.report(null, third, ReportReason.Other)
        val (post, profile) = world.fs.reports
        assertEquals(listOf(patagonia, other, me, "inappropriate"), listOf(post["postId"], post["target"], post["uid"], post["reason"]))
        assertNull(profile["postId"])
        assertEquals(third, profile["target"])
        assertTrue(post["at"] is FsTime)
        repo.block(BlockedUser(other, CommunityPeople.OTHER_NAME))
        assertEquals(listOf(other), repo.blocked().map { it.uid })
        repo.unblock(other)
        assertTrue(repo.blocked().isEmpty())
    }

    @Test fun deleteAllLeavesNothingOfTheirs() = runBlocking<Unit> {
        val (world, _, repo) = signedIn()
        val fs = world.fs
        fs.post("pParos01", me, CommunityPeople.ME_NAME, "Paros", 1_790_000_000_000L, "folderId" to "drvFolderParos1", "hasTrip" to true)
        fs.trip("pParos01", "{}")
        fs.like(patagonia, me)
        fs.like(CommunityWorld.OWN, other)
        fs.block(me, third, CommunityPeople.THIRD_NAME)
        fs.follow(me, CommunityPeople.ME_NAME, other, CommunityPeople.OTHER_NAME)
        fs.follow(third, CommunityPeople.THIRD_NAME, me, CommunityPeople.ME_NAME)
        assertEquals(129L, fs.docs["posts/$patagonia"]?.get("likes"))
        val folders = repo.deleteAll()
        assertEquals(listOf("drvFolderParos1"), folders)
        assertTrue(fs.docs.keys.none { me in it.split('/') }, fs.docs.keys.filter { me in it.split('/') }.toString())
        assertTrue(fs.docs.keys.none { it.startsWith("posts/pParos01") || it.startsWith("posts/${CommunityWorld.OWN}") })
        assertFalse(fs.docs.containsKey("usernames/${CommunityPeople.ME_NAME}"))
        assertEquals(128L, fs.docs["posts/$patagonia"]?.get("likes"))
        // Others' things stay.
        assertTrue(fs.docs.containsKey("profiles/$other"))
        assertTrue(fs.docs.containsKey("posts/$patagonia/content/trip"))
        assertFalse(repo.waiting())
    }

    @Test fun theTokenIsRenewedAndAnAccountFirebaseLostIsSignedOut() = runBlocking<Unit> {
        val (world, c, repo) = signedIn()
        // Firestore no longer takes the ID token: a new one, and the read goes through.
        world.cloud.firebaseIdTokens.clear()
        assertEquals("Patagonia on buses", repo.post(patagonia)?.title)
        assertTrue(world.cloud.calls.count { it.path == "/v1/token" } >= 1)
        // The account is gone from Firebase (deleted elsewhere): signed out here, and the community says so.
        world.cloud.firebaseUsers.clear()
        world.cloud.firebaseDeleted += "elsewhere"
        world.cloud.firebaseIdTokens.clear()
        assertFailsWith<Exception> { repo.post(patagonia) }
        assertNull(c.user.value)
    }

    @Test fun theSessionIsKeptBetweenStartsAndSigningInNeedsAnAccount() = runBlocking<Unit> {
        val (world, c, _) = signedIn()
        val again = CommunityAuth(world.cloud, CommunityWorld.FIREBASE, world.google.keys, world.account, CommunityWorld.BUNDLE)
        assertEquals(me, again.user.value?.uid)
        c.signOut()
        assertNull(c.user.value)
        // Signed out of the app too: quietly there is no way in, and nothing is asked of Firebase.
        val fresh = CommunityWorld()
        val none = fresh.community()
        assertEquals("", none.signIn(quiet = true))
        assertTrue(fresh.cloud.calls.none { it.host == "identitytoolkit.googleapis.com" })
        // A build without Firebase's ids: not available, and it says so.
        val off = fresh.community(firebase = false)
        assertFalse(off.available)
        assertEquals(CommunityAuth.UNAVAILABLE, off.signIn())
    }

    @Test fun anAppleAccountIsACommunityAccountToo() = runBlocking<Unit> {
        val world = CommunityWorld()
        world.seed()
        world.account.signInWithApple()
        val c = world.community()
        assertTrue(c.appleOffered)
        assertNull(c.signIn(quiet = true))
        assertEquals("fb-user-1", c.user.value?.uid)
        assertEquals("Patagonia on buses", c.repo.post(patagonia)?.title)
    }

    @Test fun picturesAndClipsAreReadByLinkWithTheBuildsKey() = runBlocking<Unit> {
        val (world, c, _) = signedIn()
        val pictures = c.pictures!!
        assertNotNull(pictures.load("drvPatagonia01", 400))
        val asked = world.cloud.calls.last { "drvPatagonia01" in it.url }
        assertEquals("www.googleapis.com", asked.host)
        assertEquals(CommunityWorld.DRIVE_KEY, asked.q("key"))
        assertEquals("media", asked.q("alt"))
        assertEquals(CommunityWorld.BUNDLE, asked.header("X-Ios-Bundle-Identifier"))
        // Never the viewer's own Drive token.
        assertNull(asked.header("Authorization"))
        val calls = world.cloud.calls.size
        assertNotNull(pictures.load("drvPatagonia01", 400))
        assertEquals(calls, world.cloud.calls.size)
        assertNull(pictures.load("drvNotShared1", 400))
        assertNull(pictures.load("not a drive id!", 400))
        // A clip: downloaded once into the caches.
        c.clipFile("drvBaClip01")
        assertNotNull(world.cache.read("community_clips/drvBaClip01.mp4"))
        val clipCalls = world.cloud.calls.count { "drvBaClip01" in it.url }
        c.clipFile("drvBaClip01")
        assertEquals(clipCalls, world.cloud.calls.count { "drvBaClip01" in it.url })
        // Without a key in the build: Google's public download address.
        val public = PublicDrive(world.cloud, "", CommunityWorld.BUNDLE, retryWait = 0)
        assertNotNull(public.bytes("drvPatagonia02", 5_000_000L))
        assertEquals("drive.usercontent.google.com", world.cloud.calls.last().host)
    }

    @Test fun theCommunitysScreensComeBackAfterARestart() {
        val book = sampleBook()
        val stack = AppStack(
            listOf(
                Place.Trips, Place.Home(HomeTab.Community), Place.CommunityPost(patagonia), Place.CommunityProfile(other),
                Place.CommunityFollows(other, followers = false), Place.CommunityProfileEdit,
            ),
        )
        val saved = assertNotNull(AppRestore.decode(AppRestore.encode(stack, TripTab.entries.first(), null, emptyMap()), book))
        assertEquals(stack.entries.map { it.place }, saved.stack)
    }
}

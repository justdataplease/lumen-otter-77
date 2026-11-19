package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.community.CommunityLinks
import com.justdataplease.boringtravel.community.CommunityOutbox
import com.justdataplease.boringtravel.community.FirestoreCommunity
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.firebase.FsTime
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 24's community driven on the desktop as a traveller would, on the fakes (Google, Firebase Auth, Firestore and
 * the posters' Drives in memory: CommunityFakes.kt, CommunitySamples.kt): the Community tab signed out (Google's and
 * Apple's sign-in, the way to reach the developer), signed in quietly, its Latest, Top and Following; a post's heart
 * reaching Firestore and waiting offline; Report (a post, a profile, offline) and Block (from a post: their posts gone,
 * the follows ended; a blocked profile and Community profile's list unblocking); a post gone or unreadable; Copy this
 * trip without dates and a plan of ideas; Share and a post's link opening the post; the app's sign-out and Delete
 * account reaching the community; New trip's "Start from a community trip"; the feed offline from what was saved.
 */
@OptIn(ExperimentalTestApi::class)
class CommunityOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    private val me = CommunityPeople.ME
    private val other = CommunityPeople.OTHER
    private val patagonia = CommunityWorld.PATAGONIA

    /** Signed in with Google on the fakes, the travellers' posts there ([mine]: a profile and a post of their own). */
    private fun signedIn(mine: Boolean = true, seed: Boolean = true): Pair<CommunityWorld, Community> {
        val world = CommunityWorld()
        world.signInWithGoogle()
        if (seed) world.seed(mine = mine) else if (mine) world.fs.profile(me, CommunityPeople.ME_NAME, "Slow ferries")
        world.pictures()
        val c = world.community()
        runBlocking { c.signIn(quiet = true) }
        return world to c
    }

    private fun ComposeUiTest.app(
        world: CommunityWorld, c: Community, vararg at: Place, book: TripBook = sampleBook(), phone: Phone = Phone(), links: AppLinks? = null,
    ) {
        setContent {
            CompositionLocalProvider(LocalPhone provides phone) {
                BoringTravelApp(
                    book, world.settings, SAMPLE_TODAY, startAt = at.toList(), version = "1.21.1", links = links,
                    google = world.account, community = c,
                )
            }
        }
    }

    private fun ComposeUiTest.has(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitFor(text: String, substring: Boolean = false, timeout: Long = 15_000) =
        waitUntil(timeoutMillis = timeout) { has(text, substring) }

    private fun ComposeUiTest.tapDescription(description: String) =
        onAllNodesWithContentDescription(description).onFirst().performClick()

    private fun post(id: String) = arrayOf(Place.Home(HomeTab.Community), Place.CommunityPost(id))

    private fun profile(uid: String) = arrayOf(Place.Home(HomeTab.Community), Place.CommunityProfile(uid))

    @Test fun signedOutTheTabSignsInWithGoogleAndShowsTheFeed() = runDesktopComposeUiTest(width = 440, height = 2000) {
        val world = CommunityWorld()
        world.seed()
        val c = world.community()
        app(world, c, Place.Home(HomeTab.Community))
        waitFor("Sign in with Google")
        onNodeWithText("Sign in with Apple").assertExists()
        onNodeWithText("Report or block anyone, any time.").assertExists()
        // Apple 1.2: a way to reach the developer, beside Report and Block.
        assertTrue(has("Something wrong? Write to ", substring = true))
        assertNull(c.user.value)
        onNodeWithText("Sign in with Google").performClick()
        waitFor("Patagonia on buses")
        assertEquals(me, c.user.value?.uid)
        // Google's page once (account and Drive), then Firebase with its ID token.
        assertEquals(1, world.google.browser.asked.size)
        assertTrue(world.cloud.calls.any { it.path == "/v1/accounts:signInWithIdp" && "providerId=google.com" in it.text })
        assertTrue(world.settings.signedIn)
    }

    @Test fun signedOutTheTabSignsInWithApple() = runDesktopComposeUiTest(width = 440, height = 2000) {
        val world = CommunityWorld()
        world.seed()
        val c = world.community()
        app(world, c, Place.Home(HomeTab.Community))
        waitFor("Sign in with Apple")
        onNodeWithText("Sign in with Apple").performClick()
        // The Apple account has no profile yet: the feed, and the card that asks for a username.
        waitFor("Pick a username")
        waitFor("Patagonia on buses")
        assertEquals("fb-user-1", c.user.value?.uid)
        assertEquals(1, world.google.apple.asked)
    }

    @Test fun signedInQuietlyTheFeedsLatestTopAndFollowing() = runDesktopComposeUiTest(width = 440, height = 2000) {
        val world = CommunityWorld()
        world.signInWithGoogle()
        world.seed()
        world.pictures()
        val c = world.community()
        val pages = world.google.browser.asked.size
        app(world, c, Place.Home(HomeTab.Community))
        // The phone is signed in with Google: no page, no button.
        waitFor("Patagonia on buses")
        assertEquals(me, c.user.value?.uid)
        assertEquals(pages, world.google.browser.asked.size)
        assertFalse(has("Sign in with Google"))
        assertTrue(has("@trailnotes", substring = true))
        onNodeWithText("Top").performClick()
        // "Most liked · Nov 2026" (the theme may write it upper-case), over This month and All time.
        waitFor("This month")
        onNodeWithText("All time").assertExists()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Most liked", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Following").performClick()
        waitFor("Follow travellers")
        // The Firestore queries the screens asked for: Latest by time, Top by likes.
        val asked = world.fs.queries.map { it.second.toString() }
        assertTrue(asked.any { "\"createdAt\"" in it && "DESCENDING" in it })
        assertTrue(asked.any { "\"likes\"" in it })
    }

    @Test fun aHeartReachesFirestoreAndWaitsOffline() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        app(world, c, *post(patagonia))
        waitFor("Patagonia on buses")
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Like").fetchSemanticsNodes().isNotEmpty() }
        tapDescription("Like")
        waitUntil(timeoutMillis = 15_000) { world.fs.docs.containsKey("posts/$patagonia/likes/$me") }
        waitUntil(timeoutMillis = 15_000) { world.fs.docs["posts/$patagonia"]?.get("likes") == 129L }
        waitUntil(timeoutMillis = 15_000) { c.likes.value[patagonia]?.let { it.liked && !it.pending } == true }
        // Offline the heart changes at once and the unlike waits on the phone.
        world.cloud.connected = false
        tapDescription("Like")
        waitUntil(timeoutMillis = 15_000) { runBlocking { (c.repo as FirestoreCommunity).waiting() } }
        assertTrue(world.fs.docs.containsKey("posts/$patagonia/likes/$me"))
        assertTrue(world.outbox.read(CommunityOutbox.KEY)!!.contains("unlike"))
        world.cloud.connected = true
        runBlocking { c.flush() }
        assertFalse(world.fs.docs.containsKey("posts/$patagonia/likes/$me"))
        assertEquals(128L, world.fs.docs["posts/$patagonia"]?.get("likes"))
        assertNull(world.outbox.read(CommunityOutbox.KEY))
    }

    @Test fun reportAPostSendsItAndOfflineItWaits() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        app(world, c, *post(patagonia))
        waitFor("Patagonia on buses")
        tapDescription("Post options")
        onNodeWithText("Report post").performClick()
        waitFor("Spam")
        assertTrue(has("Something wrong? Write to ", substring = true))
        onNodeWithText("Spam").performClick()
        waitFor("Report sent. Thank you.")
        val sent = world.fs.reports.single()
        assertEquals(me, sent["uid"])
        assertEquals(other, sent["target"])
        assertEquals(patagonia, sent["postId"])
        assertEquals("spam", sent["reason"])
        assertTrue(sent["at"] is FsTime)
        // Without a connection: kept, and sent with the next write.
        world.cloud.connected = false
        tapDescription("Post options")
        onNodeWithText("Report post").performClick()
        waitFor("Inappropriate")
        onNodeWithText("Inappropriate").performClick()
        waitFor("Saved. It goes out when you're online.")
        assertEquals(1, world.fs.reports.size)
        world.cloud.connected = true
        runBlocking { c.flush() }
        assertEquals(listOf("spam", "inappropriate"), world.fs.reports.map { it["reason"] })
    }

    @Test fun reportAProfile() = runDesktopComposeUiTest(width = 440, height = 2000) {
        val (world, c) = signedIn()
        app(world, c, *profile(other))
        waitFor("Long walks, cheap buses, small towns.")
        tapDescription("Profile options")
        onNodeWithText("Report @trailnotes").performClick()
        waitFor("Hate or harassment")
        onNodeWithText("Hate or harassment").performClick()
        waitFor("Report sent. Thank you.")
        val sent = world.fs.reports.single()
        assertNull(sent["postId"])
        assertEquals(other, sent["target"])
        assertEquals("hate", sent["reason"])
    }

    @Test fun blockingFromAPostHidesTheirPostsAndEndsTheFollows() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        world.fs.follow(me, CommunityPeople.ME_NAME, other, CommunityPeople.OTHER_NAME)
        world.fs.follow(other, CommunityPeople.OTHER_NAME, me, CommunityPeople.ME_NAME)
        app(world, c, *post(patagonia))
        waitFor("Patagonia on buses")
        tapDescription("Post options")
        onNodeWithText("Block @trailnotes").performClick()
        waitFor("Block @trailnotes?")
        onNodeWithText("You won't see their posts or profile, and neither of you follows the other. They aren't told.").assertExists()
        onNodeWithText("Block").performClick()
        // Back on the feed, without their posts (the trip and the album), with the others'.
        waitFor("Buenos Aires in forty seconds")
        waitUntil(timeoutMillis = 15_000) { !has("Patagonia on buses") && !has("Bariloche lakes") }
        waitUntil(timeoutMillis = 15_000) { world.fs.docs.containsKey("users/$me/blocked/$other") }
        assertEquals(CommunityPeople.OTHER_NAME, world.fs.docs["users/$me/blocked/$other"]?.get("username"))
        listOf("users/$me/following/$other", "users/$other/followers/$me", "users/$other/following/$me", "users/$me/followers/$other").forEach {
            assertFalse(world.fs.docs.containsKey(it), it)
        }
        assertEquals(listOf(other), c.blocked.value.map { it.uid })
    }

    @Test fun aBlockedProfileAndCommunityProfilesListUnblock() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        world.fs.block(me, other, CommunityPeople.OTHER_NAME)
        world.fs.block(me, CommunityPeople.THIRD, CommunityPeople.THIRD_NAME)
        runBlocking { c.refreshMe() }
        app(world, c, Place.Home(HomeTab.Community), Place.CommunityProfileEdit)
        waitFor("@harbour.days")
        onNodeWithText("Delete profile").assertExists()
        onAllNodesWithText("Unblock").onFirst().performClick()
        waitUntil(timeoutMillis = 15_000) { world.fs.docs.keys.count { it.startsWith("users/$me/blocked/") } == 1 }
        assertEquals(1, c.blocked.value.size)
    }

    @Test fun aBlockedProfileShowsUnblock() = runDesktopComposeUiTest(width = 440, height = 2000) {
        val (world, c) = signedIn()
        world.fs.block(me, other, CommunityPeople.OTHER_NAME)
        runBlocking { c.refreshMe() }
        app(world, c, *profile(other))
        waitFor("You blocked @trailnotes")
        onNodeWithText("Their posts and profile are hidden for you.").assertExists()
        assertFalse(has("Long walks, cheap buses, small towns."))
        onNodeWithText("Unblock").performClick()
        waitFor("Long walks, cheap buses, small towns.")
        waitUntil(timeoutMillis = 15_000) { !world.fs.docs.containsKey("users/$me/blocked/$other") }
    }

    @Test fun aPostGoneAndAPlanThatCantBeRead() {
        runDesktopComposeUiTest(width = 440, height = 2000) {
            val (world, c) = signedIn()
            app(world, c, *post("pGone0001"))
            waitFor("This post is gone")
            onNodeWithText("Its poster deleted it.").assertExists()
        }
        runDesktopComposeUiTest(width = 440, height = 2400) {
            val (world, c) = signedIn()
            world.fs.trip(patagonia, """{"v":9,"days":1}""")
            app(world, c, *post(patagonia))
            waitFor("This trip's plan can't be read. Update BoringTravel to see it.")
            assertFalse(has("Copy this trip"))
        }
    }

    @Test fun copyThisTripWithoutDatesAndAPlanOfIdeasAtOnce() {
        runDesktopComposeUiTest(width = 440, height = 2400) {
            val (world, c) = signedIn()
            val book = sampleBook()
            app(world, c, *post(patagonia), book = book)
            waitFor("The plan")
            onNodeWithText("Copies the plan into your trips, without the journal.").assertExists()
            onNodeWithText("Copy this trip").performClick()
            waitFor("Pick the first day")
            onNodeWithText("Without dates").performClick()
            waitFor("Copied to your trips")
            val copy = book.trips(SAMPLE_TODAY).single { it.title == "Patagonia on buses" }
            assertNull(copy.startDate)
            val items = book.items(copy.id)
            assertEquals(8, items.size)
            assertTrue(items.all { it.day == null })
            assertEquals("https://www.losglaciares.com/", items.single { it.title == "Perito Moreno glacier" }.url)
            // It opens on its Plan.
            waitFor("Add dates")
        }
        runDesktopComposeUiTest(width = 440, height = 2400) {
            val (world, c) = signedIn()
            val book = sampleBook()
            app(world, c, *post(CommunityWorld.IDEAS), book = book)
            waitFor("The plan")
            onNodeWithText("Copy this trip").performClick()
            waitFor("Copied to your trips")
            assertFalse(has("Pick the first day"))
            val copy = book.trips(SAMPLE_TODAY).single { it.title == "Iceland someday" }
            assertEquals(listOf("Blue Lagoon", "Ring road", "Hallgrímskirkja"), book.items(copy.id).map { it.title })
        }
    }

    @Test fun shareSendsThePostsLinkAndALinkOpensThePost() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        val shared = mutableListOf<String>()
        val links = AppLinks()
        app(world, c, Place.Home(HomeTab.Community), phone = Phone(share = { _, text -> shared += text; true }), links = links)
        waitFor("Buenos Aires in forty seconds")
        // A post's web link (a universal link once the site has the owner's file) opens it.
        assertTrue(links.open("https://justdataplease.com/apps/boringtravel/post/#$patagonia"))
        waitFor("Copy this trip")
        onAllNodesWithText("Share").onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { shared.isNotEmpty() }
        assertEquals("Patagonia on buses · @trailnotes on BoringTravel\n${CommunityLinks.url(patagonia)}", shared.single())
        // The app's own link (the website's way in) too.
        assertTrue(links.open("boringtravel://post/#${CommunityWorld.ALBUM}"))
        waitFor("Six photos of the lakes, one of them in the rain.")
        assertFalse(links.open("https://example.com/apps/boringtravel/post/#$patagonia"))
    }

    @Test fun signingOutOfTheAppSignsOutOfTheCommunity() = runDesktopComposeUiTest(width = 440, height = 1800) {
        val (world, c) = signedIn()
        app(world, c, Place.Home(HomeTab.Me))
        waitFor("Sign out")
        assertNotNull(c.user.value)
        onNodeWithText("Sign out").performClick()
        onAllNodesWithText("Sign out").onLast().performClick()
        waitUntil(timeoutMillis = 15_000) { c.user.value == null }
        assertNull(world.google.keys.get(com.justdataplease.boringtravel.community.CommunityAuth.SESSION))
    }

    @Test fun deleteAccountDeletesTheCommunityData() = runDesktopComposeUiTest(width = 440, height = 1800) {
        val (world, c) = signedIn()
        val fs = world.fs
        fs.like(patagonia, me)
        fs.block(me, CommunityPeople.THIRD, CommunityPeople.THIRD_NAME)
        fs.follow(me, CommunityPeople.ME_NAME, other, CommunityPeople.OTHER_NAME)
        app(world, c, Place.Home(HomeTab.Me))
        waitFor("Delete account")
        onNodeWithText("Delete account").performClick()
        onNodeWithText("Your community profile, username, posts, likes and follows are deleted too.", substring = true).assertExists()
        onNodeWithText("Delete").performClick()
        waitUntil(timeoutMillis = 20_000) { !world.settings.signedIn }
        assertFalse(fs.docs.containsKey("profiles/$me"))
        assertFalse(fs.docs.containsKey("usernames/${CommunityPeople.ME_NAME}"))
        assertFalse(fs.docs.containsKey("posts/${CommunityWorld.OWN}"))
        assertFalse(fs.docs.containsKey("posts/$patagonia/likes/$me"))
        assertEquals(128L, fs.docs["posts/$patagonia"]?.get("likes"))
        assertTrue(fs.docs.keys.none { it.startsWith("users/$me/") || it.endsWith("/$me") })
        // The community's Firebase account is deleted, and the community signed out.
        assertTrue(world.cloud.firebaseDeleted.isNotEmpty())
        assertNull(c.user.value)
        // Others' posts stay.
        assertTrue(fs.docs.containsKey("posts/$patagonia"))
    }

    @Test fun newTripOffersACommunityTrip() = runDesktopComposeUiTest(width = 440, height = 2000) {
        val (world, c) = signedIn()
        app(world, c, Place.NewTrip)
        waitFor("Start from a community trip")
        onNodeWithText("Trips other travellers posted. Copy one and set your dates.").assertExists()
        onNodeWithText("Start from a community trip").performClick()
        waitFor("Patagonia on buses")
        assertTrue(has("Latest"))
    }

    @Test fun offlineTheFeedShowsWhatWasSaved() {
        runDesktopComposeUiTest(width = 440, height = 2000) {
            val (world, c) = signedIn()
            runBlocking { c.repo.latest(null) }
            world.cloud.connected = false
            app(world, c, Place.Home(HomeTab.Community))
            waitFor("Offline · showing what was saved")
            waitFor("Patagonia on buses")
        }
        runDesktopComposeUiTest(width = 440, height = 2000) {
            val (world, c) = signedIn()
            world.cloud.connected = false
            app(world, c, Place.Home(HomeTab.Community))
            waitFor("You're offline")
            onNodeWithText("Connect to see the community.").assertExists()
            world.cloud.connected = true
            onNodeWithText("Try again").performClick()
            waitFor("Patagonia on buses")
        }
    }

    @Test fun aBuildWithoutTheCommunitySaysSo() = runDesktopComposeUiTest(width = 440, height = 2000) {
        val world = CommunityWorld()
        val c = world.community(firebase = false)
        assertFalse(c.available)
        app(world, c, Place.Home(HomeTab.Community))
        waitFor("Not in this build")
        onNodeWithText("The community needs the app from the App Store.").assertExists()
    }
}

package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Profiles, following and Community profile (round 24's screens, Android's own, read in; their tests round 25's) on
 * the fakes: someone's profile with its counts, posts grid and lists; Follow and Following moving the count at once and
 * reaching Firestore with both halves; Follow without a username; the empty texts; the traveller's own profile with Edit
 * profile and Delete profile; follow lists without blocked or deleted people; Community profile signed out, its first
 * profile after the community rules (a username suggested, checked live: taken, free), a rename that frees the old name
 * and renames the posts, the server's limits on a name and a bio; and Me's Community profile row.
 */
@OptIn(ExperimentalTestApi::class)
class CommunityProfilesOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    private val me = CommunityPeople.ME
    private val other = CommunityPeople.OTHER
    private val third = CommunityPeople.THIRD

    private fun signedIn(mine: Boolean = true, rules: Boolean = true): Pair<CommunityWorld, Community> {
        val world = CommunityWorld()
        world.signInWithGoogle()
        world.seed(mine = mine)
        world.pictures()
        world.settings.communityRulesAccepted = rules
        val c = world.community()
        runBlocking { c.signIn(quiet = true) }
        return world to c
    }

    private fun ComposeUiTest.app(world: CommunityWorld, c: Community, vararg at: Place, book: TripBook = sampleBook()) {
        setContent {
            CompositionLocalProvider(LocalPhone provides Phone()) {
                BoringTravelApp(book, world.settings, SAMPLE_TODAY, startAt = at.toList(), version = "1.22.0", google = world.account, community = c)
            }
        }
    }

    private fun ComposeUiTest.has(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitFor(text: String, substring: Boolean = false, timeout: Long = 15_000) =
        waitUntil(timeoutMillis = timeout) { has(text, substring) }

    private fun ComposeUiTest.tapDescription(description: String) =
        onAllNodesWithContentDescription(description).onFirst().performClick()

    private fun profile(uid: String) = arrayOf(Place.Home(HomeTab.Community), Place.CommunityProfile(uid))

    @Test fun someonesProfileHasItsCountsPostsAndLists() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        world.fs.follow(third, CommunityPeople.THIRD_NAME, other, CommunityPeople.OTHER_NAME)
        app(world, c, *profile(other))
        waitFor("@${CommunityPeople.OTHER_NAME}", substring = true)
        onNodeWithText("Trail notes").assertExists()
        onNodeWithText("Long walks, cheap buses, small towns.").assertExists()
        // posts · likes · followers · following; the posts grid in two columns.
        waitFor("posts")
        onNodeWithText("likes").assertExists()
        onNodeWithText("follower").assertExists()
        onNodeWithText("following").assertExists()
        waitFor("Patagonia on buses")
        onNodeWithText("Bariloche lakes").assertExists()
        assertTrue(has("135"))
        onNodeWithText("Follow").assertExists()
        // The followers open their list: avatar, @username, name.
        onNodeWithText("follower").performClick()
        waitFor("Followers")
        waitFor("@${CommunityPeople.THIRD_NAME}", substring = true)
    }

    @Test fun followAndUnfollowMoveTheCountAtOnceAndReachFirestore() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        app(world, c, *profile(other))
        waitFor("Follow")
        waitFor("followers")
        onNodeWithText("Follow").performClick()
        waitFor("Following")
        waitFor("follower")
        waitUntil(timeoutMillis = 15_000) { world.fs.docs.containsKey("users/$me/following/$other") }
        assertEquals(CommunityPeople.ME_NAME, world.fs.docs["users/$other/followers/$me"]?.get("username"))
        assertEquals(CommunityPeople.OTHER_NAME, world.fs.docs["users/$me/following/$other"]?.get("username"))
        onNodeWithText("Following").performClick()
        waitFor("Follow")
        waitUntil(timeoutMillis = 15_000) { !world.fs.docs.containsKey("users/$me/following/$other") && !world.fs.docs.containsKey("users/$other/followers/$me") }
    }

    @Test fun withoutAUsernameFollowAsksForOne() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn(mine = false)
        app(world, c, *profile(other))
        waitFor("Follow")
        onNodeWithText("Follow").performClick()
        waitFor("Pick a username to follow people.")
        assertFalse(world.fs.docs.keys.any { it.startsWith("users/$me/following/") })
    }

    @Test fun emptyProfilesSayWhyAndAnUnknownOneIsNotThere() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        world.fs.profile("u-quiet", "quiet.one", "Quiet one")
        app(world, c, *profile("u-quiet"))
        waitFor("No posts yet")
        onNodeWithText("@quiet.one hasn't posted yet.").assertExists()
    }

    @Test fun aProfileThatIsntThere() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        app(world, c, *profile("u-gone"))
        waitFor("No profile here")
        onNodeWithText("It was deleted.").assertExists()
    }

    @Test fun yourOwnProfileHasEditAndDelete() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        app(world, c, *profile(me))
        waitFor("@${CommunityPeople.ME_NAME}", substring = true)
        waitFor("Naxos by ferry")
        onNodeWithText("Edit profile").assertExists()
        assertFalse(has("Follow"))
        tapDescription("Profile options")
        waitFor("Delete profile")
        onAllNodesWithText("Delete profile").onLast().performClick()
        waitFor("Delete your profile?")
        onNodeWithText("Your username, profile, posts, likes and follows are deleted, and your posts' photos leave your Drive. This can't be undone.").assertExists()
        onAllNodesWithText("Delete").onLast().performClick()
        waitFor("Profile deleted", substring = true)
        waitUntil(timeoutMillis = 15_000) { !world.fs.docs.containsKey("profiles/$me") }
        assertFalse(world.fs.docs.containsKey("usernames/${CommunityPeople.ME_NAME}"))
        assertFalse(world.fs.docs.containsKey("posts/${CommunityWorld.OWN}"))
        assertNull(c.profile.value)
    }

    @Test fun followListsLeaveOutBlockedAndDeletedPeople() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        world.fs.follow(third, CommunityPeople.THIRD_NAME, other, CommunityPeople.OTHER_NAME)
        world.fs.follow("u-gone", "gone.traveller", other, CommunityPeople.OTHER_NAME)
        world.fs.follow("u-kept", "kept.traveller", other, CommunityPeople.OTHER_NAME)
        world.fs.profile("u-kept", "kept.traveller", "Kept")
        world.fs.block(me, third, CommunityPeople.THIRD_NAME)
        app(world, c, Place.Home(HomeTab.Community), Place.CommunityFollows(other, followers = true))
        waitFor("@kept.traveller", substring = true)
        assertFalse(has("@${CommunityPeople.THIRD_NAME}", substring = true))
        assertFalse(has("@gone.traveller", substring = true))
        // The other list: nobody yet.
        onNodeWithText("Following").performClick()
        waitFor("Not following anyone")
        onNodeWithText("The people they follow show here.").assertExists()
    }

    @Test fun signedOutCommunityProfileSaysWhatAProfileIs() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val world = CommunityWorld()
        world.seed()
        val c = world.community()
        app(world, c, Place.Home(HomeTab.Community), Place.CommunityProfileEdit)
        waitFor("Your profile is how others see you in the community: a username, a name, a few words and a picture.")
        onNodeWithText("Sign in with Google").assertExists()
    }

    @Test fun aFirstProfileAfterTheRulesWithAUsernameCheckedLive() = runDesktopComposeUiTest(width = 440, height = 2600) {
        val (world, c) = signedIn(mine = false, rules = false)
        app(world, c, Place.Home(HomeTab.Community), Place.CommunityProfileEdit)
        // Suggested from the Google account's name.
        waitFor("Make my profile")
        waitFor(" is free", substring = true)
        val field = onAllNodes(hasSetTextAction()).onFirst()
        field.performTextReplacement(CommunityPeople.OTHER_NAME)
        waitFor("@${CommunityPeople.OTHER_NAME} is taken. Try another.")
        field.performTextReplacement("admin")
        waitUntil(timeoutMillis = 10_000) { !has("@admin is free") }
        field.performTextReplacement("slow.ferries")
        waitFor("@slow.ferries is free")
        onAllNodes(hasSetTextAction())[1].performTextReplacement("Slow ferries")
        onNodeWithText("Others see your username, name, bio and picture.").performScrollTo().assertExists()
        onNodeWithText("Make my profile").performScrollTo().performClick()
        // The first profile: the community rules first.
        waitFor("Community rules")
        onNodeWithText("Agree").performClick()
        waitFor("Profile made")
        assertEquals("slow.ferries", world.fs.docs["profiles/$me"]?.get("username"))
        assertEquals("Slow ferries", world.fs.docs["profiles/$me"]?.get("name"))
        assertEquals(me, world.fs.docs["usernames/slow.ferries"]?.get("uid"))
        assertTrue(world.settings.communityRulesAccepted)
    }

    @Test fun aNewUsernameFreesTheOldOneAndRenamesThePosts() = runDesktopComposeUiTest(width = 440, height = 2600) {
        val (world, c) = signedIn()
        app(world, c, Place.Home(HomeTab.Community), Place.CommunityProfileEdit)
        waitFor("This is your username")
        onAllNodes(hasSetTextAction() and hasText(CommunityPeople.ME_NAME)).onFirst().performTextReplacement("islands.slowly")
        waitFor("@islands.slowly is free")
        onNodeWithText("Save").performScrollTo().performClick()
        waitFor("Saved")
        waitUntil(timeoutMillis = 15_000) { world.fs.docs["posts/${CommunityWorld.OWN}"]?.get("username") == "islands.slowly" }
        assertFalse(world.fs.docs.containsKey("usernames/${CommunityPeople.ME_NAME}"))
        assertEquals(me, world.fs.docs["usernames/islands.slowly"]?.get("uid"))
    }

    @Test fun theServerHoldsANameOf40AndABioOf150() = runBlocking<Unit> {
        val (world, c) = signedIn()
        val repo = c.repo
        // The app cuts them to the limits; a write past them is refused by the rules.
        val saved = repo.saveProfile(CommunityPeople.ME_NAME, "N".repeat(60), "B".repeat(200), null)
        assertEquals(40, saved.name.length)
        assertEquals(150, saved.bio.length)
        assertEquals(40, (world.fs.docs["profiles/$me"]?.get("name") as String).length)
    }

    @Test fun mesCommunityProfileRowOpensIt() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val (world, c) = signedIn()
        app(world, c, Place.Home(HomeTab.Me))
        waitFor("Community profile")
        waitFor("@${CommunityPeople.ME_NAME}")
        onNodeWithText("Community profile").performClick()
        waitFor("This is your username")
    }
}

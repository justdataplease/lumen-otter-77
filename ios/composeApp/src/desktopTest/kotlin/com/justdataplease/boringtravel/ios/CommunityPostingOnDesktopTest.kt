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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.community.SharedTrips
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 25's posting driven on the desktop as a traveller would, on the fakes (Google, Drive, Firebase Auth and a
 * Firestore holding the owner's rules, in memory): Community's + and the trip picker, the trip's ⋮ › Post to community,
 * what is still needed (sign in, a username, Drive), a trip post with a drawn cover (no Drive) after the community rules,
 * an album into the poster's Drive (a link-shared BoringTravel/Community folder, photos 1600 px without their tags), a
 * clip with its picture from 40 % in, a failure carried on by Try again without posting twice, Stop and Back deleting
 * what went to Drive, demo and deleted trips, the trip's ⋮ › Community post (Last updated, The trip changed since then,
 * Update now, Stop updating it), and Delete post with its folder. Nothing reaches the network.
 */
@OptIn(ExperimentalTestApi::class)
class CommunityPostingOnDesktopTest {
    init {
        runBlocking {
            BtFontFiles.load()
            CountryOutlines.load()
        }
    }

    private val me = CommunityPeople.ME

    /** Signed in with Google on the fakes (Drive connected), with a profile of their own ([mine]) and others' posts. */
    private fun signedIn(trip: PostingTrip, mine: Boolean = true, rules: Boolean = true, net: ((CommunityWorld) -> Net)? = null): Pair<CommunityWorld, Community> {
        // The account's Drive and the app's photos and clips are one set of files, as on the phone.
        val world = CommunityWorld(files = trip.kit.files as MemoryMediaFiles)
        world.signInWithGoogle()
        world.seed(mine = mine)
        world.pictures()
        world.settings.communityRulesAccepted = rules
        val c = if (net == null) world.community() else world.community(net = net(world))
        runBlocking { c.signIn(quiet = true) }
        return world to c
    }

    private fun ComposeUiTest.app(world: CommunityWorld, c: Community?, trip: PostingTrip, vararg at: Place) {
        setContent {
            CompositionLocalProvider(LocalPhone provides Phone()) {
                BoringTravelApp(
                    trip.book, world.settings, SAMPLE_TODAY, startAt = at.toList(), version = "1.22.0", media = trip.kit,
                    google = world.account, community = c,
                )
            }
        }
    }

    private fun compose(trip: PostingTrip, kind: String = "trip") =
        arrayOf(Place.Trip(trip.tripId, TripTab.Plan), Place.Compose(CommunityCompose(trip.tripId, kind)))

    private fun ComposeUiTest.has(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitFor(text: String, substring: Boolean = false, timeout: Long = 20_000) =
        waitUntil(timeoutMillis = timeout) { has(text, substring) }

    private fun ComposeUiTest.tapDescription(description: String) =
        onAllNodesWithContentDescription(description).onFirst().performClick()

    /** The posts of the traveller's own in Firestore titled [title]. */
    private fun CommunityWorld.posts(title: String) = fs.docs.filter { (k, v) -> k.count { it == '/' } == 1 && k.startsWith("posts/") && v["uid"] == me && v["title"] == title }

    /** Drive's folders under BoringTravel/Community. */
    private fun CommunityWorld.postFolders(): List<FakeDriveItem> {
        val community = cloud.items.values.firstOrNull { it.isFolder && it.name == "Community" } ?: return emptyList()
        return cloud.items.values.filter { it.isFolder && community.id in it.parents }
    }

    @Test fun plusPicksOneOfYourTripsAndOpensTheComposer() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip)
        app(world, c, trip, Place.Home(HomeTab.Community))
        waitFor("Patagonia on buses")
        tapDescription("Post to community")
        waitFor("Post a trip")
        onNodeWithText("Its plan, a clip or photos. You choose next.").assertExists()
        onNodeWithText(trip.title).performClick()
        // The composer: what to share, the trip's plan, Include notes, the cover, title, caption, the preview.
        waitFor("WHAT TO SHARE")
        onNodeWithText("Post to community").assertExists()
        listOf("Trip", "Clip", "Album").forEach { onNodeWithText(it).assertExists() }
        waitFor("5 days · 7 items in the plan")
        onNodeWithText("Places, times and links go. Plan notes, bookings and your route don't.").assertExists()
        onNodeWithText("Include notes").assertExists()
        onNodeWithText("Changed the trip later? Its ⋮ › Community post › Update now updates this post.").performScrollTo().assertExists()
        assertTrue(has("Photos go up smaller and without their location, to BoringTravel/Community in your Google Drive.", substring = true))
        // The preview is the feed's card: the trip's title, @username, "5 days · … km", and Greece's flag.
        assertTrue(has("@${CommunityPeople.ME_NAME}", substring = true))
    }

    @Test fun aTripPostWithADrawnCoverNeedsNoDriveAndAsksForTheRulesFirst() = runDesktopComposeUiTest(width = 440, height = 2600) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip, rules = false)
        world.settings.driveConnected = false
        app(world, c, trip, *compose(trip))
        waitFor("WHAT TO SHARE")
        // The cover is a photo, which goes to Drive: Drive is asked for first.
        waitFor("Connect Google Drive")
        onNodeWithText("Your photos and clips stay in your Drive, in a folder anyone with its link can view.").assertExists()
        // The drawn picture as the cover: nothing goes to Drive, nothing more is needed.
        onNodeWithText("Drawn").performClick()
        waitUntil(timeoutMillis = 10_000) { !has("Connect Google Drive") }
        assertFalse(has("Photos go up smaller", substring = true))
        onNodeWithText("Post").performScrollTo().performClick()
        // The first post: the community rules, then it goes.
        waitFor("Community rules")
        listOf("Be kind.", "Post only your own photos and clips.", "No nudity, hate, violence or spam.").forEach { onNodeWithText(it).assertExists() }
        onNodeWithText("Agree").performClick()
        waitUntil(timeoutMillis = 20_000) { world.posts(trip.title).isNotEmpty() }
        assertTrue(world.settings.communityRulesAccepted)
        val (path, post) = world.posts(trip.title).entries.single()
        assertEquals("trip", post["kind"])
        assertEquals(CommunityPeople.ME_NAME, post["username"])
        assertNull(post["coverFileId"])
        assertEquals(emptyList<String>(), post["photoFileIds"])
        assertNull(post["folderId"])
        assertEquals(true, post["hasTrip"])
        assertEquals(listOf("GR"), post["countries"])
        assertEquals(5L, post["days"])
        assertTrue((post["km"] as Long) > 100)
        assertEquals("2026-10", post["startMonth"])
        assertEquals(PostText.monthKey(nowMs()), post["month"])
        assertEquals(0L, post["likes"])
        // The plan went with it (no plan notes, no route); nothing went to Drive.
        val shared = SharedTrips.decode(world.fs.docs["$path/content/trip"]?.get("trip") as String)!!
        assertEquals(setOf("Ferry from Piraeus", "Parikia harbour", "Dinner in Naoussa", "Ferry to Naxos", "Portara at sunset", "Mount Zas", "Kitron tasting in Halki"), shared.items.map { it.title }.toSet())
        assertTrue(shared.notes.isEmpty())
        assertTrue(world.postFolders().isEmpty())
        // The new post opens in place of the composer.
        waitUntil(timeoutMillis = 20_000) { !has("WHAT TO SHARE") && has("Posted", substring = true) }
        // This phone keeps the trip linked to its post (never in a backup).
        val link = runBlocking { c.postLinks.of(trip.tripId) }
        assertEquals(path.removePrefix("posts/"), link?.postId)
        assertTrue(trip.kit.files.read("community/post-links.json") != null)
    }

    @Test fun anAlbumGoesToALinkSharedFolderInTheDriveAsSmallerPhotosWithoutTags() = runDesktopComposeUiTest(width = 440, height = 3000) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip)
        app(world, c, trip, *compose(trip, "album"))
        waitFor("Pick up to 30")
        // Two photos, picked in order: their numbers show.
        val tiles = onAllNodesWithContentDescription("Photo")
        tiles[3].performClick()
        tiles[0].performClick()
        waitFor("2 picked")
        onNodeWithText("Post").performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { world.posts(trip.title).isNotEmpty() }
        val post = world.posts(trip.title).values.single()
        assertEquals("album", post["kind"])
        // BoringTravel/Community/"Paros and Naxos <date>", anyone with the link may view it; photo-1.jpg and photo-2.jpg.
        val folder = world.postFolders().single()
        assertTrue(folder.name.startsWith("Paros and Naxos 20"))
        assertTrue(folder.anyone)
        assertEquals(folder.id, post["folderId"])
        val photos = world.cloud.items.values.filter { folder.id in it.parents }.sortedBy { it.name }
        assertEquals(listOf("photo-1.jpg", "photo-2.jpg"), photos.map { it.name })
        // In the order picked: photo-1.jpg is the first photo picked, and the cover.
        assertEquals(photos.map { it.id }, post["photoFileIds"])
        assertEquals((post["photoFileIds"] as List<*>).first(), post["coverFileId"])
        for (p in photos) {
            assertEquals("image/jpeg", p.mimeType)
            val (w, h) = Pictures.size(p.content)!!
            // 2400 × 1800 photos go up at 1600 px on the long side, as plain JPEGs (no EXIF block).
            assertEquals(1600, maxOf(w, h))
            assertFalse(p.content.decodeToString(0, minOf(64, p.content.size)).contains("Exif"))
        }
        // The posted picture is read by link like anyone else's (the feed's card and the post page show it).
        waitFor(trip.title)
    }

    @Test fun aClipGoesUpWithItsPictureFromFortyPercentIn() = runDesktopComposeUiTest(width = 440, height = 2600) {
        val trip = postingTrip(clip = true)
        val (world, c) = signedIn(trip)
        app(world, c, trip, *compose(trip, "clip"))
        waitFor("Your clip")
        onNodeWithText("Post").performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { world.posts(trip.title).isNotEmpty() }
        val post = world.posts(trip.title).values.single()
        assertEquals("clip", post["kind"])
        val folder = world.postFolders().single()
        val files = world.cloud.items.values.filter { folder.id in it.parents }.associateBy { it.name }
        assertEquals(setOf("photo-1.jpg", "clip.mp4"), files.keys)
        assertEquals(files.getValue("clip.mp4").id, post["clipFileId"])
        assertEquals(files.getValue("photo-1.jpg").id, post["coverFileId"])
        assertEquals("video/mp4", files.getValue("clip.mp4").mimeType)
        // The clip's own picture at 40 % (PostingFrames), as AVAssetImageGenerator gives it on iPhone.
        assertEquals(960 to 540, Pictures.size(files.getValue("photo-1.jpg").content))
    }

    @Test fun whatIsStillNeededIsSaidInOrder() = runDesktopComposeUiTest(width = 440, height = 2400) {
        // Signed out: the sign-in card first.
        val trip = postingTrip()
        val world = CommunityWorld(files = trip.kit.files as MemoryMediaFiles)
        world.seed()
        val c = world.community()
        app(world, c, trip, *compose(trip))
        waitFor("Sign in with Google to post")
        assertFalse(onNodeWithText("Post").isEnabled())
    }

    @Test fun withoutAUsernameItAsksForOne() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip, mine = false)
        app(world, c, trip, *compose(trip))
        waitFor("Pick a username first")
        onNodeWithText("Others see your posts under it.").assertExists()
        assertFalse(onNodeWithText("Post").isEnabled())
        onNodeWithText("Make my profile").performClick()
        waitFor("Community profile")
        assertTrue(world.posts(trip.title).isEmpty())
    }

    @Test fun aFailedUploadCarriesOnWithTryAgainAndNeverPostsTwice() = runDesktopComposeUiTest(width = 440, height = 3000) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip)
        // The second photo's upload fails while [failing].
        var failing = true
        var uploads = 0
        world.cloud.route = { call ->
            if (call.path == "/upload/drive/v3/files" && call.method == "POST" && failing && ++uploads >= 2) throw NetException("No connection") else null
        }
        app(world, c, trip, *compose(trip, "album"))
        waitFor("Pick up to 30")
        val tiles = onAllNodesWithContentDescription("Photo")
        tiles[0].performClick()
        tiles[1].performClick()
        tiles[2].performClick()
        waitFor("3 picked")
        onNodeWithText("Post").performScrollTo().performClick()
        waitFor("Try again")
        onNodeWithText("Stop").assertExists()
        assertTrue(world.posts(trip.title).isEmpty())
        failing = false
        onNodeWithText("Try again").performClick()
        waitUntil(timeoutMillis = 30_000) { world.posts(trip.title).isNotEmpty() }
        // One folder, three photos, one post: Try again carried on where it stopped.
        assertEquals(1, world.postFolders().size)
        assertEquals(3, world.cloud.items.values.count { world.postFolders().single().id in it.parents })
        assertEquals(1, world.posts(trip.title).size)
        assertEquals(3, (world.posts(trip.title).values.single()["photoFileIds"] as List<*>).size)
    }

    @Test fun stopAfterAFailureDeletesWhatWentToDrive() = runDesktopComposeUiTest(width = 440, height = 3000) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip)
        var uploads = 0
        world.cloud.route = { call -> if (call.path == "/upload/drive/v3/files" && call.method == "POST" && ++uploads >= 2) throw NetException("No connection") else null }
        app(world, c, trip, *compose(trip, "album"))
        waitFor("Pick up to 30")
        val tiles = onAllNodesWithContentDescription("Photo")
        tiles[0].performClick()
        tiles[1].performClick()
        onNodeWithText("Post").performScrollTo().performClick()
        waitFor("Try again")
        assertEquals(1, world.postFolders().size)
        onNodeWithText("Stop").performClick()
        // The folder and the photo in it leave the Drive; nothing was posted.
        waitUntil(timeoutMillis = 15_000) { world.postFolders().isEmpty() }
        assertTrue(world.cloud.items.values.none { it.name == "photo-1.jpg" })
        assertTrue(world.posts(trip.title).isEmpty())
    }

    @Test fun backWhilePostingAsksAndStopsAndDeletesWhatWentToDrive() = runDesktopComposeUiTest(width = 440, height = 3000) {
        val trip = postingTrip()
        // Firestore's write of the post waits: the photos are in Drive, "Posting" shows.
        val (world, c) = signedIn(trip) { w ->
            CommunityWorld.HangingNet(w.cloud) { it.url.endsWith(":commit") && "photoFileIds" in (it.body?.decodeToString() ?: "") }
        }
        app(world, c, trip, *compose(trip, "album"))
        waitFor("Pick up to 30")
        onAllNodesWithContentDescription("Photo")[0].performClick()
        onNodeWithText("Post").performScrollTo().performClick()
        waitFor("Posting")
        onNodeWithText("Keep this screen open until it's posted.").assertExists()
        assertEquals(1, world.postFolders().size)
        tapDescription("Back")
        waitFor("Stop posting?")
        onNodeWithText("What went to your Drive so far is deleted.").assertExists()
        onAllNodesWithText("Stop").onLast().performClick()
        waitUntil(timeoutMillis = 15_000) { world.postFolders().isEmpty() }
        assertTrue(world.posts(trip.title).isEmpty())
        // Back on the trip.
        waitUntil(timeoutMillis = 15_000) { !has("WHAT TO SHARE") && onAllNodesWithContentDescription("Trip options").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun demoAndDeletedTripsCantBePosted() = runDesktopComposeUiTest(width = 440, height = 2000) {
        val trip = postingTrip()
        // An example trip (its id says so, as the demo's do).
        val demo = trip.book.trip(trip.tripId)!!.copy(id = "demo-paros")
        trip.book.putRows(trips = listOf(demo))
        val (world, c) = signedIn(trip)
        app(world, c, trip, Place.Home(HomeTab.Community), Place.Compose(CommunityCompose(demo.id)))
        waitFor("Demo trips can't be posted")
        onNodeWithText("Post a trip of your own. Turn off Demo mode in Me to see yours.").assertExists()
    }

    @Test fun aDeletedTripIsGone() = runDesktopComposeUiTest(width = 440, height = 2000) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip)
        app(world, c, trip, Place.Home(HomeTab.Community), Place.Compose(CommunityCompose("no-such-trip")))
        waitFor("This trip is gone")
        onNodeWithText("It was deleted from this phone.").assertExists()
    }

    @Test fun theTripsCommunityPostIsUpdatedByHandOnly() = runDesktopComposeUiTest(width = 440, height = 2600) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip)
        world.settings.driveConnected = false
        app(world, c, trip, *compose(trip))
        waitFor("WHAT TO SHARE")
        onNodeWithText("Drawn").performClick()
        onNodeWithText("Post").performScrollTo().performClick()
        waitUntil(timeoutMillis = 20_000) { world.posts(trip.title).isNotEmpty() }
        val (path, _) = world.posts(trip.title).entries.single()
        // Back from the new post: the trip, whose ⋮ now has Community post.
        waitUntil(timeoutMillis = 15_000) { !has("WHAT TO SHARE") && onAllNodesWithContentDescription("Post options").fetchSemanticsNodes().isNotEmpty() }
        tapDescription("Back")
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Trip options").fetchSemanticsNodes().isNotEmpty() }
        tapDescription("Trip options")
        waitFor("Community post")
        onNodeWithText("Post to community").assertExists()
        onNodeWithText("Community post").performClick()
        waitFor("Last updated just now")
        waitFor("The post shows the trip as it is now.")
        // Nothing new: nothing is asked of the network.
        val commits = world.fs.commits.size
        onNodeWithText("Update now").performClick()
        waitFor("Already up to date.")
        assertEquals(commits, world.fs.commits.size)
        // A new place in the plan while the post gathers likes: Update now sends the plan; the likes and the words stay.
        world.fs.docs[path]!!["likes"] = 4L
        trip.book.addItem(trip.tripId, "Old market in Chora", trip.book.trip(trip.tripId)!!.startDate!!.plusDays(2), PlanKind.Place)
        onNodeWithText("Update now").performClick()
        waitFor("Updated.")
        val shared = SharedTrips.decode(world.fs.docs["$path/content/trip"]?.get("trip") as String)!!
        assertTrue(shared.items.any { it.title == "Old market in Chora" })
        assertEquals(4L, world.fs.docs[path]!!["likes"])
        assertEquals(trip.title, world.fs.docs[path]!!["title"])
        onNodeWithText("Nothing goes up by itself.", substring = true).assertExists()
        // Stop updating it: the post stays, the trip isn't linked to it any more.
        onNodeWithText("Stop updating it").performClick()
        waitUntil(timeoutMillis = 10_000) { runBlocking { c.postLinks.of(trip.tripId) } == null }
        assertTrue(world.fs.docs.containsKey(path))
        tapDescription("Trip options")
        waitFor("Post to community")
        assertFalse(has("Community post"))
    }

    @Test fun deletingYourPostTakesItsFolderOutOfYourDrive() = runDesktopComposeUiTest(width = 440, height = 3000) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip)
        app(world, c, trip, *compose(trip, "album"))
        waitFor("Pick up to 30")
        onAllNodesWithContentDescription("Photo")[0].performClick()
        onNodeWithText("Post").performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { world.posts(trip.title).isNotEmpty() }
        assertEquals(1, world.postFolders().size)
        // The post page: ⋮ › Delete post.
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Post options").fetchSemanticsNodes().isNotEmpty() }
        tapDescription("Post options")
        waitFor("Delete post")
        onNodeWithText("Delete post").performClick()
        waitFor("Delete this post?")
        onNodeWithText("It leaves the community, and its photos leave your Drive.").assertExists()
        onAllNodesWithText("Delete").onLast().performClick()
        waitUntil(timeoutMillis = 15_000) { world.posts(trip.title).isEmpty() && world.postFolders().isEmpty() }
    }

    @Test fun withoutDriveADeletedPostLeavesItsPhotosAndSaysSo() = runDesktopComposeUiTest(width = 440, height = 3000) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip)
        app(world, c, trip, *compose(trip, "album"))
        waitFor("Pick up to 30")
        onAllNodesWithContentDescription("Photo")[0].performClick()
        onNodeWithText("Post").performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { world.posts(trip.title).isNotEmpty() }
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription("Post options").fetchSemanticsNodes().isNotEmpty() }
        world.settings.driveConnected = false
        tapDescription("Post options")
        waitFor("Delete post")
        onNodeWithText("Delete post").performClick()
        waitFor("Delete this post?")
        onAllNodesWithText("Delete").onLast().performClick()
        waitFor("Post deleted. Its photos stay in your Drive, in BoringTravel/Community.")
        assertTrue(world.posts(trip.title).isEmpty())
        assertEquals(1, world.postFolders().size)
    }

    @Test fun offlineDeletingFailsBeforeAnythingGoes() = runBlocking<Unit> {
        val trip = postingTrip()
        val (world, c) = signedIn(trip)
        val own = c.repo.post(CommunityWorld.OWN)
        assertNotNull(own)
        world.cloud.connected = false
        val failed = runCatching { c.deletePost(own) }.exceptionOrNull()
        assertNotNull(failed)
        world.cloud.connected = true
        assertTrue(world.fs.docs.containsKey("posts/${CommunityWorld.OWN}"))
    }

    @Test fun postLinksStayOnThisPhoneAndOutOfBackups() = runBlocking<Unit> {
        val trip = postingTrip()
        val (_, c) = signedIn(trip)
        c.usePhone(trip.kit.files, trip.kit.frames, { trip.book.media(it) })
        c.postLinks.put(com.justdataplease.boringtravel.community.PostLink(trip.tripId, "p1", "Paros and Naxos"))
        // The link is a file of this phone's; a backup file has none of it.
        assertNotNull(trip.kit.files.read("community/post-links.json"))
        assertFalse(trip.book.export().contains("p1"))
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.isEnabled(): Boolean =
        !fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
}

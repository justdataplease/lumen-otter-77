package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.community.PostLink
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.runBlocking
import java.io.File

/*
 * Round 25's pictures, on the fakes (Google, Drive, Firebase Auth, Firestore): the trip's ⋮ with Post to community and
 * Community post; the composer for a trip (in two looks), an album with photos picked, a clip; what is still needed
 * (sign in, a username, Drive); the community rules before the first post; uploading, a failure with Try again and Stop,
 * "Stop posting?"; a demo trip; the trip's Community post sheet; a post of one's own with Delete post asked; profiles
 * (one's own ⋮, Delete your profile?, someone followed), Community profile for a first profile (a username suggested,
 * one taken) and Me's Community profile row. `gradlew :composeApp:screenshots -PshotsOnly=community` draws them too.
 */
fun posting(out: File, drive: (name: String, tall: Float, frames: Int, content: @Composable () -> Unit, steps: CommunityDriver.() -> Unit) -> Unit) {
    fun signedIn(trip: PostingTrip, look: Look = Look.Default, mine: Boolean = true, rules: Boolean = true, connected: Boolean = true, signIn: Boolean = true, net: ((CommunityWorld) -> Net)? = null): Pair<CommunityWorld, Community> {
        val world = CommunityWorld(settings = AppSettings.ready(look), files = trip.kit.files as MemoryMediaFiles)
        if (signIn) world.signInWithGoogle()
        world.seed(mine = mine)
        world.pictures()
        world.settings.communityRulesAccepted = rules
        if (!connected) world.settings.driveConnected = false
        val c = if (net == null) world.community() else world.community(net = net(world))
        if (signIn) runBlocking { c.signIn(quiet = true) }
        return world to c
    }

    @Composable
    fun App(world: CommunityWorld, c: Community, trip: PostingTrip, vararg at: Place) {
        BoringTravelApp(
            trip.book, world.settings, SAMPLE_TODAY, PHONE_FRAME, startAt = at.toList(), version = PREVIEW_VERSION, media = trip.kit,
            google = world.account, community = c,
        )
    }

    fun compose(trip: PostingTrip, kind: String = "trip") = arrayOf(Place.Trip(trip.tripId, TripTab.Plan), Place.Compose(CommunityCompose(trip.tripId, kind)))
    fun shot(name: String, tall: Float = 1f, frames: Int = 50, content: @Composable () -> Unit) = drive(name, tall, frames, content) {}

    // ------------------------------------------------------------------ the trip's ⋮
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        runBlocking {
            c.usePhone(trip.kit.files, trip.kit.frames, { trip.book.media(it) })
            c.postLinks.put(PostLink(trip.tripId, CommunityWorld.OWN, trip.title, syncedAt = 1_795_000_000_000L - 5 * 60_000L))
        }
        drive("trip-menu-community", 1f, 40, { App(world, c, trip, Place.Trip(trip.tripId, TripTab.Plan)) }) {
            tapDescription("Trip options")
            frames(12)
        }
    }

    // ------------------------------------------------------------------ the composer
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val trip = postingTrip()
        val (world, c) = signedIn(trip, look)
        shot("compose-trip-${look.id}", tall = 2.2f) { App(world, c, trip, *compose(trip)) }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        drive("compose-trip-notes", 2.6f, 50, { App(world, c, trip, *compose(trip)) }) {
            tapText("Include notes")
            frames(20)
        }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        drive("compose-album", 2.2f, 50, { App(world, c, trip, *compose(trip, "album")) }) {
            tapDescription("Photo", 3)
            frames(4)
            tapDescription("Photo", 0)
            frames(4)
            tapDescription("Photo", 1)
            frames(20)
        }
    }
    postingTrip(clip = true).let { trip ->
        val (world, c) = signedIn(trip)
        shot("compose-clip", tall = 2f) { App(world, c, trip, *compose(trip, "clip")) }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip, signIn = false)
        shot("compose-sign-in") { App(world, c, trip, *compose(trip)) }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip, mine = false)
        shot("compose-username") { App(world, c, trip, *compose(trip)) }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip, connected = false)
        shot("compose-drive") { App(world, c, trip, *compose(trip)) }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip, rules = false)
        drive("compose-rules", 2.2f, 50, { App(world, c, trip, *compose(trip)) }) {
            tapText("Post")
            frames(20)
        }
    }
    // Uploading: Firestore's write of the post waits (the photos are in Drive); then Back asks.
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip) { w -> CommunityWorld.HangingNet(w.cloud) { it.url.endsWith(":commit") && "photoFileIds" in (it.body?.decodeToString() ?: "") } }
        drive("compose-uploading", 2.2f, 50, { App(world, c, trip, *compose(trip, "album")) }) {
            tapDescription("Photo", 0)
            frames(4)
            tapText("Post")
            frames(40)
        }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip) { w -> CommunityWorld.HangingNet(w.cloud) { it.url.endsWith(":commit") && "photoFileIds" in (it.body?.decodeToString() ?: "") } }
        drive("compose-stop-ask", 2.2f, 50, { App(world, c, trip, *compose(trip, "album")) }) {
            tapDescription("Photo", 0)
            frames(4)
            tapText("Post")
            frames(30)
            tapDescription("Back")
            frames(15)
        }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        var uploads = 0
        world.cloud.route = { call -> if (call.path == "/upload/drive/v3/files" && call.method == "POST" && ++uploads >= 2) throw NetException("No connection") else null }
        drive("compose-failed", 2.2f, 50, { App(world, c, trip, *compose(trip, "album")) }) {
            tapDescription("Photo", 0)
            frames(4)
            tapDescription("Photo", 1)
            frames(4)
            tapText("Post")
            frames(40)
        }
    }
    postingTrip().let { trip ->
        val demo = trip.book.trip(trip.tripId)!!.copy(id = "demo-paros")
        trip.book.putRows(trips = listOf(demo))
        val (world, c) = signedIn(trip)
        shot("compose-demo") { App(world, c, trip, Place.Home(HomeTab.Community), Place.Compose(CommunityCompose(demo.id))) }
    }

    // ------------------------------------------------------------------ the trip's Community post, a post of one's own
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        runBlocking {
            c.usePhone(trip.kit.files, trip.kit.frames, { trip.book.media(it) })
            // Linked five minutes before the pictures' clock, never sent with this plan: the trip changed since.
            c.postLinks.put(PostLink(trip.tripId, CommunityWorld.OWN, trip.title, syncedAt = com.justdataplease.boringtravel.time.nowMs() - 5 * 60_000L, sent = "old"))
        }
        drive("post-sync-sheet", 1f, 40, { App(world, c, trip, Place.Trip(trip.tripId, TripTab.Plan)) }) {
            tapDescription("Trip options")
            frames(12)
            tapText("Community post")
            frames(30)
        }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        drive("post-delete-ask", 1f, 50, { App(world, c, trip, Place.Home(HomeTab.Community), Place.CommunityPost(CommunityWorld.OWN)) }) {
            tapDescription("Post options")
            frames(10)
            tapText("Delete post")
            frames(15)
        }
    }

    // ------------------------------------------------------------------ profiles and Community profile
    fun profile(uid: String) = arrayOf(Place.Home(HomeTab.Community), Place.CommunityProfile(uid))
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        drive("profile-mine-menu", 1f, 50, { App(world, c, trip, *profile(CommunityPeople.ME)) }) {
            tapDescription("Profile options")
            frames(10)
        }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        drive("profile-delete-ask", 1f, 50, { App(world, c, trip, *profile(CommunityPeople.ME)) }) {
            tapDescription("Profile options")
            frames(10)
            tapText("Delete profile")
            frames(15)
        }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        world.fs.follow(CommunityPeople.ME, CommunityPeople.ME_NAME, CommunityPeople.OTHER, CommunityPeople.OTHER_NAME)
        shot("profile-following") { App(world, c, trip, *profile(CommunityPeople.OTHER)) }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip, mine = false)
        shot("community-profile-new", tall = 1.5f) { App(world, c, trip, Place.Home(HomeTab.Community), Place.CommunityProfileEdit) }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip, mine = false, rules = false)
        drive("community-profile-rules", 1f, 50, { App(world, c, trip, Place.Home(HomeTab.Community), Place.CommunityProfileEdit) }) {
            tapText("Make my profile")
            frames(20)
        }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip, signIn = false)
        shot("community-profile-signed-out") { App(world, c, trip, Place.Home(HomeTab.Community), Place.CommunityProfileEdit) }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        world.fs.follow(CommunityPeople.THIRD, CommunityPeople.THIRD_NAME, CommunityPeople.ME, CommunityPeople.ME_NAME)
        shot("follow-list-mine-following") { App(world, c, trip, Place.Home(HomeTab.Community), Place.CommunityFollows(CommunityPeople.ME, followers = false)) }
    }
    postingTrip().let { trip ->
        val (world, c) = signedIn(trip)
        shot("me-community-profile-row", tall = 1.3f) { App(world, c, trip, Place.Home(HomeTab.Me)) }
    }
}

/** Round 25's pictures alone (`-PshotsOnly=posting`), each drawn as the community's are ([CommunityDriver]). */
fun postingOnly(out: File) = posting(out) { name, tall, frames, content, steps ->
    CommunityDriver(PHONE_W, (PHONE_H * tall).toInt(), content).use { d ->
        d.frames(frames)
        d.steps()
        d.frames(12)
        File(out, "$name.png").writeBytes(d.png())
    }
}

package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.justdataplease.boringtravel.community.Community
import com.justdataplease.boringtravel.community.LocalCommunity
import com.justdataplease.boringtravel.compat.SystemBars
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.google.LocalGoogle
import com.justdataplease.boringtravel.ui.components.paper
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.Bt
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File

/*
 * Round 24's pictures, on the fake Google, Firebase Auth, Firestore and Drive (CommunityFakes.kt, CommunitySamples.kt):
 * the Community tab in a build without Firebase, signed out (Google's and Apple's buttons) and signing in, a failed
 * sign-in; the feed (Latest, a username to pick, Top this month and of all time, Following and its suggestions,
 * nothing yet, offline with and without what was saved, a failure, loading); a post (a trip's plan with its map and
 * notes, the bar fading in, its heart tapped, a clip, an album, a plan of ideas, the traveller's own), its ⋮ and Report and Block, the
 * page of someone blocked, a post gone or unreadable, Copy this trip's first day and the trip copied, the photo viewer,
 * the clip playing; a profile (someone's with its ⋮, the traveller's own, someone blocked), Community profile with the
 * blocked list, a follow list; the trip picker behind +, and New trip's "Start from a community trip". In two looks
 * where the colours matter. `gradlew :composeApp:screenshots -PshotsOnly=community` draws only these.
 */
fun community(out: File) {
    /** The app on [world] with its community, opened at [at] over Trips. */
    @Composable
    fun App(world: CommunityWorld, community: Community?, book: TripBook = sampleBook(), vararg at: Place) {
        BoringTravelApp(
            book, world.settings, SAMPLE_TODAY, PHONE_FRAME, startAt = at.toList(), version = PREVIEW_VERSION,
            google = world.account, community = community,
        )
    }

    fun signedIn(look: Look = Look.Default, mine: Boolean = true, posts: Boolean = true, pictures: Boolean = true): Pair<CommunityWorld, Community> {
        val world = CommunityWorld(settings = AppSettings.ready(look))
        world.signInWithGoogle()
        if (posts) world.seed(mine = mine) else if (mine) world.fs.profile(CommunityPeople.ME, CommunityPeople.ME_NAME, "Slow ferries")
        if (pictures) world.pictures()
        val c = world.community()
        runBlocking { c.signIn(quiet = true) }
        return world to c
    }

    /** [content] drawn to [name].png after [frames] frames, then [steps] (taps, drags) and a few frames more. */
    fun driveThen(name: String, tall: Float = 1f, frames: Int = 40, content: @Composable () -> Unit, steps: CommunityDriver.() -> Unit) {
        CommunityDriver(PHONE_W, (PHONE_H * tall).toInt(), content).use { d ->
            d.frames(frames)
            d.steps()
            d.frames(12)
            File(out, "$name.png").writeBytes(d.png())
        }
    }

    fun drive(name: String, tall: Float = 1f, frames: Int = 40, content: @Composable () -> Unit) = driveThen(name, tall, frames, content) {}

    // ------------------------------------------------------------------ signing in
    drive("community-not-in-build") { App(CommunityWorld(), null, sampleBook(), Place.Home(HomeTab.Community)) }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val world = CommunityWorld(settings = AppSettings.ready(look))
        val c = world.community()
        drive("community-signed-out-${look.id}", tall = 1.2f) { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }
    }
    run {
        val world = CommunityWorld()
        val c = world.community()
        drive("community-signing-in") {
            CompositionLocalProvider(LocalCommunity provides c, LocalGoogle provides world.account) {
                BoringTravelTheme(Look.Default) {
                    Box(Modifier.fillMaxSize().paper(Bt.tokens).padding(PHONE_FRAME)) {
                        com.justdataplease.boringtravel.ui.community.SignInGate(
                            CommunityGraph(c, CommunitySettings(world.settings), CommunityTrips(TripBook(MemoryTripFile()), {}, {}), world.account),
                            holdSigningIn = true,
                        )
                    }
                }
            }
        }
    }
    run {
        // Google's page went through, Firebase didn't take the account: the card says so in red.
        val world = CommunityWorld()
        world.cloud.route = { call -> if (call.path == "/v1/accounts:signInWithIdp") NetAnswer(400, call.url, emptyMap(), """{"error":{"message":"INVALID_IDP_RESPONSE"}}""".encodeToByteArray()) else null }
        val c = world.community()
        driveThen("community-sign-in-failed", tall = 1.2f, content = { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }) {
            tapText("Sign in with Google")
            frames(20)
        }
    }

    // ------------------------------------------------------------------ the feed
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val (world, c) = signedIn(look)
        drive("community-feed-${look.id}") { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }
    }
    signedIn(mine = false).let { (world, c) -> drive("community-pick-username") { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) } }
    signedIn().let { (world, _) ->
        // The first page still on its way: the skeletons.
        val c = world.community(net = CommunityWorld.HangingNet(world.cloud) { it.url.endsWith(":runQuery") })
        runBlocking { c.signIn(quiet = true) }
        drive("community-loading") { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }
    }
    signedIn().let { (world, c) ->
        driveThen("community-top-month", content = { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }) {
            tapText("Top")
            frames(30)
        }
    }
    signedIn().let { (world, c) ->
        driveThen("community-top-all-time", content = { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }) {
            tapText("Top")
            frames(20)
            tapText("All time")
            frames(30)
        }
    }
    signedIn().let { (world, c) ->
        world.fs.follow(CommunityPeople.ME, CommunityPeople.ME_NAME, CommunityPeople.OTHER, CommunityPeople.OTHER_NAME)
        driveThen("community-following", content = { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }) {
            tapText("Following")
            frames(30)
        }
    }
    signedIn().let { (world, c) ->
        driveThen("community-following-empty", tall = 1.3f, content = { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }) {
            tapText("Following")
            frames(40)
        }
    }
    signedIn(posts = false).let { (world, c) -> drive("community-empty") { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) } }
    signedIn().let { (world, c) ->
        // Read once with a connection (kept on the phone, its pictures too), then without one.
        runBlocking {
            c.repo.latest(null)
            listOf("drvPatagonia01", "drvCaminitoCover").forEach { c.pictures?.load(it, PHONE_W) }
        }
        world.cloud.connected = false
        drive("community-offline-saved") { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }
    }
    signedIn().let { (world, c) ->
        world.cloud.connected = false
        drive("community-offline-empty") { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }
    }
    signedIn().let { (world, c) ->
        world.fs.queryError = 403 to "PERMISSION_DENIED"
        drive("community-error") { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }
    }
    signedIn().let { (world, c) ->
        driveThen("community-trip-picker", content = { App(world, c, sampleBook(), Place.Home(HomeTab.Community)) }) {
            tapDescription("Post to community")
            frames(20)
        }
    }

    // ------------------------------------------------------------------ a post
    fun post(id: String) = arrayOf(Place.Home(HomeTab.Community), Place.CommunityPost(id))
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val (world, c) = signedIn(look)
        drive("post-trip-${look.id}", tall = 2.9f) { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }
    }
    signedIn().let { (world, c) ->
        driveThen("post-trip-scrolled", content = { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }) {
            drag(Offset(PHONE_W / 2f, PHONE_H * 0.8f), Offset(PHONE_W / 2f, PHONE_H * 0.15f))
            frames(20)
        }
    }
    signedIn().let { (world, c) ->
        // The heart tapped: filled in the theme's red, the count one up (the server follows).
        driveThen("post-liked", content = { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }) {
            tapDescription("Like")
            frames(30)
        }
    }
    signedIn().let { (world, c) -> drive("post-clip") { App(world, c, sampleBook(), *post(CommunityWorld.CLIP)) } }
    signedIn().let { (world, c) -> drive("post-album", tall = 1.8f) { App(world, c, sampleBook(), *post(CommunityWorld.ALBUM)) } }
    signedIn().let { (world, c) -> drive("post-ideas", tall = 1.5f) { App(world, c, sampleBook(), *post(CommunityWorld.IDEAS)) } }
    signedIn().let { (world, c) ->
        driveThen("post-own-menu", content = { App(world, c, sampleBook(), *post(CommunityWorld.OWN)) }) {
            tapDescription("Post options")
            frames(10)
        }
    }
    signedIn().let { (world, c) ->
        driveThen("post-menu", content = { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }) {
            tapDescription("Post options")
            frames(10)
        }
    }
    signedIn().let { (world, c) ->
        driveThen("post-report", content = { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }) {
            tapDescription("Post options")
            frames(10)
            tapText("Report post")
            frames(20)
        }
    }
    signedIn().let { (world, c) ->
        driveThen("post-report-sent", content = { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }) {
            tapDescription("Post options")
            frames(10)
            tapText("Report post")
            frames(20)
            tapText("Spam")
            frames(20)
        }
    }
    signedIn().let { (world, c) ->
        driveThen("post-block-ask", content = { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }) {
            tapDescription("Post options")
            frames(10)
            tapText("Block @${CommunityPeople.OTHER_NAME}")
            frames(10)
        }
    }
    signedIn().let { (world, c) ->
        // Blocked from their post: the page closes and their posts leave the feed.
        driveThen("community-feed-after-block", content = { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }) {
            tapDescription("Post options")
            frames(10)
            tapText("Block @${CommunityPeople.OTHER_NAME}")
            frames(10)
            tapText("Block")
            frames(40)
        }
    }
    signedIn().let { (world, c) ->
        world.fs.block(CommunityPeople.ME, CommunityPeople.OTHER, CommunityPeople.OTHER_NAME)
        drive("post-blocked") { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }
    }
    signedIn().let { (world, c) -> drive("post-gone") { App(world, c, sampleBook(), *post("pGone0001")) } }
    signedIn().let { (world, c) ->
        world.fs.trip(CommunityWorld.PATAGONIA, """{"v":9,"days":1}""")
        drive("post-cant-read", tall = 1.4f) { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }
    }
    signedIn().let { (world, c) ->
        driveThen("post-copy-dialog", content = { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }) {
            drag(Offset(PHONE_W / 2f, PHONE_H * 0.7f), Offset(PHONE_W / 2f, PHONE_H * 0.4f))
            frames(10)
            tapText("Copy this trip")
            frames(15)
        }
    }
    signedIn().let { (world, c) ->
        driveThen("post-copied", content = { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }) {
            drag(Offset(PHONE_W / 2f, PHONE_H * 0.7f), Offset(PHONE_W / 2f, PHONE_H * 0.4f))
            frames(10)
            tapText("Copy this trip")
            frames(15)
            tapText("Without dates")
            frames(30)
        }
    }
    signedIn().let { (world, c) ->
        driveThen("post-photo-viewer", content = { App(world, c, sampleBook(), *post(CommunityWorld.PATAGONIA)) }) {
            tapDescription("Photo 1 of 3")
            frames(30)
        }
    }
    signedIn().let { (world, c) ->
        // The desktop's still player (AVPlayer on iPhone): the clip's first picture stands in for it playing.
        val still = runBlocking { com.justdataplease.boringtravel.ios.resources.Res.readBytes("files/demo/argentina/caminito.jpg") }
        val media = MediaKit(MemoryMediaFiles(), frames = VideoFrames { _, _ -> still }, playback = StillVideoPlayback({ 40_000 }))
        driveThen("post-clip-playing", content = {
            BoringTravelApp(
                sampleBook(), world.settings, SAMPLE_TODAY, PHONE_FRAME, startAt = post(CommunityWorld.CLIP).toList(), version = PREVIEW_VERSION,
                media = media, google = world.account, community = c,
            )
        }) {
            tapDescription("clip")
            frames(30)
        }
    }

    // ------------------------------------------------------------------ profiles
    fun profile(uid: String) = arrayOf(Place.Home(HomeTab.Community), Place.CommunityProfile(uid))
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val (world, c) = signedIn(look)
        drive("profile-other-${look.id}") { App(world, c, sampleBook(), *profile(CommunityPeople.OTHER)) }
    }
    signedIn().let { (world, c) ->
        driveThen("profile-menu", content = { App(world, c, sampleBook(), *profile(CommunityPeople.OTHER)) }) {
            tapDescription("Profile options")
            frames(10)
        }
    }
    signedIn().let { (world, c) ->
        driveThen("profile-report", content = { App(world, c, sampleBook(), *profile(CommunityPeople.OTHER)) }) {
            tapDescription("Profile options")
            frames(10)
            tapText("Report @${CommunityPeople.OTHER_NAME}")
            frames(20)
        }
    }
    signedIn().let { (world, c) -> drive("profile-mine") { App(world, c, sampleBook(), *profile(CommunityPeople.ME)) } }
    signedIn().let { (world, c) ->
        world.fs.block(CommunityPeople.ME, CommunityPeople.OTHER, CommunityPeople.OTHER_NAME)
        drive("profile-blocked") { App(world, c, sampleBook(), *profile(CommunityPeople.OTHER)) }
    }
    signedIn().let { (world, c) ->
        world.fs.block(CommunityPeople.ME, CommunityPeople.THIRD, CommunityPeople.THIRD_NAME)
        drive("community-profile-blocked-list", tall = 1.4f) { App(world, c, sampleBook(), Place.Home(HomeTab.Community), Place.CommunityProfileEdit) }
    }
    signedIn().let { (world, c) ->
        world.fs.follow(CommunityPeople.THIRD, CommunityPeople.THIRD_NAME, CommunityPeople.OTHER, CommunityPeople.OTHER_NAME)
        world.fs.follow(CommunityPeople.ME, CommunityPeople.ME_NAME, CommunityPeople.OTHER, CommunityPeople.OTHER_NAME)
        drive("follow-list") { App(world, c, sampleBook(), Place.Home(HomeTab.Community), Place.CommunityFollows(CommunityPeople.OTHER, followers = true)) }
    }

    // ------------------------------------------------------------------ New trip
    signedIn().let { (world, c) -> drive("new-trip-community", tall = 1.15f) { App(world, c, sampleBook(), Place.NewTrip) } }

    // ------------------------------------------------------------------ round 25: posting, profiles, Community profile
    posting(out) { name, tall, frames, content, steps -> driveThen(name, tall, frames, content, steps) }
}

/**
 * A scene drawn frame by frame with a finger on it, for the community's pictures: taps on what a screen reader would
 * read (a description or a text), drags, the phone's status bar over it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CommunityDriver(width: Int, height: Int, content: @Composable () -> Unit) : AutoCloseable {
    init {
        // The status bar's default first: the app then sets its theme's (light icons on Trail) as it composes.
        SystemBars.set(true)
    }

    private val scene = ImageComposeScene(width, height, Density(PHONE_DENSITY)) {
        Box(Modifier.fillMaxSize()) {
            content()
            PhoneStatusBar(landscape = false)
        }
    }
    private var ms = 0L
    private var image: Image

    init {
        image = scene.render(0)
    }

    fun frames(n: Int) = repeat(n) {
        Thread.sleep(50)
        ms += 50
        image.close()
        image = scene.render(ms * 1_000_000)
    }

    private fun press(at: Offset) = scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms, type = PointerType.Touch)

    private fun release(at: Offset) = scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms, type = PointerType.Touch)

    fun tapAt(at: Offset) {
        press(at)
        frames(1)
        release(at)
        frames(2)
    }

    /** A finger held on [at] for [heldFrames] frames, then lifted (a long press: a plan row's menu). */
    fun holdAt(at: Offset, heldFrames: Int = 14) {
        press(at)
        frames(heldFrames)
        release(at)
        frames(2)
    }

    /** Where the first node a screen reader would tap for [label] is ("Open Hotel Grotta"), for [holdAt]. */
    fun clickCentre(label: String): Offset =
        (find { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == label } ?: error("Nothing does $label")).center

    /** A tap on what is described as (or contains) [description]. */
    fun tapDescription(description: String) = tapAt((find { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> description in d } == true } ?: error("Nothing described as $description")).center)

    /** A tap on the [index]th node described as (or containing) [description] (a grid's photos). */
    fun tapDescription(description: String, index: Int) {
        val found = mutableListOf<Rect>()
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> description in d } == true) found += n.boundsInRoot
            n.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        tapAt(found.getOrNull(index)?.center ?: error("No ${index}th $description"))
    }

    /** A tap on the last node showing [text] (a menu's line over the page, a sheet's row). */
    fun tapText(text: String) = tapAt((find(last = true) { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true } ?: error("No text $text")).center)

    fun drag(from: Offset, to: Offset, steps: Int = 12) {
        press(from)
        frames(1)
        for (i in 1..steps) {
            val at = Offset(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps)
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms, type = PointerType.Touch)
            frames(1)
        }
        release(to)
        frames(4)
    }

    private fun find(last: Boolean = false, match: (SemanticsNode) -> Boolean): Rect? {
        val found = mutableListOf<Rect>()
        fun walk(n: SemanticsNode) {
            if (match(n)) found += n.boundsInRoot
            n.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return if (last) found.lastOrNull() else found.firstOrNull()
    }

    fun png(): ByteArray = image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }

    override fun close() {
        image.close()
        scene.close()
        System.gc()
    }
}

package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import com.justdataplease.boringtravel.community.PostLink
import com.justdataplease.boringtravel.compat.setPhoneLanguage
import com.justdataplease.boringtravel.together.ShareLink
import com.justdataplease.boringtravel.together.TripMail
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.runBlocking
import java.io.File

/*
 * Round 26's pictures, on the fakes (each traveller's own Google and Drive, one Firestore for the trip mailboxes):
 * Trips' "Join a friend's trip" and its dialog, the trip's ⋮ with Travel together, the sheet before sharing (two looks)
 * and once a friend is in, joined without Drive, the Join sheet (asked, in, one more step, an error), a member's link
 * (Add, Already here), the header's badge, a friend's note "By Maria", Leave this trip?, and the Community post sheet's
 * "Last updated" in Greek. `gradlew :composeApp:screenshots -PshotsOnly=together` draws only these.
 */
fun together(out: File) {
    fun drive(name: String, tall: Float = 1f, frames: Int = 40, content: @Composable () -> Unit, steps: CommunityDriver.() -> Unit = {}) {
        CommunityDriver(PHONE_W, (PHONE_H * tall).toInt(), content).use { d ->
            d.frames(frames)
            d.steps()
            d.frames(12)
            File(out, "$name.png").writeBytes(d.png())
        }
    }

    @Composable
    fun App(p: TogetherPhone, vararg at: Place, links: AppLinks? = null) {
        BoringTravelApp(
            p.book, p.settings, SAMPLE_TODAY, PHONE_FRAME, startAt = at.toList(), version = PREVIEW_VERSION, media = p.kit,
            google = p.world.account, community = p.community, together = p.together, links = links,
        )
    }

    // ------------------------------------------------------------------ Trips' last row and its dialog
    TogetherWorld().let { w ->
        drive("trips-join-row", 1.4f, content = { App(w.nikos) })
        drive("join-a-trip", 1.4f, content = { App(w.nikos) }) {
            tapText("Join a friend's trip")
            frames(16)
        }
    }

    // ------------------------------------------------------------------ the trip's ⋮ and the sheet before sharing
    TogetherWorld().let { w ->
        drive("trip-menu-together", content = { App(w.maria, Place.Trip(w.tripId, TripTab.Plan)) }) {
            tapDescription("Trip options")
            frames(12)
        }
    }
    for (look in listOf(Look.PostcardPop, Look.Trail)) {
        val w = TogetherWorld()
        w.maria.settings.look = look
        drive("together-sheet-invite-${look.id}", content = { App(w.maria, Place.Trip(w.tripId, TripTab.Plan)) }) {
            tapDescription("Trip options")
            frames(12)
            tapText("Travel together")
            frames(30)
        }
    }
    // Drive not connected: the button connects it first.
    TogetherWorld().let { w ->
        w.maria.settings.driveConnected = false
        drive("together-sheet-connect-drive", content = { App(w.maria, Place.Trip(w.tripId, TripTab.Plan)) }) {
            tapDescription("Trip options")
            frames(12)
            tapText("Travel together")
            frames(30)
        }
    }

    // ------------------------------------------------------------------ a friend in: members, the sync line, the badge
    TogetherWorld().let { w ->
        w.joined()
        drive("trip-shared-badge", content = { App(w.maria, Place.Trip(w.tripId, TripTab.Plan)) })
        drive("together-sheet-members", content = { App(w.maria, Place.Trip(w.tripId, TripTab.Plan)) }) {
            tapText("+1")
            frames(30)
            tapText("Sync now")
            frames(40)
        }
        drive("trip-leave-ask", content = { App(w.nikos, Place.Trip(w.tripId, TripTab.Plan)) }) {
            tapDescription("Trip options")
            frames(12)
            tapText("Leave trip")
            frames(16)
        }
        // Maria's note on Nikos's phone, read only.
        val note = w.nikos.book.entries(w.tripId).single()
        runBlocking { w.nikos.together.syncNow(w.tripId) }
        drive("note-by-friend", 1.2f, content = { App(w.nikos, Place.Trip(w.tripId, TripTab.Journal), Place.Note(w.tripId, note.id)) })
    }
    // Joined without Drive: viewing only, until Drive is connected.
    TogetherWorld(nikosDrive = false).let { w ->
        runBlocking { w.nikos.together.open(w.invite()) }
        drive("together-sheet-viewing", content = { App(w.nikos, Place.Trip(w.tripId, TripTab.Plan)) }) {
            tapText("+1")
            frames(30)
        }
    }

    // ------------------------------------------------------------------ the Join sheet
    TogetherWorld().let { w ->
        val invite = w.invite()
        val links = AppLinks().apply { open(invite.url) }
        drive("join-sheet", content = { App(w.nikos, links = links) })
    }
    TogetherWorld().let { w ->
        val invite = w.invite()
        runBlocking { w.maria.together.checkMail(w.tripId) }
        val links = AppLinks().apply { open(invite.url) }
        drive("join-sheet-in", content = { App(w.nikos, links = links) }) {
            tapText("Join")
            frames(60)
        }
    }
    // Maria's app isn't in the mailbox (an older app, or not signed in to Firebase): Nikos is asked to send his link once.
    // Her app never signs in to Firebase here, so nothing of hers reaches the mailbox, also not later in the background.
    TogetherWorld(mariaFirebase = false).let { w ->
        val invite = w.invite()
        runBlocking { w.maria.together.syncNow(w.tripId) }
        check(w.fs.mailbox(TripMail.box(w.tripId)!!).isEmpty()) { "Maria's entry is in the mailbox" }
        val links = AppLinks().apply { open(invite.url) }
        drive("join-sheet-one-more-step", content = { App(w.nikos, links = links) }) {
            tapText("Join")
            frames(60)
        }
    }
    TogetherWorld().let { w ->
        val invite = w.invite()
        w.maria.cloud.items.remove(invite.fileId)
        val links = AppLinks().apply { open(invite.url) }
        drive("join-sheet-error", content = { App(w.nikos, links = links) }) {
            tapText("Join")
            frames(40)
        }
    }
    // A member's own link, sent back (the way before the mailbox): Add, then Already here.
    TogetherWorld().let { w ->
        val invite = w.invite()
        val back: ShareLink = runBlocking {
            w.nikos.together.open(invite)
            w.nikos.together.share(w.tripId, "Spain 2027")
        }
        drive("join-sheet-add", content = { App(w.maria, links = AppLinks().apply { open(back.url) }) })
        runBlocking { w.maria.together.open(back) }
        drive("join-sheet-already", content = { App(w.maria, links = AppLinks().apply { open(back.url) }) }) {
            tapText("Add")
            frames(40)
        }
    }

    // ------------------------------------------------------------------ round 25's leftover: "Last updated" in Greek
    setPhoneLanguage("el-GR")
    try {
        val trip = postingTrip()
        val world = CommunityWorld(files = trip.kit.files as MemoryMediaFiles)
        world.signInWithGoogle()
        world.seed()
        val c = world.community()
        runBlocking {
            c.signIn(quiet = true)
            c.usePhone(trip.kit.files, trip.kit.frames, { trip.book.media(it) })
            c.postLinks.put(PostLink(trip.tripId, CommunityWorld.OWN, trip.title, syncedAt = com.justdataplease.boringtravel.time.nowMs() - 5 * 60_000L, sent = "old"))
        }
        drive(
            "post-sync-sheet-el",
            content = {
                BoringTravelApp(
                    trip.book, world.settings, SAMPLE_TODAY, PHONE_FRAME, startAt = listOf(Place.Trip(trip.tripId, TripTab.Plan)),
                    version = PREVIEW_VERSION, media = trip.kit, google = world.account, community = c,
                )
            },
        ) {
            tapDescription("Trip options")
            frames(12)
            tapText("Community post")
            frames(30)
        }
    } finally {
        setPhoneLanguage(null)
    }
}

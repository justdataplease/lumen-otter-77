package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.together.Member
import com.justdataplease.boringtravel.together.ShareCodec
import com.justdataplease.boringtravel.together.ShareLink
import com.justdataplease.boringtravel.together.SharedTrip
import com.justdataplease.boringtravel.together.SyncStatus
import com.justdataplease.boringtravel.together.TogetherEngine
import com.justdataplease.boringtravel.together.TogetherState
import com.justdataplease.boringtravel.together.TripMail
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.together.TogetherText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Travel together (round 26) driven on the desktop as travellers would, on the fakes: each phone its own Google account
 * and Drive, one Firestore holding the owner's rules for the trip mailboxes. Inviting from the trip's ⋮, joining from a
 * pasted link and from a link opened with the app (waiting for Welcome), the Join sheet's last step (send your link once
 * when the inviter's app isn't in the mailbox, nothing more when it is), adding a member's link, every join error, the
 * members and the sync line, the merge (the later edit wins, a tie keeps this phone's, deletions travel), photos arriving
 * and a damaged copy, a deleted copy made again, joining without Drive, the sends after a change (also offline), leaving,
 * the mailbox's newcomer word, backups and restore, the badge, the menu, a friend's note "By <Name>", an example trip,
 * Delete account's mailbox entries and each member's own check-in. Nothing reaches the network.
 */
@OptIn(ExperimentalTestApi::class)
class TogetherOnDesktopTest {
    init {
        runBlocking {
            BtFontFiles.load()
            CountryOutlines.load()
        }
    }

    private fun ComposeUiTest.app(p: TogetherPhone, vararg at: Place, phone: Phone = Phone(), links: AppLinks? = null) {
        setContent {
            CompositionLocalProvider(LocalPhone provides phone) {
                BoringTravelApp(
                    p.book, p.settings, SAMPLE_TODAY, startAt = at.toList(), version = "1.22.0", media = p.kit,
                    google = p.world.account, community = p.community, together = p.together, links = links,
                )
            }
        }
    }

    private fun ComposeUiTest.has(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitFor(text: String, substring: Boolean = false, timeout: Long = 20_000) =
        waitUntil(timeoutMillis = timeout) { has(text, substring) }

    private fun ComposeUiTest.tap(text: String) = onAllNodesWithText(text).onFirst().performClick()

    private fun ComposeUiTest.openTogether() {
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription("Trip options").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription("Trip options").onFirst().performClick()
        waitFor("Travel together")
        tap("Travel together")
        waitFor("TRAVEL TOGETHER")
    }

    @Test fun inviteFromTheTripsMenu() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        val shares = mutableListOf<Pair<String, String>>()
        app(w.maria, Place.Trip(w.tripId, TripTab.Plan), phone = Phone(share = { s, t -> shares += s to t; true }))
        openTogether()
        waitFor("Plan and write this trip with friends")
        assertTrue(has("Everyone can add places, change the plan, write and add photos, and make their own videos.", substring = true))
        assertTrue(has("Your copy of the trip goes to your Google Drive. Anyone with the link can see it, so send it only to the people you travel with."))
        tap("Invite")
        waitUntil(timeoutMillis = 20_000) { shares.isNotEmpty() }
        val (subject, text) = shares.single()
        assertEquals("Spain 2027", subject)
        assertTrue(text.startsWith("Join my trip “Spain 2027” on BoringTravel, so we can plan it together:\n${ShareLink.WEB}#v=1&t=${w.tripId}&f="), text)
        val link = assertNotNull(ShareLink.find(text))
        assertEquals("Maria", link.name)
        // Maria's copy: BoringTravel › Shared › Spain 2027 › trip.btshare in her own Drive, link-shared; its photo's copy beside it.
        val folder = w.maria.cloud.items.values.single { it.isFolder && it.name == "Spain 2027" }
        assertTrue(folder.anyone)
        assertEquals("Shared", w.maria.cloud.items[folder.parents.single()]?.name)
        val file = w.maria.cloud.items.getValue(link.fileId)
        assertEquals("trip.btshare", file.name)
        assertEquals("Spain 2027", ShareCodec.decode(file.content).trip.title)
        waitUntil(timeoutMillis = 20_000) { w.maria.cloud.items.values.any { folder.id in it.parents && it.name.endsWith(".jpg") } }
        // The sheet now shows who is in, the sync line and the next steps.
        waitFor("Friends who open your link and join show up here.")
        onNodeWithText("Maria").assertExists()
        assertTrue(has("Sync now"))
        assertTrue(has("Leave trip"))
        // Her address in the trip's mailbox, under her own Firebase account.
        val box = TripMail.box(w.tripId)!!
        waitUntil(timeoutMillis = 20_000) { w.fs.mailbox(box).containsKey(w.maria.uid) }
        assertEquals(link.fileId, w.fs.mailbox(box).getValue(w.maria.uid)["fileId"])
        // The same link each time.
        tap("Invite")
        waitUntil(timeoutMillis = 20_000) { shares.size == 2 }
        assertEquals(link, ShareLink.find(shares[1].second))
    }

    @Test fun aFriendJoinsFromAPastedLink() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        val invite = w.invite()
        w.nikos.settings.demoMode = true
        app(w.nikos)
        waitFor("Join a friend's trip")
        onNodeWithText("Join a friend's trip").performScrollTo().performClick()
        waitFor("Join a trip")
        onNodeWithText("Paste the link or the whole message").assertExists()
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("see you in Seville")
        waitFor("That doesn't look like a BoringTravel trip link.")
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(TogetherText.invite(invite))
        waitUntil(timeoutMillis = 10_000) { !has("That doesn't look like a BoringTravel trip link.") }
        tap("Next")
        waitFor("Join “Spain 2027”")
        onNodeWithText("Maria invited you to plan this trip together. You'll see the plan, the journal and the photos, and add your own.").assertExists()
        tap("Join")
        // The trip arrives; his own copy goes to his Drive and its address to the mailbox, where Maria's already is.
        waitFor("You're in")
        waitFor("Your changes reach the others by themselves.", timeout = 30_000)
        assertFalse(w.nikos.settings.demoMode)
        assertEquals("Spain 2027", w.nikos.trip(w.tripId)?.title)
        assertEquals(w.maria.titles(w.tripId), w.nikos.titles(w.tripId))
        assertTrue(w.nikos.cloud.items.values.any { it.name == "trip.btshare" && it.anyone })
        tap("Open the trip")
        waitFor("Alcázar of Seville")
        // Shared with one other: the header's badge.
        waitFor("+1")
        // Maria's phone picks him up from the mailbox, without a link back.
        val added = runBlocking { w.maria.together.mail!!.look(w.tripId) }
        assertNotNull(added)
        waitUntil(timeoutMillis = 20_000) { runBlocking { w.maria.together.engine.load() }.of(w.tripId)!!.members.any { it.name == "Nikos" } }
    }

    @Test fun aJoinerWhoseInviterIsntInTheMailboxSendsTheirLinkOnce() = runDesktopComposeUiTest(width = 440, height = 1600) {
        // Maria's app isn't signed in to Firebase (or is an older one): her copy is in her Drive, nothing in the mailbox.
        val w = TogetherWorld(mariaFirebase = false)
        val invite = w.invite()
        runBlocking { w.maria.together.syncNow(w.tripId) }
        val box = TripMail.box(w.tripId)!!
        assertTrue(w.fs.mailbox(box).isEmpty())
        val shares = mutableListOf<Pair<String, String>>()
        val links = AppLinks()
        assertTrue(links.open(invite.url))
        app(w.nikos, phone = Phone(share = { s, t -> shares += s to t; true }), links = links)
        waitFor("Join “Spain 2027”")
        tap("Join")
        waitFor("You're in")
        // JoinSheet: a copy of his own, but nobody else reads the mailbox, so Maria's phone can't pick it up by itself.
        waitFor("One more step: send Maria your link once, so your changes show on their phone too.", timeout = 30_000)
        assertFalse(has("Your changes reach the others by themselves."))
        assertTrue(has("Later"))
        // His address went in all the same; Maria's isn't there.
        assertEquals(setOf(w.nikos.uid), w.fs.mailbox(box).keys)
        tap("Send my link")
        waitUntil(timeoutMillis = 20_000) { shares.isNotEmpty() }
        val (subject, text) = shares.single()
        assertEquals("Spain 2027", subject)
        val mine = assertNotNull(ShareLink.find(text))
        assertEquals(TogetherText.sendBack(mine), text)
        assertTrue(text.startsWith("I joined “Spain 2027”. Open this so my changes show on your phone too:\n${ShareLink.WEB}#v=1&t=${w.tripId}&f="), text)
        assertEquals("Nikos", mine.name)
        assertEquals(w.tripId, mine.tripId)
        // The link is his own copy, link-shared in his Drive; then the trip opens.
        val file = w.nikos.cloud.items.getValue(mine.fileId)
        assertEquals("trip.btshare", file.name)
        assertTrue(file.anyone)
        waitFor("Alcázar of Seville")
        // Maria opens it: Nikos is added on her phone, the way before the mailbox.
        val added = runBlocking { w.maria.together.open(mine) }
        assertTrue(added is TogetherEngine.Opened.Added, added.toString())
        assertTrue(runBlocking { w.maria.together.engine.load() }.of(w.tripId)!!.members.any { it.name == "Nikos" && it.fileId == mine.fileId })
    }

    @Test fun aJoinerWhoseInviterIsInTheMailboxHasNothingMoreToDo() = runDesktopComposeUiTest(width = 440, height = 1600) {
        // Maria's app is signed in to Firebase: her address is in the trip's mailbox before Nikos joins.
        val w = TogetherWorld()
        val invite = w.invite()
        runBlocking { w.maria.together.checkMail(w.tripId) }
        val box = TripMail.box(w.tripId)!!
        assertEquals(setOf(w.maria.uid), w.fs.mailbox(box).keys)
        val shares = mutableListOf<Pair<String, String>>()
        val links = AppLinks()
        assertTrue(links.open(invite.url))
        app(w.nikos, phone = Phone(share = { s, t -> shares += s to t; true }), links = links)
        waitFor("Join “Spain 2027”")
        tap("Join")
        waitFor("You're in")
        waitFor("Your changes reach the others by themselves.", timeout = 30_000)
        assertFalse(has("One more step", substring = true))
        assertFalse(has("Send my link"))
        assertEquals(setOf(w.maria.uid, w.nikos.uid), w.fs.mailbox(box).keys)
        tap("Open the trip")
        waitFor("Alcázar of Seville")
        // Nothing to send: Maria's phone reads his address from the mailbox.
        assertTrue(shares.isEmpty())
        runBlocking { w.maria.together.mail!!.look(w.tripId) }
        waitUntil(timeoutMillis = 20_000) { runBlocking { w.maria.together.engine.load() }.of(w.tripId)!!.members.any { it.name == "Nikos" } }
    }

    @Test fun aLinkOpenedWithTheAppWaitsForWelcomeAndSurvivesAStop() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld(nikosSignedIn = false)
        val invite = w.invite()
        w.nikos.settings.onboardingDone = false
        val links = AppLinks()
        // The app's own link (boringtravel://join#…), everything after "#" read.
        assertTrue(links.open(invite.url.replace(ShareLink.WEB, ShareLink.APP)))
        app(w.nikos, links = links)
        waitFor("Use without an account")
        assertFalse(has("Join “Spain 2027”"))
        tap("Use without an account")
        waitFor("Join “Spain 2027”")
        tap("Not now")
        waitUntil(timeoutMillis = 10_000) { !has("Join “Spain 2027”") }
        // Kept with the window (scene storage) while it waits: back after iOS closed the app.
        val stack = AppStack(listOf(Place.Trips))
        val kept = AppRestore.encode(stack, TripTab.Plan, null, emptyMap(), invite.url)
        assertEquals(invite, AppRestore.joinOf(kept))
        assertNull(AppRestore.joinOf(AppRestore.encode(stack, TripTab.Plan, null, emptyMap())))
    }

    @Test fun everyJoinErrorIsSaid() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        val invite = w.invite()
        val eleni = w.eleni()
        val links = AppLinks()
        app(eleni, links = links)
        waitFor("Join a friend's trip")
        fun tryJoin(link: ShareLink, error: String) {
            assertTrue(links.open(link.url))
            waitFor("Join “Spain 2027”")
            tap("Join")
            waitFor(error, timeout = 30_000)
            tap("Not now")
            waitUntil(timeoutMillis = 10_000) { !has("Join “Spain 2027”") }
        }
        // Not that member's copy of that trip: someone else's id for it.
        tryJoin(invite.copy(memberId = "someone-else"), "This link doesn't match its trip.")
        // No connection.
        eleni.cloud.connected = false
        tryJoin(invite, "No internet connection. Check it and try again.")
        eleni.cloud.connected = true
        // Something the app has no words for: the sheet's own.
        eleni.cloud.route = { c -> if (c.q("alt") == "media") throw IllegalStateException() else null }
        tryJoin(invite, "Couldn't open the trip. Try again.")
        eleni.cloud.route = null
        // The trip is on this phone already (not as a shared trip).
        eleni.book.putRows(trips = listOf(w.maria.trip(w.tripId)!!))
        tryJoin(invite, "This trip is already on this phone.")
        eleni.book.putRows(trips = listOf(w.maria.trip(w.tripId)!!.copy(deletedAt = 5)))
        // Maria left: her copy says so.
        runBlocking { w.maria.together.leave(w.tripId) }
        tryJoin(invite, "Maria left this trip. Ask someone still in it for a link.")
        // Her copy deleted from her Drive: the link is dead.
        w.maria.cloud.items.remove(invite.fileId)
        tryJoin(invite, "This link doesn't work any more. Ask for a new one.")
    }

    @Test fun aTripTakesTwelvePeople() = runBlocking<Unit> {
        val w = TogetherWorld()
        w.invite()
        // Maria's trip with eleven others already (the twelfth is her): one more is refused before anything is read.
        val st = w.maria.together.engine.load()
        val full = st.of(w.tripId)!!.copy(members = (1..11).map { Member("friend-$it", "Friend $it", "drvFriend$it") })
        val json = Json { encodeDefaults = false }.encodeToString(TogetherState.serializer(), TogetherState(trips = listOf(full)))
        val other = TogetherPhone("Sofia", "sub-sofia", w.fs, w.clock)
        TogetherWorld.spain(other, SAMPLE_TODAY)
        val copy = other.book.snapshot.trips.single()
        // The same trip on another phone (its id as Maria's), shared there as Maria's state says.
        other.book.putRows(trips = listOf(copy.copy(id = w.tripId)))
        other.together.engine.importState(json)
        val error = runCatching { other.together.open(ShareLink(w.tripId, "drvNewFriend1", "friend-12", "Twelve")) }.exceptionOrNull()
        assertEquals("This trip already has 12 people, the most a shared trip takes.", error?.message)
    }

    @Test fun aMembersOwnLinkAddsThemOnce() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        val invite = w.invite()
        // Nikos joins with an older app's way: no mailbox, so he sends his link back.
        val back = runBlocking {
            w.nikos.together.open(invite)
            w.nikos.together.share(w.tripId, "Spain 2027")
        }
        assertTrue(TogetherText.sendBack(back).startsWith("I joined “Spain 2027”. Open this so my changes show on your phone too:\n"))
        val links = AppLinks()
        app(w.maria, links = links)
        waitFor("Join a friend's trip")
        links.open(back.url)
        waitFor("Add Nikos to “Spain 2027”?")
        onNodeWithText("Their changes will show on this phone.").assertExists()
        tap("Add")
        waitFor("Nikos is in", timeout = 30_000)
        onNodeWithText("Their changes will show here within a minute.").assertExists()
        tap("Open the trip")
        waitFor("+1")
        // The same link again: asked as before, then already here.
        links.open(back.url)
        waitFor("Add Nikos to “Spain 2027”?")
        // The trip's own Add (its plan) is under the sheet: the sheet's, drawn last.
        onAllNodesWithText("Add").onLast().performClick()
        waitFor("Already here", timeout = 30_000)
        onNodeWithText("Nikos is already in.").assertExists()
        tap("Open the trip")
        waitUntil(timeoutMillis = 10_000) { !has("Already here") }
        // Her own link: the Join question (Android's sheet), then hers.
        links.open(invite.url)
        waitFor("Join “Spain 2027”")
        tap("Join")
        waitFor("This is your own link for this trip.", timeout = 30_000)
    }

    @Test fun membersAndTheSyncLine() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        w.joined()
        val eleni = w.eleni()
        // Eleni joins without Drive: she only views.
        eleni.settings.driveConnected = false
        runBlocking { eleni.together.open(w.invite()) }
        // Eleni's link back without a copy of her own is nothing to add: Maria knows of her through Nikos's file later.
        app(w.maria, Place.Trip(w.tripId, TripTab.Plan))
        openTogether()
        waitFor("Sync now")
        tap("Sync now")
        waitFor("Up to date · just now", timeout = 30_000)
        assertTrue(has("Maria"))
        assertTrue(has("You"))
        assertTrue(has("Nikos"))
        assertTrue(has("Changed ", substring = true))
        // Nikos's copy taken off its link: can't be reached.
        w.nikos.cloud.items.values.filter { it.name == "trip.btshare" || it.isFolder }.forEach { it.anyone = false }
        tap("Sync now")
        waitFor("Can't reach their copy", timeout = 30_000)
        // A failure is said in red, in plain words.
        w.maria.cloud.connected = false
        tap("Sync now")
        waitFor("No internet connection. Check it and try again.", timeout = 30_000)
        w.maria.cloud.connected = true
        // The status's words, as the sheet writes them.
        assertEquals("just now", TogetherText.ago(1_000, 30_000))
        assertEquals("2 min ago", TogetherText.ago(0, 150_000))
        assertEquals("yesterday", TogetherText.ago(0, 30 * 3_600_000L))
    }

    @Test fun theLaterEditWinsATieKeepsThisPhonesAndDeletionsTravel() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        w.joined()
        val alcazar = w.maria.book.items(w.tripId).single { it.title == "Alcázar of Seville" }
        val triana = w.maria.book.items(w.tripId).single { it.title == "Tapas in Triana" }
        val flamenco = w.maria.book.items(w.tripId).single { it.title == "Flamenco show" }
        // Maria changes the Alcázar, then Nikos later: his is later and wins on both phones.
        w.maria.book.saveItem(alcazar.copy(title = "Alcázar early"))
        w.nikos.book.saveItem(w.nikos.book.item(alcazar.id)!!.copy(title = "Alcázar at opening time"))
        // Both change Triana at the very same stamp: each phone keeps its own.
        val tie = w.clock.next()
        w.maria.book.putRows(items = listOf(triana.copy(title = "Triana (Maria)", updatedAt = tie)))
        w.nikos.book.putRows(items = listOf(w.nikos.book.item(triana.id)!!.copy(title = "Triana (Nikos)", updatedAt = tie)))
        // Nikos deletes the flamenco show.
        w.nikos.book.removeItem(flamenco.id)
        runBlocking { w.nikos.together.syncNow(w.tripId) }
        app(w.maria, Place.Trip(w.tripId, TripTab.Plan))
        openTogether()
        tap("Sync now")
        waitFor("Up to date · just now", timeout = 30_000)
        runBlocking { w.nikos.together.syncNow(w.tripId) }
        assertEquals("Alcázar at opening time", w.maria.book.item(alcazar.id)?.title)
        assertEquals("Alcázar at opening time", w.nikos.book.item(alcazar.id)?.title)
        assertEquals("Triana (Maria)", w.maria.book.item(triana.id)?.title)
        assertEquals("Triana (Nikos)", w.nikos.book.item(triana.id)?.title)
        assertNull(w.maria.book.item(flamenco.id))
        // Nobody else can delete or hide the trip on her phone; her tracking level stays hers.
        w.maria.book.setTrackingLevel(w.tripId, "detailed")
        w.nikos.book.putRows(trips = listOf(w.nikos.trip(w.tripId)!!.copy(title = "Spain, spring 2027", trackingLevel = "low", updatedAt = w.clock.next())))
        runBlocking {
            w.nikos.together.syncNow(w.tripId)
            w.maria.together.syncNow(w.tripId)
        }
        val trip = w.maria.trip(w.tripId)!!
        assertEquals("Spain, spring 2027", trip.title)
        assertEquals("detailed", trip.trackingLevel)
        assertNull(trip.deletedAt)
    }

    @Test fun photosArriveAndADamagedCopyIsSaid() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        w.joined()
        runBlocking { w.nikos.together.syncNow(w.tripId) }
        // Maria's photo arrives on Nikos's phone as its 1600 px copy, by its author, under media/shared-<id>.
        val photo = w.nikos.book.snapshot.media.single { it.tripId == w.tripId }
        assertNotNull(photo.authorId)
        assertEquals("media/shared-${photo.id}.jpg", photo.localPath)
        val bytes = assertNotNull(w.nikos.files.read(photo.localPath))
        assertEquals(1600, Pictures.size(bytes)!!.first)
        assertTrue(w.nikos.together.photosVersion.value > 0)
        // Her note on his phone: "By Maria", read only (no Edit, no Delete, its checklist not tickable).
        val note = w.nikos.book.entries(w.tripId).single()
        app(w.nikos, Place.Trip(w.tripId, TripTab.Journal), Place.Note(w.tripId, note.id))
        waitFor("By Maria")
        assertFalse(has("Edit"))
        assertFalse(has("Delete"))
        tap("Passports")
        waitForIdle()
        assertTrue(EntryDoc.parse(w.nikos.book.entry(note.id)!!.body).blocks.filterIsInstance<com.justdataplease.boringtravel.doc.Block.Checklist>().single().items.none { it.text == "Passports" && it.done })
        // Maria's copy damaged in her Drive: his sync says so, in red, and keeps what he has.
        val file = w.maria.cloud.items.values.single { it.name == "trip.btshare" }
        file.content = "not a trip".encodeToByteArray()
        runBlocking { runCatching { w.nikos.together.syncNow(w.tripId) } }
        assertEquals(SyncStatus.Failed("This shared trip is damaged."), w.nikos.together.status.value[w.tripId])
        file.content = com.justdataplease.boringtravel.compat.Gzip.compress("""{"format":1,"app":"Other","writer":"x","trip":{"id":"t"}}""".encodeToByteArray())
        runBlocking { runCatching { w.nikos.together.syncNow(w.tripId) } }
        assertEquals(SyncStatus.Failed("This isn't a BoringTravel trip."), w.nikos.together.status.value[w.tripId])
        assertEquals(w.maria.titles(w.tripId), w.nikos.titles(w.tripId))
    }

    @Test fun aDeletedCopyIsMadeAgain() = runBlocking<Unit> {
        val w = TogetherWorld()
        w.joined()
        val before = w.maria.together.engine.load().of(w.tripId)!!
        // Maria deletes her shared folder in Drive (with her copy and its photos): the next sync makes them again.
        val folder = w.maria.cloud.items.values.single { it.isFolder && it.name == "Spain 2027" }
        w.maria.cloud.items.values.filter { folder.id in it.parents }.map { it.id }.forEach { w.maria.cloud.items.remove(it) }
        w.maria.cloud.items.remove(folder.id)
        w.maria.book.saveItem(w.maria.book.items(w.tripId).first().copy(title = "Alcázar, again"))
        w.maria.together.syncNow(w.tripId)
        val after = w.maria.together.engine.load().of(w.tripId)!!
        assertNotEquals(before.fileId, after.fileId)
        assertNotEquals(before.folderId, after.folderId)
        assertTrue(w.maria.cloud.items.getValue(after.fileId!!).anyone)
        // Her new copy's address goes into the mailbox; Nikos can't reach her old one, so he takes the new one from there.
        w.maria.together.checkMail(w.tripId)
        runCatching { w.nikos.together.syncNow(w.tripId) }
        assertTrue(w.maria.together.engine.load().of(w.tripId)!!.me in w.nikos.together.engine.load().of(w.tripId)!!.unreachable)
        w.nikos.together.checkMail(w.tripId)
        w.nikos.together.syncNow(w.tripId)
        assertTrue("Alcázar, again" in w.nikos.titles(w.tripId))
    }

    @Test fun joinedWithoutDriveViewsUntilDriveIsConnected() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld(nikosDrive = false)
        val invite = w.invite()
        // Google's page closed: Drive stays off, and the sheet says what it means.
        w.nikos.google.browser.mode = FakeAuthBrowser.Mode.Closed
        val links = AppLinks()
        app(w.nikos, links = links)
        waitFor("Join a friend's trip")
        links.open(invite.url)
        waitFor("Join “Spain 2027”")
        tap("Join")
        waitFor("You can see the trip. To add your changes, connect Google Drive.", timeout = 30_000)
        assertEquals("Spain 2027", w.nikos.trip(w.tripId)?.title)
        // Connect Drive: Google's page, then his copy and the mailbox.
        w.nikos.google.browser.mode = FakeAuthBrowser.Mode.Accept
        tap("Connect Drive")
        waitFor("Your changes reach the others by themselves.", timeout = 30_000)
        assertTrue(w.nikos.settings.driveConnected)
    }

    @Test fun aChangeIsSentByItselfAlsoAfterBeingOffline() = runBlocking<Unit> {
        val w = TogetherWorld()
        w.joined()
        w.maria.together.start(w.maria.writes)
        // The watch on the store starts from what it holds now (a moment, on its own thread).
        kotlinx.coroutines.delay(500)
        val item = w.maria.book.items(w.tripId).first()
        // Offline: the change waits, kept on the phone (it outlives the app being closed).
        w.maria.cloud.connected = false
        w.maria.book.saveItem(item.copy(title = "Alcázar with a guide"))
        w.maria.wrote()
        waitUntilTrue { w.maria.keep.read("together.sends") == w.tripId }
        w.maria.together.wentAway()
        waitUntilTrue { w.maria.runs.planned != null }
        // Back online and on screen: it goes, and the others get it.
        w.maria.cloud.connected = true
        w.maria.together.cameBack()
        waitUntilTrue { w.maria.keep.read("together.sends") == null }
        w.nikos.together.syncNow(w.tripId)
        assertTrue("Alcázar with a guide" in w.nikos.titles(w.tripId))
        // The background turn sends what waits, then catches up; the next turn is asked for 3 h on.
        w.maria.together.backgroundTurn()
        assertTrue((w.maria.runs.planned ?: 0) > com.justdataplease.boringtravel.time.nowMs() + 2 * 3_600_000L)
    }

    @Test fun leavingTheTrip() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        w.joined()
        val box = TripMail.box(w.tripId)!!
        runBlocking { waitUntilTrue { w.fs.mailbox(box).containsKey(w.nikos.uid) } }
        app(w.nikos, Place.Trip(w.tripId, TripTab.Plan))
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription("Trip options").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription("Trip options").onFirst().performClick()
        // A shared trip is left, not deleted.
        waitFor("Leave trip")
        assertFalse(has("Delete trip"))
        tap("Leave trip")
        waitFor("Leave this trip?")
        onNodeWithText(TogetherText.LEAVE).assertExists()
        tap("Leave")
        // The trip's screen closes; Trips no longer lists it.
        waitFor("Join a friend's trip")
        assertNull(w.nikos.book.trip(w.tripId))
        assertTrue(w.nikos.together.isLeft(w.tripId))
        // His folder is off its link; his mailbox entry is gone; the others see "Left the trip".
        val folder = w.nikos.cloud.items.values.single { it.isFolder && it.name == "Spain 2027" }
        assertFalse(folder.anyone)
        runBlocking { waitUntilTrue { !w.fs.mailbox(box).containsKey(w.nikos.uid) } }
        runBlocking { w.maria.together.syncNow(w.tripId) }
        assertTrue(runBlocking { w.maria.together.engine.load() }.of(w.tripId)!!.members.single { it.name == "Nikos" }.left)
        // Joining again carries on as the same member.
        val me = runBlocking { w.nikos.together.engine.load() }.former.getValue(w.tripId)
        runBlocking { w.nikos.together.open(w.invite()) }
        assertEquals(me, runBlocking { w.nikos.together.engine.load() }.of(w.tripId)!!.me)
        assertNotNull(w.nikos.book.trip(w.tripId))
    }

    @Test fun aSharedTripNobodyJoinedIsDeletedNormally() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        w.invite()
        app(w.maria, Place.Trip(w.tripId, TripTab.Plan))
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithContentDescription("Trip options").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription("Trip options").onFirst().performClick()
        // Shared (the menu says Leave), but nobody joined: the dialog deletes it, and sharing stops.
        tap("Leave trip")
        waitFor("Delete this trip?")
        tap("Delete")
        waitFor("Join a friend's trip")
        assertNull(w.maria.book.trip(w.tripId))
        assertNull(runBlocking { w.maria.together.engine.load() }.of(w.tripId))
        assertTrue(w.maria.book.snapshot.planItems.filter { it.tripId == w.tripId }.all { it.deletedAt != null })
    }

    @Test fun theMailboxSaysWhoJoined() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        val invite = w.invite()
        app(w.maria)
        waitFor("Join a friend's trip")
        // Nikos joins and posts his address; Maria never taps anything.
        runBlocking {
            w.nikos.together.open(invite)
            w.nikos.together.share(w.tripId, "Spain 2027")
            w.nikos.together.checkMail(w.tripId)
            w.maria.together.mail!!.look(w.tripId)
        }
        waitFor("Nikos joined Spain 2027", timeout = 30_000)
    }

    @Test fun backupsCarryTheSharedTripsAndARestoreBringsThemBack() = runBlocking<Unit> {
        val w = TogetherWorld()
        w.joined()
        val backups = BackupFiles(w.nikos.book, w.nikos.settings, w.nikos.files, "1.22.0")
        backups.sharing = w.nikos.together.backupState()
        val (path, _) = backups.saveFile()
        val zip = w.nikos.files.read(path)!!
        // A new phone: the backup restored, and the shared trip with it, as the same member.
        val phone = TogetherPhone("Nikos", "sub-nikos", w.fs, w.clock)
        val restored = BackupFiles(phone.book, phone.settings, phone.files, "1.22.0")
        restored.sharing = phone.together.backupState()
        phone.files.write("picked/backup.zip", zip)
        restored.restoreFile("picked/backup.zip")
        val mine = w.nikos.together.engine.load().of(w.tripId)!!
        val back = phone.together.engine.load().of(w.tripId)
        assertNotNull(back)
        assertEquals(mine.me, back.me)
        assertEquals(mine.members.map { it.id }, back.members.map { it.id })
        // The restore never rolls a shared trip back: the trips shared now only gain.
        assertTrue(w.tripId in phone.together.sharedNow())
        assertNotNull(BackupJson.decodeFromString(BackupData.serializer(), phone.book.export()).sharedTrips)
    }

    @Test fun theBadgeTheMenuAndAnExampleTrip() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        w.invite()
        app(w.maria, Place.Trip(w.tripId, TripTab.Plan))
        // Shared with nobody yet: "Shared"; the menu has Travel together and Leave trip.
        waitFor("Shared")
        onAllNodesWithContentDescription("Trip options").onFirst().performClick()
        waitFor("Travel together")
        assertTrue(has("Leave trip"))
        tap("Travel together")
        waitFor("Friends who open your link and join show up here.")
        // A friend joins: "+1".
        runBlocking {
            w.nikos.together.open(w.invite())
            w.nikos.together.share(w.tripId, "Spain 2027")
            w.nikos.together.checkMail(w.tripId)
            w.maria.together.mail!!.look(w.tripId)
        }
        waitFor("+1", timeout = 30_000)
    }

    @Test fun anExampleTripCantBeShared() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        val p = w.maria
        val example = p.book.newTrip("Argentina & Patagonia").copy(id = "demo-argentina")
        p.book.putRows(trips = listOf(example))
        p.settings.demoMode = true
        app(p, Place.Trip(example.id, TripTab.Plan))
        openTogether()
        waitFor("Example trips can't be shared. Make a trip of your own first.")
        assertFalse(has("Invite"))
    }

    @Test fun deleteAccountTakesTheMailboxEntriesOutFirst() = runBlocking<Unit> {
        val w = TogetherWorld()
        w.joined()
        val box = TripMail.box(w.tripId)!!
        waitUntilTrue { w.fs.mailbox(box).containsKey(w.maria.uid) }
        w.maria.community.beforeDelete = { w.maria.together.forgetMail() }
        w.maria.community.deleteAccount()
        assertFalse(w.fs.mailbox(box).containsKey(w.maria.uid))
        assertTrue(w.fs.mailbox(box).containsKey(w.nikos.uid))
    }

    @Test fun eachMemberChecksInWithANoteOfTheirOwn() = runBlocking<Unit> {
        val w = TogetherWorld(today = SAMPLE_TODAY.minusDays(10))
        w.joined()
        val day = w.maria.trip(w.tripId)!!.startDate!!
        // In a shared trip each member's check-in note is their own (its id has this phone's member id in it).
        val ids = listOf(w.maria, w.nikos).map { p ->
            val me = assertNotNull(p.together.memberOf(w.tripId))
            com.justdataplease.boringtravel.checkin.DailyCheckIn.entryId(w.tripId, day, me)
        }
        assertEquals(2, ids.toSet().size)
        assertNotEquals(com.justdataplease.boringtravel.checkin.DailyCheckIn.entryId(w.tripId, day), ids[0])
    }

    @Test fun leavingRemovesTheTripsCalendarEventsQuietly() = runBlocking<Unit> {
        val w = TogetherWorld()
        w.joined()
        val gone = mutableListOf<String>()
        val deletions = TripDeletions(w.nikos.book, w.nikos.files, w.nikos.together, calendarGone = { gone += it }, tripGone = {}, changed = {})
        assertTrue(deletions.leaves(w.tripId))
        deletions.deleteTrip(w.tripId)
        assertEquals(listOf(w.tripId), gone)
        // Left, not deleted with its contents: the plan and notes stay for joining again.
        assertNotNull(w.nikos.trip(w.tripId)?.deletedAt)
        assertTrue(w.nikos.book.snapshot.planItems.any { it.tripId == w.tripId && it.deletedAt == null })
    }

    /** Waits up to 20 s for [condition] (the background's sends and posts). */
    private suspend fun waitUntilTrue(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Still waiting" }
            kotlinx.coroutines.delay(50)
        }
    }
}

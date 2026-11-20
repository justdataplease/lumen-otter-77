package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.ai.ItineraryImport
import com.justdataplease.boringtravel.ai.SharedItinerary
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.together.ShareLink
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.together.TogetherText
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sharing into the app (round 27) driven on the desktop as a traveller would, with the share extension's folder in
 * memory: a share written there as the extension writes it, the app taking it and opening Android's own sheets. Add to
 * plan for a Maps place (the trip on now, today, Place; Add, then the Plan on that day), another trip, its day, Ideas and
 * Food, Food or Stay first by the name, a short Maps link's pin afterwards, No trips, only the examples, a blog's "Copy its
 * notes and pictures", a Maps list's "Add its N places" and "Add them to a new trip", "Nothing to add: share a link", a
 * join link, longer text, a Google Doc's link, pictures (10 at most) and a text file into the planner with every error of
 * Android's, "Reading the document" with Cancel and its 45 s, shares waiting for Welcome and kept after the app was
 * stopped, each share taken once and oldest first. Nothing reaches the network (the recorded answers).
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShareInOnDesktopTest {
    init {
        runBlocking {
            BtFontFiles.load()
            CountryOutlines.load()
        }
    }

    /** The App Group's folder, written from the test while the app reads it from its own thread. */
    private class LockedFiles(private val inner: MediaFiles = MemoryMediaFiles()) : MediaFiles by inner {
        private val lock = Any()
        override fun write(path: String, bytes: ByteArray): Boolean = synchronized(lock) { inner.write(path, bytes) }
        override fun read(path: String): ByteArray? = synchronized(lock) { inner.read(path) }
        override fun list(path: String): List<String> = synchronized(lock) { inner.list(path) }
        override fun delete(path: String) = synchronized(lock) { inner.delete(path) }
    }

    /** The share extension as the tests use it: each share written into [group] as ShareViewController.swift writes it. */
    private class Extension(files: MediaFiles) {
        val group = LockedFiles()
        val inbox = ShareInbox(group, files)
        private var n = 0

        fun share(text: String? = null, subject: String? = null, pictures: List<ByteArray> = emptyList(), file: Pair<String, ByteArray>? = null) {
            val name = "${1_795_000_000_000L + n}-test-${n++}"
            val names = pictures.mapIndexed { i, bytes -> "${i + 1}.jpg".also { group.write("${ShareInbox.FOLDER}/$name/$it", bytes) } }
            file?.let { (fileName, bytes) -> group.write("${ShareInbox.FOLDER}/$name/$fileName", bytes) }
            val manifest = ShareManifest(text = text, subject = subject, pictures = names, file = file?.first)
            // The share's own file last, as the extension writes it: the app takes only a share whose file is there.
            group.write("${ShareInbox.FOLDER}/$name.json", ShareInbox.manifestText(manifest).encodeToByteArray())
            inbox.ask()
        }
    }

    private fun ComposeUiTest.app(
        book: TripBook, ext: Extension, kit: MediaKit, settings: AppSettings = AppSettings.ready(), web: Web = Web.offline(),
        restored: String? = null, keep: ((String) -> Unit)? = null,
    ) {
        setContent {
            CompositionLocalProvider(LocalPhone provides Phone(paste = { null })) {
                BoringTravelApp(
                    book, settings, SAMPLE_TODAY, version = "1.22.1", media = kit, web = web, shares = ext.inbox,
                    restored = restored, keep = keep,
                )
            }
        }
    }

    private fun ComposeUiTest.has(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitFor(text: String, substring: Boolean = false, timeout: Long = 20_000) =
        waitUntil(timeoutMillis = timeout) { has(text, substring) }

    private fun ComposeUiTest.tap(text: String) = onAllNodesWithText(text).onFirst().performClick()

    /** A button of the sheet: its window comes after the screen's, so the last one with these words. */
    private fun ComposeUiTest.tapInSheet(text: String) = onAllNodesWithText(text).onLast().performClick()

    /** A chip of the sheet (a choice: it says whether it is the one picked), not a trip's ticket behind the sheet. */
    private val chipKey = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun ComposeUiTest.chip(text: String) = onNode(hasText(text) and chipKey)

    private fun ComposeUiTest.picked(text: String) = onNode(hasText(text) and isSelected()).assertExists()

    private fun ComposeUiTest.count(description: String) = onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    /** The item Add made in trip [tripId]: the one that wasn't there before ([ids]). */
    private fun ComposeUiTest.added(book: TripBook, tripId: String, ids: Set<String>): com.justdataplease.boringtravel.data.db.PlanItem {
        waitUntil(timeoutMillis = 10_000) { book.items(tripId).any { it.id !in ids } }
        return book.items(tripId).single { it.id !in ids }
    }

    private val yasaka = "Yasaka Shrine\n${Recorded.YASAKA_LONG}"

    @Test fun aMapsPlaceOpensAddToPlanAndGoesOnTodayInTheTripOnNow() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share(yasaka)
        app(book, ext, kit)
        waitFor("Add to plan")
        val kyoto = book.sections(SAMPLE_TODAY).now!!
        // The trip on now first; today, marked "·"; Place (the name says neither Food nor Stay). Eyebrows upper-case.
        assertTrue(has("TRIP"))
        assertTrue(has("DAY"))
        picked(kyoto.title)
        picked(Dates.dayShort(SAMPLE_TODAY) + " ·")
        picked("Place")
        chip("Food").assertExists()
        chip("Stay").assertExists()
        assertFalse(has("No trips of your own yet. Add it to a demo trip?"))
        // The link's name on its card (offline: the compact row with Google Maps under it).
        assertTrue(has("Yasaka Shrine"))
        val before = book.items(kyoto.id).filter { it.day == SAMPLE_TODAY }
        val ids = book.items(kyoto.id).map { it.id }.toSet()
        tapInSheet("Add")
        // (The Kyoto program has a Yasaka Shrine of its own, on its first day: the new item is the one not there before.)
        val item = added(book, kyoto.id, ids)
        // At the end of today, with the Maps place's pin; the Maps link is its Maps link, not its website.
        assertEquals("Yasaka Shrine", item.title)
        assertEquals(SAMPLE_TODAY, item.day)
        assertTrue(before.all { it.position < item.position })
        assertEquals(PlanKind.Place.id, item.kind)
        assertEquals(SharedLinks.parse(yasaka)!!.url, item.mapsUrl)
        assertNull(item.url)
        assertEquals(35.0036559, item.lat!!, 1e-7)
        assertEquals("Yasaka Shrine", item.placeName)
        // Then the trip's Plan, on that day (its name in the day's header).
        waitUntil(timeoutMillis = 10_000) { !has("Add to plan") }
        waitFor("Temples and tea", substring = true)
        assertTrue(has("Yasaka Shrine", substring = true))
        // Taken once: nothing is left in the folder.
        assertTrue(ext.group.list(ShareInbox.FOLDER).isEmpty())
    }

    @Test fun anotherTripItsDayIdeasAndFood() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share("Portara\nhttps://www.google.com/maps/place/Portara/@37.1081,25.3725,17z")
        app(book, ext, kit)
        waitFor("Add to plan")
        // Naxos: its first day, then the next one picked.
        chip("Naxos").performClick()
        // (Its first day is picked first: ShareTargetsTest.) The day after it, picked here.
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(Dates.dayShort(localDate(2027, 6, 18))) and chipKey).fetchSemanticsNodes().isNotEmpty() }
        chip(Dates.dayShort(localDate(2027, 6, 18))).performClick()
        picked(Dates.dayShort(localDate(2027, 6, 18)))
        tapInSheet("Add")
        val naxos = book.snapshot.trips.single { it.title == "Naxos" }
        waitUntil(timeoutMillis = 10_000) { book.items(naxos.id).any { it.title == "Portara" } }
        assertEquals(localDate(2027, 6, 18), book.items(naxos.id).single { it.title == "Portara" }.day)
        waitUntil(timeoutMillis = 10_000) { !has("Add to plan") }

        // A trip without dates: Ideas, and only Ideas; Food picked by hand.
        ext.share("Lunch place\nhttps://www.google.com/maps/place/Kaffivagninn/@64.1516,-21.9440,17z")
        waitFor("Add to plan")
        chip("Iceland ring road").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Ideas") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        chip("Food").performClick()
        picked("Food")
        tapInSheet("Add")
        val iceland = book.snapshot.trips.single { it.title == "Iceland ring road" }
        waitUntil(timeoutMillis = 10_000) { book.items(iceland.id).any { it.title == "Lunch place" } }
        val idea = book.items(iceland.id).single { it.title == "Lunch place" }
        assertNull(idea.day)
        assertEquals(PlanKind.Food.id, idea.kind)
        // The trip opens on its Plan, the Ideas in view.
        waitUntil(timeoutMillis = 10_000) { !has("Add to plan") }
        waitFor("Lunch place", substring = true)
    }

    @Test fun foodOrStayComesFirstWhenTheNameSaysSo() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        // A browser's page: its address, and the page's title as the subject.
        ext.share("https://www.granviakyoto.com/", subject = "Hotel Granvia Kyoto")
        app(book, ext, kit)
        waitFor("Add to plan")
        picked("Stay")
        tapInSheet("Add")
        val kyoto = book.sections(SAMPLE_TODAY).now!!
        waitUntil(timeoutMillis = 10_000) { book.items(kyoto.id).any { it.title == "Hotel Granvia Kyoto" } }
        val stay = book.items(kyoto.id).single { it.title == "Hotel Granvia Kyoto" }
        assertEquals(PlanKind.Stay.id, stay.kind)
        // A site's link is its website.
        assertEquals("https://www.granviakyoto.com/", stay.url)
        assertNull(stay.mapsUrl)
    }

    /** A network that holds the short Maps link's answer until [gate] opens. */
    private class GatedNet(private val inner: Net, private val gate: CompletableDeferred<Unit>, private val held: (String) -> Boolean) : Net {
        override fun online(): Boolean = inner.online()
        override suspend fun get(request: NetRequest): NetAnswer {
            if (held(request.url)) gate.await()
            return inner.get(request)
        }
    }

    @Test fun aShortMapsLinkGetsItsPinAfterAdd() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        val gate = CompletableDeferred<Unit>()
        val web = Recorded.web(GatedNet(Recorded.net(), gate) { it == Recorded.YASAKA_SHORT })
        ext.share("Yasaka Shrine\n${Recorded.YASAKA_SHORT}")
        app(book, ext, kit, web = web)
        waitFor("Add to plan")
        val kyoto = book.sections(SAMPLE_TODAY).now!!
        val ids = book.items(kyoto.id).map { it.id }.toSet()
        tapInSheet("Add")
        val id = added(book, kyoto.id, ids).id
        // Saved at once without a pin: the short link hasn't answered yet.
        assertNull(book.item(id)!!.lat)
        gate.complete(Unit)
        waitUntil(timeoutMillis = 20_000) { book.item(id)!!.lat != null }
        val item = book.item(id)!!
        assertEquals(Recorded.YASAKA.lat, item.lat!!, 1e-6)
        assertEquals(Recorded.YASAKA_SHORT, item.mapsUrl)
        // The name it was shared with stays.
        assertEquals("Yasaka Shrine", item.title)
    }

    @Test fun noTripsOffersNewTrip() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = TripBook(MemoryTripFile())
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share(yasaka)
        app(book, ext, kit)
        waitFor("No trips yet. Start one, then share the link again.")
        assertFalse(has("TRIP"))
        tapInSheet("New trip")
        waitUntil(timeoutMillis = 10_000) { !has("No trips yet. Start one, then share the link again.") }
        waitFor("Make it myself")
        assertTrue(book.snapshot.trips.isEmpty())
    }

    @Test fun onlyTheExamplesAreOfferedAndSaidSo() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = TripBook(MemoryTripFile())
        val kit = MediaKit.memory()
        val settings = AppSettings.ready()
        runBlocking { DemoMode(book, settings, kit.files).set(true, SAMPLE_TODAY) }
        val ext = Extension(kit.files)
        ext.share(yasaka)
        app(book, ext, kit, settings = settings)
        waitFor("No trips of your own yet. Add it to a demo trip?")
        // The example on now first.
        val argentina = book.snapshot.trips.single { it.isDemo && it.isCurrent(SAMPLE_TODAY) }
        picked(argentina.title)
    }

    @Test fun aBlogOffersCopyItsNotesAndPictures() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share("https://myblog.tumblr.com/")
        app(book, ext, kit)
        waitFor("Copy its notes and pictures")
        // A place to add as well: the sheet is the usual one.
        assertTrue(has("Add"))
        tap("Copy its notes and pictures")
        // Copy from a link with the address; a blog waits for Read.
        waitFor("Only public pages are read. Nothing is copied until you choose.")
        assertTrue(has("myblog.tumblr.com", substring = true))
        // A Google Maps place is a place, not a site.
        assertFalse(com.justdataplease.boringtravel.site.SiteLinks.looksLikeSite(Recorded.YASAKA_LONG))
    }

    @Test fun aMapsListOffersItsPlaces() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share(Recorded.LIST_SHORT)
        app(book, ext, kit, web = Recorded.web())
        waitFor("A Google Maps list of 3 places. Each becomes an idea in the plan.")
        // The list's name on its card; the trip, and no day or kind to choose.
        assertTrue(has("Seville"))
        assertTrue(has("Add its 3 places"))
        assertTrue(has("Add them to a new trip"))
        assertFalse(has("DAY"))
        assertFalse(has("Stay"))
        tap("Add them to a new trip")
        // Copy from a link reads the list at once.
        waitFor("3 places from a Google Maps list")
    }

    @Test fun aMapsListIntoTheTripOnNow() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share(Recorded.LIST_SHORT)
        app(book, ext, kit, web = Recorded.web())
        waitFor("Add its 3 places")
        tap("Add its 3 places")
        waitFor("3 places from a Google Maps list")
        val kyoto = book.sections(SAMPLE_TODAY).now!!
        // Copy from a link with the trip on now chosen as where they go.
        assertTrue(has(kyoto.title, substring = true))
    }

    @Test fun aMapsListWithNoTripsMakesOne() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = TripBook(MemoryTripFile())
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share(Recorded.LIST_SHORT)
        app(book, ext, kit, web = Recorded.web())
        waitFor("Add its 3 places to a new trip")
        assertFalse(has("No trips yet. Start one, then share the link again."))
        tap("Add its 3 places to a new trip")
        waitFor("3 places from a Google Maps list")
    }

    @Test fun aFewWordsSayNothingToAdd() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        app(book, ext, kit)
        waitFor("Trip ideas")
        ext.share("see you in Seville")
        waitFor("Nothing to add: share a link")
        assertFalse(has("Add to plan"))
        assertFalse(has("AI planner"))
    }

    @Test fun aJoinLinkSharedAsTextOpensJoin() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val w = TogetherWorld()
        val invite = w.invite()
        val ext = Extension(w.nikos.files)
        // As a chat app shares Maria's message.
        ext.share(TogetherText.invite(invite))
        setContent {
            CompositionLocalProvider(LocalPhone provides Phone()) {
                BoringTravelApp(
                    w.nikos.book, w.nikos.settings, SAMPLE_TODAY, version = "1.22.1", media = w.nikos.kit,
                    google = w.nikos.world.account, community = w.nikos.community, together = w.nikos.together, shares = ext.inbox,
                )
            }
        }
        waitFor("Join “Spain 2027”")
        assertFalse(has("Add to plan"))
        assertEquals(invite, ShareIntake.of(TogetherText.invite(invite), null, emptyList(), null).let { (it as ShareArrival.Join).link })
    }

    @Test fun longerTextAndAGoogleDocGoToThePlanner() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        val trip = "Day 1: Lisbon, Alfama and the castle\nDay 2: Sintra, Pena Palace\nDay 3: Belém, the tower and pastéis\n" +
            "Day 4: Cascais by train\nDay 5: fly home https://www.visitlisboa.com/"
        ext.share(trip)
        app(book, ext, kit)
        // The planner as a new trip, the text waiting in the box, nothing sent.
        waitFor("Make a new trip")
        assertTrue(has(trip))
        assertFalse(has("Reading your message"))
        onAllNodesWithContentDescription("Back").onFirst().performClick()
        waitFor("Trip ideas")
        ext.share("https://docs.google.com/document/d/1AbCdEfGhIjKlMnOp/edit?usp=sharing")
        waitFor("Make a new trip")
        // A Google Doc's link comes as a link attached to the message.
        waitFor("docs.google.com")
        assertEquals(1, count("Remove link"))
    }

    @Test fun picturesGoToThePlannerTenAtMost() = runDesktopComposeUiTest(width = 900, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share(pictures = List(12) { samplePhoto(Scene.Sea, 300, 400) })
        app(book, ext, kit)
        waitFor("Make a new trip")
        waitUntil(timeoutMillis = 10_000) { count("Remove picture") == 10 }
        // Copied under the app's files, where the planner reads them; the extension's folder is empty.
        assertEquals(10, (kit.files as MemoryMediaFiles).paths.count { it.startsWith("picked/shared-") })
        assertTrue(ext.group.list(ShareInbox.FOLDER).isEmpty())
        assertFalse(has("Up to 10 pictures in one message."))
    }

    @Test fun aTextFileIsReadIntoTheBoxAndItsCopyGoes() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share(file = "document.txt" to "﻿Lisbon in May\r\nDay 1 Alfama\r\nDay 2 Sintra".encodeToByteArray())
        app(book, ext, kit)
        waitFor("Make a new trip")
        waitFor("Lisbon in May\nDay 1 Alfama\nDay 2 Sintra")
        assertFalse(has("Reading the document"))
        waitUntil(timeoutMillis = 10_000) { (kit.files as MemoryMediaFiles).paths.none { it.startsWith("picked/shared-") } }
    }

    @Test fun aFileThatIsntPlainTextSaysSo() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share(file = "document.pdf" to "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n".encodeToByteArray())
        app(book, ext, kit)
        waitFor("Choose a plain text (.txt) file. Export PDFs and Word documents to text first.")
    }

    /** The planner's chat on the app's own kit (no model is ever asked), for the document's errors. */
    private fun chat(files: MediaFiles, scope: CoroutineScope): PlannerChat {
        val book = TripBook(MemoryTripFile())
        val kit = PlannerKit(book, AppSettings.ready(), Web.offline(), files, PlannerPhone(), "1.22.1")
        return PlannerChat(KitPlannerSide(kit, book, files), null, scope)
    }

    private fun waitIdle(chat: PlannerChat) {
        val until = System.currentTimeMillis() + 10_000
        while (chat.importing && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test fun everyDocumentErrorIsAndroids() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val files = MemoryMediaFiles()
        fun read(bytes: ByteArray?): PlannerChat {
            val path = "picked/shared-x-1.txt"
            if (bytes != null) files.write(path, bytes)
            return chat(files, scope).also {
                it.receiveShared(null, path)
                waitIdle(it)
                // The copy goes once it was read, whatever it said.
                assertNull(files.read(path))
            }
        }
        assertEquals("Save the document as UTF-8 plain text (.txt), then open it again.", read(byteArrayOf(0xC3.toByte(), 0x28)).importError)
        assertEquals("This document has no text to import.", read("  \n ".encodeToByteArray()).importError)
        assertEquals(ItineraryImport.TOO_LARGE, read(ByteArray(ItineraryImport.MAX_BYTES + 1) { 'a'.code.toByte() }).importError)
        assertEquals("Choose a plain text (.txt) file. Export PDFs and Word documents to text first.", read("PK\u0003\u0004word/document.xml".encodeToByteArray()).importError)
        assertEquals(PlannerChat.DOCUMENT_NOT_OPENED, read(null).importError)
        // UTF-16 with its mark is text.
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Naxos".flatMap { listOf(it.code.toByte(), 0) }.toByteArray()
        val ok = read(utf16)
        assertNull(ok.importError)
        assertEquals("Naxos", ok.input)
        // Shared text goes in the box, after anything typed; too long, Android's words, nothing in the box.
        val typed = chat(files, scope)
        typed.updateInput("Our trip:")
        typed.receiveShared("Day 1 Lisbon", null)
        assertEquals("Our trip:\n\nDay 1 Lisbon", typed.input)
        val long = chat(files, scope)
        long.receiveShared("x".repeat(ItineraryImport.MAX_CHARS + 1), null)
        assertEquals(ItineraryImport.TOO_LARGE, long.importError)
        assertEquals("", long.input)
        // A share is taken once per chat.
        typed.receiveShared("Day 2 Sintra", null)
        assertEquals("Our trip:\n\nDay 1 Lisbon", typed.input)
        scope.cancel()
    }

    /** The app's kit with a document that reads only when [gate] opens. */
    private class SlowDocuments(private val inner: PlannerSide, private val gate: CompletableDeferred<Unit>) : PlannerSide by inner {
        override suspend fun readDocument(path: String): String {
            gate.await()
            return inner.readDocument(path)
        }
    }

    @Test fun readingTheDocumentHasCancelAndAnEnd() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = TripBook(MemoryTripFile())
        val files = MemoryMediaFiles()
        files.write("picked/shared-a-1.txt", "Day 1 Lisbon".encodeToByteArray())
        val kit = PlannerKit(book, AppSettings.ready(), Web.offline(), files, PlannerPhone(), "1.22.1")
        val side = SlowDocuments(KitPlannerSide(kit, book, files), CompletableDeferred())
        setContent {
            CompositionLocalProvider(LocalPlannerKit provides kit, LocalMediaKit provides MediaKit(files), LocalPhone provides Phone(paste = { null })) {
                com.justdataplease.boringtravel.ui.theme.BoringTravelTheme(com.justdataplease.boringtravel.ui.theme.Look.Default) {
                    PlannerScreen(
                        side, null, start = null, dictationLanguage = null, onBack = {}, onAiSettings = {}, onCopySite = { _, _ -> },
                        onSaved = { _, _ -> }, onReport = { _, _ -> }, shared = SharedItinerary(null, "picked/shared-a-1.txt"),
                    )
                }
            }
        }
        waitFor("Reading the document")
        tap("Cancel")
        waitUntil(timeoutMillis = 10_000) { !has("Reading the document") }
        // Cancel says nothing and leaves the box as it was.
        assertFalse(has("Couldn't read this document. Paste its text instead."))
        assertFalse(has("Day 1 Lisbon"))
    }

    @Test fun aDocumentThatTakesTooLongSaysSo() {
        val dispatcher = StandardTestDispatcher()
        val test = TestScope(dispatcher)
        val book = TripBook(MemoryTripFile())
        val files = MemoryMediaFiles()
        val kit = PlannerKit(book, AppSettings.ready(), Web.offline(), files, PlannerPhone(), "1.22.1")
        val chat = PlannerChat(SlowDocuments(KitPlannerSide(kit, book, files), CompletableDeferred()), null, test)
        chat.receiveShared(null, "picked/shared-b-1.txt")
        test.runCurrent()
        assertTrue(chat.importing)
        test.advanceTimeBy(PlannerChat.DOCUMENT_TIMEOUT_MS + 1)
        test.runCurrent()
        assertFalse(chat.importing)
        assertEquals("This document took too long to open. Try again, or paste its text.", chat.importError)
    }

    @Test fun sharesWaitForWelcome() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val settings = AppSettings.ready().apply { onboardingDone = false }
        val ext = Extension(kit.files)
        var kept: String? = null
        ext.share(yasaka)
        app(book, ext, kit, settings = settings, keep = { kept = it })
        waitFor("Continue")
        // Taken from the folder at once, kept with the window while it waits, and not shown over Welcome.
        waitUntil(timeoutMillis = 10_000) { kept?.let(AppRestore::sharedPlaceOf) != null }
        assertEquals(ShareRoute.Place(SharedLinks.parse(yasaka)!!), AppRestore.sharedPlaceOf(kept))
        assertTrue(ext.group.list(ShareInbox.FOLDER).isEmpty())
        assertFalse(has("Add to plan"))
        tap("Continue")
        waitFor("Add to plan")
        // Once its sheet closes, nothing waits any more.
        tapInSheet("Add")
        waitUntil(timeoutMillis = 10_000) { kept?.let(AppRestore::sharedPlaceOf) == null && !has("Add to plan") }
    }

    @Test fun aShareKeptWhenTheAppWasStoppedComesBack() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        // iOS closed the app while Add to plan waited (SwiftUI's scene storage kept this).
        val restored = AppRestore.encodeKey(
            AppStack(listOf(Place.Trips)), TripTab.Plan, null, emptyMap(),
            place = ShareRoute.Place(SharedLink(SharedLinks.parse(yasaka)!!.url, "Yasaka Shrine")),
        )
        app(book, ext, kit, restored = restored)
        waitFor("Add to plan")
        picked(book.sections(SAMPLE_TODAY).now!!.title)
    }

    @Test fun anImportKeptWhenTheAppWasStoppedComesBack() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        val restored = AppRestore.encodeKey(
            AppStack(listOf(Place.Trips)), TripTab.Plan, null, emptyMap(), imported = SharedItinerary("Day 1 Lisbon\nDay 2 Sintra\nDay 3 Porto"),
        )
        app(book, ext, kit, restored = restored)
        waitFor("Make a new trip")
        waitFor("Day 1 Lisbon\nDay 2 Sintra\nDay 3 Porto")
    }

    @Test fun eachShareIsTakenOnceOldestFirstAndTheNewerOneWins() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        ext.share("Tsukiji Outer Market\nhttps://www.google.com/maps/place/Tsukiji+Outer+Market/@35.6654,139.7707,17z")
        ext.share(yasaka)
        // A share the extension never finished (no share file): not taken, left for the extension to clear.
        ext.group.write("${ShareInbox.FOLDER}/1795000009999-half/1.jpg", byteArrayOf(1, 2, 3))
        app(book, ext, kit)
        waitFor("Add to plan")
        // Two links: the newer one is the one offered (Android: a new intent replaces the waiting one).
        waitFor("Yasaka Shrine")
        assertFalse(has("Tsukiji Outer Market"))
        assertEquals(listOf("1.jpg"), ext.group.list("${ShareInbox.FOLDER}/1795000009999-half"))
        assertTrue(ext.group.list(ShareInbox.FOLDER).none { it.endsWith(".json") })
    }

    @Test fun theOpenTripsPlanOpensOnTheDayAsked() = runDesktopComposeUiTest(width = 440, height = 1600) {
        // The trip already open (on its Journal): Add to plan brings it back on its Plan, on the day picked.
        val book = sampleBook()
        val kit = MediaKit.memory()
        val ext = Extension(kit.files)
        val kyoto = book.sections(SAMPLE_TODAY).now!!
        setContent {
            CompositionLocalProvider(LocalPhone provides Phone(paste = { null })) {
                BoringTravelApp(book, AppSettings.ready(), SAMPLE_TODAY, startAt = listOf(Place.Trip(kyoto.id, TripTab.Journal)), version = "1.22.1", media = kit, shares = ext.inbox)
            }
        }
        waitFor("First night")
        ext.share(yasaka)
        waitFor("Add to plan")
        val ids = book.items(kyoto.id).map { it.id }.toSet()
        tapInSheet("Add")
        waitUntil(timeoutMillis = 10_000) { !has("Add to plan") }
        waitFor("Temples and tea", substring = true)
        assertNotNull(added(book, kyoto.id, ids))
    }
}

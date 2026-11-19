package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.ai.own.MemoryKeyValues
import com.justdataplease.boringtravel.ai.own.OwnAiProvider
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.site.SiteImportState
import com.justdataplease.boringtravel.site.SiteNotes
import com.justdataplease.boringtravel.site.SitePost
import com.justdataplease.boringtravel.ui.nav.HomeTab
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Copy from a link on this phone, over the made-up blog's recorded pages ([SiteSamples]) and the built-in planner's
 * recorded sorting, never the network or a model: a blog read, sorted into trips by the AI and copied with its words,
 * pictures and places, through the screens; copying again doubles nothing; one post's link; the dates without AI; the
 * own key and the day's count; a Google Doc with its pictures inside; the pictures kept as Android keeps them; the ids
 * Android makes, byte for byte; the phone's background time while a copy runs; the copy going on after its screen is
 * left. What was kept still reads as a backup's data.json.
 */
@OptIn(ExperimentalTestApi::class)
class SiteCopyOnDesktopTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking { BtFontFiles.load() }
    }

    private fun ui(
        book: TripBook,
        vararg at: Place,
        net: Net = PlannerNet(SiteSamples.net()).answer(RecordedAi::isBuiltIn, SiteSamples.modelText(SiteSamples.SORTED)),
        files: MemoryMediaFiles = MemoryMediaFiles(),
        settings: AppSettings = AppSettings.ready(),
        ai: PlannerPhone = PlannerPhone(firebase = RecordedAi.FIREBASE),
        work: BackgroundWork = NoBackgroundWork,
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 440, height = 2400) {
        setContent {
            CompositionLocalProvider(LocalBackgroundWork provides work, LocalPhone provides Phone(paste = { null })) {
                BoringTravelApp(
                    book, settings, today, startAt = at.toList(), media = MediaKit(files), web = Recorded.web(net), ai = ai,
                    siteCopy = SiteCopySetup(pause = {}, mapGapMs = 0),
                )
            }
        }
        block()
        waitForIdle()
    }

    private fun ComposeUiTest.shows(text: String, timeout: Long = 15_000) =
        waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** Types [text] into the address. */
    private fun ComposeUiTest.address(text: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasSetTextAction())[0].performTextInput(text)
    }

    private fun assertKeptAsABackup(file: MemoryTripFile, book: TripBook) {
        val data = BackupJson.decodeFromString(BackupData.serializer(), assertNotNull(file.read()))
        assertEquals(book.snapshot, data.toSnapshot())
    }

    private fun uuid(key: String): String = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()

    @Test
    fun aBlogIsReadSortedByTheAiAndCopiedIntoTripsWithItsWordsPicturesAndPlaces() {
        val file = MemoryTripFile()
        val book = TripBook(file, links = LinkPlaces::sortLinks)
        val files = MemoryMediaFiles()
        val settings = AppSettings.ready()
        // Me › Import trips from a link.
        ui(book, Place.Home(HomeTab.Me), files = files, settings = settings) {
            onNodeWithText("Import trips from a link").performScrollTo().performClick()
            shows("Copy from a link")
            onNodeWithText("Only public pages are read. Nothing is copied until you choose.").assertExists()
            address(SiteSamples.HOST)
            onNodeWithText("Read").performClick()
            shows("5 notes, 7 pictures from ${SiteSamples.HOST}")
            onNodeWithText("Organised by AI").assertExists()
            onNodeWithText("Crete, spring 2025").assertExists()
            onNodeWithText("Lisbon, autumn 2025").assertExists()
            onNodeWithText("3–9 Apr 2025 · 3 notes · 4 pictures").assertExists()
            onNodeWithText("Chania · Samaria Gorge · Balos Lagoon").assertExists()
            onNodeWithText("One trip for everything").assertExists()
            onNodeWithText("Add places").assertExists()
            onNodeWithText("Copy only what is yours: your own words and pictures.").assertExists()
            // A trip's notes unfolded.
            onNodeWithText("Crete, spring 2025").performClick()
            shows("Samaria Gorge in one long day")
            onNodeWithText("6 Apr 2025 · 2 pictures").assertExists()
            onNodeWithText("Copy 5 notes").performScrollTo().performClick()
            shows("5 notes and 7 pictures into 2 trips.")
            onNodeWithText("5 places added to the plan, with their notes.").assertExists()
            onNodeWithText("Copied").assertExists()
        }
        val trips = book.trips(today)
        assertEquals(setOf("Crete, spring 2025", "Lisbon, autumn 2025"), trips.map { it.title }.toSet())
        val crete = trips.single { it.title == "Crete, spring 2025" }
        assertEquals("2025-04-03" to "2025-04-09", crete.startDate.toString() to crete.endDate.toString())
        // Each note: the post's own words, its id Android's (UUID v3 of its address), its place pinned.
        val notes = book.entries(crete.id)
        assertEquals(SiteSamples.POSTS.take(3).map { it.title }, notes.map { it.title })
        val first = SiteSamples.POSTS.first()
        assertEquals(uuid("boringtravel-site-post:" + first.url.trimEnd('/')), notes[0].id)
        val paragraphs = EntryDoc.parse(notes[0].body).blocks.filterIsInstance<com.justdataplease.boringtravel.doc.Block.Paragraph>().map { it.html }
        assertEquals(listOf("We landed late and walked straight down to the <b>Venetian harbour</b>.", "Grilled octopus by the lighthouse, then bed.", "#travel"), paragraphs)
        assertEquals("The lighthouse at dusk", EntryDoc.parse(notes[0].body).blocks.filterIsInstance<com.justdataplease.boringtravel.doc.Block.Photo>().single().caption)
        assertEquals("Chania", notes[0].placeName)
        assertEquals(35.5138, notes[0].lat)
        // The pictures: in the app's files, their rows Android's (ids from the picture's address and the note's id).
        val pictureId = uuid("boringtravel-site-picture:${notes[0].id}:${first.pictureUrl("chania.jpg")}")
        val media = assertNotNull(book.media(pictureId))
        assertEquals("media/$pictureId.jpg", media.localPath)
        assertEquals(600 to 400, media.width to media.height)
        assertNotNull(files.read(media.localPath))
        assertEquals(7, book.snapshot.media.count { it.deletedAt == null })
        // The places: plan items on their notes' days, ticked (the days are past), named as the notes' places.
        val items = book.items(crete.id)
        assertEquals(listOf("Chania", "Samaria Gorge", "Balos Lagoon"), items.map { it.title })
        assertEquals(uuid("boringtravel-site-place:${crete.id}:chania"), items[0].id)
        assertTrue(items.all { it.done && it.source == "import" })
        assertEquals("2025-04-06", items[1].day.toString())
        // One request to the built-in planner, one of the day's answers.
        assertEquals(1, settings.plannerUsage.answersToday())
        assertKeptAsABackup(file, book)

        // The same blog again: nothing new, and said so.
        ui(book, Place.CopySite(), files = files) {
            address(SiteSamples.HOME)
            onNodeWithText("Read").performClick()
            shows("All 5 posts from ${SiteSamples.HOST} are already copied.")
            onNodeWithText("Start over").assertExists()
        }
        assertEquals(5, book.snapshot.entries.size)
    }

    @Test
    fun aLinkToOnePostCopiesThatPostOnlyAndTheWholeBlogIsOneTapAway() {
        val book = TripBook(MemoryTripFile())
        val naxos = book.newTrip("Naxos", today, today.plusDays(3)).id
        val net = PlannerNet(SiteSamples.net()).answer(RecordedAi::isBuiltIn, SiteSamples.modelText(SiteSamples.ONE_SORTED), SiteSamples.modelText(SiteSamples.SORTED))
        // The planner's offer: the address and the trip filled in; a blog waits for Read.
        ui(book, Place.Trip(naxos), Place.CopySite(naxos, SiteSamples.ONE_POST), net = net) {
            onNodeWithText(SiteSamples.ONE_POST).assertExists()
            onNodeWithText("Read").performClick()
            shows("1 note, 1 picture from ${SiteSamples.HOST}")
            onNodeWithText("Only the post at this link.").assertExists()
            onNodeWithText("Adds 1 note to Naxos").assertExists()
            onNodeWithText("Copy the whole blog instead").assertExists()
            onNodeWithText("Add 1 note").performScrollTo().performClick()
            shows("1 note and 1 picture into 1 trip.")
            shows("1 note added")
        }
        val notes = book.entries(naxos)
        assertEquals(listOf("Balos lagoon at last"), notes.map { it.title })
        // The trip keeps its days; the place goes in as an idea (its day isn't one of the trip's).
        assertEquals(today, book.trip(naxos)?.startDate)
        assertEquals(listOf("Balos Lagoon"), book.ideas(naxos).map { it.title })
        ui(book, Place.CopySite(naxos, SiteSamples.ONE_POST), net = net) {
            onNodeWithText("Read").performClick()
            shows("This post is already copied.")
            onNodeWithText("Copy the whole blog").performClick()
            shows("4 notes, 6 pictures from ${SiteSamples.HOST}")
            onNodeWithText("1 already copied").assertExists()
        }
    }

    @Test
    fun withoutAiTheDatesSortThePostsAndASilentAiCanBeAskedAgain() {
        // No AI in this build and no own key: by dates, no places.
        ui(TripBook(MemoryTripFile()), Place.CopySite(), ai = PlannerPhone()) {
            address(SiteSamples.HOME)
            onNodeWithText("Read").performClick()
            shows("Grouped by dates")
            onNodeWithText("Slow Roads · Apr 2025").assertExists()
            onNodeWithText("Slow Roads · Oct 2025").assertExists()
            onNodeWithText("Notes and pictures").assertDoesNotExist()
        }
        // The AI answers nothing usable, then sorts when asked again (the blog isn't read again).
        val net = PlannerNet(SiteSamples.net()).answer(RecordedAi::isBuiltIn, SiteSamples.modelText("Sorry."), SiteSamples.modelText(SiteSamples.SORTED))
        ui(TripBook(MemoryTripFile()), Place.CopySite(), net = net) {
            address(SiteSamples.HOME)
            onNodeWithText("Read").performClick()
            shows("The AI didn't answer, so no places were found.")
            val pages = net.asked.count { it.url.startsWith("https://${SiteSamples.HOST}") }
            onNodeWithText("Ask the AI again").performClick()
            shows("Organised by AI")
            assertEquals(pages, net.asked.count { it.url.startsWith("https://${SiteSamples.HOST}") })
            assertEquals(2, net.builtInAsked.size)
        }
    }

    @Test
    fun theOwnKeySortsWithoutCountingAndTheDaysLimitLeavesItToTheDates() = runBlocking {
        // The built-in planner at the day's limit: the sorting isn't asked, the dates decide and it says so.
        val settings = AppSettings.ready()
        val case = SiteCase(settings = settings)
        repeat(40) { case.kit.recordAnswer(startsPlan = false) }
        val limited = case.read(SiteSamples.HOME) as SiteImportState.Ready
        assertTrue(limited.aiSilent)
        assertTrue(case.net.builtInAsked.isEmpty())

        // A Gemini key of the traveller's own: its model sorts, and the day's count stays.
        val keys = MemoryKeyValues()
        val net = PlannerNet(SiteSamples.net()).answer(RecordedAi::isGeminiGenerate, SiteSamples.modelText(SiteSamples.SORTED))
        val own = SiteCase(net = net, settings = AppSettings.ready())
        own.kit.ownAi.saveKey(OwnAiProvider.Gemini, RecordedAi.OWN_KEY)
        own.kit.ownAi.setModel(OwnAiProvider.Gemini, "gemini-flash-latest")
        own.kit.ownAi.setProvider(OwnAiProvider.Gemini)
        own.kit.ownAi.setUseOwnKey(true)
        assertNotNull(own.kit.ownAi.engine())
        val sorted = own.read(SiteSamples.HOME) as SiteImportState.Ready
        assertEquals(listOf("Crete, spring 2025", "Lisbon, autumn 2025"), sorted.trips.map { it.title })
        assertEquals(1, net.asked.count { RecordedAi.isGeminiGenerate(it.url) })
        assertTrue(net.builtInAsked.isEmpty())
        assertEquals(0, own.settings.plannerUsage.answersToday())
        // The request carries the posts as a numbered list and the answer's schema.
        val body = net.asked.first { RecordedAi.isGeminiGenerate(it.url) }.body.orEmpty()
        assertTrue("1 | 2025-04-03 | Chania harbour, first evening" in body, body.take(2000))
        assertTrue("responseSchema" in body || "response_schema" in body || "responseJsonSchema" in body, body.take(600))
        own.scope.cancel()
        case.scope.cancel()
    }

    @Test
    fun aGoogleDocWithItsPicturesInsideBecomesANoteASection() = runBlocking {
        val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ab"
        val png = samplePng()
        val export = """
            <html><head><style>.c1{font-weight:700}</style></head><body>
            <p class="title"><span>Crete, slowly</span></p>
            <h2>3 April 2016 · Chania</h2><p><span class="c1">Windy</span> harbour.</p>
            <p><img src="data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(png)}"></p>
            <h2>4 April</h2><p>Up to the gorge.</p>
            </body></html>
        """.trimIndent()
        val docNet = object : Net {
            override suspend fun get(request: NetRequest): NetAnswer =
                if (request.url == "https://docs.google.com/document/d/$id/export?format=html") {
                    NetAnswer(200, request.url, mapOf("Content-Type" to "text/html; charset=utf-8", "Content-Disposition" to "attachment; filename=\"Crete diary.html\""), export.encodeToByteArray())
                } else NetAnswer(404, request.url)
        }
        val folder = Files.createTempDirectory("site-pictures").toFile()
        try {
            val book = TripBook(MemoryTripFile())
            val files = MemoryMediaFiles()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val web = Recorded.web(docNet)
            val importer = siteImporter(scope, book, files, web, null, setup = SiteCopySetup(pause = {}, mapGapMs = 0, inlineDir = File(folder.resolve("inline").path)))
            importer.read("https://docs.google.com/document/d/$id/edit?usp=sharing")
            val ready = waitFor(importer) { it is SiteImportState.Ready || it is SiteImportState.Failed } as SiteImportState.Ready
            assertEquals("docs.google.com", ready.host)
            assertEquals(listOf("3 April 2016 · Chania", "4 April"), ready.trips.flatMap { it.posts }.map { it.title })
            importer.copy()
            val done = waitFor(importer) { it is SiteImportState.Done } as SiteImportState.Done
            assertEquals(2, done.notes)
            assertEquals(1, done.pictures)
            // The picture came inside the document: copied from its file, a PNG kept as it is.
            val photo = book.snapshot.media.single()
            assertEquals("image/png", photo.mime)
            assertTrue(png.contentEquals(files.read(photo.localPath)))
            assertEquals(Sha256.hex(png), photo.sha256)
            // Its waiting file is gone once the copy is over.
            waitUntilTrue { !folder.resolve("inline").exists() || folder.resolve("inline").listFiles().isNullOrEmpty() }
            scope.cancel()
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun picturesAreKeptAsAndroidKeepsThem() = runBlocking {
        val png = samplePng()
        val bmp = sampleBmp()
        val jpeg = samplePhoto(Scene.Sea, 300, 200)
        fun picture(type: String, bytes: ByteArray) = { r: NetRequest -> NetAnswer(200, r.url, mapOf("Content-Type" to type), bytes) }
        val net = RecordedNet(
            listOf(
                { u: String -> u.endsWith("/a.png") } to picture("image/png", png),
                { u: String -> u.endsWith("/b.bmp") } to picture("image/bmp", bmp),
                { u: String -> u.endsWith("/c.svg") } to picture("image/svg+xml", "<svg/>".encodeToByteArray()),
                { u: String -> u.endsWith("/d.jpg") } to picture("image/jpeg", jpeg),
                { u: String -> u.endsWith("/e.html") } to picture("text/html", "<p>no</p>".encodeToByteArray()),
                { u: String -> u.endsWith("/big.jpg") } to { r: NetRequest -> NetAnswer(200, r.url, mapOf("Content-Type" to "image/jpeg", "Content-Length" to "20000000"), jpeg) },
            ),
        )
        val book = TripBook(MemoryTripFile())
        val files = MemoryMediaFiles()
        val store = BookSiteStore(book, files, net, "BoringTravel/1.0 (iPhone; +x)", { _, _ -> emptyList() }, null)
        val a = store.savePicture("https://pics.example.com/a.png", "m-a")
        assertEquals("media/m-a.png" to "image/png", a.media.localPath to a.media.mime)
        assertTrue(png.contentEquals(files.read("media/m-a.png")))
        // A picture the app doesn't keep as it is: a JPEG.
        val b = store.savePicture("https://pics.example.com/b.bmp", "m-b")
        assertEquals("media/m-b.jpg" to "image/jpeg", b.media.localPath to b.media.mime)
        assertEquals(4 to 3, b.media.width to b.media.height)
        val d = store.savePicture("https://pics.example.com/d.jpg", "m-d")
        assertEquals(300 to 200, d.media.width to d.media.height)
        assertEquals(Sha256.hex(jpeg), d.media.sha256)
        // Left out: an SVG, a page, a picture over 15 MB, an address that isn't https.
        assertFailsWith<Exception> { store.savePicture("https://pics.example.com/c.svg", "m-c") }
        assertFailsWith<Exception> { store.savePicture("https://pics.example.com/e.html", "m-e") }
        assertFailsWith<Exception> { store.savePicture("https://pics.example.com/big.jpg", "m-big") }
        assertFailsWith<Exception> { store.savePicture("ftp://pics.example.com/d.jpg", "m-f") }
        // The same picture again, its photo kept: reused, nothing downloaded.
        book.addMedia(d.media)
        val asked = net.asked.size
        val again = store.savePicture("https://pics.example.com/d.jpg", "m-d")
        assertFalse(again.fresh)
        assertEquals(asked, net.asked.size)
        // Every request named the app and asked for pictures.
        assertTrue(net.asked.all { it.headers["User-Agent"] == "BoringTravel/1.0 (iPhone; +x)" && it.headers["Accept"] == "image/*" })
    }

    @Test
    fun theIdsAreAndroidsByteForByte() {
        val urls = listOf(
            "https://wanderingfeet.tumblr.com/post/1001", "https://www.example.com/2016/04/kyoto/", "https://blog.example.com/?p=123",
            "https://ηράκλειο.example.gr/ταξίδι/κρήτη", "https://example.com/naïve-café#comments",
        )
        for (url in urls) {
            val post = SitePost(url, url, "", null, emptyList())
            assertEquals(uuid("boringtravel-site-post:" + SiteNotes.canonical(url)), SiteNotes.entryId(post))
            val part = post.copy(part = "3 april 2016 · chania")
            assertEquals(uuid("boringtravel-site-post:" + SiteNotes.canonical(url) + "#part:3 april 2016 · chania"), SiteNotes.entryId(part))
        }
        val post = SitePost("1", "https://b.example.com/1", "", null, listOf(com.justdataplease.boringtravel.site.SiteBlock.Picture("https://b.example.com/a.jpg"), com.justdataplease.boringtravel.site.SiteBlock.Picture("https://b.example.com/a.jpg")))
        val entry = SiteNotes.entryId(post)
        assertEquals(
            listOf(uuid("boringtravel-site-picture:$entry:https://b.example.com/a.jpg"), uuid("boringtravel-site-picture:$entry:https://b.example.com/a.jpg#2")),
            SiteNotes.pictureIds(post, entry),
        )
        assertEquals(uuid("boringtravel-site-place:trip-1:fushimi inari"), SiteNotes.placeItemId("trip-1", " Fushimi Inari "))
        assertEquals(uuid("boringtravel-site-place:trip-1:ηράκλειο"), SiteNotes.placeItemId("trip-1", "Ηράκλειο"))
        val pin = MapsListPlace("Café Ünal", 37.87676245, -4.78368705)
        assertEquals(
            uuid("boringtravel-site-pin:trip-1:café ünal@" + String.format(java.util.Locale.ROOT, "%.5f,%.5f", pin.lat, pin.lng)),
            SiteNotes.pinItemId("trip-1", pin),
        )
    }

    @Test
    fun aLongCopyAsksThePhoneForTimeAndGivesItBack() = runBlocking {
        val events = mutableListOf<String>()
        val work = object : BackgroundWork {
            override fun beginBackgroundWork(name: String): BackgroundTicket {
                events += "begin $name"
                return BackgroundTicket { events += "end" }
            }
        }
        // Pages and pictures a little slow, as on a phone network, so each state lasts.
        val slow = object : Net {
            val web = SiteSamples.net()
            override suspend fun get(request: NetRequest): NetAnswer {
                kotlinx.coroutines.delay(150)
                return web.get(request)
            }
        }
        val case = SiteCase(net = PlannerNet(slow).answer(RecordedAi::isBuiltIn, SiteSamples.modelText(SiteSamples.SORTED)))
        val watcher = case.scope.launch { keepCopyAlive(case.importer, work) }
        waitUntilTrue { watcher.isActive }
        case.read(SiteSamples.HOME)
        waitUntilTrue { events.size >= 2 }
        assertEquals(listOf("begin Copy from a link", "end"), events.toList())
        case.copy()
        waitUntilTrue { events.size >= 4 }
        assertEquals(listOf("begin Copy from a link", "end", "begin Copy from a link", "end"), events.toList())
        watcher.cancel()
        case.scope.cancel()
    }

    @Test
    fun theCopyGoesOnAfterItsScreenIsLeftAndComingBackShowsWhereItIs() {
        val book = TripBook(MemoryTripFile())
        val net = PlannerNet(SiteSamples.net(pictures = emptyMap())).answer(RecordedAi::isBuiltIn, SiteSamples.modelText(SiteSamples.SORTED))
            .hold { "/wp-content/uploads/" in it }
        val begun = mutableListOf<String>()
        val work = object : BackgroundWork {
            override fun beginBackgroundWork(name: String): BackgroundTicket {
                begun += name
                return BackgroundTicket { begun += "end" }
            }
        }
        ui(book, Place.Home(HomeTab.Me), net = net, work = work) {
            onNodeWithText("Import trips from a link").performScrollTo().performClick()
            address(SiteSamples.HOME)
            onNodeWithText("Read").performClick()
            shows("Copy 5 notes")
            onNodeWithText("Copy 5 notes").performScrollTo().performClick()
            shows("You can leave this screen: the copy goes on.")
            // Back to Me: the copy goes on (its pictures are still coming).
            onNodeWithContentDescription("Back").performClick()
            shows("Import trips from a link")
            onNodeWithText("Import trips from a link").performScrollTo().performClick()
            shows("Copying… 0 of 5 notes")
            onNodeWithText("Cancel").performClick()
            shows("Stopped")
            onNodeWithText("Copy the same address again to continue.").assertExists()
        }
        // Time asked for while it worked, and every time given back.
        assertTrue("Copy from a link" in begun)
        assertEquals(begun.count { it == "end" }, begun.count { it != "end" })
        assertEquals("end", begun.last())
        // Nothing half done: no note without its pictures.
        assertTrue(book.snapshot.entries.isEmpty())
    }

    @Test
    fun aMapsListStillGivesItsPlacesAsIdeas() {
        val book = TripBook(MemoryTripFile())
        ui(book, Place.CopySite(null, Recorded.LIST_SHORT), net = Recorded.net()) {
            // A Google Maps link is read at once.
            shows("3 places from a Google Maps list")
            onNodeWithText("Each becomes an idea in the plan, with its pin and its Google Maps link.").assertExists()
            onNodeWithText("Add 3 places").performClick()
            shows("3 places added to the plan, as ideas.")
        }
        val seville = book.trips(today).single()
        assertEquals("Seville", seville.title)
        assertEquals(3, book.ideas(seville.id).size)
    }

    private fun waitFor(importer: com.justdataplease.boringtravel.site.SiteImporter, test: (SiteImportState) -> Boolean): SiteImportState {
        waitUntilTrue { test(importer.state.value) }
        return importer.state.value
    }

    private fun waitUntilTrue(timeoutMs: Long = 20_000, test: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!test()) {
            check(System.currentTimeMillis() < until) { "Timed out" }
            Thread.sleep(10)
        }
    }

    /** A 4 × 3 PNG, drawn. */
    private fun samplePng(): ByteArray {
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(4, 3)
        surface.canvas.clear(0xFF3366CC.toInt())
        return surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
    }

    /** A 4 × 3 BMP (24 bits a pixel), written out. */
    private fun sampleBmp(): ByteArray {
        val w = 4
        val h = 3
        val row = (w * 3 + 3) / 4 * 4
        val size = 54 + row * h
        val b = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put('B'.code.toByte()).put('M'.code.toByte()).putInt(size).putInt(0).putInt(54)
        b.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24).putInt(0).putInt(row * h).putInt(2835).putInt(2835).putInt(0).putInt(0)
        repeat(h) {
            repeat(w) { b.put(0x40).put(0x80.toByte()).put(0xC0.toByte()) }
            repeat(row - w * 3) { b.put(0) }
        }
        return b.array()
    }
}

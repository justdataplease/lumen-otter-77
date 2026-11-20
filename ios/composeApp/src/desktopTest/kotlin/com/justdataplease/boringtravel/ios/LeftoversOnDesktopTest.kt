package com.justdataplease.boringtravel.ios

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.art.SceneMath
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.export.ExportOptions
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.time.startOfDayMs
import com.justdataplease.boringtravel.ui.nav.TripTab
import com.justdataplease.boringtravel.ui.theme.BoringTravelTheme
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.theme.Look
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Image
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 28, the leftover lines, driven as a traveller would on the desktop: Android's toasts as the app's own pill
 * ("Copied", "The photo couldn't be added."), the quick camera making its note at once, a note without a trip going into
 * the trip on today (the one that started last) or outside trips, the Media tab's Add media opening the photo library,
 * a page break in the editor and in print, a pick that comes back while photos still go in, the book's PDF errors,
 * Lisbon's drawn photos in the theme on screen and in Postcard Pop in its files and exports, the widgets following a
 * write, and the store in its own file.
 */
@OptIn(ExperimentalTestApi::class)
class LeftoversOnDesktopTest {
    private val today = SAMPLE_TODAY

    init {
        runBlocking { BtFontFiles.load() }
    }

    private fun ComposeUiTest.exists(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** [text] as Android's toast shows it: the app's pill, announced in passing (a live region), not a snackbar. */
    private fun ComposeUiTest.toast(text: String) =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion).and(androidx.compose.ui.test.hasAnyDescendant(hasText(text))))
            .fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.byAction(label: String): SemanticsNodeInteraction =
        onAllNodes(SemanticsMatcher("does $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label })[0]

    /** A camera and photo library that hand over what they are given, their files put where a picker puts them. */
    private class Picker(private val files: MediaFiles) : MediaPicker {
        var picks = 0
        var next: List<Pair<PickedMedia, ByteArray>> = emptyList()
        var shot: Pair<PickedMedia, ByteArray?>? = null

        override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) {
            picks++
            done(next.take(max).map { (p, b) -> files.write(p.path, b); p })
        }

        override val hasCamera: Boolean = true
        override fun takePhoto(done: (PickedMedia?) -> Unit) = done(shot?.let { (p, b) -> b?.let { files.write(p.path, it) }; p })
        override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
    }

    // --- 109: Android's toasts -----------------------------------------------------------------------------------------

    @Test
    fun copyingANameSaysCopiedInAndroidsToastForTwoSeconds() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val book = smallTripBook()
        val trip = book.trips(today).single()
        var copied: String? = null
        setContent {
            CompositionLocalProvider(LocalPhone provides Phone(copy = { copied = it })) {
                BoringTravelApp(book, AppSettings.ready(), today, startAt = listOf(Place.Trip(trip.id)))
            }
        }
        byAction("Open Hotel Grotta").performSemanticsAction(SemanticsActions.OnLongClick)
        onNodeWithText("Copy name").performClick()
        waitUntil(timeoutMillis = 5_000) { toast("Copied") }
        assertEquals("Hotel Grotta", copied)
        // Android's LENGTH_SHORT: gone after two seconds.
        waitUntil(timeoutMillis = 15_000) { !exists("Copied") }
    }

    @Test
    fun theQuickCameraMakesItsNoteAtOnceOrSaysThePhotoCouldntBeAdded() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val trip = book.newTrip("Kyoto", today.minusDays(2), today.plusDays(4))
        val files = MemoryMediaFiles()
        val picker = Picker(files)
        // A shot whose file never came: Android's toast, and Trips stays.
        picker.shot = PickedMedia("picked/shot.jpg", MediaTypes.JPEG) to null
        // The phone is allowed its location and is at the shrine; the photo has no point of its own.
        val web = Web(OfflineNet, location = FixedLocation(LatLng(34.9671, 135.7727), GeoAddress(locality = "Fushimi")))
        setContent { BoringTravelApp(book, AppSettings.ready(), today, media = MediaKit(files, picker), web = web) }
        onNodeWithContentDescription("Take a photo").performClick()
        waitUntil(timeoutMillis = 10_000) { toast("The photo couldn't be added.") }
        onNodeWithText("Write today").assertExists()
        assertTrue(book.entries(trip.id).isEmpty())
        assertTrue(book.snapshot.media.isEmpty())
        // A photo: the note is made at once with it (before anything is typed or Done), and Write opens on it.
        picker.shot = PickedMedia("picked/shot2.jpg", MediaTypes.JPEG) to samplePhoto(Scene.Sea, 400, 300)
        onNodeWithContentDescription("Take a photo").performClick()
        waitUntil(timeoutMillis = 10_000) { book.entries(trip.id).isNotEmpty() }
        val note = book.entries(trip.id).single()
        val photo = EntryDoc.parse(note.body).blocks.single() as Block.Photo
        assertNotNull(files.read(assertNotNull(book.media(photo.mediaId)).localPath))
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Done").fetchSemanticsNodes().isNotEmpty() }
        // Android's own rule, copied: a camera note whose photo has no point of its own opens as "No place" (WriteViewModel's
        // noPlace is worked out before placeShot, which then stops), so neither where the phone is nor the route places it.
        onNodeWithContentDescription("Done").performClick()
        waitForIdle()
        assertEquals("", book.entry(note.id)?.placeName)
        assertNull(book.entry(note.id)?.lat)
        assertEquals(book.snapshot, TripBook(file).snapshot)
    }

    // --- 424: a note without a trip ------------------------------------------------------------------------------------

    @Test
    fun aWriteWithoutATripGoesIntoTheTripThatStartedLastElseOutsideTrips() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val book = TripBook(MemoryTripFile())
        val links = AppLinks()
        setContent { BoringTravelApp(book, AppSettings.ready(), today, links = links) }
        // No trip on today: the widget's Write makes a note outside trips.
        runOnUiThread { links.open("boringtravel://write") }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2 }
        onAllNodes(hasSetTextAction())[0].performTextInput("Rucksack")
        onNodeWithContentDescription("Done").performClick()
        waitForIdle()
        assertNull(book.snapshot.entries.single().tripId)
        // A long trip, and a short one inside it that started later: Android's currentOn gives the short one.
        val greece = book.newTrip("Greece", today.minusDays(10), today.plusDays(10))
        val naxos = book.newTrip("Naxos", today.minusDays(1), today.plusDays(2))
        runOnUiThread { links.open("boringtravel://write") }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2 }
        onAllNodes(hasSetTextAction())[0].performTextInput("Ferry")
        onNodeWithContentDescription("Done").performClick()
        waitForIdle()
        assertEquals(naxos.id, book.snapshot.entries.single { it.title == "Ferry" }.tripId)
        assertTrue(book.entries(greece.id).isEmpty())
    }

    // --- 461: the Media tab's Add media ----------------------------------------------------------------------------------

    @Test
    fun theMediaTabsAddMediaOpensANewNoteWithThePhotoLibrary() = runDesktopComposeUiTest(width = 440, height = 2400) {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", today.minusDays(1), today.plusDays(3))
        val files = MemoryMediaFiles()
        val picker = Picker(files).apply { next = listOf(PickedMedia("picked/a.jpg", MediaTypes.JPEG) to samplePhoto(Scene.Sea, 400, 300)) }
        setContent { BoringTravelApp(book, AppSettings.ready(), today, startAt = listOf(Place.Trip(trip.id, TripTab.Media)), media = MediaKit(files, picker)) }
        onNodeWithText("Photos and videos you add to this trip's journal show here.").assertExists()
        onAllNodesWithText("Add media").onFirst().performClick()
        // Write opens with the photo library, once; what was picked goes into a new note of the trip.
        waitUntil(timeoutMillis = 10_000) { book.entries(trip.id).isNotEmpty() }
        assertEquals(1, picker.picks)
        val note = book.entries(trip.id).single()
        assertEquals(1, EntryDoc.parse(note.body).mediaIds().size)
        onNodeWithContentDescription("Done").performClick()
        waitForIdle()
        assertEquals(1, picker.picks)
    }

    // --- 477: a page break -----------------------------------------------------------------------------------------------

    @Test
    fun aPageBreakIsAddedAndRemovedInWriteAndStartsANewPrintedPage() {
        val file = MemoryTripFile()
        val book = TripBook(file)
        val trip = book.newTrip("Naxos", today, today.plusDays(1))
        runDesktopComposeUiTest(width = 440, height = 2400) {
            setContent { BoringTravelApp(book, AppSettings.ready(), today, startAt = listOf(Place.Trip(trip.id, TripTab.Journal), Place.Write(trip.id))) }
            onAllNodes(hasSetTextAction())[0].performTextInput("Two pages")
            onAllNodes(hasSetTextAction())[1].performTextInput("The first page.")
            onAllNodesWithContentDescription("Insert here").onFirst().performClick()
            onNodeWithText("Page break").performClick()
            waitUntil(timeoutMillis = 5_000) { exists("New printed page") }
            // X takes it out again.
            onNodeWithContentDescription("Remove page break").performClick()
            waitUntil(timeoutMillis = 5_000) { !exists("New printed page") }
            onAllNodesWithContentDescription("Insert here").onFirst().performClick()
            onNodeWithText("Page break").performClick()
            waitUntil(timeoutMillis = 5_000) { exists("New printed page") }
            // The words after it: the text under the break.
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 3 }
            onAllNodes(hasSetTextAction())[2].performTextInput("The second page.")
            onNodeWithContentDescription("Done").performClick()
            waitForIdle()
        }
        val note = book.entries(trip.id).single()
        val blocks = EntryDoc.parse(note.body).blocks
        assertEquals(listOf("The first page.", "PageBreak", "The second page."), blocks.map { b -> (b as? Block.Paragraph)?.html ?: b.toString() })
        // In print: the part after the break starts a page of its own (the stand-in reads the same HTML WebKit prints).
        runBlocking {
            val files = MemoryMediaFiles()
            val printer = DesktopPagePrinter()
            val exports = TripExports(book, files, VideoFrames { _, _ -> null }, printer)
            val html = exports.printHtml(exports.load(trip.id), ExportOptions()) {}
            assertTrue(html.contains("page-continuation") && html.contains("break-before:page"), html)
            suspend fun pages(): Int = PagesPdf.read(assertNotNull(files.read(exports.pdf(exports.load(trip.id), ExportOptions()) {}))).size
            val withBreak = pages()
            book.saveNote(note, EntryDoc(blocks = blocks.filter { it != Block.PageBreak }))
            val without = pages()
            assertEquals(without + 1, withBreak)
        }
    }

    // --- 492: a pick that comes back while photos still go in -------------------------------------------------------------

    /** A photo library and a camera that keep their answers until the test hands them over. */
    private class HeldPicker : MediaPicker {
        var photos: ((List<PickedMedia>) -> Unit)? = null
        var video: ((PickedMedia?) -> Unit)? = null
        override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) { photos = done }
        override val hasCamera: Boolean = true
        override fun takePhoto(done: (PickedMedia?) -> Unit) = done(null)
        override fun recordVideo(done: (PickedMedia?) -> Unit) { video = done }
    }

    /** A video being prepared that waits for [gate]. */
    private class GatedVideos(private val files: MediaFiles, private val gate: CompletableDeferred<Unit>) : VideoPrep {
        override fun probe(path: String): VideoProbe = VideoProbe(20_000, 1920, 1080)
        override suspend fun transcode(source: String, out: String, probe: VideoProbe, bitrate: Int, shortSide: Int?, audioBitrate: Int, onProgress: (Float) -> Unit) {
            onProgress(0.4f)
            gate.await()
            files.write(out, "720p mp4".encodeToByteArray())
            onProgress(1f)
        }
    }

    @Test
    fun aPickThatComesBackWhileAVideoIsPreparedGoesInOnceItIsIn() = runDesktopComposeUiTest(width = 440, height = 1600) {
        val book = TripBook(MemoryTripFile())
        val trip = book.newTrip("Naxos", today.minusDays(1), today.plusDays(3))
        val files = MemoryMediaFiles()
        val gate = CompletableDeferred<Unit>()
        val picker = HeldPicker()
        setContent {
            CompositionLocalProvider(LocalMediaKit provides MediaKit(files, picker, videos = GatedVideos(files, gate))) {
                BoringTravelTheme(Look.Default) { WriteScreen(book, trip.id, null, today, onDone = {}) }
            }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("Harbour")
        // The photo library opens and waits; the camera records meanwhile.
        onNodeWithContentDescription("Choose photos and videos").performClick()
        onNodeWithContentDescription("Record a video").performClick()
        val photos = assertNotNull(picker.photos)
        val video = assertNotNull(picker.video)
        files.write("picked/v.mov", ByteArray(100))
        runOnUiThread { video(PickedMedia("picked/v.mov", "video/quicktime")) }
        waitUntil(timeoutMillis = 10_000) { exists("Preparing the video… 40%") }
        // The photos come back while the video is prepared: they wait.
        files.write("picked/a.jpg", samplePhoto(Scene.Sea, 400, 300))
        runOnUiThread { photos(listOf(PickedMedia("picked/a.jpg", MediaTypes.JPEG))) }
        waitForIdle()
        assertTrue(book.snapshot.media.none { it.mime == MediaTypes.JPEG })
        assertNotNull(files.read("picked/a.jpg"))
        // The video is in, then the photos go in after it.
        gate.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { book.snapshot.media.size == 2 }
        val doc = EntryDoc.parse(book.entries(trip.id).single().body)
        assertEquals(2, doc.mediaIds().size)
        assertEquals(setOf("video", "image"), doc.mediaIds().map { id -> book.media(id)!!.mime.substringBefore('/') }.toSet())
    }

    // --- 911: the book's PDF errors --------------------------------------------------------------------------------------

    /** A phone whose book PDF goes wrong as [how] says: "slow", "empty" or "broken". */
    private class FailingPrinter(private val how: String) : PagePrinter by DesktopPagePrinter() {
        override suspend fun writeBook(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, sheet: BookSheetSize, ground: Long): Int =
            when (how) {
                "slow" -> withTimeout(1) { delay(1_000); 0 }
                "empty" -> 0
                else -> throw IllegalStateException("WebKit gave up")
            }
    }

    @Test
    fun theBooksPdfSaysAndroidsWordsWhenItCantBeMade() {
        val (book, kit) = demoBook()
        val settings = AppSettings.ready().apply { demoMode = true }
        for ((how, words) in listOf(
            "slow" to "The pages took too long. Try smaller photos or fewer pages.",
            "empty" to "The PDF came out empty. Try again.",
            "broken" to "Couldn't make the PDF. Try again.",
        )) {
            runDesktopComposeUiTest(width = 440, height = 956) {
                setContent {
                    CompositionLocalProvider(
                        LocalMediaKit provides kit, LocalMediaRows provides { id: String -> book.media(id) }, LocalPagePrinter provides FailingPrinter(how),
                    ) { BookScreen(book, SampleArgentina.TRIP_ID, settings, onBack = {}, startWith = BookStart.Pdf) }
                }
                waitUntil(timeoutMillis = 60_000) { exists("Save to phone") }
                onNodeWithText("Save to phone").performClick()
                waitUntil(timeoutMillis = 60_000) { exists(words) }
                // Nothing is left half made, and it can be tried again.
                onNodeWithText("Save to phone").assertExists()
            }
        }
    }

    // --- 1404: Lisbon's drawn photos ------------------------------------------------------------------------------------

    @Test
    fun lisbonsDrawnPhotosFollowTheThemeOnScreenAndArePostcardPopInTheirFilesAndPrints() {
        val (book, kit) = demoBook()
        val drawn = book.snapshot.media.filter { it.tripId == SampleTrips.LISBON && DemoArt.live(it.localPath) != null }
        assertTrue(drawn.isNotEmpty())
        // Each file is the picture drawn in Postcard Pop, whatever the theme (Android's DemoArt.write).
        for (m in drawn) {
            val live = assertNotNull(DemoArt.live(m.localPath))
            val bytes = assertNotNull(kit.files.read(m.localPath))
            val size = Image.makeFromEncoded(bytes).use { SceneMath.Size(it.width, it.height) }
            val shape = SceneMath.Shape.entries.single { SceneMath.pixelSize(it) == size }
            assertContentEquals(DemoArt.jpeg(DemoArt.Picture(live.kind, shape, live.mirror)), bytes)
        }
        // On screen it is drawn live in the theme: the same photo in Postcard Pop and in Trail differs.
        val m = drawn.first()
        fun colours(look: Look): IntArray {
            var out = IntArray(0)
            runDesktopComposeUiTest(width = 200, height = 200) {
                setContent { BoringTravelTheme(look) { MediaImage(m, Modifier.size(60.dp)) } }
                waitForIdle()
                val pixels = onRoot().captureToImage().toPixelMap()
                out = IntArray(9) { i -> pixels[10 + 20 * (i % 3), 10 + 20 * (i / 3)].hashCode() }
            }
            return out
        }
        val pop = colours(Look.PostcardPop)
        val trail = colours(Look.Trail)
        assertTrue(!pop.contentEquals(trail))
        // Shares and books print the files: the exported photo is the Postcard Pop file, made small.
        runBlocking {
            val exports = TripExports(book, kit.files, VideoFrames { _, _ -> null }, DesktopPagePrinter())
            val html = exports.printHtml(exports.load(SampleTrips.LISBON), ExportOptions()) {}
            val src = assertNotNull(Regex("src=\"(img/[^\"]+)\"").find(html)).groupValues[1]
            val printed = assertNotNull(kit.files.read("$EXPORTS/$src"))
            val made = averageColour(printed)
            val inFiles = drawn.map { averageColour(kit.files.read(it.localPath)!!) }
            assertTrue(inFiles.any { close(it, made) }, "the printed photo is one of the Postcard Pop files")
        }
    }

    private fun averageColour(jpeg: ByteArray): Triple<Int, Int, Int> = Image.makeFromEncoded(jpeg).use { image ->
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
            val c = bitmap.getColor(x, y)
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            n++
        }
        Triple((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun close(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>) =
        abs(a.first - b.first) <= 6 && abs(a.second - b.second) <= 6 && abs(a.third - b.third) <= 6

    // --- 1325: the widgets follow a change -------------------------------------------------------------------------------

    @Test
    fun theWidgetsAreWrittenAgainAMomentAfterAChangeToTheStore() = runDesktopComposeUiTest(width = 440, height = 956) {
        val core = AppCore(MemoryTripFile(), MemorySettingsFile())
        val trip = core.book.newTrip("Naxos", today.minusDays(1), today.plusDays(3))
        val widget = MemoryHomeWidget()
        setContent { BoringTravelApp(core.book, AppSettings.ready(), today, widget = widget, writes = core.writes) }
        waitUntil(timeoutMillis = 5_000) { widget.saves == 1 }
        assertEquals("Naxos", WidgetSnapshot.parse(widget.snapshot!!)?.days?.first()?.title)
        // A change no screen made (a shared trip merged, a route's stops at night): the widgets follow it.
        runOnUiThread { core.book.editTrip(trip.id, "Naxos and Paros", "", today.minusDays(1), today.plusDays(3)) }
        waitUntil(timeoutMillis = 10_000) { widget.saves == 2 }
        assertEquals("Naxos and Paros", WidgetSnapshot.parse(widget.snapshot!!)?.days?.first()?.title)
    }

    // --- 1479: the store -------------------------------------------------------------------------------------------------

    @Test
    fun theTripsLiveInAFileBesideThePhotosAndSurviveAStart() {
        val files = MemoryMediaFiles()
        val old = MemoryTripFile()
        val seed = TripBook(old)
        val trip = seed.newTrip("Kyoto", today, today.plusDays(4))
        seed.writeNote(trip.id, "Lanterns in Gion.", at = startOfDayMs(today, "Asia/Tokyo") + 20 * 3_600_000L, zoneId = "Asia/Tokyo")
        var cleared = false
        val core = AppCore(FileTripFile(files, legacy = old, forgetLegacy = { cleared = true }), MemorySettingsFile())
        assertTrue(cleared)
        assertEquals(seed.snapshot, core.book.snapshot)
        core.book.newTrip("Naxos")
        // The next start reads the file: nothing of UserDefaults is needed any more.
        val again = AppCore(FileTripFile(files), MemorySettingsFile())
        assertEquals(core.book.snapshot, again.book.snapshot)
        assertTrue(files.paths.contains(FileTripFile.PATH))
    }
}

package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.album.AlbumLayouts
import com.justdataplease.boringtravel.backup.BackupData
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.toSnapshot
import com.justdataplease.boringtravel.book.BookEdit
import com.justdataplease.boringtravel.book.BookFormat
import com.justdataplease.boringtravel.book.BookJson
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.Template
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.export.Paper
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.book.BookViewModel
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.justdataplease.boringtravel.androidref.book.BookJson as AndroidJson

/**
 * The book on the desktop (round 21), on the demo's Argentina (its journal and real photos): Make a book from the trip's
 * menu and from Share, the first visit laid out and kept as Android keeps it (the trip store's `books` row, the same JSON),
 * a second visit reopening every edit, the overview with its numbers and labels, a page opened large, Add a page, Photo
 * pages, the Book look (the theme, the layout started again), Undo and Redo, the PDF at the book's own size (the
 * stand-in's) with Save, Print and Drive, a trip that is gone, a deleted trip's book, and a book carried by a backup to
 * the other phone.
 */
@OptIn(ExperimentalTestApi::class)
class BookOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    private val trip = SampleArgentina.TRIP_ID

    private fun demo(theme: PrintTheme = PrintTheme.Notebook): Triple<TripBook, MediaKit, AppSettings> {
        val (book, kit) = demoBook()
        return Triple(book, kit, AppSettings.ready().apply { demoMode = true; printTheme = theme })
    }

    private fun exports(book: TripBook, kit: MediaKit, printer: PagePrinter = DesktopPagePrinter()) =
        BookExports(book, kit.files, kit.frames, printer)

    /** A view-model on [book]'s Argentina in a scope of its own, laid out (the first visit makes the draft). */
    private suspend fun opened(book: TripBook, kit: MediaKit, scope: CoroutineScope, theme: PrintTheme = PrintTheme.Notebook): BookViewModel {
        val vm = BookViewModel(exports(book, kit), trip, { theme }, scope)
        withTimeout(60_000) { vm.state.first { !it.loading } }
        return vm
    }

    private val expectedFormat = if (Paper.default() == Paper.Letter) BookFormat.Letter else BookFormat.A5

    @Test
    fun theTripsMenuMakesTheBookAndItIsKeptAsAndroidKeepsIt() {
        val (book, kit, settings) = demo(PrintTheme.Scrapbook)
        runDesktopComposeUiTest(width = 440, height = 956) {
            setContent {
                CompositionLocalProvider(LocalPagePrinter provides DesktopPagePrinter()) {
                    BoringTravelApp(book, settings, SAMPLE_TODAY, startAt = listOf(Place.Trip(trip)), media = kit)
                }
            }
            onNodeWithContentDescription("Trip options").performClick()
            onNodeWithText("Make a book").performClick()
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithText("Tap a page to change it. Hold and drag to move it.").fetchSemanticsNodes().isNotEmpty() }
            val draft = assertNotNull(book.latestBook(trip))
            val doc = BookJson.decode(draft.pages)
            assertEquals("scrapbook", draft.theme)
            assertEquals(expectedFormat.id, draft.format)
            assertEquals("theme", draft.fontSet)
            onNodeWithText("Argentina & Patagonia").assertExists()
            val layout = BookLayout(exports(book, kit).spec(draft, doc), com.justdataplease.boringtravel.book.BookFacts(runBlocking { exports(book, kit).loadTrip(trip) }))
            val printed = layout.printed(doc)
            onNodeWithText("Book · ${printed.size} pages · ${expectedFormat.label}").assertExists()
            // The thumbnails' names; notes pages for binding when the count isn't a multiple of four.
            onNodeWithText("1 · Cover").assertExists()
            onNodeWithText("2 · Title").assertExists()
            val pad = BookLayout.padding(doc.pages.size)
            if (pad > 0) onNodeWithText("$pad notes ${if (pad == 1) "page" else "pages"} added for binding.").assertExists()
            // A tap opens the page in the editor (round 22), exactly as it prints, with what is on it; All pages goes back.
            onNodeWithText("1 · Cover").performClick()
            onNodeWithText("Page 1 of ${printed.size}").assertExists()
            onNodeWithText("Argentina & Patagonia · Book").assertExists()
            onNodeWithText("ON THIS PAGE · TOP FIRST").assertExists()
            onNodeWithContentDescription("Next page").performClick()
            onNodeWithText("Page 2 of ${printed.size}").assertExists()
            onNodeWithContentDescription("All pages").performClick()
            onNodeWithText("Tap a page to change it. Hold and drag to move it.").assertExists()
        }
        // The row is the backup's: Android's own book code reads its pages to the same JSON.
        val draft = assertNotNull(book.latestBook(trip))
        assertEquals(draft.pages, AndroidJson.encode(AndroidJson.decode(draft.pages)))
    }

    @Test
    fun aSecondVisitReopensEveryEditAndUndoRedoGoThroughThem() = runBlocking {
        val (book, kit, _) = demo()
        val scope = CoroutineScope(coroutineContext + Job())
        val vm = opened(book, kit, scope)
        val first = vm.state.value.doc
        assertEquals(1, book.snapshot.books.size)
        // Move the title page after the third and let the text settle and the save come (0.4 s).
        vm.edit(reflowAll = true) { BookEdit.move(it, 1, 3) }
        assertTrue(vm.state.value.canUndo)
        delay(1_500)
        val moved = vm.state.value.doc
        assertEquals(Template.TitlePage, moved.pages[3].template)
        assertEquals(moved, BookJson.decode(assertNotNull(book.latestBook(trip)).pages))
        // A second visit opens the same book, not a new one.
        val again = opened(book, kit, scope)
        assertEquals(moved, again.state.value.doc)
        assertEquals(1, book.snapshot.books.size)
        // Undo and redo.
        vm.undo()
        assertEquals(first.pages.map { it.id }, vm.state.value.doc.pages.map { it.id })
        assertTrue(vm.state.value.canRedo)
        vm.redo()
        assertEquals(moved.pages.map { it.id }, vm.state.value.doc.pages.map { it.id })
        // Leaving before the save comes still keeps the change (the screen's scope may already be gone).
        vm.edit { it.copy(padTo4 = !it.padTo4) }
        scope.cancel()
        vm.close()
        assertEquals(!moved.padTo4, BookJson.decode(assertNotNull(book.latestBook(trip)).pages).padTo4)
    }

    @Test
    fun theLookChangesThePagesAndTheLayoutStartsAgain() = runBlocking {
        val (book, kit, _) = demo()
        val scope = CoroutineScope(coroutineContext + Job())
        val vm = opened(book, kit, scope)
        val before = vm.state.value
        vm.setSpec(before.spec.copy(theme = PrintTheme.Minimal, format = BookFormat.A4))
        withTimeout(30_000) { vm.state.first { it.spec.format == BookFormat.A4 && !it.busy } }
        delay(800)
        val saved = assertNotNull(book.latestBook(trip))
        assertEquals("minimal" to "a4", saved.theme to saved.format)
        // A4 sets the text in two columns: fewer text pages than A5's one column.
        val texts = { d: com.justdataplease.boringtravel.book.BookDoc -> d.pages.count { it.template == Template.Text } }
        assertTrue(texts(vm.state.value.doc) <= texts(before.doc))
        // Start the layout again: the first layout, with Undo bringing the edits back.
        vm.edit { BookEdit.delete(it, it.pages[2].id) }
        val edited = vm.state.value.doc
        vm.resetLayout()
        // The first layout comes with new pages (new ids).
        withTimeout(30_000) { vm.state.first { !it.busy && it.doc.pages.first().id != edited.pages.first().id } }
        val reset = vm.state.value.doc
        assertEquals(Template.Cover, reset.pages.first().template)
        assertEquals(Template.TitlePage, reset.pages[1].template)
        vm.undo()
        assertEquals(edited.pages.map { it.id }, vm.state.value.doc.pages.map { it.id })
        scope.cancel()
    }

    @Test
    fun addAPageAndPhotoPagesGoBeforeTheBackCoverAndOpen() {
        val (book, kit, settings) = demo()
        runDesktopComposeUiTest(width = 440, height = 956) {
            setContent {
                CompositionLocalProvider(LocalMediaKit provides kit, LocalMediaRows provides { id: String -> book.media(id) }) {
                    BookScreen(book, trip, settings, onBack = {}, startWith = BookStart.AddPage)
                }
            }
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithText("Add a page").fetchSemanticsNodes().isNotEmpty() }
            Template.choosable.forEach { onNodeWithText(it.label).performScrollTo().assertExists() }
            onNodeWithText("Notes page").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Page ", substring = true).fetchSemanticsNodes().any() }
            onNodeWithContentDescription("All pages").assertExists()
        }
        val doc = BookJson.decode(assertNotNull(book.latestBook(trip)).pages)
        assertEquals(Template.BackCover, doc.pages.last().template)
        assertEquals(Template.Notes, doc.pages[doc.pages.lastIndex - 1].template)

        runDesktopComposeUiTest(width = 440, height = 956) {
            setContent {
                CompositionLocalProvider(LocalMediaKit provides kit, LocalMediaRows provides { id: String -> book.media(id) }) {
                    BookScreen(book, trip, settings, onBack = {}, startWith = BookStart.PhotoPages)
                }
            }
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithText("Add photo pages").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Only photos not in the book").assertExists()
            onNodeWithText("Pick photos").assertExists()
            // The demo's photos are all in the book: with every photo shown, pick two in order.
            onNodeWithText("Only photos not in the book").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Photo", substring = true).fetchSemanticsNodes().size >= 2 }
            onAllNodesWithContentDescription("Photo, in the book").onFirst().performClick()
            onAllNodesWithContentDescription("Photo, in the book").onFirst().performClick()
            onNodeWithText("Next · 2 photos").performClick()
            onNodeWithText("2 photos. Pick a layout; the pages follow, as many as the photos need.").assertExists()
            onAllNodesWithText(AlbumLayouts.forCount(2).first().name).onFirst().performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("All pages").fetchSemanticsNodes().isNotEmpty() }
        }
        val withAlbum = BookJson.decode(assertNotNull(book.latestBook(trip)).pages)
        val album = withAlbum.pages[withAlbum.pages.lastIndex - 1]
        assertEquals(Template.Album, album.template)
        assertEquals(2, album.slots.count { it.mediaId != null })
    }

    @Test
    fun thePdfIsMadeOnceAtTheBooksOwnSizeAndHandedOn() {
        val (book, kit, settings) = demo()
        val printer = DesktopPagePrinter()
        val saved = ArrayList<String>()
        val shared = ArrayList<Pair<String, String>>()
        val subjects = ArrayList<String>()
        val phone = Phone(
            exportFile = { path, done -> saved += path; done(true) },
            shareFile = { name, mime, _, subject -> shared += name to mime; subjects += subject; true },
        )
        runDesktopComposeUiTest(width = 440, height = 956) {
            setContent {
                CompositionLocalProvider(LocalMediaKit provides kit, LocalMediaRows provides { id: String -> book.media(id) }, LocalPagePrinter provides printer, LocalPhone provides phone) {
                    BookScreen(book, trip, settings, onBack = {}, startWith = BookStart.Pdf)
                }
            }
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithText("Save to phone").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("pages · ${expectedFormat.label} · ${expectedFormat.sizeText}", substring = true).assertExists()
            onNodeWithText("Photos at print quality · fonts embedded").assertExists()
            onNodeWithText("Save to phone").performClick()
            waitUntil(timeoutMillis = 120_000) { onAllNodesWithText("Saved.").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(saved.single().endsWith("/Argentina Patagonia book.pdf"), saved.single())
            onNodeWithText("Share PDF").performClick()
            waitUntil(timeoutMillis = 10_000) { shared.isNotEmpty() }
            assertEquals("Argentina Patagonia book.pdf" to "application/pdf", shared.single())
            // The trip's name as the subject, as Android's Sharing.share gives it (EXTRA_SUBJECT).
            assertEquals(book.trip(trip)!!.title, subjects.single())
            onNodeWithText("Print").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Couldn't open the print dialog.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Argentina & Patagonia", printer.printed.single().first)
            onNodeWithText("Send to Drive").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Google Drive isn't connected. Connect it in Me › Backup & sync.").fetchSemanticsNodes().isNotEmpty() }
        }
        // One PDF for all four: the book didn't change. Its sheets are the book's own size, one per printed page.
        assertEquals(1, printer.bookPrints.size)
        val pdf = assertNotNull(kit.files.read(printer.bookPrints.single()))
        val text = String(pdf, Charsets.ISO_8859_1)
        val w = "%.2f".format(java.util.Locale.ROOT, expectedFormat.widthMm * 72 / 25.4)
        val h = "%.2f".format(java.util.Locale.ROOT, expectedFormat.heightMm * 72 / 25.4)
        assertTrue(text.contains("/MediaBox [0 0 $w $h]"), text.take(600))
        val doc = BookJson.decode(assertNotNull(book.latestBook(trip)).pages)
        assertEquals(BookLayout.padded(doc.pages, doc.padTo4).size, PagesPdf.read(pdf).size)
        // Its HTML beside it is WebKit's print form: no clear gradient, the book's sheet and its section heights.
        val html = assertNotNull(kit.files.read(printer.bookPrints.single() + ".html")).decodeToString()
        assertTrue(html.contains("url(data:image/png;base64,") && html.contains(".pg{width:"))
        assertTrue(html.contains("<meta http-equiv=\"Content-Security-Policy\" content=\"${PrintPages.POLICY}\">"))
        BookExports.BOOK_FONTS.forEach { assertNotNull(kit.files.read("$EXPORTS/fonts/$it"), it) }
    }

    /**
     * Round 23: with Drive connected (the fake Drive), Send to Drive puts the PDF in BoringTravel › Exports as
     * "<Trip> book.pdf" and says so, then Send Drive link lets anyone with the link open it and hands the link to the
     * share sheet, as Android's MakeSheet.
     */
    @Test
    fun sendToDriveSavesThePdfInExportsAndSendsItsLink() {
        val (book, kit, settings) = demo()
        val google = FakeGoogle()
        val account = google.account(settings, kit.files)
        runBlocking { account.connectDrive() }
        val links = ArrayList<Pair<String, String>>()
        val phone = Phone(share = { subject, text -> links += subject to text; true })
        runDesktopComposeUiTest(width = 440, height = 956) {
            setContent {
                CompositionLocalProvider(
                    LocalMediaKit provides kit, LocalMediaRows provides { id: String -> book.media(id) }, LocalPagePrinter provides DesktopPagePrinter(),
                    LocalPhone provides phone, com.justdataplease.boringtravel.google.LocalGoogle provides account,
                ) {
                    BookScreen(book, trip, settings, onBack = {}, startWith = BookStart.Pdf)
                }
            }
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithText("Send to Drive").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Send to Drive").performClick()
            waitUntil(timeoutMillis = 120_000) { onAllNodesWithText("Saved to Drive › BoringTravel › Exports ›", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Saved to Drive › BoringTravel › Exports › Argentina Patagonia book.pdf").assertExists()
            val pdf = google.cloud.filesIn(com.justdataplease.boringtravel.google.DriveApi.EXPORTS).single()
            assertEquals("Argentina Patagonia book.pdf", pdf.name)
            assertEquals("application/pdf", pdf.mimeType)
            assertTrue(String(pdf.content, 0, 5, Charsets.ISO_8859_1).startsWith("%PDF"))
            onNodeWithText("Send Drive link").performClick()
            waitUntil(timeoutMillis = 10_000) { links.isNotEmpty() }
            assertEquals("Argentina & Patagonia book" to "https://drive.google.com/file/d/${pdf.id}/view", links.single())
            assertTrue(pdf.anyone)
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Anyone with the link can open the book; it stays in your Drive.").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun aTripThatIsGoneSaysSo() {
        val (book, kit, settings) = demo()
        runDesktopComposeUiTest(width = 440, height = 956) {
            setContent {
                CompositionLocalProvider(LocalMediaKit provides kit) { BookScreen(book, "gone", settings, onBack = {}) }
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Couldn't make the book").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("This trip isn't here any more.").assertExists()
        }
        assertNull(book.latestBook("gone"))
    }

    @Test
    fun shareOpensTheBookToo() {
        val (book, kit, settings) = demo()
        runDesktopComposeUiTest(width = 440, height = 956) {
            setContent {
                CompositionLocalProvider(LocalPagePrinter provides DesktopPagePrinter()) {
                    BoringTravelApp(book, settings, SAMPLE_TODAY, startAt = listOf(Place.Trip(trip), Place.Share(trip)), media = kit)
                }
            }
            onNodeWithText("Make a book").performClick()
            waitUntil(timeoutMillis = 60_000) { onAllNodesWithText("Tap a page to change it. Hold and drag to move it.").fetchSemanticsNodes().isNotEmpty() }
        }
        assertNotNull(book.latestBook(trip))
    }

    @Test
    fun aDeletedTripTakesItsBookAndABackupCarriesABookToTheOtherPhone() = runBlocking {
        val (book, kit, _) = demo()
        val scope = CoroutineScope(coroutineContext + Job())
        opened(book, kit, scope)
        // The photos' screen copies may still be being written: let them finish before the trip's files go.
        scope.coroutineContext[Job]!!.cancelAndJoin()
        val draft = assertNotNull(book.latestBook(trip))
        // The backup's data.json on another phone (here or Android): the same row, its pages the same text.
        val other = TripBook(MemoryTripFile(book.export()))
        assertEquals(draft, other.latestBook(trip))
        val androidRows = BackupJson.decodeFromString(BackupData.serializer(), book.export()).toSnapshot().books
        assertEquals(listOf(draft), androidRows)
        // Deleting the trip marks its book deleted (a backup carries that too).
        assertTrue(removeTripAndFiles(book, kit.files, trip))
        assertNull(book.latestBook(trip))
        assertNotNull(book.snapshot.books.single { it.id == draft.id }.deletedAt)
        val restored = TripBook(MemoryTripFile(book.export()))
        assertNull(restored.latestBook(trip))
        assertNotEquals(draft, restored.snapshot.books.single())
    }
}

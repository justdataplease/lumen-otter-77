package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.justdataplease.boringtravel.backup.BackupJson
import com.justdataplease.boringtravel.backup.BackupPreferences
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.export.ExportOptions
import com.justdataplease.boringtravel.export.Paper
import com.justdataplease.boringtravel.export.PhotoSize
import com.justdataplease.boringtravel.export.TripText
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.justdataplease.boringtravel.androidref.export.ExportOptions as AndroidOptions
import com.justdataplease.boringtravel.androidref.export.TripBook as AndroidBook
import com.justdataplease.boringtravel.androidref.export.TripDocx as AndroidDocx

/**
 * Share on the desktop: a trip with a photo in a note made into the print HTML (photos made small, upright and without
 * their tags, the fonts beside them, nothing allowed from the network), into the stand-in's PDF pages (WebKit makes
 * them on iPhone), into a Word file the Android app would write part for part, and the screen itself: the preview, the
 * options kept and in a backup, Text's Copy, Word's Share, PDF's Save and Print, Google Docs without an account, and
 * the trip's ⋮ › Share.
 */
@OptIn(ExperimentalTestApi::class)
class ShareOnDesktopTest {
    init {
        runBlocking { BtFontFiles.load() }
    }

    /** A JPEG of [w] x [h] in a flat colour, with an EXIF block the export must drop. */
    private fun jpeg(w: Int, h: Int, color: Int): ByteArray {
        val s = Surface.makeRasterN32Premul(w, h)
        s.canvas.clear(color)
        val plain = s.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes
        // A fake APP1 "Exif" segment after the SOI marker, as a camera writes (with a place in it).
        val exif = "Exif\u0000\u0000GPS 37.1,25.3".encodeToByteArray()
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((exif.size + 2) shr 8).toByte(), ((exif.size + 2) and 0xFF).toByte()) + exif
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    /** The small trip with a note holding a photo and a pair, the photos' files in [files]. */
    private fun bookWithPhotos(files: MediaFiles): Pair<TripBook, String> {
        val book = smallTripBook()
        val trip = book.trips().first()
        val note = book.entries(trip.id).first()
        files.write("media/ph1.jpg", jpeg(2400, 1800, 0xFF3A7CA5.toInt()))
        files.write("media/ph2.jpg", jpeg(900, 1200, 0xFFC4423A.toInt()))
        files.write("media/ph3.jpg", jpeg(1200, 900, 0xFF2E8B57.toInt()))
        listOf("ph1" to (2400 to 1800), "ph2" to (900 to 1200), "ph3" to (1200 to 900)).forEach { (id, size) ->
            book.addMedia(Media(id, trip.id, note.id, "media/$id.jpg", width = size.first, height = size.second, createdAt = 1, updatedAt = 1))
        }
        val doc = EntryDoc(blocks = listOf(Block.Paragraph("The ferry leaves at nine."), Block.Photo("ph1", caption = "The harbour"), Block.Paragraph("Then lunch."), Block.Album("2-side", listOf("ph2", "ph3"))))
        book.saveNote(note, doc)
        return book to trip.id
    }

    private fun exports(book: TripBook, files: MediaFiles, printer: PagePrinter = DesktopPagePrinter()) =
        TripExports(book, files, VideoFrames { _, _ -> null }, printer)

    @Test
    fun printHtmlHasThePhotosAndFontsBesideItAndNothingFromOutside() = runBlocking {
        val files = MemoryMediaFiles()
        val (book, tripId) = bookWithPhotos(files)
        val exports = exports(book, files)
        val steps = ArrayList<String>()
        val html = exports.printHtml(exports.load(tripId), ExportOptions()) { steps += it.text }
        assertTrue(html.contains("<meta http-equiv=\"Content-Security-Policy\" content=\"${PrintPages.POLICY}\">"), html.take(400))
        assertTrue(html.contains("src=\"img/ph1-2000-1.jpg\""))
        assertTrue(html.contains("url('fonts/caveat.ttf')"))
        PrintPages.FONTS.forEach { assertNotNull(files.read("$EXPORTS/fonts/$it"), it) }
        assertEquals(listOf("Getting photos ready · 1 of 3", "Getting photos ready · 2 of 3", "Getting photos ready · 3 of 3"), steps)
        // Made small (2000 px for Large), upright, a plain JPEG without the camera's tags.
        val photo = files.read("$EXPORTS/img/ph1-2000-1.jpg")!!
        assertEquals(2000 to 1500, Pictures.size(photo))
        assertFalse(String(photo, Charsets.ISO_8859_1).contains("Exif"))
        assertTrue(String(files.read("media/ph1.jpg")!!, Charsets.ISO_8859_1).contains("Exif"))
    }

    @Test
    fun theStandInWritesThePdfPagesAndReadsThemBack() = runBlocking {
        val files = MemoryMediaFiles()
        val (book, tripId) = bookWithPhotos(files)
        val printer = DesktopPagePrinter()
        val exports = exports(book, files, printer)
        val loaded = exports.load(tripId)
        for (options in listOf(ExportOptions(), ExportOptions(PrintTheme.Minimal, Paper.Letter, PhotoSize.Small, includePlan = true))) {
            val pdf = exports.pdf(loaded, options) {}
            assertTrue(pdf.startsWith("$EXPORTS/pdf-") && pdf.endsWith("/Naxos.pdf"), pdf)
            val bytes = files.read(pdf)!!
            assertEquals("%PDF-1.4", String(bytes, 0, 8, Charsets.ISO_8859_1))
            val pageCount = PagesPdf.read(bytes).size
            // The cover, and a page for each day with something on it (the plan's days too when it is in).
            val days = loaded.days(options).size
            assertTrue(pageCount >= 1 + days, "$pageCount pages, $days days")
            assertTrue(String(bytes, Charsets.ISO_8859_1).contains("/Count $pageCount"))
            val pages = printer.open(files, pdf)!!
            assertEquals(pageCount, pages.count)
            assertEquals((options.paper.widthMm / options.paper.heightMm).toFloat(), pages.aspect, 0.01f)
            val first = pages.render(0, 400)!!
            assertEquals(400, first.width)
            // The HTML it was made from stays beside it on the desktop, to open in a browser; the page itself is gone.
            assertNotNull(files.read("$pdf.html"))
            assertTrue(files.paths.none { it.startsWith("$EXPORTS/page-") })
            exports.discard(pdf)
        }
        // The stand-in has no printer.
        assertFalse(printer.print(files, "x.pdf", "Naxos"))
    }

    @Test
    fun aTripThatIsGoneSaysSo() {
        val files = MemoryMediaFiles()
        val e = runCatching { exports(smallTripBook(), files).load("nope") }.exceptionOrNull()
        assertEquals("This trip isn't here any more.", e?.message)
    }

    @Test
    fun theWordFileIsWhatAndroidWritesForTheSamePhotos() = runBlocking {
        val files = MemoryMediaFiles()
        val (book, tripId) = bookWithPhotos(files)
        val exports = exports(book, files)
        val loaded = exports.load(tripId)
        val steps = ArrayList<String>()
        val path = exports.docx(loaded, ExportOptions(includePlan = true)) { steps += it.text }
        assertEquals("$EXPORTS/Naxos.docx", path)
        assertTrue(steps.contains("Getting photos ready · 3 of 3"))
        fun parts(zip: ByteArray) = LinkedHashMap<String, ByteArray>().also { out ->
            ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.forEach { out[it.name] = z.readBytes() } }
        }
        val here = parts(files.read(path)!!)
        // Android's own TripDocx, given the same photos as files (made for Word: 1600 px for Large, quality 82).
        val dir = kotlin.io.path.createTempDirectory("docx").toFile()
        val android = java.io.ByteArrayOutputStream()
        AndroidDocx.write(
            AndroidBook(loaded.trip, loaded.entries, loaded.plan, loaded.media, loaded.track, loaded.stops),
            AndroidOptions(includePlan = true),
            android,
            { m ->
                exports.photos.scaled(m, 1600, quality = 82)?.let { p ->
                    val f = java.io.File(dir, "${m.id}.jpg").apply { writeBytes(files.read(p.path)!!) }
                    AndroidDocx.Photo(f, p.width, p.height)
                }
            },
            // English in the phone's region, as this phone writes it.
            java.util.Locale("en", java.util.Locale.getDefault().country),
        )
        val theirs = parts(android.toByteArray())
        assertEquals(theirs.keys.toList(), here.keys.toList())
        theirs.forEach { (name, bytes) -> assertContentEquals(bytes, here.getValue(name), name) }
        assertEquals(3, here.keys.count { it.startsWith("word/media/") })
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun theTextIsAndroidsAndAFileWhenLong() {
        val files = MemoryMediaFiles()
        val (book, tripId) = bookWithPhotos(files)
        val exports = exports(book, files)
        val loaded = exports.load(tripId)
        val text = exports.text(loaded, ExportOptions(includePlan = true))
        assertEquals(TripText.build(loaded, ExportOptions(includePlan = true), com.justdataplease.boringtravel.compat.Locale.US), text)
        assertTrue(text.startsWith("Naxos\nParos → Naxos\n"), text)
        assertTrue(text.contains("The harbour"))
        val path = exports.textFile(loaded, ExportOptions(), text)
        assertEquals("$EXPORTS/Naxos.txt", path)
        assertEquals(text, files.read(path)!!.decodeToString())
    }

    @Test
    fun theChoicesAreKeptAndTravelInBackups() {
        val settings = AppSettings(MemorySettingsFile())
        assertEquals(PrintTheme.Notebook, settings.printTheme)
        assertEquals(PrintChoices(), settings.printChoices)
        settings.printTheme = PrintTheme.Minimal
        settings.setPrintChoices(PrintChoices(photoSize = "l"))
        settings.setPrintChoices(PrintChoices(paper = "letter", plan = true))
        assertEquals(PrintChoices("l", "letter", null, true), settings.printChoices)
        val prefs = settings.backupPreferences()
        assertEquals("minimal", prefs.printTheme)
        assertEquals("l", prefs.printPhotos)
        assertEquals("letter", prefs.printPaper)
        assertEquals(true, prefs.printPlan)
        // A restore puts them back on another phone; an older Postcard opens the screen as Notebook.
        val other = AppSettings(MemorySettingsFile())
        other.restorePreferences(BackupJson.decodeFromString(BackupPreferences.serializer(), BackupJson.encodeToString(BackupPreferences.serializer(), prefs.copy(printTheme = "postcard"))), replace = true)
        assertEquals(PrintTheme.Postcard, other.printTheme)
        assertEquals(PrintTheme.Notebook, PrintTheme.forShare(other.printTheme))
        assertEquals(PrintChoices("l", "letter", null, true), other.printChoices)
    }

    /**
     * Round 23: Google Docs as Android's: Drive not connected yet, so Google's page connects it first (the fake page and
     * Drive) and the save goes on by itself; the trip becomes a Google Doc in BoringTravel › Exports (Android's plainer
     * Docs HTML with the photos inside it, converted by Drive), "Saved to Google Docs" with Open and Share link; Share
     * link lets anyone with the link open it and hands the link to the share sheet.
     */
    @Test
    fun googleDocsConnectsDriveSavesTheDocAndSharesItsLink() {
        val files = MemoryMediaFiles()
        val (book, tripId) = bookWithPhotos(files)
        val settings = AppSettings.ready()
        val google = FakeGoogle()
        val account = google.account(settings, files)
        val links = ArrayList<Pair<String, String>>()
        val phone = Phone(share = { subject, text -> links += subject to text; true })
        runDesktopComposeUiTest(width = 440, height = 1600) {
            setContent {
                CompositionLocalProvider(LocalPhone provides phone, LocalPagePrinter provides DesktopPagePrinter()) {
                    BoringTravelApp(book, settings, SAMPLE_TODAY, startAt = listOf(Place.Trip(tripId), Place.Share(tripId)), media = MediaKit(files), google = account)
                }
            }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("PREVIEW · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            // Closed at Google's page: Android's words, nothing saved.
            google.browser.mode = FakeAuthBrowser.Mode.Closed
            onNodeWithText("Google Docs").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Google Docs saves the document in your Drive.").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(google.cloud.items.isEmpty())
            // Connected on the way, then saved.
            google.browser.mode = FakeAuthBrowser.Mode.Accept
            onNodeWithText("Google Docs").performScrollTo().performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Saved to Google Docs").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(settings.driveConnected)
            val doc = google.cloud.filesIn(com.justdataplease.boringtravel.google.DriveApi.EXPORTS).single()
            assertEquals("Naxos", doc.name)
            assertEquals(com.justdataplease.boringtravel.google.DriveApi.GOOGLE_DOC, doc.mimeType)
            // Android's Docs HTML: a page per day, the photos inside it.
            assertTrue(doc.text.contains("data:image/jpeg;base64,"), doc.text.take(400))
            assertTrue(doc.text.contains("<html"), doc.text.take(200))
            // In the notice and in the sheet with Open and Share link.
            assertEquals(2, onAllNodesWithText("In Drive › BoringTravel › Exports.", substring = true).fetchSemanticsNodes().size)
            onNodeWithText("Open").assertExists()
            onNodeWithText("Share link").performClick()
            waitUntil(timeoutMillis = 10_000) { links.isNotEmpty() }
            assertEquals("Naxos" to "https://docs.google.com/document/d/${doc.id}/edit", links.single())
            assertTrue(doc.anyone)
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Anyone with the link can open it. It stays in your Google Drive.").fetchSemanticsNodes().isNotEmpty() }
            // Drive disconnected meanwhile (in Me › Backup & sync): Google's page connects it again and the save goes on.
            runBlocking { account.disconnectDrive() }
            onNodeWithText("Google Docs").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Saved to Google Docs").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun theScreenPreviewsTheOptionsAndHandsEachFormatOn() {
        val files = MemoryMediaFiles()
        val (book, tripId) = bookWithPhotos(files)
        val settings = AppSettings.ready()
        val printer = DesktopPagePrinter()
        val copied = ArrayList<String>()
        val shared = ArrayList<Triple<String, String, ByteArray>>()
        val subjects = ArrayList<String>()
        val saved = ArrayList<String>()
        val phone = Phone(
            copy = { copied += it },
            shareFile = { name, mime, bytes, subject -> shared += Triple(name, mime, bytes); subjects += subject; true },
            exportFile = { path, done -> saved += path; done(true) },
        )
        runDesktopComposeUiTest(width = 440, height = 1600) {
            setContent {
                CompositionLocalProvider(LocalPhone provides phone, LocalPagePrinter provides printer) {
                    BoringTravelApp(book, settings, SAMPLE_TODAY, startAt = listOf(Place.Trip(tripId)), media = MediaKit(files))
                }
            }
            onNodeWithContentDescription("Trip options").performClick()
            onNodeWithText("Share").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("PREVIEW · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Tap a page to see it bigger.").assertExists()
            onNodeWithText("Whole trip · 4 days").assertExists()

            // Large photos: kept, and the pages are made again.
            onNodeWithText("Large").performClick()
            assertEquals("l", settings.printChoices.photoSize)
            waitUntil(timeoutMillis = 20_000) { files.paths.any { it.startsWith("$EXPORTS/img/ph1-2000-") } && onAllNodesWithText("PREVIEW · ", substring = true).fetchSemanticsNodes().isNotEmpty() }

            // Text: Copy.
            onNodeWithText("Text").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Copy").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Copy").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Copied.").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(copied.single().startsWith("Naxos\n"))

            // Word: Share hands the file on.
            onNodeWithText("Word").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("Word file").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("Share").onLast().performClick()
            waitUntil(timeoutMillis = 5_000) { shared.isNotEmpty() }
            assertEquals("Naxos.docx", shared.single().first)
            assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", shared.single().second)
            assertEquals("PK", String(shared.single().third, 0, 2, Charsets.ISO_8859_1))
            // The export's title as the subject, as Android's Sharing.share gives it (EXTRA_SUBJECT).
            assertTrue(subjects.single().startsWith("Naxos"), subjects.single())

            // PDF: Save to phone, then Print (no printer here).
            onNodeWithText("PDF").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Save to phone").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Save to phone").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Saved.").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(saved.single().endsWith("/Naxos.pdf"), saved.single())
            onNodeWithText("PDF").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Print").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Print").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Couldn't open the print dialog.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Naxos", printer.printed.single().first)

            // Google Docs needs the Google account.
            onNodeWithText("Google Docs").performScrollTo().performClick()
            onNodeWithText("Google Drive isn't connected.").assertExists()

            // Minimal: kept as the style.
            onNodeWithText("Minimal").performScrollTo().performClick()
            assertEquals(PrintTheme.Minimal, settings.printTheme)
        }
    }
}

package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.compat.OutputStream
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.export.ExportFiles
import com.justdataplease.boringtravel.export.ExportOptions
import com.justdataplease.boringtravel.export.ExportTarget
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.DriveFile
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.export.Paper
import com.justdataplease.boringtravel.export.PrintCss
import com.justdataplease.boringtravel.export.TripDocx
import com.justdataplease.boringtravel.export.TripHtml
import com.justdataplease.boringtravel.export.TripText
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import com.justdataplease.boringtravel.export.TripBook as ExportBook

/*
 * Sharing a trip (the Android app's export/TripExporter.kt, PhotoPrep.kt and PdfMaker.kt): the trip loaded once from
 * the trip store, its photos made ready, then the Android app's own print HTML (export/TripHtml.kt, read in) made into
 * a PDF by the phone ([PagePrinter]: WebKit on iPhone), its plain text (TripText) or its Word file (TripDocx). Files go
 * under the app's files in "exports/", as Android's go to its cache: photos in "exports/img/", the fonts the pages
 * use in "exports/fonts/", each PDF in a folder of its own. The app empties "exports/" when it starts.
 */

/** What the Share screen shows while it works; [fraction] null = no known end. */
data class ExportProgress(val text: String, val fraction: Float? = null)

/** A failure with a message meant for the traveller (plain words). */
class ExportException(message: String) : Exception(message)

/** Where the exports live under the app's files. */
const val EXPORTS = "exports"

/**
 * The pages of a finished PDF, for the preview: the very file that is then shared, saved or printed (the Android app's
 * ui/export/PdfPreview.kt PdfPages, over PdfRenderer). PDFKit on iPhone.
 */
interface PdfPages {
    val count: Int

    /** Width / height of the first page (every page has the same paper). */
    val aspect: Float

    /** Page [index] drawn [widthPx] wide (at most [MAX_PX]); null when it can't be drawn. */
    suspend fun render(index: Int, widthPx: Int): ImageBitmap?

    fun close() {}

    companion object {
        /** The biggest page picture, for the full-screen view. */
        const val MAX_PX = 1600
    }
}

/**
 * What prints the trip's pages (the Android app's export/PdfMaker.kt): the print HTML laid out by the phone's web view
 * on the paper and written as a PDF, the pages of a PDF read back, and the phone's print dialog. iPhone: WKWebView's
 * print formatter through UIPrintPageRenderer into a PDF context, PDFKit, UIPrintInteractionController
 * (iosMain/…/IosPrint.kt); the desktop: a stand-in that draws the HTML's pages itself (desktopMain/…/DesktopPrint.kt).
 */
interface PagePrinter {
    /**
     * Lays [html] out on [paper], as the page at [htmlPath] under [files] (the photos and fonts it names are beside it,
     * in "img/" and "fonts/"), and writes the PDF to [pdfPath]. [footer] is the trip's name, printed at the foot of
     * every page but the cover with the page's number. Returns the number of pages.
     */
    suspend fun writePdf(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, paper: Paper, theme: PrintTheme, footer: String): Int

    /** The pages of the PDF at [pdfPath]; null when it can't be read. */
    suspend fun open(files: MediaFiles, pdfPath: String): PdfPages?

    /** The phone's print dialog for the PDF at [pdfPath], as it is; false when this phone can't print. */
    fun print(files: MediaFiles, pdfPath: String, jobName: String): Boolean

    /**
     * A book's pages (the Android app's book/BookExporter.kt pdf, printed by a WebView at the book's own media size):
     * [html] is book/BookHtml.kt's, one fixed-size section per page, laid out as the page at [htmlPath] under [files]
     * (its photos and fonts beside it in "img/" and "fonts/") on sheets [sheet] big with no margins, and written as the
     * PDF at [pdfPath], one sheet per page. [ground] is the colour under each page (the book's paper, white with bleed).
     * Returns the number of pages.
     */
    suspend fun writeBook(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, sheet: BookSheetSize, ground: Long): Int =
        throw ExportException("The PDF came out empty. Try again.")
}

/** A book's sheet in mm (book/PageGeometry.kt's Sheet: the trim, with bleed and crop marks around it when asked). */
data class BookSheetSize(val widthMm: Double, val heightMm: Double)

/** A phone that makes no pages: a PDF fails with a plain message (tests that never print). */
object NoPagePrinter : PagePrinter {
    override suspend fun writePdf(files: MediaFiles, htmlPath: String, html: String, pdfPath: String, paper: Paper, theme: PrintTheme, footer: String): Int =
        throw ExportException("The PDF came out empty. Try again.")

    override suspend fun open(files: MediaFiles, pdfPath: String): PdfPages? = null

    override fun print(files: MediaFiles, pdfPath: String, jobName: String): Boolean = false
}

/** The phone's printer for the Share screen. */
val LocalPagePrinter = staticCompositionLocalOf<PagePrinter> { NoPagePrinter }

/**
 * Photos sized for an export (the Android app's export/PhotoPrep.kt): each decoded once and turned upright, made no
 * longer than the export needs and saved again as a plain JPEG on white, which also leaves the camera's location and
 * every other tag behind. A video gives its first frame. Print copies are kept in "exports/img/" by id, size and last
 * change, so a second export reuses them.
 */
class PhotoPrep(private val files: MediaFiles, private val frames: VideoFrames) {
    /** A photo ready for a document: where it is (under "exports/"), and its size as it prints. */
    data class Prepared(val path: String, val width: Int, val height: Int) {
        val aspect: Double get() = width.toDouble() / height

        /** Its address in the print HTML, which sits in "exports/". */
        val src: String get() = path.removePrefix("$EXPORTS/")
    }

    /** A photo inside the document itself (Google Docs): `data:image/jpeg;base64,…` and its size as it prints. */
    data class Inline(val src: String, val width: Int, val height: Int) {
        val aspect: Double get() = width.toDouble() / height
    }

    /** `data:image/jpeg;base64,…` for Google Docs, with its size; null when the photo can't be read (Android's PhotoPrep.data). */
    fun data(m: Media, maxPx: Int, quality: Int = 80): Inline? =
        encode(m, maxPx, quality)?.let { Inline("data:image/jpeg;base64," + com.justdataplease.boringtravel.compat.Base64.getEncoder().encodeToString(it.bytes), it.width, it.height) }

    /** A JPEG copy of [m] no longer than [maxPx] on its longest side; null when the photo can't be read. */
    fun scaled(m: Media, maxPx: Int, quality: Int = 86): Prepared? {
        val path = "$EXPORTS/img/${m.id.filter { it.isLetterOrDigit() || it == '-' }}-$maxPx-${m.updatedAt}.jpg"
        files.read(path)?.takeIf { it.isNotEmpty() }?.let { bytes ->
            Pictures.size(bytes)?.let { (w, h) -> return Prepared(path, w, h) }
        }
        val made = encode(m, maxPx, quality) ?: return null
        if (!files.write(path, made.bytes)) return null
        return Prepared(path, made.width, made.height)
    }

    private class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    private fun encode(m: Media, maxPx: Int, quality: Int): Encoded? = runCatching {
        val source = (if (m.isVideo) frames.poster(m.localPath, maxPx) else files.read(m.localPath)) ?: return null
        // A HEIC or AVIF photo (kept as Android keeps it) through the phone's reader first.
        val data = Data.makeFromBytes(Images.drawable(source, maxPx) ?: source)
        val codec = Codec.makeFromData(data)
        try {
            // A JPEG is read at a half, quarter or eighth of its size when that is still big enough (what Android's
            // ImageDecoder does with its target size): a 48 MP photo never sits in memory whole.
            val bitmap = Pictures.readSmall(codec, maxPx)
            val image = Image.makeFromBitmap(bitmap)
            val origin = codec.encodedOrigin
            val swap = origin.swapsWidthHeight()
            val ow = if (swap) bitmap.height else bitmap.width
            val oh = if (swap) bitmap.width else bitmap.height
            val (tw, th) = ExportFiles.fit(ow, oh, maxPx)
            val surface = Surface.makeRasterN32Premul(tw, th)
            val canvas: Canvas = surface.canvas
            // JPEG has no transparency: a see-through picture goes on white, not black.
            canvas.clear(0xFFFFFFFF.toInt())
            canvas.scale(tw.toFloat() / ow, th.toFloat() / oh)
            canvas.concat(origin.toMatrix(bitmap.width, bitmap.height))
            canvas.drawImageRect(
                image, Rect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()), Rect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), Paint(), true,
            )
            val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, quality)?.bytes
            image.close()
            bitmap.close()
            surface.close()
            bytes?.let { Encoded(it, tw, th) }
        } finally {
            codec.close()
            data.close()
        }
    }.getOrNull()
}

/** A file under the app's files, as TripDocx reads a photo (compat's java.io.File). */
class AppFileStore(private val files: MediaFiles) : File.Store {
    override fun isFile(path: String): Boolean = (files.size(path) ?: 0L) > 0L

    override fun read(path: String): ByteArray? = files.read(path)

    override fun size(path: String): Long? = files.size(path)
}

/** A file under the app's files written as it goes, as TripDocx writes into a FileOutputStream. */
private class AppFileStream(private val files: MediaFiles, private val path: String) : OutputStream() {
    private val buffer = ByteArray(64 * 1024)
    private var count = 0
    var failed = false
        private set

    override fun write(b: Int) {
        if (count == buffer.size) flush()
        buffer[count++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len >= buffer.size) {
            flush()
            if (!files.append(path, b.copyOfRange(off, off + len))) failed = true
            return
        }
        if (count + len > buffer.size) flush()
        b.copyInto(buffer, count, off, off + len)
        count += len
    }

    override fun flush() {
        if (count == 0) return
        if (!files.append(path, buffer, count)) failed = true
        count = 0
    }

    override fun close() = flush()
}

/**
 * One trip's exports (the Android app's export/TripExporter.kt): [book] is the trip store, [files] the app's files
 * (photos are read there, exports written under "exports/"), [frames] a video's first frame, [printer] the phone's
 * pages. Screens call this; it never touches the screen.
 */
class TripExports(
    private val book: TripBook,
    private val files: MediaFiles,
    frames: VideoFrames,
    val printer: PagePrinter,
    private val now: () -> Long = ::nowMs,
) {
    val photos = PhotoPrep(files, frames)
    private var made = 0

    /** Everything one export reads, loaded once: the trip, its notes, plan, photos (also those of its notes filed without the trip), route and stops. */
    fun load(tripId: String): ExportBook {
        val rows = book.snapshot
        val trip = rows.trips.firstOrNull { it.id == tripId && it.deletedAt == null } ?: throw ExportException("This trip isn't here any more.")
        val entries = rows.entries.filter { it.tripId == tripId && it.deletedAt == null }
        val media = rows.media.filter { it.tripId == tripId }.toMutableList()
        val known = media.mapTo(HashSet()) { it.id }
        entries.asSequence().flatMap { EntryDoc.parse(it.body).mediaIds() }.distinct().filter { it !in known }.forEach { id ->
            rows.media.firstOrNull { it.id == id && it.deletedAt == null }?.let { media += it; known += id }
        }
        return ExportBook(
            trip, entries, rows.planItems.filter { it.tripId == tripId && it.deletedAt == null }, media,
            rows.trackPoints.filter { it.tripId == tripId }, rows.stops.filter { it.tripId == tripId },
        )
    }

    /** "Cyclades, slowly" for the whole trip, "Cyclades, slowly, 19–21 Jun 2027" for some days. */
    fun title(book: ExportBook, options: ExportOptions): String {
        if (options.wholeTrip) return book.trip.title
        val from = options.from ?: book.trip.startDate ?: return book.trip.title
        val to = options.to ?: book.trip.endDate ?: from
        return "${book.trip.title}, ${Dates.range(from, to)}"
    }

    /** The print HTML with photos made ready for paper and measured for the layout; progress per photo. */
    suspend fun printHtml(book: ExportBook, options: ExportOptions, progress: (ExportProgress) -> Unit): String = withContext(MediaWork) {
        fonts()
        val total = photoCount(book, options).coerceAtLeast(1)
        var done = 0
        val maxPx = TripHtml.printPhotoMaxPx(options.photoSize)
        val ready = HashMap<String, PhotoPrep.Prepared>()
        val context = currentCoroutineContext()
        PrintPages.lockedDown(
            TripHtml.build(
                book, options,
                photoSrc = { m ->
                    context.ensureActive()
                    done++
                    progress(ExportProgress("Getting photos ready · $done of $total", (done.toFloat() / total).coerceAtMost(1f)))
                    photos.scaled(m, maxPx)?.also { ready[m.id] = it }?.src
                },
                fontBase = "fonts/",
                photoAspect = { m -> ready[m.id]?.aspect },
            ),
        )
    }

    /** The fonts the pages name ([PrintCss.fonts]), written once beside them. */
    private fun fonts() {
        for (name in PrintPages.FONTS) {
            val path = "$EXPORTS/fonts/$name"
            if ((files.size(path) ?: 0L) > 0L) continue
            BtFontFiles.file(name)?.let { files.write(path, it) }
        }
    }

    /**
     * A PDF for the preview, and then for sharing, saving and printing: each in a folder of its own, so its name stays
     * the trip's ("Cyclades, slowly.pdf") while an older one is still being shared.
     */
    suspend fun pdf(book: ExportBook, options: ExportOptions, progress: (ExportProgress) -> Unit): String {
        val html = printHtml(book, options, progress)
        progress(ExportProgress("Making the pages"))
        val n = ++made
        val stamp = now()
        val htmlPath = "$EXPORTS/page-$stamp-$n.html"
        val pdf = "$EXPORTS/pdf-$stamp-$n/${ExportFiles.fileName(title(book, options), "pdf")}"
        val pages = try {
            printer.writePdf(files, htmlPath, html, pdf, options.paper, options.theme, book.trip.title)
        } catch (e: Throwable) {
            discard(pdf)
            throw e
        } finally {
            files.delete(htmlPath)
        }
        if (pages <= 0 || (files.size(pdf) ?: 0L) == 0L) {
            discard(pdf)
            throw ExportException("The PDF came out empty. Try again.")
        }
        return pdf
    }

    /** Deletes a preview PDF no one was given, with its own folder. */
    fun discard(path: String) {
        val folder = path.substringBeforeLast('/', "")
        files.delete(if (folder.startsWith("$EXPORTS/pdf-")) folder else path)
    }

    /** The trip as plain text ([TripText]). */
    fun text(book: ExportBook, options: ExportOptions): String = TripText.build(book, options)

    /** [text] as a file, for a trip too long to hand over as a message. */
    fun textFile(book: ExportBook, options: ExportOptions, text: String): String {
        val path = "$EXPORTS/${ExportFiles.fileName(title(book, options), "txt")}"
        files.delete(path)
        if (!files.write(path, text.encodeToByteArray())) throw ExportException("Couldn't make the text.")
        return path
    }

    /** A Word file ([TripDocx]) in "exports/", photos made small as for Google Docs. */
    suspend fun docx(book: ExportBook, options: ExportOptions, progress: (ExportProgress) -> Unit): String = withContext(MediaWork) {
        val total = photoCount(book, options).coerceAtLeast(1)
        val maxPx = ExportFiles.docsMaxPx(options.photoSize, total)
        var done = 0
        val path = "$EXPORTS/${ExportFiles.fileName(title(book, options), "docx")}"
        files.delete(path)
        val store = AppFileStore(files)
        val context = currentCoroutineContext()
        val out = AppFileStream(files, path)
        try {
            TripDocx.write(book, options, out, photo = { m ->
                context.ensureActive()
                done++
                progress(ExportProgress("Getting photos ready · $done of $total", (done.toFloat() / total).coerceAtMost(1f)))
                photos.scaled(m, maxPx, quality = 82)?.let { TripDocx.Photo(File(it.path, store), it.width, it.height) }
            })
            out.flush()
            if (out.failed) throw ExportException("Couldn't make the Word file.")
        } catch (e: Throwable) {
            files.delete(path)
            throw e
        }
        path
    }

    /**
     * Uploads a plainer HTML version to Drive, converted to a Google Doc (the Android app's TripExporter.saveToDocs):
     * text, headings and photos (inside the HTML, made smaller by their count: ExportFiles.docsMaxPx). Returns the new
     * Doc, in BoringTravel › Exports. Throws DriveNotConnectedException when Drive isn't connected ([drive] null: no
     * Google in this build).
     */
    suspend fun saveToDocs(book: ExportBook, options: ExportOptions, drive: DriveApi?, progress: (ExportProgress) -> Unit): DriveFile {
        val d = requireDrive(drive)
        val title = title(book, options)
        val path = withContext(MediaWork) {
            val total = photoCount(book, options)
            val maxPx = ExportFiles.docsMaxPx(options.photoSize, total)
            var done = 0
            val ready = HashMap<String, PhotoPrep.Inline>()
            val context = currentCoroutineContext()
            val html = TripHtml.build(
                book, options,
                photoSrc = { m ->
                    context.ensureActive()
                    done++
                    progress(ExportProgress("Getting photos ready · $done of ${total.coerceAtLeast(1)}", (done.toFloat() / total.coerceAtLeast(1)).coerceAtMost(1f)))
                    photos.data(m, maxPx)?.also { ready[m.id] = it }?.src
                },
                target = ExportTarget.Docs,
                photoAspect = { m -> ready[m.id]?.aspect },
            )
            val p = "$EXPORTS/${ExportFiles.fileName(title, "html")}"
            files.delete(p)
            if (!files.write(p, html.encodeToByteArray())) throw ExportException("Couldn't make the document.")
            p
        }
        progress(ExportProgress("Sending to Google Docs"))
        try {
            return driveCall { d.upload(path, title, "text/html", d.folder(DriveApi.EXPORTS), convertTo = DriveApi.GOOGLE_DOC) }
        } finally {
            files.delete(path)
        }
    }

    private fun photoCount(book: ExportBook, options: ExportOptions): Int {
        val ids = book.days(options).flatMap { d -> d.photos.map { it.id } }.toMutableSet()
        book.trip.coverMediaId?.let { ids += it }
        return ids.size
    }

    companion object {
        /** Puts the PDF at [pdf] in Drive › BoringTravel › Exports as "<title>.pdf" (Android's savePdfToDrive). */
        suspend fun savePdfToDrive(drive: DriveApi?, pdf: String, title: String, progress: (ExportProgress) -> Unit): DriveFile {
            val d = requireDrive(drive)
            progress(ExportProgress("Uploading to Google Drive"))
            return driveCall { d.upload(pdf, ExportFiles.fileName(title, "pdf"), "application/pdf", d.folder(DriveApi.EXPORTS)) }
        }

        /** Lets anyone with the link open [file] and returns the link. */
        suspend fun shareLink(drive: DriveApi?, file: DriveFile): String = driveCall { requireDrive(drive).shareLink(file) }

        /** Drive, when it is connected; else Android's DriveNotConnectedException. */
        suspend fun requireDrive(drive: DriveApi?): DriveApi {
            if (drive == null || !drive.isConnected()) throw DriveNotConnectedException()
            return drive
        }

        /** Drive's failures in Android's words: no connection, or Drive didn't take the file. */
        private suspend fun <T> driveCall(block: suspend () -> T): T = withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: DriveNotConnectedException) {
                throw e
            } catch (e: com.justdataplease.boringtravel.io.IOException) {
                throw ExportException("Couldn't reach Google Drive. Check the connection and try again.")
            } catch (e: Exception) {
                throw ExportException("Google Drive didn't take the file. Try again later.")
            }
        }
    }
}

/** What the print HTML needs before the phone's web view may read it. */
object PrintPages {
    /** The font files [PrintCss.fonts] names. */
    val FONTS = listOf(
        "literata.ttf", "literata_italic.ttf", "albert_sans.ttf", "caveat.ttf", "plex_mono_regular.ttf", "plex_mono_medium.ttf",
        "plex_mono_semibold.ttf", "barlow_regular.ttf", "barlow_medium.ttf", "barlow_semibold.ttf", "barlow_condensed_semibold.ttf",
        "barlow_condensed_bold.ttf",
    )

    /**
     * [html] with a content security policy after its charset, as Android's WebView is locked down: no script, nothing
     * from the network; only the photos and fonts beside it and data URIs.
     */
    fun lockedDown(html: String): String = html.replaceFirst(
        "<meta charset=\"utf-8\">",
        "<meta charset=\"utf-8\">\n<meta http-equiv=\"Content-Security-Policy\" content=\"$POLICY\">",
    )

    const val POLICY = "default-src 'none'; img-src 'self' file: data:; font-src 'self' file: data:; style-src 'unsafe-inline'"

    /** The page margins in mm the print HTML is laid out for: top, sides, bottom ([PrintCss]). */
    val MARGINS_MM = Triple(PrintCss.MARGIN_TOP_MM, PrintCss.MARGIN_SIDE_MM, PrintCss.MARGIN_BOTTOM_MM)

    /** The page's ground: the notebook's off-white paper for Notebook and Scrapbook, else white (the CSS @page rule). */
    fun pageColor(theme: PrintTheme): Long = if (theme == PrintTheme.Scrapbook || theme == PrintTheme.Notebook) 0xFFFCFCF9 else 0xFFFFFFFF

    /** The footer's ink: Caveat in blue-grey on the notebook styles, small grey mono on Postcard (the CSS @page rule). */
    fun footerColor(theme: PrintTheme): Long = if (theme == PrintTheme.Postcard) 0xFF8A9096 else 0xFF525C86
}

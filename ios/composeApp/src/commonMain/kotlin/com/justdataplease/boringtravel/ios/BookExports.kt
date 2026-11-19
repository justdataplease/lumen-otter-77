package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.book.BookCss
import com.justdataplease.boringtravel.book.BookDoc
import com.justdataplease.boringtravel.book.BookFonts
import com.justdataplease.boringtravel.book.BookFormat
import com.justdataplease.boringtravel.book.BookHtml
import com.justdataplease.boringtravel.book.BookJson
import com.justdataplease.boringtravel.book.BookLayout
import com.justdataplease.boringtravel.book.BookSpec
import com.justdataplease.boringtravel.book.Sheet
import com.justdataplease.boringtravel.book.TextMeasure
import com.justdataplease.boringtravel.book.phoneTextMeasure
import com.justdataplease.boringtravel.data.db.BookDraft
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.export.ExportFiles
import com.justdataplease.boringtravel.model.PrintTheme
import com.justdataplease.boringtravel.time.nowMs
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.justdataplease.boringtravel.export.TripBook as ExportBook

/*
 * Books on this phone (the Android app's book/BookExporter.kt): drafts kept in the trip store's `books` rows (the
 * engine's TripBook, the same rows a backup carries, so a book moves between the phones), photos made ready for the page
 * (screen size for the pages on screen, about 300 dpi where they are placed for the PDF; upright, location and camera
 * data left behind: round 13's PhotoPrep), and the print-ready PDF through the phone's printer at the book's own sheet
 * size (WebKit on iPhone: PagePrinter.writeBook). Files go under "exports/" as Share's do, emptied at every start.
 */

/** A folder under the app's files where a page's HTML, its photos ("img/") and fonts ("fonts/") sit together. */
class BookFolder(val files: MediaFiles, val path: String)

/**
 * The trip store's books and their files: [book] the trip store, [files] the app's files, [frames] a video's first
 * frame, [printer] the phone's pages, [measure] how the book's text is measured ([phoneTextMeasure]: Core Text on
 * iPhone, Skia on the desktop).
 */
class BookExports(
    private val book: TripBook,
    private val files: MediaFiles,
    frames: VideoFrames,
    val printer: PagePrinter,
    val measure: TextMeasure = phoneTextMeasure(),
    private val now: () -> Long = ::nowMs,
    private val ids: () -> String = { com.justdataplease.boringtravel.data.repo.newId() },
) {
    private val exports = TripExports(book, files, frames, printer, now)
    private val photos: PhotoPrep get() = exports.photos
    private var made = 0

    /** Where the pages' HTML, photos and fonts sit (the Android app's photoDir: its cache's exports folder). */
    val photoDir: BookFolder = BookFolder(files, EXPORTS)

    /** The trip, with photo sizes filled in where the import didn't record them (needed for crops and fitting). */
    suspend fun loadTrip(tripId: String): ExportBook = withContext(MediaWork) {
        val trip = exports.load(tripId)
        trip.copy(media = trip.media.map { m ->
            if (m.width > 0 && m.height > 0) m else {
                val bytes = if (m.isVideo) null else files.read(m.localPath)
                bytes?.let { Pictures.size(it) }?.let { (w, h) -> m.copy(width = w, height = h) } ?: m
            }
        })
    }

    /** The trip's newest book draft (BookDao.latestForTrip). */
    fun latest(tripId: String): BookDraft? = book.latestBook(tripId)

    /** Keeps [draft] (BookDao.upsert). */
    fun save(draft: BookDraft) {
        book.saveBook(draft)
    }

    /** A new draft row for [spec] and [doc]. */
    fun newDraft(tripId: String, spec: BookSpec, doc: BookDoc): BookDraft {
        val t = now()
        return BookDraft(ids(), tripId, spec.format.id, spec.theme.id, spec.fonts.id, spec.bleed, BookJson.encode(doc), t, t)
    }

    /** The spec stored with a draft (paper lives in its JSON). */
    fun spec(d: BookDraft, doc: BookDoc): BookSpec =
        BookSpec(BookFormat.of(d.format), PrintTheme.of(d.theme), BookFonts.of(d.fontSet), doc.paper, d.bleed)

    /** A copy of the photo for the page on screen ([PREVIEW_PX]), its address beside the page's HTML; null when it can't be read. */
    suspend fun previewUrl(tripBook: ExportBook, mediaId: String): String? = withContext(MediaWork) {
        val m = tripBook.media(mediaId) ?: return@withContext null
        writeFonts()
        photos.scaled(m, PREVIEW_PX)?.src
    }

    /** The book's fonts beside its pages (the Android app's app files; here written once into "exports/fonts/"). */
    fun writeFonts() {
        for (name in BOOK_FONTS) {
            val path = "$EXPORTS/fonts/$name"
            if ((files.size(path) ?: 0L) > 0L) continue
            BtFontFiles.file(name)?.let { files.write(path, it) }
        }
    }

    /**
     * The print-ready PDF (BookExporter.pdf): every photo made ready at the pixels its largest use on paper needs (about
     * 300 dpi, BookHtml.photoNeeds), progress per photo, then the pages printed at the book's sheet size. Its path,
     * "<Trip> book.pdf" in a folder of its own under "exports/".
     */
    suspend fun pdf(layout: BookLayout, doc: BookDoc, title: String, progress: (ExportProgress) -> Unit): String {
        // Laying out every page (measuring its text word by word) grows with the book: never on the main thread.
        val needs = withContext(kotlinx.coroutines.Dispatchers.Default) { BookHtml.photoNeeds(layout, doc) }
        val context = currentCoroutineContext()
        val urls = withContext(MediaWork) {
            writeFonts()
            val out = HashMap<String, String>()
            var done = 0
            needs.forEach { (id, px) ->
                context.ensureActive()
                done++
                progress(ExportProgress("Getting photos ready · $done of ${needs.size}", done.toFloat() / needs.size.coerceAtLeast(1)))
                val m = layout.facts.media(id) ?: return@forEach
                photos.scaled(m, px, quality = 92)?.let { out[id] = it.src }
            }
            out
        }
        progress(ExportProgress("Making the PDF"))
        val html = withContext(kotlinx.coroutines.Dispatchers.Default) {
            PrintPages.lockedDown(BookHtml.document(layout, doc, { id, _ -> urls[id] }, fontBase = "fonts/"))
        }
        val n = ++made
        val stamp = now()
        val htmlPath = "$EXPORTS/book-$stamp-$n.html"
        val pdf = "$EXPORTS/book-$stamp-$n/${ExportFiles.fileName("$title book", "pdf")}"
        val sheet = layout.pages.sheet
        val pages = try {
            printer.writeBook(files, htmlPath, html, pdf, BookSheetSize(sheet.pageW, sheet.pageH), ground(layout))
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

    /** Deletes a PDF no one was given, with its own folder. */
    fun discard(path: String) {
        val folder = path.substringBeforeLast('/', "")
        files.delete(if (folder.startsWith("$EXPORTS/book-")) folder else path)
    }

    /** The phone's print dialog for a finished PDF (the printer fits the pages to its paper); false when it can't print. */
    fun print(path: String, title: String): Boolean = printer.print(files, path, title)

    companion object {
        /** The longest side of a photo on the page on screen (the Android app's PREVIEW_PX). */
        const val PREVIEW_PX = 1400

        /** The font files the book's pages name (book/BookCss.kt's @font-face rules). */
        val BOOK_FONTS: List<String> = Regex("""url\('([^']+)'\)""").findAll(BookCss.fonts("")).map { it.groupValues[1] }.distinct().toList()

        /** The colour under every sheet of the PDF: the book's paper, or white around a bleed (the crop marks' margin). */
        fun ground(layout: BookLayout): Long =
            if (layout.spec.bleed) 0xFFFFFFFF else 0xFF000000 or layout.pages.look.paperColor.removePrefix("#").toLong(16)

        /** The sheet of [layout]'s book. */
        fun sheet(layout: BookLayout): Sheet = layout.pages.sheet
    }
}

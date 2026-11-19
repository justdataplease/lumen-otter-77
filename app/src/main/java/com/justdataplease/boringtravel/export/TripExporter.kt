package com.justdataplease.boringtravel.export

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.DriveFile
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** What the share screen shows while it works; [fraction] null = no known end. */
data class ExportProgress(val text: String, val fraction: Float? = null)

/** A failure with a message meant for the traveller (plain words). */
class ExportException(message: String) : Exception(message)

/**
 * Sharing one trip: loads the trip once, prepares photos, builds the HTML ([TripHtml]) and turns it into a PDF
 * (previewed, then shared, saved or printed), a Word file ([TripDocx]), plain text ([TripText]) or a Google Doc in
 * the owner's Drive. Screens call this; it never touches the UI.
 */
class TripExporter(private val app: Context, private val graph: AppGraph) {
    val photos = PhotoPrep(app)
    private val exportsDir: File get() = File(app.cacheDir, "exports").apply { mkdirs() }

    suspend fun load(tripId: String): TripBook = withContext(Dispatchers.IO) {
        val trip = graph.trips.get(tripId)?.takeIf { it.deletedAt == null } ?: throw ExportException("This trip isn't here any more.")
        val entries = graph.journal.forTrip(tripId)
        val media = graph.db.media().forTrip(tripId).toMutableList()
        val known = media.mapTo(HashSet()) { it.id }
        // Photos placed in an entry but filed without the trip.
        entries.asSequence().flatMap { EntryDoc.parse(it.body).mediaIds() }.distinct().filter { it !in known }.forEach { id ->
            graph.media.get(id)?.takeIf { it.deletedAt == null }?.let { media += it; known += id }
        }
        TripBook(trip, entries, graph.plan.items(tripId), media, graph.tracks.forTrip(tripId), graph.tracks.stops(tripId))
    }

    /** "Cyclades, slowly" for the whole trip, "Cyclades, slowly, 19–21 Jun 2027" for some days. */
    fun title(book: TripBook, options: ExportOptions): String {
        if (options.wholeTrip) return book.trip.title
        val from = options.from ?: book.trip.startDate ?: return book.trip.title
        val to = options.to ?: book.trip.endDate ?: from
        return "${book.trip.title}, ${Dates.range(from, to)}"
    }

    /** The print HTML with photos scaled for paper and measured for the layout; reports progress per photo. */
    suspend fun printHtml(book: TripBook, options: ExportOptions, progress: (ExportProgress) -> Unit): String = withContext(Dispatchers.IO) {
        photos.prune()
        val total = photoCount(book, options).coerceAtLeast(1)
        var done = 0
        val maxPx = TripHtml.printPhotoMaxPx(options.photoSize)
        val ready = HashMap<String, PhotoPrep.Prepared>()
        TripHtml.build(
            book, options,
            photoSrc = { m ->
                ensureActive()
                done++
                progress(ExportProgress("Getting photos ready · $done of $total", (done.toFloat() / total).coerceAtMost(1f)))
                photos.prepared(m, maxPx)?.also { ready[m.id] = it }?.src
            },
            photoAspect = { m -> ready[m.id]?.aspect },
        )
    }

    /**
     * Opens the system print dialog straight from the HTML. Only for phones that can't write a PDF file ([pdf]
     * failed with a LinkageError); otherwise the preview's PDF is printed ([printPdf]).
     */
    suspend fun print(activity: Activity, book: TripBook, options: ExportOptions, progress: (ExportProgress) -> Unit) {
        val html = printHtml(book, options, progress)
        progress(ExportProgress("Opening the print dialog"))
        PdfMaker.print(activity, html, photos.dir, title(book, options), options.paper)
    }

    /** The system print dialog for a finished PDF: what was previewed is what prints. */
    fun printPdf(activity: Activity, pdf: File, title: String, paper: Paper) = PdfMaker.printFile(activity, pdf, title, paper)

    /**
     * A PDF file for the preview, and then for sharing, saving and printing. Each one gets its own folder in
     * `cache/exports/` so its name stays the trip's ("Cyclades, slowly.pdf") while an older one is still open or shared.
     */
    suspend fun pdf(context: Context, book: TripBook, options: ExportOptions, progress: (ExportProgress) -> Unit): File {
        val html = printHtml(book, options, progress)
        progress(ExportProgress("Making the pages"))
        val out = withContext(Dispatchers.IO) {
            cleanUp()
            File(File(exportsDir, "pdf-${System.nanoTime()}").apply { mkdirs() }, ExportFiles.fileName(title(book, options), "pdf"))
        }
        try {
            PdfMaker.writePdf(context, html, photos.dir, out, options.paper)
        } catch (e: Throwable) {
            // Also when cancelled: plain file calls, no suspending.
            deletePdf(out)
            throw e
        }
        if (!out.isFile || out.length() == 0L) {
            deletePdf(out)
            throw ExportException("The PDF came out empty. Try again.")
        }
        return out
    }

    /** Deletes a preview PDF no one was given (and its folder). */
    suspend fun discard(file: File) = withContext(Dispatchers.IO) { deletePdf(file) }

    private fun deletePdf(file: File) {
        file.delete()
        file.parentFile?.takeIf { it.parentFile == exportsDir && it.name.startsWith("pdf-") }?.delete()
    }

    /** Copies a finished file to where the traveller chose to save it. */
    suspend fun copyTo(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        val out = app.contentResolver.openOutputStream(uri, "w") ?: throw ExportException("Couldn't write there. Pick another place.")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** The trip as plain text ([TripText]). */
    suspend fun text(book: TripBook, options: ExportOptions): String = withContext(Dispatchers.Default) { TripText.build(book, options) }

    /** [text] as a file, for a trip too long to hand over as a message. */
    suspend fun textFile(book: TripBook, options: ExportOptions, text: String): File = withContext(Dispatchers.IO) {
        cleanUp()
        File(exportsDir, ExportFiles.fileName(title(book, options), "txt")).apply { writeText(text) }
    }

    /** A Word file ([TripDocx]) in `cache/exports/`, photos scaled down as for Google Docs. */
    suspend fun docx(book: TripBook, options: ExportOptions, progress: (ExportProgress) -> Unit): File = withContext(Dispatchers.IO) {
        cleanUp()
        val total = photoCount(book, options).coerceAtLeast(1)
        val maxPx = ExportFiles.docsMaxPx(options.photoSize, total)
        var done = 0
        val out = File(exportsDir, ExportFiles.fileName(title(book, options), "docx"))
        try {
            out.outputStream().buffered().use { stream ->
                TripDocx.write(book, options, stream, photo = { m ->
                    ensureActive()
                    done++
                    progress(ExportProgress("Getting photos ready · $done of $total", (done.toFloat() / total).coerceAtMost(1f)))
                    photos.scaled(m, maxPx, quality = 82)?.let { f -> photos.size(f)?.let { (w, h) -> TripDocx.Photo(f, w, h) } }
                })
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        out
    }

    /**
     * Uploads a plainer HTML version to Drive, converted to a Google Doc: text, headings and photos (as data URIs,
     * scaled down). Returns the new Doc.
     */
    suspend fun saveToDocs(book: TripBook, options: ExportOptions, progress: (ExportProgress) -> Unit): DriveFile {
        requireDrive()
        val title = title(book, options)
        val file = withContext(Dispatchers.IO) {
            val total = photoCount(book, options)
            val maxPx = ExportFiles.docsMaxPx(options.photoSize, total)
            var done = 0
            val ready = HashMap<String, PhotoPrep.Prepared>()
            val html = TripHtml.build(
                book, options,
                photoSrc = { m ->
                    ensureActive()
                    done++
                    progress(ExportProgress("Getting photos ready · $done of ${total.coerceAtLeast(1)}", (done.toFloat() / total.coerceAtLeast(1)).coerceAtMost(1f)))
                    photos.data(m, maxPx)?.also { ready[m.id] = it }?.src
                },
                target = ExportTarget.Docs,
                photoAspect = { m -> ready[m.id]?.aspect },
            )
            File(exportsDir, ExportFiles.fileName(title, "html")).apply { writeText(html) }
        }
        progress(ExportProgress("Sending to Google Docs"))
        try {
            return drive { graph.drive.upload(file, title, "text/html", graph.drive.folder(DriveApi.EXPORTS), convertTo = DriveApi.GOOGLE_DOC) }
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    /** Lets anyone with the link open [file] and returns the link. */
    suspend fun shareLink(file: DriveFile): String = drive { graph.drive.shareLink(file) }

    /** Opens [file] in Docs or Drive. */
    fun openUrl(file: DriveFile): String = graph.drive.openUrl(file)

    /** Puts [pdf] in Drive › BoringTravel › Exports. */
    suspend fun savePdfToDrive(pdf: File, title: String, progress: (ExportProgress) -> Unit): DriveFile {
        requireDrive()
        progress(ExportProgress("Uploading to Google Drive"))
        return drive { graph.drive.upload(pdf, ExportFiles.fileName(title, "pdf"), "application/pdf", graph.drive.folder(DriveApi.EXPORTS)) }
    }

    suspend fun requireDrive() {
        if (!graph.drive.isConnected()) throw DriveNotConnectedException()
    }

    suspend fun driveConnected(): Boolean = graph.drive.isConnected()

    /** Files handed to other apps only need to live until those have read them: a day's worth is kept. */
    private fun cleanUp() {
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
        exportsDir.listFiles()?.forEach { f ->
            when {
                f.isFile && f.lastModified() < cutoff -> f.delete()
                f.isDirectory && f.name.startsWith("pdf-") && f.lastModified() < cutoff -> f.deleteRecursively()
            }
        }
    }

    private suspend fun <T> drive(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveNotConnectedException) {
            throw e
        } catch (e: java.io.IOException) {
            throw ExportException("Couldn't reach Google Drive. Check the connection and try again.")
        } catch (e: Exception) {
            throw ExportException("Google Drive didn't take the file. Try again later.")
        }
    }

    private fun photoCount(book: TripBook, options: ExportOptions): Int {
        val ids = book.days(options).flatMap { d -> d.photos.map { it.id } }.toMutableSet()
        book.trip.coverMediaId?.let { ids += it }
        return ids.size
    }
}

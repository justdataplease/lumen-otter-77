package com.justdataplease.boringtravel.book

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.exifinterface.media.ExifInterface
import com.justdataplease.boringtravel.AppGraph
import com.justdataplease.boringtravel.data.db.BookDraft
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.data.repo.now
import com.justdataplease.boringtravel.export.ExportException
import com.justdataplease.boringtravel.export.ExportFiles
import com.justdataplease.boringtravel.export.ExportProgress
import com.justdataplease.boringtravel.export.PdfMaker
import com.justdataplease.boringtravel.export.TripBook
import com.justdataplease.boringtravel.model.PrintTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Books on the phone: drafts in the `books` table, photos prepared for the page (preview size for the editor, about
 * 300 dpi where placed for the PDF, location and camera data dropped), and the print-ready PDF.
 */
class BookExporter(private val app: Context, private val graph: AppGraph) {
    val measure: TextMeasure by lazy { PaintTextMeasure(app) }
    private val photos get() = graph.export.photos
    private val dao get() = graph.db.books()
    private val exportsDir: File get() = File(app.cacheDir, "exports").apply { mkdirs() }

    /** The trip, with photo sizes filled in where the import didn't record them (needed for crops and fitting). */
    suspend fun loadTrip(tripId: String): TripBook = withContext(Dispatchers.IO) {
        val book = graph.export.load(tripId)
        book.copy(media = book.media.map { m ->
            if (m.width > 0 && m.height > 0) m else {
                val f = File(app.filesDir, m.localPath)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                runCatching { BitmapFactory.decodeFile(f.path, o) }
                val turned = runCatching { ExifInterface(f).rotationDegrees.let { it == 90 || it == 270 } }.getOrDefault(false)
                if (o.outWidth > 0 && o.outHeight > 0) m.copy(width = if (turned) o.outHeight else o.outWidth, height = if (turned) o.outWidth else o.outHeight) else m
            }
        })
    }

    suspend fun latest(tripId: String): BookDraft? = withContext(Dispatchers.IO) { dao.latestForTrip(tripId) }

    suspend fun save(draft: BookDraft) = withContext(Dispatchers.IO) { dao.upsert(draft) }

    /** A new draft row for [spec] and [doc]. */
    fun newDraft(tripId: String, spec: BookSpec, doc: BookDoc): BookDraft {
        val t = now()
        return BookDraft(newId(), tripId, spec.format.id, spec.theme.id, spec.fonts.id, spec.bleed, BookJson.encode(doc), t, t)
    }

    /** The spec stored with a draft (paper lives in its JSON). */
    fun spec(d: BookDraft, doc: BookDoc): BookSpec =
        BookSpec(BookFormat.of(d.format), PrintTheme.of(d.theme), BookFonts.of(d.fontSet), doc.paper, d.bleed)

    /** A copy of the photo for the editor's preview (screen size); null when it can't be read. */
    suspend fun previewUrl(tripBook: TripBook, mediaId: String): String? = withContext(Dispatchers.IO) {
        val m = tripBook.media(mediaId) ?: return@withContext null
        photos.scaled(m, PREVIEW_PX)?.let { Uri.fromFile(it).toString() }
    }

    val photoDir: File get() = photos.dir

    /** The print-ready PDF in `cache/exports/`. */
    suspend fun pdf(layout: BookLayout, doc: BookDoc, title: String, progress: (ExportProgress) -> Unit): File {
        // Laying out every page (measuring its text word by word) grows with the book: never on the main thread.
        val needs = withContext(Dispatchers.Default) { BookHtml.photoNeeds(layout, doc) }
        val urls = withContext(Dispatchers.IO) {
            photos.prune()
            val out = HashMap<String, String>()
            var done = 0
            needs.forEach { (id, px) ->
                ensureActive()
                done++
                progress(ExportProgress("Getting photos ready · $done of ${needs.size}", done.toFloat() / needs.size.coerceAtLeast(1)))
                val m = layout.facts.media(id) ?: return@forEach
                photos.scaled(m, px, quality = 92)?.let { out[id] = Uri.fromFile(it).toString() }
            }
            out
        }
        progress(ExportProgress("Making the PDF"))
        val html = withContext(Dispatchers.Default) { BookHtml.document(layout, doc, { id, _ -> urls[id] }) }
        val out = File(exportsDir, ExportFiles.fileName("$title book", "pdf"))
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
            exportsDir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
            out.delete()
        }
        PdfMaker.writePdf(app, html, photos.dir, out, mediaSize(layout.pages.sheet))
        if (!out.isFile || out.length() == 0L) throw ExportException("The PDF came out empty. Try again.")
        return out
    }

    /** Copies the PDF to a place the traveller picked (Save to phone). */
    suspend fun copyTo(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        val out = app.contentResolver.openOutputStream(uri, "w") ?: throw ExportException("Couldn't write there. Pick another place.")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** The system print dialog for a finished PDF; the printer fits the pages to its paper. */
    fun print(activity: Activity, file: File, title: String, sheet: Sheet) {
        val manager = activity.getSystemService(PrintManager::class.java)
        val attrs = PrintAttributes.Builder().setMediaSize(mediaSize(sheet)).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
        manager.print(title, PdfFileAdapter(file, title), attrs)
    }

    companion object {
        const val PREVIEW_PX = 1400

        fun mediaSize(sheet: Sheet): PrintAttributes.MediaSize =
            PrintAttributes.MediaSize("bt_book_${sheet.widthMils}x${sheet.heightMils}", sheet.format.label, sheet.widthMils, sheet.heightMils)
    }

    /** Hands a finished PDF to the print system as it is. */
    private class PdfFileAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { callback.onLayoutCancelled(); return }
            callback.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }

        /** The print system calls this on the main thread; the copy (tens of MB with photos) runs on its own. */
        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
            kotlin.concurrent.thread(name = "bt-print-copy") {
                try {
                    FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
    }
}

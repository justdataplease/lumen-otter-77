package com.justdataplease.boringtravel.export

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PdfFileWriter
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Trip HTML → PDF through an offscreen WebView: JavaScript off, no network, and only the bundled fonts and the
 * prepared photos ([PhotoPrep.dir]) may load. Either the system print dialog (print or "Save as PDF") or a silent
 * PDF file for sharing.
 */
object PdfMaker {
    private const val LOAD_TIMEOUT_MS = 60_000L

    fun mediaSize(paper: Paper): PrintAttributes.MediaSize = when (paper) {
        Paper.A4 -> PrintAttributes.MediaSize.ISO_A4
        Paper.Letter -> PrintAttributes.MediaSize.NA_LETTER
    }

    private fun attributes(paper: Paper) = attributes(mediaSize(paper))

    private fun attributes(media: PrintAttributes.MediaSize) = PrintAttributes.Builder()
        .setMediaSize(media)
        .setResolution(PrintAttributes.Resolution("pdf", "PDF", 600, 600))
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS) // margins come from the CSS @page rule
        .build()

    /** Opens the system print dialog for [html]. The WebView stays alive until the dialog is done with it. */
    suspend fun print(activity: Activity, html: String, allowedDir: File, jobName: String, paper: Paper) {
        withContext(Dispatchers.Main) {
            val web = load(activity, html, allowedDir)
            val manager = activity.getSystemService(PrintManager::class.java)
            val adapter = KeepAlive(web.createPrintDocumentAdapter(jobName), web)
            try {
                manager.print(jobName, adapter, attributes(paper))
            } catch (e: RuntimeException) {
                web.destroy()
                throw e
            }
        }
    }

    /**
     * The system print dialog for a PDF already made (the one the share screen previewed), so what prints is what was
     * shown. The printer fits the pages to its paper.
     */
    fun printFile(activity: Activity, file: File, jobName: String, paper: Paper) {
        val attrs = PrintAttributes.Builder().setMediaSize(mediaSize(paper)).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
        activity.getSystemService(PrintManager::class.java).print(jobName, FileAdapter(file, jobName), attrs)
    }

    /** Writes [html] as a PDF to [out] without any dialog; returns the page count (-1 if unknown). */
    suspend fun writePdf(context: Context, html: String, allowedDir: File, out: File, paper: Paper): Int =
        writePdf(context, html, allowedDir, out, mediaSize(paper))

    /** Same, on a sheet of any size (books: the trim size, plus bleed and crop marks when asked). */
    suspend fun writePdf(context: Context, html: String, allowedDir: File, out: File, media: PrintAttributes.MediaSize): Int =
        withContext(Dispatchers.Main) {
            val web = load(context, html, allowedDir)
            try {
                out.parentFile?.mkdirs()
                PdfFileWriter.write(web.createPrintDocumentAdapter(out.nameWithoutExtension), attributes(media), out)
            } finally {
                web.destroy()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(context: Context, html: String, allowedDir: File): WebView {
        val web = WebView(context)
        web.settings.apply {
            javaScriptEnabled = false
            // File access only for the prepared photos; shouldInterceptRequest below refuses every other path.
            allowFileAccess = true
            allowContentAccess = false
            blockNetworkLoads = true
            loadsImagesAutomatically = true
        }
        val allowed = allowedDir.absolutePath
        try {
            withTimeout(LOAD_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            if (request.isForMainFrame || ExportFiles.isAllowedUrl(url, allowed)) return null
                            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    web.loadDataWithBaseURL("file://$allowed/", html, "text/html", "utf-8", null)
                }
            }
            // Fonts and decoded photos settle a moment after the load event.
            delay(300)
        } catch (e: Throwable) {
            web.destroy()
            throw e
        }
        return web
    }

    /** Hands a finished PDF file to the print system as it is. */
    private class FileAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { callback.onLayoutCancelled(); return }
            callback.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old == null)
        }

        /** The print system calls this on the main thread; the copy (tens of MB with photos) runs on its own. */
        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
            kotlin.concurrent.thread(name = "bt-print-copy") {
                try {
                    FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                    if (cancel?.isCanceled == true) callback.onWriteCancelled() else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
    }

    /** Passes everything to the WebView's adapter and frees the WebView once the print job lets go of it. */
    private class KeepAlive(private val delegate: PrintDocumentAdapter, private var web: WebView?) : PrintDocumentAdapter() {
        override fun onStart() = delegate.onStart()

        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) =
            delegate.onLayout(old, new, cancel, callback, extras)

        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) =
            delegate.onWrite(pages, destination, cancel, callback)

        override fun onFinish() {
            delegate.onFinish()
            web?.destroy()
            web = null
        }
    }
}

package android.print

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives a [PrintDocumentAdapter] (a WebView's) straight into a PDF file, without the print dialog, so the PDF can
 * be shared or uploaded. It lives in `android.print` because the SDK declares the result callbacks' constructors
 * package-private (they are public at runtime).
 *
 * Call on the main thread: WebView's adapter must be driven from the thread the WebView was created on.
 */
object PdfFileWriter {
    class PdfWriteException(message: String) : IOException(message)

    /** Lays out and writes every page to [out]; returns the page count, or -1 when the adapter doesn't say. */
    suspend fun write(adapter: PrintDocumentAdapter, attributes: PrintAttributes, out: File): Int =
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { runCatching { cancel.cancel() } }
            var pfd: ParcelFileDescriptor? = null

            fun finish(result: Result<Int>) {
                runCatching { pfd?.close() }
                runCatching { adapter.onFinish() }
                if (!cont.isActive) return
                result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }

            adapter.onStart()
            adapter.onLayout(
                null,
                attributes,
                cancel,
                object : PrintDocumentAdapter.LayoutResultCallback() {
                    override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) {
                        val fd = try {
                            ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                        } catch (e: IOException) {
                            finish(Result.failure(e))
                            return
                        }
                        pfd = fd
                        adapter.onWrite(
                            arrayOf(PageRange.ALL_PAGES),
                            fd,
                            cancel,
                            object : PrintDocumentAdapter.WriteResultCallback() {
                                override fun onWriteFinished(pages: Array<out PageRange>) {
                                    val count = info.pageCount.takeIf { it != PrintDocumentInfo.PAGE_COUNT_UNKNOWN } ?: -1
                                    finish(Result.success(count))
                                }

                                override fun onWriteFailed(error: CharSequence?) {
                                    finish(Result.failure(PdfWriteException(error?.toString() ?: "write failed")))
                                }

                                override fun onWriteCancelled() {
                                    finish(Result.failure(PdfWriteException("write cancelled")))
                                }
                            },
                        )
                    }

                    override fun onLayoutFailed(error: CharSequence?) {
                        finish(Result.failure(PdfWriteException(error?.toString() ?: "layout failed")))
                    }

                    override fun onLayoutCancelled() {
                        finish(Result.failure(PdfWriteException("layout cancelled")))
                    }
                },
                Bundle().apply { putBoolean(PrintDocumentAdapter.EXTRA_PRINT_PREVIEW, false) },
            )
        }
}

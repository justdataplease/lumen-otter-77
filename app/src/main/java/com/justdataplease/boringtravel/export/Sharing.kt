package com.justdataplease.boringtravel.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Hands export files and text to other apps (any share target) and opens Drive links. */
object Sharing {
    private const val PDF = "application/pdf"

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun send(context: Context, file: File, title: String, mime: String): Intent {
        val uri = uri(context, file)
        return Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, title)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri(title, uri) }
    }

    /** The system share sheet for a file: a PDF unless [mime] says otherwise (a Word file, a text file). */
    fun share(context: Context, file: File, title: String, mime: String = PDF) {
        val chooser = Intent.createChooser(send(context, file, title, mime), title)
        context.startActivity(chooser.addNewTaskIfNeeded(context))
    }

    /** The system share sheet for [text] itself (a message, a note, an email), [title] as its subject. */
    fun shareText(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
        context.startActivity(Intent.createChooser(send, title).addNewTaskIfNeeded(context))
    }

    /**
     * Longest text handed over as words: Android passes a share through a buffer of about 1 MB, shared with
     * everything else in flight; longer goes as a text file.
     */
    const val MAX_TEXT_CHARS = 100_000

    /** The system share sheet for a link (a Drive file anyone with it can open), with [title] as its subject. */
    fun shareLink(context: Context, url: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
        context.startActivity(Intent.createChooser(send, title).addNewTaskIfNeeded(context))
    }

    /**
     * Keeps a copy of the video [file] in the phone's gallery, in Movies › BoringTravel (no permission needed for the
     * app's own files). Returns where it went.
     */
    fun saveVideo(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BoringTravel")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw java.io.IOException("The gallery didn't take the video.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw java.io.IOException("Couldn't write to the gallery.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Opens a Drive or Docs link in the Docs/Drive app if installed, else the browser. False when nothing can. */
    fun openUrl(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addNewTaskIfNeeded(context))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun Intent.addNewTaskIfNeeded(context: Context): Intent =
        if (context is android.app.Activity) this else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

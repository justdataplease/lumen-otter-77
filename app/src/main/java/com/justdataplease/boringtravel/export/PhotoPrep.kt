package com.justdataplease.boringtravel.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.repo.JournalVideos
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Photos sized for an export. Each is decoded once (with its EXIF rotation applied), scaled down and saved again as
 * a plain JPEG, which also drops the camera's location and other metadata from shared PDFs, Docs and Word files. A
 * journal video gives its first frame ([JournalVideos.frame]): printed pages, Docs and books show it as a picture.
 *
 * Print copies are cached in `cache/exports/img/` by id, size and last change, so a second export reuses them.
 */
class PhotoPrep(private val context: Context) {
    val dir: File get() = File(context.cacheDir, "exports/img").apply { mkdirs() }

    /** A photo ready for a document: its URL or data URI, and its size as it prints (EXIF turn applied). */
    data class Prepared(val src: String, val width: Int, val height: Int) {
        val aspect: Double get() = width.toDouble() / height
    }

    private fun source(m: Media): File = File(context.filesDir, m.localPath)

    /** A JPEG copy of [m] no longer than [maxPx] on its longest side; null when the photo can't be read. */
    fun scaled(m: Media, maxPx: Int, quality: Int = 86): File? {
        val out = File(dir, "${m.id.filter { it.isLetterOrDigit() || it == '-' }}-$maxPx-${m.updatedAt}.jpg")
        if (out.isFile && out.length() > 0) return out.also { it.setLastModified(System.currentTimeMillis()) }
        val bytes = encode(m, maxPx, quality)?.bytes ?: return null
        // Its own temporary name: the preview and a Word export may prepare the same photo at the same time.
        return runCatching {
            val tmp = File.createTempFile(out.name, ".tmp", dir)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            out
        }.getOrNull()
    }

    /** Width and height of an image file without decoding it; null when it can't be read. */
    fun size(file: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /** [scaled] as a `file://` URL with its size; null when the photo can't be read. */
    fun prepared(m: Media, maxPx: Int, quality: Int = 86): Prepared? {
        val file = scaled(m, maxPx, quality) ?: return null
        val (w, h) = size(file) ?: return null
        return Prepared(android.net.Uri.fromFile(file).toString(), w, h)
    }

    /** `data:image/jpeg;base64,…` for Google Docs, with its size; null when the photo can't be read. */
    fun data(m: Media, maxPx: Int, quality: Int = 80): Prepared? =
        encode(m, maxPx, quality)?.let { Prepared("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(it.bytes), it.width, it.height) }

    private class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    private fun encode(m: Media, maxPx: Int, quality: Int): Encoded? {
        val file = source(m)
        if (!file.isFile) return null
        val bitmap = if (m.isVideo) JournalVideos.frame(file, maxPx) ?: return null else runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val (w, h) = ExportFiles.fit(info.size.width, info.size.height, maxPx)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        }.getOrNull() ?: return null
        // JPEG has no transparency: put see-through images (PNG, WebP) on white instead of black.
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                val canvas = android.graphics.Canvas(it)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                bitmap.recycle()
            }
        } else bitmap
        return try {
            ByteArrayOutputStream(256 * 1024).use { buf ->
                if (!opaque.compress(Bitmap.CompressFormat.JPEG, quality, buf)) null else Encoded(buf.toByteArray(), opaque.width, opaque.height)
            }
        } finally {
            opaque.recycle()
        }
    }

    /** Removes cached copies not used for a week. */
    fun prune(maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}

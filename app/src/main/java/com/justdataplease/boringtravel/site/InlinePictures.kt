package com.justdataplease.boringtravel.site

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Pictures written inside a page as `data:image/…;base64,…` (Google Docs' HTML export carries every picture this way,
 * at its original size): read as the page arrives, each picture goes straight to a file in [dir] and its address in
 * the text becomes a short placeholder ([ADDRESS_PREFIX] + its SHA-256), so a document with a hundred photos stays a
 * small text in memory. The placeholder is the same for the same picture, which keeps note and photo ids stable
 * when the document is copied again. Pure (files only) and unit tested.
 */
class InlinePictures(private val dir: File) {
    /** Placeholder address → the picture's file, in the order they came. */
    val files = LinkedHashMap<String, File>()

    /** True when [read] stopped at its byte limit: the text holds what came before. */
    var truncated = false
        private set

    /**
     * The text of [input] with its inline pictures saved and replaced. Reads at most [maxBytes] in all and keeps at
     * most [maxTextBytes] of text; a picture over [maxPictureBytes] is left out (its address becomes empty).
     */
    fun read(input: InputStream, maxBytes: Long, maxTextBytes: Long, maxPictureBytes: Long, checkActive: () -> Unit = {}): String {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't make the pictures folder")
        val source = Counted(input.buffered(64 * 1024), maxBytes)
        val text = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            if (source.count and 0xFFFF == 0L) checkActive()
            val b = source.read()
            if (b < 0) break
            if (b == MARKER[matched].toInt()) {
                matched++
                if (matched == MARKER.size) {
                    matched = 0
                    text.write(picture(source, maxPictureBytes, checkActive).toByteArray(Charsets.US_ASCII))
                }
                continue
            }
            if (matched > 0) {
                text.write(MARKER, 0, matched)
                matched = if (b == MARKER[0].toInt()) 1 else 0
                if (matched == 1) continue
            }
            if (text.size() < maxTextBytes) text.write(b) else truncated = true
        }
        if (matched > 0) text.write(MARKER, 0, matched)
        if (source.cut) truncated = true
        return text.toString(Charsets.UTF_8.name())
    }

    /**
     * After "data:image/": the type up to ";base64,", then the base64 up to the first other character (the closing
     * quote, which stays in the text). Returns the placeholder address, or "" when the picture was left out.
     */
    private fun picture(source: Counted, maxBytes: Long, checkActive: () -> Unit): String {
        val type = StringBuilder()
        while (true) {
            val b = source.read()
            if (b < 0) return ""
            if (b == ';'.code) break
            if (type.length > 20 || !(b.toChar().isLetterOrDigit() || b == '+'.code || b == '-'.code || b == '.'.code)) {
                // Not a base64 picture after all: keep what was read as text.
                return "data:image/$type" + b.toChar()
            }
            type.append(b.toChar())
        }
        for (c in BASE64_TAG) {
            val b = source.read()
            if (b != c.code) return "data:image/$type;" + (if (b >= 0) b.toChar().toString() else "")
        }
        val partial = File.createTempFile("inline-", ".part", dir)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var tooLarge = false
        try {
            partial.outputStream().buffered().use { out ->
                val chunk = StringBuilder(CHUNK)
                var last: Int
                while (true) {
                    last = source.peek()
                    if (last < 0 || !isBase64(last)) break
                    source.read()
                    if (last == '\n'.code || last == '\r'.code || last == ' '.code) continue
                    chunk.append(last.toChar())
                    if (chunk.length == CHUNK) {
                        if (source.count and 0xFFFF == 0L) checkActive()
                        written += decode(chunk, out, digest, tooLarge)
                        chunk.setLength(0)
                        if (written > maxBytes) tooLarge = true
                    }
                }
                if (chunk.isNotEmpty()) written += decode(chunk, out, digest, tooLarge)
                if (written > maxBytes) tooLarge = true
            }
            if (tooLarge || written == 0L) return ""
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val ext = EXTENSIONS[type.toString().lowercase()] ?: "img"
            val name = "$hash.$ext"
            val file = File(dir, name)
            if (!file.exists() && !partial.renameTo(file)) throw IOException("Couldn't keep an inline picture")
            val address = ADDRESS_PREFIX + name
            files[address] = file
            return address
        } finally {
            partial.delete()
        }
    }

    /** Decodes a whole chunk (a multiple of 4 characters, or the last one) and writes it unless the picture is too large. */
    private fun decode(chunk: StringBuilder, out: OutputStream, digest: MessageDigest, skip: Boolean): Long {
        val bytes = try {
            Base64.getDecoder().decode(padded(chunk))
        } catch (_: IllegalArgumentException) {
            return 0
        }
        if (!skip) {
            out.write(bytes)
            digest.update(bytes)
        }
        return bytes.size.toLong()
    }

    private fun padded(chunk: StringBuilder): String {
        val s = chunk.toString()
        val rest = s.length % 4
        return if (rest == 0) s else s + "=".repeat(4 - rest)
    }

    private fun isBase64(b: Int): Boolean =
        b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code || b in '0'.code..'9'.code || b == '+'.code || b == '/'.code ||
            b == '='.code || b == '\n'.code || b == '\r'.code

    /** A stream that counts what it read, stops at a limit and can look one byte ahead. */
    private class Counted(private val input: InputStream, private val max: Long) {
        var count = 0L
            private set
        var cut = false
            private set
        private var ahead = -2

        fun peek(): Int {
            if (ahead == -2) ahead = next()
            return ahead
        }

        fun read(): Int {
            if (ahead != -2) {
                val b = ahead
                ahead = -2
                return b
            }
            return next()
        }

        private fun next(): Int {
            if (count >= max) {
                cut = true
                return -1
            }
            val b = input.read()
            if (b >= 0) count++
            return b
        }
    }

    companion object {
        /** Placeholder addresses: `.invalid` never resolves, so nothing is ever asked for there. */
        const val ADDRESS_PREFIX = "https://inline.boringtravel.invalid/"

        fun isInline(url: String): Boolean = url.startsWith(ADDRESS_PREFIX)

        private val MARKER = "data:image/".toByteArray(Charsets.US_ASCII)
        private const val BASE64_TAG = "base64,"
        private const val CHUNK = 64 * 1024 // a multiple of 4
        private val EXTENSIONS = mapOf(
            "jpeg" to "jpg", "jpg" to "jpg", "png" to "png", "gif" to "gif", "webp" to "webp", "heic" to "heic", "heif" to "heic",
            "bmp" to "bmp", "avif" to "avif", "svg+xml" to "svg",
        )
    }
}

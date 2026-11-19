package com.justdataplease.boringtravel.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded plain text only. A failed import never returns a truncated itinerary. */
object ItineraryImport {
    const val MAX_CHARS = 30_000
    const val MAX_BYTES = MAX_CHARS * 4 + 4

    fun read(input: InputStream, checkActive: () -> Unit = {}): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_BYTES) throw ItineraryImportException(TOO_LARGE)
            out.write(buffer, 0, count)
        }
        checkActive()
        return decode(out.toByteArray())
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw ItineraryImportException(TOO_LARGE)
        val (charset, skip) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw ItineraryImportException("Save the document as UTF-8 plain text (.txt), then open it again.")
        }
        return validate(text)
    }

    fun validate(raw: String): String {
        if (raw.length > MAX_CHARS) throw ItineraryImportException(TOO_LARGE)
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isBlank()) throw ItineraryImportException("This document has no text to import.")
        if (text.startsWith("%PDF-") || text.startsWith("PK\u0003\u0004") || text.any { it.code < 32 && it != '\n' && it != '\t' }) {
            throw ItineraryImportException("Choose a plain text (.txt) file. Export PDFs and Word documents to text first.")
        }
        return text
    }

    /** Only a genuine Google Docs HTTPS link; never follows an arbitrary URL or accepts path fragments as ids. */
    fun googleDocId(link: String): String? = runCatching {
        val uri = URI(link.trim())
        if (uri.scheme != "https" || !uri.host.equals("docs.google.com", ignoreCase = true) || uri.userInfo != null || uri.port !in listOf(-1, 443)) null
        else Regex("^/document/(?:u/\\d+/)?d/([A-Za-z0-9_-]{5,200})(?:/.*)?$").matchEntire(uri.path)?.groupValues?.get(1)
    }.getOrNull()

    const val TOO_LARGE = "This itinerary is too long. Choose up to 30,000 characters, or import one part at a time."
}

class ItineraryImportException(message: String) : Exception(message)

/**
 * A shared document is reviewed locally before the traveller chooses to send it to AI. [pictures]: pictures shared
 * into the app (Plan from picture), read by the planner's model ([PictureReading]).
 */
data class SharedItinerary(val text: String? = null, val uri: String? = null, val pictures: List<String> = emptyList())

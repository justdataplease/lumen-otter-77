package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.compat.InputStream
import com.justdataplease.boringtravel.ios.SharedLinks

/*
 * The Android app's ai/ItineraryImport.kt, written again here because it reads streams and decodes text with java.io
 * and java.nio: the same limits, checks and messages, in plain Kotlin.
 */

/** Bounded plain text only. A failed import never returns a truncated itinerary. */
object ItineraryImport {
    const val MAX_CHARS = 30_000
    const val MAX_BYTES = MAX_CHARS * 4 + 4

    /** A document's bytes ([input] is read whole); [checkActive] throws when the reading was stopped. */
    fun read(input: InputStream, checkActive: () -> Unit = {}): String {
        checkActive()
        val bytes = input.readBytes()
        if (bytes.size > MAX_BYTES) throw ItineraryImportException(TOO_LARGE)
        checkActive()
        return decode(bytes)
    }

    /** UTF-8 (with or without its mark) or UTF-16 with its mark; anything that isn't valid text in it is refused. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw ItineraryImportException(TOO_LARGE)
        val text = try {
            when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    bytes.decodeToString(3, bytes.size, throwOnInvalidSequence = true)
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> utf16(bytes, 2, littleEndian = true)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> utf16(bytes, 2, littleEndian = false)
                else -> bytes.decodeToString(throwOnInvalidSequence = true)
            }
        } catch (_: CharacterCodingException) {
            throw ItineraryImportException("Save the document as UTF-8 plain text (.txt), then open it again.")
        }
        return validate(text)
    }

    /** UTF-16 from [from], each surrogate in its pair (Java's decoder reports anything else). */
    private fun utf16(bytes: ByteArray, from: Int, littleEndian: Boolean): String {
        if ((bytes.size - from) % 2 != 0) throw CharacterCodingException()
        val out = StringBuilder((bytes.size - from) / 2)
        var i = from
        while (i < bytes.size) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            out.append((if (littleEndian) (b shl 8) or a else (a shl 8) or b).toChar())
            i += 2
        }
        var j = 0
        while (j < out.length) {
            val c = out[j]
            when {
                c.isHighSurrogate() -> if (j + 1 < out.length && out[j + 1].isLowSurrogate()) j++ else throw CharacterCodingException()
                c.isLowSurrogate() -> throw CharacterCodingException()
            }
            j++
        }
        return out.toString()
    }

    fun validate(raw: String): String {
        if (raw.length > MAX_CHARS) throw ItineraryImportException(TOO_LARGE)
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isBlank()) throw ItineraryImportException("This document has no text to import.")
        if (text.startsWith("%PDF-") || text.startsWith("PK\u0003\u0004") || text.any { it.code < 32 && it != '\n' && it != '\t' }) {
            throw ItineraryImportException("Choose a plain text (.txt) file. Export PDFs and Word documents to text first.")
        }
        return text
    }

    /** Only a genuine Google Docs HTTPS link; never follows an arbitrary URL or accepts path fragments as ids. */
    fun googleDocId(link: String): String? = SharedLinks.googleDocId(link)

    const val TOO_LARGE = "This itinerary is too long. Choose up to 30,000 characters, or import one part at a time."
}

class ItineraryImportException(message: String) : Exception(message)

/**
 * A shared document is reviewed locally before the traveller chooses to send it to AI. [pictures]: pictures shared
 * into the app (Plan from picture), read by the planner's model ([PictureReading]).
 */
data class SharedItinerary(val text: String? = null, val uri: String? = null, val pictures: List<String> = emptyList())

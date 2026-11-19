package com.justdataplease.boringtravel.ai

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItineraryImportTest {
    @Test fun utf8KeepsUnicodeAndNormalizesLineEndings() {
        val text = "El Chaltén → Αθήνα\r\n1–3 May 2027"
        assertEquals("El Chaltén → Αθήνα\n1–3 May 2027", ItineraryImport.read(ByteArrayInputStream(text.toByteArray())))
        assertEquals("Mendoza", ItineraryImport.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Mendoza".toByteArray()))
    }

    @Test fun utf16BomIsSupportedWithoutCorruptingNames() {
        val text = "Valparaíso and 日本"
        assertEquals(text, ItineraryImport.decode(text.toByteArray(Charsets.UTF_16)))
        assertEquals(text, ItineraryImport.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun longFilesFailInsteadOfImportingAPartialSchedule() {
        assertTrue(runCatching { ItineraryImport.decode("a".repeat(ItineraryImport.MAX_CHARS + 1).toByteArray()) }.exceptionOrNull() is ItineraryImportException)
        assertTrue(runCatching { ItineraryImport.read(ByteArrayInputStream(ByteArray(ItineraryImport.MAX_BYTES + 1))) }.exceptionOrNull() is ItineraryImportException)
        assertEquals(ItineraryImport.MAX_CHARS, ItineraryImport.validate("a".repeat(ItineraryImport.MAX_CHARS)).length)
    }

    @Test fun binaryMalformedAndEmptyInputsAreRejected() {
        for (bytes in listOf(byteArrayOf(0xFF.toByte(), 0xA1.toByte()), "%PDF-1.7 text".toByteArray(), "PK\u0003\u0004 document".toByteArray(), "  \r\n".toByteArray())) {
            assertTrue(runCatching { ItineraryImport.decode(bytes) }.exceptionOrNull() is ItineraryImportException)
        }
    }

    @Test fun cancellationDoesNotBecomeAnEmptyImport() {
        val error = runCatching { ItineraryImport.read(ByteArrayInputStream("A trip".toByteArray())) { throw CancellationException("Stop") } }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    @Test fun onlyGenuineGoogleDocsLinksYieldAnId() {
        assertEquals("abc123_-", ItineraryImport.googleDocId("https://docs.google.com/document/d/abc123_-/edit?usp=sharing"))
        assertEquals("abc123", ItineraryImport.googleDocId("https://docs.google.com/document/u/0/d/abc123/edit"))
        for (link in listOf("http://docs.google.com/document/d/abc123/edit", "https://docs.google.com.evil.test/document/d/abc123/edit", "https://docs.google.com@evil.test/document/d/abc123/edit", "https://evil.test/document/d/abc123/edit", "https://docs.google.com/spreadsheets/d/abc123/edit", "file:///document/d/abc123", "https://docs.google.com:444/document/d/abc123/edit")) {
            assertNull(link, ItineraryImport.googleDocId(link))
        }
    }
}

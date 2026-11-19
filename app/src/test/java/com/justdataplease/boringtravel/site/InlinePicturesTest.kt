package com.justdataplease.boringtravel.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

class InlinePicturesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun picturesGoToFilesAndTheTextStaysSmall() {
        val big = Random(1).nextBytes(300_000) // several decoding chunks
        val small = byteArrayOf(1, 2, 3)
        val enc = Base64.getEncoder()
        val html = "<p>Ηράκλειο 😀</p><img src=\"data:image/jpeg;base64,${enc.encodeToString(big)}\"><img src='data:image/png;base64,${enc.encodeToString(small)}'>" +
            "<img src=\"data:image/png;base64,${enc.encodeToString(small)}\"><p>data:image is just words here</p>"
        val pictures = InlinePictures(tmp.newFolder())
        val text = pictures.read(html.byteInputStream(), Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        val bigAddress = InlinePictures.ADDRESS_PREFIX + sha(big) + ".jpg"
        val smallAddress = InlinePictures.ADDRESS_PREFIX + sha(small) + ".png"
        assertEquals(
            "<p>Ηράκλειο 😀</p><img src=\"$bigAddress\"><img src='$smallAddress'><img src=\"$smallAddress\"><p>data:image is just words here</p>",
            text,
        )
        assertEquals(listOf(bigAddress, smallAddress), pictures.files.keys.toList())
        assertTrue(big.contentEquals(pictures.files.getValue(bigAddress).readBytes()))
        assertFalse(pictures.truncated)
        assertTrue(InlinePictures.isInline(bigAddress))
    }

    @Test fun aPictureTooLargeIsLeftOutAndALimitStopsTheReading() {
        val enc = Base64.getEncoder()
        val html = "<img src=\"data:image/png;base64,${enc.encodeToString(ByteArray(100_000))}\"><p>after</p>"
        val pictures = InlinePictures(tmp.newFolder())
        val text = pictures.read(html.byteInputStream(), Long.MAX_VALUE, Long.MAX_VALUE, maxPictureBytes = 50_000)
        assertEquals("<img src=\"\"><p>after</p>", text)
        assertTrue(pictures.files.isEmpty())

        val cut = InlinePictures(tmp.newFolder())
        assertEquals("<p>abc", cut.read("<p>abcdef</p>".byteInputStream(), maxBytes = 6, maxTextBytes = Long.MAX_VALUE, maxPictureBytes = Long.MAX_VALUE))
        assertTrue(cut.truncated)
    }

    @Test fun aSvgOrBrokenDataAddressStaysOrGoesCleanly() {
        val pictures = InlinePictures(tmp.newFolder())
        val text = pictures.read("<img src=\"data:image/svg+xml;utf8,<svg/>\">".byteInputStream(), Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals("<img src=\"data:image/svg+xml;utf8,<svg/>\">", text)
        assertTrue(pictures.files.isEmpty())
    }
}

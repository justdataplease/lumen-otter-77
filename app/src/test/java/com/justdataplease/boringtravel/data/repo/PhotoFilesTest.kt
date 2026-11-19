package com.justdataplease.boringtravel.data.repo

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhotoFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun firstPhotoCreatesStorageAndCopiesCompleteBytes() {
        val destination = File(temporary.root, "fresh/media/photo.jpg")
        val bytes = "hello".toByteArray()
        val checksum = PhotoFiles.copy(ByteArrayInputStream(bytes), destination)
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", checksum)
        assertEquals(listOf("photo.jpg"), requireNotNull(destination.parentFile).list()!!.toList())
    }

    @Test fun providerFailureNeverLeavesATruncatedPhotoOrTemporaryFile() {
        val destination = File(temporary.root, "media/photo.jpg")
        val broken = object : InputStream() {
            private var reads = 0
            override fun read(): Int = throw IOException("Provider disconnected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw IOException("Provider disconnected")
                buffer[offset] = 42
                return 1
            }
        }
        val error = runCatching { PhotoFiles.copy(broken, destination) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(destination.exists())
        assertTrue(requireNotNull(destination.parentFile).list()!!.isEmpty())
    }

    @Test fun cancelledCopyCleansPartialBytesAndPreservesCancellation() {
        val destination = File(temporary.root, "media/photo.jpg")
        var checks = 0
        val error = runCatching {
            PhotoFiles.copy(ByteArrayInputStream(ByteArray(150_000)), destination) {
                if (++checks == 2) throw CancellationException("Cancelled")
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(destination.exists())
        assertTrue(requireNotNull(destination.parentFile).list()!!.isEmpty())
    }

    @Test fun emptyProviderResultDoesNotCreateAnAlbumPhoto() {
        val destination = File(temporary.root, "media/photo.jpg")
        assertTrue(runCatching { PhotoFiles.copy(ByteArrayInputStream(byteArrayOf()), destination) }.exceptionOrNull() is IOException)
        assertFalse(destination.exists())
        assertTrue(requireNotNull(destination.parentFile).list()!!.isEmpty())
    }
}

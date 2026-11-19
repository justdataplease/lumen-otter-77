package com.justdataplease.boringtravel.google

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** PublicDrive against a fake Drive inside OkHttp (an interceptor answers; nothing goes to the network). */
class PublicDriveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val seen = mutableListOf<Request>()

    private fun drive(apiKey: String = "key", answer: (Request) -> Pair<Int, ByteArray>): PublicDrive {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            synchronized(seen) { seen += req }
            val (code, body) = answer(req)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return PublicDrive(http, apiKey, "com.example.app", "AB:CD")
    }

    @Test fun theChecksumIsAskedForWithoutTheContent() = runTest {
        val d = drive { 200 to """{"md5Checksum":"0cc175b9c0f1b6a831c399e269772661"}""".toByteArray() }
        assertEquals("0cc175b9c0f1b6a831c399e269772661", d.checksum("file12345"))
        val url = seen.single().url
        assertEquals("/drive/v3/files/file12345", url.encodedPath)
        assertEquals("md5Checksum", url.queryParameter("fields"))
        assertNull(url.queryParameter("alt"))
        assertEquals("com.example.app", seen.single().header("X-Android-Package"))
    }

    @Test fun withoutAKeyOrAnAnswerThereIsNoChecksum() = runTest {
        assertNull(drive(apiKey = "") { throw AssertionError("asked Drive") }.checksum("file12345"))
        assertNull(drive { 200 to "{}".toByteArray() }.checksum("file12345"))
        assertNull(drive { 200 to "not json".toByteArray() }.checksum("file12345"))
    }

    @Test fun aFileTakenOffItsLinkIsNotShared() = runTest {
        try {
            drive { 404 to "{}".toByteArray() }.checksum("file12345"); fail("read a file that is gone")
        } catch (_: NotSharedException) {
        }
    }

    @Test fun aBrokenDownloadLeavesNoPartFile() = runTest {
        val target = tmp.root.resolve("media/shared-p.jpg")
        val part = tmp.root.resolve("media/shared-p.jpg.part")
        try {
            drive { 200 to ByteArray(2048) }.download("file12345", target, maxBytes = 1024); fail("took a file too big")
        } catch (_: IOException) {
        }
        assertFalse(part.exists())
        assertFalse(target.exists())
        drive { 200 to ByteArray(512) { 7 } }.download("file12345", target, maxBytes = 1024)
        assertEquals(512L, target.length())
        assertFalse(part.exists())
        assertTrue(target.readBytes().all { it == 7.toByte() })
    }
}

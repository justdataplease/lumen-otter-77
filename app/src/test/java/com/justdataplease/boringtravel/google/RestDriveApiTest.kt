package com.justdataplease.boringtravel.google

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** RestDriveApi against a fake Drive inside OkHttp (an interceptor answers; nothing goes to the network). */
class RestDriveApiTest {
    private class Call(val method: String, val url: HttpUrl, val headers: Headers, val body: ByteArray, val contentType: String?) {
        val text: String get() = String(body, Charsets.UTF_8)
        fun q(name: String): String? = url.queryParameter(name)
    }

    private class Answer(val code: Int, val body: String = "{}", val headers: Map<String, String> = emptyMap())

    /** Records every request and answers with [route]; a thrown IOException is a dropped connection. */
    private class FakeDrive(var route: (Call) -> Answer) : Interceptor {
        val calls = mutableListOf<Call>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val bytes = req.body?.let { Buffer().also { b -> it.writeTo(b) }.readByteArray() } ?: ByteArray(0)
            val call = Call(req.method, req.url, req.headers, bytes, req.body?.contentType()?.toString())
            synchronized(calls) { calls += call }
            val a = route(call)
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(a.code)
                .message("fake")
                .headers(Headers.Builder().apply { a.headers.forEach { (k, v) -> add(k, v) } }.build())
                .body(a.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private class FakeTokens : AccessTokens {
        var current = "t1"
        var refreshes = 0
        override suspend fun token() = current
        override suspend fun refreshed(stale: String): String {
            refreshes++
            current = "t2"
            return current
        }
    }

    private val tokens = FakeTokens()

    private fun api(
        fake: FakeDrive,
        connected: Boolean = true,
        multipartLimit: Long = RestDriveApi.MULTIPART_LIMIT,
        chunkSize: Long = RestDriveApi.CHUNK_SIZE,
    ) = RestDriveApi(
        http = OkHttpClient.Builder().addInterceptor(fake).build(),
        tokens = tokens,
        connected = { connected },
        multipartLimit = multipartLimit,
        chunkSize = chunkSize,
        retryWait = 1,
    )

    private fun tempFile(bytes: ByteArray): File = Files.createTempFile("bt", ".bin").toFile().apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    @Test fun folderFindsOrMakesEachLevelAndRemembersIt() = runBlocking<Unit> {
        val fake = FakeDrive { c ->
            when {
                c.method == "GET" && c.q("q")!!.startsWith("name = 'BoringTravel'") -> Answer(200, """{"files":[{"id":"bt","name":"BoringTravel"}]}""")
                c.method == "GET" -> Answer(200, """{"files":[]}""")
                c.method == "POST" -> Answer(200, """{"id":"bk"}""")
                else -> Answer(500)
            }
        }
        val drive = api(fake)
        assertEquals("bk", drive.folder(DriveApi.BACKUPS))

        assertEquals(3, fake.calls.size)
        val first = fake.calls[0]
        assertEquals("/drive/v3/files", first.url.encodedPath)
        assertEquals("name = 'BoringTravel' and mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false", first.q("q"))
        assertEquals("createdTime", first.q("orderBy"))
        assertEquals("drive", first.q("spaces"))
        assertEquals("Bearer t1", first.headers["Authorization"])
        assertTrue(fake.calls[1].q("q")!!.contains("'bt' in parents"))
        val create = fake.calls[2]
        assertEquals("POST", create.method)
        assertEquals("""{"name":"Backups","parents":["bt"],"mimeType":"application/vnd.google-apps.folder"}""", create.text)

        // Known this session: no more calls, and a sibling reuses the parent's id.
        assertEquals("bk", drive.folder(DriveApi.BACKUPS))
        assertEquals(3, fake.calls.size)
        drive.folder(DriveApi.PHOTOS)
        assertEquals(5, fake.calls.size)
        assertTrue(fake.calls[3].q("q")!!.contains("name = 'Photos'"))

        assertNull(api(FakeDrive { Answer(200, """{"files":[]}""") }).findFolder(DriveApi.EXPORTS, create = false))
    }

    @Test fun smallUploadIsOneMultipartRequestAndConverts() = runBlocking<Unit> {
        val fake = FakeDrive {
            Answer(
                200,
                """{"id":"doc1","name":"Naxos","mimeType":"application/vnd.google-apps.document",
                   "webViewLink":"https://docs.google.com/document/d/doc1/edit","modifiedTime":"2026-11-05T20:00:00.000Z","size":"1234"}""",
            )
        }
        val html = tempFile("<h1>Naxos</h1>".toByteArray())
        val file = api(fake).upload(html, "Naxos", "text/html", "exports", convertTo = DriveApi.GOOGLE_DOC)

        assertEquals(DriveFile("doc1", "Naxos", DriveApi.GOOGLE_DOC, "https://docs.google.com/document/d/doc1/edit", "2026-11-05T20:00:00.000Z", 1234), file)
        assertEquals("https://docs.google.com/document/d/doc1/edit", api(fake).openUrl(file))
        val call = fake.calls.single()
        assertEquals("POST", call.method)
        assertEquals("/upload/drive/v3/files", call.url.encodedPath)
        assertEquals("multipart", call.q("uploadType"))
        assertEquals(DriveJson.FILE_FIELDS, call.q("fields"))
        assertTrue(call.contentType!!.startsWith("multipart/related"))
        val body = call.text
        assertTrue(body.contains("""{"name":"Naxos","parents":["exports"],"mimeType":"application/vnd.google-apps.document"}"""))
        assertTrue(body.contains("Content-Type: text/html"))
        assertTrue(body.contains("<h1>Naxos</h1>"))
        assertTrue(body.indexOf("\"name\":\"Naxos\"") < body.indexOf("<h1>"))
    }

    @Test fun bigUploadGoesInResumablePieces() = runBlocking<Unit> {
        val content = ByteArray(40) { it.toByte() }
        val fake = FakeDrive { c ->
            when {
                c.method == "POST" -> Answer(200, "", mapOf("Location" to "https://upload.example/session1"))
                c.headers["Content-Range"] == "bytes 0-15/40" -> Answer(308, "", mapOf("Range" to "bytes=0-15"))
                c.headers["Content-Range"] == "bytes 16-31/40" -> Answer(308, "", mapOf("Range" to "bytes=0-31"))
                c.headers["Content-Range"] == "bytes 32-39/40" -> Answer(200, """{"id":"big","name":"big.zip","size":"40"}""")
                else -> Answer(400, """{"error":{"message":"unexpected ${c.headers["Content-Range"]}"}}""")
            }
        }
        val file = api(fake, multipartLimit = 10, chunkSize = 16)
            .uploadFile(tempFile(content), "big.zip", "application/zip", listOf("bk"), appProperties = mapOf("trips" to "2"))

        assertEquals("big", file.id)
        val start = fake.calls[0]
        assertEquals("resumable", start.q("uploadType"))
        assertEquals("application/zip", start.headers["X-Upload-Content-Type"])
        assertEquals("40", start.headers["X-Upload-Content-Length"])
        assertEquals("""{"name":"big.zip","parents":["bk"],"appProperties":{"trips":"2"}}""", start.text)
        val pieces = fake.calls.drop(1)
        assertEquals(3, pieces.size)
        assertTrue(pieces.all { it.method == "PUT" && it.url.toString() == "https://upload.example/session1" })
        assertArrayEquals(content, pieces.fold(ByteArray(0)) { acc, c -> acc + c.body })
    }

    @Test fun resumableUploadCarriesOnAfterADroppedPiece() = runBlocking<Unit> {
        val content = ByteArray(40) { (it * 3).toByte() }
        var dropped = false
        val fake = FakeDrive { c ->
            val range = c.headers["Content-Range"]
            when {
                c.method == "POST" -> Answer(200, "", mapOf("Location" to "https://upload.example/s"))
                range == "bytes 0-15/40" -> Answer(308, "", mapOf("Range" to "bytes=0-15"))
                range == "bytes 16-31/40" && !dropped -> {
                    dropped = true
                    throw IOException("connection reset")
                }
                // Drive got only part of the dropped piece.
                range == "bytes */40" -> Answer(308, "", mapOf("Range" to "bytes=0-23"))
                range == "bytes 24-39/40" -> Answer(201, """{"id":"done"}""")
                else -> Answer(400, """{"error":{"message":"unexpected $range"}}""")
            }
        }
        val file = api(fake, multipartLimit = 10, chunkSize = 16).uploadFile(tempFile(content), "b.zip", "application/zip", emptyList())

        assertEquals("done", file.id)
        assertEquals(
            listOf(null, "bytes 0-15/40", "bytes 16-31/40", "bytes */40", "bytes 24-39/40"),
            fake.calls.map { it.headers["Content-Range"] },
        )
        assertArrayEquals(content.copyOfRange(24, 40), fake.calls.last().body)
    }

    @Test fun anExpiredTokenIsRenewedOnce() = runBlocking<Unit> {
        val fake = FakeDrive { c ->
            if (c.headers["Authorization"] == "Bearer t1") Answer(401, """{"error":{"code":401}}""") else Answer(200, """{"files":[{"id":"x","name":"x"}]}""")
        }
        val files = api(fake).list("trashed = false")
        assertEquals(listOf("x"), files.map { it.id })
        assertEquals(1, tokens.refreshes)
        assertEquals(listOf("Bearer t1", "Bearer t2"), fake.calls.map { it.headers["Authorization"] })
    }

    @Test fun aLinkLetsAnyoneWithItViewTheFile() = runBlocking<Unit> {
        // Owner: "send full video or google drive link?": one reader permission for anyone, not listed in search.
        val fake = FakeDrive { Answer(200, """{"id":"anyoneWithLink"}""") }
        val file = DriveFile("abc123", "Lisbon-clip.mp4", "video/mp4", webViewLink = "https://drive.google.com/file/d/abc123/view")
        assertEquals("https://drive.google.com/file/d/abc123/view", api(fake).shareLink(file))
        val call = fake.calls.single()
        assertEquals("POST", call.method)
        assertEquals("/drive/v3/files/abc123/permissions", call.url.encodedPath)
        assertTrue(call.text, call.text.contains("\"role\":\"reader\"") && call.text.contains("\"type\":\"anyone\"") && call.text.contains("\"allowFileDiscovery\":false"))
        // Without a web link from the upload, the plain Drive link.
        assertEquals("https://drive.google.com/open?id=abc123", api(FakeDrive { Answer(200, "{}") }).shareLink(file.copy(webViewLink = null)))
        // Not connected: nothing is sent.
        val idle = FakeDrive { Answer(200) }
        try {
            api(idle, connected = false).shareLink(file)
            fail("expected DriveNotConnectedException")
        } catch (_: DriveNotConnectedException) {
        }
        assertTrue(idle.calls.isEmpty())
    }

    @Test fun nothingIsSentWhenDriveIsNotConnected() = runBlocking<Unit> {
        val fake = FakeDrive { Answer(200) }
        try {
            api(fake, connected = false).folder(DriveApi.ROOT)
            fail("expected DriveNotConnectedException")
        } catch (_: DriveNotConnectedException) {
        }
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun busyAnswersAreRetriedAndRefusalsAreNot() = runBlocking<Unit> {
        var n = 0
        val busy = FakeDrive { if (++n < 3) Answer(503, "Service Unavailable") else Answer(200, """{"files":[]}""") }
        assertTrue(api(busy).list("trashed = false").isEmpty())
        assertEquals(3, busy.calls.size)

        val full = FakeDrive { Answer(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}],"message":"quota"}}""") }
        try {
            api(full).list("trashed = false")
            fail("expected DriveException")
        } catch (e: DriveException) {
            assertEquals(403, e.code)
            assertFalse(e.busy)
            assertEquals("Your Google Drive is full. Free some space and try again.", e.message)
        }
        assertEquals(1, full.calls.size)
    }

    @Test fun listFollowsPages() = runBlocking<Unit> {
        val fake = FakeDrive { c ->
            if (c.q("pageToken") == null) Answer(200, """{"nextPageToken":"p2","files":[{"id":"1","name":"a"}]}""")
            else Answer(200, """{"files":[{"id":"2","name":"b"}]}""")
        }
        assertEquals(listOf("1", "2"), api(fake).list("trashed = false", orderBy = "createdTime desc").map { it.id })
        assertEquals("p2", fake.calls[1].q("pageToken"))
        assertEquals("createdTime desc", fake.calls[0].q("orderBy"))
        assertTrue(fake.calls[0].q("fields")!!.startsWith("nextPageToken,files("))
    }

    @Test fun appDataIsCreatedOnceThenReplaced() = runBlocking<Unit> {
        var exists = false
        val fake = FakeDrive { c ->
            when {
                c.method == "GET" && c.q("alt") == "media" -> Answer(200, """{"id":"bk1"}""")
                c.method == "GET" -> Answer(200, if (exists) """{"files":[{"id":"m1","name":"backup.json"}]}""" else """{"files":[]}""")
                c.method == "POST" -> {
                    exists = true
                    Answer(200, """{"id":"m1","name":"backup.json"}""")
                }
                c.method == "PATCH" -> Answer(200, """{"id":"m1","name":"backup.json"}""")
                else -> Answer(400)
            }
        }
        val drive = api(fake)
        assertNull(drive.readAppData("backup.json"))
        drive.writeAppData("backup.json", """{"id":"bk1"}""".toByteArray())
        val create = fake.calls.last()
        assertEquals("POST", create.method)
        assertTrue(create.text.contains("""{"name":"backup.json","parents":["appDataFolder"]}"""))
        assertEquals(DriveJson.APP_DATA, fake.calls[0].q("spaces"))

        drive.writeAppData("backup.json", """{"id":"bk2"}""".toByteArray())
        val patch = fake.calls.last()
        assertEquals("PATCH", patch.method)
        assertEquals("/upload/drive/v3/files/m1", patch.url.encodedPath)
        assertEquals("media", patch.q("uploadType"))
        assertEquals("""{"id":"bk2"}""", patch.text)

        assertEquals("""{"id":"bk1"}""", String(drive.readAppData("backup.json")!!))
    }

    @Test fun downloadWritesTheFileWithoutLeftovers() = runBlocking<Unit> {
        val fake = FakeDrive { c -> if (c.q("alt") == "media") Answer(200, "photo bytes") else Answer(404) }
        val dir = Files.createTempDirectory("bt").toFile()
        val target = File(dir, "media/p1.jpg")
        var seen = 0L
        api(fake).download("p1", target) { seen = it }
        assertEquals("photo bytes", target.readText())
        assertEquals(11L, seen)
        assertFalse(File(target.path + ".part").exists())
        assertEquals("/drive/v3/files/p1", fake.calls.single().url.encodedPath)
        dir.deleteRecursively()
    }

    @Test fun deleteTreatsGoneAsDoneAndMetadataOfGoneIsNull() = runBlocking<Unit> {
        val fake = FakeDrive { Answer(404, """{"error":{"code":404}}""") }
        val drive = api(fake)
        drive.delete("old")
        assertEquals("DELETE", fake.calls.single().method)
        assertNull(drive.fileOrNull("old"))
        val trashed = FakeDrive { Answer(200, """{"id":"t","name":"t","trashed":true}""") }
        assertNull(api(trashed).fileOrNull("t"))
    }

    @Test fun documentExportUsesOnlyTheAuthenticatedDriveTextEndpoint() = runBlocking<Unit> {
        val fake = FakeDrive { Answer(200, "Naxos, 17–26 June 2027") }
        assertEquals("Naxos, 17–26 June 2027", api(fake).exportText("abc123", 1000).toString(Charsets.UTF_8))
        val call = fake.calls.single()
        assertEquals("GET", call.method)
        assertEquals("/drive/v3/files/abc123/export", call.url.encodedPath)
        assertEquals("text/plain", call.q("mimeType"))
        assertEquals("Bearer t1", call.headers["Authorization"])
    }

    @Test fun documentExportRejectsSizeAndPermissionFailuresWithoutPartialText() = runBlocking<Unit> {
        val big = FakeDrive { Answer(200, "x".repeat(100)) }
        assertTrue(runCatching { api(big).exportText("abc123", 50) }.exceptionOrNull() is DriveExportTooLargeException)
        assertEquals(1, big.calls.size)
        val denied = FakeDrive { Answer(403, """{"error":{"message":"Access denied"}}""") }
        val error = runCatching { api(denied).exportText("abc123", 1000) }.exceptionOrNull()
        assertTrue(error is DriveException)
        assertEquals(403, (error as DriveException).code)
        val untouched = FakeDrive { Answer(200, "private") }
        assertTrue(runCatching { api(untouched, connected = false).exportText("abc123", 1000) }.exceptionOrNull() is DriveNotConnectedException)
        assertTrue(runCatching { api(untouched).exportText("abc123/../other", 1000) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(untouched.calls.isEmpty())
    }

    @Test fun aFolderDeletedMeanwhileIsFoundAgain() = runBlocking<Unit> {
        var round = 0
        val fake = FakeDrive { c ->
            when {
                c.method == "GET" -> Answer(200, """{"files":[{"id":"f$round","name":"x"}]}""")
                c.method == "POST" && c.text.contains("\"parents\":[\"f1\"]") -> Answer(404, """{"error":{"code":404,"message":"File not found: f1."}}""")
                c.method == "POST" -> Answer(200, """{"id":"up","name":"a.jpg"}""")
                else -> Answer(400)
            }
        }
        val drive = api(fake)
        round = 1
        val photo = tempFile(byteArrayOf(1, 2, 3))
        drive.folder(DriveApi.PHOTOS)
        round = 2
        val up = drive.intoFolder(DriveApi.PHOTOS) { folder -> drive.uploadFile(photo, "a.jpg", "image/jpeg", listOf(folder)) }
        assertEquals("up", up.id)
        assertTrue(fake.calls.last().text.contains("\"parents\":[\"f2\"]"))
    }
}

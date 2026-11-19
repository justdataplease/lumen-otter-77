package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.google.AccessTokens
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.DriveException
import com.justdataplease.boringtravel.google.DriveExportTooLargeException
import com.justdataplease.boringtravel.google.DriveFile
import com.justdataplease.boringtravel.google.DriveJson
import com.justdataplease.boringtravel.google.DriveNotConnectedException
import com.justdataplease.boringtravel.google.FolderMemory
import com.justdataplease.boringtravel.google.RestDriveApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drive's REST calls (google/RestDrive.kt) against the Android app's own cases: its RestDriveApiTest and BackupFolderTest
 * (an OkHttp interceptor there, a scripted Net here; nothing reaches the network), the same requests, bodies, headers
 * and answers: folders found or made and remembered, a multipart upload that converts, resumable pieces carrying on
 * after a dropped one, a stale token renewed once, links for anyone, nothing sent without Drive, busy retried and
 * refusals not, pages followed, the hidden marker made then replaced, downloads without leftovers, gone counted as
 * done, Docs exported only through Drive's own endpoint and refused past their limit, a folder deleted meanwhile found
 * again; and where backups go: a renamed and moved folder found by its id, one in the trash made again at the default
 * place, a new phone finding a moved folder through the newest backup.
 */
class RestDriveOnDesktopTest {
    /** A Net answering with [route] and keeping every call; a thrown NetException is a dropped connection. */
    private class ScriptedNet(var route: (FakeCall) -> NetAnswer) : Net {
        val calls = mutableListOf<FakeCall>()

        override suspend fun get(request: NetRequest): NetAnswer = send(NetCall("GET", request.url, request.headers))

        override suspend fun send(call: NetCall): NetAnswer {
            val c = FakeCall(call.method, call.url, call.headers, call.body ?: ByteArray(0), call.contentType)
            synchronized(calls) { calls += c }
            return route(c)
        }
    }

    private fun answer(code: Int, body: String = "{}", headers: Map<String, String> = emptyMap()) = NetAnswer(code, "https://www.googleapis.com", headers, body.encodeToByteArray())

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
    private val files = MemoryMediaFiles()

    private fun api(
        net: ScriptedNet,
        connected: Boolean = true,
        multipartLimit: Long = RestDriveApi.MULTIPART_LIMIT,
        chunkSize: Long = RestDriveApi.CHUNK_SIZE,
        memory: FolderMemory = FolderMemory.None,
    ) = RestDriveApi(net, tokens, connected = { connected }, files, multipartLimit = multipartLimit, chunkSize = chunkSize, retryWait = 1, memory = memory, wait = {})

    private fun file(bytes: ByteArray): String = "picked/test-${bytes.size}-${bytes.contentHashCode()}.bin".also { files.write(it, bytes) }

    @Test fun folderFindsOrMakesEachLevelAndRemembersIt() = runBlocking<Unit> {
        val net = ScriptedNet { c ->
            when {
                c.method == "GET" && c.q("q")!!.startsWith("name = 'BoringTravel'") -> answer(200, """{"files":[{"id":"bt","name":"BoringTravel"}]}""")
                c.method == "GET" -> answer(200, """{"files":[]}""")
                c.method == "POST" -> answer(200, """{"id":"bk"}""")
                else -> answer(500)
            }
        }
        val drive = api(net)
        assertEquals("bk", drive.folder(DriveApi.BACKUPS))

        assertEquals(3, net.calls.size)
        val first = net.calls[0]
        assertEquals("/drive/v3/files", first.path)
        assertEquals("name = 'BoringTravel' and mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false", first.q("q"))
        assertEquals("createdTime", first.q("orderBy"))
        assertEquals("drive", first.q("spaces"))
        assertEquals("Bearer t1", first.header("Authorization"))
        assertTrue(net.calls[1].q("q")!!.contains("'bt' in parents"))
        val create = net.calls[2]
        assertEquals("POST", create.method)
        assertEquals("""{"name":"Backups","parents":["bt"],"mimeType":"application/vnd.google-apps.folder"}""", create.text)

        // Known this session: no more calls, and a sibling reuses the parent's id.
        assertEquals("bk", drive.folder(DriveApi.BACKUPS))
        assertEquals(3, net.calls.size)
        drive.folder(DriveApi.PHOTOS)
        assertEquals(5, net.calls.size)
        assertTrue(net.calls[3].q("q")!!.contains("name = 'Photos'"))

        assertNull(api(ScriptedNet { answer(200, """{"files":[]}""") }).findFolder(DriveApi.EXPORTS, create = false))
    }

    @Test fun smallUploadIsOneMultipartRequestAndConverts() = runBlocking<Unit> {
        val net = ScriptedNet {
            answer(
                200,
                """{"id":"doc1","name":"Naxos","mimeType":"application/vnd.google-apps.document",
                   "webViewLink":"https://docs.google.com/document/d/doc1/edit","modifiedTime":"2026-11-05T20:00:00.000Z","size":"1234"}""",
            )
        }
        val html = file("<h1>Naxos</h1>".encodeToByteArray())
        val uploaded = api(net).upload(html, "Naxos", "text/html", "exports", convertTo = DriveApi.GOOGLE_DOC)

        assertEquals(DriveFile("doc1", "Naxos", DriveApi.GOOGLE_DOC, "https://docs.google.com/document/d/doc1/edit", "2026-11-05T20:00:00.000Z", 1234), uploaded)
        assertEquals("https://docs.google.com/document/d/doc1/edit", api(net).openUrl(uploaded))
        val call = net.calls.single()
        assertEquals("POST", call.method)
        assertEquals("/upload/drive/v3/files", call.path)
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
        val net = ScriptedNet { c ->
            when {
                c.method == "POST" -> answer(200, "", mapOf("Location" to "https://upload.example.com/session1"))
                c.header("Content-Range") == "bytes 0-15/40" -> answer(308, "", mapOf("Range" to "bytes=0-15"))
                c.header("Content-Range") == "bytes 16-31/40" -> answer(308, "", mapOf("Range" to "bytes=0-31"))
                c.header("Content-Range") == "bytes 32-39/40" -> answer(200, """{"id":"big","name":"big.zip","size":"40"}""")
                else -> answer(400, """{"error":{"message":"unexpected ${c.header("Content-Range")}"}}""")
            }
        }
        val uploaded = api(net, multipartLimit = 10, chunkSize = 16)
            .uploadFile(file(content), "big.zip", "application/zip", listOf("bk"), appProperties = mapOf("trips" to "2"))

        assertEquals("big", uploaded.id)
        val start = net.calls[0]
        assertEquals("resumable", start.q("uploadType"))
        assertEquals("application/zip", start.header("X-Upload-Content-Type"))
        assertEquals("40", start.header("X-Upload-Content-Length"))
        assertEquals("""{"name":"big.zip","parents":["bk"],"appProperties":{"trips":"2"}}""", start.text)
        val pieces = net.calls.drop(1)
        assertEquals(3, pieces.size)
        assertTrue(pieces.all { it.method == "PUT" && it.url == "https://upload.example.com/session1" })
        assertContentEquals(content, pieces.fold(ByteArray(0)) { acc, c -> acc + c.body })
    }

    @Test fun resumableUploadCarriesOnAfterADroppedPiece() = runBlocking<Unit> {
        val content = ByteArray(40) { (it * 3).toByte() }
        var dropped = false
        val net = ScriptedNet { c ->
            val range = c.header("Content-Range")
            when {
                c.method == "POST" -> answer(200, "", mapOf("Location" to "https://upload.example.com/s"))
                range == "bytes 0-15/40" -> answer(308, "", mapOf("Range" to "bytes=0-15"))
                range == "bytes 16-31/40" && !dropped -> {
                    dropped = true
                    throw NetException("connection reset")
                }
                // Drive got only part of the dropped piece.
                range == "bytes */40" -> answer(308, "", mapOf("Range" to "bytes=0-23"))
                range == "bytes 24-39/40" -> answer(201, """{"id":"done"}""")
                else -> answer(400, """{"error":{"message":"unexpected $range"}}""")
            }
        }
        val uploaded = api(net, multipartLimit = 10, chunkSize = 16).uploadFile(file(content), "b.zip", "application/zip", emptyList())

        assertEquals("done", uploaded.id)
        assertEquals(listOf(null, "bytes 0-15/40", "bytes 16-31/40", "bytes */40", "bytes 24-39/40"), net.calls.map { it.header("Content-Range") })
        assertContentEquals(content.copyOfRange(24, 40), net.calls.last().body)
    }

    @Test fun anExpiredTokenIsRenewedOnce() = runBlocking<Unit> {
        val net = ScriptedNet { c ->
            if (c.header("Authorization") == "Bearer t1") answer(401, """{"error":{"code":401}}""") else answer(200, """{"files":[{"id":"x","name":"x"}]}""")
        }
        val found = api(net).list("trashed = false")
        assertEquals(listOf("x"), found.map { it.id })
        assertEquals(1, tokens.refreshes)
        assertEquals(listOf("Bearer t1", "Bearer t2"), net.calls.map { it.header("Authorization") })
    }

    @Test fun aLinkLetsAnyoneWithItViewTheFile() = runBlocking<Unit> {
        // The Android owner: "send full video or google drive link?": one reader permission for anyone, not listed in search.
        val net = ScriptedNet { answer(200, """{"id":"anyoneWithLink"}""") }
        val f = DriveFile("abc123", "Lisbon-clip.mp4", "video/mp4", webViewLink = "https://drive.google.com/file/d/abc123/view")
        assertEquals("https://drive.google.com/file/d/abc123/view", api(net).shareLink(f))
        val call = net.calls.single()
        assertEquals("POST", call.method)
        assertEquals("/drive/v3/files/abc123/permissions", call.path)
        assertTrue(call.text.contains("\"role\":\"reader\"") && call.text.contains("\"type\":\"anyone\"") && call.text.contains("\"allowFileDiscovery\":false"), call.text)
        // Without a web link from the upload, the plain Drive link.
        assertEquals("https://drive.google.com/open?id=abc123", api(ScriptedNet { answer(200, "{}") }).shareLink(f.copy(webViewLink = null)))
        // Not connected: nothing is sent.
        val idle = ScriptedNet { answer(200) }
        try {
            api(idle, connected = false).shareLink(f)
            fail("expected DriveNotConnectedException")
        } catch (_: DriveNotConnectedException) {
        }
        assertTrue(idle.calls.isEmpty())
    }

    @Test fun nothingIsSentWhenDriveIsNotConnected() = runBlocking<Unit> {
        val net = ScriptedNet { answer(200) }
        try {
            api(net, connected = false).folder(DriveApi.ROOT)
            fail("expected DriveNotConnectedException")
        } catch (_: DriveNotConnectedException) {
        }
        assertTrue(net.calls.isEmpty())
    }

    @Test fun busyAnswersAreRetriedAndRefusalsAreNot() = runBlocking<Unit> {
        var n = 0
        val busy = ScriptedNet { if (++n < 3) answer(503, "Service Unavailable") else answer(200, """{"files":[]}""") }
        assertTrue(api(busy).list("trashed = false").isEmpty())
        assertEquals(3, busy.calls.size)

        val full = ScriptedNet { answer(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}],"message":"quota"}}""") }
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
        val net = ScriptedNet { c ->
            if (c.q("pageToken") == null) answer(200, """{"nextPageToken":"p2","files":[{"id":"1","name":"a"}]}""")
            else answer(200, """{"files":[{"id":"2","name":"b"}]}""")
        }
        assertEquals(listOf("1", "2"), api(net).list("trashed = false", orderBy = "createdTime desc").map { it.id })
        assertEquals("p2", net.calls[1].q("pageToken"))
        assertEquals("createdTime desc", net.calls[0].q("orderBy"))
        assertTrue(net.calls[0].q("fields")!!.startsWith("nextPageToken,files("))
    }

    @Test fun appDataIsCreatedOnceThenReplaced() = runBlocking<Unit> {
        var exists = false
        val net = ScriptedNet { c ->
            when {
                c.method == "GET" && c.q("alt") == "media" -> answer(200, """{"id":"bk1"}""")
                c.method == "GET" -> answer(200, if (exists) """{"files":[{"id":"m1","name":"backup.json"}]}""" else """{"files":[]}""")
                c.method == "POST" -> {
                    exists = true
                    answer(200, """{"id":"m1","name":"backup.json"}""")
                }
                c.method == "PATCH" -> answer(200, """{"id":"m1","name":"backup.json"}""")
                else -> answer(400)
            }
        }
        val drive = api(net)
        assertNull(drive.readAppData("backup.json"))
        drive.writeAppData("backup.json", """{"id":"bk1"}""".encodeToByteArray())
        val create = net.calls.last()
        assertEquals("POST", create.method)
        assertTrue(create.text.contains("""{"name":"backup.json","parents":["appDataFolder"]}"""))
        assertEquals(DriveJson.APP_DATA, net.calls[0].q("spaces"))

        drive.writeAppData("backup.json", """{"id":"bk2"}""".encodeToByteArray())
        val patch = net.calls.last()
        assertEquals("PATCH", patch.method)
        assertEquals("/upload/drive/v3/files/m1", patch.path)
        assertEquals("media", patch.q("uploadType"))
        assertEquals("""{"id":"bk2"}""", patch.text)

        assertEquals("""{"id":"bk1"}""", drive.readAppData("backup.json")!!.decodeToString())
    }

    @Test fun downloadWritesTheFileWithoutLeftovers() = runBlocking<Unit> {
        val net = ScriptedNet { c -> if (c.q("alt") == "media") answer(200, "photo bytes") else answer(404) }
        var seen = 0L
        api(net).download("p1", "media/p1.jpg") { seen = it }
        assertEquals("photo bytes", files.read("media/p1.jpg")!!.decodeToString())
        assertEquals(11L, seen)
        assertNull(files.size("media/p1.jpg.part"))
        assertEquals("/drive/v3/files/p1", net.calls.single().path)
    }

    @Test fun deleteTreatsGoneAsDoneAndMetadataOfGoneIsNull() = runBlocking<Unit> {
        val net = ScriptedNet { answer(404, """{"error":{"code":404}}""") }
        val drive = api(net)
        drive.delete("old")
        assertEquals("DELETE", net.calls.single().method)
        assertNull(drive.fileOrNull("old"))
        val trashed = ScriptedNet { answer(200, """{"id":"t","name":"t","trashed":true}""") }
        assertNull(api(trashed).fileOrNull("t"))
    }

    @Test fun documentExportUsesOnlyTheAuthenticatedDriveTextEndpoint() = runBlocking<Unit> {
        val net = ScriptedNet { answer(200, "Naxos, 17–26 June 2027") }
        assertEquals("Naxos, 17–26 June 2027", api(net).exportText("abc123", 1000).decodeToString())
        val call = net.calls.single()
        assertEquals("GET", call.method)
        assertEquals("/drive/v3/files/abc123/export", call.path)
        assertEquals("text/plain", call.q("mimeType"))
        assertEquals("Bearer t1", call.header("Authorization"))
    }

    @Test fun documentExportRejectsSizeAndPermissionFailuresWithoutPartialText() = runBlocking<Unit> {
        val big = ScriptedNet { answer(200, "x".repeat(100)) }
        assertTrue(runCatching { api(big).exportText("abc123", 50) }.exceptionOrNull() is DriveExportTooLargeException)
        assertEquals(1, big.calls.size)
        val denied = ScriptedNet { answer(403, """{"error":{"message":"Access denied"}}""") }
        val error = runCatching { api(denied).exportText("abc123", 1000) }.exceptionOrNull()
        assertTrue(error is DriveException)
        assertEquals(403, (error as DriveException).code)
        val untouched = ScriptedNet { answer(200, "private") }
        assertTrue(runCatching { api(untouched, connected = false).exportText("abc123", 1000) }.exceptionOrNull() is DriveNotConnectedException)
        assertTrue(runCatching { api(untouched).exportText("abc123/../other", 1000) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(untouched.calls.isEmpty())
    }

    @Test fun aFolderDeletedMeanwhileIsFoundAgain() = runBlocking<Unit> {
        var round = 0
        val net = ScriptedNet { c ->
            when {
                c.method == "GET" -> answer(200, """{"files":[{"id":"f$round","name":"x"}]}""")
                c.method == "POST" && c.text.contains("\"parents\":[\"f1\"]") -> answer(404, """{"error":{"code":404,"message":"File not found: f1."}}""")
                c.method == "POST" -> answer(200, """{"id":"up","name":"a.jpg"}""")
                else -> answer(400)
            }
        }
        val drive = api(net)
        round = 1
        val photo = file(byteArrayOf(1, 2, 3))
        drive.folder(DriveApi.PHOTOS)
        round = 2
        val up = drive.intoFolder(DriveApi.PHOTOS) { folder -> drive.uploadFile(photo, "a.jpg", "image/jpeg", listOf(folder)) }
        assertEquals("up", up.id)
        assertTrue(net.calls.last().text.contains("\"parents\":[\"f2\"]"))
    }

    // --------------------------------------------------------- where backups go (Android's BackupFolderTest)

    private class MapMemory(vararg start: Pair<String, String>) : FolderMemory {
        val map = hashMapOf(*start)
        override fun get(path: String): String? = map[path]
        override fun put(path: String, id: String?) {
            if (id == null) map.remove(path) else map[path] = id
        }
    }

    /** A tiny Drive: [folders] by id (name, parent, trashed), [docs] by id (parent), the hidden marker's JSON. */
    private class TinyDrive(
        val folders: MutableMap<String, Triple<String, String, Boolean>> = mutableMapOf(),
        val docs: Map<String, String> = emptyMap(),
        val marker: String? = null,
    ) {
        val calls = mutableListOf<String>()
        private var made = 0

        fun route(c: FakeCall): NetAnswer {
            val path = c.path
            val q = c.q("q").orEmpty()
            calls += "${c.method} $path${if (q.isEmpty()) "" else " q=$q"}"
            fun ok(body: String, code: Int = 200) = NetAnswer(code, c.url, emptyMap(), body.encodeToByteArray())
            return when {
                c.method == "POST" && path == "/drive/v3/files" -> {
                    val name = Regex("\"name\":\"([^\"]+)\"").find(c.text)!!.groupValues[1]
                    val parent = Regex("\"parents\":\\[\"([^\"]+)\"").find(c.text)!!.groupValues[1]
                    val id = "newfolder${++made}"
                    folders[id] = Triple(name, parent, false)
                    ok("""{"id":"$id"}""")
                }
                c.method == "GET" && path == "/drive/v3/files" && c.q("spaces") == "appDataFolder" ->
                    ok(if (marker == null) """{"files":[]}""" else """{"files":[{"id":"marker1","name":"backup.json"}]}""")
                c.method == "GET" && path == "/drive/v3/files" -> {
                    val name = Regex("name = '([^']+)'").find(q)!!.groupValues[1]
                    val parent = Regex("'([^']+)' in parents").find(q)!!.groupValues[1]
                    val hit = folders.entries.firstOrNull { (_, f) -> f.first == name && f.second == parent && !f.third }
                    ok("""{"files":[${hit?.let { """{"id":"${it.key}","name":"$name"}""" } ?: ""}]}""")
                }
                c.method == "GET" && path == "/drive/v3/files/marker1" -> ok(marker.orEmpty())
                c.method == "GET" && path.startsWith("/drive/v3/files/") -> {
                    val id = path.removePrefix("/drive/v3/files/")
                    val folder = folders[id]
                    val doc = docs[id]
                    when {
                        folder != null -> ok("""{"id":"$id","name":"${folder.first}","mimeType":"${DriveApi.FOLDER}","parents":["${folder.second}"],"trashed":${folder.third}}""")
                        doc != null -> ok("""{"id":"$id","name":"x.zip","mimeType":"application/zip","parents":["$doc"],"trashed":false}""")
                        else -> ok("""{"error":{"code":404,"message":"File not found: $id."}}""", 404)
                    }
                }
                else -> ok("{}", 400)
            }
        }
    }

    private fun folderApi(tiny: TinyDrive, memory: FolderMemory) = api(ScriptedNet(tiny::route), memory = memory)

    @Test fun aRenamedAndMovedFolderIsFoundByItsIdNotMadeAgain() = runBlocking<Unit> {
        // The owner renamed BoringTravel to "Trips 2026" and moved it into a folder of their own.
        val tiny = TinyDrive(folders = mutableMapOf("btfolder1" to Triple("Trips 2026", "theirs", false), "bkfolder1" to Triple("Backups", "btfolder1", false)))
        val memory = MapMemory("BoringTravel" to "btfolder1", "BoringTravel/Backups" to "bkfolder1")
        val drive = folderApi(tiny, memory)
        assertEquals("bkfolder1", BackupFolder.find(drive))
        assertEquals(listOf("GET /drive/v3/files/bkfolder1"), tiny.calls)
        // Uploads land there too, and Photos is looked up under the moved folder.
        assertEquals("bkfolder1", drive.folder(DriveApi.BACKUPS))
        drive.folder(DriveApi.PHOTOS)
        assertTrue(tiny.folders.values.none { it.first == "BoringTravel" })
        assertEquals(Triple("Photos", "btfolder1", false), tiny.folders.getValue(memory.map.getValue("BoringTravel/Photos")))
        assertEquals("btfolder1", memory.map["BoringTravel"])
    }

    @Test fun aFolderInTheTrashIsForgottenAndTheNextBackupMakesOneAtTheDefaultPlace() = runBlocking<Unit> {
        val tiny = TinyDrive(folders = mutableMapOf("btfolder1" to Triple("BoringTravel", "root", true), "bkfolder1" to Triple("Backups", "btfolder1", true)))
        val memory = MapMemory("BoringTravel" to "btfolder1", "BoringTravel/Backups" to "bkfolder1")
        val drive = folderApi(tiny, memory)
        // Nothing to list, nothing made by just looking.
        assertNull(BackupFolder.find(drive))
        assertTrue(tiny.calls.none { it.startsWith("POST") })
        assertTrue(memory.map.isEmpty())
        // The next backup: My Drive › BoringTravel › Backups, new.
        val made = drive.folder(DriveApi.BACKUPS)
        val backups = tiny.folders.getValue(made)
        assertEquals("Backups", backups.first)
        assertEquals(Triple("BoringTravel", "root", false), tiny.folders.getValue(backups.second))
        assertEquals(made, memory.map["BoringTravel/Backups"])
        assertEquals(backups.second, memory.map["BoringTravel"])
    }

    @Test fun aNewPhoneFindsAMovedFolderThroughTheNewestBackup() = runBlocking<Unit> {
        // No ids on this phone, no "BoringTravel" in My Drive (renamed), but the marker names the newest backup.
        val tiny = TinyDrive(
            folders = mutableMapOf("btfolder1" to Triple("Old trips", "root", false), "bkfolder1" to Triple("Backups", "btfolder1", false)),
            docs = mapOf("zipfile01" to "bkfolder1"),
            marker = """{"format":1,"id":"zipfile01","name":"BoringTravel backup 2026-11-05 22.00.zip","createdAt":9}""",
        )
        val memory = MapMemory()
        val drive = folderApi(tiny, memory)
        assertEquals("bkfolder1", BackupFolder.find(drive))
        assertEquals("bkfolder1", memory.map["BoringTravel/Backups"])
        // From then on the backup goes there, not into a new folder.
        val calls = tiny.calls.size
        assertEquals("bkfolder1", drive.folder(DriveApi.BACKUPS))
        assertEquals(calls, tiny.calls.size)
        assertTrue(tiny.calls.none { it.startsWith("POST") })
    }

    @Test fun noFolderAndNoMarkerIsNullAndADefaultNameStillWorks() = runBlocking<Unit> {
        assertNull(BackupFolder.find(folderApi(TinyDrive(), MapMemory())))
        // A phone without kept ids: found by name as before, and kept from now on.
        val tiny = TinyDrive(folders = mutableMapOf("btfolder1" to Triple("BoringTravel", "root", false), "bkfolder1" to Triple("Backups", "btfolder1", false)))
        val memory = MapMemory()
        assertEquals("bkfolder1", BackupFolder.find(folderApi(tiny, memory)))
        assertEquals(mapOf("BoringTravel" to "btfolder1", "BoringTravel/Backups" to "bkfolder1"), memory.map)
        assertEquals("https://drive.google.com/drive/folders/bkfolder1", BackupFolder.url("bkfolder1"))
    }

    @Test fun aKeptIdThatIsNotAFolderOrNotAnIdIsDropped() = runBlocking<Unit> {
        val tiny = TinyDrive(folders = mutableMapOf("btfolder1" to Triple("BoringTravel", "root", false)), docs = mapOf("zipfile09" to "btfolder1"))
        val memory = MapMemory("BoringTravel" to "../../x", "BoringTravel/Backups" to "zipfile09")
        assertNull(folderApi(tiny, memory).findFolder(DriveApi.BACKUPS, create = false))
        assertTrue(memory.map.values.none { it == "zipfile09" || it == "../../x" })
        assertTrue(tiny.calls.none { it.contains("..") })
    }

    @Test fun theSettingsKeepTheFolderIdsAsAndroidsSmallFileDoes() {
        val file = MemorySettingsFile()
        val memory = AppSettings(file).driveFolders
        assertNull(memory.get("BoringTravel"))
        memory.put("BoringTravel", "btfolder1")
        memory.put("BoringTravel/Backups", "bkfolder1")
        // Kept between starts: the next run's settings read them back.
        assertEquals("bkfolder1", AppSettings(file).driveFolders.get("BoringTravel/Backups"))
        memory.put("BoringTravel", null)
        assertNull(memory.get("BoringTravel"))
        assertEquals("bkfolder1", memory.get("BoringTravel/Backups"))
    }
}

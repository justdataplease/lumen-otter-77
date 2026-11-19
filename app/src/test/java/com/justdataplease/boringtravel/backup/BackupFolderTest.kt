package com.justdataplease.boringtravel.backup

import com.justdataplease.boringtravel.google.AccessTokens
import com.justdataplease.boringtravel.google.DriveApi
import com.justdataplease.boringtravel.google.FolderMemory
import com.justdataplease.boringtravel.google.RestDriveApi
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Drive backups go, against a fake Drive (an OkHttp interceptor; nothing reaches the network): a folder the owner
 * renamed or moved is found by its id, one in the trash is replaced at the default place, and a new phone finds a moved
 * folder through the newest backup in it.
 */
class BackupFolderTest {
    private class MapMemory(vararg start: Pair<String, String>) : FolderMemory {
        val map = hashMapOf(*start)
        override fun get(path: String): String? = map[path]
        override fun put(path: String, id: String?) {
            if (id == null) map.remove(path) else map[path] = id
        }
    }

    /** A tiny Drive: [folders] by id (name, parent, trashed), [files] by id (parent), the hidden marker's JSON. */
    private class FakeDrive(
        val folders: MutableMap<String, Triple<String, String, Boolean>> = mutableMapOf(),
        val files: Map<String, String> = emptyMap(),
        val marker: String? = null,
    ) : Interceptor {
        val calls = mutableListOf<String>()
        private var made = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val path = req.url.encodedPath
            val q = req.url.queryParameter("q").orEmpty()
            calls += "${req.method} $path${if (q.isEmpty()) "" else " q=$q"}"
            val body: Pair<Int, String> = when {
                req.method == "POST" && path == "/drive/v3/files" -> {
                    val meta = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
                    val name = Regex("\"name\":\"([^\"]+)\"").find(meta)!!.groupValues[1]
                    val parent = Regex("\"parents\":\\[\"([^\"]+)\"").find(meta)!!.groupValues[1]
                    val id = "newfolder${++made}"
                    folders[id] = Triple(name, parent, false)
                    200 to """{"id":"$id"}"""
                }
                req.method == "GET" && path == "/drive/v3/files" && req.url.queryParameter("spaces") == "appDataFolder" ->
                    200 to if (marker == null) """{"files":[]}""" else """{"files":[{"id":"marker1","name":"backup.json"}]}"""
                req.method == "GET" && path == "/drive/v3/files" -> {
                    val name = Regex("name = '([^']+)'").find(q)!!.groupValues[1]
                    val parent = Regex("'([^']+)' in parents").find(q)!!.groupValues[1]
                    val hit = folders.entries.firstOrNull { (_, f) -> f.first == name && f.second == parent && !f.third }
                    200 to """{"files":[${hit?.let { """{"id":"${it.key}","name":"$name"}""" } ?: ""}]}"""
                }
                req.method == "GET" && path == "/drive/v3/files/marker1" -> 200 to marker.orEmpty()
                req.method == "GET" && path.startsWith("/drive/v3/files/") -> {
                    val id = path.removePrefix("/drive/v3/files/")
                    val folder = folders[id]
                    val file = files[id]
                    when {
                        folder != null -> 200 to """{"id":"$id","name":"${folder.first}","mimeType":"${DriveApi.FOLDER}","parents":["${folder.second}"],"trashed":${folder.third}}"""
                        file != null -> 200 to """{"id":"$id","name":"x.zip","mimeType":"application/zip","parents":["$file"],"trashed":false}"""
                        else -> 404 to """{"error":{"code":404,"message":"File not found: $id."}}"""
                    }
                }
                else -> 400 to "{}"
            }
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(body.first).message("fake")
                .headers(Headers.Builder().build()).body(body.second.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun api(fake: FakeDrive, memory: FolderMemory) = RestDriveApi(
        http = OkHttpClient.Builder().addInterceptor(fake).build(),
        tokens = object : AccessTokens {
            override suspend fun token() = "t"
            override suspend fun refreshed(stale: String) = "t"
        },
        connected = { true },
        retryWait = 1,
        memory = memory,
    )

    @Test fun aRenamedAndMovedFolderIsFoundByItsIdNotMadeAgain() = runBlocking<Unit> {
        // The owner renamed BoringTravel to "Trips 2026" and moved it into a folder of their own.
        val fake = FakeDrive(folders = mutableMapOf("btfolder1" to Triple("Trips 2026", "theirs", false), "bkfolder1" to Triple("Backups", "btfolder1", false)))
        val memory = MapMemory("BoringTravel" to "btfolder1", "BoringTravel/Backups" to "bkfolder1")
        val drive = api(fake, memory)
        assertEquals("bkfolder1", BackupFolder.find(drive))
        assertEquals(listOf("GET /drive/v3/files/bkfolder1"), fake.calls)
        // Uploads land there too, and Photos is looked up under the moved folder.
        assertEquals("bkfolder1", drive.folder(DriveApi.BACKUPS))
        drive.folder(DriveApi.PHOTOS)
        assertTrue(fake.folders.values.none { it.first == "BoringTravel" })
        assertEquals(Triple("Photos", "btfolder1", false), fake.folders.getValue(memory.map.getValue("BoringTravel/Photos")))
        assertEquals("btfolder1", memory.map["BoringTravel"])
    }

    @Test fun aFolderInTheTrashIsForgottenAndTheNextBackupMakesOneAtTheDefaultPlace() = runBlocking<Unit> {
        val fake = FakeDrive(folders = mutableMapOf("btfolder1" to Triple("BoringTravel", "root", true), "bkfolder1" to Triple("Backups", "btfolder1", true)))
        val memory = MapMemory("BoringTravel" to "btfolder1", "BoringTravel/Backups" to "bkfolder1")
        val drive = api(fake, memory)
        // Nothing to list, nothing made by just looking.
        assertNull(BackupFolder.find(drive))
        assertTrue(fake.calls.none { it.startsWith("POST") })
        assertTrue(memory.map.isEmpty())
        // The next backup: My Drive › BoringTravel › Backups, new.
        val made = drive.folder(DriveApi.BACKUPS)
        val backups = fake.folders.getValue(made)
        assertEquals("Backups", backups.first)
        assertEquals(Triple("BoringTravel", "root", false), fake.folders.getValue(backups.second))
        assertEquals(made, memory.map["BoringTravel/Backups"])
        assertEquals(backups.second, memory.map["BoringTravel"])
    }

    @Test fun aNewPhoneFindsAMovedFolderThroughTheNewestBackup() = runBlocking<Unit> {
        // No ids on this phone, no "BoringTravel" in My Drive (renamed), but the marker names the newest backup.
        val fake = FakeDrive(
            folders = mutableMapOf("btfolder1" to Triple("Old trips", "root", false), "bkfolder1" to Triple("Backups", "btfolder1", false)),
            files = mapOf("zipfile01" to "bkfolder1"),
            marker = """{"format":1,"id":"zipfile01","name":"BoringTravel backup 2026-11-05 22.00.zip","createdAt":9}""",
        )
        val memory = MapMemory()
        val drive = api(fake, memory)
        assertEquals("bkfolder1", BackupFolder.find(drive))
        assertEquals("bkfolder1", memory.map["BoringTravel/Backups"])
        // From then on the backup goes there, not into a new folder.
        val calls = fake.calls.size
        assertEquals("bkfolder1", drive.folder(DriveApi.BACKUPS))
        assertEquals(calls, fake.calls.size)
        assertTrue(fake.calls.none { it.startsWith("POST") })
    }

    @Test fun noFolderAndNoMarkerIsNullAndADefaultNameStillWorks() = runBlocking<Unit> {
        assertNull(BackupFolder.find(api(FakeDrive(), MapMemory())))
        // A phone without kept ids (1.5.0's): found by name as before, and kept from now on.
        val fake = FakeDrive(folders = mutableMapOf("btfolder1" to Triple("BoringTravel", "root", false), "bkfolder1" to Triple("Backups", "btfolder1", false)))
        val memory = MapMemory()
        assertEquals("bkfolder1", BackupFolder.find(api(fake, memory)))
        assertEquals(mapOf("BoringTravel" to "btfolder1", "BoringTravel/Backups" to "bkfolder1"), memory.map)
        assertEquals("https://drive.google.com/drive/folders/bkfolder1", BackupFolder.url("bkfolder1"))
    }

    @Test fun aKeptIdThatIsNotAFolderOrNotAnIdIsDropped() = runBlocking<Unit> {
        val fake = FakeDrive(folders = mutableMapOf("btfolder1" to Triple("BoringTravel", "root", false)), files = mapOf("zipfile09" to "btfolder1"))
        val memory = MapMemory("BoringTravel" to "../../x", "BoringTravel/Backups" to "zipfile09")
        assertNull(api(fake, memory).findFolder(DriveApi.BACKUPS, create = false))
        assertTrue(memory.map.values.none { it == "zipfile09" || it == "../../x" })
        assertTrue(fake.calls.none { it.contains("..") })
    }

}

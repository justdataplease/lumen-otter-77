package com.justdataplease.boringtravel.google

import com.justdataplease.boringtravel.compat.newBuilder
import com.justdataplease.boringtravel.data.repo.Stamps
import com.justdataplease.boringtravel.compat.toHttpUrl
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetAnswer
import com.justdataplease.boringtravel.ios.NetCall
import com.justdataplease.boringtravel.ios.call
import com.justdataplease.boringtravel.ios.fetch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/** Access tokens for Drive calls (google/GoogleAccount.kt). Both throw [DriveNotConnectedException] when the owner must act. */
interface AccessTokens {
    suspend fun token(): String

    /** Drops [stale], which Google rejected with 401, and returns a fresh token. */
    suspend fun refreshed(stale: String): String
}

/** Drive folder ids kept on the phone between sessions, by path ("BoringTravel/Backups"). Ids only. */
interface FolderMemory {
    fun get(path: String): String?

    /** Keeps [id] for [path]; null forgets it. */
    fun put(path: String, id: String?)

    /** Nothing kept: every session looks the folders up by name. */
    object None : FolderMemory {
        override fun get(path: String): String? = null
        override fun put(path: String, id: String?) = Unit
    }
}

/**
 * Google Drive REST API v3 over this app's [Net] (Ktor: URLSession on iPhone), as the Android app's
 * google/RestDriveApi.kt calls it over OkHttp, without Google's client library: the same requests, the same rules. Only
 * `drive.file` and `drive.appdata` are used, so the app sees just the files it created and its own hidden folder. Files
 * are the app's own ([files]: a path under them stands for Android's java.io.File).
 *
 * Uploads up to [multipartLimit] go in one multipart request; bigger files use a resumable session in [chunkSize] pieces
 * (Drive wants multiples of 256 KiB) that picks up where it stopped after a dropped connection.
 */
class RestDriveApi(
    private val net: Net,
    private val tokens: AccessTokens,
    private val connected: suspend () -> Boolean,
    private val files: MediaFiles,
    private val base: String = "https://www.googleapis.com",
    private val multipartLimit: Long = MULTIPART_LIMIT,
    private val chunkSize: Long = CHUNK_SIZE,
    private val retryWait: Long = 1_000,
    /** The app's folders found or made before, so one the owner renamed or moved is still found ([findFolder]). */
    private val memory: FolderMemory = FolderMemory.None,
    /** The pause between tries (the coroutines' delay; none in the tests). */
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : DriveApi {
    /** Folder ids found or made this session, by path ("BoringTravel/Backups"). */
    private val folderIds = MutableStateFlow<Map<String, String>>(emptyMap())
    private val folderLock = Mutex()

    override suspend fun isConnected(): Boolean = connected()

    override suspend fun folder(path: List<String>): String =
        findFolder(path, create = true) ?: throw DriveException(500, "Google Drive didn't make the folder.")

    override suspend fun upload(path: String, name: String, mime: String, folderId: String?, convertTo: String?): DriveFile =
        uploadFile(path, name, mime, listOfNotNull(folderId), convertTo).toDriveFile()

    override suspend fun shareLink(file: DriveFile): String {
        shareAnyone(file.id)
        return openUrl(file)
    }

    /** Lets anyone with the link view file or folder [id] (one this app made) without it showing in searches. */
    suspend fun shareAnyone(id: String) {
        requireId(id)
        if (!isConnected()) throw DriveNotConnectedException()
        val body = """{"role":"reader","type":"anyone","allowFileDiscovery":false}"""
        retrying(firstWait = retryWait, wait = wait) { json("POST", url("/drive/v3/files/$id/permissions") { add("fields", "id") }, body.encodeToByteArray(), JSON) }
    }

    /** Takes file or folder [id] off its link: only its owner can open it again. */
    suspend fun unshareAnyone(id: String) {
        requireId(id)
        retrying(firstWait = retryWait, wait = wait) {
            val r = send("DELETE", url("/drive/v3/files/$id/permissions/anyoneWithLink"))
            if (!r.ok && r.code != 404) throw DriveJson.error(r.code, r.text())
        }
    }

    /** A new file [name] in [parents] holding [content] (small files: one request). */
    suspend fun createBytes(name: String, content: ByteArray, mime: String, parents: List<String>, appProperties: Map<String, String> = emptyMap()): RemoteFile =
        retrying(firstWait = retryWait, wait = wait) { multipart(DriveJson.metadata(name, parents, null, appProperties), content, mime) }

    /** Replaces the content of file [id] (made by this app); the id, its link and its sharing stay. */
    suspend fun replaceBytes(id: String, content: ByteArray, mime: String): RemoteFile {
        requireId(id)
        val address = url("/upload/drive/v3/files/$id") {
            add("uploadType", "media")
            add("fields", DriveJson.FILE_FIELDS)
        }
        return parseFile(retrying(firstWait = retryWait, wait = wait) { json("PATCH", address, content, mime) })
    }

    /** A new folder [name] inside [parent] ("root" = My Drive), even when one of that name exists. */
    suspend fun newFolder(name: String, parent: String): String = retrying(firstWait = retryWait, wait = wait) { createFolder(name, parent) }

    override suspend fun exportText(id: String, maxBytes: Int): ByteArray {
        require(DRIVE_ID.matches(id)) { "Invalid Google Doc id" }
        require(maxBytes in 1..1_000_000)
        if (!isConnected()) throw DriveNotConnectedException()
        return retrying(firstWait = retryWait, wait = wait) { exported(id, "text/plain", maxBytes.toLong()) }
    }

    override suspend fun export(id: String, mimeType: String, maxBytes: Long): ByteArray {
        require(DRIVE_ID.matches(id)) { "Invalid Google Doc id" }
        if (!isConnected()) throw DriveNotConnectedException()
        return retrying(firstWait = retryWait, wait = wait) { exported(id, mimeType, maxBytes) }
    }

    /** A Doc's export, read as it comes and refused past [maxBytes] (no partial text). */
    private suspend fun exported(id: String, mimeType: String, maxBytes: Long): ByteArray {
        val task = currentCoroutineContext()
        val out = Bytes()
        val answer = sendDownload(url("/drive/v3/files/$id/export") { add("mimeType", mimeType) }) { b, n ->
            task.ensureActive()
            if (out.size + n > maxBytes) throw DriveExportTooLargeException()
            out.add(b, n)
        }
        if (!answer.ok) throw DriveJson.error(answer.code, answer.text())
        return out.bytes()
    }

    /**
     * The folder at [path] under My Drive. A folder found or made before is used again by its id ([memory]): the app
     * may open the folders it made (drive.file) wherever the owner moved them and whatever they renamed them to, as
     * long as they aren't in the trash. Below the deepest such folder, each level is the oldest folder of that name the
     * app can see (so two phones that both made one agree); with [create] a missing level is made there (My Drive for
     * the first), otherwise the answer is null.
     */
    suspend fun findFolder(path: List<String>, create: Boolean): String? = folderLock.withLock {
        val keys = path.indices.map { path.subList(0, it + 1).joinToString("/") }
        var start = 0
        var parent = ROOT
        for (depth in keys.indices.reversed()) {
            val id = folderIds.value[keys[depth]] ?: remembered(keys[depth]) ?: continue
            folderIds.update { it + (keys[depth] to id) }
            start = depth + 1
            parent = id
            break
        }
        for (depth in start until path.size) {
            val name = path[depth]
            val found = list(DriveJson.folderQuery(name, parent), orderBy = "createdTime", fields = "id,name", limit = 1).firstOrNull()?.id
            val id = found ?: if (create) createFolder(name, parent) else return@withLock null
            folderIds.update { it + (keys[depth] to id) }
            memory.put(keys[depth], id)
            parent = id
        }
        parent
    }

    /** A folder id kept from an earlier session, if Drive still has that folder for the app outside the trash. */
    private suspend fun remembered(key: String): String? {
        val id = memory.get(key) ?: return null
        val file = if (!DRIVE_ID.matches(id)) null else try {
            fileOrNull(id)
        } catch (e: DriveException) {
            if (e.code == 403 && !e.busy) null else throw e
        }
        if (file?.mimeType == DriveApi.FOLDER) return file.id
        memory.put(key, null)
        return null
    }

    /** Takes [id] as the folder at [path] from now on (found another way: where a backup in it is). */
    suspend fun rememberFolder(path: List<String>, id: String) = folderLock.withLock {
        val key = path.joinToString("/")
        folderIds.update { it + (key to id) }
        memory.put(key, id)
    }

    /** The folder file [id] is in, when the app can see both and neither is in the trash; null otherwise. */
    suspend fun parentOf(id: String): String? {
        if (!DRIVE_ID.matches(id)) return null
        val o = try {
            retrying(firstWait = retryWait, wait = wait) { json("GET", url("/drive/v3/files/$id") { add("fields", "parents,trashed") }) }
        } catch (e: DriveException) {
            if (e.code == 404) return null else throw e
        }
        if ((o["trashed"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val parent = ((o["parents"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return null
        if (!DRIVE_ID.matches(parent)) return null
        return fileOrNull(parent)?.takeIf { it.mimeType == DriveApi.FOLDER }?.id
    }

    /** Forgets the folder ids found this session (after a disconnect, or a folder the owner deleted); kept ids are checked again. */
    fun forgetFolders() {
        folderIds.value = emptyMap()
    }

    /** Runs [block] with the folder at [path]; if Drive says the folder is gone, finds or makes it again once. */
    suspend fun <T> intoFolder(path: List<String>, block: suspend (folderId: String) -> T): T = try {
        block(folder(path))
    } catch (e: DriveException) {
        if (e.code != 404) throw e
        forgetFolders()
        block(folder(path))
    }

    /** Uploads the file at [path] with optional conversion ([convertTo]) and private [appProperties] other phones can read in lists. */
    suspend fun uploadFile(
        path: String,
        name: String,
        mime: String,
        parents: List<String>,
        convertTo: String? = null,
        appProperties: Map<String, String> = emptyMap(),
    ): RemoteFile {
        val meta = DriveJson.metadata(name, parents, convertTo, appProperties)
        val size = files.size(path) ?: throw IOException("The file isn't on this phone any more.")
        return if (size <= multipartLimit) {
            multipart(meta, files.read(path) ?: throw IOException("The file isn't on this phone any more."), mime)
        } else {
            resumable(path, size, mime, meta)
        }
    }

    /** All files matching [q] (Drive query syntax) in [spaces], up to [limit]. */
    suspend fun list(
        q: String,
        spaces: String = "drive",
        orderBy: String? = null,
        fields: String = DriveJson.LIST_FIELDS,
        limit: Int = 1000,
    ): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val address = url("/drive/v3/files") {
                add("q", q)
                add("spaces", spaces)
                add("fields", "nextPageToken,files($fields)")
                add("pageSize", minOf(PAGE_SIZE, limit - out.size).toString())
                orderBy?.let { add("orderBy", it) }
                page?.let { add("pageToken", it) }
            }
            val (found, next) = DriveJson.parseList(retrying(firstWait = retryWait, wait = wait) { json("GET", address) })
            out += found
            page = next
        } while (page != null && out.size < limit)
        return out
    }

    /** A file's metadata, or null when it is gone or in the trash. */
    suspend fun fileOrNull(id: String): RemoteFile? = try {
        val o = retrying(firstWait = retryWait, wait = wait) { json("GET", url("/drive/v3/files/$id") { add("fields", DriveJson.LIST_FIELDS) }) }
        DriveJson.parseFile(o)?.takeUnless { it.trashed }
    } catch (e: DriveException) {
        if (e.code == 404) null else throw e
    }

    /**
     * Downloads a file's content to [target] under the app's files, through a .part file, so a broken download leaves
     * nothing half-written; [onBytes] hears how much has come.
     */
    suspend fun download(id: String, target: String, onBytes: (Long) -> Unit = {}) {
        val part = "$target.part"
        val task = currentCoroutineContext()
        try {
            retrying(firstWait = retryWait, wait = wait) {
                files.delete(part)
                if (!files.write(part, ByteArray(0))) throw IOException("Couldn't write the file on this phone.")
                var total = 0L
                val answer = sendDownload(url("/drive/v3/files/$id") { add("alt", "media") }) { b, n ->
                    task.ensureActive()
                    if (!files.append(part, b, n)) throw IOException("Couldn't write the file on this phone.")
                    total += n
                    onBytes(total)
                }
                if (!answer.ok) throw DriveJson.error(answer.code, answer.text())
            }
            if (!files.replace(part, target)) throw IOException("Couldn't write the file on this phone.")
        } finally {
            files.delete(part)
        }
    }

    /** A small file's content. */
    suspend fun bytes(id: String): ByteArray = retrying(firstWait = retryWait, wait = wait) {
        val r = send("GET", url("/drive/v3/files/$id") { add("alt", "media") })
        if (!r.ok) throw DriveJson.error(r.code, r.text())
        r.body
    }

    /** Deletes a file this app made (gone already counts as done). */
    suspend fun delete(id: String) = retrying(firstWait = retryWait, wait = wait) {
        val r = send("DELETE", url("/drive/v3/files/$id"))
        if (!r.ok && r.code != 404) throw DriveJson.error(r.code, r.text())
    }

    /** The content of [name] in the app's hidden folder, or null when there is none. */
    suspend fun readAppData(name: String): ByteArray? {
        val file = list(DriveJson.nameQuery(name), spaces = DriveJson.APP_DATA, orderBy = "modifiedTime desc", limit = 1).firstOrNull() ?: return null
        return bytes(file.id)
    }

    /** Writes [name] in the app's hidden folder, replacing its content when it is already there. */
    suspend fun writeAppData(name: String, content: ByteArray, mime: String = "application/json"): RemoteFile {
        val existing = list(DriveJson.nameQuery(name), spaces = DriveJson.APP_DATA, orderBy = "modifiedTime desc", limit = 1).firstOrNull()
        if (existing == null) return multipart(DriveJson.metadata(name, listOf(DriveJson.APP_DATA)), content, mime)
        val address = url("/upload/drive/v3/files/${existing.id}") {
            add("uploadType", "media")
            add("fields", DriveJson.FILE_FIELDS)
        }
        return parseFile(retrying(firstWait = retryWait, wait = wait) { json("PATCH", address, content, mime) })
    }

    /** The email of the Google account the token belongs to. */
    suspend fun accountEmail(): String? =
        DriveJson.parseAboutEmail(retrying(firstWait = retryWait, wait = wait) { json("GET", url("/drive/v3/about") { add("fields", "user(emailAddress)") }) })

    private suspend fun createFolder(name: String, parent: String): String {
        val meta = DriveJson.metadata(name, listOf(parent), DriveApi.FOLDER)
        val o = json("POST", url("/drive/v3/files") { add("fields", "id") }, meta.encodeToByteArray(), JSON)
        return DriveJson.parseFile(o)?.id ?: throw DriveException(500, "Google Drive didn't make the folder.")
    }

    private suspend fun multipart(meta: String, media: ByteArray, mime: String): RemoteFile {
        val boundary = "boringtravel-" + Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
        val body = Multipart.related(boundary, meta, media, mime)
        return parseFile(json("POST", uploadUrl("multipart"), body, "multipart/related; boundary=$boundary"))
    }

    private suspend fun resumable(path: String, size: Long, mime: String, meta: String): RemoteFile {
        val start = send(
            "POST", uploadUrl("resumable"), meta.encodeToByteArray(), JSON,
            mapOf("X-Upload-Content-Type" to mime, "X-Upload-Content-Length" to size.toString()),
        )
        if (!start.ok) throw DriveJson.error(start.code, start.text())
        val session = start.header("Location") ?: throw DriveException(500, "Google Drive didn't start the upload.")

        var offset = 0L
        var failures = 0
        var askWhereItStands = false
        while (true) {
            val step = try {
                if (askWhereItStands) {
                    // After a dropped piece: Drive says how much arrived, and the upload carries on from there.
                    chunkResult(send("PUT", session, ByteArray(0), null, mapOf("Content-Range" to "bytes */$size")))
                } else {
                    val end = minOf(offset + chunkSize, size)
                    val piece = files.readRange(path, offset, (end - offset).toInt())
                        ?.takeIf { it.size.toLong() == end - offset } ?: throw IOException("The file got shorter during the upload.")
                    chunkResult(send("PUT", session, piece, mime, mapOf("Content-Range" to "bytes $offset-${end - 1}/$size")))
                }
            } catch (e: IOException) {
                if (e is DriveException && !e.busy) throw e
                if (++failures > CHUNK_TRIES) throw e
                wait(retryWait * failures)
                askWhereItStands = true
                continue
            }
            askWhereItStands = false
            when (step) {
                is Chunk.Done -> return step.file
                is Chunk.Next -> offset = step.offset
            }
        }
    }

    private sealed interface Chunk {
        data class Next(val offset: Long) : Chunk
        data class Done(val file: RemoteFile) : Chunk
    }

    private fun chunkResult(r: NetAnswer): Chunk = when {
        r.code == 308 -> Chunk.Next(DriveJson.nextOffset(r.header("Range")))
        r.ok -> Chunk.Done(parseFile(DriveJson.parse(r.text())))
        else -> throw DriveJson.error(r.code, r.text())
    }

    private fun parseFile(o: JsonObject): RemoteFile = DriveJson.parseFile(o) ?: throw DriveException(500, "Google Drive didn't return the file.")

    /**
     * Adds the access token; a 401 gets one fresh token and one more try. Each answer's Date header sets the rows'
     * clock (Stamps: Google's time, as Android's RestDriveApi tells it; round 26).
     */
    private suspend fun send(method: String, address: String, body: ByteArray? = null, contentType: String? = null, headers: Map<String, String> = emptyMap()): NetAnswer {
        if (!connected()) throw DriveNotConnectedException()
        val token = tokens.token()
        val first = net.call(NetCall(method, address, headers + ("Authorization" to "Bearer $token"), body, contentType)).also { Stamps.heard(it.header("Date")) }
        if (first.code != 401) return first
        val fresh = tokens.refreshed(token)
        return net.call(NetCall(method, address, headers + ("Authorization" to "Bearer $fresh"), body, contentType)).also { Stamps.heard(it.header("Date")) }
    }

    /** A GET whose answer goes to [piece] as it comes (a file's bytes); the token as [send] adds it. */
    private suspend fun sendDownload(address: String, piece: (ByteArray, Int) -> Unit): NetAnswer {
        if (!connected()) throw DriveNotConnectedException()
        val token = tokens.token()
        val first = net.fetch(NetCall("GET", address, mapOf("Authorization" to "Bearer $token")), piece).also { Stamps.heard(it.header("Date")) }
        if (first.code != 401) return first
        val fresh = tokens.refreshed(token)
        return net.fetch(NetCall("GET", address, mapOf("Authorization" to "Bearer $fresh")), piece).also { Stamps.heard(it.header("Date")) }
    }

    private suspend fun json(method: String, address: String, body: ByteArray? = null, contentType: String? = null): JsonObject {
        val r = send(method, address, body, contentType)
        val text = r.text()
        if (!r.ok) throw DriveJson.error(r.code, text)
        return DriveJson.parse(text)
    }

    private fun url(path: String, params: Query.() -> Unit = {}): String {
        val q = Query().apply(params)
        val builder = "$base$path".toHttpUrl().newBuilder()
        q.pairs.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun uploadUrl(type: String): String = url("/upload/drive/v3/files") {
        add("uploadType", type)
        add("fields", DriveJson.FILE_FIELDS)
    }

    /** A request's query parameters in order (OkHttp's HttpUrl.Builder.addQueryParameter). */
    private class Query {
        val pairs = ArrayList<Pair<String, String>>()
        fun add(name: String, value: String) {
            pairs += name to value
        }
    }

    /** Bytes gathered piece by piece. */
    private class Bytes {
        private var buffer = ByteArray(64 * 1024)
        var size = 0
            private set

        fun add(b: ByteArray, n: Int) {
            if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + n))
            b.copyInto(buffer, size, 0, n)
            size += n
        }

        fun bytes(): ByteArray = buffer.copyOf(size)
    }

    companion object {
        const val ROOT = "root"
        const val MULTIPART_LIMIT = 5L * 1024 * 1024
        const val CHUNK_SIZE = 8L * 1024 * 1024
        private const val CHUNK_TRIES = 5
        private const val PAGE_SIZE = 1000
        private val DRIVE_ID = Regex("[A-Za-z0-9_-]{5,200}")
        private const val JSON = "application/json; charset=UTF-8"

        /** A Drive id the app may put in an address (Android's PublicDrive.requireId). */
        fun requireId(id: String) = require(DRIVE_ID.matches(id)) { "Invalid Drive id" }
    }
}

/**
 * A multipart/related body as OkHttp's MultipartBody writes it for Drive's uploads: the JSON metadata first, then the
 * file, each part with its type and length.
 */
object Multipart {
    fun related(boundary: String, meta: String, media: ByteArray, mime: String): ByteArray {
        val metaBytes = meta.encodeToByteArray()
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: ${metaBytes.size}\r\n\r\n"
        val mid = "\r\n--$boundary\r\nContent-Type: $mime\r\nContent-Length: ${media.size}\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        return head.encodeToByteArray() + metaBytes + mid.encodeToByteArray() + media + tail.encodeToByteArray()
    }
}

package com.justdataplease.boringtravel.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Access tokens for Drive calls (see DriveAuth). Both throw [DriveNotConnectedException] when the owner must act. */
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
 * Google Drive REST API v3 over OkHttp, without the Google API client library. Only `drive.file` and
 * `drive.appdata` are used, so the app sees just the files it created and its own hidden folder.
 *
 * Uploads up to [multipartLimit] go in one multipart request; bigger files use a resumable session in [chunkSize]
 * pieces (Drive wants multiples of 256 KiB) that picks up where it stopped after a dropped connection.
 */
class RestDriveApi(
    private val http: OkHttpClient,
    private val tokens: AccessTokens,
    private val connected: suspend () -> Boolean,
    private val base: String = "https://www.googleapis.com",
    private val multipartLimit: Long = MULTIPART_LIMIT,
    private val chunkSize: Long = CHUNK_SIZE,
    private val retryWait: Long = 1_000,
    /** Every reply's Date header, so rows can be stamped on Google's clock (data/repo/Stamps). */
    private val onServerTime: (dateHeader: String?) -> Unit = {},
    /** The app's folders found or made before, so one the owner renamed or moved is still found ([findFolder]). */
    private val memory: FolderMemory = FolderMemory.None,
) : DriveApi {
    /** Folder ids found or made this session, by path ("BoringTravel/Backups"). */
    private val folderIds = ConcurrentHashMap<String, String>()
    private val folderLock = Mutex()

    override suspend fun isConnected(): Boolean = connected()

    override suspend fun folder(path: List<String>): String =
        findFolder(path, create = true) ?: throw DriveException(500, "Google Drive didn't make the folder.")

    override suspend fun upload(file: File, name: String, mime: String, folderId: String?, convertTo: String?): DriveFile =
        uploadFile(file, name, mime, listOfNotNull(folderId), convertTo).toDriveFile()

    override suspend fun shareLink(file: DriveFile): String {
        shareAnyone(file.id)
        return openUrl(file)
    }

    /**
     * Lets anyone with the link view file or folder [id] (one this app made) without it showing in searches. Shared
     * trips and community posts are read this way (see [PublicDrive]).
     */
    suspend fun shareAnyone(id: String) {
        PublicDrive.requireId(id)
        if (!isConnected()) throw DriveNotConnectedException()
        val body = """{"role":"reader","type":"anyone","allowFileDiscovery":false}"""
        retrying(firstWait = retryWait) {
            json { Request.Builder().url(url("/drive/v3/files/$id/permissions") { addQueryParameter("fields", "id") }).post(body.toRequestBody(JSON)) }
        }
    }

    /** Takes file or folder [id] off its link: only its owner can open it again. */
    suspend fun unshareAnyone(id: String) {
        PublicDrive.requireId(id)
        retrying(firstWait = retryWait) {
            send { Request.Builder().url(url("/drive/v3/files/$id/permissions/anyoneWithLink")).delete() }.use { r ->
                if (!r.isSuccessful && r.code != 404) throw DriveJson.error(r.code, readBody(r))
            }
        }
    }

    /** A new file [name] in [parents] holding [content] (small files: one request). */
    suspend fun createBytes(name: String, content: ByteArray, mime: String, parents: List<String>, appProperties: Map<String, String> = emptyMap()): RemoteFile =
        retrying(firstWait = retryWait) { multipart(DriveJson.metadata(name, parents, null, appProperties), content.toRequestBody(mime.toMediaType())) }

    /** Replaces the content of file [id] (made by this app); the id, its link and its sharing stay. */
    suspend fun replaceBytes(id: String, content: ByteArray, mime: String): RemoteFile {
        PublicDrive.requireId(id)
        val url = url("/upload/drive/v3/files/$id") {
            addQueryParameter("uploadType", "media")
            addQueryParameter("fields", DriveJson.FILE_FIELDS)
        }
        return parseFile(retrying(firstWait = retryWait) { json { Request.Builder().url(url).patch(content.toRequestBody(mime.toMediaType())) } })
    }

    /** A new folder [name] inside [parent] ("root" = My Drive), even when one of that name exists. */
    suspend fun newFolder(name: String, parent: String): String = retrying(firstWait = retryWait) { createFolder(name, parent) }

    override suspend fun exportText(id: String, maxBytes: Int): ByteArray {
        require(id.matches(Regex("[A-Za-z0-9_-]{5,200}"))) { "Invalid Google Doc id" }
        require(maxBytes in 1..1_000_000)
        if (!isConnected()) throw DriveNotConnectedException()
        return retrying(firstWait = retryWait) {
            send { Request.Builder().url(url("/drive/v3/files/$id/export") { addQueryParameter("mimeType", "text/plain") }) }.use { response ->
                if (!response.isSuccessful) throw DriveJson.error(response.code, readBody(response))
                withContext(Dispatchers.IO) {
                    val body = response.body ?: throw IOException("Google Drive sent an empty document.")
                    if (body.contentLength() > maxBytes) throw DriveExportTooLargeException()
                    val out = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (out.size() + count > maxBytes) throw DriveExportTooLargeException()
                            out.write(buffer, 0, count)
                        }
                    }
                    out.toByteArray()
                }
            }
        }
    }

    override suspend fun <T> export(id: String, mimeType: String, read: (java.io.InputStream) -> T): T {
        require(id.matches(Regex("[A-Za-z0-9_-]{5,200}"))) { "Invalid Google Doc id" }
        if (!isConnected()) throw DriveNotConnectedException()
        return retrying(firstWait = retryWait) {
            send { Request.Builder().url(url("/drive/v3/files/$id/export") { addQueryParameter("mimeType", mimeType) }) }.use { response ->
                if (!response.isSuccessful) throw DriveJson.error(response.code, readBody(response))
                withContext(Dispatchers.IO) { (response.body ?: throw IOException("Google Drive sent an empty document.")).byteStream().use(read) }
            }
        }
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
            val id = folderIds[keys[depth]] ?: remembered(keys[depth]) ?: continue
            folderIds[keys[depth]] = id
            start = depth + 1
            parent = id
            break
        }
        for (depth in start until path.size) {
            val name = path[depth]
            val found = list(DriveJson.folderQuery(name, parent), orderBy = "createdTime", fields = "id,name", limit = 1).firstOrNull()?.id
            val id = found ?: if (create) createFolder(name, parent) else return@withLock null
            folderIds[keys[depth]] = id
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
        folderIds[key] = id
        memory.put(key, id)
    }

    /** The folder file [id] is in, when the app can see both and neither is in the trash; null otherwise. */
    suspend fun parentOf(id: String): String? {
        if (!DRIVE_ID.matches(id)) return null
        val o = try {
            retrying(firstWait = retryWait) { json { Request.Builder().url(url("/drive/v3/files/$id") { addQueryParameter("fields", "parents,trashed") }) } }
        } catch (e: DriveException) {
            if (e.code == 404) return null else throw e
        }
        if ((o["trashed"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val parent = ((o["parents"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return null
        if (!DRIVE_ID.matches(parent)) return null
        return fileOrNull(parent)?.takeIf { it.mimeType == DriveApi.FOLDER }?.id
    }

    /** Forgets the folder ids found this session (after a disconnect, or a folder the owner deleted); kept ids are checked again. */
    fun forgetFolders() = folderIds.clear()

    /** Runs [block] with the folder at [path]; if Drive says the folder is gone, finds or makes it again once. */
    suspend fun <T> intoFolder(path: List<String>, block: suspend (folderId: String) -> T): T = try {
        block(folder(path))
    } catch (e: DriveException) {
        if (e.code != 404) throw e
        forgetFolders()
        block(folder(path))
    }

    /** Uploads [file] with optional conversion ([convertTo]) and private [appProperties] other phones can read in lists. */
    suspend fun uploadFile(
        file: File,
        name: String,
        mime: String,
        parents: List<String>,
        convertTo: String? = null,
        appProperties: Map<String, String> = emptyMap(),
    ): RemoteFile {
        val meta = DriveJson.metadata(name, parents, convertTo, appProperties)
        val size = withContext(Dispatchers.IO) { file.length() }
        return if (size <= multipartLimit) {
            multipart(meta, file.asRequestBody(mime.toMediaType()))
        } else {
            resumable(file, size, mime, meta)
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
            val url = url("/drive/v3/files") {
                addQueryParameter("q", q)
                addQueryParameter("spaces", spaces)
                addQueryParameter("fields", "nextPageToken,files($fields)")
                addQueryParameter("pageSize", minOf(PAGE_SIZE, limit - out.size).toString())
                orderBy?.let { addQueryParameter("orderBy", it) }
                page?.let { addQueryParameter("pageToken", it) }
            }
            val (files, next) = DriveJson.parseList(retrying(firstWait = retryWait) { json { Request.Builder().url(url) } })
            out += files
            page = next
        } while (page != null && out.size < limit)
        return out
    }

    /** A file's metadata, or null when it is gone or in the trash. */
    suspend fun fileOrNull(id: String): RemoteFile? = try {
        val o = retrying(firstWait = retryWait) { json { Request.Builder().url(url("/drive/v3/files/$id") { addQueryParameter("fields", DriveJson.LIST_FIELDS) }) } }
        DriveJson.parseFile(o)?.takeUnless { it.trashed }
    } catch (e: DriveException) {
        if (e.code == 404) null else throw e
    }

    /** Downloads a file's content into [target] (through a .part file, so a broken download leaves nothing half-written). */
    suspend fun download(id: String, target: File, onBytes: (Long) -> Unit = {}) {
        val part = File(target.path + ".part")
        withContext(Dispatchers.IO) { target.parentFile?.mkdirs() }
        retrying(firstWait = retryWait) {
            send { Request.Builder().url(url("/drive/v3/files/$id") { addQueryParameter("alt", "media") }) }.use { r ->
                if (!r.isSuccessful) throw DriveJson.error(r.code, readBody(r))
                withContext(Dispatchers.IO) {
                    val input = r.body?.byteStream() ?: throw DriveException(500, "Google Drive sent an empty file.")
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            onBytes(total)
                        }
                    }
                }
            }
        }
        withContext(Dispatchers.IO) {
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        }
    }

    /** A small file's content. */
    suspend fun bytes(id: String): ByteArray = retrying(firstWait = retryWait) {
        send { Request.Builder().url(url("/drive/v3/files/$id") { addQueryParameter("alt", "media") }) }.use { r ->
            if (!r.isSuccessful) throw DriveJson.error(r.code, readBody(r))
            withContext(Dispatchers.IO) { r.body?.bytes() ?: ByteArray(0) }
        }
    }

    /** Deletes a file this app made (gone already counts as done). */
    suspend fun delete(id: String) = retrying(firstWait = retryWait) {
        send { Request.Builder().url(url("/drive/v3/files/$id")).delete() }.use { r ->
            if (!r.isSuccessful && r.code != 404) throw DriveJson.error(r.code, readBody(r))
        }
    }

    /** The content of [name] in the app's hidden folder, or null when there is none. */
    suspend fun readAppData(name: String): ByteArray? {
        val file = list(DriveJson.nameQuery(name), spaces = DriveJson.APP_DATA, orderBy = "modifiedTime desc", limit = 1).firstOrNull() ?: return null
        return bytes(file.id)
    }

    /** Writes [name] in the app's hidden folder, replacing its content when it is already there. */
    suspend fun writeAppData(name: String, content: ByteArray, mime: String = "application/json"): RemoteFile {
        val existing = list(DriveJson.nameQuery(name), spaces = DriveJson.APP_DATA, orderBy = "modifiedTime desc", limit = 1).firstOrNull()
        val type = mime.toMediaType()
        if (existing == null) return multipart(DriveJson.metadata(name, listOf(DriveJson.APP_DATA)), content.toRequestBody(type))
        val url = url("/upload/drive/v3/files/${existing.id}") {
            addQueryParameter("uploadType", "media")
            addQueryParameter("fields", DriveJson.FILE_FIELDS)
        }
        return parseFile(retrying(firstWait = retryWait) { json { Request.Builder().url(url).patch(content.toRequestBody(type)) } })
    }

    /** The email of the Google account the token belongs to. */
    suspend fun accountEmail(): String? =
        DriveJson.parseAboutEmail(retrying(firstWait = retryWait) { json { Request.Builder().url(url("/drive/v3/about") { addQueryParameter("fields", "user(emailAddress)") }) } })

    private suspend fun createFolder(name: String, parent: String): String {
        val meta = DriveJson.metadata(name, listOf(parent), DriveApi.FOLDER)
        val o = json { Request.Builder().url(url("/drive/v3/files") { addQueryParameter("fields", "id") }).post(meta.toRequestBody(JSON)) }
        return DriveJson.parseFile(o)?.id ?: throw DriveException(500, "Google Drive didn't make the folder.")
    }

    private suspend fun multipart(meta: String, media: RequestBody): RemoteFile {
        val url = uploadUrl("multipart")
        val o = json {
            Request.Builder().url(url).post(
                MultipartBody.Builder()
                    .setType(RELATED)
                    .addPart(meta.toRequestBody(JSON))
                    .addPart(media)
                    .build(),
            )
        }
        return parseFile(o)
    }

    private suspend fun resumable(file: File, size: Long, mime: String, meta: String): RemoteFile {
        val session = send {
            Request.Builder().url(uploadUrl("resumable")).post(meta.toRequestBody(JSON))
                .header("X-Upload-Content-Type", mime)
                .header("X-Upload-Content-Length", size.toString())
        }.use { r ->
            if (!r.isSuccessful) throw DriveJson.error(r.code, readBody(r))
            r.header("Location")
        } ?: throw DriveException(500, "Google Drive didn't start the upload.")

        val type = mime.toMediaType()
        var offset = 0L
        var failures = 0
        var askWhereItStands = false
        while (true) {
            val step = try {
                if (askWhereItStands) {
                    // After a dropped piece: Drive says how much arrived, and the upload carries on from there.
                    send {
                        Request.Builder().url(session).put(ByteArray(0).toRequestBody(null)).header("Content-Range", "bytes */$size")
                    }.use { chunkResult(it) }
                } else {
                    val end = minOf(offset + chunkSize, size)
                    send {
                        Request.Builder().url(session)
                            .put(FileSlice(file, offset, end - offset, type))
                            .header("Content-Range", "bytes $offset-${end - 1}/$size")
                    }.use { chunkResult(it) }
                }
            } catch (e: IOException) {
                if (e is DriveException && !e.busy) throw e
                if (++failures > CHUNK_TRIES) throw e
                delay(retryWait * failures)
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

    private suspend fun chunkResult(r: Response): Chunk {
        val body = readBody(r)
        return when {
            r.code == 308 -> Chunk.Next(DriveJson.nextOffset(r.header("Range")))
            r.isSuccessful -> Chunk.Done(parseFile(DriveJson.parse(body)))
            else -> throw DriveJson.error(r.code, body)
        }
    }

    private fun parseFile(o: JsonObject): RemoteFile = DriveJson.parseFile(o) ?: throw DriveException(500, "Google Drive didn't return the file.")

    /** Adds the access token; a 401 gets one fresh token and one more try. */
    private suspend fun send(build: () -> Request.Builder): Response {
        if (!connected()) throw DriveNotConnectedException()
        val token = tokens.token()
        val first = http.newCall(build().header("Authorization", "Bearer $token").build()).await()
        onServerTime(first.header("Date"))
        if (first.code != 401) return first
        first.close()
        val fresh = tokens.refreshed(token)
        return http.newCall(build().header("Authorization", "Bearer $fresh").build()).await().also { onServerTime(it.header("Date")) }
    }

    private suspend fun json(build: () -> Request.Builder): JsonObject = send(build).use { r ->
        val body = readBody(r)
        if (!r.isSuccessful) throw DriveJson.error(r.code, body)
        DriveJson.parse(body)
    }

    private suspend fun readBody(r: Response): String = withContext(Dispatchers.IO) { r.body?.string().orEmpty() }

    private fun url(path: String, params: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        "$base$path".toHttpUrl().newBuilder().apply(params).build()

    private fun uploadUrl(type: String): HttpUrl = url("/upload/drive/v3/files") {
        addQueryParameter("uploadType", type)
        addQueryParameter("fields", DriveJson.FILE_FIELDS)
    }

    /** One piece of a file for a resumable upload. */
    private class FileSlice(private val file: File, private val offset: Long, private val length: Long, private val type: MediaType) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException("The file got shorter during the upload.")
                    sink.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }

    companion object {
        const val ROOT = "root"
        const val MULTIPART_LIMIT = 5L * 1024 * 1024
        const val CHUNK_SIZE = 8L * 1024 * 1024
        private const val CHUNK_TRIES = 5
        private const val PAGE_SIZE = 1000
        private val DRIVE_ID = Regex("[A-Za-z0-9_-]{5,200}")
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val RELATED = "multipart/related".toMediaType()
    }
}

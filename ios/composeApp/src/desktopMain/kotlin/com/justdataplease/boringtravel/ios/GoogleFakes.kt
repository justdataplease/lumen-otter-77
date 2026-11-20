package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.google.AppleCredential
import com.justdataplease.boringtravel.google.AppleSignIn
import com.justdataplease.boringtravel.google.AuthBrowser
import com.justdataplease.boringtravel.google.GoogleOAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * Google, Drive and Firebase Auth as fakes, for the tests and the pictures (round 23): nothing here reaches the network.
 * [FakeGoogleCloud] answers this app's requests as Google's servers do (OAuth's token and revoke endpoints, Drive's REST
 * API v3 with its folders, lists and queries, multipart and resumable uploads, downloads, exports, the hidden app folder
 * and sharing, Firebase Auth's Apple sign-in, revoke and delete), on files kept in memory; [FakeAuthBrowser] stands for
 * Google's sign-in page (the account picked, Drive ticked or not, closed, refused); [FakeAppleSignIn] for Apple's sheet.
 * Addresses are made up (example.com), tokens are counters.
 */

/**
 * An account on the fake Google: [email] and [name] (made up); [sub] is its Google id (round 26: two travellers on two
 * phones are two accounts, two Firebase users).
 */
data class FakeAccount(val email: String = "traveller@example.com", val name: String? = "Traveller", val sub: String = "sub-1")

/** A file or folder in the fake Drive. */
class FakeDriveItem(
    val id: String,
    var name: String,
    val mimeType: String,
    var parents: List<String>,
    var content: ByteArray,
    var appProperties: Map<String, String>,
    val created: Int,
    var trashed: Boolean = false,
    var anyone: Boolean = false,
) {
    val isFolder: Boolean get() = mimeType == FOLDER
    val text: String get() = content.decodeToString()

    companion object {
        const val FOLDER = "application/vnd.google-apps.folder"
    }
}

/** One request the fake was asked, as it came. */
class FakeCall(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray, val contentType: String?) {
    private val parsed = WebUrl.of(url)
    val host: String get() = parsed?.host.orEmpty()
    val path: String get() = parsed?.encodedPath.orEmpty()
    fun q(name: String): String? = parsed?.queryParameter(name)
    val text: String get() = body.decodeToString()
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * Google's servers in memory. [account] is who signs in; [refreshRevoked] makes the next refresh answer invalid_grant
 * (Google wants the owner again); [route] answers a request before the fake does (failures to test); [connected] false:
 * no connection; [metered] the connection's cost.
 */
@OptIn(ExperimentalEncodingApi::class)
class FakeGoogleCloud(
    var account: FakeAccount = FakeAccount(),
    var connected: Boolean = true,
    var metered: Boolean = false,
    /** Cloud Firestore (round 24): the community's documents; one shared by every phone of a test (round 26's mailboxes). */
    val firestore: FakeFirestore = FakeFirestore(),
    /** How this Drive's ids start (round 26: each traveller's Drive its own, as Google's ids never meet). */
    private val idPrefix: String = "drv",
) : Net {
    private val json = Json { ignoreUnknownKeys = true }
    private var n = 0

    /** Every request, in order. */
    val calls = mutableListOf<FakeCall>()

    /** Answers a request first when it gives one (a 503, a full Drive, a dropped connection: throw NetException). */
    var route: ((FakeCall) -> NetAnswer?)? = null

    /** Drive's files and folders by id, in the order made. */
    val items = LinkedHashMap<String, FakeDriveItem>()

    /** Access tokens Google accepts now; refresh tokens it will renew; and what each grants. */
    private val access = HashMap<String, Set<String>>()
    private val refresh = HashMap<String, Set<String>>()
    private val codes = HashMap<String, Pair<Set<String>, FakeAccount>>()
    val revoked = mutableListOf<String>()
    var refreshRevoked = false

    /** Firebase Auth: its users by id, and what was asked of it. */
    val firebaseUsers = LinkedHashMap<String, String?>()
    val appleRevoked = mutableListOf<String>()
    val firebaseDeleted = mutableListOf<String>()

    /** Firebase Auth's tokens (round 24): each ID token and refresh token to its account's id; Firestore reads the first. */
    val firebaseIdTokens = HashMap<String, String>()
    private val firebaseRefresh = HashMap<String, String>()

    /** Files shared by link in someone's Drive (a community post's pictures and clips), by id. */
    val publicFiles = LinkedHashMap<String, ByteArray>()

    /**
     * Other people's Drives (round 26: the other members of a shared trip, each phone with its own cloud): their files
     * shared by link ("anyone with the link", the file or its folder) are read here by link, as Google serves them.
     */
    val others = mutableListOf<FakeGoogleCloud>()

    /** How many link reads of each file's content there were (checksums don't count): an unchanged copy isn't read again. */
    val linkReads = LinkedHashMap<String, Int>()

    /** A Firebase account's ID token, as Firebase gives one (tests use it to reach Firestore as that account). */
    fun firebaseToken(uid: String): String = "fb-id-${++n}".also { firebaseIdTokens[it] = uid }

    /** Resumable uploads in progress, by session. */
    private class Session(val meta: JsonObject, val mime: String, val size: Long, var bytes: ByteArray = ByteArray(0))
    private val sessions = HashMap<String, Session>()

    override fun online(): Boolean = connected

    override fun metered(): Boolean = metered

    override suspend fun get(request: NetRequest): NetAnswer = send(NetCall("GET", request.url, request.headers))

    override suspend fun post(request: NetRequest): NetAnswer =
        send(NetCall("POST", request.url, request.headers, request.body?.encodeToByteArray(), request.contentType))

    override suspend fun send(call: NetCall): NetAnswer {
        val c = FakeCall(call.method, call.url, call.headers, call.body ?: ByteArray(0), call.contentType)
        calls += c
        if (!connected) throw NetException("No connection")
        route?.invoke(c)?.let { return it }
        return when (c.host) {
            "oauth2.googleapis.com" -> oauth(c)
            // A link-shared file read with the app's API key (a community post's picture or clip), else Drive with a token.
            "www.googleapis.com" -> when {
                c.q("key") != null && c.q("alt") == "media" && c.path.startsWith("/drive/v3/files/") -> shared(c, c.path.removePrefix("/drive/v3/files/"))
                // A link-shared file's MD5 by the app's API key (round 26: another member's copy, unchanged or not).
                c.q("key") != null && c.q("fields") == "md5Checksum" && c.path.startsWith("/drive/v3/files/") -> checksum(c, c.path.removePrefix("/drive/v3/files/"))
                !authorized(c) -> answer(c, 401, """{"error":{"code":401,"message":"Invalid Credentials"}}""")
                else -> drive(c)
            }
            // Google's public download address (no key in the build).
            "drive.usercontent.google.com" -> shared(c, c.q("id").orEmpty())
            "identitytoolkit.googleapis.com", "securetoken.googleapis.com" -> firebase(c)
            "firestore.googleapis.com" -> firestore.answer(c, c.header("Authorization")?.removePrefix("Bearer ")?.let(firebaseIdTokens::get), c.header("Authorization") != null)
            else -> NetAnswer(404, c.url)
        }
    }

    /**
     * A file shared by link: its bytes, or Drive's 404 when it isn't (or isn't shared any more). Someone's posted files
     * ([publicFiles]), or a file in this Drive whose folder (or itself) anyone with the link may view (a post made here).
     */
    private fun shared(c: FakeCall, id: String): NetAnswer {
        val bytes = byLink(id) ?: return answer(c, 404, """{"error":{"code":404,"message":"File not found: $id."}}""")
        linkReads[id] = (linkReads[id] ?: 0) + 1
        val type = if (bytes.size > 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte()) "video/mp4" else "image/jpeg"
        return NetAnswer(200, c.url, mapOf("Content-Type" to type), bytes)
    }

    /** A file anyone with its link may read: posted ones, this Drive's shared ones, then the other people's Drives'. */
    private fun byLink(id: String): ByteArray? =
        publicFiles[id] ?: ownByLink(id) ?: others.firstNotNullOfOrNull { it.ownByLink(id) }

    private fun ownByLink(id: String): ByteArray? =
        items[id]?.takeIf { f -> !f.trashed && (f.anyone || f.parents.any { items[it]?.anyone == true }) }?.content

    /** Drive's md5Checksum of a link-shared file, or Drive's 404 when it isn't readable by link. */
    private fun checksum(c: FakeCall, id: String): NetAnswer {
        val bytes = byLink(id) ?: return answer(c, 404, """{"error":{"code":404,"message":"File not found: $id."}}""")
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        return answer(c, 200, """{"md5Checksum":"$md5"}""")
    }

    // ------------------------------------------------------------------ OAuth

    /** A code for [scopes] of [who], as Google's page would give it after the traveller's yes. */
    fun issueCode(scopes: Set<String>, who: FakeAccount = account): String {
        val code = "code-${++n}"
        codes[code] = scopes to who
        return code
    }

    /** Makes the access tokens given so far stale (Google answers 401 until a refresh). */
    fun expireAccess() = access.clear()

    /** The scopes the access token in [call] carries. */
    fun scopesOf(call: FakeCall): Set<String>? = call.header("Authorization")?.removePrefix("Bearer ")?.let(access::get)

    private fun authorized(c: FakeCall): Boolean {
        if (c.path.startsWith("/upload/drive/v3/files") && c.q("upload_id") != null) return true
        return scopesOf(c)?.contains("https://www.googleapis.com/auth/drive.file") == true
    }

    private fun form(c: FakeCall): Map<String, String> = c.text.split('&').filter { it.isNotEmpty() }.associate { p ->
        val eq = p.indexOf('=')
        java.net.URLDecoder.decode(p.substring(0, eq), Charsets.UTF_8) to java.net.URLDecoder.decode(p.substring(eq + 1), Charsets.UTF_8)
    }

    private fun oauth(c: FakeCall): NetAnswer {
        val f = form(c)
        return when (c.path) {
            "/token" -> when (f["grant_type"]) {
                "authorization_code" -> {
                    val (scopes, who) = codes.remove(f["code"]) ?: return answer(c, 400, """{"error":"invalid_grant"}""")
                    if (f["code_verifier"].isNullOrBlank()) return answer(c, 400, """{"error":"invalid_request"}""")
                    val token = "access-${++n}"
                    val refreshToken = "refresh-${++n}"
                    access[token] = scopes
                    refresh[refreshToken] = scopes
                    answer(c, 200, buildJsonObject {
                        put("access_token", token)
                        put("expires_in", 3599)
                        put("refresh_token", refreshToken)
                        put("scope", scopes.joinToString(" "))
                        put("token_type", "Bearer")
                        if ("openid" in scopes) put("id_token", jwt(buildJsonObject { put("email", who.email); who.name?.let { put("name", it) }; put("sub", who.sub) }))
                    }.toString())
                }
                "refresh_token" -> {
                    val scopes = refresh[f["refresh_token"]]
                    if (scopes == null || refreshRevoked || f["refresh_token"] in revoked) return answer(c, 400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""")
                    val token = "access-${++n}"
                    access[token] = scopes
                    answer(c, 200, buildJsonObject {
                        put("access_token", token)
                        put("expires_in", 3599)
                        put("scope", scopes.joinToString(" "))
                        // A grant with openid gives a fresh ID token on each refresh, as Google's does.
                        if ("openid" in scopes) put("id_token", jwt(buildJsonObject { put("email", account.email); account.name?.let { put("name", it) }; put("sub", account.sub) }))
                    }.toString())
                }
                else -> answer(c, 400, """{"error":"unsupported_grant_type"}""")
            }
            "/revoke" -> {
                f["token"]?.let { revoked += it; refresh.remove(it); access.remove(it) }
                answer(c, 200, "")
            }
            else -> NetAnswer(404, c.url)
        }
    }

    // ------------------------------------------------------------------ Drive

    private fun newId(): String = idPrefix + (++n).toString().padStart(5, '0')

    private fun time(i: Int): String = "2026-11-19T10:${(i / 60 % 60).toString().padStart(2, '0')}:${(i % 60).toString().padStart(2, '0')}.000Z"

    private fun fileJson(f: FakeDriveItem): JsonObject = buildJsonObject {
        put("id", f.id)
        put("name", f.name)
        put("mimeType", f.mimeType)
        put("webViewLink", if (f.mimeType == "application/vnd.google-apps.document") "https://docs.google.com/document/d/${f.id}/edit" else "https://drive.google.com/file/d/${f.id}/view")
        put("modifiedTime", time(f.created))
        put("createdTime", time(f.created))
        if (!f.isFolder) put("size", f.content.size.toString())
        if (f.appProperties.isNotEmpty()) put("appProperties", JsonObject(f.appProperties.mapValues { JsonPrimitive(it.value) }))
        put("trashed", f.trashed)
        put("parents", JsonArray(f.parents.map(::JsonPrimitive)))
    }

    /** Drive's query as this app writes it (DriveJson): name, parent, folder or not, trashed. */
    private fun matches(f: FakeDriveItem, q: String): Boolean {
        fun unq(s: String) = s.replace("\\'", "'").replace("\\\\", "\\")
        Regex("""name = '((?:\\.|[^'])*)'""").find(q)?.let { if (f.name != unq(it.groupValues[1])) return false }
        Regex("""'((?:\\.|[^'])*)' in parents""").find(q)?.let { if (unq(it.groupValues[1]) !in f.parents) return false }
        if ("mimeType = '${FakeDriveItem.FOLDER}'" in q && !f.isFolder) return false
        if ("mimeType != '${FakeDriveItem.FOLDER}'" in q && f.isFolder) return false
        if ("trashed = false" in q && f.trashed) return false
        return true
    }

    private fun drive(c: FakeCall): NetAnswer {
        val path = c.path
        val id = path.removePrefix("/drive/v3/files/").substringBefore('/')
        return when {
            c.method == "GET" && path == "/drive/v3/about" -> answer(c, 200, """{"user":{"emailAddress":"${account.email}"}}""")
            c.method == "GET" && path == "/drive/v3/files" -> {
                val q = c.q("q").orEmpty()
                val app = c.q("spaces") == "appDataFolder"
                var hits = items.values.filter { f -> ("appDataFolder" in f.parents) == app && matches(f, q) }
                hits = when (c.q("orderBy")) {
                    "createdTime" -> hits.sortedBy { it.created }
                    "createdTime desc", "modifiedTime desc" -> hits.sortedByDescending { it.created }
                    else -> hits
                }
                val size = c.q("pageSize")?.toIntOrNull() ?: 100
                val from = c.q("pageToken")?.toIntOrNull() ?: 0
                val page = hits.drop(from).take(size)
                val next = if (from + size < hits.size) (from + size).toString() else null
                answer(c, 200, buildJsonObject {
                    next?.let { put("nextPageToken", it) }
                    put("files", JsonArray(page.map(::fileJson)))
                }.toString())
            }
            c.method == "GET" && path.endsWith("/export") -> {
                val f = items[id] ?: return answer(c, 404, """{"error":{"code":404,"message":"File not found: $id."}}""")
                answer(c, 200, f.text)
            }
            c.method == "GET" && path.startsWith("/drive/v3/files/") -> {
                val f = items[id] ?: return answer(c, 404, """{"error":{"code":404,"message":"File not found: $id."}}""")
                if (c.q("alt") == "media") NetAnswer(200, c.url, mapOf("Content-Type" to f.mimeType), f.content) else answer(c, 200, fileJson(f).toString())
            }
            c.method == "POST" && path == "/drive/v3/files" -> {
                val meta = json.parseToJsonElement(c.text).jsonObject
                goneParent(meta)?.let { return answer(c, 404, """{"error":{"code":404,"message":"File not found: $it."}}""") }
                val f = add(meta, ByteArray(0), (meta["mimeType"] as? JsonPrimitive)?.content ?: FakeDriveItem.FOLDER)
                answer(c, 200, """{"id":"${f.id}"}""")
            }
            c.method == "POST" && path.endsWith("/permissions") -> {
                items[id]?.anyone = true
                answer(c, 200, """{"id":"anyoneWithLink"}""")
            }
            c.method == "DELETE" && path.endsWith("/permissions/anyoneWithLink") -> {
                items[id]?.anyone = false
                answer(c, 204, "")
            }
            // A folder goes with everything in it, as Drive deletes it.
            c.method == "DELETE" && path.startsWith("/drive/v3/files/") ->
                if (items.remove(id) == null) answer(c, 404, """{"error":{"code":404}}""") else {
                    var gone = setOf(id)
                    while (true) {
                        val inside = items.values.filter { f -> f.parents.any { it in gone } }.map { it.id }
                        if (inside.isEmpty()) break
                        inside.forEach { items.remove(it) }
                        gone = inside.toSet()
                    }
                    answer(c, 204, "")
                }
            path == "/upload/drive/v3/files" && c.q("uploadType") == "multipart" -> {
                val boundary = c.contentType?.substringAfter("boundary=", "")?.trim('"') ?: return answer(c, 400, "{}")
                val (meta, media) = multipart(c.body, boundary)
                val metaJson = json.parseToJsonElement(meta.decodeToString()).jsonObject
                goneParent(metaJson)?.let { return answer(c, 404, """{"error":{"code":404,"message":"File not found: $it."}}""") }
                val f = add(metaJson, media.second, media.first)
                answer(c, 200, fileJson(f).toString())
            }
            path == "/upload/drive/v3/files" && c.q("uploadType") == "resumable" && c.method == "POST" -> {
                goneParent(json.parseToJsonElement(c.text).jsonObject)?.let { return answer(c, 404, """{"error":{"code":404,"message":"File not found: $it."}}""") }
                val sid = "s${++n}"
                sessions[sid] = Session(json.parseToJsonElement(c.text).jsonObject, c.header("X-Upload-Content-Type").orEmpty(), c.header("X-Upload-Content-Length")?.toLong() ?: 0)
                NetAnswer(200, c.url, mapOf("Location" to "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=$sid"))
            }
            c.q("upload_id") != null && c.method == "PUT" -> {
                val s = sessions[c.q("upload_id")] ?: return answer(c, 404, "{}")
                val range = c.header("Content-Range").orEmpty()
                if (!range.startsWith("bytes */")) s.bytes += c.body
                if (s.bytes.size.toLong() < s.size) {
                    NetAnswer(308, c.url, if (s.bytes.isEmpty()) emptyMap() else mapOf("Range" to "bytes=0-${s.bytes.size - 1}"))
                } else {
                    sessions.remove(c.q("upload_id"))
                    answer(c, 200, fileJson(add(s.meta, s.bytes, s.mime)).toString())
                }
            }
            c.method == "PATCH" && path.startsWith("/upload/drive/v3/files/") -> {
                val f = items[path.removePrefix("/upload/drive/v3/files/")] ?: return answer(c, 404, "{}")
                f.content = c.body
                answer(c, 200, fileJson(f).toString())
            }
            else -> answer(c, 400, """{"error":{"message":"The fake has no ${c.method} $path"}}""")
        }
    }

    /**
     * A folder [meta] names as a parent that this Drive made and no longer has (deleted by its owner in Drive), as
     * Drive refuses a file into it with 404 (round 26: a shared trip's folder deleted, made again).
     */
    private fun goneParent(meta: JsonObject): String? =
        (meta["parents"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.firstOrNull { p -> p.startsWith("drv") && items[p]?.takeIf { !it.trashed } == null }

    private fun add(meta: JsonObject, content: ByteArray, mime: String, given: String? = null): FakeDriveItem {
        val id = given ?: newId()
        val type = (meta["mimeType"] as? JsonPrimitive)?.content ?: mime
        val f = FakeDriveItem(
            id = id,
            name = (meta["name"] as? JsonPrimitive)?.content.orEmpty(),
            mimeType = type,
            parents = (meta["parents"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: listOf("root"),
            content = content,
            appProperties = (meta["appProperties"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty(),
            created = n,
        )
        items[id] = f
        return f
    }

    /** The metadata part's bytes, and the media part's type and bytes. */
    private fun multipart(body: ByteArray, boundary: String): Pair<ByteArray, Pair<String, ByteArray>> {
        val text = String(body, Charsets.ISO_8859_1)
        val parts = text.split("--$boundary").drop(1).filter { !it.startsWith("--") }
        fun split(part: String): Pair<String, ByteArray> {
            val head = part.substringBefore("\r\n\r\n")
            val raw = part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
            val type = head.lines().firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()
            return type to raw.toByteArray(Charsets.ISO_8859_1)
        }
        return split(parts[0]).second to split(parts[1])
    }

    /** Puts a file into Drive as another phone would have (a backup made on Android, a photo). */
    fun put(name: String, parents: List<String>, content: ByteArray, mime: String = "application/octet-stream", appProperties: Map<String, String> = emptyMap(), id: String? = null): FakeDriveItem =
        add(buildJsonObject {
            put("name", name)
            put("parents", JsonArray(parents.map(::JsonPrimitive)))
            put("mimeType", mime)
            if (appProperties.isNotEmpty()) put("appProperties", JsonObject(appProperties.mapValues { JsonPrimitive(it.value) }))
        }, content, mime, id)

    /** A folder path under My Drive as the app makes it, made where missing; its id. */
    fun folder(path: List<String>): String {
        var parent = "root"
        for (name in path) {
            parent = items.values.firstOrNull { it.isFolder && it.name == name && parent in it.parents && !it.trashed }?.id
                ?: add(buildJsonObject { put("name", name); put("parents", JsonArray(listOf(JsonPrimitive(parent)))); put("mimeType", FakeDriveItem.FOLDER) }, ByteArray(0), FakeDriveItem.FOLDER).id
        }
        return parent
    }

    /** The files directly in the folder at [path] (none when it isn't there). */
    fun filesIn(path: List<String>): List<FakeDriveItem> {
        var parent = "root"
        for (name in path) parent = items.values.firstOrNull { it.isFolder && it.name == name && parent in it.parents && !it.trashed }?.id ?: return emptyList()
        return items.values.filter { !it.isFolder && parent in it.parents && !it.trashed }
    }

    // ------------------------------------------------------------------ Firebase Auth

    private fun firebase(c: FakeCall): NetAnswer {
        if (c.q("key").isNullOrBlank()) return answer(c, 400, """{"error":{"message":"API key not valid"}}""")
        val o = runCatching { json.parseToJsonElement(c.text).jsonObject }.getOrNull()
        return when (c.path) {
            "/v1/accounts:signInWithIdp" -> {
                val post = (o?.get("postBody") as? JsonPrimitive)?.content.orEmpty()
                val google = "providerId=google.com" in post
                if (!google && ("providerId=apple.com" !in post || "nonce=" !in post)) return answer(c, 400, """{"error":{"message":"INVALID_IDP_RESPONSE"}}""")
                val token = java.net.URLDecoder.decode(post.substringAfter("id_token=").substringBefore('&'), Charsets.UTF_8)
                val who = GoogleOAuth.who(token)
                if (google && who.subject == null) return answer(c, 400, """{"error":{"message":"INVALID_IDP_RESPONSE"}}""")
                val email = who.email
                // Apple's account is always the same one here; Google's is named after its subject.
                val id = if (google) "fb-google-${who.subject}" else "fb-user-1"
                firebaseUsers[id] = email
                val idToken = firebaseToken(id)
                val refreshToken = "fb-refresh-${++n}".also { firebaseRefresh[it] = id }
                answer(c, 200, buildJsonObject {
                    put("localId", id)
                    put("idToken", idToken)
                    put("refreshToken", refreshToken)
                    email?.let { put("email", it) }
                    if (google) who.name?.let { put("displayName", it) }
                }.toString())
            }
            "/v2/accounts:revokeToken" -> {
                appleRevoked += (o?.get("token") as? JsonPrimitive)?.contentOrNull.orEmpty()
                answer(c, 200, "{}")
            }
            "/v1/accounts:delete" -> {
                val token = (o?.get("idToken") as? JsonPrimitive)?.contentOrNull.orEmpty()
                firebaseDeleted += token
                firebaseIdTokens[token]?.let { uid -> firebaseUsers.remove(uid) } ?: firebaseUsers.clear()
                answer(c, 200, "{}")
            }
            "/v1/token" -> {
                val f = form(c)
                val refreshToken = f["refresh_token"].orEmpty()
                val uid = firebaseRefresh[refreshToken] ?: "fb-user-1"
                if (uid !in firebaseUsers && firebaseRefresh.containsKey(refreshToken) && firebaseDeleted.isNotEmpty()) {
                    return answer(c, 400, """{"error":{"code":400,"message":"USER_NOT_FOUND"}}""")
                }
                answer(c, 200, buildJsonObject {
                    put("id_token", firebaseToken(uid))
                    put("refresh_token", refreshToken.ifEmpty { "fb-refresh-${++n}" })
                    put("user_id", uid)
                    put("expires_in", "3600")
                }.toString())
            }
            else -> NetAnswer(404, c.url)
        }
    }

    private fun answer(c: FakeCall, code: Int, body: String) = NetAnswer(code, c.url, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())

    companion object {
        /** An unsigned JWT whose payload is [claims] (what the app reads of an ID token or Apple's identity token). */
        fun jwt(claims: JsonObject): String {
            fun part(s: String) = Base64.UrlSafe.encode(s.encodeToByteArray()).trimEnd('=')
            return part("""{"alg":"none"}""") + "." + part(claims.toString()) + ".sig"
        }
    }
}

/**
 * Google's sign-in page as a fake: [mode] says what the traveller does there. Accept gives what was asked; NoDrive
 * unticks Drive; Closed closes the sheet; Denied says no. [asked] keeps every page address it was opened on.
 */
class FakeAuthBrowser(private val cloud: FakeGoogleCloud, var mode: Mode = Mode.Accept) : AuthBrowser {
    enum class Mode { Accept, NoDrive, Closed, Denied, Broken }

    val asked = mutableListOf<String>()

    override suspend fun open(url: String, callbackScheme: String): String? {
        asked += url
        val page = WebUrl.of(url) ?: return null
        val state = page.queryParameter("state").orEmpty()
        val redirect = page.queryParameter("redirect_uri").orEmpty()
        check(redirect.startsWith("$callbackScheme:/")) { "The page goes back to another scheme: $redirect" }
        val scopes = page.queryParameter("scope").orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
        return when (mode) {
            Mode.Closed -> null
            Mode.Broken -> throw com.justdataplease.boringtravel.google.AuthBrowserException("The sheet couldn't open.")
            Mode.Denied -> "$redirect?state=$state&error=access_denied"
            Mode.Accept, Mode.NoDrive -> {
                val given = if (mode == Mode.NoDrive) scopes.filterNot { "drive" in it }.toSet() else scopes
                val code = cloud.issueCode(given)
                "$redirect?state=$state&code=$code&scope=" + UrlCodec.queryComponent(given.joinToString(" "))
            }
        }
    }
}

/** Apple's sheet as a fake: the first sign-in shares the name and email, later ones don't (Apple's rule). */
class FakeAppleSignIn(var closes: Boolean = false, var email: String = "apple-traveller@example.com", var name: String? = "Traveller") : AppleSignIn {
    var asked = 0
    val nonces = mutableListOf<String>()

    override suspend fun signIn(hashedNonce: String): AppleCredential? {
        asked++
        nonces += hashedNonce
        if (closes) return null
        val token = FakeGoogleCloud.jwt(buildJsonObject { put("email", email); put("sub", "apple-user-1"); put("nonce", hashedNonce) })
        return AppleCredential("apple-user-1", token, "apple-code-$asked", if (asked == 1) email else null, if (asked == 1) name else null)
    }
}

/**
 * The account on the fakes, for the tests and the pictures: Google and Firebase are [cloud], Google's page [browser],
 * Apple's sheet [apple], the Keychain [keys]. The client id and the Firebase ids are made-up test values.
 */
class FakeGoogle(
    val cloud: FakeGoogleCloud = FakeGoogleCloud(),
    val browser: FakeAuthBrowser = FakeAuthBrowser(cloud),
    val apple: FakeAppleSignIn = FakeAppleSignIn(),
    val keys: com.justdataplease.boringtravel.ai.own.MemoryKeyValues = com.justdataplease.boringtravel.ai.own.MemoryKeyValues(),
) {
    fun account(settings: AppSettings, files: MediaFiles, clientId: String = CLIENT, appleOn: Boolean = true): com.justdataplease.boringtravel.google.GoogleAccount =
        com.justdataplease.boringtravel.google.GoogleAccount(
            settings, cloud, keys, files, browser, apple, clientId = clientId, appleOn = appleOn,
            firebase = com.justdataplease.boringtravel.firebase.FirebaseSetup.Config("test-project", "test-app", "test-key"),
            bundleId = "com.justdataplease.boringtravel", retryWait = 0, wait = {},
        )

    companion object {
        /** A made-up iPhone OAuth client id (its scheme: com.googleusercontent.apps.test-client). */
        const val CLIENT = "test-client.apps.googleusercontent.com"
    }
}

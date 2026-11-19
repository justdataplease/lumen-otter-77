package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.firebase.FsRef
import com.justdataplease.boringtravel.firebase.FsTime
import com.justdataplease.boringtravel.firebase.FsValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * Cloud Firestore in memory, for the tests and the pictures (round 24): nothing here reaches the network. [FakeFirestore]
 * answers the REST API as Firestore does (documents read, structured queries with filters, orders, cursors and limits,
 * collection groups, counts, commits with server times and increments, transactions) and holds the writes to the owner's
 * rules as far as the app's community meets them (android-boringtravel/firebase/firestore.rules: only as yourself, a like
 * with its count by one and never on your own post, a username claimed once, follows with both halves and never across a
 * block, reports and blocks as yourself, likes read only by who gave them). Names and addresses are made up.
 */

/** The community's documents by path ("posts/abc"), each field a plain value (as [FsValues.decode] gives them). */
class FakeFirestore(private val project: String = "test-project") {
    val docs = LinkedHashMap<String, MutableMap<String, Any?>>()

    /** Every commit's writes, in order (what the app sent). */
    val commits = mutableListOf<List<JsonObject>>()

    /** Every query (its parent and structured query), in order. */
    val queries = mutableListOf<Pair<String, JsonObject>>()

    /** A refusal of one's own (a test's): the uid and a write; true refuses the commit. */
    var refuse: ((uid: String?, write: JsonObject) -> Boolean)? = null

    /** The server can't be reached (503 UNAVAILABLE) while true. */
    var unavailable = false

    /** Every query refused with this HTTP code and status while set (a failure that isn't the connection). */
    var queryError: Pair<Int, String>? = null

    /** The server's clock: each write's time a millisecond later. */
    var clock = 1_795_000_000_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val root get() = "projects/$project/databases/(default)/documents"

    // ------------------------------------------------------------------ seeding

    fun time(ms: Long) = FsTime(FsValues.formatTime(ms))

    fun profile(uid: String, username: String, name: String = "", bio: String = "", avatar: String? = null, at: Long = clock) {
        docs["profiles/$uid"] = mutableMapOf("username" to username, "name" to name, "bio" to bio, "avatar" to avatar, "createdAt" to time(at), "updatedAt" to time(at))
        docs["usernames/$username"] = mutableMapOf("uid" to uid)
    }

    /** A post as the app writes one ([fields] over the defaults). */
    fun post(id: String, uid: String, username: String, title: String, at: Long, vararg fields: Pair<String, Any?>) {
        docs["posts/$id"] = mutableMapOf<String, Any?>(
            "uid" to uid, "username" to username, "kind" to "trip", "title" to title, "caption" to "", "countries" to emptyList<String>(),
            "days" to 0L, "km" to 0L, "startMonth" to null, "month" to FsValues.formatTime(at).take(7), "coverFileId" to null,
            "photoFileIds" to emptyList<String>(), "clipFileId" to null, "folderId" to null, "hasTrip" to false, "likes" to 0L,
            "createdAt" to time(at), "updatedAt" to time(at),
        ).apply { fields.forEach { (k, v) -> put(k, if (v is Int) v.toLong() else v) } }
    }

    fun trip(postId: String, tripJson: String) {
        docs["posts/$postId/content/trip"] = mutableMapOf("trip" to tripJson)
    }

    fun like(postId: String, uid: String) {
        docs["posts/$postId/likes/$uid"] = mutableMapOf("uid" to uid, "at" to time(clock))
        docs["posts/$postId"]?.let { it["likes"] = ((it["likes"] as? Long) ?: 0L) + 1 }
    }

    fun follow(from: String, fromName: String, to: String, toName: String, at: Long = clock) {
        docs["users/$from/following/$to"] = mutableMapOf("username" to toName, "at" to time(at))
        docs["users/$to/followers/$from"] = mutableMapOf("username" to fromName, "at" to time(at))
    }

    fun block(uid: String, other: String, username: String) {
        docs["users/$uid/blocked/$other"] = mutableMapOf("username" to username, "at" to time(clock))
    }

    /** The reports made, as stored. */
    val reports: List<Map<String, Any?>> get() = docs.filterKeys { it.startsWith("reports/") }.values.toList()

    // ------------------------------------------------------------------ the REST API

    /** One request to Firestore: [uid] the signed-in account (null: a token Firestore doesn't take, or none: [token] false). */
    fun answer(c: FakeCall, uid: String?, token: Boolean): NetAnswer {
        if (unavailable) return error(c, 503, "UNAVAILABLE", "The service is currently unavailable.")
        if (token && uid == null) return error(c, 401, "UNAUTHENTICATED", "Invalid credentials")
        val base = "/v1/$root"
        if (!c.path.startsWith(base)) return error(c, 404, "NOT_FOUND", "No such database")
        val rest = c.path.removePrefix(base).removePrefix("/")
        val (path, verb) = rest.split(':').let { it[0] to it.getOrNull(1) }
        val body = runCatching { json.parseToJsonElement(c.text).jsonObject }.getOrNull()
        return when {
            c.method == "GET" && verb == null -> read(c, uid, path)
            verb == "runQuery" -> query(c, uid, path, body?.get("structuredQuery") as? JsonObject ?: return error(c, 400, "INVALID_ARGUMENT", "No query"))
            verb == "runAggregationQuery" -> count(c, uid, path, body)
            verb == "commit" -> commit(c, uid, body)
            verb == "beginTransaction" -> ok(c, buildJsonObject { put("transaction", "dHgtMQ==") })
            verb == "rollback" -> ok(c, buildJsonObject { })
            else -> error(c, 400, "INVALID_ARGUMENT", "The fake has no ${c.method} $rest")
        }
    }

    private fun read(c: FakeCall, uid: String?, path: String): NetAnswer {
        if (uid == null) return error(c, 403, "PERMISSION_DENIED", "Missing or insufficient permissions.")
        val parts = path.split('/')
        // A like is read by who gave it; a blocked list by its owner.
        if (parts.size == 4 && parts[2] == "likes" && parts[3] != uid) return error(c, 403, "PERMISSION_DENIED", "Missing or insufficient permissions.")
        if (parts.size == 4 && parts[0] == "users" && parts[2] == "blocked" && parts[1] != uid) return error(c, 403, "PERMISSION_DENIED", "Missing or insufficient permissions.")
        val doc = docs[path] ?: return error(c, 404, "NOT_FOUND", "Document not found: $path")
        return ok(c, docJson(path, doc))
    }

    private fun docJson(path: String, fields: Map<String, Any?>): JsonObject = buildJsonObject {
        put("name", "$root/$path")
        put("fields", JsonObject(fields.mapValues { FsValues.encode(it.value) }))
    }

    /** The documents [q] finds under [parent], in its order, after its cursor, up to its limit. */
    private fun run(parent: String, q: JsonObject): List<Pair<String, MutableMap<String, Any?>>> {
        val from = (q["from"] as JsonArray).first().jsonObject
        val collection = (from["collectionId"] as JsonPrimitive).content
        val all = (from["allDescendants"] as? JsonPrimitive)?.booleanOrNull == true
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        var found = docs.entries.filter { (path, _) ->
            if (!path.startsWith(prefix)) return@filter false
            val parts = path.removePrefix(prefix).split('/')
            if (all) parts.size >= 2 && parts.size % 2 == 0 && parts[parts.size - 2] == collection else parts.size == 2 && parts[0] == collection
        }.map { it.key to it.value }
        (q["where"] as? JsonObject)?.let { w -> found = found.filter { (_, d) -> matches(w, d) } }
        val orders = (q["orderBy"] as? JsonArray).orEmpty().map { o ->
            val field = ((o.jsonObject["field"] as JsonObject)["fieldPath"] as JsonPrimitive).content
            field to ((o.jsonObject["direction"] as? JsonPrimitive)?.content == "DESCENDING")
        }
        // Firestore leaves out documents without an ordered field.
        found = found.filter { (_, d) -> orders.all { (f, _) -> f == "__name__" || d.containsKey(f) } }
        fun key(p: Pair<String, Map<String, Any?>>, field: String): Any? = if (field == "__name__") "$root/${p.first}" else p.second[field]
        val comparator = Comparator<Pair<String, MutableMap<String, Any?>>> { a, b ->
            for ((f, desc) in orders) {
                val r = compareValues(a, b, f, ::key)
                if (r != 0) return@Comparator if (desc) -r else r
            }
            a.first.compareTo(b.first)
        }
        found = found.sortedWith(comparator)
        (q["startAt"] as? JsonObject)?.let { s ->
            val values = (s["values"] as JsonArray).map { v -> FsValues.decode(v)?.let { if (it is FsRef) it.name else it } }
            val before = (s["before"] as? JsonPrimitive)?.booleanOrNull == true
            found = found.filter { p ->
                var r = 0
                for ((i, o) in orders.withIndex()) {
                    val v = key(p, o.first)
                    val cmp = compare(v, values.getOrNull(i))
                    if (cmp != 0) {
                        r = if (o.second) -cmp else cmp
                        break
                    }
                }
                if (before) r >= 0 else r > 0
            }
        }
        (q["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { found = found.take(it) }
        return found
    }

    private fun compareValues(a: Pair<String, MutableMap<String, Any?>>, b: Pair<String, MutableMap<String, Any?>>, f: String, key: (Pair<String, Map<String, Any?>>, String) -> Any?) =
        compare(key(a, f), key(b, f))

    private fun compare(a: Any?, b: Any?): Int = when {
        a == null && b == null -> 0
        a == null -> -1
        b == null -> 1
        a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
        a is FsTime && b is FsTime -> a.ms.compareTo(b.ms)
        else -> a.toString().compareTo(b.toString())
    }

    private fun matches(w: JsonObject, d: Map<String, Any?>): Boolean {
        (w["compositeFilter"] as? JsonObject)?.let { cf -> return (cf["filters"] as JsonArray).all { matches(it.jsonObject, d) } }
        val f = w["fieldFilter"] as? JsonObject ?: return true
        val field = ((f["field"] as JsonObject)["fieldPath"] as JsonPrimitive).content
        val value = FsValues.decode(f["value"])
        return when ((f["op"] as JsonPrimitive).content) {
            "EQUAL" -> compare(d[field], value) == 0 && d.containsKey(field)
            "IN" -> (value as List<*>).any { compare(d[field], it) == 0 }
            else -> false
        }
    }

    private fun query(c: FakeCall, uid: String?, parentName: String, q: JsonObject): NetAnswer {
        if (uid == null) return error(c, 403, "PERMISSION_DENIED", "Missing or insufficient permissions.")
        queryError?.let { (code, status) -> return error(c, code, status, "Refused") }
        val parent = parentName.removePrefix("/")
        queries += parent to q
        val from = (q["from"] as JsonArray).first().jsonObject
        val collection = (from["collectionId"] as JsonPrimitive).content
        val all = (from["allDescendants"] as? JsonPrimitive)?.booleanOrNull == true
        // The rules: a post's likes listed only by its poster; across posts only one's own; a blocked list only by its owner.
        if (collection == "likes") {
            if (all) {
                val w = q["where"]?.jsonObject?.get("fieldFilter")?.jsonObject
                val asked = (w?.get("value")?.let { FsValues.decode(it) } as? String)
                if (asked != uid) return error(c, 403, "PERMISSION_DENIED", "Missing or insufficient permissions.")
            } else if (docs[parent]?.get("uid") != uid) {
                return error(c, 403, "PERMISSION_DENIED", "Missing or insufficient permissions.")
            }
        }
        if (collection == "blocked" && parent != "users/$uid") return error(c, 403, "PERMISSION_DENIED", "Missing or insufficient permissions.")
        if (collection == "profiles") return error(c, 403, "PERMISSION_DENIED", "Missing or insufficient permissions.")
        val found = run(parent, q)
        val answer = if (found.isEmpty()) {
            JsonArray(listOf(buildJsonObject { put("readTime", FsValues.formatTime(clock)) }))
        } else {
            JsonArray(found.map { (path, d) -> buildJsonObject { put("document", docJson(path, d)); put("readTime", FsValues.formatTime(clock)) } })
        }
        return NetAnswer(200, c.url, mapOf("Content-Type" to "application/json"), answer.toString().encodeToByteArray())
    }

    private fun count(c: FakeCall, uid: String?, parentName: String, body: JsonObject?): NetAnswer {
        if (uid == null) return error(c, 403, "PERMISSION_DENIED", "Missing or insufficient permissions.")
        val q = (body?.get("structuredAggregationQuery") as JsonObject)["structuredQuery"] as JsonObject
        val n = run(parentName.removePrefix("/"), q).size
        val answer = buildJsonArray {
            add(buildJsonObject {
                put("result", buildJsonObject { put("aggregateFields", buildJsonObject { put("n", buildJsonObject { put("integerValue", n.toString()) }) }) })
                put("readTime", FsValues.formatTime(clock))
            })
        }
        return NetAnswer(200, c.url, mapOf("Content-Type" to "application/json"), answer.toString().encodeToByteArray())
    }

    // ------------------------------------------------------------------ writes

    private fun commit(c: FakeCall, uid: String?, body: JsonObject?): NetAnswer {
        val writes = (body?.get("writes") as? JsonArray).orEmpty().map { it.jsonObject }
        if (uid == null && writes.isNotEmpty()) return error(c, 403, "PERMISSION_DENIED", "Missing or insufficient permissions.")
        val now = time(++clock)
        val after = LinkedHashMap<String, MutableMap<String, Any?>>()
        docs.forEach { (k, v) -> after[k] = LinkedHashMap(v) }
        val touched = mutableSetOf<String>()
        for (w in writes) {
            (w["delete"] as? JsonPrimitive)?.contentOrNull?.let { name ->
                val path = name.removePrefix("$root/")
                after.remove(path)
                touched += path
            }
            val update = w["update"] as? JsonObject ?: continue
            val path = (update["name"] as JsonPrimitive).content.removePrefix("$root/")
            val fields = (update["fields"] as? JsonObject).orEmpty().mapValues { FsValues.decode(it.value) }
            val mustExist = ((w["currentDocument"] as? JsonObject)?.get("exists") as? JsonPrimitive)?.booleanOrNull == true
            if (mustExist && path !in after) return error(c, 404, "NOT_FOUND", "No document to update: $path")
            val mask = (w["updateMask"] as? JsonObject)?.let { m -> ((m["fieldPaths"] as? JsonArray).orEmpty()).map { (it as JsonPrimitive).content } }
            val doc = if (mask == null) LinkedHashMap() else (after[path] ?: LinkedHashMap())
            if (mask == null) doc.putAll(fields) else mask.forEach { k -> if (k in fields) doc[k] = fields[k] else doc.remove(k) }
            (w["updateTransforms"] as? JsonArray).orEmpty().forEach { t ->
                val o = t.jsonObject
                val field = (o["fieldPath"] as JsonPrimitive).content
                when {
                    o["setToServerValue"] != null -> doc[field] = now
                    o["increment"] != null -> doc[field] = ((doc[field] as? Long) ?: 0L) + ((FsValues.decode(o["increment"]) as? Long) ?: 0L)
                }
            }
            after[path] = doc
            touched += path
        }
        for (w in writes) if (refuse?.invoke(uid, w) == true) return denied(c)
        if (uid != null) rules(uid, touched, after)?.let { return denied(c, it) }
        commits += writes
        docs.clear()
        docs.putAll(after)
        return ok(c, buildJsonObject { put("commitTime", now.text) })
    }

    /** The owner's rules for each document a commit touches; why it is refused, or null. */
    private fun rules(uid: String, touched: Set<String>, after: Map<String, Map<String, Any?>>): String? {
        for (path in touched) {
            val p = path.split('/')
            val before = docs[path]
            val now = after[path]
            when {
                p[0] == "profiles" && p.size == 2 -> if (p[1] != uid) return "Only your own profile"
                p[0] == "usernames" && p.size == 2 -> {
                    if (now != null && before != null) return "A username is claimed once"
                    if (now != null && now["uid"] != uid) return "Only as yourself"
                    if (now != null && after["profiles/$uid"]?.get("username") != p[1]) return "Only with the profile naming it"
                    if (now == null && before?.get("uid") != uid) return "Only its holder lets it go"
                }
                p[0] == "posts" && p.size == 2 -> {
                    val owner = (before ?: now)?.get("uid")
                    if (before == null && now != null) {
                        if (now["uid"] != uid || now["likes"] != 0L) return "Posts only as yourself, with no likes"
                    } else if (now == null) {
                        if (owner != uid) return "Only its poster deletes a post"
                    } else if (owner != uid) {
                        // Someone else's like: only the count moves by one, with their like appearing or going.
                        val changed = (before!!.keys + now.keys).filter { before[it] != now[it] }
                        if (changed.any { it != "likes" }) return "Only the likes change"
                        val delta = ((now["likes"] as Long) - (before["likes"] as Long))
                        val likeBefore = docs.containsKey("$path/likes/$uid")
                        val likeAfter = after.containsKey("$path/likes/$uid")
                        if (!((delta == 1L && !likeBefore && likeAfter) || (delta == -1L && likeBefore && !likeAfter))) return "A like with its count"
                    }
                }
                p.size == 4 && p[0] == "posts" && p[2] == "likes" -> {
                    if (after.containsKey("posts/${p[1]}")) {
                        if (p[3] != uid) return "Only your own like"
                        if (after["posts/${p[1]}"]?.get("uid") == uid) return "Nobody likes their own post"
                        val delta = ((after["posts/${p[1]}"]?.get("likes") as? Long) ?: 0L) - ((docs["posts/${p[1]}"]?.get("likes") as? Long) ?: 0L)
                        if (now != null && (before != null || delta != 1L)) return "One like each, with the count"
                        if (now == null && before != null && delta != -1L) return "An unlike takes one off"
                    }
                }
                p[0] == "reports" -> if (now == null || now["uid"] != uid) return "Reports only as yourself"
                p.size == 4 && p[0] == "users" && p[2] == "blocked" -> if (p[1] != uid || p[3] == uid) return "Only your own blocks"
                p.size == 4 && p[0] == "users" && (p[2] == "following" || p[2] == "followers") -> {
                    val (follower, followed) = if (p[2] == "following") p[1] to p[3] else p[3] to p[1]
                    if (now == null) {
                        if (uid != p[1] && uid != p[3]) return "Either side deletes a follow"
                    } else {
                        if (follower != uid || follower == followed) return "Follows only as yourself"
                        if (docs.containsKey("users/$follower/blocked/$followed") || docs.containsKey("users/$followed/blocked/$follower")) return "No follow across a block"
                        val named = if (p[2] == "following") followed else follower
                        if (now["username"] != docs["profiles/$named"]?.get("username")) return "The username the profile has"
                        val other = if (p[2] == "following") "users/$followed/followers/$follower" else "users/$follower/following/$followed"
                        if (!after.containsKey(other)) return "Both halves together"
                    }
                }
            }
        }
        return null
    }

    private fun ok(c: FakeCall, body: JsonObject) = NetAnswer(200, c.url, mapOf("Content-Type" to "application/json"), body.toString().encodeToByteArray())

    private fun denied(c: FakeCall, why: String = "Missing or insufficient permissions.") = error(c, 403, "PERMISSION_DENIED", why)

    private fun error(c: FakeCall, code: Int, status: String, message: String) = NetAnswer(
        code, c.url, mapOf("Content-Type" to "application/json"),
        buildJsonObject { put("error", buildJsonObject { put("code", code); put("message", message); put("status", status) }) }.toString().encodeToByteArray(),
    )

    @Suppress("unused")
    private val nothing = JsonNull
}

/** Made-up community people for the tests and the pictures (handles only, no person's name). */
object CommunityPeople {
    const val ME = "fb-google-sub-1"
    const val ME_NAME = "coastwalker"
    const val OTHER = "u-trail"
    const val OTHER_NAME = "trailnotes"
    const val THIRD = "u-harbour"
    const val THIRD_NAME = "harbour.days"
}

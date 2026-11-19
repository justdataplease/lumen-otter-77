package com.justdataplease.boringtravel.firebase

import com.justdataplease.boringtravel.ai.AppCheckTokens
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetCall
import com.justdataplease.boringtravel.ios.UrlCodec
import com.justdataplease.boringtravel.ios.call
import com.justdataplease.boringtravel.time.CivilDay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * Cloud Firestore over its REST API (round 24), where the Android app uses the Firebase SDK: documents read one by one,
 * structured queries with their filters, orders, cursors and limits, Firestore's count, and writes committed together
 * (a batch), with server times and increments as field transforms, or in a transaction. Requests carry the signed-in
 * account's Firebase ID token, so the owner's security rules (android-boringtravel/firebase/firestore.rules) see the same
 * request.auth as for the Android app, and the App Check token when the phone has one. Plain Kotlin over the app's Net;
 * the tests run it against an in-memory Firestore (desktopMain's FakeFirestore).
 */

/** A timestamp as Firestore keeps it: [text] as the server wrote it (RFC 3339 in UTC), sent back unchanged when kept. */
data class FsTime(val text: String) {
    /** Epoch milliseconds (0 when it can't be read). */
    val ms: Long get() = FsValues.parseTime(text) ?: 0L
}

/** A field set to the server's time when the write is applied (the SDK's FieldValue.serverTimestamp()). */
data object ServerTime

/** A number field moved by [by] on the server (the SDK's FieldValue.increment). */
data class Increment(val by: Long)

/** A reference to another document, by its full name. */
data class FsRef(val name: String)

/**
 * A document as read: its full [name] ("projects/…/documents/posts/abc"), its fields as plain values (strings, Longs,
 * Doubles, Booleans, null, [FsTime], lists and maps), and the fields as the server wrote them ([raw]: query cursors).
 */
class FsDoc(val name: String, val fields: Map<String, Any?>, val raw: JsonObject) {
    /** The document's own id (the last part of its name). */
    val id: String get() = name.substringAfterLast('/')

    /** The path under the database's documents ("posts/abc/likes/u1"). */
    val path: String get() = name.substringAfter("/documents/")

    /** Its parent document's path ("posts/abc" for "posts/abc/likes/u1"), or null at the top. */
    val parentPath: String? get() = path.split('/').let { if (it.size >= 4) it.dropLast(2).joinToString("/") else null }

    fun string(field: String): String? = fields[field] as? String

    fun long(field: String): Long? = when (val v = fields[field]) {
        is Long -> v
        is Double -> v.toLong()
        else -> null
    }

    fun bool(field: String): Boolean? = fields[field] as? Boolean

    fun time(field: String): FsTime? = fields[field] as? FsTime

    fun list(field: String): List<Any?>? = fields[field] as? List<*>

    /** The document as Firestore's JSON (what a cache keeps). */
    fun json(): JsonObject = buildJsonObject {
        put("name", name)
        put("fields", raw)
    }
}

/** Firestore's typed JSON values, both ways (the REST API's Value). */
object FsValues {
    private val TIME = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$""")

    fun encode(v: Any?): JsonObject = when (v) {
        null -> JsonObject(mapOf("nullValue" to JsonNull))
        is String -> obj("stringValue", JsonPrimitive(v))
        is Boolean -> obj("booleanValue", JsonPrimitive(v))
        is Int -> obj("integerValue", JsonPrimitive(v.toString()))
        is Long -> obj("integerValue", JsonPrimitive(v.toString()))
        is Float -> obj("doubleValue", JsonPrimitive(v.toDouble()))
        is Double -> obj("doubleValue", JsonPrimitive(v))
        is FsTime -> obj("timestampValue", JsonPrimitive(v.text))
        is FsRef -> obj("referenceValue", JsonPrimitive(v.name))
        is List<*> -> obj("arrayValue", if (v.isEmpty()) JsonObject(emptyMap()) else JsonObject(mapOf("values" to JsonArray(v.map { encode(it) }))))
        is Map<*, *> -> obj("mapValue", JsonObject(mapOf("fields" to JsonObject(v.entries.associate { (k, x) -> k.toString() to encode(x) }))))
        else -> throw IllegalArgumentException("Not a Firestore value: ${v::class.simpleName}")
    }

    fun decode(e: JsonElement?): Any? {
        val o = e as? JsonObject ?: return null
        o["stringValue"]?.let { return (it as? JsonPrimitive)?.contentOrNull }
        o["integerValue"]?.let { return (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
        o["doubleValue"]?.let { return (it as? JsonPrimitive)?.doubleOrNull ?: (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
        o["booleanValue"]?.let { return (it as? JsonPrimitive)?.booleanOrNull }
        o["timestampValue"]?.let { return (it as? JsonPrimitive)?.contentOrNull?.let(::FsTime) }
        o["referenceValue"]?.let { return (it as? JsonPrimitive)?.contentOrNull?.let(::FsRef) }
        o["arrayValue"]?.let { a -> return ((a as? JsonObject)?.get("values") as? JsonArray).orEmpty().map { decode(it) } }
        o["mapValue"]?.let { m -> return ((m as? JsonObject)?.get("fields") as? JsonObject).orEmpty().mapValues { decode(it.value) } }
        return null
    }

    /** A document from the REST API's Document JSON; null when it has no name. */
    fun doc(o: JsonObject?): FsDoc? {
        val name = (o?.get("name") as? JsonPrimitive)?.contentOrNull ?: return null
        val raw = (o["fields"] as? JsonObject) ?: JsonObject(emptyMap())
        return FsDoc(name, raw.mapValues { decode(it.value) }, raw)
    }

    /** "2026-11-19T10:00:00.123456Z" as epoch milliseconds; null for another shape. */
    fun parseTime(text: String): Long? {
        val m = TIME.matchEntire(text) ?: return null
        val g = m.groupValues
        if (!CivilDay.isValid(g[1].toInt(), g[2].toInt(), g[3].toInt())) return null
        val day = CivilDay(g[1].toInt(), g[2].toInt(), g[3].toInt()).epochDay
        val frac = g[7].padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
        return day * 86_400_000L + g[4].toLong() * 3_600_000L + g[5].toLong() * 60_000L + g[6].toLong() * 1_000L + frac
    }

    /** Epoch milliseconds as Firestore writes a time: "2026-11-19T10:00:00.123000Z". */
    fun formatTime(ms: Long): String {
        val day = CivilDay.ofEpochDay(ms.floorDiv(86_400_000L))
        val inDay = ms.mod(86_400_000L)
        fun two(n: Long) = n.toString().padStart(2, '0')
        return day.iso() + "T" + two(inDay / 3_600_000L) + ":" + two(inDay / 60_000L % 60) + ":" + two(inDay / 1_000L % 60) + "." +
            (inDay % 1_000L).toString().padStart(3, '0') + "000Z"
    }

    private fun obj(key: String, value: JsonElement) = JsonObject(mapOf(key to value))
}

/** The writes of a commit, shaped as the SDK shapes them. */
object FsWrites {
    /** The whole document [name] as [fields] (made or replaced: the SDK's set); [ServerTime] and [Increment] as transforms. */
    fun set(name: String, fields: Map<String, Any?>): JsonObject = buildJsonObject {
        putJsonObject("update") {
            put("name", name)
            put("fields", JsonObject(fields.filterValues { !isTransform(it) }.mapValues { FsValues.encode(it.value) }))
        }
        transforms(fields)?.let { put("updateTransforms", it) }
    }

    /** [fields] of the existing document [name] (the SDK's update: it must be there); the rest stays. */
    fun update(name: String, fields: Map<String, Any?>): JsonObject = buildJsonObject {
        val plain = fields.filterValues { !isTransform(it) }
        putJsonObject("update") {
            put("name", name)
            put("fields", JsonObject(plain.mapValues { FsValues.encode(it.value) }))
        }
        putJsonObject("updateMask") { putJsonArray("fieldPaths") { plain.keys.forEach { add(JsonPrimitive(it)) } } }
        transforms(fields)?.let { put("updateTransforms", it) }
        putJsonObject("currentDocument") { put("exists", true) }
    }

    fun delete(name: String): JsonObject = buildJsonObject { put("delete", name) }

    private fun isTransform(v: Any?) = v is ServerTime || v is Increment

    private fun transforms(fields: Map<String, Any?>): JsonArray? {
        val t = fields.filterValues { isTransform(it) }
        if (t.isEmpty()) return null
        return buildJsonArray {
            t.forEach { (k, v) ->
                add(buildJsonObject {
                    put("fieldPath", k)
                    when (v) {
                        is ServerTime -> put("setToServerValue", "REQUEST_TIME")
                        is Increment -> put("increment", FsValues.encode(v.by))
                    }
                })
            }
        }
    }
}

/**
 * A structured query on [collection] (every collection of that id under the parent when [allDescendants]: a collection
 * group): [filters] all true, in [orders], at most [limit], after [after]. The SDK orders by the document's name last
 * (in the last order's direction) so a page boundary between equal values is exact; so does [json].
 */
data class FsQuery(
    val collection: String,
    val filters: List<Filter> = emptyList(),
    val orders: List<Order> = emptyList(),
    val limit: Int? = null,
    val after: FsCursor? = null,
    val allDescendants: Boolean = false,
) {
    /** [field] equal to [value] ("EQUAL"), or one of the list [value] ("IN"). */
    data class Filter(val field: String, val op: String, val value: Any?)

    data class Order(val field: String, val descending: Boolean = false)

    /** The orders the server applies: [orders], then the name in the last one's direction. */
    val allOrders: List<Order>
        get() = if (orders.isEmpty() || orders.last().field == NAME) orders else orders + Order(NAME, orders.last().descending)

    /** The cursor just after [doc] in this query's order (the SDK's startAfter(snapshot)). */
    fun cursorAfter(doc: FsDoc): FsCursor =
        FsCursor(allOrders.map { o -> if (o.field == NAME) FsValues.encode(FsRef(doc.name)) else (doc.raw[o.field] as? JsonObject) ?: FsValues.encode(null) })

    fun json(): JsonObject = buildJsonObject {
        putJsonObject("structuredQuery") {
            putJsonArray("from") {
                add(buildJsonObject {
                    put("collectionId", collection)
                    if (allDescendants) put("allDescendants", true)
                })
            }
            val wheres = filters.map { f ->
                buildJsonObject {
                    putJsonObject("fieldFilter") {
                        putJsonObject("field") { put("fieldPath", f.field) }
                        put("op", f.op)
                        put("value", FsValues.encode(f.value))
                    }
                }
            }
            when (wheres.size) {
                0 -> Unit
                1 -> put("where", wheres.single())
                else -> putJsonObject("where") {
                    putJsonObject("compositeFilter") {
                        put("op", "AND")
                        put("filters", JsonArray(wheres))
                    }
                }
            }
            if (allOrders.isNotEmpty()) putJsonArray("orderBy") {
                allOrders.forEach { o ->
                    add(buildJsonObject {
                        putJsonObject("field") { put("fieldPath", o.field) }
                        put("direction", if (o.descending) "DESCENDING" else "ASCENDING")
                    })
                }
            }
            after?.let { c ->
                putJsonObject("startAt") {
                    put("values", JsonArray(c.values))
                    put("before", false)
                }
            }
            limit?.let { put("limit", it) }
        }
    }

    companion object {
        const val NAME = "__name__"
    }
}

/** Where a page ends: the values of its last document in the query's order. */
data class FsCursor(val values: List<JsonObject>)

/** Firestore refused or failed: its HTTP [code] and gRPC [status] ("PERMISSION_DENIED", "UNAVAILABLE", "ABORTED" …). */
class FirestoreException(val code: Int, val status: String, message: String) : IOException("Firestore: $status ($code) $message")

/**
 * The REST API for project [projectId]. [token] gives the signed-in account's Firebase ID token (fresh = a new one, after
 * the server said the last one is stale; null: nobody is signed in); [appCheck] the App Check token when the phone has
 * one. Network failures are [com.justdataplease.boringtravel.ios.NetException] (no connection, too slow), refusals
 * [FirestoreException].
 */
class FirestoreRest(
    private val net: Net,
    val projectId: String,
    private val token: suspend (fresh: Boolean) -> String?,
    private val appCheck: AppCheckTokens = AppCheckTokens { null },
    private val host: String = "https://firestore.googleapis.com",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** "projects/<id>/databases/(default)/documents". */
    val root: String get() = "projects/$projectId/databases/(default)/documents"

    /** The full name of the document at [path] ("posts/abc"). */
    fun name(path: String): String = "$root/$path"

    /** The document at [path], or null when there is none. */
    suspend fun get(path: String, transaction: String? = null): FsDoc? {
        val url = "$host/v1/${name(path)}" + (transaction?.let { "?transaction=" + UrlCodec.queryComponent(it) } ?: "")
        val answer = send("GET", url, null) { code, status -> code == 404 || status == "NOT_FOUND" } ?: return null
        return FsValues.doc(answer as? JsonObject)
    }

    /** The documents [query] finds under [parent] (a document's path; null: the database's top). */
    suspend fun query(parent: String?, query: FsQuery): List<FsDoc> {
        val at = if (parent == null) root else name(parent)
        val answer = send("POST", "$host/v1/$at:runQuery", query.json()) ?: return emptyList()
        return (answer as? JsonArray).orEmpty().mapNotNull { FsValues.doc((it as? JsonObject)?.get("document") as? JsonObject) }
    }

    /** How many documents [collection] under [parent] holds, counted by the server. */
    suspend fun count(parent: String?, collection: String): Long {
        val at = if (parent == null) root else name(parent)
        val body = buildJsonObject {
            putJsonObject("structuredAggregationQuery") {
                put("structuredQuery", FsQuery(collection).json()["structuredQuery"]!!)
                putJsonArray("aggregations") { add(buildJsonObject { put("alias", "n"); putJsonObject("count") {} }) }
            }
        }
        val answer = send("POST", "$host/v1/$at:runAggregationQuery", body) as? JsonArray
        val n = answer.orEmpty().firstNotNullOfOrNull { e ->
            (((e as? JsonObject)?.get("result") as? JsonObject)?.get("aggregateFields") as? JsonObject)?.get("n")
        }
        return (FsValues.decode(n) as? Long) ?: 0L
    }

    /** Applies [writes] together, or none of them (in [transaction] when given). */
    suspend fun commit(writes: List<JsonObject>, transaction: String? = null) {
        if (writes.isEmpty() && transaction == null) return
        val body = buildJsonObject {
            put("writes", JsonArray(writes))
            transaction?.let { put("transaction", it) }
        }
        send("POST", "$host/v1/$root:commit", body)
    }

    /** A read-write transaction's id. */
    suspend fun begin(): String {
        val answer = send("POST", "$host/v1/$root:beginTransaction", JsonObject(emptyMap())) as? JsonObject
        return (answer?.get("transaction") as? JsonPrimitive)?.contentOrNull ?: throw FirestoreException(500, "INTERNAL", "No transaction")
    }

    /** Lets go of [transaction] without writing (best effort). */
    suspend fun rollback(transaction: String) {
        runCatching { send("POST", "$host/v1/$root:rollback", buildJsonObject { put("transaction", transaction) }) }
    }

    /**
     * One call: its answer as JSON, null when [none] says the refusal means "nothing there". A stale ID token is renewed
     * once (the SDK refreshes it the same way).
     */
    private suspend fun send(method: String, url: String, body: JsonObject?, none: (Int, String) -> Boolean = { _, _ -> false }): JsonElement? {
        var fresh = false
        while (true) {
            val headers = buildMap {
                token(fresh)?.let { put("Authorization", "Bearer $it") }
                appCheck.token()?.takeIf { it.isNotEmpty() }?.let { put("X-Firebase-AppCheck", it) }
            }
            val answer = net.call(
                NetCall(method, url, headers, body?.toString()?.encodeToByteArray(), body?.let { "application/json; charset=utf-8" }, maxBytes = MAX_BYTES, timeoutMs = 30_000),
            )
            val parsed = runCatching { json.parseToJsonElement(answer.text()) }.getOrNull()
            if (answer.ok) return parsed
            val error = (parsed as? JsonObject)?.get("error") as? JsonObject
                ?: ((parsed as? JsonArray)?.firstOrNull() as? JsonObject)?.get("error") as? JsonObject
            val status = (error?.get("status") as? JsonPrimitive)?.contentOrNull ?: statusOf(answer.code)
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (none(answer.code, status)) return null
            if ((answer.code == 401 || status == "UNAUTHENTICATED") && !fresh && token(false) != null) {
                fresh = true
                continue
            }
            throw FirestoreException(answer.code, status, message)
        }
    }

    private fun statusOf(code: Int): String = when (code) {
        400 -> "INVALID_ARGUMENT"
        401 -> "UNAUTHENTICATED"
        403 -> "PERMISSION_DENIED"
        404 -> "NOT_FOUND"
        409 -> "ABORTED"
        412 -> "FAILED_PRECONDITION"
        429 -> "RESOURCE_EXHAUSTED"
        503 -> "UNAVAILABLE"
        504 -> "DEADLINE_EXCEEDED"
        else -> "UNKNOWN"
    }

    private companion object {
        /** A page of posts, a trip of 300 KB with its JSON around it: far under this. */
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}

package com.justdataplease.boringtravel.community

import com.justdataplease.boringtravel.firebase.FsDoc
import com.justdataplease.boringtravel.firebase.FsValues
import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.Sha256
import com.justdataplease.boringtravel.ios.SettingsFile
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * What the Firebase SDK keeps on the phone for the Android app, kept here by hand (round 24): the community's documents
 * and the pages of the lists last read ([CommunityCache], in the caches folder: the phone may empty it), so a phone
 * without a connection shows what it saw ("Offline · showing what was saved"); and the writes made without a connection
 * ([CommunityOutbox], in the app's settings: a like, a report, a block or unblock, a follow), sent in order once it is
 * back, as the SDK sends its pending writes. Neither ever goes into a backup.
 */

/**
 * The documents last read and the lists' pages (each the paths of its documents, in order), in [files] under
 * "community/": at most [maxBytes] in all, the least recently used going first.
 */
class CommunityCache(private val files: MediaFiles, private val maxBytes: Long = MAX_BYTES, private val now: () -> Long = ::nowMs) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** The cache's index: file key → (bytes, last used), read once, written after each change. */
    private var index: MutableMap<String, Pair<Long, Long>>? = null

    suspend fun putDoc(doc: FsDoc) = lock.withLock { write("d:" + doc.path, doc.json().toString()) }

    suspend fun doc(path: String): FsDoc? = lock.withLock { read("d:$path")?.let { FsValues.doc(runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()) } }

    /** Forgets the document at [path] (deleted on the server). */
    suspend fun dropDoc(path: String) = lock.withLock { remove("d:$path") }

    /** A list's page under [key]: its documents are kept one by one, the page as their paths. */
    suspend fun putPage(key: String, docs: List<FsDoc>) = lock.withLock {
        docs.forEach { write("d:" + it.path, it.json().toString()) }
        write("q:$key", JsonArray(docs.map { kotlinx.serialization.json.JsonPrimitive(it.path) }).toString())
    }

    /** The page kept under [key]: its documents still kept, in order; null when the page was never kept. */
    suspend fun page(key: String): List<FsDoc>? = lock.withLock {
        val paths = read("q:$key")?.let { runCatching { json.parseToJsonElement(it) as JsonArray }.getOrNull() } ?: return@withLock null
        paths.mapNotNull { p ->
            val path = (p as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            read("d:$path")?.let { FsValues.doc(runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()) }
        }
    }

    /** Everything goes (another account signed in, or out). */
    suspend fun clear() = lock.withLock {
        index().keys.toList().forEach { files.delete("$FOLDER/$it") }
        index().clear()
        files.delete(INDEX)
    }

    private fun fileKey(key: String) = Sha256.hex(key.encodeToByteArray()).take(40)

    private fun index(): MutableMap<String, Pair<Long, Long>> = index ?: LinkedHashMap<String, Pair<Long, Long>>().also { map ->
        files.read(INDEX)?.decodeToString()?.lineSequence()?.forEach { line ->
            val parts = line.split(' ')
            if (parts.size == 3) map[parts[0]] = (parts[1].toLongOrNull() ?: 0L) to (parts[2].toLongOrNull() ?: 0L)
        }
        index = map
    }

    private fun read(key: String): String? {
        val k = fileKey(key)
        val entry = index()[k] ?: return null
        val bytes = files.read("$FOLDER/$k") ?: run {
            index().remove(k)
            return null
        }
        index()[k] = entry.first to now()
        return bytes.decodeToString()
    }

    private fun write(key: String, text: String) {
        val k = fileKey(key)
        val bytes = text.encodeToByteArray()
        if (bytes.size > maxBytes / 4 || !files.write("$FOLDER/$k", bytes)) return
        val all = index()
        all[k] = bytes.size.toLong() to now()
        var total = all.values.sumOf { it.first }
        if (total > maxBytes) {
            // Keys and values copied out first: on Kotlin/Native a map's entries read the live map, so an entry kept
            // past a remove() throws (Mac run of round 24); the oldest read first.
            val oldest = all.entries.map { it.key to it.value }.sortedBy { it.second.second }
            for ((old, v) in oldest) {
                if (total <= maxBytes) break
                if (old == k) continue
                files.delete("$FOLDER/$old")
                all.remove(old)
                total -= v.first
            }
        }
        saveIndex()
    }

    private fun remove(key: String) {
        val k = fileKey(key)
        if (index().remove(k) != null) {
            files.delete("$FOLDER/$k")
            saveIndex()
        }
    }

    private fun saveIndex() {
        files.write(INDEX, index().entries.joinToString("\n") { "${it.key} ${it.value.first} ${it.value.second}" }.encodeToByteArray())
    }

    companion object {
        private const val FOLDER = "community"
        private const val INDEX = "community/index.txt"

        /** About a thousand posts and the trips of the last few opened. */
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}

/**
 * The community's writes made without a connection, in the order made, per account ([Entry.uid]), kept in [settings]
 * until they reach the server (the Firebase SDK's pending writes). [Entry.kind] says what each is ("like", "unlike",
 * "report", "block", "unblock", "follow", "unfollow", "unfollowBoth"); [Entry.key] whom or what (a post's id, a person's
 * uid); [Entry.writes] the commit's writes as the REST API takes them.
 */
class CommunityOutbox(private val settings: SettingsFile) {
    @Serializable
    data class Entry(val uid: String, val kind: String, val key: String, val writes: String, val name: String? = null) {
        val commit: List<JsonObject> get() = runCatching { (JSON.parseToJsonElement(writes) as JsonArray).map { it.jsonObject } }.getOrDefault(emptyList())
    }

    private val lock = Mutex()

    private fun read(): List<Entry> = settings.read(KEY)?.let { runCatching { JSON.decodeFromString(ListSerializer(Entry.serializer()), it) }.getOrNull() }.orEmpty()

    private fun write(all: List<Entry>) = settings.write(KEY, if (all.isEmpty()) null else JSON.encodeToString(ListSerializer(Entry.serializer()), all))

    /** The writes of [uid] still waiting, oldest first. */
    suspend fun of(uid: String): List<Entry> = lock.withLock { read().filter { it.uid == uid } }

    suspend fun add(entry: Entry) = lock.withLock { write(read() + entry) }

    /** [entry] went (or was refused): it waits no more. */
    suspend fun remove(entry: Entry) = lock.withLock {
        val all = read().toMutableList()
        val i = all.indexOf(entry)
        if (i >= 0) all.removeAt(i)
        write(all)
    }

    /** Everything [uid] still had waiting goes (their profile or account was deleted). */
    suspend fun clear(uid: String) = lock.withLock { write(read().filter { it.uid != uid }) }

    companion object {
        /** This phone's own setting, never in a backup. */
        const val KEY = "communityOutbox"

        private val JSON = Json { ignoreUnknownKeys = true }

        fun writesText(writes: List<JsonObject>): String = JsonArray(writes).toString()
    }
}

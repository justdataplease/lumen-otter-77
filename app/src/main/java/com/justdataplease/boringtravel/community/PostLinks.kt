package com.justdataplease.boringtravel.community

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A trip post kept in step with its trip (owner, 17 Nov 2026: "a button in trip to autosync with community to post and
 * resync"). What the traveller picked when posting: the notes ([noteIds]; [allNotes]: every note was picked, so notes
 * written later go too) and the cover ([cover], a media id; [drawnCover]: the drawn picture was chosen over photos).
 * What is in the post's Drive folder: [files], media id → Drive file id, so a picture goes up once. [sent]: the
 * fingerprint of what the post holds ([PostSyncs.fingerprint]), so an update with nothing new asks nothing (and the
 * trip's sheet can tell the trip changed since). Updates go up only when asked (1.19.0's "auto" is read and dropped). [problem]: why the last update didn't go up, until one does. [title] is the post's, for showing.
 */
@Serializable
data class PostLink(
    val tripId: String,
    val postId: String,
    val title: String = "",
    val includeNotes: Boolean = false,
    val noteIds: List<String> = emptyList(),
    val allNotes: Boolean = false,
    val cover: String? = null,
    val drawnCover: Boolean = false,
    val folderId: String? = null,
    val files: Map<String, String> = emptyMap(),
    val sent: String? = null,
    val syncedAt: Long = 0,
    val problem: String? = null,
)

/**
 * The trips' post links, by trip id, in one small file on this phone (`files/community/post-links.json`; not in
 * backups: on another phone the trip is posted again). [all] is what screens show; every change is written at once.
 */
class PostLinks(private val file: File) {
    private val mutex = Mutex()
    private val _all = MutableStateFlow<Map<String, PostLink>>(emptyMap())
    val all: StateFlow<Map<String, PostLink>> = _all.asStateFlow()

    @Volatile private var loaded = false

    suspend fun load() = mutex.withLock { loadLocked() }

    suspend fun of(tripId: String): PostLink? = mutex.withLock {
        loadLocked()
        _all.value[tripId]
    }

    /** Trip [link]'s post from now on (a trip posted again: the newer post). */
    suspend fun put(link: PostLink) = change { it + (link.tripId to link) }

    suspend fun edit(tripId: String, change: (PostLink) -> PostLink) = change { m -> m[tripId]?.let { m + (tripId to change(it)) } ?: m }

    suspend fun remove(tripId: String) = change { it - tripId }

    /** The post went (deleted from its page): its trip isn't linked to it any more. */
    suspend fun removePost(postId: String) = change { m -> m.filterValues { it.postId != postId } }

    suspend fun clear() = change { emptyMap() }

    private suspend fun change(next: (Map<String, PostLink>) -> Map<String, PostLink>) = mutex.withLock {
        loadLocked()
        val now = next(_all.value)
        if (now != _all.value) {
            _all.value = now
            withContext(Dispatchers.IO) { write(now) }
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        _all.value = withContext(Dispatchers.IO) { runCatching { if (file.isFile) decode(file.readText()) else emptyMap() }.getOrDefault(emptyMap()) }
        loaded = true
    }

    private fun write(links: Map<String, PostLink>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encode(links))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val list = ListSerializer(PostLink.serializer())

        fun encode(links: Map<String, PostLink>): String = json.encodeToString(list, links.values.toList())

        /** [encode]'s text; a damaged file reads as no links. */
        fun decode(text: String): Map<String, PostLink> =
            runCatching { json.decodeFromString(list, text) }.getOrDefault(emptyList()).associateBy { it.tripId }
    }
}

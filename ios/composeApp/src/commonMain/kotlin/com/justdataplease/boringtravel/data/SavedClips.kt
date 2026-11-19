package com.justdataplease.boringtravel.data

import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * Finished trip videos kept for the trip's Media tab (round 20): the Android app's data/SavedClips.kt written again over
 * this app's files. The same folder under the app's files ("clips/"), the same three files a clip (<id>.mp4, its <id>.json
 * written last so a clip half copied never lists, a <id>.jpg for its thumbnail), the same sidecar and ids, so the
 * Android app's SavedClipsTest runs against this index (desktopTest shims give it Java's folders) and a backup file's
 * extras/clips/ carries them between the phones.
 */

/** A finished trip clip kept on the phone (see [SavedClips]); [durationMs], [width] and [height] are 0 when unknown. */
data class SavedClip(
    val id: String,
    val tripId: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

/** The saved-clips index, pure but for the files it reads: one folder holds each clip's video, sidecar and thumbnail. */
object SavedClipIndex {
    const val VERSION = 1

    /** The folder under the app's files (Android: files/clips). */
    const val DIR = "clips"
    private const val VIDEO = ".mp4"
    private const val META = ".json"
    private const val THUMB = ".jpg"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun video(id: String, dir: String = DIR) = "$dir/$id$VIDEO"
    fun meta(id: String, dir: String = DIR) = "$dir/$id$META"
    fun thumb(id: String, dir: String = DIR) = "$dir/$id$THUMB"

    /** A new id: the time it was made, then a few random letters (sorts by time, never clashes in practice). */
    fun newId(now: Long, random: String): String = "$now-${random.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "x" }}"

    fun encode(c: SavedClip): String = JsonObject(
        linkedMapOf(
            "v" to JsonPrimitive(VERSION),
            "id" to JsonPrimitive(c.id),
            "trip" to JsonPrimitive(c.tripId),
            "title" to JsonPrimitive(c.title),
            "at" to JsonPrimitive(c.createdAt),
            "ms" to JsonPrimitive(c.durationMs),
            "w" to JsonPrimitive(c.width),
            "h" to JsonPrimitive(c.height),
        ),
    ).toString()

    /** The clip a sidecar describes, or null when it is damaged or lacks its id, trip or time. */
    fun decode(text: String?): SavedClip? {
        if (text.isNullOrBlank()) return null
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull }.getOrNull()
        fun long(k: String) = runCatching { o[k]?.jsonPrimitive?.takeIf { !it.isString }?.longOrNull }.getOrNull()
        fun int(k: String) = runCatching { o[k]?.jsonPrimitive?.takeIf { !it.isString }?.intOrNull }.getOrNull()
        val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
        val trip = str("trip")?.takeIf { it.isNotBlank() } ?: return null
        val at = long("at") ?: return null
        return SavedClip(
            id = id, tripId = trip, title = str("title").orEmpty(), createdAt = at,
            durationMs = (long("ms") ?: 0).coerceAtLeast(0), width = (int("w") ?: 0).coerceAtLeast(0), height = (int("h") ?: 0).coerceAtLeast(0),
        )
    }

    /** Every clip in [dir] whose video is there, newest first; damaged, orphaned or half-written ones are left out. */
    fun list(files: MediaFiles, dir: String = DIR): List<SavedClip> = files.list(dir).filter { it.endsWith(META) }.mapNotNull { name ->
        runCatching { decode(files.read("$dir/$name")?.decodeToString()) }.getOrNull()
            ?.takeIf { it.id == name.removeSuffix(META) && (files.size(video(it.id, dir)) ?: 0L) > 0L }
    }.let(::newestFirst)

    fun newestFirst(clips: List<SavedClip>): List<SavedClip> = clips.sortedWith(compareByDescending<SavedClip> { it.createdAt }.thenByDescending { it.id })

    /** One trip's clips, newest first. */
    fun forTrip(clips: List<SavedClip>, tripId: String): List<SavedClip> = newestFirst(clips.filter { it.tripId == tripId })

    /** Deletes a clip's files (sidecar first, so a half-deleted clip never lists). */
    fun delete(files: MediaFiles, id: String, dir: String = DIR) {
        files.delete(meta(id, dir))
        files.delete(video(id, dir))
        files.delete(thumb(id, dir))
    }

    /**
     * Deletes every clip of trip [tripId] (the trip was deleted), half-written ones too: any sidecar that names the
     * trip, with its video and frame. Returns the ids deleted.
     */
    fun deleteTrip(files: MediaFiles, tripId: String, dir: String = DIR): List<String> = files.list(dir).filter { it.endsWith(META) }.mapNotNull { name ->
        val clip = runCatching { decode(files.read("$dir/$name")?.decodeToString()) }.getOrNull() ?: return@mapNotNull null
        if (clip.tripId != tripId) return@mapNotNull null
        val id = name.removeSuffix(META)
        delete(files, id, dir)
        id
    }

    /** The longest side of a clip's thumbnail (Android's SavedClips.THUMB_SIDE) and its JPEG quality (Android's 82). */
    const val THUMB_SIDE = 480
    const val THUMB_QUALITY = 82

    /** Where a clip's thumbnail is taken: 2/5 of the way in (the middle of the route; Android's `duration * 2 / 5`). */
    fun thumbAtMs(durationMs: Long): Long = durationMs.coerceAtLeast(0L) * 2 / 5

    /**
     * A thumbnail for clip [c] brought back from a backup (Android's SavedClips.restore): its picture 2/5 of the way in,
     * by the length the video itself says (else its listing's), at most [THUMB_SIDE] px, as a JPEG at [THUMB_QUALITY].
     * False when the phone couldn't make one (the Media tab then shows its film mark).
     */
    fun restoreThumb(files: MediaFiles, frames: com.justdataplease.boringtravel.ios.VideoFrames, c: SavedClip, dir: String = DIR): Boolean {
        val video = video(c.id, dir)
        val ms = frames.durationMs(video) ?: c.durationMs
        val jpeg = runCatching { frames.frameAt(video, thumbAtMs(ms), THUMB_SIDE, THUMB_QUALITY) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        return files.write(thumb(c.id, dir), jpeg)
    }

    /** "0:42", "1:05", "12:30" (whole seconds, rounded); "" when unknown. */
    fun length(ms: Long): String {
        if (ms <= 0) return ""
        val s = (ms / 1000.0).roundToInt().coerceAtLeast(1)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}

/**
 * Finished trip clips, kept so the trip's Media tab can list, play and share them (the Android app's SavedClips): a
 * clip is made into the app's exports/ and shared from there; the studio hands each finished one to [keep], which
 * copies it into clips/ with its thumbnail (a frame from 40 % in, drawn by the export) and its length and size. Deleting
 * a trip deletes its clips ([deleteTrip]). One for the app ([of]).
 */
class SavedClips(private val files: MediaFiles) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val _all = MutableStateFlow<List<SavedClip>?>(null)

    /** Every clip on the phone, newest first; null until first read ([refresh]). */
    val all: StateFlow<List<SavedClip>?> = _all.asStateFlow()

    fun video(clip: SavedClip): String = SavedClipIndex.video(clip.id)

    fun thumbnail(clip: SavedClip): String? = SavedClipIndex.thumb(clip.id).takeIf { (files.size(it) ?: 0L) > 0L }

    /**
     * Copies a finished clip of trip [tripId] at [source] in, [durationMs] long and [width] × [height], with [thumb] (a
     * JPEG) as its picture; in the background (never blocks). [done] gets the clip kept, or null.
     */
    fun keep(tripId: String, title: String, source: String, durationMs: Long, width: Int, height: Int, thumb: ByteArray?, done: (SavedClip?) -> Unit = {}) {
        scope.launch { done(runCatching { keepNow(tripId, title, source, durationMs, width, height, thumb) }.getOrNull()) }
    }

    /** [keep], waiting for it. */
    suspend fun keepNow(tripId: String, title: String, source: String, durationMs: Long, width: Int, height: Int, thumb: ByteArray?): SavedClip? =
        withContext(scope.coroutineContext) {
            if ((files.size(source) ?: 0L) <= 0L) return@withContext null
            val now = nowMs()
            val id = SavedClipIndex.newId(now, Random.nextLong().toULong().toString(36) + Random.nextInt().toUInt().toString(36))
            if (!files.copy(source, SavedClipIndex.video(id))) return@withContext null
            thumb?.let { files.write(SavedClipIndex.thumb(id), it) }
            val clip = SavedClip(id, tripId, title, now, durationMs, width, height)
            // The listing last: a clip half copied never shows.
            files.write(SavedClipIndex.meta(id), SavedClipIndex.encode(clip).encodeToByteArray())
            _all.value = SavedClipIndex.list(files)
            clip
        }

    /** Reads the folder again (the Media tab, after a restore). */
    fun refresh() {
        scope.launch { _all.value = runCatching { SavedClipIndex.list(files) }.getOrDefault(emptyList()) }
    }

    /** The clips on this phone now, read from the folder (newest first); empty when it can't be read. */
    fun listNow(): List<SavedClip> = runCatching { SavedClipIndex.list(files) }.getOrDefault(emptyList())

    fun delete(clip: SavedClip) {
        scope.launch {
            SavedClipIndex.delete(files, clip.id)
            _all.value = SavedClipIndex.list(files)
        }
    }

    /** Deletes every clip of trip [tripId] (the trip was deleted), now. */
    fun deleteTrip(tripId: String) {
        runCatching { SavedClipIndex.deleteTrip(files, tripId) }
        refresh()
    }
}

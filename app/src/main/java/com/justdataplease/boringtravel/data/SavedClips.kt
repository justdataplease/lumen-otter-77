package com.justdataplease.boringtravel.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

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

/**
 * The saved-clips index, pure: one folder holds `<id>.mp4`, its `<id>.json` (written last, so a clip half copied never
 * lists) and a `<id>.jpg` frame for the thumbnail. Unit tested.
 */
object SavedClipIndex {
    const val VERSION = 1
    private const val VIDEO = ".mp4"
    private const val META = ".json"
    private const val THUMB = ".jpg"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun video(dir: File, id: String) = File(dir, id + VIDEO)
    fun meta(dir: File, id: String) = File(dir, id + META)
    fun thumb(dir: File, id: String) = File(dir, id + THUMB)

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
    fun list(dir: File): List<SavedClip> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(META) }.orEmpty()
        return files.mapNotNull { f ->
            runCatching { decode(f.readText()) }.getOrNull()
                ?.takeIf { it.id == f.name.removeSuffix(META) && video(dir, it.id).let { v -> v.isFile && v.length() > 0 } }
        }.let(::newestFirst)
    }

    fun newestFirst(clips: List<SavedClip>): List<SavedClip> = clips.sortedWith(compareByDescending<SavedClip> { it.createdAt }.thenByDescending { it.id })

    /** One trip's clips, newest first. */
    fun forTrip(clips: List<SavedClip>, tripId: String): List<SavedClip> = newestFirst(clips.filter { it.tripId == tripId })

    /** Deletes a clip's files (sidecar first, so a half-deleted clip never lists). */
    fun delete(dir: File, id: String) {
        meta(dir, id).delete()
        video(dir, id).delete()
        thumb(dir, id).delete()
    }

    /**
     * Deletes every clip of trip [tripId] (the trip was deleted), half-written ones too: any sidecar that names the
     * trip, with its video and frame. Returns the ids deleted.
     */
    fun deleteTrip(dir: File, tripId: String): List<String> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(META) }.orEmpty()
        return files.mapNotNull { f ->
            val clip = runCatching { decode(f.readText()) }.getOrNull() ?: return@mapNotNull null
            if (clip.tripId != tripId) return@mapNotNull null
            val id = f.name.removeSuffix(META)
            delete(dir, id)
            id
        }
    }

    /** "0:42", "1:05", "12:30" (whole seconds, rounded); "" when unknown. */
    fun length(ms: Long): String {
        if (ms <= 0) return ""
        val s = (ms / 1000.0).roundToInt().coerceAtLeast(1)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}

/**
 * Finished trip clips, kept so the trip's Media tab can list, play and share them. A clip is rendered to the cache
 * (clip/ClipExport) and shared from there; ClipViewModel hands each finished one to [keep], which copies it to
 * `files/clips` with a frame for the thumbnail. Kept on the phone only (not in Drive backups). Deleting a trip
 * deletes its clips ([deleteTrip]); leaving a shared trip keeps them, for joining again.
 */
class SavedClips private constructor(context: Context) {
    private val app = context.applicationContext
    val dir = File(app.filesDir, "clips")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val all = MutableStateFlow<List<SavedClip>?>(null)

    /** Trip [tripId]'s clips, newest first. */
    fun observe(tripId: String): Flow<List<SavedClip>> =
        all.onStart { if (all.value == null) refresh() }.filterNotNull().map { SavedClipIndex.forTrip(it, tripId) }

    fun video(clip: SavedClip): File = SavedClipIndex.video(dir, clip.id)
    fun thumbnail(clip: SavedClip): File? = SavedClipIndex.thumb(dir, clip.id).takeIf { it.isFile }

    /** Copies a finished clip of trip [tripId] in, with its length, size and a thumbnail; runs on its own (never blocks). */
    fun keep(tripId: String, title: String, source: File) {
        scope.launch { runCatching { keepNow(tripId, title, source) } }
    }

    private fun keepNow(tripId: String, title: String, source: File) {
        if (!source.isFile || source.length() == 0L) return
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val id = SavedClipIndex.newId(now, java.util.UUID.randomUUID().toString())
        val video = SavedClipIndex.video(dir, id)
        val part = File(dir, "$id.part")
        try {
            source.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(video)) return
        } finally {
            part.delete()
        }
        var duration = 0L
        var w = 0
        var h = 0
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(video.path)
                duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                // A frame from the middle of the route (the opening is the overview, the end the totals card).
                val frame = r.getFrameAtTime(duration * 1000L * 2 / 5, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) saveThumb(frame, SavedClipIndex.thumb(dir, id))
            }
        }
        SavedClipIndex.meta(dir, id).writeText(SavedClipIndex.encode(SavedClip(id, tripId, title, now, duration, w, h)))
        refresh()
    }

    private fun saveThumb(frame: Bitmap, out: File) {
        val scale = THUMB_SIDE.toFloat() / max(frame.width, frame.height).coerceAtLeast(1)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt().coerceAtLeast(1), (frame.height * scale).roundToInt().coerceAtLeast(1), true)
        } else {
            frame
        }
        out.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (small !== frame) small.recycle()
        frame.recycle()
    }

    /** The clips on this phone now, read from the folder (newest first); empty when it can't be read. */
    fun listNow(): List<SavedClip> = runCatching { SavedClipIndex.list(dir) }.getOrDefault(emptyList())

    /**
     * Brings clip [c] back from a backup: [source] (a finished download or copy) becomes its video, with a new
     * thumbnail and its listing as it was. False when the clip is here already or [source] is empty; [source] is
     * always gone afterwards.
     */
    suspend fun restore(c: SavedClip, source: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!source.isFile || source.length() == 0L) return@withContext false
            val video = SavedClipIndex.video(dir, c.id)
            if (SavedClipIndex.meta(dir, c.id).isFile && video.isFile && video.length() > 0) return@withContext false
            dir.mkdirs()
            if (source.canonicalFile != video.canonicalFile) {
                video.delete()
                if (!source.renameTo(video)) source.copyTo(video, overwrite = true)
            }
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(video.path)
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: c.durationMs
                    val frame = r.getFrameAtTime(ms * 1000L * 2 / 5, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) saveThumb(frame, SavedClipIndex.thumb(dir, c.id))
                }
            }
            // The listing last, as when a clip is first kept: a video half moved in never shows.
            SavedClipIndex.meta(dir, c.id).writeText(SavedClipIndex.encode(c))
            refresh()
            true
        } finally {
            if (source.exists() && source.canonicalFile != SavedClipIndex.video(dir, c.id).canonicalFile) source.delete()
        }
    }

    fun delete(clip: SavedClip) {
        scope.launch {
            SavedClipIndex.delete(dir, clip.id)
            refresh()
        }
    }

    /** Deletes every clip of trip [tripId] (the trip was deleted). */
    suspend fun deleteTrip(tripId: String) = withContext(Dispatchers.IO) {
        runCatching { SavedClipIndex.deleteTrip(dir, tripId) }
        refresh()
    }

    private fun refresh() {
        scope.launch { all.value = runCatching { SavedClipIndex.list(dir) }.getOrDefault(emptyList()) }
    }

    /** The phone's video player for [clip]. */
    fun playIntent(clip: SavedClip): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", video(clip))
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The share sheet for [clip]. */
    fun shareIntent(clip: SavedClip): Intent {
        val file = video(clip)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    companion object {
        private const val THUMB_SIDE = 480

        @Volatile private var instance: SavedClips? = null

        fun of(context: Context): SavedClips = instance ?: synchronized(this) {
            instance ?: SavedClips(context).also { instance = it }
        }
    }
}

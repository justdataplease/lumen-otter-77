package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.ios.ClipVideoFiles
import com.justdataplease.boringtravel.ios.MediaFiles
import com.justdataplease.boringtravel.ios.PickedMedia
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The Android app's clip/ClipMedia.kt and the file part of clip/ClipVideoFrames.kt, written again over this app's files
 * (MediaFiles) and the phone's videos (ClipVideoFiles: AVURLAsset on iPhone, where Android reads a video with
 * MediaMetadataRetriever): the same folders under the app's files, the same names, limits and messages, so a backup
 * (round 20, its extras/) carries a video's edits and media between the phones.
 */

/** Where a phone video is ([path], under the app's files) and which part plays ([startUs], [lengthUs] into the file). */
data class VideoSource(val id: String, val path: String, val startUs: Long, val lengthUs: Long)

/**
 * The trip clip's own media on this phone, next to its scene edits: videos for the clip (picked on the phone or taken
 * from the journal) are copied into clip-media/<trip id>/ (so the clip still has them if the original leaves the
 * library or the note), voice lines go to clip-voice/<trip id>/.
 */
object ClipMedia {
    /** A picked video bigger than this is refused (about 4 minutes of 4K): the clip plays at most 10 s of it. */
    const val MAX_VIDEO_BYTES = 1_000_000_000L

    fun videoDir(tripId: String): String = "clip-media/" + safe(tripId)

    fun videoFile(tripId: String, video: ClipVideo): String = videoDir(tripId) + "/" + video.file

    fun voiceDir(tripId: String): String = "clip-voice/" + safe(tripId)

    fun voiceFile(tripId: String, voice: VoiceLine): String = voiceDir(tripId) + "/" + voice.file

    /** Where each of [videos] is and which part plays, for the preview's frames and the export. */
    fun sources(tripId: String, videos: List<ClipVideo>): Map<String, VideoSource> = videos.associate {
        it.id to VideoSource(it.id, videoFile(tripId, it), it.startMs * 1000, it.lengthMs * 1000)
    }

    /** Why a video couldn't be added. */
    class AddFailed(message: String) : Exception(message)

    /**
     * The video [picked] from the phone's library (already a copy under the app's files) moved into the trip's clip
     * media and its length read: a new [ClipVideo] after the place name [sceneKey], its first [ClipVideo.DEFAULT_MS]
     * chosen, sound off.
     */
    suspend fun importVideo(files: MediaFiles, videos: ClipVideoFiles, tripId: String, picked: PickedMedia, sceneKey: String): ClipVideo =
        try {
            copyIn(files, videos, tripId, picked.mime, sceneKey, null) { temp ->
                val size = files.size(picked.path) ?: throw AddFailed("Couldn't open the video")
                if (size > MAX_VIDEO_BYTES) throw AddFailed("This video is too big. Pick a shorter one.")
                if (!files.move(picked.path, temp)) throw AddFailed("Couldn't open the video")
            }
        } finally {
            // The picker's copy is never kept beside the clip's own.
            files.delete(picked.path)
        }

    /**
     * A journal video ([path], of type [mime]; its media id [mediaId]) copied into the trip's clip media, as a picked one
     * is: a copy, so the clip keeps it if the note loses it. The new [ClipVideo] remembers [mediaId] as its
     * [ClipVideo.source].
     */
    suspend fun importJournalVideo(
        files: MediaFiles, videos: ClipVideoFiles, tripId: String, path: String, mime: String?, mediaId: String, sceneKey: String,
    ): ClipVideo = copyIn(files, videos, tripId, mime, sceneKey, mediaId) { temp ->
        var total = 0L
        var failed: AddFailed? = null
        val read = files.readChunks(path) { buffer, n ->
            if (failed != null) return@readChunks
            total += n
            if (total > MAX_VIDEO_BYTES) {
                failed = AddFailed("This video is too big. Pick a shorter one.")
            } else if (!files.append(temp, buffer, n)) {
                failed = AddFailed("Couldn't keep the video")
            }
        }
        failed?.let { throw it }
        if (!read) throw AddFailed("Couldn't open the video")
    }

    /** The copy both ways share: the size limit, the length read, the same reasons when it fails. */
    private suspend fun copyIn(
        files: MediaFiles, videos: ClipVideoFiles, tripId: String, mime: String?, sceneKey: String, source: String?,
        fill: (temp: String) -> Unit,
    ): ClipVideo = withContext(Dispatchers.Default) {
        val ext = when (mime) {
            "video/3gpp" -> "3gp"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            else -> "mp4"
        }
        val id = newId()
        val dir = videoDir(tripId)
        val target = "$dir/$id.$ext"
        // Not yet the clip's (Android: <id>.part); its own extension, since the phone reads a video by it.
        val temp = "$dir/$id-part.$ext"
        try {
            fill(temp)
            val durationMs = videos.probe(temp)?.durationMs?.takeIf { it > 0 } ?: throw AddFailed("This file has no video the phone can play")
            if (!files.move(temp, target)) throw AddFailed("Couldn't keep the video")
            ClipVideo(id = id, file = "$id.$ext", sceneKey = sceneKey, durationMs = durationMs, source = source).trimmed()
        } catch (e: Exception) {
            files.delete(temp)
            files.delete(target)
            throw e
        }
    }

    /**
     * Deletes the trip's clip videos and voice lines no saved edit uses any more ([keep]: file names). Run when the
     * trip's edits are loaded, so a video removed (and not brought back with undo) doesn't stay on the phone. Files
     * from the last hour stay (their edit may still be on its way to the file).
     */
    suspend fun sweep(files: MediaFiles, tripId: String, keep: Set<String>) = withContext(Dispatchers.Default) {
        val now = nowMs()
        for (dir in listOf(videoDir(tripId), voiceDir(tripId))) {
            for (name in files.list(dir)) {
                val path = "$dir/$name"
                if (name !in keep && now - (files.modifiedMs(path) ?: now) > KEEP_NEW_MS) files.delete(path)
            }
        }
    }

    private const val KEEP_NEW_MS = 3_600_000L

    internal fun safe(tripId: String): String = tripId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
}

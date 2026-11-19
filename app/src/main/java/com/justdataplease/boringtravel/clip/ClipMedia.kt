package com.justdataplease.boringtravel.clip

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * The trip clip's own media on this phone, next to its scene edits: videos for the clip (picked on the phone or taken
 * from the journal) are copied into files/clip-media/<trip id>/ (so the clip still has them if the original leaves the
 * gallery or the note), voice lines go to files/clip-voice/<trip id>/. Not in Drive backups, like the finished clips.
 */
object ClipMedia {
    /** A picked video bigger than this is refused (about 4 minutes of 4K): the clip plays at most 10 s of it. */
    const val MAX_VIDEO_BYTES = 1_000_000_000L

    fun videoDir(context: Context, tripId: String): File = File(File(context.filesDir, "clip-media"), safe(tripId))

    fun videoFile(context: Context, tripId: String, video: ClipVideo): File = File(videoDir(context, tripId), video.file)

    fun voiceDir(context: Context, tripId: String): File = File(File(context.filesDir, "clip-voice"), safe(tripId))

    fun voiceFile(context: Context, tripId: String, voice: VoiceLine): File = File(voiceDir(context, tripId), voice.file)

    /** Where each of [videos] is and which part plays, for the preview's frames and the export. */
    fun sources(context: Context, tripId: String, videos: List<ClipVideo>): Map<String, VideoSource> = videos.associate {
        it.id to VideoSource(it.id, videoFile(context, tripId, it), it.startMs * 1000, it.lengthMs * 1000)
    }

    /** Why a video couldn't be added. */
    class AddFailed(message: String) : Exception(message)

    /**
     * Copies the video at [uri] (from the Photo Picker) into the trip's clip media and reads its length: a new
     * [ClipVideo] after the place name [sceneKey], its first [ClipVideo.DEFAULT_MS] chosen, sound off.
     */
    suspend fun importVideo(context: Context, tripId: String, uri: Uri, sceneKey: String): ClipVideo {
        val resolver = context.contentResolver
        return copyIn(context, tripId, withContext(Dispatchers.IO) { resolver.getType(uri) }, sceneKey, null) { resolver.openInputStream(uri) }
    }

    /**
     * Copies a journal video ([file], of type [mime]; its media id [mediaId]) into the trip's clip media, as a picked
     * one is (owner, 2026-11-14: "in clips videos are added as photos"): a copy, so the clip keeps it if the note loses
     * it. The new [ClipVideo] remembers [mediaId] as its [ClipVideo.source].
     */
    suspend fun importJournalVideo(context: Context, tripId: String, file: File, mime: String?, mediaId: String, sceneKey: String): ClipVideo =
        copyIn(context, tripId, mime, sceneKey, mediaId) { if (file.isFile) file.inputStream() else null }

    /** The copy both ways share: the size limit, the length read, the same reasons when it fails. */
    private suspend fun copyIn(
        context: Context, tripId: String, mime: String?, sceneKey: String, source: String?, open: () -> InputStream?,
    ): ClipVideo = withContext(Dispatchers.IO) {
        val ext = when (mime) {
            "video/3gpp" -> "3gp"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            else -> "mp4"
        }
        val id = UUID.randomUUID().toString()
        val dir = videoDir(context, tripId)
        if (!dir.isDirectory && !dir.mkdirs()) throw AddFailed("Couldn't make room for the video")
        val target = File(dir, "$id.$ext")
        val part = File(dir, "$id.part")
        try {
            val input = open() ?: throw AddFailed("Couldn't open the video")
            input.use { inp ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_VIDEO_BYTES) throw AddFailed("This video is too big. Pick a shorter one.")
                        out.write(buffer, 0, n)
                    }
                }
            }
            val durationMs = probe(part) ?: throw AddFailed("This file has no video the phone can play")
            if (!part.renameTo(target)) throw AddFailed("Couldn't keep the video")
            ClipVideo(id = id, file = target.name, sceneKey = sceneKey, durationMs = durationMs, source = source).trimmed()
        } catch (e: Exception) {
            part.delete()
            target.delete()
            throw e
        }
    }

    /** The video's length in ms, or null when it has no picture track. */
    private fun probe(file: File): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Deletes the trip's clip videos no saved edit uses any more ([keep]: file names). Run when the trip's edits are
     * loaded, so a video removed (and not brought back with undo) doesn't stay on the phone. Files from the last hour
     * stay (their edit may still be on its way to the file).
     */
    suspend fun sweep(context: Context, tripId: String, keep: Set<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for (dir in listOf(videoDir(context, tripId), voiceDir(context, tripId))) {
            dir.listFiles()?.forEach { f -> if (f.isFile && f.name !in keep && now - f.lastModified() > KEEP_NEW_MS) f.delete() }
        }
    }

    private const val KEEP_NEW_MS = 3_600_000L

    internal fun safe(tripId: String): String = tripId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
}

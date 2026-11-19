package com.justdataplease.boringtravel.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import androidx.annotation.OptIn
import androidx.core.graphics.scale
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.justdataplease.boringtravel.backup.BackupFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A video that couldn't be added, with what to tell the traveller. */
class VideoImportException(message: String) : IOException(message)

/**
 * Journal videos, picked from the phone or just recorded (see [VideoLimits]). [prepare] reads what the video says
 * about itself (length, size, time, place), then Media3's Transformer writes the whole video again as MP4 (H.264 and
 * AAC), its short side at most [VideoLimits.SHORT_SIDE] px at about [VideoLimits.VIDEO_BITRATE] bit/s (less for videos
 * over 10 minutes: [VideoLimits.bitrate]). Videos over [VideoLimits.MAX_MS] are refused. When this phone can't
 * compress it, a video of at most [VideoLimits.MAX_BYTES] is copied as it is; anything else fails with a plain message.
 * A half-written file never stays behind.
 *
 * A video is a journal photo like any other (a Block.Photo whose row has a video type); places that need a still
 * picture use its first frame ([frame]).
 */
class JournalVideos(context: Context) {
    private val app = context.applicationContext

    /** What [prepare] made: the file (in the media folder), its row's facts, and how long it is (null: unknown). */
    class Prepared(
        val localPath: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val takenAt: Long?,
        val lat: Double?,
        val lng: Double?,
        val sha256: String,
        val durationMs: Long?,
    )

    /** What a video says about itself; nulls and zeros for what it doesn't. Width and height as it is shown. */
    data class Probe(
        val durationMs: Long? = null,
        val width: Int = 0,
        val height: Int = 0,
        val takenAt: Long? = null,
        val lat: Double? = null,
        val lng: Double? = null,
        /** False only when the video says it has no sound. */
        val hasAudio: Boolean = true,
    )

    /**
     * Makes `files/media/<id>.mp4` from the video at [uri]. [onProgress] gets 0..1 on the main thread while it is
     * compressed. Cancelling stops the work and removes what it wrote.
     */
    suspend fun prepare(uri: Uri, id: String, onProgress: (Float) -> Unit): Prepared {
        val task = currentCoroutineContext()
        val source = withContext(Dispatchers.IO) { probe(uri) }
        if (VideoLimits.tooLong(source.durationMs)) throw VideoImportException(TOO_LONG)
        val bytes = withContext(Dispatchers.IO) { size(uri) }
        val work = withContext(Dispatchers.IO) { workDir() }
        val compressed = File(work, "$id.mp4")
        try {
            val made = try {
                transcode(uri, source, compressed, onProgress)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                task.ensureActive()
                false
            }
            if (made) {
                val out = withContext(Dispatchers.IO) { probe(Uri.fromFile(compressed)) }
                // A length the video didn't tell before: the same limits on what came out.
                if (VideoLimits.tooLong(out.durationMs)) throw VideoImportException(TOO_LONG)
                if (compressed.length() > VideoLimits.HARD_MAX_BYTES) throw VideoImportException("This video is too big to add.")
                return move(compressed, id, out, source)
            }
            // The phone couldn't compress it: one that is small enough goes in as it is.
            if (!VideoLimits.canCopy(bytes)) throw VideoImportException("This video couldn't be prepared on this phone.")
            val mime = withContext(Dispatchers.IO) { app.contentResolver.getType(uri) }?.takeIf { MediaTypes.isVideo(it) } ?: MediaTypes.MP4
            return copy(uri, id, mime, source)
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { compressed.delete() }
        }
    }

    /**
     * Moves the compressed [file] into the media folder (no second copy of a big file: the cache and the media folder
     * are on the same storage), checksummed first, and describes it.
     */
    private suspend fun move(file: File, id: String, out: Probe, source: Probe): Prepared = withContext(Dispatchers.IO) {
        val rel = "media/$id.${MediaTypes.extension(MediaTypes.MP4)}"
        val target = File(app.filesDir, rel)
        val task = currentCoroutineContext()
        val sum = BackupFiles.sha256(file) { task.ensureActive() }
        target.parentFile?.mkdirs()
        if (target.exists()) throw IOException("A video already exists at this path")
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            // Made in the cache folder, it carries the cache's group: the app's own again, so it isn't counted as cache.
            runCatching { Os.chown(target.path, -1, Os.getgid()) }
        } catch (_: IOException) {
            file.inputStream().use { PhotoFiles.copy(it, target) { task.ensureActive() } }
        }
        Prepared(rel, MediaTypes.MP4, out.width, out.height, source.takenAt, source.lat, source.lng, sum, out.durationMs ?: source.durationMs)
    }

    /** Copies the video at [uri] as it is into the media folder (whole, then visible: [PhotoFiles.copy]) and describes it. */
    private suspend fun copy(uri: Uri, id: String, mime: String, source: Probe): Prepared = withContext(Dispatchers.IO) {
        val rel = "media/$id.${MediaTypes.extension(mime)}"
        val task = currentCoroutineContext()
        val input = app.contentResolver.openInputStream(uri) ?: throw VideoImportException("This video couldn't be opened.")
        val sum = input.use { PhotoFiles.copy(it, File(app.filesDir, rel)) { task.ensureActive() } }
        Prepared(rel, mime, source.width, source.height, source.takenAt, source.lat, source.lng, sum, source.durationMs)
    }

    /** The folder Transformer writes into; what a stopped app left there long ago is cleared. */
    private fun workDir(): File {
        val dir = File(app.cacheDir, WORK_DIR).apply { mkdirs() }
        val old = System.currentTimeMillis() - STALE_MS
        dir.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
        return dir
    }

    /** The video's size in bytes, when the phone can tell. */
    private fun size(uri: Uri): Long? = runCatching {
        app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { n -> n > 0 } }
            ?: app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
            }
    }.getOrNull()

    /** Reads [uri]'s own tags: length, shown size, recording time and place ([VideoTags]). */
    fun probe(uri: Uri): Probe = runCatching {
        MediaMetadataRetriever().use { r ->
            if (uri.scheme == "file") r.setDataSource(requireNotNull(uri.path)) else r.setDataSource(app, uri)
            probe(r)
        }
    }.getOrDefault(Probe())

    /**
     * Compresses [uri] into [out] with Media3's Transformer, on the main thread's looper (Transformer calls back on the
     * thread that started it). Returns when it is written; throws when it can't be; cancelling stops it.
     */
    @OptIn(UnstableApi::class)
    private suspend fun transcode(uri: Uri, source: Probe, out: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.Main) {
        // The whole video: no clipping.
        val item = MediaItem.fromUri(uri)
        val resize = VideoLimits.shortSide(source.width, source.height)
        val effects = if (resize != null) Effects(emptyList(), listOf(Presentation.createForShortSide(resize))) else Effects.EMPTY
        val edited = EditedMediaItem.Builder(item).setEffects(effects).build()
        // A video without sound (a screen recording) stays without: no silent track is made up for it.
        val sequence = if (source.hasAudio) EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
        else EditedMediaItemSequence.withVideoFrom(listOf(edited))
        // HDR from newer phones becomes ordinary colour: every phone and Drive's player can show it.
        val composition = Composition.Builder(sequence)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(app)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(app)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VideoLimits.bitrate(source.durationMs)).build())
                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(VideoLimits.AUDIO_BITRATE).build())
                    .build(),
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        onProgress(0f)
        transformer.start(composition, out.path)
        val ticks = launch {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                delay(PROGRESS_MS)
            }
        }
        try {
            done.await()
        } finally {
            ticks.cancel()
            // Stopped from outside (the writer cancelled, the screen went): Transformer stops, its file goes above.
            if (!done.isCompleted) transformer.cancel()
        }
    }

    companion object {
        private val TOO_LONG = "This video is too long. Videos can be up to ${VideoLimits.MAX_MS / 60_000} minutes."
        private const val WORK_DIR = "journal-videos"
        private const val STALE_MS = 60 * 60_000L
        private const val PROGRESS_MS = 250L

        private fun probe(r: MediaMetadataRetriever): Probe {
            fun key(k: Int): String? = r.extractMetadata(k)
            var w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) w = h.also { h = w }
            val place = VideoTags.location(key(MediaMetadataRetriever.METADATA_KEY_LOCATION))
            return Probe(
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
                width = w.coerceAtLeast(0),
                height = h.coerceAtLeast(0),
                takenAt = VideoTags.date(key(MediaMetadataRetriever.METADATA_KEY_DATE)),
                lat = place?.first,
                lng = place?.second,
                hasAudio = key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null ||
                    key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null,
            )
        }

        /** The shown width and height of the video [file]; null when it can't be read. */
        fun size(file: File): Pair<Int, Int>? = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(file.path)
                probe(r).takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height }
            }
        }.getOrNull()

        /**
         * The video [file]'s first frame as a still picture, no longer than [maxPx] on its longest side (exports, books
         * and anything else that needs a photo); null when it can't be read.
         */
        fun frame(file: File, maxPx: Int): Bitmap? = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(file.path)
                val full = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@use null
                val longest = maxOf(full.width, full.height)
                if (maxPx <= 0 || longest <= maxPx) return@use full
                val k = maxPx.toFloat() / longest
                val w = (full.width * k).toInt().coerceAtLeast(1)
                val h = (full.height * k).toInt().coerceAtLeast(1)
                full.scale(w, h).also { if (it !== full) full.recycle() }
            }
        }.getOrNull()
    }
}

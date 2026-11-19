package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.repo.VideoLimits
import com.justdataplease.boringtravel.data.repo.newId
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A photo or video that couldn't go into a note, with what to tell the traveller (the Android app's words). */
class MediaImportException(message: String) : Exception(message)

/** A video added to a note, and how long it is (null: unknown); the Android app's MediaRepository.VideoImport. */
class VideoImport(val media: Media, val durationMs: Long?)

/**
 * Brings what was picked into the app the way the Android app's MediaRepository imports it, so the rows and files are
 * the ones a backup carries:
 *
 * - **A photo** is kept as it is, in its own format (a HEIC stays a HEIC, as Android's photo picker hands it over): its
 *   file moves to "media/<id>.<ext>" (the extension from its type: jpg, png, webp, heic, else jpg), with its SHA-256,
 *   and from its own tags (any format: the phone's reader, [Images]) its capture time and place and its size as shown.
 * - **A video** is the whole of it written again as Android's JournalVideos writes it ([prepareVideo]): an MP4 with
 *   H.264 at 720p at most and Android's bit rate, AAC 128 kbit/s, HDR as ordinary colour ([VideoPrep]: AVFoundation on
 *   iPhone), its time and place from its own tags; up to 30 minutes; one the phone can't write again goes in as it is
 *   when it is no bigger than 240 MB.
 *
 * [prepare] does a photo's file work and makes the row (off the main thread: [MediaWork]); the caller keeps the row
 * ([TripBook.addMedia]) on the main thread. A file that couldn't go in is removed.
 */
class MediaImporter(
    private val files: MediaFiles,
    private val videos: VideoPrep = NoVideoPrep,
    private val ids: () -> String = ::newId,
) {
    /** A photo's row (a video given here goes in as it is, without being written again: see [prepareVideo]). */
    fun prepare(p: PickedMedia, tripId: String?, entryId: String?): Media = try {
        if (p.video) videoAsIs(p, tripId, entryId, probe(p)).media else photo(p, tripId, entryId)
    } catch (e: MediaImportException) {
        files.delete(p.path)
        throw e
    } catch (e: Exception) {
        files.delete(p.path)
        throw MediaImportException(if (p.video) "This video couldn't be added." else "One photo couldn't be added.")
    }

    private fun photo(p: PickedMedia, tripId: String?, entryId: String?): Media {
        val bytes = files.read(p.path)?.takeIf { it.isNotEmpty() } ?: throw MediaImportException("One photo couldn't be added.")
        val id = ids()
        // The type the picker gave (Android: the content resolver's), as Android keeps it.
        val mime = p.mime.takeIf { it.startsWith("image/") } ?: MediaTypes.JPEG
        val path = "media/$id.${MediaTypes.extension(mime)}"
        if (!files.move(p.path, path) && !files.write(path, bytes)) throw MediaImportException("One photo couldn't be added.")
        // Its time, place and size from its own tags, whatever its format (Android: ExifInterface).
        val facts = Images.facts(bytes)
        val tags = facts?.tags ?: Exif.Tags()
        val (w, h) = facts?.let { it.width to it.height } ?: (p.width to p.height)
        return Media(
            id = id, tripId = tripId, entryId = entryId, localPath = path, mime = mime, width = w, height = h,
            takenAt = tags.takenAt() ?: p.takenAt, lat = tags.lat ?: p.lat, lng = tags.lng ?: p.lng,
            sha256 = Sha256.hex(bytes), createdAt = 0L, updatedAt = 0L,
        )
    }

    /** What the video says about itself, else what the picker said. */
    private fun probe(p: PickedMedia): VideoProbe {
        val read = runCatching { videos.probe(p.path) }.getOrDefault(VideoProbe())
        return read.copy(
            durationMs = read.durationMs ?: p.durationMs,
            width = read.width.takeIf { it > 0 } ?: p.width,
            height = read.height.takeIf { it > 0 } ?: p.height,
            takenAt = read.takenAt ?: p.takenAt,
            lat = read.lat ?: p.lat,
            lng = read.lng ?: p.lng,
        )
    }

    /**
     * The Android app's JournalVideos.prepare: [p] written again as `media/<id>.mp4` ([VideoPrep.transcode]: H.264, its
     * short side 720 px at most, [VideoLimits.bitrate] for its length, AAC 128 kbit/s), [onProgress] getting 0..1 as it
     * goes. Over 30 minutes, or a result over 256 MB: Android's words. When the phone can't write it again, it goes in as
     * it is when it is no bigger than 240 MB, else "This video couldn't be prepared on this phone.". Cancelling stops the
     * work and leaves nothing behind; the picked copy is gone either way.
     */
    suspend fun prepareVideo(p: PickedMedia, tripId: String?, entryId: String?, onProgress: (Float) -> Unit): VideoImport {
        val task = currentCoroutineContext()
        try {
            val bytes = files.size(p.path)?.takeIf { it > 0 } ?: throw MediaImportException("This video couldn't be opened.")
            val source = withContext(MediaWork) { probe(p) }
            if (VideoLimits.tooLong(source.durationMs)) throw MediaImportException(MediaLimits.TOO_LONG)
            val id = ids()
            val compressed = "$WORK_DIR/$id.mp4"
            withContext(MediaWork) { clearStale() }
            try {
                val made = try {
                    videos.transcode(
                        p.path, compressed, source, VideoLimits.bitrate(source.durationMs), VideoLimits.shortSide(source.width, source.height),
                        VideoLimits.AUDIO_BITRATE, onProgress,
                    )
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    task.ensureActive()
                    false
                }
                if (made) {
                    val out = withContext(MediaWork) { runCatching { videos.probe(compressed) }.getOrDefault(VideoProbe()) }
                    // A length the video didn't tell before: the same limits on what came out.
                    if (VideoLimits.tooLong(out.durationMs)) throw MediaImportException(MediaLimits.TOO_LONG)
                    if ((files.size(compressed) ?: 0L) > VideoLimits.HARD_MAX_BYTES) throw MediaImportException("This video is too big to add.")
                    return withContext(MediaWork) { keep(compressed, id, MediaTypes.MP4, out.width, out.height, source, out.durationMs ?: source.durationMs, tripId, entryId) }
                }
                // The phone couldn't write it again: one small enough goes in as it is.
                if (!VideoLimits.canCopy(bytes)) throw MediaImportException("This video couldn't be prepared on this phone.")
                return withContext(MediaWork) { videoAsIs(p, tripId, entryId, source, id) }
            } finally {
                files.delete(compressed)
            }
        } finally {
            // The picker's copy (already moved when it went in as it is).
            files.delete(p.path)
        }
    }

    /** [p] kept as it is (Android's JournalVideos.copy), within [VideoLimits.canCopy]. */
    private fun videoAsIs(p: PickedMedia, tripId: String?, entryId: String?, source: VideoProbe, id: String = ids()): VideoImport {
        if (VideoLimits.tooLong(source.durationMs)) throw MediaImportException(MediaLimits.TOO_LONG)
        if (!VideoLimits.canCopy(files.size(p.path))) throw MediaImportException("This video is too big to add.")
        val mime = p.mime.takeIf { MediaTypes.isVideo(it) } ?: MediaTypes.MP4
        return keep(p.path, id, mime, source.width, source.height, source, source.durationMs, tripId, entryId)
    }

    /** [from] moved to `media/<id>.<ext>`, checksummed, with its row: its time and place from [source]. */
    private fun keep(from: String, id: String, mime: String, w: Int, h: Int, source: VideoProbe, durationMs: Long?, tripId: String?, entryId: String?): VideoImport {
        val path = "media/$id.${MediaTypes.extension(mime)}"
        if (!files.move(from, path)) throw MediaImportException("This video couldn't be added.")
        val sum = Sha256()
        if (!files.readChunks(path) { buffer, n -> sum.update(buffer, 0, n) }) {
            files.delete(path)
            throw MediaImportException("This video couldn't be added.")
        }
        val media = Media(
            id = id, tripId = tripId, entryId = entryId, localPath = path, mime = mime, width = w, height = h,
            takenAt = source.takenAt, lat = source.lat, lng = source.lng, sha256 = sum.hex(), createdAt = 0L, updatedAt = 0L,
        )
        return VideoImport(media, durationMs)
    }

    /** What a stopped app left in the work folder long ago goes (Android's workDir). */
    private fun clearStale() {
        val old = nowMs() - STALE_MS
        files.list(WORK_DIR).forEach { name -> files.modifiedMs("$WORK_DIR/$name")?.takeIf { it < old }?.let { files.delete("$WORK_DIR/$name") } }
    }

    private companion object {
        /** Where a video is written again before it moves into media/ (Android: the cache's journal-videos). */
        const val WORK_DIR = "journal-videos"
        const val STALE_MS = 60 * 60_000L
    }
}

/**
 * Deletes trip [tripId] with everything in it ([TripBook.removeTrip]), then the app's copies of its photos and videos
 * (the trip's and its notes') that no other live photo uses, as the Android app's Deletions.deleteTrip does. False when
 * there is no such live trip.
 */
fun removeTripAndFiles(book: TripBook, files: MediaFiles, tripId: String): Boolean {
    val entries = book.snapshot.entries.filter { it.tripId == tripId }.mapTo(HashSet()) { it.id }
    val ids = book.snapshot.media.filter { (it.tripId == tripId || it.entryId in entries) && it.deletedAt == null }.mapTo(HashSet()) { it.id }
    if (!book.removeTrip(tripId)) return false
    // Its books (Make a book) are marked deleted too, as the Android app's BookDao.deleteForTrip does (round 21).
    book.removeBooks(tripId)
    freeFiles(book, files, book.snapshot.media.filter { it.id in ids })
    // The trip video's own files (scene edits, voice lines, the phone videos copied in) and its saved clips (round 20;
    // the Android app's TripRemoval.deleteClipFiles and SavedClips.deleteTrip).
    deleteClipFiles(files, tripId)
    com.justdataplease.boringtravel.data.SavedClipIndex.deleteTrip(files, tripId)
    return true
}

/**
 * The trip video's files of [tripId] under the app's files: its scene edits, voice lines and the phone videos copied in
 * (the Android app's TripRemoval.clipFiles; the same folders as a backup's extras/).
 */
fun deleteClipFiles(files: MediaFiles, tripId: String) {
    files.delete(ClipEditStore.path(tripId))
    files.delete(com.justdataplease.boringtravel.clip.ClipMedia.voiceDir(tripId))
    files.delete(com.justdataplease.boringtravel.clip.ClipMedia.videoDir(tripId))
}

/**
 * Deletes the app's copies of [deleted] photos and videos that no live row still uses (the Android app's
 * MediaRepository.freeFiles after a note is deleted): only files under media/.
 */
fun freeFiles(book: TripBook, files: MediaFiles, deleted: List<Media>) {
    val live = book.snapshot.media.filter { it.deletedAt == null }.mapTo(HashSet()) { it.localPath }
    deleted.filter { it.deletedAt != null }.map { it.localPath }.distinct()
        .filter { it !in live && it.startsWith("media/") && ".." !in it }
        .forEach(files::delete)
}

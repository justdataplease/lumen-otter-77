package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.data.db.Media
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/*
 * Photos and videos in notes: where their files live, how they are picked, made ready and played. Each phone's part is
 * behind a small interface (iosMain: Foundation, PhotosUI, UIKit, AVFoundation, ImageIO; desktopMain: files and a file
 * dialog; tests: a fake), and everything else is common code.
 *
 * Files are kept the way the Android app keeps them, so a backup carries them unchanged: each one under the app's own
 * files as "media/<id>.<ext>" (Media.localPath; the backup file's zip entry is that same path), its row in data.json
 * with type, size, capture time, place and SHA-256.
 */

/**
 * The app's own files, by path under its files folder ("media/<id>.jpg", "picked/…"). Application Support on iPhone, a
 * folder on the desktop, memory in tests.
 */
interface MediaFiles {
    /** Writes [bytes] whole to [path], its folder made when needed; false when it couldn't. */
    fun write(path: String, bytes: ByteArray): Boolean

    /** The whole file at [path]; null when there is none. */
    fun read(path: String): ByteArray?

    /** The file at [path] in pieces ([each] gets a buffer and how much of it is filled), for hashing a big video. */
    fun readChunks(path: String, each: (ByteArray, Int) -> Unit): Boolean

    /** Bytes in the file at [path]; null when there is none. */
    fun size(path: String): Long?

    /** [length] bytes of the file at [path] from [offset] (a backup file's parts); null when it has no such bytes. */
    fun readRange(path: String, offset: Long, length: Int): ByteArray?

    /** Adds [count] bytes of [bytes] to the end of the file at [path], made when there is none; false when it couldn't. */
    fun append(path: String, bytes: ByteArray, count: Int = bytes.size): Boolean

    /** Moves [from] to [to] (no copy of a big file when both are on the same disk); false when it couldn't. */
    fun move(from: String, to: String): Boolean

    /** Deletes the file at [path], or the folder at [path] with everything in it. */
    fun delete(path: String)

    /** The file's full path on this device, for the platform's own player and frame reader. */
    fun absolute(path: String): String

    /** The names of the files in the folder [path] (not its folders); empty when there is none. */
    fun list(path: String): List<String> = emptyList()

    /** When the file at [path] was last written, ms since 1970; null when there is none (or this phone can't say). */
    fun modifiedMs(path: String): Long? = null

    /**
     * A copy of [from] at [to] (what was there is replaced); false when it couldn't (nothing is left at [to] then). The
     * file system's own copy where there is one (iPhone: NSFileManager, a clone on APFS); else read and written in pieces.
     */
    fun copy(from: String, to: String): Boolean {
        val part = "$to.copying"
        delete(part)
        var ok = true
        val read = readChunks(from) { buffer, n -> if (ok) ok = append(part, buffer, n) }
        if (!read || !ok || size(from) != size(part)) {
            delete(part)
            return false
        }
        return replace(part, to)
    }

    /** Moves [from] over [to], whatever was at [to]; false when it couldn't ([from] stays then). */
    fun replace(from: String, to: String): Boolean {
        if (size(from) == null) return false
        delete(to)
        return move(from, to)
    }
}

/** [MediaFiles] in memory, for tests and previews. */
class MemoryMediaFiles : MediaFiles {
    private val files = LinkedHashMap<String, ByteArray>()
    private val written = HashMap<String, Long>()

    /** Every path kept, in the order written. */
    val paths: List<String> get() = files.keys.toList()

    /** The clock the files are dated by (tests move it). */
    var now: () -> Long = { com.justdataplease.boringtravel.time.nowMs() }

    override fun write(path: String, bytes: ByteArray): Boolean {
        files[path] = bytes.copyOf()
        written[path] = now()
        return true
    }

    override fun list(path: String): List<String> {
        val prefix = "${path.trimEnd('/')}/"
        return files.keys.filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }.map { it.removePrefix(prefix) }
    }

    override fun modifiedMs(path: String): Long? = if (path in files) written[path] ?: now() else null

    override fun read(path: String): ByteArray? = files[path]?.copyOf()

    override fun readChunks(path: String, each: (ByteArray, Int) -> Unit): Boolean {
        val bytes = files[path] ?: return false
        var at = 0
        while (at < bytes.size) {
            val n = minOf(CHUNK, bytes.size - at)
            each(bytes.copyOfRange(at, at + n), n)
            at += n
        }
        return true
    }

    override fun size(path: String): Long? = files[path]?.size?.toLong()

    override fun readRange(path: String, offset: Long, length: Int): ByteArray? {
        val bytes = files[path] ?: return null
        if (offset < 0 || offset + length > bytes.size) return null
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    override fun append(path: String, bytes: ByteArray, count: Int): Boolean {
        files[path] = (files[path] ?: ByteArray(0)) + bytes.copyOf(count)
        written[path] = now()
        return true
    }

    override fun move(from: String, to: String): Boolean {
        val bytes = files.remove(from) ?: return false
        files[to] = bytes
        written[to] = written.remove(from) ?: now()
        return true
    }

    override fun delete(path: String) {
        files.remove(path)
        written.remove(path)
        // A folder goes with what is in it.
        files.keys.filter { it.startsWith("$path/") }.forEach { files.remove(it); written.remove(it) }
    }

    override fun absolute(path: String): String = "memory:/$path"

    private companion object {
        const val CHUNK = 64 * 1024
    }
}

/**
 * Something picked for a note: its file, already under the app's files ([path], "picked/…") so nothing waits on the
 * picker, its type, and what the phone told about it (a video's size, length and time; a photo's come from its file).
 */
data class PickedMedia(
    val path: String,
    val mime: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long? = null,
    val takenAt: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
) {
    val video: Boolean get() = MediaTypes.isVideo(mime)
}

/**
 * The phone's pickers, as the Android editor opens them: photos and videos from the library together ([pickMedia], at
 * most [MediaLimits.PICKED], in the order picked), a photo or a video from the camera. Each hands over what it got;
 * nothing (an empty list, null) when the traveller backed out. They come back on the main thread.
 */
interface MediaPicker {
    fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit)

    /** Photos only, at most [max] (the AI planner's pictures); by default what [pickMedia] gives without its videos. */
    fun pickPhotos(max: Int, done: (List<PickedMedia>) -> Unit) = pickMedia(max) { picked -> done(picked.filter { !it.video }) }

    /**
     * Videos only, at most [max] (the trip video's "From your phone"; Android's Photo Picker with VideoOnly); by default
     * what [pickMedia] gives without its photos.
     */
    fun pickVideos(max: Int, done: (List<PickedMedia>) -> Unit) = pickMedia(max) { picked -> done(picked.filter { it.video }) }

    /** False when the phone has no camera to open (the Simulator): the camera's buttons say so. */
    val hasCamera: Boolean

    fun takePhoto(done: (PickedMedia?) -> Unit)

    fun recordVideo(done: (PickedMedia?) -> Unit)
}

/** A picker with nothing to pick: previews and screens that never pick. */
object NoPicker : MediaPicker {
    override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) = done(emptyList())
    override val hasCamera: Boolean = false
    override fun takePhoto(done: (PickedMedia?) -> Unit) = done(null)
    override fun recordVideo(done: (PickedMedia?) -> Unit) = done(null)
}

/** A video's first frame as an encoded picture (JPEG or PNG), at most [maxPx] on its longest side; null when none. */
fun interface VideoFrames {
    fun poster(path: String, maxPx: Int): ByteArray?

    /** How long the video at [path] is, in ms; null when the phone can't say (the desktop). */
    fun durationMs(path: String): Long? = null

    /**
     * The picture nearest [atMs] (the key frame there, as Android's OPTION_CLOSEST_SYNC) as a JPEG at [quality] (0–100),
     * at most [maxPx] on its longest side, turned as the video is shown; null when there is none.
     */
    fun frameAt(path: String, atMs: Long, maxPx: Int, quality: Int): ByteArray? = null
}

/**
 * What a video says about itself (the Android app's JournalVideos.Probe): its length, its size as it is shown, when and
 * where it was recorded (its own date and ISO 6709 location tags) and whether it has sound; nulls and zeros for what it
 * doesn't say.
 */
data class VideoProbe(
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
 * The phone's work on a note's video (the Android app's JournalVideos over MediaMetadataRetriever and Media3's
 * Transformer): what it says about itself, and the whole of it written again as Android writes it. iPhone: AVFoundation
 * (AVAssetReader and AVAssetWriter); the desktop: none (a video goes in as it is, as on an Android phone that can't
 * compress it); tests: a fake.
 */
interface VideoPrep {
    /** The video at [path] (under the app's files): its own tags. */
    fun probe(path: String): VideoProbe

    /**
     * The video at [source] written again at [out] (both under the app's files) as an MPEG-4 file: H.264 at about
     * [bitrate] bit/s, its short side [shortSide] px (null: its own size), turned upright, HDR as ordinary colour, its
     * sound AAC at [audioBitrate] (none when it has none: [VideoProbe.hasAudio]). [onProgress] gets 0..1. Throws when the
     * phone can't (the caller then keeps the video as it is when it may); cancelling stops it and removes [out].
     */
    suspend fun transcode(source: String, out: String, probe: VideoProbe, bitrate: Int, shortSide: Int?, audioBitrate: Int, onProgress: (Float) -> Unit)
}

/** No video work (the desktop, previews): nothing is read and nothing written again. */
object NoVideoPrep : VideoPrep {
    override fun probe(path: String): VideoProbe = VideoProbe()

    override suspend fun transcode(source: String, out: String, probe: VideoProbe, bitrate: Int, shortSide: Int?, audioBitrate: Int, onProgress: (Float) -> Unit) =
        throw UnsupportedOperationException("This phone doesn't write videos again")
}

/** What notes need of the phone for their photos and videos. */
class MediaKit(
    val files: MediaFiles,
    val picker: MediaPicker = NoPicker,
    val frames: VideoFrames = VideoFrames { _, _ -> null },
    /** A note's videos made 720p as Android makes them (AVFoundation on iPhone). */
    val videos: VideoPrep = NoVideoPrep,
    /** The phone's video player under the app's own controls (AVPlayer on iPhone). */
    val playback: VideoPlayback = NoVideoPlayback,
) {
    /** Pictures read from the files, small enough for where they are drawn. */
    val pictures: Pictures = Pictures(this)

    companion object {
        /** Files in memory and no picker: tests and previews. */
        fun memory(picker: MediaPicker = NoPicker): MediaKit = MediaKit(MemoryMediaFiles(), picker)
    }
}

/** The phone's media, for every screen under the app. */
val LocalMediaKit = staticCompositionLocalOf { MediaKit.memory() }

/** A photo's or video's row by id (the trips' store), for the pictures drawn in notes and lists. */
val LocalMediaRows = compositionLocalOf<(String) -> Media?> { { null } }

/**
 * Where photos and videos are read, written and decoded: one at a time, off the main thread, so a few large photos never
 * sit in memory together.
 */
val MediaWork: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

/** The Android app's limits for what goes into a note (WriteScreen.MAX_PHOTOS; a video's: data/repo/VideoLimits). */
object MediaLimits {
    /** The most photos and videos picked at once. */
    const val PICKED = 20

    /** "This video is too long. Videos can be up to 30 minutes." (the Android app's words, JournalVideos.TOO_LONG). */
    val TOO_LONG = "This video is too long. Videos can be up to ${com.justdataplease.boringtravel.data.repo.VideoLimits.MAX_MS / 60_000} minutes."
}

/**
 * File types of journal media (the Android app's data/repo/MediaTypes, here as data/repo/MediaTypes.kt): the extension
 * a type gets on disk, and a picked file's type by its extension.
 */
object MediaTypes {
    const val JPEG = "image/jpeg"
    const val MP4 = "video/mp4"

    fun isVideo(mime: String?): Boolean = com.justdataplease.boringtravel.data.repo.MediaTypes.isVideo(mime)

    /** The photo types the app keeps as they are, the usual one for a video (mp4 when unknown), else jpg. */
    fun extension(mime: String?): String = com.justdataplease.boringtravel.data.repo.MediaTypes.extension(mime)

    /** The type of a file by its extension, as a picker names it ("IMG_0001.MOV" → video/quicktime). */
    fun ofExtension(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        "gif" -> "image/gif"
        "mov", "qt" -> "video/quicktime"
        "mp4", "m4v" -> "video/mp4"
        "3gp" -> "video/3gpp"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> JPEG
    }
}

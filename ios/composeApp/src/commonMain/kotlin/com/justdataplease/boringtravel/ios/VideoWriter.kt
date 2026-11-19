package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.music.MixedSound
import com.justdataplease.boringtravel.compat.graphics.Bitmap

/*
 * The trip video's MP4 (round 20), the phone's part: H.264 pictures and AAC sound written into an MPEG-4 file. Android
 * encodes with MediaCodec into MediaMuxer, then puts the sound under the finished picture (ClipMusic.attach); this app
 * does the same in two steps through this small interface: the pictures into one or more picture-only files
 * ([PictureFile]; a new one starts each time the app comes back to the screen, since an iPhone's video encoder stops
 * while the app is away), then [VideoWriter.join] copies their pictures unchanged into the finished file beside the
 * sound encoded to AAC. iPhone: AVAssetWriter (iosMain/…/IosVideoWriter.kt); desktop: a writer that records what it
 * was given and writes a Motion-JPEG AVI (desktopMain/…/DesktopVideoWriter.kt); tests: the recording writer.
 *
 * Everything else (which frame when, the sound's samples, the parts, pausing and going on, the progress, the files'
 * names and places) is common Kotlin: ios/ClipExporter.kt.
 */

/**
 * How the pictures are encoded, as Android's Mp4Encoder sets its encoder: [width] × [height] at [fps], about
 * [bitRate] bits a second, a key frame every [keyFrameFrames] frames (2 s).
 */
data class VideoSpec(val width: Int, val height: Int, val fps: Int, val bitRate: Int, val keyFrameFrames: Int)

/** One picture-only file being written. Its methods are called from one coroutine, never on the main thread. */
interface PictureFile {
    /**
     * Encodes [frame] (a bitmap of the spec's size) as picture [index] of this file (shown at index / fps from the
     * file's start), waiting while the encoder is busy. Throws [VideoWriteFailed] when the writer failed.
     */
    suspend fun append(frame: Bitmap, index: Int)

    /** Ends the file after [frames] pictures; true when it is complete and can be read. */
    suspend fun finish(frames: Int): Boolean

    /** Stops without finishing and deletes the file. */
    fun cancel()
}

/** A finished picture-only file: [frames] pictures from frame [firstFrame] of the clip. */
data class PicturePart(val path: String, val firstFrame: Int, val frames: Int)

/** The phone's writer failed ([reason] says what it told). */
class VideoWriteFailed(val reason: String) : Exception(reason)

/** The phone's video writer. Paths are under the app's files (MediaFiles). */
interface VideoWriter {
    /** A new picture-only MP4 at [path] (what is there is replaced). Throws [VideoWriteFailed] when it can't start. */
    fun openPicture(path: String, spec: VideoSpec): PictureFile

    /**
     * Writes the finished MP4 at [path]: every picture of [parts], in order, copied as they are (each part's pictures
     * moved to its place in the clip: [PicturePart.firstFrame] / fps), and [sound] (null: none) encoded to AAC at
     * ClipMusic.BIT_RATE, cut at the last picture's end. [progress] gets 0–1 as the sound goes in; [stillWanted] throws
     * to stop. Throws [VideoWriteFailed] when it fails (the file is then deleted).
     */
    suspend fun join(path: String, spec: VideoSpec, parts: List<PicturePart>, sound: MixedSound?, progress: (Float) -> Unit, stillWanted: () -> Unit)

    /** Bytes free for the app's files, or null when the phone can't say. */
    fun freeBytes(): Long?
}

/** A phone that can't write videos (previews and tests that never export): every export fails at once. */
object NoVideoWriter : VideoWriter {
    override fun openPicture(path: String, spec: VideoSpec): PictureFile = throw VideoWriteFailed("This phone can't write videos")

    override suspend fun join(path: String, spec: VideoSpec, parts: List<PicturePart>, sound: MixedSound?, progress: (Float) -> Unit, stillWanted: () -> Unit) =
        throw VideoWriteFailed("This phone can't write videos")

    override fun freeBytes(): Long? = null
}

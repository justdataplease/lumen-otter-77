package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.staticCompositionLocalOf
import com.justdataplease.boringtravel.clip.ClipTarget
import com.justdataplease.boringtravel.clip.DecodedVideo
import com.justdataplease.boringtravel.clip.music.SoundDecoder

/*
 * What the trip video's editor needs of the phone (round 19), each behind a small interface: the bundled music played
 * (Android: MediaPlayer), a voice line recorded (MediaRecorder), and a phone video's length and pictures
 * (MediaMetadataRetriever, MediaCodec). iPhone: iosMain/…/IosClipDevice.kt (AVAudioPlayer, AVAudioRecorder, the audio
 * session, AVURLAsset, AVAssetImageGenerator); desktop: stand-ins that make no sound and draw a video's pictures
 * (desktopMain/…/DesktopClipDevice.kt); tests: fakes that record what they were asked.
 *
 * Since round 20 also the export's: the MP4's writer ([VideoWriter]: AVAssetWriter), a sound file decoded ([SoundDecoder]:
 * AVAssetReader), a phone video's every picture in order ([ClipVideoFiles.open]: AVAssetReader), and where a finished
 * video goes ([VideoShare]: the share sheet, Photos, the apps it goes straight to).
 */

/** One bundled track being played (iPhone: an AVAudioPlayer). */
interface TrackPlayer {
    /** Where it is in the track, in ms. */
    val positionMs: Int

    val isPlaying: Boolean

    /** Plays from [fromMs] into the track. */
    fun play(fromMs: Int)

    /** Moves to [ms] into the track, playing on if it played. */
    fun seekTo(ms: Int)

    fun pause()

    /** Stops for good and lets go of the track. */
    fun release()
}

/** The bundled tracks' sound. */
interface ClipSounds {
    /**
     * A player of the track at [uri] (the app's own file: Compose's resources, `files/music/<id>.m4a`), ready to play;
     * [onEnd] is called on the main thread when it plays to its end. Null when it can't be opened.
     */
    fun open(uri: String, onEnd: () -> Unit = {}): TrackPlayer?
}

/** The microphone, for a voice line. */
interface Microphone {
    /** Whether the app may use it (dictation's [MicAccess]: the same question to the phone). */
    val access: MicAccess

    /** The phone's own question about the microphone (once ever); [done] gets the answer on the main thread. */
    fun ask(done: (Boolean) -> Unit)

    /**
     * Starts recording into the file at [absolutePath] (AAC in an MPEG-4 file, 44.1 kHz mono at 96 kbps, as Android
     * records a line), for at most [maxMs]; null when the microphone couldn't start.
     */
    fun record(absolutePath: String, maxMs: Long): MicRecording?
}

/** A recording going on. */
interface MicRecording {
    /** Stops and keeps the file; false when the recording failed. */
    fun stop(): Boolean

    /** Stops and deletes the file. */
    fun cancel()
}

/** What a phone video says about itself: its length and its picture's size as it is shown (turned as it was held). */
data class VideoFacts(val durationMs: Long, val width: Int, val height: Int)

/** Phone videos under the app's files. */
interface ClipVideoFiles {
    /** The video at [path]: its length and size, or null when it has no picture track the phone can play. */
    fun probe(path: String): VideoFacts?

    /**
     * The video at [path]'s pictures at [timesUs] (into the file), each turned the way the video was held and at least
     * big enough to fill [width] × [height] when cropped, as an encoded picture (JPEG or PNG) or null when there is none
     * at that time. [each] gets each one in order with its index and says whether to go on.
     */
    fun pictures(path: String, timesUs: LongArray, width: Int, height: Int, each: (Int, ByteArray?) -> Boolean)

    /**
     * The video at [path] read forward from [fromUs] for [lengthUs] (and a little past it), every picture in order, as
     * the export draws it (Android's VideoDecoder: MediaCodec; iPhone: AVAssetReader); null when it can't be read.
     */
    fun open(path: String, fromUs: Long, lengthUs: Long): DecodedVideo? = null
}

/**
 * Where a finished trip video goes from the Export sheet (the Android app's ClipExport.shareIntent, Sharing.saveVideo
 * and ClipTargets): the share sheet, a copy in the phone's photo library, and the apps it goes to straight.
 */
interface VideoShare {
    /** The phone's share sheet with the video at [path] (under the app's files); false when it can't show. */
    fun share(path: String): Boolean

    /**
     * A copy of the video at [path] in the phone's photo library (iPhone: Photos, add-only access asked at that moment);
     * [done] says whether it was kept, on the main thread.
     */
    fun saveToPhotos(path: String, done: (Boolean) -> Unit)

    /** Whether [target]'s app is on this phone. */
    fun installed(target: ClipTarget): Boolean

    /** Hands the video at [path] to [target]'s app; false when it isn't there or can't take it. */
    fun sendTo(target: ClipTarget, path: String): Boolean
}

/** No share sheet, no photo library, no apps (previews, tests that don't share). */
object NoVideoShare : VideoShare {
    override fun share(path: String): Boolean = false
    override fun saveToPhotos(path: String, done: (Boolean) -> Unit) = done(false)
    override fun installed(target: ClipTarget): Boolean = false
    override fun sendTo(target: ClipTarget, path: String): Boolean = false
}

/**
 * The phone's sound, microphone and videos for the trip video's editor, and since round 20 its export: the MP4's
 * [writer], the sound files' [decoder] and the finished video's [share].
 */
class ClipDevice(
    val sounds: ClipSounds,
    val microphone: Microphone,
    val videos: ClipVideoFiles,
    val writer: VideoWriter = NoVideoWriter,
    val decoder: SoundDecoder = SoundDecoder.NONE,
    val share: VideoShare = NoVideoShare,
) {
    companion object {
        /** No sound, no microphone, no pictures of videos, no export (a phone with none of them). */
        val SILENT = ClipDevice(NoSounds, NoMicrophone, NoVideoFiles)
    }
}

/** The phone's sound, microphone and videos, for the trip video's editor. */
val LocalClipDevice = staticCompositionLocalOf { ClipDevice.SILENT }

object NoSounds : ClipSounds {
    override fun open(uri: String, onEnd: () -> Unit): TrackPlayer? = null
}

object NoMicrophone : Microphone {
    override val access: MicAccess get() = MicAccess.Refused

    override fun ask(done: (Boolean) -> Unit) = done(false)

    override fun record(absolutePath: String, maxMs: Long): MicRecording? = null
}

object NoVideoFiles : ClipVideoFiles {
    override fun probe(path: String): VideoFacts? = null

    override fun pictures(path: String, timesUs: LongArray, width: Int, height: Int, each: (Int, ByteArray?) -> Boolean) {
        for (i in timesUs.indices) if (!each(i, null)) return
    }
}

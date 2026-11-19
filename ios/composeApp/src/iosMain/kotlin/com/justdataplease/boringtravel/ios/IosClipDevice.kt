@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.justdataplease.boringtravel.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGSizeApplyAffineTransform
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The trip video's editor on iPhone (round 19), behind the small interfaces common code uses ([ClipSounds],
 * [Microphone], [ClipVideoFiles]). Apple's APIs called here: AVFAudio (AVAudioPlayer and its delegate, AVAudioRecorder,
 * AVAudioSession's category, activation and record permission), AVFoundation (AVURLAsset, AVAssetTrack,
 * AVAssetImageGenerator), CoreMedia and CoreGraphics for times and sizes, UIKit for a picture as a JPEG, Foundation for
 * files. Only type-checked on Windows: none of it has run yet.
 */

/**
 * The editor's sound, microphone and videos on this phone, and since round 20 the export's writer, sound decoder and
 * sharing (IosVideoWriter.kt); [files] is the app's own folder (Application Support).
 */
fun iosClipDevice(files: IosMediaFiles): ClipDevice {
    val (writer, decoder, share) = iosExportDevice(files)
    return ClipDevice(IosClipSounds(), IosMicrophone(), IosClipVideoFiles(files), writer, decoder, share)
}

private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { block() }

/** The audio session for playing the bundled music: sound even with the phone on silent, as a video someone plays. */
private fun playbackSession() {
    val session = AVAudioSession.sharedInstance()
    session.setCategory(AVAudioSessionCategoryPlayback, error = null)
    session.setActive(true, error = null)
}

/** The bundled tracks through AVAudioPlayer (the file in the app's bundle, opened whole, ready at once). */
class IosClipSounds : ClipSounds {
    override fun open(uri: String, onEnd: () -> Unit): TrackPlayer? {
        val url = NSURL.URLWithString(uri) ?: return null
        val player = AVAudioPlayer(contentsOfURL = url, error = null)
        if (!player.prepareToPlay()) return null
        return IosTrackPlayer(player, onEnd)
    }
}

/** Tells the track's end; AVAudioPlayer keeps only a weak hold on it, so [IosTrackPlayer] keeps it. */
private class TrackEnd(private val onEnd: () -> Unit) : NSObject(), AVAudioPlayerDelegateProtocol {
    override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
        onMain(onEnd)
    }
}

private class IosTrackPlayer(private val player: AVAudioPlayer, onEnd: () -> Unit) : TrackPlayer {
    private val end = TrackEnd(onEnd)
    private var released = false

    init {
        player.delegate = end
    }

    override val positionMs: Int get() = (player.currentTime * 1000).roundToInt()

    override val isPlaying: Boolean get() = !released && player.playing

    override fun play(fromMs: Int) {
        if (released) return
        playbackSession()
        player.currentTime = fromMs.coerceAtLeast(0) / 1000.0
        player.play()
    }

    override fun seekTo(ms: Int) {
        if (!released) player.currentTime = ms.coerceAtLeast(0) / 1000.0
    }

    override fun pause() {
        if (!released) player.pause()
    }

    override fun release() {
        if (released) return
        released = true
        player.stop()
        player.delegate = null
    }
}

/** The microphone through AVAudioRecorder, asked for with AVAudioSession's record permission (the same as dictation's). */
class IosMicrophone : Microphone {
    override val access: MicAccess
        get() = when (AVAudioSession.sharedInstance().recordPermission) {
            AVAudioSessionRecordPermissionGranted -> MicAccess.Allowed
            AVAudioSessionRecordPermissionDenied -> MicAccess.Refused
            else -> MicAccess.NotAsked
        }

    override fun ask(done: (Boolean) -> Unit) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted -> onMain { done(granted) } }
    }

    override fun record(absolutePath: String, maxMs: Long): MicRecording? {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, mode = AVAudioSessionModeDefault, options = AVAudioSessionCategoryOptionDefaultToSpeaker, error = null)
        if (!session.setActive(true, error = null)) return null
        // Android's line: AAC in an MPEG-4 file, 44.1 kHz mono at 96 kbps.
        val settings: Map<Any?, Any?> = mapOf(
            AVFormatIDKey to kAudioFormatMPEG4AAC.toInt(),
            AVSampleRateKey to 44_100.0,
            AVNumberOfChannelsKey to 1,
            AVEncoderBitRateKey to 96_000,
        )
        val recorder = AVAudioRecorder(uRL = NSURL.fileURLWithPath(absolutePath), settings = settings, error = null)
        if (!recorder.prepareToRecord()) return null
        if (!recorder.recordForDuration(maxMs / 1000.0)) return null
        return IosMicRecording(recorder)
    }
}

private class IosMicRecording(private val recorder: AVAudioRecorder) : MicRecording {
    override fun stop(): Boolean {
        recorder.stop()
        playbackSession()
        return true
    }

    override fun cancel() {
        recorder.stop()
        recorder.deleteRecording()
        playbackSession()
    }
}

/**
 * Phone videos under the app's files: their length and size from AVURLAsset, their pictures for the preview from
 * AVAssetImageGenerator, and for the export every picture in order from AVAssetReader ([open], round 20).
 */
class IosClipVideoFiles(private val files: IosMediaFiles) : ClipVideoFiles {
    override fun open(path: String, fromUs: Long, lengthUs: Long): com.justdataplease.boringtravel.clip.DecodedVideo? =
        iosDecodedVideo(files.absolute(path), fromUs, lengthUs)

    override fun probe(path: String): VideoFacts? {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(files.absolute(path)), options = null)
        val track = asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack ?: return null
        val seconds = CMTimeGetSeconds(asset.duration)
        if (!seconds.isFinite() || seconds <= 0) return null
        var w = 0
        var h = 0
        CGSizeApplyAffineTransform(track.naturalSize, track.preferredTransform).useContents {
            w = abs(width).roundToInt()
            h = abs(height).roundToInt()
        }
        return VideoFacts((seconds * 1000).toLong(), w, h)
    }

    override fun pictures(path: String, timesUs: LongArray, width: Int, height: Int, each: (Int, ByteArray?) -> Boolean) {
        val facts = probe(path)
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(files.absolute(path)), options = null)
        val generator = AVAssetImageGenerator(asset = asset)
        // Turned the way the video was held, and only as big as it must be to fill the frame once cropped.
        generator.appliesPreferredTrackTransform = true
        val (bw, bh) = if (facts != null && facts.width > 0 && facts.height > 0) {
            val scale = max(width.toDouble() / facts.width, height.toDouble() / facts.height)
            ceil(facts.width * scale) to ceil(facts.height * scale)
        } else {
            max(width, height) * 2.0 to max(width, height) * 2.0
        }
        generator.maximumSize = CGSizeMake(bw, bh)
        // The picture at exactly the time asked, as Android's decoder (MediaCodec, every frame decoded forward) gives
        // it: no tolerance either way (the second parity round; it was a sixteenth of a second).
        generator.requestedTimeToleranceBefore = CMTimeMake(value = 0, timescale = 1)
        generator.requestedTimeToleranceAfter = CMTimeMake(value = 0, timescale = 1)
        for (i in timesUs.indices) {
            val jpeg = autoreleasepool {
                val frame = generator.copyCGImageAtTime(CMTimeMake(value = timesUs[i], timescale = 1_000_000), actualTime = null, error = null)
                frame?.let {
                    val image = UIImage.imageWithCGImage(it)
                    CGImageRelease(it)
                    UIImageJPEGRepresentation(image, 0.9)?.toByteArray()
                }
            }
            if (!each(i, jpeg)) return
        }
    }
}

/** The names of the files in the folder at [absolute] (its folders left out). */
internal fun listFiles(manager: NSFileManager, absolute: String): List<String> =
    (manager.contentsOfDirectoryAtPath(absolute, error = null) ?: emptyList<Any?>()).mapNotNull { it as? String }.filter { name ->
        manager.attributesOfItemAtPath("$absolute/$name", error = null)?.get(NSFileType) == NSFileTypeRegular
    }

/** When the file at [absolute] was last written, ms since 1970. */
internal fun modifiedAt(manager: NSFileManager, absolute: String): Long? =
    (manager.attributesOfItemAtPath(absolute, error = null)?.get(NSFileModificationDate) as? NSDate)?.let { (it.timeIntervalSince1970 * 1000).toLong() }

private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.compat.diag
import com.justdataplease.boringtravel.data.repo.VideoTags
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderStatusReading
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetReaderVideoCompositionOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataCommonKeyLocation
import platform.AVFoundation.AVMetadataItem
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVMutableVideoCompositionInstruction
import platform.AVFoundation.AVMutableVideoCompositionLayerInstruction
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoColorPrimariesKey
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoColorPropertiesKey
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoExpectedSourceFrameRateKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoProfileLevelH264HighAutoLevel
import platform.AVFoundation.AVVideoProfileLevelKey
import platform.AVFoundation.AVVideoTransferFunctionKey
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.AVVideoYCbCrMatrixKey
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.AVFoundation.commonKey
import platform.AVFoundation.commonMetadata
import platform.AVFoundation.setColorPrimaries
import platform.AVFoundation.setColorTransferFunction
import platform.AVFoundation.setColorYCbCrMatrix
import platform.AVFoundation.creationDate
import platform.AVFoundation.dateValue
import platform.AVFoundation.duration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.stringValue
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGSizeApplyAffineTransform
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * A note's video made ready on iPhone as the Android app makes it (the second parity round; Android: JournalVideos with
 * MediaMetadataRetriever and Media3's Transformer), behind [VideoPrep]; the rules (the limits, the bit rate by length,
 * what happens when it can't, the messages) are common code (ios/MediaImport.kt). Apple's calls here, only type-checked
 * on Windows:
 *
 * - **What it says about itself:** AVURLAsset's duration, its video track's natural size turned by its preferred
 *   transform, its creation date, its common "location" metadata (ISO 6709, Android's METADATA_KEY_LOCATION) and
 *   whether it has a sound track.
 * - **Written again:** AVAssetReader with an AVAssetReaderVideoCompositionOutput (an AVMutableVideoComposition that
 *   turns each picture upright by the preferred transform, scales it to the short side asked for and renders it in
 *   Rec. 709, so an HDR video becomes ordinary colour, Android's HDR_MODE_TONE_MAP_HDR_TO_SDR) and an
 *   AVAssetReaderTrackOutput for the sound as 16-bit LPCM; AVAssetWriter with an H.264 input (High where the phone
 *   takes it, the average bit rate asked, Rec. 709) and an AAC input at the bit rate asked, in Apple's documented order
 *   (as IosVideoWriter.kt): inputs added, startWriting, the session at zero, each sample appended only when its input
 *   is ready, markAsFinished, finishWriting, the status read. The writer's pixel-buffer input path is the one the Mac
 *   ran green with the trip video (no passthrough input, which the Simulator refused).
 */

/** A note's videos on this phone, under the app's files [files]. */
class IosVideoPrep(private val files: IosMediaFiles) : VideoPrep {
    private val manager = NSFileManager.defaultManager

    override fun probe(path: String): VideoProbe {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(files.absolute(path)), options = null)
        val seconds = CMTimeGetSeconds(asset.duration)
        var w = 0
        var h = 0
        (asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack)?.let { track ->
            CGSizeApplyAffineTransform(track.naturalSize, track.preferredTransform).useContents {
                w = abs(width).roundToInt()
                h = abs(height).roundToInt()
            }
        }
        // Containers write 1904 or 1970 when nobody set a date (Android's VideoTags leaves those out).
        val takenAt = asset.creationDate?.dateValue?.timeIntervalSince1970?.let { (it * 1000).toLong() }?.takeIf { it >= EARLIEST_MS }
        val location = asset.commonMetadata.mapNotNull { it as? AVMetadataItem }
            .firstOrNull { it.commonKey == AVMetadataCommonKeyLocation }?.stringValue
        val place = VideoTags.location(location)
        return VideoProbe(
            durationMs = if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else null,
            width = w, height = h, takenAt = takenAt, lat = place?.first, lng = place?.second,
            hasAudio = asset.tracksWithMediaType(AVMediaTypeAudio ?: "soun").isNotEmpty(),
        )
    }

    /**
     * Written again ([writeOnce]) with the phone's time asked for while the app is away (UIApplication's background
     * task). An iPhone's video encoder stops when the app leaves the screen: a try that failed then is made again from
     * the start once the app is back (Android's Transformer goes on; the screen stays on meanwhile, as on Android).
     */
    override suspend fun transcode(
        source: String, out: String, probe: VideoProbe, bitrate: Int, shortSide: Int?, audioBitrate: Int, onProgress: (Float) -> Unit,
    ) {
        val ticket = withContext(Dispatchers.Main) { IosBackgroundWork().beginBackgroundWork("Preparing the video") }
        try {
            var tries = 0
            while (true) {
                try {
                    writeOnce(source, out, probe, bitrate, shortSide, audioBitrate, onProgress)
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (++tries > MAX_TRIES || onScreen()) throw e
                    diag("video prep: stopped while the app was away (${e.message}); again once it is back")
                    while (!onScreen()) delay(500)
                    onProgress(0f)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { ticket.endBackgroundWork() }
        }
    }

    /** Whether the app is in front now (asked on the main thread). */
    private suspend fun onScreen(): Boolean = withContext(Dispatchers.Main) {
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
    }

    private suspend fun writeOnce(
        source: String, out: String, probe: VideoProbe, bitrate: Int, shortSide: Int?, audioBitrate: Int, onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val absolute = files.absolute(out)
        files.folderFor(absolute)
        manager.removeItemAtPath(absolute, error = null)
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(files.absolute(source)), options = null)
        val video = asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack
            ?: throw VideoWriteFailed("The video has no pictures")
        val audio = if (probe.hasAudio) asset.tracksWithMediaType(AVMediaTypeAudio ?: "soun").firstOrNull() as? AVAssetTrack else null
        val durationS = CMTimeGetSeconds(asset.duration).takeIf { it.isFinite() && it > 0 } ?: 1.0

        // 1. The size as shown, made smaller to the short side asked (even, as H.264 takes it).
        var shownW = 0.0
        var shownH = 0.0
        CGSizeApplyAffineTransform(video.naturalSize, video.preferredTransform).useContents {
            shownW = abs(width)
            shownH = abs(height)
        }
        if (shownW <= 0 || shownH <= 0) throw VideoWriteFailed("The video has no size")
        val scale = shortSide?.let { it / minOf(shownW, shownH) }?.coerceAtMost(1.0) ?: 1.0
        val outW = even(shownW * scale)
        val outH = even(shownH * scale)
        val fps = video.nominalFrameRate.toDouble().takeIf { it.isFinite() && it > 0 }?.roundToInt()?.coerceIn(1, 60) ?: 30

        // 2. The reader: the pictures upright, scaled and in Rec. 709; the sound as LPCM.
        val reader = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVAssetReader.assetReaderWithAsset(asset, error = error.ptr) ?: throw failure("The video couldn't be read", error.value)
        }
        val composition = AVMutableVideoComposition.videoComposition()
        composition.setRenderSize(CGSizeMake(outW.toDouble(), outH.toDouble()))
        composition.setFrameDuration(CMTimeMake(1, fps))
        composition.setColorPrimaries(AVVideoColorPrimaries_ITU_R_709_2)
        composition.setColorTransferFunction(AVVideoTransferFunction_ITU_R_709_2)
        composition.setColorYCbCrMatrix(AVVideoYCbCrMatrix_ITU_R_709_2)
        val instruction = AVMutableVideoCompositionInstruction.videoCompositionInstruction()
        instruction.setTimeRange(CMTimeRangeMake(CMTimeMake(0, 600), asset.duration))
        val layer = AVMutableVideoCompositionLayerInstruction.videoCompositionLayerInstructionWithAssetTrack(video)
        val k = outW / shownW
        layer.setTransform(CGAffineTransformConcat(video.preferredTransform, CGAffineTransformMakeScale(k, k)), atTime = CMTimeMake(0, 600))
        instruction.setLayerInstructions(listOf(layer))
        composition.setInstructions(listOf(instruction))
        val pictures = AVAssetReaderVideoCompositionOutput.assetReaderVideoCompositionOutputWithVideoTracks(
            listOf(video), videoSettings = mapOf<Any?, Any?>(cfKey(kCVPixelBufferPixelFormatTypeKey) to kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange.toInt()),
        )
        pictures.videoComposition = composition
        pictures.alwaysCopiesSampleData = false
        if (!reader.canAddOutput(pictures)) throw failure("The video's pictures couldn't be read", reader.error)
        reader.addOutput(pictures)
        val sound = audio?.let { track ->
            AVAssetReaderTrackOutput(
                track = track,
                outputSettings = mapOf<Any?, Any?>(
                    AVFormatIDKey to kAudioFormatLinearPCM.toInt(),
                    AVLinearPCMBitDepthKey to 16,
                    AVLinearPCMIsFloatKey to false,
                    AVLinearPCMIsBigEndianKey to false,
                    AVLinearPCMIsNonInterleaved to false,
                    AVSampleRateKey to SAMPLE_RATE,
                    AVNumberOfChannelsKey to CHANNELS,
                ),
            ).also { it.alwaysCopiesSampleData = false }
        }?.takeIf { reader.canAddOutput(it) }
        sound?.let { reader.addOutput(it) }

        // 3. The writer: H.264 at the bit rate asked, AAC for the sound.
        val writer = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(absolute), fileType = AVFileTypeMPEG4, error = error.ptr)
                ?: throw failure("The video's file couldn't be opened", error.value)
        }
        writer.shouldOptimizeForNetworkUse = true
        val settings = videoSettings(outW, outH, fps, bitrate, high = true).takeIf { writer.canApplyOutputSettings(it, forMediaType = AVMediaTypeVideo) }
            ?: videoSettings(outW, outH, fps, bitrate, high = false)
        val pictureInput = AVAssetWriterInput(mediaType = AVMediaTypeVideo, outputSettings = settings)
        pictureInput.expectsMediaDataInRealTime = false
        if (!writer.canAddInput(pictureInput)) throw failure("The video writer won't take these pictures", writer.error)
        writer.addInput(pictureInput)
        val soundInput = sound?.let {
            AVAssetWriterInput(
                mediaType = AVMediaTypeAudio,
                outputSettings = mapOf<Any?, Any?>(
                    AVFormatIDKey to kAudioFormatMPEG4AAC.toInt(),
                    AVSampleRateKey to SAMPLE_RATE,
                    AVNumberOfChannelsKey to CHANNELS,
                    AVEncoderBitRateKey to audioBitrate,
                ),
            ).also { input -> input.expectsMediaDataInRealTime = false }
        }?.takeIf { writer.canAddInput(it) }
        soundInput?.let { writer.addInput(it) }
        // The sound read but not written (the writer refused AAC) is left out rather than failing the video.
        if (sound != null && soundInput == null) diag("video prep: the writer took no AAC input; the video goes without sound")

        // 4. Each sample moved across when its input takes more; the progress by the pictures' time.
        try {
            if (!reader.startReading()) throw failure("The video couldn't be read", reader.error)
            if (!writer.startWriting()) throw failure("The video writer didn't start", writer.error)
            writer.startSessionAtSourceTime(CMTimeMake(0, 600))
            var picturesDone = false
            var soundDone = soundInput == null
            while (!picturesDone || !soundDone) {
                coroutineContext.ensureActive()
                var moved = false
                if (!picturesDone && pictureInput.readyForMoreMediaData) {
                    moved = true
                    val sample = pictures.copyNextSampleBuffer()
                    if (sample == null) {
                        pictureInput.markAsFinished()
                        picturesDone = true
                    } else {
                        try {
                            val at = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
                            if (at.isFinite()) onProgress((at / durationS).toFloat().coerceIn(0f, 1f))
                            if (!pictureInput.appendSampleBuffer(sample)) throw failure("The video writer refused a picture", writer.error)
                        } finally {
                            CFRelease(sample)
                        }
                    }
                }
                if (!soundDone && soundInput != null && sound != null && soundInput.readyForMoreMediaData) {
                    moved = true
                    val sample = sound.copyNextSampleBuffer()
                    if (sample == null) {
                        soundInput.markAsFinished()
                        soundDone = true
                    } else {
                        try {
                            if (!soundInput.appendSampleBuffer(sample)) throw failure("The video writer refused the sound", writer.error)
                        } finally {
                            CFRelease(sample)
                        }
                    }
                }
                if (reader.status == AVAssetReaderStatusFailed) throw failure("The video couldn't be read", reader.error)
                if (writer.status != AVAssetWriterStatusWriting) throw failure("The video writer stopped", writer.error)
                if (!moved) delay(2)
            }
            finish(writer)
            if (writer.status != AVAssetWriterStatusCompleted) throw failure("The video writer didn't finish", writer.error)
            onProgress(1f)
        } catch (e: Throwable) {
            if (reader.status == AVAssetReaderStatusReading) reader.cancelReading()
            if (writer.status == AVAssetWriterStatusWriting) writer.cancelWriting()
            manager.removeItemAtPath(absolute, error = null)
            throw e
        }
    }

    /** Android's Transformer settings: H.264 at [bitrate], High where the phone takes it, in Rec. 709. */
    private fun videoSettings(w: Int, h: Int, fps: Int, bitrate: Int, high: Boolean): Map<Any?, Any?> {
        val compression = mutableMapOf<Any?, Any?>(
            AVVideoAverageBitRateKey to bitrate,
            AVVideoExpectedSourceFrameRateKey to fps,
        )
        if (high) compression[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel
        return mapOf(
            AVVideoCodecKey to AVVideoCodecTypeH264,
            AVVideoWidthKey to w,
            AVVideoHeightKey to h,
            AVVideoCompressionPropertiesKey to compression,
            AVVideoColorPropertiesKey to mapOf<Any?, Any?>(
                AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey to AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_709_2,
            ),
        )
    }

    /** [v] rounded to an even number of pixels, at least 2. */
    private fun even(v: Double): Int = ((v / 2).roundToInt() * 2).coerceAtLeast(2)

    private companion object {
        /** Before this a date is the container's "unset" (Android's VideoTags.EARLIEST_MS). */
        const val EARLIEST_MS = 365L * 24 * 3600 * 1000
        const val SAMPLE_RATE = 44_100.0
        const val CHANNELS = 2

        /** Tries made again after the app was away, at most. */
        const val MAX_TRIES = 3
    }
}

/** A Core Foundation key as the NSString Objective-C dictionaries take (CoreVideo's keys are CFStrings). */
private fun cfKey(key: CFStringRef?): NSString = interpretObjCPointer(requireNotNull(key).rawValue)

private fun failure(what: String, error: NSError?): VideoWriteFailed = VideoWriteFailed(what + (error?.localizedDescription?.let { ": $it" } ?: ""))

/** Waits for the writer's completion handler (its own queue calls it). */
private suspend fun finish(writer: AVAssetWriter) = suspendCancellableCoroutine { going ->
    writer.finishWritingWithCompletionHandler { going.resume(Unit) }
}

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.justdataplease.boringtravel.ios

import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeRange
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportPresetPassthrough
import platform.AVFoundation.AVAssetExportSessionStatusWaiting
import platform.AVFoundation.AVAssetExportSessionStatusExporting
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVFileTypeAppleM4A
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.duration
import platform.CoreMedia.CMTimeMinimum
import kotlinx.cinterop.CValue
import com.justdataplease.boringtravel.compat.diag
import com.justdataplease.boringtravel.clip.ClipTarget
import com.justdataplease.boringtravel.clip.DecodedVideo
import com.justdataplease.boringtravel.clip.music.ClipMusic
import com.justdataplease.boringtravel.clip.music.MixedSound
import com.justdataplease.boringtravel.clip.music.MusicMix
import com.justdataplease.boringtravel.clip.music.Pcm
import com.justdataplease.boringtravel.clip.music.SoundDecoder
import com.justdataplease.boringtravel.compat.graphics.Bitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.readValue
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderStatusReading
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoAllowFrameReorderingKey
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoExpectedSourceFrameRateKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoMaxKeyFrameIntervalDurationKey
import platform.AVFoundation.AVVideoMaxKeyFrameIntervalKey
import platform.AVFoundation.AVVideoProfileLevelH264HighAutoLevel
import platform.AVFoundation.AVVideoProfileLevelKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracksWithMediaType
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsPacked
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsSignedInteger
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreMedia.CMAudioFormatDescriptionCreate
import platform.CoreMedia.CMAudioFormatDescriptionGetStreamBasicDescription
import platform.CoreMedia.CMAudioFormatDescriptionRefVar
import platform.CoreMedia.CMAudioSampleBufferCreateReadyWithPacketDescriptions
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferCreateWithMemoryBlock
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMBlockBufferRefVar
import platform.CoreMedia.CMBlockBufferReplaceDataBytes
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMSampleBufferCreateCopyWithNewTiming
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetFormatDescription
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferGetSampleTimingInfoArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.CMTimeAdd
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMBlockBufferAssureMemoryNowFlag
import platform.CoreMedia.kCMTimeFlags_Valid
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferPoolCreatePixelBuffer
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferHeightKey
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelBufferWidthKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The trip video's MP4 on iPhone (round 20), behind the small interfaces common code uses ([VideoWriter],
 * [SoundDecoder], [DecodedVideo], [VideoShare]); everything else is common Kotlin (ios/ClipExporter.kt). Apple's APIs
 * called here: AVFoundation (AVAssetWriter, AVAssetWriterInput, AVAssetWriterInputPixelBufferAdaptor, AVAssetReader,
 * AVAssetReaderTrackOutput, AVURLAsset, AVAssetTrack's preferredTransform), CoreVideo (the adaptor's CVPixelBufferPool,
 * locking a pixel buffer's memory), CoreMedia (CMTime, sample buffers, their timing, block buffers, an LPCM format
 * description), CoreAudioTypes (the LPCM description), Photos (add-only access, a video asset), UIKit (canOpenURL),
 * Foundation (files, free space). Only type-checked on Windows: none of it has run yet.
 *
 * Apple's documented order for AVAssetWriter, followed by both writers below:
 *  1. AVAssetWriter(URL, fileType), its inputs made and added (canAddInput, addInput) before writing starts;
 *  2. startWriting, then startSessionAtSourceTime at time zero;
 *  3. each sample appended only when its input isReadyForMoreMediaData (waited for, off the main thread; the writer's
 *     status checked meanwhile, so a failed writer never leaves the loop waiting);
 *  4. markAsFinished on every input, endSessionAtSourceTime at the last picture's end;
 *  5. finishWritingWithCompletionHandler, then the status read (Completed, else the file is deleted).
 * Every pixel buffer from the pool and every sample buffer made or copied here is released once handed over (the
 * writer keeps its own reference); every reader is cancelled when left early.
 */

/** The export's phone side on this iPhone, in the app's files [files]. */
fun iosExportDevice(files: IosMediaFiles): Triple<VideoWriter, SoundDecoder, VideoShare> =
    Triple(IosVideoWriter(files), IosSoundDecoder(), IosVideoShare(files))

/** A Core Foundation key as the NSString Objective-C dictionaries take (CoreVideo's keys are CFStrings). */
private fun cfKey(key: CFStringRef?): NSString = interpretObjCPointer(requireNotNull(key).rawValue)

private fun failure(what: String, error: NSError?): VideoWriteFailed = VideoWriteFailed(what + (error?.localizedDescription?.let { ": $it" } ?: ""))

/** Waits for the writer's completion handler (the writer's own queue calls it), off the main thread. */
private suspend fun finishWriting(writer: AVAssetWriter) = suspendCancellableCoroutine { going ->
    writer.finishWritingWithCompletionHandler { going.resume(Unit) }
}

/** Waits until [input] takes more, or throws when [writer] stopped (failed or cancelled). */
private suspend fun waitReady(writer: AVAssetWriter, input: AVAssetWriterInput) {
    while (!input.readyForMoreMediaData) {
        if (writer.status != AVAssetWriterStatusWriting) throw failure("The video writer stopped", writer.error)
        delay(2)
    }
    if (writer.status != AVAssetWriterStatusWriting) throw failure("The video writer stopped", writer.error)
}

/** H.264 and AAC into MPEG-4 files with AVAssetWriter, under the app's files. */
class IosVideoWriter(private val files: IosMediaFiles) : VideoWriter {
    private val manager = NSFileManager.defaultManager

    /** A writer for a new file at [absolute] (what is there is removed: AVAssetWriter won't write over a file). */
    private fun writerAt(absolute: String): AVAssetWriter {
        files.folderFor(absolute)
        manager.removeItemAtPath(absolute, error = null)
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(absolute), fileType = AVFileTypeMPEG4, error = error.ptr)
                ?: throw failure("AVAssetWriter couldn't open the file", error.value)
        }
    }

    /** Android's encoder settings ([spec]): its bit rate and key frames, H.264 High where the phone takes it. */
    private fun videoSettings(spec: VideoSpec, high: Boolean): Map<Any?, Any?> {
        val compression = mutableMapOf<Any?, Any?>(
            AVVideoAverageBitRateKey to spec.bitRate,
            AVVideoMaxKeyFrameIntervalKey to spec.keyFrameFrames,
            AVVideoMaxKeyFrameIntervalDurationKey to spec.keyFrameFrames.toDouble() / spec.fps,
            AVVideoExpectedSourceFrameRateKey to spec.fps,
            // No B-frames: every picture's decode time is its presentation time, so the parts join by moving them.
            AVVideoAllowFrameReorderingKey to false,
        )
        if (high) compression[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel
        return mapOf(
            AVVideoCodecKey to AVVideoCodecTypeH264,
            AVVideoWidthKey to spec.width,
            AVVideoHeightKey to spec.height,
            AVVideoCompressionPropertiesKey to compression,
        )
    }

    override fun openPicture(path: String, spec: VideoSpec): PictureFile {
        val absolute = files.absolute(path)
        // 1. The writer and its picture input with a pixel buffer adaptor (BGRA buffers of the clip's size).
        val writer = writerAt(absolute)
        // High profile where the phone takes it (Android: where its encoder offers it), else the plain settings.
        val settings = videoSettings(spec, high = true).takeIf { writer.canApplyOutputSettings(it, forMediaType = AVMediaTypeVideo) }
            ?: videoSettings(spec, high = false)
        val input = AVAssetWriterInput(mediaType = AVMediaTypeVideo, outputSettings = settings)
        input.expectsMediaDataInRealTime = false
        val attributes = mapOf<Any?, Any?>(
            cfKey(kCVPixelBufferPixelFormatTypeKey) to kCVPixelFormatType_32BGRA.toInt(),
            cfKey(kCVPixelBufferWidthKey) to spec.width,
            cfKey(kCVPixelBufferHeightKey) to spec.height,
        )
        val adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput = input, sourcePixelBufferAttributes = attributes)
        if (!writer.canAddInput(input)) throw failure("The video writer won't take these pictures", writer.error)
        writer.addInput(input)
        // 2. Writing starts, the session at time zero.
        if (!writer.startWriting()) {
            manager.removeItemAtPath(absolute, error = null)
            throw failure("The video writer didn't start", writer.error)
        }
        writer.startSessionAtSourceTime(CMTimeMake(0, spec.fps))
        return IosPictureFile(writer, input, adaptor, spec, absolute)
    }

    private inner class IosPictureFile(
        private val writer: AVAssetWriter,
        private val input: AVAssetWriterInput,
        private val adaptor: AVAssetWriterInputPixelBufferAdaptor,
        private val spec: VideoSpec,
        private val absolute: String,
    ) : PictureFile {
        private val info = ImageInfo(spec.width, spec.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)

        // 3. Each picture: a buffer from the adaptor's pool, the frame's pixels copied in, appended at index / fps.
        override suspend fun append(frame: Bitmap, index: Int) {
            waitReady(writer, input)
            val pool = adaptor.pixelBufferPool ?: throw failure("The video writer has no pixel buffers", writer.error)
            val buffer = memScoped {
                val out = alloc<CVPixelBufferRefVar>()
                if (CVPixelBufferPoolCreatePixelBuffer(null, pool, out.ptr) != kCVReturnSuccess) null else out.value
            } ?: throw VideoWriteFailed("No pixel buffer for the frame")
            try {
                if (!copyInto(frame, buffer)) throw VideoWriteFailed("The frame couldn't be copied")
                if (!adaptor.appendPixelBuffer(buffer, withPresentationTime = CMTimeMake(index.toLong(), spec.fps))) {
                    throw failure("The video writer refused a frame", writer.error)
                }
            } finally {
                // The pool's buffer is ours (+1): the writer keeps its own reference.
                CVPixelBufferRelease(buffer)
            }
        }

        /** The frame's pixels, as BGRA, into the buffer's memory (Skia copies them row by row at the buffer's stride). */
        private fun copyInto(frame: Bitmap, buffer: CVPixelBufferRef): Boolean {
            if (CVPixelBufferLockBaseAddress(buffer, 0u) != kCVReturnSuccess) return false
            try {
                val base = CVPixelBufferGetBaseAddress(buffer) ?: return false
                val rowBytes = CVPixelBufferGetBytesPerRow(buffer).toInt()
                val pixels: Pixmap = frame.peek() ?: return false
                try {
                    return pixels.readPixels(info, base.rawValue, rowBytes)
                } finally {
                    pixels.close()
                }
            } finally {
                CVPixelBufferUnlockBaseAddress(buffer, 0u)
            }
        }

        // 4 and 5. The input finished, the session ended after the last picture, the file finished.
        override suspend fun finish(frames: Int): Boolean {
            if (frames <= 0) {
                cancel()
                return true
            }
            if (writer.status != AVAssetWriterStatusWriting) return false
            input.markAsFinished()
            writer.endSessionAtSourceTime(CMTimeMake(frames.toLong(), spec.fps))
            finishWriting(writer)
            val ok = writer.status == AVAssetWriterStatusCompleted
            if (!ok) manager.removeItemAtPath(absolute, error = null)
            return ok
        }

        override fun cancel() {
            if (writer.status == AVAssetWriterStatusWriting) writer.cancelWriting()
            manager.removeItemAtPath(absolute, error = null)
        }
    }

    /**
     * The parts' pictures and [sound] joined into one MPEG-4 file at [path]. Apple's usual way, after the Mac run of
     * 19 Nov 2026 showed AVAssetWriter refusing a passthrough picture input (canAddInput false, no error): the sound is
     * encoded alone into an .m4a beside the file (AAC, from the mixed LPCM), then an AVMutableComposition holds each
     * part's pictures at its place in the clip and the sound under them, and AVAssetExportSession writes them out as they
     * are (passthrough: nothing is encoded again). The sound stops at the last picture's end (Android's
     * MusicMix.samplesWithin).
     */
    override suspend fun join(path: String, spec: VideoSpec, parts: List<PicturePart>, sound: MixedSound?, progress: (Float) -> Unit, stillWanted: () -> Unit) {
        require(parts.isNotEmpty()) { "No pictures to join" }
        val absolute = files.absolute(path)
        val totalFrames = parts.sumOf { it.frames }
        val soundFile = if (sound != null) "$absolute.sound.m4a" else null
        var session: AVAssetExportSession? = null
        try {
            // 1. The sound alone, AAC in an .m4a.
            if (sound != null && soundFile != null) {
                writeSound(soundFile, sound, min(sound.totalFrames, totalFrames.toLong() * sound.sampleRate / spec.fps), stillWanted) { progress(it * 0.6f) }
            }
            // 2. The composition: each part at its first frame's time, the sound from zero.
            val composition = AVMutableComposition()
            val pictures = composition.addMutableTrackWithMediaType(AVMediaTypeVideo, preferredTrackID = 0)
                ?: throw VideoWriteFailed("The video's pictures couldn't be laid out")
            for (part in parts) {
                stillWanted()
                val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(files.absolute(part.path)), options = null)
                val track = asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack
                    ?: throw VideoWriteFailed("A part of the video has no pictures")
                val length = CMTimeMinimum(asset.duration, CMTimeMake(part.frames.toLong(), spec.fps))
                insert(pictures, track, CMTimeRangeMake(CMTimeMake(0, spec.fps), length), CMTimeMake(part.firstFrame.toLong(), spec.fps), "pictures")
            }
            if (soundFile != null) {
                val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(soundFile), options = null)
                val track = asset.tracksWithMediaType(AVMediaTypeAudio ?: "soun").firstOrNull() as? AVAssetTrack
                    ?: throw VideoWriteFailed("The sound has no track")
                val audio = composition.addMutableTrackWithMediaType(AVMediaTypeAudio, preferredTrackID = 0)
                    ?: throw VideoWriteFailed("The sound couldn't be laid out")
                val length = CMTimeMinimum(asset.duration, CMTimeMake(totalFrames.toLong(), spec.fps))
                insert(audio, track, CMTimeRangeMake(CMTimeMake(0, spec.fps), length), CMTimeMake(0, spec.fps), "sound")
            }
            // 3. Written out as it is.
            files.folderFor(absolute)
            manager.removeItemAtPath(absolute, error = null)
            val export = AVAssetExportSession.exportSessionWithAsset(composition, presetName = AVAssetExportPresetPassthrough)
                ?: throw VideoWriteFailed("The video couldn't be joined")
            session = export
            export.outputURL = NSURL.fileURLWithPath(absolute)
            export.outputFileType = AVFileTypeMPEG4
            export.shouldOptimizeForNetworkUse = true
            export.exportAsynchronouslyWithCompletionHandler {}
            while (export.status == AVAssetExportSessionStatusWaiting || export.status == AVAssetExportSessionStatusExporting) {
                stillWanted()
                progress((if (soundFile != null) 0.6f else 0f) + export.progress * (if (soundFile != null) 0.4f else 1f))
                delay(100)
            }
            if (export.status != AVAssetExportSessionStatusCompleted) throw failure("The video couldn't be joined", export.error)
            progress(1f)
        } catch (e: Throwable) {
            session?.let { if (it.status == AVAssetExportSessionStatusWaiting || it.status == AVAssetExportSessionStatusExporting) it.cancelExport() }
            manager.removeItemAtPath(absolute, error = null)
            throw e
        } finally {
            soundFile?.let { manager.removeItemAtPath(it, error = null) }
        }
    }

    /** [range] of [from] into [into] at [at]; [what] names it in the failure. */
    private fun insert(into: AVMutableCompositionTrack, from: AVAssetTrack, range: CValue<CMTimeRange>, at: CValue<CMTime>, what: String) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!into.insertTimeRange(range, ofTrack = from, atTime = at, error = error.ptr)) {
                throw failure("The video's $what couldn't be laid out", error.value)
            }
        }
    }

    /**
     * [frames] frames of [sound] encoded AAC into an .m4a at [absolute] with AVAssetWriter, in Apple's order: the
     * writer, its one input, startWriting, the session at zero, each block of LPCM appended when the input is ready,
     * markAsFinished, finishWriting, the status read.
     */
    private suspend fun writeSound(absolute: String, sound: MixedSound, frames: Long, stillWanted: () -> Unit, progress: (Float) -> Unit) {
        files.folderFor(absolute)
        manager.removeItemAtPath(absolute, error = null)
        val writer = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(absolute), fileType = AVFileTypeAppleM4A, error = error.ptr)
                ?: throw failure("The sound's file couldn't be opened", error.value)
        }
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatMPEG4AAC.toInt(),
            AVSampleRateKey to sound.sampleRate.toDouble(),
            AVNumberOfChannelsKey to sound.channels,
            AVEncoderBitRateKey to ClipMusic.BIT_RATE,
        )
        val input = AVAssetWriterInput(mediaType = AVMediaTypeAudio, outputSettings = settings)
        input.expectsMediaDataInRealTime = false
        if (!writer.canAddInput(input)) throw failure("The sound's writer won't take AAC", writer.error)
        writer.addInput(input)
        val format = lpcmFormat(sound.sampleRate, sound.channels)
        try {
            if (!writer.startWriting()) throw failure("The sound's writer didn't start", writer.error)
            writer.startSessionAtSourceTime(CMTimeMake(0, sound.sampleRate))
            val chunk = 4096
            val samples = ShortArray(chunk * sound.channels)
            var at = 0L
            while (at < frames) {
                stillWanted()
                waitReady(writer, input)
                val n = min(chunk.toLong(), frames - at).toInt()
                appendSound(writer, input, format, sound, at, n, samples)
                at += n
                progress(at.toFloat() / frames)
            }
            input.markAsFinished()
            finishWriting(writer)
            if (writer.status != AVAssetWriterStatusCompleted) throw failure("The sound's writer didn't finish", writer.error)
        } catch (e: Throwable) {
            if (writer.status == AVAssetWriterStatusWriting) writer.cancelWriting()
            throw e
        } finally {
            CFRelease(format)
        }
    }

    /** 16-bit interleaved LPCM at [rate] with [channels] (ours, +1: released after the join). */
    private fun lpcmFormat(rate: Int, channels: Int): CMFormatDescriptionRef = memScoped {
        val asbd = alloc<AudioStreamBasicDescription>()
        asbd.mSampleRate = rate.toDouble()
        asbd.mFormatID = kAudioFormatLinearPCM
        asbd.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger or kLinearPCMFormatFlagIsPacked
        asbd.mBytesPerPacket = (2 * channels).toUInt()
        asbd.mFramesPerPacket = 1u
        asbd.mBytesPerFrame = (2 * channels).toUInt()
        asbd.mChannelsPerFrame = channels.toUInt()
        asbd.mBitsPerChannel = 16u
        asbd.mReserved = 0u
        val out = alloc<CMAudioFormatDescriptionRefVar>()
        if (CMAudioFormatDescriptionCreate(null, asbd.ptr, 0u, null, 0u, null, null, out.ptr) != 0) throw VideoWriteFailed("The sound's format couldn't be made")
        out.value ?: throw VideoWriteFailed("The sound's format couldn't be made")
    }

    /** [count] frames of [sound] from frame [at], rendered into [samples], appended to [input] as one LPCM sample buffer. */
    private fun appendSound(writer: AVAssetWriter, input: AVAssetWriterInput, format: CMFormatDescriptionRef, sound: MixedSound, at: Long, count: Int, samples: ShortArray) {
        sound.render(at, count, samples)
        val bytes = (count * sound.channels * 2).toULong()
        memScoped {
            val block = alloc<CMBlockBufferRefVar>()
            // A block of its own (the default allocator's), filled with the samples.
            if (CMBlockBufferCreateWithMemoryBlock(null, null, bytes, null, null, 0u, bytes, kCMBlockBufferAssureMemoryNowFlag, block.ptr) != 0) {
                throw VideoWriteFailed("No memory for the sound")
            }
            val data = block.value ?: throw VideoWriteFailed("No memory for the sound")
            try {
                val copied = samples.usePinned { CMBlockBufferReplaceDataBytes(it.addressOf(0), data, 0u, bytes) }
                if (copied != 0) throw VideoWriteFailed("The sound couldn't be copied")
                val out = alloc<CMSampleBufferRefVar>()
                val made = CMAudioSampleBufferCreateReadyWithPacketDescriptions(null, data, format, count.toLong(), CMTimeMake(at, sound.sampleRate), null, out.ptr)
                val sample = out.value
                if (made != 0 || sample == null) throw VideoWriteFailed("The sound couldn't be made")
                try {
                    if (!input.appendSampleBuffer(sample)) throw failure("The video writer refused the sound", writer.error)
                } finally {
                    CFRelease(sample)
                }
            } finally {
                CFRelease(data)
            }
        }
    }

    /** What the volume holding the app's files has free for things the traveller asked for (iOS 11's capacity). */
    override fun freeBytes(): Long? {
        val folder = files.absolute("exports")
        files.folderFor("$folder/x")
        val values = NSURL.fileURLWithPath(folder).resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), error = null)
        return (values?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue
    }
}

/** A sound file decoded to 16-bit PCM with AVAssetReader (the bundled music, a voice line, a phone video's sound). */
class IosSoundDecoder : SoundDecoder {
    override fun decode(location: String, fromUs: Long, lengthUs: Long, stillWanted: () -> Unit): Pcm? {
        val url = (if ("://" in location) NSURL.URLWithString(location) else NSURL.fileURLWithPath(location))
            ?: return soundDiag("no address", location)
        val asset = AVURLAsset(uRL = url, options = null)
        val track = asset.tracksWithMediaType(AVMediaTypeAudio ?: "soun").firstOrNull() as? AVAssetTrack
            ?: return soundDiag("no sound track (file there: ${url.path?.let { NSFileManager.defaultManager.fileExistsAtPath(it) }})", location)
        val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: return soundDiag("no reader", location)
        // 16-bit signed integers, interleaved, little-endian: what Android's decoder hands MusicMix.
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatLinearPCM.toInt(),
            AVLinearPCMBitDepthKey to 16,
            AVLinearPCMIsFloatKey to false,
            AVLinearPCMIsBigEndianKey to false,
            AVLinearPCMIsNonInterleaved to false,
        )
        val output = AVAssetReaderTrackOutput(track = track, outputSettings = settings)
        if (!reader.canAddOutput(output)) return soundDiag("the reader takes no such output", location)
        reader.addOutput(output)
        // From a little before the start (the packet the start is in), to a little past the end; what comes before the
        // start is skipped by its time below, as Android skips it.
        val from = max(0L, fromUs - LEAD_US)
        reader.timeRange = CMTimeRangeMake(CMTimeMake(from, 1_000_000), CMTimeMake(lengthUs + (fromUs - from) + LEAD_US, 1_000_000))
        if (!reader.startReading()) return soundDiag("didn't start: ${reader.error?.localizedDescription}", location)
        var samples = ShortArray(0)
        var size = 0
        var rate = 0
        var channels = 0
        try {
            while (true) {
                stillWanted()
                val sample = output.copyNextSampleBuffer() ?: break
                try {
                    if (rate == 0) {
                        val asbd = CMAudioFormatDescriptionGetStreamBasicDescription(CMSampleBufferGetFormatDescription(sample))?.pointed ?: continue
                        rate = asbd.mSampleRate.roundToInt()
                        channels = asbd.mChannelsPerFrame.toInt().coerceAtLeast(1)
                        samples = ShortArray((MusicMix.frames(lengthUs, rate) * channels + 8192).toInt())
                    }
                    val atUs = (CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample)) * 1_000_000).toLong()
                    val data = CMSampleBufferGetDataBuffer(sample) ?: continue
                    val length = CMBlockBufferGetDataLength(data).toInt()
                    val count = length / 2
                    if (count <= 0) continue
                    val chunk = ShortArray(count)
                    if (chunk.usePinned { CMBlockBufferCopyDataBytes(data, 0u, length.toULong(), it.addressOf(0)) } != 0) continue
                    val skip = if (atUs < fromUs) (MusicMix.frames(fromUs - atUs, rate) * channels).toInt().coerceIn(0, count) else 0
                    val take = count - skip
                    if (take > 0) {
                        if (size + take > samples.size) samples = samples.copyOf(max(samples.size * 2, size + take))
                        chunk.copyInto(samples, size, skip, count)
                        size += take
                    }
                    if (size / channels >= MusicMix.frames(lengthUs, rate)) break
                } finally {
                    CFRelease(sample)
                }
            }
        } finally {
            if (reader.status == AVAssetReaderStatusReading) reader.cancelReading()
        }
        if (rate == 0 || reader.status == AVAssetReaderStatusFailed) {
            return soundDiag("read nothing (rate $rate, status ${reader.status}, ${reader.error?.localizedDescription})", location)
        }
        val frames = min(size / channels, MusicMix.frames(lengthUs, rate).toInt())
        return if (frames <= 0) soundDiag("no frames", location) else Pcm(samples, frames, rate, channels)
    }

    /** Why a sound couldn't be read, in the app's output (the Simulator's CI prints it; the Mac run of 19 Nov 2026
     *  made the tour's video silent without saying why). */
    private fun soundDiag(why: String, location: String): Pcm? {
        diag("sound: $why: $location")
        return null
    }

    private companion object {
        const val LEAD_US = 100_000L
    }
}

/**
 * A phone video read forward for the export with AVAssetReader: every picture decoded to BGRA, as stored (the
 * renderer's side turns it by [DecodedVideo.rotation], the track's preferred transform, and crops it).
 */
internal fun iosDecodedVideo(absolute: String, fromUs: Long, lengthUs: Long): DecodedVideo? {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(absolute), options = null)
    val track = asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack ?: return null
    val turned = track.preferredTransform.useContents { ((atan2(b, a) * 180 / PI).roundToInt() % 360 + 360) % 360 }
    val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: return null
    val output = AVAssetReaderTrackOutput(track = track, outputSettings = mapOf<Any?, Any?>(cfKey(kCVPixelBufferPixelFormatTypeKey) to kCVPixelFormatType_32BGRA.toInt()))
    output.alwaysCopiesSampleData = false
    if (!reader.canAddOutput(output)) return null
    reader.addOutput(output)
    // The played part and a little past it (Android reads 0.3 s on: B-frames come out of order).
    reader.timeRange = CMTimeRangeMake(CMTimeMake(fromUs, 1_000_000), CMTimeMake(lengthUs + 300_000, 1_000_000))
    if (!reader.startReading()) return null
    return object : DecodedVideo {
        override val rotation: Int = turned

        override fun next(use: (picture: Image, timeUs: Long) -> Unit): Boolean {
            val sample = output.copyNextSampleBuffer() ?: return false
            try {
                val atUs = (CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample)) * 1_000_000).toLong()
                val pixels = CMSampleBufferGetImageBuffer(sample) ?: return true
                if (CVPixelBufferLockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) return true
                try {
                    val base = CVPixelBufferGetBaseAddress(pixels) ?: return true
                    val info = ImageInfo(CVPixelBufferGetWidth(pixels).toInt(), CVPixelBufferGetHeight(pixels).toInt(), ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                    val pixmap = Pixmap.make(info, base.rawValue, CVPixelBufferGetBytesPerRow(pixels).toInt())
                    try {
                        val image = Image.makeFromPixmap(pixmap)
                        try {
                            use(image, atUs)
                        } finally {
                            image.close()
                        }
                    } finally {
                        pixmap.close()
                    }
                } finally {
                    CVPixelBufferUnlockBaseAddress(pixels, kCVPixelBufferLock_ReadOnly)
                }
            } finally {
                CFRelease(sample)
            }
            return true
        }

        override fun close() {
            if (reader.status == AVAssetReaderStatusReading) reader.cancelReading()
        }
    }
}

/**
 * Where a finished video goes on iPhone: the share sheet (UIActivityViewController with the file), Photos (add-only
 * access, asked at that moment), and the apps it goes to straight (their addresses checked with canOpenURL; they take
 * the video through their own share extensions in the share sheet: TikTok's direct posting needs its Share Kit and the
 * owner's TikTok developer app).
 */
class IosVideoShare(private val files: IosMediaFiles) : VideoShare {
    override fun share(path: String): Boolean = presentShareSheet(listOf(NSURL.fileURLWithPath(files.absolute(path))))

    override fun saveToPhotos(path: String, done: (Boolean) -> Unit) {
        fun main(ok: Boolean) = dispatch_async(dispatch_get_main_queue()) { done(ok) }
        val url = NSURL.fileURLWithPath(files.absolute(path))
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
            if (status != PHAuthorizationStatusAuthorized && status != PHAuthorizationStatusLimited) {
                main(false)
                return@requestAuthorizationForAccessLevel
            }
            PHPhotoLibrary.sharedPhotoLibrary().performChanges({ PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(url) }) { ok, _ -> main(ok) }
        }
    }

    override fun installed(target: ClipTarget): Boolean = target.schemes.any { scheme ->
        NSURL.URLWithString("$scheme://")?.let { UIApplication.sharedApplication.canOpenURL(it) } == true
    }

    override fun sendTo(target: ClipTarget, path: String): Boolean = installed(target) && share(path)
}

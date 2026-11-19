@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.justdataplease.boringtravel.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.creationDate
import platform.AVFoundation.dateValue
import platform.AVFoundation.duration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracksWithMediaType
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGSizeApplyAffineTransform
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.seekToFileOffset
import platform.Foundation.writeData
import platform.Foundation.readDataOfLength
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerConfigurationAssetRepresentationModeCurrent
import platform.PhotosUI.PHPickerConfigurationSelectionOrdered
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerMediaMetadata
import platform.UIKit.UIImagePickerControllerMediaURL
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerQualityTypeHigh
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.UniformTypeIdentifiers.conformsToType
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The notes' photos and videos on iPhone, each behind the small interface common code uses ([MediaFiles],
 * [MediaPicker], [VideoFrames]; the player is IosVideoPlayback.kt, a video's 720p copy IosVideoPrep.kt, the image
 * reader IosImages.kt). Apple's APIs called here: Foundation (NSFileManager, NSFileHandle, NSData, NSURL, NSUUID),
 * PhotosUI (PHPickerViewController, each file as it is stored), UniformTypeIdentifiers (a file's type), UIKit
 * (UIImagePickerController for the camera, with its media metadata and a 30-minute recording limit), AVFoundation
 * (AVURLAsset, AVAssetImageGenerator), CoreMedia and CoreGraphics for times and sizes.
 */

/** The notes' media on this phone: files, the library picker and the camera, the player, first frames and 720p videos. */
fun iosMediaKit(): MediaKit {
    val files = IosMediaFiles()
    // Pictures Skia can't draw (HEIC, AVIF) and every picture's tags through ImageIO, for every screen and export.
    Images.phone = IosPhoneImages
    return MediaKit(files, IosMediaPicker(files), IosVideoFrames(files), videos = IosVideoPrep(files), playback = IosVideoPlayback(files))
}

/**
 * The app's files under Application Support/BoringTravel ("media/<id>.jpg" as on Android, "picked/…" on the way in);
 * with [directory] NSCachesDirectory, what can be fetched again (link previews and their pictures).
 */
class IosMediaFiles(private val directory: platform.Foundation.NSSearchPathDirectory = NSApplicationSupportDirectory) : MediaFiles {
    private val manager = NSFileManager.defaultManager

    private val root: String = run {
        val base = NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).firstOrNull() as? String
        (base ?: NSTemporaryDirectory().trimEnd('/')) + "/BoringTravel"
    }

    override fun absolute(path: String): String = "$root/$path"

    /** Makes the folder [file] goes into. */
    fun folderFor(file: String) {
        manager.createDirectoryAtPath(file.substringBeforeLast('/'), withIntermediateDirectories = true, attributes = null, error = null)
    }

    override fun write(path: String, bytes: ByteArray): Boolean {
        val full = absolute(path)
        folderFor(full)
        return bytes.toNSData().writeToFile(full, atomically = true)
    }

    override fun read(path: String): ByteArray? = NSData.dataWithContentsOfFile(absolute(path))?.toByteArray()

    override fun readChunks(path: String, each: (ByteArray, Int) -> Unit): Boolean {
        val handle = NSFileHandle.fileHandleForReadingAtPath(absolute(path)) ?: return false
        try {
            while (true) {
                val data = handle.readDataOfLength(CHUNK.toULong())
                val n = data.length.toInt()
                if (n == 0) break
                each(data.toByteArray(), n)
            }
        } finally {
            handle.closeFile()
        }
        return true
    }

    override fun size(path: String): Long? = (manager.attributesOfItemAtPath(absolute(path), error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue

    override fun readRange(path: String, offset: Long, length: Int): ByteArray? {
        val handle = NSFileHandle.fileHandleForReadingAtPath(absolute(path)) ?: return null
        try {
            handle.seekToFileOffset(offset.toULong())
            val data = handle.readDataOfLength(length.toULong())
            return data.toByteArray().takeIf { it.size == length }
        } finally {
            handle.closeFile()
        }
    }

    override fun append(path: String, bytes: ByteArray, count: Int): Boolean {
        val full = absolute(path)
        folderFor(full)
        if (!manager.fileExistsAtPath(full) && !manager.createFileAtPath(full, contents = null, attributes = null)) return false
        val handle = NSFileHandle.fileHandleForWritingAtPath(full) ?: return false
        try {
            handle.seekToEndOfFile()
            handle.writeData(bytes.copyOf(count).toNSData())
        } finally {
            handle.closeFile()
        }
        return true
    }

    override fun move(from: String, to: String): Boolean {
        val target = absolute(to)
        folderFor(target)
        return manager.moveItemAtPath(absolute(from), toPath = target, error = null)
    }

    override fun delete(path: String) {
        manager.removeItemAtPath(absolute(path), error = null)
    }

    override fun list(path: String): List<String> = listFiles(manager, absolute(path))

    override fun modifiedMs(path: String): Long? = modifiedAt(manager, absolute(path))

    /** NSFileManager's copy (a clone on APFS: a finished trip video copied into the trip's Media takes no time). */
    override fun copy(from: String, to: String): Boolean {
        val target = absolute(to)
        folderFor(target)
        manager.removeItemAtPath(target, error = null)
        return manager.copyItemAtPath(absolute(from), toPath = target, error = null)
    }

    private companion object {
        const val CHUNK = 256 * 1024
    }
}

/** Uniform type identifiers the pickers ask for. */
private const val MOVIE = "public.movie"
private const val IMAGE = "public.image"

/**
 * The photo library through PHPickerViewController (no permission needed: the traveller picks, the app gets copies),
 * photos and videos together in the order picked, each as it is stored (a HEIC stays a HEIC, as Android's photo picker
 * hands over the file itself; a video is made 720p by the note, IosVideoPrep); the camera through
 * UIImagePickerController (asks for the camera, and the microphone for a video's sound).
 */
class IosMediaPicker(private val files: IosMediaFiles) : MediaPicker {
    /** The open picker's delegate: UIKit only keeps a weak hold on it. */
    private var delegate: NSObject? = null

    override fun pickMedia(max: Int, done: (List<PickedMedia>) -> Unit) =
        pick(max, PHPickerFilter.anyFilterMatchingSubfilters(listOf(PHPickerFilter.imagesFilter, PHPickerFilter.videosFilter)), done)

    /** Photos only (the AI planner's pictures): PHPicker's images filter. */
    override fun pickPhotos(max: Int, done: (List<PickedMedia>) -> Unit) = pick(max, PHPickerFilter.imagesFilter, done)

    /** Videos only (the trip video's "From your phone"): PHPicker's videos filter. */
    override fun pickVideos(max: Int, done: (List<PickedMedia>) -> Unit) = pick(max, PHPickerFilter.videosFilter, done)

    private fun pick(max: Int, filter: PHPickerFilter, done: (List<PickedMedia>) -> Unit) {
        val config = PHPickerConfiguration()
        config.selectionLimit = max.toLong()
        config.filter = filter
        config.selection = PHPickerConfigurationSelectionOrdered
        // The file as it is stored (iOS turns nothing into JPEG or H.264 first), as Android's picker hands it over.
        config.preferredAssetRepresentationMode = PHPickerConfigurationAssetRepresentationModeCurrent
        val picker = PHPickerViewController(configuration = config)
        val d = LibraryDelegate { results ->
            delegate = null
            load(results, done)
        }
        delegate = d
        picker.delegate = d
        if (!present(picker)) done(emptyList())
    }

    /** Each result's file copied in (the picker deletes its own once read), handed over in the order picked. */
    private fun load(results: List<PHPickerResult>, done: (List<PickedMedia>) -> Unit) {
        if (results.isEmpty()) return done(emptyList())
        val got = arrayOfNulls<PickedMedia>(results.size)
        var left = results.size
        results.forEachIndexed { i, result ->
            val provider = result.itemProvider
            val video = provider.hasItemConformingToTypeIdentifier(MOVIE)
            // The photo's own type (public.heic for an iPhone's photo), so its file comes as it is stored.
            val own = provider.registeredTypeIdentifiers.mapNotNull { it as? String }.firstOrNull { id ->
                UTType.typeWithIdentifier(id)?.conformsToType(if (video) UTTypeMovie else UTTypeImage) == true
            }
            val type = own ?: if (video) MOVIE else IMAGE
            provider.loadFileRepresentationForTypeIdentifier(type) { url, _ ->
                // Off the main thread, and the file is gone once this returns: copy it first.
                val picked = url?.let { handOver(it, video) }
                dispatch_async(dispatch_get_main_queue()) {
                    got[i] = picked
                    left -= 1
                    if (left == 0) done(got.filterNotNull())
                }
            }
        }
    }

    /**
     * [url]'s file copied under "picked/", with its type (a HEIC stays a HEIC: the app's pictures read it through
     * ImageIO, as Android's decoder reads it) and, for a video, its size, length and time.
     */
    private fun handOver(url: NSURL, video: Boolean): PickedMedia? {
        val ext = url.pathExtension?.lowercase()?.ifEmpty { null } ?: if (video) "mov" else "jpg"
        val path = "picked/${NSUUID().UUIDString}.$ext"
        val target = files.absolute(path)
        files.folderFor(target)
        if (!NSFileManager.defaultManager.copyItemAtURL(url, toURL = NSURL.fileURLWithPath(target), error = null)) return null
        val mime = MediaTypes.ofExtension(ext)
        return when {
            video -> videoFacts(path, if (MediaTypes.isVideo(mime)) mime else "video/quicktime")
            else -> PickedMedia(path, mime)
        }
    }

    /** What a video says about itself: its size as it is shown, its length and when it was made. */
    private fun videoFacts(path: String, mime: String): PickedMedia {
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
        val takenAt = asset.creationDate?.dateValue?.timeIntervalSince1970?.let { (it * 1000).toLong() }
        return PickedMedia(path, mime, w, h, durationMs = if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else null, takenAt = takenAt)
    }

    override val hasCamera: Boolean
        get() = UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)

    override fun takePhoto(done: (PickedMedia?) -> Unit) = camera(IMAGE, done)

    override fun recordVideo(done: (PickedMedia?) -> Unit) = camera(MOVIE, done)

    private fun camera(type: String, done: (PickedMedia?) -> Unit) {
        val picker = UIImagePickerController()
        picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        picker.mediaTypes = listOf(type)
        if (type == MOVIE) {
            picker.videoQuality = UIImagePickerControllerQualityTypeHigh
            // Android's camera app records with no cut; the note takes up to 30 minutes (VideoLimits.MAX_MS), so the
            // camera stops there, not at UIKit's own 10 minutes.
            picker.videoMaximumDuration = com.justdataplease.boringtravel.data.repo.VideoLimits.MAX_MS / 1000.0
        }
        val d = CameraDelegate { info ->
            delegate = null
            done(info?.let(::fromCamera))
        }
        delegate = d
        picker.delegate = d
        if (!present(picker)) done(null)
    }

    /**
     * The camera's photo as a JPEG with the camera's own metadata (its time with the offset, the camera, the
     * orientation: as an Android camera app writes its JPEG; ImageIO's CGImageDestination, where UIImageJPEGRepresentation
     * would leave them out), taken now; or its video's file.
     */
    private fun fromCamera(info: Map<Any?, *>): PickedMedia? {
        (info[UIImagePickerControllerMediaURL] as? NSURL)?.let { return handOver(it, video = true) }
        val image = info[UIImagePickerControllerOriginalImage] as? UIImage ?: return null
        @Suppress("UNCHECKED_CAST")
        val metadata = info[UIImagePickerControllerMediaMetadata] as? Map<Any?, *>
        val bytes = IosPhoneImages.cameraJpeg(image, metadata, 0.92) ?: UIImageJPEGRepresentation(image, 0.92)?.toByteArray() ?: return null
        val path = "picked/${NSUUID().UUIDString}.jpg"
        if (!files.write(path, bytes)) return null
        return PickedMedia(path, MediaTypes.JPEG, takenAt = (NSDate().timeIntervalSince1970 * 1000).toLong())
    }
}

private class LibraryDelegate(private val onPicked: (List<PHPickerResult>) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onPicked(didFinishPicking.filterIsInstance<PHPickerResult>())
    }
}

private class CameraDelegate(private val onDone: (Map<Any?, *>?) -> Unit) :
    NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
    override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onDone(didFinishPickingMediaWithInfo)
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onDone(null)
    }
}

/** A video's pictures through AVAssetImageGenerator, turned as the video is shown, as JPEGs; its length from AVURLAsset. */
class IosVideoFrames(private val files: IosMediaFiles) : VideoFrames {
    override fun poster(path: String, maxPx: Int): ByteArray? = picture(path, 0L, maxPx, 0.85)

    override fun durationMs(path: String): Long? {
        val seconds = CMTimeGetSeconds(AVURLAsset(uRL = NSURL.fileURLWithPath(files.absolute(path)), options = null).duration)
        return if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else null
    }

    // The generator's own tolerance (the nearest key frame), as Android's OPTION_CLOSEST_SYNC.
    override fun frameAt(path: String, atMs: Long, maxPx: Int, quality: Int): ByteArray? = picture(path, atMs, maxPx, quality / 100.0)

    private fun picture(path: String, atMs: Long, maxPx: Int, quality: Double): ByteArray? {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(files.absolute(path)), options = null)
        val generator = AVAssetImageGenerator(asset = asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSizeMake(maxPx.toDouble(), maxPx.toDouble())
        val frame = generator.copyCGImageAtTime(CMTimeMake(value = atMs.coerceAtLeast(0L), timescale = 1000), actualTime = null, error = null) ?: return null
        val image = UIImage.imageWithCGImage(frame)
        CGImageRelease(frame)
        return UIImageJPEGRepresentation(image, quality)?.toByteArray()
    }
}

/** Shows [controller] over whatever is on top; false when there is no window yet. */
private fun present(controller: UIViewController, then: (() -> Unit)? = null): Boolean {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return false
    while (true) top = top.presentedViewController ?: break
    top.presentViewController(controller, animated = true, completion = then)
    return true
}

private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

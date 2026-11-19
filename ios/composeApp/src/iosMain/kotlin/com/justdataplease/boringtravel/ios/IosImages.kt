@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.justdataplease.boringtravel.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSMutableData
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetType
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImageDestinationLossyCompressionQuality
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeJPEG
import platform.CoreGraphics.CGImageRelease
import platform.posix.memcpy

/*
 * The phone's image reader (the second parity round): ImageIO, which reads every format the iPhone does (HEIC, and AVIF
 * since iOS 16), beside Skia ([PhoneImages], common code's Images). A picture's type, size as shown and EXIF time and
 * place from its properties (CGImageSourceCopyPropertiesAtIndex: {Exif}, {TIFF}, {GPS}), a JPEG of one Skia can't draw
 * (CGImageSourceCreateThumbnailAtIndex with its orientation applied), and the camera's photo written with the camera's
 * own metadata (CGImageDestination: what UIImageJPEGRepresentation leaves out). Only type-checked on Windows.
 */

/** A Core Foundation key as the NSString Objective-C dictionaries take (ImageIO's keys are CFStrings). */
private fun cfKey(key: CFStringRef?): NSString = interpretObjCPointer(requireNotNull(key).rawValue)

/** [bytes] as an ImageIO source, handed to [use] and released after; null when ImageIO can't read them. */
private fun <T> withSource(bytes: ByteArray, use: (CGImageSourceRef) -> T?): T? {
    if (bytes.isEmpty()) return null
    val data: CFDataRef = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret<UByteVar>(), bytes.size.toLong()) } ?: return null
    try {
        val source = CGImageSourceCreateWithData(data, null) ?: return null
        try {
            return use(source)
        } finally {
            CFRelease(source)
        }
    } finally {
        CFRelease(data)
    }
}

/** ImageIO's reading (see the file's note). */
object IosPhoneImages : PhoneImages {
    override fun facts(bytes: ByteArray): ImageFacts? = withSource(bytes) { source ->
        val uti = CGImageSourceGetType(source)?.let { interpretObjCPointer<String>(it.rawValue) }
        val props = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: return@withSource null
        val all = CFBridgingRelease(props) as? Map<*, *> ?: return@withSource null
        val w = (all["PixelWidth"] as? NSNumber)?.intValue ?: 0
        val h = (all["PixelHeight"] as? NSNumber)?.intValue ?: 0
        if (w <= 0 || h <= 0) return@withSource null
        val exif = all["{Exif}"] as? Map<*, *>
        val tiff = all["{TIFF}"] as? Map<*, *>
        val gps = all["{GPS}"] as? Map<*, *>
        val orientation = (all["Orientation"] as? NSNumber)?.intValue ?: (tiff?.get("Orientation") as? NSNumber)?.intValue ?: 1
        // A damaged GPS tag can read as no number at all: such a photo simply has no place (as on Android).
        fun coordinate(value: String, ref: String, negative: String): Double? {
            val v = (gps?.get(value) as? NSNumber)?.doubleValue ?: return null
            return if ((gps[ref] as? String)?.trim()?.uppercase()?.startsWith(negative) == true) -v else v
        }
        val lat = coordinate("Latitude", "LatitudeRef", "S")
        val lng = coordinate("Longitude", "LongitudeRef", "W")
        val placed = lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
        val tags = Exif.Tags(
            dateTimeOriginal = exif?.get("DateTimeOriginal") as? String,
            offsetTimeOriginal = exif?.get("OffsetTimeOriginal") as? String,
            dateTime = tiff?.get("DateTime") as? String,
            offsetTime = exif?.get("OffsetTime") as? String,
            lat = lat.takeIf { placed },
            lng = lng.takeIf { placed },
        )
        // Turned a quarter (orientations 5 to 8): shown the other way round.
        val turned = orientation in 5..8
        ImageFacts(uti?.let(::mimeOf), if (turned) h else w, if (turned) w else h, tags)
    }

    override fun jpeg(bytes: ByteArray, maxSide: Int?, quality: Int): ByteArray? = withSource(bytes) { source ->
        val side = maxSide ?: facts(bytes)?.let { maxOf(it.width, it.height) } ?: return@withSource null
        val options = mapOf<Any?, Any?>(
            cfKey(kCGImageSourceCreateThumbnailFromImageAlways) to true,
            cfKey(kCGImageSourceCreateThumbnailWithTransform) to true,
            cfKey(kCGImageSourceThumbnailMaxPixelSize) to side,
        )
        val cfOptions = CFBridgingRetain(options)?.reinterpret<cnames.structs.__CFDictionary>() as CFDictionaryRef?
        try {
            val picture = CGImageSourceCreateThumbnailAtIndex(source, 0u, cfOptions) ?: return@withSource null
            try {
                // Written plainly (no tags): what is left of it is only to be drawn.
                UIImageJPEGRepresentation(UIImage.imageWithCGImage(picture), quality / 100.0)?.let { d ->
                    ByteArray(d.length.toInt()).also { out -> if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), d.bytes, d.length) } }
                }
            } finally {
                CGImageRelease(picture)
            }
        } finally {
            cfOptions?.let { CFRelease(it) }
        }
    }

    /** The MIME type of a uniform type identifier ("public.heic" → "image/heic"). */
    private fun mimeOf(uti: String): String? = UTType.typeWithIdentifier(uti)?.preferredMIMEType ?: when (uti) {
        "public.jpeg" -> "image/jpeg"
        "public.png" -> "image/png"
        "public.heic" -> "image/heic"
        "public.heif" -> "image/heif"
        "public.avif" -> "image/avif"
        "org.webmproject.webp" -> "image/webp"
        "com.compuserve.gif" -> "image/gif"
        else -> null
    }

    /**
     * The camera's photo [image] as a JPEG at [quality] (0–1) with the camera's own [metadata] (UIImagePickerController's
     * media metadata: the capture time and its offset, the camera, the orientation), as an Android camera app writes its
     * JPEG; null when it can't be written.
     */
    fun cameraJpeg(image: UIImage, metadata: Map<Any?, *>?, quality: Double): ByteArray? {
        val picture = image.CGImage ?: return null
        val data = NSMutableData()
        val cfData = CFBridgingRetain(data)?.reinterpret<cnames.structs.__CFData>() as CFMutableDataRef? ?: return null
        val type = CFBridgingRetain(UTTypeJPEG.identifier)?.reinterpret<cnames.structs.__CFString>() as CFStringRef?
        val properties = HashMap<Any?, Any?>()
        metadata?.forEach { (k, v) -> properties[k] = v }
        properties[cfKey(kCGImageDestinationLossyCompressionQuality)] = quality
        val cfProperties = CFBridgingRetain(properties)?.reinterpret<cnames.structs.__CFDictionary>() as CFDictionaryRef?
        try {
            val destination = CGImageDestinationCreateWithData(cfData, type, 1u, null) ?: return null
            try {
                CGImageDestinationAddImage(destination, picture, cfProperties)
                if (!CGImageDestinationFinalize(destination)) return null
            } finally {
                CFRelease(destination)
            }
        } finally {
            CFRelease(cfData)
            type?.let { CFRelease(it) }
            cfProperties?.let { CFRelease(it) }
        }
        val n = data.length.toInt()
        if (n <= 0) return null
        return ByteArray(n).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
    }
}

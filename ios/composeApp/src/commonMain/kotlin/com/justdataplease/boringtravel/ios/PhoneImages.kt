package com.justdataplease.boringtravel.ios

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat

/*
 * Pictures in every format the phone reads (the second parity round). The app draws with Skia, which reads JPEG, PNG,
 * WebP and GIF but not HEIC or AVIF; Android keeps a picked HEIC as HEIC and draws it (BitmapFactory), and reads a
 * photo's time and place from any format (ExifInterface). So on iPhone the phone's own reader stands beside Skia
 * (ImageIO: CGImageSource, which reads HEIC and, since iOS 16, AVIF): it reads every format's size and tags
 * ([PhoneImages.facts]) and turns what Skia can't read into a JPEG for drawing ([PhoneImages.jpeg]). The file itself is
 * kept as Android keeps it. The desktop has Skia only (its stand-in reads what Skia reads, EXIF from JPEGs).
 */

/**
 * What a picture says about itself: its type as the reader names it (null: unknown), its size as it is shown (turned
 * as its orientation says), and its EXIF time and place.
 */
data class ImageFacts(val mime: String?, val width: Int, val height: Int, val tags: Exif.Tags = Exif.Tags())

/** The phone's image reader (ImageIO on iPhone). */
interface PhoneImages {
    /** [bytes]' type, size as shown and EXIF time and place, from any format the phone reads; null when it can't read them. */
    fun facts(bytes: ByteArray): ImageFacts?

    /**
     * [bytes] drawn upright (its orientation applied) and written as a JPEG at [quality] (0–100), at most [maxSide] px on
     * its longer side (null: its own size), without its tags; null when the phone can't read it.
     */
    fun jpeg(bytes: ByteArray, maxSide: Int?, quality: Int): ByteArray?
}

/** Skia's reading only (the desktop, the tests): JPEG, PNG, WebP and GIF, EXIF from JPEGs; no HEIC or AVIF. */
object SkiaImages : PhoneImages {
    override fun facts(bytes: ByteArray): ImageFacts? {
        val format = Images.skiaFormat(bytes) ?: return null
        val (w, h) = Pictures.size(bytes) ?: return null
        val mime = when (format) {
            EncodedImageFormat.JPEG -> "image/jpeg"
            EncodedImageFormat.PNG -> "image/png"
            EncodedImageFormat.WEBP -> "image/webp"
            EncodedImageFormat.GIF -> "image/gif"
            else -> null
        }
        return ImageFacts(mime, w, h, if (format == EncodedImageFormat.JPEG) Exif.read(bytes) else Exif.Tags())
    }

    override fun jpeg(bytes: ByteArray, maxSide: Int?, quality: Int): ByteArray? = null
}

/** The phone's reader beside Skia, for every screen and export: the iPhone's is set when the app starts ([Images.phone]). */
object Images {
    /** ImageIO on iPhone (set by iosMain's start); Skia alone elsewhere. */
    var phone: PhoneImages = SkiaImages

    /** The format Skia reads [bytes] as, or null when Skia can't draw them (HEIC, AVIF, anything else). */
    fun skiaFormat(bytes: ByteArray): EncodedImageFormat? = runCatching {
        val data = Data.makeFromBytes(bytes)
        val codec = Codec.makeFromData(data)
        try {
            codec.encodedImageFormat.takeIf { codec.width > 0 && codec.height > 0 }
        } finally {
            codec.close()
            data.close()
        }
    }.getOrNull()

    /**
     * [bytes] as Skia can draw them: themselves when Skia reads them, else the phone's JPEG of them, upright, at most
     * [maxSide] px (a HEIC or AVIF photo, as Android's decoder reads it); null when neither can.
     */
    fun drawable(bytes: ByteArray, maxSide: Int? = null): ByteArray? =
        if (skiaFormat(bytes) != null) bytes else runCatching { phone.jpeg(bytes, maxSide, DRAW_QUALITY) }.getOrNull()

    /**
     * [bytes] as Skia can draw them with their short side at least [shortSide] px (the trip video's photos): themselves
     * when Skia reads them, else the phone's upright JPEG, just big enough; null when neither can.
     */
    fun drawableShort(bytes: ByteArray, shortSide: Int): ByteArray? {
        if (skiaFormat(bytes) != null) return bytes
        val f = facts(bytes)
        val side = f?.let { kotlin.math.ceil(shortSide * maxOf(it.width, it.height).toDouble() / minOf(it.width, it.height).coerceAtLeast(1)).toInt() }
        return runCatching { phone.jpeg(bytes, side, DRAW_QUALITY) }.getOrNull()
    }

    /** [bytes]' type, size and tags: the phone's reader first (every format), else Skia's with EXIF from a JPEG. */
    fun facts(bytes: ByteArray): ImageFacts? =
        runCatching { phone.facts(bytes) }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 } ?: SkiaImages.facts(bytes)

    /** The quality a picture made only to be drawn is written at. */
    private const val DRAW_QUALITY = 92
}

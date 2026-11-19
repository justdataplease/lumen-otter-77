package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.compat.graphics.Bitmap
import com.justdataplease.boringtravel.ios.Pictures
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The Android app's clip/ClipExport.kt as far as the picture needs it: how big a photo is decoded for the video and
 * what of it a card shows (the same numbers), and the decoding itself, here with Skia (Android: ImageDecoder). Making
 * the MP4 (Media3 and MediaCodec on Android) is round 20's, over AVFoundation; its encoder's bit rate is below
 * (Mp4Encoder), so that round starts from Android's number.
 */
object ClipExport {
    /**
     * Photos are decoded with their short side at most this: a paused photo's card is about 560 px wide in a
     * 1080 px clip and crops the photo to 4:5 (or square on polaroid looks), so the short side is what shows.
     */
    const val PHOTO_SHORT_SIDE = 600

    /** A [w] × [h] picture scaled so its short side is at most [shortSide] (never enlarged). */
    fun photoTarget(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val scale = min(1.0, shortSide.toDouble() / min(w, h).coerceAtLeast(1))
        return (w * scale).roundToInt().coerceAtLeast(1) to (h * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * What of a [w] × [h] picture a card can show: cards are 4:5 portrait (square on polaroid looks) and crop around
     * the middle, so a landscape photo keeps its middle square and a tall one its middle 4:5.
     */
    fun photoCrop(w: Int, h: Int): Pair<Int, Int> =
        if (w >= h) h to h else w to min(h, (w * 1.25).roundToInt())

    /**
     * [bytes] (a JPEG, PNG or HEIF the phone can read) decoded the right way up (its EXIF orientation), scaled so its
     * short side is [shortSide] ([photoTarget]) and cropped around the middle to what a card can show ([photoCrop]),
     * as Android's loadPhotos decodes them; null when it can't be read. A big JPEG is read at a half, quarter or eighth
     * of its size first (Pictures.readSmall), as Android's decoder does with its target size.
     */
    fun decode(bytes: ByteArray, shortSide: Int = PHOTO_SHORT_SIDE): Bitmap? = runCatching {
        // A HEIC or AVIF photo (kept as Android keeps it) through the phone's reader first, just big enough.
        val data = Data.makeFromBytes(com.justdataplease.boringtravel.ios.Images.drawableShort(bytes, shortSide) ?: bytes)
        val codec = Codec.makeFromData(data)
        try {
            val origin = codec.encodedOrigin
            val swap = origin.swapsWidthHeight()
            val longest = max(codec.width, codec.height).toDouble() / min(codec.width, codec.height).coerceAtLeast(1)
            val read = Pictures.readSmall(codec, ceil(shortSide * longest).toInt())
            val raw = Image.makeFromBitmap(read)
            try {
                val w = if (swap) read.height else read.width
                val h = if (swap) read.width else read.height
                val (tw, th) = photoTarget(w, h, shortSide)
                val (cw, ch) = photoCrop(tw, th)
                val surface = Surface.makeRasterN32Premul(cw, ch)
                try {
                    val c = surface.canvas
                    // The scaled, upright picture with its middle in the crop.
                    c.translate((cw - tw) / 2f, (ch - th) / 2f)
                    c.scale(tw.toFloat() / w, th.toFloat() / h)
                    c.concat(origin.toMatrix(read.width, read.height))
                    val paint = Paint()
                    c.drawImageRect(
                        raw, Rect.makeWH(read.width.toFloat(), read.height.toFloat()), Rect.makeWH(read.width.toFloat(), read.height.toFloat()),
                        FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), paint, true,
                    )
                    paint.close()
                    Bitmap.of(surface.makeImageSnapshot())
                } finally {
                    surface.close()
                }
            } finally {
                raw.close()
                read.close()
            }
        } finally {
            codec.close()
            data.close()
        }
    }.getOrNull()
}

/** The Android app's encoder's bit rate (clip/Mp4Encoder.kt), for the export (round 20). */
object Mp4Encoder {
    const val KEY_FRAME_SECONDS = 2

    /**
     * About 0.26 bits per pixel per frame: 16 Mbps for 1080 × 1920 at 30 fps, 9 for the square, within 6–20 Mbps.
     * Maps are fine lines and small labels that move every frame; social apps re-encode, so a clean source matters.
     */
    fun defaultBitRate(width: Int, height: Int, fps: Int = 30): Int =
        (width.toLong() * height * fps * 0.26).toLong().coerceIn(6_000_000L, 20_000_000L).toInt()
}

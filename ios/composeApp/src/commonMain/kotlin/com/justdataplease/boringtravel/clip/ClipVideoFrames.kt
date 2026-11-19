package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.compat.graphics.Bitmap
import com.justdataplease.boringtravel.ios.ClipVideoFiles
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.ceil

/*
 * The Android app's clip/ClipVideoFrames.kt: the interface the renderer reads a phone video's pictures through, with
 * [ClipVideoFrames.NONE] (the map stays under the insert), and the preview's pictures prepared ahead
 * ([PreparedVideoFrames], since round 19). Android decodes them with MediaCodec; here the phone hands over its pictures
 * (AVAssetImageGenerator on iPhone, behind [ClipVideoFiles]) and Skia crops them to fill the frame (Android's VideoFit)
 * and keeps them as small JPEGs, as Android does. The export's own pictures (Android's DecodedVideoFrames, MediaCodec;
 * AVAssetReader here) are round 20's.
 */

/**
 * The pictures of a clip's phone videos ([ClipVideo]) for the renderer. Each renderer takes its own [Reader] (a
 * renderer draws on one thread); [NONE] has none (the renderer then keeps the map under the insert).
 */
interface ClipVideoFrames {
    fun reader(): Reader

    interface Reader : AutoCloseable {
        /**
         * Video [id]'s picture [timeUs] into its played part, cropped to fill a [width] × [height] frame (a prepared
         * picture may be smaller; the renderer scales it), or null when there is none yet. Valid until the next call.
         */
        fun frame(id: String, timeUs: Long, width: Int, height: Int): Bitmap?

        override fun close() {}
    }

    companion object {
        val NONE: ClipVideoFrames = object : ClipVideoFrames {
            override fun reader(): Reader = object : Reader {
                override fun frame(id: String, timeUs: Long, width: Int, height: Int): Bitmap? = null
            }
        }
    }
}

/**
 * The preview's video pictures, prepared ahead ([prepare]: [FPS] a second as small JPEGs, about 4 MB for 10 s at the
 * preview's size) and shared by every preview renderer, so playing and scrubbing never wait for the phone.
 */
class PreparedVideoFrames(private val videos: Map<String, Prepared>) : ClipVideoFrames {
    /** One video's pictures at times [timesUs] (into its played part); [key] says what they were made from. */
    class Prepared(val key: String, val timesUs: LongArray, val jpegs: List<ByteArray>) {
        val bytes: Long get() = jpegs.sumOf { it.size.toLong() }
    }

    /** The videos it has pictures of. */
    val ids: Set<String> get() = videos.keys

    override fun reader(): ClipVideoFrames.Reader = object : ClipVideoFrames.Reader {
        private var bitmap: Bitmap? = null
        private var shownId: String? = null
        private var shownIndex = -1

        override fun frame(id: String, timeUs: Long, width: Int, height: Int): Bitmap? {
            val v = videos[id] ?: return null
            if (v.jpegs.isEmpty()) return null
            val i = PreparedTimes.index(v.timesUs, timeUs)
            if (id == shownId && i == shownIndex) return bitmap
            val jpeg = v.jpegs[i]
            val (w, h) = jpegSize(jpeg) ?: return null
            // One picture reused while the size stays (every prepared picture is the preview's size).
            val into = bitmap?.takeIf { it.width == w && it.height == h && !it.isRecycled } ?: run {
                bitmap?.recycle()
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
            }
            if (!into.decodeInto(jpeg)) return null
            shownId = id
            shownIndex = i
            return into
        }

        /** The reader's picture is freed with it (the renderer closes its reader). */
        override fun close() {
            bitmap?.recycle()
            bitmap = null
            shownId = null
        }
    }

    companion object {
        /** Preview pictures a second of video. */
        const val FPS = 8
        const val JPEG_QUALITY = 78

        /**
         * [source]'s played part as [FPS] pictures a second at [width] × [height] (cropped to fill), from the phone's
         * [videos], in order. [key] is kept with them; [stillWanted] false stops it (what is in so far is kept).
         */
        fun prepare(source: VideoSource, width: Int, height: Int, key: String, videos: ClipVideoFiles, stillWanted: () -> Boolean): Prepared {
            val times = PreparedTimes.of(source.lengthUs, FPS)
            val inFile = LongArray(times.size) { source.startUs + times[it] }
            val jpegs = ArrayList<ByteArray>(times.size)
            val kept = ArrayList<Long>(times.size)
            videos.pictures(source.path, inFile, width, height) { i, picture ->
                if (!stillWanted()) return@pictures false
                picture?.let { cropToFill(it, width, height) }?.let {
                    jpegs += it
                    kept += times[i]
                }
                true
            }
            return Prepared(key, kept.toLongArray(), jpegs)
        }

        /**
         * The picture in [bytes] cropped around its middle to fill [width] × [height] ([VideoFit.crop], as Android
         * crops a video's picture), as a JPEG; null when it can't be read.
         */
        fun cropToFill(bytes: ByteArray, width: Int, height: Int): ByteArray? = runCatching {
            val data = Data.makeFromBytes(bytes)
            val codec = Codec.makeFromData(data)
            try {
                val pixels = codec.readPixels()
                val image = Image.makeFromBitmap(pixels)
                val surface = Surface.makeRasterN32Premul(width, height)
                try {
                    val c = VideoFit.crop(image.width, image.height, width, height)
                    val src = Rect.makeXYWH(c.left.toFloat(), c.top.toFloat(), (width * c.step).toFloat(), (height * c.step).toFloat())
                    val paint = Paint()
                    surface.canvas.drawImageRect(
                        image, src, Rect.makeWH(width.toFloat(), height.toFloat()), FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), paint, true,
                    )
                    paint.close()
                    val shot = surface.makeImageSnapshot()
                    try {
                        shot.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)?.let { d -> d.bytes.also { d.close() } }
                    } finally {
                        shot.close()
                    }
                } finally {
                    surface.close()
                    image.close()
                    pixels.close()
                }
            } finally {
                codec.close()
                data.close()
            }
        }.getOrNull()

        /** A JPEG's width and height, from its header. */
        private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? = runCatching {
            val data = Data.makeFromBytes(bytes)
            try {
                val codec = Codec.makeFromData(data)
                try {
                    codec.width to codec.height
                } finally {
                    codec.close()
                }
            } finally {
                data.close()
            }
        }.getOrNull()
    }
}

/** The preview's sampling of a video: times every 1/fps over [0, length), and the picture shown at a time (Android's). */
object PreparedTimes {
    fun of(lengthUs: Long, fps: Int): LongArray {
        val step = 1_000_000L / fps.coerceAtLeast(1)
        val n = ceil(lengthUs.coerceAtLeast(1).toDouble() / step).toInt().coerceAtLeast(1)
        return LongArray(n) { it * step }
    }

    /** The last picture at or before [timeUs] (the first one before it starts). */
    fun index(times: LongArray, timeUs: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= timeUs) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }
}

package com.justdataplease.boringtravel.clip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import kotlin.math.ceil

/** Where a phone video is and which part plays ([startUs], [lengthUs] into the file). */
data class VideoSource(val id: String, val file: File, val startUs: Long, val lengthUs: Long)

/**
 * The pictures of a clip's phone videos ([ClipVideo]) for the renderer. Each renderer takes its own [Reader] (a
 * renderer draws on one thread); [NONE] has none (the renderer then keeps the map under the insert).
 */
interface ClipVideoFrames {
    fun reader(): Reader

    interface Reader : Closeable {
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
 * preview's size) and shared by every preview renderer, so playing and scrubbing never wait for a decoder.
 */
class PreparedVideoFrames(private val videos: Map<String, Prepared>) : ClipVideoFrames {
    /** One video's pictures at times [timesUs] (into its played part); [key] says what they were made from. */
    class Prepared(val key: String, val timesUs: LongArray, val jpegs: List<ByteArray>) {
        val bytes: Long get() = jpegs.sumOf { it.size.toLong() }
    }

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
            val reuse = BitmapFactory.Options().apply { inMutable = true; inBitmap = bitmap }
            val decoded = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, reuse) }.getOrNull()
                ?: runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inMutable = true }) }.getOrNull()
                ?: return null
            bitmap = decoded
            shownId = id
            shownIndex = i
            return decoded
        }
    }

    companion object {
        /** Preview pictures a second of video. */
        const val FPS = 8
        const val JPEG_QUALITY = 78

        /**
         * [source]'s played part as [FPS] pictures a second at [width] × [height] (cropped to fill), decoded once in
         * order. [key] is kept with them; [stillWanted] throws to stop.
         */
        fun prepare(source: VideoSource, width: Int, height: Int, key: String, stillWanted: () -> Unit): Prepared {
            val times = PreparedTimes.of(source.lengthUs, FPS)
            val jpegs = ArrayList<ByteArray>(times.size)
            val kept = ArrayList<Long>(times.size)
            VideoDecoder(source, width, height).use { decoder ->
                val out = ByteArrayOutputStream(width * height / 4)
                for (t in times) {
                    stillWanted()
                    val frame = decoder.frameAt(t) ?: continue
                    out.reset()
                    frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    jpegs += out.toByteArray()
                    kept += t
                }
            }
            return Prepared(key, kept.toLongArray(), jpegs)
        }
    }
}

/** The preview's sampling of a video: times every 1/fps over [0, length), and the picture shown at a time. */
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

/**
 * The export's video pictures: each video decoded in order at the clip's size as the encoder reaches it (one decoder
 * at a time; asking for an earlier time starts it again).
 */
class DecodedVideoFrames(private val sources: Map<String, VideoSource>) : ClipVideoFrames {
    override fun reader(): ClipVideoFrames.Reader = object : ClipVideoFrames.Reader {
        private var decoder: VideoDecoder? = null
        private var failed: String? = null

        override fun frame(id: String, timeUs: Long, width: Int, height: Int): Bitmap? {
            val source = sources[id] ?: return null
            if (failed == id) return null
            var d = decoder
            if (d == null || d.source.id != id || d.width != width || d.height != height || timeUs < d.lastAskedUs) {
                d?.close()
                d = try {
                    VideoDecoder(source, width, height)
                } catch (e: Exception) {
                    Log.w("TripClip", "Couldn't open a video for the clip", e)
                    failed = id
                    null
                }
                decoder = d
            }
            return d?.let { runCatching { it.frameAt(timeUs) }.onFailure { e -> Log.w("TripClip", "Video frame failed", e) }.getOrNull() }
        }

        override fun close() {
            decoder?.close()
            decoder = null
        }
    }
}

/**
 * One phone video decoded forward (MediaExtractor → MediaCodec, YUV output images) into a [width] × [height] picture,
 * turned the way it was filmed and cropped to fill ([VideoFit]). Not thread-safe; [close] it.
 */
class VideoDecoder(val source: VideoSource, val width: Int, val height: Int) : Closeable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val rotation: Int
    private val bt709: Boolean
    private val info = MediaCodec.BufferInfo()
    private val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(width * height)
    private val yuv = YuvFrames()
    private var inputDone = false
    private var outputDone = false
    private var shown = false
    /** An output picture held for a later time (past the one asked for). */
    private var pending = -1
    private var pendingUs = 0L
    private var released = false

    /** The last time asked for; asking for an earlier one needs a new decoder. */
    var lastAskedUs = -1L
        private set

    init {
        val opened = try {
            open()
        } catch (e: Exception) {
            extractor.release()
            out.recycle()
            throw e
        }
        codec = opened.codec
        rotation = opened.rotation
        bt709 = opened.bt709
    }

    private class Opened(val codec: MediaCodec, val rotation: Int, val bt709: Boolean)

    private fun open(): Opened {
        extractor.setDataSource(source.file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("No picture track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
        val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else null
        val bt709 = if (standard != null) {
            standard != MediaFormat.COLOR_STANDARD_BT601_NTSC && standard != MediaFormat.COLOR_STANDARD_BT601_PAL
        } else h >= 720
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val c = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        try {
            c.configure(format, null, null, 0)
            c.start()
        } catch (e: Exception) {
            runCatching { c.release() }
            throw e
        }
        extractor.seekTo(source.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        return Opened(c, rotation, bt709)
    }

    /**
     * The picture [timeUs] into the played part: the last frame at or before it (the first frame when it comes later),
     * or null when the video gives none. The bitmap is reused by the next call.
     */
    fun frameAt(timeUs: Long): Bitmap? {
        check(!released) { "Closed" }
        lastAskedUs = timeUs
        val target = source.startUs + timeUs
        var candidate = -1
        if (pending >= 0) {
            if (pendingUs > target && shown) return out
            candidate = pending
            pending = -1
        }
        var idle = 0
        while (!outputDone && pending < 0) {
            if (!inputDone) feed()
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index >= 0 -> {
                    idle = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (info.size <= 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    if (info.presentationTimeUs <= target) {
                        if (candidate >= 0) codec.releaseOutputBuffer(candidate, false)
                        candidate = index
                    } else {
                        pending = index
                        pendingUs = info.presentationTimeUs
                    }
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (++idle > MAX_IDLE) break
                else -> Unit
            }
        }
        val show = when {
            candidate >= 0 -> candidate
            !shown && pending >= 0 -> pending.also { pending = -1 }
            else -> -1
        }
        if (show >= 0) {
            draw(show)
            codec.releaseOutputBuffer(show, false)
        }
        return if (shown) out else null
    }

    private fun feed() {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val buffer = requireNotNull(codec.getInputBuffer(index))
        val size = extractor.readSampleData(buffer, 0)
        val time = extractor.sampleTime
        if (size < 0 || time > source.startUs + source.lengthUs + TAIL_US) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, time, 0)
            extractor.advance()
        }
    }

    private fun draw(index: Int) {
        val image = runCatching { codec.getOutputImage(index) }.getOrNull() ?: return
        image.use {
            if (yuv.toArgb(it, rotation, bt709, width, height, pixels)) {
                out.setPixels(pixels, 0, width, 0, 0, width, height)
                shown = true
            }
        }
    }

    override fun close() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        extractor.release()
        out.recycle()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE = 300
        /** Frames are read a little past the part's end (B-frames come out of order). */
        const val TAIL_US = 300_000L
    }
}

/**
 * A decoded YUV 4:2:0 picture (8-bit, or 10-bit P010 read from its top 8 bits) to ARGB pixels: turned by its rotation
 * and cropped to fill the output ([VideoFit]), nearest pixel, BT.709 or BT.601 studio range. Reuses its buffers.
 */
internal class YuvFrames {
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)

    fun toArgb(image: Image, rotation: Int, bt709: Boolean, outW: Int, outH: Int, out: IntArray): Boolean {
        val p010 = image.format == P010
        if (image.format != ImageFormat.YUV_420_888 && !p010) return false
        val planes = image.planes
        if (planes.size < 3) return false
        val crop = image.cropRect
        val srcW = crop.width()
        val srcH = crop.height()
        if (srcW <= 0 || srcH <= 0) return false
        yBytes = copy(planes[0].buffer, yBytes)
        uBytes = copy(planes[1].buffer, uBytes)
        vBytes = copy(planes[2].buffer, vBytes)
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        // P010 keeps each sample in 16 bits, little-endian, the value in the top bits: read the high byte.
        val hi = if (p010) 1 else 0
        val (shownW, shownH) = VideoFit.shown(srcW, srcH, rotation)
        val fit = VideoFit.crop(shownW, shownH, outW, outH)
        val xs = IntArray(outW) { ((fit.left + (it + 0.5) * fit.step).toInt()).coerceIn(0, shownW - 1) }
        val ys = IntArray(outH) { ((fit.top + (it + 0.5) * fit.step).toInt()).coerceIn(0, shownH - 1) }
        // Stored pixel = (ax·x + bx·y + cx, ay·x + by·y + cy) for the turned picture's (x, y).
        val q = VideoFit.quarter(rotation)
        val ax = intArrayOf(1, 0, -1, 0)[q]
        val bx = intArrayOf(0, 1, 0, -1)[q]
        val cx = intArrayOf(0, 0, srcW - 1, srcW - 1)[q]
        val ay = intArrayOf(0, -1, 0, 1)[q]
        val by = intArrayOf(1, 0, -1, 0)[q]
        val cy = intArrayOf(0, srcH - 1, srcH - 1, 0)[q]
        val kr = if (bt709) 459 else 409
        val kgu = if (bt709) 55 else 100
        val kgv = if (bt709) 136 else 208
        val kb = if (bt709) 541 else 516
        val left = crop.left
        val top = crop.top
        val yMax = yBytes.size - 1
        val uMax = uBytes.size - 1
        val vMax = vBytes.size - 1
        for (oy in 0 until outH) {
            val y = ys[oy]
            val rowX = bx * y + cx
            val rowY = by * y + cy
            var o = oy * outW
            for (ox in 0 until outW) {
                val x = xs[ox]
                val px = ax * x + rowX + left
                val py = ay * x + rowY + top
                val c = ((yBytes[(py * yRow + px * yPix + hi).coerceAtMost(yMax)].toInt() and 0xFF) - 16).coerceAtLeast(0) * 298
                val cxi = px shr 1
                val cyi = py shr 1
                val d = (uBytes[(cyi * uRow + cxi * uPix + hi).coerceAtMost(uMax)].toInt() and 0xFF) - 128
                val e = (vBytes[(cyi * vRow + cxi * vPix + hi).coerceAtMost(vMax)].toInt() and 0xFF) - 128
                val r = ((c + kr * e + 128) shr 8).coerceIn(0, 255)
                val g = ((c - kgu * d - kgv * e + 128) shr 8).coerceIn(0, 255)
                val b = ((c + kb * d + 128) shr 8).coerceIn(0, 255)
                out[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return true
    }

    private fun copy(buffer: java.nio.ByteBuffer, into: ByteArray): ByteArray {
        val b = buffer.duplicate()
        b.rewind()
        val n = b.remaining()
        val arr = if (into.size >= n) into else ByteArray(n)
        b.get(arr, 0, n)
        return arr
    }

    private companion object {
        /** ImageFormat.YCBCR_P010 (API 31). */
        const val P010 = 0x36
    }
}

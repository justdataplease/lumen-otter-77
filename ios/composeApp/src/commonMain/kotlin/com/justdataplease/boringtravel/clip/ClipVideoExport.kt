package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.compat.graphics.Bitmap
import com.justdataplease.boringtravel.ios.ClipVideoFiles
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Image as SkImage

/*
 * The export's phone-video pictures (round 20): the Android app's DecodedVideoFrames and VideoDecoder (clip/
 * ClipVideoFrames.kt) written again over the phone's decoder ([DecodedVideo]: AVAssetReader on iPhone, where Android
 * uses MediaExtractor and MediaCodec). Each video is read forward once, every picture in order, at the clip's size; the
 * picture shown at a time is the last one at or before it (the first one before the video's first picture), exactly as
 * Android picks it, turned the way the video was filmed and cropped to fill the frame ([VideoFit], read in).
 */

/** A phone video read forward, picture after picture (the phone's decoder). Not thread-safe; [close] it. */
interface DecodedVideo : AutoCloseable {
    /** Degrees the stored pictures turn clockwise to be seen as filmed (0, 90, 180 or 270; Android's KEY_ROTATION). */
    val rotation: Int

    /**
     * The next picture in time order: [use] gets it as stored (valid only during the call) with its time into the file;
     * false when there are no more.
     */
    fun next(use: (picture: SkImage, timeUs: Long) -> Unit): Boolean
}

/**
 * The export's video pictures: each video decoded in order at the clip's size as the export reaches it (one decoder at
 * a time; asking for an earlier time starts it again), as Android's DecodedVideoFrames.
 */
class DecodedVideoFrames(private val sources: Map<String, VideoSource>, private val files: ClipVideoFiles) : ClipVideoFrames {
    override fun reader(): ClipVideoFrames.Reader = object : ClipVideoFrames.Reader {
        private var decoder: VideoDecoder? = null
        private var failed: String? = null

        override fun frame(id: String, timeUs: Long, width: Int, height: Int): Bitmap? {
            val source = sources[id] ?: return null
            if (failed == id) return null
            var d = decoder
            if (d == null || d.source.id != id || d.width != width || d.height != height || timeUs < d.lastAskedUs) {
                d?.close()
                d = files.open(source.path, source.startUs, source.lengthUs)?.let { VideoDecoder(source, it, width, height) }
                if (d == null) failed = id
                decoder = d
            }
            return d?.let { runCatching { it.frameAt(timeUs) }.getOrNull() }
        }

        override fun close() {
            decoder?.close()
            decoder = null
        }
    }
}

/**
 * One phone video decoded forward into a [width] × [height] picture, turned the way it was filmed and cropped to fill
 * ([VideoFit]), as Android's VideoDecoder. Not thread-safe; [close] it.
 */
class VideoDecoder(val source: VideoSource, private val video: DecodedVideo, val width: Int, val height: Int) : AutoCloseable {
    private var out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** A picture past the time asked for, kept for a later time (Android keeps the codec's output buffer). */
    private var pending = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var pendingUs: Long? = null
    private var shown = false
    private var ended = false
    private var released = false
    private val paint = Paint()

    /** The last time asked for; asking for an earlier one needs a new decoder. */
    var lastAskedUs = -1L
        private set

    /**
     * The picture [timeUs] into the played part: the last one at or before it (the first one when it comes later), or
     * null when the video gives none. The bitmap is reused by the next call.
     */
    fun frameAt(timeUs: Long): Bitmap? {
        check(!released) { "Closed" }
        lastAskedUs = timeUs
        val target = source.startUs + timeUs
        pendingUs?.let { at ->
            if (at > target && shown) return out
            // The held picture is the one to show now (or the first there is).
            swap()
        }
        while (!ended && pendingUs == null) {
            val more = video.next { picture, at ->
                if (at <= target) {
                    draw(picture, out)
                    shown = true
                } else {
                    draw(picture, pending)
                    pendingUs = at
                }
            }
            if (!more) ended = true
        }
        if (!shown && pendingUs != null) swap()
        return if (shown) out else null
    }

    private fun swap() {
        val held = pending
        pending = out
        out = held
        pendingUs = null
        shown = true
    }

    /** [picture] as stored, turned by the video's rotation and cropped to fill [into] ([VideoFit], as Android's YuvFrames). */
    private fun draw(picture: SkImage, into: Bitmap) {
        val srcW = picture.width
        val srcH = picture.height
        if (srcW <= 0 || srcH <= 0) return
        val (shownW, shownH) = VideoFit.shown(srcW, srcH, video.rotation)
        val fit = VideoFit.crop(shownW, shownH, width, height)
        val c = into.canvas()
        c.save()
        // The shown picture's crop to the output: (x, y) shown → ((x − left) / step, (y − top) / step).
        c.scale((1.0 / fit.step).toFloat(), (1.0 / fit.step).toFloat())
        c.translate(-fit.left.toFloat(), -fit.top.toFloat())
        // The stored picture turned into the shown one (clockwise, as KEY_ROTATION and the preferred transform turn it).
        when (VideoFit.quarter(video.rotation)) {
            1 -> { c.translate(shownW.toFloat(), 0f); c.rotate(90f) }
            2 -> { c.translate(shownW.toFloat(), shownH.toFloat()); c.rotate(180f) }
            3 -> { c.translate(0f, shownH.toFloat()); c.rotate(270f) }
            else -> Unit
        }
        c.drawImageRect(
            picture, Rect.makeWH(srcW.toFloat(), srcH.toFloat()), Rect.makeWH(srcW.toFloat(), srcH.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), paint, true,
        )
        c.restore()
        into.drawn()
    }

    override fun close() {
        if (released) return
        released = true
        runCatching { video.close() }
        out.recycle()
        pending.recycle()
        paint.close()
    }
}

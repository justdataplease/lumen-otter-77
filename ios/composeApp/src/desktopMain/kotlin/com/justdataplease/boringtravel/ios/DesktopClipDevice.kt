package com.justdataplease.boringtravel.ios

import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.TimeSource

/*
 * The trip video's editor on the desktop, where the iPhone's screens are previewed (`gradlew :composeApp:run`, the
 * screenshots): no sound and no microphone, so stand-ins that keep the screens working, and a phone video's pictures
 * drawn (the iPhone takes them from the video with AVAssetImageGenerator). Since round 20 the export too: the desktop's
 * writer (a Motion-JPEG AVI the desktop can play, DesktopVideoWriter.kt), the sound silent (the JVM can't read AAC), a
 * phone video's pictures drawn in order, and no share sheet or photo library.
 */

/** The desktop's stand-ins: silent music, a microphone that records a placeholder, drawn videos, a real AVI for an export. */
fun desktopClipDevice(files: MediaFiles): ClipDevice =
    ClipDevice(SilentSounds(), PlaceholderMicrophone(), DrawnVideoFiles(files), DesktopVideoWriter(files, realFile = true, checksums = false), SilentDecoder())

/**
 * Every sound file as silence of the length asked (the JVM has no AAC decoder): the bundled music as 44.1 kHz stereo,
 * anything else mono, so an export on the desktop makes its sound track as the iPhone does, without sound in it.
 */
class SilentDecoder : com.justdataplease.boringtravel.clip.music.SoundDecoder {
    override fun decode(location: String, fromUs: Long, lengthUs: Long, stillWanted: () -> Unit): com.justdataplease.boringtravel.clip.music.Pcm? {
        val stereo = "music/" in location
        val rate = 44_100
        val channels = if (stereo) 2 else 1
        val frames = (lengthUs.coerceAtMost(600_000_000L) * rate / 1_000_000).toInt()
        return if (frames <= 0) null else com.justdataplease.boringtravel.clip.music.Pcm(ShortArray(frames * channels), frames, rate, channels)
    }
}

/** Music without sound: a player that keeps time as the iPhone's would, and ends at the track's end. */
class SilentSounds(private val trackMs: Int = 120_000) : ClipSounds {
    override fun open(uri: String, onEnd: () -> Unit): TrackPlayer = SilentTrack(trackMs)
}

private class SilentTrack(private val lengthMs: Int) : TrackPlayer {
    private var from = 0
    private var started: TimeSource.Monotonic.ValueTimeMark? = null

    override val positionMs: Int
        get() = (from + (started?.elapsedNow()?.inWholeMilliseconds ?: 0L).toInt()).coerceAtMost(lengthMs)

    override val isPlaying: Boolean get() = started != null && positionMs < lengthMs

    override fun play(fromMs: Int) {
        from = fromMs
        started = TimeSource.Monotonic.markNow()
    }

    override fun seekTo(ms: Int) {
        from = ms
        if (started != null) started = TimeSource.Monotonic.markNow()
    }

    override fun pause() {
        from = positionMs
        started = null
    }

    override fun release() {
        started = null
    }
}

/** A microphone that writes a short placeholder file, so a line's steps can be tried on the desktop (it has no sound). */
class PlaceholderMicrophone : Microphone {
    override val access: MicAccess get() = MicAccess.Allowed

    override fun ask(done: (Boolean) -> Unit) = done(true)

    override fun record(absolutePath: String, maxMs: Long): MicRecording = object : MicRecording {
        override fun stop(): Boolean = runCatching { java.io.File(absolutePath).writeBytes(PLACEHOLDER) }.isSuccess

        override fun cancel() {
            java.io.File(absolutePath).delete()
        }
    }

    private companion object {
        val PLACEHOLDER = "BoringTravel desktop voice line (no sound)".encodeToByteArray()
    }
}

/**
 * Phone videos on the desktop: any file with a video's extension is a [lengthMs] video of [width] × [height] (the
 * desktop can't read a video), and its pictures are drawn: a sky that turns with the time, a sun going across and a
 * bar of the time at the foot, so the preview shows the video moving where the iPhone shows its real pictures.
 */
class DrawnVideoFiles(
    private val files: MediaFiles, private val lengthMs: Long = 8_000, private val width: Int = 1080, private val height: Int = 1920,
) : ClipVideoFiles {
    override fun probe(path: String): VideoFacts? {
        if (!MediaTypes.isVideo(MediaTypes.ofExtension(path.substringAfterLast('.', ""))) || (files.size(path) ?: 0L) <= 0L) return null
        return VideoFacts(lengthMs, width, height)
    }

    /** The video's first picture, at most [maxPx] on its longest side (a journal video's tile, as the iPhone's poster). */
    fun poster(path: String, maxPx: Int): ByteArray? {
        val facts = probe(path) ?: return null
        val scale = maxPx.toDouble() / max(facts.width, facts.height)
        return drawn(0.0, (facts.width * scale).roundToInt().coerceAtLeast(2), (facts.height * scale).roundToInt().coerceAtLeast(2))
    }

    /** The video's every picture at 30 a second, drawn as [pictures] draws them (the export reads it forward). */
    override fun open(path: String, fromUs: Long, lengthUs: Long): com.justdataplease.boringtravel.clip.DecodedVideo? {
        val facts = probe(path) ?: return null
        return object : com.justdataplease.boringtravel.clip.DecodedVideo {
            private var at = fromUs
            override val rotation: Int = 0

            override fun next(use: (picture: org.jetbrains.skia.Image, timeUs: Long) -> Unit): Boolean {
                if (at > fromUs + lengthUs + 300_000 || at > facts.durationMs * 1000) return false
                val png = drawn(at / 1000.0 / facts.durationMs, 270, 480) ?: return false
                val image = org.jetbrains.skia.Image.makeFromEncoded(png)
                try {
                    use(image, at)
                } finally {
                    image.close()
                }
                at += 33_333
                return true
            }

            override fun close() {}
        }
    }

    override fun pictures(path: String, timesUs: LongArray, width: Int, height: Int, each: (Int, ByteArray?) -> Boolean) {
        val facts = probe(path)
        for (i in timesUs.indices) {
            val picture = facts?.let { drawn(timesUs[i] / 1000.0 / it.durationMs, max(width, 2), max(height, 2)) }
            if (!each(i, picture)) return
        }
    }

    /** The picture at [share] of the way through the video, as a PNG. */
    private fun drawn(share: Double, w: Int, h: Int): ByteArray? {
        val surface = Surface.makeRasterN32Premul(w, h)
        try {
            val c = surface.canvas
            val turn = (share.coerceIn(0.0, 1.0) * 2 * PI)
            val sky = Color.makeRGB((60 + 40 * cos(turn)).roundToInt(), (110 + 50 * sin(turn)).roundToInt(), 190)
            c.clear(sky)
            val paint = Paint().apply { isAntiAlias = true }
            // The sea, the sun crossing it, and how far the video is.
            paint.color = Color.makeRGB(20, 70, 120)
            c.drawRect(Rect.makeXYWH(0f, h * 0.62f, w.toFloat(), h * 0.38f), paint)
            paint.color = Color.makeRGB(255, 205, 70)
            c.drawCircle((w * (0.15 + 0.7 * share)).toFloat(), h * 0.45f, w * 0.09f, paint)
            paint.color = Color.makeARGB(200, 255, 255, 255)
            c.drawRect(Rect.makeXYWH(0f, h - h * 0.02f, (w * share).toFloat(), h * 0.02f), paint)
            paint.mode = PaintMode.STROKE
            paint.strokeWidth = w * 0.01f
            c.drawRect(Rect.makeXYWH(w * 0.04f, h * 0.04f, w * 0.92f, h * 0.92f), paint)
            paint.close()
            val image = surface.makeImageSnapshot()
            try {
                return image.encodeToData(EncodedImageFormat.PNG)?.let { d -> d.bytes.also { d.close() } }
            } finally {
                image.close()
            }
        } finally {
            surface.close()
        }
    }
}

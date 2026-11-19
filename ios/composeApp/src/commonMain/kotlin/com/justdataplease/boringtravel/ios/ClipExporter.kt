package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.compat.diag
import com.justdataplease.boringtravel.clip.BaseMap
import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipStyle
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.clip.ClipVideoFrames
import com.justdataplease.boringtravel.clip.DecodedVideoFrames
import com.justdataplease.boringtravel.clip.ExportBar
import com.justdataplease.boringtravel.clip.Mp4Encoder
import com.justdataplease.boringtravel.clip.VideoSource
import com.justdataplease.boringtravel.clip.music.ClipMusic
import com.justdataplease.boringtravel.clip.music.MixedSound
import com.justdataplease.boringtravel.clip.music.MusicTrack
import com.justdataplease.boringtravel.clip.music.SoundDecoder
import com.justdataplease.boringtravel.compat.graphics.Bitmap
import com.justdataplease.boringtravel.time.nowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The trip video's export (round 20): the Android app's ClipExport.make, Mp4Encoder's settings and ClipMusic's steps,
 * written again as common Kotlin over the phone's writer ([VideoWriter]). What it does, in order:
 *
 *  1. Checks there is room for the video (the pictures, the finished file and its copy in the trip's Media).
 *  2. Starts the sound alongside (the music from its start with its fades, the voice lines and the phone videos' own
 *     sound: [ClipMusic.prepare], Android's mix), as Android makes it while the frames render.
 *  3. Draws every frame in order with the clip's own renderer ([ClipFrames], what the preview draws), the phone videos'
 *     pictures read forward exactly ([DecodedVideoFrames]), and hands each to the writer with its number. The frames go
 *     into picture-only parts: an iPhone's video encoder stops when the app leaves the screen, so a part is finished as
 *     soon as the app goes, the export waits, and a new part starts from the next frame when it is back. A part the
 *     phone broke off (the app left mid-frame) is made again from its first frame.
 *  4. Joins the parts' pictures, copied as they are, and the sound encoded to AAC into one MP4 ([VideoWriter.join]); if
 *     the sound can't go in, the video is joined silent (Android's "Couldn't add the music, so this video is silent.").
 *  5. Moves the finished file over [ExportJob.output] only once it is whole: a cancel or a failure never leaves a broken
 *     file, and the last good clip stays (Android's ClipFiles).
 *
 * The progress is Android's ExportBar (read in): the frames most of the bar, then the sound, never 100 % before done.
 */

/** Everything one export draws and mixes (the studio's clip as it stood when Create video was tapped). */
class ExportJob(
    val plan: ClipPlan,
    val base: BaseMap,
    val style: ClipStyle,
    val photos: Map<String, Bitmap>,
    /** Leave out the mark (Pro on sale only; false here). */
    val noMark: Boolean,
    val design: ClipDesign,
    /** The phone videos the clip plays, by id. */
    val videos: Map<String, VideoSource>,
    /** The chosen music (null: none) and where its file is ([trackLocation]), played from [musicStartUs]. */
    val track: MusicTrack?,
    val trackLocation: String?,
    val musicStartUs: Long,
    /** The voice lines and the phone videos' own sounds laid over the music. */
    val sounds: List<ClipMusic.Sound>,
    /** Where the finished MP4 goes, under the app's files. */
    val output: String,
) {
    val hasSound: Boolean get() = track != null || sounds.isNotEmpty()
}

/** A finished export: the MP4 at [path], and whether the chosen music couldn't go in ([musicFailed]: the video is silent). */
data class MadeClip(
    val path: String,
    val musicFailed: Boolean,
    /** A frame from 40 % in as a JPEG (the trip's Media shows it), at most [ClipExporter.THUMB_SIDE] on its long side. */
    val thumb: ByteArray?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    /** How many picture parts it took (1 unless the app left the screen while the frames were made). */
    val parts: Int,
)

/** Why an export stopped short (the screen says Android's "Couldn't make the video on this phone…"). */
class ExportFailed(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

/** What the export draws each frame with: the clip's own renderer ([ClipFrames]); a test may draw something quicker. */
fun interface ExportFrames : AutoCloseable {
    /** Frame [frame] of the clip into [into] (a bitmap of the export's size). */
    fun draw(frame: Int, into: Bitmap)

    override fun close() {}
}

/**
 * Makes trip videos with the phone's [writer], [decoder] and [videos], keeping its files under [files]. [onScreen] says
 * whether the app is on screen (the writer's encoder works only then: the export waits while it isn't); [background]
 * asks the phone for time to finish a part when the app leaves.
 */
class ClipExporter(
    private val files: MediaFiles,
    private val writer: VideoWriter,
    private val decoder: SoundDecoder,
    private val videos: ClipVideoFiles,
    private val onScreen: StateFlow<Boolean>,
    private val background: BackgroundWork = NoBackgroundWork,
) {
    /** Each time the app left the screen during this export (a part broken off then is made again, not a failure). */
    @Volatile
    private var leftScreen = 0

    /**
     * How far the sound is (0–1) while the frames are drawn: kept here and handed to the bar by the export's own
     * coroutine, since Android's ExportBar is called from one thread at a time (its @Synchronized has no common form).
     */
    @Volatile
    private var soundPart = 0f

    /** How the frames are drawn: [ClipFrames], as the preview draws them (tests: something quicker). */
    var frames: (job: ExportJob, spec: VideoSpec, videos: ClipVideoFrames) -> ExportFrames = { job, spec, videos ->
        val drawn = ClipFrames(job.plan, job.base, job.style, job.photos, job.noMark, job.design, spec.width, spec.height, videos)
        object : ExportFrames {
            override fun draw(frame: Int, into: Bitmap) {
                drawn.draw(frame, into)
            }

            override fun close() = drawn.close()
        }
    }

    /**
     * Makes [job]'s MP4, reporting to [bar]. Throws [ExportFailed] when the phone couldn't (or hasn't the room);
     * cancelling stops it, and either way the parts are deleted and [ExportJob.output] stays as it was.
     */
    suspend fun make(job: ExportJob, bar: ExportBar): MadeClip = coroutineScope {
        val plan = job.plan
        val spec = spec(plan.options.shape.width, plan.options.shape.height, plan.fps)
        val run = "${nowMs()}-${(0..9999).random()}"
        val partsDir = "$PARTS/$run"
        // 1. Room for the pictures, the finished file and the trip's Media copy (a clone on an iPhone, but counted).
        val need = estimateBytes(spec, plan.frameCount, job.hasSound) * 5 / 2 + SPARE_BYTES
        writer.freeBytes()?.let { free -> if (free < need) throw ExportFailed("Not enough space: $free bytes free, $need needed") }
        val watcher = launch { onScreen.collect { if (!it) leftScreen++ } }
        var ticket = background.beginBackgroundWork(TASK_NAME)
        try {
            // 2. The sound alongside the frames (a child: cancelling the export stops it too).
            val durationUs = plan.frameCount * 1_000_000L / plan.fps
            soundPart = 0f
            val sound = if (!job.hasSound) null else async(Dispatchers.Default) {
                runCatching {
                    ClipMusic.prepare(decoder, durationUs, job.track, job.trackLocation, job.musicStartUs, job.sounds) { soundPart = max(soundPart, it) }
                }
            }
            // 3. The frames, in parts (one part when the phone lets the export go on away from the screen: iOS 26).
            val drawn = drawFrames(
                job, spec, partsDir, bar,
                goesOnAway = { ticket.keepsGoingAway },
                progress = { ticket.progressBackgroundWork(it * FRAMES_SHARE) },
            ) { ticket.endBackgroundWork(); ticket = background.beginBackgroundWork(TASK_NAME) }
            ticket.progressBackgroundWork(FRAMES_SHARE)
            bar.sound(soundPart)
            bar.framesDone()
            // 4. The sound under the pictures (or the pictures alone).
            val made = sound?.await()
            bar.sound(soundPart)
            val mixed = made?.getOrElse { e -> if (e is CancellationException) throw e; diag("the music couldn't be mixed: $e"); null }
            var musicFailed = made != null && made.isFailure
            val finished = "$partsDir/clip.mp4"
            val joined = if (mixed == null && drawn.parts.size == 1) {
                drawn.parts.single().path
            } else {
                val withSound = mixed != null && runCatching { joinRetrying(finished, spec, drawn.parts, mixed, bar) { ticket.keepsGoingAway } }
                    .onFailure { if (it is CancellationException) throw it; diag("the sound couldn't be joined in: $it") }.isSuccess
                if (mixed != null && !withSound) musicFailed = true
                if (withSound) finished else if (drawn.parts.size == 1) drawn.parts.single().path else {
                    joinRetrying(finished, spec, drawn.parts, null, bar) { ticket.keepsGoingAway }
                    finished
                }
            }
            ticket.progressBackgroundWork(0.99f)
            bar.finishing()
            currentCoroutineContext().ensureActive()
            // 5. Whole: over the last clip.
            if ((files.size(joined) ?: 0L) <= 0L || !files.replace(joined, job.output)) throw ExportFailed("Couldn't keep the finished video")
            MadeClip(job.output, musicFailed, drawn.thumb, durationUs / 1000, spec.width, spec.height, drawn.parts.size)
        } finally {
            watcher.cancel()
            ticket.endBackgroundWork()
            files.delete(partsDir)
        }
    }

    private class Drawn(val parts: List<PicturePart>, val thumb: ByteArray?)

    /**
     * Every frame of [job]'s clip into picture parts under [dir]; a part ends when the app leaves the screen, and the
     * next starts once it is back ([cameBack] runs then: a new ask for background time). While [goesOnAway] (iOS 26's
     * continued task) the pictures go on into the same part away from the screen; if the phone's encoder stops there
     * anyway, the part is made again once the app is back and the rest of this export waits for the screen as before.
     * [progress] gets how far the frames are, 0–1.
     */
    private suspend fun drawFrames(
        job: ExportJob, spec: VideoSpec, dir: String, bar: ExportBar,
        goesOnAway: () -> Boolean = { false }, progress: (Float) -> Unit = {}, cameBack: () -> Unit,
    ): Drawn = withContext(Dispatchers.Default) {
        val plan = job.plan
        val total = plan.frameCount
        val thumbAt = total * 2 / 5
        val parts = ArrayList<PicturePart>()
        var thumb: ByteArray? = null
        val frames = frames(job, spec, if (job.videos.isEmpty()) ClipVideoFrames.NONE else DecodedVideoFrames(job.videos, videos))
        val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        var f = 0
        var failures = 0
        // Set once the encoder stopped away from the screen although the phone let the export go on.
        var encoderStopsAway = false
        fun mayGoOn() = onScreen.value || (!encoderStopsAway && goesOnAway())
        try {
            while (f < total) {
                if (!mayGoOn()) {
                    onScreen.first { it }
                    cameBack()
                }
                val first = f
                val awayBefore = leftScreen
                val path = "$dir/part-${parts.size + 1}-$failures.mp4"
                var file: PictureFile? = null
                try {
                    file = writer.openPicture(path, spec)
                    while (f < total && mayGoOn()) {
                        ensureActive()
                        frames.draw(f, bitmap)
                        if (f == thumbAt) thumb = thumbnail(bitmap)
                        file.append(bitmap, f - first)
                        f++
                        bar.frames(f.toFloat() / total)
                        progress(f.toFloat() / total)
                    }
                    val done = file.finish(f - first)
                    file = null
                    if (!done) throw VideoWriteFailed("A part of the video didn't finish")
                    if (f > first) parts += PicturePart(path, first, f - first) else files.delete(path)
                    failures = 0
                } catch (e: VideoWriteFailed) {
                    file?.cancel()
                    files.delete(path)
                    f = first
                    // The phone stops its encoder when the app leaves the screen: that part is made again once it is
                    // back. A failure on screen, again and again, ends the export.
                    val away = leftScreen != awayBefore || !onScreen.value
                    if (away && goesOnAway()) encoderStopsAway = true
                    if (!away && ++failures >= MAX_FAILURES) throw ExportFailed(e.reason, e)
                } catch (e: Throwable) {
                    // Cancelled, or the drawing failed: the open part is let go of (its encoder stopped) first.
                    file?.cancel()
                    throw e
                }
            }
            Drawn(parts, thumb)
        } catch (e: Throwable) {
            parts.forEach { files.delete(it.path) }
            throw e
        } finally {
            frames.close()
            bitmap.recycle()
        }
    }

    /**
     * [VideoWriter.join], made again when the app left the screen meanwhile (the phone may have broken it off); away from
     * the screen it waits for the app unless the phone lets the export go on ([goesOnAway]: iOS 26's continued task).
     */
    private suspend fun joinRetrying(path: String, spec: VideoSpec, parts: List<PicturePart>, sound: MixedSound?, bar: ExportBar, goesOnAway: () -> Boolean = { false }) {
        var failures = 0
        var stoppedAway = false
        while (true) {
            if (!onScreen.value && (stoppedAway || !goesOnAway())) onScreen.first { it }
            val awayBefore = leftScreen
            try {
                withContext(Dispatchers.Default) {
                    val wanted: () -> Unit = { ensureActive() }
                    writer.join(path, spec, parts, sound, progress = { p -> if (sound != null) bar.sound(SOUND_DECODED + (1 - SOUND_DECODED) * p) }, stillWanted = wanted)
                }
                return
            } catch (e: VideoWriteFailed) {
                files.delete(path)
                val away = leftScreen != awayBefore || !onScreen.value
                if (away) stoppedAway = true
                if (!away && ++failures >= MAX_FAILURES) throw ExportFailed(e.reason, e)
            }
        }
    }

    companion object {
        /**
         * What the phone calls the work (iPhone: the background task's name, and on iOS 26 the title the system shows
         * with the export's progress: the Export sheet's own words).
         */
        const val TASK_NAME = "Making the video"

        /** The frames' share of the export's progress the system shows (the sound and the join are the rest). */
        const val FRAMES_SHARE = 0.9f

        /** The parts and the joined file, under the app's files (in exports/, emptied when the app starts). */
        const val PARTS = "exports/.parts"

        /** Tries of one part or the join while the app stays on screen before the export gives up. */
        const val MAX_FAILURES = 3

        /** Room kept free besides the video's own (the phone needs some to go on). */
        const val SPARE_BYTES = 50L * 1024 * 1024

        /** The thumbnail's long side (Android's SavedClips.THUMB_SIDE) and its JPEG quality. */
        const val THUMB_SIDE = 480
        const val THUMB_QUALITY = 82

        /** Where the sound is once it is decoded and mixed ([ClipMusic.prepare]'s last step): the writer's share follows. */
        const val SOUND_DECODED = 0.25f

        /** The pictures' settings for a [width] × [height] clip at [fps]: Android's Mp4Encoder's. */
        fun spec(width: Int, height: Int, fps: Int): VideoSpec =
            VideoSpec(width, height, fps, Mp4Encoder.defaultBitRate(width, height, fps), Mp4Encoder.KEY_FRAME_SECONDS * fps)

        /** About how big the finished file is: the pictures at their bit rate and the sound at its. */
        fun estimateBytes(spec: VideoSpec, frames: Int, hasSound: Boolean): Long {
            val seconds = frames.toDouble() / spec.fps
            val bits = spec.bitRate * seconds + if (hasSound) ClipMusic.BIT_RATE * seconds else 0.0
            return (bits / 8 * 1.1).toLong()
        }

        /** Where a finished clip of [title] goes under the app's files (Android: cache/exports/<title>-clip.mp4). */
        fun outputPath(title: String): String = "exports/" + ClipText.fileName(title)

        /** [frame] made small (its long side [THUMB_SIDE]) as a JPEG, as Android's SavedClips keeps a clip's picture. */
        fun thumbnail(frame: Bitmap): ByteArray? = runCatching {
            val scale = THUMB_SIDE.toFloat() / max(frame.width, frame.height).coerceAtLeast(1)
            val w = if (scale < 1f) (frame.width * scale).roundToInt().coerceAtLeast(1) else frame.width
            val h = if (scale < 1f) (frame.height * scale).roundToInt().coerceAtLeast(1) else frame.height
            val surface = Surface.makeRasterN32Premul(w, h)
            try {
                val paint = org.jetbrains.skia.Paint()
                surface.canvas.drawImageRect(
                    frame.image(), Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), paint, true,
                )
                paint.close()
                val shot = surface.makeImageSnapshot()
                try {
                    shot.encodeToData(EncodedImageFormat.JPEG, THUMB_QUALITY)?.let { d -> d.bytes.also { d.close() } }
                } finally {
                    shot.close()
                }
            } finally {
                surface.close()
            }
        }.getOrNull()
    }
}

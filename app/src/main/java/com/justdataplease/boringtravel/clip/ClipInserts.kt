package com.justdataplease.boringtravel.clip

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A video from the phone in the trip clip (owner: "Your phone videos"): copied into files/clip-media/<trip>/ ([file],
 * see [ClipMedia]) and played full frame after the place name [sceneKey]'s map scene (where the head reaches the
 * place: a day's end, a planned place's arrival), cropped to fill the clip's shape. [startMs] and [lengthMs] trim it
 * (at most [MAX_MS]); [sound] keeps its own sound quietly under the music (off by default). The clip gets longer by its
 * length. Kept with the scene edits ([ClipEdits.videos]), per trip and route. [source] is the journal video it was
 * copied from (its media id; the Add media sheet marks that one as in the clip), null for one from the phone's picker.
 */
data class ClipVideo(
    val id: String,
    val file: String,
    val sceneKey: String,
    val durationMs: Long,
    val startMs: Long = 0L,
    val lengthMs: Long = min(durationMs, DEFAULT_MS),
    val sound: Boolean = false,
    val source: String? = null,
) {
    /** The trimmed part, kept inside the video and within [MIN_MS]..[MAX_MS]. */
    fun trimmed(startMs: Long = this.startMs, lengthMs: Long = this.lengthMs): ClipVideo {
        val longest = min(MAX_MS, durationMs).coerceAtLeast(1L)
        val length = lengthMs.coerceIn(min(MIN_MS, longest), longest)
        val start = startMs.coerceIn(0L, (durationMs - length).coerceAtLeast(0L))
        return copy(startMs = start, lengthMs = length)
    }

    /** What the plan needs: where it goes and how long it plays. */
    val slot: VideoSlot get() = VideoSlot(id, sceneKey, lengthMs / 1000.0)

    companion object {
        /** The longest part of a video the clip plays. */
        const val MAX_MS = 10_000L
        const val MIN_MS = 1_000L
        /** A new video plays its first 5 s (or all of it when shorter). */
        const val DEFAULT_MS = 5_000L
        /** At most this many videos in one clip. */
        const val MAX_VIDEOS = 8
    }
}

/** A phone video's place in the plan: after the place name [sceneKey], [seconds] long. */
data class VideoSlot(val id: String, val sceneKey: String, val seconds: Double)

/**
 * Which days open with a full-frame day card (owner: "Voice-over and day cards"): every day when [on], except the day
 * keys in [hidden]; with it off, only the ones in [shown] ([ClipDesign.dayCardKey]).
 */
data class DayCardChoice(val on: Boolean = false, val shown: Set<String> = emptySet(), val hidden: Set<String> = emptySet()) {
    fun shows(key: String): Boolean = key in shown || (on && key !in hidden)
    val any: Boolean get() = on || shown.isNotEmpty()
}

/**
 * A day of the clip that can open with a day card: "Day 14" ([day]; a date when the day falls outside the trip), the
 * place the day starts with ([place]) and its [date]. [key] is the card's edit key ([ClipDesign.dayCardKey]); [opens]
 * the place name it comes before (where that scene's voice line starts).
 */
data class DayCard(val key: String, val date: LocalDate, val day: String?, val place: String?, val opens: String)

enum class InsertKind { Video, DayCard }

/**
 * Something full frame the journey stops for during frames [fromFrame, toFrame): a phone video ([id]) after the place
 * name [key], or a day card ([card]; [key] its own key) before the day's first place name.
 */
class Insert(val kind: InsertKind, val key: String, val fromFrame: Int, val toFrame: Int, val id: String? = null, val card: DayCard? = null) {
    val frames: Int get() = toFrame - fromFrame
}

/**
 * A voice line the traveller recorded for a place name or a day ([sceneKey]; owner: "Voice-over"): an AAC file in
 * files/clip-voice/<trip>/ ([file]), [durationMs] long, played from that scene's start ([ClipPlan.sceneStart]) with
 * the music ducked under it. One per scene, kept with the scene edits ([ClipEdits.voices]).
 */
data class VoiceLine(val sceneKey: String, val file: String, val durationMs: Long) {
    companion object {
        /** A voice line stops by itself after this long. */
        const val MAX_MS = 20_000L
        /** Shorter than this is a slip of the finger, not a line. */
        const val MIN_MS = 400L
    }
}

/** How a full-frame insert fades in and out: quickly, so a cut on the beat still reads as a cut. */
object InsertFade {
    /** Frames of the fade at each end (at most a quarter of the insert). */
    fun frames(length: Int, fps: Int): Int = min((fps * 0.12).roundToInt().coerceAtLeast(1), length / 4).coerceAtLeast(0)

    /** The insert's opacity at frame [f] of [from, to). */
    fun alpha(f: Int, from: Int, to: Int, fps: Int): Float {
        if (f < from || f >= to) return 0f
        val n = frames(to - from, fps)
        if (n <= 0) return 1f
        val inA = (f - from + 1).toFloat() / (n + 1)
        val outA = (to - f).toFloat() / (n + 1)
        return min(1f, min(inA, outA))
    }
}

/**
 * Fitting a phone video into the clip's frame: crop to fill (the middle kept), after turning it the way it was held
 * (its rotation tag, 0/90/180/270 clockwise).
 */
object VideoFit {
    /** A [srcW] × [srcH] picture turned by [rotation] is this size on screen. */
    fun shown(srcW: Int, srcH: Int, rotation: Int): Pair<Int, Int> = if (quarter(rotation) % 2 == 1) srcH to srcW else srcW to srcH

    /**
     * The part of the turned picture ([shownW] × [shownH]) that fills [outW] × [outH]: its left, top and the shown
     * pixels per output pixel.
     */
    data class Crop(val left: Double, val top: Double, val step: Double)

    fun crop(shownW: Int, shownH: Int, outW: Int, outH: Int): Crop {
        val scale = max(outW.toDouble() / shownW, outH.toDouble() / shownH)
        val step = 1 / scale
        return Crop((shownW - outW * step) / 2, (shownH - outH * step) / 2, step)
    }

    /** The stored picture's pixel for the turned picture's pixel ([x], [y]). */
    fun source(x: Int, y: Int, srcW: Int, srcH: Int, rotation: Int): Pair<Int, Int> = when (quarter(rotation)) {
        1 -> y to (srcH - 1 - x)
        2 -> (srcW - 1 - x) to (srcH - 1 - y)
        3 -> (srcW - 1 - y) to x
        else -> x to y
    }

    fun quarter(rotation: Int): Int = (((rotation / 90) % 4) + 4) % 4
}

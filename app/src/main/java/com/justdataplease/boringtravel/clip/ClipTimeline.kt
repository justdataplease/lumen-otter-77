package com.justdataplease.boringtravel.clip

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What a stretch of the clip is, for the editor's tracks. */
enum class SegmentKind {
    /** The trip's title over the whole route. */
    Opening,
    /** The map following the route under a place name ([TimelineSegment.key] its [LabelRange.key]; null: no name). */
    Map,
    /** A phone video, full frame ([TimelineSegment.id] its [ClipVideo.id]). */
    Video,
    /** A day card, full frame ([TimelineSegment.key] its [ClipDesign.dayCardKey]). */
    DayCard,
    /** The pull-back and the end card. */
    End,
    /** A photo card over the moving map. */
    Photo,
    /** A photo the journey pauses for. */
    PhotoPause,
    /** A close-up: the head waits while the camera goes in close and back out. */
    CloseUp,
    /** A voice line, from its scene's start ([TimelineSegment.key] the scene's key). */
    Voice,
}

/**
 * One stretch of a clip's timeline: frames [from, to) at [ClipTimeline.fps]. [key] is the scene it belongs to (a
 * place name's [LabelRange.key], a photo's [ClipDesign.photoKey], [ClipDesign.OPENING], [ClipDesign.END]); [id] the
 * photo's or video's own id; [title] what the track shows on it. A place name's map cut by a video is two segments
 * with the same key, the second [continued].
 */
data class TimelineSegment(
    val kind: SegmentKind,
    val from: Int,
    val to: Int,
    val key: String? = null,
    val id: String? = null,
    val title: String = "",
    val continued: Boolean = false,
) {
    val frames: Int get() = to - from
}

/**
 * A clip as the editor's tracks draw it (owner: "a video editor with tracks"), worked out from a [ClipPlan] in plain
 * Kotlin: [scenes] is the main track, in order, back to back from frame 0 to [frames] (the opening, the map under each
 * place name with the traveller's videos between and a day card before each day when asked for, the end); [photos] the
 * photo cards (pauses and those over the moving map) and [closeUps] the close-ups, which sit over the map; [voices]
 * the voice lines from their scenes' starts; [beats] the music's beats when the clip cuts on them. Frames run at
 * [fps]; [seconds] converts.
 */
data class ClipTimeline(
    val fps: Int,
    val frames: Int,
    val scenes: List<TimelineSegment>,
    val photos: List<TimelineSegment>,
    val closeUps: List<TimelineSegment>,
    val voices: List<TimelineSegment> = emptyList(),
    /** The music's beats as frames, when the clip cuts on them (the editor draws them on the music track). */
    val beats: List<Int> = emptyList(),
) {
    val seconds: Double get() = frames.toDouble() / fps

    fun seconds(frame: Int): Double = frame.toDouble() / fps

    /** The main track's segment at [frame]. */
    fun sceneAt(frame: Int): TimelineSegment? = scenes.firstOrNull { frame >= it.from && frame < it.to }

    companion object {
        fun of(plan: ClipPlan, design: ClipDesign = ClipDesign()): ClipTimeline {
            val n = plan.frameCount
            val scenes = ArrayList<TimelineSegment>()
            val title = design.scene(ClipDesign.OPENING).text?.trim()?.takeIf { it.isNotEmpty() } ?: plan.title
            if (plan.drawStart > 0) scenes += TimelineSegment(SegmentKind.Opening, 0, plan.drawStart, ClipDesign.OPENING, title = title)
            val end = plan.endCardStart.coerceIn(plan.drawStart, n)
            // Full-frame inserts cut the map; the rest of the journey is the map under its place names.
            val inserts = plan.inserts.filter { it.toFrame > it.fromFrame }.sortedBy { it.fromFrame }
            val names = plan.labels.sortedBy { it.from }
            var f = plan.drawStart
            var i = 0
            fun mapUntil(stop: Int) {
                while (f < stop) {
                    val label = names.firstOrNull { f >= it.from && f < it.to }
                    val next = min(stop, label?.to ?: (names.firstOrNull { it.from > f }?.from ?: stop))
                    val to = max(next, f + 1).coerceAtMost(stop)
                    val prev = scenes.lastOrNull()
                    val continued = label != null && scenes.any { it.kind == SegmentKind.Map && it.key == label.key }
                    if (prev != null && prev.kind == SegmentKind.Map && prev.key == label?.key && prev.to == f) {
                        scenes[scenes.lastIndex] = prev.copy(to = to)
                    } else {
                        val shown = label?.let { design.label(it.key, it.label) ?: it.label }
                        scenes += TimelineSegment(SegmentKind.Map, f, to, label?.key, title = shown?.text.orEmpty(), continued = continued)
                    }
                    f = to
                }
            }
            while (f < end) {
                val insert = inserts.getOrNull(i)
                if (insert != null && insert.fromFrame < end) {
                    mapUntil(insert.fromFrame.coerceAtMost(end))
                    val to = insert.toFrame.coerceAtMost(end)
                    if (to > f) scenes += segment(insert, f, to)
                    f = max(f, to)
                    i++
                } else {
                    mapUntil(end)
                }
            }
            if (n > end) scenes += TimelineSegment(SegmentKind.End, end, n, ClipDesign.END, title = "End")
            val photos = plan.photos.map {
                TimelineSegment(
                    if (it.held) SegmentKind.PhotoPause else SegmentKind.Photo, it.fromFrame, it.toFrame,
                    ClipDesign.photoKey(it.id), it.id, "Photo",
                )
            }
            val closeUps = plan.stills.map { TimelineSegment(SegmentKind.CloseUp, it.fromFrame, it.toFrame, title = "Close-up") }
            // A voice line plays from its scene's start for its own length (cut at the clip's end).
            val voices = design.voices.mapNotNull { v ->
                val from = plan.sceneStart(v.sceneKey) ?: return@mapNotNull null
                val to = (from + ceil(v.durationMs * plan.fps / 1000.0).toInt()).coerceAtMost(n)
                if (to > from) TimelineSegment(SegmentKind.Voice, from, to, v.sceneKey, v.file, "Voice") else null
            }.sortedBy { it.from }
            return ClipTimeline(plan.fps, n, scenes, photos, closeUps, voices, plan.beatFrames.toList())
        }

        private fun segment(insert: Insert, from: Int, to: Int): TimelineSegment = when (insert.kind) {
            InsertKind.Video -> TimelineSegment(SegmentKind.Video, from, to, insert.key, insert.id, "Video")
            InsertKind.DayCard -> TimelineSegment(SegmentKind.DayCard, from, to, insert.key, title = insert.card?.day ?: "Day")
        }
    }
}

package com.justdataplease.boringtravel.ui.clip

import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipScenes
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.clip.ClipTimeline
import com.justdataplease.boringtravel.clip.SceneKind
import com.justdataplease.boringtravel.clip.SegmentKind
import com.justdataplease.boringtravel.clip.TimelineSegment

/*
 * The trip video editor's tracks (owner: "video editor tracks"), kept apart from Compose so they can be tested: what a
 * block on the tracks stands for ([ScenePick]), which tools act on it ([ClipTool]), the blocks themselves
 * ([TrackModel]) and how frames map to distance under the fixed playhead ([TrackScale]).
 */

/** What a block on the tracks stands for, and what the tools' "This scene" acts on when it is chosen. */
internal sealed interface ScenePick {
    /** The key the choice is kept by: the scene's [ClipDesign] key, or "video:<id>" for a phone video. */
    val key: String

    /** The opening over the whole route (the trip's title). */
    data object Opening : ScenePick {
        override val key: String get() = ClipDesign.OPENING
    }

    /** The pull-back and the end card. */
    data object End : ScenePick {
        override val key: String get() = ClipDesign.END
    }

    /** A place name and the map under it ([com.justdataplease.boringtravel.clip.LabelRange.key]). */
    data class Place(override val key: String) : ScenePick

    data class Photo(val id: String) : ScenePick {
        override val key: String get() = ClipDesign.photoKey(id)
    }

    /** A phone video ([com.justdataplease.boringtravel.clip.ClipVideo.id]). */
    data class Video(val id: String) : ScenePick {
        override val key: String get() = VIDEO + id
    }

    /** A day card ([ClipDesign.dayCardKey]). */
    data class DayCard(override val key: String) : ScenePick

    companion object {
        const val VIDEO = "video:"

        /** The pick kept as [key] (see [ScenePick.key]). */
        fun of(key: String?): ScenePick? = when {
            key.isNullOrEmpty() -> null
            key == ClipDesign.OPENING -> Opening
            key == ClipDesign.END -> End
            key.startsWith(ClipDesign.PHOTO) -> ClipDesign.photoId(key)?.let { Photo(it) }
            key.startsWith(VIDEO) -> key.removePrefix(VIDEO).takeIf { it.isNotEmpty() }?.let { Video(it) }
            key.startsWith(ClipDesign.DAY_CARD) -> DayCard(key)
            else -> Place(key)
        }

        /** What a stretch of the timeline stands for (null: a stretch of map without a place name, a close-up). */
        fun of(segment: TimelineSegment): ScenePick? = when (segment.kind) {
            SegmentKind.Opening -> Opening
            SegmentKind.End -> End
            SegmentKind.Map, SegmentKind.Voice -> segment.key?.let { Place(it) }
            SegmentKind.Video -> segment.id?.let { Video(it) }
            SegmentKind.DayCard -> segment.key?.let { DayCard(it) }
            SegmentKind.Photo, SegmentKind.PhotoPause -> segment.id?.let { Photo(it) }
            SegmentKind.CloseUp -> null
        }

        /** The scene the main track shows at [frame] (what "This scene" means when nothing is chosen). */
        fun at(timeline: ClipTimeline, frame: Int): ScenePick? {
            if (timeline.frames <= 0) return null
            return timeline.sceneAt(frame.coerceIn(0, timeline.frames - 1))?.let { of(it) }
        }
    }
}

/**
 * The editor's seven tools (owner, 2026-11-08: "maybe merge some menus that make sense"; a three-editor panel agreed on
 * these). Each opens one panel for the whole clip; those with a "This scene" side ([forScene]) also change just the
 * chosen block. With a block chosen, its own tools come first on the bar, under its name.
 */
internal enum class ClipTool(val label: String) {
    /** Every text: the style, each name with its note and exact spot, the title, the day cards, the end. */
    Text("Text"),
    /** Music (track, where it starts, beats) and voice lines. */
    Audio("Audio"),
    /** Photos and videos, each place's in one list (owner, 2026-11-14: "in clips videos are added as photos"). */
    Media("Media"),
    /** The map, the camera, how close it goes, close-ups. */
    Camera("Camera"),
    /** How long the clip runs, cuts on the beat, photo pauses, video trims. */
    Length("Length"),
    /** Where the route comes from, the line, how each leg travels, the traveller and the rides. */
    Route("Route"),
    /** Named looks, tone, grain, bars. */
    Look("Look"),
    /**
     * The trip's stats line: what it shows, its size, where it goes, its font, background and colour (owner,
     * 2026-11-09: "font size etc are very important"; its own tool, so it is easy to find).
     */
    Stats("Stats");

    /**
     * Whether this tool has a "This scene" side for [pick]: any text but a video's; a place's or a day card's voice; a
     * photo, a video, or a place's media; a place's camera; a photo's pause, a video's trim or a place's time here; how a place was reached.
     * [opensDay]: the place keys a day card can open (a place that opens a day can add or edit that day's card).
     */
    fun forScene(pick: ScenePick?, opensDay: Set<String> = emptySet()): Boolean = when (pick) {
        null -> false
        else -> when (this) {
            Text -> pick !is ScenePick.Video
            Audio -> pick is ScenePick.Place || pick is ScenePick.DayCard
            Media -> pick is ScenePick.Photo || pick is ScenePick.Video || pick is ScenePick.Place
            Camera -> pick is ScenePick.Place
            Length -> pick is ScenePick.Photo || pick is ScenePick.Video || pick is ScenePick.Place
            Route -> pick is ScenePick.Place
            Look, Stats -> false
        }
    }

    /**
     * Whether the panel opens on "This scene": when a scene was chosen on the tracks ([chosen]) and the tool has a
     * side for it; otherwise on "All scenes".
     */
    fun opensOnScene(pick: ScenePick?, chosen: Boolean, opensDay: Set<String> = emptySet()): Boolean = chosen && forScene(pick, opensDay)

    companion object {
        /** The chosen block's own tools, in bar order (none without a choice). */
        fun forPick(pick: ScenePick?, opensDay: Set<String> = emptySet()): List<ClipTool> = entries.filter { it.forScene(pick, opensDay) }
    }
}

/** What a block on the tracks is. */
internal enum class BlockKind { Opening, Map, Video, DayCard, End, Photo, PhotoPause, PhotoOut, Text, Voice }

/**
 * One block on a track: frames [from, to) ([from] = [to] for a photo not in the clip, drawn as a dimmed thumbnail
 * where it would be); [pick] is what choosing it selects. [tone] picks a map block's colour by day; [dim] shows what is
 * not in the clip (a hidden name, a photo left out).
 */
internal data class TrackBlock(
    val kind: BlockKind,
    val from: Int,
    val to: Int,
    val pick: ScenePick?,
    val title: String,
    val sub: String? = null,
    val tone: Int = 0,
    val dim: Boolean = false,
    val photoId: String? = null,
) {
    val frames: Int get() = to - from
}

/**
 * The tracks of a clip [frames] long at [fps]: the scenes back to back (the opening, the map under each place name,
 * the traveller's videos and the day cards between, the end), the close-ups over them, the photos at their pause
 * points (and those left out, dimmed), the texts (the title, each place name, the day cards' lines, the closing line),
 * the voice lines and the music's beats.
 */
internal data class TrackModel(
    val frames: Int,
    val fps: Int,
    val scenes: List<TrackBlock>,
    val closeUps: List<IntRange>,
    val photos: List<TrackBlock>,
    val texts: List<TrackBlock>,
    val voices: List<TrackBlock>,
    val beats: List<Int>,
) {
    companion object {
        /** A place's day for the tracks' colours: its "Day 4", else its own key (each place its own colour). */
        private fun dayOf(plan: ClipPlan, key: String?): String? = key?.let { k -> plan.labels.firstOrNull { it.key == k }?.label?.day ?: k }

        /**
         * The tracks of [plan] as [design] shows it ([timeline] from [ClipTimeline.of]); [beats] are the music's beats as
         * frames (the timeline's when the clip cuts on them, else the track's own grid; empty without music).
         */
        fun of(plan: ClipPlan, design: ClipDesign, timeline: ClipTimeline, beats: List<Int> = timeline.beats): TrackModel {
            val labels = plan.labels.associateBy { it.key }
            val cards = plan.dayStarts.associateBy { it.key }
            val closeUps = timeline.closeUps.map { it.from until it.to }
            var tone = -1
            var day: String? = null
            val scenes = timeline.scenes.map { s ->
                when (s.kind) {
                    SegmentKind.Opening -> TrackBlock(
                        BlockKind.Opening, s.from, s.to, ScenePick.Opening, "Title",
                        sub = if (design.openingVisible) s.title else "No title",
                    )
                    SegmentKind.Map -> {
                        val d = dayOf(plan, s.key)
                        if (tone < 0 || d != day) tone++
                        day = d
                        val auto = s.key?.let { labels[it] }?.label
                        val shown = auto?.let { design.label(s.key, it) } ?: auto
                        val place = shown?.place ?: auto?.place
                        val title = auto?.day ?: place ?: "Map"
                        val close = closeUps.any { it.first < s.to && it.last >= s.from }
                        val sub = listOfNotNull(place.takeIf { auto?.day != null }, "close-up".takeIf { close }).joinToString(" · ").ifEmpty { null }
                        TrackBlock(BlockKind.Map, s.from, s.to, s.key?.let { ScenePick.Place(it) }, title, sub, tone = tone.coerceAtLeast(0))
                    }
                    SegmentKind.Video -> TrackBlock(
                        BlockKind.Video, s.from, s.to, s.id?.let { ScenePick.Video(it) }, ClipText.clock(s.frames, plan.fps), "Video",
                    )
                    SegmentKind.DayCard -> {
                        val card = s.key?.let { cards[it] }
                        val line = s.key?.let { design.scene(it).text } ?: card?.place
                        TrackBlock(BlockKind.DayCard, s.from, s.to, s.key?.let { ScenePick.DayCard(it) }, card?.day ?: s.title, line)
                    }
                    else -> TrackBlock(BlockKind.End, s.from, s.to, ScenePick.End, "End")
                }
            }
            // Every photo on the route: those in the clip at their cards, those left out where they would be.
            val pops = timeline.photos.associateBy { it.id }
            val photos = ClipScenes.list(plan, design).filter { it.kind == SceneKind.Photo && it.photoId != null }.map { sc ->
                val id = sc.photoId!!
                val pop = pops[id]
                when {
                    pop != null -> TrackBlock(
                        if (pop.kind == SegmentKind.PhotoPause) BlockKind.PhotoPause else BlockKind.Photo,
                        pop.from, pop.to, ScenePick.Photo(id), "Photo", photoId = id,
                    )
                    else -> TrackBlock(BlockKind.PhotoOut, sc.frame, sc.frame, ScenePick.Photo(id), "Photo", "Not in the clip", dim = true, photoId = id)
                }
            }.sortedBy { it.from }
            val texts = ArrayList<TrackBlock>()
            timeline.scenes.firstOrNull { it.kind == SegmentKind.Opening }?.let { s ->
                texts += TrackBlock(BlockKind.Text, s.from, s.to, ScenePick.Opening, s.title, dim = !design.openingVisible)
            }
            for (range in plan.labels) {
                val shown = design.label(range.key, range.label)
                texts += TrackBlock(BlockKind.Text, range.from, range.to, ScenePick.Place(range.key), (shown ?: range.label).text, dim = shown == null)
            }
            for (s in timeline.scenes) {
                if (s.kind != SegmentKind.DayCard) continue
                val block = scenes.firstOrNull { it.kind == BlockKind.DayCard && it.from == s.from } ?: continue
                texts += TrackBlock(BlockKind.Text, s.from, s.to, block.pick, listOfNotNull(block.title, block.sub).joinToString(" · "))
            }
            timeline.scenes.lastOrNull { it.kind == SegmentKind.End }?.let { s ->
                val line = design.scene(ClipDesign.END).text?.trim()?.takeIf { it.isNotEmpty() } ?: plan.endCard.stats
                texts += TrackBlock(BlockKind.Text, s.from, s.to, ScenePick.End, line)
            }
            val voices = timeline.voices.map { v ->
                TrackBlock(BlockKind.Voice, v.from, v.to, v.key?.let { ScenePick.Place(it) }, "Voice " + ClipText.clock(v.frames, plan.fps))
            }
            return TrackModel(timeline.frames, timeline.fps, scenes, closeUps, photos, texts.sortedBy { it.from }, voices, beats)
        }

        /**
         * The block of [row] under [frame]: the last drawn wins where blocks overlap, as on screen. A block is at least
         * [minFrames] wide (a photo's thumbnail; a short scene's tap target).
         */
        fun hit(row: List<TrackBlock>, frame: Float, minFrames: Float = 0f): TrackBlock? =
            row.lastOrNull { frame >= it.from && frame < maxOf(it.to.toFloat(), it.from + minFrames) }
    }
}

/**
 * How the tracks map clip frames to distance (dp, or px with the density folded into [dpPerSecond]): [dpPerSecond] at
 * [fps]. The playhead stays still in the middle; the tracks move under it, so the frame it shows is the clip's.
 */
internal data class TrackScale(val fps: Int, val dpPerSecond: Float) {
    val dpPerFrame: Float get() = dpPerSecond / fps.coerceAtLeast(1)

    /** Distance from the clip's start to frame [frame]. */
    fun x(frame: Float): Float = frame * dpPerFrame

    /** How wide frames [from, to) are. */
    fun width(from: Int, to: Int): Float = (to - from).coerceAtLeast(0) * dpPerFrame

    /** Frames in [distance]. */
    fun frames(distance: Float): Float = distance / dpPerFrame

    /** Where frame [frame] is drawn when the playhead at [playheadX] shows frame [current]. */
    fun screenX(frame: Float, current: Float, playheadX: Float): Float = playheadX + (frame - current) * dpPerFrame

    /** The frame drawn at [screenX] when the playhead at [playheadX] shows frame [current]. */
    fun frameAt(screenX: Float, current: Float, playheadX: Float): Float = current + (screenX - playheadX) / dpPerFrame

    /** The playhead's frame after the tracks are dragged [dx] (dragging them left moves on), within a clip [frameCount] long. */
    fun scrub(current: Float, dx: Float, frameCount: Int): Float =
        (current - dx / dpPerFrame).coerceIn(0f, (frameCount - 1).coerceAtLeast(0).toFloat())

    /** Pinched by [factor], within [MIN_DP_PER_S]..[MAX_DP_PER_S]. */
    fun zoomed(factor: Float): TrackScale =
        if (!factor.isFinite() || factor <= 0f) this else copy(dpPerSecond = (dpPerSecond * factor).coerceIn(MIN_DP_PER_S, MAX_DP_PER_S))

    /** Seconds between the ruler's marks: the first of [TICKS] that keeps them [MIN_TICK_GAP] apart. */
    val tickSeconds: Int get() = TICKS.firstOrNull { it * dpPerSecond >= MIN_TICK_GAP } ?: TICKS.last()

    /** The ruler's marks for a clip [frameCount] long, in seconds from the start. */
    fun ticks(frameCount: Int): List<Int> {
        val last = frameCount / fps.coerceAtLeast(1)
        return (0..last step tickSeconds).toList()
    }

    companion object {
        const val MIN_DP_PER_S = 4f
        const val MAX_DP_PER_S = 96f
        const val MIN_TICK_GAP = 48f
        val TICKS = listOf(1, 2, 5, 10, 15, 30, 60)

        /** A place block's width the first scale aims for. */
        const val PLACE_DP = 64f
        const val FIT_MIN = 10f
        const val FIT_MAX = 40f

        /**
         * The first scale for [timeline]: its typical place (the median map block) about [PLACE_DP] wide, within
         * [FIT_MIN]..[FIT_MAX] dp a second (a 42 s clip is then 420–1,680 dp long).
         */
        fun fit(timeline: ClipTimeline): TrackScale {
            val maps = timeline.scenes.filter { it.kind == SegmentKind.Map }.map { it.frames }.sorted()
            val median = maps.getOrNull(maps.size / 2)?.takeIf { it > 0 }
            val dp = median?.let { PLACE_DP / (it.toFloat() / timeline.fps.coerceAtLeast(1)) } ?: 14f
            return TrackScale(timeline.fps, dp.coerceIn(FIT_MIN, FIT_MAX))
        }
    }
}

/** The place [by] steps from [current] in [keys] (the places in clip order), or null past either end. */
internal fun stepPlace(keys: List<String>, current: String, by: Int): String? {
    val i = keys.indexOf(current)
    if (i < 0) return null
    return keys.getOrNull(i + by)
}

/** "0:18 / 0:42" for the playhead at [frame] of a clip [frames] long. */
internal fun clockPair(frame: Int, frames: Int, fps: Int): String = "${ClipText.clock(frame, fps)} / ${ClipText.clock(frames, fps)}"

/** The ruler's mark for [seconds]: "0:05", "1:30". */
internal fun rulerLabel(seconds: Int): String = "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)

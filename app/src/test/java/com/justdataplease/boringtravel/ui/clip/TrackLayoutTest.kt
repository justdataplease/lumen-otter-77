package com.justdataplease.boringtravel.ui.clip

import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipInput
import com.justdataplease.boringtravel.clip.ClipOptions
import com.justdataplease.boringtravel.clip.ClipPhoto
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.ClipSources
import com.justdataplease.boringtravel.clip.ClipStop
import com.justdataplease.boringtravel.clip.ClipTimeline
import com.justdataplease.boringtravel.clip.ClipVideo
import com.justdataplease.boringtravel.clip.GeoPoint
import com.justdataplease.boringtravel.clip.PhotoMode
import com.justdataplease.boringtravel.clip.SceneOverride
import com.justdataplease.boringtravel.clip.SegmentKind
import com.justdataplease.boringtravel.clip.TimelineSegment
import com.justdataplease.boringtravel.clip.VoiceLine
import com.justdataplease.boringtravel.data.SampleArgentina
import com.justdataplease.boringtravel.data.SampleKyoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The trip video editor's tracks (owner: "video editor tracks"): what a block stands for, which tools have a "This
 * scene" side for it, the blocks of a real clip, and frames ↔ distance under the fixed playhead.
 */
class TrackLayoutTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val start = LocalDate.of(2026, 6, 16)
    private fun at(day: Int, h: Int, m: Int) = start.plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun leg(from: Pair<Double, Double>, to: Pair<Double, Double>, startMs: Long, minutes: Int): List<GeoPoint> =
        (0..minutes).map { i ->
            val f = i.toDouble() / minutes
            GeoPoint(startMs + i * 60_000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, 10f)
        }

    /** Three recorded days on Paros and Naxos with a photo each day. */
    private val recorded = ClipInput(
        title = "Cyclades", startDate = start, endDate = start.plusDays(2), zone = zone,
        points = leg(37.0853 to 25.1488, 37.0950 to 25.1700, at(0, 9, 0), 40) +
            leg(37.0950 to 25.1700, 37.1056 to 25.3747, at(1, 8, 0), 60) +
            leg(37.1056 to 25.3747, 37.0587 to 25.4807, at(2, 11, 0), 30),
        stops = listOf(
            ClipStop(at(0, 9, 40), 37.0950, 25.1700, "Parikia, Paros", "GR", "s0"),
            ClipStop(at(1, 9, 0), 37.1056, 25.3747, "Chora, Naxos", "GR", "s1"),
            ClipStop(at(2, 11, 30), 37.0587, 25.4807, "Halki, Naxos", "GR", "s2"),
        ),
        photos = listOf(ClipPhoto("p0", at(0, 9, 20), null, null), ClipPhoto("p1", at(1, 8, 30), null, null), ClipPhoto("p2", at(2, 11, 10), null, null)),
    )

    private val kyoto: ClipInput by lazy {
        val plan = SampleKyoto.plan(LocalDate.of(2027, 10, 3), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), zone).getValue(ClipSource.Planned)
    }

    private val argentina: ClipInput by lazy {
        val plan = SampleArgentina.plan(LocalDate.of(2026, 10, 25), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleArgentina.ZONE).getValue(ClipSource.Planned)
    }

    private fun build(input: ClipInput, design: ClipDesign = ClipDesign()): ClipPlan =
        requireNotNull(ClipPlan.build(input, design.applyTo(ClipOptions()), Locale.ENGLISH))

    private fun model(input: ClipInput, design: ClipDesign = ClipDesign()): Pair<ClipPlan, TrackModel> {
        val p = build(input, design)
        return p to TrackModel.of(p, design, ClipTimeline.of(p, design))
    }

    // --- What a block stands for ------------------------------------------------------------------------------------

    @Test fun aChoiceIsKeptByItsKeyAndReadBack() {
        val picks = listOf(
            ScenePick.Opening, ScenePick.End, ScenePick.Place("label:item:abc"), ScenePick.Place("label:day:2026-06-16"),
            ScenePick.Photo("m1"), ScenePick.Video("v-9"), ScenePick.DayCard(ClipDesign.dayCardKey(start)),
        )
        for (p in picks) assertEquals(p, ScenePick.of(p.key))
        assertEquals("photo:m1", ScenePick.Photo("m1").key)
        assertEquals("video:v-9", ScenePick.Video("v-9").key)
        assertNull(ScenePick.of(null))
        assertNull(ScenePick.of(""))
        assertNull(ScenePick.of("photo:"))
        assertNull(ScenePick.of("video:"))
    }

    @Test fun eachStretchOfTheTimelineChoosesItsScene() {
        fun seg(kind: SegmentKind, key: String? = null, id: String? = null) = TimelineSegment(kind, 0, 10, key, id)
        assertEquals(ScenePick.Opening, ScenePick.of(seg(SegmentKind.Opening, ClipDesign.OPENING)))
        assertEquals(ScenePick.End, ScenePick.of(seg(SegmentKind.End, ClipDesign.END)))
        assertEquals(ScenePick.Place("label:x"), ScenePick.of(seg(SegmentKind.Map, "label:x")))
        assertNull("a stretch of map without a name", ScenePick.of(seg(SegmentKind.Map)))
        assertEquals(ScenePick.Video("v1"), ScenePick.of(seg(SegmentKind.Video, "label:x", "v1")))
        assertEquals(ScenePick.DayCard("daycard:2026-06-16"), ScenePick.of(seg(SegmentKind.DayCard, "daycard:2026-06-16")))
        assertEquals(ScenePick.Photo("m1"), ScenePick.of(seg(SegmentKind.PhotoPause, "photo:m1", "m1")))
        assertEquals(ScenePick.Photo("m1"), ScenePick.of(seg(SegmentKind.Photo, "photo:m1", "m1")))
        assertEquals("a voice line chooses its place", ScenePick.Place("label:x"), ScenePick.of(seg(SegmentKind.Voice, "label:x", "a.m4a")))
        assertNull(ScenePick.of(seg(SegmentKind.CloseUp)))
    }

    @Test fun withNothingChosenThisSceneIsTheOneUnderThePlayhead() {
        val t = ClipTimeline(
            30, 100,
            listOf(
                TimelineSegment(SegmentKind.Opening, 0, 20, ClipDesign.OPENING),
                TimelineSegment(SegmentKind.Map, 20, 50, "label:a"),
                TimelineSegment(SegmentKind.Video, 50, 60, "label:a", "v1"),
                TimelineSegment(SegmentKind.Map, 60, 80, "label:a", continued = true),
                TimelineSegment(SegmentKind.End, 80, 100, ClipDesign.END),
            ),
            emptyList(), emptyList(),
        )
        assertEquals(ScenePick.Opening, ScenePick.at(t, 0))
        assertEquals(ScenePick.Place("label:a"), ScenePick.at(t, 20))
        assertEquals(ScenePick.Video("v1"), ScenePick.at(t, 55))
        assertEquals(ScenePick.Place("label:a"), ScenePick.at(t, 79))
        assertEquals(ScenePick.End, ScenePick.at(t, 99))
        // Past either end: the first or last scene.
        assertEquals(ScenePick.Opening, ScenePick.at(t, -5))
        assertEquals(ScenePick.End, ScenePick.at(t, 500))
        assertNull(ScenePick.at(ClipTimeline(30, 0, emptyList(), emptyList(), emptyList()), 0))
    }

    // --- Tools ------------------------------------------------------------------------------------------------------

    @Test fun theToolsInTheOrderThePanelAgreedThenStats() {
        assertEquals(
            listOf(ClipTool.Text, ClipTool.Audio, ClipTool.Media, ClipTool.Camera, ClipTool.Length, ClipTool.Route, ClipTool.Look, ClipTool.Stats),
            ClipTool.entries.toList(),
        )
    }

    @Test fun eachToolHasAThisSceneSideOnlyWhereItMeansSomething() {
        val place = ScenePick.Place("label:a")
        val opener = ScenePick.Place("label:b")
        val picks = listOf(ScenePick.Opening, ScenePick.End, place, opener, ScenePick.Photo("m"), ScenePick.Video("v"), ScenePick.DayCard("daycard:x"))
        val opensDay = setOf(opener.key)
        fun sides(tool: ClipTool) = picks.filter { tool.forScene(it, opensDay) }
        assertEquals(emptyList<ScenePick>(), sides(ClipTool.Look))
        assertEquals("every text but a video's", picks - ScenePick.Video("v"), sides(ClipTool.Text))
        assertEquals(listOf(place, opener, ScenePick.DayCard("daycard:x")), sides(ClipTool.Audio))
        assertEquals(listOf(place, opener, ScenePick.Photo("m"), ScenePick.Video("v")), sides(ClipTool.Media))
        assertEquals(listOf(place, opener), sides(ClipTool.Camera))
        assertEquals(listOf(place, opener, ScenePick.Photo("m"), ScenePick.Video("v")), sides(ClipTool.Length))
        assertEquals(listOf(place, opener), sides(ClipTool.Route))
        for (tool in ClipTool.entries) assertFalse(tool.forScene(null, opensDay))
    }

    @Test fun aChosenBlockBringsItsOwnToolsFirst() {
        assertEquals(listOf(ClipTool.Text, ClipTool.Media, ClipTool.Length), ClipTool.forPick(ScenePick.Photo("m")))
        assertEquals(listOf(ClipTool.Text), ClipTool.forPick(ScenePick.Opening))
        assertEquals(emptyList<ClipTool>(), ClipTool.forPick(null))
    }

    @Test fun aPanelOpensOnThisSceneOnlyForAChosenScene() {
        val photo = ScenePick.Photo("m")
        assertTrue(ClipTool.Media.opensOnScene(photo, chosen = true))
        assertFalse("under the playhead only: all scenes first", ClipTool.Media.opensOnScene(photo, chosen = false))
        assertFalse("no side for it", ClipTool.Camera.opensOnScene(photo, chosen = true))
        assertFalse(ClipTool.Look.opensOnScene(photo, chosen = true))
    }

    // --- The blocks of a real clip ----------------------------------------------------------------------------------

    @Test fun theScenesRunBackToBackFromTheTitleToTheEnd() {
        for (input in listOf(recorded, kyoto, argentina)) {
            val (p, m) = model(input)
            assertEquals(p.frameCount, m.frames)
            assertEquals(BlockKind.Opening, m.scenes.first().kind)
            assertEquals(BlockKind.End, m.scenes.last().kind)
            assertEquals(0, m.scenes.first().from)
            assertEquals(p.frameCount, m.scenes.last().to)
            m.scenes.zipWithNext().forEach { (a, b) -> assertEquals("${input.title}: ${a.title} then ${b.title}", a.to, b.from) }
            // Every place is a block, chosen by its key; a day keeps its colour, the next day takes the next.
            val maps = m.scenes.filter { it.kind == BlockKind.Map }
            assertEquals(p.labels.map { it.key }.toSet(), maps.mapNotNull { it.pick?.key }.toSet())
            maps.zipWithNext().forEach { (a, b) -> assertTrue(b.tone == a.tone || b.tone == a.tone + 1) }
            for (b in m.scenes + m.texts + m.photos + m.voices) b.pick?.let { assertEquals(it, ScenePick.of(it.key)) }
        }
    }

    @Test fun aPlannedTripsBlocksSayTheDayOverThePlace() {
        val (p, m) = model(argentina)
        val first = m.scenes.first { it.kind == BlockKind.Map }
        val label = p.labels.first { it.key == first.pick?.key }.label
        assertNotNull(label.day)
        assertEquals(label.day, first.title)
        assertEquals(label.place, first.sub)
        // Places of the same day share a colour.
        val byDay = m.scenes.filter { it.kind == BlockKind.Map }.groupBy { b -> p.labels.first { it.key == b.pick?.key }.label.day }
        for ((_, blocks) in byDay) assertEquals(1, blocks.map { it.tone }.distinct().size)
    }

    @Test fun videosDayCardsCloseUpsAndVoicesHaveTheirBlocks() {
        val base = build(recorded)
        val place = base.labels[1].key
        val design = ClipDesign(
            dayCards = true,
            closeUp = com.justdataplease.boringtravel.clip.CloseUp.Close,
            videos = listOf(ClipVideo("v1", "v1.mp4", place, 8_000L, lengthMs = 4_000L)),
            voices = listOf(VoiceLine(place, "line.m4a", 3_000L)),
        )
        val (p, m) = model(recorded, design)
        val video = m.scenes.single { it.kind == BlockKind.Video }
        assertEquals(ScenePick.Video("v1"), video.pick)
        assertEquals(120, video.frames)
        assertEquals("0:04", video.title)
        val cards = m.scenes.filter { it.kind == BlockKind.DayCard }
        assertEquals(p.dayStarts.map { it.key }, cards.map { it.pick?.key })
        assertEquals(listOf("Day 1", "Day 2", "Day 3"), cards.map { it.title })
        // The cards' lines are on the text track too, with the title, each place name and the closing line.
        assertEquals(p.labels.size + cards.size + 2, m.texts.size)
        assertEquals(ScenePick.Opening, m.texts.first().pick)
        assertEquals(ScenePick.End, m.texts.last().pick)
        // Close-ups sit over their places' blocks, which say so.
        assertTrue(m.closeUps.isNotEmpty())
        for (r in m.closeUps) {
            val under = m.scenes.filter { it.kind == BlockKind.Map && it.from <= r.last && it.to > r.first }
            assertTrue(under.isNotEmpty())
            assertTrue(under.any { it.sub?.contains("close-up") == true })
        }
        val voice = m.voices.single()
        assertEquals(ScenePick.Place(place), voice.pick)
        assertEquals(p.sceneStart(place), voice.from)
        assertEquals("Voice 0:03", voice.title)
    }

    @Test fun everyPhotoIsOnItsTrackThoseLeftOutDimmed() {
        // Photo pauses: each shown photo is a pause block (Auto photos would pop these singles over the moving map).
        val design = ClipDesign(photoMode = PhotoMode.Pause2, overrides = mapOf(ClipDesign.photoKey("p1") to SceneOverride(show = false)))
        val (p, m) = model(recorded, design)
        assertEquals(p.photoScenes.map { it.id }.toSet(), m.photos.mapNotNull { it.photoId }.toSet())
        val out = m.photos.single { it.photoId == "p1" }
        assertEquals(BlockKind.PhotoOut, out.kind)
        assertTrue(out.dim)
        assertEquals(out.from, out.to)
        for (b in m.photos.filter { it.photoId != "p1" }) {
            assertFalse(b.dim)
            assertEquals(BlockKind.PhotoPause, b.kind)
            assertTrue(b.to > b.from)
        }
        assertEquals(m.photos.sortedBy { it.from }, m.photos)
    }

    @Test fun aHiddenNameIsDimOnTheTextTrackAndItsOwnWordsShow() {
        val base = build(recorded)
        val (a, b) = base.labels[0].key to base.labels[1].key
        val design = ClipDesign(overrides = mapOf(a to SceneOverride(hidden = true), b to SceneOverride(text = "Our beach")))
        val (_, m) = model(recorded, design)
        assertTrue(m.texts.single { it.pick == ScenePick.Place(a) }.dim)
        val named = m.texts.single { it.pick == ScenePick.Place(b) }
        assertFalse(named.dim)
        assertTrue(named.title, named.title.endsWith("Our beach"))
        assertEquals("Our beach", m.scenes.first { it.pick == ScenePick.Place(b) }.sub)
    }

    @Test fun theBlockUnderAFrameIsTheTopmostAndThumbnailsCountTheirWidth() {
        val row = listOf(
            TrackBlock(BlockKind.Photo, 10, 20, ScenePick.Photo("a"), "Photo"),
            TrackBlock(BlockKind.Photo, 15, 30, ScenePick.Photo("b"), "Photo"),
            TrackBlock(BlockKind.PhotoOut, 50, 50, ScenePick.Photo("c"), "Photo", dim = true),
        )
        assertEquals(ScenePick.Photo("a"), TrackModel.hit(row, 12f)?.pick)
        assertEquals("where they overlap, the one drawn last", ScenePick.Photo("b"), TrackModel.hit(row, 16f)?.pick)
        assertNull(TrackModel.hit(row, 40f))
        assertNull("a photo left out has no length of its own", TrackModel.hit(row, 50f))
        assertEquals(ScenePick.Photo("c"), TrackModel.hit(row, 52f, minFrames = 6f)?.pick)
        assertNull(TrackModel.hit(row, 56f, minFrames = 6f))
    }

    // --- Frames and distance ----------------------------------------------------------------------------------------

    @Test fun framesAndDistanceMapBothWaysAroundThePlayhead() {
        val s = TrackScale(30, 15f)
        assertEquals(0.5f, s.dpPerFrame, 1e-6f)
        assertEquals(150f, s.x(300f), 1e-4f)
        assertEquals(300f, s.frames(150f), 1e-4f)
        assertEquals(15f, s.width(0, 30), 1e-4f)
        assertEquals(0f, s.width(30, 10), 1e-4f)
        // The playhead shows the current frame; a frame further on is to its right.
        assertEquals(160f, s.screenX(400f, 400f, 160f), 1e-4f)
        assertEquals(170f, s.screenX(420f, 400f, 160f), 1e-4f)
        for (x in listOf(0f, 37f, 160f, 300f)) assertEquals(x, s.screenX(s.frameAt(x, 400f, 160f), 400f, 160f), 1e-3f)
    }

    @Test fun draggingTheTracksLeftMovesOnWithinTheClip() {
        val s = TrackScale(30, 15f)
        assertEquals(120f, s.scrub(100f, -10f, 900), 1e-4f)
        assertEquals(80f, s.scrub(100f, 10f, 900), 1e-4f)
        assertEquals(0f, s.scrub(5f, 100f, 900), 1e-4f)
        assertEquals(899f, s.scrub(890f, -100f, 900), 1e-4f)
        assertEquals(0f, s.scrub(0f, -10f, 0), 1e-4f)
    }

    @Test fun pinchingZoomsTheTimeWithinItsLimits() {
        val s = TrackScale(30, 14f)
        assertEquals(28f, s.zoomed(2f).dpPerSecond, 1e-4f)
        assertEquals(TrackScale.MAX_DP_PER_S, s.zoomed(100f).dpPerSecond, 1e-4f)
        assertEquals(TrackScale.MIN_DP_PER_S, s.zoomed(0.01f).dpPerSecond, 1e-4f)
        assertEquals(s, s.zoomed(Float.NaN))
        assertEquals(s, s.zoomed(0f))
    }

    @Test fun theRulerMarksStayReadable() {
        // The mockup: 70 dp between "0:10" and "0:15" at 14 dp a second.
        assertEquals(5, TrackScale(30, 14f).tickSeconds)
        assertEquals(1, TrackScale(30, 96f).tickSeconds)
        assertEquals(15, TrackScale(30, 4f).tickSeconds)
        for (dp in listOf(4f, 7f, 10f, 14f, 25f, 40f, 96f)) {
            val s = TrackScale(30, dp)
            assertTrue(s.tickSeconds * dp >= TrackScale.MIN_TICK_GAP || s.tickSeconds == TrackScale.TICKS.last())
        }
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30, 35, 40), TrackScale(30, 14f).ticks(42 * 30))
        assertEquals(listOf(0), TrackScale(30, 14f).ticks(20))
        assertEquals("0:05", rulerLabel(5))
        assertEquals("1:30", rulerLabel(90))
        assertEquals("0:18 / 0:42", clockPair(18 * 30, 42 * 30, 30))
    }

    @Test fun theFirstScaleFitsATypicalPlace() {
        fun timeline(mapSeconds: List<Double>): ClipTimeline {
            var f = 0
            val segs = mapSeconds.mapIndexed { i, s ->
                val n = (s * 30).toInt()
                TimelineSegment(SegmentKind.Map, f, f + n, "label:$i").also { f += n }
            }
            return ClipTimeline(30, f, segs, emptyList(), emptyList())
        }
        assertEquals(32f, TrackScale.fit(timeline(listOf(2.0, 2.0, 2.0))).dpPerSecond, 1e-3f)
        assertEquals("the median, not a long day", 32f, TrackScale.fit(timeline(listOf(1.0, 2.0, 2.0, 30.0))).dpPerSecond, 1e-3f)
        assertEquals(TrackScale.FIT_MAX, TrackScale.fit(timeline(listOf(0.5, 0.5))).dpPerSecond, 1e-3f)
        assertEquals(TrackScale.FIT_MIN, TrackScale.fit(timeline(listOf(20.0))).dpPerSecond, 1e-3f)
        assertEquals(14f, TrackScale.fit(timeline(emptyList())).dpPerSecond, 1e-3f)
        for (input in listOf(recorded, kyoto, argentina)) {
            val p = build(input)
            val dp = TrackScale.fit(ClipTimeline.of(p)).dpPerSecond
            assertTrue("${input.title} $dp", dp in TrackScale.FIT_MIN..TrackScale.FIT_MAX)
        }
    }

    @Test fun aVideoMovesOnePlaceAtATime() {
        val keys = listOf("a", "b", "c")
        assertEquals("a", stepPlace(keys, "b", -1))
        assertEquals("c", stepPlace(keys, "b", 1))
        assertNull(stepPlace(keys, "a", -1))
        assertNull(stepPlace(keys, "c", 1))
        assertNull("a place no longer in the clip", stepPlace(keys, "z", 1))
    }
}

package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.data.SampleArgentina
import com.justdataplease.boringtravel.data.SampleKyoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Cuts on the beat (owner: "Cuts on the beat"): the bundled tracks' measured grids, the beats a clip hears (looping
 * like the music), and the scene changes moved onto them without changing the clip's length by more than a beat or
 * the camera's zoom speed limit.
 */
class ClipBeatsTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val start = LocalDate.of(2026, 6, 16)
    private fun at(day: Int, h: Int, m: Int) = start.plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun leg(from: Pair<Double, Double>, to: Pair<Double, Double>, startMs: Long, minutes: Int): List<GeoPoint> =
        (0..minutes).map { i ->
            val f = i.toDouble() / minutes
            GeoPoint(startMs + i * 60_000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, 10f)
        }

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
        val input = ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), zone).getValue(ClipSource.Planned)
        input.copy(photos = input.stops.filterIndexed { i, _ -> i % 4 == 1 }.mapIndexed { i, s -> ClipPhoto("k$i", null, s.lat, s.lng) })
    }

    private val argentina: ClipInput by lazy {
        val plan = SampleArgentina.plan(LocalDate.of(2026, 10, 25), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleArgentina.ZONE).getValue(ClipSource.Planned)
    }

    /** 120 BPM, first beat at 0.25 s: a beat every 15 frames, on frames 7.5, 22.5, … (rounded 8, 23, …). */
    private val grid = ClipBeats(BeatGrid(120.0, 0.25), 120.0)

    private fun options(beats: ClipBeats?, design: ClipDesign, videos: (List<LabelRange>) -> List<VideoSlot> = { emptyList() }, input: ClipInput): ClipOptions {
        val plain = requireNotNull(ClipPlan.build(input, design.applyTo(ClipOptions()), Locale.ENGLISH))
        return design.applyTo(ClipOptions()).copy(beats = beats, videos = videos(plain.labels))
    }

    private fun build(input: ClipInput, beats: ClipBeats?, design: ClipDesign = ClipDesign(), videos: (List<LabelRange>) -> List<VideoSlot> = { emptyList() }) =
        requireNotNull(ClipPlan.build(input, options(beats, design, videos, input), Locale.ENGLISH))

    // --- The grids ----------------------------------------------------------------------------------------------------

    @Test fun everyTrackHasItsMeasuredBeat() {
        for (t in MusicCatalog.tracks) {
            assertTrue(t.id, t.beats.bpm in 75.0..140.0)
            assertTrue(t.id, t.beats.firstBeatS >= 0 && t.beats.firstBeatS < t.beats.periodS)
        }
        // The tresillo tracks: their beat, not the three-sixteenths pulse.
        assertEquals(105.0, MusicCatalog.byId("afro_groove")!!.beats.bpm, 0.01)
        assertEquals(110.0, MusicCatalog.byId("lost_in_brazil")!!.beats.bpm, 0.01)
        assertEquals(120.0, MusicCatalog.byId("caldera")!!.beats.bpm, 0.01)
        val beats = MusicCatalog.clipBeats("caldera", onBeat = true)
        assertNotNull(beats)
        assertEquals(120.0, beats!!.trackS, 1e-9)
        assertNull(MusicCatalog.clipBeats("caldera", onBeat = false))
        assertNull(MusicCatalog.clipBeats(null, onBeat = true))
        assertNull(MusicCatalog.clipBeats("gone", onBeat = true))
    }

    @Test fun theClipHearsTheBeatsFromItsStartAndAcrossTheLoop() {
        val t = grid.times(5.0)
        assertEquals(0.25, t[0], 1e-9)
        for (i in 1 until t.size) assertEquals(0.5, t[i] - t[i - 1], 1e-9)
        assertTrue(t.last() <= 5.0)
        // A 10.2 s track loops 1.5 s early: the second pass's beats follow, a little off the first's grid.
        val loop = ClipBeats(BeatGrid(120.0, 0.25), 10.2).times(30.0)
        for (i in 1 until loop.size) {
            val gap = loop[i] - loop[i - 1]
            assertTrue("gap $gap at ${loop[i]}", gap > 0.3 * 0.5 - 1e-9 && gap <= 0.5 + 1e-9)
        }
        // Second pass: starts 8.7 s in, its beats from the middle of the crossfade.
        assertTrue(loop.any { abs(it - (8.7 + 0.25 + 0.5 * 2)) < 1e-9 })
        val frames = grid.frames(30, 300)
        assertEquals(7.5, frames[0], 1e-9)
        assertEquals(15.0, frames[1] - frames[0], 1e-9)
    }

    // --- The fit ------------------------------------------------------------------------------------------------------

    @Test fun stopsStartOnBeatsAndLastWholeBeats() {
        val beats = grid.frames(30, 3_000)
        fun onBeat(f: Int) = beats.any { it.roundToInt() == f }
        val blocks = java.util.TreeMap<Int, MutableList<Hold>>()
        blocks[-1] = mutableListOf(Hold(HoldKind.DayCard, 45, key = "daycard:x"))
        blocks[99] = mutableListOf(Hold(HoldKind.Photo, 60, "p"))
        blocks[299] = mutableListOf(Hold(HoldKind.StillIn, 20), Hold(HoldKind.Photo, 60, "q"), Hold(HoldKind.StillOut, 20))
        blocks[449] = mutableListOf()
        blocks[549] = mutableListOf(Hold(HoldKind.Video, 100, "v", "label:x"))
        val stillAt = mapOf(299 to (40 to 0.5))
        val before = layFrames(46, 700, blocks, stillAt, 90)
        val rounds = mapOf(HoldKind.DayCard to BeatFit.Round.Nearest, HoldKind.Photo to BeatFit.Round.Nearest, HoldKind.StillIn to BeatFit.Round.Up,
            HoldKind.StillOut to BeatFit.Round.Up, HoldKind.Video to BeatFit.Round.Down)
        val cuts = blocks.map { (k, items) -> BeatFit.Cut(k, items.map { BeatFit.Part(it.frames, rounds.getValue(it.kind)) }) }
        val fit = requireNotNull(BeatFit.fit(46, 700, cuts, beats, minIntro = 30))
        assertTrue(onBeat(fit.drawStart))
        assertEquals(listOf(45), fit.parts[0].toList())
        assertEquals(listOf(60), fit.parts[1].toList())
        // The close-up's halves only grow (whole beats), the photo in its middle stays 4 beats.
        assertEquals(listOf(30, 60, 30), fit.parts[2].toList())
        assertEquals(0, fit.parts[3].size)
        // The video only shrinks: 6 beats of its 100 frames.
        assertEquals(listOf(90), fit.parts[4].toList())
        blocks.values.forEachIndexed { b, items -> items.forEachIndexed { i, item -> item.frames = fit.parts[b][i] } }
        val after = layFrames(fit.drawStart, 700, blocks, stillAt, 90, fit.runs)
        // Every stop starts and ends on a beat, the day change too.
        for (i in after.inserts) assertTrue("${i.id} ${i.fromFrame}..${i.toFrame}", onBeat(i.fromFrame) && onBeat(i.toFrame))
        for (p in after.heldPops) assertTrue("${p.id} ${p.fromFrame}..${p.toFrame}", onBeat(p.fromFrame) && onBeat(p.toFrame))
        for (s in after.stills) assertTrue(onBeat(s.fromFrame) && onBeat(s.toFrame))
        assertTrue(onBeat(after.outOf[450]))
        // The journey ends where it did; every moving frame is still shown, in order.
        assertEquals(before.drawEnd, after.drawEnd)
        assertEquals(before.src.size, after.src.size)
        var last = -1.0
        for (f in fit.drawStart until after.drawEnd) {
            assertTrue(after.src[f] >= last)
            last = after.src[f]
        }
        for (k in 0 until 700) assertTrue(after.src[after.outOf[k]] >= k - 1e-9 && after.outOf[k] <= after.outOf[k + 1])
    }

    @Test fun aStopRightAfterAnotherWaitsForTheNextBeat() {
        val beats = grid.frames(30, 3_000)
        val cuts = listOf(
            BeatFit.Cut(99, listOf(BeatFit.Part(60, BeatFit.Round.Nearest))),
            BeatFit.Cut(102, listOf(BeatFit.Part(30, BeatFit.Round.Nearest))),
        )
        val fit = requireNotNull(BeatFit.fit(45, 400, cuts, beats, 30))
        // Three moving frames between them take one beat (slower for a moment, no jump).
        assertEquals(15, fit.runs[1])
        assertEquals(30, fit.parts[1][0])
        assertNull(BeatFit.fit(45, 400, cuts, DoubleArray(1), 30))
        assertNull(BeatFit.fit(45, 0, cuts, beats, 30))
    }

    // --- The clip -----------------------------------------------------------------------------------------------------

    private fun design() = ClipDesign(dayCards = true, closeUp = CloseUp.Close)

    @Test fun sceneChangesLandOnTheBeatAndTheLengthStaysWithinABeat() {
        for (input in listOf(recorded, kyoto, argentina)) {
            val videos = { labels: List<LabelRange> -> listOf(VideoSlot("v", labels[labels.size / 2].key, 3.3)) }
            val free = build(input, null, design(), videos)
            val p = build(input, grid, design(), videos)
            val beats = grid.frames(p.fps, p.frameCount + 600)
            fun onBeat(f: Int) = beats.any { it.roundToInt() == f }
            // A chosen length with music ends on the bar line nearest it (half a bar, two beats, from it at most); when
            // its stops overflow the length (Argentina's 31 day cards) on the next bar line after them.
            if (free.seconds <= ClipLength.Auto.target(free.tripDays, free.options.shape) + 0.5) {
                assertTrue("${input.title} length ${free.frameCount} → ${p.frameCount}", abs(p.frameCount - free.frameCount) <= 2 * 15 + 1)
            } else assertTrue("${input.title} length ${free.frameCount} → ${p.frameCount}", p.frameCount >= free.frameCount - 2 * 15)
            val bars = beats.filterIndexed { i, _ -> i % ClipTiming.BAR_BEATS == 0 }
            assertTrue("${input.title} ends at ${p.frameCount}, bars $bars", bars.any { abs(it - p.frameCount) <= 1.0 })
            // Pace (no chosen length) keeps its length within a beat.
            val freePace = requireNotNull(ClipPlan.build(input, options(null, design(), videos, input).copy(length = null), Locale.ENGLISH))
            val pacePlan = requireNotNull(ClipPlan.build(input, options(grid, design(), videos, input).copy(length = null), Locale.ENGLISH))
            assertTrue("${input.title} pace ${freePace.frameCount} → ${pacePlan.frameCount}", abs(pacePlan.frameCount - freePace.frameCount) <= 15)
            assertTrue(onBeat(p.drawStart))
            assertTrue(onBeat(p.endCardStart))
            val edges = p.inserts.flatMap { listOf(it.fromFrame, it.toFrame) } + p.photos.filter { it.held }.flatMap { listOf(it.fromFrame, it.toFrame) } +
                p.stills.flatMap { listOf(it.fromFrame, it.toFrame) }
            val on = edges.count { onBeat(it) }
            assertEquals("${input.title}: stops on the beat", edges.size, on)
            assertTrue(p.inserts.any { it.kind == InsertKind.DayCard })
            // Close-ups never get shorter (their zoom needs the time); the video never plays longer than chosen.
            assertEquals(free.stills.size, p.stills.size)
            for (i in p.stills.indices) assertTrue(p.stills[i].toFrame - p.stills[i].fromFrame >= free.stills[i].toFrame - free.stills[i].fromFrame)
            assertTrue(p.inserts.first { it.kind == InsertKind.Video }.frames <= 99)
            assertEquals(free.inserts.size, p.inserts.size)
            assertEquals(free.labels.map { it.key }, p.labels.map { it.key })
        }
    }

    @Test fun aRecordedDayChangeIsACutOnTheBeatToo() {
        val p = build(recorded, grid, ClipDesign())
        val beats = grid.frames(p.fps, p.frameCount + 600)
        for (label in p.labels.drop(1)) assertTrue("${label.key} at ${label.from}", beats.any { it.roundToInt() == label.from })
    }

    @Test fun theCameraKeepsItsSpeedLimitOnTheBeat() {
        val limit = ClipPlan.MAX_ZOOM_RATE + 1e-9
        for (input in listOf(recorded, kyoto, argentina)) for (closeUp in listOf(CloseUp.Close, CloseUp.VeryClose)) {
            val p = build(input, ClipBeats(BeatGrid(78.98, 0.02), 120.0), ClipDesign(dayCards = true, closeUp = closeUp))
            for (f in 1 until p.frameCount) {
                // The opening's dive and the closing pull-back have their own easing; the journey keeps the limit.
                if (f > p.openingEnd && f <= p.drawEnd) {
                    assertTrue("${input.title} $closeUp frame $f", abs(ln(p.camera(f).spanX / p.camera(f - 1).spanX)) <= limit)
                }
                val st = p.frame(f)
                val r = st.camera.rect(p.options.shape.aspect)
                assertTrue((st.headX - r.x0) / r.w in 0.05..0.95 && (st.headY - r.y0) / r.h in 0.05..0.95)
            }
            // The head only moves forward.
            for (f in 1 until p.frameCount) assertTrue(p.headDistance(f) >= p.headDistance(f - 1) - 1e-6)
        }
    }

    @Test fun withoutMusicOrWithTheBeatOffTheClipIsAsBefore() {
        for (input in listOf(recorded, kyoto)) {
            val a = build(input, null, design())
            val b = requireNotNull(ClipPlan.build(input, design().applyTo(ClipOptions()), Locale.ENGLISH))
            assertTrue(a.sameTimeline(b))
            assertEquals(0, a.beatFrames.size)
            // Music alone (no beats) changes nothing on screen.
            val c = requireNotNull(ClipPlan.build(input, design().applyTo(ClipOptions(music = "caldera")), Locale.ENGLISH))
            assertTrue(a.sameTimeline(c))
        }
    }

    @Test fun theTimelineShowsTheBeats() {
        val p = build(kyoto, grid, design())
        val t = ClipTimeline.of(p)
        assertEquals(p.beatFrames.toList(), t.beats)
        assertTrue(t.beats.isNotEmpty())
        assertEquals(8, t.beats.first())
        for (i in 1 until t.beats.size) assertTrue(t.beats[i] - t.beats[i - 1] in 14..16)
        assertTrue(t.beats.last() < t.frames)
        assertTrue(ClipTimeline.of(build(kyoto, null, design())).beats.isEmpty())
    }
}

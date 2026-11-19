package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.SampleArgentina
import com.justdataplease.boringtravel.data.SampleLisbon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Clip lengths (owner, 2026-11-08: "make sure speeds are well defined, x1 seems huge", "keep the defaults strava
 * like") and Auto photos (2026-11-09: "pause 3s the video the image appears and disappears when there is movement"):
 * a chosen length holds the whole clip, the journey's pace follows; the journey stops 3 s for one photo per place.
 */
class ClipLengthTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val start = LocalDate.of(2026, 6, 16)
    private fun at(day: Int, h: Int, m: Int) = start.plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun leg(from: Pair<Double, Double>, to: Pair<Double, Double>, startMs: Long, minutes: Int): List<GeoPoint> =
        (0..minutes).map { i ->
            val f = i.toDouble() / minutes
            GeoPoint(startMs + i * 60_000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, 10f)
        }

    private val parikia = 37.0853 to 25.1488
    private val parikiaNorth = 37.0950 to 25.1700
    private val naxos = 37.1056 to 25.3747
    private val halki = 37.0587 to 25.4807

    /**
     * A short trip: three days on Paros and Naxos, with [photos]: several at each day's end (taken there), one on the
     * way (a time only), one taken far off the route (on Santorini) and one with only a time, the day before the trip.
     */
    private fun cyclades(photos: Boolean = false) = ClipInput(
        title = "Cyclades", startDate = start, endDate = start.plusDays(2), zone = zone,
        points = leg(parikia, parikiaNorth, at(0, 9, 0), 40) + leg(parikiaNorth, naxos, at(1, 8, 0), 60) + leg(naxos, halki, at(2, 11, 0), 30),
        stops = listOf(
            ClipStop(at(0, 9, 40), parikiaNorth.first, parikiaNorth.second, "Parikia, Paros", "GR", "s0"),
            ClipStop(at(1, 9, 0), naxos.first, naxos.second, "Chora, Naxos", "GR", "s1"),
            ClipStop(at(2, 11, 30), halki.first, halki.second, "Halki, Naxos", "GR", "s2"),
        ),
        photos = if (!photos) emptyList() else
            (0 until 4).map { ClipPhoto("a$it", at(0, 9, 41 + it), parikiaNorth.first, parikiaNorth.second) } +
                (0 until 3).map { ClipPhoto("b$it", at(1, 9, 1 + it), naxos.first, naxos.second) } +
                (0 until 4).map { ClipPhoto("c$it", at(2, 11, 31 + it), halki.first, halki.second) } +
                listOf(
                    ClipPhoto("way", at(1, 8, 30), null, null),
                    ClipPhoto("santorini", at(1, 15, 0), 36.4167, 25.4333),
                    ClipPhoto("before", at(-1, 18, 0), null, null),
                ),
    )

    /** A long one: Lisbon and Porto, nine planned days (Auto still 25 s). */
    private val lisbon: ClipInput by lazy {
        val plan = SampleLisbon.plan(LocalDate.of(2026, 6, 1), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleLisbon.ZONE).getValue(ClipSource.Planned)
    }

    private val argentina: ClipInput by lazy {
        val plan = SampleArgentina.plan(LocalDate.of(2026, 10, 25), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleArgentina.ZONE).getValue(ClipSource.Planned)
    }

    private fun build(input: ClipInput, options: ClipOptions = ClipOptions()) = requireNotNull(ClipPlan.build(input, options, Locale.ENGLISH))

    /** 120 BPM: a beat every 15 frames, a bar every 60. */
    private val grid = ClipBeats(BeatGrid(120.0, 0.25), 120.0)

    // --- Lengths --------------------------------------------------------------------------------------------------------

    @Test fun autoGrowsWithTheTripFromTwentyFiveSeconds() {
        for (input in listOf(cyclades(), lisbon)) {
            val p = build(input)
            val want = ClipLength.Auto.target(p.tripDays, ClipShape.Story)
            assertEquals(input.title, want, p.seconds, 1.0 / p.fps)
            // With music: on the bar line nearest the length, so within half a bar (two beats).
            val beat = 0.5
            val q = build(input, ClipOptions(beats = grid))
            assertTrue("${input.title} ${q.seconds}", abs(q.seconds - want) <= 2 * beat + 1.0 / q.fps)
            val bars = grid.frames(q.fps, q.frameCount + 120).filterIndexed { i, _ -> i % ClipTiming.BAR_BEATS == 0 }
            assertTrue(bars.any { abs(it - q.frameCount) <= 1.0 })
        }
        // Pace (the trip's own travel time at ×1) is what made these 1:05 and longer before.
        assertTrue(build(lisbon, ClipOptions(length = null)).seconds > 40)
    }

    @Test fun autoGivesAMonthAMinuteAndAWideClipAtLeastFortyFiveSeconds() {
        // Owner, 2026-11-08, on Argentina at 45 s: "it goes too fast".
        assertEquals(60.0, build(argentina).seconds, 1.0 / 30)
        assertEquals(45.0, build(cyclades(), ClipOptions(shape = ClipShape.Wide)).seconds, 1.0 / 30)
        assertEquals(25.0, ClipLength.Auto.target(2, ClipShape.Story), 0.0)
        assertEquals(31.7, ClipLength.Auto.target(9, ClipShape.Story), 1e-9)
        assertEquals(60.0, ClipLength.Auto.target(31, ClipShape.Story), 0.0)
        assertEquals(60.0, ClipLength.Auto.target(90, ClipShape.Wide), 0.0)
        assertEquals(15.0, ClipLength.S15.target(30, ClipShape.Wide), 0.0)
        for (l in ClipLength.entries) assertTrue(l.target(40, ClipShape.Wide) <= ClipTiming.FREE_MAX_SECONDS)
    }

    @Test fun everyLengthHoldsTheWholeClip() {
        for (length in ClipLength.entries) for (input in listOf(cyclades(photos = true), lisbon)) {
            // Photo pauses (at most HELD_SHARE of the length) and a phone video fit inside: the journey makes room.
            val base = build(input, ClipOptions(length = length))
            val target = length.target(base.tripDays, ClipShape.Story)
            val video = listOf(VideoSlot("v", base.labels[base.labels.size / 2].key, 2.0))
            val held = input.photos.ifEmpty { input.stops.take(4).mapIndexed { i, s -> ClipPhoto("s$i", null, s.lat, s.lng) } }
            val full = build(input.copy(photos = held), ClipDesign(photoMode = PhotoMode.Pause2).applyTo(ClipOptions(length = length)).copy(videos = video))
            for (p in listOf(base, full)) assertEquals("${input.title} $length", target, p.seconds, 1.0 / p.fps)
            assertEquals(1, full.inserts.size)
            assertTrue(full.photos.any { it.held })
            assertTrue(full.photos.filter { it.held }.sumOf { it.toFrame - it.fromFrame } <= target * ClipPlan.HELD_SHARE * full.fps + 1)
            // The journey goes faster to make room, as Pace would say it.
            assertTrue(full.pace > base.pace)
            assertFalse(ClipTiming.needsPro(full.seconds))
        }
    }

    @Test fun shorterLengthsMeanAFasterJourney() {
        val paces = ClipLength.entries.filter { it != ClipLength.Auto }.map { build(lisbon, ClipOptions(length = it)).pace }
        assertTrue("$paces", paces.zipWithNext().all { (a, b) -> a > b })
        // 15 s goes faster than the trip's own ×1 pace.
        assertTrue(paces.first() > 1.0)
    }

    // --- Auto photos ----------------------------------------------------------------------------------------------------

    @Test fun autoPhotosStopTheJourneyThreeSecondsEach() {
        // Owner, 2026-11-09: "pause 3s the video the image appears and disappears when there is movement".
        val input = cyclades(photos = true)
        val auto = build(input, ClipDesign(photoMode = PhotoMode.Auto).applyTo(ClipOptions()))
        val pause = build(input, ClipDesign(photoMode = PhotoMode.Pause2).applyTo(ClipOptions()))
        // Each photo shows while the journey stands still for 3 s and goes when it moves on: none over the moving map.
        assertTrue(auto.photos.size >= 2)
        for (pop in auto.photos) {
            assertTrue(pop.id, pop.held)
            assertEquals((3.0 * auto.fps).roundToInt(), pop.toFrame - pop.fromFrame)
            assertEquals(auto.headDistance(pop.fromFrame), auto.headDistance(pop.toFrame - 1), 1e-9)
        }
        // One photo per place, spread over the route, within the share of the length that pauses may take.
        val places = auto.photos.map { auto.headDistance(it.fromFrame) }
        assertEquals(places.size, places.distinct().size)
        assertTrue(auto.photos.sumOf { it.toFrame - it.fromFrame } <= auto.seconds * ClipPlan.HELD_SHARE * auto.fps + 1)
        // The photos far off the route or from the day before join the nearest stop instead of vanishing.
        val ids = auto.photoScenes.map { it.id }.toSet()
        assertTrue("santorini" in ids && "before" in ids)
        assertFalse("santorini" in pause.photoScenes.map { it.id })
        assertEquals(input.photos.size, auto.photoCandidates)
        assertTrue(auto.photos.all { it.toFrame <= auto.endCardStart })
        // The length holds.
        assertEquals(25.0, auto.seconds, 1.0 / auto.fps)
        // A photo asked for joins with the same pause; one whose own pause is 0 pops in over the moving map.
        val shown = auto.photos.map { it.id }.toSet()
        val extra = input.photos.map { it.id }.first { it !in shown && it != "santorini" && it != "before" && it != "way" }
        val asked = build(input, ClipDesign(photoMode = PhotoMode.Auto).applyTo(ClipOptions()).copy(photoEdits = PhotoEdits(included = setOf(extra))))
        assertEquals((3.0 * asked.fps).roundToInt(), asked.photos.single { it.id == extra }.let { it.toFrame - it.fromFrame })
        assertTrue(asked.photos.all { it.held })
        val first = auto.photos.first().id
        val over = build(input, ClipDesign(photoMode = PhotoMode.Auto).applyTo(ClipOptions()).copy(photoEdits = PhotoEdits(holds = mapOf(first to 0.0))))
        assertFalse(over.photos.single { it.id == first }.held)
    }

    @Test fun withMusicPhotosAndPlaceNamesComeInOnTheBeat() {
        val input = cyclades(photos = true)
        val p = build(input, ClipDesign(photoMode = PhotoMode.Auto).applyTo(ClipOptions(beats = grid)))
        val beats = grid.frames(p.fps, p.frameCount + 60).map { it.roundToInt() }.toSet()
        assertTrue(p.photos.isNotEmpty())
        for (pop in p.photos) assertTrue("photo ${pop.id} at ${pop.fromFrame}", pop.fromFrame in beats)
        // Each photo's pause lasts whole beats (at least one).
        for (pop in p.photos.filter { it.held }) assertTrue(pop.toFrame in beats && pop.toFrame - pop.fromFrame >= 15)
        for (label in p.labels.drop(1)) assertTrue("${label.key} at ${label.from}", label.from in beats)
        // A planned trip's place names too (they change within days, not on cuts).
        val q = build(lisbon, ClipOptions(beats = grid))
        val qBeats = grid.frames(q.fps, q.frameCount + 60).map { it.roundToInt() }
        val onBeat = q.labels.drop(1).count { l -> qBeats.any { it == l.from } }
        assertTrue("$onBeat of ${q.labels.size - 1}", onBeat >= (q.labels.size - 1) * 0.9)
        assertTrue(q.labels.zipWithNext().all { (a, b) -> a.from < b.from && a.to <= b.from })
    }
}

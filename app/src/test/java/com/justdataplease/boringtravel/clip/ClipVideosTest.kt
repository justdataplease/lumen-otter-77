package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.clip.music.MusicMix
import com.justdataplease.boringtravel.data.SampleKyoto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Phone videos in the clip (owner: "Your phone videos"): where they go, what they add to the length, the still map
 * under them, the edits file, the crop to fill, the preview's sampling, their sound under the music and the timeline
 * the tracks editor draws.
 */
class ClipVideosTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val start = LocalDate.of(2026, 6, 16)
    private fun at(day: Int, h: Int, m: Int) = start.plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun leg(from: Pair<Double, Double>, to: Pair<Double, Double>, startMs: Long, minutes: Int): List<GeoPoint> =
        (0..minutes).map { i ->
            val f = i.toDouble() / minutes
            GeoPoint(startMs + i * 60_000L, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, 10f)
        }

    /** Three recorded days on Paros and Naxos, a stop at each day's end and photos along the way. */
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

    /** Built once: the sample gives its plan items new ids each time, and the place name keys come from them. */
    private val kyoto: ClipInput by lazy {
        val plan = SampleKyoto.plan(LocalDate.of(2027, 10, 3), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), zone).getValue(ClipSource.Planned)
    }

    /** At the trip's own pace (no chosen length), where each video adds its time; see [videosFitInsideAChosenLength]. */
    private fun build(input: ClipInput, videos: List<VideoSlot> = emptyList(), photoHold: Double = 2.0, closeUp: CloseUp = CloseUp.Off) =
        requireNotNull(ClipPlan.build(input, ClipOptions(length = null, photoHoldSeconds = photoHold, videos = videos, closeUp = closeUp, stopTitles = false), Locale.ENGLISH))

    @Test fun videosFitInsideAChosenLength() {
        for (input in listOf(kyoto, recorded)) {
            val base = requireNotNull(ClipPlan.build(input, ClipOptions(), Locale.ENGLISH))
            val label = base.labels[base.labels.size / 2]
            val p = requireNotNull(ClipPlan.build(input, ClipOptions(videos = listOf(VideoSlot("v1", label.key, 4.0))), Locale.ENGLISH))
            // The video plays in full and the journey makes room: the clip keeps its length.
            assertEquals(120, p.inserts.single().frames)
            assertEquals(base.frameCount, p.frameCount)
            assertTrue(p.drawEnd - p.drawStart - 120 <= base.drawEnd - base.drawStart - 110)
        }
    }

    // --- The plan ---------------------------------------------------------------------------------------------------

    @Test fun aVideoPlaysAfterItsPlaceWhileTheJourneyWaits() {
        for (input in listOf(kyoto, recorded)) {
            val base = build(input)
            val label = base.labels[base.labels.size / 2]
            val p = build(input, listOf(VideoSlot("v1", label.key, 4.0)))
            // It adds exactly its length; nothing else moves on the journey.
            assertEquals(base.frameCount + 120, p.frameCount)
            assertEquals(4.0, p.insertSeconds, 1e-9)
            val v = p.inserts.single()
            assertEquals(InsertKind.Video, v.kind)
            assertEquals("v1", v.id)
            assertEquals(label.key, v.key)
            assertEquals(120, v.frames)
            // It plays while its place name shows (the name stays on top of it).
            val named = p.labels.first { it.key == label.key }
            assertTrue("${input.title} ${v.fromFrame}..${v.toFrame} in ${named.from}..${named.to}", v.fromFrame > named.from && v.toFrame <= named.to)
            // The head waits: the frames before are the clip without it, the frames after are shifted by its length.
            for (f in v.fromFrame until v.toFrame) assertEquals(p.frame(v.fromFrame - 1).headX, p.frame(f).headX, 0.0)
            for (f in 0 until v.fromFrame) assertEquals(base.frame(f).headX, p.frame(f).headX, 0.0)
            for (f in v.fromFrame until base.frameCount) assertEquals(base.frame(f).headY, p.frame(f + 120).headY, 0.0)
            assertSame(v, p.insertAt(v.fromFrame))
            assertSame(v, p.insertAt(v.toFrame - 1))
            assertNull(p.insertAt(v.fromFrame - 1))
            assertNull(p.insertAt(v.toFrame))
            assertTrue(base.inserts.isEmpty())
        }
    }

    @Test fun aRecordedDaysVideoPlaysWhereTheDayEnds() {
        val base = build(recorded)
        val day2 = base.labels.first { it.key == ClipDesign.labelKey("day:${start.plusDays(1)}") }
        val p = build(recorded, listOf(VideoSlot("v", day2.key, 3.0)))
        val v = p.inserts.single()
        val head = p.frame(v.fromFrame)
        // Chora on Naxos, the end of day 2's leg.
        val x = Mercator.x(25.3747)
        val y = Mercator.y(37.1056)
        assertTrue(hypot(head.headX - x, head.headY - y) < p.overview.spanX * 0.02)
    }

    @Test fun aPlannedPlacesVideoPlaysWhereTheHeadReachesIt() {
        val base = build(kyoto)
        val waypoints = requireNotNull(kyoto.planned).waypoints
        for (label in listOf(base.labels[1], base.labels[base.labels.size / 2], base.labels.last())) {
            val p = build(kyoto, listOf(VideoSlot("v", label.key, 2.0)))
            val head = p.frame(p.inserts.single().fromFrame)
            fun name(t: String) = t.trim().let { if (it.length > 28) it.take(27).trimEnd() + "…" else it }
            val place = waypoints.filter { w -> name(w.place ?: w.title) == label.label.place }
            assertTrue(label.label.text, place.isNotEmpty())
            val nearest = place.minOf { w -> hypot(head.headX - Mercator.x(w.lng), head.headY - Mercator.y(w.lat)) }
            assertTrue("${label.label.text} ${nearest / p.overview.spanX}", nearest < p.overview.spanX * 0.03)
        }
    }

    @Test fun severalVideosPlayInTheOrderAddedAndAnUnknownPlaceIsSkipped() {
        val base = build(kyoto)
        val a = base.labels[2].key
        val b = base.labels[5].key
        val p = build(kyoto, listOf(VideoSlot("x", b, 2.0), VideoSlot("y", a, 1.5), VideoSlot("z", a, 3.0), VideoSlot("gone", "label:item:nope", 5.0)))
        assertEquals(listOf("y", "z", "x"), p.inserts.map { it.id })
        assertEquals(base.frameCount + 60 + 45 + 90, p.frameCount)
        // Back to back after one place.
        assertEquals(p.inserts[0].toFrame, p.inserts[1].fromFrame)
        for (i in 1 until p.inserts.size) assertTrue(p.inserts[i].fromFrame >= p.inserts[i - 1].toFrame)
        val only = build(kyoto, listOf(VideoSlot("gone", "label:item:nope", 5.0)))
        assertEquals(base.frameCount, only.frameCount)
        assertTrue(only.inserts.isEmpty())
    }

    @Test fun photoCardsAndCloseUpsKeepClearOfVideos() {
        for (input in listOf(kyoto.copy(photos = kyoto.stops.filterIndexed { i, _ -> i % 3 == 0 }.mapIndexed { i, s -> ClipPhoto("k$i", null, s.lat, s.lng) }), recorded)) {
            val base = build(input, photoHold = 0.0)
            val slots = base.labels.filterIndexed { i, _ -> i % 2 == 0 }.mapIndexed { i, l -> VideoSlot("v$i", l.key, 2.0) }
            for (hold in listOf(0.0, 2.0)) {
                val p = build(input, slots, photoHold = hold, closeUp = CloseUp.Close)
                assertTrue(p.inserts.isNotEmpty())
                for (photo in p.photos) for (v in p.inserts) {
                    assertTrue("${input.title} photo ${photo.fromFrame}..${photo.toFrame} video ${v.fromFrame}..${v.toFrame}",
                        photo.toFrame <= v.fromFrame || photo.fromFrame >= v.toFrame)
                }
                for (s in p.stills) for (v in p.inserts) assertTrue(s.toFrame <= v.fromFrame || s.fromFrame >= v.toFrame)
            }
        }
    }

    @Test fun theCameraKeepsItsSpeedLimitAndTheHeadAroundVideos() {
        val limit = ClipPlan.MAX_ZOOM_RATE + 1e-9
        for (input in listOf(kyoto, recorded)) {
            val base = build(input)
            val slots = base.labels.mapIndexed { i, l -> VideoSlot("v$i", l.key, 2.5) }.take(ClipVideo.MAX_VIDEOS)
            val p = build(input, slots, closeUp = CloseUp.VeryClose)
            assertEquals(slots.size, p.inserts.size)
            for (f in 1 until p.frameCount) {
                val a = p.camera(f - 1)
                val b = p.camera(f)
                if (f > p.openingEnd && f <= p.drawEnd) assertTrue("${input.title} frame $f", abs(ln(b.spanX / a.spanX)) <= limit)
                val st = p.frame(f)
                val r = st.camera.rect(p.options.shape.aspect)
                assertTrue((st.headX - r.x0) / r.w in 0.05..0.95 && (st.headY - r.y0) / r.h in 0.05..0.95)
            }
        }
    }

    @Test fun theSpeedChipsCountTheVideos() {
        val base = build(kyoto)
        val p = build(kyoto, listOf(VideoSlot("v", base.labels[3].key, 6.0)))
        for (speed in ClipTiming.SPEEDS) {
            val without = ClipTiming.seconds(base.travelHours, speed, base.photoHolds)
            val with = ClipTiming.seconds(p.travelHours, speed, p.photoHolds, p.insertSeconds)
            assertEquals(6.0, with - without, 1e-9)
        }
        assertEquals(base.seconds + 6.0, p.seconds, 1.0 / p.fps)
        // A video can move a free clip to a faster speed, as photo pauses do.
        val long = (1..3).map { VideoSlot("v$it", base.labels[it].key, 10.0) }
        val q = build(kyoto, long)
        assertTrue(ClipTiming.freeSpeed(q.travelHours, 1, false, q.photoHolds, q.insertSeconds) >= ClipTiming.freeSpeed(base.travelHours, 1, false, base.photoHolds))
    }

    // --- The video edit -----------------------------------------------------------------------------------------------

    @Test fun aVideoPlaysAtMostTenSecondsOfItself() {
        val long = ClipVideo("a", "a.mp4", "label:day:x", durationMs = 60_000)
        assertEquals(0L, long.startMs)
        assertEquals(ClipVideo.DEFAULT_MS, long.lengthMs)
        assertEquals(10_000L, long.trimmed(lengthMs = 30_000).lengthMs)
        assertEquals(1_000L, long.trimmed(lengthMs = 10).lengthMs)
        assertEquals(50_000L, long.trimmed(startMs = 58_000, lengthMs = 10_000).startMs)
        val short = ClipVideo("b", "b.mp4", "label:day:x", durationMs = 2_400)
        assertEquals(2_400L, short.lengthMs)
        assertEquals(2_400L, short.trimmed(lengthMs = 9_000).lengthMs)
        assertEquals(0L, short.trimmed(startMs = 5_000).startMs)
        val tiny = ClipVideo("c", "c.mp4", "k", durationMs = 400)
        assertEquals(400L, tiny.trimmed(lengthMs = 5_000).lengthMs)
        assertEquals(VideoSlot("a", "label:day:x", 5.0), long.slot)
        assertFalse(long.sound)
    }

    @Test fun videosAreKeptWithTheSceneEditsAndOldFilesLoadAsBefore() {
        val v = ClipVideo("v1", "v1.mp4", "label:item:42", durationMs = 12_000, startMs = 2_000, lengthMs = 6_000, sound = true)
        val edits = ClipEdits(scenes = mapOf("opening" to SceneOverride(text = "Hi")), videos = listOf(v, ClipVideo("v2", "v2.mov", "label:day:2026-06-17", 3_000)))
        val back = ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Planned" to edits)))
        assertEquals(edits, back["Planned"])
        // A file from before videos: the same edits, no videos.
        val old = """{"v":1,"sources":{"Planned":{"scenes":{"opening":{"text":"Hi"}},"order":{}}}}"""
        assertEquals(ClipEdits(scenes = mapOf("opening" to SceneOverride(text = "Hi"))), ClipEditsJson.decode(old)["Planned"])
        // Only videos: still kept (not an empty edit).
        assertFalse(ClipEdits(videos = listOf(v)).isEmpty)
        assertEquals(listOf(v), ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Stops" to ClipEdits(videos = listOf(v)))))["Stops"]?.videos)
        // Bad entries are left out: a path for a file name, no scene, no length; odd trims are brought back in.
        val bad = """{"v":1,"sources":{"Planned":{"videos":[
            {"id":"a","file":"../x.mp4","scene":"s","duration":5000},
            {"id":"b","file":"b.mp4","duration":5000},
            {"id":"c","file":"c.mp4","scene":"s","duration":0},
            {"id":"d","file":"d.mp4","scene":"s","duration":20000,"start":19000,"length":60000,"sound":"yes"},
            {"id":"d","file":"d2.mp4","scene":"s","duration":20000}
        ]}}}"""
        val decoded = ClipEditsJson.decode(bad)["Planned"]?.videos.orEmpty()
        assertEquals(listOf("d"), decoded.map { it.id })
        assertEquals(10_000L, decoded[0].startMs)
        assertEquals(10_000L, decoded[0].lengthMs)
        assertFalse(decoded[0].sound)
    }

    @Test fun aJournalVideoKeepsWhereItCameFromAndOlderSavedVideosStillRead() {
        val fromNote = ClipVideo("v1", "v1.mp4", "label:item:42", durationMs = 12_000, source = "media-7")
        val phone = ClipVideo("v2", "v2.mp4", "label:item:43", durationMs = 4_000)
        val edits = ClipEdits(videos = listOf(fromNote, phone))
        val text = ClipEditsJson.encode(mapOf("Planned" to edits))
        assertEquals(edits, ClipEditsJson.decode(text)["Planned"])
        // Only a journal video writes "from"; a phone video's entry is as before.
        assertEquals(1, Regex("\"from\"").findAll(text).count())
        // A video saved before journal videos could be added: no "from", read as a phone video with all else kept.
        val old = """{"v":1,"sources":{"Planned":{"videos":[
            {"id":"a","file":"a.mp4","scene":"label:item:1","duration":9000,"start":1000,"length":4000,"sound":true}
        ]}}}"""
        val back = ClipEditsJson.decode(old)["Planned"]?.videos?.single()
        assertEquals(ClipVideo("a", "a.mp4", "label:item:1", 9_000, startMs = 1_000, lengthMs = 4_000, sound = true), back)
        assertNull(back?.source)
        // An odd "from" (empty, or not text) is dropped, the video kept.
        val odd = """{"v":1,"sources":{"Planned":{"videos":[
            {"id":"b","file":"b.mp4","scene":"s","duration":5000,"from":""},
            {"id":"c","file":"c.mp4","scene":"s","duration":5000,"from":{"x":1}}
        ]}}}"""
        assertEquals(listOf<String?>(null, null), ClipEditsJson.decode(odd)["Planned"]?.videos?.map { it.source })
    }

    @Test fun editingVideosAndResettingScenesKeepsThem() {
        val v = ClipVideo("v1", "v1.mp4", "label:item:1", 8_000)
        var e = ClipEdits().withVideo(v)
        e = e.changeVideo("v1") { it.copy(sound = true) }
        assertTrue(e.video("v1")!!.sound)
        e = e.withVideo(v.copy(sceneKey = "label:item:2"))
        assertEquals(1, e.videos.size)
        assertEquals("label:item:2", e.videos.single().sceneKey)
        e = e.with("opening", SceneOverride(hidden = true))
        assertTrue(e.hasSceneEdits)
        val reset = e.resetScenes()
        assertFalse(reset.hasSceneEdits)
        assertEquals(e.videos, reset.videos)
        assertTrue(e.withoutVideo("v1").videos.isEmpty())
        assertSame(e, e.changeVideo("nope") { it })
        // The design hands the plan each video's place and length; sound is the mix's business.
        val d = ClipDesign().withEdits(e)
        assertEquals(e, d.edits)
        assertEquals(listOf(VideoSlot("v1", "label:item:2", 5.0)), d.applyTo(ClipOptions()).videos)
        assertEquals(ClipDesign().globals, d.globals)
        assertEquals(d.applyTo(ClipOptions()), d.withEdits(e.changeVideo("v1") { it.copy(sound = false) }).applyTo(ClipOptions()))
    }

    // --- Pictures -----------------------------------------------------------------------------------------------------

    @Test fun aVideoIsTurnedTheWayItWasFilmedAndCroppedToFill() {
        // A landscape video in a 9:16 clip: its middle third.
        assertEquals(1920 to 1080, VideoFit.shown(1920, 1080, 0))
        val wide = VideoFit.crop(1920, 1080, 1080, 1920)
        assertEquals(1080.0 / 1920, wide.step, 1e-9)
        assertEquals((1920 - 1080 * wide.step) / 2, wide.left, 1e-9)
        assertEquals(0.0, wide.top, 1e-9)
        // Filmed upright (stored landscape, turned 90°): it fills a story as it is.
        assertEquals(1080 to 1920, VideoFit.shown(1920, 1080, 90))
        val upright = VideoFit.crop(1080, 1920, 1080, 1920)
        assertEquals(1.0, upright.step, 1e-9)
        assertEquals(0.0, upright.left, 1e-9)
        // In a square: the middle of its height.
        val square = VideoFit.crop(1080, 1920, 1080, 1080)
        assertEquals(0.0, square.left, 1e-9)
        assertEquals(420.0, square.top, 1e-9)
        // Turned pictures: the shown corners come from the stored ones.
        assertEquals(0 to 1079, VideoFit.source(0, 0, 1920, 1080, 90))
        assertEquals(1919 to 0, VideoFit.source(0, 0, 1920, 1080, 270))
        assertEquals(1919 to 1079, VideoFit.source(0, 0, 1920, 1080, 180))
        assertEquals(0 to 0, VideoFit.source(1079, 0, 1920, 1080, 90))
        assertEquals(5 to 7, VideoFit.source(5, 7, 1920, 1080, 0))
        assertEquals(VideoFit.quarter(-90), VideoFit.quarter(270))
        for (r in listOf(0, 90, 180, 270)) {
            val (w, h) = VideoFit.shown(640, 360, r)
            val seen = HashSet<Pair<Int, Int>>()
            for (x in listOf(0, w - 1)) for (y in listOf(0, h - 1)) seen += VideoFit.source(x, y, 640, 360, r)
            assertEquals("rotation $r", setOf(0 to 0, 639 to 0, 0 to 359, 639 to 359), seen)
        }
    }

    @Test fun insertsFadeQuicklySoACutStaysACut() {
        assertEquals(4, InsertFade.frames(120, 30))
        assertEquals(1, InsertFade.frames(4, 30))
        assertEquals(0f, InsertFade.alpha(9, 10, 40, 30), 0f)
        assertTrue(InsertFade.alpha(10, 10, 40, 30) in 0.1f..0.3f)
        assertEquals(1f, InsertFade.alpha(25, 10, 40, 30), 0f)
        assertTrue(InsertFade.alpha(39, 10, 40, 30) < 0.3f)
        assertEquals(0f, InsertFade.alpha(40, 10, 40, 30), 0f)
    }

    @Test fun thePreviewSamplesEachVideoEightTimesASecond() {
        val times = PreparedTimes.of(10_000_000, 8)
        assertEquals(80, times.size)
        assertEquals(125_000L, times[1])
        assertEquals(0, PreparedTimes.index(times, 0))
        assertEquals(0, PreparedTimes.index(times, 124_999))
        assertEquals(1, PreparedTimes.index(times, 125_000))
        assertEquals(79, PreparedTimes.index(times, 60_000_000))
        assertEquals(1, PreparedTimes.of(10, 8).size)
    }

    // --- Sound --------------------------------------------------------------------------------------------------------

    @Test fun aVideosSoundSitsUnderTheMusicWithShortRamps() {
        assertEquals(0f, MusicMix.overlayGain(-1, 100, 10, 0.5f), 0f)
        assertEquals(0.05f, MusicMix.overlayGain(0, 100, 10, 0.5f), 1e-6f)
        assertEquals(0.5f, MusicMix.overlayGain(50, 100, 10, 0.5f), 0f)
        assertEquals(0.05f, MusicMix.overlayGain(99, 100, 10, 0.5f), 1e-6f)
        assertEquals(0f, MusicMix.overlayGain(100, 100, 10, 0.5f), 0f)
        // Over silence (no music): the sound at its gain where it is placed, nothing elsewhere.
        val pcm = ShortArray(2 * 40) { 10_000 }
        val o = MusicMix.Overlay(pcm, 40, at = 20, gain = 0.5f, fadeFrames = 0)
        val out = ShortArray(2 * 100)
        MusicMix.mix(null, 2, null, listOf(o), 0, 100, out)
        assertEquals(0, out[2 * 19].toInt())
        assertEquals(5_000, out[2 * 20].toInt())
        assertEquals(5_000, out[2 * 59 + 1].toInt())
        assertEquals(0, out[2 * 60].toInt())
        // A chunk that starts inside it.
        val part = ShortArray(2 * 10)
        MusicMix.mix(null, 2, null, listOf(o), 55, 10, part)
        assertEquals(5_000, part[0].toInt())
        assertEquals(0, part[2 * 5].toInt())
        // Over music: added, and kept within 16 bits.
        val music = ShortArray(2 * 200) { 30_000 }
        val plan = MusicMix.Plan(100, 200, 0, 0, 0)
        val loud = ShortArray(2 * 100)
        MusicMix.mix(music, 2, plan, listOf(o), 0, 100, loud)
        assertEquals(Short.MAX_VALUE.toInt(), loud[2 * 30].toInt())
    }

    @Test fun soundsAreBroughtToTheMixesRateAndChannels() {
        // Mono to stereo: both sides.
        val mono = shortArrayOf(0, 100, 200, 300)
        assertArrayEquals(shortArrayOf(0, 0, 100, 100, 200, 200, 300, 300), MusicMix.convert(mono, 4, 44_100, 1, 44_100, 2))
        // 48 kHz to 44.1 kHz: the same length in time, values in between.
        val ramp = ShortArray(4_800) { (it * 5).toShort() }
        val down = MusicMix.convert(ramp, 4_800, 48_000, 1, 44_100, 1)
        assertEquals(4_410, down.size)
        for (i in 1 until down.size) assertTrue(down[i] >= down[i - 1])
        assertEquals(ramp.last().toDouble(), down.last().toDouble(), 10.0)
        // Stereo to mono: averaged.
        assertArrayEquals(shortArrayOf(150), MusicMix.convert(shortArrayOf(100, 200), 1, 44_100, 2, 44_100, 1))
        assertEquals(0, MusicMix.convert(ShortArray(0), 0, 44_100, 2, 44_100, 2).size)
    }

    // --- Timeline -----------------------------------------------------------------------------------------------------

    @Test fun theTimelineRunsBackToBackWithVideosBetweenMapScenes() {
        for (input in listOf(kyoto, recorded)) {
            val base = build(input)
            val label = base.labels[base.labels.size / 2]
            val p = build(input, listOf(VideoSlot("v1", label.key, 3.0)), closeUp = CloseUp.Close)
            val t = ClipTimeline.of(p)
            assertEquals(p.frameCount, t.frames)
            assertEquals(p.fps, t.fps)
            assertEquals(0, t.scenes.first().from)
            assertEquals(SegmentKind.Opening, t.scenes.first().kind)
            assertEquals(SegmentKind.End, t.scenes.last().kind)
            assertEquals(p.frameCount, t.scenes.last().to)
            for (i in 1 until t.scenes.size) assertEquals("${input.title} $i", t.scenes[i - 1].to, t.scenes[i].from)
            assertTrue(t.scenes.all { it.frames > 0 })
            val video = t.scenes.single { it.kind == SegmentKind.Video }
            assertEquals("v1", video.id)
            assertEquals(label.key, video.key)
            assertEquals(p.inserts.single().fromFrame, video.from)
            val i = t.scenes.indexOf(video)
            // The place's map runs on both sides of its video: the second part is "continued".
            assertEquals(label.key, t.scenes[i - 1].key)
            assertFalse(t.scenes[i - 1].continued)
            if (t.scenes[i + 1].kind == SegmentKind.Map && t.scenes[i + 1].key == label.key) assertTrue(t.scenes[i + 1].continued)
            // Every place name is on the track, in order.
            assertEquals(p.labels.map { it.key }.distinct(), t.scenes.filter { it.kind == SegmentKind.Map && it.key != null }.map { it.key }.distinct())
            assertEquals(p.photos.size, t.photos.size)
            assertEquals(p.photos.count { it.held }, t.photos.count { it.kind == SegmentKind.PhotoPause })
            assertEquals(p.stills.size, t.closeUps.size)
            assertSame(video, t.sceneAt(video.from))
            assertEquals(t.frames / p.fps.toDouble(), t.seconds, 1e-9)
        }
    }

    @Test fun theTimelineShowsTheTravellersOwnNames() {
        val p = build(kyoto)
        val key = p.labels[2].key
        val design = ClipDesign(overrides = mapOf(key to SceneOverride(text = "Our ryokan"), ClipDesign.OPENING to SceneOverride(text = "Japan!")))
        val t = ClipTimeline.of(p, design)
        assertEquals("Japan!", t.scenes.first().title)
        assertTrue(t.scenes.first { it.key == key }.title.contains("Our ryokan"))
        assertNotNull(t.scenes.firstOrNull { it.kind == SegmentKind.Map && it.key == p.labels.first().key })
    }

    @Test fun theFramesAreLaidOutAsBeforeWhenNothingIsAdded() {
        // The opening (2), moving frames 0..4, a close-up at frame 1 with a photo pause in its middle, the close (2):
        // the same frames the clip always had (each moving frame, then its wait's first half, the photo, the rest).
        val blocks = java.util.TreeMap<Int, MutableList<Hold>>()
        blocks[1] = mutableListOf(Hold(HoldKind.StillIn, 1), Hold(HoldKind.Photo, 3, "p"), Hold(HoldKind.StillOut, 2))
        val laid = layFrames(2, 5, blocks, mapOf(1 to (3 to 0.5)), 2)
        assertArrayEquals(doubleArrayOf(-1.0, -1.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 3.0, 4.0, 5.0, 5.0), laid.src, 0.0)
        assertArrayEquals(intArrayOf(2, 3, 10, 11, 12, 13), laid.outOf)
        assertEquals(13, laid.drawEnd)
        assertEquals(5 to 8, laid.heldPops.single().let { it.fromFrame to it.toFrame })
        assertEquals(4 to 10, laid.stills.single().let { it.fromFrame to it.toFrame })
        assertTrue(laid.inserts.isEmpty())
        // A video after moving frame 3 and one before the first frame.
        blocks[3] = mutableListOf(Hold(HoldKind.Video, 4, "v", "label:x"))
        blocks[-1] = mutableListOf(Hold(HoldKind.Video, 2, "w", "label:y"))
        val more = layFrames(2, 5, blocks, mapOf(1 to (3 to 0.5)), 2)
        assertEquals(listOf("w" to (2 to 4), "v" to (14 to 18)), more.inserts.map { it.id to (it.fromFrame to it.toFrame) })
        assertEquals(0.0, more.src[2], 0.0)
        assertEquals(3.0, more.src[16], 0.0)
        assertEquals(19, more.drawEnd)
        assertEquals(21, more.src.size)
        // No plan without videos changes: none of them adds a frame.
        for (input in listOf(kyoto, recorded)) {
            val a = build(input)
            assertTrue(a.inserts.isEmpty())
            assertEquals(0.0, a.insertSeconds, 0.0)
            val b = build(input, listOf(VideoSlot("x", "label:nowhere", 3.0)))
            assertEquals(a.frameCount, b.frameCount)
            for (f in 0 until a.frameCount) assertEquals(a.camera(f), b.camera(f))
        }
    }
}

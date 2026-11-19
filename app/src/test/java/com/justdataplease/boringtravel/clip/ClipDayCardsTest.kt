package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.clip.music.MusicMix
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
import kotlin.math.abs
import kotlin.math.ln

/**
 * Voice-over and day cards (owner: "Voice-over and day cards"): a card opening each day (on, off, per day), where a
 * scene starts (its voice line's place), the music ducking under voices, and what the edits and prefs keep.
 */
class ClipDayCardsTest {
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
        photos = emptyList(),
    )

    private val kyoto: ClipInput by lazy {
        val plan = SampleKyoto.plan(LocalDate.of(2027, 10, 3), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), zone).getValue(ClipSource.Planned)
    }

    private val argentina: ClipInput by lazy {
        val plan = SampleArgentina.plan(LocalDate.of(2026, 10, 25), 0L)
        ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleArgentina.ZONE).getValue(ClipSource.Planned)
    }

    /** At the trip's own pace (no chosen length), where each card adds its time; see [cardsFitInsideAChosenLength]. */
    private fun build(input: ClipInput, design: ClipDesign = ClipDesign(), seconds: Int? = null) =
        requireNotNull(ClipPlan.build(input, design.applyTo(ClipOptions(seconds = seconds, length = null)), Locale.ENGLISH))

    @Test fun cardsFitInsideAChosenLength() {
        for (input in listOf(recorded, kyoto)) {
            val plain = requireNotNull(ClipPlan.build(input, ClipDesign().applyTo(ClipOptions()), Locale.ENGLISH))
            val p = requireNotNull(ClipPlan.build(input, ClipDesign(dayCards = true).applyTo(ClipOptions()), Locale.ENGLISH))
            assertTrue(p.inserts.isNotEmpty())
            // The cards come out of the journey's time: the clip keeps its length.
            assertEquals(input.title, plain.frameCount, p.frameCount)
            assertTrue(p.drawEnd - p.drawStart - p.insertSeconds * p.fps < plain.drawEnd - plain.drawStart)
        }
    }

    private val cardFrames = 45

    @Test fun dayCardsAreOffUntilAskedFor() {
        for (input in listOf(recorded, kyoto)) {
            val p = build(input)
            assertTrue(p.inserts.isEmpty())
            assertTrue("${input.title} has days", p.dayStarts.isNotEmpty())
        }
        assertFalse(ClipDesign().dayCards)
        assertEquals(DayCardChoice(), ClipDesign().dayCardChoice())
    }

    @Test fun aRecordedTripGetsACardBeforeEachDay() {
        val base = build(recorded)
        val p = build(recorded, ClipDesign(dayCards = true))
        val cards = p.inserts.filter { it.kind == InsertKind.DayCard }
        assertEquals(3, cards.size)
        assertEquals(base.frameCount + 3 * cardFrames, p.frameCount)
        assertEquals(3 * 1.5, p.insertSeconds, 1e-9)
        assertEquals(listOf("Day 1", "Day 2", "Day 3"), cards.map { it.card?.day })
        assertEquals((0..2).map { ClipDesign.dayCardKey(start.plusDays(it.toLong())) }, cards.map { it.key })
        assertEquals(listOf("Paros", "Naxos", "Naxos"), cards.map { it.card?.place })
        // The first card follows the opening; each comes right before its day's place name.
        assertEquals(p.drawStart, cards.first().fromFrame)
        for (card in cards) {
            val label = p.labels.first { it.key == card.card?.opens }
            assertEquals(label.from, card.toFrame)
            assertEquals(card.fromFrame, p.sceneStart(label.key))
            assertEquals(cardFrames, card.frames)
        }
        // The journey waits under each card, and no place name shows over it (the card has its own text).
        for (card in cards) for (f in card.fromFrame until card.toFrame) {
            assertEquals(p.frame(card.fromFrame).headX, p.frame(f).headX, 0.0)
            assertNull(p.frame(f).place)
        }
        // A video keeps its place's name on top.
        val v = requireNotNull(ClipPlan.build(recorded, ClipDesign(dayCards = true).applyTo(ClipOptions()).copy(
            videos = listOf(VideoSlot("v", base.labels[1].key, 2.0)),
        ), Locale.ENGLISH)).let { q -> q to q.inserts.first { it.kind == InsertKind.Video } }
        assertEquals(base.labels[1].key, v.first.frame(v.second.fromFrame + 10).labelKey)
    }

    @Test fun aPlannedTripGetsACardForEachDatedDay() {
        for (input in listOf(kyoto, argentina)) {
            val p = build(input, ClipDesign(dayCards = true))
            val cards = p.inserts.filter { it.kind == InsertKind.DayCard }
            assertEquals(p.dayStarts.size, cards.size)
            assertTrue(cards.size >= 2)
            val dates = cards.map { requireNotNull(it.card).date }
            assertEquals(dates.distinct(), dates)
            for (card in cards) {
                val c = requireNotNull(card.card)
                assertTrue(c.day!!.startsWith("Day "))
                val label = p.labels.first { it.key == c.opens }
                assertEquals(label.from, card.toFrame)
                // The stop's own title is its name; the day and the place go over it ("Day 2 · Kyoto").
                assertTrue(label.label.day == c.day || label.label.day!!.startsWith(c.day + " · "))
            }
            assertEquals(build(input).frameCount + cards.size * cardFrames, p.frameCount)
        }
    }

    @Test fun eachDayCanSayOtherwise() {
        val days = build(recorded).dayStarts
        val second = days[1].key
        // On, but not for day 2.
        val without = build(recorded, ClipDesign(dayCards = true, overrides = mapOf(second to SceneOverride(hidden = true))))
        assertEquals(listOf(days[0].key, days[2].key), without.inserts.map { it.key })
        // Off, but day 2 asked for.
        val only = ClipDesign(overrides = mapOf(second to SceneOverride(hidden = false)))
        assertEquals(DayCardChoice(false, setOf(second), emptySet()), only.dayCardChoice())
        assertTrue(only.dayCardShown(second))
        assertFalse(only.dayCardShown(days[0].key))
        assertEquals(listOf(second), build(recorded, only).inserts.map { it.key })
        // A day card's own line: the same timeline, only its frames drawn again.
        val a = ClipDesign(dayCards = true)
        val b = a.copy(overrides = mapOf(second to SceneOverride(text = "Ferry day")))
        val pa = build(recorded, a)
        val pb = build(recorded, b)
        assertTrue(pa.sameTimeline(pb))
        val card = pb.inserts.first { it.key == second }
        assertEquals(listOf(card.fromFrame until card.toFrame), ClipScenes.dirtyFrames(pa, pb, a, b))
    }

    @Test fun cardsComeAfterTheLastDaysVideoAndKeepTheCameraSteady() {
        val base = build(recorded)
        val day1 = base.labels.first().key
        val options = ClipDesign(dayCards = true).applyTo(ClipOptions()).copy(videos = listOf(VideoSlot("v", day1, 2.0)))
        val p = requireNotNull(ClipPlan.build(recorded, options, Locale.ENGLISH))
        assertEquals(listOf(InsertKind.DayCard, InsertKind.Video, InsertKind.DayCard, InsertKind.DayCard), p.inserts.map { it.kind })
        // Day 1's video, then day 2's card, back to back.
        assertEquals(p.inserts[1].toFrame, p.inserts[2].fromFrame)
        val limit = ClipPlan.MAX_ZOOM_RATE + 1e-9
        for (input in listOf(kyoto, argentina, recorded)) {
            val q = build(input, ClipDesign(dayCards = true, closeUp = CloseUp.Close))
            for (f in q.openingEnd + 1..q.drawEnd) assertTrue(abs(ln(q.camera(f).spanX / q.camera(f - 1).spanX)) <= limit)
        }
    }

    @Test fun aSceneStartsAtItsNameOrItsDayCard() {
        val p = build(kyoto, ClipDesign(dayCards = true))
        val opened = p.dayStarts.map { it.opens }.toSet()
        val plain = p.labels.first { it.key !in opened }
        assertEquals(plain.from, p.sceneStart(plain.key))
        assertNull(p.sceneStart("label:item:nothing"))
        val q = build(kyoto)
        for (l in q.labels) assertEquals(l.from, q.sceneStart(l.key))
    }

    // --- Voice lines --------------------------------------------------------------------------------------------------

    @Test fun voiceLinesAreKeptOnePerSceneWithTheEdits() {
        val a = VoiceLine("label:day:2026-06-16", "a.m4a", 4_200)
        var e = ClipEdits().withVoice(a)
        e = e.withVoice(VoiceLine("label:day:2026-06-16", "b.m4a", 2_000))
        assertEquals(listOf("b.m4a"), e.voices.map { it.file })
        e = e.withVoice(VoiceLine("label:day:2026-06-17", "c.m4a", 3_000))
        assertEquals(2, e.voices.size)
        assertFalse(e.isEmpty)
        assertEquals(e.voices, e.with("opening", SceneOverride(hidden = true)).resetScenes().voices)
        assertEquals(1, e.withoutVoice("label:day:2026-06-16").voices.size)
        val back = ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Recorded" to e)))["Recorded"]
        assertEquals(e, back)
        val d = ClipDesign().withEdits(e)
        assertEquals(e, d.edits)
        assertEquals("c.m4a", d.voice("label:day:2026-06-17")?.file)
        // Voices never change the plan (they are sound only).
        assertEquals(ClipDesign().applyTo(ClipOptions()), d.applyTo(ClipOptions()))
        // Odd entries are left out; a long one is cut to the longest line.
        val bad = """{"v":1,"sources":{"Recorded":{"voices":[
            {"scene":"s","file":"../a.m4a","duration":1000},
            {"scene":"s","file":"a.m4a"},
            {"file":"a.m4a","duration":1000},
            {"scene":"t","file":"t.m4a","duration":90000}
        ]}}}"""
        val decoded = ClipEditsJson.decode(bad)["Recorded"]?.voices.orEmpty()
        assertEquals(listOf(VoiceLine("t", "t.m4a", VoiceLine.MAX_MS)), decoded)
        // A file from before voices: none.
        assertTrue(ClipEditsJson.decode("""{"v":1,"sources":{"Recorded":{"scenes":{"end":{"text":"Bye"}}}}}""")["Recorded"]!!.voices.isEmpty())
    }

    @Test fun dayCardsAreRememberedWithTheStyle() {
        assertTrue(ClipPrefs.decode(ClipPrefs.encode(ClipDesign(dayCards = true))).dayCards)
        assertFalse(ClipPrefs.decode(ClipPrefs.encode(ClipDesign())).dayCards)
        assertFalse(ClipPrefs.decode("opening=title;name=top").dayCards)
    }

    @Test fun theMusicDucksUnderAVoiceAndComesBack() {
        val rate = 1_000
        val duck = requireNotNull(MusicMix.Duck.of(listOf(1_000L until 2_000L), rate))
        assertNull(MusicMix.Duck.of(emptyList(), rate))
        assertEquals(100L, duck.lead)
        assertEquals(250L, duck.down)
        assertEquals(600L, duck.up)
        assertEquals(1f, MusicMix.duckGain(0, duck), 0f)
        assertEquals(1f, MusicMix.duckGain(649, duck), 0f)
        // Down over 250 ms, ending 100 ms before the voice.
        val mid = MusicMix.duckGain(775, duck)
        assertTrue(mid > MusicMix.DUCK_GAIN && mid < 1f)
        assertEquals(MusicMix.DUCK_GAIN, MusicMix.duckGain(900, duck), 1e-6f)
        assertEquals(MusicMix.DUCK_GAIN, MusicMix.duckGain(1_999, duck), 1e-6f)
        // Back up over 600 ms after it.
        assertTrue(MusicMix.duckGain(2_300, duck) in MusicMix.DUCK_GAIN..1f)
        assertEquals(1f, MusicMix.duckGain(2_600, duck), 1e-6f)
        for (f in 600L until 2_700L) assertTrue(MusicMix.duckGain(f, duck) <= MusicMix.duckGain(f - 1, duck) || f >= 2_000)
        assertEquals(1f, MusicMix.duckGain(500, null), 0f)
        // Two voices close together: down through both.
        val two = requireNotNull(MusicMix.Duck.of(listOf(1_000L until 1_500L, 1_700L until 2_000L), rate))
        assertEquals(MusicMix.DUCK_GAIN, MusicMix.duckGain(1_550, two), 0.15f)
        // In the mix: the music dips, the voice itself does not.
        val music = ShortArray(2 * 3_000) { 10_000 }
        val plan = MusicMix.Plan(3_000, 3_000, 0, 0, 0)
        val voice = MusicMix.Overlay(ShortArray(2 * 1_000) { 2_000 }, 1_000, 1_000, 1f, 0)
        val out = ShortArray(2 * 3_000)
        MusicMix.mix(music, 2, plan, listOf(voice), 0, 3_000, out, duck)
        assertEquals(10_000, out[2 * 100].toInt())
        assertEquals((10_000 * MusicMix.DUCK_GAIN).toInt() + 2_000, out[2 * 1_500].toInt())
        assertEquals(10_000, out[2 * 2_900].toInt())
        // Without music, voices sound alone.
        val alone = ShortArray(2 * 3_000)
        MusicMix.mix(null, 2, null, listOf(voice), 0, 3_000, alone, duck)
        assertEquals(2_000, alone[2 * 1_500].toInt())
        assertEquals(0, alone[2 * 100].toInt())
    }

    // --- Timeline -----------------------------------------------------------------------------------------------------

    @Test fun theTimelineHasTheDayCardsAndTheVoices() {
        val design0 = ClipDesign(dayCards = true)
        val p0 = build(kyoto, design0)
        val opened = p0.dayStarts[1].opens
        val plain = p0.labels.first { it.key !in p0.dayStarts.map { d -> d.opens } }.key
        val design = design0.copy(voices = listOf(VoiceLine(opened, "v.m4a", 3_000), VoiceLine(plain, "w.m4a", 1_000), VoiceLine("label:item:gone", "x.m4a", 1_000)))
        val p = build(kyoto, design)
        val t = ClipTimeline.of(p, design)
        for (i in 1 until t.scenes.size) assertEquals(t.scenes[i - 1].to, t.scenes[i].from)
        val cards = t.scenes.filter { it.kind == SegmentKind.DayCard }
        assertEquals(p.dayStarts.size, cards.size)
        for (c in cards) {
            val next = t.scenes[t.scenes.indexOf(c) + 1]
            assertEquals(SegmentKind.Map, next.kind)
            assertTrue(c.title.startsWith("Day "))
        }
        assertEquals(2, t.voices.size)
        val v = t.voices.first { it.key == opened }
        assertEquals(p.sceneStart(opened), v.from)
        assertEquals(cards.first { p.inserts.first { i -> i.key == it.key }.card?.opens == opened }.from, v.from)
        assertEquals(90, v.frames)
        val w = t.voices.first { it.key == plain }
        assertEquals(p.labels.first { it.key == plain }.from, w.from)
        // A voice past the end is cut there.
        val last = p.labels.last()
        val late = design.copy(voices = listOf(VoiceLine(last.key, "z.m4a", VoiceLine.MAX_MS)))
        val tl = ClipTimeline.of(build(kyoto, late), late)
        assertTrue(tl.voices.single().to <= tl.frames)
        assertNotNull(tl.voices.single())
    }
}

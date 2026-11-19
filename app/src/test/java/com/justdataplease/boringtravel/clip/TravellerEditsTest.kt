package com.justdataplease.boringtravel.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A video's own traveller: kept with the trip's edits, shown over Me's, and redrawing the clip when it changes. */
class TravellerEditsTest {
    private val couple = Traveller(Who.Couple, woman = Person.WOMAN.copy(hair = HairStyle.Bun, glasses = Glasses.Sunglasses))

    @Test fun aVideosTravellerIsKeptWithItsEdits() {
        val edits = ClipEdits(traveller = couple)
        assertFalse(edits.isEmpty)
        val back = ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Planned" to edits)))
        assertEquals(mapOf("Planned" to edits), back)
        // Older files have none (Me's applies); a broken one is ignored, the rest still read.
        val old = """{"v":1,"sources":{"Planned":{"scenes":{"opening":{"text":"Hi"}},"order":{}}}}"""
        assertNull(ClipEditsJson.decode(old)["Planned"]?.traveller)
        val broken = """{"v":1,"sources":{"Planned":{"scenes":{"opening":{"text":"Hi"}},"traveller":42}}}"""
        assertEquals(ClipEdits(scenes = mapOf(ClipDesign.OPENING to SceneOverride(text = "Hi"))), ClipEditsJson.decode(broken)["Planned"])
        // Resetting the scenes keeps who walks, like the videos and voice lines.
        val withScene = edits.with(ClipDesign.OPENING, SceneOverride(text = "Hi"))
        assertEquals(couple, withScene.resetScenes().traveller)
        assertTrue(withScene.hasSceneEdits)
    }

    @Test fun theVideosOwnWinsOverMesAndIsAnEditNotAGlobal() {
        val me = Traveller(Who.Woman)
        val d = ClipDesign(travellerDefault = me)
        assertEquals(me, d.travellerShown)
        val own = d.withEdits(ClipEdits(traveller = couple))
        assertEquals(couple, own.travellerShown)
        assertEquals(couple, own.edits.traveller)
        // The globals (remembered for the next clip) carry neither.
        assertEquals(ClipDesign(), own.globals)
        assertNull(own.globals.traveller)
        // Remembered styles never store a traveller.
        assertFalse(ClipPrefs.encode(own).contains("traveller"))
        assertEquals(Traveller.DEFAULT, ClipPrefs.decode(ClipPrefs.encode(own)).travellerShown)
    }

    @Test fun anotherTravellerDrawsEveryFrameAgain() {
        val zone = ZoneId.of("Europe/Athens")
        val start = LocalDate.of(2026, 6, 16)
        val t0 = start.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val points = (0..60).map { m -> GeoPoint(t0 + m * 60_000L, 37.0853 + m * 0.0004, 25.1488 + m * 0.0003, 8f) }
        val input = ClipInput("Paros", start, start, zone, points, listOf(ClipStop(t0, 37.0853, 25.1488, "Parikia", "GR")), emptyList())
        val d0 = ClipDesign()
        val p = requireNotNull(ClipPlan.build(input, d0.applyTo(ClipOptions()), Locale.ENGLISH))
        assertEquals(emptyList<IntRange>(), ClipScenes.dirtyFrames(p, p, d0, d0))
        // This video's own, or Me's changing: everything (the traveller walks the whole journey).
        assertNull(ClipScenes.dirtyFrames(p, p, d0, d0.withEdits(ClipEdits(traveller = couple))))
        assertNull(ClipScenes.dirtyFrames(p, p, d0, d0.copy(travellerDefault = couple)))
        // Me's changing under a video that has its own: nothing to redraw.
        val own = d0.withEdits(ClipEdits(traveller = couple))
        assertEquals(emptyList<IntRange>(), ClipScenes.dirtyFrames(p, p, own, own.copy(travellerDefault = Traveller(Who.Woman))))
        // Who walks never changes the plan.
        val q = requireNotNull(ClipPlan.build(input, own.applyTo(ClipOptions()), Locale.ENGLISH))
        assertTrue(p.sameTimeline(q))
    }
}

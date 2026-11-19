package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.clip.music.MusicShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Editor round 2 (owner, 2026-11-08): a note, an exact spot and the small line on names; where the music starts. */
class EditorTextsAndMusicStartTest {
    private val auto = ClipLabel("Day 4", "Lisbon")
    private val key = ClipDesign.labelKey("day:2027-03-04")

    private fun design(o: SceneOverride) = ClipDesign(overrides = mapOf(key to o))

    @Test fun theSmallLineCanBeChangedOrLeftOut() {
        assertEquals(auto, ClipDesign().label(key, auto))
        assertEquals(ClipLabel("Sunday", "Lisbon"), design(SceneOverride(kicker = "Sunday")).label(key, auto))
        assertEquals("blank leaves it out", ClipLabel(null, "Lisbon"), design(SceneOverride(kicker = "")).label(key, auto))
        assertEquals(ClipLabel("Day 4", "Alfama"), design(SceneOverride(text = "Alfama")).label(key, auto))
        // A name that is only "Day 4" stays the name under a new small line.
        assertEquals(ClipLabel("Arrival", "Day 4"), design(SceneOverride(kicker = "Arrival")).label(key, ClipLabel("Day 4", null)))
    }

    @Test fun noteAndSpotAreTrimmedAndOptional() {
        val d = design(SceneOverride(note = "  Sunset at the miradouro ", spot = "Rua Augusta 24"))
        assertEquals("Sunset at the miradouro", d.note(key))
        assertEquals("Rua Augusta 24", d.spot(key))
        assertNull(design(SceneOverride(note = "   ")).note(key))
        assertNull(ClipDesign().spot(key))
    }

    @Test fun theNewFieldsAndTheMusicStartAreKept() {
        val e = ClipEdits(
            scenes = mapOf(key to SceneOverride(kicker = "", note = "Fado night", spot = "Alfama, Lisbon")),
            musicStartS = 31.875,
        )
        val back = ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Recorded" to e)))["Recorded"]
        assertEquals(e, back)
        assertEquals("the music start alone is an edit worth keeping", 12.0, ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Recorded" to ClipEdits(musicStartS = 12.0))))["Recorded"]?.musicStartS)
        assertEquals("kept by Reset every scene", 31.875, e.resetScenes().musicStartS)
        assertEquals(31.875, ClipDesign().withEdits(e).edits.musicStartS)
    }

    @Test fun beatsFollowTheStart() {
        val grid = BeatGrid(120.0, 0.25) // a beat every 0.5 s, the first at 0.25
        val fromStart = ClipBeats(grid, 120.0).times(4.0)
        assertEquals(0.25, fromStart.first(), 1e-9)
        // Started 10.1 s in: the next beat in the song is at 10.25, 0.15 s into the clip.
        val later = ClipBeats(grid, 120.0, startS = 10.1).times(4.0)
        assertEquals(0.15, later.first(), 1e-9)
        assertTrue(later.toList().zipWithNext().all { (a, b) -> abs(b - a - 0.5) < 1e-9 })
        // A start on a beat keeps the beat on the clip's first frame.
        assertEquals(0.0, ClipBeats(grid, 120.0, startS = 10.25).times(2.0).first(), 1e-9)
    }

    @Test fun startsSnapToABarAndStayInTheSong() {
        val t = MusicCatalog.byId("latin_groove")!!
        val bar = t.beats.periodS * MusicCatalog.BAR
        val at = MusicCatalog.onBar(t, 40.0)
        assertTrue(abs(40.0 - at) <= bar / 2 + 1e-9)
        assertEquals(0.0, ((at - t.beats.firstBeatS) / bar) - Math.round((at - t.beats.firstBeatS) / bar), 1e-6)
        assertEquals("the very start stays 0:00", 0.0, MusicCatalog.onBar(t, 0.3), 1e-9)
        assertTrue("never past the last start", MusicCatalog.onBar(t, 1e6) <= t.durationMs / 1000.0 - MusicCatalog.LAST_START_S)
        assertEquals("no track, no start", 0.0, MusicCatalog.startFor(null, 30.0), 1e-9)
    }

    @Test fun everyTrackHasAShapeAndItsBestPartOnABarWithRoomForAClip() {
        for (t in MusicCatalog.tracks) {
            val levels = MusicShapes.levels(t.id)
            assertTrue(t.id, levels.size >= t.durationMs / 1000 * 2 - 2)
            assertTrue(t.id, levels.all { it in 0f..1f } && levels.any { it == 1f })
            val hook = MusicShapes.hookS(t.id)
            assertTrue(t.id, hook <= t.durationMs / 1000.0 - 25.0)
            assertEquals(t.id, hook, MusicCatalog.startFor(t.id, null), 0.05)
        }
    }

    @Test fun theMarkerShrinksOnlyOnWholeCountryViews() {
        assertEquals(1.0, ClipRenderer.markerScale(0.0005), 1e-9)
        assertEquals(1.0, ClipRenderer.markerScale(ClipRenderer.MARKER_FULL_SPAN), 1e-9)
        assertEquals(ClipRenderer.MARKER_MIN_SCALE, ClipRenderer.markerScale(1.0), 1e-9)
        val mid = ClipRenderer.markerScale(0.1)
        assertTrue(mid < 1.0 && mid > ClipRenderer.MARKER_MIN_SCALE)
    }
}

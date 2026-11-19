package com.justdataplease.boringtravel.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * A place's own photos (owner, 2026-11-12: "in clip I only see one photo I don't seem to select how to add another or
 * change order"): ticking in and out, the order, photos brought from another place or from none, the room rule the
 * panel and the plan share, album pages, and older designs.
 */
class ClipPicksTest {
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

    /** Three days on Paros and Naxos, several photos at each day's end, and one with neither a time nor a place. */
    private val cyclades = ClipInput(
        title = "Cyclades", startDate = start, endDate = start.plusDays(2), zone = zone,
        points = leg(parikia, parikiaNorth, at(0, 9, 0), 40) + leg(parikiaNorth, naxos, at(1, 8, 0), 60) + leg(naxos, halki, at(2, 11, 0), 30),
        stops = listOf(
            ClipStop(at(0, 9, 40), parikiaNorth.first, parikiaNorth.second, "Parikia, Paros", "GR", "s0"),
            ClipStop(at(1, 9, 0), naxos.first, naxos.second, "Chora, Naxos", "GR", "s1"),
            ClipStop(at(2, 11, 30), halki.first, halki.second, "Halki, Naxos", "GR", "s2"),
        ),
        photos = (0 until 4).map { ClipPhoto("a$it", at(0, 9, 41 + it), parikiaNorth.first, parikiaNorth.second) } +
            (0 until 3).map { ClipPhoto("b$it", at(1, 9, 1 + it), naxos.first, naxos.second) } +
            (0 until 4).map { ClipPhoto("c$it", at(2, 11, 31 + it), halki.first, halki.second) } +
            ClipPhoto("lost", null, null, null),
    )

    /** With the defaults: Auto photos, full screen, 3 s pauses with half a second of still map, Auto length (25 s here). */
    private fun build(design: ClipDesign = ClipDesign()) = requireNotNull(ClipPlan.build(cyclades, design.applyTo(ClipOptions()), Locale.ENGLISH))

    private fun picked(picks: Map<String, List<String>>, d: ClipDesign = ClipDesign()) = d.withEdits(d.edits.copy(picks = picks))

    private fun placeOf(p: ClipPlan, id: String): String = requireNotNull(p.photoScenes.first { it.id == id }.placeKey)

    /** The photos the journey stops for, in the order they show. */
    private fun held(p: ClipPlan) = p.photos.filter { it.held }.sortedWith(compareBy({ it.fromFrame }, { it.album })).map { it.id }

    private fun panel(p: ClipPlan, d: ClipDesign, key: String) = PlacePhotos.of(p, d, ClipScenes.list(p, d), key)

    private val base by lazy { build() }
    private val placeA by lazy { placeOf(base, "a0") }
    private val placeC by lazy { placeOf(base, "c0") }

    // --- The sums the panel and the plan share -----------------------------------------------------------------------

    @Test fun eachPauseCountsAndTheStillMapOncePerPlace() {
        assertEquals(0.0, PhotoPicks.seconds(emptyList(), 0.5, PhotoLayout.Full), 0.0)
        assertEquals(7.0, PhotoPicks.seconds(listOf(3.0, 3.0), 0.5, PhotoLayout.Full), 1e-9)
        // A photo without a pause shows over the moving map and takes no time.
        assertEquals(4.0, PhotoPicks.seconds(listOf(3.0, 0.0), 0.5, PhotoLayout.Card), 1e-9)
        assertEquals(0.0, PhotoPicks.seconds(listOf(0.0), 0.5, PhotoLayout.Full), 0.0)
        // An album page of up to four is one pause, its first photo's.
        assertEquals(4.0, PhotoPicks.seconds(List(4) { 3.0 }, 0.5, PhotoLayout.Album), 1e-9)
        assertEquals(8.0, PhotoPicks.seconds(listOf(5.0, 3.0, 3.0, 3.0, 2.0), 0.5, PhotoLayout.Album), 1e-9)
        // What fits in 8.75 s (a 25 s clip's share): two pauses of 3 s, or eight photos on two album pages.
        assertEquals(2, PhotoPicks.fitting(List(3) { 3.0 }, 0.5, PhotoLayout.Full, 8.75))
        assertEquals(3, PhotoPicks.fitting(List(3) { 3.0 }, 0.5, PhotoLayout.Full, 10.0))
        assertEquals(0, PhotoPicks.fitting(List(3) { 3.0 }, 0.5, PhotoLayout.Full, 3.9))
        assertTrue(PhotoPicks.fits(listOf(3.0), 3.0, 0.5, PhotoLayout.Full, 8.75))
        assertFalse(PhotoPicks.fits(listOf(3.0, 3.0), 3.0, 0.5, PhotoLayout.Full, 8.75))
        assertEquals(1, PhotoPicks.more(listOf(3.0), 3.0, 0.5, PhotoLayout.Full, 8.75))
        assertEquals(8, PhotoPicks.more(emptyList(), 3.0, 0.5, PhotoLayout.Album, 8.75))
        assertEquals(99, PhotoPicks.more(listOf(3.0), 0.0, 0.5, PhotoLayout.Full, 0.0))
    }

    // --- Ticking, order, moving between places ----------------------------------------------------------------------

    @Test fun theFirstTickKeepsWhatShowedThenThePlaceShowsExactlyItsTicks() {
        val one = PhotoPicks.toggle(ClipEdits(), "P", shown = listOf("m"), id = "x")
        assertEquals(mapOf("P" to listOf("m", "x")), one.picks)
        assertEquals(listOf("m", "x"), PhotoPicks.ticked(one, "P", shown = emptyList()))
        val out = PhotoPicks.toggle(one, "P", listOf("m", "x"), "m")
        assertEquals(listOf("x"), out.picks["P"])
        // All taken out: none there (not back to what the clip picks).
        val none = PhotoPicks.toggle(out, "P", listOf("x"), "x")
        assertEquals(emptyList<String>(), none.picks["P"])
        assertFalse(none.isEmpty)
        // The order is the list's.
        assertEquals(listOf("x", "m"), PhotoPicks.move(one, "P", emptyList(), 1, 0).picks["P"])
        // Ticked at another place, a photo leaves the first one's list.
        val moved = PhotoPicks.toggle(one, "Q", emptyList(), "x")
        assertEquals(mapOf("P" to listOf("m"), "Q" to listOf("x")), moved.picks)
        assertEquals(listOf("a", "b"), ClipEdits().withPicks("P", listOf("a", "b", "a")).picks["P"])
        // Reset scene drops the place's list; Reset every scene drops them all.
        assertTrue(one.hasSceneEdits)
        assertFalse("P" in one.reset("P").picks)
        assertTrue(moved.resetScenes().picks.isEmpty())
    }

    @Test fun picksSurviveTheRoundTripAndOlderFilesHaveNone() {
        val e = ClipEdits(picks = mapOf("label:item:a" to listOf("p2", "p1"), "label:item:b" to emptyList()))
        assertEquals(mapOf("planned" to e), ClipEditsJson.decode(ClipEditsJson.encode(mapOf("planned" to e))))
        assertEquals(e.picks, ClipDesign().withEdits(e).edits.picks)
        assertEquals(e.picks, ClipDesign().withEdits(e).photoEdits().picks)
        assertTrue(ClipDesign().withEdits(e).globals.picks.isEmpty())
        // A file from 1.11: no lists, its own order and choices read as they were.
        val old = """{"v":1,"sources":{"planned":{"scenes":{"photo:p1":{"show":false}},"order":{"label:item:a":["p2","p1"]}}}}"""
        val read = ClipEditsJson.decode(old).getValue("planned")
        assertTrue(read.picks.isEmpty())
        assertEquals(listOf("p2", "p1"), read.order["label:item:a"])
        assertEquals(false, read.scene("photo:p1").show)
        // Odd values are dropped.
        val bad = ClipEditsJson.decode("""{"v":1,"sources":{"planned":{"picks":{"label:item:a":["p1",3,"","p1"],"label:item:b":"x","":["q"]}}}}""")
        assertEquals(mapOf("label:item:a" to listOf("p1")), bad.getValue("planned").picks)
    }

    // --- The plan -------------------------------------------------------------------------------------------------------

    @Test fun aPlaceShowsItsTicksTogetherInTheirOrder() {
        assertNotEquals(placeA, placeC)
        val d = picked(mapOf(placeA to listOf("a3", "a1")))
        val p = build(d)
        assertEquals(listOf("a3", "a1"), held(p).filter { placeOf(p, it) == placeA })
        // The place's other photos are left out; nothing else of it shows.
        assertTrue(p.photoScenes.filter { it.placeKey == placeA && it.id !in setOf("a1", "a3") }.all { it.excluded && !it.shown })
        // One stop: the journey stands still from the first to the last, each its own 3 s.
        val pops = p.photos.filter { it.id == "a3" || it.id == "a1" }.sortedBy { it.fromFrame }
        assertEquals(pops[0].toFrame, pops[1].fromFrame)
        assertTrue(pops.all { it.toFrame - it.fromFrame == 3 * p.fps })
        assertEquals(p.headDistance(pops[0].fromFrame), p.headDistance(pops[1].toFrame - 1), 1e-9)
        assertEquals(7.0, p.pickedSeconds.getValue(placeA), 1e-9)
        // The set length holds.
        assertEquals(25.0, p.seconds, 1.0 / p.fps)
        // The scene strip and the panel list them first, in that order.
        assertEquals(listOf("a3", "a1"), ClipScenes.list(p, d).filter { it.placeKey == placeA }.take(2).map { it.photoId })
        val here = panel(p, d, placeA)
        assertEquals(listOf("a3", "a1"), here.ticked)
        assertEquals(listOf("a3", "a1"), here.photos.take(2).map { it.id })
        assertEquals(2, here.shownCount)
        assertEquals(4, here.photos.size)
    }

    @Test fun otherPlacesKeepAutosPhotosInTheRoomLeft() {
        // One photo of the first place picked (4 s of the 8.75 s): Auto still shows one photo somewhere else.
        val p = build(picked(mapOf(placeA to listOf("a2"))))
        assertEquals("a2", held(p).single { placeOf(p, it) == placeA })
        assertEquals(1, held(p).count { placeOf(p, it) != placeA })
        // An empty list: none there, and Auto adds none.
        val none = build(picked(mapOf(placeA to emptyList())))
        assertTrue(none.photos.none { placeOf(none, it.id) == placeA })
        assertTrue(none.photos.isNotEmpty())
        // A list whose photos are all gone (deleted since) is no list: the place shows Auto's photos again.
        val gone = build(picked(mapOf(placeA to listOf("deleted1", "deleted2"))))
        assertEquals(held(base), held(gone))
        assertTrue(gone.photoScenes.filter { it.placeKey == placeA }.none { it.excluded })
    }

    @Test fun aTickPastTheRoomIsRefusedBeforeAndNeverShows() {
        // A 25 s clip has 8.75 s for photo pauses: two of 3 s with the still map around them.
        assertEquals(8.75, base.photoRoom, 1e-9)
        val two = picked(mapOf(placeA to listOf("a0", "a1")))
        val p = build(two)
        val here = panel(p, two, placeA)
        assertFalse(here.fits("a2"))
        assertTrue(here.fits("a0"))
        assertEquals(0, here.more(3.0))
        // A shorter pause makes room.
        val short = picked(mapOf(placeA to listOf("a0", "a1")), ClipDesign(photoHoldS = 1.0))
        val q = build(short)
        assertTrue(panel(q, short, placeA).fits("a2"))
        // Ticked all the same (or the clip made shorter after): the photos past the room don't show, the rest do.
        val three = picked(mapOf(placeA to listOf("a0", "a1", "a2")))
        val r = build(three)
        assertEquals(listOf("a0", "a1"), held(r))
        assertEquals(listOf("a2"), panel(r, three, placeA).noRoom)
        assertFalse(r.photoScenes.first { it.id == "a2" }.excluded)
        assertEquals(25.0, r.seconds, 1.0 / r.fps)
        // Room another place's own photos take is not this place's.
        val both = picked(mapOf(placeA to listOf("a0"), placeC to listOf("c0")))
        val s = build(both)
        assertEquals(listOf("a0", "c0"), held(s))
        assertFalse(panel(s, both, placeA).fits("a1"))
    }

    @Test fun atAPaceTheFreeVersionKeepsThePicksWithinAMinute() {
        // At a Pace the pauses add to the clip (up to two minutes); without Pro the panel stops at the free minute.
        val d = ClipDesign()
        val p = requireNotNull(ClipPlan.build(cyclades, d.applyTo(ClipOptions(length = null, speed = 1)), Locale.ENGLISH))
        val pro = PlacePhotos.of(p, d, ClipScenes.list(p, d), placeA, pro = true)
        val free = PlacePhotos.of(p, d, ClipScenes.list(p, d), placeA, pro = false)
        assertTrue(free.more(3.0) < pro.more(3.0))
        val ticked = free.ticked.map(d::photoPause)
        val now = PhotoPicks.seconds(ticked, 0.5, PhotoLayout.Full)
        val then = PhotoPicks.seconds(ticked + List(free.more(3.0)) { 3.0 }, 0.5, PhotoLayout.Full)
        assertTrue(p.seconds - now + then <= ClipTiming.FREE_MAX_SECONDS + 1e-6)
        // A set length holds whatever the version.
        assertEquals(panel(base, d, placeA).more(3.0), PlacePhotos.of(base, d, ClipScenes.list(base, d), placeA, pro = false).more(3.0))
    }

    @Test fun aPhotoFromAnotherPlaceOrFromNoneMovesHereAndBack() {
        assertTrue(base.photoScenes.none { it.id == "lost" })
        val d = picked(mapOf(placeA to listOf("a0", "c1")))
        val p = build(d)
        val moved = p.photoScenes.first { it.id == "c1" }
        assertEquals(placeA, moved.placeKey)
        assertTrue(moved.added && moved.shown)
        assertEquals(listOf("a0", "c1"), held(p))
        val pops = p.photos.sortedBy { it.fromFrame }
        assertEquals(p.headDistance(pops[0].fromFrame), p.headDistance(pops[1].fromFrame), 1e-9)
        assertTrue(panel(p, d, placeA).photos.single { it.id == "c1" }.added)
        // A photo with neither a time nor a place, added to a place, shows there.
        val lost = build(picked(mapOf(placeC to listOf("lost"))))
        val scene = lost.photoScenes.single { it.id == "lost" }
        assertEquals(placeC, scene.placeKey)
        assertTrue(scene.added && scene.shown)
        // Taken out, it goes back where the clip had it.
        val back = ClipDesign().withEdits(PhotoPicks.toggle(d.edits, placeA, emptyList(), "c1"))
        val r = build(back)
        assertEquals(placeC, placeOf(r, "c1"))
        assertFalse(r.photoScenes.first { it.id == "c1" }.added)
    }

    @Test fun albumPagesHoldFourOfAPlacesOwnPhotosEach() {
        val album = ClipDesign(photoLayout = PhotoLayout.Album)
        val wanted = listOf("a0", "a1", "a2", "a3", "b0", "b1")
        val d = picked(mapOf(placeA to wanted), album)
        val p = build(d)
        val pages = p.photos.filter { it.held }.groupBy { it.fromFrame }.toSortedMap().values.toList()
        assertEquals(listOf(4, 2), pages.map { it.size })
        assertEquals(wanted, held(p))
        assertTrue(pages.all { page -> page.all { it.albumOf == page.size } })
        assertEquals(7.0, p.pickedSeconds.getValue(placeA), 1e-9)
        assertEquals(25.0, p.seconds, 1.0 / p.fps)
    }

    @Test fun anOlderDesignAndListsForPlacesNotInTheClipChangeNothing() {
        val stale = build(picked(mapOf("label:day:1999-01-01" to listOf("a0", "lost"))))
        assertEquals(base.frameCount, stale.frameCount)
        assertEquals(base.photos.map { it.id to it.fromFrame }, stale.photos.map { it.id to it.fromFrame })
        assertEquals(base.photoHolds, stale.photoHolds)
        assertTrue(stale.photoScenes.none { it.added })
        // Without lists, the panel ticks what shows.
        val here = panel(base, ClipDesign(), placeA)
        assertEquals(base.photoScenes.filter { it.placeKey == placeA && it.shown }.map { it.id }, here.ticked)
        assertTrue(base.pickedSeconds.isEmpty())
    }
}

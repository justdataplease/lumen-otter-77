package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.SampleArgentina
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * The video editor's fourth round (owner, 2026-11-09): photos fill the frame by default and cards sit at the bottom
 * right; the trip's stats line (the day, the distance) small in the bottom left corner, with its own size, place,
 * font, background and colour; and how far the camera pulls back ("when zoom out to fix a level to zoom out").
 */
class ClipRound4Test {
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

    /** Three days on Paros and Naxos with a stop each day. */
    private val cyclades = ClipInput(
        title = "Cyclades", startDate = start, endDate = start.plusDays(2), zone = zone,
        points = leg(parikia, parikiaNorth, at(0, 9, 0), 40) + leg(parikiaNorth, naxos, at(1, 8, 0), 60) + leg(naxos, halki, at(2, 11, 0), 30),
        stops = listOf(
            ClipStop(at(0, 9, 40), parikiaNorth.first, parikiaNorth.second, "Parikia, Paros", "GR", "s0"),
            ClipStop(at(1, 9, 0), naxos.first, naxos.second, "Chora, Naxos", "GR", "s1"),
            ClipStop(at(2, 11, 30), halki.first, halki.second, "Halki, Naxos", "GR", "s2"),
        ),
        photos = emptyList(),
    )

    private fun argentina(): ClipInput {
        val plan = SampleArgentina.plan(LocalDate.of(2026, 10, 25), 0L)
        return ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleArgentina.ZONE).getValue(ClipSource.Planned)
    }

    private fun build(input: ClipInput, options: ClipOptions = ClipOptions(seconds = 30)) = requireNotNull(ClipPlan.build(input, options, Locale.ENGLISH))

    // --- Photos: full screen, cards at the bottom right ---------------------------------------------------------------

    @Test fun photosFillTheFrameByDefaultAndCardsSitBottomRight() {
        // Owner: "by default keep the full screen"; the card "at the bottom of the screen … bottom right".
        val d = ClipDesign()
        assertEquals(PhotoLayout.Full, d.photoLayout)
        assertEquals(PhotoLayout.Full, d.applyTo(ClipOptions()).photoLayout)
        assertEquals(PhotoRow.Bottom, d.photoRow)
        assertEquals(PhotoSide.Right, d.photoSide)
        val layout = ClipLayout(1080, 1920, story = true)
        val (x, y) = layout.cardCentre(d.photoRow, d.photoSide, slot = 0, cardW = 400f, cardH = 500f, nameBox = null)
        assertTrue("right half", x > 540f)
        assertTrue("lower half", y > 960f)
        // Every slot: no more left and right in turn.
        assertEquals(x, layout.cardCentre(d.photoRow, d.photoSide, slot = 1, cardW = 400f, cardH = 500f, nameBox = null).first, 0.01f)
    }

    @Test fun aRememberedStyleTakesTheNewPhotoDefaultsOnce() {
        val old = ClipPrefs.encode(ClipDesign(photoLayout = PhotoLayout.Card, photoSide = PhotoSide.Both, photoX = 0.2f, photoY = 0.3f, textScale = 1.3f))
        val upgraded = ClipPrefs.photoDefaults(ClipPrefs.decode(old))
        assertEquals(PhotoLayout.Full, upgraded.photoLayout)
        assertEquals(PhotoSide.Right, upgraded.photoSide)
        assertNull(upgraded.photoX)
        assertNull(upgraded.photoY)
        // The rest of the style stays.
        assertEquals(1.3f, upgraded.textScale, 0.001f)
        // A card chosen after the upgrade is remembered as such.
        assertEquals(PhotoLayout.Card, ClipPrefs.decode(ClipPrefs.encode(upgraded.copy(photoLayout = PhotoLayout.Card))).photoLayout)
    }

    // --- The stats line -----------------------------------------------------------------------------------------------

    @Test fun theStatsLineIsOnByDefaultWithTheDayAndTheDistance() {
        val d = ClipDesign()
        assertTrue(d.statsShown)
        assertEquals(setOf(TripStat.Day, TripStat.Distance), d.stats)
        assertNull("the bottom left corner by default", d.statsSpot)
        assertEquals(TextBack.None, d.statsBack)
        assertFalse(d.copy(stats = emptySet()).statsShown)
        // Drawing only: the plan doesn't change with it.
        assertEquals(d.applyTo(ClipOptions()), d.copy(stats = setOf(TripStat.Distance), statsScale = 1.5f, statsX = 0.8f, statsY = 0.1f).applyTo(ClipOptions()))
    }

    @Test fun theEditorOffersDayAndKmDayKmOrNothing() {
        // Owner: "select if i want day, day km, km only or nothing at all".
        assertEquals(listOf("Day · km", "Day", "km", "None"), StatsShow.entries.map { it.label })
        assertEquals(StatsShow.Both, StatsShow.of(ClipDesign().stats))
        assertEquals(StatsShow.Km, StatsShow.of(setOf(TripStat.Distance)))
        assertEquals(StatsShow.None, StatsShow.of(emptySet()))
        for (s in StatsShow.entries) assertEquals(s, StatsShow.of(s.stats))
    }

    @Test fun theStatsLineSaysTheDayAndTheDistance() {
        val s = TripStats(km = 128.4, day = 3, days = 10)
        assertEquals("Day 3 of 10 · 128 km", ClipText.statsLine(s, TripStat.DEFAULT, Locale.ENGLISH))
        assertEquals("128 km", ClipText.statsLine(s, setOf(TripStat.Distance), Locale.ENGLISH))
        assertEquals("Day 3 of 10", ClipText.statsLine(s, setOf(TripStat.Day), Locale.ENGLISH))
        // In the stats' own order, whatever order they were picked in.
        assertEquals("Day 1 · 4.2 km", ClipText.statsLine(TripStats(4.2, 1, 1), linkedSetOf(TripStat.Distance, TripStat.Day), Locale.ENGLISH))
        assertEquals("", ClipText.statsLine(s, emptySet(), Locale.ENGLISH))
    }

    @Test fun theNumbersCountUpWithTheJourney() {
        val plan = build(cyclades)
        var last = plan.statsAt(0)
        assertEquals(1, last.day)
        assertEquals(3, last.days)
        assertEquals(0.0, last.km, 0.001)
        for (f in 1 until plan.frameCount) {
            val s = plan.statsAt(f)
            assertTrue("distance never goes back at $f", s.km >= last.km - 1e-9)
            assertTrue("days never go back at $f", s.day >= last.day)
            assertTrue(s.day in 1..s.days)
            last = s
        }
        val end = plan.statsAt(plan.frameCount - 1)
        assertEquals(plan.totalKm, end.km, 0.05)
        assertEquals(3, end.day)
        assertEquals(end, plan.statsAtEnd.copy(km = end.km))
        // Each day's numbers show while the head is on that day.
        for ((i, d) in plan.days.withIndex()) assertEquals(i + 1, plan.statsAt((d.fromFrame + d.toFrame) / 2).day)
    }

    @Test fun aPlannedTripCountsItsDaysByDate() {
        val plan = build(argentina())
        assertEquals(plan.tripDays, plan.statsAtEnd.days)
        val days = (0 until plan.frameCount step 5).map { plan.statsAt(it).day }
        assertEquals(1, days.first())
        assertEquals(days.sorted(), days)
        // The last place's day by its own date (a trip's last days may have nothing on the route).
        val start = requireNotNull(argentina().startDate)
        assertTrue(plan.dayMarks.isNotEmpty())
        val last = (plan.dayMarks.last().second.toEpochDay() - start.toEpochDay() + 1).toInt()
        assertEquals("days ${days.distinct()}", last, plan.statsAt(plan.frameCount - 1).day)
        assertTrue("several days counted: ${days.distinct()}", days.distinct().size >= 5)
    }

    @Test fun theStatsLineSitsInTheBottomLeftCornerAndMovesWithThePad() {
        // Owner: "needed to be at the bottom bottom", circled to the corner; "change it same as you have the image …
        // full change in screen".
        for (shape in ClipShape.entries) {
            val layout = ClipLayout(shape.width, shape.height, shape == ClipShape.Story)
            val box = layout.statsBox(null, 300f, 50f)
            assertEquals(10f * layout.u, box.left, 0.01f)
            assertEquals(shape.height - 10f * layout.u, box.bottom, 0.01f)
            // Film's bars: just above the bottom one.
            val barred = ClipLayout(shape.width, shape.height, shape == ClipShape.Story, bars = true)
            assertTrue(barred.statsBox(null, 300f, 50f).bottom <= shape.height - barred.bar)
            val (ax, ay) = layout.statsAuto(300f, 50f)
            assertEquals(box.centerX / shape.width, ax, 0.001f)
            assertEquals(box.centerY / shape.height, ay, 0.001f)
        }
        val layout = ClipLayout(1080, 1920, story = true)
        // On the pad: its middle where the dot is, anywhere, kept inside the frame.
        val placed = layout.statsBox(0.5f to 0.5f, 300f, 50f)
        assertEquals(540f, placed.centerX, 0.5f)
        assertEquals(960f, placed.centerY, 0.5f)
        val top = layout.statsBox(0.5f to 0f, 300f, 50f)
        assertTrue(top.top >= 0f && top.top < 40f)
        val corner = layout.statsBox(1f to 1f, 300f, 50f)
        assertTrue(corner.right <= 1080f && corner.bottom <= 1920f && corner.right > 1040f && corner.bottom > 1880f)
    }

    @Test fun aCardKeepsClearOfTheStatsLine() {
        val layout = ClipLayout(1080, 1920, story = true)
        val stats = layout.statsBox(0.5f to 0.78f, 900f, 50f)
        val (x, y) = layout.cardCentre(PhotoRow.Bottom, PhotoSide.Right, 0, 500f, 600f, nameBox = null)
        val moved = layout.clearOf(stats, x, y, 500f, 600f)
        assertTrue("moved up", moved < y)
        assertFalse(FrameBox(x - 250f, moved - 300f, x + 250f, moved + 300f).intersects(stats))
        // Nothing in the way: it stays.
        assertEquals(y, layout.clearOf(null, x, y, 500f, 600f), 0.001f)
        assertEquals(y, layout.clearOf(layout.statsBox(0.1f to 0.1f, 200f, 40f), x, y, 500f, 600f), 0.001f)
        // The corner line sits under a story's cards.
        assertEquals(y, layout.clearOf(layout.statsBox(null, 600f, 50f), x, y, 500f, 600f), 0.001f)
    }

    @Test fun theStatsChoicesAreRemembered() {
        val d = ClipDesign(
            stats = setOf(TripStat.Distance), statsScale = 2.2f, statsX = 0.75f, statsY = 0.2f,
            statsFont = ClipFont.Serif, statsBack = TextBack.Glass, statsColor = TextColor.Accent, zoomOut = ZoomOut.Region,
        )
        val back = ClipPrefs.decode(ClipPrefs.encode(d))
        assertEquals(d.stats, back.stats)
        assertEquals(2.2f, back.statsScale, 0.001f)
        assertEquals(0.75f, back.statsX!!, 0.001f)
        assertEquals(0.2f, back.statsY!!, 0.001f)
        assertEquals(ClipFont.Serif, back.statsFont)
        assertEquals(TextBack.Glass, back.statsBack)
        assertEquals(TextColor.Accent, back.statsColor)
        assertEquals(ZoomOut.Region, back.zoomOut)
        // Nothing picked is kept as nothing, not as the default.
        assertEquals(emptySet<TripStat>(), ClipPrefs.decode(ClipPrefs.encode(ClipDesign(stats = emptySet()))).stats)
        // A style saved before these shows the line with the day and the distance, and zooms out as it always did.
        val before = ClipPrefs.decode("opening=title;name=top;photos=auto;camera=steady")
        assertTrue(before.statsShown)
        assertEquals(TripStat.DEFAULT, before.stats)
        assertEquals(ZoomOut.Auto, before.zoomOut)
        // 1.3.0's switch, off: nothing shows.
        assertFalse(ClipPrefs.decode("statsOn=off;stats=day,km").statsShown)
        // 1.3.0's stops are gone: what is left of the choice stays.
        assertEquals(setOf(TripStat.Day), ClipPrefs.decode("stats=day,stops").stats)
        assertEquals(setOf(TripStat.Day), TripStat.read("day,unknown"))
        assertNull(TripStat.read(null))
    }

    // --- How far the camera pulls back --------------------------------------------------------------------------------

    @Test fun theChosenZoomOutSetsTheOpeningAndTheWidestView() {
        val input = argentina()
        fun plan(z: ZoomOut) = build(input, ClipOptions(seconds = 40, map = MapStyle.Globe, zoomOut = z))
        val auto = plan(ZoomOut.Auto)
        val space = plan(ZoomOut.Space)
        val region = plan(ZoomOut.Region)
        val route = plan(ZoomOut.Route)
        // A trip this long opens from space by itself.
        assertTrue(auto.fromSpace)
        assertEquals(auto.overview.spanX, space.overview.spanX, 1e-9)
        assertFalse(region.fromSpace)
        assertFalse(route.fromSpace)
        assertTrue("region is closer than space", region.overview.spanX < space.overview.spanX)
        assertTrue("the route is closer still", route.overview.spanX <= region.overview.spanX)
        assertEquals(route.routeView.spanX, route.overview.spanX, 1e-9)
        // The camera never goes wider than the chosen view, from the first frame to the last.
        for (p in listOf(region, route)) {
            for (f in 0 until p.frameCount) assertTrue("frame $f", p.camera(f).spanX <= p.overview.spanX * 1.0001)
            assertEquals(p.overview.spanX, p.camera(0).spanX, p.overview.spanX * 1e-6)
            assertEquals(p.overview.spanX, p.camera(p.frameCount - 1).spanX, p.overview.spanX * 1e-6)
        }
        // A short trip: Auto is the region; Space takes it out to space all the same.
        val near = build(cyclades, ClipOptions(seconds = 30, map = MapStyle.Globe))
        assertFalse(near.fromSpace)
        assertTrue(build(cyclades, ClipOptions(seconds = 30, map = MapStyle.Globe, zoomOut = ZoomOut.Space)).fromSpace)
    }

    @Test fun theFollowCameraHoldsHowCloseWhateverTheSpeed() {
        // Owner: "maybe i have a close by region camera that just follows whatever ... did not work in preview".
        fun spans(p: ClipPlan): List<Double> {
            val a = p.drawStart + (p.drawEnd - p.drawStart) / 5
            val b = p.drawEnd - (p.drawEnd - p.drawStart) / 5
            return (a until b).map { p.camera(it).spanX }
        }
        fun median(v: List<Double>) = v.sorted()[v.size / 2]
        val input = argentina()
        val calm = build(input, ClipOptions(seconds = 30, zoom = ClipZoom.VeryClose))
        val follow = build(input, ClipOptions(seconds = 30, zoom = ClipZoom.VeryClose, camera = CameraMode.Chase))
        assertTrue("follow ${median(spans(follow))} vs calm ${median(spans(calm))}", median(spans(follow)) < median(spans(calm)) * 0.5)
        // Each zoom step is closer than the last.
        val far = build(input, ClipOptions(seconds = 30, zoom = ClipZoom.Far, camera = CameraMode.Chase))
        val normal = build(input, ClipOptions(seconds = 30, zoom = ClipZoom.Normal, camera = CameraMode.Chase))
        val close = build(input, ClipOptions(seconds = 30, zoom = ClipZoom.Close, camera = CameraMode.Chase))
        val m = listOf(far, normal, close, follow).map { median(spans(it)) }
        assertEquals(m.sortedDescending(), m)
        // The traveller stays in the picture.
        for (f in follow.drawStart until follow.drawEnd step 3) {
            val s = follow.frame(f)
            val c = s.camera
            assertTrue("frame $f", abs(s.headX - c.cx) <= c.spanX / 2 + 1e-9)
        }
        // Remembered and handed to the plan.
        assertEquals(CameraMode.Chase, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(camera = CameraMode.Chase))).camera)
        assertEquals(CameraMode.Chase, ClipDesign(camera = CameraMode.Chase).applyTo(ClipOptions()).camera)
    }

    @Test fun theMapIs3DUnlessFlatWasChosenAndTheChoiceIsKept() {
        // Owner, 2026-11-12: "the default in video should be globe 3d" (the sky band stays off by default).
        assertEquals("Globe in 3D by default (owner, 2026-11-12)", ClipView.Perspective, ClipPrefs.readView(null))
        assertEquals(ClipView.Flat, ClipPrefs.readView("flat"))
        assertEquals(ClipView.Perspective, ClipPrefs.readView("3d"))
        // Flat has no sky: the ground reaches the top of the frame.
        val plan = build(cyclades, ClipOptions(seconds = 30, view = ClipView.Flat))
        for (f in 0 until plan.frameCount step 10) assertEquals(0.0, plan.projection(f).horizonY, 1e-9)
    }

    @Test fun the3DSkyIsOffUnlessSwitchedOnAndTheChoiceIsKept() {
        // Owner, on 1.4.0's videos: "there is a gradient glare below the top place sign, can you remove it by default?"
        assertFalse(ClipDesign().sky)
        assertFalse(ClipOptions().sky)
        assertTrue(ClipPrefs.decode(ClipPrefs.encode(ClipDesign(sky = true))).sky)
        assertFalse(ClipPrefs.decode(ClipPrefs.encode(ClipDesign())).sky)
        assertTrue(ClipPrefs.encode(ClipDesign(sky = true)).contains("sky=on"))
        assertTrue(ClipPrefs.encode(ClipDesign()).contains("sky=off"))
        // A style saved before the switch (1.4.0 always had the band in 3D) reads without it.
        assertFalse(ClipPrefs.decode("opening=title;name=top;photos=auto;camera=steady").sky)
        assertFalse(ClipPrefs.decode("sky=maybe").sky)
        // It changes the plan; a look leaves it as it is.
        assertTrue(ClipDesign(sky = true).applyTo(ClipOptions()).sky)
        assertFalse(ClipDesign().applyTo(ClipOptions(sky = true)).sky)
        for (look in ClipLook.entries) assertTrue(look.apply(ClipDesign(sky = true)).sky)
        assertEquals(ClipLook.Film, ClipLook.Film.apply(ClipDesign(sky = true)).look)
        // 3D without the sky never shows a band and never leans past its cap; with it, the 1.4.0 camera.
        val plain = build(cyclades, ClipOptions(seconds = 30, view = ClipView.Perspective))
        val banded = build(cyclades, ClipOptions(seconds = 30, view = ClipView.Perspective, sky = true))
        assertTrue((0 until plain.frameCount).all { plain.projection(it).horizonY == 0.0 })
        assertEquals(ReplayProjection.MAX_TILT_NO_SKY, (0 until plain.frameCount).maxOf { plain.projection(it).tiltDegrees }, 1e-9)
        assertEquals(ReplayProjection.MAX_TILT, (0 until banded.frameCount).maxOf { banded.projection(it).tiltDegrees }, 1e-9)
        assertTrue((0 until banded.frameCount).any { banded.projection(it).horizonY > 0.0 })
    }

    @Test fun theZoomOutIsPartOfThePlan() {
        assertEquals(ZoomOut.Auto, ClipDesign().applyTo(ClipOptions()).zoomOut)
        assertEquals(ZoomOut.Route, ClipDesign(zoomOut = ZoomOut.Route).applyTo(ClipOptions()).zoomOut)
        // On a flat map too.
        val flat = build(cyclades, ClipOptions(seconds = 30, zoomOut = ZoomOut.Route))
        assertEquals(flat.routeView.spanX, flat.overview.spanX, 1e-9)
    }
}

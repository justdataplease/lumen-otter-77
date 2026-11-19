package com.justdataplease.boringtravel.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The clip's style: layout and positions, the route line, the stored choices, photo pauses, the end card's credits. */
class ClipDesignTest {
    // --- Layout ---------------------------------------------------------------------------------------------------

    private val story = ClipLayout(1080, 1920, story = true)

    @Test fun aStoryKeepsClearOfTheBandsSocialAppsCover() {
        assertEquals(3f, story.u, 1e-6f)
        assertEquals(1920 * 0.09f, story.safeTop, 1e-3f)
        assertEquals("Reels, TikTok and Shorts cover the bottom fifth", 1920 * 0.20f, story.safeBottom, 1e-3f)
        assertEquals("and a column of buttons on the right", 1080 * 0.12f, story.sideRight, 1e-3f)
        val square = ClipLayout(1080, 1080, story = false)
        assertEquals(48f, square.safeTop, 1e-3f)
        assertEquals(48f, square.safeBottom, 1e-3f)
    }

    @Test fun thePlaceNameSitsAtTheTopOrTheBottomInsideTheSafeBands() {
        val h = 120f
        assertEquals(story.safeTop, story.blockTop(NamePlace.Top, h), 1e-3f)
        assertEquals(1920 - story.safeBottom - h, story.blockTop(NamePlace.Bottom, h), 1e-3f)
        // Off: the opening (the only text then) goes to the top.
        assertEquals(story.safeTop, story.blockTop(NamePlace.Off, h), 1e-3f)
        assertEquals(1080 - 2 * 54f, story.blockMaxWidth, 1e-3f)
    }

    @Test fun photoCardsGoWhereChosenAndStayInTheFrame() {
        val w = story.cardWidth(ClipShape.Story, held = false, centred = false)
        assertEquals(1080 * 0.46f, w, 1e-3f)
        assertTrue(story.cardWidth(ClipShape.Story, held = true, centred = false) > w)
        assertTrue(story.cardWidth(ClipShape.Wide, held = false, centred = false) < w)
        val h = w * 1.3f
        for (row in PhotoRow.entries) for (side in PhotoSide.entries) for (slot in 0..1) for (name in NamePlace.entries) {
            val (x, y) = story.cardCentre(row, side, slot, w, h, name, 100f)
            assertTrue("$row $side x", x - w / 2 >= 0f && x + w / 2 <= 1080f)
            assertTrue("$row $side $name y", y - h / 2 >= story.safeTop - 1e-3f && y + h / 2 <= 1920 - story.safeBottom + 1e-3f)
        }
        val left = story.cardCentre(PhotoRow.Bottom, PhotoSide.Left, 1, w, h, NamePlace.Top, 0f).first
        val right = story.cardCentre(PhotoRow.Bottom, PhotoSide.Right, 0, w, h, NamePlace.Top, 0f).first
        val centre = story.cardCentre(PhotoRow.Bottom, PhotoSide.Centre, 0, w, h, NamePlace.Top, 0f).first
        assertTrue(left < centre && centre < right)
        assertEquals(540f, centre, 1e-3f)
        // Both: even slots on the left, odd on the right.
        assertEquals(left, story.cardCentre(PhotoRow.Bottom, PhotoSide.Both, 2, w, h, NamePlace.Top, 0f).first, 1e-3f)
        assertEquals(right, story.cardCentre(PhotoRow.Bottom, PhotoSide.Both, 3, w, h, NamePlace.Top, 0f).first, 1e-3f)
        val top = story.cardCentre(PhotoRow.Top, PhotoSide.Centre, 0, w, h, NamePlace.Bottom, 0f).second
        val middle = story.cardCentre(PhotoRow.Middle, PhotoSide.Centre, 0, w, h, NamePlace.Bottom, 0f).second
        val bottom = story.cardCentre(PhotoRow.Bottom, PhotoSide.Centre, 0, w, h, NamePlace.Top, 0f).second
        assertTrue(top < middle && middle < bottom)
        assertEquals(960f, middle, 1e-3f)
    }

    @Test fun aCardOnTheNamesRowMovesClearOfTheName() {
        val w = 400f
        val h = 500f
        val nameH = 150f
        val nameTop = story.blockTop(NamePlace.Bottom, nameH)
        val (_, y) = story.cardCentre(PhotoRow.Bottom, PhotoSide.Left, 0, w, h, NamePlace.Bottom, nameH)
        assertTrue("card bottom ${y + h / 2} vs name top $nameTop", y + h / 2 <= nameTop)
        val nameBottom = story.blockTop(NamePlace.Top, nameH) + nameH
        val (_, y2) = story.cardCentre(PhotoRow.Top, PhotoSide.Left, 0, w, h, NamePlace.Top, nameH)
        assertTrue(y2 - h / 2 >= nameBottom)
    }

    // --- Route line -----------------------------------------------------------------------------------------------

    @Test fun lineWidthsAndDashesScaleWithTheFrame() {
        assertEquals(listOf(3.5f, 5.5f, 8f), LineWeight.entries.map { it.dp })
        val medium = LineSpec.of(LineWeight.Medium, LineStyle.Solid, 3f)
        assertEquals(16.5f, medium.width, 1e-4f)
        assertEquals(16.5f + 9.075f, medium.casing, 1e-3f)
        assertNull(medium.dash)
        assertEquals(8.4f, medium.futureWidth, 1e-4f)
        assertEquals(26.4f, medium.futureGap, 1e-3f)
        val thin = LineSpec.of(LineWeight.Thin, LineStyle.Solid, 1f)
        // The casing never gets thinner than 2.4 dp beyond the line; the dots never under 2.8 dp or 7.5 dp apart.
        assertEquals(3.5f + 2.4f, thin.casing, 1e-4f)
        assertEquals(2.8f, thin.futureWidth, 1e-4f)
        assertEquals(7.5f, thin.futureGap, 1e-4f)
        val dashed = LineSpec.of(LineWeight.Bold, LineStyle.Dashed, 1f)
        assertEquals(listOf(12.8f, 17.6f), dashed.dash!!.toList())
        // Round caps add half a width at each end: a dash shows 1.6 + 1 widths, a gap 2.2 − 1.
        for (w in LineWeight.entries) {
            val d = LineSpec.of(w, LineStyle.Dashed, 2f).dash!!
            assertTrue(d[1] > w.dp * 2)
        }
        assertTrue(LineSpec.of(LineWeight.Bold, LineStyle.Solid, 1f).width > LineSpec.of(LineWeight.Thin, LineStyle.Solid, 1f).width)
    }

    // --- Stored choices -------------------------------------------------------------------------------------------

    @Test fun choicesSurviveTheRoundTrip() {
        val d = ClipDesign(
            opening = Opening.None, name = NamePlace.Bottom, font = ClipFont.Tall, back = TextBack.Glass,
            panel = PanelShape.Pill, color = TextColor.Accent, line = LineStyle.Dashed, weight = LineWeight.Bold,
            lineColor = LineColor.White, photoRow = PhotoRow.Middle, photoSide = PhotoSide.Right, photoMode = PhotoMode.Auto,
            photoHoldS = 4.5,
        )
        assertEquals(d, ClipPrefs.decode(ClipPrefs.encode(d)))
        assertEquals(ClipDesign(), ClipPrefs.decode(ClipPrefs.encode(ClipDesign())))
        // Every value of every choice survives on its own.
        for (v in ClipFont.entries) assertEquals(v, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(font = v))).font)
        for (v in TextColor.entries) assertEquals(v, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(color = v))).color)
        for (v in listOf(PhotoMode.Auto, PhotoMode.Off)) assertEquals(v, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(photoMode = v))).photoMode)
        for (v in PhotoSide.entries) assertEquals(v, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(photoSide = v))).photoSide)
    }

    @Test fun missingOrUnknownChoicesAreTheDefaults() {
        assertEquals(ClipDesign(), ClipPrefs.decode(null))
        assertEquals(ClipDesign(), ClipPrefs.decode(""))
        assertEquals(ClipDesign(), ClipPrefs.decode("font=comic;name=sideways;garbage;=x"))
        assertEquals(ClipDesign(name = NamePlace.Off), ClipPrefs.decode("name=off;future=thing"))
    }

    @Test fun theDefaultsAreAPanelledNameOnTopAutoPhotosAndTheSteadyCamera() {
        val d = ClipDesign()
        assertEquals(NamePlace.Top, d.name)
        assertEquals(Opening.Title, d.opening)
        assertEquals(TextBack.Panel, d.back)
        // Owner, 2026-11-08: "keep the defaults strava like" ("more photos", no zoom pumping, a set length).
        assertEquals(PhotoMode.Auto, d.photoMode)
        assertEquals(CameraMode.Steady, d.camera)
        val o = d.applyTo(ClipOptions())
        assertTrue(o.photos && o.photoAuto)
        // Owner, 2026-11-09: "pause 3s the video the image appears and disappears when there is movement".
        assertEquals(3.0, o.photoHoldSeconds, 1e-9)
        assertEquals(CameraMode.Steady, o.camera)
        assertEquals(ClipLength.Auto, o.length)
        assertEquals(CameraMode.Follow, ClipDesign(camera = CameraMode.Follow).applyTo(ClipOptions()).camera)
        val pause = ClipDesign(photoMode = PhotoMode.Pause2).applyTo(ClipOptions())
        assertTrue(pause.photos && !pause.photoAuto)
        assertEquals(2.0, pause.photoHoldSeconds, 1e-9)
        val off = ClipDesign(photoMode = PhotoMode.Off).applyTo(ClipOptions())
        assertFalse(off.photos)
        assertEquals(0.0, ClipDesign(photoMode = PhotoMode.On).applyTo(ClipOptions()).photoHoldSeconds, 1e-9)
    }

    @Test fun theCameraAndTheLengthAreRemembered() {
        for (c in CameraMode.entries) assertEquals(c, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(camera = c))).camera)
        for (m in listOf(PhotoMode.Auto, PhotoMode.Off)) assertEquals(m, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(photoMode = m))).photoMode)
        assertEquals(CameraMode.Follow, CameraMode.of("follow"))
        assertNull(CameraMode.of("orbit"))
        // A style saved before the steady camera, with the photo pauses the clip used to start with, reads as Auto
        // photos; one that chose another photo mode reads as Auto with its pause (the editor offers Auto or Off).
        assertEquals(ClipDesign(), ClipPrefs.decode("photos=pause2;zoom=normal"))
        assertEquals(PhotoMode.Auto to 3.0, ClipPrefs.decode("photos=pause3;zoom=normal").let { it.photoMode to it.holdSeconds })
        assertEquals(PhotoMode.Auto to 2.0, ClipPrefs.decode("photos=pause2;camera=steady").let { it.photoMode to it.holdSeconds })
        assertEquals(PhotoMode.Auto to 3.0, ClipPrefs.decode("photos=on;camera=steady").let { it.photoMode to it.holdSeconds })
        assertEquals(PhotoMode.Off, ClipPrefs.decode("photos=off").photoMode)
        // Lengths: Auto the first time, Pace when the clip was last made at a speed.
        assertEquals(ClipLength.Auto, ClipPrefs.readLength(null))
        assertEquals(ClipLength.Auto, ClipPrefs.readLength("90"))
        assertNull(ClipPrefs.readLength(ClipPrefs.PACE))
        for (l in ClipLength.entries) assertEquals(l, ClipPrefs.readLength(l.id))
    }

    @Test fun aSceneOverrideWinsOverTheGlobalChoice() {
        val d = ClipDesign(
            photoRow = PhotoRow.Bottom, photoSide = PhotoSide.Both, name = NamePlace.Top,
            overrides = mapOf(
                ClipDesign.photoKey("p1") to SceneOverride(photoRow = PhotoRow.Top),
                ClipDesign.labelKey("day:2026-06-17") to SceneOverride(name = NamePlace.Bottom),
            ),
        )
        assertEquals(PhotoRow.Top, d.photoRow("p1"))
        assertEquals(PhotoSide.Both, d.photoSide("p1"))
        assertEquals(PhotoRow.Bottom, d.photoRow("p2"))
        assertEquals(NamePlace.Bottom, d.labelPlace(ClipDesign.labelKey("day:2026-06-17")))
        assertEquals(NamePlace.Top, d.labelPlace(ClipDesign.labelKey("day:2026-06-16")))
        assertEquals(NamePlace.Top, d.labelPlace(null))
    }

    // --- Texts ----------------------------------------------------------------------------------------------------

    @Test fun theEndCardCreditsEveryMapSource() {
        for (style in MapStyle.entries) {
            val credits = ClipText.credits(style.credits)
            assertTrue(style.name, credits.any { it.contains("OpenStreetMap") })
            assertTrue(style.name, credits.any { it.contains("OpenFreeMap") })
        }
        assertTrue(ClipText.credits(MapStyle.Terrain.credits).any { it.startsWith("Terrain") })
        assertTrue(ClipText.credits(MapStyle.Earth.credits).any { it.contains("EOX") })
        // No map, nothing to credit; blanks and repeats dropped.
        assertTrue(ClipText.credits(BaseMap.Plain.credits).isEmpty())
        assertEquals(listOf("a b"), ClipText.credits(listOf(" a b ", "", "a b")))
    }

    @Test fun placeLabelsJoinTheirParts() {
        assertEquals("Day 4 · Naxos", ClipLabel("Day 4", "Naxos").text)
        assertEquals("Naxos", ClipLabel(null, "Naxos").text)
        assertEquals("Day 4", ClipLabel("Day 4", null).text)
    }

    // --- Photo pauses in a plan -----------------------------------------------------------------------------------

    private val zone = ZoneId.of("Europe/Athens")
    private val start = LocalDate.of(2026, 6, 16)

    /** A morning walk in Parikia with a photo every few minutes along it. */
    private fun walk(photos: Int): ClipInput {
        val t0 = start.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val points = (0..60).map { m -> GeoPoint(t0 + m * 60_000L, 37.0853 + m * 0.0004, 25.1488 + m * 0.0003, 8f) }
        return ClipInput(
            title = "Parikia", startDate = start, endDate = start, zone = zone, points = points,
            stops = listOf(ClipStop(t0, 37.0853, 25.1488, "Parikia, Paros", "GR")),
            photos = (0 until photos).map { ClipPhoto("p$it", t0 + (5 + it * 50 / photos.coerceAtLeast(1)) * 60_000L, null, null) },
        )
    }

    @Test fun theJourneyStandsStillWhileAHeldPhotoShows() {
        // Without the still map around the pauses (ClipRound3Test has it).
        val held = requireNotNull(ClipPlan.build(walk(3), ClipOptions(length = null, speed = 1, photoHoldSeconds = 2.0, photoGapSeconds = 0.0), Locale.ENGLISH))
        val moving = requireNotNull(ClipPlan.build(walk(3), ClipOptions(length = null, speed = 1), Locale.ENGLISH))
        assertEquals(3, held.photoCandidates)
        // As many pauses as the clip has room for (one per 4 s: two of the three in this 11 s walk).
        val pauses = held.photos.size
        assertEquals(ClipTiming.heldPhotos(3, ClipTiming.baseSeconds(held.travelHours, 1), 2.0), pauses)
        assertTrue(pauses >= 2)
        assertTrue(held.photos.all { it.held && it.toFrame - it.fromFrame == 60 })
        // Each pause adds its frames; the opening and the close keep their length.
        assertEquals(moving.frameCount + pauses * 60, held.frameCount)
        assertEquals(moving.drawStart, held.drawStart)
        assertEquals(moving.frameCount - moving.drawEnd, held.frameCount - held.drawEnd)
        for (pop in held.photos) {
            val at = held.frame(pop.fromFrame - 1)
            for (f in pop.fromFrame until pop.toFrame) {
                val s = held.frame(f)
                assertEquals(at.headX, s.headX, 0.0)
                assertEquals(at.headY, s.headY, 0.0)
                assertEquals(held.headDistance(pop.fromFrame - 1), held.headDistance(f), 0.0)
            }
            assertTrue(held.headDistance(pop.toFrame) >= held.headDistance(pop.toFrame - 1))
            assertEquals(1, held.frame((pop.fromFrame + pop.toFrame) / 2).photos.size)
        }
        // The head still only moves forward and ends where it should.
        for (f in 1 until held.frameCount) assertTrue(held.headDistance(f) >= held.headDistance(f - 1) - 1e-6)
        assertEquals(held.totalKm * 1000, held.headDistance(held.frameCount - 1), 1e-6)
        assertEquals(held.drawStart, held.days.first().fromFrame)
        assertEquals(held.drawEnd, held.days.last().toFrame)
    }

    @Test fun theSpeedChipsShowTheLengthWithPauses() {
        for (photos in listOf(0, 1, 3, 12)) for (hold in listOf(0.0, 2.0, 3.0)) for (speed in ClipTiming.SPEEDS) {
            val p = requireNotNull(ClipPlan.build(walk(photos), ClipOptions(length = null, speed = speed, photoHoldSeconds = hold, photoGapSeconds = 0.0), Locale.ENGLISH))
            val chip = ClipTiming.seconds(p.travelHours, speed, p.photoCandidates, hold)
            assertEquals("$photos photos, $hold s, ×$speed", ClipTiming.clock(chip), ClipText.clock(p.frameCount, p.fps))
            assertEquals(ClipTiming.heldPhotos(p.photoCandidates, ClipTiming.baseSeconds(p.travelHours, speed), hold),
                p.photos.count { it.held })
        }
    }

    @Test fun pausesAreCappedAndKeepTheClipWithinTwoMinutes() {
        assertEquals(0, ClipTiming.heldPhotos(5, 30.0, 0.0))
        assertEquals(0, ClipTiming.heldPhotos(0, 30.0, 2.0))
        assertEquals(5, ClipTiming.heldPhotos(5, 30.0, 2.0))
        // One pause per 4 s of the clip at most.
        assertEquals(7, ClipTiming.heldPhotos(40, 30.0, 2.0))
        assertEquals(1, ClipTiming.heldPhotos(40, 6.0, 3.0))
        // A long clip only gets the pauses that fit within 120 s.
        assertEquals(5, ClipTiming.heldPhotos(40, 110.0, 2.0))
        assertTrue(ClipTiming.seconds(58.0, 1, 40, 3.0) <= ClipTiming.MAX_SECONDS)
        assertEquals(ClipTiming.baseSeconds(5.0, 2), ClipTiming.seconds(5.0, 2), 1e-9)
        assertEquals(ClipTiming.baseSeconds(5.0, 2) + 3 * 2.0, ClipTiming.seconds(5.0, 2, 3, 2.0), 1e-9)
    }

    @Test fun pausesCanMoveAFreeClipToAFasterSpeed() {
        // 23 h of travel runs 0:49 at ×1; a dozen 3 s pauses push it past a minute.
        val travel = 23.0
        assertEquals(1, ClipTiming.freeSpeed(travel, 1, isPro = false))
        assertEquals(2, ClipTiming.freeSpeed(travel, 1, isPro = false, photos = 12, holdSeconds = 3.0))
        assertEquals(1, ClipTiming.freeSpeed(travel, 1, isPro = true, photos = 12, holdSeconds = 3.0))
    }

    @Test fun photosOverTheMovingRouteDontChangeTheLength() {
        val plain = requireNotNull(ClipPlan.build(walk(0), ClipOptions(length = null, speed = 1), Locale.ENGLISH))
        val withPhotos = requireNotNull(ClipPlan.build(walk(6), ClipOptions(length = null, speed = 1), Locale.ENGLISH))
        assertEquals(plain.frameCount, withPhotos.frameCount)
        assertTrue(withPhotos.photos.isNotEmpty() && withPhotos.photos.none { it.held })
        val off = requireNotNull(ClipPlan.build(walk(6), ClipOptions(length = null, speed = 1, photos = false, photoHoldSeconds = 2.0), Locale.ENGLISH))
        assertEquals(plain.frameCount, off.frameCount)
        assertTrue(off.photos.isEmpty())
    }

    @Test fun photosAreDecodedSharpButOnlyAsBigAsACardShows() {
        assertEquals(600 to 800, ClipExport.photoTarget(3000, 4000, 600))
        assertEquals(800 to 600, ClipExport.photoTarget(4000, 3000, 600))
        assertEquals(400 to 300, ClipExport.photoTarget(400, 300, 600))
        // Landscape keeps its middle square, portrait its middle 4:5; nothing gets bigger.
        assertEquals(600 to 600, ClipExport.photoCrop(800, 600))
        assertEquals(600 to 750, ClipExport.photoCrop(600, 800))
        assertEquals(600 to 700, ClipExport.photoCrop(600, 700))
        assertEquals(500 to 500, ClipExport.photoCrop(500, 500))
    }

    @Test fun theEncoderGetsAboutAQuarterBitPerPixel() {
        assertEquals(16_174_080, Mp4Encoder.defaultBitRate(1080, 1920, 30))
        assertEquals(16_174_080, Mp4Encoder.defaultBitRate(1920, 1080, 30))
        assertEquals(9_097_920, Mp4Encoder.defaultBitRate(1080, 1080, 30))
        assertEquals(6_000_000, Mp4Encoder.defaultBitRate(640, 360, 30))
        assertEquals(20_000_000, Mp4Encoder.defaultBitRate(2160, 3840, 30))
    }
}

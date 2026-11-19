package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.model.TravelDefault
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.trip.travel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The video editor's third round (owner, 2026-11-09): the still map around each photo pause, the pause and size
 * sliders, the stops' own titles, walking between the items of a place, Skip stop, album pages, the thumb pad's spots,
 * the text size, and the new choices remembered.
 */
class ClipRound3Test {
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

    /** Three days on Paros and Naxos, several photos at each day's end. */
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
            (0 until 4).map { ClipPhoto("c$it", at(2, 11, 31 + it), halki.first, halki.second) },
    )

    // A planned trip: the flight in, three sights of Lisbon on day 1, Sintra on day 2, the train north on day 3.
    private val day = LocalDate.of(2031, 5, 9)
    private val trip = Trip("lx", "Lisbon", startDate = day, endDate = day.plusDays(2), createdAt = 1, updatedAt = 1)
    private fun item(id: String, title: String, lat: Double, lng: Double, d: Int, pos: Int, placeName: String = "", kind: PlanKind = PlanKind.Place) =
        PlanItem(id = id, tripId = trip.id, day = day.plusDays(d.toLong()), position = pos.toDouble(), title = title, placeName = placeName,
            kind = kind.id, lat = lat, lng = lng, createdAt = 1, updatedAt = 1)

    private val items = listOf(
        item("fly", "Fly to Lisbon", 38.7742, -9.1342, 0, 0, kind = PlanKind.Transport),
        item("se", "Lisbon Cathedral", 38.7099, -9.1334, 0, 1, placeName = "Sé de Lisboa, Lisbon, Portugal"),
        item("castle", "São Jorge Castle", 38.7139, -9.1335, 0, 2, placeName = "Castelo de São Jorge, Lisbon, Portugal"),
        item("tram", "Tram 28 and the lanes of Alfama with a long lunch", 38.7115, -9.1300, 0, 3, placeName = "Alfama, Lisbon, Portugal"),
        item("pena", "Pena Palace", 38.7876, -9.3906, 1, 0, placeName = "Palácio da Pena, Sintra, Portugal"),
        item("porto", "Train to Porto", 41.1496, -8.6109, 2, 0, kind = PlanKind.Transport),
    )
    private val lisbonZone = ZoneId.of("Europe/Lisbon")
    private fun planned(t: Trip = trip) = ClipSources.available(t, emptyList(), emptyList(), items, emptyList(), lisbonZone).getValue(ClipSource.Planned)

    private fun build(input: ClipInput, options: ClipOptions = ClipOptions()) = requireNotNull(ClipPlan.build(input, options, Locale.ENGLISH))
    private fun build(input: ClipInput, design: ClipDesign) = build(input, design.applyTo(ClipOptions()))

    // --- 1. The still map before and after each photo -----------------------------------------------------------------

    @Test fun theMapStandsStillHalfASecondBeforeAndAfterEachPhoto() {
        // Owner: "when the movement ends wait like half second and appear the photo. then photo disappears wait half a
        // second and start the movement"; "maybe have this as a slider".
        for (gapS in listOf(0.5, 2.0)) {
            val p = build(cyclades, ClipDesign(photoGapS = gapS))
            val gap = (gapS * p.fps).roundToInt()
            val pops = p.photos.filter { it.held }
            // Longer gaps leave room for fewer pauses.
            assertTrue(pops.size >= if (gapS < 1.0) 2 else 1)
            var movedBefore = false
            for (pop in pops) {
                val still = p.headDistance(pop.fromFrame)
                // The head has stopped for the whole gap before the photo, and stays for the gap after it.
                for (f in pop.fromFrame - gap until pop.toFrame + gap) assertEquals("$gapS s, frame $f", still, p.headDistance(f), 1e-9)
                if (p.headDistance(pop.fromFrame - gap - 2) < still) movedBefore = true
                // No photo shows in the gaps.
                assertTrue(p.frame(pop.fromFrame - 1).photos.none { it.pop.id == pop.id })
                assertTrue(p.frame(pop.toFrame).photos.none { it.pop.id == pop.id })
            }
            assertTrue(movedBefore)
            // The clip's length holds (Auto); the gaps come out of the journey's time.
            assertEquals(25.0, p.seconds, 1.0 / p.fps)
        }
        // At 0 the photo comes as the journey stops, as before.
        val none = build(cyclades, ClipDesign(photoGapS = 0.0))
        assertTrue(none.photos.filter { it.held }.any { none.headDistance(it.fromFrame - 2) < none.headDistance(it.fromFrame) })
        assertEquals(0.5, ClipDesign().photoGapS, 0.0)
    }

    @Test fun thePhotoPauseSliderSetsEachPause() {
        val counts = listOf(1.0, 3.0, 5.0, 8.0).map { s ->
            val p = build(cyclades, ClipDesign(photoHoldS = s))
            val pops = p.photos.filter { it.held }
            assertTrue("$s s", pops.isNotEmpty())
            for (pop in pops) assertEquals("$s s", (s * p.fps).roundToInt(), pop.toFrame - pop.fromFrame)
            pops.size
        }
        // Longer pauses fit fewer photos into the same length.
        assertTrue(counts.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(counts.last() < counts.first())
        // Within the slider's range; Off has no photos.
        assertEquals(Spots.HOLD_MIN_S, ClipDesign(photoHoldS = 0.2).holdSeconds, 0.0)
        assertEquals(ClipDesign.MAX_HOLD_S, ClipDesign(photoHoldS = 12.0).holdSeconds, 0.0)
        assertFalse(ClipDesign(photoMode = PhotoMode.Off).applyTo(ClipOptions()).photos)
        assertEquals(2.5, Spots.hold(2.3), 0.0)
        assertEquals(ClipDesign.MAX_HOLD_S, Spots.hold(9.0), 0.0)
    }

    // --- 3. The stops' own titles, on foot between them ---------------------------------------------------------------

    @Test fun aPlacesStopsShowTheirOwnTitlesUnderTheDayAndThePlace() {
        // Owner: "text should be able [to] take the sub items title also".
        val on = build(planned(), ClipOptions(stopTitles = true))
        val off = build(planned(), ClipOptions(stopTitles = false))
        val names = on.labels.map { it.label }
        assertTrue(ClipLabel("Day 1 · Lisbon", "Lisbon Cathedral") in names)
        assertTrue(ClipLabel("Day 1 · Lisbon", "São Jorge Castle") in names)
        // A long title is cut at its first "and".
        assertTrue(ClipLabel("Day 1 · Lisbon", "Tram 28") in names)
        // A travel item never shows its title.
        assertTrue(names.none { it.place?.startsWith("Fly") == true || it.place?.startsWith("Train") == true })
        // Each stop is its own scene, keyed by its plan item.
        assertEquals("label:item:castle", on.labels.single { it.label.place == "São Jorge Castle" }.key)
        // Off: one name for the place and day, as before.
        assertTrue(off.labels.size < on.labels.size)
        assertTrue(off.labels.none { it.label.place == "Lisbon Cathedral" })
        assertTrue(off.labels.any { it.label == ClipLabel("Day 1", "Lisbon") })
        // The day card still says the day and the place.
        val cards = build(planned(), ClipDesign(dayCards = true)).inserts.mapNotNull { it.card }
        assertTrue(cards.any { it.day == "Day 1" && it.place == "Lisbon" })
    }

    @Test fun legsBetweenTheItemsOfOnePlaceAreWalkedUnlessChosen() {
        // Owner: "assume each sub item by foot by default but you can also change that".
        assertTrue(TravelDefault.samePlace("Lisbon", "lisbon ", day, day, 800.0))
        assertFalse(TravelDefault.samePlace("Lisbon", "Lisbon", day, day.plusDays(1), 800.0))
        assertFalse(TravelDefault.samePlace("Lisbon", "Sintra", day, day, 800.0))
        assertFalse(TravelDefault.samePlace("Lisbon", "Lisbon", day, day, 9_000.0))
        assertFalse(TravelDefault.samePlace(null, "Lisbon", day, day, 800.0))
        assertEquals(Vehicle.Walk, TravelDefault.between("São Jorge Castle", samePlace = true))
        assertEquals(Vehicle.Car, TravelDefault.between("Pena Palace", samePlace = false))
        assertEquals(Vehicle.Train, TravelDefault.between("Train to Porto", samePlace = true))

        val input = planned()
        val legs = input.routeLegs
        val ids = legs.map { it.key.fromId to it.key.toId }
        val walked = legs.filter { it.samePlace }.map { it.key.toId }
        assertEquals(listOf("castle", "tram"), walked)
        // The plan's list and the video agree: on foot within Lisbon, by car to Sintra, by train to Porto.
        assertEquals(listOf(Vehicle.Car, Vehicle.Walk, Vehicle.Walk, Vehicle.Car, Vehicle.Train), legs.map { it.travel })
        val p = build(input)
        assertEquals(legs.map { it.travel }, p.legVehicle)
        // A chosen way wins.
        val chosen = trip.copy(travelModes = TravelModes.set(null, legs[1].key, Vehicle.Bike))
        val q = build(planned(chosen))
        assertEquals(Vehicle.Bike, q.legVehicle[1])
        assertEquals(Vehicle.Walk, q.legVehicle[2])
        assertEquals(5, ids.size)
    }

    // --- 8. Skip stop --------------------------------------------------------------------------------------------------

    @Test fun aSkippedStopIsLeftOutAndTheRouteGoesStraightToTheNext() {
        // Owner: "add ability to deactivate a stop from the map next to let go? and go directly to the next".
        val base = build(planned())
        val castle = base.labels.single { it.key == "label:item:castle" }
        assertEquals(listOf("castle"), castle.stops)
        // With music the names come in on the beat and still know their stops (what Skip stop leaves out).
        val beat = build(planned(), ClipOptions(beats = ClipBeats(BeatGrid(120.0, 0.25), 120.0)))
        assertEquals(listOf("castle"), beat.labels.single { it.key == castle.key }.stops)
        assertTrue(beat.labels.all { it.stops.isNotEmpty() })
        val p = build(planned(), ClipOptions(skipped = setOf("castle")))
        assertEquals(listOf("castle"), p.skippedStops.map { it.id })
        assertEquals("São Jorge Castle", p.skippedStops.single().title)
        assertTrue(p.labels.none { it.key == castle.key })
        assertEquals(base.xs.size - 1, p.xs.size)
        // The cathedral goes straight on to the tram, still on foot.
        assertTrue(p.labels.any { it.key == "label:item:tram" })
        assertEquals(Vehicle.Walk, p.legVehicle[1])
        // Put back: as it was.
        assertEquals(base.labels.map { it.key }, build(planned(), ClipOptions(skipped = emptySet())).labels.map { it.key })
        // A recorded route has no plan items to skip; a route needs two places left.
        assertTrue(build(cyclades, ClipOptions(skipped = setOf("s1"))).skippedStops.isEmpty())
        val most = items.map { it.id }.filter { it != "fly" }.toSet()
        assertTrue(build(planned(), ClipOptions(skipped = most)).skippedStops.isEmpty())
        // Kept with the trip's edits; undo and Put back take it out again.
        val e = ClipEdits().skipping(listOf("castle", "pena"), true)
        assertEquals(setOf("castle", "pena"), ClipEditsJson.decodeEdits(ClipEditsJson.encodeEdits(e)).skipped)
        assertEquals(setOf("pena"), e.skipping(listOf("castle"), false).skipped)
        assertEquals(setOf("castle", "pena"), e.resetScenes().skipped)
        assertTrue(ClipDesign().withEdits(e).applyTo(ClipOptions()).skipped == setOf("castle", "pena"))
        assertTrue(ClipDesign().withEdits(e).globals.skipped.isEmpty())
    }

    // --- 9. Album pages ------------------------------------------------------------------------------------------------

    @Test fun albumPagesShowSeveralPhotosOfAPlaceInOnePause() {
        // Owner: "for images add a layout option maybe I want like full screen or 2 pictures, see layout in photo album".
        val card = build(cyclades, ClipDesign(photoLayout = PhotoLayout.Card))
        val album = build(cyclades, ClipDesign(photoLayout = PhotoLayout.Album))
        val full = build(cyclades, ClipDesign(photoLayout = PhotoLayout.Full))
        assertTrue(card.photos.all { it.albumOf == 1 })
        // Full screen draws differently; the timing is the card's.
        assertEquals(card.frameCount, full.frameCount)
        assertEquals(card.photos.map { it.id to it.fromFrame }, full.photos.map { it.id to it.fromFrame })
        val pages = album.photos.filter { it.held }.groupBy { it.fromFrame }.values
        assertTrue(pages.any { it.size >= 2 })
        for (page in pages) {
            assertTrue(page.size <= PhotoLayout.ALBUM_MAX)
            assertEquals(1, page.map { it.toFrame }.distinct().size)
            assertTrue(page.all { it.albumOf == page.size })
            assertEquals((0 until page.size).toList(), page.map { it.album }.sorted())
        }
        // A page shows photos of one place, in the same pause as a card would.
        assertEquals(card.photos.count { it.held }, pages.size)
        assertEquals(card.frameCount, album.frameCount)
        val ids = album.photos.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    // --- 4, 5, 6. Text size, the thumb pad, photo size and frame --------------------------------------------------------

    @Test fun textSizeSpotsAndPhotoSizes() {
        val key = ClipDesign.labelKey("item:se")
        val d = ClipDesign(textSize = TextSize.Large, textScale = 1.2f, overrides = mapOf(key to SceneOverride(textScale = 0.8f)))
        assertEquals(1.25f * 1.2f, d.typeScale(), 1e-6f)
        assertEquals(1.25f * 1.2f * 0.8f, d.typeScale(key), 1e-6f)
        // A look sets its own size again; moving the slider makes the style custom.
        assertEquals(1f, ClipLook.Bold.apply(d).textScale, 0f)
        assertEquals(ClipLook.Bold, ClipLook.of(ClipLook.Bold.apply(ClipDesign())))
        assertNull(ClipLook.of(ClipLook.Bold.apply(ClipDesign()).copy(textScale = 1.1f)))

        // The names' spot: its own, else every name's; a name moved only up or down stays centred.
        assertNull(ClipDesign().nameSpot(key))
        assertEquals(0.5f to 0.9f, ClipDesign(overrides = mapOf(key to SceneOverride(y = 0.9f))).nameSpot(key))
        val all = ClipDesign(nameX = 0.2f, nameY = 1f)
        assertEquals(0.2f to 1f, all.nameSpot(key))
        assertEquals(0.2f to 0.4f, all.copy(overrides = mapOf(key to SceneOverride(y = 0.4f))).nameSpot(key))

        // The cards' spot and size: their own, else the clip's.
        val photo = ClipDesign.photoKey("a1")
        assertNull(ClipDesign().photoSpot("a1"))
        assertEquals(0.9f to 1f, ClipDesign(photoX = 0.9f, photoY = 1f).photoSpot("a1"))
        assertEquals(0.1f to 0.2f, ClipDesign(photoX = 0.9f, photoY = 1f, overrides = mapOf(photo to SceneOverride(x = 0.1f, y = 0.2f))).photoSpot("a1"))
        assertEquals(0.7f, ClipDesign(photoScale = 0.7f).photoScale("a1"), 0f)
        assertEquals(PhotoSize.L.scale, ClipDesign(photoScale = 0.7f, overrides = mapOf(photo to SceneOverride(size = PhotoSize.L))).photoScale("a1"), 0f)
        assertEquals(1.4f, ClipDesign(overrides = mapOf(photo to SceneOverride(size = PhotoSize.S, scale = 1.4f))).photoScale("a1"), 0f)

        // Kept with the trip's edits.
        val e = ClipEdits(mapOf(photo to SceneOverride(scale = 0.7f, x = 0.1f, y = 0.95f), key to SceneOverride(textScale = 1.4f, x = 0f, y = 1f)))
        assertEquals(e, ClipEditsJson.decodeEdits(ClipEditsJson.encodeEdits(e)))
        assertEquals(1f, Spots.share(1.2f), 0f)
        assertEquals(0.5f, Spots.scale(0.3f, Spots.PHOTO_SCALE), 0f)
    }

    @Test fun theNewChoicesAreRememberedAndOlderStylesReadAsBefore() {
        val d = ClipDesign(
            textScale = 1.3f, nameX = 0.2f, nameY = 0.9f, photoX = 0.8f, photoY = 1f, photoHoldS = 5.0, photoScale = 0.7f,
            photoFrame = PhotoFrame.None, photoGapS = 1.25, stopTitles = false, photoLayout = PhotoLayout.Album,
        )
        assertEquals(d, ClipPrefs.decode(ClipPrefs.encode(d)))
        assertEquals(ClipDesign(), ClipPrefs.decode(ClipPrefs.encode(ClipDesign())))
        // A style from 1.1.2: the look's size, names at the bottom, cards by row and side, 3 s pauses and the theme's border.
        val old = ClipPrefs.decode("opening=title;name=bottom;textSize=large;photoRow=top;photoSide=left;photos=auto;camera=steady")
        assertEquals(1f, old.textScale, 0f)
        assertEquals(TextSize.Large, old.textSize)
        assertNull(old.nameY)
        assertNull(old.photoX)
        assertEquals(3.0, old.holdSeconds, 0.0)
        assertEquals(PhotoFrame.Theme, old.photoFrame)
        assertEquals(0.5, old.photoGapS, 0.0)
        assertTrue(old.stopTitles)
        // Full screen since round 4 (owner, 2026-11-09: "by default keep the full screen").
        assertEquals(PhotoLayout.Full, old.photoLayout)
        // Bad values fall back.
        val bad = ClipPrefs.decode("textScale=9;nameY=2;photoHold=-1;photoScale=x;photoFrame=gold")
        assertEquals(ClipDesign(), bad)
    }
}

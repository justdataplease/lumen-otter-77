package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.model.PlanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The scene editor against real plans: overrides applied, timing, scene ids, the place names' town text. */
class ClipScenesTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val start = LocalDate.of(2026, 6, 16)

    /** Two days of walking around Parikia (a photo every few minutes), so the clip has two place names. */
    private fun walk(photos: Int): ClipInput {
        val t0 = start.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val t1 = start.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val points = (0..60).map { m -> GeoPoint(t0 + m * 60_000L, 37.0853 + m * 0.0004, 25.1488 + m * 0.0003, 8f) } +
            (0..60).map { m -> GeoPoint(t1 + m * 60_000L, 37.1097 + m * 0.0004, 25.1671 + m * 0.0003, 8f) }
        return ClipInput(
            title = "Paros", startDate = start, endDate = start.plusDays(1), zone = zone, points = points,
            stops = listOf(ClipStop(t0, 37.0853, 25.1488, "Parikia, Paros", "GR"), ClipStop(t1, 37.1097, 25.1671, "Naoussa, Paros", "GR")),
            photos = (0 until photos).map { i ->
                val day = if (i % 2 == 0) t0 else t1
                ClipPhoto("p$i", day + (5 + i * 50 / photos.coerceAtLeast(1)) * 60_000L, null, null)
            },
        )
    }

    /** At the trip's own pace ([speed]; no chosen length), where photo pauses add their time. */
    private fun plan(input: ClipInput, design: ClipDesign, speed: Int = 1, seconds: Int? = null) =
        requireNotNull(ClipPlan.build(input, design.applyTo(ClipOptions(length = null, speed = speed, seconds = seconds)), Locale.ENGLISH))

    // --- Overrides applied to a plan ------------------------------------------------------------------------------

    @Test fun hiddenNamesAndOwnTextReachTheFrames() {
        val base = plan(walk(0), ClipDesign())
        assertEquals(listOf("label:day:2026-06-16", "label:day:2026-06-17"), base.labels.map { it.key })
        val (first, second) = base.labels
        val d = ClipDesign().withEdits(
            ClipEdits(
                mapOf(
                    first.key to SceneOverride(hidden = true),
                    second.key to SceneOverride(text = "Naoussa harbour"),
                ),
            ),
        )
        // Hidden and custom text are drawing choices: the plan keeps its timing.
        val edited = plan(walk(0), d)
        assertTrue(base.sameTimeline(edited))
        for (f in first.from until first.to) {
            val s = edited.frame(f)
            assertEquals(first.key, s.labelKey)
            assertNull(d.label(s.labelKey, s.place!!))
        }
        for (f in second.from until second.to) {
            val s = edited.frame(f)
            assertEquals(ClipLabel("Day 2", "Naoussa harbour"), d.label(s.labelKey, s.place!!))
        }
        // The scene list still has the hidden name (so it can come back), marked not shown, with its own text otherwise.
        val scenes = ClipScenes.list(edited, d)
        assertFalse(scenes.first { it.key == first.key }.shown)
        assertEquals("Day 2 · Naoussa harbour", scenes.first { it.key == second.key }.title)
    }

    @Test fun aPhotoLeftOutIsNotShownAndOneAskedForIs() {
        // A long walk with many photos over the moving route: the clip has room for only some.
        val input = walk(40)
        val auto = plan(input, ClipDesign(photoMode = PhotoMode.On))
        val shown = auto.photos.map { it.id }
        val dropped = auto.photoScenes.first { !it.shown }.id
        assertTrue(shown.size in 2 until 40)
        val out = shown[1]
        val d = ClipDesign(photoMode = PhotoMode.On).withEdits(
            ClipEdits(mapOf(ClipDesign.photoKey(out) to SceneOverride(show = false), ClipDesign.photoKey(dropped) to SceneOverride(show = true))),
        )
        val edited = plan(input, d)
        val ids = edited.photos.map { it.id }
        assertFalse(out in ids)
        assertTrue(dropped in ids)
        // Both stay in the scene list, the one left out marked so.
        val scene = edited.photoScenes.first { it.id == out }
        assertTrue(scene.excluded && !scene.shown)
        assertTrue(ClipScenes.list(edited, d).any { it.photoId == out && !it.shown })
        // Over the moving route, photos never change the length.
        assertEquals(auto.frameCount, edited.frameCount)
    }

    @Test fun eachPhotosPauseFeedsTheTimingAndTheSpeedChips() {
        val input = walk(4)
        val d = ClipDesign(photoMode = PhotoMode.Pause2, photoGapS = 0.0).withEdits(
            ClipEdits(
                mapOf(
                    ClipDesign.photoKey("p0") to SceneOverride(hold = 5.0),
                    ClipDesign.photoKey("p1") to SceneOverride(hold = 0.0),
                    ClipDesign.photoKey("p2") to SceneOverride(hold = 1.0),
                ),
            ),
        )
        for (speed in ClipTiming.SPEEDS) {
            val p = plan(input, d, speed)
            val held = p.photos.filter { it.held }.associate { it.id to (it.toFrame - it.fromFrame) }
            // Pauses as set (p3 keeps the style's 2 s); p1 shows over the moving route instead.
            for ((id, frames) in held) assertEquals(id, mapOf("p0" to 150, "p2" to 30, "p3" to 60).getValue(id), frames)
            assertFalse("p1" in held)
            // The plan's length is what the chip says, and the pauses are all of the difference.
            val chip = ClipTiming.seconds(p.travelHours, speed, p.photoHolds)
            assertEquals(ClipTiming.clock(chip), ClipText.clock(p.frameCount, p.fps))
            val base = ClipTiming.frames(ClipTiming.baseSeconds(p.travelHours, speed), p.fps)
            assertEquals(base + held.values.sum(), p.frameCount)
            // The head stands still through each pause.
            for (pop in p.photos.filter { it.held }) {
                for (f in pop.fromFrame until pop.toFrame) assertEquals(p.headDistance(pop.fromFrame - 1), p.headDistance(f), 0.0)
            }
        }
        // Without edits, each photo's pause is the style's, and the free speed check agrees with the older count.
        val plain = plan(input, ClipDesign(photoMode = PhotoMode.Pause2, photoGapS = 0.0))
        assertEquals(List(4) { PhotoHold(2.0) }, plain.photoHolds)
        // The still map before and after each pause counts too (the chips show the length with it).
        assertEquals(List(4) { PhotoHold(3.0) }, plan(input, ClipDesign(photoMode = PhotoMode.Pause2)).photoHolds)
        for (hours in listOf(10.0, 23.0, 40.0)) {
            assertEquals(ClipTiming.freeSpeed(hours, 1, false, plain.photoCandidates, 2.0), ClipTiming.freeSpeed(hours, 1, false, plain.photoHolds))
        }
    }

    @Test fun theChosenPausesStayWithinTwoMinutesAndAskedOnesComeFirst() {
        val holds = List(30) { PhotoHold(5.0) }
        // One pause per 4 s of a 40 s clip, at most.
        assertEquals(10, ClipTiming.chooseHeld(holds, 40.0).size)
        // Asked-for photos come in even past that, as long as the whole stays within 120 s.
        val asked = holds.mapIndexed { i, h -> if (i < 12) h.copy(forced = true) else h }
        assertEquals((0 until 12).toList(), ClipTiming.chooseHeld(asked, 40.0))
        assertTrue(ClipTiming.chooseHeld(asked, 100.0).sumOf { asked[it].seconds } <= 20.0)
        // No pause, not counted; uniform pauses agree with the older count.
        assertTrue(ClipTiming.chooseHeld(List(5) { PhotoHold(0.0) }, 30.0).isEmpty())
        for (base in listOf(6.0, 30.0, 110.0)) for (n in listOf(1, 5, 40)) {
            assertEquals(ClipTiming.heldPhotos(n, base, 2.0), ClipTiming.chooseHeld(List(n) { PhotoHold(2.0) }, base).size)
        }
    }

    @Test fun photosFollowTheirPlacesOrder() {
        val input = walk(6)
        // 20 s: room for five of the six pauses, so the first day has several.
        val auto = plan(input, ClipDesign(photoMode = PhotoMode.Pause2), seconds = 20)
        val firstDay = auto.photoScenes.filter { it.placeKey == "label:day:2026-06-16" && it.shown }.map { it.id }
        assertTrue(firstDay.size >= 2)
        val wanted = firstDay.reversed()
        val d = ClipDesign(photoMode = PhotoMode.Pause2).withEdits(ClipEdits(order = mapOf("label:day:2026-06-16" to wanted)))
        val ordered = plan(input, d, seconds = 20)
        val slots = auto.photos.filter { it.id in firstDay }.map { it.fromFrame }
        assertEquals(wanted, ordered.photos.filter { it.id in firstDay }.sortedBy { it.fromFrame }.map { it.id })
        // Same pauses, same frames: the photos only swap slots.
        assertEquals(slots, ordered.photos.filter { it.id in firstDay }.map { it.fromFrame }.sorted())
        assertTrue(auto.sameTimeline(ordered))
        // The strip shows the place's photos in that order, right after its name (with those not in the clip).
        val scenes = ClipScenes.list(ordered, d)
        val at = scenes.indexOfFirst { it.key == "label:day:2026-06-16" }
        val strip = scenes.drop(at + 1).takeWhile { it.kind == SceneKind.Photo }
        assertEquals(wanted, strip.filter { it.shown }.map { it.photoId })
        assertEquals(auto.photoScenes.count { it.placeKey == "label:day:2026-06-16" }, strip.size)
        // Only the swapped cards' frames need drawing again.
        val dirty = requireNotNull(ClipScenes.dirtyFrames(auto, ordered, ClipDesign(photoMode = PhotoMode.Pause2), d))
        val swapped = auto.photos.filter { it.id in firstDay }
        assertTrue(swapped.all { p -> dirty.any { p.fromFrame in it } })
        assertTrue(dirty.sumOf { it.count() } < ordered.frameCount / 2)
    }

    // --- Scene list -----------------------------------------------------------------------------------------------

    @Test fun sceneIdsHoldAcrossReplansOfTheSameTrip() {
        val input = walk(6)
        val a = ClipScenes.list(plan(input, ClipDesign()), ClipDesign())
        val b = ClipScenes.list(plan(input, ClipDesign()), ClipDesign())
        assertEquals(a.map { it.key }, b.map { it.key })
        assertEquals(ClipDesign.OPENING, a.first().key)
        assertEquals(ClipDesign.END, a.last().key)
        assertEquals(a.size, a.map { it.key }.distinct().size)
        // Other speeds and photo pauses: the same names and photos, by the same keys.
        for (speed in ClipTiming.SPEEDS) for (mode in listOf(PhotoMode.On, PhotoMode.Pause3)) {
            val d = ClipDesign(photoMode = mode)
            val keys = ClipScenes.list(plan(input, d, speed), d).map { it.key }
            assertEquals(a.filter { it.kind == SceneKind.Label }.map { it.key }, keys.filter { it.startsWith(ClipDesign.LABEL) })
            assertEquals(a.filter { it.kind == SceneKind.Photo }.map { it.key }.toSet(), keys.filter { it.startsWith(ClipDesign.PHOTO) }.toSet())
        }
        // In time order, each scene's frame inside the clip.
        val p = plan(input, ClipDesign())
        for (s in ClipScenes.list(p, ClipDesign())) assertTrue(s.key, s.frame in 0 until p.frameCount)
    }

    @Test fun editsForScenesThatAreGoneAreHarmless() {
        val d = ClipDesign().withEdits(
            ClipEdits(
                mapOf("label:day:1999-01-01" to SceneOverride(hidden = true), ClipDesign.photoKey("gone") to SceneOverride(show = true, hold = 5.0)),
                mapOf("label:item:gone" to listOf("x", "y")),
            ),
        )
        val p = plan(walk(3), d)
        assertEquals(plan(walk(3), ClipDesign()).frameCount, p.frameCount)
        assertTrue(ClipScenes.list(p, d).none { it.key.contains("gone") || it.key.contains("1999") })
    }

    @Test fun onlyTheEditedScenesFramesAreDrawnAgain() {
        val d0 = ClipDesign()
        val p = plan(walk(4), d0)
        val label = p.labels[1]
        val d1 = d0.withEdits(ClipEdits(mapOf(label.key to SceneOverride(font = ClipFont.Hand))))
        assertEquals(listOf(label.from until label.to), ClipScenes.dirtyFrames(p, p, d0, d1))
        val opening = d0.withEdits(ClipEdits(mapOf(ClipDesign.OPENING to SceneOverride(text = "Hi"))))
        assertEquals(listOf(0 until p.drawStart), ClipScenes.dirtyFrames(p, p, d0, opening))
        val end = d0.withEdits(ClipEdits(mapOf(ClipDesign.END to SceneOverride(text = "Bye"))))
        assertEquals(listOf(p.endCardStart until p.frameCount), ClipScenes.dirtyFrames(p, p, d0, end))
        val pop = p.photos.first()
        val card = d0.withEdits(ClipEdits(mapOf(ClipDesign.photoKey(pop.id) to SceneOverride(tilt = 4f))))
        assertEquals(listOf(pop.fromFrame until pop.toFrame), ClipScenes.dirtyFrames(p, p, d0, card))
        assertEquals(emptyList<IntRange>(), ClipScenes.dirtyFrames(p, p, d0, d0))
        // A global style change, or another timeline: everything.
        assertNull(ClipScenes.dirtyFrames(p, p, d0, d0.copy(font = ClipFont.Tall)))
        assertNull(ClipScenes.dirtyFrames(p, plan(walk(4), d0, speed = 2), d0, d0))
        assertEquals(listOf(0..9, 12..20), ClipScenes.merge(listOf(12..15, 0..4, 5..9, 14..20, 3..2)))
    }

    // --- The place name's town text -------------------------------------------------------------------------------

    private val day = LocalDate.of(2035, 1, 10)
    private val trip = Trip("arg", "Patagonia", startDate = day, endDate = day.plusDays(4), createdAt = 1, updatedAt = 1)

    private fun item(id: String, title: String, lat: Double, lng: Double, d: Int, pos: Int, placeName: String = "", kind: PlanKind = PlanKind.Place) =
        PlanItem(id = id, tripId = trip.id, day = day.plusDays(d.toLong()), position = pos.toDouble(), title = title, placeName = placeName,
            kind = kind.id, lat = lat, lng = lng, createdAt = 1, updatedAt = 1)

    @Test fun aPlannedPlaceIsNamedByItsTownNotItsTitle() {
        val items = listOf(
            item("hotel", "Hotel in El Calafate", -50.338, -72.264, 0, 0, placeName = "Hotel Kau, El Calafate, Argentina"),
            item("bus", "Bus to El Chaltén", -49.331, -72.886, 1, 0, kind = PlanKind.Transport),
            item("capri", "Laguna Capri and Mirador Fitz Roy", -49.300, -72.930, 1, 1),
            item("torre", "Laguna Torre trail", -49.320, -72.950, 2, 0, placeName = "Laguna Torre, El Chaltén, Argentina"),
            item("lake", "Lago del Desierto", -48.990, -72.850, 3, 0),
        )
        val replay = PlannedReplay.build(trip, items)
        val places = replay.waypoints.associate { it.itemId to it.place }
        assertEquals("El Calafate", places["hotel"])
        // The bus's own destination, and the trail near it: the town the plan arrives in.
        assertEquals("El Chaltén", places["bus"])
        assertEquals("El Chaltén", places["capri"])
        assertEquals("El Chaltén", places["torre"])
        // 38 km out, still within a town's reach of the bus stop: El Chaltén.
        assertEquals("El Chaltén", places["lake"])
        // Nothing in the plan says more than the title: no town, so the title shows.
        val farItems = listOf(item("solo", "Cueva de las Manos", -47.15, -70.66, 0, 0), item("x", "Perito Moreno", -46.59, -70.93, 1, 0))
        assertTrue(PlannedReplay.build(trip, farItems).waypoints.all { it.place == null })

        val input = ClipSources.available(trip, emptyList(), emptyList(), items, emptyList(), ZoneId.of("America/Argentina/Rio_Gallegos"))
            .getValue(ClipSource.Planned)
        // With one name per place (the stops' own titles off; ClipRound3Test has them on).
        val p = requireNotNull(ClipPlan.build(input, ClipOptions(stopTitles = false), Locale.ENGLISH))
        val names = p.labels.map { it.label }
        assertTrue(names.none { it.place?.contains("Laguna") == true || it.place?.contains("Bus") == true })
        assertEquals(ClipLabel("Day 1", "El Calafate"), names.first())
        // The bus and the trail share day 2 in El Chaltén: one scene, keyed by the first of them.
        val day2 = p.labels.single { it.label == ClipLabel("Day 2", "El Chaltén") }
        assertEquals("label:item:bus", day2.key)
        assertNotNull(p.labels.firstOrNull { it.label == ClipLabel("Day 3", "El Chaltén") })
        val farPlan = requireNotNull(ClipPlan.build(
            ClipSources.available(trip, emptyList(), emptyList(), farItems, emptyList(), zone).getValue(ClipSource.Planned),
            ClipOptions(stopTitles = false), Locale.ENGLISH,
        ))
        assertEquals(listOf("Cueva de las Manos", "Perito Moreno"), farPlan.labels.map { it.label.place })
    }
}

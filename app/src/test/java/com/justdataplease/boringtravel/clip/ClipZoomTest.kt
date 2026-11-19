package com.justdataplease.boringtravel.clip

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
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The camera's zoom (owner: "can the zoom be more adjustable especiallye earth … a better zoom static to show the
 * teritories very near the building"): the closeness steps, each map's sharpest zoom, close-ups at stops within the
 * zoom speed limit, their pictures, and the stored choices.
 */
class ClipZoomTest {
    private val zone = ZoneId.of("Europe/Lisbon")
    private val start = LocalDate.of(2026, 6, 16)
    private fun at(h: Int, m: Int) = start.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val lisbon = listOf(
        38.7078 to -9.1366, 38.7105 to -9.1333, 38.7118 to -9.1302, 38.7131 to -9.1290, 38.7152 to -9.1290,
        38.7139 to -9.1334, 38.7139 to -9.1394, 38.7149 to -9.1406, 38.7110 to -9.1427, 38.7122 to -9.1458,
        38.7140 to -9.1460, 38.7107 to -9.1480, 38.7085 to -9.1440, 38.7078 to -9.1366,
    )

    /** One morning walking around Lisbon (≈ 7 km), points every 30 s, three stops with a photo at each. */
    private fun walk(): ClipInput {
        val points = ArrayList<GeoPoint>()
        var t = at(9, 0).toDouble()
        val msPerM = 3_600_000.0 / 4_500
        for (i in 0 until lisbon.size - 1) {
            val (a, b) = lisbon[i] to lisbon[i + 1]
            val d = Geo.distanceM(a.first, a.second, b.first, b.second)
            val steps = max(1, (d * msPerM / 30_000).toInt())
            for (s in 0 until steps) {
                val f = s.toDouble() / steps
                points += GeoPoint(t.toLong(), a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, 8f)
                t += d * msPerM / steps
            }
        }
        points += GeoPoint(t.toLong(), lisbon.last().first, lisbon.last().second, 8f)
        val stopsAt = listOf(3, 7, 10).map { points[points.size * it / 13] }
        return ClipInput(
            title = "Lisbon on foot", startDate = start, endDate = start, zone = zone, points = points,
            stops = stopsAt.mapIndexed { i, p -> ClipStop(p.time, p.lat, p.lng, "Stop $i, Lisbon", "PT", "s$i") },
            photos = stopsAt.mapIndexed { i, p -> ClipPhoto("p$i", p.time + 60_000, p.lat, p.lng) },
        )
    }

    private fun argentina(): ClipInput {
        val plan = SampleArgentina.plan(LocalDate.of(2026, 10, 25), 0L)
        return ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), SampleArgentina.ZONE).getValue(ClipSource.Planned)
    }

    /** Built once: the sample gives its plan items new ids each time, and the place name keys come from them. */
    private val kyoto: ClipInput by lazy {
        val plan = SampleKyoto.plan(LocalDate.of(2027, 10, 3), 0L)
        val input = ClipSources.available(plan.trip, emptyList(), emptyList(), plan.items, emptyList(), zone).getValue(ClipSource.Planned)
        // A photo at every fourth place, so some close-ups fall on photo pauses.
        input.copy(photos = input.stops.filterIndexed { i, _ -> i % 4 == 1 }.mapIndexed { i, s -> ClipPhoto("k$i", null, s.lat, s.lng) })
    }

    private val inputs by lazy { listOf(walk(), kyoto, argentina()) }

    private fun build(
        input: ClipInput, zoom: ClipZoom = ClipZoom.Normal, closeUp: CloseUp = CloseUp.Off, map: MapStyle = MapStyle.Street,
        seconds: Int? = null, shape: ClipShape = ClipShape.Story, view: ClipView = ClipView.Flat, edits: CameraEdits = CameraEdits(),
        photoHold: Double = 2.0, gap: Double = ClipDesign.PHOTO_GAP_S,
    ) = requireNotNull(ClipPlan.build(
        input,
        ClipOptions(seconds = seconds, shape = shape, view = view, map = map, zoom = zoom, closeUp = closeUp, cameraEdits = edits, photoHoldSeconds = photoHold, photoGapSeconds = gap),
        Locale.ENGLISH,
    ))

    /** Ground metres on the frame's short side at frame [f]. */
    private fun shortSideM(p: ClipPlan, f: Int): Double {
        val c = p.camera(f)
        return min(c.spanX, c.spanY(p.options.shape.aspect)) / Mercator.unitsPerMeter(c.cy)
    }

    private fun drawing(p: ClipPlan) = (p.drawStart + p.fps until p.drawEnd)
    private fun median(p: ClipPlan) = drawing(p).map { shortSideM(p, it) }.sorted().let { it[it.size / 2] }

    // --- Closeness ------------------------------------------------------------------------------------------------

    @Test fun normalIsTheCameraTheClipAlwaysHad() {
        for (input in inputs) {
            val plain = requireNotNull(ClipPlan.build(input, ClipOptions(photoHoldSeconds = 2.0), Locale.ENGLISH))
            val normal = build(input)
            assertEquals(plain.frameCount, normal.frameCount)
            for (f in 0 until plain.frameCount) assertEquals(plain.camera(f), normal.camera(f))
            assertEquals(plain.layerSpecs(), normal.layerSpecs())
            assertTrue(normal.stills.isEmpty())
        }
    }

    @Test fun eachStepCloserShowsLessGround() {
        for (input in inputs) {
            // The typical view (mean log width over the drawing): never wider for a closer step, and clearly closer
            // from Far on (a short walk already fits Far's floor in the whole-route view).
            val typical = ClipZoom.entries.map { z -> build(input, z).let { p -> drawing(p).map { ln(shortSideM(p, it)) }.average() } }
            for (i in 1 until typical.size) {
                val z = ClipZoom.entries[i]
                assertTrue("${input.title}: $z ${typical[i]} vs ${typical[i - 1]}", typical[i] <= typical[i - 1] + 1e-6)
                if (z >= ClipZoom.Normal) assertTrue("${input.title}: $z ${typical[i]} vs ${typical[i - 1]}", typical[i] < typical[i - 1] - 0.05)
            }
        }
        // On a walk the floors decide: Very close is down among the buildings, never past its floor.
        val p = build(walk(), ClipZoom.VeryClose)
        val closest = drawing(p).minOf { shortSideM(p, it) }
        assertTrue("closest $closest m", closest >= ClipZoom.VeryClose.floorM * 0.99 && closest <= 400)
        // Far never comes closer than its floor, or the whole route when that is smaller (a short walk).
        val far = build(walk(), ClipZoom.Far)
        val whole = far.routeView.let { c -> min(c.spanX, c.spanY(far.options.shape.aspect)) / Mercator.unitsPerMeter(c.cy) }
        assertTrue(drawing(far).minOf { shortSideM(far, it) } >= min(ClipZoom.Far.floorM, whole) * 0.99)
    }

    @Test fun wholeRouteHoldsTheWholeRouteThroughout() {
        for (input in inputs) {
            val p = build(input, ClipZoom.Route)
            // From the opening's dive to the pull-back: the whole route, still.
            for (f in p.openingEnd until p.drawEnd) {
                val c = p.camera(f)
                assertEquals("${input.title} frame $f", 1.0, c.spanX / p.routeView.spanX, 1e-6)
                assertEquals(p.routeView.cx, c.cx, p.routeView.spanX * 1e-6)
                assertEquals(p.routeView.cy, c.cy, p.routeView.spanX * 1e-6)
            }
            // The opening and the close look at the region around it.
            assertEquals(p.overview, p.camera(0))
            assertTrue(p.overview.spanX > p.routeView.spanX * 2)
            assertEquals(p.overview.spanX, p.camera(p.frameCount - 1).spanX, p.overview.spanX * 1e-9)
        }
    }

    @Test fun everyZoomKeepsTheHeadInViewAndTheSpeedLimit() {
        val limit = ClipPlan.MAX_ZOOM_RATE + 1e-9
        for (input in inputs) for (zoom in ClipZoom.entries) for (closeUp in listOf(CloseUp.Off, CloseUp.VeryClose)) {
            for (shape in listOf(ClipShape.Story, ClipShape.Wide)) {
                val p = build(input, zoom, closeUp, shape = shape)
                for (f in 0 until p.frameCount) {
                    val st = p.frame(f)
                    val r = st.camera.rect(shape.aspect)
                    val fx = (st.headX - r.x0) / r.w
                    val fy = (st.headY - r.y0) / r.h
                    assertTrue("${input.title} $zoom $closeUp $shape frame $f head at $fx,$fy", fx in 0.05..0.95 && fy in 0.05..0.95)
                    // The opening's dive and the closing pull-back have their own easing; the journey keeps the limit.
                    if (f <= p.openingEnd || f > p.drawEnd) continue
                    val a = p.camera(f - 1)
                    val b = st.camera
                    assertTrue("${input.title} $zoom $closeUp $shape frame $f zoom", abs(ln(b.spanX / a.spanX)) <= limit)
                    val span = min(a.spanX, b.spanX)
                    val move = max(abs(b.cx - a.cx) / span, abs(b.cy - a.cy) / (span / shape.aspect))
                    assertTrue("${input.title} $zoom $closeUp $shape frame $f centre moved $move", move <= 0.08)
                }
            }
        }
    }

    @Test fun aPlaceCanHaveItsOwnZoom() {
        val base = build(kyoto)
        val label = base.labels[base.labels.size / 2]
        val p = build(kyoto, edits = CameraEdits(zoom = mapOf(label.key to ClipZoom.VeryClose)))
        val inside = (label.from + (label.to - label.from) / 3 until label.to - (label.to - label.from) / 3)
        assertTrue(inside.minOf { shortSideM(p, it) } < inside.minOf { shortSideM(base, it) } * 0.5)
        // Far from it, the camera is as before.
        val other = base.labels.first()
        assertTrue(other.to < label.from - base.fps * 5)
        for (f in other.from until other.to) assertEquals(base.camera(f), p.camera(f))
        // A place on screen for less than a second still gets its own zoom (its stretch grows to ZOOM_MIN_S).
        val short = base.labels.filter { it.from > base.drawStart + base.fps * 3 && it.to < base.drawEnd }.minBy { it.to - it.from }
        assertTrue(short.to - short.from < base.fps)
        val q = build(kyoto, edits = CameraEdits(zoom = mapOf(short.key to ClipZoom.VeryClose)))
        val middle = (short.from + short.to) / 2
        assertTrue(shortSideM(q, middle) < shortSideM(base, middle) * 0.8)
    }

    // --- Each map's limit -----------------------------------------------------------------------------------------

    @Test fun noMapGoesPastItsSharpestZoom() {
        for (map in MapStyle.entries) for (input in listOf(walk(), kyoto)) for (shape in listOf(ClipShape.Story, ClipShape.Wide)) {
            val p = build(input, ClipZoom.VeryClose, CloseUp.VeryClose, map = map, shape = shape)
            val closest = ZoomLimits.shortSpan(map.maxZoom)
            for (f in 0 until p.frameCount) {
                val c = p.camera(f)
                assertTrue("$map ${input.title} $shape frame $f", min(c.spanX, c.spanY(shape.aspect)) >= closest * (1 - 1e-9))
            }
        }
        // Earth stops at its pictures (about 2.7 km across in Lisbon), the street maps go on to the buildings.
        val earth = build(walk(), ClipZoom.VeryClose, map = MapStyle.Earth)
        val street = build(walk(), ClipZoom.VeryClose)
        val terrain = build(walk(), ClipZoom.VeryClose, map = MapStyle.Terrain)
        val earthClosest = drawing(earth).minOf { shortSideM(earth, it) }
        assertEquals(ZoomLimits.closestM(MapStyle.Earth, Mercator.y(38.71)), earthClosest, earthClosest * 0.02)
        assertTrue(drawing(street).minOf { shortSideM(street, it) } * 5 < earthClosest)
        assertTrue(drawing(terrain).minOf { shortSideM(terrain, it) } * 5 < earthClosest)
    }

    @Test fun theLimitsAreSaidPlainly() {
        val equator = Mercator.y(0.0)
        val lisbonY = Mercator.y(38.71)
        assertEquals(3_440.0, ZoomLimits.closestM(MapStyle.Earth, equator), 20.0)
        assertEquals(215.0, ZoomLimits.closestM(MapStyle.Street, equator), 2.0)
        // Earth already holds back the usual camera; the street maps reach every step and close-up.
        assertTrue(ZoomLimits.limits(MapStyle.Earth, ClipZoom.Normal.floorM, lisbonY))
        assertFalse(ZoomLimits.limits(MapStyle.Earth, ClipZoom.Far.floorM, lisbonY))
        for (map in listOf(MapStyle.Street, MapStyle.Terrain)) for (y in listOf(equator, lisbonY, Mercator.y(-50.0))) {
            for (floor in ClipZoom.entries.map { it.floorM } + CloseUp.entries.map { it.floorM }) assertFalse("$map $floor", ZoomLimits.limits(map, floor, y))
        }
        assertFalse(ZoomLimits.limits(MapStyle.Earth, ClipZoom.Route.floorM, lisbonY))
        assertFalse(ZoomLimits.limits(MapStyle.Earth, CloseUp.Off.floorM, lisbonY))
        assertEquals(
            "Earth's pictures blur closer than about 2.7 km across. Street, Terrain and Globe go closer.",
            ZoomLimits.note(MapStyle.Earth, ClipZoom.Close.floorM, lisbonY, Locale.ENGLISH),
        )
        assertNull(ZoomLimits.note(MapStyle.Street, ClipZoom.VeryClose.floorM, lisbonY, Locale.ENGLISH))
        assertNull(ZoomLimits.note(MapStyle.Earth, ClipZoom.Far.floorM, lisbonY, Locale.ENGLISH))
    }

    // --- Close-ups ------------------------------------------------------------------------------------------------

    @Test fun closeUpsKeepTheClipsLengthAndHoldAStillCloseView() {
        for (input in inputs) for (closeUp in listOf(CloseUp.Close, CloseUp.VeryClose)) {
            val off = build(input)
            val p = build(input, closeUp = closeUp)
            assertEquals("${input.title}: the length stays", off.frameCount, p.frameCount)
            assertEquals(off.drawEnd, p.drawEnd)
            assertTrue("${input.title}: close-ups", p.stills.isNotEmpty())
            assertTrue(p.stills.size <= ClipPlan.MAX_CLOSE_UPS)
            for (s in p.stills) {
                assertTrue(s.fromFrame >= p.drawStart && s.toFrame <= p.drawEnd)
                // The head waits at the place.
                for (f in s.fromFrame until s.toFrame) assertEquals(s.x, p.frame(f).headX, 1e-12)
                // The camera reaches the close view and holds it still, centred on the place (it settles onto the place
                // as it moves in).
                fun off(f: Int) = p.camera(f).let { c -> hypot(c.cx - s.x, c.cy - s.y) / c.spanX }
                val held = (s.fromFrame until s.toFrame).filter { p.camera(it).spanX <= s.span * 1.002 }
                assertTrue("${input.title} $closeUp still at ${s.fromFrame}: ${held.size} frames close", held.size >= p.fps / 2)
                assertTrue("${input.title} $closeUp still at ${s.fromFrame}: not still", held.count { off(it) <= 0.002 } >= p.fps / 3)
                for (f in held) assertTrue("${input.title} frame $f off centre ${off(f)}", off(f) <= 0.02)
                for (f in (s.fromFrame until s.toFrame).filter { p.camera(it).spanX <= s.span * 1.1 }) {
                    assertTrue("${input.title} frame $f settling ${off(f)}", off(f) <= 0.08)
                }
            }
            // The journey moves through the rest: what the close-ups take stays within their share.
            val waiting = p.stills.sumOf { it.toFrame - it.fromFrame } - p.photos.filter { it.held }.sumOf { it.toFrame - it.fromFrame }
            assertTrue(waiting <= (off.drawEnd - off.drawStart) * ClipPlan.CLOSE_UP_SHARE + 1)
        }
    }

    @Test fun aCloseUpAtAPhotoPauseHoldsDuringThePause() {
        // The walk's three stops each have a photo: with room for all three, each pause sits inside its close-up.
        val p = build(walk(), closeUp = CloseUp.Close, gap = 0.0)
        val pauses = p.photos.filter { it.held }
        assertEquals(3, pauses.size)
        // With the still map around each pause there is room for fewer, still inside their close-ups.
        val gapped = build(walk(), closeUp = CloseUp.Close)
        for (pop in gapped.photos.filter { it.held }) {
            assertNotNull("photo ${pop.id} in a close-up", gapped.stills.firstOrNull { pop.fromFrame >= it.fromFrame && pop.toFrame <= it.toFrame })
        }
        for (pop in pauses) {
            val still = p.stills.firstOrNull { pop.fromFrame >= it.fromFrame && pop.toFrame <= it.toFrame }
            assertNotNull("photo ${pop.id} in a close-up", still)
        }
        // Closer close-ups take longer; the ones that fit still wrap their pause, none cuts one.
        for (closeUp in listOf(CloseUp.VeryClose)) for (input in inputs) {
            val q = build(input, closeUp = closeUp)
            for (pop in q.photos.filter { it.held }) for (s in q.stills) {
                val overlaps = pop.fromFrame < s.toFrame && s.fromFrame < pop.toFrame
                if (overlaps) assertTrue("${input.title} ${pop.id}", pop.fromFrame >= s.fromFrame && pop.toFrame <= s.toFrame)
            }
        }
    }

    @Test fun aPlaceCanAskForACloseUpOrGoWithout() {
        val base = build(kyoto)
        val label = base.labels.first { it.to - it.from > base.fps }
        val asked = build(kyoto, edits = CameraEdits(closeUp = mapOf(label.key to CloseUp.Close)))
        assertTrue(asked.stills.isNotEmpty())
        val range = asked.labels.first { it.key == label.key }
        for (s in asked.stills) assertTrue("still at ${s.fromFrame} in ${range.from}..${range.to}", s.fromFrame >= range.from - 1 && s.fromFrame < range.to)
        // On for the clip, off for that place.
        val all = build(kyoto, closeUp = CloseUp.Close)
        val without = build(kyoto, closeUp = CloseUp.Close, edits = CameraEdits(closeUp = all.labels.associate { it.key to CloseUp.Off }))
        assertTrue(without.stills.isEmpty())
    }

    @Test fun closeUpsGetTheirOwnSharpPictures() {
        for (input in inputs) for (view in ClipView.entries) {
            val p = build(input, closeUp = CloseUp.VeryClose, view = view)
            val specs = p.layerSpecs()
            assertTrue(specs.size <= ClipPlan.MAX_LAYERS + 2 * p.stills.size)
            assertTrue(specs.sumOf { it.pixels } <= ClipPlan.LAYER_BUDGET_PX + ClipPlan.STILL_BUDGET_PX)
            assertTrue(specs.drop(1).zipWithNext().all { (x, y) -> x.density <= y.density })
            val q = p.sharpness(specs)
            for (s in p.stills) {
                val closest = (s.fromFrame until s.toFrame).minOf { p.camera(it).spanX }
                for (f in s.fromFrame until s.toFrame) if (p.camera(f).spanX <= closest * 1.02) {
                    assertTrue("${input.title} $view frame $f sharpness ${q[f]}", q[f] >= 0.85)
                }
            }
        }
    }

    @Test fun theSameChoicesAlwaysMakeTheSameCamera() {
        for (input in inputs) {
            val a = build(input, ClipZoom.Close, CloseUp.VeryClose, view = ClipView.Perspective)
            val b = build(input, ClipZoom.Close, CloseUp.VeryClose, view = ClipView.Perspective)
            for (f in 0 until a.frameCount) assertEquals(a.camera(f), b.camera(f))
            assertEquals(a.layerSpecs(), b.layerSpecs())
            assertEquals(a.geometryKey, b.geometryKey)
            assertFalse(a.geometryKey == build(input).geometryKey)
        }
    }

    // --- Stored choices -------------------------------------------------------------------------------------------

    @Test fun theZoomIsRememberedWithTheStyle() {
        for (z in ClipZoom.entries) assertEquals(z, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(zoom = z))).zoom)
        for (c in CloseUp.entries) assertEquals(c, ClipPrefs.decode(ClipPrefs.encode(ClipDesign(closeUp = c))).closeUp)
        // A style saved before the zoom existed reads as the camera it always had.
        val old = "opening=title;name=top;font=theme;back=panel;panel=rounded;color=light;line=solid;weight=medium;lineColor=theme;photoRow=bottom;photoSide=both;photos=pause2"
        // The side it kept (Both) stays until the one-time move to the new defaults (ClipPrefs.photoDefaults).
        assertEquals(ClipDesign(photoSide = PhotoSide.Both), ClipPrefs.decode(old))
        assertEquals(ClipDesign(), ClipPrefs.decode("zoom=closest;closeUp=maybe"))
        // The design hands both to the plan.
        val o = ClipDesign(zoom = ClipZoom.Far, closeUp = CloseUp.Close).applyTo(ClipOptions())
        assertEquals(ClipZoom.Far, o.zoom)
        assertEquals(CloseUp.Close, o.closeUp)
    }

    @Test fun aPlacesZoomIsStepsFromTheClipsAndOldEditFilesStillLoad() {
        val d = ClipDesign(
            zoom = ClipZoom.Close,
            overrides = mapOf(
                "label:item:a" to SceneOverride(zoom = 1, closeUp = CloseUp.Off),
                "label:item:b" to SceneOverride(zoom = -9),
                "photo:m1" to SceneOverride(zoom = 2),
            ),
        )
        assertEquals(ClipZoom.VeryClose, d.zoom("label:item:a"))
        assertEquals(ClipZoom.Route, d.zoom("label:item:b"))
        assertEquals(ClipZoom.Close, d.zoom("label:item:c"))
        assertEquals(CloseUp.Off, d.closeUp("label:item:a"))
        // Only place names steer the camera; the steps follow the clip's zoom when it changes.
        assertEquals(CameraEdits(mapOf("label:item:a" to ClipZoom.VeryClose, "label:item:b" to ClipZoom.Route), mapOf("label:item:a" to CloseUp.Off)), d.cameraEdits())
        assertEquals(ClipZoom.Normal, d.copy(zoom = ClipZoom.Far).zoom("label:item:a"))

        // New keys survive the round trip.
        val edits = ClipEdits(scenes = mapOf("label:item:a" to SceneOverride(zoom = -2, closeUp = CloseUp.VeryClose, text = "Gion")))
        assertEquals(mapOf("Planned" to edits), ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Planned" to edits))))
        // A file from before the zoom reads as it did; bad zoom values are left out.
        val old = """{"v":1,"sources":{"Planned":{"scenes":{"label:item:a":{"text":"Gion","font":"serif"}},"order":{}}}}"""
        assertEquals(SceneOverride(text = "Gion", font = ClipFont.Serif), ClipEditsJson.decode(old).getValue("Planned").scene("label:item:a"))
        val bad = """{"v":1,"sources":{"Planned":{"scenes":{"label:item:a":{"text":"x","zoom":2.5,"closeUp":"near"},"label:item:b":{"zoom":"close"},"label:item:c":{"zoom":9},"label:item:d":{"zoom":0}}}}}"""
        val read = ClipEditsJson.decode(bad).getValue("Planned")
        assertEquals(SceneOverride(text = "x"), read.scene("label:item:a"))
        assertFalse(read.scenes.containsKey("label:item:b"))
        assertFalse(read.scenes.containsKey("label:item:c"))
        assertFalse(read.scenes.containsKey("label:item:d"))
    }

    /** Not a check: prints how the steps and close-ups come out on the demo trips (see PLAN.md, F. Trip clip). */
    @Test fun printTheNumbers() {
        val out = StringBuilder()
        for (input in inputs) for (map in listOf(MapStyle.Street, MapStyle.Earth)) {
            val line = ClipZoom.entries.joinToString("  ") { z -> "%s %.1f km".format(z.label, median(build(input, z, map = map)) / 1000) }
            out.appendLine("${input.title} $map median short side: $line")
            for (c in listOf(CloseUp.Close, CloseUp.VeryClose)) {
                val p = build(input, closeUp = c, map = map)
                val specs = p.layerSpecs()
                out.appendLine("  $c: ${p.stills.size} close-ups, " + p.stills.joinToString(", ") { s ->
                    "%.1f s down to %.2f km".format((s.toFrame - s.fromFrame).toDouble() / p.fps,
                        (s.fromFrame until s.toFrame).minOf { shortSideM(p, it) } / 1000)
                } + "; pictures ${specs.size} (%.1f Mpx)".format(specs.sumOf { it.pixels } / 1e6))
            }
        }
        println(out)
    }


}

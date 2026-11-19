package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.traveller.RideChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Every vehicle's rides: stored strings, today's look as the default, the paper models, and the per-video choice. */
class RidesTest {
    private val colours: List<Cloth?> = listOf<Cloth?>(null) + Cloth.entries

    // --- Stored as a string -------------------------------------------------------------------------------------

    @Test fun everyRideSurvivesItsString() {
        RideModel.entries.forEachIndexed { i, model ->
            val ride = Ride(model, colours[i % colours.size], colours[(i * 5 + 3) % colours.size])
            val rides = Rides.DEFAULT.with(ride)
            assertEquals(ride, rides[model.vehicle])
            assertEquals(rides, Rides.decode(rides.encode()))
            assertEquals(rides, Rides.decodeOrNull(rides.encode()))
        }
        val all = Rides(
            Ride(RideModel.Camper, Cloth.Teal, Cloth.White), Ride(RideModel.Motorbike, Cloth.Black), Ride(RideModel.Tram, trim = Cloth.Mustard),
            Ride(RideModel.Sailboat, Cloth.Navy, Cloth.Coral), Ride(RideModel.Helicopter, Cloth.Red, Cloth.Grey),
        )
        assertEquals(all, Rides.decode(all.encode()))
        assertEquals(Rides.DEFAULT, Rides.decode(Rides.DEFAULT.encode()))
    }

    @Test fun missingOrUnknownValuesFallBackOneByOne() {
        assertEquals(Rides.DEFAULT, Rides.decode(null))
        assertEquals(Rides.DEFAULT, Rides.decode(""))
        assertEquals(Rides.DEFAULT, Rides.decode("nonsense"))
        // Another vehicle's model, or one this app doesn't know: the default model; known colours still read.
        val r = Rides.decode("car=model:helicopter,body:red;plane=model:jetpack,body:chrome,trim:white;ferry=model:sailboat")
        assertEquals(Ride(RideModel.Hatchback, Cloth.Red), r.car)
        assertEquals(Ride(RideModel.Airliner, trim = Cloth.White), r.plane)
        assertEquals(Ride(RideModel.Sailboat), r.boat)
        assertEquals(Rides.DEFAULT.bike, r.bike)
        // A newer app's extra keys, vehicles and values are ignored; "theme" is the theme's colour.
        val newer = Rides.decode("car=model:suv,wheels:big,body:theme;rocket=model:x;train=model:tram,trim:pink")
        assertEquals(Ride(RideModel.Suv), newer.car)
        assertEquals(Ride(RideModel.Tram, trim = Cloth.Pink), newer.train)
        assertEquals(Ride(RideModel.Steam), Ride.decode("model:steam,body:theme,trim:theme", Ride(RideModel.Steam, Cloth.Red)))
        // A per-video choice that isn't one: none.
        assertNull(Rides.decodeOrNull(null))
        assertNull(Rides.decodeOrNull("  "))
        assertNull(Rides.decodeOrNull("model:suv"))
        assertNull(Rides.decodeOrNull("car=" + "x".repeat(3000)))
    }

    @Test fun idsAreUniqueAndStable() {
        // Stored in settings, backups and every trip's edits: never rename an id.
        assertEquals(
            listOf("hatch", "suv", "camper", "convertible", "bicycle", "scooter", "motorbike", "steam", "highspeed", "regional", "tram",
                "ferry", "sailboat", "speedboat", "cruise", "airliner", "prop", "seaplane", "helicopter"),
            RideModel.entries.map { it.id },
        )
        assertEquals(listOf("car", "bike", "train", "ferry", "plane"), Rides.VEHICLES.map { it.id })
        for (v in Rides.VEHICLES) assertTrue("$v has 3 or 4 models", RideModel.of(v).size in 3..4)
        assertTrue(RideModel.of(Vehicle.Walk).isEmpty())
        assertNull(Ride.default(Vehicle.Auto))
        assertNull(Rides.DEFAULT[Vehicle.Walk])
        assertEquals(Rides.DEFAULT, Rides.DEFAULT.with(Ride(RideModel.Suv)).with(Ride(RideModel.Hatchback)))
    }

    // --- Today's look -------------------------------------------------------------------------------------------

    @Test fun theDefaultIsTodaysLook() {
        val todays = mapOf(
            Vehicle.Car to RideModel.Hatchback, Vehicle.Bike to RideModel.Bicycle, Vehicle.Train to RideModel.Steam,
            Vehicle.Ferry to RideModel.Ferry, Vehicle.Plane to RideModel.Airliner,
        )
        for ((v, model) in todays) {
            val ride = requireNotNull(Rides.DEFAULT[v])
            assertEquals(Ride(model), ride)
            assertTrue(ride.isDefault)
            assertSame("$v draws today's model", PaperVehicleMeshes.get(v), PaperVehicleMeshes.get(ride.model))
        }
        // In the theme's colours every face keeps its material's colour; Walk and Auto have no ride.
        val materials = PaperVehicle3D.Palette.materials(PaperVehicle3D.Palette.of(BtLooks.of(Look.PostcardPop)))
        for (model in RideModel.entries) for (face in PaperVehicleMeshes.get(model).faces) {
            assertEquals(materials[face.material.ordinal], faceColor(face, materials, 0, 0))
        }
    }

    @Test fun bodyAndTrimColourTheirOwnPartsOnly() {
        val materials = PaperVehicle3D.Palette.materials(PaperVehicle3D.Palette.of(BtLooks.of(Look.Trail)))
        val body = Cloth.Teal.argb
        val trim = Cloth.Mustard.argb
        for (model in RideModel.entries) {
            val faces = PaperVehicleMeshes.get(model).faces
            assertTrue("$model has Body faces", faces.count { it.paint == PaperPaint.BODY } >= 6)
            assertTrue("$model has Trim faces", faces.count { it.paint == PaperPaint.TRIM } >= 1)
            for (face in faces) {
                val c = faceColor(face, materials, body, trim)
                when (face.paint) {
                    PaperPaint.BODY -> assertEquals(body, c)
                    PaperPaint.TRIM -> assertEquals(trim, c)
                    else -> assertEquals(materials[face.material.ordinal], c)
                }
                // Glass, tyres and the wake always stay the theme's.
                if (face.material in setOf(PaperMaterial.Glass, PaperMaterial.Rubber, PaperMaterial.Foam)) assertEquals("$model ${face.material}", PaperPaint.NONE, face.paint)
            }
        }
        // The "Theme" swatches show what the parts are in the theme.
        val palette = PaperVehicle3D.Palette.of(BtLooks.of(Look.Trail))
        val m = PaperVehicle3D.Palette.materials(palette)
        assertEquals(m[PaperMaterial.Ivory.ordinal], RidePaint.themeColor(RideModel.Hatchback, PaperPaint.BODY, palette))
        assertEquals(m[PaperMaterial.Terracotta.ordinal], RidePaint.themeColor(RideModel.Hatchback, PaperPaint.TRIM, palette))
        assertEquals(m[PaperMaterial.Navy.ordinal], RidePaint.themeColor(RideModel.Ferry, PaperPaint.BODY, palette))
    }

    // --- The paper models ---------------------------------------------------------------------------------------

    @Test fun everyModelBuildsFiniteBoundedGeometry() {
        val camera = PaperVehicleProjection()
        for (model in RideModel.entries) {
            val mesh = PaperVehicleMeshes.get(model)
            assertSame(mesh, PaperVehicleMeshes.get(model))
            assertTrue("$model faces ${mesh.faces.size}", mesh.faces.size in 20..900)
            assertTrue("$model vertices", mesh.vertices.size in 40..4000)
            assertTrue(mesh.vertices.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.z >= -.001f })
            assertTrue(mesh.radius.isFinite() && mesh.radius > 0f)
            mesh.faces.forEachIndexed { index, face ->
                assertTrue(face.indices.size in 3..16)
                assertTrue(face.indices.all { it in mesh.vertices.indices })
                assertEquals(face.indices.size, face.indices.toSet().size)
                assertTrue("$model detail lies on an earlier face", face.over in -1 until index)
                // The camera reads which way a face looks from its first corner: it must turn the outline's way.
                val p = points(mesh, face)
                val u = DoubleArray(3) { p[1][it] - p[0][it] }; val w = DoubleArray(3) { p[2][it] - p[0][it] }
                val corner = doubleArrayOf(u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0])
                assertTrue("$model face $index starts on a corner", dot(corner, newell(p)) > 1e-9)
            }
            for (tilt in listOf(30f, 44f, 65f)) for (heading in 0 until 360 step 30) for (seconds in listOf(0f, .37f, 13.1f)) {
                camera.project(mesh, heading.toFloat(), tilt, seconds)
                assertTrue("$model/$heading visible", camera.visibleFaces > 0)
                for (i in 0 until mesh.vertices.size) {
                    for (axis in 0..2) assertTrue(camera.coordinates[i * 3 + axis].isFinite())
                    assertTrue("$model x", abs(camera.coordinates[i * 3]) <= .55f)
                    assertTrue("$model y", abs(camera.coordinates[i * 3 + 1]) <= .55f)
                }
                for (i in 1 until camera.visibleFaces) assertTrue(camera.faceDepth[camera.faceOrder[i - 1]] <= camera.faceDepth[camera.faceOrder[i]])
            }
        }
    }

    /**
     * Every closed part (box, rod, loft, slab) has each face wound outward: just in front of a face is outside its
     * part (winding number 0), just behind it inside (1). Single faces (glass, sails, the wake) are left out.
     */
    @Test fun everyClosedPartIsWoundOutward() {
        for (model in RideModel.entries) {
            val mesh = PaperVehicleMeshes.get(model)
            assertTrue("$model has closed parts", mesh.solids.size >= 5)
            var checked = 0
            for (part in mesh.solids) {
                val triangles = part.flatMap { f -> fan(mesh, mesh.faces[f]) }
                for (f in part) {
                    // The face's biggest triangle: its centre lies on the surface even where a quad is a little twisted.
                    val t = fan(mesh, mesh.faces[f]).maxByOrNull { area(it) } ?: continue
                    val n = newell(t.toList())
                    val length = sqrt(dot(n, n))
                    if (length < 1e-6) continue
                    val unit = DoubleArray(3) { n[it] / length }
                    val c = DoubleArray(3) { k -> t.sumOf { it[k] } / 3 }
                    val eps = 2e-3
                    val front = winding(DoubleArray(3) { c[it] + unit[it] * eps }, triangles)
                    val back = winding(DoubleArray(3) { c[it] - unit[it] * eps }, triangles)
                    assertTrue("$model part ${part.first} face $f in front: $front", abs(front) < .25)
                    assertTrue("$model part ${part.first} face $f behind: $back", abs(back - 1) < .25)
                    checked++
                }
            }
            assertTrue("$model faces checked", checked >= mesh.solids.size * 3)
        }
    }

    @Test fun onlyTheRotorTurnsAndAWholeMinuteComesBackRound() {
        val heli = PaperVehicleMeshes.get(RideModel.Helicopter)
        val camera = PaperVehicleProjection()
        camera.project(heli, 35f, 40f, 0f); val rest = camera.coordinates.copyOf()
        camera.project(heli, 35f, 40f, .3f)
        var turned = 0
        for (i in heli.vertices.indices) {
            val moved = (0..2).any { abs(rest[i * 3 + it] - camera.coordinates[i * 3 + it]) > 1e-5f }
            if (heli.bones[heli.vertices[i].bone].spin == 0f) assertFalse("only the rotor moves", moved) else if (moved) turned++
        }
        assertTrue(turned > 20)
        assertEquals(0f, (60f * RideMeshes.ROTOR_TURNS) % 1f, 0f)
        // Nothing else turns or swings.
        for (model in RideModel.entries - RideModel.Helicopter) {
            val mesh = PaperVehicleMeshes.get(model)
            assertTrue(mesh.bones.all { it.spin == 0f && it.swing == 0f })
        }
    }

    @Test fun modelsOfOneVehicleLookApart() {
        for (v in Rides.VEHICLES) {
            val sizes = RideModel.of(v).map { m ->
                PaperVehicleMeshes.get(m).let { mesh ->
                    fun span(f: (PaperVertex) -> Float) = Math.round((mesh.vertices.maxOf(f) - mesh.vertices.minOf(f)) * 20)
                    listOf(mesh.faces.size, span { it.x }, span { it.y }, span { it.z })
                }
            }
            assertEquals("$v models differ", sizes.size, sizes.toSet().size)
        }
    }

    // --- Kept per video -----------------------------------------------------------------------------------------

    private val mine = Rides.DEFAULT.with(Ride(RideModel.Suv, Cloth.Olive)).with(Ride(RideModel.Sailboat, trim = Cloth.Red))

    @Test fun aVideosRidesAreKeptWithItsEdits() {
        val edits = ClipEdits(rides = mine)
        assertFalse(edits.isEmpty)
        val back = ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Planned" to edits)))
        assertEquals(mapOf("Planned" to edits), back)
        assertTrue(ClipEditsJson.encode(mapOf("Planned" to edits)).contains("\"rides\""))
        // Older files have none (Me's apply); a broken one is ignored, the rest still read.
        val old = """{"v":1,"sources":{"Planned":{"scenes":{"opening":{"text":"Hi"}},"order":{}}}}"""
        assertNull(ClipEditsJson.decode(old)["Planned"]?.rides)
        val broken = """{"v":1,"sources":{"Planned":{"scenes":{"opening":{"text":"Hi"}},"rides":42}}}"""
        assertEquals(ClipEdits(scenes = mapOf(ClipDesign.OPENING to SceneOverride(text = "Hi"))), ClipEditsJson.decode(broken)["Planned"])
        // Resetting the scenes keeps the rides, like the traveller.
        assertEquals(mine, edits.with(ClipDesign.OPENING, SceneOverride(text = "Hi")).resetScenes().rides)
    }

    @Test fun theVideosOwnWinsOverMesAndIsAnEditNotAGlobal() {
        val me = Rides.DEFAULT.with(Ride(RideModel.Helicopter))
        val d = ClipDesign(ridesDefault = me)
        assertEquals(me, d.ridesShown)
        val own = d.withEdits(ClipEdits(rides = mine))
        assertEquals(mine, own.ridesShown)
        assertEquals(mine, own.edits.rides)
        assertEquals(ClipDesign(), own.globals)
        assertFalse(ClipPrefs.encode(own).contains("rides"))
        assertEquals(Rides.DEFAULT, ClipPrefs.decode(ClipPrefs.encode(own)).ridesShown)
    }

    @Test fun otherRidesDrawEveryFrameAgain() {
        val zone = ZoneId.of("Europe/Athens")
        val start = LocalDate.of(2026, 6, 16)
        val t0 = start.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val points = (0..60).map { m -> GeoPoint(t0 + m * 60_000L, 37.0853 + m * 0.0004, 25.1488 + m * 0.0003, 8f) }
        val input = ClipInput("Paros", start, start, zone, points, listOf(ClipStop(t0, 37.0853, 25.1488, "Parikia", "GR")), emptyList())
        val d0 = ClipDesign()
        val p = requireNotNull(ClipPlan.build(input, d0.applyTo(ClipOptions()), Locale.ENGLISH))
        assertEquals(emptyList<IntRange>(), ClipScenes.dirtyFrames(p, p, d0, d0))
        assertNull(ClipScenes.dirtyFrames(p, p, d0, d0.withEdits(ClipEdits(rides = mine))))
        assertNull(ClipScenes.dirtyFrames(p, p, d0, d0.copy(ridesDefault = mine)))
        val own = d0.withEdits(ClipEdits(rides = mine))
        assertEquals(emptyList<IntRange>(), ClipScenes.dirtyFrames(p, p, own, own.copy(ridesDefault = Rides.DEFAULT.with(Ride(RideModel.Tram)))))
    }

    // --- The editors' rows --------------------------------------------------------------------------------------

    @Test fun rowsPickModelsAndColoursAndKeepTheRest() {
        val ride = Ride(RideModel.Suv, Cloth.Red, Cloth.White)
        val rows = RideChoices.rows(ride, 1, 2)
        assertEquals(listOf("model", "body", "trim"), rows.map { it.key })
        val model = rows[0]
        assertEquals(RideModel.of(Vehicle.Car).map { it.label }, model.choices.map { it.label })
        assertEquals(1, model.selected)
        // Another model keeps the colours picked.
        assertEquals(Ride(RideModel.Camper, Cloth.Red, Cloth.White), model.pick(ride, 2))
        assertEquals(ride, model.pick(ride, 99))
        val body = rows[1]
        assertEquals("Theme", body.choices[0].label)
        assertEquals(1, body.choices[0].argb)
        assertEquals(2, rows[2].choices[0].argb)
        assertEquals(Ride(RideModel.Suv, null, Cloth.White), body.pick(ride, 0))
        assertEquals(Cloth.Red, RideChoices.colours[body.selected])
        assertEquals("Boat", RideChoices.label(Vehicle.Ferry))
        assertNull(RideChoices.summary(Rides.DEFAULT))
        assertEquals("SUV, Sailboat", RideChoices.summary(mine))
        assertEquals("Hatchback", RideChoices.summary(Rides.DEFAULT.with(Ride(RideModel.Hatchback, Cloth.Red))))
    }

    // --- Geometry helpers ---------------------------------------------------------------------------------------

    private fun points(mesh: PaperMesh, face: PaperFace): List<DoubleArray> =
        face.indices.map { mesh.vertices[it].let { v -> doubleArrayOf(v.x.toDouble(), v.y.toDouble(), v.z.toDouble()) } }

    private fun fan(mesh: PaperMesh, face: PaperFace): List<Array<DoubleArray>> {
        val p = points(mesh, face)
        return (1 until p.size - 1).map { arrayOf(p[0], p[it], p[it + 1]) }
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun area(t: Array<DoubleArray>): Double = newell(t.toList()).let { sqrt(dot(it, it)) }

    private fun newell(p: List<DoubleArray>): DoubleArray {
        val n = DoubleArray(3)
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            n[0] += (a[1] - b[1]) * (a[2] + b[2]); n[1] += (a[2] - b[2]) * (a[0] + b[0]); n[2] += (a[0] - b[0]) * (a[1] + b[1])
        }
        return n
    }

    /** The winding number of [q] in a closed surface (the solid angle of its outward-wound triangles over 4π). */
    private fun winding(q: DoubleArray, triangles: List<Array<DoubleArray>>): Double {
        var total = 0.0
        for (t in triangles) {
            val a = DoubleArray(3) { t[0][it] - q[it] }; val b = DoubleArray(3) { t[1][it] - q[it] }; val c = DoubleArray(3) { t[2][it] - q[it] }
            val la = sqrt(dot(a, a)); val lb = sqrt(dot(b, b)); val lc = sqrt(dot(c, c))
            val cross = doubleArrayOf(b[1] * c[2] - b[2] * c[1], b[2] * c[0] - b[0] * c[2], b[0] * c[1] - b[1] * c[0])
            total += 2 * atan2(dot(a, cross), la * lb * lc + dot(a, b) * lc + dot(a, c) * lb + dot(b, c) * la)
        }
        return total / (4 * PI)
    }

    @Test fun windingHelperKnowsInsideFromOutside() {
        // A unit box from the builder: its centre is inside, a point beyond it outside.
        val b = PaperVehicleMeshes.Builder().apply { box(0f, 0f, .5f, 1f, 1f, 1f, PaperMaterial.Ivory) }.build()
        val triangles = b.faces.flatMap { fan(b, it) }
        assertEquals(1.0, winding(doubleArrayOf(0.0, 0.0, .5), triangles), 1e-6)
        assertEquals(0.0, winding(doubleArrayOf(2.0, 0.0, .5), triangles), 1e-6)
        assertNotEquals(0, b.solids.size)
    }
}

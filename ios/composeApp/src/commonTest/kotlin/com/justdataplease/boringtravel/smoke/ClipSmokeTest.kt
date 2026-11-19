package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.clip.ClipDesign
import com.justdataplease.boringtravel.clip.ClipEdits
import com.justdataplease.boringtravel.clip.ClipEditsJson
import com.justdataplease.boringtravel.clip.ClipLook
import com.justdataplease.boringtravel.clip.ClipOptions
import com.justdataplease.boringtravel.clip.ClipPlan
import com.justdataplease.boringtravel.clip.ClipPrefs
import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.ClipSource
import com.justdataplease.boringtravel.clip.ClipSources
import com.justdataplease.boringtravel.clip.ClipText
import com.justdataplease.boringtravel.clip.ClipTiming
import com.justdataplease.boringtravel.clip.MapStyle
import com.justdataplease.boringtravel.clip.PaperVehicle3D
import com.justdataplease.boringtravel.clip.Rides
import com.justdataplease.boringtravel.clip.SceneOverride
import com.justdataplease.boringtravel.clip.Traveller
import com.justdataplease.boringtravel.clip.music.MusicCatalog
import com.justdataplease.boringtravel.compat.AccessOrderMap
import com.justdataplease.boringtravel.compat.JavaRandom
import com.justdataplease.boringtravel.compat.PrefsText
import com.justdataplease.boringtravel.compat.SortedIntSet
import com.justdataplease.boringtravel.compat.TreeMap
import com.justdataplease.boringtravel.compat.ZoneId
import com.justdataplease.boringtravel.compat.graphics.Bitmap
import com.justdataplease.boringtravel.compat.graphics.BitmapShader
import com.justdataplease.boringtravel.compat.graphics.Canvas
import com.justdataplease.boringtravel.compat.graphics.ColorMatrix
import com.justdataplease.boringtravel.compat.graphics.ColorMatrixColorFilter
import com.justdataplease.boringtravel.compat.graphics.Matrix
import com.justdataplease.boringtravel.compat.graphics.Paint
import com.justdataplease.boringtravel.compat.graphics.Shader
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.ios.ClipEditStore
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.Look
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The trip video's read-in code where Kotlin/Native could differ from the JVM (round 18): a planned route made into a
 * clip (the planner's maths, its dates and zones), the clip's texts (Java's number formats written out, a regular
 * expression with Unicode classes for the file's name), the remembered choices and the scene edits in Android's own
 * forms, the compat stand-ins for Java's Random, TreeMap and access-ordered map, and android.graphics on Skia (a
 * shader with its local matrix, a colour matrix, a mesh, a layer, pixels read back, a JPEG) with a paper vehicle drawn.
 */
class ClipSmokeTest {
    private val start = localDate(2027, 6, 17)

    private fun item(id: String, day: Int?, title: String, lat: Double, lng: Double) = PlanItem(
        id = id, tripId = "t", day = day?.let { start.plusDays((it - 1).toLong()) }, position = 1.0, kind = "place", title = title,
        note = "", lat = lat, lng = lng, done = false, createdAt = 0L, updatedAt = 0L,
    )

    private fun cyclades() = ClipSources.available(
        Trip("t", "Cyclades, slowly", "", start, start.plusDays(4), trackingLevel = "off", createdAt = 0L, updatedAt = 0L),
        emptyList(), emptyList(),
        listOf(
            item("a", 1, "Piraeus", 37.942, 23.646), item("b", 2, "Naxos", 37.105, 25.376),
            item("c", 3, "Paros", 37.085, 25.150), item("d", 5, "Santorini", 36.393, 25.461),
        ),
        emptyList(), ZoneId.of("UTC"),
    )

    @Test fun aPlannedRouteBecomesAClip() {
        val input = assertNotNull(cyclades()[ClipSource.Planned])
        val plan = assertNotNull(ClipPlan.build(input, ClipDesign().applyTo(ClipOptions(map = MapStyle.Street))))
        assertEquals(ClipShape.Story, plan.options.shape)
        assertTrue(plan.seconds in 15.0..60.0, "length ${plan.seconds}")
        assertEquals(4, plan.labels.size)
        assertEquals("Cyclades, slowly", plan.title)
        assertTrue(plan.endCard.stats.endsWith("km approx."), plan.endCard.stats)
        assertEquals("17 Jun – 21 Jun 2027".substringAfterLast(' '), plan.endCard.dates?.substringAfterLast(' '))
        // Every frame's state reads, the stats count up, and the camera stays finite.
        var lastKm = 0.0
        for (f in 0 until plan.frameCount step 15) {
            val s = plan.frame(f)
            assertTrue(s.camera.spanX.isFinite() && s.camera.spanX > 0)
            val km = plan.statsAt(f).km
            assertTrue(km >= lastKm - 1e-9)
            lastKm = km
        }
        assertTrue(plan.layerSpecs().isNotEmpty())
    }

    @Test fun theClipsTextsAreWrittenAsJavaWritesThem() {
        assertEquals("0:34", ClipTiming.clock(34.4))
        assertEquals("1:05", ClipTiming.clock(65.0))
        assertEquals("8.4 km", ClipText.km(8.42, com.justdataplease.boringtravel.compat.Locale.ENGLISH))
        assertEquals("1,234 km", ClipText.km(1234.4, com.justdataplease.boringtravel.compat.Locale.ENGLISH))
        assertEquals("Cyclades-slowly-clip.mp4", ClipText.fileName("Cyclades, slowly"))
        assertEquals("Νάξος-Πάρος-clip.mp4", ClipText.fileName("Νάξος & Πάρος!"))
        assertEquals("trip-clip.mp4", ClipText.fileName("!!!"))
        assertEquals("Naxos", ClipText.place("Halki, Naxos"))
        assertEquals("clip-edits/trip_1_.json", ClipEditStore.path("trip/1?"))
        assertTrue(MusicCatalog.startFor("latin_groove", null) >= 0.0)
    }

    @Test fun theRememberedChoicesAndEditsReadBack() {
        val film = ClipLook.Film.apply(ClipDesign())
        val decoded = ClipPrefs.decode(ClipPrefs.encode(film))
        assertEquals(film.bars, decoded.bars)
        assertEquals(film.grain, decoded.grain)
        assertEquals(film.font, decoded.font)
        val edits = ClipEdits(scenes = mapOf("label:item:b" to SceneOverride(text = "Νάξος", y = 0.25f)))
        val back = ClipEditsJson.decode(ClipEditsJson.encode(mapOf("Planned" to edits)))
        assertEquals("Νάξος", back["Planned"]?.scenes?.get("label:item:b")?.text)
        val prefs = PrefsText.encode(mapOf("shape" to "square", "speed" to 2, "on" to true))
        assertEquals(mapOf<String, Any>("on" to true, "shape" to "square", "speed" to 2), PrefsText.decode(prefs))
        assertEquals(Traveller.DEFAULT, Traveller.decode(Traveller.DEFAULT.encode()))
        assertEquals(Rides.DEFAULT, Rides.decode(Rides.DEFAULT.encode()))
    }

    @Test fun javasCollectionsAndRandomAsJavaHasThem() {
        // java.util.Random(7): the film grain's seed (the same numbers as the JVM's).
        val r = JavaRandom(7L)
        assertEquals(-1156638823, r.nextInt())
        val g = JavaRandom(7L).nextGaussian()
        assertTrue(abs(g - 0.8452060657049847) < 1e-12, "gaussian $g")
        val holds = TreeMap<Int, String>().apply { put(30, "c"); put(-1, "a"); put(5, "b") }
        assertEquals(listOf(-1, 5, 30), holds.keys.toList())
        assertEquals(listOf("a", "b", "c"), holds.values.toList())
        val keep = SortedIntSet(0, 9).apply { this += 4; this += 9 }
        assertEquals(listOf(0, 4, 9), keep.toList())
        val lru = object : AccessOrderMap<String, Int>(4, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 2
        }
        lru["a"] = 1; lru["b"] = 2; lru["a"]; lru["c"] = 3
        assertEquals(null, lru["b"])
        assertEquals(1, lru["a"])
    }

    @Test fun androidGraphicsDrawsOnSkia() {
        val out = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFFFFFFF.toInt())
        // A red picture through a shader moved by its local matrix, into the right half only.
        val red = Bitmap.createBitmap(IntArray(16) { 0xFFFF0000.toInt() }, 4, 4, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shader = BitmapShader(red, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply { setScale(8f, 16f); postTranslate(32f, 0f) })
        paint.shader = shader
        c.drawRect(32f, 0f, 64f, 64f, paint)
        assertEquals(0xFFFF0000.toInt(), out.getPixel(48, 32))
        assertEquals(0xFFFFFFFF.toInt(), out.getPixel(10, 32))
        // A grey colour matrix on a blue square, half see-through in a layer.
        val grey = Paint().apply { color = 0xFF0000FF.toInt(); colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        val layer = c.saveLayerAlpha(null, 255)
        c.drawRect(0f, 0f, 16f, 16f, grey)
        c.restoreToCount(layer)
        val p = out.getPixel(8, 8)
        assertTrue(abs(((p shr 16) and 0xFF) - (p and 0xFF)) <= 2, "grey ${p.toUInt().toString(16)}")
        // A mesh with its texture.
        val mesh = Paint().apply { this.shader = BitmapShader(red, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        c.drawVertices(Canvas.VertexMode.TRIANGLES, 6, floatArrayOf(0f, 40f, 20f, 40f, 0f, 60f), 0, floatArrayOf(0f, 0f, 4f, 0f, 0f, 4f), 0, null, 0, null, 0, 0, mesh)
        assertEquals(0xFFFF0000.toInt(), out.getPixel(4, 44))
        assertTrue((out.jpeg(85)?.size ?: 0) > 100)
        out.recycle()
        red.recycle()
    }

    @Test fun aPaperVehicleIsDrawn() {
        val out = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val drawn = PaperVehicle3D().draw(Canvas(out), Vehicle.Car, 48f, 48f, 80f, 35f, 40f, 0f, PaperVehicle3D.Palette.of(BtLooks.of(Look.PostcardPop)))
        assertTrue(drawn)
        val pixels = IntArray(96 * 96)
        out.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        assertTrue(pixels.count { (it ushr 24) > 200 } > 300, "the car covers some of the picture")
        out.recycle()
    }
}

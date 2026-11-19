package com.justdataplease.boringtravel.art

import com.justdataplease.boringtravel.ui.components.scenePalette
import com.justdataplease.boringtravel.ui.theme.Look
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The port of the "Drawn pictures" board, checked shape by shape against its scenes.json (test resource). */
class SceneShapesTest {
    private val spec: JSONObject = JSONObject(
        requireNotNull(javaClass.classLoader?.getResource("art/scenes.json")) { "art/scenes.json missing" }.readText(),
    )

    @Test fun palettesMatchTheBoard() {
        val palettes = spec.getJSONObject("palettes")
        for (p in ScenePalettes.all) {
            val o = palettes.getJSONObject(p.key)
            assertEquals(o.getJSONArray("sky").strings(), p.sky)
            assertEquals(o.getJSONArray("b").strings(), p.buildings)
            assertEquals(o.getJSONArray("dark").strings(), p.dark)
            val singles = mapOf(
                "sun" to p.sun, "far" to p.far, "near" to p.near, "snow" to p.snow, "glow" to p.glow, "water" to p.water,
                "water2" to p.water2, "grass" to p.grass, "grass2" to p.grass2, "ground" to p.ground, "win" to p.win,
                "ink" to p.ink, "lit" to p.lit, "sand" to p.sand,
            )
            singles.forEach { (k, v) -> assertEquals("${p.key}.$k", o.getString(k), v) }
        }
        assertEquals(palettes.length(), ScenePalettes.all.size)
    }

    @Test fun everySceneInEveryThemeMatchesTheBoardShapeByShape() {
        val scenes = spec.getJSONObject("scenes")
        for (p in ScenePalettes.all) {
            val theme = scenes.getJSONObject(p.key)
            assertEquals(theme.length(), SceneKind.entries.size)
            for (kind in SceneKind.entries) {
                val expected = theme.getJSONArray(kind.id)
                val actual = SceneShapes.of(kind, p)
                assertEquals("${p.key}.${kind.id} count", expected.length(), actual.size)
                for (i in actual.indices) assertShape("${p.key}.${kind.id}[$i]", expected.getJSONArray(i), actual[i])
            }
        }
    }

    @Test fun theSketchIsPenOnPaper() {
        val p = ScenePalettes.sketch
        val shapes = SceneShapes.painted(SceneKind.Mountain, p)
        assertEquals(SceneShapes.PAPER, SceneShapes.background(p))
        assertEquals(null, SceneShapes.background(ScenePalettes.pop))
        // The sky bands are gone; everything else is outlined in ink, 1.5 wide; lines keep their own width.
        assertEquals(SceneShapes.of(SceneKind.Mountain, p).size - 3, shapes.size)
        assertTrue(shapes.none { it is Shape.Rect && it.x == 0f && it.y == 0f && it.w == 240f })
        assertTrue(shapes.all { SceneShapes.strokeOf(it, p) == (p.ink to 1.5f) })
        val lake = SceneShapes.painted(SceneKind.Lake, p).filterIsInstance<Shape.Line>()
        assertEquals(p.ink to 2.5f, SceneShapes.strokeOf(lake.first(), p))
        // The night scene's blocks are paper, not ink.
        assertTrue(SceneShapes.of(SceneKind.Night, p).filterIsInstance<Shape.Rect>().any { it.fill == SceneShapes.PAPER })
    }

    @Test fun colourThemesOutlineOnlyWhatTheBoardOutlines() {
        val p = ScenePalettes.pop
        val city = SceneShapes.painted(SceneKind.City, p)
        assertEquals(SceneShapes.of(SceneKind.City, p), city)
        assertEquals(null, SceneShapes.strokeOf(city.first(), p))
        assertEquals(p.ink to 1f, SceneShapes.strokeOf(city.first { it.stroke != null }, p))
    }

    @Test fun svgPathsBecomeLinesAndCurves() {
        val ops = SvgPath.parse("M0 118 C50 98 100 132 160 112 S220 104 240 110 V160 H0Z")
        assertEquals(
            listOf(
                SvgPath.Op.Move(0f, 118f),
                SvgPath.Op.Cubic(50f, 98f, 100f, 132f, 160f, 112f),
                // S mirrors the last control point (100,132) about (160,112).
                SvgPath.Op.Cubic(220f, 92f, 220f, 104f, 240f, 110f),
                SvgPath.Op.LineTo(240f, 160f),
                SvgPath.Op.LineTo(0f, 160f),
                SvgPath.Op.Close,
            ),
            ops,
        )
    }

    @Test fun picturesFillAnyBoxCentredAndCropped() {
        val wide = SceneMath.slice(480f, 160f)
        assertEquals(2f, wide.scale, 1e-6f)
        assertEquals(0f, wide.dx, 1e-6f)
        assertEquals(-80f, wide.dy, 1e-6f)
        val square = SceneMath.slice(100f, 100f)
        assertEquals(100f / 160f, square.scale, 1e-6f)
        assertEquals((100f - 240f * square.scale) / 2f, square.dx, 1e-4f)
        assertEquals(0f, square.dy, 1e-4f)
        // The whole box is always covered.
        for ((w, h) in listOf(54f to 50f, 360f to 190f, 72f to 72f, 1080f to 1350f)) {
            val f = SceneMath.slice(w, h)
            assertTrue(f.dx <= 1e-3f && f.dy <= 1e-3f)
            assertTrue(f.dx + 240f * f.scale >= w - 1e-3f && f.dy + 160f * f.scale >= h - 1e-3f)
        }
    }

    @Test fun writtenPicturesHaveTheirShape() {
        assertEquals(SceneMath.Size(1200, 800), SceneMath.pixelSize(SceneMath.Shape.Wide))
        assertEquals(SceneMath.Size(1200, 1200), SceneMath.pixelSize(SceneMath.Shape.Square))
        assertEquals(SceneMath.Size(960, 1200), SceneMath.pixelSize(SceneMath.Shape.Tall))
        assertEquals(SceneMath.Size(600, 400), SceneMath.pixelSize(SceneMath.Shape.Wide, 600))
        assertEquals(DemoArt.Picture(SceneKind.Lake, SceneMath.Shape.Tall).size, SceneMath.Size(960, 1200))
    }

    @Test fun everyThemeHasItsPalette() {
        // The themes' ids are the board's palette keys; Postcard Pop is the default and the demo photos' colours.
        assertEquals(Look.entries.map { it.id }.toSet(), ScenePalettes.all.map { it.key }.toSet())
        Look.entries.forEach { assertEquals(it.id, it.scenePalette().key) }
        assertEquals(ScenePalettes.pop, Look.Default.scenePalette())
        assertEquals(ScenePalettes.pop, ScenePalettes.default)
        assertEquals(ScenePalettes.market, ScenePalettes.of("market"))
        assertEquals(ScenePalettes.pop, ScenePalettes.of("notebook"))
        assertFalse(ScenePalettes.pop.sketch)
        assertTrue(ScenePalettes.sketch.sketch)
    }

    @Test fun demoFilesSayWhatTheyShow() {
        val id = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b"
        for (kind in SceneKind.entries) for (mirror in listOf(true, false)) {
            val path = DemoArt.localPath(DemoArt.Picture(kind, SceneMath.Shape.Tall, mirror), id)
            assertTrue(path.startsWith("media/demo-") && path.endsWith("$id.jpg"))
            assertEquals(DemoArt.Live(kind, mirror), DemoArt.live(path))
        }
        assertEquals("media/demo-lake-m-$id.jpg", DemoArt.localPath(DemoArt.Picture(SceneKind.Lake, mirror = true), id))
        // Real photos and the first demo's names are shown as files.
        assertEquals(null, DemoArt.live("media/$id.jpg"))
        assertEquals(null, DemoArt.live("media/demo-$id.jpg"))
        assertEquals(null, DemoArt.live("media/demo-deadbeef-m-$id.jpg"))
        assertEquals(null, DemoArt.live("media/demo-lake-m-$id.png"))
        assertEquals(null, DemoArt.live(null))
    }

    // ---- helpers ----

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun assertShape(at: String, e: JSONArray, actual: Shape) {
        val type = e.getString(0)
        val a = e.getJSONObject(1)
        fun num(k: String) = a.getDouble(k).toFloat()
        fun close(k: String, v: Float) = assertEquals("$at.$k", num(k), v, 1e-3f)
        when (type) {
            "rect" -> {
                val r = actual as? Shape.Rect
                assertNotNull("$at is $type", r); r!!
                close("x", r.x); close("y", r.y); close("w", r.w); close("h", r.h)
                assertEquals("$at.fill", a.getString("fill"), r.fill)
                assertStroke(at, a, r)
            }
            "circle" -> {
                val c = actual as? Shape.Circle
                assertNotNull("$at is $type", c); c!!
                close("cx", c.cx); close("cy", c.cy); close("r", c.r)
                assertEquals("$at.fill", a.getString("fill"), c.fill)
                assertStroke(at, a, c)
            }
            "poly" -> {
                val p = actual as? Shape.Poly
                assertNotNull("$at is $type", p); p!!
                val pts = a.getJSONArray("pts")
                assertEquals("$at.pts", pts.length(), p.pts.size)
                for (i in 0 until pts.length()) {
                    assertEquals("$at.pts[$i].x", pts.getJSONArray(i).getDouble(0).toFloat(), p.pts[i].first, 1e-3f)
                    assertEquals("$at.pts[$i].y", pts.getJSONArray(i).getDouble(1).toFloat(), p.pts[i].second, 1e-3f)
                }
                assertEquals("$at.fill", a.getString("fill"), p.fill)
                assertStroke(at, a, p)
            }
            "path" -> {
                val p = actual as? Shape.PathD
                assertNotNull("$at is $type", p); p!!
                assertEquals("$at.d", a.getString("d"), p.d)
                assertEquals("$at.fill", a.getString("fill"), p.fill)
                assertStroke(at, a, p)
            }
            "line" -> {
                val l = actual as? Shape.Line
                assertNotNull("$at is $type", l); l!!
                close("x1", l.x1); close("y1", l.y1); close("x2", l.x2); close("y2", l.y2); close("sw", l.sw)
                assertEquals("$at.stroke", a.getString("stroke"), l.stroke)
            }
            else -> throw AssertionError("$at: unknown shape $type")
        }
    }

    private fun assertStroke(at: String, a: JSONObject, s: Shape) {
        assertEquals("$at.stroke", a.optString("stroke").ifEmpty { null }, s.stroke)
        if (a.has("sw")) assertEquals("$at.sw", a.getDouble("sw").toFloat(), s.sw, 1e-3f)
    }
}

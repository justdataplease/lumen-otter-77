package com.justdataplease.boringtravel.art

import androidx.compose.ui.graphics.toArgb
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.planeMark
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneMarkTest {

    @Test
    fun everyThemeShowsTheSamePlaneOnItsOwnYellow() {
        val yellows = mapOf(
            Look.PostcardPop to 0xFFFFC93C, Look.Trail to 0xFFF6C445, Look.StickerBook to 0xFFFFE066,
            Look.Sketchbook to 0xFFFFE45C, Look.Market to 0xFFF2C14E,
        )
        assertEquals(Look.entries.toSet(), yellows.keys)
        for ((look, yellow) in yellows) {
            val c = BtLooks.of(look).colors
            val mark = c.planeMark()
            assertEquals(look.name, yellow.toInt(), mark.sky)
            assertEquals(look.name, c.accent.toArgb(), mark.sky)
            assertEquals(look.name, PlaneMark.Colors(yellow.toInt()), mark)
        }
        assertEquals(PlaneMark.POP_YELLOW, BtLooks.pop.colors.planeMark().sky)
        assertEquals(PlaneMark.Colors.Pop, BtLooks.pop.colors.planeMark())
    }

    @Test
    fun theNavyEdgesAndTheWhiteSheetReadOnEveryYellow() {
        for (look in Look.entries) {
            val m = BtLooks.of(look).colors.planeMark()
            assertTrue("${look.name}: navy on yellow", contrast(m.ink, m.sky) >= 7.0)
            assertTrue("${look.name}: navy round the white sheet", contrast(m.ink, m.body) >= 7.0)
            assertTrue("${look.name}: navy round the pale wing", contrast(m.ink, m.wing) >= 7.0)
            assertTrue("${look.name}: the rules show on the sheet", contrast(m.rule, m.body) >= 1.5)
        }
    }

    @Test
    fun theFacesAreDrawnWingSheetKeel() {
        val f = PlaneMark.Colors.Pop.faces
        assertEquals(listOf(PlaneMark.WING, PlaneMark.BODY, PlaneMark.KEEL), f.map { it.first })
        assertEquals(listOf(PlaneMark.PALE_WING, PlaneMark.WHITE, PlaneMark.NAVY), f.map { it.second })
    }

    @Test
    fun svgIsASolidTileWithTheNavyEdgedPlane() {
        val svg = PlaneMark.svg(PlaneMark.Colors(0xFFF2C14E.toInt()))
        assertFalse(svg.contains("Gradient"))
        assertTrue(svg.contains("<rect width=\"72\" height=\"72\" rx=\"17.28\" fill=\"#F2C14E\"/>"))
        assertTrue(svg.contains("stroke=\"#1E2A5A\""))
        assertTrue(svg.contains("stroke-linejoin=\"round\""))
        assertTrue(svg.contains("stroke=\"#B9C6E4\""))
        assertEquals(PlaneMark.DOTS.size / 2, Regex("<circle ").findAll(svg).count())
        assertEquals(3, Regex("<path d=\"M[^\"]+Z\"").findAll(svg).count())
        val padded = PlaneMark.svg(pad = 0.08f)
        assertTrue(padded.contains("viewBox=\"-5.76 -5.76 83.52 83.52\""))
        assertTrue(padded.contains("fill=\"#FFC93C\""))
    }

    @Test
    fun theNoseStaysInsideTheAdaptiveIconsSafeCircle() {
        for (tri in listOf(PlaneMark.BODY, PlaneMark.WING, PlaneMark.KEEL)) {
            for (i in tri.indices step 2) {
                val x = PlaneMark.local(tri[i]) + PlaneMark.VIEW_MIN - PlaneMark.CENTER
                val y = PlaneMark.local(tri[i + 1]) + PlaneMark.VIEW_MIN - PlaneMark.CENTER
                assertTrue("(${tri[i]}, ${tri[i + 1]})", hypot(x, y) <= 33f)
            }
        }
    }

    /** PlaneMark mirrors docs/brand/plane_icon.py (the launcher icon, Play icon and drawables). */
    @Test
    fun matchesTheIconScript() {
        val py = File("../docs/brand/plane_icon.py").readText()
        fun line(name: String) = Regex("^$name = (.+)$", RegexOption.MULTILINE).find(py)!!.groupValues[1].substringBefore("  #")
        fun nums(name: String) = Regex("-?\\d+(?:\\.\\d+)?").findAll(line(name)).map { it.value.toFloat() }.toList().toFloatArray()
        fun colour(hex: String) = (0xFF000000 or hex.removePrefix("#").toLong(16)).toInt()

        assertEquals(PlaneMark.POP_YELLOW, colour(line("YELLOW").trim('"')))
        val (navy, white, wing, rule) = Regex("#[0-9A-F]{6}").findAll(line("NAVY, WHITE, WING_FILL, RULE")).map { colour(it.value) }.toList()
        assertEquals(listOf(PlaneMark.NAVY, PlaneMark.WHITE, PlaneMark.PALE_WING, PlaneMark.RULE), listOf(navy, white, wing, rule))
        assertEquals(PlaneMark.SCALE, nums("SCALE").single(), 0f)
        assertEquals(PlaneMark.STROKE, nums("STROKE").single(), 0f)
        assertEquals(PlaneMark.RULE_W, nums("RULE_W").single(), 0f)
        assertEquals(PlaneMark.CORNER, nums("CORNER").single(), 0f)
        assertEquals(PlaneMark.DOT_R, nums("DOT_STEP, DOT_R")[1], 0f)
        assertArrayEquals(PlaneMark.BODY, nums("BODY"), 0f)
        assertArrayEquals(PlaneMark.WING, nums("WING"), 0f)
        assertArrayEquals(PlaneMark.KEEL, nums("KEEL"), 0f)
        assertArrayEquals(PlaneMark.RULES, nums("RULES"), 0f)

        // The trail as the script last wrote it (the Play icon's master).
        val svg = File("../docs/brand/plane.svg").readText()
        val dots = Regex("<circle cx=\"([0-9.]+)\" cy=\"([0-9.]+)\"").findAll(svg)
            .flatMap { sequenceOf(it.groupValues[1].toFloat(), it.groupValues[2].toFloat()) }.toList().toFloatArray()
        assertArrayEquals(PlaneMark.DOTS, dots, 0.005f)
        assertTrue(svg.contains("<rect width=\"108\" height=\"108\" fill=\"#FFC93C\"/>"))
    }

    @Test
    fun theLauncherIconIsTheNavyEdgedPlaneOnSolidYellow() {
        val drawables = File("src/main/res/drawable")
        val background = File(drawables, "ic_launcher_background.xml").readText()
        assertTrue(background.contains("android:fillColor=\"#FFFFC93C\""))
        assertFalse(background.contains("gradient"))
        val foreground = File(drawables, "ic_launcher_foreground.xml").readText()
        assertTrue(foreground.contains("android:strokeColor=\"#FF1E2A5A\""))
        assertTrue(foreground.contains("android:scaleX=\"0.84\""))
        for (name in listOf("ic_launcher_monochrome.xml", "ic_brand_plane_mono.xml")) {
            val colours = Regex("#[0-9A-F]{8}").findAll(File(drawables, name).readText()).map { it.value.takeLast(6) }.toSet()
            assertEquals(name, 1, colours.size)
        }
        val tile = File(drawables, "ic_brand_plane.xml").readText()
        assertTrue(tile.contains("#FFFFC93C"))
        assertFalse(tile.contains("gradient"))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(c: Int): Double {
        fun ch(shift: Int): Double {
            val v = ((c shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }
}

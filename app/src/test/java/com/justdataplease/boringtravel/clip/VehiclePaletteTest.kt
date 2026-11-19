package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VehiclePaletteTest {
    private val palettes = Look.entries.associateWith { PaperVehicle3D.Palette.of(BtLooks.of(it)) }

    @Test
    fun everyThemeYieldsItsOwnPalette() {
        assertEquals(5, palettes.values.toSet().size)
        assertEquals(5, palettes.values.map { PaperVehicle3D.Palette.materials(it).toList() }.toSet().size)
    }

    @Test
    fun palettesAreOpaqueAndValid() {
        for ((look, p) in palettes) {
            for (c in listOf(p.body, p.card, p.ink, p.accent, p.glass, p.shadow)) assertEquals(look.name, 0xFF, c ushr 24)
            assertTrue(look.name, p.outline in 0f..1f)
            PaperVehicle3D.Palette.materials(p).forEach { assertEquals(look.name, 0xFF, it ushr 24) }
        }
    }

    @Test
    fun bodyInkAndAccentAreTellApart() {
        for ((look, p) in palettes) {
            val m = PaperVehicle3D.Palette.materials(p)
            val body = m[0]
            val ink = m[2]
            val accent = m[3]
            assertTrue("$look body vs ink", distance(body, ink) > 150)
            assertTrue("$look body vs accent", distance(body, accent) > 60)
        }
    }

    @Test
    fun theThemesColoursCarry() {
        fun near(a: Int, b: Long) = distance(a, b.toInt()) < 60
        val pop = PaperVehicle3D.Palette.materials(palettes.getValue(Look.PostcardPop))
        assertTrue("Pop coral", near(pop[3], 0xFFFF5A3C))
        val trail = palettes.getValue(Look.Trail)
        assertTrue(trail.dark)
        assertTrue("Trail yellow", near(PaperVehicle3D.Palette.materials(trail)[3], 0xFFF6C445))
        val sticker = PaperVehicle3D.Palette.materials(palettes.getValue(Look.StickerBook))
        assertTrue("Sticker pink", near(sticker[3], 0xFFFF8FB1))
        assertTrue("Sticker mint", near(sticker[4], 0xFF7FD8B2))
        val sketch = PaperVehicle3D.Palette.materials(palettes.getValue(Look.Sketchbook))
        assertTrue("Sketch ballpoint", near(sketch[2], 0xFF1F2D6B))
        val market = PaperVehicle3D.Palette.materials(palettes.getValue(Look.Market))
        assertTrue("Market orange dot", near(market[3], 0xFFFF7A3D))
        assertTrue("Market mustard", near(market[4], 0xFFF2C14E))
    }

    @Test
    fun fontChoiceDoesNotChangeTheVehicles() {
        for (look in Look.entries) for (set in FontSet.entries) {
            assertEquals(palettes.getValue(look), PaperVehicle3D.Palette.of(BtLooks.of(look, set)))
        }
    }

    private fun distance(a: Int, b: Int): Int =
        abs((a ushr 16 and 255) - (b ushr 16 and 255)) + abs((a ushr 8 and 255) - (b ushr 8 and 255)) + abs((a and 255) - (b and 255))
}

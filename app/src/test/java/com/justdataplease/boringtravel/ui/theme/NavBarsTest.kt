package com.justdataplease.boringtravel.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavBarsTest {

    @Test
    fun everyThemeHasItsOwnBar() {
        assertEquals(
            listOf(NavMarker.Pill, NavMarker.LuggageTag, NavMarker.Highlighter, NavMarker.PencilLoop, NavMarker.DotSticker),
            Look.entries.map { NavBars.of(it).marker },
        )
    }

    @Test
    fun postcardPopIsAPlainBarWithACoralPill() {
        val b = NavBars.pop
        assertTrue(b.floating)
        assertEquals(12.dp, b.inset)
        assertEquals(64.dp, b.height)
        assertEquals(18.dp, b.corner)
        assertEquals(Color.White, b.fill)
        assertEquals(2.dp, b.border!!.width)
        assertFalse(b.border.dashed)
        assertEquals(Color(0xFF1D1B3A), b.border.color)
        assertEquals(4.dp, b.shadow!!.y)
        assertEquals(0.dp, b.shadow.blur)
        assertEquals(Color(0xFF1D1B3A), b.shadow.color)
        assertNull("no stitch or perforation", b.stitch)
        assertNull(b.topLine)
        assertEquals(Color(0xFFFF5A3C), b.markerColor)
        assertEquals("the pill sits straight, in line", 0f, b.markerTilt)
        assertEquals(Color.White, b.activeIcon)
        assertEquals(Color(0xFFFF5A3C), b.activeLabel)
        assertEquals(FontWeight.Bold, b.activeLabelWeight)
        assertEquals(Color(0xFF5E5A7D), b.icon)
        assertEquals(b.icon, b.label)
        assertEquals(b.labelSize, b.activeLabelSize)
        assertEquals(NavFace.Albert, b.activeFace)
    }

    @Test
    fun trailIsALuggageTagOnADarkSlab() {
        val b = NavBars.trail
        assertTrue(b.floating)
        assertEquals(Color(0xFF24132C), b.fill)
        assertTrue("inset stitch", b.stitch!!.dashed)
        assertEquals(6.dp, b.stitchInset)
        assertEquals(Color(0xFFF6C445), b.markerColor)
        assertEquals(-5f, b.markerTilt)
        assertTrue("upper-case labels", b.upper)
        assertEquals(b.activeIcon, b.activeLabel)
    }

    @Test
    fun stickerBookIsAHighlighterOnPaper() {
        val b = NavBars.sticker
        assertFalse(b.floating)
        assertEquals(70.dp, b.height)
        assertEquals(Color(0xFFFCFCF9), b.fill)
        assertEquals(Color(0xFFD8DCEA), b.topLine)
        assertEquals(NavFace.Hand, b.activeFace)
        assertEquals(Color(0xFFFFE066), b.markerColor)
        assertEquals(-2f, b.markerTilt)
    }

    @Test
    fun sketchbookBarNeverPutsALineNextToTheRuling() {
        val b = NavBars.sketch
        assertFalse(b.floating)
        assertNull("no bar background", b.fill)
        assertNull("no top line", b.topLine)
        assertNull(b.border)
        assertNull(b.stitch)
        assertNull(b.shadow)
        assertEquals(NavFace.Hand, b.activeFace)
        assertEquals(Color(0xFF1F2D6B), b.markerColor)
    }

    @Test
    fun marketIsAKraftStripWithADotSticker() {
        val b = NavBars.market
        assertTrue(b.floating)
        assertEquals(Color(0xFFF8F2E6), b.fill)
        assertTrue(b.stitch!!.dashed)
        assertEquals(Color(0xFFB9A27F), b.stitch.color)
        assertEquals(Color(0xFFFF7A3D), b.markerColor)
    }

    @Test
    fun activeTabsStandOut() {
        for (look in Look.entries) {
            val b = NavBars.of(look)
            assertNotEquals(look.name, b.label, b.activeLabel)
            assertNotEquals(look.name, b.icon, b.activeIcon)
            assertTrue(look.name, b.activeLabelWeight >= b.labelWeight)
        }
    }
}

package com.justdataplease.boringtravel.widget

import androidx.compose.ui.graphics.Color
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.Paper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.toArgb
import com.justdataplease.boringtravel.art.PlaneMark

class WidgetLookTest {

    @Test
    fun postcardPopIsAnInkedTicketWithTheCoralNote() {
        val w = WidgetLook.of(Look.PostcardPop)
        val c = BtLooks.pop.colors
        assertEquals(Color.White, w.paper)
        assertEquals(Paper.Plain, w.texture)
        assertEquals(c.ink, w.edge)
        assertEquals(1.5f, w.edgeWidth)
        assertEquals("a hard ink shadow", c.ink, w.shadow)
        assertEquals(c.primary, w.note)
        assertEquals(Color.White, w.onNote)
        assertEquals(-3f, w.noteTilt)
        assertFalse(w.pill)
    }

    @Test
    fun trailStaysDarkWithTheYellowNote() {
        val w = WidgetLook.of(Look.Trail)
        val c = BtLooks.trail.colors
        assertEquals(Color(0xFF24132C), w.paper)
        assertEquals(Paper.Plain, w.texture)
        assertNull(w.textureColor)
        assertTrue(w.pill)
        assertEquals(c.primary, w.note)
        assertEquals(c.onPrimary, w.onNote)
        assertTrue("dark bar", w.paper.luminance() < 0.2f)
        assertTrue("light text", w.ink.luminance() > 0.7f)
        // The logo is the navy-edged plane on Trail's yellow.
        assertEquals(PlaneMark.Colors(c.accent.toArgb()), w.mark)
    }

    @Test
    fun paperThemesAreTheirPage() {
        val expected = mapOf(Look.StickerBook to Paper.Dots, Look.Sketchbook to Paper.Lines, Look.Market to Paper.Dots)
        for ((look, paper) in expected) {
            val w = WidgetLook.of(look)
            val c = BtLooks.of(look).colors
            assertEquals(look.name, paper, w.texture)
            assertEquals(look.name, c.bg, w.paper)
            assertEquals(look.name, c.paperDot, w.textureColor)
            assertEquals(look.name, c.ink, w.ink)
            assertFalse(look.name, w.pill)
        }
        assertTrue("Market's fibres are finer", WidgetLook.of(Look.Market).dotStep < WidgetLook.of(Look.StickerBook).dotStep)
    }

    @Test
    fun noteIsTheThemesActiveTabColour() {
        assertEquals(BtLooks.sticker.colors.accent, WidgetLook.of(Look.StickerBook).note)
        assertEquals(BtLooks.sketch.colors.accent, WidgetLook.of(Look.Sketchbook).note)
        assertEquals(Color(0xFFFF7A3D), WidgetLook.of(Look.Market).note)
    }

    @Test
    fun everyThemeHasItsOwnBar() {
        assertEquals(5, Look.entries.map { WidgetLook.of(it) }.toSet().size)
        for (look in Look.entries) assertEquals(look.name, look.dark, WidgetLook.of(look).pill)
    }

    @Test
    fun everyThemeHasReadableButtonsAndTheLogoOnItsYellow() {
        for (look in Look.entries) {
            val w = WidgetLook.of(look)
            assertNotEquals(look.name, w.note, w.onNote)
            assertNotEquals(look.name, w.button, w.onButton)
            assertNotEquals(look.name, w.paper, w.ink)
            val c = BtLooks.of(look).colors
            assertEquals(look.name, c.accent.toArgb(), w.mark.sky)
            assertEquals(look.name, PlaneMark.NAVY, w.mark.ink)
        }
    }

    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}

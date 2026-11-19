package com.justdataplease.boringtravel.ui.tripcal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.justdataplease.boringtravel.tripcal.TripColors
import com.justdataplease.boringtravel.ui.theme.BtColors
import com.justdataplease.boringtravel.ui.theme.BtTokens
import com.justdataplease.boringtravel.ui.theme.Look
import kotlin.math.max
import kotlin.math.min

/**
 * The trip colours of each theme for the Calendar tab, [TripColors.SIZE] each and in the same order of roles, so a
 * trip keeps its place when the theme changes: the theme's route, plan and highlighter colours first, then three
 * that sit well beside them.
 */
object TripPalette {
    fun of(t: BtTokens): List<Color> {
        val c = t.colors
        return when (t.look) {
            Look.PostcardPop -> listOf(c.route, c.plan, c.accent, c.sea, Color(0xFF7B5EA7), Color(0xFFFF8FB1))
            Look.Trail -> listOf(c.route, c.plan, Color(0xFF7FD1C0), c.muted, Color(0xFF8FB8F2), Color(0xFFFF9FBF))
            Look.StickerBook -> listOf(c.route, c.plan, c.accent, t.vehicle.accent, Color(0xFF6B8FD6), Color(0xFFB08AD9))
            Look.Sketchbook -> listOf(c.route, c.plan, c.accent, t.vehicle.glass, Color(0xFF5BB08A), Color(0xFFF29BB5))
            Look.Market -> listOf(c.route, c.plan, c.accent, Color(0xFF8C6BB1), Color(0xFF7FA65A), Color(0xFFD98BA0))
        }
    }

    /** The colour of trip colour [index] in this theme. */
    fun color(t: BtTokens, index: Int): Color = of(t).let { it[Math.floorMod(index, it.size)] }

    /** Text on a trip colour: the theme's ink or its page colour, whichever reads better (WCAG contrast). */
    fun onColor(fill: Color, c: BtColors): Color = if (contrast(fill, c.ink) >= contrast(fill, c.bg)) c.ink else c.bg

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }
}

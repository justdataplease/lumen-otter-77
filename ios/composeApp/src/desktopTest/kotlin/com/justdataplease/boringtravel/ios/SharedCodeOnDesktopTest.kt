package com.justdataplease.boringtravel.ios

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.art.PlaneMark
import com.justdataplease.boringtravel.ui.theme.BtFontFiles
import com.justdataplease.boringtravel.ui.theme.BtFonts
import com.justdataplease.boringtravel.ui.theme.BtLooks
import com.justdataplease.boringtravel.ui.theme.FontSet
import com.justdataplease.boringtravel.ui.theme.Look
import com.justdataplease.boringtravel.ui.theme.TextFont
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Android app's files as this app compiles and reads them. */
class SharedCodeOnDesktopTest {
    private fun width(text: String, family: FontFamily, weight: FontWeight): Int {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        return measurer.measure(text, TextStyle(fontFamily = family, fontWeight = weight, fontSize = 100.sp), maxLines = 1).size.width
    }

    @Test
    fun theBundledFontsAreTheOnesDrawn() = runBlocking {
        BtFontFiles.load()
        val words = "Trips to Kyoto"
        val system = width(words, FontFamily.Default, FontWeight.Bold)
        // A handwriting face, a serif and a fixed-width one: each sets the same words to its own width.
        val caveat = width(words, BtFonts.caveat, FontWeight.Bold)
        val literata = width(words, BtFonts.literata, FontWeight.Bold)
        val mono = width(words, BtFonts.plexMono, FontWeight.Medium)
        assertNotEquals(system, caveat)
        assertNotEquals(system, literata)
        assertNotEquals(caveat, literata)
        // Fixed width: every letter of the mono face takes the same room.
        assertEquals(width("iiii", BtFonts.plexMono, FontWeight.Medium), width("MMMM", BtFonts.plexMono, FontWeight.Medium))
        assertTrue(mono > 0)
    }

    @Test
    fun everyLookHasItsTokens() {
        for (look in Look.entries) {
            val tokens = BtLooks.of(look, FontSet.LooksOwn, TextFont.Default)
            assertEquals(look, tokens.look)
            assertEquals(look.dark, tokens.colors.bg.red + tokens.colors.bg.green + tokens.colors.bg.blue < 1.5f)
        }
    }

    @Test
    fun theBrandMarkWritesItsNumbersAsOnAndroid() {
        // The two lines rewritten on the way in (the build file's rules): a colour and a two-decimal number.
        assertEquals("#FFC93C", PlaneMark.hex(PlaneMark.POP_YELLOW))
        val svg = PlaneMark.svg()
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 72 72\""), svg.take(80))
        assertTrue("r=\"1.26\"" in svg, "the trail's dots: 1.5 x 0.84")
        assertTrue("stroke-width=\"2.02\"" in svg, "the plane's edges: 2.4 x 0.84 = 2.016")
    }
}

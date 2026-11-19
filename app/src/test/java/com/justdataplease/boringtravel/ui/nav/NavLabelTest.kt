package com.justdataplease.boringtravel.ui.nav

import androidx.compose.ui.unit.sp
import com.justdataplease.boringtravel.ui.theme.NavBars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How far a bottom-bar label may shrink to fit (checked on the emulator at 360 dp in every theme). */
class NavLabelTest {
    @Test fun labelsShrinkToSixtyPercentButNeverUnderEightSp() {
        assertEquals(10.2f, labelMinSize(17.sp).value, 0.01f) // Sticker Book's active Caveat
        assertEquals(9.6f, labelMinSize(16.sp).value, 0.01f) // Sketchbook's active hand
        assertEquals(8f, labelMinSize(11.sp).value, 0.01f)
        assertEquals(8f, labelMinSize(9.5.sp).value, 0.01f) // Trail
    }

    @Test fun everyThemeCanShrinkItsLabels() {
        listOf(NavBars.pop, NavBars.trail, NavBars.sticker, NavBars.sketch, NavBars.market).forEach { spec ->
            listOf(spec.labelSize, spec.activeLabelSize).forEach { size ->
                assertTrue(labelMinSize(size).value <= size.value)
            }
        }
    }
}

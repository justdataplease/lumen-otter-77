package com.justdataplease.boringtravel.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpriteFitTest {

    @Test
    fun boundsFindTheDrawnPixels() {
        val w = 10
        val px = IntArray(w * w)
        px[2 * w + 3] = 0xFF000000.toInt()
        px[6 * w + 7] = 0x80FFFFFF.toInt()
        px[9 * w + 9] = 0x05000000 // too faint to count
        assertEquals(SpriteFit.Box(3, 2, 8, 7), SpriteFit.bounds(px, w, w))
        assertNull(SpriteFit.bounds(IntArray(w * w), w, w))
    }

    @Test
    fun aSmallOffCentreModelIsScaledAndCentred() {
        // A 40 x 20 box whose middle sits 20 px right of and 10 px below the centre of a 200 px square.
        val fit = SpriteFit.fit(SpriteFit.Box(100, 100, 140, 120), 200f, 0.9f)
        assertEquals(4.5f, fit.scale, 0.0001f)
        // The box's middle (120, 110) is 20, 10 from the origin it was drawn about; after scaling it lands mid-square.
        assertEquals(100f, fit.centerX + 20f * fit.scale, 0.001f)
        assertEquals(100f, fit.centerY + 10f * fit.scale, 0.001f)
    }
}

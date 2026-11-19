package com.justdataplease.boringtravel.globe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PinLayoutTest {
    private fun minGap(x: FloatArray, y: FloatArray, n: Int): Float {
        var m = Float.MAX_VALUE
        for (i in 0 until n) for (j in i + 1 until n) m = minOf(m, hypot(x[j] - x[i], y[j] - y[i]))
        return m
    }

    @Test fun pinsWithRoomStayOnTheirCountries() {
        val x = floatArrayOf(10f, 200f, 400f)
        val y = floatArrayOf(10f, 50f, 300f)
        PinLayout.separate(x, y, 3, 30f)
        assertArrayEquals(floatArrayOf(10f, 200f, 400f), x, 0f)
        assertArrayEquals(floatArrayOf(10f, 50f, 300f), y, 0f)
    }

    @Test fun overlappingPinsArePushedApartEvenly() {
        val x = floatArrayOf(100f, 110f)
        val y = floatArrayOf(100f, 100f)
        PinLayout.separate(x, y, 2, 30f)
        assertTrue(hypot(x[1] - x[0], y[1] - y[0]) >= 30f)
        // Both move, about the same amount, and their middle stays put.
        assertEquals(105f, (x[0] + x[1]) / 2, 0.01f)
    }

    @Test fun pinsOnTheSameSpotFanOut() {
        val n = 6
        val x = FloatArray(n) { 200f }
        val y = FloatArray(n) { 200f }
        PinLayout.separate(x, y, n, 30f, iterations = 40)
        assertTrue(minGap(x, y, n) >= 30f * 0.9f)
    }

    @Test fun aCrowdedEuropeSpreadsOut() {
        // Ten pins within 40 px of each other, as Europe looks at the default zoom.
        val ax = floatArrayOf(100f, 108f, 112f, 120f, 96f, 130f, 104f, 116f, 125f, 99f)
        val ay = floatArrayOf(100f, 104f, 96f, 110f, 120f, 100f, 112f, 125f, 90f, 92f)
        val x = ax.copyOf()
        val y = ay.copyOf()
        PinLayout.separate(x, y, ax.size, 28f)
        assertTrue(minGap(x, y, ax.size) >= 28f * 0.8f)
        // Nobody flies off: every pin stays within a few pin widths of its country.
        for (i in ax.indices) assertTrue(hypot(x[i] - ax[i], y[i] - ay[i]) < 28f * 3)
    }

    @Test fun theSameAnchorsAlwaysGiveTheSameLayout() {
        val a = floatArrayOf(50f, 55f, 52f, 60f)
        val b = floatArrayOf(50f, 51f, 58f, 49f)
        val x1 = a.copyOf(); val y1 = b.copyOf()
        val x2 = a.copyOf(); val y2 = b.copyOf()
        PinLayout.separate(x1, y1, 4, 24f)
        PinLayout.separate(x2, y2, 4, 24f)
        assertArrayEquals(x1, x2, 0f)
        assertArrayEquals(y1, y2, 0f)
    }

    @Test fun namesShowWhereTheyFitFlagsFirst() {
        val out = BooleanArray(3)
        val w = floatArrayOf(60f, 60f, 60f)
        // Pin 1 sits right under pin 0: pin 0's name would cover it, so only pin 1's (and the far pin's) show.
        val x = floatArrayOf(100f, 100f, 300f)
        val y = floatArrayOf(100f, 124f, 100f)
        PinLayout.labels(x, y, 3, pinR = 15f, w = w, h = 14f, gap = 2f, viewW = 400f, viewH = 400f, out = out)
        assertFalse(out[0])
        assertTrue(out[1])
        assertTrue(out[2])
    }

    @Test fun twoNamesNeverOverlapAndTheFirstWins() {
        val out = BooleanArray(2)
        val x = floatArrayOf(100f, 140f)
        val y = floatArrayOf(100f, 100f)
        PinLayout.labels(x, y, 2, pinR = 15f, w = floatArrayOf(70f, 70f), h = 14f, gap = 2f, viewW = 400f, viewH = 400f, out = out)
        assertTrue(out[0])
        assertFalse(out[1])
    }

    @Test fun namesOffTheViewOrEmptyDoNotShow() {
        val out = BooleanArray(2)
        PinLayout.labels(
            floatArrayOf(10f, 200f), floatArrayOf(100f, 100f), 2, pinR = 15f, w = floatArrayOf(80f, 0f), h = 14f, gap = 2f,
            viewW = 400f, viewH = 400f, out = out,
        )
        assertFalse(out[0]) // would start left of the screen
        assertFalse(out[1]) // no name (the pin is near the rim)
    }
}

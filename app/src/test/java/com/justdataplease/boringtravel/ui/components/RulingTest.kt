package com.justdataplease.boringtravel.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulingTest {

    @Test
    fun fractionalStepsRoundToWholePixels() {
        assertEquals(58, Ruling.stepPx(22 * 2.625f)) // 57.75 px on a 420 dpi phone
        assertEquals(66, Ruling.stepPx(22 * 3f))
        assertEquals(2, Ruling.stepPx(0.4f))
    }

    @Test
    fun strokeIsAtLeastOnePixel() {
        assertEquals(2, Ruling.strokePx(0.75f * 2.625f))
        assertEquals(1, Ruling.strokePx(0.2f))
    }

    @Test
    fun everyGapIsTheSameHeight() {
        val lines = Ruling.lines(2400f, 58)
        assertEquals(41, lines.size)
        assertEquals(58, lines.first())
        assertTrue(lines.zipWithNext { a, b -> b - a }.all { it == 58 })
        assertTrue(lines.last() < 2400)
    }

    @Test
    fun noLinesForEmptyOrBadInput() {
        assertTrue(Ruling.lines(0f, 58).isEmpty())
        assertTrue(Ruling.lines(500f, 0).isEmpty())
    }

    @Test
    fun gridSquaresAreEqualWholePixelsBothWays() {
        val step = Ruling.stepPx(18 * 2.625f) // 47.25 px on a 420 dpi phone
        val stroke = Ruling.strokePx(0.75f * 2.625f)
        assertEquals(47, step)
        val rects = Ruling.grid(1080f, 2400f, step, stroke)
        val across = rects.filter { it.left == 0 && it.width == 1080 }
        val down = rects.filter { it.top == 0 && it.height == 2400 }
        assertEquals(51, across.size) // 47 … 2397
        assertEquals(22, down.size) // 47 … 1034
        assertEquals(rects.size, across.size + down.size)
        assertTrue(across.all { it.height == stroke })
        assertTrue(down.all { it.width == stroke })
        assertTrue(across.map { it.top }.zipWithNext { a, b -> b - a }.all { it == step })
        assertTrue(down.map { it.left }.zipWithNext { a, b -> b - a }.all { it == step })
        assertEquals(step, across.first().top)
        assertEquals(step, down.first().left)
    }

    @Test
    fun gridStaysInsideThePage() {
        val rects = Ruling.grid(300.5f, 200f, 18, 1)
        assertTrue(rects.all { it.left >= 0 && it.top >= 0 && it.right <= 301 && it.bottom <= 200 })
        assertTrue(rects.all { it.top < 200 && it.left < 301 })
    }

    @Test
    fun noGridForEmptyOrBadInput() {
        assertTrue(Ruling.grid(0f, 0f, 47, 2).isEmpty())
        assertTrue(Ruling.grid(1080f, 2400f, 0, 2).isEmpty())
    }
}

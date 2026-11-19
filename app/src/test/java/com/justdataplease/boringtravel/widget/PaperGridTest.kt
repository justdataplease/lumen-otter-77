package com.justdataplease.boringtravel.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperGridTest {
    @Test
    fun dotsAreCentredWithEqualEnds() {
        val d = PaperGrid.dots(60f, 13f)
        assertEquals(4, d.size)
        assertEquals(d.first(), 60f - d.last(), 0.001f)
        d.zipWithNext().forEach { (a, b) -> assertEquals(13f, b - a, 0.001f) }
    }

    @Test
    fun rulesStayHalfAStepFromTheEdges() {
        for (h in listOf(45f, 57f, 60f, 74f)) {
            val r = PaperGrid.rules(h, 15f)
            assertTrue("no rules on $h", r.isNotEmpty())
            r.forEach { assertTrue(it >= 7.5f - 0.01f && it <= h - 7.5f + 0.01f) }
            assertEquals(r.first(), h - r.last(), 0.001f)
        }
    }

    @Test
    fun nothingOnAnEmptyStrip() {
        assertTrue(PaperGrid.dots(0f, 13f).isEmpty())
        assertTrue(PaperGrid.rules(10f, 15f).isEmpty())
    }
}

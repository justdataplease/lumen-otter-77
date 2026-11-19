package com.justdataplease.boringtravel.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhotoRowsTest {
    private val land = 4 / 3.0
    private val port = 3 / 4.0
    private val width = 178.0
    private val gap = 3.0

    private fun sizes(rows: List<PhotoRows.Row>) = rows.map { it.count }

    /** Every photo once, in order, each row starting where the last ended. */
    private fun covers(rows: List<PhotoRows.Row>, n: Int) {
        assertEquals(0, rows.first().from)
        assertEquals(n, rows.last().to)
        rows.zipWithNext { a, b -> assertEquals(a.to, b.from) }
    }

    @Test fun largeKeepsTwoLandscapesARowMediumThreeSmallFour() {
        val twelve = List(12) { land }
        fun perRow(size: PhotoSize) = PhotoRows.scale(size).let { s -> sizes(PhotoRows.rows(twelve, width, s.rowMm, gap)) }
        assertEquals(List(6) { 2 }, perRow(PhotoSize.Large))
        assertEquals(List(4) { 3 }, perRow(PhotoSize.Medium))
        assertEquals(List(3) { 4 }, perRow(PhotoSize.Small))
    }

    @Test fun fullRowsFillTheWidthAtOneHeight() {
        val aspects = listOf(land, port, land, port, port, land, 1.0, 3.0)
        val rows = PhotoRows.rows(aspects, width, 60.0, gap)
        covers(rows, aspects.size)
        rows.dropLast(1).forEach { r ->
            assertEquals(width, r.width, 1e-9)
            // Photo widths at the row's height plus the gaps make the column.
            val photos = (r.from until r.to).sumOf { aspects[it] * r.height }
            assertEquals(width, photos + gap * (r.count - 1), 1e-6)
        }
    }

    @Test fun aLastRowWithFewPhotosStopsAtTheTargetInsteadOfGrowing() {
        // Five landscapes at a 72 mm target: rows of three and two rather than a lone photo at the end.
        val rows = PhotoRows.rows(List(5) { land }, width, 72.0, gap)
        assertEquals(setOf(2, 3), sizes(rows).toSet())
        // One photo alone stays at the target height and is centred at its own width.
        val one = PhotoRows.rows(listOf(land), width, 72.0, gap).single()
        assertEquals(72.0, one.height, 1e-9)
        assertEquals(72.0 * land, one.width, 1e-9)
    }

    @Test fun portraitsAndPanoramasKeepTheirShape() {
        // A panorama alone is as wide as the column and low; it isn't squeezed next to a portrait.
        val rows = PhotoRows.rows(listOf(4.0, port, port, port), width, 72.0, gap)
        covers(rows, 4)
        assertEquals(1, rows.first().count)
        assertEquals(width / 4.0, rows.first().height, 1e-9)
        assertEquals(3, rows.last().count)
        // No row grows taller than the cap, even a lone tall photo between others.
        PhotoRows.rows(listOf(land, land, 0.3, land, land), width, 60.0, gap, maxHeight = 90.0).forEach { assertTrue(it.height <= 90.0 + 1e-9) }
    }

    @Test fun framesAndGapsAreLeftOutOfThePhotos() {
        val r = PhotoRows.rows(listOf(land, land), width, 80.0, gap = 4.0, extra = 4.0).single()
        assertEquals(width, r.width, 1e-9)
        assertEquals((width - 4.0 - 2 * 4.0) / (2 * land), r.height, 1e-9)
    }

    @Test fun neverMoreThanSixInARow() {
        PhotoRows.rows(List(20) { 4.0 }, width, 5.0, gap).forEach { assertTrue(it.count <= PhotoRows.MAX_PER_ROW) }
    }

    @Test fun aPhotoAloneIsAsWideAsItMayBeUpToItsHeight() {
        // A landscape takes its share of the column; a portrait gets narrower so it isn't taller than allowed.
        assertEquals(1.0, PhotoRows.single(land, width, 200.0), 1e-9)
        assertEquals(0.8, PhotoRows.single(land, width, 200.0, share = 0.8), 1e-9)
        assertEquals(120.0 * port / width, PhotoRows.single(port, width, 120.0), 1e-9)
        assertTrue(abs(PhotoRows.single(port, width, 120.0) * width / port - 120.0) < 1e-9)
    }

    @Test fun oddShapesAreTamed() {
        assertEquals(PhotoRows.UNKNOWN_ASPECT, PhotoRows.clean(null), 0.0)
        assertEquals(PhotoRows.UNKNOWN_ASPECT, PhotoRows.clean(Double.NaN), 0.0)
        assertEquals(PhotoRows.UNKNOWN_ASPECT, PhotoRows.clean(0.0), 0.0)
        assertEquals(PhotoRows.MAX_ASPECT, PhotoRows.clean(40.0), 0.0)
        assertEquals(PhotoRows.MIN_ASPECT, PhotoRows.clean(0.01), 0.0)
        assertEquals(emptyList<PhotoRows.Row>(), PhotoRows.rows(emptyList(), width, 60.0, gap))
    }

    @Test fun aCoverStripIsOneRowWithinItsHeight() {
        val r = PhotoRows.strip(listOf(port, port), width, gap, maxHeight = 70.0)!!
        assertEquals(2, r.count)
        assertEquals(70.0, r.height, 1e-9)
        assertTrue(r.width < width)
        assertEquals(null, PhotoRows.strip(emptyList(), width, gap, 70.0))
    }
}

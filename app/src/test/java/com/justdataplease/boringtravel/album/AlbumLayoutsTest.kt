package com.justdataplease.boringtravel.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared album arrangements: groups, grid cells, rectangles in boxes of every shape, splitting photos into pages. */
class AlbumLayoutsTest {
    private val eps = 1e-6

    /** The type areas of the book's sizes (A5, 21 × 21, A4, Letter) with their gutters, then a phone column and a wide box. */
    private val boxes = listOf(
        Triple(115.0, 175.0, 5.0), Triple(172.8, 167.4, 5.4), Triple(173.6, 257.6, 5.6), Triple(179.2, 235.2, 5.6),
        Triple(360.0, 520.0, 8.0), Triple(400.0, 220.0, 6.0),
    )

    @Test fun groupsByPhotoCountWithTwoToFourArrangementsEach() {
        assertEquals(listOf(1, 2, 3, 4, 6, 9), AlbumLayouts.counts)
        AlbumLayouts.counts.forEach { n -> assertTrue("$n", AlbumLayouts.forCount(n).size in 2..4) }
        assertEquals(AlbumLayouts.all.size, AlbumLayouts.all.map { it.id }.toSet().size)
        AlbumLayouts.all.forEach { a ->
            assertEquals(a, AlbumLayouts.of(a.id))
            assertTrue(a.id.matches(Regex("^[a-z0-9-]+$")))
            assertTrue(a.name.isNotBlank())
        }
        assertNull(AlbumLayouts.of("7-spiral"))
        assertNull(AlbumLayouts.of(null))
    }

    @Test fun everyCellSitsOnItsGridAndNoTwoShareAPlace() {
        AlbumLayouts.all.forEach { a ->
            val taken = HashSet<Pair<Int, Int>>()
            a.cells.forEach { c ->
                assertTrue(a.id, c.cols >= 1 && c.rows >= 1 && c.col >= 0 && c.row >= 0)
                assertTrue(a.id, c.col + c.cols <= a.columns && c.row + c.rows <= a.rows)
                for (x in c.col until c.col + c.cols) for (y in c.row until c.row + c.rows) assertTrue("${a.id} $x,$y", taken.add(x to y))
            }
            assertEquals(a.id, a.colWeights.size, a.columns)
            assertTrue(a.id, a.colWeights.all { it > 0 } && a.rowWeights.all { it > 0 })
        }
    }

    @Test fun photosStayInsideTheBoxAGutterApartWithNoOverlaps() {
        AlbumLayouts.all.forEach { a ->
            boxes.forEach { (w, h, g) ->
                val rs = a.place(w, h, g)
                val where = "${a.id} in $w × $h"
                assertEquals(where, a.count, rs.size)
                rs.forEach { r ->
                    assertTrue(where, r.w > 0 && r.h > 0)
                    assertTrue(where, r.x >= -eps && r.y >= -eps && r.right <= w + eps && r.bottom <= h + eps)
                }
                for (i in rs.indices) for (j in i + 1 until rs.size) {
                    val p = rs[i]
                    val q = rs[j]
                    assertTrue("$where $i/$j overlap", !p.overlaps(q))
                    // Side by side or one above the other, always at least a gutter apart.
                    val gapX = maxOf(q.x - p.right, p.x - q.right)
                    val gapY = maxOf(q.y - p.bottom, p.y - q.bottom)
                    assertTrue("$where $i/$j gap", maxOf(gapX, gapY) >= g - eps)
                }
            }
        }
    }

    @Test fun onBookPagesNoPhotoBecomesAThinStrip() {
        AlbumLayouts.all.forEach { a ->
            boxes.take(4).forEach { (w, h, g) ->
                a.place(w, h, g).forEach { r -> assertTrue("${a.id} $w × $h: ${r.w / r.h}", r.w / r.h in 0.3..3.5) }
            }
        }
    }

    @Test fun anArrangementKeepsItsShapeAndSitsInTheMiddle() {
        // One landscape photo on a tall page: 3:2, as wide as the page, centred top to bottom.
        val wide = AlbumLayouts.of("1-wide")!!.place(115.0, 175.0, 5.0).single()
        assertEquals(115.0, wide.w, eps)
        assertEquals(115.0 / 1.5, wide.h, eps)
        assertEquals((175.0 - wide.h) / 2, wide.y, eps)
        // Two side by side on a square page fill it across; on a tall page they are shorter than the page.
        val side = AlbumLayouts.of("2-side")!!.place(115.0, 175.0, 5.0)
        assertEquals(side[0].h, side[1].h, eps)
        assertEquals(115.0 / 1.4, side[0].h, eps)
        assertEquals(5.0, side[1].x - side[0].right, eps)
        // Weights: the big one on top takes two thirds of what is left after the gutter.
        val top = AlbumLayouts.of("3-top")!!.place(115.0, 175.0, 5.0)
        assertEquals(2 * top[1].h, top[0].h, eps)
        assertEquals(115.0, top[0].w, eps)
        assertEquals(top[1].w, top[2].w, eps)
    }

    @Test fun fractionsAndNaturalHeights() {
        AlbumLayouts.all.forEach { a ->
            a.fractions(360.0, 480.0, 8.0).forEach { r -> assertTrue(a.id, r.x >= -eps && r.right <= 1 + eps && r.y >= -eps && r.bottom <= 1 + eps) }
            val h = a.heightFor(360.0, 8.0)
            assertTrue(a.id, h > 0)
            assertTrue(a.id, 360.0 / h in a.minAspect - eps..a.maxAspect + eps)
            // In a box of its natural height the arrangement fills it.
            val rs = a.place(360.0, h, 8.0)
            assertEquals(a.id, 0.0, rs.minOf { it.x }, 1e-6)
            assertEquals(a.id, h, rs.maxOf { it.bottom }, 1e-6)
        }
        assertTrue(AlbumLayouts.of("4-grid")!!.place(0.0, 100.0, 5.0).all { it.w == 0.0 })
    }

    @Test fun usualArrangementsAndSplittingPhotosIntoPagesWithoutGaps() {
        assertEquals("1-full", AlbumLayouts.fitting(1)?.id)
        assertEquals("6-grid", AlbumLayouts.fitting(5)?.id)
        assertEquals("9-grid", AlbumLayouts.fitting(7)?.id)
        assertNull(AlbumLayouts.fitting(10))
        assertNull(AlbumLayouts.exact(5))
        assertEquals(9, AlbumLayouts.max)
        val grid = AlbumLayouts.of("4-grid")!!
        assertEquals(listOf("4-grid", "4-grid", "3-top"), AlbumLayouts.split(11, grid).map { it.id })
        assertEquals(listOf(6, 1), AlbumLayouts.split(7).map { it.count })
        assertEquals(listOf(9, 9, 2), AlbumLayouts.split(20).map { it.count })
        assertEquals(listOf("2-side"), AlbumLayouts.split(2, AlbumLayouts.of("6-big")).map { it.id })
        assertTrue(AlbumLayouts.split(0).isEmpty())
        for (n in 1..40) {
            val parts = AlbumLayouts.split(n, AlbumLayouts.of("6-wide"))
            assertEquals("$n", n, parts.sumOf { it.count })
        }
    }
}

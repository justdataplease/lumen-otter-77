package com.justdataplease.boringtravel.globe

import com.justdataplease.boringtravel.geo.Countries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class RingClipperTest {
    private val cx = 500f
    private val cy = 400f
    private val r = 200f

    /** Records what the clipper draws: rings of points (rim arcs flattened), every straight line, every arc. */
    private inner class Recorder : FillSink, LineSink {
        val rings = ArrayList<MutableList<Pair<Float, Float>>>()
        val lines = ArrayList<FloatArray>()
        val segments = ArrayList<FloatArray>()
        var arcs = 0
        private var lx = 0f
        private var ly = 0f

        override fun moveTo(x: Float, y: Float) { rings += mutableListOf(x to y); lx = x; ly = y }
        override fun lineTo(x: Float, y: Float) {
            lines += floatArrayOf(lx, ly, x, y)
            rings.last() += x to y
            lx = x; ly = y
        }
        override fun rimArc(startRad: Float, sweepRad: Float) {
            arcs++
            val steps = (abs(sweepRad) / 0.01f).toInt().coerceAtLeast(1)
            for (i in 0..steps) {
                val a = startRad + sweepRad * i / steps
                val x = cx + r * cos(a)
                val y = cy + r * sin(a)
                rings.last() += x to y
                lx = x; ly = y
            }
        }
        override fun close() = Unit
        override fun segment(x1: Float, y1: Float, x2: Float, y2: Float) { segments += floatArrayOf(x1, y1, x2, y2) }

        fun area(): Double = rings.sumOf { ring ->
            var a = 0.0
            for (i in ring.indices) {
                val (x1, y1) = ring[i]
                val (x2, y2) = ring[(i + 1) % ring.size]
                a += x1.toDouble() * y2 - x2.toDouble() * y1
            }
            abs(a) / 2
        }

        fun onRim(x: Float, y: Float) = abs(hypot(x - cx, y - cy) - r) < 0.6f

        /**
         * A straight line from rim to rim that cuts into the disc is land smeared across it. (Short edges of a coast
         * running along the horizon also have both ends near the rim, but their middle stays at the rim.)
         */
        fun chords(): Int = lines.count { (x1, y1, x2, y2) ->
            onRim(x1, y1) && onRim(x2, y2) && hypot((x1 + x2) / 2 - cx, (y1 + y2) / 2 - cy) < r - 3f
        }

        fun maxRadius(): Float = rings.flatten().maxOfOrNull { (x, y) -> hypot(x - cx, y - cy) } ?: 0f
    }

    /** A ring of [n] points [radiusDeg] around ([lat], [lng]), as lng,lat pairs. */
    private fun circle(lat: Double, lng: Double, radiusDeg: Double, n: Int = 120, reverse: Boolean = false): DoubleArray {
        val p = Math.toRadians(lat)
        val l = Math.toRadians(lng)
        val d = Math.toRadians(radiusDeg)
        val out = DoubleArray(2 * n)
        for (k in 0 until n) {
            val b = 2 * Math.PI * (if (reverse) n - k else k) / n
            val lat2 = asin(sin(p) * cos(d) + cos(p) * sin(d) * cos(b))
            val lng2 = l + atan2(sin(b) * sin(d) * cos(p), cos(d) - sin(p) * sin(lat2))
            out[2 * k] = Math.toDegrees(lng2)
            out[2 * k + 1] = Math.toDegrees(lat2)
        }
        return out
    }

    private fun clip(ring: DoubleArray, view: Ortho): Recorder {
        val g = GlobeGeometry.from(listOf("XX" to listOf(ring)))
        val rec = Recorder()
        RingClipper().ring(g.xyz, g.ringStart[0], g.ringSize[0], view, rec, rec, g.seam, g.ringCap, 0)
        return rec
    }

    /** The cap's true area on screen, counted on a fine grid of the disc. */
    private fun expectedArea(view: Ortho, lat: Double, lng: Double, radiusDeg: Double): Double {
        val out = DoubleArray(2)
        val step = 0.5f
        var n = 0
        var y = cy - r
        while (y <= cy + r) {
            var x = cx - r
            while (x <= cx + r) {
                if (view.unproject(x, y, out) && GlobeMath.angleDeg(out[0], out[1], lat, lng) <= radiusDeg) n++
                x += step
            }
            y += step
        }
        return n * (step * step).toDouble()
    }

    @Test fun aRingFacingUsIsDrawnWhole() {
        val view = Ortho().set(0.0, 0.0, cx, cy, r)
        val rec = clip(circle(10.0, 10.0, 15.0, n = 40), view)
        assertEquals(1, rec.rings.size)
        assertEquals(41, rec.rings[0].size) // 40 points and the line back to the first
        assertEquals(0, rec.arcs)
        assertEquals(40, rec.segments.size)
        assertEquals(expectedArea(view, 10.0, 10.0, 15.0), rec.area(), expectedArea(view, 10.0, 10.0, 15.0) * 0.03)
    }

    @Test fun aRingBehindTheGlobeDrawsNothing() {
        val view = Ortho().set(0.0, 0.0, cx, cy, r)
        val rec = clip(circle(0.0, 180.0, 20.0), view)
        assertTrue(rec.rings.isEmpty())
        assertTrue(rec.segments.isEmpty())
    }

    @Test fun aRingAcrossTheHorizonFollowsTheRimWithoutSmearing() {
        // Half of a 20° cap is visible; the other half must not paint the disc.
        for (reverse in listOf(false, true)) {
            val view = Ortho().set(0.0, 0.0, cx, cy, r)
            val rec = clip(circle(0.0, 90.0, 20.0, reverse = reverse), view)
            assertEquals("reverse=$reverse", 1, rec.arcs)
            assertEquals("reverse=$reverse", 0, rec.chords())
            assertTrue(rec.maxRadius() <= r + 0.6f)
            val expected = expectedArea(view, 0.0, 90.0, 20.0)
            assertEquals("reverse=$reverse", expected, rec.area(), expected * 0.05)
            // Outlines stop at the horizon: none runs along the rim.
            assertTrue(rec.segments.none { s -> rec.onRim(s[0], s[1]) && rec.onRim(s[2], s[3]) && hypot(s[2] - s[0], s[3] - s[1]) > 1.5f })
        }
    }

    @Test fun aBigRingWrappingBehindTheGlobeKeepsOnlyItsVisibleCrescent() {
        // A 60° cap centred 110° away: a thin crescent shows; the rest of the disc stays sea.
        for ((viewLat, reverse) in listOf(0.0 to false, 20.0 to true, -35.0 to false)) {
            val view = Ortho().set(0.0, viewLat, cx, cy, r)
            val rec = clip(circle(viewLat, 110.0, 60.0, n = 180, reverse = reverse), view)
            assertEquals("lat $viewLat", 0, rec.chords())
            val expected = expectedArea(view, viewLat, 110.0, 60.0)
            assertEquals("lat $viewLat", expected, rec.area(), expected * 0.06)
        }
    }

    @Test fun graticuleLinesEndExactlyOnTheRim() {
        val view = Ortho().set(0.0, 0.0, cx, cy, r)
        val g = GlobeGeometry.from(emptyList())
        val rec = Recorder()
        val clipper = RingClipper()
        for (i in g.gridStart.indices) clipper.polyline(g.gridXyz, g.gridStart[i], g.gridSize[i], view, rec)
        assertTrue(rec.segments.isNotEmpty())
        rec.segments.forEach { s ->
            assertTrue(hypot(s[0] - cx, s[1] - cy) <= r + 0.6f)
            assertTrue(hypot(s[2] - cx, s[3] - cy) <= r + 0.6f)
        }
        // The equator from 90°W to 90°E: its two ends sit on the rim.
        assertTrue(rec.segments.count { rec.onRim(it[0], it[1]) || rec.onRim(it[2], it[3]) } >= 2)
    }

    @Test fun realCountriesNeverSmearFromAnySide() {
        val countries = Countries.parse(File("src/main/assets/geo/countries.json").readText())
        val g = GlobeGeometry.from(countries)
        val clipper = RingClipper(g.maxRing)
        for (lat in -60..60 step 20) {
            for (lon in -180 until 180 step 20) {
                val view = Ortho().set(lon.toDouble(), lat.toDouble(), cx, cy, r)
                val rec = Recorder()
                for (ring in 0 until g.ringCount) {
                    clipper.ring(g.xyz, g.ringStart[ring], g.ringSize[ring], view, rec, rec, g.seam, g.ringCap, ring * 4)
                }
                assertEquals("view $lat/$lon", 0, rec.chords())
                assertTrue("view $lat/$lon", rec.maxRadius() <= r + 0.6f)
                // Land never covers more than the disc (a wrong-way rim arc would add most of it).
                assertTrue("view $lat/$lon", rec.area() < Math.PI * r * r * 0.75)
            }
        }
    }

    @Test fun theDateLineAndSouthPoleCutsAreNotOutlined() {
        val countries = Countries.parse(File("src/main/assets/geo/countries.json").readText())
        val g = GlobeGeometry.from(countries)
        val ru = countries.outlines.indexOfFirst { it.code == "RU" }
        val aq = countries.outlines.indexOfFirst { it.code == "AQ" }
        fun seams(country: Int) = (0 until g.ringCount).filter { g.ringCountry[it] == country }
            .sumOf { ring -> (0 until g.ringSize[ring]).count { g.seam[g.ringStart[ring] + it] } }
        assertTrue(seams(ru) > 0)
        assertTrue(seams(aq) > 0)
        // Looking at the Bering Strait, Russia's outline has fewer segments with the cuts left out.
        val view = Ortho().set(180.0, 62.0, cx, cy, r)
        val with = Recorder()
        val without = Recorder()
        val clipper = RingClipper(g.maxRing)
        for (ring in 0 until g.ringCount) {
            if (g.ringCountry[ring] != ru) continue
            clipper.ring(g.xyz, g.ringStart[ring], g.ringSize[ring], view, null, with, g.seam, g.ringCap, ring * 4)
            clipper.ring(g.xyz, g.ringStart[ring], g.ringSize[ring], view, null, without, null, g.ringCap, ring * 4)
        }
        assertTrue(with.segments.size < without.segments.size)
    }

    @Test fun capsHoldEveryVertexOfTheirRing() {
        val countries = Countries.parse(File("src/main/assets/geo/countries.json").readText())
        val g = GlobeGeometry.from(countries)
        assertTrue(g.ringCount > 250)
        for (ring in 0 until g.ringCount) {
            val s = g.ringCap[ring * 4 + 3]
            if (s > 1f) continue
            val cosR = kotlin.math.sqrt((1 - s * s).toDouble()).toFloat()
            for (i in 0 until g.ringSize[ring]) {
                val b = 3 * (g.ringStart[ring] + i)
                val dot = g.ringCap[ring * 4] * g.xyz[b] + g.ringCap[ring * 4 + 1] * g.xyz[b + 1] + g.ringCap[ring * 4 + 2] * g.xyz[b + 2]
                assertTrue(dot >= cosR - 1e-3f)
            }
        }
    }
}

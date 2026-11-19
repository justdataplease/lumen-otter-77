package com.justdataplease.boringtravel.tripcal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TripColorsTest {
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun span(id: String, start: LocalDate, end: LocalDate) = TripSpan(id, id, start, end)

    /** Two ids that want the same colour. */
    private val clash: Pair<String, String> = run {
        val ids = (0 until 200).map { "trip-$it" }
        val first = ids.first()
        first to ids.drop(1).first { TripColors.preferred(it) == TripColors.preferred(first) }
    }

    @Test fun aTripsOwnColourComesFromItsIdAlone() {
        val ids = (0 until 500).map { "id-$it-${it * 7}" }
        val once = ids.map { TripColors.preferred(it) }
        assertEquals(once, ids.map { TripColors.preferred(it) })
        assertTrue(once.all { it in 0 until TripColors.SIZE })
        // Spread over the whole palette, not stuck on a few colours.
        assertEquals(TripColors.SIZE, once.toSet().size)
        // FNV-1a, fixed: the same on every phone and run.
        assertEquals(Math.floorMod(0xE40C292C.toInt(), 6), TripColors.preferred("a"))
        assertEquals(0, TripColors.preferred("a"))
    }

    @Test fun otherTripsNeverRepaintATrip() {
        val a = span("lisbon", d(3, 1), d(3, 9))
        val b = span("kyoto", d(5, 10), d(5, 20))
        val before = TripColors.assign(listOf(a, b))
        assertEquals(TripColors.preferred("lisbon"), before["lisbon"])
        assertEquals(TripColors.preferred("kyoto"), before["kyoto"])
        val after = TripColors.assign(listOf(span("new", d(1, 1), d(1, 4)), b, a, span("later", d(9, 1), d(9, 2))))
        assertEquals(before["lisbon"], after["lisbon"])
        assertEquals(before["kyoto"], after["kyoto"])
    }

    @Test fun overlappingTripsNeverShareAColour() {
        val (x, y) = clash
        val first = span(x, d(4, 1), d(4, 10))
        val second = span(y, d(4, 8), d(4, 12))
        val colours = TripColors.assign(listOf(second, first))
        assertEquals(TripColors.preferred(x), colours[x]) // the earlier keeps its own
        assertNotEquals(colours[x], colours[y])
        assertEquals((TripColors.preferred(y) + 1) % TripColors.SIZE, colours[y])
    }

    @Test fun backToBackTripsDifferButTripsDaysApartKeepTheirOwn() {
        val (x, y) = clash
        val nextDay = TripColors.assign(listOf(span(x, d(4, 1), d(4, 10)), span(y, d(4, 11), d(4, 12))))
        assertNotEquals(nextDay[x], nextDay[y])
        val apart = TripColors.assign(listOf(span(x, d(4, 1), d(4, 10)), span(y, d(4, 12), d(4, 14))))
        assertEquals(apart[x], apart[y])
        assertTrue(TripColors.touches(span("p", d(4, 1), d(4, 10)), span("q", d(4, 11), d(4, 12))))
        assertTrue(!TripColors.touches(span("p", d(4, 1), d(4, 10)), span("q", d(4, 12), d(4, 13))))
    }

    @Test fun aCrowdStillGetsAColourEach() {
        val crowd = (0 until 8).map { span("c$it", d(6, 1), d(6, 5)) }
        val colours = TripColors.assign(crowd)
        assertEquals(8, colours.size)
        assertEquals(TripColors.SIZE, colours.values.take(TripColors.SIZE).toSet().size)
        assertTrue(colours.values.all { it in 0 until TripColors.SIZE })
    }
}

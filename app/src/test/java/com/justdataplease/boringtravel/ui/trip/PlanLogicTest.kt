package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.data.db.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanLogicTest {
    private val d1 = LocalDate.of(2027, 6, 17)

    private fun item(id: String, pos: Double, time: Int? = null, day: LocalDate? = d1) =
        PlanItem(id, "t", day, pos, timeMinutes = time, title = id, createdAt = 0, updatedAt = 0)

    private val ferry = item("ferry", 1.0, 7 * 60 + 30)
    private val bags = item("bags", 2.0, 10 * 60)
    private val sunset = item("sunset", 3.0, 19 * 60 + 40)
    private val note = item("note", 4.0)
    private val day = listOf(ferry, bags, sunset, note)

    private fun order(items: List<PlanItem>) = items.sortedBy { it.position }.map { it.id }

    @Test fun positionsGoBetweenNeighbours() {
        assertEquals(1.0, PlanOrder.positionAt(emptyList(), 0), 0.0)
        assertEquals(0.0, PlanOrder.positionAt(day, 0), 0.0)
        assertEquals(1.5, PlanOrder.positionAt(day, 1), 0.0)
        assertEquals(5.0, PlanOrder.end(day), 0.0)
    }

    @Test fun aTimedItemLandsInTimeOrder() {
        val lunch = item("lunch", 99.0, 12 * 60 + 30)
        assertEquals(listOf("ferry", "bags", "lunch", "sunset", "note"), order(day + lunch.copy(position = PlanOrder.forTime(day, 12 * 60 + 30))))
        val early = item("early", 99.0, 6 * 60)
        assertEquals(listOf("early", "ferry", "bags", "sunset", "note"), order(day + early.copy(position = PlanOrder.forTime(day, 6 * 60))))
        // Later than everything with a time: after the last timed item, before the untimed note.
        val late = item("late", 99.0, 22 * 60)
        assertEquals(listOf("ferry", "bags", "sunset", "late", "note"), order(day + late.copy(position = PlanOrder.forTime(day, 22 * 60))))
        // No times at all: at the end.
        val plain = listOf(item("a", 1.0), item("b", 2.0))
        assertEquals(3.0, PlanOrder.forTime(plain, 600), 0.0)
    }

    @Test fun crowdedPositionsAreRenumbered() {
        var list = listOf(item("a", 1.0), item("b", 2.0))
        // Keep pushing a new item in right after "a": the gap halves each time.
        repeat(40) { i ->
            val others = list
            list = others + item("n$i", PlanOrder.positionAt(others, 1))
        }
        assertTrue(PlanOrder.crowded(list))
        val renumbered = PlanOrder.renumber(list)
        assertFalse(PlanOrder.crowded(renumbered))
        assertEquals(order(list), order(renumbered))
        assertEquals((1..42).map { it.toDouble() }, renumbered.map { it.position })
    }

    @Test fun daysCoverTheTripAndStrayItems() {
        val end = d1.plusDays(2)
        val stray = item("stray", 1.0, day = d1.plusDays(5))
        val idea = item("idea", 1.0, day = null)
        val days = PlanDays.days(d1, end, listOf(stray, idea, ferry))
        assertEquals(listOf(d1, d1.plusDays(1), d1.plusDays(2), d1.plusDays(5)), days)
        assertEquals(listOf(d1.plusDays(5)), PlanDays.days(null, null, listOf(stray, idea)))
        assertEquals(3, PlanDays.number(d1, end, end))
        assertNull(PlanDays.number(d1, end, d1.plusDays(5)))
    }

    @Test fun theFirstDayShownIsTodayDuringTheTrip() {
        val days = PlanDays.days(d1, d1.plusDays(9), emptyList())
        assertEquals(d1.plusDays(3), PlanDays.initial(days, d1.plusDays(3)))
        assertEquals(d1, PlanDays.initial(days, d1.minusDays(30)))
        assertNull(PlanDays.initial(emptyList(), d1))
    }

    @Test fun movingTheStartMovesDatedItemsOnly() {
        val idea = item("idea", 1.0, day = null)
        val moved = PlanDays.shift(listOf(ferry, idea, item("d3", 1.0, day = d1.plusDays(2))), d1, d1.plusDays(7))
        assertEquals(listOf(d1.plusDays(7), d1.plusDays(9)), moved.map { it.day })
        assertTrue(PlanDays.shift(listOf(ferry), d1, d1).isEmpty())
        assertTrue(PlanDays.shift(listOf(ferry), null, d1).isEmpty())
    }
}

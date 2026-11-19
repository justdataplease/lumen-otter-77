package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanSpan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Dragging a plan item (owner, 2026-11-12: "drag to change position. If change position to another day automatically update the time"). */
class PlanDropTest {
    private val sat = LocalDate.of(2027, 3, 13)
    private val sun = sat.plusDays(1)
    private var locale: Locale = Locale.getDefault()

    @Before fun english() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After fun restore() = Locale.setDefault(locale)

    private fun item(id: String, pos: Double, time: Int? = null, day: LocalDate? = sat, endDays: Int? = null, endMinutes: Int? = null, kind: String = "place") =
        PlanItem(id, "t", day, pos, timeMinutes = time, kind = kind, title = id, createdAt = 0, updatedAt = 0, endDays = endDays, endMinutes = endMinutes)

    private val museum = item("museum", 1.0, 10 * 60)
    private val lunch = item("lunch", 2.0, 13 * 60)
    private val walk = item("walk", 3.0) // no time
    private val dinner = item("dinner", 4.0, 20 * 60)

    // ------------------------------------------------------------------ fit

    @Test fun aTimeThatFitsStays() {
        assertEquals(11 * 60, PlanDrop.fit(11 * 60, 10 * 60, 13 * 60))
        assertEquals(10 * 60, PlanDrop.fit(10 * 60, 10 * 60, 13 * 60))
        assertEquals(9 * 60, PlanDrop.fit(9 * 60, null, null))
        assertEquals(22 * 60, PlanDrop.fit(22 * 60, 20 * 60, null))
        assertEquals(8 * 60, PlanDrop.fit(8 * 60, null, 9 * 60))
    }

    @Test fun aTimeThatNoLongerFitsGoesBetweenItsNeighbours() {
        // The middle, to 5 minutes.
        assertEquals(11 * 60 + 30, PlanDrop.fit(18 * 60, 10 * 60, 13 * 60))
        assertEquals(10 * 60 + 25, PlanDrop.fit(18 * 60, 10 * 60 + 10, 10 * 60 + 41))
        // Never outside them, even when they are less than 5 minutes apart.
        assertEquals(10 * 60, PlanDrop.fit(18 * 60, 10 * 60, 10 * 60 + 2))
        // One side only: an hour after the previous, an hour before the next.
        assertEquals(21 * 60, PlanDrop.fit(9 * 60, 20 * 60, null))
        assertEquals(8 * 60, PlanDrop.fit(12 * 60, null, 9 * 60))
        // Within the day.
        assertEquals(24 * 60 - 1, PlanDrop.fit(9 * 60, 23 * 60 + 30, null))
        assertEquals(0, PlanDrop.fit(9 * 60, null, 30))
    }

    @Test fun anEndMovesWithItsStartAndKeepsTheLength() {
        val show = item("show", 1.0, 19 * 60, endMinutes = 22 * 60)
        val earlier = PlanDrop.withTime(show, 17 * 60)
        assertEquals(17 * 60, earlier.timeMinutes)
        assertEquals(20 * 60, earlier.endMinutes)
        assertNull(earlier.endDays)
        // Later in the evening: the end crosses midnight.
        val late = PlanDrop.withTime(show, 22 * 60 + 30)
        assertEquals(1, late.endDays)
        assertEquals(60 + 30, late.endMinutes)
        assertEquals(PlanSpan.minutes(show), PlanSpan.minutes(late))
        // A stay of nights without a check-out time keeps its nights.
        val hostal = item("hostal", 1.0, 15 * 60, endDays = 3, kind = "stay")
        assertEquals(3, PlanDrop.withTime(hostal, 12 * 60).endDays)
    }

    // ------------------------------------------------------------------ drop

    @Test fun anUntimedItemTakesThePlaceBetweenItsNewNeighbours() {
        val saves = PlanDrop.drop(walk, sat, listOf(museum), listOf(lunch, dinner))
        assertEquals(1, saves.size)
        assertEquals(1.5, saves.single().position, 0.0)
        assertNull(saves.single().timeMinutes)
        assertEquals(listOf("museum", "walk", "lunch", "dinner"), PlanSpan.dayOrder(listOf(museum, lunch, dinner) + saves).map { it.id })
    }

    @Test fun aTimedItemDroppedOutOfOrderGetsATimeThatFits() {
        // Dinner (20:00) dragged between the museum (10:00) and lunch (13:00).
        val saves = PlanDrop.drop(dinner, sat, listOf(museum), listOf(lunch, walk))
        val moved = saves.single()
        assertEquals(11 * 60 + 30, moved.timeMinutes)
        assertEquals(listOf("museum", "dinner", "lunch", "walk"), PlanSpan.dayOrder(listOf(museum, lunch, walk) + moved).map { it.id })
    }

    @Test fun anUntimedNeighbourIsSkippedForTheTime() {
        // Museum (10:00) dragged under the walk: the times around it are lunch (13:00) before and dinner (20:00) after.
        val saves = PlanDrop.drop(museum, sat, listOf(lunch, walk), listOf(dinner))
        assertEquals(16 * 60 + 30, saves.single().timeMinutes)
    }

    @Test fun onAnotherDayTheDayChangesAndAFittingTimeStays() {
        val brunch = item("brunch", 1.0, 11 * 60, day = sun)
        val saves = PlanDrop.drop(lunch, sun, listOf(brunch), emptyList())
        val moved = saves.single()
        assertEquals(sun, moved.day)
        assertEquals(13 * 60, moved.timeMinutes)
        assertEquals(2.0, moved.position, 0.0)
        assertEquals("Moved to Sun 14 · 13:00", PlanDrop.notice(lunch, moved))
    }

    @Test fun anEndFollowsItsItemToAnotherDay() {
        val hostal = item("hostal", 1.0, 15 * 60, endDays = 2, endMinutes = 11 * 60, kind = "stay")
        val moved = PlanDrop.drop(hostal, sun, emptyList(), emptyList()).single()
        assertEquals(sun, moved.day)
        assertEquals(sun.plusDays(2), PlanSpan.endDate(moved))
        assertEquals("Moved to Sun 14 · 15:00", PlanDrop.notice(hostal, moved))
    }

    @Test fun ontoAnEmptyDayOrTheIdeas() {
        assertEquals(1.0, PlanDrop.drop(walk, sun, emptyList(), emptyList()).single().position, 0.0)
        val idea = PlanDrop.drop(walk, null, emptyList(), listOf(item("other", 5.0, day = null))).single()
        assertNull(idea.day)
        assertEquals(4.0, idea.position, 0.0)
        assertEquals("Moved to Ideas", PlanDrop.notice(walk, idea))
    }

    @Test fun droppedBackInPlaceChangesNothing() {
        assertTrue(PlanDrop.drop(lunch, sat, listOf(museum), listOf(walk, dinner)).isEmpty())
        // Same day, same time: no notice for a new place within the day.
        assertNull(PlanDrop.notice(walk, walk.copy(position = 9.0)))
    }

    @Test fun neighboursOutOfStepAreRenumberedInTheNewOrder() {
        // An older plan: positions that don't follow the order the List shows.
        val a = item("a", 5.0)
        val b = item("b", 1.0)
        val c = item("c", 3.0)
        val saves = PlanDrop.drop(c, sat, listOf(a), listOf(b))
        assertEquals("c", saves.first().id)
        val all = (listOf(a, b, c).associateBy { it.id } + saves.associateBy { it.id }).values
        assertEquals(listOf("a", "c", "b"), all.sortedBy { it.position }.map { it.id })
    }

    @Test fun crowdedNeighboursAreRenumbered() {
        val a = item("a", 1.0)
        val b = item("b", 1.0 + 1e-7)
        val saves = PlanDrop.drop(walk, sat, listOf(a), listOf(b))
        assertEquals(listOf(1.0, 2.0, 3.0), (saves.associateBy { it.id }.let { m -> listOf(m["a"] ?: a, m["walk"]!!, m["b"] ?: b) }).map { it.position })
    }

    // ------------------------------------------------------------------ target

    private fun line(i: PlanItem, end: Boolean = false) = PlanListLogic.Row.Line(PlanLine(com.justdataplease.boringtravel.model.PlanStep(i, end), null))

    @Test fun theTargetIsTheDayUnderTheNearestHeader() {
        val hostal = item("hostal", 9.0, 15 * 60, endDays = 1, endMinutes = 11 * 60, kind = "stay")
        val brunch = item("brunch", 1.0, 12 * 60, day = sun)
        val rows = listOf(
            PlanListLogic.Row.DayHead(sat, 1), line(museum), line(lunch),
            PlanListLogic.Row.DayHead(sun, 2), line(hostal, end = true), line(walk), line(brunch),
            PlanListLogic.Row.Folded(sun.plusDays(1), sun.plusDays(3)),
            PlanListLogic.Row.IdeasHead(0), PlanListLogic.Row.IdeasEmpty,
        )
        val t = PlanDrop.target(rows, "i-walk")!!
        assertEquals(sun, t.day)
        // The check-out row is not a neighbour.
        assertEquals(emptyList<String>(), t.before.map { it.id })
        assertEquals(listOf("brunch"), t.after.map { it.id })

        val folded = PlanDrop.target(rows.toMutableList().apply { add(7, removeAt(5)) }, "i-walk")!!
        assertEquals("a folded run counts as its first day", sun.plusDays(1), folded.day)
        assertTrue(folded.before.isEmpty() && folded.after.isEmpty())

        val idea = PlanDrop.target(rows.toMutableList().apply { add(8, removeAt(5)) }, "i-walk")!!
        assertNull(idea.day)

        // Above every header: the first day, first.
        val top = PlanDrop.target(rows.toMutableList().apply { add(0, removeAt(5)) }, "i-walk")!!
        assertEquals(sat, top.day)
        assertEquals(listOf("museum", "lunch"), top.after.map { it.id })
        assertNull(PlanDrop.target(rows, "i-nothing"))
    }

    @Test fun withoutHeadersEveryRowIsAnIdea() {
        val ideas = listOf(item("x", 1.0, day = null), item("y", 2.0, day = null), item("z", 3.0, day = null))
        val t = PlanDrop.target(ideas.map { line(it) }, "i-y")!!
        assertNull(t.day)
        assertEquals(listOf("x"), t.before.map { it.id })
        assertEquals(listOf("z"), t.after.map { it.id })
    }

    @Test fun nothingPlannedLinesStayPut() {
        assertFalse(PlanDrop.isTarget(PlanListLogic.Row.DayEmpty(sat)))
        assertFalse(PlanDrop.isTarget(PlanListLogic.Row.IdeasEmpty))
        assertTrue(PlanDrop.isTarget(PlanListLogic.Row.DayHead(sat, 1)))
        assertTrue(PlanDrop.isTarget(line(walk)))
    }
}

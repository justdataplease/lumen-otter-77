package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.trip.PlanListLogic.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class PlanViewLogicTest {
    private val start = LocalDate.of(2027, 1, 28)
    private val end = LocalDate.of(2027, 2, 3)

    private fun item(id: String, day: LocalDate?, pos: Double = 1.0, time: Int? = null) =
        PlanItem(id, "t", day, pos, timeMinutes = time, title = id, createdAt = 0, updatedAt = 0)

    // ---- List

    @Test fun daysInOrderWithTheirItemsInTimelineOrder() {
        val items = listOf(
            item("b", start, pos = 2.0), item("a", start, pos = 1.0),
            item("c", start.plusDays(1)), item("idea", null),
        )
        val s = PlanListLogic.sections(start, start.plusDays(1), items, emptyMap())
        assertEquals(3, s.size)
        val d1 = s[0] as Section.Day
        assertEquals(start, d1.day)
        assertEquals(1, d1.number)
        assertEquals(listOf("a", "b"), d1.lines.map { it.item.id })
        assertEquals(2, (s[1] as Section.Day).number)
        assertEquals(listOf("idea"), (s[2] as Section.Ideas).lines.map { it.item.id })
    }

    @Test fun runsOfEmptyDaysFoldIntoOneLine() {
        // 28 Jan – 3 Feb: items on day 1 and day 5 only.
        val items = listOf(item("x", start), item("y", start.plusDays(4)))
        val s = PlanListLogic.sections(start, end, items, emptyMap())
        assertEquals(listOf("Day", "Empty", "Day", "Empty"), s.map { it::class.simpleName })
        assertEquals(Section.Empty(start.plusDays(1), start.plusDays(3)), s[1])
        assertEquals(Section.Empty(start.plusDays(5), end), s[3])
        // No ideas, so no Ideas section.
        assertFalse(s.any { it is Section.Ideas })
    }

    @Test fun anItemOutsideTheDatesIsItsOwnDayAndBreaksTheRun() {
        val outside = LocalDate.of(2027, 3, 10)
        val s = PlanListLogic.sections(start, start.plusDays(1), listOf(item("late", outside)), emptyMap())
        assertEquals(Section.Empty(start, start.plusDays(1)), s[0])
        val d = s[1] as Section.Day
        assertEquals(outside, d.day)
        assertNull(d.number)
    }

    @Test fun legsFoldIntoTheirDestinationsVehicleAndAutoShowsNothing() {
        val items = listOf(item("a", start, 1.0), item("b", start, 2.0), item("c", start, 3.0))
        val s = PlanListLogic.sections(start, start, items, mapOf("b" to Vehicle.Train, "c" to Vehicle.Auto))
        val lines = (s[0] as Section.Day).lines
        assertEquals(listOf(null, Vehicle.Train, null), lines.map { it.leg })
    }

    @Test fun anEmptyPlanWithoutDatesStillHasIdeas() {
        val s = PlanListLogic.sections(null, null, emptyList(), emptyMap())
        assertEquals(listOf(Section.Ideas(emptyList())), s)
        // With dates and nothing planned: one folded line for the whole trip.
        assertEquals(listOf(Section.Empty(start, end)), PlanListLogic.sections(start, end, emptyList(), emptyMap()))
    }

    @Test fun todayAndTheDayAskedForKeepTheirHeaderWithNothingOnThem() {
        // Items on day 1 only; today is day 3 and day 6 was asked for.
        val today = start.plusDays(2)
        val asked = start.plusDays(5)
        val s = PlanListLogic.sections(start, end, listOf(item("x", start)), emptyMap(), keep = setOf(today, asked))
        assertEquals(
            listOf(
                Section.Day(start, 1, s.filterIsInstance<Section.Day>().first().lines),
                Section.Empty(start.plusDays(1), start.plusDays(1)),
                Section.Day(today, 3, emptyList()),
                Section.Empty(start.plusDays(3), start.plusDays(4)),
                Section.Day(asked, 6, emptyList()),
                Section.Empty(end, end),
            ),
            s,
        )
        // A day to keep outside the plan's days changes nothing.
        assertEquals(
            PlanListLogic.sections(start, end, emptyList(), emptyMap()),
            PlanListLogic.sections(start, end, emptyList(), emptyMap(), keep = setOf(end.plusDays(10))),
        )
    }

    @Test fun rowsListEachDayItsItemsAndIdeasUnderStableKeys() {
        val items = listOf(item("a", start), item("b", start, pos = 2.0), item("idea", null))
        val sections = PlanListLogic.sections(start, start.plusDays(3), items, emptyMap(), keep = setOf(start.plusDays(1)))
        val rows = PlanListLogic.rows(sections)
        assertEquals(
            listOf(
                PlanListLogic.Row.DayHead(start, 1),
                PlanListLogic.Row.Line(PlanLine(items[0], null)),
                PlanListLogic.Row.Line(PlanLine(items[1], null)),
                PlanListLogic.Row.DayHead(start.plusDays(1), 2),
                PlanListLogic.Row.DayEmpty(start.plusDays(1)),
                PlanListLogic.Row.Folded(start.plusDays(2), start.plusDays(3)),
                PlanListLogic.Row.IdeasHead(1),
                PlanListLogic.Row.Line(PlanLine(items[2], null)),
            ),
            rows,
        )
        // Keys are unique, so the List keeps its place when it is expanded or collapsed.
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        // A plan without days has its own Ideas header; nothing yet says "Places and notes for later".
        assertEquals(listOf(PlanListLogic.Row.IdeasEmpty), PlanListLogic.rows(PlanListLogic.sections(null, null, emptyList(), emptyMap()), ideasHeader = false))
    }

    @Test fun theListOpensOnTheDayAskedForElseTodayDuringTheTrip() {
        val days = PlanDays.days(start, end, emptyList())
        val today = start.plusDays(2)
        val asked = start.plusDays(4)
        assertEquals(PlanListLogic.Focus.Day(asked), PlanListLogic.focus(asked.toEpochDay().toString(), days, today))
        assertEquals(PlanListLogic.Focus.Ideas, PlanListLogic.focus(PLAN_IDEAS, days, today))
        assertEquals(PlanListLogic.Focus.Day(today), PlanListLogic.focus(null, days, today))
        // A day outside the plan, or nonsense, falls back to today.
        assertEquals(PlanListLogic.Focus.Day(today), PlanListLogic.focus(end.plusDays(30).toEpochDay().toString(), days, today))
        assertEquals(PlanListLogic.Focus.Day(today), PlanListLogic.focus("soon", days, today))
        // Before or after the trip: the top.
        assertNull(PlanListLogic.focus(null, days, start.minusDays(10)))
        assertNull(PlanListLogic.focus(null, emptyList(), today))
    }

    @Test fun theRowToScrollToIsTheDaysHeaderOrTheFoldedLineItIsIn() {
        val items = listOf(item("a", start), item("c", start.plusDays(4)), item("idea", null))
        val rows = PlanListLogic.rows(PlanListLogic.sections(start, end, items, emptyMap()))
        // Day 1, a, Days 2–4, Day 5, c, Days 6–7, Ideas, idea
        assertEquals(0, PlanListLogic.indexOf(rows, PlanListLogic.Focus.Day(start)))
        assertEquals(2, PlanListLogic.indexOf(rows, PlanListLogic.Focus.Day(start.plusDays(2))))
        assertEquals(3, PlanListLogic.indexOf(rows, PlanListLogic.Focus.Day(start.plusDays(4))))
        assertEquals(5, PlanListLogic.indexOf(rows, PlanListLogic.Focus.Day(end)))
        assertEquals(6, PlanListLogic.indexOf(rows, PlanListLogic.Focus.Ideas))
        assertNull(PlanListLogic.indexOf(rows, null))
        assertNull(PlanListLogic.indexOf(rows, PlanListLogic.Focus.Day(end.plusDays(1))))
        // Without an Ideas header (a plan without days) the Ideas are the top already.
        val noDays = PlanListLogic.rows(PlanListLogic.sections(null, null, listOf(item("idea", null)), emptyMap()), ideasHeader = false)
        assertNull(PlanListLogic.indexOf(noDays, PlanListLogic.Focus.Ideas))
    }

    // ---- Calendar

    @Test fun aTripAcrossTwoMonthsShowsBoth() {
        val days = PlanDays.days(start, end, emptyList())
        val months = PlanCalendarLogic.months(days, DayOfWeek.MONDAY)
        assertEquals(listOf(YearMonth.of(2027, 1), YearMonth.of(2027, 2)), months.map { it.month })
    }

    @Test fun anItemFarOutsideTheDatesAddsOnlyItsMonth() {
        val days = PlanDays.days(start, end, listOf(item("far", LocalDate.of(2027, 6, 1))))
        val months = PlanCalendarLogic.months(days, DayOfWeek.MONDAY).map { it.month }
        assertEquals(listOf(YearMonth.of(2027, 1), YearMonth.of(2027, 2), YearMonth.of(2027, 6)), months)
    }

    @Test fun weeksStartOnMondayWithBlanksAroundTheMonth() {
        // January 2027 starts on a Friday and ends on a Sunday.
        val weeks = PlanCalendarLogic.weeks(YearMonth.of(2027, 1), DayOfWeek.MONDAY)
        assertEquals(5, weeks.size)
        assertTrue(weeks.all { it.days.size == 7 })
        assertEquals(listOf(null, null, null, null), weeks.first().days.take(4))
        assertEquals(LocalDate.of(2027, 1, 1), weeks.first().days[4])
        assertEquals(LocalDate.of(2027, 1, 31), weeks.last().days[6])
        assertEquals(31, weeks.sumOf { w -> w.days.count { it != null } })
    }

    @Test fun weeksStartOnSundayWhereTheLocaleSaysSo() {
        val weeks = PlanCalendarLogic.weeks(YearMonth.of(2027, 1), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2027, 1, 1), weeks.first().days[5])
        // 31 January is a Sunday: it opens a sixth week of its own.
        assertEquals(6, weeks.size)
        assertEquals(LocalDate.of(2027, 1, 31), weeks.last().days[0])
        assertEquals(List(6) { null }, weeks.last().days.drop(1))
        assertEquals(
            listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
            PlanCalendarLogic.weekdays(DayOfWeek.SUNDAY),
        )
    }

    @Test fun everyCellSitsUnderItsWeekday() {
        for (first in DayOfWeek.entries) {
            val header = PlanCalendarLogic.weekdays(first)
            PlanCalendarLogic.weeks(YearMonth.of(2027, 2), first).forEach { w ->
                w.days.forEachIndexed { i, d -> if (d != null) assertEquals(header[i], d.dayOfWeek) }
            }
        }
    }

    @Test fun tripDaysAndCounts() {
        assertTrue(PlanCalendarLogic.inTrip(start, end, start))
        assertTrue(PlanCalendarLogic.inTrip(start, end, end))
        assertFalse(PlanCalendarLogic.inTrip(start, end, end.plusDays(1)))
        assertFalse(PlanCalendarLogic.inTrip(null, null, start))
        val counts = PlanCalendarLogic.counts(listOf(item("a", start), item("b", start), item("c", end), item("i", null)))
        assertEquals(mapOf(start to 2, end to 1), counts)
    }

    @Test fun anItemEndingOnAnotherDayHasARowThereThatOpensTheSameItem() {
        val hotel = item("hotel", start, time = 20 * 60).copy(kind = "stay", endDays = 3, endMinutes = 11 * 60)
        val museum = item("museum", start.plusDays(3), time = 16 * 60)
        val s = PlanListLogic.sections(start, end, listOf(hotel, museum), mapOf("hotel~end" to Vehicle.Walk))
        val checkOut = s.filterIsInstance<Section.Day>().single { it.day == start.plusDays(3) }
        assertEquals(listOf(true, false), checkOut.lines.map { it.end })
        assertEquals(hotel, checkOut.lines[0].item)
        assertEquals(Vehicle.Walk, checkOut.lines[0].leg)
        val keys = PlanListLogic.rows(s).map { it.key }
        assertTrue(keys.contains("i-hotel") && keys.contains("i-hotel~end"))
        assertEquals(keys.size, keys.toSet().size)
        // The days between are empty and fold; the calendar's dots count the check-out day.
        assertEquals(2, PlanCalendarLogic.counts(listOf(hotel, museum))[start.plusDays(3)])
        // Off in the item: no row.
        val off = PlanListLogic.sections(start, end, listOf(hotel.copy(endRow = false), museum), emptyMap())
        assertEquals(listOf(false), off.filterIsInstance<Section.Day>().single { it.day == start.plusDays(3) }.lines.map { it.end })
    }
}

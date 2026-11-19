package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.clip.PlanRoute
import com.justdataplease.boringtravel.clip.TravelModes
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.time.localDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The plan's order, days and list rows as this app works them out, against what the Android app's rules give. */
class PlanLogicTest {
    private val start = localDate(2027, 4, 1)
    private val trip = Trip("t", "Naxos", startDate = start, endDate = start.plusDays(6), createdAt = 1L, updatedAt = 1L)
    private fun item(id: String, day: Int?, position: Double = 1.0, time: Int? = null, endDays: Int? = null, endMinutes: Int? = null) = PlanItem(
        id = id, tripId = "t", day = day?.let { start.plusDays(it.toLong()) }, position = position, timeMinutes = time,
        endDays = endDays, endMinutes = endMinutes, title = id, createdAt = 1L, updatedAt = 1L,
    )

    /** The List's rows as the Plan tab makes them: no legs, Ideas' header only when the plan has days. */
    private fun rows(trip: Trip, items: List<PlanItem>, keep: Set<com.justdataplease.boringtravel.time.LocalDate> = emptySet()) =
        PlanListLogic.rows(PlanListLogic.sections(trip.startDate, trip.endDate, items, emptyMap(), keep), ideasHeader = PlanDays.days(trip.startDate, trip.endDate, items).isNotEmpty())

    @Test
    fun theDatePickersDaysAreUtcMidnights() {
        // As the Android screens convert them with java.time, before 1970 too.
        for (day in listOf(localDate(1969, 12, 31), localDate(1970, 1, 1), localDate(2027, 4, 1), localDate(2028, 2, 29))) {
            val millis = day.utcMillis()
            // On the desktop the engine's day is java.time's own.
            assertEquals(day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), millis)
            assertEquals(day, utcDay(millis))
            assertEquals(day, utcDay(millis + 86_399_999L))
        }
    }

    @Test
    fun anItemTakesItsPlaceByTime() {
        val day = listOf(item("a", 0, 1.0, time = 9 * 60), item("b", 0, 2.0, time = 13 * 60), item("c", 0, 3.0))
        assertEquals(1.5, PlanOrder.forTime(day, 11 * 60))
        assertEquals(0.0, PlanOrder.forTime(day, 8 * 60))
        assertEquals(2.5, PlanOrder.forTime(day, 20 * 60))
        assertEquals(4.0, PlanOrder.end(day))
        assertTrue(PlanOrder.crowded(listOf(item("x", 0, 1.0), item("y", 0, 1.0 + 1e-9))))
        assertEquals(listOf(1.0, 2.0), PlanOrder.renumber(listOf(item("y", 0, 7.5), item("x", 0, 7.0))).map { it.position })
    }

    @Test
    fun emptyDaysFoldAndTodayAndNamedDaysStay() {
        val items = listOf(item("ferry", 0), item("beach", 4), item("idea", null))
        val rows = rows(trip, items, keep = setOf(start.plusDays(2)))
        assertEquals(
            listOf(
                PlanListLogic.Row.DayHead(start, 1), "ferry",
                PlanListLogic.Row.Folded(start.plusDays(1), start.plusDays(1)),
                PlanListLogic.Row.DayHead(start.plusDays(2), 3), PlanListLogic.Row.DayEmpty(start.plusDays(2)),
                PlanListLogic.Row.Folded(start.plusDays(3), start.plusDays(3)),
                PlanListLogic.Row.DayHead(start.plusDays(4), 5), "beach",
                PlanListLogic.Row.Folded(start.plusDays(5), start.plusDays(6)),
                PlanListLogic.Row.IdeasHead(1), "idea",
            ),
            rows.map { if (it is PlanListLogic.Row.Line) it.line.item.id else it },
        )
    }

    @Test
    fun anItemOutsideTheDatesAndAnEndOnALaterDayHaveTheirDays() {
        // A stay of two nights from day 7 ends after the trip; an item parked before it keeps its day too.
        val items = listOf(item("stay", 6, time = 15 * 60, endDays = 2, endMinutes = 11 * 60), item("early", -3))
        val days = PlanDays.days(trip.startDate, trip.endDate, items)
        assertEquals(start.minusDays(3), days.first())
        assertEquals(start.plusDays(8), days.last())
        assertEquals(1 + 7 + 1, days.size)
        assertEquals(null, PlanDays.number(trip.startDate, trip.endDate, start.plusDays(8)))
        val rows = rows(trip, items)
        assertTrue(rows.any { it is PlanListLogic.Row.Line && it.line.end && it.line.item.id == "stay" })
        // A plan without days: only the Ideas, without their header (the page shows its own).
        val someday = trip.copy(startDate = null, endDate = null)
        assertEquals(listOf<Any>(PlanListLogic.Row.IdeasEmpty), rows(someday, emptyList()))
    }

    @Test
    fun aTripIdeasTravelShowsTheVehicleItWasSavedWith() {
        // The trip page reads a travel row's vehicle from the leg that arrives at it (Haruka is a train, not a car).
        val book = sampleBook()
        val trip = book.sections(SAMPLE_TODAY).now!!
        val items = book.items(trip.id)
        val haruka = items.single { it.title == "Haruka to Kyoto" }
        val leg = PlanRoute.legKeys(trip, items).single { it.toId == haruka.id }
        assertEquals(Vehicle.Train, TravelModes.choices(trip.travelModes)[leg])
    }

    @Test
    fun theSummaryCountsDaysAndItems() {
        assertEquals("7 days · 1 item", planSummary(7, 1))
        assertEquals("0 items", planSummary(0, 0))
    }
}

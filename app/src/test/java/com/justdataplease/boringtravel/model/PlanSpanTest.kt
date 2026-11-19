package com.justdataplease.boringtravel.model

import com.justdataplease.boringtravel.data.db.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** From and To on a plan item (owner, 2026-11-12: "in each trip item add from date time to date time"). */
class PlanSpanTest {
    private val thu = LocalDate.of(2026, 11, 12)

    private fun item(
        id: String = "i", day: LocalDate? = thu, time: Int? = null, endDays: Int? = null, endMinutes: Int? = null,
        kind: String = "place", title: String = id, pos: Double = 1.0, endRow: Boolean? = null, endTitle: String? = null,
    ) = PlanItem(id, "t", day, pos, timeMinutes = time, kind = kind, title = title, createdAt = 0, updatedAt = 0,
        endDays = endDays, endMinutes = endMinutes, endRow = endRow, endTitle = endTitle)

    @Test fun aConcertOfThreeHoursEndsTheSameDayWithoutItsOwnRow() {
        val yandel = item(time = 19 * 60, endMinutes = 22 * 60, kind = "activity", title = "Yandel Sinfónico")
        assertTrue(PlanSpan.hasEnd(yandel))
        assertEquals(0, PlanSpan.days(yandel))
        assertEquals(thu, PlanSpan.endDate(yandel))
        assertEquals(180, PlanSpan.minutes(yandel))
        assertEquals(3.0, PlanSpan.hours(yandel)!!, 1e-9)
        assertEquals("3 h", PlanSpan.length(yandel))
        assertEquals("19:00–22:00", PlanSpan.times(yandel))
        assertNull("the time column shows both times", PlanSpan.until(yandel))
        assertFalse("same day: one row unless asked", PlanSpan.endRow(yandel))
        assertTrue(PlanSpan.endRow(yandel.copy(endRow = true)))
        assertEquals("Ends", PlanSpan.endTag(yandel))
        assertNull(PlanSpan.startTag(yandel))
    }

    @Test fun aHotelForThreeNightsChecksOutOnItsOwnRow() {
        val hostal = item(time = 20 * 60 + 30, endDays = 3, endMinutes = 11 * 60, kind = "stay", title = "Hostal Alicante")
        assertEquals(thu.plusDays(3), PlanSpan.endDate(hostal))
        assertEquals("3 nights", PlanSpan.length(hostal))
        assertTrue(PlanSpan.until(hostal)!!.startsWith("until "))
        assertTrue(PlanSpan.until(hostal)!!.endsWith("11:00 · 3 nights"))
        assertTrue("another day: its own row by itself", PlanSpan.endRow(hostal))
        assertFalse("switched off in the item", PlanSpan.endRow(hostal.copy(endRow = false)))
        assertEquals("Check-in", PlanSpan.startTag(hostal))
        assertEquals("Check-out", PlanSpan.endTag(hostal))
        assertNull("no check-in tag without the check-out row", PlanSpan.startTag(hostal.copy(endRow = false)))
        assertEquals("Hostal Alicante", PlanSpan.endName(hostal))
        assertEquals("Check-out Alicante", PlanSpan.endName(hostal.copy(endTitle = " Check-out Alicante ")))
        assertEquals((3 * 24 * 60 + 11 * 60 - (20 * 60 + 30)) / 60.0, PlanSpan.hours(hostal)!!, 1e-9)
    }

    @Test fun aThreeDayActivityEndsWithoutHotelWords() {
        val festival = item(endDays = 2, kind = "activity", title = "Festival")
        assertEquals("3 days", PlanSpan.length(festival))
        assertEquals(48.0, PlanSpan.hours(festival)!!, 1e-9)
        assertNull(PlanSpan.startTag(festival))
        assertEquals("Ends", PlanSpan.endTag(festival))
        assertTrue(PlanSpan.endRow(festival))
    }

    @Test fun savingTidiesTheEnd() {
        // A party until 02:00 ends the next morning.
        assertEquals(1, PlanSpan.normalized(item(time = 22 * 60, endMinutes = 2 * 60)).endDays)
        // An end at the start time is none.
        val none = PlanSpan.normalized(item(time = 600, endMinutes = 600))
        assertFalse(PlanSpan.hasEnd(none))
        assertNull(none.endMinutes)
        // An idea has no end.
        val idea = PlanSpan.normalized(item(day = null, endDays = 2, endMinutes = 600, endTitle = "x", endRow = true))
        assertNull(idea.endDays); assertNull(idea.endMinutes); assertNull(idea.endTitle); assertNull(idea.endRow)
        // The end's name only when it differs; 0 days is none; out of range is clamped.
        assertNull(PlanSpan.normalized(item(endDays = 1, endTitle = "i")).endTitle)
        assertNull(PlanSpan.normalized(item(endDays = 0, endMinutes = 700)).endDays)
        assertEquals(PlanSpan.MAX_DAYS, PlanSpan.normalized(item(endDays = 999)).endDays)
        assertEquals(23 * 60 + 59, PlanSpan.normalized(item(endDays = 1, endMinutes = 5000)).endMinutes)
    }

    @Test fun anEndChosenAsADateIsKeptAsDaysSoItMovesWithTheItem() {
        val hostal = PlanSpan.endingAt(item(kind = "stay"), thu.plusDays(3), 11 * 60)
        assertEquals(3, hostal.endDays)
        val moved = hostal.copy(day = thu.plusDays(1))
        assertEquals(thu.plusDays(4), PlanSpan.endDate(moved))
        assertEquals(3, PlanSpan.daysAfter(thu, thu.plusDays(3)))
        assertNull(PlanSpan.daysAfter(thu, thu.minusDays(1)))
        assertTrue(PlanSpan.validEnd(3, 660))
        assertFalse(PlanSpan.validEnd(-1, null))
        assertFalse(PlanSpan.validEnd(null, 24 * 60))
    }

    @Test fun anEndRowFallsByItsTimeAmongTheDaysItems() {
        val sun = thu.plusDays(3)
        val alhambra = item("alhambra", sun, pos = 1.0)
        val lunch = item("lunch", sun, time = 13 * 60, pos = 2.0)
        val suspiro = item("suspiro", sun, time = 16 * 60, pos = 3.0)
        val hostal = item("hostal", thu, endDays = 3, endMinutes = 11 * 60, kind = "stay")
        val untimed = item("trek", thu, endDays = 3, kind = "activity")
        val day = listOf(alhambra, lunch, suspiro)
        val steps = PlanSpan.byDay(day + hostal + untimed).getValue(sun)
        // An end without a time opens the day; the check-out at 11:00 comes before the first row timed after it.
        assertEquals(listOf("trek~end", "hostal~end", "alhambra", "lunch", "suspiro"), steps.map { it.id })
        assertTrue(steps[0].end && steps[1].end)
        assertEquals(11 * 60, steps[1].minutes)
        // Never before its own start: a same-day end with its own row comes after it.
        val show = item("show", thu, time = 19 * 60, endMinutes = 22 * 60, endRow = true, pos = 2.0)
        val dinner = item("dinner", thu, time = 21 * 60, pos = 3.0)
        val sameDay = PlanSpan.byDay(listOf(show, dinner)).getValue(thu)
        assertEquals(listOf("show", "dinner", "show~end"), sameDay.map { it.id })
        assertEquals("hostal", PlanSpan.itemIdOf("hostal~end"))
        assertEquals("x", PlanSpan.itemIdOf(PlanSpan.endId("x")))
        // The days in order, the check-out morning among them.
        assertEquals(listOf(thu, sun), PlanSpan.byDay(day + hostal + untimed).keys.toList())
    }

    @Test fun aDayIsInTimeOrderAndUntimedItemsKeepTheirPlace() {
        // Owner, 2026-11-12: "by default order by time". The positions say lunch, walk, museum, dinner.
        val lunch = item("lunch", time = 13 * 60, pos = 1.0)
        val walk = item("walk", pos = 2.0)
        val museum = item("museum", time = 10 * 60, pos = 3.0)
        val dinner = item("dinner", time = 20 * 60, pos = 4.0)
        val ordered = PlanSpan.dayOrder(listOf(dinner, museum, walk, lunch))
        assertEquals(listOf("museum", "walk", "lunch", "dinner"), ordered.map { it.id })
        // The List, the calendar and the clip read days through byDay: the same order.
        assertEquals(ordered.map { it.id }, PlanSpan.byDay(listOf(dinner, museum, walk, lunch)).getValue(thu).map { it.id })
        // Without times: the traveller's own order; equal times keep it too.
        assertEquals(listOf("walk", "trek"), PlanSpan.dayOrder(listOf(item("trek", pos = 5.0), walk)).map { it.id })
        val early = item("early", time = 9 * 60, pos = 7.0)
        val also = item("also", time = 9 * 60, pos = 6.0)
        assertEquals(listOf("also", "early"), PlanSpan.dayOrder(listOf(early, also)).map { it.id })
    }

    @Test fun thePlanIsDayByDayThenIdeas() {
        val fri = thu.plusDays(1)
        val idea = item("idea", day = null, pos = 1.0)
        val friLate = item("friLate", fri, time = 18 * 60, pos = 1.0)
        val friEarly = item("friEarly", fri, time = 8 * 60, pos = 2.0)
        val thuNote = item("thuNote", pos = 9.0)
        assertEquals(listOf("thuNote", "friEarly", "friLate", "idea"), PlanSpan.ordered(listOf(idea, friLate, friEarly, thuNote)).map { it.id })
    }
}

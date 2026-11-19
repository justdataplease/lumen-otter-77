package com.justdataplease.boringtravel.calendar

import com.justdataplease.boringtravel.calendar.Fx.athens
import com.justdataplease.boringtravel.calendar.Fx.d17
import com.justdataplease.boringtravel.calendar.Fx.d19
import com.justdataplease.boringtravel.calendar.Fx.d26
import com.justdataplease.boringtravel.calendar.Fx.item
import com.justdataplease.boringtravel.model.ItemSource
import com.justdataplease.boringtravel.model.PlanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CalendarImportTest {
    private val personal = CalendarInfo(1, "Personal", "ana@gmail.com", "com.google", color = 0x3366CC)
    private val cals = mapOf(1L to personal)

    private fun at(d: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(d, java.time.LocalTime.of(h, m)).atZone(athens).toInstant().toEpochMilli()
    private fun allDay(d: LocalDate, days: Int = 1) = d.toEpochDay() * 86_400_000L to d.plusDays(days.toLong()).toEpochDay() * 86_400_000L

    private fun timed(id: Long, title: String, d: LocalDate, h: Int, m: Int = 0, location: String = "") =
        PhoneInstance(id, 1, title, location, begin = at(d, h, m), end = at(d, h + 1, m))

    private fun whole(id: Long, title: String, d: LocalDate, days: Int = 1) =
        allDay(d, days).let { (b, e) -> PhoneInstance(id, 1, title, allDay = true, begin = b, end = e) }

    private fun candidates(instances: List<PhoneInstance>, items: List<com.justdataplease.boringtravel.data.db.PlanItem> = emptyList(), skip: Set<Long> = emptySet()) =
        CalendarImport.candidates(instances, cals, d17, d26, athens, items, skip)

    @Test fun eventsInTheTripsDatesLandOnTheirDayAndTime() {
        val list = candidates(
            listOf(
                timed(1, "Flight A3 602 to Paros", d19, 7, 45),
                whole(2, "Hotel Kalypso", LocalDate.of(2027, 6, 20), days = 3),
                timed(3, "Dentist", LocalDate.of(2027, 6, 10), 9), // before the trip
                timed(4, "Late dinner", d26, 22),
                timed(5, "Breakfast", LocalDate.of(2027, 6, 27), 9), // the day after
                whole(6, "Mum's birthday", LocalDate.of(2027, 6, 16)), // ends as the trip starts
            ),
        )
        assertEquals(listOf(1L, 2L, 4L), list.map { it.instance.eventId })
        val flight = list[0]
        assertEquals(d19, flight.day)
        assertEquals(7 * 60 + 45, flight.timeMinutes)
        assertEquals(PlanKind.Transport, flight.kind)
        assertTrue(flight.guessed)
        assertEquals("cal:1/2027-06-19", flight.externalId)
        assertEquals("Personal", flight.calendar?.name)
        val hotel = list[1]
        assertEquals(LocalDate.of(2027, 6, 20), hotel.day)
        assertNull(hotel.timeMinutes)
        assertEquals(PlanKind.Stay, hotel.kind)
        assertEquals(22 * 60, list[2].timeMinutes)
        assertEquals(PlanKind.Food, list[2].kind)
    }

    @Test fun anEventThatStartedBeforeTheTripLandsOnItsFirstDayWithoutATime() {
        val conf = PhoneInstance(9, 1, "Conference", begin = at(LocalDate.of(2027, 6, 15), 9), end = at(LocalDate.of(2027, 6, 18), 17))
        val c = candidates(listOf(conf)).single()
        assertEquals(d17, c.day)
        assertNull(c.timeMinutes)
        assertEquals(PlanKind.Place, c.kind)
        assertFalse(c.guessed)
    }

    @Test fun ourOwnEventsAreNotOffered() {
        assertTrue(candidates(listOf(timed(1, "Ferry", d19, 9)), skip = setOf(1L)).isEmpty())
    }

    @Test fun whatThePlanHasIsMarkedAndNeverAddedTwice() {
        val events = listOf(
            timed(1, "Ferry to Naxos", d19, 9),
            timed(2, "Museum tickets", d19, 11),
            timed(3, "Sunset walk", d19, 20),
            timed(4, "Yoga", d19, 8),
            timed(4, "Yoga", LocalDate.of(2027, 6, 20), 8), // repeats daily
        )
        val items = listOf(
            item("x1", externalId = "cal:1/2027-06-19", source = "calendar"), // imported before
            item("x2", day = LocalDate.of(2027, 6, 18), externalId = "cal:2/2027-06-18", source = "calendar"), // moved since
            item("x3", minutes = 20 * 60, title = "  sunset WALK "), // typed by hand (or imported on another phone)
            item("x4", externalId = "cal:4/2027-06-19", source = "calendar"),
        )
        val list = candidates(events, items)
        val inPlan = list.associate { it.externalId to it.inPlan }
        assertEquals(true, inPlan["cal:1/2027-06-19"])
        assertEquals(true, inPlan["cal:2/2027-06-19"]) // a one-off event, same id
        assertEquals(true, inPlan["cal:3/2027-06-19"])
        assertEquals(true, inPlan["cal:4/2027-06-19"])
        assertEquals(false, inPlan["cal:4/2027-06-20"]) // the next yoga day is new
        // Deleted items don't count.
        assertFalse(candidates(events, listOf(item("x1", externalId = "cal:1/2027-06-19", deleted = true))).first { it.instance.eventId == 1L }.inPlan)
    }

    @Test fun theSameEventInTwoCalendarsShowsOnce() {
        val shared = timed(8, "Boat tour", d19, 10).copy(calendarId = 2)
        val list = CalendarImport.candidates(listOf(timed(7, "Boat tour", d19, 10), shared), cals + (2L to personal.copy(id = 2, name = "Family")), d17, d26, athens, emptyList())
        assertEquals(1, list.size)
    }

    @Test fun freshDropsWhatIsAlreadyThereAndRepeats() {
        val list = candidates(listOf(timed(1, "Ferry", d19, 9), timed(2, "Tour", d19, 11)))
        val ferry = list[0]
        assertEquals(list, CalendarImport.fresh(list, emptyList()))
        assertEquals(listOf(list[1]), CalendarImport.fresh(list + ferry, listOf(item("f", minutes = 9 * 60, title = "ferry"))))
        assertEquals(list, CalendarImport.fresh(list + ferry, emptyList()))
    }

    @Test fun itemsComeFromTheCalendar() {
        val c = candidates(listOf(timed(1, "  Dinner at  Lefteris ", d19, 21, location = "Halki, Naxos").copy(description = "<p>Table for 2</p><br>Ref&nbsp;<b>A1</b> &amp; more"))).single()
        val item = CalendarImport.toItem(c, "T1", "N1", 3.5, 99)
        assertEquals("Dinner at Lefteris", item.title)
        assertEquals("Table for 2\n\nRef A1 & more", item.note)
        assertEquals("Halki, Naxos", item.placeName)
        assertEquals(ItemSource.Calendar.id, item.source)
        assertEquals("cal:1/2027-06-19", item.externalId)
        assertEquals(PlanKind.Food.id, item.kind)
        assertEquals(21 * 60, item.timeMinutes)
        assertEquals(d19, item.day)
        assertEquals(3.5, item.position, 0.0)
    }

    @Test fun longNotesAreCapped() {
        val note = CalendarImport.note("word ".repeat(300))
        assertEquals(500, note.length)
        assertTrue(note.endsWith("…"))
    }

    @Test fun kindsComeOnlyFromObviousWords() {
        assertEquals(PlanKind.Transport, CalendarImport.guessKind("Flight to Athens"))
        assertEquals(PlanKind.Transport, CalendarImport.guessKind("Online check-in for your flight"))
        assertEquals(PlanKind.Transport, CalendarImport.guessKind("Πτήση για Νάξο"))
        assertEquals(PlanKind.Transport, CalendarImport.guessKind("ΠΤΗΣΕΙΣ"))
        assertEquals(PlanKind.Transport, CalendarImport.guessKind("✈ A3 602"))
        assertEquals(PlanKind.Transport, CalendarImport.guessKind("A3 602", "Athens International Airport"))
        assertEquals(PlanKind.Stay, CalendarImport.guessKind("Check-in Hotel Grande Bretagne"))
        assertEquals(PlanKind.Stay, CalendarImport.guessKind("Ξενοδοχείο Αστέρι"))
        assertEquals(PlanKind.Stay, CalendarImport.guessKind("Airbnb in Plaka"))
        assertEquals(PlanKind.Food, CalendarImport.guessKind("Dinner with Maria"))
        assertEquals(PlanKind.Food, CalendarImport.guessKind("Ταβέρνα"))
        assertEquals(PlanKind.Activity, CalendarImport.guessKind("Acropolis Museum tickets"))
        assertEquals(PlanKind.Activity, CalendarImport.guessKind("Ξενάγηση στην Πλάκα"))
        // Words inside other words don't count.
        assertNull(CalendarImport.guessKind("Barcelona"))
        assertNull(CalendarImport.guessKind("Meet Anna"))
        assertNull(CalendarImport.guessKind("Busy"))
        assertNull(CalendarImport.guessKind("Dinnerware shopping"))
        assertNull(CalendarImport.guessKind(""))
    }
}

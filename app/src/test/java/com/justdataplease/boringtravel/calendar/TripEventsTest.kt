package com.justdataplease.boringtravel.calendar

import com.justdataplease.boringtravel.calendar.Fx.athens
import com.justdataplease.boringtravel.calendar.Fx.d17
import com.justdataplease.boringtravel.calendar.Fx.d19
import com.justdataplease.boringtravel.calendar.Fx.item
import com.justdataplease.boringtravel.calendar.Fx.trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TripEventsTest {

    @Test fun deletingATripProducesAnEmptyCalendarPlanEvenWhenItsItemsStillExist() {
        assertTrue(TripEvents.build(trip().copy(deletedAt = 42), listOf(item("flight", minutes = 600)), forPhone = true).isEmpty())
    }

    @Test fun theTripAndItsDayItemsBecomeEvents() {
        val items = listOf(
            item("idea", day = null),
            item("gone", deleted = true),
            item("ferry", minutes = 7 * 60 + 30, title = "Ferry", place = "Paros port", note = "Blue Star"),
            item("beach", title = "Beach day"),
            item("noname", minutes = 20 * 60, title = " ", place = "Taverna Lefteris, Naxos"),
            item("blank", title = "", kind = "food"),
        )
        val events = TripEvents.build(trip(subtitle = " Ten slow days "), items)
        assertEquals(listOf("trip", "item/beach", "item/blank", "item/ferry", "item/noname"), events.map { it.key })
        val tripEvent = events[0]
        assertEquals(EventTime.Days(d17, LocalDate.of(2027, 6, 27)), tripEvent.time)
        assertEquals("Ten slow days", tripEvent.description)
        assertTrue(tripEvent.free)
        val ferry = events.first { it.key == "item/ferry" }
        assertEquals(EventTime.At(LocalDateTime.of(2027, 6, 19, 7, 30), 60), ferry.time)
        assertEquals("Paros port", ferry.location)
        assertEquals("Blue Star", ferry.description)
        assertFalse(ferry.free)
        assertEquals(EventTime.Days(d19, d19.plusDays(1)), events.first { it.key == "item/beach" }.time)
        assertEquals("Taverna Lefteris", events.first { it.key == "item/noname" }.title)
        assertEquals("Food", events.first { it.key == "item/blank" }.title)
    }

    @Test fun itemsImportedFromACalendarAreNotWrittenBackToOne() {
        val items = listOf(item("mine", minutes = 600), item("flight", minutes = 450, source = "calendar", externalId = "cal:5/2027-06-19"))
        assertEquals(listOf("trip", "item/flight", "item/mine"), TripEvents.build(trip(), items).map { it.key })
        assertEquals(listOf("trip", "item/mine"), TripEvents.build(trip(), items, forPhone = true).map { it.key })
        // So importing more events never makes the calendar look behind.
        val link = CalendarLink(7, "Personal", zone = "Europe/Athens", fingerprint = TripEvents.fingerprint(TripEvents.build(trip(), items.take(1), forPhone = true), athens), updatedAt = 0)
        assertFalse(link.isBehind(trip(), items, null))
    }

    @Test fun aSomedayTripHasNoTripEvent() {
        val events = TripEvents.build(trip(null, null), listOf(item("x", day = null)))
        assertTrue(events.isEmpty())
        val single = TripEvents.build(trip(d17, null), emptyList())
        assertEquals(EventTime.Days(d17, d17.plusDays(1)), single.single().time)
    }

    @Test fun providerFieldsUseUtcMidnightsForAllDayAndTheZoneForTimes() {
        val events = TripEvents.build(trip(), listOf(item("ferry", minutes = 9 * 60 + 30)))
        val day = TripEvents.fields(events[0], athens)
        assertTrue(day.allDay)
        assertEquals("UTC", day.timeZone)
        assertEquals(20986L * 86_400_000L, day.startMs)
        assertEquals((20986L + 10) * 86_400_000L, day.endMs)
        val timed = TripEvents.fields(events[1], athens)
        assertFalse(timed.allDay)
        assertEquals("Europe/Athens", timed.timeZone)
        assertEquals(1_813_386_600_000L, timed.startMs) // 09:30 EEST = 06:30 UTC
        assertEquals(timed.startMs + 3_600_000L, timed.endMs)
        assertEquals(LocalDate.of(2027, 6, 17), TripEvents.utcDay(day.startMs))
    }

    @Test fun aTimeInTheSpringGapMovesForward() {
        // 03:30 on 28 March 2027 doesn't exist in Athens; java.time (and Android) read it as 04:30 EEST.
        val e = TripEvents.build(trip(LocalDate.of(2027, 3, 27), LocalDate.of(2027, 3, 29)), listOf(item("x", LocalDate.of(2027, 3, 28), 3 * 60 + 30)))[1]
        val f = TripEvents.fields(e, athens)
        assertEquals(LocalDateTime.of(2027, 3, 28, 4, 30).atZone(athens).toInstant().toEpochMilli(), f.startMs)
    }

    @Test fun anItemsWebsiteGoesUnderItsNoteAndAddingOneAsksForAnUpdate() {
        val plain = item("shrine", minutes = 540, title = "Yasaka Shrine", note = "Go early")
        val linked = plain.copy(url = "https://www.google.com/maps/place/Yasaka+Shrine/@35.0036559,135.7785534,17z")
        val event = TripEvents.build(trip(), listOf(linked)).single { it.key == "item/shrine" }
        assertEquals("Go early\n\nhttps://www.google.com/maps/place/Yasaka+Shrine/@35.0036559,135.7785534,17z", event.description)
        assertEquals("https://example.com/", TripEvents.build(trip(), listOf(plain.copy(note = "", url = "https://example.com/"))).single { it.key == "item/shrine" }.description)
        // The website, then the place's Google Maps link; a Maps link alone reads as it did when it was the website.
        val maps = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
        assertEquals("Go early\n\nhttps://example.com/\n\n$maps", TripEvents.itemDescription(plain.copy(url = "https://example.com/", mapsUrl = maps)))
        assertEquals("Go early\n\n$maps", TripEvents.itemDescription(plain.copy(mapsUrl = maps)))
        assertEquals(TripEvents.itemDescription(plain.copy(url = maps)), TripEvents.itemDescription(plain.copy(mapsUrl = maps)))
        // Items without a link read as before, so existing calendars don't look behind.
        assertEquals("Go early", TripEvents.build(trip(), listOf(plain)).single { it.key == "item/shrine" }.description)
        assertNotEquals(
            TripEvents.fingerprint(TripEvents.build(trip(), listOf(plain)), athens),
            TripEvents.fingerprint(TripEvents.build(trip(), listOf(linked)), athens),
        )
    }

    @Test fun theFingerprintFollowsWhatTheCalendarShows() {
        val base = listOf(item("a", minutes = 600, position = 1.0), item("b", position = 2.0))
        val fp = TripEvents.fingerprint(TripEvents.build(trip(), base), athens)
        // Ticking off, reordering and ideas don't change the calendar.
        val same = listOf(item("b", position = 0.5, done = true), item("a", minutes = 600, position = 1.0, done = true), item("idea", day = null))
        assertEquals(fp, TripEvents.fingerprint(TripEvents.build(trip(), same), athens))
        // A new time, a deleted item, other dates or another zone do.
        assertNotEquals(fp, TripEvents.fingerprint(TripEvents.build(trip(), listOf(item("a", minutes = 630), item("b"))), athens))
        assertNotEquals(fp, TripEvents.fingerprint(TripEvents.build(trip(), listOf(item("a", minutes = 600), item("b", deleted = true))), athens))
        assertNotEquals(fp, TripEvents.fingerprint(TripEvents.build(trip(end = d19), base), athens))
        assertNotEquals(fp, TripEvents.fingerprint(TripEvents.build(trip(), base), ZoneId.of("Europe/Rome")))
    }

    @Test fun aLinkIsBehindWhenThePlanChanges() {
        val items = listOf(item("a", minutes = 600))
        val events = TripEvents.build(trip(), items)
        val link = CalendarLink(7, "Personal", "ana@gmail.com", "com.google", 0, "Europe/Athens", TripEvents.fingerprint(events, athens), 0)
        assertFalse(link.isBehind(trip(), items, null))
        assertFalse(link.isBehind(trip(), items, "Europe/Athens"))
        assertTrue(link.isBehind(trip(), items + item("b"), null))
        assertTrue(link.isBehind(trip(), items, "Europe/Rome"))
        assertEquals("Google · ana@gmail.com", link.accountLabel)
    }
}

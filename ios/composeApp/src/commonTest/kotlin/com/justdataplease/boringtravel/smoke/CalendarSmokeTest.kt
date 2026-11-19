package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.calendar.CalendarIntents
import com.justdataplease.boringtravel.calendar.EventTime
import com.justdataplease.boringtravel.calendar.IcsWriter
import com.justdataplease.boringtravel.calendar.TripEvent
import com.justdataplease.boringtravel.calendar.TripZone
import com.justdataplease.boringtravel.calendar.wallMs
import com.justdataplease.boringtravel.calendar.wallOf
import com.justdataplease.boringtravel.time.localDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The calendar's .ics file (calendar/IcsWriter.kt) for a made-up trip, with the phone's own zone rules (NSTimeZone on
 * iPhone, java.time on the desktop): CRLF lines, escaped text, a VTIMEZONE over the trip's summer time, folding at 75
 * octets between whole UTF-8 characters, and the zone's names and offsets.
 */
class CalendarSmokeTest {
    private val day = Samples.start
    private val stamp = 1_800_000_000_000L

    private fun ics(): String = IcsWriter.write(
        "Naxos, slowly",
        listOf(
            TripEvent("trip", "trip-t1@boringtravel.justdataplease.com", "Naxos, slowly", EventTime.Days(day, day.plusDays(5))),
            TripEvent(
                "item/i1", "item-i1@boringtravel.justdataplease.com", "Ferry; Piraeus, Naxos", EventTime.At(day, 7 * 60 + 25, 60),
                location = "Piraeus port, Greece", description = "Blue Star\nDeck seats \\ no cabins", lat = 37.942, lng = 23.646, updatedAt = stamp,
            ),
        ),
        "Europe/Athens", stamp, "1.21.1",
    )

    @Test fun aTripAsAnIcsFile() {
        val text = ics()
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//justdataplease//BoringTravel 1.21.1//EN\r\n"), text)
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        assertFalse(text.replace("\r\n", "").contains("\n"))
        assertTrue(text.contains("\r\nX-WR-CALNAME:Naxos\\, slowly\r\n"))
        assertTrue(text.contains("\r\nDTSTART;VALUE=DATE:20270910\r\nDTEND;VALUE=DATE:20270915\r\n"))
        assertTrue(text.contains("\r\nDTSTART;TZID=Europe/Athens:20270910T072500\r\nDTEND;TZID=Europe/Athens:20270910T082500\r\n"), text)
        assertTrue(text.contains("\r\nSUMMARY:Ferry\\; Piraeus\\, Naxos\r\n"))
        assertTrue(text.contains("\r\nLOCATION:Piraeus port\\, Greece\r\n"))
        assertTrue(text.contains("\r\nDESCRIPTION:Blue Star\\nDeck seats \\\\ no cabins\r\n"))
        assertTrue(text.contains("\r\nGEO:37.942000;23.646000\r\n"))
        assertTrue(text.contains("\r\nDTSTAMP:20270115T080000Z\r\n"))
        assertTrue(text.contains("\r\nLAST-MODIFIED:20270115T080000Z\r\nSEQUENCE:30000000\r\n"))
        assertTrue(text.contains("\r\nTRANSP:TRANSPARENT\r\n") && text.contains("\r\nTRANSP:OPAQUE\r\n"))
    }

    @Test fun theTimezoneOverTheTripsSummer() {
        val text = ics()
        val zone = text.substringAfter("BEGIN:VTIMEZONE\r\n").substringBefore("END:VTIMEZONE")
        assertTrue(zone.startsWith("TZID:Europe/Athens\r\n"), zone)
        assertTrue(zone.contains("BEGIN:DAYLIGHT\r\nDTSTART:20270328T030000\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0300\r\nEND:DAYLIGHT"), zone)
        assertFalse(zone.contains("BEGIN:STANDARD"))
        // A fixed offset is written in UTC, without a VTIMEZONE.
        val utc = IcsWriter.write("x", listOf(TripEvent("a", "a@x", "A", EventTime.At(day, 9 * 60, 30))), "UTC", stamp, "1")
        assertTrue(utc.contains("\r\nDTSTART:20270910T090000Z\r\nDTEND:20270910T093000Z\r\n"))
        assertFalse(utc.contains("VTIMEZONE"))
    }

    @Test fun longLinesFoldBetweenWholeCharacters() {
        val line = "DESCRIPTION:" + "Νάξος ".repeat(20)
        val folded = IcsWriter.fold(line)
        val physical = folded.split("\r\n")
        assertTrue(physical.size > 1)
        assertTrue(physical.all { it.encodeToByteArray().size <= 75 }, physical.map { it.encodeToByteArray().size }.toString())
        assertTrue(physical.drop(1).all { it.startsWith(" ") })
        assertEquals(line, folded.replace("\r\n ", ""))
        assertEquals("short", IcsWriter.fold("short"))
        val emoji = "SUMMARY:" + "🏝".repeat(30)
        assertEquals(emoji, IcsWriter.fold(emoji).replace("\r\n ", ""))
    }

    @Test fun textValuesAndOffsets() {
        assertEquals("a\\\\b\\;c\\,d\\ne", IcsWriter.text("a\\b;c,d\r\ne\u0001"))
        assertEquals("+0530", IcsWriter.offset(19_800))
        assertEquals("-0500", IcsWriter.offset(-18_000))
        assertEquals("+0000", IcsWriter.offset(0))
        assertTrue(IcsWriter.usesTzid("Europe/Athens"))
        assertFalse(IcsWriter.usesTzid("UTC"))
        assertFalse(IcsWriter.usesTzid("Etc/GMT+3"))
        assertEquals(30_000_000, IcsWriter.sequence(stamp))
        assertEquals("Naxos Paros 2027.ics", IcsWriter.fileName("Naxos/Paros: 2027"))
    }

    @Test fun zonesByTheirIds() {
        assertEquals("New York", TripZone.city("America/New_York"))
        assertEquals("UTC", TripZone.city("Etc/UTC"))
        assertEquals("GMT+3", TripZone.offsetLabel("Europe/Athens", day))
        assertEquals("GMT+2", TripZone.offsetLabel("Europe/Athens", localDate(2027, 1, 10)))
        assertEquals("GMT+5:30", TripZone.offsetLabel("Asia/Kolkata", day))
        assertEquals("GMT-4", TripZone.offsetLabel("America/New_York", day))
        assertEquals("Athens · GMT+3", TripZone.label("Europe/Athens", day))
        assertEquals("Europe/Athens", TripZone.guess(null, listOf("Europe/Athens", "Europe/Athens", "Europe/Rome"), emptyList(), "UTC"))
        assertEquals("Europe/Rome", TripZone.guess("Europe/Rome", listOf("Europe/Athens"), emptyList(), "UTC"))
        assertEquals("Asia/Tokyo", TripZone.guess("Mars/Olympus", emptyList(), listOf(setOf("Asia/Tokyo")), "UTC"))
        assertEquals("UTC", TripZone.guess(null, emptyList(), listOf(setOf("America/New_York", "America/Chicago")), "UTC"))
        val at = wallMs(day, 7 * 60 + 25, "Europe/Athens")
        assertEquals(day to 7 * 60 + 25, wallOf(at, "Europe/Athens"))
        assertEquals(day to 4 * 60 + 25, wallOf(at, "UTC"))
        val untimed = CalendarIntents.whenOf(day, null, "Europe/Athens")
        assertTrue(untimed.allDay)
        assertEquals(day.toEpochDay() * 86_400_000L, untimed.beginMs)
        assertEquals(at + 60 * 60_000L, CalendarIntents.whenOf(day, 7 * 60 + 25, "Europe/Athens").endMs)
    }
}

package com.justdataplease.boringtravel.calendar

import com.justdataplease.boringtravel.calendar.Fx.athens
import com.justdataplease.boringtravel.calendar.Fx.d19
import com.justdataplease.boringtravel.calendar.Fx.item
import com.justdataplease.boringtravel.calendar.Fx.trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class IcsWriterTest {
    private val stamp = Instant.parse("2026-11-06T12:34:56Z")

    private fun ics(items: List<com.justdataplease.boringtravel.data.db.PlanItem>, zone: ZoneId = athens, t: com.justdataplease.boringtravel.data.db.Trip = trip(), at: Instant = stamp) =
        IcsWriter.write(t.title, TripEvents.build(t, items), zone, at, "0.1.0")

    /** Content lines after unfolding (RFC 5545 §3.1). */
    private fun unfold(s: String): List<String> = s.replace("\r\n ", "").split("\r\n").filter { it.isNotEmpty() }

    @Test fun textIsEscaped() {
        assertEquals("a\\, b\\; c\\\\d\\ne\\nf\\ng", IcsWriter.text("a, b; c\\d\ne\r\nf\rg"))
        assertEquals("tab\tkept, bell gone", IcsWriter.text("tab\tkept, bell\u0007 gone").replace("\\,", ","))
        assertEquals("Ferry: 07:30 \"Blue Star\"", IcsWriter.text("Ferry: 07:30 \"Blue Star\""))
    }

    @Test fun shortLinesAreNotFolded() {
        val line = "SUMMARY:" + "x".repeat(67)
        assertEquals(75, line.length)
        assertEquals(line, IcsWriter.fold(line))
    }

    @Test fun longAsciiLinesFoldAt75Octets() {
        val line = "DESCRIPTION:" + "abcdefghij".repeat(30)
        val folded = IcsWriter.fold(line)
        val parts = folded.split("\r\n")
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue("${it.length} octets", it.toByteArray(Charsets.UTF_8).size <= 75) }
        assertEquals(75, parts[0].length)
        parts.drop(1).forEach { assertTrue(it.startsWith(" ")) }
        assertEquals(line, folded.replace("\r\n ", ""))
    }

    @Test fun foldingNeverSplitsAUtf8Character() {
        // Greek (2 octets), CJK (3), emoji (4 octets, a surrogate pair in Java).
        val line = "SUMMARY:" + "Παραλία Νάξου 海辺の道 🚲🌊 ".repeat(12)
        val folded = IcsWriter.fold(line)
        for (part in folded.split("\r\n")) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            assertTrue("${bytes.size} octets", bytes.size <= 75)
            // Each physical line is valid UTF-8 on its own: it decodes back to the same text.
            assertEquals(part, String(bytes, Charsets.UTF_8))
            assertFalse(part.isNotEmpty() && Character.isLowSurrogate(part.trimStart(' ').first()))
            assertFalse(part.isNotEmpty() && Character.isHighSurrogate(part.last()))
        }
        assertEquals(line, folded.replace("\r\n ", ""))
    }

    @Test fun fileUsesCrlfAndShortLinesOnly() {
        val s = ics(listOf(item("a", minutes = 9 * 60 + 30, title = "Ferry to Naxos, Blue Star; deck seats", note = "Line one\nLine two " + "long ".repeat(40))))
        assertTrue(s.endsWith("\r\n"))
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(s))
        s.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
        val lines = unfold(s)
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertEquals("END:VCALENDAR", lines.last())
        assertTrue("VERSION:2.0" in lines)
        assertTrue("PRODID:-//justdataplease//BoringTravel 0.1.0//EN" in lines)
        assertTrue("METHOD:PUBLISH" in lines)
        assertTrue("SUMMARY:Ferry to Naxos\\, Blue Star\\; deck seats" in lines)
        assertTrue(lines.any { it.startsWith("DESCRIPTION:Line one\\nLine two long") })
        assertEquals(lines.count { it == "BEGIN:VEVENT" }, lines.count { it == "END:VEVENT" })
    }

    @Test fun allDayEventsEndTheDayAfter() {
        val lines = unfold(ics(listOf(item("idea", day = null), item("beach", day = d19))))
        // The trip: 17–26 June → DTEND is 27 June (exclusive).
        assertTrue("DTSTART;VALUE=DATE:20270617" in lines)
        assertTrue("DTEND;VALUE=DATE:20270627" in lines)
        // An all-day item on 19 June ends on the 20th.
        assertTrue("DTSTART;VALUE=DATE:20270619" in lines)
        assertTrue("DTEND;VALUE=DATE:20270620" in lines)
        assertTrue("TRANSP:TRANSPARENT" in lines)
        // Ideas stay out; no timed events → no VTIMEZONE.
        assertEquals(2, lines.count { it == "BEGIN:VEVENT" })
        assertFalse(lines.any { it.startsWith("BEGIN:VTIMEZONE") })
    }

    @Test fun timedEventsUseTheTripZoneWithAVtimezone() {
        val lines = unfold(ics(listOf(item("ferry", minutes = 9 * 60 + 30, place = "Port of Paros", lat = 37.0857, lng = 25.1489))))
        assertTrue("DTSTART;TZID=Europe/Athens:20270619T093000" in lines)
        assertTrue("DTEND;TZID=Europe/Athens:20270619T103000" in lines)
        assertTrue("LOCATION:Port of Paros" in lines)
        assertTrue("GEO:37.085700;25.148900" in lines)
        assertTrue("TRANSP:OPAQUE" in lines)
        val tz = lines.subList(lines.indexOf("BEGIN:VTIMEZONE"), lines.indexOf("END:VTIMEZONE") + 1)
        assertEquals(
            listOf(
                "BEGIN:VTIMEZONE", "TZID:Europe/Athens",
                "BEGIN:DAYLIGHT", "DTSTART:20270328T030000", "TZOFFSETFROM:+0200", "TZOFFSETTO:+0300", "END:DAYLIGHT",
                "END:VTIMEZONE",
            ),
            tz,
        )
        // VTIMEZONE comes before the events that use it.
        assertTrue(lines.indexOf("END:VTIMEZONE") < lines.indexOf("BEGIN:VEVENT"))
    }

    @Test fun aTripAcrossTheClockChangeListsBothOffsets() {
        val t = trip(LocalDate.of(2027, 10, 29), LocalDate.of(2027, 11, 2))
        val lines = unfold(ics(listOf(item("a", LocalDate.of(2027, 10, 30), 10 * 60), item("b", LocalDate.of(2027, 11, 1), 10 * 60)), t = t))
        assertTrue("DTSTART:20271031T040000" in lines)
        val i = lines.indexOf("DTSTART:20271031T040000")
        assertEquals("BEGIN:STANDARD", lines[i - 1])
        assertEquals("TZOFFSETFROM:+0300", lines[i + 1])
        assertEquals("TZOFFSETTO:+0200", lines[i + 2])
    }

    @Test fun westernZonesAndWinterOffsets() {
        val ny = ZoneId.of("America/New_York")
        val tz = IcsWriter.vtimezone(ny, Instant.parse("2027-01-10T13:00:00Z"), Instant.parse("2027-01-12T13:00:00Z"))
        assertEquals(listOf("BEGIN:VTIMEZONE", "TZID:America/New_York", "BEGIN:STANDARD", "DTSTART:20261101T020000", "TZOFFSETFROM:-0400", "TZOFFSETTO:-0500", "END:STANDARD", "END:VTIMEZONE"), tz)
        assertEquals("+0530", IcsWriter.offset(ZoneOffset.ofHoursMinutes(5, 30)))
        assertEquals("-0330", IcsWriter.offset(ZoneOffset.ofHoursMinutes(-3, -30)))
        assertEquals("+0000", IcsWriter.offset(ZoneOffset.UTC))
    }

    @Test fun fixedOffsetZonesAreWrittenInUtc() {
        val lines = unfold(ics(listOf(item("ferry", minutes = 9 * 60 + 30)), zone = ZoneId.of("UTC")))
        assertTrue("DTSTART:20270619T093000Z" in lines)
        assertTrue("DTEND:20270619T103000Z" in lines)
        assertFalse(lines.any { it.startsWith("BEGIN:VTIMEZONE") || it.contains("TZID") })
        val plus3 = unfold(ics(listOf(item("ferry", minutes = 9 * 60 + 30)), zone = ZoneId.of("Etc/GMT-3")))
        assertTrue("DTSTART:20270619T063000Z" in plus3)
        assertTrue(IcsWriter.usesTzid(ZoneId.of("Asia/Tokyo")))
        assertFalse(IcsWriter.usesTzid(ZoneOffset.ofHours(2)))
    }

    @Test fun uidsAreStableAndStampsAreUtc() {
        val items = listOf(item("I-42", minutes = 600))
        val a = unfold(ics(items, at = stamp))
        val b = unfold(ics(items, at = stamp.plusSeconds(3600)))
        assertEquals(a.filter { it.startsWith("UID:") }, b.filter { it.startsWith("UID:") })
        assertEquals(listOf("UID:trip-T1@boringtravel.justdataplease.com", "UID:I-42@boringtravel.justdataplease.com"), a.filter { it.startsWith("UID:") })
        assertTrue("DTSTAMP:20261106T123456Z" in a)
        assertTrue("DTSTAMP:20261106T133456Z" in b)
        assertTrue("LAST-MODIFIED:20270115T080000Z" in a)
        assertTrue("SEQUENCE:${1_800_000_000_000 / 60_000}" in a)
    }

    @Test fun calendarNameIsEscapedText() {
        val lines = unfold(ics(emptyList(), t = trip(title = "Paros, Naxos; Halki")))
        assertTrue("X-WR-CALNAME:Paros\\, Naxos\\; Halki" in lines)
    }
}

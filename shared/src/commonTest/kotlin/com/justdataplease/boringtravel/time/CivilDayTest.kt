package com.justdataplease.boringtravel.time

import com.justdataplease.boringtravel.util.PlainDates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The calendar arithmetic the iPhone's day is built on, and the engine's day itself as shared code sees it. */
class CivilDayTest {
    @Test
    fun knownDays() {
        assertEquals(CivilDay(1970, 1, 1), CivilDay.ofEpochDay(0))
        assertEquals(4, CivilDay(1970, 1, 1).dayOfWeek) // a Thursday
        assertEquals(CivilDay(1969, 12, 31), CivilDay.ofEpochDay(-1))
        assertEquals(10_957L, CivilDay(2000, 1, 1).epochDay)
        assertEquals(CivilDay(2000, 2, 29), CivilDay.ofEpochDay(CivilDay(2000, 2, 28).epochDay + 1))
        assertEquals(CivilDay(2100, 3, 1), CivilDay.ofEpochDay(CivilDay(2100, 2, 28).epochDay + 1)) // 2100 is no leap year
        assertEquals(5, CivilDay(2027, 6, 18).dayOfWeek) // a Friday
        assertEquals(7, CivilDay(2026, 11, 15).dayOfWeek) // a Sunday
    }

    @Test
    fun everyDayRoundTrips() {
        var previous = CivilDay.ofEpochDay(-200_001)
        for (n in -200_000L..200_000L) {
            val day = CivilDay.ofEpochDay(n)
            assertEquals(n, day.epochDay)
            assertTrue(CivilDay.isValid(day.year, day.month, day.day))
            // The day after the previous one: the next day of the month, or the first of the next month or year.
            val expected = when {
                previous.day < CivilDay.lengthOfMonth(previous.year, previous.month) -> previous.copy(day = previous.day + 1)
                previous.month < 12 -> CivilDay(previous.year, previous.month + 1, 1)
                else -> CivilDay(previous.year + 1, 1, 1)
            }
            assertEquals(expected, day)
            previous = day
        }
    }

    @Test
    fun isoTextAndBack() {
        assertEquals("2027-06-18", CivilDay(2027, 6, 18).iso())
        assertEquals("0987-01-05", CivilDay(987, 1, 5).iso())
        assertEquals(CivilDay(2027, 6, 18), CivilDay.parse("2027-06-18"))
        assertEquals(CivilDay(2028, 2, 29), CivilDay.parse("2028-02-29"))
        for (bad in listOf("", "2027-6-18", "2027-06-31", "2027-02-29", "2027-13-01", "2027-00-10", "18/06/2027", "2027-06-18T10:00", " 2027-06-18"))
            assertNull(CivilDay.parse(bad), bad)
        assertFalse(CivilDay.isValid(2027, 2, 29))
    }

    @Test
    fun theEnginesDay() {
        val day = localDate(2027, 6, 18)
        assertEquals("2027-06-18", day.toString())
        assertEquals(CivilDay(2027, 6, 18), day.civil)
        assertEquals(day, localDateOfEpochDay(day.toEpochDay()))
        assertEquals(day, parseLocalDateOrNull("2027-06-18"))
        assertEquals(localDate(2027, 7, 1), day.plusDays(13))
        assertEquals(localDate(2027, 5, 31), day.minusDays(18))
        assertEquals(day.hashCode(), localDate(2027, 6, 18).hashCode())
        assertNull(parseLocalDateOrNull("2027-02-30"))
        assertNull(parseLocalDateOrNull("June 18"))
    }

    @Test
    fun aMomentIsADayInItsZone() {
        // 2027-06-18 22:30 UTC: already the 19th in Tokyo, still the 18th in New York.
        val moment = (CivilDay(2027, 6, 18).epochDay * 24 + 22) * 3_600_000L + 30 * 60_000L
        assertEquals(localDate(2027, 6, 18), localDateAt(moment, "UTC"))
        assertEquals(localDate(2027, 6, 19), localDateAt(moment, "Asia/Tokyo"))
        assertEquals(localDate(2027, 6, 18), localDateAt(moment, "America/New_York"))
        // Midnight in Athens that day is 21:00 UTC the day before (summer time, UTC+3).
        assertEquals((CivilDay(2027, 6, 17).epochDay * 24 + 21) * 3_600_000L, startOfDayMs(localDate(2027, 6, 18), "Europe/Athens"))
        assertEquals(localDate(2027, 6, 18), localDateAt(startOfDayMs(localDate(2027, 6, 18), "Europe/Athens"), "Europe/Athens"))
        // An unknown zone is the phone's own.
        assertEquals(localDateAt(moment, null), localDateAt(moment, "Nowhere/At_All"))
        assertTrue(systemZoneId().isNotBlank())
    }

    @Test
    fun plainDateTexts() {
        val day = localDate(2027, 6, 18)
        assertEquals("Fri 18 Jun 2027", PlainDates.dayLong(day))
        assertEquals("Fri 18", PlainDates.dayShort(day))
        assertEquals("18 Jun", PlainDates.dayMonth(day))
        assertEquals("Jun 2027", PlainDates.monthYear(day))
        assertEquals("07:05", PlainDates.time(425))
        assertEquals("1 day", PlainDates.days(1))
        assertEquals("12 days", PlainDates.days(12))
        assertEquals("17–26 Jun 2027", PlainDates.range(localDate(2027, 6, 17), localDate(2027, 6, 26)))
        assertEquals("28 Jun – 3 Jul 2027", PlainDates.range(localDate(2027, 6, 28), localDate(2027, 7, 3)))
        assertEquals("30 Dec 2026 – 4 Jan 2027", PlainDates.range(localDate(2026, 12, 30), localDate(2027, 1, 4)))
        assertEquals("18 Jun 2027", PlainDates.range(day, day))
    }
}

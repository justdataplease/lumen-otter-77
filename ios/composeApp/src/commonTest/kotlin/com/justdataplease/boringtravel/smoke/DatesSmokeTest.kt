package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.compat.DateTimeFormatter
import com.justdataplease.boringtravel.compat.DateTimeParseException
import com.justdataplease.boringtravel.compat.Instant
import com.justdataplease.boringtravel.compat.JavaDates
import com.justdataplease.boringtravel.compat.JavaFormat
import com.justdataplease.boringtravel.compat.LocalDateTime
import com.justdataplease.boringtravel.compat.Locale
import com.justdataplease.boringtravel.compat.OffsetDateTime
import com.justdataplease.boringtravel.compat.ZoneId
import com.justdataplease.boringtravel.compat.ZoneOffset
import com.justdataplease.boringtravel.compat.ZonedDateTime
import com.justdataplease.boringtravel.compat.atTimeIn
import com.justdataplease.boringtravel.compat.plusYears
import com.justdataplease.boringtravel.ios.JavaText
import com.justdataplease.boringtravel.ios.zoneDst
import com.justdataplease.boringtravel.ios.zoneKnown
import com.justdataplease.boringtravel.ios.zoneOffsetMs
import com.justdataplease.boringtravel.time.CivilDay
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.localDateAt
import com.justdataplease.boringtravel.time.parseLocalDateOrNull
import com.justdataplease.boringtravel.time.startOfDayMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * java.time and String.format as the read-in code calls them through compat/ (JavaTime.kt, JavaTimeParse.kt) and
 * ios/WebUrl.kt's JavaText: patterns written and read, RFC 1123 and ISO moments, time zones by id across summer time
 * (the phone's own rules), and numbers written as Java writes them (Double.toString's shortest digits, half up).
 */
class DatesSmokeTest {
    @Test fun patternsWriteDaysInEnglish() {
        val day = localDate(2027, 9, 10)
        // US English is Java's English, written here without the phone (British English is the phone's: "Sept").
        assertEquals("Fri 10 Sep 2027", DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US).format(day))
        assertEquals("Friday, 10/09/27", DateTimeFormatter.ofPattern("EEEE, dd/MM/yy").format(day))
        assertEquals("September 2027 week 10", DateTimeFormatter.ofPattern("MMMM yyyy 'week' d").format(day))
        assertEquals("10 Sep '27", DateTimeFormatter.ofPattern("d MMM ''yy").format(day))
        assertFailsWith<IllegalArgumentException> { DateTimeFormatter.ofPattern("QQQ yyyy") }
        val summer = Instant.parse("2027-07-01T09:30:00Z").atZone(ZoneId.of("Europe/Athens"))
        val winter = Instant.parse("2027-01-15T09:30:00Z").atZone(ZoneId.of("Europe/Athens"))
        assertEquals("12:30", DateTimeFormatter.ofPattern("HH:mm").format(summer))
        assertEquals("11:30", DateTimeFormatter.ofPattern("HH:mm").format(winter))
        assertEquals("2027-07-01", summer.toLocalDate().toString())
        assertEquals("2026-12-31", Instant.parse("2026-12-31T23:30:00Z").atZone(ZoneId.of("America/New_York")).toLocalDate().toString())
    }

    @Test fun momentsAreReadAsJavaReadsThem() {
        assertEquals("2016-11-01T13:00:00Z", Instant.parse("2016-11-01T06:00:00.000-07:00").toString())
        assertEquals("2019-05-04T08:30:00.250Z", Instant.parse("2019-05-04T08:30:00.250Z").toString())
        assertEquals(Instant.parse("2019-05-04T05:30:00Z"), OffsetDateTime.parse("2019-05-04T08:30+03:00").toInstant())
        assertFailsWith<DateTimeParseException> { Instant.parse("2019-02-29T10:00:00Z") }
        assertFailsWith<IllegalArgumentException> { Instant.parse("someday") }
        assertEquals(
            Instant.parse("2026-11-10T18:42:18Z"),
            ZonedDateTime.parse("Tue, 10 Nov 2026 18:42:18 GMT", DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),
        )
        assertEquals(
            Instant.parse("2026-11-10T18:42:00Z"),
            ZonedDateTime.parse("10 nov 2026 20:42 +0200", DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),
        )
        assertEquals(
            Instant.parse("2019-05-04T10:00:00Z"),
            LocalDateTime.parse("2019-05-04 10:00:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC),
        )
        assertEquals(Instant.parse("2019-05-04T08:30:00Z"), LocalDateTime.parse("2019-05-04T08:30").toInstant(ZoneOffset.UTC))
        assertEquals("1970-01-01T00:00:00Z", JavaDates.isoInstant(0))
        assertNull(JavaDates.offsetDateTime("2019-02-29T10:00Z"))
        assertNull(JavaDates.offsetDateTime("2019-05-04T24:00Z"))
        assertTrue(Instant.parse("2019-05-04T08:30:00Z") < Instant.parse("2019-05-04T08:30:01Z"))
        assertEquals(1557000000L, Instant.ofEpochSecond(1557000000).epochSecond)
    }

    @Test fun zonesByIdAcrossSummerTime() {
        assertTrue(zoneKnown("Europe/Athens"))
        assertFalse(zoneKnown("Mars/Olympus"))
        assertFailsWith<IllegalArgumentException> { ZoneId.of("Mars/Olympus") }
        assertSame(ZoneOffset.UTC, ZoneId.of("Z"))
        val january = Instant.parse("2027-01-15T12:00:00Z").toEpochMilli()
        val july = Instant.parse("2027-07-15T12:00:00Z").toEpochMilli()
        assertEquals(7_200_000L, zoneOffsetMs(january, "Europe/Athens"))
        assertEquals(10_800_000L, zoneOffsetMs(july, "Europe/Athens"))
        assertEquals(-14_400_000L, zoneOffsetMs(july, "America/New_York"))
        assertEquals(19_800_000L, zoneOffsetMs(july, "Asia/Kolkata"))
        assertTrue(zoneDst(july, "Europe/Athens"))
        assertFalse(zoneDst(january, "Europe/Athens"))
        val day = localDate(2027, 7, 1)
        assertEquals(Instant.parse("2027-07-01T09:00:00Z"), day.atTimeIn(12, 0, ZoneId.of("Europe/Athens")))
        assertEquals(Instant.parse("2027-03-28T09:00:00Z"), localDate(2027, 3, 28).atTimeIn(12, 0, ZoneId.of("Europe/Athens")))
        assertEquals(Instant.parse("2027-07-01T12:00:00Z"), day.atTimeIn(12, 0, ZoneOffset.UTC))
        assertEquals(Instant.parse("2027-06-30T21:00:00Z").toEpochMilli(), startOfDayMs(day, "Europe/Athens"))
        assertEquals("2027-07-02", localDateAt(Instant.parse("2027-07-01T21:30:00Z").toEpochMilli(), "Europe/Athens").toString())
    }

    @Test fun daysAndLeapYears() {
        assertEquals("2029-02-28", localDate(2028, 2, 29).plusYears(1).toString())
        assertEquals("2032-02-29", localDate(2028, 2, 29).plusYears(4).toString())
        assertEquals("2027-09-10", parseLocalDateOrNull("2027-09-10").toString())
        assertNull(parseLocalDateOrNull("2027-9-10"))
        assertNull(parseLocalDateOrNull("2027-02-29"))
        assertEquals(5, CivilDay(2027, 9, 10).dayOfWeek)
        assertEquals(CivilDay(2027, 9, 10), CivilDay.ofEpochDay(localDate(2027, 9, 10).toEpochDay()))
        assertEquals("+12345-01-01", CivilDay(12345, 1, 1).iso())
    }

    @Test fun numbersAsJavaWritesThem() {
        assertEquals("18.5", JavaFormat.oneDecimal(18.45))
        assertEquals("18.4", JavaFormat.oneDecimal(18.44))
        assertEquals("0.2", JavaFormat.oneDecimal(0.15))
        assertEquals("-0.0", JavaFormat.oneDecimal(-0.04))
        assertEquals("2.001", JavaFormat.threeDecimals(2.0005))
        assertEquals("1.25", JavaFormat.twoDecimals(1.245))
        assertEquals("1,234,567", JavaFormat.grouped(1_234_567.4))
        assertEquals("-9,877", JavaFormat.grouped(-9_876.5))
        assertEquals("0", JavaFormat.grouped(0.4))
        assertEquals("07", JavaFormat.twoDigits(7))
        assertEquals("12", JavaFormat.twoDigits(12))
        assertEquals("🏝", JavaFormat.codePoint(0x1F3DD))
        assertEquals("é", JavaFormat.codePoint(0xE9))
        assertEquals("0.00", JavaText.fixed(1.0E-5, 2))
        assertEquals("0.000", JavaText.fixed(0.000123, 3))
        assertEquals("12345678.9", JavaText.fixed(12_345_678.9, 1))
        assertEquals("1" + "0".repeat(20), JavaText.fixed(1.0E20, 0))
        assertEquals("3", JavaText.fixed(2.5, 0))
        assertEquals("37.110600", JavaText.fixed(37.1106, 6))
        assertEquals("NaN", JavaText.fixed(Double.NaN, 2))
        assertEquals("d41d8cd9-8f00-3204-a980-0998ecf8427e", JavaText.nameUuid(ByteArray(0)))
    }
}

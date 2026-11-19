package com.justdataplease.boringtravel.time

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneWithName

// iPhone: the engine's own day (a count of days, read through CivilDay) and Foundation's time zones.

actual class LocalDate internal constructor(private val epochDay: Long) {
    actual fun toEpochDay(): Long = epochDay
    actual fun plusDays(daysToAdd: Long): LocalDate = LocalDate(epochDay + daysToAdd)
    actual fun minusDays(daysToSubtract: Long): LocalDate = LocalDate(epochDay - daysToSubtract)
    override fun equals(other: Any?): Boolean = other is LocalDate && other.epochDay == epochDay
    override fun hashCode(): Int = epochDay.hashCode()
    override fun toString(): String = CivilDay.ofEpochDay(epochDay).iso()
}

actual fun localDate(year: Int, month: Int, day: Int): LocalDate {
    require(CivilDay.isValid(year, month, day)) { "No such day: $year-$month-$day" }
    return LocalDate(CivilDay(year, month, day).epochDay)
}

actual fun localDateOfEpochDay(epochDay: Long): LocalDate = LocalDate(epochDay)

actual fun parseLocalDateOrNull(iso: String): LocalDate? = CivilDay.parse(iso)?.let { LocalDate(it.epochDay) }

private const val DAY_MS = 86_400_000L

private fun zone(zoneId: String?): NSTimeZone =
    zoneId?.takeIf { it.isNotBlank() }?.let { NSTimeZone.timeZoneWithName(it) } ?: NSTimeZone.localTimeZone

private fun offsetMs(epochMs: Long, zone: NSTimeZone): Long =
    zone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)) * 1000L

actual fun systemZoneId(): String = NSTimeZone.localTimeZone.name

actual fun localDateAt(epochMs: Long, zoneId: String?): LocalDate =
    LocalDate((epochMs + offsetMs(epochMs, zone(zoneId))).floorDiv(DAY_MS))

actual fun startOfDayMs(day: LocalDate, zoneId: String?): Long {
    // Midnight as if the zone were UTC, moved by the zone's offset there; a second pass settles a day on which the
    // clocks change.
    val z = zone(zoneId)
    val utcMidnight = day.toEpochDay() * DAY_MS
    val first = utcMidnight - offsetMs(utcMidnight, z)
    return utcMidnight - offsetMs(first, z)
}

actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

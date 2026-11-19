package com.justdataplease.boringtravel.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

// Android and the desktop: the engine's day is java.time's own, so nothing in the Android app changes type.

actual typealias LocalDate = java.time.LocalDate

actual fun localDate(year: Int, month: Int, day: Int): LocalDate = java.time.LocalDate.of(year, month, day)

actual fun localDateOfEpochDay(epochDay: Long): LocalDate = java.time.LocalDate.ofEpochDay(epochDay)

actual fun parseLocalDateOrNull(iso: String): LocalDate? = try {
    java.time.LocalDate.parse(iso)
} catch (_: DateTimeParseException) {
    null
}

private fun zone(zoneId: String?): ZoneId =
    zoneId?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

actual fun systemZoneId(): String = ZoneId.systemDefault().id

actual fun localDateAt(epochMs: Long, zoneId: String?): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone(zoneId)).toLocalDate()

actual fun startOfDayMs(day: LocalDate, zoneId: String?): Long = day.atStartOfDay(zone(zoneId)).toInstant().toEpochMilli()

actual fun nowMs(): Long = System.currentTimeMillis()

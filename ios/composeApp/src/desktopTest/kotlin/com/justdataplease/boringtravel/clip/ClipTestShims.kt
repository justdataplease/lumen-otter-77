package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.compat.ZoneId
import java.time.LocalDateTime
import java.time.ZonedDateTime

/*
 * What lets the Android app's clip tests (read in at build time) pass this app's zones: they build moments with
 * java.time (`day.atTime(9, 0).atZone(zone)`), where the zone is now compat's (the clip's ClipInput keeps one).
 */

/** java.time's atZone for a zone given as compat's: the same zone, by its id. */
fun LocalDateTime.atZone(zone: ZoneId): ZonedDateTime = atZone(javaZone(zone))

/** java.time's atStartOfDay for a zone given as compat's. */
fun java.time.LocalDate.atStartOfDay(zone: ZoneId): ZonedDateTime = atStartOfDay(javaZone(zone))

private fun javaZone(zone: ZoneId): java.time.ZoneId = java.time.ZoneId.of(if (zone.id == "Z") "UTC" else zone.id)

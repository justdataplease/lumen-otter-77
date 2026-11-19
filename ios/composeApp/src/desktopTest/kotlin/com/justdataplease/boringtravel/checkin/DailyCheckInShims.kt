package com.justdataplease.boringtravel.checkin

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.time.LocalDate
import java.time.ZoneId

/*
 * What the Android app's DailyCheckInTest (read in as it is) calls with a java.time zone: here DailyCheckIn takes a
 * zone by its id.
 */

fun DailyCheckIn.localDay(now: Long, zone: ZoneId): LocalDate = localDay(now, zone.id)

fun DailyCheckIn.draft(
    existing: Entry?, prompt: CheckInPrompt, place: CheckInPlace, now: Long, zone: ZoneId, member: String? = null,
    newId: () -> String = { java.util.UUID.randomUUID().toString() },
): Entry = draft(existing, prompt, place, now, zone.id, member, newId)

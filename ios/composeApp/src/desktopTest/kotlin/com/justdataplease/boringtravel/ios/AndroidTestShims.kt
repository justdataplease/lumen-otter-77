package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import java.time.LocalDate
import java.time.ZoneId

/*
 * What the Android app's own tests (read in by the build's androidTestSources) call in the Android form, where this
 * app's version takes plain values: a time zone as its id.
 */

/** DayPlace.label with a java.time zone, as the Android app's takes it. */
fun DayPlace.label(day: LocalDate, stops: List<Stop>, items: List<PlanItem>, zone: ZoneId, now: Long = System.currentTimeMillis()): String? =
    label(day, stops, items, zone.id, now)

/** ItemNotes.newEntry with a java.time zone, as the Android app's takes it (its ItemNotesTest). */
fun ItemNotes.newEntry(id: String, item: PlanItem, items: List<PlanItem>, now: Long, zone: ZoneId): com.justdataplease.boringtravel.data.db.Entry =
    newEntry(id, item, items, now, zone.id)

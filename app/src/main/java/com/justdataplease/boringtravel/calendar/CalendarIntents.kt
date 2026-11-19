package com.justdataplease.boringtravel.calendar

import android.content.Intent
import android.provider.CalendarContract
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** When one plan item happens, as a calendar app wants it: start and end in epoch ms, and whether it is all day. */
data class ItemWhen(val beginMs: Long, val endMs: Long, val allDay: Boolean)

/**
 * One plan item handed to the phone's calendar app (Intent.ACTION_INSERT): the app opens with the event filled in
 * and the person saves it there. No calendar permission. A copy: later plan changes don't reach it (adding the whole
 * trip from the trip's ⋮ › Calendar keeps every item in step). [ItemWhen] is pure and unit tested.
 */
object CalendarIntents {
    /** A timed item at its time in [zone] for [TripEvents.DEFAULT_MINUTES]; an untimed one all day (UTC midnights). */
    fun whenOf(day: LocalDate, minutes: Int?, zone: ZoneId): ItemWhen = whenOf(
        if (minutes == null) EventTime.Days(day, day.plusDays(1))
        else minutes.coerceIn(0, 24 * 60 - 1).let { EventTime.At(day.atTime(it / 60, it % 60), TripEvents.DEFAULT_MINUTES) },
        zone,
    )

    /** [time] as the calendar app takes it: timed in [zone], or all day at UTC midnights. */
    fun whenOf(time: EventTime, zone: ZoneId): ItemWhen = when (time) {
        is EventTime.Days -> ItemWhen(
            time.first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), time.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            allDay = true,
        )
        is EventTime.At -> time.start.atZone(zone).let {
            ItemWhen(it.toInstant().toEpochMilli(), it.plusMinutes(time.minutes.toLong()).toInstant().toEpochMilli(), allDay = false)
        }
    }

    /** The event's title: the item's, else its place's, else its kind (as the trip's calendar events are named). */
    fun title(item: PlanItem): String = item.title.trim()
        .ifBlank { item.placeName.substringBefore(',').trim() }
        .ifBlank { PlanKind.of(item.kind).label }

    /** The event text: the item's note and link (as in the trip's calendar events), then [tripTitle]. */
    fun description(item: PlanItem, tripTitle: String): String =
        listOf(TripEvents.itemDescription(item), tripTitle.trim()).filter { it.isNotBlank() }.joinToString("\n\n")

    /** The insert intent for [item] (it must be on a day), with its link and [tripTitle] under the note. */
    fun insert(item: PlanItem, tripTitle: String, zone: ZoneId): Intent? {
        val w = whenOf(TripEvents.itemTime(item) ?: return null, zone)
        val description = description(item, tripTitle)
        return Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, w.beginMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, w.endMs)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, w.allDay)
            .putExtra(CalendarContract.Events.TITLE, title(item))
            .putExtra(CalendarContract.Events.EVENT_LOCATION, item.placeName.trim())
            .putExtra(CalendarContract.Events.DESCRIPTION, description)
            .putExtra(CalendarContract.Events.EVENT_TIMEZONE, if (w.allDay) "UTC" else zone.id)
    }
}

package com.justdataplease.boringtravel.model

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.util.Dates
import com.justdataplease.boringtravel.time.LocalDate

/**
 * When a plan item ends (owner, 2026-11-12: "in each trip item add from date time to date time"; a concert at 19:00
 * for 3 hours, a hotel for 3 nights). The end is [PlanItem.endDays] days after the item's day at
 * [PlanItem.endMinutes]; an item that ends on another day shows its end as its own row on that day ("Hostal Alicante ·
 * Check-out"), which opens the same item, unless the traveller turns it off ([PlanItem.endRow]). The clip stops there
 * too. Ideas (no day) have no end.
 */
object PlanSpan {
    /** The longest span: two months. */
    const val MAX_DAYS = 60
    private const val DAY_MIN = 24 * 60

    /** Whether [item] ends at a time or on a later day. */
    fun hasEnd(item: PlanItem): Boolean = item.day != null && (item.endMinutes != null || days(item) > 0)

    /** Days from the item's day to its end (0 = the same day, and for an idea). */
    fun days(item: PlanItem): Int = if (item.day == null) 0 else (item.endDays ?: 0).coerceIn(0, MAX_DAYS)

    /** The day it ends, or null without an end. */
    fun endDate(item: PlanItem): LocalDate? = if (hasEnd(item)) item.day?.plusDays(days(item).toLong()) else null

    /** Whether the end shows as its own row (and a stop in the clip): the traveller's choice, else when it ends on another day. */
    fun endRow(item: PlanItem): Boolean = hasEnd(item) && (item.endRow ?: (days(item) > 0))

    /** The day an item's end row shows on, or null when it has none. */
    fun endRowDate(item: PlanItem): LocalDate? = if (endRow(item)) endDate(item) else null

    /** The traveller's own order (position, then time, then id); [dayOrder] puts the timed items in time order on top. */
    val ORDER: Comparator<PlanItem> = compareBy<PlanItem>({ it.position }, { it.timeMinutes }, { it.id })

    /**
     * One day's items (or the Ideas) in plan order (owner, 2026-11-12: "by default order by time"): items with a time
     * in time order, and an item without one keeps its place between them. Laid out by [ORDER] first; the timed items
     * then fill the places timed items hold, earliest first (equal times keep [ORDER]). The List, the calendar, the
     * legs and the clip all read days in this order, so they agree.
     */
    fun dayOrder(items: List<PlanItem>): List<PlanItem> {
        val laidOut = items.sortedWith(ORDER)
        val timed = laidOut.filter { it.timeMinutes != null }.sortedBy { it.timeMinutes }.iterator()
        return laidOut.map { if (it.timeMinutes != null) timed.next() else it }
    }

    /** All of [items] in plan order: the days in date order, each in [dayOrder], then the Ideas. */
    fun ordered(items: List<PlanItem>): List<PlanItem> {
        val byDay = items.groupBy { it.day }
        return byDay.keys.filterNotNull().sortedBy { it.toEpochDay() }.flatMap { dayOrder(byDay.getValue(it)) } + dayOrder(byDay[null].orEmpty())
    }

    /** The end row's name: its own, else the item's title. */
    fun endName(item: PlanItem): String = item.endTitle?.trim()?.takeIf { it.isNotEmpty() } ?: item.title

    /** The small tag on the start row: "Check-in" for a stay whose end has its own row. */
    fun startTag(item: PlanItem): String? = if (item.kind == PlanKind.Stay.id && endRow(item)) "Check-in" else null

    /** The small tag on the end row. */
    fun endTag(item: PlanItem): String = if (item.kind == PlanKind.Stay.id) "Check-out" else "Ends"

    /** Minutes from start to end when both times are known, else null. */
    fun minutes(item: PlanItem): Int? {
        if (!hasEnd(item)) return null
        val start = item.timeMinutes ?: return null
        val end = item.endMinutes ?: return null
        return (days(item) * DAY_MIN + end - start).takeIf { it > 0 }
    }

    /** How long it lasts in hours, for the clip's time at the stop: exact with both times, else whole days; null without an end. */
    fun hours(item: PlanItem): Double? {
        if (!hasEnd(item)) return null
        minutes(item)?.let { return it / 60.0 }
        val d = days(item)
        return if (d > 0) d * 24.0 else null
    }

    /** "3 nights", "2 days", "3 h", "2 h 45", "45 min"; null when it can't be said. */
    fun length(item: PlanItem): String? {
        if (!hasEnd(item)) return null
        val d = days(item)
        if (item.kind == PlanKind.Stay.id && d > 0) return if (d == 1) "1 night" else "$d nights"
        val m = minutes(item)
        if (m != null && m < DAY_MIN) return duration(m)
        return if (d > 0) Dates.days(d + 1) else null
    }

    /** "3 h", "2 h 45", "45 min". */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
    }

    /**
     * The line under the start row of an item that ends on another day: "until Sun 15 · 11:00 · 3 nights". Null for
     * one that ends the same day (the time column shows both times).
     */
    fun until(item: PlanItem): String? {
        val end = endDate(item) ?: return null
        if (days(item) == 0) return null
        return listOfNotNull("until " + Dates.dayShort(end), item.endMinutes?.let(Dates::time), length(item)).joinToString(" · ")
    }

    /** "19:00–22:00", "20:30 → Sun 15 11:00", or just the start time; null without a time or an end. */
    fun times(item: PlanItem): String? {
        val start = item.timeMinutes?.let(Dates::time)
        val end = endDate(item) ?: return start
        val endTime = item.endMinutes?.let(Dates::time)
        return if (days(item) == 0) listOfNotNull(start, endTime).joinToString("–").ifEmpty { null }
        else listOfNotNull(start, "→", Dates.dayShort(end), endTime).joinToString(" ")
    }

    /**
     * [item] as it should be saved: no end without a day; times within the day; an end time before the start on the
     * same day is the next morning (a party until 02:00); an end equal to the start is none; the end's own name only
     * when it differs from the title.
     */
    fun normalized(item: PlanItem): PlanItem {
        if (item.day == null) return item.copy(endDays = null, endMinutes = null, endTitle = null, endRow = null)
        var days = item.endDays?.coerceIn(0, MAX_DAYS)?.takeIf { it > 0 }
        val end = item.endMinutes?.coerceIn(0, DAY_MIN - 1)
        val start = item.timeMinutes
        if (days == null && end != null && start != null && end < start) days = 1
        val none = days == null && (end == null || end == start)
        val title = item.endTitle?.trim()?.takeIf { it.isNotEmpty() && it != item.title.trim() }
        return item.copy(endDays = if (none) null else days, endMinutes = if (none) null else end, endTitle = title)
    }

    /** Whether stored end values are possible ([endDays] within [MAX_DAYS], [endMinutes] within the day); a backup or shared file with others loses them. */
    fun validEnd(endDays: Int?, endMinutes: Int?): Boolean =
        (endDays == null || endDays in 0..MAX_DAYS) && (endMinutes == null || endMinutes in 0 until DAY_MIN)

    /** Days from [day] to [date], or null when [date] is before it or more than [MAX_DAYS] after. */
    fun daysAfter(day: LocalDate, date: LocalDate): Int? = (date.toEpochDay() - day.toEpochDay()).takeIf { it in 0..MAX_DAYS }?.toInt()

    /** [item] ending on [date] at [minutes] (null: no time), kept as days after its own day (within [MAX_DAYS]). */
    fun endingAt(item: PlanItem, date: LocalDate, minutes: Int?): PlanItem {
        val day = item.day ?: return item.copy(endDays = null, endMinutes = null)
        val days = (date.toEpochDay() - day.toEpochDay()).toInt().coerceIn(0, MAX_DAYS)
        return item.copy(endDays = days.takeIf { it > 0 }, endMinutes = minutes)
    }

    /** The id of an item's end row and clip stop. */
    fun endId(itemId: String): String = itemId + END_SUFFIX

    /** The item behind a row or stop id ([endId] or the item's own). */
    fun itemIdOf(id: String): String = id.removeSuffix(END_SUFFIX)

    private const val END_SUFFIX = "~end"

    /**
     * Each dated day's rows, in date order: the day's items ([dayOrder]) with the ends of [items] that show as rows on it,
     * each after the last row timed at or before its time (an end without a time first in the day), never before its
     * own start. A day with only an end on it (the check-out morning) is there too. The plan's List and calendar and
     * the clip's planned route all read the days from here.
     */
    fun byDay(items: List<PlanItem>): Map<LocalDate, List<PlanStep>> {
        val live = items.filter { it.deletedAt == null }
        val starts = live.filter { it.day != null }.groupBy { it.day!! }
        val ends = live.filter { endRow(it) }.groupBy { endDate(it)!! }
        return (starts.keys + ends.keys).sortedBy { it.toEpochDay() }.associateWith { day ->
            steps(dayOrder(starts[day].orEmpty()), ends[day].orEmpty())
        }
    }

    /** One day's rows: [dayItems] in order, with [ends] (the items whose end row is on this day) put in by time. */
    private fun steps(dayItems: List<PlanItem>, ends: List<PlanItem>): List<PlanStep> {
        val out = dayItems.mapTo(ArrayList()) { PlanStep(it) }
        for (item in ends.sortedWith(compareBy<PlanItem>({ it.endMinutes ?: -1 }, { it.day?.toEpochDay() }, { it.position }, { it.id }))) {
            val t = item.endMinutes
            // Ends without a time open the day, in order; a timed one goes after them and after the rows timed before it.
            var at = out.indexOfLast { it.end && it.minutes == null } + 1
            if (t != null) at = maxOf(at, out.indexOfLast { s -> s.minutes?.let { it <= t } == true } + 1)
            val own = out.indexOfFirst { !it.end && it.item.id == item.id }
            if (own >= 0) at = maxOf(at, own + 1)
            out.add(at, PlanStep(item, end = true))
        }
        return out
    }
}

/** A row of a plan day: an item, or the end of one ([end], see [PlanSpan.steps]). */
data class PlanStep(val item: PlanItem, val end: Boolean = false) {
    /** The row's own id: the item's, or its end's ([PlanSpan.endId]). */
    val id: String get() = if (end) PlanSpan.endId(item.id) else item.id

    /** The row's time: the start's, or the end's. */
    val minutes: Int? get() = if (end) item.endMinutes else item.timeMinutes
}

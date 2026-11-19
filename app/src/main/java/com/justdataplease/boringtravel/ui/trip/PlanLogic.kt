package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.data.db.PlanItem
import java.time.LocalDate

/**
 * Order within a plan day. Items keep a double `position`, so one item moves by taking a value between its new
 * neighbours and nothing else is rewritten. Every function takes the day's other items (any order, without the
 * moving item) and returns the new position.
 */
object PlanOrder {
    /** Position for slot [index] among [others] (0 = first, others.size = last). */
    fun positionAt(others: List<PlanItem>, index: Int): Double {
        val sorted = others.sortedBy { it.position }
        return when {
            sorted.isEmpty() -> 1.0
            index <= 0 -> sorted.first().position - 1.0
            index >= sorted.size -> sorted.last().position + 1.0
            else -> (sorted[index - 1].position + sorted[index].position) / 2
        }
    }

    fun end(others: List<PlanItem>): Double = positionAt(others, others.size)

    /**
     * Where an item at [minutes] belongs: before the first item with a later time, else right after the last item
     * with a time (untimed items at the end stay at the end), else at the end.
     */
    fun forTime(others: List<PlanItem>, minutes: Int): Double {
        val sorted = others.sortedBy { it.position }
        val later = sorted.indexOfFirst { (it.timeMinutes ?: -1) > minutes }
        val index = when {
            later >= 0 -> later
            sorted.any { it.timeMinutes != null } -> sorted.indexOfLast { it.timeMinutes != null } + 1
            else -> sorted.size
        }
        return positionAt(sorted, index)
    }

    /** True when two neighbours got so close that further halving would lose order; then renumber. */
    fun crowded(items: List<PlanItem>): Boolean =
        items.sortedBy { it.position }.zipWithNext().any { (a, b) -> b.position - a.position < 1e-6 }

    /** 1, 2, 3… in the current order. */
    fun renumber(items: List<PlanItem>): List<PlanItem> =
        items.sortedBy { it.position }.mapIndexed { i, it -> it.copy(position = (i + 1).toDouble()) }
}

/** The days a plan shows. */
object PlanDays {
    /**
     * Every day of the trip, plus any day an item sits on or its end row falls on outside the dates (so nothing gets
     * lost when dates change).
     */
    fun days(start: LocalDate?, end: LocalDate?, items: List<PlanItem>): List<LocalDate> {
        val out = sortedSetOf<LocalDate>()
        if (start != null && end != null && !end.isBefore(start)) {
            var d: LocalDate = start
            while (!d.isAfter(end)) {
                out += d
                d = d.plusDays(1)
            }
        }
        items.mapNotNullTo(out) { it.day }
        items.mapNotNullTo(out) { PlanSpan.endRowDate(it) }
        return out.toList()
    }

    /** "Day 4" counting from the start, or null outside the trip's dates. */
    fun number(start: LocalDate?, end: LocalDate?, day: LocalDate): Int? {
        if (start == null || end == null || day.isBefore(start) || day.isAfter(end)) return null
        return (day.toEpochDay() - start.toEpochDay() + 1).toInt()
    }

    /** The day to open on: today during the trip, else the first day; null (Ideas) when there are no days. */
    fun initial(days: List<LocalDate>, today: LocalDate): LocalDate? = days.firstOrNull { it == today } ?: days.firstOrNull()

    /** Items moved with the trip when its start moves, so "day 4" stays day 4. Ideas stay ideas. */
    fun shift(items: List<PlanItem>, oldStart: LocalDate?, newStart: LocalDate?): List<PlanItem> {
        if (oldStart == null || newStart == null || oldStart == newStart) return emptyList()
        val delta = newStart.toEpochDay() - oldStart.toEpochDay()
        return items.filter { it.day != null }.map { it.copy(day = it.day!!.plusDays(delta)) }
    }
}

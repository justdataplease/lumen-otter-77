package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.PlanStep
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.tripcal.TripGrid
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * A plan item on one line, with the travel leg that leads to it folded into a vehicle (null = none shown). [end]: the
 * row of the item's end on a later day ("Hostal Alicante · Check-out", [PlanSpan.steps]); it opens the same item.
 */
internal data class PlanLine(val step: PlanStep, val leg: Vehicle?) {
    constructor(item: PlanItem, leg: Vehicle?) : this(PlanStep(item), leg)

    val item: PlanItem get() = step.item
    val end: Boolean get() = step.end

    /** The row's id: the item's, or its end's ([PlanSpan.endId]); legs are keyed by it. */
    val id: String get() = step.id

    companion object {
        /** [step] with the vehicle of the leg arriving at it from [legs]; Auto ("not chosen") shows none. */
        fun of(step: PlanStep, legs: Map<String, Vehicle?>) = PlanLine(step, legs[step.id]?.takeIf { it != Vehicle.Auto })
    }
}

/** The plan's day key for its Ideas (TripState.DAY holds an epoch day or this). */
internal const val PLAN_IDEAS = "ideas"

/**
 * The List view's sections: every day in order with its items, runs of empty days folded into one line
 * ("Days 5–7 · nothing planned"), then Ideas. The same rows show collapsed (one line per item) and expanded (each day
 * in full, with the travel between places).
 */
internal object PlanListLogic {
    sealed interface Section {
        data class Day(val day: LocalDate, val number: Int?, val lines: List<PlanLine>) : Section
        /** Consecutive days with nothing planned, [from]..[to] (the same day for one). */
        data class Empty(val from: LocalDate, val to: LocalDate) : Section
        data class Ideas(val lines: List<PlanLine>) : Section
    }

    /** One row of the List. Collapsed and expanded show the same rows under the same keys, so a switch keeps the place. */
    sealed interface Row {
        val key: String

        data class DayHead(val day: LocalDate, val number: Int?) : Row {
            override val key: String get() = "d-${day.toEpochDay()}"
        }

        data class Line(val line: PlanLine) : Row {
            override val key: String get() = "i-${line.id}"
        }

        /** A day kept open with nothing on it: today, or the day the plan was opened on. */
        data class DayEmpty(val day: LocalDate) : Row {
            override val key: String get() = "de-${day.toEpochDay()}"
        }

        /** "Days 5–7 · nothing planned". */
        data class Folded(val from: LocalDate, val to: LocalDate) : Row {
            override val key: String get() = "f-${from.toEpochDay()}"
        }

        data class IdeasHead(val count: Int) : Row {
            override val key: String get() = "ideas"
        }

        data object IdeasEmpty : Row {
            override val key: String get() = "ideas-empty"
        }
    }

    /** Where the List opens. */
    sealed interface Focus {
        data class Day(val day: LocalDate) : Focus
        data object Ideas : Focus
    }

    /**
     * [legs] maps an item's id to the vehicle of the leg that arrives at it (the expanded List's leg rows); Auto ("not
     * chosen") folds to nothing. Ideas come last and only when there are any, or when the plan has no days at all.
     * Days in [keep] (today, the day asked for) are never folded away, so their header shows even with nothing on them.
     */
    fun sections(
        start: LocalDate?,
        end: LocalDate?,
        items: List<PlanItem>,
        legs: Map<String, Vehicle?>,
        keep: Set<LocalDate> = emptySet(),
    ): List<Section> {
        val rows = PlanSpan.byDay(items)
        val out = mutableListOf<Section>()
        var emptyFrom: LocalDate? = null
        var emptyTo: LocalDate? = null
        fun flush() {
            val f = emptyFrom ?: return
            out += Section.Empty(f, emptyTo ?: f)
            emptyFrom = null
            emptyTo = null
        }
        val days = PlanDays.days(start, end, items)
        for (d in days) {
            val list = rows[d].orEmpty()
            if (list.isEmpty() && d !in keep) {
                // Only consecutive days fold together.
                if (emptyTo != null && emptyTo!!.plusDays(1) != d) flush()
                if (emptyFrom == null) emptyFrom = d
                emptyTo = d
            } else {
                flush()
                out += Section.Day(d, PlanDays.number(start, end, d), list.map { PlanLine.of(it, legs) })
            }
        }
        flush()
        val ideas = items.filter { it.day == null }
        if (ideas.isNotEmpty() || days.isEmpty()) out += Section.Ideas(PlanSpan.dayOrder(ideas).map { PlanLine.of(PlanStep(it), legs) })
        return out
    }

    /** The rows of [sections] in order; [ideasHeader] false leaves out the Ideas header (a plan with no days has its own). */
    fun rows(sections: List<Section>, ideasHeader: Boolean = true): List<Row> = buildList {
        for (s in sections) when (s) {
            is Section.Day -> {
                add(Row.DayHead(s.day, s.number))
                if (s.lines.isEmpty()) add(Row.DayEmpty(s.day)) else s.lines.forEach { add(Row.Line(it)) }
            }
            is Section.Empty -> add(Row.Folded(s.from, s.to))
            is Section.Ideas -> {
                if (ideasHeader) add(Row.IdeasHead(s.lines.size))
                if (s.lines.isEmpty()) add(Row.IdeasEmpty) else s.lines.forEach { add(Row.Line(it)) }
            }
        }
    }

    /**
     * Where the List opens: the day asked for ([key]: an epoch day, from the Calendar or after adding a shared link, or
     * [PLAN_IDEAS]), else today while the trip is on; null = the top.
     */
    fun focus(key: String?, days: List<LocalDate>, today: LocalDate): Focus? {
        if (key == PLAN_IDEAS) return Focus.Ideas
        val asked = key?.toLongOrNull()?.let(LocalDate::ofEpochDay)?.takeIf { it in days }
        return (asked ?: today.takeIf { it in days })?.let { Focus.Day(it) }
    }

    /** The row to bring to the top for [focus]: its day's header or the folded line it is in, or the Ideas header. */
    fun indexOf(rows: List<Row>, focus: Focus?): Int? {
        val i = when (focus) {
            null -> -1
            is Focus.Day -> rows.indexOfFirst { r ->
                (r is Row.DayHead && r.day == focus.day) || (r is Row.Folded && focus.day >= r.from && focus.day <= r.to)
            }
            Focus.Ideas -> rows.indexOfFirst { it is Row.IdeasHead }
        }
        return i.takeIf { it >= 0 }
    }
}

/** The Calendar view's grid: one month block per month the plan touches, weeks starting on [DayOfWeek]. */
internal object PlanCalendarLogic {
    /** A cell: null pads the week before the 1st and after the month's last day. */
    data class Week(val days: List<LocalDate?>)
    data class Month(val month: YearMonth, val weeks: List<Week>)

    /**
     * The months to show, in order: every month with a trip day or a dated item. A trip from 28 Jan to 3 Feb shows
     * January and February; an item parked far outside the dates adds only its own month, not the months between.
     */
    fun months(days: List<LocalDate>, firstDay: DayOfWeek): List<Month> =
        days.map { YearMonth.from(it) }.distinct().sorted().map { m -> Month(m, weeks(m, firstDay)) }

    /** The weeks of [month] (the Calendar tab's grid, [TripGrid.weeks]), other months' days left blank. */
    fun weeks(month: YearMonth, firstDay: DayOfWeek): List<Week> =
        TripGrid.weeks(month, firstDay).map { week -> Week(week.map { d -> d.takeIf { YearMonth.from(it) == month } }) }

    /** The weekday header in order, starting on [firstDay]. */
    fun weekdays(firstDay: DayOfWeek): List<DayOfWeek> = List(7) { firstDay.plus(it.toLong()) }

    /** True when [day] is one of the trip's own days. */
    fun inTrip(start: LocalDate?, end: LocalDate?, day: LocalDate): Boolean = PlanDays.number(start, end, day) != null

    /** Rows per day, for the dots (Ideas left out): an end on its own row counts on its day. */
    fun counts(items: List<PlanItem>): Map<LocalDate, Int> = PlanSpan.byDay(items).mapValues { it.value.size }
}

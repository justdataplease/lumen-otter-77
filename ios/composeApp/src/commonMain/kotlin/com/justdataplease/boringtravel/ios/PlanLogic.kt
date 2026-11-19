package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanSpan
import com.justdataplease.boringtravel.model.PlanStep
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDateOfEpochDay
import com.justdataplease.boringtravel.util.Dates

/*
 * The plan's order, days, List and Calendar views and drops as the Android app works them out (ui/trip/PlanLogic.kt,
 * PlanViewLogic.kt and PlanDrop.kt there), written again here in plain Kotlin because those files compare days with
 * java.time (and the Calendar's months are YearMonth, its weekdays DayOfWeek). Same names, rules and results; they go
 * once the Android files move into the engine. Days are compared by their epoch day.
 */

private fun LocalDate.ep(): Long = toEpochDay()

/**
 * Order within a plan day. Items keep a double `position`, so one item moves by taking a value between its new
 * neighbours and nothing else is rewritten. Every function takes the day's other items (any order, without the moving
 * item) and returns the new position.
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
     * Where an item at [minutes] belongs: before the first item with a later time, else right after the last item with
     * a time (untimed items at the end stay at the end), else at the end.
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
        val out = HashSet<Long>()
        if (start != null && end != null && end.ep() >= start.ep()) {
            for (d in start.ep()..end.ep()) out += d
        }
        items.mapNotNullTo(out) { it.day?.ep() }
        items.mapNotNullTo(out) { PlanSpan.endRowDate(it)?.ep() }
        return out.sorted().map(::localDateOfEpochDay)
    }

    /** "Day 4" counting from the start, or null outside the trip's dates. */
    fun number(start: LocalDate?, end: LocalDate?, day: LocalDate): Int? {
        if (start == null || end == null || day.ep() < start.ep() || day.ep() > end.ep()) return null
        return (day.ep() - start.ep() + 1).toInt()
    }

    /** The day to open on: today during the trip, else the first day; null (Ideas) when there are no days. */
    fun initial(days: List<LocalDate>, today: LocalDate): LocalDate? = days.firstOrNull { it == today } ?: days.firstOrNull()
}

/**
 * A plan item on one line, with the travel leg that leads to it folded into a vehicle (null = none shown). [end]: the
 * row of the item's end on a later day ("Hostal Alicante · Check-out", [PlanSpan.steps]); it opens the same item.
 */
data class PlanLine(val step: PlanStep, val leg: Vehicle?) {
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

/** The plan's day key for its Ideas (the Calendar view's picked day is an epoch day or this). */
const val PLAN_IDEAS = "ideas"

/**
 * The List view's sections: every day in order with its items, runs of empty days folded into one line
 * ("Days 5–7 · nothing planned"), then Ideas. The same rows show collapsed (one line per item) and expanded (each day in
 * full, with the travel between places).
 */
object PlanListLogic {
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
            override val key: String get() = "d-${day.ep()}"
        }

        data class Line(val line: PlanLine) : Row {
            override val key: String get() = "i-${line.id}"
        }

        /** A day kept open with nothing on it: today, or the day the plan was opened on. */
        data class DayEmpty(val day: LocalDate) : Row {
            override val key: String get() = "de-${day.ep()}"
        }

        /** "Days 5–7 · nothing planned". */
        data class Folded(val from: LocalDate, val to: LocalDate) : Row {
            override val key: String get() = "f-${from.ep()}"
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
     * chosen") folds to nothing. Ideas come last and only when there are any, or when the plan has no days at all. Days
     * in [keep] (today, the day asked for, a named day) are never folded away, so their header shows even with nothing
     * on them.
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
        val kept = keep.mapTo(HashSet()) { it.ep() }
        val days = PlanDays.days(start, end, items)
        for (d in days) {
            val list = rows[d].orEmpty()
            if (list.isEmpty() && d.ep() !in kept) {
                // Only consecutive days fold together.
                val to = emptyTo
                if (to != null && to.ep() + 1 != d.ep()) flush()
                if (emptyFrom == null) emptyFrom = d
                emptyTo = d
            } else {
                flush()
                out += Section.Day(d, PlanDays.number(start, end, d), list.map { PlanLine.of(it, legs) })
            }
        }
        flush()
        val ideas = items.filter { it.day == null && it.deletedAt == null }
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
     * Where the List opens: the day asked for ([key]: an epoch day, from the Calendar, or [PLAN_IDEAS]), else today
     * while the trip is on; null = the top.
     */
    fun focus(key: String?, days: List<LocalDate>, today: LocalDate): Focus? {
        if (key == PLAN_IDEAS) return Focus.Ideas
        val asked = key?.toLongOrNull()?.let(::localDateOfEpochDay)?.takeIf { it in days }
        return (asked ?: today.takeIf { it in days })?.let { Focus.Day(it) }
    }

    /** The row to bring to the top for [focus]: its day's header or the folded line it is in, or the Ideas header. */
    fun indexOf(rows: List<Row>, focus: Focus?): Int? {
        val i = when (focus) {
            null -> -1
            is Focus.Day -> rows.indexOfFirst { r ->
                (r is Row.DayHead && r.day == focus.day) || (r is Row.Folded && focus.day.ep() >= r.from.ep() && focus.day.ep() <= r.to.ep())
            }
            Focus.Ideas -> rows.indexOfFirst { it is Row.IdeasHead }
        }
        return i.takeIf { it >= 0 }
    }
}

/** The Calendar view's grid: one month block per month the plan touches, weeks starting on the phone's first day. */
object PlanCalendarLogic {
    /** A cell: null pads the week before the 1st and after the month's last day. */
    data class Week(val days: List<LocalDate?>)
    data class Month(val month: CalMonth, val weeks: List<Week>)

    /**
     * The months to show, in order: every month with a trip day or a dated item. A trip from 28 Jan to 3 Feb shows
     * January and February; an item parked far outside the dates adds only its own month, not the months between.
     * [firstDay]: 1 = Monday … 7 = Sunday.
     */
    fun months(days: List<LocalDate>, firstDay: Int): List<Month> =
        days.map { CalMonth.of(it) }.distinct().sortedBy { it.index }.map { m -> Month(m, weeks(m, firstDay)) }

    /** The weeks of [month] (the Calendar tab's grid, [TripGrid.weeks]), other months' days left blank. */
    fun weeks(month: CalMonth, firstDay: Int): List<Week> =
        TripGrid.weeks(month, firstDay).map { week -> Week(week.map { d -> d.takeIf { it in month } }) }

    /** The weekday header in order, starting on [firstDay] (1 = Monday … 7 = Sunday). */
    fun weekdays(firstDay: Int): List<Int> = List(7) { (firstDay - 1 + it) % 7 + 1 }

    /** True when [day] is one of the trip's own days. */
    fun inTrip(start: LocalDate?, end: LocalDate?, day: LocalDate): Boolean = PlanDays.number(start, end, day) != null

    /** Rows per day, for the dots (Ideas left out): an end on its own row counts on its day. */
    fun counts(items: List<PlanItem>): Map<LocalDate, Int> = PlanSpan.byDay(items).mapValues { it.value.size }
}

/**
 * What dropping a dragged plan item means (the Android app's PlanDrop: "give the possibility to drag to change position.
 * If change position to another day automatically update the time"). The List drags, this decides.
 */
object PlanDrop {
    private const val DAY = 24 * 60
    private const val HOUR = 60

    /** Where a dragged row ended up: its day (null: the Ideas) and that day's items above and below it, in plan order. */
    data class Target(val day: LocalDate?, val before: List<PlanItem>, val after: List<PlanItem>)

    /**
     * Whether a dragged item moves past [row]: items (an end row too, though it can't be lifted itself), day headers,
     * folded empty days and the Ideas header. The "nothing planned" lines stay put under their header.
     */
    fun isTarget(row: PlanListLogic.Row): Boolean =
        row !is PlanListLogic.Row.DayEmpty && row !is PlanListLogic.Row.IdeasEmpty

    /**
     * Where the row [key] sits in [rows] (the List's rows as the drag left them): under the nearest day header above it
     * (a folded run of empty days counts as its first day, the Ideas header as the Ideas), between the items of that day
     * above and below it. Above every header it joins the first day; with no headers at all (a trip without dates) it
     * is an idea. An item's end row ([PlanLine.end]) is never a neighbour: it follows its own item. Null when the row
     * isn't there.
     */
    fun target(rows: List<PlanListLogic.Row>, key: String): Target? {
        val at = rows.indexOfFirst { it.key == key }
        if (at < 0) return null
        fun isHead(r: PlanListLogic.Row) =
            r is PlanListLogic.Row.DayHead || r is PlanListLogic.Row.Folded || r is PlanListLogic.Row.IdeasHead
        fun nextHead(from: Int) = (from until rows.size).firstOrNull { isHead(rows[it]) } ?: rows.size
        fun items(range: IntRange) = range.filter { it != at }
            .mapNotNull { (rows[it] as? PlanListLogic.Row.Line)?.line?.takeIf { l -> !l.end }?.item }
        fun dayOf(head: PlanListLogic.Row) = when (head) {
            is PlanListLogic.Row.DayHead -> head.day
            is PlanListLogic.Row.Folded -> head.from
            else -> null
        }
        val headAt = (at - 1 downTo 0).firstOrNull { isHead(rows[it]) }
        if (headAt != null) return Target(dayOf(rows[headAt]), items(headAt + 1 until at), items(at + 1 until nextHead(at + 1)))
        val first = rows.indexOfFirst(::isHead)
        // No headers: a plan without dates, every row an idea.
        if (first < 0) return Target(null, items(0 until at), items(at + 1 until rows.size))
        return Target(dayOf(rows[first]), emptyList(), items(first + 1 until nextHead(first + 1)))
    }

    /**
     * [item] dropped on [day] (null: the Ideas) between [before] and [after], that day's other items in plan order. It
     * takes the day (its end follows: [PlanItem.endDays] counts from its day), a position between its new neighbours
     * and, when its time no longer fits between theirs, a [fit] time with its length kept. Neighbours whose positions are
     * out of step with the plan order are renumbered in the new order. Returns the items to save, the moved one first;
     * empty when the drop changes nothing.
     */
    fun drop(item: PlanItem, day: LocalDate?, before: List<PlanItem>, after: List<PlanItem>): List<PlanItem> {
        val above = before.filter { it.id != item.id }
        val below = after.filter { it.id != item.id }
        val time = item.timeMinutes?.let { t ->
            fit(t, above.lastOrNull { it.timeMinutes != null }?.timeMinutes, below.firstOrNull { it.timeMinutes != null }?.timeMinutes)
        }
        val placed = withTime(item.copy(day = day), time)
        val a = above.lastOrNull()
        val b = below.firstOrNull()
        val inStep = (above + below).zipWithNext().all { (x, y) -> x.position < y.position }
        if (!inStep) {
            val renumbered = (above + placed + below).mapIndexed { i, it -> it.copy(position = (i + 1).toDouble()) }
            val was = (above + below).associateBy { it.id } + (item.id to item)
            return renumbered.filter { it != was[it.id] }.sortedByDescending { it.id == item.id }
        }
        // Dropped back between the same neighbours: the position it has is already right.
        val keep = item.day == day && (a == null || a.position < item.position) && (b == null || item.position < b.position)
        val position = when {
            keep -> item.position
            a == null && b == null -> 1.0
            a == null -> b!!.position - 1.0
            b == null -> a.position + 1.0
            else -> (a.position + b.position) / 2
        }
        val moved = placed.copy(position = position)
        val line = above + moved + below
        if (PlanOrder.crowded(line)) {
            val was = (above + below).associateBy { it.id } + (item.id to item)
            return PlanOrder.renumber(line).filter { it != was[it.id] }.sortedByDescending { it.id == item.id }
        }
        return if (moved == item) emptyList() else listOf(moved)
    }

    /**
     * [time] if it fits between the times before and after the drop ([prev], [next]; null: none on that side), else one
     * that does: the middle, to 5 minutes; an hour after [prev] or an hour before [next] when only one side has a time;
     * kept within the day.
     */
    fun fit(time: Int, prev: Int?, next: Int?): Int = when {
        prev != null && next != null -> {
            val low = minOf(prev, next)
            val high = maxOf(prev, next)
            if (time in low..high) time else (((low + high) / 2 + 2) / 5 * 5).coerceIn(low, high)
        }
        prev != null -> if (time >= prev) time else (prev + HOUR).coerceAtMost(DAY - 1)
        next != null -> if (time <= next) time else (next - HOUR).coerceAtLeast(0)
        else -> time
    }

    /** [item] starting at [time] instead, its end moved by as much (the same length; it may cross midnight). */
    fun withTime(item: PlanItem, time: Int?): PlanItem {
        val old = item.timeMinutes
        if (time == null || old == null || time == old) return item
        val end = item.endMinutes ?: return item.copy(timeMinutes = time)
        val total = (item.endDays ?: 0) * DAY + end + (time - old)
        return item.copy(timeMinutes = time, endDays = total.floorDiv(DAY).takeIf { it > 0 }, endMinutes = total.mod(DAY))
    }

    /**
     * The short notice after a drop that moved [was] to another day or time: "Moved to Tue 14 · 11:00", "Moved to
     * Ideas"; null when only its place in the day changed (the List shows that).
     */
    fun notice(was: PlanItem, now: PlanItem): String? {
        if (was.day == now.day && was.timeMinutes == now.timeMinutes) return null
        val where = now.day?.let(Dates::dayShort) ?: "Ideas"
        return "Moved to " + listOfNotNull(where, now.timeMinutes?.let(Dates::time)).joinToString(" · ")
    }
}

/** "12 days · 34 items", on the Plan tab's top row. */
fun planSummary(days: Int, items: Int): String = listOfNotNull(
    days.takeIf { it > 0 }?.let(Dates::days),
    if (items == 1) "1 item" else "$items items",
).joinToString(" · ")

/** Material's date pickers count days as UTC midnights in milliseconds. */
private const val DAY_MS = 86_400_000L

fun LocalDate.utcMillis(): Long = toEpochDay() * DAY_MS

fun utcDay(millis: Long): LocalDate = localDateOfEpochDay(millis.floorDiv(DAY_MS))

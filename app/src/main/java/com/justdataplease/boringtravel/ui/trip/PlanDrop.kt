package com.justdataplease.boringtravel.ui.trip

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.util.Dates
import java.time.LocalDate

/**
 * What dropping a dragged plan item means (owner, 2026-11-12: "give the possibility to drag to change position. If
 * change position to another day automatically update the time"). Pure, unit tested; the List drags, this decides.
 */
internal object PlanDrop {
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
     * (a folded run of empty days counts as its first day, the Ideas header as the Ideas), between the items of that
     * day above and below it. Above every header it joins the first day; with no headers at all (a trip without dates)
     * it is an idea. An item's end row ([PlanLine.end]) is never a neighbour: it follows its own item. Null when the
     * row isn't there.
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
     * and, when its time no longer fits between theirs, a [fit] time with its length kept. Neighbours whose positions
     * are out of step with the plan order (an older plan, or one the AI wrote) are renumbered in the new order. Returns
     * the items to save, the moved one first; empty when the drop changes nothing.
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
     * [time] if it fits between the times before and after the drop ([prev], [next]; null: none on that side), else
     * one that does: the middle, to 5 minutes; an hour after [prev] or an hour before [next] when only one side has a
     * time; kept within the day.
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
        return item.copy(timeMinutes = time, endDays = Math.floorDiv(total, DAY).takeIf { it > 0 }, endMinutes = Math.floorMod(total, DAY))
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

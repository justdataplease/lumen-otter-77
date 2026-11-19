package com.justdataplease.boringtravel.tripcal

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Calendar tab's month grid: whole weeks from the locale's first weekday, and each trip as bars over its days in
 * the month, one bar per week row. Overlapping trips get their own lane, the same lane in every row of the month.
 * Pure; kept apart from the plan's calendar on purpose.
 */
object TripGrid {
    /** One trip's bar in one week row: columns [from]..[to] (0–6), its [lane] (0 = top). */
    data class Bar(
        val tripId: String,
        val week: Int,
        val from: Int,
        val to: Int,
        val lane: Int,
        /** The trip started before this bar's first day (another month or week): no rounded start. */
        val opensBefore: Boolean,
        /** The trip goes on after this bar's last day. */
        val runsOn: Boolean,
    ) {
        val length: Int get() = to - from + 1
    }

    /** A month as drawn: its [weeks] (days of the next and last months fill the ends), bars and the lanes they use. */
    data class MonthLayout(val month: YearMonth, val weeks: List<List<LocalDate>>, val bars: List<Bar>, val lanes: Map<String, Int>) {
        val laneCount: Int get() = (lanes.values.maxOrNull() ?: -1) + 1

        /** The lanes used in week row [week] (0 when it has no trip). */
        fun lanesIn(week: Int): Int = (bars.filter { it.week == week }.maxOfOrNull { it.lane } ?: -1) + 1
    }

    /**
     * The weeks that show [month]: from the [firstDay] on or before the 1st to the day before the next [firstDay]
     * after the last; 4 to 6 rows of 7.
     */
    fun weeks(month: YearMonth, firstDay: DayOfWeek): List<List<LocalDate>> {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        val from = first.minusDays(Math.floorMod(first.dayOfWeek.value - firstDay.value, 7).toLong())
        val to = last.plusDays(Math.floorMod(firstDay.value + 6 - last.dayOfWeek.value, 7).toLong())
        val n = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
        return (0 until n).map { from.plusDays(it.toLong()) }.chunked(7)
    }

    /**
     * Lanes for the trips that touch [from]..[to]: by start (clipped to the range), longer first, then id; each takes
     * the lowest lane free since its first day there.
     */
    fun lanes(trips: List<TripSpan>, from: LocalDate, to: LocalDate): Map<String, Int> {
        val inRange = trips.filter { it.meets(from, to) }
        fun clipStart(t: TripSpan) = maxOf(t.start, from)
        fun clipEnd(t: TripSpan) = minOf(t.end, to)
        val ordered = inRange.sortedWith(
            compareBy<TripSpan> { clipStart(it) }.thenByDescending { clipEnd(it).toEpochDay() - clipStart(it).toEpochDay() }.thenBy { it.id },
        )
        val laneEnds = ArrayList<LocalDate>()
        val out = LinkedHashMap<String, Int>()
        for (t in ordered) {
            val s = clipStart(t)
            var lane = laneEnds.indexOfFirst { it.isBefore(s) }
            if (lane < 0) {
                lane = laneEnds.size
                laneEnds += clipEnd(t)
            } else {
                laneEnds[lane] = clipEnd(t)
            }
            out[t.id] = lane
        }
        return out
    }

    /** [month] laid out from [firstDay], with bars for the days of [trips] inside the month (not the filler days). */
    fun month(month: YearMonth, firstDay: DayOfWeek, trips: List<TripSpan>): MonthLayout {
        val weeks = weeks(month, firstDay)
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        val lanes = lanes(trips, first, last)
        val byId = trips.associateBy { it.id }
        val bars = ArrayList<Bar>()
        weeks.forEachIndexed { w, week ->
            val rowFrom = maxOf(week.first(), first)
            val rowTo = minOf(week.last(), last)
            if (rowFrom.isAfter(rowTo)) return@forEachIndexed
            for ((id, lane) in lanes) {
                val t = byId.getValue(id)
                if (!t.meets(rowFrom, rowTo)) continue
                val s = maxOf(t.start, rowFrom)
                val e = minOf(t.end, rowTo)
                bars += Bar(
                    tripId = id, week = w,
                    from = (s.toEpochDay() - week.first().toEpochDay()).toInt(),
                    to = (e.toEpochDay() - week.first().toEpochDay()).toInt(),
                    lane = lane,
                    opensBefore = t.start.isBefore(s),
                    runsOn = t.end.isAfter(e),
                )
            }
        }
        bars.sortWith(compareBy<Bar>({ it.week }, { it.lane }, { it.from }))
        return MonthLayout(month, weeks, bars, lanes)
    }

    /** The trips on [day], top lane first (earliest start first when lanes aren't known). */
    fun tripsOn(day: LocalDate, trips: List<TripSpan>, lanes: Map<String, Int> = emptyMap()): List<TripSpan> =
        trips.filter { it.covers(day) }.sortedWith(compareBy<TripSpan>({ lanes[it.id] ?: Int.MAX_VALUE }, { it.start }, { it.id }))

    /** A month as one number (year × 12 + month − 1), for paging and saving; [monthOf] turns it back. */
    fun index(month: YearMonth): Int = month.year * 12 + month.monthValue - 1

    fun monthOf(index: Int): YearMonth = YearMonth.of(Math.floorDiv(index, 12), Math.floorMod(index, 12) + 1)
}

package com.justdataplease.boringtravel.ios

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.time.CivilDay
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.civil
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.ui.theme.BtColors
import com.justdataplease.boringtravel.ui.theme.BtTokens
import com.justdataplease.boringtravel.ui.theme.Look
import kotlin.math.max
import kotlin.math.min

/*
 * The Calendar tab's rules as the Android app works them out (tripcal/TripSpan.kt, TripGrid.kt, TripColors.kt,
 * TripAnalytics.kt and ui/tripcal/TripPalette.kt there), written again here in plain Kotlin because those files take
 * java.time's YearMonth and DayOfWeek and compare days with it. Same rules, same results; they go once the Android files
 * move into the engine. Days are compared by their epoch day.
 */

/** A month of the calendar (the Android app's YearMonth). */
data class CalMonth(val year: Int, val month: Int) {
    val first: LocalDate get() = localDate(year, month, 1)
    val last: LocalDate get() = localDate(year, month, CivilDay.lengthOfMonth(year, month))

    /** The month as one number (year × 12 + month − 1), for paging and saving ([ofIndex] turns it back). */
    val index: Int get() = year * 12 + month - 1

    operator fun contains(d: LocalDate): Boolean = d.civil.let { it.year == year && it.month == month }

    companion object {
        fun of(d: LocalDate): CalMonth = d.civil.let { CalMonth(it.year, it.month) }
        fun ofIndex(index: Int): CalMonth = CalMonth(index.floorDiv(12), index.mod(12) + 1)

        /** The months' names as the calendar writes them: "November 2026", and "NOV" over a small month. */
        val NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    }
}

private fun LocalDate.ep(): Long = toEpochDay()
private fun earlier(a: LocalDate, b: LocalDate): LocalDate = if (a.ep() <= b.ep()) a else b
private fun later(a: LocalDate, b: LocalDate): LocalDate = if (a.ep() >= b.ep()) a else b

/**
 * A trip with dates as the Calendar tab sees it: [start] to [end], both days included; its countries in travel order
 * ([countries], the flags on its cards) and, where something places it, each date's countries in the order they came
 * ([dayCountries], from world/WorldModel).
 */
data class TripSpan(
    val id: String,
    val title: String,
    val start: LocalDate,
    val end: LocalDate,
    val countries: List<String> = emptyList(),
    val dayCountries: Map<LocalDate, List<String>> = emptyMap(),
) {
    /** Days away, both ends included: 1–3 Mar is 3 days (and 2 nights). */
    val days: Int get() = (end.ep() - start.ep() + 1).toInt()

    fun covers(d: LocalDate): Boolean = d.ep() in start.ep()..end.ep()

    /** Whether any of its days falls in [from]..[to]. */
    fun meets(from: LocalDate, to: LocalDate): Boolean = end.ep() >= from.ep() && start.ep() <= to.ep()

    companion object {
        /** No trip is drawn longer than this (ten years), so a mistyped year can't run the screen out of memory. */
        const val MAX_DAYS = 3_660

        /** The trips with dates (earliest first) and the ones without (in the given order). */
        data class Split(val spans: List<TripSpan>, val undated: List<Trip>)

        /**
         * The live trips as spans: a trip without a start is "no dates yet" (as on Trips); one with a start and no end
         * (or an end before its start) is one day.
         */
        fun split(
            trips: List<Trip>,
            countries: Map<String, List<String>> = emptyMap(),
            dayCountries: Map<String, Map<LocalDate, List<String>>> = emptyMap(),
        ): Split {
            val live = trips.filter { it.deletedAt == null }
            val spans = live.mapNotNull { t ->
                val s = t.startDate ?: return@mapNotNull null
                val e = t.endDate?.takeIf { it.ep() >= s.ep() } ?: s
                val end = if (e.ep() - s.ep() >= MAX_DAYS) s.plusDays(MAX_DAYS - 1L) else e
                TripSpan(t.id, t.title, s, end, countries[t.id].orEmpty(), dayCountries[t.id].orEmpty())
            }.sortedWith(compareBy<TripSpan>({ it.start.ep() }, { it.end.ep() }, { it.id }))
            return Split(spans, live.filter { it.startDate == null })
        }
    }
}

/** Each trip day's countries, filled in where nothing says (the Android app's tripcal/TripSpan.kt TripDays). */
object TripDays {
    /**
     * The countries of each day of [trip] (index 0 = its start): the day's own where known; else the last country of
     * the latest known day before it (the traveller is still there), else the first country of the next known day. A
     * trip with no day known spreads its [TripSpan.countries] over its days in travel order (2 countries over 4 days:
     * A A B B); a trip without countries has empty days.
     */
    fun countries(trip: TripSpan): List<List<String>> {
        val n = trip.days
        val known = trip.dayCountries.filter { (d, codes) -> codes.isNotEmpty() && trip.covers(d) }
        if (known.isEmpty()) return spread(trip.countries, n)
        val out = ArrayList<List<String>>(n)
        var last: List<String>? = null
        val firstKnown = known.keys.minBy { it.ep() }
        for (i in 0 until n) {
            val d = trip.start.plusDays(i.toLong())
            val own = known[d]
            when {
                own != null -> { out += own; last = own }
                last != null -> out += listOf(last.last())
                else -> out += listOf(known.getValue(firstKnown).first())
            }
        }
        return out
    }

    /** [countries] spread over [n] days in order; each day gets at least one when there are any. */
    internal fun spread(countries: List<String>, n: Int): List<List<String>> {
        val k = countries.size
        if (k == 0) return List(n) { emptyList() }
        return List(n) { i ->
            val from = i * k / n
            val to = (i + 1) * k / n
            if (to > from) countries.subList(from, to) else listOf(countries[from.coerceAtMost(k - 1)])
        }
    }
}

/**
 * The month grid: whole weeks from the first weekday, and each trip as bars over its days in the month, one bar per
 * week row. Overlapping trips get their own lane, the same lane in every row of the month.
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
    data class MonthLayout(val month: CalMonth, val weeks: List<List<LocalDate>>, val bars: List<Bar>, val lanes: Map<String, Int>) {
        val laneCount: Int get() = (lanes.values.maxOrNull() ?: -1) + 1

        /** The lanes used in week row [week] (0 when it has no trip). */
        fun lanesIn(week: Int): Int = (bars.filter { it.week == week }.maxOfOrNull { it.lane } ?: -1) + 1
    }

    /**
     * The weeks that show [month]: from the [firstDay] (1 = Monday … 7 = Sunday) on or before the 1st to the day before
     * the next [firstDay] after the last; 4 to 6 rows of 7.
     */
    fun weeks(month: CalMonth, firstDay: Int): List<List<LocalDate>> {
        val first = month.first
        val last = month.last
        val from = first.minusDays((first.civil.dayOfWeek - firstDay).mod(7).toLong())
        val to = last.plusDays((firstDay + 6 - last.civil.dayOfWeek).mod(7).toLong())
        val n = (to.ep() - from.ep() + 1).toInt()
        return (0 until n).map { from.plusDays(it.toLong()) }.chunked(7)
    }

    /**
     * Lanes for the trips that touch [from]..[to]: by start (clipped to the range), longer first, then id; each takes
     * the lowest lane free since its first day there.
     */
    fun lanes(trips: List<TripSpan>, from: LocalDate, to: LocalDate): Map<String, Int> {
        val inRange = trips.filter { it.meets(from, to) }
        fun clipStart(t: TripSpan) = later(t.start, from)
        fun clipEnd(t: TripSpan) = earlier(t.end, to)
        val ordered = inRange.sortedWith(
            compareBy<TripSpan> { clipStart(it).ep() }.thenByDescending { clipEnd(it).ep() - clipStart(it).ep() }.thenBy { it.id },
        )
        val laneEnds = ArrayList<Long>()
        val out = LinkedHashMap<String, Int>()
        for (t in ordered) {
            val s = clipStart(t).ep()
            var lane = laneEnds.indexOfFirst { it < s }
            if (lane < 0) {
                lane = laneEnds.size
                laneEnds += clipEnd(t).ep()
            } else {
                laneEnds[lane] = clipEnd(t).ep()
            }
            out[t.id] = lane
        }
        return out
    }

    /** [month] laid out from [firstDay], with bars for the days of [trips] inside the month (not the filler days). */
    fun month(month: CalMonth, firstDay: Int, trips: List<TripSpan>): MonthLayout {
        val weeks = weeks(month, firstDay)
        val first = month.first
        val last = month.last
        val lanes = lanes(trips, first, last)
        val byId = trips.associateBy { it.id }
        val bars = ArrayList<Bar>()
        weeks.forEachIndexed { w, week ->
            val rowFrom = later(week.first(), first)
            val rowTo = earlier(week.last(), last)
            if (rowFrom.ep() > rowTo.ep()) return@forEachIndexed
            for ((id, lane) in lanes) {
                val t = byId.getValue(id)
                if (!t.meets(rowFrom, rowTo)) continue
                val s = later(t.start, rowFrom)
                val e = earlier(t.end, rowTo)
                bars += Bar(
                    tripId = id, week = w,
                    from = (s.ep() - week.first().ep()).toInt(),
                    to = (e.ep() - week.first().ep()).toInt(),
                    lane = lane,
                    opensBefore = t.start.ep() < s.ep(),
                    runsOn = t.end.ep() > e.ep(),
                )
            }
        }
        bars.sortWith(compareBy<Bar>({ it.week }, { it.lane }, { it.from }))
        return MonthLayout(month, weeks, bars, lanes)
    }

    /** The trips on [day], top lane first (earliest start first when lanes aren't known). */
    fun tripsOn(day: LocalDate, trips: List<TripSpan>, lanes: Map<String, Int> = emptyMap()): List<TripSpan> =
        trips.filter { it.covers(day) }.sortedWith(compareBy<TripSpan>({ lanes[it.id] ?: Int.MAX_VALUE }, { it.start.ep() }, { it.id }))
}

/**
 * Which of the theme's trip colours each trip gets (an index; [TripPalette] turns it into a colour). A trip keeps its
 * colour: it comes from its id, so adding, moving or deleting another trip never repaints it. Only where two trips
 * overlap or follow each other within a day, and would share a colour, the later one takes the next free colour.
 */
object TripColors {
    /** Colours in every theme's trip palette. */
    const val SIZE = 6

    /** The colour a trip gets on its own: FNV-1a over its id, the same on every phone and every run. */
    fun preferred(id: String, size: Int = SIZE): Int {
        var h = 0x811C9DC5.toInt()
        for (ch in id) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return h.mod(size)
    }

    /**
     * The colour of each trip in [trips]: its own ([preferred]) unless a trip it overlaps or touches (within a day)
     * already has that one; then the next colour none of those has (back to its own when all are taken).
     */
    fun assign(trips: List<TripSpan>, size: Int = SIZE): Map<String, Int> {
        val ordered = trips.sortedWith(compareBy<TripSpan>({ it.start.ep() }, { it.id }))
        val out = LinkedHashMap<String, Int>()
        for ((i, t) in ordered.withIndex()) {
            val own = preferred(t.id, size)
            val taken = HashSet<Int>()
            for (j in 0 until i) {
                val o = ordered[j]
                if (touches(o, t)) out[o.id]?.let(taken::add)
            }
            var c = own
            if (taken.size < size) while (c in taken) c = (c + 1) % size
            out[t.id] = c
        }
        return out
    }

    /** Overlapping, or one ends the day before (or the day) the other starts. */
    fun touches(a: TripSpan, b: TripSpan): Boolean = a.end.ep() + 1 >= b.start.ep() && b.end.ep() + 1 >= a.start.ep()
}

/** What the Calendar tab works out from the trips' dates. */
object TripAnalytics {
    /** Two trips on the same dates, [from]..[to]. */
    data class Overlap(val first: TripSpan, val second: TripSpan, val from: LocalDate, val to: LocalDate)

    /**
     * Every pair of trips that share dates, in date order. A hand-over (one trip's last day is the next one's first,
     * "Lisbon 1–5 May, Porto 5–9 May") is not an overlap.
     */
    fun overlaps(trips: List<TripSpan>): List<Overlap> {
        val ordered = trips.sortedWith(compareBy<TripSpan>({ it.start.ep() }, { it.id }))
        val out = ArrayList<Overlap>()
        for (i in ordered.indices) for (j in i + 1 until ordered.size) {
            val a = ordered[i]
            val b = ordered[j]
            if (b.start.ep() > a.end.ep()) continue
            if (a.end.ep() == b.start.ep() && a.start.ep() < b.start.ep()) continue
            out += Overlap(a, b, b.start, earlier(a.end, b.end))
        }
        return out.sortedWith(compareBy<Overlap>({ it.from.ep() }, { it.first.id }, { it.second.id }))
    }
}

/**
 * The trip colours of each theme for the Calendar tab, [TripColors.SIZE] each and in the same order of roles, so a
 * trip keeps its place when the theme changes: the theme's route, plan and highlighter colours first, then three that
 * sit well beside them.
 */
object TripPalette {
    fun of(t: BtTokens): List<Color> {
        val c = t.colors
        return when (t.look) {
            Look.PostcardPop -> listOf(c.route, c.plan, c.accent, c.sea, Color(0xFF7B5EA7), Color(0xFFFF8FB1))
            Look.Trail -> listOf(c.route, c.plan, Color(0xFF7FD1C0), c.muted, Color(0xFF8FB8F2), Color(0xFFFF9FBF))
            Look.StickerBook -> listOf(c.route, c.plan, c.accent, t.vehicle.accent, Color(0xFF6B8FD6), Color(0xFFB08AD9))
            Look.Sketchbook -> listOf(c.route, c.plan, c.accent, t.vehicle.glass, Color(0xFF5BB08A), Color(0xFFF29BB5))
            Look.Market -> listOf(c.route, c.plan, c.accent, Color(0xFF8C6BB1), Color(0xFF7FA65A), Color(0xFFD98BA0))
        }
    }

    /** The colour of trip colour [index] in this theme. */
    fun color(t: BtTokens, index: Int): Color = of(t).let { it[index.mod(it.size)] }

    /** Text on a trip colour: the theme's ink or its page colour, whichever reads better (WCAG contrast). */
    fun onColor(fill: Color, c: BtColors): Color = if (contrast(fill, c.ink) >= contrast(fill, c.bg)) c.ink else c.bg

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }
}

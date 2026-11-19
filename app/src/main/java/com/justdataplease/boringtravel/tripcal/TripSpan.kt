package com.justdataplease.boringtravel.tripcal

import com.justdataplease.boringtravel.data.db.Trip
import java.time.LocalDate

/**
 * A trip with dates as the Calendar tab sees it: [start] to [end], both days included; its countries in travel order
 * ([countries], the flags on its cards) and, where something places it, each date's countries in the order they came
 * ([dayCountries], from world/WorldModel). Pure.
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
    val days: Int get() = (end.toEpochDay() - start.toEpochDay() + 1).toInt()

    fun covers(d: LocalDate): Boolean = !d.isBefore(start) && !d.isAfter(end)

    /** Whether any of its days falls in [from]..[to]. */
    fun meets(from: LocalDate, to: LocalDate): Boolean = !end.isBefore(from) && !start.isAfter(to)

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
                val e = t.endDate?.takeIf { !it.isBefore(s) } ?: s
                val end = if (e.toEpochDay() - s.toEpochDay() >= MAX_DAYS) s.plusDays(MAX_DAYS - 1L) else e
                TripSpan(t.id, t.title, s, end, countries[t.id].orEmpty(), dayCountries[t.id].orEmpty())
            }.sortedWith(compareBy<TripSpan>({ it.start }, { it.end }, { it.id }))
            return Split(spans, live.filter { it.startDate == null })
        }
    }
}

/** Each trip day's countries, filled in where nothing says. Pure. */
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
        val firstKnown = known.keys.min()
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

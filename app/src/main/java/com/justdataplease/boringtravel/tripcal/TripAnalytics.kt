package com.justdataplease.boringtravel.tripcal

import java.time.LocalDate

/**
 * What the Calendar tab works out from the trips' dates. Pure. (The year numbers, free stretches and countdown it
 * once showed are gone: the countdown lives on the Trips tab's tickets, ui/trips/TripCountdown.)
 */
object TripAnalytics {
    /** Two trips on the same dates, [from]..[to]. */
    data class Overlap(val first: TripSpan, val second: TripSpan, val from: LocalDate, val to: LocalDate)

    /**
     * Every pair of trips that share dates, in date order. A hand-over (one trip's last day is the next one's first,
     * "Lisbon 1–5 May, Porto 5–9 May") is not an overlap.
     */
    fun overlaps(trips: List<TripSpan>): List<Overlap> {
        val ordered = trips.sortedWith(compareBy<TripSpan>({ it.start }, { it.id }))
        val out = ArrayList<Overlap>()
        for (i in ordered.indices) for (j in i + 1 until ordered.size) {
            val a = ordered[i]
            val b = ordered[j]
            if (b.start.isAfter(a.end)) continue
            if (a.end == b.start && a.start.isBefore(b.start)) continue
            out += Overlap(a, b, b.start, minOf(a.end, b.end))
        }
        return out.sortedWith(compareBy<Overlap>({ it.from }, { it.first.id }, { it.second.id }))
    }
}

package com.justdataplease.boringtravel.ui.trips

import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.time.LocalDate

/**
 * How Trips sorts its list: the trip happening now, next, someday and past. (The lifetime numbers row that used to sit
 * under the trip on now is gone, owner 2026-11-08; the Map tab's Your world keeps countries and days away.)
 */
object HomeStats {
    /** The home lists: the trip happening now, then next (soonest first), someday, and past (latest first). */
    data class Sections(val now: Trip?, val next: List<Trip>, val someday: List<Trip>, val past: List<Trip>)

    fun sections(trips: List<Trip>, today: LocalDate): Sections {
        val live = trips.filter { it.deletedAt == null }
        val now = live.filter { it.isCurrent(today) }.minByOrNull { it.startDate!!.toEpochDay() }
        return Sections(
            now = now,
            next = live.filter { it != now && it.startDate != null && !it.isPast(today) }.sortedBy { it.startDate?.toEpochDay() },
            someday = live.filter { it.startDate == null },
            past = live.filter { it != now && it.isPast(today) }.sortedByDescending { it.startDate?.toEpochDay() },
        )
    }
}

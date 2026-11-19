package com.justdataplease.boringtravel.places

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Stop
import java.time.LocalDate
import java.time.ZoneId

/** Where a trip day was spent, for headers like "Day 4 · Naxos". */
object DayPlace {
    /**
     * The area named most often among the day's stops (where the traveller really was), or, for days without stops,
     * among the day's planned places. A tie goes to the later one, where the day ended. [now] ends the latest stay:
     * days still to come are named by their plan (the latest stop used to last forever, so every later day of a trip
     * under way took its name: "Day 18 · El Chaltén" for the Perito Moreno glacier).
     */
    fun label(day: LocalDate, stops: List<Stop>, items: List<PlanItem>, zone: ZoneId, now: Long = System.currentTimeMillis()): String? {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // A stay without a departure lasts until the next stop arrives, or until now for the latest one.
        val live = stops.filter { it.deletedAt == null && it.name.isNotBlank() }.sortedBy { it.arrivedAt }
        val fromStops = live.mapIndexedNotNull { i, s ->
            val until = s.leftAt ?: live.getOrNull(i + 1)?.arrivedAt ?: maxOf(now, s.arrivedAt)
            if (s.arrivedAt < end && until >= start) PlaceNames.area(s.name) else null
        }
        val names = fromStops.ifEmpty {
            items.filter { it.day == day && it.deletedAt == null && it.placeName.isNotBlank() }
                .sortedBy { it.position }
                .mapNotNull { PlaceNames.area(it.placeName) }
        }
        if (names.isEmpty()) return null
        val counts = names.groupingBy { it.lowercase() }.eachCount()
        val best = counts.values.max()
        return names.last { counts[it.lowercase()] == best }
    }
}

package com.justdataplease.boringtravel.places

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.TrackPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Plan ↔ route: a planned item counts as visited when the route passed near it on its day. Only ever marks items
 * done; an item the traveller un-ticked stays as they left it, because callers only pass the result to items that
 * are not done yet.
 */
object VisitMatcher {
    const val RADIUS_M = 200.0

    /**
     * Ids of not-done items with a place and a day for which a track point recorded that day (in [zone]) lies within
     * [radiusM] of the place.
     */
    fun visited(items: List<PlanItem>, points: List<TrackPoint>, zone: ZoneId, radiusM: Double = RADIUS_M): Set<String> {
        val candidates = items.filter { !it.done && it.deletedAt == null && it.day != null && it.lat != null && it.lng != null }
        if (candidates.isEmpty() || points.isEmpty()) return emptySet()
        val days = candidates.mapTo(HashSet()) { it.day!! }
        val byDay: Map<LocalDate, List<TrackPoint>> = points
            .groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
            .filterKeys { it in days }
        return candidates.filter { item ->
            byDay[item.day].orEmpty().any { p -> Geo.distanceM(item.lat!!, item.lng!!, p.lat, p.lng) <= radiusM }
        }.mapTo(LinkedHashSet()) { it.id }
    }
}

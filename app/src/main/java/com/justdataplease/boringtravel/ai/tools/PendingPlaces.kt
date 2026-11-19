package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.LooseQueries
import com.justdataplease.boringtravel.places.NearTrip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * After the planner: pins for pending items and changed places that still have a place name but no coordinates, from
 * the app's place search, searched near the item before (so "Hostal Alicante" is looked for in Madrid). Nothing found
 * leaves the item without a pin, as before; the review says pins are suggestions. Within [budgetMs] in all: what was
 * found by then keeps its pin, the rest stays without one (until 17 Nov 2026 a slow map threw every pin away).
 */
object PendingPlaces {
    /** Most look-ups after one answer (a long itinerary the model didn't look up itself). */
    const val MAX_LOOKUPS = 40

    private const val NO_LIMIT_MS = 600_000L

    suspend fun locate(pending: PendingPlan, context: PlanContext, lookup: PlaceLookup, budgetMs: Long = NO_LIMIT_MS): PendingPlan {
        val until = System.currentTimeMillis() + budgetMs
        suspend fun <T> timed(block: suspend () -> T): T? {
            val left = until - System.currentTimeMillis()
            return if (left <= 0) null else withTimeoutOrNull(left) { block() }
        }
        val cache = HashMap<String, LatLng?>()
        var lookups = 0
        // Where the trip is: a name found in several towns gets the one nearest the trip (Madrid, not Barcelona).
        val trip = timed { TripAnchors(context, lookup).of(pending) }.orEmpty()
        suspend fun find(query: String, near: LatLng?): LatLng? {
            val key = query.trim().lowercase()
            if (key.length < 3) return null
            cache[key]?.let { return it }
            if (key in cache || lookups >= MAX_LOOKUPS) return null
            lookups++
            val point = try {
                val around = listOfNotNull(near) + trip
                timed {
                    NearTrip.ranked(LooseQueries.search(query, around) { lookup.find(it, near ?: trip.lastOrNull()) }, around)
                        .firstOrNull()?.let { LatLng(it.lat, it.lng) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            cache[key] = point
            return point
        }

        // Saved places as the starting point: the last located item of the plan.
        var last: LatLng? = context.live.filter { it.lat != null && it.lng != null }
            .sortedWith(compareBy({ it.day == null }, { it.day }, { it.position })).lastOrNull()?.let { LatLng(it.lat!!, it.lng!!) }
        val located = HashMap<String, PendingItem>()
        val order = pending.added.withIndex().sortedWith(compareBy({ it.value.date == null }, { it.value.date }, { it.index })).map { it.value }
        for (p in order) {
            val item = p.item
            if (item.lat != null && item.lng != null) { last = LatLng(item.lat, item.lng); continue }
            val place = item.placeName?.takeIf { it.isNotBlank() && item.kind != PlanKind.Note.id } ?: continue
            val point = find(place, last) ?: continue
            located[p.id] = p.copy(item = item.copy(lat = point.lat, lng = point.lng))
            last = point
        }
        val updates = pending.updates.mapValues { (id, u) ->
            if (u.placeName.isNullOrBlank() || u.lat != null) return@mapValues u
            val saved = context.byId[id]
            val near = saved?.let { s -> if (s.lat != null && s.lng != null) LatLng(s.lat!!, s.lng!!) else null } ?: last
            val point = find(u.placeName, near) ?: return@mapValues u
            u.copy(lat = point.lat, lng = point.lng)
        }
        return pending.copy(added = pending.added.map { located[it.id] ?: it }, updates = updates)
    }
}

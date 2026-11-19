package com.justdataplease.boringtravel.ai.tools

import com.justdataplease.boringtravel.places.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the trip is, for ranking map searches nearest it ([com.justdataplease.boringtravel.places.NearTrip]): the pins
 * of its saved and pending items; a trip with none yet, the towns of its route or name looked up once ("Madrid →
 * Granada → Seville", "Madrid, Granada & Seville").
 */
class TripAnchors(private val context: PlanContext, private val lookup: PlaceLookup?) {
    private var townsFor: String? = null
    private var towns: List<LatLng> = emptyList()

    suspend fun of(pending: PendingPlan): List<LatLng> {
        val pins = (context.live.mapNotNull { pin(it.lat, it.lng) } + pending.added.mapNotNull { pin(it.item.lat, it.item.lng) }).distinct()
        if (pins.isNotEmpty()) return pins.takeLast(MAX_PINS)
        val text = listOfNotNull(pending.trip.route, context.trip?.subtitle, pending.trip.title, context.trip?.title)
            .firstOrNull { it.isNotBlank() } ?: return emptyList()
        if (text != townsFor) {
            towns = lookUp(text)
            townsFor = text
        }
        return towns
    }

    private suspend fun lookUp(text: String): List<LatLng> {
        val search = lookup ?: return emptyList()
        val names = text.split(SEPARATORS).map { it.trim() }.filter { it.length >= 3 }.distinctBy { it.lowercase() }.take(MAX_TOWNS)
        return coroutineScope {
            names.map { name ->
                async {
                    try {
                        withTimeoutOrNull(TOWN_TIMEOUT_MS) { search.find(name, null).firstOrNull() }?.let { LatLng(it.lat, it.lng) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    companion object {
        const val MAX_PINS = 80
        const val MAX_TOWNS = 5
        const val TOWN_TIMEOUT_MS = 8_000L
        private val SEPARATORS = Regex("→|->|,|&|/|·|\\+|–|—|\\band\\b|\\bto\\b")

        private fun pin(lat: Double?, lng: Double?): LatLng? = if (lat != null && lng != null) LatLng(lat, lng) else null
    }
}

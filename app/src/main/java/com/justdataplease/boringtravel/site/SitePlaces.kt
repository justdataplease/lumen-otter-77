package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.NearTrip
import com.justdataplease.boringtravel.places.PlaceResult
import kotlinx.coroutines.delay

/**
 * A copied note's place on the map (owner, 15 Nov 2026: copying a site "should try to find the place"). The organiser
 * names the place as the post writes it ("Fushimi Inari") and, when it knows, where that is ("Kyoto, Japan"; "Japan"
 * for a town).
 *
 * - A sight or venue with its town: the town is found first and the place is the answer nearest it, among the answers
 *   for the place with its town and for its name around the town. Words alone don't hold the map to a town (checked
 *   live on 15 Nov 2026: "Shibuya, Tokyo, Japan" answered with a shop in Osaka first), and the answers can't be told
 *   by their names (the map names places in their own language and script). An answer inside the town is taken as
 *   it is (no second question). Nothing within a day trip of the town: the town's own point, the right town at least.
 * - A town with its country: the map's first answer for both together.
 * - No area, or one the map doesn't know: the name, nearest the trip's last place.
 *
 * Each town is looked up once ([MAX_TOWNS] kept). The map is a free service: its questions go one after the other,
 * [gapMs] apart (a post about thirty places asks it sixty times). Plain Kotlin over [ask] (the app's place search),
 * unit tested.
 */
class SitePlaces(private val gapMs: Long = 0, private val ask: suspend (query: String, near: LatLng?) -> List<PlaceResult>) {
    private val towns = HashMap<String, PlaceResult?>()
    private var asked = 0

    private suspend fun search(query: String, near: LatLng?): List<PlaceResult> {
        if (asked++ > 0 && gapMs > 0) delay(gapMs)
        return ask(query, near)
    }

    suspend fun find(name: String, area: String?, near: LatLng?): SiteNotes.Place? {
        val where = area?.trim()?.takeIf { it.isNotEmpty() && !it.equals(name.trim(), ignoreCase = true) } ?: return byName(name, near)
        val asWritten = search("$name, $where", null)
        val town = if (',' in where) town(where) else null
        if (town == null) return asWritten.firstOrNull()?.let { place(name, it) } ?: byName(name, near)
        val at = listOf(LatLng(town.lat, town.lng))
        fun nearest(found: List<PlaceResult>, within: Double) =
            NearTrip.ranked(found, at).firstOrNull { (NearTrip.distanceM(it, at) ?: Double.MAX_VALUE) <= within }
        nearest(asWritten, NearTrip.TOWN_M)?.let { return place(name, it) }
        return place(name, nearest(asWritten + search(name, at[0]), NearTrip.DAY_TRIP_M) ?: town)
    }

    private suspend fun byName(name: String, near: LatLng?): SiteNotes.Place? =
        NearTrip.ranked(search(name, near), listOfNotNull(near)).firstOrNull()?.let { place(name, it) }

    private suspend fun town(where: String): PlaceResult? {
        val key = where.lowercase()
        if (key in towns) return towns[key]
        if (towns.size >= MAX_TOWNS) towns.clear()
        return search(where, null).firstOrNull().also { towns[key] = it }
    }

    private fun place(name: String, found: PlaceResult) =
        SiteNotes.Place(name, found.lat, found.lng, found.displayName, found.kind?.id, found.externalId)

    private companion object {
        const val MAX_TOWNS = 200
    }
}

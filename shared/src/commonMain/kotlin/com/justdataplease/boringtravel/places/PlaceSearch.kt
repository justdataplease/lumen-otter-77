package com.justdataplease.boringtravel.places

import com.justdataplease.boringtravel.model.PlanKind

/** A point on the map (WGS84 degrees). Our own small type, so place logic stays free of map SDKs. */
data class LatLng(val lat: Double, val lng: Double)

/**
 * One place found by a [PlaceSearch].
 *
 * @property name the place itself: "Halki", "Portara Coffee & Bar".
 * @property displayName compact line for lists and plan items: "Halki, Naxos, Greece".
 * @property countryCode ISO 3166-1 alpha-2, upper case, when known.
 * @property kind a guess at the plan kind (a café is Food, a hotel is Stay), when the source says what it is.
 * @property externalId the id at the source ("osm:N806501481"), kept on the plan item for later look-ups.
 */
data class PlaceResult(
    val name: String,
    val displayName: String,
    val lat: Double,
    val lng: Double,
    val countryCode: String? = null,
    val kind: PlanKind? = null,
    val externalId: String? = null,
    /** A town, region, street or land, not a venue (a website's place is never one). */
    val area: Boolean = false,
)

/**
 * Finds places by name and names a point. The app's own search first (Photon + the system geocoder); Tripadvisor
 * can come later as another implementation, so nothing here is tied to one source.
 */
interface PlaceSearch {
    /** Places matching [query], nearest first when [near] is given. Throws on network errors. */
    suspend fun search(query: String, near: LatLng?): List<PlaceResult>

    /** The place at a point, or null when nothing is known there. */
    suspend fun reverse(lat: Double, lng: Double): PlaceResult?
}

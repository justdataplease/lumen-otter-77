package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.places.Geo
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.NearTrip
import com.justdataplease.boringtravel.places.PlaceResult
import kotlin.math.abs

/** Where a Google Maps link puts a plan item. Pure and unit tested. */
object LinkPlaces {
    /** A place found by name farther than this from the trip is someone else's (another branch of a chain). */
    const val MAX_FROM_TRIP_M = 1_000_000.0

    /**
     * The place search's answer for a Maps link that names a place without its pin: the first result, or with the
     * trip's whereabouts [near], the nearest within [MAX_FROM_TRIP_M] of them ([NearTrip]; none: no guess).
     */
    fun choose(results: List<PlaceResult>, near: LatLng?): PlaceResult? =
        if (near == null) results.firstOrNull()
        else NearTrip.ranked(results, listOf(near)).firstOrNull { Geo.distanceM(near.lat, near.lng, it.lat, it.lng) <= MAX_FROM_TRIP_M }

    /**
     * [item] with the place from its Maps link, when it has no place yet: the pin, and the link's name unless the
     * item has one. Anything else stays as it is.
     */
    fun fill(item: PlanItem, place: MapsPlace?): PlanItem {
        if (place == null || !place.hasPoint || item.lat != null || item.lng != null) return item
        return item.copy(placeName = item.placeName.ifBlank { place.name.orEmpty() }, lat = place.lat, lng = place.lng)
    }

    /**
     * The title of an item known only by its link, until the link says more: "Google Maps place" for a Maps link,
     * else the site's domain ("example.com").
     */
    fun placeholderTitle(url: String): String = if (MapsLinks.isMaps(url)) PLACEHOLDER_MAPS else LinkUrls.domain(url)

    /** [placeholderTitle] for an item with a website [url] and a Maps link [mapsUrl]: the Maps one first (it gets the place's name later). */
    fun placeholderTitle(url: String?, mapsUrl: String?): String? = (mapsUrl ?: url)?.let(::placeholderTitle)

    const val PLACEHOLDER_MAPS = "Google Maps place"

    /**
     * A plan item's two links as kept (owner, 2026-11-13: "add to a place a website and a google url"): the website
     * [url] holds only a site, so a Google Maps link there (kept as the website before schema 9, or put there by hand)
     * becomes the Maps link [mapsUrl] unless there is one. [MapsLinks.isMaps] decides what a Maps link is. The schema
     * 9 migration, restores, shared trips and every item made from a link go through this.
     */
    fun sortLinks(url: String?, mapsUrl: String?): Pair<String?, String?> =
        if (url != null && MapsLinks.isMaps(url)) null to (mapsUrl ?: url) else url to mapsUrl

    /** [item] with its links sorted ([sortLinks]); the same item when they already are. */
    fun sortLinks(item: PlanItem): PlanItem {
        val (url, maps) = sortLinks(item.url, item.mapsUrl)
        return if (url == item.url && maps == item.mapsUrl) item else item.copy(url = url, mapsUrl = maps)
    }

    /** [url] when it is an http(s) link, else null: what a backup or a shared trip may carry as an item's link. */
    fun web(url: String?): String? = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /**
     * The links to keep from the item sheet's Website ([siteText]) and Google Maps link ([mapsText]) fields as typed:
     * an empty field removes its link; one that can't be kept (not a web address, or not a Maps link in the Maps
     * field) keeps what [before] had; a Maps link typed as the website is the Maps link.
     */
    fun fromFields(siteText: String, mapsText: String, before: PlanItem?): Pair<String?, String?> {
        val siteTyped = siteText.trim()
        val mapsTyped = mapsText.trim()
        val site = if (siteTyped.isEmpty()) null else LinkUrls.normalize(siteTyped) ?: before?.url
        val maps = if (mapsTyped.isEmpty()) null else LinkUrls.normalize(mapsTyped)?.takeIf(MapsLinks::isMaps) ?: before?.mapsUrl
        return if (site != null && MapsLinks.isMaps(site)) null to site else site to maps
    }

    /** Pins closer than this (about 10 cm) are the same pin. */
    private const val SAME_PIN = 1e-6

    /**
     * Whether [item]'s place is the one a Maps link gave it ([place], that link's): the same pin. A place picked from
     * the search, typed, or found with Use my location isn't.
     */
    fun placeFrom(item: PlanItem, place: MapsPlace?): Boolean {
        val lat = item.lat
        val lng = item.lng
        if (place == null || !place.hasPoint || lat == null || lng == null) return false
        return abs(lat - place.lat!!) < SAME_PIN && abs(lng - place.lng!!) < SAME_PIN
    }

    /**
     * [item] after its Maps link changed (owner, 2026-11-13): a place the old link gave ([old], [placeFrom]) becomes
     * the new link's ([place]: its name and pin); any other place, or a new link without a pin, leaves it as it is.
     */
    fun follow(item: PlanItem, old: MapsPlace?, place: MapsPlace?): PlanItem {
        if (place == null || !place.hasPoint || !placeFrom(item, old)) return item
        return item.copy(placeName = place.name.orEmpty(), lat = place.lat, lng = place.lng)
    }

    /**
     * [item] once its Maps link [url] answered after saving: [fill], and the place's name instead of a
     * [placeholderTitle].
     */
    fun fillLater(item: PlanItem, url: String, place: MapsPlace): PlanItem {
        val filled = fill(item, place)
        val name = place.name?.substringBefore(',')?.trim()
        return if (!name.isNullOrEmpty() && filled.title == placeholderTitle(url)) filled.copy(title = name) else filled
    }

    /** Where the trip is, to lean a place search: the day's first located item, else the trip's. */
    fun near(items: List<PlanItem>, day: java.time.LocalDate?): LatLng? =
        (items.firstOrNull { it.day == day && it.lat != null && it.lng != null } ?: items.firstOrNull { it.lat != null && it.lng != null })
            ?.let { LatLng(it.lat!!, it.lng!!) }
}

package com.justdataplease.boringtravel.places

import com.justdataplease.boringtravel.model.PlanKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Reads a Photon (OpenStreetMap) GeoJSON answer into [PlaceResult]s. Pure, so it is tested on canned answers.
 * Photon gives coordinates as [lng, lat]; features without a point or a name are skipped, near-duplicates
 * (the same name within a kilometre, as OSM often has a node and an area for one place) are dropped.
 */
object PhotonParser {
    fun parse(json: String): List<PlaceResult> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyList()
        val features = root["features"] as? JsonArray ?: return emptyList()
        val out = ArrayList<PlaceResult>()
        for (f in features) {
            val place = feature(f as? JsonObject ?: continue) ?: continue
            val dup = out.any {
                it.displayName.equals(place.displayName, ignoreCase = true) &&
                    Geo.distanceM(it.lat, it.lng, place.lat, place.lng) < 1_000
            }
            if (!dup) out += place
        }
        return out
    }

    private fun feature(obj: JsonObject): PlaceResult? {
        val coords = (obj["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: return null
        val lng = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        val p = obj["properties"] as? JsonObject ?: JsonObject(emptyMap())
        fun s(key: String): String? = (p[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        val street = listOfNotNull(s("street"), s("housenumber")).joinToString(" ").takeIf { it.isNotBlank() }
        val name = s("name") ?: street ?: return null
        val country = s("country")
        val display = PlaceNames.compact(name, listOf(s("city"), s("locality"), s("county"), s("state")), country)
        val osmType = s("osm_type")
        val osmId = s("osm_id")
        return PlaceResult(
            name = name,
            displayName = display,
            lat = lat,
            lng = lng,
            countryCode = s("countrycode")?.uppercase(),
            kind = kindOf(s("osm_key"), s("osm_value")),
            externalId = if (osmType != null && osmId != null) "osm:$osmType$osmId" else null,
            area = s("osm_key") in AREA_KEYS,
        )
    }

    /** Towns, regions, streets and land: never a venue's own place. */
    private val AREA_KEYS = setOf("place", "boundary", "highway", "landuse")

    private val food = setOf("restaurant", "cafe", "bar", "pub", "fast_food", "ice_cream", "biergarten", "food_court", "bakery", "winery", "distillery", "brewery", "wine")
    private val stay = setOf("hotel", "guest_house", "hostel", "motel", "apartment", "chalet", "camp_site", "caravan_site", "alpine_hut", "wilderness_hut")
    private val transportAmenity = setOf("ferry_terminal", "bus_station", "car_rental", "taxi", "bicycle_rental")
    private val activityLeisure = setOf("park", "water_park", "sports_centre", "marina", "beach_resort", "nature_reserve", "golf_course")
    private val activityTourism = setOf("theme_park", "zoo", "aquarium")

    /** A plan kind for an OSM tag, or null when it says nothing useful. */
    fun kindOf(key: String?, value: String?): PlanKind? {
        if (key == null) return null
        return when {
            value in food && key in setOf("amenity", "shop", "craft") -> PlanKind.Food
            key == "tourism" && value in stay -> PlanKind.Stay
            key == "amenity" && value in transportAmenity -> PlanKind.Transport
            key in setOf("railway", "aeroway", "public_transport") -> PlanKind.Transport
            key == "highway" && value == "bus_stop" -> PlanKind.Transport
            key == "leisure" && value in activityLeisure -> PlanKind.Activity
            key == "tourism" && value in activityTourism -> PlanKind.Activity
            key in setOf("tourism", "historic", "place", "natural") -> PlanKind.Place
            else -> null
        }
    }
}

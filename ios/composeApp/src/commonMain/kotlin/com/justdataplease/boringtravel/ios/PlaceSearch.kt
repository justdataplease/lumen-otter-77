package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.art.decomposed
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.Geo
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.NearTrip
import com.justdataplease.boringtravel.places.PlaceResult
import com.justdataplease.boringtravel.places.PlaceSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * Finding places and naming points, as the Android app does it (places/PhotonPlaceSearch.kt, PhotonParser.kt,
 * LooseQueries.kt; geo/ReverseGeocoder.kt; ui/editor/Places.kt), written again here because those files call Android's
 * Geocoder, OkHttp and Java's Normalizer. Same requests, same rules, same results for the same answers.
 */

/**
 * Place search over Photon (komoot's free OpenStreetMap geocoder, no key), with names for points from the phone's
 * geocoder ([PhoneLocation.address]; Photon's reverse endpoint when it has none). Results lean towards [LatLng] when
 * given. [userAgent] names the app; [language]: the phone's ("en").
 */
class PhotonPlaceSearch(
    private val net: Net,
    private val location: PhoneLocation,
    private val userAgent: String,
    private val language: String,
    private val baseUrl: String = "https://photon.komoot.io",
) : PlaceSearch {
    override suspend fun search(query: String, near: LatLng?): List<PlaceResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val params = buildList {
            add("q" to q)
            add("limit" to "8")
            if (near != null) {
                add("lat" to near.lat.toString())
                add("lon" to near.lng.toString())
            }
            lang()?.let { add("lang" to it) }
        }
        return PhotonParser.parse(get(UrlCodec.withQuery("$baseUrl/api/", *params.toTypedArray())))
    }

    override suspend fun reverse(lat: Double, lng: Double): PlaceResult? =
        orNull { geocoderReverse(lat, lng) } ?: orNull { photonReverse(lat, lng) }

    /** Runs [block], turning failures into null but letting cancellation through. */
    private suspend fun <T> orNull(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun photonReverse(lat: Double, lng: Double): PlaceResult? {
        val params = listOfNotNull("lat" to lat.toString(), "lon" to lng.toString(), "limit" to "1", lang()?.let { "lang" to it })
        return PhotonParser.parse(get(UrlCodec.withQuery("$baseUrl/reverse", *params.toTypedArray()))).firstOrNull()
    }

    /** The phone's geocoder, 8 s at most: the town, else the area, else the feature, with its country. */
    private suspend fun geocoderReverse(lat: Double, lng: Double): PlaceResult? {
        val a = withTimeoutOrNull(8_000) { location.address(lat, lng, 8_000) } ?: return null
        val name = a.locality ?: a.subLocality ?: a.subAdminArea ?: a.adminArea ?: a.featureName ?: return null
        return PlaceResult(
            name = name,
            displayName = PlaceNames.compact(name, listOf(a.subAdminArea, a.adminArea), a.countryName),
            lat = lat,
            lng = lng,
            countryCode = a.countryCode?.uppercase(),
        )
    }

    /** Photon has English, German and French names; other languages get the local names, which read fine. */
    private fun lang(): String? = language.takeIf { it in setOf("en", "de", "fr") }

    private suspend fun get(url: String): String {
        val answer = net.ask(NetRequest(url, mapOf("User-Agent" to userAgent), maxBytes = 2L * 1024 * 1024, timeoutMs = 20_000, readTimeoutMs = 15_000))
        if (!answer.ok) throw NetException("Place search answered ${answer.code}")
        return answer.text()
    }
}

/** The place's names the phone's geocoder gives, as the Android app reads them. */
object GeoNames {
    /**
     * A note's place (ui/editor/Places.kt): the town, else the area, else whatever the geocoder named, never a bare
     * number.
     */
    fun noteLabel(a: GeoAddress): String? =
        listOf(a.locality, a.subAdminArea, a.adminArea, a.featureName)
            .firstOrNull { !it.isNullOrBlank() && !it.trim().all { ch -> ch.isDigit() || ch == ' ' } }
            ?.trim()

    /** A place page's name (geo/ReverseGeocoder.kt): the town, else the area, else a feature with letters. */
    fun pageName(a: GeoAddress): String? =
        listOf(a.locality, a.subAdminArea, a.featureName?.takeIf { f -> f.any(Char::isLetter) }, a.adminArea).firstOrNull { !it.isNullOrBlank() }
}

/**
 * Reads a Photon (OpenStreetMap) GeoJSON answer into [PlaceResult]s. Photon gives coordinates as [lng, lat]; features
 * without a point or a name are skipped, near-duplicates (the same name within a kilometre, as OSM often has a node
 * and an area for one place) are dropped.
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

/**
 * Looser forms of an address the map search found nothing for. Google Maps links often name a place by its full
 * address ("Mollafenari, Elizi, Bab-ı Ali Cd. No:27, 34120 Fatih/İstanbul, Turkey"), and Photon finds nothing with the
 * postcode, the "No:" or the "Fatih/İstanbul" in it; "Bab-ı Ali Cd. 27, Fatih, İstanbul, Turkey" it finds. So: the
 * address cleaned, then its leading parts dropped while a numbered street is left. Never down to a bare town.
 */
object LooseQueries {
    /** Most extra searches for one place. */
    const val MAX = 3

    private val NUMBER_MARK = Regex("(?i)\\b(?:no|nr|nº|n°)\\s*[:.]?\\s*(?=\\d)")
    private val POSTCODE = Regex("(?<![\\w-])\\d{4,6}(?![\\w-])")
    private val SPACES = Regex("\\s+")

    fun of(query: String): List<String> {
        val parts = query.replace('/', ',').replace(NUMBER_MARK, "").replace(POSTCODE, " ")
            .split(',').map { it.replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return emptyList()
        val out = LinkedHashSet<String>()
        out += parts.joinToString(", ")
        for (drop in 1 until parts.size - 1) {
            val rest = parts.drop(drop)
            if (rest.size >= 3 && rest.first().any(Char::isDigit)) out += rest.joinToString(", ")
        }
        out.remove(query.trim())
        return out.take(MAX)
    }

    /**
     * [find] for [query]; nothing found, its looser forms in turn until one finds a place within a day trip of
     * [anchors] (anywhere without anchors); then, near a trip only, the name at its front, kept only for a venue of
     * that name.
     */
    suspend fun search(query: String, anchors: List<LatLng>, find: suspend (String) -> List<PlaceResult>): List<PlaceResult> {
        val found = find(query)
        if (found.isNotEmpty()) return found
        fun near(p: PlaceResult) = anchors.isEmpty() || NearTrip.distanceM(p, anchors)!! <= NearTrip.DAY_TRIP_M
        for (q in of(query)) {
            val hits = find(q).filter(::near)
            if (hits.isNotEmpty()) return hits
        }
        if (anchors.isEmpty()) return emptyList()
        val front = query.split(',').first().trim()
        for (n in names(front).filter { normal(it) != normal(query) }) {
            val hits = find(n).filter { near(it) && !it.area && sameName(it.name, front) }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    /** [name], then without the words that only say what it is ("Lhardy Restaurante" → "Lhardy"). */
    fun names(name: String): List<String> =
        listOf(name.trim(), bare(name)).filter { it.length >= 3 }.distinctBy(::normal)

    /** [name] without the words that only say what it is: restaurant, hotel, café, museum … */
    fun bare(name: String): String = name.trim().split(SPACES).filter { normal(it) !in KIND_WORDS }.joinToString(" ")

    /** The same name: equal once accents, case and punctuation go, or the shorter one's words inside the longer one. */
    fun sameName(a: String, b: String): Boolean {
        val x = normal(a).split(' ').filter { it.isNotEmpty() }
        val y = normal(b).split(' ').filter { it.isNotEmpty() }
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val (short, long) = if (x.size <= y.size) x to y else y to x
        if (short.joinToString("").length < 4 || short.size * 2 < long.size) return false
        return long.windowed(short.size).any { it == short }
    }

    /** Lower case, no accents, words only (Java's NFD and \p{Mn}: [decomposed] here). */
    fun normal(s: String): String =
        decomposed(s.lowercase()).replace(MARKS, "").replace(NON_WORD, " ").replace(SPACES, " ").trim()

    private val MARKS = Regex("[\\u0300-\\u036f]+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val KIND_WORDS = setOf(
        "restaurant", "restaurante", "ristorante", "restaurang", "hotel", "hostal", "hostel", "cafe", "bar", "taverna",
        "tavern", "trattoria", "brasserie", "bistro", "bistrot", "pub", "museum", "museo", "musee", "official",
    )
}

/**
 * Plan kinds from obvious words in a name (the Android app's calendar/CalendarImport.guessKind), in English and Greek,
 * accents and case not counting. Transport first, so "check-in for your flight" is a flight.
 */
object KindWords {
    private fun fold(s: String): String = decomposed(s).replace(Regex("[\\u0300-\\u036f]+"), "").lowercase().replace('ς', 'σ')

    private val rules: List<Pair<PlanKind, Regex>> = listOf(
        PlanKind.Transport to listOf(
            "flights?", "airport", "boarding", "layover", "trains?", "rail", "railway", "ferry", "ferries", "bus", "shuttle",
            "transfer", "taxi", "car rental", "rental car", "car hire",
            "πτηση", "πτησεις", "αεροδρομιο", "αεροπλανο", "πλοιο", "φεριμποτ", "τρενο", "λεωφορειο", "ταξι", "μεταφορα",
        ),
        PlanKind.Stay to listOf(
            "hotel", "hostel", "motel", "inn", "airbnb", "apartments?", "guesthouse", "guest house", "b&b", "bed and breakfast",
            "villa", "resort", "lodge", "camping", "campsite", "check[- ]?in", "check[- ]?out", "stay", "accommodation",
            "ξενοδοχειο", "διαμονη", "καταλυμα", "δωματια", "βιλα", "ξενωνας",
        ),
        PlanKind.Food to listOf(
            "restaurant", "dinner", "lunch", "breakfast", "brunch", "cafe", "coffee", "bar", "taverna", "tavern", "bistro",
            "pizzeria", "trattoria", "food", "meal",
            "bodega", "taberna", "tasca", "meson", "casa de comidas", "cerveceria", "marisqueria", "tapas", "churreria",
            "heladeria", "gelateria", "pasteleria", "bakery", "ristorante", "osteria", "brasserie", "cafeteria",
            "εστιατοριο", "ταβερνα", "δειπνο", "γευμα", "μεσημεριανο", "πρωινο", "καφε", "μεζεδοπωλειο", "ουζερι",
        ),
        PlanKind.Activity to listOf(
            "tours?", "museum", "gallery", "excursion", "tickets?", "concert", "show", "hike", "hiking", "trek", "trekking",
            "class", "lesson", "workshop", "tasting", "cruise", "boat trip", "snorkell?ing", "diving", "kayak(ing)?", "surf(ing)?",
            "spa", "massage", "festival", "theatre", "theater", "cinema", "opera", "zoo", "aquarium", "guided", "sightseeing",
            "ξεναγηση", "εκδρομη", "μουσειο", "εισιτηρια", "εισιτηριο", "συναυλια", "παρασταση", "πεζοπορια", "κρουαζιερα",
        ),
    ).map { (kind, words) ->
        kind to Regex("(?<![\\p{L}\\p{N}])(" + words.joinToString("|") { fold(it) } + ")(?![\\p{L}\\p{N}])")
    }

    /** A kind from obvious words in [title], else in [location]; null when nothing is obvious. */
    fun guessKind(title: String, location: String = ""): PlanKind? {
        for (text in listOf(title, location)) {
            if (text.isBlank()) continue
            if (text.contains('✈')) return PlanKind.Transport
            val t = fold(text)
            rules.firstOrNull { it.second.containsMatchIn(t) }?.let { return it.first }
        }
        return null
    }
}

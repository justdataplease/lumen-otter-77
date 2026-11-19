package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

/**
 * What to look for to give a located plan item a photo: its spot and the names it goes by (the title, the title
 * without "Lunch at", the place's first part), best first.
 */
data class PlaceQuery(val lat: Double, val lng: Double, val names: List<String>) {
    /** The spot to about 10 m and the names: a moved or renamed item looks again, the same place shares its photo. */
    val key: String get() = PlacePhotos.key(this)
}

/**
 * Photos for plan items that have a place but no link, from Wikipedia (Me › Place photos). An article whose title is
 * the item's name wins when it is near the item ([radiusM]: wider for towns and areas); else the nearest article
 * with a photo within [WikiPhotos.RADIUS_M] ([WikiPhotos.pick]). Notes have no photo; a Transport item's place is
 * where it arrives. Pure and unit tested; the requests are [WikiLookup]'s.
 */
object PlacePhotos {
    const val SITE_NAME = "Wikipedia"

    /** A named landmark this far from the item is still the same place (a big temple's pin vs its article's). */
    const val NEAR_M = 1_500.0
    const val AIRPORT_M = 5_000.0
    const val TOWN_M = 20_000.0
    const val AREA_M = 50_000.0
    private const val MAX_DIM_M = 100_000.0
    private const val MAX_TITLES = 8

    private val json = Json { ignoreUnknownKeys = true }
    private val spaces = Regex("\\s+")
    /** Characters a Wikipedia title can't have ("|" also separates titles in the query). */
    private val notInTitle = Regex("[#<>\\[\\]{}|_]")
    private val towns = setOf("city", "adm3rd")
    private val areas = setOf("state", "adm1st", "adm2nd", "isle", "waterbody", "forest", "glacier", "mountain", "river", "pass")

    /** What starts a plan title before the place: "Lunch at …", "Fly to …", "Day trip to …", "Back to …". */
    private val lead = Regex(
        "^(?:fly|flight|train|bus|coach|ferry|boat|cruise|drive|walk|cycle|ride|taxi|transfer|shinkansen|day trip|trip|" +
            "head|go|back|return|arrive|arrival|check[- ]?in|stay|night|lunch|dinner|breakfast|brunch|coffee|drinks|" +
            "visit|see|explore|hike|tour|evening|morning|afternoon|sunset|sunrise)" +
            "(?:\\s+(?:to|in|at|on|by|from|around|near|of|up))?[\\s,:]+",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The photo query for [item]: null for a Note, an item without a place, and an item with a link (its link's
     * picture wins).
     */
    fun query(item: PlanItem): PlaceQuery? {
        val lat = item.lat ?: return null
        val lng = item.lng ?: return null
        if (lat == 0.0 && lng == 0.0) return null
        if (PlanKind.of(item.kind) == PlanKind.Note) return null
        if (item.url?.let(LinkUrls::normalize) != null) return null
        return PlaceQuery(lat, lng, names(item.title, item.placeName))
    }

    /**
     * The names to look up, best first: the title, the title without what leads to the place ("Lunch at Kuromon
     * Market" → "Kuromon Market"; "Day trip to Nara: park and forest" → "Nara") and the place's first part
     * ("Kansai International Airport, Osaka, Japan" → "Kansai International Airport").
     */
    fun names(title: String, placeName: String): List<String> {
        val t = title.replace(spaces, " ").trim()
        val bare = t.substringBefore(':').replace(lead, "").trim()
        val place = placeName.substringBefore(',').replace(spaces, " ").trim()
        return listOf(t, bare, place)
            .filter { n -> n.length >= 3 && n.count { it.isLetter() } >= 3 }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    fun key(q: PlaceQuery): String =
        String.format(Locale.ROOT, "geo:%.4f,%.4f", q.lat, q.lng) + q.names.joinToString("") { "|" + WikiPhotos.key(it) }

    /**
     * The titles to ask for: each name as written and with its words capitalised (Wikipedia only capitalises the
     * first letter itself: "kuromon market" → "Kuromon Market").
     */
    fun titles(names: List<String>): List<String> = names
        .map { it.replace(notInTitle, " ").replace(spaces, " ").trim() }
        .flatMap { listOf(it, capitalised(it)) }
        .filter { it.length >= 3 }
        .distinct()
        .take(MAX_TITLES)

    private fun capitalised(s: String): String = s.split(' ').joinToString(" ") { w ->
        if (w.isNotEmpty() && w.all { !it.isLetter() || it.isLowerCase() }) w.replaceFirstChar { it.titlecase(Locale.ROOT) } else w
    }

    /** The articles titled [titles] (redirects followed) with their lead images and coordinates. */
    fun titleQueryUrl(titles: List<String>): String = WikiPhotos.API.toHttpUrl().newBuilder()
        .addQueryParameter("action", "query")
        .addQueryParameter("titles", titles.joinToString("|"))
        .addQueryParameter("redirects", "1")
        .addQueryParameter("prop", "pageimages|coordinates")
        .addQueryParameter("piprop", "thumbnail|name")
        .addQueryParameter("pithumbsize", WikiPhotos.THUMB_PX.toString())
        .addQueryParameter("coprop", "type|dim")
        .addQueryParameter("format", "json")
        .build().toString()

    /**
     * From a [titleQueryUrl] answer, the photo of the first of [titles] that is an article with a photo near
     * [lat],[lng] (within [radiusM] of its kind); null when none is.
     */
    fun pickByTitle(answer: String, titles: List<String>, lat: Double, lng: Double): WikiPhoto? {
        val pages = WikiPhotos.pages(answer).associateBy { it.title }
        if (pages.isEmpty()) return null
        val renamed = renames(answer)
        for (t in titles) {
            var title = t
            repeat(3) { renamed[title]?.let { title = it } }
            val page = pages[title] ?: continue
            val image = page.imageUrl ?: continue
            val radius = radiusM(page.type, page.dimM) ?: continue
            val distance = WikiPhotos.distanceM(page, lat, lng) ?: continue
            if (distance <= radius) return WikiPhoto(image, page.title, page.file)
        }
        return null
    }

    /**
     * How far from the item an article of coordinates [type] may be and still be its place: a landmark [NEAR_M], an
     * airport [AIRPORT_M], a town [TOWN_M], a region, island or mountain [AREA_M], or the article's own size when it
     * says more; null for what is never a place to photograph (a country, an event).
     */
    fun radiusM(type: String?, dimM: Double?): Double? {
        if (!WikiPhotos.placeType(type)) return null
        val base = when (type) {
            "airport" -> AIRPORT_M
            in towns -> TOWN_M
            in areas -> AREA_M
            else -> NEAR_M
        }
        return maxOf(base, minOf(dimM ?: 0.0, MAX_DIM_M))
    }

    /** The article's page, opened from the item sheet's photo. */
    fun articleUrl(title: String): String = "https://en.wikipedia.org".toHttpUrl().newBuilder()
        .addPathSegment("wiki").addPathSegment(title.replace(' ', '_'))
        .build().toString()

    /** "from" → "to" for the titles Wikipedia normalised ("yasaka Shrine") and the redirects it followed. */
    private fun renames(answer: String): Map<String, String> {
        val query = runCatching { (json.parseToJsonElement(answer) as? JsonObject)?.get("query") as? JsonObject }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (list in listOf("normalized", "redirects", "converted")) {
                (query[list] as? JsonArray)?.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val from = (o["from"] as? JsonPrimitive)?.content ?: return@forEach
                    val to = (o["to"] as? JsonPrimitive)?.content ?: return@forEach
                    if (from != to) put(from, to)
                }
            }
        }
    }
}

/**
 * Wikipedia's photo for a spot, asked only while Me › Place photos is on ([enabled]): an article titled like one of
 * the [names] near it, else the nearest article with a photo; then its author and licence ([WikiCredits]): a photo
 * without a reusable licence isn't taken. [ask] makes one request and returns its answer (it may throw when offline).
 * Off, nothing is asked.
 */
class WikiLookup(
    private val ask: suspend (url: String) -> String,
    private val enabled: suspend () -> Boolean,
) {
    suspend fun photo(names: List<String>, lat: Double, lng: Double): WikiPhoto? {
        if (!enabled()) return null
        val titles = PlacePhotos.titles(names)
        var refused: String? = null
        if (titles.isNotEmpty()) {
            PlacePhotos.pickByTitle(ask(PlacePhotos.titleQueryUrl(titles)), titles, lat, lng)?.let { photo ->
                credited(photo)?.let { return it }
                refused = photo.file
            }
        }
        val nearest = WikiPhotos.pick(ask(WikiPhotos.queryUrl(lat, lng)), names, lat, lng) ?: return null
        return if (nearest.file == refused) null else credited(nearest)
    }

    /** [photo] with its author and licence; null when its file has no reusable licence (it is never shown). */
    private suspend fun credited(photo: WikiPhoto): WikiPhoto? {
        val file = photo.file ?: return null
        val credit = WikiCredits.parse(ask(WikiCredits.queryUrl(file)), file) ?: return null
        return photo.copy(credit = credit)
    }
}

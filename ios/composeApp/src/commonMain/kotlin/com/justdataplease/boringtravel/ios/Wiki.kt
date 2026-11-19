package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.art.decomposed
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.Geo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Wikipedia photos for places, with their credits, as the Android app picks them (links/WikiPhotos.kt, PlacePhotos.kt,
 * WikiCredits.kt), written again here because those files build addresses with OkHttp and read names with Java's
 * Normalizer and String.format. Same requests, same picks.
 */

/** A Wikipedia article's lead photo near a place: the thumbnail, the article, the file on Commons and its credit. */
data class WikiPhoto(val imageUrl: String, val title: String, val file: String? = null, val credit: WikiCredit? = null)

/** One article in a Wikipedia answer: its lead photo (null when none, or only a drawing) and where it is. */
data class WikiPage(
    val title: String,
    val imageUrl: String?,
    val file: String? = null,
    val lat: Double?,
    val lng: Double?,
    val type: String?,
    val dimM: Double?,
    val index: Int,
)

/**
 * A photo for a place from Wikipedia: the articles within [RADIUS_M] of the pin (geosearch) with their lead images. The
 * article named like the place wins, else the nearest one with a photo. Only photos count: SVGs, PNGs and GIFs, and files
 * named as maps, flags, logos, coats of arms and the like are left out.
 */
object WikiPhotos {
    const val RADIUS_M = 200
    const val THUMB_PX = 640
    const val API = "https://en.wikipedia.org/w/api.php"
    private const val LIMIT = 5

    private val json = Json { ignoreUnknownKeys = true }
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val marks = Regex("[\\u0300-\\u036f]+")
    private val bracket = Regex("\\s*\\([^)]*\\)\\s*$")
    private val thumbSize = Regex("^\\d+px-")
    private val photoTypes = setOf("jpg", "jpeg", "jpe", "webp", "tif", "tiff")
    private val drawingWords = setOf(
        "map", "maps", "mapa", "karte", "carte", "locator", "location", "plan", "diagram", "chart", "relief",
        "flag", "flags", "bandera", "drapeau", "logo", "logos", "logotype", "wordmark", "emblem", "seal", "crest",
        "coa", "wappen", "blason", "escudo", "stemma", "insignia", "symbol", "icon", "signature",
    )
    private val neverTypes = setOf("country", "event")
    private val areaTypes = setOf("country", "state", "adm1st", "adm2nd", "event")

    /** The geosearch query for the articles near [lat],[lng] with their lead images and coordinates. */
    fun queryUrl(lat: Double, lng: Double): String = UrlCodec.withQuery(
        API,
        "action" to "query", "generator" to "geosearch",
        "ggscoord" to "${JavaText.fixed(lat, 6)}|${JavaText.fixed(lng, 6)}",
        "ggsradius" to RADIUS_M.toString(), "ggslimit" to LIMIT.toString(),
        "prop" to "pageimages|coordinates", "piprop" to "thumbnail|name", "pithumbsize" to THUMB_PX.toString(),
        "coprop" to "type|dim", "format" to "json",
    )

    fun pick(answer: String, name: String?, lat: Double, lng: Double): WikiPhoto? = pick(answer, listOfNotNull(name), lat, lng)

    /** The photo to show for a place that goes by any of [names] at [lat],[lng]; null when none fits. */
    fun pick(answer: String, names: List<String>, lat: Double, lng: Double): WikiPhoto? {
        val candidates = pages(answer).filter { it.imageUrl != null && it.type !in areaTypes }
        if (candidates.isEmpty()) return null
        val wanted = names.map(::key).filter { it.isNotEmpty() }.distinct()
        val byName = wanted.firstNotNullOfOrNull { w -> candidates.firstOrNull { key(it.title) == w } }
            ?: wanted.firstNotNullOfOrNull { w -> candidates.firstOrNull { similar(key(it.title), w) } }
        val chosen = byName ?: candidates.minWith(compareBy<WikiPage>({ distanceM(it, lat, lng) ?: Double.MAX_VALUE }, { it.index }))
        return WikiPhoto(chosen.imageUrl!!, chosen.title, chosen.file)
    }

    /** The articles in an answer of the query API (`query.pages`); a broken answer has none. */
    fun pages(answer: String): List<WikiPage> {
        val root = runCatching { json.parseToJsonElement(answer) as? JsonObject }.getOrNull() ?: return emptyList()
        val pages = (root["query"] as? JsonObject)?.get("pages") as? JsonObject ?: return emptyList()
        return pages.values.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            if (p.containsKey("missing") || p.containsKey("invalid")) return@mapNotNull null
            val title = p.text("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val image = (p["thumbnail"] as? JsonObject)?.text("source")
                ?.takeIf { it.startsWith("https://") && isPhoto(it, p.text("pageimage")) }
            val c = (p["coordinates"] as? JsonArray)?.firstOrNull() as? JsonObject
            val globe = c?.text("globe")
            val onEarth = globe == null || globe == "earth"
            WikiPage(
                title = title,
                imageUrl = image,
                file = image?.let { p.text("pageimage")?.takeIf { n -> n.isNotBlank() } ?: fileOf(it) },
                lat = c?.number("lat")?.takeIf { onEarth },
                lng = c?.number("lon")?.takeIf { onEarth },
                type = c?.text("type")?.substringBefore('(')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                dimM = c?.number("dim"),
                index = (p["index"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE,
            )
        }
    }

    /** True when an article of [type] can stand for a place at all (not a country, not an event). */
    fun placeType(type: String?): Boolean = type !in neverTypes

    /** True for a photo: a JPEG (or TIFF, WebP) whose file name doesn't say map, flag, logo and the like. */
    fun isPhoto(imageUrl: String, file: String? = null): Boolean {
        val name = (file?.takeIf { it.isNotBlank() } ?: fileOf(imageUrl) ?: return false).lowercase()
        if (name.substringAfterLast('.', "") !in photoTypes) return false
        val words = name.substringBeforeLast('.').split(nonWord).filter { it.isNotEmpty() }.toSet()
        if (words.any { it in drawingWords }) return false
        return !("coat" in words && "arms" in words)
    }

    /** The original file's name in a Wikimedia image address (the thumbnail's folder, or the file itself). */
    private fun fileOf(imageUrl: String): String? {
        val segments = WebUrl.of(imageUrl)?.pathSegments?.filter { it.isNotEmpty() } ?: return null
        val last = segments.lastOrNull() ?: return null
        return if (thumbSize.containsMatchIn(last) && segments.size >= 2) segments[segments.size - 2] else last
    }

    fun distanceM(p: WikiPage, lat: Double, lng: Double): Double? =
        if (p.lat != null && p.lng != null) Geo.distanceM(lat, lng, p.lat, p.lng) else null

    /** Lower-case letters and digits, accents and a trailing "(Kyoto)" dropped, words joined by single spaces. */
    fun key(s: String): String =
        decomposed(s.replace(bracket, "")).replace(marks, "").lowercase().replace(nonWord, " ").trim()

    /** "Kiyomizu-dera" and "Kiyomizu-dera Temple": one name inside the other, as whole words, and not too short. */
    fun similar(a: String, b: String): Boolean {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 5 && " $long ".contains(" $short ")
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content
    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
}

/**
 * What to look for to give a located plan item a photo: its spot and the names it goes by, best first.
 */
data class PlaceQuery(val lat: Double, val lng: Double, val names: List<String>) {
    /** The spot to about 10 m and the names: a moved or renamed item looks again, the same place shares its photo. */
    val key: String get() = PlacePhotos.key(this)
}

/** Photos for plan items that have a place but no link, from Wikipedia (Me › Place photos). */
object PlacePhotos {
    const val SITE_NAME = "Wikipedia"
    const val NEAR_M = 1_500.0
    const val AIRPORT_M = 5_000.0
    const val TOWN_M = 20_000.0
    const val AREA_M = 50_000.0
    private const val MAX_DIM_M = 100_000.0
    private const val MAX_TITLES = 8

    private val json = Json { ignoreUnknownKeys = true }
    private val spaces = Regex("\\s+")
    private val notInTitle = Regex("[#<>\\[\\]{}|_]")
    private val towns = setOf("city", "adm3rd")
    private val areas = setOf("state", "adm1st", "adm2nd", "isle", "waterbody", "forest", "glacier", "mountain", "river", "pass")
    private val lead = Regex(
        "^(?:fly|flight|train|bus|coach|ferry|boat|cruise|drive|walk|cycle|ride|taxi|transfer|shinkansen|day trip|trip|" +
            "head|go|back|return|arrive|arrival|check[- ]?in|stay|night|lunch|dinner|breakfast|brunch|coffee|drinks|" +
            "visit|see|explore|hike|tour|evening|morning|afternoon|sunset|sunrise)" +
            "(?:\\s+(?:to|in|at|on|by|from|around|near|of|up))?[\\s,:]+",
        RegexOption.IGNORE_CASE,
    )

    /** The photo query for [item]: null for a Note, an item without a place, and an item with a link. */
    fun query(item: PlanItem): PlaceQuery? {
        val lat = item.lat ?: return null
        val lng = item.lng ?: return null
        if (lat == 0.0 && lng == 0.0) return null
        if (PlanKind.of(item.kind) == PlanKind.Note) return null
        if (item.url?.let(LinkUrls::normalize) != null) return null
        return PlaceQuery(lat, lng, names(item.title, item.placeName))
    }

    /** The names to look up, best first: the title, the title without what leads to the place, the place's first part. */
    fun names(title: String, placeName: String): List<String> {
        val t = title.replace(spaces, " ").trim()
        val bare = t.substringBefore(':').replace(lead, "").trim()
        val place = placeName.substringBefore(',').replace(spaces, " ").trim()
        return listOf(t, bare, place)
            .filter { n -> n.length >= 3 && n.count { it.isLetter() } >= 3 }
            .distinctBy { it.lowercase() }
    }

    fun key(q: PlaceQuery): String =
        "geo:${JavaText.fixed(q.lat, 4)},${JavaText.fixed(q.lng, 4)}" + q.names.joinToString("") { "|" + WikiPhotos.key(it) }

    /** The titles to ask for: each name as written and with its words capitalised. */
    fun titles(names: List<String>): List<String> = names
        .map { it.replace(notInTitle, " ").replace(spaces, " ").trim() }
        .flatMap { listOf(it, capitalised(it)) }
        .filter { it.length >= 3 }
        .distinct()
        .take(MAX_TITLES)

    private fun capitalised(s: String): String = s.split(' ').joinToString(" ") { w ->
        if (w.isNotEmpty() && w.all { !it.isLetter() || it.isLowerCase() }) w.replaceFirstChar { it.titlecase() } else w
    }

    /** The articles titled [titles] (redirects followed) with their lead images and coordinates. */
    fun titleQueryUrl(titles: List<String>): String = UrlCodec.withQuery(
        WikiPhotos.API,
        "action" to "query", "titles" to titles.joinToString("|"), "redirects" to "1",
        "prop" to "pageimages|coordinates", "piprop" to "thumbnail|name", "pithumbsize" to WikiPhotos.THUMB_PX.toString(),
        "coprop" to "type|dim", "format" to "json",
    )

    /** From a [titleQueryUrl] answer, the photo of the first of [titles] that is an article with a photo near the spot. */
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

    /** How far from the item an article of coordinates [type] may be and still be its place. */
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
    fun articleUrl(title: String): String = "https://en.wikipedia.org/wiki/" + UrlCodec.pathSegment(title.replace(' ', '_'))

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
 * Wikipedia's photo for a spot, asked only while Me › Place photos is on ([enabled]): an article titled like one of the
 * names near it, else the nearest article with a photo; then its author and licence: a photo without a reusable
 * licence isn't taken. [ask] makes one request and returns its answer.
 */
class WikiLookup(private val ask: suspend (url: String) -> String, private val enabled: suspend () -> Boolean) {
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

    private suspend fun credited(photo: WikiPhoto): WikiPhoto? {
        val file = photo.file ?: return null
        val credit = WikiCredits.parse(ask(WikiCredits.queryUrl(file)), file) ?: return null
        return photo.copy(credit = credit)
    }
}

/** Who took a Wikipedia photo and under which licence. */
data class WikiCredit(val author: String?, val license: String, val licenseUrl: String?, val file: String)

/** Credits for Wikipedia photos, from the file's Commons metadata. A photo without a reusable licence gets none. */
object WikiCredits {
    const val SOURCE = "Wikipedia"
    const val MAX_AUTHOR = 60

    private val json = Json { ignoreUnknownKeys = true }
    private val tags = Regex("<[^>]*>")
    private val spaces = Regex("\\s+")
    private val entity = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    private val restricted = Regex("(^|[^a-z])(nc|nd)([^a-z]|$)")

    /** The metadata query for [file] (its name without "File:"). */
    fun queryUrl(file: String): String = UrlCodec.withQuery(
        WikiPhotos.API,
        "action" to "query", "titles" to "File:$file", "prop" to "imageinfo", "iiprop" to "extmetadata",
        "iiextmetadatafilter" to "Artist|LicenseShortName|LicenseUrl|AttributionRequired|NonFree",
        "iiextmetadatalanguage" to "en", "format" to "json",
    )

    /** The credit in a [queryUrl] answer for [file]; null when the file isn't reusable or the answer is broken. */
    fun parse(answer: String, file: String): WikiCredit? {
        val root = runCatching { json.parseToJsonElement(answer) as? JsonObject }.getOrNull() ?: return null
        val pages = (root["query"] as? JsonObject)?.get("pages") as? JsonObject ?: return null
        val meta = pages.values.firstNotNullOfOrNull { page ->
            (((page as? JsonObject)?.get("imageinfo") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("extmetadata") as? JsonObject
        } ?: return null
        fun value(name: String): String? = ((meta[name] as? JsonObject)?.get("value") as? JsonPrimitive)?.content

        if (value("NonFree")?.trim()?.lowercase() == "true") return null
        val license = value("LicenseShortName")?.let(::plain)?.takeIf { it.isNotEmpty() } ?: return null
        if (!reusable(license)) return null
        val author = value("Artist")?.let(::plain)?.let(::short)?.takeIf { it.isNotEmpty() }
        val attribution = value("AttributionRequired")?.trim()?.lowercase() != "false" && !publicDomain(license)
        if (attribution && author == null) return null
        val url = value("LicenseUrl")?.trim()
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        return WikiCredit(author, license, url, file)
    }

    fun reusable(license: String): Boolean {
        val l = license.lowercase().replace('_', ' ').trim()
        if (l.startsWith("cc") && restricted.containsMatchIn(l)) return false
        return listOf(
            "cc by", "cc-by", "cc0", "cc zero", "cc-zero", "cc pdm", "cc-pd", "public domain", "pd", "gfdl", "fal",
            "free art", "ogl", "attribution", "copyrighted free use", "no restrictions",
        ).any { l.startsWith(it) }
    }

    fun publicDomain(license: String): Boolean {
        val l = license.lowercase().replace('_', ' ').trim()
        return listOf("public domain", "pd", "cc0", "cc zero", "cc-zero", "cc pdm", "cc-pd", "no restrictions").any { l.startsWith(it) }
    }

    /** "Photo: Jane Doe, CC BY-SA 4.0 · Wikipedia" (without an author: "Photo: Public domain · Wikipedia"). */
    fun line(author: String?, license: String): String =
        "Photo: " + listOfNotNull(author?.takeIf { it.isNotBlank() }, license).joinToString(", ") + " · " + SOURCE

    /** The file's page on Wikipedia: author, licence and source. */
    fun fileUrl(file: String): String = "https://en.wikipedia.org/wiki/" + UrlCodec.pathSegment("File:" + file.replace(' ', '_'))

    fun plain(html: String): String = decode(html.replace(tags, " ")).replace(spaces, " ").trim()

    fun short(text: String): String {
        if (text.length <= MAX_AUTHOR) return text
        val cut = text.substring(0, MAX_AUTHOR).substringBeforeLast(' ').trimEnd(',', ';', ' ', '-')
        return (cut.ifEmpty { text.substring(0, MAX_AUTHOR) }) + "…"
    }

    private fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
            else -> named[e.lowercase()]
        } ?: m.value
    }

    private fun codePoint(cp: Int): String? = if (cp in 0..0x10FFFF) {
        if (cp < 0x10000) cp.toChar().toString()
        else charArrayOf((0xD800 + ((cp - 0x10000) shr 10)).toChar(), (0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar()).concatToString()
    } else null
}

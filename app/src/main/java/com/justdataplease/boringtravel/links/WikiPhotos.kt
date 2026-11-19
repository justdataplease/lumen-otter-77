package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.places.Geo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer
import java.util.Locale

/**
 * A Wikipedia article's lead photo near a place: the thumbnail, the article's [title], the photo's [file] on Commons
 * (for its credit) and, once asked, its [credit] ([WikiCredits]).
 */
data class WikiPhoto(val imageUrl: String, val title: String, val file: String? = null, val credit: WikiCredit? = null)

/**
 * One article in a Wikipedia answer: its lead photo ([imageUrl], null when it has none or only a drawing) and where
 * it is. [type] is the coordinates' type ("city", "airport", "landmark" …), [dimM] the size the article gives.
 */
internal data class WikiPage(
    val title: String,
    val imageUrl: String?,
    /** The lead image's file name ("Yasaka_jinja.jpg"), with [imageUrl]. */
    val file: String? = null,
    val lat: Double?,
    val lng: Double?,
    val type: String?,
    val dimM: Double?,
    val index: Int,
)

/**
 * A photo for a place from Wikipedia: the articles within [RADIUS_M] of the pin (geosearch) with their lead images.
 * The article named like the place wins ("Yasaka Shrine"), else the nearest one with a photo. Only photos count:
 * SVGs, PNGs and GIFs, and files named as maps, flags, logos, coats of arms and the like are left out. Pure and unit
 * tested; the request itself is the caller's.
 */
object WikiPhotos {
    const val RADIUS_M = 200
    const val THUMB_PX = 640
    const val API = "https://en.wikipedia.org/w/api.php"
    private const val LIMIT = 5

    private val json = Json { ignoreUnknownKeys = true }
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val marks = Regex("\\p{M}+")
    private val bracket = Regex("\\s*\\([^)]*\\)\\s*$")
    private val thumbSize = Regex("^\\d+px-")

    /** Photos come as JPEG (TIFF and WebP too); PNG, GIF and SVG lead images are nearly always maps, logos or drawings. */
    private val photoTypes = setOf("jpg", "jpeg", "jpe", "webp", "tif", "tiff")

    /** Words in a file's name that make it a drawing or a symbol, not a photo of the place. */
    private val drawingWords = setOf(
        "map", "maps", "mapa", "karte", "carte", "locator", "location", "plan", "diagram", "chart", "relief",
        "flag", "flags", "bandera", "drapeau", "logo", "logos", "logotype", "wordmark", "emblem", "seal", "crest",
        "coa", "wappen", "blason", "escudo", "stemma", "insignia", "symbol", "icon", "signature",
    )

    /** Article types that are never "the spot": a country, a historic event. */
    private val neverTypes = setOf("country", "event")

    /** Big areas: their articles are near any pin inside them, so the nearest article never stands for them. */
    private val areaTypes = setOf("country", "state", "adm1st", "adm2nd", "event")

    /** The geosearch query for the articles near [lat],[lng] with their lead images and coordinates. */
    fun queryUrl(lat: Double, lng: Double): String = API.toHttpUrl().newBuilder()
        .addQueryParameter("action", "query")
        .addQueryParameter("generator", "geosearch")
        .addQueryParameter("ggscoord", String.format(Locale.ROOT, "%.6f|%.6f", lat, lng))
        .addQueryParameter("ggsradius", RADIUS_M.toString())
        .addQueryParameter("ggslimit", LIMIT.toString())
        .addQueryParameter("prop", "pageimages|coordinates")
        .addQueryParameter("piprop", "thumbnail|name")
        .addQueryParameter("pithumbsize", THUMB_PX.toString())
        .addQueryParameter("coprop", "type|dim")
        .addQueryParameter("format", "json")
        .build().toString()

    /** The photo to show for the place [name] at [lat],[lng], from a [queryUrl] answer; null when none fits. */
    fun pick(answer: String, name: String?, lat: Double, lng: Double): WikiPhoto? = pick(answer, listOfNotNull(name), lat, lng)

    /** As [pick], for a place that goes by any of [names] (the first that matches an article's title wins). */
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
    internal fun pages(answer: String): List<WikiPage> {
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
                type = c?.text("type")?.substringBefore('(')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
                dimM = c?.number("dim"),
                index = (p["index"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE,
            )
        }
    }

    /** True when an article of [type] can stand for a place at all (not a country, not an event). */
    internal fun placeType(type: String?): Boolean = type !in neverTypes

    /**
     * True for a photo: a JPEG (or TIFF, WebP) whose file name doesn't say map, flag, logo, coat of arms and the
     * like. [file] is the original file's name when the answer gives it, else it is read from [imageUrl]
     * (`…/thumb/9/96/Yasaka_jinja.jpg/640px-Yasaka_jinja.jpg`).
     */
    fun isPhoto(imageUrl: String, file: String? = null): Boolean {
        val name = (file?.takeIf { it.isNotBlank() } ?: fileOf(imageUrl) ?: return false).lowercase(Locale.ROOT)
        if (name.substringAfterLast('.', "") !in photoTypes) return false
        val words = name.substringBeforeLast('.').split(nonWord).filter { it.isNotEmpty() }.toSet()
        if (words.any { it in drawingWords }) return false
        return !("coat" in words && "arms" in words)
    }

    /** The original file's name in a Wikimedia image address (the thumbnail's folder, or the file itself). */
    private fun fileOf(imageUrl: String): String? {
        val segments = imageUrl.toHttpUrlOrNull()?.pathSegments?.filter { it.isNotEmpty() } ?: return null
        val last = segments.lastOrNull() ?: return null
        return if (thumbSize.containsMatchIn(last) && segments.size >= 2) segments[segments.size - 2] else last
    }

    internal fun distanceM(p: WikiPage, lat: Double, lng: Double): Double? =
        if (p.lat != null && p.lng != null) Geo.distanceM(lat, lng, p.lat, p.lng) else null

    /** Lower-case letters and digits, accents and a trailing "(Kyoto)" dropped, words joined by single spaces. */
    internal fun key(s: String): String =
        Normalizer.normalize(s.replace(bracket, ""), Normalizer.Form.NFD).replace(marks, "")
            .lowercase(Locale.ROOT).replace(nonWord, " ").trim()

    /** "Kiyomizu-dera" and "Kiyomizu-dera Temple": one name inside the other, as whole words, and not too short. */
    internal fun similar(a: String, b: String): Boolean {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 5 && " $long ".contains(" $short ")
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content
    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
}

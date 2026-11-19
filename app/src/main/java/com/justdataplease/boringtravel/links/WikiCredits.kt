package com.justdataplease.boringtravel.links

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

/**
 * Who took a Wikipedia photo and under which licence: [author] as plain text (null only for a public-domain file that
 * names nobody), the licence's short name ("CC BY-SA 4.0") and its page, and the [file] on Wikimedia Commons.
 */
data class WikiCredit(val author: String?, val license: String, val licenseUrl: String?, val file: String)

/**
 * Credits for Wikipedia photos. Most are CC BY-SA, which asks for the author and the licence wherever the photo is
 * shown: they come from the file's Commons metadata (`imageinfo` › `extmetadata`: Artist, LicenseShortName,
 * LicenseUrl), asked from Wikipedia's API with the photo. A file without a reusable licence (non-free, NC or ND, none
 * named) or without the author its licence asks for gets no credit, and its photo is never shown. Pure and unit tested;
 * the request is [WikiLookup]'s.
 */
object WikiCredits {
    /** Where the photos come from, at the end of every credit line. */
    const val SOURCE = "Wikipedia"

    /** Longer author lines (a list of names, a museum's whole credit) are cut at a word. */
    const val MAX_AUTHOR = 60

    private val json = Json { ignoreUnknownKeys = true }
    private val tags = Regex("<[^>]*>")
    private val spaces = Regex("\\s+")
    private val entity = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    private val restricted = Regex("(^|[^a-z])(nc|nd)([^a-z]|$)")

    /** The metadata query for [file] (its name without "File:", as `pageimage` gives it). */
    fun queryUrl(file: String): String = WikiPhotos.API.toHttpUrl().newBuilder()
        .addQueryParameter("action", "query")
        .addQueryParameter("titles", "File:$file")
        .addQueryParameter("prop", "imageinfo")
        .addQueryParameter("iiprop", "extmetadata")
        .addQueryParameter("iiextmetadatafilter", "Artist|LicenseShortName|LicenseUrl|AttributionRequired|NonFree")
        .addQueryParameter("iiextmetadatalanguage", "en")
        .addQueryParameter("format", "json")
        .build().toString()

    /** The credit in a [queryUrl] answer for [file]; null when the file isn't reusable or the answer is broken. */
    fun parse(answer: String, file: String): WikiCredit? {
        val root = runCatching { json.parseToJsonElement(answer) as? JsonObject }.getOrNull() ?: return null
        val pages = (root["query"] as? JsonObject)?.get("pages") as? JsonObject ?: return null
        // A file kept on Commons reads as "missing" on Wikipedia but still has its imageinfo.
        val meta = pages.values.firstNotNullOfOrNull { page ->
            (((page as? JsonObject)?.get("imageinfo") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("extmetadata") as? JsonObject
        } ?: return null
        fun value(name: String): String? = ((meta[name] as? JsonObject)?.get("value") as? JsonPrimitive)?.content

        if (value("NonFree")?.trim()?.lowercase(Locale.ROOT) == "true") return null
        val license = value("LicenseShortName")?.let(::plain)?.takeIf { it.isNotEmpty() } ?: return null
        if (!reusable(license)) return null
        val author = value("Artist")?.let(::plain)?.let(::short)?.takeIf { it.isNotEmpty() }
        val attribution = value("AttributionRequired")?.trim()?.lowercase(Locale.ROOT) != "false" && !publicDomain(license)
        if (attribution && author == null) return null
        val url = value("LicenseUrl")?.trim()
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        return WikiCredit(author, license, url, file)
    }

    /**
     * True for a licence that lets the app show the photo: Creative Commons BY, BY-SA and CC0 (not NC or ND), public
     * domain, GFDL, the Free Art License, OGL, "Attribution" and "Copyrighted free use". Anything else is left out.
     */
    fun reusable(license: String): Boolean {
        val l = license.lowercase(Locale.ROOT).replace('_', ' ').trim()
        if (l.startsWith("cc") && restricted.containsMatchIn(l)) return false
        return listOf(
            "cc by", "cc-by", "cc0", "cc zero", "cc-zero", "cc pdm", "cc-pd", "public domain", "pd", "gfdl", "fal",
            "free art", "ogl", "attribution", "copyrighted free use", "no restrictions",
        ).any { l.startsWith(it) }
    }

    /** A licence that asks for no author: public domain, CC0, no restrictions. */
    fun publicDomain(license: String): Boolean {
        val l = license.lowercase(Locale.ROOT).replace('_', ' ').trim()
        return listOf("public domain", "pd", "cc0", "cc zero", "cc-zero", "cc pdm", "cc-pd", "no restrictions").any { l.startsWith(it) }
    }

    /** "Photo: Jane Doe, CC BY-SA 4.0 · Wikipedia" (without an author: "Photo: Public domain · Wikipedia"). */
    fun line(author: String?, license: String): String =
        "Photo: " + listOfNotNull(author?.takeIf { it.isNotBlank() }, license).joinToString(", ") + " · " + SOURCE

    /** The file's page on Wikipedia (Commons files show their Commons page there): author, licence and source. */
    fun fileUrl(file: String): String = "https://en.wikipedia.org".toHttpUrl().newBuilder()
        .addPathSegment("wiki").addPathSegment("File:" + file.replace(' ', '_'))
        .build().toString()

    /** Commons' HTML (links, spans, line breaks) as one line of plain text. */
    internal fun plain(html: String): String = decode(html.replace(tags, " ")).replace(spaces, " ").trim()

    /** At most [MAX_AUTHOR] characters, cut at a word with "…". */
    internal fun short(text: String): String {
        if (text.length <= MAX_AUTHOR) return text
        val cut = text.substring(0, MAX_AUTHOR).substringBeforeLast(' ').trimEnd(',', ';', ' ', '-')
        return (cut.ifEmpty { text.substring(0, MAX_AUTHOR) }) + "…"
    }

    private fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
            else -> named[e.lowercase(Locale.ROOT)]
        } ?: m.value
    }

    private fun codePoint(cp: Int): String? = if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null
}

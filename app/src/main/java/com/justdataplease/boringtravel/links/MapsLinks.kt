package com.justdataplease.boringtravel.links

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/** A place read from a Google Maps link: its name (when the link has one) and the pin (when it has one). */
data class MapsPlace(val name: String?, val lat: Double?, val lng: Double?) {
    val hasPoint: Boolean get() = lat != null && lng != null
}

/**
 * Google Maps links: `google.com/maps/place/…`, `google.<country>/maps…`, `maps.google.*`, and the short
 * `maps.app.goo.gl/…` and `goo.gl/maps/…` that redirect to them. Maps pages only offer the generic Maps logo as
 * og:image, so they are never scraped: the place name and pin come from the link itself (the photo, from Wikipedia).
 * Pure and unit tested; following a short link's redirects is the caller's network step.
 */
object MapsLinks {
    private const val NUM = "(-?\\d{1,3}\\.\\d+)"
    /** The Maps pin in the `data=` part: `!3d<lat>!4d<lng>` (the place itself, better than the camera's `@`). */
    private val pinRe = Regex("!3d$NUM!4d$NUM")
    /** The camera: `/@35.0036,135.7785,17z`. */
    private val atRe = Regex("/@$NUM,$NUM")
    /** "35.0036,135.7785", "35.0036, 135.7785", "loc:35.0036 135.7785", "Yasaka Shrine@35.0036,135.7785". */
    private val pairRe = Regex("(?:^|@|loc:)\\s*$NUM\\s*[, ]\\s*$NUM\\s*$")
    private val googleHostRe = Regex("^(?:www\\.)?google\\.[a-z]{2,3}(?:\\.[a-z]{2})?$")
    private val mapsHostRe = Regex("^maps\\.google\\.[a-z]{2,3}(?:\\.[a-z]{2})?$")
    /** A long Maps link written in a page, plainly or JSON-escaped (`https:\/\/www.google.com\/maps\/…`). */
    private val linkInPageRe = Regex("https?:(?://|\\\\/\\\\/)(?:www\\.)?google\\.[a-z.]{2,6}(?:/|\\\\/)maps[^\"'\\s<>]*")

    /** The host whose favicon stands for every Maps link (the Maps pin). */
    const val FAVICON_HOST = "maps.google.com"
    const val SITE_NAME = "Google Maps"

    fun isMaps(url: String): Boolean = url.toHttpUrlOrNull()?.let(::isMaps) ?: false

    /**
     * A Google Maps search (Maps URLs, `api=1`) for [name] as people search for it, else for the point: it opens the
     * Maps app, or the site. Null with neither.
     */
    fun searchUrl(name: String?, lat: Double?, lng: Double?): String? {
        val q = name?.trim()?.takeIf { it.isNotEmpty() } ?: if (lat != null && lng != null) "$lat,$lng" else return null
        return "https://www.google.com/maps/search/?api=1&query=" + java.net.URLEncoder.encode(q, "UTF-8")
    }

    private fun isMaps(u: HttpUrl): Boolean =
        mapsHostRe.matches(u.host) || (googleHostRe.matches(u.host) && u.encodedPath.startsWith("/maps")) || isShort(u)

    /** A short link that needs its redirects followed before it says anything. */
    fun isShort(url: String): Boolean = url.toHttpUrlOrNull()?.let(::isShort) ?: false

    private fun isShort(u: HttpUrl): Boolean =
        u.host == "maps.app.goo.gl" || (u.host == "goo.gl" && u.encodedPath.startsWith("/maps"))

    /** The name and pin in a long Maps link, or null when it has neither (or isn't one, or is still a short link). */
    fun parse(url: String): MapsPlace? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (!isMaps(u) || isShort(u)) return null
        // "/maps/place/Yasaka+Shrine/@…" and "/maps/search/Yasaka+Shrine/@…"
        val named = u.encodedPathSegments.zipWithNext().firstOrNull { (a, _) -> a == "place" || a == "search" }?.second
            ?.let(::decodePart)?.takeIf { it.isNotBlank() }
        val query = listOf("q", "query", "destination", "daddr")
            .firstNotNullOfOrNull { u.queryParameter(it)?.trim()?.takeIf { q -> q.isNotEmpty() } }

        val point = pinRe.findAll(url).lastOrNull()?.let { pair(it.groupValues[1], it.groupValues[2]) }
            ?: atRe.find(u.encodedPath)?.let { pair(it.groupValues[1], it.groupValues[2]) }
            ?: listOfNotNull(query, u.queryParameter("ll"), u.queryParameter("center"), named).firstNotNullOfOrNull(::pairIn)

        val name = listOfNotNull(named, query).map { it.substringBefore('@').trim() }
            .firstOrNull { it.isNotBlank() && !pairRe.containsMatchIn(it) && !it.startsWith("loc:") }
        if (name == null && point == null) return null
        return MapsPlace(name, point?.first, point?.second)
    }

    /** Where a redirect to the consent screen was going (`consent.google.com/…?continue=<Maps link>`). */
    fun unwrapConsent(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        return if (u.host.startsWith("consent.google.")) u.queryParameter("continue") ?: url else url
    }

    /** The browser address inside an Android `intent://…;S.browser_fallback_url=…;end` redirect, if any. */
    fun fromIntent(location: String): String? {
        if (!location.startsWith("intent:", ignoreCase = true)) return null
        val raw = location.substringAfter("S.browser_fallback_url=", "").substringBefore(';').takeIf { it.isNotEmpty() } ?: return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
    }

    /** The first long Maps link written in a page (for a short link that answers with a page instead of a redirect). */
    fun findInHtml(html: String): String? = linkInPageRe.find(html)?.value
        ?.replace("\\/", "/")?.replace("\\u0026", "&")?.replace("&amp;", "&")?.trimEnd('\\')

    private fun decodePart(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s).trim()

    private fun pairIn(s: String): Pair<Double, Double>? = pairRe.find(s.trim())?.let { pair(it.groupValues[1], it.groupValues[2]) }

    private fun pair(a: String, b: String): Pair<Double, Double>? {
        val lat = a.toDoubleOrNull() ?: return null
        val lng = b.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) return null
        return lat to lng
    }
}

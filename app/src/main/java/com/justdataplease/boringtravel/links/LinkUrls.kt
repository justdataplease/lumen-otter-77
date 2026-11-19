package com.justdataplease.boringtravel.links

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A plan item's link as typed or pasted, made safe to keep, show and open. Pure and unit tested. */
object LinkUrls {
    private val schemeRe = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val webUrlRe = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?'\"»”’"

    /**
     * The link to keep, or null when [input] isn't a web address. Takes the first http(s) link out of pasted text
     * ("Yasaka Shrine https://maps.app.goo.gl/…", as Google Maps shares), adds https:// to a bare domain
     * ("example.com/menu"), and refuses other schemes (mailto:, javascript:, intent:, ftp:) and hosts without a dot.
     */
    fun normalize(input: String?): String? {
        var t = input?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.any { it.isWhitespace() }) t = webUrlRe.find(t)?.value?.trimEnd { it in TRAILING } ?: return null
        val withScheme = when {
            t.startsWith("//") -> "https:$t"
            else -> schemeRe.matchEntire(t)?.let { m ->
                val scheme = m.groupValues[1].lowercase()
                val rest = m.groupValues[2]
                when {
                    scheme == "http" || scheme == "https" -> if (rest.startsWith("//")) t else return null
                    // "example.com:8080/x" is a host and a port, not a scheme.
                    rest.firstOrNull()?.isDigit() == true -> "https://$t"
                    else -> return null
                }
            } ?: "https://$t"
        }
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (!validHost(url)) return null
        return url.toString()
    }

    private fun validHost(url: HttpUrl): Boolean {
        val host = url.host
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return false
        val tld = host.substringAfterLast('.')
        // A name needs a real-looking top-level domain ("example.c" is a typo); an IPv4 address ends in digits.
        return tld.length >= 2 || tld.all { it.isDigit() }
    }

    /** The host, lower-case ("www.example.com"), or null. */
    fun host(url: String): String? = url.toHttpUrlOrNull()?.host

    /** The domain people read: the host without "www." ("example.com"), or the link itself when it doesn't parse. */
    fun domain(url: String): String = host(url)?.removePrefix("www.") ?: url

    /**
     * Where the app fetches [url] from: https even when the link says http (Android refuses plain http, and nearly
     * every site answers both).
     */
    fun forFetch(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        return if (u.scheme == "http") u.newBuilder().scheme("https").build().toString() else u.toString()
    }

    /**
     * Google's favicon service for [host]: it redirects to gstatic and answers 200 with the site's icon when Google
     * knows it, and 404 with a generic globe when it doesn't (that globe is never shown).
     */
    fun favicon(host: String): String = "https://www.google.com/s2/favicons?domain=$host&sz=128"
}

package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.ai.ItineraryImport

/** A link shared into the app or pasted, and the name written beside it ("Yasaka Shrine" above a Maps link). */
data class SharedLink(val url: String, val name: String? = null)

/** What a text shared from another app becomes. */
sealed interface ShareRoute {
    /** A link to add to a plan (Add to plan). */
    data class Place(val link: SharedLink) : ShareRoute

    /** An itinerary for the AI planner's import, as before: a file, a Google Docs link or a longer text. */
    data object Itinerary : ShareRoute

    /** A few words without a link: nothing to add. */
    data object NoLink : ShareRoute
}

/**
 * Links in shared and pasted text. Google Maps shares "Yasaka Shrine\n<address>\nhttps://maps.app.goo.gl/…",
 * a browser shares the address (and the page's title as the subject). Pure and unit tested.
 */
object SharedLinks {
    /** A share with a link whose other words fit in this is a place to add; longer text is an itinerary. */
    const val SHORT_CHARS = 280
    const val SHORT_LINES = 4

    /** Words without a link up to this are "nothing to add"; longer text goes to the planner as before. */
    const val NO_LINK_CHARS = 200
    const val NO_LINK_LINES = 2

    const val MAX_NAME = 120
    private val urlRe = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
    private val spaces = Regex("\\s+")
    private const val TRAILING = ".,;:!?'\"»”’"
    private const val EDGES = " ,;:-–—|•·\t"

    /** [text] without its links: the words beside them. */
    fun words(text: String): String = urlRe.replace(text, " ").trim()

    /** The links in [text], in order, as [LinkUrls] keeps them. */
    fun links(text: String): List<String> =
        urlRe.findAll(text).mapNotNull { LinkUrls.normalize(it.value.trimEnd { c -> c in TRAILING }) }.toList()

    /**
     * The link to add from [text]: the first Google Maps link, else the first link, else a bare address on its own
     * ("example.com/menu"); null without one. Its name is the first line of words beside the links, else [subject]
     * (a browser's page title).
     */
    fun parse(text: String?, subject: String? = null): SharedLink? {
        val t = text?.trim().orEmpty()
        val all = links(t)
        val url = all.firstOrNull(MapsLinks::isMaps) ?: all.firstOrNull() ?: bare(t) ?: return null
        return SharedLink(url, nameIn(t, subject))
    }

    /**
     * The link in the item sheet's "Place or Google Maps link" field, where words are a place to search for: any link
     * with its scheme (alone or with a name beside it, as the Maps app shares it), or a bare Google Maps address
     * ("maps.app.goo.gl/…"). Any other bare address is a name ("St.Tropez"), so null.
     */
    fun inPlaceField(text: String): SharedLink? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val shared = parse(t) ?: return null
        return shared.takeIf { "://" in t || MapsLinks.isMaps(it.url) }
    }

    /** Where a share goes: Add to plan with a link, the planner with a file or an itinerary, else nothing to add. */
    fun route(text: String?, subject: String? = null, hasFile: Boolean = false): ShareRoute? {
        if (hasFile) return ShareRoute.Itinerary
        val t = text?.trim() ?: return null
        if (ItineraryImport.googleDocId(t) != null) return ShareRoute.Itinerary
        val words = urlRe.replace(t, " ").trim()
        val lines = words.lines().count { it.isNotBlank() }
        val link = parse(t, subject)
        return when {
            link != null && words.length <= SHORT_CHARS && lines <= SHORT_LINES -> ShareRoute.Place(link)
            link == null && words.length <= NO_LINK_CHARS && lines <= NO_LINK_LINES -> ShareRoute.NoLink
            else -> ShareRoute.Itinerary
        }
    }

    /** A single word that is a web address without its scheme ("example.com", not "v1.2" or "e.g."). */
    private fun bare(t: String): String? {
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val url = LinkUrls.normalize(t) ?: return null
        val tld = LinkUrls.host(url)?.substringAfterLast('.') ?: return null
        return url.takeIf { tld.length >= 2 && tld.all { it.isLetter() } }
    }

    private fun nameIn(text: String, subject: String?): String? {
        val line = urlRe.replace(text, " ").lines().map { it.replace(spaces, " ").trim { c -> c in EDGES } }.firstOrNull { it.isNotEmpty() }
        // A bare address alone is the link, not its name.
        val words = line?.takeUnless { bare(it) != null }
        return (words ?: subject?.trim()?.trim { it in EDGES })?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
    }
}

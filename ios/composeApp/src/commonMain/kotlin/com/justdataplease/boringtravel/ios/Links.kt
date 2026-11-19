package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind
import com.justdataplease.boringtravel.places.Geo
import com.justdataplease.boringtravel.places.LatLng
import com.justdataplease.boringtravel.places.NearTrip
import com.justdataplease.boringtravel.places.PlaceResult
import com.justdataplease.boringtravel.time.LocalDate
import kotlin.math.abs

/*
 * A plan item's links as the Android app reads them (links/LinkUrls.kt, MapsLinks.kt, LinkPlaces.kt, LinkFill.kt,
 * LinkRenew.kt, SharedLinks.kt, MetaTags.kt), written again here because those files parse addresses with OkHttp and
 * decode with java.net. Same rules and the same results ([WebUrl] reads addresses as OkHttp does).
 */

/** A plan item's link as typed or pasted, made safe to keep, show and open. */
object LinkUrls {
    // (?s): the dot takes line breaks too (Java's DOT_MATCHES_ALL, which Kotlin's common Regex has no option for).
    private val schemeRe = Regex("(?s)^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$")
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
        val url = WebUrl.of(withScheme) ?: return null
        if (!validHost(url)) return null
        return url.toString()
    }

    private fun validHost(url: WebUrl): Boolean {
        val host = url.host
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return false
        val tld = host.substringAfterLast('.')
        // A name needs a real-looking top-level domain ("example.c" is a typo); an IPv4 address ends in digits.
        return tld.length >= 2 || tld.all { it.isDigit() }
    }

    /** The host, lower-case ("www.example.com"), or null. */
    fun host(url: String): String? = WebUrl.of(url)?.host

    /** The domain people read: the host without "www." ("example.com"), or the link itself when it doesn't parse. */
    fun domain(url: String): String = host(url)?.removePrefix("www.") ?: url

    /** Where the app fetches [url] from: https even when the link says http (nearly every site answers both). */
    fun forFetch(url: String): String? {
        val u = WebUrl.of(url) ?: return null
        return if (u.scheme == "http") u.withScheme("https").toString() else u.toString()
    }

    /** Google's favicon service for [host]: 200 with the site's icon when Google knows it, else 404 (never shown). */
    fun favicon(host: String): String = "https://www.google.com/s2/favicons?domain=$host&sz=128"
}

/** A place read from a Google Maps link: its name (when the link has one) and the pin (when it has one). */
data class MapsPlace(val name: String?, val lat: Double?, val lng: Double?) {
    val hasPoint: Boolean get() = lat != null && lng != null
}

/**
 * Google Maps links: `google.com/maps/place/…`, `google.<country>/maps…`, `maps.google.*`, and the short
 * `maps.app.goo.gl/…` and `goo.gl/maps/…` that redirect to them. Maps pages only offer the generic Maps logo as
 * og:image, so they are never scraped: the place name and pin come from the link itself (the photo, from Wikipedia).
 */
object MapsLinks {
    private const val NUM = "(-?\\d{1,3}\\.\\d+)"
    private val pinRe = Regex("!3d$NUM!4d$NUM")
    private val atRe = Regex("/@$NUM,$NUM")
    private val pairRe = Regex("(?:^|@|loc:)\\s*$NUM\\s*[, ]\\s*$NUM\\s*$")
    private val googleHostRe = Regex("^(?:www\\.)?google\\.[a-z]{2,3}(?:\\.[a-z]{2})?$")
    private val mapsHostRe = Regex("^maps\\.google\\.[a-z]{2,3}(?:\\.[a-z]{2})?$")
    private val linkInPageRe = Regex("https?:(?://|\\\\/\\\\/)(?:www\\.)?google\\.[a-z.]{2,6}(?:/|\\\\/)maps[^\"'\\s<>]*")

    /** The host whose favicon stands for every Maps link (the Maps pin). */
    const val FAVICON_HOST = "maps.google.com"
    const val SITE_NAME = "Google Maps"

    fun isMaps(url: String): Boolean = WebUrl.of(url)?.let(::isMaps) ?: false

    /** A Google Maps search (Maps URLs, `api=1`) for [name], else for the point. Null with neither. */
    fun searchUrl(name: String?, lat: Double?, lng: Double?): String? {
        val q = name?.trim()?.takeIf { it.isNotEmpty() } ?: if (lat != null && lng != null) "$lat,$lng" else return null
        return "https://www.google.com/maps/search/?api=1&query=" + UrlCodec.formEncode(q)
    }

    private fun isMaps(u: WebUrl): Boolean =
        mapsHostRe.matches(u.host) || (googleHostRe.matches(u.host) && u.encodedPath.startsWith("/maps")) || isShort(u)

    /** A short link that needs its redirects followed before it says anything. */
    fun isShort(url: String): Boolean = WebUrl.of(url)?.let(::isShort) ?: false

    private fun isShort(u: WebUrl): Boolean =
        u.host == "maps.app.goo.gl" || (u.host == "goo.gl" && u.encodedPath.startsWith("/maps"))

    /** The name and pin in a long Maps link, or null when it has neither (or isn't one, or is still a short link). */
    fun parse(url: String): MapsPlace? {
        val u = WebUrl.of(url) ?: return null
        if (!isMaps(u) || isShort(u)) return null
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
        val u = WebUrl.of(url) ?: return url
        return if (u.host.startsWith("consent.google.")) u.queryParameter("continue") ?: url else url
    }

    /** The browser address inside an Android `intent://…;S.browser_fallback_url=…;end` redirect, if any. */
    fun fromIntent(location: String): String? {
        if (!location.startsWith("intent:", ignoreCase = true)) return null
        val raw = location.substringAfter("S.browser_fallback_url=", "").substringBefore(';').takeIf { it.isNotEmpty() } ?: return null
        return runCatching { UrlCodec.formDecode(raw) }.getOrNull()
    }

    /** The first long Maps link written in a page (for a short link that answers with a page instead of a redirect). */
    fun findInHtml(html: String): String? = linkInPageRe.find(html)?.value
        ?.replace("\\/", "/")?.replace("\\u0026", "&")?.replace("&amp;", "&")?.trimEnd('\\')

    private fun decodePart(s: String): String = runCatching { UrlCodec.formDecode(s) }.getOrDefault(s).trim()

    private fun pairIn(s: String): Pair<Double, Double>? = pairRe.find(s.trim())?.let { pair(it.groupValues[1], it.groupValues[2]) }

    private fun pair(a: String, b: String): Pair<Double, Double>? {
        val lat = a.toDoubleOrNull() ?: return null
        val lng = b.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) return null
        return lat to lng
    }
}

/** Where a Google Maps link puts a plan item. */
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

    /** [item] with the place from its Maps link, when it has no place yet: the pin, and the link's name unless it has one. */
    fun fill(item: PlanItem, place: MapsPlace?): PlanItem {
        if (place == null || !place.hasPoint || item.lat != null || item.lng != null) return item
        return item.copy(placeName = item.placeName.ifBlank { place.name.orEmpty() }, lat = place.lat, lng = place.lng)
    }

    /** The title of an item known only by its link: "Google Maps place" for a Maps link, else the site's domain. */
    fun placeholderTitle(url: String): String = if (MapsLinks.isMaps(url)) PLACEHOLDER_MAPS else LinkUrls.domain(url)

    /** [placeholderTitle] for an item with a website [url] and a Maps link [mapsUrl]: the Maps one first. */
    fun placeholderTitle(url: String?, mapsUrl: String?): String? = (mapsUrl ?: url)?.let(::placeholderTitle)

    const val PLACEHOLDER_MAPS = "Google Maps place"

    /** A plan item's two links as kept: a Google Maps link in the website becomes the Maps link unless there is one. */
    fun sortLinks(url: String?, mapsUrl: String?): Pair<String?, String?> =
        if (url != null && MapsLinks.isMaps(url)) null to (mapsUrl ?: url) else url to mapsUrl

    /** [item] with its links sorted ([sortLinks]); the same item when they already are. */
    fun sortLinks(item: PlanItem): PlanItem {
        val (url, maps) = sortLinks(item.url, item.mapsUrl)
        return if (url == item.url && maps == item.mapsUrl) item else item.copy(url = url, mapsUrl = maps)
    }

    /** [url] when it is an http(s) link, else null. */
    fun web(url: String?): String? = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /**
     * The links to keep from the item sheet's Website ([siteText]) and Google Maps link ([mapsText]) as typed: an empty
     * field removes its link; one that can't be kept keeps what [before] had; a Maps link typed as the website is the
     * Maps link.
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

    /** Whether [item]'s place is the one a Maps link gave it ([place], that link's): the same pin. */
    fun placeFrom(item: PlanItem, place: MapsPlace?): Boolean {
        val lat = item.lat
        val lng = item.lng
        if (place == null || !place.hasPoint || lat == null || lng == null) return false
        return abs(lat - place.lat!!) < SAME_PIN && abs(lng - place.lng!!) < SAME_PIN
    }

    /** [item] after its Maps link changed: a place the old link gave becomes the new link's; any other place stays. */
    fun follow(item: PlanItem, old: MapsPlace?, place: MapsPlace?): PlanItem {
        if (place == null || !place.hasPoint || !placeFrom(item, old)) return item
        return item.copy(placeName = place.name.orEmpty(), lat = place.lat, lng = place.lng)
    }

    /** [item] once its Maps link [url] answered after saving: [fill], and the place's name instead of a placeholder. */
    fun fillLater(item: PlanItem, url: String, place: MapsPlace): PlanItem {
        val filled = fill(item, place)
        val name = place.name?.substringBefore(',')?.trim()
        return if (!name.isNullOrEmpty() && filled.title == placeholderTitle(url)) filled.copy(title = name) else filled
    }

    /** Where the trip is, to lean a place search: the day's first located item, else the trip's. */
    fun near(items: List<PlanItem>, day: LocalDate?): LatLng? =
        (items.firstOrNull { it.day == day && it.lat != null && it.lng != null } ?: items.firstOrNull { it.lat != null && it.lng != null })
            ?.let { LatLng(it.lat!!, it.lng!!) }
}

/** What a link tells a plan item once it is known: its name, its place, its kind. */
object LinkFill {
    const val MAX_TITLE = 100

    /** The name a link gives its item: a Google Maps place's without the address after it ("Yasaka Shrine, Kyoto"). */
    fun nameOf(url: String, raw: String?): String? =
        raw?.let { if (MapsLinks.isMaps(url)) it.substringBefore(',') else it }?.trim()?.takeIf { it.isNotEmpty() }

    /** The name for [url]'s item: for a Google Maps link the shared one first, for a site its own title first. */
    fun nameFor(url: String, resolved: String?, shared: String?): String? {
        val own = nameOf(url, resolved)
        val given = nameOf(url, shared)
        return if (MapsLinks.isMaps(url)) given ?: own else own ?: given
    }

    /** Whether [title] is one the link [url] gave its item rather than the traveller's own. */
    fun fromLink(title: String, url: String, vararg names: String?): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return true
        return t == LinkPlaces.placeholderTitle(url) || names.any { n -> nameOf(url, n)?.take(MAX_TITLE)?.trim() == t }
    }

    /** Food or Stay when the name says so plainly ("Hotel Granvia", "Taverna Lefteris"); else null (leave the kind). */
    fun obviousKind(name: String?): PlanKind? =
        name?.let { KindWords.guessKind(it) }?.takeIf { it == PlanKind.Food || it == PlanKind.Stay }

    /** [item] with what the traveller left empty filled from its link: the place, the title and, unless touched, the kind. */
    fun fill(item: PlanItem, name: String?, place: MapsPlace?, kindTouched: Boolean, placeCleared: Boolean = false): PlanItem {
        var d = if (placeCleared) item else LinkPlaces.fill(item, place)
        val n = name?.trim()?.take(MAX_TITLE)?.takeIf { it.isNotEmpty() }
        if (d.title.isBlank()) d = d.copy(title = n ?: d.placeName.substringBefore(',').trim())
        if (!kindTouched) obviousKind(n ?: d.title)?.let { d = d.copy(kind = it.id) }
        return d
    }
}

/** The item sheet's Renew details: a plan item after its links were read again. Only what a link gave is replaced. */
object LinkRenew {
    /** One link read again: what it said before ([oldTitle]), what it says now ([newTitle]), the words it came with ([shared]). */
    data class Look(val url: String, val oldTitle: String?, val newTitle: String?, val shared: String? = null)

    fun apply(item: PlanItem, placeCleared: Boolean, site: Look?, maps: Look?, oldPlace: MapsPlace?, place: MapsPlace?): PlanItem {
        var d = item
        if (!placeCleared && place != null && place.hasPoint) {
            d = if (LinkPlaces.placeFrom(d, oldPlace)) {
                LinkPlaces.follow(d, oldPlace, place).let { if (place.name.isNullOrBlank()) it.copy(placeName = d.placeName) else it }
            } else {
                LinkPlaces.fill(d, place)
            }
        }
        val title = d.title.trim()
        val name = listOfNotNull(maps, site)
            .filter { LinkFill.fromLink(title, it.url, it.oldTitle, it.shared, MapsLinks.parse(it.url)?.name, oldPlace?.name?.takeIf { _ -> it === maps }) }
            .firstNotNullOfOrNull { LinkFill.nameFor(it.url, it.newTitle, it.shared)?.take(LinkFill.MAX_TITLE)?.trim()?.takeIf { n -> n.isNotEmpty() } }
        if (name != null && name != title) d = d.copy(title = name)
        return d
    }
}

/** A link shared into the app or pasted, and the name written beside it ("Yasaka Shrine" above a Maps link). */
data class SharedLink(val url: String, val name: String? = null)

/** What a text shared from another app becomes. */
sealed interface ShareRoute {
    data class Place(val link: SharedLink) : ShareRoute
    data object Itinerary : ShareRoute
    data object NoLink : ShareRoute
}

/** Links in shared and pasted text. */
object SharedLinks {
    const val SHORT_CHARS = 280
    const val SHORT_LINES = 4
    const val NO_LINK_CHARS = 200
    const val NO_LINK_LINES = 2
    const val MAX_NAME = 120
    private val urlRe = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
    private val spaces = Regex("\\s+")
    private const val TRAILING = ".,;:!?'\"»”’"
    private const val EDGES = " ,;:-–—|•·\t"

    fun words(text: String): String = urlRe.replace(text, " ").trim()

    /** The links in [text], in order, as [LinkUrls] keeps them. */
    fun links(text: String): List<String> =
        urlRe.findAll(text).mapNotNull { LinkUrls.normalize(it.value.trimEnd { c -> c in TRAILING }) }.toList()

    /** The link to add from [text]: the first Google Maps link, else the first link, else a bare address on its own. */
    fun parse(text: String?, subject: String? = null): SharedLink? {
        val t = text?.trim().orEmpty()
        val all = links(t)
        val url = all.firstOrNull(MapsLinks::isMaps) ?: all.firstOrNull() ?: bare(t) ?: return null
        return SharedLink(url, nameIn(t, subject))
    }

    /** The link in the item sheet's "Place or Google Maps link" field: any link with its scheme, or a bare Maps address. */
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
        if (googleDocId(t) != null) return ShareRoute.Itinerary
        val words = urlRe.replace(t, " ").trim()
        val lines = words.lines().count { it.isNotBlank() }
        val link = parse(t, subject)
        return when {
            link != null && words.length <= SHORT_CHARS && lines <= SHORT_LINES -> ShareRoute.Place(link)
            link == null && words.length <= NO_LINK_CHARS && lines <= NO_LINK_LINES -> ShareRoute.NoLink
            else -> ShareRoute.Itinerary
        }
    }

    /** A Google Doc's id in its https link (the Android app's ItineraryImport.googleDocId). */
    fun googleDocId(link: String): String? {
        val t = link.trim()
        val m = Regex("^https://([^/?#@:]+)(?::443)?(/[^?#]*)?(?:[?#].*)?$", RegexOption.IGNORE_CASE).matchEntire(t) ?: return null
        if (!m.groupValues[1].equals("docs.google.com", ignoreCase = true)) return null
        return Regex("^/document/(?:u/\\d+/)?d/([A-Za-z0-9_-]{5,200})(?:/.*)?$").matchEntire(m.groupValues[2])?.groupValues?.get(1)
    }

    private fun bare(t: String): String? {
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val url = LinkUrls.normalize(t) ?: return null
        val tld = LinkUrls.host(url)?.substringAfterLast('.') ?: return null
        return url.takeIf { tld.length >= 2 && tld.all { it.isLetter() } }
    }

    private fun nameIn(text: String, subject: String?): String? {
        val line = urlRe.replace(text, " ").lines().map { it.replace(spaces, " ").trim { c -> c in EDGES } }.firstOrNull { it.isNotEmpty() }
        val words = line?.takeUnless { bare(it) != null }
        return (words ?: subject?.trim()?.trim { it in EDGES })?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
    }
}

/** What a page says about itself for link previews. */
data class PageMeta(val imageUrl: String? = null, val title: String? = null, val siteName: String? = null)

/**
 * Reads a page's Open Graph and Twitter card tags out of its HTML: attributes in any order, single, double or no
 * quotes, entities, and image addresses that are relative or protocol-relative, resolved against the page's final
 * address. No HTML library: one pass of regular expressions over the first part of the page.
 */
object MetaTags {
    private val metaRe = Regex("<meta\\b((?:[^>\"']|\"[^\"]*\"|'[^']*')*)/?>", RegexOption.IGNORE_CASE)
    private val attrRe = Regex("([^\\s=/>\"']+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?")
    private val titleRe = Regex("(?s)<title\\b[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
    private val charsetRe = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)", RegexOption.IGNORE_CASE)
    private val entityRe = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val spaceRe = Regex("\\s+")

    private val imageKeys = listOf("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src")
    private val titleKeys = listOf("og:title", "twitter:title")
    private const val MAX_TEXT = 200

    fun parse(html: String, pageUrl: String): PageMeta {
        val tags = HashMap<String, String>()
        for (m in metaRe.findAll(html)) {
            val attrs = attributes(m.groupValues[1])
            val key = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.trim()?.lowercase() ?: continue
            val content = attrs["content"]?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (key !in tags) tags[key] = content // the first of each wins, as with crawlers
        }
        val image = imageKeys.firstNotNullOfOrNull { k -> tags[k]?.let { resolve(it, pageUrl) } }
        val title = (titleKeys.firstNotNullOfOrNull { tags[it] } ?: titleRe.find(html)?.groupValues?.get(1)?.let(::decode))?.let(::clean)
        val site = tags["og:site_name"]?.let(::clean)
        return PageMeta(image, title, site)
    }

    private fun attributes(raw: String): Map<String, String> = buildMap {
        for (a in attrRe.findAll(raw)) {
            val name = a.groupValues[1].lowercase()
            val value = a.groups[2]?.value ?: a.groups[3]?.value ?: a.groups[4]?.value ?: ""
            if (name !in this) put(name, value)
        }
    }

    /** [ref] as an absolute https address against [base], or null for data: URIs and anything that isn't http(s). */
    fun resolve(ref: String, base: String): String? {
        val r = ref.trim().replace(" ", "%20")
        if (r.isEmpty() || r.startsWith("data:", ignoreCase = true)) return null
        val url = WebUrl.of(base)?.resolve(r) ?: WebUrl.of(r) ?: return null
        return if (url.scheme == "http") url.withScheme("https").toString() else url.toString()
    }

    /** The character set a page names in its Content-Type header, else in its first bytes (`<meta charset>`). */
    fun charset(contentType: String?, head: String): String? =
        contentType?.let { charsetRe.find(it)?.groupValues?.get(1) } ?: charsetRe.find(head)?.groupValues?.get(1)

    /** Entities (`&amp;`, `&#39;`, `&#x27;`, `&nbsp;` …) as characters; unknown ones stay as written. */
    fun decode(s: String): String = if ('&' !in s) s else entityRe.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
            else -> named[e] ?: accented(e)
        } ?: m.value
    }

    /** `&eacute;`, `&Ouml;`, `&ccedil;` …: a letter and its accent, as one character. */
    private fun accented(e: String): String? {
        val mark = e.drop(1)
        if (mark !in accentNames || !e[0].isLetter()) return null
        return COMPOSED[e[0] + mark]?.toString()
    }

    private val accentNames = setOf("acute", "grave", "circ", "uml", "tilde", "ring", "cedil")

    /** The precomposed letters Java's NFC makes from a letter and one of those marks. */
    private val COMPOSED: Map<String, Char> = HashMap<String, Char>().apply {
        fun row(mark: String, letters: String, composed: String) = letters.forEachIndexed { i, l -> if (composed[i] != '-') put("$l$mark", composed[i]) }
        row("acute", "aeiouyAEIOUYcCnNsSzZlLrRgG", "áéíóúýÁÉÍÓÚÝćĆńŃśŚźŹĺĹŕŔǵǴ")
        row("grave", "aeiouAEIOUnN", "àèìòùÀÈÌÒÙǹǸ")
        row("circ", "aeiouAEIOUcCgGhHjJsSwWyYzZ", "âêîôûÂÊÎÔÛĉĈĝĜĥĤĵĴŝŜŵŴŷŶẑẐ")
        row("uml", "aeiouyAEIOUYhHwWxXt", "äëïöüÿÄËÏÖÜŸḧḦẅẄẍẌẗ")
        row("tilde", "anoANOiIuUeEyYvV", "ãñõÃÑÕĩĨũŨẽẼỹỸṽṼ")
        row("ring", "aAuUwy", "åÅůŮẘẙ")
        row("cedil", "cCsStTgGkKlLnNrReEdDhH", "çÇşŞţŢģĢķĶļĻņŅŗŖȩȨḑḐḩḨ")
    }

    private fun codePoint(cp: Int): String? = if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) {
        if (cp < 0x10000) cp.toChar().toString()
        else charArrayOf((0xD800 + ((cp - 0x10000) shr 10)).toChar(), (0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar()).concatToString()
    } else null

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "middot" to "·", "bull" to "•", "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€", "pound" to "£",
        "szlig" to "ß", "oslash" to "ø", "Oslash" to "Ø", "aelig" to "æ", "AElig" to "Æ", "deg" to "°", "times" to "×",
    )

    private fun clean(s: String): String? = s.replace(spaceRe, " ").trim().take(MAX_TEXT).takeIf { it.isNotEmpty() }
}

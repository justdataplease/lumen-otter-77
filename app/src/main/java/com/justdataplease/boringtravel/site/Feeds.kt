package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.links.MetaTags
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A site's RSS (2.0 and 1.0) or Atom feed as posts: each item's full text (`content:encoded`, Atom `content`, else the
 * description or summary), its address, date and categories, and pictures sent beside the text (enclosures, Media RSS).
 * Feeds are found through the page's `<link rel="alternate">` or at the usual places ([guesses]); older posts come
 * from the feed's own next page (Atom `rel="next"`, also Blogger's) or WordPress's `?paged=N`. Checked on 2026-11-12
 * against wordpress.org/news/feed/ (10 items a page, `?paged=999` answers 404) and a Blogger Atom feed. Pure parsing;
 * [SiteReader] fetches.
 */
object Feeds {
    /** A feed page: the site's [title], its [generator], the [posts] read, the [next] page when the feed names one. */
    data class Feed(val title: String, val generator: String, val posts: List<SitePost>, val next: String?)

    /** True when [text] starts like a feed (RSS, RDF or Atom), whatever the server called it. */
    fun isFeed(text: String): Boolean {
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').take(1_024).lowercase()
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) return false
        return "<rss" in head || "<feed" in head || "<rdf:rdf" in head
    }

    fun parse(xml: String, feedUrl: String): Feed? {
        if (!isFeed(xml)) return null
        val doc = Jsoup.parse(xml, feedUrl, Parser.xmlParser())
        doc.outputSettings().prettyPrint(false)
        val root = doc.children().firstOrNull { name(it) in ROOTS } ?: return null
        val atom = name(root) == "feed"
        val channel = if (atom) root else root.children().firstOrNull { name(it) == "channel" } ?: root
        val title = plain(child(channel, "title")?.wholeText())
        val generator = child(channel, "generator")?.let { (it.wholeText() + " " + it.attr("uri")).trim() }.orEmpty()
        // RSS 1.0 keeps its items beside the channel, not in it.
        val items = if (atom) children(root, "entry") else children(channel, "item") + (if (channel !== root) children(root, "item") else emptyList())
        val next = children(channel, "link", "atom:link").firstOrNull { it.attr("rel").equals("next", ignoreCase = true) }
            ?.attr("href")?.let { MetaTags.resolve(it, feedUrl) }
        return Feed(title, generator, items.mapNotNull { item(it, atom, feedUrl) }, next)
    }

    private fun item(e: Element, atom: Boolean, feedUrl: String): SitePost? {
        val link = if (atom) {
            children(e, "link").firstOrNull { it.attr("rel").isEmpty() || it.attr("rel").equals("alternate", ignoreCase = true) }?.attr("href")
        } else child(e, "link")?.wholeText()?.trim()?.ifEmpty { null } ?: e.attr("rdf:about").ifEmpty { null }
        val guid = child(e, "guid")?.takeIf { !it.attr("isPermaLink").equals("false", ignoreCase = true) }?.wholeText()?.trim()
        val idText = child(e, "id")?.wholeText()?.trim()
        val url = listOfNotNull(link, guid, idText).firstNotNullOfOrNull { MetaTags.resolve(it, feedUrl) } ?: return null
        val id = child(e, "guid")?.wholeText()?.trim()?.ifEmpty { null } ?: idText?.ifEmpty { null } ?: url
        val title = plain(child(e, "title")?.let { body(it) })
        val at = listOf("pubdate", "dc:date", "published", "updated", "issued", "created", "dcterms:created")
            .firstNotNullOfOrNull { n -> child(e, n)?.wholeText()?.let(SiteDates::parse) }
        val html = listOf("content:encoded", "content", "description", "summary").firstNotNullOfOrNull { n -> child(e, n)?.let(::body)?.takeIf { it.isNotBlank() } }.orEmpty()
        val blocks = HtmlBlocks.parse(html, url)
        val seen = blocks.filterIsInstance<SiteBlock.Picture>().mapTo(HashSet()) { it.url }
        val extra = enclosures(e, url).filter { seen.add(it) }.map { SiteBlock.Picture(it) }
        val tags = children(e, "category").mapNotNull { c -> plain(c.attr("term").ifEmpty { c.wholeText() }).takeIf { it.isNotEmpty() } }.distinct()
        return SitePost(id, url, title, at, blocks + extra, tags)
    }

    /** An element's text as the feed means it: escaped or CDATA HTML as HTML, Atom's inline XHTML as its markup. */
    private fun body(e: Element): String = if (e.attr("type").equals("xhtml", ignoreCase = true)) e.html() else e.wholeText()

    /** Pictures sent beside the text: RSS enclosures and Media RSS contents that are images. */
    private fun enclosures(e: Element, base: String): List<String> {
        val found = ArrayList<String>()
        for (c in e.getAllElements()) {
            val n = name(c)
            val type = c.attr("type").lowercase()
            val isImage = type.startsWith("image/") || c.attr("medium").equals("image", ignoreCase = true)
            if ((n == "enclosure" || n == "media:content") && isImage) MetaTags.resolve(c.attr("url"), base)?.let { found += it }
        }
        return found
    }

    /**
     * The feeds a page names (`<link rel="alternate" type="application/rss+xml|atom+xml">`), comments feeds left out,
     * in the page's order.
     */
    fun discover(html: String, pageUrl: String): List<String> {
        val doc: Document = Jsoup.parse(html, pageUrl)
        return doc.select("link[href]").filter { l ->
            val rel = l.attr("rel").lowercase().split(' ')
            val type = l.attr("type").lowercase()
            "alternate" in rel && (type == "application/rss+xml" || type == "application/atom+xml") &&
                "comment" !in l.attr("title").lowercase() && "comment" !in l.attr("href").lowercase()
        }.mapNotNull { MetaTags.resolve(it.attr("href"), pageUrl) }.distinct()
    }

    /**
     * Where feeds usually are (all checked live on 2026-11-12): Medium's `/feed/@name` (and `name.medium.com/feed`),
     * the page's own feed on Squarespace (`?format=rss`) and Wix (`…/blog-feed.xml`), then the site's root: WordPress
     * and Substack (`/feed`), Ghost (`/rss/`), Jekyll and Hugo, Blogger, Wix.
     */
    fun guesses(siteUrl: String): List<String> {
        val u = siteUrl.toHttpUrlOrNull() ?: return emptyList()
        val root = "https://${u.host}"
        val out = ArrayList<String>()
        val first = u.pathSegments.firstOrNull { it.isNotEmpty() }
        if (u.host == "medium.com" && first != null) out += "$root/feed/$first"
        if (u.host.endsWith(".medium.com")) out += "$root/feed"
        val path = u.encodedPath.trimEnd('/')
        if (path.isNotEmpty() && u.host != "medium.com") {
            out += "$root$path?format=rss"
            out += "$root$path/blog-feed.xml"
        }
        out += GUESSES.map { root + it }
        return out.distinct()
    }

    /** WordPress's older posts: `?paged=2`, `?paged=3`… on the same feed. */
    fun pagedUrl(feedUrl: String, page: Int): String? =
        feedUrl.toHttpUrlOrNull()?.newBuilder()?.setQueryParameter("paged", page.toString())?.build()?.toString()

    /** A WordPress feed (by its generator, or a `/feed/` address): older pages come from [pagedUrl]. */
    fun isWordPress(feed: Feed, feedUrl: String): Boolean {
        val path = feedUrl.toHttpUrlOrNull()?.encodedPath.orEmpty().trimEnd('/')
        return "wordpress" in feed.generator.lowercase() || path.endsWith("/feed") || feedUrl.toHttpUrlOrNull()?.queryParameter("feed") != null
    }

    private val ROOTS = setOf("rss", "feed", "rdf:rdf")
    private val GUESSES = listOf("/feed/", "/rss/", "/feed.xml", "/atom.xml", "/rss.xml", "/index.xml", "/feeds/posts/default", "/blog-feed.xml")

    private fun name(e: Element): String = e.tagName().lowercase()
    private fun child(e: Element, vararg names: String): Element? = e.children().firstOrNull { name(it) in names }
    private fun children(e: Element, vararg names: String): List<Element> = e.children().filter { name(it) in names }

    /** Titles and categories: entities and any markup as plain words. */
    private fun plain(s: String?): String = if (s.isNullOrBlank()) "" else Jsoup.parse(s).text().trim()
}

/** Dates as feeds and pages write them: RFC 822 (RSS), ISO 8601 with or without a zone (Atom, meta tags), a bare day. */
object SiteDates {
    private val rfc822 = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm[:ss] zzz", Locale.US),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm[:ss] Z", Locale.US),
        DateTimeFormatter.ofPattern("EEE, d MMM yy HH:mm[:ss] Z", Locale.US),
    )

    fun parse(text: String?): Instant? {
        val s = text?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return OffsetDateTime.parse(s).toInstant() }
        runCatching { return Instant.parse(s) }
        for (f in rfc822) runCatching { return ZonedDateTime.parse(s, f).toInstant() }
        // No zone: the site's own clock is unknown, so the time is read as UTC; a bare day sits at noon, which keeps it
        // on the same day in nearly every time zone.
        runCatching { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }
        runCatching { return LocalDate.parse(s.take(10)).atTime(12, 0).toInstant(ZoneOffset.UTC) }
        return null
    }
}

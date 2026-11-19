package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.links.MetaTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * A site's articles without a feed: the addresses its sitemap lists ([sitemap], with `robots.txt`'s Sitemap lines and
 * Disallow rules, [robots]) or its pages of posts link to ([index], following "Older posts"); [candidates] keeps the
 * ones on the same site that can be posts (no tag, category, author or about pages). Each is then read and kept only
 * when it is a dated article ([isArticle]). Pure parsing; [SiteReader] fetches.
 */
object SiteArticles {
    data class Sitemap(val pages: List<String>, val sitemaps: List<String>)

    /** A `<urlset>` (pages) or a `<sitemapindex>` (more sitemaps); posts' sitemaps first. */
    fun sitemap(xml: String, url: String): Sitemap? {
        val head = xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t').take(2_048).lowercase()
        if ("<urlset" !in head && "<sitemapindex" !in head) return null
        val doc = Jsoup.parse(xml, url, Parser.xmlParser())
        fun locs(parent: String) = doc.getAllElements().filter { it.tagName().lowercase() == parent }
            .mapNotNull { e -> e.children().firstOrNull { it.tagName().lowercase() == "loc" }?.text()?.trim()?.let { MetaTags.resolve(it, url) } }
        val children = locs("sitemap").sortedBy { s ->
            val name = s.substringAfterLast('/').lowercase()
            when {
                "post" in name -> 0
                SKIP_SITEMAPS.any { it in name } -> 2
                else -> 1
            }
        }.filterNot { s -> SKIP_SITEMAPS.any { it in s.substringAfterLast('/').lowercase() } && "post" !in s.substringAfterLast('/').lowercase() }
        return Sitemap(locs("url"), children)
    }

    data class Robots(val sitemaps: List<String>, private val disallow: List<String>, private val allow: List<String>) {
        /** False when the site asks robots not to read [path] (the longest matching rule wins). */
        fun allows(path: String): Boolean {
            val d = disallow.filter { it.isNotEmpty() && path.startsWith(it) }.maxOfOrNull { it.length } ?: return true
            val a = allow.filter { path.startsWith(it) }.maxOfOrNull { it.length } ?: return false
            return a >= d
        }
    }

    /** `robots.txt`: its Sitemap lines and the rules for every robot (`User-agent: *`) or this app. */
    fun robots(text: String, base: String): Robots {
        val sitemaps = ArrayList<String>()
        val disallow = ArrayList<String>()
        val allow = ArrayList<String>()
        var agents = ArrayList<String>()
        var inRules = false
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            val i = line.indexOf(':')
            if (i <= 0) continue
            val key = line.substring(0, i).trim().lowercase()
            val value = line.substring(i + 1).trim()
            when (key) {
                "sitemap" -> MetaTags.resolve(value, base)?.let { sitemaps += it }
                "user-agent" -> {
                    if (inRules) { agents = ArrayList(); inRules = false }
                    agents += value.lowercase()
                }
                "disallow", "allow" -> {
                    inRules = true
                    if (agents.any { it == "*" || "boringtravel" in it }) (if (key == "disallow") disallow else allow) += value
                }
            }
        }
        return Robots(sitemaps.distinct(), disallow, allow)
    }

    /** Pages of the same site (www. or not) that can be posts, each once, in the order given. */
    fun candidates(urls: List<String>, siteUrl: String): List<String> {
        val host = siteUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return emptyList()
        return urls.mapNotNull { u ->
            val url = u.toHttpUrlOrNull() ?: return@mapNotNull null
            if (url.host.removePrefix("www.") != host) return@mapNotNull null
            val parts = url.pathSegments.filter { it.isNotEmpty() }.map { it.lowercase() }
            if (parts.isEmpty()) return@mapNotNull null
            if (parts.any { it in SKIP_PARTS }) return@mapNotNull null
            if (FILE_RE.containsMatchIn(parts.last())) return@mapNotNull null
            url.newBuilder().fragment(null).build().toString()
        }.distinct()
    }

    data class Index(val posts: List<String>, val next: String?)

    /**
     * A page of posts: the links to its posts (headings' links inside articles, `rel=bookmark`, the usual title
     * classes) and the next page of older posts (`rel=next`, "Older posts", "Next page", Greek "Παλαιότερες").
     */
    fun index(html: String, pageUrl: String): Index {
        val doc = Jsoup.parse(html, pageUrl)
        val links = doc.select(
            "article h1 a[href], article h2 a[href], article h3 a[href], a[rel~=bookmark], .entry-title a[href], " +
                ".post-title a[href], h2.title a[href], .blog-post-title a[href], .post-header a[href]",
        ).map { it.attr("abs:href") }
        val next = doc.selectFirst("link[rel=next][href], a[rel~=next][href]")?.attr("abs:href")
            ?: doc.select("a[href]").firstOrNull { a -> OLDER.containsMatchIn(a.text().lowercase()) || "nav-previous" in a.parent()?.className().orEmpty() }?.attr("abs:href")
        return Index(candidates(links, pageUrl), next?.takeIf { it.isNotBlank() && it != pageUrl })
    }

    /**
     * True when [doc] is a dated article: Article/BlogPosting/NewsArticle markup with `datePublished` (JSON-LD), an
     * `article:published_time` tag, or an `<article>` holding a `<time datetime>`.
     */
    fun isArticle(doc: Document): Boolean {
        if (doc.selectFirst("meta[property=article:published_time][content]") != null) return true
        if (doc.selectFirst("[itemtype~=(?i)schema.org/(Blog|News)?(Posting|Article)] [itemprop=datePublished]") != null) return true
        if (doc.selectFirst("article time[datetime]") != null) return true
        return doc.select("script[type=application/ld+json]").any { s ->
            runCatching { Json { isLenient = true }.parseToJsonElement(s.data()) }.getOrNull()?.let(::datedArticle) == true
        }
    }

    /**
     * True when [doc] at [url] is one post, not a blog's home or a page of posts (owner, 15 Nov 2026: a link to one
     * article "should get only the one sent"): its address can be a post's ([candidates]: not the home, a tag or a
     * category) and the page says it is one article: `article:published_time` or a dated JSON-LD article; else
     * `og:type` article or its date, as long as it doesn't show several `<article>`s (a page of posts does).
     */
    fun isOnePost(doc: Document, url: String): Boolean {
        if (candidates(listOf(url), url).isEmpty()) return false
        if (doc.selectFirst("meta[property=article:published_time][content]") != null || jsonLdDate(doc) != null) return true
        val articles = doc.select("article").filter { a -> a.parents().none { it.normalName() == "article" } }
        if (articles.size > 1) return false
        val saysArticle = doc.selectFirst("meta[property=og:type]")?.attr("content")?.trim().equals("article", ignoreCase = true)
        return saysArticle || articles.singleOrNull()?.selectFirst("time[datetime]") != null
    }

    /** The `datePublished` of a JSON-LD Article, BlogPosting or NewsArticle, also inside `@graph`. */
    fun jsonLdDate(doc: Document): String? = doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { s ->
        runCatching { Json { isLenient = true }.parseToJsonElement(s.data()) }.getOrNull()?.let(::articleDate)
    }

    private fun datedArticle(e: JsonElement): Boolean = articleDate(e) != null

    private fun articleDate(e: JsonElement): String? = when (e) {
        is JsonArray -> e.firstNotNullOfOrNull(::articleDate)
        is JsonObject -> {
            val types = when (val t = e["@type"]) {
                is JsonPrimitive -> listOfNotNull(t.contentOrNull)
                is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
            val date = (e["datePublished"] as? JsonPrimitive)?.contentOrNull
            if (types.any { it in ARTICLE_TYPES } && !date.isNullOrBlank()) date else (e["@graph"])?.let(::articleDate)
        }
        else -> null
    }

    private val ARTICLE_TYPES = setOf("Article", "BlogPosting", "NewsArticle", "Report", "TechArticle", "SocialMediaPosting")
    private val SKIP_PARTS = setOf(
        "tag", "tags", "category", "categories", "author", "authors", "about", "about-us", "contact", "privacy", "privacy-policy",
        "terms", "search", "page", "feed", "rss", "wp-json", "wp-admin", "wp-content", "cart", "shop", "product", "products",
        "login", "signin", "sign-in", "account", "subscribe", "archive", "archives", "attachment", "comments", "label", "labels",
    )
    private val SKIP_SITEMAPS = listOf("page", "category", "tag", "author", "product", "user", "attachment", "image", "video")
    private val FILE_RE = Regex("""\.(jpe?g|png|gif|webp|svg|pdf|xml|zip|mp3|mp4|css|js|json|txt)$""")
    private val OLDER = Regex("""older (posts|entries)|next page|previous posts|παλαιότερ|επόμενη σελίδα|« older|older »""")
}

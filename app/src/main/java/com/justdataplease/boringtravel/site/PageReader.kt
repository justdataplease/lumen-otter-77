package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.links.MetaTags
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A page without a feed, read as one post: its title (`og:title`, the first heading, else the page title), its date
 * (`article:published_time`, `datePublished`, the first `<time datetime>`), its tags (`article:tag`) and the text and
 * pictures of its article (the one `<article>`, else `articleBody`, `<main>`, the usual content classes, else the
 * body), without menus, headers, footers, sidebars, comments and share buttons. Pages that build their text with
 * JavaScript have nothing to read here. Pure (jsoup only).
 */
object PageReader {
    fun parse(html: String, url: String): SitePost? = parse(Jsoup.parse(html, url), url)

    /** The page as a post only when it is a dated article ([SiteArticles.isArticle]): a site's posts without a feed. */
    fun article(html: String, url: String): SitePost? {
        val doc = Jsoup.parse(html, url)
        if (!SiteArticles.isArticle(doc)) return null
        return parse(doc, url)?.takeIf { it.at != null }
    }

    /** The page as a post only when its address is one post's ([SiteArticles.isOnePost]), dated or not. */
    fun onePost(html: String, url: String): SitePost? {
        val doc = Jsoup.parse(html, url)
        if (!SiteArticles.isOnePost(doc, url)) return null
        return parse(doc, url)
    }

    private fun parse(doc: Document, url: String): SitePost? {
        val title = (meta(doc, "og:title") ?: doc.selectFirst("h1")?.text() ?: doc.title()).trim()
        val at = date(doc)
        val tags = doc.select("meta[property=article:tag]").mapNotNull { it.attr("content").trim().takeIf { t -> t.isNotEmpty() } }.distinct()
        val canonical = doc.selectFirst("link[rel=canonical][href]")?.attr("href")?.let { MetaTags.resolve(it, url) } ?: MetaTags.resolve(url, url) ?: url
        val formats = HtmlBlocks.styles(doc)
        doc.select(CLUTTER).remove()
        val root = content(doc) ?: return null
        // The title is the note's own title: the same words as the article's first heading are not repeated.
        root.selectFirst("h1, h2")?.takeIf { it.text().trim().equals(title, ignoreCase = true) }?.remove()
        val blocks = HtmlBlocks.of(root, url, formats)
        if (blocks.isEmpty()) return null
        return SitePost(canonical, canonical, title, at, blocks, tags)
    }

    /** The site's own name: `og:site_name`, else the page's title ([orTitle]: a post's page is titled after the post). */
    fun siteName(html: String, orTitle: Boolean = true): String? {
        val doc = Jsoup.parse(html)
        return (meta(doc, "og:site_name") ?: if (orTitle) doc.title() else "").trim().takeIf { it.isNotEmpty() }
    }

    private fun content(doc: Document): Element? {
        doc.select("article").singleOrNull()?.let { return it }
        return CONTENT.firstNotNullOfOrNull { doc.selectFirst(it) } ?: doc.body()
    }

    private fun date(doc: Document): java.time.Instant? =
        (listOf("meta[property=article:published_time]", "meta[itemprop=datePublished]", "meta[name=date]").firstNotNullOfOrNull { doc.selectFirst(it)?.attr("content") }
            ?: SiteArticles.jsonLdDate(doc)
            ?: doc.selectFirst("[itemprop=datePublished][datetime], [itemprop=datePublished][content]")?.let { it.attr("datetime").ifEmpty { it.attr("content") } }
            ?: doc.selectFirst("article time[datetime], time[datetime]")?.attr("datetime"))?.let(SiteDates::parse)

    private fun meta(doc: Document, property: String): String? =
        doc.selectFirst("meta[property=$property], meta[name=$property]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    private val CONTENT = listOf("[itemprop=articleBody]", "main", ".post-content", ".entry-content", ".post-body", ".article-body", ".post", "#content")
    private const val CLUTTER = "script, style, noscript, nav, header, footer, aside, form, .comments, #comments, .comment-list, " +
        ".sharedaddy, .share, .sharing, .social, .related, #related, .sidebar, #sidebar, .widget, .navigation, .breadcrumbs"
}

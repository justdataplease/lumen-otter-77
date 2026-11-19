package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.links.LinkHttpException
import com.justdataplease.boringtravel.links.LinkUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId

/**
 * A Google Doc through the owner's Drive (the planner's access path): its title and its HTML as [read] makes it from
 * the export stream. Null when Drive isn't connected or may not open the document (with the `drive.file` scope, only
 * documents this app made, such as its own trip exports).
 */
interface DriveDocs {
    suspend fun html(id: String, read: (InputStream) -> String): DriveDoc?
}

data class DriveDoc(val title: String?, val html: String)

/**
 * Reads every post at a web address, trying in turn:
 * 1. a Google Doc ([GoogleDocs]): through Drive when it can, else its public export, else its published page;
 * 2. Tumblr's read API ([TumblrApi]; `*.tumblr.com`, tumblr.com/name and any other domain, since a blog's own domain
 *    answers it too), reblogs left out and counted;
 * 3. the site's feed ([Feeds]: the address itself, the feeds the page names, the usual places including Substack,
 *    Medium, Ghost, Squarespace and Wix) page after page; a feed that holds only the newest posts goes on with the
 *    site's sitemap for the rest;
 * 4. the site's dated articles through its sitemap or its pages of posts ([SiteArticles]);
 * 5. the page itself.
 * An address that is one post's (a Tumblr post, an article of a blog) gives that post only, with the blog's address in
 * [SiteRead.whole] for copying all of it.
 * A single long page or document is cut into a note per section ([SiteSections]). Posts already copied ([isCopied])
 * are counted, not read again, and don't count towards the limits ([SiteLimits]), so copying again continues where a
 * limit stopped. [stop] ends the reading early with what was found. Requests to a site are spaced by
 * [SiteLimits.PAGE_PAUSE_MS] and never leave it (sitemaps and pages of posts: the same host only, `robots.txt`
 * respected). Plain Kotlin over [SiteWeb], so the whole order is unit tested.
 */
class SiteReader(
    private val web: SiteWeb,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val drive: DriveDocs? = null,
    /** Where pictures that come inside a page are saved (a Google Doc's); null: such pictures can't be kept. */
    private val inlineDir: () -> File? = { null },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private var requests = 0

    suspend fun read(
        address: String,
        isCopied: suspend (SitePost) -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
        stop: () -> Boolean = { false },
    ): SiteRead {
        requests = 0
        val url = address(address) ?: throw SiteReadException(NOT_AN_ADDRESS)
        val host = LinkUrls.domain(url)
        val found = Found(isCopied, onProgress, stop)
        GoogleDocs.address(url)?.let { return checked(doc(it, found)) }
        TumblrApi.blog(url)?.let { blog -> tumblr(blog, host, found)?.let { return checked(it) } }
        return checked(site(url, host, found))
    }

    private fun checked(read: SiteRead): SiteRead {
        if (read.posts.isEmpty() && read.alreadyCopied == 0) {
            throw SiteReadException(if (read.reblogs == 0) NO_POSTS else if (read.whole != null) A_REBLOG else ONLY_REBLOGS)
        }
        return read
    }

    // ---- Google Docs

    private sealed interface Export {
        data class Read(val title: String?, val html: String) : Export
        data object SignIn : Export
    }

    private suspend fun doc(address: GoogleDocs.Address, found: Found): SiteRead {
        val canonical = GoogleDocs.canonical(address)
        val dir = inlineDir()
        var pictures: InlinePictures? = null
        fun pictures(): InlinePictures = pictures ?: InlinePictures(dir ?: throw SiteReadException(COULD_NOT_READ)).also { pictures = it }
        fun text(input: InputStream): String =
            pictures().read(input, SiteLimits.MAX_DOC_BYTES, SiteLimits.MAX_PAGE_BYTES, SiteLimits.MAX_PICTURE_BYTES)

        var title: String? = null
        var html: String? = null
        if (address is GoogleDocs.Address.Doc) {
            drive?.let { d ->
                try {
                    d.html(address.id, ::text)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }?.let { title = it.title; html = it.html }
            if (html == null) {
                val export = try {
                    requestStream(GoogleDocs.exportUrl(address.id)) { s ->
                        if (GoogleDocs.isSignIn(s.url)) Export.SignIn else Export.Read(GoogleDocs.titleFromDisposition(s.disposition), text(s.input))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LinkHttpException) {
                    if (e.code == 404) throw SiteReadException(DOC_NOT_FOUND)
                    Export.SignIn
                } catch (_: IOException) {
                    throw SiteReadException(NO_CONNECTION)
                }
                when (export) {
                    is Export.Read -> { title = export.title; html = export.html }
                    else -> {
                        // Not shared with everyone who has the link: maybe published to the web.
                        html = page(GoogleDocs.pubUrl(address.id))?.also { title = GoogleDocs.pageTitle(it) }
                    }
                }
            }
        } else if (address is GoogleDocs.Address.Published) {
            html = page(GoogleDocs.pubUrl(address))?.also { title = GoogleDocs.pageTitle(it) }
        }
        val text = html ?: throw SiteReadException(DOC_NOT_SHARED)
        val post = GoogleDocs.post(text, canonical, title) ?: throw SiteReadException(NO_POSTS)
        found.add(SiteSections.split(post, zone(), today()))
        val name = post.title.ifBlank { "Google Doc" }
        val truncated = pictures?.truncated == true
        return SiteRead(name, "docs.google.com", SiteKind.Doc, found.posts, found.copied, more = found.cut, stopped = truncated,
            inline = pictures?.files.orEmpty())
    }

    /** A page's text, or null when it doesn't answer (a published page that isn't). */
    private suspend fun page(url: String): String? = try {
        request(url)?.takeUnless { GoogleDocs.isSignIn(it.url) }?.text
    } catch (e: CancellationException) {
        throw e
    } catch (_: LinkHttpException) {
        null
    } catch (_: IOException) {
        throw SiteReadException(NO_CONNECTION)
    }

    // ---- Tumblr

    /**
     * Null when the address isn't a Tumblr blog (another domain whose read API doesn't answer). A tumblr.com blog that
     * doesn't answer is private or gone: its page is a JavaScript app with nothing to read, so that is said plainly.
     */
    private suspend fun tumblr(blog: TumblrApi.Blog, host: String, found: Found): SiteRead? {
        if (blog.post != null) return tumblrPost(blog, host, found)
        var start = 0
        var num = TumblrApi.PAGE
        var title = ""
        var total = Int.MAX_VALUE
        var pages = 0
        while (start < total && pages < SiteLimits.MAX_PAGES && !found.done) {
            pages++
            val text = try {
                request(TumblrApi.pageUrl(blog, start, num)) { it == null || "javascript" in it || "json" in it || it == "text/plain" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (start == 0 && blog.known) throw SiteReadException(if (e is LinkHttpException) TUMBLR_PRIVATE else NO_CONNECTION)
                if (start > 0) found.stopEarly()
                null
            }
            val page = text?.let { TumblrApi.parse(it.text) }
            if (page == null) {
                // 50 long posts can pass the page limit: ask for fewer.
                if (text?.truncated == true && num == TumblrApi.PAGE) {
                    num = TumblrApi.SMALL_PAGE
                    continue
                }
                if (start == 0) {
                    if (blog.known) throw SiteReadException(TUMBLR_PRIVATE)
                    return null
                }
                found.stopEarly()
                break
            }
            if (start == 0) {
                title = page.title
                total = page.total
            }
            if (page.count == 0 || !found.add(page.posts)) break
            start += page.count
        }
        val more = found.cut || start < total
        return SiteRead(title.ifBlank { host }, host, SiteKind.Tumblr, found.posts, found.copied, more, found.reblogs, found.stopped)
    }

    /**
     * One post of a Tumblr blog, when the address was the post's (owner, 15 Nov 2026: "it should get only the one
     * sent"): a single call for that post. Null when the address isn't a Tumblr blog's.
     */
    private suspend fun tumblrPost(blog: TumblrApi.Blog, host: String, found: Found): SiteRead? {
        val text = try {
            request(TumblrApi.pageUrl(blog, 0)) { it == null || "javascript" in it || "json" in it || it == "text/plain" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (blog.known) throw SiteReadException(if (e is LinkHttpException) POST_NOT_FOUND else NO_CONNECTION)
            null
        }
        val page = text?.let { TumblrApi.parse(it.text) }
        if (page == null) {
            if (blog.known) throw SiteReadException(POST_NOT_FOUND)
            return null
        }
        found.add(page.posts.filter { it.id == blog.post }.ifEmpty { page.posts.take(1) })
        return SiteRead(page.title.ifBlank { host }, host, SiteKind.Tumblr, found.posts, found.copied, reblogs = found.reblogs, whole = blog.base)
    }

    // ---- Feeds, articles, the page

    private suspend fun site(url: String, host: String, found: Found): SiteRead {
        val page = try {
            request(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw SiteReadException(if (e is LinkHttpException) COULD_NOT_READ else NO_CONNECTION)
        } ?: throw SiteReadException(COULD_NOT_READ)
        val pageUrl = page.url
        // A shared link that only passes on to a Tumblr blog or post (tmblr.co, at.tumblr.com).
        if (LinkUrls.domain(pageUrl) != host) {
            TumblrApi.blog(pageUrl)?.takeIf { it.known }?.let { blog -> tumblr(blog, LinkUrls.domain(pageUrl), found)?.let { return it } }
        }
        val siteName = PageReader.siteName(page.text)
        // A link to one post is that post only, not the blog around it (owner, 15 Nov 2026).
        if (!Feeds.isFeed(page.text)) PageReader.onePost(page.text, pageUrl)?.let { post ->
            found.add(listOf(post))
            // The whole blog: the feed the post's page names (a blog under example.com/news/), else its site.
            val whole = Feeds.discover(page.text, pageUrl).firstOrNull { sameSite(it, pageUrl) } ?: home(pageUrl)
            return SiteRead(PageReader.siteName(page.text, orTitle = false) ?: host, host, SiteKind.Page, found.posts, found.copied, whole = whole)
        }
        val feed: Pair<String, Feeds.Feed>? = if (Feeds.isFeed(page.text)) {
            Feeds.parse(page.text, pageUrl)?.let { pageUrl to it }
        } else {
            val named = Feeds.discover(page.text, pageUrl).take(MAX_NAMED_FEEDS)
            firstFeed(named) ?: firstFeed(Feeds.guesses(pageUrl).filter { it !in named })
        }
        if (feed != null && feed.second.posts.isNotEmpty()) {
            val name = feed.second.title.ifBlank { siteName.orEmpty() }.ifBlank { host }
            return readFeed(feed.first, feed.second, name, host, found)
        }
        val isFeedPage = Feeds.isFeed(page.text)
        if (!isFeedPage && articles(pageUrl, page.text, found)) {
            return SiteRead(siteName ?: host, host, SiteKind.Articles, found.posts, found.copied, found.cut, stopped = found.stopped)
        }
        val post = PageReader.parse(page.text, pageUrl) ?: throw SiteReadException(NO_POSTS)
        found.add(SiteSections.split(post, zone(), today()))
        return SiteRead(siteName ?: host, host, SiteKind.Page, found.posts, found.copied)
    }

    /** The first of [urls] that answers with a feed holding posts. */
    private suspend fun firstFeed(urls: List<String>): Pair<String, Feeds.Feed>? {
        for (u in urls) {
            val text = try {
                request(u, NOT_HTML)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            Feeds.parse(text.text, text.url)?.takeIf { it.posts.isNotEmpty() }?.let { return u to it }
        }
        return null
    }

    /** The feed's pages, newest first, until a limit, the last page, or a page with nothing new. */
    private suspend fun readFeed(feedUrl: String, first: Feeds.Feed, name: String, host: String, found: Found): SiteRead {
        var feed = first
        var current = feedUrl
        var page = 1
        var pages = 1
        var paged = false
        var keep = found.add(feed.posts)
        while (keep && pages < SiteLimits.MAX_PAGES) {
            val next = feed.next ?: if (Feeds.isWordPress(first, feedUrl)) Feeds.pagedUrl(feedUrl, page + 1) else null
            if (next == null || next == current) break
            val text = try {
                request(next, NOT_HTML)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkHttpException) {
                // Past the last page (WordPress answers 404); anything else stopped the reading early.
                if (e.code != 404 && e.code != 410) found.stopEarly()
                null
            } catch (_: Exception) {
                found.stopEarly()
                null
            } ?: break
            val nextFeed = Feeds.parse(text.text, text.url) ?: break
            val seen = found.seen
            keep = found.add(nextFeed.posts)
            if (found.seen == seen) break // the site ignores the page number
            paged = true
            feed = nextFeed
            current = next
            page++
            pages++
        }
        // A feed of only the newest posts (Substack, Medium, Ghost and the like send 10 to 25): the sitemap has the rest.
        if (!paged && !found.cut && first.posts.size >= FEED_CAP_HINT) {
            // The rest from the sitemap of the posts' own site, under the path they share (medium.com/@name/…: only
            // that writer's). Medium lists no writer's posts in its sitemaps, so it isn't asked.
            val postUrl = first.posts.first().url
            val prefix = commonPath(first.posts.map { it.url })
            if (postUrl.toHttpUrlOrNull()?.host?.endsWith("medium.com") != true) articles(postUrl, null, found, prefix)
        }
        val more = found.cut || (pages >= SiteLimits.MAX_PAGES && (feed.next != null || Feeds.isWordPress(first, feedUrl)))
        return SiteRead(name, host, SiteKind.Feed, found.posts, found.copied, more, stopped = found.stopped)
    }

    /**
     * The site's dated articles: the pages its sitemaps list ([SiteArticles.sitemap], `robots.txt` first), else the
     * posts its pages of posts link to ([SiteArticles.index], from [startHtml]); each read and kept when it is a dated
     * article. True when any was found (or was copied before).
     */
    private suspend fun articles(siteUrl: String, startHtml: String?, found: Found, prefix: String = "/"): Boolean {
        val root = siteUrl.toHttpUrlOrNull()?.let { "https://${it.host}" } ?: return false
        val robots = try {
            request("$root/robots.txt", NOT_HTML)?.text?.let { SiteArticles.robots(it, root) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: SiteArticles.Robots(emptyList(), emptyList(), emptyList())
        val pages = LinkedHashSet<String>()
        val queue = ArrayDeque(robots.sitemaps.ifEmpty { SITEMAPS.map { root + it } })
        val visited = HashSet<String>()
        var sitemaps = 0
        while (queue.isNotEmpty() && sitemaps < SiteLimits.MAX_SITEMAPS && !found.done) {
            val s = queue.removeFirst()
            if (!visited.add(s)) continue
            val text = try {
                request(s, NOT_HTML)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            val map = SiteArticles.sitemap(text.text, text.url) ?: continue
            sitemaps++
            pages += SiteArticles.candidates(map.pages, siteUrl)
            map.sitemaps.forEach { if (it !in visited) queue.addLast(it) }
        }
        if (pages.isEmpty() && startHtml != null) indexPages(siteUrl, startHtml, pages)
        val candidates = pages.filter { u ->
            val path = u.toHttpUrlOrNull()?.encodedPath ?: "/"
            robots.allows(path) && path.startsWith(prefix)
        }
        val before = found.posts.size + found.copied
        var misses = 0
        var fetched = 0
        for (u in candidates) {
            if (found.done || fetched >= SiteLimits.MAX_ARTICLE_FETCHES) {
                if (fetched >= SiteLimits.MAX_ARTICLE_FETCHES) found.cut = true
                break
            }
            if (found.knows(u)) continue
            fetched++
            val text = try {
                request(u) { it == null || "html" in it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkHttpException) {
                null
            } catch (_: IOException) {
                found.stopEarly()
                break
            } ?: continue
            val post = PageReader.article(text.text, text.url)
            if (post == null) {
                misses++
                if (found.posts.size + found.copied == before && misses >= SiteLimits.MAX_MISSES) break
                continue
            }
            if (!found.add(listOf(post))) break
        }
        return found.posts.size + found.copied > before
    }

    /** Post addresses from the pages of posts, following "Older posts" from [startHtml]. */
    private suspend fun indexPages(siteUrl: String, startHtml: String, into: MutableSet<String>) {
        var index = SiteArticles.index(startHtml, siteUrl)
        var pages = 1
        val seen = hashSetOf(siteUrl)
        while (true) {
            into += index.posts
            val next = index.next ?: break
            if (!sameSite(next, siteUrl) || !seen.add(next) || pages >= SiteLimits.MAX_INDEX_PAGES) break
            val text = try {
                request(next) { it == null || "html" in it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: break
            index = SiteArticles.index(text.text, text.url)
            pages++
        }
    }

    /**
     * The section of the site the addresses share, whole segments up to the first date part: "/@name/", "/blog/",
     * "/p/", or "/" (posts by date, "/2019/05/…", are the whole site).
     */
    internal fun commonPath(urls: List<String>): String {
        val paths = urls.mapNotNull { u -> u.toHttpUrlOrNull()?.encodedPathSegments?.filter { it.isNotEmpty() }?.dropLast(1) }
        if (paths.isEmpty()) return "/"
        var shared = paths.first()
        for (p in paths.drop(1)) shared = shared.zip(p).takeWhile { (a, b) -> a == b }.map { it.first }
        shared = shared.takeWhile { s -> !s.all(Char::isDigit) }
        return if (shared.isEmpty()) "/" else "/" + shared.joinToString("/") + "/"
    }

    /** The blog a post's address belongs to: its site, or its writer's pages there (medium.com/@name). */
    internal fun home(postUrl: String): String? {
        val u = postUrl.toHttpUrlOrNull() ?: return null
        val writer = u.pathSegments.firstOrNull()?.takeIf { it.startsWith("@") && it.length > 1 }
        return "https://${u.host}/" + (writer ?: "")
    }

    /** True when [url] is on the same site as [siteUrl] (www. or not). */
    private fun sameSite(url: String, siteUrl: String): Boolean =
        url.toHttpUrlOrNull()?.host?.removePrefix("www.") == siteUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")

    private suspend fun request(url: String, accept: (String?) -> Boolean = { true }): WebText? {
        if (requests++ > 0) pause(SiteLimits.PAGE_PAUSE_MS)
        return web.get(url, SiteLimits.MAX_PAGE_BYTES, accept)
    }

    private suspend fun <T> requestStream(url: String, read: (WebStream) -> T): T? {
        if (requests++ > 0) pause(SiteLimits.PAGE_PAUSE_MS)
        return web.stream(url, { true }, read)
    }

    /**
     * The posts found so far within the limits; each post (and section) once. [cut]: the reading stopped before the
     * site's last post (a limit, a page that didn't answer, or the traveller's Stop), so copying again later finds more;
     * [stopped]: it ended early, not at a limit. Reblogs are counted and left out.
     */
    private class Found(
        private val isCopied: suspend (SitePost) -> Boolean,
        private val onProgress: (Int) -> Unit,
        private val stop: () -> Boolean,
    ) {
        val posts = ArrayList<SitePost>()
        private val keys = HashSet<String>()
        var copied = 0
        var reblogs = 0
        var cut = false
        var stopped = false
            private set
        private var pictures = 0
        val seen: Int get() = keys.size

        /** True once nothing more should be read: a limit, or Stop. */
        val done: Boolean get() {
            if (!cut && stop()) stopEarly()
            return cut
        }

        fun stopEarly() {
            cut = true
            stopped = true
        }

        /** True when the post at [url] was seen in this read or copied before (counted then, not fetched again). */
        suspend fun knows(url: String): Boolean {
            val stub = SitePost(url, url, "", null, emptyList())
            if (SiteNotes.entryId(stub) in keys) return true
            if (isCopied(stub)) {
                keys += SiteNotes.entryId(stub)
                copied++
                onProgress(posts.size + copied)
                return true
            }
            return false
        }

        /** False once a limit is reached: nothing more to read. */
        suspend fun add(list: List<SitePost>): Boolean {
            for (p in list) {
                if (!keys.add(SiteNotes.entryId(p))) continue
                if (p.blocks.isEmpty() && p.title.isBlank()) continue
                if (p.reblog) {
                    reblogs++
                    continue
                }
                if (isCopied(p)) {
                    copied++
                    continue
                }
                if (posts.size >= SiteLimits.MAX_POSTS || pictures + p.pictures.size > SiteLimits.MAX_PICTURES) {
                    cut = true
                    break
                }
                posts += p
                pictures += p.pictures.size
            }
            onProgress(posts.size + copied)
            return !done
        }
    }

    companion object {
        const val NOT_AN_ADDRESS = "Enter a web address, like myblog.tumblr.com."
        const val COULD_NOT_READ = "This site couldn't be read."
        const val NO_CONNECTION = "This site couldn't be read. Check the address and your connection."
        const val NO_POSTS = "No posts found at this address."
        const val ONLY_REBLOGS = "This blog only has reblogs: other people's posts aren't copied."
        const val A_REBLOG = "This post is a reblog: other people's posts aren't copied."
        const val TUMBLR_PRIVATE = "This Tumblr blog couldn't be read. Private blogs can't be copied."
        const val POST_NOT_FOUND = "This post couldn't be read. Check the link."
        const val DOC_NOT_SHARED = "This document isn't shared. In Google Docs: Share › Anyone with the link."
        const val DOC_NOT_FOUND = "This document wasn't found. Check the link."

        /** Feeds named by a page that are tried (a site may name one per category). */
        private const val MAX_NAMED_FEEDS = 3

        /** A feed this long without older pages likely holds only the newest posts. */
        private const val FEED_CAP_HINT = 10
        private val SITEMAPS = listOf("/sitemap.xml", "/sitemap_index.xml", "/wp-sitemap.xml")
        private val NOT_HTML: (String?) -> Boolean = { it == null || "html" !in it }

        /** The address to read, https, or null when [input] isn't a web address. */
        fun address(input: String): String? {
            val t = input.trim().take(SiteLimits.MAX_ADDRESS)
            return LinkUrls.normalize(t)?.let(LinkUrls::forFetch)
        }
    }
}

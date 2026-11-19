package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.links.LinkHttpException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** The reading order (Tumblr, feeds, the page) and paging, against a fake web of synthetic answers. */
class SiteReaderTest {
    /** Answers by address; anything else is a 404. Records every request. */
    private class FakeWeb(val pages: Map<String, Pair<String, String>>, val dynamic: (String) -> Pair<String, String>? = { null }) : SiteWeb {
        val asked = ArrayList<String>()
        override suspend fun get(url: String, maxBytes: Long, accept: (String?) -> Boolean): WebText? {
            asked += url
            val (type, body) = pages[url] ?: dynamic(url) ?: throw LinkHttpException(404)
            if (!accept(type)) return null
            val truncated = body.length > maxBytes
            return WebText(url, type, if (truncated) body.take(maxBytes.toInt()) else body, truncated)
        }
    }

    private val pauses = ArrayList<Long>()
    private fun reader(web: SiteWeb) = SiteReader(web, pause = { pauses += it })

    private fun tumblrPost(n: Int, pictures: Int = 0): String {
        val body = "<p>Post $n.</p>" + (1..pictures).joinToString("") { "<img src=\\\"https://64.media.example.com/p${n}_$it.jpg\\\">" }
        return """{"id":"$n","url":"https:\/\/wanderingfeet.tumblr.com\/post\/$n","type":"regular","unix-timestamp":${1_500_000_000 + n * 3600},"regular-title":"","regular-body":"$body","tags":[]}"""
    }

    private fun tumblrPage(total: Int, from: Int, count: Int, pictures: Int = 0): String =
        """var tumblr_api_read = {"tumblelog":{"title":"Wandering Feet","name":"wanderingfeet"},"posts-start":$from,"posts-total":$total,"posts":[""" +
            (from until minOf(from + count, total)).joinToString(",") { tumblrPost(it + 1, pictures) } + "]};"

    /** Answers the read API for a blog of [total] posts, like Tumblr: [start] and [num] from the address. */
    private fun tumblrBlog(total: Int, pictures: Int = 0, maxNum: Int = 50): (String) -> Pair<String, String>? = { url ->
        val u = url.toHttpUrl()
        if (u.encodedPath == "/api/read/json") {
            val start = u.queryParameter("start")!!.toInt()
            val num = minOf(u.queryParameter("num")!!.toInt(), maxNum)
            "text/javascript" to tumblrPage(total, start, num, pictures)
        } else null
    }

    @Test fun tumblrReadsEveryPage() = runTest {
        val web = FakeWeb(emptyMap(), tumblrBlog(120))
        val progress = ArrayList<Int>()
        val read = reader(web).read("wanderingfeet.tumblr.com", onProgress = { progress += it })
        assertEquals(SiteKind.Tumblr, read.kind)
        assertEquals("Wandering Feet", read.name)
        assertEquals("wanderingfeet.tumblr.com", read.host)
        assertEquals(120, read.posts.size)
        assertFalse(read.more)
        assertEquals(listOf(50, 100, 120), progress)
        assertEquals(
            listOf(0, 50, 100).map { "https://wanderingfeet.tumblr.com/api/read/json?start=$it&num=50" },
            web.asked,
        )
        assertEquals(listOf(SiteLimits.PAGE_PAUSE_MS, SiteLimits.PAGE_PAUSE_MS), pauses)
        assertEquals(Instant.ofEpochSecond(1_500_000_000L + 3600), read.posts.first().at)
    }

    @Test fun alreadyCopiedPostsAreCountedAndDoNotUseTheLimit() = runTest {
        val web = FakeWeb(emptyMap(), tumblrBlog(560))
        val read = reader(web).read("https://www.tumblr.com/wanderingfeet", isCopied = { it.id.toInt() <= 40 })
        assertEquals(40, read.alreadyCopied)
        assertEquals(SiteLimits.MAX_POSTS, read.posts.size)
        assertTrue(read.more)
        assertEquals("41", read.posts.first().id)
    }

    @Test fun picturesLimitStopsBeforeThePostThatWouldPassIt() = runTest {
        val web = FakeWeb(emptyMap(), tumblrBlog(300, pictures = 9))
        val read = reader(web).read("wanderingfeet.tumblr.com")
        assertEquals(SiteLimits.MAX_PICTURES / 9, read.posts.size)
        assertTrue(read.posts.sumOf { it.pictures.size } <= SiteLimits.MAX_PICTURES)
        assertTrue(read.more)
    }

    @Test fun aPageTooLargeIsAskedAgainForFewerPosts() = runTest {
        val bigBody = "x".repeat(SiteLimits.MAX_PAGE_BYTES.toInt() + 10)
        val web = FakeWeb(emptyMap()) { url ->
            val u = url.toHttpUrl()
            when (u.queryParameter("num")) {
                "50" -> "text/javascript" to "var tumblr_api_read = {\"posts\":[\"$bigBody\"]};"
                else -> tumblrBlog(15)(url)
            }
        }
        val read = reader(web).read("wanderingfeet.tumblr.com")
        assertEquals(15, read.posts.size)
        assertEquals(
            listOf(
                "https://wanderingfeet.tumblr.com/api/read/json?start=0&num=50",
                "https://wanderingfeet.tumblr.com/api/read/json?start=0&num=10",
                "https://wanderingfeet.tumblr.com/api/read/json?start=10&num=10",
            ),
            web.asked,
        )
    }

    @Test fun ownDomainWithoutTumblrFindsTheFeedAndPagesThroughWordPress() = runTest {
        val home = "https://wanderingfeet.example.com/"
        val feed = "https://wanderingfeet.example.com/feed/"
        fun rssPage(vararg n: Int) = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Wandering Feet</title><generator>https://wordpress.org/?v=6.9</generator>" +
            n.joinToString("") { "<item><title>Post $it</title><link>https://wanderingfeet.example.com/p/$it/</link><pubDate>Sat, 04 May 2019 08:30:00 +0000</pubDate><description>Text $it</description></item>" } +
            "</channel></rss>"
        val web = FakeWeb(
            mapOf(
                "https://wanderingfeet.example.com/api/read/json?start=0&num=50" to ("text/html" to "<html>Not found</html>"),
                home to ("text/html" to "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/feed/\"></head><body>Home</body></html>"),
                feed to ("application/rss+xml" to rssPage(1, 2)),
                "$feed?paged=2" to ("application/rss+xml" to rssPage(3, 4)),
            ),
        )
        val read = reader(web).read("http://wanderingfeet.example.com")
        assertEquals(SiteKind.Feed, read.kind)
        assertEquals("Wandering Feet", read.name)
        assertEquals(listOf("Post 1", "Post 2", "Post 3", "Post 4"), read.posts.map { it.title })
        assertEquals(listOf("https://wanderingfeet.example.com/api/read/json?start=0&num=50", home, feed, "$feed?paged=2", "$feed?paged=3"), web.asked)
        assertFalse(read.more) // 404 past the last page: that was all
    }

    @Test fun aPageThatDoesNotAnswerLeavesTheRestForLater() = runTest {
        val feed = "https://busy.example.com/feed/"
        val rss = "<rss><channel><title>Busy</title><item><title>One</title><link>https://busy.example.com/1/</link></item></channel></rss>"
        val web = FakeWeb(mapOf(feed to ("application/rss+xml" to rss))) { url -> if ("paged=2" in url) throw LinkHttpException(503) else null }
        val read = reader(web).read(feed)
        assertEquals(1, read.posts.size)
        assertTrue(read.more)
    }

    @Test fun aFeedThatIgnoresThePageNumberStops() = runTest {
        val feed = "https://ghost.example.com/feed/"
        val rss = "<rss><channel><title>G</title><item><title>Only</title><link>https://ghost.example.com/only/</link></item></channel></rss>"
        val web = FakeWeb(mapOf(feed to ("application/rss+xml" to rss), "$feed?paged=2" to ("application/rss+xml" to rss)))
        val read = reader(web).read(feed)
        assertEquals(1, read.posts.size)
        assertEquals(listOf("https://ghost.example.com/api/read/json?start=0&num=50", feed, "$feed?paged=2"), web.asked)
    }

    @Test fun atomNextPages() = runTest {
        val base = "https://wanderingfeet.blogspot.com/feeds/posts/default"
        fun atom(n: Int, next: String?) = "<feed xmlns='http://www.w3.org/2005/Atom'><title>B</title>" +
            (next?.let { "<link rel='next' href='$it'/>" } ?: "") +
            "<entry><title>Entry $n</title><link rel='alternate' href='https://wanderingfeet.blogspot.com/$n.html'/><published>2019-05-0${n}T10:00:00Z</published><content type='html'>Hi $n</content></entry></feed>"
        val web = FakeWeb(
            mapOf(
                base to ("application/atom+xml" to atom(1, "$base?start-index=2")),
                "$base?start-index=2" to ("application/atom+xml" to atom(2, "$base?start-index=3")),
                "$base?start-index=3" to ("application/atom+xml" to atom(3, null)),
            ),
        )
        val read = reader(web).read(base)
        assertEquals(listOf("Entry 1", "Entry 2", "Entry 3"), read.posts.map { it.title })
    }

    @Test fun guessesTheUsualFeedAddressWhenThePageNamesNone() = runTest {
        val web = FakeWeb(
            mapOf(
                "https://hugo.example.com/" to ("text/html" to "<html><body><h1>Home</h1></body></html>"),
                "https://hugo.example.com/index.xml" to ("application/xml" to "<rss><channel><title>Hugo</title><item><title>A</title><link>https://hugo.example.com/a/</link></item></channel></rss>"),
            ),
        )
        val read = reader(web).read("hugo.example.com")
        assertEquals(SiteKind.Feed, read.kind)
        assertEquals(listOf("A"), read.posts.map { it.title })
    }

    @Test fun aPageWithoutFeedIsOnePost() = runTest {
        val page = "https://notes.example.com/2020/03/iceland"
        val web = FakeWeb(mapOf(page to ("text/html" to PAGE)))
        val read = reader(web).read(page)
        assertEquals(SiteKind.Page, read.kind)
        assertEquals("Notes", read.name)
        val post = read.posts.single()
        assertEquals("Ten days in Iceland", post.title)
        assertEquals("https://notes.example.com/2020/03/iceland/", post.url)
        assertEquals(Instant.parse("2020-03-10T09:00:00Z"), post.at)
        assertEquals(listOf("iceland"), post.tags)
        assertEquals(
            listOf(SiteBlock.Text("Wind, then more wind."), SiteBlock.Picture("https://notes.example.com/img/falls.jpg", "Skógafoss"), SiteBlock.Text("Worth it.")),
            post.blocks,
        )
    }

    @Test fun plainReasons() = runTest {
        assertReason(SiteReader.NOT_AN_ADDRESS) { reader(FakeWeb(emptyMap())).read("just some words") }
        assertReason(SiteReader.COULD_NOT_READ) { reader(FakeWeb(emptyMap())).read("missing.example.com") }
        assertReason(SiteReader.NO_POSTS) {
            reader(FakeWeb(mapOf("https://empty.example.com/" to ("text/html" to "<html><body><script>app()</script></body></html>")))).read("empty.example.com")
        }
        assertReason(SiteReader.NO_POSTS) { reader(FakeWeb(emptyMap(), tumblrBlog(0))).read("empty.tumblr.com") }
        assertReason(SiteReader.TUMBLR_PRIVATE) { reader(FakeWeb(emptyMap())).read("https://www.tumblr.com/hidden") }
        assertReason(SiteReader.NO_CONNECTION) {
            reader(FakeWeb(emptyMap()) { throw java.net.UnknownHostException("offline") }).read("wanderingfeet.tumblr.com")
        }
    }

    private suspend fun assertReason(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected: $message")
        } catch (e: SiteReadException) {
            assertEquals(message, e.message)
        }
    }

    private companion object {
        val PAGE = """
            <html><head><title>Ten days in Iceland | Notes</title>
            <meta property="og:site_name" content="Notes">
            <meta property="og:title" content="Ten days in Iceland">
            <meta property="article:published_time" content="2020-03-10T09:00:00+00:00">
            <meta property="article:tag" content="iceland">
            <link rel="canonical" href="https://notes.example.com/2020/03/iceland/">
            </head><body>
            <header><nav><a href="/">Home</a></nav></header>
            <article>
              <h1>Ten days in Iceland</h1>
              <p>Wind, then more wind.</p>
              <figure><img src="/img/falls.jpg"><figcaption>Skógafoss</figcaption></figure>
              <p>Worth it.</p>
              <div class="sharedaddy">Share this</div>
            </article>
            <aside>Popular posts</aside>
            <footer>© Notes</footer>
            </body></html>
        """.trimIndent()
    }
}

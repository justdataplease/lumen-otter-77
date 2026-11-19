package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.links.LinkHttpException
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A link to one post gives that post only (owner, 15 Nov 2026: "I sent a Tumblr article and got all articles, it
 * should get only the one sent"), against a fake web: a Tumblr post, a shared link that passes on to one, an article
 * of a blog with a feed; a blog's home and its pages of posts still give the whole blog.
 */
class SiteOnePostTest {
    private class FakeWeb(val pages: Map<String, Answer>) : SiteWeb {
        data class Answer(val type: String?, val body: String, val finalUrl: String? = null)
        val asked = ArrayList<String>()
        override suspend fun get(url: String, maxBytes: Long, accept: (String?) -> Boolean): WebText? {
            asked += url
            val a = pages[url] ?: throw LinkHttpException(404)
            if (!accept(a.type)) return null
            return WebText(a.finalUrl ?: url, a.type, a.body, false)
        }
    }

    private fun reader(web: SiteWeb) = SiteReader(web, pause = {})

    private val postId = "828009069026721792"

    private fun api(vararg posts: String) =
        "var tumblr_api_read = {\"tumblelog\":{\"title\":\"Wandering Feet\",\"name\":\"wanderingfeet\"},\"posts-start\":0,\"posts-total\":2990,\"posts\":[${posts.joinToString(",")}]};"

    private fun tumblrPost(id: String, extra: String = "") =
        """{"id":"$id","url":"https://wanderingfeet.tumblr.com/post/$id","type":"regular","unix-timestamp":1500000000,"regular-title":"Kyoto at last","regular-body":"<p>Mine.</p>"$extra}"""

    private val one = "https://wanderingfeet.tumblr.com/api/read/json?id=$postId"

    @Test fun aTumblrPostLinkReadsThatPostWithOneCall() = runTest {
        val web = FakeWeb(mapOf(one to FakeWeb.Answer("text/javascript", api(tumblrPost(postId)))))
        for (link in listOf(
            "https://wanderingfeet.tumblr.com/post/$postId/kyoto-at-last",
            "https://www.tumblr.com/wanderingfeet/$postId/kyoto-at-last?source=share",
        )) {
            web.asked.clear()
            val read = reader(web).read(link)
            assertEquals(listOf(postId), read.posts.map { it.id })
            assertEquals(SiteKind.Tumblr, read.kind)
            assertEquals("Wandering Feet", read.name)
            // The blog has 2,990 posts: none of them is "more" of this copy.
            assertFalse(read.more)
            assertEquals("https://wanderingfeet.tumblr.com", read.whole)
            assertEquals(listOf(one), web.asked)
        }
    }

    @Test fun theBlogsAddressStillReadsTheBlog() = runTest {
        val all = "https://wanderingfeet.tumblr.com/api/read/json?start=0&num=50"
        val web = FakeWeb(mapOf(all to FakeWeb.Answer("text/javascript", api(tumblrPost("1"), tumblrPost("2")).replace("2990", "2"))))
        val read = reader(web).read("wanderingfeet.tumblr.com")
        assertEquals(listOf("1", "2"), read.posts.map { it.id })
        assertNull(read.whole)
    }

    @Test fun aSharedLinkThatPassesOnToATumblrPost() = runTest {
        val shared = "https://at.tumblr.com/wanderingfeet/kyoto-at-last/abc123"
        val web = FakeWeb(
            mapOf(
                shared to FakeWeb.Answer("text/html", "<html><body><script>app()</script></body></html>", finalUrl = "https://www.tumblr.com/wanderingfeet/$postId/kyoto-at-last"),
                one to FakeWeb.Answer("text/javascript", api(tumblrPost(postId))),
            ),
        )
        val read = reader(web).read(shared)
        assertEquals(listOf(postId), read.posts.map { it.id })
        assertEquals(listOf(shared, one), web.asked)
    }

    @Test fun aPostAlreadyCopiedIsCountedAndAReblogSaysSo() = runTest {
        val web = FakeWeb(mapOf(one to FakeWeb.Answer("text/javascript", api(tumblrPost(postId)))))
        val again = reader(web).read("https://wanderingfeet.tumblr.com/post/$postId", isCopied = { true })
        assertEquals(1, again.alreadyCopied)
        assertTrue(again.posts.isEmpty())

        val reblog = FakeWeb(mapOf(one to FakeWeb.Answer("text/javascript", api(tumblrPost(postId, ""","reblogged-from-name":"someone","reblogged-root-name":"someone"""")))))
        assertReason(SiteReader.A_REBLOG) { reader(reblog).read("https://wanderingfeet.tumblr.com/post/$postId") }
        assertReason(SiteReader.POST_NOT_FOUND) { reader(FakeWeb(emptyMap())).read("https://wanderingfeet.tumblr.com/post/$postId") }
    }

    private val home = "https://wanderingfeet.example.com/"
    private val feedUrl = "https://wanderingfeet.example.com/feed/"
    private val article = "https://wanderingfeet.example.com/2019/05/porto/"

    private fun rss(vararg n: Int) = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Wandering Feet</title>" +
        n.joinToString("") { "<item><title>Post $it</title><link>https://wanderingfeet.example.com/p/$it/</link><pubDate>Sat, 04 May 2019 08:30:00 +0000</pubDate><description>Text $it</description></item>" } +
        "</channel></rss>"

    private fun page(head: String, body: String) =
        "<html><head><title>Wandering Feet</title><meta property=\"og:site_name\" content=\"Wandering Feet\">" +
            "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"$feedUrl\">$head</head><body>$body</body></html>"

    private val articlePage = page(
        """<meta property="og:title" content="Two days in Porto"><meta property="article:published_time" content="2019-05-04T08:30:00+00:00">""",
        "<article><h1>Two days in Porto</h1><p>Tiles everywhere.</p></article><aside><article><h3><a href=\"/p/1/\">Post 1</a></h3></article></aside>",
    )

    @Test fun anArticlesLinkReadsThatArticleNotTheBlogsFeed() = runTest {
        val web = FakeWeb(mapOf(article to FakeWeb.Answer("text/html", articlePage), feedUrl to FakeWeb.Answer("application/rss+xml", rss(1, 2, 3))))
        val read = reader(web).read(article)
        assertEquals(listOf("Two days in Porto"), read.posts.map { it.title })
        assertEquals(SiteKind.Page, read.kind)
        assertEquals("Wandering Feet", read.name)
        // The whole blog is the feed the article's page names; a page that names none, its site.
        assertEquals(feedUrl, read.whole)
        val bare = FakeWeb(mapOf(article to FakeWeb.Answer("text/html", "<html><head><title>Two days in Porto</title><meta property=\"article:published_time\" content=\"2019-05-04T08:30:00+00:00\"></head><body><article><p>Tiles.</p></article></body></html>")))
        val alone = reader(bare).read(article)
        assertEquals(home, alone.whole)
        // A post's page is titled after the post: the site is then known by its address.
        assertEquals("wanderingfeet.example.com", alone.name)
        // After the one question every unknown site gets (is it a Tumblr blog?), only the article was asked for: not
        // the feed, the sitemap or robots.txt.
        assertEquals(listOf("https://wanderingfeet.example.com/api/read/json?start=0&num=50", article), web.asked)
    }

    @Test fun theHomeAndAPageOfPostsStillReadTheWholeBlog() = runTest {
        val listing = page(
            """<meta property="og:type" content="article">""",
            (1..3).joinToString("") { "<article><h2><a href=\"/p/$it/\">Post $it</a></h2><time datetime=\"2019-05-0${it}T08:00:00Z\">May $it</time></article>" },
        )
        val web = FakeWeb(
            mapOf(
                home to FakeWeb.Answer("text/html", articlePage),
                "https://wanderingfeet.example.com/blog/" to FakeWeb.Answer("text/html", listing),
                feedUrl to FakeWeb.Answer("application/rss+xml", rss(1, 2, 3)),
            ),
        )
        for (address in listOf("wanderingfeet.example.com", "https://wanderingfeet.example.com/blog/")) {
            val read = reader(web).read(address)
            assertEquals(SiteKind.Feed, read.kind)
            assertEquals(3, read.posts.size)
            assertNull(read.whole)
        }
    }

    @Test fun whatMakesAPageOnePost() {
        fun one(url: String, html: String) = SiteArticles.isOnePost(Jsoup.parse(html, url), url)
        val post = "https://x.example.com/2019/05/porto/"
        assertTrue(one(post, """<meta property="article:published_time" content="2019-05-04T08:30:00Z"><p>Text</p>"""))
        assertTrue(one(post, """<script type="application/ld+json">{"@type":"BlogPosting","datePublished":"2019-05-04"}</script><p>Text</p>"""))
        assertTrue(one(post, """<meta property="og:type" content="article"><article><p>Text</p></article>"""))
        assertTrue(one(post, """<article><time datetime="2019-05-04">4 May</time><p>Text</p><article class="comment">Nice</article></article>"""))
        // The home, a tag's or a category's page are never one post, whatever they say.
        assertFalse(one("https://x.example.com/", """<meta property="article:published_time" content="2019-05-04T08:30:00Z">"""))
        assertFalse(one("https://x.example.com/tag/porto/", """<meta property="og:type" content="article"><article><p>Text</p></article>"""))
        // Several articles side by side are a page of posts; a page that says nothing is not known to be a post.
        assertFalse(one("https://x.example.com/2019/05/", """<meta property="og:type" content="article"><article><time datetime="2019-05-04"></time></article><article><time datetime="2019-05-05"></time></article>"""))
        assertFalse(one("https://x.example.com/blog/", "<main><p>Welcome</p></main>"))
    }

    @Test fun theBlogAPostBelongsTo() {
        val r = reader(FakeWeb(emptyMap()))
        assertEquals("https://x.example.com/", r.home("https://x.example.com/2019/05/porto/"))
        assertEquals("https://medium.com/@wanderer", r.home("https://medium.com/@wanderer/two-days-in-porto-1a2b3c"))
    }

    private suspend fun assertReason(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected: $message")
        } catch (e: SiteReadException) {
            assertEquals(message, e.message)
        }
    }
}

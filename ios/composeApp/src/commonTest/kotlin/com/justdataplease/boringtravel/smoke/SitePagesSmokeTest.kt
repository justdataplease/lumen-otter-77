package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.compat.Instant
import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.site.Feeds
import com.justdataplease.boringtravel.site.GoogleDocs
import com.justdataplease.boringtravel.site.HtmlBlocks
import com.justdataplease.boringtravel.site.PageReader
import com.justdataplease.boringtravel.site.SiteBlock
import com.justdataplease.boringtravel.site.SiteDates
import com.justdataplease.boringtravel.site.TumblrApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Copy from a link's readers, read in from the Android app (site/) and run on Ksoup: a post's HTML as note blocks, a
 * page as one post, RSS and Atom feeds (Ksoup's XML parser), their dates (compat's java.time), Tumblr's read API and a
 * Google Doc's export. Made-up pages of a made-up blog.
 */
class SitePagesSmokeTest {
    private val base = "https://slowroads.example.com/2019/05/naxos/"

    // ---- HtmlBlocks

    @Test fun wordsPicturesEmojiAndLinksInPageOrder() {
        val html = "<p>Sunset at the <strong>Portara</strong> <img class=\"emoji\" src=\"https://s.example.org/images/core/emoji/15/svg/1f305.svg\" alt=\"🌅\"></p>\n" +
            "<figure><img src=\"https://cdn.slowroads.example.com/portara-640.jpg\" srcset=\"https://cdn.slowroads.example.com/portara-320.jpg 320w, https://cdn.slowroads.example.com/portara-1600.jpg 1600w, https://cdn.slowroads.example.com/portara-640.jpg 640w\"><figcaption>The gate at dusk</figcaption></figure>\n" +
            "<img src=\"https://stats.wordpress.com/g.gif?blog=1\" width=\"1\" height=\"1\">\n" +
            "<p><em>Next:</em> <a href=\"/2019/05/paros/\">Paros</a> &amp; more</p>"
        assertEquals(
            listOf(
                SiteBlock.Text("Sunset at the <b>Portara</b> 🌅"),
                SiteBlock.Picture("https://cdn.slowroads.example.com/portara-1600.jpg", "The gate at dusk"),
                SiteBlock.Text("<i>Next:</i> <a href=\"https://slowroads.example.com/2019/05/paros/\">Paros</a> &amp; more"),
            ),
            HtmlBlocks.parse(html, base),
        )
    }

    @Test fun headingsQuotesListsDividersAndEmbeds() {
        val html = "<h2>Day 2 · Hydra</h2><blockquote><p>No cars.</p><p>Only donkeys.</p></blockquote>" +
            "<ol><li>Port</li><li>Monastery</li></ol><hr>" +
            "<iframe src=\"https://www.youtube.com/embed/xyz789?feature=oembed\"></iframe><script>var x = 1;</script>" +
            "<pre>a  b\nc</pre>"
        assertEquals(
            listOf(
                SiteBlock.Heading("Day 2 · Hydra", 2),
                SiteBlock.Quote("No cars.<br><br>Only donkeys."),
                SiteBlock.Text("Port", ListStyle.Number),
                SiteBlock.Text("Monastery", ListStyle.Number),
                SiteBlock.Divider,
                SiteBlock.Text("<a href=\"https://www.youtube.com/watch?v=xyz789\">https://www.youtube.com/watch?v=xyz789</a>"),
                SiteBlock.Text("a  b<br>c"),
            ),
            HtmlBlocks.parse(html, base),
        )
        assertTrue(HtmlBlocks.parse("", base).isEmpty())
    }

    @Test fun linkWrappersGiveTheirDestination() {
        assertEquals("https://slowroads.example.com/y", HtmlBlocks.link("https://href.li/?https://slowroads.example.com/y", base))
        assertEquals("https://slowroads.example.com/x?a=1", HtmlBlocks.link("https://t.umblr.com/redirect?z=https%3A%2F%2Fslowroads.example.com%2Fx%3Fa%3D1&t=1", base))
        assertEquals("https://slowroads.example.com/z", HtmlBlocks.link("https://www.google.com/url?q=https://slowroads.example.com/z&sa=D", base))
        assertEquals("https://slowroads.example.com/2019/05/img/a.jpg", HtmlBlocks.link("../img/a.jpg", base))
        assertEquals("mailto:hello@example.org", HtmlBlocks.link("mailto:hello@example.org", base))
        assertNull(HtmlBlocks.link("#top", base))
        assertNull(HtmlBlocks.link("javascript:alert(1)", base))
    }

    // ---- PageReader

    private val article = """
        <html><head><title>Naxos at last | Slow Roads</title>
        <meta property="og:site_name" content="Slow Roads">
        <meta property="og:title" content="Naxos at last">
        <meta property="article:published_time" content="2019-05-04T08:30:00+03:00">
        <meta property="article:tag" content="greece"><meta property="article:tag" content="islands">
        <link rel="canonical" href="https://slowroads.example.com/2019/05/naxos/">
        </head><body>
        <nav><a href="/">Home</a></nav><header><h1>Slow Roads</h1></header>
        <article><h1>Naxos at last</h1><p>The ferry was <b>late</b>.</p><figure><img src="/img/portara.jpg"><figcaption>Portara</figcaption></figure></article>
        <aside>Popular posts</aside><footer>Slow Roads, 2019</footer>
        </body></html>
    """.trimIndent()

    @Test fun anArticleIsOnePostWithoutItsMenus() {
        val url = "https://slowroads.example.com/2019/05/naxos/?utm_source=feed"
        val post = assertNotNull(PageReader.parse(article, url))
        assertEquals("Naxos at last", post.title)
        assertEquals("https://slowroads.example.com/2019/05/naxos/", post.url)
        assertEquals(Instant.parse("2019-05-04T05:30:00Z"), post.at)
        assertEquals(listOf("greece", "islands"), post.tags)
        assertEquals(
            listOf(SiteBlock.Text("The ferry was <b>late</b>."), SiteBlock.Picture("https://slowroads.example.com/img/portara.jpg", "Portara")),
            post.blocks,
        )
        assertEquals(post, PageReader.article(article, url))
        assertEquals(post, PageReader.onePost(article, url))
        assertEquals("Slow Roads", PageReader.siteName(article))
        assertNull(PageReader.siteName("<html><head><title>Only a title</title></head></html>", orTitle = false))
        assertNull(PageReader.article("<html><body><p>No date here.</p></body></html>", url))
    }

    // ---- Feeds

    @Test fun anRssFeedWithFullTextPicturesAndCategories() {
        val feedUrl = "https://slowroads.example.com/feed/"
        val feed = assertNotNull(Feeds.parse(Samples.RSS, feedUrl))
        assertEquals("Slow Roads & Ferries", feed.title)
        assertTrue(Feeds.isWordPress(feed, feedUrl))
        assertNull(feed.next)
        assertEquals(2, feed.posts.size)
        val first = feed.posts[0]
        assertEquals("Naxos at last", first.title)
        assertEquals("https://slowroads.example.com/2019/05/naxos/", first.url)
        assertEquals(Instant.parse("2019-05-04T08:30:00Z"), first.at)
        assertEquals(listOf("Greece", "Islands"), first.tags)
        assertEquals(
            listOf(
                SiteBlock.Text("The ferry was <b>late</b>."),
                SiteBlock.Picture("https://slowroads.example.com/wp-content/uploads/portara.jpg", "Portara"),
                SiteBlock.Picture("https://slowroads.example.com/wp-content/uploads/harbour.jpg"),
            ),
            first.blocks,
        )
        val second = feed.posts[1]
        assertEquals("Paros ’" + "19", second.title)
        assertEquals("https://slowroads.example.com/?p=77", second.url)
        assertEquals(Instant.parse("2019-05-07T18:00:00Z"), second.at)
        assertEquals(listOf(SiteBlock.Text("Short one.")), second.blocks)
    }

    @Test fun anAtomFeedWithANextPage() {
        val feedUrl = "https://slowroads.example.com/feeds/posts/default"
        val feed = assertNotNull(Feeds.parse(Samples.ATOM, feedUrl))
        assertEquals("Slow Roads (Atom)", feed.title)
        assertEquals("https://slowroads.example.com/feeds/posts/default?start-index=26&max-results=25", feed.next)
        assertFalse(Feeds.isWordPress(feed, feedUrl))
        val post = feed.posts.single()
        assertEquals("Hydra without cars", post.title)
        assertEquals("https://slowroads.example.com/2019/05/hydra.html", post.url)
        assertEquals(Instant.parse("2019-05-09T18:00:00Z"), post.at)
        assertEquals(listOf("Greece"), post.tags)
        assertEquals(listOf(SiteBlock.Text("Donkeys at <i>dawn</i>.")), post.blocks)
    }

    @Test fun feedsAreFoundGuessedAndPaged() {
        assertTrue(Feeds.isFeed("﻿<?xml version=\"1.0\"?>\n<rss version=\"2.0\">"))
        assertTrue(Feeds.isFeed("  <feed xmlns='http://www.w3.org/2005/Atom'>"))
        assertFalse(Feeds.isFeed("<!DOCTYPE html><html><body><rss></rss></body></html>"))
        assertNull(Feeds.parse("<html><body>Not found</body></html>", "https://slowroads.example.com/feed/"))
        val page = """
            <html><head>
            <link rel="alternate" type="application/rss+xml" title="Slow Roads Feed" href="/feed/">
            <link rel="alternate" type="application/rss+xml" title="Slow Roads Comments Feed" href="/comments/feed/">
            <link rel="alternate" type="application/atom+xml" href="https://slowroads.example.com/atom.xml">
            <link rel="stylesheet" href="/style.css">
            </head><body></body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://slowroads.example.com/feed/", "https://slowroads.example.com/atom.xml"),
            Feeds.discover(page, "https://slowroads.example.com/2019/05/naxos/"),
        )
        assertEquals("https://slowroads.example.com/feed/", Feeds.guesses("https://slowroads.example.com/").first())
        assertEquals("https://medium.com/feed/@slowroads", Feeds.guesses("https://medium.com/@slowroads/naxos-1a2b").first())
        assertEquals("https://slowroads.example.com/feed/?paged=2", Feeds.pagedUrl("https://slowroads.example.com/feed/", 2))
    }

    @Test fun datesAsFeedsWriteThem() {
        assertEquals(Instant.parse("2026-11-11T14:00:00Z"), SiteDates.parse("Wed, 11 Nov 2026 14:00:00 +0000"))
        assertEquals(Instant.parse("2027-09-10T15:00:00Z"), SiteDates.parse("Fri, 10 Sep 2027 08:00 PDT"))
        assertEquals(Instant.parse("2027-09-11T07:15:00Z"), SiteDates.parse("11 Sep 2027 09:15 +0200"))
        assertEquals(Instant.parse("2016-11-01T13:00:00Z"), SiteDates.parse("2016-11-01T06:00:00.000-07:00"))
        assertEquals(Instant.parse("2019-05-04T08:30:00Z"), SiteDates.parse("2019-05-04T08:30:00"))
        assertEquals(Instant.parse("2019-05-04T12:00:00Z"), SiteDates.parse("  2019-05-04 "))
        assertNull(SiteDates.parse("Thu, 11 Nov 2026 14:00:00 +0000")) // the weekday doesn't match the date
        assertNull(SiteDates.parse("someday"))
        assertNull(SiteDates.parse(null))
    }

    // ---- Tumblr

    @Test fun tumblrBlogAddresses() {
        assertEquals(TumblrApi.Blog("https://slowroads.tumblr.com", null, true), TumblrApi.blog("https://slowroads.tumblr.com/"))
        assertEquals(TumblrApi.Blog("https://slowroads.tumblr.com", "greek islands", true), TumblrApi.blog("https://slowroads.tumblr.com/tagged/greek-islands"))
        assertEquals(
            TumblrApi.Blog("https://slowroads.tumblr.com", null, true, post = "712345678901234567"),
            TumblrApi.blog("https://www.tumblr.com/slowroads/712345678901234567/naxos-at-last"),
        )
        assertEquals(TumblrApi.Blog("https://slowroads.example.com", null, false), TumblrApi.blog("https://slowroads.example.com/archive"))
        assertNull(TumblrApi.blog("https://www.tumblr.com/explore/trending"))
        assertNull(TumblrApi.blog("https://64.media.tumblr.com/abc/tumblr_x_1280.jpg"))
        assertEquals(
            "https://slowroads.tumblr.com/api/read/json?start=50&num=50&tagged=greek%20islands",
            TumblrApi.pageUrl(TumblrApi.Blog("https://slowroads.tumblr.com", "greek islands", true), 50),
        )
    }

    @Test fun tumblrsReadApiAnswer() {
        val page = assertNotNull(TumblrApi.parse(Samples.TUMBLR))
        assertEquals("Slow Roads & Ferries", page.title)
        assertEquals(3, page.total)
        assertEquals(3, page.count)
        val byId = page.posts.associateBy { it.id }
        val text = byId.getValue("7001")
        assertEquals("Naxos at last", text.title)
        assertEquals(Instant.ofEpochSecond(1557000000), text.at)
        assertEquals("2019-05-04T20:00:00Z", text.at.toString())
        assertEquals(listOf("greece", "naxos"), text.tags)
        assertEquals(listOf(SiteBlock.Text("The ferry was <b>late</b>.")), text.blocks)
        assertFalse(text.reblog)
        val photo = byId.getValue("7002")
        assertEquals(Instant.parse("2019-05-06T10:00:00Z"), photo.at)
        assertEquals(
            listOf(SiteBlock.Picture("https://64.media.example.com/portara_1280.jpg"), SiteBlock.Text("Portara at <i>dusk</i>.")),
            photo.blocks,
        )
        val quote = byId.getValue("7003")
        assertEquals(listOf(SiteBlock.Quote("Slow is a speed too."), SiteBlock.Text("A sign in Halki")), quote.blocks)
        assertTrue(quote.reblog)
        assertNull(TumblrApi.parse("<!doctype html><html><body>Not found</body></html>"))
        assertNull(TumblrApi.parse("var tumblr_api_read = {\"tumblelog\":{\"title\":\"Cut"))
    }

    // ---- Google Docs

    @Test fun googleDocsAddressesAndTitles() {
        val id = Samples.DOC_ID
        val doc = GoogleDocs.Address.Doc(id)
        assertEquals(doc, GoogleDocs.address("https://docs.google.com/document/d/$id/edit?usp=sharing"))
        assertEquals(doc, GoogleDocs.address("https://docs.google.com/document/u/0/d/$id/edit#heading=h.x"))
        assertEquals(GoogleDocs.Address.Published("2PACX-1vAbCdEfGhIjKlMnOpQrStUv"), GoogleDocs.address("https://docs.google.com/document/d/e/2PACX-1vAbCdEfGhIjKlMnOpQrStUv/pub"))
        assertNull(GoogleDocs.address("https://docs.google.com/spreadsheets/d/$id/edit"))
        assertNull(GoogleDocs.address("https://docs.google.com/document/d/short/edit"))
        assertEquals("https://docs.google.com/document/d/$id/export?format=html", GoogleDocs.exportUrl(id))
        assertEquals("https://docs.google.com/document/d/$id", GoogleDocs.canonical(doc))
        assertTrue(GoogleDocs.isSignIn("https://accounts.google.com/v3/signin/identifier?continue=x"))
        assertFalse(GoogleDocs.isSignIn("https://docs.google.com/document/d/$id/export?format=html"))
        assertEquals("Island hopping", GoogleDocs.titleFromDisposition("attachment; filename=\"Island_hopping.docx.html\""))
        assertEquals(
            "Νάξος 2019",
            GoogleDocs.titleFromDisposition("attachment; filename=\"Naxos.html\"; filename*=UTF-8''%CE%9D%CE%AC%CE%BE%CE%BF%CF%82%202019.html"),
        )
        assertNull(GoogleDocs.titleFromDisposition(null))
        assertNull(GoogleDocs.titleFromDisposition("  "))
    }

    @Test fun anExportedDocIsOnePostWithItsFormatsAndWithoutComments() {
        val html = """
            <html><head><style type="text/css">.c1{font-weight:700}.c2{font-style:italic}.title{font-size:26pt}</style></head><body class="doc-content">
            <p class="title"><span>Island hopping</span></p>
            <h2 class="c3"><span>Day 1 · Naxos</span></h2>
            <p class="c0"><span class="c1">Ferry</span><span> from Piraeus at </span><span class="c2">dawn</span><sup><a href="#cmnt1" id="cmnt_ref1">[a]</a></sup></p>
            <div><p class="c0"><a href="#cmnt_ref1" id="cmnt1">[a]</a><span>Check the time</span></p></div>
            </body></html>
        """.trimIndent()
        val url = GoogleDocs.canonical(GoogleDocs.Address.Doc(Samples.DOC_ID))
        val post = assertNotNull(GoogleDocs.post(html, url, "Fallback title"))
        assertEquals("Island hopping", post.title)
        assertEquals(url, post.url)
        assertNull(post.at)
        assertEquals(
            listOf(SiteBlock.Heading("Day 1 · Naxos", 2), SiteBlock.Text("<b>Ferry</b> from Piraeus at <i>dawn</i>")),
            post.blocks,
        )
        assertEquals("Island notes", GoogleDocs.pageTitle("<html><head><title>Island notes</title></head><body></body></html>"))
    }
}

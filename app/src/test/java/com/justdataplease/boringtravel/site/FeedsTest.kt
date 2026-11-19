package com.justdataplease.boringtravel.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** RSS and Atom on synthetic feeds written in the shapes WordPress and Blogger send (checked on 2026-11-12). */
class FeedsTest {
    @Test fun wordpressRss() {
        val feed = Feeds.parse(RSS, "https://wanderingfeet.example.com/feed/")!!
        assertEquals("Wandering Feet", feed.title)
        assertTrue(Feeds.isWordPress(feed, "https://wanderingfeet.example.com/feed/"))
        assertNull(feed.next)
        assertEquals(2, feed.posts.size)
        val first = feed.posts[0]
        assertEquals("Lisbon’s trams & tiles", first.title)
        assertEquals("https://wanderingfeet.example.com/2019/05/lisbon/", first.url)
        assertEquals(Instant.parse("2019-05-04T08:30:00Z"), first.at)
        assertEquals(listOf("Portugal", "Lisbon"), first.tags)
        assertEquals(
            listOf(
                SiteBlock.Text("Tram <b>28</b> up the hill."),
                SiteBlock.Picture("https://wanderingfeet.example.com/wp-content/uploads/tram.jpg", "Tram 28"),
                SiteBlock.Text("Then tiles everywhere."),
                SiteBlock.Picture("https://wanderingfeet.example.com/wp-content/uploads/extra.jpg"),
            ),
            first.blocks,
        )
        // Without content:encoded, the description is the text.
        assertEquals(listOf(SiteBlock.Text("Short one.")), feed.posts[1].blocks)
        assertEquals("https://wanderingfeet.example.com/?p=42", feed.posts[1].url)
    }

    @Test fun bloggerAtomWithNextPage() {
        val feed = Feeds.parse(ATOM, "https://wanderingfeet.blogspot.com/feeds/posts/default")!!
        assertEquals("Wandering Feet (Blogger)", feed.title)
        assertEquals("https://wanderingfeet.blogspot.com/feeds/posts/default?start-index=26&max-results=25", feed.next)
        assertFalse(Feeds.isWordPress(feed, "https://wanderingfeet.blogspot.com/feeds/posts/default"))
        val post = feed.posts.single { it.title == "Porto by the river" }
        assertEquals("https://wanderingfeet.blogspot.com/2019/05/porto.html", post.url)
        assertEquals(Instant.parse("2019-05-09T18:00:00Z"), post.at)
        assertEquals(listOf("Portugal"), post.tags)
        assertEquals(
            listOf(SiteBlock.Text("Port wine at <i>sunset</i>."), SiteBlock.Picture("https://blogger.example.com/s1600/porto.jpg")),
            post.blocks,
        )
        val xhtml = feed.posts.single { it.title == "Inline XHTML" }
        assertEquals(listOf(SiteBlock.Text("Written <b>inline</b>.")), xhtml.blocks)
    }

    @Test fun rss1() {
        val feed = Feeds.parse(RDF, "https://old.example.com/index.rdf")!!
        assertEquals(1, feed.posts.size)
        assertEquals("https://old.example.com/2005/01/first.html", feed.posts[0].url)
        assertEquals(Instant.parse("2005-01-02T10:00:00Z"), feed.posts[0].at)
    }

    @Test fun notAFeed() {
        assertNull(Feeds.parse("<!DOCTYPE html><html><head><title>x</title></head><body><rss></rss></body></html>", "https://x.example.com/"))
        assertFalse(Feeds.isFeed("<html><body>Not found</body></html>"))
        assertTrue(Feeds.isFeed("\uFEFF<?xml version=\"1.0\"?>\n<rss version=\"2.0\">"))
    }

    @Test fun feedsNamedByAPage() {
        val html = """
            <html><head>
            <link rel="alternate" type="application/rss+xml" title="Wandering Feet » Feed" href="https://wanderingfeet.example.com/feed/">
            <link rel="alternate" type="application/rss+xml" title="Wandering Feet » Comments Feed" href="https://wanderingfeet.example.com/comments/feed/">
            <link rel="alternate" type="application/atom+xml" href="/atom.xml">
            <link rel="alternate" type="application/json+oembed" href="/oembed">
            <link rel="stylesheet" href="/style.css">
            </head><body></body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://wanderingfeet.example.com/feed/", "https://wanderingfeet.example.com/atom.xml"),
            Feeds.discover(html, "https://wanderingfeet.example.com/2019/05/lisbon/"),
        )
        assertEquals(
            listOf(
                "https://wanderingfeet.example.com/blog?format=rss", "https://wanderingfeet.example.com/blog/blog-feed.xml",
                "https://wanderingfeet.example.com/feed/", "https://wanderingfeet.example.com/rss/",
            ),
            Feeds.guesses("https://wanderingfeet.example.com/blog?c=1").take(4),
        )
        assertEquals("https://medium.com/feed/@wanderer", Feeds.guesses("https://medium.com/@wanderer/some-post-1a2b").first())
        assertEquals("https://wanderer.medium.com/feed", Feeds.guesses("https://wanderer.medium.com/").first())
        assertEquals("https://hugo.example.com/feed/", Feeds.guesses("https://hugo.example.com/").first())
        assertEquals("https://wanderingfeet.example.com/feed/?paged=3", Feeds.pagedUrl("https://wanderingfeet.example.com/feed/", 3))
    }

    @Test fun dates() {
        assertEquals(Instant.parse("2026-11-11T14:00:00Z"), SiteDates.parse("Wed, 11 Nov 2026 14:00:00 +0000"))
        assertEquals(Instant.parse("2026-11-10T18:42:18Z"), SiteDates.parse("Tue, 10 Nov 2026 18:42:18 GMT"))
        assertEquals(Instant.parse("2016-11-01T13:00:00Z"), SiteDates.parse("2016-11-01T06:00:00.000-07:00"))
        assertEquals(Instant.parse("2019-05-04T08:30:00Z"), SiteDates.parse("2019-05-04T08:30:00Z"))
        assertEquals(Instant.parse("2019-05-04T08:30:00Z"), SiteDates.parse("2019-05-04T08:30:00"))
        assertEquals(Instant.parse("2019-05-04T12:00:00Z"), SiteDates.parse("2019-05-04"))
        assertNull(SiteDates.parse("someday"))
        assertNull(SiteDates.parse(null))
    }

    companion object {
        val RSS = """
            <?xml version="1.0" encoding="UTF-8"?><rss version="2.0"
                xmlns:content="http://purl.org/rss/1.0/modules/content/"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:atom="http://www.w3.org/2005/Atom"
                xmlns:media="http://search.yahoo.com/mrss/">
            <channel>
                <title>Wandering Feet</title>
                <atom:link href="https://wanderingfeet.example.com/feed/" rel="self" type="application/rss+xml" />
                <link>https://wanderingfeet.example.com</link>
                <generator>https://wordpress.org/?v=6.9</generator>
                <item>
                    <title>Lisbon&#8217;s trams &amp; tiles</title>
                    <link>https://wanderingfeet.example.com/2019/05/lisbon/</link>
                    <dc:creator><![CDATA[Ana]]></dc:creator>
                    <pubDate>Sat, 04 May 2019 08:30:00 +0000</pubDate>
                    <category><![CDATA[Portugal]]></category>
                    <category><![CDATA[Lisbon]]></category>
                    <guid isPermaLink="false">https://wanderingfeet.example.com/?p=41</guid>
                    <description><![CDATA[Tram 28 up the hill [&#8230;]]]></description>
                    <content:encoded><![CDATA[
            <p>Tram <strong>28</strong> up the hill.</p>
            <figure class="wp-block-image size-large"><img src="https://wanderingfeet.example.com/wp-content/uploads/tram-1024x683.jpg" srcset="https://wanderingfeet.example.com/wp-content/uploads/tram-300x200.jpg 300w, https://wanderingfeet.example.com/wp-content/uploads/tram.jpg 2048w" /><figcaption>Tram 28</figcaption></figure>
            <p>Then tiles everywhere.</p>
            <img src="https://pixel.wp.com/b.gif?host=x" width="1" height="1" />
                    ]]></content:encoded>
                    <media:content url="https://wanderingfeet.example.com/wp-content/uploads/extra.jpg" medium="image" />
                    <media:content url="https://wanderingfeet.example.com/wp-content/uploads/tram.jpg" medium="image" />
                </item>
                <item>
                    <title>Short</title>
                    <guid>https://wanderingfeet.example.com/?p=42</guid>
                    <pubDate>Sun, 05 May 2019 09:00:00 GMT</pubDate>
                    <description>&lt;p&gt;Short one.&lt;/p&gt;</description>
                </item>
            </channel>
            </rss>
        """.trimIndent()

        val ATOM = """
            <?xml version='1.0' encoding='UTF-8'?><feed xmlns='http://www.w3.org/2005/Atom' xmlns:openSearch='http://a9.com/-/spec/opensearchrss/1.0/'>
            <id>tag:blogger.com,1999:blog-1</id>
            <title type='text'>Wandering Feet (Blogger)</title>
            <link rel='self' type='application/atom+xml' href='https://wanderingfeet.blogspot.com/feeds/posts/default'/>
            <link rel='next' type='application/atom+xml' href='https://wanderingfeet.blogspot.com/feeds/posts/default?start-index=26&amp;max-results=25'/>
            <generator version='7.00' uri='http://www.blogger.com'>Blogger</generator>
            <openSearch:totalResults>40</openSearch:totalResults>
            <entry>
              <id>tag:blogger.com,1999:blog-1.post-2</id>
              <published>2019-05-09T20:00:00.000+02:00</published>
              <updated>2019-05-10T08:00:00.000+02:00</updated>
              <category scheme='http://www.blogger.com/atom/ns#' term='Portugal'/>
              <title type='text'>Porto by the river</title>
              <content type='html'>&lt;div&gt;Port wine at &lt;i&gt;sunset&lt;/i&gt;.&lt;/div&gt;&lt;a href="https://blogger.example.com/s1600/porto.jpg"&gt;&lt;img src="https://blogger.example.com/s320/porto.jpg" border="0"&gt;&lt;/a&gt;</content>
              <link rel='replies' type='text/html' href='https://wanderingfeet.blogspot.com/2019/05/porto.html#comment-form'/>
              <link rel='edit' type='application/atom+xml' href='https://www.blogger.com/feeds/1/posts/default/2'/>
              <link rel='alternate' type='text/html' href='https://wanderingfeet.blogspot.com/2019/05/porto.html' title='Porto by the river'/>
            </entry>
            <entry>
              <id>tag:blogger.com,1999:blog-1.post-3</id>
              <updated>2019-05-11T08:00:00Z</updated>
              <title>Inline XHTML</title>
              <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Written <b>inline</b>.</p></div></content>
              <link href='https://wanderingfeet.blogspot.com/2019/05/inline.html'/>
            </entry>
            </feed>
        """.trimIndent()

        val RDF = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://old.example.com/"><title>Old blog</title><link>https://old.example.com/</link></channel>
              <item rdf:about="https://old.example.com/2005/01/first.html">
                <title>First</title><link>https://old.example.com/2005/01/first.html</link>
                <description>Hello.</description><dc:date>2005-01-02T10:00:00Z</dc:date>
              </item>
            </rdf:RDF>
        """.trimIndent()
    }
}

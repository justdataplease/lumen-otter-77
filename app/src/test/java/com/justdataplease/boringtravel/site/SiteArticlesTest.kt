package com.justdataplease.boringtravel.site

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Sitemaps, robots.txt, pages of posts and dated articles, on synthetic pages. */
class SiteArticlesTest {
    @Test fun sitemapsAndTheirIndex() {
        val index = """<?xml version="1.0" encoding="UTF-8"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
            <sitemap><loc>https://notes.example.com/page-sitemap.xml</loc></sitemap>
            <sitemap><loc>https://notes.example.com/post-sitemap.xml</loc></sitemap>
            <sitemap><loc>https://notes.example.com/category-sitemap.xml</loc></sitemap>
            <sitemap><loc>https://notes.example.com/misc-sitemap.xml</loc></sitemap></sitemapindex>"""
        assertEquals(
            SiteArticles.Sitemap(emptyList(), listOf("https://notes.example.com/post-sitemap.xml", "https://notes.example.com/misc-sitemap.xml")),
            SiteArticles.sitemap(index, "https://notes.example.com/sitemap_index.xml"),
        )
        val urls = """<?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
            <url><loc>https://notes.example.com/2020/03/iceland/</loc><lastmod>2020-03-11</lastmod></url>
            <url><loc> https://notes.example.com/about/ </loc></url></urlset>"""
        assertEquals(listOf("https://notes.example.com/2020/03/iceland/", "https://notes.example.com/about/"), SiteArticles.sitemap(urls, "https://notes.example.com/sitemap.xml")!!.pages)
        assertNull(SiteArticles.sitemap("<html><body>Not found</body></html>", "https://notes.example.com/sitemap.xml"))
    }

    @Test fun robotsRulesForEveryRobot() {
        val robots = SiteArticles.robots(
            """
            User-agent: BadBot
            Disallow: /

            User-agent: *
            Disallow: /private/
            Disallow: /drafts
            Allow: /private/shared/
            # a comment
            Sitemap: https://notes.example.com/sitemap.xml
            Sitemap: /extra-sitemap.xml
            """.trimIndent(),
            "https://notes.example.com",
        )
        assertEquals(listOf("https://notes.example.com/sitemap.xml", "https://notes.example.com/extra-sitemap.xml"), robots.sitemaps)
        assertTrue(robots.allows("/2020/03/iceland/"))
        assertFalse(robots.allows("/private/x"))
        assertTrue(robots.allows("/private/shared/y"))
        assertFalse(robots.allows("/drafts/1"))
    }

    @Test fun onlyPagesOfTheSameSiteThatCanBePosts() {
        assertEquals(
            listOf("https://notes.example.com/2020/03/iceland/", "https://www.notes.example.com/p/crete"),
            SiteArticles.candidates(
                listOf(
                    "https://notes.example.com/2020/03/iceland/#comments", "https://notes.example.com/2020/03/iceland/",
                    "https://www.notes.example.com/p/crete", "https://other.example.com/2020/x", "https://notes.example.com/",
                    "https://notes.example.com/tag/iceland/", "https://notes.example.com/category/trips/", "https://notes.example.com/author/ana/",
                    "https://notes.example.com/about/", "https://notes.example.com/page/2/", "https://notes.example.com/files/map.pdf",
                ),
                "https://notes.example.com/",
            ),
        )
    }

    @Test fun pagesOfPostsAndOlderPosts() {
        val html = """
            <html><body><main>
            <article><h2 class="entry-title"><a href="/2020/03/iceland/">Iceland</a></h2><p>…</p></article>
            <article><h2><a href="https://notes.example.com/2020/01/crete/">Crete</a></h2></article>
            <a href="https://twitter.example.com/notes">Follow</a>
            </main><div class="nav-previous"><a href="/page/2/">Older posts</a></div></body></html>
        """.trimIndent()
        val index = SiteArticles.index(html, "https://notes.example.com/")
        assertEquals(listOf("https://notes.example.com/2020/03/iceland/", "https://notes.example.com/2020/01/crete/"), index.posts)
        assertEquals("https://notes.example.com/page/2/", index.next)
        assertEquals("https://notes.example.com/page/3/", SiteArticles.index("<link rel=\"next\" href=\"/page/3/\">", "https://notes.example.com/page/2/").next)
    }

    @Test fun datedArticles() {
        fun article(head: String, body: String = "<p>Words.</p>") = Jsoup.parse("<html><head>$head</head><body>$body</body></html>", "https://notes.example.com/x/")
        assertTrue(SiteArticles.isArticle(article("<meta property=\"article:published_time\" content=\"2020-03-10T09:00:00+00:00\">")))
        assertTrue(SiteArticles.isArticle(article("", "<article><time datetime=\"2020-03-10\">10 March</time><p>Words.</p></article>")))
        val graph = """<script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"@type":["BlogPosting"],"datePublished":"2020-03-10T09:00:00Z"}]}</script>"""
        assertTrue(SiteArticles.isArticle(article(graph)))
        assertEquals("2020-03-10T09:00:00Z", SiteArticles.jsonLdDate(article(graph)))
        assertFalse(SiteArticles.isArticle(article("<script type=\"application/ld+json\">{\"@type\":\"WebPage\"}</script>")))
        assertFalse(SiteArticles.isArticle(article("<script type=\"application/ld+json\">{broken</script>")))

        val page = "<html><head>$graph<title>Iceland</title></head><body><article><h1>Iceland</h1><p>Wind.</p></article></body></html>"
        val post = PageReader.article(page, "https://notes.example.com/2020/03/iceland/")!!
        assertEquals(Instant.parse("2020-03-10T09:00:00Z"), post.at)
        assertEquals(listOf(SiteBlock.Text("Wind.")), post.blocks)
        assertNull(PageReader.article("<html><body><h1>About me</h1><p>Hi.</p></body></html>", "https://notes.example.com/about/"))
    }
}

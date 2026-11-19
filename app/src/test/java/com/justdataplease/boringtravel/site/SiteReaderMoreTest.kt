package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.links.LinkHttpException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

/** Round 2 sources against a fake web: Google Docs, sitemaps and pages of posts, a feed with a sitemap, reblogs, Stop. */
class SiteReaderMoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Answers by address (with the final address after redirects and a Content-Disposition); anything else is a 404. */
    private class FakeWeb(val pages: Map<String, Answer>) : SiteWeb {
        data class Answer(val type: String?, val body: String, val finalUrl: String? = null, val disposition: String? = null, val code: Int = 200)
        val asked = ArrayList<String>()
        override suspend fun get(url: String, maxBytes: Long, accept: (String?) -> Boolean): WebText? {
            asked += url
            val a = pages[url] ?: throw LinkHttpException(404)
            if (a.code != 200) throw LinkHttpException(a.code)
            if (!accept(a.type)) return null
            return WebText(a.finalUrl ?: url, a.type, a.body, false, a.disposition)
        }
    }

    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ab"
    private val docLink = "https://docs.google.com/document/d/$id/edit?usp=sharing"
    private val exportUrl = "https://docs.google.com/document/d/$id/export?format=html"
    private val today = LocalDate.of(2026, 11, 12)

    private fun reader(web: SiteWeb, drive: DriveDocs? = null) =
        SiteReader(web, pause = {}, drive = drive, inlineDir = { tmp.root.resolve("inline") }, zone = { ZoneOffset.UTC }, today = { today })

    private val png = Base64.getEncoder().encodeToString(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 9))
    private val diary = """
        <html><head><style>.c1{font-weight:700}</style></head><body>
        <p class="title"><span>Crete, slowly</span></p>
        <p>Ten days on the island.</p>
        <h1>3 April 2016 · Chania</h1><p><span class="c1">Windy</span> harbour.</p><p><img src="data:image/png;base64,$png"></p>
        <h1>4 April</h1><p>Samaria gorge.</p>
        <h1>Day 3</h1><p>Rest.</p>
        </body></html>
    """.trimIndent()

    @Test fun aLinkSharedGoogleDocBecomesANotePerSection() = runTest {
        val web = FakeWeb(mapOf(exportUrl to FakeWeb.Answer("text/html", diary, disposition = "attachment; filename=\"Crete.html\"")))
        val read = reader(web).read(docLink)
        assertEquals(SiteKind.Doc, read.kind)
        assertEquals("Crete, slowly", read.name)
        assertEquals("docs.google.com", read.host)
        assertEquals(listOf("Crete, slowly", "3 April 2016 · Chania", "4 April", "Day 3"), read.posts.map { it.title })
        assertEquals(
            listOf("2016-04-03", "2016-04-03", "2016-04-04", "2016-04-05"),
            read.posts.map { it.at!!.atZone(ZoneOffset.UTC).toLocalDate().toString() },
        )
        val picture = read.posts[1].pictures.single()
        assertTrue(InlinePictures.isInline(picture.url))
        assertTrue(read.inline.getValue(picture.url).exists())
        assertEquals(SiteBlock.Text("<b>Windy</b> harbour."), read.posts[1].blocks[0])
        // The same document again: every section is already there.
        val again = reader(web).read(docLink, isCopied = { p -> read.posts.any { SiteNotes.entryId(it) == SiteNotes.entryId(p) } })
        assertEquals(4, again.alreadyCopied)
        assertTrue(again.posts.isEmpty())
    }

    @Test fun throughDriveFirstWhenItCanOpenTheDocument() = runTest {
        var opened: String? = null
        val drive = object : DriveDocs {
            override suspend fun html(id: String, read: (InputStream) -> String): DriveDoc {
                opened = id
                return DriveDoc(null, read(diary.byteInputStream()))
            }
        }
        val web = FakeWeb(emptyMap())
        val read = reader(web, drive).read(docLink)
        assertEquals(id, opened)
        assertEquals(4, read.posts.size)
        assertTrue(web.asked.isEmpty())
    }

    @Test fun aDocumentThatIsNotSharedSaysSo() = runTest {
        val signIn = FakeWeb(mapOf(exportUrl to FakeWeb.Answer("text/html", "<html>Sign in</html>", finalUrl = "https://accounts.google.com/ServiceLogin?continue=x")))
        assertReason(SiteReader.DOC_NOT_SHARED) { reader(signIn).read(docLink) }
        assertReason(SiteReader.DOC_NOT_FOUND) { reader(FakeWeb(mapOf(exportUrl to FakeWeb.Answer("text/html", "", code = 404)))).read(docLink) }
        // Not shared, but published to the web: its page is read.
        val published = FakeWeb(
            mapOf(
                exportUrl to FakeWeb.Answer("text/html", "", code = 401),
                "https://docs.google.com/document/d/$id/pub" to FakeWeb.Answer("text/html", "<html><body><div id=\"title\">Crete</div><div id=\"contents\"><p>Hello.</p></div></body></html>"),
            ),
        )
        val read = reader(published).read(docLink)
        assertEquals("Crete", read.name)
        assertEquals(listOf(SiteBlock.Text("Hello.")), read.posts.single().blocks)
    }

    private fun article(n: Int, day: String) = """
        <html><head><meta property="article:published_time" content="${day}T10:00:00Z"><title>Post $n</title></head>
        <body><nav><a href="/">Home</a></nav><article><h1>Post $n</h1><p>Words of post $n.</p></article></body></html>
    """.trimIndent()

    @Test fun aSiteWithoutAFeedIsReadThroughItsSitemap() = runTest {
        val root = "https://notes.example.com"
        val web = FakeWeb(
            mapOf(
                "$root/" to FakeWeb.Answer("text/html", "<html><head><title>Notes</title></head><body><h1>Notes</h1></body></html>"),
                "$root/robots.txt" to FakeWeb.Answer("text/plain", "User-agent: *\nDisallow: /drafts/\nSitemap: $root/sitemap_index.xml"),
                "$root/sitemap_index.xml" to FakeWeb.Answer("application/xml", "<sitemapindex><sitemap><loc>$root/post-sitemap.xml</loc></sitemap></sitemapindex>"),
                "$root/post-sitemap.xml" to FakeWeb.Answer(
                    "application/xml",
                    "<urlset>" + listOf("/2020/03/iceland/", "/about/", "/drafts/secret/", "/2020/04/faroe/", "/2020/05/notes-page/").joinToString("") { "<url><loc>$root$it</loc></url>" } + "</urlset>",
                ),
                "$root/2020/03/iceland/" to FakeWeb.Answer("text/html", article(1, "2020-03-10")),
                "$root/2020/04/faroe/" to FakeWeb.Answer("text/html", article(2, "2020-04-10")),
                "$root/2020/05/notes-page/" to FakeWeb.Answer("text/html", "<html><body><h1>Just a page</h1><p>No date.</p></body></html>"),
            ),
        )
        val read = reader(web).read("notes.example.com")
        assertEquals(SiteKind.Articles, read.kind)
        assertEquals(listOf("Post 1", "Post 2"), read.posts.map { it.title })
        assertFalse(web.asked.any { "/drafts/" in it || "/about/" in it })
    }

    @Test fun pagesOfPostsWhenThereIsNoSitemap() = runTest {
        val root = "https://notes.example.com"
        fun index(vararg posts: String, next: String?) = "<html><body>" + posts.joinToString("") { "<article><h2><a href=\"$it\">x</a></h2></article>" } +
            (next?.let { "<a href=\"$it\">Older posts</a>" } ?: "") + "</body></html>"
        val web = FakeWeb(
            mapOf(
                "$root/" to FakeWeb.Answer("text/html", index("/2020/03/iceland/", next = "/page/2/")),
                "$root/page/2/" to FakeWeb.Answer("text/html", index("/2020/04/faroe/", next = null)),
                "$root/2020/03/iceland/" to FakeWeb.Answer("text/html", article(1, "2020-03-10")),
                "$root/2020/04/faroe/" to FakeWeb.Answer("text/html", article(2, "2020-04-10")),
            ),
        )
        val read = reader(web).read("notes.example.com")
        assertEquals(listOf("Post 1", "Post 2"), read.posts.map { it.title })
    }

    @Test fun aFeedOfTheNewestPostsGoesOnWithTheSitemap() = runTest {
        val root = "https://letters.example.com"
        val items = (11..20).joinToString("") { "<item><title>Letter $it</title><link>$root/p/letter-$it</link><pubDate>Sat, 04 May 2019 08:30:00 +0000</pubDate><description>Text $it</description></item>" }
        val web = FakeWeb(
            mapOf(
                "$root/feed" to FakeWeb.Answer("application/rss+xml", "<rss><channel><title>Letters</title>$items</channel></rss>"),
                "$root/sitemap.xml" to FakeWeb.Answer(
                    "application/xml",
                    "<urlset>" + (1..20).joinToString("") { "<url><loc>$root/p/letter-$it</loc></url>" } + "</urlset>",
                ),
            ) + (1..10).associate { "$root/p/letter-$it" to FakeWeb.Answer("text/html", article(it, "2019-04-%02d".format(it))) },
        )
        val read = reader(web).read("$root/feed")
        assertEquals(SiteKind.Feed, read.kind)
        assertEquals(20, read.posts.size)
        // The feed's ten are not fetched again as pages.
        assertFalse(web.asked.any { it.endsWith("/p/letter-15") })
    }

    @Test fun theSectionOfTheSiteAFeedsPostsShare() {
        val r = reader(FakeWeb(emptyMap()))
        assertEquals("/p/", r.commonPath(listOf("https://letters.example.com/p/a", "https://letters.example.com/p/b")))
        assertEquals("/@wanderer/", r.commonPath(listOf("https://medium.com/@wanderer/a-1?source=rss", "https://medium.com/@wanderer/b-2")))
        assertEquals("/blog/", r.commonPath(listOf("https://x.example.com/blog/2019/05/a/", "https://x.example.com/blog/2019/06/b/")))
        assertEquals("/", r.commonPath(listOf("https://x.example.com/2026/11/a/", "https://x.example.com/2026/11/b/")))
        assertEquals("/", r.commonPath(listOf("https://x.example.com/a", "https://x.example.com/b")))
    }

    @Test fun reblogsAreLeftOutAndCounted() = runTest {
        val posts = listOf(
            """{"id":"1","url":"https://wanderingfeet.tumblr.com/post/1","type":"regular","unix-timestamp":1500000000,"regular-body":"<p>Mine.</p>"}""",
            """{"id":"2","url":"https://wanderingfeet.tumblr.com/post/2","type":"regular","unix-timestamp":1500003600,"regular-body":"<p>Theirs.</p>","reblogged-from-name":"someone","reblogged-root-name":"someone"}""",
            """{"id":"3","url":"https://wanderingfeet.tumblr.com/post/3","type":"regular","unix-timestamp":1500007200,"regular-body":"<p>Mine again.</p>","reblogged-from-name":"wanderingfeet","reblogged-root-name":"wanderingfeet"}""",
        )
        val api = "var tumblr_api_read = {\"tumblelog\":{\"title\":\"W\",\"name\":\"wanderingfeet\"},\"posts-total\":3,\"posts\":[${posts.joinToString(",")}]};"
        val web = FakeWeb(mapOf("https://wanderingfeet.tumblr.com/api/read/json?start=0&num=50" to FakeWeb.Answer("text/javascript", api)))
        val read = reader(web).read("wanderingfeet.tumblr.com")
        assertEquals(listOf("1", "3"), read.posts.map { it.id })
        assertEquals(1, read.reblogs)
    }

    @Test fun stopKeepsWhatWasFound() = runTest {
        var calls = 0
        val web = object : SiteWeb {
            override suspend fun get(url: String, maxBytes: Long, accept: (String?) -> Boolean): WebText {
                calls++
                val start = Regex("start=(\\d+)").find(url)!!.groupValues[1].toInt()
                val posts = (start until start + 50).joinToString(",") {
                    """{"id":"$it","url":"https://w.tumblr.com/post/$it","type":"regular","unix-timestamp":${1500000000 + it},"regular-body":"<p>$it</p>"}"""
                }
                return WebText(url, "text/javascript", "var tumblr_api_read = {\"tumblelog\":{\"name\":\"w\"},\"posts-total\":1000,\"posts\":[$posts]};", false)
            }
        }
        val read = reader(web).read("w.tumblr.com", stop = { calls >= 2 })
        assertEquals(100, read.posts.size)
        assertTrue(read.stopped)
        assertTrue(read.more)
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

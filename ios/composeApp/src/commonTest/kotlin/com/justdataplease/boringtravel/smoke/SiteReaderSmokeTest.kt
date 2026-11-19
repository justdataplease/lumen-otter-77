package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.site.SiteBlock
import com.justdataplease.boringtravel.site.SiteKind
import com.justdataplease.boringtravel.site.SiteLimits
import com.justdataplease.boringtravel.site.SiteReadException
import com.justdataplease.boringtravel.site.SiteReader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Copy from a link's whole reading order (site/SiteReader.kt, read in) against a made-up web: a blog's own domain
 * without Tumblr finds the feed its page names and pages through WordPress until a 404; a Tumblr blog through its read
 * API with a reblog left out; a page without a feed cut into sections; and the plain errors.
 */
class SiteReaderSmokeTest {
    private val pauses = ArrayList<Long>()

    private fun reader(web: FakeWeb) = SiteReader(web, pause = { pauses += it })

    @Test fun aBlogsOwnDomainFindsItsFeed() = runTest {
        val home = Samples.BLOG
        val feed = "https://slowroads.example.com/feed/"
        val page = "<html><head><title>Slow Roads</title><link rel=\"alternate\" type=\"application/rss+xml\" title=\"Slow Roads Feed\" href=\"/feed/\"></head>" +
            "<body><h1>Slow Roads</h1><p>Welcome.</p></body></html>"
        val web = FakeWeb(mapOf(home to ("text/html" to page), feed to ("application/rss+xml" to Samples.RSS)))
        val read = reader(web).read("slowroads.example.com")
        assertEquals(SiteKind.Feed, read.kind)
        assertEquals("Slow Roads & Ferries", read.name)
        assertEquals("slowroads.example.com", read.host)
        assertEquals(listOf("Naxos at last", "Paros ’" + "19"), read.posts.map { it.title })
        assertFalse(read.more)
        assertEquals(0, read.alreadyCopied)
        assertEquals(
            listOf("https://slowroads.example.com/api/read/json?start=0&num=50", home, feed, "$feed?paged=2"),
            web.asked,
        )
        assertEquals(List(3) { SiteLimits.PAGE_PAUSE_MS }, pauses)
    }

    @Test fun postsCopiedBeforeAreCountedNotReadAgain() = runTest {
        val home = Samples.BLOG
        val feed = "https://slowroads.example.com/feed/"
        val page = "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/feed/\"></head><body></body></html>"
        val web = FakeWeb(mapOf(home to ("text/html" to page), feed to ("application/rss+xml" to Samples.RSS)))
        val read = reader(web).read(home, isCopied = { it.title == "Naxos at last" })
        assertEquals(1, read.alreadyCopied)
        assertEquals(listOf("https://slowroads.example.com/?p=77"), read.posts.map { it.url })
    }

    @Test fun aTumblrBlogThroughItsReadApi() = runTest {
        val web = FakeWeb(mapOf("https://slowroads.tumblr.com/api/read/json?start=0&num=50" to ("text/javascript" to Samples.TUMBLR)))
        val progress = ArrayList<Int>()
        val read = reader(web).read("slowroads.tumblr.com", onProgress = { progress += it })
        assertEquals(SiteKind.Tumblr, read.kind)
        assertEquals("Slow Roads & Ferries", read.name)
        assertEquals("slowroads.tumblr.com", read.host)
        assertEquals(listOf("7001", "7002"), read.posts.map { it.id })
        assertEquals(1, read.reblogs)
        assertFalse(read.more)
        assertEquals(listOf(2), progress)
        assertEquals(1, web.asked.size)
    }

    @Test fun aPageWithoutAFeedIsCutAtItsHeadings() = runTest {
        val url = "https://slowroads.example.com/island-notes"
        val page = "<html><head><title>Island notes | Slow Roads</title></head><body>" +
            "<main><h2>Day 1 · Naxos</h2><p>Ferry at dawn.</p><h2>Day 2 · Paros</h2><p>Bikes and beaches.</p></main></body></html>"
        val web = FakeWeb(mapOf(url to ("text/html" to page)))
        val read = reader(web).read(url)
        assertEquals(SiteKind.Page, read.kind)
        assertEquals("Island notes | Slow Roads", read.name)
        assertEquals(listOf("Day 1 · Naxos", "Day 2 · Paros"), read.posts.map { it.title })
        assertEquals(listOf("day 1 · naxos", "day 2 · paros"), read.posts.map { it.part })
        assertEquals(listOf(SiteBlock.Text("Ferry at dawn.")), read.posts[0].blocks)
        assertEquals("https://slowroads.example.com/api/read/json?start=0&num=50", web.asked.first())
        assertTrue("https://slowroads.example.com/robots.txt" in web.asked)
        assertTrue("https://slowroads.example.com/feed/" in web.asked)
    }

    @Test fun plainErrors() = runTest {
        suspend fun failure(address: String): Throwable? = runCatching { reader(FakeWeb(emptyMap())).read(address) }.exceptionOrNull()
        val notAnAddress = failure("two words")
        assertTrue(notAnAddress is SiteReadException, notAnAddress.toString())
        assertEquals(SiteReader.NOT_AN_ADDRESS, notAnAddress?.message)
        val gone = failure("https://gone.example.com/")
        assertTrue(gone is SiteReadException, gone.toString())
        assertEquals(SiteReader.COULD_NOT_READ, gone?.message)
        val closed = failure("https://quietblog.tumblr.com/")
        assertTrue(closed is SiteReadException, closed.toString())
        assertEquals(SiteReader.TUMBLR_PRIVATE, closed?.message)
        assertEquals("https://slowroads.example.com/naxos", SiteReader.address("  http://slowroads.example.com/naxos "))
        assertNull(SiteReader.address("localhost"))
    }
}

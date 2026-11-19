package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.links.LinkFetcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The real pipeline against live public sites, run by hand only: `BT_LIVE_SITE=1` (optionally `BT_LIVE_SITE_URLS`,
 * comma-separated; default Tumblr's staff blog, WordPress's news feed and a public Google Doc). Reads every post within the limits, groups
 * them by dates, builds every note, and prints what it found. No AI, no Android, no pictures downloaded.
 */
class SiteLiveTest {
    @Test fun readsLiveSites() = runBlocking {
        assumeTrue(System.getenv("BT_LIVE_SITE") == "1")
        val urls = System.getenv("BT_LIVE_SITE_URLS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("https://staff.tumblr.com", "https://wordpress.org/news/", "https://docs.google.com/document/d/1r_OPY26nYcDkBOmFL9mVfFduHCMEFJ6_/edit")
        val web = OkSiteWeb(OkHttpClient(), LinkFetcher.userAgent("test"), LinkFetcher.crawlerUserAgent("test"))
        val zone = ZoneId.of("Europe/Athens")
        for (url in urls) {
            val started = System.currentTimeMillis()
            var last = 0
            val dir = java.nio.file.Files.createTempDirectory("site-live").toFile()
            val read = SiteReader(web, inlineDir = { dir }).read(url, onProgress = { last = it })
            val organised = SiteOrganizer.organise(read.posts, read.name, zone, ai = null, locale = Locale.ENGLISH)
            val notes = read.posts.map { p ->
                val id = SiteNotes.entryId(p)
                SiteNotes.note(p, id, "trip", zone, 0L, SiteNotes.pictureIds(p, id).toSet(), 0L)
            }
            val pictures = read.posts.sumOf { it.pictures.size }
            val blocks = read.posts.flatMap { it.blocks }.groupingBy { it::class.simpleName }.eachCount()
            println(
                "LIVE $url: ${read.kind}, \"${read.name}\", ${read.posts.size} posts (progress $last), $pictures pictures, " +
                    "more=${read.more}, ${organised.trips.size} trips by dates, ${notes.size} notes, blocks=$blocks, " +
                    "dated=${read.posts.count { it.at != null }}, reblogs=${read.reblogs}, inline pictures=${read.inline.size}, " +
                    "stopped=${read.stopped}, whole=${read.whole}, ${(System.currentTimeMillis() - started) / 1000} s",
            )
            organised.trips.take(5).forEach { println("  trip: ${it.title} (${it.posts.size} posts)") }
            read.posts.take(8).forEach { p -> println("  note: \"${p.title.take(50)}\" ${p.at} ${p.blocks.size} blocks, ${p.pictures.size} pictures") }
            dir.deleteRecursively()
            read.posts.firstOrNull()?.let { p -> println("  first: ${p.url} \"${p.title}\" ${p.at} ${p.blocks.size} blocks, tags ${p.tags}") }
            assertTrue(read.posts.isNotEmpty())
            assertTrue(notes.all { it.entry.body.isNotEmpty() })
        }
    }
}

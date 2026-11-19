package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.data.db.Entry
import com.justdataplease.boringtravel.data.db.Media
import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.data.db.Trip
import com.justdataplease.boringtravel.doc.Block
import com.justdataplease.boringtravel.doc.CheckItem
import com.justdataplease.boringtravel.doc.EntryDoc
import com.justdataplease.boringtravel.export.TripBook
import com.justdataplease.boringtravel.ios.LinkHttpException
import com.justdataplease.boringtravel.site.SiteWeb
import com.justdataplease.boringtravel.site.WebText
import com.justdataplease.boringtravel.time.LocalDate
import com.justdataplease.boringtravel.time.localDate
import com.justdataplease.boringtravel.time.startOfDayMs

/*
 * Made-up data for the smoke tests that run the Android app's read-in code on the desktop and on the iPhone Simulator:
 * a five-day island trip, a made-up blog ("Slow Roads", slowroads.example.com), its feeds and a Tumblr answer.
 */
internal object Samples {
    /** "Today" for every check that bounds dates. */
    val today: LocalDate = localDate(2026, 11, 19)

    /** The trip's first day, a Friday. */
    val start: LocalDate = localDate(2027, 9, 10)

    const val ZONE = "Europe/Athens"
    const val BLOG = "https://slowroads.example.com/"
    const val DOC_ID = "1QwErTyUiOpAsDfGhJkLzXcVbNm123"

    /** [day] at [minutes] past midnight on [zone]'s clocks (no clock change on the days used). */
    fun at(day: LocalDate, minutes: Int, zone: String = ZONE): Long = startOfDayMs(day, zone) + minutes * 60_000L

    fun trip(): Trip = Trip("t1", "Naxos, slowly", "Paros → Naxos", start, start.plusDays(4), createdAt = 1, updatedAt = 1)

    fun plan(): List<PlanItem> = listOf(
        PlanItem(
            "i1", "t1", day = start, position = 1.0, timeMinutes = 7 * 60 + 25, kind = "transport", title = "Ferry to Naxos",
            note = "Blue Star", placeName = "Piraeus port", createdAt = 1, updatedAt = 1,
        ),
        PlanItem(
            "i2", "t1", day = start.plusDays(1), position = 1.0, timeMinutes = 19 * 60 + 30, kind = "food", title = "Dinner by the harbour",
            lat = 37.1, lng = 25.37, createdAt = 1, updatedAt = 1,
        ),
        PlanItem("i3", "t1", day = null, kind = "food", title = "Kitron tasting in Halki", createdAt = 1, updatedAt = 1),
        PlanItem("i4", "t1", day = start, title = "Removed earlier", createdAt = 1, updatedAt = 1, deletedAt = 3),
    )

    fun note(): EntryDoc = EntryDoc(
        blocks = listOf(
            Block.Heading("On deck"),
            Block.Paragraph("Slept on the deck chairs. <b>Paros</b> got smaller &amp; nothing else happened."),
            Block.Photo("m1", caption = "The harbour at dawn"),
            Block.Checklist(listOf(CheckItem("Tickets", true), CheckItem("Sunscreen"))),
        ),
    )

    /** The trip with one note on its second day (a photo in it) and its plan: what the exports read. */
    fun book(): TripBook {
        val entry = Entry(
            id = "e1", tripId = "t1", occurredAt = at(start.plusDays(1), 9 * 60 + 15), zoneId = ZONE, title = "Ferry day",
            body = note().toJson(), plainText = "", placeName = "Naxos port", tempC = 27.0, weather = "sunny", createdAt = 1, updatedAt = 1,
        )
        val photo = Media(
            id = "m1", tripId = "t1", entryId = "e1", localPath = "media/m1.jpg", width = 1200, height = 900,
            takenAt = at(start.plusDays(1), 7 * 60), createdAt = 1, updatedAt = 1,
        )
        return TripBook(trip = trip(), entries = listOf(entry), plan = plan(), media = listOf(photo))
    }

    /** A WordPress-shaped RSS 2.0 feed of the made-up blog: one post with content:encoded, one with a description only. */
    val RSS: String = """
        <?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:media="http://search.yahoo.com/mrss/">
        <channel>
        <title>Slow Roads &amp; Ferries</title>
        <link>https://slowroads.example.com</link>
        <generator>https://wordpress.org/?v=6.9</generator>
        <item>
        <title>Naxos at last</title>
        <link>https://slowroads.example.com/2019/05/naxos/</link>
        <pubDate>Sat, 04 May 2019 08:30:00 +0000</pubDate>
        <category><![CDATA[Greece]]></category>
        <category><![CDATA[Islands]]></category>
        <description><![CDATA[Short version [&#8230;]]]></description>
        <content:encoded><![CDATA[<p>The ferry was <strong>late</strong>.</p><figure><img src="https://slowroads.example.com/wp-content/uploads/portara-1024.jpg" srcset="https://slowroads.example.com/wp-content/uploads/portara-300.jpg 300w, https://slowroads.example.com/wp-content/uploads/portara.jpg 2048w"><figcaption>Portara</figcaption></figure>]]></content:encoded>
        <media:content url="https://slowroads.example.com/wp-content/uploads/harbour.jpg" medium="image" />
        </item>
        <item>
        <title>Paros &#8217;19</title>
        <guid>https://slowroads.example.com/?p=77</guid>
        <pubDate>Tue, 07 May 2019 18:00:00 GMT</pubDate>
        <description>&lt;p&gt;Short one.&lt;/p&gt;</description>
        </item>
        </channel>
        </rss>
    """.trimIndent()

    /** A Blogger-shaped Atom feed with a next page. */
    val ATOM: String = """
        <?xml version='1.0' encoding='UTF-8'?><feed xmlns='http://www.w3.org/2005/Atom'>
        <id>tag:slowroads.example.com,2019:blog</id>
        <title type='text'>Slow Roads (Atom)</title>
        <link rel='next' type='application/atom+xml' href='https://slowroads.example.com/feeds/posts/default?start-index=26&amp;max-results=25'/>
        <entry>
        <id>tag:slowroads.example.com,2019:post-9</id>
        <published>2019-05-09T20:00:00.000+02:00</published>
        <title type='text'>Hydra without cars</title>
        <category term='Greece'/>
        <content type='html'>&lt;p&gt;Donkeys at &lt;i&gt;dawn&lt;/i&gt;.&lt;/p&gt;</content>
        <link rel='alternate' type='text/html' href='https://slowroads.example.com/2019/05/hydra.html'/>
        </entry>
        </feed>
    """.trimIndent()

    /** Tumblr's read API in its JSONP shape: a text post, a photo post dated by date-gmt, a reblogged quote. */
    val TUMBLR: String = """
        var tumblr_api_read = {"tumblelog":{"title":"Slow Roads &amp; Ferries","name":"slowroads"},"posts-start":0,"posts-total":"3","posts":[
        {"id":"7001","url":"https:\/\/slowroads.tumblr.com\/post\/7001","type":"regular","unix-timestamp":1557000000,"regular-title":"Naxos at last","regular-body":"<p>The ferry was <b>late<\/b>.<\/p>","tags":["greece","naxos"]},
        {"id":"7002","url":"https:\/\/slowroads.tumblr.com\/post\/7002","type":"photo","date-gmt":"2019-05-06 10:00:00 GMT","photo-caption":"<p>Portara at <em>dusk<\/em>.<\/p>","photo-url-500":"https:\/\/64.media.example.com\/portara_500.jpg","photo-url-1280":"https:\/\/64.media.example.com\/portara_1280.jpg","photos":[]},
        {"id":"7003","url":"https:\/\/slowroads.tumblr.com\/post\/7003","type":"quote","unix-timestamp":1557200000,"quote-text":"Slow is a speed too.","quote-source":"A sign in Halki","reblogged-from-name":"otherblog","reblogged-root-name":"otherblog"}
        ]};
    """.trimIndent()
}

/** [SiteWeb] answering from [pages] (address → media type and body); anything else is a 404. Records every request. */
internal class FakeWeb(private val pages: Map<String, Pair<String, String>>) : SiteWeb {
    val asked = ArrayList<String>()

    override suspend fun get(url: String, maxBytes: Long, accept: (String?) -> Boolean): WebText? {
        asked += url
        val (type, body) = pages[url] ?: throw LinkHttpException(404)
        if (!accept(type)) return null
        return WebText(url, type, body, truncated = false)
    }
}

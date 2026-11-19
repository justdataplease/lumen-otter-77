package com.justdataplease.boringtravel.ios

import kotlinx.serialization.json.JsonPrimitive

/*
 * A made-up travel blog for Copy from a link's tests and pictures, answered from recorded pages: nothing here reaches
 * the network. "Slow Roads" (slowroads.example.com, a WordPress feed found through its home page) has five posts from
 * two journeys, Crete in April 2025 and Lisbon in October 2025, with pictures drawn here; its words are made up. With
 * the built-in planner's recorded sorting ([SORTED]) and the map's answers for its places.
 */
object SiteSamples {
    const val HOST = "slowroads.example.com"
    const val HOME = "https://$HOST/"
    const val FEED = "https://$HOST/feed/"
    const val ONE_POST = "https://$HOST/2025/04/09/balos-lagoon/"

    /** A post of the feed: its address, title, day (RFC 822 in the feed), words and pictures (file name, caption). */
    class Post(val slug: String, val title: String, val date: String, val iso: String, val words: List<String>, val pictures: List<Pair<String, String>>) {
        val url: String get() = "https://$HOST/${iso.take(4)}/${iso.substring(5, 7)}/${iso.substring(8, 10)}/$slug/"
        fun pictureUrl(file: String) = "https://$HOST/wp-content/uploads/${iso.take(4)}/${iso.substring(5, 7)}/$file"
    }

    /** Oldest first, as the organiser numbers them. */
    val POSTS = listOf(
        Post(
            "chania-harbour", "Chania harbour, first evening", "Thu, 03 Apr 2025 18:40:00 +0000", "2025-04-03",
            listOf("We landed late and walked straight down to the <b>Venetian harbour</b>.", "Grilled octopus by the lighthouse, then bed."),
            listOf("chania.jpg" to "The lighthouse at dusk"),
        ),
        Post(
            "samaria-gorge", "Samaria Gorge in one long day", "Sun, 06 Apr 2025 20:15:00 +0000", "2025-04-06",
            listOf("Sixteen kilometres down to the sea, the iron gates halfway.", "The ferry back from Agia Roumeli was <i>packed</i>."),
            listOf("gorge.jpg" to "", "gates.jpg" to "The iron gates"),
        ),
        Post(
            "balos-lagoon", "Balos lagoon at last", "Wed, 09 Apr 2025 17:05:00 +0000", "2025-04-09",
            listOf("A dirt road, a steep path, and that water."),
            listOf("balos.jpg" to "Balos from the path"),
        ),
        Post(
            "alfama-before-breakfast", "Alfama before breakfast", "Sun, 12 Oct 2025 09:30:00 +0000", "2025-10-12",
            listOf("Up the stairs to the miradouro while the city was still asleep.", "Coffee and a bica at the corner café."),
            listOf("alfama.jpg" to "", "miradouro.jpg" to "Morning over the river"),
        ),
        Post(
            "tram-28", "Tram 28 and a custard tart", "Tue, 14 Oct 2025 16:20:00 +0000", "2025-10-14",
            listOf("The whole line, end to end, then pastéis at the bakery."),
            listOf("tram.jpg" to "Tram 28 on the hill"),
        ),
    )

    val PICTURES: Int = POSTS.sumOf { it.pictures.size }

    val HOME_PAGE = """
        <!doctype html><html><head><meta charset="utf-8"><title>Slow Roads</title>
        <meta property="og:site_name" content="Slow Roads">
        <link rel="alternate" type="application/rss+xml" title="Slow Roads » Feed" href="$FEED">
        <link rel="alternate" type="application/rss+xml" title="Slow Roads » Comments Feed" href="https://$HOST/comments/feed/">
        </head><body><header><nav><a href="/">Home</a> <a href="/about/">About</a></nav></header>
        <main>${POSTS.reversed().joinToString("") { "<article><h2><a href=\"${it.url}\">${it.title}</a></h2></article>" }}</main>
        </body></html>
    """.trimIndent()

    private fun body(p: Post): String =
        p.words.joinToString("") { "<p>$it</p>" } +
            p.pictures.joinToString("") { (file, caption) ->
                "<figure><img src=\"${p.pictureUrl(file)}\" width=\"1200\" height=\"800\">" + (if (caption.isNotEmpty()) "<figcaption>$caption</figcaption>" else "") + "</figure>"
            }

    val FEED_XML: String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel>""")
        append("<title>Slow Roads</title><link>$HOME</link><generator>https://wordpress.org/?v=6.8</generator>")
        for (p in POSTS.reversed()) {
            append("<item><title>${p.title}</title><link>${p.url}</link><pubDate>${p.date}</pubDate>")
            append("<category><![CDATA[travel]]></category><guid isPermaLink=\"false\">https://$HOST/?p=${p.slug.length * 7}</guid>")
            append("<content:encoded><![CDATA[${body(p)}]]></content:encoded></item>")
        }
        append("</channel></rss>")
    }

    /** One post's own page: a dated article, so its link copies that post only. */
    fun postPage(p: Post): String = """
        <!doctype html><html><head><meta charset="utf-8"><title>${p.title} – Slow Roads</title>
        <meta property="og:site_name" content="Slow Roads"><meta property="og:type" content="article">
        <meta property="article:published_time" content="${p.iso}T12:00:00+00:00">
        <link rel="alternate" type="application/rss+xml" title="Slow Roads » Feed" href="$FEED">
        </head><body><header>Slow Roads</header><article><h1>${p.title}</h1>${body(p)}</article>
        <aside class="sidebar">Archives</aside><footer>© Slow Roads</footer></body></html>
    """.trimIndent()

    /** The built-in planner's sorting of the five posts: two trips, each post's place with its town and country. */
    val SORTED: String = """
        {"trips":[{"title":"Crete, spring 2025","posts":[1,2,3]},{"title":"Lisbon, autumn 2025","posts":[4,5]}],
         "details":[{"post":1,"places":[{"place":"Chania","area":"Greece"}]},
                    {"post":2,"places":[{"place":"Samaria Gorge","area":"Chania, Greece"}]},
                    {"post":3,"places":[{"place":"Balos Lagoon","area":"Chania, Greece"}]},
                    {"post":4,"places":[{"place":"Alfama","area":"Lisbon, Portugal"}]},
                    {"post":5,"places":[{"place":"Tram 28","area":"Lisbon, Portugal"}]}]}
    """.trimIndent()

    /** The sorting of [ONE_POST] read alone: one trip, its one place. */
    val ONE_SORTED: String =
        """{"trips":[{"title":"Balos, April 2025","posts":[1]}],"details":[{"post":1,"places":[{"place":"Balos Lagoon","area":"Chania, Greece"}]}]}"""

    /** [text] as the built-in planner's (Firebase AI Logic's) answer. */
    fun modelText(text: String): String =
        """{"candidates":[{"content":{"role":"model","parts":[{"text":${JsonPrimitive(text)}}]},"finishReason":"STOP","index":0}],""" +
            """"usageMetadata":{"promptTokenCount":900,"candidatesTokenCount":120,"totalTokenCount":1020}}"""

    /** The map's answer for a town or a sight in Crete or Lisbon (one point each, as Photon gives them). */
    private fun photon(name: String, city: String, country: String, code: String, lat: Double, lng: Double) =
        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"osm_type":"N","osm_id":${(lat * 1000).toLong()},""" +
            """"osm_key":"place","osm_value":"town","type":"city","name":"$name","city":"$city","country":"$country","countrycode":"$code"},""" +
            """"geometry":{"type":"Point","coordinates":[$lng,$lat]}}]}"""

    private val CRETE = photon("Chania", "Chania", "Greece", "GR", 35.5138, 24.0180)
    private val LISBON = photon("Lisboa", "Lisboa", "Portugal", "PT", 38.7139, -9.1394)

    /**
     * The blog's pages, feed and pictures, the map's answers for its places, and every other recorded answer from
     * [other] ([Recorded.net]); the feed's older pages answer 404 (WordPress past its last page). [pictures]: what each
     * picture's address answers with (the drawn photos by default).
     */
    fun net(other: RecordedNet = Recorded.net(), pictures: Map<String, Pair<String, ByteArray>> = drawnPictures()): Net = object : Net {
        val asked = mutableListOf<NetRequest>()

        override suspend fun get(request: NetRequest): NetAnswer {
            val u = request.url
            fun ok(body: String, type: String) = NetAnswer(200, u, mapOf("Content-Type" to type), body.encodeToByteArray())
            pictures[u]?.let { (type, bytes) -> return NetAnswer(200, u, mapOf("Content-Type" to type), bytes) }
            return when {
                u == HOME || u == "https://$HOST" -> ok(HOME_PAGE, "text/html; charset=UTF-8")
                u == FEED -> ok(FEED_XML, "application/rss+xml; charset=UTF-8")
                POSTS.any { it.url == u } -> ok(postPage(POSTS.first { it.url == u }), "text/html; charset=UTF-8")
                u.startsWith("https://photon.komoot.io/api/") && listOf("Chania", "Samaria", "Balos", "Greece").any { it in u } -> ok(CRETE, "application/json")
                u.startsWith("https://photon.komoot.io/api/") && listOf("Lisbon", "Alfama", "Tram").any { it in u } -> ok(LISBON, "application/json")
                u.startsWith("https://$HOST/") -> NetAnswer(404, u)
                else -> other.get(request)
            }
        }

        override suspend fun post(request: NetRequest): NetAnswer = other.post(request)
    }

    /** Every picture of the blog, drawn (JPEG). */
    fun drawnPictures(): Map<String, Pair<String, ByteArray>> {
        val scenes = listOf(Scene.Sunset, Scene.Sea, Scene.Olive, Scene.Sea, Scene.Town, Scene.Night, Scene.Town)
        var i = 0
        return POSTS.flatMap { p -> p.pictures.map { (file, _) -> p.pictureUrl(file) to ("image/jpeg" to samplePhoto(scenes[i++ % scenes.size], 600, 400)) } }.toMap()
    }
}

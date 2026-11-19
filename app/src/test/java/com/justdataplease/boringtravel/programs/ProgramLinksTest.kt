package com.justdataplease.boringtravel.programs

import com.justdataplease.boringtravel.data.SampleArgentina
import com.justdataplease.boringtravel.links.LinkUrls
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The websites in the trip ideas and the Argentina demo: https, written the way the app keeps a link, and never a
 * review site, booking site, Google, Wikipedia, a blog or an affiliate link. Generic items have none. Pure: whether a
 * link loads was checked by hand (research/boringtravel/programs, "Links").
 */
class ProgramLinksTest {
    /**
     * Hosts a link must never be on (the host itself or any subdomain). Brands with many country domains
     * ([bannedBrands]) are matched on any label of the host.
     */
    private val bannedHosts = listOf(
        // Reviews, bookings, tours and their resellers.
        "booking.com", "hotels.com", "agoda.com", "trip.com", "hostelworld.com", "viator.com", "klook.com", "kkday.com",
        "civitatis.com", "tiqets.com", "musement.com", "headout.com", "yelp.com", "lonelyplanet.com", "japan-guide.com",
        // Google (maps, search, short links) and the wikis.
        "goo.gl", "g.co", "g.page", "googleusercontent.com", "wikipedia.org", "wikivoyage.org", "wikimedia.org",
        "wikidata.org", "wikiwand.com",
        // Blogs, social pages and link shorteners.
        "blogspot.com", "wordpress.com", "medium.com", "substack.com", "tumblr.com", "facebook.com", "instagram.com",
        "tiktok.com", "youtube.com", "x.com", "twitter.com", "bit.ly", "tinyurl.com", "t.co", "linktr.ee",
    )
    private val bannedBrands = listOf("tripadvisor", "google", "booking", "expedia", "airbnb", "getyourguide", "skyscanner", "kayak")
    private val affiliateParams = listOf("aid", "affiliate", "affid", "partner", "partnerid", "ref", "tag", "utm_source", "utm_medium", "utm_campaign", "cid")

    private fun banned(url: String): Boolean {
        val host = url.toHttpUrl().host
        if (bannedHosts.any { host == it || host.endsWith(".$it") }) return true
        val labels = host.split('.')
        return bannedBrands.any { it in labels }
    }

    private val programs = ProgramLibrary.destinations.flatMap { it.programs }
    private fun Program.items(): List<ProgramItem> = days.flatMap { it.items } + ideas
    private val argentina = SampleArgentina.demo(LocalDate.of(2026, 11, 7), Instant.parse("2026-11-07T21:00:00Z"), 1L).items

    /** Every link with where it is from ("kyoto-osaka-culture · Fushimi Inari at first light"). */
    private val links: List<Pair<String, String>> =
        programs.flatMap { p -> p.items().mapNotNull { i -> i.url?.let { "${p.id} · ${i.title}" to it } } } +
            argentina.mapNotNull { i -> i.url?.let { "argentina · ${i.title}" to it } }

    @Test fun theBannedListCatchesWhatItShould() {
        for (bad in listOf(
            "https://www.tripadvisor.com/Attraction_Review-g298564", "https://www.tripadvisor.co.uk/x", "https://www.tripadvisor.com.ar/x",
            "https://www.booking.com/hotel/jp/x.html", "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA", "https://www.google.com/maps/place/x",
            "https://maps.google.pt/x", "https://en.wikipedia.org/wiki/Kyoto", "https://en.m.wikivoyage.org/wiki/Kyoto",
            "https://someone.blogspot.com/2024/kyoto", "https://www.getyourguide.de/x", "https://www.japan-guide.com/e/e3900.html",
        )) assertTrue(bad, banned(bad))
        for (ok in listOf("https://inari.jp/en/", "https://www.visitlisboa.com/en/places/alfama", "https://kyoto.travel/en/x", "https://www.cp.pt/x")) {
            assertFalse(ok, banned(ok))
        }
    }

    @Test fun everyLinkIsHttpsAndWellFormed() {
        assertTrue(links.isNotEmpty())
        for ((where, url) in links) {
            assertTrue(where, url.startsWith("https://"))
            // Exactly what the app keeps when someone types it in (no spaces, a real host, nothing to tidy).
            assertEquals(where, url, LinkUrls.normalize(url))
            val parsed = url.toHttpUrl()
            assertTrue(where, parsed.fragment == null && parsed.username.isEmpty() && parsed.port == 443)
        }
    }

    @Test fun noLinkIsOnABannedHostOrAnAffiliateLink() {
        for ((where, url) in links) {
            assertFalse("$where: $url", banned(url))
            val params = url.toHttpUrl().queryParameterNames.map { it.lowercase() }
            assertTrue("$where: $url", params.none { it in affiliateParams })
        }
    }

    @Test fun genericItemsHaveNoLink() {
        // Notes and stays ("Small hotel near Gion") never; places and the rest only where the place is a real one.
        for (p in programs) for (i in p.items()) {
            if (i.kind == "note" || i.kind == "stay") assertEquals("${p.id} · ${i.title}", null, i.url)
        }
        for (i in argentina) if (i.kind == "note" || i.kind == "stay") assertEquals(i.title, null, i.url)
    }

    @Test fun mostRealPlacesHaveOne() {
        // A real place: a place, meal or activity with a place name (programs) or a point (the demo).
        fun share(items: List<Boolean>) = items.count { it }.toDouble() / items.size
        for (p in programs) {
            val real = p.items().filter { it.placeName.isNotBlank() && it.kind in setOf("place", "food", "activity") }
            val share = share(real.map { it.url != null })
            assertTrue("${p.id}: ${"%.2f".format(share)}", share >= MIN_SHARE)
        }
        val real = argentina.filter { it.lat != null && it.kind in setOf("place", "food", "activity") }
        val share = share(real.map { it.url != null })
        assertTrue("argentina: ${"%.2f".format(share)}", share >= MIN_SHARE)
    }

    @Test fun argentinasIdeasUseTheDemosPages() {
        // A place in both the Argentina demo and the Argentina & Patagonia ideas has the same checked page in both
        // (research/boringtravel/programs/argentina-links.md and argentina-patagonia.md).
        val ideas = ProgramLibrary.destination("argentina-patagonia")!!.programs.flatMap { it.items() }.mapNotNull { it.url }.toSet()
        val demo = argentina.mapNotNull { it.url }.toSet()
        for (url in listOf(
            "https://www.aeropuertosargentina.com/en/EZE", "https://www.aeropuertosargentina.com/en/MDZ",
            "https://www.aeropuertosargentina.com/en/BRC", "https://www.aeropuertoelcalafate.com/en/",
            "https://www.aeropuertosantiagodechile.cl/en",
            "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/recoleta-cemetery",
            "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/palermo-rose-garden",
            "https://turismo.buenosaires.gob.ar/en/otros-establecimientos/caminito",
            "https://barilocheturismo.gob.ar/en/short-circuit", "https://cerrocampanario.com.ar/la-aerosilla/",
            "https://barilocheturismo.gob.ar/en/victoria-island-and-myrtle-woods",
            "https://elchalten.tur.ar/mirador-de-los-condores-y-aguilas/", "https://elchalten.tur.ar/laguna-capri/",
            "https://elchalten.tur.ar/el-chorrillo-del-salto/", "https://elchalten.tur.ar/laguna-de-los-tres/",
            "https://www.elcalafate.tur.ar/in-perito-moreno-glacier.htm", "https://telefericosantiago.cl/?lang=en",
            "https://chile.travel/en/destinations/valparaiso/",
        )) {
            assertTrue("demo: $url", url in demo)
            assertTrue("ideas: $url", url in ideas)
        }
    }

    @Test fun printTheNumbers() {
        // Links per program (every pace and the ideas) and in the demo, for the research notes.
        for (p in programs) {
            val items = p.items()
            val real = items.filter { it.placeName.isNotBlank() && it.kind in setOf("place", "food", "activity") }
            println("${p.id}: ${items.count { it.url != null }} links, ${items.mapNotNull { it.url }.toSet().size} distinct, real places ${real.count { it.url != null }}/${real.size}")
        }
        val real = argentina.filter { it.lat != null && it.kind in setOf("place", "food", "activity") }
        println("argentina: ${argentina.count { it.url != null }} links, ${argentina.mapNotNull { it.url }.toSet().size} distinct, real places ${real.count { it.url != null }}/${real.size}")
    }

    private companion object {
        /**
         * The least any program has: Lisbon & Porto Nature, 36 of 46 (Kyoto & Osaka Eat & drink 51 of 63; Argentina &
         * Patagonia Eat & drink 57 of 74).
         */
        const val MIN_SHARE = 0.75
    }
}

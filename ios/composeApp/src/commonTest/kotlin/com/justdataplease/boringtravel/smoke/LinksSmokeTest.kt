package com.justdataplease.boringtravel.smoke

import com.justdataplease.boringtravel.ios.KindWords
import com.justdataplease.boringtravel.ios.LinkFill
import com.justdataplease.boringtravel.ios.LinkPlaces
import com.justdataplease.boringtravel.ios.LinkUrls
import com.justdataplease.boringtravel.ios.LooseQueries
import com.justdataplease.boringtravel.ios.MapsLinks
import com.justdataplease.boringtravel.ios.MapsLists
import com.justdataplease.boringtravel.ios.MapsPlace
import com.justdataplease.boringtravel.ios.MetaTags
import com.justdataplease.boringtravel.ios.PageMeta
import com.justdataplease.boringtravel.ios.PhotonParser
import com.justdataplease.boringtravel.ios.PlaceNames
import com.justdataplease.boringtravel.ios.PlacePhotos
import com.justdataplease.boringtravel.ios.ShareRoute
import com.justdataplease.boringtravel.ios.SharedLink
import com.justdataplease.boringtravel.ios.SharedLinks
import com.justdataplease.boringtravel.ios.UrlCodec
import com.justdataplease.boringtravel.ios.WebUrl
import com.justdataplease.boringtravel.ios.WikiCredit
import com.justdataplease.boringtravel.ios.WikiCredits
import com.justdataplease.boringtravel.ios.WikiPhoto
import com.justdataplease.boringtravel.ios.WikiPhotos
import com.justdataplease.boringtravel.model.PlanKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iPhone's own link and place rules (ios/: Links.kt, MapsLists.kt, PlaceSearch.kt, DayPlaces.kt, Wiki.kt,
 * WebUrl.kt), which the read-in code calls in place of OkHttp, java.net and Java's Normalizer: addresses read as OkHttp
 * reads them, Google Maps links and lists, page meta tags and entities, place names, looser searches (lookbehind, (?i),
 * \p{L}), kinds from Greek and English words, Photon's answers and Wikipedia's photos and credits. Made-up places.
 */
class LinksSmokeTest {
    // ---- WebUrl and UrlCodec

    @Test fun addressesAreReadAsOkHttpReadsThem() {
        assertEquals("http://slowroads.example.com/a/c%20d?q=1%202#frag", WebUrl.of("HTTP://Slowroads.Example.com:80/a/./b/../c d?q=1 2#frag").toString())
        assertNull(WebUrl.of("ftp://slowroads.example.com/"))
        assertNull(WebUrl.of("not an address"))
        val base = WebUrl.of("https://slowroads.example.com/naxos")!!
        assertEquals("https://slowroads.example.com/img/a.jpg", base.resolve("../img/a.jpg").toString())
        assertEquals("https://cdn.example.com/x.png", base.resolve("//cdn.example.com/x.png").toString())
        assertEquals("https://slowroads.example.com:8443/", WebUrl.of("https://slowroads.example.com:8443")!!.toString())
        val q = WebUrl.of("https://slowroads.example.com/maps/place/Caf%C3%A9?q=Plaka%2C+Athens&n=1")!!
        assertEquals("Plaka, Athens", q.queryParameter("q"))
        assertEquals(listOf("maps", "place", "Café"), q.pathSegments)
        assertNull(q.queryParameter("missing"))
    }

    @Test fun formAndQueryEncoding() {
        assertEquals("Plaka%2C+Athens+%26+%CE%AC", UrlCodec.formEncode("Plaka, Athens & ά"))
        assertEquals("Plaka, Athens", UrlCodec.formDecode("Plaka%2C+Athens"))
        assertEquals("€5", UrlCodec.formDecode("%E2%82%AC5"))
        assertEquals("a%20b%2Bc%2Fd", UrlCodec.queryComponent("a b+c/d"))
        assertEquals("https://x.example.com/api?q=%CE%9D%CE%AC%CE%BE%CE%BF%CF%82&n=2", UrlCodec.withQuery("https://x.example.com/api", "q" to "Νάξος", "n" to "2"))
        assertEquals("Temple_of_Apollo_(Naxos)", UrlCodec.pathSegment("Temple_of_Apollo_(Naxos)"))
    }

    // ---- LinkUrls

    @Test fun linksAsTypedOrPasted() {
        assertEquals("https://www.example.org/menu", LinkUrls.normalize("Kafeneio Lefkes https://www.example.org/menu."))
        assertEquals("https://example.org/menu", LinkUrls.normalize("example.org/menu"))
        assertEquals("https://example.org:8080/x", LinkUrls.normalize("example.org:8080/x"))
        assertEquals("https://cdn.example.org/a", LinkUrls.normalize("//cdn.example.org/a"))
        assertNull(LinkUrls.normalize("mailto:someone@example.org"))
        assertNull(LinkUrls.normalize("javascript:alert(1)"))
        assertNull(LinkUrls.normalize("localhost"))
        assertNull(LinkUrls.normalize("example.c"))
        assertNull(LinkUrls.normalize("   "))
        assertEquals("example.org", LinkUrls.domain("https://www.example.org/a"))
        assertEquals("https://example.org/a", LinkUrls.forFetch("http://example.org/a"))
    }

    // ---- Google Maps links and lists

    @Test fun googleMapsLinksGiveTheirPlace() {
        assertEquals(
            MapsPlace("Temple of Poseidon", 37.65008, 24.02459),
            MapsLinks.parse("https://www.google.com/maps/place/Temple+of+Poseidon/@37.6503,24.0245,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d37.65008!4d24.02459"),
        )
        assertEquals(MapsPlace(null, 37.9715, 23.7257), MapsLinks.parse("https://www.google.com/maps/@37.9715,23.7257,15z"))
        assertEquals(MapsPlace("Monastiraki Square", null, null), MapsLinks.parse("https://maps.google.gr/maps?q=Monastiraki+Square"))
        assertEquals(MapsPlace(null, 37.9715, 23.7257), MapsLinks.parse("https://maps.google.com/?q=37.9715,23.7257"))
        assertNull(MapsLinks.parse("https://maps.app.goo.gl/AbCdEf123"))
        assertNull(MapsLinks.parse("https://slowroads.example.com/maps/place/x"))
        assertTrue(MapsLinks.isShort("https://maps.app.goo.gl/AbCdEf123"))
        assertTrue(MapsLinks.isMaps("https://www.google.co.uk/maps/place/Portara"))
        assertFalse(MapsLinks.isMaps("https://www.example.com/maps"))
        assertEquals("https://www.google.com/maps/search/?api=1&query=Plaka%2C+Athens", MapsLinks.searchUrl("Plaka, Athens", null, null))
        assertEquals("https://www.google.com/maps/place/Portara", MapsLinks.fromIntent("intent://maps.app.goo.gl/x#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fwww.google.com%2Fmaps%2Fplace%2FPortara;end"))
        assertEquals(
            "https://www.google.com/maps/place/Portara/@37.1106,25.3713,17z",
            MapsLinks.findInHtml("<script>var u=\"https:\\/\\/www.google.com\\/maps\\/place\\/Portara\\/@37.1106,25.3713,17z\";</script>"),
        )
        assertEquals("https://www.google.com/maps/place/Portara", MapsLinks.unwrapConsent("https://consent.google.com/m?continue=https://www.google.com/maps/place/Portara&gl=GR"))
        assertEquals(null to "https://www.google.com/maps/place/Portara", LinkPlaces.sortLinks("https://www.google.com/maps/place/Portara", null))
        assertEquals("example.org", LinkPlaces.placeholderTitle("https://www.example.org/menu"))
        assertEquals("Yasaka Shrine", LinkFill.nameOf("https://www.google.com/maps/place/Yasaka", "Yasaka Shrine, Kyoto"))
    }

    @Test fun aSharedGoogleMapsList() {
        val ref = assertNotNull(MapsLists.ref("https://www.google.com/maps/placelists/list/AbCdEfGhIjKlMnOp?token=Zz9_x-Y"))
        assertEquals(MapsLists.Ref("AbCdEfGhIjKlMnOp", "Zz9_x-Y"), ref)
        assertEquals("https://www.google.com/maps/placelists/list/AbCdEfGhIjKlMnOp?token=Zz9_x-Y", MapsLists.pageUrl(ref))
        assertEquals(MapsLists.Ref("QwErTyUiOpAsDf"), MapsLists.ref("https://www.google.com/maps/@37.1,25.3,12z/data=!4m3!11m2!2sQwErTyUiOpAsDf!3e3"))
        assertNull(MapsLists.ref("https://maps.app.goo.gl/AbC"))
        val urls = MapsLists.requestUrls(MapsLists.Ref("QwErTyUiOpAsDf"), session = "0123456789abcdef012345")
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("!1s0123456789abcdef012345!") && urls[0].contains("!13shttps%3A%2F%2Fwww.google.com%2Fmaps%2Fplacelists%2Flist%2FQwErTyUiOpAsDf!"), urls[0])
        assertTrue(urls[1].contains("!1m4!1sQwErTyUiOpAsDf!"))
        val answer = ")]}'\n[[[\"LIST_ID_1234567\"],null,null,null,\"Naxos eats\",\"Places to eat\",null,null," +
            "[[null,[null,null,\"Taverna Lefteris, Halki 843 02\",null,\"Halki 843 02, Greece\",[null,null,37.0587,25.4807],[null,\"1234567890123456789\"]],\"Taverna Lefteris\",\"Try the kitron\"]," +
            "[null,[null,null,\"No pin\",null,null,null]]],null,null,null,2]]"
        val list = assertNotNull(MapsLists.parse(answer))
        assertEquals("LIST_ID_1234567", list.id)
        assertEquals("Naxos eats", list.name)
        assertEquals("Places to eat", list.description)
        assertEquals(2, list.total)
        assertTrue(list.more)
        val place = list.places.single()
        assertEquals("Taverna Lefteris", place.name)
        assertEquals(37.0587, place.lat)
        assertEquals("Halki 843 02, Greece", place.address)
        assertEquals("Try the kitron", place.note)
        assertEquals("https://maps.google.com/?cid=1234567890123456789", place.mapsUrl)
        assertNull(MapsLists.parse("<html>not a list</html>"))
    }

    // ---- Shared text and page meta tags

    @Test fun sharedTextBecomesAPlaceOrAnItinerary() {
        val maps = "https://maps.app.goo.gl/AbCdEf123"
        assertEquals(SharedLink(maps, "Taverna Lefteris"), SharedLinks.parse("Taverna Lefteris\n$maps"))
        assertEquals(ShareRoute.Place(SharedLink(maps, "Taverna Lefteris")), SharedLinks.route("Taverna Lefteris $maps"))
        assertEquals(ShareRoute.NoLink, SharedLinks.route("hello there"))
        val itinerary = (1..6).joinToString("\n") { "Day $it: ferry, beach, dinner by the harbour and a long walk home" }
        assertEquals(ShareRoute.Itinerary, SharedLinks.route(itinerary))
        assertEquals(Samples.DOC_ID, SharedLinks.googleDocId("https://docs.google.com/document/d/${Samples.DOC_ID}/edit"))
        assertEquals(listOf("https://example.org/a", "https://example.org/b"), SharedLinks.links("see https://example.org/a, and https://example.org/b!"))
    }

    @Test fun pageMetaTagsAndEntities() {
        val html = "<html><head><meta charset=\"utf-8\"><title>Kafeneio &#8217;Lefkes&#8217; &amp; more</title>" +
            "<meta property=\"og:image\" content=\"/img/cover.jpg\"><meta property='og:site_name' content='Slow Roads'>" +
            "<META NAME=twitter:title CONTENT=\"Kafeneio Lefkes,   on Paros\"></head></html>"
        val meta = MetaTags.parse(html, "http://slowroads.example.com/posts/1")
        assertEquals("https://slowroads.example.com/img/cover.jpg", meta.imageUrl)
        assertEquals("Kafeneio Lefkes, on Paros", meta.title)
        assertEquals("Slow Roads", meta.siteName)
        assertEquals(
            PageMeta(null, "Kafeneio ’Lefkes’ & more", null),
            MetaTags.parse("<title>Kafeneio &#8217;Lefkes&#8217; &amp; more</title>", "https://slowroads.example.com/"),
        )
        assertEquals("café Ö 🏝 &bogus;", MetaTags.decode("caf&eacute; &Ouml; &#x1F3DD; &bogus;"))
        assertEquals("utf-8", MetaTags.charset(null, "<meta charset=\"utf-8\">"))
        assertEquals("ISO-8859-7", MetaTags.charset("text/html; charset=ISO-8859-7", ""))
        assertNull(MetaTags.resolve("data:image/png;base64,AAAA", "https://slowroads.example.com/"))
    }

    // ---- Place names and searches

    @Test fun placeNamesAreShortAndClean() {
        assertEquals("Naxos", PlaceNames.clean("Naxos Regional Unit"))
        assertEquals("Paros", PlaceNames.clean("Municipality of Paros"))
        assertEquals("Halki, Naxos, Greece", PlaceNames.compact("Halki", listOf(null, "Naxos Regional Unit", "South Aegean"), "Greece"))
        assertEquals("Greece", PlaceNames.compact("Greece", listOf("Greece"), "Greece"))
        assertEquals("Naxos", PlaceNames.area("Halki, Naxos, Greece"))
        assertEquals("Naxos", PlaceNames.area("Agios Georgios, Naxos"))
        assertNull(PlaceNames.area(" , "))
    }

    @Test fun looserSearchesForAnAddress() {
        assertEquals(
            listOf("Kafeneio Lefkes, Odos Agiou Nikolaou 12, Lefkes, Paros, Greece", "Odos Agiou Nikolaou 12, Lefkes, Paros, Greece"),
            LooseQueries.of("Kafeneio Lefkes, Odos Agiou Nikolaou No:12, 84400 Lefkes/Paros, Greece"),
        )
        assertEquals(emptyList<String>(), LooseQueries.of("Naxos"))
        assertEquals("ταβερνα το λιμανι", LooseQueries.normal("Ταβέρνα «Το Λιμάνι»"))
        assertEquals("cafe tortoni", LooseQueries.normal("Café  Tortoni!"))
        assertEquals("Lefteris", LooseQueries.bare("Taverna Lefteris"))
        assertEquals(listOf("Taverna Lefteris", "Lefteris"), LooseQueries.names("Taverna Lefteris"))
        assertTrue(LooseQueries.sameName("Lefteris", "Taverna Lefteris"))
        assertTrue(LooseQueries.sameName("Ταβέρνα Λευτέρης", "ταβερνα λευτερης"))
        assertFalse(LooseQueries.sameName("Naxos", "Paros"))
    }

    @Test fun kindsFromObviousWords() {
        assertEquals(PlanKind.Transport, KindWords.guessKind("Πτήση για Νάξο"))
        assertEquals(PlanKind.Transport, KindWords.guessKind("Back home ✈"))
        assertEquals(PlanKind.Stay, KindWords.guessKind("Check-in Hotel Aegean"))
        assertEquals(PlanKind.Food, KindWords.guessKind("Dinner at Lefteris"))
        assertEquals(PlanKind.Food, KindWords.guessKind("Ταβέρνα Λευτέρης"))
        assertEquals(PlanKind.Activity, KindWords.guessKind("Hiking to the summit"))
        assertNull(KindWords.guessKind("Portara"))
        assertEquals(PlanKind.Food, KindWords.guessKind("Portara", "Café Portara"))
        assertEquals(PlanKind.Stay, LinkFill.obviousKind("Hotel Aegean"))
        assertNull(LinkFill.obviousKind("Museum of Cycladic Art"))
    }

    @Test fun photonsAnswer() {
        val json = """
            {"features":[
             {"geometry":{"coordinates":[25.3713,37.1106]},"properties":{"name":"Portara","city":"Naxos","county":"Naxos Regional Unit","country":"Greece","countrycode":"gr","osm_key":"historic","osm_value":"ruins","osm_type":"W","osm_id":"123"}},
             {"geometry":{"coordinates":[25.3714,37.1107]},"properties":{"name":"Portara","city":"Naxos","country":"Greece"}},
             {"geometry":{"coordinates":[25.48,37.06]},"properties":{"street":"Odos Halkiou","housenumber":"3","country":"Greece","osm_key":"amenity","osm_value":"cafe"}},
             {"geometry":{"coordinates":[200.0,37.06]},"properties":{"name":"Off the map"}}
            ]}
        """.trimIndent()
        val results = PhotonParser.parse(json)
        assertEquals(listOf("Portara, Naxos, Greece", "Odos Halkiou 3, Greece"), results.map { it.displayName })
        assertEquals("GR", results[0].countryCode)
        assertEquals(PlanKind.Place, results[0].kind)
        assertEquals("osm:W123", results[0].externalId)
        assertEquals(PlanKind.Food, results[1].kind)
        assertEquals(37.1106, results[0].lat)
        assertTrue(PhotonParser.parse("not json").isEmpty())
    }

    // ---- Wikipedia photos and credits

    @Test fun wikipediaPhotosAndTheirNames() {
        val thumb = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Portara_Naxos.jpg/640px-Portara_Naxos.jpg"
        assertTrue(WikiPhotos.isPhoto(thumb))
        assertFalse(WikiPhotos.isPhoto("https://upload.wikimedia.org/wikipedia/commons/a/ab/Naxos_location_map.svg"))
        assertFalse(WikiPhotos.isPhoto(thumb, "Flag_of_Greece.jpg"))
        assertFalse(WikiPhotos.isPhoto(thumb, "Coat_of_arms_of_Naxos.jpg"))
        assertEquals("παναγια εκατονταπυλιανη", WikiPhotos.key("Παναγία Εκατονταπυλιανή (Πάρος)"))
        assertTrue(WikiPhotos.similar("portara", "portara gate"))
        val answer = """
            {"query":{"pages":{
             "1":{"pageid":1,"title":"Portara","index":2,"thumbnail":{"source":"$thumb"},"pageimage":"Portara_Naxos.jpg","coordinates":[{"lat":37.1106,"lon":25.3713,"type":"landmark","globe":"earth"}]},
             "2":{"pageid":2,"title":"Naxos (city)","index":1,"thumbnail":{"source":"https://upload.wikimedia.org/x/Naxos_map.png"},"coordinates":[{"lat":37.105,"lon":25.376,"type":"city"}]}
            }}}
        """.trimIndent()
        assertEquals(2, WikiPhotos.pages(answer).size)
        assertEquals(WikiPhoto(thumb, "Portara", "Portara_Naxos.jpg"), WikiPhotos.pick(answer, "Portara", 37.11, 25.37))
        assertTrue(WikiPhotos.queryUrl(37.1106, 25.3713).contains("ggscoord=37.110600%7C25.371300"))
        assertEquals(
            listOf("Lunch at Taverna Lefteris: book ahead", "Taverna Lefteris"),
            PlacePhotos.names("Lunch at Taverna Lefteris: book ahead", "Taverna Lefteris, Halki, Naxos"),
        )
        assertEquals(listOf("temple of apollo", "Temple Of Apollo"), PlacePhotos.titles(listOf("temple of apollo")))
        assertEquals(5_000.0, PlacePhotos.radiusM("airport", null))
        assertNull(PlacePhotos.radiusM("country", null))
        assertEquals(3_000.0, PlacePhotos.radiusM(null, 3_000.0))
        assertEquals("https://en.wikipedia.org/wiki/Temple_of_Apollo_(Naxos)", PlacePhotos.articleUrl("Temple of Apollo (Naxos)"))
    }

    @Test fun wikipediaCredits() {
        val answer = """
            {"query":{"pages":{"-1":{"imageinfo":[{"extmetadata":{
              "Artist":{"value":"<a href=\"//commons.wikimedia.org/wiki/User:Example\">Island Photo Club</a>"},
              "LicenseShortName":{"value":"CC BY-SA 4.0"},
              "LicenseUrl":{"value":"//creativecommons.org/licenses/by-sa/4.0"},
              "AttributionRequired":{"value":"true"}}}]}}}}
        """.trimIndent()
        assertEquals(
            WikiCredit("Island Photo Club", "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0", "Portara_Naxos.jpg"),
            WikiCredits.parse(answer, "Portara_Naxos.jpg"),
        )
        assertNull(WikiCredits.parse(answer.replace("CC BY-SA 4.0", "CC BY-NC 2.0"), "Portara_Naxos.jpg"))
        assertTrue(WikiCredits.reusable("Public domain"))
        assertFalse(WikiCredits.reusable("CC BY-NC-ND 3.0"))
        assertEquals("Photo: Island Photo Club, CC BY-SA 4.0 · Wikipedia", WikiCredits.line("Island Photo Club", "CC BY-SA 4.0"))
        assertEquals("x".repeat(60) + "…", WikiCredits.short("x".repeat(70)))
        assertEquals("A & B 🏝", WikiCredits.plain("<b>A</b>  &amp;\n B &#127965;"))
    }
}

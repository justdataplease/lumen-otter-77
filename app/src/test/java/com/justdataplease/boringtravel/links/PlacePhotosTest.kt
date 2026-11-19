package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacePhotosTest {
    private fun item(
        title: String = "Yasaka Shrine",
        place: String = "",
        lat: Double? = 35.0036,
        lng: Double? = 135.7785,
        kind: PlanKind = PlanKind.Place,
        url: String? = null,
    ) = PlanItem("i", "t", null, title = title, placeName = place, lat = lat, lng = lng, kind = kind.id, url = url, createdAt = 1, updatedAt = 1)

    @Test fun onlyLocatedItemsWithoutALinkThatAreRealPlacesGetAPhoto() {
        assertNotNull(PlacePhotos.query(item()))
        assertNull(PlacePhotos.query(item(lat = null, lng = null)))
        assertNull(PlacePhotos.query(item(lat = 0.0, lng = 0.0)))
        assertNull(PlacePhotos.query(item(kind = PlanKind.Note)))
        // A link always wins (its own picture); a link that isn't one doesn't count.
        assertNull(PlacePhotos.query(item(url = "https://maps.app.goo.gl/abc")))
        assertNotNull(PlacePhotos.query(item(url = "not a link")))
        // A Transport item's place is where it arrives: the airport.
        val kix = PlacePhotos.query(item("Fly to Osaka Kansai", "Kansai International Airport, Osaka, Japan", 34.43056, 135.23028, PlanKind.Transport))!!
        assertEquals(listOf("Fly to Osaka Kansai", "Osaka Kansai", "Kansai International Airport"), kix.names)
    }

    @Test fun namesAreTheTitleTheTitleWithoutTheLeadAndThePlace() {
        assertEquals(listOf("Lunch at Kuromon Market", "Kuromon Market"), PlacePhotos.names("Lunch at Kuromon Market", ""))
        assertEquals(listOf("Day trip to Nara: park and forest", "Nara", "Nara Park"), PlacePhotos.names("Day trip to Nara: park and forest", "Nara Park, Nara, Japan"))
        assertEquals(listOf("Back to Kyoto", "Kyoto"), PlacePhotos.names("Back to Kyoto", "Kyoto, Japan"))
        assertEquals(listOf("Yasaka Shrine"), PlacePhotos.names("Yasaka Shrine", "Yasaka Shrine, Gion, Kyoto"))
        assertEquals(listOf("Gion"), PlacePhotos.names("", "Gion, Kyoto, Japan"))
        // Too short or no letters: nothing to look up by name.
        assertEquals(emptyList<String>(), PlacePhotos.names("A1", "12"))
    }

    @Test fun theKeyIsTheSpotToTenMetresAndTheNames() {
        val a = PlacePhotos.query(item())!!
        assertEquals("geo:35.0036,135.7785|yasaka shrine", a.key)
        // A few metres off is the same spot; a rename or a move looks again.
        assertEquals(a.key, PlacePhotos.query(item(lat = 35.003602, lng = 135.778499))!!.key)
        assertNotEquals(a.key, PlacePhotos.query(item(title = "Gion"))!!.key)
        assertNotEquals(a.key, PlacePhotos.query(item(lat = 35.0100))!!.key)
        // Never a link: a place's preview can't be mistaken for a link's.
        assertFalse(a.key.startsWith("http"))
    }

    @Test fun titlesAreAskedAsWrittenAndCapitalised() {
        assertEquals(listOf("kuromon market", "Kuromon Market", "Yasaka Shrine"), PlacePhotos.titles(listOf("kuromon market", "Yasaka Shrine")))
        // "|" separates titles in the query; other characters a title can't have go too.
        assertEquals(listOf("Cafe Bar"), PlacePhotos.titles(listOf("Cafe|Bar")))
        val url = PlacePhotos.titleQueryUrl(listOf("Yasaka Shrine", "Gion"))
        assertTrue(url, url.startsWith("https://en.wikipedia.org/w/api.php?action=query&titles=Yasaka%20Shrine%7CGion&redirects=1&"))
        listOf("prop=pageimages%7Ccoordinates", "piprop=thumbnail%7Cname", "pithumbsize=640", "coprop=type%7Cdim", "format=json")
            .forEach { assertTrue("$url has $it", url.contains(it)) }
    }

    private fun page(id: Int, title: String, file: String?, lat: Double?, lng: Double?, type: String? = null, dim: String? = null): String {
        val thumb = file?.let { """"thumbnail":{"source":"https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/$it/640px-$it","width":640,"height":427},"pageimage":"$it",""" }.orEmpty()
        val coord = if (lat != null && lng != null) {
            val extra = listOfNotNull(type?.let { "\"type\":\"$it\"" }, dim?.let { "\"dim\":$it" }).joinToString("") { ",$it" }
            """"coordinates":[{"lat":$lat,"lon":$lng,"primary":"","globe":"earth"$extra}],"""
        } else ""
        return """"$id":{"pageid":$id,"ns":0,$thumb$coord"title":"$title"}"""
    }

    private fun answer(vararg pages: String, normalized: String = "", redirects: String = "") =
        """{"batchcomplete":"","query":{${normalized}${redirects}"pages":{${pages.joinToString(",")}}}}"""

    @Test fun theArticleNamedLikeThePlaceWinsWhenItIsNear() {
        // Asked "Fushimi Inari" and "kyoto"; Wikipedia capitalises one and follows the other's redirect.
        val a = answer(
            page(1, "Fushimi Inari-taisha", "Fushimi_Inari_Taisha_Torii.jpg", 34.9671, 135.7727, "landmark"),
            page(2, "Kyoto", "Kyoto_montage.jpg", 35.0117, 135.7683, "city(1474570)"),
            """"-1":{"ns":0,"title":"Lunch in Fushimi","missing":""}""",
            normalized = """"normalized":[{"from":"kyoto","to":"Kyoto"}],""",
            redirects = """"redirects":[{"from":"Fushimi Inari","to":"Fushimi Inari-taisha"}],""",
        )
        val shrine = PlacePhotos.pickByTitle(a, listOf("Lunch in Fushimi", "Fushimi Inari", "kyoto"), 34.9675, 135.7730)!!
        assertEquals("Fushimi Inari-taisha", shrine.title)
        assertTrue(shrine.imageUrl.endsWith("640px-Fushimi_Inari_Taisha_Torii.jpg"))
        // Names are tried in order: the city only when the landmark isn't it.
        assertEquals("Kyoto", PlacePhotos.pickByTitle(a, listOf("kyoto", "Fushimi Inari"), 34.9675, 135.7730)!!.title)
    }

    @Test fun aNamedLandmarkFarFromTheItemIsSomeoneElses() {
        val a = answer(page(1, "Golden Pavilion", "Kinkaku-ji.jpg", 35.0394, 135.7292, "landmark"))
        // 300 m off: the same temple (a pin at the gate).
        assertNotNull(PlacePhotos.pickByTitle(a, listOf("Golden Pavilion"), 35.0370, 135.7270))
        // 6 km off: another place with that name.
        assertNull(PlacePhotos.pickByTitle(a, listOf("Golden Pavilion"), 35.0, 135.78))
        // An article without coordinates can't be checked, so it isn't taken.
        assertNull(PlacePhotos.pickByTitle(answer(page(1, "Golden Pavilion", "Kinkaku-ji.jpg", null, null)), listOf("Golden Pavilion"), 35.0394, 135.7292))
    }

    @Test fun townsAirportsAndAreasAllowAWiderRadius() {
        val kyoto = answer(page(2, "Kyoto", "Kyoto_montage.jpg", 35.0117, 135.7683, "city(1474570)"))
        // 9 km from the city's point (Arashiyama) is still Kyoto; Osaka, 43 km away, isn't.
        assertNotNull(PlacePhotos.pickByTitle(kyoto, listOf("Kyoto"), 35.0094, 135.6668))
        assertNull(PlacePhotos.pickByTitle(kyoto, listOf("Kyoto"), 34.6937, 135.5023))
        val kix = answer(page(3, "Kansai International Airport", "Kansai_International_Airport_aerial.jpg", 34.4342, 135.2328, "airport"))
        // A pin at the far end of the island, 2.8 km from the airport article's point; a landmark there would be too far.
        assertNotNull(PlacePhotos.pickByTitle(kix, listOf("Kansai International Airport"), 34.4100, 135.2400))
        assertNull(PlacePhotos.pickByTitle(kix.replace("airport", "landmark"), listOf("Kansai International Airport"), 34.4100, 135.2400))
        assertEquals(PlacePhotos.NEAR_M, PlacePhotos.radiusM(null, null)!!, 0.0)
        assertEquals(PlacePhotos.NEAR_M, PlacePhotos.radiusM("landmark", 300.0)!!, 0.0)
        assertEquals(PlacePhotos.AIRPORT_M, PlacePhotos.radiusM("airport", null)!!, 0.0)
        assertEquals(PlacePhotos.TOWN_M, PlacePhotos.radiusM("city", null)!!, 0.0)
        assertEquals(PlacePhotos.AREA_M, PlacePhotos.radiusM("isle", null)!!, 0.0)
        // The article's own size when it is bigger (a park, a lake), up to 100 km.
        assertEquals(4_000.0, PlacePhotos.radiusM("landmark", 4_000.0)!!, 0.0)
        assertEquals(100_000.0, PlacePhotos.radiusM("adm1st", 400_000.0)!!, 0.0)
        // A country or an event is never the place's photo.
        assertNull(PlacePhotos.radiusM("country", null))
        assertNull(PlacePhotos.radiusM("event", 1_000.0))
        val japan = answer(page(4, "Japan", "Japan_Fuji.jpg", 36.0, 138.0, "country"))
        assertNull(PlacePhotos.pickByTitle(japan, listOf("Japan"), 36.0, 138.0))
    }

    @Test fun drawingsNeverStandForThePlace() {
        val a = answer(page(1, "Nara", "Nara_locator_map.jpg", 34.685, 135.805, "city"), page(2, "Gion", "Gion_logo.svg", 35.0036, 135.7785))
        assertNull(PlacePhotos.pickByTitle(a, listOf("Nara"), 34.685, 135.805))
        assertNull(PlacePhotos.pickByTitle(a, listOf("Gion"), 35.0036, 135.7785))
        assertNull(PlacePhotos.pickByTitle("<html>busy</html>", listOf("Gion"), 35.0036, 135.7785))
        assertNull(PlacePhotos.pickByTitle("", listOf("Gion"), 35.0036, 135.7785))
    }

    @Test fun theArticleOpensOnWikipedia() {
        assertEquals("https://en.wikipedia.org/wiki/Yasaka_Shrine", PlacePhotos.articleUrl("Yasaka Shrine"))
        assertEquals("https://en.wikipedia.org/wiki/Shij%C5%8D_Street", PlacePhotos.articleUrl("Shijō Street"))
    }

    private val titleHit = answer(page(1, "Yasaka Shrine", "Yasaka_jinja.jpg", 35.00361, 135.77861, "landmark"))
    private val geoHit = answer(page(7, "Shijō Street", "Shijo_dori_01.JPG", 35.003825, 135.777222))

    /** A Commons file's metadata answer: [license] null = none named. */
    private fun creditAnswer(file: String, license: String? = "CC BY-SA 4.0", artist: String? = "Jane Doe") =
        """{"query":{"pages":{"-1":{"ns":6,"title":"File:$file","missing":"","imagerepository":"shared","imageinfo":[{"extmetadata":{""" +
            listOfNotNull(
                artist?.let { """"Artist":{"value":"$it","source":"commons-desc-page"}""" },
                license?.let { """"LicenseShortName":{"value":"$it","source":"commons-desc-page","hidden":""}""" },
                """"LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0","source":"commons-desc-page","hidden":""}""",
            ).joinToString(",") + "}}]}}}}"

    /** Answers Wikipedia's three kinds of request: titles, nearby articles, a file's credit. */
    private fun wiki(asked: MutableList<String>, licensed: (String) -> Boolean = { true }): suspend (String) -> String = { url ->
        asked += url
        when {
            "prop=imageinfo" in url -> {
                val file = if ("Yasaka" in url) "Yasaka_jinja.jpg" else "Shijo_dori_01.JPG"
                creditAnswer(file, license = if (licensed(file)) "CC BY-SA 4.0" else null)
            }
            "titles=" in url -> titleHit
            else -> geoHit
        }
    }

    @Test fun placePhotosOffMeansWikipediaIsNeverAsked() = runTest {
        val asked = mutableListOf<String>()
        val off = WikiLookup(ask = { asked += it; titleHit }, enabled = { false })
        assertNull(off.photo(listOf("Yasaka Shrine"), 35.0036, 135.7785))
        assertTrue(asked.isEmpty())
    }

    @Test fun aTitleHitIsOneRequestElseTheNearestIsAskedTooEachWithItsCredit() = runTest {
        val asked = mutableListOf<String>()
        val lookup = WikiLookup(ask = wiki(asked), enabled = { true })
        val yasaka = lookup.photo(listOf("Yasaka Shrine"), 35.0036, 135.7785)!!
        assertEquals("Yasaka Shrine", yasaka.title)
        assertEquals("Yasaka_jinja.jpg", yasaka.file)
        assertEquals(WikiCredit("Jane Doe", "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0", "Yasaka_jinja.jpg"), yasaka.credit)
        assertEquals(2, asked.size)
        assertTrue(asked[1], asked[1].contains("Yasaka_jinja.jpg") && asked[1].contains("prop=imageinfo"))

        asked.clear()
        assertEquals("Shijō Street", lookup.photo(listOf("Dinner in Gion"), 35.0038, 135.7772)!!.title)
        assertEquals(3, asked.size)
        assertTrue(asked[0].contains("titles="))
        assertTrue(asked[1].contains("generator=geosearch"))
        assertTrue(asked[2].contains("prop=imageinfo"))

        // No name to look up: straight to the nearest.
        asked.clear()
        assertEquals("Shijō Street", lookup.photo(emptyList(), 35.0038, 135.7772)!!.title)
        assertEquals(2, asked.size)
    }

    @Test fun aPhotoWithoutAReusableLicenceIsNeverTaken() = runTest {
        val asked = mutableListOf<String>()
        // The named article's photo has no licence: the nearest article's photo, with one, is taken instead.
        val noYasaka = WikiLookup(ask = wiki(asked) { it != "Yasaka_jinja.jpg" }, enabled = { true })
        assertEquals("Shijō Street", noYasaka.photo(listOf("Yasaka Shrine"), 35.0036, 135.7785)!!.title)
        // Neither has one: no photo at all.
        asked.clear()
        val none = WikiLookup(ask = wiki(asked) { false }, enabled = { true })
        assertNull(none.photo(listOf("Yasaka Shrine"), 35.0036, 135.7785))
        assertEquals(4, asked.size)
    }
}

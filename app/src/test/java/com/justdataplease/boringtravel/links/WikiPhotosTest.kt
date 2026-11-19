package com.justdataplease.boringtravel.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiPhotosTest {
    private val lat = 35.0036559
    private val lng = 135.7785534

    // The real answer for the Yasaka Shrine pin (2026-11-07).
    private val yasaka = """
        {"batchcomplete":"","query":{"pages":{
          "1584824":{"pageid":1584824,"ns":0,"title":"Yasaka Shrine","index":-1,
            "thumbnail":{"source":"https://thumb.wikimedia.org/wikipedia/commons/thumb/9/96/Yasaka_jinja.jpg/960px-Yasaka_jinja.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail","width":640,"height":480},
            "coordinates":[{"lat":35.00361111,"lon":135.77861111,"primary":"","globe":"earth"}]},
          "7351895":{"pageid":7351895,"ns":0,"title":"Shijō Street","index":0,
            "thumbnail":{"source":"https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4c/Shijo_dori_01.JPG/960px-Shijo_dori_01.JPG?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail","width":640,"height":480},
            "coordinates":[{"lat":35.003825,"lon":135.777222,"primary":"","globe":"earth"}]}
        }}}
    """.trimIndent()

    @Test fun theQueryAsksForArticlesNearThePinWithTheirLeadImages() {
        val url = WikiPhotos.queryUrl(lat, lng)
        assertTrue(url, url.startsWith("https://en.wikipedia.org/w/api.php?action=query&generator=geosearch&"))
        assertTrue(url, url.contains("ggscoord=35.003656%7C135.778553"))
        listOf("ggsradius=200", "ggslimit=5", "prop=pageimages%7Ccoordinates", "piprop=thumbnail%7Cname", "pithumbsize=640", "coprop=type%7Cdim", "format=json")
            .forEach { assertTrue("$url has $it", url.contains(it)) }
    }

    @Test fun theArticleNamedLikeThePlaceWins() {
        val photo = WikiPhotos.pick(yasaka, "Yasaka Shrine", lat, lng)!!
        assertEquals("Yasaka Shrine", photo.title)
        assertTrue(photo.imageUrl.contains("Yasaka_jinja.jpg"))
        // Spelling, accents, case and a "(Kyoto)" don't stand in the way.
        assertEquals("Shijō Street", WikiPhotos.pick(yasaka, "shijo street", lat, lng)!!.title)
        assertEquals("Yasaka Shrine", WikiPhotos.pick(yasaka.replace("\"Yasaka Shrine\"", "\"Yasaka Shrine (Kyoto)\""), "Yasaka-Shrine", lat, lng)!!.title.substringBefore(" ("))
    }

    @Test fun elseTheNearestArticleWithAPhoto() {
        // Nearer to Shijō Street's coordinates than to the shrine's.
        assertEquals("Shijō Street", WikiPhotos.pick(yasaka, "Some café", 35.003825, 135.777222)!!.title)
        assertEquals("Yasaka Shrine", WikiPhotos.pick(yasaka, null, lat, lng)!!.title)
    }

    @Test fun aLongerNameWithTheArticlesNameInsideMatches() {
        val answer = """{"query":{"pages":{
            "1":{"title":"Gion","index":0,"thumbnail":{"source":"https://upload.wikimedia.org/a/gion.jpg"},"coordinates":[{"lat":35.0036,"lon":135.7785}]},
            "2":{"title":"Kiyomizu-dera","index":1,"thumbnail":{"source":"https://upload.wikimedia.org/a/kiyomizu.jpg"},"coordinates":[{"lat":34.99,"lon":135.78}]}}}}"""
        assertEquals("Kiyomizu-dera", WikiPhotos.pick(answer, "Kiyomizu-dera Temple", lat, lng)!!.title)
        // Too short a name to trust inside another ("Gion" in "Gion Corner"): the nearest instead.
        assertEquals("Kiyomizu-dera", WikiPhotos.pick(answer, "Gion Corner", 34.99, 135.78)!!.title)
    }

    @Test fun drawingsAndPagesWithoutPicturesAreSkipped() {
        val answer = """{"query":{"pages":{
            "1":{"title":"Higashiyama-ku","index":0,"thumbnail":{"source":"https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Higashiyama_map.svg/640px-Higashiyama_map.svg.png"},"coordinates":[{"lat":35.0036,"lon":135.7785}]},
            "2":{"title":"Maruyama Park","index":1,"coordinates":[{"lat":35.0037,"lon":135.7786}]},
            "3":{"title":"Chion-in","index":2,"thumbnail":{"source":"https://upload.wikimedia.org/a/chionin.jpg"},"coordinates":[{"lat":35.0057,"lon":135.7834}]}}}}"""
        assertEquals("Chion-in", WikiPhotos.pick(answer, "Higashiyama-ku", lat, lng)!!.title)
    }

    @Test fun nothingNearbyOrABrokenAnswerGivesNoPhoto() {
        assertNull(WikiPhotos.pick("""{"batchcomplete":""}""", "Yasaka Shrine", lat, lng))
        assertNull(WikiPhotos.pick("""{"query":{"pages":{}}}""", "Yasaka Shrine", lat, lng))
        assertNull(WikiPhotos.pick("<html>error</html>", "Yasaka Shrine", lat, lng))
        assertNull(WikiPhotos.pick("", null, lat, lng))
        assertNull(WikiPhotos.pick("""{"query":{"pages":{"1":{"title":"X","thumbnail":{"source":"http://plain.example.com/a.jpg"}}}}}""", null, lat, lng))
    }

    @Test fun onlyPhotosCountNotMapsFlagsLogosOrDrawings() {
        val thumb = "https://upload.wikimedia.org/wikipedia/commons/thumb/9/96"
        assertTrue(WikiPhotos.isPhoto("$thumb/Yasaka_jinja.jpg/640px-Yasaka_jinja.jpg"))
        assertTrue(WikiPhotos.isPhoto("https://upload.wikimedia.org/a/IMG_2041.JPEG"))
        assertTrue(WikiPhotos.isPhoto("$thumb/x.tif/lossy-page1-640px-x.tif.jpg", "Kyoto_1890.tif"))
        listOf(
            "Japan_location_map.svg", "Kyoto_Prefecture_locator_map.jpg", "Flag_of_Kyoto.jpg", "Kyoto_City_logo.jpg",
            "Coat_of_arms_of_Lisbon.jpg", "Emblem_of_Nara.jpg", "Gion_map_1910.jpg", "Kyoto_station_plan.jpg",
            "Panorama.png", "Spinner.gif",
        ).forEach { file -> assertFalse(file, WikiPhotos.isPhoto("$thumb/$file/640px-$file.png", file)) }
        // Without the file's name, it is read from the address: an SVG's render is still a drawing.
        assertFalse(WikiPhotos.isPhoto("$thumb/Japan_location_map.svg/640px-Japan_location_map.svg.png"))
        // Words inside other words don't count ("Mapleton", "Flagstaff").
        assertTrue(WikiPhotos.isPhoto("$thumb/Mapleton_Hall.jpg/640px-Mapleton_Hall.jpg"))
        assertTrue(WikiPhotos.isPhoto("$thumb/Flagstaff_hill.jpg/640px-Flagstaff_hill.jpg"))
    }

    @Test fun anyOfTheItemsNamesCanMatchAndBigAreasAreNeverTheNearest() {
        // The title doesn't match, the place does.
        assertEquals("Shijō Street", WikiPhotos.pick(yasaka, listOf("Dinner by the river", "Shijo Street"), lat, lng)!!.title)
        val answer = """{"query":{"pages":{
            "1":{"title":"Kyoto Prefecture","index":0,"thumbnail":{"source":"https://upload.wikimedia.org/a/kyoto_pref.jpg"},"coordinates":[{"lat":35.0036,"lon":135.7785,"type":"adm1st"}]},
            "2":{"title":"Chion-in","index":1,"thumbnail":{"source":"https://upload.wikimedia.org/a/chionin.jpg"},"coordinates":[{"lat":35.0057,"lon":135.7834,"type":"landmark"}]}}}}"""
        assertEquals("Chion-in", WikiPhotos.pick(answer, emptyList(), lat, lng)!!.title)
    }
}

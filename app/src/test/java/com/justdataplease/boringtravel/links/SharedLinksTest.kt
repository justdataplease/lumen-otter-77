package com.justdataplease.boringtravel.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinksTest {
    @Test fun googleMapsSharesTheNameTheAddressAndTheLink() {
        val text = "Yasaka Shrine\n625 Gionmachi Kitagawa, Higashiyama Ward, Kyoto, 605-0073, Japan\n\nhttps://maps.app.goo.gl/dhGpNqYSh5WmUy9X8"
        assertEquals(SharedLink("https://maps.app.goo.gl/dhGpNqYSh5WmUy9X8", "Yasaka Shrine"), SharedLinks.parse(text))
        assertEquals(ShareRoute.Place(SharedLink("https://maps.app.goo.gl/dhGpNqYSh5WmUy9X8", "Yasaka Shrine")), SharedLinks.route(text))
        // On one line, as older versions share it.
        assertEquals(SharedLink("https://maps.app.goo.gl/abc", "Yasaka Shrine"), SharedLinks.parse("Yasaka Shrine https://maps.app.goo.gl/abc"))
    }

    @Test fun aBrowserSharesTheAddressAndThePagesTitleAsTheSubject() {
        assertEquals(SharedLink("https://www.tavernalefteris.gr/menu", "Taverna Lefteris – Menu"), SharedLinks.parse("https://www.tavernalefteris.gr/menu", "Taverna Lefteris – Menu"))
        assertEquals(SharedLink("https://example.com/"), SharedLinks.parse("https://example.com/"))
        assertEquals(ShareRoute.Place(SharedLink("https://example.com/")), SharedLinks.route("  https://example.com/  "))
        // The words beside the link win over the subject; punctuation around the link isn't part of it.
        assertEquals(SharedLink("https://example.com/a", "Look at this"), SharedLinks.parse("Look at this: https://example.com/a.", "Subject"))
    }

    @Test fun ofSeveralLinksTheFirstMapsLinkElseTheFirst() {
        val text = "Dinner https://www.tripadvisor.com/Restaurant_Review-x and the map https://maps.app.goo.gl/xyz"
        assertEquals("https://maps.app.goo.gl/xyz", SharedLinks.parse(text)!!.url)
        assertEquals("Dinner and the map", SharedLinks.parse(text)!!.name)
        assertEquals("https://a.example/", SharedLinks.parse("https://a.example/ https://b.example/")!!.url)
        assertEquals(listOf("https://a.example/", "https://www.google.com/maps/place/X"), SharedLinks.links("see https://a.example/, https://www.google.com/maps/place/X!"))
    }

    @Test fun aPastedBareAddressIsALinkButOtherWordsAreNot() {
        assertEquals(SharedLink("https://example.com/menu"), SharedLinks.parse("example.com/menu"))
        assertNull(SharedLinks.parse("Yasaka Shrine"))
        assertNull(SharedLinks.parse("v1.2"))
        assertNull(SharedLinks.parse("e.g."))
        assertNull(SharedLinks.parse(""))
        assertNull(SharedLinks.parse(null))
        assertNull(SharedLinks.parse("mailto:hey@example.com"))
    }

    @Test fun textWithoutALinkHasNothingToAdd() {
        assertEquals(ShareRoute.NoLink, SharedLinks.route("Yasaka Shrine"))
        assertEquals(ShareRoute.NoLink, SharedLinks.route("Yasaka Shrine\nGion, Kyoto"))
        assertEquals(ShareRoute.NoLink, SharedLinks.route("   "))
        assertNull(SharedLinks.route(null))
    }

    @Test fun itinerariesFilesAndGoogleDocsStillGoToThePlanner() {
        val itinerary = (1..6).joinToString("\n") { "Day $it: walk the old town, lunch by the river, a museum in the afternoon" }
        assertEquals(ShareRoute.Itinerary, SharedLinks.route(itinerary))
        // A long text with links in it is an itinerary too, not a place.
        assertEquals(ShareRoute.Itinerary, SharedLinks.route("$itinerary\nhttps://maps.app.goo.gl/abc"))
        assertEquals(ShareRoute.Itinerary, SharedLinks.route(null, hasFile = true))
        assertEquals(ShareRoute.Itinerary, SharedLinks.route("https://example.com/", hasFile = true))
        assertEquals(ShareRoute.Itinerary, SharedLinks.route("https://docs.google.com/document/d/1AbCdEfGhIjK/edit"))
    }
}

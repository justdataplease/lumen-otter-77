package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.data.db.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A place's website and its Google Maps link (owner, 2026-11-13: "add to a place a website and a google url"). */
class LinkPlacesTest {
    private val site = "https://www.tavernalefteris.gr/"
    private val maps = "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"
    private val longMaps = "https://www.google.com/maps/place/Yasaka+Shrine/@35.0036559,135.7785534,17z"
    private val item = PlanItem("i", "t", null, title = "Lefteris", createdAt = 1, updatedAt = 1)

    @Test fun aMapsLinkKeptAsTheWebsiteBecomesTheMapsLink() {
        assertEquals(null to maps, LinkPlaces.sortLinks(maps, null))
        assertEquals(null to longMaps, LinkPlaces.sortLinks(longMaps, null))
        // A Maps link already there stays; a site stays the website; nothing stays nothing.
        assertEquals(null to longMaps, LinkPlaces.sortLinks(maps, longMaps))
        assertEquals(site to maps, LinkPlaces.sortLinks(site, maps))
        assertEquals(site to null, LinkPlaces.sortLinks(site, null))
        assertEquals(null to null, LinkPlaces.sortLinks(null, null))
        // The same test as everywhere else: maps.google.*, google.<country>/maps, short links; not other Google pages.
        assertEquals(null to "https://maps.google.gr/?q=Naxos", LinkPlaces.sortLinks("https://maps.google.gr/?q=Naxos", null))
        assertEquals("https://www.google.com/search?q=naxos" to null, LinkPlaces.sortLinks("https://www.google.com/search?q=naxos", null))
    }

    @Test fun anItemsLinksAreSortedAndASortedItemIsLeftAsItIs() {
        val moved = LinkPlaces.sortLinks(item.copy(url = maps))
        assertNull(moved.url)
        assertEquals(maps, moved.mapsUrl)
        val sorted = item.copy(url = site, mapsUrl = maps)
        assertSame(sorted, LinkPlaces.sortLinks(sorted))
        assertSame(item, LinkPlaces.sortLinks(item))
    }

    @Test fun onlyWebLinksAreKept() {
        assertEquals(site, LinkPlaces.web(site))
        assertEquals("http://example.com", LinkPlaces.web("http://example.com"))
        assertNull(LinkPlaces.web("javascript:alert(1)"))
        assertNull(LinkPlaces.web("intent://maps#Intent;end"))
        assertNull(LinkPlaces.web(null))
    }

    @Test fun theSheetsTwoFieldsKeepTwoLinks() {
        assertEquals("https://tavernalefteris.gr/" to maps, LinkPlaces.fromFields(" tavernalefteris.gr ", " $maps ", null))
        // A Maps link typed as the website is the Maps link; Maps shares "Name + link" and the link is kept.
        assertEquals(null to maps, LinkPlaces.fromFields(maps, "", null))
        assertEquals(null to maps, LinkPlaces.fromFields("Yasaka Shrine $maps", "", null))
        assertEquals(null to maps, LinkPlaces.fromFields("", "Yasaka Shrine\n$maps", null))
        // An empty field removes its link; one that can't be kept keeps the item's (a site is no Maps link).
        val saved = item.copy(url = site, mapsUrl = longMaps)
        assertEquals(null to null, LinkPlaces.fromFields("", "", saved))
        assertEquals(site to longMaps, LinkPlaces.fromFields("not a link", "https://example.com/menu", saved))
        assertEquals(site to null, LinkPlaces.fromFields("not a link", "example.com", item.copy(url = site)))
    }

    @Test fun aPlaceFollowsANewMapsLinkOnlyWhenTheOldOneGaveIt() {
        val yasaka = MapsPlace("Yasaka Shrine", 35.0036559, 135.7785534)
        val kiyomizu = MapsPlace("Kiyomizu-dera", 34.9948561, 135.7850463)
        val fromLink = item.copy(placeName = "Yasaka Shrine", lat = yasaka.lat, lng = yasaka.lng)
        assertTrue(LinkPlaces.placeFrom(fromLink, yasaka))
        val moved = LinkPlaces.follow(fromLink, yasaka, kiyomizu)
        assertEquals("Kiyomizu-dera", moved.placeName)
        assertEquals(34.9948561, moved.lat!!, 0.0)
        assertEquals(135.7850463, moved.lng!!, 0.0)
        // A place picked from the search, typed or found with Use my location stays; so does one when the new link has no pin.
        val picked = item.copy(placeName = "Gion, Kyoto", lat = 35.0037, lng = 135.7752)
        assertFalse(LinkPlaces.placeFrom(picked, yasaka))
        assertSame(picked, LinkPlaces.follow(picked, yasaka, kiyomizu))
        val typed = item.copy(placeName = "Grandma's house")
        assertSame(typed, LinkPlaces.follow(typed, yasaka, kiyomizu))
        assertSame(fromLink, LinkPlaces.follow(fromLink, yasaka, MapsPlace("Kiyomizu-dera", null, null)))
        assertSame(fromLink, LinkPlaces.follow(fromLink, null, kiyomizu))
    }

    @Test fun anItemWithBothLinksIsTitledByTheMapsPlaceUntilItAnswers() {
        assertEquals(LinkPlaces.PLACEHOLDER_MAPS, LinkPlaces.placeholderTitle(site, maps))
        assertEquals("tavernalefteris.gr", LinkPlaces.placeholderTitle(site, null))
        assertNull(LinkPlaces.placeholderTitle(null, null))
    }
}

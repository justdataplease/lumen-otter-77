package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.data.db.PlanItem
import com.justdataplease.boringtravel.model.PlanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A link that resolves while the item sheet is open fills what the traveller left empty, and nothing else. */
class LinkFillTest {
    private val blank = PlanItem("i", "t", null, title = "", createdAt = 1, updatedAt = 1)
    private val yasaka = MapsPlace("Yasaka Shrine", 35.0036, 135.7785)

    @Test fun aMapsLinkFillsTheTitleAndThePlaceOfANewItem() {
        val filled = LinkFill.fill(blank, "Yasaka Shrine", yasaka, kindTouched = false)
        assertEquals("Yasaka Shrine", filled.title)
        assertEquals("Yasaka Shrine", filled.placeName)
        assertEquals(35.0036, filled.lat!!, 0.0)
        // Nothing in the name says Food or Stay: the kind stays as it was.
        assertEquals(PlanKind.Place.id, filled.kind)
    }

    @Test fun whatTheTravellerSetStays() {
        val typed = blank.copy(title = "Shrine at dusk", kind = PlanKind.Activity.id)
        val filled = LinkFill.fill(typed, "Hotel Granvia Kyoto", yasaka, kindTouched = true)
        assertEquals("Shrine at dusk", filled.title)
        assertEquals(PlanKind.Activity.id, filled.kind)
        // A place picked by hand isn't moved; a place removed by hand isn't put back.
        val placed = blank.copy(placeName = "Gion", lat = 35.0, lng = 135.77)
        assertEquals("Gion", LinkFill.fill(placed, null, yasaka, kindTouched = false).placeName)
        assertNull(LinkFill.fill(blank, "Yasaka Shrine", yasaka, kindTouched = false, placeCleared = true).lat)
    }

    @Test fun aTitleALinkGaveFollowsANewLinkAndOneOfTheTravellersOwnStays() {
        // Owner, 2026-11-13: "when I change website need to update title and image".
        val site = "https://www.tavernalefteris.gr/"
        assertTrue(LinkFill.fromLink("", site))
        assertTrue(LinkFill.fromLink("Taverna Lefteris", site, "Taverna Lefteris"))
        assertTrue(LinkFill.fromLink("Taverna Lefteris - Naxos", site, null, "Taverna Lefteris - Naxos"))
        assertTrue(LinkFill.fromLink("tavernalefteris.gr", site))
        assertFalse(LinkFill.fromLink("Dinner with Maria", site, "Taverna Lefteris"))
        assertFalse(LinkFill.fromLink("Taverna Lefteris!", site, "Taverna Lefteris"))
        // A Maps place's name without its address, and the Maps placeholder.
        val maps = "https://maps.app.goo.gl/x"
        assertTrue(LinkFill.fromLink("Yasaka Shrine", maps, "Yasaka Shrine, Gion, Kyoto"))
        assertTrue(LinkFill.fromLink(LinkPlaces.PLACEHOLDER_MAPS, maps))
        assertFalse(LinkFill.fromLink("Shrine at dusk", maps, "Yasaka Shrine"))
        // A very long page title was cut to fit.
        val long = "A".repeat(LinkFill.MAX_TITLE + 20)
        assertTrue(LinkFill.fromLink(long.take(LinkFill.MAX_TITLE), site, long))
    }

    @Test fun theKindChangesOnlyWhenTheNameMakesItObvious() {
        assertEquals(PlanKind.Stay.id, LinkFill.fill(blank, "Hotel Granvia Kyoto | Official site", null, kindTouched = false).kind)
        assertEquals(PlanKind.Food.id, LinkFill.fill(blank, "Taverna Lefteris", null, kindTouched = false).kind)
        assertEquals(PlanKind.Stay, LinkFill.obviousKind("Kyoto Hostel"))
        assertEquals(PlanKind.Food, LinkFill.obviousKind("Café Bibliotic Hello!"))
        // An airport, a museum or a station stays a place unless the traveller picks.
        assertNull(LinkFill.obviousKind("Kansai International Airport"))
        assertNull(LinkFill.obviousKind("Kyoto National Museum"))
        assertNull(LinkFill.obviousKind("Kyoto Station"))
        assertNull(LinkFill.obviousKind(null))
    }

    @Test fun withoutANameTheTitleIsThePlacesFirstPart() {
        val filled = LinkFill.fill(blank, null, MapsPlace("Yasaka Shrine, Gion, Kyoto", 35.0, 135.77), kindTouched = false)
        assertEquals("Yasaka Shrine", filled.title)
        assertEquals("", LinkFill.fill(blank, "  ", null, kindTouched = false).title)
    }

    @Test fun aMapsLinkPrefersTheNameItWasSharedWithASiteItsOwnTitle() {
        val maps = "https://maps.app.goo.gl/abc"
        val site = "https://www.tavernalefteris.gr/"
        assertEquals("Yasaka Shrine", LinkFill.nameFor(maps, resolved = "八坂神社", shared = "Yasaka Shrine"))
        assertEquals("八坂神社", LinkFill.nameFor(maps, resolved = "八坂神社", shared = null))
        assertEquals("Taverna Lefteris", LinkFill.nameFor(site, resolved = "Taverna Lefteris", shared = "Look at this"))
        assertEquals("Look at this", LinkFill.nameFor(site, resolved = null, shared = "Look at this"))
        // A Maps name loses the address after it; a site's title keeps its commas.
        assertEquals("Yasaka Shrine", LinkFill.nameOf(maps, "Yasaka Shrine, 625 Gionmachi"))
        assertEquals("Eat, Drink, Sleep", LinkFill.nameOf(site, "Eat, Drink, Sleep"))
        assertNull(LinkFill.nameFor(site, null, " "))
    }
}

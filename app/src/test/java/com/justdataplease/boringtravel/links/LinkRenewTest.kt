package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.data.db.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Renew details: only what a link gave is replaced. */
class LinkRenewTest {
    private val site = "https://example.com/menu"
    private val maps = "https://maps.app.goo.gl/abc123"

    private fun item(title: String, place: String = "", lat: Double? = null, lng: Double? = null) =
        PlanItem(id = "i", tripId = "t", title = title, placeName = place, lat = lat, lng = lng, createdAt = 1, updatedAt = 1)

    @Test
    fun `a title the website gave follows its new title`() {
        val out = LinkRenew.apply(item("Old menu"), false, LinkRenew.Look(site, "Old menu", "New menu"), null, null, null)
        assertEquals("New menu", out.title)
    }

    @Test
    fun `a title typed by hand stays`() {
        val out = LinkRenew.apply(item("Lunch with Anna"), false, LinkRenew.Look(site, "Old menu", "New menu"), null, null, null)
        assertEquals("Lunch with Anna", out.title)
    }

    @Test
    fun `a placeholder title gets the name once the link answers`() {
        val out = LinkRenew.apply(item(LinkPlaces.PLACEHOLDER_MAPS), false, null, LinkRenew.Look(maps, null, "Yasaka Shrine, Kyoto"), null, MapsPlace("Yasaka Shrine, Kyoto", 35.0, 135.7))
        assertEquals("Yasaka Shrine", out.title)
        assertEquals(35.0, out.lat!!, 0.0)
        assertEquals("Yasaka Shrine, Kyoto", out.placeName)
    }

    @Test
    fun `the maps link names the item before the website does`() {
        val out = LinkRenew.apply(
            item(""), false,
            LinkRenew.Look(site, null, "Site title"), LinkRenew.Look(maps, null, "Place name"), null, MapsPlace("Place name", 1.0, 2.0),
        )
        assertEquals("Place name", out.title)
    }

    @Test
    fun `a failed look changes nothing`() {
        val before = item("Old menu")
        assertEquals(before, LinkRenew.apply(before, false, LinkRenew.Look(site, "Old menu", null), null, null, null))
        assertEquals(before, LinkRenew.apply(before, false, LinkRenew.Look(site, "Old menu", "Old menu"), null, null, null))
    }

    @Test
    fun `the link's own place follows a moved pin and a picked place stays`() {
        val old = MapsPlace("Cafe", 10.0, 20.0)
        val now = MapsPlace("Cafe (new address)", 10.5, 20.5)
        val fromLink = LinkRenew.apply(item("My title", "Cafe", 10.0, 20.0), false, null, LinkRenew.Look(maps, "Cafe", "Cafe (new address)"), old, now)
        assertEquals(10.5, fromLink.lat!!, 0.0)
        assertEquals("Cafe (new address)", fromLink.placeName)
        assertEquals("My title", fromLink.title)
        val picked = LinkRenew.apply(item("My title", "Somewhere else", 48.0, 2.0), false, null, LinkRenew.Look(maps, "Cafe", "Cafe (new address)"), old, now)
        assertEquals(48.0, picked.lat!!, 0.0)
        assertEquals("Somewhere else", picked.placeName)
    }

    @Test
    fun `a removed place stays removed`() {
        val out = LinkRenew.apply(item("My title"), true, null, LinkRenew.Look(maps, null, "Cafe"), null, MapsPlace("Cafe", 10.0, 20.0))
        assertNull(out.lat)
        assertEquals("", out.placeName)
    }

    @Test
    fun `the place field takes links and leaves names alone`() {
        assertEquals(maps, SharedLinks.inPlaceField(maps)?.url)
        assertEquals("Yasaka Shrine", SharedLinks.inPlaceField("Yasaka Shrine\n$maps")?.name)
        assertEquals("https://maps.app.goo.gl/abc123", SharedLinks.inPlaceField("maps.app.goo.gl/abc123")?.url)
        assertEquals(site, SharedLinks.inPlaceField(site)?.url)
        assertNull(SharedLinks.inPlaceField("St.Tropez"))
        assertNull(SharedLinks.inPlaceField("naxos.gr"))
        assertNull(SharedLinks.inPlaceField("Halki square"))
        assertNull(SharedLinks.inPlaceField("  "))
    }
}

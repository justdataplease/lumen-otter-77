package com.justdataplease.boringtravel.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lists of saved places shared from Google Maps (owner, 17 Nov 2026: "import a trip from a Google Maps user list"). The
 * answer below has the shape Google gave on 17 Nov 2026 for three public lists and the owner's "Seville" list (names,
 * people and ids made up).
 */
class MapsListsTest {
    private val answer = """)]}'
[[["mznoUpXRzRCwLM9endLiBGRe8dn",1,null,1,1],4,[2,1,"https://www.google.com/maps/placelists/list/mznoUpXRzRCwLM9endLiBGRe8dn"],["A. Traveller","https://example.com/a.png","1"],"Seville","Tapas and sights",null,null,
[
 [null,[null,null,"",null,"C. San Basilio, 16, 14004 Córdoba",[null,null,37.8767624,-4.7836871],["967465349572558039","-6613953165144310848"],"/g/1q5bn_k6x"],"Casa de Comidas La Posada","Try the salmorejo",null,null,null,[],[[1],["967465349572558039","-6613953165144310848"]],[1,0],[1,0],null,["A. Traveller","x","1"]],
 [null,[null,null,"The White House, 1600 Pennsylvania Ave NW, Washington, DC 20500",null,"",[null,null,38.8976763,-77.0365298],["1","2"]],"","",null,null,null,[],[[1],["1","2"]],[1,0],[1,0],null,["A. Traveller","x","1"]],
 [null,[null,null,"",null,"",null,["3","4"]],"No pin","",null,null,null,[],[[1],["3","4"]],[1,0],[1,0],null,null],
 [null,[null,null,"",null,"",[null,null,0.0,0.0],["5","6"]],"Null island","",null,null,null,[],[[1],["5","6"]],[1,0],[1,0],null,null],
 [null,[null,null,"",null,"Plaza de España, Sevilla",[null,null,37.3772,-5.9869],["7","8"]],"Plaza de España","",null,null,null,[],[[1],["7","8"]],[1,0],[1,0],null,null]
],
[null,null,null,[10,"10"]],[1701688509,484720000],[1765370565,792988000],7,null,null,null,null,"x",null,null,"https://www.google.com/local/content/rap/report?postId=x"]]"""

    @Test fun aListsPlacesWithTheirPinsNotesAndOwnMapsLinks() {
        val list = MapsLists.parse(answer)!!
        assertEquals("Seville", list.name)
        assertEquals("Tapas and sights", list.description)
        // Places without a pin (or at 0,0) are left out; the list says it holds 7.
        assertEquals(listOf("Casa de Comidas La Posada", "The White House", "Plaza de España"), list.places.map { it.name })
        assertEquals(7, list.total)
        assertTrue(list.more)
        val posada = list.places[0]
        assertEquals(37.8767624, posada.lat, 1e-9)
        assertEquals(-4.7836871, posada.lng, 1e-9)
        assertEquals("C. San Basilio, 16, 14004 Córdoba", posada.address)
        assertEquals("Try the salmorejo", posada.note)
        // The place's number in Maps links is the second half of its id, unsigned.
        assertEquals("https://maps.google.com/?cid=11832790908565240768", posada.mapsUrl)
        // A saved address has no name of its own: its label's first part, and the label as its address.
        val house = list.places[1]
        assertEquals("The White House, 1600 Pennsylvania Ave NW, Washington, DC 20500", house.address)
        assertEquals("", house.note)
    }

    @Test fun anythingElseIsNoList() {
        assertNull(MapsLists.parse(""))
        assertNull(MapsLists.parse(")]}'\n[]"))
        assertNull(MapsLists.parse("<html>Sign in</html>"))
        assertNull(MapsLists.parse(")]}'\n[[null]]"))
    }

    @Test fun theListALinkPointsAt() {
        // Where a shared list's short link passes on to (checked on 17 Nov 2026).
        val shared = "https://www.google.com/maps/@/data=!4m3!11m2!2smznoUpXRzRCwLM9endLiBGRe8dnOzg!3e3!18m1!1e1?utm_source=mstt_0&g_ep=CAESBzI2"
        assertEquals(MapsLists.Ref("mznoUpXRzRCwLM9endLiBGRe8dnOzg"), MapsLists.ref(shared))
        assertEquals(MapsLists.Ref("1qautbCcsQZUe7MFq8owMsL0aEHg"), MapsLists.ref("https://www.google.com/maps/placelists/list/1qautbCcsQZUe7MFq8owMsL0aEHg"))
        assertEquals(
            MapsLists.Ref("-mup0V4zyZUEpvJl_Tvf2UMziCBMGg", "3PoDpW5u7uo"),
            MapsLists.ref("https://www.google.com/maps/placelists/list/-mup0V4zyZUEpvJl_Tvf2UMziCBMGg?token=3PoDpW5u7uo"),
        )
        // A place, a search, a short link not followed yet, another site: no list.
        assertNull(MapsLists.ref("https://www.google.com/maps/place/Yasaka+Shrine/@35.0036,135.7785,17z"))
        assertNull(MapsLists.ref("https://www.google.com/maps/search/?api=1&query=Prado"))
        assertNull(MapsLists.ref("https://maps.app.goo.gl/4XbhDugkXP74C4Vu7"))
        assertNull(MapsLists.ref("https://example.com/maps/placelists/list/1qautbCcsQZUe7MFq8owMsL0aEHg"))
    }

    @Test fun theListIsAskedForAsItsOwnPageAsks() {
        val urls = MapsLists.requestUrls(MapsLists.Ref("1qautbCcsQZUe7MFq8owMsL0aEHg"), session = "s")
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.startsWith("https://www.google.com/maps/preview/entitylist/getlist?") })
        assertTrue(urls[0].contains("!13shttps%3A%2F%2Fwww.google.com%2Fmaps%2Fplacelists%2Flist%2F1qautbCcsQZUe7MFq8owMsL0aEHg!"))
        assertTrue(urls[1].contains("!1m4!1s1qautbCcsQZUe7MFq8owMsL0aEHg!"))
        assertTrue(urls.all { "!4i${MapsLists.MAX_PLACES}!" in it })
        // A list shared with the link only: its token goes with the page's address, and only that way.
        val token = MapsLists.requestUrls(MapsLists.Ref("abcdefghijk", "tok"), session = "s")
        assertEquals(1, token.size)
        assertTrue(token[0].contains("abcdefghijk%3Ftoken%3Dtok"))
        assertFalse(MapsLinks.isShort("https://www.google.com/maps/placelists/list/abcdefghijk"))
    }
}

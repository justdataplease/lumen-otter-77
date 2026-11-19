package com.justdataplease.boringtravel.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsLinksTest {
    private val yasaka =
        "https://www.google.com/maps/place/Yasaka+Shrine/@35.0036559,135.7785534,17z/data=!3m1!4b1!4m6!3m5!1s0x600108e9fe6b81e1:0x6c8c6b8b4bd3c8f!8m2!3d35.0036559!4d135.7785534!16zL20vMDJ5ZmNt?entry=ttu"

    @Test fun whichLinksAreMapsLinks() {
        listOf(
            yasaka,
            "https://maps.google.com/?q=35.0036,135.7785",
            "https://maps.google.co.jp/maps?ll=35.0,135.7&z=15",
            "https://www.google.co.uk/maps/@51.5,-0.12,15z",
            "https://google.com/maps/search/?api=1&query=Yasaka+Shrine",
            "https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA",
            "https://goo.gl/maps/sXnse1erRvkA6aV99",
        ).forEach { assertTrue(it, MapsLinks.isMaps(it)) }
        listOf(
            "https://www.google.com/search?q=yasaka",
            "https://goo.gl/abc",
            "https://www.openstreetmap.org/#map=17/35.0/135.7",
            "https://maps.apple.com/?q=Yasaka",
            "https://notgoogle.com/maps/place/X",
            "not a link",
        ).forEach { assertFalse(it, MapsLinks.isMaps(it)) }
        assertTrue(MapsLinks.isShort("https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"))
        assertTrue(MapsLinks.isShort("https://goo.gl/maps/sXnse1erRvkA6aV99"))
        assertFalse(MapsLinks.isShort(yasaka))
    }

    @Test fun aPlaceLinkGivesItsNameAndThePinNotTheCamera() {
        assertEquals(MapsPlace("Yasaka Shrine", 35.0036559, 135.7785534), MapsLinks.parse(yasaka))
        // The camera (@) and the pin (!3d!4d) can differ; the pin is the place.
        val moved = "https://www.google.com/maps/place/Kiyomizu-dera/@34.99,135.77,15z/data=!4m6!3m5!1s0x0:0x0!8m2!3d34.9948561!4d135.7850463"
        assertEquals(MapsPlace("Kiyomizu-dera", 34.9948561, 135.7850463), MapsLinks.parse(moved))
        // What the Falafel short link opens (real redirect, 2026-11-07).
        val falafel = "https://www.google.com/maps/place/Falafel+M.+Sahyoun/@33.8904447,35.5066505,17z/data=!3m1!4b1!4m6!3m5!1s0x151f16e2123697fd:0x8e6626b678863990!8m2!3d33.8904447!4d35.5066505!16s%2Fg%2F1td7wffc?utm_source=mstt_1&coh=164777&entry=tt&shorturl=1"
        assertEquals(MapsPlace("Falafel M. Sahyoun", 33.8904447, 35.5066505), MapsLinks.parse(falafel))
    }

    @Test fun theCameraWhenThereIsNoPin() {
        assertEquals(MapsPlace("Fushimi Inari Taisha", 34.9671, 135.7727), MapsLinks.parse("https://www.google.com/maps/place/Fushimi+Inari+Taisha/@34.9671,135.7727,16z"))
        assertEquals(MapsPlace(null, 51.5007, -0.1246), MapsLinks.parse("https://www.google.co.uk/maps/@51.5007,-0.1246,15z"))
    }

    @Test fun queryForms() {
        assertEquals(MapsPlace(null, 35.0036, 135.7785), MapsLinks.parse("https://maps.google.com/?q=35.0036,135.7785"))
        assertEquals(MapsPlace(null, 35.0036, 135.7785), MapsLinks.parse("https://maps.google.com/maps?q=loc:35.0036+135.7785"))
        assertEquals(MapsPlace(null, -33.8568, 151.2153), MapsLinks.parse("https://maps.google.com/maps?ll=-33.8568,151.2153&z=16"))
        assertEquals(MapsPlace(null, 35.0036, 135.7785), MapsLinks.parse("https://www.google.com/maps/search/?api=1&query=35.0036%2C135.7785"))
        assertEquals(MapsPlace("Yasaka Shrine", 35.0036, 135.7785), MapsLinks.parse("https://maps.google.com/?q=Yasaka+Shrine@35.0036,135.7785"))
        assertEquals(MapsPlace("Eiffel Tower", null, null), MapsLinks.parse("https://www.google.com/maps/search/?api=1&query=Eiffel%20Tower"))
        // What another short link opens: a search for a point.
        assertEquals(MapsPlace(null, 5.811698, -55.118891), MapsLinks.parse("https://www.google.com/maps/search/5.811698,+-55.118891?entry=tts&g_ep=EgoyMDI1MDEyOS4xIPu8ASoASAFQAw%3D%3D"))
    }

    @Test fun aSharedPlaceWithoutAPinGivesItsNameOnly() {
        // The Maps app's share of an address (real redirect, 2026-11-07): a name, a place id, no coordinates.
        val shared = "https://www.google.com/maps/place/Mollafenari,+Elizi,+Bab-%C4%B1+Ali+Cd.+No:27,+34120+Fatih%2F%C4%B0stanbul,+Turkey/data=!4m2!3m1!1s0x14cab996a54299cb:0x40acb197d028d115?utm_source=mstt_1&entry=gps"
        assertEquals(MapsPlace("Mollafenari, Elizi, Bab-ı Ali Cd. No:27, 34120 Fatih/İstanbul, Turkey", null, null), MapsLinks.parse(shared))
        assertFalse(MapsLinks.parse(shared)!!.hasPoint)
    }

    @Test fun shortLinksAndOtherSitesSayNothingYet() {
        assertNull(MapsLinks.parse("https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"))
        assertNull(MapsLinks.parse("https://example.com/maps/place/X/@1.5,2.5,3z"))
        assertNull(MapsLinks.parse("https://www.google.com/maps"))
        // Out-of-range or zero points aren't places.
        assertNull(MapsLinks.parse("https://www.google.com/maps/@95.5,10.5,3z"))
        assertNull(MapsLinks.parse("https://maps.google.com/?q=0.0,0.0"))
    }

    @Test fun redirectsAndPagesThatHideTheLongLink() {
        val consent = "https://consent.google.com/ml?continue=https://www.google.com/maps/place/Yasaka%2BShrine/@35.0036559,135.7785534,17z&gl=GR&hl=en"
        assertEquals("https://www.google.com/maps/place/Yasaka+Shrine/@35.0036559,135.7785534,17z", MapsLinks.unwrapConsent(consent))
        assertEquals(yasaka, MapsLinks.unwrapConsent(yasaka))
        // With a phone browser's User-Agent the short link answers with an intent:// redirect (real answer, 2026-11-07).
        val intent = "intent://maps.app.goo.gl/9ivZQ71CKMqRPDtQA#Intent;package=com.google.android.gms;" +
            "action=com.google.firebase.dynamiclinks.VIEW_DYNAMIC_LINK;scheme=https;" +
            "S.browser_fallback_url=https://www.google.com/maps/place/Mollafenari,%2BElizi,%2BBab-%25C4%25B1%2BAli%2BCd./data%3D!4m2!3m1!1s0x14cab996a54299cb:0x40acb197d028d115;end;"
        assertEquals("https://www.google.com/maps/place/Mollafenari,+Elizi,+Bab-%C4%B1+Ali+Cd./data=!4m2!3m1!1s0x14cab996a54299cb:0x40acb197d028d115", MapsLinks.fromIntent(intent))
        assertNull(MapsLinks.fromIntent("https://www.google.com/maps"))
        val page = """<html><script>var u = "https:\/\/www.google.com\/maps\/place\/Yasaka+Shrine\/@35.0036559,135.7785534,17z?a=1&b=2";</script>"""
        assertEquals("https://www.google.com/maps/place/Yasaka+Shrine/@35.0036559,135.7785534,17z?a=1&b=2", MapsLinks.findInHtml(page))
        assertEquals(
            "https://www.google.com/maps/place/X/@1.5,2.5,3z?a=1&b=2",
            MapsLinks.findInHtml("""<a href="https://www.google.com/maps/place/X/@1.5,2.5,3z?a=1&amp;b=2">open</a>"""),
        )
        assertNull(MapsLinks.findInHtml("<html>nothing here</html>"))
    }
}

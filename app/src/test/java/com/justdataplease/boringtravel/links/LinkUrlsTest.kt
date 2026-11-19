package com.justdataplease.boringtravel.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkUrlsTest {
    @Test fun bareDomainsGetHttps() {
        assertEquals("https://example.com/", LinkUrls.normalize("example.com"))
        assertEquals("https://www.kyoto.travel/en/see-and-do", LinkUrls.normalize("  www.kyoto.travel/en/see-and-do "))
        assertEquals("https://example.com:8080/menu", LinkUrls.normalize("example.com:8080/menu"))
        assertEquals("https://cdn.example.com/a", LinkUrls.normalize("//cdn.example.com/a"))
    }

    @Test fun webLinksAreKeptAndTidied() {
        assertEquals("https://example.com/Menu?x=1", LinkUrls.normalize("HTTPS://Example.COM/Menu?x=1"))
        assertEquals("http://old-site.gr/", LinkUrls.normalize("http://old-site.gr"))
        val maps = "https://www.google.com/maps/place/Yasaka+Shrine/@35.0036559,135.7785534,17z/data=!3m1!4b1!4m6!3m5!1s0x600108e9fe6b81e1:0x6c8c6b8b4bd3c8f!8m2!3d35.0036559!4d135.7785534"
        assertEquals(maps, LinkUrls.normalize(maps))
    }

    @Test fun otherSchemesAndNonsenseAreRefused() {
        assertNull(LinkUrls.normalize("javascript:alert(1)"))
        assertNull(LinkUrls.normalize("mailto:hey@example.com"))
        assertNull(LinkUrls.normalize("ftp://files.example.com/a"))
        assertNull(LinkUrls.normalize("intent://maps.app.goo.gl/x#Intent;end"))
        assertNull(LinkUrls.normalize("file:///sdcard/a.html"))
        assertNull(LinkUrls.normalize("http:example.com"))
        assertNull(LinkUrls.normalize("hello"))
        assertNull(LinkUrls.normalize("localhost:8080"))
        assertNull(LinkUrls.normalize("example.c"))
        assertNull(LinkUrls.normalize("two words"))
        assertNull(LinkUrls.normalize(""))
        assertNull(LinkUrls.normalize("   "))
        assertNull(LinkUrls.normalize(null))
    }

    @Test fun aLinkIsTakenOutOfSharedText() {
        // What Google Maps' Share puts on the clipboard.
        assertEquals("https://maps.app.goo.gl/9ivZQ71CKMqRPDtQA", LinkUrls.normalize("Yasaka Shrine\nhttps://maps.app.goo.gl/9ivZQ71CKMqRPDtQA"))
        assertEquals("https://example.com/menu", LinkUrls.normalize("See https://example.com/menu."))
        assertNull(LinkUrls.normalize("call me at 555 1234"))
    }

    @Test fun domainHostAndFetchAddress() {
        assertEquals("kyoto.travel", LinkUrls.domain("https://www.kyoto.travel/en/"))
        assertEquals("maps.app.goo.gl", LinkUrls.domain("https://maps.app.goo.gl/abc"))
        assertEquals("www.kyoto.travel", LinkUrls.host("https://www.kyoto.travel/en/"))
        assertEquals("not a link", LinkUrls.domain("not a link"))
        assertEquals("https://old-site.gr/a?b=1", LinkUrls.forFetch("http://old-site.gr/a?b=1"))
        assertEquals("https://example.com/", LinkUrls.forFetch("https://example.com/"))
    }

    @Test fun theFaviconComesFromGooglesService() {
        assertEquals("https://www.google.com/s2/favicons?domain=www.kyoto.travel&sz=128", LinkUrls.favicon("www.kyoto.travel"))
        assertEquals("https://www.google.com/s2/favicons?domain=maps.google.com&sz=128", LinkUrls.favicon(MapsLinks.FAVICON_HOST))
    }
}

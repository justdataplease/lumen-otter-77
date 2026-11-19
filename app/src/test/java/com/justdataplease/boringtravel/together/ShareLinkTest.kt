package com.justdataplease.boringtravel.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkTest {
    private val link = ShareLink("trip-1", "1AbCdEfGhIjKlMnOp", "m-1", "Maria José", "Argentina & Patagonia")

    @Test fun roundTripsThroughTheUrl() {
        assertEquals(link, ShareLink.find(link.url))
        assertTrue(link.url.startsWith("${ShareLink.WEB}#"))
    }

    @Test fun isFoundInsideAChatMessage() {
        val message = "Join my trip on BoringTravel, so we can plan it together:\n${link.url}\nsee you!"
        assertEquals(link, ShareLink.find(message))
    }

    @Test fun theAppSchemeWorksToo() {
        val url = link.url.replace(ShareLink.WEB, ShareLink.APP)
        assertEquals(link, ShareLink.find(url))
    }

    @Test fun rejectsOtherLinksAndBadIds() {
        assertNull(ShareLink.find("https://example.com/apps/boringtravel/join#v=1&t=a&f=12345&m=b"))
        assertNull(ShareLink.find("${ShareLink.WEB}#v=1&t=a&f=12&m=b"))
        assertNull(ShareLink.find("${ShareLink.WEB}#v=1&t=a/../b&f=123456&m=b"))
        assertNull(ShareLink.find("${ShareLink.WEB}#v=2&t=a&f=123456&m=b"))
        assertNull(ShareLink.find(null))
    }

    @Test fun namesAreOneShortLine() {
        val l = ShareLink.find("${ShareLink.WEB}#v=1&t=a&f=123456&m=b&n=" + "A%0AB".repeat(30))!!
        assertTrue(l.name.length <= ShareCodec.MAX_NAME)
        assertTrue('\n' !in l.name)
    }
}

package com.justdataplease.boringtravel.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteLinksTest {
    @Test fun offersAWebAddressInTheMessage() {
        assertEquals("https://wanderingfeet.tumblr.com/", SiteLinks.offer("Copy my blog https://wanderingfeet.tumblr.com/ into trips please."))
        assertEquals("https://wanderingfeet.tumblr.com/", SiteLinks.offer("my old blog is wanderingfeet.tumblr.com"))
        assertEquals("https://www.tumblr.com/wanderingfeet", SiteLinks.offer("see www.tumblr.com/wanderingfeet, thanks"))
        assertEquals("https://travels.example.org/2019/lisbon", SiteLinks.offer("(https://travels.example.org/2019/lisbon)"))
        assertEquals("wanderingfeet.tumblr.com", SiteLinks.host("https://wanderingfeet.tumblr.com/"))
    }

    @Test fun leavesDocsMapsAndPlainTextToThePlanner() {
        assertNull(SiteLinks.offer("Madrid 3-7 June, Prado on day 2, flight IB123"))
        assertNull(SiteLinks.offer("https://docs.google.com/document/d/abcdefghijk/edit"))
        assertNull(SiteLinks.offer("https://drive.google.com/file/d/abc/view"))
        assertNull(SiteLinks.offer("Dinner here https://maps.app.goo.gl/AbCdEf123"))
        assertNull(SiteLinks.offer("mail me at me@wanderingfeet.tumblr.com"))
        assertEquals(
            "https://wanderingfeet.tumblr.com/",
            SiteLinks.offer("Dinner here https://maps.app.goo.gl/AbCdEf123 and my blog wanderingfeet.tumblr.com"),
        )
    }
}

package com.justdataplease.boringtravel.ui.ai

import com.justdataplease.boringtravel.ai.ItineraryImport
import com.justdataplease.boringtravel.ai.PictureReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pictures and links attached to a planner message, added to it on Send (owner, 18 Nov 2026). */
class AttachmentsTest {
    @Test fun theMessageIsTheWordsThenTheLinksThenWhatThePicturesSay() {
        val a = Attachments(listOf("content://p/1"), listOf("https://maps.app.goo.gl/abc", "https://example.com/blog/rome"))
        assertEquals(
            "Make 3 days from these\n\nhttps://maps.app.goo.gl/abc\nhttps://example.com/blog/rome\n\nFrom a picture:\nColosseum (Rome)",
            a.message("  Make 3 days from these ", "From a picture:\nColosseum (Rome)"),
        )
        // Before the pictures are read, and with nothing typed: only what there is.
        assertEquals("https://maps.app.goo.gl/abc\nhttps://example.com/blog/rome", a.message("", null))
        assertEquals("Lisbon in May", Attachments().message("Lisbon in May", null))
    }

    @Test fun theMessageStaysWithinTheLimit() {
        val a = Attachments(listOf("content://p/1"))
        assertEquals(ItineraryImport.MAX_CHARS, a.message("x", "y".repeat(ItineraryImport.MAX_CHARS)).length)
    }

    @Test fun picturesOnceEachAndAtMostTen() {
        val many = (1..14).map { "content://p/$it" }
        val a = Attachments().withPictures(listOf("content://p/1", "content://p/1")).withPictures(many)
        assertEquals(PictureReading.MAX_PICTURES, a.pictures.size)
        assertEquals((1..10).map { "content://p/$it" }, a.pictures)
        assertEquals(9, a.withoutPicture("content://p/4").pictures.size)
    }

    @Test fun linksOnceEachAndAtMostFive() {
        var a = Attachments().withLink("https://a.com/").withLink("https://a.com/")
        assertEquals(1, a.links.size)
        (1..8).forEach { a = a.withLink("https://site$it.com/") }
        assertEquals(Attachments.MAX_LINKS, a.links.size)
        assertTrue(a.withoutLink("https://a.com/").links.none { it == "https://a.com/" })
    }

    @Test fun aLinkIsTheFirstWebAddressInWhatWasPasted() {
        assertEquals("https://maps.app.goo.gl/xyz", Attachments.link("Yasaka Shrine https://maps.app.goo.gl/xyz"))
        assertEquals("https://example.com/menu", Attachments.link("example.com/menu"))
        assertNull(Attachments.link("the temple near the station"))
        assertNull(Attachments.link("javascript:alert(1)"))
    }

    @Test fun sentMessagesShowTheLinksByDomain() {
        assertEquals(listOf("maps.app.goo.gl", "example.com"), Attachments(links = listOf("https://maps.app.goo.gl/x", "https://www.example.com/a")).domains)
        assertTrue(Attachments().isEmpty)
    }
}

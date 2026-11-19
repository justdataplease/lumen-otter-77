package com.justdataplease.boringtravel.site

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatting written as styles, tables, Google's link wrapper, other scripts and emoji; links offered and reblogs. */
class StyledHtmlTest {
    private val base = "https://notes.example.com/p/1"

    @Test fun stylesCountLikeTags() {
        val css = CssFormats.of(".c1{font-weight:700}.c2{font-style:italic;font-weight:400}p.c3,.c4{text-decoration:underline line-through}.x>li:before{content:\"*\"}")
        fun tags(html: String) = css.of(Jsoup.parse(html).body().child(0))
        assertEquals(setOf("b"), tags("<span class=\"c1\">x</span>"))
        assertEquals(setOf("i"), tags("<span class=\"c2\">x</span>"))
        assertEquals(setOf("u", "s"), tags("<span class=\"c4\">x</span>"))
        assertEquals(setOf("b", "i"), tags("<span class=\"c1\" style=\"font-style: oblique\">x</span>"))
        assertEquals(emptySet<String>(), tags("<span class=\"x\">x</span>"))
        assertEquals(setOf("b"), CssFormats.declarations("font-weight: bold !important"))
        assertEquals(emptySet<String>(), CssFormats.declarations("font-weight:300;color:red"))
    }

    @Test fun styledTextTablesAndLinks() {
        val blocks = HtmlBlocks.parse(
            """
            <style>.b{font-weight:700}.u{text-decoration:underline}</style>
            <p><span style="font-weight:bold">Bold</span> and <b><span class="b">not twice</span></b>, <span class="u"><a href="https://www.google.com/url?q=https://ferries.example.org/&amp;sa=D">link</a></span></p>
            <table><thead><tr><th>Day</th><th></th><th>Km</th></tr></thead><tbody><tr><td><p>1</p><p>Chania</p></td><td>—</td><td><table><tr><td>12</td></tr></table></td></tr></tbody></table>
            """.trimIndent(),
            base,
        )
        assertEquals(
            listOf(
                SiteBlock.Text("<b>Bold</b> and <b>not twice</b>, <a href=\"https://ferries.example.org/\">link</a>"),
                SiteBlock.Text("Day · Km"),
                SiteBlock.Text("1 Chania · — · 12"),
            ),
            blocks,
        )
    }

    @Test fun greekRightToLeftAndEmojiStayAsWritten() {
        val blocks = HtmlBlocks.parse("<p>Καλημέρα από τα <b>Χανιά</b> 🌊</p><p dir=\"rtl\">שלום עולם</p><p>مرحبا بالعالم</p><h2>Ημέρα 3 — 5 Αυγούστου</h2>", base)
        assertEquals(
            listOf(
                SiteBlock.Text("Καλημέρα από τα <b>Χανιά</b> 🌊"),
                SiteBlock.Text("שלום עולם"),
                SiteBlock.Text("مرحبا بالعالم"),
                SiteBlock.Heading("Ημέρα 3 — 5 Αυγούστου", 2),
            ),
            blocks,
        )
    }

    @Test fun aVeryLongPostWithManyPictures() {
        val html = (1..150).joinToString("") { "<p>Paragraph $it ${"word ".repeat(50)}</p><img src=\"https://cdn.example.com/$it.jpg\">" }
        val blocks = HtmlBlocks.parse(html, base)
        assertEquals(300, blocks.size)
        assertEquals((1..150).map { "https://cdn.example.com/$it.jpg" }, blocks.filterIsInstance<SiteBlock.Picture>().map { it.url })
    }

    @Test fun linksOfferedForCopying() {
        val doc = "https://docs.google.com/document/d/1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789/edit"
        assertEquals(doc, SiteLinks.offer("my diary: $doc"))
        assertTrue(SiteLinks.isDoc(doc))
        assertNull(SiteLinks.offer("https://docs.google.com/spreadsheets/d/abc/edit"))
        assertTrue(SiteLinks.looksLikeSite(doc))
        assertTrue(SiteLinks.looksLikeSite("https://wanderingfeet.tumblr.com/post/123"))
        assertTrue(SiteLinks.looksLikeSite("https://notes.example.com/"))
        assertTrue(SiteLinks.looksLikeSite("https://notes.example.com/blog"))
        assertFalse(SiteLinks.looksLikeSite("https://www.example.com/restaurants/menu"))
        assertFalse(SiteLinks.looksLikeSite("https://maps.app.goo.gl/AbC123"))
        assertFalse(SiteLinks.looksLikeSite("https://www.google.com/"))
    }

    @Test fun reblogsAsTheReadApiMarksThem() {
        fun o(s: String) = Json.parseToJsonElement(s).jsonObject
        assertFalse(TumblrApi.isReblog(o("""{"id":"1"}"""), "wanderingfeet"))
        assertTrue(TumblrApi.isReblog(o("""{"reblogged-from-name":"safety","reblogged-root-name":"safety"}"""), "wanderingfeet"))
        assertTrue(TumblrApi.isReblog(o("""{"reblogged-from-name":"wanderingfeet","reblogged-root-name":"someone"}"""), "wanderingfeet"))
        assertFalse(TumblrApi.isReblog(o("""{"reblogged-from-name":"friend","reblogged-root-name":"WanderingFeet"}"""), "wanderingfeet"))
    }
}

package com.justdataplease.boringtravel.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaTagsTest {
    private val page = "https://www.kyoto.travel/en/see-and-do/yasaka.html"

    @Test fun openGraphTagsAreRead() {
        val html = """
            <!doctype html><html><head>
            <meta charset="utf-8">
            <title>Ignored when og:title is there</title>
            <meta property="og:site_name" content="Kyoto City Official Travel Guide">
            <meta property="og:title" content="Yasaka Shrine">
            <meta property="og:image" content="https://www.kyoto.travel/img/yasaka.jpg">
            </head><body></body></html>
        """.trimIndent()
        assertEquals(
            PageMeta("https://www.kyoto.travel/img/yasaka.jpg", "Yasaka Shrine", "Kyoto City Official Travel Guide"),
            MetaTags.parse(html, page),
        )
    }

    @Test fun contentBeforePropertySingleQuotesNoQuotesAndUpperCase() {
        val html = """
            <META content='https://cdn.example.com/a.jpg' property='og:image' />
            <meta
               content="Lunch &amp; views"
               property="og:title">
            <meta name=twitter:image content=https://cdn.example.com/b.jpg>
        """.trimIndent()
        val meta = MetaTags.parse(html, page)
        assertEquals("https://cdn.example.com/a.jpg", meta.imageUrl)
        assertEquals("Lunch & views", meta.title)
        assertNull(meta.siteName)
    }

    @Test fun secureUrlThenOgImageThenTwitter() {
        val both = """<meta property="og:image" content="http://img.example.com/x.jpg"><meta property="og:image:secure_url" content="https://img.example.com/secure.jpg">"""
        assertEquals("https://img.example.com/secure.jpg", MetaTags.parse(both, page).imageUrl)
        val twitterOnly = """<meta name="twitter:image:src" content="https://pbs.example.com/t.jpg"><meta name="twitter:title" content="From Twitter">"""
        assertEquals(PageMeta("https://pbs.example.com/t.jpg", "From Twitter"), MetaTags.parse(twitterOnly, page))
        // Twitter's tag only when there is no og:image, wherever it stands.
        val order = """<meta name="twitter:image" content="/t.jpg"><meta property="og:image" content="/og.jpg">"""
        assertEquals("https://www.kyoto.travel/og.jpg", MetaTags.parse(order, page).imageUrl)
    }

    @Test fun relativeAndProtocolRelativeImagesResolveAgainstTheFinalAddress() {
        fun image(ref: String, base: String = page) = MetaTags.parse("""<meta property="og:image" content="$ref">""", base).imageUrl
        assertEquals("https://www.kyoto.travel/img/a.jpg", image("/img/a.jpg"))
        assertEquals("https://www.kyoto.travel/en/see-and-do/a.jpg", image("a.jpg"))
        assertEquals("https://www.kyoto.travel/en/img/a.jpg", image("../img/a.jpg"))
        assertEquals("https://cdn.example.com/a.jpg", image("//cdn.example.com/a.jpg"))
        assertEquals("https://m.site.com/i/a%20b.jpg?w=1200&h=630", image("/i/a b.jpg?w=1200&amp;h=630", "https://m.site.com/after/redirect"))
        // Plain http pictures are asked for over https (Android refuses plain http).
        assertEquals("https://old.example.com/a.jpg", image("http://old.example.com/a.jpg"))
        assertNull(image("data:image/png;base64,iVBORw0KGgo="))
        assertNull(image("   "))
    }

    @Test fun titleFallsBackToTheTitleTagAndQuotesInsideValuesAreKept() {
        val html = """
            <head><title>
               Caf&eacute; Lefteris &#8211; Naxos &#x2014; &quot;best&quot; &#39;moussaka&#39;
            </title>
            <meta name="description" content="a > b, and 'quoted' words">
            <meta property="og:image" content="https://x.example.com/a.jpg?q=1&amp;r=2">
        """.trimIndent()
        val meta = MetaTags.parse(html, page)
        assertEquals("Café Lefteris – Naxos — \"best\" 'moussaka'", meta.title)
        assertEquals("https://x.example.com/a.jpg?q=1&r=2", meta.imageUrl)
    }

    @Test fun emptyValuesAndPagesWithoutTagsGiveNothing() {
        assertEquals(PageMeta(), MetaTags.parse("<html><body>Hello</body></html>", page))
        assertEquals(PageMeta(), MetaTags.parse("""<meta property="og:image" content=""><meta property="og:title" content="   ">""", page))
        assertEquals(PageMeta(), MetaTags.parse("", page))
        // The first of a repeated tag wins.
        assertEquals("https://a.example.com/1.jpg", MetaTags.parse("""<meta property="og:image" content="https://a.example.com/1.jpg"><meta property="og:image" content="https://a.example.com/2.jpg">""", page).imageUrl)
    }

    @Test fun longTitlesAreCutAndSpacesSqueezed() {
        val meta = MetaTags.parse("""<meta property="og:title" content="  ${"word ".repeat(80)} ">""", page)
        assertEquals(200, meta.title!!.length)
        assertEquals("A B", MetaTags.parse("<title>A\n\t  B</title>", page).title)
    }

    @Test fun charsetFromTheHeaderElseThePage() {
        assertEquals("UTF-8", MetaTags.charset("text/html; charset=UTF-8", "<meta charset=\"iso-8859-7\">"))
        assertEquals("iso-8859-7", MetaTags.charset("text/html", "<head><meta charset=\"iso-8859-7\">"))
        assertEquals("windows-1253", MetaTags.charset(null, "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1253\">"))
        assertNull(MetaTags.charset(null, "<head></head>"))
    }

    @Test fun entities() {
        assertEquals("Tom & Jerry's «café» ©", MetaTags.decode("Tom &amp; Jerry&#x27;s &laquo;café&raquo; &copy;"))
        assertEquals("&unknown; stays, &#0; too", MetaTags.decode("&unknown; stays, &#0; too"))
        assertEquals("😀", MetaTags.decode("&#128512;"))
        assertEquals("Zürich, São Paulo, Çeşme, Straße", MetaTags.decode("Z&uuml;rich, S&atilde;o Paulo, &Ccedil;eşme, Stra&szlig;e"))
    }
}

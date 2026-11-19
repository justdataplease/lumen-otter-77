package com.justdataplease.boringtravel.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiCreditsTest {
    /** Wikipedia's answer for one Commons file's `extmetadata`; null fields are left out. */
    private fun answer(
        artist: String? = null, license: String? = null, url: String? = null, attribution: String? = null, nonFree: String? = null,
    ): String {
        fun field(name: String, value: String?) = value?.let { """"$name":{"value":${quote(it)},"source":"commons-desc-page"}""" }
        val meta = listOfNotNull(
            field("Artist", artist), field("LicenseShortName", license), field("LicenseUrl", url),
            field("AttributionRequired", attribution), field("NonFree", nonFree),
        ).joinToString(",")
        return """{"batchcomplete":"","query":{"normalized":[{"from":"File:Yasaka_jinja.jpg","to":"File:Yasaka jinja.jpg"}],""" +
            """"pages":{"-1":{"ns":6,"title":"File:Yasaka jinja.jpg","missing":"","known":"","imagerepository":"shared",""" +
            """"imageinfo":[{"extmetadata":{$meta}}]}}}}"""
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test fun theAuthorAndLicenceComeFromCommons() {
        val a = answer(
            artist = """<a href="//commons.wikimedia.org/wiki/User:Jane_Doe" title="User:Jane Doe">Jane Doe</a>""",
            license = "CC BY-SA 4.0", url = "https://creativecommons.org/licenses/by-sa/4.0", attribution = "true",
        )
        val c = WikiCredits.parse(a, "Yasaka_jinja.jpg")!!
        assertEquals(WikiCredit("Jane Doe", "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0", "Yasaka_jinja.jpg"), c)
        assertEquals("Photo: Jane Doe, CC BY-SA 4.0 · Wikipedia", WikiCredits.line(c.author, c.license))
        assertEquals("https://en.wikipedia.org/wiki/File:Yasaka_jinja.jpg", WikiCredits.fileUrl("Yasaka jinja.jpg"))
        // A protocol-relative licence page gets https.
        assertEquals("https://creativecommons.org/licenses/by/2.0", WikiCredits.parse(answer("Kim", "CC BY 2.0", "//creativecommons.org/licenses/by/2.0"), "f.jpg")!!.licenseUrl)
    }

    @Test fun commonsHtmlBecomesOnePlainLine() {
        assertEquals("Jane Doe", WikiCredits.plain("""<span class="fn"><a href="x">Jane</a>  Doe</span>"""))
        assertEquals("Ó Briain & Sons — Kraków", WikiCredits.plain("&#211; Briain &amp; Sons &#8212; Krak&#xF3;w"))
        // An entity it doesn't know stays as written.
        assertEquals("&eacute;t&eacute;", WikiCredits.plain("&eacute;t&eacute;"))
        assertEquals("Line one Line two", WikiCredits.plain("Line one<br/>\nLine two"))
        assertEquals("a < b", WikiCredits.plain("a &lt; b"))
        // A long credit is cut at a word.
        val long = "Photographed by the Kyoto Prefectural Board of Education Department of Cultural Properties Protection"
        val short = WikiCredits.short(long)
        assertTrue(short, short.length <= WikiCredits.MAX_AUTHOR + 1 && short.endsWith("…"))
        assertTrue(short, long.startsWith(short.removeSuffix("…")))
        assertEquals("Jane Doe", WikiCredits.short("Jane Doe"))
    }

    @Test fun onlyReusableLicencesAreShown() {
        listOf(
            "CC BY-SA 4.0", "CC BY 2.0", "CC BY-SA 3.0 de", "CC0", "Public domain", "PD-user", "GFDL",
            "Attribution", "Copyrighted free use", "FAL", "CC BY-SA 2.5,2.0,1.0",
        ).forEach { assertTrue(it, WikiCredits.reusable(it)) }
        listOf("CC BY-NC-SA 2.0", "CC BY-ND 4.0", "CC BY-NC 3.0", "Fair use", "All rights reserved", "", "Unknown").forEach {
            assertFalse(it, WikiCredits.reusable(it))
        }
        // Non-free, or no licence at all: no credit, so the photo is never shown.
        assertNull(WikiCredits.parse(answer("Jane Doe", "CC BY-SA 4.0", nonFree = "true"), "f.jpg"))
        assertNull(WikiCredits.parse(answer("Jane Doe", "CC BY-NC-SA 2.0"), "f.jpg"))
        assertNull(WikiCredits.parse(answer(artist = "Jane Doe"), "f.jpg"))
        assertNull(WikiCredits.parse("<html>busy</html>", "f.jpg"))
        assertNull(WikiCredits.parse("""{"query":{"pages":{"-1":{"ns":6,"title":"File:f.jpg","missing":""}}}}""", "f.jpg"))
    }

    @Test fun anAttributionLicenceNeedsItsAuthorPublicDomainDoesNot() {
        assertNull(WikiCredits.parse(answer(license = "CC BY-SA 4.0"), "f.jpg"))
        assertNull(WikiCredits.parse(answer(artist = "<span></span>", license = "CC BY 4.0", attribution = "true"), "f.jpg"))
        val pd = WikiCredits.parse(answer(license = "Public domain", attribution = "false"), "f.jpg")!!
        assertNull(pd.author)
        assertEquals("Photo: Public domain · Wikipedia", WikiCredits.line(pd.author, pd.license))
        assertEquals("Photo: Jo, CC0 · Wikipedia", WikiCredits.parse(answer("Jo", "CC0"), "f.jpg")!!.let { WikiCredits.line(it.author, it.license) })
    }

    @Test fun theQueryAsksWikipediaForTheFilesMetadataOnly() {
        val url = WikiCredits.queryUrl("Yasaka_jinja.jpg")
        assertTrue(url, url.startsWith("https://en.wikipedia.org/w/api.php?action=query&titles=File"))
        listOf("prop=imageinfo", "iiprop=extmetadata", "LicenseShortName", "Artist", "format=json").forEach { assertTrue("$url has $it", url.contains(it)) }
    }
}

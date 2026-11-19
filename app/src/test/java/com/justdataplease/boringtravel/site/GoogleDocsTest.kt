package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.doc.ListStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/**
 * Google Docs on synthetic exports written in the shape the export sent on 2026-11-12 (`<style>` classes for bold and
 * italic, `google.com/url?q=` links, comments at the end, pictures as base64 `data:` addresses); the words are made up.
 */
class GoogleDocsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-ab"

    @Test fun everyFormOfTheLink() {
        val doc = GoogleDocs.Address.Doc(id)
        for (form in listOf("edit", "edit?usp=sharing", "view", "preview", "mobilebasic", "export?format=html", "pub", "")) {
            assertEquals(form, doc, GoogleDocs.address("https://docs.google.com/document/d/$id/$form"))
        }
        assertEquals(doc, GoogleDocs.address("https://docs.google.com/document/u/1/d/$id/edit#heading=h.x"))
        val pub = "2PACX-1vSNtCNi2W2usV9Y0AohZoCDzxu6PpZ_8nHff"
        assertEquals(GoogleDocs.Address.Published(pub), GoogleDocs.address("https://docs.google.com/document/d/e/$pub/pub"))
        assertNull(GoogleDocs.address("https://docs.google.com/spreadsheets/d/$id/edit"))
        assertNull(GoogleDocs.address("https://docs.google.com/document/d/short/edit"))
        assertNull(GoogleDocs.address("https://drive.google.com/file/d/$id/view"))
        assertEquals("https://docs.google.com/document/d/$id/export?format=html", GoogleDocs.exportUrl(id))
        assertEquals("https://docs.google.com/document/d/$id", GoogleDocs.canonical(doc))
    }

    @Test fun signInAndTitles() {
        assertTrue(GoogleDocs.isSignIn("https://accounts.google.com/ServiceLogin?service=wise&continue=x"))
        assertFalse(GoogleDocs.isSignIn("https://docs.google.com/document/d/$id/export?format=html"))
        assertEquals("Kyoto-diary", GoogleDocs.titleFromDisposition("attachment; filename=\"Kyoto-diary.html\""))
        assertEquals(
            "Ταξίδι στην Κρήτη",
            GoogleDocs.titleFromDisposition("attachment; filename=\"Taxidi.html\"; filename*=UTF-8''%CE%A4%CE%B1%CE%BE%CE%AF%CE%B4%CE%B9%20%CF%83%CF%84%CE%B7%CE%BD%20%CE%9A%CF%81%CE%AE%CF%84%CE%B7.html"),
        )
        assertNull(GoogleDocs.titleFromDisposition(null))
        assertEquals("Detailed Itinerary", GoogleDocs.titleFromDisposition("attachment; filename=\"Detailed Itinerary.docx.html\""))
    }

    @Test fun anExportBecomesOnePostWithItsFormattingAndPictures() {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(png)
        val html = """
            <html><head><meta content="text/html; charset=UTF-8" http-equiv="content-type"><style type="text/css">
            .lst-kix_list_1-0>li:before{content:"\0025cf   "}ul.lst-kix_list_1-0{list-style-type:none}
            .c1{font-weight:700}.c2{font-style:italic}.c3{color:#1155cc;text-decoration:underline}.c4{text-decoration:line-through}
            .c9{font-weight:400}.title{font-size:26pt}
            </style></head><body class="c8 doc-content">
            <p class="title"><span>Crete, slowly</span></p>
            <p class="c0"><span class="c9">Day one in </span><span class="c1">Chania</span><span class="c9">, </span><span class="c2">windy</span><sup><a href="#cmnt1" id="cmnt_ref1">[a]</a></sup></p>
            <p class="c0"><span class="c3"><a class="c5" href="https://www.google.com/url?q=https://www.example.org/ferry&amp;sa=D&amp;source=editors&amp;ust=1">the ferry</a></span><span class="c4"> was late</span></p>
            <p class="c0"><span style="overflow: hidden; display: inline-block; width: 624px;"><img alt="" src="data:image/png;base64,$b64" style="width: 624px"></span></p>
            <ul class="c6 lst-kix_list_1-0 start"><li class="c0 li-bullet-0"><span class="c9">Boots</span></li></ul>
            <table class="c7"><tr><td class="c10"><p><span>Day</span></p></td><td><p><span>Km</span></p></td></tr><tr><td><p><span>1</span></p></td><td><p><span>12</span></p></td></tr></table>
            <div><p class="c0"><a href="#cmnt_ref1" id="cmnt1">[a]</a><span class="c9">A comment from a friend</span></p></div>
            </body></html>
        """.trimIndent()
        val pictures = InlinePictures(tmp.newFolder("pictures"))
        val text = pictures.read(html.byteInputStream(), Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        val url = "https://docs.google.com/document/d/$id"
        val post = GoogleDocs.post(text, url, "Fallback title")!!
        assertEquals("Crete, slowly", post.title)
        val placeholder = pictures.files.keys.single()
        assertEquals(
            listOf(
                SiteBlock.Text("Day one in <b>Chania</b>, <i>windy</i>"),
                SiteBlock.Text("<a href=\"https://www.example.org/ferry\">the ferry</a><s> was late</s>"),
                SiteBlock.Picture(placeholder),
                SiteBlock.Text("Boots", ListStyle.Bullet),
                SiteBlock.Text("Day · Km"),
                SiteBlock.Text("1 · 12"),
            ),
            post.blocks,
        )
        assertTrue(png.contentEquals(pictures.files.getValue(placeholder).readBytes()))
    }

    @Test fun aPublishedPage() {
        val html = """
            <html><head><title>Crete, slowly - Google Docs</title></head><body>
            <div id="banners">Published using Google Docs</div><div id="title">Crete, slowly</div>
            <div id="contents"><style type="text/css">.c1{font-weight:700}</style><p><span class="c1">Bold</span> words</p>
            <p><img src="https://lh7-rt.googleusercontent.com/docsz/AD_4nX?key=k"></p></div><div id="footer">Report abuse</div>
            </body></html>
        """.trimIndent()
        val post = GoogleDocs.post(html, "https://docs.google.com/document/d/e/2PACX-abc", null)!!
        assertEquals("Crete, slowly", post.title)
        assertEquals(listOf(SiteBlock.Text("<b>Bold</b> words"), SiteBlock.Picture("https://lh7-rt.googleusercontent.com/docsz/AD_4nX?key=k")), post.blocks)
        assertEquals("Crete, slowly", GoogleDocs.pageTitle(html))
    }
}

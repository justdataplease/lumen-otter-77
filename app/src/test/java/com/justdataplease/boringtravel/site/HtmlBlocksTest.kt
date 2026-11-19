package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.doc.ListStyle
import com.justdataplease.boringtravel.doc.RichHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic posts (made up for these tests): HTML in, note blocks out, words and order exactly as written. */
class HtmlBlocksTest {
    private val base = "https://wanderingfeet.example.com/post/1"

    private fun parse(html: String) = HtmlBlocks.parse(html, base)

    @Test fun paragraphsKeepTheirWordsFormattingAndLinks() {
        val blocks = parse(
            """
            <p>We landed in <b>Kyoto</b> at   dawn,
               <i>tired</i> and <span style="color:red">happy</span>.</p>
            <p>Dinner at <a href="/places/ramen">a tiny ramen bar</a> &amp; a walk by the <u>river</u>.</p>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                SiteBlock.Text("We landed in <b>Kyoto</b> at dawn, <i>tired</i> and happy."),
                SiteBlock.Text("Dinner at <a href=\"https://wanderingfeet.example.com/places/ramen\">a tiny ramen bar</a> &amp; a walk by the <u>river</u>."),
            ),
            blocks,
        )
    }

    @Test fun headingsQuotesListsAndDividersInPageOrder() {
        val blocks = parse(
            """
            <h1>Day one</h1>
            <p>Morning.</p>
            <blockquote><p>The best ramen of my life.</p><p>Said everyone.</p></blockquote>
            <h3>Packing list</h3>
            <ul><li>Passport</li><li>Rail pass<ol><li>Seat one</li></ol></li></ul>
            <hr>
            <h5>Small print</h5>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                SiteBlock.Heading("Day one", 1),
                SiteBlock.Text("Morning."),
                SiteBlock.Quote("The best ramen of my life.<br><br>Said everyone."),
                SiteBlock.Heading("Packing list", 3),
                SiteBlock.Text("Passport", ListStyle.Bullet),
                SiteBlock.Text("Rail pass", ListStyle.Bullet),
                SiteBlock.Text("Seat one", ListStyle.Number),
                SiteBlock.Divider,
                SiteBlock.Heading("Small print", 5),
            ),
            blocks,
        )
    }

    @Test fun picturesComeAtTheirLargestSizeWithCaptionsInOrder() {
        val blocks = parse(
            """
            <p>Before the temple.</p>
            <div class="npf_row"><figure class="tmblr-full"><img src="https://media.example.com/a/s640x960/a.jpg"
              srcset="https://media.example.com/a/s75x75/a.jpg 75w, https://media.example.com/a/s1280x1920/a.jpg 1280w, https://media.example.com/a/s640x960/a.jpg 640w"></figure></div>
            <figure class="wp-block-image"><img src="/small.jpg" data-orig-file="https://wanderingfeet.example.com/full.jpg"><figcaption>The <b>gate</b> at noon</figcaption></figure>
            <p>After.</p>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                SiteBlock.Text("Before the temple."),
                SiteBlock.Picture("https://media.example.com/a/s1280x1920/a.jpg"),
                SiteBlock.Picture("https://wanderingfeet.example.com/full.jpg", "The gate at noon"),
                SiteBlock.Text("After."),
            ),
            blocks,
        )
    }

    @Test fun aGalleryKeepsItsPicturesAndItsCaptionAsAParagraph() {
        val blocks = parse(
            """
            <figure class="wp-block-gallery">
              <figure><img src="https://cdn.example.com/1.jpg"><figcaption>One</figcaption></figure>
              <figure><img src="https://cdn.example.com/2.jpg"></figure>
              <figcaption>Two days in Nara</figcaption>
            </figure>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                SiteBlock.Picture("https://cdn.example.com/1.jpg", "One"),
                SiteBlock.Picture("https://cdn.example.com/2.jpg"),
                SiteBlock.Text("Two days in Nara"),
            ),
            blocks,
        )
    }

    @Test fun linkedThumbnailsLazyPicturesPixelsAndEmoji() {
        val blocks = parse(
            """
            <p>Look <img class="wp-smiley" src="https://s.w.org/images/core/emoji/15/72x72/1f600.png" alt="😀"> at this:</p>
            <a href="https://blogger.example.com/s1600/IMG_1.JPG"><img src="https://blogger.example.com/s320/IMG_1.JPG"></a>
            <img src="data:image/gif;base64,R0lGOD" data-src="//cdn.example.com/lazy.jpg">
            <img src="https://pixel.wp.com/g.gif?x=1" width="1" height="1">
            <img src="http://insecure.example.com/old.jpg">
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                SiteBlock.Text("Look 😀 at this:"),
                SiteBlock.Picture("https://blogger.example.com/s1600/IMG_1.JPG"),
                SiteBlock.Picture("https://cdn.example.com/lazy.jpg"),
                SiteBlock.Picture("https://insecure.example.com/old.jpg"),
            ),
            blocks,
        )
    }

    @Test fun videosAndEmbedsBecomeALinkLine() {
        val blocks = parse(
            """
            <figure><video controls><source src="https://va.example.com/clip.mp4" type="video/mp4"></video></figure>
            <iframe src="https://www.youtube.com/embed/abc123?feature=oembed"></iframe>
            <iframe src="https://player.vimeo.com/video/987"></iframe>
            <iframe src="https://www.facebook.com/plugins/like.php"></iframe>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                SiteBlock.Text("<a href=\"https://va.example.com/clip.mp4\">https://va.example.com/clip.mp4</a>"),
                SiteBlock.Text("<a href=\"https://www.youtube.com/watch?v=abc123\">https://www.youtube.com/watch?v=abc123</a>"),
                SiteBlock.Text("<a href=\"https://vimeo.com/987\">https://vimeo.com/987</a>"),
            ),
            blocks,
        )
    }

    @Test fun scriptsStylesFormsAndNavigationAreLeftOut() {
        val blocks = parse(
            """
            <script>alert('x')</script><style>p{color:red}</style>
            <nav><a href="/">Home</a></nav>
            <p>Only this.</p>
            <form><input value="Subscribe"><button>Go</button></form>
            """.trimIndent(),
        )
        assertEquals(listOf(SiteBlock.Text("Only this.")), blocks)
    }

    @Test fun formattingOpenAcrossABlockIsClosedAndOpenedAgain() {
        val blocks = parse("<b>Bold start<p>bold paragraph</p>bold end</b>")
        assertEquals(
            listOf(SiteBlock.Text("<b>Bold start</b>"), SiteBlock.Text("<b>bold paragraph</b>"), SiteBlock.Text("<b>bold end</b>")),
            blocks,
        )
        blocks.forEach { b -> assertEquals(RichHtml.sanitize((b as SiteBlock.Text).html), b.html) }
    }

    @Test fun lineBreaksStayEmptyParagraphsGoAndPreKeepsItsLines() {
        val blocks = parse("<p>Line one<br>line two<br/></p><p>&nbsp;</p><p><br></p><pre>a  b\nc</pre>")
        assertEquals(listOf(SiteBlock.Text("Line one<br>line two"), SiteBlock.Text("a  b<br>c")), blocks)
    }

    @Test fun tumblrLinkWrappersGiveTheirDestination() {
        assertEquals("https://www.example.org/story", HtmlBlocks.link("https://href.li/?https://www.example.org/story", base))
        assertEquals("https://example.org/a?b=1", HtmlBlocks.link("https://t.umblr.com/redirect?z=https%3A%2F%2Fexample.org%2Fa%3Fb%3D1&t=x", base))
        assertEquals("http://plain.example.org/", HtmlBlocks.link("http://plain.example.org/", base))
        assertEquals("mailto:me@example.org", HtmlBlocks.link("mailto:me@example.org", base))
        assertEquals(null, HtmlBlocks.link("#top", base))
        assertEquals(null, HtmlBlocks.link("javascript:alert(1)", base))
    }

    @Test fun everyWordIsKeptInOrder() {
        val html = """
            <p>Zero <b>one</b> two</p><div>three <i>four <u>five</u></i></div>
            <blockquote>six <a href="https://x.example.com">seven</a></blockquote>
            <ul><li>eight</li><li>nine <s>ten</s></li></ul><h2>eleven</h2><p>twelve&nbsp;thirteen</p>
        """.trimIndent()
        val words = parse(html).joinToString(" ") { b ->
            when (b) {
                is SiteBlock.Text -> RichHtml.toPlainText(b.html)
                is SiteBlock.Quote -> RichHtml.toPlainText(b.html)
                is SiteBlock.Heading -> b.text
                else -> ""
            }
        }.split(Regex("[\\s\\u00A0]+")).filter { it.isNotEmpty() }
        assertEquals("Zero one two three four five six seven eight nine ten eleven twelve thirteen".split(' '), words)
    }

    @Test fun aPictureInsideAQuoteSplitsTheQuote() {
        val blocks = parse("<blockquote><p>Before</p><img src=\"https://cdn.example.com/q.jpg\"><p>After</p></blockquote>")
        assertEquals(
            listOf(SiteBlock.Quote("Before"), SiteBlock.Picture("https://cdn.example.com/q.jpg"), SiteBlock.Quote("After")),
            blocks,
        )
        assertTrue(parse("").isEmpty())
    }
}

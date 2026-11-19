package com.justdataplease.boringtravel.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Tumblr's read API on synthetic answers written in the shape the live API sent on 2026-11-12 (JSONP, the field names
 * of each post type, `posts-total` as a number or a string); the posts themselves are made up.
 */
class TumblrApiTest {
    @Test fun blogAddresses() {
        assertEquals(TumblrApi.Blog("https://wanderingfeet.tumblr.com", null, true), TumblrApi.blog("https://wanderingfeet.tumblr.com/"))
        assertEquals(TumblrApi.Blog("https://wanderingfeet.tumblr.com", null, true), TumblrApi.blog("https://www.tumblr.com/wanderingfeet"))
        assertEquals(TumblrApi.Blog("https://wanderingfeet.tumblr.com", null, true), TumblrApi.blog("https://www.tumblr.com/blog/view/wanderingfeet"))
        assertEquals(TumblrApi.Blog("https://wanderingfeet.tumblr.com", "new york", true), TumblrApi.blog("https://wanderingfeet.tumblr.com/tagged/new-york"))
        assertEquals(TumblrApi.Blog("https://wanderingfeet.tumblr.com", "japan", true), TumblrApi.blog("https://www.tumblr.com/wanderingfeet/tagged/japan"))
        assertEquals(TumblrApi.Blog("https://travels.example.com", null, false), TumblrApi.blog("https://travels.example.com/archive"))
        assertNull(TumblrApi.blog("https://www.tumblr.com/dashboard"))
        assertNull(TumblrApi.blog("https://www.tumblr.com/explore/trending"))
        assertNull(TumblrApi.blog("https://64.media.tumblr.com/abc/tumblr_x_1280.jpg"))
        assertNull(TumblrApi.blog("https://assets.tumblr.com/x.js"))
    }

    @Test fun aPostsAddressIsThatPostOnly() {
        val blog = TumblrApi.Blog("https://wanderingfeet.tumblr.com", null, true, post = "828009069026721792")
        assertEquals(blog, TumblrApi.blog("https://wanderingfeet.tumblr.com/post/828009069026721792/kyoto-at-last"))
        assertEquals(blog, TumblrApi.blog("https://wanderingfeet.tumblr.com/post/828009069026721792"))
        assertEquals(blog, TumblrApi.blog("https://www.tumblr.com/wanderingfeet/828009069026721792/kyoto-at-last?source=share"))
        assertEquals(blog, TumblrApi.blog("https://tumblr.com/wanderingfeet/828009069026721792"))
        assertEquals(blog, TumblrApi.blog("https://www.tumblr.com/blog/view/wanderingfeet/828009069026721792"))
        // A blog's own domain: asked the same way; another site's "/post/…" simply doesn't answer.
        assertEquals(TumblrApi.Blog("https://travels.example.com", null, false, post = "828009069026721792"), TumblrApi.blog("https://travels.example.com/post/828009069026721792/kyoto"))
        assertEquals("https://wanderingfeet.tumblr.com/api/read/json?id=828009069026721792", TumblrApi.pageUrl(blog, 0))
        // Not posts: a tag's page, a word after the blog's name, a share link's own host.
        assertNull(TumblrApi.blog("https://wanderingfeet.tumblr.com/tagged/japan")!!.post)
        assertNull(TumblrApi.blog("https://www.tumblr.com/wanderingfeet/archive")!!.post)
        assertNull(TumblrApi.blog("https://at.tumblr.com/wanderingfeet/kyoto-at-last/abc123"))
    }

    @Test fun pageAddress() {
        val blog = TumblrApi.Blog("https://wanderingfeet.tumblr.com", "new york", true)
        assertEquals("https://wanderingfeet.tumblr.com/api/read/json?start=50&num=50&tagged=new%20york", TumblrApi.pageUrl(blog, 50))
        assertEquals("https://wanderingfeet.tumblr.com/api/read/json?start=0&num=10", TumblrApi.pageUrl(blog.copy(tag = null), 0, 10))
    }

    @Test fun notTheApi() {
        assertNull(TumblrApi.parse("<!doctype html><html><body>Not found</body></html>"))
        assertNull(TumblrApi.parse("var tumblr_api_read = {\"tumblelog\":{\"title\":\"Cut"))
        assertNull(TumblrApi.parse(""))
    }

    @Test fun everyPostType() {
        val page = TumblrApi.parse(ANSWER)!!
        assertEquals("Wandering Feet & friends", page.title)
        assertEquals(9, page.total)
        assertEquals(9, page.count)
        val byId = page.posts.associateBy { it.id }

        val text = byId.getValue("1001")
        assertEquals("https://wanderingfeet.tumblr.com/post/1001", text.url)
        assertEquals("Kyoto at last", text.title)
        assertEquals(Instant.ofEpochSecond(1460000000), text.at)
        assertEquals(listOf("japan", "kyoto"), text.tags)
        assertEquals(
            listOf(
                SiteBlock.Text("We landed at dawn."),
                SiteBlock.Picture("https://64.media.example.com/aa/s1280x1920/a.jpg"),
                SiteBlock.Text("The <b>gate</b> was red."),
                SiteBlock.Picture("https://64.media.example.com/bb/s1280x1920/b.jpg"),
            ),
            text.blocks,
        )

        val set = byId.getValue("1002")
        assertEquals(
            listOf(
                SiteBlock.Picture("https://64.media.example.com/tumblr_set_o1_1280.jpg", "Morning"),
                SiteBlock.Picture("https://64.media.example.com/tumblr_set_o2_1280.jpg", ""),
                SiteBlock.Picture("https://64.media.example.com/tumblr_set_o3_1280.jpg", "Night market"),
                SiteBlock.Text("Three pictures from <i>Osaka</i>."),
            ),
            set.blocks,
        )
        assertEquals(Instant.parse("2016-04-08T10:00:00Z"), set.at) // from date-gmt when there is no timestamp

        assertEquals(
            listOf(SiteBlock.Picture("https://64.media.example.com/tumblr_one_1280.jpg"), SiteBlock.Text("Just one.")),
            byId.getValue("1003").blocks,
        )
        assertEquals(
            listOf(SiteBlock.Quote("Not all who wander are lost."), SiteBlock.Text("A mug in Nara")),
            byId.getValue("1004").blocks,
        )
        val link = byId.getValue("1005")
        assertEquals("Train times", link.title)
        assertEquals(
            listOf(SiteBlock.Text("<a href=\"https://trains.example.org/kyoto\">https://trains.example.org/kyoto</a>"), SiteBlock.Text("Handy.")),
            link.blocks,
        )
        assertEquals(listOf(SiteBlock.Quote("Where next?"), SiteBlock.Text("Hokkaido.")), byId.getValue("1006").blocks)
        val chat = byId.getValue("1007")
        assertEquals("At the station", chat.title)
        assertEquals(listOf(SiteBlock.Text("<b>Me:</b> Which platform?"), SiteBlock.Text("<b>Guard:</b> Four.")), chat.blocks)
        assertEquals(
            listOf(SiteBlock.Text("<a href=\"https://va.example.com/v.mp4\">https://va.example.com/v.mp4</a>"), SiteBlock.Text("Snow!")),
            byId.getValue("1008").blocks,
        )
        assertEquals(
            listOf(SiteBlock.Text("<a href=\"https://a.example.com/song.mp3\">https://a.example.com/song.mp3</a>"), SiteBlock.Text("Our song.")),
            byId.getValue("1009").blocks,
        )
    }

    @Test fun totalAsAStringWhenFilteredByTag() {
        val page = TumblrApi.parse("""var tumblr_api_read = {"tumblelog":{"title":"","name":"wanderingfeet"},"posts-start":0,"posts-total":"2","posts":[]};""")!!
        assertEquals(2, page.total)
        assertEquals("wanderingfeet", page.title)
        assertTrue(page.posts.isEmpty())
        assertFalse(page.count > 0)
    }

    companion object {
        /** A made-up blog in the API's shape, one post of each type. */
        val ANSWER = """
var tumblr_api_read = {"tumblelog":{"title":"Wandering Feet &amp; friends","description":"","name":"wanderingfeet","timezone":"US\/Eastern","cname":false,"feeds":[]},"posts-start":0,"posts-total":9,"posts-type":false,"posts":[
{"id":"1001","url":"https:\/\/wanderingfeet.tumblr.com\/post\/1001","url-with-slug":"https:\/\/wanderingfeet.tumblr.com\/post\/1001\/kyoto-at-last","type":"regular","date-gmt":"2016-04-07 03:33:20 GMT","unix-timestamp":1460000000,"format":"html","like-button":"<iframe src=\"https:\/\/assets.example.com\/like_iframe.html\"><\/iframe>","regular-title":"Kyoto at last","regular-body":"<p>We landed at dawn.<\/p><div class=\"npf_row\"><figure class=\"tmblr-full\"><img src=\"https:\/\/64.media.example.com\/aa\/s640x960\/a.jpg\" srcset=\"https:\/\/64.media.example.com\/aa\/s400x600\/a.jpg 400w, https:\/\/64.media.example.com\/aa\/s1280x1920\/a.jpg 1080w\"\/><\/figure><\/div><p>The <b>gate<\/b> was red.<\/p><figure class=\"tmblr-full\"><img src=\"https:\/\/64.media.example.com\/bb\/s640x960\/b.jpg\" srcset=\"https:\/\/64.media.example.com\/bb\/s1280x1920\/b.jpg 1280w\"\/><\/figure>","tags":["japan","kyoto"]},
{"id":"1002","url":"https:\/\/wanderingfeet.tumblr.com\/post\/1002","type":"photo","date-gmt":"2016-04-08 10:00:00 GMT","photo-caption":"<p>Three pictures from <em>Osaka<\/em>.<\/p>","photo-url-1280":"https:\/\/64.media.example.com\/tumblr_set_o1_1280.jpg","photo-url-500":"https:\/\/64.media.example.com\/tumblr_set_o1_500.jpg","photos":[{"offset":"o1","caption":"Morning","width":1280,"height":960,"photo-url-1280":"https:\/\/64.media.example.com\/tumblr_set_o1_1280.jpg","photo-url-500":"https:\/\/64.media.example.com\/tumblr_set_o1_500.jpg"},{"offset":"o2","caption":"","photo-url-1280":"https:\/\/64.media.example.com\/tumblr_set_o2_1280.jpg"},{"offset":"o3","caption":"Night market","photo-url-500":"https:\/\/64.media.example.com\/tumblr_set_o3_500.jpg","photo-url-1280":"https:\/\/64.media.example.com\/tumblr_set_o3_1280.jpg"}],"tags":[]},
{"id":"1003","url":"https:\/\/wanderingfeet.tumblr.com\/post\/1003","type":"photo","unix-timestamp":1460100000,"photo-caption":"<p>Just one.<\/p>","photo-url-1280":"https:\/\/64.media.example.com\/tumblr_one_1280.jpg","photo-url-500":"https:\/\/64.media.example.com\/tumblr_one_500.jpg","photos":[]},
{"id":"1004","url":"https:\/\/wanderingfeet.tumblr.com\/post\/1004","type":"quote","unix-timestamp":1460200000,"quote-text":"Not all who wander are lost.","quote-source":"A mug in Nara"},
{"id":"1005","url":"https:\/\/wanderingfeet.tumblr.com\/post\/1005","type":"link","unix-timestamp":1460300000,"link-text":"Train times","link-url":"https:\/\/trains.example.org\/kyoto","link-description":"<p>Handy.<\/p>"},
{"id":"1006","url":"https:\/\/wanderingfeet.tumblr.com\/post\/1006","type":"answer","unix-timestamp":1460400000,"question":"Where next?","answer":"<p>Hokkaido.<\/p>"},
{"id":"1007","url":"https:\/\/wanderingfeet.tumblr.com\/post\/1007","type":"conversation","unix-timestamp":1460500000,"conversation-title":"At the station","conversation-text":"Me: Which platform?\r\nGuard: Four.","conversation":[{"name":"Me","label":"Me:","phrase":"Which platform?"},{"name":"Guard","label":"Guard:","phrase":"Four."}]},
{"id":"1008","url":"https:\/\/wanderingfeet.tumblr.com\/post\/1008","type":"video","unix-timestamp":1460600000,"video-caption":"<p>Snow!<\/p>","video-source":"a:2:{i:0;a:0:{}}","video-player":"<video width='400' controls><source src=\"https:\/\/va.example.com\/v.mp4\" type=\"video\/mp4\"><\/video>"},
{"id":"1009","url":"https:\/\/wanderingfeet.tumblr.com\/post\/1009","type":"audio","unix-timestamp":1460700000,"audio-caption":"<p>Our song.<\/p>","audio-player":"<iframe class=\"tumblr_audio_player\" src=\"https:\/\/wanderingfeet.tumblr.com\/post\/1009\/audio_player_iframe\/wanderingfeet\/x?audio_file=https%3A%2F%2Fa.example.com%2Fsong.mp3\"><\/iframe>"}
]};
""".trimIndent()
    }
}

package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.doc.RichHtml
import com.justdataplease.boringtravel.links.MetaTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Tumblr's public read API (`https://<blog>/api/read/json?start=N&num=50`, checked on 2026-11-12): a JSONP answer
 * (`var tumblr_api_read = {…};`) with the blog's title, `posts-total` and up to 50 posts, newest first. It answers on
 * `<name>.tumblr.com` and on a blog's own domain, without a key and without signing in. Post types: regular (text,
 * also every post written with the newer editor: its pictures, links and videos are HTML in `regular-body`), photo
 * (one picture or a photoset, sizes up to 1280), quote, link, answer, conversation, video and audio (kept as a line
 * with their link). Pure parsing; [SiteReader] fetches the pages.
 */
object TumblrApi {
    /** Posts per call: the API's most. */
    const val PAGE = 50

    /** Posts per call after a page too large to read ([SiteLimits.MAX_PAGE_BYTES]). */
    const val SMALL_PAGE = 10

    /**
     * A blog to ask: [base] like `https://name.tumblr.com` (or a blog's own domain), [tag] when the address was a tag's
     * page (`/tagged/japan`): only those posts. [known]: the address is on tumblr.com, so it surely is a blog. [post]:
     * the address was one post's (`/post/123/slug`, `tumblr.com/name/123/slug`): only that post (owner, 15 Nov 2026:
     * "it should get only the one sent").
     */
    data class Blog(val base: String, val tag: String?, val known: Boolean, val post: String? = null)

    /** One answer: the blog's [title], its [total] posts, the [posts] read and how many the answer held ([count]). */
    data class Page(val title: String, val total: Int, val posts: List<SitePost>, val count: Int)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val nameRe = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

    /** First path parts of tumblr.com that are not blogs. */
    private val notBlogs = setOf(
        "blog", "dashboard", "explore", "tagged", "search", "settings", "likes", "following", "communities", "login",
        "register", "about", "policy", "help", "docs", "privacy", "apps", "inbox", "new", "reblog", "post", "activity",
        "messages", "account", "logout", "embed", "oauth", "api", "safe-mode", "tags", "notifications", "premium",
    )

    /** Subdomains of tumblr.com that are not blogs ("at" only passes a shared link on to its post). */
    private val notBlogHosts = setOf("www", "api", "assets", "media", "static", "help", "staff-blog", "va", "64", "at")

    /** A post's id in an address: digits only, as Tumblr numbers its posts. */
    private val postRe = Regex("^\\d{5,25}$")

    /**
     * The blog behind an address: `name.tumblr.com/…`, `tumblr.com/name/…`, `www.tumblr.com/blog/view/name`, or any
     * other domain (a blog's own domain answers the same API; [Blog.known] is false then); with [Blog.post] when the
     * address is one post's. Null for tumblr.com pages that are not a blog.
     */
    fun blog(url: String): Blog? {
        val u = url.toHttpUrlOrNull() ?: return null
        val host = u.host.lowercase()
        val parts = u.pathSegments.filter { it.isNotEmpty() }
        if (host == "tumblr.com" || host == "www.tumblr.com") {
            val at = when {
                parts.getOrNull(0) == "blog" && parts.getOrNull(1) == "view" -> 2
                parts.getOrNull(0) == "blog" -> 1
                else -> 0
            }
            val name = parts.getOrNull(at)?.lowercase() ?: return null
            if ((at == 0 && name in notBlogs) || !nameRe.matches(name)) return null
            val rest = parts.drop(at + 1)
            // tumblr.com/name/123/slug, and the older tumblr.com/name/post/123.
            return Blog("https://$name.tumblr.com", tag(rest), known = true, post = rest.firstOrNull()?.takeIf(postRe::matches) ?: post(rest))
        }
        if (host.endsWith(".tumblr.com")) {
            val sub = host.removeSuffix(".tumblr.com")
            if ('.' in sub || sub in notBlogHosts || !nameRe.matches(sub)) return null
            return Blog("https://$host", tag(parts), known = true, post = post(parts))
        }
        return Blog("https://$host", tag(parts), known = false, post = post(parts))
    }

    /** `/tagged/new-york` is the tag "new york" (Tumblr writes spaces as hyphens in tag addresses). */
    private fun tag(parts: List<String>): String? =
        if (parts.getOrNull(0) == "tagged") parts.getOrNull(1)?.replace('-', ' ')?.replace('+', ' ')?.trim()?.takeIf { it.isNotEmpty() } else null

    /** `/post/123456/a-slug` is post 123456. */
    private fun post(parts: List<String>): String? =
        if (parts.getOrNull(0) == "post") parts.getOrNull(1)?.takeIf(postRe::matches) else null

    /** One page of the blog's posts, or (for [Blog.post]) that one post. */
    fun pageUrl(blog: Blog, start: Int, num: Int = PAGE): String =
        "${blog.base}/api/read/json".toHttpUrl().newBuilder()
            .apply {
                if (blog.post != null) addQueryParameter("id", blog.post)
                else {
                    addQueryParameter("start", start.toString())
                    addQueryParameter("num", num.toString())
                    blog.tag?.let { addQueryParameter("tagged", it) }
                }
            }
            .build().toString()

    /** One answer of the read API, or null when [text] isn't one (another site, an error page, a cut-off answer). */
    fun parse(text: String): Page? {
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (!t.startsWith("var tumblr_api_read")) return null
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { json.parseToJsonElement(t.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val blog = root["tumblelog"] as? JsonObject
        val name = blog?.str("name").orEmpty()
        val title = plain(blog?.str("title")).ifBlank { name }
        val raw = (root["posts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Page(title, root.num("posts-total")?.toInt() ?: raw.size, raw.mapNotNull { post(it, name) }, raw.size)
    }

    /**
     * A reblog: the API names the blog it came from (`reblogged-from-name`) and the one that first posted it
     * (`reblogged-root-name`), checked on staff.tumblr.com on 2026-11-12. One first posted by this blog itself is its own.
     */
    internal fun isReblog(o: JsonObject, blogName: String): Boolean {
        val from = o.str("reblogged-from-name")?.takeIf { it.isNotBlank() } ?: return false
        val root = o.str("reblogged-root-name")?.takeIf { it.isNotBlank() } ?: from
        return !root.equals(blogName, ignoreCase = true) || blogName.isBlank()
    }

    private fun post(o: JsonObject, blogName: String): SitePost? {
        val url = (o.str("url") ?: o.str("url-with-slug"))?.let { MetaTags.resolve(it, it) } ?: return null
        val id = o.str("id") ?: url
        val at = o.num("unix-timestamp")?.let { Instant.ofEpochSecond(it) } ?: o.str("date-gmt")?.let(::gmt)
        val tags = (o["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { t -> t.isNotEmpty() } }
        val html = { key: String -> o.str(key)?.let { HtmlBlocks.parse(it, url) }.orEmpty() }
        val (title, blocks) = when (o.str("type")) {
            "photo" -> "" to photos(o) + html("photo-caption")
            "quote" -> "" to quote(o.str("quote-text"), url) + html("quote-source")
            "link" -> plain(o.str("link-text")) to linkLine(o.str("link-url"), url) + html("link-description")
            "answer" -> "" to quote(o.str("question"), url) + html("answer")
            "conversation", "chat" -> plain(o.str("conversation-title")) to chat(o)
            "video" -> "" to linkLine(videoUrl(o, url), url) + html("video-caption")
            "audio" -> "" to linkLine(audioUrl(o, url), url) + html("audio-caption")
            else -> plain(o.str("regular-title")) to html("regular-body")
        }
        return SitePost(id, url, title, at, blocks, tags, reblog = isReblog(o, blogName))
    }

    /** A photo post's pictures: each of a photoset with its own caption, else the one picture; 1280 wide at most. */
    private fun photos(o: JsonObject): List<SiteBlock> {
        val set = (o["photos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (set.isNotEmpty()) return set.mapNotNull { p -> largest(p)?.let { SiteBlock.Picture(it, plain(p.str("caption"))) } }
        return listOfNotNull(largest(o)?.let { SiteBlock.Picture(it) })
    }

    private fun largest(o: JsonObject): String? =
        listOf("1280", "500", "400", "250", "100").firstNotNullOfOrNull { size -> o.str("photo-url-$size")?.let { MetaTags.resolve(it, it) } }

    private fun quote(html: String?, base: String): List<SiteBlock> =
        if (html.isNullOrBlank()) emptyList() else HtmlBlocks.parse("<blockquote>$html</blockquote>", base)

    /** A link alone on its line, written out as its address. */
    private fun linkLine(url: String?, base: String): List<SiteBlock> {
        val href = url?.let { HtmlBlocks.link(it, base) } ?: return emptyList()
        val safe = RichHtml.escape(href)
        return listOf(SiteBlock.Text("<a href=\"$safe\">$safe</a>"))
    }

    /** A chat post: one line per speaker, the speaker's label in bold, as Tumblr shows it. */
    private fun chat(o: JsonObject): List<SiteBlock> {
        val lines = (o["conversation"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (lines.isNotEmpty()) return lines.mapNotNull { l ->
            val label = plain(l.str("label"))
            val phrase = plain(l.str("phrase"))
            if (label.isEmpty() && phrase.isEmpty()) null
            else SiteBlock.Text((if (label.isNotEmpty()) "<b>${RichHtml.escape(label)}</b> " else "") + RichHtml.escape(phrase))
        }
        return o.str("conversation-text").orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { SiteBlock.Text(RichHtml.escape(it)) }
    }

    /** A video post's address: its source when it is one, else the player's video or frame. */
    private fun videoUrl(o: JsonObject, base: String): String? {
        o.str("video-source")?.trim()?.takeIf { it.startsWith("http") }?.let { return it }
        val player = o.str("video-player") ?: return null
        val doc = Jsoup.parseBodyFragment(player, base)
        return doc.select("video, iframe, embed, object").firstNotNullOfOrNull { HtmlBlocks.mediaUrl(it, base) }
    }

    /** An audio post's file (the player's `audio_file`), else the player's address. */
    private fun audioUrl(o: JsonObject, base: String): String? {
        val player = o.str("audio-player") ?: o.str("audio-embed") ?: return null
        val src = Jsoup.parseBodyFragment(player, base).selectFirst("iframe, audio, embed")?.let { HtmlBlocks.mediaUrl(it, base) } ?: return null
        return src.toHttpUrlOrNull()?.queryParameter("audio_file")?.takeIf { it.startsWith("http") } ?: src
    }

    /** Text that may hold entities or a little HTML, as plain words. */
    private fun plain(s: String?): String = if (s.isNullOrBlank()) "" else Jsoup.parse(s).text().trim()

    private val gmtFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun gmt(s: String): Instant? =
        runCatching { LocalDateTime.parse(s.removeSuffix("GMT").trim(), gmtFormat).toInstant(ZoneOffset.UTC) }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    /** A number the API sends as a number or as a string ("posts-total" is a string when filtered by tag). */
    private fun JsonObject.num(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }
}

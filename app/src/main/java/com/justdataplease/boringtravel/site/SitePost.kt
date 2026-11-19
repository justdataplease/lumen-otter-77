package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.doc.ListStyle
import java.io.File
import java.time.Instant

/**
 * One post of a blog as its site shows it: [blocks] in the page's order, the words exactly as written. [id] tells the
 * post apart within one read (Tumblr's post id, else its address); [url] is the post's own address, which also makes
 * the note's id ([SiteNotes.entryId]); [at] is null when the site gives no date. [part] names one section of a long
 * page or document cut at its headings ([SiteSections]), so each section gets a note of its own. [reblog]: a Tumblr
 * post shared from another blog. Read by [SiteReader].
 */
data class SitePost(
    val id: String,
    val url: String,
    val title: String,
    val at: Instant?,
    val blocks: List<SiteBlock>,
    val tags: List<String> = emptyList(),
    val part: String? = null,
    val reblog: Boolean = false,
) {
    val pictures: List<SiteBlock.Picture> get() = blocks.filterIsInstance<SiteBlock.Picture>()
}

/** A post's parts, in the order the site shows them. */
sealed interface SiteBlock {
    /**
     * A paragraph in the journal's HTML subset ([com.justdataplease.boringtravel.doc.RichHtml]: bold, italic,
     * underline, strike-through, links, line breaks); [list] when it is one item of a list.
     */
    data class Text(val html: String, val list: ListStyle? = null) : SiteBlock

    /** [level] 1 to 6 as the page wrote it (h1…h6); the note draws 1 and 2 as its level 2, 3 as 3, the rest as 4. */
    data class Heading(val text: String, val level: Int = 2) : SiteBlock

    data class Quote(val html: String) : SiteBlock

    /** A picture at the largest size the site offers, with its caption. */
    data class Picture(val url: String, val caption: String = "") : SiteBlock

    data object Divider : SiteBlock
}

/**
 * Where the posts came from: Tumblr's read API, the site's RSS or Atom feed, a Google Doc, the site's dated articles
 * found through its sitemap or its pages of posts, or the page itself as one post.
 */
enum class SiteKind { Tumblr, Feed, Doc, Articles, Page }

/**
 * What [SiteReader] found at an address: the site's [name] (its own title, else its host), the [posts] not copied
 * before, how many were [alreadyCopied], how many Tumblr [reblogs] were left out (other people's posts), and [more]
 * when the reading stopped before the end: a limit ([SiteLimits]), or the site stopped answering ([stopped]); copying
 * again later continues with the rest. [inline] are pictures that came inside the page (a Google Doc's export) and
 * were saved on the phone already: their placeholder address and their file. [whole]: the address was one post's, so
 * only that post was read; this is the address of the blog it belongs to, for copying all of it.
 */
data class SiteRead(
    val name: String,
    val host: String,
    val kind: SiteKind,
    val posts: List<SitePost>,
    val alreadyCopied: Int = 0,
    val more: Boolean = false,
    val reblogs: Int = 0,
    val stopped: Boolean = false,
    val inline: Map<String, File> = emptyMap(),
    val whole: String? = null,
)

/** A plain reason for the traveller ("No posts found at this address."). */
class SiteReadException(message: String) : Exception(message)

/** How much one copy reads and downloads, and how politely. */
object SiteLimits {
    /** Posts copied in one go; already copied posts don't count, so copying again continues. */
    const val MAX_POSTS = 500

    /** Pictures in one go (the posts stop before the one that would pass it). */
    const val MAX_PICTURES = 2_000

    /** One picture. Larger ones are left out of their note. */
    const val MAX_PICTURE_BYTES = 15L * 1024 * 1024

    /** One page, feed or API answer (Tumblr's 50 posts with their HTML run to about 2 MB). */
    const val MAX_PAGE_BYTES = 6L * 1024 * 1024

    /** Pages, feed pages or API calls per read. */
    const val MAX_PAGES = 60

    /** Between two pages of the same site. */
    const val PAGE_PAUSE_MS = 400L

    /** Pictures downloaded at the same time. */
    const val DOWNLOADS_AT_ONCE = 3

    /** Seconds: connecting, waiting for bytes, one page, one picture. */
    const val CONNECT_SECONDS = 15L
    const val READ_SECONDS = 30L
    const val PAGE_SECONDS = 60L
    const val PICTURE_SECONDS = 120L

    /** One Google Doc export with its pictures inside. */
    const val DOC_SECONDS = 600L

    /** The longest address accepted. */
    const val MAX_ADDRESS = 2_048

    /** A Google Doc's export with its pictures inside (they are saved to files as it arrives; the text stays small). */
    const val MAX_DOC_BYTES = 150L * 1024 * 1024

    /** Pages fetched to find a site's dated articles (its sitemap or its pages of posts), articles or not. */
    const val MAX_ARTICLE_FETCHES = 600

    /** Pages read before giving up on a site whose pages are not dated articles. */
    const val MAX_MISSES = 25

    /** Sitemaps read (an index lists several), and pages of posts followed through "Older posts". */
    const val MAX_SITEMAPS = 12
    const val MAX_INDEX_PAGES = 30

    /** Places looked up on the map in one copy (the planner's own cap is 40 per answer). */
    const val MAX_PLACE_LOOKUPS = 60

    /**
     * Between two questions to the map (a place takes one to three), and the lookups that may fail in a row before
     * the map is left alone. One a second: the free map stopped answering this network on 15 Nov 2026 after bursts of
     * a few a second, and a post about thirty places now asks it sixty times.
     */
    const val MAP_GAP_MS = 1_000L
    const val MAX_PLACE_FAILURES = 2
}

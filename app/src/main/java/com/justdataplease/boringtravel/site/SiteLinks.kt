package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.links.LinkUrls
import com.justdataplease.boringtravel.links.MapsLinks
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where Copy from a link is offered besides its own card. The AI planner, when the traveller asks for their own notes
 * to be copied (its copy_notes_from_link tool, which also reads pages with [offer] for read_link): "Copy the notes and
 * pictures from <host>" (or "from this document") opens the copy with the address the model sent. Add to plan, for a
 * link shared from a browser: a second choice when the link looks like a blog, a site's front page or a Google Doc
 * ([looksLikeSite]). Google Maps links are places, not sites. Pure and unit tested.
 */
object SiteLinks {
    private val webRe = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    /** A blog's address typed without https:// ("myblog.tumblr.com"). */
    private val blogRe = Regex("""(?<![\w@./-])[a-z0-9][a-z0-9-]*\.(?:tumblr\.com|wordpress\.com|blogspot\.com|substack\.com|medium\.com)(?:/[^\s<>"]*)?""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}'\"»”’"

    /** Hosts (and their subdomains) where people keep blogs. */
    private val BLOG_HOSTS = listOf(
        "tumblr.com", "wordpress.com", "blogspot.com", "blogger.com", "substack.com", "medium.com", "ghost.io", "wixsite.com",
        "squarespace.com", "weebly.com", "travelpod.com", "travellerspoint.com", "polarsteps.com", "livejournal.com", "typepad.com",
        "over-blog.com", "home.blog", "bearblog.dev", "micro.blog", "write.as",
    )

    /** The address to offer, or null. */
    fun offer(text: String): String? {
        val candidates = webRe.findAll(text).map { it.value } + blogRe.findAll(text).map { it.value }
        return candidates.map { it.trimEnd { c -> c in TRAILING } }.firstNotNullOfOrNull { c ->
            LinkUrls.normalize(c)?.takeIf { url -> !isDrive(url) && !MapsLinks.isMaps(url) }
        }
    }

    /** A Google Doc ([GoogleDocs.address]): the offer says "this document". */
    fun isDoc(url: String): Boolean = GoogleDocs.address(url) != null

    private fun isDrive(url: String): Boolean = LinkUrls.host(url) == "drive.google.com" ||
        (LinkUrls.host(url) == "docs.google.com" && !isDoc(url))

    /** A link that is likely a whole site to copy: a Google Doc, a blog platform's address, or a site's front page. */
    fun looksLikeSite(url: String): Boolean {
        if (isDoc(url)) return true
        val u = url.toHttpUrlOrNull() ?: return false
        if (MapsLinks.isMaps(url) || u.host.endsWith("google.com")) return false
        val host = u.host.removePrefix("www.")
        if (BLOG_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val path = u.pathSegments.filter { it.isNotEmpty() }
        return path.isEmpty() || (path.size == 1 && path[0].lowercase() in setOf("blog", "journal", "travels", "travel", "diary", "posts"))
    }

    /** "myblog.tumblr.com" for the offer's words. */
    fun host(url: String): String = LinkUrls.domain(url)
}

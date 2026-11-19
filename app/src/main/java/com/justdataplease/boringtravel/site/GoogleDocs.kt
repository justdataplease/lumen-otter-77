package com.justdataplease.boringtravel.site

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder

/**
 * Google Docs as a source (checked with curl on 2026-11-12):
 * - `https://docs.google.com/document/d/<id>/export?format=html` answers 200 `text/html` with the whole document for a
 *   document shared with "Anyone with the link", `Content-Disposition: attachment; filename="<Title>.html"`, its
 *   formatting in `<style>` classes (`.c3{font-weight:700}`) and every picture inside as a `data:image/…;base64`
 *   address at its original size (no sign-in needed: they come with the text). An unknown id answers 404; a document
 *   that isn't shared sends people to Google's sign-in (accounts.google.com).
 * - `/edit`, `/view`, `/preview` redirect to `/mobilebasic`; `/d/<id>/pub` answers 401 unless the document is
 *   published; a published document (`/d/e/2PACX…/pub`) is a page whose `<div id="contents">` holds the text with its
 *   own `<style>` (pictures, when there are any, from lh7-rt.googleusercontent.com, which download without sign-in).
 * Pure: addresses and parsing. [SiteReader] fetches.
 */
object GoogleDocs {
    sealed interface Address {
        /** A document by its id: read through its export (or the owner's Drive). */
        data class Doc(val id: String) : Address

        /** A document published to the web (File › Share › Publish to web): only its page can be read. */
        data class Published(val pubId: String) : Address
    }

    private val idRe = Regex("^[A-Za-z0-9_-]{20,200}$")
    private val OFFICE_EXTENSION = Regex("""\.(docx?|odt|rtf|txt)$""", RegexOption.IGNORE_CASE)

    /** The document behind a docs.google.com/document address, in any of its forms; null for anything else. */
    fun address(url: String): Address? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (u.host != "docs.google.com") return null
        val parts = u.pathSegments.filter { it.isNotEmpty() }
        if (parts.firstOrNull() != "document") return null
        var i = 1
        if (parts.getOrNull(i) == "u" && parts.getOrNull(i + 1)?.all { it.isDigit() } == true) i += 2
        if (parts.getOrNull(i) != "d") return null
        if (parts.getOrNull(i + 1) == "e") {
            val pub = parts.getOrNull(i + 2) ?: return null
            return if (pub.matches(idRe)) Address.Published(pub) else null
        }
        val id = parts.getOrNull(i + 1) ?: return null
        return if (id.matches(idRe)) Address.Doc(id) else null
    }

    fun exportUrl(id: String): String = "https://docs.google.com/document/d/$id/export?format=html"
    fun pubUrl(id: String): String = "https://docs.google.com/document/d/$id/pub"
    fun pubUrl(published: Address.Published): String = "https://docs.google.com/document/d/e/${published.pubId}/pub"

    /** The address a document's notes are known by (their ids come from it): the same for every form of its link. */
    fun canonical(address: Address): String = when (address) {
        is Address.Doc -> "https://docs.google.com/document/d/${address.id}"
        is Address.Published -> "https://docs.google.com/document/d/e/${address.pubId}"
    }

    /** True when an answer is Google's sign-in page: the document isn't shared with everyone who has the link. */
    fun isSignIn(finalUrl: String): Boolean = finalUrl.toHttpUrlOrNull()?.host?.let { it == "accounts.google.com" || it.endsWith(".accounts.google.com") } == true

    /** The document's title from the export's `Content-Disposition` ("Kyoto-diary.html", or its UTF-8 form). */
    fun titleFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val utf = Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
        val plain = Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        val name = (utf ?: plain)?.trim()?.removeSuffix(".html")?.removeSuffix(".htm") ?: return null
        // A Word file opened in Docs keeps its extension in its name ("Itinerary.docx").
        return name.replace(OFFICE_EXTENSION, "").replace('_', ' ').trim().takeIf { it.isNotBlank() }
    }

    /**
     * The document as one post: its title (the "Title" paragraph, else [fallbackTitle]) and its blocks with the
     * formatting its classes carry. Comments and their marks are left out (`[a]` and the comment list at the end).
     * Works for an export and for a published page (its `#contents`).
     */
    fun post(html: String, url: String, fallbackTitle: String?): SitePost? {
        val doc: Document = Jsoup.parse(html, url)
        val formats = HtmlBlocks.styles(doc)
        val root = doc.selectFirst("#contents") ?: doc.body()
        val pageTitle = doc.selectFirst("#title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        // Comments: the marks in the text and the list at the end.
        root.select("a[id^=cmnt]").forEach { a ->
            if (a.id().startsWith("cmnt_ref")) (a.parent()?.takeIf { it.normalName() == "sup" } ?: a).remove()
            else a.parents().firstOrNull { it.normalName() == "div" && it !== root }?.remove()
        }
        root.select("#title, #header, #footer, #banners, script, style").remove()
        val titleEl = root.selectFirst("p.title")
        val title = titleEl?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: pageTitle
            ?: fallbackTitle?.trim().orEmpty()
        titleEl?.remove()
        val blocks = HtmlBlocks.of(root, url, formats)
        if (blocks.isEmpty()) return null
        return SitePost(url, url, title, null, blocks)
    }

    /** The title of a published page (its `#title`, else its `<title>`), for its site name. */
    fun pageTitle(html: String): String? {
        val doc = Jsoup.parse(html)
        return (doc.selectFirst("#title")?.text() ?: doc.title()).trim().takeIf { it.isNotEmpty() }
    }
}

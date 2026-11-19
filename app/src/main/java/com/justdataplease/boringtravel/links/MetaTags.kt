package com.justdataplease.boringtravel.links

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a page says about itself for link previews. */
data class PageMeta(val imageUrl: String? = null, val title: String? = null, val siteName: String? = null)

/**
 * Reads a page's Open Graph and Twitter card tags (`og:image`, `og:title`, `og:site_name`, `twitter:image` …) out
 * of its HTML: attributes in any order, single, double or no quotes, entities (`&amp;`), and image addresses that
 * are relative or protocol-relative, resolved against the page's final address. No HTML library: one pass of
 * regular expressions over the first part of the page. Pure and unit tested.
 */
object MetaTags {
    /** A `<meta …>` tag; quoted values may contain `>`. */
    private val metaRe = Regex("<meta\\b((?:[^>\"']|\"[^\"]*\"|'[^']*')*)/?>", RegexOption.IGNORE_CASE)
    private val attrRe = Regex("([^\\s=/>\"']+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?")
    private val titleRe = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val charsetRe = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)", RegexOption.IGNORE_CASE)
    private val entityRe = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val spaceRe = Regex("\\s+")

    private val imageKeys = listOf("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src")
    private val titleKeys = listOf("og:title", "twitter:title")
    private const val MAX_TEXT = 200

    fun parse(html: String, pageUrl: String): PageMeta {
        val tags = HashMap<String, String>()
        for (m in metaRe.findAll(html)) {
            val attrs = attributes(m.groupValues[1])
            val key = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.trim()?.lowercase() ?: continue
            val content = attrs["content"]?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            tags.putIfAbsent(key, content) // the first of each wins, as with crawlers
        }
        val image = imageKeys.firstNotNullOfOrNull { k -> tags[k]?.let { resolve(it, pageUrl) } }
        val title = (titleKeys.firstNotNullOfOrNull { tags[it] } ?: titleRe.find(html)?.groupValues?.get(1)?.let(::decode))?.let(::clean)
        val site = tags["og:site_name"]?.let(::clean)
        return PageMeta(image, title, site)
    }

    /** Attribute names (lower-case) and their raw values; a bare attribute gets "". */
    private fun attributes(raw: String): Map<String, String> = buildMap {
        for (a in attrRe.findAll(raw)) {
            val name = a.groupValues[1].lowercase()
            val value = a.groups[2]?.value ?: a.groups[3]?.value ?: a.groups[4]?.value ?: ""
            putIfAbsent(name, value)
        }
    }

    /**
     * [ref] as an absolute https address against [base] ("/img/a.jpg", "//cdn.site.com/a.jpg", "a.jpg"), or null
     * for data: URIs and anything that isn't http(s). Plain http images are asked for over https.
     */
    fun resolve(ref: String, base: String): String? {
        val r = ref.trim().replace(" ", "%20")
        if (r.isEmpty() || r.startsWith("data:", ignoreCase = true)) return null
        val url = base.toHttpUrlOrNull()?.resolve(r) ?: r.toHttpUrlOrNull() ?: return null
        return if (url.scheme == "http") url.newBuilder().scheme("https").build().toString() else url.toString()
    }

    /** The character set a page names in its Content-Type header, else in its first bytes (`<meta charset>`). */
    fun charset(contentType: String?, head: String): String? =
        contentType?.let { charsetRe.find(it)?.groupValues?.get(1) } ?: charsetRe.find(head)?.groupValues?.get(1)

    /** Entities (`&amp;`, `&#39;`, `&#x27;`, `&nbsp;` …) as characters; unknown ones stay as written. */
    fun decode(s: String): String = if ('&' !in s) s else entityRe.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
            else -> named[e] ?: accented(e)
        } ?: m.value
    }

    private val accents = mapOf(
        "acute" to '́', "grave" to '̀', "circ" to '̂', "uml" to '̈', "tilde" to '̃', "ring" to '̊', "cedil" to '̧',
    )

    /** `&eacute;`, `&Ouml;`, `&ccedil;` …: a letter and its accent. */
    private fun accented(e: String): String? {
        val mark = accents[e.drop(1)] ?: return null
        if (!e[0].isLetter()) return null
        return java.text.Normalizer.normalize("${e[0]}$mark", java.text.Normalizer.Form.NFC).takeIf { it.length == 1 }
    }

    private fun codePoint(cp: Int): String? = if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "middot" to "·", "bull" to "•", "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€", "pound" to "£",
        "szlig" to "ß", "oslash" to "ø", "Oslash" to "Ø", "aelig" to "æ", "AElig" to "Æ", "deg" to "°", "times" to "×",
    )

    private fun clean(s: String): String? = s.replace(spaceRe, " ").trim().take(MAX_TEXT).takeIf { it.isNotEmpty() }
}

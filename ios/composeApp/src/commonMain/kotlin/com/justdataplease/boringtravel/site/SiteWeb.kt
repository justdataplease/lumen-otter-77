package com.justdataplease.boringtravel.site

import com.fleeksoft.charset.Charsets
import com.fleeksoft.charset.decodeToString as decodeIn
import com.justdataplease.boringtravel.compat.File
import com.justdataplease.boringtravel.compat.FileInputStream
import com.justdataplease.boringtravel.compat.FileOutputStream
import com.justdataplease.boringtravel.compat.InputStream
import com.justdataplease.boringtravel.compat.byteInputStream
import com.justdataplease.boringtravel.io.IOException
import com.justdataplease.boringtravel.ios.LinkHttpException
import com.justdataplease.boringtravel.ios.LinkUrls
import com.justdataplease.boringtravel.ios.MetaTags
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetRequest
import com.justdataplease.boringtravel.ios.ask
import com.justdataplease.boringtravel.ios.askStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The Android app's site/SiteWeb.kt, written again here: Android reads pages with OkHttp (OkSiteWeb); this phone with
 * its own network ([Net]). The interface, the texts it hands on and every rule are Android's: https only, the app's
 * User-Agent and the crawler form once after a 403, nothing about the traveller, at most the bytes asked for, the
 * page's own character set. The site readers (site/, read in from the Android app) go through [SiteWeb] only.
 */

/**
 * A page as text: its address after redirects, its media type ("text/html"), at most the bytes asked for, and its
 * `Content-Disposition` (a Google Doc's export names its title there).
 */
data class WebText(val url: String, val type: String?, val text: String, val truncated: Boolean, val disposition: String? = null)

/** A page as it arrives, for bodies too large to hold as text (a Google Doc with its pictures inside). */
class WebStream(val url: String, val type: String?, val disposition: String?, val input: InputStream)

/** How [SiteReader] reads the web; tests answer from fixtures. */
interface SiteWeb {
    /**
     * GET [url] over https. Null when the answer's media type isn't one [accept] takes; a refusal (404, 403 …) throws
     * [LinkHttpException], no connection an [IOException].
     */
    suspend fun get(url: String, maxBytes: Long = SiteLimits.MAX_PAGE_BYTES, accept: (String?) -> Boolean = { true }): WebText?

    /** Like [get], but [read] gets the body as it arrives and its answer is returned. */
    suspend fun <T> stream(url: String, accept: (String?) -> Boolean = { true }, read: (WebStream) -> T): T? =
        get(url, Long.MAX_VALUE / 2, accept)?.let { read(WebStream(it.url, it.type, it.disposition, it.text.byteInputStream())) }
}

/**
 * [SiteWeb] over this app's network ([Net]: URLSession on iPhone), as Android's OkSiteWeb asks: https only, every
 * request naming the app ([userAgent], the link previews' own) and nothing about the traveller (no cookies, no
 * language); a site that refuses it with 403 is asked once more in the crawler form. Time limits as Android's client:
 * 15 s to connect, 30 s of silence, a minute a page, ten minutes for a document with its pictures inside ([stream]),
 * which arrives into a file in [tempDir] first (never held whole in memory), else as text.
 */
class NetSiteWeb(
    private val net: Net,
    private val userAgent: String,
    private val crawlerUserAgent: String = userAgent,
    private val tempDir: () -> File? = { null },
) : SiteWeb {
    override suspend fun get(url: String, maxBytes: Long, accept: (String?) -> Boolean): WebText? {
        val target = secure(url)
        return try {
            get(target, userAgent, maxBytes, accept)
        } catch (e: LinkHttpException) {
            if (e.code != 403 || crawlerUserAgent == userAgent) throw e
            get(target, crawlerUserAgent, maxBytes, accept)
        }
    }

    override suspend fun <T> stream(url: String, accept: (String?) -> Boolean, read: (WebStream) -> T): T? {
        val target = secure(url)
        val dir = tempDir()?.takeIf { it.isDirectory || it.mkdirs() }
            ?: return get(target, SiteLimits.MAX_DOC_BYTES, accept)?.let { read(WebStream(it.url, it.type, it.disposition, it.text.byteInputStream())) }
        val file = File.createTempFile("page-", ".part", dir)
        try {
            val head = net.askStream(request(target, userAgent, Long.MAX_VALUE, SiteLimits.DOC_SECONDS)) { lines ->
                if (!lines.ok) throw LinkHttpException(lines.code)
                val media = lines.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (!accept(media)) return@askStream null
                // Line by line into the file, as far as a document may run (InlinePictures stops there and says so).
                FileOutputStream(file).use { out ->
                    var written = 0L
                    while (written <= SiteLimits.MAX_DOC_BYTES) {
                        val line = lines.line() ?: break
                        val bytes = (line + "\n").encodeToByteArray()
                        out.write(bytes)
                        written += bytes.size
                    }
                }
                Head(lines.url, media, lines.header("Content-Disposition"))
            } ?: return null
            return withContext(Dispatchers.Default) {
                FileInputStream(file).use { read(WebStream(head.url, head.type, head.disposition, it)) }
            }
        } finally {
            file.delete()
        }
    }

    private class Head(val url: String, val type: String?, val disposition: String?)

    private fun secure(url: String): String {
        val target = LinkUrls.forFetch(url) ?: throw IOException("Not a web address")
        if (!target.startsWith("https://")) throw IOException("Not a secure address")
        return target
    }

    private fun request(target: String, agent: String, maxBytes: Long, seconds: Long) = NetRequest(
        target,
        mapOf(
            "User-Agent" to agent,
            "Accept" to "text/html,application/xhtml+xml,application/rss+xml,application/atom+xml,application/xml;q=0.9,*/*;q=0.8",
        ),
        maxBytes = maxBytes,
        timeoutMs = seconds * 1000,
        readTimeoutMs = SiteLimits.READ_SECONDS * 1000,
        connectTimeoutMs = SiteLimits.CONNECT_SECONDS * 1000,
    )

    private suspend fun get(target: String, agent: String, maxBytes: Long, accept: (String?) -> Boolean): WebText? {
        // One byte more than asked for says whether there was more.
        val r = net.ask(request(target, agent, if (maxBytes >= Long.MAX_VALUE - 1) maxBytes else maxBytes + 1, SiteLimits.PAGE_SECONDS))
        if (!r.ok) throw LinkHttpException(r.code)
        val type = r.header("Content-Type")
        val media = type?.substringBefore(';')?.trim()?.lowercase()
        if (!accept(media)) return null
        val truncated = r.body.size > maxBytes
        val bytes = if (truncated) r.body.copyOf(maxBytes.toInt()) else r.body
        val head = CharArray(minOf(bytes.size, 4096)) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
        val charset = MetaTags.charset(type, head) ?: XML_ENCODING.find(head)?.groupValues?.get(1)
        return WebText(r.url, media, decode(bytes, charset), truncated, r.header("Content-Disposition"))
    }

    private companion object {
        val XML_ENCODING = Regex("""<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9_.:-]+)""")

        /** The page in its own character set when the phone knows it (Java's Charset.forName), else UTF-8. */
        fun decode(bytes: ByteArray, charset: String?): String {
            val name = charset?.trim()?.lowercase()
            if (name == null || name == "utf-8" || name == "utf8") return bytes.decodeToString()
            val cs = runCatching { Charsets.forName(name) }.getOrNull() ?: return bytes.decodeToString()
            return runCatching { bytes.decodeIn(cs) }.getOrElse { bytes.decodeToString() }
        }
    }
}

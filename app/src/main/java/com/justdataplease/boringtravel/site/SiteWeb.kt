package com.justdataplease.boringtravel.site

import com.justdataplease.boringtravel.google.await
import com.justdataplease.boringtravel.links.LinkHttpException
import com.justdataplease.boringtravel.links.LinkUrls
import com.justdataplease.boringtravel.links.MetaTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit

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
     * GET [url] over https. Null when the answer's media type isn't one [accept] takes (its body is never downloaded);
     * a refusal (404, 403 …) throws [LinkHttpException], no connection an [IOException].
     */
    suspend fun get(url: String, maxBytes: Long = SiteLimits.MAX_PAGE_BYTES, accept: (String?) -> Boolean = { true }): WebText?

    /** Like [get], but [read] gets the body as it arrives (on the IO dispatcher) and its answer is returned. */
    suspend fun <T> stream(url: String, accept: (String?) -> Boolean = { true }, read: (WebStream) -> T): T? =
        get(url, Long.MAX_VALUE / 2, accept)?.let { read(WebStream(it.url, it.type, it.disposition, it.text.byteInputStream())) }
}

/**
 * [SiteWeb] over OkHttp. Https only: a plain http address is asked over https and redirects never switch to http. Every
 * request names the app (the same User-Agent as link previews, [com.justdataplease.boringtravel.links.LinkFetcher]) and
 * carries nothing about the traveller (no cookies, no language); a site that refuses it with 403 is asked once more in
 * the crawler form.
 */
class OkSiteWeb(base: OkHttpClient, private val userAgent: String, private val crawlerUserAgent: String = userAgent) : SiteWeb {
    private val client: OkHttpClient = base.newBuilder()
        .connectTimeout(SiteLimits.CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SiteLimits.READ_SECONDS, TimeUnit.SECONDS)
        .callTimeout(SiteLimits.PAGE_SECONDS, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    /** For [stream]: a document with its pictures inside can take minutes on a phone network. */
    private val longClient: OkHttpClient = client.newBuilder().callTimeout(SiteLimits.DOC_SECONDS, TimeUnit.SECONDS).build()

    override suspend fun get(url: String, maxBytes: Long, accept: (String?) -> Boolean): WebText? {
        val target = LinkUrls.forFetch(url) ?: throw IOException("Not a web address")
        if (!target.startsWith("https://")) throw IOException("Not a secure address")
        return try {
            get(target, userAgent, maxBytes, accept)
        } catch (e: LinkHttpException) {
            if (e.code != 403 || crawlerUserAgent == userAgent) throw e
            get(target, crawlerUserAgent, maxBytes, accept)
        }
    }

    override suspend fun <T> stream(url: String, accept: (String?) -> Boolean, read: (WebStream) -> T): T? {
        val target = LinkUrls.forFetch(url) ?: throw IOException("Not a web address")
        if (!target.startsWith("https://")) throw IOException("Not a secure address")
        longClient.newCall(request(target, userAgent)).await().use { response ->
            if (!response.isSuccessful) throw LinkHttpException(response.code)
            val media = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (!accept(media)) return null
            val body = response.body ?: throw IOException("Empty answer")
            return withContext(Dispatchers.IO) {
                body.byteStream().use { read(WebStream(response.request.url.toString(), media, response.header("Content-Disposition"), it)) }
            }
        }
    }

    private fun request(target: String, agent: String): Request = Request.Builder().url(target)
        .header("User-Agent", agent)
        .header("Accept", "text/html,application/xhtml+xml,application/rss+xml,application/atom+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    private suspend fun get(target: String, agent: String, maxBytes: Long, accept: (String?) -> Boolean): WebText? {
        val request = request(target, agent)
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw LinkHttpException(response.code)
            val type = response.header("Content-Type")
            val media = type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (!accept(media)) return null
            val body = response.body ?: return WebText(response.request.url.toString(), media, "", false)
            val (bytes, truncated) = withContext(Dispatchers.IO) { readAtMost(body.source(), maxBytes) }
            val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
            val charset = (MetaTags.charset(type, head) ?: XML_ENCODING.find(head)?.groupValues?.get(1))
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            return WebText(response.request.url.toString(), media, String(bytes, charset), truncated, response.header("Content-Disposition"))
        }
    }

    private companion object {
        val XML_ENCODING = Regex("""<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9_.:-]+)""")

        /** Up to [max] bytes of a body (the rest is never downloaded), and whether there was more. */
        fun readAtMost(source: BufferedSource, max: Long): Pair<ByteArray, Boolean> {
            source.request(max + 1)
            val more = source.buffer.size > max
            return source.buffer.readByteArray(minOf(source.buffer.size, max)) to more
        }
    }
}

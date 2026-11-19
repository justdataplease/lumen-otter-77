package com.justdataplease.boringtravel.links

import com.justdataplease.boringtravel.google.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A site answered, but not with the page (403, 404, 500 …). */
class LinkHttpException(val code: Int) : IOException("The site answered $code")

/**
 * The network side of link previews, kept thin: each call is one request (or a short chain of redirects) and hands
 * strings to the pure parsers ([MetaTags], [MapsLinks], [WikiPhotos], [PlacePhotos], [WikiCredits]). Calls run on
 * OkHttp's threads, bodies are read on the IO dispatcher and parsed off the main thread; [client] limits each call to
 * a few seconds. Every request names the app ([appUserAgent], [userAgent]); a page that refuses it with 403 is asked
 * once more in the crawler form link-preview bots use ([crawlerUserAgent]), which still names the app and claims no
 * browser.
 */
internal class LinkFetcher(
    private val client: OkHttpClient,
    private val appUserAgent: String,
    private val crawlerUserAgent: String = appUserAgent,
) {
    private val noRedirects: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** For a whole list of places (a few hundred kB): more time than a preview gets. */
    private val patient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(LIST_SECONDS, TimeUnit.SECONDS).callTimeout(LIST_SECONDS, TimeUnit.SECONDS).build()
    }

    /** The page's preview tags, read from at most [MAX_PAGE_BYTES] of it. A link straight to a picture is the picture. */
    suspend fun page(url: String): PageMeta {
        val target = LinkUrls.forFetch(url) ?: throw IOException("Not a web link")
        return try {
            page(target, appUserAgent)
        } catch (e: LinkHttpException) {
            if (e.code != 403 || crawlerUserAgent == appUserAgent) throw e
            page(target, crawlerUserAgent)
        }
    }

    private suspend fun page(target: String, userAgent: String): PageMeta {
        val request = Request.Builder().url(target)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", acceptLanguage())
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw LinkHttpException(response.code)
            val finalUrl = response.request.url.toString()
            val type = response.header("Content-Type")
            val media = type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (media != null && media.startsWith("image/")) return PageMeta(imageUrl = finalUrl)
            if (media != null && "html" !in media && "xml" !in media) return PageMeta()
            val body = response.body ?: return PageMeta()
            val bytes = withContext(Dispatchers.IO) { readAtMost(body.source(), MAX_PAGE_BYTES) }
            return withContext(Dispatchers.Default) {
                val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
                val charset = MetaTags.charset(type, head)?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                MetaTags.parse(String(bytes, charset), finalUrl)
            }
        }
    }

    /** True when [url] answers 200 with a picture; Google's favicon service answers 404 for sites it doesn't know. */
    suspend fun isImage(url: String): Boolean {
        val request = Request.Builder().url(url).header("User-Agent", appUserAgent).build()
        client.newCall(request).await().use { r ->
            return r.isSuccessful && r.header("Content-Type").orEmpty().startsWith("image/")
        }
    }

    /**
     * The long Maps link behind a short one (`maps.app.goo.gl/…`), by following its redirects one at a time and
     * stopping at the first long Maps link, so Google's consent page and the Maps page itself are never loaded.
     * Asked with the app's own User-Agent (with a phone browser's, Google answers with an intent:// redirect).
     */
    suspend fun expandMaps(url: String): String? {
        var current = url
        repeat(MAX_HOPS) {
            current = MapsLinks.unwrapConsent(current)
            if (MapsLinks.isMaps(current) && !MapsLinks.isShort(current)) return current
            val target = LinkUrls.forFetch(current) ?: return null
            val request = Request.Builder().url(target).header("User-Agent", appUserAgent).build()
            val next: String = noRedirects.newCall(request).await().use { r ->
                if (r.isSuccessful) {
                    val body = r.body ?: return null
                    val bytes = withContext(Dispatchers.IO) { readAtMost(body.source(), MAX_REDIRECT_PAGE_BYTES) }
                    return withContext(Dispatchers.Default) { MapsLinks.findInHtml(String(bytes, Charsets.UTF_8)) }
                }
                if (!r.isRedirect) throw LinkHttpException(r.code)
                val location = r.header("Location") ?: return null
                MapsLinks.fromIntent(location) ?: r.request.url.resolve(location)?.toString() ?: return null
            }
            current = next
        }
        return null
    }

    /**
     * The list of saved places behind a Google Maps link ([MapsLists]): a short link followed first, then the list's
     * places asked for as its own page asks. Null when the link isn't a list's (a place, a search); a list that gives
     * nothing (not shared, or Google answered something else) throws [MapsListException].
     */
    suspend fun mapsList(url: String): MapsList? {
        val long = (if (MapsLinks.isShort(url)) expandMaps(url) else MapsLinks.unwrapConsent(url)) ?: return null
        val ref = MapsLists.ref(long) ?: return null
        for (ask in MapsLists.requestUrls(ref)) {
            val request = Request.Builder().url(ask).header("User-Agent", appUserAgent).build()
            val text = patient.newCall(request).await().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body ?: return@use null
                withContext(Dispatchers.IO) { String(readAtMost(body.source(), MapsLists.MAX_BYTES), Charsets.UTF_8) }
            }
            text?.let { t -> withContext(Dispatchers.Default) { MapsLists.parse(t) } }?.let { return it }
        }
        throw MapsListException()
    }

    /**
     * One answer of Wikipedia's query API ([WikiPhotos.queryUrl], [PlacePhotos.titleQueryUrl]); Wikimedia asks for a
     * named client.
     */
    suspend fun wikipedia(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", appUserAgent).build()
        client.newCall(request).await().use { r ->
            if (!r.isSuccessful) throw LinkHttpException(r.code)
            val body = r.body ?: return ""
            return withContext(Dispatchers.IO) { String(readAtMost(body.source(), MAX_PAGE_BYTES), Charsets.UTF_8) }
        }
    }

    companion object {
        /** The app's page, in every User-Agent. */
        const val APP_PAGE = "https://justdataplease.com/apps/boringtravel/"

        /** "BoringTravel/1.0.0 (Android; +https://justdataplease.com/apps/boringtravel/)". */
        fun userAgent(version: String): String = "BoringTravel/$version (Android; +$APP_PAGE)"

        /**
         * After a 403 only: the form crawlers and link-preview bots use ("Mozilla/5.0 (compatible; Name/version;
         * +page)"). It names the app and its page and claims no browser or device.
         */
        fun crawlerUserAgent(version: String): String = "Mozilla/5.0 (compatible; BoringTravel/$version; +$APP_PAGE)"
        const val MAX_PAGE_BYTES = 512L * 1024
        private const val MAX_REDIRECT_PAGE_BYTES = 256L * 1024
        private const val MAX_HOPS = 6
        private const val LIST_SECONDS = 30L

        private fun acceptLanguage(): String {
            val tag = Locale.getDefault().toLanguageTag()
            return if (tag.startsWith("en")) "$tag,en;q=0.9" else "$tag,en;q=0.8"
        }

        /** Up to [max] bytes of a body (the rest is never downloaded). */
        private fun readAtMost(source: BufferedSource, max: Long): ByteArray {
            source.request(max)
            return source.buffer.readByteArray(minOf(source.buffer.size, max))
        }
    }
}

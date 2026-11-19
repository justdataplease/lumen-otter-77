package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.ios.UrlCodec
import com.justdataplease.boringtravel.ios.WebUrl

/*
 * OkHttp's HttpUrl as the Android app's Copy from a link reads and builds addresses (site/: toHttpUrlOrNull, its host,
 * path, query; a query parameter set or added; the fragment dropped), under the same names over this app's [WebUrl],
 * which reads an address the way OkHttp does. Only what those files call.
 */

/** okhttp3.HttpUrl. */
typealias HttpUrl = WebUrl

/** OkHttp's `String.toHttpUrlOrNull()`: null for anything but a well-formed http(s) address. */
fun String.toHttpUrlOrNull(): HttpUrl? = WebUrl.of(this)

/** OkHttp's `String.toHttpUrl()`: throws for anything but a well-formed http(s) address. */
fun String.toHttpUrl(): HttpUrl = WebUrl.of(this) ?: throw IllegalArgumentException("Invalid URL: $this")

/** OkHttp's `HttpUrl.newBuilder()`. */
fun HttpUrl.newBuilder(): HttpUrlBuilder = HttpUrlBuilder(this)

/**
 * OkHttp's HttpUrl.Builder for what the site files change: query parameters ([addQueryParameter], [setQueryParameter],
 * encoded as OkHttp encodes a query component) and the fragment.
 */
class HttpUrlBuilder internal constructor(private val base: WebUrl) {
    /** The query as name=value pairs, as written; a name without "=" has a null value. */
    private val pairs: MutableList<Pair<String, String?>> = base.encodedQuery?.split('&')?.map { p ->
        val eq = p.indexOf('=')
        if (eq < 0) p to null else p.substring(0, eq) to p.substring(eq + 1)
    }?.toMutableList() ?: mutableListOf()
    private var hasQuery = base.encodedQuery != null
    private var fragment: String? = base.encodedFragment

    fun addQueryParameter(name: String, value: String?): HttpUrlBuilder = apply {
        pairs += UrlCodec.queryComponent(name) to value?.let(UrlCodec::queryComponent)
        hasQuery = true
    }

    fun setQueryParameter(name: String, value: String?): HttpUrlBuilder = apply {
        val encoded = UrlCodec.queryComponent(name)
        pairs.removeAll { it.first == encoded }
        addQueryParameter(name, value)
    }

    fun fragment(fragment: String?): HttpUrlBuilder = apply {
        this.fragment = fragment?.let { WebUrl.canonical(it, "", alreadyEncoded = false) }
    }

    fun build(): HttpUrl {
        val query = if (!hasQuery) null else pairs.joinToString("&") { (k, v) -> if (v == null) k else "$k=$v" }
        return base.withQueryAndFragment(query, fragment)
    }
}

package com.justdataplease.boringtravel.compat

import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetRequest
import com.justdataplease.boringtravel.ios.ask

/*
 * The part of OkHttp the Android app's own-key files call (ai/own/OwnKeyApi.kt, ToolRest.kt: a client, a request built
 * with a URL, headers and a JSON body, the call awaited, the answer's status and text), under the same names over this
 * app's [Net], so those files are read in with only their imports changed. The client carries the time limits the
 * Android app builds its OkHttp client with; every answer is read whole (the AI's answers and model lists).
 */

/**
 * OkHttp's client: [connectTimeoutMs], [readTimeoutMs] (silence on the line) and [callTimeoutMs] (the whole call;
 * [NetRequest.NO_LIMIT] as OkHttp's 0), and at most [maxBytes] of an answer.
 */
class OkHttpClient(
    val net: Net,
    val connectTimeoutMs: Long = 10_000,
    val readTimeoutMs: Long = 10_000,
    val callTimeoutMs: Long = NetRequest.NO_LIMIT,
    val maxBytes: Long = 32L * 1024 * 1024,
) {
    fun newCall(request: Request): Call = Call(this, request)
}

/** A media type ("application/json; charset=utf-8"). */
class MediaType internal constructor(val text: String) {
    override fun toString(): String = text
}

fun String.toMediaType(): MediaType = MediaType(this)

/** A request's body: text sent as [type]. */
class RequestBody internal constructor(val text: String, val type: MediaType?)

fun String.toRequestBody(contentType: MediaType? = null): RequestBody = RequestBody(this, contentType)

/** A GET or a POST: its address, headers and body. */
class Request private constructor(val url: String, val headers: Map<String, String>, val body: RequestBody?) {
    val method: String get() = if (body == null) "GET" else "POST"

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    class Builder {
        private var url: String? = null
        private val headers = LinkedHashMap<String, String>()
        private var body: RequestBody? = null

        fun url(url: String): Builder = apply { this.url = url }

        /** Sets [name] to [value], replacing what it had (OkHttp's header, not addHeader). */
        fun header(name: String, value: String): Builder = apply {
            headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { headers.remove(it) }
            headers[name] = value
        }

        fun get(): Builder = apply { body = null }

        fun post(body: RequestBody): Builder = apply { this.body = body }

        fun build(): Request = Request(requireNotNull(url) { "url == null" }, headers.toMap(), body)
    }
}

/** A request about to be sent by its client ([await]). */
class Call internal constructor(private val client: OkHttpClient, private val request: Request) {
    internal suspend fun execute(): Response {
        val answer = client.net.ask(
            NetRequest(
                request.url, request.headers, maxBytes = client.maxBytes, timeoutMs = client.callTimeoutMs,
                readTimeoutMs = client.readTimeoutMs, body = request.body?.text,
                contentType = request.body?.type?.text ?: "application/json; charset=utf-8", connectTimeoutMs = client.connectTimeoutMs,
            ),
        )
        return Response(answer.code, ResponseBody(answer.body), answer.headersOf())
    }

    private fun com.justdataplease.boringtravel.ios.NetAnswer.headersOf(): (String) -> String? = { header(it) }
}

/** Sends the call and waits for its answer; no answer at all throws the network's IOException, as OkHttp does. */
suspend fun Call.await(): Response = execute()

/** An answer: its status, its body read whole, its headers. */
class Response internal constructor(val code: Int, val body: ResponseBody?, private val headers: (String) -> String?) : AutoCloseable {
    val isSuccessful: Boolean get() = code in 200..299

    fun header(name: String): String? = headers(name)

    override fun close() {}
}

/** An answer's body. */
class ResponseBody internal constructor(private val bytes: ByteArray) {
    fun string(): String = bytes.decodeToString()
}

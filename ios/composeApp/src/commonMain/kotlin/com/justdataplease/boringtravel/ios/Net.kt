package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.compat.ConnectException
import com.justdataplease.boringtravel.compat.SSLHandshakeException
import com.justdataplease.boringtravel.compat.SocketTimeoutException
import com.justdataplease.boringtravel.io.IOException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readLine
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

/*
 * Everything the app asks of the network goes through [Net]: a GET or a JSON POST at a time, with the app's own
 * User-Agent, a time limit and a cap on how much of the answer is read (what the Android app sets on its OkHttp clients
 * per service), and answers read line by line as they come (the AI planner's server-sent events). The phone's side is
 * Ktor ([KtorNet]: URLSession on iPhone, Java's HTTP client on the desktop); the tests use a fake fed with recorded
 * answers, so they never reach the network.
 */

/**
 * One request: a GET, or a POST of [body] as [contentType]. [timeoutMs] is the whole call's limit (OkHttp's
 * callTimeout; [NO_LIMIT]: none), [readTimeoutMs] the wait for the next bytes, [connectTimeoutMs] the wait to connect.
 */
data class NetRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** False: a redirect comes back as it is (its Location header), to be followed one step at a time. */
    val followRedirects: Boolean = true,
    /** At most this much of the body is read; the rest is never downloaded. */
    val maxBytes: Long = 512L * 1024,
    val timeoutMs: Long = 8_000,
    val readTimeoutMs: Long = 8_000,
    /** A POST's body (JSON text); null for a GET. */
    val body: String? = null,
    val contentType: String = "application/json; charset=utf-8",
    /** Null: no limit of its own, only [timeoutMs]. */
    val connectTimeoutMs: Long? = null,
) {
    val method: String get() = if (body == null) "GET" else "POST"

    companion object {
        /** No limit for the whole call (OkHttp's callTimeout 0): the AI's answers take as long as they take. */
        const val NO_LIMIT = Long.MAX_VALUE
    }
}

/**
 * A call of any method with a binary body (since round 23, for Google's REST APIs: Drive's uploads in one piece or in
 * resumable pieces, its downloads, OAuth's forms): [method] "GET", "POST", "PUT", "PATCH" or "DELETE"; [body] null for
 * none. The time limits are the Android app's Google client's (GoogleAccount.http: connect 20 s, read 60 s): [timeoutMs]
 * the whole call's ([NetRequest.NO_LIMIT]: none, as OkHttp's callTimeout 0), [readTimeoutMs] the wait for the next
 * bytes. At most [maxBytes] of the answer is read by [Net.send]; [Net.download] hands over the whole body in pieces.
 */
class NetCall(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val maxBytes: Long = 8L * 1024 * 1024,
    val timeoutMs: Long = NetRequest.NO_LIMIT,
    val readTimeoutMs: Long = 60_000,
    val connectTimeoutMs: Long? = 20_000,
)

/** An answer: its status, the address it came from in the end (after redirects), its headers and up to maxBytes of its body. */
class NetAnswer(
    val code: Int,
    val url: String,
    headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    private val byName = headers.mapKeys { it.key.lowercase() }

    fun header(name: String): String? = byName[name.lowercase()]

    val ok: Boolean get() = code in 200..299

    /** 301, 302, 303, 307 or 308 (OkHttp's isRedirect). */
    val redirect: Boolean get() = code in setOf(301, 302, 303, 307, 308)

    fun text(): String = body.decodeToString()
}

/**
 * An answer read as it arrives, line by line (server-sent events): its status and headers first, then [line] until it
 * gives null at the end. Stopping (cancelling the coroutine) closes the connection.
 */
class NetLines(val code: Int, val url: String, headers: Map<String, String>, private val next: suspend () -> String?) {
    private val byName = headers.mapKeys { it.key.lowercase() }

    fun header(name: String): String? = byName[name.lowercase()]

    val ok: Boolean get() = code in 200..299

    /** The next line without its line break; null at the end of the answer. */
    suspend fun line(): String? = next()

    /** The rest of the answer as one text (an error's body), at most [maxChars] characters. */
    suspend fun rest(maxChars: Int = 64 * 1024): String = buildString {
        while (length < maxChars) {
            val l = next() ?: break
            if (isNotEmpty()) append('\n')
            append(l)
        }
    }.take(maxChars)

    companion object {
        /** A whole answer read as lines (a network that can't stream, recorded answers). */
        fun of(answer: NetAnswer): NetLines {
            val lines = answer.text().split('\n').map { it.removeSuffix("\r") }.toMutableList()
            if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
            val queue = ArrayDeque(lines)
            val headers = buildMap {
                listOf("Content-Type", "Content-Disposition", "Retry-After", "Retry-After-Ms", "X-Should-Retry", "Location").forEach { h -> answer.header(h)?.let { put(h, it) } }
            }
            return NetLines(answer.code, answer.url, headers) { queue.removeFirstOrNull() }
        }
    }
}

/** Why no answer came: no connection, too slow, or a secure connection the phone refused. */
enum class NetFailure { Offline, Timeout, Insecure }

/**
 * No connection, a time limit reached, a refused secure connection: what OkHttp throws as an IOException on Android.
 * Its [cause] is the Java exception Android would have seen for [failure] (compat/JavaNet.kt), which the AI planner's
 * error words go by (Android's OwnKeyError.ofNetwork and PlannerError.of).
 */
class NetException(message: String, val failure: NetFailure = NetFailure.Offline) : IOException(message) {
    override val cause: Throwable? = when (failure) {
        NetFailure.Offline -> ConnectException(message)
        NetFailure.Timeout -> SocketTimeoutException(message)
        NetFailure.Insecure -> SSLHandshakeException(message)
    }
}

/** The network as the app uses it. */
interface Net {
    /**
     * False when the phone knows it has no connection (Network's path monitor on iPhone): nothing is asked then, and
     * [get] fails at once as it does offline.
     */
    fun online(): Boolean = true

    /** One GET; throws [NetException] when no answer came (offline, too slow), never for an HTTP status. */
    suspend fun get(request: NetRequest): NetAnswer

    /** One POST of the request's body; failures as [get]. A network that only reads (previews, tests) refuses it. */
    suspend fun post(request: NetRequest): NetAnswer = throw NetException("No connection")

    /**
     * One request whose answer [read] reads line by line as it arrives (server-sent events); what [read] gives back is
     * the result. Leaving [read], or cancelling the coroutine, closes the connection. Failures before the answer came
     * as [get]; [read]'s own exceptions pass through as they are.
     */
    suspend fun <T> stream(request: NetRequest, read: suspend (NetLines) -> T): T =
        read(NetLines.of(if (request.body == null) get(request) else post(request)))

    /** One call of any method ([NetCall]); failures as [get]. A network that only reads (previews, tests) refuses it. */
    suspend fun send(call: NetCall): NetAnswer = throw NetException("No connection")

    /**
     * [send], a successful answer's body handed to [piece] as it arrives (a download into a file; the answer's own body
     * is then empty); an error's body is read whole, as [send] reads it. Exceptions [piece] throws pass through.
     */
    suspend fun download(call: NetCall, piece: (ByteArray, Int) -> Unit): NetAnswer {
        val answer = send(call)
        if (!answer.ok) return answer
        if (answer.body.isNotEmpty()) piece(answer.body, answer.body.size)
        return NetAnswer(answer.code, answer.url)
    }

    /**
     * Whether the connection is one the phone counts as costly (cellular, a personal hotspot, Low Data Mode: Network's
     * path on iPhone; Android's isActiveNetworkMetered): with "Only on Wi‑Fi" the backup's videos and the daily backup
     * wait for another one.
     */
    fun metered(): Boolean = false
}

/** [send] that asks nothing while the phone says it is offline. */
suspend fun Net.call(call: NetCall): NetAnswer {
    if (!online()) throw NetException("No connection")
    return send(call)
}

/** [download] that asks nothing while the phone says it is offline. */
suspend fun Net.fetch(call: NetCall, piece: (ByteArray, Int) -> Unit): NetAnswer {
    if (!online()) throw NetException("No connection")
    return download(call, piece)
}

/** A phone without a connection: every request fails at once. Previews and tests that never reach the network. */
object OfflineNet : Net {
    override fun online(): Boolean = false

    override suspend fun get(request: NetRequest): NetAnswer = throw NetException("No connection")
}

/** [get] or [post] (a request with a body) that asks nothing while the phone says it is offline. */
suspend fun Net.ask(request: NetRequest): NetAnswer {
    if (!online()) throw NetException("No connection")
    return if (request.body == null) get(request) else post(request)
}

/** [stream] that asks nothing while the phone says it is offline. */
suspend fun <T> Net.askStream(request: NetRequest, read: suspend (NetLines) -> T): T {
    if (!online()) throw NetException("No connection")
    return stream(request, read)
}

/**
 * Why a request failed, from the exceptions' names (Ktor's and the engines'): a time limit, a secure connection
 * refused, else no connection. The iPhone adds URLSession's own error codes ([KtorNet]'s failure).
 */
fun failureOf(error: Throwable): NetFailure {
    val names = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(8).map { it::class.simpleName.orEmpty() }.toList()
    return when {
        names.any { "Timeout" in it } -> NetFailure.Timeout
        names.any { "SSL" in it || "Certificate" in it || "Handshake" in it } -> NetFailure.Insecure
        else -> NetFailure.Offline
    }
}

/**
 * [Net] over Ktor. [client] makes the HttpClient for following redirects or not (Darwin on iPhone, Java on the
 * desktop); [online] is the phone's own view of its connection; [failure] sorts what Ktor threw ([failureOf]).
 */
class KtorNet(
    client: (followRedirects: Boolean) -> HttpClient,
    private val online: () -> Boolean = { true },
    private val failure: (Throwable) -> NetFailure = ::failureOf,
    /** Whether the connection is a costly one ([Net.metered]); iPhone: Network's path. */
    private val metered: () -> Boolean = { false },
) : Net {
    private val following: HttpClient by lazy { client(true) }
    private val stepping: HttpClient by lazy { client(false) }

    override fun online(): Boolean = online.invoke()

    override fun metered(): Boolean = metered.invoke()

    override suspend fun send(call: NetCall): NetAnswer = try {
        prepared(call).execute { response ->
            val body = response.bodyAsChannel().readRemaining(call.maxBytes).readByteArray()
            NetAnswer(response.status.value, response.call.request.url.toString(), headersOf(response), body)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw NetException(e.message ?: "No answer", failure(e))
    }

    override suspend fun download(call: NetCall, piece: (ByteArray, Int) -> Unit): NetAnswer = try {
        prepared(call).execute { response ->
            val code = response.status.value
            val url = response.call.request.url.toString()
            val channel = response.bodyAsChannel()
            // An error's body is Google's JSON, read whole; a file goes on to [piece] as it comes.
            if (code !in 200..299) return@execute NetAnswer(code, url, headersOf(response), channel.readRemaining(64L * 1024).readByteArray())
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = try {
                    channel.readAvailable(buffer, 0, buffer.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw NetException(e.message ?: "The answer stopped", failure(e))
                }
                if (n < 0) break
                if (n == 0) continue
                try {
                    piece(buffer, n)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw Passed(e)
                }
            }
            NetAnswer(code, url, headersOf(response))
        }
    } catch (e: Passed) {
        throw e.error
    } catch (e: NetException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw NetException(e.message ?: "No answer", failure(e))
    }

    private suspend fun prepared(call: NetCall) = following.prepareRequest(call.url) {
        method = HttpMethod.parse(call.method)
        call.headers.forEach { (k, v) -> if (!k.equals("Content-Type", ignoreCase = true) && !k.equals("Content-Length", ignoreCase = true)) header(k, v) }
        call.body?.let { bytes -> setBody(ByteArrayContent(bytes, call.contentType?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream)) }
        timeout {
            requestTimeoutMillis = call.timeoutMs
            socketTimeoutMillis = call.readTimeoutMs
            call.connectTimeoutMs?.let { connectTimeoutMillis = it }
        }
    }

    override suspend fun get(request: NetRequest): NetAnswer = send(request)

    override suspend fun post(request: NetRequest): NetAnswer = send(request)

    private suspend fun send(request: NetRequest): NetAnswer = try {
        prepared(request).execute { response ->
            val body = response.bodyAsChannel().readRemaining(request.maxBytes).readByteArray()
            NetAnswer(response.status.value, response.call.request.url.toString(), headersOf(response), body)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw NetException(e.message ?: "No answer", failure(e))
    }

    /** What [stream]'s reader threw, carried past the network's own handling as it is. */
    private class Passed(val error: Throwable) : Exception()

    override suspend fun <T> stream(request: NetRequest, read: suspend (NetLines) -> T): T = try {
        prepared(request).execute { response ->
            val channel = response.bodyAsChannel()
            val lines = NetLines(response.status.value, response.call.request.url.toString(), headersOf(response)) {
                try {
                    channel.readLine()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw NetException(e.message ?: "The answer stopped", failure(e))
                }
            }
            try {
                read(lines)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Passed(e)
            }
        }
    } catch (e: Passed) {
        throw e.error
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw NetException(e.message ?: "No answer", failure(e))
    }

    private suspend fun prepared(request: NetRequest) = (if (request.followRedirects) following else stepping).prepareRequest(request.url) {
        configure(request)
    }

    private fun HttpRequestBuilder.configure(request: NetRequest) {
        method = if (request.body == null) HttpMethod.Get else HttpMethod.Post
        request.headers.forEach { (k, v) -> if (!k.equals("Content-Type", ignoreCase = true)) header(k, v) }
        request.body?.let { setBody(TextContent(it, ContentType.parse(request.contentType))) }
        timeout {
            requestTimeoutMillis = request.timeoutMs
            socketTimeoutMillis = request.readTimeoutMs
            request.connectTimeoutMs?.let { connectTimeoutMillis = it }
        }
    }

    private fun headersOf(response: HttpResponse): Map<String, String> =
        response.headers.entries().associate { (k, v) -> k to v.joinToString(", ") }

    companion object {
        /** What every client is made with: time limits per request, no exception for an HTTP status. */
        fun HttpClient.forApp(): HttpClient = config {
            install(HttpTimeout)
            expectSuccess = false
        }
    }
}

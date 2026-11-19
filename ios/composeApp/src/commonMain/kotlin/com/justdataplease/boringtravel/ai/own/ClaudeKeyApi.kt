package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftSchema
import com.justdataplease.boringtravel.ai.SchemaNode
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetException
import com.justdataplease.boringtravel.ios.NetLines
import com.justdataplease.boringtravel.ios.NetRequest
import com.justdataplease.boringtravel.ios.ask
import com.justdataplease.boringtravel.ios.askStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

/*
 * The Android app's ai/own/ClaudeKeyApi.kt, written again here: Android goes through Anthropic's Java SDK, which has no
 * iPhone build. This phone sends the same requests to the Messages API over plain HTTPS ([Net]): the JSON the SDK sends
 * for the same call (ClaudeAgainstSdkTest compares them), the draft streamed as server-sent events and gathered as the
 * SDK's accumulator gathers them, one retry as the SDK makes it, the same time limits and the SDK's errors in the same
 * plain words.
 */

/**
 * Claude with an Anthropic API key: the models list is the key test, the draft is a streamed Messages request with
 * structured output ([ClaudeRequests]). One retry ([MAX_RETRIES]) for a refusal or a dropped connection before any
 * answer; the list and Test's request get [LIST_TIMEOUT_MS] and [PING_TIMEOUT_MS]. The draft streams, so a long answer is
 * never cut by a read timeout, its one attempt stays inside the planner's 190 s cap ([DRAFT_TIMEOUT_MS]), and a
 * cancelled or capped plan closes the stream: Anthropic stops writing (and billing) it.
 */
class ClaudeKeyApi(
    private val net: Net,
    /** How the retry waits (tests don't wait). */
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : OwnKeyApi {
    override val provider = OwnAiProvider.Claude

    /** Nothing is held per key here (Android's SDK client is): kept for Remove key and Delete account. */
    fun forget() {}

    override suspend fun models(key: String): List<String> = call {
        val ids = mutableListOf<String>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val url = "$BASE/v1/models?limit=$PAGE" + (after?.let { "&after_id=$it" } ?: "")
            val page = json.parseToJsonElement(send(get(url, key, LIST_TIMEOUT_MS))).jsonObject
            val data = (page["data"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
            ids += data
            val more = (page["has_more"] as? JsonPrimitive)?.contentOrNull == "true"
            after = (page["last_id"] as? JsonPrimitive)?.contentOrNull
            if (!more || data.isEmpty() || after == null) return@call ids
        }
        ids
    }

    private val latest = LatestModels(provider) { models(it) }

    override suspend fun resolve(key: String, model: String): String = latest.resolve(key, model)

    override suspend fun ping(key: String, model: String) {
        val resolved = resolve(key, model)
        call {
            val request = ClaudeRequests.ping(resolved)
            ClaudeRequests.reply(ClaudeRequests.message(json.parseToJsonElement(send(post(request, key, stream = false, PING_TIMEOUT_MS, PING_TIMEOUT_MS))).jsonObject), requireText = false)
        }
    }

    override suspend fun draft(key: String, model: String, system: String, turns: List<ChatTurn>): OwnKeyReply = call {
        ClaudeRequests.reply(streamed(key, ClaudeRequests.draft(model, system, turns)))
    }

    /** Like a draft: streamed, with the schema ([JsonSchemas.Dialect.Claude]) in the system prompt. */
    override suspend fun answer(key: String, model: String, system: String, turns: List<ChatTurn>, schema: SchemaNode.Obj): OwnKeyReply = call {
        ClaudeRequests.reply(streamed(key, ClaudeRequests.draft(model, system, turns, JsonSchemas.of(schema, JsonSchemas.Dialect.Claude))))
    }

    /** A refusal before any answer: its status, body and the headers the retry reads. */
    private class Refused(val status: Int, val body: String, val retry: Boolean?, val waitMs: Long?) : Exception("HTTP $status")

    /** The answer gathered from the stream ([ClaudeStream]); the stream is closed however this ends. */
    private suspend fun streamed(key: String, request: ClaudeRequest): ClaudeMessage = retrying {
        net.askStream(post(request, key, stream = true, DRAFT_TIMEOUT_MS, DRAFT_READ_TIMEOUT_MS)) { lines ->
            if (!lines.ok) throw refused(lines.code, lines.rest(), lines::header)
            try {
                ClaudeStream.read(lines)
            } catch (e: NetException) {
                // The answer had begun: the SDK doesn't ask again.
                throw OwnKeyException(OwnKeyError.ofNetwork(e), cause = e)
            }
        }
    }

    /** One non-streamed request's body text, retried once as the SDK retries. */
    private suspend fun send(request: NetRequest): String = retrying {
        val r = net.ask(request)
        if (r.code !in 200..299) throw refused(r.code, r.text(), r::header)
        r.text()
    }

    private fun refused(status: Int, body: String, header: (String) -> String?): Refused {
        val retryAfter = header("retry-after-ms")?.toDoubleOrNull()?.toLong()
            ?: header("retry-after")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        return Refused(status, body, header("x-should-retry")?.lowercase()?.let { it == "true" }, retryAfter)
    }

    /**
     * [attempt], and once more after a refusal the SDK retries (408, 409, 429, 5xx, or the server's x-should-retry) or a
     * failure before any answer, after the SDK's wait (the server's Retry-After up to a minute, else about half a second).
     */
    private suspend fun <T> retrying(attempt: suspend () -> T): T {
        var tries = 0
        while (true) {
            try {
                return attempt()
            } catch (e: Refused) {
                val again = e.retry ?: (e.status == 408 || e.status == 409 || e.status == 429 || e.status >= 500)
                if (!again || tries >= MAX_RETRIES) throw e
                wait(e.waitMs?.takeIf { it in 1..60_000 } ?: backoff())
            } catch (e: NetException) {
                if (tries >= MAX_RETRIES) throw e
                wait(backoff())
            }
            tries++
        }
    }

    /** The SDK's first wait: half a second, less up to a quarter at random. */
    private fun backoff(): Long = (500 * (1 - 0.25 * Random.nextDouble())).toLong()

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: OwnKeyException) {
        throw e
    } catch (e: Refused) {
        throw OwnKeyException(ClaudeRequests.errorOf(e.status, e.body), e.status)
    } catch (e: Exception) {
        throw OwnKeyException(ClaudeRequests.errorOf(e), cause = e)
    }

    private fun headers(key: String, betas: List<String> = emptyList(), stream: Boolean = false) = buildMap {
        put("x-api-key", key)
        put("anthropic-version", API_VERSION)
        put("Accept", if (stream) "text/event-stream" else "application/json")
        if (betas.isNotEmpty()) put("anthropic-beta", betas.joinToString(","))
    }

    private fun get(url: String, key: String, timeoutMs: Long) =
        NetRequest(url, headers(key), maxBytes = MAX_BYTES, timeoutMs = timeoutMs, readTimeoutMs = timeoutMs, connectTimeoutMs = CONNECT_TIMEOUT_MS)

    /** The Messages request ([ClaudeRequest.body]; "stream": true for a stream), on the beta path the SDK's beta service uses. */
    private fun post(request: ClaudeRequest, key: String, stream: Boolean, timeoutMs: Long, readMs: Long): NetRequest {
        val body = if (stream) JsonObject(request.body + ("stream" to JsonPrimitive(true))) else request.body
        return NetRequest(
            MESSAGES_URL, headers(key, request.betas, stream), maxBytes = MAX_BYTES, timeoutMs = timeoutMs, readTimeoutMs = readMs,
            body = body.toString(), contentType = "application/json", connectTimeoutMs = CONNECT_TIMEOUT_MS,
        )
    }

    companion object {
        const val BASE = "https://api.anthropic.com"
        const val MESSAGES_URL = "$BASE/v1/messages?beta=true"
        const val API_VERSION = "2023-06-01"
        private const val PAGE = 100
        private const val MAX_PAGES = 5
        private const val MAX_BYTES = 16L * 1024 * 1024

        /** One retry: a 429, 5xx or dropped connection before any answer (the SDK's default is two). */
        const val MAX_RETRIES = 1

        /** The list and Test's request: two tries of each (2 × 8 + 2 × 13 s) fit Me › AI's 45 s Test. */
        const val LIST_TIMEOUT_MS = 8_000L
        const val PING_TIMEOUT_MS = 13_000L

        /** A draft's whole stream: inside the planner's 190 s cap, which closes the stream first if a retry ran. */
        const val DRAFT_TIMEOUT_MS = 180_000L

        /** Silence on an open stream (Anthropic sends pings while the model thinks). */
        const val DRAFT_READ_TIMEOUT_MS = 90_000L

        private const val CONNECT_TIMEOUT_MS = 15_000L

        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** A Messages request: its JSON body and the betas it needs (the anthropic-beta header). */
data class ClaudeRequest(val body: JsonObject, val betas: List<String> = emptyList())

/** A Messages answer as the SDK's BetaMessage reads it: why it stopped, its text blocks in order, its token counts. */
data class ClaudeMessage(val stopReason: String?, val texts: List<String>, val inputTokens: Long?, val outputTokens: Long?)

/**
 * A streamed answer gathered as the SDK's BetaMessageAccumulator gathers it: message_start, each content block and its
 * text deltas, message_delta's stop reason and output tokens, message_stop. A stream that ends before message_stop is
 * cut off ([OwnKeyError.Incomplete]); an error event is the error it names.
 */
object ClaudeStream {
    suspend fun read(lines: NetLines): ClaudeMessage {
        var data = StringBuilder()
        var event: String? = null
        val blocks = HashMap<Int, StringBuilder>()
        var input: Long? = null
        var output: Long? = null
        var stop: String? = null
        var stopped = false
        suspend fun dispatch() {
            if (data.isEmpty()) return
            val o = runCatching { ClaudeKeyApi.json.parseToJsonElement(data.toString()) as? JsonObject }.getOrNull()
            data = StringBuilder()
            val type = (o?.get("type") as? JsonPrimitive)?.contentOrNull ?: event
            event = null
            o ?: return
            when (type) {
                "message_start" -> {
                    val usage = (o["message"] as? JsonObject)?.get("usage") as? JsonObject
                    input = (usage?.get("input_tokens") as? JsonPrimitive)?.longOrNull
                    output = (usage?.get("output_tokens") as? JsonPrimitive)?.longOrNull
                }
                "content_block_start" -> {
                    val i = (o["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return
                    val block = o["content_block"] as? JsonObject
                    if (block != null && (block["type"] as? JsonPrimitive)?.contentOrNull == "text") blocks[i] = StringBuilder((block["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                }
                "content_block_delta" -> {
                    val i = (o["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return
                    val delta = o["delta"] as? JsonObject
                    if (delta != null && (delta["type"] as? JsonPrimitive)?.contentOrNull == "text_delta") blocks[i]?.append((delta["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                }
                "message_delta" -> {
                    ((o["delta"] as? JsonObject)?.get("stop_reason") as? JsonPrimitive)?.contentOrNull?.let { stop = it }
                    ((o["usage"] as? JsonObject)?.get("output_tokens") as? JsonPrimitive)?.longOrNull?.let { output = it }
                }
                "message_stop" -> stopped = true
                "error" -> {
                    val error = o["error"] as? JsonObject
                    throw OwnKeyException(ClaudeRequests.errorOfType((error?.get("type") as? JsonPrimitive)?.contentOrNull, 0, o.toString()))
                }
            }
        }
        while (true) {
            val line = lines.line() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        dispatch()
        if (!stopped) throw OwnKeyException(OwnKeyError.Incomplete)
        return ClaudeMessage(stop, blocks.keys.sorted().map { blocks.getValue(it).toString() }, input, output)
    }
}

/**
 * The Messages requests for the planner, as the Android app builds them with the SDK's types (claude-api skill,
 * 2026-11): the system prompt, the chat as user/assistant turns, `max_tokens` 16,000 (streamed by [ClaudeKeyApi]),
 * structured output with [JsonSchemas.Dialect.Claude], explicit `medium` effort, and Anthropic's server-side refusal
 * fallback on Opus and Sonnet 5.5.
 */
object ClaudeRequests {
    const val MAX_TOKENS = 16_000L
    private const val PING_TOKENS = 256L

    /** Server-side fallback (beta): a refused request is retried by Anthropic on a model fit for its category. */
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    /** Opus and Sonnet from 5.5 on take the "default" fallback (claude-api skill, 2026-11-03). */
    fun withFallback(model: String): Boolean = version(model, "opus", "sonnet")?.let { it >= 505 } == true

    /** Models that take `output_config.effort`: every Sonnet, Opus, Haiku and Fable from 5 on. */
    fun withEffort(model: String): Boolean = version(model, "opus", "sonnet", "haiku", "fable")?.let { it >= 500 } == true

    /** "claude-sonnet-5-5" → 505, "claude-opus-5" → 500; null for other families or names. */
    internal fun version(model: String, vararg families: String): Int? {
        val m = Regex("""^claude-([a-z]+)-(\d+)(?:-(\d{1,2}))?(?:-|$)""").find(model) ?: return null
        if (m.groupValues[1] !in families) return null
        return m.groupValues[2].toInt() * 100 + (m.groupValues[3].toIntOrNull() ?: 0)
    }

    val schema: JsonObject by lazy { JsonSchemas.of(DraftSchema.generation, JsonSchemas.Dialect.Claude) }

    /**
     * Every planner tool in one answer. The schema goes in the system prompt, not in `output_config.format`: strict
     * schemas allow at most 24 optional fields per request and the planner's tools have more.
     */
    fun draft(model: String, system: String, turns: List<ChatTurn>, answerSchema: JsonObject = schema): ClaudeRequest {
        val body = buildJsonObject {
            put("max_tokens", MAX_TOKENS)
            putJsonArray("messages") {
                turns.forEach { t ->
                    add(buildJsonObject {
                        put("role", if (t.fromTraveller) "user" else "assistant")
                        if (!t.fromTraveller || t.pictures.isEmpty()) put("content", t.text)
                        // Pictures first, then the words (Anthropic's advice).
                        else putJsonArray("content") {
                            t.pictures.forEach { p ->
                                add(buildJsonObject {
                                    putJsonObject("source") { put("data", p.base64); put("media_type", p.mime); put("type", "base64") }
                                    put("type", "image")
                                })
                            }
                            add(buildJsonObject { put("text", t.text); put("type", "text") })
                        }
                    })
                }
            }
            put("model", model)
            if (withEffort(model)) putJsonObject("output_config") { put("effort", "medium") }
            put("system", system + "\n\nAnswer with one JSON object and nothing else, following this JSON Schema:\n" + answerSchema)
            if (withFallback(model)) put("fallbacks", "default")
        }
        return ClaudeRequest(body, if (withFallback(model)) listOf(FALLBACK_BETA) else emptyList())
    }

    /** The Test button's tiny request, with structured output like a draft ([JsonSchemas.ping]) and `low` effort. */
    fun ping(model: String): ClaudeRequest = ClaudeRequest(buildJsonObject {
        put("max_tokens", PING_TOKENS)
        putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", "Reply with OK.") }) }
        put("model", model)
        putJsonObject("output_config") {
            if (withEffort(model)) put("effort", "low")
            putJsonObject("format") { put("schema", JsonSchemas.ping); put("type", "json_schema") }
        }
    })

    /** A whole (not streamed) Messages answer. */
    fun message(o: JsonObject): ClaudeMessage {
        val usage = o["usage"] as? JsonObject
        val texts = (o["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }.map { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return ClaudeMessage(
            (o["stop_reason"] as? JsonPrimitive)?.contentOrNull, texts,
            (usage?.get("input_tokens") as? JsonPrimitive)?.longOrNull, (usage?.get("output_tokens") as? JsonPrimitive)?.longOrNull,
        )
    }

    /**
     * The answer's text; a refusal or a cut-off answer is an error, checked before reading the content. A stream that
     * ended before its stop reason came is cut off too.
     */
    fun reply(message: ClaudeMessage, requireText: Boolean = true): OwnKeyReply {
        when (message.stopReason) {
            "refusal" -> throw OwnKeyException(OwnKeyError.Blocked)
            "max_tokens", "model_context_window_exceeded", null -> if (requireText) throw OwnKeyException(OwnKeyError.Incomplete)
        }
        return OwnKeyReply(message.texts.joinToString(""), message.inputTokens, message.outputTokens)
    }

    /** A refused request by its status, as the SDK sorts them into its exceptions, then by Anthropic's error type. */
    fun errorOf(status: Int, body: String): OwnKeyError {
        val type = runCatching { ((ClaudeKeyApi.json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull }.getOrNull()
        return when {
            status == 401 -> OwnKeyError.WrongKey
            status == 404 -> OwnKeyError.ModelUnavailable
            status == 429 -> OwnKeyError.TooMany
            status >= 500 -> OwnKeyError.Busy
            status == 403 -> OwnKeyError.ofHttp(403, "permission_error", body)
            status == 400 -> OwnKeyError.ofHttp(400, "invalid_request_error", body)
            else -> errorOfType(type, status, body)
        }
    }

    /** Anthropic's error type (in a refusal's body or a stream's error event) in plain words. */
    fun errorOfType(type: String?, status: Int, body: String): OwnKeyError = when (type) {
        "billing_error" -> OwnKeyError.NoCredit
        "authentication_error" -> OwnKeyError.WrongKey
        "rate_limit_error" -> OwnKeyError.TooMany
        "overloaded_error", "api_error" -> OwnKeyError.Busy
        "timeout_error" -> OwnKeyError.Timeout
        else -> OwnKeyError.ofHttp(status, type.orEmpty(), body)
    }

    /** A failure with no answer: the network's ([OwnKeyError.ofNetwork]), anything else unknown. */
    fun errorOf(error: Throwable): OwnKeyError = when (error) {
        is com.justdataplease.boringtravel.io.IOException -> OwnKeyError.ofNetwork(error)
        else -> OwnKeyError.Unknown
    }
}

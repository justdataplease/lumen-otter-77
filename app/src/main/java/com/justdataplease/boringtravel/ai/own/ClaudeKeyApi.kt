package com.justdataplease.boringtravel.ai.own

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.core.http.AsyncStreamResponse
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.ErrorType
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.anthropic.models.models.ModelListParams
import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftSchema
import com.justdataplease.boringtravel.ai.SchemaNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletionException

/**
 * Claude with an Anthropic API key, through Anthropic's official Java SDK (`com.anthropic:anthropic-java`): the models
 * list is the key test, the draft is a streamed Messages request with structured output ([ClaudeRequests]). [clients]
 * makes the SDK client for a key (tests pass their own, pointed at a local server).
 *
 * Time limits (the SDK's retries count): one retry ([MAX_RETRIES]), for a refusal or dropped connection before any
 * answer; the list and Test's request get [LIST_TIMEOUT] and [PING_TIMEOUT]. The draft streams, so a long answer is
 * never cut by a read timeout, its one attempt stays inside the planner's 190 s cap ([DRAFT_TIMEOUT]), and a cancelled
 * or capped plan closes the stream: Anthropic stops writing (and billing) it. A non-streamed request can't be stopped
 * that way: cancelling the coroutine leaves the SDK's call running to its timeout, retries included.
 */
class ClaudeKeyApi(private val clients: (String) -> AnthropicClient = { defaultClient(it) }) : OwnKeyApi {
    override val provider = OwnAiProvider.Claude

    private var cachedKey: String? = null
    private var cached: AnthropicClient? = null

    /** One SDK client for the key in use (it holds its own connection pool and Jackson setup). */
    @Synchronized
    private fun client(key: String): AnthropicClient {
        cached?.let { if (cachedKey == key) return it }
        cached?.let { runCatching { it.close() } }
        return clients(key).also { cached = it; cachedKey = key }
    }

    /** Drops the SDK client and the key it holds (Remove key, Delete account). */
    @Synchronized
    fun forget() {
        cached?.let { runCatching { it.close() } }
        cached = null
        cachedKey = null
    }

    /** Made off the main thread: the first client sets up Jackson and Kotlin reflection. */
    private suspend fun clientFor(key: String): AnthropicClient = withContext(Dispatchers.IO) { client(key) }

    override suspend fun models(key: String): List<String> = call {
        val ids = mutableListOf<String>()
        var page = clientFor(key).async().models().list(ModelListParams.builder().limit(PAGE).build(), options(LIST_TIMEOUT)).await()
        repeat(MAX_PAGES) {
            ids += page.data().map { it.id() }
            if (!page.hasNextPage()) return@call ids
            page = page.nextPage().await()
        }
        ids
    }

    private val latest = LatestModels(provider) { models(it) }

    override suspend fun resolve(key: String, model: String): String = latest.resolve(key, model)

    override suspend fun ping(key: String, model: String) {
        val resolved = resolve(key, model)
        call {
            val message = clientFor(key).async().beta().messages().create(ClaudeRequests.ping(resolved), options(PING_TIMEOUT)).await()
            ClaudeRequests.reply(message, requireText = false)
        }
    }

    override suspend fun draft(key: String, model: String, system: String, turns: List<ChatTurn>): OwnKeyReply = call {
        ClaudeRequests.reply(streamed(clientFor(key), ClaudeRequests.draft(model, system, turns)))
    }

    /** Like a draft: streamed, with the schema ([JsonSchemas.Dialect.Claude]) in the system prompt. */
    override suspend fun answer(key: String, model: String, system: String, turns: List<ChatTurn>, schema: SchemaNode.Obj): OwnKeyReply = call {
        ClaudeRequests.reply(streamed(clientFor(key), ClaudeRequests.draft(model, system, turns, JsonSchemas.of(schema, JsonSchemas.Dialect.Claude))))
    }

    /** The answer gathered from the stream ([BetaMessageAccumulator]); the stream is closed however this ends. */
    private suspend fun streamed(client: AnthropicClient, params: MessageCreateParams): BetaMessage {
        val stream = client.async().beta().messages().createStreaming(params, options(DRAFT_TIMEOUT, read = DRAFT_READ_TIMEOUT))
        val accumulator = BetaMessageAccumulator.create()
        try {
            stream.subscribe(object : AsyncStreamResponse.Handler<BetaRawMessageStreamEvent> {
                override fun onNext(value: BetaRawMessageStreamEvent) {
                    accumulator.accumulate(value)
                }
            })
            stream.onCompleteFuture().await()
        } finally {
            stream.close()
        }
        return try {
            accumulator.message()
        } catch (_: IllegalStateException) {
            // The stream ended before `message_stop`: a cut-off answer.
            throw OwnKeyException(OwnKeyError.Incomplete)
        }
    }

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: OwnKeyException) {
        throw e
    } catch (e: Exception) {
        val error = ClaudeRequests.errorOf(e)
        throw OwnKeyException(error, (unwrap(e) as? AnthropicServiceException)?.statusCode(), e)
    }

    companion object {
        private const val PAGE = 100L
        private const val MAX_PAGES = 5

        /** One retry: a 429, 5xx or dropped connection before any answer (the SDK's default is two). */
        const val MAX_RETRIES = 1

        /** The list and Test's request: two tries of each (2 × 8 + 2 × 13 s) fit Me › AI's 45 s Test. */
        val LIST_TIMEOUT: Duration = Duration.ofSeconds(8)
        val PING_TIMEOUT: Duration = Duration.ofSeconds(13)

        /** A draft's whole stream: inside the planner's 190 s cap, which closes the stream first if a retry ran. */
        val DRAFT_TIMEOUT: Duration = Duration.ofSeconds(180)

        /** Silence on an open stream (Anthropic sends pings while the model thinks). */
        val DRAFT_READ_TIMEOUT: Duration = Duration.ofSeconds(90)

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)

        /** [request] for the whole call (a stream included), [read] for silence on the line. */
        internal fun options(request: Duration, read: Duration = request): RequestOptions = RequestOptions.builder()
            .timeout(Timeout.builder().connect(CONNECT_TIMEOUT).write(request).read(read).request(request).build())
            .build()

        /** [baseUrl] only in tests (a local server). */
        fun defaultClient(key: String, baseUrl: String? = null): AnthropicClient = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .apply { if (baseUrl != null) baseUrl(baseUrl) }
            .maxRetries(MAX_RETRIES)
            .timeout(Timeout.builder().connect(CONNECT_TIMEOUT).write(PING_TIMEOUT).read(PING_TIMEOUT).request(PING_TIMEOUT).build())
            .build()

        internal fun unwrap(e: Throwable): Throwable = if (e is CompletionException && e.cause != null) e.cause!! else e
    }
}

/**
 * The Messages requests for the planner, built with the SDK's types (claude-api skill, 2026-11): the system prompt,
 * the chat as user/assistant turns, `max_tokens` 16,000 (streamed by [ClaudeKeyApi]), structured output with
 * [JsonSchemas.Dialect.Claude], explicit `medium` effort (set as the skill asks; adaptive thinking is on by default on
 * all three current models), and Anthropic's server-side refusal fallback on Opus and Sonnet 5.5.
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

    private fun format(schema: JsonObject): BetaJsonOutputFormat = BetaJsonOutputFormat.builder()
        .schema(BetaJsonOutputFormat.Schema.builder().putAllAdditionalProperties(schema.mapValues { JsonValue.from(plain(it.value)) }).build())
        .build()

    /**
     * Every planner tool in one answer. The schema goes in the system prompt, not in `output_config.format`: strict
     * schemas allow at most 24 optional fields per request and the planner's tools have more.
     */
    fun draft(model: String, system: String, turns: List<ChatTurn>, answerSchema: JsonObject = schema): MessageCreateParams {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(system + "\n\nAnswer with one JSON object and nothing else, following this JSON Schema:\n" + answerSchema)
        if (withEffort(model)) params.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
        turns.forEach { t ->
            when {
                !t.fromTraveller -> params.addAssistantMessage(t.text)
                t.pictures.isEmpty() -> params.addUserMessage(t.text)
                // Pictures first, then the words (Anthropic's advice).
                else -> params.addUserMessageOfContentBlockParams(
                    t.pictures.map { p ->
                        BetaContentBlockParam.ofImage(BetaBase64ImageSource.builder().data(p.base64).mediaType(BetaBase64ImageSource.MediaType.of(p.mime)).build())
                    } + BetaContentBlockParam.ofText(t.text),
                )
            }
        }
        if (withFallback(model)) params.addBeta(FALLBACK_BETA).fallbacksDefault()
        return params.build()
    }

    /** The Test button's tiny request, with structured output like a draft ([JsonSchemas.ping]) and `low` effort. */
    fun ping(model: String): MessageCreateParams {
        val config = BetaOutputConfig.builder().format(format(JsonSchemas.ping))
        if (withEffort(model)) config.effort(BetaOutputConfig.Effort.LOW)
        return MessageCreateParams.builder().model(model).maxTokens(PING_TOKENS).addUserMessage("Reply with OK.").outputConfig(config.build()).build()
    }

    /**
     * The answer's text; a refusal or a cut-off answer is an error, checked before reading the content. A stream that
     * ended before its stop reason came is cut off too.
     */
    fun reply(message: BetaMessage, requireText: Boolean = true): OwnKeyReply {
        when (message.stopReason().orElse(null)) {
            BetaStopReason.REFUSAL -> throw OwnKeyException(OwnKeyError.Blocked)
            BetaStopReason.MAX_TOKENS, BetaStopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, null -> if (requireText) throw OwnKeyException(OwnKeyError.Incomplete)
            else -> Unit
        }
        val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        return OwnKeyReply(text, message.usage().inputTokens(), message.usage().outputTokens())
    }

    /** The SDK's typed exceptions, most specific first; the rest by status and Anthropic's error type. */
    fun errorOf(error: Throwable): OwnKeyError = when (val e = ClaudeKeyApi.unwrap(error)) {
        is UnauthorizedException -> OwnKeyError.WrongKey
        is NotFoundException -> OwnKeyError.ModelUnavailable
        is RateLimitException -> OwnKeyError.TooMany
        is InternalServerException -> OwnKeyError.Busy
        is PermissionDeniedException -> OwnKeyError.ofHttp(403, "permission_error", e.body().toString())
        is BadRequestException -> OwnKeyError.ofHttp(400, "invalid_request_error", e.body().toString())
        is AnthropicServiceException -> when (e.errorType().orElse(null)) {
            ErrorType.BILLING_ERROR -> OwnKeyError.NoCredit
            ErrorType.AUTHENTICATION_ERROR -> OwnKeyError.WrongKey
            ErrorType.RATE_LIMIT_ERROR -> OwnKeyError.TooMany
            ErrorType.OVERLOADED_ERROR, ErrorType.API_ERROR -> OwnKeyError.Busy
            ErrorType.TIMEOUT_ERROR -> OwnKeyError.Timeout
            else -> OwnKeyError.ofHttp(e.statusCode(), e.errorType().map { it.asString() }.orElse(""), e.body().toString())
        }
        is AnthropicIoException -> OwnKeyError.ofNetwork(e.cause ?: e)
        is IOException -> OwnKeyError.ofNetwork(e)
        else -> OwnKeyError.Unknown
    }

    /** kotlinx JSON as the plain maps, lists and values the SDK's [JsonValue.from] takes. */
    internal fun plain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { plain(it.value) }
        is JsonArray -> e.map(::plain)
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
    }
}

package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.AiPicture
import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftSchema
import com.justdataplease.boringtravel.ai.GeminiPlanner
import com.justdataplease.boringtravel.ai.PlanRequest
import com.justdataplease.boringtravel.ai.PlanResult
import com.justdataplease.boringtravel.ai.PlannerEngine
import com.justdataplease.boringtravel.ai.PlannerRuns
import com.justdataplease.boringtravel.ai.SchemaNode
import com.justdataplease.boringtravel.ai.tools.ToolChat
import com.justdataplease.boringtravel.ai.PlannerPrompt
import com.justdataplease.boringtravel.google.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** One answer with an own key; token counts when the provider sent them (for logs and tests, never the text). */
data class OwnKeyReply(val text: String, val inputTokens: Long? = null, val outputTokens: Long? = null)

/** The providers' replies are read leniently: unknown fields are skipped (one instance for the three REST helpers). */
internal val OwnKeyJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** One provider's three calls with the traveller's key. Every failure is an [OwnKeyException]. */
interface OwnKeyApi {
    val provider: OwnAiProvider

    /** Every model the key may use, as the provider lists them (this is also the key test). */
    suspend fun models(key: String): List<String>

    /**
     * A tiny request to [model] (the Test button) with the same structured-output setting as a draft
     * ([JsonSchemas.ping]): returns when the model answered, so it can also plan.
     */
    suspend fun ping(key: String, model: String)

    /** Every planner tool in one answer, as JSON text following [DraftSchema] (providers without the tool loop). */
    suspend fun draft(key: String, model: String, system: String, turns: List<ChatTurn>): OwnKeyReply

    /**
     * One structured answer following [schema], without the planner's tools (Copy from a site asks which posts make
     * which trip). Each provider sends it like a draft, with its own schema dialect ([JsonSchemas]).
     */
    suspend fun answer(key: String, model: String, system: String, turns: List<ChatTurn>, schema: SchemaNode.Obj): OwnKeyReply =
        throw OwnKeyException(OwnKeyError.Unknown)

    /** The tool loop with this key, or null when this provider answers in one reply ([draft]). */
    fun toolChat(key: String, model: String): ToolChat? = null

    /** The model to call for [model]: a family's "-latest" name becomes its newest model where the provider has none. */
    suspend fun resolve(key: String, model: String): String = model
}

/**
 * [OwnKeyApi.models], or null when this key may not list them but may still plan: an OpenAI restricted key without
 * "Models: Read" gets 401/403 from `/v1/models`. Test then asks the chosen or typed model directly (a wrong key fails
 * there too, free).
 */
suspend fun OwnKeyApi.modelsOrNull(key: String): List<String>? = try {
    models(key)
} catch (e: OwnKeyException) {
    if (provider == OwnAiProvider.ChatGpt && (e.status == 401 || e.status == 403)) null else throw e
}

/**
 * The planner with the traveller's own key: the same tools and prompt as the built-in planner, sent straight from the
 * phone to the provider. Gemini and OpenRouter run the tool loop ([OwnKeyApi.toolChat]); Claude and ChatGPT answer
 * with every tool in one structured reply. A "-latest" family name is turned into the newest model first
 * ([OwnKeyApi.resolve]). No App Check and no daily allowance: it is the traveller's own bill.
 */
class OwnKeyPlanner(private val api: OwnKeyApi, private val key: String, override val modelName: String) : PlannerEngine {
    val provider: OwnAiProvider get() = api.provider
    override val isAvailable: Boolean get() = true

    /** The model that answered last ("claude-sonnet-5-5" for "claude-sonnet-latest"). */
    @Volatile private var answeredBy: String = modelName

    /** "claude-sonnet-5-5 (own Claude key)". */
    override val reportName: String get() = "$answeredBy (own ${provider.label} key)"

    override suspend fun plan(request: PlanRequest, onStep: (String) -> Unit): PlanResult {
        val model = api.resolve(key, modelName)
        answeredBy = model
        val req = request.copy(message = request.message.take(GeminiPlanner.MAX_NOTES))
        val chat = api.toolChat(key, model)
        if (chat != null) return PlannerRuns.loop(chat, req, onStep)
        onStep("Planning")
        val system = PlannerPrompt.system(req.today, tools = false)
        return PlannerRuns.oneShot(req, onStep) { turns -> api.draft(key, model, system, turns).text }
    }

    /**
     * One JSON answer following [schema] with this key and model ([OwnKeyApi.answer]), about [pictures] when there are
     * any ([com.justdataplease.boringtravel.ai.PictureReading]); the text as it came.
     */
    suspend fun answer(system: String, prompt: String, schema: SchemaNode.Obj, pictures: List<AiPicture> = emptyList()): String {
        val model = api.resolve(key, modelName)
        answeredBy = model
        return api.answer(key, model, system, listOf(ChatTurn(true, prompt, pictures)), schema).text
    }

    override fun toString(): String = "OwnKeyPlanner(${provider.id}, $modelName)"
}

/** Gemini with a Google AI Studio key ([GeminiRest]). */
class GeminiKeyApi(private val http: OkHttpClient) : OwnKeyApi {
    override val provider = OwnAiProvider.Gemini
    private val schema: JsonObject by lazy { JsonSchemas.of(DraftSchema.generation, JsonSchemas.Dialect.Gemini) }

    override suspend fun models(key: String): List<String> {
        val ids = mutableListOf<String>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val page = parse { GeminiRest.parseModels(http.send(get(GeminiRest.modelsUrl(token), key), GeminiRest::error)) }
            ids += page.ids
            token = page.next ?: return ids
        }
        return ids
    }

    override suspend fun ping(key: String, model: String) {
        GeminiRest.parseAnswer(http.send(post(GeminiRest.generateUrl(model), key, GeminiRest.pingBody()), GeminiRest::error))
    }

    override suspend fun draft(key: String, model: String, system: String, turns: List<ChatTurn>): OwnKeyReply {
        val body = http.send(post(GeminiRest.generateUrl(model), key, GeminiRest.draftBody(system, turns, schema)), GeminiRest::error)
        return GeminiRest.parseAnswer(body)
    }

    override suspend fun answer(key: String, model: String, system: String, turns: List<ChatTurn>, schema: SchemaNode.Obj): OwnKeyReply {
        val body = GeminiRest.draftBody(system, turns, JsonSchemas.of(schema, JsonSchemas.Dialect.Gemini))
        return GeminiRest.parseAnswer(http.send(post(GeminiRest.generateUrl(model), key, body), GeminiRest::error))
    }

    override fun toolChat(key: String, model: String): ToolChat = GeminiToolChat(http, key, model)

    private fun get(url: String, key: String) = Request.Builder().url(url).header(GeminiRest.KEY_HEADER, key).get().build()
    private fun post(url: String, key: String, body: JsonObject) =
        Request.Builder().url(url).header(GeminiRest.KEY_HEADER, key).post(body.toString().toRequestBody(JSON)).build()

    private companion object { const val MAX_PAGES = 5 }
}

/**
 * Family names ("claude-sonnet-latest", "gpt-sol-latest") turned into the newest model the key lists
 * ([OwnAiModels.resolve]); the list is remembered for a few hours per key. A list that can't be read leaves the name
 * as it is (the provider then says whether it knows it).
 */
internal class LatestModels(private val provider: OwnAiProvider, private val list: suspend (String) -> List<String>) {
    private var listed: List<String>? = null
    private var listedFor = 0
    private var listedAt = 0L

    suspend fun resolve(key: String, model: String): String {
        if (!model.endsWith("-latest") || model.startsWith("~")) return model
        val now = System.currentTimeMillis()
        val ids = synchronized(this) { listed?.takeIf { listedFor == key.hashCode() && now - listedAt < KEEP_MS } }
            ?: try {
                list(key).also { synchronized(this) { listed = it; listedFor = key.hashCode(); listedAt = now } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return model
            }
        return OwnAiModels.resolve(provider, model, ids)
    }

    private companion object { const val KEEP_MS = 6 * 60 * 60 * 1000L }
}

/** ChatGPT's models with an OpenAI API key ([OpenAiRest]). */
class OpenAiKeyApi(private val http: OkHttpClient) : OwnKeyApi {
    override val provider = OwnAiProvider.ChatGpt
    private val schema: JsonObject by lazy { JsonSchemas.of(DraftSchema.generation, JsonSchemas.Dialect.OpenAi) }

    private val latest = LatestModels(provider) { models(it) }

    override suspend fun models(key: String): List<String> =
        parse { OpenAiRest.parseModels(http.send(bearer(OpenAiRest.MODELS_URL, key, null), OpenAiRest::error)) }

    override suspend fun resolve(key: String, model: String): String = latest.resolve(key, model)

    override suspend fun ping(key: String, model: String) {
        OpenAiRest.parseAnswer(http.send(bearer(OpenAiRest.RESPONSES_URL, key, OpenAiRest.pingBody(resolve(key, model))), OpenAiRest::error))
    }

    override suspend fun draft(key: String, model: String, system: String, turns: List<ChatTurn>): OwnKeyReply {
        val body = http.send(bearer(OpenAiRest.RESPONSES_URL, key, OpenAiRest.draftBody(model, system, turns, schema)), OpenAiRest::error)
        return OpenAiRest.parseAnswer(body)
    }

    override suspend fun answer(key: String, model: String, system: String, turns: List<ChatTurn>, schema: SchemaNode.Obj): OwnKeyReply {
        val body = OpenAiRest.draftBody(model, system, turns, JsonSchemas.of(schema, JsonSchemas.Dialect.OpenAi), OpenAiRest.ANSWER_SCHEMA_NAME)
        return OpenAiRest.parseAnswer(http.send(bearer(OpenAiRest.RESPONSES_URL, key, body), OpenAiRest::error))
    }
}

/**
 * Any model on OpenRouter with an OpenRouter key ([OpenRouterRest]): the key check, then the public model list with
 * only the models that take structured outputs; the schema follows the model maker's rules.
 */
class OpenRouterKeyApi(private val http: OkHttpClient) : OwnKeyApi {
    override val provider = OwnAiProvider.OpenRouter
    private val schemas = mutableMapOf<JsonSchemas.Dialect, JsonObject>()

    override suspend fun models(key: String): List<String> {
        val info = http.send(bearer(OpenRouterRest.KEY_URL, key, null, OpenRouterRest.APP_HEADERS), OpenRouterRest::error)
        OpenRouterRest.keyLimitRemaining(info)?.let { if (it <= 0.0) throw OwnKeyException(OwnKeyError.NoCredit) }
        val list = http.send(bearer(OpenRouterRest.MODELS_URL, key, null, OpenRouterRest.APP_HEADERS), OpenRouterRest::error)
        // Every model OpenRouter has, a megabyte or two of JSON: read off the main thread.
        return withContext(Dispatchers.Default) { parse { OpenRouterRest.parseModels(list) } }
    }

    override fun toolChat(key: String, model: String): ToolChat = OpenRouterToolChat(http, key, model)

    override suspend fun ping(key: String, model: String) {
        OpenRouterRest.parseAnswer(http.send(bearer(OpenRouterRest.CHAT_URL, key, OpenRouterRest.pingBody(model), OpenRouterRest.APP_HEADERS), OpenRouterRest::error))
    }

    override suspend fun draft(key: String, model: String, system: String, turns: List<ChatTurn>): OwnKeyReply {
        val dialect = OpenRouterRest.dialect(model)
        val schema = synchronized(schemas) { schemas.getOrPut(dialect) { JsonSchemas.of(DraftSchema.generation, dialect) } }
        val body = http.send(bearer(OpenRouterRest.CHAT_URL, key, OpenRouterRest.draftBody(model, system, turns, schema), OpenRouterRest.APP_HEADERS), OpenRouterRest::error)
        return OpenRouterRest.parseAnswer(body)
    }

    override suspend fun answer(key: String, model: String, system: String, turns: List<ChatTurn>, schema: SchemaNode.Obj): OwnKeyReply {
        val body = OpenRouterRest.draftBody(model, system, turns, JsonSchemas.of(schema, OpenRouterRest.dialect(model)), OpenAiRest.ANSWER_SCHEMA_NAME)
        return OpenRouterRest.parseAnswer(http.send(bearer(OpenRouterRest.CHAT_URL, key, body, OpenRouterRest.APP_HEADERS), OpenRouterRest::error))
    }
}

private val JSON = "application/json; charset=utf-8".toMediaType()

/** A GET (no [body]) or JSON POST with `Authorization: Bearer` (OpenAI and OpenRouter). */
internal fun bearer(url: String, key: String, body: JsonObject?, headers: Map<String, String> = emptyMap()): Request =
    Request.Builder().url(url)
        .header("Authorization", "Bearer $key")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .apply { if (body == null) get() else post(body.toString().toRequestBody(JSON)) }
        .build()

/** Sends [request]; a refusal becomes [onError]'s [OwnKeyError], a network failure [OwnKeyError.ofNetwork]. */
internal suspend fun OkHttpClient.send(request: Request, onError: (Int, String) -> OwnKeyError): String {
    val response = try {
        newCall(request).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw OwnKeyException(OwnKeyError.ofNetwork(e), cause = e)
    }
    return withContext(Dispatchers.IO) {
        response.use { r ->
            val body = try { r.body?.string().orEmpty() } catch (e: IOException) { throw OwnKeyException(OwnKeyError.ofNetwork(e), r.code, e) }
            if (!r.isSuccessful) throw OwnKeyException(onError(r.code, body), r.code)
            body
        }
    }
}

/** A list the provider sent that couldn't be read. */
private inline fun <T> parse(block: () -> T): T = try {
    block()
} catch (e: OwnKeyException) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw OwnKeyException(OwnKeyError.Unknown, cause = e)
}

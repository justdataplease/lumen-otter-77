package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.ChatTurn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenRouter with its own key, over plain HTTPS (openrouter.ai/docs, 2026-11-11): `Authorization: Bearer <key>`,
 * `GET /api/v1/key` checks the key (the model list is public), `GET /api/v1/models` lists every model with its
 * supported parameters, `POST /api/v1/chat/completions` answers with a `json_schema` `response_format` and
 * `provider.require_parameters`, so only providers that can follow the schema get the request. Chat Completions, not
 * OpenRouter's Responses API, which its docs still call a beta that may break. The app names itself with
 * `HTTP-Referer` and `X-OpenRouter-Title`. Bodies and parsing only; [OpenRouterKeyApi] sends them.
 */
object OpenRouterRest {
    const val BASE = "https://openrouter.ai/api/v1"
    const val KEY_URL = "$BASE/key"
    const val MODELS_URL = "$BASE/models"
    const val CHAT_URL = "$BASE/chat/completions"

    /** App attribution headers (optional, OpenRouter's docs). */
    val APP_HEADERS = mapOf("HTTP-Referer" to "https://justdataplease.com", "X-OpenRouter-Title" to "BoringTravel")

    private val json = OwnKeyJson

    /**
     * The schema written the way the model's own maker takes it: Claude's rules for `anthropic/…`, Gemini's for
     * `google/…`, OpenAI's strict rules (every field required, nullable ones as `[type, "null"]`) for the rest.
     */
    fun dialect(model: String): JsonSchemas.Dialect = when (model.substringBefore('/').lowercase()) {
        "anthropic" -> JsonSchemas.Dialect.Claude
        "google" -> JsonSchemas.Dialect.Gemini
        else -> JsonSchemas.Dialect.OpenAi
    }

    fun draftBody(model: String, system: String, turns: List<ChatTurn>, schema: JsonObject, name: String = OpenAiRest.SCHEMA_NAME): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            turns.forEach { t ->
                add(buildJsonObject {
                    put("role", if (t.fromTraveller) "user" else "assistant")
                    if (t.pictures.isEmpty()) put("content", t.text)
                    else putJsonArray("content") {
                        // OpenRouter's advice: the words first, then the pictures.
                        add(buildJsonObject { put("type", "text"); put("text", t.text) })
                        t.pictures.forEach { p -> add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", p.dataUrl) } }) }
                    }
                })
            }
        }
        putJsonObject("response_format") {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", name)
                put("strict", true)
                put("schema", schema)
            }
        }
        putJsonObject("provider") { put("require_parameters", true) }
    }

    /**
     * The Test button's tiny request, with a `json_schema` `response_format` and `require_parameters` like a draft
     * ([JsonSchemas.ping]), so it only works where a draft would.
     */
    fun pingBody(model: String): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", "Reply with OK.") }) }
        putJsonObject("response_format") {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", OpenAiRest.PING_SCHEMA_NAME)
                put("strict", true)
                put("schema", JsonSchemas.ping)
            }
        }
        putJsonObject("provider") { put("require_parameters", true) }
    }

    /** The key's remaining credit limit; null when it has none. A key at its limit can't plan. */
    fun keyLimitRemaining(body: String): Double? {
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"] as? JsonObject }.getOrNull()
        return (data?.get("limit_remaining") as? JsonPrimitive)?.doubleOrNull
    }

    /**
     * Models that answer in text and take both tool calls (the planner's loop) and structured outputs (Copy from a
     * link), newest first; no `:batch` variants. A model without tools passed Test but failed every plan.
     */
    fun parseModels(body: String): List<String> =
        (json.parseToJsonElement(body).jsonObject["data"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { m ->
                val params = (m["supported_parameters"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val out = ((m["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                "structured_outputs" in params && "tools" in params && out == listOf("text")
            }
            .sortedByDescending { (it["created"] as? JsonPrimitive)?.longOrNull ?: 0L }
            .mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }
            .filter { !it.endsWith(":batch") }

    /** The answer's text, or why there is none: an error even with HTTP 200, a refusal, a filter or a cut. */
    fun parseAnswer(body: String): OwnKeyReply {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw OwnKeyException(OwnKeyError.Incomplete)
        (root["error"] as? JsonObject)?.let { throw OwnKeyException(errorOf(it, null)) }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw OwnKeyException(OwnKeyError.Incomplete)
        (choice["error"] as? JsonObject)?.let { throw OwnKeyException(errorOf(it, null)) }
        val message = choice["message"] as? JsonObject
        if (!(message?.get("refusal") as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) throw OwnKeyException(OwnKeyError.Blocked)
        when ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull) {
            "content_filter" -> throw OwnKeyException(OwnKeyError.Blocked)
            "length" -> throw OwnKeyException(OwnKeyError.Incomplete)
            "error" -> throw OwnKeyException(OwnKeyError.Busy)
        }
        val usage = root["usage"] as? JsonObject
        return OwnKeyReply(
            (message?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty(),
            inputTokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.longOrNull,
            outputTokens = (usage?.get("completion_tokens") as? JsonPrimitive)?.longOrNull,
        )
    }

    /** A refused call: OpenRouter's `error.code` matches the HTTP status (errors docs). */
    fun error(status: Int, body: String): OwnKeyError {
        val err = runCatching { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject }.getOrNull()
        return if (err == null) OwnKeyError.ofHttp(status) else errorOf(err, status)
    }

    private fun errorOf(err: JsonObject, status: Int?): OwnKeyError {
        val code = status ?: (err["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val message = (err["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val flagged = ((err["metadata"] as? JsonObject)?.get("reasons") as? JsonArray)?.isNotEmpty() == true
        return when {
            // 403: a moderation flag or a guardrail, not a key problem.
            code == 403 -> if (flagged || "moderation" in message.lowercase() || "flagged" in message.lowercase()) OwnKeyError.Blocked else OwnKeyError.WrongKey
            // 503 / 404: no provider of this model can take the request (here: the JSON schema).
            code == 503 && ("no endpoints" in message.lowercase() || "routing" in message.lowercase() || "provider" in message.lowercase()) -> OwnKeyError.ModelUnavailable
            code == 404 -> OwnKeyError.ModelUnavailable
            code == 502 -> OwnKeyError.Busy
            else -> OwnKeyError.ofHttp(code, "", message)
        }
    }
}

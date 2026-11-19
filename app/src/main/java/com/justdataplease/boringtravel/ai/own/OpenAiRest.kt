package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.ChatTurn
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

/**
 * The OpenAI API with a developer key, over plain HTTPS (developers.openai.com, 2026-11-11: "Responses is recommended
 * for all new projects"): `Authorization: Bearer <key>`, `GET /v1/models` lists the models, `POST /v1/responses` answers
 * with `instructions`, the chat as `input` messages and a strict `text.format` JSON schema. `store` is off, so OpenAI
 * keeps no copy for later turns (each request carries the chat itself). Bodies and parsing only; [OpenAiKeyApi] sends
 * them.
 */
object OpenAiRest {
    const val BASE = "https://api.openai.com/v1"
    const val MODELS_URL = "$BASE/models"
    const val RESPONSES_URL = "$BASE/responses"
    const val SCHEMA_NAME = "trip_draft"
    const val PING_SCHEMA_NAME = "ping"

    /** The name of a one-off structured answer's schema ([OwnKeyApi.answer]). */
    const val ANSWER_SCHEMA_NAME = "answer"

    private val json = OwnKeyJson

    fun draftBody(model: String, system: String, turns: List<ChatTurn>, schema: JsonObject, name: String = SCHEMA_NAME): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", system)
        putJsonArray("input") {
            turns.forEach { t ->
                add(buildJsonObject {
                    put("role", if (t.fromTraveller) "user" else "assistant")
                    if (t.pictures.isEmpty()) put("content", t.text)
                    else putJsonArray("content") {
                        add(buildJsonObject { put("type", "input_text"); put("text", t.text) })
                        t.pictures.forEach { p -> add(buildJsonObject { put("type", "input_image"); put("image_url", p.dataUrl); put("detail", "high") }) }
                    }
                })
            }
        }
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", name)
                put("strict", true)
                put("schema", schema)
            }
        }
        put("store", false)
    }

    /** The Test button's tiny request, with a strict `json_schema` format like a draft's ([JsonSchemas.ping]). */
    fun pingBody(model: String): JsonObject = buildJsonObject {
        put("model", model)
        put("input", "Reply with OK.")
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", PING_SCHEMA_NAME)
                put("strict", true)
                put("schema", JsonSchemas.ping)
            }
        }
        put("store", false)
    }

    fun parseModels(body: String): List<String> =
        (json.parseToJsonElement(body).jsonObject["data"] as? JsonArray).orEmpty().mapNotNull { m ->
            ((m as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
        }

    /** The answer's text, or why there is none (a refusal, or cut short). */
    fun parseAnswer(body: String): OwnKeyReply {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw OwnKeyException(OwnKeyError.Incomplete)
        val parts = (root["output"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "message" }
            .flatMap { (it["content"] as? JsonArray).orEmpty().mapNotNull { c -> c as? JsonObject } }
        if (parts.any { (it["type"] as? JsonPrimitive)?.contentOrNull == "refusal" }) throw OwnKeyException(OwnKeyError.Blocked)
        val status = (root["status"] as? JsonPrimitive)?.contentOrNull
        if (status == "incomplete") {
            val reason = ((root["incomplete_details"] as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
            throw OwnKeyException(if (reason == "content_filter") OwnKeyError.Blocked else OwnKeyError.Incomplete)
        }
        if (status == "failed") throw OwnKeyException(OwnKeyError.Busy)
        val text = parts.filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "output_text" }
            .joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val usage = root["usage"] as? JsonObject
        return OwnKeyReply(
            text,
            inputTokens = (usage?.get("input_tokens") as? JsonPrimitive)?.longOrNull,
            outputTokens = (usage?.get("output_tokens") as? JsonPrimitive)?.longOrNull,
        )
    }

    /** A refused call: OpenAI's `error.code`, `error.type` and message. */
    fun error(status: Int, body: String): OwnKeyError {
        val err = runCatching { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject }.getOrNull()
        fun field(name: String) = (err?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
        return OwnKeyError.ofHttp(status, field("code") + " " + field("type"), field("message"))
    }
}

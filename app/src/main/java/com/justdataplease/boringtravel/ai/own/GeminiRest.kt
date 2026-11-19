package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.ChatTurn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Gemini API with a Google AI Studio key, over plain HTTPS (ai.google.dev docs, 2026-11-11): the key goes in the
 * `x-goog-api-key` header, `GET /v1beta/models` lists the models, `POST /v1beta/models/{model}:generateContent` answers
 * with a `systemInstruction` and `generationConfig.responseMimeType` + `responseJsonSchema` (JSON Schema).
 * Bodies and parsing only; [GeminiKeyApi] sends them.
 */
object GeminiRest {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val KEY_HEADER = "x-goog-api-key"

    /** The built-in planner's setting, kept for the same kind of drafts. */
    private const val TEMPERATURE = 0.3

    private val json = OwnKeyJson

    fun modelsUrl(pageToken: String? = null): String =
        "$BASE/models?pageSize=1000" + (pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")

    /** [model] as listed, without the `models/` prefix. */
    fun generateUrl(model: String): String = "$BASE/models/${java.net.URLEncoder.encode(model.removePrefix("models/"), "UTF-8")}:generateContent"

    fun draftBody(system: String, turns: List<ChatTurn>, schema: JsonObject): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
        putJsonArray("contents") {
            turns.forEach { t ->
                add(buildJsonObject {
                    put("role", if (t.fromTraveller) "user" else "model")
                    putJsonArray("parts") {
                        // Pictures first, then the words about them (Gemini's advice for one picture or several).
                        t.pictures.forEach { p -> add(buildJsonObject { putJsonObject("inlineData") { put("mimeType", p.mime); put("data", p.base64) } }) }
                        add(buildJsonObject { put("text", t.text) })
                    }
                })
            }
        }
        putJsonObject("generationConfig") {
            put("temperature", TEMPERATURE)
            put("responseMimeType", "application/json")
            put("responseJsonSchema", schema)
        }
    }

    /** The Test button's tiny request, asking for JSON with a schema like a draft ([JsonSchemas.ping]). */
    fun pingBody(): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { add(buildJsonObject { put("text", "Reply with OK.") }) }
            })
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", JsonSchemas.ping)
        }
    }

    data class ModelPage(val ids: List<String>, val next: String?)

    /** Models that can generate content, ids without the `models/` prefix. */
    fun parseModels(body: String): ModelPage {
        val root = json.parseToJsonElement(body).jsonObject
        val ids = (root["models"] as? JsonArray).orEmpty().mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val methods = (o["supportedGenerationMethods"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if ("generateContent" !in methods) return@mapNotNull null
            (o["name"] as? JsonPrimitive)?.contentOrNull?.removePrefix("models/")
        }
        return ModelPage(ids, (root["nextPageToken"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null })
    }

    /** The answer's text (thoughts left out), or why there is none. */
    fun parseAnswer(body: String): OwnKeyReply {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw OwnKeyException(OwnKeyError.Incomplete)
        val blocked = (root["promptFeedback"] as? JsonObject)?.get("blockReason")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (!blocked.isNullOrBlank()) throw OwnKeyException(OwnKeyError.Blocked)
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw OwnKeyException(OwnKeyError.Blocked)
        val finish = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val text = parts.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            if ((o["thought"] as? JsonPrimitive)?.booleanOrNull == true) null else (o["text"] as? JsonPrimitive)?.contentOrNull
        }.joinToString("")
        when (finish) {
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "LANGUAGE" -> throw OwnKeyException(OwnKeyError.Blocked)
            "MAX_TOKENS" -> throw OwnKeyException(OwnKeyError.Incomplete)
        }
        val usage = root["usageMetadata"] as? JsonObject
        return OwnKeyReply(
            text,
            inputTokens = (usage?.get("promptTokenCount") as? JsonPrimitive)?.longOrNull,
            outputTokens = (usage?.get("candidatesTokenCount") as? JsonPrimitive)?.longOrNull,
        )
    }

    /** A refused call: Google's `error.status`, the `ErrorInfo` reason and the message. */
    fun error(status: Int, body: String): OwnKeyError {
        val err = runCatching { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject }.getOrNull()
        val reasons = (err?.get("details") as? JsonArray).orEmpty().mapNotNull { d ->
            ((d as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
        }
        val code = (listOfNotNull((err?.get("status") as? JsonPrimitive)?.contentOrNull) + reasons).joinToString(" ")
        return OwnKeyError.ofHttp(status, code, (err?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty())
    }
}

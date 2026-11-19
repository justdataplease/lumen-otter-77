package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.tools.LoopTurn
import com.justdataplease.boringtravel.ai.tools.ModelTurn
import com.justdataplease.boringtravel.ai.tools.PlannerTool
import com.justdataplease.boringtravel.ai.tools.ToolCall
import com.justdataplease.boringtravel.ai.tools.ToolChat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The planner's tools over Gemini's REST API with a Google AI Studio key (ai.google.dev, function calling,
 * 2026-11-11): `tools[].functionDeclarations` with `parametersJsonSchema`, `toolConfig.functionCallingConfig.mode`
 * ANY (a call every turn; finish ends it). The model's own content goes back unchanged, so Gemini 3's thought
 * signatures stay with their calls; results go back as `functionResponse` parts.
 */
object GeminiTools {
    fun body(system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
        putJsonArray("contents") { turns.forEach { add(content(it)) } }
        putJsonArray("tools") {
            add(buildJsonObject {
                putJsonArray("functionDeclarations") {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parametersJsonSchema", JsonSchemas.of(t.parameters, JsonSchemas.Dialect.Gemini))
                        })
                    }
                }
            })
        }
        putJsonObject("toolConfig") { putJsonObject("functionCallingConfig") { put("mode", "ANY") } }
    }

    private fun content(turn: LoopTurn): JsonObject = when (turn) {
        is LoopTurn.Traveller -> text("user", turn.text)
        is LoopTurn.Planner -> (turn.raw as? JsonObject) ?: text("model", turn.text.ifBlank { "…" })
        is LoopTurn.Results -> buildJsonObject {
            put("role", "user")
            putJsonArray("parts") {
                turn.results.forEach { (call, result) ->
                    add(buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", call.name)
                            call.id?.let { put("id", it) }
                            put("response", result)
                        }
                    })
                }
            }
        }
    }

    private fun text(role: String, text: String) = buildJsonObject {
        put("role", role)
        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
    }

    /** The turn's calls and words (thoughts left out), its content kept whole for the next request. */
    fun parse(body: String): ModelTurn {
        GeminiRest.parseAnswer(body) // the same refusals and cuts as a plain answer
        val root = OwnKeyJson.parseToJsonElement(body).jsonObject
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw OwnKeyException(OwnKeyError.Incomplete)
        val content = candidate["content"] as? JsonObject ?: throw OwnKeyException(OwnKeyError.Incomplete)
        val parts = (content["parts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val calls = parts.mapNotNull { p ->
            val call = p["functionCall"] as? JsonObject ?: return@mapNotNull null
            val name = (call["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ToolCall(name, call["args"] as? JsonObject ?: JsonObject(emptyMap()), (call["id"] as? JsonPrimitive)?.contentOrNull)
        }
        val text = parts.filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
            .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }.joinToString("")
        return ModelTurn(text, calls, content)
    }
}

/**
 * The planner's tools over OpenRouter's Chat Completions (openrouter.ai/docs, tool calling, 2026-11-11): `tools` as
 * OpenAI functions, `tool_choice` required, `provider.require_parameters` so only providers that take tools answer.
 * The assistant message goes back with its `tool_calls` (and `reasoning_details`, which OpenRouter asks to keep),
 * each result as a `tool` message.
 */
object OpenRouterTools {
    fun body(model: String, system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            turns.forEach { t -> messages(t).forEach { add(it) } }
        }
        putJsonArray("tools") {
            tools.forEach { t ->
                add(buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", JsonSchemas.of(t.parameters, JsonSchemas.Dialect.Gemini))
                    }
                })
            }
        }
        put("tool_choice", "required")
        putJsonObject("provider") { put("require_parameters", true) }
    }

    private fun messages(turn: LoopTurn): List<JsonObject> = when (turn) {
        is LoopTurn.Traveller -> listOf(buildJsonObject { put("role", "user"); put("content", turn.text) })
        is LoopTurn.Planner -> listOf((turn.raw as? JsonObject) ?: buildJsonObject { put("role", "assistant"); put("content", turn.text) })
        is LoopTurn.Results -> turn.results.map { (call, result) ->
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", call.id ?: call.name)
                put("content", result.toString())
            }
        }
    }

    fun parse(body: String): ModelTurn {
        OpenRouterRest.parseAnswer(body) // errors, refusals, filters and cuts
        val root = OwnKeyJson.parseToJsonElement(body).jsonObject
        val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            ?: throw OwnKeyException(OwnKeyError.Incomplete)
        val toolCalls = (message["tool_calls"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val calls = toolCalls.mapNotNull { c ->
            val fn = c["function"] as? JsonObject ?: return@mapNotNull null
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ToolCall(name, arguments(fn["arguments"]), (c["id"] as? JsonPrimitive)?.contentOrNull)
        }
        val text = (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val raw = buildJsonObject {
            put("role", "assistant")
            put("content", text)
            if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
            message["reasoning_details"]?.let { put("reasoning_details", it) }
        }
        return ModelTurn(text, calls, raw)
    }

    /** Arguments come as a JSON string (sometimes already an object); anything unreadable becomes no arguments. */
    private fun arguments(raw: JsonElement?): JsonObject = when (raw) {
        is JsonObject -> raw
        is JsonPrimitive -> runCatching { OwnKeyJson.parseToJsonElement(raw.content) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        else -> JsonObject(emptyMap())
    }
}

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/** [GeminiTools] sent with the traveller's Google AI Studio key. */
class GeminiToolChat(private val http: OkHttpClient, private val key: String, private val model: String) : ToolChat {
    override suspend fun next(system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): ModelTurn {
        val request = Request.Builder().url(GeminiRest.generateUrl(model)).header(GeminiRest.KEY_HEADER, key)
            .post(GeminiTools.body(system, turns, tools).toString().toRequestBody(JSON_TYPE)).build()
        return GeminiTools.parse(http.send(request, GeminiRest::error))
    }
}

/** [OpenRouterTools] sent with the traveller's OpenRouter key. */
class OpenRouterToolChat(private val http: OkHttpClient, private val key: String, private val model: String) : ToolChat {
    override suspend fun next(system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): ModelTurn {
        val request = bearer(OpenRouterRest.CHAT_URL, key, OpenRouterTools.body(model, system, turns, tools), OpenRouterRest.APP_HEADERS)
        return OpenRouterTools.parse(http.send(request, OpenRouterRest::error))
    }
}

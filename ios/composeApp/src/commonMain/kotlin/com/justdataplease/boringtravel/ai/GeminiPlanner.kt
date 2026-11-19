package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.ai.tools.LoopTurn
import com.justdataplease.boringtravel.ai.tools.ModelTurn
import com.justdataplease.boringtravel.ai.tools.PlannerTool
import com.justdataplease.boringtravel.ai.tools.ToolCall
import com.justdataplease.boringtravel.ai.tools.ToolChat
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import com.justdataplease.boringtravel.firebase.InvalidAPIKeyException
import com.justdataplease.boringtravel.firebase.PromptBlockedException
import com.justdataplease.boringtravel.firebase.QuotaExceededException
import com.justdataplease.boringtravel.firebase.ResponseStoppedException
import com.justdataplease.boringtravel.firebase.ServerException
import com.justdataplease.boringtravel.firebase.ServiceDisabledException
import com.justdataplease.boringtravel.firebase.UnsupportedUserLocationException
import com.justdataplease.boringtravel.ios.Net
import com.justdataplease.boringtravel.ios.NetRequest
import com.justdataplease.boringtravel.ios.ask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

/*
 * The Android app's ai/GeminiPlanner.kt, written again here: Android asks Gemini through the Firebase AI Logic SDK,
 * which has no iPhone build for Kotlin. This phone asks the same service over its REST endpoint (the requests the SDK
 * makes, firebase-ai 17.2.0: POST firebasevertexai.googleapis.com/v1beta/projects/<project>/models/<model>
 * :generateContent with the API key, the Firebase app id and the App Check token in headers), with the same models,
 * tools, prompt, time limit and fallback rules, and its failures thrown under the SDK's exception names
 * (firebase/FirebaseAiErrors.kt), which PlannerError reads as on Android.
 */

/**
 * Where the built-in planner's App Check token comes from: Firebase App Check (App Attest) on iPhone, once the owner's
 * Firebase iPhone app has it; none on Windows and in tests. Null: no token (the service then refuses the request when
 * App Check is enforced, and the planner says so: PlannerError.AppCheck).
 */
fun interface AppCheckTokens {
    suspend fun token(): String?
}

/**
 * The built-in planner: Gemini through Firebase AI Logic (Gemini Developer API backend) with App Check, running the
 * planner's tools ([com.justdataplease.boringtravel.ai.tools.PlannerTools]). Only the trip, its plan, the pending
 * changes and the traveller's text are sent. [config] and [aiModel] are this build's ([FirebaseSetup]); [bundleId]
 * goes with each request for an API key kept to the iPhone app.
 */
class GeminiPlanner(
    private val net: Net,
    private val config: FirebaseSetup.Config = FirebaseSetup.config,
    aiModel: String = FirebaseSetup.aiModel,
    private val appCheck: AppCheckTokens = AppCheckTokens { null },
    private val appVersion: String = "",
    private val bundleId: String? = null,
    /** How the planner waits before asking a busy model again (tests don't wait). */
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : PlannerEngine {
    override val isAvailable: Boolean get() = config.isComplete

    /** [DEFAULT_MODEL], or `firebase.aiModel` from local.properties. */
    override val modelName: String = aiModel.ifBlank { DEFAULT_MODEL }

    override suspend fun plan(request: PlanRequest, onStep: (String) -> Unit): PlanResult =
        PlannerRuns.loop(FirebaseToolChat(), request.copy(message = request.message.take(MAX_NOTES)), onStep)

    /**
     * One JSON answer following [schema], without the planner's tools (Copy from a site asks which posts make which
     * trip; the pictures are read): the same models, App Check and fallback as a plan (busy: asked once more, then
     * [FALLBACK_MODEL]; out of quota: [FALLBACK_MODEL] at once). [pictures] go before the prompt ([PictureReading]).
     * The text as it came.
     */
    suspend fun answer(system: String, prompt: String, schema: SchemaNode.Obj, pictures: List<AiPicture> = emptyList()): String {
        var current = modelName
        var retried = false
        while (true) {
            try {
                return answerOnce(current, system, prompt, schema, pictures)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val quota = PlannerError.isQuota(e)
                val busy = PlannerError.isTransient(e)
                when {
                    current != FALLBACK_MODEL && (quota || (busy && retried)) -> current = FALLBACK_MODEL
                    busy && !retried -> { retried = true; wait(RETRY_WAIT_MS) }
                    else -> throw e
                }
            }
        }
    }

    private suspend fun answerOnce(name: String, system: String, prompt: String, schema: SchemaNode.Obj, pictures: List<AiPicture>): String {
        val body = buildJsonObject {
            put("model", modelPath(name))
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        pictures.forEach { p -> add(buildJsonObject { putJsonObject("inlineData") { put("mimeType", p.mime); put("data", p.base64) } }) }
                        add(buildJsonObject { put("text", prompt) })
                    }
                })
            }
            putJsonObject("generation_config") {
                put("response_mime_type", "application/json")
                put("response_schema", schema.toFirebase())
            }
            put("system_instruction", systemContent(system))
        }
        val answer = generate(name, body)
        val text = answer.parts.filterNot { it.thought }.mapNotNull { it.text }.joinToString("")
        return text.takeIf { it.isNotBlank() } ?: throw DraftParseException("The answer was empty.")
    }

    /** One model turn through Firebase AI Logic: a tool call is required, so the turn ends with finish. */
    private inner class FirebaseToolChat : ToolChat {
        /** The model this plan uses: [modelName], until it runs out of quota or stays busy; then [FALLBACK_MODEL]. */
        private var current = modelName

        /**
         * A busy or timed-out answer (Google: "high demand", "Deadline expired") is asked again once, then of
         * [FALLBACK_MODEL]; out of quota (the free tier's daily requests per model) goes to [FALLBACK_MODEL] at once.
         * Anything else goes to the screen.
         */
        override suspend fun next(system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): ModelTurn {
            var retried = false
            while (true) {
                try {
                    return once(current, system, turns, tools)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val quota = PlannerError.isQuota(e)
                    val busy = PlannerError.isTransient(e)
                    when {
                        current != FALLBACK_MODEL && (quota || (busy && retried)) -> current = FALLBACK_MODEL
                        busy && !retried -> { retried = true; wait(RETRY_WAIT_MS) }
                        else -> throw e
                    }
                }
            }
        }

        private suspend fun once(name: String, system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): ModelTurn {
            val body = buildJsonObject {
                put("model", modelPath(name))
                putJsonArray("contents") { turns.forEach { add(toContent(it)) } }
                putJsonArray("tools") { add(buildJsonObject { putJsonArray("functionDeclarations") { tools.forEach { add(declaration(it)) } } }) }
                putJsonObject("tool_config") { putJsonObject("function_calling_config") { put("mode", "ANY") } }
                put("system_instruction", systemContent(system))
            }
            val answer = generate(name, body)
            val calls = answer.parts.mapNotNull { it.call }
            val text = answer.parts.filterNot { it.thought }.mapNotNull { it.text }.joinToString("")
            if (answer.content == null && calls.isEmpty()) throw DraftParseException("The answer was empty.")
            return ModelTurn(text, calls, answer.content)
        }

        private fun toContent(turn: LoopTurn): JsonObject = when (turn) {
            is LoopTurn.Traveller -> textContent("user", turn.text)
            is LoopTurn.Planner -> (turn.raw as? JsonObject) ?: textContent("model", turn.text.ifBlank { "…" })
            // Tool results go back as the user's turn: the Gemini API refuses the role "function" (seen 11 Nov 2026).
            is LoopTurn.Results -> buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    turn.results.forEach { (call, result) ->
                        add(buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", call.name)
                                put("response", result)
                                call.id?.let { put("id", it) }
                            }
                        })
                    }
                }
            }
        }
    }

    /** One part of the model's answer: words, a thought, or a tool call. */
    private class Part(val text: String?, val thought: Boolean, val call: ToolCall?)

    /** The first candidate's content (sent back unchanged in the next turn: thought signatures stay with their calls) and its parts. */
    private class Answer(val content: JsonObject?, val parts: List<Part>)

    /** One request; refusals and stops thrown as the SDK throws them. */
    private suspend fun generate(model: String, body: JsonObject): Answer {
        if (!config.isComplete) throw PlannerNotSetUpException()
        val headers = buildMap {
            put("x-goog-api-key", config.apiKey)
            put("x-goog-api-client", API_CLIENT)
            put("X-Firebase-AppId", config.appId)
            if (appVersion.isNotEmpty()) put("X-Firebase-AppVersion", appVersion)
            bundleId?.let { put("X-Ios-Bundle-Identifier", it) }
            appCheck.token()?.takeIf { it.isNotEmpty() }?.let { put("X-Firebase-AppCheck", it) }
        }
        val r = net.ask(
            NetRequest(
                "$BASE/${modelPath(model)}:generateContent", headers, maxBytes = MAX_ANSWER_BYTES,
                timeoutMs = TIMEOUT_MS, readTimeoutMs = TIMEOUT_MS, body = body.toString(),
            ),
        )
        if (r.code != 200) throw failure(r.code, r.text())
        val root = runCatching { json.parseToJsonElement(r.text()).jsonObject }.getOrNull()
            ?: throw SerializationException("Error deserializing response, found no valid fields")
        val candidates = (root["candidates"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val feedback = root["promptFeedback"] as? JsonObject
        if (candidates.isEmpty() && feedback == null) throw SerializationException("Error deserializing response, found no valid fields")
        (feedback?.get("blockReason") as? JsonPrimitive)?.contentOrNull?.let { throw PromptBlockedException("Prompt was blocked: $it") }
        candidates.mapNotNull { (it["finishReason"] as? JsonPrimitive)?.contentOrNull }.firstOrNull { it != "STOP" }
            ?.let { throw ResponseStoppedException("Content generation stopped. Reason: $it") }
        val content = candidates.firstOrNull()?.get("content") as? JsonObject
        val parts = (content?.get("parts") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { p ->
            val call = (p["functionCall"] as? JsonObject)?.let { c ->
                val n = (c["name"] as? JsonPrimitive)?.contentOrNull ?: return@let null
                ToolCall(n, c["args"] as? JsonObject ?: JsonObject(emptyMap()), (c["id"] as? JsonPrimitive)?.contentOrNull)
            }
            Part((p["text"] as? JsonPrimitive)?.contentOrNull, (p["thought"] as? JsonPrimitive)?.booleanOrNull == true, call)
        }
        return Answer(content, parts)
    }

    /** A refused request, as the SDK's validateResponse sorts it by Google's error message. */
    private fun failure(status: Int, text: String): Exception {
        val error = runCatching { json.parseToJsonElement(text).jsonObject["error"] as? JsonObject }.getOrNull()
            ?: return ServerException("Unexpected Response:\n$text")
        val message = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val disabled = (error["details"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any {
            (it["reason"] as? JsonPrimitive)?.contentOrNull == "SERVICE_DISABLED" && (it["domain"] as? JsonPrimitive)?.contentOrNull == "googleapis.com"
        }
        return when {
            "API key not valid" in message -> InvalidAPIKeyException(message)
            message == "User location is not supported for the API use." -> UnsupportedUserLocationException()
            "quota" in message -> QuotaExceededException(message)
            "The prompt could not be submitted" in message -> PromptBlockedException(message)
            disabled -> ServiceDisabledException(message)
            else -> ServerException(message.ifEmpty { "HTTP $status" })
        }
    }

    private fun modelPath(model: String) = "projects/${config.projectId}/models/$model"

    companion object {
        /**
         * Google's alias for its newest Flash model (owner, 2026-11-11: "have the latest versions only … like
         * gemini flash latest"): it follows new releases without an app update. Override with firebase.aiModel.
         */
        const val DEFAULT_MODEL = "gemini-flash-latest"

        /** Asked when [DEFAULT_MODEL] is out of quota or stays busy: Google's alias for its newest Flash-Lite. */
        const val FALLBACK_MODEL = "gemini-flash-lite-latest"
        private const val RETRY_WAIT_MS = 2_000L

        /** Longest text sent in one message (about 5,000 words). */
        const val MAX_NOTES = ItineraryImport.MAX_CHARS
        private const val TIMEOUT_MS = 180_000L
        private const val MAX_ANSWER_BYTES = 16L * 1024 * 1024

        /** Firebase AI Logic's endpoint (the SDK's RequestOptions: endpoint and API version). */
        const val BASE = "https://firebasevertexai.googleapis.com/v1beta"

        /** Who asks, in Google's client header (the Android SDK sends "gl-kotlin/… fire/17.2.0"). */
        private const val API_CLIENT = "gl-kotlin fire/boringtravel-ios"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun textContent(role: String, text: String) = buildJsonObject {
            put("role", role)
            putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
        }

        /** The system instruction, as the SDK sends it (its role "system"). */
        private fun systemContent(system: String) = textContent("system", system)

        private fun declaration(tool: PlannerTool) = buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters.toFirebase())
        }

        /**
         * [SchemaNode] as the SDK's Schema writes it (OpenAPI's subset: OBJECT, ARRAY, STRING, INTEGER int32, NUMBER,
         * BOOLEAN, an enumeration as a STRING of format "enum"), required = every property not optional.
         */
        internal fun SchemaNode.toFirebase(): JsonObject = buildJsonObject {
            when (val n = this@toFirebase) {
                is SchemaNode.Obj -> {
                    put("type", "OBJECT")
                    n.description?.let { put("description", it) }
                    putJsonObject("properties") { n.properties.forEach { (k, v) -> put(k, v.toFirebase()) } }
                    put("required", buildJsonArray { n.properties.keys.filter { it !in n.optional }.forEach { add(JsonPrimitive(it)) } })
                }
                is SchemaNode.Arr -> {
                    put("type", "ARRAY")
                    n.description?.let { put("description", it) }
                    put("items", n.items.toFirebase())
                    n.maxItems?.let { put("maxItems", it) }
                }
                is SchemaNode.Str -> {
                    put("type", "STRING")
                    n.description?.let { put("description", it) }
                    if (n.nullable) put("nullable", true)
                }
                is SchemaNode.Choice -> {
                    put("type", "STRING")
                    put("format", "enum")
                    n.description?.let { put("description", it) }
                    put("enum", buildJsonArray { n.values.forEach { add(JsonPrimitive(it)) } })
                }
                is SchemaNode.Integer -> {
                    put("type", "INTEGER")
                    put("format", "int32")
                    n.description?.let { put("description", it) }
                    if (n.nullable) put("nullable", true)
                }
                is SchemaNode.Decimal -> {
                    put("type", "NUMBER")
                    n.description?.let { put("description", it) }
                    if (n.nullable) put("nullable", true)
                }
                is SchemaNode.Bool -> {
                    put("type", "BOOLEAN")
                    n.description?.let { put("description", it) }
                }
            }
        }
    }
}

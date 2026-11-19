package com.justdataplease.boringtravel.ai

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallingConfig
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.ToolConfig
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.justdataplease.boringtravel.BuildConfig
import com.justdataplease.boringtravel.ai.tools.LoopTurn
import com.justdataplease.boringtravel.ai.tools.ModelTurn
import com.justdataplease.boringtravel.ai.tools.PlannerTool
import com.justdataplease.boringtravel.ai.tools.ToolCall
import com.justdataplease.boringtravel.ai.tools.ToolChat
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * The built-in planner: Gemini through Firebase AI Logic (Gemini Developer API backend) with App Check, running the
 * planner's tools ([com.justdataplease.boringtravel.ai.tools.PlannerTools]). Only the trip, its plan, the pending
 * changes and the traveller's text are sent.
 */
class GeminiPlanner(private val context: Context) : PlannerEngine {
    override val isAvailable: Boolean get() = FirebaseSetup.isConfigured

    /** [DEFAULT_MODEL], or `firebase.aiModel` from local.properties. */
    override val modelName: String = BuildConfig.AI_MODEL.ifBlank { DEFAULT_MODEL }

    override suspend fun plan(request: PlanRequest, onStep: (String) -> Unit): PlanResult =
        PlannerRuns.loop(FirebaseToolChat(), request.copy(message = request.message.take(MAX_NOTES)), onStep)

    /**
     * One JSON answer following [schema], without the planner's tools (Copy from a site asks which posts make which
     * trip): the same models, App Check and fallback as a plan (busy: asked once more, then [FALLBACK_MODEL]; out of
     * quota: [FALLBACK_MODEL] at once). [pictures] go before the prompt ([PictureReading]). The text as it came.
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
                    busy && !retried -> { retried = true; delay(RETRY_WAIT_MS) }
                    else -> throw e
                }
            }
        }
    }

    private suspend fun answerOnce(name: String, system: String, prompt: String, schema: SchemaNode.Obj, pictures: List<AiPicture>): String {
        val app = FirebaseSetup.app(context) ?: throw PlannerNotSetUpException()
        val model = Firebase.ai(app = app, backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = name,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema.toFirebase()
            },
            systemInstruction = content { text(system) },
            requestOptions = RequestOptions(TIMEOUT_MS),
        )
        val text = model.generateContent(content(role = "user") {
            pictures.forEach { inlineData(it.bytes, it.mime) }
            text(prompt)
        }).text
        return text?.takeIf { it.isNotBlank() } ?: throw DraftParseException("The answer was empty.")
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
                        busy && !retried -> { retried = true; delay(RETRY_WAIT_MS) }
                        else -> throw e
                    }
                }
            }
        }

        private suspend fun once(name: String, system: String, turns: List<LoopTurn>, tools: List<PlannerTool>): ModelTurn {
            val app = FirebaseSetup.app(context) ?: throw PlannerNotSetUpException()
            val model = Firebase.ai(app = app, backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = name,
                tools = listOf(Tool.functionDeclarations(tools.map(::declaration))),
                toolConfig = ToolConfig(FunctionCallingConfig.any()),
                systemInstruction = content { text(system) },
                requestOptions = RequestOptions(TIMEOUT_MS),
            )
            val response = model.generateContent(turns.map(::toContent))
            val raw = response.candidates.firstOrNull()?.content
            val calls = response.functionCalls.map { ToolCall(it.name, JsonObject(it.args), it.id) }
            val text = raw?.parts.orEmpty().filterIsInstance<TextPart>().filterNot { it.isThought }.joinToString("") { it.text }
            if (raw == null && calls.isEmpty()) throw DraftParseException("The answer was empty.")
            return ModelTurn(text, calls, raw)
        }

        private fun toContent(turn: LoopTurn): Content = when (turn) {
            is LoopTurn.Traveller -> content(role = "user") { text(turn.text) }
            is LoopTurn.Planner -> (turn.raw as? Content) ?: content(role = "model") { text(turn.text.ifBlank { "…" }) }
            // Tool results go back as the user's turn: the Gemini API refuses the role "function" (seen 11 Nov 2026).
            is LoopTurn.Results -> Content(role = "user", parts = turn.results.map { (call, result) -> FunctionResponsePart(call.name, result, call.id) })
        }
    }

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

        private fun declaration(tool: PlannerTool) = FunctionDeclaration(
            tool.name, tool.description,
            tool.parameters.properties.mapValues { it.value.toFirebase() },
            tool.parameters.optional,
        )

        internal fun SchemaNode.toFirebase(): Schema = when (this) {
            is SchemaNode.Obj -> Schema.obj(properties.mapValues { it.value.toFirebase() }, optionalProperties = optional, description = description)
            is SchemaNode.Arr -> Schema.array(items.toFirebase(), description = description, maxItems = maxItems)
            is SchemaNode.Str -> Schema.string(description = description, nullable = nullable)
            is SchemaNode.Choice -> Schema.enumeration(values, description = description)
            is SchemaNode.Integer -> Schema.integer(description = description, nullable = nullable)
            is SchemaNode.Decimal -> Schema.double(description = description, nullable = nullable)
            is SchemaNode.Bool -> Schema.boolean(description = description)
        }
    }
}

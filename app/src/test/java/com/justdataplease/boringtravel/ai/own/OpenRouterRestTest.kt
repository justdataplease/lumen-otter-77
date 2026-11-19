package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class OpenRouterRestTest {
    @Test fun draftBodyIsAChatRequestWithAStrictSchemaOnlyForProvidersThatTakeIt() {
        val schema = JsonSchemas.of(DraftSchema.generation, OpenRouterRest.dialect("openai/gpt-6.1-terra"))
        val body = OpenRouterRest.draftBody("openai/gpt-6.1-terra", "RULES", listOf(ChatTurn(true, "Rome"), ChatTurn(false, "{}"), ChatTurn(true, "More")), schema)
        assertEquals("openai/gpt-6.1-terra", body.getValue("model").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("RULES", messages[0].getValue("content").jsonPrimitive.content)
        val format = body.getValue("response_format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        val js = format.getValue("json_schema").jsonObject
        assertEquals(OpenAiRest.SCHEMA_NAME, js.getValue("name").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), js.getValue("strict"))
        assertEquals(schema, js.getValue("schema"))
        assertEquals(JsonPrimitive(true), body.getValue("provider").jsonObject.getValue("require_parameters"))
    }

    @Test fun schemaFollowsTheModelMaker() {
        assertEquals(JsonSchemas.Dialect.Claude, OpenRouterRest.dialect("anthropic/claude-sonnet-5.5"))
        assertEquals(JsonSchemas.Dialect.Gemini, OpenRouterRest.dialect("google/gemini-3.5-flash-lite"))
        assertEquals(JsonSchemas.Dialect.OpenAi, OpenRouterRest.dialect("openai/gpt-6-luna"))
        assertEquals(JsonSchemas.Dialect.OpenAi, OpenRouterRest.dialect("deepseek/deepseek-v4.1-flash"))
    }

    @Test fun urlsAndHeaders() {
        assertEquals("https://openrouter.ai/api/v1/key", OpenRouterRest.KEY_URL)
        assertEquals("https://openrouter.ai/api/v1/models", OpenRouterRest.MODELS_URL)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", OpenRouterRest.CHAT_URL)
        assertEquals("BoringTravel", OpenRouterRest.APP_HEADERS["X-OpenRouter-Title"])
        assertEquals("https://justdataplease.com", OpenRouterRest.APP_HEADERS["HTTP-Referer"])
        val r = bearer(OpenRouterRest.CHAT_URL, "sk-or-v1-x", OpenRouterRest.pingBody("openai/gpt-6-luna"), OpenRouterRest.APP_HEADERS)
        assertEquals("Bearer sk-or-v1-x", r.header("Authorization"))
        assertEquals("BoringTravel", r.header("X-OpenRouter-Title"))
        assertEquals("POST", r.method)
        assertEquals("GET", bearer(OpenRouterRest.KEY_URL, "k", null).method)
    }

    @Test fun pingAsksForASchemaFromProvidersThatTakeIt() {
        val body = OpenRouterRest.pingBody("openai/gpt-6-luna")
        val format = body.getValue("response_format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertEquals(JsonSchemas.ping, format.getValue("json_schema").jsonObject.getValue("schema"))
        assertEquals(JsonPrimitive(true), body.getValue("provider").jsonObject.getValue("require_parameters"))
    }

    @Test fun modelsKeepTextModelsWithStructuredOutputsAndToolsNewestFirst() {
        val ids = OpenRouterRest.parseModels(
            """{"data":[
              {"id":"old/text","created":100,"architecture":{"output_modalities":["text"]},"supported_parameters":["structured_outputs","response_format","tools","tool_choice"]},
              {"id":"new/text","created":300,"architecture":{"output_modalities":["text"]},"supported_parameters":["structured_outputs","tools"]},
              {"id":"new/text:batch","created":300,"architecture":{"output_modalities":["text"]},"supported_parameters":["structured_outputs","tools"]},
              {"id":"no/schema","created":400,"architecture":{"output_modalities":["text"]},"supported_parameters":["response_format","tools"]},
              {"id":"no/tools","created":450,"architecture":{"output_modalities":["text"]},"supported_parameters":["structured_outputs","response_format"]},
              {"id":"draws/pictures","created":500,"architecture":{"output_modalities":["text","image"]},"supported_parameters":["structured_outputs","tools"]}
            ]}""",
        )
        // A model without tool calls passed Test but failed every plan (the planner's loop): not offered.
        assertEquals(listOf("new/text", "old/text"), ids)
    }

    @Test fun keyInfo() {
        assertEquals(0.0, OpenRouterRest.keyLimitRemaining("""{"data":{"label":"x","limit":5,"limit_remaining":0,"usage":5}}""")!!, 0.0)
        assertNull(OpenRouterRest.keyLimitRemaining("""{"data":{"label":"x","limit":null,"limit_remaining":null}}"""))
    }

    @Test fun answerText() {
        val reply = OpenRouterRest.parseAnswer(
            """{"id":"gen-1","model":"openai/gpt-6.1-terra","choices":[{"finish_reason":"stop","native_finish_reason":"stop",
                "message":{"role":"assistant","content":"{\"summary\":\"ok\"}","refusal":null}}],
                "usage":{"prompt_tokens":900,"completion_tokens":120,"total_tokens":1020}}""",
        )
        assertEquals("{\"summary\":\"ok\"}", reply.text)
        assertEquals(900L, reply.inputTokens)
        assertEquals(120L, reply.outputTokens)
    }

    @Test fun refusalsFiltersCutsAndErrorsInA200() {
        assertError(OwnKeyError.Blocked) { OpenRouterRest.parseAnswer("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":null,"refusal":"I can't help."}}]}""") }
        assertError(OwnKeyError.Blocked) { OpenRouterRest.parseAnswer("""{"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}""") }
        assertError(OwnKeyError.Incomplete) { OpenRouterRest.parseAnswer("""{"choices":[{"finish_reason":"length","message":{"content":"{\"su"}}]}""") }
        assertError(OwnKeyError.Busy) { OpenRouterRest.parseAnswer("""{"choices":[{"finish_reason":"error","error":{"code":502,"message":"Provider returned error"},"message":{"content":""}}]}""") }
        assertError(OwnKeyError.NoCredit) { OpenRouterRest.parseAnswer("""{"error":{"code":402,"message":"Insufficient credits"}}""") }
    }

    /** What OpenRouter answered a made-up key on 2026-11-11 (GET /api/v1/key). */
    private val wrongKey = """{"error":{"message":"User not found.","code":401}}"""

    @Test fun errorsInPlainWords() {
        assertEquals(OwnKeyError.WrongKey, OpenRouterRest.error(401, wrongKey))
        assertEquals(OwnKeyError.NoCredit, OpenRouterRest.error(402, """{"error":{"code":402,"message":"This request requires more credits, or fewer max_tokens."}}"""))
        assertEquals(OwnKeyError.Blocked, OpenRouterRest.error(403, """{"error":{"code":403,"message":"Input was flagged","metadata":{"reasons":["violence"],"flagged_input":"…"}}}"""))
        assertEquals(OwnKeyError.TooMany, OpenRouterRest.error(429, """{"error":{"code":429,"message":"Rate limit exceeded"}}"""))
        assertEquals(OwnKeyError.Busy, OpenRouterRest.error(502, """{"error":{"code":502,"message":"Provider returned error"}}"""))
        assertEquals(OwnKeyError.ModelUnavailable, OpenRouterRest.error(404, """{"error":{"code":404,"message":"No endpoints found that can handle the requested parameters."}}"""))
        assertEquals(OwnKeyError.ModelUnavailable, OpenRouterRest.error(503, """{"error":{"code":503,"message":"No provider meets your routing requirements"}}"""))
        assertEquals(OwnKeyError.Busy, OpenRouterRest.error(503, "<html>down</html>"))
        assertEquals(OwnKeyError.Timeout, OpenRouterRest.error(408, """{"error":{"code":408,"message":"Timed out"}}"""))
        assertEquals(OwnKeyError.BadRequest, OpenRouterRest.error(400, """{"error":{"code":400,"message":"Invalid schema"}}"""))
    }

    private fun assertError(expected: OwnKeyError, block: () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: OwnKeyException) {
            assertEquals(expected, e.error)
        }
    }
}

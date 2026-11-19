package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiRestTest {
    private val schema = JsonSchemas.of(DraftSchema.generation, JsonSchemas.Dialect.Gemini)

    @Test fun draftBodyCarriesSystemTurnsAndJsonSchema() {
        val body = GeminiRest.draftBody("RULES", listOf(ChatTurn(true, "Rome in May"), ChatTurn(false, "{\"summary\":\"x\"}"), ChatTurn(true, "Add a day")), schema)
        assertEquals("RULES", body.getValue("systemInstruction").jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        val contents = body.getValue("contents").jsonArray
        assertEquals(listOf("user", "model", "user"), contents.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals("Add a day", contents[2].jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        val config = body.getValue("generationConfig").jsonObject
        assertEquals("application/json", config.getValue("responseMimeType").jsonPrimitive.content)
        assertEquals(schema, config.getValue("responseJsonSchema"))
        assertEquals(0.3, config.getValue("temperature").jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test fun urlsPutTheModelInThePathAndNeverTheKey() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent", GeminiRest.generateUrl("models/gemini-3.5-flash-lite"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", GeminiRest.modelsUrl())
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&pageToken=a%2Fb", GeminiRest.modelsUrl("a/b"))
        assertEquals("x-goog-api-key", GeminiRest.KEY_HEADER)
    }

    @Test fun modelListKeepsGenerateContentModels() {
        val page = GeminiRest.parseModels(
            """{"models":[
              {"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/gemini-embedding-002","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]}
            ],"nextPageToken":"next"}""",
        )
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.8-flash"), page.ids)
        assertEquals("next", page.next)
        assertEquals(null, GeminiRest.parseModels("""{"models":[]}""").next)
    }

    @Test fun answerTextSkipsThoughts() {
        val reply = GeminiRest.parseAnswer(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"thinking…","thought":true},{"text":"{\"summary\":"},{"text":"\"ok\"}"}]},"finishReason":"STOP"}],
               "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":30,"totalTokenCount":150}}""",
        )
        assertEquals("{\"summary\":\"ok\"}", reply.text)
        assertEquals(120L, reply.inputTokens)
        assertEquals(30L, reply.outputTokens)
    }

    @Test fun blockedAndCutAnswersAreErrors() {
        assertError(OwnKeyError.Blocked) { GeminiRest.parseAnswer("""{"promptFeedback":{"blockReason":"SAFETY"}}""") }
        assertError(OwnKeyError.Blocked) { GeminiRest.parseAnswer("""{"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}""") }
        assertError(OwnKeyError.Incomplete) { GeminiRest.parseAnswer("""{"candidates":[{"content":{"parts":[{"text":"{\"sum"}]},"finishReason":"MAX_TOKENS"}]}""") }
        assertError(OwnKeyError.Incomplete) { GeminiRest.parseAnswer("not json") }
    }

    /** The answer Google gave a made-up key on 2026-11-11 (GET /v1beta/models and generateContent alike). */
    private val wrongKey = """{
      "error": {
        "code": 400,
        "message": "API key not valid. Please pass a valid API key.",
        "status": "INVALID_ARGUMENT",
        "details": [
          {"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "API_KEY_INVALID", "domain": "googleapis.com",
           "metadata": {"service": "generativelanguage.googleapis.com"}},
          {"@type": "type.googleapis.com/google.rpc.LocalizedMessage", "locale": "en-US", "message": "API key not valid. Please pass a valid API key."}
        ]
      }
    }"""

    @Test fun errorsInPlainWords() {
        assertEquals(OwnKeyError.WrongKey, GeminiRest.error(400, wrongKey))
        assertEquals(
            OwnKeyError.TooMany,
            GeminiRest.error(429, """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details.","status":"RESOURCE_EXHAUSTED"}}"""),
        )
        assertEquals(
            OwnKeyError.ModelUnavailable,
            GeminiRest.error(404, """{"error":{"code":404,"message":"models/gemini-9 is not found for API version v1beta, or is not supported for generateContent.","status":"NOT_FOUND"}}"""),
        )
        assertEquals(
            OwnKeyError.Region,
            GeminiRest.error(400, """{"error":{"code":400,"message":"User location is not supported for the API use.","status":"FAILED_PRECONDITION"}}"""),
        )
        assertEquals(OwnKeyError.Busy, GeminiRest.error(503, """{"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}"""))
        assertEquals(OwnKeyError.BadRequest, GeminiRest.error(400, """{"error":{"code":400,"message":"Invalid JSON payload","status":"INVALID_ARGUMENT"}}"""))
        assertEquals(OwnKeyError.WrongKey, GeminiRest.error(403, "<html>forbidden</html>"))
    }

    @Test fun pingIsTinyAndAsksForJsonLikeADraft() {
        val text = GeminiRest.pingBody().getValue("contents").jsonArray[0].jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("text")
        assertEquals(JsonPrimitive("Reply with OK."), text)
        val config = GeminiRest.pingBody().getValue("generationConfig").jsonObject
        assertEquals(JsonPrimitive("application/json"), config.getValue("responseMimeType"))
        assertEquals(JsonSchemas.ping, config.getValue("responseJsonSchema"))
        assertTrue("systemInstruction" !in GeminiRest.pingBody())
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

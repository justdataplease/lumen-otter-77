package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ai.DraftSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OpenAiRestTest {
    private val schema = JsonSchemas.of(DraftSchema.generation, JsonSchemas.Dialect.OpenAi)

    @Test fun draftBodyIsAResponsesRequestWithAStrictSchema() {
        val body = OpenAiRest.draftBody("gpt-6-astra", "RULES", listOf(ChatTurn(true, "Rome in May"), ChatTurn(false, "{}"), ChatTurn(true, "More")), schema)
        assertEquals("gpt-6-astra", body.getValue("model").jsonPrimitive.content)
        assertEquals("RULES", body.getValue("instructions").jsonPrimitive.content)
        val input = body.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), input.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("Rome in May", input[0].getValue("content").jsonPrimitive.content)
        val format = body.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertEquals("trip_draft", format.getValue("name").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), format.getValue("strict"))
        assertEquals(schema, format.getValue("schema"))
        assertEquals(JsonPrimitive(false), body.getValue("store"))
    }

    @Test fun urls() {
        assertEquals("https://api.openai.com/v1/models", OpenAiRest.MODELS_URL)
        assertEquals("https://api.openai.com/v1/responses", OpenAiRest.RESPONSES_URL)
        assertEquals(JsonPrimitive("Reply with OK."), OpenAiRest.pingBody("gpt-6-luna")["input"])
    }

    @Test fun pingAsksForAStrictSchemaLikeADraft() {
        val body = OpenAiRest.pingBody("gpt-4o-2024-05-13")
        val format = body.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), format.getValue("strict"))
        assertEquals(JsonSchemas.ping, format.getValue("schema"))
        assertEquals(JsonPrimitive(false), body.getValue("store"))
    }

    @Test fun modelList() {
        assertEquals(
            listOf("gpt-6-astra", "gpt-image-2.6", "text-embedding-4"),
            OpenAiRest.parseModels("""{"object":"list","data":[{"id":"gpt-6-astra","object":"model"},{"id":"gpt-image-2.6"},{"id":"text-embedding-4"}]}"""),
        )
    }

    @Test fun answerText() {
        val reply = OpenAiRest.parseAnswer(
            """{"id":"resp_1","object":"response","status":"completed","output":[
                 {"type":"reasoning","id":"rs_1","summary":[]},
                 {"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"{\"summary\":\"ok\"}","annotations":[]}]}
               ],"usage":{"input_tokens":200,"output_tokens":40,"total_tokens":240}}""",
        )
        assertEquals("{\"summary\":\"ok\"}", reply.text)
        assertEquals(200L, reply.inputTokens)
        assertEquals(40L, reply.outputTokens)
    }

    @Test fun refusalsAndCutAnswersAreErrors() {
        assertError(OwnKeyError.Blocked) {
            OpenAiRest.parseAnswer("""{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"I can't help with that."}]}]}""")
        }
        assertError(OwnKeyError.Incomplete) {
            OpenAiRest.parseAnswer("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""")
        }
        assertError(OwnKeyError.Blocked) {
            OpenAiRest.parseAnswer("""{"status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[]}""")
        }
    }

    /** The answer OpenAI gave a made-up key on 2026-11-11 (GET /v1/models). */
    private val wrongKey = """{
      "error": {
        "message": "Incorrect API key provided: sk-proj-****************0000. You can find your API key at https://platform.openai.com/account/api-keys.",
        "type": "invalid_request_error",
        "param": null,
        "code": "invalid_api_key"
      }
    }"""

    @Test fun errorsInPlainWords() {
        assertEquals(OwnKeyError.WrongKey, OpenAiRest.error(401, wrongKey))
        assertEquals(
            OwnKeyError.NoCredit,
            OpenAiRest.error(429, """{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota","code":"insufficient_quota"}}"""),
        )
        assertEquals(
            OwnKeyError.TooMany,
            OpenAiRest.error(429, """{"error":{"message":"Rate limit reached for requests","type":"requests","code":"rate_limit_exceeded"}}"""),
        )
        assertEquals(
            OwnKeyError.ModelUnavailable,
            OpenAiRest.error(404, """{"error":{"message":"The model `gpt-9` does not exist or you do not have access to it.","type":"invalid_request_error","code":"model_not_found"}}"""),
        )
        assertEquals(
            OwnKeyError.Region,
            OpenAiRest.error(403, """{"error":{"code":"unsupported_country_region_territory","message":"Country, region, or territory not supported","type":"request_forbidden"}}"""),
        )
        assertEquals(OwnKeyError.Busy, OpenAiRest.error(500, """{"error":{"message":"The server had an error","type":"server_error"}}"""))
        assertEquals(
            OwnKeyError.BadRequest,
            OpenAiRest.error(400, """{"error":{"message":"Invalid schema for response_format","type":"invalid_request_error","code":"invalid_json_schema"}}"""),
        )
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

package com.justdataplease.boringtravel.ai.own

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnexpectedStatusCodeException
import com.anthropic.models.beta.messages.BetaMessage
import com.justdataplease.boringtravel.ai.ChatTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.CompletionException

/** The Claude requests as the SDK will send them, the answer reading and the SDK's errors in plain words. */
class ClaudeRequestsTest {
    private val mapper = jsonMapper()
    private val turns = listOf(ChatTurn(true, "Rome in May"), ChatTurn(false, "{\"summary\":\"Dates?\"}"), ChatTurn(true, "1 to 4 May"))

    @Test fun draftRequestForOpus() {
        val params = ClaudeRequests.draft("claude-opus-5-5", "RULES", turns)
        assertEquals("claude-opus-5-5", params.model().asString())
        assertEquals(16_000L, params.maxTokens())
        // The tools' schema goes in the system prompt: a strict format allows only 24 optional fields.
        val system = params.system().get().string().get()
        assertTrue(system.startsWith("RULES\n\nAnswer with one JSON object and nothing else"))
        assertTrue(system.endsWith(ClaudeRequests.schema.toString()))
        assertEquals(listOf("user", "assistant", "user"), params.messages().map { mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(it).get("role").asText() })
        val config = Json.parseToJsonElement(mapper.writeValueAsString(params.outputConfig().get())).jsonObject
        assertEquals("\"medium\"", config.getValue("effort").toString())
        assertFalse("format" in config)
        // Server-side refusal fallback, "default" form, with its beta header.
        assertEquals(listOf(ClaudeRequests.FALLBACK_BETA), params.betas().get().map { it.asString() })
        assertEquals("\"default\"", mapper.writeValueAsString(params.fallbacks().get()))
        // Thinking is left to the model (adaptive by default on the current models; disabling it is a 400 on Opus 5.5).
        assertFalse(params.thinking().isPresent)
    }

    @Test fun haikuHasEffortButNoFallback() {
        val params = ClaudeRequests.draft("claude-haiku-5-5", "RULES", turns)
        assertFalse(params.fallbacks().isPresent)
        assertFalse(params.betas().isPresent && params.betas().get().isNotEmpty())
        assertTrue(mapper.writeValueAsString(params.outputConfig().get()).contains("\"effort\":\"medium\""))
    }

    @Test fun anOlderListedModelGetsNoEffortOrFallback() {
        val params = ClaudeRequests.draft("claude-sonnet-4-6", "RULES", turns)
        assertFalse(params.outputConfig().isPresent)
        assertFalse(params.fallbacks().isPresent)
        assertTrue(ClaudeRequests.withEffort("claude-opus-6"))
        assertTrue(ClaudeRequests.withFallback("claude-sonnet-6-1"))
        assertFalse(ClaudeRequests.withFallback("claude-haiku-5-5"))
    }

    @Test fun pingIsSmallAndAsksForStructuredOutputLikeADraft() {
        val params = ClaudeRequests.ping("claude-sonnet-5-5")
        assertEquals(256L, params.maxTokens())
        assertEquals(1, params.messages().size)
        assertFalse(params.fallbacks().isPresent)
        val config = Json.parseToJsonElement(mapper.writeValueAsString(params.outputConfig().get())).jsonObject
        assertEquals("\"low\"", config.getValue("effort").toString())
        val format = config.getValue("format").jsonObject
        assertEquals("\"json_schema\"", format.getValue("type").toString())
        assertEquals(JsonSchemas.ping, format.getValue("schema"))
        // A model typed by hand gets the format too (that's what Test proves), without effort.
        val older = mapper.writeValueAsString(ClaudeRequests.ping("claude-3-5-haiku-latest").outputConfig().get())
        assertTrue(older.contains("json_schema"))
        assertFalse(older.contains("effort"))
    }

    @Test fun sonnetIsTheDefaultAndKeepsTheRefusalFallback() {
        assertEquals("claude-sonnet-latest", OwnAiModels.fallback(OwnAiProvider.Claude))
        val params = ClaudeRequests.draft("claude-sonnet-5-5", "RULES", turns)
        assertEquals("\"default\"", mapper.writeValueAsString(params.fallbacks().get()))
        assertTrue(mapper.writeValueAsString(params.outputConfig().get()).contains("\"effort\":\"medium\""))
    }

    @Test fun timeoutsFitThePlannerAndTest() {
        assertEquals(1, ClaudeKeyApi.MAX_RETRIES)
        // A draft's stream stays inside the planner's 190 s cap.
        assertTrue(ClaudeKeyApi.DRAFT_TIMEOUT.seconds < 190)
        // Two tries of the list and of the ping fit Me › AI's 45 s Test.
        assertTrue(2 * ClaudeKeyApi.LIST_TIMEOUT.seconds + 2 * ClaudeKeyApi.PING_TIMEOUT.seconds < 45)
        val options = ClaudeKeyApi.options(ClaudeKeyApi.DRAFT_TIMEOUT, read = ClaudeKeyApi.DRAFT_READ_TIMEOUT).timeout().get()
        assertEquals(ClaudeKeyApi.DRAFT_TIMEOUT, options.request())
        assertEquals(ClaudeKeyApi.DRAFT_READ_TIMEOUT, options.read())
    }

    private fun message(stop: String, text: String = "{\"summary\":\"ok\"}") = mapper.readValue(
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","stop_reason":"$stop","stop_sequence":null,
            "content":[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":${mapper.writeValueAsString(text)}}],
            "usage":{"input_tokens":1200,"output_tokens":300}}""",
        BetaMessage::class.java,
    )

    @Test fun replyReadsTheTextAndUsage() {
        val reply = ClaudeRequests.reply(message("end_turn"))
        assertEquals("{\"summary\":\"ok\"}", reply.text)
        assertEquals(1200L, reply.inputTokens)
        assertEquals(300L, reply.outputTokens)
    }

    @Test fun refusalAndCutAnswersAreErrors() {
        assertError(OwnKeyError.Blocked) { ClaudeRequests.reply(message("refusal", "")) }
        assertError(OwnKeyError.Incomplete) { ClaudeRequests.reply(message("max_tokens", "{\"summ")) }
        // The Test button only needs an answer, even a cut one.
        ClaudeRequests.reply(message("max_tokens", ""), requireText = false)
    }

    private fun headers() = Headers.builder().build()
    private fun body(type: String, message: String) =
        JsonValue.from(mapOf("type" to "error", "error" to mapOf("type" to type, "message" to message)))

    @Test fun sdkErrorsInPlainWords() {
        assertEquals(OwnKeyError.WrongKey, ClaudeRequests.errorOf(UnauthorizedException.builder().headers(headers()).body(body("authentication_error", "invalid x-api-key")).build()))
        assertEquals(OwnKeyError.ModelUnavailable, ClaudeRequests.errorOf(NotFoundException.builder().headers(headers()).body(body("not_found_error", "model: claude-x")).build()))
        assertEquals(OwnKeyError.TooMany, ClaudeRequests.errorOf(RateLimitException.builder().headers(headers()).body(body("rate_limit_error", "slow down")).build()))
        assertEquals(OwnKeyError.Busy, ClaudeRequests.errorOf(InternalServerException.builder().statusCode(529).headers(headers()).body(body("overloaded_error", "Overloaded")).build()))
        assertEquals(
            OwnKeyError.NoCredit,
            ClaudeRequests.errorOf(UnexpectedStatusCodeException.builder().statusCode(402).headers(headers()).body(body("billing_error", "Your credit balance is too low.")).build()),
        )
        // The SDK's async calls fail with the error wrapped.
        assertEquals(
            OwnKeyError.WrongKey,
            ClaudeRequests.errorOf(CompletionException(UnauthorizedException.builder().headers(headers()).body(body("authentication_error", "invalid x-api-key")).build())),
        )
        assertEquals(OwnKeyError.Offline, ClaudeRequests.errorOf(AnthropicIoException("Request failed", UnknownHostException("api.anthropic.com"))))
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

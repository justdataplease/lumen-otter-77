package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.ChatTurn
import com.justdataplease.boringtravel.ios.NetException
import com.justdataplease.boringtravel.ios.NetFailure
import com.justdataplease.boringtravel.ios.StreamScript
import com.justdataplease.boringtravel.ios.StreamingNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Claude with the traveller's key on this phone's code (the Android app's ClaudeKeyApiStreamTest and ClaudeRequestsTest,
 * which run the SDK, asked again of ai/own/ClaudeKeyApi.kt on a recorded stream): the draft streams and is read whole,
 * Stop and the planner's cap close the stream and start no retry, a quick overload is retried once, a wrong key never,
 * a cut stream is incomplete, Test's ping is one small structured request, and the errors are said in plain words.
 */
class ClaudeOnDesktopTest {
    private fun event(name: String, data: String) = listOf("event: $name", "data: $data", "")

    private val start = event(
        "message_start",
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1200,"output_tokens":1}}}""",
    ) + event("ping", """{"type":"ping"}""") +
        event("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
        event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"{\"summary\":"}}""")

    private val rest = event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"\"Rome\"}"}}""") +
        event("content_block_stop", """{"type":"content_block_stop","index":0}""") +
        event("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":300}}""") +
        event("message_stop", """{"type":"message_stop"}""")

    private val stream = StreamScript(200, start + rest)
    private val overloaded = StreamScript(529, listOf("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
    private val wrongKey = StreamScript(401, listOf("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
    private val turns = listOf(ChatTurn(true, "Rome in May"))

    private suspend fun ClaudeKeyApi.plan() = draft("sk-ant-test", "claude-sonnet-5-5", "RULES", turns)

    private fun expect(error: OwnKeyError, block: suspend () -> Unit) = runBlocking {
        val e = assertFailsWith<OwnKeyException> { block() }
        assertEquals(error, e.error)
    }

    @Test fun theDraftStreamsAndIsReadWhole() = runBlocking {
        val net = StreamingNet(listOf(stream))
        val reply = ClaudeKeyApi(net, wait = {}).plan()
        assertEquals("{\"summary\":\"Rome\"}", reply.text)
        assertEquals(1200L, reply.inputTokens)
        assertEquals(300L, reply.outputTokens)
        val request = net.asked.single()
        assertEquals("https://api.anthropic.com/v1/messages?beta=true", request.url)
        assertEquals("sk-ant-test", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals("text/event-stream", request.headers["Accept"])
        assertEquals("server-side-fallback-2026-07-01", request.headers["anthropic-beta"])
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("true", body.getValue("stream").jsonPrimitive.content)
        assertEquals("claude-sonnet-5-5", body.getValue("model").jsonPrimitive.content)
        assertEquals(ClaudeKeyApi.DRAFT_TIMEOUT_MS, request.timeoutMs)
        assertEquals(ClaudeKeyApi.DRAFT_READ_TIMEOUT_MS, request.readTimeoutMs)
    }

    @Test fun aStoppedPlanClosesTheStreamAndStartsNoRetry() = runBlocking {
        val net = StreamingNet(listOf(StreamScript(200, start, hang = true)))
        val api = ClaudeKeyApi(net, wait = {})
        val plan = async(Dispatchers.Default) { api.plan() }
        withTimeout(5_000) { while (!net.started) delay(10) }
        plan.cancel()
        plan.join()
        assertTrue(net.closed)
        delay(100)
        assertEquals(1, net.asked.size)
    }

    @Test fun thePlannersCapClosesTheStreamToo() = runBlocking {
        val net = StreamingNet(listOf(StreamScript(200, start, hang = true)))
        assertFailsWith<TimeoutCancellationException> { withTimeout(300) { ClaudeKeyApi(net, wait = {}).plan() } }
        assertTrue(net.closed)
        assertEquals(1, net.asked.size)
    }

    @Test fun aQuickOverloadIsRetriedOnce() = runBlocking {
        val net = StreamingNet(listOf(overloaded, stream))
        assertEquals("{\"summary\":\"Rome\"}", ClaudeKeyApi(net, wait = {}).plan().text)
        assertEquals(2, net.asked.size)
    }

    @Test fun aSecondOverloadIsBusyWithoutAThirdTry() {
        val net = StreamingNet(listOf(overloaded, overloaded, stream))
        expect(OwnKeyError.Busy) { ClaudeKeyApi(net, wait = {}).plan() }
        assertEquals(2, net.asked.size)
    }

    @Test fun aWrongKeyIsNeverRetried() {
        val net = StreamingNet(listOf(wrongKey, stream))
        expect(OwnKeyError.WrongKey) { ClaudeKeyApi(net, wait = {}).plan() }
        assertEquals(1, net.asked.size)
    }

    @Test fun aStreamCutShortIsIncomplete() {
        val net = StreamingNet(listOf(StreamScript(200, start)))
        expect(OwnKeyError.Incomplete) { ClaudeKeyApi(net, wait = {}).plan() }
        assertEquals(1, net.asked.size)
    }

    @Test fun anErrorInTheStreamIsTheErrorItNames() {
        val net = StreamingNet(listOf(StreamScript(200, start + event("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))))
        expect(OwnKeyError.Busy) { ClaudeKeyApi(net, wait = {}).plan() }
        assertEquals(1, net.asked.size)
    }

    @Test fun testsPingIsOneSmallStructuredRequest() = runBlocking {
        val message = """{"id":"msg_2","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"{\"reply\":\"OK\"}"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":6}}"""
        val net = StreamingNet(listOf(StreamScript(200, listOf(message))))
        ClaudeKeyApi(net, wait = {}).ping("sk-ant-test", "claude-sonnet-5-5")
        val request = net.asked.single()
        val body = request.body.orEmpty()
        assertTrue("\"json_schema\"" in body)
        assertFalse("\"stream\":true" in body)
        assertEquals(ClaudeKeyApi.PING_TIMEOUT_MS, request.timeoutMs)
    }

    @Test fun theModelsAreListedPageByPageAndAFamilyBecomesItsNewest() = runBlocking {
        val first = """{"data":[{"id":"claude-sonnet-5-5","type":"model"},{"id":"claude-sonnet-4-6","type":"model"}],"has_more":true,"last_id":"claude-sonnet-4-6"}"""
        val second = """{"data":[{"id":"claude-opus-5-5","type":"model"}],"has_more":false,"last_id":"claude-opus-5-5"}"""
        val net = StreamingNet(listOf(StreamScript(200, listOf(first)), StreamScript(200, listOf(second))))
        val api = ClaudeKeyApi(net, wait = {})
        assertEquals(listOf("claude-sonnet-5-5", "claude-sonnet-4-6", "claude-opus-5-5"), api.models("sk-ant-test"))
        assertEquals("https://api.anthropic.com/v1/models?limit=100", net.asked[0].url)
        assertEquals("https://api.anthropic.com/v1/models?limit=100&after_id=claude-sonnet-4-6", net.asked[1].url)
        assertEquals(ClaudeKeyApi.LIST_TIMEOUT_MS, net.asked[0].timeoutMs)
    }

    @Test fun repliesAndErrorsInPlainWords() {
        fun reply(stop: String?, text: String) = ClaudeMessage(stop, listOf(text), 1200, 300)
        assertEquals("{\"summary\":\"ok\"}", ClaudeRequests.reply(reply("end_turn", "{\"summary\":\"ok\"}")).text)
        assertEquals(OwnKeyError.Blocked, assertFailsWith<OwnKeyException> { ClaudeRequests.reply(reply("refusal", "")) }.error)
        assertEquals(OwnKeyError.Incomplete, assertFailsWith<OwnKeyException> { ClaudeRequests.reply(reply("max_tokens", "{\"summ")) }.error)
        ClaudeRequests.reply(reply("max_tokens", ""), requireText = false)

        fun body(type: String, message: String) = """{"type":"error","error":{"type":"$type","message":"$message"}}"""
        assertEquals(OwnKeyError.WrongKey, ClaudeRequests.errorOf(401, body("authentication_error", "invalid x-api-key")))
        assertEquals(OwnKeyError.ModelUnavailable, ClaudeRequests.errorOf(404, body("not_found_error", "model: claude-x")))
        assertEquals(OwnKeyError.TooMany, ClaudeRequests.errorOf(429, body("rate_limit_error", "slow down")))
        assertEquals(OwnKeyError.Busy, ClaudeRequests.errorOf(529, body("overloaded_error", "Overloaded")))
        assertEquals(OwnKeyError.NoCredit, ClaudeRequests.errorOf(402, body("billing_error", "Your credit balance is too low.")))
        assertEquals(OwnKeyError.Offline, ClaudeRequests.errorOf(NetException("api.anthropic.com")))
        assertEquals(OwnKeyError.Timeout, ClaudeRequests.errorOf(NetException("slow", NetFailure.Timeout)))

        assertTrue(ClaudeRequests.withEffort("claude-opus-6"))
        assertTrue(ClaudeRequests.withFallback("claude-sonnet-6-1"))
        assertFalse(ClaudeRequests.withFallback("claude-haiku-5-5"))
        assertTrue(2 * ClaudeKeyApi.LIST_TIMEOUT_MS + 2 * ClaudeKeyApi.PING_TIMEOUT_MS < 45_000)
        assertTrue(ClaudeKeyApi.DRAFT_TIMEOUT_MS < 190_000)
    }
}

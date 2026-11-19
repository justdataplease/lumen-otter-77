package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [ClaudeKeyApi] through the real SDK against a local server (offline, free): the draft streams and is read whole,
 * a cancelled plan closes the stream (Anthropic stops writing it) and starts no retry, a quick overload is retried
 * once, a wrong key never.
 */
class ClaudeKeyApiStreamTest {
    /** One scripted reply per connection, in order; [Hang] keeps the connection open without finishing. */
    private sealed interface Reply
    private data class Full(val status: Int, val contentType: String, val body: String) : Reply
    private data class Hang(val prefix: String) : Reply

    /** A tiny HTTP/1.1 server on localhost that records each request and when the client closed the connection. */
    private class FakeServer(private val replies: List<Reply>) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${socket.localPort}"
        val requests = CopyOnWriteArrayList<String>()
        val clientClosed = CountDownLatch(1)
        val sentPrefix = CountDownLatch(1)

        init {
            thread(isDaemon = true) {
                var n = 0
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    val reply = replies.getOrElse(n++) { replies.last() }
                    thread(isDaemon = true) { serve(client, reply) }
                }
            }
        }

        private fun serve(client: Socket, reply: Reply) = client.use { s ->
            val input = s.getInputStream()
            requests += readRequest(input)
            val out = s.getOutputStream()
            when (reply) {
                is Full -> {
                    val bytes = reply.body.toByteArray()
                    out.write("HTTP/1.1 ${reply.status} X\r\nContent-Type: ${reply.contentType}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nrequest-id: req_test\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
                is Hang -> {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(reply.prefix.toByteArray())
                    out.flush()
                    sentPrefix.countDown()
                    // Wait for the client to go away (read returns -1 or fails once it closes the stream).
                    try { while (input.read() >= 0) Unit } catch (_: Exception) { }
                    clientClosed.countDown()
                }
            }
        }

        private fun readRequest(input: InputStream): String {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val c = input.read()
                if (c < 0) break
                head.append(c.toChar())
            }
            val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val r = input.read(body, read, length - read)
                if (r < 0) break
                read += r
            }
            return head.toString() + String(body)
        }

        override fun close() = socket.close()
    }

    private var server: FakeServer? = null

    @After fun stop() { server?.close() }

    private fun api(vararg replies: Reply): ClaudeKeyApi {
        val s = FakeServer(replies.toList()).also { server = it }
        return ClaudeKeyApi { key -> ClaudeKeyApi.defaultClient(key, baseUrl = s.url) }
    }

    private fun event(name: String, data: String) = "event: $name\ndata: $data\n\n"

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

    private val stream = Full(200, "text/event-stream", start + rest)
    private val overloaded = Full(529, "application/json", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
    private val wrongKey = Full(401, "application/json", """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")

    private val turns = listOf(ChatTurn(true, "Rome in May"))

    private suspend fun ClaudeKeyApi.plan() = draft("sk-ant-test", "claude-sonnet-5-5", "RULES", turns)

    private fun expectError(expected: OwnKeyError, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected $expected")
        } catch (e: OwnKeyException) {
            assertEquals(expected, e.error)
        }
    }

    @Test fun theDraftStreamsAndIsReadWhole() = runBlocking {
        val reply = api(stream).plan()
        assertEquals("{\"summary\":\"Rome\"}", reply.text)
        assertEquals(1200L, reply.inputTokens)
        assertEquals(300L, reply.outputTokens)
        val request = server!!.requests.single()
        assertTrue(request.startsWith("POST /v1/messages"))
        assertTrue(request.contains("\"stream\":true"))
        assertTrue(request.contains("\"model\":\"claude-sonnet-5-5\""))
        assertTrue(request, request.contains("sk-ant-test"))
    }

    @Test fun aCancelledPlanClosesTheStreamAndStartsNoRetry() = runBlocking {
        val api = api(Hang(start))
        val plan = async(Dispatchers.IO) { api.plan() }
        assertTrue("the server sent the start", server!!.sentPrefix.await(10, TimeUnit.SECONDS))
        delay(200)
        plan.cancel()
        // The connection closes at once, not at the 180 s timeout.
        assertTrue("stream closed after cancel", server!!.clientClosed.await(3, TimeUnit.SECONDS))
        delay(1500)
        assertEquals(1, server!!.requests.size)
    }

    @Test fun thePlannersCapClosesTheStreamToo() = runBlocking {
        val api = api(Hang(start))
        try {
            withTimeout(1500) { api.plan() }
            fail("expected the cap")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        }
        assertTrue(server!!.clientClosed.await(3, TimeUnit.SECONDS))
        assertEquals(1, server!!.requests.size)
    }

    @Test fun aQuickOverloadIsRetriedOnce() = runBlocking {
        val reply = api(overloaded, stream).plan()
        assertEquals("{\"summary\":\"Rome\"}", reply.text)
        assertEquals(2, server!!.requests.size)
    }

    @Test fun aSecondOverloadIsBusyWithoutAThirdTry() {
        val api = api(overloaded, overloaded, stream)
        expectError(OwnKeyError.Busy) { api.plan() }
        assertEquals(2, server!!.requests.size)
    }

    @Test fun aWrongKeyIsNeverRetried() {
        val api = api(wrongKey, stream)
        expectError(OwnKeyError.WrongKey) { api.plan() }
        assertEquals(1, server!!.requests.size)
    }

    @Test fun aStreamCutShortIsIncomplete() {
        val api = api(Full(200, "text/event-stream", start))
        expectError(OwnKeyError.Incomplete) { api.plan() }
        assertEquals(1, server!!.requests.size)
    }

    @Test fun testsPingIsOneSmallStructuredRequest() = runBlocking {
        val message = """{"id":"msg_2","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"{\"reply\":\"OK\"}"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":6}}"""
        val api = api(Full(200, "application/json", message))
        api.ping("sk-ant-test", "claude-sonnet-5-5")
        val request = server!!.requests.single()
        assertTrue(request.contains("\"json_schema\""))
        assertTrue(!request.contains("\"stream\":true"))
        assertNotNull(request)
    }
}

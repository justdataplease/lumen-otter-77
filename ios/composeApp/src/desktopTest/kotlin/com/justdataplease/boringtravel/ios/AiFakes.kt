package com.justdataplease.boringtravel.ios

import com.justdataplease.boringtravel.ai.GeminiPlanner
import com.justdataplease.boringtravel.ai.own.KeyValues
import com.justdataplease.boringtravel.ai.own.MemoryKeyValues
import com.justdataplease.boringtravel.engine.MemoryTripFile
import com.justdataplease.boringtravel.engine.TripBook
import com.justdataplease.boringtravel.firebase.FirebaseSetup
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * The AI planner's network for the desktop tests, on recorded answers only: the model's turns as Gemini's REST answers
 * (functionCall parts, as the Android app's GeminiRestTest and AgentToolsTest feed them), everything else (Photon,
 * Google Maps links and lists, pages) from [Recorded]. Nothing here reaches the network, and no key is real.
 */

/** Firebase ids for a test build: complete, made up. */
val TEST_FIREBASE = FirebaseSetup.Config("boringtravel-test", "1:000000000000:ios:0000000000000000", "test-api-key-not-real")

/**
 * A network whose POSTs to a model ([isModel]) are answered in turn from [answer]ed replies, each request kept in
 * [asked]; every other request goes to [other] (the recorded web).
 */
class ModelNet(
    private val other: RecordedNet = Recorded.net(),
    private val isModel: (String) -> Boolean = { it.startsWith(GeminiPlanner.BASE) },
) : Net {
    val asked = mutableListOf<NetRequest>()
    private val replies = ArrayDeque<(NetRequest) -> NetAnswer>()

    /** Every request that went to the recorded web. */
    val web: List<NetRequest> get() = other.asked

    fun answer(json: String, code: Int = 200): ModelNet = apply {
        replies += { r -> NetAnswer(code, r.url, mapOf("Content-Type" to "application/json; charset=UTF-8"), json.encodeToByteArray()) }
    }

    /** The body of the [n]th model request, as JSON. */
    fun body(n: Int): JsonObject = Json.parseToJsonElement(requireNotNull(asked[n].body)).jsonObject

    override suspend fun get(request: NetRequest): NetAnswer = if (isModel(request.url)) take(request) else other.get(request)

    override suspend fun post(request: NetRequest): NetAnswer = if (isModel(request.url)) take(request) else other.post(request)

    private fun take(request: NetRequest): NetAnswer {
        asked += request
        val next = replies.removeFirstOrNull() ?: error("No recorded answer left for ${request.url}")
        return next(request)
    }
}

/** Gemini's answers as its REST API writes them. */
object GeminiAnswers {
    /** One model turn calling [calls] (tool name to its arguments as JSON), with optional words. */
    fun turn(vararg calls: Pair<String, String>, text: String? = null): String {
        val parts = listOfNotNull(text?.let { """{"text":${JsonPrimitive(it)}}""" }) +
            calls.map { (name, args) -> """{"functionCall":{"name":"$name","args":$args},"thoughtSignature":"sig-$name"}""" }
        return """{"candidates":[{"content":{"role":"model","parts":[${parts.joinToString(",")}]},"finishReason":"STOP","index":0}],""" +
            """"usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":300}}"""
    }

    /** A plain JSON answer (pictures read, Copy from a link's sorting): its text. */
    fun text(text: String): String =
        """{"candidates":[{"content":{"role":"model","parts":[{"text":${JsonPrimitive(text)}}]},"finishReason":"STOP"}]}"""

    /** Google's refusal: its code, message and status. */
    fun error(code: Int, message: String, status: String): String =
        """{"error":{"code":$code,"message":${JsonPrimitive(message)},"status":"$status"}}"""

    val BUSY = error(503, "The model is overloaded. Please try again later.", "UNAVAILABLE")
    val QUOTA = error(429, "You exceeded your current quota, please check your plan and billing details.", "RESOURCE_EXHAUSTED")
}

/** The AI kit on [net] (recorded), a fresh trip store and settings, Me › AI's keys in [keys], this test build's ids. */
fun aiKit(
    net: Net,
    book: TripBook = TripBook(MemoryTripFile()),
    settings: AppSettings = AppSettings.ready(),
    keys: KeyValues = MemoryKeyValues(),
    files: MediaFiles = MemoryMediaFiles(),
    firebase: FirebaseSetup.Config = TEST_FIREBASE,
): PlannerKit = PlannerKit(book, settings, Recorded.web(net), files, PlannerPhone(keys, firebase = firebase), version = "1.0")

/** A streamed answer for the tests: whole lines, then the end, or [hang]: no end, until the reader stops. */
class StreamScript(val code: Int, val lines: List<String>, val hang: Boolean = false, val headers: Map<String, String> = emptyMap())

/**
 * A network that streams [scripts] in turn (one per request, the last again when they run out), keeping each request
 * and whether the reader closed a stream that never ended ([closed]).
 */
class StreamingNet(private val scripts: List<StreamScript>) : Net {
    val asked = mutableListOf<NetRequest>()
    var closed = false
        private set
    var started = false
        private set

    override suspend fun get(request: NetRequest): NetAnswer = answer(request)

    override suspend fun post(request: NetRequest): NetAnswer = answer(request)

    private fun answer(request: NetRequest): NetAnswer {
        val s = script(request)
        return NetAnswer(s.code, request.url, s.headers + ("Content-Type" to "application/json"), s.lines.joinToString("\n").encodeToByteArray())
    }

    private fun script(request: NetRequest): StreamScript {
        asked += request
        return scripts.getOrElse(asked.size - 1) { scripts.last() }
    }

    override suspend fun <T> stream(request: NetRequest, read: suspend (NetLines) -> T): T {
        val s = script(request)
        val queue = ArrayDeque(s.lines)
        val lines = NetLines(s.code, request.url, s.headers) {
            queue.removeFirstOrNull() ?: if (s.hang) {
                started = true
                awaitCancellation()
            } else null
        }
        try {
            return read(lines)
        } finally {
            if (s.hang) closed = true
        }
    }
}

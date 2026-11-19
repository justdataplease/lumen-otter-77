package com.justdataplease.boringtravel.ai.own

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The key check of every provider against the real services with made-up keys (free: each is refused before any
 * model runs). Run by hand only, with `BT_LIVE_WRONG_KEYS=1`; skipped in normal test runs, which stay offline.
 */
class WrongKeyLiveTest {
    private val http = OkHttpClient()

    private fun check(api: OwnKeyApi, key: String) = runBlocking {
        assumeTrue(System.getenv("BT_LIVE_WRONG_KEYS") == "1")
        val error = try {
            api.models(key)
            null
        } catch (e: OwnKeyException) {
            println("LIVE wrong key ${api.provider.id}: ${e.error} status=${e.status} → \"${e.error.message(api.provider)}\"")
            e.error
        }
        assertEquals(OwnKeyError.WrongKey, error)
    }

    @Test fun gemini() = check(GeminiKeyApi(http), "AIzaSyWRONGKEYfortest000000000000000000")
    @Test fun claude() = check(ClaudeKeyApi(), "sk-ant-api03-WRONGKEYfortest0000000000")
    @Test fun chatGpt() = check(OpenAiKeyApi(http), "sk-proj-WRONGKEYfortest0000000000")
    @Test fun openRouter() = check(OpenRouterKeyApi(http), "sk-or-v1-WRONGKEYfortest0000000000")
}

package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.GeminiPlanner
import com.justdataplease.boringtravel.ai.PlannerError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Which models Me › AI offers from a provider's list, and the own-key errors in plain words. */
class OwnAiModelsTest {
    @Test fun claudeOffersFamiliesThatFollowTheNewestModel() {
        val listed = listOf("claude-fable-5-2", "claude-haiku-5-5", "claude-opus-5-5", "claude-sonnet-5-5", "claude-opus-4-8", "claude-sonnet-4-5-20250929")
        val offered = OwnAiModels.offered(OwnAiProvider.Claude, listed)
        assertEquals(listOf("claude-sonnet-latest", "claude-opus-latest", "claude-haiku-latest"), offered)
        assertEquals("claude-sonnet-latest", OwnAiModels.choose(offered, null))
        // Each family name becomes its newest model; a dated snapshot counts as its version.
        assertEquals("claude-sonnet-5-5", OwnAiModels.resolve(OwnAiProvider.Claude, "claude-sonnet-latest", listed))
        assertEquals("claude-opus-5-5", OwnAiModels.resolve(OwnAiProvider.Claude, "claude-opus-latest", listed))
        assertEquals("claude-sonnet-6", OwnAiModels.resolve(OwnAiProvider.Claude, "claude-sonnet-latest", listed + "claude-sonnet-6"))
        // A typed id is called as typed.
        assertEquals("claude-opus-4-8", OwnAiModels.resolve(OwnAiProvider.Claude, "claude-opus-4-8", listed))
    }

    @Test fun geminiOffersGooglesLatestAliasesOnly() {
        val listed = listOf("gemini-3.8-flash", "gemini-embedding-002", "gemini-flash-latest", "gemini-pro-latest", "gemini-flash-lite-latest", "imagen-5", "gemini-3.8-flash-tts", "antigravity-preview-latest")
        val offered = OwnAiModels.offered(OwnAiProvider.Gemini, listed)
        assertEquals(listOf(GeminiPlanner.DEFAULT_MODEL, "gemini-pro-latest", "gemini-flash-lite-latest"), offered)
        assertEquals("gemini-flash-latest", GeminiPlanner.DEFAULT_MODEL)
        assertEquals(GeminiPlanner.DEFAULT_MODEL, OwnAiModels.choose(offered, null))
        // Google resolves its aliases itself.
        assertEquals("gemini-flash-latest", OwnAiModels.resolve(OwnAiProvider.Gemini, "gemini-flash-latest", emptyList()))
        // Even a key that lists none gets the aliases.
        assertEquals(OwnAiModels.GEMINI, OwnAiModels.offered(OwnAiProvider.Gemini, emptyList()))
    }

    @Test fun openRouterOffersItsLatestAliasesWithDeepSeekFlashFirst() {
        val listed = listOf("google/gemini-3.8-flash", "~google/gemini-flash-latest", "~deepseek/deepseek-flash-latest", "~x-ai/grok-latest", "deepseek/deepseek-v4.1-flash", "~unbiased/pareto-latest")
        val offered = OwnAiModels.offered(OwnAiProvider.OpenRouter, listed)
        assertEquals(listOf("~deepseek/deepseek-flash-latest", "~google/gemini-flash-latest", "~x-ai/grok-latest"), offered)
        assertEquals("~deepseek/deepseek-flash-latest", OwnAiModels.choose(offered, null))
        assertEquals("~deepseek/deepseek-flash-latest", OwnAiModels.fallback(OwnAiProvider.OpenRouter))
    }

    @Test fun pickerShowsAShortListOrTheSearchAndAlwaysTheChoice() {
        val offered = (1..300).map { "vendor/model-$it" }
        assertEquals(OwnAiModels.SHORT_LIST, OwnAiModels.shown(offered, null, "").size)
        assertEquals(listOf("vendor/model-299") + offered.take(OwnAiModels.SHORT_LIST), OwnAiModels.shown(offered, "vendor/model-299", ""))
        assertEquals(listOf("vendor/model-29", "vendor/model-290", "vendor/model-291"), OwnAiModels.shown(offered, null, "MODEL-29").take(3))
        assertEquals(listOf("my/own-model"), OwnAiModels.shown(offered, "my/own-model", "own"))
        assertEquals(emptyList<String>(), OwnAiModels.shown(offered, null, "nothing-like-this"))
    }

    @Test fun openAiOffersItsFamiliesWithSolFirst() {
        val listed = listOf(
            "gpt-6-luna", "gpt-6.1-terra", "gpt-6-astra", "gpt-6.1-sol", "gpt-6-sol", "gpt-6.1-sol-pro", "gpt-image-2.6", "gpt-realtime-2.1",
            "text-embedding-4", "dall-e-3", "gpt-4o", "o4-mini", "ft:gpt-4o:me:x", "gpt-6-cyber", "whisper-1",
        )
        val offered = OwnAiModels.offered(OwnAiProvider.ChatGpt, listed)
        assertEquals(listOf("gpt-sol-latest", "gpt-astra-latest", "gpt-terra-latest", "gpt-luna-latest"), offered)
        assertEquals("gpt-6.1-sol", OwnAiModels.resolve(OwnAiProvider.ChatGpt, "gpt-sol-latest", listed))
        assertEquals("gpt-7-sol", OwnAiModels.resolve(OwnAiProvider.ChatGpt, "gpt-sol-latest", listed + "gpt-7-sol"))
        assertEquals("gpt-sol-latest", OwnAiModels.choose(offered, null))
    }

    @Test fun modelsSavedBeforeTheLatestNamesMoveToTheirFamily() {
        assertEquals("gemini-flash-lite-latest", OwnAiModels.upgrade(OwnAiProvider.Gemini, "gemini-3.5-flash-lite"))
        assertEquals("gemini-flash-latest", OwnAiModels.upgrade(OwnAiProvider.Gemini, "gemini-3.8-flash"))
        assertEquals("gemini-pro-latest", OwnAiModels.upgrade(OwnAiProvider.Gemini, "gemini-3.1-pro-preview"))
        assertEquals("claude-sonnet-latest", OwnAiModels.upgrade(OwnAiProvider.Claude, "claude-sonnet-5-5"))
        assertEquals("gpt-sol-latest", OwnAiModels.upgrade(OwnAiProvider.ChatGpt, "gpt-6.1-sol"))
        assertEquals("~deepseek/deepseek-flash-latest", OwnAiModels.upgrade(OwnAiProvider.OpenRouter, "deepseek/deepseek-v4.1-flash"))
        assertEquals("~anthropic/claude-sonnet-latest", OwnAiModels.upgrade(OwnAiProvider.OpenRouter, "anthropic/claude-sonnet-5.5"))
        // Typed ids and names already "latest" stay.
        assertEquals("my-lab/hand-typed-model", OwnAiModels.upgrade(OwnAiProvider.OpenRouter, "my-lab/hand-typed-model"))
        assertEquals("gemini-pro-latest", OwnAiModels.upgrade(OwnAiProvider.Gemini, "gemini-pro-latest"))
    }

    @Test fun nothingOfferedNothingChosen() {
        assertNull(OwnAiModels.choose(emptyList(), null))
        assertEquals("my/typed-model", OwnAiModels.choose(emptyList(), "my/typed-model"))
        assertEquals(emptyList<String>(), OwnAiModels.offered(OwnAiProvider.Claude, listOf("text-bison", "aqa")))
    }

    @Test fun defaultsBeforeATest() {
        assertEquals(GeminiPlanner.DEFAULT_MODEL, OwnAiModels.fallback(OwnAiProvider.Gemini))
        assertEquals("claude-sonnet-latest", OwnAiModels.fallback(OwnAiProvider.Claude))
        assertEquals("gpt-sol-latest", OwnAiModels.fallback(OwnAiProvider.ChatGpt))
        assertEquals("Wrong key. Copy it again from OpenRouter.", OwnKeyError.WrongKey.message(OwnAiProvider.OpenRouter))
    }

    @Test fun httpErrors() {
        assertEquals(OwnKeyError.WrongKey, OwnKeyError.ofHttp(401))
        assertEquals(OwnKeyError.NoCredit, OwnKeyError.ofHttp(402))
        assertEquals(OwnKeyError.NoCredit, OwnKeyError.ofHttp(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API."))
        assertEquals(OwnKeyError.ModelUnavailable, OwnKeyError.ofHttp(404))
        assertEquals(OwnKeyError.ModelUnavailable, OwnKeyError.ofHttp(403, "permission_error", "Your key can't use this model."))
        assertEquals(OwnKeyError.WrongKey, OwnKeyError.ofHttp(403))
        assertEquals(OwnKeyError.TooMany, OwnKeyError.ofHttp(429))
        assertEquals(OwnKeyError.Busy, OwnKeyError.ofHttp(529))
        assertEquals(OwnKeyError.Timeout, OwnKeyError.ofHttp(408))
        assertEquals(OwnKeyError.BadRequest, OwnKeyError.ofHttp(413))
        assertEquals(OwnKeyError.Unknown, OwnKeyError.ofHttp(302))
    }

    @Test fun networkErrors() {
        assertEquals(OwnKeyError.Offline, OwnKeyError.ofNetwork(UnknownHostException("api.openai.com")))
        assertEquals(OwnKeyError.Timeout, OwnKeyError.ofNetwork(IOException("wrapped", SocketTimeoutException("read timed out"))))
        assertEquals(OwnKeyError.Offline, OwnKeyError.ofNetwork(IOException("stream was reset")))
        assertEquals(OwnKeyError.Unknown, OwnKeyError.ofNetwork(IllegalStateException("x")))
        // What the emulator met on 2026-11-11: the host's antivirus opened HTTPS to api.openai.com with its own certificate.
        val untrusted = javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found.")
            .apply { initCause(java.security.cert.CertificateException(java.security.cert.CertPathValidatorException("not trusted"))) }
        assertEquals(OwnKeyError.Insecure, OwnKeyError.ofNetwork(untrusted))
        assertEquals("Couldn't connect securely to ChatGPT. Try another network.", OwnKeyError.Insecure.message(OwnAiProvider.ChatGpt))
    }

    @Test fun onlyTheSetUpErrorsPointToTheOwnKey() {
        // A build without the planner, and the testers' debug-build App Check message.
        listOf(PlannerError.AppCheckDebug, PlannerError.NotSetUp).forEach {
            assertTrue(it.message, it.message.endsWith("use your own key in Me › AI.", ignoreCase = true))
        }
        // Ordinary users aren't sent to API keys.
        PlannerError.entries.filter { it != PlannerError.AppCheckDebug && it != PlannerError.NotSetUp }.forEach {
            assertTrue(it.message, "key" !in it.message.lowercase())
        }
        assertEquals("Google couldn't confirm this copy of the app. Install BoringTravel from Google Play and try again.", PlannerError.AppCheck.message)
    }

    @Test fun meAiDoesNotSendTravellersToMeAi() {
        assertEquals("This model isn't available with your key. Pick another one in Me › AI.", OwnKeyError.ModelUnavailable.message(OwnAiProvider.Claude))
        assertEquals("This model isn't available with your key. Pick another one.", OwnKeyError.ModelUnavailable.message(OwnAiProvider.Claude, inAiSettings = true))
        assertEquals("This model can't plan trips here. Pick another one.", OwnKeyError.BadRequest.message(OwnAiProvider.ChatGpt, inAiSettings = true))
        OwnKeyError.entries.forEach { e ->
            OwnAiProvider.entries.forEach { p -> assertTrue(e.message(p, inAiSettings = true), "Me › AI" !in e.message(p, inAiSettings = true)) }
        }
    }

    @Test fun messagesArePlainAndNameTheProvider() {
        assertEquals("Wrong key. Copy it again from the Claude Console.", OwnKeyError.WrongKey.message(OwnAiProvider.Claude))
        assertEquals("No credit left on this key. Add some on the OpenAI platform.", OwnKeyError.NoCredit.message(OwnAiProvider.ChatGpt))
        assertEquals("Gemini is busy right now. Try again in a few minutes.", OwnKeyError.Busy.message(OwnAiProvider.Gemini))
        OwnKeyError.entries.forEach { e ->
            OwnAiProvider.entries.forEach { p ->
                listOf(e.message(p), e.message(p, inAiSettings = true)).forEach { m ->
                    assertTrue(m, m.length <= 120)
                    assertTrue(m, !m.contains("!"))
                }
            }
        }
    }
}

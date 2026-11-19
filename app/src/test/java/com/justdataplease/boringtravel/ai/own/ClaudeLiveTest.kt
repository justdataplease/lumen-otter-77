package com.justdataplease.boringtravel.ai.own

import com.justdataplease.boringtravel.ai.PlanRequest
import com.justdataplease.boringtravel.ai.tools.PendingPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/**
 * One real Claude request through [ClaudeKeyApi] (every planner tool in one answer), run by hand only:
 * `BT_LIVE_CLAUDE=1` and `ANTHROPIC_API_KEY` set. Skipped in every normal test run (it costs a few cents). Prints the
 * model and what it planned, never the key.
 */
class ClaudeLiveTest {
    @Test fun oneRealPlan() = runBlocking {
        assumeTrue(System.getenv("BT_LIVE_CLAUDE") == "1")
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        assumeTrue(key.isNotBlank())
        val api = ClaudeKeyApi()
        val offered = OwnAiModels.offered(OwnAiProvider.Claude, api.models(key))
        println("LIVE Claude models offered: $offered")
        val family = OwnAiModels.choose(offered, null) ?: error("no model")
        val planner = OwnKeyPlanner(api, key, family)
        val notes = "3 days in Lisbon, 12 to 14 May 2027. Day 1 Alfama and tram 28. Day 2 Belém and pastéis. Day 3 Sintra by train."
        val started = System.nanoTime()
        val result = planner.plan(PlanRequest(null, emptyList(), PendingPlan(), emptyList(), notes, LocalDate.of(2026, 11, 11)))
        val seconds = (System.nanoTime() - started) / 1e9
        println("LIVE Claude ${planner.reportName} seconds=${"%.1f".format(seconds)} calls=${result.calls.map { it.name }}")
        println("LIVE Claude trip=${result.pending.trip} items=${result.pending.added.size} reply=${result.reply}")
        assertTrue(result.pending.added.isNotEmpty() || result.pending.questions.isNotEmpty())
    }
}

package com.justdataplease.boringtravel.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google's busy and deadline answers (seen on 11 Nov 2026 through Firebase AI Logic) in plain words, and retried. */
class PlannerErrorRetryTest {
    private class ServerException(message: String) : Exception(message)

    @Test fun busyAndDeadlineAnswersAreRetriedAndSaidPlainly() {
        val busy = ServerException("This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.")
        val deadline = ServerException("Deadline expired before operation could complete.")
        assertTrue(PlannerError.isTransient(busy))
        assertTrue(PlannerError.isTransient(deadline))
        assertTrue(PlannerError.isTransient(Exception("HTTP 503")))
        assertEquals(PlannerError.Quota, PlannerError.of(busy, debugBuild = false))
        assertEquals(PlannerError.Timeout, PlannerError.of(deadline, debugBuild = false))
        assertFalse(PlannerError.isTransient(Exception("API key not valid")))
        assertFalse(PlannerError.isTransient(Exception("Request contains an invalid argument. 400")))
        val quota = ServerException("You exceeded your current quota. Quota exceeded for metric: generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash")
        assertTrue(PlannerError.isQuota(quota))
        assertTrue(PlannerError.isQuota(Exception("HTTP 429 Too Many Requests")))
        assertFalse(PlannerError.isQuota(busy))
        assertEquals(PlannerError.Quota, PlannerError.of(quota, debugBuild = false))
    }
}

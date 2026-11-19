package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.ui.ai.AiReport
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReportTest {
    @Test fun bodyQuotesTheAnswerWithVersionAndModel() {
        val body = AiReport.body("  {\"title\":\"Kyoto\"}  ", model = "gemini-x", version = "1.0.0")
        assertTrue(body.startsWith("What's wrong with this answer?"))
        assertTrue(body.contains("BoringTravel 1.0.0 · model gemini-x"))
        assertTrue(body.endsWith("{\"title\":\"Kyoto\"}"))
    }

    @Test fun longAnswersAreCut() {
        val body = AiReport.body("x".repeat(AiReport.MAX_ANSWER + 500), model = "m", version = "v")
        assertTrue(body.endsWith("[…cut]"))
        assertTrue(body.length < AiReport.MAX_ANSWER + 300)
    }
}

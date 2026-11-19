package com.justdataplease.boringtravel.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationParserTest {
    @Test fun questionsAreBoundedDistinctTextAndOlderDraftsStillParse() {
        val draft = TripDraftParser.parse("""{"summary":"A few details first.","questions":["Which year?",null,23,"",{},"Which year?","Which Paris?","Which flight?","Fourth question?"]}""")
        assertEquals(listOf("Which year?", "Which Paris?", "Which flight?"), draft.questions)
        assertEquals(0, draft.itemCount)
        assertTrue(TripDraftParser.parse("""{"summary":"Ready","days":[],"ideas":[]}""").questions.isEmpty())
    }
}

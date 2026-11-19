package com.justdataplease.boringtravel.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftLimitsTest {
    private fun items(count: Int) = (1..count).joinToString(",") { """{"title":"Item $it"}""" }

    @Test fun anExtraDayRejectsTheWholeDraftInsteadOfLosingTheLastDay() {
        val days = (1..TripDraftParser.MAX_DAYS + 1).joinToString(",") { """{"dayIndex":$it,"items":[{"title":"Day $it"}]}""" }
        assertTrue(runCatching { TripDraftParser.parse("""{"days":[$days]}""") }.exceptionOrNull() is DraftLimitException)
    }

    @Test fun extraDailyItemsIdeasAndUpdatesAreRejectedWithoutPartialChanges() {
        val tooManyItems = """{"days":[{"dayIndex":1,"items":[${items(TripDraftParser.MAX_ITEMS_PER_DAY + 1)}]}]}"""
        val tooManyIdeas = """{"ideas":[${items(TripDraftParser.MAX_IDEAS + 1)}]}"""
        val updates = (1..TripDraftParser.MAX_UPDATES + 1).joinToString(",") { """{"itemId":"id$it","time":"14:00"}""" }
        for (input in listOf(tooManyItems, tooManyIdeas, """{"updates":[$updates]}""")) {
            assertTrue(runCatching { TripDraftParser.parse(input) }.exceptionOrNull() is DraftLimitException)
        }
    }

    @Test fun permittedBoundaryKeepsEveryItem() {
        val draft = TripDraftParser.parse("""{"days":[{"date":"2027-06-01","items":[${items(TripDraftParser.MAX_ITEMS_PER_DAY)}]}],"ideas":[${items(TripDraftParser.MAX_IDEAS)}]}""")
        assertEquals(TripDraftParser.MAX_ITEMS_PER_DAY, draft.days.single().items.size)
        assertEquals(TripDraftParser.MAX_IDEAS, draft.ideas.size)
    }

    @Test fun genericProviderValidationAndBusyErrorsHaveUsefulCategories() {
        assertEquals(PlannerError.InvalidRequest, PlannerError.of(Exception("Request contains an invalid argument."), true))
        assertEquals(PlannerError.Quota, PlannerError.of(Exception("HTTP 503"), true))
    }
}

package com.justdataplease.boringtravel.ai

import com.justdataplease.boringtravel.ai.PlannerAllowance.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class PlannerAllowanceTest {
    @Test fun proHasNoCount() = assertEquals(Check.Allowed(null), PlannerAllowance.check(isPro = true, freeUsed = 99, answersToday = 0, startsPlan = true))

    @Test fun twoFreePlans() {
        assertEquals(Check.Allowed(2), PlannerAllowance.check(false, freeUsed = 0, answersToday = 0, startsPlan = true))
        assertEquals(Check.Allowed(1), PlannerAllowance.check(false, freeUsed = 1, answersToday = 0, startsPlan = true))
        assertEquals(Check.NeedsPro, PlannerAllowance.check(false, freeUsed = 2, answersToday = 0, startsPlan = true))
    }

    @Test fun aStartedChatCanGoOn() =
        assertEquals(Check.Allowed(0), PlannerAllowance.check(false, freeUsed = 2, answersToday = 3, startsPlan = false))

    @Test fun dailyCapForEveryone() {
        assertEquals(Check.DailyLimit, PlannerAllowance.check(true, 0, PlannerAllowance.DAILY_ANSWERS, startsPlan = false))
        assertEquals(Check.DailyLimit, PlannerAllowance.check(false, 0, PlannerAllowance.DAILY_ANSWERS, startsPlan = true))
    }

    @Test fun freeLeftNeverNegative() {
        assertEquals(PlannerAllowance.FREE_PLANS, PlannerAllowance.freeLeft(0))
        assertEquals(0, PlannerAllowance.freeLeft(7))
    }
}

// Stand-ins named like the Firebase AI Logic exceptions (the mapping goes by class name).
private class QuotaExceededException(message: String) : RuntimeException(message)
private class PromptBlockedException(message: String) : RuntimeException(message)
private class ServerException(message: String) : RuntimeException(message)
private class UnknownException(message: String, cause: Throwable?) : RuntimeException(message, cause)
private class UnsupportedUserLocationException : RuntimeException("")
private class SerializationException(message: String) : RuntimeException(message)
private class RequestTimeoutException(message: String) : RuntimeException(message)
private class ServiceDisabledException(message: String) : RuntimeException(message)

class PlannerErrorTest {
    private fun of(e: Throwable, debug: Boolean = false) = PlannerError.of(e, debug)

    @Test fun offline() {
        assertEquals(PlannerError.Offline, of(UnknownException("Something went wrong", UnknownHostException("Unable to resolve host \"firebasevertexai.googleapis.com\""))))
        assertEquals(PlannerError.Offline, of(IOException("closed")))
    }

    @Test fun quota() {
        assertEquals(PlannerError.Quota, of(QuotaExceededException("Quota exceeded")))
        assertEquals(PlannerError.Quota, of(ServerException("HTTP 429 RESOURCE_EXHAUSTED")))
    }

    @Test fun appCheck() {
        val e = ServerException("Firebase App Check token is invalid. PERMISSION_DENIED")
        assertEquals(PlannerError.AppCheck, of(e))
        assertEquals(PlannerError.AppCheckDebug, of(e, debug = true))
        assertEquals(PlannerError.AppCheck, of(ServerException("Error 403")))
    }

    @Test fun others() {
        assertEquals(PlannerError.NotSetUp, of(PlannerNotSetUpException()))
        assertEquals(PlannerError.NotSetUp, of(ServiceDisabledException("The Firebase AI Logic API is not enabled")))
        assertEquals(PlannerError.Blocked, of(PromptBlockedException("blocked")))
        assertEquals(PlannerError.Region, of(UnsupportedUserLocationException()))
        assertEquals(PlannerError.Unreadable, of(DraftParseException("no json")))
        assertEquals(PlannerError.Unreadable, of(SerializationException("bad")))
        assertEquals(PlannerError.Timeout, of(RequestTimeoutException("slow")))
        assertEquals(PlannerError.Unknown, of(IllegalStateException("odd 4031 thing")))
    }

    @Test fun messagesArePlain() {
        PlannerError.entries.forEach { e ->
            assertFalse("${e.name} has an exclamation mark", e.message.contains("!"))
            assertTrue("${e.name} is long", e.message.length < 140)
        }
    }
}

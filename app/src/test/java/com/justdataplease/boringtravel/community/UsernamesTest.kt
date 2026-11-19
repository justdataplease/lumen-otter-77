package com.justdataplease.boringtravel.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernamesTest {
    @Test fun goodNamesPass() {
        for (n in listOf("alexa", "maria_p", "a.b.c", "abc", "x".repeat(20), "trip2027", "_under_", "9lives")) {
            assertNull(n, Usernames.problem(n))
        }
    }

    @Test fun eachRuleHasItsMessage() {
        assertEquals(Usernames.Problem.Short, Usernames.problem("ab"))
        assertEquals(Usernames.Problem.Long, Usernames.problem("x".repeat(21)))
        assertEquals(Usernames.Problem.Characters, Usernames.problem("Alexa"))
        assertEquals(Usernames.Problem.Characters, Usernames.problem("ja son"))
        assertEquals(Usernames.Problem.Characters, Usernames.problem("alexa!"))
        assertEquals(Usernames.Problem.Characters, Usernames.problem("μαρία"))
        assertEquals(Usernames.Problem.Characters, Usernames.problem("ja-son"))
        assertEquals(Usernames.Problem.Dot, Usernames.problem(".alexa"))
        assertEquals(Usernames.Problem.Dot, Usernames.problem("alexa."))
        assertEquals(Usernames.Problem.Dots, Usernames.problem("ja..son"))
        assertEquals(Usernames.Problem.Reserved, Usernames.problem("admin"))
        assertEquals(Usernames.Problem.Reserved, Usernames.problem("boringtravel"))
        assertEquals(Usernames.Problem.Reserved, Usernames.problem("support"))
    }

    @Test fun typingIsNormalized() {
        assertEquals("alexa", Usernames.normalize("  @Alexa "))
        assertEquals("maria.p", Usernames.normalize("Maria.P"))
    }

    @Test fun suggestionsAreValid() {
        assertEquals("maria.papadopoulou", Usernames.suggest("Maria Papadopoulou"))
        assertEquals("jose.garcia", Usernames.suggest("José García"))
        assertEquals("alexa.doe", Usernames.suggest("alexa.doe@gmail.com"))
        assertEquals("al0", Usernames.suggest("Al"))
        assertNull(Usernames.suggest("Μαρία"))
        assertNull(Usernames.suggest(null))
        assertNull(Usernames.suggest("   "))
        val long = Usernames.suggest("Bartholomew Montgomery-Fitzwilliam")!!
        assertTrue(long, Usernames.isValid(long))
        assertTrue(long.length <= Usernames.MAX)
        // Reserved names get a suffix instead.
        assertEquals("admin_tr", Usernames.suggest("Admin"))
    }

    @Test fun theRulesFileUsesTheSameReservedNames() {
        val rules = java.io.File("../firebase/firestore.rules").takeIf { it.isFile } ?: java.io.File("firebase/firestore.rules")
        if (!rules.isFile) return
        val text = rules.readText()
        for (n in Usernames.RESERVED) assertTrue("rules miss \"$n\"", text.contains("'$n'"))
    }
}

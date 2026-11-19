package com.justdataplease.boringtravel.ui.together

import org.junit.Assert.assertEquals
import org.junit.Test

class TogetherTextTest {
    @Test fun whoJoinedIsSaidByName() {
        assertEquals("Maria joined Spain 2027", TogetherText.joined(listOf("Maria"), "Spain 2027"))
        assertEquals("Maria and Nikos joined Spain 2027", TogetherText.joined(listOf("Maria", "Nikos"), " Spain 2027 "))
        assertEquals("Maria and 2 more joined Spain 2027", TogetherText.joined(listOf("Maria", "Nikos", "Eleni"), "Spain 2027"))
    }

    @Test fun withoutANameOrATitleItStillReads() {
        assertEquals("A friend joined your trip", TogetherText.joined(listOf(" "), ""))
        assertEquals("2 friends joined Crete", TogetherText.joined(listOf("", ""), "Crete"))
        assertEquals("Maria joined Crete", TogetherText.joined(listOf("", "Maria"), "Crete"))
    }
}

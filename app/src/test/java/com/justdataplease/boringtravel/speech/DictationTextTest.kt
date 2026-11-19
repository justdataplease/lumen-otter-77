package com.justdataplease.boringtravel.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DictationTextTest {
    private fun format(phrase: String, before: String, after: String = "", paragraphStart: Boolean = false) =
        DictationText.format(phrase, before, after, paragraphStart)

    @Test fun firstWordsAreCapitalised() {
        assertEquals("Hello world", format("hello world", "", paragraphStart = true))
        assertEquals("Hello world", format("  hello   world ", ""))
    }

    @Test fun joinsMidSentenceWithASpace() {
        assertEquals(" to the beach", format("to the beach", "We went"))
        assertEquals("to the beach", format("to the beach", "We went "))
    }

    @Test fun newSentenceAfterAFullStop() {
        assertEquals(" Then we ate", format("then we ate", "We swam."))
        assertEquals(" Ναι", format("ναι", "Πού;"))
    }

    @Test fun spaceBeforeAFollowingWord() {
        assertEquals(" the ", format("the", "We saw", "harbour"))
        assertEquals(" the", format("the", "We saw", " harbour"))
    }

    @Test fun punctuationAttachesToTheWordBefore() {
        assertEquals(", then", format(", then", "Halki"))
    }

    @Test fun greekKeepsItsAccentsWhenCapitalised() {
        assertEquals("Άργησε το λεωφορείο", format("άργησε το λεωφορείο", "", paragraphStart = true))
    }

    @Test fun bulletAndParagraphStartsBeginASentence() {
        assertEquals("Buy bread", format("buy bread", "• "))
        assertEquals("New day", format("new day", "Old text ", paragraphStart = true))
    }

    @Test fun emptyPhraseAddsNothing() {
        assertEquals("", format("   ", "a", "b"))
    }

    @Test fun languageNames() {
        assertEquals("Greek", DictationText.languageName("el", Locale.ENGLISH))
        assertEquals("English", DictationText.languageName("en-GB", Locale.ENGLISH))
    }

    private class FakeTarget(var value: String, var caretAt: Int = value.length) : DictationTarget {
        override val text: String get() = value
        override val caret: Int get() = caretAt
        override fun replace(start: Int, end: Int, with: String) {
            value = value.substring(0, start) + with + value.substring(end)
            caretAt = start + with.length
        }
    }

    @Test fun partialsReplaceEachOtherAndFinalsStay() {
        val t = FakeTarget("")
        val m = DictationMerger()
        m.partial(t, "hel")
        assertEquals("Hel", t.text)
        assertFalse(m.idle)
        m.partial(t, "hello wor")
        assertEquals("Hello wor", t.text)
        m.final(t, "hello world")
        assertEquals("Hello world", t.text)
        assertTrue(m.idle)
        m.partial(t, "and then")
        assertEquals("Hello world and then", t.text)
        m.final(t, "and then we swam")
        assertEquals("Hello world and then we swam", t.text)
    }

    @Test fun nextUtteranceFollowsTheCaret() {
        val t = FakeTarget("Ferry day. Slept.", caretAt = 10)
        val m = DictationMerger()
        m.final(t, "it was late")
        assertEquals("Ferry day. It was late Slept.", t.text)
    }

    @Test fun reanchorsWhenTheTextChangedUnderneath() {
        val t = FakeTarget("Start")
        val m = DictationMerger()
        m.partial(t, "one two")
        assertEquals("Start one two", t.text)
        t.value = "Changed"
        t.caretAt = 7
        m.partial(t, "one two three")
        assertEquals("Changed one two three", t.text)
    }

    @Test fun emptyResultLeavesTheTextAlone() {
        val t = FakeTarget("Hi.")
        val m = DictationMerger()
        m.final(t, "")
        assertEquals("Hi.", t.text)
        assertTrue(m.idle)
    }
}

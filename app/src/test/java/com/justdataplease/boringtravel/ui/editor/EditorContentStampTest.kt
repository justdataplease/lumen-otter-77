package com.justdataplease.boringtravel.ui.editor

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import com.mohamedrejeb.richeditor.model.HeadingStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EditorContentStampTest {
    @Test fun formattingWithoutTypingChangesTheAutosaveStamp() {
        val state = newState("<p>A memory</p>")
        state.selection = TextRange(0, state.annotatedString.length)
        val before = richContentStamp(state)
        state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        val bold = richContentStamp(state)
        assertEquals(before.text.text, bold.text.text)
        assertNotEquals(before, bold)
        state.setHeadingStyle(HeadingStyle.H2)
        assertNotEquals(bold, richContentStamp(state))
    }

    @Test fun changingAListWithoutTypingChangesTheAutosaveStamp() {
        val state = newState("<p>Pack a notebook</p>")
        val before = richContentStamp(state)
        state.toggleUnorderedList()
        assertNotEquals(before, richContentStamp(state))
    }
}

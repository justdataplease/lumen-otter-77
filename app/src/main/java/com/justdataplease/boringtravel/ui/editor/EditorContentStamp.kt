package com.justdataplease.boringtravel.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState

/** Immutable, already-computed editor values; observing them never encodes or parses HTML. */
internal data class RichContentStamp(
    val text: AnnotatedString,
    val heading: HeadingStyle,
    val span: SpanStyle,
    val ordered: Boolean,
    val unordered: Boolean,
)

internal fun richContentStamp(state: RichTextState) = RichContentStamp(
    state.annotatedString, state.currentHeadingStyle, state.currentSpanStyle, state.isOrderedList, state.isUnorderedList,
)

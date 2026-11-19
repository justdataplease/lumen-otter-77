package com.justdataplease.boringtravel.speech

import java.util.Locale

/** Text that dictation writes into: the editor's text area, or a plain string in tests. */
interface DictationTarget {
    val text: String
    /** Where the caret is (end of the selection). */
    val caret: Int
    /** Replaces [start, end) with [with] and puts the caret after it. */
    fun replace(start: Int, end: Int, with: String)
    /** True when [index] is the start of a paragraph (the editor separates paragraphs without a newline). */
    fun isParagraphStart(index: Int): Boolean = index == 0 || text.getOrNull(index - 1) == '\n'
}

/**
 * How spoken words join the text around them: a space where one is missing, a capital letter at the start of a
 * sentence, and no space before punctuation. Pure, so it is unit tested.
 */
object DictationText {
    private const val SENTENCE_END = ".!?…;" // U+037E is the Greek question mark
    private const val OPENERS = "([{\"'«“‘¿¡-–—/"
    private const val NO_SPACE_BEFORE = ".,;:!?…)]}»”’%;"
    private const val BULLETS = "•◦▪‣·"

    fun format(phrase: String, before: CharSequence, after: CharSequence, paragraphStart: Boolean): String {
        var p = phrase.trim().replace(Regex("\\s+"), " ")
        if (p.isEmpty()) return ""
        val prev = before.lastOrNull()
        val prevSolid = before.trimEnd().lastOrNull()
        val sentenceStart = paragraphStart || prevSolid == null || prevSolid in SENTENCE_END || prevSolid in BULLETS || prevSolid == '\n'
        if (sentenceStart) p = capitalizeFirst(p)
        val lead = prev != null && !prev.isWhitespace() && prev !in OPENERS && p.first() !in NO_SPACE_BEFORE && !paragraphStart
        val next = after.firstOrNull()
        val trail = next != null && (next.isLetterOrDigit() || next in OPENERS && next != '-' && next != '/')
        return buildString {
            if (lead) append(' ')
            append(p)
            if (trail) append(' ')
        }
    }

    /** Upper-cases the first letter only (title case keeps Greek accents: ά → Ά). */
    fun capitalizeFirst(s: String): String {
        val i = s.indexOfFirst { it.isLetter() }
        if (i < 0 || !s[i].isLowerCase()) return s
        return s.substring(0, i) + s[i].titlecaseChar() + s.substring(i + 1)
    }

    /** "el" → "Greek" (in [display]'s language), for the "Listening · Greek" pill. */
    fun languageName(tag: String, display: Locale): String {
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(display)
        return if (name.isBlank()) tag else name.replaceFirstChar { it.titlecase(display) }
    }
}

/**
 * Streams recognizer results into a [DictationTarget]. Each partial result replaces the words shown for the current
 * utterance; the final result fixes them and the next utterance starts after them (or at the caret, if the writer
 * moved it in between).
 */
class DictationMerger {
    private var start = -1
    private var shown = ""

    /** True between utterances (nothing of the current one is on screen). */
    val idle: Boolean get() = shown.isEmpty()

    fun partial(target: DictationTarget, hypothesis: String) = apply(target, hypothesis, final = false)
    fun final(target: DictationTarget, hypothesis: String) = apply(target, hypothesis, final = true)

    /** Forget the current utterance (dictation stopped). Words already written stay. */
    fun reset() {
        start = -1
        shown = ""
    }

    private fun apply(target: DictationTarget, hypothesis: String, final: Boolean) {
        val text = target.text
        val stillThere = start in 0..text.length && start + shown.length <= text.length &&
            text.regionMatches(start, shown, 0, shown.length)
        if (shown.isEmpty() || !stillThere) {
            start = target.caret.coerceIn(0, text.length)
            shown = ""
        }
        val before = text.substring(0, start)
        val after = text.substring(start + shown.length)
        val insert = DictationText.format(hypothesis, before, after, target.isParagraphStart(start))
        if (insert != shown) target.replace(start, start + shown.length, insert)
        if (final) {
            start += insert.length
            shown = ""
        } else {
            shown = insert
        }
    }
}

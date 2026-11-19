package com.justdataplease.boringtravel.widget

import kotlin.math.min

/** The buttons on the Quick bar, left to right. */
enum class QuickAction { Note, Camera, Voice }

/**
 * Where everything sits on the Quick bar at one widget size, in dp. Every width is fixed here, so the paper and
 * button bitmaps are drawn at exactly the size they are shown (nothing stretched).
 *
 * What gives way as the bar gets narrower: Voice first, then the stamp, then the context line; Note and Camera always
 * stay. At 2×1 the Note pill takes the room left beside Camera.
 */
data class QuickBarLayout(
    val width: Float,
    val barHeight: Float,
    val mark: Boolean,
    val context: Boolean,
    val actions: List<QuickAction>,
    val noteWidth: Float,
    /** Room for the two lines of text (0 without them). */
    val contextWidth: Float,
    /** Space before the first thing on the bar: a little more when the text starts it (no stamp). */
    val startPad: Float = PAD,
) {
    val voice: Boolean get() = QuickAction.Voice in actions

    companion object {
        const val PAD = 10f
        const val GAP = 8f
        const val BUTTON = 40f
        const val MARK = 32f
        const val MARK_GAP = 10f
        const val NOTE = 82f

        /** The bar's flat shadow lies inside the widget: the paper is this much narrower and lower. */
        const val SHADOW_X = 2f
        const val SHADOW_Y = 3f

        /** The bar's height: like the search bar, never taller than this, whatever the cell's height. */
        const val MAX_HEIGHT = 60f

        /** Less room than this and the context line is left out ("Day 4 · Naxos" needs about 95 dp). */
        const val MIN_CONTEXT = 92f

        /** The stamp and Voice are added only while the text keeps at least this much room. */
        const val COMFORT_CONTEXT = 108f

        /** The Note pill's widest, when it fills the small bar. */
        const val NOTE_MAX = 132f

        /** Extra space before the text when there is no stamp, so the words don't crowd the bar's edge. */
        const val TEXT_INDENT = 4f

        fun of(widthDp: Float, heightDp: Float): QuickBarLayout {
            val barHeight = min(MAX_HEIGHT, heightDp).coerceAtLeast(1f)
            val inner = widthDp - SHADOW_X - 2 * PAD
            // Note and Camera always: what is left goes to the text, then the stamp, then Voice.
            val rest = inner - (NOTE + GAP + BUTTON)
            if (rest < MIN_CONTEXT + GAP) {
                val note = (inner - GAP - BUTTON).coerceIn(BUTTON, NOTE_MAX)
                return QuickBarLayout(widthDp, barHeight, false, false, listOf(QuickAction.Note, QuickAction.Camera), note, 0f)
            }
            var text = rest - GAP
            val mark = text - (MARK + MARK_GAP) >= COMFORT_CONTEXT
            if (mark) text -= MARK + MARK_GAP
            val voice = text - (BUTTON + GAP) >= COMFORT_CONTEXT
            if (voice) text -= BUTTON + GAP
            val indent = if (mark) 0f else TEXT_INDENT
            val actions = listOfNotNull(QuickAction.Note, QuickAction.Camera, QuickAction.Voice.takeIf { voice })
            return QuickBarLayout(widthDp, barHeight, mark, true, actions, NOTE, text - indent, PAD + indent)
        }
    }
}

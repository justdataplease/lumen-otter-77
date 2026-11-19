package com.justdataplease.boringtravel.widget

import com.justdataplease.boringtravel.widget.QuickAction.Camera
import com.justdataplease.boringtravel.widget.QuickAction.Note
import com.justdataplease.boringtravel.widget.QuickAction.Voice
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.BUTTON
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.GAP
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.MARK
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.MARK_GAP
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.PAD
import com.justdataplease.boringtravel.widget.QuickBarLayout.Companion.SHADOW_X
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickBarLayoutTest {
    // Widths the Pixel launcher (4 columns on a 411 dp wide phone) gives a 1-row widget, in dp.
    private val twoByOne = 175f
    private val threeByOne = 268f
    private val fourByOne = 361f

    @Test
    fun smallShowsJustNoteAndCamera() {
        val l = QuickBarLayout.of(twoByOne, 70f)
        assertEquals(listOf(Note, Camera), l.actions)
        assertFalse(l.context)
        assertEquals(0f, l.contextWidth)
        // The Note pill takes the room beside Camera.
        assertTrue(l.noteWidth > QuickBarLayout.NOTE)
    }

    @Test
    fun threeByOneHasTheTripButNoVoice() {
        val l = QuickBarLayout.of(threeByOne, 70f)
        assertTrue(l.context)
        assertEquals(listOf(Note, Camera), l.actions)
        assertTrue(l.contextWidth >= QuickBarLayout.MIN_CONTEXT)
    }

    @Test
    fun fourByOneAddsTheStampAndVoice() {
        val l = QuickBarLayout.of(fourByOne, 70f)
        assertTrue(l.context)
        assertTrue(l.mark)
        assertEquals(listOf(Note, Camera, Voice), l.actions)
        assertTrue(l.contextWidth >= QuickBarLayout.COMFORT_CONTEXT)
    }

    @Test
    fun noteAndCameraAlwaysStayAndVoiceNeedsTheText() {
        var w = 110f
        while (w <= 600f) {
            val l = QuickBarLayout.of(w, 70f)
            assertEquals(listOf(Note, Camera), l.actions.take(2))
            if (l.voice) assertTrue("voice without the text at $w", l.context)
            w += 5f
        }
    }

    @Test
    fun widerNeverShowsLess() {
        var prev = QuickBarLayout.of(110f, 70f)
        var w = 115f
        while (w <= 600f) {
            val l = QuickBarLayout.of(w, 70f)
            if (prev.context) assertTrue("context lost at $w", l.context)
            if (prev.voice) assertTrue("voice lost at $w", l.voice)
            if (prev.context && prev.mark) assertTrue("stamp lost at $w", l.mark)
            prev = l
            w += 5f
        }
    }

    @Test
    fun everythingFitsInsideTheBar() {
        var w = 110f
        while (w <= 600f) {
            val l = QuickBarLayout.of(w, 70f)
            val used = (if (l.mark) MARK + MARK_GAP else 0f) +
                (if (l.context) l.contextWidth + GAP else 0f) +
                l.noteWidth + l.actions.drop(1).size * (GAP + BUTTON)
            assertTrue("overflows at $w: $used", used <= w - SHADOW_X - l.startPad - PAD + 0.01f)
            w += 5f
        }
    }

    @Test
    fun textStartsAWayInFromTheEdgeWithoutTheStamp() {
        val l = QuickBarLayout.of(threeByOne, 70f)
        assertFalse(l.mark)
        assertEquals(PAD + QuickBarLayout.TEXT_INDENT, l.startPad)
        assertEquals(PAD, QuickBarLayout.of(fourByOne, 70f).startPad)
        assertEquals(PAD, QuickBarLayout.of(twoByOne, 70f).startPad)
    }

    @Test
    fun barStaysASearchBarHeight() {
        assertEquals(QuickBarLayout.MAX_HEIGHT, QuickBarLayout.of(fourByOne, 110f).barHeight)
        assertEquals(52f, QuickBarLayout.of(fourByOne, 52f).barHeight)
    }
}

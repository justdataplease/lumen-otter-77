package com.justdataplease.boringtravel.ui.clip

import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.ClipTiming
import com.justdataplease.boringtravel.clip.ClipText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewMathTest {
    @Test fun fullScreenDrawsSharperThanInlineInTheClipsShape() {
        assertEquals(360 to 640, PreviewMath.size(ClipShape.Story, 360))
        assertEquals(540 to 960, PreviewMath.size(ClipShape.Story, PreviewMath.FULL_SHORT))
        assertEquals(540 to 540, PreviewMath.size(ClipShape.Square, PreviewMath.FULL_SHORT))
        assertEquals(960 to 540, PreviewMath.size(ClipShape.Wide, PreviewMath.FULL_SHORT))
        for (shape in ClipShape.entries) {
            val (w, h) = PreviewMath.size(shape, PreviewMath.FULL_SHORT)
            assertEquals(shape.aspect, w.toDouble() / h, 0.01)
        }
    }

    @Test fun thePreviewStepsAtTenFramesASecondAndLoops() {
        assertEquals(3, PreviewMath.nextFrame(0, 450, 30))
        assertEquals(449, PreviewMath.nextFrame(448, 450, 30))
        assertEquals(0, PreviewMath.nextFrame(449, 450, 30))
        assertEquals(1, PreviewMath.nextFrame(0, 450, 5))
    }

    @Test fun thePreviewShowsTheSameLengthAsItsSpeedChip() {
        // Argentina at ×2 ran 41.6 s: the chip said 0:42 and the preview, cutting frames down, 0:41.
        for (seconds in listOf(41.6, 34.2, 65.0, 6.0, 119.5)) {
            val frames = ClipTiming.frames(seconds, 10)
            assertEquals(ClipTiming.clock(seconds), ClipText.clock(frames, 10))
        }
        assertEquals("0:12 / 1:05", PreviewMath.clockMs(12_400, 65_000))
    }

    @Test fun thePreparedFramesStayWithinAMemoryBudgetFromTheHeapSize() {
        assertEquals(PreviewMath.MIN_BUDGET, PreviewMath.budgetBytes(96))
        assertEquals(256 * PreviewMath.MB / 6, PreviewMath.budgetBytes(256))
        assertEquals(PreviewMath.MAX_BUDGET, PreviewMath.budgetBytes(512))
        // A 540 × 960 frame is expected at about 78 KB.
        assertEquals(77_760L, PreviewMath.frameBytes(540, 960))
    }

    @Test fun thePreviewIsAsSmoothAsTheBudgetAllows() {
        assertEquals(2, PreviewMath.step(30, 15))
        assertEquals(3, PreviewMath.step(30, 10))
        assertEquals(4, PreviewMath.step(30, 8))
        assertEquals(1, PreviewMath.step(10, 15))
        assertEquals(450, PreviewMath.count(900, 2))
        assertEquals(451, PreviewMath.count(901, 2))
        val budget = 48 * PreviewMath.MB
        // 30 s at 15 fps (450 frames, 35 MB); 60 s at 10 fps (600, 47 MB); 2 minutes at 5 fps.
        assertEquals(2, PreviewMath.previewStep(900, 30, 540, 960, budget))
        assertEquals(3, PreviewMath.previewStep(1800, 30, 540, 960, budget))
        assertEquals(6, PreviewMath.previewStep(3600, 30, 540, 960, budget))
        for (frames in listOf(180, 900, 1800, 2700, 3600)) {
            val step = PreviewMath.previewStep(frames, 30, 540, 960, budget)
            val fits = PreviewMath.count(frames, step) * PreviewMath.frameBytes(540, 960) <= budget
            assertTrue("$frames frames, step $step", fits || step == PreviewMath.step(30, PreviewMath.PREVIEW_FPS.last()))
        }
        // Too long even at 5 fps: the slowest rate, the rest drawn live.
        assertEquals(6, PreviewMath.previewStep(30_000, 30, 540, 960, budget))
    }

    @Test fun scrubbingLandsOnTheNearestPreparedFrame() {
        assertEquals(0, PreviewMath.index(0, 3, 100))
        assertEquals(1, PreviewMath.index(2, 3, 100))
        assertEquals(1, PreviewMath.index(4, 3, 100))
        assertEquals(99, PreviewMath.index(10_000, 3, 100))
        assertEquals(1, PreviewMath.workers(2))
        assertEquals(2, PreviewMath.workers(4))
        assertEquals(3, PreviewMath.workers(8))
    }

    @Test fun playStartsOnlyWhenTheRestIsReadyInTime() {
        // All prepared: play.
        assertTrue(PreviewMath.canStart(buffered = 0, remaining = 0, renderFps = 0.0, playFps = 15.0, minBuffer = 15))
        // Preparing faster than playing: a second's buffer is enough.
        assertTrue(PreviewMath.canStart(15, 400, renderFps = 30.0, playFps = 15.0, minBuffer = 15))
        assertFalse(PreviewMath.canStart(10, 400, renderFps = 30.0, playFps = 15.0, minBuffer = 15))
        // Preparing at half the speed of playing: about half must be ready first.
        assertFalse(PreviewMath.canStart(100, 300, renderFps = 7.5, playFps = 15.0, minBuffer = 15))
        assertTrue(PreviewMath.canStart(250, 150, renderFps = 7.5, playFps = 15.0, minBuffer = 15))
        // Nothing measured yet: wait.
        assertFalse(PreviewMath.canStart(20, 100, renderFps = 0.0, playFps = 15.0, minBuffer = 15))
    }
}

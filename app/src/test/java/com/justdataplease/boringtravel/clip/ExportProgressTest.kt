package com.justdataplease.boringtravel.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProgressTest {
    private fun walk(hasSound: Boolean): List<Float> {
        val steps = (0..200).map { it / 200f }
        return steps.map { ExportProgress.overall(ExportStage.Picture, it, hasSound) } +
            steps.map { ExportProgress.overall(ExportStage.Sound, it, hasSound) } +
            ExportProgress.overall(ExportStage.Finishing, 0f, hasSound) +
            ExportProgress.overall(ExportStage.Finishing, 1f, hasSound)
    }

    @Test fun theBarNeverGoesBackAndNeverReachesTheEndBeforeTheVideoIsReady() {
        for (hasSound in listOf(true, false)) {
            val bar = walk(hasSound)
            for (i in 1 until bar.size) assertTrue("$hasSound step $i: ${bar[i - 1]} → ${bar[i]}", bar[i] >= bar[i - 1])
            assertTrue(bar.all { it in 0f..ExportProgress.LAST })
            assertEquals(0f, bar.first(), 0f)
            assertEquals(ExportProgress.LAST, bar.last(), 0f)
            // Nothing ever reads 100 %.
            assertTrue(bar.all { (it * 100).toInt() < 100 && Math.round(it * 100) < 100 })
        }
    }

    @Test fun theFramesTakeMostOfTheBarAndTheRestIsLeftForTheSound() {
        assertEquals(0.95f, ExportProgress.pictureShare(hasSound = true), 0f)
        assertEquals(0.97f, ExportProgress.pictureShare(hasSound = false), 0f)
        for (hasSound in listOf(true, false)) {
            val share = ExportProgress.pictureShare(hasSound)
            assertTrue(share < ExportProgress.LAST)
            assertEquals(share, ExportProgress.overall(ExportStage.Picture, 1f, hasSound), 1e-6f)
            assertEquals(share, ExportProgress.overall(ExportStage.Sound, 0f, hasSound), 1e-6f)
            assertEquals(share / 2, ExportProgress.overall(ExportStage.Picture, 0.5f, hasSound), 1e-6f)
            assertEquals(ExportProgress.LAST, ExportProgress.overall(ExportStage.Sound, 1f, hasSound), 1e-6f)
        }
        // Out-of-range parts stay on the bar.
        assertEquals(0f, ExportProgress.overall(ExportStage.Picture, -1f, true), 0f)
        assertEquals(0f, ExportProgress.overall(ExportStage.Picture, Float.NaN, true), 0f)
        assertEquals(ExportProgress.LAST, ExportProgress.overall(ExportStage.Sound, 7f, true), 1e-6f)
    }

    private class Seen {
        val values = ArrayList<Pair<Float, ExportStage>>()
        fun bar(hasSound: Boolean) = ExportBar(hasSound) { p, s -> values += p to s }
    }

    @Test fun theBarShowsEachWholePercentOnceAndTheSoundOnlyAfterTheFrames() {
        val seen = Seen()
        val bar = seen.bar(hasSound = true)
        // The sound is made alongside the frames: half done before the frames are, but the bar still shows the frames.
        for (f in 0..1000) {
            bar.frames(f / 1000f)
            if (f == 300) bar.sound(0.5f)
            if (f == 600) bar.sound(0.85f)
        }
        assertTrue(seen.values.all { it.second == ExportStage.Picture })
        // One publication per whole percent, not one per frame.
        assertEquals(seen.values.map { (it.first * 100).toInt() }.distinct().size, seen.values.size)
        assertEquals(96, seen.values.size) // 0 % … 95 %
        bar.framesDone()
        // Straight to where the sound has got to.
        val (p, stage) = seen.values.last()
        assertEquals(ExportStage.Sound, stage)
        assertEquals(ExportProgress.overall(ExportStage.Sound, 0.85f, true), p, 1e-6f)
        // A late, smaller report never moves the bar back; the attached sound ends at the last percent.
        bar.sound(0.2f)
        bar.sound(1f)
        val all = seen.values.map { it.first }
        for (i in 1 until all.size) assertTrue(all[i] >= all[i - 1])
        assertEquals(ExportProgress.LAST, all.last(), 1e-6f)
        assertTrue(all.all { (it * 100).toInt() < 100 })
        // Frames reported after they are done are ignored.
        val count = seen.values.size
        bar.frames(0.5f)
        assertEquals(count, seen.values.size)
    }

    @Test fun withoutSoundTheBarGoesFromTheFramesToFinishing() {
        val seen = Seen()
        val bar = seen.bar(hasSound = false)
        for (f in 0..100) bar.frames(f / 100f)
        assertEquals(0.97f, seen.values.last().first, 1e-6f)
        bar.framesDone()
        assertEquals(ExportProgress.LAST to ExportStage.Finishing, seen.values.last())
        bar.sound(1f)
        bar.finishing()
        // Nothing new: the same stage and percent.
        assertEquals(ExportStage.Finishing, seen.values.last().second)
        assertEquals(1, seen.values.count { it.second == ExportStage.Finishing })
    }
}

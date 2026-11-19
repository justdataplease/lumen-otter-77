package com.justdataplease.boringtravel.ui.clip

import com.justdataplease.boringtravel.clip.ClipShape
import com.justdataplease.boringtravel.clip.ClipTiming
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The clip screen's small sums, kept apart from Compose so they can be tested. */
internal object PreviewMath {
    /** The preview's short side in pixels, live or prepared (540 × 960 for 9:16). */
    const val FULL_SHORT = 540

    /** Preview frame rates tried, smoothest first; the first whose frames fit the memory budget is used. */
    val PREVIEW_FPS = listOf(15, 10, 8, 6, 5)

    /** What a prepared frame takes as a JPEG (quality [JPEG_QUALITY]), per pixel: a map frame is mostly 0.08–0.15. */
    const val JPEG_BYTES_PER_PX = 0.15
    const val JPEG_QUALITY = 85
    const val MB = 1024L * 1024L
    const val MIN_BUDGET = 24 * MB
    const val MAX_BUDGET = 64 * MB

    /** Wait this long after the last option change before preparing again. */
    const val DEBOUNCE_MS = 350L

    /** Width and height of a preview of [shape] with a short side of [shortSide] pixels. */
    fun size(shape: ClipShape, shortSide: Int): Pair<Int, Int> = when (shape) {
        ClipShape.Story -> shortSide to shortSide * 16 / 9
        ClipShape.Portrait -> shortSide to shortSide * 5 / 4
        ClipShape.Square -> shortSide to shortSide
        ClipShape.Wide -> shortSide * 16 / 9 to shortSide
    }

    /** The live preview plays at about 10 frames a second in step with the clip, looping to the start after the end. */
    fun nextFrame(frame: Int, frameCount: Int, fps: Int): Int {
        val last = (frameCount - 1).coerceAtLeast(0)
        return if (frame >= last) 0 else (frame + (fps / 10).coerceAtLeast(1)).coerceAtMost(last)
    }

    /** "0:12 / 1:05" for a position and a length in milliseconds. */
    fun clockMs(positionMs: Int, lengthMs: Int): String =
        "${ClipTiming.clock(positionMs / 1000.0)} / ${ClipTiming.clock(lengthMs / 1000.0)}"

    // --- Prepared preview: frames rendered ahead, kept as JPEGs, played at a steady rate ----------------------------

    /** The prepared frames' memory: a sixth of the app's heap class, within 24–64 MB. */
    fun budgetBytes(memoryClassMb: Int): Long = (memoryClassMb.toLong() * MB / 6).coerceIn(MIN_BUDGET, MAX_BUDGET)

    /** Clip frames per preview frame at [previewFps] for a clip at [fps] (never faster than the clip). */
    fun step(fps: Int, previewFps: Int): Int = ((fps + previewFps - 1) / previewFps.coerceAtLeast(1)).coerceAtLeast(1)

    /** Preview frames for a clip of [frameCount] frames, one every [step]. */
    fun count(frameCount: Int, step: Int): Int = ((frameCount + step - 1) / step).coerceAtLeast(1)

    /** The expected size of one prepared [width] × [height] frame. */
    fun frameBytes(width: Int, height: Int): Long = (width.toLong() * height * JPEG_BYTES_PER_PX).roundToLong()

    /**
     * The step for the smoothest preview whose frames fit [budget]: 15 fps when they do, down to 5 fps for a long
     * clip (a 2-minute 9:16 clip in 48 MB). Frames past the budget are drawn live instead.
     */
    fun previewStep(frameCount: Int, fps: Int, width: Int, height: Int, budget: Long): Int {
        val each = frameBytes(width, height)
        for (pf in PREVIEW_FPS) {
            val step = step(fps, pf)
            if (count(frameCount, step) * each <= budget) return step
        }
        return step(fps, PREVIEW_FPS.last())
    }

    /** The prepared frame nearest clip frame [frame]. */
    fun index(frame: Int, step: Int, count: Int): Int = (frame.toDouble() / step).roundToInt().coerceIn(0, count - 1)

    /** Background threads that prepare frames: the cores less two (the screen and the encoder keep theirs), 1–3. */
    fun workers(cores: Int): Int = (cores - 2).coerceIn(1, 3)

    /**
     * Whether playing can start without stalling: [buffered] frames are ready in a row from the playhead, [remaining]
     * are still to render at [renderFps], and playing takes [playFps]. Frame j (counted from the playhead) is ready
     * after (j − buffered) / renderFps and shown at j / playFps; the last frame is the tightest when rendering is the
     * slower, so start once remaining / renderFps ≤ 0.9 × (buffered + remaining) / playFps, with at least [minBuffer]
     * ready.
     */
    fun canStart(buffered: Int, remaining: Int, renderFps: Double, playFps: Double, minBuffer: Int): Boolean {
        if (remaining <= 0) return true
        if (buffered < minBuffer || renderFps <= 0.0) return false
        return remaining / renderFps <= 0.9 * (buffered + remaining) / playFps
    }
}

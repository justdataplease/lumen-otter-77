package com.justdataplease.boringtravel.clip

import kotlin.math.max

/** What an export is doing: drawing and encoding the frames, adding the sound, or the last writes. */
enum class ExportStage { Picture, Sound, Finishing }

/**
 * The export's one progress number across its stages (owner, on 1.4.0: "during export it lags at 100% for a bit"):
 * the frames take most of the bar, the sound and the last writes the rest, and it never shows 100 % before the
 * video is ready.
 */
object ExportProgress {
    /** The most the bar shows before the video is ready. */
    const val LAST = 0.99f

    /**
     * The share of the bar the frames take; the rest is the sound and the last writes. The sound is made while the
     * frames render, so after them only putting it under the picture is left.
     */
    fun pictureShare(hasSound: Boolean): Float = if (hasSound) 0.95f else 0.97f

    /** One number for the bar: never past [LAST], never backwards as the stages go by. */
    fun overall(stage: ExportStage, part: Float, hasSound: Boolean): Float {
        val p = if (part.isNaN()) 0f else part.coerceIn(0f, 1f)
        val share = pictureShare(hasSound)
        return when (stage) {
            ExportStage.Picture -> p * share
            ExportStage.Sound -> share + p * (LAST - share)
            ExportStage.Finishing -> LAST
        }.coerceAtMost(LAST)
    }
}

/**
 * The bar as the export's stages report to it, from any thread: the frames' part until they are written, then the
 * sound's (made alongside the frames, so it may be well on already), then the last writes. [publish] gets the
 * number and the stage on a new stage or a new whole percent only, and never a smaller number than before.
 */
class ExportBar(private val hasSound: Boolean, private val publish: (progress: Float, stage: ExportStage) -> Unit) {
    private var stage = ExportStage.Picture
    private var soundPart = 0f
    private var shown: ExportStage? = null
    private var shownPercent = -1
    private var shownProgress = 0f

    /** The frames' part (0–1); ignored once they are written. */
    @Synchronized fun frames(part: Float) {
        if (stage == ExportStage.Picture) show(ExportStage.Picture, part)
    }

    /** The sound's part (0–1) at any time; it shows once the frames are written. */
    @Synchronized fun sound(part: Float) {
        soundPart = max(soundPart, part)
        if (stage == ExportStage.Sound) show(ExportStage.Sound, soundPart)
    }

    /** The frames are written: on to the sound (where it has got to), or to the last writes when there is none. */
    @Synchronized fun framesDone() {
        stage = if (hasSound) ExportStage.Sound else ExportStage.Finishing
        show(stage, soundPart)
    }

    /** Only the last writes are left. */
    @Synchronized fun finishing() {
        stage = ExportStage.Finishing
        show(stage, 1f)
    }

    private fun show(at: ExportStage, part: Float) {
        val p = max(ExportProgress.overall(at, part, hasSound), shownProgress)
        val percent = (p * 100).toInt()
        if (at == shown && percent <= shownPercent) return
        shown = at
        shownPercent = percent
        shownProgress = p
        publish(p, at)
    }
}

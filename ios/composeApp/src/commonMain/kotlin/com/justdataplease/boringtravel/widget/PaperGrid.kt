package com.justdataplease.boringtravel.widget

import kotlin.math.floor

/*
 * A copy, word for word, of the PaperGrid object in the Android app's widget/WidgetArt.kt (the rest of that file draws
 * with android.graphics, so it can't be read in). Keep the two in step; the Android app's PaperGridTest runs against
 * this one.
 */

/** Where the dots or rules of a strip of paper go, centred so the pattern sits evenly on a short bar. */
object PaperGrid {
    /** Paper spacing on the widget, in dp: a little tighter than the screens' so a 60 dp bar still reads as paper. */
    const val DOT_STEP = 13f
    const val DOT_RADIUS = 0.95f
    const val LINE_STEP = 15f
    const val GRID_STEP = 12f
    const val RULE = 0.75f

    /** Dot centres along [length]: [step] apart, the outer ones the same distance from both ends. */
    fun dots(length: Float, step: Float): List<Float> {
        if (step <= 0f || length <= 0f) return emptyList()
        val n = floor(length / step).toInt().coerceAtLeast(1)
        val first = (length - (n - 1) * step) / 2f
        return List(n) { first + it * step }
    }

    /** Rules across [length]: [step] apart, centred, none closer than half a step to either end. */
    fun rules(length: Float, step: Float): List<Float> {
        if (step <= 0f || length <= step) return emptyList()
        val n = floor(length / step).toInt()
        val first = (length - (n - 1) * step) / 2f
        return List(n) { first + it * step }.filter { it >= step / 2f - 0.01f && it <= length - step / 2f + 0.01f }
    }
}

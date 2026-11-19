package com.justdataplease.boringtravel.ui.clip

import android.os.SystemClock
import android.util.Log

/**
 * How long an editor change takes to reach the screen, in logcat under "ClipPerf" (owner, 2026-11-09: "when I select
 * something it create a whole clip, no need, I can always go back"): the change is stamped where the view-model takes
 * it ([edit]), and each stage after it (the new plan, the map's pictures, the first picture, the prepared frames) logs
 * how long after that stamp it came.
 */
internal object EditorPerf {
    private const val TAG = "ClipPerf"
    @Volatile private var at = 0L
    @Volatile private var what = "open"

    /** A change was made: [label] says which ("style", "edits", "zoom"…). */
    fun edit(label: String) {
        at = SystemClock.elapsedRealtime()
        what = label
    }

    /** Milliseconds since the last change. */
    fun since(): Long = SystemClock.elapsedRealtime() - at

    fun log(event: String) {
        Log.d(TAG, "$event · ${since()} ms after $what")
    }
}

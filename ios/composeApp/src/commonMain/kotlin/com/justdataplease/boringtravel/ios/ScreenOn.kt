package com.justdataplease.boringtravel.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** Keeps the phone from locking itself while [on] (iPhone: UIApplication's idleTimerDisabled; the desktop: nothing). */
expect fun setScreenKeptOn(on: Boolean)

/**
 * The screen stays on while [on] is true and this is on screen (the Android app's View.keepScreenOn while a note's video
 * is prepared, so the phone doesn't stop the app halfway).
 */
@Composable
fun KeepScreenOn(on: Boolean) {
    DisposableEffect(on) {
        if (on) setScreenKeptOn(true)
        onDispose { if (on) setScreenKeptOn(false) }
    }
}
